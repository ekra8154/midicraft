package com.midicraft.client.compat;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A UI sound whose pitch may extend beyond Minecraft's normal 0.5x-2.0x clamp.
 * SoundEngineMixin grants the exception only to this class.
 */
public final class ExtendedPitchSoundInstance extends SimpleSoundInstance {
	private ExtendedPitchSoundInstance(SoundEvent sound, float pitch, float volume) {
		super(sound.location(), SoundSource.UI, volume, pitch, SoundInstance.createUnseededRandom(),
			false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
	}

	public static ExtendedPitchSoundInstance forUI(SoundEvent sound, float pitch, float volume) {
		return new ExtendedPitchSoundInstance(sound, pitch, volume);
	}
}
