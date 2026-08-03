package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: how hard does the top voice have to be weighted before it actually wins?
 *
 * <p>At a weight of three the chooser still sold the melody on six songs of thirty-six -- DELTARUNE
 * Guardian went from 900 melody notes out of range to 1,394 -- because three times a small number
 * loses to one times a large one, and a song's accompaniment is the large one. The question a sweep
 * can answer and an argument cannot is where that stops, and what the rest of the song pays for it.
 * "Songs made worse" is the number to read: a fix that improves a total while hurting six songs is
 * not the fix that was asked for.</p>
 */
@Tag("sweep")
class TransposeWeightSweepTest {
	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");
	private static final Pattern SHIFT = Pattern.compile("\\((in range|([+-]\\d+) oct)\\)$");

	@Test
	void sweeps() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		List<ComposerProject> imports = new ArrayList<>();
		for (Path file : files) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<Layer> restored = new ArrayList<>();
			for (Layer layer : song.layers()) {
				int applied = recordedShift(layer.name());
				restored.add(layer.withNotes(layer.notes().stream()
					.map(note -> note.movedTo(note.startTick(), note.midiNote() - applied))
					.toList()));
			}
			imports.add(new ComposerProject(song.name(), song.ppq(), song.tempoMicrosPerQuarter(),
				restored, 0, song.nextNoteId(), song.endTick(), song.speedQuarters()));
		}

		System.out.println(String.format(Locale.ROOT, "%6s %10s %10s %8s %10s %8s %8s",
			"weight", "melodyOut", "saved%", "melWorse", "totalOut", "total%", "totWorse"));
		for (int weight : new int[] {1, 2, 3, 5, 8, 12, 20, 50, 200, 100_000}) {
			long melodyNow = 0;
			long melodyAfter = 0;
			long totalNow = 0;
			long totalAfter = 0;
			int melodyWorse = 0;
			int totalWorse = 0;
			for (ComposerProject song : imports) {
				ComposerProject.TransposeFit fit = song.bestTransposeIntoRange(weight);
				melodyNow += fit.melodyOutNow();
				melodyAfter += fit.melodyOutAfter();
				totalNow += fit.outNow();
				totalAfter += fit.outAfter();
				if (fit.melodyOutAfter() > fit.melodyOutNow()) {
					melodyWorse++;
				}
				if (fit.outAfter() > fit.outNow()) {
					totalWorse++;
				}
			}
			System.out.println(String.format(Locale.ROOT, "%6d %10d %9.1f%% %8d %10d %7.1f%% %8d",
				weight, melodyAfter,
				100.0 * (melodyNow - melodyAfter) / Math.max(1, melodyNow), melodyWorse,
				totalAfter, 100.0 * (totalNow - totalAfter) / Math.max(1, totalNow), totalWorse));
		}
		System.out.println("baseline melodyOut=" + imports.stream()
			.mapToLong(song -> song.bestTransposeIntoRange(1).melodyOutNow()).sum()
			+ " totalOut=" + imports.stream()
			.mapToLong(song -> song.bestTransposeIntoRange(1).outNow()).sum());
	}

	private static int recordedShift(String name) {
		Matcher matcher = SHIFT.matcher(name.trim());
		if (!matcher.find() || matcher.group(2) == null) {
			return 0;
		}
		return Integer.parseInt(matcher.group(2)) * 12;
	}
}
