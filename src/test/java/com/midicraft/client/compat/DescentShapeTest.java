package com.midicraft.client.compat;

import java.util.HashMap;
import java.util.Map;
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
 * What an ordinary descent actually gets built as, drawn the way it was drawn in game.
 *
 * <p>Small enough to read: two chords over a seeded descent, so the only staircase in the box is
 * the one in question. Rendered with the four-cell descent on and off at the same place, since
 * unlike a breach a staircase does not move when the shape changes -- the seed pins where it
 * stands.</p>
 */
@Tag("sweep")
class DescentShapeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.UNIVERSAL_FOUR_DESCENT = true;
	}

	@Test
	void drawsTheDescentBothWays() {
		for (int four = 1; four >= 0; four--) {
			SongBuilder.UNIVERSAL_FOUR_DESCENT = four == 1;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse("8@1 8@1 8@1", DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 16, 2),
				new SongBuilder.WalkStart(2, 1, -1, false));
			System.out.println("SHAPE ---- UNIVERSAL_FOUR_DESCENT=" + (four == 1)
				+ " blocks=" + plan.commands().size() + " turns=" + plan.turns() + " ----");
			for (String fault : plan.faults()) {
				System.out.println("SHAPE fault: " + fault);
			}
			if (plan.turns().isEmpty()) {
				System.out.println("SHAPE no turn in this build");
				continue;
			}
			// The staircase itself, which is what turnedAt records.
			BlockPos at = plan.turns().get(0);
			Map<BlockPos, BlockState> world = new HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parse(parts[4]));
			}
			System.out.println(AsciiDiagram.render(
				position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
				at.offset(-4, -6, -2), at.offset(4, 2, 2),
				AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
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
