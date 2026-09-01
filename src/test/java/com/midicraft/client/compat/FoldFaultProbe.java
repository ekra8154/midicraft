package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
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
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			dissect();
		} finally {
			held.putBack();
		}
	}

	private void dissect() throws Exception {
		// Real conditions unless asked: a debug build tolerates collisions, which suppresses the
		// tight-turn rewalks, which is a DIFFERENT build -- its faults are artifacts as often as
		// facts, and this probe has tabled them as facts twice.
		boolean debug = !Boolean.getBoolean("probe.real");
		// Naming ships the real build -- it records who laid each cell and changes nothing --
		// so it stays on even under probe.real, where only the collision tolerance goes.
		SongBuilder.NAME_EVERY_CELL = true;
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
				+ " repeatersOnCorners=" + plan.padding().getOrDefault("REPEATER-ON-CORNER", 0)
				+ " paritySeams=" + plan.padding().getOrDefault("paritySeams", 0)
				+ " seamsLaid=" + plan.padding().getOrDefault("paritySeam", 0)
				+ " aOdd=" + plan.padding().getOrDefault("machineAStartsOdd", 0)
				+ " bEven=" + plan.padding().getOrDefault("machineBStartsEven", 0));
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
					+ plan.padding().getOrDefault("waysIn", 1) + ", versions="
					+ reading.versions() + ")");
				reading.unreachedAt().stream().limit(4).forEach(pos ->
					System.out.println("     unreached at " + pos.getX() + " " + pos.getY()
						+ " " + pos.getZ()));
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
				String[] reachSpot = System.getProperty("probe.reached", "").split(",");
				if (reachSpot.length == 3) {
					BlockPos centre = new BlockPos(Integer.parseInt(reachSpot[0]),
						Integer.parseInt(reachSpot[1]), Integer.parseInt(reachSpot[2]));
					for (String command : plan.commands()) {
						String[] token = command.split(" ", 5);
						BlockPos pos = new BlockPos(Integer.parseInt(token[1]),
							Integer.parseInt(token[2]), Integer.parseInt(token[3]));
						if (Math.abs(pos.getX() - centre.getX()) <= 3
								&& Math.abs(pos.getY() - centre.getY()) <= 2
								&& Math.abs(pos.getZ() - centre.getZ()) <= 3) {
							System.out.println("     "
								+ (reading.reachedAt().contains(pos) ? "LIVE " : "dead ")
								+ command.replace(" replace", ""));
						}
					}
				}
				breaks.stream().limit(6).forEach(pos -> System.out.println("     break at "
					+ pos.getX() + " " + pos.getY() + " " + pos.getZ()));
			}
			if (Boolean.getBoolean("probe.tint")) {
				// Plain ground per row: a row belongs to one machine, so each should be all stone
				// or all andesite, and a dual build should show both.
				Map<Integer, int[]> perRow = new java.util.TreeMap<>();
				for (String command : plan.commands()) {
					String[] token = command.split(" ", 5);
					int z = Integer.parseInt(token[3]);
					BlockPos pos = new BlockPos(Integer.parseInt(token[1]),
						Integer.parseInt(token[2]), z);
					// Stacked chords wear andesite in the shape table, in either machine, so the
					// tint can only be read off the cells the table leaves plain.
					// Only the ground the shape table leaves plain. Every chord family owns a
					// colour of its own -- tuff is a bus, andesite a stacked chord -- so counting
					// a chord's cell reads its shape as a machine. Filtering only the stacked
					// family last time made a solo build report 246 rows of machine B.
					String who = plan.laidBy().getOrDefault(pos, "");
					if (who.startsWith("chord:") || who.startsWith("rail:")) {
						continue;
					}
					// A: stone / stone_bricks. B: tuff / tuff_bricks. Bricks are the odd half.
					if (token[4].startsWith("minecraft:stone ")) {
						perRow.computeIfAbsent(z, row -> new int[4])[0]++;
					} else if (token[4].startsWith("minecraft:stone_bricks ")) {
						perRow.computeIfAbsent(z, row -> new int[4])[1]++;
					} else if (token[4].startsWith("minecraft:tuff ")) {
						perRow.computeIfAbsent(z, row -> new int[4])[2]++;
					} else if (token[4].startsWith("minecraft:tuff_bricks ")) {
						perRow.computeIfAbsent(z, row -> new int[4])[3]++;
					}
				}
				long aRows = perRow.values().stream()
					.filter(c -> c[0] + c[1] > 0 && c[2] + c[3] == 0).count();
				long bRows = perRow.values().stream()
					.filter(c -> c[2] + c[3] > 0 && c[0] + c[1] == 0).count();
				long mixedMachines = perRow.values().stream()
					.filter(c -> c[0] + c[1] > 0 && c[2] + c[3] > 0).count();
				long oddSeen = perRow.values().stream().filter(c -> c[1] + c[3] > 0).count();
				System.out.println("   TINT rows A=" + aRows + " B=" + bRows + " mixed="
					+ mixedMachines + " rowsShowingOddHalf=" + oddSeen);
				perRow.entrySet().stream().limit(8).forEach(row -> System.out.println("     z="
					+ row.getKey() + " stone=" + row.getValue()[0] + " stoneBricks="
					+ row.getValue()[1] + " tuff=" + row.getValue()[2] + " tuffBricks="
					+ row.getValue()[3]));
			}
			if (Boolean.getBoolean("probe.padding")) {
				// What the build spent, biggest first: the delay chain, the pacing dust and the
				// seams are all counted here, and which of them is "a ton" is not a guess.
				plan.padding().entrySet().stream()
					.filter(entry -> entry.getValue() > 20)
					.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
					.limit(24)
					.forEach(entry -> System.out.println("   PAD " + entry.getKey() + " = "
						+ entry.getValue()));
				long wire = plan.commands().stream()
					.filter(command -> command.contains("minecraft:redstone_wire")).count();
				long repeaters = plan.commands().stream()
					.filter(command -> command.contains("minecraft:repeater")).count();
				long noteBlocks = plan.commands().stream()
					.filter(command -> command.contains("minecraft:note_block")).count();
				System.out.println("   CELLS wire=" + wire + " repeaters=" + repeaters
					+ " notes=" + noteBlocks + " commands=" + plan.commands().size());
			}
			if (Boolean.getBoolean("probe.pistons")) {
				for (String command : plan.commands()) {
					if (command.contains("sticky_piston")) {
						System.out.println("   " + command);
					}
				}
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
