package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The routed walk is the v2 walk: same song, same size, same start, same build, block for block.
 *
 * <p>This is the gate the whole route migration stands behind. {@code walkRouted} began as a copy
 * of {@code walkV2} with the floor state machine replaced by a {@link LaneRoute} it follows, and
 * every further step of the migration -- leg-relative rulers, new turn kinds, the interleaved
 * generator -- is only allowed while the serpentine route still reproduces v2 exactly. A failure
 * here means the routed walk has changed a build it promised not to change.</p>
 *
 * <p>One song at a handful of sizes and both starts, so the default suite holds the gate cheaply;
 * {@code RouteIdentitySweepProbe} asks the same question of the whole library.</p>
 */
class RouteWalkIdentityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> notes() throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile("illit-do-the-dance"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
	}

	@Test
	void routedSerpentineIsV2() throws Exception {
		List<SongBuilder.EventNote> notes = notes();
		int[][] sizes = {{40, 3}, {24, 3}, {20, 5}, {16, 1}};
		for (int[] size : sizes) {
			int width = size[0];
			int floors = size[1];
			for (SongBuilder.WalkStart start : List.of(SongBuilder.WalkStart.HEAD,
					new SongBuilder.WalkStart(0, floors - 1, -1))) {
				SongBuilder.PastePlan v2 = SongBuilder.createV2PastePlan(new BlockPos(0, 64, 0),
					Direction.EAST, notes, width, floors, start);
				SongBuilder.PastePlan routed = SongBuilder.createRoutedPastePlan(
					new BlockPos(0, 64, 0), Direction.EAST, notes, width, floors, start);
				assertEquals(v2.commands(), routed.commands(),
					width + "x" + floors + " from " + start);
				// Two empty plans are also equal; a gate that can pass vacuously is not a gate.
				org.junit.jupiter.api.Assertions.assertTrue(v2.commands().size() > 1000,
					"suspiciously small build: " + v2.commands().size());
			}
		}
	}
}
