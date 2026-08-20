package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Nothing but chords of twenty-five, a repeater tick apart. The bar the next builder has to clear.
 *
 * <p>The target, and it is the hardest chord in the range rather than a gentle one. A cut costs
 * a transition cell, the two halves of the tail, and the staircase, and it has to come in under the
 * fifteen a repeater carries:</p>
 *
 * <pre>
 *   plain cut, descent:  cells + 4 &lt;= 15  -&gt;  11 cells  -&gt;  22 notes
 *   plain cut, climb:    cells + 3 &lt;= 15  -&gt;  12 cells  -&gt;  24 notes
 *   twenty-five        =  13 cells        -&gt;  17 and 16, over both ways
 * </pre>
 *
 * <p>So <b>every cut in this song has to be headed</b>. Seven notes go in the head and the
 * remaining eighteen make nine cells, {@code 1 + 9 + 4 = 14}, one cell clear -- which is one parity
 * pad's worth, and two if the head sheds its flank onto the staircase. That is the whole margin
 * this song has, and it is why it is the right thing to build against: it puts no weight at all on
 * the pad machinery and all of it on the head.</p>
 *
 * <p>The pass mark is all three of no breach, no wrong note, and <b>no dead line</b> -- the last
 * read off the blocks with {@link NoteMachineReader}, because {@code plan.breaches()} reads nought
 * over a machine whose wire has been severed and has done exactly that here before.</p>
 */
@Tag("sweep")
class AllTwentyFivesTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Notes per chord. The largest that cannot be cut plain in either direction. */
	private static final int CHORD = 25;
	/**
	 * Ticks between chords, and the whole probe turned on getting this right.
	 *
	 * <p>One, not two. {@code time} is counted in redstone ticks: the sweep takes {@code wait - 1} as
	 * the cells of dust it has to cross to reach the next chord, so a gap of one is two chords with
	 * nothing at all between them and a gap of two has a cell to put a repeater in. Built at two, this
	 * song passed at every width and floor count and so did five harder-looking variations of it --
	 * chords from one note to twenty-five, gaps out to a bar -- which was not the builder being good.
	 * It was every chord getting its own repeater and so its own fresh fifteen, which is the premise
	 * the whole cut-anywhere ceiling rests on. Guardian's shortest gap is one.</p>
	 */
	private static final int GAP = 1;
	private static final int EVENTS = 120;

	/**
	 * Five instruments of five notes, which is what a chord of this size is actually made of.
	 *
	 * <p>Not all harp. The all-harp debug builder invented a dead line that did not exist and cost an
	 * afternoon, because harp is the one instrument whose block is air -- a song of nothing but harp
	 * never asks the build to fit an instrument block anywhere, and fitting instrument blocks is half
	 * of what makes a head refuse. Layers in the library group by instrument, so these do too.</p>
	 */
	private static final String[] INSTRUMENTS = {"minecraft:air", "minecraft:gold_block",
		"minecraft:stone", "minecraft:oak_planks", "minecraft:packed_ice"};

	static List<SongBuilder.EventNote> allTwentyFives() {
		return song(seed -> CHORD, seed -> GAP);
	}

	/**
	 * @param sized notes in the chord at this event, and {@code waited} the ticks before it. Taken as
	 *     functions of the event index rather than baked in, because the point of this probe turned
	 *     out to be which of the two a build is sensitive to.
	 */
	private static List<SongBuilder.EventNote> song(java.util.function.IntUnaryOperator sized,
			java.util.function.IntUnaryOperator waited) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		int time = 0;
		for (int event = 0; event < EVENTS; event++) {
			time += waited.applyAsInt(event);
			int chord = sized.applyAsInt(event);
			for (int index = 0; index < chord; index++) {
				int layer = index * INSTRUMENTS.length / Math.max(1, chord);
				// Pitches walk rather than repeat, so two notes of one instrument never collapse into
				// the same block and hide a slot that was never really wanted.
				int pitch = (index * 7 + event * 3) % 25;
				notes.add(new SongBuilder.EventNote(time, layer + 1, index, pitch,
					INSTRUMENTS[layer]));
			}
		}
		return List.copyOf(notes);
	}

	/**
	 * The flat song, and then the two things a real one has that it does not, one axis at a time.
	 *
	 * <p>The flat song passes everywhere and always did, which is what makes it useless as a target
	 * and useful as a control: a chord of twenty-five is headed and cut wherever it lands, and the
	 * head is not refused once in sixteen builds. So whatever Guardian is doing is not about the size
	 * of its chords -- it tops out at twenty-four -- and the two candidates left are that the chords
	 * differ in size from one another and that the gaps between them differ in length.</p>
	 */
	@Test
	void buildsTwentyFivesWithoutBreachOrDeadLine() {
		java.util.Random sizes = new java.util.Random(7L);
		java.util.Random gaps = new java.util.Random(7L);
		int[] mixedSize = new int[EVENTS];
		int[] mixedGap = new int[EVENTS];
		for (int event = 0; event < EVENTS; event++) {
			mixedSize[event] = 5 + sizes.nextInt(CHORD - 4);
			mixedGap[event] = GAP + gaps.nextInt(7);
		}
		table("flat: every chord " + CHORD + ", every gap " + GAP,
			song(event -> CHORD, event -> GAP));
		table("mixed sizes 5.." + CHORD + ", every gap " + GAP,
			song(event -> mixedSize[event], event -> GAP));
		table("every chord " + CHORD + ", mixed gaps " + GAP + ".." + (GAP + 6),
			song(event -> CHORD, event -> mixedGap[event]));
		table("mixed sizes and mixed gaps",
			song(event -> mixedSize[event], event -> mixedGap[event]));

		// The two things the mixes above still do not have. Chords start at five there, and the library
		// keeps 22% of its placements in chords smaller than that -- a chord of one or two is the one
		// that can land flush on a wall and leave nothing worth cutting. And a gap of eight ticks is
		// not a rest; a real song stops for a bar.
		java.util.Random tinySizes = new java.util.Random(13L);
		java.util.Random rests = new java.util.Random(13L);
		int[] tiny = new int[EVENTS];
		int[] rest = new int[EVENTS];
		for (int event = 0; event < EVENTS; event++) {
			tiny[event] = 1 + tinySizes.nextInt(CHORD);
			rest[event] = GAP + (rests.nextInt(6) == 0 ? rests.nextInt(40) : rests.nextInt(7));
		}
		table("tiny chords too, 1.." + CHORD + ", every gap " + GAP,
			song(event -> tiny[event], event -> GAP));
		table("tiny chords and real rests", song(event -> tiny[event], event -> rest[event]));
	}

	/**
	 * The target song under the old builder and the new one, side by side.
	 *
	 * <p>v2 is the same walk with the booking layer switched off and one rule changed: a cut is
	 * offered on the chord that <em>reaches</em> the wall rather than the one that overshoots it, so
	 * the near half is never empty and a lane never has to fund its own turn. Everything that knows
	 * about blocks -- the raised pad into a climb, the four-cell descent, the head-only cut, the shed,
	 * relocation, parity -- is the same code in both arms, which is the point of doing it this way
	 * rather than beside it.</p>
	 */
	@Test
	void weighsCutOnlyLanesAgainstThePlanner() {
		List<SongBuilder.EventNote> song = allTwentyFives();
		try {
			for (int arm = 0; arm < 2; arm++) {
				SongBuilder.CUT_ONLY_LANES = arm == 1;
				SongBuilder.CUTS_THE_CHORD_THAT_REACHES = arm == 1;
				table((arm == 0 ? "v1, the planner" : "v2, cuts only") + " -- chords of " + CHORD
					+ " at a gap of " + GAP, song);
			}
		} finally {
			SongBuilder.CUT_ONLY_LANES = false;
			SongBuilder.CUTS_THE_CHORD_THAT_REACHES = false;
		}
	}

	/** The same two arms on the song this is all for. */
	@Test
	void weighsCutOnlyLanesOnGuardian() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== Guardian, every size ====");
		try {
			// Four arms, because the two changes are independent and lumping them cost the first read
			// of this table its meaning: dropping the planner and cutting a column earlier are separate
			// claims, and one of them may be carrying the other.
			String[] named = {"v1: planner, cut on overshoot", "planner off only          ",
				"earlier cut only          ", "v2: both                  "};
			for (int arm = 0; arm < 4; arm++) {
				SongBuilder.CUT_ONLY_LANES = (arm & 1) != 0;
				SongBuilder.CUTS_THE_CHORD_THAT_REACHES = (arm & 2) != 0;
				int lanes = 0;
				int blocks = 0;
				int wrong = 0;
				int dirty = 0;
				long length = 0;

				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 16; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
							guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
						lanes += plan.breaches().size();
						blocks += sum;
						wrong += plan.wrongNotes();
						length += plan.width();
						if (sum > 0) {
							dirty++;
						}
					}
				}
				System.out.println("   " + named[arm]
					+ "   lanes=" + lanes + " blocks=" + blocks + " wrong=" + wrong
					+ " dirtyConfigs=" + dirty + " length=" + length);
				// The live size, and the machine rather than the plan.
				SongBuilder.PastePlan mine = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 40, 5));
				System.out.println("      40w x 5f (live)  breaches=" + mine.breaches()
					+ " unreached=" + BreachView.readBack("Guardian", mine).unreachedNotes());
			}
		} finally {
			SongBuilder.CUT_ONLY_LANES = false;
			SongBuilder.CUTS_THE_CHORD_THAT_REACHES = false;
		}
	}

	/**
	 * What Guardian is made of, against what the synthetic songs above are made of.
	 *
	 * <p>Asked because none of them breach. Six shapes and ninety-six builds, chords from one note to
	 * twenty-five, gaps from two ticks to a bar, and not one column outside a wall -- while Guardian
	 * at the same widths breaches. Whatever the difference is, it is in the note data, and this prints
	 * both so it can be read rather than guessed at a seventh time.</p>
	 */
	@Test
	void comparesGuardianToTheSyntheticSongs() throws Exception {
		describe("guardian", BreachView.song("deltarune-ch-4-guardian"));
		describe("synthetic flat 25", allTwentyFives());
	}

	private static void describe(String name, List<SongBuilder.EventNote> notes) {
		java.util.Map<Integer, java.util.List<SongBuilder.EventNote>> byTime =
			new java.util.TreeMap<>();
		for (SongBuilder.EventNote note : notes) {
			byTime.computeIfAbsent(note.time(), when -> new ArrayList<>()).add(note);
		}
		int[] buckets = new int[7];
		int biggest = 0;
		int overTwentyFive = 0;
		java.util.Map<String, Integer> instruments = new java.util.LinkedHashMap<>();
		int duplicates = 0;
		for (java.util.List<SongBuilder.EventNote> chord : byTime.values()) {
			int size = chord.size();
			biggest = Math.max(biggest, size);
			if (size > CHORD) {
				overTwentyFive++;
			}
			buckets[Math.min(6, (size - 1) / 5)]++;
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (SongBuilder.EventNote note : chord) {
				instruments.merge(note.instrumentBlock(), 1, Integer::sum);
				if (!seen.add(note.instrumentBlock() + "/" + note.pitch())) {
					duplicates++;
				}
			}
		}
		java.util.List<Integer> gaps = new ArrayList<>(byTime.keySet());
		int shortest = Integer.MAX_VALUE;
		for (int index = 1; index < gaps.size(); index++) {
			shortest = Math.min(shortest, gaps.get(index) - gaps.get(index - 1));
		}
		System.out.println();
		System.out.println("==== " + name + " ====");
		System.out.println("   events=" + byTime.size() + " notes=" + notes.size()
			+ " biggestChord=" + biggest + " over" + CHORD + "=" + overTwentyFive
			+ " shortestGap=" + (gaps.size() > 1 ? shortest : 0)
			+ " sameInstrumentAndPitchTwice=" + duplicates);
		StringBuilder sizes = new StringBuilder("   chord sizes ");
		for (int bucket = 0; bucket < buckets.length; bucket++) {
			sizes.append(bucket * 5 + 1).append("-").append(bucket == 6 ? "+" : (bucket + 1) * 5)
				.append("=").append(buckets[bucket]).append("  ");
		}
		System.out.println(sizes.toString());
		System.out.println("   instruments=" + instruments.size() + " " + instruments);
	}

	private static void table(String heading, List<SongBuilder.EventNote> song) {
		System.out.println();
		System.out.println("==== " + EVENTS + " chords -- " + heading + " ====");
		System.out.println("   width x floors   breaches            wrong  unreached  length  volume");
		int dirty = 0;
		for (int floors = 2; floors <= 5; floors++) {
			// Narrow lanes are left out on purpose. A headed twenty-five is two columns of head and ten
			// of bus, so a corridor under about fourteen cannot hold one however well it is planned, and
			// a failure there says nothing about the builder.
			for (int width : new int[] {20, 24, 32, 40}) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, width, floors));
				int blocks = plan.breaches().stream().mapToInt(Integer::intValue).sum();
				int unreached = BreachView.readBack("AllTwentyFives", plan).unreachedNotes();
				if (blocks > 0 || unreached > 0 || plan.wrongNotes() > 0) {
					dirty++;
				}
				System.out.println(String.format("   %2dw x %df      %3d in %2d lanes   %5d  %9d  %6d  %7d",
					width, floors, blocks, plan.breaches().size(), plan.wrongNotes(), unreached,
					plan.width(), plan.commands().size()));
			}
		}
		System.out.println("   dirty configs: " + dirty + " of 16");
	}
}
