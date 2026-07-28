package com.fastnoteblocks.client.composer;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ComposerHistory {
	private static final int MAX_HISTORY = 100;
	private final Deque<ComposerState> undo = new ArrayDeque<>();
	private final Deque<ComposerState> redo = new ArrayDeque<>();
	private ComposerState current;

	public ComposerHistory(ComposerState initial) {
		current = initial;
	}

	public ComposerState current() {
		return current;
	}

	public void apply(ComposerState next) {
		if (next == null || next.equals(current)) {
			return;
		}
		undo.addLast(current);
		while (undo.size() > MAX_HISTORY) {
			undo.removeFirst();
		}
		current = next;
		redo.clear();
	}

	/**
	 * Updates the current state without recording a step.
	 *
	 * <p>For changes that arrive as a stream while a control is dragged: the first one records a
	 * step so undo has somewhere to land, and the rest replace it, leaving one entry for the whole
	 * gesture instead of dozens that would evict real edits.</p>
	 */
	public void replaceCurrent(ComposerState next) {
		if (next != null) {
			current = next;
		}
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public ComposerState undo() {
		if (!undo.isEmpty()) {
			redo.addLast(current);
			current = undo.removeLast();
		}
		return current;
	}

	public ComposerState redo() {
		if (!redo.isEmpty()) {
			undo.addLast(current);
			current = redo.removeLast();
		}
		return current;
	}
}
