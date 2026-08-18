package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a two-rail run costs in blocks a player has to make, rather than in lanes.
 *
 * <p>Every other measurement of the runs asks about depth, because that is what ekran optimises for
 * space. This asks the other question: <i>"repeaters are very expensive in survival mode ... its
 * placing one every other block on the bottom rail and they never get used."</i> A run holds a
 * repeater in every column bar the one it opens on, where the plain lane holds one per module across
 * two columns -- so a run that buys no depth is straightforwardly worse, and one that buys a little
 * has a price nobody had ever put a number on.</p>
 *
 * <p>Every arm in one window, because the library grows between sessions and a number remembered
 * from last week is a number about a different library:</p>
 *
 * <ul>
 *   <li><b>off</b> -- {@code TWO_RAIL_RUNS} false, the plain lane.</li>
 *   <li><b>all</b> -- runs as they were: every run that fits opens.</li>
 *   <li><b>fed1</b>, <b>fed2</b>, <b>fed3</b> -- {@link SongBuilder#RUN_WANTS_ITS_FLOOR_RAIL} at
 *       each setting of {@link SongBuilder#RAIL_FLOOR_CHORDS_WANTED}: only runs whose floor rail
 *       takes that many chords inside the room they have. A run saves two columns per floor chord
 *       and pays its head once, so one is break-even against a full head and two is the first
 *       setting that is ahead on depth -- while repeaters keep improving past that.</li>
 * </ul>
 *
 * <pre>
 * gradlew sweepTest --tests "*RailResourceProbe" -Dprobe.sizes=24x3,40x3 -Dprobe.mode=v2
 * </pre>
 *
 * <p>Prints; asserts nothing.</p>
 */
@Tag("sweep")
class RailResourceProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static String text(String key, String fallback) {
		// "probe.", not "rail.": build.gradle forwards three prefixes to the test JVM and that is
		// not one of them. A probe whose prefix is missing does not fail -- it runs its defaults and
		// prints a full page of numbers for the experiment nobody performed, which is exactly what
		// this one did on the run that priced the runs.
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private static List<int[]> sizes() {
		List<int[]> found = new ArrayList<>();
		for (String pair : text("sizes", "20x5,24x3,40x3").split(",")) {
			String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
			found.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		return found;
	}

	private static SongBuilder.PasteMode mode() {
		return text("mode", "v2").equalsIgnoreCase("v1")
			? SongBuilder.PasteMode.ULTRA_COMPACT_LANE
			: SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2;
	}

	/** One build's bill: the lanes it is deep, and the blocks a player has to bring. */
	private record Bill(int depth, int repeaters, int blocks, boolean refused) {
	}

	/** One arm's totals over every build that every arm managed to build. */
	private static final class Arm {
		private final String name;
		private final int wanted;
		private long depth;
		private long repeaters;
		private long blocks;
		private final TreeMap<String, Long> tally = new TreeMap<>();

		private Arm(String name, int wanted) {
			this.name = name;
			this.wanted = wanted;
		}
	}

	@Test
	void pricesEveryRun() throws Exception {
		SongBuilder.PasteMode mode = mode();
		List<int[]> sizes = sizes();
		String only = text("songs", "");
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json"))
				.filter(path -> only.isEmpty() || path.getFileName().toString().contains(only))
				.sorted().toList();
		}
		Map<String, Arm> arms = new LinkedHashMap<>();
		arms.put("off", new Arm("off", 0));
		arms.put("all", new Arm("all", 0));
		for (int wanted = 1; wanted <= 3; wanted++) {
			arms.put("fed" + wanted, new Arm("fed" + wanted, wanted));
		}
		Gson gson = new Gson();
		System.out.println(String.format("%-42s %6s %6s %6s %6s %8s %8s %8s",
			"song", "w/f", "dOff", "dAll", "dFed1", "repOff", "repAll", "repFed1"));
		int builds = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (int[] size : sizes) {
				Map<String, Bill> bills = new LinkedHashMap<>();
				for (Arm arm : arms.values()) {
					SongBuilder.TWO_RAIL_RUNS = !"off".equals(arm.name);
					SongBuilder.RUN_WANTS_ITS_FLOOR_RAIL = arm.wanted > 0;
					SongBuilder.RAIL_FLOOR_CHORDS_WANTED = Math.max(1, arm.wanted);
					bills.put(arm.name, bill(notes, mode, size[0], size[1], arm));
				}
				SongBuilder.TWO_RAIL_RUNS = true;
				SongBuilder.RUN_WANTS_ITS_FLOOR_RAIL = true;
				SongBuilder.RAIL_FLOOR_CHORDS_WANTED = 1;
				if (bills.values().stream().anyMatch(Bill::refused)) {
					System.out.println(String.format("%-42s %6s   refused",
						name, size[0] + "/" + size[1]));
					continue;
				}
				builds++;
				for (Arm arm : arms.values()) {
					Bill paid = bills.get(arm.name);
					arm.depth += paid.depth();
					arm.repeaters += paid.repeaters();
					arm.blocks += paid.blocks();
				}
				System.out.println(String.format("%-42s %6s %6d %6d %6d %8d %8d %8d",
					name, size[0] + "/" + size[1],
					bills.get("off").depth(), bills.get("all").depth(), bills.get("fed1").depth(),
					bills.get("off").repeaters(), bills.get("all").repeaters(),
					bills.get("fed1").repeaters()));
			}
		}
		System.out.println();
		System.out.println("builds " + builds + "   mode " + mode);
		Arm off = arms.get("off");
		for (Arm arm : arms.values()) {
			System.out.println(String.format("%-5s depth %8d  repeaters %9d  blocks %10d"
				+ "   vs off: depth %+6d  repeaters %+7d",
				arm.name, arm.depth, arm.repeaters, arm.blocks,
				arm.depth - off.depth, arm.repeaters - off.repeaters));
		}
		System.out.println();
		for (Arm arm : arms.values()) {
			if (arm.tally.isEmpty()) {
				continue;
			}
			System.out.println("-- " + arm.name);
			arm.tally.forEach((key, count) ->
				System.out.println(String.format("%-40s %10d", key, count)));
		}
	}

	private static Bill bill(List<SongBuilder.EventNote> notes, SongBuilder.PasteMode mode,
			int width, int floors, Arm arm) {
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				mode, new SongBuilder.BuildLimits(16, width, floors));
			for (Map.Entry<String, Integer> entry : plan.padding().entrySet()) {
				if (entry.getKey().startsWith("railRun") || entry.getKey().startsWith("railStopped")) {
					arm.tally.merge(entry.getKey(), (long) entry.getValue(), Long::sum);
				}
			}
			int repeaters = 0;
			for (String command : plan.commands()) {
				if (command.contains("minecraft:repeater")) {
					repeaters++;
				}
			}
			return new Bill(plan.spanZ(), repeaters, plan.commands().size(), false);
		} catch (RuntimeException refused) {
			return new Bill(0, 0, 0, true);
		}
	}
}
