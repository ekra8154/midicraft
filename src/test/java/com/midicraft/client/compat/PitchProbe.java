package com.midicraft.client.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: every song read back, with the seam riding on. */
@Tag("sweep")
class PitchProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	@Test
	void everySong() throws Exception {
		List<String> songs = new ArrayList<>();
		try (Stream<Path> files = Files.list(SONGS)) {
			files.filter(path -> path.toString().endsWith(".json"))
				.map(path -> path.getFileName().toString().replace(".json", ""))
				.sorted().forEach(songs::add);
		}
		List<int[]> sizes = new ArrayList<>();
		for (String size : text("sizes", "10x1,24x1,16x2").split(",")) {
			String[] part = size.split("x");
			sizes.add(new int[] {Integer.parseInt(part[0].strip()),
				Integer.parseInt(part[1].strip())});
		}
		Flags.Held held = Flags.set(text("set", ""));
		System.out.println("  flags:" + held.said());
		int builds = 0;
		int dirty = 0;
		int silent = 0;
		int breached = 0;
		for (String song : songs) {
			for (int[] size : sizes) {
				FaultView.Build built;
				try {
					built = FaultView.of(song, SongBuilder.PasteMode.INTERLEAVED_HALF_TICK,
						size[0], size[1], 4, false);
				} catch (Exception | StackOverflowError refused) {
					System.out.println(String.format(Locale.ROOT, "%-46s %2dx%d  THREW %s",
						song, size[0], size[1], refused));
					continue;
				}
				builds++;
				int unreached = built.reading().unreachedNotes();
				// The plan's own breach count, not a cell tally: everything from the input
				// spine to a note hanging off the lane sits outside nearWall..farWall in a
				// perfectly healthy build, so counting cells says nothing at all.
				int outside = built.plan().breaches().stream().mapToInt(Integer::intValue).sum();
				silent += unreached;
				breached += outside;
				if (unreached > 0 || outside > 0) {
					dirty++;
					System.out.println(String.format(Locale.ROOT,
						"%-46s %2dx%d  silent %5d/%-5d  breached columns %5d  rode %d",
						song, size[0], size[1], unreached, built.reading().noteBlocks(), outside,
						built.plan().padding().getOrDefault("paritySeamRodeItsTurn", 0)));
				}
			}
		}
		held.putBack();
		System.out.println("  " + dirty + " of " + builds + " builds are dirty; " + silent
			+ " silent notes and " + breached + " breached columns in total");
	}
}
