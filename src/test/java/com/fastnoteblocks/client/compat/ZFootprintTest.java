package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: how deep a build ends up, across the three versions that changed it.
 *
 * <p>Width and floors are chosen by the player, so X and Y are settings rather than results. Z is
 * the one dimension the builder decides, which makes it the size number worth arguing about.</p>
 *
 * <p>The three versions are reached by their own flags rather than by checking out commits. Both
 * flags gate every branch of their change, and with {@code STACKED_BUS_HEADS} off the later fixes
 * to that shape are inert -- they only ever branch on {@code STACKED_BUS}.</p>
 */
class ZFootprintTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.CHEAP_SPLIT_DESCENT = true;
		// The live default, not the one that was live when this probe was written. Restoring a stale
		// value leaves every test that runs after this one building a different machine, and that is
		// how the same code gave a green suite and a red suite on two consecutive runs.
		SongBuilder.STACKED_BUS_HEADS = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static List<SongBuilder.EventNote> song(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
				raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
	}

	@Test
	void depthAcrossVersions() throws Exception {
		String[] songs = {"illit-do-the-dance", "big-shot", "sunset-of-seven-suns-we-did-it"};
		System.out.println("Z  song / cfg | zFootprint  before -> cheapDescent -> stackedBus"
			+ " | spanX | breaches");
		for (String name : songs) {
			List<SongBuilder.EventNote> notes = song(name);
			long[] totals = new long[3];
			for (int floors : new int[] {2, 4, 6}) {
				for (int width : new int[] {12, 24, 36}) {
					int[] z = new int[3];
					int[] x = new int[3];
					int[] breaches = new int[3];
					for (int version = 0; version < 3; version++) {
						SongBuilder.CHEAP_SPLIT_DESCENT = version >= 1;
						SongBuilder.STACKED_BUS_HEADS = version == 2;
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						z[version] = plan.spanZ();
						x[version] = plan.spanX();
						breaches[version] = plan.breaches().size();
						totals[version] += plan.spanZ();
					}
					System.out.println("Z  " + name + " f" + floors + " w" + width
						+ " | z " + z[0] + " -> " + z[1] + " -> " + z[2]
						+ " | x " + x[0] + " -> " + x[1] + " -> " + x[2]
						+ " | breaches " + breaches[0] + " -> " + breaches[1] + " -> "
						+ breaches[2]);
				}
			}
			System.out.println("Z  == " + name + " total z " + totals[0] + " -> " + totals[1]
				+ " -> " + totals[2]);
		}
	}

	/** Depth over every real song and every config, which is the "overall" question. */
	@Test
	void depthOverTheWholeLibrary() throws Exception {
		java.util.List<Path> files;
		try (java.util.stream.Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		long[] z = new long[3];
		long[] breaches = new long[3];
		java.util.TreeMap<String, String> perSong = new java.util.TreeMap<>();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = song(name);
			if (notes.isEmpty()) {
				continue;
			}
			long[] here = new long[3];
			for (int version = 0; version < 3; version++) {
				SongBuilder.CHEAP_SPLIT_DESCENT = version >= 1;
				SongBuilder.STACKED_BUS_HEADS = version == 2;
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						z[version] += plan.spanZ();
						here[version] += plan.spanZ();
						breaches[version] += plan.breaches().size();
					}
				}
			}
			perSong.put(name, here[0] + " -> " + here[1] + " -> " + here[2]
				+ "  (" + (here[2] - here[0]) + ")");
		}
		perSong.forEach((s, row) -> System.out.println("ZALL " + String.format("%-46s", s) + row));
		System.out.println("ZALL TOTAL z " + z[0] + " -> " + z[1] + " -> " + z[2]);
		System.out.println("ZALL TOTAL breaches " + breaches[0] + " -> " + breaches[1]
			+ " -> " + breaches[2]);
	}
}
