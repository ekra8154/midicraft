package com.fastnoteblocks.client.compat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

public record PreviewInstrument(String id, String name, Item icon, Holder<SoundEvent> sound, Effect effect) {
	/** A pitched instrument: a block a note block stands on. */
	public PreviewInstrument(String id, String name, Item icon, Holder<SoundEvent> sound) {
		this(id, name, icon, sound, null);
	}

	/**
	 * What a sound effect is made of.
	 *
	 * <p>A pitched instrument names a block a note block stands on, and the note block is the thing
	 * that sounds. An effect <em>is</em> the sound source: what the build places at the cell where a
	 * note block would have gone is this, and there is no note block over it at all -- except for the
	 * mob heads, which are a note block wearing a skull, and say so by naming one in {@code above}.</p>
	 *
	 * <p>All of it fits the column an ordinary note already uses: the cell itself, the cell under it
	 * where an instrument block would go, and the cell over it that a note block keeps as air. A
	 * door's upper half and a skull go in that air; so does a piston's head, which is why the pistons
	 * here face up rather than out. Nothing an effect needs reaches past that column, so a lane
	 * carrying effects is exactly as wide as one carrying notes.</p>
	 *
	 * <p>Where a facing appears it is cosmetic -- these blocks sound the same whichever way they are
	 * turned -- so it is written in rather than worked out, and every one of them faces the same way
	 * so a wall of them looks laid rather than scattered.</p>
	 *
	 * @param block the source block, as a state the build writes into a setblock verbatim
	 * @param above what has to sit directly over the source -- a door's upper half, a note block's
	 *              skull -- in the cell an ordinary note keeps as air, or null to keep the air
	 * @param floor whether it drops or pops without something sturdy underneath it
	 * @param volume the volume the block itself plays its sound at, read out of the block's own
	 *              code rather than guessed. It is the only input to how far the sound carries that
	 *              is not already in the sound event, and most blocks here use 1. The loud ones are
	 *              worth knowing about: a bell is 2, a note block 3, and a sculk shrieker 5.
	 */
	public record Effect(String block, String above, boolean floor, float volume) {
		/** The block id alone, without the state. Every {@code %s} sits inside the brackets. */
		public String blockId() {
			int state = block.indexOf('[');
			return state < 0 ? block : block.substring(0, state);
		}
	}

	private static final int MIN_CACHED_NOTE = -64;
	private static final float[] CACHED_PITCHES = createPitchCache();
	static final List<PreviewInstrument> VALUES = List.of(
		new PreviewInstrument("HARP", "Harp", Items.GRASS_BLOCK, SoundEvents.NOTE_BLOCK_HARP),
		new PreviewInstrument("BASS", "Bass", Items.OAK_PLANKS, SoundEvents.NOTE_BLOCK_BASS),
		new PreviewInstrument("BASEDRUM", "Bass drum", Items.STONE, SoundEvents.NOTE_BLOCK_BASEDRUM),
		new PreviewInstrument("SNARE", "Snare", Items.SAND, SoundEvents.NOTE_BLOCK_SNARE),
		new PreviewInstrument("HAT", "Hi-hat", Items.GLASS, SoundEvents.NOTE_BLOCK_HAT),
		new PreviewInstrument("GUITAR", "Guitar", Items.WOOL.white(), SoundEvents.NOTE_BLOCK_GUITAR),
		new PreviewInstrument("FLUTE", "Flute", Items.CLAY, SoundEvents.NOTE_BLOCK_FLUTE),
		new PreviewInstrument("BELL", "Bell", Items.GOLD_BLOCK, SoundEvents.NOTE_BLOCK_BELL),
		new PreviewInstrument("CHIME", "Chime", Items.PACKED_ICE, SoundEvents.NOTE_BLOCK_CHIME),
		new PreviewInstrument("XYLOPHONE", "Xylophone", Items.BONE_BLOCK, SoundEvents.NOTE_BLOCK_XYLOPHONE),
		new PreviewInstrument("IRON_XYLOPHONE", "Iron xylophone", Items.IRON_BLOCK, SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE),
		new PreviewInstrument("COW_BELL", "Cow bell", Items.SOUL_SAND, SoundEvents.NOTE_BLOCK_COW_BELL),
		new PreviewInstrument("DIDGERIDOO", "Didgeridoo", Items.PUMPKIN, SoundEvents.NOTE_BLOCK_DIDGERIDOO),
		new PreviewInstrument("BIT", "Bit", Items.EMERALD_BLOCK, SoundEvents.NOTE_BLOCK_BIT),
		new PreviewInstrument("BANJO", "Banjo", Items.HAY_BLOCK, SoundEvents.NOTE_BLOCK_BANJO),
		new PreviewInstrument("PLING", "Pling", Items.GLOWSTONE, SoundEvents.NOTE_BLOCK_PLING),
		// Waxed, all four of them. The four trumpets are one instrument at four ages, so an unwaxed
		// block does not merely weather -- it walks up the list and starts playing the next voice
		// along. A song built with a trumpet line in it was quietly rewriting itself while it stood
		// there, and the age it settles on is whatever the weather got to first. The same reason the
		// sound effects have always been waxed, for once it actually changes the sound.
		new PreviewInstrument("TRUMPET", "Trumpet", Items.COPPER_BLOCK.waxed().unaffected(), SoundEvents.NOTE_BLOCK_TRUMPET),
		new PreviewInstrument("TRUMPET_EXPOSED", "Exposed trumpet", Items.COPPER_BLOCK.waxed().exposed(), SoundEvents.NOTE_BLOCK_TRUMPET_EXPOSED),
		new PreviewInstrument("TRUMPET_WEATHERED", "Weathered trumpet", Items.COPPER_BLOCK.waxed().weathered(), SoundEvents.NOTE_BLOCK_TRUMPET_WEATHERED),
		new PreviewInstrument("TRUMPET_OXIDIZED", "Oxidized trumpet", Items.COPPER_BLOCK.waxed().oxidized(), SoundEvents.NOTE_BLOCK_TRUMPET_OXIDIZED)
	);

	/**
	 * Which voice a skull on a note block means, for reading a built machine back.
	 *
	 * <p>Declared before the list that fills it, so that it exists by the time {@link #head} runs.</p>
	 */
	private static final Map<NoteBlockInstrument, String> HEAD_VOICES =
		new java.util.EnumMap<>(NoteBlockInstrument.class);

	/**
	 * The blocks that make a sound when redstone reaches them, rather than when a note block does.
	 *
	 * <p>None of them are tuned: a door opens at the pitch a door opens at, so a layer using one has
	 * a row only so that you can see where the hits are, and two hits on one tick are one hit.</p>
	 *
	 * <p>Every one of these makes a second sound when the power goes away again -- the door shuts,
	 * the bulb clicks off, the piston pulls back. The build pulses, so that second sound always
	 * comes, a moment behind the first. That is the deal, not a bug to be fixed here.</p>
	 *
	 * <p>The copper here is waxed. Nothing about an effect changes as copper ages, but an unwaxed
	 * bulb would quietly become a different block id than the one that was planned, and the machine
	 * reader looks up what it finds by id.</p>
	 */
	static final List<PreviewInstrument> EFFECTS = List.of(
		effect("FX_OAK_DOOR", "Oak door", Items.OAK_DOOR, SoundEvents.WOODEN_DOOR_OPEN,
			door("minecraft:oak_door")),
		effect("FX_IRON_DOOR", "Iron door", Items.IRON_DOOR, SoundEvents.IRON_DOOR_OPEN,
			door("minecraft:iron_door")),
		effect("FX_OAK_TRAPDOOR", "Oak trapdoor", Items.OAK_TRAPDOOR, SoundEvents.WOODEN_TRAPDOOR_OPEN,
			new Effect("minecraft:oak_trapdoor[facing=north]", null, false, 1.0F)),
		effect("FX_IRON_TRAPDOOR", "Iron trapdoor", Items.IRON_TRAPDOOR, SoundEvents.IRON_TRAPDOOR_OPEN,
			new Effect("minecraft:iron_trapdoor[facing=north]", null, false, 1.0F)),
		effect("FX_COPPER_TRAPDOOR", "Copper trapdoor", Items.COPPER_TRAPDOOR.waxed().unaffected(),
			SoundEvents.COPPER_TRAPDOOR_OPEN,
			new Effect("minecraft:waxed_copper_trapdoor[facing=north]", null, false, 1.0F)),
		effect("FX_OAK_FENCE_GATE", "Oak fence gate", Items.OAK_FENCE_GATE, SoundEvents.FENCE_GATE_OPEN,
			new Effect("minecraft:oak_fence_gate[facing=north]", null, false, 1.0F)),
		effect("FX_OAK_SHELF", "Oak shelf", Items.OAK_SHELF, SoundEvents.SHELF_ACTIVATE,
			new Effect("minecraft:oak_shelf[facing=north]", null, false, 1.0F)),
		effect("FX_BELL", "Bell", Items.BELL, SoundEvents.BELL_BLOCK,
			// Stood on the floor rather than hung on a wall, so it needs nothing beside it and the
			// block it hangs off can be on any side -- which is what a bus gives it.
			new Effect("minecraft:bell[attachment=floor]", null, true, 2.0F)),
		effect("FX_COPPER_BULB", "Copper bulb", Items.COPPER_BULB.waxed().unaffected(),
			SoundEvents.COPPER_BULB_TURN_ON,
			new Effect("minecraft:waxed_copper_bulb", null, false, 1.0F)),
		// No dispenser. An empty dispenser and an empty dropper both play block.dispenser.fail, so
		// the two were one voice wearing two icons. Facing up so that one that somehow ends up
		// filled fires into its own airspace instead of into the machine.
		effect("FX_DROPPER", "Dropper", Items.DROPPER, SoundEvents.DISPENSER_FAIL,
			new Effect("minecraft:dropper[facing=up]", null, false, 1.0F)),
		effect("FX_PISTON", "Piston", Items.PISTON, SoundEvents.PISTON_EXTEND,
			new Effect("minecraft:piston[facing=up]", null, false, 0.5F)),
		effect("FX_SCULK_SHRIEKER", "Sculk shrieker", Items.SCULK_SHRIEKER, SoundEvents.SCULK_SHRIEKER_SHRIEK,
			new Effect("minecraft:sculk_shrieker", null, false, 5.0F)),
		head("FX_HEAD_SKELETON", "Skeleton", Items.SKELETON_SKULL, SoundEvents.NOTE_BLOCK_IMITATE_SKELETON,
			NoteBlockInstrument.SKELETON, "minecraft:skeleton_skull"),
		head("FX_HEAD_WITHER_SKELETON", "Wither skeleton", Items.WITHER_SKELETON_SKULL,
			SoundEvents.NOTE_BLOCK_IMITATE_WITHER_SKELETON, NoteBlockInstrument.WITHER_SKELETON,
			"minecraft:wither_skeleton_skull"),
		head("FX_HEAD_ZOMBIE", "Zombie", Items.ZOMBIE_HEAD, SoundEvents.NOTE_BLOCK_IMITATE_ZOMBIE,
			NoteBlockInstrument.ZOMBIE, "minecraft:zombie_head"),
		head("FX_HEAD_CREEPER", "Creeper", Items.CREEPER_HEAD, SoundEvents.NOTE_BLOCK_IMITATE_CREEPER,
			NoteBlockInstrument.CREEPER, "minecraft:creeper_head"),
		head("FX_HEAD_PIGLIN", "Piglin", Items.PIGLIN_HEAD, SoundEvents.NOTE_BLOCK_IMITATE_PIGLIN,
			NoteBlockInstrument.PIGLIN, "minecraft:piglin_head"),
		head("FX_HEAD_ENDER_DRAGON", "Ender dragon", Items.DRAGON_HEAD, SoundEvents.NOTE_BLOCK_IMITATE_ENDER_DRAGON,
			NoteBlockInstrument.DRAGON, "minecraft:dragon_head")
	);

	/** Both halves, or the door pops the first time anything near it updates. */
	private static Effect door(String block) {
		return new Effect(block + "[facing=north,half=lower]", block + "[facing=north,half=upper]",
			true, 1.0F);
	}

	/**
	 * A note block wearing a skull.
	 *
	 * <p>The instrument is written into the state rather than left to the neighbour update the skull
	 * would cause, so that it does not depend on which of the two setblocks the paste sends first.
	 * A note block with a head over it still sounds -- the head instruments are the one case where
	 * the air a note block usually needs above it is waived.</p>
	 */
	private static PreviewInstrument head(String id, String name, Item icon, Holder<SoundEvent> sound,
			NoteBlockInstrument instrument, String skull) {
		HEAD_VOICES.put(instrument, id);
		return effect(id, name, icon, sound, new Effect(
			"minecraft:note_block[instrument=" + instrument.getSerializedName() + "]", skull, false,
			NOTE_BLOCK_VOLUME));
	}

	/**
	 * The voice a note block wearing this instrument's skull is, or null if it is not a head at all.
	 *
	 * <p>For reading a machine back. Every other instrument is decided by the block a note block
	 * stands on; these are decided by the one it wears, and a reader that only looked down would
	 * call a zombie a harp and say nothing about it.</p>
	 */
	public static String headVoice(NoteBlockInstrument instrument) {
		return HEAD_VOICES.get(instrument);
	}

	/**
	 * Whether this voice is a note block wearing a skull rather than a block that sounds by itself.
	 *
	 * <p>The one sound effect that takes two blocks to make, which is what anybody laying one by hand
	 * has to be told: the note block goes down first and the skull goes on top of it.</p>
	 */
	public boolean wearsASkull() {
		return effect != null && NOTE_BLOCK.equals(effect.blockId());
	}

	private static final String NOTE_BLOCK = "minecraft:note_block";

	private static PreviewInstrument effect(String id, String name, Item icon, SoundEvent sound, Effect effect) {
		return effect(id, name, icon, Holder.direct(sound), effect);
	}

	private static PreviewInstrument effect(String id, String name, Item icon, Holder<SoundEvent> sound,
			Effect effect) {
		return new PreviewInstrument(id, name, icon, sound, effect);
	}

	/** Every voice a layer can name, pitched and unpitched alike. */
	static final List<PreviewInstrument> ALL =
		Stream.concat(VALUES.stream(), EFFECTS.stream()).toList();
	private static final Map<String, PreviewInstrument> BY_ID = ALL.stream()
		.collect(Collectors.toUnmodifiableMap(PreviewInstrument::id, Function.identity()));
	/**
	 * What an unknown instrument sounds like.
	 *
	 * <p>Named rather than an index into the list. It used to be {@code VALUES.get(1)}, which was
	 * harp only because a Mute entry sat in front of it -- taking that entry out would silently have
	 * made every unrecognised instrument a bass.</p>
	 */
	private static final PreviewInstrument FALLBACK = BY_ID.get("HARP");

	/** Whether this is a block somebody is carrying because a note has to stand on it. */
	public static boolean isInstrumentBlock(ItemStack stack) {
		for (PreviewInstrument instrument : ALL) {
			if (stack.is(instrument.icon())) {
				return true;
			}
		}
		return false;
	}

	public static PreviewInstrument byId(String id) {
		return BY_ID.getOrDefault(id, FALLBACK);
	}

	boolean playable() {
		return sound != null;
	}

	/** Whether the note decides anything. An effect sounds the way it sounds. */
	public boolean pitched() {
		return effect == null;
	}

	/**
	 * How far away this can still be heard, in blocks.
	 *
	 * <p>Vanilla's own arithmetic, given the volume the block plays at: quiet sounds all carry 16
	 * blocks and only a loud one reaches further, so a piston at half volume is still a 16 and only
	 * the bell, the heads and the shrieker are worth spacing a machine around.</p>
	 */
	public int rangeBlocks() {
		return (int)sound.value().getRange(effect == null ? NOTE_BLOCK_VOLUME : effect.volume());
	}

	/** What a note block plays at, for the pitched half of the palette. */
	private static final float NOTE_BLOCK_VOLUME = 3.0F;

	/** The name with how far it carries, for a palette cell or a layer row. */
	public String label() {
		return name + " (" + rangeBlocks() + " block range)";
	}

	void play() {
		play(12);
	}

	void play(int note) {
		if (sound == null) {
			return;
		}
		if (effect != null) {
			// 12 is the pitch cache's unity entry, so this is the sound at its own speed.
			note = 12;
		}
		int cachedIndex = note - MIN_CACHED_NOTE;
		float pitch = cachedIndex >= 0 && cachedIndex < CACHED_PITCHES.length
			? CACHED_PITCHES[cachedIndex]
			: pitch(note);
		Minecraft.getInstance().getSoundManager().play(
			pitch >= 0.5F && pitch <= 2.0F
				? SimpleSoundInstance.forUI(sound.value(), pitch, 0.55F)
				: ExtendedPitchSoundInstance.forUI(sound.value(), pitch, 0.55F)
		);
	}

	private static float[] createPitchCache() {
		float[] pitches = new float[192];
		for (int index = 0; index < pitches.length; index++) {
			pitches[index] = pitch(MIN_CACHED_NOTE + index);
		}
		return pitches;
	}

	private static float pitch(int note) {
		return (float)Math.pow(2.0, (note - 12) / 12.0);
	}
}
