package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: every note cell the plan timed at one tick, with what laid it, so a fault that
 * names a tick and no cell ("had nowhere to hang") can be found in the build.
 *
 * <pre>
 * gradlew sweepTest --tests "*TickCellsProbe" -Dfault.song=... -Dfault.width=8 -Dfault.floors=3
 *     -Dfault.startTop=false -Dfault.tick=864
 * </pre>
 */
@Tag("sweep")
class TickCellsProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("fault." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	@Test
	void lists() throws Exception {
		String song = text("song", "hall-of-the-mountain-king-2-lanes-insane-copy");
		int width = Integer.parseInt(text("width", "8"));
		int floors = Integer.parseInt(text("floors", "3"));
		int tick = Integer.parseInt(text("tick", "864"));
		int reach = Integer.parseInt(text("reach", "2"));
		GameSettings.Values game = GameSettings.get();
		FaultView.Build built = FaultView.of(song, SongBuilder.PasteMode.INTERLEAVED_HALF_TICK,
			width, floors, game.maxBuildFloors(), game.debugPaste());
		Map<BlockPos, String> laidBy = built.plan().laidBy();
		System.out.println("TICKCELLS " + built.where() + " tick " + tick + " " + game.said());
		List<BlockPos> cells = new ArrayList<>();
		built.plan().noteTicks().forEach((at, when) -> {
			if (when >= tick - reach && when <= tick + reach) {
				cells.add(at);
			}
		});
		cells.sort((a, b) -> {
			int byTick = Integer.compare(built.plan().noteTicks().get(a),
				built.plan().noteTicks().get(b));
			return byTick != 0 ? byTick : FaultView.say(a).compareTo(FaultView.say(b));
		});
		for (BlockPos at : cells) {
			System.out.println("   t" + built.plan().noteTicks().get(at) + "  " + FaultView.say(at)
				+ "  " + built.at(at).getBlock().getName().getString() + " over "
				+ built.at(at.below()).getBlock().getName().getString()
				+ "  laid by " + laidBy.getOrDefault(at, "?") + "  #"
				+ built.laid().getOrDefault(at, -1));
		}
		for (String fault : built.plan().faults()) {
			if (fault.contains("tick " + tick + " ")) {
				System.out.println("   FAULT " + fault);
			}
		}
	}
}
