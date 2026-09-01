package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway: the interleaved builds with wrong notes, each fault named with its shapes. */
@Tag("sweep")
class InterleavedFaultProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void nameTheWrongNotes() throws Exception {
		SongBuilder.NAME_EVERY_CELL = true;
		// song:widthxfloors triples, from the census's own rows.
		String[][] failing = java.util.Arrays.stream(System.getProperty("probe.builds",
				"neverending-night-2-lanes:24x1,neverending-night-2-lanes:24x3").split(","))
			.map(spec -> spec.strip().split("[:x]"))
			.toArray(String[][]::new);
		for (String[] build : failing) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(BreachView.songFile(build[0]))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
				new BlockPos(0, 64, 0), Direction.EAST, notes,
				new SongBuilder.BuildLimits(16, Integer.parseInt(build[1]),
					Integer.parseInt(build[2])),
				SongBuilder.WalkStart.HEAD);
			System.out.println("== " + build[0] + " " + build[1] + "x" + build[2]);
			plan.faults().stream()
				.filter(fault -> fault.startsWith("the note"))
				.forEach(fault -> System.out.println("   " + fault));
		}
		SongBuilder.NAME_EVERY_CELL = false;
	}
}
