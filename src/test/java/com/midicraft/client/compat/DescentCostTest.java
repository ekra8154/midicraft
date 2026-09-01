package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: how many blocks of wire a descent really spends, counted off the built blocks. */
class DescentCostTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void counts() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(
				Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		TreeMap<Integer, Integer> descents = new TreeMap<>();
		TreeMap<Integer, Integer> climbs = new TreeMap<>();
		for (Path file : files) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			if (!file.getFileName().toString().startsWith("illit")) { continue; }
			for (int floors = 2; floors <= 2; floors++) {
				for (int width = 24; width <= 24; width += 8) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					int dust = 0;
					int fromY = Integer.MIN_VALUE;
					for (String command : plan.commands()) {
						String[] parts = command.split(" ");
						int y = Integer.parseInt(parts[2]);
						if (parts[4].startsWith("minecraft:redstone_wire")) {
							dust++;
							continue;
						}
						if (!parts[4].startsWith("minecraft:repeater")) {
							continue;
						}
						if (fromY != Integer.MIN_VALUE && dust > 0) {
							// Only runs that actually cross a floor, and only the shortest of them:
							// the shortest is the one whose pad was nothing, which is the staircase
							// on its own.
							if (y - fromY <= -4) {
								descents.merge(dust, 1, Integer::sum);
								if (dust == 5) { System.out.println("COST 5-dust descent ending at repeater " + parts[1] + " " + parts[2] + " " + parts[3]); }
							} else if (y - fromY >= 4) {
								climbs.merge(dust, 1, Integer::sum);
							}
						}
						dust = 0;
						fromY = y;
					}
				}
			}
		}
		System.out.println("COST descent runs by length " + descents);
		System.out.println("COST climb runs by length " + climbs);
	}
}
