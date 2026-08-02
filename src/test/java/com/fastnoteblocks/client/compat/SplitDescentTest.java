package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
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
class SplitDescentTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restoreTheCheapDescent() {
		SongBuilder.CHEAP_SPLIT_DESCENT = true;
	}

	/** Chords too big to carry across a turn whole, so the only way over one is a cut. */
	private static List<SongBuilder.EventNote> bigChordSong(int low, int high, long seed) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:stone", "minecraft:oak_planks",
			"minecraft:gold_block", "minecraft:sand"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 160; event++) {
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

	/**
	 * The path has to fire, or everything below tests the old descent.
	 *
	 * <p>Nineteen to twenty-two is the band the change is for: over the eighteen the old six-cell
	 * descent could carry, under the twenty-two the new four-cell one can.</p>
	 */
	@Test
	void cutsChordsTheOldDescentCouldNotCarry() {
		int cheap = splitDescents(true);
		int old = splitDescents(false);
		System.out.println("SPLITDESCENT chords 19..22 cut across a descent: cheap=" + cheap
			+ " old=" + old);
		assertTrue(old == 0, "the old descent should not have been able to cut a chord this big, "
			+ "and cut " + old + " of them");
		assertTrue(cheap > 0, "no chord of nineteen to twenty-two was cut across a descent, so the "
			+ "cheap descent is never built and nothing below tests it");
	}

	private static int splitDescents(boolean cheap) {
		SongBuilder.CHEAP_SPLIT_DESCENT = cheap;
		int fired = 0;
		for (int floors = 2; floors <= 4; floors++) {
			for (int width = 16; width <= 32; width += 8) {
				for (Map.Entry<String, Integer> entry
						: build(bigChordSong(19, 22, 7L), width, floors).padding().entrySet()) {
					if (entry.getKey().endsWith("SplitDescent")) {
						fired += entry.getValue();
					}
				}
			}
		}
		return fired;
	}

	/** And having fired, the machine has to still be the song. */
	@Test
	void readsBackEveryNoteOfASongCutAcrossDescents() {
		SongBuilder.CHEAP_SPLIT_DESCENT = true;
		for (int floors = 2; floors <= 4; floors++) {
			for (int width = 16; width <= 32; width += 8) {
				List<SongBuilder.EventNote> notes = bigChordSong(19, 22, 7L);
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

	/**
	 * A song of ordinary chords still reads back, which is the check that nothing else moved.
	 *
	 * <p>Chords of fifteen to eighteen were already being cut across descents before any of this.
	 * They now go over the new staircase instead of the old one, so this is the same song built two
	 * ways, and both have to be the song.</p>
	 */
	@Test
	void readsBackASongThatWasAlreadyBeingCut() {
		for (boolean cheap : new boolean[] {false, true}) {
			SongBuilder.CHEAP_SPLIT_DESCENT = cheap;
			for (int floors = 2; floors <= 4; floors++) {
				List<SongBuilder.EventNote> notes = bigChordSong(15, 18, 3L);
				SongBuilder.PastePlan plan = build(notes, 24, floors);
				String where = (cheap ? "cheap" : "old") + " f" + floors + ": ";
				NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
				assertEquals(0, reading.unreachedNotes(),
					where + "note blocks the signal never got to");
				assertEquals("", difference(sounds(notes), sounds(reading.project())),
					where + "the machine did not read back as the song it was built from");
			}
		}
	}

	/** No run of wire past what a repeater reaches, which is the other way a descent can lie. */
	@Test
	void leavesNoDeadRunAcrossTheCheapDescent() {
		SongBuilder.CHEAP_SPLIT_DESCENT = true;
		for (int floors = 2; floors <= 4; floors++) {
			for (int width = 16; width <= 32; width += 8) {
				SongBuilder.PastePlan plan = build(bigChordSong(19, 22, 7L), width, floors);
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
