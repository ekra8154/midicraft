package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A breach, drawn the way in-game reading shows one off the world.
 *
 * <p>Every diagnosis in this file so far has come from standing at the blocks in game and asking
 * how the same notes could have been laid without going outside -- and every one made away from
 * the blocks has come from reading arithmetic and guessing. The difference is not insight, it is
 * that the blocks show the
 * machine and I could not. {@link AsciiDiagram} already draws a box of world for the in-game
 * command; this points it at a headless plan and puts the walk's own turn trace beside it.</p>
 *
 * <p>Two traps are handled here because both have cost real time. The trace prints <b>two</b> whole
 * builds, one for each lookahead candidate, and only one of them is the plan handed back -- so the
 * output is split on the {@code PLANRUN} markers and only the winner's half is kept. And the two
 * candidates disagree about where the finished build slides to, so every coordinate is quoted
 * relative to the plan's own walls as well as absolutely.</p>
 */
final class BreachView {
	private BreachView() {
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	static List<SongBuilder.EventNote> song(String name) throws Exception {
		return song(name, SongBuilder.PasteMode.ULTRA_COMPACT_LANE);
	}

	/**
	 * A saved song, read the way the mode about to build it would read it.
	 *
	 * <p>Worth the parameter, because getting it wrong is not a small error and has already been
	 * made. A layout timed in game ticks is planned from the composition; every other one is
	 * planned from the sequence, whose delays are repeater ticks. A probe that always reads the
	 * sequence hands a game-tick layout a song at twice its speed, split down the middle by a
	 * property of the sequence rather than of the music -- which is precisely the fault that
	 * shipped, and the probes agreed with it because they were making the same mistake.</p>
	 *
	 * <p>So the question goes through the same method the paste uses. A probe and a build that
	 * share a mistake agree perfectly and prove nothing.</p>
	 */
	static List<SongBuilder.EventNote> song(String name, SongBuilder.PasteMode mode)
			throws Exception {
		ComposerProject project = project(name);
		return SongBuilder.notesFor(mode, project.toSequenceTracks(Set.of(), true), project, true);
	}

	/**
	 * Old library names mapped onto the songs that replaced them.
	 *
	 * <p>The library was deleted on 2026-08-19 and rebuilt by hand the next day; several songs
	 * came back under new titles. Tests keep their historical names and resolve here, so a song's
	 * tests and its file can move independently.</p>
	 */
	private static final Map<String, String> RENAMED = Map.of(
		"guardian-w-25-chords", "guardian25",
		// Renamed in the composer 2026-08-20: the chords were culled to 26 notes. Both ways,
		// because the rename has happened in the composer and not yet on disk: the old name
		// keeps resolving once the client saves, and the new one -- which is what the song is
		// called in conversation now -- resolves to the old file until it does.
		"deltarune-ch-4-guardian", "guardian26",
		"guardian26", "deltarune-ch-4-guardian",
		"adventure-of-a-lifetime", "adventure-lifetime-3",
		"a-dark-zone-2-lanes-maybe", "a-dark-zone",
		"am-i-dreaming", "am-i-dreaming-metro-boomin-from-spider-man-acros",
		"jackpot", "jackpot-thefatrat");

	/**
	 * The file a song name resolves to today.
	 *
	 * <p>A name with no file and no mapping <b>aborts</b> the test rather than failing it: the
	 * song is gone from the library, which says nothing about the code. The abort message names
	 * the song so a rebuilt or re-imported file picks the test straight back up.</p>
	 */
	/** Whether a name still resolves to a song, for tests that iterate several and keep going. */
	static boolean inLibrary(String name) {
		if (Files.exists(SONGS.resolve(name + ".json"))) {
			return true;
		}
		String renamed = RENAMED.get(name);
		return renamed != null && Files.exists(SONGS.resolve(renamed + ".json"));
	}

	static Path songFile(String name) {
		Path exact = SONGS.resolve(name + ".json");
		if (Files.exists(exact)) {
			return exact;
		}
		String renamed = RENAMED.get(name);
		if (renamed != null && Files.exists(SONGS.resolve(renamed + ".json"))) {
			return SONGS.resolve(renamed + ".json");
		}
		throw new org.opentest4j.TestAbortedException(
			"song no longer in the library (lost 2026-08-19, rebuilt without it): " + name);
	}

	static ComposerProject project(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			return new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
		}
	}

	/** A plan and the winning walk's trace, which is the only half worth reading. */
	record Traced(SongBuilder.PastePlan plan, List<String> trace) {
	}

	static Traced build(List<SongBuilder.EventNote> notes, int width, int floors, int maxFloors) {
		PrintStream saved = System.out;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		SongBuilder.PastePlan plan;
		try {
			System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
			SongBuilder.TRACE_TURNS = true;
			plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(maxFloors, width, floors));
		} finally {
			SongBuilder.TRACE_TURNS = false;
			System.setOut(saved);
		}
		return new Traced(plan, winnersHalf(buffer.toString(StandardCharsets.UTF_8)));
	}

	/**
	 * The half of the trace belonging to the build that was kept.
	 *
	 * <p>{@code createPastePlan} walks the song twice, without the lookahead and then with it, and
	 * announces both and then the winner. Lines from the loser describe a machine nobody built.</p>
	 */
	private static List<String> winnersHalf(String printed) {
		List<String> without = new ArrayList<>();
		List<String> with = new ArrayList<>();
		List<String> current = null;
		boolean lookaheadWon = false;
		for (String line : printed.split("\r?\n")) {
			String trimmed = line.strip();
			if (trimmed.equals("PLANRUN lookahead=no")) {
				current = without;
			} else if (trimmed.equals("PLANRUN lookahead=yes")) {
				current = with;
			} else if (trimmed.startsWith("PLANRUN won=")) {
				lookaheadWon = trimmed.startsWith("PLANRUN won=lookahead=yes");
				current = null;
			} else if (current != null && !trimmed.isEmpty()) {
				current.add(trimmed);
			}
		}
		return lookaheadWon ? with : without;
	}

	/** One lane that finished outside its footprint: which lane, how far out, and where. */
	record Overrun(int y, int z, int out, int x, boolean nearSide) {
		@Override
		public String toString() {
			return "y" + y + " z" + z + "  out=" + out + " columns past the "
				+ (nearSide ? "near" : "far") + " wall   tp " + x + " " + y + " " + z;
		}
	}

	/** The lowest block in the build, which is the y every floor is counted from. */
	private static int groundOf(SongBuilder.PastePlan plan) {
		int lowest = Integer.MAX_VALUE;
		for (String command : plan.commands()) {
			lowest = Math.min(lowest, Integer.parseInt(command.split(" ")[2]));
		}
		return lowest;
	}

	/** The floor a block belongs to. Floors stand four apart, and a lane occupies three of them. */
	private static int floorOf(int y, int ground) {
		return ground + Math.floorDiv(y - ground, FLOOR_HEIGHT) * FLOOR_HEIGHT;
	}

	private static final int FLOOR_HEIGHT = 4;

	/**
	 * Every lane holding a block outside the walls, worst first.
	 *
	 * <p>Gathered off the blocks rather than out of {@code breaches()}, because the count says how
	 * many and the blocks say where.</p>
	 *
	 * <p>Keyed by <b>floor</b> and z rather than by y and z. A lane is three levels deep -- the path
	 * it walks, the stone of its bus a block up, and the dust on top of that -- so keying on y makes
	 * one overrunning lane look like three, and the two worst entries on a page turn out to be the
	 * same run twice.</p>
	 */
	static List<Overrun> overruns(SongBuilder.PastePlan plan) {
		int ground = groundOf(plan);
		Map<String, Overrun> worst = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			boolean near = x < plan.nearWall();
			int out = near ? plan.nearWall() - x : x > plan.farWall() ? x - plan.farWall() : 0;
			if (out == 0) {
				continue;
			}
			String key = floorOf(y, ground) + " " + z;
			Overrun seen = worst.get(key);
			if (seen == null || out > seen.out()) {
				worst.put(key, new Overrun(y, z, out, x, near));
			}
		}
		List<Overrun> all = new ArrayList<>(worst.values());
		all.sort((a, b) -> Integer.compare(b.out(), a.out()));
		return all;
	}

	/**
	 * The corridor holding a breach, drawn from a column before the near wall to the far end of the
	 * overrun, and from the lane's own floor up far enough to show the bus and the dust on it.
	 */
	static String draw(SongBuilder.PastePlan plan, Overrun run, int zEither, int yBelow, int yAbove) {
		Map<BlockPos, BlockState> world = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3])), parse(word[4]));
		}
		int fromX = Math.min(plan.nearWall() - 2, run.x() - 2);
		int toX = Math.max(plan.farWall() + 2, run.x() + 2);
		// From the lane's own floor, not from the block that happened to stick out furthest. That
		// block is as likely to be the dust on top of a bus as the path, and a window hung off it
		// starts two levels above the lane and shows none of it.
		int floor = floorOf(run.y(), groundOf(plan));
		return AsciiDiagram.render(at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()),
			new BlockPos(fromX, floor - yBelow, run.z() - zEither),
			new BlockPos(toX, floor + yAbove, run.z() + zEither),
			AsciiDiagram.View.TOP, AsciiDiagram.Shape.CODE);
	}

	/** The winning walk's turn lines for one lane, in build order. */
	static List<String> laneTrace(List<String> trace, int z, int zEither) {
		List<String> lines = new ArrayList<>();
		for (String line : trace) {
			if (!line.startsWith("TURN ") && !line.startsWith("PAST ")
					&& !line.startsWith("TURNEDAT ") && !line.startsWith("STARTOFF ")) {
				continue;
			}
			int at = line.indexOf(" at ");
			String tail = at < 0 ? line.substring(line.indexOf("TURNEDAT ") + 9)
				: line.substring(at + 4);
			String[] word = tail.strip().split(" ");
			if (word.length < 3) {
				continue;
			}
			try {
				if (Math.abs(Integer.parseInt(word[2]) - z) <= zEither) {
					lines.add(line);
				}
			} catch (NumberFormatException notAPosition) {
				// STARTOFF and friends do not all carry one. Kept out rather than guessed at.
			}
		}
		return lines;
	}

	/**
	 * The plan stood up as blocks and read back as a machine.
	 *
	 * <p>The only thing that can answer whether a build plays. {@code plan.breaches()} counts what the
	 * planner meant to do and reads nought over a machine whose wire is severed, so any change that
	 * moves which half of a chord lands on which side of a staircase has to come through here before
	 * it is believed.</p>
	 */
	static NoteMachineReader.Reading readBack(String name, SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3])), parse(word[4]));
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read(name, new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
	}

	static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}

	/** Everything about one breach on one page: the fault, the trace, and the blocks. */
	static void report(String heading, SongBuilder.PastePlan plan, List<String> trace,
			Overrun run) {
		System.out.println();
		System.out.println("######## " + heading + " -- " + run);
		System.out.println("   nearWall=" + plan.nearWall() + " farWall=" + plan.farWall()
			+ " breaches=" + plan.breaches());
		for (String fault : plan.faults()) {
			if (fault.startsWith("a lane turned")) {
				System.out.println("   fault " + fault);
			}
		}
		System.out.println("   -- the lane, in build order --");
		laneTrace(trace, run.z(), 1).forEach(line -> System.out.println("   " + line));
		System.out.println(draw(plan, run, 2, 1, 5));
	}
}
