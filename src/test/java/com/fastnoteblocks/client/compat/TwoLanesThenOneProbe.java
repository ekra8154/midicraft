package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The case half trading exists for: a song that needs two halves of the tick and then stops.
 *
 * <p>Built here rather than read from the library so the shape is exactly the one being argued
 * about -- ultra ones at gap 2, with every other note of the opening third pushed onto an odd
 * game tick. For that third the song genuinely needs both halves; after it, everything is even
 * again.</p>
 *
 * <p>What should happen is that the lane carrying the odd half gives it up once the odd notes run
 * out, and the two lanes then split the even remainder between them. What is being measured is
 * whether it does: the per-third share is the whole answer, and a final third that reads 100/0 is
 * the failure -- one lane finishing early and the other playing the rest of the song alone.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*TwoLanesThenOneProbe"
 * </pre>
 */
@Tag("sweep")
class TwoLanesThenOneProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Ultra ones at gap 2, with the opening third made to need both halves of the tick.
	 *
	 * <p>Read from the library rather than made here: the same shape is saved as
	 * {@code ultra ones gap2 odd third}, so what this probe argues about and what can be pasted
	 * and listened to are one file. {@code -Dprobe.song=} points it elsewhere.</p>
	 */
	private static List<SongBuilder.EventNote> twoLanesThenOne() throws Exception {
		Path file = Path.of("run", "config", "fast-noteblocks", "songs",
			System.getProperty("probe.song", "ultra-ones-gap2-odd-third") + ".json");
		List<SongBuilder.EventNote> notes;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
				raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			notes = SongBuilder.gameTickEventNotes(project, true);
		}
		return notes;
	}

	private static String share(List<SongBuilder.EventNote> a, List<SongBuilder.EventNote> b,
			int last) {
		StringBuilder line = new StringBuilder();
		for (int third = 0; third < 3; third++) {
			int from = last * third / 3;
			int to = third == 2 ? Integer.MAX_VALUE : last * (third + 1) / 3;
			long inA = a.stream().filter(n -> n.time() >= from && n.time() < to).count();
			long inB = b.stream().filter(n -> n.time() >= from && n.time() < to).count();
			long total = inA + inB;
			line.append(String.format("  third%d %d/%d (%.0f%% / %.0f%%)", third + 1, inA, inB,
				total == 0 ? 0.0 : 100.0 * inA / total, total == 0 ? 0.0 : 100.0 * inB / total));
		}
		return line.toString();
	}

	@Test
	void bothLanesShouldKeepWorkingAfterTheOddHalfRunsOut() throws Exception {
		List<SongBuilder.EventNote> notes = twoLanesThenOne();
		int last = notes.stream().mapToInt(SongBuilder.EventNote::time).max().orElse(0);
		long odd = notes.stream().filter(n -> Math.floorMod(n.time(), 2) == 1).count();
		int lastOdd = notes.stream().filter(n -> Math.floorMod(n.time(), 2) == 1)
			.mapToInt(SongBuilder.EventNote::time).max().orElse(0);
		System.out.println("SONG two-lanes-then-one: " + notes.size() + " notes, " + odd
			+ " on the odd half, last odd at " + lastOdd + " of " + last);

		SongBuilder.TRACE_PARITY = Boolean.getBoolean("probe.trace");
		SongBuilder.TRACE_PARITY_FROM = Integer.getInteger("probe.from", 1600);
		SongBuilder.ParitySchedule schedule = SongBuilder.scheduleParities(notes);
		SongBuilder.TRACE_PARITY = false;
		System.out.println("  seams=" + (schedule.flipsA().size() + schedule.flipsB().size())
			+ " laneA=" + schedule.laneA().size() + " laneB=" + schedule.laneB().size());
		System.out.println("  SHARE" + share(schedule.laneA(), schedule.laneB(), last));
		// Where each lane stops, which is the failure the naked eye sees: one lane ending early
		// and the other carrying the rest of the song by itself.
		int endA = schedule.laneA().isEmpty() ? -1
			: schedule.laneA().get(schedule.laneA().size() - 1).time();
		int endB = schedule.laneB().isEmpty() ? -1
			: schedule.laneB().get(schedule.laneB().size() - 1).time();
		System.out.println("  laneA ends at " + endA + ", laneB ends at " + endB
			+ ", song ends at " + last);
		// And what it costs, by the measure the corridor is: the longer of the two lanes.
		int[][] trackA = SongBuilder.laneCellTrajectory(schedule.laneA());
		int[][] trackB = SongBuilder.laneCellTrajectory(schedule.laneB());
		int cellsA = trackA[1].length == 0 ? 0 : trackA[1][trackA[1].length - 1];
		int cellsB = trackB[1].length == 0 ? 0 : trackB[1][trackB[1].length - 1];
		System.out.println("  cells A=" + cellsA + " B=" + cellsB + " longer="
			+ Math.max(cellsA, cellsB));
	}
}
