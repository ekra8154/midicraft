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
