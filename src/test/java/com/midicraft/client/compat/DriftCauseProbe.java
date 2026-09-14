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
 * Where the interleaved mode's drift comes from: the schedule's own plan, or the walk that lays it.
 *
 * <p>{@link SongBuilder#scheduleParities} balances an estimate of each lane's cells. If the estimate
 * it settles on is already lopsided, the drift is planned -- forced by the music, or bought because a
 * seam cost more than the imbalance. If the plan is even and the build still drifts, the walk spent
 * cells the estimate never counted. So for each song: the fixed split's planned imbalance, the
 * schedule's, the seams it bought at the game's price and at the lowest price there is, and a real
 * build whose drift is set against the one the plan predicts (planned imbalance over the longer
 * planned lane, times the build's depth).</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*DriftCauseProbe" -i -Dprobe.sizes=8x1,24x3
 * </pre>
 */
@Tag("sweep")
class DriftCauseProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	/** Worst and mean |A - B| in planned cells over every moment either lane plays, and the totals. */
	private record Balance(long totalA, long totalB, long worst, double mean) {
		long longer() {
			return Math.max(totalA, totalB);
		}
	}

	@Test
	void splitsDriftIntoItsCauses() throws Exception {
		int reseed = Integer.parseInt(text("reseed", "16"));
		String named = text("name", "2 lanes").toLowerCase(Locale.ROOT);
		List<int[]> sizes = new ArrayList<>();
		for (String pair : text("sizes", "8x1,24x3").split(",")) {
			String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
			sizes.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		Gson gson = new Gson();
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		boolean wasDebug = SongBuilder.DEBUG_PASTE;
		boolean wasMarking = SongBuilder.MARK_UNREACHED;
		int wasReseed = SongBuilder.PARITY_MIN_DELAY_BEFORE_RESEED;
		try {
			SongBuilder.DEBUG_PASTE = true;
			SongBuilder.MARK_UNREACHED = false;
			SongBuilder.PARITY_MIN_DELAY_BEFORE_RESEED = reseed;
			System.out.println();
			System.out.println("==== drift causes, reseed " + reseed + " (seam priced at "
				+ Math.max(1, reseed / 8) + " cells) ====");
			System.out.println("   planned cells are the schedule's own estimate (+"
				+ SongBuilder.PARITY_SEAM_CELLS + " a seam); diff = |A - B| at each moment");
			System.out.println();
			System.out.println(String.format("   %-34s %9s %9s | %-24s | %-24s | %5s %5s | %s", "song",
				"events", "flips", "fixed split  A/B  worst", "schedule  A/B  worst mean",
				"plan%", "free", "builds: size  predicted worst -> measured worst (mean)  cols/plan"));
			for (Path file : files) {
				ComposerProject song;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
					song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
						raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
						raw.speedQuarters());
				}
				if (song.name() == null || !song.name().toLowerCase(Locale.ROOT).contains(named)) {
					continue;
				}
				List<SongBuilder.EventNote> notes =
					SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true);
				List<SongBuilder.EventNote> even = notes.stream()
					.filter(note -> Math.floorMod(note.time(), 2) == 0).toList();
				List<SongBuilder.EventNote> odd = notes.stream()
					.filter(note -> Math.floorMod(note.time(), 2) == 1).toList();
				Balance fixed = balance(even, List.of(), odd, List.of());
				SongBuilder.ParitySchedule schedule = SongBuilder.scheduleParities(notes, reseed);
				Balance planned = balance(schedule.laneA(), schedule.flipsA(), schedule.laneB(),
					schedule.flipsB());
				SongBuilder.ParitySchedule free = SongBuilder.scheduleParities(notes, 1);
				int flips = schedule.flipsA().size() + schedule.flipsB().size();
				int freeFlips = free.flipsA().size() + free.flipsB().size();
				StringBuilder builds = new StringBuilder();
				for (int[] size : sizes) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode,
							new SongBuilder.BuildLimits(16, size[0], size[1]));
					} catch (RuntimeException refused) {
						builds.append("  ").append(size[0]).append('x').append(size[1]).append(" refused");
						continue;
					}
					double predicted = planned.longer() == 0 ? 0
						: (double) planned.worst() / planned.longer() * plan.spanZ();
					double[] measured = drift(plan);
					builds.append(String.format("  %dx%d %4.0f -> %4.0f (%3.0f) %4.2f", size[0], size[1],
						predicted, measured[0], measured[1],
						(double) plan.totalColumns() / Math.max(1, planned.totalA() + planned.totalB())));
				}
				System.out.println(String.format(
					"   %-34.34s %4d/%-4d %4d/%-4d | %5d/%-5d %6d    | %5d/%-5d %6d %5.0f | %4.0f%% %5d |%s",
					file.getFileName().toString().replace(".json", ""),
					distinctTimes(schedule.laneA()), distinctTimes(schedule.laneB()),
					schedule.flipsA().size(), schedule.flipsB().size(),
					fixed.totalA(), fixed.totalB(), fixed.worst(),
					planned.totalA(), planned.totalB(), planned.worst(), planned.mean(),
					100.0 * planned.worst() / Math.max(1, planned.longer()), freeFlips, builds));
				if (flips == 0 && freeFlips == 0) {
					System.out.println("      no seam even at the lowest price");
				}
			}
		} finally {
			SongBuilder.DEBUG_PASTE = wasDebug;
			SongBuilder.MARK_UNREACHED = wasMarking;
			SongBuilder.PARITY_MIN_DELAY_BEFORE_RESEED = wasReseed;
		}
	}

	/**
	 * Each half of the game tick on its own: how much it plays, how fast it spends cells, and how
	 * often the other half has sat out a seam's worth of silence right before it -- the only
	 * moments a lane can change parity and take this half's work.
	 */
	@Test
	void parityStreams() throws Exception {
		String named = text("name", "2 lanes").toLowerCase(Locale.ROOT);
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		Gson gson = new Gson();
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		System.out.println();
		System.out.println("==== the two halves of the game tick, song by song ====");
		System.out.println(String.format("   %-34s %4s %6s %6s %6s %6s %7s | %5s %5s %5s %5s %5s | %s",
			"song", "half", "events", "notes", "n/ev", "cells", "c/100gt", "gap<=2", "p50", "p90", "max",
			">=13", "windows (other half silent >=13 gt before this one plays)"));
		for (Path file : files) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			if (song.name() == null || !song.name().toLowerCase(Locale.ROOT).contains(named)) {
				continue;
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true);
			List<List<SongBuilder.EventNote>> halves = List.of(
				notes.stream().filter(note -> Math.floorMod(note.time(), 2) == 0).toList(),
				notes.stream().filter(note -> Math.floorMod(note.time(), 2) == 1).toList());
			int span = notes.isEmpty() ? 1
				: Math.max(1, notes.get(notes.size() - 1).time() - notes.get(0).time());
			List<TreeSet<Integer>> times = new ArrayList<>();
			for (List<SongBuilder.EventNote> half : halves) {
				TreeSet<Integer> set = new TreeSet<>();
				half.forEach(note -> set.add(note.time()));
				times.add(set);
			}
			for (int side = 0; side < 2; side++) {
				List<SongBuilder.EventNote> half = halves.get(side);
				List<Integer> own = new ArrayList<>(times.get(side));
				List<Integer> gaps = new ArrayList<>();
				for (int i = 1; i < own.size(); i++) {
					gaps.add(own.get(i) - own.get(i - 1));
				}
				List<Integer> sorted = new ArrayList<>(gaps);
				java.util.Collections.sort(sorted);
				long cells = trajectory(half, List.of()).isEmpty() ? 0
					: trajectory(half, List.of()).lastEntry().getValue();
				int windows = 0;
				for (int time : own) {
					Integer before = times.get(1 - side).lower(time);
					if (before != null && time - before >= SongBuilder.PARITY_SEAM_GT) {
						windows++;
					}
				}
				System.out.println(String.format(
					"   %-34.34s %4s %6d %6d %6.2f %6d %7.1f | %4.0f%% %5d %5d %5d %5d | %d",
					side == 0 ? file.getFileName().toString().replace(".json", "") : "",
					side == 0 ? "even" : "odd", own.size(), half.size(),
					(double) half.size() / Math.max(1, own.size()), cells, 100.0 * cells / span,
					100.0 * gaps.stream().filter(gap -> gap <= 2).count() / Math.max(1, gaps.size()),
					sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2),
					sorted.isEmpty() ? 0 : sorted.get(sorted.size() * 9 / 10),
					sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1),
					gaps.stream().filter(gap -> gap >= SongBuilder.PARITY_SEAM_GT).count(), windows));
			}
		}
	}

	/**
	 * The pacing planner's model of each machine against the walk that was handed its stretches.
	 *
	 * <p>The planner takes a machine's position at an event to be its dry-walk position plus every
	 * stretch handed so far, and paces the pair level (or within a lane) on that. The real walk
	 * records where each event actually stood. Where the two disagree, the pacing is chasing a
	 * position the build never had.</p>
	 *
	 * <pre>
	 * gradlew sweepTest --tests "*DriftCauseProbe.paceModelVsWalk" -i -Dprobe.sizes=8x1
	 * </pre>
	 */
	@Test
	void paceModelVsWalk() throws Exception {
		String configs = text("configs", "today:PARITY_MIN_DELAY_BEFORE_RESEED=16;"
			+ "level:PARITY_MIN_DELAY_BEFORE_RESEED=16,PACE_AIMS_LEVEL=true;"
			+ "wire+level:PARITY_MIN_DELAY_BEFORE_RESEED=16,PACE_AIMS_LEVEL=true,PACE_BUDGETS_THE_WIRE=true");
		String songs = text("songs", "sunset-2-lanes,illit-do-the-dance-2-lanes,"
			+ "geometry-dash-electroman-adventures-2,yo-ho-yo-ho-riff");
		List<int[]> sizes = new ArrayList<>();
		for (String pair : text("sizes", "8x1,24x3").split(",")) {
			String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
			sizes.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		Gson gson = new Gson();
		boolean wasDebug = SongBuilder.DEBUG_PASTE;
		boolean wasMarking = SongBuilder.MARK_UNREACHED;
		System.out.println();
		System.out.println("==== the pacing planner's model against the real walk ====");
		System.out.println("   positions in path columns (legs x (lane + link) + way into the leg); gap = A - B"
			+ " at each event, each machine at its latest");
		System.out.println(String.format("   %-40s %-12s %8s %8s | %9s %9s | %8s %8s %8s %8s | %s",
			"song / size", "config", "stretchA", "stretchB", "|real-mdl|A", "|real-mdl|B",
			"dry gap", "mdl gap", "real gap", "worst", "world drift worst (mean)"));
		try {
			SongBuilder.DEBUG_PASTE = true;
			SongBuilder.MARK_UNREACHED = false;
			for (String name : songs.split(",")) {
				ComposerProject song;
				try (Reader reader = Files.newBufferedReader(SONGS.resolve(name.strip() + ".json"))) {
					ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
					song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
						raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
						raw.speedQuarters());
				}
				List<SongBuilder.EventNote> notes =
					SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true);
				for (int[] size : sizes) {
					for (String config : configs.split(";")) {
						int colon = config.indexOf(':');
						String label = (colon < 0 ? config : config.substring(0, colon)).strip();
						Flags.Held held = Flags.set(colon < 0 ? "" : config.substring(colon + 1));
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
								notes, mode, new SongBuilder.BuildLimits(16, size[0], size[1]));
							SongBuilder.PaceRecord pace = SongBuilder.LAST_PACE;
							double[] world = drift(plan);
							String where = name.strip() + " " + size[0] + "x" + size[1];
							if (pace == null) {
								System.out.println(String.format("   %-40.40s %-12s  not paced (a seam stood it down)"
									+ "   world %4.0f (%3.0f)", where, label, world[0], world[1]));
								continue;
							}
							long[] modelA = model(pace.dryA(), pace.stretchA());
							long[] modelB = model(pace.dryB(), pace.stretchB());
							double[] gaps = gaps(pace, modelA, modelB);
							System.out.println(String.format(
								"   %-40.40s %-12s %8d %8d | %9.1f %9.1f | %8.1f %8.1f %8.1f %8.0f | %4.0f (%3.0f)",
								where, label, java.util.Arrays.stream(pace.stretchA()).sum(),
								java.util.Arrays.stream(pace.stretchB()).sum(),
								meanAbs(pace.realA(), modelA), meanAbs(pace.realB(), modelB),
								gaps[0], gaps[1], gaps[2], gaps[3], world[0], world[1]));
						} finally {
							held.putBack();
						}
					}
				}
			}
		} finally {
			SongBuilder.DEBUG_PASTE = wasDebug;
			SongBuilder.MARK_UNREACHED = wasMarking;
		}
	}

	/** Dry position plus every stretch handed up to and including each event. */
	private static long[] model(int[] dry, int[] stretch) {
		long[] model = new long[dry.length];
		long added = 0;
		for (int i = 0; i < dry.length; i++) {
			added += stretch[i];
			model[i] = dry[i] + added;
		}
		return model;
	}

	private static double meanAbs(int[] real, long[] model) {
		double total = 0;
		for (int i = 0; i < real.length; i++) {
			total += Math.abs(real[i] - model[i]);
		}
		return total / Math.max(1, real.length);
	}

	/**
	 * Mean |A - B| over events in time order, each machine at its latest event: dry, model and real,
	 * and the worst real one.
	 */
	private static double[] gaps(SongBuilder.PaceRecord pace, long[] modelA, long[] modelB) {
		int ia = 0;
		int ib = 0;
		long dryA = -1;
		long dryB = -1;
		long mA = 0;
		long mB = 0;
		long rA = 0;
		long rB = 0;
		double dry = 0;
		double model = 0;
		double real = 0;
		double worst = 0;
		int counted = 0;
		while (ia < pace.timesA().length || ib < pace.timesB().length) {
			boolean takeA = ib >= pace.timesB().length
				|| ia < pace.timesA().length && pace.timesA()[ia] <= pace.timesB()[ib];
			if (takeA) {
				dryA = pace.dryA()[ia];
				mA = modelA[ia];
				rA = pace.realA()[ia];
				ia++;
			} else {
				dryB = pace.dryB()[ib];
				mB = modelB[ib];
				rB = pace.realB()[ib];
				ib++;
			}
			if (dryA < 0 || dryB < 0) {
				continue;
			}
			counted++;
			dry += Math.abs(dryA - dryB);
			model += Math.abs(mA - mB);
			real += Math.abs(rA - rB);
			worst = Math.max(worst, Math.abs(rA - rB));
		}
		int n = Math.max(1, counted);
		return new double[] {dry / n, model / n, real / n, worst};
	}

	private static int distinctTimes(List<SongBuilder.EventNote> lane) {
		return (int) lane.stream().mapToInt(SongBuilder.EventNote::time).distinct().count();
	}

	/** Each lane's planned cumulative cells, seams added at their flip times, compared moment by moment. */
	private static Balance balance(List<SongBuilder.EventNote> laneA, List<Integer> flipsA,
			List<SongBuilder.EventNote> laneB, List<Integer> flipsB) {
		NavigableMap<Integer, Long> a = trajectory(laneA, flipsA);
		NavigableMap<Integer, Long> b = trajectory(laneB, flipsB);
		TreeSet<Integer> moments = new TreeSet<>(a.keySet());
		moments.addAll(b.keySet());
		long worst = 0;
		double total = 0;
		for (int moment : moments) {
			Map.Entry<Integer, Long> atA = a.floorEntry(moment);
			Map.Entry<Integer, Long> atB = b.floorEntry(moment);
			long diff = Math.abs((atA == null ? 0 : atA.getValue()) - (atB == null ? 0 : atB.getValue()));
			worst = Math.max(worst, diff);
			total += diff;
		}
		return new Balance(a.isEmpty() ? 0 : a.lastEntry().getValue(),
			b.isEmpty() ? 0 : b.lastEntry().getValue(), worst, total / Math.max(1, moments.size()));
	}

	/** Walk ticks to cumulative cells, through the real grouping, with a seam's cells at each flip. */
	private static NavigableMap<Integer, Long> trajectory(List<SongBuilder.EventNote> lane,
			List<Integer> flips) {
		NavigableMap<Integer, Long> cells = new TreeMap<>();
		if (lane.isEmpty()) {
			return cells;
		}
		List<Integer> groupTimes = new ArrayList<>();
		for (SongBuilder.EventNote note : lane) {
			if (groupTimes.isEmpty() || groupTimes.get(groupTimes.size() - 1) != note.time()) {
				groupTimes.add(note.time());
			}
		}
		// Seams by walk tick, the clock the trajectory comes out on.
		TreeSet<Integer> seamTicks = new TreeSet<>();
		for (int flip : flips) {
			seamTicks.add(Math.floorDiv(groupTimes.get(flip), 2) + 1);
		}
		int[][] walked = SongBuilder.laneCellTrajectory(lane);
		for (int index = 0; index < walked[0].length; index++) {
			int tick = walked[0][index];
			long seams = seamTicks.headSet(tick, true).size();
			cells.put(tick, walked[1][index] + seams * SongBuilder.PARITY_SEAM_CELLS);
		}
		return cells;
	}

	/** Worst and mean distance between the machines' notes within a repeater tick of each other. */
	private static double[] drift(SongBuilder.PastePlan plan) {
		NavigableMap<Integer, List<BlockPos>> aAt = new TreeMap<>();
		NavigableMap<Integer, List<BlockPos>> bAt = new TreeMap<>();
		for (Map.Entry<BlockPos, Integer> note : plan.noteTicks().entrySet()) {
			(plan.noteMachines().getOrDefault(note.getKey(), -1) == 1 ? bAt : aAt)
				.computeIfAbsent(note.getValue(), tick -> new ArrayList<>()).add(note.getKey());
		}
		double worst = 0;
		double total = 0;
		int moments = 0;
		for (Map.Entry<Integer, List<BlockPos>> tick : aAt.entrySet()) {
			double widest = -1;
			for (List<BlockPos> there : bAt.subMap(tick.getKey() - 1, true, tick.getKey() + 1, true)
					.values()) {
				for (BlockPos from : tick.getValue()) {
					for (BlockPos to : there) {
						widest = Math.max(widest, from.distSqr(to));
					}
				}
			}
			if (widest >= 0) {
				moments++;
				total += Math.sqrt(widest);
				worst = Math.max(worst, Math.sqrt(widest));
			}
		}
		return new double[] {worst, moments == 0 ? 0 : total / moments};
	}
}
