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
 * <p>Every number in the handoff is a total. A total says how much is wrong and nothing about where,
 * and a rule that takes breaches to nought has to be derived from one lane that can be looked at.
 * This finds the worst single breach over the whole Guardian sweep, then prints the config, the
 * chord that walked out, and every block of the build standing past its far wall -- space-separated,
 * so a line can go straight into {@code /tp}.</p>
 */
@Tag("sweep")
class GuardianBreachReproProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> guardian() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs");
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
				songs.resolve("deltarune-ch-4-guardian.json"))) {
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
	private static final int REPRO_WIDTH = 40;
	private static final int REPRO_FLOORS = 5;

	@Test
	void dumpsTheWorstBreachInCoordinates() throws Exception {
		for (int[] config : new int[][] {{36, 5}, {36, 2}, {40, 5}, {24, 3}}) {
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
		// Every block past the far wall, gathered into the lane it belongs to. A lane is one y and
		// one z, so that pair names the run, and the furthest block in it is how far the lane got.
		// Only the far side: the machine's read line stands off the near wall by design and would
		// otherwise drown the one run that is actually a fault.
		Map<String, int[]> runs = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			int out = x > plan.farWall() ? x - plan.farWall() : 0;
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
	 * The repro at the {@code maxBuildFloors} ekran actually has set, not the sweep's.
	 *
	 * <p>Every table in this file sweeps with {@code maxFloors=4} and ekran's config says 16. A
	 * repro they cannot paste is not a repro, so the config the coordinates belong to has to be the
	 * one they would type.</p>
	 */
	@Test
	void checksTheReproSurvivesEkransMaxFloors() throws Exception {
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
				java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs"))) {
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
				java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs"))) {
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
	 * <p>ekran remembers the library at nought breaches for everything but Guardian. The sweep says
	 * otherwise with the rule <em>off</em>, which is main -- so either the memory is of a narrower
	 * band of configurations than a sweep from twelve wide over one floor, or it is of a different
	 * build. Worth settling before anything is blamed on the shed rule: a config band nobody would
	 * ever paste drowns out the ones somebody would.</p>
	 */
	@Test
	void findsTheBandWhereTheLibraryIsClean() throws Exception {
		List<java.nio.file.Path> files;
		try (java.util.stream.Stream<java.nio.file.Path> listing = java.nio.file.Files.list(
				java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs"))) {
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
		for (int[] config : new int[][] {{40, 5}, {36, 2}, {36, 5}, {24, 3}}) {
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
		box(40, 5, 39, 52, 74, 80, 74, 78);
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
