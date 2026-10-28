package com.midicraft.client;

import com.midicraft.client.compat.CommandPasteSender;
import com.midicraft.client.compat.ComposerCommand;
import com.midicraft.client.compat.ComposerMusicPause;
import com.midicraft.client.compat.ComposerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class MidicraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MidicraftConfig.load();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MidicraftConfig.save());
		NoteBlockOverlay.INSTANCE.register();
		CommandPasteSender.register();
		ComposerCommand.register();
		ClientTickEvents.END_CLIENT_TICK.register(ComposerScreen::tickBehindPopup);
		ClientTickEvents.END_CLIENT_TICK.register(ComposerMusicPause::tick);
	}
}
