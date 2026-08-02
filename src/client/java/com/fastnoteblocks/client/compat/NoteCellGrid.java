package com.fastnoteblocks.client.compat;

import java.util.Arrays;

/**
 * One frame of piano-roll notes, with the ones hidden behind other notes left out.
 *
 * <p>Zoomed all the way out, a dense composition puts thousands of notes on the same few hundred
 * pixel columns — nine thousand of them for one of this library's songs, each asking the GUI for a
 * quad of its own, each quad costing a matrix and a render-state object and a place in the frame's
 * element sort. The notes are opaque and all the same size, so where several land on one pixel cell
 * only the last one drawn is ever seen and every quad before it is waste. This keeps the last note
 * per cell and drops the rest, which on those songs is between half and two-thirds of them.</p>
 *
 * <p>The one thing a covered note still contributes is its selection halo: that is drawn a pixel
 * bigger than the note itself, so it survives being drawn over. A covered note therefore still
 * draws its halo, and draws it in the place in the order it always had. Not a detail — dropping it
 * would let a collapsed cell hide that something in it is selected, and moving it onto the
 * surviving note would put it over neighbours it used to sit under. Both were tried; the
 * pixel-for-pixel test caught the second.</p>
 *
 * <p>Kept alive between frames and emptied by walking only the cells the last frame touched, so a
 * frame allocates nothing: the whole point is to stop making an object per note per frame.</p>
 */
final class NoteCellGrid {
	static final int SELECTED = 1;
	static final int HIGHLIGHTED = 2;
	static final int CROWDED = 4;
	static final int OFF_GRID = 8;
	/** Set on a note once a later note has taken its cell. */
	private static final int COVERED = 16;
	/** Columns of slack either side of the roll, for the notes that hang over its edges. */
	private static final int MARGIN = 8;
	/** One row per MIDI note, which is every row the roll can ever show. */
	private static final int ROWS = 128;

	/** Where the surviving quads go. An interface so a test can count pixels instead of drawing. */
	interface Quads {
		void fill(int left, int top, int right, int bottom, int color);
	}

	/**
	 * Cell to note, one past the slot index so that zero can mean empty.
	 *
	 * <p>A plain array rather than a map: a cell is a pixel column and a MIDI note, both small
	 * integers, so the index is arithmetic with nothing hashed and nothing boxed.</p>
	 */
	private int[] slot = new int[0];
	private int columns;
	private int originX;
	private int noteWidth;
	private int noteHeight;
	private int[] left = new int[0];
	private int[] top = new int[0];
	private int[] color = new int[0];
	private int[] flags = new int[0];
	private int[] cell = new int[0];
	private int count;
	/** The run of welded notes currently open in each row, at most one per row. */
	private final boolean[] runOpen = new boolean[ROWS];
	private final int[] runLeft = new int[ROWS];
	private final int[] runRight = new int[ROWS];
	private final int[] runTop = new int[ROWS];
	private final int[] runColor = new int[ROWS];
	private final int[] runKind = new int[ROWS];
	private final boolean[] runHighlighted = new boolean[ROWS];

	/**
	 * Starts a frame.
	 *
	 * @param originX left edge of the roll, which columns are measured from
	 * @param rollWidth how wide the roll is, which decides how many columns there are
	 */
	void begin(int originX, int rollWidth, int noteWidth, int noteHeight) {
		int wanted = Math.max(1, rollWidth) + 2 * MARGIN;
		if (columns != wanted) {
			columns = wanted;
			slot = new int[wanted * ROWS];
		}
		this.originX = originX;
		this.noteWidth = noteWidth;
		this.noteHeight = noteHeight;
		count = 0;
		// draw closes every run before it returns, so this only matters if a frame was abandoned
		// part way through. Cheap enough that the grid need not depend on that never happening.
		Arrays.fill(runOpen, false);
	}

	/** Records one note, retiring whatever was already standing on its cell. */
	void add(int noteLeft, int noteTop, int noteColor, int noteFlags, int midi) {
		if (count == left.length) {
			int capacity = Math.max(1024, count * 2);
			left = Arrays.copyOf(left, capacity);
			top = Arrays.copyOf(top, capacity);
			color = Arrays.copyOf(color, capacity);
			flags = Arrays.copyOf(flags, capacity);
			cell = Arrays.copyOf(cell, capacity);
		}
		int column = noteLeft - originX + MARGIN;
		int index = column < 0 || column >= columns || midi < 0 || midi >= ROWS
			? -1
			: column * ROWS + midi;
		if (index >= 0) {
			int standing = slot[index];
			if (standing != 0) {
				flags[standing - 1] |= COVERED;
			}
			slot[index] = count + 1;
		}
		left[count] = noteLeft;
		top[count] = noteTop;
		color[count] = noteColor;
		flags[count] = noteFlags;
		cell[count] = index;
		count++;
	}

	/**
	 * Draws the surviving notes in the order they were added, and empties the grid.
	 *
	 * <p>Second saving on top of dropping the covered notes: neighbours in a row that touch or
	 * overlap and match are welded into one wide quad. Ten notes of the same line within a note's
	 * width of each other paint exactly the pixels one rectangle over them paints, and on a dense
	 * song at full zoom-out that is most of what is left — four thousand quads down to nine
	 * hundred. Matching means the same colour and the same warning, so a flagged run welds too and
	 * takes one border round the whole stretch instead of one per note; on a raw import, where
	 * almost every note is off grid, that is the difference between twenty-six thousand quads and a
	 * few thousand. What the welded border loses is the vertical edge between two adjacent bad
	 * notes, which at this zoom is inside a pixel. A selected note never welds: its halo is pinned
	 * to its own edges, so it draws alone.</p>
	 *
	 * <p>Rows never overlap — a note is two pixels shorter than its row, and a halo is one pixel
	 * proud on each side — so a run may stay open across other rows' quads and only has to be
	 * flushed when something else lands in <em>its</em> row. That is what makes one pass enough.</p>
	 *
	 * @return how many quads were issued, which is what the frame profiler reports
	 */
	int draw(Quads quads) {
		int issued = 0;
		for (int index = 0; index < count; index++) {
			int cellIndex = cell[index];
			if (cellIndex >= 0) {
				slot[cellIndex] = 0;
			}
			int noteFlags = flags[index];
			int noteLeft = left[index];
			int noteTop = top[index];
			int right = noteLeft + noteWidth;
			// A note off the roll's edge has no cell and so no row to weld along.
			int row = cellIndex < 0 ? -1 : cellIndex % ROWS;
			// The halo is drawn a pixel proud of the note, so unlike the note it is not covered by
			// whatever took the cell -- a covered note still draws this, and still draws it here,
			// where it was, so that anything painted between the two lands the same way round.
			if ((noteFlags & SELECTED) != 0) {
				issued += flush(quads, row);
				quads.fill(noteLeft - 1, noteTop - 1, right + 1, noteTop + noteHeight + 1, 0xFFFFFFFF);
				issued++;
			}
			if ((noteFlags & COVERED) != 0) {
				continue;
			}
			int kind = noteFlags & (CROWDED | OFF_GRID);
			boolean highlighted = (noteFlags & HIGHLIGHTED) != 0;
			// A selected note is the one thing that cannot weld: its halo is pinned to its own
			// edges, so it draws alone and leaves the row closed behind it.
			if (row >= 0 && (noteFlags & SELECTED) == 0) {
				if (runOpen[row] && runColor[row] == color[index] && runKind[row] == kind
						&& runHighlighted[row] == highlighted
						&& noteLeft >= runLeft[row] && noteLeft <= runRight[row]) {
					runRight[row] = Math.max(runRight[row], right);
					continue;
				}
				issued += flush(quads, row);
				runOpen[row] = true;
				runLeft[row] = noteLeft;
				runRight[row] = right;
				runTop[row] = noteTop;
				runColor[row] = color[index];
				runKind[row] = kind;
				runHighlighted[row] = highlighted;
				continue;
			}
			issued += flush(quads, row);
			issued += paint(quads, noteLeft, noteTop, right, color[index], kind, highlighted);
		}
		for (int row = 0; row < ROWS; row++) {
			issued += flush(quads, row);
		}
		count = 0;
		return issued;
	}

	/** Draws the run standing in a row, if there is one, and closes it. */
	private int flush(Quads quads, int row) {
		if (row < 0 || !runOpen[row]) {
			return 0;
		}
		runOpen[row] = false;
		return paint(quads, runLeft[row], runTop[row], runRight[row], runColor[row], runKind[row],
			runHighlighted[row]);
	}

	/**
	 * Draws one welded stretch: its body, and a warning border round the whole stretch if the notes
	 * in it were flagged.
	 *
	 * <p>Outline rather than recolour: a layer colour may itself be orange. Orange means
	 * arrives-too-soon-to-build; yellow means lands-between-ticks.</p>
	 */
	private int paint(Quads quads, int left, int top, int right, int color, int kind,
			boolean highlighted) {
		int bottom = top + noteHeight;
		if (kind == 0) {
			quads.fill(left, top, right, bottom, color);
			return 1;
		}
		int warn = (kind & CROWDED) != 0
			? (highlighted ? 0xFFFF9A2E : 0x55FF9A2E)
			: (highlighted ? 0xFFFFE45C : 0x55FFE45C);
		int issued = 0;
		// A border of four edges over a fill paints the same pixels as one warn rectangle with the
		// fill put back inside it -- and that is two quads rather than five. The body underneath is
		// still needed when the warning colour is half transparent, because the border's colour is
		// the warning blended over the note, not over the background behind it.
		if ((warn >>> 24) != 0xFF) {
			quads.fill(left, top, right, bottom, color);
			issued++;
		}
		quads.fill(left, top, right, bottom, warn);
		issued++;
		// Zoomed out vertically a row is four pixels and a note is two, so the border is the whole
		// note and there is no inside left to restore. That is the case this all has to be fast in.
		if (right - left > 2 && bottom - top > 2) {
			quads.fill(left + 1, top + 1, right - 1, bottom - 1, color);
			issued++;
		}
		return issued;
	}
}
