package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.SongAnalysis;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Whether Convert moves the tempo of a song that is already on the build grid.
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
class ConvertNoOpProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private static ComposerProject load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			return new Gson().fromJson(reader, ComposerProject.class);
		}
	}

	/** ComposerScreen.automaticConversionGridTicks, at the default 0% percentile. */
	private static int autoGrid(ComposerProject source) {
		List<Long> starts = source.mergedStartTicks(0);
		List<Long> gaps = new java.util.ArrayList<>();
		for (int index = 1; index < starts.size(); index++) {
			long gap = starts.get(index) - starts.get(index - 1);
			if (gap > 0) {
				gaps.add(gap);
			}
		}
		long smallestGap = Long.MAX_VALUE;
		if (!gaps.isEmpty()) {
			gaps.sort(null);
			smallestGap = gaps.get(0);
		}
		if (smallestGap <= Math.max(1, source.ppq() * 3L / 8L)) {
			return Math.max(1, source.ppq() / 4);
		}
		if (smallestGap <= Math.max(1, source.ppq() * 3L / 4L)) {
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
		return autoGrid(source);
	}

	@Test
	void quantizeThenConvert() throws Exception {
		List<String> songs;
		String only = text("song", "");
		if (!only.isEmpty()) {
			songs = List.of(only);
		} else {
			try (Stream<Path> files = Files.list(SONGS)) {
				songs = files.filter(p -> p.toString().endsWith(".json"))
					.map(p -> p.getFileName().toString().replace(".json", ""))
					.sorted().toList();
			}
		}
		int moved = 0;
		int checked = 0;
		for (String song : songs) {
			ComposerProject raw;
			try {
				raw = load(song);
			} catch (Exception refused) {
				continue;
			}
			if (raw == null || raw.noteCount() == 0) {
				continue;
			}
			// Exactly what Edit > Quantize > Game ticks does, on the whole song.
			ComposerProject baked = raw.withBakedSpeed();
			ComposerProject quantized = baked.withQuantized(
				(int)baked.buildGridTicks(true), Set.of());
			SongAnalysis after = SongAnalysis.of(quantized, true, true);
			if (!after.offGrid().isEmpty() || !after.crowdedNotes().isEmpty()) {
				continue;
			}
			checked++;
			// And exactly what Convert for Minecraft (game ticks) does: the musical grid the
			// button picks, not the build grid. AUTO is the default.
			ComposerProject source = quantized.withBakedSpeed();
			int grid = convertGrid(source, true);
			ComposerProject.MinecraftConversion conversion = source.convertToMinecraft(
				grid, true, 0, true);
			// How many notes the grid quantize inside Convert actually moves.
			long shifted = source.layers().stream()
				.flatMap(layer -> layer.notes().stream())
				.filter(note -> Math.max(0L, Math.round(note.startTick() / (double)grid)
					* (long)grid) != note.startTick())
				.count();
			// And what the tempo snap alone would decide, asked of the song as it stands --
			// no quantize in between.
			int snapOnly = source.alignedTempoFor(
				(int)Math.max(1, source.noteSpacing().gridTicks()), true);
			double ratio = conversion.project().tempoMicrosPerQuarter()
				/ (double)source.tempoMicrosPerQuarter();
			double snapOnlyRatio = snapOnly / (double)source.tempoMicrosPerQuarter();
			if (Math.abs(ratio - 1.0) > 1.0e-9) {
				moved++;
				System.out.println(String.format(Locale.ROOT,
					"%-52s ppq=%-4d tempo=%-7d speed=%-3d grid=%-5d spacing=%-5d smallest=%-5d"
						+ " -> tempo=%-7d %.4fx | quantize moves %5d notes | snap alone %.4fx",
					song, source.ppq(), source.tempoMicrosPerQuarter(), source.speedQuarters(),
					grid, source.noteSpacing().gridTicks(),
					source.noteSpacing().smallestGapTicks(),
					conversion.project().tempoMicrosPerQuarter(), ratio, shifted, snapOnlyRatio));
			}
		}
		System.out.println("  " + moved + " of " + checked
			+ " already-quantized songs have their tempo moved by Convert");
	}
}
