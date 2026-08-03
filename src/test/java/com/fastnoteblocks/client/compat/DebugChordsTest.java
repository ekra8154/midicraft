package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The spec, and the build it turns into.
 *
 * <p>The point of the spec is that the same words build the same thing in world and in a test, so
 * these check both ends: that the grammar reads the way it is documented, and that what comes out
 * of it goes through the builder and lands where it says.</p>
 */
@Tag("sweep")
class DebugChordsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void readsAListOfSizes() {
		assertEquals(List.of(new DebugChords.Chord(6, 4), new DebugChords.Chord(2, 4),
			new DebugChords.Chord(18, 4)), DebugChords.parse("6 2 18", 4));
	}

	@Test
	void takesCommasOrSpacesOrBoth() {
		List<DebugChords.Chord> wanted = List.of(new DebugChords.Chord(6, 4),
			new DebugChords.Chord(2, 4), new DebugChords.Chord(18, 4));

		assertEquals(wanted, DebugChords.parse("6 2 18", 4));
		assertEquals(wanted, DebugChords.parse("6, 2, 18", 4));
		assertEquals(wanted, DebugChords.parse("  6   2,,18 ", 4));
	}

	/** A gap said once holds until it is said again, so a run of them is written once. */
	@Test
	void carriesAGapForwardToTheChordsAfterIt() {
		assertEquals(List.of(new DebugChords.Chord(5, 4), new DebugChords.Chord(6, 1),
			new DebugChords.Chord(7, 1), new DebugChords.Chord(8, 3)),
			DebugChords.parse("5 6@1 7 8@3", 4));
	}

	@Test
	void repeatsAChordAsManyTimesAsAsked() {
		assertEquals(List.of(new DebugChords.Chord(30, 4), new DebugChords.Chord(30, 4),
			new DebugChords.Chord(30, 4)), DebugChords.parse("30x3", 4));
	}

	@Test
	void takesAGapPerChord() {
		assertEquals(List.of(new DebugChords.Chord(5, 4), new DebugChords.Chord(18, 1)),
			DebugChords.parse("5 18@1", 4));
	}

	@Test
	void takesARepeatAndAGapTogether() {
		assertEquals(List.of(new DebugChords.Chord(5, 1), new DebugChords.Chord(5, 1)),
			DebugChords.parse("5x2@1", 4));
	}


	@Test
	void refusesAChordBiggerThanTheBuilderTakes() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> DebugChords.parse("31", 4));
		assertTrue(refused.getMessage().contains("between one note and 30"),
			"the refusal should say the range: " + refused.getMessage());
	}

	@Test
	void refusesWordsAndNonsense() {
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("six", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("   ", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("6@0", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("6x0", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("0", 4));
	}

	@Test
	void putsEveryNoteOfAChordOnOneTick() {
		List<SongBuilder.EventNote> notes = DebugChords.notes("3 2", 4);

		assertEquals(5, notes.size());
		assertEquals(List.of(4, 4, 4, 8, 8),
			notes.stream().map(SongBuilder.EventNote::time).toList());
		assertEquals(1, notes.stream().map(SongBuilder.EventNote::pitch).distinct().count(),
			"every note should be the same pitch");
	}

	/** The whole point: a spec goes through the builder and comes back as a machine. */
	@Test
	void buildsSomethingTheBuilderAccepts() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes("6 2 18", 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 12, 1));

		assertTrue(plan.commands().size() > 26, "a build of twenty-six notes should place more "
			+ "blocks than that, and placed " + plan.commands().size());
		assertTrue(plan.width() > 0 && plan.height() > 0 && plan.depth() > 0);
	}

	/**
	 * A spec built round a turnaround leaves no run past fifteen.
	 *
	 * <p>Not the reproduction of the fault this was written after -- that one lives deep in a song
	 * and has not been cut down to a spec yet, which is the first thing the command is for. This is
	 * the assertion that shape of check belongs in a test at all: build a spec, walk the commands,
	 * and read the longest run of wire between two repeaters.</p>
	 */
	@Test
	void leavesNoRunOfWireLongerThanARepeaterReaches() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes("5x6 30", 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 36, 1));

		int dust = 0;
		int longest = 0;
		for (String command : plan.commands()) {
			String block = command.split(" ")[4];
			if (block.startsWith("minecraft:redstone_wire")) {
				dust++;
			} else if (block.startsWith("minecraft:repeater")) {
				longest = Math.max(longest, dust);
				dust = 0;
			}
		}
		assertTrue(longest <= 15, "a run of " + longest + " blocks of wire is longer than the "
			+ "fifteen a repeater reaches, so its far end is dead");
	}

	private static SongBuilder.PastePlan built(String spec, int width, int floors,
			SongBuilder.WalkStart start) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), DebugChords.notes(spec, 4),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, width, floors), start);
	}

	private static int lowest(SongBuilder.PastePlan plan) {
		return plan.commands().stream().mapToInt(command ->
			Integer.parseInt(command.split(" ")[2])).min().orElseThrow();
	}

	private static int highest(SongBuilder.PastePlan plan) {
		return plan.commands().stream().mapToInt(command ->
			Integer.parseInt(command.split(" ")[2])).max().orElseThrow();
	}

	/** The seed has to be inert by default, or every build in the library moves under it. */
	@Test
	void startingAtTheHeadIsTheWalkThatWasThereBefore() {
		SongBuilder.PastePlan asked = built("5x12 18", 24, 2, SongBuilder.WalkStart.HEAD);
		SongBuilder.PastePlan silent = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes("5x12 18", 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 24, 2));

		assertEquals(silent.commands(), asked.commands(),
			"seeding the head should build exactly what not seeding it builds");
	}

	/**
	 * The three wall shapes, told apart by where the build ends up vertically.
	 *
	 * <p>A climb takes the lanes above the floor they started on and a descent takes them below it;
	 * a flat turn is the step that lands outside the build, so it stays in its own band. Asserted
	 * against each other rather than against fixed heights, because what is being checked is that the
	 * seed picks a different wall -- not how tall a floor happens to be.</p>
	 */
	@Test
	void seedsTheWallShapeItIsAskedFor() {
		String spec = "5x24";
		SongBuilder.PastePlan flat = built(spec, 20, 3, new SongBuilder.WalkStart(0, 2, 1));
		SongBuilder.PastePlan up = built(spec, 20, 3, new SongBuilder.WalkStart(0, 0, 1));
		SongBuilder.PastePlan down = built(spec, 20, 3, new SongBuilder.WalkStart(0, 2, -1));

		assertTrue(highest(up) > highest(flat), "a climb should reach above a flat turn, and got "
			+ highest(up) + " against " + highest(flat));
		assertTrue(lowest(down) < lowest(flat), "a descent should reach below a flat turn, and got "
			+ lowest(down) + " against " + lowest(flat));
		assertTrue(lowest(down) < 64, "a descent from the origin should go below it, and got "
			+ lowest(down));
	}

	/** Starting partway along the lane leaves less of it, so the walk meets its wall sooner. */
	@Test
	void startingDownTheLaneMeetsTheWallSooner() {
		SongBuilder.PastePlan whole = built("5x6", 40, 1, SongBuilder.WalkStart.HEAD);
		SongBuilder.PastePlan late = built("5x6", 40, 1, new SongBuilder.WalkStart(30, 0, 1));

		assertTrue(whole.turns().isEmpty(),
			"six small chords should not reach a wall forty columns out");
		assertTrue(!late.turns().isEmpty(),
			"and should reach it when they start thirty columns along");
	}

	@Test
	void readsAnInstrumentMixAndFillsTheRestWithHarp() {
		DebugChords.Chord chord = DebugChords.parse("6:2h1s1b", 4).get(0);

		assertEquals(List.of("minecraft:glass", "minecraft:glass", "minecraft:sand",
			"minecraft:gold_block", "minecraft:air", "minecraft:air"), chord.blocks());
	}

	@Test
	void takesAMixAlongsideARepeatAndAGap() {
		List<DebugChords.Chord> chords = DebugChords.parse("7:7bx2@1", 4);

		assertEquals(2, chords.size());
		assertEquals(1, chords.get(0).gap());
		assertEquals(java.util.Collections.nCopies(7, "minecraft:gold_block"),
			chords.get(1).blocks());
	}

	@Test
	void refusesAMixThatDoesNotFitOrDoesNotParse() {
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("3:4b", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("6:2z", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("6:b", 4));
		assertThrows(IllegalArgumentException.class, () -> DebugChords.parse("6:2", 4));
	}

	/** A chord with no mix is every note over air, which is what harp has always been. */
	@Test
	void defaultsToHarpWhichIsAir() {
		assertEquals(java.util.Collections.nCopies(4, "minecraft:air"),
			DebugChords.parse("4", 4).get(0).blocks());
	}

	/** And the instruments reach the build, which is the whole reason for them. */
	@Test
	void putsTheNamedInstrumentBlocksIntoThePlan() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes("8:4h4b", 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 40, 1), SongBuilder.WalkStart.HEAD);

		assertTrue(plan.commands().stream().anyMatch(command ->
			command.contains("minecraft:glass")), "the hi-hats should be glass in the build");
		assertTrue(plan.commands().stream().anyMatch(command ->
			command.contains("minecraft:gold_block")), "and the bells gold");
	}

	@Test
	void saysWhatTheSpecCameTo() {
		assertEquals("3 chords, 26 notes, biggest 18",
			DebugChords.describe(DebugChords.parse("6 2 18", 4)));
	}
}
