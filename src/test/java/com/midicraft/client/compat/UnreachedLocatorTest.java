package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where a machine actually goes quiet, taken from the propagation rather than from counting wire.
 *
 * <p>Counting redstone between repeaters answers a question about lengths and was asked three times
 * today about conduction, which it cannot answer: the stacked module's cross is dust off the signal
 * path, so every run through one reads a cell long. It pointed at blocks where nothing was wrong
 * while the real fault sat elsewhere.</p>
 *
 * <p>{@link NoteMachineReader} already walks the signal. Asking it which note blocks it never
 * reached, and drawing the earliest of them, is the answer the counting was standing in for.</p>
 */
@Tag("sweep")
class UnreachedLocatorTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.FRONT_ONLY_CUTS = false;
		SongBuilder.UNIVERSAL_FOUR_DESCENT = true;
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void locatesWhatTheFourCellDescentLoses() throws Exception {
		// The descent, not the cut: the cut is off on main and is a different fault.
		SongBuilder.FRONT_ONLY_CUTS = false;
		SongBuilder.UNIVERSAL_FOUR_DESCENT = true;
		String worst = null;
		int bestWidth = 0;
		int bestFloors = 0;
		int smallest = Integer.MAX_VALUE;
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			// Width twenty-four only, which is where the library sweep read back and so where the
			// 10,829 were counted. Widening it costs a read-back per build and found nothing.
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 24; width <= 24; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					if (plan.commands().size() >= smallest) {
						continue;
					}
					if (readAll(placeInWorld(plan)).unreachedNotes() == 0) {
						continue;
					}
					smallest = plan.commands().size();
					worst = name;
					bestWidth = width;
					bestFloors = floors;
				}
			}
		}
		if (worst == null) {
			System.out.println("QUIET nothing loses notes at these settings");
			return;
		}
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			load(worst), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, bestWidth, bestFloors));
		Map<BlockPos, BlockState> world = placeInWorld(plan);
		NoteMachineReader.Reading reading = readAll(world);
		List<BlockPos> quiet = reading.unreachedAt();
		System.out.println("QUIET " + worst + " w" + bestWidth + " f" + bestFloors
			+ " (" + plan.commands().size() + " blocks): " + reading.unreachedNotes()
			+ " of " + reading.noteBlocks() + " note blocks never reached");
		// The earliest one along the build, which is the one whose cause is not somebody else's
		// silence. Sorted by where the commands put them, since the commands are in build order.
		Map<BlockPos, Integer> order = new HashMap<>();
		int index = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			order.putIfAbsent(new BlockPos(Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2]), Integer.parseInt(parts[3])), index++);
		}
		List<BlockPos> sorted = new java.util.ArrayList<>(quiet);
		sorted.sort((a, b) -> Integer.compare(order.getOrDefault(a, Integer.MAX_VALUE),
			order.getOrDefault(b, Integer.MAX_VALUE)));
		for (int i = 0; i < Math.min(6, sorted.size()); i++) {
			BlockPos at = sorted.get(i);
			System.out.println("QUIET   silent note at " + at.getX() + " " + at.getY() + " "
				+ at.getZ());
		}
		BlockPos first = sorted.get(0);
		System.out.println("QUIET ---- around the first silent note ----");
		System.out.println(AsciiDiagram.render(
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
			first.offset(-7, -4, -3), first.offset(5, 3, 3),
			AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
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
		return NoteMachineReader.read("Unreached", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
