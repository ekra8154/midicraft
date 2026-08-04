package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One of the 319 notes the four-cell descent still misses, drawn rather than described.
 *
 * <p>The last-rung fix took the unreached count from 10,829 to 319. What is left is either a
 * different fault or the same one in a case the fix does not cover, and the way to tell is to look
 * at one -- so this finds the smallest build that still loses notes, walks the commands in build
 * order to the cell where the run passes fifteen, and renders that box.</p>
 */
@Tag("sweep")
class FourDescentDiagramTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void drawsWhatIsLeft() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		String best = null;
		int bestWidth = 0;
		int bestFloors = 0;
		int smallest = Integer.MAX_VALUE;
		int bestUnreached = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 32; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					if (plan.commands().size() >= smallest || deadRun(plan) == null) {
						continue;
					}
					int unreached = readAll(placeInWorld(plan)).unreachedNotes();
					if (unreached == 0) {
						continue;
					}
					smallest = plan.commands().size();
					best = name;
					bestWidth = width;
					bestFloors = floors;
					bestUnreached = unreached;
				}
			}
		}
		if (best == null) {
			System.out.println("LEFT nothing both loses notes and overruns");
			return;
		}
		List<SongBuilder.EventNote> notes = load(best);
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, bestWidth, bestFloors));
		BlockPos at = deadRun(plan);
		System.out.println("LEFT " + best + " w" + bestWidth + " f" + bestFloors
			+ " (" + smallest + " blocks, " + bestUnreached + " unreached) dies at "
			+ at.getX() + " " + at.getY() + " " + at.getZ());
		// What the sixteen cells are made of. A run is only ever too long for one of two reasons --
		// too many cells of bus, or a staircase that spends more than it was charged -- and the
		// y of each cell tells them apart: a bus holds its level, a spiral drops one a rung.
		int dust = 0;
		String openedAt = "?";
		java.util.List<String> run = new java.util.ArrayList<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String block = parts[4];
			if (block.startsWith("minecraft:redstone_wire")) {
				dust++;
				run.add(dust + ":" + parts[1] + "," + parts[2] + "," + parts[3]
					+ (block.contains("north=side") || block.contains("east=side") ? "" : ""));
				if (dust == 16) {
					break;
				}
			} else if (block.startsWith("minecraft:repeater")) {
				openedAt = parts[1] + " " + parts[2] + " " + parts[3];
				dust = 0;
				run.clear();
			}
		}
		System.out.println("LEFT run opened at repeater " + openedAt);
		for (String cell : run) {
			System.out.println("LEFT   " + cell);
		}
		Map<BlockPos, BlockState> world = placeInWorld(plan);
		System.out.println(AsciiDiagram.render(
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
			at.offset(-6, -4, -4), at.offset(6, 3, 4),
			AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
	}

	/** The first cell whose run from the last repeater passes fifteen. */
	private static BlockPos deadRun(SongBuilder.PastePlan plan) {
		int dust = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String block = parts[4];
			if (block.startsWith("minecraft:redstone_wire")) {
				dust++;
				if (dust == 16) {
					return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
						Integer.parseInt(parts[3]));
				}
			} else if (block.startsWith("minecraft:repeater")) {
				dust = 0;
			}
		}
		return null;
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (Exception broken) {
			throw new IllegalStateException(blockState, broken);
		}
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(Map<BlockPos, BlockState> world) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read("Four descent", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
