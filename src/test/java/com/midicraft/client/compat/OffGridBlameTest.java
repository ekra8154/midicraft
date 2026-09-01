package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.SongAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which notes "off grid" names.
 *
 * <p>It used to be a question about gaps: of every consecutive pair, is the distance between them a
 * whole number of game ticks. That is the right test for whether a build can be laid, and the wrong
 * list of notes to hand anyone, because one stray note spoils two gaps -- the one into it and the
 * one out of it -- so the note standing exactly on a line after a stray one was reported off the
 * grid alongside the stray.</p>
 *
 * <p>Asking instead how far each note stands from the first one is the same question about the
 * build and a different answer about the notes.</p>
 */
class OffGridBlameTest {
	/** 150 BPM at 480 PPQ: a repeater tick is 120 composer ticks and a game tick is 60. */
	private static final long GAME_TICK = 60L;

	/**
	 * One note in the wrong place is one note in the wrong place.
	 *
	 * <p>The note after it is on a line, is placeable, and needs nothing done to it. Naming it made
	 * the selection a list of things to fix with things that were already right mixed in.</p>
	 */
	@Test
	void aStrayNoteDoesNotTakeItsNeighbourDownWithIt() {
		long stray = 4L * GAME_TICK + 25L;
		ComposerProject song = at(0L, 2L * GAME_TICK, stray, 6L * GAME_TICK, 8L * GAME_TICK);

		Set<Long> flagged = SongAnalysis.of(song, true, true).offGridNotes();
		assertEquals(Set.of(stray), flagged,
			"only the note that is not on a game tick, and it was " + flagged);
	}

	/**
	 * A passage shifted bodily off the beat is as buildable as it ever was.
	 *
	 * <p>The reason the old test measured gaps rather than positions: where a song sits relative to
	 * tick zero has nothing to do with whether a chain of repeaters can play it. Measuring from the
	 * song's own first note rather than from zero keeps that true.</p>
	 */
	@Test
	void aSongThatStartsBetweenTheTicksIsStillOnItsOwnGrid() {
		ComposerProject shifted = at(37L, 37L + 2L * GAME_TICK, 37L + 5L * GAME_TICK,
			37L + 6L * GAME_TICK);

		assertTrue(SongAnalysis.of(shifted, true, true).offGridNotes().isEmpty(),
			"every note is a whole number of game ticks from the first, which is all a build asks");
		assertTrue(SongAnalysis.of(shifted, true, true).buildable());
	}

	/** And a song that is genuinely scattered still reports every note that is out of place. */
	@Test
	void everyNoteOffTheGridIsStillNamed() {
		ComposerProject scattered = at(0L, 31L, 90L, 155L, 3L * GAME_TICK);

		Set<Long> flagged = SongAnalysis.of(scattered, true, true).offGridNotes();
		assertEquals(Set.of(31L, 90L, 155L), flagged, "the three that stand between ticks");
		assertFalse(SongAnalysis.of(scattered, true, true).buildable());
	}

	/**
	 * The tolerance is four hundredths of a game tick, which at this tempo is under three composer
	 * ticks, and a note inside it is left alone.
	 *
	 * <p>Worth pinning: a note two composer ticks off a line is not a fault, because rounding it
	 * into place costs a two-tick shift nobody can hear. The point at which it becomes one is a
	 * number, and a number that moves quietly would change what every song is judged to be.</p>
	 */
	@Test
	void aNoteJustOffALineIsNotOffTheGrid() {
		ComposerProject nearly = at(0L, 2L * GAME_TICK, 4L * GAME_TICK + 2L);
		assertTrue(SongAnalysis.of(nearly, true, true).offGridNotes().isEmpty(),
			"two composer ticks is inside the tolerance");

		ComposerProject past = at(0L, 2L * GAME_TICK, 4L * GAME_TICK + 3L);
		assertEquals(Set.of(4L * GAME_TICK + 3L), SongAnalysis.of(past, true, true).offGridNotes(),
			"three is outside it");
	}

	/**
	 * The verdict is unchanged where it matters: on the grid means buildable, off it does not.
	 *
	 * <p>Checked against the gap test's own answer rather than restated, so this fails if the two
	 * ever disagree about whether a song is clean.</p>
	 */
	@Test
	void theCleanVerdictAgreesWithTheGapItReplaced() {
		ComposerProject clean = at(0L, 2L * GAME_TICK, 4L * GAME_TICK, 10L * GAME_TICK);
		assertTrue(SongAnalysis.of(clean, true, true).offGridNotes().isEmpty());
		assertTrue(everyGapWhole(clean));

		ComposerProject dirty = at(0L, 2L * GAME_TICK, 4L * GAME_TICK + 19L);
		assertFalse(SongAnalysis.of(dirty, true, true).offGridNotes().isEmpty());
		assertFalse(everyGapWhole(dirty));
	}

	/** The test that used to be in the analysis, kept here as the thing being agreed with. */
	private static boolean everyGapWhole(ComposerProject song) {
		double span = SongAnalysis.redstoneTickSpan(song) / 2.0;
		List<Long> ticks = song.layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
		for (int index = 1; index < ticks.size(); index++) {
			double gap = (ticks.get(index) - ticks.get(index - 1)) / span;
			if (Math.abs(gap - Math.round(gap)) > 0.04) {
				return false;
			}
		}
		return true;
	}

	/** One layer, one note on each of the given ticks, at 150 BPM and 480 PPQ. */
	private static ComposerProject at(long... ticks) {
		List<NoteEvent> notes = new ArrayList<>();
		for (int index = 0; index < ticks.length; index++) {
			notes.add(new NoteEvent(index + 1L, 60, ticks[index], 30L, 100));
		}
		long last = ticks[ticks.length - 1];
		return new ComposerProject("blame", 480, 400_000,
			List.of(new Layer("One", "HARP", false, true, true, notes)), 0, 100L, last, 4);
	}
}
