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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: every wrong note in the library, with the gap that makes it one. */
@Tag("sweep")
class WrongNoteProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void lists() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		TreeMap<Integer, Integer> byGap = new TreeMap<>();
		TreeMap<String, Integer> byShape = new TreeMap<>();
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
					for (String fault : plan.faults()) {
						if (!fault.startsWith("the note")) {
							continue;
						}
						// "... belongs to tick A but would sound again at tick B, too late ..."
						// Every number that follows the word "tick": the note's own, then the intruder's.
						String[] words = fault.split(" ");
						List<Integer> ticks = new java.util.ArrayList<>();
						for (int index = 1; index < words.length; index++) {
							if (words[index - 1].equals("tick")) {
								ticks.add(Integer.parseInt(words[index].replace(",", "")));
							}
						}
						int own = ticks.get(0);
						int again = ticks.get(1);
						byGap.merge(again - own, 1, Integer::sum);
						int fi = indexOf(words, "from");
						int cx = Integer.parseInt(words[fi + 4]);
						int cy = Integer.parseInt(words[fi + 5]);
						int cz = Integer.parseInt(words[fi + 6].replace(",", ""));
						int nearest = Integer.MAX_VALUE;
						for (BlockPos turn : plan.turns()) {
							nearest = Math.min(nearest, Math.abs(turn.getX() - cx)
								+ Math.abs(turn.getY() - cy) + Math.abs(turn.getZ() - cz));
						}
						byShape.merge("distanceToNearestTurn=" + (nearest > 8 ? "9+" : "" + nearest),
							1, Integer::sum);
						byShape.merge(name + " f" + floors + " w" + width, 1, Integer::sum);
						System.out.println("WRONG " + name + " floors=" + floors + " width=" + width
							+ " gap=" + (again - own) + " :: " + fault);
					}
				}
			}
		}
		System.out.println("WRONG byGap " + byGap);
		System.out.println("WRONG byBuild " + byShape);
	}

	private static int indexOf(String[] words, String want) {
		for (int index = 0; index < words.length; index++) {
			if (words[index].equals(want)) {
				return index;
			}
		}
		return 0;
	}
}
