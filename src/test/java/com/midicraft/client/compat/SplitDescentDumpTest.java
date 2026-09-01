package com.midicraft.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: one split across one descent, every block of it, in build order. */
@Tag("sweep")
class SplitDescentDumpTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dumps() {
		// Seeded onto floor one going down, so the very first wall this walk meets is a descent.
		for (boolean cheap : new boolean[] {false, true}) {
		SongBuilder.CHEAP_SPLIT_DESCENT = cheap;
		for (String spec : new String[] {"2 18"}) {
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					DebugChords.notes(spec, 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, 24, 2), new SongBuilder.WalkStart(18, 1, -1));
				System.out.println("=== " + spec + (cheap ? " CHEAP" : " OLD") + " ===");
				List<String> commands = plan.commands();
				for (String command : commands) {
					System.out.println("DUMP " + command.replace("setblock ", "")
						.replace(" replace", ""));
				}
				System.out.println("DUMPFAULTS " + spec + " " + plan.faults());
			} catch (RuntimeException refused) {
				System.out.println("=== " + spec + (cheap ? " CHEAP" : " OLD") + " REFUSED "
					+ refused.getMessage());
			}
		}
		}
		SongBuilder.CHEAP_SPLIT_DESCENT = true;
	}
}
