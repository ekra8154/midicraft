package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A width below what the song's widest chord needs is raised, not refused -- and the plan says so.
 *
 * <p>The paste screen's width line is written off {@link SongBuilder.PastePlan#builtWidth}, so the
 * number it shows is only as true as the sum that undoes the clamp. These are the two halves of
 * that: every width below the floor really does build identically, and {@code builtWidth} really
 * does name the floor rather than something three either side of it.</p>
 */
class WidthClampTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SongBuilder.PastePlan at(int width) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes("20 20 20 20 6 6 20 20", 4),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(4, width, 3));
	}

	@Test
	void everyWidthBelowTheFloorIsTheSameBuild() {
		int floor = at(4).builtWidth();
		assertTrue(floor > 4, "a song of twenty-note chords cannot fit a paste four blocks across, "
			+ "so the width must have been raised; builtWidth said " + floor);
		for (int width = 4; width <= floor; width++) {
			SongBuilder.PastePlan plan = at(width);
			assertEquals(floor, plan.builtWidth(),
				"asked " + width + ", which is at or below the floor of " + floor);
			assertEquals(at(floor).commands(), plan.commands(),
				"asked " + width + ": a width below the floor must build exactly what the floor "
					+ "builds, or the screen is promising a build nobody makes");
		}
	}

	@Test
	void aWidthAboveTheFloorIsBuiltAtTheWidthItAsked() {
		int floor = at(4).builtWidth();
		for (int width = floor + 1; width <= floor + 4; width++) {
			assertEquals(width, at(width).builtWidth(),
				"asked " + width + ", which is above the floor of " + floor);
		}
	}

	/**
	 * The number on the slider is the number of blocks across, which is the whole of
	 * {@link SongBuilder#V2_WIDTH_IS_THE_PASTE_WIDTH} -- and {@code builtWidth} has to agree with
	 * the blocks rather than merely with the arithmetic that produced them.
	 */
	@Test
	void theBuiltWidthIsTheWidthOfTheBlocks() {
		for (int width : new int[] {4, 12, 30}) {
			SongBuilder.PastePlan plan = at(width);
			int lowX = Integer.MAX_VALUE;
			int highX = Integer.MIN_VALUE;
			for (String command : plan.commands()) {
				int x = Integer.parseInt(command.split(" ")[1]);
				lowX = Math.min(lowX, x);
				highX = Math.max(highX, x);
			}
			assertEquals(plan.builtWidth(), highX - lowX + 1,
				"asked " + width + ": builtWidth must be the width of the blocks that go down");
		}
	}
}
