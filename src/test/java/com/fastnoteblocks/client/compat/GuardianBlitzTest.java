package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guardian's remaining breaches, one page each, in the order they are worth looking at.
 *
 * <p>ekran's reading order, which is the point of this class: look at the blocks, work out how the
 * same notes could have been laid inside the rules without going outside, then ask why the walk did
 * not do that. A total cannot be read that way and neither can a fault string; the corridor can.</p>
 *
 * <p>Widest first, because a wide corridor that breaches is a bug and a narrow one may simply be too
 * small for the chord. 12 wide is skipped: it produces a build identical to 16 in every breach, so
 * something clamps the corridor to a floor of sixteen and the 12w rows are the same machine twice.</p>
 */
@Tag("sweep")
class GuardianBlitzTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Every size that still breaches, worst-first within each width, widest width first. */
	private static final int[][] SIZES = {
		{40, 4}, {32, 5}, {24, 4}, {24, 3}, {24, 2},
		{20, 6}, {20, 5}, {20, 3}, {20, 2},
		{16, 3}, {16, 6}, {16, 5}, {16, 4}, {16, 2},
	};

	/**
	 * Which of these breaches the prepad was holding shut, and which it never touched.
	 *
	 * <p>Worth knowing before any of them is diagnosed. A breach the prepad used to cover is a breach
	 * whose real answer is the better prepad ekran wants, and reasoning about it as though it were a
	 * fresh bug is reasoning about the wrong thing. A breach present either way is the walk's own.</p>
	 */
	@Test
	void saysWhichBreachesThePrepadWasCovering() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== Guardian, prepad off against on, the sizes that breach ====");
		for (int[] size : SIZES) {
			String line = "   " + size[0] + "w x " + size[1] + "f  ";
			for (boolean prepad : new boolean[] {false, true}) {
				SongBuilder.PREPADS_FOR_THE_OFF_BUS_DISCOUNT = prepad;
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					new net.minecraft.core.BlockPos(0, 64, 0), guardian,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, size[0], size[1]));
				line += (prepad ? "   on " : "  off ") + plan.breaches().stream()
					.mapToInt(Integer::intValue).sum() + " blocks in " + plan.breaches().size()
					+ " lanes";
			}
			SongBuilder.PREPADS_FOR_THE_OFF_BUS_DISCOUNT = false;
			System.out.println(line);
		}
	}

	/**
	 * The cut that is refused because the chord fits, measured again now the prepad is gone.
	 *
	 * <p>{@link SongBuilder#CUTS_A_CHORD_THAT_FITS} is the whole of the blitz in one flag: a chord of
	 * 24 is twelve cells, a plain cut of it wants sixteen of a possible fifteen, so its only cut is a
	 * headed one -- and {@code stackedSplitOf} refuses the headed cut whenever the chord happens to
	 * fit before the wall without it. It was measured off against the old build and lost, 3 breaches
	 * to 5 on Guardian 44x3, for a reason the flag states: the near half ends wherever the chord ended
	 * rather than on the wall. Both halves of that measurement have moved since, so it is worth the
	 * two minutes to ask again.</p>
	 */
	@Test
	void weighsCuttingAChordThatFits() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== Guardian, cutting a chord that fits ====");
		for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			int lanes = 0;
			int blocks = 0;
			int worst = 0;
			int wrong = 0;
			int dirty = 0;
			long length = 0;
			StringBuilder moved = new StringBuilder();
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 16; width <= 48; width += 4) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new net.minecraft.core.BlockPos(0, 64, 0), guardian,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
					lanes += plan.breaches().size();
					blocks += sum;
					worst = Math.max(worst, plan.worstBreach());
					wrong += plan.wrongNotes();
					length += plan.width();
					if (sum > 0) {
						dirty++;
						moved.append("      ").append(width).append("w x ").append(floors)
							.append("f  ").append(sum).append(" blocks in ")
							.append(plan.breaches().size()).append(" lanes\n");
					}
				}
			}
			System.out.println("   cutsAChordThatFits=" + (cuts ? "on " : "off")
				+ "   lanes=" + lanes + " blocks=" + blocks + " worst=" + worst + " wrong=" + wrong
				+ " dirtyConfigs=" + dirty + " length=" + length);
			System.out.print(moved);
		}
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
	}

	private static final List<String> PICKED = List.of(
		"deltarune-ch-4-guardian", "illit-do-the-dance", "big-shot", "hopes-and-dreams",
		"golden-brown-2xspeed", "adventure-of-a-lifetime", "michael-jackson-thriller",
		"aria-math-c418");

	/**
	 * The same flag across the library, and read back, because a cut moves notes.
	 *
	 * <p>Guardian saying yes is not the answer -- Guardian said yes to the off-bus prepad too, and
	 * that cost seven other songs 772 lanes between them. And a cut that changes which half of a chord
	 * lands on which side of a staircase is exactly the change that puts a note on somebody else's
	 * tick, so nothing here is quotable until {@link NoteMachineReader} has read it.</p>
	 */
	@Test
	void weighsCuttingAChordThatFitsAcrossTheLibrary() throws Exception {
		java.util.List<java.util.List<SongBuilder.EventNote>> songs = new java.util.ArrayList<>();
		for (String name : PICKED) {
			songs.add(BreachView.song(name));
		}
		System.out.println();
		System.out.println("==== cutting a chord that fits, eight songs x 60 configs ====");
		for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			int lanes = 0;
			int blocks = 0;
			int worst = 0;
			int wrong = 0;
			int refused = 0;
			long length = 0;
			long volume = 0;
			for (java.util.List<SongBuilder.EventNote> notes : songs) {
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new net.minecraft.core.BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							lanes += plan.breaches().size();
							blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
							worst = Math.max(worst, plan.worstBreach());
							wrong += plan.wrongNotes();
							length += plan.width();
							volume += plan.commands().size();
						} catch (RuntimeException refusedHere) {
							refused++;
						}
					}
				}
			}
			System.out.println("   cutsAChordThatFits=" + (cuts ? "on " : "off") + "   lanes=" + lanes
				+ " blocks=" + blocks + " worst=" + worst + " wrong=" + wrong + " refused=" + refused
				+ " length=" + length + " volume=" + volume);
		}
		// And the machine, at ekran's own size and at the two Guardian configs the flag changes most.
		System.out.println("   -- read back --");
		for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			int unreached = 0;
			int wrong = 0;
			for (String name : PICKED) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					new net.minecraft.core.BlockPos(0, 64, 0), BreachView.song(name),
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 40, 5));
				unreached += readAll(placeInWorld(plan)).unreachedNotes();
				wrong += plan.wrongNotes();
			}
			List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
			for (int[] size : new int[][] {{40, 4}, {16, 3}, {20, 4}, {24, 5}}) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					new net.minecraft.core.BlockPos(0, 64, 0), guardian,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, size[0], size[1]));
				unreached += readAll(placeInWorld(plan)).unreachedNotes();
				wrong += plan.wrongNotes();
			}
			System.out.println("   cutsAChordThatFits=" + (cuts ? "on " : "off")
				+ "   unreached=" + unreached + " wrong=" + wrong);
		}
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
	}

	/**
	 * The shed bought as a cell of wire rather than as a column, across the library.
	 *
	 * <p>ekran's arithmetic says a chord of 28 can cut a descent if the head sheds its flank: six in
	 * the head, twenty-two on the bus at eleven cells, no transition, staircase of four -- fifteen. It
	 * can, and on a song of nothing but 28s the cuts go from five to forty-eight. Whether that is
	 * <em>worth</em> anything is a different question, and it is this one.</p>
	 */
	@Test
	void weighsShedBuyingTheLastCell() throws Exception {
		java.util.List<java.util.List<SongBuilder.EventNote>> songs = new java.util.ArrayList<>();
		for (String name : PICKED) {
			songs.add(BreachView.song(name));
		}
		System.out.println();
		System.out.println("==== shed buys the last cell, eight songs x 60 configs ====");
		for (boolean shed : new boolean[] {false, true}) {
			SongBuilder.SHED_BUYS_THE_LAST_CELL = shed;
			SongBuilder.SHED_BOUGHT_THE_CELL = 0;
			int lanes = 0;
			int blocks = 0;
			int worst = 0;
			int wrong = 0;
			long length = 0;
			long volume = 0;
			for (java.util.List<SongBuilder.EventNote> notes : songs) {
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new net.minecraft.core.BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						lanes += plan.breaches().size();
						blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						worst = Math.max(worst, plan.worstBreach());
						wrong += plan.wrongNotes();
						length += plan.width();
						volume += plan.commands().size();
					}
				}
			}
			System.out.println("   shedBuysTheCell=" + (shed ? "on " : "off") + "   lanes=" + lanes
				+ " blocks=" + blocks + " worst=" + worst + " wrong=" + wrong + " length=" + length
				+ " volume=" + volume + " boughtTheCell=" + SongBuilder.SHED_BOUGHT_THE_CELL);
		}
		System.out.println("   -- read back at 40w x 5f --");
		for (boolean shed : new boolean[] {false, true}) {
			SongBuilder.SHED_BUYS_THE_LAST_CELL = shed;
			int unreached = 0;
			for (String name : PICKED) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					new net.minecraft.core.BlockPos(0, 64, 0), BreachView.song(name),
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 40, 5));
				unreached += readAll(placeInWorld(plan)).unreachedNotes();
			}
			System.out.println("   shedBuysTheCell=" + (shed ? "on " : "off")
				+ "   unreached=" + unreached);
		}
		SongBuilder.SHED_BUYS_THE_LAST_CELL = false;
	}

	/**
	 * Padding a chord forward until it cuts, against telling the cutter to cut anyway.
	 *
	 * <p>They are two answers to one problem, and ekran's is the honest one: move the chord forward so
	 * it really does overshoot, and the ordinary cut applies with the near half filling to the wall.
	 * {@link SongBuilder#CUTS_A_CHORD_THAT_FITS} is the shortcut -- it skips the padding and forces the
	 * division instead, which is why its near half ends wherever the chord ended.</p>
	 *
	 * <p>So: does a deeper search let the shortcut go? {@link SongBuilder#CUT_PAD_COLUMNS} is 2, and a
	 * chord smaller than its room can be short by a great deal more than two.</p>
	 */
	@Test
	void weighsPaddingForwardAgainstForcingTheCut() throws Exception {
		java.util.List<java.util.List<SongBuilder.EventNote>> songs = new java.util.ArrayList<>();
		for (String name : PICKED) {
			songs.add(BreachView.song(name));
		}
		System.out.println();
		System.out.println("==== pad until it cuts, against cutting what fits ====");
		for (int columns : new int[] {2, 4, 8, 14}) {
			for (boolean force : new boolean[] {false, true}) {
				SongBuilder.CUT_PAD_COLUMNS = columns;
				SongBuilder.CUTS_A_CHORD_THAT_FITS = force;
				int lanes = 0;
				int blocks = 0;
				int worst = 0;
				int wrong = 0;
				long length = 0;
				long volume = 0;
				for (java.util.List<SongBuilder.EventNote> notes : songs) {
					for (int floors = 1; floors <= 6; floors++) {
						for (int width = 12; width <= 48; width += 4) {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new net.minecraft.core.BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							lanes += plan.breaches().size();
							blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
							worst = Math.max(worst, plan.worstBreach());
							wrong += plan.wrongNotes();
							length += plan.width();
							volume += plan.commands().size();
						}
					}
				}
				System.out.println(String.format(
					"   cutPadColumns=%-2d forceTheCut=%-5s   lanes=%3d blocks=%4d worst=%2d wrong=%d"
						+ " length=%d volume=%d",
					columns, force, lanes, blocks, worst, wrong, length, volume));
			}
		}
		SongBuilder.CUT_PAD_COLUMNS = 2;
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
	}

	/**
	 * Why the search that pads a chord forward until it cuts gives up, counted.
	 *
	 * <p>Deepening it does nothing -- 2, 4, 8 and 14 columns all land within twenty blocks of each
	 * other -- so it is not running out of columns. It has two other ways to stop: nowhere left to
	 * charge a column to, and a pad that pushes the lane so far it no longer reaches the chord at all.
	 * Both are trace lines rather than census keys, so this counts them off the trace.</p>
	 */
	@Test
	void saysWhyThePadUntilItCutsSearchGivesUp() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		for (int columns : new int[] {2, 14}) {
			SongBuilder.CUT_PAD_COLUMNS = columns;
			int noRoom = 0;
			int stopsShort = 0;
			int tried = 0;
			for (int[] size : new int[][] {{20, 3}, {40, 4}, {16, 3}}) {
				java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
				java.io.PrintStream saved = System.out;
				try {
					System.setOut(new java.io.PrintStream(buffer, true,
						java.nio.charset.StandardCharsets.UTF_8));
					SongBuilder.TRACE = true;
					SongBuilder.createPastePlan(new net.minecraft.core.BlockPos(0, 64, 0), guardian,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, size[0], size[1]));
				} finally {
					SongBuilder.TRACE = false;
					System.setOut(saved);
				}
				for (String line : buffer.toString(java.nio.charset.StandardCharsets.UTF_8)
						.lines().toList()) {
					if (!line.contains("CUTTRY")) {
						continue;
					}
					tried++;
					if (line.contains("refused=noRoomToBook")) {
						noRoom++;
					} else if (line.contains("refused=sweepStopsShort")) {
						stopsShort++;
					}
				}
			}
			System.out.println("   cutPadColumns=" + columns + "  CUTTRY lines=" + tried
				+ "  noRoomToBook=" + noRoom + "  sweepStopsShort=" + stopsShort
				+ "  gotAsFarAsAsking=" + (tried - noRoom - stopsShort));
		}
		SongBuilder.CUT_PAD_COLUMNS = 2;
	}

	private static java.util.Map<net.minecraft.core.BlockPos,
			net.minecraft.world.level.block.state.BlockState> placeInWorld(
			SongBuilder.PastePlan plan) {
		java.util.Map<net.minecraft.core.BlockPos,
			net.minecraft.world.level.block.state.BlockState> world = new java.util.HashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new net.minecraft.core.BlockPos(Integer.parseInt(word[1]),
				Integer.parseInt(word[2]), Integer.parseInt(word[3])), BreachView.parse(word[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(java.util.Map<net.minecraft.core.BlockPos,
			net.minecraft.world.level.block.state.BlockState> world) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (net.minecraft.core.BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read("Blitz", new net.minecraft.core.BlockPos(minX, minY, minZ),
			new net.minecraft.core.BlockPos(maxX, maxY, maxZ),
			at -> world.getOrDefault(at, net.minecraft.world.level.block.Blocks.AIR
				.defaultBlockState()));
	}

	@Test
	void drawsEveryGuardianBreach() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		for (int[] size : SIZES) {
			BreachView.Traced traced = BreachView.build(guardian, size[0], size[1], 4);
			List<BreachView.Overrun> overruns = BreachView.overruns(traced.plan());
			if (overruns.isEmpty()) {
				System.out.println();
				System.out.println("######## " + size[0] + "w x " + size[1] + "f -- nothing outside");
				continue;
			}
			// The worst two only. A page a lane over fourteen configs is more than anybody reads, and
			// the deepest overrun in a corridor is the one whose cause the others usually share.
			overruns.stream().limit(2).forEach(run ->
				BreachView.report(size[0] + "w x " + size[1] + "f", traced.plan(), traced.trace(),
					run));
		}
	}
}
