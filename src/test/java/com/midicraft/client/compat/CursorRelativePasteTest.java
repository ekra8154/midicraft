package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where a phrase lands, and where the cursor goes afterwards.
 *
 * <p>The cursor and the notes make a block with a bound at each end: the nearer of the cursor and
 * the first note, and the further of the cursor and the last note. Whichever end the cursor is at,
 * the silence between it and the notes is inside the block. A paste lays the whole block down
 * starting at the cursor, and the cursor comes to rest on its far bound.</p>
 *
 * <p>The subtlety worth having tests for is what a bound made of a note is worth. Two positions
 * {@code n} apart are a run of {@code n} only if the far end is empty; a note standing on it takes a
 * slot of its own, so the block is a slot longer than the distance across it.</p>
 */
class CursorRelativePasteTest {
	private static final long BEAT = 480L;

	/** The passage's own resolution: the tightest gap between two of its starts. */
	private static long unitOf(long[] notes, long grid) {
		long step = Long.MAX_VALUE;
		for (int index = 1; index < notes.length; index++) {
			step = Math.min(step, notes[index] - notes[index - 1]);
		}
		return step == Long.MAX_VALUE || step <= 0L ? Math.max(1L, grid) : step;
	}

	/** {@code ComposerScreen.blockStep}, restated. */
	private static long step(long cursor, long first, long last, long unit) {
		long lo = Math.min(cursor, first);
		long hi = Math.max(cursor, last);
		return Math.max(1L, hi - lo + (hi == last ? unit : 0L));
	}

	/** {@code ComposerScreen.blockLeadIn}, restated. */
	private static long leadIn(long cursor, long first) {
		return Math.max(0L, first - cursor);
	}

	/** Presses Ctrl+V {@code times} over, and returns the start tick of every note laid down. */
	private static List<Long> repeatedPastes(long[] notes, long copyCursor, long from, int times,
			long grid) {
		long first = notes[0];
		long lead = leadIn(copyCursor, first);
		long stride = step(copyCursor, first, notes[notes.length - 1], unitOf(notes, grid));
		List<Long> laid = new ArrayList<>();
		long cursor = from;
		for (int press = 0; press < times; press++) {
			long base = Math.max(0L, cursor + lead);
			for (long note : notes) {
				laid.add(base + (note - first));
			}
			cursor += stride;
		}
		return laid;
	}

	/**
	 * A bar of rest then three notes, tiled, stays a bar of rest then three notes.
	 *
	 * <p>This is the case that says why a block ending on a note is a step longer than the distance
	 * across it. The bound-to-bound distance here is three beats; the block is a bar. Tiled at three
	 * beats the rest is eaten and the result is an unbroken run of notes, one a beat, forever.</p>
	 */
	@Test
	void aBarOfRestAndThreeNotesTilesAsABar() {
		long[] notes = { BEAT, 2 * BEAT, 3 * BEAT };
		List<Long> laid = repeatedPastes(notes, 0L, 0L, 3, 120L);

		assertEquals(4 * BEAT, step(0L, BEAT, 3 * BEAT, BEAT), "a bar, not three beats");
		assertEquals(List.of(BEAT, 2 * BEAT, 3 * BEAT,
			5 * BEAT, 6 * BEAT, 7 * BEAT,
			9 * BEAT, 10 * BEAT, 11 * BEAT), laid,
			"three bars, each with its rest on the downbeat");
	}

	/** Cursor before the phrase: the run-up is kept in front, and the block starts at the cursor. */
	@Test
	void aRunUpBeforeThePhraseIsKeptInFront() {
		long[] notes = { 2 * BEAT, 3 * BEAT, 4 * BEAT };
		// Copied with the cursor a beat before the first note, then pasted somewhere else entirely.
		List<Long> laid = repeatedPastes(notes, BEAT, 100 * BEAT, 1, 120L);

		assertEquals(List.of(101 * BEAT, 102 * BEAT, 103 * BEAT), laid,
			"the beat of run-up survives the move");
	}

	/**
	 * Cursor after the phrase: the notes come forward so the first lands on the cursor, and the tail
	 * of silence follows them.
	 */
	@Test
	void aPhraseCopiedFromBehindComesForwardToTheCursor() {
		long[] notes = { 0L, BEAT, 2 * BEAT };
		long copyCursor = 4 * BEAT;
		List<Long> laid = repeatedPastes(notes, copyCursor, 10 * BEAT, 3, 120L);

		assertEquals(4 * BEAT, step(copyCursor, 0L, 2 * BEAT, BEAT),
			"first note to cursor, and no extra slot: the far bound is silence");
		assertEquals(List.of(10 * BEAT, 11 * BEAT, 12 * BEAT,
			14 * BEAT, 15 * BEAT, 16 * BEAT,
			18 * BEAT, 19 * BEAT, 20 * BEAT), laid,
			"the first note lands on the cursor and the two beats of tail separate the copies");
	}

	/** Cursor inside the phrase: the first note still lands on the cursor, and nothing overlaps. */
	@Test
	void aPhraseCopiedFromInsideAlsoComesForward() {
		long[] notes = { 0L, BEAT, 2 * BEAT, 3 * BEAT };
		long copyCursor = 2 * BEAT;
		List<Long> laid = repeatedPastes(notes, copyCursor, 10 * BEAT, 2, 120L);

		assertEquals(4 * BEAT, step(copyCursor, 0L, 3 * BEAT, BEAT),
			"the whole phrase, plus the slot its last note stands in");
		assertEquals(List.of(10 * BEAT, 11 * BEAT, 12 * BEAT, 13 * BEAT,
			14 * BEAT, 15 * BEAT, 16 * BEAT, 17 * BEAT), laid,
			"copies run on end to end with no note landing on another");
	}

	/** Whatever the cursor was, no note ever lands behind it. */
	@Test
	void nothingEverLandsBehindTheCursor() {
		long[] notes = { 3 * BEAT, 5 * BEAT, 6 * BEAT };
		for (long copyCursor : new long[] { 0L, 2 * BEAT, 3 * BEAT, 4 * BEAT, 6 * BEAT, 9 * BEAT }) {
			List<Long> laid = repeatedPastes(notes, copyCursor, 50 * BEAT, 1, 120L);
			assertTrue(laid.getFirst() >= 50 * BEAT,
				"cursor at " + copyCursor + " put a note at " + laid.getFirst());
		}
	}

	/** Off the grid entirely, and it still tiles: nothing here rounds anything. */
	@Test
	void anOffGridPhraseStillTilesExactly() {
		long[] notes = { 137L, 519L, 802L };
		List<Long> laid = repeatedPastes(notes, 11L, 11L, 3, 120L);
		long stride = step(11L, 137L, 802L, unitOf(notes, 120L));

		assertEquals(1074L, stride, "791 across the block, plus the 283 slot its last note holds");
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
	 * <p>Without it, holding Ctrl+V stacks copies in one place for as long as the key is down and
	 * the composition quietly grows by nothing anyone can see.</p>
	 */
	@Test
	void aChordUnderTheCursorStepsByOneGridLine() {
		long[] chord = { 600L };
		assertEquals(120L, step(600L, 600L, 600L, unitOf(chord, 120L)));
		assertEquals(1L, step(600L, 600L, 600L, unitOf(chord, 0L)), "and never by nothing at all");
	}
}
