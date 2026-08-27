package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Which half of the game tick a note lands on, as the composer asks it.
 *
 * <p>The one thing worth guarding here is that the composer's answer is the build's answer. A
 * note's game tick is not its own start rounded: it is the running sum of its layer's gaps, each
 * rounded once, which is a different number for anything not already sitting on the grid. Working
 * it out the obvious way agrees almost everywhere, and almost everywhere is what makes it worth a
 * test.</p>
 */
class NoteParityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Notes at a tempo whose repeater tick is 102.4 composer ticks, so nothing is on a grid. */
	private static ComposerProject awkwardSong() {
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		long[] starts = {0L, 137L, 260L, 401L, 555L, 700L, 913L};
		for (int index = 0; index < starts.length; index++) {
			notes.add(new ComposerProject.NoteEvent(index + 1L, 60, starts[index], 1L, 100));
		}
		return new ComposerProject("awkward", 480, 468_750,
			List.of(new ComposerProject.Layer("Test", "HARP", false, true, true, notes)),
			0, 100L, 913L, 4);
	}

	@Test
	void theNoteIdMapCarriesTheSameTicksTheBuildWould() {
		ComposerProject song = awkwardSong();
		Map<Long, Integer> byId = SongBuilder.buildGameTickByNoteId(song, true);
		List<SongBuilder.EventNote> built = SongBuilder.gameTickEventNotes(song, true);

		assertEquals(built.size(), byId.size(),
			"one layer with no repeats: every built note should have an entry");
		// The song is one layer of single notes, so the two lists line up in time order.
		List<Integer> fromMap = new ArrayList<>(byId.values());
		fromMap.sort(null);
		List<Integer> fromBuild = new ArrayList<>(built.stream().map(SongBuilder.EventNote::time)
			.toList());
		fromBuild.sort(null);
		assertEquals(fromBuild, fromMap, "the map and the build disagree about a note's game tick");
	}

	/**
	 * The easy reading agrees, and is written down here so that it is a measurement and not a
	 * hope.
	 *
	 * <p>Rounding each note's own start is a different definition from summing its layer's rounded
	 * gaps, and the difference is why the composer asks the builder rather than doing its own
	 * arithmetic. It is not why the answers are right: over the library the two agree on every one
	 * of 235,500 notes, and they agree on this song too, which is deliberately off both grids.
	 * ParityDivergenceProbe is the library-wide version of this.</p>
	 *
	 * <p>If this ever fails, the two have parted -- and the walked answer is the correct one,
	 * because it is the one the build lays.</p>
	 */
	@Test
	void roundingEachNotesOwnStartHappensToAgree() {
		ComposerProject song = awkwardSong();
		Map<Long, Integer> byId = SongBuilder.buildGameTickByNoteId(song, true);
		Map<Long, Integer> naive = new HashMap<>();
		for (ComposerProject.NoteEvent note : song.layers().get(0).notes()) {
			naive.put(note.id(), song.buildDelayGameTicks(note.startTick()));
		}
		assertEquals(byId, naive);
	}

	/** Every note is on one half or the other, and the two counts are the whole song. */
	@Test
	void everyBuiltNoteIsOnExactlyOneHalf() {
		ComposerProject song = awkwardSong();
		Map<Long, Integer> byId = SongBuilder.buildGameTickByNoteId(song, true);
		int even = 0;
		int odd = 0;
		for (int time : byId.values()) {
			if (Math.floorMod(time, 2) == 0) {
				even++;
			} else {
				odd++;
			}
		}
		assertEquals(byId.size(), even + odd);
		assertTrue(even > 0 && odd > 0,
			"a song off the grid should reach both halves; got " + even + " even, " + odd + " odd");
	}
}
