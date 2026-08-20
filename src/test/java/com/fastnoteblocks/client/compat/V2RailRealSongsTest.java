package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The v2 rail port on real music, which is the only place the contention lives.
 *
 * <p>The songs written for the runs cannot see the thing most likely to break them. None of them
 * holds a chord big enough to stack, and the known fault of the runs is exactly a stacked chord
 * standing beside one: a floor column hangs its notes at the lane's own floor level, which is where
 * the lane alongside hangs the low half of a stacked module, and those low notes stand on
 * instrument blocks that conduct sideways. Both fixes for it came across with the port -- a chord
 * with a side to spare hangs on the far side, and a chord needing both is refused the floor column
 * -- but they were measured against v1's walk, not v2's.</p>
 *
 * <p>Read back in both arms, and the wrong-note and dead columns are the ones to read. Depth is
 * whatever the song allows: a song of big chords has no run in it and should not move at all, and
 * that is a result rather than a disappointment -- see Guardian, which lays nought.</p>
 */
@Tag("sweep")
class V2RailRealSongsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Real songs, picked for the shapes that break things rather than for being liked.
	 *
	 * <p>lady brown is where in-game reading found the floor rail carried the length of a run without
	 * ever holding a chord; song of storms is where a run opened on a wait of eight and sounded its
	 * whole first phrase early; all of the lights is where the dead wire was found. The rest are
	 * ordinary and are here to be ordinary.</p>
	 */
	private static final List<String> SONGS = List.of(
		"lady-brown-nujabes", "song-of-storms-legend-of-zelda-ocarina-of-time",
		"all-of-the-lights-kanye-west", "aria-math-c418", "big-shot",
		"golden-brown-2xspeed", "illit-do-the-dance", "he-s-a-pirate");

	private static final int[][] SIZES = {{16, 3}, {24, 3}, {40, 5}};

	private record Arm(int depth, int breach, int wrong, int dead, int railColumns) {
	}

	private static Arm build(String song, List<SongBuilder.EventNote> notes, int width, int floors,
			boolean rails) {
		SongBuilder.V2_RUNS_ON_RAILS = rails;
		SongBuilder.RAIL_COLUMNS = 0;
		FaultView.Build built = FaultView.of(song, notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, width, floors, 4, false);
		return new Arm(built.plan().spanZ(),
			built.plan().breaches().stream().mapToInt(Integer::intValue).sum(),
			built.plan().wrongNotes(), built.reading().unreachedNotes(), SongBuilder.RAIL_COLUMNS);
	}

	@Test
	void pricesTheRunsOnMusicWithBigChordsInIt() throws Exception {
		boolean was = SongBuilder.V2_RUNS_ON_RAILS;
		int worseRows = 0;
		try {
			System.out.println();
			System.out.println("==== v2 rail runs on real songs: off -> on ====");
			for (String song : SONGS) {
				List<SongBuilder.EventNote> notes = BreachView.song(song);
				System.out.println();
				System.out.println("---- " + song + " (" + notes.size() + " notes) ----");
				for (int[] size : SIZES) {
					Arm off;
					Arm on;
					try {
						off = build(song, notes, size[0], size[1], false);
						on = build(song, notes, size[0], size[1], true);
					} catch (RuntimeException refused) {
						System.out.println(String.format("   %2dw x %df  REFUSED: %s", size[0],
							size[1], refused.getMessage()));
						worseRows++;
						continue;
					}
					boolean worse = on.dead() > off.dead() || on.wrong() > off.wrong()
						|| on.breach() > off.breach();
					if (worse) {
						worseRows++;
					}
					System.out.println(String.format(
						"   %2dw x %df  rails %5d  depth %4d -> %4d (%+d)   breach %3d -> %-3d"
						+ "  wrong %3d -> %-3d  dead %3d -> %-3d%s",
						size[0], size[1], on.railColumns(), off.depth(), on.depth(),
						on.depth() - off.depth(), off.breach(), on.breach(), off.wrong(), on.wrong(),
						off.dead(), on.dead(), worse ? "   <-- WORSE" : ""));
				}
			}
			System.out.println();
			System.out.println("==== " + worseRows + " rows the runs made worse ====");
		} finally {
			SongBuilder.V2_RUNS_ON_RAILS = was;
		}
	}
}
