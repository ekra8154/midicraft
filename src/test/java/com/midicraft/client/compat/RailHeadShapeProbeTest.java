package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The opening of the dead run, lifted out of the song and built by hand.
 *
 * <p>Their build is eight wide over two floors and the whole of it after one head is silent, which
 * is far too much machine to reason about. This is the same blocks in the same places -- the head's
 * repeater and dust, the first path column, the floor column after it and the column that ends the
 * run -- with nothing else within reach, so whichever hop does not carry has nowhere to hide.</p>
 */
@Tag("sweep")
class RailHeadShapeProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Floor level, as the lane has it. The path runs one above and a repeater's support one below. */
	private static final int FLOOR = 68;

	@Test
	void saysWhichHopOfTheRunCarries() {
		report("the run on its own", world(false, false));
		report("the run reached by the climb that feeds it", world(false, true));
		// A run that stops at its opening column, which is the one column the head's dust drives
		// rather than a repeater. Reading a stone with wire either side of it: "issue is
		// redstone line going both into and out of this block".
		report("a run of one column, carrying on in dust", stub(false));
		report("a run of one column, carrying on through a repeater", stub(true));
		// Whether the column a run opens on may keep its centre for a harp note. It is the one column the
		// head's dust drives, and it gave the centre away to a stone for a long time on the grounds that
		// dust does not hand a note block on to the repeater in front of it. It does: the block after the
		// centre is what says so, and it is reached either way. In-game testing, which would not have it
		// -- "a note block can be powered just like a stone, there's no difference".
		report("an opening centre of stone, one note hung", opening(false, 1));
		report("an opening centre of note, nothing hung", opening(true, 1));
		report("an opening centre of note, one note hung", opening(true, 2));
		report("an opening centre of stone, two notes hung", opening(false, 2));
		// The transition. A stacked module already lays a cross of dust on a stone at the lane's
		// floor level, which is the head's two blocks in the head's two places -- so the run needs no
		// head, only the trigger column every module pays for. Ticks are what this has to get right:
		// the floor rail is seeded at the stacked chord's own tick, a tick earlier than a head's
		// would be.
		report("a run opening off a stacked chord", fromStack(true));
		report("a run opening off a stacked chord with a stone centre", fromStack(false));
	}

	/**
	 * The tail of a stacked module, then a run opening straight off it.
	 *
	 * <p>Expected: the module at tick 0, the run's first chord at 2 where the trigger repeater puts
	 * it, the floor rail's first chord at 3, and the path rail's second at 5.</p>
	 *
	 * @param centreIsNote whether the stacked module's centre is a note block or a stone, since both
	 *     happen and both have to light the dust under them
	 */
	private static Map<BlockPos, BlockState> fromStack(boolean centreIsNote) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		// The stacked module: its trigger, then its centre with the dust cross on stone beneath.
		put(world, 8, FLOOR, "minecraft:stone");
		put(world, 8, FLOOR + 1, "minecraft:repeater[facing=east,delay=1]");
		put(world, 7, FLOOR + 1, centreIsNote ? "minecraft:note_block[note=3]" : "minecraft:stone");
		put(world, 7, FLOOR + 2, "minecraft:air");
		put(world, 7, FLOOR, "minecraft:redstone_wire[north=side,east=side,south=side,west=side]");
		put(world, 7, FLOOR - 1, "minecraft:stone");
		// The trigger column, which is all a run off a stack costs. Its stone is already powered by
		// the cross behind it, so the floor rail starts here without a column of its own.
		put(world, 6, FLOOR, "minecraft:stone");
		put(world, 6, FLOOR + 1, "minecraft:repeater[facing=east,delay=2]");
		// The run's first path column: its chord, and the repeater pulling the floor rail out.
		put(world, 5, FLOOR + 1, "minecraft:note_block[note=7]");
		put(world, 5, FLOOR + 2, "minecraft:air");
		put(world, 5, FLOOR, "minecraft:repeater[facing=east,delay=3]");
		put(world, 5, FLOOR - 1, "minecraft:stone");
		// The floor column, and the path column after it.
		put(world, 4, FLOOR, "minecraft:stone");
		put(world, 4, FLOOR + 1, "minecraft:repeater[facing=east,delay=3]");
		hang(world, 4, FLOOR, 20);
		put(world, 3, FLOOR + 1, "minecraft:note_block[note=15]");
		put(world, 3, FLOOR + 2, "minecraft:air");
		return world;
	}

	/**
	 * The head and the column it opens on, and nothing after it.
	 *
	 * @param centreIsNote whether a harp note takes the centre rather than a stone
	 * @param notes how many notes the chord holds altogether
	 */
	private static Map<BlockPos, BlockState> opening(boolean centreIsNote, int notes) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		put(world, 7, FLOOR, "minecraft:stone");
		put(world, 7, FLOOR + 1, "minecraft:repeater[facing=east,delay=2]");
		put(world, 6, FLOOR, "minecraft:stone");
		put(world, 6, FLOOR + 1, "minecraft:redstone_wire");
		put(world, 5, FLOOR + 1, centreIsNote ? "minecraft:note_block[note=7]" : "minecraft:stone");
		put(world, 5, FLOOR + 2, "minecraft:air");
		// The floor rail this column starts, so the run is a real one rather than a stub.
		put(world, 5, FLOOR, "minecraft:repeater[facing=east,delay=2]");
		put(world, 5, FLOOR - 1, "minecraft:stone");
		put(world, 4, FLOOR, "minecraft:stone");
		put(world, 4, FLOOR + 1, "minecraft:repeater[facing=east,delay=2]");
		// And the path column after it, which is the whole point: the repeater above the floor column
		// reads this centre, so a centre that cannot relay ends the path chain rather than merely
		// losing its own note.
		put(world, 3, FLOOR + 1, "minecraft:note_block[note=15]");
		put(world, 3, FLOOR + 2, "minecraft:air");
		int hung = notes - (centreIsNote ? 1 : 0);
		for (int index = 0; index < hung; index++) {
			int z = index == 0 ? -1 : 1;
			world.put(new BlockPos(5, FLOOR + 1, z),
				parse("minecraft:note_block[note=" + (11 + index) + "]"));
			world.put(new BlockPos(5, FLOOR, z), parse("minecraft:air"));
			world.put(new BlockPos(5, FLOOR + 2, z), parse("minecraft:air"));
		}
		return world;
	}

	/** Head, one column, and then whatever the lane lays next. */
	private static Map<BlockPos, BlockState> stub(boolean repeater) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		put(world, 7, FLOOR, "minecraft:stone");
		put(world, 7, FLOOR + 1, "minecraft:repeater[facing=east,delay=2]");
		put(world, 6, FLOOR, "minecraft:stone");
		put(world, 6, FLOOR + 1, "minecraft:redstone_wire");
		put(world, 5, FLOOR + 1, "minecraft:stone");
		put(world, 5, FLOOR, "minecraft:stone");
		hang(world, 5, FLOOR + 1, 11);
		put(world, 4, FLOOR, "minecraft:stone");
		put(world, 4, FLOOR + 1,
			repeater ? "minecraft:repeater[facing=east,delay=1]" : "minecraft:redstone_wire");
		put(world, 3, FLOOR, "minecraft:stone");
		put(world, 3, FLOOR + 1, "minecraft:note_block[note=15]");
		put(world, 3, FLOOR + 2, "minecraft:air");
		return world;
	}

	private static Map<BlockPos, BlockState> world(boolean fillTail, boolean climb) {
		Map<BlockPos, BlockState> world = world(fillTail);
		if (!climb) {
			return world;
		}
		// The lane a floor below, and the glass staircase it rises on. This is the one thing the run
		// has around it in the build that it does not have here, so it is the one thing to add.
		put(world, 6, FLOOR - 4, "minecraft:stone");
		put(world, 6, FLOOR - 3, "minecraft:repeater[facing=west,delay=2]");
		put(world, 7, FLOOR - 4, "minecraft:stone");
		put(world, 7, FLOOR - 3, "minecraft:redstone_wire");
		put(world, 8, FLOOR - 4, "minecraft:stone");
		put(world, 8, FLOOR - 3, "minecraft:redstone_wire");
		put(world, 9, FLOOR - 3, "minecraft:glass");
		put(world, 9, FLOOR - 2, "minecraft:redstone_wire");
		put(world, 8, FLOOR - 2, "minecraft:glass");
		put(world, 8, FLOOR - 1, "minecraft:redstone_wire");
		put(world, 9, FLOOR - 1, "minecraft:glass");
		put(world, 9, FLOOR, "minecraft:redstone_wire");
		put(world, 8, FLOOR, "minecraft:glass");
		put(world, 8, FLOOR + 1, "minecraft:redstone_wire");
		return world;
	}

	/**
	 * @param fillTail whether the column ending the run gets stone under its note, which is the one
	 *     cell the shape leaves out
	 */
	private static Map<BlockPos, BlockState> world(boolean fillTail) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		// The head: a repeater with nothing behind it, which is how the reader knows where to start,
		// and then the dust that starts the second chain.
		put(world, 7, FLOOR, "minecraft:stone");
		put(world, 7, FLOOR + 1, "minecraft:repeater[facing=east,delay=2]");
		put(world, 6, FLOOR, "minecraft:stone");
		put(world, 6, FLOOR + 1, "minecraft:redstone_wire");
		// First path column: a stone centre, because the head's dust drives it, and the repeater
		// under it that starts the floor rail.
		put(world, 5, FLOOR + 1, "minecraft:stone");
		put(world, 5, FLOOR, "minecraft:repeater[facing=east,delay=2]");
		put(world, 5, FLOOR - 1, "minecraft:stone");
		hang(world, 5, FLOOR + 1, 11);
		// The floor column, and its repeater driving the path column after it.
		put(world, 4, FLOOR, "minecraft:stone");
		put(world, 4, FLOOR + 1, "minecraft:repeater[facing=east,delay=4]");
		hang(world, 4, FLOOR, 20);
		// The column the run ends on: a harp note takes the centre, so nothing is laid beneath it.
		put(world, 3, FLOOR + 1, "minecraft:note_block[note=11]");
		put(world, 3, FLOOR + 2, "minecraft:air");
		hang(world, 3, FLOOR + 1, 15);
		if (fillTail) {
			put(world, 3, FLOOR, "minecraft:stone");
		}
		return world;
	}

	/** A note hung either side of the cell that drives it, the way a rail column carries a chord. */
	private static void hang(Map<BlockPos, BlockState> world, int x, int y, int note) {
		for (int z : new int[] {-1, 1}) {
			world.put(new BlockPos(x, y, z), parse("minecraft:note_block[note=" + note + "]"));
			world.put(new BlockPos(x, y - 1, z), parse("minecraft:air"));
			world.put(new BlockPos(x, y + 1, z), parse("minecraft:air"));
		}
	}

	private static void put(Map<BlockPos, BlockState> world, int x, int y, String block) {
		world.put(new BlockPos(x, y, 0), parse(block));
	}

	private static void report(String what, Map<BlockPos, BlockState> world) {
		NoteMachineReader.Reading reading = NoteMachineReader.read(what,
			new BlockPos(0, FLOOR - 3, -3), new BlockPos(12, FLOOR + 4, 3),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
		Map<String, Long> heard = new LinkedHashMap<>();
		for (ComposerProject.Layer layer : reading.project().layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				heard.merge("t=" + note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK
					+ " pitch=" + (note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE),
					1L, Long::sum);
			}
		}
		System.out.println("SHAPE " + what + " -> heard " + heard
			+ " unreached=" + reading.unreachedNotes() + " at " + reading.unreachedAt().stream()
				.map(at -> at.getX() + " " + at.getY() + " " + at.getZ()).toList());
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (Exception broken) {
			throw new IllegalStateException("could not parse " + blockState, broken);
		}
	}
}
