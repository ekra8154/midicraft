package com.midicraft.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the rail runs are worth to v2, against v2 without them.
 *
 * <p>Both arms of every row, because a new move has to be measured against the old one or it has not
 * been offered -- and the thing it is meant to buy is columns, which only means anything as a
 * difference. Depth is the number: a run costs one column a chord instead of two, so a song it fires
 * on should come out shorter and a song it never fires on should come out identical.</p>
 *
 * <p>Read back through {@link NoteMachineReader} in both arms. A shorter build that has stopped
 * playing is not a shorter build, and the plan's own numbers read nought over a severed wire.</p>
 */
@Tag("sweep")
class V2RailSweepTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The songs written for the runs, plus the two the real work is judged on. */
	private static final List<String> RAIL_SONGS = List.of(
		"ultra-ones-gap1", "ultra-ones-gap2", "ultra-ones-mixed",
		"ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed");

	private static final int[][] SIZES = {{16, 2}, {16, 3}, {24, 3}, {40, 5}};

	private record Arm(int depth, int breach, int wrong, int dead) {
	}

	private static Arm build(String song, List<SongBuilder.EventNote> notes, int width, int floors,
			boolean rails) {
		SongBuilder.V2_RUNS_ON_RAILS = rails;
		FaultView.Build built = FaultView.of(song, notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, width, floors, 4, false);
		return new Arm(built.plan().spanZ(),
			built.plan().breaches().stream().mapToInt(Integer::intValue).sum(),
			built.plan().wrongNotes(), built.reading().unreachedNotes());
	}

	private static void sweep(String song, int[][] sizes) throws Exception {
		List<SongBuilder.EventNote> notes = BreachView.song(song);
		System.out.println();
		System.out.println("---- " + song + " (" + notes.size() + " notes) ----");
		int offTotal = 0;
		int onTotal = 0;
		for (int[] size : sizes) {
			Arm off;
			Arm on;
			try {
				off = build(song, notes, size[0], size[1], false);
				on = build(song, notes, size[0], size[1], true);
			} catch (RuntimeException refused) {
				System.out.println(String.format("   %2dw x %df  REFUSED: %s", size[0], size[1],
					refused.getMessage()));
				continue;
			}
			offTotal += off.depth();
			onTotal += on.depth();
			// The delta first, because it is the only column anyone reads twice.
			System.out.println(String.format(
				"   %2dw x %df  depth %4d -> %4d (%+d)   breach %2d -> %-2d  wrong %2d -> %-2d"
				+ "  dead %2d -> %-2d%s",
				size[0], size[1], off.depth(), on.depth(), on.depth() - off.depth(),
				off.breach(), on.breach(), off.wrong(), on.wrong(), off.dead(), on.dead(),
				on.dead() > off.dead() || on.wrong() > off.wrong() ? "   <-- WORSE" : ""));
		}
		System.out.println("   total depth " + offTotal + " -> " + onTotal
			+ " (" + (onTotal - offTotal) + ")");
	}

	@Test
	void pricesTheRunsAgainstV2WithoutThem() throws Exception {
		boolean was = SongBuilder.V2_RUNS_ON_RAILS;
		try {
			System.out.println();
			System.out.println("==== v2 rail runs: off -> on ====");
			for (String song : RAIL_SONGS) {
				sweep(song, SIZES);
			}
			// The real work, at the two sizes the handoff quotes. Guardian is 16 instruments and 1,556
			// events against these songs' handful, so it is the only one here that can say whether the
			// runs survive contact with big chords standing beside them.
			sweep("deltarune-ch-4-guardian", new int[][] {{20, 5}, {40, 5}});
		} finally {
			SongBuilder.V2_RUNS_ON_RAILS = was;
		}
	}
}
