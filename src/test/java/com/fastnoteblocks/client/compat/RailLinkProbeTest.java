package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
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
 * Which of the head's two links carries, asked one link at a time.
 *
 * <p>The rail's path chain reads back dead from its first hop, and the head is where it differs
 * from anything else in this build: everywhere else a block that goes on to drive a repeater is one
 * a repeater drives, and here it is one a dust points into. So the question is exactly whether dust
 * into a block hands that block's neighbour a signal, and a hand-built machine of five blocks
 * answers it without a song in the way.</p>
 */
@Tag("sweep")
class RailLinkProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void saysWhichLinksCarry() {
		// Every machine opens with a repeater with nothing behind it, which is the head of a machine.
		// Each lays a note at x=3 and a second at x=5, and the second is the one under test.
		report("repeater -> note -> repeater -> note", machine(false, false));
		report("repeater -> dust -> note -> repeater -> note", machine(true, false));
		report("repeater -> dust -> stone -> repeater -> note", machine(true, true));
	}

	/**
	 * @param dust whether a cell of dust stands between the opening repeater and the first block
	 * @param stone whether that first block is plain stone rather than a note block
	 */
	private static Map<BlockPos, BlockState> machine(boolean dust, boolean stone) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		int y = 64;
		for (int x = 0; x <= 7; x++) {
			world.put(new BlockPos(x, y, 0), parse("minecraft:stone"));
		}
		world.put(new BlockPos(0, y + 1, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		int first = dust ? 2 : 1;
		if (dust) {
			world.put(new BlockPos(1, y + 1, 0), parse("minecraft:redstone_wire"));
		}
		world.put(new BlockPos(first, y + 1, 0),
			parse(stone ? "minecraft:stone" : "minecraft:note_block[note=0]"));
		world.put(new BlockPos(first + 1, y + 1, 0),
			parse("minecraft:repeater[facing=west,delay=2]"));
		world.put(new BlockPos(first + 2, y + 1, 0), parse("minecraft:note_block[note=12]"));
		return world;
	}

	private static void report(String what, Map<BlockPos, BlockState> world) {
		NoteMachineReader.Reading reading = NoteMachineReader.read(what,
			new BlockPos(-1, 63, -1), new BlockPos(8, 66, 1),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
		Map<String, Long> heard = new LinkedHashMap<>();
		for (ComposerProject.Layer layer : reading.project().layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				heard.put("t=" + note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK
					+ " pitch=" + (note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE),
					1L);
			}
		}
		System.out.println("LINK " + what + " -> heard " + heard.keySet()
			+ " unreached=" + reading.unreachedNotes());
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
