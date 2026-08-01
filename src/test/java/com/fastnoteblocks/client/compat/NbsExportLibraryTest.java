package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.nbs.NbsReader;
import com.fastnoteblocks.nbs.NbsSong;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every song in the library, written out and read back.
 *
 * <p>The hand-built cases all passed while the feature was broken, because none of them had two
 * notes on one tick in one layer and every real song does. A synthetic song only contains what I
 * thought to put in it; the library contains what ekran actually writes.</p>
 */
class NbsExportLibraryTest {
	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void everySongInTheLibraryReadsBackAsItself(@TempDir Path folder) throws Exception {
		if (!Files.isDirectory(SONGS)) {
			return;
		}
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		List<String> broken = new ArrayList<>();
		int exported = 0;
		for (Path file : files) {
			ComposerProject project;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				project = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			long wanted = project.layers().stream()
				.flatMap(layer -> layer.notes().stream())
				.filter(note -> note.startTick() / Math.max(1, project.ppq() / 4) <= 65_535)
				.count();
			String name = file.getFileName().toString().replace(".json", "");
			try {
				NbsExporter.Result result =
					NbsExporter.export(project, folder.resolve(exported + ".nbs"));
				NbsSong read = NbsReader.read(result.path());
				if (read.notes().size() != wanted) {
					broken.add(name + ": wrote " + wanted + " notes, read back "
						+ read.notes().size());
				}
			} catch (Exception failed) {
				broken.add(name + ": " + failed);
			}
			exported++;
		}
		assertTrue(exported > 0, "no songs were found to export");
		assertEquals(List.of(), broken, "songs that did not survive the round trip");
	}
}
