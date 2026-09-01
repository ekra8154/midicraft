package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The raised three-cell ascent, off against on, song by song and config by config.
 *
 * <p>Every number quoted for this rule so far has been a library total or a Guardian total, and a
 * total says how much moved and nothing about where. This prints the same measurement at three
 * altitudes -- the config actually built at, one line a song over the sweep, and one line a
 * config for Guardian -- so a regression can be pointed at rather than argued about.</p>
 */
@Tag("sweep")
class RaisedAscentABTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** Real songs, not the {@code ultra-*} stress files, with Guardian first because it is the case. */
	private static final List<String> PICKED = List.of(
		"deltarune-ch-4-guardian",
		"illit-do-the-dance",
		"big-shot",
		"hopes-and-dreams",
		"golden-brown-2xspeed",
		"adventure-of-a-lifetime",
		"michael-jackson-thriller",
		"aria-math-c418");

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	/** One arm's totals over whatever set of builds it was handed. */
	private static final class Tally {
		int builds;
		int refused;
		int lanesBreached;
		int breachBlocks;
		int worst;
		long blocks;
		long length;
		long padCells;
		int wrong;
		int recessLanes;
		long recessColumns;
		final TreeMap<String, Long> padBy = new TreeMap<>();

		void add(SongBuilder.PastePlan plan) {
			builds++;
			lanesBreached += plan.breaches().size();
			breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
			worst = Math.max(worst, plan.worstBreach());
			blocks += plan.commands().size();
			length += plan.width();
			padCells += plan.padCells();
			wrong += plan.wrongNotes();
			recessLanes += plan.recesses().size();
			recessColumns += plan.recessedColumns();
			plan.padding().forEach((key, count) -> padBy.merge(key, (long) count, Long::sum));
		}

		String line() {
			return "builds=" + builds + " refused=" + refused + " lanesBreached=" + lanesBreached
				+ " breachBlocks=" + breachBlocks + " worst=" + worst + " wrong=" + wrong
				+ " blocks=" + blocks + " length=" + length + " pad=" + padCells
				+ " recessLanes=" + recessLanes + " recessCols=" + recessColumns;
		}
	}

	private static Tally run(List<SongBuilder.EventNote> notes, List<int[]> configs) {
		Tally tally = new Tally();
		for (int[] config : configs) {
			try {
				tally.add(SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(config[2], config[0], config[1])));
			} catch (RuntimeException refused) {
				tally.refused++;
			}
		}
		return tally;
	}

	private static List<int[]> sweepGrid() {
		List<int[]> configs = new ArrayList<>();
		for (int floors = 1; floors <= 6; floors++) {
			for (int width = 12; width <= 48; width += 4) {
				// maxFloors 4, matching every other sweep in this package, so the totals here can be
				// held against the ones already in the handoff.
				configs.add(new int[] {width, floors, 4});
			}
		}
		return configs;
	}

	/** The live build limits, read off {@code run/config/midicraft.json}. */
	private static final List<int[]> REAL = List.of(new int[] {40, 5, 16});

	private static Map<String, Tally> arm(boolean raised, List<int[]> configs) throws Exception {
		Map<String, Tally> byName = new LinkedHashMap<>();
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = raised;
		try {
			for (String name : PICKED) {
				byName.put(name, run(load(name), configs));
			}
		} finally {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
		}
		return byName;
	}

	private static void report(String heading, Map<String, Tally> off, Map<String, Tally> on) {
		System.out.println();
		System.out.println("==== " + heading + " ====");
		for (String name : PICKED) {
			Tally a = off.get(name);
			Tally b = on.get(name);
			System.out.println(name);
			System.out.println("   off  " + a.line());
			System.out.println("   on   " + b.line());
			System.out.println("   diff " + delta("lanesBreached", a.lanesBreached, b.lanesBreached)
				+ delta("breachBlocks", a.breachBlocks, b.breachBlocks)
				+ delta("worst", a.worst, b.worst)
				+ delta("wrong", a.wrong, b.wrong)
				+ delta("refused", a.refused, b.refused)
				+ delta("blocks", a.blocks, b.blocks)
				+ delta("length", a.length, b.length)
				+ delta("pad", a.padCells, b.padCells));
		}
	}

	private static String delta(String key, long before, long after) {
		long diff = after - before;
		return diff == 0 ? "" : key + " " + before + "->" + after + " ("
			+ (diff > 0 ? "+" : "") + diff + ")  ";
	}

	@Test
	void measuresTheRaisedAscentAtEveryAltitude() throws Exception {
		report("the live config, 40w x 5f", arm(false, REAL), arm(true, REAL));

		List<int[]> grid = sweepGrid();
		Map<String, Tally> offSweep = arm(false, grid);
		Map<String, Tally> onSweep = arm(true, grid);
		report("sweep, 60 configs a song (12-48w x 1-6f)", offSweep, onSweep);

		// The padding census, which is where the plan says why it spent a column. Only the movers,
		// because there are over a hundred keys and the ones that did not move are the answer to a
		// different question.
		System.out.println();
		System.out.println("==== padding keys that moved, summed over every picked song ====");
		TreeMap<String, long[]> moved = new TreeMap<>();
		for (String name : PICKED) {
			offSweep.get(name).padBy.forEach((key, count) ->
				moved.computeIfAbsent(key, unused -> new long[2])[0] += count);
			onSweep.get(name).padBy.forEach((key, count) ->
				moved.computeIfAbsent(key, unused -> new long[2])[1] += count);
		}
		moved.entrySet().stream()
			.filter(entry -> entry.getValue()[0] != entry.getValue()[1])
			.sorted((a, b) -> Long.compare(Math.abs(b.getValue()[1] - b.getValue()[0]),
				Math.abs(a.getValue()[1] - a.getValue()[0])))
			.limit(30)
			.forEach(entry -> System.out.println("   " + entry.getKey() + "  "
				+ entry.getValue()[0] + " -> " + entry.getValue()[1] + "  ("
				+ (entry.getValue()[1] - entry.getValue()[0] > 0 ? "+" : "")
				+ (entry.getValue()[1] - entry.getValue()[0]) + ")"));

		// Guardian, one line a config, so the cost can be located rather than summed.
		System.out.println();
		System.out.println("==== Guardian, config by config ====");
		List<SongBuilder.EventNote> guardian = load("deltarune-ch-4-guardian");
		System.out.println("   config      off: lanes/blocks/worst      on: lanes/blocks/worst   "
			+ " length off->on");
		for (int[] config : grid) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = false;
			Tally a = run(guardian, List.of(config));
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
			Tally b = run(guardian, List.of(config));
			if (a.refused > 0 || b.refused > 0) {
				System.out.println("   " + config[0] + "w x " + config[1] + "f   refused off="
					+ a.refused + " on=" + b.refused);
				continue;
			}
			String flag = b.breachBlocks > a.breachBlocks ? "   WORSE"
				: b.breachBlocks < a.breachBlocks ? "   better" : "";
			System.out.println("   " + config[0] + "w x " + config[1] + "f      "
				+ a.lanesBreached + "/" + a.breachBlocks + "/" + a.worst + "        "
				+ b.lanesBreached + "/" + b.breachBlocks + "/" + b.worst + "        "
				+ a.length + " -> " + b.length + flag);
		}
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
	}

	/**
	 * The raised ascent against the search that pads a chord until it cuts.
	 *
	 * <p>Because they are arguing about the same arithmetic. The cut search moves a chord forward a
	 * column at a time until it no longer fits and has to be cut across the turn, and "fits" is
	 * measured against what the turn costs -- so making the turn two cells cheaper makes chords fit
	 * that used to be cut, and {@link SongBuilder#planLane} says in writing what happens then: the
	 * lane is laid whole, "has nothing left to climb with, cannot turn, and runs on". If that is the
	 * mechanism, deepening the search should buy some of it back and switching the search off should
	 * make both arms equally bad. If it is not, neither will move.</p>
	 */
	@Test
	void weighsTheRaisedAscentAgainstTheCutSearch() throws Exception {
		List<SongBuilder.EventNote> guardian = load("deltarune-ch-4-guardian");
		List<int[]> grid = sweepGrid();
		System.out.println();
		System.out.println("==== Guardian sweep: raised ascent x cut-pad search ====");
		for (boolean cuts : new boolean[] {true, false}) {
			for (int columns : cuts ? new int[] {2, 4, 6} : new int[] {2}) {
				for (boolean raised : new boolean[] {false, true}) {
					SongBuilder.PADS_UNTIL_THE_NEXT_CHORD_CUTS = cuts;
					SongBuilder.CUT_PAD_COLUMNS = columns;
					SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = raised;
					Tally tally = run(guardian, grid);
					System.out.println("   cutSearch=" + (cuts ? columns + " columns" : "off     ")
						+ "  raised=" + (raised ? "on " : "off") + "   " + tally.line());
				}
			}
		}
		SongBuilder.PADS_UNTIL_THE_NEXT_CHORD_CUTS = true;
		SongBuilder.CUT_PAD_COLUMNS = 2;
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
	}

	/**
	 * The raised ascent with both places that price a turn agreeing about it.
	 *
	 * <p>Four arms, because the fix only means anything against the arm it is meant to repair: the
	 * raised ascent is what creates lanes standing on a bus with a one-cell pad, and
	 * {@code reachesWall} is what refuses them. Off the raised ascent the flag should do very little,
	 * and if it does a lot there, it is doing something other than what it says.</p>
	 */
	@Test
	void measuresPricingTheWallReachTheSameWay() throws Exception {
		for (String heading : new String[] {"real", "sweep"}) {
			List<int[]> configs = heading.equals("real") ? REAL : sweepGrid();
			System.out.println();
			System.out.println("==== both prices agreeing -- " + heading + " ====");
			for (boolean raised : new boolean[] {false, true}) {
				for (boolean agree : new boolean[] {false, true}) {
					SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = raised;
					SongBuilder.WALL_REACH_PRICES_THE_RAISED_PAD = agree;
					Tally total = new Tally();
					StringBuilder perSong = new StringBuilder();
					for (String name : PICKED) {
						Tally one = run(load(name), configs);
						total.builds += one.builds;
						total.refused += one.refused;
						total.lanesBreached += one.lanesBreached;
						total.breachBlocks += one.breachBlocks;
						total.worst = Math.max(total.worst, one.worst);
						total.blocks += one.blocks;
						total.length += one.length;
						total.wrong += one.wrong;
						perSong.append("      ").append(name).append("  lanes=")
							.append(one.lanesBreached).append(" blocks=").append(one.breachBlocks)
							.append(" worst=").append(one.worst).append('\n');
					}
					System.out.println("   raised=" + (raised ? "on " : "off") + " bothPrices="
						+ (agree ? "on " : "off") + "   " + total.line());
					System.out.print(perSong);
				}
			}
		}
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
		SongBuilder.WALL_REACH_PRICES_THE_RAISED_PAD = true;
	}

	/**
	 * illit at 32 wide over four floors, which is a cut a player can see going wrong.
	 *
	 * <p>The chord is divided by {@code near = 2 * (room - 1)} and {@code room} is however many
	 * columns are left to the wall, so the near half is not chosen -- it falls out of where the lane
	 * happens to be standing. This prints the turn trace for both arms so the two divisions can be
	 * held against each other. Both candidate walks print, so the line to read is the one whose
	 * {@code at} matches the plan's own coordinates.</p>
	 */
	@Test
	void tracesTheIllitCutAtThirtyTwoByFour() throws Exception {
		List<SongBuilder.EventNote> illit = load("illit-do-the-dance");
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = arm == 1;
			String label = arm == 0 ? "OFF" : "ON ";
			SongBuilder.TRACE_TURNS = true;
			System.out.println();
			System.out.println("######## arm " + label + " ########");
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), illit,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 32, 4));
			SongBuilder.TRACE_TURNS = false;
			System.out.println(label + "  nearWall=" + plan.nearWall() + " farWall=" + plan.farWall()
				+ " spanX=" + plan.spanX() + " spanZ=" + plan.spanZ()
				+ " blocks=" + plan.commands().size() + " turns=" + plan.turns().size());
			System.out.println(label + "  breaches " + plan.breaches());
			plan.faults().forEach(fault -> System.out.println(label + "  fault " + fault));
			Map<String, int[]> runs = new LinkedHashMap<>();
			for (String command : plan.commands()) {
				String[] word = command.split(" ");
				int x = Integer.parseInt(word[1]);
				int y = Integer.parseInt(word[2]);
				int z = Integer.parseInt(word[3]);
				int out = x < plan.nearWall() ? plan.nearWall() - x
					: x > plan.farWall() ? x - plan.farWall() : 0;
				if (out == 0) {
					continue;
				}
				int[] seen = runs.get(y + " " + z);
				if (seen == null || out > seen[0]) {
					runs.put(y + " " + z, new int[] {out, x, y, z});
				}
			}
			runs.values().stream().sorted((a, b) -> Integer.compare(b[0], a[0])).limit(10)
				.forEach(run -> System.out.println(label + "    out=" + run[0] + " columns   tp "
					+ run[1] + " " + run[2] + " " + run[3]));
		}
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
	}

	/**
	 * The two lanes Guardian loses at the live size, in coordinates somebody can stand on.
	 *
	 * <p>Paste at {@code 0 64 0} and every number here is a number in the world. The turns are
	 * printed as a difference rather than a list because a build has hundreds and only the ones that
	 * moved are evidence -- a lane that turns in the same column in both arms did not pay for this.</p>
	 */
	@Test
	void locatesWhatGuardianLosesAtFortyByFive() throws Exception {
		List<SongBuilder.EventNote> guardian = load("deltarune-ch-4-guardian");
		SongBuilder.PastePlan[] plans = new SongBuilder.PastePlan[2];
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = arm == 1;
			plans[arm] = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 40, 5));
		}
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;

		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PastePlan plan = plans[arm];
			String label = arm == 0 ? "OFF" : "ON ";
			System.out.println();
			System.out.println(label + "  nearWall=" + plan.nearWall() + " farWall=" + plan.farWall()
				+ " spanX=" + plan.spanX() + " spanZ=" + plan.spanZ() + " floors=" + plan.height()
				+ " blocks=" + plan.commands().size() + " turns=" + plan.turns().size());
			System.out.println(label + "  breaches " + plan.breaches());
			for (String fault : plan.faults()) {
				System.out.println(label + "  fault " + fault);
			}
			Map<String, int[]> runs = new LinkedHashMap<>();
			for (String command : plan.commands()) {
				String[] word = command.split(" ");
				int x = Integer.parseInt(word[1]);
				int y = Integer.parseInt(word[2]);
				int z = Integer.parseInt(word[3]);
				int out = x < plan.nearWall() ? plan.nearWall() - x
					: x > plan.farWall() ? x - plan.farWall() : 0;
				if (out == 0) {
					continue;
				}
				int[] seen = runs.get(y + " " + z);
				if (seen == null || out > seen[0]) {
					runs.put(y + " " + z, new int[] {out, x, y, z});
				}
			}
			runs.values().stream().sorted((a, b) -> Integer.compare(b[0], a[0])).limit(8)
				.forEach(run -> System.out.println(label + "    out=" + run[0] + " columns   tp "
					+ run[1] + " " + run[2] + " " + run[3]));
		}

		// Turn by turn, in build order, with x measured from each build's own near wall -- the two
		// arms sit ten columns apart in the world and every raw coordinate differs for that reason
		// alone. What matters is whether the nth turn stands the same distance into the corridor.
		System.out.println();
		System.out.println("turns off=" + plans[0].turns().size() + " on=" + plans[1].turns().size());
		int shown = 0;
		int shared = Math.min(plans[0].turns().size(), plans[1].turns().size());
		for (int index = 0; index < shared && shown < 24; index++) {
			BlockPos a = plans[0].turns().get(index);
			BlockPos b = plans[1].turns().get(index);
			int offX = a.getX() - plans[0].nearWall();
			int onX = b.getX() - plans[1].nearWall();
			if (offX == onX && a.getY() == b.getY() && a.getZ() == b.getZ()) {
				continue;
			}
			System.out.println("   turn #" + index + "   off col " + offX + " at y" + a.getY()
				+ " z" + a.getZ() + "   ->   on col " + onX + " at y" + b.getY() + " z" + b.getZ()
				+ (offX != onX ? "   moved " + (onX - offX) + " columns" : ""));
			shown++;
		}
		if (shown == 0) {
			System.out.println("   every shared turn stands in the same column");
		}
	}
}
