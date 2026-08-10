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
		SongBuilder.BACK_PAIR_FREE_AFTER_A_STAIRCASE = true;
		SongBuilder.KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER = true;
		SongBuilder.RELOCATES_CONTESTED_NOTE = true;
		SongBuilder.BACK_FLANK_AWAY_FROM_NEXT_LANE = true;
		SongBuilder.NUDGE_WHEN_BEHIND_BUSY = true;
		SongBuilder.BACK_PAIR_FREE_AFTER_A_TURN = false;
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
				SongBuilder.BACK_PAIR_FREE_AFTER_A_STAIRCASE = climb;
				SongBuilder.CENTRE_TAKES_A_SPARE_HARP = swap;
				String arm = "stairPair=" + (climb ? "on " : "off") + " swap=" + (swap ? "on " : "off");
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
					SongBuilder.BACK_PAIR_FREE_AFTER_A_STAIRCASE = climb;
					SongBuilder.CENTRE_TAKES_A_SPARE_HARP = swap;
					SongBuilder.PastePlan plan = build(notes);
					System.out.println("GUARDIAN marks stairPair=" + (climb ? "on " : "off")
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
				|| pad.getKey().startsWith("planRelocate") || pad.getKey().startsWith("planShift") || pad.getKey().startsWith("planKept"))
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(pad -> System.out.println("GUARDIAN pad " + pad.getKey() + " = " + pad.getValue()));
	}

	/** Every event that wanted to turn, so a lane's run past its wall can be read chord by chord. */
	@Test
	void tracesEveryTurnItWanted() throws Exception {
		SongBuilder.TRACE_TURNS = true;
		try {
			build(load("deltarune-ch-4-guardian"));
		} finally {
			SongBuilder.TRACE_TURNS = false;
		}
	}

	/** And the same on the rules as they stood before any of this week's changes. */
	@Test
	void tracesEveryTurnItWantedOnTheOldRules() throws Exception {
		SongBuilder.RELOCATES_CONTESTED_NOTE = false;
		SongBuilder.BACK_FLANK_AWAY_FROM_NEXT_LANE = false;
		SongBuilder.NUDGE_WHEN_BEHIND_BUSY = false;
		SongBuilder.BACK_PAIR_FREE_AFTER_A_STAIRCASE = false;
		SongBuilder.TRACE_TURNS = true;
		try {
			build(load("deltarune-ch-4-guardian"));
		} finally {
			SongBuilder.TRACE_TURNS = false;
			SongBuilder.RELOCATES_CONTESTED_NOTE = true;
			SongBuilder.BACK_FLANK_AWAY_FROM_NEXT_LANE = true;
			SongBuilder.NUDGE_WHEN_BEHIND_BUSY = true;
		SongBuilder.BACK_PAIR_FREE_AFTER_A_TURN = false;
		}
	}

	/** Guardian across every width and floor count, this week's rules against last week's. */
	@Test
	void sweepsGuardianEveryWidthAndFloor() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean now : new boolean[] {false, true}) {
			SongBuilder.RELOCATES_CONTESTED_NOTE = now;
			SongBuilder.BACK_FLANK_AWAY_FROM_NEXT_LANE = now;
			SongBuilder.NUDGE_WHEN_BEHIND_BUSY = now;
			SongBuilder.BACK_PAIR_FREE_AFTER_A_STAIRCASE = now;
			SongBuilder.KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER = now;
			int built = 0;
			int refused = 0;
			int breaches = 0;
			int breachBlocks = 0;
			int worst = 0;
			int clean = 0;
			long blocks = 0;
			long span = 0;
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					try {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						built++;
						breaches += plan.breaches().size();
						clean += plan.breaches().isEmpty() ? 1 : 0;
						for (int breach : plan.breaches()) {
							breachBlocks += breach;
							worst = Math.max(worst, breach);
						}
						blocks += plan.commands().size();
						span += plan.spanZ();
					} catch (RuntimeException no) {
						refused++;
					}
				}
			}
			System.out.println("GUARDIAN sweep " + (now ? "thisWeek" : "lastWeek")
				+ " built=" + built + " refused=" + refused + " cleanBuilds=" + clean
				+ " breaches=" + breaches + " breachBlocks=" + breachBlocks + " worst=" + worst
				+ " blocks=" + blocks + " spanZ=" + span);
		}
	}

	/** ekran's chord at 40 61 16, Guardian 44 wide over three floors, and the one before it. */
	@Test
	void tracesGuardianFortyFourByThree() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		SongBuilder.TRACE = true;
		try {
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 44, 3));
		} finally {
			SongBuilder.TRACE = false;
		}
	}

	/** Letting the first chord out of a bend keep its back pair, priced on ekran's own build. */
	@Test
	void pricesTheBackPairAfterATurn() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean free : new boolean[] {false, true}) {
			SongBuilder.BACK_PAIR_FREE_AFTER_A_TURN = free;
			SongBuilder.MARK_COLLISIONS = true;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 44, 3));
				System.out.println("TURNPAIR free=" + (free ? "on " : "off")
					+ " marks=" + plan.collisions().size() + " " + describe(plan));
				plan.collisions().entrySet().stream().limit(6)
					.forEach(mark -> System.out.println("TURNPAIR   " + mark));
			} catch (RuntimeException refused) {
				System.out.println("TURNPAIR free=" + (free ? "on " : "off") + " REFUSED: "
					+ refused.getMessage());
			} finally {
				SongBuilder.MARK_COLLISIONS = false;
			}
		}
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
