package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Scratch probe: how often a chord wants a stacked head and does not get one, and what took it.
 *
 * <p>"Stacked buses everywhere" is a claim about reach, and reach is not something the flag can be
 * read for -- the flag says the shape is allowed, and five separate rules downstream can still
 * refuse it. This counts the refusals against the builds, so the claim can be checked rather than
 * repeated.</p>
 */
@Tag("sweep")
class StackedReachTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	/** Every counter that ends with a chord not being built in the shape it asked for. */
	private static final List<String> REFUSALS = List.of(
		"planBusForBehindStackedBus", "planBusForBehind",
		"planBusForTurnStackedBus", "planBusForTurn",
		"planBusForRoom", "planBusForSignal",
		"planParityGaveUpSlack", "planParityGaveUpTight",
		"planStackedSplitNoRoomBehind", "planStackedSplitClashed");

	@Test
	void countsWhatTakesTheHeadAway() throws Exception {
		SongBuilder.STACKED_BUS_HEADS = true;
		SongBuilder.STACKED_SPLIT_HEADS = true;
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		TreeMap<String, Long> tally = new TreeMap<>();
		long builds = 0;
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
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					builds++;
					for (Map.Entry<String, Integer> e : plan.padding().entrySet()) {
						if (REFUSALS.contains(e.getKey())
								|| e.getKey().startsWith("planStackedSplit")
								|| e.getKey().equals("busHandover")) {
							tally.merge(e.getKey(), (long) e.getValue(), Long::sum);
						}
					}
				}
			}
		}
		System.out.println("REACH builds " + builds);
		tally.forEach((k, v) -> System.out.println("REACH " + k + " " + v));
	}
}
