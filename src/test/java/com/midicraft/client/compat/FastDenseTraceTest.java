package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
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

/** Scratch probe: the run of sixteen the slot-order change put into fast-and-dense. */
@Tag("sweep")
class FastDenseTraceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void traces() throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs",
			"illit-do-the-dance.json");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		SongBuilder.TRACE = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 28, 2));
		SongBuilder.TRACE = false;
		int dust = 0;
		String from = "start";
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String where = parts[1] + " " + parts[2] + " " + parts[3];
			if (parts[4].startsWith("minecraft:redstone_wire")) {
				dust++;
				continue;
			}
			if (!parts[4].startsWith("minecraft:repeater")) {
				continue;
			}
			if (dust > 15) {
				System.out.println("FD !!! run of " + dust + " from [" + from + "] to [" + where + "]");
			}
			dust = 0;
			from = where;
		}
		System.out.println("FD padding=" + plan.padding());
	}
}
