package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The first interleaved half-tick build: two mirrored combs in one region, read back whole.
 *
 * <p>Three questions, in the order they can kill the layout. Do two machines come out of one plan
 * at all -- two starters, both wired. Does either machine's wire die -- read back through {@link
 * NoteMachineReader}, since the plan's own numbers read nought over a severed wire. And the open
 * question the whole layout exists to answer: with foreign lanes a lane spacing apart, does the
 * layout check find notes sounding on the other machine's ticks. The last one is a measurement,
 * not a hope -- the assertion pins whatever the honest number turns out to be, and the number
 * being nought is what makes three-apart shippable.</p>
 */
class InterleavedHalfTickTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> notes(String song) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(song))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.gameTickEventNotes(project, true);
		}
	}

	@Test
	void twoMachinesInterleaveAndBothPlay() throws Exception {
		List<SongBuilder.EventNote> notes = notes("neverending-night-2-lanes");
		long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
		assertTrue(evens > 0 && evens < notes.size(),
			"the two-lane song must live on both parities: " + evens + " of " + notes.size());
		SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
			new BlockPos(0, 64, 0), Direction.EAST, notes,
			new SongBuilder.BuildLimits(16, 24, 1), SongBuilder.WalkStart.HEAD);

		List<BlockPos> buttons = plan.commands().stream()
			.filter(command -> command.contains("minecraft:oak_button"))
			.map(command -> {
				String[] token = command.split(" ", 5);
				return new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
					Integer.parseInt(token[3]));
			})
			.toList();
		assertEquals(2, buttons.size(), "each machine brings its own way in");
		// And the two ways in stand together at one corner: a lane pitch apart in depth, a
		// couple of columns apart, level -- close enough for one contraption to drive both.
		BlockPos gap = buttons.get(1).subtract(buttons.get(0));
		assertTrue(Math.abs(gap.getX()) <= 3 && Math.abs(gap.getZ()) == 3 && gap.getY() == 0,
			"the two starts should stand together: " + buttons);
		assertEquals(0, plan.wrongNotes(), "notes sounding on a foreign tick: " + plan.faults());
		assertEquals(0, plan.missingNotes(), "notes with nowhere to hang: " + plan.faults());

		Map<BlockPos, BlockState> world = new HashMap<>();
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			BlockPos at = new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
				Integer.parseInt(token[3]));
			String block = token[4].substring(0, token[4].length() - " replace".length());
			// Levers where the real thing has buttons: buttons are found through a block tag and a
			// bare bootstrap loads none, so a button is not a way in here and its whole machine
			// would read unreached.
			if (block.startsWith("minecraft:oak_button")) {
				block = "minecraft:lever[face=floor,facing=east,powered=true]";
			}
			world.put(at, parse(block));
			min[0] = Math.min(min[0], at.getX());
			min[1] = Math.min(min[1], at.getY());
			min[2] = Math.min(min[2], at.getZ());
			max[0] = Math.max(max[0], at.getX());
			max[1] = Math.max(max[1], at.getY());
			max[2] = Math.max(max[2], at.getZ());
		}
		NoteMachineReader.Reading reading = NoteMachineReader.read("Interleaved",
			new BlockPos(min[0] - 1, min[1] - 1, min[2] - 1),
			new BlockPos(max[0] + 1, max[1] + 1, max[2] + 1),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
		assertEquals(0, reading.unreachedNotes(),
			"a machine's wire died: " + reading.report());
		// Nought unreached out of nought found is no reading at all; the song is thousands of notes
		// and the reader must have seen them to have cleared them.
		int heard = reading.project().layers().stream()
			.mapToInt(layer -> layer.notes().size()).sum();
		assertTrue(heard > 500, "the reader only found " + heard + " notes");
		// And the comb was really stretched -- long trunk turns were laid, not defaulted away.
		assertTrue(plan.padding().keySet().stream()
				.anyMatch(key -> key.equals("turnFlatStep9")),
			"no stretched trunk turn in the build: " + plan.padding().keySet().stream()
				.filter(key -> key.startsWith("turnFlatStep")).toList());
		// The paste stays inside the width it promised, breaches aside -- and this build has none.
		assertTrue(plan.spanX() <= 24, "span " + plan.spanX() + " for a 24-wide paste, breaches="
			+ plan.breaches());
	}

	@Test
	void aDoubledSongInterleavesForReal() throws Exception {
		// A one-lane song at double speed: times halved, exact because every time is even. The
		// synthetic two-lane copy the census sweeps, given the full wire readback once.
		List<SongBuilder.EventNote> notes = notes("illit-do-the-dance").stream()
			.map(note -> new SongBuilder.EventNote(note.time() / 2, note.trackNumber(),
				note.order(), note.pitch(), note.instrumentBlock()))
			.toList();
		long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
		assertTrue(evens > 0 && evens < notes.size(),
			"the doubled song must live on both parities: " + evens + " of " + notes.size());
		SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
			new BlockPos(0, 64, 0), Direction.EAST, notes,
			new SongBuilder.BuildLimits(16, 24, 1), SongBuilder.WalkStart.HEAD);
		assertEquals(2, plan.commands().stream()
			.filter(command -> command.contains("minecraft:oak_button")).count(),
			"two machines, two ways in");
		assertEquals(0, plan.wrongNotes(), "wrong notes: " + plan.faults());
		assertEquals(0, plan.missingNotes(), "missing notes: " + plan.faults());
	}

	@Test
	void aOneParitySongGetsOneMachineOnItsOwnClock() throws Exception {
		// A song written on repeater ticks lives entirely on one parity of the game tick, so there
		// is nothing for a second machine to play and no trunk should be stretched waiting for it.
		List<SongBuilder.EventNote> notes = notes("illit-do-the-dance");
		long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
		assertTrue(evens == 0 || evens == notes.size(),
			"a repeater-tick song must live on one parity: " + evens + " of " + notes.size());
		SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
			new BlockPos(0, 64, 0), Direction.EAST, notes,
			new SongBuilder.BuildLimits(16, 24, 1), SongBuilder.WalkStart.HEAD);
		assertEquals(1, plan.commands().stream()
			.filter(command -> command.contains("minecraft:oak_button")).count(),
			"one machine, one way in");
		assertEquals(0, plan.wrongNotes(), "wrong notes: " + plan.faults());
		assertEquals(0, plan.missingNotes(), "missing notes: " + plan.faults());
		assertTrue(plan.padding().keySet().stream()
				.noneMatch(key -> key.equals("turnFlatStep9")),
			"a solo build stretched its trunk for nobody: " + plan.padding().keySet().stream()
				.filter(key -> key.startsWith("turnFlatStep")).toList());
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
