package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A click lands in the cell it was made in, and a marker lands on the line it was aimed at.
 *
 * <p>These are two different questions and the composer used to answer both with the nearest grid
 * line. Drawing a note is pointing at a cell -- the same act as pointing at a row, which has always
 * floored -- so a click on the right-hand half of a cell put the note in the cell after it. That
 * reads as the editor being imprecise rather than as a rule, and it is the kind of fault that is
 * hard to report and easy to reintroduce, since both readings look reasonable in the source.</p>
 */
class GridSnapTest {
	private static final long GRID = 120L;

	/** Anywhere inside a cell is that cell, including the very last tick of it. */
	@Test
	void everyTickInACellBelongsToThatCell() {
		for (long cell = 0L; cell < 5L; cell++) {
			long start = cell * GRID;
			for (long offset = 0L; offset < GRID; offset++) {
				assertEquals(start, ComposerScreen.gridCellStart(start + offset, GRID),
					"tick " + (start + offset) + " is inside the cell starting at " + start);
			}
		}
	}

	/** The fault this replaced, kept as a statement of what the two rules actually differ by. */
	@Test
	void theNearestLineLeavesTheCellHalfwayThrough() {
		assertEquals(0L, ComposerScreen.nearestGridLine(59L, GRID));
		assertEquals(GRID, ComposerScreen.nearestGridLine(60L, GRID),
			"the halfway tick already rounds forward, which is where the note used to jump");
		assertEquals(0L, ComposerScreen.gridCellStart(59L, GRID));
		assertEquals(0L, ComposerScreen.gridCellStart(60L, GRID), "but it is still in the first cell");
		assertEquals(0L, ComposerScreen.gridCellStart(GRID - 1L, GRID), "and so is the last tick");
	}

	/** Neither may hand back a negative tick; there is no roll to the left of zero. */
	@Test
	void nothingLandsBeforeTheStartOfTheSong() {
		assertEquals(0L, ComposerScreen.gridCellStart(-1L, GRID));
		assertEquals(0L, ComposerScreen.gridCellStart(-10_000L, GRID));
		assertEquals(0L, ComposerScreen.nearestGridLine(-10_000L, GRID));
	}

	/** Snap off is a grid of one tick, where both rules have to agree and nothing may shift. */
	@Test
	void aGridOfOneTickMovesNothing() {
		for (long tick = 0L; tick < 500L; tick += 37L) {
			assertEquals(tick, ComposerScreen.gridCellStart(tick, 1L));
			assertEquals(tick, ComposerScreen.nearestGridLine(tick, 1L));
		}
	}
}
