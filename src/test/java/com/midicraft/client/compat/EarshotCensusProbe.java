package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How far apart the two machines of an interleaved build sound at the same moment, over every
 * song named "2 lanes", at every census size, under several configs in one run.
 *
 * <p>{@link InterleavedDriftProbe} asks the same question without knowing whose note is whose, so a
 * wide chord reads as drift; at a reach of twenty blocks that is no longer noise. This one pairs
 * machine A's notes with machine B's ({@link SongBuilder.PastePlan#noteMachines}). Both lanes are
 * on one clock -- {@code laneTimes} halves the game tick on both -- so a tick here is a repeater
 * tick, two game ticks.</p>
 *
 * <p>A <b>moment</b> is a tick where one machine sounds and the other sounds within the window
 * (in repeater ticks either side). Nought is not the natural window it looks like: the schedule
 * keeps every note of one game tick on one machine, so the two only ever share a repeater tick a
 * game tick apart, and most songs never do. A moment is <b>past reach</b> when some note of one
 * machine and some note of the other stand more than the reach apart in a straight line. Notes of
 * one machine on one tick further apart than the reach are counted on their own, as own past.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*EarshotCensusProbe" -i
 * gradlew sweepTest --tests "*EarshotCensusProbe" -i -Dprobe.reach=20 -Dprobe.windows=0,1,2 -Dprobe.window=1
 * gradlew sweepTest --tests "*EarshotCensusProbe" -i "-Dprobe.configs=game:;fixed:INTERLEAVED_DYNAMIC_PARITY=false"
 * </pre>
 *
 * <p>Plan only, the way the preview plans: under {@code DEBUG_PASTE}, which the user's game has on
 * and which changes how collisions are walked. Prints; asserts nothing.</p>
 */
@Tag("sweep")
class EarshotCensusProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** The fault census's nineteen sizes. */
	private static final String SIZES =
		"8x1,10x1,12x1,16x1,20x1,24x1,32x1,40x1,48x1,8x2,16x2,24x2,8x3,24x3,40x3,16x5,40x5,20x5,8x4";

	/** {@code label:FLAG=value,FLAG=value}, separated by semicolons. */
	private static final String CONFIGS = "pacing off:PARITY_MIN_DELAY_BEFORE_RESEED=16,"
		+ "INTERLEAVED_PACES_THE_LANES=false;"
		+ "pacing on (game):PARITY_MIN_DELAY_BEFORE_RESEED=16,INTERLEAVED_PACES_THE_LANES=true;"
		+ "pacing everywhere:PARITY_MIN_DELAY_BEFORE_RESEED=16,INTERLEAVED_PACES_THE_LANES=true,"
		+ "PACING_STANDS_DOWN_FOR_SEAMS=false";

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private record Song(String name, List<SongBuilder.EventNote> notes) {
	}

	/**
	 * One build at one window.
	 *
	 * @param moments ticks where one machine sounds and the other sounds within the window
	 * @param past moments with a note of each machine further apart than the reach
	 * @param ownPast ticks where two notes of one machine stand further apart than the reach
	 * @param worst the widest distance between the machines at any moment
	 * @param meanWidest the widest distance between the machines, averaged over moments
	 */
	private record Row(String song, int width, int floors, boolean dual, int moments, int past,
			int ownPast, double worst, int worstTick, BlockPos worstFrom, BlockPos worstTo,
			double meanWidest, String refused) {
	}

	private record Summary(String config, int window, int builds, int duals, int refused,
			int buildsPast, long moments, long past, long ownPast, double worst, String worstAt,
			double mean, long depth, long columns, long wrong, long collisions) {
	}

	@Test
	void measuresEarshotAcrossConfigs() throws Exception {
		double reach = Double.parseDouble(text("reach", "20"));
		List<Integer> windows = new ArrayList<>();
		for (String each : text("windows", "0,1,2").split(",")) {
			windows.add(Integer.parseInt(each.strip()));
		}
		int shown = Integer.parseInt(text("window", "1"));
		if (!windows.contains(shown)) {
			windows.add(shown);
		}
		String named = text("name", "2 lanes").toLowerCase(Locale.ROOT);
		List<int[]> sizes = new ArrayList<>();
		for (String pair : text("sizes", SIZES).split(",")) {
			String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
			sizes.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		List<Song> songs = new ArrayList<>();
		Gson gson = new Gson();
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		// The game's own settings, so the notes and the limits are the paste's. See GameSettings.
		GameSettings.Values game = GameSettings.get();
		for (Path file : files) {
			ComposerProject song = GameSettings.project(file);
			if (song.name() == null || !song.name().toLowerCase(Locale.ROOT).contains(named)) {
				continue;
			}
			List<SongBuilder.EventNote> notes = game.notes(song, mode);
			if (!notes.isEmpty()) {
				songs.add(new Song(file.getFileName().toString().replace(".json", ""), notes));
			}
		}
		if (songs.isEmpty()) {
			throw new IllegalArgumentException("no song's name contains \"" + named + "\"");
		}
		boolean wasDebug = SongBuilder.DEBUG_PASTE;
		boolean wasMarking = SongBuilder.MARK_UNREACHED;
		List<Summary> summaries = new ArrayList<>();
		try {
			SongBuilder.DEBUG_PASTE = Boolean.parseBoolean(text("debug",
				String.valueOf(GameSettings.get().debugPaste())));
			SongBuilder.MARK_UNREACHED = false;
			for (String config : text("configs", CONFIGS).split(";")) {
				if (config.isBlank()) {
					continue;
				}
				int colon = config.indexOf(':');
				String label = (colon < 0 ? config : config.substring(0, colon)).strip();
				Flags.Held held = Flags.set(colon < 0 ? "" : config.substring(colon + 1));
				try {
					summaries.addAll(census(label, held.said(), songs, sizes, mode, reach, windows,
						shown));
				} finally {
					held.putBack();
				}
			}
		} finally {
			SongBuilder.DEBUG_PASTE = wasDebug;
			SongBuilder.MARK_UNREACHED = wasMarking;
		}
		System.out.println();
		System.out.println("==== earshot census, reach " + fmt(reach) + ", " + songs.size() + " songs x "
			+ sizes.size() + " sizes, every config " + GameSettings.get().said() + " ====");
		System.out.println(String.format("   %-24s %6s %5s %7s %13s %18s %9s %7s %6s %8s %9s %6s %5s  %s",
			"config", "window", "dual", "refused", "builds past", "moments past", "own past", "worst",
			"mean", "depth", "corridor", "wrong", "coll", "worst at"));
		for (Summary each : summaries) {
			System.out.println(String.format(
				"   %-24s %6d %5d %7d %6d (%3.0f%%) %9d (%5.1f%%) %9d %7s %6.1f %8d %9d %6d %5d  %s",
				each.config(), each.window(), each.duals(), each.refused(), each.buildsPast(),
				100.0 * each.buildsPast() / Math.max(1, each.duals()), each.past(),
				100.0 * each.past() / Math.max(1, each.moments()), each.ownPast(), fmt(each.worst()),
				each.mean(), each.depth(), each.columns(), each.wrong(), each.collisions(),
				each.worstAt()));
		}
		for (Summary each : summaries) {
			System.out.println("EARSHOT " + each.config().replace(' ', '_') + " window=" + each.window()
				+ " duals=" + each.duals() + " buildsPast=" + each.buildsPast() + " moments="
				+ each.moments() + " past=" + each.past() + " ownPast=" + each.ownPast() + " worst="
				+ fmt(each.worst()) + " depth=" + each.depth() + " corridor=" + each.columns()
				+ " wrong=" + each.wrong() + " collisions=" + each.collisions());
		}
	}

	private static List<Summary> census(String label, String said, List<Song> songs,
			List<int[]> sizes, SongBuilder.PasteMode mode, double reach, List<Integer> windows,
			int shown) {
		long started = System.currentTimeMillis();
		Map<Integer, List<Row>> byWindow = new TreeMap<>();
		windows.forEach(window -> byWindow.put(window, new ArrayList<>()));
		// What the config costs, beside what it buys: depth, corridor, and the plan's own faults.
		long[] cost = new long[4];
		for (Song song : songs) {
			for (int[] size : sizes) {
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song.notes(), mode,
						GameSettings.get().limits(size[0], size[1]));
					cost[0] += plan.spanZ();
					cost[1] += plan.totalColumns();
					cost[2] += plan.wrongNotes();
					cost[3] += plan.collisions().size();
				} catch (RuntimeException refused) {
					byWindow.values().forEach(rows -> rows.add(new Row(song.name(), size[0], size[1],
						false, 0, 0, 0, 0, 0, null, null, 0, String.valueOf(refused.getMessage()))));
					continue;
				}
				for (int window : windows) {
					byWindow.get(window).add(measure(song.name(), size, plan, reach, window));
				}
			}
		}
		List<Summary> summaries = new ArrayList<>();
		for (Map.Entry<Integer, List<Row>> entry : byWindow.entrySet()) {
			List<Row> rows = entry.getValue();
			if (entry.getKey() == shown) {
				report(label + said, rows, reach, entry.getKey(),
					(System.currentTimeMillis() - started) / 1000);
			}
			summaries.add(summarise(label, entry.getKey(), rows, cost));
		}
		return summaries;
	}

	private static Summary summarise(String label, int window, List<Row> rows, long[] cost) {
		List<Row> duals = rows.stream().filter(row -> row.refused() == null && row.dual()).toList();
		Row worst = duals.stream().max((a, b) -> Double.compare(a.worst(), b.worst())).orElse(null);
		long moments = duals.stream().mapToLong(Row::moments).sum();
		return new Summary(label, window, rows.size(), duals.size(),
			(int) rows.stream().filter(row -> row.refused() != null).count(),
			(int) duals.stream().filter(row -> row.past() > 0).count(), moments,
			duals.stream().mapToLong(Row::past).sum(), duals.stream().mapToLong(Row::ownPast).sum(),
			worst == null ? 0 : worst.worst(),
			worst == null ? "-" : worst.song() + " " + worst.width() + "x" + worst.floors(),
			duals.stream().mapToDouble(row -> row.meanWidest() * row.moments()).sum()
				/ Math.max(1, moments), cost[0], cost[1], cost[2], cost[3]);
	}

	private static void report(String heading, List<Row> rows, double reach, int window, long seconds) {
		List<Row> duals = rows.stream().filter(row -> row.refused() == null && row.dual()).toList();
		List<Row> pastRows = new ArrayList<>(duals.stream().filter(row -> row.past() > 0).toList());
		pastRows.sort((a, b) -> Double.compare(b.worst(), a.worst()));
		System.out.println();
		System.out.println("==== " + heading + ": " + rows.size() + " builds, reach " + fmt(reach)
			+ ", window " + window + ", " + seconds + "s ====");
		System.out.println("   " + duals.size() + " dual, "
			+ rows.stream().filter(row -> row.refused() == null && !row.dual()).count()
			+ " one machine, " + rows.stream().filter(row -> row.refused() != null).count()
			+ " refused; " + pastRows.size() + " dual builds hold a moment past reach");
		if (!pastRows.isEmpty()) {
			System.out.println();
			System.out.println("---- builds past reach, worst first ----");
			System.out.println(String.format("   %-50s %7s %6s %6s %6s %6s  %s", "song / size",
				"moments", "past", "%", "worst", "mean", "worst pair (tick: one machine | the other)"));
			for (Row row : pastRows) {
				System.out.println(String.format("   %-50s %7d %6d %5.1f%% %6s %6.1f  t%d: %s | %s",
					row.song() + " " + row.width() + "x" + row.floors(), row.moments(), row.past(),
					100.0 * row.past() / Math.max(1, row.moments()), fmt(row.worst()),
					row.meanWidest(), row.worstTick(), FaultView.say(row.worstFrom()),
					FaultView.say(row.worstTo())));
			}
		}
		// One line a song, because one song at eight sizes is one imbalance and not eight.
		TreeMap<String, long[]> bySong = new TreeMap<>();
		for (Row row : duals) {
			long[] tally = bySong.computeIfAbsent(row.song(), key -> new long[5]);
			tally[0]++;
			tally[1] += row.past() > 0 ? 1 : 0;
			tally[2] += row.moments();
			tally[3] += row.past();
			tally[4] = Math.max(tally[4], (long) Math.ceil(row.worst()));
		}
		System.out.println();
		System.out.println("---- by song ----");
		bySong.forEach((song, tally) -> System.out.println(String.format(
			"   %-50s builds past %2d of %2d   moments past %6d of %7d (%5.1f%%)   worst %3d",
			song, tally[1], tally[0], tally[3], tally[2], 100.0 * tally[3] / Math.max(1, tally[2]),
			tally[4])));
		List<Row> refused = rows.stream().filter(row -> row.refused() != null).toList();
		if (!refused.isEmpty()) {
			System.out.println();
			System.out.println("---- refused ----");
			refused.forEach(row -> System.out.println(String.format("   %-50s %.100s",
				row.song() + " " + row.width() + "x" + row.floors(), row.refused())));
		}
	}

	private static Row measure(String song, int[] size, SongBuilder.PastePlan plan, double reach,
			int window) {
		NavigableMap<Integer, List<BlockPos>> aAt = new TreeMap<>();
		NavigableMap<Integer, List<BlockPos>> bAt = new TreeMap<>();
		for (Map.Entry<BlockPos, Integer> note : plan.noteTicks().entrySet()) {
			// A plan that never named a machine is one machine, and it is machine A's.
			NavigableMap<Integer, List<BlockPos>> side =
				plan.noteMachines().getOrDefault(note.getKey(), -1) == 1 ? bAt : aAt;
			side.computeIfAbsent(note.getValue(), tick -> new ArrayList<>()).add(note.getKey());
		}
		double limit = reach * reach;
		int ownPast = 0;
		for (Map<Integer, List<BlockPos>> side : List.of(aAt, bAt)) {
			for (List<BlockPos> together : side.values()) {
				if (widest(together, together, new BlockPos[2]) > limit) {
					ownPast++;
				}
			}
		}
		TreeSet<Integer> ticks = new TreeSet<>(aAt.keySet());
		ticks.addAll(bAt.keySet());
		int moments = 0;
		int past = 0;
		double worst = 0;
		double total = 0;
		int worstTick = 0;
		BlockPos[] worstPair = {null, null};
		for (int tick : ticks) {
			// What sounds on this tick, against whatever the other machine sounds near it.
			double widest = -1;
			BlockPos[] pair = new BlockPos[2];
			for (boolean fromA : new boolean[] {true, false}) {
				List<BlockPos> here = (fromA ? aAt : bAt).get(tick);
				if (here == null) {
					continue;
				}
				for (List<BlockPos> there : (fromA ? bAt : aAt)
						.subMap(tick - window, true, tick + window, true).values()) {
					BlockPos[] found = new BlockPos[2];
					double apart = widest(here, there, found);
					if (apart > widest) {
						widest = apart;
						pair = found;
					}
				}
			}
			if (widest < 0) {
				continue;
			}
			moments++;
			double blocks = Math.sqrt(widest);
			total += blocks;
			if (blocks > reach) {
				past++;
			}
			if (blocks > worst) {
				worst = blocks;
				worstTick = tick;
				worstPair = pair;
			}
		}
		return new Row(song, size[0], size[1], !aAt.isEmpty() && !bAt.isEmpty(), moments, past,
			ownPast, worst, worstTick, worstPair[0], worstPair[1], moments == 0 ? 0 : total / moments,
			null);
	}

	/** The squared distance of the furthest pair, one end from each list, written into {@code pair}. */
	private static double widest(List<BlockPos> from, List<BlockPos> to, BlockPos[] pair) {
		double widest = -1;
		for (BlockPos a : from) {
			for (BlockPos b : to) {
				double apart = a.distSqr(b);
				if (apart > widest) {
					widest = apart;
					pair[0] = a;
					pair[1] = b;
				}
			}
		}
		return widest;
	}

	private static String fmt(double blocks) {
		return blocks == Math.rint(blocks) ? String.valueOf((long) blocks) : String.format("%.1f", blocks);
	}
}
