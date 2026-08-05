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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one build the shed silences, and whether it silences it by overrunning a repeater.
 *
 * <p>The shed was offered on the arithmetic that a bus carries two notes a cell, so an odd tail has
 * half a cell spare and a note added to it is free. {@link SongBuilder#layBus} says otherwise in as
 * many words: where the ground will not take a note the run carries on a block further. If that is
 * what happens here then the module comes out a column longer than the plan paid for, and a run of
 * wire passes the fifteen a repeater reaches.</p>
 *
 * <p>Counted with the stacked cross skipped. That dust is off the signal path -- it is what the
 * centre block lights, not what carries the wire along -- and counting it reads every run through a
 * stacked module a cell long, which has produced a whole afternoon of phantom faults before.</p>
 */
@Tag("sweep")
class ShedRunLengthTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.SHEDS_BACK_FLANK = true;
	}

	/**
	 * Which decisions moved, rather than which blocks did.
	 *
	 * <p>The shed itself measured clean -- every one of the eleven spent the cells the module would
	 * have spent anyway -- so whatever lengthens the run is downstream of it. Removing a back flank
	 * changes what the <em>next</em> lane finds when it asks about the ground, so a shed here can
	 * turn a decision over there, and the padding map is the record of every decision made.</p>
	 */
	@Test
	void diffsTheDecisionsTheShedChanges() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		SongBuilder.SHEDS_BACK_FLANK = true;
		java.util.Map<String, Integer> with = build(notes).padding();
		SongBuilder.SHEDS_BACK_FLANK = false;
		java.util.Map<String, Integer> without = build(notes).padding();
		java.util.TreeSet<String> keys = new java.util.TreeSet<>(with.keySet());
		keys.addAll(without.keySet());
		for (String key : keys) {
			int a = without.getOrDefault(key, 0);
			int b = with.getOrDefault(key, 0);
			if (a != b) {
				System.out.println("DIFF " + key + ": " + a + " -> " + b
					+ " (" + (b - a > 0 ? "+" : "") + (b - a) + ")");
			}
		}
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 24, 5));
	}

	/**
	 * The sixteen cells themselves, and what each one is standing on.
	 *
	 * <p>Which answers the question the length alone cannot: a pad is glass with dust over it and a
	 * bus is powered stone, so the run says outright how much of itself is music and how much is
	 * wire laid to wait. ekran, reading it in game: a standard bus that dies because the chord after
	 * it was given a block of padding, with nothing stacked on either side to want one.</p>
	 */
	@Test
	void dumpsTheRunThatOverruns() throws Exception {
		SongBuilder.SHEDS_BACK_FLANK = true;
		SongBuilder.PastePlan plan = build(load("deltarune-ch-4-guardian"));
		java.util.Map<String, String> under = new java.util.HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			under.put(parts[1] + " " + parts[2] + " " + parts[3], parts[4]);
		}
		List<String> run = new java.util.ArrayList<>();
		String opened = "?";
		String openedNow = "?";
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String block = parts[4];
			if (block.startsWith("minecraft:redstone_wire[")) {
				continue;
			}
			if (block.startsWith("minecraft:redstone_wire")) {
				String floor = under.getOrDefault(parts[1] + " " + (Integer.parseInt(parts[2]) - 1)
					+ " " + parts[3], "nothing");
				run.add(run.size() + 1 + ": " + parts[1] + " " + parts[2] + " " + parts[3]
					+ " on " + floor.replace("minecraft:", ""));
				if (run.size() == 16) {
					break;
				}
			} else if (block.startsWith("minecraft:repeater")) {
				run.clear();
				opened = openedNow;
				openedNow = parts[1] + " " + parts[2] + " " + parts[3] + " " + block;
			}
		}
		System.out.println("DUMP the run of " + run.size() + " opened at " + openedNow
			+ " (previous repeater " + opened + ")");
		for (String cell : run) {
			System.out.println("DUMP   " + cell);
		}
	}

	@Test
	void measuresTheRunsGuardianEndsUpWith() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (int shed = 1; shed >= 0; shed--) {
			SongBuilder.SHEDS_BACK_FLANK = shed == 1;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 24, 5));
			int dust = 0;
			int longest = 0;
			int over = 0;
			String firstOver = null;
			String opened = "?";
			String openedNow = "?";
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				String block = parts[4];
				if (block.startsWith("minecraft:redstone_wire[")) {
					continue;
				}
				if (block.startsWith("minecraft:redstone_wire")) {
					dust++;
					if (dust == 16) {
						over++;
						if (firstOver == null) {
							firstOver = parts[1] + " " + parts[2] + " " + parts[3]
								+ " (run opened at repeater " + openedNow + ")";
						}
					}
					longest = Math.max(longest, dust);
				} else if (block.startsWith("minecraft:repeater")) {
					dust = 0;
					opened = openedNow;
					openedNow = parts[1] + " " + parts[2] + " " + parts[3];
				}
			}
			System.out.println("RUN shed=" + (shed == 1) + " longestRun=" + longest
				+ " runsPastFifteen=" + over + " sheds="
				+ plan.padding().getOrDefault("planShedBackFlank", 0)
				+ " blocks=" + plan.commands().size() + " spanZ=" + plan.spanZ()
				+ " lastOpened=" + opened);
			System.out.println("RUN shed=" + (shed == 1) + " firstOverAt=" + firstOver);
		}
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		Path file = Path.of("run", "config", "fast-noteblocks", "songs", name + ".json");
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
