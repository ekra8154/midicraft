package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What the forecast line would say, against what the plan's blocks actually cover. */
@Tag("sweep")
class ForecastDepthProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void whatTheDepthLineSays() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().filter(f -> f.getFileName().toString().contains(System.getProperty("probe.song", ""))).toList();
		}
		for (SongBuilder.PasteMode mode : List.of(SongBuilder.PasteMode.HALF_TICK_LANE,
				SongBuilder.PasteMode.INTERLEAVED_HALF_TICK)) {
			System.out.println("== " + mode);
			for (Path file : files) {
				String name = file.getFileName().toString().replace(".json", "");
				ComposerProject song;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
						raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
						raw.speedQuarters());
				}
				List<SongBuilder.EventNote> notes = SongBuilder.notesFor(mode,
					song.toSequenceTracks(Set.of(), true), song, true);
				if (notes.isEmpty()) {
					continue;
				}
				StringBuilder line = new StringBuilder("  " + name + " notes=" + notes.size());
				for (int delay : List.of(16, 24, 32, 48, 64, 512)) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode,
							new SongBuilder.BuildLimits(4, 32, 3, false, delay));
					} catch (RuntimeException broken) {
						line.append(" | ").append(delay).append(": threw ").append(broken);
						continue;
					}
					int lowX = Integer.MAX_VALUE;
					int highX = Integer.MIN_VALUE;
					int lowZ = Integer.MAX_VALUE;
					int highZ = Integer.MIN_VALUE;
					for (String command : plan.commands()) {
						String[] parts = command.split(" ", 5);
						int x = Integer.parseInt(parts[1]);
						int z = Integer.parseInt(parts[3]);
						lowX = Math.min(lowX, x);
						highX = Math.max(highX, x);
						lowZ = Math.min(lowZ, z);
						highZ = Math.max(highZ, z);
					}
					line.append(" | ").append(delay).append(": spanZ=").append(plan.spanZ())
						.append(" spanX=").append(plan.spanX())
						.append(" realX=").append(highX - lowX + 1)
						.append(" realZ=").append(highZ - lowZ + 1);
				}
				System.out.println(line);
			}
			SongBuilder.PARITY_MIN_DELAY_BEFORE_RESEED = 64;
		}
	}
}
