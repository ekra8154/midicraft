package com.fastnoteblocks.client.composer;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ComposerHistory {
	private static final int MAX_HISTORY = 100;
	private final Deque<ComposerProject> undo = new ArrayDeque<>();
	private final Deque<ComposerProject> redo = new ArrayDeque<>();
	private ComposerProject current;

	public ComposerHistory(ComposerProject initial) {
		current = initial;
	}

	public ComposerProject current() {
		return current;
	}

	public void apply(ComposerProject next) {
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
	public void replaceCurrent(ComposerProject next) {
		if (next != null) {
			current = next;
		}
	}

	/**
	 * Throws the history away and starts again from {@code project}.
	 *
	 * <p>For discarding edits, which is not a step backwards but a statement that the steps never
	 * happened. Leaving them on the undo stack would let Ctrl+Z bring back the very work the user
	 * just said to drop, and would leave the screen showing a composition the rest of the mod had
	 * already stopped believing in.</p>
	 */
	public void reset(ComposerProject project) {
		if (project == null) {
			return;
		}
		undo.clear();
		redo.clear();
		current = project;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public ComposerProject undo() {
		if (!undo.isEmpty()) {
			redo.addLast(current);
			current = undo.removeLast();
		}
		return current;
	}

	public ComposerProject redo() {
		if (!redo.isEmpty()) {
			undo.addLast(current);
			current = redo.removeLast();
		}
		return current;
	}
}
