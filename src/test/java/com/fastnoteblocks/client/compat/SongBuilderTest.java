package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the ultra compact lane decides, module by module.
 *
 * <p>Every one of these is really a question about whether a chord of four to seven can be stacked
 * around a single repeater, and the answer is visible in the build: a stacked module contains
 * exactly one piece of dust shaped as a cross, and nothing else in any layout contains one at all.
 * Counting crosses therefore counts stacked modules, which is what lets the eligibility rules be
 * asserted directly rather than inferred from a footprint.</p>
 *
 * <p>That every one of these builds at all is itself an assertion: a plan that put two chords'
 * notes in one block, or left a note block beside something powered on another tick, is refused by
 * {@code PlacementPlan.verify} before it ever returns.</p>
 */
class SongBuilderTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Wide and flat, so a short song runs in one straight lane and no chord lands in a corner. */
	private static final SongBuilder.BuildLimits STRAIGHT = new SongBuilder.BuildLimits(4, 200, 1);

	private static final String HARP = "minecraft:air";
	private static final String BASS_DRUM = "minecraft:stone";
	private static final String SNARE = "minecraft:sand";
	private static final String HAT = "minecraft:glass";

	@Test
	void packsAChordOfFourAroundOneRepeaterRatherThanAlongABus() {
		List<SongBuilder.EventNote> song = song(5, chords(6, 4, BASS_DRUM));

		assertEquals(6, stackedModules(build(song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"every chord of four should have stacked");
		assertEquals(0, stackedModules(build(song, SongBuilder.PasteMode.COMPACT_LANE)),
			"the older lane should be untouched by any of this");
	}

	/**
	 * A chord of four fills only the two low slots ahead of its own repeater. The next module's
	 * relays do reach those blocks, but they reach them a repeater <em>later</em>, by which time
	 * the note is already powered and there is no second edge for it to sound on. So four after
	 * four after four is fine, and only a chord that has to reach backwards has to ask.
	 */
	@Test
	void chordsOfFourStackOneAfterAnotherWithNothingBetweenThem() {
		assertEquals(6, stackedModules(build(song(1, chords(6, 4, BASS_DRUM)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"every four should stack, back to back");
	}

	/**
	 * Six needs the two low slots behind it, and those are the same two blocks the module before it
	 * already hung notes on. Not a timing question but an occupancy one: they are taken.
	 */
	@Test
	void aChordOfSixCannotHaveTheSlotsTheModuleBeforeItIsUsing() {
		assertEquals(3, stackedModules(build(song(1, chords(6, 6, BASS_DRUM)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"back to back sixes should take turns");
	}

	/**
	 * Five fits ahead of the repeater only when one of its notes can ride the centre, and only a
	 * harp can -- so the same chord of five is or is not free of the module before it depending on
	 * whether there is a harp in it.
	 */
	@Test
	void aHarpKeepsAChordOfFiveOutOfTheSlotsBehindIt() {
		List<String> withHarp = new ArrayList<>(List.of(BASS_DRUM, BASS_DRUM, BASS_DRUM, BASS_DRUM));
		withHarp.add(HARP);

		assertEquals(6, stackedModules(build(song(1, repeat(6, withHarp)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"a five with a harp never reaches behind itself");
		assertEquals(3, stackedModules(build(song(1, chords(6, 5, BASS_DRUM)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"a five without one does, so back to back fives take turns");
	}

	@Test
	void oneMoreRepeaterOfSilenceIsEnoughRoomForEveryModule() {
		assertEquals(6, stackedModules(build(song(5, chords(6, 6, BASS_DRUM)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"two repeaters apart, no module reaches into another");
	}

	/**
	 * A bus between two stacked modules is room enough as well: it ends a level up and several
	 * blocks along, so nothing it powers is anywhere near the next module's slots.
	 */
	@Test
	void aChordTooBigToStackLeavesTheNextOneRoom() {
		List<List<String>> chords = new ArrayList<>();
		for (int index = 0; index < 6; index++) {
			chords.add(java.util.Collections.nCopies(index % 2 == 0 ? 6 : 12, BASS_DRUM));
		}

		assertEquals(3, stackedModules(build(song(1, chords),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)));
	}

	@Test
	void sevenNotesFitOnlyWhenOneOfThemIsHarp() {
		List<String> withHarp = new ArrayList<>(List.of(BASS_DRUM, BASS_DRUM, BASS_DRUM, BASS_DRUM,
			BASS_DRUM, BASS_DRUM));
		withHarp.add(HARP);

		assertEquals(1, stackedModules(build(song(5, repeat(1, withHarp)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)));
		assertEquals(0, stackedModules(build(song(5, chords(1, 7, BASS_DRUM)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"seven with nothing to put in the centre is a bus");
	}

	/**
	 * Sand needs a block under it, and the four lower slots sit low enough that the block would
	 * land on the head of a note one floor down and silence it. So sand can only be one of the two
	 * relays, and there are two of those.
	 */
	@Test
	void athirdSnareIsOneMoreThanTheTwoSlotsThatCanHoldOne() {
		assertEquals(1, stackedModules(build(song(5, List.of(
				List.of(SNARE, SNARE, BASS_DRUM, BASS_DRUM, BASS_DRUM, BASS_DRUM))),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"two snares fit, in the two slots that can prop them");
		assertEquals(0, stackedModules(build(song(5, List.of(
				List.of(SNARE, SNARE, SNARE, BASS_DRUM, BASS_DRUM, BASS_DRUM))),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"a third snare has nowhere to go");
	}

	/**
	 * The two relays have to conduct, which glass does not, so a chord whose only solid instruments
	 * are its harps has to spend both of them there -- and then has none left for the centre.
	 */
	@Test
	void bothHarpsGoToTheRelaysWhenNothingElseInTheChordConducts() {
		assertEquals(1, stackedModules(build(song(5, List.of(
				List.of(HARP, HARP, HAT, HAT, HAT, HAT))),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"six fits: both harps relay, and the centre goes unused");
		assertEquals(0, stackedModules(build(song(5, List.of(
				List.of(HARP, HARP, HAT, HAT, HAT, HAT, HAT))),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE)),
			"seven would need a third solid instrument to spare for the centre");
	}

	/**
	 * Dust with nothing to join takes the dot shape, and a dot powers only the block beneath it.
	 * This dust is walled in on all four sides, so the shape has to be named or the module is
	 * silent -- and silent is not something any later check here would notice.
	 */
	@Test
	void theCentreDustIsPlacedAsACrossAndNotLeftToTheGame() {
		SongBuilder.PastePlan plan = build(song(5, chords(1, 4, BASS_DRUM)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE);

		assertEquals(1, plan.commands().stream().filter(command -> command.contains(CROSS)).count());
		assertTrue(plan.commands().stream()
				.noneMatch(command -> command.contains(" minecraft:redstone_wire ")),
			"a stacked module has no dust in it whose shape was left unstated");
	}

	@Test
	void stacksIntoLessRoomThanTheLaneItIsBuiltFrom() {
		List<SongBuilder.EventNote> song = song(5, chords(60, 6, BASS_DRUM));
		SongBuilder.BuildLimits folded = new SongBuilder.BuildLimits(4, 24, 3);

		SongBuilder.PastePlan lane = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.COMPACT_LANE, folded);
		SongBuilder.PastePlan ultra = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, folded);

		assertTrue(ultra.width() * ultra.depth() * ultra.height()
				< lane.width() * lane.depth() * lane.height(),
			"ultra " + describe(ultra) + " should take less room than lane " + describe(lane));
	}

	/**
	 * Nothing a quiet lane powers leaves its centre line -- the block a small chord hangs off is
	 * the note block itself, and note blocks do not pass power on. So the notes of two such lanes
	 * may sit right against each other, and the empty column between them can go.
	 */
	@Test
	void lanesOfSmallChordsSitOneBlockCloserThanTheyUsedTo() {
		List<SongBuilder.EventNote> song = song(2, chords(120, 3, BASS_DRUM));
		SongBuilder.BuildLimits folded = new SongBuilder.BuildLimits(4, 24, 1);

		SongBuilder.PastePlan lane = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.COMPACT_LANE, folded);
		SongBuilder.PastePlan ultra = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, folded);

		assertTrue(ultra.width() < lane.width(),
			"ultra " + describe(ultra) + " should be narrower across the lanes than " + describe(lane));
	}

	// ------------------------------------------------------------------------------- fixtures

	private static final String CROSS =
		"minecraft:redstone_wire[north=side,east=side,south=side,west=side]";

	/** Stacked modules, counted by the one piece of dust no other layout ever places. */
	private static int stackedModules(SongBuilder.PastePlan plan) {
		return (int)plan.commands().stream().filter(command -> command.contains(CROSS)).count();
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> song,
			SongBuilder.PasteMode mode) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song, mode, STRAIGHT);
	}

	private static String describe(SongBuilder.PastePlan plan) {
		return plan.width() + "x" + plan.depth() + "x" + plan.height();
	}

	private static List<List<String>> chords(int count, int size, String instrument) {
		return repeat(count, java.util.Collections.nCopies(size, instrument));
	}

	private static List<List<String>> repeat(int count, List<String> chord) {
		List<List<String>> chords = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			chords.add(chord);
		}
		return chords;
	}

	/** One chord every {@code gap} ticks, each note of a chord on the same tick. */
	private static List<SongBuilder.EventNote> song(int gap, List<List<String>> chords) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		int time = 0;
		for (List<String> chord : chords) {
			time += gap;
			for (int index = 0; index < chord.size(); index++) {
				notes.add(new SongBuilder.EventNote(time, 1, index, 12, chord.get(index)));
			}
		}
		return notes;
	}

}
