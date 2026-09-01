package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The dynamic parity scheduler measured over the library, before any block moves.
 *
 * <p>Two numbers decide whether lanes that change parity are worth building: the corridor is as
 * deep as the longer lane, and the playheads stand apart by the lanes' length difference at each
 * moment -- so for every song this prints the longer lane under the fixed split and under the
 * schedule, the worst momentary imbalance under both, and what the schedule spent to get there in
 * seams. Also checks the schedule's own contract: every note assigned exactly once, and every
 * parity change on a lane sitting in at least the seam gap of lane silence.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*ParityScheduleProbe"
 * </pre>
 */
@Tag("sweep")
class ParityScheduleProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** The census's synthetic mixed-parity stress: solo songs replayed at double speed. */
	private static Set<String> doubled() {
		return Set.of(System.getProperty("probe.double",
			"guardian25,guardian30,illit-do-the-dance,moonlight-sonata-3rd-movement,"
				+ "harder-better-faster-stronger-daft-punk,hopes-and-dreams,he-s-a-pirate,"
				+ "zoltraak,wellerman,jackpot-thefatrat").split(","));
	}

	private static int lastCell(int[][] trajectory) {
		int[] cells = trajectory[1];
		return cells.length == 0 ? 0 : cells[cells.length - 1];
	}

	/** Worst momentary length difference between two lanes, cells, off their trajectories. */
	private static int worstDrift(int[][] a, int[][] b) {
		int ia = 0;
		int ib = 0;
		int cellsA = 0;
		int cellsB = 0;
		int worst = 0;
		while (ia < a[0].length || ib < b[0].length) {
			boolean takeA = ib >= b[0].length
				|| ia < a[0].length && a[0][ia] <= b[0][ib];
			if (takeA) {
				cellsA = a[1][ia++];
			} else {
				cellsB = b[1][ib++];
			}
			worst = Math.max(worst, Math.abs(cellsA - cellsB));
		}
		return worst;
	}

	/** Seam positions on one lane, verified against the gap contract; returns violations. */
	private static int checkSeams(List<SongBuilder.EventNote> lane, List<Integer> flips) {
		List<Integer> groupTimes = new ArrayList<>();
		for (SongBuilder.EventNote note : lane) {
			if (groupTimes.isEmpty() || groupTimes.get(groupTimes.size() - 1) != note.time()) {
				groupTimes.add(note.time());
			}
		}
		int violations = 0;
		List<Integer> found = new ArrayList<>();
		for (int i = 1; i < groupTimes.size(); i++) {
			int gap = groupTimes.get(i) - groupTimes.get(i - 1);
			if (gap <= 0) {
				violations++;
			}
			if (Math.floorMod(gap, 2) != 0) {
				found.add(i);
				if (gap < SongBuilder.PARITY_SEAM_GT) {
					violations++;
				}
			}
		}
		if (!found.equals(flips)) {
			violations++;
		}
		return violations;
	}

	@Test
	void scheduleTheLibrary() throws Exception {
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			sweep(held);
		} finally {
			held.putBack();
		}
	}

	private void sweep(Flags.Held held) throws Exception {
		List<String> rows = new ArrayList<>();
		int songs = 0;
		int broken = 0;
		long totalSeams = 0;
		long totalFixedMax = 0;
		long totalDynamicMax = 0;
		long dualFixedMax = 0;
		long dualDynamicMax = 0;
		long dualFixedDrift = 0;
		long dualDynamicDrift = 0;
		int dualSongs = 0;
		List<Path> files;
		try (Stream<Path> listed = Files.list(SONGS)) {
			files = listed.filter(file -> file.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			String song = file.getFileName().toString().replace(".json", "");
			List<List<SongBuilder.EventNote>> variants = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			variants.add(notes);
			labels.add(song);
			long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
			if ((evens == 0 || evens == notes.size()) && doubled().contains(song)) {
				variants.add(notes.stream().map(note -> new SongBuilder.EventNote(
					note.time() / 2, note.trackNumber(), note.order(), note.pitch(),
					note.instrumentBlock())).toList());
				labels.add(song + " 2x-2-lane");
			}
			for (int variant = 0; variant < variants.size(); variant++) {
				List<SongBuilder.EventNote> built = variants.get(variant);
				String label = labels.get(variant);
				songs++;
				SongBuilder.ParitySchedule schedule = SongBuilder.scheduleParities(built);
				int seams = schedule.flipsA().size() + schedule.flipsB().size();
				int bad = checkSeams(schedule.laneA(), schedule.flipsA())
					+ checkSeams(schedule.laneB(), schedule.flipsB());
				if (schedule.laneA().size() + schedule.laneB().size() != built.size()) {
					bad++;
				}
				if (bad > 0) {
					broken++;
				}
				List<SongBuilder.EventNote> even = built.stream()
					.filter(note -> Math.floorMod(note.time(), 2) == 0).toList();
				List<SongBuilder.EventNote> odd = built.stream()
					.filter(note -> Math.floorMod(note.time(), 2) == 1).toList();
				boolean dual = !even.isEmpty() && !odd.isEmpty();
				int[][] fixedA = SongBuilder.laneCellTrajectory(even);
				int[][] fixedB = SongBuilder.laneCellTrajectory(odd);
				int[][] dynA = SongBuilder.laneCellTrajectory(schedule.laneA());
				int[][] dynB = SongBuilder.laneCellTrajectory(schedule.laneB());
				// Depth units: a machine's rows land at pitch 3 alone and pitch 6 nested, so a
				// solo song's baseline is its one machine at 3 per cell, and everything else is
				// the longer machine at 6 -- comparable numbers whichever way the song builds.
				int fixedMax = dual ? 6 * Math.max(lastCell(fixedA), lastCell(fixedB))
					: 3 * (lastCell(fixedA) + lastCell(fixedB));
				int dynamicMax = 6 * Math.max(
					lastCell(dynA) + schedule.flipsA().size() * SongBuilder.PARITY_SEAM_CELLS,
					lastCell(dynB) + schedule.flipsB().size() * SongBuilder.PARITY_SEAM_CELLS);
				int fixedDrift = dual ? worstDrift(fixedA, fixedB) : -1;
				int dynamicDrift = worstDrift(dynA, dynB);
				totalSeams += seams;
				totalFixedMax += fixedMax;
				totalDynamicMax += dynamicMax;
				if (dual) {
					dualSongs++;
					dualFixedMax += fixedMax;
					dualDynamicMax += dynamicMax;
					dualFixedDrift += fixedDrift;
					dualDynamicDrift += dynamicDrift;
				}
				rows.add(String.format(
					"%7d %s %s notes=%d longer: fixed=%d dyn=%d (%+.1f%%) seams=%d"
						+ " drift: fixed=%s dyn=%d%s",
					fixedMax - dynamicMax, label, dual ? "dual" : "solo", built.size(),
					fixedMax, dynamicMax,
					fixedMax == 0 ? 0.0 : 100.0 * (dynamicMax - fixedMax) / fixedMax,
					seams, fixedDrift < 0 ? "-" : String.valueOf(fixedDrift), dynamicDrift,
					bad > 0 ? " BROKEN=" + bad : ""));
			}
		}
		rows.sort(java.util.Comparator.reverseOrder());
		rows.forEach(row -> System.out.println("  " + row));
		System.out.println(String.format(
			"PARITY SCHEDULE" + held.said() + ": %d songs (%d dual), %d broken, seams=%d,"
				+ " longer lane fixed=%d dyn=%d (dual only: %d -> %d, %.1f%%),"
				+ " dual drift fixed=%d dyn=%d",
			songs, dualSongs, broken, totalSeams, totalFixedMax, totalDynamicMax,
			dualFixedMax, dualDynamicMax,
			dualFixedMax == 0 ? 0.0 : 100.0 * (dualDynamicMax - dualFixedMax) / dualFixedMax,
			dualFixedDrift, dualDynamicDrift));
	}
}
