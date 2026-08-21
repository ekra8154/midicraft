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
 * Draws the corkscrew (asc room one) the paster currently builds, so a change to its shape can be
 * read in slices rather than inferred from counters.
 *
 * <p>Sweeps the song library for v2 builds where {@code cutCorkscrewsAtTheWall} fires -- the
 * synthetic limit songs never produce one -- then renders the first modules found in the same view
 * an in-game {@code /fastnoteblocks asciidiagram} gives, which is the form the target designs arrive in, so
 * current and intended can be diffed cell for cell.</p>
 */
@Tag("sweep")
class CorkscrewShapeProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void drawsTheCorkscrewAsBuilt() throws Exception {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		boolean marks = SongBuilder.MARK_SHAPES;
		SongBuilder.NAME_EVERY_CELL = true;
		SongBuilder.MARK_SHAPES = true;
		try {
			int drawn = 0;
			java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "fast-noteblocks",
				"songs");
			List<java.nio.file.Path> files;
			try (java.util.stream.Stream<java.nio.file.Path> listing =
					java.nio.file.Files.list(songs)) {
				files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
			}
			com.google.gson.Gson gson = new com.google.gson.Gson();
			for (java.nio.file.Path file : files) {
				if (drawn >= 2) {
					break;
				}
				ComposerProject raw;
				try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
					raw = gson.fromJson(reader, ComposerProject.class);
				}
				ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				List<SongBuilder.EventNote> notes =
					SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true));
				if (notes.isEmpty()) {
					continue;
				}
				for (int[] size : new int[][] {{20, 5}, {28, 5}, {16, 3}, {24, 4}, {12, 2},
						{16, 2}, {20, 2}, {24, 2}, {32, 5}, {36, 4}, {44, 3}, {25, 8}}) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
							new SongBuilder.BuildLimits(4, size[0], size[1]));
					} catch (RuntimeException refused) {
						continue;
					}
					int fed = plan.padding().getOrDefault("cutHeadFeedsTheClimb", 0);
					if (fed > 0) {
						System.out.println("SCREW counters "
							+ file.getFileName().toString().replace(".json", "") + " " + size[0]
							+ "x" + size[1] + " fed=" + fed
							+ " flanked=" + plan.padding().getOrDefault("cutClimbsOnFlankedRungs", 0)
							+ " screw=" + plan.padding().getOrDefault("cutCorkscrewsAtTheWall", 0));
					}
					if (plan.padding().getOrDefault("cutCorkscrewsAtTheWall", 0) == 0) {
						continue;
					}
					Map<BlockPos, BlockState> world = new HashMap<>();
					for (String command : plan.commands()) {
						String[] parts = command.split(" ");
						world.put(new BlockPos(Integer.parseInt(parts[1]),
							Integer.parseInt(parts[2]), Integer.parseInt(parts[3])),
							parse(parts[4]));
					}
					// The corkscrew's own glass is laid inside the cut-head module, so its cells are
					// named cutHead*; the flanked-rung shape lays one pane, the corkscrew a pillar
					// of them two apart.
					BlockPos found = null;
					for (Map.Entry<BlockPos, BlockState> cell : world.entrySet()) {
						if (!cell.getValue().is(net.minecraft.world.level.block.Blocks.GLASS)) {
							continue;
						}
						BlockPos at = cell.getKey();
						String by = plan.laidBy().get(at);
						if (by != null && by.startsWith("cutHead")
								&& (glassAt(world, at.above().east())
									|| glassAt(world, at.above().west()))) {
							found = at;
							break;
						}
					}
					String name = file.getFileName().toString().replace(".json", "");
					System.out.println("SCREW " + name + " " + size[0] + "x" + size[1]
						+ " corkscrews=" + plan.padding().get("cutCorkscrewsAtTheWall")
						+ " bare=" + plan.padding().getOrDefault("corkscrewRunsBareForWireBehind", 0)
						+ " capped=" + plan.padding().getOrDefault("corkscrewCapsTheTailWithAHarp", 0)
						+ " severed=" + plan.padding().getOrDefault("corkscrewSeveredTheDiagonal", 0));
					if (found == null) {
						System.out.println("SCREW    no jogged glass pillar found");
						continue;
					}
					System.out.println("SCREW    first pane at " + found.getX() + " " + found.getY()
						+ " " + found.getZ() + "  laidBy=" + plan.laidBy().get(found));
					System.out.println(AsciiDiagram.render(
						position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
						found.offset(-11, -6, -2), found.offset(2, 4, 2),
						AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
					drawn++;
					break;
				}
			}
			System.out.println("SCREW drew " + drawn);
		} finally {
			SongBuilder.NAME_EVERY_CELL = names;
			SongBuilder.MARK_SHAPES = marks;
		}
	}

	private static boolean glassAt(Map<BlockPos, BlockState> world, BlockPos at) {
		BlockState state = world.get(at);
		return state != null && state.is(net.minecraft.world.level.block.Blocks.GLASS);
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

