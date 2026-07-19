package com.fastnoteblocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class NoteSequenceTest {
	@Test
	void parsesWhitespaceAgnosticCommaSeparatedPitches() {
		assertEquals(List.of(0, 4, 7, 12, 24), NoteSequence.parse(" 0,4, 7 , 12,24 "));
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
	}
}
