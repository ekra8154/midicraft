package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ten columns of bare wire ekran read off illit at 32 wide over two floors.
 *
 * <p>A pad is wire a lane pays for out of the same fifteen the chords spend, so ten of them is ten
 * columns of music given up. The question is what asked for them, and the trace says that in one
 * line -- so this prints the turn decisions for the lane holding them beside the blocks themselves,
 * drawn over the box ekran quoted.</p>
 */
@Tag("sweep")
class IllitTwoFloorPadTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> illit() throws Exception {
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
		try (Reader reader = Files.newBufferedReader(songs.resolve("illit-do-the-dance.json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	@Test
	void showsWhatAskedForTheTenColumns() throws Exception {
		SongBuilder.TRACE_TURNS = true;
		SongBuilder.TRACE = true;
		SongBuilder.PastePlan plan;
		try {
			plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), illit(),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 32, 2));
		} finally {
			SongBuilder.TRACE_TURNS = false;
			SongBuilder.TRACE = false;
		}
		System.out.println("PLAN nearWall=" + plan.nearWall() + " farWall=" + plan.farWall()
			+ " spanX=" + plan.spanX() + " spanZ=" + plan.spanZ() + " height=" + plan.height()
			+ " blocks=" + plan.commands().size());
		System.out.println("PLAN breaches " + plan.breaches());
		plan.faults().forEach(fault -> System.out.println("PLAN fault " + fault));
		System.out.println("PLAN padCells=" + plan.padCells());
		plan.padding().entrySet().stream()
			.filter(entry -> entry.getKey().startsWith("pad"))
			.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
			.limit(12)
			.forEach(entry -> System.out.println("   " + entry.getKey() + " " + entry.getValue()));

		// The box ekran quoted, from the same origin, so the two diagrams are of the same blocks.
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3])), parse(word[4]));
		}
		System.out.println(AsciiDiagram.render(
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()),
			new BlockPos(20, 64, 56), new BlockPos(36, 69, 59),
			AsciiDiagram.View.TOP, AsciiDiagram.Shape.CODE));
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
