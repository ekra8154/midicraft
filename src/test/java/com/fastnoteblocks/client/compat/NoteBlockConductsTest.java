package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Whether a note block passes redstone power on, asked of Minecraft rather than of our own reader.
 *
 * <p>{@link NoteMachineReader} refuses to spread a dust's power into a note block -- one clause, no
 * comment on it -- and that refusal is why a run's opening column gives its centre away to a stone.
 * There is no difference between the two: a note block can be powered just like a stone, and the
 * only difference is that it cannot have something on top, which does not happen here.
 * The game's own block properties are the only thing that can settle it, and they are right here in
 * the test classpath.</p>
 */
@Tag("sweep")
class NoteBlockConductsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void aNoteBlockConductsLikeStone() {
		BlockGetter empty = emptyWorld();
		BlockPos at = BlockPos.ZERO;
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState note = Blocks.NOTE_BLOCK.defaultBlockState();
		for (BlockState state : new BlockState[] {stone, note}) {
			System.out.println("CONDUCTS " + state.getBlock().getName().getString()
				+ " redstoneConductor=" + state.isRedstoneConductor(empty, at)
				+ " signalSource=" + state.isSignalSource()
				+ " occludes=" + state.canOcclude()
				+ " solidRender=" + state.isSolidRender()
				+ " signalNorth=" + state.getSignal(empty, at, Direction.NORTH)
				+ " directNorth=" + state.getDirectSignal(empty, at, Direction.NORTH));
		}
		assertEquals(stone.isRedstoneConductor(empty, at), note.isRedstoneConductor(empty, at),
			"a note block carries redstone power exactly as a stone does, which is what decides"
				+ " whether a run may keep its centre");
	}

	/** Air everywhere, which is all these properties need to be asked. */
	private static BlockGetter emptyWorld() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		return new BlockGetter() {
			@Override
			public BlockState getBlockState(BlockPos position) {
				return blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
			}

			@Override
			public net.minecraft.world.level.material.FluidState getFluidState(BlockPos position) {
				return getBlockState(position).getFluidState();
			}

			@Override
			public BlockEntity getBlockEntity(BlockPos position) {
				return null;
			}

			@Override
			public int getHeight() {
				return 384;
			}

			@Override
			public int getMinY() {
				return -64;
			}
		};
	}
}
