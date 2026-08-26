package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How far apart the interleaved mode's two pulses get, song by song.
 *
 * <p>The half-tick drift question, asked of the nested shape: the two machines advance at their own
 * rates -- columns are spent on chords carried and waits fold to their minimum -- so a song whose
 * halves carry unequal weight has its two pulses standing in different parts of the slab at the
 * same moment, and a note block is only audible for 48 blocks. Read off {@link
 * SongBuilder.PastePlan#noteTicks}: both machines share the halved time base, so notes with equal
 * ticks sound within a game tick of each other, and the widest distance among them IS the drift --
 * plus at most a chord's own extent, which is noise against a drift worth worrying about. No
 * machine attribution needed, which is what keeps this probe free of a second geometry.</p>
 */
@Tag("sweep")
class InterleavedDriftProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void measuresDriftAcrossTheLibrary() throws Exception {
		String[] size = System.getProperty("probe.size", "24x1").split("x");
		int width = Integer.parseInt(size[0]);
		int floors = Integer.parseInt(size[1]);
		List<Path> files;
		try (Stream<Path> listed = Files.list(SONGS)) {
			files = listed.filter(file -> file.toString().endsWith(".json")).sorted().toList();
		}
		System.out.println();
		System.out.println("==== drift across the library, interleaved half-tick, " + width + "x"
			+ floors + " ====");
		System.out.println(String.format("  %-46s %-7s %-9s %s",
			"", "worst", "mean", "% of moments past 48"));
		int duals = 0;
		int pastEarshot = 0;
		int worstEver = 0;
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
			long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
			if (evens > 0 && evens < notes.size()) {
				variants.add(notes);
				labels.add(song);
			} else {
				// A one-parity song builds one machine and has nothing to drift from; its doubled
				// copy is the stress case the census uses, and the drift question needs the stress.
				variants.add(notes.stream().map(note -> new SongBuilder.EventNote(
					note.time() / 2, note.trackNumber(), note.order(), note.pitch(),
					note.instrumentBlock())).toList());
				labels.add(song + " 2x");
			}
			for (int variant = 0; variant < variants.size(); variant++) {
				List<SongBuilder.EventNote> built = variants.get(variant);
				long builtEvens = built.stream().filter(note -> note.time() % 2 == 0).count();
				if (builtEvens == 0 || builtEvens == built.size()) {
					continue;
				}
				duals++;
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createInterleavedHalfTickPastePlan(
						new BlockPos(0, 64, 0), Direction.EAST, built,
						new SongBuilder.BuildLimits(16, width, floors), SongBuilder.WalkStart.HEAD);
				} catch (Exception refused) {
					System.out.println(String.format("  %-46s REFUSED %.80s",
						labels.get(variant), String.valueOf(refused.getMessage())));
					continue;
				}
				Map<Integer, List<BlockPos>> byTick = new HashMap<>();
				for (Map.Entry<BlockPos, Integer> note : plan.noteTicks().entrySet()) {
					byTick.computeIfAbsent(note.getValue(), tick -> new ArrayList<>())
						.add(note.getKey());
				}
				int worst = 0;
				long moments = 0;
				long beyond = 0;
				double total = 0;
				for (List<BlockPos> together : byTick.values()) {
					if (together.size() < 2) {
						continue;
					}
					double widest = 0;
					for (int a = 0; a < together.size(); a++) {
						for (int b = a + 1; b < together.size(); b++) {
							widest = Math.max(widest,
								Math.sqrt(together.get(a).distSqr(together.get(b))));
						}
					}
					moments++;
					total += widest;
					if (widest > 48) {
						beyond++;
					}
					worst = Math.max(worst, (int)Math.ceil(widest));
				}
				worstEver = Math.max(worstEver, worst);
				if (worst > 48) {
					pastEarshot++;
				}
				System.out.println(String.format("  %-46s %-7s %-9s %.1f%%",
					labels.get(variant).length() > 45
						? labels.get(variant).substring(0, 45) : labels.get(variant),
					worst + " blk",
					String.format("%.1f", total / Math.max(1, moments)),
					100.0 * beyond / Math.max(1, moments)));
			}
		}
		System.out.println();
		System.out.println("  " + duals + " dual builds, " + pastEarshot
			+ " with some moment past earshot (48); worst anywhere " + worstEver + " blocks");
	}
}
