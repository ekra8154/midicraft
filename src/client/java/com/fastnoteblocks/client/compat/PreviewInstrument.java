package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

record PreviewInstrument(String id, String name, Item icon, Holder<SoundEvent> sound) {
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
		new PreviewInstrument("TRUMPET", "Trumpet", Items.COPPER_BLOCK.weathering().unaffected(), SoundEvents.NOTE_BLOCK_TRUMPET),
		new PreviewInstrument("TRUMPET_EXPOSED", "Exposed trumpet", Items.COPPER_BLOCK.weathering().exposed(), SoundEvents.NOTE_BLOCK_TRUMPET_EXPOSED),
		new PreviewInstrument("TRUMPET_WEATHERED", "Weathered trumpet", Items.COPPER_BLOCK.weathering().weathered(), SoundEvents.NOTE_BLOCK_TRUMPET_WEATHERED),
		new PreviewInstrument("TRUMPET_OXIDIZED", "Oxidized trumpet", Items.COPPER_BLOCK.weathering().oxidized(), SoundEvents.NOTE_BLOCK_TRUMPET_OXIDIZED)
	);

	static PreviewInstrument byId(String id) {
		return VALUES.stream().filter(value -> value.id.equals(id)).findFirst().orElse(VALUES.getFirst());
	}

	void play() {
		play(12);
	}

	void play(int note) {
		float pitch = (float)Math.pow(2.0, (note - 12) / 12.0);
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.value(), pitch, 0.55F));
	}
}
