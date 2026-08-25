package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What actually stands in front of every stacked cross, asked of finished plans.
 *
 * <p>The quiet-sides work rests on one geometric promise -- something live stands in the cell in
 * front of the cross to drive the two front flanks -- and which shapes keep that promise has been
 * guessed wrong three times in one night: the dot-handover theory, the corkscrew exemption, and a
 * blanket quietSides on the centre-fed cut that turned one wrong note into eighteen driverless
 * ones. So this stops guessing. {@link SongBuilder#MODULE_LOG} records every module the builder
 * lays; this builds a set of songs, then asks the finished plan, per module: what block ended up
 * in the front cell, is it recorded live, which front flanks hold notes, and does each flank have
 * any driver at all -- the front cell, its own-side relaying side, or a live cell the walk put
 * beside it later. One table, grouped by shape family and quietness.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*FrontCellProbe"
 * gradlew sweepTest --tests "*FrontCellProbe" -Dprobe.songs=jackpot-thefatrat -Dprobe.sizes=14x6
 * </pre>
 */
@Tag("sweep")
class FrontCellProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void mapsTheFrontCells() throws Exception {
		String songs = System.getProperty("probe.songs",
			"jvp-raise-up-your-bat-deltarune-chapter-3-wip,sunset-of-seven-suns,"
				+ "ultra-rails-harp-gap2,jackpot-thefatrat,deltarune-ch-4-guardian");
		String sizes = System.getProperty("probe.sizes", "8x3,8x4,12x5,14x6,20x5");
		Map<String, int[]> rows = new TreeMap<>();
		Map<String, String> firstAt = new LinkedHashMap<>();
		for (String song : songs.split(",")) {
			List<SongBuilder.EventNote> notes;
			try {
				notes = BreachView.song(song.strip(), SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2);
			} catch (Exception missing) {
				continue;
			}
			for (String size : sizes.split(",")) {
				String[] half = size.strip().split("x");
				SongBuilder.LOG_MODULES = true;
				SongBuilder.MODULE_LOG.clear();
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
						new SongBuilder.BuildLimits(16, Integer.parseInt(half[0]),
							Integer.parseInt(half[1]), false));
				} finally {
					SongBuilder.LOG_MODULES = false;
				}
				// The plan slides after the walk, so the logged walk coordinates cannot be compared
				// against command coordinates -- but blocks() here is keyed the walk's way, off the
				// plan's own maps, so both sides of the comparison live in walk space.
				Map<BlockPos, String> blocks = new LinkedHashMap<>();
				for (String command : plan.commands()) {
					String[] part = command.split(" ");
					blocks.put(new BlockPos(Integer.parseInt(part[1]), Integer.parseInt(part[2]),
						Integer.parseInt(part[3])), part[4]);
				}
				tally(rows, firstAt, song.strip() + " " + size.strip(), blocks, plan);
			}
		}
		System.out.println("FRONTCELL shape | quiet | frontCell -> flanks with/without a driver");
		rows.forEach((key, counts) -> System.out.println(String.format(
			"FRONTCELL %-70s driven=%-6d bare=%-4d   first bare at %s",
			key, counts[0], counts[1], firstAt.getOrDefault(key, "-"))));
	}

	private static void tally(Map<String, int[]> rows, Map<String, String> firstAt, String build,
			Map<BlockPos, String> blocks, SongBuilder.PastePlan plan) {
		for (Object[] row : new ArrayList<>(SongBuilder.MODULE_LOG)) {
			// The plan slides between the walk and the commands; the log is walk-space and the
			// blocks map is paste-space, so every logged position moves by the plan's own shift.
			BlockPos stand = ((BlockPos) row[0])
				.offset(SongBuilder.LAST_SHIFT_X, 0, SongBuilder.LAST_SHIFT_Z);
			Direction travel = (Direction) row[1];
			Direction across = (Direction) row[2];
			boolean quiet = (Boolean) row[3];
			String label = FaultView.family((String) row[4]);
			BlockPos front = stand.relative(travel, 2);
			String frontBlock = blocks.getOrDefault(front, "EMPTY");
			String frontKind = kind(frontBlock);
			for (Direction out : List.of(across, across.getOpposite())) {
				BlockPos flank = front.relative(out);
				String note = blocks.get(flank);
				if (note == null || !note.startsWith("minecraft:note_block")) {
					continue;
				}
				// A driver is anything beside the flank that carries this module's pulse to it:
				// the front cell (conductor), the same-side side instrument when it relays
				// (conductor at stand+travel+out), or wire directly on or beside it laid later.
				boolean sideRelays = conducts(blocks.get(stand.relative(travel).relative(out)));
				boolean frontDrives = conducts(frontBlock);
				boolean wireBeside = false;
				for (Direction d : Direction.values()) {
					String near = blocks.get(flank.relative(d));
					if (near != null && (near.startsWith("minecraft:redstone_wire")
							|| near.startsWith("minecraft:repeater"))) {
						wireBeside = true;
					}
				}
				String key = label + " | quiet=" + quiet + " | front=" + frontKind;
				int[] counts = rows.computeIfAbsent(key, ignored -> new int[2]);
				if (frontDrives || sideRelays || wireBeside) {
					counts[0]++;
				} else {
					counts[1]++;
					firstAt.putIfAbsent(key, build + " at " + flank.getX() + " " + flank.getY()
						+ " " + flank.getZ());
				}
			}
		}
	}

	private static String kind(String block) {
		if ("EMPTY".equals(block)) {
			return "EMPTY";
		}
		int state = block.indexOf('[');
		String bare = state < 0 ? block : block.substring(0, state);
		return bare.replace("minecraft:", "");
	}

	/** Whether a block in the plan would pass this module's pulse to a note beside it. */
	private static boolean conducts(String block) {
		if (block == null || "minecraft:air".equals(block)) {
			return false;
		}
		return !block.startsWith("minecraft:redstone_wire") && !block.startsWith("minecraft:repeater")
			&& !block.startsWith("minecraft:glass") && !block.contains("slab");
	}
}
