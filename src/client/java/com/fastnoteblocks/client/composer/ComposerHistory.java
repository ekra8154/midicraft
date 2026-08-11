package com.fastnoteblocks.client.composer;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ComposerHistory {
	private static final int MAX_HISTORY = 100;
	/** What a step is called when whoever recorded it did not say. */
	public static final String UNNAMED_STEP = "edit";
	/**
	 * One recorded state, and the name of the edit that led away from it.
	 *
	 * <p>The name travels with the state it undoes <em>to</em> rather than with the state it
	 * produced, because that is the pairing both stacks need: the top of the undo stack is where
	 * Ctrl+Z would land and its label is what Ctrl+Z would take back.</p>
	 */
	private record Step(ComposerProject project, String label) {
	}

	private final Deque<Step> undo = new ArrayDeque<>();
	private final Deque<Step> redo = new ArrayDeque<>();
	private ComposerProject current;

	public ComposerHistory(ComposerProject initial) {
		current = initial;
	}

	public ComposerProject current() {
		return current;
	}

	public void apply(ComposerProject next) {
		apply(UNNAMED_STEP, next);
	}

	/**
	 * Records a step under a name, so undo can say what it is about to take back.
	 *
	 * @param label a short verb phrase for what this edit did, as it would read after "Undo"
	 */
	public void apply(String label, ComposerProject next) {
		if (next == null || next.equals(current)) {
			return;
		}
		undo.addLast(new Step(current, label == null || label.isBlank() ? UNNAMED_STEP : label));
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

	/** What Ctrl+Z would take back, or null when there is nothing to take back. */
	public String undoLabel() {
		return undo.isEmpty() ? null : undo.peekLast().label();
	}

	/** What Ctrl+Y would put back, or null when there is nothing to put back. */
	public String redoLabel() {
		return redo.isEmpty() ? null : redo.peekLast().label();
	}

	public ComposerProject undo() {
		if (!undo.isEmpty()) {
			Step step = undo.removeLast();
			redo.addLast(new Step(current, step.label()));
			current = step.project();
		}
		return current;
	}

	public ComposerProject redo() {
		if (!redo.isEmpty()) {
			Step step = redo.removeLast();
			undo.addLast(new Step(current, step.label()));
			current = step.project();
		}
		return current;
	}
}
