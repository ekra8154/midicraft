package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: the blocks of the lane that breaches in Do The Dance at 28 wide, 2 floors. */
class IllitBreachMapTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dumps() throws Exception {
		Path file = Path.of("run", "config", "fast-noteblocks", "songs",
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
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 28, 2));
		for (int y = 70; y >= 67; y--) {
			System.out.println("ILLIT y=" + y + "   x=15..20");
			for (int z = 105; z <= 107; z++) {
				StringBuilder row = new StringBuilder("ILLIT  z=" + z + " ");
				for (int x = 15; x <= 20; x++) {
					String found = ".";
					for (String command : plan.commands()) {
						String[] parts = command.split(" ");
						if (Integer.parseInt(parts[1]) == x && Integer.parseInt(parts[2]) == y
								&& Integer.parseInt(parts[3]) == z) {
							found = parts[4].replace("minecraft:", "");
						}
					}
					row.append(String.format(" %-22s", found));
				}
				System.out.println(row);
			}
		}
	}
}
