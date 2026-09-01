package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Draws cross-descent heads and the ground under their lowered flanks, hunting the hole punch:
 * in-game reading found the far half's note missing beneath the low back flank, a cell the harp
 * above it leaves perfectly legal -- gap of one, harp on top.
 */
@Tag("sweep")
class CrossDescentHoleProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void drawsTheCrossDescents() throws Exception {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		boolean marks = SongBuilder.MARK_SHAPES;
		SongBuilder.NAME_EVERY_CELL = true;
		SongBuilder.MARK_SHAPES = true;
		try {
			String name = System.getProperty("hole.song", "guardian25");
			int width = Integer.parseInt(System.getProperty("hole.width", "20"));
			int floors = Integer.parseInt(System.getProperty("hole.floors", "5"));
			java.nio.file.Path file = java.nio.file.Path.of("run", "config", "midicraft",
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
			System.out.println("HOLE " + name + " " + width + "x" + floors + " crossDescents="
				+ plan.padding().getOrDefault("planCrossDescent", 0));
			int drawn = 0;
			for (Map.Entry<BlockPos, String> cell : plan.laidBy().entrySet()) {
				if (drawn >= 2 || cell.getValue() == null
						|| !cell.getValue().startsWith("crossHead")) {
					continue;
				}
				BlockState state = world.get(cell.getKey());
				if (state == null || !state.is(Blocks.REPEATER)) {
					continue;
				}
				BlockPos repeater = cell.getKey();
				System.out.println("HOLE crossHead repeater at " + repeater.getX() + " "
					+ repeater.getY() + " " + repeater.getZ() + "  laidBy=" + cell.getValue());
				System.out.println(AsciiDiagram.render(
					position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
					repeater.offset(-4, -6, -2), repeater.offset(4, 2, 2),
					AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
				drawn++;
			}
			System.out.println("HOLE drew " + drawn);
		} finally {
			SongBuilder.NAME_EVERY_CELL = names;
			SongBuilder.MARK_SHAPES = marks;
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
