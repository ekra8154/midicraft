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
	void expandsLongDelaysIntoTheMinimumNumberOfRepeaters() {
		assertEquals(List.of(
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 10, 0, 3),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 4, 10, 1, 3),
			new NoteSequence.Step(NoteSequence.StepType.REPEATER, 2, 10, 2, 3)
		), NoteSequence.parse("10d"));
		assertEquals(16, NoteSequence.parse("64d").size());
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
