package com.midicraft.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: what a song actually gets built at, for each width the slider offers. */
@Tag("sweep")
class WidthClampProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void listsTheBuiltWidth() throws Exception {
		String name = System.getProperty("probe.song", "guardian26");
		int floors = Integer.parseInt(System.getProperty("probe.floors", "4"));
		for (int width = 6; width <= 26; width++) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				BreachView.song(name), SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, width, floors));
			int lowX = Integer.MAX_VALUE;
			int highX = Integer.MIN_VALUE;
			int lowZ = Integer.MAX_VALUE;
			int highZ = Integer.MIN_VALUE;
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				int x = Integer.parseInt(parts[1]);
				int z = Integer.parseInt(parts[3]);
				lowX = Math.min(lowX, x);
				highX = Math.max(highX, x);
				lowZ = Math.min(lowZ, z);
				highZ = Math.max(highZ, z);
			}
			System.out.println("CLAMP " + name + " asked " + String.format("%2d", width)
				+ " -> built x " + lowX + ".." + highX
				+ " (" + (highX - lowX + 1) + " across)"
				+ "  builtWidth " + plan.builtWidth()
				+ "  depth " + (highZ - lowZ + 1)
				+ "  blocks " + plan.commands().size());
		}
	}
}
