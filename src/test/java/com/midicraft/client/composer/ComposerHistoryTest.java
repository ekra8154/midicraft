package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ComposerHistoryTest {
	/**
	 * Discarding is not a step backwards; it is a statement that the steps never happened.
	 *
	 * <p>Leaving them on the undo stack would let Ctrl+Z bring back the very work the user just
	 * said to drop, and -- because the screen keeps asking whether the current composition differs
	 * from the saved one -- would go on asking about those same edits forever.</p>
	 */
	@Test
	void resetLeavesNothingToUndo() {
		ComposerProject saved = ComposerProject.empty("Song");
		ComposerHistory history = new ComposerHistory(saved);
		history.apply(saved.addNote(0, 60, 0L, 120L));
		history.apply(history.current().addNote(0, 62, 240L, 120L));
		assertEquals(2, history.current().noteCount(), "the edits should have landed");

		history.reset(saved);

		assertEquals(saved, history.current(), "reset should go back to what was handed in");
		assertFalse(history.canUndo(), "discarded edits should not be waiting on the undo stack");
		assertFalse(history.canRedo(), "nor on the redo stack");
	}

	/**
	 * Undo names the step it would take back, and the name follows the step across both stacks.
	 *
	 * <p>The screen reads the label <em>before</em> moving, and shows it in the menu row as well as
	 * in the toast afterwards, so getting the pairing wrong is not a cosmetic slip -- it would have
	 * Ctrl+Z announce the edit before the one it is actually about to reverse.</p>
	 */
	@Test
	void everyStepCarriesTheNameOfTheEditThatMadeIt() {
		ComposerProject song = ComposerProject.empty("Song");
		ComposerHistory history = new ComposerHistory(song);
		assertNull(history.undoLabel(), "a fresh history has nothing to take back");
		assertNull(history.redoLabel(), "nor anything to put back");

		history.apply("add note", song.addNote(0, 60, 0L, 120L));
		history.apply("add layer", history.current().addLayer());

		assertEquals("add layer", history.undoLabel(), "the top of the stack is the last edit");
		history.undo();
		assertEquals("add note", history.undoLabel(), "and the one under it is the one before");
		assertEquals("add layer", history.redoLabel(), "which is now what redo would put back");

		history.redo();
		assertEquals("add layer", history.undoLabel(), "a redo hands the name straight back");
		assertNull(history.redoLabel(), "with nothing left in front of it");
	}

	/** An unnamed step still has a name, because the menu row has to say something. */
	@Test
	void anUnnamedStepFallsBackRatherThanShowingNothing() {
		ComposerProject song = ComposerProject.empty("Song");
		ComposerHistory history = new ComposerHistory(song);

		history.apply(song.addNote(0, 60, 0L, 120L));
		history.apply("   ", history.current().addNote(0, 62, 240L, 120L));

		assertEquals(ComposerHistory.UNNAMED_STEP, history.undoLabel(), "blank is not a label");
		history.undo();
		assertEquals(ComposerHistory.UNNAMED_STEP, history.undoLabel(), "nor is no label at all");
	}

	/** Discarding takes the names with it, so a stale label cannot outlive its step. */
	@Test
	void resetForgetsTheLabelsToo() {
		ComposerProject saved = ComposerProject.empty("Song");
		ComposerHistory history = new ComposerHistory(saved);
		history.apply("add note", saved.addNote(0, 60, 0L, 120L));

		history.reset(saved);

		assertNull(history.undoLabel());
		assertNull(history.redoLabel());
	}

	/** A reset with nothing to reset to leaves what is there, rather than blanking the editor. */
	@Test
	void resetIgnoresNothing() {
		ComposerProject song = ComposerProject.empty("Song").addNote(0, 60, 0L, 120L);
		ComposerHistory history = new ComposerHistory(song);

		history.reset(null);

		assertEquals(song, history.current());
	}

	/**
	 * An edit that moved the time marker takes the marker back with it, and forward again.
	 *
	 * <p>A paste moves the marker as part of what it does -- that is what makes holding Ctrl+V lay a
	 * passage down -- so taking one back and leaving the marker four bars on takes back half of
	 * it.</p>
	 */
	@Test
	void anEditThatMovedTheMarkerRestoresIt() {
		ComposerProject song = ComposerProject.empty("Song");
		ComposerHistory history = new ComposerHistory(song);

		history.apply("paste 3 notes", song.addNote(0, 60, 1920L, 120L), 480L);

		assertEquals(480L, history.undoCursor(), "where the marker stood before the paste");
		history.undo(2400L);
		assertEquals(2400L, history.redoCursor(), "and where it stood after it");

		history.redo(480L);
		assertEquals(480L, history.undoCursor(), "which undo can take back again");
	}

	/**
	 * An edit that left the marker alone says nothing about where it should be.
	 *
	 * <p>Undoing a note deleted five minutes ago should not throw the marker back to wherever it
	 * stood at the time. You have been somewhere else since, and that is not part of what Ctrl+Z
	 * was asked to take back.</p>
	 */
	@Test
	void anOrdinaryEditLeavesTheMarkerWhereItIs() {
		ComposerProject song = ComposerProject.empty("Song");
		ComposerHistory history = new ComposerHistory(song);

		history.apply("add note", song.addNote(0, 60, 0L, 120L));

		assertEquals(ComposerHistory.NO_CURSOR, history.undoCursor());
		history.undo(9600L);
		assertEquals(ComposerHistory.NO_CURSOR, history.redoCursor(),
			"and the marker's present position is not smuggled onto the redo either");
	}
}
