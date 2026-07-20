package com.fastnoteblocks.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class FastNoteblocksClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FastNoteblocksConfig.load();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FastNoteblocksConfig.save());
		NoteBlockOverlay.INSTANCE.register();
	}
}
