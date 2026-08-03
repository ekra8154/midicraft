package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
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

/** Scratch probe: what stands around a note that gets sounded twice. */
@Tag("sweep")
class WrongNoteMapTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dumps() throws Exception {
		Path file = Path.of("run", "config", "fast-noteblocks", "songs",
			"ultra-limit-two-thirties.json");
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
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 12, 1));
		Map<String, String> world = new HashMap<>();
		Map<String, Integer> order = new HashMap<>();
		List<String> commands = plan.commands();
		for (int index = 0; index < commands.size(); index++) {
			String[] parts = commands.get(index).split(" ");
			world.put(parts[1] + " " + parts[2] + " " + parts[3], parts[4]);
			order.put(parts[1] + " " + parts[2] + " " + parts[3], index);
		}
		// The note at 26 65 26 belongs to tick 209 and is sounded again from 26 65 27 at tick 217.
		for (int z = 23; z <= 30; z++) {
			StringBuilder row = new StringBuilder("MAP z=" + z + "  ");
			for (int x = 23; x <= 29; x++) {
				String key = x + " 65 " + z;
				String block = world.get(key);
				row.append(String.format(" %-26s",
					(block == null ? "." : block.replace("minecraft:", ""))
						+ (order.containsKey(key) ? "#" + order.get(key) : "")));
			}
			System.out.println(row);
		}
		for (String at : List.of("26 64 26", "26 65 26", "26 66 26", "26 64 27", "26 65 27",
				"26 66 27", "25 65 26", "27 65 26", "26 65 25", "26 65 28")) {
			System.out.println("CELL " + at + " = " + world.getOrDefault(at, ".")
				+ " placed#" + order.getOrDefault(at, -1));
		}
	}
}
