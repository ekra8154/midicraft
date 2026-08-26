package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway: does plain v2 also lay repeaters on corners, or only the routed walks? */
@Tag("sweep")
class CornerRepeaterProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void v2AgainstRouted() throws Exception {
		for (String song : new String[] {"moonlight-sonata-3rd-movement", "neverending-night-2-lanes",
				"illit-do-the-dance", "guardian25"}) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(BreachView.songFile(song))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			for (int[] size : new int[][] {{24, 1}, {24, 3}}) {
				SongBuilder.PastePlan v2 = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
					new SongBuilder.BuildLimits(16, size[0], size[1]));
				System.out.println("  " + song + " " + size[0] + "x" + size[1] + " v2="
					+ v2.padding().getOrDefault("REPEATER-ON-CORNER", 0));
				v2.padding().entrySet().stream()
					.filter(entry -> entry.getKey().startsWith("REPEATER-ON-CORNER:"))
					.forEach(entry -> System.out.println("     " + entry.getKey() + " x"
						+ entry.getValue()));
			}
		}
	}
}
