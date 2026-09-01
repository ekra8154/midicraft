package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
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
 * Scratch probe: would a whole-song semitone transpose have saved any of the octave shifting?
 *
 * <p>Conversion fits a note into the note block's twenty-five semitones by moving it whole octaves,
 * which keeps its pitch class and tears its contour -- a note that was a step below its neighbour
 * comes back an octave above it. Transposing the whole song by a non-octave amount does the
 * opposite: every interval survives exactly and only the key changes. The question is how much of
 * the first the second would replace.</p>
 *
 * <p>The library holds converted songs, so asking it directly is useless -- everything in it is in
 * range by construction, which is what conversion is. But conversion writes the shift it applied
 * into the layer's name, so subtracting it recovers the pitch the import arrived with. That
 * reconstruction is the population worth measuring, and it is the only one on this disk.</p>
 */
@Tag("sweep")
class TransposeFitSweepTest {
	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");
	private static final int LOW = ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE;
	private static final int HIGH = ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE;
	private static final Pattern SHIFT = Pattern.compile("\\((in range|([+-]\\d+) oct)\\)$");

	@Test
	void sweeps() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		long totalNotes = 0;
		long totalShifted = 0;
		long totalLeftOver = 0;
		long totalMelodyNow = 0;
		long totalMelodyAfter = 0;
		int songsSeen = 0;
		int songsWithShifts = 0;
		int songsFullyFixed = 0;
		int songsImproved = 0;
		System.out.println(String.format(Locale.ROOT, "%-42s %7s %8s %5s %8s %6s %6s %6s",
			"song", "notes", "shifted", "span", "leftOver", "trans", "melNow", "melAft"));
		for (Path file : files) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			// The song as it arrived, by undoing the shift conversion recorded in each layer's name.
			List<Layer> restored = new ArrayList<>();
			List<Integer> original = new ArrayList<>();
			long shifted = 0;
			for (Layer layer : song.layers()) {
				int applied = recordedShift(layer.name());
				restored.add(layer.withNotes(layer.notes().stream()
					.map(note -> note.movedTo(note.startTick(), note.midiNote() - applied))
					.toList()));
				if (!layer.buildEnabled()) {
					continue;
				}
				for (NoteEvent note : layer.notes()) {
					original.add(note.midiNote() - applied);
				}
				if (applied != 0) {
					shifted += layer.notes().size();
				}
			}
			if (original.isEmpty()) {
				continue;
			}
			songsSeen++;
			int lowest = original.stream().mapToInt(Integer::intValue).min().orElse(0);
			int highest = original.stream().mapToInt(Integer::intValue).max().orElse(0);

			// The shipped chooser, on the reconstructed import, so this measures the code and not a
			// second opinion about it.
			ComposerProject asImported = new ComposerProject(song.name(), song.ppq(),
				song.tempoMicrosPerQuarter(), restored, 0, song.nextNoteId(), song.endTick(),
				song.speedQuarters());
			ComposerProject.TransposeFit fit = asImported.bestTransposeIntoRange();
			long leftOver = fit.outAfter();
			totalNotes += original.size();
			totalShifted += shifted;
			totalLeftOver += leftOver;
			totalMelodyNow += fit.melodyOutNow();
			totalMelodyAfter += fit.melodyOutAfter();
			if (shifted > 0) {
				songsWithShifts++;
				if (leftOver == 0) {
					songsFullyFixed++;
				}
				if (leftOver < fit.outNow()) {
					songsImproved++;
				}
			}
			System.out.println(String.format(Locale.ROOT, "%-42s %7d %8d %5d %8d %6d %6d %6d",
				trim(song.name()), original.size(), shifted, highest - lowest + 1, leftOver,
				fit.semitones(), fit.melodyOutNow(), fit.melodyOutAfter()));
		}
		System.out.println(String.format(Locale.ROOT,
			"TOTAL songs=%d notes=%d octaveShifted=%d (%.1f%%)"
				+ " stillOutAfterBestTranspose=%d (%.1f%%)",
			songsSeen, totalNotes, totalShifted, 100.0 * totalShifted / Math.max(1, totalNotes),
			totalLeftOver, 100.0 * totalLeftOver / Math.max(1, totalNotes)));
		System.out.println(String.format(Locale.ROOT,
			"TOTAL songsThatNeededShifting=%d transposeWouldImprove=%d transposeWouldRemoveAll=%d",
			songsWithShifts, songsImproved, songsFullyFixed));
		System.out.println(String.format(Locale.ROOT,
			"TOTAL melodyOutOfRange now=%d after=%d (%.1f%% of it saved)",
			totalMelodyNow, totalMelodyAfter,
			100.0 * (totalMelodyNow - totalMelodyAfter) / Math.max(1, totalMelodyNow)));
	}

	/** The octave shift conversion put in a layer's name, in semitones. */
	private static int recordedShift(String name) {
		Matcher matcher = SHIFT.matcher(name.trim());
		if (!matcher.find() || matcher.group(2) == null) {
			return 0;
		}
		return Integer.parseInt(matcher.group(2)) * 12;
	}

	private static long countOut(List<Integer> pitches, int shift) {
		return pitches.stream().filter(pitch -> pitch + shift < LOW || pitch + shift > HIGH).count();
	}

	private static String trim(String name) {
		return name.length() <= 42 ? name : name.substring(0, 39) + "...";
	}
}
