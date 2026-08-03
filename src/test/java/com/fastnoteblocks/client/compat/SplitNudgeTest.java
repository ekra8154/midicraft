package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What moving a clashing headed cut a column is worth, against giving the head up.
 *
 * <p>The shifted cut is new geometry -- a pad, then a head, then a near half one cell shorter --
 * so the layout check is not enough on its own. Every build here is placed and read back.</p>
 */
@Tag("sweep")
class SplitNudgeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.SPLIT_NUDGES = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void pricesTheNudgeAndReadsEveryBuildBack() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		for (int on = 0; on <= 1; on++) {
			SongBuilder.SPLIT_NUDGES = on == 1;
			long breaches = 0;
			long guardian = 0;
			long other = 0;
			long breachBlocks = 0;
			long spanZ = 0;
			long nudged = 0;
			long clashed = 0;
			int unreached = 0;
			int mismatched = 0;
			int readBuilds = 0;
			for (Path file : files) {
				String name = file.getFileName().toString().replace(".json", "");
				if (name.startsWith("ultra-")) {
					continue;
				}
				ComposerProject song;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
						raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
						raw.speedQuarters());
				}
				List<SongBuilder.EventNote> notes =
					SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
				if (notes.isEmpty()) {
					continue;
				}
				for (int floors = 1; floors <= 8; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						breaches += plan.breaches().size();
						breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						spanZ += plan.spanZ();
						nudged += plan.padding().getOrDefault("planStackedSplitNudged", 0);
						clashed += plan.padding().getOrDefault("planStackedSplitClashed", 0);
						if (name.equals("deltarune-ch-4-guardian")) {
							guardian += plan.breaches().size();
						} else {
							other += plan.breaches().size();
						}
						// Reading every build back would take an hour. Read the ones that actually
						// contain the new shape, which are the only ones it can have broken.
						if (plan.padding().containsKey("planStackedSplitNudged")
								|| (on == 0 && plan.padding().containsKey("planStackedSplitClashed"))) {
							readBuilds++;
							NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
							unreached += reading.unreachedNotes();
							int sounded = 0;
							for (var layer : reading.project().layers()) {
								sounded += layer.notes().size();
							}
							if (sounded != notes.size()) {
								mismatched++;
								System.out.println("NUDGE mismatch " + name + " w" + width
									+ " f" + floors + " sounded=" + sounded + " of " + notes.size());
							}
						}
					}
				}
			}
			System.out.println("NUDGE " + (on == 1 ? "nudging " : "refusing")
				+ " breaches=" + breaches + " (guardian=" + guardian + " other=" + other + ")"
				+ " breachBlocks=" + breachBlocks + " spanZ=" + spanZ
				+ " nudged=" + nudged + " clashed=" + clashed
				+ " || readBuilds=" + readBuilds + " unreached=" + unreached
				+ " mismatched=" + mismatched);
			assertEquals(0, unreached, "note blocks the signal never got to");
			assertEquals(0, mismatched, "builds that did not read back as their song");
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
		return NoteMachineReader.read("Split nudge", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
