package com.midicraft.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: where every run in a build opens, with the walls beside it. */
@Tag("sweep")
class RailHeadTraceProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void tracesTheHeads() throws Exception {
		SongBuilder.TRACE_RAIL_HEADS = true;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				BreachView.song(System.getProperty("probe.song", "choral-chambers-hollow-knight-silksong")),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, Integer.parseInt(System.getProperty("probe.width", "8")),
					Integer.parseInt(System.getProperty("probe.floors", "1"))));
			System.out.println("RAILHEAD walls " + plan.nearWall() + ".." + plan.farWall());
		} finally {
			SongBuilder.TRACE_RAIL_HEADS = false;
		}
	}
}
