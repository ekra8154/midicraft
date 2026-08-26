package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway: the builds the wait fold broke, collisions tolerated and every cell named. */
@Tag("sweep")
class FoldFaultProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void nameTheCollisions() throws Exception {
		// Real conditions unless asked: a debug build tolerates collisions, which suppresses the
		// tight-turn rewalks, which is a DIFFERENT build -- its faults are artifacts as often as
		// facts, and this probe has tabled them as facts twice.
		boolean debug = !Boolean.getBoolean("probe.real");
		SongBuilder.NAME_EVERY_CELL = debug;
		SongBuilder.DEBUG_PASTE = debug;
		SongBuilder.TRACE_TURNS = Boolean.getBoolean("probe.trace");
		SongBuilder.INTERLEAVED_PACES_THE_LANES = !Boolean.getBoolean("probe.nopace");
		String[][] failing = java.util.Arrays.stream(System.getProperty("probe.builds",
				"field-of-hopes-and-dreams:24x1").split(","))
			.map(spec -> spec.strip().split("[:x]"))
			.toArray(String[][]::new);
		for (String[] build : failing) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(BreachView.songFile(build[0]))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
				new BlockPos(0, 64, 0), Direction.EAST, notes,
				new SongBuilder.BuildLimits(16, Integer.parseInt(build[1]),
					Integer.parseInt(build[2])),
				SongBuilder.WalkStart.HEAD);
			System.out.println("== " + build[0] + " " + build[1] + "x" + build[2]
				+ " wrong=" + plan.wrongNotes() + " missing=" + plan.missingNotes()
				+ " collisions=" + plan.collisions().size()
				+ " span=" + plan.spanX() + " built=" + plan.builtWidth()
				+ " walls=" + plan.nearWall() + ".." + plan.farWall()
				+ " repeatersOnCorners=" + plan.padding().getOrDefault("REPEATER-ON-CORNER", 0));
			plan.padding().entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("REPEATER-ON-CORNER:")
					|| entry.getKey().startsWith("REPEATER-ON-CORNER@"))
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> System.out.println("   " + entry.getKey() + " x"
					+ entry.getValue()));
			if (Boolean.getBoolean("probe.readback")) {
				Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
					new java.util.HashMap<>();
				int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
				int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
				for (String command : plan.commands()) {
					String[] token = command.split(" ", 5);
					BlockPos pos = new BlockPos(Integer.parseInt(token[1]),
						Integer.parseInt(token[2]), Integer.parseInt(token[3]));
					String blockText = token[4].substring(0, token[4].length()
						- " replace".length());
					// Levers for buttons: a bare bootstrap loads no block tags, so a button is
					// not a way in to the reader and its whole machine would read unreached.
					if (blockText.startsWith("minecraft:oak_button")) {
						blockText = "minecraft:lever[face=floor,facing=east,powered=true]";
					}
					world.put(pos, net.minecraft.commands.arguments.blocks.BlockStateParser
						.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
							blockText, false).blockState());
					min[0] = Math.min(min[0], pos.getX());
					min[1] = Math.min(min[1], pos.getY());
					min[2] = Math.min(min[2], pos.getZ());
					max[0] = Math.max(max[0], pos.getX());
					max[1] = Math.max(max[1], pos.getY());
					max[2] = Math.max(max[2], pos.getZ());
				}
				NoteMachineReader.Reading reading = NoteMachineReader.read("FoldFault",
					new BlockPos(min[0] - 1, min[1] - 1, min[2] - 1),
					new BlockPos(max[0] + 1, max[1] + 1, max[2] + 1),
					pos -> world.getOrDefault(pos,
						net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
				int heard = reading.project().layers().stream()
					.mapToInt(layer -> layer.notes().size()).sum();
				// The one fault the reader cannot see: a starved repeater reads as another way in
				// and everything after it counts as reached. Asked of the blocks directly, minus
				// the levers the build is entitled to.
				List<BlockPos> starved = new java.util.ArrayList<>();
				for (Map.Entry<BlockPos,
						net.minecraft.world.level.block.state.BlockState> cell : world.entrySet()) {
					if (cell.getValue().is(net.minecraft.world.level.block.Blocks.REPEATER)
							&& !world.containsKey(cell.getKey().relative(cell.getValue().getValue(
								net.minecraft.world.level.block.RepeaterBlock.FACING)))) {
						starved.add(cell.getKey());
					}
				}
				System.out.println("   READBACK unreached=" + reading.unreachedNotes()
					+ " heard=" + heard + " starved=" + starved.size() + " (levers="
					+ plan.padding().getOrDefault("waysIn", 1) + ")");
				starved.stream().limit(8).forEach(pos -> System.out.println("     starved at "
					+ pos.getX() + " " + pos.getY() + " " + pos.getZ()));
				// The break itself: dead wire touching live wire, which is where the signal
				// actually stopped rather than the first note to go quiet downstream of it.
				java.util.Set<BlockPos> dead = new java.util.HashSet<>();
				for (BlockPos powered : plan.poweredAt()) {
					if (!reading.reachedAt().contains(powered)) {
						dead.add(powered);
					}
				}
				List<BlockPos> breaks = dead.stream()
					.filter(pos -> java.util.stream.Stream.of(net.minecraft.core.Direction.values())
						.map(pos::relative)
						.anyMatch(reading.reachedAt()::contains))
					.sorted(SongBuilder.FaultSites.ORDER)
					.toList();
				System.out.println("     deadWire=" + dead.size() + " breaks=" + breaks.size());
				breaks.stream().limit(6).forEach(pos -> System.out.println("     break at "
					+ pos.getX() + " " + pos.getY() + " " + pos.getZ()));
			}
			String[] spot = System.getProperty("probe.at", "").split(",");
			if (spot.length == 3) {
				BlockPos centre = new BlockPos(Integer.parseInt(spot[0]), Integer.parseInt(spot[1]),
					Integer.parseInt(spot[2]));
				for (String command : plan.commands()) {
					String[] token = command.split(" ", 5);
					BlockPos pos = new BlockPos(Integer.parseInt(token[1]),
						Integer.parseInt(token[2]), Integer.parseInt(token[3]));
					if (Math.abs(pos.getX() - centre.getX()) <= 2
							&& Math.abs(pos.getY() - centre.getY()) <= 2
							&& Math.abs(pos.getZ() - centre.getZ()) <= 2) {
						System.out.println("   " + command + "   <- "
							+ plan.laidBy().getOrDefault(pos, "?"));
					}
				}
			}
			plan.collisions().entrySet().stream().limit(12)
				.forEach(clash -> System.out.println("   " + clash.getKey().getX() + " "
					+ clash.getKey().getY() + " " + clash.getKey().getZ() + " "
					+ clash.getValue()));
			plan.faults().stream()
				.filter(fault -> fault.startsWith("the note"))
				.limit(12)
				.forEach(fault -> System.out.println("   " + fault));
			if (Boolean.getBoolean("probe.extremes")) {
				int minX = plan.commands().stream().mapToInt(c -> Integer.parseInt(c.split(" ")[1]))
					.min().orElse(0);
				int maxX = plan.commands().stream().mapToInt(c -> Integer.parseInt(c.split(" ")[1]))
					.max().orElse(0);
				System.out.println("  extremes minX=" + minX + " maxX=" + maxX);
				for (String command : plan.commands()) {
					int x = Integer.parseInt(command.split(" ")[1]);
					if (x <= minX || x >= maxX) {
						String[] token = command.split(" ", 5);
						BlockPos at = new BlockPos(x, Integer.parseInt(token[2]),
							Integer.parseInt(token[3]));
						System.out.println("   " + command + "   <- "
							+ plan.laidBy().getOrDefault(at, "?"));
					}
				}
			}
			String[] area = System.getProperty("probe.area", "").split("[,x]");
			if (area.length == 4) {
				int x0 = Integer.parseInt(area[0]);
				int x1 = Integer.parseInt(area[1]);
				int z0 = Integer.parseInt(area[2]);
				int z1 = Integer.parseInt(area[3]);
				for (int y = 66; y >= 63; y--) {
					System.out.println("  -- y=" + y);
					for (int z = z0; z <= z1; z++) {
						StringBuilder row = new StringBuilder(String.format("  z=%3d ", z));
						for (int x = x0; x <= x1; x++) {
							String who = plan.laidBy().get(new BlockPos(x, y, z));
							row.append(String.format("%-26.26s",
								who == null ? "." : who.replace("minecraft:", "")));
						}
						System.out.println(row);
					}
				}
			}
		}
		SongBuilder.NAME_EVERY_CELL = false;
		SongBuilder.DEBUG_PASTE = false;
	}
}
