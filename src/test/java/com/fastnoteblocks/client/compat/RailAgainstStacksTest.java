package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What runs cost the songs that are actually made of big chords.
 *
 * <p>Every song the two-rail shape was developed against tops out at three notes, so no build in
 * any of those tests holds a single stacked module -- which is to say none of them can show the one
 * contention in question. A run's floor notes sit at the lane's own floor level, exactly
 * where the lane alongside hangs the low half of a stacked chord, and a stacked centre's instrument
 * blocks conduct sideways. So the songs to measure are the ones with both: runs of small chords,
 * and chords of five and up in the lanes beside them.</p>
 */
@Tag("sweep")
class RailAgainstStacksTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void measuresEverySongInTheLibrary() throws Exception {
		System.out.println(String.format("%-52s %5s %7s %7s %7s %7s %7s",
			"song", "w/f", "big%", "depthOff", "depthOn", "wrongOff", "wrongOn"));
		List<Path> songs = new ArrayList<>();
		try (var listing = Files.list(SONGS)) {
			listing.filter(path -> path.toString().endsWith(".json")).sorted().forEach(songs::add);
		}
		int worseOn = 0;
		int betterOn = 0;
		var wrongBySong = new TreeMap<String, String>();
		var census = new TreeMap<String, Integer>();
		var breachBySong = new TreeMap<String, String>();
		var deadBySong = new TreeMap<String, String>();
		var totals = new TreeMap<String, Integer>();
		for (Path path : songs) {
			String name = path.getFileName().toString().replace(".json", "");
			List<SongBuilder.EventNote> notes;
			try {
				notes = load(path);
			} catch (RuntimeException | java.io.IOException broken) {
				continue;
			}
			if (notes.isEmpty()) {
				continue;
			}
			int big = bigChordShare(notes);
			for (int[] size : new int[][] {{16, 1}, {16, 3}, {20, 2}, {24, 3}, {32, 5}, {44, 3}, {44, 6}}) {
				SongBuilder.TWO_RAIL_RUNS = false;
				SongBuilder.PastePlan off = plan(notes, size[0], size[1]);
				SongBuilder.TWO_RAIL_RUNS = true;
				SongBuilder.PastePlan on = plan(notes, size[0], size[1]);
				if (off == null || on == null) {
					continue;
				}
				if (on.wrongNotes() > off.wrongNotes()) {
					wrongBySong.merge(name, size[0] + "/" + size[1] + ":"
						+ off.wrongNotes() + "->" + on.wrongNotes(), (a, b) -> a + " " + b);
				}
				// The two faults a run could bring that a depth figure would never show. A breach is
				// blocks laid past the wall the lane was measured against; a dead wire is the reader
				// saying so, which every plan already asks itself under MARK_UNREACHED.
				int breachOff = breaches(off);
				int breachOn = breaches(on);
				int deadOff = unreached(off);
				int deadOn = unreached(on);
				totals.merge("breach off", breachOff, Integer::sum);
				totals.merge("breach on", breachOn, Integer::sum);
				totals.merge("wrong off", off.wrongNotes(), Integer::sum);
				totals.merge("wrong on", on.wrongNotes(), Integer::sum);
				totals.merge("dead off", deadOff, Integer::sum);
				totals.merge("dead on", deadOn, Integer::sum);
				if (breachOn > breachOff) {
					breachBySong.merge(name, size[0] + "/" + size[1] + ":"
						+ breachOff + "->" + breachOn, (a, b) -> a + " " + b);
				}
				if (deadOn > deadOff) {
					deadBySong.merge(name, size[0] + "/" + size[1] + ":"
						+ deadOff + "->" + deadOn, (a, b) -> a + " " + b);
				}
				if (on.spanZ() > off.spanZ()) {
					worseOn++;
				} else if (on.spanZ() < off.spanZ()) {
					betterOn++;
				}
				on.padding().forEach((key, count) -> {
					if (key.equals("railHead") || key.equals("railFromStack")) {
						census.merge(key, count, Integer::sum);
					}
				});
				System.out.println(String.format("%-52s %5s %6d%% %7d %7d %7d %7d",
					name, size[0] + "/" + size[1], big, off.spanZ(), on.spanZ(),
					off.wrongNotes(), on.wrongNotes()));
			}
		}
		System.out.println("STACKS runs opened " + census + " over the whole library");
		System.out.println("STACKS depth better on " + betterOn + " sizes, worse on " + worseOn);
		System.out.println("STACKS totals " + totals);
		System.out.println("STACKS songs where runs add wrong notes: " + wrongBySong.size());
		wrongBySong.forEach((name, where) -> System.out.println("STACKS  " + name + "  " + where));
		System.out.println("STACKS songs where runs add breach: " + breachBySong.size());
		breachBySong.forEach((name, where) -> System.out.println("STACKS breach " + name + "  " + where));
		System.out.println("STACKS songs where runs add a dead wire: " + deadBySong.size());
		deadBySong.forEach((name, where) -> System.out.println("STACKS dead " + name + "  " + where));
	}

	/** What the wrong notes a run brings actually say, in the song that has the most of them. */
	@Test
	void namesTheWrongNotes() throws Exception {
		for (String name : List.of("untitled-composition-5", "porter-robinson-goodbye-to-a-world",
				"harder-better-faster-stronger-daft-punk")) {
			List<SongBuilder.EventNote> notes = load(SONGS.resolve(name + ".json"));
			SongBuilder.TWO_RAIL_RUNS = true;
			// Which is what puts names on the blocks, so a fault can say which shape is on each side
			// rather than leaving it to be read off a dump.
			SongBuilder.DEBUG_PASTE = true;
			SongBuilder.PastePlan on = plan(notes, 44, 3);
			SongBuilder.DEBUG_PASTE = false;
			if (on == null) {
				continue;
			}
			System.out.println("WRONG " + name + " 44/3: " + on.wrongNotes() + " wrong notes");
			on.faults().stream().filter(fault -> fault.startsWith("the note")).limit(6)
				.forEach(fault -> System.out.println("WRONG   " + fault));
			dumpFirst(on);
		}
	}

	/** The blocks around the first wrong note, so the two shapes either side of it can be named. */
	private static void dumpFirst(SongBuilder.PastePlan plan) {
		String fault = plan.faults().stream().filter(text -> text.startsWith("the note")).findFirst()
			.orElse(null);
		if (fault == null) {
			return;
		}
		String[] words = fault.split(" ");
		int x = Integer.parseInt(words[3]);
		int y = Integer.parseInt(words[4]);
		int z = Integer.parseInt(words[5]);
		var world = new java.util.HashMap<BlockPos, String>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parts[4]);
		}
		for (int level = y + 2; level >= y - 2; level--) {
			System.out.println("WRONG   y=" + level + " (the note is at " + x + " " + y + " " + z
				+ ", z across, x down)");
			for (int row = x - 2; row <= x + 2; row++) {
				StringBuilder line = new StringBuilder(String.format("WRONG    x=%3d ", row));
				for (int column = z - 3; column <= z + 2; column++) {
					String block = world.get(new BlockPos(row, level, column));
					line.append(String.format(" %-18s", block == null ? "."
						: block.replace("minecraft:", "").replaceAll("\\[.*", "*")));
				}
				System.out.println(line);
			}
		}
	}

	/** Blocks laid past the wall, summed over every lane that did it. */
	private static int breaches(SongBuilder.PastePlan plan) {
		return plan.breaches().stream().mapToInt(Integer::intValue).sum();
	}

	/**
	 * Note blocks the signal never reaches, off the fault every plan already writes for itself.
	 *
	 * <p>{@code MARK_UNREACHED} reads every build back inside {@code createPastePlan} and says so,
	 * so a dead wire is already counted here -- it only ever needed reporting.</p>
	 */
	private static int unreached(SongBuilder.PastePlan plan) {
		for (String fault : plan.faults()) {
			if (fault.contains("would never be triggered")) {
				return Integer.parseInt(fault.split(" ")[0]);
			}
		}
		return 0;
	}

	/** What share of this song's notes stand in a chord big enough to be built stacked. */
	private static int bigChordShare(List<SongBuilder.EventNote> notes) {
		var perTick = new TreeMap<Integer, Integer>();
		for (SongBuilder.EventNote note : notes) {
			perTick.merge(note.time(), 1, Integer::sum);
		}
		int big = 0;
		for (int count : perTick.values()) {
			if (count >= 5) {
				big += count;
			}
		}
		return big * 100 / notes.size();
	}

	private static SongBuilder.PastePlan plan(List<SongBuilder.EventNote> notes, int width,
			int floors) {
		try {
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, width, floors));
		} catch (RuntimeException refused) {
			return null;
		}
	}

	private static List<SongBuilder.EventNote> load(Path path) throws java.io.IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
