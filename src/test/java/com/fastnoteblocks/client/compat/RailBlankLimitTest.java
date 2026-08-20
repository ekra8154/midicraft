package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How many blanks in a row a run should be allowed, measured rather than argued.
 *
 * <p>The arithmetic says a run holding a blank for every chord costs exactly what the plain lane
 * costs plus its head, so somewhere between "never" and "as many as it likes" is a limit worth
 * having. This builds every song at every limit and prints the depth, which is the figure being
 * optimised: a build is as deep as it has lanes.</p>
 */
@Tag("sweep")
class RailBlankLimitTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static final List<String> SUBJECTS =
		List.of("ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed",
			"song-of-storms-but-noteblocks-dont-kill-me", "lady-brown-nujabes");

	@Test
	void measuresEveryLimit() throws Exception {
		int[] limits = {0, 1, 2, 3, 99};
		System.out.println(String.format("%-44s %6s %8s %8s %8s %8s %8s %8s",
			"song", "w/f", "plain", "b=0", "b=1", "b=2", "b=3", "b=any"));
		int[] total = new int[limits.length];
		int plainTotal = 0;
		for (String name : SUBJECTS) {
			List<SongBuilder.EventNote> notes = load(name);
			for (int floors : new int[] {2, 5, 9}) {
				for (int width : new int[] {16, 24, 40}) {
					SongBuilder.TWO_RAIL_RUNS = false;
					int plain = depth(notes, width, floors);
					SongBuilder.TWO_RAIL_RUNS = true;
					StringBuilder row = new StringBuilder();
					for (int index = 0; index < limits.length; index++) {
						SongBuilder.RAIL_BLANKS_IN_A_ROW = limits[index];
						int depth = depth(notes, width, floors);
						total[index] += depth;
						row.append(String.format(" %8d", depth));
					}
					plainTotal += plain;
					System.out.println(String.format("%-44s %6s %8d%s",
						name, width + "/" + floors, plain, row));
				}
			}
		}
		SongBuilder.RAIL_BLANKS_IN_A_ROW = 1;
		StringBuilder totals = new StringBuilder();
		for (int sum : total) {
			totals.append(String.format(" %8d", sum));
		}
		System.out.println(String.format("%-44s %6s %8d%s", "TOTAL DEPTH", "", plainTotal, totals));
	}

	/** How many lanes deep the build is, or -1 where it refused to build at all. */
	private static int depth(List<SongBuilder.EventNote> notes, int width, int floors) {
		try {
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, width, floors)).spanZ();
		} catch (RuntimeException refused) {
			return -1;
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
}
