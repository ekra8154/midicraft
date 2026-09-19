package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Strikes;
import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Sustains in the build: the strikes the roll draws are the notes the build places, and everything
 * that weighs a build weighs them.
 */
class SustainBuildTest {

	// These pin the exact strike arithmetic, so they measure it with alignment off. Aligning is
	// tested on its own in SustainAlignTest.
	@org.junit.jupiter.api.BeforeEach
	void strikeExactly() {
		com.midicraft.client.composer.ComposerProject.ALIGN_SUSTAINED_NOTES = false;
	}

	@org.junit.jupiter.api.AfterEach
	void alignAgain() {
		com.midicraft.client.composer.ComposerProject.ALIGN_SUSTAINED_NOTES = true;
	}
	private static final Sustain EVERY_SIXTEENTH =
		new Sustain(true, SustainLength.QUARTER, SustainLength.SIXTEENTH);
	private static long nextId = 1L;

	private static NoteEvent note(int midi, long tick, long length) {
		return new NoteEvent(nextId++, midi, tick, length, 90);
	}

	private static Layer layer(String instrument, Sustain sustain, NoteEvent... notes) {
		return new Layer("Part", instrument, false, true, true, List.of(notes)).withSustain(sustain);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Held", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, nextId,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	/** The one promise the feature is built on: what the roll draws is what the machine plays. */
	@Test
	void theBuildPlacesExactlyTheStrikesTheRollDraws() {
		NoteEvent held = note(60, 0, 960);
		Layer pad = layer("HARP", EVERY_SIXTEENTH, held, note(64, 480, 240));
		ComposerProject song = songOf(pad);

		Strikes strikes = song.sustainStrikes(pad, song.finestSustainStep());
		List<Long> drawn = new ArrayList<>(List.of(0L));
		strikes.forEach(held, 0L, Long.MAX_VALUE, drawn::add);
		List<Long> built = song.buildLayers(true).get(0).notes().stream()
			.filter(each -> each.midiNote() == 60).map(NoteEvent::startTick).toList();

		assertEquals(List.of(0L, 120L, 240L, 360L, 480L, 600L, 720L, 840L), drawn);
		assertEquals(drawn, built);
		String sequence = song.toSequenceTracks(Set.of(), true).get(0).sequence();
		long noteTokens = java.util.Arrays.stream(sequence.split(",\s*"))
			.filter(token -> !token.endsWith("d")).count();
		assertEquals(9L, noteTokens, "the sequence text: eight on the held note, one on the short one");
		assertEquals(9, SongAnalysis.of(song, true).buildNotes());
		assertEquals(7, song.sustainStrikeCount());
	}

	@Test
	void aStrikeCanOverloadAChord() {
		NoteEvent[] held = new NoteEvent[20];
		for (int index = 0; index < held.length; index++) {
			held[index] = note(54 + index, 0, 480);
		}
		NoteEvent[] landing = new NoteEvent[12];
		for (int index = 0; index < landing.length; index++) {
			landing[index] = note(54 + index, 120, 120);
		}
		ComposerProject song = songOf(layer("HARP", EVERY_SIXTEENTH, held), layer("BELL", null, landing));

		assertEquals(0L, SongAnalysis.ofWritten(song, true).overloadedTicks(), "as written, nothing is over");
		assertTrue(SongAnalysis.of(song, true, true, Integer.MAX_VALUE).overloadedTicks() >= 1L,
			"twenty strikes and twelve notes on one tick are thirty-two note blocks");
		SongAnalysis fitted = SongAnalysis.of(song, true);
		assertEquals(0L, fitted.overloadedTicks(), "at the cap, two strikes give way");
		assertEquals(2, fitted.skips().strikesSkipped());
	}

	@Test
	void anOutOfRangeNoteIsCountedOnceHoweverOftenItStrikes() {
		ComposerProject song = songOf(layer("HARP", EVERY_SIXTEENTH, note(100, 0, 960)));

		assertEquals(1, SongAnalysis.of(song, true).outOfRange());
	}

	/** Taking a strike would take the whole held note, so the thinner never offers one. */
	@Test
	void theThinnerNeverTakesAStrike() {
		NoteEvent held = note(60, 0, 480);
		Layer pad = layer("HARP", EVERY_SIXTEENTH, held);
		Layer chord = layer("HARP", null, note(60, 120, 120), note(62, 120, 120), note(64, 120, 120),
			note(65, 120, 120), note(67, 120, 120), note(69, 120, 120), note(71, 120, 120),
			note(72, 120, 120));
		Layer bass = layer("BASS", null, note(60, 120, 120));
		ComposerProject song = songOf(pad, chord, bass);

		ChordThinner.Result thinned = ChordThinner.thin(song, 8, true);

		assertTrue(thinned.noteIds().isEmpty(),
			"the only doubled harp C at 120 is doubled by a strike, and the bass C is the only bass");
		assertEquals(1, thinned.chordsStillOver());
	}

	@Test
	void theMergeWindowFollowsTheSongsSpeed() {
		// A repeater tick is 96 composer ticks at speed 1 and 192 at speed 2, so notes 150 apart are
		// a repeat only at the faster speed.
		ComposerProject song = songOf(layer("HARP", null, note(60, 0, 120), note(60, 150, 120)));

		assertEquals(2, song.withMergedRepeats(1, Set.of()).layers().get(0).notes().size());
		ComposerProject faster = song.withSpeedEighths(16);
		assertEquals(1, faster.withMergedRepeats(1, Set.of()).layers().get(0).notes().size());
		assertEquals(270L, faster.withMergedRepeats(1, Set.of()).layers().get(0).notes().get(0).durationTicks(),
			"the merged note keeps the repeat as its trail");
	}
}
