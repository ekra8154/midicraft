package com.midicraft.client.compat;

import com.midicraft.client.MidicraftConfig;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;


/**
 * {@code /midicraft} -- the door to the Composer that is always there.
 *
 * <p>The key that opens it is unbound by default, on the grounds that a mod helping itself to a
 * letter key is a rude thing to install. That leaves the problem of how anyone finds the Composer
 * at all, and a command answers it better than a keybind can: typing {@code /fast} makes the game
 * list what exists, which is discovery rather than documentation. The one-time notice below is
 * what points a new player at the command in the first place.</p>
 *
 * <p>Deliberately not behind the debug-commands setting. That gate is for a paster that builds
 * test chords; this is the main way in.</p>
 */
public final class ComposerCommand {
	/**
	 * Ticks to wait after joining before saying anything.
	 *
	 * <p>A line sent on the very first tick of a world arrives among the join spam and the
	 * resource-pack chatter, which is where a message goes to be missed.</p>
	 */
	private static final int NOTICE_DELAY_TICKS = 60;

	private static int ticksUntilNotice = -1;

	private ComposerCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
			dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal(
					"midicraft")
				.executes(context -> open(new ComposerScreen(null, MidicraftConfig.get())))
				// Spelled out as well as bare. The name on its own opening the Composer is only
				// obvious once you know it, and someone who has tab-completed as far as
				// "/midicraft " is looking at a list for the thing they came for.
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("composer")
					.executes(context ->
						open(new ComposerScreen(null, MidicraftConfig.get()))))
				.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("settings")
					.executes(context -> open(new SettingsScreen(null))))
				// Everything the mod adds hangs here, so it holds one name in a command list that
				// every other mod is also competing for. The first two carry their own gate; the
				// third is a setting anyone can reach from the Build Pasting tab, so it has none.
				.then(DebugCommands.pasteCommand())
				.then(DebugCommands.asciiDiagramCommand())
				.then(DebugCommands.colorCodePasteCommand())));
	}

	/**
	 * Opens a screen once the command is finished with.
	 *
	 * <p>Not opened inline: a command runs with the chat screen still up, so the screen would go
	 * straight back to chat when closed. By the next tick chat has gone and the parent is the
	 * world, which is where closing it should land you.</p>
	 */
	private static int open(Screen screen) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> minecraft.gui.setScreen(screen));
		return 1;
	}

	/**
	 * Says the mod is here, once, and never again.
	 *
	 * <p>Once ever rather than once per world: the flag is in the config, so a second world is a
	 * second world and not a second introduction. The countdown starts itself the first tick a
	 * player exists, which saves subscribing to a join event to learn the same thing.</p>
	 */
	public static void tick(Minecraft minecraft) {
		if (minecraft.player == null || MidicraftConfig.get().seenWelcome()) {
			return;
		}
		if (ticksUntilNotice < 0) {
			ticksUntilNotice = NOTICE_DELAY_TICKS;
			return;
		}
		if (--ticksUntilNotice > 0) {
			return;
		}
		ticksUntilNotice = -1;
		MidicraftConfig.get().setSeenWelcome(true);
		MidicraftConfig.save();
		minecraft.player.sendSystemMessage(Component.literal("Midicraft: ")
			.withStyle(ChatFormatting.GRAY)
			.append(Component.literal("open composer and settings with "))
			.append(Component.literal("/midicraft")
				.withStyle(style -> style
					.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.SuggestCommand("/midicraft"))))
			.append(Component.literal(", with a keybind, or through Mod Menu.")
				.withStyle(ChatFormatting.GRAY)));
	}
}
