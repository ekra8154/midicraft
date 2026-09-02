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
 * The speed unit changed from quarters to eighths; every saved song must play at the same speed.
 *
 * <p>A file written before the change carries only {@code speedQuarters}, so the compact
 * constructor doubles it. Getting that wrong halves or doubles every build in the library at once,
 * silently, so it is worth a probe of its own.</p>
 *
 * <pre>
 * gradlew sweepTest --offline --tests "*SpeedEighthsProbe"
 * </pre>
 */
@Tag("sweep")
class SpeedEighthsProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void savedSongsKeepTheirSpeed() throws Exception {
		List<String> songs;
		try (Stream<Path> files = Files.list(SONGS)) {
			songs = files.filter(p -> p.toString().endsWith(".json"))
				.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
		}
		int checked = 0;
		int wrongFactor = 0;
		int wrongDelay = 0;
		for (String song : songs) {
			ComposerProject loaded;
			String raw;
			try (Reader reader = Files.newBufferedReader(SONGS.resolve(song + ".json"))) {
				loaded = new Gson().fromJson(reader, ComposerProject.class);
			} catch (Exception refused) {
				continue;
			}
			raw = Files.readString(SONGS.resolve(song + ".json"));
			if (loaded == null || loaded.noteCount() == 0) {
				continue;
			}
			checked++;
			// What the file says, read straight out of the JSON rather than through the record.
			boolean hasEighths = raw.contains("\"speedEighths\"");
			int storedQuarters = ComposerProject.DEFAULT_SPEED_QUARTERS;
			int at = raw.indexOf("\"speedQuarters\"");
			if (at >= 0) {
				String tail = raw.substring(at + 16).replaceAll("[^0-9-].*", "").trim();
				if (!tail.isEmpty()) {
					storedQuarters = Integer.parseInt(tail);
				}
			}
			double expected = Math.max(1, storedQuarters) / 4.0;
			if (Math.abs(loaded.speedFactor() - expected) > 1.0e-9) {
				wrongFactor++;
				System.out.println(String.format(Locale.ROOT,
					"  %-46s stored quarters=%d eighths=%s -> factor %.3f, wanted %.3f",
					song, storedQuarters, hasEighths, loaded.speedFactor(), expected));
			}
			// And the number the builder actually places: one repeater tick of composer time.
			long oneBeat = loaded.ppq();
			int delay = loaded.buildDelayGameTicks(oneBeat);
			int wanted = (int)Math.max(0L, Math.round(
				oneBeat * loaded.tempoMicrosPerQuarter() / (double)loaded.ppq() / 100_000.0
					* 2.0 / expected));
			if (delay != wanted) {
				wrongDelay++;
				System.out.println(String.format(Locale.ROOT,
					"  %-46s build delay %d, wanted %d", song, delay, wanted));
			}
		}
		System.out.println("  " + checked + " songs: " + wrongFactor
			+ " with the wrong speed factor, " + wrongDelay + " with the wrong build delay");

		// Baking at a half-step. Asked in quarters this is where it went wrong: 1.125x rounds to
		// the default and bakes nothing, 1.375x rounds to 1.25x and bakes the wrong tempo.
		ComposerProject base = new ComposerProject("speed", 96, 500_000,
			List.of(new ComposerProject.Layer("L", "HARP", false, true, true,
				List.of(new ComposerProject.NoteEvent(1, 60, 0, 24L, 100)), null)),
			0, 2, 96, 4, 8, List.of());
		int wrongBake = 0;
		for (int eighths = ComposerProject.MIN_SPEED_EIGHTHS;
				eighths <= ComposerProject.MAX_SPEED_EIGHTHS; eighths++) {
			ComposerProject at = base.withSpeedEighths(eighths);
			ComposerProject baked = at.withBakedSpeed();
			// Baking may not change how the song sounds: same real time for the same music.
			double beforeTicks = at.buildDelayGameTicks(base.ppq());
			double afterTicks = baked.buildDelayGameTicks(base.ppq());
			boolean speedReset = baked.speedEighths() == ComposerProject.DEFAULT_SPEED_EIGHTHS;
			if (!speedReset || Math.abs(beforeTicks - afterTicks) > 1.0) {
				wrongBake++;
				System.out.println(String.format(Locale.ROOT,
					"    %s: %d game ticks -> %d, speed now %d",
					MidicraftConfigSpeedLabel(eighths), (long)beforeTicks, (long)afterTicks,
					baked.speedEighths()));
			}
		}
		System.out.println("  baking at every one of the "
			+ (ComposerProject.MAX_SPEED_EIGHTHS - ComposerProject.MIN_SPEED_EIGHTHS + 1)
			+ " speeds: " + wrongBake + " changed the song");
	}

	private static String MidicraftConfigSpeedLabel(int eighths) {
		return com.midicraft.client.MidicraftConfig.speedLabel(eighths);
	}
}
