package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The live half-tick input, block for block, read back off the world.
 *
 * <p>Transcribed from a scan of the real thing rather than invented, because what it caught was a
 * fault every made-up example had missed. One switch drives two chains: one straight through a
 * repeater, and one through a sticky piston that shoves a block of redstone across a cell of air
 * into the second chain's wire. The air is not optional -- a block of redstone already touching
 * that wire would drive its lane from the moment the machine loaded -- and it is what makes the
 * second lane, read as it stands, a chain nothing feeds.</p>
 *
 * <p>The fault: dust handed its signal to a side block only when that block was a conductor, and a
 * piston is not one. So dust lying against a piston never told it anything, the switch's walk
 * stopped dead at it, and the only thing that reached the second lane was the block of redstone
 * sitting on the piston's face -- read as a machine in its own right, timed from its own first
 * note. Two versions, no relation between them, and the game tick between the lanes gone. Every
 * note was individually right, which is why it read as a perfectly ordinary song that happened to
 * want only one lane.</p>
 */
class HalfTickInputReadBackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private final Map<BlockPos, BlockState> world = new HashMap<>();

	private void put(int x, int y, int z, String block) {
		world.put(new BlockPos(x, y, z), parse(block));
	}

	@Test
	void readsBothLanesOfTheHandBuiltInputAsOneMachineAHalfTickApart() {
		// A lever where the real thing has a button; the button gets its own test below, because
		// what starts a machine is exactly the thing this file exists to be sure of.
		put(-503, 39, -353, "minecraft:lever[face=floor,facing=north,powered=true]");
		put(-503, 38, -353, "minecraft:stone");

		// The piston chain: switch, dust, piston, the block it shoves, and the cell of air.
		put(-503, 39, -354, "minecraft:redstone_wire");
		put(-503, 38, -354, "minecraft:stone");
		put(-503, 39, -355, "minecraft:sticky_piston[facing=east,extended=false]");
		put(-502, 39, -355, "minecraft:redstone_block");
		for (int x = -500; x <= -497; x++) {
			put(x, 39, -355, "minecraft:redstone_wire");
			put(x, 38, -355, "minecraft:stone");
		}
		put(-496, 39, -355, "minecraft:note_block[note=1]");

		// The repeater chain off the same switch. facing names the side the signal arrives from.
		put(-503, 39, -352, "minecraft:redstone_wire");
		put(-502, 39, -352, "minecraft:repeater[facing=west,delay=1]");
		put(-501, 39, -352, "minecraft:redstone_wire");
		put(-500, 39, -352, "minecraft:redstone_wire");
		put(-500, 39, -351, "minecraft:redstone_wire");
		put(-500, 39, -350, "minecraft:note_block[note=0]");
		for (int x = -503; x <= -500; x++) {
			put(x, 38, -352, "minecraft:stone");
		}
		put(-500, 38, -351, "minecraft:stone");

		NoteMachineReader.Reading reading = NoteMachineReader.read("World scan",
			new BlockPos(-506, 36, -360), new BlockPos(-495, 42, -349),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));

		assertEquals(0, reading.unreachedNotes(), "both lanes have to be reached");
		// One machine, not two. Two would mean each lane timed from its own first note, which is
		// exactly how the tick between them goes missing.
		assertEquals(1, reading.versions(),
			"one switch drives both lanes, so this is one performance: " + reading.report());

		Map<Integer, Long> heard = new TreeMap<>();
		reading.project().layers().forEach(layer -> layer.notes().forEach(note ->
			heard.put(note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE,
				note.startTick() / NoteMachineReader.TICKS_PER_GAME_TICK)));
		// Repeater chain: one delay-1 repeater, so game tick 2. Piston chain: three game ticks for
		// the push. One apart, and odd -- which no arrangement of repeaters can produce, and which
		// is therefore proof the push was followed and the phase between the lanes survived.
		assertEquals(Map.of(0, 0L, 1, 1L), heard,
			"the piston lane sounds one game tick after the repeater lane: " + heard);
	}

	/**
	 * The same machine started by the button the layouts actually lay.
	 *
	 * <p>Worth its own test, and not merely a parameter on the one above. Buttons used to be
	 * recognised through {@code BlockTags.BUTTONS}, and block tags are datapack contents: loaded in
	 * game, empty under a bare bootstrap. So the reader said one thing in the world and the
	 * opposite on the bench, and no test could have caught it, because the file that had already
	 * met the fault worked around it by swapping in a lever and saying so in a comment.</p>
	 *
	 * <p>What it cost: the interleaved layout is the one whose input is a button, and every
	 * interleaved build in the library came back with one of its two machines reporting every note
	 * dead -- 24,412 notes over nine builds, none of them true. A checker that answers differently
	 * depending on whether a datapack is loaded is worse than no checker.</p>
	 */
	@Test
	void readsAMachineStartedByAButton() {
		put(-503, 39, -353, "minecraft:oak_button[face=floor,facing=north,powered=true]");
		put(-503, 38, -353, "minecraft:stone");
		// Straight along z: a dust with one neighbour is a line on that axis, so this run points
		// into the note block at its end. Bent round a corner it would point along the bend and the
		// note would never hear it -- which is a fact about dust, not about ways in.
		for (int z = -352; z <= -351; z++) {
			put(-503, 39, z, "minecraft:redstone_wire");
			put(-503, 38, z, "minecraft:stone");
		}
		put(-503, 39, -350, "minecraft:note_block[note=0]");

		NoteMachineReader.Reading reading = NoteMachineReader.read("Button", new BlockPos(-506, 36,
			-360), new BlockPos(-495, 42, -349),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));

		assertEquals(0, reading.unreachedNotes(),
			"a button is a way in: " + reading.report());
	}

	/** The same again for a pressure plate, the other source that used to be found by tag. */
	@Test
	void readsAMachineStartedByAPressurePlate() {
		put(-503, 39, -353, "minecraft:stone_pressure_plate[powered=true]");
		put(-503, 38, -353, "minecraft:stone");
		for (int z = -352; z <= -351; z++) {
			put(-503, 39, z, "minecraft:redstone_wire");
			put(-503, 38, z, "minecraft:stone");
		}
		put(-503, 39, -350, "minecraft:note_block[note=0]");

		NoteMachineReader.Reading reading = NoteMachineReader.read("Plate", new BlockPos(-506, 36,
			-360), new BlockPos(-495, 42, -349),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));

		assertEquals(0, reading.unreachedNotes(),
			"a pressure plate is a way in: " + reading.report());
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}
}
