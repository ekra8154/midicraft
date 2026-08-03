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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the breach that is left once Guardian is set aside.
 *
 * <p>Across every real song at every width and up to eight floors there are seventy-six breaching
 * builds, and sixty-nine of them are Guardian. The other seven are all one shape and mostly one
 * song: a lane that reaches its wall, meets a chord too big to lie across the corner, and runs on
 * past the wall looking for one that fits.</p>
 *
 * <p>What is wanted from this is not the count -- {@code BreachPickTest} has that -- but which of
 * the four gates said no, which is why it runs with {@link SongBuilder#TRACE_TURNS} on.</p>
 */
@Tag("sweep")
class TurnShortTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.TRACE_TURNS = false;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
		return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	/** The worst of the seven: five columns past the wall, on the narrowest build of the song. */
	@Test
	void tracesTheTurnsAroundTheBreach() throws Exception {
		List<SongBuilder.EventNote> notes = load("illit-do-the-dance");
		SongBuilder.TRACE_TURNS = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 12, 2));
		SongBuilder.TRACE_TURNS = false;
		System.out.println("SHORT illit w12 f2: " + plan.spanZ() + " deep, "
			+ plan.breaches().size() + " breaching lanes, worst " + plan.worstBreach()
			+ ", wrong " + plan.wrongNotes());
		for (String fault : plan.faults()) {
			System.out.println("SHORT fault: " + fault);
		}
	}

	/** And the same for the one case that is not this song, in case it is not the same shape. */
	@Test
	void tracesTheChainsawCase() throws Exception {
		List<SongBuilder.EventNote> notes = load("chainsaw-man-op-kenshi-yonezu-kick-back");
		SongBuilder.TRACE_TURNS = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 20, 2));
		SongBuilder.TRACE_TURNS = false;
		System.out.println("CHAIN kick-back w20 f2: " + plan.spanZ() + " deep, "
			+ plan.breaches().size() + " breaching lanes, worst " + plan.worstBreach()
			+ ", wrong " + plan.wrongNotes());
		for (String fault : plan.faults()) {
			System.out.println("CHAIN fault: " + fault);
		}
	}
}
