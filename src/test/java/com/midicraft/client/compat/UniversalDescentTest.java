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
 * The universal four-cell descent: is a descent four everywhere, or only for a cut?
 *
 * <p>The claim is that the two descents differ only in where the wire arrives, and that the cheap
 * one's first rung -- a powered stone with wire on top -- is itself a cell of bus. If that holds,
 * an ordinary turn can take the short way down too, and the {@code +1} step off goes with it.</p>
 *
 * <p>What decides it is not the size: it is {@code wrongNotes()} and the read-back. A descent that
 * saves two cells and does not conduct has saved nothing, and read-backs alone will not say so --
 * they compare note counts, and a note at the wrong tick still sounds.</p>
 */
@Tag("sweep")
class UniversalDescentTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.UNIVERSAL_FOUR_DESCENT = true;
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void pricesItAndReadsTheMachinesBack() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		for (int on = 0; on <= 1; on++) {
			SongBuilder.UNIVERSAL_FOUR_DESCENT = on == 1;
			// The descent alone. This branch still defaults the front-only cut on, so every earlier
			// run of this test priced the two changes together and blamed the descent for both.
			SongBuilder.FRONT_ONLY_CUTS = false;
			long breaches = 0;
			long guardian = 0;
			long other = 0;
			long breachBlocks = 0;
			long spanZ = 0;
			long blocks = 0;
			long wrong = 0;
			long refusals = 0;
			long deadRuns = 0;
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
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan;
						try {
							plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
						} catch (RuntimeException refused) {
							refusals++;
							if (refusals <= 5) {
								System.out.println("FOUR refused on=" + (on == 1) + " " + name
									+ " w" + width + " f" + floors + " :: " + refused.getMessage());
							}
							continue;
						}
						breaches += plan.breaches().size();
						breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						spanZ += plan.spanZ();
						blocks += plan.commands().size();
						wrong += plan.wrongNotes();
						if (name.equals("deltarune-ch-4-guardian")) {
							guardian += plan.breaches().size();
						} else {
							other += plan.breaches().size();
						}
						// A descent that does not conduct shows up as a run of wire past fifteen,
						// which the commands say directly because they are in build order.
						deadRuns += longestRun(plan) > 15 ? 1 : 0;
						if (width == 24) {
							readBuilds++;
							NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
							unreached += reading.unreachedNotes();
							int sounded = 0;
							for (var layer : reading.project().layers()) {
								sounded += layer.notes().size();
							}
							if (sounded != notes.size()) {
								mismatched++;
							}
						}
					}
				}
			}
			System.out.println("FOUR on=" + (on == 1)
				+ " breaches=" + breaches + " (guardian=" + guardian + " other=" + other + ")"
				+ " breachBlocks=" + breachBlocks + " spanZ=" + spanZ + " blocks=" + blocks
				+ " wrong=" + wrong + " refusals=" + refusals + " deadRunBuilds=" + deadRuns);
			System.out.println("FOUR on=" + (on == 1) + " readBuilds=" + readBuilds
				+ " unreached=" + unreached + " mismatched=" + mismatched);
		}
	}

	/** The longest stretch of wire between one repeater and the next, in build order. */
	private static int longestRun(SongBuilder.PastePlan plan) {
		int longest = 0;
		int dust = 0;
		for (String command : plan.commands()) {
			String block = command.split(" ")[4];
			if (block.startsWith("minecraft:redstone_wire")) {
				dust++;
				longest = Math.max(longest, dust);
			} else if (block.startsWith("minecraft:repeater")) {
				dust = 0;
			}
		}
		return longest;
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
		return NoteMachineReader.read("Universal descent", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
