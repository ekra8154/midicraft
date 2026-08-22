package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where a phrase lands, and where the cursor goes afterwards.
 *
 * <p>A copy remembers two things: the notes, and where the cursor stood over them. A paste puts the
 * notes back at the same distances from wherever the cursor is now, which is the only way the
 * silence at the edges of a passage survives -- a set of notes begins on its first note and ends on
 * its last, so a run-up before the phrase or a tail after it exists nowhere else.</p>
 *
 * <p>The cursor then goes to the far bound of the block the cursor and the notes make together,
 * except from inside the phrase, where it goes to the end of the notes. That is what makes holding
 * Ctrl+V lay a passage down rather than a heap.</p>
 */
class CursorRelativePasteTest {
	/** The rule under test, in the same terms {@code ComposerScreen.cursorRelativeStep} states it. */
	private static long step(long cursor, long first, long last, long grid) {
		long extent = cursor > last ? cursor - first : last - cursor;
		return extent > 0L ? extent : Math.max(1L, grid);
	}

	/**
	 * Presses Ctrl+V {@code times} over, and returns the start tick of every note laid down.
	 *
	 * <p>The notes are held as offsets from the first of them, the way the clipboard holds them, and
	 * the base each paste lands on is the cursor plus the distance the first note stood from it.</p>
	 */
	private static List<Long> repeatedPastes(long[] notes, long copyCursor, long from, int times,
			long grid) {
		long first = notes[0];
		long last = notes[notes.length - 1];
		long offsetToFirst = first - copyCursor;
		long stride = step(copyCursor, first, last, grid);
		List<Long> laid = new ArrayList<>();
		long cursor = from;
		for (int press = 0; press < times; press++) {
			long base = Math.max(0L, cursor + offsetToFirst);
			for (long note : notes) {
				laid.add(base + (note - first));
			}
			cursor += stride;
		}
		return laid;
	}

	/**
	 * Cursor before the phrase: the run-up is kept, and repeats keep it between the copies.
	 *
	 * <p>Four notes a beat apart starting a beat after the cursor. Every copy should sit a beat
	 * after the one before it ends, which is the run-up doing its job.</p>
	 */
	@Test
	void aRunUpBeforeThePhraseIsKeptAndRepeats() {
		long[] notes = { 480L, 960L, 1440L, 1920L };
		List<Long> laid = repeatedPastes(notes, 0L, 0L, 3, 120L);

		assertEquals(List.of(480L, 960L, 1440L, 1920L,
			2400L, 2880L, 3360L, 3840L,
			4320L, 4800L, 5280L, 5760L), laid,
			"three copies of a four-note phrase, evenly spaced, run-up and all");
		assertEvenlySpaced(laid, 480L);
	}

	/**
	 * Cursor after the phrase: the tail of silence is kept, and repeats keep it between the copies.
	 *
	 * <p>The notes land behind the cursor and the cursor clears the tail, so the same even spacing
	 * comes out the other way round.</p>
	 */
	@Test
	void aTailAfterThePhraseIsKeptAndRepeats() {
		long[] notes = { 0L, 480L, 960L, 1440L };
		long copyCursor = 1920L;
		List<Long> laid = repeatedPastes(notes, copyCursor, copyCursor, 3, 120L);

		assertEquals(List.of(0L, 480L, 960L, 1440L,
			1920L, 2400L, 2880L, 3360L,
			3840L, 4320L, 4800L, 5280L), laid,
			"the notes land behind the cursor, and the tail separates the copies");
		assertEvenlySpaced(laid, 480L);
	}

	/** From inside the phrase the cursor goes to the end of the notes, and copies overlap. */
	@Test
	void fromInsideThePhraseTheCopiesOverlap() {
		long[] notes = { 0L, 480L, 960L, 1440L };
		long copyCursor = 960L;

		assertEquals(480L, step(copyCursor, 0L, 1440L, 120L),
			"to the last note, not to the far bound");

		List<Long> laid = repeatedPastes(notes, copyCursor, copyCursor, 2, 120L);
		assertEquals(List.of(0L, 480L, 960L, 1440L, 480L, 960L, 1440L, 1920L), laid,
			"the second copy lands partly behind the cursor and partly ahead of it");
	}

	/** A copy pasted where it was copied from lands exactly on itself. */
	@Test
	void pastingWithoutMovingTheCursorLandsOnTheOriginal() {
		long[] notes = { 300L, 700L, 1100L };
		List<Long> laid = repeatedPastes(notes, 512L, 512L, 1, 120L);
		assertEquals(List.of(300L, 700L, 1100L), laid,
			"the offsets are exact, so nothing moves when the cursor has not");
	}

	/** Off the grid entirely, and it still tiles: nothing here rounds anything. */
	@Test
	void anOffGridPhraseStillTilesExactly() {
		long[] notes = { 137L, 519L, 802L };
		List<Long> laid = repeatedPastes(notes, 11L, 11L, 3, 120L);
		long stride = step(11L, 137L, 802L, 120L);

		assertEquals(791L, stride);
		for (int copy = 1; copy < 3; copy++) {
			for (int note = 0; note < notes.length; note++) {
				assertEquals(laid.get(note) + copy * stride, laid.get(copy * notes.length + note),
					"every copy is one exact stride on from the last");
			}
		}
	}

	/**
	 * A single chord with the cursor on it has no extent, so the step falls back to a grid line.
	 *
	 * <p>Without the floor, holding Ctrl+V stacks copies in one place for as long as the key is
	 * down and the composition quietly grows by nothing anyone can see.</p>
	 */
	@Test
	void aChordUnderTheCursorStepsByOneGridLine() {
		assertEquals(120L, step(600L, 600L, 600L, 120L));
		assertEquals(1L, step(600L, 600L, 600L, 0L), "and never by nothing at all");
	}

	private static void assertEvenlySpaced(List<Long> laid, long expected) {
		for (int index = 1; index < laid.size(); index++) {
			long gap = laid.get(index) - laid.get(index - 1);
			assertTrue(gap == expected,
				"gap " + index + " was " + gap + ", not " + expected + ": " + laid);
		}
	}
}
