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
 * Every size of the three one-note songs, so a failure has to be named rather than not looked for.
 *
 * <p>Nine sizes was what the shape was developed against, and nine sizes is a sample. This is the
 * whole grid the build menu can ask for, and it prints the ones that refuse, breach or sound a note
 * that is not theirs -- with the same grid run with the runs off beside it, because a size that was
 * already failing is not something this broke.</p>
 */
@Tag("sweep")
class RailGridTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void reportsEverySizeThatFails() throws Exception {
		for (String name : List.of("ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed",
			"song-of-storms-but-noteblocks-dont-kill-me", "lady-brown-nujabes")) {
			List<SongBuilder.EventNote> notes = load(name);
			int sizes = 0;
			int trouble = 0;
			for (int floors = 1; floors <= 12; floors++) {
				for (int width = 8; width <= 48; width += 2) {
					sizes++;
					SongBuilder.TWO_RAIL_RUNS = false;
					String off = outcome(notes, width, floors);
					SongBuilder.TWO_RAIL_RUNS = true;
					String on = outcome(notes, width, floors);
					if (on.isEmpty()) {
						continue;
					}
					trouble++;
					System.out.println("GRID " + name + " " + width + " wide over " + floors
						+ (floors == 1 ? " floor" : " floors") + ": " + on
						+ "   [runs off: " + (off.isEmpty() ? "clean" : off) + "]");
				}
			}
			System.out.println("GRID " + name + ": " + trouble + " of " + sizes + " sizes not clean");
		}
		SongBuilder.TWO_RAIL_RUNS = true;
	}

	/**
	 * And the same grid read back, because the plan check is exactly what missed a dead machine.
	 *
	 * <p>Sparser than the grid above only because reading a build costs far more than planning one.
	 * Every width the menu offers is still here, and both sides of the floor counts that change what
	 * a lane's wall is.</p>
	 */
	@Test
	void readsBackEverySizeOfTheGrid() throws Exception {
		for (String name : List.of("ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed",
			"song-of-storms-but-noteblocks-dont-kill-me", "lady-brown-nujabes")) {
			List<SongBuilder.EventNote> notes = load(name);
			int sizes = 0;
			int broken = 0;
			int brokenOff = 0;
			for (int floors : new int[] {1, 2, 3, 5, 9}) {
				for (int width = 8; width <= 48; width += 4) {
					sizes++;
					SongBuilder.RAIL_BLANKS_FOR_DELAY = false;
					if (!RailReadBackTest.readBackDifference(SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(16, width, floors)), notes, name).isEmpty()) {
						brokenOff++;
					}
					SongBuilder.RAIL_BLANKS_FOR_DELAY = true;
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(16, width, floors));
					String difference = RailReadBackTest.readBackDifference(plan, notes, name);
					if (difference.isEmpty()) {
						continue;
					}
					broken++;
					System.out.println("READ " + name + " " + width + " wide over " + floors
						+ (floors == 1 ? " floor" : " floors") + ": "
						+ RailReadBackTest.readBackCensus(plan, notes, name));
				}
			}
			System.out.println("READ " + name + ": " + broken + " of " + sizes
				+ " sizes did not read back as themselves, and " + brokenOff + " with delay blanks off");
		}
	}

	/** What is wrong with this build, or nothing at all. */
	private static String outcome(List<SongBuilder.EventNote> notes, int width, int floors) {
		SongBuilder.PastePlan plan;
		try {
			plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, width, floors));
		} catch (RuntimeException refused) {
			return "REFUSED " + String.valueOf(refused.getMessage()).replaceAll("-?\\d+", "#");
		}
		int breach = plan.breaches().stream().mapToInt(Integer::intValue).sum();
		int wrong = plan.wrongNotes();
		if (breach == 0 && wrong == 0 && plan.faults().isEmpty()) {
			return "";
		}
		return (breach > 0 ? "breach " + breach + " blocks in " + plan.breaches().size() + " lanes  " : "")
			+ (wrong > 0 ? "wrong " + wrong + "  " : "")
			+ (plan.faults().isEmpty() ? "" : plan.faults().size() + " faults");
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
