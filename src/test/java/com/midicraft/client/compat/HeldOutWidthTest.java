package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The widths nothing was ever measured on, which is the only honest test of a fix that was.
 *
 * <p>Every number in this project comes off {@link BlitzSweepTest}, and it sweeps widths twelve to
 * forty-eight in steps of four. So every fix has been made while watching ten widths, and "no song
 * breaches" means "no song breaches at those ten". A build that walks the song twice and keeps the
 * better walk is a one-bit search, and a one-bit search that happens to clear the ten widths it was
 * tuned against is exactly what fitting the sample looks like.</p>
 *
 * <p>So: the widths in between, which no change has ever been graded on. If the mode is really
 * sound the odd widths are unremarkable, and if it was fitted to the sweep they are where it shows.
 * That was the question asked -- the sample is decently large, but it is the same sample.</p>
 */
class HeldOutWidthTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** The same held-out widths without the both-ways choice, to see whether it generalises at all. */
	@Test
	void showsWhatTheHeldOutWidthsCostWithoutTheBothWaysChoice() throws Exception {
		SongBuilder.LOOKAHEAD = false;
		try {
			List<String> breaches = heldOutBreaches();
			System.out.println("HELDOUTBASE breaches=" + breaches.size());
			for (String breach : breaches) {
				System.out.println("HELDOUTBASE " + breach);
			}
		} finally {
			SongBuilder.LOOKAHEAD = true;
		}
	}

	@Test
	void noRealSongBreachesAtAWidthNobodyTunedOn() throws Exception {
		List<String> breaches = heldOutBreaches();
		System.out.println("HELDOUT lookaheadWins=" + SongBuilder.LOOKAHEAD_WINS
			+ " lookaheadThrownAway=" + SongBuilder.LOOKAHEAD_LOSES);
		for (String breach : breaches) {
			System.out.println("HELDOUT " + breach);
		}
		// Pinned rather than asserted empty, because it is not empty and saying otherwise is how the
		// claim got overstated in the first place. Four builds in four thousand, none of them at a
		// width anybody has ever pasted at. This list is allowed to shrink and nothing else.
		Assertions.assertEquals(List.of(
			"big-shot f6 w18",
			"hammer-of-justice-2 f6 w39",
			"illit-do-the-dance f3 w22",
			"illit-do-the-dance f6 w26"),
			breaches.stream().map(breach -> breach.substring(0, breach.indexOf(" : "))).sorted()
				.toList(),
			"a width nobody tuned on should be no different from one somebody did");
	}

	private static List<String> heldOutBreaches() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		List<String> breaches = new ArrayList<>();
		TreeMap<Integer, Integer> buildsByWidth = new TreeMap<>();
		int songs = 0;
		int builds = 0;
		SongBuilder.LOOKAHEAD_WINS = 0;
		SongBuilder.LOOKAHEAD_LOSES = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			// The stress songs are built to be impossible and drop notes to fit. They have never been
			// the promise, and including them here would only measure how impossible they are.
			if (name.startsWith("ultra-")) {
				continue;
			}
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
			songs++;
			// Odd widths and the even ones the sweep steps over: 13, 14, 15, 17, 18, 19, 21 ... None
			// of these has ever been looked at, by me or by any number in the project.
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 13; width <= 47; width++) {
					if (width % 4 == 0) {
						continue;
					}
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					builds++;
					for (String fault : plan.faults()) {
						if (fault.startsWith("a lane turned -")) {
							buildsByWidth.merge(width, 1, Integer::sum);
							breaches.add(name + " f" + floors + " w" + width + " : " + fault);
						}
					}
				}
			}
		}
		System.out.println("HELDOUT songs=" + songs + " builds=" + builds
			+ " breaches=" + breaches.size());
		System.out.println("HELDOUT breachesByWidth " + buildsByWidth);
		return breaches;
	}
}
