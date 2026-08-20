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
 * Why a chord that fits a stacked-bus is built as a plain bus, on the Hammer of Justice.
 *
 * <p>The same chord pasted in the air came out a stacked-bus of seven columns against
 * the nine the build gave it, so the shape is available and something declined it. Every downgrade
 * counts itself in the padding map; this prints them, and then the chords themselves.</p>
 */
@Tag("sweep")
class HammerBusTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.TRACE = false;
		SongBuilder.NUDGE_WHEN_BEHIND_BUSY = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void namesEveryReasonAStackedShapeWasGivenUp() throws Exception {
		SongBuilder.PastePlan plan = build(load("hammer-of-justice-2"));
		System.out.println("HAMMER blocks=" + plan.commands().size()
			+ " breaches=" + plan.breaches().size() + " wrong=" + plan.wrongNotes());
		plan.padding().entrySet().stream()
			.filter(pad -> pad.getKey().startsWith("plan"))
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(pad -> System.out.println("HAMMER pad " + pad.getKey() + " = " + pad.getValue()));
	}

	/** Standing a column off the lane behind, against giving the head up, on this one build. */
	@Test
	void pricesTheShiftAgainstGivingUpTheHead() throws Exception {
		List<SongBuilder.EventNote> notes = load("hammer-of-justice-2");
		SongBuilder.NUDGE_WHEN_BEHIND_BUSY = false;
		SongBuilder.PastePlan without = build(notes);
		SongBuilder.NUDGE_WHEN_BEHIND_BUSY = true;
		SongBuilder.PastePlan with = build(notes);
		System.out.println("HAMMER shift off: " + describe(without));
		System.out.println("HAMMER shift on : " + describe(with));
	}

	private static String describe(SongBuilder.PastePlan plan) {
		return "blocks=" + plan.commands().size() + " spanZ=" + plan.spanZ()
			+ " breaches=" + plan.breaches().size()
			+ " breachBlocks=" + plan.breaches().stream().mapToInt(Integer::intValue).sum()
			+ " wrong=" + plan.wrongNotes();
	}

	/** And the chords themselves, so a bus in the build can be matched to the line that made it. */
	@Test
	void tracesEveryChord() throws Exception {
		List<SongBuilder.EventNote> notes = load("hammer-of-justice-2");
		SongBuilder.TRACE = true;
		build(notes);
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 24, 4));
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
