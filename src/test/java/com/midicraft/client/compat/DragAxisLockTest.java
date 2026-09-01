package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.midicraft.client.compat.ComposerScreen.DragAxis;
import org.junit.jupiter.api.Test;

/**
 * Holding Shift while dragging notes pins the drag to one axis.
 *
 * <p>The rule is small and the whole of it is in when it commits and to what, which is what makes
 * it worth a test rather than a read: a lock that re-decides is worse than no lock, because it
 * changes its mind at the end of a long drag when the hand relaxes.</p>
 */
class DragAxisLockTest {

	@Test
	void saysNothingUntilTheCursorHasActuallyGoneSomewhere() {
		assertEquals(DragAxis.UNDECIDED, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 0.0, 0.0));
		assertEquals(DragAxis.UNDECIDED, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 4.0, 3.0),
			"four pixels is a hand resting on a mouse, not a gesture");
	}

	@Test
	void commitsToWhicheverWayTheCursorWentFurther() {
		assertEquals(DragAxis.TIME, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 20.0, 3.0));
		assertEquals(DragAxis.PITCH, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 3.0, 20.0));
	}

	/** Crossing the threshold on either axis is enough to ask the question. */
	@Test
	void commitsOnTravelInEitherDirection() {
		assertEquals(DragAxis.PITCH, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 0.0, 5.0));
		assertEquals(DragAxis.TIME, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 5.0, 0.0));
	}

	@Test
	void breaksATieTowardsTime() {
		assertEquals(DragAxis.TIME, ComposerScreen.lockedAxis(DragAxis.UNDECIDED, 9.0, 9.0),
			"retiming is what a drag is usually for");
	}

	/**
	 * Once it has decided it does not decide again, however far the drag wanders afterwards.
	 *
	 * <p>The case this exists for: a long drag across the roll that drifts downward at the end, as
	 * every long drag does. Re-deciding would turn the last stretch of a retiming into a
	 * transposition.</p>
	 */
	@Test
	void keepsItsAnswerForTheRestOfTheDrag() {
		assertEquals(DragAxis.TIME, ComposerScreen.lockedAxis(DragAxis.TIME, 2.0, 400.0));
		assertEquals(DragAxis.PITCH, ComposerScreen.lockedAxis(DragAxis.PITCH, 400.0, 2.0));
		assertEquals(DragAxis.TIME, ComposerScreen.lockedAxis(DragAxis.TIME, 0.0, 0.0),
			"including when the cursor comes back to where it started");
	}
}
