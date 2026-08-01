package com.fastnoteblocks.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: with the lane already bending, which distance lands the bus on the second corner. */
class DeadLineReproTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void sweeps() {
		for (String spec : new String[] {"30 5@1 5 5"}) {
			for (int cols = 8; cols <= 18; cols++) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					DebugChords.notes(spec, 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, 36, 1),
					new SongBuilder.WalkStart(36 - 2 - cols, 0, 1, true));
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
				System.out.println("REPRO [" + spec + "] cols=" + cols
					+ " swapDone=" + plan.padding().getOrDefault("swapDone", 0)
					+ " swapSeen=" + plan.padding().getOrDefault("swapSeen", 0)
					+ " longestRun=" + longest + " blocks=" + plan.commands().size()
					+ " faults=" + plan.faults());
			}
		}
	}
}
