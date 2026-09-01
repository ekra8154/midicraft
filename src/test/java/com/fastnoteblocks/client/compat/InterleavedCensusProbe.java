package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every song in the library through the interleaved half-tick builder, faults ranked worst first.
 *
 * <p>The cross-machine adjacency census: foreign lanes stand a lane spacing apart everywhere in
 * this layout, and whether that is company or contention is a number, not an argument. Prints and
 * asserts nothing, in the census tradition -- the library has whatever faults it has, and a probe
 * that fails on the first is a probe nobody can point at the second.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*InterleavedCensusProbe" -Dprobe.sizes=24x1,32x3
 * </pre>
 */
@Tag("sweep")
class InterleavedCensusProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/**
	 * Solo songs the census also builds at double speed, as a synthetic two-lane copy.
	 *
	 * <p>A one-lane song's game-tick times are all even, so double speed is exactly a halving --
	 * no rounding, which is the same fact that makes the half-tick layouts worth building at all.
	 * The halved copy lives on both parities and interleaves for real, which is what the library
	 * is otherwise too honest to provide many of.</p>
	 */
	private static Set<String> doubled() {
		return Set.of(System.getProperty("probe.double",
			"guardian25,guardian30,illit-do-the-dance,moonlight-sonata-3rd-movement,"
				+ "harder-better-faster-stronger-daft-punk,hopes-and-dreams,he-s-a-pirate,"
				+ "zoltraak,wellerman,jackpot-thefatrat").split(","));
	}

	/** Repeaters whose input cell holds no block at all, counted straight off the commands. */
	static int starvedRepeaters(SongBuilder.PastePlan plan) {
		Set<BlockPos> filled = new java.util.HashSet<>();
		List<BlockPos[]> repeaters = new ArrayList<>();
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			BlockPos at = new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
				Integer.parseInt(token[3]));
			filled.add(at);
			if (token[4].startsWith("minecraft:repeater")) {
				String facing = token[4].substring(token[4].indexOf("facing=") + "facing=".length());
				facing = facing.substring(0, facing.indexOf(','));
				// A repeater's FACING points at its input side.
				Direction input = Direction.valueOf(facing.toUpperCase(java.util.Locale.ROOT));
				repeaters.add(new BlockPos[] {at, at.relative(input)});
			}
		}
		int starved = 0;
		for (BlockPos[] repeater : repeaters) {
			if (!filled.contains(repeater[1])) {
				starved++;
			}
		}
		return starved;
	}

	private static List<int[]> sizes() {
		String given = System.getProperty("probe.sizes", "24x1,32x1,20x2,24x3");
		List<int[]> sizes = new ArrayList<>();
		for (String size : given.split(",")) {
			String[] part = size.strip().split("x");
			sizes.add(new int[] {Integer.parseInt(part[0]), Integer.parseInt(part[1])});
		}
		return sizes;
	}

	@Test
	void faultsOverTheLibrary() throws Exception {
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			sweep(held);
		} finally {
			held.putBack();
		}
	}

	private void sweep(Flags.Held held) throws Exception {
		List<String> rows = new ArrayList<>();
		int builds = 0;
		int dualBuilds = 0;
		int clean = 0;
		int totalWrong = 0;
		int totalMissing = 0;
		int totalCollisions = 0;
		int widthOver = 0;
		int totalCornerRepeaters = 0;
		int threw = 0;
		long totalDepth = 0;
		List<Path> files;
		try (Stream<Path> listed = Files.list(SONGS)) {
			files = listed.filter(file -> file.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			String song = file.getFileName().toString().replace(".json", "");
			List<List<SongBuilder.EventNote>> variants = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			variants.add(notes);
			labels.add(song);
			long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
			if ((evens == 0 || evens == notes.size()) && doubled().contains(song)) {
				variants.add(notes.stream().map(note -> new SongBuilder.EventNote(
					note.time() / 2, note.trackNumber(), note.order(), note.pitch(),
					note.instrumentBlock())).toList());
				labels.add(song + " 2x-2-lane");
			}
			for (int variant = 0; variant < variants.size(); variant++) {
			List<SongBuilder.EventNote> built = variants.get(variant);
			String label = labels.get(variant);
			long builtEvens = built.stream().filter(note -> note.time() % 2 == 0).count();
			boolean dual = builtEvens > 0 && builtEvens < built.size();
			for (int[] size : sizes()) {
				builds++;
				try {
					SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
						new BlockPos(0, 64, 0), Direction.EAST, built,
						new SongBuilder.BuildLimits(16, size[0], size[1]),
						SongBuilder.WalkStart.HEAD);
					int wrong = plan.wrongNotes();
					int missing = plan.missingNotes();
					int clashes = plan.collisions().size();
					// A starved repeater -- nothing in the cell it reads -- is a lane cut in two,
					// and it is the one fault the note-level numbers cannot see: the reader treats
					// it as another way in and counts everything after it as reached. Asked of the
					// commands directly, facing parsed from the block text; a whole build has
					// nought, because even the head repeaters read the stone their buttons sit on.
					// Forty-two of these shipped in one build while every other number said clean.
					int cornerRepeaters = starvedRepeaters(plan);
					// The width promise, now that waits fold: a build wider than the width it
					// REPORTS is a fault of its own kind, whatever its notes did. The reported
					// width may exceed the asked one -- that is the chord clamp, and it is honest.
					int over = plan.spanX() - plan.builtWidth();
					totalDepth += plan.spanZ();
					totalWrong += wrong;
					totalMissing += missing;
					totalCollisions += clashes;
					if (over > 0) {
						widthOver++;
					}
					if (dual) {
						dualBuilds++;
					}
					totalCornerRepeaters += cornerRepeaters;
					if (wrong == 0 && missing == 0 && clashes == 0 && over <= 0
							&& cornerRepeaters == 0) {
						clean++;
					} else {
						rows.add(String.format("%6d %s %dx%d %s wrong=%d missing=%d collisions=%d"
							+ " span=%d over=%d deadRepeaters=%d",
							wrong * 3 + missing * 5 + clashes + Math.max(0, over)
								+ cornerRepeaters * 5,
							label, size[0], size[1], dual ? "dual" : "solo", wrong, missing,
							clashes, plan.spanX(), Math.max(0, over), cornerRepeaters));
					}
				} catch (Exception refused) {
					threw++;
					rows.add(String.format("%6d %s %dx%d THREW %s: %.120s", 999999, label,
						size[0], size[1], refused.getClass().getSimpleName(),
						String.valueOf(refused.getMessage())));
				}
			}
			}
		}
		rows.sort(java.util.Comparator.reverseOrder());
		rows.forEach(row -> System.out.println("  " + row));
		System.out.println("INTERLEAVED CENSUS" + held.said() + ": " + builds + " builds (" + dualBuilds
			+ " dual, " + (builds - dualBuilds) + " solo), " + clean + " clean, "
			+ threw + " threw, wrong=" + totalWrong + " missing=" + totalMissing
			+ " collisions=" + totalCollisions + " overWidth=" + widthOver
			+ " deadRepeaters=" + totalCornerRepeaters
			+ " corridor=" + totalDepth);
	}
}
