package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: which way the lane was going when a stacked head was wanted, and what it got. */
class StackedBusWhereTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void countsByDirection() throws Exception {
		SongBuilder.STACKED_BUS_HEADS = true;
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		TreeMap<String, Long> tally = new TreeMap<>();
		long zBefore = 0;
		long zAfter = 0;
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
			for (int on = 0; on <= 1; on++) {
				SongBuilder.STACKED_BUS_HEADS = on == 1;
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						if (on == 0) {
							zBefore += plan.spanZ();
						} else {
							zAfter += plan.spanZ();
							for (Map.Entry<String, Integer> e : plan.padding().entrySet()) {
								if (e.getKey().startsWith("planStackedBus")
										|| e.getKey().startsWith("planLaneEndedOn")) {
									tally.merge(e.getKey(), (long) e.getValue(), Long::sum);
								}
							}
						}
					}
				}
			}
		}
		SongBuilder.STACKED_BUS_HEADS = false;
		tally.forEach((k, v) -> System.out.println("WHERE " + k + " " + v));
		System.out.println("WHERE zFootprintTotal " + zBefore + " -> " + zAfter);
	}
}
