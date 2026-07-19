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
	void rejectsMissingAndOutOfRangeEntries() {
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("-1,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,25"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,G,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,0d,12"));
		assertThrows(IllegalArgumentException.class, () -> NoteSequence.parse("0,5d,12"));
	}
}
