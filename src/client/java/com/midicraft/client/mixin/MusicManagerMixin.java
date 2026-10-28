package com.midicraft.client.mixin;

import com.midicraft.client.compat.ComposerMusicPause;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	/**
	 * Holds the music manager still while the composer has the music paused.
	 *
	 * <p>A tick would fade the paused track, or replace it when the situation's music changes, and
	 * the replacement would start playing over the song.</p>
	 */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void midicraft$holdForComposer(CallbackInfo callbackInfo) {
		if (ComposerMusicPause.holding()) {
			callbackInfo.cancel();
		}
	}
}
