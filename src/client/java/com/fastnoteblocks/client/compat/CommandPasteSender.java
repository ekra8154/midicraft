package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class CommandPasteSender {

	private static final Deque<String> COMMANDS = new ArrayDeque<>();
	private static int total;
	private static int sent;

	private CommandPasteSender() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(CommandPasteSender::tick);
	}

	static boolean isRunning() {
		return !COMMANDS.isEmpty();
	}

	static void start(List<String> commands) {
		cancel(false);
		COMMANDS.addAll(commands);
		total = COMMANDS.size();
		sent = 0;
		show(Component.literal("Placing sequence: 0/" + total));
	}

	static void cancel(boolean notify) {
		if (COMMANDS.isEmpty()) {
			return;
		}
		COMMANDS.clear();
		if (notify) {
			show(Component.literal("Sequence placement cancelled at " + sent + "/" + total));
		}
		total = 0;
		sent = 0;
	}

	private static void tick(Minecraft minecraft) {
		if (COMMANDS.isEmpty()) {
			return;
		}
		if (minecraft.player == null || minecraft.player.connection == null) {
			cancel(true);
			return;
		}
		int perTick = FastNoteblocksConfig.get().commandsPerTick();
		for (int i = 0; i < perTick && !COMMANDS.isEmpty(); i++) {
			minecraft.player.connection.sendCommand(COMMANDS.removeFirst());
			sent++;
		}
		if (COMMANDS.isEmpty()) {
			show(Component.literal("Sequence placement complete: " + sent + "/" + total));
			total = 0;
			sent = 0;
		} else if (sent % 20 == 0) {
			show(Component.literal("Placing sequence: " + sent + "/" + total));
		}
	}

	private static void show(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.gui.hud.setOverlayMessage(message, true);
		}
	}
}
