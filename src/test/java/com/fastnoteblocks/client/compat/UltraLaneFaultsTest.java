package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * How many ultra compact lane builds come out with a note in the wrong bar.
 *
 * <p>The mode is finished on one floor and not on several: the staircase that changes floors steps a
 * column off its own centre line, and where it lands is decided by where a lane happened to stop, so
 * on a folded build it can land beside notes belonging to a corridor half a song away. A single test
 * over a single song cannot see that -- the sample song in
 * {@link NoteMachineReaderTest} passed the whole time the count below was in the hundreds -- so this
 * sweeps a corpus wide enough for the shape of the problem to show, and reports a number.</p>
 *
 * <p>The number is asserted rather than merely printed, and the ceiling is meant to be ratcheted
 * <em>down</em> as the staircases are brought into line. It is not a target: a build with any faults
 * at all is a build with a wrong note in it. It is a fence against the thing that kept happening
 * while this mode was being written, which is a change that looked like an improvement, passed every
 * test, and quietly made the machines worse.</p>
 */
class UltraLaneFaultsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Builds with at least one wrong note, over the generated corpus at every width and floor count.
	 *
	 * <p>Ratchet down, never up, and now at nought: no build in the corpus has a wrong note in it,
	 * and neither does any build of any song in the library, at any width or floor count. It stays
	 * asserted because that is what it is for -- this mode was written through a long run of changes
	 * that each looked like an improvement and several of which quietly made the machines worse.</p>
	 */
	private static final int WORST_FAULTY_BUILDS = 0;

	@Test
	void reportsHowManyBuildsHaveAWrongNoteInThem() {
		int builds = 0;
		int faulty = 0;
		int wrongNotes = 0;
		StringBuilder worst = new StringBuilder();
		int worstCount = 0;
		for (Corpus song : corpus()) {
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					builds++;
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song.notes(),
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refusedForItsTurns) {
						// Refused rather than built crooked; the other test counts those.
						continue;
					}
					if (plan.faults().isEmpty()) {
						continue;
					}
					faulty++;
					wrongNotes += plan.faults().size();
					if (plan.faults().size() > worstCount) {
						worstCount = plan.faults().size();
						worst.setLength(0);
						worst.append(song.name()).append(" floors=").append(floors)
							.append(" width=").append(width).append(": ")
							.append(plan.faults().size()).append(" wrong, first is ")
							.append(plan.faults().get(0));
					}
				}
			}
		}

		String report = faulty + " of " + builds + " builds have a wrong note in them, "
			+ wrongNotes + " notes in all. Worst: " + worst;
		System.out.println(report);
		assertTrue(faulty <= WORST_FAULTY_BUILDS,
			"more builds are wrong than they were. " + report);
	}

	/**
	 * Turns not standing in one of the two columns most of them stand in, over the whole corpus.
	 *
	 * <p>The sharper of the two numbers, and the one that only goes down when a turn that used to be
	 * misplaced stops being. It is asserted at nought now and no longer ratcheted: the rule has no
	 * exceptions left in it, so a turn off the wall is not a number to bring down but a bug.</p>
	 */
	private static final int WORST_TURNS_OFF_THE_WALL = 0;

	/**
	 * Builds refused because no arrangement of their lanes lands every one of them on a wall.
	 *
	 * <p>This is what keeping the rule costs, and the number that replaces the one above as the
	 * thing to bring down. A build is refused when a chord too big to cut across a turn arrives
	 * where the lane behind it cannot be filled either -- no wire left for a pad, and no spare tick
	 * to buy a repeater with. Every one is a song someone cannot paste at that width, so: ratchet
	 * down, never up.</p>
	 */
	private static final int WORST_REFUSED = 18;

	@Test
	void everyFloorChangeStandsOnAWall() {
		int offTheWall = 0;
		int refused = 0;
		String where = "";
		for (Corpus song : corpus()) {
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song.notes(),
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException wouldNotKeepTheRule) {
						refused++;
						continue;
					}
					Map<Integer, Long> byColumn = plan.turns().stream().map(BlockPos::getX)
						.collect(Collectors.groupingBy(x -> x, Collectors.counting()));
					int stray = byColumn.entrySet().stream()
						.sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
						.skip(2)
						.mapToInt(wall -> wall.getValue().intValue())
						.sum();
					offTheWall += stray;
					if (stray > 0 && where.isEmpty()) {
						where = song.name() + " floors=" + floors + " width=" + width + ": "
							+ new java.util.TreeSet<>(byColumn.keySet());
					}
				}
			}
		}

		String report = offTheWall + " floor changes do not stand on a wall, and " + refused
			+ " builds were refused for keeping the rule. " + where;
		System.out.println(report);
		assertEquals(WORST_TURNS_OFF_THE_WALL, offTheWall,
			"a turn stood somewhere other than a wall. " + report);
		assertTrue(refused <= WORST_REFUSED, "more builds are refused than were. " + report);
	}

	/** One generated song and what it is meant to stress. */
	private record Corpus(String name, List<SongBuilder.EventNote> notes) {
	}

	/**
	 * Songs chosen for the shapes that decide how a lane ends.
	 *
	 * <p>A lane stops when the event about to be placed will not fit before the wall, so what matters
	 * is how long the events are and how unevenly: a song of nothing but small chords ends its lanes
	 * flush and a song with a chord of thirty in it can end one sixteen blocks short. The instrument
	 * mixes are here because they decide whether a chord of four to seven can be stacked at all, and
	 * a stacked module is two long where its bus would have been four.</p>
	 */
	private static List<Corpus> corpus() {
		List<Corpus> songs = new ArrayList<>();
		songs.add(new Corpus("small chords only", generate(11L, 1, 3, 1, 9, MIXED)));
		songs.add(new Corpus("all stackable", generate(22L, 4, 7, 1, 9, MIXED)));
		songs.add(new Corpus("stackable, tightly packed", generate(33L, 4, 7, 1, 2, MIXED)));
		songs.add(new Corpus("mixed with long buses", generate(44L, 1, 14, 1, 9, MIXED)));
		songs.add(new Corpus("the odd enormous chord", generate(55L, 1, 30, 1, 9, MIXED)));
		songs.add(new Corpus("nothing that conducts", generate(66L, 4, 7, 1, 9, AWKWARD)));
		return songs;
	}

	/** Conducting and not, gravity and not, harp and not. */
	private static final String[] MIXED = {"minecraft:air", "minecraft:stone",
		"minecraft:oak_planks", "minecraft:gold_block", "minecraft:sand"};

	/** Almost nothing here can be a relay, so most chords fall back to a bus. */
	private static final String[] AWKWARD = {"minecraft:glass", "minecraft:glowstone",
		"minecraft:soul_sand", "minecraft:air"};

	private static List<SongBuilder.EventNote> generate(long seed, int minChord, int maxChord,
			int minGap, int maxGap, String[] instruments) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 120; event++) {
			time += minGap + random.nextInt(maxGap - minGap + 1);
			int chord = minChord + random.nextInt(maxChord - minChord + 1);
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}
}
