package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Layout and pacing for a command-driven paste, chosen at the moment of pasting.
 *
 * <p>Both are decisions about this build rather than standing preferences -- which layout suits a
 * song depends on where you are standing and how much room is there, and the safe command rate
 * depends on the server. Asking here also puts the resulting footprint next to the choice that
 * determines it, so the trade is visible while it is being made.</p>
 */
final class BuildOptionsScreen extends Screen {
	private final Screen parent;
	private final String songName;
	private final List<FastNoteblocksConfig.SequenceTrack> sequence;
	private final Consumer<SongBuilder.PasteMode> confirm;
	private SongBuilder.PasteMode mode;
	private int commandsPerTick;

	BuildOptionsScreen(
		Screen parent,
		String songName,
		List<FastNoteblocksConfig.SequenceTrack> sequence,
		SongBuilder.PasteMode initialMode,
		Consumer<SongBuilder.PasteMode> confirm
	) {
		super(Component.literal("Paste sequence in world"));
		this.parent = parent;
		this.songName = songName;
		this.sequence = sequence;
		this.confirm = confirm;
		this.mode = initialMode;
		this.commandsPerTick = FastNoteblocksConfig.get().commandsPerTick();
	}

	@Override
	protected void init() {
		clearWidgets();
		int width = Math.min(300, this.width - 40);
		int left = (this.width - width) / 2;
		int top = Math.max(40, height / 2 - 78);

		int y = top + 14;
		for (SongBuilder.PasteMode option : SongBuilder.PasteMode.values()) {
			Button button = addRenderableWidget(Button.builder(
					Component.literal((option == mode ? "> " : "  ") + option.label()),
					clicked -> {
						mode = option;
						init();
					})
				.bounds(left, y, width, 20)
				.tooltip(Tooltip.create(Component.literal(describe(option))))
				.build());
			button.active = option != mode;
			y += 22;
		}

		y += 16;
		addRenderableWidget(Button.builder(Component.literal("-"), clicked -> changeRate(-8))
			.bounds(left, y, 20, 20).build());
		addRenderableWidget(Button.builder(Component.literal("+"), clicked -> changeRate(8))
			.bounds(left + width - 20, y, 20, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Paste"), clicked -> {
			FastNoteblocksConfig.get().setCommandsPerTick(commandsPerTick);
			FastNoteblocksConfig.get().setPasteMode(mode.name());
			FastNoteblocksConfig.save();
			confirm.accept(mode);
		}).bounds(left, y + 34, width / 2 - 3, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, clicked -> onClose())
			.bounds(left + width / 2 + 3, y + 34, width / 2 - 3, 20).build());
	}

	private void changeRate(int delta) {
		commandsPerTick = Math.max(FastNoteblocksConfig.MIN_COMMANDS_PER_TICK,
			Math.min(FastNoteblocksConfig.MAX_COMMANDS_PER_TICK, commandsPerTick + delta));
		init();
	}

	private static String describe(SongBuilder.PasteMode option) {
		return switch (option) {
			case COMPACT_CUBE -> "Folds onto stacked floors joined by a glass redstone riser. "
				+ "Smallest footprint, and the only layout that keeps a long song inside earshot.";
			case COMPACT -> "Folds back and forth on one level. Compact, but a long song still "
				+ "reaches past the 48-block range note blocks can be heard from.";
			case STRAIGHT -> "One straight line. Easiest to read and repair, largest footprint.";
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int width = Math.min(300, this.width - 40);
		int left = (this.width - width) / 2;
		int top = Math.max(40, height / 2 - 78);

		graphics.text(font, "Paste the build sequence of \"" + songName + "\" with /setblock",
			left, top - 14, 0xFFFFFFFF, false);
		graphics.text(font, "Layout", left, top + 2, 0xFF8A9098, false);

		int rateY = top + 14 + SongBuilder.PasteMode.values().length * 22 + 16;
		SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(sequence);
		int commands = blocks.total();
		double seconds = commands / (commandsPerTick * 20.0);
		graphics.text(font, String.format(Locale.ROOT, "%d commands per tick", commandsPerTick),
			left + 26, rateY + 6, 0xFFD6D8DD, false);
		graphics.text(font, String.format(Locale.ROOT,
				"about %d blocks, roughly %.1fs", commands, seconds),
			left, rateY + 24, 0xFF8A9098, false);
		graphics.text(font, "Needs /setblock permission. Overwrites whatever is there.",
			left, rateY + 78, 0xFF8A9098, false);
		if (commandsPerTick > 64) {
			graphics.text(font, "High rates can trip server command spam limits.",
				left, rateY + 90, 0xFFFFAA00, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
