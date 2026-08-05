package com.fastnoteblocks.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Does a stacked module mispower itself, with nothing anywhere near it? */
@Tag("sweep")
class SoloModuleTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void explainsWhyARealSongRefuses() throws Exception {
		SongBuilder.TRACE_PARITY = true;
		SongBuilder.PARITY_SHOWN = 0;
		java.nio.file.Path file = java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs",
			"illit-do-the-dance.json");
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
			com.fastnoteblocks.client.composer.ComposerProject raw =
				new com.google.gson.Gson().fromJson(reader,
					com.fastnoteblocks.client.composer.ComposerProject.class);
			com.fastnoteblocks.client.composer.ComposerProject song =
				new com.fastnoteblocks.client.composer.ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 24, 3));
		} finally {
			SongBuilder.TRACE_PARITY = false;
		}
	}

	@Test
	void buildsOneChordAndCountsWhatItGaveUp() {
		for (String spec : new String[] {"7@1", "5@1", "7@1 7@1", "20@1"}) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse(spec, DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 24, 2));
			StringBuilder said = new StringBuilder();
			plan.padding().forEach((key, count) -> {
				if (key.startsWith("planParity") || key.startsWith("planRelocate")
						|| key.startsWith("planBusFor")) {
					said.append(" ").append(key).append("=").append(count);
				}
			});
			System.out.println("SOLO " + spec + " blocks=" + plan.commands().size()
				+ " faults=" + plan.faults().size() + " |" + said);
		}
	}
}
