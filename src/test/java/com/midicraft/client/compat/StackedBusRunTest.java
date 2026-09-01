package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Can one stacked-bus follow another with nothing between them?
 *
 * <p>The question, and the smallest case that answers it: a chord of eight is seven notes in
 * the head and one left over, which is a single cell of bus. If one cell is enough to hand the back
 * slots on, a run of eights is a run of stacked-buses; if the rule is really about the shape rather
 * than about where the shape ends, every other one drops to a plain bus.</p>
 */
class StackedBusRunTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Chords of a fixed size, back to back, with no gap for a delay repeater to fill. */
	private static List<SongBuilder.EventNote> runOf(int size, int count) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		for (int event = 0; event < count; event++) {
			int time = event + 1;
			for (int index = 0; index < size; index++) {
				// All harp: the head wants a harp for its centre and conductors for its relays, and
				// an instrument refusal would answer a different question than the one being asked.
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					index % 25, "minecraft:air"));
			}
		}
		return notes;
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 48, 1));
	}

	@Test
	void aRunOfEightsIsARunOfStackedBuses() {
		int count = 40;
		SongBuilder.PastePlan plan = build(runOf(8, count));
		int heads = plan.padding().getOrDefault("busHandover", 0);
		System.out.println("RUN eights: " + heads + " stacked-buses out of " + count + " chords");
		// Only the planBusFor* entries are refusals. A parity entry is a chord that kept its head
		// and moved a column, which is a different and much cheaper thing.
		plan.padding().forEach((key, value) -> {
			if (key.startsWith("planBusFor")) {
				System.out.println("RUN refused " + key + " " + value);
			} else if (key.startsWith("planParity")) {
				System.out.println("RUN nudged " + key + " " + value);
			}
		});
		assertTrue(heads > count / 2,
			"a chord of eight leaves one cell of bus, which should be enough to hand the back "
				+ "slots on, so most of a run of them should keep the head");
	}

	/**
	 * And with no fold in the way, every single one keeps its head.
	 *
	 * <p>A chord of eight is four columns -- head, head, transition, one cell of bus -- so ten of
	 * them fit inside a corridor forty-eight wide and the lane never turns. That isolates the rule
	 * being asked about from the two that are nothing to do with it: a chord standing inside a turn
	 * gives the shape up, and a chord straight after one has its back slots taken by the turn's own
	 * run of powered stone.</p>
	 */
	@Test
	void withNoTurnInTheWayEveryOneKeepsItsHead() {
		int count = 10;
		int heads = build(runOf(8, count)).padding().getOrDefault("busHandover", 0);
		System.out.println("RUN eights on one lane: " + heads + " of " + count);
		assertEquals(count, heads,
			"nothing but one stacked-bus behind another, and one cell of bus is enough");
	}

	/** And the old rule really did stop it, so the test above is testing something. */
	@Test
	void theOldRuleTookEveryOtherOne() {
		SongBuilder.GAP_ENDS_ON_BUS = false;
		try {
			int count = 40;
			int heads = build(runOf(8, count)).padding().getOrDefault("busHandover", 0);
			System.out.println("RUN eights under the old rule: " + heads + " of " + count);
			assertTrue(heads < count, "the old rule was supposed to refuse some of these");
		} finally {
			SongBuilder.GAP_ENDS_ON_BUS = true;
		}
	}

	/** And a run of them is still the song it was built from. */
	@Test
	void theRunReadsBack() {
		for (int size : new int[] {8, 9, 12, 20}) {
			SongBuilder.PastePlan plan = build(runOf(size, 40));
			Map<BlockPos, BlockState> world = new HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parse(parts[4]));
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
			NoteMachineReader.Reading reading = NoteMachineReader.read("Run",
				new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
				position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
			assertEquals(0, reading.unreachedNotes(),
				"chords of " + size + ": note blocks the signal never got to");
			int sounded = 0;
			for (var layer : reading.project().layers()) {
				sounded += layer.notes().size();
			}
			assertEquals(size * 40, sounded, "chords of " + size + ": notes read back");
		}
	}

	/**
	 * The three chords that used to lose the head entirely, now built as a head of six.
	 *
	 * <p>Each is seven notes with nothing that can sit in the centre: no harp to spare, or a third
	 * snare, or nothing solid left after both relays. The centre is the seventh slot, so six of the
	 * seven still fit the hangers and the odd one goes on the bus. These are the cases
	 * {@code SongBuilderTest} pinned to "and so it is a bus", and re-pinning them is only honest if
	 * the machine they now build actually sounds right -- the rules they encode are physical ones
	 * about sand needing a prop and glass not conducting.</p>
	 */
	@Test
	void theSevensThatCannotFillTheCentreStillReadBack() {
		String harp = "minecraft:air";
		String bassDrum = "minecraft:stone";
		String snare = "minecraft:sand";
		String hat = "minecraft:glass";
		Map<String, String[]> cases = new java.util.LinkedHashMap<>();
		cases.put("no harp for the centre", new String[] {bassDrum, bassDrum, bassDrum, bassDrum,
			bassDrum, bassDrum, bassDrum});
		cases.put("a third snare", new String[] {snare, snare, snare, bassDrum, bassDrum, bassDrum,
			bassDrum});
		cases.put("both harps spent on the relays", new String[] {harp, harp, hat, hat, hat, hat,
			hat});
		for (Map.Entry<String, String[]> one : cases.entrySet()) {
			String[] instruments = one.getValue();
			List<SongBuilder.EventNote> notes = new ArrayList<>();
			for (int event = 0; event < 12; event++) {
				for (int index = 0; index < instruments.length; index++) {
					notes.add(new SongBuilder.EventNote(event + 1, 1 + index % 3, index,
						index % 25, instruments[index]));
				}
			}
			SongBuilder.PastePlan plan = build(notes);
			Map<BlockPos, BlockState> world = new HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parse(parts[4]));
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
			NoteMachineReader.Reading reading = NoteMachineReader.read("Sevens",
				new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
				position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
			int sounded = 0;
			for (var layer : reading.project().layers()) {
				sounded += layer.notes().size();
			}
			System.out.println("SEVENS " + one.getKey() + ": heads="
				+ plan.padding().getOrDefault("busHandover", 0)
				+ " unreached=" + reading.unreachedNotes() + " sounded=" + sounded);
			assertEquals(0, reading.unreachedNotes(),
				one.getKey() + ": note blocks the signal never got to");
			assertEquals(7 * 12, sounded, one.getKey() + ": notes read back");
		}
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (Exception broken) {
			throw new IllegalStateException(blockState, broken);
		}
	}
}
