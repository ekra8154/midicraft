package com.midicraft.client.compat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The double-piston pair, alone on a bench, through the reader.
 *
 * <p>Four lines, matching the four command-block measurements the phase rule came from: a bare
 * piston (3), a repeater then a piston (4), a bare pair (5), and a repeater then a pair (7).
 * Prints the tick the far note block fires on; the numbers in brackets are the world's.</p>
 */
@Tag("sweep")
class PhasePairProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static void line(String name, String... cells) throws Exception {
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world = new HashMap<>();
		int x = 0;
		// A lever standing in for the button; the first wire sits on a note block, which fires
		// the moment the lever does and is the bench's zero. The far note's tick, less the
		// reference's, is the line's whole delay.
		put(world, x++, "minecraft:lever[face=floor,facing=east,powered=true]");
		put(world, x, "minecraft:redstone_wire[east=side,west=side]");
		world.put(new BlockPos(x++, 64, 0), net.minecraft.commands.arguments.blocks.BlockStateParser
			.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
				"minecraft:note_block[note=1]", false).blockState());
		for (String cell : cells) {
			put(world, x++, cell);
		}
		put(world, x++, "minecraft:redstone_wire[east=side,west=side]");
		put(world, x, "minecraft:note_block[note=0]");
		int last = x;
		NoteMachineReader.Reading reading = NoteMachineReader.read(name,
			new BlockPos(-1, 63, -1), new BlockPos(last + 1, 66, 1),
			pos -> world.getOrDefault(pos,
				net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
		long ticks = reading.project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(note -> note.startTick() / (NoteMachineReader.TICKS_PER_REDSTONE_TICK / 2))
			.max().orElse(-1);
		System.out.println("  " + name + ": note at +" + ticks + " gt, unreached="
			+ reading.unreachedNotes());
	}

	private static void put(Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world,
			int x, String block) throws Exception {
		BlockPos pos = new BlockPos(x, 65, 0);
		world.put(pos.below(), net.minecraft.commands.arguments.blocks.BlockStateParser
			.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
				"minecraft:stone", false).blockState());
		world.put(pos, net.minecraft.commands.arguments.blocks.BlockStateParser
			.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, block, false)
			.blockState());
	}

	@Test
	void theFourBenchLines() throws Exception {
		String piston = "minecraft:sticky_piston[facing=east]";
		String block = "minecraft:redstone_block";
		String repeater = "minecraft:repeater[facing=west,delay=1]";
		line("bare piston        [3]", piston, block, "minecraft:air");
		line("repeater + piston  [4]", repeater, piston, block, "minecraft:air");
		line("bare pair          [5]", piston, block, "minecraft:air", piston, block,
			"minecraft:air");
		line("repeater + pair    [7]", repeater, piston, block, "minecraft:air", piston, block,
			"minecraft:air");
	}
}
