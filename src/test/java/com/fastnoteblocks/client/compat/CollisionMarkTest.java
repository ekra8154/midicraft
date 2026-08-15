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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The marking pass, on the smallest build that collides, drawn.
 *
 * <p>{@code DEBUG_PASTE} exists so a broken build can be walked round in game rather than
 * guessed at from a message. The same build can be walked round here: what the diagram shows is
 * whose column the sea lantern is standing in, which is the thing the exception never said.</p>
 */
@Tag("sweep")
class CollisionMarkTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void stopMarking() {
		SongBuilder.DEBUG_PASTE = false;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	/**
	 * Three refusals from the library, on the shipping flags.
	 *
	 * <p>Adventure of a Lifetime is the smallest that refuses, and refuses twice at different widths
	 * -- so if the two lanterns land in the same relation to the staircase, the shape is the fault
	 * and not the song. Kick Back is here because ekran has that one built.</p>
	 */
	@Test
	void marksTheCellsAndDrawsTheFirst() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		Object[][] cases = {
			{"adventure-of-a-lifetime", 12, 2},
			{"adventure-of-a-lifetime", 16, 4},
			{"chainsaw-man-op-kenshi-yonezu-kick-back", 0, 0},
		};
		for (Object[] settings : cases) {
			String name = (String) settings[0];
			int width = (Integer) settings[1];
			int floors = (Integer) settings[2];
			if (width == 0) {
				int[] found = firstRefusal(name);
				if (found == null) {
					System.out.println("MARK " + name + " no longer refuses anywhere");
					continue;
				}
				width = found[0];
				floors = found[1];
			}
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				load(name), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, width, floors));
			System.out.println("MARK ---- " + name + " ----");
			System.out.println("MARK w" + width + " f" + floors + ": " + plan.collisions().size()
				+ " collisions in " + plan.commands().size() + " blocks");
			plan.collisions().forEach((at, what) -> System.out.println("MARK   "
				+ at.getX() + " " + at.getY() + " " + at.getZ() + "  " + what));
			if (plan.collisions().isEmpty()) {
				continue;
			}
			Map<BlockPos, BlockState> world = new HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parse(parts[4]));
			}
			BlockPos first = plan.collisions().keySet().iterator().next();
			// Wide enough to hold the whole staircase and the chords either side of it, and four
			// levels down because a descent falls that far.
			System.out.println(AsciiDiagram.render(
				position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
				first.offset(-8, -5, -4), first.offset(8, 3, 4),
				AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
		}
	}

	/** The first width and floor count this song refuses at, with the marking pass off. */
	private static int[] firstRefusal(String name) throws Exception {
		SongBuilder.DEBUG_PASTE = false;
		try {
			List<SongBuilder.EventNote> notes = load(name);
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					try {
						SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						return new int[] {width, floors};
					}
				}
			}
			return null;
		} finally {
			SongBuilder.DEBUG_PASTE = true;
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
