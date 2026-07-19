package com.fastnoteblocks.client;

import net.fabricmc.api.ClientModInitializer;

public final class FastNoteblocksClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FastNoteblocksConfig.load();
		NoteBlockOverlay.INSTANCE.register();
	}
}
