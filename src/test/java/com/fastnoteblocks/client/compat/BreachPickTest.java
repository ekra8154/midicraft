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
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: every real-song breach left, smallest first, with the fault that names it.
 *
 * <p>Sorted small-first on purpose. A lane one column past its wall is the one whose cause fits in
 * a single slice; a lane forty-seven past has been going wrong for a while by the time it shows.</p>
 */
@Tag("sweep")
class BreachPickTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private record Case(String song, int width, int floors, int worst, int lanes, String fault)
			implements Comparable<Case> {
		@Override
		public int compareTo(Case other) {
			int byWorst = Integer.compare(worst, other.worst);
			return byWorst != 0 ? byWorst : Integer.compare(lanes, other.lanes);
		}
	}

	@Test
	void listsEveryRealBreachSmallestFirst() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		List<Case> cases = new ArrayList<>();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 1; floors <= 8; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					if (plan.breaches().isEmpty()) {
						continue;
					}
					String fault = plan.faults().stream()
						.filter(text -> text.startsWith("a lane turned -"))
						.findFirst().orElse("");
					cases.add(new Case(name, width, floors, plan.worstBreach(),
						plan.breaches().size(), fault));
				}
			}
		}
		cases.sort(null);
		long guardian = cases.stream()
			.filter(one -> one.song().equals("deltarune-ch-4-guardian")).count();
		System.out.println("PICK " + cases.size() + " breaching builds across real songs, "
			+ guardian + " of them Guardian");
		int shown = 0;
		for (Case one : cases) {
			// Guardian breaches in bulk and drowns everything else out. The small ones from the
			// other songs are the readable cases: one lane, a column or two, one slice to look at.
			if (one.song().equals("deltarune-ch-4-guardian")) {
				continue;
			}
			if (shown++ >= 25) {
				break;
			}
			System.out.println("PICK " + one.song() + " w" + one.width() + " f" + one.floors()
				+ " lanes=" + one.lanes() + " worst=" + one.worst() + " :: " + one.fault());
		}
	}
}
