package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the ultra lane's chord shapes are worth on a straight half-tick lane.
 *
 * <p>Two numbers, and the second is the one asked for. Length, because a stacked module
 * holds seven notes in the two columns a bus spends on four. And stalls, because a bus's cells come
 * out of the fifteen a dust run reaches while a stacked module hands the signal on through a
 * strongly powered block -- so the wire past it starts again at full strength, and full strength is
 * exactly what the mirrored lane spends keeping level with its partner.</p>
 */
@Tag("sweep")
class StackedStraightProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void pricesTheStackedShapes() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "midicraft", "songs");
		List<java.nio.file.Path> files = new ArrayList<>();
		try (var listing = java.nio.file.Files.list(songs)) {
			listing.filter(file -> file.toString().endsWith(".json")).sorted().forEach(files::add);
		}
		System.out.println();
		System.out.println("==== stacked shapes on the straight half-tick lane ====");
		System.out.println(String.format("  %-40s %-6s %9s %9s %7s %8s %8s",
			"", "speed", "plainLen", "stackLen", "saved", "plainStl", "stackStl"));
		long plainTotal = 0;
		long stackTotal = 0;
		long plainStalls = 0;
		long stackStalls = 0;
		int shown = 0;
		int builds = 0;
		for (java.nio.file.Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			for (int speedFactor : new int[] {1, 2}) {
				ComposerProject project = BreachView.project(name);
				if (speedFactor != 1) {
					project = project.withSpeedQuarters(
						Math.max(1, project.speedQuarters()) * speedFactor);
				}
				List<SongBuilder.EventNote> song = SongBuilder.notesFor(
					SongBuilder.PasteMode.HALF_TICK_LANE,
					project.toSequenceTracks(Set.of(), true), project, true);
				if (song.isEmpty()) {
					continue;
				}
				long odd = song.stream().filter(note -> Math.floorMod(note.time(), 2) == 1).count();
				if (odd == 0 || odd == song.size()) {
					continue;
				}
				int[] plain = measure(song, false);
				int[] stacked = measure(song, true);
				if (plain == null || stacked == null) {
					System.out.println("  " + name + " " + speedFactor + "x REFUSED");
					continue;
				}
				builds++;
				plainTotal += plain[0];
				stackTotal += stacked[0];
				plainStalls += plain[1];
				stackStalls += stacked[1];
				if (shown++ < 18) {
					System.out.println(String.format("  %-40s %-6s %9d %9d %6.1f%% %8d %8d",
						name.length() > 39 ? name.substring(0, 39) : name, speedFactor + "x",
						plain[0], stacked[0],
						100.0 * (plain[0] - stacked[0]) / Math.max(1, plain[0]),
						plain[1], stacked[1]));
				}
			}
		}
		System.out.println();
		System.out.println("  " + builds + " two-lane builds");
		System.out.println(String.format("  total columns %d -> %d, %.1f%% shorter",
			plainTotal, stackTotal, 100.0 * (plainTotal - stackTotal) / Math.max(1, plainTotal)));
		System.out.println(String.format("  mirror stalls %d -> %d, %.1f%% fewer",
			plainStalls, stackStalls,
			100.0 * (plainStalls - stackStalls) / Math.max(1, plainStalls)));
	}

	/** Build length and mirror stalls, or null if the layout refused it. */
	private static int[] measure(List<SongBuilder.EventNote> song, boolean stacked) {
		boolean restore = SongBuilder.HALF_TICK_LANE_STACKS;
		int restoreGap = SongBuilder.HALF_TICK_LANE_GAP;
		try {
			SongBuilder.HALF_TICK_LANE_STACKS = stacked;
			SongBuilder.HALF_TICK_LANE_GAP = stacked ? 4 : 3;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
				SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(4, 44, 3));
			return new int[] {plan.spanX(),
				plan.padding().getOrDefault("halfTickMirrorStalled", 0)};
		} catch (RuntimeException refused) {
			return null;
		} finally {
			SongBuilder.HALF_TICK_LANE_STACKS = restore;
			SongBuilder.HALF_TICK_LANE_GAP = restoreGap;
		}
	}
}
