package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where Guardian's biggest breach is right now, in coordinates somebody can stand on.
 *
 * <p>Every number in the handoff is a total. A total says how much is wrong and nothing about
 * where, and a rule that takes breaches to nought has to be derived from one lane that can be
 * looked at. This finds the worst single breach over the whole Guardian sweep, then prints the
 * config, the chord that walked out, and every block of the build standing past its far wall --
 * space-separated, so a line can go straight into {@code /tp}.</p>
 */
@Tag("sweep")
class GuardianBreachReproProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> guardian() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "midicraft", "songs");
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
				BreachView.songFile("deltarune-ch-4-guardian"))) {
			com.fastnoteblocks.client.composer.ComposerProject raw =
				new com.google.gson.Gson().fromJson(reader,
					com.fastnoteblocks.client.composer.ComposerProject.class);
			com.fastnoteblocks.client.composer.ComposerProject song =
				new com.fastnoteblocks.client.composer.ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	private record Config(int width, int floors, int worst, int breaches, int blocks) {
	}

	/** The whole sweep, worst single breach first, so the repro below is not cherry-picked. */
	@Test
	void ranksEveryGuardianSizeByItsWorstBreach() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		List<Config> ranked = new ArrayList<>();
		for (int floors = 2; floors <= 6; floors++) {
			for (int width = 12; width <= 48; width += 4) {
				try {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					ranked.add(new Config(width, floors,
						plan.breaches().stream().mapToInt(Integer::intValue).max().orElse(0),
						plan.breaches().size(),
						plan.breaches().stream().mapToInt(Integer::intValue).sum()));
				} catch (RuntimeException refused) {
					System.out.println("RANK " + width + "w x " + floors + "f  REFUSED");
				}
			}
		}
		ranked.sort((a, b) -> b.worst() != a.worst() ? Integer.compare(b.worst(), a.worst())
			: Integer.compare(b.blocks(), a.blocks()));
		for (Config config : ranked) {
			System.out.println("RANK " + config.width() + "w x " + config.floors() + "f  worst="
				+ config.worst() + " breaches=" + config.breaches() + " blocks=" + config.blocks());
		}
	}

	/**
	 * The worst breach in the sweep, dumped where it stands.
	 *
	 * <p>Set the two constants from the top line of {@link #ranksEveryGuardianSizeByItsWorstBreach}.
	 * Paste at {@code 0 64 0} and every coordinate printed here is the coordinate in the world.</p>
	 */
	private static final int REPRO_WIDTH = 28;
	private static final int REPRO_FLOORS = 3;

	@Test
	void dumpsTheWorstBreachInCoordinates() throws Exception {
		for (int[] config : new int[][] {{28, 3}, {24, 5}}) {
			dump(config[0], config[1]);
		}
	}

	private static void dump(int width, int floors) throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, width, floors));
		System.out.println("REPRO config " + width + "w x " + floors + "f  nearWall="
			+ plan.nearWall() + " farWall=" + plan.farWall() + " spanX=" + plan.spanX()
			+ " spanZ=" + plan.spanZ() + " blocks=" + plan.commands().size());
		System.out.println("REPRO breaches " + plan.breaches());
		for (String fault : plan.faults()) {
			if (fault.startsWith("a lane turned -")) {
				System.out.println("REPRO fault " + fault);
			}
		}
		// Every block outside either wall, gathered into the lane it belongs to. A lane is one y and
		// one z, so that pair names the run, and the furthest block in it is how far the lane got.
		//
		// Both sides. A lane travels each way in turn, so a breach is as likely to run out past the
		// near wall as the far one -- and where the plan slides, the near side is where it lands. A
		// column of sixteen blocks a long way outside is a lane, not the machine's head: the head is
		// a fixed structure a few columns wide and it does not run for eleven of them.
		Map<String, int[]> runs = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			int out = x < plan.nearWall() ? plan.nearWall() - x
				: x > plan.farWall() ? x - plan.farWall() : 0;
			if (out == 0) {
				continue;
			}
			String key = y + " " + z;
			int[] seen = runs.get(key);
			if (seen == null || out > seen[0]) {
				runs.put(key, new int[] {out, x, y, z});
			}
		}
		System.out.println("REPRO runs outside the walls: " + runs.size());
		runs.values().stream()
			.sorted((a, b) -> Integer.compare(b[0], a[0]))
			.limit(12)
			.forEach(run -> System.out.println("    out=" + run[0] + " columns   tp " + run[1] + " "
				+ run[2] + " " + run[3]));
	}

	/**
	 * The repro at the {@code maxBuildFloors} the library actually e set, not the sweep's.
	 *
	 * <p>Every table in this file sweeps with {@code maxFloors=4} and the live config says 16. A
	 * repro they cannot paste is not a repro, so the config the coordinates belong to has to be the
	 * one they would type.</p>
	 */
	@Test
	void checksTheReproSurvivesTheLiveMaxFloors() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (int max : new int[] {4, 16}) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(max, REPRO_WIDTH, REPRO_FLOORS));
			int far = 0;
			for (String command : plan.commands()) {
				far = Math.max(far, Integer.parseInt(command.split(" ")[1]));
			}
			System.out.println("MAXFLOORS " + max + "  breaches=" + plan.breaches()
				+ " near=" + plan.nearWall() + " far=" + plan.farWall() + " blocksReach=" + far
				+ " cmds=" + plan.commands().size());
		}
	}

	/** The shed-flank rule off against on: the repro first, then every Guardian size. */
	@Test
	void pricesTheShedFlank() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean sheds : new boolean[] {false, true}) {
			SongBuilder.SHEDS_THE_FLANK_THE_DESCENT_WANTS = sheds;
			try {
				for (int[] config : new int[][] {{40, 5}, {36, 2}, {24, 3}, {44, 3}}) {
					try {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, config[0], config[1]));
						System.out.println("SHED sheds=" + sheds + " " + config[0] + "w x "
							+ config[1] + "f breaches=" + plan.breaches() + " wrong="
							+ plan.wrongNotes() + " cmds=" + plan.commands().size()
							+ " spanZ=" + plan.spanZ()
							+ " shedCuts=" + plan.padding().getOrDefault("busHandoverShed", 0)
							+ " overran=" + plan.padding().entrySet().stream()
								.filter(e -> e.getKey().startsWith("overran"))
								.mapToInt(Map.Entry::getValue).sum());
					} catch (RuntimeException no) {
						System.out.println("SHED sheds=" + sheds + " " + config[0] + "w x "
							+ config[1] + "f REFUSED: " + no.getMessage());
					}
				}
				int built = 0;
				int refused = 0;
				int clean = 0;
				int breaches = 0;
				int breachBlocks = 0;
				int worst = 0;
				long wrong = 0;
				long blocks = 0;
				long overran = 0;
				long shedCuts = 0;
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							built++;
							clean += plan.breaches().isEmpty() ? 1 : 0;
							breaches += plan.breaches().size();
							for (int breach : plan.breaches()) {
								breachBlocks += breach;
								worst = Math.max(worst, breach);
							}
							wrong += plan.wrongNotes();
							blocks += plan.commands().size();
							shedCuts += plan.padding().getOrDefault("busHandoverShed", 0);
							overran += plan.padding().entrySet().stream()
								.filter(e -> e.getKey().startsWith("overran"))
								.mapToInt(Map.Entry::getValue).sum();
						} catch (RuntimeException no) {
							refused++;
						}
					}
				}
				System.out.println("SHEDSWEEP sheds=" + sheds + " built=" + built
					+ " refused=" + refused + " clean=" + clean + " breaches=" + breaches
					+ " breachBlocks=" + breachBlocks + " worst=" + worst + " wrong=" + wrong
					+ " blocks=" + blocks + " shedCuts=" + shedCuts + " overran=" + overran);
			} finally {
				SongBuilder.SHEDS_THE_FLANK_THE_DESCENT_WANTS = true;
			}
		}
	}

	/**
	 * What shape each lane comes to rest on in front of a staircase, over the whole library.
	 *
	 * <p>Asked before writing the plain-chord half of the shed rule, because a rule with no site to
	 * fire at is a day spent on the front-slot case. The walk already books this: every handover
	 * records {@code planLaneEndedOn<style><turn>}.</p>
	 */
	@Test
	void censusesWhatLanesEndOnInFrontOfAStaircase() throws Exception {
		Map<String, Integer> total = new java.util.TreeMap<>();
		List<java.nio.file.Path> files;
		try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(
				java.nio.file.Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		for (java.nio.file.Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = songAt(file);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 16; width <= 48; width += 8) {
					try {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						plan.padding().forEach((key, count) -> {
							if (key.startsWith("planLaneEndedOn") || key.startsWith("busHandoverShed")
									|| key.startsWith("shed")) {
								total.merge(key, count, Integer::sum);
							}
						});
					} catch (RuntimeException refused) {
						total.merge("REFUSED", 1, Integer::sum);
					}
				}
			}
		}
		total.forEach((key, count) -> System.out.println("ENDSON " + key + " = " + count));
	}

	private static List<SongBuilder.EventNote> songAt(java.nio.file.Path file) throws Exception {
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
			com.fastnoteblocks.client.composer.ComposerProject raw =
				new com.google.gson.Gson().fromJson(reader,
					com.fastnoteblocks.client.composer.ComposerProject.class);
			com.fastnoteblocks.client.composer.ComposerProject song =
				new com.fastnoteblocks.client.composer.ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	/**
	 * The shed rule over every song somebody actually wrote, not only Guardian.
	 *
	 * <p>The point of the exercise is a general rule. Guardian is where the fault was read off, and a
	 * change measured only there is a change tuned to one song -- which is what
	 * {@code HeldOutWidthTest} exists to catch and what this answers before it has to.</p>
	 */
	@Test
	void pricesTheShedFlankOverTheWholeLibrary() throws Exception {
		List<java.nio.file.Path> files;
		try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(
				java.nio.file.Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Map<String, int[]> perSong = new LinkedHashMap<>();
		for (boolean sheds : new boolean[] {false, true}) {
			SongBuilder.SHEDS_THE_FLANK_THE_DESCENT_WANTS = sheds;
			try {
				int built = 0;
				int refused = 0;
				int clean = 0;
				int breaches = 0;
				int breachBlocks = 0;
				int worst = 0;
				long wrong = 0;
				long overran = 0;
				for (java.nio.file.Path file : files) {
					String name = file.getFileName().toString().replace(".json", "");
					if (name.startsWith("ultra-")) {
						continue;
					}
					List<SongBuilder.EventNote> notes = songAt(file);
					if (notes.isEmpty()) {
						continue;
					}
					int songBreaches = 0;
					int songBlocks = 0;
					for (int floors = 1; floors <= 6; floors++) {
						for (int width = 12; width <= 48; width += 4) {
							try {
								SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
									new BlockPos(0, 64, 0), notes,
									SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
									new SongBuilder.BuildLimits(4, width, floors));
								built++;
								clean += plan.breaches().isEmpty() ? 1 : 0;
								breaches += plan.breaches().size();
								songBreaches += plan.breaches().size();
								for (int breach : plan.breaches()) {
									breachBlocks += breach;
									songBlocks += breach;
									worst = Math.max(worst, breach);
								}
								wrong += plan.wrongNotes();
								overran += plan.padding().entrySet().stream()
									.filter(e -> e.getKey().startsWith("overran"))
									.mapToInt(Map.Entry::getValue).sum();
							} catch (RuntimeException no) {
								refused++;
							}
						}
					}
					int[] row = perSong.computeIfAbsent(name, key -> new int[4]);
					row[sheds ? 2 : 0] = songBreaches;
					row[sheds ? 3 : 1] = songBlocks;
				}
				System.out.println("LIB sheds=" + sheds + " built=" + built + " refused=" + refused
					+ " clean=" + clean + " breaches=" + breaches + " breachBlocks=" + breachBlocks
					+ " worst=" + worst + " wrong=" + wrong + " overran=" + overran);
			} finally {
				SongBuilder.SHEDS_THE_FLANK_THE_DESCENT_WANTS = true;
			}
		}
		perSong.forEach((name, row) -> {
			if (row[0] != row[2] || row[1] != row[3]) {
				System.out.println("LIBSONG " + name + "  breaches " + row[0] + " -> " + row[2]
					+ "   blocks " + row[1] + " -> " + row[3]);
			}
		});
	}

	/**
	 * Which songs are clean, and at which widths, with the rule off and on.
	 *
	 * <p>The library is remembered at nought breaches for everything but Guardian. The sweep says
	 * otherwise with the rule <em>off</em>, which is main -- so either the memory is of a narrower
	 * band of configurations than a sweep from twelve wide over one floor, or it is of a different
	 * build. Worth settling before anything is blamed on the shed rule: a config band nobody would
	 * ever paste drowns out the ones somebody would.</p>
	 */
	@Test
	void findsTheBandWhereTheLibraryIsClean() throws Exception {
		List<java.nio.file.Path> files;
		try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(
				java.nio.file.Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		// Bands worth telling apart: everything, then what somebody would actually paste.
		int[][] bands = {{12, 48, 1, 6}, {24, 48, 2, 6}, {40, 48, 2, 4}, {44, 44, 3, 3}};
		for (int[] band : bands) {
			for (boolean sheds : new boolean[] {false, true}) {
				SongBuilder.SHEDS_THE_FLANK_THE_DESCENT_WANTS = sheds;
				try {
					int breaches = 0;
					int blocks = 0;
					List<String> dirty = new ArrayList<>();
					for (java.nio.file.Path file : files) {
						String name = file.getFileName().toString().replace(".json", "");
						if (name.startsWith("ultra-")) {
							continue;
						}
						List<SongBuilder.EventNote> notes = songAt(file);
						if (notes.isEmpty()) {
							continue;
						}
						int songBreaches = 0;
						for (int floors = band[2]; floors <= band[3]; floors++) {
							for (int width = band[0]; width <= band[1]; width += 4) {
								try {
									SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
										new BlockPos(0, 64, 0), notes,
										SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
										new SongBuilder.BuildLimits(4, width, floors));
									songBreaches += plan.breaches().size();
									blocks += plan.breaches().stream()
										.mapToInt(Integer::intValue).sum();
								} catch (RuntimeException refused) {
									// counted by absence
								}
							}
						}
						breaches += songBreaches;
						if (songBreaches > 0) {
							dirty.add(name + "=" + songBreaches);
						}
					}
					System.out.println("BAND w" + band[0] + "-" + band[1] + " f" + band[2] + "-"
						+ band[3] + " sheds=" + sheds + " breaches=" + breaches + " blocks=" + blocks
						+ " dirtySongs=" + dirty.size() + "  " + dirty);
				} finally {
					SongBuilder.SHEDS_THE_FLANK_THE_DESCENT_WANTS = true;
				}
			}
		}
	}

	/**
	 * Does the plan predict the tip the walk ends a climb with, once the pad is raised?
	 *
	 * <p>{@code gradeLaneTip} already books the answer: it compares {@code DUST_RANGE - turnCells}
	 * against what the walk actually handed on and files the difference under
	 * {@code planTipRich}/{@code planTipPoor}. A raised pad hands on two more than the old sum, so
	 * if the planner has not understood the change these counts move to Rich by exactly two -- and
	 * that is the plan promising a lane less wire than it gets, which moves every close decision.</p>
	 */
	@Test
	void checksThePlanPredictsTheRaisedTip() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean plans : new boolean[] {false, true}) {
			SongBuilder.PLANS_THE_RAISED_PAD = plans;
			try {
				Map<String, Integer> total = new java.util.TreeMap<>();
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						plan.padding().forEach((key, count) -> {
							if (key.startsWith("planTip") && key.endsWith("Climb")
									|| key.startsWith("padClosingRaised")
									|| key.startsWith("padPinnedRaised")) {
								total.merge(key, count, Integer::sum);
							}
						});
					}
				}
				System.out.println("TIP plans=" + plans + "  " + total);
			} finally {
				SongBuilder.PLANS_THE_RAISED_PAD = true;
			}
		}
	}

	/** Where a raised pad actually is, so it can be stood in front of rather than reasoned about. */
	@Test
	void findsARaisedPadToStandAt() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (int[] config : new int[][] {{44, 3}, {16, 3}, {24, 3}, {32, 4}}) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, config[0], config[1]));
			int raised = plan.padding().getOrDefault("padClosingRaised", 0)
				+ plan.padding().getOrDefault("padPinnedRaised", 0);
			// A raised pad is the one place in an ultra build where stone stands with nothing at all
			// underneath it: every other floor block sits on the lane. So look for stone with dust
			// over it and air below, which no other shape lays.
			Map<String, String> block = new java.util.HashMap<>();
			for (String command : plan.commands()) {
				String[] word = command.split(" ");
				block.put(word[1] + " " + word[2] + " " + word[3], word[4]);
			}
			List<String> found = new ArrayList<>();
			for (Map.Entry<String, String> cell : block.entrySet()) {
				if (!"minecraft:stone".equals(cell.getValue())) {
					continue;
				}
				String[] at = cell.getKey().split(" ");
				int x = Integer.parseInt(at[0]);
				int y = Integer.parseInt(at[1]);
				int z = Integer.parseInt(at[2]);
				if ("minecraft:redstone_wire".equals(block.get(x + " " + (y + 1) + " " + z))
						&& !block.containsKey(x + " " + (y - 1) + " " + z)
						&& !block.containsKey(x + " " + (y - 2) + " " + z)) {
					found.add(x + " " + (y + 1) + " " + z);
				}
			}
			found.sort(null);
			System.out.println("RAISEDAT " + config[0] + "w x " + config[1] + "f  booked=" + raised
				+ " floatingStone=" + found.size()
				+ (found.isEmpty() ? "" : "  first: tp " + found.get(0)));
		}
	}

	/**
	 * The raised pad against no raised pad, over Guardian and over the library, three arms.
	 *
	 * <p>Measured after the machine was made to conduct, so unlike everything quoted before
	 * {@code ae8c942} these are numbers about layout rather than about a dead staircase.</p>
	 */
	@Test
	void pricesTheRaisedPadThreeWays() throws Exception {
		List<java.nio.file.Path> files;
		try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(
				java.nio.file.Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		String[] names = {"off                    ", "build only             ",
			"build + price          ", "build + price + plan   "};
		boolean[][] arms = {{false, false, false}, {true, false, false}, {true, true, false},
			{true, true, true}};
		for (int arm = 0; arm < arms.length; arm++) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = arms[arm][0];
			SongBuilder.PRICES_THE_RAISED_PAD = arms[arm][1];
			SongBuilder.PLANS_THE_RAISED_PAD = arms[arm][2];
			try {
				int gBreaches = 0;
				int gBlocks = 0;
				int gWorst = 0;
				List<SongBuilder.EventNote> guardian = guardian();
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), guardian,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						gBreaches += plan.breaches().size();
						for (int breach : plan.breaches()) {
							gBlocks += breach;
							gWorst = Math.max(gWorst, breach);
						}
					}
				}
				int lBreaches = 0;
				int lBlocks = 0;
				int lClean = 0;
				int lBuilt = 0;
				int lRefused = 0;
				long lWrong = 0;
				for (java.nio.file.Path file : files) {
					String name = file.getFileName().toString().replace(".json", "");
					if (name.startsWith("ultra-")) {
						continue;
					}
					List<SongBuilder.EventNote> notes = songAt(file);
					if (notes.isEmpty()) {
						continue;
					}
					for (int floors = 1; floors <= 6; floors++) {
						for (int width = 12; width <= 48; width += 4) {
							try {
								SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
									new BlockPos(0, 64, 0), notes,
									SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
									new SongBuilder.BuildLimits(4, width, floors));
								lBuilt++;
								lClean += plan.breaches().isEmpty() ? 1 : 0;
								lBreaches += plan.breaches().size();
								lBlocks += plan.breaches().stream()
									.mapToInt(Integer::intValue).sum();
								lWrong += plan.wrongNotes();
							} catch (RuntimeException refused) {
								lRefused++;
							}
						}
					}
				}
				System.out.println("ARM " + names[arm]
					+ " | guardian breaches " + gBreaches + " blocks " + gBlocks
					+ " worst " + gWorst
					+ " | library breaches " + lBreaches + " blocks " + lBlocks
					+ " clean " + lClean + "/" + lBuilt + " refused " + lRefused
					+ " wrong " + lWrong);
			} finally {
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
				SongBuilder.PRICES_THE_RAISED_PAD = true;
				SongBuilder.PLANS_THE_RAISED_PAD = true;
			}
		}
	}

	/**
	 * What the walk did differently, key by key, with the raised ascent off and on.
	 *
	 * <p>The geometry is proven free and the two halves are proven to agree, so whatever costs 162
	 * breaches is a decision the walk makes differently -- and every decision it makes is booked
	 * under some {@code padding} key. Diffing the census names it without a theory.</p>
	 */
	@Test
	void diffsTheCensusAcrossTheRaisedAscent() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		Map<String, Integer>[] arms = new Map[2];
		int[] turns = new int[2];
		int[] breaches = new int[2];
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = arm == 1;
			try {
				Map<String, Integer> total = new java.util.TreeMap<>();
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						plan.padding().forEach((key, count) -> total.merge(key, count, Integer::sum));
						turns[arm] += plan.turns().size();
						breaches[arm] += plan.breaches().size();
					}
				}
				arms[arm] = total;
			} finally {
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
			}
		}
		System.out.println("CENSUS turns " + turns[0] + " -> " + turns[1]
			+ "   breaches " + breaches[0] + " -> " + breaches[1]);
		java.util.TreeSet<String> keys = new java.util.TreeSet<>(arms[0].keySet());
		keys.addAll(arms[1].keySet());
		List<String> moved = new ArrayList<>();
		for (String key : keys) {
			int off = arms[0].getOrDefault(key, 0);
			int on = arms[1].getOrDefault(key, 0);
			if (off != on) {
				moved.add(String.format("%+7d  %-42s %d -> %d", on - off, key, off, on));
			}
		}
		moved.sort((a, b) -> Integer.compare(
			Math.abs(Integer.parseInt(b.substring(0, 7).trim())),
			Math.abs(Integer.parseInt(a.substring(0, 7).trim()))));
		moved.stream().limit(10).forEach(line -> System.out.println("CENSUS " + line));
		// The keys that say where the staircase actually stood, whether they moved or not: a climb
		// set back from its wall is the one thing that puts the next lane somewhere nobody predicted.
		for (String key : keys) {
			if (key.startsWith("padPinned") || key.startsWith("recessed")
					|| key.startsWith("planStart") && key.endsWith("Climb")) {
				System.out.println("WHERE " + String.format("%-40s %6d -> %6d", key,
					arms[0].getOrDefault(key, 0), arms[1].getOrDefault(key, 0)));
			}
		}
	}

	/** Which configurations pay for the raised ascent, so one of them can be traced. */
	@Test
	void findsWhichConfigsPayForTheRaisedAscent() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		Map<String, int[]> byConfig = new LinkedHashMap<>();
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = arm == 1;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						byConfig.computeIfAbsent(width + "w x " + floors + "f",
							key -> new int[2])[arm] = plan.breaches().size();
					}
				}
			} finally {
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
			}
		}
		byConfig.entrySet().stream()
			.sorted((a, b) -> Integer.compare(b.getValue()[1] - b.getValue()[0],
				a.getValue()[1] - a.getValue()[0]))
			.limit(10)
			.forEach(entry -> System.out.println("PAYS " + entry.getKey() + "  "
				+ entry.getValue()[0] + " -> " + entry.getValue()[1]
				+ "   (" + (entry.getValue()[1] - entry.getValue()[0] > 0 ? "+" : "")
				+ (entry.getValue()[1] - entry.getValue()[0]) + ")"));
	}

	/** The first turn the two builds put in different places, at the config that pays most. */
	@Test
	void findsWhereTheTwoBuildsFirstDiverge() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		List<net.minecraft.core.BlockPos>[] turns = new List[2];
		List<String>[] commands = new List[2];
		for (int arm = 0; arm < 2; arm++) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = arm == 1;
			SongBuilder.TRACE_TURNS = true;
			System.out.println("ARMSTART " + arm);
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, 20, 3));
				SongBuilder.TRACE_TURNS = false;
				turns[arm] = plan.turns();
				commands[arm] = plan.commands();
				System.out.println("DIVERGE arm=" + arm + " turns=" + plan.turns().size()
					+ " breaches=" + plan.breaches() + " cmds=" + plan.commands().size());
			} finally {
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
			}
		}
		for (int index = 0; index < Math.min(turns[0].size(), turns[1].size()); index++) {
			net.minecraft.core.BlockPos off = turns[0].get(index);
			net.minecraft.core.BlockPos on = turns[1].get(index);
			if (!off.equals(on)) {
				System.out.println("DIVERGE first differing turn #" + index
					+ "  off: " + off.getX() + " " + off.getY() + " " + off.getZ()
					+ "   on: " + on.getX() + " " + on.getY() + " " + on.getZ());
				for (int back = Math.max(0, index - 3); back <= index; back++) {
					System.out.println("    turn #" + back
						+ "  off " + turns[0].get(back).getX() + " " + turns[0].get(back).getY()
						+ " " + turns[0].get(back).getZ()
						+ "   on " + turns[1].get(back).getX() + " " + turns[1].get(back).getY()
						+ " " + turns[1].get(back).getZ());
				}
				return;
			}
		}
		System.out.println("DIVERGE turns identical up to " + Math.min(turns[0].size(),
			turns[1].size()));
	}

	/** The same build, walked, so the chord that went out and the one behind it can be read. */
	@Test
	void tracesTheWorstBreach() throws Exception {
		SongBuilder.TRACE_TURNS = true;
		try {
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, REPRO_WIDTH, REPRO_FLOORS));
		} finally {
			SongBuilder.TRACE_TURNS = false;
		}
	}

	/**
	 * How many blocks stand in each column, so a reported wall can be checked rather than trusted.
	 *
	 * <p>{@code reportsTheWallsTheBuildWasMeasuredAgainst} is one of the seven red tests, so
	 * {@code nearWall} and {@code farWall} are exactly the numbers not to take on faith. A wall the
	 * build genuinely respects shows up as a cliff in this histogram: thousands of blocks up to it
	 * and a handful past it. A wall that is merely misreported shows no cliff at all.</p>
	 */
	@Test
	void histogramsTheColumns() throws Exception {
		for (int[] config : new int[][] {{24, 5}, {28, 3}}) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				guardian(), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, config[0], config[1]));
			Map<Integer, Integer> byColumn = new java.util.TreeMap<>();
			for (String command : plan.commands()) {
				byColumn.merge(Integer.parseInt(command.split(" ")[1]), 1, Integer::sum);
			}
			StringBuilder line = new StringBuilder();
			byColumn.forEach((x, count) -> line.append(' ').append(x).append(':').append(count));
			System.out.println("HIST " + config[0] + "w x " + config[1] + "f near=" + plan.nearWall()
				+ " far=" + plan.farWall() + " breaches=" + plan.breaches());
			System.out.println("HIST  " + line);
		}
	}

	/** Every block of the box around a breach, so the shape that walked out can be named. */
	@Test
	void dumpsTheBoxAroundTheBreach() throws Exception {
		box(24, 5, 23, 38, 62, 70, 130, 135);
	}

	private static void box(int width, int floors, int lowX, int highX, int lowY, int highY,
			int lowZ, int highZ) throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, width, floors));
		System.out.println("BOX " + width + "w x " + floors + "f near=" + plan.nearWall()
			+ " far=" + plan.farWall() + " breaches=" + plan.breaches());
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			if (x >= lowX && x <= highX && y >= lowY && y <= highY && z >= lowZ && z <= highZ) {
				System.out.println("    " + x + " " + y + " " + z + "  " + word[4]
					+ (x > plan.farWall() ? "   <-- " + (x - plan.farWall()) + " past the far wall"
						: ""));
			}
		}
	}
}
