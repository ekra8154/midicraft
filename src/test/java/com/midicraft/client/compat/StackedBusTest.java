package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The four-cell descent, checked by reading the machine back rather than by trusting the sums.
 *
 * <p>A cheaper staircase is a claim about redstone, and the only thing that settles it is following
 * the wire the way the game does. The builder's own {@code verify} cannot: it knows what the
 * builder meant to power, which is the thing in question.</p>
 */
class StackedBusTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Back to the default, which is on.
	 *
	 * <p>This said {@code false}, which is not putting anything back: every test in this class turns
	 * the heads on for itself, so the tidy-up left {@link SongBuilder#STACKED_BUS_HEADS} off for every
	 * class that ran afterwards. It is the leak {@link UltraLaneV2Test#doesNotDisturbTheFirstLayout}
	 * was written to catch -- v1 builds the all-25 song at 40x2 for 88 breach blocks with the heads on
	 * and none at all with them off -- and that guard passes on its own and failed in the suite for
	 * exactly this. Both of the other classes that turn the flag off restore it to {@code true}.</p>
	 */
	@AfterEach
	void putTheHeadsBack() {
		SongBuilder.STACKED_BUS_HEADS = true;
	}

	/** Chords long enough to want a bus, with the instrument mix a stacked head needs. */
	private static List<SongBuilder.EventNote> longChordSong(int low, int high, long seed) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:stone", "minecraft:oak_planks",
			"minecraft:gold_block", "minecraft:sand"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 140; event++) {
			time += 2 + random.nextInt(8);
			int chord = low + random.nextInt(high - low + 1);
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return notes;
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes, int width,
			int floors) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, width, floors));
	}

	/** The shape has to be built, or the round trip below is testing plain buses. */
	@Test
	void buildsLongChordsWithAStackedHead() {
		SongBuilder.STACKED_BUS_HEADS = true;
		int heads = 0;
		for (int floors = 1; floors <= 3; floors++) {
			for (int width = 24; width <= 40; width += 8) {
				heads += build(longChordSong(10, 20, 5L), width, floors).padding()
					.getOrDefault("busHandover", 0);
			}
		}
		System.out.println("STACKEDBUS heads built: " + heads);
		assertTrue(heads > 0, "no long chord was ever given a stacked head");
	}

	/** And the machine has to still be the song. */
	@Test
	void readsBackEveryNoteOfASongWithStackedHeads() {
		SongBuilder.STACKED_BUS_HEADS = true;
		for (int floors = 1; floors <= 3; floors++) {
			for (int width = 24; width <= 40; width += 8) {
				List<SongBuilder.EventNote> notes = longChordSong(10, 20, 5L);
				SongBuilder.PastePlan plan = build(notes, width, floors);
				String where = "f" + floors + " w" + width + ": ";
				NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
				assertEquals(0, reading.unreachedNotes(),
					where + "note blocks the signal never got to");
				assertEquals("", difference(sounds(notes), sounds(reading.project())),
					where + "the machine did not read back as the song it was built from");
			}
		}
	}

	/** No run of wire past what a repeater reaches. */
	@Test
	void leavesNoDeadRun() {
		SongBuilder.STACKED_BUS_HEADS = true;
		for (int floors = 1; floors <= 3; floors++) {
			for (int width = 24; width <= 40; width += 8) {
				SongBuilder.PastePlan plan = build(longChordSong(10, 20, 5L), width, floors);
				int dust = 0;
				int longest = 0;
				for (String command : plan.commands()) {
					String block = command.split(" ")[4];
					if (block.startsWith("minecraft:redstone_wire[")) {
						continue;
					}
					if (block.startsWith("minecraft:redstone_wire")) {
						dust++;
					} else if (block.startsWith("minecraft:repeater")) {
						longest = Math.max(longest, dust);
						dust = 0;
					}
				}
				assertTrue(longest <= 15, "f" + floors + " w" + width + ": a run of " + longest
					+ " blocks of wire is longer than the fifteen a repeater reaches");
			}
		}
	}

	private record Sound(int time, String instrument, int pitch) implements Comparable<Sound> {
		@Override
		public int compareTo(Sound other) {
			int byTime = Integer.compare(time, other.time);
			if (byTime != 0) {
				return byTime;
			}
			int byInstrument = instrument.compareTo(other.instrument);
			return byInstrument != 0 ? byInstrument : Integer.compare(pitch, other.pitch);
		}
	}

	private static SortedMap<Sound, Integer> sounds(List<SongBuilder.EventNote> notes) {
		SortedMap<Sound, Integer> counts = new TreeMap<>();
		int first = notes.get(0).time();
		for (SongBuilder.EventNote note : notes) {
			counts.merge(new Sound(note.time() - first,
				parse(note.instrumentBlock()).instrument().name(), note.pitch()), 1, Integer::sum);
		}
		return counts;
	}

	private static SortedMap<Sound, Integer> sounds(ComposerProject project) {
		SortedMap<Sound, Integer> counts = new TreeMap<>();
		for (ComposerProject.Layer layer : project.layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				counts.merge(new Sound(
					(int) (note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK),
					layer.instrument(),
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
				text.append(String.format("%n  t=%d %s pitch %d: built %d, read %d",
					sound.time(), sound.instrument(), sound.pitch(), want, got));
			}
		}
		if (shown > 12) {
			text.append(String.format("%n  ... and %d more", shown - 12));
		}
		return text.toString();
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
		return NoteMachineReader.read("Split descent", new BlockPos(minX, minY, minZ),
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
