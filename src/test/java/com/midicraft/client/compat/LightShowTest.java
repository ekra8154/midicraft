package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * That a light show is the plain build with lamps in it, and plays exactly as the plain build does.
 *
 * <p>The light show is meant to be heard, which is the difference between it and the colour-coded
 * paste it shares a setting with. So the bar is higher than {@link DebugPasteMarkTest}'s: not only
 * the same cells in the same order, but the same song read back off the blocks, because a lamp that
 * conducted differently from the stone it replaced would be a wrong note nobody could see.</p>
 */
class LightShowTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void plainAgain() {
		SongBuilder.LIGHT_SHOW = false;
	}

	private static final String LAMP = "minecraft:redstone_lamp";

	@Test
	void anUltraLaneLightShowPlaysTheSameSong() throws Exception {
		// The song the colour-coded paste's own test marks, a hundred-odd cells of it rail.
		check("illit-do-the-dance", SongBuilder.PasteMode.ULTRA_COMPACT_LANE, 40, 3);
	}

	@Test
	void anInterleavedLightShowPlaysTheSameSong() throws Exception {
		check("a-dark-zone-2-lanes", SongBuilder.PasteMode.INTERLEAVED_HALF_TICK, 24, 3);
	}

	private static void check(String name, SongBuilder.PasteMode mode, int width, int floors)
			throws Exception {
		ComposerProject song = GameSettings.project(BreachView.songFile(name));
		List<SongBuilder.EventNote> notes =
			SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true);
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(16, width, floors);

		SongBuilder.PastePlan plainPlan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits);
		SongBuilder.LIGHT_SHOW = true;
		SongBuilder.PastePlan litPlan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits);
		SongBuilder.LIGHT_SHOW = false;

		Map<BlockPos, String> plain = laid(plainPlan);
		Map<BlockPos, String> lit = laid(litPlan);
		assertEquals(new ArrayList<>(plain.keySet()), new ArrayList<>(lit.keySet()),
			"a light show puts blocks in different places, or in a different order, from a plain build");

		Map<BlockPos, String> laidBy = litPlan.laidBy();
		Map<String, Integer> census = new TreeMap<>();
		List<String> wrong = new ArrayList<>();
		for (Map.Entry<BlockPos, String> cell : plain.entrySet()) {
			String was = cell.getValue();
			String now = lit.get(cell.getKey());
			if (was.equals(now)) {
				continue;
			}
			String where = cell.getKey().getX() + " " + cell.getKey().getY() + " "
				+ cell.getKey().getZ();
			if (!LAMP.equals(now) || !"minecraft:stone".equals(was)) {
				wrong.add(where + "  " + was + " -> " + now);
				continue;
			}
			if (plain.getOrDefault(cell.getKey().above(), "").startsWith("minecraft:note_block")) {
				wrong.add(where + "  a lamp under a note, which retunes it");
				continue;
			}
			String by = laidBy.getOrDefault(cell.getKey(), "?");
			if (by.startsWith("rail:") && !by.endsWith(SongBuilder.TOP_RAIL)) {
				wrong.add(where + "  a lamp on the floor rail (" + by + ")");
				continue;
			}
			census.merge(by.startsWith("rail:") ? "top rail" : by.replaceAll(" .*", ""), 1,
				Integer::sum);
		}
		System.out.println("LIGHTSHOW " + name + " " + width + "x" + floors + ": "
			+ census.values().stream().mapToInt(Integer::intValue).sum() + " lamps of "
			+ lit.size() + " blocks " + census);
		wrong.stream().limit(10).forEach(line -> System.out.println("  WRONG " + line));
		assertTrue(wrong.isEmpty(), wrong.size() + " cells changed that should not have");
		assertTrue(census.values().stream().mapToInt(Integer::intValue).sum() > 100,
			"hardly anything lit up, so the labels never landed: " + census);
		assertEquals(plainPlan.faults(), litPlan.faults(), "a light show reports different faults");

		NoteMachineReader.Reading heard = read(plain);
		NoteMachineReader.Reading seen = read(lit);
		assertEquals(heard.noteBlocks(), seen.noteBlocks());
		assertEquals(heard.unreachedNotes(), seen.unreachedNotes(),
			"lamps change which notes the signal reaches");
		assertEquals(heard.redstoneTicks(), seen.redstoneTicks());
		assertEquals(heard.project().layers(), seen.project().layers(),
			"the song read back off a light show is not the song read back off the plain build");
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
		return NoteMachineReader.read("light show", low, high,
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
	}
}
