package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fitting chords to the thinning target by leaving out what was added -- strikes and extra copies --
 * and never a written note.
 */
class ChordSkipsTest {
	private static final Sustain EVERY_SIXTEENTH =
		new Sustain(true, SustainLength.QUARTER, SustainLength.SIXTEENTH);
	private static long nextId = 1L;

	private static NoteEvent note(int midi, long tick, long length) {
		return new NoteEvent(nextId++, midi, tick, length, 90);
	}

	private static Layer layer(String instrument, NoteEvent... notes) {
		return new Layer("Part", instrument, false, true, true, List.of(notes));
	}

	/** {@code count} notes from F#3 up, all on one tick. */
	private static Layer chord(String instrument, int count, long tick) {
		NoteEvent[] notes = new NoteEvent[count];
		for (int index = 0; index < count; index++) {
			notes[index] = note(54 + index, tick, 120);
		}
		return layer(instrument, notes);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Fit", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, nextId,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static long blocksAt(List<Layer> built, long tick) {
		return built.stream().flatMap(each -> each.notes().stream())
			.filter(each -> each.startTick() == tick).count();
	}

	/** The example the feature was asked for: a harp at ten plays what fits, only where it must. */
	@Test
	void aLoudHarpPlaysWhatFitsOnlyWhereItHasTo() {
		NoteEvent loud = note(60, 0, 120);
		NoteEvent alone = note(60, 480, 120);
		ComposerProject song = songOf(layer("HARP", loud, alone).withCountStepped("HARP", 9),
			chord("BELL", 23, 0));

		ChordSkips skips = ChordSkips.of(song, true, 30, 0.0);
		assertArrayEquals(new int[] {10, 7}, skips.playedAt(0, loud.id(), 0L));
		assertEquals(null, skips.playedAt(0, alone.id(), 480L), "the harp alone is not over anything");

		List<Layer> built = song.buildLayers(true, 30);
		assertEquals(30L, blocksAt(built, 0L));
		assertEquals(10L, blocksAt(built, 480L));
		SongAnalysis stats = SongAnalysis.of(song, true, true, 30);
		assertEquals(30, stats.peakChord());
		assertEquals(40, stats.buildNotes());
		long tokens = song.toSequenceTracks(Set.of(), true, 30).stream()
			.flatMap(track -> Arrays.stream(track.sequence().split(", ")))
			.filter(token -> !token.isBlank() && !token.endsWith("d")).count();
		assertEquals(40L, tokens, "the sequence text places what the analysis counts");
		assertTrue(skips.thinnedNoteIds().contains(loud.id()));
	}

	@Test
	void itFollowsTheThinningPreference() {
		NoteEvent loud = note(60, 0, 120);
		ComposerProject song = songOf(layer("HARP", loud).withCountStepped("HARP", 9), chord("BELL", 23, 0));

		assertArrayEquals(new int[] {10, 2}, ChordSkips.of(song, true, 25, 0.0).playedAt(0, loud.id(), 0L));
		assertEquals(25L, blocksAt(song.buildLayers(true, 25), 0L));
	}

	@Test
	void copiesComeOffTheLoudestVoiceFirst() {
		NoteEvent harp = note(60, 0, 120);
		NoteEvent bass = note(60, 0, 120);
		ComposerProject song = songOf(layer("HARP", harp).withCountStepped("HARP", 9),
			layer("BASS", bass).withCountStepped("BASS", 4), chord("BELL", 20, 0));

		ChordSkips skips = ChordSkips.of(song, true, 30, 0.0);
		assertArrayEquals(new int[] {10, 5}, skips.playedAt(0, harp.id(), 0L));
		assertEquals(null, skips.playedAt(1, bass.id(), 0L), "the bass at five keeps all five");
	}

	/** Volume gives way before a strike, and each can be switched off on its own. */
	@Test
	void volumeGivesWayBeforeAStrike() {
		NoteEvent held = note(60, 0, 960);
		NoteEvent flute = note(64, 120, 120);
		ComposerProject song = songOf(
			layer("HARP", held).withSustain(EVERY_SIXTEENTH),
			layer("FLUTE", flute).withCountStepped("FLUTE", 4),
			chord("BELL", 25, 120));
		double finest = song.finestSustainStep();

		ChordSkips both = ChordSkips.of(song, true, 30, finest);
		assertEquals(4, both.copiesAt(1, 0, flute.id(), 120L, 5), "one flute copy covers the one over");
		assertFalse(both.skips(0, held.id(), 120L), "so the strike plays");

		ChordSkips strikesOnly = ChordSkips.of(song, true, new ChordSkips.Rules(30, false, true), finest);
		assertEquals(5, strikesOnly.copiesAt(1, 0, flute.id(), 120L, 5));
		assertTrue(strikesOnly.skips(0, held.id(), 120L));
		assertEquals(1, strikesOnly.skippedStrikes(0, held.id()));
		assertFalse(strikesOnly.skips(0, held.id(), 240L), "nothing is over at 240");

		ChordSkips neither = ChordSkips.of(song, true, new ChordSkips.Rules(30, false, false), finest);
		assertFalse(neither.skips(0, held.id(), 120L));
		assertEquals(5, neither.copiesAt(1, 0, flute.id(), 120L, 5));
		assertEquals(Set.of(120L), neither.stillOver(), "with both off the chord stays over, for the thinner");
	}

	@Test
	void volumeAloneNeverSkipsAStrike() {
		NoteEvent held = note(60, 0, 960);
		ComposerProject song = songOf(layer("HARP", held).withSustain(EVERY_SIXTEENTH),
			chord("BELL", 25, 120), chord("FLUTE", 5, 120));

		ChordSkips volumeOnly = ChordSkips.of(song, true, new ChordSkips.Rules(30, true, false),
			song.finestSustainStep());
		assertFalse(volumeOnly.skips(0, held.id(), 120L));
		assertEquals(Set.of(120L), volumeOnly.stillOver(), "nothing counted to lower, and strikes may not go");
	}

	@Test
	void aHeldNoteNeverLosesTwoStrikesInARow() {
		NoteEvent held = note(60, 0, 960);
		ComposerProject song = songOf(
			layer("HARP", held).withSustain(EVERY_SIXTEENTH),
			chord("BELL", 25, 120), chord("FLUTE", 5, 120),
			chord("BELL", 25, 240), chord("FLUTE", 5, 240));

		ChordSkips skips = ChordSkips.of(song, true, 30, song.finestSustainStep());
		assertTrue(skips.skips(0, held.id(), 120L));
		assertFalse(skips.skips(0, held.id(), 240L), "the strike after a skipped one plays");
		assertEquals(Set.of(240L), skips.stillOver(), "so 240 stays over, for the thinner");
	}

	@Test
	void writtenNotesAreNeverLeftOut() {
		ComposerProject song = songOf(chord("BELL", 25, 0), chord("FLUTE", 6, 0));

		ChordSkips skips = ChordSkips.of(song, true, 30, 0.0);
		assertEquals(Set.of(0L), skips.stillOver());
		assertEquals(0, skips.blocksRemoved());
		assertEquals(31L, blocksAt(song.buildLayers(true, 30), 0L));
	}

	@Test
	void theThinnerWeighsChordsAfterTheSkips() {
		ComposerProject song = songOf(layer("HARP", note(60, 0, 120)).withCountStepped("HARP", 9),
			chord("BELL", 23, 0));

		assertEquals(0, ChordThinner.thin(song, 25, true).chordsOver(),
			"skipping already brings the chord to 25, so there is nothing to thin");
	}

	@Test
	void aSongUnderItsTargetLeavesNothingOut() {
		ComposerProject song = songOf(chord("BELL", 20, 0));

		assertTrue(ChordSkips.of(song, true, 30, 0.0).isEmpty());
		assertTrue(SongAnalysis.of(song, true, true, 30).skips().isEmpty());
	}
}
