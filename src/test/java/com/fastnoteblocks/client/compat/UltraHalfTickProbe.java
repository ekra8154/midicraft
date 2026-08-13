package com.fastnoteblocks.client.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Two folded snakes, one per half of the tick: does it build, does it conduct, how big is it.
 *
 * <p>The first questions only. Whether the two stay near each other is the interesting one and is
 * not asked here -- nothing paces them yet, and measuring a drift nothing is trying to control
 * would only restate the arithmetic. What has to be true before that is worth asking: that two
 * corridors four blocks apart leave each other alone, that neither snake goes dead, and that the
 * pair is small enough to be worth the trouble against one straight lane of the same song.</p>
 */
@Tag("sweep")
class UltraHalfTickProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void buildsGuardianAsTwoSnakes() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== Ultra half-tick lane, Guardian ====");
		for (int[] size : new int[][] {{44, 3}, {32, 4}, {24, 3}}) {
			SongBuilder.PastePlan plan;
			try {
				plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
					SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE,
					new SongBuilder.BuildLimits(4, size[0], size[1]));
			} catch (RuntimeException refused) {
				System.out.println("  " + size[0] + "w x " + size[1] + "f  REFUSED: "
					+ refused.getMessage());
				continue;
			}
			Map<BlockPos, BlockState> world = placeInWorld(plan);
			NoteMachineReader.Reading reading = read(world);
			System.out.println("  " + size[0] + "w x " + size[1] + "f  blocks "
				+ plan.commands().size() + ", spanX " + plan.spanX() + ", spanZ " + plan.spanZ()
				+ ", height " + plan.height());
			System.out.println("      noteBlocks " + reading.noteBlocks()
				+ ", unreached " + reading.unreachedNotes()
				+ ", versions " + reading.versions() + " (2 means the snakes share nothing)"
				+ ", wrongNotes " + plan.wrongNotes()
				+ ", breaches " + plan.breaches().size());
			// The size that decides whether folding was worth it: a straight pair of the same song
			// is 7,220 long and a note block carries 48, so anything that fits inside earshot at
			// all has to come from folding.
			System.out.println("      footprint " + plan.spanX() + " x " + plan.spanZ() + " x "
				+ plan.height() + ", longest diagonal about "
				+ Math.round(Math.sqrt((double)plan.spanX() * plan.spanX()
					+ (double)plan.spanZ() * plan.spanZ())) + " blocks");
		}
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading read(Map<BlockPos, BlockState> world) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read("Ultra half-tick", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
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
