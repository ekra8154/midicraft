package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where an interleaved build's signal actually stops, cell by cell rather than note by note.
 *
 * <p>{@link FaultView#firstBreak} answers with the first note block that never sounded, which for a
 * machine that never started at all is its first note -- true, and no help. This walks the laid
 * cells in walk order and prints the boundary between reached and unreached with the blocks either
 * side of it, so the shape that failed to hand the signal on is the thing on the page.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*InterleavedDeadProbe" -Dprobe.song=neverending-night-2-lanes
 * </pre>
 */
@Tag("sweep")
class InterleavedDeadProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private static String say(FaultView.Build built, BlockPos at) {
		BlockState state = built.at(at);
		String name = state.isAir() ? "air"
			: net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		String shape = built.plan().laidBy().getOrDefault(at, "?");
		return String.format(Locale.ROOT, "%s %d %d %d  %-18s %s",
			built.reading().reachedAt().contains(at) ? "LIVE" : "dead", at.getX(), at.getY(),
			at.getZ(), name, shape);
	}

	@Test
	void whereTheSignalStops() throws Exception {
		String song = text("song", "neverending-night-2-lanes");
		int width = Integer.parseInt(text("width", "24"));
		int floors = Integer.parseInt(text("floors", "3"));
		int around = Integer.parseInt(text("around", "14"));
		Flags.Held held = Flags.set(text("set", ""));
		try {
			System.out.println("   oak_button is in BlockTags.BUTTONS: "
				+ Blocks.OAK_BUTTON.defaultBlockState().is(net.minecraft.tags.BlockTags.BUTTONS)
				+ "   lever is a Blocks constant: " + Blocks.LEVER.defaultBlockState().is(Blocks.LEVER));
			SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
			FaultView.Build built = FaultView.of(song, mode, width, floors, 4, false);
			System.out.println();
			System.out.println("==== " + built.where() + held.said() + " ====");
			System.out.println("   note blocks=" + built.reading().noteBlocks() + "  never fired="
				+ built.reading().unreachedNotes() + "  versions=" + built.reading().versions()
				+ "  machineANotes="
				+ built.plan().padding().getOrDefault(SongBuilder.MACHINE_A_NOTES, -1)
				+ "  machineBNotes="
				+ built.plan().padding().getOrDefault(SongBuilder.MACHINE_B_NOTES, -1));
			for (String warning : built.reading().warnings()) {
				System.out.println("   warning: " + warning);
			}
			// Where the signal actually stopped: a dead cell with a live one beside it. Walk order
			// cannot answer this -- the plan is sorted along the build before it is handed over, so
			// "the first cell laid that was never reached" is the first in *paste* order, which for a
			// machine that snakes through three floors is somewhere in the middle of it. The frontier
			// is a fact about the blocks and needs no order at all.
			Set<BlockPos> powered = built.plan().poweredAt();
			Set<BlockPos> live = built.reading().reachedAt();
			List<BlockPos> frontier = new ArrayList<>();
			for (BlockPos at : powered) {
				if (live.contains(at)) {
					continue;
				}
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					if (live.contains(at.relative(way))) {
						frontier.add(at);
						break;
					}
				}
			}
			frontier.sort(Comparator.comparingInt((BlockPos at) -> at.getZ()).thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX));
			long dead = powered.stream().filter(at -> !live.contains(at)).count();
			System.out.println("   " + powered.size() + " powered cells, " + dead + " never reached, "
				+ frontier.size() + " of them with a live cell beside them");
			// The shape that stopped and the shape that was live next to it, which is the pair a fix
			// has to be about. Grouped, because one rule failing forty times is one thing to fix.
			Map<String, int[]> pairs = new TreeMap<>();
			Map<String, BlockPos> firstOf = new TreeMap<>();
			for (BlockPos at : frontier) {
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					BlockPos beside = at.relative(way);
					if (!live.contains(beside) || !powered.contains(beside)) {
						continue;
					}
					String pair = FaultView.family(built.plan().laidBy().getOrDefault(beside, "?"))
						+ "  =>  " + FaultView.family(built.plan().laidBy().getOrDefault(at, "?"));
					pairs.computeIfAbsent(pair, key -> new int[1])[0]++;
					firstOf.putIfAbsent(pair, at);
				}
			}
			System.out.println("   live shape => dead shape, at the frontier:");
			pairs.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
				.limit(20)
				.forEach(entry -> System.out.println(String.format(Locale.ROOT, "      %4d  %-72s at %s",
					entry.getValue()[0], entry.getKey(), firstOf.get(entry.getKey()).toShortString())));
			// Which rows are dead, as runs of z. A serial machine that breaks once has one run
			// reaching the end; several separate runs mean several breaks, and that is the whole
			// difference between one fix and a family of them.
			java.util.TreeMap<Integer, int[]> byRow = new java.util.TreeMap<>();
			for (BlockPos at : powered) {
				int[] tally = byRow.computeIfAbsent(at.getZ(), key -> new int[2]);
				tally[live.contains(at) ? 0 : 1]++;
			}
			StringBuilder runs = new StringBuilder();
			Integer runStart = null;
			int previous = Integer.MIN_VALUE;
			for (Map.Entry<Integer, int[]> row : byRow.entrySet()) {
				boolean anyDead = row.getValue()[1] > 0;
				if (anyDead && runStart == null) {
					runStart = row.getKey();
				} else if (!anyDead && runStart != null) {
					runs.append(" z").append(runStart).append("..").append(previous);
					runStart = null;
				}
				previous = row.getKey();
			}
			if (runStart != null) {
				runs.append(" z").append(runStart).append("..").append(previous);
			}
			System.out.println("   rows holding a dead cell (build spans z" + byRow.firstKey() + ".."
				+ byRow.lastKey() + "):" + (runs.isEmpty() ? " none" : runs.toString()));
			System.out.println("   the first few frontier cells, in build order along z:");
			for (int index = 0; index < Math.min(frontier.size(), around); index++) {
				System.out.println("     " + say(built, frontier.get(index)));
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					BlockPos beside = frontier.get(index).relative(way);
					if (live.contains(beside) && powered.contains(beside)) {
						System.out.println("         fed from " + way.getName() + ": " + say(built, beside));
					}
				}
			}
			// Sources: what the reader thought it could start from, which is the other half of a
			// machine that never began.
			int redstoneBlocks = 0;
			int buttons = 0;
			for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
				if (cell.getValue().is(Blocks.REDSTONE_BLOCK)) {
					redstoneBlocks++;
				} else if (cell.getValue().is(net.minecraft.tags.BlockTags.BUTTONS)
						|| cell.getValue().is(Blocks.LEVER)) {
					buttons++;
				}
			}
			System.out.println("   sources in the build: " + buttons + " levers/buttons, "
				+ redstoneBlocks + " blocks of redstone");
		} finally {
			held.putBack();
		}
	}
}
