package com.midicraft.client.compat;

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
 * Scratch: where a cut leaves its staircase, size by size.
 *
 * <p>The rule is that every climb, descent and flat turn stands at the wall, and the padded close
 * has been walked out to it since {@link SongBuilder#PIN_DESCENTS}. The cut was held to the same
 * rule by a pin until 2026-08-16, when it was taken out as a fossil: a paster that can cut any
 * chord anywhere does not have to buy itself a wall in wire.</p>
 *
 * <p>The rule stayed. What fills the columns now is the chord -- a cut that would fall short takes
 * a shorter head, or gives the head up for a plain cut, or is laid whole and breaches. So the
 * recessed columns here should read <b>nought</b>, and this is the table that says what holding
 * them there costs in breaches, refusals and length.</p>
 *
 * <p>The arms that switched the pin are gone with it. What is left is the same table on one arm,
 * and the read-back that says whether a build still plays.</p>
 */
@Tag("sweep")
class RecessProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void whereTheCutLeavesItsStaircase() throws Exception {
		System.out.println();
		System.out.println("==== the cut's staircase, and how far inside the wall it stands ====");
		try {
			// The bend rule stays on both arms, because it is known to refuse Guardian sizes on its own
			// and a table that moves it cannot say which switch a refusal belongs to.
			for (boolean bend : new boolean[] {false, true}) {
				SongBuilder.STACKED_MAY_WRAP_A_BEND = bend;
				System.out.println("   -- STACKED_MAY_WRAP_A_BEND = " + bend);
				song("all-25 at a gap of 1", AllTwentyFivesTest.allTwentyFives());
				song("guardian", BreachView.song("deltarune-ch-4-guardian"));
			}
		} finally {
			SongBuilder.STACKED_MAY_WRAP_A_BEND = true;
		}
	}

	/**
	 * And the build read back off its own blocks, which is the only thing that can see wire that
	 * conducts nothing.
	 *
	 * <p>Breach counts cannot: a run of sixteen builds cleanly, reports nought and plays half a song.
	 * Taking the pin out shortens the run a cut opens with and leaves bare corridor where wire used to
	 * stand, so both songs are read back rather than counted.</p>
	 */
	@Test
	void theUnpinnedBuildStillPlays() throws Exception {
		System.out.println();
		System.out.println("==== builds with no pin, read off the blocks ====");
		readBack("guardian", BreachView.song("deltarune-ch-4-guardian"));
		readBack("all-25", AllTwentyFivesTest.allTwentyFives());
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
	 * of it. The booking search survived as a last resort for a refused head. This is the arm that
	 * says whether it is still earning its place.</p>
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
	 * <p>It was measured as a loss and left on so it could be stood in. Both of the things it was
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
		long columns = 0;
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
				columns += plan.totalColumns();
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
			"      %-22s lanes=%-4d blocks=%-5d dirty=%-2d refused=%-2d wrong=%-3d totalCols=%-8d"
			+ " width=%d",
			name, lanes, blocks, dirty, refused, wrong, columns, length));
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
		{
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
				"      lanes=%-4d blocks=%-5d dirty=%-2d refused=%-2d length=%-7d"
				+ " recessed=%d lanes / %d columns / worst %d",
				lanes, blocks, dirty, refused, length, recessLanes, recessColumns,
				worstRecess));
			census.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("busyPad")
					|| entry.getKey().startsWith("recess"))
				.forEach(entry -> System.out.println("         " + entry.getKey() + " = "
					+ entry.getValue()));
			sizes.forEach(System.out::println);
		}
	}
}
