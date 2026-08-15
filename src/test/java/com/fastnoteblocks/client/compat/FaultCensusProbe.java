package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every fault in the whole library, on whichever paster is asked for, ranked worst first.
 *
 * <p>{@link BlitzSweepTest} does this for v1 and cannot be pointed anywhere else; {@link FaultProbeTest}
 * draws one build in detail but you have to know which build to name. This is the step between them:
 * it says <b>which builds are worth drawing</b>, which is the question at the start of a fault
 * session and the one nothing here answered for v2.</p>
 *
 * <p>Read back through {@link NoteMachineReader}, because the plan's own numbers read nought over a
 * severed wire -- a build that has stopped playing reports no wrong notes at all.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*FaultCensusProbe" -Dcensus.mode=v2 -Dcensus.sizes=40x3,24x3
 * gradlew sweepTest --tests "*FaultCensusProbe" -Dcensus.songs=guardian -Dcensus.sizes=20x5
 * </pre>
 *
 * <p>Prints; asserts nothing. The library has faults today and a probe that fails the build when it
 * finds one is a probe nobody can point at a broken build.</p>
 */
@Tag("sweep")
class FaultCensusProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static String text(String key, String fallback) {
		String given = System.getProperty("census." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	/** Sizes as {@code 40x3}, because that is how they are said out loud. */
	private static List<int[]> sizes() {
		List<int[]> found = new ArrayList<>();
		for (String pair : text("sizes", "20x5,24x3,40x3,40x5,48x1").split(",")) {
			String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
			found.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		return found;
	}

	private static SongBuilder.PasteMode mode() {
		return switch (text("mode", "v2").toLowerCase(Locale.ROOT)) {
			case "v2", "ultra2", "ultra_compact_lane_v2" -> SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2;
			case "v1", "ultra", "ultra_compact_lane" -> SongBuilder.PasteMode.ULTRA_COMPACT_LANE;
			default -> SongBuilder.PasteMode.valueOf(text("mode", "v2").toUpperCase(Locale.ROOT));
		};
	}

	/**
	 * One build's faults, in the order they are worth fixing.
	 *
	 * @param dead notes the signal never reaches -- everything downstream of one break, so the number
	 *     is a shadow rather than a count of breaks
	 * @param dropped notes the layout had nowhere to hang, which the build simply does not contain
	 */
	private record Row(String song, int width, int floors, int dead, int dropped, int wrong,
			int breachLanes, int breachBlocks, int depth, String refused, FaultView.Break broke,
			List<String> wrongPairs) {

		boolean clean() {
			return refused == null && dead == 0 && dropped == 0 && wrong == 0 && breachBlocks == 0;
		}

		/** Dead first, then missing notes, then wrong ones, then ground the build promised not to take. */
		long weight() {
			return dead * 1_000_000L + dropped * 10_000L + wrong * 100L + breachBlocks;
		}
	}

	private static int droppedIn(SongBuilder.PastePlan plan) {
		int lost = 0;
		for (String fault : plan.faults()) {
			if (fault.contains("had nowhere to hang")) {
				lost += Integer.parseInt(fault.split(" ")[0]);
			}
		}
		return lost;
	}

	@Test
	void ranksEveryBuildInTheLibrary() throws Exception {
		Flags.Held held = Flags.set(text("set", ""));
		try {
			sweep(held);
		} finally {
			held.putBack();
		}
	}

	private void sweep(Flags.Held held) throws Exception {
		SongBuilder.PasteMode mode = mode();
		List<int[]> sizes = sizes();
		String only = text("songs", "");
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json"))
				.filter(path -> only.isEmpty() || path.getFileName().toString().contains(only))
				.sorted().toList();
		}
		Gson gson = new Gson();
		List<Row> rows = new ArrayList<>();
		long started = System.currentTimeMillis();
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
				try {
					FaultView.Build built = FaultView.of(name, notes, mode, size[0], size[1], 4, false);
					rows.add(new Row(name, size[0], size[1], built.reading().unreachedNotes(),
						droppedIn(built.plan()), built.plan().wrongNotes(),
						built.plan().breaches().size(),
						built.plan().breaches().stream().mapToInt(Integer::intValue).sum(),
						built.plan().spanZ(), null,
						built.reading().unreachedNotes() == 0 ? null : FaultView.firstBreak(built),
						FaultView.wrongNotes(built).stream()
							.map(wrong -> FaultView.whose(built, wrong)).toList()));
				} catch (RuntimeException refused) {
					rows.add(new Row(name, size[0], size[1], 0, 0, 0, 0, 0, 0,
						String.valueOf(refused.getMessage()), null, List.of()));
				}
			}
		}
		report(mode, sizes, rows, System.currentTimeMillis() - started, held);
	}

	private static void report(SongBuilder.PasteMode mode, List<int[]> sizes, List<Row> rows,
			long millis, Flags.Held held) {
		System.out.println();
		System.out.println("==== " + mode.label() + " over " + rows.size() + " builds, "
			+ sizes.size() + " sizes" + held.said() + " ====");
		List<Row> faulty = new ArrayList<>(rows.stream().filter(row -> !row.clean()).toList());
		faulty.sort((a, b) -> Long.compare(b.weight(), a.weight()));
		System.out.println("   " + (rows.size() - faulty.size()) + " clean, " + faulty.size()
			+ " with something wrong, " + millis / 1000 + "s");
		if (faulty.isEmpty()) {
			System.out.println("   nothing to draw: no dead wire, no missing note, no wrong note, "
				+ "no breach anywhere in the library");
			return;
		}
		System.out.println();
		System.out.println("---- worst first (fault.song / fault.width / fault.floors) ----");
		for (Row row : faulty) {
			System.out.println(String.format(
				"   %-34s %2dw x %df  dead %5d  missing %4d  wrong %3d  breach %2d lanes %3d blocks%s",
				row.song(), row.width(), row.floors(), row.dead(), row.dropped(), row.wrong(),
				row.breachLanes(), row.breachBlocks(),
				row.refused() == null ? "" : "   REFUSED: " + row.refused()));
		}
		// By song and by kind, because one song at five sizes is one bug five times and reads as five
		// in a list sorted by weight.
		TreeMap<String, long[]> bySong = new TreeMap<>();
		for (Row row : faulty) {
			long[] tally = bySong.computeIfAbsent(row.song(), key -> new long[5]);
			tally[0] += row.dead();
			tally[1] += row.dropped();
			tally[2] += row.wrong();
			tally[3] += row.breachBlocks();
			tally[4] += row.refused() == null ? 0 : 1;
		}
		System.out.println();
		System.out.println("---- by song ----");
		bySong.entrySet().stream()
			.sorted((a, b) -> Long.compare(
				b.getValue()[0] * 1_000_000 + b.getValue()[1] * 10_000 + b.getValue()[2] * 100
					+ b.getValue()[3],
				a.getValue()[0] * 1_000_000 + a.getValue()[1] * 10_000 + a.getValue()[2] * 100
					+ a.getValue()[3]))
			.forEach(entry -> System.out.println(String.format(
				"   %-34s dead %6d  missing %4d  wrong %3d  breach %4d  refused %d",
				entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2],
				entry.getValue()[3], entry.getValue()[4])));
		// The shapes that meet at each break, which is what says whether twenty dead builds are twenty
		// bugs or one. Nothing else here can tell those apart, and a session that guesses wrong spends
		// itself on the rarest of them.
		List<Row> broken = faulty.stream().filter(row -> row.broke() != null).toList();
		if (!broken.isEmpty()) {
			System.out.println();
			System.out.println("---- where each dead build breaks ----");
			TreeMap<String, Integer> byPair = new TreeMap<>();
			for (Row row : broken) {
				System.out.println(String.format("   %-34s %2dw x %df  %s", row.song(), row.width(),
					row.floors(), row.broke()));
				byPair.merge(FaultView.family(row.broke().before()) + " -> "
					+ FaultView.family(row.broke().what()), 1, Integer::sum);
			}
			System.out.println();
			System.out.println("---- breaks by the pair of shapes that meet ----");
			byPair.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
				.forEach(entry -> System.out.println(String.format("   %3d  %s", entry.getValue(),
					entry.getKey())));
		}
		// And the same question of the wrong notes, which are the other fault that is two shapes
		// disagreeing rather than one shape being wrong on its own.
		TreeMap<String, Integer> byWrong = new TreeMap<>();
		TreeMap<String, String> whereWrong = new TreeMap<>();
		for (Row row : faulty) {
			for (String pair : row.wrongPairs()) {
				String shapes = FaultView.family(pair);
				byWrong.merge(shapes, 1, Integer::sum);
				whereWrong.putIfAbsent(shapes,
					row.song() + " " + row.width() + "x" + row.floors());
			}
		}
		if (!byWrong.isEmpty()) {
			System.out.println();
			System.out.println("---- wrong notes by the pair of shapes that meet ----");
			byWrong.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
				.forEach(entry -> System.out.println(String.format("   %3d  %-72s  first at %s",
					entry.getValue(), entry.getKey(), whereWrong.get(entry.getKey()))));
		}
		long dead = faulty.stream().mapToLong(Row::dead).sum();
		long missing = faulty.stream().mapToLong(Row::dropped).sum();
		long wrong = faulty.stream().mapToLong(Row::wrong).sum();
		long breach = faulty.stream().mapToLong(Row::breachBlocks).sum();
		long refused = faulty.stream().filter(row -> row.refused() != null).count();
		System.out.println();
		// Size beside the faults, because compactness is the product: a flag that buys a dead build
		// with a hundred columns of corridor is a regression however green the fault columns go.
		// Summed over every build including the clean ones -- the faulty rows are not where a shape
		// that costs columns spends them.
		System.out.println("CENSUS depth=" + rows.stream().mapToLong(Row::depth).sum());
		System.out.println("CENSUS builds=" + rows.size()
			+ " deadBuilds=" + faulty.stream().filter(row -> row.dead() > 0).count()
			+ " dead=" + dead + " missing=" + missing + " wrong=" + wrong
			+ " breachBlocks=" + breach + " refused=" + refused);
	}
}
