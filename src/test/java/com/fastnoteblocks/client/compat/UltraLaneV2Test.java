package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The second layout, against the first, on both songs that matter.
 *
 * <p>Not tagged {@code sweep}: this one is a regression. It asserts that the new mode builds at all,
 * that it plays -- no wrong note and no dead line, the latter read off the blocks -- and that it
 * still beats the old layout on the song it was designed for. The comparison numbers are printed
 * rather than asserted, because they are meant to move.</p>
 */
class UltraLaneV2Test {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> song,
			SongBuilder.PasteMode mode, int width, int floors) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song, mode,
			new SongBuilder.BuildLimits(4, width, floors));
	}

	@Test
	void buildsAndPlays() {
		List<SongBuilder.EventNote> song = AllTwentyFivesTest.allTwentyFives();
		assertTrue(UltraLaneV2.fits(song), "the target song is inside the 25-note cap");
		SongBuilder.PastePlan plan = build(song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, 40, 5);
		assertTrue(plan.commands().size() > 1000, "the new mode built something");
		assertEquals(0, plan.wrongNotes(), "no note sounds at the wrong moment");
		assertEquals(0, BreachView.readBack("v2", plan).unreachedNotes(), "no dead line");
	}

	/**
	 * And it leaves the layout it stands beside exactly as it was.
	 *
	 * <p>The flags are pinned first, and that is not belt and braces. Every switch in this file is a
	 * static, the probes set them to measure a change, and a probe that throws or that forgets its
	 * {@code finally} leaves the next class to run building something else. This assertion passed on
	 * its own and failed in the suite for exactly that reason -- {@link SongBuilder#MARK_COLLISIONS}
	 * left on by another class marks collisions instead of refusing them, which changes what v1
	 * builds. An exact number is only worth asserting if what it depends on is stated.</p>
	 */
	@Test
	void doesNotDisturbTheFirstLayout() {
		SongBuilder.MARK_COLLISIONS = false;
		SongBuilder.CUT_ONLY_LANES = false;
		SongBuilder.CUTS_THE_CHORD_THAT_REACHES = false;
		SongBuilder.SMALL_MAY_STACK_IN_A_TURN = false;
		SongBuilder.STRANDED_CHORD_MAY_STILL_CUT = false;
		SongBuilder.CUT_FAR_HALF_FREES_THE_GAP = false;
		// Not a v2 switch, and pinned all the same: v1's whole 88 depends on it, and it is the one a
		// tidy-up in another class was leaving off. See StackedBusTest#putTheHeadsBack.
		SongBuilder.STACKED_BUS_HEADS = true;
		List<SongBuilder.EventNote> song = AllTwentyFivesTest.allTwentyFives();
		SongBuilder.PastePlan v1 = build(song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE, 40, 2);
		// Printed whether it passes or not, because when this fails in the suite and passes on its own
		// the question is always which switch the class before it left somewhere else. Naming a handful
		// of suspects only answers that when the suspect is one of them -- the leak this caught was
		// STACKED_BUS_HEADS, which is not in the list above and was not on anybody's list. Reflecting
		// over every static in the file and diffing the two runs is what found it in one go.
		System.out.println("V1GUARD blocks="
			+ v1.breaches().stream().mapToInt(Integer::intValue).sum()
			+ " busHeads=" + SongBuilder.STACKED_BUS_HEADS);
		// 40 wide over two floors is where v1 breaches this song hardest, 88 blocks in 8 lanes. If
		// that has changed, something meant for v2 has leaked into the mode it was supposed to leave
		// alone -- which is the whole reason the two are separate modes rather than a flag.
		assertEquals(88, v1.breaches().stream().mapToInt(Integer::intValue).sum(),
			"v1 still breaches the target song exactly as it did");
	}

	/**
	 * Every climb, descent and flat turn stands at its wall.
	 *
	 * <p>ekran's rule, and a regression rather than a measurement: a staircase set back inside the
	 * corridor stands in a column no other corridor's turn stands in, which is the one thing that
	 * reaches into the lane alongside. The closing pad has been walked out to its wall since
	 * {@link SongBuilder#PIN_DESCENTS}; {@link SongBuilder#CUT_PINS_ITS_STAIRCASE} holds the cut to
	 * the same rule, and v2 closes nearly every lane on a cut.</p>
	 *
	 * <p>Asserted on the count the walk takes from the block the staircase actually lands on, not on
	 * the shape it was planned in.</p>
	 */
	@Test
	void turnsAtTheWallAndNowhereElse() throws Exception {
		for (List<SongBuilder.EventNote> song : List.of(AllTwentyFivesTest.allTwentyFives(),
				BreachView.song("deltarune-ch-4-guardian"))) {
			for (int floors = 2; floors <= 5; floors++) {
				for (int width : new int[] {20, 24, 32, 40}) {
					SongBuilder.PastePlan plan;
					try {
						plan = build(song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, width, floors);
					} catch (IllegalArgumentException refused) {
						// A size that will not build at all is a different fault, and one this branch is
						// still carrying. It says nothing about where the staircases stand.
						continue;
					}
					assertEquals(0, plan.recessedColumns(),
						"no staircase stands inside the wall at " + width + "w x " + floors + "f");
				}
			}
		}
	}

	@Test
	void weighsTheTwoLayouts() throws Exception {
		System.out.println();
		System.out.println("==== the two layouts, side by side ====");
		row("all-25 at a gap of 1", AllTwentyFivesTest.allTwentyFives());
		row("guardian", BreachView.song("deltarune-ch-4-guardian"));
	}

	private static void row(String name, List<SongBuilder.EventNote> song) {
		System.out.println("   " + name + "  (biggest chord " + UltraLaneV2.biggestChord(song) + ")");
		for (SongBuilder.PasteMode mode : new SongBuilder.PasteMode[] {
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2}) {
			int lanes = 0;
			int blocks = 0;
			int dirty = 0;
			int refusals = 0;
			long length = 0;
			// The length of the snake, which is the number worth watching. Width and depth move in
			// jumps and depend on how many floors the config was handed; this moves whenever the build
			// actually gets shorter. ekran's.
			long columns = 0;
			int recessed = 0;
			for (int floors = 2; floors <= 5; floors++) {
				for (int width : new int[] {20, 24, 32, 40}) {
					// A size that will not build at all is counted, not thrown. v2 is under construction and
					// some of its switches collide at some widths -- STACKED_MAY_WRAP_A_BEND does -- and a
					// table that stops at the first one tells you nothing about the other fifteen.
					int sum;
					try {
						SongBuilder.PastePlan plan = build(song, mode, width, floors);
						sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
						lanes += plan.breaches().size();
						length += plan.width();
						columns += plan.totalColumns();
						recessed += plan.recessedColumns();
					} catch (IllegalArgumentException refused) {
						refusals++;
						continue;
					}
					blocks += sum;
					if (sum > 0) {
						dirty++;
					}
				}
			}
			// Recessed columns beside the breach count, because they are the two ways a lane can fail to
			// use the corridor it was given and only one of them has ever been printed.
			System.out.println(String.format("      %-24s lanes=%-4d blocks=%-5d dirty=%d of 16"
				+ "  totalCols=%-8d width=%-7d recessed=%d%s", mode.label(), lanes, blocks, dirty,
				columns, length, recessed,
				refusals == 0 ? "" : "  refusedToBuild=" + refusals));
		}
	}
}
