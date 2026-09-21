package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * That the Build Pasting blocks change what a build is made of and nothing about how it plays.
 *
 * <p>The same bar as {@link LightShowTest}: the same cells in the same order, only the three kinds
 * of cell the settings name changed, never the block under a note, and the same song read back off
 * the blocks.</p>
 */
class LaneMaterialsTest {
	private static final String ONE = "minecraft:oak_planks";
	private static final String TWO = "minecraft:bricks";
	private static final String CLEAR = "minecraft:white_stained_glass";
	private static final String SUPPORT = "minecraft:smooth_stone_slab[type=top]";

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void defaultsAgain() {
		SongBuilder.LANE_ONE_BLOCK = "minecraft:stone";
		SongBuilder.LANE_TWO_BLOCK = "minecraft:stone";
		SongBuilder.TRANSPARENT_BLOCK = "minecraft:glass";
		SongBuilder.SUPPORT_BLOCK = "minecraft:stone_slab[type=top]";
		SongBuilder.LIGHT_SHOW = false;
	}

	@Test
	void customBlocksPlayTheSameSong() throws Exception {
		check(false);
	}

	@Test
	void customBlocksUnderALightShowKeepTheLamps() throws Exception {
		check(true);
	}

	/**
	 * That the paste screen's forecast judges the blocks that will be placed, not the stone the walk
	 * planned in. The settings refuse both of these, so they are written straight to the builder:
	 * this is the backstop for a block the settings' checks did not foresee.
	 */
	@Test
	void aBlockThatBreaksTheMachineShowsUpAsSilentNotes() throws Exception {
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		ComposerProject song = GameSettings.project(BreachView.songFile("a-dark-zone-2-lanes"));
		List<SongBuilder.EventNote> notes =
			SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true);
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(16, 24, 3);
		int clean = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits)
			.deadNotes();

		SongBuilder.TRANSPARENT_BLOCK = "minecraft:stone";
		int stoneClimbs = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits)
			.deadNotes();
		SongBuilder.TRANSPARENT_BLOCK = "minecraft:glass";
		SongBuilder.LANE_TWO_BLOCK = "minecraft:air";
		int airLane = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits)
			.deadNotes();
		System.out.println("MATERIALS silent notes: clean " + clean + ", stone climbs "
			+ stoneClimbs + ", air lane two " + airLane);
		assertTrue(stoneClimbs > clean, "stone climbs cut the wire and the forecast did not notice");
		assertTrue(airLane > clean, "an air lane holds nothing up and the forecast did not notice");
	}

	@Test
	void eachRoleRefusesTheBlocksThatWouldBreakIt() {
		assertNull(LaneMaterials.problem(LaneMaterials.Role.LANE, "minecraft:stone"));
		assertNull(LaneMaterials.problem(LaneMaterials.Role.TRANSPARENT, "minecraft:glass"));
		assertNull(LaneMaterials.problem(LaneMaterials.Role.SUPPORT, "minecraft:stone_slab"));
		assertNull(LaneMaterials.problem(LaneMaterials.Role.LANE, ONE));
		assertNull(LaneMaterials.problem(LaneMaterials.Role.TRANSPARENT, CLEAR));
		assertNull(LaneMaterials.problem(LaneMaterials.Role.SUPPORT, "minecraft:smooth_stone_slab"));

		assertNotNull(LaneMaterials.problem(LaneMaterials.Role.LANE, "minecraft:glass"),
			"glass does not conduct, so a lane of it cannot relay");
		assertNotNull(LaneMaterials.problem(LaneMaterials.Role.TRANSPARENT, "minecraft:stone"),
			"stone conducts, which cuts the climbing wire");
		assertNotNull(LaneMaterials.problem(LaneMaterials.Role.LANE, "minecraft:sand"));
		assertNotNull(LaneMaterials.problem(LaneMaterials.Role.SUPPORT, "minecraft:air"));
		assertNotNull(LaneMaterials.problem(LaneMaterials.Role.LANE, "minecraft:not_a_block"));

		assertEquals("minecraft:stone", LaneMaterials.normalise("minecraft: stone"));
		assertEquals("minecraft:stone", LaneMaterials.normalise(" Stone "));
		assertNull(LaneMaterials.normalise(""));
		assertEquals("minecraft:stone_slab[type=top]",
			LaneMaterials.placed(LaneMaterials.Role.SUPPORT, "minecraft:stone_slab"));
		assertEquals("minecraft:stone",
			LaneMaterials.accepted(LaneMaterials.Role.LANE, "minecraft:glass"),
			"a hand-edited lane of glass goes back to stone");
		assertEquals("minecraft:bricks", LaneMaterials.accepted(LaneMaterials.Role.LANE, "bricks"));
	}

	private static void check(boolean lightShow) throws Exception {
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		ComposerProject song = GameSettings.project(BreachView.songFile("a-dark-zone-2-lanes"));
		List<SongBuilder.EventNote> notes =
			SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true);
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(16, 24, 3);

		SongBuilder.LIGHT_SHOW = lightShow;
		SongBuilder.PastePlan plainPlan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits);
		SongBuilder.LANE_ONE_BLOCK = ONE;
		SongBuilder.LANE_TWO_BLOCK = TWO;
		SongBuilder.TRANSPARENT_BLOCK = CLEAR;
		SongBuilder.SUPPORT_BLOCK = SUPPORT;
		SongBuilder.PastePlan customPlan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits);

		Map<BlockPos, String> plain = laid(plainPlan);
		Map<BlockPos, String> custom = laid(customPlan);
		assertEquals(new ArrayList<>(plain.keySet()), new ArrayList<>(custom.keySet()),
			"custom blocks put cells in different places, or in a different order");

		Map<String, Integer> census = new TreeMap<>();
		List<String> wrong = new ArrayList<>();
		for (Map.Entry<BlockPos, String> cell : plain.entrySet()) {
			String was = cell.getValue();
			String now = custom.get(cell.getKey());
			if (was.equals(now)) {
				continue;
			}
			String where = cell.getKey().getX() + " " + cell.getKey().getY() + " "
				+ cell.getKey().getZ();
			if (plain.getOrDefault(cell.getKey().above(), "").startsWith("minecraft:note_block")) {
				wrong.add(where + "  " + was + " -> " + now + " under a note, which retunes it");
				continue;
			}
			boolean expected = switch (was) {
				case "minecraft:stone" -> ONE.equals(now) || TWO.equals(now);
				case "minecraft:glass" -> CLEAR.equals(now);
				case "minecraft:stone_slab[type=top]" -> SUPPORT.equals(now);
				default -> false;
			};
			if (!expected) {
				wrong.add(where + "  " + was + " -> " + now);
				continue;
			}
			census.merge(now, 1, Integer::sum);
		}
		System.out.println("MATERIALS lightShow=" + lightShow + " " + census + " of " + custom.size());
		wrong.stream().limit(10).forEach(line -> System.out.println("  WRONG " + line));
		assertTrue(wrong.isEmpty(), wrong.size() + " cells changed that should not have");
		assertTrue(census.getOrDefault(ONE, 0) > 50, "lane one hardly appears: " + census);
		assertTrue(census.getOrDefault(TWO, 0) > 50, "lane two hardly appears: " + census);
		assertTrue(census.getOrDefault(CLEAR, 0) > 0, "no climb took the transparent block");
		assertTrue(census.getOrDefault(SUPPORT, 0) > 0, "no support took the support block");
		if (lightShow) {
			long lamps = custom.values().stream().filter("minecraft:redstone_lamp"::equals).count();
			assertEquals(plain.values().stream().filter("minecraft:redstone_lamp"::equals).count(),
				lamps, "custom blocks took lamps away from the light show");
			assertTrue(lamps > 100, "the light show never lit");
		}
		assertEquals(plainPlan.faults(), customPlan.faults(), "custom blocks report different faults");

		NoteMachineReader.Reading heard = read(plain);
		NoteMachineReader.Reading seen = read(custom);
		assertEquals(heard.noteBlocks(), seen.noteBlocks());
		assertEquals(heard.unreachedNotes(), seen.unreachedNotes(),
			"custom blocks change which notes the signal reaches");
		assertEquals(heard.redstoneTicks(), seen.redstoneTicks());
		assertEquals(heard.project().layers(), seen.project().layers(),
			"the song read back off custom blocks is not the song read back off stone");
	}

	private static Map<BlockPos, String> laid(SongBuilder.PastePlan plan) {
		Map<BlockPos, String> blocks = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			blocks.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parts[4]);
		}
		return blocks;
	}

	private static NoteMachineReader.Reading read(Map<BlockPos, String> laid) throws Exception {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (Map.Entry<BlockPos, String> cell : laid.entrySet()) {
			world.put(cell.getKey(), BlockStateParser
				.parseForBlock(BuiltInRegistries.BLOCK, cell.getValue(), false).blockState());
		}
		BlockPos low = new BlockPos(
			world.keySet().stream().mapToInt(BlockPos::getX).min().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0));
		BlockPos high = new BlockPos(
			world.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0));
		return NoteMachineReader.read("lane materials", low, high,
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
	}
}
