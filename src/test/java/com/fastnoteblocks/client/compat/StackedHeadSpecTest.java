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
 * Specs that build a stacked head, checked through the path the command uses.
 *
 * <p>Written because handing somebody a command that turns out to build something else wastes a
 * trip in world. Every spec named here is asserted to produce the module it is advertised as
 * producing, so the commands in the handover cannot rot without this going red.</p>
 */
class StackedHeadSpecTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The two blocks only a stacked module places: the cross, and the stone under it. */
	private static int stackedModules(SongBuilder.PastePlan plan) {
		int crosses = 0;
		for (String command : plan.commands()) {
			if (command.split(" ")[4].startsWith("minecraft:redstone_wire[")) {
				crosses++;
			}
		}
		return crosses;
	}

	private static SongBuilder.PastePlan build(String spec, int width, int floors, String shape,
			int columnsToWall) {
		List<DebugChords.Chord> chords = DebugChords.parse(spec, DebugChords.DEFAULT_GAP);
		SongBuilder.WalkStart start = shape == null ? SongBuilder.WalkStart.HEAD
			: new SongBuilder.WalkStart(Math.max(0, width - 2 - columnsToWall),
				"up".equals(shape) ? 0 : floors - 1, "down".equals(shape) ? -1 : 1, false);
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), DebugChords.notes(chords),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, width, floors), start);
	}

	/**
	 * @param turns whether the spec is advertised as reaching a wall and turning. Asserted, because
	 *     the first version of this file counted modules and nothing else -- and the spec sold as
	 *     "a big chord walking into a descent" never reached its wall at all. ekran pasted it and
	 *     read that off the blocks in about a second: no level change, and the only glass in it was
	 *     the module's own carrying cell rather than a climb.
	 */
	private static void headsIn(String label, String spec, int width, int floors, String shape,
			int columnsToWall, boolean turns) {
		SongBuilder.PastePlan plan = build(spec, width, floors, shape, columnsToWall);
		int modules = stackedModules(plan);
		System.out.println("SPEC " + label + " :: " + modules + " stacked modules, "
			+ plan.turns().size() + " turns, " + plan.width() + " long, "
			+ plan.commands().size() + " blocks");
		for (String fault : plan.faults()) {
			System.out.println("  FAULT " + fault);
		}
		assertTrue(modules > 0, label + " was supposed to build a stacked head");
		assertEquals(turns, !plan.turns().isEmpty(),
			label + " was supposed to " + (turns ? "reach its wall and turn" : "stop short"));
	}

	/** One chord of ten, on its own, with nothing else to move it about. */
	@Test
	void oneChordOfTen() {
		headsIn("24 2 flat 20 :: 10", "10", 24, 2, "flat", 20, false);
	}

	/** The shape the breaches are made of: a big chord walking into a descent it actually takes. */
	@Test
	void aBigChordIntoADescent() {
		headsIn("16 3 down 10 :: 10 24", "10 24", 16, 3, "down", 10, true);
	}

	/** All four instruments, because an all-harp chord is not what a song holds. */
	@Test
	void withRealInstruments() {
		headsIn("24 2 flat 20 :: 12:2b2s8p", "12:2b2s8p", 24, 2, "flat", 20, false);
	}

	/** A run of them, so the modules can be compared against each other down one lane. */
	@Test
	void aRunOfThem() {
		headsIn("32 2 flat 28 :: 10x4@6", "10x4@6", 32, 2, "flat", 28, false);
	}
}
