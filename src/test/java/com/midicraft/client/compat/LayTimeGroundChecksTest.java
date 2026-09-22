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
 * Four faults the dense two-lane census of 2026-09-21 was left with, each a decision made before the
 * ground it was made about existed, and each pinned here to the build that showed it.
 *
 * <ul>
 * <li>A parity seam whose repeater goes down on a landing with its first stage stands forward
 * like one whose repeater crossed alone, or its landed block's dust dies on the straight cell
 * before the second piston: Song of Storms at eight wide over three floors, top-start, 148 notes
 * dark. See {@link SongBuilder#SEAM_STAGE_ONE_STANDS_FORWARD}.</li>
 * <li>A rail claims the floor slots its pair has committed to, so the other machine's stacked
 * module refuses the ground instead of sounding them a tick early: moonlight sonata at eight wide
 * over two floors, bottom-start. See {@link SongBuilder#RAIL_CLAIMS_ITS_COMMITTED_FLOOR}.</li>
 * <li>A corkscrew head's front pair stays empty when its lows are rehomed, and a centre-fed head's
 * rung flanks are asked of the ground: HOTMK to end all at sixty-three wide over five floors,
 * top-start, two notes with nothing to set them off and one sounded early by the next lane's
 * climb. See {@link SongBuilder#CUT_HEAD_ASKS_ITS_RUNG_FLANKS}.</li>
 * <li>A descent foldback's catch on a wall column hangs no note on the side the next lane's
 * descent lands its rung: a dark zone at twenty-five wide over eight floors, top-start. See
 * {@link SongBuilder#FOLDBACK_CATCH_KEEPS_OFF_THE_DESCENT_ROW}.</li>
 * </ul>
 *
 * <p>Built with the settings pinned rather than read off the game's config, for the reason
 * {@link ForcedFlatCornerTest} gives: a regression that follows the player's toggles passes and
 * fails with them. Read back through the reader where the fault is a dead note, because the
 * plan's own counters read nought over a wire that never fires.</p>
 */
class LayTimeGroundChecksTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.SEAM_STAGE_ONE_STANDS_FORWARD = true;
		SongBuilder.RAIL_CLAIMS_ITS_COMMITTED_FLOOR = true;
		SongBuilder.CUT_HEAD_ASKS_ITS_RUNG_FLANKS = true;
		SongBuilder.FOLDBACK_CATCH_KEEPS_OFF_THE_DESCENT_ROW = true;
	}

	@Test
	void songOfStormsEightByThreeTopStartPlaysEveryNote() throws Exception {
		Built built = build("song-of-storms-legend-of-zelda-ocarina-of-time", 8, 3, true);
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
	}

	@Test
	void withoutTheStandForwardTheSeamStarves() throws Exception {
		SongBuilder.SEAM_STAGE_ONE_STANDS_FORWARD = false;
		Built built = build("song-of-storms-legend-of-zelda-ocarina-of-time", 8, 3, true);
		assertTrue(built.reading().unreachedNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	@Test
	void moonlightEightByTwoBottomStartSoundsNothingEarly() throws Exception {
		Built built = build("moonlight-sonata-3rd-movement-2-lanes-melodic", 8, 2, false);
		assertEquals(0, built.plan().wrongNotes(), "wrong notes: " + built.plan().faults());
	}

	@Test
	void withoutTheClaimTheRailNoteSoundsEarly() throws Exception {
		SongBuilder.RAIL_CLAIMS_ITS_COMMITTED_FLOOR = false;
		Built built = build("moonlight-sonata-3rd-movement-2-lanes-melodic", 8, 2, false);
		assertTrue(built.plan().wrongNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	@Test
	void hotmkSixtyThreeByFiveTopStartIsCleanAtItsCutHeads() throws Exception {
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 63, 5, true);
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
		assertEquals(0, built.plan().wrongNotes(), "wrong notes: " + built.plan().faults());
	}

	@Test
	void withoutTheRungAskTheClimbSoundsTheFlank() throws Exception {
		SongBuilder.CUT_HEAD_ASKS_ITS_RUNG_FLANKS = false;
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 63, 5, true);
		assertTrue(built.plan().wrongNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	@Test
	void darkZoneTwentyFiveByEightTopStartSoundsNothingTwice() throws Exception {
		Built built = build("a-dark-zone-2-lanes", 25, 8, true);
		assertEquals(0, built.plan().wrongNotes(), "wrong notes: " + built.plan().faults());
	}

	@Test
	void withoutTheCatchRuleTheRungSoundsTheFoldbackNote() throws Exception {
		SongBuilder.FOLDBACK_CATCH_KEEPS_OFF_THE_DESCENT_ROW = false;
		Built built = build("a-dark-zone-2-lanes", 25, 8, true);
		assertTrue(built.plan().wrongNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	private record Built(SongBuilder.PastePlan plan, NoteMachineReader.Reading reading) {
	}

	private static Built build(String name, int width, int floors, boolean startTop)
			throws Exception {
		Assumptions.assumeTrue(BreachView.inLibrary(name), name + " is not in the library");
		ComposerProject song = GameSettings.project(BreachView.songFile(name));
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		ChordSkips.Rules thinning = new ChordSkips.Rules(28, true, true);
		boolean was = SongBuilder.DEBUG_PASTE;
		try {
			// Recorded, not thrown: the marked paste's walk, which is what the census builds.
			SongBuilder.DEBUG_PASTE = true;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true, thinning), song,
					true, thinning),
				mode, new SongBuilder.BuildLimits(16, width, floors, startTop, 16));
			return new Built(plan, BreachView.readBack(name, plan));
		} finally {
			SongBuilder.DEBUG_PASTE = was;
		}
	}
}
