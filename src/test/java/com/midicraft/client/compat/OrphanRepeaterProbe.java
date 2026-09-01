package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Repeaters with nothing behind them, which is what a severed lane leaves.
 *
 * <p>The blunt form of the question {@link SeveredLaneProbeTest} asks through the reader. A repeater
 * reads the one cell behind it and nothing else, so a repeater with <b>air</b> there can never fire:
 * everything downstream of it is silent, and every note hung on it stays quiet. One such repeater is
 * expected -- it is where the player throws the lever -- and any beyond that is a break.</p>
 *
 * <p>Counted off the blocks rather than through {@link NoteMachineReader}, deliberately. The reader
 * cannot tell an orphan from a second way into the machine and takes the generous reading, so it is
 * the wrong instrument for measuring how often the generous reading is wrong. This one cannot be
 * fooled that way: it does not care what the signal does, only that a repeater has nothing to read.</p>
 *
 * <p>Set beside {@code railEndedOnAFloorColumn} it answers the question that decides how big the fix
 * has to be -- a run ending on a floor column leaves the path cell above it as air, and whether the
 * lane after it actually dies there is not something the ending itself can say.</p>
 */
@Tag("sweep")
class OrphanRepeaterProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static String text(String key, String fallback) {
		String given = System.getProperty("census." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	/**
	 * The cell a repeater reads, by the same convention {@link NoteMachineReader} uses.
	 *
	 * <p>Copied from {@code startingPoints} rather than worked out again, because the two have to
	 * agree about which side of a repeater is its input and this file has no way to check that
	 * against the game. If that convention is wrong, both are wrong together and the disagreement
	 * this probe exists to find would be hidden by the very thing causing it.</p>
	 */
	private static BlockPos behind(BlockPos repeater, BlockState state) {
		return repeater.relative(state.getValue(RepeaterBlock.FACING));
	}

	@Test
	void countsRepeatersWithNothingToRead() throws Exception {
		List<int[]> sizes = new ArrayList<>();
		for (String pair : text("sizes", "20x5,24x3,40x3,40x5,48x1,128x1").split(",")) {
			String[] half = pair.strip().split("[xX]");
			sizes.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		String only = text("songs", "");
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json"))
				.filter(path -> only.isEmpty() || path.getFileName().toString().contains(only))
				.sorted().toList();
		}
		Gson gson = new Gson();
		TreeMap<String, Integer> orphansBySong = new TreeMap<>();
		int builds = 0;
		int withOrphans = 0;
		int orphans = 0;
		long floorEnds = 0;
		long severed = 0;
		System.out.println();
		System.out.println("==== repeaters with air behind them, one expected per build ====");
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (int[] size : sizes) {
				FaultView.Build built;
				try {
					built = FaultView.of(name, notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
						size[0], size[1], 4, false);
				} catch (RuntimeException refused) {
					continue;
				}
				builds++;
				floorEnds += built.plan().padding()
					.getOrDefault("railEndedOnAFloorColumnPathHop", 0);
				severed += Math.max(0, built.reading().versions() - 1);
				List<BlockPos> starved = new ArrayList<>();
				for (java.util.Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
					if (!cell.getValue().is(Blocks.REPEATER)) {
						continue;
					}
					if (built.at(behind(cell.getKey(), cell.getValue())).isAir()) {
						starved.add(cell.getKey());
					}
				}
				// One is the way in. Anything past that read nothing and drove nothing.
				int extra = Math.max(0, starved.size() - 1);
				if (extra > 0) {
					withOrphans++;
					orphans += extra;
					orphansBySong.merge(name, extra, Integer::sum);
					starved.sort((a, b) -> Integer.compare(built.laid().getOrDefault(a, 0),
						built.laid().getOrDefault(b, 0)));
					System.out.println(String.format("   %-34s %3dw x %df  %d starved repeaters, "
						+ "first past the way in at %s", name, size[0], size[1], extra,
						say(starved.get(1))));
				}
			}
		}
		System.out.println();
		System.out.println("ORPHANS builds=" + builds + " withOrphans=" + withOrphans
			+ " orphanRepeaters=" + orphans + "   againstFloorEnds=" + floorEnds
			+ " readerSevered=" + severed);
		System.out.println("ORPHANS bySong " + orphansBySong);
	}

	private static String say(BlockPos at) {
		return at.getX() + " " + at.getY() + " " + at.getZ();
	}
}
