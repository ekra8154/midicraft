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
 * What size of chord can still be cut, asked one size at a time and read back.
 *
 * <p>The ceilings in this file are quoted as arithmetic -- 22 notes for a plain cut at a descent, 27
 * for a headed one, 29 climbing -- and arithmetic is what the wire check makes, not what the builder
 * lays. ekran asked whether 22 still splits and whether 27 still cuts, and the honest answer is a
 * build of nothing but chords of that size, read through {@link NoteMachineReader}, with the longest
 * run counted off the commands. One size a line.</p>
 */
@Tag("sweep")
class CutCeilingTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * A song of one chord size, spaced so lanes have to turn rather than sit on spatial delay.
	 *
	 * <p>Every instrument is one that hangs on a bus, and a harp is always present, so a refusal here
	 * is about the size and not about the chord being unable to make a head.</p>
	 */
	private static List<SongBuilder.EventNote> song(int size, long seed) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:packed_ice"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 90; event++) {
			time += 3 + random.nextInt(5);
			for (int index = 0; index < size; index++) {
				// Index 0 is always harp, so the head always has a centre available.
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25),
					index == 0 ? "minecraft:air" : instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}

	private static int longestRun(SongBuilder.PastePlan plan) {
		int dust = 0;
		int longest = 0;
		for (String command : plan.commands()) {
			String block = command.split(" ")[4];
			// The crossed state is the stacked module's own cross and is not part of any run.
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
	void saysWhichChordSizesStillCut() throws Exception {
		System.out.println();
		System.out.println("==== one chord size a line, 3 configs each, read back ====");
		System.out.println("   arm  size  cutHeads  lanesOut   unreached  wrong  longestRun"
			+ "  breachBlocks");
		for (int size = 18; size <= 30; size++) {
		 for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			List<SongBuilder.EventNote> notes = song(size, 7L);
			int heads = 0;
			int whole = 0;
			int unreached = 0;
			int wrong = 0;
			int longest = 0;
			int breachBlocks = 0;
			int refused = 0;
			for (int[] config : new int[][] {{20, 3}, {28, 4}, {40, 5}}) {
				try {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, config[0], config[1]));
					// What the walk says it built, counted off the padding census rather than guessed.
					for (Map.Entry<String, Integer> entry : plan.padding().entrySet()) {
						if (entry.getKey().startsWith("planStackedSplit")) {
							heads += entry.getValue();
						}
					}
					// Chords laid whole where a cut was wanted. Counted off the fault the walk writes
					// when a lane hands over outside its wall, which is what laying one whole costs --
					// not off a padding key, because none of them means "this chord was not cut".
					for (String fault : plan.faults()) {
						if (fault.startsWith("a lane turned")) {
							whole++;
						}
					}
					unreached += readAll(placeInWorld(plan)).unreachedNotes();
					wrong += plan.wrongNotes();
					longest = Math.max(longest, longestRun(plan));
					breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
				} catch (RuntimeException refusedHere) {
					refused++;
				}
			}
			System.out.println(String.format(
				"   %3s  %4d  %8d  %8d  %9d  %5d  %10d  %12d%s",
				cuts ? "on" : "off", size, heads, whole, unreached, wrong, longest, breachBlocks,
				refused > 0 ? "   REFUSED " + refused : "")
				+ (unreached > 0 || longest > 15 ? "   <-- DEAD WIRE" : ""));
		 }
		}
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
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
		return NoteMachineReader.read("Ceiling", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
	}
}
