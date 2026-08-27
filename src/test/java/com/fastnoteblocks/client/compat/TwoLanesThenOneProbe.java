package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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

		// The build the schedule becomes, read back for when every note actually fires. Both
		// lists aligned at their own first note -- a shift the whole song shares is the machine
		// starting a moment after the button, and a shift part of it takes alone is the fault.
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(16, 24, 1));
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
			new java.util.HashMap<>();
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			BlockPos pos = new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
				Integer.parseInt(token[3]));
			String text = token[4].substring(0, token[4].length() - " replace".length());
			if (text.startsWith("minecraft:oak_button")) {
				text = "minecraft:lever[face=floor,facing=east,powered=true]";
			}
			world.put(pos, net.minecraft.commands.arguments.blocks.BlockStateParser
				.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, text, false)
				.blockState());
			for (int axis = 0; axis < 3; axis++) {
				int value = axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
				min[axis] = Math.min(min[axis], value);
				max[axis] = Math.max(max[axis], value);
			}
		}
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			if ((token[4].contains("repeater") || token[4].contains("note_block")
						|| token[4].contains("piston") || token[4].contains("redstone_block")
						|| token[4].contains("button"))) {
				System.out.println("    " + command.replace(" replace", ""));
			}
		}
		NoteMachineReader.Reading reading = NoteMachineReader.read("TwoLanesThenOne",
			new BlockPos(min[0] - 1, min[1] - 1, min[2] - 1),
			new BlockPos(max[0] + 1, max[1] + 1, max[2] + 1),
			pos -> world.getOrDefault(pos,
				net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
		// Fire times in game ticks: the reading's fixed clock puts sixty composer ticks in one.
		List<Integer> heard = new ArrayList<>();
		for (var layer : reading.project().layers()) {
			List<Integer> ticks = layer.notes().stream()
				.map(note -> (int)(note.startTick()
					/ (NoteMachineReader.TICKS_PER_REDSTONE_TICK / 2)))
				.sorted().toList();
			System.out.println("  LAYER " + layer.instrument() + " " + ticks.size() + " raw "
				+ ticks.subList(0, Math.min(14, ticks.size())));
			heard.addAll(ticks);
		}
		heard.sort(null);
		List<Integer> wrote = notes.stream().map(SongBuilder.EventNote::time).sorted().toList();
		System.out.println("  WROTE " + wrote.subList(0, Math.min(24, wrote.size())));
		System.out.println("  HEARD " + heard.subList(0, Math.min(24, heard.size())));
		int seamAt = 262;
		System.out.println("  WROTE@switch " + wrote.stream()
			.filter(t -> t > seamAt - 8 && t < seamAt + 60).toList());
		System.out.println("  HEARD@switch " + heard.stream()
			.filter(t -> t > seamAt - 8 && t < seamAt + 60).toList());
		System.out.println("  laneA@switch " + schedule.laneA().stream()
			.map(SongBuilder.EventNote::time).distinct()
			.filter(t -> t > seamAt - 16 && t < seamAt + 60).toList());
		System.out.println("  laneB@switch " + schedule.laneB().stream()
			.map(SongBuilder.EventNote::time).distinct()
			.filter(t -> t > seamAt - 16 && t < seamAt + 60).toList());
		int shiftHeard = heard.isEmpty() ? 0 : heard.get(0);
		int shiftWrote = wrote.get(0);
		System.out.println("  TIMING heard " + heard.size() + " of " + wrote.size()
			+ " (a clump is two on one tick, and the reader keeps one)");
		int shown = 0;
		int at = 0;
		for (int index = 0; index < wrote.size() && at < heard.size(); index++) {
			int expected = wrote.get(index) - shiftWrote;
			int actual = heard.get(at) - shiftHeard;
			if (actual == expected) {
				at++;
				continue;
			}
			if (shown++ < 12) {
				System.out.println("    note " + index + " written gt " + expected
					+ " heard gt " + (actual < expected ? actual + " EARLY" : actual + " LATE"));
			}
			if (actual > expected) {
				continue;
			}
			at++;
			index--;
		}
		if (shown > 12) {
			System.out.println("    ... and " + (shown - 12) + " more");
		}
		if (shown == 0) {
			System.out.println("    every heard note on its written tick");
		}
	}
}
