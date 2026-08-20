package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the new verdict says about every song the library actually e, at its own speed and at
 * double.
 *
 * <p>Two questions. The one that was asked: a song that builds today should still read buildable
 * and want one lane, and the same song at twice the timescale should read buildable and want two --
 * that is the whole claim of half-ticking, put in terms of the library rather than of a test song.
 * And the one that has to be asked whenever a verdict changes: how many songs does the new rule
 * judge differently at the speed they are saved at, because that is guidance already relied on,
 * and it moving quietly would be worse than it being wrong.</p>
 */
@Tag("sweep")
class HalfTickVerdictProbe {
	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static ComposerProject load(Path file) throws Exception {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			return new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
	}

	/** The verdict the redstone-tick grid would have given, recomputed from the same gaps. */
	private record Old(int crowded, int offGrid) {
		boolean buildable() {
			return crowded == 0 && offGrid == 0;
		}
	}

	private static Old oldVerdict(SongAnalysis analysis) {
		int crowded = 0;
		int offGrid = 0;
		for (Map.Entry<Long, Double> gap : analysis.gaps().entrySet()) {
			double repeaterTicks = gap.getValue();
			if (repeaterTicks < 1.0 - 1.0e-6) {
				crowded++;
			} else if (Math.abs(repeaterTicks - Math.round(repeaterTicks)) > 0.02) {
				offGrid++;
			}
		}
		return new Old(crowded, offGrid);
	}

	private static String verdict(SongAnalysis analysis) {
		if (!analysis.buildable()) {
			return "NOT BUILDABLE (" + String.join(", ", analysis.problems()) + ")";
		}
		return "READY, " + analysis.lanesNeeded() + " lane"
			+ (analysis.lanesNeeded() == 1 ? " " : "s")
			+ (analysis.halfTickedNotes().isEmpty() ? ""
				: " (" + analysis.halfTickedNotes().size() + " half-ticked)");
	}

	/**
	 * How wrong each interval comes out, in milliseconds, under each projection.
	 *
	 * <p>The claim under all of this is that a build quantised in game ticks is closer to the music
	 * than one quantised in repeater ticks, and that is a number rather than an argument. Rhythm is
	 * intervals, so what is measured is the interval: every gap between consecutive events, against
	 * what the composition says that gap really is.</p>
	 *
	 * <p>Measured at double speed, because that is where a song already in the library starts asking
	 * for timing the repeater grid cannot hold. At its own speed such a song is exact under both, and
	 * the two projections should agree to the millisecond -- which is worth printing too, since a
	 * finer grid that moved a song already sitting on the coarse one would be a bug.</p>
	 */
	@Test
	void measuresHowFarEachProjectionMissesTheMusic() throws Exception {
		System.out.println();
		System.out.println("==== interval error against the composition, ms ====");
		System.out.println(String.format("%-40s %-24s %s", "", "at its own speed", "at double"));
		System.out.println(String.format("%-40s %-24s %s", "",
			"repeater / game (worst)", "repeater / game (worst, mean)"));
		List<Path> songs = new ArrayList<>();
		try (var listing = Files.list(SONGS)) {
			listing.filter(file -> file.toString().endsWith(".json")).sorted().forEach(songs::add);
		}
		double worstRepeater = 0.0;
		double worstGame = 0.0;
		for (Path file : songs) {
			ComposerProject song = load(file);
			Error own = intervalError(song);
			Error doubled = intervalError(
				song.withSpeedQuarters(Math.max(1, song.speedQuarters()) * 2));
			worstRepeater = Math.max(worstRepeater, doubled.repeaterWorst);
			worstGame = Math.max(worstGame, doubled.gameWorst);
			System.out.println(String.format("%-40s %6.1f / %-15.1f %6.1f / %-6.1f  (%4.1f / %4.1f)",
				song.name().length() > 39 ? song.name().substring(0, 39) : song.name(),
				own.repeaterWorst, own.gameWorst,
				doubled.repeaterWorst, doubled.gameWorst,
				doubled.repeaterMean, doubled.gameMean));
		}
		System.out.println();
		System.out.println(String.format(
			"worst interval error at double speed: %.1f ms on repeater ticks, %.1f ms on game ticks",
			worstRepeater, worstGame));
	}

	private record Error(double repeaterWorst, double gameWorst, double repeaterMean,
			double gameMean) {
	}

	/**
	 * Every gap in the song, rounded both ways, against what it really is.
	 *
	 * <p>Walked per layer and per gap because that is how both projections do it -- each gap is
	 * rounded on its own, from the previous event, so a per-gap error is what a listener hears.</p>
	 */
	private static Error intervalError(ComposerProject song) {
		double repeaterWorst = 0.0;
		double gameWorst = 0.0;
		double repeaterTotal = 0.0;
		double gameTotal = 0.0;
		int gaps = 0;
		for (ComposerProject.Layer layer : song.buildLayers(true)) {
			long previous = 0L;
			List<Long> ticks = layer.notes().stream()
				.filter(ComposerProject.NoteEvent::isBuildable)
				.map(ComposerProject.NoteEvent::startTick).distinct().sorted().toList();
			for (long tick : ticks) {
				// The gap's true length in repeater ticks, unrounded: composer ticks to quarters, to
				// microseconds, to tenths of a second, then scaled by the speed the song is set to.
				double real = (tick - previous) / (double)song.ppq()
					* song.tempoMicrosPerQuarter() / 100_000.0
					* 4.0 / Math.max(1, song.speedQuarters());
				double repeater = Math.abs(Math.round(real) - real) * 100.0;
				double game = Math.abs(Math.round(real * 2.0) - real * 2.0) * 50.0;
				repeaterWorst = Math.max(repeaterWorst, repeater);
				gameWorst = Math.max(gameWorst, game);
				repeaterTotal += repeater;
				gameTotal += game;
				gaps++;
				previous = tick;
			}
		}
		int counted = Math.max(1, gaps);
		return new Error(repeaterWorst, gameWorst, repeaterTotal / counted, gameTotal / counted);
	}

	@Test
	void readsTheWholeLibraryAtItsOwnSpeedAndAtDouble() throws Exception {
		List<Path> songs = new ArrayList<>();
		try (var listing = Files.list(SONGS)) {
			listing.filter(file -> file.toString().endsWith(".json")).sorted().forEach(songs::add);
		}
		int oneLane = 0;
		int twoLanes = 0;
		int unbuildable = 0;
		int changedAtOwnSpeed = 0;
		int buildableDoubled = 0;
		int twoLanesDoubled = 0;

		System.out.println();
		System.out.println("==== verdict at the saved speed, and at twice it ====");
		for (Path file : songs) {
			ComposerProject song = load(file);
			SongAnalysis own = SongAnalysis.of(song, true);
			// Higher speedQuarters is faster -- the span multiplies by it -- so this is the song
			// running at twice the timescale, which is the thing half-ticking is meant to survive.
			SongAnalysis doubled = SongAnalysis.of(
				song.withSpeedQuarters(Math.max(1, song.speedQuarters()) * 2), true);
			Old before = oldVerdict(own);

			if (!own.buildable()) {
				unbuildable++;
			} else if (own.lanesNeeded() == 1) {
				oneLane++;
			} else {
				twoLanes++;
			}
			if (before.buildable() != (own.crowded().isEmpty() && own.offGrid().isEmpty())) {
				changedAtOwnSpeed++;
			}
			if (doubled.buildable()) {
				buildableDoubled++;
				if (doubled.lanesNeeded() == 2) {
					twoLanesDoubled++;
				}
			}

			System.out.println(String.format("%-44s speed %-3d  own: %-42s doubled: %s",
				song.name().length() > 43 ? song.name().substring(0, 43) : song.name(),
				song.speedQuarters(), verdict(own), verdict(doubled)));
		}

		System.out.println();
		System.out.println("---- " + songs.size() + " songs ----");
		System.out.println("at their own speed:  " + oneLane + " one lane, " + twoLanes
			+ " two lanes, " + unbuildable + " not buildable");
		System.out.println("verdict changed by the new grid at their own speed: " + changedAtOwnSpeed);
		System.out.println("at double speed:     " + buildableDoubled + " of " + songs.size()
			+ " buildable, " + twoLanesDoubled + " of those on two lanes");
	}
}
