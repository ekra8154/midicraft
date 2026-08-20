package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a marked paste actually looks like, across songs and widths that are known to go wrong.
 *
 * <p>{@link DebugPasteMarkTest} proves the marking harms nothing. This says whether it is worth
 * looking at, which is a different question and one only a census answers: a mark that fires on
 * every block says as little as one that never fires, and the three that only appear on a broken
 * build -- the breach, the dead wire, the wrong note -- are exactly the ones a clean song will never
 * show. So the configurations here are chosen to break.</p>
 *
 * <p>It also prints what stands outside the walls, block by block and column by column, which is how
 * the two-column allowance the breach mark is built on was arrived at.</p>
 */
@Tag("sweep")
class DebugPasteCensusProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void plainAgain() {
		SongBuilder.DEBUG_PASTE = false;
	}

	private static List<SongBuilder.EventNote> song(String file) throws Exception {
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(file.replace(".json", "")))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
	}

	private static final Map<String, String> MARKS = new LinkedHashMap<>(Map.of(
		"minecraft:tuff", "bus",
		"minecraft:andesite", "stackedChord",
		"minecraft:deepslate", "stackedBus",
		"minecraft:deepslate_tiles", "cutHead",
		"minecraft:cobbled_deepslate", "stackedSimple",
		"minecraft:smooth_basalt", "rail",
		"minecraft:stripped_crimson_hyphae[axis=x]", "BREACH",
		"minecraft:red_nether_bricks", "DEADWIRE",
		"minecraft:waxed_copper_bulb[lit=true]", "WRONGNOTE",
		"minecraft:sea_lantern", "COLLISION"));

	private static String mark(String block) {
		String named = MARKS.get(block);
		return named == null && block.startsWith("minecraft:dragon_head") ? "MISSEDNOTE" : named;
	}

	/**
	 * Where in the library the three rare marks can actually be seen.
	 *
	 * <p>A wrong note, a note with nothing to set it off and a run of dead wire are all things a
	 * good build does not have, which makes them the marks most likely to be shipped never having
	 * fired. This looks for a configuration that shows each, so the regression test has something
	 * real to pin itself to instead of a build somebody hoped was broken.</p>
	 */
	@Test
	void whereTheRareMarksLive() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
		int found = 0;
		try (var listing = Files.list(songs)) {
			for (Path file : listing.filter(path -> path.toString().endsWith(".json")).sorted()
					.toList()) {
				List<SongBuilder.EventNote> notes;
				try {
					notes = song(file.getFileName().toString());
				} catch (RuntimeException unreadable) {
					continue;
				}
				if (notes.isEmpty()) {
					continue;
				}
				for (int[] size : new int[][] {{12, 1}, {12, 2}, {16, 1}, {8, 2}, {40, 3}, {24, 6}}) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(16, size[0], size[1]));
					} catch (RuntimeException refused) {
						continue;
					}
					Map<String, Integer> census = new TreeMap<>();
					for (String command : plan.commands()) {
						String mark = mark(command.split(" ", 5)[4].replace(" replace", ""));
						if (mark != null && mark.equals(mark.toUpperCase(java.util.Locale.ROOT))) {
							census.merge(mark, 1, Integer::sum);
						}
					}
					census.remove("BREACH");
					if (!census.isEmpty()) {
						found++;
						System.out.println("RARE " + file.getFileName() + " " + size[0] + "x"
							+ size[1] + "  " + census);
					}
				}
			}
		}
		System.out.println("RARE " + found + " configurations show a rare mark");
	}

	@Test
	void census() throws Exception {
		String[] songs = {"illit-do-the-dance.json", "deltarune-ch-4-guardian.json",
			"all-of-the-lights-kanye-west.json", "big-shot.json"};
		int[][] configurations = {{40, 3}, {16, 4}, {12, 3}, {24, 6}};
		SongBuilder.DEBUG_PASTE = true;
		for (String file : songs) {
			if (!BreachView.inLibrary(file.replace(".json", ""))) {
				continue;
			}
			List<SongBuilder.EventNote> notes = song(file);
			for (int[] configuration : configurations) {
				String heading = file.replace(".json", "") + "  " + configuration[0] + "x"
					+ configuration[1];
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(16, configuration[0], configuration[1]));
				} catch (RuntimeException refused) {
					System.out.println("CENSUS " + heading + "  refused: " + refused.getMessage());
					continue;
				}
				report(heading, plan);
			}
		}
	}

	private static void report(String heading, SongBuilder.PastePlan plan) {
		Map<String, Integer> census = new TreeMap<>();
		Map<Integer, Map<String, Integer>> outside = new TreeMap<>();
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			int x = Integer.parseInt(parts[1]);
			lowest = Math.min(lowest, x);
			highest = Math.max(highest, x);
			String mark = mark(parts[4]);
			if (mark != null) {
				census.merge(mark, 1, Integer::sum);
			}
			if (x < plan.nearWall() || x > plan.farWall()) {
				outside.computeIfAbsent(x, column -> new TreeMap<>()).merge(parts[4], 1, Integer::sum);
			}
		}
		System.out.println("CENSUS " + heading + "  blocks=" + plan.commands().size()
			+ "  breaches=" + plan.breaches() + " wrong=" + plan.wrongNotes()
			+ "  walls " + plan.nearWall() + ".." + plan.farWall()
			+ "  blocks span " + lowest + ".." + highest + "  " + census);
		// What the walk counted about cut chords, which is the one shape whose colour depends on a
		// decision rather than on what it is: a cut opens with a stacked head or it does not.
		System.out.println("   CUTS " + plan.padding().entrySet().stream()
			.filter(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT).contains("split"))
			.sorted(Map.Entry.comparingByKey()).toList());
		outside.forEach((column, blocks) -> System.out.println("   OUTSIDE x=" + column + " "
			+ (column < plan.nearWall() ? "near" : "far") + " by "
			+ (column < plan.nearWall() ? plan.nearWall() - column : column - plan.farWall())
			+ "  " + blocks));
	}
}
