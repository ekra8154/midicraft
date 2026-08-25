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
 * The comb: a serpentine whose trunk-side flat turns run long, read back for dead wire.
 *
 * <p>The first build of the interleaved half-tick's actual shape, one machine of it. The
 * interleaved paste runs two mirrored serpentines whose fingers interdigitate, and what makes room
 * for the other machine's finger is a flat turn stretched down the trunk -- the perpendicular run
 * at nine or ten cells instead of three. The walk has never laid one longer than its own lane
 * spacing, so before any second machine exists this asks the honest question of the stretched turn
 * alone: does the song still play.</p>
 *
 * <p>Read back through {@link NoteMachineReader} rather than trusted to {@code verify}, because
 * the plan's own numbers read nought over a severed wire -- a build that has stopped playing
 * reports no wrong notes at all. The starter button is swapped for a lever first: buttons are
 * recognised through a block tag, block tags are not loaded by a bare bootstrap, and a machine
 * whose way in is invisible reads as entirely unreached.</p>
 */
class StretchedFlatTurnTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> notes() throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile("illit-do-the-dance"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
	}

	@Test
	void theCombPlaysWholeOnLongTrunkLinks() throws Exception {
		LaneRoute base = LaneRoute.serpentine(1, 0, 1);
		// Odd legs end at the trunk wall; their turns run nine cells of perpendicular so another
		// machine's finger could pass. Even legs keep the walk's own spacing at the finger tips.
		LaneRoute comb = new LaneRoute() {
			@Override
			public int floorOf(int leg) {
				return base.floorOf(leg);
			}

			@Override
			public int climbOf(int leg) {
				return base.climbOf(leg);
			}

			@Override
			public int linkOf(int leg) {
				return leg % 2 == 1 ? 9 : 0;
			}
		};
		SongBuilder.PastePlan plan = SongBuilder.createRoutedPastePlan(new BlockPos(0, 64, 0),
			Direction.EAST, notes(), 24, 1, SongBuilder.WalkStart.HEAD, comb);

		assertTrue(plan.padding().getOrDefault("turnFlatStep9", 0) > 0,
			"no stretched turn was laid, so this tested nothing: " + plan.padding().keySet()
				.stream().filter(key -> key.startsWith("turnFlatStep")).toList());
		assertEquals(0, plan.wrongNotes(), "wrong notes: " + plan.faults());
		assertEquals(0, plan.missingNotes(), "missing notes: " + plan.faults());

		Map<BlockPos, BlockState> world = new HashMap<>();
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			BlockPos at = new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
				Integer.parseInt(token[3]));
			String block = token[4].substring(0, token[4].length() - " replace".length());
			// A lever where the real thing has a button: buttons are found through a block tag and
			// a bare bootstrap loads none, so the button is not a way in here and the whole machine
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
		NoteMachineReader.Reading reading = NoteMachineReader.read("Comb",
			new BlockPos(min[0] - 1, min[1] - 1, min[2] - 1),
			new BlockPos(max[0] + 1, max[1] + 1, max[2] + 1),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
		assertEquals(0, reading.unreachedNotes(),
			"the stretched turns severed the wire: " + reading.report());
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
