package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The half-tick lane, measured on the song it exists for.
 *
 * <p>Two questions, and only the second one is new. Does each lane on its own carry its signal to
 * the end and sound its half of the song at the right moments -- and do the two lanes, sitting
 * closer together than any two lanes in this file ever have, leave each other alone?</p>
 *
 * <p>Each lane is its own machine with its own way in, so they are read back separately and put
 * together afterwards on one clock: the right lane's repeater tick {@code r} is game tick {@code
 * 2r}, the left lane's is {@code 2r + 1}. That merged performance is what gets diffed against the
 * song, which makes this a test of the parity split and the delays under it, not just of two lanes
 * that happen to build.</p>
 *
 * <p>The one thing anchored rather than measured is where each lane's first note falls, since that
 * is fixed by wiring this cannot see -- the contract being that the left lane starts one game tick
 * after the right. Everything downstream of the first note is the plan's own arithmetic.</p>
 */
@Tag("sweep")
class HalfTickLaneProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void buildsGuardianOnTwoLanes() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		int even = (int)guardian.stream().filter(note -> note.time() % 2 == 0).count();
		System.out.println();
		System.out.println("==== Half-tick lane, Guardian ====");
		System.out.println(guardian.size() + " notes, " + even + " on even game ticks, "
			+ (guardian.size() - even) + " on odd");
		System.out.println("single straight lane for comparison: spanX "
			+ straightSpanX(guardian));

		int restore = SongBuilder.HALF_TICK_LANE_GAP;
		try {
			for (int gap : new int[] {3, 4, 5}) {
				SongBuilder.HALF_TICK_LANE_GAP = gap;
				System.out.println();
				System.out.println("---- lane centres " + gap + " apart ----");
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
						SongBuilder.PasteMode.HALF_TICK_LANE,
						new SongBuilder.BuildLimits(4, 44, 3));
				} catch (IllegalArgumentException refused) {
					// finish() refuses a plan with faults for every mode but the ultra lane, so a
					// layout fault arrives as this rather than as a number. It is still the answer.
					System.out.println("REFUSED: " + refused.getMessage());
					continue;
				}
				System.out.println("blocks " + plan.commands().size() + ", spanX " + plan.spanX()
					+ ", spanZ " + plan.spanZ() + ", height " + plan.height()
					+ ", wrongNotes " + plan.wrongNotes());
				// How far the paste front ever drops back down the build, which is what decides
				// whether the blocks land at all: anything behind the player's loaded chunks is a
				// command sent into ground that is not there.
				int furthestBack = 0;
				int previousX = Integer.MIN_VALUE;
				for (String command : plan.commands()) {
					int x = Integer.parseInt(command.split(" ")[1]);
					if (previousX != Integer.MIN_VALUE) {
						furthestBack = Math.max(furthestBack, previousX - x);
					}
					previousX = x;
				}
				System.out.println("  paste front steps back at most " + furthestBack
					+ " blocks (build is " + plan.spanX() + " long)");
				report(plan, guardian);
			}
		} finally {
			SongBuilder.HALF_TICK_LANE_GAP = restore;
		}
	}

	/** Reads both lanes back and says whether the two together are the song. */
	private static void report(SongBuilder.PastePlan plan, List<SongBuilder.EventNote> song) {
		Map<BlockPos, BlockState> world = placeInWorld(plan);
		int minZ = world.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
		int maxZ = world.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
		// A lane is exactly three columns wide, so the two are separable by z whatever the gap.
		NoteMachineReader.Reading left = read(world, minZ, minZ + 2);
		NoteMachineReader.Reading right = read(world, maxZ - 2, maxZ);
		System.out.println("  right lane (even): noteBlocks " + right.noteBlocks()
			+ ", unreached " + right.unreachedNotes() + ", versions " + right.versions()
			+ ", spanX " + spanX(world, maxZ - 2, maxZ));
		System.out.println("  left  lane (odd):  noteBlocks " + left.noteBlocks()
			+ ", unreached " + left.unreachedNotes() + ", versions " + left.versions()
			+ ", spanX " + spanX(world, minZ, minZ + 2));

		// Both lanes at once, which is the only way to ask whether they leave each other alone.
		// Two machines that share nothing read as two versions; if either one's signal got into the
		// other's note blocks, one way in would subsume the other and this would come back as one.
		NoteMachineReader.Reading together = read(world, minZ, maxZ);
		System.out.println("  both lanes in one region: versions " + together.versions()
			+ " (2 means they share nothing), noteBlocks " + together.noteBlocks()
			+ ", unreached " + together.unreachedNotes());
		for (String warning : right.warnings()) {
			System.out.println("    right warning: " + warning);
		}
		for (String warning : left.warnings()) {
			System.out.println("    left warning: " + warning);
		}

		int firstEven = song.stream().filter(note -> note.time() % 2 == 0)
			.mapToInt(SongBuilder.EventNote::time).min().orElse(0);
		int firstOdd = song.stream().filter(note -> note.time() % 2 == 1)
			.mapToInt(SongBuilder.EventNote::time).min().orElse(1);
		SortedMap<Sound, Integer> played = new TreeMap<>(recovered(right, firstEven));
		recovered(left, firstOdd).forEach((sound, count) -> played.merge(sound, count, Integer::sum));
		String difference = difference(written(song), played);
		System.out.println("  PERFORMANCE " + (difference.isEmpty()
			? "matches the song exactly, on both lanes together" : "differs:" + difference));
	}

	/** One sounding: at what game tick, on what instrument, at what pitch. */
	private record Sound(int time, String instrument, int pitch) implements Comparable<Sound> {
		@Override
		public int compareTo(Sound other) {
			return java.util.Comparator.comparingInt(Sound::time)
				.thenComparing(Sound::instrument).thenComparingInt(Sound::pitch)
				.compare(this, other);
		}
	}

	/** The song as game ticks, which is what the times already are once read at double speed. */
	private static SortedMap<Sound, Integer> written(List<SongBuilder.EventNote> notes) {
		SortedMap<Sound, Integer> counts = new TreeMap<>();
		for (SongBuilder.EventNote note : notes) {
			counts.merge(new Sound(note.time(),
				parse(note.instrumentBlock()).instrument().name(), note.pitch()), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * One lane's performance put back on the song's clock.
	 *
	 * <p>The reader counts from the first note it sees fire, so that note is the anchor: the lane's
	 * repeater tick {@code r} is game tick {@code first + 2r}, which is even on the right lane and
	 * odd on the left by construction.</p>
	 */
	private static Map<Sound, Integer> recovered(NoteMachineReader.Reading reading, int first) {
		Map<Sound, Integer> counts = new TreeMap<>();
		for (ComposerProject.Layer layer : reading.project().layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				int lane = (int)(note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK);
				counts.merge(new Sound(first + 2 * lane, layer.instrument(),
					note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE), 1, Integer::sum);
			}
		}
		return counts;
	}

	private static String difference(Map<Sound, Integer> expected, Map<Sound, Integer> actual) {
		TreeSet<Sound> all = new TreeSet<>(expected.keySet());
		all.addAll(actual.keySet());
		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (Sound sound : all) {
			int want = expected.getOrDefault(sound, 0);
			int got = actual.getOrDefault(sound, 0);
			if (want == got) {
				continue;
			}
			if (shown++ < 12) {
				text.append(String.format("%n    gtick %d %s pitch %d: written %d, played %d",
					sound.time(), sound.instrument(), sound.pitch(), want, got));
			}
		}
		if (shown > 12) {
			text.append(String.format("%n    ... and %d more", shown - 12));
		}
		return text.toString();
	}

	private static int spanX(Map<BlockPos, BlockState> world, int fromZ, int toZ) {
		java.util.IntSummaryStatistics along = world.keySet().stream()
			.filter(at -> at.getZ() >= fromZ && at.getZ() <= toZ)
			.mapToInt(BlockPos::getX).summaryStatistics();
		return along.getMax() - along.getMin() + 1;
	}

	private static int straightSpanX(List<SongBuilder.EventNote> song) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.LANE, new SongBuilder.BuildLimits(4, 44, 3)).spanX();
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	/** One lane read on its own, the rest of the world left as air so nothing leaks in. */
	private static NoteMachineReader.Reading read(Map<BlockPos, BlockState> world,
			int fromZ, int toZ) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			if (at.getZ() < fromZ || at.getZ() > toZ) {
				continue;
			}
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
		}
		return NoteMachineReader.read("Half-tick lane", new BlockPos(minX, minY, fromZ),
			new BlockPos(maxX, maxY, toZ),
			position -> position.getZ() >= fromZ && position.getZ() <= toZ
				? world.getOrDefault(position, Blocks.AIR.defaultBlockState())
				: Blocks.AIR.defaultBlockState());
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}
}
