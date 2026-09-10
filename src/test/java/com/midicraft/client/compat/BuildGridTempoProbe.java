package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How often the build grid needs a tempo of its own, which Convert does not take.
 *
 * <p>buildGrid returns a grid AND a tempo. Edit &gt; Quantize applies both; Convert asks only for
 * the grid and leaves the tempo to its own snap. Where the two tempos agree that is the same
 * thing by two routes, and where they do not, quantizing to the grid alone leaves notes that are
 * not on whole build ticks until the snap moves the tempo.</p>
 *
 * <pre>
 * gradlew sweepTest --offline --tests "*BuildGridTempoProbe"
 * </pre>
 */
@Tag("sweep")
class BuildGridTempoProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void howOftenTheGridWantsItsOwnTempo() throws Exception {
		List<String> songs;
		try (Stream<Path> files = Files.list(SONGS)) {
			songs = files.filter(p -> p.toString().endsWith(".json"))
				.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
		}
		for (boolean gameTicks : new boolean[] {true, false}) {
			int checked = 0;
			int wantsOwnTempo = 0;
			double worst = 0.0;
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
				ComposerProject viaMenu = source.withQuantizedToBuildTicks(
					java.util.Set.of(), gameTicks).project();
				if (viaMenu.tempoMicrosPerQuarter() != source.tempoMicrosPerQuarter()) {
					wantsOwnTempo++;
					double ratio = viaMenu.tempoMicrosPerQuarter()
						/ (double)source.tempoMicrosPerQuarter();
					worst = Math.max(worst, Math.abs(ratio - 1.0));
					System.out.println(String.format(Locale.ROOT,
						"  %-46s %s: tempo %d -> %d  (%.4fx)", song,
						gameTicks ? "game" : "repeater", source.tempoMicrosPerQuarter(),
						viaMenu.tempoMicrosPerQuarter(), ratio));
				}
			}
			System.out.println("  " + (gameTicks ? "game ticks" : "repeater ticks") + ": "
				+ wantsOwnTempo + " of " + checked
				+ " songs need a tempo the grid alone cannot give, worst move "
				+ String.format(Locale.ROOT, "%.2f%%", worst * 100.0));
		}
	}
}
