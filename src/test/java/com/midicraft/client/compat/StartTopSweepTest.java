package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the whole library built from the bottom floor and from the top, side by side.
 *
 * <p>The question is not which is better. It is whether starting from the top is a build at all --
 * a top start descends first, and a descent is the dearer turn -- and how far the two part company
 * when the same song is walked both ways.</p>
 */
@Tag("sweep")
class StartTopSweepTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private record Tally(int breaches, int breachBlocks, int worst, int wrong, int dropped,
			int refused, long volume, long length) {
	}

	@Test
	void sweepsBothWaysUp() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		int[] breaches = new int[2];
		int[] breachBlocks = new int[2];
		int[] worst = new int[2];
		int[] wrong = new int[2];
		int[] dropped = new int[2];
		int[] refused = new int[2];
		long[] volume = new long[2];
		long[] length = new long[2];
		TreeMap<String, String> partedBySong = new TreeMap<>();
		TreeMap<String, String> whoWins = new TreeMap<>();
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
			int parted = 0;
			int configs = 0;
			int[] songBreach = new int[2];
			int topWon = 0;
			int bottomWon = 0;
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					configs++;
					long[] blocks = new long[2];
					int[] here = new int[2];
					for (int top = 0; top <= 1; top++) {
						SongBuilder.PastePlan plan;
						try {
							plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors, top == 1));
						} catch (RuntimeException refusedHere) {
							refused[top]++;
							continue;
						}
						blocks[top] = plan.commands().size();
						volume[top] += (long) plan.width() * plan.height() * plan.depth();
						length[top] += plan.width();
						for (int breach : plan.breaches()) {
							breaches[top]++;
							breachBlocks[top] += breach;
							here[top] += breach;
							worst[top] = Math.max(worst[top], breach);
						}
						for (String fault : plan.faults()) {
							if (fault.startsWith("the note")) {
								wrong[top]++;
							} else if (fault.contains("had nowhere to hang")) {
								dropped[top] += Integer.parseInt(fault.split(" ")[0]);
							}
						}
					}
					if (blocks[0] != blocks[1]) {
						parted++;
					}
					songBreach[0] += here[0];
					songBreach[1] += here[1];
					if (here[1] < here[0]) {
						topWon++;
					} else if (here[0] < here[1]) {
						bottomWon++;
					}
				}
			}
			partedBySong.put(name, parted + "/" + configs);
			if (songBreach[0] != 0 || songBreach[1] != 0) {
				whoWins.put(name, "bottom=" + songBreach[0] + " top=" + songBreach[1]
					+ " configsTopWon=" + topWon + " configsBottomWon=" + bottomWon);
			}
		}
		for (int top = 0; top <= 1; top++) {
			System.out.println("TOPSWEEP " + (top == 1 ? "top   " : "bottom")
				+ " breaches=" + breaches[top] + " breachBlocks=" + breachBlocks[top]
				+ " worst=" + worst[top] + " wrong=" + wrong[top] + " dropped=" + dropped[top]
				+ " refused=" + refused[top] + " volume=" + volume[top]
				+ " length=" + length[top]);
		}
		System.out.println("TOPSWEEP partedBySong " + partedBySong);
		whoWins.forEach((song, line) ->
			System.out.println("TOPWINS " + song + " " + line));
	}
}
