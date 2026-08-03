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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: what the stacked head costs and saves, per song and per config. */
class StackedBusCostTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.STACKED_BUS_HEADS = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static ComposerProject load(Path file) throws Exception {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			return new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
	}

	/** Pad that is a column a chord could have used, split into long stretches and single cells. */
	private static long waste(Map<String, Integer> padding, String only) {
		long total = 0;
		for (Map.Entry<String, Integer> entry : padding.entrySet()) {
			String key = entry.getKey();
			if (key.equals("corner") || key.equals("stackedBusTransition")
					|| key.startsWith("swap") || key.startsWith("plan")) {
				continue;
			}
			if (only == null || key.startsWith(only)) {
				total += entry.getValue();
			}
		}
		return total;
	}

	@Test
	void doTheDanceInDetail() throws Exception {
		List<SongBuilder.EventNote> notes = SongBuilder.eventNotes(
			load(SONGS.resolve("illit-do-the-dance.json")).toSequenceTracks(Set.of(), true));
		System.out.println("DTD  cfg        pad(off->on)  closing      parity      "
			+ "len   depth  hgt   volume        blocks");
		for (int floors : new int[] {4, 6}) {
			for (int width : new int[] {12, 20, 36}) {
				long[] pad = new long[2];
				long[] closing = new long[2];
				long[] parity = new long[2];
				long[] vol = new long[2];
				long[] blocks = new long[2];
				int[] len = new int[2];
				int[] depth = new int[2];
				int[] hgt = new int[2];
				for (int on = 0; on <= 1; on++) {
					SongBuilder.STACKED_BUS_HEADS = on == 1;
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					pad[on] = waste(plan.padding(), null);
					closing[on] = waste(plan.padding(), "padClosing");
					parity[on] = waste(plan.padding(), "parity");
					vol[on] = (long) plan.width() * plan.depth() * plan.height();
					blocks[on] = plan.commands().size();
					len[on] = plan.width();
					depth[on] = plan.depth();
					hgt[on] = plan.height();
				}
				System.out.println("DTD f" + floors + " w" + width
					+ " | pad " + pad[0] + "->" + pad[1]
					+ " | closing " + closing[0] + "->" + closing[1]
					+ " | parity " + parity[0] + "->" + parity[1]
					+ " | len " + len[0] + "->" + len[1]
					+ " depth " + depth[0] + "->" + depth[1]
					+ " hgt " + hgt[0] + "->" + hgt[1]
					+ " | vol " + vol[0] + "->" + vol[1]
					+ " | blocks " + blocks[0] + "->" + blocks[1]);
			}
		}
	}

	@Test
	void everyRealSong() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		TreeMap<String, String> rows = new TreeMap<>();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(load(file).toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			long[] pad = new long[2];
			long[] vol = new long[2];
			long[] blocks = new long[2];
			long[] len = new long[2];
			for (int on = 0; on <= 1; on++) {
				SongBuilder.STACKED_BUS_HEADS = on == 1;
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						pad[on] += waste(plan.padding(), null);
						vol[on] += (long) plan.width() * plan.depth() * plan.height();
						blocks[on] += plan.commands().size();
						len[on] += plan.width();
					}
				}
			}
			rows.put(name, String.format("pad %6d->%-6d (%+5.1f%%)  vol %9d->%-9d (%+5.1f%%)  "
				+ "len %6d->%-6d (%+5.1f%%)  blocks %7d->%-7d",
				pad[0], pad[1], pct(pad), vol[0], vol[1], pct(vol), len[0], len[1], pct(len),
				blocks[0], blocks[1]));
		}
		rows.forEach((song, row) -> System.out.println("SONG " + String.format("%-48s", song) + row));
	}


	/** The whole ledger for real songs: every padding reason, flag off against flag on. */
	@Test
	void whereThePadWent() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		TreeMap<String, long[]> byKey = new TreeMap<>();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(load(file).toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (int on = 0; on <= 1; on++) {
				SongBuilder.STACKED_BUS_HEADS = on == 1;
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						for (Map.Entry<String, Integer> e : plan.padding().entrySet()) {
							String k = e.getKey();
							if (k.startsWith("planParity")) {
								byKey.computeIfAbsent(k, ignored -> new long[2])[on] += e.getValue();
								continue;
							}
							if (k.equals("corner") || k.equals("stackedBusTransition")
									|| k.startsWith("swap") || k.startsWith("plan")) {
								continue;
							}
							byKey.computeIfAbsent(k, ignored -> new long[2])[on] += e.getValue();
						}
					}
				}
			}
		}
		long off = 0;
		long on = 0;
		for (Map.Entry<String, long[]> e : byKey.entrySet()) {
			off += e.getValue()[0];
			on += e.getValue()[1];
			System.out.println("LEDGER " + e.getKey() + " " + e.getValue()[0] + " -> "
				+ e.getValue()[1] + " (" + (e.getValue()[1] - e.getValue()[0]) + ")");
		}
		System.out.println("LEDGER TOTAL " + off + " -> " + on + " (" + (on - off) + ")");
	}


	/** Nudge the head, or give it up: same chord, both ways, over the real library. */
	@Test
	void nudgeAgainstGivingUp() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		long[] pad = new long[2];
		long[] parity = new long[2];
		long[] vol = new long[2];
		long[] len = new long[2];
		long[] blocks = new long[2];
		long[] breaches = new long[2];
		long[] breachBlocks = new long[2];
		long[] wrong = new long[2];
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(load(file).toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (int give = 0; give <= 1; give++) {
				SongBuilder.STACKED_BUS_HEADS = true;
				SongBuilder.PARITY_PREFERS_BUS = give == 1;
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						pad[give] += waste(plan.padding(), null);
						parity[give] += waste(plan.padding(), "parity");
						vol[give] += (long) plan.width() * plan.depth() * plan.height();
						len[give] += plan.width();
						blocks[give] += plan.commands().size();
						for (int b : plan.breaches()) {
							breaches[give]++;
							breachBlocks[give] += b;
						}
						for (String fault : plan.faults()) {
							if (fault.startsWith("the note")) {
								wrong[give]++;
							}
						}
					}
				}
			}
		}
		SongBuilder.PARITY_PREFERS_BUS = false;
		System.out.println("NUDGE  nudge -> giveUp");
		System.out.println("NUDGE  pad         " + pad[0] + " -> " + pad[1]);
		System.out.println("NUDGE  parity      " + parity[0] + " -> " + parity[1]);
		System.out.println("NUDGE  length      " + len[0] + " -> " + len[1]);
		System.out.println("NUDGE  volume      " + vol[0] + " -> " + vol[1]);
		System.out.println("NUDGE  blocks      " + blocks[0] + " -> " + blocks[1]);
		System.out.println("NUDGE  breaches    " + breaches[0] + " -> " + breaches[1]);
		System.out.println("NUDGE  breachBlks  " + breachBlocks[0] + " -> " + breachBlocks[1]);
		System.out.println("NUDGE  wrongNotes  " + wrong[0] + " -> " + wrong[1]);
	}

	private static double pct(long[] both) {
		return both[0] == 0 ? 0.0 : 100.0 * (both[1] - both[0]) / both[0];
	}
}
