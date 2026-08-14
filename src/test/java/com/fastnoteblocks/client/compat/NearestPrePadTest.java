package com.fastnoteblocks.client.compat;

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
 * Letting the pad in front take the nearest fit when no pad lands exactly.
 *
 * <p>{@link SongBuilder#prePad} hands back -1 where no pad size lands the chord's end on the column
 * the handover wants, and the whole pad is then given up -- 183 of 837 wanted pads over the library.
 * Taking the largest pad that still falls short of the target instead gets the chord as near as it
 * can, which is the difference between a run that reaches its staircase and one that dies on it.</p>
 *
 * <p>Read back over the real songs <b>and</b> over a song of nothing but chords too big to cut as a
 * plain bus, because that is the one that has caught two silent machines today and the real songs
 * caught neither.</p>
 */
@Tag("sweep")
class NearestPrePadTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final List<String> PICKED = List.of(
		"deltarune-ch-4-guardian", "illit-do-the-dance", "big-shot", "hopes-and-dreams",
		"golden-brown-2xspeed", "adventure-of-a-lifetime", "michael-jackson-thriller",
		"aria-math-c418");

	/** {@link BigSplitTest}'s song: the one that finds dead wire when the real library does not. */
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
	void weighsTakingTheNearestFit() throws Exception {
		List<List<SongBuilder.EventNote>> songs = new ArrayList<>();
		for (String name : PICKED) {
			songs.add(BreachView.song(name));
		}
		System.out.println();
		System.out.println("==== the pad in front takes the nearest fit ====");
		for (int arm = 0; arm < 4; arm++) {
			SongBuilder.PREPAD_TAKES_THE_NEAREST = (arm & 1) != 0;
			SongBuilder.PREPAD_LAYS_WHAT_IT_CAN = (arm & 2) != 0;
			int lanes = 0;
			int blocks = 0;
			int worst = 0;
			int wrong = 0;
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
						length += plan.width();
						volume += plan.commands().size();
					}
				}
			}
			System.out.println("   nearest=" + ((arm & 1) != 0 ? "on " : "off")
				+ " laysWhatItCan=" + ((arm & 2) != 0 ? "on " : "off") + "   lanes=" + lanes
				+ " blocks=" + blocks + " worst=" + worst + " wrong=" + wrong + " length=" + length
				+ " volume=" + volume);
		}

		// Both flags, because the big-chord song reads a run of sixteen on either arm of the nearest
		// fit, and a fault that predates the change wants finding before the change is judged on it.
		// CUTS_A_CHORD_THAT_FITS is the thing that went on today, so it is the thing to rule out.
		System.out.println("   -- read back: real songs at 40w x 5f, then the big-chord song --");
		for (boolean fits : new boolean[] {false, true}) {
		 for (boolean nearest : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = fits;
			SongBuilder.PREPAD_TAKES_THE_NEAREST = nearest;
			int unreached = 0;
			int wrong = 0;
			int longest = 0;
			// Counted rather than thrown. A build the reader cannot parse at all is a worse result
			// than one it reads as silent, not a reason to abandon the comparison -- and one arm here
			// does exactly that, so letting it escape would hide which arm.
			int unreadable = 0;
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
			System.out.println("   cutsWhatFits=" + (fits ? "on " : "off")
				+ " nearest=" + (nearest ? "on " : "off") + "   unreached=" + unreached
				+ " unreadable=" + unreadable + " wrong=" + wrong + " longestRun=" + longest);
		 }
		}
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
		SongBuilder.PREPAD_TAKES_THE_NEAREST = false;
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
		return NoteMachineReader.read("Nearest", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
	}
}
