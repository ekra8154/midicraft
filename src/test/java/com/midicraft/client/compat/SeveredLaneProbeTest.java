package com.midicraft.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How many ways into the machine the reader thinks a planned build has.
 *
 * <p>The answer should always be one. A walk lays one entrance and one chain, so a second way in is
 * a repeater the build left with nothing behind it -- which is a severed lane, not an alternative
 * beginning. The reader cannot tell those apart and takes the generous reading: it starts a fresh
 * performance at the orphan, everything downstream counts as reached, and
 * {@code unreachedNotes()} comes back nought on a build whose lane is cut in half.</p>
 *
 * <p>Which means every dead-wire number in this repo is blind to exactly one shape of break, and
 * the debug paste's red nether brick is blind to it too -- it marks what the walk expected to be
 * live and the reader did not reach, and here the reader reached all of it.</p>
 */
@Tag("sweep")
class SeveredLaneProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void countsTheWaysIn() throws Exception {
		System.out.println();
		System.out.println("==== ways into the machine, which should be one ====");
		for (int[] size : new int[][] {{128, 1}, {48, 1}, {40, 3}, {24, 3}}) {
			FaultView.Build built = FaultView.of("hammer-of-justice-2",
				BreachView.song("hammer-of-justice-2"), SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				size[0], size[1], 4, false);
			System.out.println(String.format("   %3dw x %df  versions %d  dead %d  notes %d",
				size[0], size[1], built.reading().versions(), built.reading().unreachedNotes(),
				built.reading().noteBlocks()));
		}
	}
}
