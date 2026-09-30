package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every pacing decision one interleaved build took: which machine padded, at which event, by how
 * much, and where it and its partner stood along the path when it did.
 *
 * <p>For the question a build in the world raises and cannot answer by looking: is a long run of
 * wire a lane catching up, or a lane doing what it always does? Plans the song the Paste button
 * would, with the game's settings, and prints {@code SongBuilder.LAST_PACE} beside the plan's own
 * padding counts.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*PaceDecisionProbe" -i -Dprobe.song=fireflies -Dprobe.size=60x1
 * </pre>
 */
@Tag("sweep")
class PaceDecisionProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void printsEveryPacingDecision() throws Exception {
		String wanted = System.getProperty("probe.song", "fireflies").toLowerCase(Locale.ROOT);
		String[] size = System.getProperty("probe.size", "60x1").toLowerCase(Locale.ROOT).split("x");
		int width = Integer.parseInt(size[0]);
		int floors = Integer.parseInt(size[1]);
		Path file;
		try (Stream<Path> listing = Files.list(SONGS)) {
			file = listing.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
				.contains(wanted)).sorted().findFirst().orElseThrow();
		}
		GameSettings.Values game = GameSettings.get();
		ComposerProject song = GameSettings.project(file);
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		List<SongBuilder.EventNote> notes = game.notes(song, mode);
		// The notes the paste gets at one tick, before anything plans them: -Dprobe.tick=2229.
		String tickAsked = System.getProperty("probe.tick", "");
		if (!tickAsked.isBlank()) {
			int tick = Integer.parseInt(tickAsked.strip());
			List<SongBuilder.EventNote> at = notes.stream().filter(note -> note.time() == tick).toList();
			System.out.println("==== " + file.getFileName() + " at tick " + tick + ": " + at.size()
				+ " notes ====");
			at.forEach(note -> System.out.println("   pitch " + note.pitch() + "  "
				+ note.instrumentBlock() + "  track " + note.trackNumber() + "  order " + note.order()
				+ (note.effect() == null ? "" : "  effect " + note.effect())));
			return;
		}
		boolean wasDebug = SongBuilder.DEBUG_PASTE;
		SongBuilder.PastePlan plan;
		// Builder switches by name, -Dprobe.set=PACE_BUDGETS_THE_WIRE=true. See Flags.
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			SongBuilder.DEBUG_PASTE = game.debugPaste();
			plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode,
				game.limits(width, floors));
			// Said while the flags still hold, or it reports what they were put back to.
			System.out.println("   " + held.said());
		} finally {
			SongBuilder.DEBUG_PASTE = wasDebug;
			held.putBack();
		}
		SongBuilder.PaceRecord pace = SongBuilder.LAST_PACE;
		rows(plan, System.getProperty("probe.rows", ""));
		System.out.println();
		System.out.println("==== pace decisions: " + file.getFileName() + " " + width + "x" + floors
			+ " " + game.said() + " ====");
		System.out.println("   built width " + plan.builtWidth() + ", spanZ " + plan.spanZ()
			+ ", columns " + plan.totalColumns());
		System.out.println("   padding:");
		for (Map.Entry<String, Integer> each : plan.padding().entrySet()) {
			System.out.println(String.format("      %-32s %d", each.getKey(), each.getValue()));
		}
		if (pace == null) {
			System.out.println("   no pacing record: this build did not pace");
			return;
		}
		for (int machine = 0; machine < 2; machine++) {
			int[] times = machine == 0 ? pace.timesA() : pace.timesB();
			int[] stretch = machine == 0 ? pace.stretchA() : pace.stretchB();
			int[] real = machine == 0 ? pace.realA() : pace.realB();
			int[] partnerTimes = machine == 0 ? pace.timesB() : pace.timesA();
			int[] partnerReal = machine == 0 ? pace.realB() : pace.realA();
			long total = 0;
			int events = 0;
			for (int each : stretch) {
				total += each;
				events += each > 0 ? 1 : 0;
			}
			System.out.println();
			System.out.println("---- machine " + (char) ('A' + machine) + ": " + times.length
				+ " events, " + events + " padded, " + total + " stretch cells asked, ends at column "
				+ real[real.length - 1] + " ----");
			// A machine's own column can only grow; every drop is the measure, not the walk.
			int drops = 0;
			for (int index = 1; index < real.length; index++) {
				if (real[index] < real[index - 1]) {
					if (drops++ < 12) {
						System.out.println("   DROP event " + index + " t" + times[index] + ": "
							+ real[index - 1] + " -> " + real[index] + "   at "
							+ noteAt(plan, times[index - 1], machine) + " -> "
							+ noteAt(plan, times[index], machine));
					}
				}
			}
			System.out.println("   " + drops + " events where this machine's own column went down");
			System.out.println(String.format("   %6s %6s %7s %8s %8s %9s   %s", "event", "time",
				"stretch", "own col", "partner", "gap", "own note then | partner's nearest note"));
			for (int index = 0; index < times.length; index++) {
				if (stretch[index] <= 0) {
					continue;
				}
				// The partner's last chord before this event, as the baton would have handed it:
				// A walks first on a tie, so B sees A's chord at the same time and A does not see B's.
				int partner = -1;
				for (int other = 0; other < partnerTimes.length; other++) {
					boolean before = machine == 0 ? partnerTimes[other] < times[index]
						: partnerTimes[other] <= times[index];
					if (!before) {
						break;
					}
					partner = partnerReal[other];
				}
				int standing = real[index] - stretch[index];
				System.out.println(String.format("   %6d %6d %7d %8d %8d %9d   %s | %s", index,
					times[index], stretch[index], real[index], partner,
					partner < 0 ? 0 : partner - standing, noteAt(plan, times[index], machine),
					noteAt(plan, times[index], 1 - machine)));
			}
		}
	}

	/** A note block of that machine sounding at that tick, or its nearest earlier one. */
	private static String noteAt(SongBuilder.PastePlan plan, int tick, int machine) {
		BlockPos best = null;
		int bestTick = Integer.MIN_VALUE;
		for (Map.Entry<BlockPos, Integer> note : plan.noteTicks().entrySet()) {
			int whose = plan.noteMachines().getOrDefault(note.getKey(), 0);
			if (whose != machine || note.getValue() > tick || note.getValue() < bestTick) {
				continue;
			}
			if (note.getValue() > bestTick || best == null) {
				bestTick = note.getValue();
				best = note.getKey();
			}
		}
		return best == null ? "-" : "t" + bestTick + " " + best.getX() + " " + best.getY() + " "
			+ best.getZ();
	}

	/**
	 * Rows of the build as runs of what laid them: {@code -Dprobe.rows=65:94,65:100} for y 65 at
	 * z 94 and at z 100, every x the build spans.
	 */
	private static void rows(SongBuilder.PastePlan plan, String asked) {
		if (asked.isBlank()) {
			return;
		}
		int lowX = Integer.MAX_VALUE;
		int highX = Integer.MIN_VALUE;
		for (BlockPos cell : plan.laidBy().keySet()) {
			lowX = Math.min(lowX, cell.getX());
			highX = Math.max(highX, cell.getX());
		}
		for (String row : asked.split(",")) {
			String[] yz = row.strip().split(":");
			int y = Integer.parseInt(yz[0]);
			int z = Integer.parseInt(yz[1]);
			System.out.println();
			System.out.println("---- row y " + y + " z " + z + " ----");
			String run = null;
			int from = lowX;
			for (int x = lowX; x <= highX + 1; x++) {
				String by = x > highX ? "<end>" : plan.laidBy().getOrDefault(new BlockPos(x, y, z), "-");
				if (!by.equals(run)) {
					if (run != null) {
						System.out.println("   x " + from + (x - 1 > from ? ".." + (x - 1) : "") + "  " + run);
					}
					run = by;
					from = x;
				}
			}
		}
	}
}
