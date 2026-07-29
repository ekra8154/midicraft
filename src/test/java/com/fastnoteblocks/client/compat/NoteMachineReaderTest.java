package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Builds a song into blocks and reads it straight back out.
 *
 * <p>The planner and the reader are supposed to be exact inverses, and nothing else in the mod
 * checks that. A machine read a tick wrong still builds, still looks right, and only gives itself
 * away as a wrong note somewhere in the middle of a song -- so the check has to be arithmetic on
 * every note, not a listen.</p>
 */
class NoteMachineReaderTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A note as the music sees it: when it sounds, what it sounds like, and how high. */
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

	/**
	 * Only the two modes that decide their own shape.
	 *
	 * <p>The cube and the compact lane read their floor count and corridor width out of the saved
	 * config, and the config reads the game's directory, which does not exist out here. The layout
	 * they share with these two is the part being tested anyway: every mode lays down the same
	 * modules and differs only in where it folds.</p>
	 */
	@ParameterizedTest
	@EnumSource(value = SongBuilder.PasteMode.class, names = {"COMPACT", "LANE"})
	void readsBackEveryNoteOfItsOwnBuild(SongBuilder.PasteMode mode) {
		List<SongBuilder.EventNote> notes = sampleSong();
		SongBuilder.PastePlan plan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode);
		Map<BlockPos, BlockState> world = placeInWorld(plan);

		NoteMachineReader.Reading reading = readAll(world, "Round trip");

		assertEquals("", difference(sounds(notes), sounds(reading.project())),
			mode + ": the machine did not read back as the song it was built from");
		assertEquals(0, reading.unreachedNotes(), mode + ": some note blocks were never triggered");
	}

	/** Only the notes the two disagree about, so a failure names the bug instead of the song. */
	private static String difference(Map<Sound, Integer> expected, Map<Sound, Integer> actual) {
		java.util.TreeSet<Sound> all = new java.util.TreeSet<>(expected.keySet());
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

	/**
	 * The times have to line up as well as the notes.
	 *
	 * <p>Checked separately from the note comparison because a whole song shifted by one repeater
	 * tick compares equal note-for-note once both are rebased to their own first note. What is
	 * being pinned here is the spacing: the gaps in the song that comes out are the gaps that were
	 * put in, in the units a repeater actually counts.</p>
	 */
	@Test
	void readsBackTheOriginalSpacing() {
		List<SongBuilder.EventNote> notes = sampleSong();
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.COMPACT);
		NoteMachineReader.Reading reading = readAll(placeInWorld(plan), "Spacing");

		List<Long> expected = notes.stream()
			.map(note -> (note.time() - notes.get(0).time())
				* (long)NoteMachineReader.TICKS_PER_REDSTONE_TICK)
			.distinct()
			.sorted()
			.toList();
		List<Long> actual = reading.project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(ComposerProject.NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
		assertEquals(expected, actual, "the gaps between events did not survive the round trip");
	}

	/** What comes out has to be buildable as it stands, or reading a build in is a trap. */
	@Test
	void readsBackAsSomethingAlreadyOnTheRepeaterGrid() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			sampleSong(), SongBuilder.PasteMode.COMPACT);
		ComposerProject project = readAll(placeInWorld(plan), "Grid").project();

		double span = project.ppq() * 100_000.0 / project.tempoMicrosPerQuarter()
			* project.speedQuarters() / 4.0;
		assertEquals(NoteMachineReader.TICKS_PER_REDSTONE_TICK, span, 1.0e-9,
			"a repeater tick should be a whole number of composer ticks");
		for (ComposerProject.Layer layer : project.layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				assertEquals(0L, note.startTick() % NoteMachineReader.TICKS_PER_REDSTONE_TICK,
					"note at " + note.startTick() + " is off the repeater grid");
				assertTrue(note.isBuildable(), "note came back outside the note block range");
			}
		}
	}

	/**
	 * A note block nothing can set off is left out and counted, rather than placed at tick zero.
	 *
	 * <p>Note that stopping the region short of the end of a machine is <em>not</em> this case, and
	 * should not be: the part inside the box is a real, shorter song, and the notes outside it were
	 * never in the reading to begin with. What this is about is a note block that is in the box and
	 * has nothing to play it -- a machine someone has taken a repeater out of.</p>
	 */
	@Test
	void reportsNoteBlocksTheSignalNeverReached() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			sampleSong(), SongBuilder.PasteMode.LANE);
		Map<BlockPos, BlockState> world = new HashMap<>(placeInWorld(plan));
		Bounds bounds = Bounds.of(world.keySet());
		// Stranded well clear of the machine, so nothing it does can reach this.
		world.put(new BlockPos(bounds.maxX + 6, bounds.maxY + 6, bounds.maxZ + 6),
			Blocks.NOTE_BLOCK.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "Stranded");

		assertEquals(1, reading.unreachedNotes(),
			"the stranded note block should be the only one nothing reaches");
		assertTrue(reading.report().contains("never triggered"),
			"the report should mention it: " + reading.report());
	}

	/**
	 * Several starts mean several guesses, and the guesses are only right if they all begin
	 * together. Worth saying so out loud rather than presenting the result as read fact.
	 */
	@Test
	void warnsWhenAMachineHasMoreThanOneStart() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			sampleSong(), SongBuilder.PasteMode.LANE);
		Map<BlockPos, BlockState> world = new HashMap<>(placeInWorld(plan));
		Bounds bounds = Bounds.of(world.keySet());
		assertEquals(1, readAll(world, "One start").sources(), "the build should have one start");

		// A second chain, unconnected to the first, standing on its own.
		BlockPos apart = new BlockPos(bounds.minX, bounds.maxY + 4, bounds.minZ);
		world.put(apart, Blocks.STONE.defaultBlockState());
		world.put(apart.above(), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(apart.above().east(), Blocks.NOTE_BLOCK.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "Two starts");
		assertEquals(2, reading.sources(), "both chains should be found");
		assertTrue(reading.report().contains("started together"),
			"the report should say the two are only lined up on an assumption: " + reading.report());
	}

	// ------------------------------------------------------------------ helpers

	private static NoteMachineReader.Reading readAll(Map<BlockPos, BlockState> world, String name) {
		Bounds bounds = Bounds.of(world.keySet());
		return NoteMachineReader.read(name,
			new BlockPos(bounds.minX, bounds.minY, bounds.minZ),
			new BlockPos(bounds.maxX, bounds.maxY, bounds.maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}

	/**
	 * A song with the things that have historically gone wrong in it: chords too big for a note
	 * block to carry alone, chords small enough not to need a bus, gaps longer than one repeater
	 * can hold, and enough length to force the folds, risers and descents.
	 */
	private static List<SongBuilder.EventNote> sampleSong() {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:sand"};
		Random random = new Random(20260729L);
		int time = 0;
		for (int event = 0; event < 220; event++) {
			// Every gap from one repeater tick to nine, so single repeaters, chains of them, and
			// the boundary at four all get walked.
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

	private static java.util.SortedMap<Sound, Integer> sounds(List<SongBuilder.EventNote> notes) {
		java.util.SortedMap<Sound, Integer> counts = new TreeMap<>();
		int first = notes.get(0).time();
		for (SongBuilder.EventNote note : notes) {
			counts.merge(new Sound(note.time() - first, instrumentOf(note.instrumentBlock()),
				note.pitch()), 1, Integer::sum);
		}
		return counts;
	}

	private static java.util.SortedMap<Sound, Integer> sounds(ComposerProject project) {
		java.util.SortedMap<Sound, Integer> counts = new TreeMap<>();
		for (ComposerProject.Layer layer : project.layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				counts.merge(new Sound(
					(int)(note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK),
					layer.instrument(),
					note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE), 1, Integer::sum);
			}
		}
		return counts;
	}

	/** What the game will call the sound of a note block standing on this block. */
	private static String instrumentOf(String blockId) {
		return parse(blockId).instrument().name();
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			// "setblock <x> <y> <z> <block state> replace" -- parsed rather than read off an
			// internal map, so what is tested is what actually goes to the server.
			String[] parts = command.split(" ");
			BlockPos position = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3]));
			world.put(position, parse(parts[4]));
		}
		return world;
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}

	private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		static Bounds of(Iterable<BlockPos> positions) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (BlockPos position : positions) {
				minX = Math.min(minX, position.getX());
				minY = Math.min(minY, position.getY());
				minZ = Math.min(minZ, position.getZ());
				maxX = Math.max(maxX, position.getX());
				maxY = Math.max(maxY, position.getY());
				maxZ = Math.max(maxZ, position.getZ());
			}
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
		}
	}
}
