package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What actually stands between a parity seam and the next note, in blocks.
 *
 * <p>The seam is eleven game ticks of machine. Anything past that in front of it is the schedule's
 * doing rather than the piston's, and this says which is which: the gap the scheduler left, the
 * ticks the element eats, and the redstone ticks of repeater the walk then laid to cover what was
 * left. Read off the commands, so it is the build and not the plan's opinion of it.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*SeamPadProbe" -Dprobe.song=... -Dprobe.reseed=16
 * </pre>
 */
@Tag("sweep")
class SeamPadProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The block id without its state, without a regex to get wrong. */
	private static String nameOf(String block) {
		int bracket = block.indexOf('[');
		return bracket < 0 ? block : block.substring(0, bracket);
	}

	/** The delay digits of a repeater state, without a regex to get wrong. */
	private static String delayOf(String block) {
		int from = block.indexOf("delay=") + 6;
		int to = from;
		while (to < block.length() && Character.isDigit(block.charAt(to))) {
			to++;
		}
		return block.substring(from, to);
	}

	@Test
	void whatStandsAfterEverySeam() throws Exception {
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			run(held);
		} finally {
			held.putBack();
		}
	}

	private void run(Flags.Held held) throws Exception {
		boolean quiet = Boolean.getBoolean("probe.quiet");
		String name = System.getProperty("probe.song", "ultra-ones-gap2-odd-third-short");
		int reseed = Integer.getInteger("probe.reseed", 16);
		Path file = Path.of("run", "config", "fast-noteblocks", "songs", name + ".json");
		ComposerProject project;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			project = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes = SongBuilder.gameTickEventNotes(project, true);
		SongBuilder.ParitySchedule schedule = SongBuilder.scheduleParities(notes, reseed);
		System.out.println("SONG " + name + held.said() + " reseed=" + reseed + " notes=" + notes.size()
			+ " laneA=" + schedule.laneA().size() + " laneB=" + schedule.laneB().size()
			+ " seams=" + (schedule.flipsA().size() + schedule.flipsB().size()));

		int savedRepeaters = 0;
		int laidRepeaters = 0;
		for (int side = 0; side < 2; side++) {
			List<SongBuilder.EventNote> lane = side == 0 ? schedule.laneA() : schedule.laneB();
			List<Integer> flips = side == 0 ? schedule.flipsA() : schedule.flipsB();
			List<Integer> times = new ArrayList<>();
			for (SongBuilder.EventNote note : lane) {
				if (times.isEmpty() || times.get(times.size() - 1) != note.time()) {
					times.add(note.time());
				}
			}
			List<Integer> allTimes = new ArrayList<>();
			for (SongBuilder.EventNote note : notes) {
				if (allTimes.isEmpty() || allTimes.get(allTimes.size() - 1) != note.time()) {
					allTimes.add(note.time());
				}
			}
			if (!quiet) {
				System.out.println("  lane " + (side == 0 ? "A" : "B") + ": " + flips.size()
					+ " seams");
			}
			for (int flip : flips) {
				int at = times.get(flip);
				int prev = times.get(flip - 1);
				int gap = at - prev;
				int eat = Math.floorMod(at, 2) == 1 ? (SongBuilder.PARITY_SEAM_GAME_TICKS - 1) / 2
					: (SongBuilder.PARITY_SEAM_GAME_TICKS + 1) / 2;
				int delay = Math.floorDiv(at, 2) - Math.floorDiv(prev, 2) - eat;
				// The earliest moment this lane could legally have flipped instead: an event
				// somewhere in the song, at least PARITY_SEAM_GT after the lane's last note,
				// and on the other half from it. Anything between that and where the flip
				// actually went is silence the schedule chose, not machine the piston needs.
				int soonest = -1;
				for (int time : allTimes) {
					if (time - prev >= SongBuilder.PARITY_SEAM_GT
							&& (time - prev) % 2 != 0) {
						soonest = time;
						break;
					}
				}
				int couldEat = Math.floorMod(soonest, 2) == 1
					? (SongBuilder.PARITY_SEAM_GAME_TICKS - 1) / 2
					: (SongBuilder.PARITY_SEAM_GAME_TICKS + 1) / 2;
				int couldDelay = soonest < 0 ? 0
					: Math.floorDiv(soonest, 2) - Math.floorDiv(prev, 2) - couldEat;
				savedRepeaters += Math.max(0, delay) - Math.max(0, couldDelay);
				laidRepeaters += Math.max(0, delay);
				if (!quiet) {
					System.out.println("    seam at gt " + at + " after gt " + prev + ": gap " + gap
					+ " gt, element eats " + (2 * eat) + " gt, walk lays " + Math.max(0, delay)
					+ " rt of repeater"
					+ (soonest >= 0 && soonest < at
						? "   <- could have flipped at gt " + soonest + ", "
							+ (at - soonest) + " gt earlier, for "
							+ Math.max(0, couldDelay) + " rt"
						: ""));
				}
			}
		}

		System.out.println("  LAID " + laidRepeaters + " rt of repeater across every seam;"
			+ " flipping as early as legal would drop " + savedRepeaters + " more");

		// And the blocks, which is what can be walked up to in the world.
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.HALF_TICK_LANE,
			new SongBuilder.BuildLimits(16, 24, 1, false, reseed));
		Map<Integer, String> byColumn = new TreeMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ", 5);
			int x = Integer.parseInt(parts[1]);
			int y = Integer.parseInt(parts[2]);
			int z = Integer.parseInt(parts[3]);
			if (y != 65) {
				continue;
			}
			String block = parts[4];
			if (block.startsWith("minecraft:sticky_piston") || block.contains("note_block")
					|| block.startsWith("minecraft:repeater")) {
				byColumn.put(x * 8 + z, nameOf(block) + (block.contains("delay=")
					? "/" + delayOf(block) : ""));
			}
		}
		System.out.println("  --- blocks after the first two seams, per lane row ---");
		List<Integer> keys = new ArrayList<>(byColumn.keySet());
		int shown = 0;
		for (int index = 0; index < keys.size() && shown < 2; index++) {
			if (!byColumn.get(keys.get(index)).contains("sticky_piston")) {
				continue;
			}
			int row = keys.get(index) % 8;
			StringBuilder line = new StringBuilder("    row " + row + " x"
				+ (keys.get(index) / 8) + ": ");
			int printed = 0;
			for (int look = index; look < keys.size() && printed < 16; look++) {
				if (keys.get(look) % 8 != row) {
					continue;
				}
				line.append(byColumn.get(keys.get(look)).replace("minecraft:", "")).append(" ");
				printed++;
			}
			System.out.println(line);
			shown++;
			// Skip the pair's second piston.
			index += 2;
		}
	}
}
