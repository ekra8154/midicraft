package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ChordSkips;
import com.midicraft.client.composer.ComposerProject;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A forced turn ahead of a flat corner the chord cannot lie across pads to the corner and turns,
 * rather than laying the chord through the inner wall into the other machine.
 *
 * <p>Thriller at twenty wide over two floors, bottom-start, collisions recorded: machine B's
 * stacked bus of twenty-nine at z=591 was decided six columns from its inner wall, could not
 * straddle the flat turn, and was refused it on every rewalk -- so the wall went soft and the
 * chord ran nine past it into the column machine A hangs its notes in. See
 * {@link SongBuilder#FORCED_TURN_PADS_TO_A_FLAT_CORNER}.</p>
 *
 * <p>Built with the settings pinned rather than read off the game's config: the start floor and
 * the debug paste both change the walk, and a regression that follows the player's toggles is a
 * regression that passes and fails with them.</p>
 */
class ForcedFlatCornerTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.FORCED_TURN_PADS_TO_A_FLAT_CORNER = true;
		SongBuilder.STACKED_BUS_STRADDLES_BY_ITS_TAIL = true;
	}

	@Test
	void thrillerAtTwentyByTwoKeepsMachineBInsideItsWall() throws Exception {
		SongBuilder.PastePlan plan = thriller();
		assertEquals(0, plan.innerWallBreaches(), "inner wall breaches: " + plan.wallBreaches());
		assertTrue(plan.collisions().isEmpty(), "cells two shapes both wanted: " + plan.collisions());
	}

	/**
	 * The same build with both ways round the corner off, so the test is known to be pointed at
	 * the fault. Both, because the chord is a stacked bus of head six and tail twenty-three and
	 * since {@link SongBuilder#STACKED_BUS_STRADDLES_BY_ITS_TAIL} it rides the corner on its tail
	 * before any turn has to be forced; the pad is what is left for the chords that cannot.
	 */
	@Test
	void withoutThePadTheChordRunsThroughTheWall() throws Exception {
		SongBuilder.FORCED_TURN_PADS_TO_A_FLAT_CORNER = false;
		SongBuilder.STACKED_BUS_STRADDLES_BY_ITS_TAIL = false;
		SongBuilder.PastePlan plan = thriller();
		assertTrue(plan.innerWallBreaches() > 0 || !plan.collisions().isEmpty(),
			"the fault this test guards is no longer reproduced with the fix off");
	}

	private static SongBuilder.PastePlan thriller() throws Exception {
		String name = "michael-jackson-thriller";
		Assumptions.assumeTrue(BreachView.inLibrary(name), name + " is not in the library");
		ComposerProject song = GameSettings.project(BreachView.songFile(name));
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		ChordSkips.Rules thinning = new ChordSkips.Rules(28, true, true);
		boolean was = SongBuilder.DEBUG_PASTE;
		try {
			// Recorded, not thrown: the marked paste's walk, which is the one that gave the wall up.
			SongBuilder.DEBUG_PASTE = true;
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true, thinning), song,
					true, thinning),
				mode, new SongBuilder.BuildLimits(16, 20, 2, false, 16));
		} finally {
			SongBuilder.DEBUG_PASTE = was;
		}
	}
}
