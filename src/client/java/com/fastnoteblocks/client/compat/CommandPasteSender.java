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
	/**
	 * Said once the last block is down, rather than before the first.
	 *
	 * <p>A debug paste is a few hundred {@code /setblock} calls and every one of them prints itself
	 * to chat, so a report sent before the paste has scrolled out of reach by the time the build
	 * exists. What the report says is only worth reading against the thing it describes.</p>
	 */
	private static Runnable whenDone;

	private CommandPasteSender() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(CommandPasteSender::tick);
	}

	static boolean isRunning() {
		return !COMMANDS.isEmpty();
	}

	static void start(List<String> commands) {
		start(commands, List.of());
	}

	/**
	 * @param faults places the finished machine is known to be wrong at. Shown instead of the
	 *     progress line, and kept on screen, because a build worth looking at is one you have to be
	 *     told to look at -- it goes up, it looks right, and it plays one note in the wrong bar.
	 */
	static void start(List<String> commands, List<String> faults) {
		start(commands, faults, null);
	}

	/**
	 * @param done run once the last command has been sent, or at once if there are none. Not run if
	 *     the paste is cancelled: it describes a finished machine, and a cancelled one is not that.
	 */
	static void start(List<String> commands, List<String> faults, Runnable done) {
		cancel(false);
		COMMANDS.addAll(commands);
		total = COMMANDS.size();
		sent = 0;
		whenDone = done;
		if (COMMANDS.isEmpty()) {
			finish();
			return;
		}
		if (faults.isEmpty()) {
			show(Component.literal("Placing sequence: 0/" + total));
			return;
		}
		show(Component.literal(faults.size() + " note" + (faults.size() == 1 ? "" : "s")
				+ " will be wrong. First: " + faults.get(0))
			.withStyle(net.minecraft.ChatFormatting.YELLOW));
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
		whenDone = null;
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
			finish();
		} else if (sent % 20 == 0) {
			show(Component.literal("Placing sequence: " + sent + "/" + total));
		}
	}

	/** Cleared before the callback runs, so that a report which pastes again is not fighting it. */
	private static void finish() {
		total = 0;
		sent = 0;
		Runnable done = whenDone;
		whenDone = null;
		if (done != null) {
			done.run();
		}
	}

	private static void show(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.gui.hud.setOverlayMessage(message, true);
		}
	}
}
