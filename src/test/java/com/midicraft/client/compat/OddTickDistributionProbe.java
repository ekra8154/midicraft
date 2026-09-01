package com.midicraft.client.compat;

import com.midicraft.nbs.NbsSong;
import com.midicraft.nbs.NbsReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where the notes would fall if the machine could play on every game tick.
 *
 * <p>A piston extends in three game ticks where every other component takes an even number, so a
 * signal put through one lands on the other parity and every repeater after it keeps it there. That
 * makes a note on an odd game tick buildable, and a note every game tick -- twenty a second --
 * rather than the ten the machine plays now.</p>
 *
 * <p>Whether that is worth an architecture depends entirely on how real songs use it, and the
 * answer is already in the library: an NBS file carries its own tempo, so a song written at more
 * than ten ticks per second is one whose notes do not all land on the machine's grid. This counts
 * them. The three shapes it can come out as want three different builds -- a burst of odd notes
 * wants two snakes, a scatter wants a branching one, and a handful wants neither.</p>
 */
@Tag("sweep")
class OddTickDistributionProbe {
	private static final Path SONGS = Path.of(System.getProperty("user.home"), "Downloads",
		"fast-noteblocks-midi-tests");

	@Test
	void countsWhatWouldLandOnAnOddGameTick() throws Exception {
		List<Path> files;
		try (var listing = Files.list(SONGS)) {
			files = listing.filter(file -> file.toString().toLowerCase(Locale.ROOT).endsWith(".nbs"))
				.sorted()
				.toList();
		}
		long totalNotes = 0;
		long totalOdd = 0;
		int songsWithAny = 0;
		TreeMap<String, double[]> perSong = new TreeMap<>();
		for (Path file : files) {
			NbsSong song;
			try {
				song = NbsReader.read(file);
			} catch (RuntimeException | java.io.IOException unreadable) {
				System.out.println("SKIP " + file.getFileName() + " " + unreadable);
				continue;
			}
			double ticksPerSecond = song.header().ticksPerSecond();
			if (ticksPerSecond <= 0 || song.notes().isEmpty()) {
				continue;
			}
			// A song tick is 20 / tempo game ticks. At ten a second that is two -- always even, so
			// every note lands on the machine's grid. Anything else puts notes between the rungs.
			double gameTicksPerSongTick = 20.0 / ticksPerSecond;
			long odd = 0;
			List<Integer> oddTicks = new ArrayList<>();
			for (NbsSong.Note note : song.notes()) {
				long gameTick = Math.round(note.tick() * gameTicksPerSongTick);
				if ((gameTick & 1L) == 1L) {
					odd++;
					oddTicks.add(note.tick());
				}
			}
			totalNotes += song.notes().size();
			totalOdd += odd;
			songsWithAny += odd > 0 ? 1 : 0;
			// How far apart the odd notes are, in song ticks, so a burst can be told from a scatter.
			int longestQuiet = 0;
			if (!oddTicks.isEmpty()) {
				oddTicks.sort(null);
				int previous = oddTicks.get(0);
				for (int tick : oddTicks) {
					longestQuiet = Math.max(longestQuiet, tick - previous);
					previous = tick;
				}
			}
			perSong.put(file.getFileName().toString(), new double[] {
				song.notes().size(), odd, ticksPerSecond, longestQuiet});
		}
		System.out.println("ODDTOTAL songs=" + perSong.size() + " withOddNotes=" + songsWithAny
			+ " notes=" + totalNotes + " odd=" + totalOdd
			+ " share=" + String.format(Locale.ROOT, "%.2f%%", 100.0 * totalOdd / totalNotes));
		perSong.forEach((name, value) -> {
			if (value[1] > 0) {
				System.out.println(String.format(Locale.ROOT,
					"    %-52s notes=%6.0f odd=%6.0f (%5.1f%%) tempo=%5.2f longestGap=%.0f",
					name.length() > 50 ? name.substring(0, 50) : name,
					value[0], value[1], 100.0 * value[1] / value[0], value[2], value[3]));
			}
		});
		System.out.println("ODDNONE " + perSong.entrySet().stream()
			.filter(entry -> entry.getValue()[1] == 0)
			.count() + " songs have no note off the grid at all");
	}
}
