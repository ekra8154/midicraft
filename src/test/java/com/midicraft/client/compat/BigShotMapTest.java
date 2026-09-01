package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: the one wrong note in a real song, and what stands around it. */
@Tag("sweep")
class BigShotMapTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dumps() throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs", "big-shot.json");
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
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 12, 3));
		Map<String, String> world = new HashMap<>();
		Map<String, Integer> order = new HashMap<>();
		List<String> commands = plan.commands();
		for (int index = 0; index < commands.size(); index++) {
			String[] parts = commands.get(index).split(" ");
			world.put(parts[1] + " " + parts[2] + " " + parts[3], parts[4]);
			order.put(parts[1] + " " + parts[2] + " " + parts[3], index);
		}
		for (int y = 70; y >= 67; y--) {
			System.out.println("BS y=" + y);
			for (int z = 131; z <= 138; z++) {
				StringBuilder row = new StringBuilder("BS  z=" + z + " ");
				for (int x = 0; x <= 6; x++) {
					String key = x + " " + y + " " + z;
					String block = world.get(key);
					row.append(String.format(" %-24s",
						(block == null ? "." : block.replace("minecraft:", ""))
							+ (order.containsKey(key) ? "#" + order.get(key) : "")));
				}
				System.out.println(row);
			}
		}
	}
}
