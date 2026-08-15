package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Which songs in the library are made of single notes, and at what spacing.
 *
 * <p>The two-rail shape halves a run of small chords, so before any of it is built the question is
 * how much of the library is such a run. A song whose largest chord is one note is the base case
 * ekran asked for -- it exercises the shape and nothing else.</p>
 */
@Tag("sweep")
class SingleNoteCensusTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void namesEverySongMadeOfSmallChords() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		System.out.println(String.format("%-38s %6s %6s %5s %6s %6s %6s %6s",
			"song", "events", "notes", "max", "<=1", "<=3", "gap1", "gap2"));
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			Map<Integer, Integer> sizeByTime = new TreeMap<>();
			for (SongBuilder.EventNote note : notes) {
				sizeByTime.merge(note.time(), 1, Integer::sum);
			}
			int max = 0;
			int ones = 0;
			int smalls = 0;
			for (int size : sizeByTime.values()) {
				max = Math.max(max, size);
				if (size <= 1) {
					ones++;
				}
				if (size <= 3) {
					smalls++;
				}
			}
			List<Integer> times = List.copyOf(sizeByTime.keySet());
			int gap1 = 0;
			int gap2 = 0;
			for (int i = 1; i < times.size(); i++) {
				int gap = times.get(i) - times.get(i - 1);
				if (gap == 1) {
					gap1++;
				}
				if (gap == 2) {
					gap2++;
				}
			}
			System.out.println(String.format("%-38s %6d %6d %5d %6d %6d %6d %6d",
				name, sizeByTime.size(), notes.size(), max, ones, smalls, gap1, gap2));
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
