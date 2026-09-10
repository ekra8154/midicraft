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
		List<NoteEvent> notes,
		/** How a split layer voices its notes, or null for the ordinary one-instrument layer. */
		Split split
	) {
		public Layer {
			name = name == null || name.isBlank() ? "Layer" : name.trim();
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			// A split layer always keeps the pitch in the cell, whatever its vestigial instrument
			// says: the row decides which voices sound, so two rows are never the same note.
			notes = notes == null ? List.of() : oneNotePerCell(notes, pitched(instrument) || split != null);
		}

		/** Everything but the split, for the callers written before there was one. */
		public Layer(String name, String instrument, boolean muted, boolean buildEnabled,
				boolean visible, List<NoteEvent> notes) {
			this(name, instrument, muted, buildEnabled, visible, notes, null);
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
			return split != null || pitched(instrument);
		}

		private static boolean pitched(String instrument) {
			return !instrument.startsWith(SOUND_EFFECT_PREFIX);
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
		 */
		public List<Layer> buildVoices() {
			if (split == null) {
				return List.of(this);
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
						voice.instrument(), muted, buildEnabled, visible, covered));
				}
			}
			return List.copyOf(voices);
		}

		/**
		 * A layer's notes in order, with at most one on any pitch at any tick.
		 *
		 * <p>A layer has one instrument, so two notes on the same pitch at the same tick are the same
		 * sound twice. The build already collapsed them and preview already played them once; keeping
		 * them in the document only meant the roll had a cell you could put notes into forever, with
		 * nothing to show that you had. Wanting a doubled note is a real thing to want -- it is how
		 * you make one louder -- and it is two layers, which says so.</p>
		 *
		 * <p>Enforced here, in the constructor, rather than at the places that add notes. Every edit
		 * in this file goes through {@code with}, and a rule about what a layer <em>is</em> cannot be
		 * left to each caller to remember: quantizing two neighbours onto one tick, transposing two
		 * pitches onto one, pasting, merging layers and importing a MIDI whose track doubles a note
		 * all arrive at the same cell by different roads.</p>
		 *
		 * <p>The survivor is the lowest id, which is the one that was there first. Deliberately not
		 * the newest: a phrase dragged across an existing note would otherwise lose one of its own
		 * notes to every note it passed, and a drag is rebuilt from its starting point each frame, so
		 * only where it comes to rest can cost anything.</p>
		 *
		 * <p>On a sound effect layer the cell is the tick alone. A door cannot be tuned, so the row a
		 * hit is drawn on says nothing about how it sounds, and two hits on one tick would be one door
		 * opening twice in the same instant -- which is one door opening. The row is still yours to
		 * use: drawing a part across the roll to keep it readable costs nothing, it just cannot mean
		 * two of anything. Note that switching a layer onto an effect collapses whatever chords it
		 * already had; that is an edit like any other and undo puts them back.</p>
		 */
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
			return new Layer(name, instrument, muted, buildEnabled, visible, value, split);
		}

		public Layer withName(String value) {
			return new Layer(value, instrument, muted, buildEnabled, visible, notes, split);
		}

		public Layer withInstrument(String value) {
			return new Layer(name, value, muted, buildEnabled, visible, notes, split);
		}

		public Layer withMuted(boolean value) {
			return new Layer(name, instrument, value, buildEnabled, visible, notes, split);
		}

		public Layer withBuildEnabled(boolean value) {
			return new Layer(name, instrument, muted, value, visible, notes, split);
		}

		public Layer withVisible(boolean value) {
			return new Layer(name, instrument, muted, buildEnabled, value, notes, split);
		}

		public Layer withSplit(Split value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, notes, value);
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
		 */
		public record Voice(String instrument, int lo, int hi) {
			public Voice {
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
		int melodicLayers
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
		List<SequenceTrack> result = new ArrayList<>();
		Set<NoteSound> heard = dedupeIdentical ? new java.util.HashSet<>() : null;
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
			for (Layer voice : layer.buildVoices()) {
				Layer projected = heard == null ? voice : withoutAlreadyHeard(voice, heard);
				result.add(new SequenceTrack(voice.name(), toText(projected), voice.instrument(), 0, true));
			}
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
	 * Copies every given layer, each copy directly after the layer it came from.
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
		List<Layer> updated = new ArrayList<>(layers.size() + sources.size());
		for (int index = 0; index < layers.size(); index++) {
			Layer source = layers.get(index);
			updated.add(source);
			if (!sources.contains(index)) {
				continue;
			}
			List<NoteEvent> copied = new ArrayList<>(source.notes().size());
			for (NoteEvent note : source.notes()) {
				copied.add(new NoteEvent(nextId++, note.midiNote(), note.startTick(),
					note.durationTicks(), note.velocity()));
			}
			updated.add(source.withName(source.name() + " copy").withNotes(copied));
		}
		return with(updated, sources.getFirst() + 1, nextId);
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
		// A split layer's notes are not held to the harp window at all -- true pitch is the whole
		// point of one -- so folding them into it would wreck exactly the notes the layer exists
		// to keep. They are left alone; a note outside every bracket is the handles' business.
		List<Layer> updated = layers.stream()
			.map(layer -> layer.split() != null ? layer : layer.withNotes(layer.notes().stream()
				.map(note -> note.isBuildable() || !inScope(note, scope)
					? note
					: note.movedTo(note.startTick(),
						note.midiNote() + octaveShiftIntoNoteBlockRange(note.midiNote())))
				.toList()))
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
		int grid = Math.max(1, quantizeTicks);
		double repeatWindow = repeatMergeTicks <= 0
			? 0.0
			: repeatMergeTicks * ppq * 100_000.0 / tempoMicrosPerQuarter;
		int mergedRepeats = 0;
		int duplicateLayers = 0;
		int duplicateLayerNotes = 0;
		int mergedIntoExisting = 0;
		int melodicNotes = 0;
		int melodicLayers = 0;
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
				Split melodicSplit = Split.melodic();
				boolean wholeLayer = shifting == OctaveShifting.CONVERT_TO_MELODIC;
				// Convert moves a layer or it does not, and one stray decides. Asked up front
				// rather than per note, because the answer for the first note has to be the answer
				// for the last: a part half on its instrument and half on a split is the thing
				// this mode exists to not produce.
				boolean anyOutOfRange = sourceNotes.stream().anyMatch(note -> !note.isBuildable());
				List<NoteEvent> kept = new ArrayList<>();
				List<NoteEvent> relocated = new ArrayList<>();
				for (NoteEvent note : sourceNotes) {
					long quantizedStart =
						Math.max(0L, Math.round(note.startTick() / (double)grid) * (long)grid);
					if (!anyOutOfRange || (!wholeLayer && note.isBuildable())) {
						kept.add(note.movedTo(quantizedStart, note.midiNote()));
						continue;
					}
					// Six octaves of brackets, so this only moves a note written outside F#1 to
					// F#7 at all -- and it moves that one by a whole octave, like everywhere else.
					int shift = melodicSplit.covers(note.midiNote())
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
					Layer built = emitted.get(emittedIndex);
					// Two notes an octave apart can still land on one cell, where both were
					// outside the brackets and the same octave brought them in. A layer keeps one
					// note per cell, and this is the one way the mode can take a note away.
					mergedIntoExisting += fed.get(emittedIndex) - built.notes().size();
					convertedLayers.add(built);
				}
				continue;
			}
			// Where the layer sits before any note is looked at individually.
			int base = pitched && shifting == OctaveShifting.LAYER_THEN_NOTES
				? bestLayerOctaveShift(sourceNotes)
				: 0;
			for (NoteEvent note : sourceNotes) {
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
				Layer built = source.withName(convertedName).withNotes(entry.getValue());
				// What the layer would not hold. A layer keeps one note per pitch per tick, so two
				// source notes an octave apart that land on the same pitch become one -- the same
				// dedupe the split reports as a dropped duplicate layer, arriving a note at a time
				// because there is no second layer for it to arrive as. Counted rather than left
				// silent: it is the one way this can take notes away, and it should say so.
				mergedIntoExisting += entry.getValue().size() - built.notes().size();
				convertedLayers.add(built);
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
			melodicLayers
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

	/**
	 * Adds one note, or hands back the same composition if that cell is already taken.
	 *
	 * <p>The constructor would drop the duplicate either way -- see {@link Layer#oneNotePerCell} --
	 * but going through it would still spend an id and hand back a record that differs, which is an
	 * undo step for an edit that changed nothing. Refusing here is what makes clicking an occupied
	 * cell a no-op rather than something Ctrl+Z has to be pressed to get past.</p>
	 */
	public ComposerProject addNote(int layerIndex, int midiNote, long startTick, long durationTicks) {
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Layer layer = layers.get(target);
		int clampedNote = Math.max(0, Math.min(127, midiNote));
		long clampedTick = Math.max(0L, startTick);
		for (NoteEvent existing : layer.notes()) {
			if (existing.startTick() == clampedTick && existing.midiNote() == clampedNote) {
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
				copied.add(new NoteEvent(nextId++, note.midiNote(), note.startTick(),
					note.durationTicks(), note.velocity()));
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
				NoteEvent copy = new NoteEvent(id++, note.midiNote(),
					Math.max(0L, note.startTick() + tickDelta), note.durationTicks(),
					note.velocity());
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
			copied.add(new NoteEvent(nextId++, note.midiNote(), note.startTick(), note.durationTicks(),
				note.velocity()));
		}
		List<Layer> updated = new ArrayList<>(layers);
		updated.add(index + 1, source.withName(source.name() + " copy").withNotes(copied));
		return with(updated, index + 1, nextId);
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
		List<Layer> chosen = new ArrayList<>();
		Set<NoteSound> heard = dedupeIdentical ? new java.util.HashSet<>() : null;
		for (Layer layer : layers) {
			if (!layer.inBuild()) {
				continue;
			}
			// Split layers arrive already expanded into their voices, so every layer this returns
			// is a plain one-instrument layer and downstream readers need no new case.
			for (Layer voice : layer.buildVoices()) {
				chosen.add(heard == null ? voice : withoutAlreadyHeard(voice, heard));
			}
		}
		return List.copyOf(chosen);
	}

	private static List<Layer> normalizeLayers(List<Layer> source) {
		List<Layer> normalized = new ArrayList<>();
		if (source != null) {
			for (Layer layer : source) {
				if (layer != null && normalized.size() < MAX_LAYERS) {
					normalized.add(new Layer(layer.name(), layer.instrument(), layer.muted(),
						layer.buildEnabled(), layer.visible(), layer.notes(), layer.split()));
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
