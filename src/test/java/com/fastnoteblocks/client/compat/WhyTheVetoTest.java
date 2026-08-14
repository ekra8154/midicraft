package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the no-split veto is actually protecting, read off a lane rather than assumed.
 *
 * <p>Its stated reason is too strong: it forbids a cut that would leave the next lane unable to lay
 * its first chord whole, and a chord that will not fit whole is precisely the chord that gets cut.
 * ekran's point, and on Guardian it is nearly always true -- a headed cut of twenty-four is
 * {@code 1 + 9 + 5 = 15} even at the dearest crossing.</p>
 *
 * <p>And removing it on that basis costs Guardian 86 breach blocks to 157. So it is protecting
 * something the comment does not name. Guardian at 16 wide over two floors is the sharpest case:
 * clean with the veto, 24 blocks in seven lanes without it. This prints both builds' turn decisions
 * so the first one that differs can be read.</p>
 */
@Tag("sweep")
class WhyTheVetoTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final int WIDTH = 16;
	private static final int FLOORS = 2;

	@Test
	void diffsTheTurnsTheVetoChanges() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		BreachView.Traced[] arms = new BreachView.Traced[2];
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.STRANDED_CHORD_MAY_STILL_CUT = arm == 1;
			arms[arm] = BreachView.build(guardian, WIDTH, FLOORS, 4);
		}
		SongBuilder.STRANDED_CHORD_MAY_STILL_CUT = false;
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PastePlan plan = arms[arm].plan();
			System.out.println("   veto=" + (arm == 0 ? "kept   " : "skipped")
				+ "  breaches=" + plan.breaches()
				+ "  nearWall=" + plan.nearWall() + " farWall=" + plan.farWall()
				+ " blocks=" + plan.commands().size());
		}

		// The turns, in build order, with x taken from each build's own near wall. The two builds
		// slide relative to one another, so raw coordinates differ for every turn and say nothing.
		List<String> kept = onlyTurns(arms[0]);
		List<String> skipped = onlyTurns(arms[1]);
		System.out.println("   turns kept=" + kept.size() + " skipped=" + skipped.size());
		int shown = 0;
		for (int index = 0; index < Math.min(kept.size(), skipped.size()) && shown < 6; index++) {
			if (kept.get(index).equals(skipped.get(index))) {
				continue;
			}
			System.out.println();
			System.out.println("   -- first turn that differs, #" + index + " --");
			System.out.println("   kept    " + kept.get(index));
			System.out.println("   skipped " + skipped.get(index));
			shown++;
		}
		if (shown == 0) {
			System.out.println("   every shared turn is identical");
		}

		// And where the skipped arm ends up outside.
		System.out.println();
		System.out.println("   -- lanes outside the walls, veto skipped --");
		BreachView.overruns(arms[1].plan()).stream().limit(6)
			.forEach(run -> System.out.println("   " + run));
	}

	/** Does freeing the gap after a cut still build a machine that plays? */
	@Test
	void readsBackTheGapChange() throws Exception {
		String[] picked = {"deltarune-ch-4-guardian", "illit-do-the-dance", "big-shot",
			"hopes-and-dreams", "golden-brown-2xspeed", "adventure-of-a-lifetime",
			"michael-jackson-thriller", "aria-math-c418"};
		System.out.println();
		System.out.println("==== the gap after a cut, library and read back ====");
		for (boolean free : new boolean[] {false, true}) {
			SongBuilder.CUT_FAR_HALF_FREES_THE_GAP = free;
			int lanes = 0;
			int blocks = 0;
			int wrong = 0;
			long length = 0;
			long volume = 0;
			for (String name : picked) {
				List<SongBuilder.EventNote> notes = BreachView.song(name);
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new net.minecraft.core.BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						lanes += plan.breaches().size();
						blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						wrong += plan.wrongNotes();
						length += plan.width();
						volume += plan.commands().size();
					}
				}
			}
			// The machine, not the plan. Freeing the gap lets chords hang notes in slots the walk
			// previously kept clear, so the question this change actually turns on is whether anything
			// now sounds that should not -- which only NoteMachineReader can answer.
			int unreached = 0;
			int readWrong = 0;
			for (String name : picked) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					new net.minecraft.core.BlockPos(0, 64, 0), BreachView.song(name),
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 40, 5));
				java.util.Map<net.minecraft.core.BlockPos,
					net.minecraft.world.level.block.state.BlockState> world =
						new java.util.HashMap<>();
				for (String command : plan.commands()) {
					String[] word = command.split(" ");
					world.put(new net.minecraft.core.BlockPos(Integer.parseInt(word[1]),
						Integer.parseInt(word[2]), Integer.parseInt(word[3])),
						BreachView.parse(word[4]));
				}
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
				unreached += NoteMachineReader.read("Gap",
					new net.minecraft.core.BlockPos(minX, minY, minZ),
					new net.minecraft.core.BlockPos(maxX, maxY, maxZ),
					at -> world.getOrDefault(at,
						net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()))
					.unreachedNotes();
				readWrong += plan.wrongNotes();
			}
			System.out.println("   gapFree=" + (free ? "on " : "off") + "   lanes=" + lanes
				+ " blocks=" + blocks + " wrong=" + wrong + " length=" + length
				+ " volume=" + volume + "   unreached=" + unreached + " readWrong=" + readWrong);
		}
		SongBuilder.CUT_FAR_HALF_FREES_THE_GAP = true;
	}

	/**
	 * The winning walk's turn lines, as they stand.
	 *
	 * <p>Not normalised against the plan's walls, which was the first thing tried and was wrong: the
	 * trace's x is already the lane's own, running from 0 to the lane width -- the {@code wall=15} and
	 * {@code wall=0} in every line are those coordinates. Subtracting {@code nearWall}, which is where
	 * the finished build slid to, made every turn differ by exactly the slide.</p>
	 */
	private static List<String> onlyTurns(BreachView.Traced traced) {
		return traced.trace().stream().filter(line -> line.startsWith("TURN ")).toList();
	}
}
