package com.fastnoteblocks.client.mixin;

import com.fastnoteblocks.client.NoteBlockOverlay;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void fastNoteblocks$handleOverlayScroll(long handle, double horizontalAmount, double verticalAmount, CallbackInfo callbackInfo) {
		if (NoteBlockOverlay.INSTANCE.handleScroll(verticalAmount)) {
			callbackInfo.cancel();
		}
	}
}
