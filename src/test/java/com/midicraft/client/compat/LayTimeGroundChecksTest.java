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
		SongBuilder.BUS_MEASURES_ITS_WIRE = true;
		SongBuilder.BUS_OPENS_WITH_ITS_BACK_SLOT = true;
		SongBuilder.BACK_SLOT_WANTS_SETTLED_GROUND = true;
		SongBuilder.FORCED_TURN_OPENS_A_LANE = true;
		SongBuilder.FLUSH_CHORD_KEEPS_THE_TURN_RUN_CLEAR = true;
		SongBuilder.STAIRCASES_PAST_THE_OUTER_ALLOWANCE_REWALK = true;
		SongBuilder.FOLDBACK_DESCENT_RESCUES_A_DEAD_FOLD = true;
		SongBuilder.SWAP_SINKS_A_BUS_THAT_RUNS_OUT = true;
		SongBuilder.SWAP_DOUBLE_SINKS_A_LOUD_OPENING = true;
		SongBuilder.FOLDBACK_PADS_PAST_WIRE_BEHIND = true;
	}

	/**
	 * A foldback refused for the wire behind it stands a column on over a parity pad instead of
	 * walking the chord past the wall. The user's design, from this build.
	 * See FOLDBACK_PADS_PAST_WIRE_BEHIND.
	 */
	@Test
	void letItHappenNineteenByThreeBottomStartKeepsInsideItsWalls() throws Exception {
		Built built = build("let-it-happen-tame-impala", 19, 3, false);
		assertTrue(built.plan().wallBreaches().isEmpty(), "walls: " + built.plan().wallBreaches());
		assertEquals(0, built.plan().wrongNotes(), "wrong: " + built.plan().faults());
		assertEquals(0, built.plan().missingNotes(), "missing: " + built.plan().faults());
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
	}

	@Test
	void withoutTheFoldPadTheChordRunsPastTheWall() throws Exception {
		SongBuilder.FOLDBACK_PADS_PAST_WIRE_BEHIND = false;
		Built built = build("let-it-happen-tame-impala", 19, 3, false);
		assertTrue(!built.plan().wallBreaches().isEmpty(),
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/**
	 * A two-swap turn whose repeater would drive a block against the other machine's note opens on
	 * dust, both cells sunk. The user's design, from this build. See SWAP_DOUBLE_SINKS_A_LOUD_OPENING.
	 */
	@Test
	void grimGrinningGhostsNineteenByTwoTopStartSoundsNothingTwice() throws Exception {
		Built built = build("buddy-baker-xavier-atencio-grim-grinning-ghosts", 19, 2, true);
		assertEquals(0, built.plan().wrongNotes(), "wrong: " + built.plan().faults());
		assertEquals(0, built.plan().missingNotes(), "missing: " + built.plan().faults());
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
	}

	@Test
	void withoutTheDoubleSinkTheSwapBlockSoundsTheOtherLane() throws Exception {
		SongBuilder.SWAP_DOUBLE_SINKS_A_LOUD_OPENING = false;
		Built built = build("buddy-baker-xavier-atencio-grim-grinning-ghosts", 19, 2, true);
		assertTrue(built.plan().wrongNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/**
	 * A two-swap turn whose plain bus runs out of wire sinks instead, on a plain opening where the
	 * chord has no harp. The user's design, from this build. See SWAP_SINKS_A_BUS_THAT_RUNS_OUT.
	 */
	@Test
	void hotmkTwentyFiveByOneHangsTheTwentyEighthNote() throws Exception {
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 25, 1, false);
		assertEquals(0, built.plan().missingNotes(), "missing: " + built.plan().faults());
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
		assertEquals(0, built.plan().wrongNotes(), "wrong: " + built.plan().faults());
	}

	@Test
	void withoutSinkingTheSwappedBusDropsOne() throws Exception {
		SongBuilder.SWAP_SINKS_A_BUS_THAT_RUNS_OUT = false;
		// An earlier corner double-sinks and the walk never meets this chord in a swap at all.
		SongBuilder.SWAP_DOUBLE_SINKS_A_LOUD_OPENING = false;
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 25, 1, false);
		assertTrue(built.plan().missingNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/**
	 * A wait that cannot reach the bottom of its descent, with no cell for a repeater before it,
	 * goes down the compact foldback descent. The user's design, from this build.
	 */
	@Test
	void illitFortyFourBySevenBottomStartKeepsInsideItsWalls() throws Exception {
		Built built = build("illit-do-the-dance-2-lanes", 44, 7, false);
		assertTrue(built.plan().wallBreaches().isEmpty(), "walls: " + built.plan().wallBreaches());
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
		assertEquals(1, built.reading().versions(), "ways in: " + built.reading().warnings());
	}

	@Test
	void withoutTheFoldbackDescentTheChordRunsPastTheWall() throws Exception {
		SongBuilder.FOLDBACK_DESCENT_RESCUES_A_DEAD_FOLD = false;
		Built built = build("illit-do-the-dance-2-lanes", 44, 7, false);
		assertTrue(!built.plan().wallBreaches().isEmpty(),
			"the fault this test guards is no longer reproduced with the fix off");
	}

	// ---- 2026-09-23, breaches -------------------------------------------------------------------

	/** A chord landing on its wall keeps the tight turn's run clear. */
	@Test
	void wellermanEightByOneKeepsInsideItsWalls() throws Exception {
		Built built = build("sea-shanty-wellerman", 8, 1, false);
		assertEquals(0, built.plan().innerWallBreaches(), "walls: " + built.plan().wallBreaches());
	}

	@Test
	void withoutTheClearRunTheCornerStandsPastTheInnerWall() throws Exception {
		SongBuilder.FLUSH_CHORD_KEEPS_THE_TURN_RUN_CLEAR = false;
		Built built = build("sea-shanty-wellerman", 8, 1, false);
		assertTrue(built.plan().innerWallBreaches() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/** A staircase two past its outer wall forces a turn. */
	@Test
	void sunsetNineteenByThreeTopStartKeepsInsideItsWalls() throws Exception {
		Built built = build("sunset-of-seven-suns", 19, 3, true);
		assertTrue(built.plan().wallBreaches().isEmpty(), "walls: " + built.plan().wallBreaches());
	}

	@Test
	void withoutTheStaircaseRewalkTheDescentStandsTwoOut() throws Exception {
		SongBuilder.STAIRCASES_PAST_THE_OUTER_ALLOWANCE_REWALK = false;
		Built built = build("sunset-of-seven-suns", 19, 3, true);
		assertTrue(!built.plan().wallBreaches().isEmpty(),
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/** A one-lane build is not refused by a hard outer wall nothing catches (13623e1). */
	@Test
	void aOneLaneBuildWithAChordPastItsOuterWallIsStillBuilt() throws Exception {
		Built built = build("all-my-fellas-remastered-finished", 58, 3, true);
		assertTrue(!built.plan().commands().isEmpty(), "the build was refused");
	}

	// ---- 2026-09-23 ----------------------------------------------------------------------------

	/** A bus round a two-corner turn carries on while its dust does. See BUS_MEASURES_ITS_WIRE. */
	@Test
	void hotmkFiftyFourByOneHangsEveryNoteOfItsCornerBus() throws Exception {
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 54, 1, false);
		assertEquals(0, built.plan().missingNotes(), "missing: " + built.plan().faults());
		assertEquals(0, built.reading().unreachedNotes(),
			"notes the signal never reached: " + built.reading().unreachedAt());
	}

	@Test
	void withoutTheWireMeasureTheCornerBusDropsTwo() throws Exception {
		SongBuilder.BUS_MEASURES_ITS_WIRE = false;
		// Or the sunk swap rescues the same note and the fix off proves nothing.
		SongBuilder.SWAP_SINKS_A_BUS_THAT_RUNS_OUT = false;
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 54, 1, false);
		assertTrue(built.plan().missingNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/** A two-swap turn's first bus cell hangs a note behind it. See BUS_OPENS_WITH_ITS_BACK_SLOT. */
	@Test
	void hotmkThirtyEightByOneHangsEveryNote() throws Exception {
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 38, 1, false);
		assertEquals(0, built.plan().missingNotes(), "missing: " + built.plan().faults());
	}

	@Test
	void withoutTheBackSlotTheBusDropsOne() throws Exception {
		SongBuilder.BUS_OPENS_WITH_ITS_BACK_SLOT = false;
		// Or the sunk swap rescues the same note and the fix off proves nothing.
		SongBuilder.SWAP_SINKS_A_BUS_THAT_RUNS_OUT = false;
		Built built = build("hall-of-the-mountain-king-2-lanes-insane-copy", 38, 1, false);
		assertTrue(built.plan().missingNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/** The back slot only on settled ground: all my fellas 8x1 sounded it again otherwise. */
	@Test
	void allMyFellasEightByOneSoundsNothingTwice() throws Exception {
		Built built = build("all-my-fellas-remastered-finished-2", 8, 1, false);
		assertEquals(0, built.plan().wrongNotes(), "wrong notes: " + built.plan().faults());
	}

	@Test
	void withoutSettledGroundTheBackSlotIsSoundedAgain() throws Exception {
		SongBuilder.BACK_SLOT_WANTS_SETTLED_GROUND = false;
		Built built = build("all-my-fellas-remastered-finished-2", 8, 1, false);
		assertTrue(built.plan().wrongNotes() > 0,
			"the fault this test guards is no longer reproduced with the fix off");
	}

	/** The rail's claim takes the side its note will hang on (c02d47b). */
	@Test
	void darkZoneFiftyEightByThreeTopStartSoundsNothingEarly() throws Exception {
		Built built = build("a-dark-zone-2-lanes", 58, 3, true);
		assertEquals(0, built.plan().wrongNotes(), "wrong notes: " + built.plan().faults());
	}

	/**
	 * A forced turn opens an empty lane, and a rollback forgets the rail tail it undid: michael
	 * jackson bad 8x1 was two machines with twelve collisions. See FORCED_TURN_OPENS_A_LANE.
	 */
	@Test
	void michaelJacksonBadEightByOneIsOneMachine() throws Exception {
		Built built = build("michael-jackson-bad", 8, 1, false);
		assertTrue(built.plan().collisions().isEmpty(), "collisions: " + built.plan().collisions());
		assertEquals(1, built.reading().versions(), "ways in: " + built.reading().warnings());
	}

	@Test
	void withoutTheForcedOpeningTheFirstChordCollides() throws Exception {
		SongBuilder.FORCED_TURN_OPENS_A_LANE = false;
		Built built = build("michael-jackson-bad", 8, 1, false);
		assertTrue(!built.plan().collisions().isEmpty(),
			"the fault this test guards is no longer reproduced with the fix off");
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
