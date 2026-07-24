package com.fastnoteblocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class NoteSequenceTest {
	@Test
	void parsesUnifiedWhitespaceAgnosticSequence() {
		assertEquals(List.of(
			NoteSequence.Step.note(0),
			NoteSequence.Step.repeater(2),
			NoteSequence.Step.note(7),
			NoteSequence.Step.repeater(4)
		), NoteSequence.parse(" 0, 2d, 7 , 4D "));
	}

	@Test
	void blankSequenceDisablesAutomaticTuning() {
		assertEquals(List.of(), NoteSequence.parse("   "));
	}

	@Test
	void parsesWhitespaceSeparatedSequenceWhenThereAreNoCommas() {
		assertEquals(NoteSequence.parse("3, 6, 8, 11, 2d, 6"), NoteSequence.parse("3  6\n8\t11 2d 6"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("3 6, 8 11"));
	}

	@Test
	void expandsLongDelaysIntoTheMinimumNumberOfRepeaters() {
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 10, 0, 3),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 10, 1, 3),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 2, 10, 2, 3)
		), NoteSequence.parse("10d"));
		assertEquals(16, NoteSequence.parse("64d").size());
	}

	@Test
	void scalesDelayTokensBeforeExpandingRepeaters() {
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.NOTE, 0, 0, 0, 1),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 4, 0, 1),
			new NoteSequence.Step(NoteSequence.StepType.NOTE, 7, 0, 0, 1)
		), NoteSequence.parse("0, 2d, 7", 8));
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 6, 0, 2),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 2, 6, 1, 2)
		), NoteSequence.parse("2d", 12));
	}

	@Test
	void roundsFractionalScaledDelaysToTheNearestTick() {
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 2, 2, 0, 1)
		), NoteSequence.parse("1d", 6));
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 5, 0, 2),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 1, 5, 1, 2)
		), NoteSequence.parse("3d", 6));
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 1, 1, 0, 1)
		), NoteSequence.parse("1d", 1));
	}

	@Test
	void rejectsMissingAndOutOfRangeEntries() {
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("-1,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,25"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,G,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,0d,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,65d,12"));
	}

	@Test
	void countsPhysicalNoteBlockAndRepeaterProgress() {
		List<NoteSequence.Step> steps = NoteSequence.parse("0, 10d, 7, 2d");
		assertEquals(
			new NoteSequence.Progress(5, 6, 2, 2, 3, 4),
			NoteSequence.progress(steps, 4)
		);
		assertEquals(
			new NoteSequence.Progress(1, 6, 1, 2, 0, 4),
			NoteSequence.progress(steps, 6)
		);
	}
}
