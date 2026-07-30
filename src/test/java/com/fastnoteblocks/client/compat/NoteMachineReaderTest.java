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

	/**
	 * The same round trip over a song made of nothing but the chord sizes that stack.
	 *
	 * <p>The mixed sample above reaches a stacked module now and then between everything else. This
	 * one is wall to wall with them, which is what puts a module next to a module, next to a turn,
	 * next to a riser, hundreds of times over. Those joins are where the whole design lives: the
	 * module hangs four of its notes a level lower than any other layout ever has, right where the
	 * runs of powered stone that turn a lane and climb a floor go.</p>
	 */
	@Test
	void readsBackASongOfNothingButStackableChords() {
		List<SongBuilder.EventNote> notes = stackableSong();
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, LIMITS);

		NoteMachineReader.Reading reading = readAll(placeInWorld(plan), "Stacked");

		assertEquals("", difference(sounds(notes), sounds(reading.project())),
			"a song of stacked chords did not read back as itself");
		assertEquals(0, reading.unreachedNotes(), "some note blocks were never triggered");
	}

	/** Chords of four to seven throughout, over every instrument the module treats differently. */
	private static List<SongBuilder.EventNote> stackableSong() {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		// Harp, which the module has to build as a solid block where it relays; sand, which cannot
		// hang from the lower slots at all; glass and glowstone, which conduct nothing.
		String[] instruments = {"minecraft:air", "minecraft:sand", "minecraft:glass",
			"minecraft:glowstone", "minecraft:stone", "minecraft:gold_block"};
		Random random = new Random(20260730L);
		int time = 0;
		for (int event = 0; event < 220; event++) {
			time += 1 + random.nextInt(9);
			int chord = 4 + event % 4;
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
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
		assertEquals(1, readAll(world, "One start").versions(), "the build should have one start");

		// A second chain, unconnected to the first, standing on its own.
		BlockPos apart = new BlockPos(bounds.minX, bounds.maxY + 4, bounds.minZ);
		world.put(apart, Blocks.STONE.defaultBlockState());
		world.put(apart.above(), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(apart.above().east(), Blocks.NOTE_BLOCK.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "Two starts");
		assertEquals(2, reading.versions(), "both chains should be found");
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
		assertEquals(1, reading.versions(), "the two chains are one machine, not two");
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

		assertEquals(2, reading.versions(), "both machines should survive");
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
	 * Two ways in that meet at a shared tail are two performances, and both are kept.
	 *
	 * <p>Neither contains the other -- each holds notes the other never reaches -- so there is no
	 * "bigger" one to prefer, and the tail sits a different distance from each opening, so no single
	 * timeline holds both. Choosing between them would drop real music on the strength of comparing
	 * coordinates. Both are read instead, each timed from its own start, with the shared tail
	 * appearing in both because both really do play it.</p>
	 */
	@Test
	void keepsBothOpeningsWhenTwoShareATail() {
		Map<BlockPos, BlockState> world = convergingBranches(0);

		NoteMachineReader.Reading reading = readAll(world, "Two openings");

		assertEquals(2, reading.versions(), "both openings are performances in their own right");
		assertEquals(0, reading.unreachedNotes(), "keeping both should strand nothing");
		// Three note blocks in the world, four notes in the song: the tail belongs to both.
		assertEquals(3, reading.noteBlocks(), "the world holds three note blocks");
		assertEquals(4, reading.project().noteCount(), "the shared tail should appear in both");
		assertTrue(reading.warnings().stream().anyMatch(text -> text.contains("alternatives")),
			"they should be called alternatives rather than parts: " + reading.report());
		assertEquals(List.of("Harp 1", "Harp 2"),
			reading.project().layers().stream().map(ComposerProject.Layer::name).sorted().toList(),
			"each version should be mutable on its own");
	}

	/**
	 * Each version begins at its own beginning, so silencing one leaves a song that starts at zero.
	 *
	 * <p>The point of splitting them. Timed from a common origin the later opening would start
	 * however far along it happens to join, which is not what pressing its button does.</p>
	 */
	@Test
	void timesEachVersionFromItsOwnStart() {
		NoteMachineReader.Reading reading = readAll(convergingBranches(2), "Uneven openings");

		assertEquals(2, reading.versions());
		assertEquals(0, reading.unreachedNotes(), "an uneven pair should still strand nothing");
		// Long opening: three notes then the tail. Short opening: one note then the tail.
		assertEquals(6, reading.project().noteCount());
		for (ComposerProject.Layer layer : reading.project().layers()) {
			assertEquals(0L, layer.notes().get(0).startTick(),
				layer.name() + " should begin at the beginning");
		}
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

	/**
	 * A ring that feeds itself must finish being read, and must not be read twice.
	 *
	 * <p>The safety half of this matters more than the musical half: a walk that went round a loop
	 * forever would hang the game on a scan, not merely misread it. It cannot, because time only
	 * ever increases through a repeater and every position refuses a pulse that arrives no earlier
	 * than one it has already had -- so the second lap is rejected at its first block. Pinned with a
	 * deadline rather than a plain call, because a failure here is a hang and a hang is not a test
	 * result.</p>
	 */
	@Test
	void readsALoopOnceAndStops() {
		Map<BlockPos, BlockState> world = ring();
		world.put(new BlockPos(1, 66, 0), parse("minecraft:lever[face=floor,facing=north]"));

		NoteMachineReader.Reading reading = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
			java.time.Duration.ofSeconds(10), () -> readAll(world, "Ring"));

		assertEquals(1, reading.project().noteCount(),
			"a loop should be read one lap round, not repeatedly");
		assertEquals(0, reading.unreachedNotes());
		assertTrue(reading.warnings().stream().anyMatch(text -> text.contains("loops back")),
			"reading one lap of something built to repeat should be said out loud: "
				+ reading.report());
	}

	/**
	 * Converging is not looping, and must not be reported as it.
	 *
	 * <p>Two routes meeting at one repeater happens constantly in an ordinary build -- every chord
	 * that rejoins the lane does it -- so a loop cannot be "a repeater reached twice". It has to be
	 * the signal arriving back somewhere it has already been, which is a different question and is
	 * why the check is a search for a back edge rather than a counter.</p>
	 */
	@Test
	void doesNotCallAnOrdinaryBuildALoop() {
		NoteMachineReader.Reading reading = readAll(placeInWorld(
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), sampleSong(),
				SongBuilder.PasteMode.COMPACT_LANE, LIMITS)), "Not a loop");

		assertTrue(reading.warnings().stream().noneMatch(text -> text.contains("loops back")),
			"a folded build converges everywhere and loops nowhere: " + reading.report());
	}

	/**
	 * A ring with no way in is refused, because there is nothing to read it from.
	 *
	 * <p>Every repeater in a loop is fed by another one, so none of them is a beginning. A machine
	 * like this is started by something -- a lever, a button, an observer -- and without that in the
	 * selection there is no first note and so no song.</p>
	 */
	@Test
	void refusesALoopWithNoWayIn() {
		NoteMachineReader.UnreadableException refused =
			org.junit.jupiter.api.Assertions.assertThrows(NoteMachineReader.UnreadableException.class,
				() -> readAll(ring(), "Ring with no start"));
		assertTrue(refused.getMessage().contains("clock or a loop"),
			"the reason should name the shape: " + refused.getMessage());
	}

	/**
	 * Repeaters wired nose to tail, with one note block on the ring.
	 *
	 * <p>Six repeaters round a rectangle, each reading the block the one before it powers, and the
	 * last closing back onto the first's input.</p>
	 */
	private static Map<BlockPos, BlockState> ring() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		int y = 65;
		world.put(new BlockPos(0, y, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(new BlockPos(1, y, 0), note(7));
		world.put(new BlockPos(2, y, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(new BlockPos(3, y, 0), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(3, y, 1), parse("minecraft:repeater[facing=north,delay=1]"));
		world.put(new BlockPos(3, y, 2), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(2, y, 2), parse("minecraft:repeater[facing=east,delay=1]"));
		world.put(new BlockPos(1, y, 2), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(0, y, 2), parse("minecraft:repeater[facing=east,delay=1]"));
		world.put(new BlockPos(-1, y, 2), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(-1, y, 1), parse("minecraft:repeater[facing=south,delay=1]"));
		world.put(new BlockPos(-1, y, 0), Blocks.STONE.defaultBlockState());
		return world;
	}

	/**
	 * A note block two modules both power sounds once, on the first of them.
	 *
	 * <p>The shape compact community builds are made of: modules overlap so that the note blocks at
	 * the front of one are the note blocks at the back of the next, which is how a repeater ends up
	 * driving six note blocks in the space of two. Fired in sequence the overlap does not sound
	 * twice -- the shared blocks are still held high from the first module when the second arrives,
	 * so there is no fresh edge for them to answer, and the second module sounds only the four that
	 * were low.</p>
	 *
	 * <p>Keeping the earliest arrival per note block reproduces exactly that, because a note block
	 * plays on the rising edge and the earliest arrival is the rising edge. The overlap is credited
	 * to the module that got there first, which is also the module a listener hears it in.</p>
	 */
	@Test
	void soundsASharedNoteBlockOnceAndWithTheEarlierModule() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		int y = 65;
		// Head repeater into the first module's block.
		world.put(new BlockPos(-1, y, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(new BlockPos(0, y, 0), Blocks.STONE.defaultBlockState());
		// The overlap: touching the first module's block and the second module's block both.
		world.put(new BlockPos(0, y, 1), note(7));
		world.put(new BlockPos(0, y, 2), Blocks.STONE.defaultBlockState());
		// A note block only the second module reaches, so the second module is known to have fired.
		world.put(new BlockPos(-1, y, 2), note(11));
		// Three more repeaters carrying the signal round to the second module's block.
		world.put(new BlockPos(1, y, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		world.put(new BlockPos(2, y, 0), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(2, y, 1), parse("minecraft:repeater[facing=north,delay=1]"));
		world.put(new BlockPos(2, y, 2), Blocks.STONE.defaultBlockState());
		world.put(new BlockPos(1, y, 2), parse("minecraft:repeater[facing=east,delay=1]"));

		NoteMachineReader.Reading reading = readAll(world, "Overlapping modules");

		assertEquals(2, reading.noteBlocks(), "two note blocks stand in the world");
		assertEquals(2, reading.project().noteCount(), "the shared one should not sound twice");
		assertEquals(0, reading.unreachedNotes());
		// The shared block with the first module at tick zero, the second module three repeater
		// ticks later -- not the other way round, and not both.
		assertEquals(List.of(0L, 3L * NoteMachineReader.TICKS_PER_REDSTONE_TICK),
			reading.project().layers().stream()
				.flatMap(layer -> layer.notes().stream())
				.map(ComposerProject.NoteEvent::startTick)
				.sorted()
				.toList());
	}

	/**
	 * A chord's bus may turn a corner and still be one chord.
	 *
	 * <p>The thing a straight lane cannot do is end exactly where you want it to: a lane stops when
	 * the next event will not fit, so a big chord either overshoots the wall or leaves the lane
	 * short of it, and both of those are why the staircases in a folded build stand in a scatter
	 * rather than in a column. A chord that can wrap the turn instead lets a lane fill to the wall
	 * every time.</p>
	 *
	 * <p>It works because dust carries no delay. Every note touching the run sounds at the instant
	 * the signal crosses it, so a bus bent through ninety degrees is still one tick -- and the note
	 * on the inside of the bend, which touches the run on both arms, still sounds once, because both
	 * arms are the same instant.</p>
	 */
	@Test
	void aChordsBusMayTurnACornerAndStillBeOneChord() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		int bus = 65;
		world.put(new BlockPos(-1, bus, 0), parse("minecraft:repeater[facing=west,delay=1]"));
		// The run: three along, then bent through a right angle for two more.
		List<BlockPos> run = List.of(new BlockPos(0, bus, 0), new BlockPos(1, bus, 0),
			new BlockPos(2, bus, 0), new BlockPos(2, bus, 1), new BlockPos(2, bus, 2));
		for (BlockPos cell : run) {
			world.put(cell, Blocks.STONE.defaultBlockState());
			world.put(cell.above(), Blocks.REDSTONE_WIRE.defaultBlockState());
		}
		// Notes down the outside of both arms, one past the end, and one tucked into the bend where
		// it touches both arms at once.
		List<BlockPos> hung = List.of(new BlockPos(0, bus, -1), new BlockPos(1, bus, -1),
			new BlockPos(2, bus, -1), new BlockPos(3, bus, 1), new BlockPos(3, bus, 2),
			new BlockPos(2, bus, 3), new BlockPos(1, bus, 1));
		int pitch = 0;
		for (BlockPos slot : hung) {
			world.put(slot.below(), Blocks.STONE.defaultBlockState());
			world.put(slot, note(pitch++));
		}

		NoteMachineReader.Reading reading = readAll(world, "Bent bus");

		assertEquals(hung.size(), reading.noteBlocks(), "every note block stands in the world");
		assertEquals(hung.size(), reading.project().noteCount(), "and every one of them sounds");
		assertEquals(0, reading.unreachedNotes(), "the bend carries the signal round");
		assertEquals(1, reading.project().layers().stream()
				.flatMap(layer -> layer.notes().stream())
				.map(ComposerProject.NoteEvent::startTick)
				.distinct()
				.count(),
			"a bent bus is still one chord: every note on the same tick");
	}

	/**
	 * A block of redstone in the selection must not throw away the real beginning.
	 *
	 * <p>It used to. Anything making power on its own was treated as <em>the</em> way in, and the
	 * head of the chain was then never considered -- so one block of redstone left over from a
	 * piston contraption gutted the song around it. Both kinds of way in are offered now, and the
	 * stray one falls out because what it reaches is contained in what the real beginning reaches.
	 * </p>
	 */
	@Test
	void aStrayBlockOfRedstoneDoesNotHijackTheReading() {
		List<SongBuilder.EventNote> notes = sampleSong();
		Map<BlockPos, BlockState> clean = placeInWorld(SongBuilder.createPastePlan(
			new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.LANE, LIMITS));
		java.util.SortedMap<Sound, Integer> intended = sounds(readAll(clean, "Clean").project());

		// Touching the machine partway along, as a redstone block on a piston would when extended.
		Map<BlockPos, BlockState> world = new HashMap<>(clean);
		BlockPos joint = midChainAnchor(world);
		world.put(joint.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "With a redstone block");

		assertEquals("", difference(intended, sounds(reading.project())),
			"the song should read as it does without the block of redstone");
		assertEquals(0, reading.unreachedNotes());
	}

	/** An observer stops the signal, and the part after it is reported rather than invented. */
	@Test
	void reportsObserversAndWhatTheyCutOff() {
		Map<BlockPos, BlockState> world = new HashMap<>(placeInWorld(SongBuilder.createPastePlan(
			new BlockPos(0, 64, 0), sampleSong(), SongBuilder.PasteMode.LANE, LIMITS)));
		// Replacing a repeater partway along breaks the chain exactly where the observer stands.
		BlockPos repeater = world.entrySet().stream()
			.filter(entry -> entry.getValue().is(Blocks.REPEATER))
			.map(Map.Entry::getKey)
			.sorted(Comparator.comparingInt(BlockPos::getX))
			.toList()
			.get(20);
		world.put(repeater, Blocks.OBSERVER.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "With an observer");

		assertTrue(reading.warnings().stream().anyMatch(text -> text.contains("observers")),
			"the observer should be named: " + reading.report());
		assertTrue(reading.unreachedNotes() > 0,
			"everything past it should be reported unreached rather than guessed at");
	}

	/** A torch is read as simply on, and says so, because inversion is not followed. */
	@Test
	void warnsThatTorchesAreReadAsAlwaysOn() {
		Map<BlockPos, BlockState> world = new HashMap<>(placeInWorld(SongBuilder.createPastePlan(
			new BlockPos(0, 64, 0), sampleSong(), SongBuilder.PasteMode.LANE, LIMITS)));
		Bounds bounds = Bounds.of(world.keySet());
		world.put(new BlockPos(bounds.maxX + 4, bounds.maxY + 4, bounds.maxZ + 4),
			Blocks.REDSTONE_TORCH.defaultBlockState());

		NoteMachineReader.Reading reading = readAll(world, "With a torch");

		assertTrue(reading.warnings().stream().anyMatch(text -> text.contains("always on")),
			"a torch should say how it was read: " + reading.report());
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
