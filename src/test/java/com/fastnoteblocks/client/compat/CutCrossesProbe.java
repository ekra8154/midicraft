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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How often a headed cut leaves nothing on the far side of the staircase.
 *
 * <p>The answer is never, over 244 builds of the whole library at four widths, and it is worth
 * being able to ask again. Deepslate tiles are meant to say that a chord's head stands on one side
 * of a staircase and its tail on the other; whether that is the same thing as "a cut that opened
 * with a head" is a question about the walk, not about the colouring, and the only way to settle it
 * is to build both ways and compare every block.</p>
 *
 * <p>It follows once said out loud: a cut exists because the chord did not fit before the wall, so
 * a chord whose tail stays on the near side is not cut at all. But that was not obvious in advance
 * and the arithmetic in this file has surprised everybody before.</p>
 */
@Tag("sweep")
class CutCrossesProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void doesAHeadedCutAlwaysCross() throws Exception {
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
		SongBuilder.DEBUG_PASTE = true;
		int[][] sizes = {{40, 3}, {16, 4}, {12, 3}, {24, 6}};
		int differing = 0;
		int compared = 0;
		try (var listing = Files.list(songs)) {
			for (Path file : listing.filter(path -> path.toString().endsWith(".json")).sorted()
					.toList()) {
				List<SongBuilder.EventNote> notes;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
						raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
						raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
					notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
				} catch (RuntimeException unreadable) {
					continue;
				}
				if (notes.isEmpty()) {
					continue;
				}
				for (int[] size : sizes) {
					long was = tiles(notes, size, true);
					long now = tiles(notes, size, false);
					if (was < 0 || now < 0) {
						continue;
					}
					compared++;
					if (was != now) {
						differing++;
						System.out.println("CUTCROSS " + file.getFileName() + " " + size[0] + "x"
							+ size[1] + "  tiles " + was + " -> " + now + "  ("
							+ (was - now) + " blocks were a head that never crossed)");
					}
				}
			}
		}
		SongBuilder.TILES_EVERY_HEADED_CUT = false;
		System.out.println("CUTCROSS " + differing + " of " + compared
			+ " builds hold a headed cut whose tail stayed on the near side");
	}

	private static long tiles(List<SongBuilder.EventNote> notes, int[] size, boolean everyHeadedCut) {
		SongBuilder.TILES_EVERY_HEADED_CUT = everyHeadedCut;
		try {
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, size[0], size[1]))
				.commands().stream().filter(command -> command.contains("deepslate_tiles")).count();
		} catch (RuntimeException refused) {
			return -1;
		}
	}
}
