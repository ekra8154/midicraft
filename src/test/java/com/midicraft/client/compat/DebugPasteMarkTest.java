package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * That a marked paste and a plain one are the same machine, differing only in what they are made of.
 *
 * <p>This is the whole safety of the thing. The colouring is meant to be a report written into the
 * blocks, and a report that changes what it describes is worse than no report -- an afternoon spent
 * on a chord that only exists because somebody was looking at it. Layout decisions in
 * {@code SongBuilder} do read blocks back ({@code
 * describeBlock(behind).startsWith("minecraft:stone")} is one), so this is not a theoretical worry;
 * it is the mistake the marking pass is arranged to avoid, and the arrangement is worth a test.</p>
 *
 * <p>So: build one song both ways and hold them to the same positions, in the same order, with the
 * same block at every cell that is not one of the marks. Then print what the marks came out as,
 * because a colour scheme nobody can read is a different kind of failure and the only way to know
 * is to look at the census.</p>
 */
class DebugPasteMarkTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void plainAgain() {
		SongBuilder.DEBUG_PASTE = false;
	}

	/** The live build limits, so the shapes counted here are the shapes they are looking at. */
	private static final SongBuilder.BuildLimits LIMITS = new SongBuilder.BuildLimits(16, 40, 3);

	private static List<SongBuilder.EventNote> song(String file) throws Exception {
		Path songs = Path.of("run", "config", "midicraft", "songs");
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(file.replace(".json", "")))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	private static SongBuilder.PastePlan plan(List<SongBuilder.EventNote> notes) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, LIMITS);
	}

	/** Which of the marks a block is, or null for anything the plain build would have laid too. */
	private static String markOf(String block) {
		return switch (block) {
			case "minecraft:tuff" -> "bus";
			case "minecraft:polished_tuff" -> "sunken bus";
			// The two machines' plain ground on a two-machine plan, brick for the odd half of the
			// game tick. Plain stone and plain tuff are already here as the lane and the bus.
			case "minecraft:stone_bricks" -> "machine A, odd half";
			case "minecraft:tuff_bricks" -> "machine B, odd half";
			// The pad family, added 2026-08-16 and never taught to this switch -- which is why
			// this method was failing on some 398 spruce and acacia cells long before either of
			// the marks above existed. See SongBuilder.padPlanks for the table.
			case "minecraft:spruce_planks" -> "parity pad";
			case "minecraft:dark_oak_planks" -> "busy pad";
			case "minecraft:birch_planks" -> "corner";
			case "minecraft:acacia_planks" -> "closing pad";
			case "minecraft:bamboo_planks" -> "pad";
			case "minecraft:andesite" -> "stacked chord";
			case "minecraft:deepslate" -> "stacked bus";
			case "minecraft:deepslate_tiles" -> "cut head";
			case "minecraft:cobbled_deepslate" -> "stacked simple";
			case "minecraft:smooth_basalt" -> "rail";
			case "minecraft:polished_basalt[axis=x]" -> "foldback descent";
			case "minecraft:polished_basalt[axis=y]" -> "foldback climb";
			case "minecraft:stripped_crimson_hyphae[axis=x]" -> "breach";
			case "minecraft:red_nether_bricks" -> "dead wire";
			case "minecraft:waxed_copper_bulb[lit=true]" -> "wrong note";
			case "minecraft:sea_lantern" -> "collision";
			default -> block.startsWith("minecraft:dragon_head") ? "missed note" : null;
		};
	}

	private static Map<BlockPos, String> laid(SongBuilder.PastePlan plan) {
		Map<BlockPos, String> blocks = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			blocks.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parts[4]);
		}
		return blocks;
	}

	@Test
	void aMarkedPasteIsTheSameMachineAsAPlainOne() throws Exception {
		List<SongBuilder.EventNote> notes = song("illit-do-the-dance.json");
		Map<BlockPos, String> plain = laid(plan(notes));
		SongBuilder.DEBUG_PASTE = true;
		SongBuilder.PastePlan markedPlan = plan(notes);
		Map<BlockPos, String> marked = laid(markedPlan);

		assertEquals(plain.keySet(), marked.keySet(),
			"a marked paste puts blocks in different places from a plain one");

		Map<String, Integer> census = new TreeMap<>();
		List<String> unexplained = new ArrayList<>();
		for (Map.Entry<BlockPos, String> cell : plain.entrySet()) {
			String was = cell.getValue();
			String now = marked.get(cell.getKey());
			if (was.equals(now)) {
				continue;
			}
			String mark = markOf(now);
			if (mark == null) {
				unexplained.add(cell.getKey().getX() + " " + cell.getKey().getY() + " "
					+ cell.getKey().getZ() + "  " + was + " -> " + now);
				continue;
			}
			census.merge(mark, 1, Integer::sum);
		}
		System.out.println("MARKED " + marked.size() + " blocks, " + census);
		unexplained.stream().limit(10).forEach(line -> System.out.println("  UNEXPLAINED " + line));
		assertTrue(unexplained.isEmpty(),
			unexplained.size() + " cells changed into something that is not one of the marks");
		// Not a target, just a floor: a build of this size is thousands of chords and every one of
		// them is a bus or a stacked something, so a census of nothing means the labels never landed.
		assertTrue(census.getOrDefault("bus", 0) + census.getOrDefault("stacked chord", 0)
			+ census.getOrDefault("stacked bus", 0) > 100,
			"the shape colours never landed: " + census);
	}

	/**
	 * That a build which breaches nothing is marked as breaching nothing.
	 *
	 * <p>The breach mark is the one that cannot be read off a label -- a lane past its wall is a fact
	 * about where blocks ended up, so it is worked out from the walls, and the walls are not where a
	 * build stops. Every build stands two columns outside them for reasons that are not faults, so
	 * the mark starts past that; and the moment it starts anywhere else it paints a thousand blocks
	 * of a perfectly good lane and means nothing at all.</p>
	 *
	 * <p>Four songs and four widths, of which twelve breach nothing. Those twelve are the test: if
	 * any of them comes out with a single block of hyphae in it, the allowance has moved.</p>
	 *
	 * <p>One way round only. A breach that marks nothing is not a failure and Guardian has one --
	 * at 24 wide over six floors it oversteps by a single column, and that column holds a chord and
	 * no lane at all, so there is no stone out there to colour. Nothing is going to be recoloured
	 * into it either: the block under a note decides the note's instrument.</p>
	 */
	@Test
	void aCleanLaneIsNeverMarkedAsBreaching() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		List<String> wrong = new ArrayList<>();
		for (String file : List.of("illit-do-the-dance.json", "deltarune-ch-4-guardian.json",
				"all-of-the-lights-kanye-west.json", "big-shot.json")) {
			if (!BreachView.inLibrary(file.replace(".json", ""))) {
				continue;
			}
			List<SongBuilder.EventNote> notes = song(file);
			for (int[] size : new int[][] {{40, 3}, {16, 4}, {12, 3}, {24, 6}}) {
				SongBuilder.PastePlan built = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, size[0], size[1]));
				long hyphae = built.commands().stream()
					.filter(command -> command.contains("stripped_crimson_hyphae")).count();
				if (built.breaches().isEmpty() && hyphae > 0) {
					wrong.add(file + " " + size[0] + "x" + size[1] + " breaches nothing but has "
						+ hyphae + " blocks marked as a breach");
				}
			}
		}
		wrong.forEach(line -> System.out.println("  BREACHMARK " + line));
		assertTrue(wrong.isEmpty(), "the breach mark disagrees with the breach count: " + wrong);
	}

	/**
	 * That the three marks a clean build never shows actually appear on a build that earns them.
	 *
	 * <p>The shape colours are on every block of every build and would be noticed the first time
	 * anybody looked. These are the opposite: a wrong note and a note with nothing to set it off are
	 * both things a good build has none of, and both are only ever wanted on the day something has
	 * gone wrong -- which is the worst possible day to find out the mark was never wired up.</p>
	 *
	 * <p>There is no broken build to borrow. Sixty-one songs at six widths each were swept and not
	 * one of them shows any of the three, which is the state the layout work has got the library
	 * into and not something to undo for a test. So the fault is made rather than found: {@link
	 * SongBuilder#RAIL_MOVES_FOR_STACKS} is the guard that moves a rail's single note off the side a
	 * stacked neighbour reaches into, and its own comment says what happens without it -- the note
	 * "sounds a stacked chord's tick instead of its own", which is a wrong note by construction.
	 * Turning it off is the cheapest honest way to have one.</p>
	 */
	@Test
	void theRareMarksAreReallyThere() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		SongBuilder.RAIL_MOVES_FOR_STACKS = false;
		SongBuilder.PastePlan plan;
		try {
			// The song the rails actually run in: a third of its coloured stone is rail.
			plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				song("all-of-the-lights-kanye-west.json"), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				LIMITS);
		} finally {
			SongBuilder.RAIL_MOVES_FOR_STACKS = true;
		}
		Map<String, Integer> census = new TreeMap<>();
		for (String command : plan.commands()) {
			String mark = markOf(command.split(" ", 5)[4].replace(" replace", ""));
			if (mark != null) {
				census.merge(mark, 1, Integer::sum);
			}
		}
		System.out.println("RAREMARKS wrong=" + plan.wrongNotes() + " faults=" + plan.faults().size()
			+ "  " + census);
		plan.faults().stream().limit(4).forEach(fault -> System.out.println("   " + fault));
		long silent = plan.faults().stream()
			.filter(fault -> fault.endsWith("has nothing to set it off")).count();
		long dead = plan.faults().stream()
			.filter(fault -> fault.contains("never be triggered")).count();
		// A note may be both early and doubled, and is one block either way, so the marks are counted
		// against the notes rather than against the faults.
		long wrong = plan.faults().stream().filter(fault -> fault.startsWith("the note at "))
			.filter(fault -> !fault.endsWith("has nothing to set it off"))
			.map(fault -> fault.substring(0, fault.indexOf(" belongs to tick "))).distinct().count();
		assertTrue(wrong > 0, "turning the rail guard off no longer makes a wrong note, so this test "
			+ "is checking nothing: " + plan.faults().size() + " faults");
		assertEquals(wrong, census.getOrDefault("wrong note", 0).longValue(),
			"a note that sounds at the wrong moment should be a copper bulb: " + census);
		assertEquals(silent, census.getOrDefault("missed note", 0).longValue(),
			"a note with nothing to set it off should wear a head: " + census);
		assertTrue(dead == 0 || census.getOrDefault("dead wire", 0) > 0,
			"the build has dead wire and none of it is marked: " + census);
	}

	/**
	 * That a diagram of a marked build explains itself to somebody who has never seen one.
	 *
	 * <p>The colours are only useful if a reader knows what they mean, and the way a build usually
	 * travels is as a slice of {@code /midicraft asciidiagram} pasted into a conversation -- to somebody, or to
	 * something, that was not there when it was made. A legend reading {@code TU  minecraft:tuff} is
	 * no help at all: the reader needs to be told that tuff is how a bus looks. So every block a
	 * marked paste uses carries its meaning into the legend, out of the one table the builder
	 * colours from.</p>
	 */
	@Test
	void aDiagramOfAMarkedBuildSaysWhatTheColoursMean() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		Map<BlockPos, String> marked = laid(plan(song("illit-do-the-dance.json")));
		Map<BlockPos, BlockState> world = new java.util.HashMap<>();
		for (Map.Entry<BlockPos, String> cell : marked.entrySet()) {
			world.put(cell.getKey(), BlockStateParser
				.parseForBlock(BuiltInRegistries.BLOCK, cell.getValue(), false).blockState());
		}
		// A slice through the middle of the build, wherever that lands, which is all anybody ever has.
		BlockPos low = new BlockPos(0, 60, 0);
		BlockPos high = new BlockPos(24, 70, 8);
		String drawn = AsciiDiagram.render(
			at -> world.getOrDefault(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),
			low, high, AsciiDiagram.View.TOP, AsciiDiagram.Shape.CODE);
		String legend = drawn.substring(drawn.indexOf("legend"));
		System.out.println(legend);
		// Whatever blocks that slice happens to hold, any of them that carry a meaning must say it.
		List<String> silent = SongBuilder.DEBUG_PASTE_KEY.entrySet().stream()
			.filter(entry -> legend.contains(entry.getKey()))
			.filter(entry -> !legend.contains(entry.getValue()))
			.map(Map.Entry::getKey).toList();
		assertTrue(silent.isEmpty(), "the legend names these blocks without saying what they "
			+ "mean: " + silent);
		assertTrue(legend.contains("color-coded paste:"),
			"a slice of a marked build should explain its colours: " + legend);
	}

	/**
	 * That a note is only ever recoloured where its sound does not depend on it.
	 *
	 * <p>A note block reads its instrument off the block underneath, so the one thing the colouring
	 * may never touch is a cell with a note on top of it -- tuff under a kick is still a kick, but
	 * stripped hyphae under one is a bass, and a build that quietly retunes a third of its percussion
	 * is exactly the kind of wrong that gets blamed on the composer.
	 */
	@Test
	void nothingUnderANoteIsRecoloured() throws Exception {
		List<SongBuilder.EventNote> notes = song("illit-do-the-dance.json");
		SongBuilder.DEBUG_PASTE = true;
		Map<BlockPos, String> marked = laid(plan(notes));
		List<String> retuned = new ArrayList<>();
		for (Map.Entry<BlockPos, String> cell : marked.entrySet()) {
			String above = marked.get(cell.getKey().above());
			if (above == null || !above.startsWith("minecraft:note_block")) {
				continue;
			}
			String mark = markOf(cell.getValue());
			// A collision keeps its lantern: that cell is a build refusing to be a machine at all, and
			// nothing about it is meant to still play.
			if (mark != null && !"collision".equals(mark)) {
				retuned.add(cell.getKey().getX() + " " + cell.getKey().getY() + " "
					+ cell.getKey().getZ() + "  " + cell.getValue() + " under " + above);
			}
		}
		retuned.stream().limit(10).forEach(line -> System.out.println("  RETUNED " + line));
		assertTrue(retuned.isEmpty(), retuned.size() + " notes had their instrument recoloured");
	}
}
