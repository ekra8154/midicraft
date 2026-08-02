package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The repeater grid the menu prints has to be the one the operation snaps to.
 *
 * <p>It was not, and the two disagreeing is worse than showing nothing: the menu quoted the width
 * of a single repeater tick, which made a song's grid look like a plain 1/8 when the operation was
 * about to move every note onto something else entirely. Quantizing to 1/8 and then to repeater
 * ticks visibly moved notes that the menu said were already there.</p>
 */
class RepeaterGridTest {
	private static long nextId = 1L;

	/** Quantizing to the printed grid must leave the repeater quantize with nothing left to do. */
	@Test
	void thePrintedGridIsTheGridItSnapsTo() {
		for (int ppq : new int[] {384, 480}) {
			for (int tempo : new int[] {100_000, 125_000, 200_000, 300_000, 400_000, 500_000, 800_000}) {
				for (int speed : new int[] {1, 3, 4, 5, 6, 8, 16}) {
					ComposerProject song = songOf(ppq, tempo, speed);
					long grid = song.repeaterGridTicks();
					assertTrue(grid >= 1, "a grid must be at least a tick");

					ComposerProject byHand = song.withQuantized((int)grid, Set.of());
					ComposerProject byOperation = song.withQuantizedToRepeaters(Set.of()).project();
					assertEquals(startTicks(byOperation), startTicks(byHand), String.format(
						"ppq %d, tempo %d, speed %d: the printed grid of %d put notes somewhere "
							+ "other than the operation did", ppq, tempo, speed, grid));
				}
			}
		}
	}

	/**
	 * The grid is routinely not a note value, which is the whole reason it is ruled off on its own.
	 *
	 * <p>Named songs from the library rather than invented numbers, because the claim being made is
	 * about real compositions and not about arithmetic in the abstract.</p>
	 */
	@Test
	void theGridIsNotUsuallyANoteValue() {
		// gangsta's paradise: finer than a 1/16.
		assertEquals(60L, songOf(480, 800_000, 4).repeaterGridTicks());
		// anybody-can-find-love: between a 1/16 and a 1/8, which no note value names.
		assertEquals(144L, songOf(384, 400_000, 6).repeaterGridTicks());
		// hammer of justice: a whole quarter note, the coarsest row in the menu.
		assertEquals(480L, songOf(480, 100_000, 4).repeaterGridTicks());
		// deltarune guardian: this one does land on a 1/16, by coincidence.
		assertEquals(120L, songOf(480, 400_000, 4).repeaterGridTicks());

		assertNotEquals(240L, songOf(480, 300_000, 4).repeaterGridTicks(),
			"i-wonder's grid is 160 and nothing like an eighth note");
	}

	/** The speed slider moves it, so nothing about it can be baked in. */
	@Test
	void theSpeedSliderMovesTheGrid() {
		assertEquals(120L, songOf(480, 400_000, 4).repeaterGridTicks());
		assertEquals(240L, songOf(480, 400_000, 8).repeaterGridTicks());
		assertEquals(480L, songOf(480, 400_000, 16).repeaterGridTicks());
	}

	private static List<Long> startTicks(ComposerProject song) {
		List<Long> ticks = new ArrayList<>();
		for (Layer layer : song.layers()) {
			for (NoteEvent note : layer.notes()) {
				ticks.add(note.startTick());
			}
		}
		return ticks;
	}

	private static ComposerProject songOf(int ppq, int tempo, int speedQuarters) {
		List<NoteEvent> notes = new ArrayList<>();
		// Deliberately off any grid: primes and near misses, so a wrong grid moves them somewhere
		// a right one would not.
		for (long tick : new long[] {0, 37, 101, 199, 250, 373, 480, 601, 719, 960, 1103, 1441}) {
			notes.add(new NoteEvent(nextId++, 60, tick, 1L, 100));
		}
		return new ComposerProject("grid", ppq, tempo,
			List.of(new Layer("Harp", "HARP", false, true, true, notes)), 0, nextId + 1000L,
			2000L, speedQuarters);
	}
}
