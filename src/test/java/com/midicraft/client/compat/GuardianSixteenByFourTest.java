package com.midicraft.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The repro: Guardian at 16 wide over four floors, the lane through {@code 20 77 148}.
 *
 * <p>Their reading, off the blocks: the thing standing outside the wall is a stacked bus, and had
 * it shed its flank it would have crossed the staircase without going outside at all. Which would
 * be a third reason to shed, and a different one from either that exists --
 * {@link SongBuilder#SHEDS_THE_FLANK_THE_DESCENT_WANTS} sheds where the corridor is too tight to
 * hold the near half ({@code room == 2}), and {@link SongBuilder#SHED_BUYS_THE_LAST_CELL} sheds
 * where the run is one cell over. Neither asks whether the head is standing against the wall.</p>
 *
 * <p>So: the blocks first. The corridor around that column, the walk's decisions for that lane, and
 * the census keys that say what shape the chord came out as.</p>
 */
@Tag("sweep")
class GuardianSixteenByFourTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final int WIDTH = 16;
	private static final int FLOORS = 4;
	/** The coordinate, pasted at {@code 0 64 0}. */
	private static final int AT_X = 20;
	private static final int AT_Y = 77;
	private static final int AT_Z = 148;

	@Test
	void dumpsTheLaneFoundInGame() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		BreachView.Traced traced = BreachView.build(guardian, WIDTH, FLOORS, 4);
		SongBuilder.PastePlan plan = traced.plan();
		System.out.println();
		System.out.println("######## Guardian " + WIDTH + "w x " + FLOORS + "f, paste at 0 64 0");
		System.out.println("   nearWall=" + plan.nearWall() + " farWall=" + plan.farWall()
			+ " spanX=" + plan.spanX() + " spanZ=" + plan.spanZ()
			+ " breaches=" + plan.breaches());
		plan.faults().stream().filter(fault -> fault.startsWith("a lane turned"))
			.forEach(fault -> System.out.println("   fault " + fault));

		System.out.println("   -- every lane outside a wall, worst first --");
		List<BreachView.Overrun> out = BreachView.overruns(plan);
		out.stream().limit(8).forEach(run -> System.out.println("   " + run));

		// The column, whichever lane it belongs to.
		BreachView.Overrun here = new BreachView.Overrun(AT_Y, AT_Z,
			Math.max(1, AT_X - plan.farWall()), AT_X, AT_X < plan.nearWall());
		System.out.println();
		System.out.println("   -- the lane at " + AT_X + " " + AT_Y + " " + AT_Z + " --");
		BreachView.laneTrace(traced.trace(), AT_Z, 2).stream()
			.filter(line -> line.startsWith("TURN ") || line.startsWith("TURNEDAT "))
			.forEach(line -> System.out.println("   " + line));
		System.out.println(BreachView.draw(plan, here, 2, 1, 5));

		// And what the chords near it were built as, which is what "it should have shed" turns on.
		System.out.println("   -- shapes and sheds over the whole build --");
		plan.padding().entrySet().stream()
			.filter(entry -> entry.getKey().contains("Shed") || entry.getKey().contains("shed")
				|| entry.getKey().startsWith("planStackedSplit")
				|| entry.getKey().startsWith("planStackedBusGot"))
			.forEach(entry -> System.out.println("   " + entry.getKey() + " " + entry.getValue()));
	}
}
