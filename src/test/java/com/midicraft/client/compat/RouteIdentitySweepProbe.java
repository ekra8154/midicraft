package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code RouteWalkIdentityTest} over the whole library: every song, several sizes, both starts.
 *
 * <p>This one asserts, unlike the census probes, because identity is not a fault count that moves
 * with the library -- it either holds everywhere or the routed walk has diverged. A build that
 * throws is compared too: both walks must throw the same complaint, since a song one of them
 * refuses is as much a difference as a block moved.</p>
 *
 * <p>Planning only, no read-back, so the sweep is cheap. Sizes can be overridden with
 * {@code -Dprobe.sizes=40x3,24x3}.</p>
 */
@Tag("sweep")
class RouteIdentitySweepProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static List<int[]> sizes() {
		String given = System.getProperty("probe.sizes", "40x3,24x3,20x5,16x1,32x2");
		List<int[]> sizes = new ArrayList<>();
		for (String size : given.split(",")) {
			String[] part = size.strip().split("x");
			sizes.add(new int[] {Integer.parseInt(part[0]), Integer.parseInt(part[1])});
		}
		return sizes;
	}

	private static String outcome(java.util.concurrent.Callable<SongBuilder.PastePlan> plan) {
		try {
			return String.join("\n", plan.call().commands());
		} catch (Exception refused) {
			return "THREW " + refused.getClass().getSimpleName() + ": " + refused.getMessage();
		}
	}

	@Test
	void routedSerpentineIsV2EverywhereInTheLibrary() throws Exception {
		List<String> mismatches = new ArrayList<>();
		int compared = 0;
		List<Path> files;
		try (Stream<Path> listed = Files.list(SONGS)) {
			files = listed.filter(file -> file.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
			}
			for (int[] size : sizes()) {
				int width = size[0];
				int floors = size[1];
				for (SongBuilder.WalkStart start : List.of(SongBuilder.WalkStart.HEAD,
						new SongBuilder.WalkStart(0, floors - 1, -1))) {
					String v2 = outcome(() -> SongBuilder.createV2PastePlan(new BlockPos(0, 64, 0),
						Direction.EAST, notes, width, floors, start));
					String routed = outcome(() -> SongBuilder.createRoutedPastePlan(
						new BlockPos(0, 64, 0), Direction.EAST, notes, width, floors, start));
					compared++;
					if (!v2.equals(routed)) {
						mismatches.add(file.getFileName() + " " + width + "x" + floors
							+ " from " + start);
					}
				}
			}
		}
		System.out.println("ROUTE IDENTITY: " + compared + " builds compared, "
			+ mismatches.size() + " mismatches");
		mismatches.forEach(mismatch -> System.out.println("  DIVERGED: " + mismatch));
		Assertions.assertTrue(compared > 0, "no builds compared -- did the songs load?");
		Assertions.assertEquals(List.of(), mismatches);
	}
}
