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
 * What the ultra lane costs a song of single notes today, so the two-rail shape has a number to
 * beat.
 *
 * <p>Length is the figure that matters: a run of single notes two ticks apart is a repeater and a
 * note block per event, and the whole claim of the two-rail shape is that it is one cell per event
 * instead. Blocks and breaches are printed beside it because a shorter build that breaches its
 * footprint has not won anything.</p>
 */
@Tag("sweep")
class OneNoteBaselineTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static final List<String> SUBJECTS =
		List.of("ultra-ones-gap2", "ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed");

	@Test
	void measuresTheOneNoteSongs() throws Exception {
		System.out.println(String.format("%-18s %5s %14s %14s %16s %10s",
			"song", "w/f", "spanZ off/on", "height off/on", "blocks off/on", "wrong on"));
		for (String name : SUBJECTS) {
			List<SongBuilder.EventNote> notes = load(name);
			for (int floors : new int[] {2, 5, 9}) {
				for (int width : new int[] {16, 24, 40}) {
					SongBuilder.TWO_RAIL_RUNS = false;
					SongBuilder.PastePlan off = plan(notes, width, floors);
					SongBuilder.TWO_RAIL_RUNS = true;
					SongBuilder.PastePlan on = plan(notes, width, floors);
					if (off == null || on == null) {
						System.out.println(String.format("%-18s %5s  REFUSED off=%s on=%s",
							name, width + "/" + floors, off != null, on != null));
						continue;
					}
					System.out.println(String.format("%-18s %5s %6d %7d %6d %7d %7d %8d %10d",
						name, width + "/" + floors, off.spanZ(), on.spanZ(),
						off.height(), on.height(), off.commands().size(), on.commands().size(),
						on.wrongNotes()));
				}
			}
		}
		SongBuilder.TWO_RAIL_RUNS = true;
	}

	private static SongBuilder.PastePlan plan(List<SongBuilder.EventNote> notes, int width,
			int floors) {
		try {
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, width, floors));
		} catch (RuntimeException refused) {
			return null;
		}
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
