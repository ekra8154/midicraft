package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Which build the shed silences, and where in it the signal stops.
 *
 * <p>Shedding a back flank does not touch the path: the note moves from the head to a bus cell that
 * was already there, and the module is the same length. So a dead line is not what it should be able
 * to cause, and the first thing to establish is whether it is one build or many.</p>
 */
@Tag("sweep")
class ShedUnreachedTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.RELOCATES_CONTESTED_NOTE = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void namesEveryBuildTheShedSilences() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		String worst = null;
		int worstFloors = 0;
		int smallest = Integer.MAX_VALUE;
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
				SongBuilder.RELOCATES_CONTESTED_NOTE = true;
				SongBuilder.PastePlan with = build(notes, floors);
				SongBuilder.RELOCATES_CONTESTED_NOTE = false;
				SongBuilder.PastePlan without = build(notes, floors);
				if (with == null) {
					continue;
				}
				int quiet = readAll(placeInWorld(with)).unreachedNotes();
				if (quiet == 0) {
					continue;
				}
				int wasQuiet = without == null ? -1 : readAll(placeInWorld(without)).unreachedNotes();
				System.out.println("SHEDQUIET " + name + " w24 f" + floors + ": " + quiet
					+ " silent with the shed, " + wasQuiet + " without, sheds="
					+ moves(with.padding())
					+ ", " + with.commands().size() + " blocks");
				if (with.commands().size() < smallest) {
					smallest = with.commands().size();
					worst = name;
					worstFloors = floors;
				}
			}
		}
		if (worst == null) {
			System.out.println("SHEDQUIET nothing is silenced at width twenty-four");
			return;
		}
		SongBuilder.RELOCATES_CONTESTED_NOTE = true;
		SongBuilder.PastePlan plan = build(load(worst), worstFloors);
		Map<BlockPos, BlockState> world = placeInWorld(plan);
		NoteMachineReader.Reading reading = readAll(world);
		// The earliest silent note along the build, which is the one whose cause is its own.
		Map<BlockPos, Integer> order = new HashMap<>();
		int index = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			order.putIfAbsent(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), index++);
		}
		List<BlockPos> sorted = new ArrayList<>(reading.unreachedAt());
		sorted.sort((a, b) -> Integer.compare(order.getOrDefault(a, Integer.MAX_VALUE),
			order.getOrDefault(b, Integer.MAX_VALUE)));
		System.out.println("SHEDQUIET ---- smallest: " + worst + " w24 f" + worstFloors + " ----");
		for (int i = 0; i < Math.min(6, sorted.size()); i++) {
			BlockPos at = sorted.get(i);
			System.out.println("SHEDQUIET   silent note at " + at.getX() + " " + at.getY() + " "
				+ at.getZ());
		}
		BlockPos first = sorted.get(0);
		System.out.println(AsciiDiagram.render(
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
			first.offset(-8, -4, -4), first.offset(6, 3, 4),
			AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
	}

	/** Every relocation the plan took, whichever slot it freed and wherever the note went. */
	private static int moves(java.util.Map<String, Integer> padding) {
		return padding.entrySet().stream()
			.filter(pad -> pad.getKey().startsWith("planRelocateTo"))
			.mapToInt(java.util.Map.Entry::getValue).sum();
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes, int floors) {
		try {
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 24, floors));
		} catch (RuntimeException refused) {
			return null;
		}
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
		return NoteMachineReader.read("Shed", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
