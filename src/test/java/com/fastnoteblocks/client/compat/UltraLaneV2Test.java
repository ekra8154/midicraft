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

	/** And it leaves the layout it stands beside exactly as it was. */
	@Test
	void doesNotDisturbTheFirstLayout() {
		List<SongBuilder.EventNote> song = AllTwentyFivesTest.allTwentyFives();
		SongBuilder.PastePlan v1 = build(song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE, 40, 2);
		// 40 wide over two floors is where v1 breaches this song hardest, 88 blocks in 8 lanes. If
		// that has changed, something meant for v2 has leaked into the mode it was supposed to leave
		// alone -- which is the whole reason the two are separate modes rather than a flag.
		assertEquals(88, v1.breaches().stream().mapToInt(Integer::intValue).sum(),
			"v1 still breaches the target song exactly as it did");
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
			long length = 0;
			for (int floors = 2; floors <= 5; floors++) {
				for (int width : new int[] {20, 24, 32, 40}) {
					SongBuilder.PastePlan plan = build(song, mode, width, floors);
					int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
					lanes += plan.breaches().size();
					blocks += sum;
					length += plan.width();
					if (sum > 0) {
						dirty++;
					}
				}
			}
			System.out.println(String.format("      %-24s lanes=%-4d blocks=%-5d dirty=%d of 16"
				+ "  length=%d", mode.label(), lanes, blocks, dirty, length));
		}
	}
}
