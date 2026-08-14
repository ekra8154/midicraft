package com.fastnoteblocks.client.compat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch: what pinning a cut's staircase to the wall costs, size by size.
 *
 * <p>ekran's rule is that every climb, descent and flat turn stands at the wall. The padded close has
 * pinned its staircase since {@link SongBuilder#PIN_DESCENTS}; the cut never has, and every recessed
 * column in either layout is a headed cut whose near half ran out of notes before the wall. The two
 * arms here are {@link SongBuilder#CUT_PINS_ITS_STAIRCASE} off and on.</p>
 */
@Tag("sweep")
class RecessProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void whatThePinCosts() throws Exception {
		System.out.println();
		System.out.println("==== the cut's staircase, recessed and pinned ====");
		try {
			// Both switches, because both are new and both can collide. A table that moves one of them
			// cannot say which one a refusal belongs to, and the bend rule is known to refuse two
			// Guardian sizes on its own.
			for (boolean bend : new boolean[] {false, true}) {
				SongBuilder.STACKED_MAY_WRAP_A_BEND = bend;
				System.out.println("   -- STACKED_MAY_WRAP_A_BEND = " + bend);
				song("all-25 at a gap of 1", AllTwentyFivesTest.allTwentyFives());
				song("guardian", BreachView.song("deltarune-ch-4-guardian"));
			}
		} finally {
			SongBuilder.CUT_PINS_ITS_STAIRCASE = true;
			SongBuilder.STACKED_MAY_WRAP_A_BEND = true;
		}
	}

	/**
	 * And the pinned build read back off its own blocks, which is the only thing that can see a pin
	 * that conducts nothing.
	 *
	 * <p>Breach counts cannot: a run of sixteen builds cleanly, reports nought and plays half a song.
	 * The back pin is wire laid downstream of the cut's own repeater, so it is exactly the kind of
	 * change that reads well and goes dead.</p>
	 */
	@Test
	void thePinnedBuildStillPlays() throws Exception {
		System.out.println();
		System.out.println("==== pinned builds, read off the blocks ====");
		// Three arms, because the pin is two mechanisms. A pad in front of the module is plain dust on
		// the path, which the parity pad has always laid; a pad behind the near half is raised, and
		// raised wire beside a lane is the shape that reaches into a neighbour's notes.
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		try {
			SongBuilder.CUT_PINS_ITS_STAIRCASE = false;
			readBack("guardian pin=off", guardian);
			SongBuilder.CUT_PINS_ITS_STAIRCASE = true;
			SongBuilder.CUT_PINS_BEHIND = false;
			readBack("guardian front only", guardian);
			SongBuilder.CUT_PINS_BEHIND = true;
			readBack("guardian both", guardian);
			readBack("all-25 both", AllTwentyFivesTest.allTwentyFives());
		} finally {
			SongBuilder.CUT_PINS_ITS_STAIRCASE = true;
			SongBuilder.CUT_PINS_BEHIND = true;
		}
	}

	private static void readBack(String name, List<SongBuilder.EventNote> song) {
		for (int floors = 2; floors <= 5; floors++) {
			for (int width : new int[] {20, 24, 32, 40}) {
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
						new SongBuilder.BuildLimits(4, width, floors));
				} catch (IllegalArgumentException refusal) {
					System.out.println(String.format("      %-22s %2dw x %df  refused", name, width,
						floors));
					continue;
				}
				int unreached = BreachView.readBack("v2", plan).unreachedNotes();
				System.out.println(String.format("      %-22s %2dw x %df  wrong=%-4d unreached=%-5d%s",
					name, width, floors, plan.wrongNotes(), unreached,
					plan.wrongNotes() + unreached == 0 ? "" : "   <-- LOOK"));
			}
		}
	}

	/**
	 * What is left of the planner in v2, asked rather than assumed.
	 *
	 * <p>v2 was opened on the claim that a lane which can always cut never needs a pad booked in front
	 * of it. The booking search survived as a last resort for a refused head. This is the arm that says
	 * whether it is still earning its place.</p>
	 */
	@Test
	void whatTheBookingIsStillWorth() throws Exception {
		System.out.println();
		System.out.println("==== v2 with and without the pad search ====");
		try {
			for (boolean books : new boolean[] {true, false}) {
				SongBuilder.V2_BOOKS_PADS = books;
				System.out.println("   -- V2_BOOKS_PADS = " + books);
				weigh("all-25 at a gap of 1", AllTwentyFivesTest.allTwentyFives());
				weigh("guardian", BreachView.song("deltarune-ch-4-guardian"));
			}
		} finally {
			SongBuilder.V2_BOOKS_PADS = true;
		}
	}

	/**
	 * What letting a chord wrap a bend is worth, asked again now the cut lands on its wall.
	 *
	 * <p>It was measured as a loss and left on so ekran could stand in it. Both of the things it was
	 * measured against have since moved -- the cut pins its staircase and the pad search is gone --
	 * so the number is not the number any more.</p>
	 */
	@Test
	void whatTheBendRuleIsWorth() throws Exception {
		System.out.println();
		System.out.println("==== v2 with and without wrapping a bend ====");
		try {
			for (boolean bend : new boolean[] {false, true}) {
				SongBuilder.STACKED_MAY_WRAP_A_BEND = bend;
				System.out.println("   -- STACKED_MAY_WRAP_A_BEND = " + bend);
				weigh("all-25 at a gap of 1", AllTwentyFivesTest.allTwentyFives());
				weigh("guardian", BreachView.song("deltarune-ch-4-guardian"));
			}
		} finally {
			SongBuilder.STACKED_MAY_WRAP_A_BEND = true;
		}
	}

	/** Both songs, sixteen sizes each, as v2 stands. The table the shape work is measured against. */
	@Test
	void weighsV2AsItStands() throws Exception {
		System.out.println();
		System.out.println("==== v2 as it stands ====");
		weigh("all-25 at a gap of 1", AllTwentyFivesTest.allTwentyFives());
		weigh("guardian", BreachView.song("deltarune-ch-4-guardian"));
	}

	static void weigh(String name, List<SongBuilder.EventNote> song) {
		int lanes = 0;
		int blocks = 0;
		int dirty = 0;
		int refused = 0;
		int wrong = 0;
		long length = 0;
		List<String> sizes = new java.util.ArrayList<>();
		Map<String, Integer> census = new TreeMap<>();
		for (int floors = 2; floors <= 5; floors++) {
			for (int width : new int[] {20, 24, 32, 40}) {
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
						new SongBuilder.BuildLimits(4, width, floors));
				} catch (IllegalArgumentException refusal) {
					refused++;
					sizes.add(String.format("         %2dw x %df  refused: %s", width, floors,
						refusal.getMessage()));
					continue;
				}
				int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
				lanes += plan.breaches().size();
				blocks += sum;
				length += plan.width();
				wrong += plan.wrongNotes();
				if (sum > 0) {
					dirty++;
					sizes.add(String.format("         %2dw x %df  breaches %s", width, floors,
						plan.breaches()));
				}
				plan.padding().forEach((key, value) -> census.merge(key, value, Integer::sum));
			}
		}
		System.out.println(String.format(
			"      %-22s lanes=%-4d blocks=%-5d dirty=%-2d refused=%-2d wrong=%-3d length=%d",
			name, lanes, blocks, dirty, refused, wrong, length));
		// The shape decisions this arm turns on, so a table that does not move can say whether the
		// thing being measured ever happened.
		census.entrySet().stream()
			.filter(entry -> entry.getKey().startsWith("v2")
				|| entry.getKey().startsWith("planBusFor"))
			.forEach(entry -> System.out.println("         " + entry.getKey() + " = "
				+ entry.getValue()));
		sizes.forEach(System.out::println);
	}

	private static void song(String name, List<SongBuilder.EventNote> song) {
		System.out.println("   " + name);
		for (boolean pinned : new boolean[] {false, true}) {
			SongBuilder.CUT_PINS_ITS_STAIRCASE = pinned;
			int lanes = 0;
			int blocks = 0;
			int dirty = 0;
			int refused = 0;
			long length = 0;
			int recessLanes = 0;
			int recessColumns = 0;
			int worstRecess = 0;
			Map<String, Integer> census = new TreeMap<>();
			// One println per line, because a multi-line one is swallowed: Bootstrap redirects stdout
			// into log4j and only whole lines come through.
			List<String> sizes = new java.util.ArrayList<>();
			for (int floors = 2; floors <= 5; floors++) {
				for (int width : new int[] {20, 24, 32, 40}) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (IllegalArgumentException refusal) {
						refused++;
						sizes.add(String.format("         %2dw x %df  refused: %s", width, floors,
							refusal.getMessage()));
						continue;
					}
					int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
					lanes += plan.breaches().size();
					blocks += sum;
					length += plan.width();
					recessLanes += plan.recesses().size();
					recessColumns += plan.recessedColumns();
					worstRecess = Math.max(worstRecess, plan.worstRecess());
					if (sum > 0) {
						dirty++;
						sizes.add(String.format("         %2dw x %df  breaches %s", width, floors,
							plan.breaches()));
					}
					plan.padding().forEach((key, value) -> census.merge(key, value, Integer::sum));
				}
			}
			System.out.println(String.format(
				"      pin=%-5s lanes=%-4d blocks=%-5d dirty=%-2d refused=%-2d length=%-7d"
				+ " recessed=%d lanes / %d columns / worst %d",
				pinned, lanes, blocks, dirty, refused, length, recessLanes, recessColumns,
				worstRecess));
			census.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("cutPin")
					|| entry.getKey().startsWith("recess"))
				.forEach(entry -> System.out.println("         " + entry.getKey() + " = "
					+ entry.getValue()));
			sizes.forEach(System.out::println);
		}
	}
}
