package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where v2 stands on Guardian, size by size, and one breach to go and stand in.
 *
 * <p>Three faults, not one, because they are not the same kind of wrong. A breach is a lane outside
 * the width it promised -- visible, and harmless to the music. A wrong note is a note block something
 * else sounds, which the layout check finds. A dead line is wire the signal never crosses, which
 * nothing but reading the blocks back can find, and which silences everything downstream of it.</p>
 */
@Tag("sweep")
class GuardianV2StateProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> song, int width,
			int floors) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(4, width, floors));
	}

	/**
	 * Runs of dust the signal has to cross with nothing to revive it.
	 *
	 * <p>The commands are in build order, which is the order the signal travels them, so the stretch
	 * between one repeater and the next is the wire between them. Cheapest useful measurement in the
	 * codebase, and it sees what {@code verify} cannot.</p>
	 */
	private static List<String> deadRuns(SongBuilder.PastePlan plan) {
		List<String> found = new ArrayList<>();
		int run = 0;
		String startedAt = null;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String block = parts[4];
			if (block.startsWith("minecraft:repeater")) {
				run = 0;
				startedAt = null;
			} else if (block.startsWith("minecraft:redstone_wire")) {
				if (run == 0) {
					startedAt = parts[1] + " " + parts[2] + " " + parts[3];
				}
				run++;
				// Sixteen: one past what a repeater drives, so the sixteenth block is the first one
				// the signal does not reach.
				if (run == 16) {
					found.add("run of " + run + " from " + startedAt + " reaching "
						+ parts[1] + " " + parts[2] + " " + parts[3]);
				}
			}
		}
		return found;
	}

	@Test
	void whereGuardianStands() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== guardian on v2, every size ====");
		int worstBlocks = 0;
		int worstWidth = 0;
		int worstFloors = 0;
		for (int floors = 2; floors <= 5; floors++) {
			for (int width : new int[] {20, 24, 32, 40}) {
				SongBuilder.PastePlan plan = build(guardian, width, floors);
				int blocks = plan.breaches().stream().mapToInt(Integer::intValue).sum();
				int unreached = BreachView.readBack("guardian", plan).unreachedNotes();
				List<String> dead = deadRuns(plan);
				// Lanes and pad beside the corridor, because the corridor is the two of them: a fixed
				// amount of music, plus whatever wire the build had to lay to get between it. The song
				// does not change, so every column of variation is the second one.
				int lanes = plan.turns().size();
				System.out.println(String.format(
					"   %2dw x %df  totalCols=%-7d lanes=%-4d perLane=%-6.1f padCols=%-6d"
					+ " breach=%-4d wrong=%-3d dead=%d",
					width, floors, plan.totalColumns(), lanes,
					lanes == 0 ? 0 : plan.totalColumns() / (double) lanes, plan.padCells(),
					blocks, plan.wrongNotes(), unreached));
				if (plan.worstBreach() > worstBlocks) {
					worstBlocks = plan.worstBreach();
					worstWidth = width;
					worstFloors = floors;
				}
			}
		}
		System.out.println();
		System.out.println("==== the worst single breach: " + worstWidth + " wide x " + worstFloors
			+ " floors, " + worstBlocks + " blocks past the wall ====");
		SongBuilder.PastePlan plan = build(guardian, worstWidth, worstFloors);
		System.out.println("   paste with buildLaneWidth=" + worstWidth + " buildLaneFloors="
			+ worstFloors + " mode=" + plan.mode().label());
		System.out.println("   origin 0 64 0, walls x=" + plan.nearWall() + ".." + plan.farWall()
			+ ", build spans x " + plan.spanX() + " z " + plan.spanZ());
		System.out.println("   breaches " + plan.breaches());
		plan.faults().stream().limit(6).forEach(fault -> System.out.println("   fault " + fault));
		// The turns themselves, which is what a breach is: a lane that ran on and turned outside the
		// width it promised. Every other block outside the wall is a turn's sideways run or the slab
		// step, which are supposed to be there -- listing those buries the two that matter.
		System.out.println("   turns outside the wall, which is the breach itself:");
		for (BlockPos turn : plan.turns()) {
			if (turn.getX() > plan.farWall() || turn.getX() < plan.nearWall()) {
				int past = turn.getX() > plan.farWall()
					? turn.getX() - plan.farWall() : plan.nearWall() - turn.getX();
				System.out.println("      " + turn.getX() + " " + turn.getY() + " " + turn.getZ()
					+ "   " + past + " past the " + (turn.getX() > plan.farWall() ? "far" : "near")
					+ " wall");
			}
		}
	}
}
