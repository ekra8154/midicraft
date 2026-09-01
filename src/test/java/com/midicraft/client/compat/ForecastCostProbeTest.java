package com.midicraft.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the footprint preview costs, per mode and per size.
 *
 * <p>Clicking through build sizes on Guardian used to answer in half a second to two seconds
 * and now sits for about a minute and a half. {@code BuildOptionsScreen} runs a whole
 * {@link SongBuilder#createPastePlan} for every click, so the preview costs exactly one build --
 * and which build is the question this asks, because the ultra lane runs the walk twice and keeps
 * the better one while v2 runs it once.</p>
 */
@Tag("sweep")
class ForecastCostProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static long millis(Runnable work) {
		long from = System.nanoTime();
		work.run();
		return (System.nanoTime() - from) / 1_000_000;
	}

	/**
	 * Where the time actually goes, sampled rather than reasoned about.
	 *
	 * <p>A second thread reads the worker's stack every few milliseconds and counts what it finds. It
	 * is a real profiler in twenty lines, and it answers the question the reading of the code could
	 * not: which frame of ours is on the stack when the clock is running.</p>
	 */
	@Test
	void samplesWhereTheTimeGoes() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(16, 24, 3);
		java.util.Map<String, Integer> hits = new java.util.HashMap<>();
		Thread worker = new Thread(()
			-> SongBuilder.createPastePlan(BlockPos.ZERO, guardian,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, limits), "forecast");
		worker.start();
		int taken = 0;
		while (worker.isAlive()) {
			StackTraceElement[] stack = worker.getStackTrace();
			// The deepest frame that is ours. Anything below it is the JDK doing what we asked.
			for (StackTraceElement frame : stack) {
				if (frame.getClassName().startsWith("com.midicraft")) {
					hits.merge(frame.getClassName().substring(
						frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName()
						+ ":" + frame.getLineNumber(), 1, Integer::sum);
					break;
				}
			}
			taken++;
			Thread.sleep(3);
		}
		worker.join();
		int samples = taken;
		System.out.println();
		System.out.println("==== where the v2 forecast spends its time (" + samples
			+ " samples, Guardian 24x3) ====");
		hits.entrySet().stream()
			.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(18)
			.forEach(hit -> System.out.println(String.format("   %5.1f%%  %s",
				100.0 * hit.getValue() / samples, hit.getKey())));
	}

	@Test
	void timesTheForecast() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== forecast cost, deltarune-ch-4-guardian (" + guardian.size()
			+ " notes) ====");
		for (SongBuilder.PasteMode mode : List.of(SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2)) {
			for (int[] size : new int[][] {{40, 5}, {24, 3}, {16, 3}}) {
				SongBuilder.BuildLimits limits =
					new SongBuilder.BuildLimits(16, size[0], size[1]);
				// Twice: the first pays for class loading and JIT, the second is what a click costs.
				millis(() -> SongBuilder.createPastePlan(BlockPos.ZERO, guardian, mode, limits));
				long warm = millis(()
					-> SongBuilder.createPastePlan(BlockPos.ZERO, guardian, mode, limits));
				System.out.println(String.format("   %-24s %2dw x %df   %6d ms",
					mode, size[0], size[1], warm));
			}
		}
	}
}
