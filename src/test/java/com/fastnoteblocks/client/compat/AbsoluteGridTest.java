package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.SongAnalysis;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The redstone grids are positions in real time, and the song's tick axis is whole numbers.
 *
 * <p>Those two only agree when the tempo makes a repeater tick a whole number of composer ticks,
 * which is what conversion arranges and what a raw import has no reason to do. Everything here is
 * about the gap: how far the drawn grid may stray from the real one, and what the composer is
 * allowed to claim about a song because of it.</p>
 */
class AbsoluteGridTest {
	/** 128 BPM at 480 PPQ: one repeater tick is 102.4 composer ticks, which is the awkward case. */
	private static final double AWKWARD_SPAN = 480 * 100_000.0 / 468_750.0;

	/**
	 * The error on a grid line never grows, however far into the song it is.
	 *
	 * <p>The bug this replaces: the span was rounded to a whole number of composer ticks once and
	 * then multiplied out, so every line inherited the rounding and the error accumulated. Three
	 * minutes into a 128 BPM song the line calling itself a repeater tick was seven repeater ticks
	 * away from one. Rounding each line from its own index instead holds all of them inside half a
	 * composer tick forever.</p>
	 */
	@Test
	void gridLinesDoNotDriftFromTheirTruePositions() {
		double worst = 0.0;
		for (long index = 0; index <= 2000L; index++) {
			double truth = index * AWKWARD_SPAN;
			worst = Math.max(worst, Math.abs(ComposerScreen.gridLineAt(index, AWKWARD_SPAN) - truth));
		}
		assertTrue(worst <= 0.5, "a line strayed " + worst + " composer ticks from where it belongs");

		// What the old arithmetic did, for the contrast the number is worth reading against.
		long rounded = Math.round(AWKWARD_SPAN);
		double drift = Math.abs(2000L * rounded - 2000L * AWKWARD_SPAN);
		assertTrue(drift > 700.0,
			"the step-based grid should be badly adrift by line 2000, it was " + drift);
	}

	/** A tick snapped to the grid lands on a line that is actually drawn, not near one. */
	@Test
	void snappingLandsExactlyOnADrawnLine() {
		for (long tick = 0L; tick <= 5000L; tick += 7L) {
			long index = ComposerScreen.gridIndexNear(tick, AWKWARD_SPAN);
			long snapped = ComposerScreen.gridLineAt(index, AWKWARD_SPAN);
			assertEquals(snapped, ComposerScreen.gridLineAt(index, AWKWARD_SPAN),
				"the snap target and the drawn line are the same arithmetic");
			assertTrue(Math.abs(snapped - tick) <= AWKWARD_SPAN / 2.0 + 0.5,
				"tick " + tick + " snapped to " + snapped + ", further than half a step");
		}
	}

	/** Landing inside a cell takes the line at or before the tick, never the one after it. */
	@Test
	void theCellATickIsInsideStartsAtOrBeforeIt() {
		for (long tick = 0L; tick <= 5000L; tick += 13L) {
			long start = ComposerScreen.gridLineAt(
				ComposerScreen.gridIndexInside(tick, AWKWARD_SPAN), AWKWARD_SPAN);
			assertTrue(start <= tick, "cell start " + start + " was after the tick " + tick);
			assertTrue(tick - start < AWKWARD_SPAN + 1.0,
				"cell start " + start + " was more than a step before " + tick);
		}
	}

	/** An exact span is still exact: the whole-number case must not be disturbed by any of this. */
	@Test
	void anAlignedSongKeepsExactLines() {
		for (long index = 0L; index <= 500L; index++) {
			assertEquals(index * 120L, ComposerScreen.gridLineAt(index, 120.0));
		}
	}

	/**
	 * A song whose notes fall between repeater ticks is not buildable on a mode with one lane.
	 *
	 * <p>The verdict used to be computed for two lanes whatever the paste mode was, so a half-ticked
	 * song read MINECRAFT READY and then had every one of those notes rounded to the next whole
	 * repeater tick on the way into the world.</p>
	 */
	@Test
	void halfTickedNotesAreAFaultOnlyWhereThereIsNoSecondLane() {
		// 150 BPM at 480 PPQ: a repeater tick is 120 composer ticks, so 60 is a game tick.
		ComposerProject halfTicked = song(60L);
		ComposerProject onTicks = song(120L);

		assertTrue(SongAnalysis.of(halfTicked, true, true).buildable(),
			"two lanes reach between the repeater ticks, which is what they are for");
		assertFalse(SongAnalysis.of(halfTicked, true, false).buildable(),
			"one lane cannot, so the song is not ready for a build made of one");
		assertEquals(0, SongAnalysis.of(halfTicked, true, true).unreachableHalfTicks().size());
		assertFalse(SongAnalysis.of(halfTicked, true, false).unreachableHalfTicks().isEmpty(),
			"and the notes it cannot place are nameable, so Select can find them");

		assertTrue(SongAnalysis.of(onTicks, true, false).buildable(),
			"a song on whole repeater ticks is ready for either");
		assertTrue(SongAnalysis.of(onTicks, true, true).buildable());
	}

	/** The old two-argument form still judges as it did, so nothing that used it changed meaning. */
	@Test
	void theOlderCallStillAssumesTwoLanes() {
		ComposerProject halfTicked = song(60L);
		assertEquals(SongAnalysis.of(halfTicked, true, true).buildable(),
			SongAnalysis.of(halfTicked, true).buildable());
	}

	/** Notes every {@code step} composer ticks, at 150 BPM and 480 PPQ. */
	private static ComposerProject song(long step) {
		List<NoteEvent> notes = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			notes.add(new NoteEvent(index + 1L, 60, index * step, 30L, 100));
		}
		return new ComposerProject("grid", 480, 400_000,
			List.of(new Layer("One", "HARP", false, true, true, notes)), 0, 100L, 11 * step, 4);
	}
}
