package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The diagram says which way round it is, and is.
 *
 * <p>A diagram that is quietly mirrored is worse than no diagram, because it is believed. So what is
 * checked here is handedness and headings rather than decoration: which way the columns run for a
 * given point of view, and which way an arrow points for a given repeater.</p>
 */
class AsciiDiagramTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private final Map<BlockPos, BlockState> world = new HashMap<>();

	private BlockState at(BlockPos pos) {
		return world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	private String draw(BlockPos from, BlockPos to, AsciiDiagram.View view) {
		return AsciiDiagram.render(this::at, from, to, view, AsciiDiagram.Shape.CODE);
	}

	private String draw(BlockPos from, BlockPos to, AsciiDiagram.View view,
			net.minecraft.core.Direction up, boolean numberNotes) {
		return AsciiDiagram.render(this::at, from, to, view, up, numberNotes,
			AsciiDiagram.Shape.CODE);
	}

	/** A sign with words becomes a footnote; the same sign without the lookup stays a block. */
	@Test
	void footnotesSigns() {
		world.put(new BlockPos(0, 64, 0), Blocks.OAK_SIGN.defaultBlockState());
		world.put(new BlockPos(1, 64, 0), Blocks.OAK_WALL_SIGN.defaultBlockState());
		world.put(new BlockPos(2, 64, 0), Blocks.STONE.defaultBlockState());
		String noted = AsciiDiagram.render(this::at, new BlockPos(0, 64, 0),
			new BlockPos(2, 64, 0), AsciiDiagram.View.TOP, null, false,
			position -> position.equals(new BlockPos(0, 64, 0)) ? "this is the fix" : null,
			AsciiDiagram.Shape.CODE);
		assertTrue(noted.contains("S1"), noted);
		assertTrue(noted.contains("sign: \"this is the fix\""), noted);
		// The wall sign carried no words, so it is drawn as the block it is, not numbered.
		assertFalse(noted.contains("S2"), noted);
		// And with no lookup at all, nothing is footnoted.
		String plain = draw(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0),
			AsciiDiagram.View.TOP);
		assertFalse(plain.contains("S1"), plain);
		assertTrue(plain.contains("minecraft:oak_sign"), plain);
	}

	/** Two blocks a step apart on each horizontal axis, for asking which way a map is turned. */
	private void twoApart() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		world.put(new BlockPos(1, 64, 1), Blocks.GLASS.defaultBlockState());
	}

	/** A box thrown round a build is shrunk to it, so nobody has to measure one first. */
	@Test
	void shrinksTheBoxToWhatIsInIt() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		String drawn = draw(new BlockPos(-10, 54, -10), new BlockPos(10, 74, 10),
			AsciiDiagram.View.TOP);
		assertTrue(drawn.startsWith("# 0 64 0  ..  0 64 0,"), drawn);
		assertEquals(1, drawn.lines().filter(line -> line.startsWith("## ")).count(), drawn);
	}

	/** The air inside stays: it is most of what a diagram gets read for. */
	@Test
	void keepsTheAirBetweenBlocks() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		world.put(new BlockPos(2, 64, 0), Blocks.GLASS.defaultBlockState());
		String drawn = draw(new BlockPos(-10, 54, -10), new BlockPos(10, 74, 10),
			AsciiDiagram.View.TOP);
		assertTrue(drawn.startsWith("# 0 64 0  ..  2 64 0,"), drawn);
		String row = drawn.lines().filter(line -> line.contains("NB")).findFirst().orElseThrow();
		assertTrue(row.contains("."), "the gap between them is still drawn: " + row);
	}

	/** A box with nothing in it is left as asked for, because that is how "nothing here" is said. */
	@Test
	void leavesAnEmptyBoxAlone() {
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1), AsciiDiagram.View.TOP);
		assertTrue(drawn.startsWith("# 0 64 0  ..  1 64 1,"), drawn);
	}

	/** Turned so south is up the page, which is the map rotated by half a turn. */
	@Test
	void turnsTheMapToPutSouthUp() {
		twoApart();
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1),
			AsciiDiagram.View.TOP, net.minecraft.core.Direction.SOUTH, false);
		assertTrue(drawn.contains("looking top, south up"), drawn);
		assertTrue(drawn.contains("columns are x, -x to the right; rows are z, -z downwards"),
			drawn);
		// z=1 is southernmost, so with south up it is the first row and x=1 is on the left.
		assertTrue(drawn.indexOf("GL") < drawn.indexOf("NB"), "south up puts z=1 first: " + drawn);
	}

	/** Turned a quarter: east up puts the rows on x and south on the right. */
	@Test
	void turnsTheMapToPutEastUp() {
		twoApart();
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1),
			AsciiDiagram.View.TOP, net.minecraft.core.Direction.EAST, false);
		assertTrue(drawn.contains("columns are z, +z to the right; rows are x, -x downwards"),
			drawn);
	}

	/** Left off, it is the map it always was, so nothing already drawn moves. */
	@Test
	void leavingUpOffIsNorthUp() {
		twoApart();
		assertEquals(draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1), AsciiDiagram.View.TOP),
			draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1),
				AsciiDiagram.View.TOP, net.minecraft.core.Direction.NORTH, false));
	}

	/** Seen from below the same map is mirrored, because east is now on your left. */
	@Test
	void looksUpAtTheCeilingMirrored() {
		twoApart();
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 1),
			AsciiDiagram.View.BOTTOM, null, false);
		assertTrue(drawn.contains("slices advance +y (away from you); columns are x, "
			+ "-x to the right"), drawn);
	}

	/** Asked for, a note block says which note it plays, in two digits so a column of them lines up. */
	@Test
	void numbersTheNotesWhenAsked() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState().setValue(
			net.minecraft.world.level.block.state.properties.BlockStateProperties.NOTE, 1));
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0),
			AsciiDiagram.View.TOP, null, true);
		assertTrue(drawn.contains("N01"), drawn);
		assertTrue(drawn.contains("nn = note, 00 to 24"), drawn);
		assertFalse(draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0), AsciiDiagram.View.TOP)
			.contains("N01"), "off by default");
	}

	/** Facing east, south is on the right -- so +z runs rightwards and the slices go up x. */
	@Test
	void looksEastWithSouthOnTheRight() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		world.put(new BlockPos(0, 64, 2), Blocks.GLASS.defaultBlockState());
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 2),
			AsciiDiagram.View.EAST);
		assertTrue(drawn.contains("slices advance +x (away from you); columns are z, "
			+ "+z to the right"), drawn);
		String row = drawn.lines().filter(line -> line.contains("NB")).findFirst().orElseThrow();
		assertTrue(row.indexOf("NB") < row.indexOf("GL"), "z=0 is left of z=2 looking east: " + row);
	}

	/** Facing west, the same two blocks come out the other way round. */
	@Test
	void looksWestWithTheColumnsMirrored() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		world.put(new BlockPos(0, 64, 2), Blocks.GLASS.defaultBlockState());
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 2),
			AsciiDiagram.View.WEST);
		String row = drawn.lines().filter(line -> line.contains("NB")).findFirst().orElseThrow();
		assertTrue(row.indexOf("GL") < row.indexOf("NB"), "z=2 is left of z=0 looking west: " + row);
	}

	/** Seen from above it is a map: north at the top, east to the right, highest floor first. */
	@Test
	void looksDownLikeAMap() {
		world.put(new BlockPos(0, 65, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		world.put(new BlockPos(1, 64, 1), Blocks.GLASS.defaultBlockState());
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 65, 1),
			AsciiDiagram.View.TOP);
		assertTrue(drawn.contains("columns are x, +x to the right; rows are z, +z downwards"),
			drawn);
		assertTrue(drawn.indexOf("y=65") < drawn.indexOf("y=64"), "the top floor comes first");
	}

	/**
	 * The arrow points the way the signal leaves, which is against {@code FACING}.
	 *
	 * <p>A repeater placed pointing east reads its input from the east and sends west, because
	 * {@code getInputSignal} looks at {@code pos.relative(FACING)}. Drawn from the property alone
	 * every arrow in every diagram would be backwards.</p>
	 */
	@Test
	void drawsTheArrowTheWayTheSignalLeaves() {
		world.put(new BlockPos(0, 64, 0), Blocks.REPEATER.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
			.setValue(RepeaterBlock.DELAY, 3));
		String top = draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0), AsciiDiagram.View.TOP);
		assertTrue(top.contains("<3"), "reads from the east, so it sends west: " + top);
		// And it runs straight along the line of sight, so it gets a head-on mark rather than an
		// arrow: away from somebody looking west, towards somebody looking east.
		String west = draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0), AsciiDiagram.View.WEST);
		assertTrue(west.contains("x3"), "sending west is away from a reader looking west: " + west);
		String east = draw(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0), AsciiDiagram.View.EAST);
		assertTrue(east.contains("o3"), "and towards one looking east: " + east);
	}

	/** Wire carries its power, because that is the number every argument is about. */
	@Test
	void spellsOutWhatTheWireIsWorth() {
		world.put(new BlockPos(0, 64, 0), Blocks.REDSTONE_WIRE.defaultBlockState()
			.setValue(RedStoneWireBlock.POWER, 15));
		world.put(new BlockPos(1, 64, 0), Blocks.REDSTONE_WIRE.defaultBlockState()
			.setValue(RedStoneWireBlock.POWER, 4));
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
			AsciiDiagram.View.TOP);
		String row = drawn.lines().filter(line -> line.contains("w15")).findFirst().orElseThrow();
		assertTrue(row.contains("w15") && row.contains("w4"), row);
		assertTrue(drawn.contains("minecraft:redstone_wire, n = power, 0 to 15"), drawn);
	}

	/** Two blocks that want the same two letters get told apart, and both reach the legend. */
	@Test
	void namesEveryBlockItAbbreviates() {
		world.put(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(1, 64, 0), Blocks.STONE_BRICKS.defaultBlockState());
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
			AsciiDiagram.View.TOP);
		// By the id alone, not by the whole line: a marked paste gives some blocks a meaning and the
		// legend says it after the id, which is the point of the legend and not something to pin.
		// What matters here is that two blocks whose names share a prefix get an entry each.
		List<String> named = drawn.lines()
			.filter(line -> line.trim().contains("  minecraft:"))
			.map(line -> line.substring(line.indexOf("minecraft:")).split(" ")[0])
			.toList();
		assertTrue(named.contains("minecraft:stone"), drawn);
		assertTrue(named.contains("minecraft:stone_bricks"), drawn);
		String row = drawn.lines().filter(line -> line.startsWith("z=")).findFirst().orElseThrow();
		String[] cells = row.trim().split("\\s+");
		assertEquals(3, cells.length, row);
		assertTrue(!cells[1].equals(cells[2]), "two blocks, two symbols: " + row);
	}

	/**
	 * A page of dots is not worth pasting, so an empty slice is counted rather than drawn.
	 *
	 * <p>The ones at the edges never get here any more -- the box is shrunk to what is in it first --
	 * so what this is left holding is the case that shrinking cannot reach: a floor of air with build
	 * above it and below it.</p>
	 */
	@Test
	void leavesOutTheSlicesThatAreNothing() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		world.put(new BlockPos(0, 67, 0), Blocks.GLASS.defaultBlockState());
		String drawn = draw(new BlockPos(0, 60, 0), new BlockPos(0, 70, 0),
			AsciiDiagram.View.TOP);
		assertTrue(drawn.startsWith("# 0 64 0  ..  0 67 0,"), drawn);
		assertTrue(drawn.contains("(2 slices were all air, left out)"), drawn);
		assertTrue(drawn.contains("y=64"), drawn);
		assertTrue(drawn.contains("y=67"), drawn);
	}
}
