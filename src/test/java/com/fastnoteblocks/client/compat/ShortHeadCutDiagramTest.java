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
 * The same picture {@code /midicraft asciidiagram} draws, of a cut that opens with a head of five.
 *
 * <p>{@code FRONT_ONLY_CUTS} costs 3,353 note blocks the signal never reaches, and the flag is off
 * on main until that is understood. This renders the blocks either side of the change at the same
 * place, so the transition can be read without standing in front of it.</p>
 */
@Tag("sweep")
class ShortHeadCutDiagramTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.FRONT_ONLY_CUTS = false;
		SongBuilder.SHORT_HEAD_CUT_AT = null;
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	@Test
	void drawsTheCutThatGoesDead() throws Exception {
		// The smallest build that both cuts with a short head and loses notes to it.
		String song = null;
		int bestWidth = 0;
		int bestFloors = 0;
		int smallest = Integer.MAX_VALUE;
		java.util.List<Path> files;
		try (java.util.stream.Stream<Path> listing = Files.list(SONGS)) {
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
			for (int floors = 2; floors <= 6; floors++) {
				int width = 24;
				SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(4, width, floors);
				SongBuilder.PastePlan off;
				SongBuilder.PastePlan on;
				try {
					SongBuilder.FRONT_ONLY_CUTS = false;
					off = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE, limits);
					SongBuilder.FRONT_ONLY_CUTS = true;
					SongBuilder.SHORT_HEAD_CUT_AT = null;
					on = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE, limits);
				} catch (RuntimeException refused) {
					continue;
				}
				if (SongBuilder.SHORT_HEAD_CUT_AT == null
						|| on.commands().size() >= smallest) {
					continue;
				}
				// Only where the short head is what broke it: clean without, dead with.
				if (readAll(placeInWorld(off)).unreachedNotes() != 0
						|| readAll(placeInWorld(on)).unreachedNotes() == 0) {
					continue;
				}
				smallest = on.commands().size();
				song = name;
				bestWidth = width;
				bestFloors = floors;
			}
		}
		if (song == null) {
			System.out.println("CUTPIC no build both cut short and lost notes");
			return;
		}
		System.out.println("CUTPIC " + song + " w" + bestWidth + " f" + bestFloors
			+ " (" + smallest + " blocks)");
		List<SongBuilder.EventNote> notes = load(song);
		for (int on = 1; on >= 0; on--) {
			SongBuilder.FRONT_ONLY_CUTS = on == 1;
			SongBuilder.SHORT_HEAD_CUT_AT = null;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, bestWidth, bestFloors));
			// Where the wire runs out, not where the cut opens. The commands are in build order, so
			// the stretch between one repeater and the next is the run between them -- and the cell
			// where that run passes fifteen is the first block the signal cannot reach.
			BlockPos at = on == 1 ? deadRun(plan) : SHOWN;
			if (at == null) {
				System.out.println("CUTPIC no run past fifteen; nothing to draw");
				return;
			}
			if (on == 1) {
				SHOWN = at;
			}
			Map<BlockPos, BlockState> world = placeInWorld(plan);
			NoteMachineReader.Reading reading = readAll(world);
			System.out.println("CUTPIC ---- FRONT_ONLY_CUTS=" + (on == 1)
				+ " unreached=" + reading.unreachedNotes()
				+ " blocks=" + plan.commands().size() + " ----");
			// The plan slides when it is finished so nothing lands behind the player, and the walk's
			// coordinates are pre-slide. The commands are post-slide, so the box has to be too.
			System.out.println(AsciiDiagram.render(
				position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
				at.offset(-6, -3, -4), at.offset(6, 4, 4),
				AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
		}
	}

	/** The first cell whose run from the last repeater passes fifteen, which is where it goes dead. */
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

	private static BlockPos SHOWN;

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
		return NoteMachineReader.read("Short head cut", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
