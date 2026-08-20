package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The flat-turn breach: Guardian 15 wide over six floors, the lane through 15 81 379. */
@Tag("sweep")
class BendCollisionTest {
	/** At {@code 15 81 379}. Sixteen wide over five floors. */
	private static final int WIDE = Integer.getInteger("bend.w", 16);
	private static final int TALL = Integer.getInteger("bend.f", 5);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void showsTheBendCollision() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		for (boolean wrap : new boolean[] {false, true}) {
			SongBuilder.STACKED_MAY_WRAP_A_BEND = wrap;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, WIDE, TALL));
			System.out.println();
			System.out.println("==== guardian " + WIDE + "w x " + TALL + "f, wrapABend=" + (wrap ? "on" : "off") + " ====");
			System.out.println("   breaches=" + plan.breaches() + " wrong=" + plan.wrongNotes()
				+ " unreached=" + BreachView.readBack("bend", plan).unreachedNotes()
				+ " nearWall=" + plan.nearWall() + " farWall=" + plan.farWall());
			plan.padding().entrySet().stream()
				.filter(e -> e.getKey().startsWith("v2Shape") || e.getKey().contains("ollision"))
				.forEach(e -> System.out.println("   " + e.getKey() + " " + e.getValue()));
			plan.faults().stream().limit(4).forEach(f -> System.out.println("   fault " + f));
		}
		SongBuilder.STACKED_MAY_WRAP_A_BEND = true;
	}
}
