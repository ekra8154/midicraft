package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How far past its two walls a v2 build actually reaches, and what is standing out there.
 *
 * <p>The width the paste screen shows is the distance between the walls; what stands past them is
 * whatever each kind of turn happens to poke out. This lists, per column past a wall, the shapes
 * standing in it, so the outline can be seen and not guessed at.</p>
 */
@Tag("sweep")
class XExtentProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void extents() throws Exception {
		String[] names = System.getProperty("probe.songs",
			"deltarune-ch-4-guardian,all25").split(",");
		int[] widths = java.util.Arrays.stream(System.getProperty("probe.widths", "25,40")
			.split(",")).mapToInt(Integer::parseInt).toArray();
		int[] floorsList = java.util.Arrays.stream(System.getProperty("probe.floors", "5,3")
			.split(",")).mapToInt(Integer::parseInt).toArray();
		boolean was = SongBuilder.NAME_EVERY_CELL;
		SongBuilder.NAME_EVERY_CELL = true;
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			for (String name : names) {
				List<SongBuilder.EventNote> song = "all25".equals(name)
					? AllTwentyFivesTest.allTwentyFives() : BreachView.song(name);
				for (int floors : floorsList) {
					for (int width : widths) {
						SongBuilder.PastePlan plan;
						long began = System.nanoTime();
						try {
							plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
								new SongBuilder.BuildLimits(4, width, floors));
							System.out.println("XEXT built " + name + " " + width + "x" + floors + " in "
								+ (System.nanoTime() - began) / 1_000_000 + " ms");
						} catch (IllegalArgumentException refused) {
							System.out.println("XEXT " + name + " " + width + "x" + floors
								+ " refused: " + refused.getMessage());
							continue;
						}
						report(name + " " + width + "x" + floors, plan);
					}
				}
			}
		} finally {
			SongBuilder.NAME_EVERY_CELL = was;
			held.putBack();
		}
	}

	/** How many columns inside each wall to list as well, tagged +-100. */
	static final int INSIDE = Integer.getInteger("probe.inside", 0);

	static void report(String heading, SongBuilder.PastePlan plan) {
		Map<BlockPos, String> world = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3])), word[4]);
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		BlockPos first = null;
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			BlockPos at = new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3]));
			if (first == null) {
				first = at;
			}
			minX = Math.min(minX, at.getX());
			maxX = Math.max(maxX, at.getX());
		}
		System.out.println();
		System.out.println("XEXT " + heading + " nearWall=" + plan.nearWall() + " farWall="
			+ plan.farWall() + " minX=" + minX + " maxX=" + maxX + " (out " + (plan.nearWall() - minX)
			+ " / " + (maxX - plan.farWall()) + ") first=" + first.getX() + " " + first.getY() + " "
			+ first.getZ() + " breaches=" + plan.breaches() + " lanes=" + plan.turns().size());
		StringBuilder counters = new StringBuilder();
		for (Map.Entry<String, Integer> entry : new TreeMap<>(plan.padding()).entrySet()) {
			if (entry.getKey().startsWith("flatTurn") || entry.getKey().startsWith("REPEATER")
					|| entry.getKey().startsWith("planStart") || entry.getKey().startsWith("recessed")
					|| entry.getKey().startsWith("turnAsked")) {
				counters.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
			}
		}
		String dump = System.getProperty("probe.dump", "");
		if (!dump.isEmpty()) {
			int[] box = java.util.Arrays.stream(dump.split(",")).mapToInt(Integer::parseInt).toArray();
			for (int y = box[4]; y >= box[1]; y--) {
				for (int z = box[2]; z <= box[5]; z++) {
					for (int x = box[0]; x <= box[3]; x++) {
						BlockPos at = new BlockPos(x, y, z);
						String block = world.get(at);
						if (block == null) {
							continue;
						}
						System.out.println("XEXT cell " + x + " " + y + " " + z + "  " + block
							+ "  by " + plan.laidBy().getOrDefault(at, "?")
							+ (plan.noteTicks().containsKey(at) ? "  tick " + plan.noteTicks().get(at) : ""));
					}
				}
			}
		}
		if (Boolean.getBoolean("probe.collisions")) {
			plan.collisions().forEach((at, what) -> System.out.println("XEXT collision " + at.getX()
				+ " " + at.getY() + " " + at.getZ() + " " + what));
		}
		System.out.println("XEXT counters" + counters + " | wrong=" + plan.wrongNotes()
			+ " missing=" + plan.missingNotes() + " recessed=" + plan.recessedColumns()
			+ " spanX=" + plan.spanX() + " depth=" + plan.spanZ());
		// dx -> label -> count, where dx is negative past the near wall and positive past the far.
		TreeMap<Integer, TreeMap<String, Integer>> table = new TreeMap<>();
		TreeMap<Integer, TreeMap<String, List<BlockPos>>> where = new TreeMap<>();
		for (Map.Entry<BlockPos, String> cell : world.entrySet()) {
			int x = cell.getKey().getX();
			// Signed distance outward from the nearer wall: positive is past the far wall, negative is
			// past the near wall, and the columns just inside either wall are shown as +-0.x by
			// bracketing them into 100 (far side inside) and -100 (near side inside).
			int mid = (plan.nearWall() + plan.farWall()) / 2;
			int dx;
			if (x > mid) {
				dx = x - plan.farWall();
				if (dx <= 0 && dx >= -INSIDE) {
					dx = 100 + dx;
				} else if (dx <= 0) {
					continue;
				}
			} else {
				dx = x - plan.nearWall();
				if (dx >= 0 && dx <= INSIDE) {
					dx = -100 + dx;
				} else if (dx >= 0) {
					continue;
				}
			}
			String block = cell.getValue();
			int bracket = block.indexOf('[');
			if (bracket >= 0) {
				block = block.substring(0, bracket);
			}
			block = block.replace("minecraft:", "");
			String by = plan.laidBy().getOrDefault(cell.getKey(), "?");
			int space = by.indexOf(' ');
			if (space >= 0) {
				by = by.substring(0, space);
			}
			int plus = by.indexOf('+');
			if (plus >= 0) {
				by = by.substring(0, plus);
			}
			int slash = by.indexOf('/');
			if (slash >= 0) {
				by = by.substring(0, slash);
			}
			String label = by + " / " + block;
			table.computeIfAbsent(dx, k -> new TreeMap<>()).merge(label, 1, Integer::sum);
			where.computeIfAbsent(dx, k -> new TreeMap<>())
				.computeIfAbsent(label, k -> new ArrayList<>()).add(cell.getKey());
		}
		for (Map.Entry<Integer, TreeMap<String, Integer>> row : table.entrySet()) {
			System.out.println("   dx=" + row.getKey());
			for (Map.Entry<String, Integer> entry : row.getValue().entrySet()) {
				List<BlockPos> sites = where.get(row.getKey()).get(entry.getKey());
				BlockPos eg = sites.get(0);
				System.out.println("      " + entry.getValue() + "  " + entry.getKey()
					+ "   e.g. " + eg.getX() + " " + eg.getY() + " " + eg.getZ());
			}
		}
	}
}
