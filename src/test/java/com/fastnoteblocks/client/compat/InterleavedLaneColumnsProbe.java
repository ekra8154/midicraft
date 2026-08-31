package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How much corridor each of an interleaved build's two machines spends, side by side.
 *
 * <p>{@link SongBuilder.PastePlan#totalColumns()} answers for the build, which for this layout is
 * two machines added together -- and a sum cannot say whether the two came out the same length or
 * whether one ran twice as far as the other. That matters here in a way it does not elsewhere: the
 * two machines play alternating halves of the same tick, they must stay within earshot of each
 * other, and the whole footprint is as deep as the longer of the two whatever the shorter one
 * does. A lane that finishes early is corridor bought and not used.</p>
 *
 * <p>The split comes off {@link SongBuilder#MACHINE_A_TURNS}, recorded by the walk between the two
 * machines. Columns are then counted exactly as {@code totalColumns} counts them -- wall to wall
 * per turn, staircases excluded -- so lane A plus lane B is the plan's own number, and the probe
 * asserts that rather than trusting it.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*InterleavedLaneColumnsProbe" --offline -i
 * gradlew sweepTest --tests "*InterleavedLaneColumnsProbe" -Dprobe.match="2 lanes" \
 *     -Dprobe.widths=16,24,32 -Dprobe.floors=1,2,3 -Dprobe.set=INTERLEAVED_DYNAMIC_PARITY=false
 * </pre>
 */
@Tag("sweep")
class InterleavedLaneColumnsProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	/**
	 * The corridor one machine's turns account for, measured the way the whole-build number is.
	 *
	 * <p>A copy of {@link SongBuilder.PastePlan#totalColumns()} over a slice of the turn list: the
	 * run that ended at a turn is the longer of that turn's two distances to the walls, because the
	 * wall it came from is the far one. Both machines stand between the same two walls -- one
	 * layout, one frame -- so the same pair of numbers measures either.</p>
	 */
	private static int columns(List<BlockPos> turns, int from, int to, int nearWall, int farWall) {
		int columns = 0;
		for (int index = from; index < to; index++) {
			BlockPos turn = turns.get(index);
			columns += Math.max(Math.abs(turn.getX() - nearWall), Math.abs(farWall - turn.getX()));
		}
		return columns;
	}

	private static List<Integer> numbers(String property, String fallback) {
		List<Integer> values = new ArrayList<>();
		for (String each : System.getProperty(property, fallback).split(",")) {
			values.add(Integer.parseInt(each.strip()));
		}
		return values;
	}

	/** A song's own title, punctuation flattened, so "2-lanes" and "2 lanes" match the same word. */
	private static String flattened(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
	}

	private record Song(String name, List<SongBuilder.EventNote> notes) {
	}

	@Test
	void columnsPerLane() throws Exception {
		Flags.Held held = null;
		try {
			held = Flags.set(System.getProperty("probe.set", ""));
			sweep(held);
		} finally {
			if (held != null) {
				held.putBack();
			}
		}
	}

	private void sweep(Flags.Held held) throws Exception {
		String match = flattened(System.getProperty("probe.match", "2 lanes"));
		List<Integer> widths = numbers("probe.widths", "16,20,24,28,32,40");
		List<Integer> floors = numbers("probe.floors", "1,2,3,4");
		List<Song> songs = new ArrayList<>();
		List<Path> files;
		try (Stream<Path> listed = Files.list(SONGS)) {
			files = listed.filter(file -> file.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			String slug = file.getFileName().toString().replace(".json", "");
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				// Either the title inside the file or the name it is filed under -- the library is
				// slugged, and asking only one of the two silently drops songs.
				if (!flattened(String.valueOf(project.name())).contains(match)
						&& !flattened(slug).contains(match)) {
					continue;
				}
				songs.add(new Song(slug, SongBuilder.gameTickEventNotes(project, true)));
			}
		}
		if (songs.isEmpty()) {
			System.out.println("LANECOLS no song matched \"" + match + "\"");
			return;
		}
		long sumA = 0;
		long sumB = 0;
		int builds = 0;
		int solos = 0;
		int threw = 0;
		int worstGapPercent = 0;
		String worstGap = "";
		for (Song song : songs) {
			long evens = song.notes().stream().filter(note -> note.time() % 2 == 0).count();
			// Where a seam could possibly go, before asking whether one did. A machine sits silent
			// PARITY_SEAM_GT game ticks across a seam, so a song whose own timeline never rests
			// that long has nowhere to put one and the schedule's refusal is arithmetic rather
			// than a decision. Counted over the tick-groups of the whole song, which is generous:
			// a lane playing half the notes rests at least this often.
			List<Integer> ticks = song.notes().stream().map(SongBuilder.EventNote::time).distinct()
				.sorted().toList();
			int restsLongEnough = 0;
			int longestRest = 0;
			for (int index = 1; index < ticks.size(); index++) {
				int rest = ticks.get(index) - ticks.get(index - 1);
				longestRest = Math.max(longestRest, rest);
				if (rest >= SongBuilder.PARITY_SEAM_GT) {
					restsLongEnough++;
				}
			}
			System.out.println("LANECOLS " + song.name() + "  events=" + song.notes().size()
				+ "  onEvenTicks=" + evens + "  onOddTicks=" + (song.notes().size() - evens)
				+ "  ticks=" + ticks.size() + "  restsOf" + SongBuilder.PARITY_SEAM_GT + "gt+="
				+ restsLongEnough + "  longestRest=" + longestRest);
			System.out.println("           size  builtW  lanesA lanesB   colsA   colsB"
				+ "     gap   gap%  notesA notesB  seams   spanZ  faults");
			for (int width : widths) {
				for (int floorCount : floors) {
					builds++;
					try {
						SongBuilder.PastePlan plan =
							SongBuilder.createInterleavedHalfTickPastePlan(
								new BlockPos(0, 64, 0), Direction.EAST, song.notes(),
								new SongBuilder.BuildLimits(16, width, floorCount),
								SongBuilder.WalkStart.HEAD);
						Integer boundary = plan.padding().get(SongBuilder.MACHINE_A_TURNS);
						if (boundary == null) {
							// One machine, which is what a song living on one parity of the game
							// tick gets. There is no lane B to compare against and saying so is
							// the answer, not a row of noughts.
							solos++;
							System.out.println(String.format("        %3dx%d  %5d   SOLO -- one"
								+ " machine, the song sits on a single tick parity  (cols=%d)",
								width, floorCount, plan.builtWidth(), plan.totalColumns()));
							continue;
						}
						List<BlockPos> turns = plan.turns();
						int colsA = columns(turns, 0, boundary, plan.nearWall(), plan.farWall());
						int colsB = columns(turns, boundary, turns.size(), plan.nearWall(),
							plan.farWall());
						// The split has to add back up, or the boundary is in the wrong place and
						// every number above it is decoration.
						if (colsA + colsB != plan.totalColumns()) {
							throw new IllegalStateException("lane split lost columns: " + colsA
								+ " + " + colsB + " != " + plan.totalColumns());
						}
						int gap = Math.abs(colsA - colsB);
						int gapPercent = colsA + colsB == 0 ? 0
							: (int) Math.round(200.0 * gap / (colsA + colsB));
						if (gapPercent > worstGapPercent) {
							worstGapPercent = gapPercent;
							worstGap = song.name() + " " + width + "x" + floorCount;
						}
						sumA += colsA;
						sumB += colsB;
						String faults = (plan.wrongNotes() == 0 ? "" : " wrong=" + plan.wrongNotes())
							+ (plan.missingNotes() == 0 ? "" : " missing=" + plan.missingNotes())
							+ (plan.collisions().isEmpty() ? ""
								: " collisions=" + plan.collisions().size())
							+ (plan.spanX() <= plan.builtWidth() ? ""
								: " over=" + (plan.spanX() - plan.builtWidth()));
						System.out.println(String.format(
							"        %3dx%d  %5d  %6d %6d  %6d  %6d  %+6d  %5d  %6d %6d %6d  %6d %s",
							width, floorCount, plan.builtWidth(), boundary,
							turns.size() - boundary, colsA, colsB, colsB - colsA, gapPercent,
							plan.padding().getOrDefault(SongBuilder.MACHINE_A_NOTES, 0),
							plan.padding().getOrDefault(SongBuilder.MACHINE_B_NOTES, 0),
							// How many times the schedule moved a machine to the other half of the
							// tick. Nought means the two lanes got the raw parity split, which is
							// where an imbalance in the music survives into an imbalance in the
							// corridor.
							plan.padding().getOrDefault("paritySeams", 0),
							plan.spanZ(), faults.isEmpty() ? "clean" : faults.strip()));
					} catch (Exception refused) {
						threw++;
						System.out.println(String.format("        %3dx%d  THREW %s: %.100s", width,
							floorCount, refused.getClass().getSimpleName(),
							String.valueOf(refused.getMessage())));
					}
				}
			}
		}
		System.out.println("LANECOLS" + held.said() + ": " + songs.size() + " songs, " + builds
			+ " builds (" + solos + " solo, " + threw + " threw), colsA=" + sumA + " colsB=" + sumB
			+ " gap=" + Math.abs(sumA - sumB) + " worstGap=" + worstGapPercent + "% at " + worstGap);
	}
}
