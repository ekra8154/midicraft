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
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the runs in a build are actually made of.
 *
 * <p>Three of the readings off the world are counting questions -- how many path columns give their
 * centre away when the chord had a harp for it, and how many floor rails are carried the whole
 * length of a run without ever holding a note. Neither shows up as a wrong note or a breach, so
 * nothing here would have said so; this prints the shape of every run in every song so a claim
 * about them has a number behind it.</p>
 */
@Tag("sweep")
class RailCensusTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static final List<String> SUBJECTS =
		List.of("ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed",
			"song-of-storms-but-noteblocks-dont-kill-me", "lady-brown-nujabes");

	@Test
	void countsWhatEveryRunIsMadeOf() throws Exception {
		Map<String, Integer> all = new TreeMap<>();
		for (String name : SUBJECTS) {
			List<SongBuilder.EventNote> notes = load(name);
			Map<String, Integer> song = new TreeMap<>();
			for (int floors : new int[] {2, 5, 9}) {
				for (int width : new int[] {16, 24, 40}) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(16, width, floors));
					plan.padding().forEach((key, count) -> {
						if (key.startsWith("rail")) {
							song.merge(key, count, Integer::sum);
							all.merge(key, count, Integer::sum);
						}
					});
				}
			}
			System.out.println("CENSUS " + name + " " + song);
		}
		System.out.println("CENSUS all songs " + all);
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
