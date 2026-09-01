package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: every run of dust longer than fifteen, with the coordinates it runs between. */
@Tag("sweep")
class DeadWireProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void findsDeadRuns() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
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
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					List<String> run = new ArrayList<>();
					String from = "start";
					for (String command : plan.commands()) {
						String[] parts = command.split(" ");
						String block = parts[4];
						String where = parts[1] + " " + parts[2] + " " + parts[3];
						if (block.startsWith("minecraft:redstone_wire")) {
							// Not the stacked module's cross: it sits under the centre block, off the
							// signal path, and counting it made 27 sound builds look dead.
							if (block.startsWith("minecraft:redstone_wire[")) { continue; }
							run.add(where);
							continue;
						}
						if (!block.startsWith("minecraft:repeater")) {
							continue;
						}
						if (run.size() > 15) {
							System.out.println("DEAD " + name + " floors=" + floors
								+ " width=" + width + " dust=" + run.size()
								+ " from=[" + from + "] to=[" + where + "]");
							for (int step = 0; step < run.size(); step++) {
								System.out.println("DEAD   " + (15 - step) + " at " + run.get(step));
							}
						}
						run.clear();
						from = where;
					}
				}
			}
		}
		System.out.println("DEAD done");
	}
}
