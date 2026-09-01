package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One Guardian breach, in coordinates to stand in.
 *
 * <p>Finds every size that still breaches rather than working from a list, because the list keeps
 * moving and a stale one points at a config that is now clean. Ranked worst first, then the
 * worst single lane of the worst size is dumped whole: where it is, what the walk decided on the
 * way there, and the blocks around it.</p>
 *
 * <p>Paste at {@code 0 64 0} and every coordinate printed here is the coordinate in the world.
 * Space-separated, so a line goes straight into {@code /tp}.</p>
 */
@Tag("sweep")
class GuardianReproTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private record Sized(int width, int floors, int blocks, int lanes, int worst) {
	}

	@Test
	void dumpsTheWorstGuardianBreach() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		List<Sized> dirty = new ArrayList<>();
		for (int floors = 1; floors <= 6; floors++) {
			for (int width = 16; width <= 48; width += 4) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, width, floors));
				int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
				if (sum > 0) {
					dirty.add(new Sized(width, floors, sum, plan.breaches().size(),
						plan.worstBreach()));
				}
			}
		}
		dirty.sort((a, b) -> b.worst() != a.worst() ? Integer.compare(b.worst(), a.worst())
			: Integer.compare(b.blocks(), a.blocks()));
		System.out.println();
		System.out.println("==== Guardian sizes still breaching, worst lane first ====");
		for (Sized size : dirty) {
			System.out.println("   " + size.width() + "w x " + size.floors() + "f   worst="
				+ size.worst() + " lanes=" + size.lanes() + " blocks=" + size.blocks());
		}
		// And the live, which should say nothing.
		SongBuilder.PastePlan mine = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 40, 5));
		System.out.println("   40w x 5f (live)   breaches=" + mine.breaches());
		if (dirty.isEmpty()) {
			return;
		}
		// The worst one, whole. Widest first among equals, because a wide corridor that breaches is a
		// bug and a narrow one may simply be too small for the chord.
		Sized pick = dirty.get(0);
		for (Sized size : dirty) {
			if (size.worst() == pick.worst() && size.width() > pick.width()) {
				pick = size;
			}
		}
		BreachView.Traced traced = BreachView.build(guardian, pick.width(), pick.floors(), 4);
		List<BreachView.Overrun> out = BreachView.overruns(traced.plan());
		System.out.println();
		System.out.println("######## REPRO: paste Guardian at 0 64 0, " + pick.width() + " wide x "
			+ pick.floors() + " floors");
		Sized chosen = pick;
		out.stream().limit(3).forEach(run ->
			BreachView.report(chosen.width() + "w x " + chosen.floors() + "f", traced.plan(),
				traced.trace(), run));
	}
}
