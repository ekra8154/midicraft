package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Giving a lane back the cut it was refused, where it cannot turn either.
 *
 * <p>The plan books a no-split bit when it has found a way to close a lane on a pad. Where the walk
 * then finds it cannot afford that pad, the lane has been told it may not cut and cannot turn, so
 * it lays its chord whole and comes to rest outside. In-game reading found one at Guardian 16 wide
 * over four floors: a chord of 24 with the cut sitting right there, shed and ready, thrown away for
 * a pad of one cell.</p>
 *
 * <p>Read back over the real songs and over a song of nothing but chords too big to cut plain,
 * because a change that moves which half of a chord lands on which side of a staircase is exactly
 * the change that silences a machine while every breach count improves.</p>
 */
@Tag("sweep")
class VetoTakenBackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final List<String> PICKED = List.of(
		"deltarune-ch-4-guardian", "illit-do-the-dance", "big-shot", "hopes-and-dreams",
		"golden-brown-2xspeed", "adventure-of-a-lifetime", "michael-jackson-thriller",
		"aria-math-c418");

	private static List<SongBuilder.EventNote> hugeChordSong(int low, int high, long seed) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:packed_ice"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 120; event++) {
			time += 2 + random.nextInt(6);
			int chord = low + random.nextInt(high - low + 1);
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}

	private static int longestRun(SongBuilder.PastePlan plan) {
		int dust = 0;
		int longest = 0;
		for (String command : plan.commands()) {
			String block = command.split(" ")[4];
			if (block.startsWith("minecraft:redstone_wire[")) {
				continue;
			}
			if (block.startsWith("minecraft:redstone_wire")) {
				longest = Math.max(longest, ++dust);
			} else if (block.startsWith("minecraft:repeater")) {
				dust = 0;
			}
		}
		return longest;
	}

	@Test
	void weighsTakingTheVetoBack() throws Exception {
		List<List<SongBuilder.EventNote>> songs = new ArrayList<>();
		for (String name : PICKED) {
			songs.add(BreachView.song(name));
		}
		System.out.println();
		System.out.println("==== a vetoed lane takes its cut back when it cannot turn ====");
		for (boolean back : new boolean[] {false, true}) {
			SongBuilder.CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN = back;
			int lanes = 0;
			int blocks = 0;
			int worst = 0;
			int wrong = 0;
			int taken = 0;
			long length = 0;
			long volume = 0;
			for (List<SongBuilder.EventNote> notes : songs) {
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						lanes += plan.breaches().size();
						blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						worst = Math.max(worst, plan.worstBreach());
						wrong += plan.wrongNotes();
						taken += plan.padding().getOrDefault("planVetoTakenBack", 0);
						length += plan.width();
						volume += plan.commands().size();
					}
				}
			}
			System.out.println("   takesItBack=" + (back ? "on " : "off") + "   lanes=" + lanes
				+ " blocks=" + blocks + " worst=" + worst + " wrong=" + wrong + " tookBack=" + taken
				+ " length=" + length + " volume=" + volume);
		}

		System.out.println("   -- read back --");
		for (boolean back : new boolean[] {false, true}) {
			SongBuilder.CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN = back;
			int unreached = 0;
			int unreadable = 0;
			int wrong = 0;
			int longest = 0;
			List<SongBuilder.PastePlan> plans = new ArrayList<>();
			for (String name : PICKED) {
				plans.add(SongBuilder.createPastePlan(new BlockPos(0, 64, 0), BreachView.song(name),
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 40, 5)));
			}
			List<SongBuilder.EventNote> huge = hugeChordSong(23, 27, 11L);
			for (int floors = 2; floors <= 4; floors++) {
				for (int width = 20; width <= 36; width += 8) {
					plans.add(SongBuilder.createPastePlan(new BlockPos(0, 64, 0), huge,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors)));
				}
			}
			for (SongBuilder.PastePlan plan : plans) {
				wrong += plan.wrongNotes();
				longest = Math.max(longest, longestRun(plan));
				try {
					unreached += readAll(placeInWorld(plan)).unreachedNotes();
				} catch (RuntimeException unreadableHere) {
					unreadable++;
				}
			}
			System.out.println("   takesItBack=" + (back ? "on " : "off") + "   unreached=" + unreached
				+ " unreadable=" + unreadable + " wrong=" + wrong + " longestRun=" + longest);
		}
		SongBuilder.CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN = true;
	}

	/** Guardian alone, every size, both arms -- the fast loop. */
	@Test
	void sweepsGuardian() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== Guardian sweep ====");
		for (boolean back : new boolean[] {false, true}) {
			SongBuilder.CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN = back;
			int lanes = 0;
			int blocks = 0;
			int worst = 0;
			int wrong = 0;
			int taken = 0;
			int dirty = 0;
			long length = 0;
			StringBuilder rows = new StringBuilder();
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 16; width <= 48; width += 4) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					int sum = plan.breaches().stream().mapToInt(Integer::intValue).sum();
					lanes += plan.breaches().size();
					blocks += sum;
					worst = Math.max(worst, plan.worstBreach());
					wrong += plan.wrongNotes();
					taken += plan.padding().getOrDefault("planVetoTakenBack", 0);
					length += plan.width();
					if (sum > 0) {
						dirty++;
						rows.append("      ").append(width).append("w x ").append(floors)
							.append("f  ").append(sum).append(" blocks in ")
							.append(plan.breaches().size()).append(" lanes  ")
							.append(plan.breaches()).append(System.lineSeparator());
					}
				}
			}
			System.out.println("   takesItBack=" + (back ? "on " : "off") + "   lanes=" + lanes
				+ " blocks=" + blocks + " worst=" + worst + " wrong=" + wrong + " tookBack=" + taken
				+ " dirtyConfigs=" + dirty + " length=" + length);
			System.out.print(rows);
		}
		SongBuilder.PastePlan mine = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 40, 5));
		System.out.println("   40w x 5f (live)   breaches=" + mine.breaches());
		SongBuilder.CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN = true;
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3])), BreachView.parse(word[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(Map<BlockPos, BlockState> world) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read("Veto", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
	}
}
