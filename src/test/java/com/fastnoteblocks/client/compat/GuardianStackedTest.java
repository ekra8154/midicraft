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
 * The two things ekran read off a collision in Guardian at 32 wide over four floors.
 *
 * <p>A stacked chord of six with the centre standing as plain stone and a harp somewhere in it,
 * padded a column forward for parity anyway -- into a staircase, which is what turned a wasted
 * column into a collision. And, behind it, a stacked-bus that landed off an ascent with both back
 * cells plain air and took a head of five regardless, leaving its bus a cell longer than it had to
 * be.</p>
 */
@Tag("sweep")
class GuardianStackedTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.CENTRE_TAKES_A_SPARE_HARP = true;
		SongBuilder.BACK_PAIR_FREE_AFTER_CLIMB = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	/**
	 * Both changes, each way round, because they turned out to interact.
	 *
	 * <p>A refusal is a result here and not an error: with the pair freed after a climb and the swap
	 * withheld, this build does not paste at all.</p>
	 */
	@Test
	void pricesBothChangesEveryWayRound() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean climb : new boolean[] {false, true}) {
			for (boolean swap : new boolean[] {false, true}) {
				SongBuilder.BACK_PAIR_FREE_AFTER_CLIMB = climb;
				SongBuilder.CENTRE_TAKES_A_SPARE_HARP = swap;
				String arm = "climbPair=" + (climb ? "on " : "off") + " swap=" + (swap ? "on " : "off");
				try {
					System.out.println("GUARDIAN " + arm + " " + describe(build(notes)));
				} catch (RuntimeException refused) {
					System.out.println("GUARDIAN " + arm + " REFUSED: " + refused.getMessage());
				}
			}
		}
	}

	/** The collisions themselves, which are marked rather than rolled back when ekran reads one. */
	@Test
	void countsTheCollisionsEveryWayRound() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		SongBuilder.MARK_COLLISIONS = true;
		try {
			for (boolean climb : new boolean[] {false, true}) {
				for (boolean swap : new boolean[] {false, true}) {
					SongBuilder.BACK_PAIR_FREE_AFTER_CLIMB = climb;
					SongBuilder.CENTRE_TAKES_A_SPARE_HARP = swap;
					SongBuilder.PastePlan plan = build(notes);
					System.out.println("GUARDIAN marks climbPair=" + (climb ? "on " : "off")
						+ " swap=" + (swap ? "on " : "off")
						+ " collisions=" + plan.collisions().size() + " " + describe(plan));
					plan.collisions().entrySet().stream().limit(4)
						.forEach(mark -> System.out.println("GUARDIAN   " + mark));
				}
			}
		} finally {
			SongBuilder.MARK_COLLISIONS = false;
		}
	}

	/** Every reason a stacked shape was given up, and every relocation taken. */
	@Test
	void namesEveryDecision() throws Exception {
		SongBuilder.PastePlan plan = build(load("deltarune-ch-4-guardian"));
		System.out.println("GUARDIAN " + describe(plan));
		plan.padding().entrySet().stream()
			.filter(pad -> pad.getKey().startsWith("planBus") || pad.getKey().startsWith("planParity")
				|| pad.getKey().startsWith("planRelocate") || pad.getKey().startsWith("planShift"))
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(pad -> System.out.println("GUARDIAN pad " + pad.getKey() + " = " + pad.getValue()));
	}

	private static String describe(SongBuilder.PastePlan plan) {
		return "blocks=" + plan.commands().size() + " spanZ=" + plan.spanZ()
			+ " breaches=" + plan.breaches().size()
			+ " breachBlocks=" + plan.breaches().stream().mapToInt(Integer::intValue).sum()
			+ " wrong=" + plan.wrongNotes()
			+ " collisions=" + plan.padding().getOrDefault("planBusForCollision", 0)
			+ " swaps=" + plan.padding().getOrDefault("planRelocateToCentreSwap", 0)
			+ " toCentre=" + plan.padding().getOrDefault("planRelocateToCentre", 0)
			+ " toTail=" + plan.padding().getOrDefault("planRelocateToTail", 0)
			+ " nudges=" + (plan.padding().getOrDefault("planParityTight", 0)
				+ plan.padding().getOrDefault("planParityHadSlack", 0));
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 32, 4));
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
