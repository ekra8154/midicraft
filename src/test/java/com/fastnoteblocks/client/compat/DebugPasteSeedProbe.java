package com.fastnoteblocks.client.compat;

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
 * Scratch: what {@code /midicraft paste ... up ...} builds, seeded the way the command seeds
 * it, so "it will not do an ascent" can be read as a list of what it did instead.
 */
@Tag("sweep")
class DebugPasteSeedProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

	/** The old seed: the wall guessed at two inside the typed width. */
	private static int oldColumn(int width, int toWall) {
		return toWall < 0 ? 0 : Math.max(0, width - 2 - toWall);
	}

	private static void show(String shape, int width, int floors, int toWall, int onFloor,
			String spec) {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		SongBuilder.NAME_EVERY_CELL = true;
		try {
			List<SongBuilder.EventNote> notes = DebugChords.notes(spec, 4);
			SongBuilder.PasteMode mode = SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2;
			int laneWidth = SongBuilder.laneWidthFor(mode, notes, width, floors, ORIGIN);
			int column = toWall < 0 ? 0 : Math.max(0, laneWidth - toWall);
			int floor = onFloor > 0 ? onFloor - 1 : "up".equals(shape) ? 0 : floors - 1;
			int climb = "up".equals(shape) ? 1 : "down".equals(shape) ? -1
				: floor == 0 && floors > 1 ? -1 : 1;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(ORIGIN, notes, mode,
				new SongBuilder.BuildLimits(4, width, floors),
				new SongBuilder.WalkStart(column, floor, climb, false));
			Map<String, Integer> shapes = new TreeMap<>();
			for (String by : plan.laidBy().values()) {
				if (by != null) {
					shapes.merge(by.split(" ")[0], 1, Integer::sum);
				}
			}
			long glass = plan.commands().stream()
				.filter(command -> command.contains("minecraft:glass")).count();
			System.out.println("SEED " + shape + " w" + width + " f" + floors
				+ " toWall=" + (toWall < 0 ? "origin" : toWall)
				+ (onFloor > 0 ? " on" + onFloor : "")
				+ "  lane=" + laneWidth + " col=" + column + "(was " + oldColumn(width, toWall)
				+ ") floor=" + floor + " climb=" + climb
				+ "  glass=" + glass + " breach=" + plan.breaches().size());
			System.out.println("SEED   " + shapes);
		} catch (RuntimeException | Error refused) {
			System.out.println("SEED " + shape + " toWall=" + toWall + "  REFUSED " + refused);
		} finally {
			SongBuilder.NAME_EVERY_CELL = names;
		}
	}

	@Test
	void showsWhatEachSeedBuilds() {
		System.out.println("---- an ascent, at each distance from its wall ----");
		for (int toWall : List.of(0, 1, 2, 3, 6)) {
			show("up", 28, 5, toWall, 0, "25x8");
		}
		System.out.println();
		System.out.println("---- the same, from a floor chosen rather than assumed ----");
		for (int floor : List.of(1, 2, 3, 4)) {
			show("up", 28, 5, 0, floor, "25x8");
		}
		System.out.println();
		System.out.println("---- and a descent ----");
		for (int toWall : List.of(0, 1, 3)) {
			show("down", 28, 5, toWall, 0, "25x8");
		}
	}
}
