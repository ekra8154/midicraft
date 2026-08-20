package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * A piston moving a block of redstone, read back off the blocks.
 *
 * <p>The one delay in redstone that is not a whole repeater tick. Everything else a note machine is
 * made of moves in twos -- a repeater set to one is two game ticks -- so a reader counting repeater
 * ticks lost nothing by counting them. A piston takes three, and a chain tapped off one therefore
 * runs permanently on the half of the clock repeaters cannot reach. That is what half ticking is,
 * and a build using it reads back as nonsense unless the reader counts in game ticks.</p>
 */
class PistonReadBackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private final Map<BlockPos, BlockState> world = new HashMap<>();

	private void put(int x, int y, int z, String block) {
		world.put(new BlockPos(x, y, z), parse(block));
	}

	/**
	 * A lever, a piston, and a note block the piston's redstone block lands beside.
	 *
	 * <p>Laid out along x. The spacer between piston and redstone block is not decoration: a block of
	 * redstone touching a piston powers it, so a retracted piston with one on its face could not
	 * exist in a loaded world. Every real half-ticked build has something in between, and the piston
	 * shoves the pair.</p>
	 */
	@Test
	void soundsANoteThreeGameTicksAfterThePistonIsPowered() {
		// The lever, and a note block it sounds directly -- the moment everything else is measured
		// against.
		put(0, 64, 0, "minecraft:stone");
		put(0, 65, 0, "minecraft:lever[face=floor,facing=north,powered=true]");
		put(0, 65, 1, "minecraft:note_block[note=0]");

		// The piston the lever also powers, its spacer, and the block of redstone behind that.
		put(1, 65, 0, "minecraft:piston[facing=east,extended=false]");
		put(2, 65, 0, "minecraft:stone");
		put(3, 65, 0, "minecraft:redstone_block");
		// Where the block of redstone ends up, and the note block waiting there for it.
		put(4, 65, 1, "minecraft:note_block[note=1]");

		Map<Integer, Long> heard = play();
		assertEquals(Map.of(0, 0L, 1, 3L), heard,
			"the note beside the lever sounds at once and the note beside the pushed block "
				+ "three game ticks later, which no number of repeaters could have produced");
	}

	/**
	 * The piston's three ticks land between two repeater ticks, which is the whole point.
	 *
	 * <p>A repeater chain can only ever put a note on an even game tick counted from the start. This
	 * asserts the odd one: the piston's note falls strictly between the notes either side of it, so
	 * the two chains together play at twice the rate either could alone.</p>
	 */
	@Test
	void putsANoteBetweenTwoTheRepeatersCanReach() {
		put(0, 64, 0, "minecraft:stone");
		put(0, 65, 0, "minecraft:lever[face=floor,facing=north,powered=true]");

		// A plain repeater chain. Each note block stands in front of a repeater, not beside one --
		// a repeater powers the block it faces and nothing else. Delay 1 is two game ticks, so this
		// sounds on 2 and on 4, and there is no arrangement of repeaters that sounds on 3.
		// facing names the side the signal comes in from, so a repeater carrying it east faces west.
		put(1, 65, 0, "minecraft:repeater[facing=west,delay=1]");
		put(2, 65, 0, "minecraft:note_block[note=0]");
		put(3, 65, 0, "minecraft:repeater[facing=west,delay=1]");
		put(4, 65, 0, "minecraft:note_block[note=2]");

		// And the piston chain off the same lever, landing on 3.
		put(0, 65, 1, "minecraft:piston[facing=south,extended=false]");
		put(0, 65, 2, "minecraft:stone");
		put(0, 65, 3, "minecraft:redstone_block");
		put(1, 65, 4, "minecraft:note_block[note=1]");

		// Timed from the first note the machine sounds, which is the repeater chain's at game tick
		// two, so what these say is the spacing rather than the absolute moment.
		Map<Integer, Long> heard = play();
		assertEquals(2L, heard.get(2) - heard.get(0),
			"two repeater notes a delay-1 repeater apart are two game ticks apart: " + heard);
		assertEquals(1L, heard.get(1) - heard.get(0),
			"and the piston's note falls one game tick after the first, which is the half of the "
				+ "clock a repeater cannot reach: " + heard);
		assertTrue(heard.get(0) < heard.get(1) && heard.get(1) < heard.get(2),
			"it has to fall between them, not on one of them: " + heard);
	}

	/**
	 * The arrangement a half-ticked build is actually made of: a lane fed by nothing until the
	 * block arrives.
	 *
	 * <p>The detail the tests above both miss. The block of redstone cannot start out
	 * touching the wire it is meant to drive, or that lane would run from the moment the machine
	 * loaded -- so there is a cell of air between them, and the push closes it. Which means the left
	 * lane, read as it stands, is a repeater nobody feeds: the reader's own definition of a way in.
	 * So it is offered as a beginning in its own right at the same time as the lever's walk arrives
	 * at it three game ticks late, and the two readings have to collapse to the later one. Kept
	 * apart, each is timed from its own first note, the game tick between the lanes is lost, and the
	 * song comes back with every gap a whole repeater tick -- which is to say quantised, and
	 * apparently playable on one lane.</p>
	 */
	@Test
	void keepsTheOffsetWhenTheSecondLaneIsFedOnlyByThePush() {
		put(0, 64, 0, "minecraft:stone");
		put(0, 65, 0, "minecraft:lever[face=floor,facing=north,powered=true]");

		// The right lane, straight off the lever: a note on game tick 2.
		put(1, 65, 0, "minecraft:repeater[facing=west,delay=1]");
		put(2, 65, 0, "minecraft:note_block[note=0]");

		// The piston, and the block it shoves.
		put(0, 65, 1, "minecraft:piston[facing=south,extended=false]");
		put(0, 65, 2, "minecraft:stone");
		put(0, 65, 3, "minecraft:redstone_block");
		// (0,65,4) is the cell of air the block moves into, and is deliberately left empty.

		// The left lane. Its head repeater faces the landing cell and is fed by nothing at all until
		// the block gets there, so at rest it reads as a way into the machine of its own.
		put(0, 65, 5, "minecraft:repeater[facing=north,delay=1]");
		put(0, 65, 6, "minecraft:note_block[note=1]");

		Map<Integer, Long> heard = play();
		// Right lane: one repeater, so game tick 2. Left lane: three for the push and two more for
		// its own repeater, so game tick 5. Three apart.
		assertEquals(3L, heard.get(1) - heard.get(0),
			"the left lane is driven through the piston and lands three game ticks after the "
				+ "right: " + heard);
		// The claim underneath, and the one that would survive rewiring the example: the gap is odd.
		// Every delay a repeater can make is an even number of game ticks, so two lanes that had
		// lost their offset -- each timed from its own first note -- could only ever be an even
		// number apart. An odd gap is proof the push was followed and the phase kept.
		assertEquals(1L, (heard.get(1) - heard.get(0)) % 2,
			"the two lanes have to end up an odd number of game ticks apart, which is the half "
				+ "tick itself and is unreachable by repeaters: " + heard);
	}

	/** Without the piston understood, the note beyond it is simply never reached. */
	@Test
	void reachesEveryNoteBlockThroughThePiston() {
		put(0, 64, 0, "minecraft:stone");
		put(0, 65, 0, "minecraft:lever[face=floor,facing=north,powered=true]");
		put(1, 65, 0, "minecraft:piston[facing=east,extended=false]");
		put(2, 65, 0, "minecraft:stone");
		put(3, 65, 0, "minecraft:redstone_block");
		put(4, 65, 1, "minecraft:note_block[note=5]");

		assertEquals(0, read().unreachedNotes(),
			"the note past the piston has to be reachable, or the build reads as broken");
	}

	/** Each note block's pitch against the game tick it sounded on, counted from the first. */
	private Map<Integer, Long> play() {
		NoteMachineReader.Reading reading = read();
		assertEquals(0, reading.unreachedNotes(), "every note block has to be reached; missing "
			+ reading.unreachedAt() + ", versions " + reading.versions());
		Map<Integer, Long> heard = new TreeMap<>();
		for (ComposerProject.Layer layer : reading.project().layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				heard.put(note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE,
					note.startTick() / NoteMachineReader.TICKS_PER_GAME_TICK);
			}
		}
		return heard;
	}

	private NoteMachineReader.Reading read() {
		int minX = world.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow() - 1;
		int minY = world.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow() - 1;
		int minZ = world.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow() - 1;
		int maxX = world.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow() + 1;
		int maxY = world.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow() + 1;
		int maxZ = world.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow() + 1;
		return NoteMachineReader.read("Piston", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
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
