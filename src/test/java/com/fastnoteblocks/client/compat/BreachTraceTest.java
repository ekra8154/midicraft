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
import org.junit.jupiter.api.Test;

/** Scratch probe: the walk lines around a breach, so the overshoot can be read off. */
class BreachTraceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static void trace(String name, int floors, int width) throws Exception {
		Path file = Path.of("run", "config", "fast-noteblocks", "songs", name + ".json");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		System.out.println("=== TRACE " + name + " f" + floors + " w" + width + " ===");
		SongBuilder.TRACE = true;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, width, floors));
			for (String fault : plan.faults()) {
				System.out.println("FAULT " + fault);
			}
		} finally {
			SongBuilder.TRACE = false;
		}
	}

	/** Every breach left in a song somebody actually wrote, with the build it happens in. */
	@Test
	void listsTheRealBreachesLeft() throws Exception {
		List<Path> files;
		try (java.util.stream.Stream<Path> listing =
				Files.list(Path.of("run", "config", "fast-noteblocks", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
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
					for (String fault : plan.faults()) {
						if (fault.startsWith("a lane turned -")) {
							System.out.println("LEFT " + name + " f" + floors + " w" + width
								+ " : " + fault);
						}
					}
				}
			}
		}
	}

	@Test
	void illitFourTwelve() throws Exception {
		trace("illit-do-the-dance", 4, 12);
	}

	@Test
	void illitSixTwelve() throws Exception {
		trace("illit-do-the-dance", 6, 12);
	}
}
