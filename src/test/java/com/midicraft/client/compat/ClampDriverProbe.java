package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway: the biggest chord of each half of a song, in notes. */
@Tag("sweep")
class ClampDriverProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void nameTheWidestChord() throws Exception {
		System.out.println("clamp drivers:");
		for (String song : System.getProperty("probe.songs",
				"moonlight-sonata-3rd-movement,neverending-night-2-lanes").split(",")) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(BreachView.songFile(song))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			for (int side = 0; side <= 1; side++) {
				final int parity = side;
				List<SongBuilder.EventNote> half = notes.stream()
					.filter(note -> Math.floorMod(note.time(), 2) == parity).toList();
				if (half.isEmpty()) {
					continue;
				}
				Map<Integer, Integer> sizes = new HashMap<>();
				for (SongBuilder.EventNote note : half) {
					sizes.merge(note.time(), 1, Integer::sum);
				}
				Map.Entry<Integer, Integer> widest = sizes.entrySet().stream()
					.max(Map.Entry.comparingByValue()).orElseThrow();
				System.out.println("  " + song + " half" + side + ": biggest chord "
					+ widest.getValue() + " notes at tick " + widest.getKey());
			}
		}
	}
}
