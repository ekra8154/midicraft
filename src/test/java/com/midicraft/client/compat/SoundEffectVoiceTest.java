package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The vanilla facts the sound effect palette is built on.
 *
 * <p>Every entry in {@code PreviewInstrument.EFFECTS} names a block by its string id and asserts
 * something about how that block behaves -- that a door needs two halves, that a note block still
 * sounds under a skull, that a dispenser is solid enough to pass power on and a bell is not. None
 * of that is checked by the compiler, and all of it decides what the build lays down. A version
 * that renames a block or changes what conducts should fail here, loudly, rather than in a world.</p>
 */
class SoundEffectVoiceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void everyEffectIsAPlaceableBlockState() {
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			assertNotNull(voice.effect(), voice.id() + " is in EFFECTS but has no effect");
			assertNotEquals(Blocks.AIR, parse(voice.effect().block(), voice.id()).getBlock(), voice.id());
			if (voice.effect().above() != null) {
				assertNotEquals(Blocks.AIR, parse(voice.effect().above(), voice.id() + " cover").getBlock(),
					voice.id() + " cover");
			}
		}
	}

	/**
	 * Nothing is left to be filled in later.
	 *
	 * <p>These strings go into setblock commands as they stand. A leftover format placeholder would
	 * not fail to compile, it would paste a command the server rejects.</p>
	 */
	@Test
	void everyStateIsWrittenOutInFull() {
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			assertFalse(voice.effect().block().contains("%"), voice.id() + " has an unfilled state");
			assertFalse(voice.effect().above() != null && voice.effect().above().contains("%"),
				voice.id() + " cover has an unfilled state");
		}
	}

	/** Both halves or none: a door with only a lower half pops the next time anything updates. */
	@Test
	void doorsCarryBothHalves() {
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			if (!(Block.byItem(voice.icon()) instanceof net.minecraft.world.level.block.DoorBlock)) {
				continue;
			}
			assertNotNull(voice.effect().above(), voice.id() + " is a door with no upper half");
			assertTrue(voice.effect().block().contains("half=lower"), voice.id() + " lower half");
			assertTrue(voice.effect().above().contains("half=upper"), voice.id() + " upper half");
			assertTrue(voice.effect().floor(), voice.id() + " is a door and needs a floor");
		}
	}

	/**
	 * The one that the whole mob head idea rests on.
	 *
	 * <p>A note block only sounds with air over it. The head instruments are the exception, and if
	 * they ever stop being it, six voices in the palette go silent the moment they are built.</p>
	 */
	@Test
	void headNoteBlocksSoundThroughTheirSkull() {
		int heads = 0;
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			if (!voice.effect().blockId().equals("minecraft:note_block")) {
				continue;
			}
			heads++;
			NoteBlockInstrument instrument = instrumentIn(voice.effect().block());
			assertTrue(instrument.worksAboveNoteBlock(),
				voice.id() + " puts " + instrument.getSerializedName() + " over a note block, but that "
					+ "instrument does not work above one");
			assertFalse(instrument.isTunable(),
				voice.id() + " is tunable, so it does not belong in an unpitched palette");
			assertNotNull(voice.effect().above(), voice.id() + " has no skull to wear");
		}
		assertEquals(6, heads, "mob head voices");
	}

	/**
	 * Which effects can stand in a lane and pass power on, and which can only hang off one.
	 *
	 * <p>The copper bulb is the one worth knowing about. It looks like a full solid block and it is
	 * not a redstone conductor, so it sounds where it is put and carries nothing onward -- the same
	 * as a door, for a quite different reason.</p>
	 */
	@Test
	void conductionMatchesTheBlock() {
		Set<String> conducting = new HashSet<>();
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			if (block(voice.effect().blockId()).defaultBlockState()
					.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				conducting.add(voice.id());
			}
		}
		assertEquals(Set.of("FX_DROPPER",
				"FX_HEAD_SKELETON", "FX_HEAD_WITHER_SKELETON", "FX_HEAD_ZOMBIE",
				"FX_HEAD_CREEPER", "FX_HEAD_PIGLIN", "FX_HEAD_ENDER_DRAGON"),
			conducting,
			"the effects solid enough to carry a signal on to the next block");
	}

	@Test
	void everyVoiceHasItsOwnIdAndPitchedMeansPitched() {
		Set<String> ids = new HashSet<>();
		for (PreviewInstrument voice : PreviewInstrument.ALL) {
			assertTrue(ids.add(voice.id()), voice.id() + " is in the palette twice");
			assertEquals(voice, PreviewInstrument.byId(voice.id()), voice.id() + " does not look itself up");
		}
		for (PreviewInstrument voice : PreviewInstrument.VALUES) {
			assertTrue(voice.pitched(), voice.id());
			assertNull(voice.effect(), voice.id());
		}
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			assertFalse(voice.pitched(), voice.id());
		}
	}

	/**
	 * How far each effect carries, which is what the palette tells you.
	 *
	 * <p>The number comes from vanilla's own {@code SoundEvent.getRange} applied to the volume the
	 * block plays at, so only that volume is ours to get wrong -- and getting it wrong is what this
	 * catches. The three that are not 16 are the ones a machine has to be spaced around.</p>
	 */
	@Test
	void everyEffectKnowsHowFarItCarries() {
		Map<String, Integer> ranges = new java.util.TreeMap<>();
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			ranges.put(voice.id(), voice.rangeBlocks());
		}
		assertEquals(Map.ofEntries(
			Map.entry("FX_OAK_DOOR", 16),
			Map.entry("FX_IRON_DOOR", 16),
			Map.entry("FX_OAK_TRAPDOOR", 16),
			Map.entry("FX_IRON_TRAPDOOR", 16),
			Map.entry("FX_COPPER_TRAPDOOR", 16),
			Map.entry("FX_OAK_FENCE_GATE", 16),
			Map.entry("FX_OAK_SHELF", 16),
			Map.entry("FX_COPPER_BULB", 16),
			Map.entry("FX_DROPPER", 16),
			// Half volume, and still 16: below full volume the range does not shrink, only the sound.
			Map.entry("FX_PISTON", 16),
			Map.entry("FX_BELL", 32),
			Map.entry("FX_SCULK_SHRIEKER", 80),
			Map.entry("FX_HEAD_SKELETON", 48),
			Map.entry("FX_HEAD_WITHER_SKELETON", 48),
			Map.entry("FX_HEAD_ZOMBIE", 48),
			Map.entry("FX_HEAD_CREEPER", 48),
			Map.entry("FX_HEAD_PIGLIN", 48),
			Map.entry("FX_HEAD_ENDER_DRAGON", 48)),
			ranges);
		assertEquals("Bell (32 block range)", PreviewInstrument.byId("FX_BELL").label());
	}

	/** Two icons for one sound is one voice. The dropper kept it; the dispenser is gone. */
	@Test
	void thereIsNoSecondVoiceForTheDispenserClick() {
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			assertNotEquals("FX_DISPENSER", voice.id(), "the dispenser is back in the palette");
		}
		assertEquals(SoundEvents.DISPENSER_FAIL,
			PreviewInstrument.byId("FX_DROPPER").sound().value(),
			"the dropper should still be the one that makes that click");
	}

	/**
	 * The item in the palette places exactly the block the build plans.
	 *
	 * <p>What lets the survival sequencer work at all. It hands you {@code icon()} and then waits to
	 * see that block land; a paste writes {@code effect().block()}. If those two ever named different
	 * blocks, a machine built by hand and the same song pasted would not be the same machine, and the
	 * sequencer would sit on a step that had already been done.</p>
	 *
	 * <p>A mob head is the pair read the other way round: its icon is the skull, which is what goes
	 * on top, and the note block underneath is what the build calls the block.</p>
	 */
	@Test
	void theIconPlacesTheBlockTheBuildPlans() {
		int heads = 0;
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			String planned = voice.wearsASkull()
				? voice.effect().above()
				: voice.effect().block();
			int state = planned.indexOf('[');
			String plannedId = state < 0 ? planned : planned.substring(0, state);
			assertEquals(plannedId,
				BuiltInRegistries.BLOCK.getKey(Block.byItem(voice.icon())).toString(),
				voice.id() + ": the item it hands you does not place what the build plans");
			if (voice.wearsASkull()) {
				heads++;
				assertEquals("minecraft:note_block", voice.effect().blockId(),
					voice.id() + " wears a skull but does not stand on a note block");
			}
		}
		assertEquals(6, heads, "voices laid as a note block plus a skull");
	}

	/** An unknown id still means harp, the way it always has. */
	@Test
	void anUnknownIdIsStillHarp() {
		assertEquals("HARP", PreviewInstrument.byId("NO_SUCH_VOICE").id());
	}

	/**
	 * The naming the document reads a voice by.
	 *
	 * <p>{@code ComposerProject.Layer.pitched} decides whether a row means a pitch by looking at the
	 * id and nothing else, so that a saved song can be understood without Minecraft's registries
	 * being loaded. That makes the prefix a contract rather than a convention, and this is where a
	 * new voice that forgot it gets caught -- otherwise it would simply be tuned, quietly, and its
	 * chords would survive a dedupe that should have collapsed them.</p>
	 */
	@Test
	void everyEffectIsNamedSoTheDocumentCanTellWithoutAsking() {
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			assertTrue(voice.id().startsWith(ComposerProject.SOUND_EFFECT_PREFIX),
				voice.id() + " is an effect but is not named like one");
		}
		for (PreviewInstrument voice : PreviewInstrument.VALUES) {
			assertFalse(voice.id().startsWith(ComposerProject.SOUND_EFFECT_PREFIX),
				voice.id() + " is tuned but is named like an effect");
		}
	}

	/** A skull on a note block reads back as the voice that put it there. */
	@Test
	void everyHeadVoiceCanBeReadBackFromItsSkull() {
		int heads = 0;
		for (PreviewInstrument voice : PreviewInstrument.EFFECTS) {
			if (!voice.effect().blockId().equals("minecraft:note_block")) {
				continue;
			}
			heads++;
			assertEquals(voice.id(), PreviewInstrument.headVoice(instrumentIn(voice.effect().block())),
				voice.id() + " does not read back from its own skull");
		}
		assertEquals(6, heads, "mob head voices");
		assertNull(PreviewInstrument.headVoice(NoteBlockInstrument.HARP), "a harp is not a head");
	}

	private static NoteBlockInstrument instrumentIn(String blockState) {
		String marker = "instrument=";
		int start = blockState.indexOf(marker) + marker.length();
		int end = blockState.indexOf(']', start);
		String name = blockState.substring(start, end < 0 ? blockState.length() : end);
		for (NoteBlockInstrument candidate : NoteBlockInstrument.values()) {
			if (candidate.getSerializedName().equals(name)) {
				return candidate;
			}
		}
		throw new AssertionError("no note block instrument named " + name);
	}

	/**
	 * Parsed whole, state and all.
	 *
	 * <p>Checking only the block id would let {@code oak_shelf[facing=north]} through even if shelves
	 * stopped having a facing, and the build writes these strings into setblock commands verbatim.</p>
	 */
	private static BlockState parse(String blockState, String voice) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false).blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new AssertionError(voice + " cannot be placed: " + blockState, unparseable);
		}
	}

	private static Block block(String id) {
		return parse(id, id).getBlock();
	}
}
