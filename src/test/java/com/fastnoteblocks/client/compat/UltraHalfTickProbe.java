package com.fastnoteblocks.client.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Two folded snakes, one per half of the tick: does it build, does it conduct, how big is it.
 *
 * <p>The first questions only. Whether the two stay near each other is the interesting one and is
 * not asked here -- nothing paces them yet, and measuring a drift nothing is trying to control
 * would only restate the arithmetic. What has to be true before that is worth asking: that two
 * corridors four blocks apart leave each other alone, that neither snake goes dead, and that the
 * pair is small enough to be worth the trouble against one straight lane of the same song.</p>
 */
@Tag("sweep")
class UltraHalfTickProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void buildsGuardianAsTwoSnakes() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian", SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE);
		System.out.println();
		System.out.println("==== Ultra half-tick lane, Guardian ====");
		for (int[] size : new int[][] {{44, 3}, {32, 4}, {24, 3}}) {
			SongBuilder.PastePlan plan;
			try {
				plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
					SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE,
					new SongBuilder.BuildLimits(4, size[0], size[1]));
			} catch (RuntimeException refused) {
				System.out.println("  " + size[0] + "w x " + size[1] + "f  REFUSED: "
					+ refused.getMessage());
				continue;
			}
			Map<BlockPos, BlockState> world = placeInWorld(plan);
			NoteMachineReader.Reading reading = read(world);
			System.out.println("  " + size[0] + "w x " + size[1] + "f  blocks "
				+ plan.commands().size() + ", spanX " + plan.spanX() + ", spanZ " + plan.spanZ()
				+ ", height " + plan.height());
			System.out.println("      noteBlocks " + reading.noteBlocks()
				+ ", unreached " + reading.unreachedNotes()
				+ ", versions " + reading.versions() + " (2 means the snakes share nothing)"
				+ ", wrongNotes " + plan.wrongNotes()
				+ ", breaches " + plan.breaches().size());
			// The size that decides whether folding was worth it: a straight pair of the same song
			// is 7,220 long and a note block carries 48, so anything that fits inside earshot at
			// all has to come from folding.
			System.out.println("      footprint " + plan.spanX() + " x " + plan.spanZ() + " x "
				+ plan.height() + ", longest diagonal about "
				+ Math.round(Math.sqrt((double)plan.spanX() * plan.spanX()
					+ (double)plan.spanZ() * plan.spanZ())) + " blocks");
		}
	}

	/** One event's pulse: the game tick it sounds on and the block it sounds from. */
	private record Pulse(int gameTick, BlockPos at) {
	}

	/**
	 * How far apart the two snakes are while they play, in blocks a listener would have to cross.
	 *
	 * <p>The straight version of this question was answered by column: two lanes running the same
	 * way, so a difference in columns was a difference in blocks. Folded it is not -- a snake that
	 * is a whole corridor further along its path may be three blocks away in the world, because the
	 * corridor doubled back. So this asks the world directly: where is each pulse standing, and how
	 * far is that, through the air.</p>
	 *
	 * <p>48 blocks is the whole question. A note block is audible for exactly that, so a listener
	 * standing with one pulse hears the other only while the two are inside it.</p>
	 */
	@Test
	void measuresHowFarApartTheTwoSnakesPlay() throws Exception {
		// A song only fills both corridors if its notes actually land on both halves of the tick.
		// Guardian at the speed it is saved at does not -- every gap is a whole repeater tick, so
		// every event is on an even game tick and the second snake is empty. Measuring drift on it
		// would be measuring one snake against nothing. So: the live two-lane composition, and
        // Guardian at double speed, which is the stress case the earlier numbers were really taken on.
		for (Object[] subject : new Object[][] {
				{"a-dark-zone-2-lanes-maybe", 1}, {"deltarune-ch-4-guardian", 2}}) {
			measureOneSong((String)subject[0], (Integer)subject[1]);
		}
	}

	private void measureOneSong(String name, int speedFactor) throws Exception {
		com.fastnoteblocks.client.composer.ComposerProject project = BreachView.project(name);
		if (speedFactor != 1) {
			project = project.withSpeedQuarters(Math.max(1, project.speedQuarters()) * speedFactor);
		}
		List<SongBuilder.EventNote> guardian = SongBuilder.notesFor(
			SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE,
			project.toSequenceTracks(java.util.Set.of(), true), project, true);
		long even = guardian.stream().filter(note -> note.time() % 2 == 0).count();
		System.out.println();
		System.out.println("==== Ultra half-tick lane, " + name
			+ (speedFactor == 1 ? "" : " at " + speedFactor + "x")
			+ ": " + guardian.size() + " notes, " + even + " even / "
			+ (guardian.size() - even) + " odd ====");
		// Split into the two things that separate the snakes, because they want opposite fixes.
		// Across is the corridors sitting side by side, which no pacing can close and only sharing
		// a corridor would; along is the two creeping apart down the fold, which is drift and is
		// exactly what padding fixed on the straight lanes.
		System.out.println(String.format("  %-12s %-22s %-10s %-11s %-9s %-9s %s",
			"size", "footprint", "worst", "mean", "across", "along", "out of earshot"));
		for (int[] size : new int[][] {
				{44, 2}, {44, 3}, {44, 4}, {40, 3}, {36, 3}, {32, 3}, {32, 4}, {32, 5}, {28, 4}}) {
			SongBuilder.PastePlan plan;
			try {
				plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
					SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE,
					new SongBuilder.BuildLimits(4, size[0], size[1]));
			} catch (RuntimeException refused) {
				System.out.println(String.format("  %-12s REFUSED", size[0] + "w x " + size[1] + "f"));
				continue;
			}
			List<List<String>> corridors = splitByCorridor(plan);
			List<Pulse> first = pulses(corridors.get(0), guardian, 0);
			List<Pulse> second = pulses(corridors.get(1), guardian, 1);
			if (first.isEmpty() || second.isEmpty()) {
				System.out.println(String.format("  %-12s could not separate the two corridors",
					size[0] + "w x " + size[1] + "f"));
				continue;
			}

			double worst = 0.0;
			double total = 0.0;
			double acrossTotal = 0.0;
			double alongTotal = 0.0;
			long beyond = 0;
			long samples = 0;
			int firstAt = 0;
			int secondAt = 0;
			List<Integer> ticks = new java.util.ArrayList<>();
			first.forEach(pulse -> ticks.add(pulse.gameTick()));
			second.forEach(pulse -> ticks.add(pulse.gameTick()));
			ticks.sort(java.util.Comparator.naturalOrder());
			for (int tick : ticks) {
				while (firstAt + 1 < first.size() && first.get(firstAt + 1).gameTick() <= tick) {
					firstAt++;
				}
				while (secondAt + 1 < second.size() && second.get(secondAt + 1).gameTick() <= tick) {
					secondAt++;
				}
				BlockPos here = first.get(firstAt).at();
				BlockPos there = second.get(secondAt).at();
				double apart = Math.sqrt(here.distSqr(there));
				worst = Math.max(worst, apart);
				total += apart;
				acrossTotal += Math.abs(here.getX() - there.getX());
				alongTotal += Math.hypot(here.getZ() - there.getZ(), here.getY() - there.getY());
				if (apart > 48.0) {
					beyond++;
				}
				samples++;
			}
			System.out.println(String.format("  %-12s %-22s %-10s %-11s %-9s %-9s %.1f%%",
				size[0] + "w x " + size[1] + "f",
				plan.spanX() + " x " + plan.spanZ() + " x " + plan.height(),
				String.format("%.0f blk", worst),
				String.format("%.0f blk", total / samples),
				String.format("%.0f blk", acrossTotal / samples),
				String.format("%.0f blk", alongTotal / samples),
				100.0 * beyond / Math.max(1, samples)));
		}
	}

	/**
	 * The commands split into the two corridors, by the empty ground between them.
	 *
	 * <p>Found rather than assumed: the corridors are laid at a spacing the builder works out from
	 * the song, and a probe that recomputed it would be asserting its own arithmetic. The widest
	 * run of columns holding no note block at all is the gap, wherever it turned out to be.</p>
	 */
	private static List<List<String>> splitByCorridor(SongBuilder.PastePlan plan) {
		java.util.TreeSet<Integer> columns = new java.util.TreeSet<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			if (parts[4].startsWith("minecraft:note_block")) {
				columns.add(Integer.parseInt(parts[1]));
			}
		}
		List<Integer> ordered = new java.util.ArrayList<>(columns);
		int splitAfter = -1;
		int widest = 0;
		for (int index = 1; index < ordered.size(); index++) {
			int gap = ordered.get(index) - ordered.get(index - 1);
			if (gap > widest) {
				widest = gap;
				splitAfter = ordered.get(index - 1);
			}
		}
		List<String> near = new java.util.ArrayList<>();
		List<String> far = new java.util.ArrayList<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			if (!parts[4].startsWith("minecraft:note_block")) {
				continue;
			}
			(Integer.parseInt(parts[1]) <= splitAfter ? near : far).add(command);
		}
		return List.of(near, far);
	}

	/**
	 * Each event's block, taken in the order the walk laid them.
	 *
	 * <p>A folded snake doubles back, so sorting by column would scramble the order the music is
	 * in. The command list does not: blocks are emitted as the walk reaches them, so the note blocks
	 * of one corridor arrive event by event, and each event takes as many as its chord has notes.</p>
	 */
	private static List<Pulse> pulses(List<String> noteBlocks, List<SongBuilder.EventNote> song,
			int parity) {
		List<int[]> chords = new java.util.ArrayList<>();
		for (int index = 0; index < song.size();) {
			int time = song.get(index).time();
			int size = 0;
			while (index < song.size() && song.get(index).time() == time) {
				size++;
				index++;
			}
			if (Math.floorMod(time, 2) == parity) {
				chords.add(new int[] {time, size});
			}
		}
		List<Pulse> pulses = new java.util.ArrayList<>();
		int at = 0;
		for (int[] chord : chords) {
			if (at >= noteBlocks.size()) {
				break;
			}
			String[] parts = noteBlocks.get(at).split(" ");
			pulses.add(new Pulse(chord[0], new BlockPos(Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))));
			at += chord[1];
		}
		return pulses;
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

	private static NoteMachineReader.Reading read(Map<BlockPos, BlockState> world) {
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
		return NoteMachineReader.read("Ultra half-tick", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
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
