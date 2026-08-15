package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** One build, written out block by block, so two commits can be diffed against each other. */
@Tag("sweep")
class DumpBuildProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dump() throws Exception {
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
		List<SongBuilder.EventNote> notes;
		try (Reader reader = Files.newBufferedReader(songs.resolve("illit-do-the-dance.json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
		SongBuilder.DEBUG_PASTE = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 40, 3));
		SongBuilder.DEBUG_PASTE = false;
		Files.write(Path.of(System.getProperty("dumpTo", "build/dump.txt")),
			plan.commands().stream().sorted().toList());
		System.out.println("DUMPED " + plan.commands().size() + " to "
			+ System.getProperty("dumpTo", "build/dump.txt"));
	}
}
