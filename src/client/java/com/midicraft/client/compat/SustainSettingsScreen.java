package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Sustained notes for a layer: whether its long notes strike again at all, how long a note must last
 * before it does, and how often.
 *
 * <p>In the shape of the Convert popup, because it is the same kind of question about the song
 * behind it; see {@link ModalPanelScreen}. Each row is a button that steps to the next length,
 * since both lists are short and a slider over eight named lengths would be finer than anything it
 * can land on.</p>
 */
public final class SustainSettingsScreen extends ModalPanelScreen {
	private static final int PANEL_WIDTH = 236;
	private static final int ROW_HEIGHT = 24;

	private final Consumer<Sustain> onConfirm;
	private Sustain chosen;

	public SustainSettingsScreen(Screen parent, Sustain start, int layerCount, Consumer<Sustain> onConfirm) {
		super(Component.literal(layerCount > 1
			? "Sustained notes for " + layerCount + " layers" : "Sustained notes"), parent);
		this.chosen = start == null ? Sustain.DEFAULT : start;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		panel(PANEL_WIDTH, PADDING + TITLE_HEIGHT + 3 * ROW_HEIGHT + 24 + BUTTON_HEIGHT + PADDING);
		int y = contentTop();

		addRenderableWidget(Button.builder(onLabel(), pressed -> {
				chosen = chosen.withOn(!chosen.on());
				pressed.setMessage(onLabel());
			})
			.bounds(panelLeft + PADDING, y, rowWidth(), BUTTON_HEIGHT).build());
		y += ROW_HEIGHT;
		addRenderableWidget(Button.builder(afterLabel(), pressed -> {
				chosen = chosen.withAfter(next(SustainLength.AFTER_CHOICES, chosen.after()));
				pressed.setMessage(afterLabel());
			})
			.bounds(panelLeft + PADDING, y, rowWidth(), BUTTON_HEIGHT).build());
		y += ROW_HEIGHT;
		addRenderableWidget(Button.builder(everyLabel(), pressed -> {
				chosen = chosen.withEvery(next(SustainLength.EVERY_CHOICES, chosen.every()));
				pressed.setMessage(everyLabel());
			})
			.bounds(panelLeft + PADDING, y, rowWidth(), BUTTON_HEIGHT).build());

		int buttonWidth = (rowWidth() - 6) / 2;
		addRenderableWidget(Button.builder(Component.literal("Done (Enter)"), pressed -> confirm())
			.bounds(panelLeft + PADDING, buttonTop(), buttonWidth, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel (Esc)"), pressed -> onClose())
			.bounds(panelLeft + PADDING + buttonWidth + 6, buttonTop(), buttonWidth, BUTTON_HEIGHT)
			.build());
	}

	private Component onLabel() {
		return Component.literal("Sustained notes: " + (chosen.on() ? "On" : "Off"));
	}

	private Component afterLabel() {
		return Component.literal("Sustain after: " + chosen.after().label);
	}

	private Component everyLabel() {
		return Component.literal("Strike every: " + chosen.every().label);
	}

	/** The length after {@code current} in {@code choices}, wrapping round to the first. */
	private static SustainLength next(List<SustainLength> choices, SustainLength current) {
		int at = choices.indexOf(current);
		return choices.get((at + 1) % choices.size());
	}

	/** The settings in a sentence, so the buttons read as one rule. */
	private String summary() {
		if (!chosen.on()) {
			return "Off: every note strikes once.";
		}
		String rate = chosen.every() == SustainLength.FINEST
			? "at the song's finest step"
			: "every " + chosen.every().label.toLowerCase(java.util.Locale.ROOT);
		return "Notes " + chosen.after().label.toLowerCase(java.util.Locale.ROOT)
			+ " or longer strike " + rate + ".";
	}

	@Override
	protected void confirm() {
		minecraft.gui.setScreen(parent);
		onConfirm.accept(chosen);
	}

	@Override
	protected void extractPanelContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, summary(), panelLeft + PADDING, buttonTop() - 14, HINT_COLOR, false);
	}
}
