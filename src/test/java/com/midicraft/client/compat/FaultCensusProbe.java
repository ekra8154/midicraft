package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.SongAnalysis;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>{@link BlitzSweepTest} does this for v1 and cannot be pointed anywhere else; {@link
 * FaultProbeTest} draws one build in detail but you have to know which build to name. This is the
 * step between them: it says <b>which builds are worth drawing</b>, which is the question at the
 * start of a fault session and the one nothing here answered for v2.</p>
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

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static String text(String key, String fallback) {
		String given = System.getProperty("census." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	/**
	 * Sizes as {@code 40x3}, because that is how they are said out loud -- or a grid,
	 * {@code -Dcensus.grid=8-50/1-5/1-10/7}: widths from 8 to 50 in seeded random steps of one to
	 * five, every floor count from one to ten. The thorough run: the corners a hand-picked list
	 * never visits, and a seed so the same run can be made twice.
	 */
	private static List<int[]> sizes() {
		List<int[]> found = new ArrayList<>();
		String grid = text("grid", "");
		if (!grid.isEmpty()) {
			String[] part = grid.split("/");
			String[] widths = part[0].split("-");
			String[] steps = part[1].split("-");
			String[] floors = part[2].split("-");
			java.util.Random random = new java.util.Random(part.length > 3
				? Long.parseLong(part[3]) : 7L);
			int low = Integer.parseInt(steps[0]);
			int high = Integer.parseInt(steps[1]);
			for (int width = Integer.parseInt(widths[0]); width <= Integer.parseInt(widths[1]);
					width += low + random.nextInt(high - low + 1)) {
				for (int floor = Integer.parseInt(floors[0]); floor <= Integer.parseInt(floors[1]);
						floor++) {
					found.add(new int[] {width, floor});
				}
			}
			return found;
		}
		for (String pair : text("sizes", "20x5,24x3,40x3,40x5,48x1").split(",")) {
			String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
			found.add(new int[] {Integer.parseInt(half[0]), Integer.parseInt(half[1])});
		}
		return found;
	}

	/**
	 * {@code -Dcensus.sustain=EVERY} or {@code EVERY/AFTER}, in {@code SustainLength} names ({@code AFTER}
	 * defaults to QUARTER): every layer of every song sustains, which is what prices sustained notes
	 * against the library. Empty leaves the songs as written.
	 */
	private static List<ComposerProject.Layer> sustained(List<ComposerProject.Layer> layers) {
		String given = text("sustain", "");
		if (given.isEmpty()) {
			return layers;
		}
		String[] parts = given.toUpperCase(Locale.ROOT).split("/");
		ComposerProject.Sustain held = new ComposerProject.Sustain(true,
			parts.length > 1 ? ComposerProject.SustainLength.valueOf(parts[1])
				: ComposerProject.SustainLength.QUARTER,
			ComposerProject.SustainLength.valueOf(parts[0]));
		return layers.stream().map(layer -> layer.withSustain(held)).toList();
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
			List<String> wrongPairs, int severed, int collisions, int totalCols,
			List<String> troubles, int innerWalls, int outerWalls, int worstWall) {

		boolean clean() {
			return refused == null && dead == 0 && dropped == 0 && wrong == 0 && breachBlocks == 0
				&& severed == 0 && collisions == 0 && innerWalls == 0 && outerWalls == 0;
		}

		/**
		 * Dead first, then missing notes, then legs past a wall, then wrong ones, then ground the
		 * build promised not to take. A wall crossed outranks a wrong note because an inner wall
		 * crossed is the two machines colliding, and that is where the dead lines come from.
		 */
		long weight() {
			return (dead + severed * 1_000L) * 1_000_000L + dropped * 10_000L
				+ (innerWalls + outerWalls) * 1_000L + wrong * 100L + breachBlocks;
		}
	}

	private static int innerWalls(SongBuilder.PastePlan plan) {
		return (int) plan.innerWallBreaches();
	}

	private static int outerWalls(SongBuilder.PastePlan plan) {
		return (int) plan.outerWallBreaches();
	}

	/** A substring of the padding keys to total over the run, or blank for none. */
	private static final String COUNTED = text("count", "");

	private static final TreeMap<String, Long> TALLY = new TreeMap<>();

	/** How many lanes each song surveyed wants, so the report can say which builds were two-lane. */
	private static final TreeMap<String, Integer> LANES = new TreeMap<>();

	/** What each file's song calls itself, which is what the picker shows and the filename is not. */
	private static final TreeMap<String, String> NAMES = new TreeMap<>();

	private static void tally(SongBuilder.PastePlan plan) {
		if (COUNTED.isEmpty()) {
			return;
		}
		plan.padding().forEach((key, count) -> {
			if (key.contains(COUNTED)) {
				TALLY.merge(key, (long) count, Long::sum);
			}
		});
	}

	/**
	 * What a build says is wrong with itself, in its own words, less what already has a column.
	 *
	 * <p>A walk that catches itself doing something it knows is broken writes a line onto the plan
	 * and carries on -- see the tripwire in {@code addRailNote} for the shape of it. Those lines
	 * reach the paste screen, and until now they reached nothing else: this census read {@code
	 * faults()} for the one entry it wanted and threw the rest away, so a whole class of fault that
	 * the builder had already diagnosed was invisible to every sweep. The dead-note count picked up
	 * the consequence and the census reported it as a mystery.</p>
	 *
	 * <p>Two are left out because they are already columns of their own, counted off the readback
	 * rather than off the walk's opinion of itself.</p>
	 */
	private static List<String> troublesIn(SongBuilder.PastePlan plan) {
		List<String> said = new ArrayList<>();
		for (String fault : plan.faults()) {
			if (fault.contains("had nowhere to hang") || fault.contains("would never be triggered")) {
				continue;
			}
			said.add(fault);
		}
		return said;
	}

	/** The same line with its numbers taken out, so every instance of one fault tallies together. */
	private static String troubleKind(String fault) {
		return fault.replaceAll("-?\\d+", "#");
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
		// -Dcensus.name=<substring> filters on the song's own name field rather than its filename, so a
		// mark a composer types into the name -- "2 lanes" -- selects every song they gave it, whatever
		// each file happens to be called. Case-insensitive. Checked inside the loop, once the JSON is
		// parsed, because the name is not in the path.
		String named = text("name", "").toLowerCase(Locale.ROOT);
		// -Dcensus.real=true leaves the synthetic limit songs out: they are the ones named ultra-*,
		// built to carry chords of thirty, and the scope is chords to twenty-five.
		boolean realOnly = Boolean.parseBoolean(text("real", "false"));
		// -Dcensus.lanes=2 keeps only the songs the game itself calls two-lane, which is a property of
		// the music -- a gap of an odd number of game ticks somewhere in it -- and not of the filename.
		// Half the library is named "...-2-lanes" by hand and some of those songs want one lane, while
		// songs named nothing of the sort want two. Asking SongAnalysis the way the picker asks it is
		// the only reading that agrees with what a player is told before they paste.
		int wantLanes = Integer.parseInt(text("lanes", "0"));
		// -Dcensus.readback=false plans and stops: breaches and collisions both come out of the walk,
		// and the readback that finds dead, wrong and missing notes is most of a build's cost.
		boolean readback = Boolean.parseBoolean(text("readback", "true"));
		// -Dcensus.builds=thriller:20x2+32x2,linkin:38x2 -- a song's own sizes rather than the
		// cross product, for the round of fixing where the question is whether the twenty-seven
		// builds that were dead last time are dead now. Each name is a substring as census.songs
		// takes them, and the song is built only at the sizes after its colon.
		Map<String, List<int[]>> builds = new java.util.LinkedHashMap<>();
		for (String entry : text("builds", "").split(",")) {
			if (entry.isBlank()) {
				continue;
			}
			String[] half = entry.strip().split(":");
			List<int[]> own = new ArrayList<>();
			for (String pair : half[1].toLowerCase(Locale.ROOT).split("[+]")) {
				String[] size = pair.strip().split("x");
				own.add(new int[] {Integer.parseInt(size[0]), Integer.parseInt(size[1])});
			}
			builds.put(half[0].strip(), own);
		}
		if (!builds.isEmpty()) {
			only = String.join(",", builds.keySet());
		}
		// -Dcensus.songs=a,b,c: any of the substrings, so a handful of songs can be asked for at once.
		List<String> wanted = only.isEmpty() ? List.of()
			: java.util.Arrays.stream(only.split(",")).map(String::strip)
				.filter(each -> !each.isEmpty()).toList();
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json"))
				.filter(path -> wanted.isEmpty()
					|| wanted.stream().anyMatch(each -> path.getFileName().toString().contains(each)))
				.filter(path -> !realOnly || !path.getFileName().toString().startsWith("ultra-"))
				.sorted().toList();
		}
		// A filter that matches nothing is not an empty library, it is a typo -- and the report for
		// one is a page of headings saying everything is clean, which reads exactly like a pass. A
		// trailing comma in {@code -Dcensus.songs=guardian,} produced one of those here. Same trap as
		// the unforwarded property: the probe runs, prints, and answers a question nobody asked.
		if (!only.isEmpty() && files.isEmpty()) {
			List<String> library;
			try (Stream<Path> listing = Files.list(SONGS)) {
				library = listing.filter(path -> path.toString().endsWith(".json"))
					.map(path -> path.getFileName().toString().replace(".json", ""))
					.sorted().toList();
			}
			throw new IllegalArgumentException("census.songs=\"" + only
				+ "\" matches none of the " + library.size() + " songs in " + SONGS + ": " + library);
		}
		Gson gson = new Gson();
		// The game's own paste settings: its project loading, dedupe, thinning, limits and whether
		// a collision is recorded rather than thrown. What is pasted has to be what is simulated.
		GameSettings.Values game = GameSettings.get();
		List<Row> rows = new ArrayList<>();
		long started = System.currentTimeMillis();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			ComposerProject song = GameSettings.project(file);
			if (!text("sustain", "").isEmpty()) {
				song = new ComposerProject(song.name(), song.ppq(), song.tempoMicrosPerQuarter(),
					sustained(song.layers()), song.activeLayerIndex(), song.nextNoteId(),
					song.endTick(), song.speedQuarters(), song.speedEighths(), song.markers());
			}
			if (!named.isEmpty() && (song.name() == null
					|| !song.name().toLowerCase(Locale.ROOT).contains(named))) {
				continue;
			}
			// Through the one dispatcher, not the sequence reading: a half-tick layout is planned
			// from the composition in game ticks, and handed sequence events it builds a different
			// song than the paste would -- at the wrong speed, with the two tick parities scrambled.
			// Asking eventNotes directly is how a probe answers for a machine nobody can paste.
			int lanes = SongAnalysis.of(song, song.dedupesIdentical(), true, game.thinning()).lanesNeeded();
			if (wantLanes != 0 && lanes != wantLanes) {
				continue;
			}
			LANES.put(name, lanes);
			NAMES.put(name, song.name() == null ? name : song.name());
			List<SongBuilder.EventNote> notes = game.notes(song, mode);
			if (notes.isEmpty()) {
				continue;
			}
			List<int[]> ownSizes = builds.isEmpty() ? sizes
				: builds.entrySet().stream().filter(entry -> name.contains(entry.getKey()))
					.map(Map.Entry::getValue).findFirst().orElse(sizes);
			for (int[] size : ownSizes) {
				if (!readback) {
					try {
						SongBuilder.PastePlan plan = planOnly(notes, mode, size[0], size[1]);
						tally(plan);
						rows.add(new Row(name, size[0], size[1], 0, droppedIn(plan), plan.wrongNotes(),
							plan.breaches().size(),
							plan.breaches().stream().mapToInt(Integer::intValue).sum(),
							plan.spanZ(), null, null, List.of(), 0, plan.collisions().size(),
							plan.totalColumns(), troublesIn(plan), innerWalls(plan), outerWalls(plan),
							plan.worstWallBreach()));
					} catch (RuntimeException refused) {
						rows.add(new Row(name, size[0], size[1], 0, 0, 0, 0, 0, 0,
							String.valueOf(refused.getMessage()), null, List.of(), 0, 0, 0,
							List.of(), 0, 0, 0));
					}
					continue;
				}
				try {
					FaultView.Build built = FaultView.of(name, notes, mode, size[0], size[1],
						game.maxBuildFloors(), game.debugPaste());
					tally(built.plan());
					rows.add(new Row(name, size[0], size[1], built.reading().unreachedNotes(),
						droppedIn(built.plan()), built.plan().wrongNotes(),
						built.plan().breaches().size(),
						built.plan().breaches().stream().mapToInt(Integer::intValue).sum(),
						built.plan().spanZ(), null,
						built.reading().unreachedNotes() == 0 ? null : FaultView.firstBreak(built),
						FaultView.wrongNotes(built).stream()
							.map(wrong -> FaultView.whose(built, wrong)).toList(),
						// A walk lays one entrance, so a second way in is a repeater the build left
						// with nothing behind it -- a severed lane. The reader cannot tell that from an
						// alternative beginning and takes the generous reading, starting a fresh
						// performance at the orphan: everything downstream counts as reached and
						// unreachedNotes comes back nought on a build cut in half.
						Math.max(0, built.reading().versions() - 1),
						built.plan().collisions().size(), built.plan().totalColumns(),
						troublesIn(built.plan()), innerWalls(built.plan()), outerWalls(built.plan()),
						built.plan().worstWallBreach()));
				} catch (RuntimeException refused) {
					rows.add(new Row(name, size[0], size[1], 0, 0, 0, 0, 0, 0,
						String.valueOf(refused.getMessage()), null, List.of(), 0, 0, 0, List.of(),
						0, 0, 0));
				}
			}
		}
		// The same trap as the filename filter above, one level in: a mark that matches no song's
		// name is a typo, and the report for it is a page saying everything is clean.
		if (!named.isEmpty() && rows.isEmpty()) {
			List<String> names = new ArrayList<>();
			for (Path file : files) {
				try (Reader reader = Files.newBufferedReader(file)) {
					names.add(String.valueOf(gson.fromJson(reader, ComposerProject.class).name()));
				}
			}
			throw new IllegalArgumentException("census.name=\"" + named
				+ "\" matches none of the " + names.size() + " song names: " + names);
		}
		report(mode, sizes, rows, System.currentTimeMillis() - started, held);
	}

	/** The plan and nothing after it: what the preview does, without the reader. */
	private static SongBuilder.PastePlan planOnly(List<SongBuilder.EventNote> notes,
			SongBuilder.PasteMode mode, int width, int floors) {
		boolean marking = SongBuilder.MARK_UNREACHED;
		try {
			SongBuilder.MARK_UNREACHED = false;
			return SongBuilder.createPastePlan(new net.minecraft.core.BlockPos(0, 64, 0), notes, mode,
				GameSettings.get().limits(width, floors));
		} finally {
			SongBuilder.MARK_UNREACHED = marking;
		}
	}

	private static void report(SongBuilder.PasteMode mode, List<int[]> sizes, List<Row> rows,
			long millis, Flags.Held held) {
		System.out.println();
		System.out.println("==== " + mode.label() + " over " + rows.size() + " builds, "
			+ sizes.size() + " sizes" + held.said() + " " + GameSettings.get().said() + " ====");
		List<Row> faulty = new ArrayList<>(rows.stream().filter(row -> !row.clean()).toList());
		faulty.sort((a, b) -> Long.compare(b.weight(), a.weight()));
		System.out.println("   " + (rows.size() - faulty.size()) + " clean, " + faulty.size()
			+ " with something wrong, " + millis / 1000 + "s");
		long twoLane = LANES.values().stream().filter(lanes -> lanes == 2).count();
		System.out.println("   " + LANES.size() + " songs surveyed: " + twoLane + " two-lane, "
			+ (LANES.size() - twoLane) + " one-lane");
		System.out.println("   two-lane: " + LANES.entrySet().stream()
			.filter(entry -> entry.getValue() == 2).map(Map.Entry::getKey).toList());
		// A clean run still has something to say.
		//
		// This used to return here, which took the census keys and the totals with it -- so the one
		// moment you most want to ask "did that rule fire, and how often" is the moment the probe
		// stops talking. Every question below the faults is about the build rather than about what is
		// wrong with it, and a build with nothing wrong is still a build.
		boolean anything = !faulty.isEmpty();
		if (!anything) {
			System.out.println("   nothing to draw: no dead wire, no missing note, no wrong note, "
				+ "no breach anywhere in the library");
		}
		if (anything) {
		System.out.println();
		System.out.println("---- worst first (fault.song / fault.width / fault.floors) ----");
		for (Row row : faulty) {
			System.out.println(String.format(
				"   %-34s %2dw x %df  severed %d  dead %5d  missing %4d  wrong %3d  breach %2d lanes %3d blocks  walls in %2d out %2d worst %d  collisions %2d%s",
				row.song(), row.width(), row.floors(), row.severed(), row.dead(), row.dropped(),
				row.wrong(), row.breachLanes(), row.breachBlocks(), row.innerWalls(),
				row.outerWalls(), row.worstWall(), row.collisions(),
				row.refused() == null ? "" : "   REFUSED: " + row.refused()));
		}
		// By song and by kind, because one song at five sizes is one bug five times and reads as five
		// in a list sorted by weight.
		TreeMap<String, long[]> bySong = new TreeMap<>();
		for (Row row : faulty) {
			long[] tally = bySong.computeIfAbsent(row.song(), key -> new long[7]);
			tally[0] += row.dead();
			tally[1] += row.dropped();
			tally[2] += row.wrong();
			tally[3] += row.breachBlocks();
			tally[4] += row.refused() == null ? 0 : 1;
			tally[5] += row.innerWalls();
			tally[6] += row.outerWalls();
		}
		System.out.println();
		System.out.println("---- by song ----");
		bySong.entrySet().stream()
			.sorted((a, b) -> Long.compare(
				b.getValue()[0] * 1_000_000 + b.getValue()[1] * 10_000
					+ (b.getValue()[5] + b.getValue()[6]) * 1_000 + b.getValue()[2] * 100
					+ b.getValue()[3],
				a.getValue()[0] * 1_000_000 + a.getValue()[1] * 10_000
					+ (a.getValue()[5] + a.getValue()[6]) * 1_000 + a.getValue()[2] * 100
					+ a.getValue()[3]))
			.forEach(entry -> System.out.println(String.format(
				"   %-34s %dL  dead %6d  missing %4d  wrong %3d  breach %4d  walls in %3d out %3d  refused %d   \"%s\"",
				entry.getKey(), LANES.getOrDefault(entry.getKey(), 0), entry.getValue()[0],
				entry.getValue()[1], entry.getValue()[2],
				entry.getValue()[3], entry.getValue()[5], entry.getValue()[6],
				entry.getValue()[4], NAMES.getOrDefault(entry.getKey(), entry.getKey()))));
		}
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
		// Whatever the run was asked to count. The faults say a build is wrong; the census keys say
		// which shape's rule fired how often, and a change that is supposed to fire and does not is
		// the commonest wrong answer this repo produces. -Dcensus.count=<substring>
		if (!COUNTED.isEmpty()) {
			System.out.println();
			System.out.println("---- census keys matching \"" + COUNTED + "\" ----");
			TALLY.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(entry -> System.out.println(String.format("   %6d  %s", entry.getValue(),
					entry.getKey())));
			if (TALLY.isEmpty()) {
				System.out.println("   nothing matched -- the rule did not fire once");
			}
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
		// Corridor actually spent -- every lane's length, breaches included -- which is what depth
		// cannot say: the last floor may be mostly empty.
		System.out.println("CENSUS totalCols=" + rows.stream().mapToLong(Row::totalCols).sum());
		// By song, one line each, for comparing two pasters on the same grid: -Dcensus.depth=true.
		if (Boolean.parseBoolean(text("depth", "false"))) {
			TreeMap<String, long[]> depthBySong = new TreeMap<>();
			for (Row row : rows) {
				long[] tally = depthBySong.computeIfAbsent(row.song(), key -> new long[3]);
				tally[0] += row.depth();
				tally[1] += row.refused() == null ? 1 : 0;
				tally[2] += row.totalCols();
			}
			depthBySong.forEach((song, tally) -> System.out.println(
				String.format("DEPTH %-34s builds %5d  depth %9d  totalCols %10d", song, tally[1],
					tally[0], tally[2])));
		}
		// What the builds said about themselves. Printed before the totals because a build that has
		// already named its own fault is the cheapest fault in the report to go and fix.
		Map<String, Integer> troubles = new TreeMap<>();
		Map<String, String> firstTrouble = new TreeMap<>();
		for (Row row : rows) {
			for (String said : row.troubles()) {
				String kind = troubleKind(said);
				troubles.merge(kind, 1, Integer::sum);
				firstTrouble.putIfAbsent(kind,
					row.song() + " " + row.width() + "x" + row.floors());
			}
		}
		if (!troubles.isEmpty()) {
			System.out.println();
			System.out.println("---- what the builds say is wrong with themselves ----");
			troubles.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
				.forEach(entry -> System.out.println(String.format("   %5d  %-96s first at %s",
					entry.getValue(), entry.getKey(), firstTrouble.get(entry.getKey()))));
		}
		System.out.println("CENSUS troubles="
			+ rows.stream().mapToLong(row -> row.troubles().size()).sum()
			+ " inBuilds=" + rows.stream().filter(row -> !row.troubles().isEmpty()).count());
		System.out.println("CENSUS severedBuilds="
			+ faulty.stream().filter(row -> row.severed() > 0).count()
			+ " severedLanes=" + faulty.stream().mapToLong(Row::severed).sum());
		System.out.println("CENSUS collisions=" + rows.stream().mapToLong(Row::collisions).sum()
			+ " inBuilds=" + rows.stream().filter(row -> row.collisions() > 0).count());
		// Legs past their own wall, the plan's geometric reading. Inner and outer apart, because an
		// inner wall crossed is the two machines of a dual build colliding and an outer one is the
		// build wider than it promised -- see SongBuilder.WallBreach.
		System.out.println("CENSUS innerWalls=" + rows.stream().mapToLong(Row::innerWalls).sum()
			+ " outerWalls=" + rows.stream().mapToLong(Row::outerWalls).sum()
			+ " inBuilds=" + rows.stream()
				.filter(row -> row.innerWalls() + row.outerWalls() > 0).count()
			+ " worst=" + rows.stream().mapToInt(Row::worstWall).max().orElse(0));
		System.out.println("CENSUS builds=" + rows.size()
			+ " deadBuilds=" + faulty.stream().filter(row -> row.dead() > 0).count()
			+ " dead=" + dead + " missing=" + missing + " wrong=" + wrong
			+ " breachBlocks=" + breach + " refused=" + refused);
	}
}
