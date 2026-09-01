package com.midicraft.client.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: how near its wall each foldback's wall-bound run actually gets. */
@Tag("sweep")
class WallReachProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void listsTheReach() throws Exception {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		SongBuilder.NAME_EVERY_CELL = true;
		try {
			String name = System.getProperty("probe.song", "guardian26");
			int width = Integer.parseInt(System.getProperty("probe.width", "31"));
			int floors = Integer.parseInt(System.getProperty("probe.floors", "4"));
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				BreachView.song(name), SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, width, floors));
			Map<BlockPos, String> world = new HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parts[4]);
			}
			int lowX = Integer.MAX_VALUE;
			int highX = Integer.MIN_VALUE;
			for (BlockPos at : world.keySet()) {
				lowX = Math.min(lowX, at.getX());
				highX = Math.max(highX, at.getX());
			}
			System.out.println("REACH " + name + " " + width + "x" + floors
				+ " built x " + lowX + ".." + highX);
			// Every wall-bound run, by the z-row it stands in, with the x it reached.
			Map<String, int[]> runs = new TreeMap<>();
			for (Map.Entry<BlockPos, String> cell : plan.laidBy().entrySet()) {
				String by = cell.getValue();
				if (by == null || !by.startsWith("foldback wall run")) {
					continue;
				}
				BlockPos at = cell.getKey();
				String key = at.getY() + " " + at.getZ();
				int[] span = runs.computeIfAbsent(key,
					unused -> new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE});
				span[0] = Math.min(span[0], at.getX());
				span[1] = Math.max(span[1], at.getX());
			}
			int atLow = 0;
			int atHigh = 0;
			for (Map.Entry<String, int[]> run : runs.entrySet()) {
				if (run.getValue()[0] <= lowX + 1) {
					atLow++;
				}
				if (run.getValue()[1] >= highX - 1) {
					atHigh++;
				}
			}
			System.out.println("REACH rows=" + runs.size() + " touching low edge=" + atLow
				+ " touching high edge=" + atHigh);
			int shown = 0;
			for (Map.Entry<String, int[]> run : runs.entrySet()) {
				if (shown++ >= 12) {
					break;
				}
				System.out.println("REACH   y z " + run.getKey() + "  x "
					+ run.getValue()[0] + ".." + run.getValue()[1]);
			}
		} finally {
			SongBuilder.NAME_EVERY_CELL = names;
		}
	}
}
