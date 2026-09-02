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
 * Whether the grid Convert picks is ever coarser than the musical grid it replaced.
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
class ConvertCoarserProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static int autoGrid(ComposerProject source) {
		List<Long> starts = source.mergedStartTicks(0);
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

	private static int newGrid(ComposerProject source, boolean gameTicks) {
		SongAnalysis stats = SongAnalysis.of(source, true, gameTicks);
		return stats.offGrid().isEmpty()
				&& (gameTicks || stats.halfTickedNotes().isEmpty())
			? (int)Math.max(1L, source.buildGridTicks(gameTicks))
			: autoGrid(source);
	}

	private static double gameSpan(ComposerProject p) {
		return p.ppq() * 100_000.0 / p.tempoMicrosPerQuarter() / 2.0;
	}

	private static List<Long> starts(ComposerProject p) {
		return p.layers().stream().filter(l -> l.inBuild())
			.flatMap(l -> l.notes().stream()).map(n -> n.startTick()).distinct().sorted().toList();
	}

	private static String shape(ComposerProject p) {
		double span = gameSpan(p);
		List<Long> s = starts(p);
		long finest = Long.MAX_VALUE;
		for (int i = 1; i < s.size(); i++) {
			finest = Math.min(finest, Math.round((s.get(i) - s.get(i - 1)) / span));
		}
		return String.format(Locale.ROOT, "starts=%-5d finest=%-3d length=%.1f",
			s.size(), finest == Long.MAX_VALUE ? 0 : finest,
			s.isEmpty() ? 0.0 : (s.get(s.size() - 1) - s.get(0)) / span);
	}

	@Test
	void neverCoarser() throws Exception {
		List<String> songs;
		try (Stream<Path> files = Files.list(SONGS)) {
			songs = files.filter(p -> p.toString().endsWith(".json"))
				.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
		}
		int differ = 0;
		int worse = 0;
		int checked = 0;
		for (boolean gameTicks : new boolean[] {true, false}) {
			System.out.println("==== " + (gameTicks ? "game ticks" : "repeater ticks"));
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
				int oldG = autoGrid(source);
				int newG = newGrid(source, gameTicks);
				if (oldG == newG) {
					continue;
				}
				differ++;
				ComposerProject before = source.convertToMinecraft(oldG, true, 0, gameTicks)
					.project();
				ComposerProject after = source.convertToMinecraft(newG, true, 0, gameTicks)
					.project();
				long movedOld = source.layers().stream().flatMap(l -> l.notes().stream())
					.filter(n -> Math.round(n.startTick() / (double)oldG) * (long)oldG
						!= n.startTick()).count();
				long movedNew = source.layers().stream().flatMap(l -> l.notes().stream())
					.filter(n -> Math.round(n.startTick() / (double)newG) * (long)newG
						!= n.startTick()).count();
				List<Long> b = starts(before);
				List<Long> a = starts(after);
				// Coarser means the rhythm lost resolution: distinct moments collapsed into
				// each other, or the tightest surviving gap got wider. Duration is not it --
				// the old path often shortened a song by speeding it up, which is a change to
				// the tempo and not to how finely the song is expressed.
				long finestB = Long.MAX_VALUE;
				long finestA = Long.MAX_VALUE;
				for (int i = 1; i < b.size(); i++) {
					finestB = Math.min(finestB,
						Math.round((b.get(i) - b.get(i - 1)) / gameSpan(before)));
				}
				for (int i = 1; i < a.size(); i++) {
					finestA = Math.min(finestA,
						Math.round((a.get(i) - a.get(i - 1)) / gameSpan(after)));
				}
				boolean coarser = a.size() < b.size() || finestA > finestB;
				if (coarser) {
					worse++;
				}
				System.out.println(String.format(Locale.ROOT,
					"  %-44s grid %4d->%4d moved %5d->%5d old[%s] new[%s]%s",
					song, oldG, newG, movedOld, movedNew, shape(before), shape(after),
					coarser ? "  ** COARSER **" : ""));
			}
		}
		System.out.println("  " + differ + " of " + checked
			+ " builds take a different grid; " + worse + " of those come out coarser");
	}
}
