package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.Comparator;
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
	/**
	 * Stands in for the saved build settings, which cannot be read without a game.
	 *
	 * <p>Deliberately tight. Few floors and a narrow corridor force the sample song to fold many
	 * times over, so the climbs and descents are walked dozens of times rather than once.</p>
	 */
	private static final SongBuilder.BuildLimits LIMITS = new SongBuilder.BuildLimits(4, 24, 3);

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
	 * Every mode that round-trips exactly, which is all but the cube.
	 *
	 * <p>The compact lane is the one that earns its place. A flat build only runs the signal along
	 * the ground, but that mode carries it between floors -- up a glass staircase, where a dust step
	 * connects only because the block over it is see-through, and down a spiral, where it connects
	 * only because nothing is in the way. Those two rules are the fiddliest thing the reader knows
	 * and nothing else here would notice them being wrong.</p>
	 *
	 * <p>The cube is left out because it does <em>not</em> round-trip exactly, and the evidence says
	 * the fault is in the build rather than in the reader: one note of a thousand reads four ticks
	 * -- one full repeater -- early, there is only one note block that could be it, and it has
	 * exactly one repeater pointed at it. So nothing is double-driving the note; the reader is
	 * reaching that repeater early by another path. A repeater is driven by any powered solid block
	 * directly behind it, and {@link SongBuilder.PlacementPlan#verify} only ever checks what powers
	 * a note block, never what powers a repeater, so a lane leaking into the back of a neighbouring
	 * lane's repeater is invisible to it. Cube layout is still covered by the spacing and grid tests
	 * below; what is not asserted here is that every note lands on the tick it was built for.</p>
	 */
	@ParameterizedTest
	@EnumSource(value = SongBuilder.PasteMode.class, mode = EnumSource.Mode.EXCLUDE,
		names = {"COMPACT_CUBE"})
	void readsBackEveryNoteOfItsOwnBuild(SongBuilder.PasteMode mode) {
		List<SongBuilder.EventNote> notes = sampleSong();
		SongBuilder.PastePlan plan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, LIMITS);
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
			SongBuilder.PasteMode.COMPACT_CUBE, LIMITS);
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
			sampleSong(), SongBuilder.PasteMode.COMPACT_CUBE, LIMITS);
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
			sampleSong(), SongBuilder.PasteMode.LANE, LIMITS);
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
			sampleSong(), SongBuilder.PasteMode.LANE, LIMITS);
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

	/**
	 * A second way into a machine is a second way to start it, not a second voice.
	 *
	 * <p>Adding an input partway along an existing chain gives the signal somewhere else to enter.
	 * Followed together with the real beginning, every note from the join onwards takes the nearer
	 * chain's timing and lands on top of the song's own opening -- every note still present, the
	 * tune wrecked, and nothing about the result looking wrong. The way in reaching more of the
	 * machine is the real one, and a start halfway along reaches strictly less of it, so preferring
	 * the larger is what recovers the original.</p>
	 */
	@Test
	void prefersTheWayInThatReachesMostOfTheMachine() {
		List<SongBuilder.EventNote> notes = sampleSong();
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.LANE, LIMITS);
		Map<BlockPos, BlockState> world = new HashMap<>(placeInWorld(plan));
		java.util.SortedMap<Sound, Integer> intended = sounds(readAll(world, "Before").project());
		assertEquals("", difference(sounds(notes), intended), "sanity: the build should read clean");

		// A repeater facing into a note block that already drives the next module. Powering that
		// block is exactly what the module before it does, so this is a genuine second way in
		// rather than a block stuck on the side.
		BlockPos joint = midChainAnchor(world);
		BlockPos tap = joint.north();
		world.put(tap.below(), Blocks.STONE.defaultBlockState());
		world.put(tap, parse("minecraft:repeater[facing=north,delay=1]"));

		NoteMachineReader.Reading reading = readAll(world, "Two ways in");

		assertEquals("", difference(intended, sounds(reading.project())),
			"the song should read back as it did before the extra input was added");
		assertEquals(1, reading.sources(), "the two chains are one machine, not two");
		assertTrue(reading.warnings().stream().anyMatch(text -> text.contains("other way")),
			"the ignored way in should be reported: " + reading.report());
	}

	/**
	 * Two machines that share no note blocks stay separate, and say so.
	 *
	 * <p>The other half of the same judgement. Nothing joins them, so nothing says they are one
	 * piece of music -- they are kept apart, given their own numbered layers, and the guess that
	 * they start together is stated rather than buried.</p>
	 */
	@Test
	void keepsMachinesThatShareNothingApart() {
		Map<BlockPos, BlockState> world = new HashMap<>(placeInWorld(
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), sampleSong(),
				SongBuilder.PasteMode.LANE, LIMITS)));
		Bounds bounds = Bounds.of(world.keySet());

		// A second machine standing well clear of the first: repeater, note block, nothing shared.
		BlockPos apart = new BlockPos(bounds.minX, bounds.maxY + 6, bounds.minZ);
		world.put(apart, Blocks.STONE.defaultBlockState());
		world.put(apart.above(), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(apart.above().east(), Blocks.NOTE_BLOCK.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "Two machines");

		assertEquals(2, reading.sources(), "both machines should survive");
		assertTrue(reading.warnings().stream().anyMatch(text -> text.contains("separate machines")),
			"the guess that they start together should be stated: " + reading.report());
		assertTrue(reading.project().layers().stream().anyMatch(layer -> layer.name().endsWith("2")),
			"the second machine should get its own numbered layers: "
				+ reading.project().layers().stream().map(ComposerProject.Layer::name).toList());
	}

	/** A note block partway along the chain that also drives whatever comes next. */
	private static BlockPos midChainAnchor(Map<BlockPos, BlockState> world) {
		List<BlockPos> anchors = world.entrySet().stream()
			.filter(entry -> entry.getValue().is(Blocks.NOTE_BLOCK))
			.map(Map.Entry::getKey)
			.filter(position -> world.getOrDefault(position.east(),
				Blocks.AIR.defaultBlockState()).is(Blocks.REPEATER))
			.sorted(Comparator.comparingInt(BlockPos::getX))
			.toList();
		return anchors.get(anchors.size() / 2);
	}

	/**
	 * Two ways in that meet at a shared tail, each holding notes the other never reaches.
	 *
	 * <p>The shape the "prefer the bigger" rule cannot fully honour, and the reason it says so. Two
	 * openings converging on one chorus are not a checkpoint and its run: pressing either button is
	 * a real performance, they disagree about what comes first, and no single timeline holds both --
	 * the tail sits a different distance from each opening, so there is no offset that makes the two
	 * agree. One is chosen and the other's opening is left out, which is a loss worth naming rather
	 * than folding into the general count of note blocks nothing triggered.</p>
	 */
	@Test
	void namesTheMusicLostWhenTwoOpeningsShareATail() {
		Map<BlockPos, BlockState> world = convergingBranches(0);

		NoteMachineReader.Reading reading = readAll(world, "Two openings");

		assertEquals(1, reading.sources(), "sharing a tail makes them one machine");
		assertEquals(1, reading.unreachedNotes(), "the losing opening's note should be left out");
		assertTrue(reading.warnings().stream()
				.anyMatch(text -> text.contains("different versions")),
			"losing real music should not read as a skipped checkpoint: " + reading.report());
	}

	/** The same shape with one opening longer, where the longer one is the one that survives. */
	@Test
	void prefersTheLongerOpeningWhenTwoShareATail() {
		Map<BlockPos, BlockState> world = convergingBranches(2);

		NoteMachineReader.Reading reading = readAll(world, "Uneven openings");

		assertEquals(1, reading.sources(), "still one machine");
		// The long opening's three notes and the shared tail survive; the short one's single note
		// is what gets left behind.
		assertEquals(1, reading.unreachedNotes(), "only the short opening should be left out");
		int kept = reading.project().layers().stream()
			.mapToInt(layer -> layer.notes().size()).sum();
		assertEquals(4, kept, "the longer opening plus the shared tail should be what is kept");
	}

	/**
	 * Two chains into one tail: A along z=0, B along z=2, meeting at a note block.
	 *
	 * @param extraOnA how many more note blocks opening A has than opening B, to settle which wins
	 */
	private static Map<BlockPos, BlockState> convergingBranches(int extraOnA) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		int y = 65;
		// Opening A runs east along z=0, gaining a note block per extra step asked for.
		int x = 0;
		world.put(new BlockPos(x, y - 1, 0), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(x, y, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		for (int step = 0; step <= extraOnA; step++) {
			world.put(new BlockPos(++x, y, 0), note(step + 1));
			world.put(new BlockPos(++x, y - 1, 0), Blocks.STONE.defaultBlockState());
			world.put(new BlockPos(x, y, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		}
		BlockPos shared = new BlockPos(++x, y, 0);
		world.put(shared, note(20));

		// Opening B runs east along z=2 and turns north into the same shared note block.
		world.put(new BlockPos(0, y - 1, 2), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(0, y, 2), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(new BlockPos(1, y, 2), note(9));
		world.put(new BlockPos(2, y - 1, 2), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(2, y, 2), parse("minecraft:repeater[facing=west,delay=1]"));
		// Dust, not a line of stone: stone carries nothing, so a stone run would leave B dead at
		// its first block and turn the two openings into two unrelated machines.
		for (int carry = 3; carry <= shared.getX(); carry++) {
			world.put(new BlockPos(carry, y - 1, 2), Blocks.STONE.defaultBlockState());
			world.put(new BlockPos(carry, y, 2), Blocks.REDSTONE_WIRE.defaultBlockState());
		}
		world.put(new BlockPos(shared.getX(), y - 1, 1), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(shared.getX(), y, 1), parse("minecraft:repeater[facing=south,delay=1]"));
		return world;
	}

	private static BlockState note(int pitch) {
		return parse("minecraft:note_block[note=" + pitch + "]");
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
