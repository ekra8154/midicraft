package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
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
		assertTrue(drawn.contains("minecraft:stone\n"), drawn);
		assertTrue(drawn.contains("minecraft:stone_bricks"), drawn);
		String row = drawn.lines().filter(line -> line.startsWith("z=")).findFirst().orElseThrow();
		String[] cells = row.trim().split("\\s+");
		assertEquals(3, cells.length, row);
		assertTrue(!cells[1].equals(cells[2]), "two blocks, two symbols: " + row);
	}

	/** A box drawn round a build catches air, and a page of dots is not worth pasting. */
	@Test
	void leavesOutTheSlicesThatAreNothing() {
		world.put(new BlockPos(0, 64, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		String drawn = draw(new BlockPos(0, 64, 0), new BlockPos(0, 67, 0),
			AsciiDiagram.View.TOP);
		assertTrue(drawn.contains("(3 slices were all air, left out)"), drawn);
		assertTrue(drawn.contains("y=64"), drawn);
	}
}
