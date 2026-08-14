package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What a paste containing sound effects actually lays down.
 *
 * <p>That any of these build at all is the first assertion: {@code PlacementPlan.verify} refuses a
 * plan whose sound has nothing to set it off, or something that would set it off on the wrong tick,
 * before {@code createPastePlan} ever returns. So a test here that merely finishes has already shown
 * that every effect it placed is reachable by power on its own tick and no other.</p>
 */
class SoundEffectBuildTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Wide and flat, so a short song runs in one straight lane and no chord lands in a corner. */
	private static final SongBuilder.BuildLimits STRAIGHT = new SongBuilder.BuildLimits(4, 200, 1);

	private static final String BASS_DRUM = "minecraft:stone";

	@Test
	void aDoorIsPlacedInBothHalvesOverAFloor() {
		List<String> commands = build(effectSong(8, 6, "FX_OAK_DOOR"));

		assertEquals(6, count(commands, "minecraft:oak_door[facing=north,half=lower]"), "lower halves");
		assertEquals(6, count(commands, "minecraft:oak_door[facing=north,half=upper]"), "upper halves");
		for (BlockPos lower : positionsOf(commands, "minecraft:oak_door[facing=north,half=lower]")) {
			assertTrue(blockAt(commands, lower.above()).startsWith("minecraft:oak_door"),
				"a door at " + lower + " has nothing over it");
			assertTrue(sturdyTopped(blockAt(commands, lower.below())),
				"a door at " + lower + " is standing on " + blockAt(commands, lower.below()));
		}
	}

	/** The head instruments are note blocks, and the skull goes where the air usually is. */
	@Test
	void aHeadVoicePutsItsSkullOverItsNoteBlock() {
		List<String> commands = build(effectSong(8, 4, "FX_HEAD_ZOMBIE"));

		List<BlockPos> heads = positionsOf(commands, "minecraft:note_block[instrument=zombie]");
		assertEquals(4, heads.size(), "note blocks wearing a head");
		for (BlockPos head : heads) {
			assertEquals("minecraft:zombie_head", blockAt(commands, head.above()), "the skull at " + head);
		}
		assertEquals(0, count(commands, "minecraft:note_block[note="), "no tuned note blocks");
	}

	/** Nothing under an effect that does not ask for a floor: a trapdoor hangs where it is put. */
	@Test
	void anEffectThatNeedsNoFloorIsGivenNone() {
		List<String> commands = build(effectSong(8, 5, "FX_OAK_TRAPDOOR"));

		for (BlockPos trapdoor : positionsOf(commands, "minecraft:oak_trapdoor[facing=north]")) {
			assertEquals("", blockAt(commands, trapdoor.below()),
				"a trapdoor at " + trapdoor + " was given a floor it did not need");
		}
	}

	/**
	 * The piston extends upward, into the cell every sound already keeps clear.
	 *
	 * <p>Facing it out sideways would have made a lane one block wider than the planner priced it,
	 * which is the kind of disagreement that silences a whole wall of notes.</p>
	 */
	@Test
	void aPistonExtendsIntoItsOwnAirspace() {
		List<String> commands = build(effectSong(8, 4, "FX_PISTON"));

		List<BlockPos> pistons = positionsOf(commands, "minecraft:piston[facing=up]");
		assertEquals(4, pistons.size(), "pistons");
		for (BlockPos piston : pistons) {
			assertEquals("minecraft:air", blockAt(commands, piston.above()),
				"the piston at " + piston + " has no room to extend");
		}
	}

	/**
	 * A chord of doors is not a small module, however few of them there are.
	 *
	 * <p>The shape sounds its outer two notes by strongly powering the middle one, and a door does
	 * not pass power on. Three doors therefore build as a bus, where each is powered off its own
	 * stone -- and the plan has to have priced a bus, or the walk would be laying a third column the
	 * lane never reserved.</p>
	 */
	@Test
	void aChordOfDoorsBecomesABusRatherThanASmallModule() {
		List<SongBuilder.EventNote> song = chordSong(8, 4, List.of("FX_OAK_DOOR", "FX_OAK_DOOR", "FX_OAK_DOOR"));
		List<String> commands = build(song);

		assertEquals(12, count(commands, "minecraft:oak_door[facing=north,half=lower]"), "every door placed");
		assertTrue(count(commands, "minecraft:redstone_wire") >= 4,
			"a bus runs dust over its stone, and a small module does not");
	}

	/** A dropper can hold the middle, so a chord built on one keeps the tighter shape. */
	@Test
	void aChordLedByADropperStaysASmallModule() {
		List<String> onDropper = build(chordSong(8, 4,
			List.of("FX_DROPPER", "FX_BELL", "FX_BELL")));
		List<String> onBells = build(chordSong(8, 4,
			List.of("FX_BELL", "FX_BELL", "FX_BELL")));

		assertEquals(4, count(onDropper, "minecraft:dropper[facing=up]"), "droppers");
		assertEquals(8, count(onDropper, "minecraft:bell[attachment=floor]"), "bells beside them");
		assertTrue(count(onDropper, "minecraft:redstone_wire") < count(onBells, "minecraft:redstone_wire"),
			"the dropper-led chord should need less dust than the all-bell one");
	}

	/**
	 * A chord that mixes a door with a note block is reordered so the note block holds the middle,
	 * rather than dropping to a bus for want of looking.
	 */
	@Test
	void aNoteBlockInTheChordIsPutInTheMiddleForTheDoorsToLeanOn() {
		List<SongBuilder.EventNote> song = new ArrayList<>();
		song.add(effect(8, 0, "FX_OAK_DOOR"));
		song.add(effect(8, 1, "FX_OAK_DOOR"));
		song.add(new SongBuilder.EventNote(8, 1, 2, 12, BASS_DRUM));
		List<String> commands = build(song);

		List<BlockPos> notes = positionsOf(commands, "minecraft:note_block[note=12]");
		assertEquals(1, notes.size(), "the one tuned note in the chord");
		int doorsBeside = 0;
		for (BlockPos door : positionsOf(commands, "minecraft:oak_door[facing=north,half=lower]")) {
			if (door.distManhattan(notes.getFirst()) == 1) {
				doorsBeside++;
			}
		}
		assertEquals(2, doorsBeside, "both doors should be leaning on the note block");
	}

	/**
	 * Every door and every bell stands on something, in every shape either can land in.
	 *
	 * <p>Worth asking widely rather than once. The floor is laid with {@code support}, which gives
	 * way to anything already planned in that cell -- so a door in a crowded chord could quietly end
	 * up over air where a door on its own is fine. A floating door is not a paste problem, since
	 * setblock will place one; it is a problem the first time anything near it updates, and a
	 * problem for anyone building the machine by hand.</p>
	 */
	@Test
	void nothingThatNeedsAFloorIsEverLeftWithoutOne() {
		List<String> withFloors = List.of("FX_OAK_DOOR", "FX_IRON_DOOR", "FX_BELL");
		for (SongBuilder.PasteMode mode : List.of(SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				SongBuilder.PasteMode.COMPACT_LANE)) {
			for (int size = 1; size <= 12; size++) {
				for (int gap : new int[] {1, 2, 8}) {
					List<String> voices = new ArrayList<>();
					for (int index = 0; index < size; index++) {
						// Harp mixed through on purpose: its instrument block is air, so a build
						// carrying both has planned air in the same layer of cells as these floors.
						voices.add(index % 4 == 3 ? null : withFloors.get(index % withFloors.size()));
					}
					assertEveryFloorIsSolid(build(mixedSong(gap, 4, voices), mode), mode + " " + size + "/" + gap);
				}
			}
		}
	}

	private static void assertEveryFloorIsSolid(List<String> commands, String where) {
		int checked = 0;
		for (String command : commands) {
			String block = blockOf(command);
			if (!block.startsWith("minecraft:oak_door[facing=north,half=lower]")
					&& !block.startsWith("minecraft:iron_door[facing=north,half=lower]")
					&& !block.startsWith("minecraft:bell")) {
				continue;
			}
			checked++;
			BlockPos pos = positionOf(command);
			String under = blockAt(commands, pos.below());
			// The rule vanilla itself applies: a door checks the face under it is sturdy, and so
			// does a bell standing on the floor. "Not air" is not the same question -- dust and a
			// door's own upper half are both not air and neither will hold anything up.
			assertTrue(sturdyTopped(under),
				where + ": " + block + " at " + pos + " stands on '" + under + "'");
		}
		assertTrue(checked > 0, where + ": nothing with a floor was placed at all");
	}

	/** Chords of mixed voices, where a null entry is an ordinary harp note. */
	private static List<SongBuilder.EventNote> mixedSong(int gap, int events, List<String> voices) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		for (int event = 0; event < events; event++) {
			int time = (event + 1) * gap;
			for (int index = 0; index < voices.size(); index++) {
				notes.add(voices.get(index) == null
					? new SongBuilder.EventNote(time, 1, index, 12, "minecraft:air")
					: effect(time, index, voices.get(index)));
			}
		}
		return notes;
	}

	private static List<String> build(List<SongBuilder.EventNote> song, SongBuilder.PasteMode mode) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song, mode, STRAIGHT).commands();
	}

	/**
	 * A lone sound never stands in the cell the repeater faces unless it can pass the signal on.
	 *
	 * <p>The case the earlier tests all missed, because {@code verify} asks whether every sound has
	 * power reaching it and not whether anything downstream still does. A single sculk shrieker
	 * sounded perfectly well and ended the lane there: the cell a repeater faces is also the cell the
	 * next repeater reads, and a shrieker is not a conductor.</p>
	 *
	 * <p>Asked of every voice in the palette rather than of the shrieker, since which blocks conduct
	 * is not a thing to be remembered per entry -- the copper bulb looks like a full solid block and
	 * is not one either.</p>
	 */
	@Test
	void aLoneEffectThatCannotCarryTheSignalIsMovedOffTheCentreLine() {
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			for (SongBuilder.PasteMode mode : List.of(SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					SongBuilder.PasteMode.COMPACT_LANE, SongBuilder.PasteMode.LANE)) {
				List<String> commands = build(effectSong(8, 5, voice.id()), mode);
				int faced = 0;
				for (String command : commands) {
					if (!blockOf(command).startsWith("minecraft:repeater")) {
						continue;
					}
					// One cell along the way the repeater drives, at the repeater's own level.
					BlockPos ahead = positionOf(command).relative(repeaterOutput(blockOf(command)));
					String inFront = blockAt(commands, ahead);
					if (inFront.isEmpty()) {
						continue;
					}
					faced++;
					// Deliberately not "does this conduct": a repeater facing a repeater is a
					// perfectly good chain, and that is what a delay run is made of. The invariant
					// is narrower -- a sound that cannot carry the signal must not be the block the
					// signal has to travel through.
					assertFalse(deadEnds(inFront),
						voice.id() + " in " + mode + ": a repeater at " + positionOf(command)
							+ " faces '" + inFront + "', which sounds and then carries nothing on");
				}
				assertTrue(faced > 0, voice.id() + " in " + mode + ": no repeater faced anything");
			}
		}
	}

	/** A stone where the repeater points, and the shrieker one to the negative z of it. */
	@Test
	void aLoneShriekerGetsAStoneMiddleAndSitsBesideIt() {
		List<String> commands = build(effectSong(8, 5, "FX_SCULK_SHRIEKER"), SongBuilder.PasteMode.LANE);

		List<BlockPos> shriekers = positionsOf(commands, "minecraft:sculk_shrieker");
		assertEquals(5, shriekers.size(), "shriekers");
		for (BlockPos shrieker : shriekers) {
			// Lanes step +z, so the far side is the one that cannot reach into the next lane.
			BlockPos middle = shrieker.relative(Direction.SOUTH);
			assertEquals("minecraft:stone", blockAt(commands, middle),
				"the shrieker at " + shrieker + " should be beside a stone middle");
		}
	}

	/** Which way a repeater sends its signal. Its facing names where it reads from, not where it drives. */
	private static Direction repeaterOutput(String blockState) {
		return facing(blockState).getOpposite();
	}

	private static Direction facing(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState().getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new AssertionError("unparseable: " + blockState, unparseable);
		}
	}

	/** Whether this is a sound effect that the signal cannot get through. */
	private static boolean deadEnds(String blockState) {
		String id = blockState.contains("[")
			? blockState.substring(0, blockState.indexOf('['))
			: blockState;
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			if (!voice.effect().blockId().equals(id)) {
				continue;
			}
			return !conducts(voice.effect().block());
		}
		return false;
	}

	private static boolean conducts(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new AssertionError("unparseable: " + blockState, unparseable);
		}
	}

	/** Songs made only of note blocks reach none of this and are laid exactly as before. */
	@Test
	void aSongWithoutEffectsIsBuiltTheWayItAlwaysWas() {
		List<SongBuilder.EventNote> song = new ArrayList<>();
		for (int event = 0; event < 6; event++) {
			for (int index = 0; index < 4; index++) {
				song.add(new SongBuilder.EventNote(event + 1, 1, index, 12, BASS_DRUM));
			}
		}
		SongBuilder.PastePlan plan =
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				STRAIGHT);

		assertEquals(6, crosses(plan), "every chord of four should still stack");
	}

	private static List<String> build(List<SongBuilder.EventNote> song) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, STRAIGHT).commands();
	}

	/** One effect on its own, every {@code gap} ticks. */
	private static List<SongBuilder.EventNote> effectSong(int gap, int events, String voice) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		for (int event = 0; event < events; event++) {
			notes.add(effect((event + 1) * gap, 0, voice));
		}
		return notes;
	}

	/** The same chord of effects, every {@code gap} ticks. */
	private static List<SongBuilder.EventNote> chordSong(int gap, int events, List<String> voices) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		for (int event = 0; event < events; event++) {
			for (int index = 0; index < voices.size(); index++) {
				notes.add(effect((event + 1) * gap, index, voices.get(index)));
			}
		}
		return notes;
	}

	private static SongBuilder.EventNote effect(int time, int order, String voice) {
		PreviewInstrument instrument = PreviewInstrument.byId(voice);
		return new SongBuilder.EventNote(time, 1, order, 0,
			instrument.effect().blockId(), instrument.effect());
	}

	private static int crosses(SongBuilder.PastePlan plan) {
		// A stacked module is the only shape holding a cross of dust, so counting crosses counts them.
		int found = 0;
		for (BlockPos dust : positionsOf(plan.commands(), "minecraft:redstone_wire[")) {
			if (blockAt(plan.commands(), dust).contains("north=side")
					&& blockAt(plan.commands(), dust).contains("east=side")) {
				found++;
			}
		}
		return found;
	}

	private static int count(List<String> commands, String blockPrefix) {
		int found = 0;
		for (String command : commands) {
			if (blockOf(command).startsWith(blockPrefix)) {
				found++;
			}
		}
		return found;
	}

	private static List<BlockPos> positionsOf(List<String> commands, String blockPrefix) {
		List<BlockPos> found = new ArrayList<>();
		for (String command : commands) {
			if (blockOf(command).startsWith(blockPrefix)) {
				found.add(positionOf(command));
			}
		}
		return found;
	}

	private static String blockAt(List<String> commands, BlockPos pos) {
		for (String command : commands) {
			if (positionOf(command).equals(pos)) {
				return blockOf(command);
			}
		}
		return "";
	}

	/** Whether something standing on this block would stay standing. Empty means nothing is there. */
	private static boolean sturdyTopped(String blockState) {
		if (blockState.isEmpty()) {
			return false;
		}
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState().isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new AssertionError("the build planned an unplaceable block: " + blockState, unparseable);
		}
	}

	/** {@code setblock X Y Z block replace}. */
	private static BlockPos positionOf(String command) {
		String[] parts = command.split(" ");
		return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
			Integer.parseInt(parts[3]));
	}

	private static String blockOf(String command) {
		return command.split(" ")[4];
	}
}
