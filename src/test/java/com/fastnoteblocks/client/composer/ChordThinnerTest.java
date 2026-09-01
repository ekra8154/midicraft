package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Thinning may take the colour out of a chord. It may never take a note out of the harmony.
 *
 * <p>That is the promise the operation is sold on -- run it without listening and the pitches
 * sounding at every instant are the ones that were there before -- so it is what these check,
 * rather than the counts, which are only evidence that it did something.</p>
 */
class ChordThinnerTest {
	private static long nextId = 1L;

	@Test
	void neverRemovesAPitchNothingElseIsPlaying() {
		for (int target : new int[] {30, 24, 20, 8}) {
			for (long seed : new long[] {1L, 2L, 3L, 4L}) {
				ComposerProject song = stacked(seed);
				ChordThinner.Result thinned = ChordThinner.thin(song, target, true);
				assertPitchesSurvive(song, thinned.noteIds(), "target " + target + ", seed " + seed);
			}
		}
	}

	@Test
	void bringsEveryChordDownToTheTarget() {
		ComposerProject song = stacked(7L);
		for (int target : new int[] {30, 24, 20}) {
			ChordThinner.Result thinned = ChordThinner.thin(song, target, true);
			ComposerProject after = song.deleteNotes(thinned.noteIds());
			Map<Long, Set<String>> sounds = soundsPerTick(after);
			for (Map.Entry<Long, Set<String>> chord : sounds.entrySet()) {
				assertTrue(chord.getValue().size() <= target,
					"tick " + chord.getKey() + " still holds " + chord.getValue().size()
						+ " at target " + target);
			}
			assertEquals(0, thinned.chordsStillOver(),
				"a chord this doubled should always reach " + target);
		}
	}

	/**
	 * With deduplication on, the count only moves when every copy of a sound goes.
	 *
	 * <p>The trap this guards: selecting one note of a sound that two layers play deletes a note
	 * and leaves the chord exactly as big as it was, so the operation appears to do nothing.</p>
	 */
	@Test
	void selectsEveryCopyOfTheSoundsItDrops() {
		ComposerProject song = stacked(11L);
		ChordThinner.Result thinned = ChordThinner.thin(song, 24, true);
		assertTrue(thinned.noteIds().size() > thinned.soundsRemoved(),
			"this song doubles sounds across layers, so notes must outnumber sounds");
		ComposerProject after = song.deleteNotes(thinned.noteIds());
		for (Set<String> chord : soundsPerTick(after).values()) {
			assertTrue(chord.size() <= 24, "a surviving copy would have kept the chord over");
		}
	}

	/** With deduplication off every note counts for itself, so thinning has to count that way too. */
	@Test
	void countsUnmergedWhenTheBuildWould() {
		// Forty notes of one sound: merged that is one sound and nothing to do, unmerged it is
		// forty and thirty-two have to go.
		//
		// A layer each, because one layer cannot hold the same pitch twice at the same tick. Forty
		// harp layers is what deliberately doubling a note forty times over now costs, and saying so
		// is the point: the doubling is what is being counted.
		List<Layer> layers = new ArrayList<>();
		for (int index = 0; index < 40; index++) {
			layers.add(layer("Harp " + (index + 1), "HARP", List.of(note(60, 0L, 100))));
		}
		ComposerProject song = songOf(layers);
		assertEquals(40, song.noteCount(), "every one of them survived being put in its own layer");
		assertTrue(ChordThinner.thin(song, 8, true).isEmpty());
		assertEquals(32, ChordThinner.thin(song, 8, false).noteIds().size());
	}

	/**
	 * The lone drum in a loud chord stays, even though its pitch is covered many times over.
	 *
	 * <p>The case that motivated the instrument rule. A snare sharing a pitch with a wall of harps
	 * looks maximally redundant by pitch alone -- most doubled pitch, and quiet -- so it was the
	 * first thing the old comparator reached for. But the note was never the point; having a drum
	 * in the bar was.</p>
	 */
	@Test
	void keepsTheOnlyDrumInAChord() {
		// Doubling has to come from many instruments, not many copies: with deduplication on, forty
		// harp notes of one pitch are one sound and there is nothing to thin.
		String[] melodic = {
			"HARP", "BASS", "GUITAR", "FLUTE", "BELL", "CHIME", "XYLOPHONE", "IRON_XYLOPHONE",
			"COW_BELL", "DIDGERIDOO", "BIT", "BANJO"
		};
		List<Layer> layers = new ArrayList<>();
		for (String instrument : melodic) {
			List<NoteEvent> notes = new ArrayList<>();
			for (int pitch = 60; pitch < 64; pitch++) {
				notes.add(note(pitch, 0L, 127));
			}
			layers.add(layer(instrument, instrument, notes));
		}
		// One quiet snare, on the most heavily doubled pitch in the chord: by pitch alone it is the
		// most redundant thing there, and the loudness tie-break reaches for it first.
		layers.add(layer("Snare", "SNARE", List.of(note(60, 0L, 1))));
		ComposerProject song = songOf(layers);

		ChordThinner.Result thinned = ChordThinner.thin(song, 20, true);
		assertTrue(thinned.soundsRemoved() > 0, "there is plenty of melodic doubling to take");
		ComposerProject after = song.deleteNotes(thinned.noteIds());
		assertTrue(instrumentsPerTick(after).get(0L).contains("SNARE"),
			"the only snare in the chord was thinned away");
	}

	/** Given one layer to work with, it only ever deletes notes on that layer. */
	@Test
	void takesOnlyFromTheLayersItIsOffered() {
		ComposerProject song = stacked(5L);
		for (int layerIndex = 0; layerIndex < song.layers().size(); layerIndex++) {
			ChordThinner.Result thinned =
				ChordThinner.thin(song, 20, true, Set.of(layerIndex));
			Set<Long> allowed = new HashSet<>();
			for (NoteEvent note : song.layers().get(layerIndex).notes()) {
				allowed.add(note.id());
			}
			assertTrue(allowed.containsAll(thinned.noteIds()),
				"layer " + layerIndex + " was told to thin itself and touched something else");
			assertPitchesSurvive(song, thinned.noteIds(), "layer " + layerIndex + " alone");
		}
	}

	/**
	 * A sound two layers play cannot go when only one of them is offered.
	 *
	 * <p>Deduplication means the count only moves when every copy of a sound goes, so taking this
	 * one would have to delete a note on a layer nobody selected. It stays instead.</p>
	 */
	@Test
	void willNotReachIntoAnUnofferedLayerToFinishASound() {
		List<NoteEvent> shared = new ArrayList<>();
		List<NoteEvent> alsoShared = new ArrayList<>();
		for (int pitch = 60; pitch < 72; pitch++) {
			shared.add(note(pitch, 0L, 100));
			alsoShared.add(note(pitch, 0L, 100));
		}
		// Both layers carry the same instrument and pitches, so every sound has a copy in each.
		ComposerProject song = songOf(List.of(
			layer("A", "HARP", shared),
			layer("B", "HARP", alsoShared)));
		assertEquals(12, soundsPerTick(song).get(0L).size(), "the two layers fold into twelve");
		ChordThinner.Result thinned = ChordThinner.thin(song, 8, true, Set.of(0));
		assertTrue(thinned.isEmpty(),
			"every sound has a copy on layer B, so none of them can be taken from A alone");
		assertEquals(1, thinned.chordsOver());
		assertEquals(0, thinned.chordsThinned());
		assertEquals(1, thinned.chordsStillOver());
	}

	/** Offering every layer is the same as offering none in particular. */
	@Test
	void offeringEveryLayerMatchesTheWholeSong() {
		ComposerProject song = stacked(9L);
		Set<Integer> everything = new HashSet<>();
		for (int index = 0; index < song.layers().size(); index++) {
			everything.add(index);
		}
		assertEquals(ChordThinner.thin(song, 24, true).noteIds(),
			ChordThinner.thin(song, 24, true, everything).noteIds());
	}

	/** A chord of unique pitches cannot be thinned at all, and says so rather than gutting it. */
	@Test
	void leavesAChordItCannotThinAlone() {
		List<NoteEvent> notes = new ArrayList<>();
		for (int pitch = 54; pitch < 54 + 25; pitch++) {
			notes.add(note(pitch, 0L, 100));
		}
		ComposerProject song = songOf(List.of(layer("Harp", "HARP", notes)));
		ChordThinner.Result thinned = ChordThinner.thin(song, 20, true);
		assertTrue(thinned.isEmpty(), "nothing is doubled, so nothing can go");
		assertEquals(1, thinned.chordsStillOver());
		assertEquals(0, thinned.chordsThinned());
	}

	/** Layers left out of the build cannot overload a tick they are not part of. */
	@Test
	void ignoresLayersOutsideTheBuild() {
		List<NoteEvent> included = new ArrayList<>();
		List<NoteEvent> excluded = new ArrayList<>();
		for (int index = 0; index < 20; index++) {
			included.add(note(54 + index % 10, 0L, 100));
			excluded.add(note(54 + index % 10, 0L, 100));
		}
		ComposerProject song = songOf(List.of(
			layer("In", "HARP", included),
			new Layer("Out", "FLUTE", true, true, true, excluded)));
		assertTrue(ChordThinner.thin(song, 20, true).isEmpty(),
			"only the included layer's ten sounds count, which is under the target");
	}

	/** Running it twice on its own output finds nothing left to do. */
	@Test
	void settlesAfterOnePass() {
		ComposerProject song = stacked(3L);
		ComposerProject after = song.deleteNotes(ChordThinner.thin(song, 24, true).noteIds());
		assertTrue(ChordThinner.thin(after, 24, true).isEmpty());
	}

	/**
	 * The same promise against the real library, which is the only place chords like this exist.
	 *
	 * <p>A generator can be made to produce whatever the code under test happens to handle. These
	 * are transcriptions somebody actually made, and the one song in the library with overloaded
	 * chords is the one the operation was written for.</p>
	 */
	@Test
	void keepsEveryPitchAcrossTheLibrary() throws Exception {
		java.nio.file.Path songs = songsDirectory();
		if (!java.nio.file.Files.isDirectory(songs)) {
			System.out.println("no song library here, skipped");
			return;
		}
		List<java.nio.file.Path> files;
		try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(songs)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		for (java.nio.file.Path file : files) {
			ComposerProject song;
			try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
				ComposerProject raw = new com.google.gson.Gson().fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			String name = file.getFileName().toString().replace(".json", "");
			for (int target : new int[] {30, 24}) {
				ChordThinner.Result thinned = ChordThinner.thin(song, target, true);
				assertPitchesSurvive(song, thinned.noteIds(), name + " at " + target);
				ComposerProject after = song.deleteNotes(thinned.noteIds());
				for (Map.Entry<Long, Set<String>> chord : soundsPerTick(after).entrySet()) {
					assertTrue(chord.getValue().size() <= target || thinned.chordsStillOver() > 0,
						name + " tick " + chord.getKey() + " left at " + chord.getValue().size());
				}
				if (!thinned.isEmpty()) {
					System.out.println(String.format(java.util.Locale.ROOT,
						"%-34s target %2d: %3d chords, %4d sounds, %4d notes, %d unfixable",
						name, target, thinned.chordsThinned(), thinned.soundsRemoved(),
						thinned.noteIds().size(), thinned.chordsStillOver()));
				}
			}
		}
	}

	private static java.nio.file.Path songsDirectory() {
		java.nio.file.Path local =
			java.nio.file.Path.of("run", "config", "midicraft", "songs");
		if (java.nio.file.Files.isDirectory(local)) {
			return local;
		}
		return java.nio.file.Path.of("D:", "Documents", "modding", "fast-noteblocks",
			"run", "config", "midicraft", "songs");
	}

	/**
	 * Both halves of the promise: a chord keeps every pitch and every instrument it started with.
	 *
	 * <p>The instrument half was added after the pitch half shipped, because pitch doubling alone
	 * does not make a voice redundant -- a snare and a harp on one pitch are not two of anything.
	 * Nine chords across the stress songs lost their only drum before this checked for it.</p>
	 */
	private static void assertPitchesSurvive(ComposerProject song, Set<Long> doomed, String where) {
		ComposerProject after = song.deleteNotes(doomed);
		Map<Long, Set<Integer>> pitchesBefore = pitchesPerTick(song);
		Map<Long, Set<Integer>> pitchesAfter = pitchesPerTick(after);
		for (Map.Entry<Long, Set<Integer>> chord : pitchesBefore.entrySet()) {
			assertEquals(chord.getValue(), pitchesAfter.get(chord.getKey()),
				where + ": the pitches at tick " + chord.getKey() + " changed");
		}
		Map<Long, Set<String>> timbresBefore = instrumentsPerTick(song);
		Map<Long, Set<String>> timbresAfter = instrumentsPerTick(after);
		for (Map.Entry<Long, Set<String>> chord : timbresBefore.entrySet()) {
			assertEquals(chord.getValue(), timbresAfter.get(chord.getKey()),
				where + ": the instruments at tick " + chord.getKey() + " changed");
		}
	}

	private static Map<Long, Set<String>> instrumentsPerTick(ComposerProject song) {
		Map<Long, Set<String>> instruments = new HashMap<>();
		for (Layer layer : song.layers()) {
			if (!layer.buildEnabled()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (note.isBuildable()) {
					instruments.computeIfAbsent(note.startTick(), tick -> new HashSet<>())
						.add(layer.instrument());
				}
			}
		}
		return instruments;
	}

	private static Map<Long, Set<Integer>> pitchesPerTick(ComposerProject song) {
		Map<Long, Set<Integer>> pitches = new HashMap<>();
		for (Layer layer : song.layers()) {
			if (!layer.buildEnabled()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (note.isBuildable()) {
					pitches.computeIfAbsent(note.startTick(), tick -> new HashSet<>())
						.add(note.midiNote());
				}
			}
		}
		return pitches;
	}

	private static Map<Long, Set<String>> soundsPerTick(ComposerProject song) {
		Map<Long, Set<String>> sounds = new HashMap<>();
		for (Layer layer : song.layers()) {
			if (!layer.buildEnabled()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (note.isBuildable()) {
					sounds.computeIfAbsent(note.startTick(), tick -> new HashSet<>())
						.add(layer.instrument() + " " + note.midiNote());
				}
			}
		}
		return sounds;
	}

	/**
	 * A song shaped like a transcription: a handful of pitches per chord, each played by a pile of
	 * instruments. That doubling is the thing being thinned, so a generator without it would test
	 * nothing.
	 *
	 * <p>Each instrument gets {@link #VOICES_PER_INSTRUMENT} layers and its nth simultaneous note
	 * goes to the nth of them. A doubled sound is one instrument playing one pitch twice at once, and
	 * a layer holds at most one note per pitch per tick -- so a doubled sound <em>is</em> two layers
	 * of one instrument, and a generator that put them in one layer produced a song with no doubling
	 * left in it once the constructor had had its say. Which is also what a converted song looks
	 * like: layers group by instrument and one instrument spans several of them.</p>
	 */
	private static ComposerProject stacked(long seed) {
		java.util.Random random = new java.util.Random(seed);
		String[] instruments = {
			"HARP", "FLUTE", "BELL", "CHIME", "GUITAR", "BASS", "BANJO", "PLING", "BIT",
			"XYLOPHONE", "COW_BELL", "DIDGERIDOO", "IRON_XYLOPHONE", "TRUMPET"
		};
		Map<String, List<NoteEvent>> byLayer = new java.util.LinkedHashMap<>();
		for (String instrument : instruments) {
			for (int voice = 0; voice < VOICES_PER_INSTRUMENT; voice++) {
				byLayer.put(instrument + " " + (voice + 1), new ArrayList<>());
			}
		}
		for (long tick = 0L; tick < 12L; tick++) {
			int voices = 4 + random.nextInt(8);
			int[] pitches = new int[voices];
			for (int index = 0; index < voices; index++) {
				pitches[index] = 54 + random.nextInt(25);
			}
			for (String instrument : instruments) {
				int plays = 1 + random.nextInt(VOICES_PER_INSTRUMENT);
				for (int index = 0; index < plays; index++) {
					byLayer.get(instrument + " " + (index + 1))
						.add(note(pitches[random.nextInt(voices)], tick, 1 + random.nextInt(127)));
				}
			}
		}
		List<Layer> layers = new ArrayList<>();
		byLayer.forEach((name, notes) ->
			layers.add(layer(name, name.substring(0, name.lastIndexOf(' ')), notes)));
		return songOf(layers);
	}

	/** How many layers an instrument is spread over, and so how often it may double a pitch. */
	private static final int VOICES_PER_INSTRUMENT = 4;

	private static Layer layer(String name, String instrument, List<NoteEvent> notes) {
		List<NoteEvent> sorted = new ArrayList<>(notes);
		sorted.sort(java.util.Comparator.comparingLong(NoteEvent::startTick));
		return new Layer(name, instrument, false, true, true, sorted);
	}

	private static NoteEvent note(int midiNote, long startTick, int velocity) {
		return new NoteEvent(nextId++, midiNote, startTick, 1L, velocity);
	}

	private static ComposerProject songOf(List<Layer> layers) {
		return new ComposerProject("test", 480, 500_000, layers, 0, nextId + 1_000_000L, 100L, 4);
	}
}
