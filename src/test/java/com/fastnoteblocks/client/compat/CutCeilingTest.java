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
 * lays. So: a build of nothing but chords of that size, read through {@link NoteMachineReader}, with
 * the longest run counted off the commands. One size a line.</p>
 *
 * <p><b>Climbs and descents are counted apart, on ekran's point, and it is the whole value of the
 * table.</b> They are different ceilings -- {@code turnCost} gives a climb {@code splitCells} 3 and a
 * descent 4, so {@code runCells = 1 + tail / 2 + splitCells <= 15} reaches 29 notes climbing and 27
 * descending -- and a column that sums them says nothing about either. Measured, descents cut 43
 * chords of 27, five of 28 and <b>none</b> of 29; climbs cut 45 of 28, seven of 29 and none of 30.
 * The five descents at 28 are the shed form, which drops the transition cell and so makes 15 of a
 * sum that is otherwise 16.</p>
 *
 * <p><b>Flat turns are not in here at all</b>, and could not be: {@code headed} is only asked where
 * there is a staircase. A flat turn is walked rather than crossed -- the chord takes the corner and
 * carries on through the ordinary chord machinery, with no near half and far half to keep in step --
 * so it has no cut ceiling to measure and carries chords the other two cannot.</p>
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
		System.out.println("   arm  size  climbCuts  descentCuts   unreached  wrong  longestRun"
			+ "  breachBlocks");
		for (int size = 18; size <= 30; size++) {
		 for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			List<SongBuilder.EventNote> notes = song(size, 7L);
			int climbs = 0;
			int descents = 0;
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
					// Climbs and descents apart, because they are different ceilings and mixing them
					// makes every number above 27 unreadable. turnCost gives a climb splitCells 3 and a
					// descent 4, so runCells = 1 + tail/2 + splitCells <= 15 puts the headed cut at 29
					// notes climbing and 27 descending. A flat turn is not in here at all: `headed` is
					// only asked where there is a staircase, and a flat turn is walked rather than
					// crossed -- the chord carries on round the corner and is never cut in two.
					//
					// Only the two keys that name the kind. The others -- HeadOnly, Clashed, Nudged,
					// ShortHead -- are logged beside them and summing everything counts a cut twice.
					climbs += plan.padding().getOrDefault("planStackedSplitClimb", 0);
					descents += plan.padding().getOrDefault("planStackedSplitDescent", 0);
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
				"   %3s  %4d  %9d  %11d  %9d  %5d  %10d  %12d%s",
				cuts ? "on" : "off", size, climbs, descents, unreached, wrong, longest, breachBlocks,
				refused > 0 ? "   REFUSED " + refused : "")
				+ (unreached > 0 || longest > 15 ? "   <-- DEAD WIRE" : ""));
		 }
		}
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
	}

	/**
	 * Why more cuts makes more breaches, read off the lane rather than argued about.
	 *
	 * <p>Shedding the flank buys a cut that was one cell over, so a chord of 28 can cross a descent
	 * that it could not before -- and on a song of nothing but 28s that takes breaches from 30 blocks
	 * to 116. A capability that costs is worth understanding before it is kept, so this prints the
	 * lanes that end up outside on both arms with the walk's own decisions above them.</p>
	 */
	@Test
	void showsWhatTheExtraCutsCost() throws Exception {
		List<SongBuilder.EventNote> notes = song(28, 7L);
		for (boolean shed : new boolean[] {false, true}) {
			SongBuilder.SHED_BUYS_THE_LAST_CELL = shed;
			SongBuilder.SHED_BOUGHT_THE_CELL = 0;
			BreachView.Traced traced = BreachView.build(notes, 20, 3, 4);
			List<BreachView.Overrun> out = BreachView.overruns(traced.plan());
			System.out.println();
			System.out.println("######## shed=" + shed + "  breaches=" + traced.plan().breaches()
				+ "  boughtTheCell=" + SongBuilder.SHED_BOUGHT_THE_CELL
				+ "  lanesOutside=" + out.size());
			// Only the turn lines, and only the worst lane. The blocks are the same shape on both arms
			// and it is the decisions that differ.
			if (!out.isEmpty()) {
				BreachView.Overrun worst = out.get(0);
				System.out.println("   worst " + worst);
				BreachView.laneTrace(traced.trace(), worst.z(), 1).stream()
					.filter(line -> line.startsWith("TURN "))
					.limit(8)
					.forEach(line -> System.out.println("   " + line));
			}
		}
		SongBuilder.SHED_BUYS_THE_LAST_CELL = false;
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
