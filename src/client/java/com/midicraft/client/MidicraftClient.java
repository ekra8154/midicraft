package com.midicraft.client;

import com.midicraft.client.compat.CommandPasteSender;
import com.midicraft.client.compat.ComposerCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class MidicraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MidicraftConfig.load();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MidicraftConfig.save());
		NoteBlockOverlay.INSTANCE.register();
		CommandPasteSender.register();
		ComposerCommand.register();
	}
}
