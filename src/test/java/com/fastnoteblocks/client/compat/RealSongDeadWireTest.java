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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Dead wire in a real song at settings a player would actually choose, smallest first.
 *
 * <p>Chainsaw Man pastes and plays, so the fault is not everywhere. What is wanted is a song and a
 * width where it is, small enough to walk to -- with the coordinate the run passes fifteen at, and
 * how many notes go quiet behind it.</p>
 */
@Tag("sweep")
class RealSongDeadWireTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private record Hit(String song, int width, int floors, int blocks, int longest, int unreached,
			String where) {
	}

	@Test
	void findsDeadWireInRealSongs() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		List<Hit> hits = new ArrayList<>();
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
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					BlockPos[] diesAt = {null};
					int longest = longestRun(plan, diesAt);
					if (longest <= 15) {
						continue;
					}
					int unreached = readAll(placeInWorld(plan)).unreachedNotes();
					hits.add(new Hit(name, width, floors, plan.commands().size(), longest,
						unreached, diesAt[0].getX() + " " + diesAt[0].getY() + " "
							+ diesAt[0].getZ()));
				}
			}
		}
		hits.sort((a, b) -> Integer.compare(a.blocks(), b.blocks()));
		System.out.println("REALDEAD " + hits.size() + " builds with a run past fifteen");
		int shown = 0;
		for (Hit hit : hits) {
			// One per song, so the list is a choice of songs rather than one song at ten widths.
			final Hit chosen = hit;
			if (hits.stream().anyMatch(other -> other.song().equals(chosen.song())
					&& other.blocks() < chosen.blocks())) {
				continue;
			}
			System.out.println("REALDEAD " + hit.song() + "  width " + hit.width()
				+ ", floors " + hit.floors()
				+ "  |  " + hit.blocks() + " blocks, longest run " + hit.longest()
				+ ", " + hit.unreached() + " notes never reached, dies at " + hit.where());
			if (++shown >= 12) {
				break;
			}
		}
	}

	private static int longestRun(SongBuilder.PastePlan plan, BlockPos[] diesAt) {
		int longest = 0;
		int dust = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String block = parts[4];
			// The stacked module's cross is dust, but it is not on the path: the signal goes over
			// the top -- repeater, centre block, next cell -- while the cross sits underneath
			// feeding the side relays. Counting it makes every run through a stacked module read
			// one too long, which is how 97 builds looked dead while every one of them played.
			if (block.startsWith("minecraft:redstone_wire[north=side,east=side")) {
				continue;
			}
			if (block.startsWith("minecraft:redstone_wire")) {
				dust++;
				longest = Math.max(longest, dust);
				if (dust == 16 && diesAt[0] == null) {
					diesAt[0] = new BlockPos(Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
				}
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
		return NoteMachineReader.read("Real dead wire", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
