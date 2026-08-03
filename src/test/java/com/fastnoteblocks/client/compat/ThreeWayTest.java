package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the three places a stacked head could be allowed, scored against each other.
 *
 * <p>All three keep the cheap descent, which is on main. What varies is where the head is offered:
 * nowhere, only to a chord being cut across a staircase, or to every long chord.</p>
 */
@Tag("sweep")
class ThreeWayTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.STACKED_BUS_HEADS = true;
		SongBuilder.STACKED_SPLIT_HEADS = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");
	private static final String[] NAMES = {"none", "splitOnly", "everywhere"};

	@Test
	void scoresThem() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		// 0 = guardian, 1 = other real songs, 2 = synthetic stress fixtures
		long[][] catBreach = new long[3][3];
		long[][] catBlocks = new long[3][3];
		long[][] mismatch = new long[3][3];
		long[] z = new long[3];
		long[] breaches = new long[3];
		long[] breachBlocks = new long[3];
		long[] pad = new long[3];
		long[] blocks = new long[3];
		long[] wrong = new long[3];
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
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
			int cat = name.startsWith("ultra-") ? 2
				: name.equals("deltarune-ch-4-guardian") ? 0 : 1;
			for (int mode = 0; mode < 3; mode++) {
				SongBuilder.CHEAP_SPLIT_DESCENT = true;
				SongBuilder.STACKED_SPLIT_HEADS = mode >= 1;
				SongBuilder.STACKED_BUS_HEADS = mode == 2;
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						z[mode] += plan.spanZ();
						blocks[mode] += plan.commands().size();
						for (int b : plan.breaches()) {
							breaches[mode]++;
							breachBlocks[mode] += b;
							catBreach[mode][cat]++;
							catBlocks[mode][cat] += b;
						}
						// Every place the walk built a shape the planner did not predict. If
						// "everywhere" breaches more than "cuts only", this is where to look.
						for (Map.Entry<String, Integer> e : plan.padding().entrySet()) {
							String k = e.getKey();
							if (k.startsWith("planShort") || k.equals("planBusForRoom")
									|| k.equals("planBusForSignal") || k.equals("parity")) {
								mismatch[mode][cat] += e.getValue();
							}
						}
						for (Map.Entry<String, Integer> e : plan.padding().entrySet()) {
							String k = e.getKey();
							if (!k.equals("corner") && !k.equals("busHandover")
									&& !k.startsWith("swap") && !k.startsWith("plan")) {
								pad[mode] += e.getValue();
							}
						}
						for (String fault : plan.faults()) {
							if (fault.startsWith("the note")) {
								wrong[mode]++;
							}
						}
					}
				}
			}
		}
		for (int mode = 0; mode < 3; mode++) {
			System.out.println("THREEWAY " + NAMES[mode]
				+ " | z " + z[mode]
				+ " | breaches " + breaches[mode] + " (" + breachBlocks[mode] + " blocks)"
				+ " | pad " + pad[mode]
				+ " | blocks " + blocks[mode]
				+ " | wrong " + wrong[mode]);
			System.out.println("THREEWAY " + NAMES[mode]
				+ "   breaches  guardian " + catBreach[mode][0]
				+ " | otherReal " + catBreach[mode][1]
				+ " | synthetic " + catBreach[mode][2]);
			System.out.println("THREEWAY " + NAMES[mode]
				+ "   blocks    guardian " + catBlocks[mode][0]
				+ " | otherReal " + catBlocks[mode][1]
				+ " | synthetic " + catBlocks[mode][2]);
			System.out.println("THREEWAY " + NAMES[mode]
				+ "   surprises guardian " + mismatch[mode][0]
				+ " | otherReal " + mismatch[mode][1]
				+ " | synthetic " + mismatch[mode][2]);
		}
	}
}
