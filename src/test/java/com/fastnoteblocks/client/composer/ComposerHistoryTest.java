package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

	/** A reset with nothing to reset to leaves what is there, rather than blanking the editor. */
	@Test
	void resetIgnoresNothing() {
		ComposerProject song = ComposerProject.empty("Song").addNote(0, 60, 0L, 120L);
		ComposerHistory history = new ComposerHistory(song);

		history.reset(null);

		assertEquals(song, history.current());
	}
}
