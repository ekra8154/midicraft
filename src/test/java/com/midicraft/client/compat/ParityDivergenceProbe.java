package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Does the running-sum reading of a note's game tick ever differ from rounding its own start? */
@Tag("sweep")
class ParityDivergenceProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void overTheWholeLibrary() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		long notes = 0;
		long differ = 0;
		long parityDiffer = 0;
		int songsAffected = 0;
		for (Path file : files) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			Map<Long, Integer> byId = SongBuilder.buildGameTickByNoteId(song, true);
			long here = 0;
			long hereParity = 0;
			for (ComposerProject.Layer layer : song.layers()) {
				for (ComposerProject.NoteEvent note : layer.notes()) {
					Integer walked = byId.get(note.id());
					if (walked == null) {
						continue;
					}
					notes++;
					int naive = song.buildDelayGameTicks(note.startTick());
					if (naive != walked) {
						here++;
						if (Math.floorMod(naive, 2) != Math.floorMod(walked, 2)) {
							hereParity++;
						}
					}
				}
			}
			int even = 0;
			int odd = 0;
			for (int time : byId.values()) {
				if (Math.floorMod(time, 2) == 0) {
					even++;
				} else {
					odd++;
				}
			}
			System.out.println("  SPLIT " + file.getFileName() + ": " + even + " even : "
				+ odd + " odd");
			differ += here;
			parityDiffer += hereParity;
			if (hereParity > 0) {
				songsAffected++;
				System.out.println("  " + file.getFileName() + ": " + hereParity
					+ " notes on the wrong half if you round the start, of " + here + " that move");
			}
		}
		System.out.println("PARITY DIVERGENCE: " + notes + " notes, " + differ
			+ " differ in tick, " + parityDiffer + " differ in half, " + songsAffected
			+ " songs affected of " + files.size());
	}
}
