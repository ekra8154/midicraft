package com.midicraft.client.mixin;

import com.midicraft.client.compat.ExtendedPitchSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(method = "calculatePitch", at = @At("HEAD"), cancellable = true)
	private void midicraft$allowComposerPitch(
		SoundInstance instance,
		CallbackInfoReturnable<Float> callbackInfo
	) {
		if (instance instanceof ExtendedPitchSoundInstance) {
			callbackInfo.setReturnValue(Math.max(0.01F, Math.min(64.0F, instance.getPitch())));
		}
	}
}
