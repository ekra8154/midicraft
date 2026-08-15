package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Whether the runs fire at all, per song and per paster.
 *
 * <p>Asked because a feature that never runs and a feature that runs and changes nothing produce
 * identical numbers in every other measurement here -- and Guardian came back from the v2 rail port
 * byte-identical, which is either of those. Counting the columns settles it in one run.</p>
 *
 * <p>Both pasters on the same songs, because v1 has had the runs for a while: a song where v1 lays
 * columns and v2 lays none is a fault in the port, and a song where neither lays any is a song with
 * no run in it. Those two want completely different work and nothing else distinguishes them.</p>
 */
@Tag("sweep")
class RailFiresProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final List<String> SONGS = List.of(
		"ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed",
		"deltarune-ch-4-guardian");

	private static int columns(List<SongBuilder.EventNote> notes, SongBuilder.PasteMode mode,
			int width, int floors) {
		SongBuilder.RAIL_COLUMNS = 0;
		boolean marking = SongBuilder.MARK_UNREACHED;
		try {
			// Off, so the count is of one walk rather than of a walk and the read-back rebuild.
			SongBuilder.MARK_UNREACHED = false;
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode,
				new SongBuilder.BuildLimits(4, width, floors));
		} finally {
			SongBuilder.MARK_UNREACHED = marking;
		}
		return SongBuilder.RAIL_COLUMNS;
	}

	@Test
	void countsTheColumnsEachPasterLaysOnRails() throws Exception {
		System.out.println();
		System.out.println("==== rail columns laid, by paster ====");
		// v1 reads exactly double throughout, and that is bestUltraPlan walking the song twice --
		// once without the lookahead and once with -- and keeping the better. The counter sees both
		// walks; only one of them is built. So halve the v1 column before comparing it to v2, and
		// what that comparison says on these songs is that the two lay the same columns per walk.
		System.out.println("   (v1 walks every song twice and keeps one, so its count is doubled)");
		for (String song : SONGS) {
			List<SongBuilder.EventNote> notes = BreachView.song(song);
			// Small chords are what a run is made of, so the share of them is the ceiling on what any
			// of this can ever be worth for a given song. Printed beside the count because a nought
			// under a nought is not a fault.
			long small = notes.stream().collect(java.util.stream.Collectors.groupingBy(
					SongBuilder.EventNote::time, java.util.stream.Collectors.counting()))
				.values().stream().filter(size -> size <= 3).count();
			long chords = notes.stream().map(SongBuilder.EventNote::time).distinct().count();
			for (int[] size : new int[][] {{16, 3}, {24, 3}, {40, 5}}) {
				System.out.println(String.format(
					"   %-28s %2dw x %df   v1 %6d   v2 %6d      (%d of %d chords are 3 or fewer)",
					song, size[0], size[1],
					columns(notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE, size[0], size[1]),
					columns(notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, size[0], size[1]),
					small, chords));
			}
		}
	}
}
