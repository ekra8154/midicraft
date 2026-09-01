package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the chord in-game testing found laid as a plain bus at forty wide over two floors.
 *
 * <p>A chord of twenty-four that is too big to cut as a bus and did not open with a head either.
 * The question is which rule took the head, and the answer has to come from the build rather than
 * from reading the shape off a slice.</p>
 */
@Tag("sweep")
class IllitBreachTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		Path file = BreachView.songFile(name);
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
	}

	@Test
	void namesTheRuleThatTookTheHead() throws Exception {
		List<SongBuilder.EventNote> notes = load("illit-do-the-dance");
		SongBuilder.HEADLESS_FOR_ROOM_BEHIND = 0;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 40, 2));
		System.out.println("ILLIT 40x2: " + plan.spanZ() + " deep, "
			+ plan.breaches().size() + " breaching lanes, worst " + plan.worstBreach()
			+ ", wrong " + plan.wrongNotes());
		System.out.println("ILLIT headless for room behind: " + SongBuilder.HEADLESS_FOR_ROOM_BEHIND);
		for (String fault : plan.faults()) {
			System.out.println("ILLIT fault: " + fault);
		}
	}

	/** The second find: forty wide over eight floors, a chord of twenty-four that would not cut. */
	@Test
	void namesTheRuleAtFortyByEight() throws Exception {
		List<SongBuilder.EventNote> notes = load("illit-do-the-dance");
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 40, 8));
		System.out.println("F408 " + plan.spanZ() + " deep, " + plan.breaches().size()
			+ " breaching lanes, worst " + plan.worstBreach() + ", wrong " + plan.wrongNotes());
		for (String fault : plan.faults()) {
			System.out.println("F408 fault: " + fault);
		}
		plan.padding().forEach((key, value) -> {
			if (key.startsWith("planStackedSplit") || key.startsWith("planParity")
					|| key.startsWith("planBusFor")) {
				System.out.println("F408 " + key + " " + value);
			}
		});
	}

	/** And the same count across the library, so the rule can be priced rather than anecdotal. */
	@Test
	void countsTheHeadsTheRuleCostsEverySong() throws Exception {
		List<Path> files;
		try (var listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		int total = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			SongBuilder.HEADLESS_FOR_ROOM_BEHIND = 0;
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
				}
			}
			total += SongBuilder.HEADLESS_FOR_ROOM_BEHIND;
		}
		System.out.println("HEADLESS total " + total);
		System.out.println("HEADONLY total " + SongBuilder.HEAD_ONLY_NEAR_HALVES
			+ " cuts refused a head only because the near half would be the head alone");
	}
}
