package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The collapsed note pass has to paint the same pixels as drawing every note did.
 *
 * <p>Both passes are rendered into a real ARGB buffer with real alpha blending — the warning
 * outlines are half transparent, so counting quads or comparing rectangles would not have caught a
 * difference in what actually reaches the screen. The naive pass here is written out longhand from
 * the code the screen used before {@link NoteCellGrid} existed, deliberately, so that it is an
 * independent answer and not the same routine called twice.</p>
 */
class NoteCellGridTest {
	private static final int ORIGIN_X = 244;
	private static final int WIDTH = 708;
	private static final int HEIGHT = 444;
	private static final int ORIGIN_Y = 72;
	private static final int NOTE_WIDTH = 7;
	private static final int NOTE_HEIGHT = 6;
	private static final int[] LAYER_COLORS = {
		0xFF35D7E5, 0xFFFFB347, 0xFF9BE564, 0xFFD19BFF, 0xFFFF6B9A,
		0xFF7CA7FF, 0xFFFFE66D, 0xFF8CE0C3, 0xFFFF8C5A, 0xFFC3F584
	};

	private record Note(int left, int top, int color, int flags, int midi) {
	}

	private static final int[] WARN_COLORS = {0xFFFF9A2E, 0x55FF9A2E, 0xFFFFE45C, 0x55FFE45C};

	/** A frame of pixels with the same src-over blending the GUI does. */
	private static final class Canvas implements NoteCellGrid.Quads {
		private final int[] pixels = new int[WIDTH * HEIGHT];
		/**
		 * Where a warning border was painted.
		 *
		 * <p>Welding a run of flagged notes deliberately moves those pixels — one border round the
		 * stretch rather than one per note — so they are the one thing the comparison excuses. Every
		 * other pixel still has to match exactly, which is what stops a welded border from shifting,
		 * recolouring or covering the note bodies underneath it.</p>
		 */
		private final boolean[] warned = new boolean[WIDTH * HEIGHT];
		private int quads;

		@Override
		public void fill(int left, int top, int right, int bottom, int color) {
			quads++;
			boolean warning = false;
			for (int warn : WARN_COLORS) {
				warning |= color == warn;
			}
			int alpha = color >>> 24;
			for (int y = Math.max(0, top - ORIGIN_Y); y < Math.min(HEIGHT, bottom - ORIGIN_Y); y++) {
				for (int x = Math.max(0, left - ORIGIN_X); x < Math.min(WIDTH, right - ORIGIN_X); x++) {
					pixels[y * WIDTH + x] = blend(pixels[y * WIDTH + x], color, alpha);
					warned[y * WIDTH + x] |= warning;
				}
			}
		}

		private static int blend(int under, int over, int alpha) {
			if (alpha == 0xFF) {
				return over;
			}
			int out = 0xFF000000;
			for (int shift = 0; shift <= 16; shift += 8) {
				int source = over >> shift & 0xFF;
				int destination = under >> shift & 0xFF;
				out |= (source * alpha + destination * (255 - alpha)) / 255 << shift;
			}
			return out;
		}
	}

	@Test
	void collapsingPaintsTheSamePixels() {
		// Zoom levels from "every note has its own column" down to "the whole song is on screen",
		// because collapsing only starts doing anything once notes share a pixel.
		for (double ticksPerPixel : new double[] {1.0, 8.0, 60.0, 360.0, 1160.0}) {
			for (long seed : new long[] {1L, 2L, 3L, 4L, 5L}) {
				// Odd seeds carry selections and warnings, even ones do not. A buildable song with
				// nothing selected is the ordinary case and the one the saving is measured on;
				// the messy frames are there to be drawn correctly, not quickly.
				boolean clean = seed % 2 == 0;
				List<Note> notes = randomFrame(seed, ticksPerPixel, clean);
				Canvas naive = new Canvas();
				drawEveryNote(notes, naive);
				Canvas collapsed = new Canvas();
				NoteCellGrid grid = new NoteCellGrid();
				grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
				for (Note note : notes) {
					grid.add(note.left(), note.top(), note.color(), note.flags(), note.midi());
				}
				int issued = grid.draw(collapsed);

				assertEquals(issued, collapsed.quads,
					"the grid must report the quads it actually issued");
				int differing = 0;
				for (int index = 0; index < naive.pixels.length; index++) {
					if (naive.warned[index] || collapsed.warned[index]) {
						continue;
					}
					if (naive.pixels[index] != collapsed.pixels[index]) {
						differing++;
					}
				}
				assertEquals(0, differing, String.format(Locale.ROOT,
					"%d pixels differ at %.0f ticks/px, seed %d", differing, ticksPerPixel, seed));
				assertTrue(collapsed.quads <= naive.quads, "collapsing must never add quads");
				if (clean && ticksPerPixel >= 360.0) {
					// Not just correctness: zoomed this far out the saving is the entire point, and
					// a pass that quietly stopped collapsing would still be pixel-perfect.
					assertTrue(collapsed.quads * 2 < naive.quads, String.format(Locale.ROOT,
						"%d quads from %d at %.0f ticks/px is not worth the pass",
						collapsed.quads, naive.quads, ticksPerPixel));
				}
			}
		}
	}

	/** Notes of one line, close enough together to touch, become one wide quad. */
	@Test
	void weldsTouchingNeighboursInARow() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		for (int index = 0; index < 20; index++) {
			grid.add(ORIGIN_X + index * 5, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		}
		Canvas canvas = new Canvas();
		assertEquals(1, grid.draw(canvas), "one run, one quad");
		assertEquals(0xFF35D7E5, canvas.pixels[0], "the run starts where the first note did");
		assertEquals(0xFF35D7E5, canvas.pixels[19 * 5 + NOTE_WIDTH - 1], "and ends where the last did");
		assertEquals(0, canvas.pixels[19 * 5 + NOTE_WIDTH], "and no further");
	}

	/** A stretch of off-grid notes takes one border round the stretch, not one per note. */
	@Test
	void weldsFlaggedNeighboursUnderOneBorder() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		for (int index = 0; index < 12; index++) {
			grid.add(ORIGIN_X + index * 5, ORIGIN_Y, 0xFF35D7E5,
				NoteCellGrid.OFF_GRID | NoteCellGrid.HIGHLIGHTED, 60);
		}
		Canvas canvas = new Canvas();
		assertEquals(5, grid.draw(canvas), "one body and four border edges");
		int right = 11 * 5 + NOTE_WIDTH;
		assertEquals(0xFFFFE45C, canvas.pixels[0], "the border starts at the run's left edge");
		assertEquals(0xFFFFE45C, canvas.pixels[right - 1], "and reaches its right edge");
		assertEquals(0xFF35D7E5, canvas.pixels[WIDTH + 4], "the note body survives inside it");
		assertEquals(0, canvas.pixels[right], "and nothing is painted past the last note");
	}

	/** Too frequent and off grid are different warnings, so they never share a border. */
	@Test
	void willNotWeldTwoKindsOfWarningTogether() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		grid.add(ORIGIN_X, ORIGIN_Y, 0xFF35D7E5, NoteCellGrid.CROWDED, 60);
		grid.add(ORIGIN_X + 3, ORIGIN_Y, 0xFF35D7E5, NoteCellGrid.OFF_GRID, 60);
		assertEquals(10, grid.draw(new Canvas()), "two runs of five");
	}

	/** A flagged note and a clean one are not the same thing and do not weld. */
	@Test
	void willNotWeldAFlaggedNoteToACleanOne() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		grid.add(ORIGIN_X, ORIGIN_Y, 0xFF35D7E5, NoteCellGrid.OFF_GRID, 60);
		grid.add(ORIGIN_X + 3, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		assertEquals(6, grid.draw(new Canvas()), "a bordered run and a bare one");
	}

	/** A gap wider than a note breaks the run rather than being painted over. */
	@Test
	void willNotWeldAcrossAGap() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		grid.add(ORIGIN_X, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		grid.add(ORIGIN_X + NOTE_WIDTH + 1, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		Canvas canvas = new Canvas();
		assertEquals(2, grid.draw(canvas));
		assertEquals(0, canvas.pixels[NOTE_WIDTH], "the gap stays empty");
	}

	/** A note of another colour landing between two of a run keeps its place on top of them. */
	@Test
	void willNotWeldPastANoteOfAnotherColour() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		grid.add(ORIGIN_X, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		grid.add(ORIGIN_X + 3, ORIGIN_Y, 0xFFFFB347, 0, 60);
		grid.add(ORIGIN_X + 6, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		Canvas canvas = new Canvas();
		assertEquals(3, grid.draw(canvas));
		assertEquals(0xFFFFB347, canvas.pixels[4], "the orange note is still visible under the run");
	}

	/** At the zoom the composer opens on, nothing shares a cell, so nothing is collapsed away. */
	@Test
	void collapsesNothingWhenNothingOverlaps() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		for (int index = 0; index < 50; index++) {
			grid.add(ORIGIN_X + index * 9, ORIGIN_Y + index % 20 * 8, 0xFF35D7E5, 0, 60 + index % 20);
		}
		assertEquals(50, grid.draw(new Canvas()));
	}

	/** A selected note buried under later ones still shows its halo. */
	@Test
	void carriesSelectionUpOutOfACoveredCell() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		grid.add(ORIGIN_X + 40, ORIGIN_Y + 40, 0xFF35D7E5, NoteCellGrid.SELECTED, 60);
		grid.add(ORIGIN_X + 40, ORIGIN_Y + 40, 0xFFFFB347, 0, 60);
		grid.add(ORIGIN_X + 40, ORIGIN_Y + 40, 0xFF9BE564, 0, 60);
		Canvas canvas = new Canvas();
		// The buried note's halo, then the surviving note's fill. Two of the five quads survive.
		assertEquals(2, grid.draw(canvas));
		assertEquals(0xFFFFFFFF, canvas.pixels[(40 - 1) * WIDTH + (40 - 1)], "the halo survived");
		assertEquals(0xFF9BE564, canvas.pixels[40 * WIDTH + 40], "the last note is the one on top");
	}

	/** Notes that hang off the roll's edges have no cell, and must still be drawn. */
	@Test
	void drawsNotesWithNoCellToStandOn() {
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
		grid.add(ORIGIN_X - 6, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		grid.add(ORIGIN_X - 6, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		grid.add(ORIGIN_X + WIDTH - 2, ORIGIN_Y, 0xFF35D7E5, 0, 60);
		// The two on the left edge share a cell inside the margin; the right-hand one stands alone.
		assertEquals(2, grid.draw(new Canvas()));
	}

	/** Two frames in a row must not see each other's cells. */
	@Test
	void emptiesItselfBetweenFrames() {
		NoteCellGrid grid = new NoteCellGrid();
		for (int frame = 0; frame < 3; frame++) {
			grid.begin(ORIGIN_X, WIDTH, NOTE_WIDTH, NOTE_HEIGHT);
			grid.add(ORIGIN_X + 40, ORIGIN_Y + 40, 0xFF35D7E5, 0, 60);
			grid.add(ORIGIN_X + 60, ORIGIN_Y + 40, 0xFF35D7E5, 0, 60);
			assertEquals(2, grid.draw(new Canvas()), "frame " + frame);
		}
	}

	/**
	 * A frame of notes at a given zoom, laid out the way a song lays them out.
	 *
	 * <p>Deliberately not uniform noise. Each layer is a line: a few pitches, played at a steady
	 * subdivision, in tick order. That is what makes notes share cells and sit next to each other,
	 * and a first version of this that scattered notes evenly across the roll exercised neither --
	 * it passed while collapsing almost nothing, which is a test of the generator, not the code.</p>
	 */
	private static List<Note> randomFrame(long seed, double ticksPerPixel, boolean clean) {
		Random random = new Random(seed);
		List<Note> notes = new ArrayList<>();
		int layers = 4 + random.nextInt(12);
		long span = (long)Math.ceil(WIDTH * ticksPerPixel);
		for (int layer = 0; layer < layers; layer++) {
			int color = LAYER_COLORS[layer % LAYER_COLORS.length];
			boolean highlighted = random.nextInt(4) == 0;
			int lowest = 48 + random.nextInt(24);
			int voices = 1 + random.nextInt(5);
			// A sixteenth at 480 ppq up to a bar of it, which spans the zoom range that matters.
			long step = 120L * (1 + random.nextInt(8));
			for (long tick = 0L; tick <= span; tick += step) {
				if (random.nextInt(6) == 0) {
					continue;
				}
				int midi = lowest + random.nextInt(voices) * 3;
				int left = ORIGIN_X + (int)Math.round(tick / ticksPerPixel);
				int top = ORIGIN_Y + (91 - midi) * 8 + 1;
				if (left + NOTE_WIDTH <= ORIGIN_X || left >= ORIGIN_X + WIDTH
						|| top + NOTE_HEIGHT <= ORIGIN_Y || top >= ORIGIN_Y + HEIGHT) {
					continue;
				}
				int flags = highlighted ? NoteCellGrid.HIGHLIGHTED : 0;
				if (!clean && random.nextInt(8) == 0) {
					flags |= NoteCellGrid.SELECTED;
				}
				int warning = clean ? -1 : random.nextInt(10);
				if (warning == 0) {
					flags |= NoteCellGrid.CROWDED;
				} else if (warning == 1) {
					flags |= NoteCellGrid.OFF_GRID;
				}
				boolean buildable = clean || random.nextInt(20) != 0;
				int drawn = buildable
					? (highlighted ? color : 0xFF777A80)
					: (highlighted ? 0xFFFF6B6B : 0xFF755050);
				notes.add(new Note(left, top, drawn, flags, midi));
			}
		}
		return notes;
	}

	/** Exactly what {@code extractNotes} did before the grid: every note, in order, no collapsing. */
	private static void drawEveryNote(List<Note> notes, Canvas canvas) {
		for (Note note : notes) {
			int left = note.left();
			int top = note.top();
			int right = left + NOTE_WIDTH;
			int bottom = top + NOTE_HEIGHT;
			if ((note.flags() & NoteCellGrid.SELECTED) != 0) {
				canvas.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFFFFFFFF);
			}
			canvas.fill(left, top, right, bottom, note.color());
			if ((note.flags() & (NoteCellGrid.CROWDED | NoteCellGrid.OFF_GRID)) == 0) {
				continue;
			}
			boolean highlighted = (note.flags() & NoteCellGrid.HIGHLIGHTED) != 0;
			int warn = (note.flags() & NoteCellGrid.CROWDED) != 0
				? (highlighted ? 0xFFFF9A2E : 0x55FF9A2E)
				: (highlighted ? 0xFFFFE45C : 0x55FFE45C);
			canvas.fill(left, top, right, top + 1, warn);
			canvas.fill(left, bottom - 1, right, bottom, warn);
			canvas.fill(left, top, left + 1, bottom, warn);
			canvas.fill(right - 1, top, right, bottom, warn);
		}
	}
}
