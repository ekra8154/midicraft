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
	long nextNoteId
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
		int mergedRepeats
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
			List.of(new Layer("Track 1", "HARP", false, true, true, List.of())), 0, 1L);
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
			layers.add(new Layer(track.name(), track.instrument(), "MUTE".equals(track.instrument()),
				track.buildEnabled(), true, notes));
		}
		return new ComposerProject(name, DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER,
			layers, activeTrackIndex, nextId);
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

	public List<SequenceTrack> toSequenceTracks(List<SequenceTrack> previousTracks, int delayScaleQuarters) {
		List<SequenceTrack> result = new ArrayList<>();
		for (int index = 0; index < layers.size(); index++) {
			Layer layer = layers.get(index);
			SequenceTrack previous = previousTracks != null && index < previousTracks.size()
				? previousTracks.get(index)
				: new SequenceTrack(layer.name(), "", layer.instrument(), 0, layer.buildEnabled());
			result.add(new SequenceTrack(
				layer.name(),
				sequenceText(layer, delayScaleQuarters),
				layer.muted() ? "MUTE" : layer.instrument(),
				previous.position(),
				layer.buildEnabled()
			));
		}
		return List.copyOf(result);
	}

	public ComposerProject withLayer(int index, Layer layer) {
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(Math.max(0, Math.min(updated.size() - 1, index)), layer);
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
	}

	public ComposerProject withActiveLayer(int index) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, index, nextNoteId);
	}

	public ComposerProject addLayer() {
		if (layers.size() >= MAX_LAYERS) {
			return this;
		}
		List<Layer> updated = new ArrayList<>(layers);
		int number = updated.size() + 1;
		updated.add(new Layer("Layer " + number, "HARP", false, true, true, List.of()));
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, updated.size() - 1, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, target, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, mergedIndex, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, active, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
	}

	/** Tempo at which one grid step is a whole number of repeater ticks. */
	public int repeaterAlignedTempoFor(int gridTicks) {
		return repeaterAlignedTempo(Math.max(1, gridTicks));
	}

	public int noteCount() {
		return layers.stream().mapToInt(layer -> layer.notes().size()).sum();
	}

	public ComposerProject withTempo(int value) {
		return new ComposerProject(name, ppq, value, layers, activeLayerIndex, nextNoteId);
	}

	public ComposerProject withName(String value) {
		return new ComposerProject(value, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex, nextNoteId);
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
		int convertedTempo = snapTempo ? repeaterAlignedTempo(grid) : tempoMicrosPerQuarter;
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
			if (convertedLayers.size() + notesByShift.size() > MAX_LAYERS) {
				throw new IllegalStateException(
					"Conversion needs " + (convertedLayers.size() + notesByShift.size())
						+ " layers but the limit is " + MAX_LAYERS
						+ ". Run Edit > Fit into range first: notes already inside the note-block "
						+ "range all take the same octave shift, so their layer stops splitting."
				);
			}
			if (layerIndex == activeLayerIndex) {
				convertedActiveLayer = convertedLayers.size();
			}
			for (Map.Entry<Integer, List<NoteEvent>> entry : notesByShift.entrySet()) {
				int shift = entry.getKey();
				String convertedName = notesByShift.size() == 1 && shift == 0
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

		ComposerProject converted = new ComposerProject(
			name,
			ppq,
			convertedTempo,
			convertedLayers,
			convertedActiveLayer,
			nextNoteId
		);
		return new MinecraftConversion(
			converted,
			shiftedNotes,
			Math.max(0, convertedLayers.size() - layers.size()),
			convertedTempo != tempoMicrosPerQuarter,
			convertedTempo / (double)tempoMicrosPerQuarter,
			mergedRepeats
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, target, nextNoteId + 1L);
	}

	public PasteResult pasteNotes(int layerIndex, List<ClipboardNote> clipboard, long startTick) {
		if (clipboard == null || clipboard.isEmpty()) {
			return new PasteResult(this, Set.of());
		}
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Layer layer = layers.get(target);
		List<NoteEvent> notes = new ArrayList<>(layer.notes());
		Set<Long> addedIds = new LinkedHashSet<>();
		long id = nextNoteId;
		for (ClipboardNote copied : clipboard) {
			NoteEvent added = new NoteEvent(id++, copied.midiNote(),
				Math.max(0L, startTick + copied.tickOffset()), copied.durationTicks(), copied.velocity());
			notes.add(added);
			addedIds.add(added.id());
		}
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(target, layer.withNotes(notes));
		return new PasteResult(
			new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, target, id),
			Set.copyOf(addedIds)
		);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
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
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
	}

	public long endTick() {
		return layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(note -> note.startTick() + note.durationTicks())
			.max()
			.orElse(ppq * 4L);
	}

	private String sequenceText(Layer layer, int delayScaleQuarters) {
		List<NoteEvent> buildable = layer.notes().stream().filter(NoteEvent::isBuildable).toList();
		List<String> tokens = new ArrayList<>();
		long previousTick = 0L;
		for (int index = 0; index < buildable.size();) {
			long eventTick = buildable.get(index).startTick();
			// Round once, after the timescale is applied. Rounding to whole repeater ticks first
			// destroys any gap shorter than one tick, which makes the timescale slider inert on
			// fast songs: 0.3125 ticks collapses to 0, and 0 stays 0 at every scale.
			double physicalDelay = composerTicksToMinecraftTicks(
				Math.max(0L, eventTick - previousTick), ppq, tempoMicrosPerQuarter
			);
			int rawDelay = (int)Math.max(0L,
				Math.round(physicalDelay * 4.0 / Math.max(1, delayScaleQuarters)));
			addDelayTokens(tokens, rawDelay);
			while (index < buildable.size() && buildable.get(index).startTick() == eventTick) {
				tokens.add(Integer.toString(buildable.get(index).noteBlockPitch()));
				index++;
			}
			previousTick = eventTick;
		}
		return String.join(", ", tokens);
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

	private int repeaterAlignedTempo(int gridTicks) {
		double gridRepeaterTicks = gridTicks * tempoMicrosPerQuarter / (double)ppq / 100_000.0;
		int nearestRepeaterTicks = Math.max(1, (int)Math.round(gridRepeaterTicks));
		return Math.max(1, (int)Math.round(
			nearestRepeaterTicks * 100_000.0 * ppq / gridTicks
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

	private static void addDelayTokens(List<String> tokens, int delay) {
		int remaining = delay;
		while (remaining > 0) {
			int chunk = Math.min(remaining, NoteSequence.MAX_GROUPED_DELAY);
			tokens.add(chunk + "d");
			remaining -= chunk;
		}
	}

	public record ClipboardNote(long tickOffset, int midiNote, long durationTicks, int velocity) {
		public ClipboardNote {
			tickOffset = Math.max(0L, tickOffset);
			midiNote = Math.max(0, Math.min(127, midiNote));
			durationTicks = Math.max(1L, durationTicks);
			velocity = Math.max(1, Math.min(127, velocity));
		}
	}

	public record PasteResult(ComposerProject project, Set<Long> noteIds) {
	}
}
