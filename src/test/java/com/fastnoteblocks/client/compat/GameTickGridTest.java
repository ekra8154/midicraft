package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The tools that aim a song at the grid two lanes can reach, rather than the one a chain can.
 *
 * <p>Every one of them is the existing tool with the unit halved, so the way to test them is a song
 * that falls exactly between: spaced two and a half repeater ticks, which is five whole game ticks.
 * On the repeater grid that spacing does not exist and something has to give -- the notes move or
 * the tempo does. On the game-tick grid it is already exact, and nothing gives.</p>
 *
 * <p>That gap is the whole feature. If these ever come out equal on this song, the halving has been
 * lost somewhere between the menu and the arithmetic.</p>
 */
class GameTickGridTest {
	/**
	 * Needed only by the routing test, which is the only one here that reaches {@link SongBuilder}
	 * -- whose static state maps instruments to blocks and so wants the registries up.
	 */
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * A song at 480 ppq and 400000 us per quarter, which puts a repeater tick at 120 song ticks
	 * and a game tick at 60. Notes are spaced in song ticks, so a spacing of 300 is 2.5 repeater
	 * ticks and 5 game ticks.
	 */
	/**
	 * Quantizing lands the song on the grid it named, whatever tempo it started at.
	 *
	 * <p>The claim both buttons make: repeater ticks leaves a song a single chain can place, game
	 * ticks leaves one two lanes can, and afterwards the matching snap grid is exact so the notes
	 * sit on the lines. 128 BPM is the case that matters -- its repeater tick is 102.4 composer
	 * ticks, so neither grid is expressible and the tempo has to be nudged to make one. Every tempo
	 * tested before this took the branch where it is already whole, which is why the game-tick
	 * nudge could halve the tempo and land on the repeater grid without anything noticing.</p>
	 */
	@Test
	void quantizingToEitherGridLandsOnThatGridWithoutMovingTheTempoFar() {
		// 128 BPM at 480 PPQ: 468,750 microseconds a quarter, 102.4 composer ticks a repeater tick.
		ComposerProject awkward = awkwardSong();
		double startedAt = 60_000_000.0 / awkward.tempoMicrosPerQuarter();

		for (boolean gameTicks : new boolean[] {false, true}) {
			ComposerProject quantized =
				awkward.withQuantizedToBuildTicks(Set.of(), gameTicks).project();
			String which = gameTicks ? "game" : "repeater";

			// Whole to within a thousandth, not exactly whole. The tempo is an integer number of
			// microseconds and is deliberately rounded up, so the span it produces sits a hair
			// inside the grid rather than a hair outside it -- outside would make every one-tick
			// gap 0.999 of a tick and read as too frequent. The undershoot is about 2e-4 here.
			double span = SongAnalysis.redstoneTickSpan(quantized);
			double unit = gameTicks ? span / 2.0 : span;
			assertTrue(Math.abs(unit - Math.rint(unit)) < 1.0e-3,
				"a " + which + " tick came to " + unit + " composer ticks, which no note can sit on");

			for (long start : starts(quantized)) {
				double units = start / unit;
				assertTrue(Math.abs(units - Math.rint(units)) < 1.0e-3,
					"a note landed at " + start + ", which is " + units + " " + which + " ticks in");
			}

			assertTrue(SongAnalysis.of(quantized, true, gameTicks).buildable(),
				"quantizing to " + which + " ticks should leave a song that build can place");

			double now = 60_000_000.0 / quantized.tempoMicrosPerQuarter();
			assertTrue(Math.abs(now - startedAt) / startedAt < 0.02,
				"the tempo should be nudged, not moved: " + startedAt + " became " + now);
		}
	}

	/** Repeater ticks are the stricter of the two, so nothing is left between them. */
	@Test
	void quantizingToRepeatersLeavesNothingNeedingASecondLane() {
		ComposerProject quantized =
			awkwardSong().withQuantizedToBuildTicks(Set.of(), false).project();

		assertTrue(SongAnalysis.of(quantized, true, false).halfTickedNotes().isEmpty(),
			"a repeater-quantized song has nothing sitting between repeater ticks");
		assertEquals(1, SongAnalysis.of(quantized, true, false).lanesNeeded());
	}

	/** Notes at an awkward tempo, spaced so they cannot already be on either grid. */
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

	private static ComposerProject song(long spacing, int count) {
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			notes.add(new ComposerProject.NoteEvent(index + 1L, 60, index * spacing, 1L, 100));
		}
		ComposerProject.Layer layer =
			new ComposerProject.Layer("Test", "HARP", false, true, true, notes);
		return new ComposerProject("test", 480, 400_000, List.of(layer), 0, count + 1L,
			(count - 1) * spacing, 4);
	}

	@Test
	void measuresAGameTickAsHalfARepeaterTick() {
		ComposerProject song = song(300L, 8);
		assertEquals(120L, song.repeaterGridTicks(), "a repeater tick at this tempo and speed");
		assertEquals(60L, song.buildGridTicks(true), "and a game tick is half of it");
	}

	/**
	 * The quantize that has nothing to do, against the one that has to move every other note.
	 *
	 * <p>Notes 300 song ticks apart are already whole game ticks, so the finer grid leaves the song
	 * alone entirely -- and the coarser one cannot, because 2.5 repeater ticks is not a number of
	 * repeater ticks.</p>
	 */
	@Test
	void leavesASongOnTheGameGridAloneAndMovesItOntoTheRepeaterGrid() {
		ComposerProject song = song(300L, 8);

		ComposerProject onGameTicks = song.withQuantizedToBuildTicks(Set.of(), true).project();
		assertEquals(starts(song), starts(onGameTicks),
			"every note was already on a whole game tick, so none should have moved");

		ComposerProject onRepeaters = song.withQuantizedToBuildTicks(Set.of(), false).project();
		assertTrue(!starts(song).equals(starts(onRepeaters)),
			"2.5 repeater ticks is not a spacing the repeater grid has, so notes had to move");
	}

	/** And the song it leaves alone is one the composer will say needs both lanes. */
	@Test
	void saysASongOnTheGameGridNeedsTwoLanes() {
		SongAnalysis analysis = SongAnalysis.of(song(300L, 8), true);
		assertTrue(analysis.buildable(), "5 game ticks apart is buildable: " + analysis.problems());
		assertEquals(2, analysis.lanesNeeded(), "5 game ticks is an odd number of them");
		assertTrue(!analysis.halfTickedNotes().isEmpty(),
			"and the notes that make it so are nameable, for Select > Half-ticked");
	}

	/**
	 * Snapping the tempo moves it half as far when it is aiming at half the unit.
	 *
	 * <p>The song's spacing is 2.5 repeater ticks. Against repeater ticks that has to become 2 or 3
	 * -- a fifth of the song's speed either way. Against game ticks it is already 5 of them and the
	 * tempo does not move at all.</p>
	 */
	@Test
	void movesTheTempoLessWhenAimingAtGameTicks() {
		ComposerProject song = song(300L, 8);
		int grid = (int)song.noteSpacing().gridTicks();
		int original = song.tempoMicrosPerQuarter();

		int ontoRepeaters = song.alignedTempoFor(grid, false);
		int ontoGameTicks = song.alignedTempoFor(grid, true);

		double repeaterShift = Math.abs(ontoRepeaters - original) / (double)original;
		double gameShift = Math.abs(ontoGameTicks - original) / (double)original;
		assertTrue(gameShift < repeaterShift,
			"aiming at game ticks should move the tempo less, and moved it "
				+ String.format("%.1f%% against %.1f%%", gameShift * 100, repeaterShift * 100));
		assertTrue(gameShift < 0.001,
			"this song is already on the game grid, so its tempo should barely move, and moved "
				+ String.format("%.2f%%", gameShift * 100));
	}

	/**
	 * The whole conversion, both ways, on the song that separates them.
	 *
	 * <p>Quantised to a 1/32 -- 60 song ticks here -- because the musical grid and the machine grid
	 * are different questions and this is testing the second one. A 1/16 would move the notes off
	 * their 2.5-tick spacing before the tempo was ever consulted, and both conversions would come
	 * out identical for a reason that has nothing to do with game ticks.</p>
	 */
	@Test
	void convertsToTheFinerGridWithoutSlowingTheSong() {
		ComposerProject song = song(300L, 8);
		int grid = 60;

		ComposerProject onRepeaters =
			song.convertToMinecraft(grid, true, 0, false).project();
		ComposerProject onGameTicks =
			song.convertToMinecraft(grid, true, 0, true).project();

		assertTrue(SongAnalysis.of(onGameTicks, true).buildable(),
			"the game-tick conversion has to produce a buildable song");
		assertTrue(SongAnalysis.of(onRepeaters, true).buildable(),
			"and so does the repeater one");
		assertEquals(2, SongAnalysis.of(onGameTicks, true).lanesNeeded(),
			"the finer conversion keeps the half-tick spacing, which needs two lanes");
		assertEquals(1, SongAnalysis.of(onRepeaters, true).lanesNeeded(),
			"and the coarser one flattens it onto whole repeater ticks");

		// The point of the whole thing, stated as the number a listener would hear: how far the song's
		// own speed had to move to become buildable.
		double keptSpeed = onGameTicks.tempoMicrosPerQuarter() / (double)song.tempoMicrosPerQuarter();
		double lostSpeed = onRepeaters.tempoMicrosPerQuarter() / (double)song.tempoMicrosPerQuarter();
		assertEquals(1.0, keptSpeed, 1.0e-9,
			"a song already on the game grid should convert at its own tempo");
		assertTrue(Math.abs(lostSpeed - 1.0) > 0.1,
			"and the repeater conversion has to move it, which it did by "
				+ String.format("%.0f%%", Math.abs(lostSpeed - 1.0) * 100));
	}

	/**
	 * Every layout timed in game ticks is built from the composition, and every other one is not.
	 *
	 * <p>The bug this exists for cost a whole layout and was invisible to every check in the file.
	 * The paste and the forecast each asked "is this mode the half-tick lane?" by name, so when a
	 * second game-tick layout arrived it took the sequence instead -- and a sequence delay is
	 * repeater ticks. The build then halved times that were already halved, playing at double
	 * speed, and split the song by the parity of a repeater-tick index, which means nothing.
	 * Everything downstream was correct; it was reading the wrong song.</p>
	 *
	 * <p>So the assertion is over the modes rather than over one of them: whatever is added next
	 * gets asked the same question, and answering it wrongly fails here rather than in the world.</p>
	 */
	@Test
	void buildsEveryGameTickLayoutFromTheCompositionAndTheRestFromTheSequence() {
		ComposerProject song = song(300L, 8);
		List<FastNoteblocksConfig.SequenceTrack> sequence = song.toSequenceTracks(Set.of(), true);
		List<SongBuilder.EventNote> fromComposition = SongBuilder.gameTickEventNotes(song, true);
		List<SongBuilder.EventNote> fromSequence = SongBuilder.eventNotes(sequence);
		// The song exists to tell the two apart: spaced 2.5 repeater ticks, so the sequence has to
		// round it and the composition does not. If these ever match, the test proves nothing.
		assertTrue(!times(fromComposition).equals(times(fromSequence)),
			"the fixture must distinguish the two projections, and did not");

		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			List<SongBuilder.EventNote> used = SongBuilder.notesFor(mode, sequence, song, true);
			assertEquals(mode.gameTicks() ? times(fromComposition) : times(fromSequence),
				times(used), mode + " was planned from the wrong source");
		}
	}

	/** And the two half-tick layouts are the ones that say so. */
	@Test
	void countsInGameTicksOnEveryHalfTickLayout() {
		assertTrue(SongBuilder.PasteMode.HALF_TICK_LANE.gameTicks(), "the straight pair");
		assertTrue(SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE.gameTicks(), "the folded pair");
		assertTrue(!SongBuilder.PasteMode.ULTRA_COMPACT_LANE.gameTicks(),
			"and the single-chain layouts do not");
		assertTrue(!SongBuilder.PasteMode.LANE.gameTicks(), "including the straight one");
	}

	private static List<Integer> times(List<SongBuilder.EventNote> notes) {
		return notes.stream().map(SongBuilder.EventNote::time).toList();
	}

	private static List<Long> starts(ComposerProject project) {
		return project.layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(ComposerProject.NoteEvent::startTick)
			.sorted()
			.toList();
	}
}
