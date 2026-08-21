package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Finds the wall descents (desc1) in a build and says where each one's repeater stands, so one
 * can be walked to in game -- including the empty cell where the mockup's stand flank would
 * hang, which is the note the shipped shape leaves out for want of a driver.
 */
@Tag("sweep")
class WallDescentProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void listsTheWallDescents() throws Exception {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		SongBuilder.NAME_EVERY_CELL = true;
		try {
			String name = System.getProperty("wall.song", "guardian25");
			int width = Integer.parseInt(System.getProperty("wall.width", "20"));
			int floors = Integer.parseInt(System.getProperty("wall.floors", "5"));
			java.nio.file.Path file = java.nio.file.Path.of("run", "config", "fast-noteblocks",
				"songs", name + ".json");
			ComposerProject raw;
			try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
				raw = new com.google.gson.Gson().fromJson(reader, ComposerProject.class);
			}
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
				raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true));
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, width, floors));
			Map<BlockPos, BlockState> world = new HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parse(parts[4]));
			}
			System.out.println("WALL " + name + " " + width + "x" + floors
				+ " built=" + plan.padding().getOrDefault("builtWallDescent", 0)
				+ " (paste origin 0 64 0; add your paste origin to these)");
			for (Map.Entry<BlockPos, String> cell : plan.laidBy().entrySet()) {
				if (cell.getValue() == null || !cell.getValue().startsWith("wallDescent")) {
					continue;
				}
				BlockState state = world.get(cell.getKey());
				if (state == null || !state.is(Blocks.REPEATER)) {
					continue;
				}
				BlockPos rep = cell.getKey();
				System.out.println("WALL   repeater " + rep.getX() + " " + rep.getY() + " "
					+ rep.getZ() + "  " + cell.getValue());
			}
		} finally {
			SongBuilder.NAME_EVERY_CELL = names;
		}
	}

	private static BlockState parse(String block) {
		try {
			return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
				net.minecraft.core.registries.BuiltInRegistries.BLOCK, block, false).blockState();
		} catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
