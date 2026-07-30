package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
	 * <p>Ratchet down, never up. Last measured: 23 of 360 builds, 27 wrong notes, the worst single
	 * build holding two of them.</p>
	 */
	private static final int WORST_FAULTY_BUILDS = 23;

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
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), song.notes(),
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
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
	 * How many columns the floor changes are spread across.
	 *
	 * <p>This is the thing the whole exercise is for. A staircase steps a column off its own centre
	 * line, so where it stands decides whether it lands beside another corridor's notes -- and it
	 * stands wherever the lane before it happened to stop. Pin every lane to end at the wall and
	 * every staircase stands in the same two columns, one at each end, and the question stops
	 * arising.</p>
	 *
	 * <p>Two is the target -- a lane going one way ends at the far wall, and the lane coming back ends
	 * at the near one. The plan reports the block each lane handed over on rather than the test
	 * hunting for a signature: every block a turn is made of is a block something else is made of
	 * too. Ratchet down, never up.</p>
	 *
	 * <p>Standing at 7, and at 2 for every song here without a chord bigger than fourteen. What is
	 * left is one shape: a lane that ends on a long bus has spent most of its wire on it, and if the
	 * event that will not fit is also a single tick away there is neither range to pad the rest of
	 * the way nor a tick to buy a repeater with, so the turn lands a column or two inside the
	 * wall.</p>
	 */
	private static final int WORST_CLIMB_COLUMNS = 7;

	@Test
	void reportsHowScatteredTheFloorChangesAre() {
		int worst = 0;
		String where = "";
		for (Corpus song : corpus()) {
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), song.notes(),
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					java.util.TreeSet<Integer> columns = new java.util.TreeSet<>();
					for (BlockPos turn : plan.turns()) {
						columns.add(turn.getX());
					}
					if (columns.size() > worst) {
						worst = columns.size();
						where = song.name() + " floors=" + floors + " width=" + width + ": "
							+ columns;
					}
				}
			}
		}

		String report = "floor changes are spread across up to " + worst + " columns. Worst: "
			+ where;
		System.out.println(report);
		assertTrue(worst <= WORST_CLIMB_COLUMNS,
			"the floor changes are more scattered than they were. " + report);
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
