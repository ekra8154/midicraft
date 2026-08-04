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

	/**
	 * Three notes at tick 0, an 8d delay, four notes at tick 8.
	 *
	 * <p>The repeaters carry their own start ticks -- 0 and 4, as the overlay lays them -- rather
	 * than all sharing the tick the delay began at. Tick 6 is the one that exists in no placement.</p>
	 */
	private static List<NoteSequence.Placement> chordsAcrossADelay() {
		List<NoteSequence.Placement> placements = new java.util.ArrayList<>();
		for (int pitch = 0; pitch < 3; pitch++) {
			placements.add(new NoteSequence.Placement(0, 1, NoteSequence.Step.note(pitch)));
		}
		int time = 0;
		for (NoteSequence.Step step : NoteSequence.parse("8d")) {
			placements.add(new NoteSequence.Placement(time, 0, step));
			time += step.value();
		}
		for (int pitch = 0; pitch < 4; pitch++) {
			placements.add(new NoteSequence.Placement(8, 1, NoteSequence.Step.note(pitch)));
		}
		return List.copyOf(placements);
	}

	@Test
	void aChordIsEveryNoteAtOneTickAndStopsAtTheDelay() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		assertEquals(9, placements.size());
		for (int index = 0; index <= 2; index++) {
			assertEquals(new NoteSequence.Span(0, 2), NoteSequence.chordSpan(placements, index));
		}
		for (int index = 5; index <= 8; index++) {
			assertEquals(new NoteSequence.Span(5, 8), NoteSequence.chordSpan(placements, index));
		}
		// A repeater is nobody's chord, not the tail of the one before it.
		assertEquals(new NoteSequence.Span(3, 3), NoteSequence.chordSpan(placements, 3));
	}

	@Test
	void notesAtOneTickFromDifferentTracksAreOneChord() {
		List<NoteSequence.Placement> placements = List.of(
			new NoteSequence.Placement(0, 1, NoteSequence.Step.note(0)),
			new NoteSequence.Placement(0, 2, NoteSequence.Step.note(7)),
			new NoteSequence.Placement(0, 2, NoteSequence.Step.note(11))
		);
		assertEquals(new NoteSequence.Span(0, 2), NoteSequence.chordSpan(placements, 1));
	}

	@Test
	void aJumpUnitIsAChordOrTheWholeRunOfDelay() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		assertEquals(new NoteSequence.Span(0, 2), NoteSequence.placementUnit(placements, 1));
		assertEquals(new NoteSequence.Span(3, 4), NoteSequence.placementUnit(placements, 3));
		assertEquals(new NoteSequence.Span(3, 4), NoteSequence.placementUnit(placements, 4));
		assertEquals(new NoteSequence.Span(5, 8), NoteSequence.placementUnit(placements, 6));
	}

	@Test
	void jumpingForwardLandsInFrontOfTheRepeatersRatherThanPastThem() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		assertEquals(3, NoteSequence.jump(placements, 1, 1));
		assertEquals(5, NoteSequence.jump(placements, 3, 1));
		// The last unit has nowhere to go, so forward stops on its final step.
		assertEquals(8, NoteSequence.jump(placements, 6, 1));
	}

	@Test
	void jumpingBackRestartsTheUnitBeforeLeavingIt() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		// Mid-chord, back means the top of this chord: the restart, with no key of its own.
		assertEquals(5, NoteSequence.jump(placements, 7, -1));
		// Already at the top, so back leaves for the unit before.
		assertEquals(3, NoteSequence.jump(placements, 5, -1));
		assertEquals(0, NoteSequence.jump(placements, 3, -1));
		assertEquals(0, NoteSequence.jump(placements, 0, -1));
	}

	@Test
	void alternatingWalksAChordInTheOrderItIsStoredIn() {
		NoteSequence.Span chord = new NoteSequence.Span(10, 15);
		for (int index = 10; index <= 15; index++) {
			assertEquals(index - 10, NoteSequence.chordRank(chord, index, false));
			assertEquals(index, NoteSequence.chordAt(chord, index - 10, false));
		}
	}

	@Test
	void twoStripsWalksOneSideOfTheBusAndThenTheOther() {
		// Six notes: three blocks of bus, one note either side of each.
		NoteSequence.Span chord = new NoteSequence.Span(0, 5);
		// The near side is the even placements, in order, and then the far side is the odd ones.
		assertEquals(List.of(0, 2, 4, 1, 3, 5), List.of(
			NoteSequence.chordAt(chord, 0, true), NoteSequence.chordAt(chord, 1, true),
			NoteSequence.chordAt(chord, 2, true), NoteSequence.chordAt(chord, 3, true),
			NoteSequence.chordAt(chord, 4, true), NoteSequence.chordAt(chord, 5, true)));
	}

	@Test
	void anOddChordLeavesTheLastBlockOfBusWithOneNote() {
		NoteSequence.Span chord = new NoteSequence.Span(0, 4);
		assertEquals(List.of(0, 2, 4, 1, 3), List.of(
			NoteSequence.chordAt(chord, 0, true), NoteSequence.chordAt(chord, 1, true),
			NoteSequence.chordAt(chord, 2, true), NoteSequence.chordAt(chord, 3, true),
			NoteSequence.chordAt(chord, 4, true)));
	}

	@Test
	void everyWalkVisitsEveryNoteExactlyOnce() {
		for (boolean twoStrips : List.of(false, true)) {
			for (int size = 1; size <= 30; size++) {
				NoteSequence.Span chord = new NoteSequence.Span(7, 7 + size - 1);
				java.util.Set<Integer> seen = new java.util.HashSet<>();
				for (int rank = 0; rank < size; rank++) {
					int index = NoteSequence.chordAt(chord, rank, twoStrips);
					assertEquals(rank, NoteSequence.chordRank(chord, index, twoStrips));
					seen.add(index);
				}
				assertEquals(size, seen.size(), "size " + size + " twoStrips " + twoStrips);
			}
		}
	}

	@Test
	void aMomentIsTheTickAndHowFarIntoIt() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		assertEquals(0, NoteSequence.momentOffset(placements, 0));
		assertEquals(2, NoteSequence.momentOffset(placements, 2));
		// The second chord opens tick 8, so it counts from itself again.
		assertEquals(0, NoteSequence.momentOffset(placements, 5));
		assertEquals(3, NoteSequence.momentOffset(placements, 8));
	}

	@Test
	void anEditElsewhereLeavesTheCursorOnTheSameMoment() {
		List<NoteSequence.Placement> before = chordsAcrossADelay();
		int cursor = 6;
		int time = before.get(cursor).time();
		int offset = NoteSequence.momentOffset(before, cursor);

		// A note added to the first chord shifts every later index by one.
		List<NoteSequence.Placement> after = new java.util.ArrayList<>(before);
		after.add(0, new NoteSequence.Placement(0, 1, NoteSequence.Step.note(9)));
		assertEquals(cursor + 1, NoteSequence.indexOfMoment(List.copyOf(after), time, offset));
		assertEquals(before.get(cursor).step(), after.get(cursor + 1).step());
	}

	@Test
	void aDeletedMomentFallsForwardToTheNextOneLeft() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		// The second repeater stands at tick 4, so that moment is still there to go back to.
		assertEquals(4, NoteSequence.indexOfMoment(placements, 4, 0));
		// Tick 6 is inside a repeater's delay and nothing is placed at it. Fall forward to tick 8.
		assertEquals(5, NoteSequence.indexOfMoment(placements, 6, 0));
		// An offset past the end of a shortened chord stops on its last note rather than running on.
		assertEquals(8, NoteSequence.indexOfMoment(placements, 8, 40));
		// A moment past everything left keeps the cursor on the final step.
		assertEquals(8, NoteSequence.indexOfMoment(placements, 900, 0));
	}

	@Test
	void spansSurviveAnIndexFromOutsideTheSequence() {
		List<NoteSequence.Placement> placements = chordsAcrossADelay();
		assertEquals(new NoteSequence.Span(5, 8), NoteSequence.chordSpan(placements, 400));
		assertEquals(new NoteSequence.Span(0, 2), NoteSequence.chordSpan(placements, -3));
		assertEquals(new NoteSequence.Span(0, 0), NoteSequence.chordSpan(List.of(), 0));
		assertEquals(0, NoteSequence.jump(List.of(), 0, 1));
	}
}
