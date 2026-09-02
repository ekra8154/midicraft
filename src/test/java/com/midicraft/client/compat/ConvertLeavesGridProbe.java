package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.SongAnalysis;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Whether Convert ever leaves a song off grid -- the job it exists to do.
 *
 * <p>The three together are the acceptance tests for the grid Convert quantizes to: it must
 * leave an already-aligned song alone, must still land every other song on the grid, and must
 * never come back coarser than the musical grid it replaced.</p>
 *
 * <pre>
 * gradlew sweepTest --offline --tests "*Convert*Probe"
 * </pre>
 */
@Tag("sweep")
class ConvertLeavesGridProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** ComposerScreen.automaticConversionGridTicks at the default 0% percentile. */
	private static int autoGrid(ComposerProject source, int mergeTicks) {
		List<Long> starts = source.mergedStartTicks(mergeTicks);
		List<Long> gaps = new ArrayList<>();
		for (int i = 1; i < starts.size(); i++) {
			long gap = starts.get(i) - starts.get(i - 1);
			if (gap > 0) {
				gaps.add(gap);
			}
		}
		long smallest = Long.MAX_VALUE;
		if (!gaps.isEmpty()) {
			gaps.sort(null);
			smallest = gaps.get(0);
		}
		if (smallest <= Math.max(1, source.ppq() * 3L / 8L)) {
			return Math.max(1, source.ppq() / 4);
		}
		if (smallest <= Math.max(1, source.ppq() * 3L / 4L)) {
			return Math.max(1, source.ppq() / 2);
		}
		return source.ppq();
	}

	/** ComposerScreen.conversionGridTicks: the build grid where the song already sits on it. */
	private static int convertGrid(ComposerProject source, boolean gameTicks) {
		SongAnalysis stats = SongAnalysis.of(source, true, gameTicks);
		if (stats.offGrid().isEmpty()
				&& (gameTicks || stats.halfTickedNotes().isEmpty())) {
			return (int)Math.max(1L, source.buildGridTicks(gameTicks));
		}
		return autoGrid(source, 0);
	}

	@Test
	void afterConvert() throws Exception {
		List<String> songs;
		try (Stream<Path> files = Files.list(SONGS)) {
			songs = files.filter(p -> p.toString().endsWith(".json"))
				.map(p -> p.getFileName().toString().replace(".json", ""))
				.sorted().toList();
		}
		for (boolean gameTicks : new boolean[] {true, false}) {
			int dirty = 0;
			int checked = 0;
			System.out.println("==== Convert for Minecraft ("
				+ (gameTicks ? "game ticks, 2 lanes" : "redstone ticks, 1 lane") + ")");
			for (String song : songs) {
				ComposerProject raw;
				try (Reader reader = Files.newBufferedReader(SONGS.resolve(song + ".json"))) {
					raw = new Gson().fromJson(reader, ComposerProject.class);
				} catch (Exception refused) {
					continue;
				}
				if (raw == null || raw.noteCount() == 0) {
					continue;
				}
				checked++;
				ComposerProject source = raw.withBakedSpeed();
				ComposerProject.MinecraftConversion conversion = source.convertToMinecraft(
					convertGrid(source, gameTicks), true, 0, gameTicks);
				ComposerProject after = conversion.project()
					.withSpeedQuarters(ComposerProject.DEFAULT_SPEED_QUARTERS);
				SongAnalysis stats = SongAnalysis.of(after, true, true);
				if (!stats.offGrid().isEmpty() || !stats.crowdedNotes().isEmpty()) {
					dirty++;
					System.out.println(String.format(Locale.ROOT,
						"  %-50s %5d off grid, %5d too frequent  (of %d notes)",
						song, stats.offGridNotes().size(), stats.crowdedNotes().size(),
						after.noteCount()));
				}
			}
			System.out.println("  " + dirty + " of " + checked
				+ " songs are still not Minecraft-ready after Convert");
		}
	}
}
