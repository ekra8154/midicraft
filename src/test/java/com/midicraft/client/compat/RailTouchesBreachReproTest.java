package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * That the two-rail work is not what turned {@code BreachReproSearchTest} red.
 *
 * <p>That test asks its spec to breach and it no longer does, which is a change in the walk and
 * worth pinning on something. Every line the runs added is behind a run being opened, so the
 * question is only whether this spec opens one -- and the plan says so itself, because a run's
 * columns are counted under {@code rail*} keys in the padding census. Nought of them means the
 * shape was never reached and the red is somebody else's.</p>
 */
@Tag("sweep")
class RailTouchesBreachReproTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void theBreachReproOpensNoRunAtAll() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes(DebugChords.parse("24@1 24@1 1@1 19@1 1@1", DebugChords.DEFAULT_GAP)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 28, 3), new SongBuilder.WalkStart(0, 2, -1, false));
		Map<String, Integer> rail = new java.util.TreeMap<>();
		plan.padding().forEach((key, count) -> {
			if (key.startsWith("rail")) {
				rail.put(key, count);
			}
		});
		System.out.println("TOUCH the breach repro built " + rail + " of run");
		assertEquals(Map.of(), rail,
			"the breach repro spec builds no rail column, so the runs cannot be what changed it");
	}
}
