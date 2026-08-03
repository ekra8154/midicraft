package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.ComposerProject.TransposeFit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Choosing where the pitch window sits, rather than folding notes into it where it already is.
 *
 * <p>The interesting case is the one where minimising notes out of range and keeping the tune
 * disagree, because that is the whole reason the count is weighted and the only thing a test can
 * hold in place.</p>
 */
class TransposeFitTest {
	private static final int LOW = ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE;
	private static final int HIGH = ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE;
	private static long nextId = 1L;

	/** A song that fits where it stands has nothing to gain, and the menu greys itself out on this. */
	@Test
	void staysPutWhenEverythingAlreadyFits() {
		ComposerProject song = songOf(layer("Melody", LOW, LOW + 12, HIGH));

		TransposeFit fit = song.bestTransposeIntoRange();

		assertEquals(0, fit.semitones());
		assertEquals(0L, fit.outNow());
		assertFalse(fit.worthDoing());
		assertSame(song, song.transposedBy(0));
	}

	/** A song narrow enough to fit but sitting badly is slid until it does, exactly. */
	@Test
	void slidesANarrowSongAllTheWayIn() {
		ComposerProject song = songOf(layer("Melody", LOW - 4, LOW + 6, HIGH - 4));

		TransposeFit fit = song.bestTransposeIntoRange();

		assertEquals(4, fit.semitones(), "up by exactly what it was sitting low by");
		assertEquals(1L, fit.outNow());
		assertEquals(0L, fit.outAfter());
		assertTrue(fit.worthDoing());
	}

	/**
	 * The window follows the tune, not the head count.
	 *
	 * <p>Ten melody notes above the range and twenty accompaniment notes below it, none of which can
	 * be in range at once -- the song is wider than two octaves. Fewest-notes-out would move up and
	 * keep the twenty; this moves down and keeps the ten, because those ten are the top voice and a
	 * melody that jumps an octave reads as a mistake where a bass line that does reads as a bass
	 * line.</p>
	 */
	@Test
	void keepsTheMelodyRatherThanTheMostNotes() {
		List<NoteEvent> notes = new ArrayList<>();
		for (long tick = 0L; tick < 10L; tick++) {
			notes.add(note(HIGH + 6, tick));
			notes.add(note(LOW - 4, tick));
			notes.add(note(LOW - 3, tick));
		}
		ComposerProject song = songOf(new Layer("All", "HARP", false, true, true, notes));

		TransposeFit fit = song.bestTransposeIntoRange();

		assertEquals(-6, fit.semitones(), "down far enough to bring the top voice in");
		assertEquals(0L, fit.melodyOutAfter(), "which is the whole point");
		assertEquals(10L, fit.melodyOutNow());
		// Deliberately worse by the raw count: it gave up twenty notes to save ten.
		assertEquals(30L, fit.outNow());
		assertEquals(20L, fit.outAfter());
		assertTrue(fit.worthDoing());
	}

	/** Only what is being built has to fit, since a layer left out is not in the world. */
	@Test
	void measuresOnlyTheLayersInTheBuild() {
		ComposerProject song = songOf(
			new Layer("Built", "HARP", false, true, true, List.of(note(LOW - 4, 0L))),
			new Layer("Excluded", "HARP", false, false, true, List.of(note(120, 0L))));

		TransposeFit fit = song.bestTransposeIntoRange();

		assertEquals(4, fit.semitones(), "the excluded layer did not drag the window up");
	}

	/** Transposing moves every layer, including ones left out: a key belongs to the song. */
	@Test
	void movesEveryLayerIncludingTheOnesLeftOut() {
		ComposerProject song = songOf(
			new Layer("Built", "HARP", false, true, true, List.of(note(60, 0L))),
			new Layer("Excluded", "HARP", false, false, true, List.of(note(48, 0L))));

		ComposerProject moved = song.transposedBy(5);

		assertEquals(65, moved.layers().get(0).notes().getFirst().midiNote());
		assertEquals(53, moved.layers().get(1).notes().getFirst().midiNote(),
			"or the two layers would be in different keys");
	}

	/** Refused rather than clamped: a clamp would stack notes on top of MIDI's own edge. */
	@Test
	void refusesAShiftThatWouldRunOffTheEndOfMidi() {
		ComposerProject song = songOf(layer("High", 125));
		assertSame(song, song.transposedBy(6));
		assertEquals(126, song.transposedBy(1).layers().getFirst().notes().getFirst().midiNote());
	}

	private static NoteEvent note(int midi, long tick) {
		return new NoteEvent(nextId++, midi, tick, 120L, 100);
	}

	private static Layer layer(String name, int... pitches) {
		List<NoteEvent> notes = new ArrayList<>();
		long tick = 0L;
		for (int pitch : pitches) {
			notes.add(note(pitch, tick));
			tick += 240L;
		}
		return new Layer(name, "HARP", false, true, true, notes);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("transpose", 480, 500_000, List.of(layers), 0, nextId + 1000L,
			4000L, 4);
	}
}
