package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A v2 paste is exactly as wide as the paste screen said it would be.
 *
 * <p>ekran's rule: <i>"if the slider on the paste screen shows 25 wide, thats how wide it will be
 * (not counting breaches)."</i> Every kind of turn now reaches exactly one column past its wall --
 * a descent's outer rung, a climb's glass, a flat turn's corner and the notes hanging off it -- and
 * the column behind the first repeater is where the button goes. So a build with no breach spans
 * {@code width} columns, from one past the near wall to one past the far, and nothing at all stands
 * further out. See {@link SongBuilder#V2_WIDTH_IS_THE_PASTE_WIDTH},
 * {@link SongBuilder#CLIMB_STANDS_A_COLUMN_OUT} and {@link SongBuilder#FLAT_TURN_KEEPS_ITS_WIDTH}.</p>
 *
 * <p>Not tagged {@code sweep}: it is a regression, on builds that were clean when it was written.
 * A build that starts breaching fails it for the wrong reason, which is why the breach list is
 * printed with the failure. {@link XExtentProbe} lists what stands past the walls of any build.</p>
 */
class PasteWidthTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> song, int width,
			int floors) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(4, width, floors));
	}

	private static void isExactly(String name, SongBuilder.PastePlan plan, int width) {
		String where = name + " at " + width + " wide, breaches=" + plan.breaches();
		assertTrue(plan.breaches().isEmpty(),
			"the width promise is made for builds that do not breach, and this one does: " + where);
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
		}
		assertEquals(width, plan.spanX(), "the paste spans what the slider said: " + where);
		assertEquals(plan.nearWall() - 1, minX,
			"nothing stands more than one column past the near wall: " + where);
		assertEquals(plan.farWall() + 1, maxX,
			"nothing stands more than one column past the far wall: " + where);
		// And the button's column is the edge: the first repeater stands on the near wall, so the
		// block behind it -- where the player puts the button -- is the outermost column.
		String[] first = plan.commands().get(0).split(" ");
		assertEquals(plan.nearWall(), Integer.parseInt(first[1]),
			"the first repeater stands on the near wall, the button one column out: " + where);
	}

	@Test
	void guardianIsAsWideAsItSays() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		isExactly("guardian 5 floors", build(guardian, 25, 5), 25);
		isExactly("guardian 3 floors", build(guardian, 40, 3), 40);
	}

	@Test
	void theTargetSongIsAsWideAsItSays() {
		List<SongBuilder.EventNote> song = AllTwentyFivesTest.allTwentyFives();
		isExactly("all-25 two floors", build(song, 32, 2), 32);
		isExactly("all-25 three floors", build(song, 24, 3), 24);
	}
}
