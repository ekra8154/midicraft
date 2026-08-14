package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * That a run opening off a stacked chord plays the song, read out of the blocks.
 *
 * <p>Nothing else can say so. The six songs the shape was built against hold no chord big enough to
 * stack, so the transition never fires in any of their builds; and {@code wrongNotes} is a question
 * about neighbours rather than about whether the signal arrives on time. The library songs are far
 * bigger than anything read back here before, so this takes the smallest few that actually stack --
 * enough machine to hold the shape, little enough to simulate.</p>
 */
@Tag("sweep")
class RailStackReadBackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void readsBackTheSmallestSongsThatStack() throws Exception {
		var candidates = new TreeMap<Integer, String>();
		List<Path> songs = new ArrayList<>();
		try (var listing = Files.list(SONGS)) {
			listing.filter(path -> path.toString().endsWith(".json")).sorted().forEach(songs::add);
		}
		for (Path path : songs) {
			List<SongBuilder.EventNote> notes;
			try {
				notes = load(path);
			} catch (RuntimeException | java.io.IOException broken) {
				continue;
			}
			if (notes.size() >= 40 && notes.size() <= 900 && stacks(notes)) {
				candidates.put(notes.size(), path.getFileName().toString().replace(".json", ""));
			}
		}
		int read = 0;
		int broken = 0;
		for (String name : candidates.values()) {
			if (read >= 4) {
				break;
			}
			read++;
			List<SongBuilder.EventNote> notes = load(SONGS.resolve(name + ".json"));
			for (int[] size : new int[][] {{16, 2}, {24, 3}}) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, size[0], size[1]));
				int fromStack = plan.padding().getOrDefault("railFromStack", 0);
				String difference = RailReadBackTest.readBackDifference(plan, notes, name);
				if (!difference.isEmpty()) {
					broken++;
				}
				System.out.println("STACKREAD " + name + " (" + notes.size() + " notes) "
					+ size[0] + " wide over " + size[1] + ": " + fromStack + " runs off a stack, "
					+ (difference.isEmpty() ? "reads back as itself"
						: RailReadBackTest.readBackCensus(plan, notes, name)));
			}
		}
		System.out.println("STACKREAD " + broken + " of " + (read * 2)
			+ " builds did not read back as themselves");
	}

	/** Whether this song holds a chord big enough to be built as a stacked module. */
	private static boolean stacks(List<SongBuilder.EventNote> notes) {
		var perTick = new TreeMap<Integer, Integer>();
		for (SongBuilder.EventNote note : notes) {
			perTick.merge(note.time(), 1, Integer::sum);
		}
		return perTick.values().stream().anyMatch(count -> count >= 5);
	}

	private static List<SongBuilder.EventNote> load(Path path) throws java.io.IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
