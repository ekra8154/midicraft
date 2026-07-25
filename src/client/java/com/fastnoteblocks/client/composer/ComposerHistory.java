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
