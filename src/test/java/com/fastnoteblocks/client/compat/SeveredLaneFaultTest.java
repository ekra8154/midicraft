package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A build with a repeater reading nothing says so, whatever the reader makes of it.
 *
 * <p>The fault this pins is the one that hid for the whole life of the two-rail run: a lane cut in
 * half leaves an orphaned repeater, {@link NoteMachineReader} cannot tell that from a second lever
 * and starts a fresh performance at it, and every note downstream then counts as reached. So the
 * count of unreached notes -- the only dead-wire number the mod had -- read nought on a build whose
 * lane stopped a third of the way through. 182 of them over the library, 178 on one song.</p>
 *
 * <p>Pinned by switching the fix off rather than by a hand-built machine, because the shape is a
 * property of the run and a hand-built one would only prove that the check works on the thing it
 * was written against.</p>
 */
class SeveredLaneFaultTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<String> faultsFor(String song, int width, int floors) throws Exception {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), BreachView.song(song),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(4, width, floors)).faults();
	}

	private static long severed(List<String> faults) {
		return faults.stream().filter(fault -> fault.contains("nothing behind them to read")).count();
	}

	@Test
	void namesTheCutWhenARunStopsOnAFloorColumn() throws Exception {
		boolean was = SongBuilder.RAIL_HANDS_THE_PATH_UP;
		try {
			SongBuilder.RAIL_HANDS_THE_PATH_UP = false;
			List<String> broken = faultsFor("gangsta-s-paradise", 40, 3);
			assertEquals(1, severed(broken),
				"a build whose lane is cut says so, once, naming the first cut: " + broken);
			assertTrue(broken.stream().anyMatch(fault -> fault.contains("nothing behind them to read")
					&& fault.contains("The first is at")),
				"and says where to go and stand: " + broken);
		} finally {
			SongBuilder.RAIL_HANDS_THE_PATH_UP = was;
		}
	}

	@Test
	void saysNothingWhenTheRunHandsThePathUp() throws Exception {
		assertEquals(0, severed(faultsFor("gangsta-s-paradise", 40, 3)),
			"the same build, with the run handing the path rail up before it stops");
		assertEquals(0, severed(faultsFor("hammer-of-justice-2", 128, 1)),
			"and the 128-wide paste, which is where this was found");
	}
}
