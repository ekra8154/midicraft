package com.midicraft.client.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Does a pad run at bus height actually carry the signal into the climb above it?
 *
 * <p>The question three sessions' worth of arithmetic never asked. {@code wrongNotes()} counts
 * faults beginning {@code "the note"}, which are notes sounded a second time; a staircase that
 * conducts nothing produces the opposite fault and is counted nowhere in a {@code PastePlan}. So a
 * build can report no wrong notes, no dead wire and no refusals while being silent from the first
 * raised pad onward, and every breach measurement taken over it is measuring a broken machine.</p>
 *
 * <p>{@link NoteMachineReader#read} is the only thing in the repo that answers it: build the plan
 * into a block map, read it back, and ask how many note blocks the signal never reached.</p>
 */
@Tag("sweep")
class RaisedPadReadBackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * A song of chords small enough that lanes close on pads rather than on cuts.
	 *
	 * <p>A raised pad only happens where a lane runs out of music before it runs out of lane, so the
	 * chords have to be small enough to leave columns over. Guardian is all big chords and closes
	 * mostly by cutting -- the wrong song to ask this of.</p>
	 */
	private static List<SongBuilder.EventNote> paddingSong(long seed) {
		List<SongBuilder.EventNote> notes = new java.util.ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:packed_ice"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 260; event++) {
			time += 2 + random.nextInt(10);
			int chord = 1 + random.nextInt(5);
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}

	@Test
	void readsBackASongWhoseLanesEndOnRaisedPads() {
		for (boolean raised : new boolean[] {false, true}) {
			SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = raised;
			try {
				for (int floors = 2; floors <= 4; floors++) {
					for (int width = 16; width <= 32; width += 8) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), paddingSong(11L),
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						int pads = plan.padding().getOrDefault("padClosingRaised", 0)
							+ plan.padding().getOrDefault("padPinnedRaised", 0);
						NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
						System.out.println("READBACK raised=" + raised + " f" + floors + " w" + width
							+ " raisedPads=" + pads
							+ " unreached=" + reading.unreachedNotes()
							+ " wrong=" + plan.wrongNotes()
							+ " blocks=" + plan.commands().size());
					}
				}
			} finally {
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
			}
		}
	}

	/**
	 * And the song every breach number in this session was measured on.
	 *
	 * <p>Guardian is nearly all big chords, so its lanes end on buses and the raise costs it nothing
	 * -- which is why the fault above never showed in its breach counts. That is not the same as its
	 * machine being sound, and every number quoted for {@code PLANS_THE_RAISED_PAD} is worthless if
	 * it is not.</p>
	 */
	@Test
	void readsBackGuardianWithTheBudgetBothWays() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "midicraft", "songs");
		List<SongBuilder.EventNote> notes;
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
				BreachView.songFile("deltarune-ch-4-guardian"))) {
			com.midicraft.client.composer.ComposerProject raw =
				new com.google.gson.Gson().fromJson(reader,
					com.midicraft.client.composer.ComposerProject.class);
			com.midicraft.client.composer.ComposerProject song =
				new com.midicraft.client.composer.ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			notes = SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true));
		}
		for (boolean plans : new boolean[] {false, true}) {
			SongBuilder.PLANS_THE_RAISED_PAD = plans;
			try {
				for (int[] config : new int[][] {{44, 3}, {24, 5}, {16, 3}}) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, config[0], config[1]));
					NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
					System.out.println("GUARDIAN plans=" + plans + " " + config[0] + "w x "
						+ config[1] + "f raisedPads="
						+ (plan.padding().getOrDefault("padClosingRaised", 0)
							+ plan.padding().getOrDefault("padPinnedRaised", 0))
						+ " unreached=" + reading.unreachedNotes()
						+ " breaches=" + plan.breaches().size()
						+ " wrong=" + plan.wrongNotes());
				}
			} finally {
				SongBuilder.PLANS_THE_RAISED_PAD = true;
			}
		}
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
		return NoteMachineReader.read("Raised pad", new BlockPos(minX, minY, minZ),
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
