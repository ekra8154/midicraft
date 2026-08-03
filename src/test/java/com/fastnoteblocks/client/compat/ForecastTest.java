package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: what the build options screen will say, and how long it takes to work it out.
 *
 * <p>The forecast runs off the render thread, so its cost does not stutter a frame -- but it does
 * decide whether "measuring the build..." is a flicker nobody sees or a state the screen sits in.
 * Worth knowing which before claiming the screen is live.</p>
 */
@Tag("sweep")
class ForecastTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void timesTheForecastOnTheBiggestSongs() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		long worst = 0;
		String worstName = "";
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
			// Warm the JIT the way a screen that has already drawn one forecast would be.
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 32, 4));
			long started = System.nanoTime();
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 32, 4));
			long millis = (System.nanoTime() - started) / 1_000_000;
			if (millis > worst) {
				worst = millis;
				worstName = name;
			}
			if (millis >= 20 || !plan.breaches().isEmpty()) {
				System.out.println("FORECAST " + name + " 32x4: " + millis + "ms, "
					+ plan.spanZ() + " deep, " + plan.breaches().size() + " breaching lanes, worst "
					+ plan.worstBreach() + ", wrong " + plan.wrongNotes()
					+ ", notes " + notes.size());
			}
		}
		System.out.println("FORECAST worst " + worstName + " " + worst + "ms");
		// Guardian, the biggest song here, plans in about 130ms. The bar is set well above that
		// rather than at it: what this guards against is a change that makes planning slow enough
		// for the screen to sit in "measuring the build..." while someone holds down the + button,
		// not the ordinary drift of one machine against another.
		assertTrue(worst < 500, worstName + " takes " + worst
			+ "ms to plan, which is long enough for the build options screen to feel stuck");
	}
}
