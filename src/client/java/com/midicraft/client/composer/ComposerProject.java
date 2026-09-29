package com.midicraft.client.composer;

import com.midicraft.InstrumentRanges;
import com.midicraft.NotePitch;
import com.midicraft.NoteSequence;
import com.midicraft.NoteSequence.Step;
import com.midicraft.NoteSequence.StepType;
import com.midicraft.client.MidicraftConfig.SequenceTrack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
	int speedQuarters,
	/**
	 * The speed in eighths, which is the one that counts. Nought means a file written before the
	 * slider gained half-steps, and the compact constructor reads {@code speedQuarters} instead --
	 * the two are kept in step from then on, so a song saved here still opens at the right speed
	 * in a build that only knows quarters, merely rounded to the nearest one it can express.
	 */
	int speedEighths,
	List<Marker> markers
) {
	public static final int DEFAULT_PPQ = 480;
	public static final int DEFAULT_TEMPO_MICROS_PER_QUARTER = 500_000;
	/**
	 * Converting splits a layer once per distinct octave shift its notes need, so a wide-range
	 * import can need many times its original layer count. The cap is a guard against a runaway
	 * layout, not a design limit -- the layer list collapses and scrolls.
	 */
	public static final int MAX_LAYERS = 128;
	/**
	 * What a top-voice note counts for against an inner one when positioning the pitch window.
	 *
	 * <p>Twelve, and the number is measured rather than chosen. Swept over this library at 1, 2, 3,
	 * 5, 8, 12, 20, 50 and 200, twelve is the smallest weight at which <em>no</em> song comes out
	 * with more of its melody out of range than it went in with. Below it the trade is still
	 * available -- at three, six songs of thirty-six were made worse and DELTARUNE Guardian went from
	 * 900 melody notes out of range to 1,394, because three times a small number still loses to one
	 * times a large one and a song's accompaniment is the large one. Above it the melody barely
	 * improves (37% saved at twelve, 40% at twenty, 48% at fifty) while the rest of the song pays
	 * steeply: fifty costs half again as many out-of-range notes overall. So twelve is where the
	 * guarantee arrives and before the bill does.</p>
	 */
	public static final int MELODY_WEIGHT = 12;
	/**
	 * What marks an instrument id as a sound effect rather than a pitched instrument.
	 *
	 * <p>A sound effect is a block that makes its own noise when redstone reaches it -- a door, a
	 * bell, a note block wearing a skull. None of them can be tuned, so on those layers a row is
	 * only somewhere to put a hit, and two hits on one tick are one hit.</p>
	 */
	public static final String SOUND_EFFECT_PREFIX = "FX_";
	public static final int NOTE_BLOCK_BASE_MIDI_NOTE = 54;
	public static final int NOTE_BLOCK_MAX_MIDI_NOTE = NOTE_BLOCK_BASE_MIDI_NOTE + NotePitch.PITCH_COUNT - 1;
	public static final long DEFAULT_NOTE_DURATION_TICKS = DEFAULT_PPQ / 4L;
	public static final int MIN_SPEED_QUARTERS = 1;
	public static final int MAX_SPEED_QUARTERS = 32;
	public static final int DEFAULT_SPEED_QUARTERS = 4;
	public static final int MIN_SPEED_EIGHTHS = 2;
	public static final int MAX_SPEED_EIGHTHS = 64;
	public static final int DEFAULT_SPEED_EIGHTHS = 8;
	/**
	 * How many markers a composition may carry.
	 *
	 * <p>A guard against a runaway import or a stuck key, not a design limit. Markers name the parts
	 * of a song -- intro, chorus, the bar the build goes wrong at -- and a song with more than a few
	 * dozen of those has stopped using them as landmarks.</p>
	 */
	public static final int MAX_MARKERS = 256;

	/**
	 * Everything but the markers, for the callers written before there were any.
	 *
	 * <p>A record component reaches a hundred and more construction sites at once, most of them
	 * probes and tests that build a song out of a handful of notes and have no opinion about
	 * markers. This is what lets those go on saying what they mean.</p>
	 */
	public ComposerProject(String name, int ppq, int tempoMicrosPerQuarter, List<Layer> layers,
			int activeLayerIndex, long nextNoteId, long endTick, int speedQuarters) {
		this(name, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex, nextNoteId, endTick,
			speedQuarters, 0, List.of());
	}

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
		// Eighths are the truth; quarters are kept in step behind them. A file with no eighths in
		// it was written in quarters, so it says twice its quarters -- which is the same speed,
		// expressed in the finer unit. A file with neither is new and runs at 1.00x.
		speedEighths = speedEighths <= 0
			? (speedQuarters <= 0 ? DEFAULT_SPEED_EIGHTHS : speedQuarters * 2)
			: speedEighths;
		speedEighths = Math.max(MIN_SPEED_EIGHTHS, Math.min(MAX_SPEED_EIGHTHS, speedEighths));
		speedQuarters = Math.max(MIN_SPEED_QUARTERS,
			Math.min(MAX_SPEED_QUARTERS, speedEighths / 2));
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
		markers = normalizeMarkers(markers);
	}

	/**
	 * In tick order, one to a tick, and never more than {@link #MAX_MARKERS} of them.
	 *
	 * <p>One to a tick because two names for the same instant is two flags drawn on top of each
	 * other, and the one underneath can neither be read nor clicked. The first written wins, which
	 * makes adding a marker where one already stands a rename rather than a second flag.</p>
	 */
	private static List<Marker> normalizeMarkers(List<Marker> value) {
		if (value == null || value.isEmpty()) {
			return List.of();
		}
		Map<Long, Marker> byTick = new TreeMap<>();
		for (Marker marker : value) {
			if (marker != null) {
				byTick.putIfAbsent(marker.tick(), marker);
			}
		}
		List<Marker> sorted = new ArrayList<>(byTick.values());
		return List.copyOf(sorted.size() <= MAX_MARKERS ? sorted : sorted.subList(0, MAX_MARKERS));
	}

	/**
	 * Rebuilds with new layers, keeping everything the caller did not mean to change.
	 *
	 * <p>Every edit funnels through here so the compact constructor's invariants -- the end marker
	 * floor in particular -- apply to all of them without each method remembering to.</p>
	 */
	private ComposerProject with(List<Layer> updatedLayers, int active, long nextId) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updatedLayers, active, nextId,
			endTick, speedQuarters, speedEighths, markers);
	}

	/**
	 * A named position on the timeline.
	 *
	 * <p>Nothing is built from a marker and nothing sounds at one. It is somewhere to write down
	 * what a stretch of the song is -- where the chorus starts, which bar the build goes wrong at --
	 * so that finding it again is reading a label rather than counting bars.</p>
	 */
	public record Marker(long tick, String label) {
		public Marker {
			tick = Math.max(0L, tick);
			label = label == null || label.isBlank() ? "Marker" : label.trim();
		}

		public Marker named(String value) {
			return new Marker(tick, value);
		}

		public Marker movedTo(long value) {
			return new Marker(value, label);
		}
	}

	/** The marker exactly on {@code tick}, or null. */
	public Marker markerAt(long tick) {
		for (Marker marker : markers) {
			if (marker.tick() == tick) {
				return marker;
			}
		}
		return null;
	}

	/** The marker nearest {@code tick} within {@code tolerance}, or null when none is that close. */
	public Marker markerNear(long tick, long tolerance) {
		Marker nearest = null;
		long best = Long.MAX_VALUE;
		for (Marker marker : markers) {
			long distance = Math.abs(marker.tick() - tick);
			if (distance <= Math.max(0L, tolerance) && distance < best) {
				best = distance;
				nearest = marker;
			}
		}
		return nearest;
	}

	/**
	 * Puts a marker on a tick, replacing whatever was already named there.
	 *
	 * <p>Replacing rather than refusing: one tick holds one marker, so writing to an occupied tick
	 * can only be a rename, and there is nothing else it could sensibly mean.</p>
	 */
	public ComposerProject withMarkerAt(long tick, String label) {
		long at = Math.max(0L, tick);
		List<Marker> updated = new ArrayList<>(markers.size() + 1);
		for (Marker marker : markers) {
			if (marker.tick() != at) {
				updated.add(marker);
			}
		}
		if (updated.size() >= MAX_MARKERS) {
			return this;
		}
		updated.add(new Marker(at, label));
		return withMarkers(updated);
	}

	/** Removes the marker on a tick, if there is one. */
	public ComposerProject withoutMarkerAt(long tick) {
		if (markerAt(tick) == null) {
			return this;
		}
		return withMarkers(markers.stream().filter(marker -> marker.tick() != tick).toList());
	}

	public ComposerProject withMarkers(List<Marker> value) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex,
			nextNoteId, endTick, speedQuarters, speedEighths, value);
	}

	public record NoteEvent(long id, int midiNote, long startTick, long durationTicks, int velocity,
			/**
			 * How far this note's sustain strikes sit from the song's strike grid, in composer ticks:
			 * what dragging a strike tick sets. The whole pattern moves together, so only its phase
			 * counts and any multiple of the step is the same as none.
			 *
			 * <p>Boxed, and null for none: Gson leaves a null out, so a note nobody dragged saves
			 * exactly as it did before there was anything to drag, and a song saved before reads as
			 * null.</p>
			 */
			Long strikeShift) {
		public NoteEvent {
			id = Math.max(1L, id);
			midiNote = Math.max(0, Math.min(127, midiNote));
			startTick = Math.max(0L, startTick);
			durationTicks = Math.max(1L, durationTicks);
			velocity = Math.max(1, Math.min(127, velocity));
			strikeShift = strikeShift == null || strikeShift == 0L ? null : strikeShift;
		}

		/** A note whose strikes sit on the song's grid, which is every note nobody dragged. */
		public NoteEvent(long id, int midiNote, long startTick, long durationTicks, int velocity) {
			this(id, midiNote, startTick, durationTicks, velocity, null);
		}

		/** {@link #strikeShift} as a number, nought for none. */
		public long strikeShiftTicks() {
			return strikeShift == null ? 0L : strikeShift;
		}

		public NoteEvent withStrikeShift(long value) {
			return new NoteEvent(id, midiNote, startTick, durationTicks, velocity, value);
		}

		/** The same note under another id: a copy, which strikes where the original did. */
		public NoteEvent withId(long value) {
			return new NoteEvent(value, midiNote, startTick, durationTicks, velocity, strikeShift);
		}

		public NoteEvent withDuration(long value) {
			return new NoteEvent(id, midiNote, startTick, value, velocity, strikeShift);
		}

		public boolean isBuildable() {
			return midiNote >= NOTE_BLOCK_BASE_MIDI_NOTE && midiNote <= NOTE_BLOCK_MAX_MIDI_NOTE;
		}

		public int noteBlockPitch() {
			return midiNote - NOTE_BLOCK_BASE_MIDI_NOTE;
		}

		public NoteEvent movedTo(long tick, int note) {
			return new NoteEvent(id, note, tick, durationTicks, velocity, strikeShift);
		}
	}

	public record Layer(
		String name,
		String instrument,
		boolean muted,
		boolean buildEnabled,
		boolean visible,
		List<NoteEvent> notes,
		/** How a split layer voices its notes, or null for the ordinary one-instrument layer. */
		Split split,
		/**
		 * The instruments an ordinary layer sounds together, each at its own count, or empty for
		 * the one {@link #instrument} sounding once.
		 *
		 * <p>Voices rather than names so that a count means the same thing here as on a split
		 * layer. Their brackets mean nothing on an ordinary layer: every voice plays every note at
		 * the pitch value it is written at, which is what the layer's one instrument always did.
		 * A split layer's voices are its split's, so on one this is always empty.</p>
		 */
		List<Split.Voice> mix,
		/**
		 * Instruments switched off in the palette, kept with their counts and brackets so that
		 * switching one back on gives back what it was. Never holds an instrument that is sounding.
		 */
		List<Split.Voice> resting,
		/**
		 * Whether this layer turns its long notes into repeated strikes, and how; null when never
		 * set, which reads as off. See {@link Sustain}.
		 */
		Sustain sustain
	) {
		public Layer {
			name = name == null || name.isBlank() ? "Layer" : name.trim();
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			// One instrument sounding once is the ordinary layer and is stored as one, so every song
			// written before counts, and every layer nobody stacks, stays the same document. When
			// several sound, the first is the instrument: the row's icon, and what a reader that
			// predates the mix sees.
			mix = split != null ? List.of() : distinctByInstrument(mix);
			if (mix.size() == 1 && mix.get(0).count() == 1) {
				instrument = mix.get(0).instrument();
				mix = List.of();
			} else if (!mix.isEmpty()) {
				instrument = mix.get(0).instrument();
			}
			resting = restingOnly(resting, split, mix, instrument);
			// A split layer always keeps the pitch in the cell, whatever its vestigial instrument
			// says: the row decides which voices sound, so two rows are never the same note.
			notes = notes == null ? List.of()
				: ordered(notes, split != null || pitched(instrument, mix));
		}

		/** Everything but the split, for the callers written before there was one. */
		public Layer(String name, String instrument, boolean muted, boolean buildEnabled,
				boolean visible, List<NoteEvent> notes) {
			this(name, instrument, muted, buildEnabled, visible, notes, null, null, null, null);
		}

		/** Everything but the counts, for the callers written before there were any. */
		public Layer(String name, String instrument, boolean muted, boolean buildEnabled,
				boolean visible, List<NoteEvent> notes, Split split) {
			this(name, instrument, muted, buildEnabled, visible, notes, split, null, null, null);
		}

		/**
		 * Whether this layer's voice does anything with the row a note sits on.
		 *
		 * <p>Read off the id rather than looked up, so that the document stays a document: the
		 * palette lives in the client's compat package and touching it from here would drag
		 * Minecraft's item and sound registries into every test that builds a layer.
		 * {@code SoundEffectVoiceTest} holds the palette to the naming, which is where a new voice
		 * that forgot the prefix gets caught.</p>
		 */
		/**
		 * Whether this layer goes into a build: it does if you can hear it and see it.
		 *
		 * <p>There used to be a flag of its own for this, set by a dot on each row, independent of
		 * whether the layer was muted or hidden. Two switches for one question, and the composer had
		 * two signal paths as a result -- preview played what was unmuted and a build placed what was
		 * dotted, with nothing connecting them, so pressing Space was not a preview of the build and
		 * there was no way to hear what would be built. A DAW does not have this problem because a
		 * bounce is the same chain as the transport: what you heard is what you got. This is that,
		 * and the flag it replaces stays on the record only so that older song files still load.</p>
		 *
		 * <p>Solo is not part of it. Soloing is a momentary lens for listening around a part, and it
		 * is the one place preview and build can still disagree -- the screen says so while it is on
		 * rather than quietly dropping four layers out of somebody's machine.</p>
		 */
		public boolean inBuild() {
			return !muted && visible;
		}

		public boolean pitched() {
			// A split layer's rows always mean something, even when every voice on it is a sound
			// effect: the row is what picks the voice.
			return split != null || pitched(instrument, mix);
		}

		private static boolean pitched(String instrument) {
			return !instrument.startsWith(SOUND_EFFECT_PREFIX);
		}

		/** Whether an ordinary layer's rows mean anything: they do if any voice sounding is tuned. */
		private static boolean pitched(String instrument, List<Split.Voice> mix) {
			return mix.isEmpty()
				? pitched(instrument)
				: mix.stream().anyMatch(voice -> pitched(voice.instrument()));
		}

		/**
		 * Whether this note is one the layer cannot build.
		 *
		 * <p>The one question the roll's red bar, the status counter and Select > out of range all
		 * ask, and it depends on the layer: an ordinary pitched layer holds its notes to the harp
		 * window, a sound effect layer has no range at all, and a split layer holds them to its
		 * own brackets -- a note at F#2 is out of range on a harp layer and squarely inside a bass
		 * voice on a split one.</p>
		 */
		public boolean outOfRange(NoteEvent note) {
			if (split != null) {
				return !split.covers(note.midiNote());
			}
			return pitched() && !note.isBuildable();
		}

		/**
		 * The single-instrument layers a build and a preview see, one per split voice.
		 *
		 * <p>An ordinary layer is its own only voice. A split layer expands here and nowhere else:
		 * each voice takes the notes its bracket covers, transposed into the harp window by the
		 * voice's own register, so that everything downstream -- steps, sequence tracks, the
		 * builder, the analysis -- keeps its one-instrument-per-layer world view and the note
		 * lands on the block at the pitch value that sounds as written. A note under two brackets
		 * appears in both voices, which is the doubling the overlap is for.</p>
		 *
		 * <p>Voices with nothing covered are left out rather than emitted empty: an empty track
		 * still costs a build a lane, and a bracket nothing reaches has nothing to say.</p>
		 *
		 * <p>An ordinary layer sounding several instruments expands here too, one layer per
		 * instrument, each holding every note as written. Every layer this returns carries its
		 * voice's count, read with {@link #copies}; turning a count into note blocks is
		 * {@link #asCopies}, which comes after deduplication.</p>
		 */
		public List<Layer> buildVoices() {
			if (split == null && mix.isEmpty()) {
				return List.of(this);
			}
			if (split == null) {
				List<Layer> stacked = new ArrayList<>(mix.size());
				for (Split.Voice voice : mix) {
					stacked.add(new Layer(mix.size() == 1 ? name : name + " (" + voice.instrument() + ")",
						voice.instrument(), muted, buildEnabled, visible, notes, null, List.of(voice), null, null));
				}
				return List.copyOf(stacked);
			}
			List<Layer> voices = new ArrayList<>();
			for (Split.Voice voice : split.voices()) {
				int shift = pitched(voice.instrument())
					? NOTE_BLOCK_BASE_MIDI_NOTE - InstrumentRanges.baseMidi(voice.instrument())
					: 0;
				List<NoteEvent> covered = new ArrayList<>();
				for (NoteEvent note : notes) {
					if (voice.covers(note.midiNote())) {
						covered.add(shift == 0
							? note
							: note.movedTo(note.startTick(), note.midiNote() + shift));
					}
				}
				if (!covered.isEmpty()) {
					voices.add(new Layer(name + " (" + voice.instrument() + ")",
						voice.instrument(), muted, buildEnabled, visible, covered, null,
						List.of(voice), null, null));
				}
			}
			return List.copyOf(voices);
		}

		/**
		 * A layer's notes in order: by tick, then pitch, then id, so a stack sits together with the
		 * note that was there first at the bottom of it.
		 *
		 * <p>A layer may hold more than one note in a cell -- a stack. Nothing makes one by clicking:
		 * {@link ComposerProject#addNote} refuses an occupied cell. They come from pasting onto notes
		 * and dragging onto them, and they have to survive, or pasting in place onto the layer a copy
		 * came from threw the paste away on arrival and there was nothing left to drag off.</p>
		 *
		 * <p>A stack is always built and played once, whatever Merge duplicate notes is set to; see
		 * {@link ComposerProject#placedForBuild}.</p>
		 *
		 * <p>On a sound effect layer the cell is the tick alone. A door cannot be tuned, so the row a
		 * hit is drawn on says nothing about how it sounds, and two hits on one tick are a stack
		 * however far apart their rows are.</p>
		 */
		private static List<NoteEvent> ordered(List<NoteEvent> notes, boolean pitched) {
			Comparator<NoteEvent> order = pitched
				? Comparator.comparingLong(NoteEvent::startTick)
					.thenComparingInt(NoteEvent::midiNote)
					.thenComparingLong(NoteEvent::id)
				: Comparator.comparingLong(NoteEvent::startTick)
					.thenComparingLong(NoteEvent::id);
			List<NoteEvent> sorted = notes.stream()
				.filter(java.util.Objects::nonNull)
				.sorted(order)
				.toList();
			return sorted;
		}

		/** Whether this layer tells two rows at one tick apart, which is what makes them one cell. */
		public boolean pitchedCells() {
			return split != null || pitched();
		}

		/**
		 * This layer with every stack reduced to the note at the bottom of it, the one there first.
		 *
		 * <p>For the edits that squash notes together on purpose -- quantizing neighbours onto one
		 * tick, folding octaves into range, a conversion, an import whose track doubles a note -- where
		 * two notes landing on one cell have become one note, not a stack anybody asked for.</p>
		 */
		public Layer withStacksMerged() {
			List<NoteEvent> kept = oneNotePerCell(notes, pitchedCells());
			return kept.size() == notes.size() ? this : withNotes(kept);
		}

		/** How many notes share this note's cell, itself included: 1 for a note on its own. */
		public int stackSize(NoteEvent note) {
			boolean pitched = pitchedCells();
			int size = 0;
			for (NoteEvent other : notes) {
				if (other.startTick() > note.startTick()) {
					break;
				}
				if (other.startTick() == note.startTick()
						&& (!pitched || other.midiNote() == note.midiNote())) {
					size++;
				}
			}
			return Math.max(1, size);
		}

		/**
		 * Every note in a stack but the one at the bottom of it: the copies a paste or a drag left on
		 * top of notes already there, in the order they sit.
		 */
		public List<NoteEvent> stackedExtras() {
			boolean pitched = pitchedCells();
			List<NoteEvent> extras = new ArrayList<>();
			NoteEvent previous = null;
			for (NoteEvent note : notes) {
				if (previous != null && previous.startTick() == note.startTick()
						&& (!pitched || previous.midiNote() == note.midiNote())) {
					extras.add(note);
				}
				previous = note;
			}
			return extras;
		}

		/** The notes with at most one per cell, the lowest id surviving. */
		private static List<NoteEvent> oneNotePerCell(List<NoteEvent> notes, boolean pitched) {
			// Unpitched layers leave the pitch out of the ordering as well as out of the cell, or the
			// survivor would be the lowest row rather than the note that was there first.
			Comparator<NoteEvent> order = pitched
				? Comparator.comparingLong(NoteEvent::startTick)
					.thenComparingInt(NoteEvent::midiNote)
					.thenComparingLong(NoteEvent::id)
				: Comparator.comparingLong(NoteEvent::startTick)
					.thenComparingLong(NoteEvent::id);
			List<NoteEvent> sorted = notes.stream()
				.filter(java.util.Objects::nonNull)
				.sorted(order)
				.toList();
			// Sorted by tick then pitch, so anything sharing a cell is adjacent and one pass finds it.
			List<NoteEvent> kept = new ArrayList<>(sorted.size());
			for (NoteEvent note : sorted) {
				NoteEvent last = kept.isEmpty() ? null : kept.getLast();
				if (last == null || last.startTick() != note.startTick()
						|| (pitched && last.midiNote() != note.midiNote())) {
					kept.add(note);
				}
			}
			return kept.size() == sorted.size() ? sorted : List.copyOf(kept);
		}

		public Layer withNotes(List<NoteEvent> value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, value, split, mix, resting, sustain);
		}

		public Layer withName(String value) {
			return new Layer(value, instrument, muted, buildEnabled, visible, notes, split, mix, resting, sustain);
		}

		/** One instrument, sounding once: whatever the layer was stacking, it stops. */
		public Layer withInstrument(String value) {
			return new Layer(name, value, muted, buildEnabled, visible, notes, split, null, resting, sustain);
		}

		public Layer withMuted(boolean value) {
			return new Layer(name, instrument, value, buildEnabled, visible, notes, split, mix, resting, sustain);
		}

		public Layer withBuildEnabled(boolean value) {
			return new Layer(name, instrument, muted, value, visible, notes, split, mix, resting, sustain);
		}

		public Layer withVisible(boolean value) {
			return new Layer(name, instrument, muted, buildEnabled, value, notes, split, mix, resting, sustain);
		}

		public Layer withSplit(Split value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, notes, value, mix, resting, sustain);
		}

		public Layer withMix(List<Split.Voice> value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, notes, split, value, resting, sustain);
		}

		public Layer withResting(List<Split.Voice> value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, notes, split, mix, value, sustain);
		}

		public Layer withSustain(Sustain value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, notes, split, mix, resting, value);
		}

		/** Whether this layer's long notes strike again for as long as they last. */
		public boolean sustains() {
			return sustain != null && sustain.on();
		}

		/** The layer's sustain settings, with the defaults standing in for settings never made. */
		public Sustain sustainOrDefault() {
			return sustain == null ? Sustain.DEFAULT : sustain;
		}

		/**
		 * What this layer sounds, one entry per voice with its count: the split's voices, the mix,
		 * or the single instrument once.
		 */
		public List<Split.Voice> sounding() {
			if (split != null) {
				return split.voices();
			}
			return mix.isEmpty() ? List.of(Split.Voice.fullRange(instrument)) : mix;
		}

		/** How many times this instrument sounds on each note, or 0 when it is not sounding. */
		public int countOf(String instrumentId) {
			return firstCount(sounding(), instrumentId);
		}

		/** The count a switched-off instrument would come back at, or 0 when none is remembered. */
		public int restingCountOf(String instrumentId) {
			return firstCount(resting, instrumentId);
		}

		private static int firstCount(List<Split.Voice> voices, String instrumentId) {
			for (Split.Voice voice : voices) {
				if (voice.instrument().equals(instrumentId)) {
					return voice.count();
				}
			}
			return 0;
		}

		/**
		 * How many note blocks each note of a one-voice layer places.
		 *
		 * <p>Asked of what {@link #buildVoices} returns, where every layer is one voice. A layer
		 * sounding several instruments has no single answer and says 1: expand it first.</p>
		 */
		public int copies() {
			List<Split.Voice> voices = sounding();
			return voices.size() == 1 ? voices.get(0).count() : 1;
		}

		/**
		 * A counted voice as the note blocks it places: one plain layer per copy, each sounding once.
		 *
		 * <p>Deliberately the last step, after deduplication. Copies of one voice are the same
		 * sound on purpose, and collapsing them is exactly what a count exists to prevent.</p>
		 */
		public List<Layer> asCopies() {
			int copies = copies();
			if (copies == 1 && split == null && mix.isEmpty()) {
				return List.of(this);
			}
			return java.util.Collections.nCopies(copies,
				new Layer(name, instrument, muted, buildEnabled, visible, notes));
		}

		/**
		 * A click on an instrument's tile in the palette.
		 *
		 * <p>With one instrument sounding it swaps that one for this one. The count goes with it,
		 * because how loud the part is belongs to the part -- unless this instrument remembers a
		 * count of its own from before, which wins. With two or more sounding it toggles this one:
		 * off keeps its count and bracket, on gives them back. The last instrument sounding can
		 * only be swapped, never switched off.</p>
		 */
		public Layer withInstrumentPicked(String instrumentId) {
			List<Split.Voice> lit = new ArrayList<>(sounding());
			List<Split.Voice> kept = new ArrayList<>(resting);
			boolean sounding = lit.stream().anyMatch(voice -> voice.instrument().equals(instrumentId));
			if (lit.size() <= 1) {
				if (sounding) {
					return this;
				}
				int count = lit.isEmpty() ? 1 : lit.get(0).count();
				if (!lit.isEmpty() && remembers(lit.get(0))) {
					kept.add(lit.get(0));
				}
				return withSounding(takeResting(kept, instrumentId, count), kept);
			}
			if (sounding) {
				List<Split.Voice> off = lit.stream()
					.filter(voice -> voice.instrument().equals(instrumentId)).toList();
				if (off.size() == lit.size()) {
					return this;
				}
				lit.removeAll(off);
				off.stream().filter(this::remembers).forEach(kept::add);
			} else {
				lit.addAll(takeResting(kept, instrumentId, 1));
			}
			return withSounding(lit, kept);
		}

		/**
		 * An arrow on an instrument's tile: its count up or down by {@code delta}, never below one.
		 * Up on an instrument that is not sounding adds it, at the count it remembers or once.
		 */
		public Layer withCountStepped(String instrumentId, int delta) {
			List<Split.Voice> lit = new ArrayList<>(sounding());
			boolean found = false;
			boolean changed = false;
			for (int index = 0; index < lit.size(); index++) {
				Split.Voice voice = lit.get(index);
				if (!voice.instrument().equals(instrumentId)) {
					continue;
				}
				found = true;
				int next = (int)Math.max(1L, Math.min(Integer.MAX_VALUE, (long)voice.count() + delta));
				if (next != voice.count()) {
					lit.set(index, voice.withCount(next));
					changed = true;
				}
			}
			if (found) {
				return changed ? withSounding(lit, resting) : this;
			}
			if (delta <= 0) {
				return this;
			}
			List<Split.Voice> kept = new ArrayList<>(resting);
			lit.addAll(takeResting(kept, instrumentId, 1));
			return withSounding(lit, kept);
		}

		/**
		 * Whether a voice switched off has anything to remember. A split voice has its bracket;
		 * an ordinary layer's voice only has its count, and a count of one is what a fresh one gets.
		 */
		private boolean remembers(Split.Voice voice) {
			return split != null || voice.count() > 1;
		}

		/** Takes an instrument's remembered voices out of {@code kept}, or a fresh one at {@code count}. */
		private static List<Split.Voice> takeResting(List<Split.Voice> kept, String instrumentId,
				int count) {
			List<Split.Voice> back = kept.stream()
				.filter(voice -> voice.instrument().equals(instrumentId)).toList();
			if (back.isEmpty()) {
				return List.of(Split.Voice.fullRange(instrumentId).withCount(count));
			}
			kept.removeAll(back);
			return back;
		}

		private Layer withSounding(List<Split.Voice> voices, List<Split.Voice> restingVoices) {
			return split != null
				? new Layer(name, instrument, muted, buildEnabled, visible, notes, new Split(voices),
					null, restingVoices, sustain)
				: new Layer(name, instrument, muted, buildEnabled, visible, notes, null, voices,
					restingVoices, sustain);
		}

		private static List<Split.Voice> distinctByInstrument(List<Split.Voice> voices) {
			if (voices == null || voices.isEmpty()) {
				return List.of();
			}
			Set<String> seen = new java.util.HashSet<>();
			List<Split.Voice> kept = new ArrayList<>(voices.size());
			for (Split.Voice voice : voices) {
				if (voice != null && seen.add(voice.instrument())) {
					kept.add(voice);
				}
			}
			return List.copyOf(kept);
		}

		private static List<Split.Voice> restingOnly(List<Split.Voice> voices, Split split,
				List<Split.Voice> mix, String instrument) {
			if (voices == null || voices.isEmpty()) {
				return List.of();
			}
			Set<String> soundingNow = new java.util.HashSet<>();
			if (split != null) {
				split.voices().forEach(voice -> soundingNow.add(voice.instrument()));
			} else if (mix.isEmpty()) {
				soundingNow.add(instrument);
			} else {
				mix.forEach(voice -> soundingNow.add(voice.instrument()));
			}
			return voices.stream()
				.filter(java.util.Objects::nonNull)
				.filter(voice -> !soundingNow.contains(voice.instrument()))
				.toList();
		}
	}

	/**
	 * A split layer's palette: which instruments sound, and over which stretch of the keyboard.
	 *
	 * <p>The one rule of a split layer lives here: a note sounds <em>every</em> voice whose
	 * bracket covers it. Everything the feature does falls out of that -- a note under two
	 * overlapping brackets doubles, dragging a bracket in gets single notes back, and a note no
	 * bracket reaches is out of range the same way a note outside the harp window is on an
	 * ordinary layer. On a split layer written pitch is true pitch: C3 means C3, and the brackets
	 * decide which instruments can say it.</p>
	 */
	public record Split(List<Voice> voices) {
		public Split {
			voices = normalizeVoices(voices);
		}

		/**
		 * One instrument's bracket: both ends inclusive, in MIDI.
		 *
		 * <p>Clamped to where the instrument can actually sound -- the handles in the keyboard can
		 * shrink a range but never grow it past the register the sample lives in, and this is the
		 * clamp that guarantees the transposed note always lands on a real pitch value. A sound
		 * effect voice has no register, so its bracket is only a band of rows and clamps to MIDI
		 * itself.</p>
		 *
		 * <p>The count is how many note blocks the voice places for each note it sounds -- the
		 * palette's volume control. A note block has no volume of its own, so louder is more of
		 * them struck together. Never below one; a song saved before counts reads 0 and gets 1.</p>
		 */
		public record Voice(String instrument, int lo, int hi, int count) {
			public Voice {
				count = Math.max(1, count);
				instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
				int lowest = instrument.startsWith(SOUND_EFFECT_PREFIX)
					? 0 : InstrumentRanges.lowestMidi(instrument);
				int highest = instrument.startsWith(SOUND_EFFECT_PREFIX)
					? 127 : InstrumentRanges.highestMidi(instrument);
				int floor = Math.min(lo, hi);
				int ceiling = Math.max(lo, hi);
				lo = Math.max(lowest, Math.min(highest, floor));
				hi = Math.max(lowest, Math.min(highest, ceiling));
			}

			/** A voice sounding once per note, which is what every voice was before counts. */
			public Voice(String instrument, int lo, int hi) {
				this(instrument, lo, hi, 1);
			}

			public Voice withCount(int value) {
				return new Voice(instrument, lo, hi, value);
			}

			public Voice withBracket(int low, int high) {
				return new Voice(instrument, low, high, count);
			}

			/** The whole register the instrument has, which is what a fresh bracket starts as. */
			public static Voice fullRange(String instrument) {
				return new Voice(instrument, InstrumentRanges.lowestMidi(instrument),
					InstrumentRanges.highestMidi(instrument));
			}

			public boolean covers(int midiNote) {
				return midiNote >= lo && midiNote <= hi;
			}
		}

		/** Whether any voice sounds this note. */
		public boolean covers(int midiNote) {
			for (Voice voice : voices) {
				if (voice.covers(midiNote)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * The melodic default: one voice per tier at its full register, F#1 to F#7 with every
		 * adjacent pair overlapping by an octave.
		 */
		public static Split melodic() {
			return new Split(List.of(
				Voice.fullRange("BASS"),
				Voice.fullRange("GUITAR"),
				Voice.fullRange("HARP"),
				Voice.fullRange("FLUTE"),
				Voice.fullRange("BELL")
			));
		}

		/**
		 * The melodic default with a layer's own instruments kept on it: each tuned voice the layer
		 * already sounds takes its tier at full register and at its count, and the default voices on
		 * that tier step aside. A copper trumpet layer made melodic has copper in the middle, not
		 * harp; a harp-and-pling layer has both there.
		 *
		 * <p>Drums and sound effects keep nothing -- their registers are not where they sound -- so a
		 * layer with only those gets the plain default.</p>
		 */
		public static Split melodicKeeping(List<Voice> sounding) {
			List<Voice> kept = sounding.stream()
				.filter(voice -> !voice.instrument().startsWith(SOUND_EFFECT_PREFIX)
					&& !InstrumentRanges.isPercussion(voice.instrument()))
				.map(voice -> Voice.fullRange(voice.instrument()).withCount(voice.count()))
				.toList();
			Set<Integer> taken = new java.util.HashSet<>();
			kept.forEach(voice -> taken.add(InstrumentRanges.baseMidi(voice.instrument())));
			List<Voice> voices = new ArrayList<>(kept);
			for (Voice voice : melodic().voices()) {
				if (!taken.contains(InstrumentRanges.baseMidi(voice.instrument()))) {
					voices.add(voice);
				}
			}
			return new Split(voices);
		}

		/**
		 * {@link #melodicKeeping} cut down to the tiers a layer's notes actually sound on, the rest
		 * left as empty registers for the keyboard to offer back.
		 *
		 * <p>Every voice that covers at least one note stays, overlaps included: a note in the octave
		 * two tiers share keeps both, exactly as it would sound on the full split. Only a tier no
		 * note reaches is dropped. An empty layer has nothing to measure and gets every tier; so
		 * does a layer whose notes no tier reaches at all.</p>
		 */
		public static Split melodicFor(List<Voice> sounding, List<NoteEvent> notes) {
			Split full = melodicKeeping(sounding);
			List<Voice> used = full.voices().stream()
				.filter(voice -> notes.stream().anyMatch(note -> voice.covers(note.midiNote())))
				.toList();
			return used.isEmpty() ? full : new Split(used);
		}

		/** The percussion default: kick, snare and hats stacked low to high, no gaps, no overlap. */
		public static Split percussion() {
			return new Split(List.of(
				Voice.fullRange("BASEDRUM"),
				Voice.fullRange("SNARE"),
				Voice.fullRange("HAT")
			));
		}

		/**
		 * The sound effect default: six voices in four-row bands stacked across the harp window,
		 * so a drum-machine line of doors and pistons fits one layer without switching layers.
		 *
		 * <p>Nothing about the choice of six is load-bearing -- an effect voice's bracket is only a
		 * band of rows, so the palette can swap any of them for any other. These are a starting
		 * set, placed where the roll usually already is.</p>
		 */
		public static Split soundEffects() {
			return new Split(List.of(
				new Voice("FX_OAK_DOOR", 54, 57),
				new Voice("FX_IRON_TRAPDOOR", 58, 61),
				new Voice("FX_BELL", 62, 65),
				new Voice("FX_COPPER_BULB", 66, 69),
				new Voice("FX_DROPPER", 70, 73),
				new Voice("FX_PISTON", 74, 77)
			));
		}

		private static List<Voice> normalizeVoices(List<Voice> value) {
			if (value == null || value.isEmpty()) {
				return List.of();
			}
			// Ordered by register, lowest voice first, and by name within a register. The order is
			// what assigns brackets their columns in the keyboard, so it is pinned to the one thing
			// a drag cannot change -- sorting by the bracket's own edge would make the columns trade
			// places under the hand moving them.
			return List.copyOf(value.stream()
				.filter(java.util.Objects::nonNull)
				.sorted(Comparator
					.comparingInt((Voice voice) -> InstrumentRanges.baseMidi(voice.instrument()))
					.thenComparing(Voice::instrument))
				.toList());
		}
	}

	/**
	 * A length a sustain setting is picked in: a redstone tick, a note value, or the finest step
	 * the song will really play.
	 */
	public enum SustainLength {
		FINEST("Finest"),
		GAME_TICK("Game tick"),
		REPEATER_TICK("Repeater tick"),
		// Coarser redstone steps, for a held note that should pulse rather than buzz. Whole repeater
		// ticks, so like the two above they always land on the build's own grid, which a note value
		// only does where the tempo happens to divide it.
		TWO_REPEATER_TICKS("2 repeater ticks"),
		FOUR_REPEATER_TICKS("4 repeater ticks"),
		// Note values, said as notes: "1/4" alone read as a quarter of a bar. A bar is four quarter
		// notes, since the composer has no time signature and counts every song in 4/4.
		THIRTY_SECOND("1/32 note"),
		SIXTEENTH("1/16 note"),
		EIGHTH("1/8 note"),
		QUARTER("1/4 note"),
		HALF("1/2 note"),
		BAR("1 bar");

		/** What "Sustain after" offers: every length but Finest, which is a rate and not a length. */
		public static final List<SustainLength> AFTER_CHOICES = List.of(GAME_TICK, REPEATER_TICK,
			TWO_REPEATER_TICKS, FOUR_REPEATER_TICKS, THIRTY_SECOND, SIXTEENTH, EIGHTH, QUARTER, HALF, BAR);
		/** What "Strike every" offers. */
		public static final List<SustainLength> EVERY_CHOICES = List.of(FINEST, GAME_TICK,
			REPEATER_TICK, TWO_REPEATER_TICKS, FOUR_REPEATER_TICKS, THIRTY_SECOND, SIXTEENTH, EIGHTH, QUARTER);

		public final String label;

		SustainLength(String label) {
			this.label = label;
		}
	}

	/**
	 * How a layer turns long notes into repeated strikes.
	 *
	 * <p>A note block cannot hold a note, so a sustained note is that note struck again and again
	 * for as long as it lasts. {@code after} is how long a note must be before it does that at all
	 * -- shorter notes stay single strikes -- and {@code every} is how far apart the strikes fall.
	 * Nothing is stored per note: the strikes are worked out from each note's length whenever
	 * something needs them, see {@link Strikes}.</p>
	 */
	public record Sustain(boolean on, SustainLength after, SustainLength every, Boolean align) {
		// A quarter note before a note sustains, striking every repeater tick: the user's choice on
		// 2026-09-18, over the half note at Finest the census had picked. What it costs, priced on
		// 2026-09-14 over the 24 two-lane songs at six sizes with every layer sustaining: repeater
		// tick after a quarter refused 12 builds and added 4 dead ones, where Finest after a half
		// refused 6 and added none.
		public static final Sustain DEFAULT = new Sustain(false, SustainLength.QUARTER,
			SustainLength.REPEATER_TICK, true);

		/**
		 * @param align whether every strike moves to the nearest tick the build can place; see
		 *     {@link ComposerProject#sustainGrid}. Boxed so a layer saved before the choice existed
		 *     reads as null and comes in aligned, which is what it had been getting.
		 */
		public Sustain {
			after = after == null || after == SustainLength.FINEST ? SustainLength.QUARTER : after;
			every = every == null ? SustainLength.REPEATER_TICK : every;
			align = align == null || align;
		}

		/** Aligned to the build's ticks, which is what a layer wants unless it says otherwise. */
		public Sustain(boolean on, SustainLength after, SustainLength every) {
			this(on, after, every, true);
		}

		/** Whether strikes land on the build's ticks rather than exactly on the note value. */
		public boolean aligned() {
			return align;
		}

		public Sustain withOn(boolean value) {
			return new Sustain(value, after, every, align);
		}

		public Sustain withAfter(SustainLength value) {
			return new Sustain(on, value, every, align);
		}

		public Sustain withEvery(SustainLength value) {
			return new Sustain(on, after, value, align);
		}

		public Sustain withAlign(boolean value) {
			return new Sustain(on, after, every, value);
		}
	}

	/**
	 * Where one layer's notes strike again: a grid shared by the whole song, and the length a note
	 * needs before it sustains at all.
	 *
	 * <p>The grid is counted from the song's first note, the same origin the analysis measures the
	 * redstone grid from, and every note on every layer with the same step shares it. Counting from
	 * each note's own start would space a note's strikes more evenly and scatter the song's: two
	 * sustains a tick apart would never strike together, and every strike that shares no time with
	 * another is one more event a build has to place.</p>
	 *
	 * @param origin the composer tick the grid is counted from
	 * @param step composer ticks between strikes, never below one
	 * @param after composer ticks a note must last before it strikes again
	 */
	public record Strikes(double origin, double step, double after, double grid) {
		public Strikes {
			step = Math.max(1.0, step);
			grid = Math.max(0.0, grid);
		}

		/** Strikes where the step puts them, aligned to nothing. */
		public Strikes(double origin, double step, double after) {
			this(origin, step, after, 0.0);
		}

		/** Whether a note is long enough to sustain. */
		public boolean sustained(NoteEvent note) {
			return note.durationTicks() + 1.0e-6 >= after;
		}

		/**
		 * Every tick a note strikes again, in order, from {@code from} to {@code to} inclusive.
		 *
		 * <p>The first is the first grid line at least half a step after the note starts, and the last
		 * falls before the note ends, never on it. The note's own start is not one of them: that
		 * strike is the note. Half a step rather than a whole one, because the grid is the song's and
		 * not the note's: a note starting a game tick past a line used to skip the next one for being
		 * a tick short of a full step, and its first gap came out nearly twice the rest. Half keeps the
		 * first gap within half a step of the others and still never strikes right on top of the
		 * note.</p>
		 *
		 * <p>With a {@code grid}, each strike then moves to the nearest line of it -- the build's own
		 * ticks, counted from the same origin -- so a note value the tempo does not divide still
		 * lands where a repeater can put it. Two strikes moved onto one line are one strike, and one
		 * moved onto the note's start or its end is left out, as it would have been there anyway.</p>
		 *
		 * <p>A note's own {@link NoteEvent#strikeShift} slides its lines along, all together, before
		 * any of that: the grid stays the song's, the note just keeps its own phase on it. The build's
		 * grid does not slide with it -- that is where a repeater can put a strike, whoever asks.</p>
		 */
		public void forEach(NoteEvent note, long from, long to, java.util.function.LongConsumer strike) {
			if (!sustained(note)) {
				return;
			}
			long start = note.startTick();
			long end = start + note.durationTicks();
			double phase = origin + note.strikeShiftTicks();
			long first = (long)Math.ceil((start + step / 2.0 - phase) / step - 1.0e-9);
			// Floored, and the tick itself checked: a line just under from can round onto it. A grid
			// can move a strike back by half its line, so the window opens that much earlier.
			long window = (long)Math.floor((from - grid - phase) / step);
			long last = Long.MIN_VALUE;
			for (long index = Math.max(first, window); ; index++) {
				long tick = Math.round(phase + index * step);
				if (tick >= end || tick > to + grid) {
					return;
				}
				if (grid > 0.0) {
					tick = Math.round(origin + Math.round((tick - origin) / grid) * grid);
				}
				if (tick > start && tick < end && tick >= from && tick <= to && tick != last) {
					strike.accept(tick);
					last = tick;
				}
			}
		}

		/** How many times a note sounds: its own strike, and every one after it. */
		public int soundings(NoteEvent note) {
			int[] count = {1};
			forEach(note, 0L, Long.MAX_VALUE, tick -> count[0]++);
			return count[0];
		}
	}

	/**
	 * What {@link #convertToMinecraft} moves when a layer will not fit the note-block range.
	 *
	 * <p>All three end with every note in range, because the last step of each is the same per-note
	 * octave shift and that can never fail: the window is 25 semitones, so every pitch class has an
	 * octave inside it. What differs is what moves -- how much of the part travels together, or
	 * whether a note moves layer rather than pitch.</p>
	 *
	 * <p>Multiples of twelve throughout, and that is not a detail. A whole-song transpose may move
	 * by any interval, because everything moves with it and the song simply lands in a new key. One
	 * layer moved by three semitones is not in a different octave, it is in a different key from
	 * every other part -- so a shift applied to a part is always an octave.</p>
	 */
	public enum OctaveShifting {
		/**
		 * Only the notes that are out of range move, each by its own nearest octave.
		 *
		 * <p>Nothing in range is touched, and a layer straddling the window splits once per distinct
		 * octave the notes needed.</p>
		 */
		NOTES_ONLY,
		/**
		 * The layer moves as a unit to wherever the fewest of its notes are out of range, and then
		 * whatever is still out moves note by note.
		 *
		 * <p>Fewer splits, because the bulk of the layer ends up needing one shift rather than two.
		 * The cost is that notes with nothing wrong with them can move, when moving them catches
		 * more strays than it creates -- a layer already wholly in range scores nothing at all at
		 * shift zero, so it stays where it is.</p>
		 */
		LAYER_THEN_NOTES,
		/**
		 * Out-of-range notes move to a melodic split layer beside their own, and only what no
		 * bracket there reaches is shifted.
		 *
		 * <p>The other two answer a note outside the harp window by folding it into the window,
		 * which is the one repair that cannot be made without changing the music: a bass line an
		 * octave under the tune comes back sitting on top of it. This moves the note rather than
		 * the pitch. A melodic split reads written pitch as true pitch across F#1 to F#7
		 * ({@link Split#melodic}), so the note keeps what it was written at and sounds on the tier
		 * that actually lives there -- bass low, bell high.</p>
		 *
		 * <p>One companion layer per source layer, catching both ends at once. The transposing
		 * modes give the notes under the window one layer and the notes over it another, each
		 * named by the octave it took; here there is no octave to name, because nothing inside six
		 * octaves moves at all. A layer with nothing left in the window becomes the melodic layer
		 * itself rather than emptying out beside one.</p>
		 *
		 * <p>What it costs is blocks. A split layer sounds every voice whose bracket covers the
		 * note and the melodic tiers overlap by an octave, so a note in an overlap builds twice, on
		 * two lanes. That is what the overlap is for and a surprise when nobody asked for it: drag
		 * a bracket in afterwards to get single notes back.</p>
		 */
		SPLIT_INTO_MELODIC,
		/**
		 * A layer that has a note out of range becomes a melodic split layer, whole and in place.
		 *
		 * <p>The same repair as {@link #SPLIT_INTO_MELODIC} without the second layer. That one
		 * keeps the part's instrument for the notes the harp window can hold and puts the rest on a
		 * companion, which is two rows for one part and two timbres inside it -- a piano whose low
		 * notes come back as a bass. This asks the question the other way round: if the part needs
		 * a layer that reaches six octaves, give the part that layer. Nothing is added, nothing is
		 * split, and the part is one thing again.</p>
		 *
		 * <p>What it gives up is the instrument. A pling layer that strays once stops being a pling
		 * layer and starts being bass, guitar, harp, flute and bell by register -- so the part's
		 * timbre now follows its pitch, which is a musical decision and not always the wanted one.
		 * The layer count is the compensation: this is the only mode that can bring a whole song
		 * into range without adding a single layer, which is what a song already near the
		 * {@value ComposerProject#MAX_LAYERS}-layer limit needs.</p>
		 *
		 * <p>Doubling costs more here than it does next door, and for the same reason: the notes
		 * that were always in range are on the split too now, and the harp window sits under the
		 * guitar and flute brackets as well as the harp one. Left alone, most of a converted layer
		 * builds twice. Dragging the brackets apart is the fix, and it is worth doing.</p>
		 *
		 * <p>Layers that already fit are not touched. The mode answers a part that cannot be
		 * built, not every part in the song.</p>
		 */
		CONVERT_TO_MELODIC
	}

	public record MinecraftConversion(
		ComposerProject project,
		int shiftedNotes,
		int addedLayers,
		boolean tempoChanged,
		double tempoFactor,
		int mergedRepeats,
		int duplicateLayers,
		int duplicateLayerNotes,
		/** Notes that landed on a pitch and tick their layer already held, and so became one note. */
		int mergedIntoExisting,
		/**
		 * Notes moved onto a melodic split layer instead of being folded into the harp window; see
		 * {@link OctaveShifting#SPLIT_INTO_MELODIC}. Nearly all of them keep the pitch they were written
		 * at, which is the point of the mode and the reason they are not in {@link #shiftedNotes}.
		 */
		int melodicNotes,
		/**
		 * How many layers came out with a melodic split on them -- added by
		 * {@link OctaveShifting#SPLIT_INTO_MELODIC}, or converted in place by
		 * {@link OctaveShifting#CONVERT_TO_MELODIC}, which adds none and would otherwise report
		 * a song-wide change as no change at all.
		 */
		int melodicLayers,
		/**
		 * For each layer of {@link #project}, the index of the layer it came from, so a selection of
		 * layers can follow them past the layers a conversion adds.
		 */
		List<Integer> sourceLayers
	) {
		/**
		 * How much slower the converted song plays. Greater than 1 means the source was faster than
		 * redstone can represent — a repeater cannot delay less than one tick, so a song wanting
		 * more than 10 events per second has to be stretched to fit.
		 */
		public boolean slowedDown() {
			return tempoFactor > 1.01;
		}

		/**
		 * Whether the converted song plays faster than the source did.
		 *
		 * <p>Aligning to the repeater grid moves the tempo in whichever direction is nearest, so
		 * conversion speeds a song up about as often as it slows one down. Only the slowdown used
		 * to be reported, which left the other half of the same event saying nothing more than
		 * "tempo aligned" -- true, and no help at all to anyone wondering why the song they knew
		 * now runs ahead of them.</p>
		 */
		public boolean spedUp() {
			return tempoFactor < 0.99;
		}

		/**
		 * How many times faster the converted song plays, the reciprocal of the tempo factor.
		 *
		 * <p>Tempo here is microseconds per quarter, so a smaller number is a faster song and the
		 * raw factor reads backwards for a speed-up.</p>
		 */
		public double speedFactor() {
			return tempoFactor <= 0.0 ? 1.0 : 1.0 / tempoFactor;
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
	 *
	 * <p>A voice with a count is the exception, and not through this record: its notes skip
	 * deduplication altogether, see {@link #toSequenceTracks}.</p>
	 */
	public record NoteSound(String instrument, int midiNote, long startTick) {
		public static NoteSound of(Layer layer, NoteEvent note) {
			// Two sound effect layers on the same block, hit on the same tick, are one sound however
			// far apart their rows are drawn. Only a pitched voice can tell two rows apart.
			return new NoteSound(layer.instrument(), layer.pitched() ? note.midiNote() : 0,
				note.startTick());
		}
	}

	/**
	 * @param dedupeIdentical drop a note when an earlier layer already plays that sound at that
	 *     instant. Nothing is deleted -- the note stays in the composition and comes back the
	 *     moment the layers stop agreeing, which is what changing one layer's instrument does.
	 */
	public List<SequenceTrack> toSequenceTracks(Set<Integer> layerIndices, boolean dedupeIdentical) {
		return toSequenceTracks(layerIndices, dedupeIdentical, SongAnalysis.MAX_SIMULTANEOUS_NOTES);
	}

	/**
	 * @param thinTarget the most note blocks a tick may hold before its strikes and extra copies are
	 *     left out; see {@link ChordSkips}. The chord thinning preference, or the cap.
	 */
	public List<SequenceTrack> toSequenceTracks(Set<Integer> layerIndices, boolean dedupeIdentical,
			int thinTarget) {
		return toSequenceTracks(layerIndices, dedupeIdentical, ChordSkips.Rules.at(thinTarget));
	}

	/** @param thinning what the chord limit may leave out, and above how many note blocks */
	public List<SequenceTrack> toSequenceTracks(Set<Integer> layerIndices, boolean dedupeIdentical,
			ChordSkips.Rules thinning) {
		List<SequenceTrack> result = new ArrayList<>();
		Set<NoteSound> heard = dedupeIdentical ? new java.util.HashSet<>() : null;
		// Sustains become strikes before anything is deduplicated or rounded: a strike is a note the
		// build places, and every gap is rounded once, on its own.
		double finest = layers.stream().anyMatch(Layer::sustains) ? finestSustainStep() : 0.0;
		ChordSkips skips = ChordSkips.of(this, dedupeIdentical, thinning, finest);
		for (int index = 0; index < layers.size(); index++) {
			Layer layer = layers.get(index);
			boolean chosen = layerIndices == null || layerIndices.isEmpty()
				? layer.inBuild()
				: layerIndices.contains(index);
			if (!chosen) {
				continue;
			}
			// A split layer is several tracks: one per voice, expanded before the deduplication so
			// that a doubled note two split layers agree on collapses the way any other sound does.
			//
			// A counted voice is left out of the deduplication both ways: none of its notes are
			// dropped, and none of them count as heard. The count is somebody asking for a louder
			// note, and collapsing it -- or letting it collapse a note on another layer -- would
			// quietly undo that. Its copies are expanded only after.
			List<Layer> voices = placedForBuild(layer, finest).buildVoices();
			for (int voiceIndex = 0; voiceIndex < voices.size(); voiceIndex++) {
				Layer voice = voices.get(voiceIndex);
				Layer projected = heard == null || voice.copies() > 1
					? voice : withoutAlreadyHeard(voice, heard);
				// What the chord limit leaves out comes off last, after deduplication, so it is
				// weighed against the chord the build really places: a skipped strike, and the
				// copies a crowded tick has no room for.
				for (Layer copy : skips.copiesOf(index, voiceIndex, projected)) {
					result.add(new SequenceTrack(copy.name(), toText(copy), copy.instrument(), 0, true));
				}
			}
		}
		return List.copyOf(result);
	}

	/**
	 * A layer as the build starts from it, before its voices are expanded: sustains struck out, and
	 * every stack as the one note at the bottom of it.
	 *
	 * <p>Always, whatever Merge duplicate notes is set to. A stack is a copy that landed on a note --
	 * a paste, a drag -- not a way of asking for more of it: louder is a count in the palette, or the
	 * note on a second layer, and both of those say so where you can see them. So a stack never
	 * changes what is heard, and a stack of two on a harp played three times is three note blocks.</p>
	 *
	 * <p>The one place a stack is merged, so preview, the analysis, the chord limit, the build and
	 * the export cannot disagree about it.</p>
	 *
	 * @param finest what Finest is, or 0 for a song with no sustain
	 */
	public Layer placedForBuild(Layer layer, double finest) {
		Layer placed = finest > 0.0 ? withSustainsExpanded(layer, finest) : layer;
		return placed.withStacksMerged();
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
	 * Folds every selected layer into the lowest-numbered one, which keeps its name and flags. Notes
	 * are re-sorted by the layer constructor, so overlapping material interleaves.
	 *
	 * <p>The voices are a union, not the first layer's: every instrument any of the layers sounds
	 * sounds on the result, at the largest count it had and over the widest bracket. Ordinary layers
	 * alone come out as one ordinary layer stacking all their instruments. Once any of them is a
	 * split -- melodic, drums or effects -- the result is a split, the most general of the three,
	 * and the ordinary layers' instruments join it as full-register voices.</p>
	 *
	 * <p>Joining a split is where an ordinary layer's notes change meaning: written in the harp
	 * window, they become true pitch. So they move by their instrument's register first -- nothing
	 * for the harp tier, two octaves down for a bass -- and land on the bracket that sounds them as
	 * they sounded. The drums' registers are virtual but the same arithmetic holds, so a snare keeps
	 * the pitch value that picked its sound.</p>
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
		boolean anySplit = sorted.stream().anyMatch(index -> layers.get(index).split() != null);
		List<NoteEvent> merged = new ArrayList<>();
		Map<String, Split.Voice> voices = new LinkedHashMap<>();
		for (int index : sorted) {
			Layer layer = layers.get(index);
			boolean joiningSplit = anySplit && layer.split() == null;
			int shift = joiningSplit && layer.pitched()
				? InstrumentRanges.baseMidi(layer.instrument()) - NOTE_BLOCK_BASE_MIDI_NOTE
				: 0;
			for (NoteEvent note : layer.notes()) {
				merged.add(shift == 0 ? note : note.movedTo(note.startTick(),
					Math.max(0, Math.min(127, note.midiNote() + shift))));
			}
			for (Split.Voice voice : layer.sounding()) {
				Split.Voice incoming = joiningSplit
					? Split.Voice.fullRange(voice.instrument()).withCount(voice.count())
					: voice;
				voices.merge(voice.instrument(), incoming, (had, added) -> new Split.Voice(
					had.instrument(), Math.min(had.lo(), added.lo()), Math.max(had.hi(), added.hi()),
					Math.max(had.count(), added.count())));
			}
		}
		Layer into = layers.get(target);
		List<Split.Voice> union = List.copyOf(voices.values());
		into = anySplit ? into.withSplit(new Split(union)) : into.withMix(union);
		List<Layer> updated = new ArrayList<>();
		int mergedIndex = 0;
		for (int index = 0; index < layers.size(); index++) {
			if (index == target) {
				mergedIndex = updated.size();
				// Two layers playing the same note merge into one note, not a stack: merging is
				// asking for one part, and the stack would only ever have played once anyway.
				updated.add(into.withNotes(merged).withStacksMerged());
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
	/** The speed as a multiplier, and the only place the unit is divided out. */
	public double speedFactor() {
		return Math.max(MIN_SPEED_EIGHTHS, speedEighths) / 8.0;
	}

	public boolean sameContentAs(ComposerProject other) {
		return other != null
			&& name.equals(other.name)
			&& ppq == other.ppq
			&& tempoMicrosPerQuarter == other.tempoMicrosPerQuarter
			&& endTick == other.endTick
			&& speedEighths == other.speedEighths
			&& markers.equals(other.markers)
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
	 * Where every layer ends up if the given ones are lifted out and dropped into {@code insertion},
	 * as a list of the positions they held before the move.
	 *
	 * <p>Handed back rather than kept inside {@link #moveLayersTo} because the screen holds two more
	 * sets of layer positions -- which rows are selected and which are soloed -- and a reorder that
	 * renumbers the layers without renumbering those leaves both of them pointing at whatever slid
	 * into the vacated row.</p>
	 *
	 * <p>{@code insertion} counts the gaps between rows as they stand now, so it runs from zero to
	 * the layer count and a block dropped below where it started lands short of that gap once the
	 * block itself is out of the list.</p>
	 */
	public List<Integer> layerOrderAfterMove(Set<Integer> layerIndices, int insertion) {
		List<Integer> unchanged = new ArrayList<>();
		for (int index = 0; index < layers.size(); index++) {
			unchanged.add(index);
		}
		if (layerIndices == null || layerIndices.isEmpty() || layers.size() <= 1) {
			return unchanged;
		}
		List<Integer> moving = layerIndices.stream()
			.filter(index -> index >= 0 && index < layers.size())
			.distinct()
			.sorted()
			.toList();
		if (moving.isEmpty() || moving.size() == layers.size()) {
			return unchanged;
		}
		int gap = Math.max(0, Math.min(layers.size(), insertion));
		List<Integer> order = new ArrayList<>();
		for (int index = 0; index < layers.size(); index++) {
			if (!moving.contains(index)) {
				order.add(index);
			}
		}
		int landing = gap - (int)moving.stream().filter(index -> index < gap).count();
		order.addAll(Math.max(0, Math.min(order.size(), landing)), moving);
		return order;
	}

	/**
	 * Lifts the given layers out and drops them into one gap, keeping their order among themselves.
	 *
	 * <p>A selection reorders as a block. Moving them one at a time would be a different operation
	 * -- three layers each stepping up one past whatever is above them turns them inside out the
	 * moment anything unselected is between them -- and the reason to select several is that they
	 * belong together.</p>
	 */
	public ComposerProject moveLayersTo(Set<Integer> layerIndices, int insertion) {
		return withLayerOrder(layerOrderAfterMove(layerIndices, insertion));
	}

	/** Rearranges the layers into {@code order}, a permutation of their current positions. */
	public ComposerProject withLayerOrder(List<Integer> order) {
		if (order == null || order.size() != layers.size()) {
			return this;
		}
		List<Layer> updated = new ArrayList<>(order.size());
		for (int index : order) {
			if (index < 0 || index >= layers.size()) {
				return this;
			}
			updated.add(layers.get(index));
		}
		int active = order.indexOf(activeLayerIndex);
		return with(updated, active < 0 ? activeLayerIndex : active, nextNoteId);
	}

	/**
	 * Copies every given layer, the copies together as one block directly after the lowest of them,
	 * in the order their sources stand.
	 *
	 * <p>A block rather than each copy under its own source, because a selection is duplicated in
	 * order to do something to all of it at once -- move it, re-voice it, merge it -- and copies
	 * interleaved with their sources had to be picked out one at a time to be worked on together.</p>
	 *
	 * <p>All or nothing against the layer cap: half a duplication is a song with some parts doubled
	 * and some not, which is harder to undo by hand than it is to not do.</p>
	 */
	public ComposerProject duplicateLayers(Set<Integer> layerIndices) {
		if (layerIndices == null || layerIndices.isEmpty()) {
			return this;
		}
		List<Integer> sources = layerIndices.stream()
			.filter(index -> index >= 0 && index < layers.size())
			.distinct()
			.sorted()
			.toList();
		if (sources.isEmpty() || layers.size() + sources.size() > MAX_LAYERS) {
			return this;
		}
		long nextId = nextNoteId;
		List<Layer> copies = new ArrayList<>(sources.size());
		Set<String> taken = new java.util.HashSet<>();
		layers.forEach(layer -> taken.add(layer.name()));
		for (int index : sources) {
			Layer source = layers.get(index);
			List<NoteEvent> copied = new ArrayList<>(source.notes().size());
			for (NoteEvent note : source.notes()) {
				copied.add(note.withId(nextId++));
			}
			String name = copyName(source.name(), taken);
			taken.add(name);
			copies.add(source.withName(name).withNotes(copied));
		}
		int at = sources.getLast() + 1;
		List<Layer> updated = new ArrayList<>(layers);
		updated.addAll(at, copies);
		// Onto the first copy: a duplicate is made in order to change it.
		return with(updated, at, nextId);
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
		double window = repeatMergeTicks * SongAnalysis.redstoneTickSpan(this);
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
		return buildGridTicks(false);
	}

	/** The same, in whichever tick the build will be able to place. */
	public long buildGridTicks(boolean gameTicks) {
		return buildGrid(gameTicks).gridTicks();
	}

	private record RepeaterGrid(long gridTicks, long repeaterTicks, int tempo) {
	}

	private RepeaterGrid repeaterGrid() {
		return buildGrid(false);
	}

	/**
	 * The finest composer-tick grid whose steps are whole build ticks.
	 *
	 * @param gameTicks measure in game ticks rather than repeater ticks. A game tick is half a
	 *     repeater tick, so the denominator doubles and the grid comes out half as coarse -- which
	 *     is the whole of what a second lane buys the composer.
	 */
	private RepeaterGrid buildGrid(boolean gameTicks) {
		long numerator = ppq * 100_000L * Math.max(MIN_SPEED_EIGHTHS, speedEighths);
		long perBuildTick = gameTicks ? 2L : 1L;
		long denominator = tempoMicrosPerQuarter * 8L * perBuildTick;
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
		//
		// perBuildTick belongs here as well as in the denominator above, and did not used to. The
		// tempo handed back was the one that makes `grid` a whole *repeater* tick, while `grid` had
		// been measured in game ticks -- half as much. The two disagreed by exactly that factor of
		// two, so quantizing to game ticks at any tempo reaching this branch halved the song and
		// then landed it on the repeater grid: 128 BPM came back as 63.75, on the wrong grid, from
		// the button whose only purpose is the other one. Every tempo tested took the exact branch
		// above, where the arithmetic is shared and the fault cannot show.
		// Eight, matching the numerator's unit. The numerator counts eighths of the speed, and
		// this read four -- so the tempo it handed back was twice what the grid wanted and every
		// song reaching this branch came out at half speed. 128 BPM returned as 63.75, which is
		// the same shape of fault the comment above records, one unit change later. The exact
		// branch shares its arithmetic with the numerator and could not show it.
		return new RepeaterGrid(grid, 1L,
			Math.max(1, (int)Math.ceil(numerator / (8.0 * perBuildTick * grid))));
	}

	public RepeaterQuantize withQuantizedToRepeaters(Set<Long> scope) {
		return withQuantizedToBuildTicks(scope, false);
	}

	/**
	 * Note starts moved onto the grid a build can place, in whichever tick it counts in.
	 *
	 * @param gameTicks aim at the game-tick grid, which is twice as fine and so moves each note at
	 *     most half as far. What it costs is the second lane: a song with anything landing between
	 *     repeater ticks needs both, and the status bar says so.
	 */
	public RepeaterQuantize withQuantizedToBuildTicks(Set<Long> scope, boolean gameTicks) {
		RepeaterGrid target = buildGrid(gameTicks);
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
		// Neighbours quantized onto one tick at one pitch have become one note, not a stack.
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> !inScope(note, scope) ? note : note.movedTo(
					Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid),
					note.midiNote()))
				.toList()).withStacksMerged())
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
		// A split layer's notes are not held to the harp window at all -- true pitch is the whole
		// point of one -- so folding them into it would wreck exactly the notes the layer exists
		// to keep. They are left alone; a note outside every bracket is the handles' business.
		List<Layer> updated = layers.stream()
			.map(layer -> layer.split() != null ? layer : layer.withNotes(layer.notes().stream()
				.map(note -> note.isBuildable() || !inScope(note, scope)
					? note
					: note.movedTo(note.startTick(),
						note.midiNote() + octaveShiftIntoNoteBlockRange(note.midiNote())))
				.toList()).withStacksMerged())
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/**
	 * What one whole-song shift would cost, against what standing still costs.
	 *
	 * @param semitones the shift itself, zero when nothing beats staying put
	 * @param outNow notes outside the note block's range as the song stands
	 * @param outAfter notes still outside it after the shift
	 * @param melodyOutNow top-voice notes outside it as the song stands
	 * @param melodyOutAfter top-voice notes still outside it after the shift
	 */
	public record TransposeFit(int semitones, long outNow, long outAfter, long melodyOutNow,
		long melodyOutAfter) {

		public boolean worthDoing() {
			return semitones != 0 && (melodyOutAfter < melodyOutNow || outAfter < outNow);
		}
	}

	/**
	 * The whole-song shift that leaves the least of the tune outside what a note block can play.
	 *
	 * <p>A different question from the one conversion asks. Conversion moves each out-of-range note
	 * by whole octaves into a window that never moves, which keeps the note's letter and breaks its
	 * place in the line -- a note that was a step below its neighbour comes back an octave above it.
	 * Moving the whole song instead keeps every interval exactly and changes only the key, so what it
	 * costs is the thing nobody can hear and what it saves is the thing everybody can.</p>
	 *
	 * <p>Weighted, because "fewest notes out of range" is the wrong thing to minimise. A song's bass
	 * has more notes than its tune and is the part you would rather sacrifice: an octave jump in a
	 * bass line reads as a bass line, and an octave jump in the melody reads as a mistake. The top
	 * note sounding at any instant is taken as the tune -- the oldest heuristic there is for finding
	 * a melody, and a good one on everything that is not a fugue -- and counts triple. That is what
	 * pushes the window up to keep the high notes rather than down to keep the many.</p>
	 *
	 * <p>Only layers in the build are measured, since they are the only ones that have to fit, but
	 * {@link #transposedBy} moves everything: a shift applied to some layers and not others is not a
	 * key change, it is two songs at once.</p>
	 */
	public TransposeFit bestTransposeIntoRange() {
		return bestTransposeIntoRange(MELODY_WEIGHT);
	}

	/** @param melodyWeight what a top-voice note counts for; exposed so a probe can sweep it. */
	public TransposeFit bestTransposeIntoRange(int melodyWeight) {
		// Split layers are left out of the measurement: their notes are not judged against the
		// harp window, so counting them would charge the shift for notes that were never out of
		// range. The transpose itself still moves them -- a key change is the whole song or it is
		// two songs -- and any note it pushes outside a bracket shows up as out of range after.
		List<NoteEvent> measured = layers.stream()
			.filter(layer -> layer.inBuild() && layer.split() == null)
			.flatMap(layer -> layer.notes().stream())
			.toList();
		if (measured.isEmpty()) {
			return new TransposeFit(0, 0L, 0L, 0L, 0L);
		}
		// The top voice at each instant, which is the melody often enough to steer by.
		Map<Long, Integer> ceiling = new LinkedHashMap<>();
		for (NoteEvent note : measured) {
			ceiling.merge(note.startTick(), note.midiNote(), Math::max);
		}
		int lowest = measured.stream().mapToInt(NoteEvent::midiNote).min().orElse(0);
		int highest = measured.stream().mapToInt(NoteEvent::midiNote).max().orElse(0);
		long bestCost = Long.MAX_VALUE;
		int bestShift = 0;
		// Every shift that keeps the song inside MIDI's own range, so nothing is silently clamped.
		for (int shift = -lowest; shift <= 127 - highest; shift++) {
			long cost = 0L;
			for (NoteEvent note : measured) {
				int moved = note.midiNote() + shift;
				if (moved >= NOTE_BLOCK_BASE_MIDI_NOTE && moved <= NOTE_BLOCK_MAX_MIDI_NOTE) {
					continue;
				}
				cost += note.midiNote() == ceiling.get(note.startTick()) ? melodyWeight : 1L;
			}
			if (cost < bestCost || cost == bestCost && Math.abs(shift) < Math.abs(bestShift)) {
				bestCost = cost;
				bestShift = shift;
			}
		}
		return new TransposeFit(bestShift,
			countOutOfRange(measured, 0, null), countOutOfRange(measured, bestShift, null),
			countOutOfRange(measured, 0, ceiling), countOutOfRange(measured, bestShift, ceiling));
	}

	/** Out-of-range notes after a shift; with a ceiling, only the top voice at each instant. */
	private static long countOutOfRange(List<NoteEvent> notes, int shift, Map<Long, Integer> ceiling) {
		long count = 0L;
		for (NoteEvent note : notes) {
			int moved = note.midiNote() + shift;
			if (moved >= NOTE_BLOCK_BASE_MIDI_NOTE && moved <= NOTE_BLOCK_MAX_MIDI_NOTE) {
				continue;
			}
			if (ceiling == null || note.midiNote() == ceiling.get(note.startTick())) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Moves every note in the song by the same number of semitones.
	 *
	 * <p>Every layer, including the ones left out of the build, because a key is a property of the
	 * song and not of what happens to be switched on. Refuses a shift that would push anything off
	 * the ends of MIDI rather than clamping into it, since a clamp would quietly stack notes on 0.</p>
	 */
	public ComposerProject transposedBy(int semitones) {
		if (semitones == 0) {
			return this;
		}
		for (Layer layer : layers) {
			for (NoteEvent note : layer.notes()) {
				int moved = note.midiNote() + semitones;
				if (moved < 0 || moved > 127) {
					return this;
				}
			}
		}
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> note.movedTo(note.startTick(), note.midiNote() + semitones))
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/** Tempo at which one grid step is a whole number of repeater ticks. */
	public int repeaterAlignedTempoFor(int gridTicks) {
		return alignedTempoFor(gridTicks, false);
	}

	/** The same, in game ticks when the build may use both lanes. */
	public int alignedTempoFor(int gridTicks, boolean gameTicks) {
		return alignedTempo(Math.max(1, gridTicks), gameTicks);
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
			.filter(Layer::inBuild)
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
			endTick, speedQuarters, speedEighths, markers);
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
		// Eighths, not quarters. Asked in quarters, a speed of 1.125x reads as the default and
		// baking returns the song untouched while the slider still says 1.125x, and 1.375x reads
		// as 1.25x and bakes the wrong factor into the tempo -- the one way this can change how a
		// song sounds rather than only how it is written.
		if (speedEighths == DEFAULT_SPEED_EIGHTHS) {
			return this;
		}
		double factor = speedFactor();
		return withTempo(Math.max(1, (int)Math.round(tempoMicrosPerQuarter / factor)))
			.withSpeedEighths(DEFAULT_SPEED_EIGHTHS);
	}

	public ComposerProject withName(String value) {
		return new ComposerProject(value, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex, nextNoteId,
			endTick, speedQuarters, speedEighths, markers);
	}

	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo) {
		return convertToMinecraft(quantizeTicks, snapTempo, 0);
	}

	/**
	 * @param repeatMergeTicks how many repeater ticks a repeat of the same pitch must clear to
	 *     survive; 0 disables merging. Songs that fake sustain by re-triggering a note every tick
	 *     are otherwise unbuildable, and force the whole song to be slowed to fit them.
	 */
	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo,
			int repeatMergeTicks) {
		return convertToMinecraft(quantizeTicks, snapTempo, repeatMergeTicks, false);
	}

	/**
	 * @param gameTicks fit the song to the game-tick grid rather than the repeater-tick one. The
	 *     build then needs two lanes -- one for each half of the tick -- and in exchange the tempo
	 *     moves at most half as far to reach the grid, and the grid the notes are quantised onto is
	 *     half as coarse. Every other step of the conversion is identical.
	 */
	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo,
			int repeatMergeTicks, boolean gameTicks) {
		return convertToMinecraft(quantizeTicks, snapTempo, repeatMergeTicks, gameTicks,
			OctaveShifting.NOTES_ONLY, true);
	}

	/**
	 * @param shifting what moves when a layer will not fit; see {@link OctaveShifting}
	 * @param splitTransposed whether notes that took a different octave from the rest of their layer
	 *     get a layer of their own. Off, the layer keeps them, and two source notes an octave apart
	 *     that land on one pitch become one note rather than one dropped layer.
	 */
	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo,
			int repeatMergeTicks, boolean gameTicks, OctaveShifting shifting,
			boolean splitTransposed) {
		return convertToMinecraft(quantizeTicks, snapTempo, repeatMergeTicks, gameTicks, shifting,
			splitTransposed, true);
	}

	/**
	 * @param fitRange whether a note its layer cannot reach is octave-shifted until the layer can.
	 *     Off, every note keeps the pitch it was written at and no layer splits -- which leaves
	 *     notes no note block can sound, so it is only ever what someone asked for. The other five
	 *     steps decline through arguments they already had: a grid of one moves nothing, a merge
	 *     window of nought merges nothing, and {@code snapTempo} is its own switch.
	 */
	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo,
			int repeatMergeTicks, boolean gameTicks, OctaveShifting shifting,
			boolean splitTransposed, boolean fitRange) {
		return convertToMinecraft(quantizeTicks, snapTempo, repeatMergeTicks, gameTicks, shifting,
			splitTransposed, fitRange, null, null);
	}

	/**
	 * @param layerScope the layers to convert, or null for all of them. A layer outside it comes
	 *     through exactly as it was.
	 * @param noteScope the notes whose range is fitted, or null for all of them. A note outside it
	 *     keeps its pitch and stays on its own layer, and a layer holding none of them comes through
	 *     exactly as it was. Only the range fitting reads it, so it is for a run that does nothing
	 *     else -- a scoped Fit into range. What it cannot scope is a decision about the whole layer:
	 *     Shift the layer measures the octave from the scoped notes and moves only them, but Convert
	 *     to melodic, once a scoped note needs it, still turns the whole part into the melodic layer.
	 */
	public MinecraftConversion convertToMinecraft(int quantizeTicks, boolean snapTempo,
			int repeatMergeTicks, boolean gameTicks, OctaveShifting shifting,
			boolean splitTransposed, boolean fitRange, Set<Integer> layerScope, Set<Long> noteScope) {
		int grid = Math.max(1, quantizeTicks);
		double repeatWindow = repeatMergeTicks <= 0
			? 0.0
			: repeatMergeTicks * SongAnalysis.redstoneTickSpan(this);
		int mergedRepeats = 0;
		int duplicateLayers = 0;
		int duplicateLayerNotes = 0;
		int mergedIntoExisting = 0;
		int melodicNotes = 0;
		int melodicLayers = 0;
		List<Layer> convertedLayers = new ArrayList<>();
		List<Integer> sourceLayers = new ArrayList<>();
		int convertedActiveLayer = 0;
		int shiftedNotes = 0;

		Comparator<Integer> shiftsNearestFirst = Comparator
			.comparingInt((Integer shift) -> Math.abs(shift))
			.thenComparingInt(Integer::intValue);
		for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
			Layer source = layers.get(layerIndex);
			if ((layerScope != null && !layerScope.contains(layerIndex))
					|| (noteScope != null
						&& source.notes().stream().noneMatch(note -> noteScope.contains(note.id())))) {
				if (layerIndex == activeLayerIndex) {
					convertedActiveLayer = convertedLayers.size();
				}
				convertedLayers.add(source);
				sourceLayers.add(layerIndex);
				continue;
			}
			java.util.function.Predicate<NoteEvent> inScope =
				note -> noteScope == null || noteScope.contains(note.id());
			List<NoteEvent> sourceNotes = mergeRepeats(source.notes(), repeatWindow);
			mergedRepeats += source.notes().size() - sourceNotes.size();
			Map<Integer, List<NoteEvent>> notesByShift = new TreeMap<>(shiftsNearestFirst);
			if (sourceNotes.isEmpty()) {
				notesByShift.put(0, List.of());
			}
			// A sound effect is not transposed at all, by either mode. There is no range for it to
			// be outside of -- toSteps does not filter an unpitched layer by range, so every note on
			// one builds wherever it is drawn, and the row a hit sits on is only somewhere to put
			// it. So the shift moved nothing and the split it caused was pure cost: a door written
			// low came out as "Door (+2 oct)" and "Door (+1 oct)", two layers against the
			// hundred-and-twenty-eight for a block that makes one noise.
			//
			// A split layer is not transposed either, for the opposite reason: its notes are
			// already true pitch and its brackets already reach them, so octave-folding it into
			// the harp window would undo the layer's whole purpose. Quantizing and repeat merging
			// still apply -- a split layer's notes live in time like anyone else's.
			boolean pitched = fitRange && source.pitched() && source.split() == null;
			// A split layer is fitted to its OWN brackets rather than skipped. Not to the harp
			// window -- that would undo the layer -- and not by moving the layer as a unit, which
			// means nothing when its voices already span five octaves. Only a note no voice can
			// reach moves, and only far enough that one can.
			boolean fitsToSplit = fitRange && source.split() != null && source.pitched();
			// Percussion is left out for the reason a sound effect is, one step further along. Its
			// 25 pitches are 25 timbres of one drum rather than notes on a scale, so the melodic
			// tiers say nothing about it: a snare that will not fit the window wants the window,
			// not a bell. Those layers fall through to the per-note shift below, which is what
			// they got before these modes existed and still the right answer for them.
			boolean melodic = pitched
				&& (shifting == OctaveShifting.SPLIT_INTO_MELODIC
					|| shifting == OctaveShifting.CONVERT_TO_MELODIC)
				&& !InstrumentRanges.isPercussion(source.instrument());
			// The two answers that leave the music alone: rather than fold a note into the harp
			// window, hand it to a layer whose brackets already reach it. Their own block because
			// they share nothing with the two transposing modes below -- no base shift, no
			// bucketing by octave, and what comes out is a split layer rather than a copy of the
			// source at a different pitch.
			//
			// One block for both, because they differ in exactly one decision: which notes go onto
			// the split. Split takes the ones the window cannot hold and leaves the rest on their
			// own instrument; Convert takes the lot the moment one note needs it, so the part stays
			// one layer with one voice-set. Everything after the partition -- the shift for what no
			// bracket reaches, the naming, the counting -- is the same question either way.
			// See OctaveShifting.SPLIT_INTO_MELODIC and CONVERT_TO_MELODIC.
			if (melodic) {
				Split melodicSplit = Split.melodicKeeping(source.sounding());
				boolean wholeLayer = shifting == OctaveShifting.CONVERT_TO_MELODIC;
				// Convert moves a layer or it does not, and one stray decides. Asked up front
				// rather than per note, because the answer for the first note has to be the answer
				// for the last: a part half on its instrument and half on a split is the thing
				// this mode exists to not produce.
				boolean anyOutOfRange = sourceNotes.stream()
					.anyMatch(note -> inScope.test(note) && !note.isBuildable());
				List<NoteEvent> kept = new ArrayList<>();
				List<NoteEvent> relocated = new ArrayList<>();
				for (NoteEvent note : sourceNotes) {
					long quantizedStart =
						Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid);
					if (!anyOutOfRange || (!wholeLayer && (note.isBuildable() || !inScope.test(note)))) {
						kept.add(note.movedTo(quantizedStart, note.midiNote()));
						continue;
					}
					// Six octaves of brackets, so this only moves a note written outside F#1 to
					// F#7 at all -- and it moves that one by a whole octave, like everywhere else.
					// A note outside the scope rides along with its layer but is never retuned.
					int shift = melodicSplit.covers(note.midiNote()) || !inScope.test(note)
						? 0
						: octaveShiftIntoSplit(melodicSplit, note.midiNote());
					relocated.add(note.movedTo(quantizedStart, note.midiNote() + shift));
					if (shift != 0) {
						shiftedNotes++;
					}
				}
				melodicNotes += relocated.size();
				if (!relocated.isEmpty()) {
					melodicLayers++;
					// Every tier was on offer while the notes were placed; the layer keeps only the
					// ones they landed under.
					melodicSplit = Split.melodicFor(source.sounding(), relocated);
				}
				// Parallel to emitted: how many notes each layer was handed, so that what the
				// layer dropped as a duplicate cell can be counted the way the buckets below do.
				List<Layer> emitted = new ArrayList<>();
				List<Integer> fed = new ArrayList<>();
				if (relocated.isEmpty()) {
					// Nothing was out, so nothing is added -- and a source layer with no notes at
					// all lands here and stays a layer, because that is a part someone has yet to
					// write rather than a part that dissolved.
					emitted.add(source.withNotes(kept));
					fed.add(kept.size());
				} else if (kept.isEmpty()) {
					// Where Convert always lands, and where Split lands for a part written wholly
					// under or over the window -- a bass line, usually. Splitting that would leave
					// an empty layer beside a full one for one part, so either way the layer
					// becomes the melodic one in place and keeps its name.
					emitted.add(source.withNotes(relocated).withSplit(melodicSplit));
					fed.add(relocated.size());
				} else {
					emitted.add(source.withNotes(kept));
					fed.add(kept.size());
					emitted.add(source.withName(source.name() + MELODIC_SUFFIX)
						.withNotes(relocated).withSplit(melodicSplit));
					fed.add(relocated.size());
				}
				if (convertedLayers.size() + emitted.size() > MAX_LAYERS) {
					throw new IllegalStateException(
						"Conversion needs " + (convertedLayers.size() + emitted.size())
							+ " layers but the limit is " + MAX_LAYERS
							+ ". Split into melodic adds one layer per part that straddles the "
							+ "note-block range; Convert to melodic adds none at all, and is the "
							+ "mode to reach for at this layer count."
					);
				}
				if (layerIndex == activeLayerIndex) {
					convertedActiveLayer = convertedLayers.size();
				}
				for (int emittedIndex = 0; emittedIndex < emitted.size(); emittedIndex++) {
					Layer built = emitted.get(emittedIndex).withStacksMerged();
					// Two notes an octave apart can still land on one cell, where both were
					// outside the brackets and the same octave brought them in. They are merged into
					// one, and this is the one way the mode can take a note away.
					mergedIntoExisting += fed.get(emittedIndex) - built.notes().size();
					convertedLayers.add(built);
					sourceLayers.add(layerIndex);
				}
				continue;
			}
			// Where the layer sits before any note is looked at individually.
			int base = pitched && shifting == OctaveShifting.LAYER_THEN_NOTES
				? bestLayerOctaveShift(sourceNotes.stream().filter(inScope).toList())
				: 0;
			for (NoteEvent note : sourceNotes) {
				if (!inScope.test(note)) {
					// Left where it is: the bucket whose total shift is nought, the one that keeps
					// the layer's own name when the layer moved nowhere.
					long quantizedStart =
						Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid);
					notesByShift.computeIfAbsent(splitTransposed ? -base : 0,
						ignored -> new ArrayList<>()).add(note.movedTo(quantizedStart, note.midiNote()));
					continue;
				}
				// Bucketed by what the note needed *after* the layer moved, so everything the base
				// already fixed shares one bucket and one layer. Named by the total, because what a
				// name has to answer is how far these notes are from where they were written.
				int residual = pitched ? octaveShiftIntoNoteBlockRange(note.midiNote() + base)
					: fitsToSplit && !source.split().covers(note.midiNote())
						? octaveShiftIntoSplit(source.split(), note.midiNote())
					: 0;
				int shift = base + residual;
				long quantizedStart = Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid);
				NoteEvent converted = note.movedTo(quantizedStart, note.midiNote() + shift);
				notesByShift.computeIfAbsent(splitTransposed ? residual : 0,
					ignored -> new ArrayList<>()).add(converted);
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
				int shift = base + entry.getKey();
				String convertedName = distinct.size() == 1 && shift == 0
					? source.name()
					: source.name() + octaveShiftSuffix(shift);
				// Copied off the source rather than rebuilt, so a split layer's brackets survive
				// the conversion along with everything else about it.
				Layer built = source.withName(convertedName).withNotes(entry.getValue())
					.withStacksMerged();
				// What the layer merges. A stack only ever plays once, so two source notes an octave
				// apart that land on the same pitch are merged into one -- the same
				// dedupe the split reports as a dropped duplicate layer, arriving a note at a time
				// because there is no second layer for it to arrive as. Counted rather than left
				// silent: it is the one way this can take notes away, and it should say so.
				mergedIntoExisting += entry.getValue().size() - built.notes().size();
				convertedLayers.add(built);
				sourceLayers.add(layerIndex);
			}
		}

		// The tempo comes from where the notes ended up, not from the grid they were quantized to.
		// Those are different once quantizing has moved things: a song whose notes all land two
		// grid steps apart needs a repeater tick every two steps, and forcing one per step slows it
		// by half for nothing. That is exactly what converting an already-valid song did -- notes on
		// a 1/8, grid set to 1/16, tempo doubled, song halved. Asking the notes cannot do that,
		// because after quantizing their spacing is always a whole number of grid steps.
		ComposerProject shaped = new ComposerProject(name, ppq, tempoMicrosPerQuarter, convertedLayers,
			convertedActiveLayer, nextNoteId, endTick, speedQuarters, speedEighths, markers);
		NoteSpacing spacing = shaped.noteSpacing();
		int convertedTempo = snapTempo && spacing.gridTicks() > 0L
			? shaped.alignedTempoFor(
				(int)Math.min(Integer.MAX_VALUE, spacing.gridTicks()), gameTicks)
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
			* speedFactor();
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
			speedQuarters,
			speedEighths,
			// Left on the ticks they were written on, because the notes are: quantizing moves a note
			// within the tick space rather than rescaling it, so a marker still names the same bar.
			markers
		);
		return new MinecraftConversion(
			converted,
			shiftedNotes,
			Math.max(0, convertedLayers.size() - layers.size()),
			convertedTempo != tempoMicrosPerQuarter,
			convertedTempo / (double)tempoMicrosPerQuarter,
			mergedRepeats,
			duplicateLayers,
			duplicateLayerNotes,
			mergedIntoExisting,
			melodicNotes,
			melodicLayers,
			List.copyOf(sourceLayers)
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
			: repeatMergeTicks * SongAnalysis.redstoneTickSpan(this);
		return layers.stream()
			.flatMap(layer -> mergeRepeats(layer.notes(), window).stream())
			.map(NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
	}

	/**
	 * Adds one note, or hands back the same composition if that cell is already taken.
	 *
	 * <p>A layer can hold a stack, but clicking never makes one: a stack is a copy that landed on a
	 * note, and a click on a note is not asking for a second. Refusing here is also what makes
	 * clicking an occupied cell a no-op rather than an undo step for nothing. On a sound effect
	 * layer the cell is the tick alone, so any row at that tick counts as taken.</p>
	 */
	public ComposerProject addNote(int layerIndex, int midiNote, long startTick, long durationTicks) {
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Layer layer = layers.get(target);
		int clampedNote = Math.max(0, Math.min(127, midiNote));
		long clampedTick = Math.max(0L, startTick);
		boolean pitched = layer.pitchedCells();
		for (NoteEvent existing : layer.notes()) {
			if (existing.startTick() == clampedTick
					&& (!pitched || existing.midiNote() == clampedNote)) {
				return this;
			}
			if (existing.startTick() > clampedTick) {
				// Sorted by tick, so nothing further along can be in this cell.
				break;
			}
		}
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
					copied.velocity(), copied.strikeShift());
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

	/**
	 * Lengthens or shortens a set of notes by one amount, each stopping at a length of one tick.
	 *
	 * <p>The resize drag's edit. Unlike a move there is no shape to keep against an edge: a note
	 * dragged shorter than nothing stops at its own shortest, and the others go on changing, since
	 * every note's start stays exactly where it was either way.</p>
	 */
	public ComposerProject withNotesResized(Set<Long> ids, long durationDelta) {
		if (ids == null || ids.isEmpty() || durationDelta == 0L) {
			return this;
		}
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> ids.contains(note.id())
					? note.withDuration(Math.max(1L, note.durationTicks() + durationDelta))
					: note)
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/**
	 * Moves a set of notes, keeping its shape when it runs into an edge.
	 *
	 * <p>The limits are applied to the move, once, rather than to each note as it arrives at one.
	 * Clamping note by note squashes a phrase against the start of the song: the notes that have
	 * reached tick zero stop while the ones behind them keep coming, and every interval in the
	 * phrase is quietly lost -- a chord dragged into the wall arrives as a single note. A drag is
	 * one gesture over one shape, so the shape stops when its leading edge does.</p>
	 *
	 * <p>The same for pitch, against 0 and 127, where losing the intervals would be worse still:
	 * that is not a phrase arriving early, it is a different chord.</p>
	 */
	public ComposerProject moveNotes(Set<Long> ids, long tickDelta, int pitchDelta) {
		if (ids == null || ids.isEmpty() || tickDelta == 0L && pitchDelta == 0) {
			return this;
		}
		Set<Long> selected = new LinkedHashSet<>(ids);
		long earliest = Long.MAX_VALUE;
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (Layer layer : layers) {
			for (NoteEvent note : layer.notes()) {
				if (selected.contains(note.id())) {
					earliest = Math.min(earliest, note.startTick());
					lowest = Math.min(lowest, note.midiNote());
					highest = Math.max(highest, note.midiNote());
				}
			}
		}
		if (earliest == Long.MAX_VALUE) {
			return this;
		}
		long tickShift = Math.max(tickDelta, -earliest);
		int pitchShift = Math.max(Math.min(pitchDelta, 127 - highest), -lowest);
		if (tickShift == 0L && pitchShift == 0) {
			return this;
		}
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> selected.contains(note.id())
					? note.movedTo(note.startTick() + tickShift, note.midiNote() + pitchShift)
					: note)
				.toList()))
			.toList();
		return with(updated, activeLayerIndex, nextNoteId);
	}

	/**
	 * Puts copies of {@code incoming} into the list at {@code at}, with notes under fresh ids.
	 *
	 * <p>All or nothing against the layer cap, the same as duplicating: half a paste is a song with
	 * some of what you asked for and no way to tell which half.</p>
	 *
	 * <p>Names are left exactly as they came. Pasting a layer called Bass gives a second layer
	 * called Bass, which reads oddly for a copy and is exactly right for a cut being moved -- and
	 * the clipboard cannot tell those apart at the moment it lands.</p>
	 */
	public ComposerProject withLayersInserted(int at, List<Layer> incoming) {
		if (incoming == null || incoming.isEmpty()
				|| layers.size() + incoming.size() > MAX_LAYERS) {
			return this;
		}
		int landing = Math.max(0, Math.min(layers.size(), at));
		long nextId = nextNoteId;
		List<Layer> arriving = new ArrayList<>(incoming.size());
		for (Layer layer : incoming) {
			List<NoteEvent> copied = new ArrayList<>(layer.notes().size());
			for (NoteEvent note : layer.notes()) {
				copied.add(note.withId(nextId++));
			}
			arriving.add(layer.withNotes(copied));
		}
		List<Layer> updated = new ArrayList<>(layers);
		updated.addAll(landing, arriving);
		return with(updated, landing, nextId);
	}

	/**
	 * Copies the given notes {@code tickDelta} later, each one staying on the layer it is already on.
	 *
	 * <p>The difference from {@link #pasteNotes}: a paste arrives from a clipboard and is aimed at a
	 * layer, gathering the copy there and splitting only what that layer's instrument cannot hold. A
	 * duplicate is not aimed anywhere -- it is the same passage again, so a four-part phrase comes
	 * out as four parts and not as one layer holding all of them.</p>
	 *
	 * <p>Fresh ids, for the reason a duplicated layer's notes get them: the two copies are selected
	 * and moved by id, and shared ids would make the second a view of the first.</p>
	 */
	public PasteResult duplicateNotes(Set<Long> ids, long tickDelta) {
		if (ids == null || ids.isEmpty() || tickDelta == 0L) {
			return new PasteResult(this, Set.of(), 0);
		}
		long id = nextNoteId;
		List<Layer> updated = new ArrayList<>(layers.size());
		Set<Long> addedIds = new LinkedHashSet<>();
		for (Layer layer : layers) {
			List<NoteEvent> notes = null;
			for (NoteEvent note : layer.notes()) {
				if (!ids.contains(note.id())) {
					continue;
				}
				if (notes == null) {
					notes = new ArrayList<>(layer.notes());
				}
				NoteEvent copy = note.withId(id++)
					.movedTo(Math.max(0L, note.startTick() + tickDelta), note.midiNote());
				notes.add(copy);
				addedIds.add(copy.id());
			}
			updated.add(notes == null ? layer : layer.withNotes(notes));
		}
		return new PasteResult(with(updated, activeLayerIndex, id), Set.copyOf(addedIds), 0);
	}

	/**
	 * Copies a layer, putting the copy directly after the one it came from.
	 *
	 * <p>Next to its source rather than at the end of the list, because a duplicate is a variation
	 * on the layer above it -- the same part on a second instrument, or a line about to be altered
	 * against the one it started as -- and reading the two together is the whole point of making
	 * one. The notes are copied with fresh ids so the two layers move independently.</p>
	 */
	public ComposerProject duplicateLayer(int index) {
		if (index < 0 || index >= layers.size() || layers.size() >= MAX_LAYERS) {
			return this;
		}
		Layer source = layers.get(index);
		long nextId = nextNoteId;
		List<NoteEvent> copied = new ArrayList<>(source.notes().size());
		for (NoteEvent note : source.notes()) {
			copied.add(note.withId(nextId++));
		}
		List<Layer> updated = new ArrayList<>(layers);
		Set<String> taken = new java.util.HashSet<>();
		layers.forEach(layer -> taken.add(layer.name()));
		updated.add(index + 1, source.withName(copyName(source.name(), taken)).withNotes(copied));
		return with(updated, index + 1, nextId);
	}

	/** A name already ending in " copy" or " copy (N)", with that ending taken off. */
	private static final java.util.regex.Pattern COPY_SUFFIX =
		java.util.regex.Pattern.compile("^(.*?) copy(?: \\((\\d+)\\))?$");

	/**
	 * The name a copy of a layer gets: "A copy", then "A copy (1)", "A copy (2)" as those are taken.
	 *
	 * <p>Counted from the original's own name, so copying a copy is another copy of the original
	 * rather than "A copy copy" -- a name that grew a word every time was the thing to fix.</p>
	 *
	 * @param taken every name already in use, which the copy must not repeat
	 */
	static String copyName(String source, Set<String> taken) {
		java.util.regex.Matcher suffix = COPY_SUFFIX.matcher(source);
		String base = (suffix.matches() && !suffix.group(1).isBlank() ? suffix.group(1) : source)
			+ " copy";
		if (!taken.contains(base)) {
			return base;
		}
		for (int number = 1; ; number++) {
			String candidate = base + " (" + number + ")";
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
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
		// The markers come forward too. They name positions in the music, and music that has moved
		// leaves every one of them pointing a bar of silence away from what it was written on.
		List<Marker> pulled = markers.stream()
			.map(marker -> marker.movedTo(Math.max(0L, marker.tick() - earliest)))
			.toList();
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex,
			nextNoteId, Math.max(1L, endTick - earliest), speedQuarters, speedEighths, pulled);
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
			nextNoteId, Math.max(0L, value), speedQuarters, speedEighths, markers);
	}

	/** Kept for callers that speak in quarters; a quarter is two eighths. */
	public ComposerProject withSpeedQuarters(int value) {
		return withSpeedEighths(value * 2);
	}

	public ComposerProject withSpeedEighths(int value) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex,
			nextNoteId, endTick, 0, value, markers);
	}

	/**
	 * The build projection of one layer: exactly the steps the in-world builder places.
	 *
	 * <p>This, not text, is the build path. Track text is a human-facing rendering of the same
	 * steps, so the two cannot disagree about what a composition builds as.</p>
	 */
	public List<Step> toSteps(Layer layer) {
		// A sound effect has no range to fall outside of and no pitch to carry, so every note builds
		// and each one is written as pitch 0 -- a number the sequence text can hold and read back,
		// standing for the one sound the block makes.
		//
		// A split layer never arrives here whole: buildVoices expands it first, so what this sees
		// is always a one-instrument layer whose notes already sit in the harp window. Handed a
		// split layer directly this would quietly keep only the harp-window slice, which is why
		// every build path goes through the expansion.
		boolean pitched = layer.pitched();
		List<NoteEvent> buildable = pitched
			? layer.notes().stream().filter(NoteEvent::isBuildable).toList()
			: layer.notes();
		List<Step> steps = new ArrayList<>();
		long previousTick = 0L;
		for (int index = 0; index < buildable.size();) {
			long eventTick = buildable.get(index).startTick();
			NoteSequence.addDelaySteps(steps, buildDelayTicks(eventTick - previousTick));
			while (index < buildable.size() && buildable.get(index).startTick() == eventTick) {
				steps.add(Step.note(pitched ? buildable.get(index).noteBlockPitch() : 0));
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
		return (int)Math.max(0L, Math.round(physical / speedFactor()));
	}

	/**
	 * The same gap in game ticks, which is twice as fine as a repeater can place on its own.
	 *
	 * <p>Two lanes started a game tick apart reach between the repeater ticks, so a build made of
	 * them is quantised at 50 ms rather than 100 -- and the rounding has to happen at that
	 * resolution to be worth anything. Rounding to repeater ticks first and doubling afterwards
	 * lands on precisely the same moments as before and buys nothing at all, which is the whole
	 * distinction between this and {@link #buildDelayTicks}.</p>
	 *
	 * <p>Rounds once, from the real duration, for the reason the method beside it does: rounding
	 * twice destroys anything shorter than the coarser unit.</p>
	 */
	public int buildDelayGameTicks(long composerTicks) {
		double physical = composerTicksToMinecraftTicks(
			Math.max(0L, composerTicks), ppq, tempoMicrosPerQuarter
		);
		return (int)Math.max(0L, Math.round(physical * 2.0 / speedFactor()));
	}

	/**
	 * The layers a build would place, with anything already heard removed.
	 *
	 * <p>The deduplication {@code toSequenceTracks} does, reachable on its own so that a build
	 * reading the composition at a different resolution drops exactly the same notes. Doing it
	 * afterwards instead would not be the same: gaps are rounded one at a time, and a gap that
	 * swallows a wholly deduplicated event rounds differently from the two it replaces.</p>
	 */
	public List<Layer> buildLayers(boolean dedupeIdentical) {
		return buildLayers(dedupeIdentical, SongAnalysis.MAX_SIMULTANEOUS_NOTES);
	}

	/** @param thinTarget see {@link #toSequenceTracks(Set, boolean, int)} */
	public List<Layer> buildLayers(boolean dedupeIdentical, int thinTarget) {
		return buildLayers(dedupeIdentical, ChordSkips.Rules.at(thinTarget));
	}

	/** @param thinning what the chord limit may leave out, and above how many note blocks */
	public List<Layer> buildLayers(boolean dedupeIdentical, ChordSkips.Rules thinning) {
		List<Layer> chosen = new ArrayList<>();
		Set<NoteSound> heard = dedupeIdentical ? new java.util.HashSet<>() : null;
		// Sustains become strikes first, for the reason toSequenceTracks gives.
		double finest = anyBuildLayerSustains() ? finestSustainStep() : 0.0;
		ChordSkips skips = ChordSkips.of(this, dedupeIdentical, thinning, finest);
		for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
			Layer layer = layers.get(layerIndex);
			if (!layer.inBuild()) {
				continue;
			}
			// Split layers arrive already expanded into their voices, so every layer this returns
			// is a plain one-instrument layer and downstream readers need no new case.
			// A counted voice skips the deduplication both ways and arrives as one layer per copy;
			// see toSequenceTracks.
			List<Layer> voices = placedForBuild(layer, finest).buildVoices();
			for (int voiceIndex = 0; voiceIndex < voices.size(); voiceIndex++) {
				Layer voice = voices.get(voiceIndex);
				Layer projected = heard == null || voice.copies() > 1
					? voice : withoutAlreadyHeard(voice, heard);
				chosen.addAll(skips.copiesOf(layerIndex, voiceIndex, projected));
			}
		}
		return List.copyOf(chosen);
	}

	/**
	 * How many composer ticks a sustain length is in this song.
	 *
	 * @param finest what Finest is in this song, from {@link #finestSustainStep}
	 */
	public double sustainTicks(SustainLength length, double finest) {
		double repeater = SongAnalysis.redstoneTickSpan(this);
		return switch (length) {
			case GAME_TICK -> repeater / 2.0;
			case REPEATER_TICK -> repeater;
			case TWO_REPEATER_TICKS -> repeater * 2.0;
			case FOUR_REPEATER_TICKS -> repeater * 4.0;
			case THIRTY_SECOND -> ppq / 8.0;
			case SIXTEENTH -> ppq / 4.0;
			case EIGHTH -> ppq / 2.0;
			case QUARTER -> ppq;
			case HALF -> ppq * 2.0;
			case BAR -> ppq * 4.0;
			case FINEST -> finest;
		};
	}

	/**
	 * These notes with their sustain strikes slid along by {@code delta} composer ticks, each note's
	 * pattern moving whole. What dragging a strike tick does.
	 *
	 * <p>Only notes on a sustaining layer move: anywhere else there are no strikes to see, and a
	 * shift stored there would be a surprise waiting for the day the layer starts sustaining. Each
	 * shift is kept within one step of its layer, so dragging a pattern a whole step round lands it
	 * back on the song's grid and it saves as never having moved.</p>
	 *
	 * @param finest what Finest is in this song, from {@link #finestSustainStep}
	 */
	public ComposerProject withStrikesShifted(Set<Long> ids, long delta, double finest) {
		if (ids == null || ids.isEmpty() || delta == 0L) {
			return this;
		}
		List<Layer> updated = new ArrayList<>(layers.size());
		boolean changed = false;
		for (Layer layer : layers) {
			if (!layer.sustains() || layer.notes().stream().noneMatch(note -> ids.contains(note.id()))) {
				updated.add(layer);
				continue;
			}
			double step = Math.max(1.0, sustainTicks(layer.sustainOrDefault().every(), finest));
			updated.add(layer.withNotes(layer.notes().stream()
				.map(note -> ids.contains(note.id())
					? note.withStrikeShift(phaseWithin(note.strikeShiftTicks() + delta, step))
					: note)
				.toList()));
			changed = true;
		}
		return changed ? with(updated, activeLayerIndex, nextNoteId) : this;
	}

	/** A shift brought within one step, [0, step), where every whole step round is the same. */
	private static long phaseWithin(long shift, double step) {
		double phase = shift - Math.floor(shift / step) * step;
		long rounded = Math.round(phase);
		return rounded >= step - 0.5 ? 0L : rounded;
	}

	/** Where a layer's notes strike again in this song; see {@link Strikes}. */
	public Strikes sustainStrikes(Layer layer, double finest) {
		Sustain settings = layer.sustainOrDefault();
		return new Strikes(sustainOrigin(), sustainTicks(settings.every(), finest),
			sustainTicks(settings.after(), finest), settings.aligned() ? sustainGrid() : 0.0);
	}

	/**
	 * The grid a strike is aligned to: what the song builds on with its sustains left out. A
	 * repeater tick where that is one lane, a game tick where it needs two.
	 *
	 * <p>Asked of the song as written, never of the song with its strikes in, or the strikes would
	 * decide the grid they are being put on -- and a strike put between repeater ticks would talk the
	 * whole song into a second lane.</p>
	 *
	 * <p>Remembered for the last few songs asked about, by identity: a song is immutable, the grid
	 * costs a pass over every note, and every sustaining layer asks for it on every expansion.</p>
	 */
	public double sustainGrid() {
		synchronized (SUSTAIN_GRIDS) {
			for (Map.Entry<ComposerProject, Double> entry : SUSTAIN_GRIDS) {
				if (entry.getKey() == this) {
					return entry.getValue();
				}
			}
		}
		double repeater = SongAnalysis.redstoneTickSpan(this);
		double grid = SongAnalysis.ofWritten(this, true).lanesNeeded() == 2 ? repeater / 2.0 : repeater;
		synchronized (SUSTAIN_GRIDS) {
			SUSTAIN_GRIDS.add(Map.entry(this, grid));
			if (SUSTAIN_GRIDS.size() > 4) {
				SUSTAIN_GRIDS.removeFirst();
			}
		}
		return grid;
	}

	/**
	 * Looked up by identity, in a list: two equal songs have the same grid, but hashing a whole song
	 * to find out is a pass over every note, which is the cost this is here to save.
	 */
	private static final List<Map.Entry<ComposerProject, Double>> SUSTAIN_GRIDS = new ArrayList<>();

	/**
	 * What Finest means in this song: the finest step it is already written at.
	 *
	 * <p>A song ready to paste -- nothing off the redstone grid, no gap under a game tick -- is
	 * already written in redstone ticks, so its finest step is the one its build uses: a repeater
	 * tick when it builds on one lane, a game tick when it needs two. Interleaved paste makes that
	 * choice by itself from the same question, whether anything half-ticks, so this follows the
	 * build without asking which layout was used last.</p>
	 *
	 * <p>Any other song has not been written for redstone yet, and its finest step is the finest
	 * note value its notes already stand on; see {@link #noteGridTicks}.</p>
	 *
	 * @param stats this song's analysis; only its grid questions are asked
	 */
	public double finestSustainStep(SongAnalysis stats) {
		if (stats.offGridNotes().isEmpty() && stats.crowdedNotes().isEmpty()) {
			double repeater = SongAnalysis.redstoneTickSpan(this);
			return stats.lanesNeeded() == 2 ? repeater / 2.0 : repeater;
		}
		return noteGridTicks();
	}

	/** What Finest is in this song, read off the song as written. */
	public double finestSustainStep() {
		return finestSustainStep(SongAnalysis.ofWritten(this, true));
	}

	/** Whether any layer the build places turns its long notes into strikes. */
	public boolean anyBuildLayerSustains() {
		return layers.stream().anyMatch(layer -> layer.inBuild() && layer.sustains());
	}

	/** How many strikes this song's sustains add to the notes its build places. */
	public int sustainStrikeCount() {
		if (!anyBuildLayerSustains()) {
			return 0;
		}
		double finest = finestSustainStep();
		int added = 0;
		for (Layer layer : layers) {
			if (layer.inBuild() && layer.sustains()) {
				added += withSustainsExpanded(layer, finest).notes().size() - layer.notes().size();
			}
		}
		return added;
	}

	/**
	 * The coarsest note value the song's notes start on: the granularity it is written at.
	 *
	 * <p>Tried from the quarter down to the 1/128, with the triplet values between, and measured
	 * from tick zero where the bars start. A grid holds the song when 98 in every hundred distinct
	 * starts stand on it, so a few stray notes in a quantized song do not drag the answer down to the
	 * finest value there is. A performance nothing holds, never quantized, gets a thirty-second
	 * note.</p>
	 */
	public long noteGridTicks() {
		java.util.TreeSet<Long> starts = new java.util.TreeSet<>();
		for (Layer layer : layers) {
			for (NoteEvent note : layer.notes()) {
				starts.add(note.startTick());
			}
		}
		long fallback = Math.max(1, ppq / 8);
		if (starts.isEmpty()) {
			return fallback;
		}
		for (int division : new int[] {1, 2, 3, 4, 6, 8, 12, 16, 24, 32}) {
			if (ppq % division != 0) {
				continue;
			}
			long grid = ppq / division;
			long on = starts.stream().filter(tick -> tick % grid == 0L).count();
			if (on * 100L >= starts.size() * 98L) {
				return grid;
			}
		}
		return fallback;
	}

	/**
	 * A sustaining layer with its strikes written out as notes, for whatever plays or measures it.
	 *
	 * <p>Each strike is a one-tick note on its grid line, carrying the id, pitch and velocity of the
	 * note it belongs to. A strike that lands where the layer already has that note is left out --
	 * the note that was written wins -- and so is a strike on a cell another sustain already struck.
	 * The layer that comes back no longer sustains, so expanding it again changes nothing, and a
	 * layer that does not sustain comes back as it is.</p>
	 */
	public Layer withSustainsExpanded(Layer layer, double finest) {
		if (!layer.sustains()) {
			return layer;
		}
		Strikes strikes = sustainStrikes(layer, finest);
		boolean pitched = layer.pitched();
		Set<Long> taken = new java.util.HashSet<>();
		for (NoteEvent note : layer.notes()) {
			taken.add(sustainCell(note.startTick(), pitched ? note.midiNote() : 0));
		}
		List<NoteEvent> expanded = new ArrayList<>(layer.notes());
		for (NoteEvent note : layer.notes()) {
			strikes.forEach(note, 0L, Long.MAX_VALUE, tick -> {
				if (taken.add(sustainCell(tick, pitched ? note.midiNote() : 0))) {
					expanded.add(new NoteEvent(note.id(), note.midiNote(), tick, 1L, note.velocity()));
				}
			});
		}
		return layer.withNotes(expanded).withSustain(null);
	}

	private static long sustainCell(long tick, int midiNote) {
		return tick * 128L + midiNote;
	}

	/** The song's first note, which the sustain grid is counted from. */
	private long sustainOrigin() {
		long first = Long.MAX_VALUE;
		for (Layer layer : layers) {
			if (!layer.notes().isEmpty()) {
				first = Math.min(first, layer.notes().get(0).startTick());
			}
		}
		return first == Long.MAX_VALUE ? 0L : first;
	}

	private static List<Layer> normalizeLayers(List<Layer> source) {
		List<Layer> normalized = new ArrayList<>();
		if (source != null) {
			for (Layer layer : source) {
				if (layer != null && normalized.size() < MAX_LAYERS) {
					normalized.add(new Layer(layer.name(), layer.instrument(), layer.muted(),
						layer.buildEnabled(), layer.visible(), layer.notes(), layer.split(), layer.mix(),
						layer.resting(), layer.sustain()));
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
		return alignedTempo(gridTicks, false);
	}

	/**
	 * The same, against whichever tick the build will actually be able to place.
	 *
	 * @param gameTicks aim at game ticks rather than repeater ticks, which a build of two lanes
	 *     offset by half a tick can place. Halving the unit halves how far the tempo has to move to
	 *     reach it: a grid step of 1.25 repeater ticks has to become 1 or 2 -- a fifth of the song's
	 *     speed either way -- where in game ticks it is 2.5 and becomes 2 or 3, a tenth.
	 */
	private int alignedTempo(int gridTicks, boolean gameTicks) {
		double speedFactor = speedFactor();
		double perBuildTick = gameTicks ? 2.0 : 1.0;
		double gridBuildTicks = gridTicks * tempoMicrosPerQuarter
			/ (double)ppq / 100_000.0 / speedFactor * perBuildTick;
		int nearestBuildTicks = Math.max(1, (int)Math.round(gridBuildTicks));
		// Rounded up, like the nudge in withQuantizedToRepeaters and for the same reason. The tempo
		// is an integer, so the span it produces lands either side of the grid; one microsecond low
		// makes the span a hair wider than the grid, and every gap that should be exactly one
		// repeater tick comes out at 0.999 of one, which reads as too frequent. Up lands the span
		// just inside the grid, where the error is harmless. Found by snapping a song that had just
		// been quantized to repeater ticks and watching 369 gaps go red.
		return Math.max(1, (int)Math.ceil(
			nearestBuildTicks / perBuildTick * 100_000.0 * ppq * speedFactor / gridTicks
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
				kept.set(index, anchor.withDuration(
					Math.max(anchor.durationTicks(), absorbedEnd - anchor.startTick())));
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

	/**
	 * The multiple of twelve that leaves the fewest of a layer's notes outside the note-block range.
	 *
	 * <p>Its own count, not the melody-weighted one {@link #bestTransposeIntoRange} uses. That weight
	 * was measured for moving a whole song, where the top voice at each instant is the melody often
	 * enough to steer by; the top voice of one accompaniment layer is not the melody, it is merely
	 * that layer's highest note. A weight measured for one question is not evidence about a different
	 * one, so this minimises the thing the mode is named after and nothing else.</p>
	 *
	 * <p>Ties go to the smaller move, which is what keeps a layer already wholly in range where it
	 * is: it scores nought at nought, and nothing can beat that.</p>
	 */
	private static int bestLayerOctaveShift(List<NoteEvent> notes) {
		if (notes.isEmpty()) {
			return 0;
		}
		int lowest = notes.stream().mapToInt(NoteEvent::midiNote).min().orElse(0);
		int highest = notes.stream().mapToInt(NoteEvent::midiNote).max().orElse(0);
		int best = 0;
		int fewest = Integer.MAX_VALUE;
		for (int shift = -120; shift <= 120; shift += 12) {
			// Only shifts that keep every note a MIDI note. NoteEvent clamps to 0..127, so a shift
			// that ran off either end would not be rejected, it would silently retune the notes it
			// pushed over the edge.
			if (lowest + shift < 0 || highest + shift > 127) {
				continue;
			}
			int outside = 0;
			for (NoteEvent note : notes) {
				int moved = note.midiNote() + shift;
				if (moved < NOTE_BLOCK_BASE_MIDI_NOTE || moved > NOTE_BLOCK_MAX_MIDI_NOTE) {
					outside++;
				}
			}
			if (outside < fewest || outside == fewest && Math.abs(shift) < Math.abs(best)) {
				fewest = outside;
				best = shift;
			}
		}
		return best;
	}

	/**
	 * The nearest octave shift that lands a note inside a split's own brackets.
	 *
	 * <p>{@link #octaveShiftIntoNoteBlockRange} aims at the harp window, which is the wrong target
	 * for a split layer: its whole purpose is that its notes are true pitch, reaching wherever its
	 * voices reach -- the melodic default is F#1 to F#7, five octaves rather than two. So a split
	 * layer was excluded from range fitting entirely, and that was right about the window and wrong
	 * about the consequence. A note outside <em>every</em> bracket is out of range by the layer's
	 * own definition, {@link Layer#outOfRange} says so, the roll draws it as such -- and nothing
	 * moved it, so "Fit into range" left notes visibly out of range and Convert built a layer that
	 * could not sound them.</p>
	 *
	 * <p>Nought where nothing helps, which leaves the note where it is: a bracket set can have
	 * holes, and a note in one is no better off an octave away.</p>
	 */
	private static int octaveShiftIntoSplit(Split split, int midiNote) {
		int bestShift = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int shift = -120; shift <= 120; shift += 12) {
			int shifted = midiNote + shift;
			if (shifted >= 0 && shifted <= 127 && split.covers(shifted)
					&& Math.abs(shift) < bestDistance) {
				bestShift = shift;
				bestDistance = Math.abs(shift);
			}
		}
		return bestShift;
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

	/**
	 * What a melodic companion layer is called: the part's own name and what was done to it.
	 *
	 * <p>Not an octave, unlike {@link #octaveShiftSuffix}, because the notes on it did not take
	 * one -- they are at the pitch they were written at, on a layer that can reach them.</p>
	 */
	private static final String MELODIC_SUFFIX = " (melodic)";

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
			String instrument, String sourceLayer, long strikeShift) {
		/** A copied note whose strikes sit on the song's grid. */
		public ClipboardNote(long tickOffset, int midiNote, long durationTicks, int velocity,
				String instrument, String sourceLayer) {
			this(tickOffset, midiNote, durationTicks, velocity, instrument, sourceLayer, 0L);
		}

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
