package com.midicraft.client.compat;

import java.util.TreeMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: how much of a build is half-blocks, and which ones. */
@Tag("sweep")
class SlabCountProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static void count(String label, boolean everywhere) throws Exception {
		boolean was = SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE;
		SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE = everywhere;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				BreachView.song(System.getProperty("probe.song", "guardian26")),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, 20, 4));
			Map<String, Integer> tally = new TreeMap<>();
			int slabs = 0;
			for (String command : plan.commands()) {
				String block = command.split(" ")[4];
				if (block.contains("_slab")) {
					slabs++;
					tally.merge(block, 1, Integer::sum);
				}
			}
			System.out.println("SLABS " + label + ": " + slabs + " of " + plan.commands().size()
				+ " blocks");
			tally.forEach((block, n) -> System.out.println("   " + n + "  " + block));
		} finally {
			SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE = was;
		}
	}

	@Test
	void countsTheHalfBlocks() throws Exception {
		count("off (lane level only)", false);
		count("on  (everywhere)     ", true);
	}

	/** Which shape laid each cell that the flag turns from a full block into a half one. */
	@Test
	void namesTheCellsTheFlagChanges() throws Exception {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		SongBuilder.NAME_EVERY_CELL = true;
		boolean was = SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE;
		try {
			SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE = false;
			Map<BlockPos, String> before = blocksOf();
			SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE = true;
			SongBuilder.PastePlan after = plan();
			Map<String, Integer> byShape = new TreeMap<>();
			int changed = 0;
			for (String command : after.commands()) {
				String[] parts = command.split(" ");
				BlockPos at = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3]));
				String now = parts[4];
				String then = before.get(at);
				if (then == null || then.equals(now)) {
					continue;
				}
				changed++;
				byShape.merge(then + " -> " + now + "   laid by " + after.laidBy().get(at), 1,
					Integer::sum);
			}
			System.out.println("CHANGED " + changed + " cells");
			byShape.entrySet().stream()
				.sorted((a, b) -> b.getValue() - a.getValue())
				.forEach(e -> System.out.println(String.format("   %5d  %s", e.getValue(),
					e.getKey())));
		} finally {
			SongBuilder.SLAB_INSTRUMENTS_EVERYWHERE = was;
			SongBuilder.NAME_EVERY_CELL = names;
		}
	}

	private static SongBuilder.PastePlan plan() throws Exception {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			BreachView.song(System.getProperty("probe.song", "guardian26")),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(4, 20, 4));
	}

	private static Map<BlockPos, String> blocksOf() throws Exception {
		Map<BlockPos, String> world = new java.util.HashMap<>();
		for (String command : plan().commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parts[4]);
		}
		return world;
	}
}
