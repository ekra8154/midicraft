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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: every breach in a real song, with the chord and the wire that caused it. */
@Tag("sweep")
class BreachProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void lists() throws Exception {
		TreeMap<Integer, Integer> byChord = new TreeMap<>();
		TreeMap<Integer, Integer> byOvershoot = new TreeMap<>();
		for (String name : List.of("illit-do-the-dance", "big-shot")) {
			Path file = Path.of("run", "config", "midicraft", "songs", name + ".json");
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
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
						if (!fault.startsWith("a lane turned -")) {
							continue;
						}
						String[] words = fault.split(" ");
						int over = -Integer.parseInt(words[3]);
						int chord = 0;
						for (int index = 0; index + 2 < words.length; index++) {
							if (words[index].equals("chord") && words[index + 1].equals("of")) {
								chord = Integer.parseInt(words[index + 2]);
							}
						}
						byOvershoot.merge(over, 1, Integer::sum);
						byChord.merge(chord, 1, Integer::sum);
						System.out.println("BREACH " + name + " f" + floors + " w" + width
							+ " :: " + fault);
					}
				}
			}
		}
		System.out.println("BREACH byOvershoot " + byOvershoot);
		System.out.println("BREACH byChordSize " + byChord);
	}
}
