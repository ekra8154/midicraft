package com.fastnoteblocks.client.composer;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.NoteSequence.Step;
import com.fastnoteblocks.NoteSequence.StepType;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Persistent, MIDI-shaped representation of a composition.
 *
 * <p>The legacy sequence text remains a projection used by the in-world builder.
 * Composer notes deliberately retain pitches outside the note-block range.</p>
 */
public record ComposerProject(
	String name,
	int ppq,
	int tempoMicrosPerQuarter,
	List<Layer> layers,
	int activeLayerIndex,
	long nextNoteId,
	long endTick,
	int speedQuarters
) {
	public static final int DEFAULT_PPQ = 480;
	public static final int DEFAULT_TEMPO_MICROS_PER_QUARTER = 500_000;
	/**
	 * Converting splits a layer once per distinct octave shift its notes need, so a wide-range
	 * import can need many times its original layer count. The cap is a guard against a runaway
	 * layout, not a design limit -- the layer list collapses and scrolls.
	 */
	public static final int MAX_LAYERS = 128;
	public static final int NOTE_BLOCK_BASE_MIDI_NOTE = 54;
	public static final int NOTE_BLOCK_MAX_MIDI_NOTE = NOTE_BLOCK_BASE_MIDI_NOTE + NotePitch.PITCH_COUNT - 1;
	public static final long DEFAULT_NOTE_DURATION_TICKS = DEFAULT_PPQ / 4L;
	public static final int MIN_SPEED_QUARTERS = 1;
	public static final int MAX_SPEED_QUARTERS = 32;
	public static final int DEFAULT_SPEED_QUARTERS = 4;

	public ComposerProject {
		name = name == null || name.isBlank() ? "Untitled sequence" : name.trim();
		ppq = Math.max(1, ppq);
		tempoMicrosPerQuarter = Math.max(1, tempoMicrosPerQuarter);
		layers = normalizeLayers(layers);
		activeLayerIndex = Math.max(0, Math.min(layers.size() - 1, activeLayerIndex));
		long highestId = layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(NoteEvent::id)
			.max()
			.orElse(0L);
		nextNoteId = Math.max(highestId + 1L, nextNoteId);
		speedQuarters = speedQuarters <= 0
			? DEFAULT_SPEED_QUARTERS
			: Math.max(MIN_SPEED_QUARTERS, Math.min(MAX_SPEED_QUARTERS, speedQuarters));
		// The end marker can sit past the last note but never before it: placing a note beyond the
		// end drags the end along, which is the whole invariant expressed in one line. Zero means a
		// document saved before the marker existed, so it falls back to the content it describes.
		long lastNoteStart = layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(NoteEvent::startTick)
			.max()
			.orElse(-1L);
		endTick = lastNoteStart < 0L
			? (endTick > 0L ? endTick : ppq * 4L)
			: Math.max(endTick, lastNoteStart);
	}

	/**
	 * Rebuilds with new layers, keeping everything the caller did not mean to change.
	 *
	 * <p>Every edit funnels through here so the compact constructor's invariants -- the end marker
	 * floor in particular -- apply to all of them without each method remembering to.</p>
	 */
	private ComposerProject with(List<Layer> updatedLayers, int active, long nextId) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updatedLayers, active, nextId,
			endTick, speedQuarters);
	}

	public record NoteEvent(long id, int midiNote, long startTick, long durationTicks, int velocity) {
		public NoteEvent {
			id = Math.max(1L, id);
			midiNote = Math.max(0, Math.min(127, midiNote));
			startTick = Math.max(0L, startTick);
			durationTicks = Math.max(1L, durationTicks);
			velocity = Math.max(1, Math.min(127, velocity));
		}

		public boolean isBuildable() {
			return midiNote >= NOTE_BLOCK_BASE_MIDI_NOTE && midiNote <= NOTE_BLOCK_MAX_MIDI_NOTE;
		}

		public int noteBlockPitch() {
			return midiNote - NOTE_BLOCK_BASE_MIDI_NOTE;
		}

		public NoteEvent movedTo(long tick, int note) {
			return new NoteEvent(id, note, tick, durationTicks, velocity);
		}
	}

	public record Layer(
		String name,
		String instrument,
		boolean muted,
		boolean buildEnabled,
		boolean visible,
		List<NoteEvent> notes
	) {
		public Layer {
			name = name == null || name.isBlank() ? "Layer" : name.trim();
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			notes = notes == null
				? List.of()
				: notes.stream()
					.filter(java.util.Objects::nonNull)
					.sorted(Comparator.comparingLong(NoteEvent::startTick)
						.thenComparingInt(NoteEvent::midiNote)
						.thenComparingLong(NoteEvent::id))
					.toList();
		}

		public Layer withNotes(List<NoteEvent> value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, value);
		}

		public Layer withName(String value) {
			return new Layer(value, instrument, muted, buildEnabled, visible, notes);
		}

		public Layer withInstrument(String value) {
			return new Layer(name, value, muted, buildEnabled, visible, notes);
		}

		public Layer withMuted(boolean value) {
			return new Layer(name, instrument, value, buildEnabled, visible, notes);
		}

		public Layer withBuildEnabled(boolean value) {
			return new Layer(name, instrument, muted, value, visible, notes);
		}

		public Layer withVisible(boolean value) {
			return new Layer(name, instrument, muted, buildEnabled, value, notes);
		}
	}

	public record MinecraftConversion(
		ComposerProject project,
		int shiftedNotes,
		int addedLayers,
		boolean tempoChanged,
		double tempoFactor,
		int mergedRepeats,
		int duplicateLayers,
		int duplicateLayerNotes
	) {
		/**
		 * How much slower the converted song plays. Greater than 1 means the source was faster than
		 * redstone can represent — a repeater cannot delay less than one tick, so a song wanting
		 * more than 10 events per second has to be stretched to fit.
		 */
		public boolean slowedDown() {
			return tempoFactor > 1.01;
		}
	}

	public static ComposerProject empty(String name) {
		return new ComposerProject(name, DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Track 1", "HARP", false, true, true, List.of())), 0, 1L,
			DEFAULT_PPQ * 4L, DEFAULT_SPEED_QUARTERS);
	}

	/**
	 * Reads a sequence's text back into a composition, one layer per line.
	 *
	 * <p>Lines are parallel, not consecutive. Each is an independent bus starting at tick zero,
	 * which is what a sequence's tracks are and therefore what "Copy sequence as text" writes out.
	 * Feeding the whole thing through the single-timeline tokenizer would splice the layers
	 * end-to-end instead, turning a chord into an arpeggio.</p>
	 */
	public static ComposerProject fromSequenceText(String name, String text, String instrument) {
		List<SequenceTrack> tracks = new ArrayList<>();
		for (String line : (text == null ? "" : text).split("\\R")) {
			if (!line.isBlank()) {
				tracks.add(parseSequenceLine(line, tracks.size() + 1, instrument));
			}
		}
		if (tracks.isEmpty()) {
			tracks.add(new SequenceTrack("Layer 1", "", instrument, 0));
		}
		return fromSequenceTracks(name, tracks, 0, DEFAULT_SPEED_QUARTERS);
	}

	/**
	 * Renders one layer as a line of a copied sequence: {@code Name [INSTRUMENT]: 0, 5d, 4}.
	 *
	 * <p>The header is written always and read optionally, so hand-typed text can be as bare as
	 * {@code 0, 5d, 4} while a copy still round-trips with its names and instruments intact.</p>
	 */
	public static String toSequenceLine(String layerName, String instrument, String sequence) {
		String label = layerName == null || layerName.isBlank() ? "Layer" : layerName.trim();
		String sound = instrument == null || instrument.isBlank() ? "HARP" : instrument;
		return label + " [" + sound + "]: " + sequence;
	}

	/**
	 * Reads one line, with or without its header.
	 *
	 * <p>Splits on the last colon rather than the first, because a colon cannot occur in sequence
	 * text but can easily occur in a layer's name. A line with no colon at all is bare sequence:
	 * the layer is numbered and takes the default instrument.</p>
	 */
	private static SequenceTrack parseSequenceLine(String line, int number, String fallbackInstrument) {
		String defaultInstrument = fallbackInstrument == null || fallbackInstrument.isBlank()
			? "HARP"
			: fallbackInstrument;
		int split = line.lastIndexOf(':');
		if (split < 0) {
			return new SequenceTrack("Layer " + number, line.trim(), defaultInstrument, 0);
		}
		String header = line.substring(0, split).trim();
		String sequence = line.substring(split + 1).trim();
		String instrument = defaultInstrument;
		int open = header.lastIndexOf('[');
		int close = header.lastIndexOf(']');
		if (open >= 0 && close > open) {
			String named = header.substring(open + 1, close).trim();
			if (!named.isEmpty()) {
				instrument = named.toUpperCase(java.util.Locale.ROOT);
			}
			header = header.substring(0, open).trim();
		}
		return new SequenceTrack(header.isEmpty() ? "Layer " + number : header,
			sequence, instrument, 0);
	}

	public static ComposerProject fromSequenceTracks(
		String name,
		List<SequenceTrack> tracks,
		int activeTrackIndex,
		int delayScaleQuarters
	) {
		if (tracks == null || tracks.isEmpty()) {
			return empty(name);
		}
		List<Layer> layers = new ArrayList<>();
		long nextId = 1L;
		// Where each track's text runs out, trailing delays included. Text puts its delays between
		// events, so a delay after the last note -- or a track that is nothing but delays, like
		// "4d, 4d, 4d" -- has nowhere to live unless the end marker holds it.
		long parsedEnd = 0L;
		for (SequenceTrack track : tracks) {
			List<NoteEvent> notes = new ArrayList<>();
			long time = 0L;
			for (Step step : parseForProjection(track.sequence(), delayScaleQuarters)) {
				if (step.type() == StepType.REPEATER) {
					time += minecraftTickToComposerTick(
						step.value(), DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER
					);
				} else {
					notes.add(new NoteEvent(nextId++, NOTE_BLOCK_BASE_MIDI_NOTE + step.value(), time,
						DEFAULT_NOTE_DURATION_TICKS, 96));
				}
			}
			parsedEnd = Math.max(parsedEnd, time);
			layers.add(new Layer(track.name(), track.instrument(), "MUTE".equals(track.instrument()),
				track.buildEnabled(), true, notes));
		}
		return new ComposerProject(name, DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER,
			layers, activeTrackIndex, nextId, parsedEnd, DEFAULT_SPEED_QUARTERS);
	}

	/**
	 * Parses sequence text for this derived view, treating unparseable text as empty.
	 *
	 * <p>Track text is edited a keystroke at a time and every keystroke syncs the config, so a
	 * half-typed entry like {@code "0, 2d,"} is a normal transient state rather than an error.
	 * The text itself stays the source of truth in the track, so this projection fills back in as
	 * soon as it parses again. Matches how the in-world builder already degrades on invalid text.</p>
	 */
	private static List<Step> parseForProjection(String sequence, int delayScaleQuarters) {
		if (sequence == null || sequence.isBlank()) {
			return List.of();
		}
		try {
			return NoteSequence.parse(sequence, delayScaleQuarters);
		} catch (IllegalArgumentException stillBeingTyped) {
			return List.of();
		}
	}

	/**
	 * Projects chosen layers into a build sequence: the flat timeline of notes and repeaters that
	 * gets placed, whether by command or by hand.
	 *
	 * <p>Only the chosen layers appear at all, rather than appearing switched off. A sequence is a
	 * decision that has already been taken, so it should not carry the layers you decided against.
	 * Positions start at zero because each projection replaces the last -- the sequence is a
	 * snapshot of one moment, not something accumulated across visits.</p>
	 *
	 * @param layerIndices layers to include, or empty for every layer marked for building
	 */
	/**
	 * What a note sounds like and when, which is all a note block can express.
	 *
	 * <p>Velocity and duration are deliberately not part of it. A note block has no volume and no
	 * sustain, so two notes agreeing on these three things build as one sound played twice.</p>
	 */
	public record NoteSound(String instrument, int midiNote, long startTick) {
		public static NoteSound of(Layer layer, NoteEvent note) {
			return new NoteSound(layer.instrument(), note.midiNote(), note.startTick());
		}
	}

	/**
	 * @param dedupeIdentical drop a note when an earlier layer already plays that sound at that
	 *     instant. Nothing is deleted -- the note stays in the composition and comes back the
	 *     moment the layers stop agreeing, which is what changing one layer's instrument does.
	 */
	public List<SequenceTrack> toSequenceTracks(Set<Integer> layerIndices, boolean dedupeIdentical) {
		List<SequenceTrack> result = new ArrayList<>();
		Set<NoteSound> heard = dedupeIdentical ? new java.util.HashSet<>() : null;
		for (int index = 0; index < layers.size(); index++) {
			Layer layer = layers.get(index);
			// Mute is about listening, not building. Once solo exists, muting a layer to hear
			// around it would otherwise drop it out of the build without saying so.
			boolean chosen = layerIndices == null || layerIndices.isEmpty()
				? layer.buildEnabled()
				: layerIndices.contains(index);
			if (!chosen) {
				continue;
			}
			Layer projected = heard == null ? layer : withoutAlreadyHeard(layer, heard);
			result.add(new SequenceTrack(layer.name(), toText(projected), layer.instrument(), 0, true));
		}
		return List.copyOf(result);
	}

	private static Layer withoutAlreadyHeard(Layer layer, Set<NoteSound> heard) {
		List<NoteEvent> kept = new ArrayList<>(layer.notes().size());
		for (NoteEvent note : layer.notes()) {
			if (heard.add(NoteSound.of(layer, note))) {
				kept.add(note);
			}
		}
		return kept.size() == layer.notes().size() ? layer : layer.withNotes(kept);
	}

	public ComposerProject withLayer(int index, Layer layer) {
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(Math.max(0, Math.min(updated.size() - 1, index)), layer);
		return with(updated, activeLayerIndex, nextNoteId);
	}

	public ComposerProject withActiveLayer(int index) {
		return with(layers, index, nextNoteId);
	}

	public ComposerProject addLayer() {
		if (layers.size() >= MAX_LAYERS) {
			return this;
		}
		List<Layer> updated = new ArrayList<>(layers);
		int number = updated.size() + 1;
		updated.add(new Layer("Layer " + number, "HARP", false, true, true, List.of()));
		return with(updated, updated.size() - 1, nextNoteId);
	}

	public ComposerProject moveNotesToLayer(Set<Long> ids, int targetLayer) {
		if (ids == null || ids.isEmpty()) {
			return withActiveLayer(targetLayer);
		}
		int target = Math.max(0, Math.min(layers.size() - 1, targetLayer));
		Set<Long> selected = new LinkedHashSet<>(ids);
		List<NoteEvent> moving = layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selected.contains(note.id()))
			.toList();
		List<Layer> updated = new ArrayList<>();
		for (Layer layer : layers) {
			updated.add(layer.withNotes(layer.notes().stream()
				.filter(note -> !selected.contains(note.id()))
				.toList()));
		}
		List<NoteEvent> targetNotes = new ArrayList<>(updated.get(target).notes());
		targetNotes.addAll(moving);
		updated.set(target, updated.get(target).withNotes(targetNotes));
		return with(updated, target, nextNoteId);
	}

	/**
	 * Folds every selected layer into the lowest-numbered one, which keeps its name, instrument and
	 * flags. Notes are re-sorted by the layer constructor, so overlapping material interleaves.
	 */
	public ComposerProject mergeLayers(Set<Integer> layerIndices) {
		if (layerIndices == null || layerIndices.size() < 2) {
			return this;
		}
		List<Integer> sorted = layerIndices.stream()
			.filter(index -> index >= 0 && index < layers.size())
			.distinct()
			.sorted()
			.toList();
		if (sorted.size() < 2) {
			return this;
		}
		int target = sorted.getFirst();
		List<NoteEvent> merged = new ArrayList<>();
		for (int index : sorted) {
			merged.addAll(layers.get(index).notes());
		}
		List<Layer> updated = new ArrayList<>();
		int mergedIndex = 0;
		for (int index = 0; index < layers.size(); index++) {
			if (index == target) {
				mergedIndex = updated.size();
				updated.add(layers.get(index).withNotes(merged));
			} else if (!sorted.contains(index)) {
				updated.add(layers.get(index));
			}
		}
		return with(updated, mergedIndex, nextNoteId);
	}

	/**
	 * Removes the given layers, leaving the selection on whatever slid up into the first gap.
	 *
	 * <p>Deleting everything leaves one empty layer rather than none, because a composition with no
	 * layers has nowhere to put the next note -- the constructor would put one back anyway, and
	 * doing it here means the active index is aimed at something that exists.</p>
	 */
	public ComposerProject deleteLayers(Set<Integer> layerIndices) {
		if (layerIndices == null || layerIndices.isEmpty()) {
			return this;
		}
		List<Layer> kept = new ArrayList<>();
		for (int index = 0; index < layers.size(); index++) {
			if (!layerIndices.contains(index)) {
				kept.add(layers.get(index));
			}
		}
		if (kept.size() == layers.size()) {
			return this;
		}
		int lowest = layerIndices.stream().mapToInt(Integer::intValue).filter(index -> index >= 0).min()
			.orElse(0);
		return with(kept, Math.min(lowest, Math.max(0, kept.size() - 1)), nextNoteId);
	}

	/**
	 * Whether two documents hold the same music, ignoring where the cursor happens to be.
	 *
	 * <p>{@code equals} cannot answer this: the record carries the active layer and the next note id
	 * alongside the notes, so clicking a different layer produced a document that compared unequal
	 * to the one on disk. Looking around the composer and leaving asked whether to save changes that
	 * were never made.</p>
	 *
	 * <p>Note ids do count. Two documents with the same notes under different ids are two different
	 * files, and the one on screen is the one that has not been written.</p>
	 */
	public boolean sameContentAs(ComposerProject other) {
		return other != null
			&& name.equals(other.name)
			&& ppq == other.ppq
			&& tempoMicrosPerQuarter == other.tempoMicrosPerQuarter
			&& endTick == other.endTick
			&& speedQuarters == other.speedQuarters
			&& layers.equals(other.layers);
	}

	public ComposerProject moveLayer(int layerIndex, int direction) {
		if (direction == 0 || layers.size() <= 1) {
			return this;
		}
		int from = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		int to = Math.max(0, Math.min(layers.size() - 1, from + direction));
		if (from == to) {
			return this;
		}
		List<Layer> updated = new ArrayList<>(layers);
		Layer moving = updated.remove(from);
		updated.add(to, moving);
		int active = activeLayerIndex;
		if (active == from) {
			active = to;
		} else if (from < active && to >= active) {
			active--;
		} else if (from > active && to <= active) {
			active++;
		}
		return with(updated, active, nextNoteId);
	}

	/**
	 * Collapses same-pitch repeats, the first of {@link #convertToMinecraft}'s steps, on its own.
	 *
	 * @param scope note ids to act on, or empty for the whole composition
	 */
	public ComposerProject withMergedRepeats(int repeatMergeTicks, Set<Long> scope) {
		if (repeatMergeTicks <= 0) {
			return this;
		}
		double window = repeatMergeTicks * ppq * 100_000.0 / tempoMicrosPerQuarter;
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(mergeRepeats(layer.notes(), window, scope)))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/**
	 * A quantize onto the grid redstone counts in, and what that grid turned out to be.
	 *
	 * @param gridTicks composer ticks between adjacent positions
	 * @param repeaterTicks how many repeater ticks that is -- 1 is the finest a build can express
	 * @param tempoNudged whether the tempo had to move for a whole-tick grid to exist at all
	 */
	public record RepeaterQuantize(
		ComposerProject project,
		long gridTicks,
		long repeaterTicks,
		boolean tempoNudged
	) {
	}

	/**
	 * A grid coarser than this is not a quantize, it is a demolition.
	 *
	 * <p>Past four repeater ticks the song stops being recognisable, so rather than snap to it the
	 * tempo moves instead -- which costs a fraction of a percent and buys a one-tick grid.</p>
	 */
	private static final long MAX_REPEATER_GRID = 4L;

	/**
	 * Note starts moved onto whole repeater ticks, at whatever speed the song is set to.
	 *
	 * <p>The musical grid and the repeater grid are different rulers, and only sometimes share
	 * marks. Quantizing to 1/16 helps only when a 1/16 happens to be a whole number of repeater
	 * ticks; when it is not, every note lands somewhere redstone cannot place and the song stays
	 * flagged however many times you run it. This quantizes to the ruler that actually decides.</p>
	 *
	 * <p>Notes closer together than one repeater tick land on the same tick and become a chord.
	 * That is the point rather than a side effect: a passage faster than ten notes a second cannot
	 * be built as separate notes at all, and collapsing it is the only alternative to slowing the
	 * whole song down to accommodate it.</p>
	 *
	 * <p>The grid comes from the span as an exact fraction. Composer ticks per repeater tick is
	 * {@code ppq * 100000 * speed / (tempo * 4)}; in lowest terms its numerator is the smallest
	 * whole number of composer ticks that is also a whole number of repeater ticks, and its
	 * denominator is how many repeater ticks that is. Rounding the span instead would leave every
	 * gap a fraction short and flag the lot as too frequent.</p>
	 */
	/**
	 * The grid the repeater quantize will snap to, worked out without performing it.
	 *
	 * <p>Not the width of one repeater tick. It is the shortest span that is a whole number of song
	 * ticks <em>and</em> a whole number of repeater ticks, which is what makes every gap on it a
	 * delay a build can place. Where one repeater tick is not a whole number of song ticks the grid
	 * is several of them wide, so it is routinely nothing a musician would name -- 144 ticks, 180,
	 * 384 -- and it lands on a note value only by coincidence.</p>
	 *
	 * <p>Public because the menu prints it beside the note values, and printing a different number
	 * from the one the operation uses is worse than printing none: it invites the reading that the
	 * two are the same grid, which they almost never are.</p>
	 */
	public long repeaterGridTicks() {
		return repeaterGrid().gridTicks();
	}

	private record RepeaterGrid(long gridTicks, long repeaterTicks, int tempo) {
	}

	private RepeaterGrid repeaterGrid() {
		long numerator = ppq * 100_000L * Math.max(1, speedQuarters);
		long denominator = tempoMicrosPerQuarter * 4L;
		long divisor = greatestCommonDivisor(numerator, denominator);
		long grid = Math.max(1L, numerator / divisor);
		long repeaterTicks = Math.max(1L, denominator / divisor);
		if (repeaterTicks <= MAX_REPEATER_GRID) {
			return new RepeaterGrid(grid, repeaterTicks, tempoMicrosPerQuarter);
		}
		grid = Math.max(1L, Math.round(numerator / (double)denominator));
		// Rounded up, not to nearest. The tempo has to be an integer, so the span it produces
		// lands either side of the grid -- and a span a hair wider than the grid makes every
		// one-tick gap 0.999 of a tick, which reads as too frequent rather than as exact. Up
		// puts the span just inside the grid instead, where the rounding is harmless.
		return new RepeaterGrid(grid, 1L, Math.max(1, (int)Math.ceil(numerator / (4.0 * grid))));
	}

	public RepeaterQuantize withQuantizedToRepeaters(Set<Long> scope) {
		RepeaterGrid target = repeaterGrid();
		long grid = target.gridTicks();
		long repeaterTicks = target.repeaterTicks();
		int tempo = target.tempo();
		ComposerProject quantized = withTempo(tempo).withQuantized((int)Math.min(Integer.MAX_VALUE, grid), scope);
		if (scope == null || scope.isEmpty()) {
			// The trailing gap is a delay a build has to place like any other, so it lands on the
			// same grid. Left behind, it is the one problem no note can be blamed for.
			long content = quantized.contentEndTick();
			long gap = Math.max(0L, quantized.endTick() - content);
			quantized = quantized.withEndTick(content + Math.round(gap / (double)grid) * grid);
		}
		return new RepeaterQuantize(quantized, grid, repeaterTicks, tempo != tempoMicrosPerQuarter);
	}

	private static long greatestCommonDivisor(long first, long second) {
		long a = Math.abs(first);
		long b = Math.abs(second);
		while (b != 0L) {
			long remainder = a % b;
			a = b;
			b = remainder;
		}
		return Math.max(1L, a);
	}

	/** Snaps note starts onto the given grid, within {@code scope} or everywhere if it is empty. */
	public ComposerProject withQuantized(int gridTicks, Set<Long> scope) {
		int grid = Math.max(1, gridTicks);
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> !inScope(note, scope) ? note : note.movedTo(
					Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid),
					note.midiNote()))
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	private static boolean inScope(NoteEvent note, Set<Long> scope) {
		return scope == null || scope.isEmpty() || scope.contains(note.id());
	}

	/**
	 * Octave-shifts every out-of-range note into the note-block range, in place.
	 *
	 * <p>Unlike {@link #convertToMinecraft} this does not split a layer whose notes need different
	 * shifts, so intervals across such a layer change. It is the quick fix, not the faithful one.</p>
	 */
	public ComposerProject withAllFittedToRange(Set<Long> scope) {
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> note.isBuildable() || !inScope(note, scope)
					? note
					: note.movedTo(note.startTick(),
						note.midiNote() + octaveShiftIntoNoteBlockRange(note.midiNote())))
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/** Tempo at which one grid step is a whole number of repeater ticks. */
	public int repeaterAlignedTempoFor(int gridTicks) {
		return repeaterAlignedTempo(Math.max(1, gridTicks));
	}

	/**
	 * The song's own spacing, as the notes actually sit.
	 *
	 * <p>What the tempo actually has to accommodate. A musical grid is a guess at this and usually
	 * a wrong one: a song whose notes all sit two 1/16s apart is judged against the 1/16 and forced
	 * to a tempo twice as slow as it needs, and a song whose notes have drifted off any grid at all
	 * reports a spacing of a few ticks, which is the honest answer -- no tempo will save it.</p>
	 *
	 * <p>The gcd is right even when no gap is that size. Gaps of 330 and 495 both have to be whole
	 * repeater ticks, so a repeater tick has to divide 165 whether or not anything is 165 apart.</p>
	 */
	public NoteSpacing noteSpacing() {
		List<Long> starts = layers.stream()
			.filter(Layer::buildEnabled)
			.flatMap(layer -> layer.notes().stream())
			.map(NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
		long grid = 0L;
		long smallest = Long.MAX_VALUE;
		for (int index = 1; index < starts.size(); index++) {
			long gap = starts.get(index) - starts.get(index - 1);
			grid = greatestCommonDivisor(grid, gap);
			smallest = Math.min(smallest, gap);
		}
		return new NoteSpacing(grid, grid == 0L ? 0L : smallest);
	}

	/**
	 * How the song is spaced: the grid every gap is a multiple of, and the tightest gap it has.
	 *
	 * <p>Both, because the gap between them is the tell. When they agree, the grid is real and a
	 * tempo built on it costs nothing beyond what the music demands. When the grid is far finer
	 * than anything that actually occurs, a handful of strays have dragged it down and a tempo
	 * built on it slows the whole song to accommodate spacing no note uses.</p>
	 */
	public record NoteSpacing(long gridTicks, long smallestGapTicks) {
	}

	public int noteCount() {
		return layers.stream().mapToInt(layer -> layer.notes().size()).sum();
	}

	public ComposerProject withTempo(int value) {
		return new ComposerProject(name, ppq, value, layers, activeLayerIndex, nextNoteId,
			endTick, speedQuarters);
	}

	/**
	 * The song at 1.00x, with whatever the speed slider was doing folded into the tempo.
	 *
	 * <p>The slider is a rehearsal control: it scales playback and the delays a build would place,
	 * without touching a note. Anything that reasons about redstone timing has to fold it in first,
	 * because every other calculation here reads the tempo and would otherwise be answering a
	 * question about a speed the song is not being played at.</p>
	 */
	public ComposerProject withBakedSpeed() {
		if (speedQuarters == DEFAULT_SPEED_QUARTERS) {
			return this;
		}
		double factor = Math.max(1, speedQuarters) / (double)DEFAULT_SPEED_QUARTERS;
		return withTempo(Math.max(1, (int)Math.round(tempoMicrosPerQuarter / factor)))
			.withSpeedQuarters(DEFAULT_SPEED_QUARTERS);
	}

	public ComposerProject withName(String value) {
		return new ComposerProject(value, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex, nextNoteId,
			endTick, speedQuarters);
	}

	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo) {
		return convertToMinecraft(quantizeTicks, snapTempo, 0);
	}

	/**
	 * @param repeatMergeTicks how many repeater ticks a repeat of the same pitch must clear to
	 *     survive; 0 disables merging. Songs that fake sustain by re-triggering a note every tick
	 *     are otherwise unbuildable, and force the whole song to be slowed to fit them.
	 */
	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo, int repeatMergeTicks) {
		int grid = Math.max(1, quantizeTicks);
		double repeatWindow = repeatMergeTicks <= 0
			? 0.0
			: repeatMergeTicks * ppq * 100_000.0 / tempoMicrosPerQuarter;
		int mergedRepeats = 0;
		int duplicateLayers = 0;
		int duplicateLayerNotes = 0;
		List<Layer> convertedLayers = new ArrayList<>();
		int convertedActiveLayer = 0;
		int shiftedNotes = 0;

		Comparator<Integer> shiftsNearestFirst = Comparator
			.comparingInt((Integer shift) -> Math.abs(shift))
			.thenComparingInt(Integer::intValue);
		for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
			Layer source = layers.get(layerIndex);
			List<NoteEvent> sourceNotes = mergeRepeats(source.notes(), repeatWindow);
			mergedRepeats += source.notes().size() - sourceNotes.size();
			Map<Integer, List<NoteEvent>> notesByShift = new TreeMap<>(shiftsNearestFirst);
			if (sourceNotes.isEmpty()) {
				notesByShift.put(0, List.of());
			}
			for (NoteEvent note : sourceNotes) {
				int shift = octaveShiftIntoNoteBlockRange(note.midiNote());
				long quantizedStart = Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid);
				NoteEvent converted = note.movedTo(quantizedStart, note.midiNote() + shift);
				notesByShift.computeIfAbsent(shift, ignored -> new ArrayList<>()).add(converted);
				if (shift != 0) {
					shiftedNotes++;
				}
			}
			// A split that adds nothing is not a split. Two source notes an octave apart land on
			// the same pitch once both are pulled into range, so a bucket can come out as an exact
			// copy of one already emitted -- a whole layer playing a sound that is already being
			// played. Percussion does this constantly, where notes an octave apart are different
			// drums that map to one note-block pitch: Hammer of Justice produced two 239-note snare
			// layers, every note of both already covered by the in-range one.
			//
			// Buckets are visited nearest-shift first, so what survives is the least transposed
			// copy. An empty bucket is kept: that is a source layer with no notes, not a duplicate.
			List<Map.Entry<Integer, List<NoteEvent>>> distinct = new ArrayList<>();
			Set<NoteSound> withinSplit = new java.util.HashSet<>();
			for (Map.Entry<Integer, List<NoteEvent>> entry : notesByShift.entrySet()) {
				boolean anythingNew = entry.getValue().isEmpty();
				for (NoteEvent note : entry.getValue()) {
					if (withinSplit.add(new NoteSound(
							source.instrument(), note.midiNote(), note.startTick()))) {
						anythingNew = true;
					}
				}
				if (anythingNew) {
					distinct.add(entry);
				} else {
					duplicateLayers++;
					duplicateLayerNotes += entry.getValue().size();
					shiftedNotes -= entry.getValue().size();
				}
			}
			if (convertedLayers.size() + distinct.size() > MAX_LAYERS) {
				throw new IllegalStateException(
					"Conversion needs " + (convertedLayers.size() + distinct.size())
						+ " layers but the limit is " + MAX_LAYERS
						+ ". Run Edit > Fit into range first: notes already inside the note-block "
						+ "range all take the same octave shift, so their layer stops splitting."
				);
			}
			if (layerIndex == activeLayerIndex) {
				convertedActiveLayer = convertedLayers.size();
			}
			for (Map.Entry<Integer, List<NoteEvent>> entry : distinct) {
				int shift = entry.getKey();
				String convertedName = distinct.size() == 1 && shift == 0
					? source.name()
					: source.name() + octaveShiftSuffix(shift);
				convertedLayers.add(new Layer(
					convertedName,
					source.instrument(),
					source.muted(),
					source.buildEnabled(),
					source.visible(),
					entry.getValue()
				));
			}
		}

		// The tempo comes from where the notes ended up, not from the grid they were quantized to.
		// Those are different once quantizing has moved things: a song whose notes all land two
		// grid steps apart needs a repeater tick every two steps, and forcing one per step slows it
		// by half for nothing. That is exactly what converting an already-valid song did -- notes on
		// a 1/8, grid set to 1/16, tempo doubled, song halved. Asking the notes cannot do that,
		// because after quantizing their spacing is always a whole number of grid steps.
		ComposerProject shaped = new ComposerProject(name, ppq, tempoMicrosPerQuarter, convertedLayers,
			convertedActiveLayer, nextNoteId, endTick, speedQuarters);
		NoteSpacing spacing = shaped.noteSpacing();
		int convertedTempo = snapTempo && spacing.gridTicks() > 0L
			? shaped.repeaterAlignedTempoFor((int)Math.min(Integer.MAX_VALUE, spacing.gridTicks()))
			: tempoMicrosPerQuarter;

		// The marker is a musical position, so a tempo change carries it along with the notes --
		// and then its trailing gap has to land on the new repeater grid too. Converting the notes
		// and leaving the marker behind is exactly how a song ends up reporting one problem that
		// no note is responsible for.
		long movedEnd = Math.round(endTick * (tempoMicrosPerQuarter / (double)convertedTempo));
		long convertedContentEnd = convertedLayers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(NoteEvent::startTick)
			.max()
			.orElse(0L);
		double convertedSpan = ppq * 100_000.0 / convertedTempo
			* Math.max(1, speedQuarters) / 4.0;
		long trailingGap = Math.max(0L, movedEnd - convertedContentEnd);
		long snappedEnd = convertedContentEnd
			+ Math.round(Math.round(trailingGap / convertedSpan) * convertedSpan);

		ComposerProject converted = new ComposerProject(
			name,
			ppq,
			convertedTempo,
			convertedLayers,
			convertedActiveLayer,
			nextNoteId,
			snappedEnd,
			speedQuarters
		);
		return new MinecraftConversion(
			converted,
			shiftedNotes,
			Math.max(0, convertedLayers.size() - layers.size()),
			convertedTempo != tempoMicrosPerQuarter,
			convertedTempo / (double)tempoMicrosPerQuarter,
			mergedRepeats,
			duplicateLayers,
			duplicateLayerNotes
		);
	}

	/**
	 * Distinct note starts as {@link #convertToMinecraft} will see them, after repeat merging.
	 *
	 * <p>Grid selection has to run on the merged timeline. Measuring the raw notes would let the
	 * very repeats that merging removes go on dictating the grid, and therefore the tempo.</p>
	 */
	public List<Long> mergedStartTicks(int repeatMergeTicks) {
		double window = repeatMergeTicks <= 0
			? 0.0
			: repeatMergeTicks * ppq * 100_000.0 / tempoMicrosPerQuarter;
		return layers.stream()
			.flatMap(layer -> mergeRepeats(layer.notes(), window).stream())
			.map(NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
	}

	public ComposerProject addNote(int layerIndex, int midiNote, long startTick, long durationTicks) {
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Layer layer = layers.get(target);
		List<NoteEvent> notes = new ArrayList<>(layer.notes());
		notes.add(new NoteEvent(nextNoteId, midiNote, startTick, durationTicks, 96));
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(target, layer.withNotes(notes));
		return with(updated, target, nextNoteId + 1L);
	}

	/**
	 * Pastes the clipboard, splitting it by instrument only where a layer cannot hold it.
	 *
	 * <p>A layer has one instrument, so what a copy can survive depends entirely on how many it
	 * spans. One instrument flattens into the layer you aimed at however many layers it was copied
	 * from, because those layers were splitting up a voice and not a sound -- and the notes take
	 * that layer's instrument, which is how re-voicing a phrase by pasting it into another part has
	 * always worked here. More than one and flattening would silence a whole instrument, so each
	 * gets a layer: the one you aimed at if it is already that instrument, a new one otherwise.</p>
	 *
	 * <p>The exception is aiming at an empty layer whose instrument the copy does not contain. That
	 * is a scratch layer -- nothing in it sounds, and its instrument cannot have been chosen for
	 * this paste, since none of the paste is in it. It takes the first instrument rather than being
	 * left empty beside the layers the paste had to make.</p>
	 */
	public PasteResult pasteNotes(int layerIndex, List<ClipboardNote> clipboard, long startTick) {
		if (clipboard == null || clipboard.isEmpty()) {
			return new PasteResult(this, Set.of(), 0);
		}
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Map<String, List<ClipboardNote>> byInstrument = new java.util.LinkedHashMap<>();
		for (ClipboardNote copied : clipboard) {
			byInstrument.computeIfAbsent(copied.instrument(), key -> new ArrayList<>()).add(copied);
		}
		List<Layer> updated = new ArrayList<>(layers);
		String adopted = byInstrument.size() < 2 || byInstrument.containsKey(layers.get(target).instrument())
			? layers.get(target).instrument()
			: layers.get(target).notes().isEmpty() ? byInstrument.keySet().iterator().next() : null;
		if (adopted != null && !adopted.equals(layers.get(target).instrument())) {
			updated.set(target, updated.get(target).withInstrument(adopted));
		}
		Set<Long> addedIds = new LinkedHashSet<>();
		long id = nextNoteId;
		int added = 0;
		for (Map.Entry<String, List<ClipboardNote>> group : byInstrument.entrySet()) {
			int home = target;
			if (byInstrument.size() > 1 && !group.getKey().equals(adopted)) {
				if (updated.size() >= MAX_LAYERS) {
					// Out of layers. Better a paste that lands on the wrong instrument than one that
					// silently drops the notes it had nowhere to put.
					home = target;
				} else {
					updated.add(new Layer(group.getValue().getFirst().sourceLayer(), group.getKey(),
						false, true, true, List.of()));
					home = updated.size() - 1;
					added++;
				}
			}
			List<NoteEvent> notes = new ArrayList<>(updated.get(home).notes());
			for (ClipboardNote copied : group.getValue()) {
				NoteEvent note = new NoteEvent(id++, copied.midiNote(),
					Math.max(0L, startTick + copied.tickOffset()), copied.durationTicks(),
					copied.velocity());
				notes.add(note);
				addedIds.add(note.id());
			}
			updated.set(home, updated.get(home).withNotes(notes));
		}
		return new PasteResult(with(updated, target, id), Set.copyOf(addedIds), added);
	}

	public ComposerProject deleteNotes(Set<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return this;
		}
		Set<Long> selected = new LinkedHashSet<>(ids);
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.filter(note -> !selected.contains(note.id()))
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	public ComposerProject moveNotes(Set<Long> ids, long tickDelta, int pitchDelta) {
		if (ids == null || ids.isEmpty() || tickDelta == 0L && pitchDelta == 0) {
			return this;
		}
		Set<Long> selected = new LinkedHashSet<>(ids);
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> selected.contains(note.id())
					? note.movedTo(Math.max(0L, note.startTick() + tickDelta),
						Math.max(0, Math.min(127, note.midiNote() + pitchDelta)))
					: note)
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/**
	 * The first tick anything in the given layers plays on, or -1 if none of them holds a note.
	 */
	public long firstNoteTick(Set<Integer> layerIndices) {
		long earliest = Long.MAX_VALUE;
		for (int index = 0; index < layers.size(); index++) {
			if (layerIndices != null && !layerIndices.contains(index)) {
				continue;
			}
			for (NoteEvent note : layers.get(index).notes()) {
				earliest = Math.min(earliest, note.startTick());
			}
		}
		return earliest == Long.MAX_VALUE ? -1L : earliest;
	}

	/**
	 * Pulls the given layers forward so the first of them starts at tick zero.
	 *
	 * <p>Every selected layer moves by the same amount -- the earliest note among them -- rather than
	 * each one being flushed to zero on its own. Layers of one song are a single performance whose
	 * parts do not all start together, and flushing them individually would put the bass on the
	 * downbeat with the pickup that came before it, which is not a tidier version of the song but a
	 * different one. Selecting a single layer is how you ask for that layer alone.</p>
	 *
	 * <p>The end marker comes back by the same amount, since the silence at the front is gone and
	 * leaving the end where it was would only move it to the back. The constructor floors it at the
	 * last note, so an unselected layer that still runs on holds it out.</p>
	 */
	public ComposerProject snappedToStart(Set<Integer> layerIndices) {
		long earliest = firstNoteTick(layerIndices);
		if (earliest <= 0L) {
			return this;
		}
		List<Layer> updated = new ArrayList<>();
		for (int index = 0; index < layers.size(); index++) {
			Layer layer = layers.get(index);
			if (layerIndices != null && !layerIndices.contains(index)) {
				updated.add(layer);
				continue;
			}
			updated.add(layer.withNotes(layer.notes().stream()
				.map(note -> note.movedTo(note.startTick() - earliest, note.midiNote()))
				.toList()));
		}
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex,
			nextNoteId, Math.max(1L, endTick - earliest), speedQuarters);
	}

	/** Where the notes actually stop, ignoring any trailing silence the marker adds. */
	public long contentEndTick() {
		return layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(NoteEvent::startTick)
			.max()
			.orElse(0L);
	}

	/** Pulls the end marker back to the last note, discarding deliberate trailing silence. */
	public ComposerProject trimmedToContent() {
		return withEndTick(contentEndTick());
	}

	/** Moves the end marker. Values before the last note are pulled forward to it. */
	public ComposerProject withEndTick(long value) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex,
			nextNoteId, Math.max(0L, value), speedQuarters);
	}

	public ComposerProject withSpeedQuarters(int value) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex,
			nextNoteId, endTick, value);
	}

	/**
	 * The build projection of one layer: exactly the steps the in-world builder places.
	 *
	 * <p>This, not text, is the build path. Track text is a human-facing rendering of the same
	 * steps, so the two cannot disagree about what a composition builds as.</p>
	 */
	public List<Step> toSteps(Layer layer) {
		List<NoteEvent> buildable = layer.notes().stream().filter(NoteEvent::isBuildable).toList();
		List<Step> steps = new ArrayList<>();
		long previousTick = 0L;
		for (int index = 0; index < buildable.size();) {
			long eventTick = buildable.get(index).startTick();
			NoteSequence.addDelaySteps(steps, buildDelayTicks(eventTick - previousTick));
			while (index < buildable.size() && buildable.get(index).startTick() == eventTick) {
				steps.add(Step.note(buildable.get(index).noteBlockPitch()));
				index++;
			}
			previousTick = eventTick;
		}
		// Trailing silence, up to the end marker. It is what makes a loop come round evenly, and
		// when a composition has no notes at all it is the entire build -- a bare repeater chain.
		NoteSequence.addDelaySteps(steps, buildDelayTicks(endTick - previousTick));
		return List.copyOf(steps);
	}

	/** The same projection rendered as sequence text, for export and for reading. */
	public String toText(Layer layer) {
		List<String> tokens = new ArrayList<>();
		for (Step step : toSteps(layer)) {
			if (step.type() != StepType.REPEATER) {
				tokens.add(Integer.toString(step.value()));
			} else if (step.delayIndex() == 0) {
				// One token per delay, not per repeater: the group already knows its own total.
				tokens.add(step.delayTotal() + "d");
			}
		}
		return String.join(", ", tokens);
	}

	/**
	 * A gap in composer ticks as the whole repeater ticks a build would use for it.
	 *
	 * <p>Rounds once, after the speed is applied. Rounding to whole repeater ticks first destroys
	 * any gap shorter than one tick, which makes the speed control inert on fast songs: 0.3125
	 * ticks collapses to 0, and 0 stays 0 at every speed.</p>
	 */
	private int buildDelayTicks(long composerTicks) {
		double physical = composerTicksToMinecraftTicks(
			Math.max(0L, composerTicks), ppq, tempoMicrosPerQuarter
		);
		return (int)Math.max(0L, Math.round(physical * 4.0 / Math.max(1, speedQuarters)));
	}

	private static List<Layer> normalizeLayers(List<Layer> source) {
		List<Layer> normalized = new ArrayList<>();
		if (source != null) {
			for (Layer layer : source) {
				if (layer != null && normalized.size() < MAX_LAYERS) {
					normalized.add(new Layer(layer.name(), layer.instrument(), layer.muted(),
						layer.buildEnabled(), layer.visible(), layer.notes()));
				}
			}
		}
		if (normalized.isEmpty()) {
			normalized.add(new Layer("Track 1", "HARP", false, true, true, List.of()));
		}
		return List.copyOf(normalized);
	}

	private static long minecraftTickToComposerTick(int minecraftTicks, int ppq, int tempoMicrosPerQuarter) {
		return Math.max(0L, Math.round(minecraftTicks * 100_000.0 * ppq / tempoMicrosPerQuarter));
	}

	/** Composer ticks to Minecraft repeater ticks, unrounded so callers can round once at the end. */
	private static double composerTicksToMinecraftTicks(long ticks, int ppq, int tempoMicrosPerQuarter) {
		return Math.max(0.0, ticks * tempoMicrosPerQuarter / (double)ppq / 100_000.0);
	}

	/**
	 * Tempo at which one grid step is a whole number of repeater ticks, at the current speed.
	 *
	 * <p>The speed has to be in here. A repeater tick covers {@code ppq * 100000 / tempo *
	 * speed/4} composer ticks, so the alignment this is solving for moves when the speed does.
	 * Without it the function aligned for 1.00x only, which meant that at any other speed it
	 * returned the tempo already in use and Snap tempo reported nothing to change while the status
	 * bar counted dozens of off-grid notes. Same shape of bug as an earlier one in the span
	 * itself, and it hides in the same place: at 1.00x the factor is 1 and everything agrees.</p>
	 */
	private int repeaterAlignedTempo(int gridTicks) {
		double speedFactor = Math.max(1, speedQuarters) / 4.0;
		double gridRepeaterTicks = gridTicks * tempoMicrosPerQuarter
			/ (double)ppq / 100_000.0 / speedFactor;
		int nearestRepeaterTicks = Math.max(1, (int)Math.round(gridRepeaterTicks));
		// Rounded up, like the nudge in withQuantizedToRepeaters and for the same reason. The tempo
		// is an integer, so the span it produces lands either side of the grid; one microsecond low
		// makes the span a hair wider than the grid, and every gap that should be exactly one
		// repeater tick comes out at 0.999 of one, which reads as too frequent. Up lands the span
		// just inside the grid, where the error is harmless. Found by snapping a song that had just
		// been quantized to repeater ticks and watching 369 gaps go red.
		return Math.max(1, (int)Math.ceil(
			nearestRepeaterTicks * 100_000.0 * ppq * speedFactor / gridTicks
		));
	}

	/**
	 * Collapses runs of the same pitch that re-trigger faster than {@code windowTicks} apart,
	 * keeping the first note of each run and stretching it over the notes it absorbed.
	 *
	 * <p>The window is measured against the previous note in the run rather than the note that
	 * started it, so an arbitrarily long decay ramp folds down to its attack.</p>
	 */
	private static List<NoteEvent> mergeRepeats(List<NoteEvent> notes, double windowTicks) {
		return mergeRepeats(notes, windowTicks, Set.of());
	}

	private static List<NoteEvent> mergeRepeats(
		List<NoteEvent> notes,
		double windowTicks,
		Set<Long> scope
	) {
		if (windowTicks <= 0.0 || notes.size() < 2) {
			return notes;
		}
		boolean everything = scope == null || scope.isEmpty();
		List<NoteEvent> considered = everything
			? notes
			: notes.stream().filter(note -> scope.contains(note.id())).toList();
		if (considered.size() < 2) {
			return notes;
		}
		List<NoteEvent> kept = new ArrayList<>(considered.size());
		Map<Integer, Integer> anchorIndex = new java.util.HashMap<>();
		Map<Integer, Long> lastStart = new java.util.HashMap<>();
		for (NoteEvent note : considered) {
			int pitch = note.midiNote();
			Long previousStart = lastStart.get(pitch);
			if (previousStart != null && note.startTick() - previousStart < windowTicks) {
				int index = anchorIndex.get(pitch);
				NoteEvent anchor = kept.get(index);
				long absorbedEnd = note.startTick() + note.durationTicks();
				kept.set(index, new NoteEvent(anchor.id(), anchor.midiNote(), anchor.startTick(),
					Math.max(anchor.durationTicks(), absorbedEnd - anchor.startTick()),
					anchor.velocity()));
				lastStart.put(pitch, note.startTick());
				continue;
			}
			anchorIndex.put(pitch, kept.size());
			lastStart.put(pitch, note.startTick());
			kept.add(note);
		}
		if (everything) {
			return List.copyOf(kept);
		}
		List<NoteEvent> result = new ArrayList<>(kept);
		notes.stream().filter(note -> !scope.contains(note.id())).forEach(result::add);
		return List.copyOf(result);
	}

	private static int octaveShiftIntoNoteBlockRange(int midiNote) {
		int bestShift = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int shift = -120; shift <= 120; shift += 12) {
			int shifted = midiNote + shift;
			if (shifted >= NOTE_BLOCK_BASE_MIDI_NOTE && shifted <= NOTE_BLOCK_MAX_MIDI_NOTE
					&& Math.abs(shift) < bestDistance) {
				bestShift = shift;
				bestDistance = Math.abs(shift);
			}
		}
		return bestShift;
	}

	private static String octaveShiftSuffix(int shift) {
		if (shift == 0) {
			return " (in range)";
		}
		int octaves = Math.abs(shift / 12);
		return " (" + (shift > 0 ? "+" : "-") + octaves + " oct)";
	}

	/**
	 * One copied note, with the voice it was copied from.
	 *
	 * <p>The instrument used to be dropped on the way in, so a copy spanning a piano part and a drum
	 * part pasted back as one instrument and quietly stopped being drums. It travels with the note
	 * because a layer has exactly one instrument, which makes it the one thing about a copy that
	 * cannot be reconstructed at the far end.</p>
	 *
	 * <p>{@code sourceLayer} only names the layer a new one is made after, so it is a label rather
	 * than a link -- the layer it came from may be gone by the time this is pasted.</p>
	 */
	public record ClipboardNote(long tickOffset, int midiNote, long durationTicks, int velocity,
			String instrument, String sourceLayer) {
		public ClipboardNote {
			tickOffset = Math.max(0L, tickOffset);
			midiNote = Math.max(0, Math.min(127, midiNote));
			durationTicks = Math.max(1L, durationTicks);
			velocity = Math.max(1, Math.min(127, velocity));
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			sourceLayer = sourceLayer == null || sourceLayer.isBlank() ? "Pasted" : sourceLayer;
		}
	}

	/**
	 * @param addedLayers how many layers the paste had to make to keep its instruments apart, so
	 *     the caller can say so rather than leave them to be noticed.
	 */
	public record PasteResult(ComposerProject project, Set<Long> noteIds, int addedLayers) {
	}
}
