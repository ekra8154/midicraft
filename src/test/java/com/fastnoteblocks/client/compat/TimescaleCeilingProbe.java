package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How much of a song's real tempo it can have back, now that a build can half-tick.
 *
 * <p>The reason anyone quantises a song here is to make it slow enough to build, and the reason
 * anyone slows a song down is the same. Both are paid in how much the result still sounds like the
 * music. So the question worth putting to the library is not whether half-ticking works but what it
 * is worth: for each song, the fastest timescale that still builds, before and after.</p>
 *
 * <p>A composition already converted for Minecraft has had its answer decided for it -- its notes
 * were moved onto whatever grid its tempo made whole, and no later measurement can put back what
 * the move discarded. So the ceiling here is a ceiling on the song as saved, which is the honest
 * question to ask of a file. What a fresh import would give is a question for the import.</p>
 */
@Tag("sweep")
class TimescaleCeilingProbe {
	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static ComposerProject load(Path file) throws Exception {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			return new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
	}

	/**
	 * The fastest speed setting that still builds, hunted upward from the saved one.
	 *
	 * <p>{@code speedQuarters} counts quarters of the original tempo and higher is faster, so this
	 * walks it up a quarter at a time and keeps the last one that was buildable. Walking rather
	 * than bisecting because buildability is not guaranteed to be monotonic in the speed -- a
	 * faster setting can land a gap back on the grid that a slower one missed -- and a wrong
	 * ceiling reported confidently is worse than a slow probe.</p>
	 */
	private record Ceiling(int speedQuarters, int lanes) {
	}

	private static Ceiling ceiling(ComposerProject song, boolean allowTwoLanes) {
		int best = 0;
		int lanes = 1;
		for (int speed = Math.max(1, song.speedQuarters()); speed <= 256; speed++) {
			SongAnalysis analysis = SongAnalysis.of(song.withSpeedQuarters(speed), true);
			if (!analysis.buildable() || !allowTwoLanes && analysis.lanesNeeded() > 1) {
				continue;
			}
			if (speed > best) {
				best = speed;
				lanes = analysis.lanesNeeded();
			}
		}
		return new Ceiling(best, lanes);
	}

	@Test
	void saysHowMuchFasterEachSongCanRunNow() throws Exception {
		List<Path> songs = new ArrayList<>();
		try (var listing = Files.list(SONGS)) {
			listing.filter(file -> file.toString().endsWith(".json")).sorted().forEach(songs::add);
		}
		System.out.println();
		System.out.println("==== fastest timescale that still builds ====");
		System.out.println(String.format("%-40s %8s %10s %10s %8s",
			"", "saved", "one lane", "two lanes", "gained"));
		int gained = 0;
		for (Path file : songs) {
			ComposerProject song = load(file);
			Ceiling one = ceiling(song, false);
			Ceiling two = ceiling(song, true);
			boolean better = two.speedQuarters() > one.speedQuarters();
			if (better) {
				gained++;
			}
			// A ceiling of nought means no speed at or above the saved one builds that way at all,
			// which is a different statement from "builds at 0.00x" and has to read as one. It is
			// also the case that made the gain column print Infinity, by dividing by it.
			System.out.println(String.format("%-40s %8.2f %10s %10s %8s",
				song.name().length() > 39 ? song.name().substring(0, 39) : song.name(),
				song.speedQuarters() / 4.0,
				one.speedQuarters() == 0 ? "none" : String.format("%.2f", one.speedQuarters() / 4.0),
				two.speedQuarters() == 0 ? "none" : String.format("%.2f", two.speedQuarters() / 4.0),
				!better ? "-"
					: one.speedQuarters() == 0 ? "only on two"
					: String.format("%.2fx", two.speedQuarters() / (double)one.speedQuarters())));
		}
		System.out.println();
		System.out.println(gained + " of " + songs.size()
			+ " songs can run faster than they could before");
	}

	/**
	 * The one song ekran named, and what its grid actually is.
	 *
	 * <p>Its three saved versions are all the same conversion, so the interesting number is not
	 * which is best but what a game tick is worth at that tempo -- which is what decides how finely
	 * a fresh import of it could be quantised.</p>
	 */
	@Test
	void saysWhatAGameTickIsWorthForADarkZone() throws Exception {
		System.out.println();
		System.out.println("==== A Dark Zone, the grid it sits on ====");
		for (String name : new String[] {"a-dark-zone", "a-dark-zone-2", "a-dark-zone-3"}) {
			ComposerProject song = load(SONGS.resolve(name + ".json"));
			double span = SongAnalysis.redstoneTickSpan(song);
			List<Long> ticks = song.layers().stream()
				.flatMap(layer -> layer.notes().stream())
				.map(ComposerProject.NoteEvent::startTick).distinct().sorted().toList();
			long smallest = 0L;
			for (int index = 1; index < ticks.size(); index++) {
				long gap = ticks.get(index) - ticks.get(index - 1);
				smallest = smallest == 0L ? gap : Math.min(smallest, gap);
			}
			System.out.println(String.format(
				"%-16s ppq %d  tempo %d  speed %.2fx%n"
					+ "    a repeater tick is %.0f composer ticks, a game tick %.0f%n"
					+ "    quarter %d, eighth %d, sixteenth %d, thirty-second %d%n"
					+ "    smallest gap in the song: %d composer ticks (%.2f repeater ticks)",
				name, song.ppq(), song.tempoMicrosPerQuarter(), song.speedQuarters() / 4.0,
				span, span / 2.0,
				song.ppq(), song.ppq() / 2, song.ppq() / 4, song.ppq() / 8,
				smallest, smallest / span));
		}
	}
}
