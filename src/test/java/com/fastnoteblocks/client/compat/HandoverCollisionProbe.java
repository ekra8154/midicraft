package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one thing standing against {@link SongBuilder#RESERVES_THE_HANDOVER_COLUMN}.
 *
 * <p>{@code NoteMachineReaderTest.readsBackEveryNoteOfItsOwnBuild[3]} does not read back wrong -- it
 * never gets that far. The plan is refused outright with a collision at {@code 0 71 32}, oak planks
 * against stone. This builds the same song and limits with the collision marked instead of thrown,
 * so the shape that laid each block can be read off rather than guessed at.</p>
 */
@Tag("sweep")
class HandoverCollisionProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final SongBuilder.BuildLimits LIMITS = new SongBuilder.BuildLimits(4, 24, 3);

	@Test
	void marksTheCollision() {
		List<SongBuilder.EventNote> notes = sampleSong();
		for (boolean on : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = on;
			SongBuilder.MARK_COLLISIONS = true;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE, LIMITS);
				System.out.println("COLLIDE on=" + on + " collisions=" + plan.collisions().size()
					+ " breaches=" + plan.breaches().size() + " wrong=" + plan.wrongNotes());
				plan.collisions().forEach((at, what) -> System.out.println("    " + at.getX() + " "
					+ at.getY() + " " + at.getZ() + "  " + what));
			} catch (RuntimeException no) {
				System.out.println("COLLIDE on=" + on + " REFUSED: " + no.getMessage());
			} finally {
				SongBuilder.MARK_COLLISIONS = false;
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
	}

	/**
	 * Guardian per configuration, so the arms can be compared over the configs both of them build.
	 *
	 * <p>With the handover column on, twelve configurations that used to refuse now paste -- and a
	 * config that refuses contributes no breaches at all, so a straight total flatters the arm that
	 * builds less. What is worth knowing is what happened to the builds that existed either way.</p>
	 */
	@Test
	void comparesOnlyTheConfigsBothArmsBuild() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		Map<String, int[]> off = new LinkedHashMap<>();
		Map<String, int[]> on = new LinkedHashMap<>();
		for (boolean arm : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = arm;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							int blocks = plan.breaches().stream().mapToInt(Integer::intValue).sum();
							int ones = (int) plan.breaches().stream().filter(b -> b == 1).count();
							(arm ? on : off).put(width + "x" + floors, new int[] {
								plan.breaches().size(), blocks, ones,
								plan.breaches().stream().mapToInt(Integer::intValue).max().orElse(0),
								plan.commands().size()});
						} catch (RuntimeException no) {
							// A refusal is the absence of a build, not a build with no breaches.
						}
					}
				}
			} finally {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
		int[] shared = new int[10];
		int both = 0;
		for (String config : off.keySet()) {
			if (!on.containsKey(config)) {
				continue;
			}
			both++;
			int[] a = off.get(config);
			int[] b = on.get(config);
			for (int field = 0; field < 5; field++) {
				shared[field] += a[field];
				shared[5 + field] += b[field];
			}
		}
		System.out.println("SHARED configs=" + both
			+ " | off breaches=" + shared[0] + " blocks=" + shared[1] + " ofOne=" + shared[2]
			+ " worst=" + shared[3] + " cmds=" + shared[4]
			+ " | on breaches=" + shared[5] + " blocks=" + shared[6] + " ofOne=" + shared[7]
			+ " worst=" + shared[8] + " cmds=" + shared[9]);
		System.out.println("ONLY-ON configs=" + (on.size() - both) + " of " + on.size());
		on.forEach((config, value) -> {
			if (!off.containsKey(config)) {
				System.out.println("    " + config + " breaches=" + value[0]
					+ " blocks=" + value[1] + " worst=" + value[3]);
			}
		});
	}

	private static List<SongBuilder.EventNote> guardian() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs");
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
				songs.resolve("deltarune-ch-4-guardian.json"))) {
			com.fastnoteblocks.client.composer.ComposerProject raw =
				new com.google.gson.Gson().fromJson(reader,
					com.fastnoteblocks.client.composer.ComposerProject.class);
			com.fastnoteblocks.client.composer.ComposerProject song =
				new com.fastnoteblocks.client.composer.ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true));
		}
	}

	/** The same song {@code NoteMachineReaderTest} builds, copied so this probe stands alone. */
	private static List<SongBuilder.EventNote> sampleSong() {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:sand"};
		Random random = new Random(20260729L);
		int time = 0;
		for (int event = 0; event < 220; event++) {
			time += 1 + random.nextInt(9);
			int chord = switch (event % 7) {
				case 0 -> 1;
				case 1 -> 2;
				case 2 -> 3;
				case 3 -> 4;
				case 4 -> 7;
				case 5 -> 12;
				default -> 2;
			};
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}
}
