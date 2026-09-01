package com.midicraft.client.compat;

import java.util.ArrayList;
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
 * Every handover in a build, and whether a repeater is drawing power out of it.
 *
 * <p>Asked of the blocks rather than of a counter. A census says a shape ran and says nothing about
 * whether it ran correctly, and drawing "whichever fault ranks first" answers a third question --
 * a build with no faults draws nothing at all. So this finds the shape by its signature: stone with
 * dust directly over it, which is what a handover is, and then asks what stands in front.</p>
 */
@Tag("sweep")
class HandoverCellProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void countsHandoversWithAndWithoutTheirRepeater() throws Exception {
		List<SongBuilder.EventNote> notes = BreachView.song("ultra-rails-harp-gap2");
		FaultView.Build built = FaultView.of("ultra-rails-harp-gap2", notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, 48, 1, 4, false);
		System.out.println();
		System.out.println("==== handovers, and what stands in front of them ====");
		System.out.println("   railFromHandover fired "
			+ built.plan().padding().getOrDefault("railFromHandover", 0) + " times");
		int handovers = 0;
		int withRepeater = 0;
		List<BlockPos> bare = new ArrayList<>();
		for (BlockPos at : List.copyOf(built.world().keySet())) {
			BlockState here = built.at(at);
			// A handover: stone on the lane with dust directly over it.
			if (!here.is(Blocks.STONE) || !built.at(at.above()).is(Blocks.REDSTONE_WIRE)) {
				continue;
			}
			handovers++;
			// Either way along the lane, because a lane runs both ways and this is asked of the
			// blocks rather than off a Lane that knows which.
			boolean driven = built.at(at.east()).is(Blocks.REPEATER)
				|| built.at(at.west()).is(Blocks.REPEATER);
			if (driven) {
				withRepeater++;
			} else if (bare.size() < 3) {
				bare.add(at);
			}
		}
		System.out.println("   " + handovers + " stone-under-dust cells, " + withRepeater
			+ " with a repeater drawing out of them, " + (handovers - withRepeater) + " without");
		for (BlockPos at : bare) {
			System.out.println();
			System.out.println("#### no repeater out of " + FaultView.say(at));
			System.out.println(FaultView.draw(built,
				new BlockPos(at.getX() - 4, at.getY() - 2, at.getZ()),
				new BlockPos(at.getX() + 4, at.getY() + 3, at.getZ()),
				AsciiDiagram.View.NORTH));
		}
	}
}
