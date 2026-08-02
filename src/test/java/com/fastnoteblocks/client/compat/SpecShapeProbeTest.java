package com.fastnoteblocks.client.compat;

import java.util.List;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: what a spec actually builds -- turns, levels, and every block in it. */
class SpecShapeProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static void show(String spec, int width, int floors, String shape, int columnsToWall) {
		List<DebugChords.Chord> chords = DebugChords.parse(spec, DebugChords.DEFAULT_GAP);
		SongBuilder.WalkStart start = new SongBuilder.WalkStart(
			Math.max(0, width - 2 - columnsToWall),
			"up".equals(shape) ? 0 : floors - 1, "down".equals(shape) ? -1 : 1, false);
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes(chords), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, width, floors), start);
		System.out.println("SHAPE " + width + " " + floors + " " + shape + " " + columnsToWall
			+ " :: " + spec);
		System.out.println("  size " + plan.width() + " long " + plan.depth() + " deep "
			+ plan.height() + " high, turns=" + plan.turns().size()
			+ ", blocks=" + plan.commands().size());
		TreeMap<Integer, Integer> byLevel = new TreeMap<>();
		TreeMap<String, Integer> byBlock = new TreeMap<>();
		for (String command : plan.commands()) {
			String[] words = command.split(" ");
			byLevel.merge(Integer.parseInt(words[2]), 1, Integer::sum);
			byBlock.merge(words[4].split("\\[")[0], 1, Integer::sum);
		}
		System.out.println("  levels " + byLevel);
		System.out.println("  blocks " + byBlock);
		for (String fault : plan.faults()) {
			System.out.println("  FAULT " + fault);
		}
	}

	@Test
	void whatTheHandoverSpecsBuild() {
		show("2 24", 24, 3, "down", 20);
		show("2 24", 24, 3, "down", 13);
		show("6 6 6 6 24", 24, 3, "down", 20);
		show("10", 24, 2, "flat", 20);
	}

	/** Hunt for a spec that shows a head and a descent in the same build. */
	@Test
	void findsOneThatDoesBoth() {
		for (String spec : List.of("2 24", "24", "10 24", "2 20", "20", "10 20", "2 18 24",
				"6 6 24", "24@1", "20@1", "2 24@1")) {
			for (int width = 16; width <= 40; width += 4) {
				for (int cols = 6; cols <= width - 2; cols++) {
					List<DebugChords.Chord> chords =
						DebugChords.parse(spec, DebugChords.DEFAULT_GAP);
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
							DebugChords.notes(chords), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, 3),
							new SongBuilder.WalkStart(Math.max(0, width - 2 - cols), 2, -1, false));
					} catch (RuntimeException refused) {
						continue;
					}
					int crosses = 0;
					for (String command : plan.commands()) {
						if (command.split(" ")[4].startsWith("minecraft:redstone_wire[")) {
							crosses++;
						}
					}
					if (crosses > 0 && !plan.turns().isEmpty()) {
						System.out.println("BOTH /fastnoteblockpaste " + width + " 3 down " + cols
							+ " " + spec + "  -> " + crosses + " modules, "
							+ plan.turns().size() + " turns, " + plan.width() + " long, faults="
							+ plan.faults().size());
					}
				}
			}
		}
	}
}
