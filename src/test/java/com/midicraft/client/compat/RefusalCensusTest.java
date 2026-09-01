package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * What the refusals actually are, rather than what they are assumed to be.
 *
 * <p>A build that will not paste is worse than one that plays with gaps, and the first question
 * about ninety-odd of them is whether they are one fault or several. A collision names two blocks --
 * the one already standing and the one that wanted the same cell -- and that pair is the fingerprint
 * of the shape that laid it. Bucketing by the pair says how many causes there are before any of them
 * is chased.</p>
 */
@Tag("sweep")
class RefusalCensusTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/**
	 * The same census again, but naming the two shapes rather than the two blocks.
	 *
	 * <p>Three sampled refusals were all a nudged stacked chord against the four-cell descent. Three
	 * is a sample; this is the census, and the point of it is the count that is <em>not</em> that --
	 * a refusal with some other pair of shapes in it is a second fault and would need its own fix.</p>
	 */
	@Test
	void countsEveryRefusalByWhichShapesCollided() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		try {
			Map<String, Integer> byShapes = new TreeMap<>();
			int marked = 0;
			int cells = 0;
			for (Path file : songs()) {
				String name = file.getFileName().toString().replace(".json", "");
				if (name.startsWith("ultra-")) {
					continue;
				}
				List<SongBuilder.EventNote> notes = load(name);
				if (notes.isEmpty()) {
					continue;
				}
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan;
						try {
							plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
						} catch (RuntimeException stillRefused) {
							byShapes.merge("REFUSED ANYWAY: " + stillRefused.getMessage()
								.replaceAll("-?\\d+", "#"), 1, Integer::sum);
							continue;
						}
						if (plan.collisions().isEmpty()) {
							continue;
						}
						marked++;
						cells += plan.collisions().size();
						for (String what : plan.collisions().values()) {
							// The blocks differ from song to song and the shapes do not, so only the two
							// bracketed names are kept.
							byShapes.merge(what.replaceAll("minecraft:\\S+ ", ""), 1, Integer::sum);
						}
					}
				}
			}
			System.out.println("SHAPES " + marked + " builds collided, " + cells + " cells");
			byShapes.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.forEach(pair -> System.out.println("SHAPES  " + pair.getValue() + "x  "
					+ pair.getKey()));
		} finally {
			SongBuilder.DEBUG_PASTE = false;
		}
	}

	private static List<Path> songs() throws Exception {
		try (Stream<Path> listing = Files.list(SONGS)) {
			return listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
	}

	@Test
	void countsEveryRefusalByWhatCollided() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		Map<String, Integer> byKind = new TreeMap<>();
		Map<String, Integer> bySong = new TreeMap<>();
		Map<String, String> firstOfKind = new LinkedHashMap<>();
		int builds = 0;
		int refused = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					builds++;
					try {
						SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException stopped) {
						refused++;
						String kind = kindOf(stopped);
						byKind.merge(kind, 1, Integer::sum);
						bySong.merge(name, 1, Integer::sum);
						firstOfKind.putIfAbsent(kind, name + " w" + width + " f" + floors + " :: "
							+ stopped.getMessage());
					}
				}
			}
		}
		System.out.println("REFUSE " + refused + " of " + builds + " builds refused");
		for (Map.Entry<String, Integer> kind : byKind.entrySet()) {
			System.out.println("REFUSE  " + kind.getValue() + "x  " + kind.getKey());
			System.out.println("REFUSE       first: " + firstOfKind.get(kind.getKey()));
		}
		for (Map.Entry<String, Integer> song : bySong.entrySet()) {
			System.out.println("REFUSE  song " + song.getKey() + ": " + song.getValue());
		}
	}

	/** The fingerprint: the two blocks that wanted the cell, with the coordinates dropped. */
	private static String kindOf(RuntimeException stopped) {
		String message = String.valueOf(stopped.getMessage());
		if (!message.startsWith("Placement layout collision at ")) {
			return stopped.getClass().getSimpleName() + ": "
				+ message.replaceAll("-?\\d+", "#");
		}
		int colon = message.indexOf(": ", "Placement layout collision at ".length());
		return "collision " + (colon < 0 ? message : message.substring(colon + 2))
			.replace(" is already there and ", " <- ")
			.replace(" wants the same block", "");
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
