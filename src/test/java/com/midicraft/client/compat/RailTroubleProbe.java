package com.midicraft.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: what a build says is wrong with itself, in its own words. */
@Tag("sweep")
class RailTroubleProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void printsTheFaultsAPlanCarries() throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			BreachView.song(System.getProperty("probe.song", "ultra-gaps-mixed")),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(4, Integer.parseInt(System.getProperty("probe.width", "8")),
				Integer.parseInt(System.getProperty("probe.floors", "3"))));
		System.out.println("TROUBLE faults=" + plan.faults().size());
		plan.faults().forEach(fault -> System.out.println("TROUBLE   " + fault));
	}
}
