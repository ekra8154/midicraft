package com.fastnoteblocks.client.compat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One build, read in as much detail as it takes to say why a lane went outside.
 *
 * <p>Written for the breach in-game reading found on illit at 36 wide over five floors, which is the
 * default: a lane whose repeater stood on the turn column, whose dropped-repeater descent wanted to
 * raise the cell behind it, and which was refused because the lane one floor up had hung a sand at
 * the same column and propped it in the air the raise needed. See
 * {@link SongBuilder#SAND_KEEPS_OFF_THE_WALL_COLUMNS}.</p>
 *
 * <p>{@link FaultCensusProbe} says which build is worth drawing; this draws it. Three windows onto
 * the same plan, because the three questions a breach raises are answered by different views and
 * guessing between them is what costs the time:</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*SandAtWallProbe" -Dprobe.song=illit-do-the-dance -Dprobe.width=36
 * gradlew sweepTest --tests "*SandAtWallProbe" -Dprobe.box="3 10 66 84 90 98"
 * gradlew sweepTest --tests "*SandAtWallProbe" -Dprobe.tick=1284
 * gradlew sweepTest --tests "*SandAtWallProbe" -Dprobe.set=SAND_GIVES_UP_THE_WALL_SLOT=true
 * </pre>
 *
 * <p>{@code probe.box} is a corner pair, {@code x0 x1 y0 y1 z0 z1}, and prints every block in it
 * with the shape that laid it -- which is the view that names a blocker. {@code probe.tick} prints
 * one chord wherever the build put it, which is the view that says whether a note had somewhere
 * else to go. Coordinates are space-separated throughout so they can be pasted into {@code /tp}.</p>
 */
@Tag("sweep")
class SandAtWallProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Census keys worth printing beside the build, as substrings of the key name. */
	private static final String KEYS = System.getProperty("probe.keys", "sand,escentNought");

	@Test
	void readsTheBreach() throws Exception {
		String song = System.getProperty("probe.song", "illit-do-the-dance");
		int width = Integer.getInteger("probe.width", 36);
		int floors = Integer.getInteger("probe.floors", 5);
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.valueOf(
			System.getProperty("probe.mode", "ULTRA_COMPACT_LANE_V2"));
		// Read the way the mode about to build it reads it, not off the layers: a game-tick layout
		// is planned from the composition and every other one from the sequence.
		List<SongBuilder.EventNote> notes = BreachView.song(song, mode);
		// Names every cell with the shape that laid it, which is the whole point of the box window.
		SongBuilder.NAME_EVERY_CELL = true;
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			SongBuilder.FAR_HALVES_LED_BY_A_STANDING_NOTE = 0;
			SongBuilder.QUIET_STACKED_SIDES = 0;
			SongBuilder.RELAYING_STACKED_SIDES = 0;
			SongBuilder.HEADS_TRADED_FOR_A_QUIET_SIDE = 0;
			// maxFloors from the config rather than the floor count, because the two are different
			// numbers and a build given its own floor count as the ceiling is not the build pasted.
			report(song, mode, width, floors, SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				notes, mode, new SongBuilder.BuildLimits(Integer.getInteger("probe.max", 16),
					width, floors, false)));
		} finally {
			held.putBack();
		}
	}

	private static void report(String song, SongBuilder.PasteMode mode, int width, int floors,
			SongBuilder.PastePlan plan) {
		System.out.println("PROBE " + mode + " " + song + " " + width + "x" + floors
			+ " builtWidth=" + plan.builtWidth()
			+ " walls " + plan.nearWall() + ".." + plan.farWall()
			+ " breachLanes=" + plan.breaches().stream().filter(lane -> lane > 0).count()
			+ " worst=" + plan.worstBreach() + " wrong=" + plan.wrongNotes());
		System.out.println("PROBE farHalvesLedByAStandingNote = "
			+ SongBuilder.FAR_HALVES_LED_BY_A_STANDING_NOTE
			+ " quietSides = " + SongBuilder.QUIET_STACKED_SIDES
			+ " relayingSides = " + SongBuilder.RELAYING_STACKED_SIDES
			+ " headsTraded = " + SongBuilder.HEADS_TRADED_FOR_A_QUIET_SIDE);
		for (String fault : plan.faults()) {
			System.out.println("PROBE fault: " + fault);
		}
		for (Map.Entry<String, Integer> entry : plan.padding().entrySet()) {
			String key = entry.getKey().toLowerCase(Locale.ROOT);
			for (String wanted : KEYS.split(",")) {
				if (!wanted.isBlank() && key.contains(wanted.strip().toLowerCase(Locale.ROOT))) {
					System.out.println("PROBE key " + entry.getKey() + " = " + entry.getValue());
					break;
				}
			}
		}
		List<BreachView.Overrun> overruns = BreachView.overruns(plan);
		for (BreachView.Overrun run : overruns.subList(0, Math.min(6, overruns.size()))) {
			System.out.println("PROBE overrun at " + run.x() + " " + run.y() + " " + run.z()
				+ " out=" + run.out() + " nearSide=" + run.nearSide());
		}
		if (!overruns.isEmpty()) {
			System.out.println(BreachView.draw(plan, overruns.get(0), 3, 2, 3));
		}
		chordAt(plan, Integer.getInteger("probe.tick", -1));
		blocksIn(plan, System.getProperty("probe.box", ""));
	}

	/** One chord wherever the build put it: the note, what it stands on, and what laid it. */
	private static void chordAt(SongBuilder.PastePlan plan, int tick) {
		if (tick < 0) {
			return;
		}
		Map<BlockPos, String> blocks = world(plan);
		plan.noteTicks().forEach((at, when) -> {
			if (when == tick) {
				System.out.println("TICK " + tick + " at " + at.getX() + " " + at.getY() + " "
					+ at.getZ() + " " + blocks.get(at) + " on " + blocks.get(at.below())
					+ " by=" + plan.laidBy().get(at));
			}
		});
	}

	/** Every block in a corner pair, with the shape that laid it and the tick it sounds on. */
	private static void blocksIn(SongBuilder.PastePlan plan, String box) {
		if (box.isBlank()) {
			return;
		}
		String[] word = box.strip().split("[ ,]+");
		int[] edge = new int[6];
		for (int index = 0; index < 6; index++) {
			edge[index] = Integer.parseInt(word[index]);
		}
		world(plan).forEach((at, block) -> {
			if (at.getX() >= edge[0] && at.getX() <= edge[1] && at.getY() >= edge[2]
					&& at.getY() <= edge[3] && at.getZ() >= edge[4] && at.getZ() <= edge[5]) {
				System.out.println("BOX " + at.getX() + " " + at.getY() + " " + at.getZ()
					+ " " + block + " by=" + plan.laidBy().get(at)
					+ " tick=" + plan.noteTicks().get(at));
			}
		});
	}

	private static Map<BlockPos, String> world(SongBuilder.PastePlan plan) {
		Map<BlockPos, String> blocks = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] part = command.split(" ");
			blocks.put(new BlockPos(Integer.parseInt(part[1]), Integer.parseInt(part[2]),
				Integer.parseInt(part[3])), part[4]);
		}
		return blocks;
	}
}
