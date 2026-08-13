package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The two lanes have to agree about when they start, and nothing else here can tell you they do.
 *
 * <p>Reading a lane back says what it plays relative to its own first note, which is exactly the
 * thing that cannot answer this: two lanes each perfect against their own opening can still be a
 * repeater tick apart from each other, and then every note on one of them lands on the wrong side
 * of the note it was meant to fall between. So this counts the wire itself -- the repeaters laid
 * before each lane's first note block -- and asks whether the difference between the two is the
 * difference the song asked for.</p>
 */
class HalfTickLaneTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final SongBuilder.BuildLimits LIMITS = new SongBuilder.BuildLimits(4, 44, 3);

	private static SongBuilder.EventNote note(int gameTick, int pitch) {
		return new SongBuilder.EventNote(gameTick, 1, 0, pitch, "minecraft:gold_block");
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> song) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.HALF_TICK_LANE, LIMITS);
	}

	/**
	 * ekran's own example: a b c d, one game tick apart, alternating lanes.
	 *
	 * <p>Each lane sees one repeater tick between its two notes, because a and c are two game ticks
	 * apart and so are b and d. That is the whole reason the split works, and it is worth pinning as
	 * an example rather than as an argument.</p>
	 */
	@Test
	void putsEveryOtherNoteOnTheOtherLane() {
		SongBuilder.PastePlan plan = build(List.of(note(0, 0), note(1, 1), note(2, 2), note(3, 3)));
		assertEquals(List.of(0, 2), pitchesOnLane(plan, true),
			"the right lane takes the even game ticks");
		assertEquals(List.of(1, 3), pitchesOnLane(plan, false),
			"the left lane takes the odd ones");
	}

	/**
	 * A lane opening on nothing still costs a repeater tick, and the other lane has to pay it too.
	 *
	 * <p>The right lane's first event is at game tick 0, which asks for no delay at all and gets one
	 * regardless -- a repeater has no shorter setting. The left lane's first is at game tick 5, two
	 * repeater ticks along its own clock, and wants no clamping. Charge one and not the other and
	 * the lanes are a repeater tick apart from there on, so what this asserts is the gap between the
	 * two lead-ins, which is the only number the wiring cannot fix afterwards.</p>
	 */
	@Test
	void keepsTheTwoLaneStartsTheDistanceApartTheSongAsksFor() {
		SongBuilder.PastePlan plan = build(List.of(note(0, 0), note(5, 1), note(8, 2), note(11, 3)));
		int right = leadInTicks(plan, true);
		int left = leadInTicks(plan, false);
		// Right opens at game tick 0, so lane time 0; left at game tick 5, so lane time (5-1)/2 = 2.
		assertEquals(2, left - right,
			"the lanes must open the same distance apart as their first events, "
				+ "and were right=" + right + " left=" + left);
		assertTrue(right >= 1, "no repeater delays by less than one tick, so the bias must be paid");
	}

	/** And the ordinary case, where neither lane is clamped, is unchanged by the bias. */
	@Test
	void leavesTwoLanesAloneWhenNeitherOpensOnTheFirstTick() {
		SongBuilder.PastePlan plan = build(List.of(note(4, 0), note(9, 1), note(12, 2)));
		assertEquals(2, leadInTicks(plan, false) - leadInTicks(plan, true),
			"lane times 2 and 4, so two repeater ticks apart");
	}

	/**
	 * A song entirely on one parity still builds, as one lane.
	 *
	 * <p>Which lane it is cannot be asked here and that is not a gap in the test: an empty lane
	 * places nothing, and the finished plan slides to sit against the origin, so a build of only
	 * even notes and a build of only odd ones stand in the same place. What matters is that the
	 * absent lane costs nothing -- three columns, not seven.</p>
	 */
	@Test
	void buildsASongThatNeverTouchesOneLane() {
		List<SongBuilder.EventNote> song = new ArrayList<>();
		for (int gameTick : new int[] {0, 2, 4}) {
			// Three to a chord, so the lane is its full three columns wide and the span below is
			// measuring an absent second lane rather than a chord too small to fill the first.
			for (int index = 0; index < 3; index++) {
				song.add(new SongBuilder.EventNote(gameTick, 1, index, index, "minecraft:gold_block"));
			}
		}
		SongBuilder.PastePlan plan = build(song);
		assertEquals(3, plan.spanZ(), "one lane's worth of columns and no reservation for the other");
		assertEquals(9, noteBlocks(plan).size(), "with every note in it");
		assertEquals(0, plan.wrongNotes(), "and nothing wrong with it");
	}

	/**
	 * The chord is not split, whatever the tick.
	 *
	 * <p>The half tick buys precision, and it was explicitly not to be spent on chord size. Every
	 * note sharing a game tick shares a module.</p>
	 */
	@Test
	void keepsAChordWhole() {
		List<SongBuilder.EventNote> song = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			song.add(new SongBuilder.EventNote(3, 1, index, index, "minecraft:gold_block"));
		}
		song.add(note(6, 20));
		SongBuilder.PastePlan plan = build(song);
		assertEquals(12, pitchesOnLane(plan, false).size(), "the whole chord is on the odd lane");
		assertEquals(List.of(20), pitchesOnLane(plan, true), "and the lone note on the even one");
	}

	/**
	 * The claim itself: notes one and three game ticks apart, built, and heard back where they were
	 * written.
	 *
	 * <p>Nothing else here asks this. The lane tests above are about the plan's arithmetic and the
	 * library probe reads each lane against its own opening, which cannot see an absolute tick at
	 * all. So this one builds the two lanes, reads the note blocks back through the redstone, and
	 * puts every note it hears onto the song's own clock -- the right lane's repeater tick {@code r}
	 * is game tick {@code 2r}, the left lane's is {@code 2r + 1}, and each lane's own lead-in is
	 * counted off the blocks rather than assumed.</p>
	 *
	 * <p>What is taken on trust is one number, and it is the one that is wiring rather than
	 * planning: the left lane's trigger fires a game tick after the right lane's. Everything else
	 * here is read from the machine.</p>
	 */
	@Test
	void soundsNotesOneAndThreeGameTicksApart() {
		int[] written = {0, 1, 4, 7, 8, 11};
		List<SongBuilder.EventNote> song = new ArrayList<>();
		for (int index = 0; index < written.length; index++) {
			song.add(note(written[index], index));
		}
		SongBuilder.PastePlan plan = build(song);

		Map<Integer, Integer> heard = new java.util.TreeMap<>();
		heard.putAll(heardOnLane(plan, true));
		heard.putAll(heardOnLane(plan, false));

		// Both lanes are biased one repeater tick -- two game ticks -- later than nominal, together,
		// so that neither is clamped without the other. A shift both lanes share is the machine
		// starting a moment after the button, which is not a timing error; a shift only one of them
		// takes is, and that is what the assertion below would catch.
		Map<Integer, Integer> expected = new java.util.TreeMap<>();
		for (int index = 0; index < written.length; index++) {
			expected.put(index, written[index] + 2);
		}
		assertEquals(expected, heard,
			"every note, by pitch, at the game tick it was written on");
	}

	/**
	 * What one lane actually sounds, by pitch, on the song's clock.
	 *
	 * <p>Read with the other lane taken away, so that a note can only have been set off by its own
	 * lane's wire -- the two standing together is a separate question, and the library probe asks
	 * it.</p>
	 */
	private static Map<Integer, Integer> heardOnLane(SongBuilder.PastePlan plan, boolean rightLane) {
		double split = laneSplit(plan);
		Map<BlockPos, BlockState> world = new java.util.HashMap<>();
		for (Placed block : placed(plan)) {
			if (block.z() > split == rightLane) {
				world.put(new BlockPos(block.x(), block.y(), block.z()), parse(block.block()));
			}
		}
		if (world.isEmpty()) {
			return Map.of();
		}
		java.util.IntSummaryStatistics x = world.keySet().stream()
			.mapToInt(BlockPos::getX).summaryStatistics();
		java.util.IntSummaryStatistics y = world.keySet().stream()
			.mapToInt(BlockPos::getY).summaryStatistics();
		java.util.IntSummaryStatistics z = world.keySet().stream()
			.mapToInt(BlockPos::getZ).summaryStatistics();
		NoteMachineReader.Reading reading = NoteMachineReader.read("Half-tick lane",
			new BlockPos(x.getMin(), y.getMin(), z.getMin()),
			new BlockPos(x.getMax(), y.getMax(), z.getMax()),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
		assertEquals(0, reading.unreachedNotes(), "every note on the lane has to be reachable");

		// The reader counts from the lane's first note, so the lane's own lead-in puts that note
		// back where it belongs, and the lane's parity decides which game tick each tick lands on.
		int lead = leadInTicks(plan, rightLane);
		int parity = rightLane ? 0 : 1;
		Map<Integer, Integer> heard = new java.util.TreeMap<>();
		for (ComposerProject.Layer layer : reading.project().layers()) {
			for (ComposerProject.NoteEvent event : layer.notes()) {
				int lane = (int)(event.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK);
				heard.put(event.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE,
					2 * (lead + lane) + parity);
			}
		}
		return heard;
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}

	/**
	 * The two lanes are built alongside each other, not one after the other.
	 *
	 * <p>Not a nicety. A paste is a stream of commands the player walks beside, and blocks only land
	 * in chunks that are loaded -- so a build that finishes one lane before starting the other lays
	 * its second lane thousands of blocks behind whoever is watching, into chunks that have long
	 * since unloaded, and none of it arrives. ekran hit exactly that in the world.</p>
	 *
	 * <p>What is asserted is the property that fixes it: the paste front never drops far back down
	 * the build. It steps back a little constantly, because the lane that is behind is the one that
	 * goes next and a module is several columns long -- but a little is a module, not a song.</p>
	 */
	@Test
	void buildsBothLanesAlongsideEachOther() {
		List<SongBuilder.EventNote> song = new ArrayList<>();
		// Long enough that one-lane-then-the-other would be a jump of hundreds of columns.
		for (int event = 0; event < 400; event++) {
			song.add(note(event * 3, event % 25));
		}
		SongBuilder.PastePlan plan = build(song);

		int furthestBack = 0;
		int previousX = Integer.MIN_VALUE;
		for (Placed block : placed(plan)) {
			if (previousX != Integer.MIN_VALUE) {
				furthestBack = Math.max(furthestBack, previousX - block.x());
			}
			previousX = block.x();
		}
		int spanX = plan.spanX();
		assertTrue(furthestBack < 32,
			"the paste front stepped " + furthestBack + " blocks back down a build " + spanX
				+ " long; the two lanes are not being laid alongside each other");
	}

	/**
	 * A song whose two halves carry very different chords still keeps its pulses together.
	 *
	 * <p>The failure this exists for is the one ekran asked about and none of the checks above can
	 * see. A lane spends columns on the chords it carries, so a song with big chords on its even
	 * ticks and small ones on its odd ticks runs one pulse steadily ahead of the other -- every note
	 * on the beat, half of them sounding from outside the 48 blocks a note block carries. Timing is
	 * perfect and the song is unlistenable.</p>
	 *
	 * <p>Sixteen notes against two is a wider split than any real song, chosen so the drift would be
	 * unmissable without it: unpadded, this walks the two lanes about seven columns further apart
	 * per event, for hundreds of events.</p>
	 */
	@Test
	void keepsTheTwoPulsesWithinEarshotWhenTheChordsAreLopsided() {
		List<SongBuilder.EventNote> song = new ArrayList<>();
		for (int event = 0; event < 300; event++) {
			int gameTick = event * 2;
			// Even ticks: a big chord, which eats columns. Odd ticks: two notes, which barely do.
			for (int index = 0; index < 16; index++) {
				song.add(new SongBuilder.EventNote(gameTick, 1, index, index % 25,
					"minecraft:gold_block"));
			}
			for (int index = 0; index < 2; index++) {
				song.add(new SongBuilder.EventNote(gameTick + 1, 1, index, index % 25,
					"minecraft:gold_block"));
			}
		}
		SongBuilder.PastePlan plan = build(song);

		double split = laneSplit(plan);
		int rightEnd = noteBlocks(plan).stream().filter(block -> block.z() > split)
			.mapToInt(Placed::x).max().orElseThrow();
		int leftEnd = noteBlocks(plan).stream().filter(block -> block.z() < split)
			.mapToInt(Placed::x).max().orElseThrow();

		// Both lanes cover the same span of song, so where they finish is where their last pulses
		// stood at the same moment -- the drift, measured at the one point it is always largest.
		int drift = Math.abs(rightEnd - leftEnd);
		assertTrue(drift <= 48, "the two lanes ended " + drift
			+ " blocks apart, which is further than a note block can be heard; a listener by one "
			+ "pulse would not hear the other");
	}

	// ----------------------------------------------------------------- reading it off the blocks

	private static final Pattern SETBLOCK = Pattern.compile(
		"setblock (-?\\d+) (-?\\d+) (-?\\d+) (\\S+) replace");

	private record Placed(int x, int y, int z, String block) {
	}

	private static List<Placed> placed(SongBuilder.PastePlan plan) {
		List<Placed> blocks = new ArrayList<>();
		for (String command : plan.commands()) {
			Matcher match = SETBLOCK.matcher(command);
			if (match.matches()) {
				blocks.add(new Placed(Integer.parseInt(match.group(1)),
					Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)),
					match.group(4)));
			}
		}
		return blocks;
	}

	private static List<Placed> noteBlocks(SongBuilder.PastePlan plan) {
		return placed(plan).stream()
			.filter(block -> block.block().startsWith("minecraft:note_block")).toList();
	}

	/**
	 * The line between the two lanes.
	 *
	 * <p>A lane is three columns wide and the two sit at least three apart, so halfway between the
	 * outermost columns falls in the space between them whatever the gap is set to. Only meaningful
	 * when both lanes hold something, which is why the single-parity test does not ask.</p>
	 */
	private static double laneSplit(SongBuilder.PastePlan plan) {
		List<Placed> blocks = placed(plan);
		int minZ = blocks.stream().mapToInt(Placed::z).min().orElse(0);
		int maxZ = blocks.stream().mapToInt(Placed::z).max().orElse(0);
		return (minZ + maxZ) / 2.0;
	}

	/** Note pitches on one lane, in build order, read off the commands rather than the plan. */
	private static List<Integer> pitchesOnLane(SongBuilder.PastePlan plan, boolean rightLane) {
		double split = laneSplit(plan);
		return noteBlocks(plan).stream()
			.filter(block -> block.z() > split == rightLane)
			.map(block -> Integer.parseInt(block.block().replaceAll(".*note=(\\d+).*", "$1")))
			.toList();
	}

	/**
	 * Repeater ticks laid before a lane's first note sounds, counted off the blocks.
	 *
	 * <p>Every repeater standing in front of the first note block is part of getting there, so their
	 * delays added together are how long after the button that lane speaks. Read from the commands
	 * because that is what gets built; the arithmetic that produced them is the thing under test.</p>
	 */
	private static int leadInTicks(SongBuilder.PastePlan plan, boolean rightLane) {
		double split = laneSplit(plan);
		int firstNoteX = noteBlocks(plan).stream()
			.filter(block -> block.z() > split == rightLane)
			.mapToInt(Placed::x).min().orElseThrow();
		return placed(plan).stream()
			.filter(block -> block.block().startsWith("minecraft:repeater"))
			.filter(block -> block.z() > split == rightLane)
			.filter(block -> block.x() < firstNoteX)
			.mapToInt(block -> Integer.parseInt(block.block().replaceAll(".*delay=(\\d+).*", "$1")))
			.sum();
	}
}
