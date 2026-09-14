package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The two numbers a sustaining layer needs: how long a note must last before it strikes again, and
 * how often it does.
 *
 * <p>In the shape of the Convert popup, because it is the same kind of question about the song
 * behind it: small, dismissible, escape leaves and enter confirms. Each row is a button that steps
 * to the next length, since both lists are short and a slider over eight named lengths would be
 * finer than anything it can land on.</p>
 */
public final class SustainSettingsScreen extends Screen {
	private static final int PANEL_WIDTH = 236;
	private static final int PADDING = 10;
	private static final int ROW_HEIGHT = 24;
	private static final int BUTTON_HEIGHT = 20;

	private final Screen parent;
	private final Consumer<Sustain> onConfirm;
	private Sustain chosen;
	private int panelLeft;
	private int panelTop;
	private int panelHeight;

	public SustainSettingsScreen(Screen parent, Sustain start, int layerCount, Consumer<Sustain> onConfirm) {
		super(Component.literal(layerCount > 1
			? "Sustain settings for " + layerCount + " layers" : "Sustain settings"));
		this.parent = parent;
		this.chosen = start == null ? Sustain.DEFAULT : start;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		panelHeight = PADDING + 12 + 6 + 2 * ROW_HEIGHT + 24 + BUTTON_HEIGHT + PADDING;
		panelLeft = (width - PANEL_WIDTH) / 2;
		panelTop = (height - panelHeight) / 2;
		int rowWidth = PANEL_WIDTH - PADDING * 2;
		int y = panelTop + PADDING + 12 + 6;

		addRenderableWidget(Button.builder(afterLabel(), pressed -> {
				chosen = chosen.withAfter(next(SustainLength.AFTER_CHOICES, chosen.after()));
				pressed.setMessage(afterLabel());
			})
			.bounds(panelLeft + PADDING, y, rowWidth, BUTTON_HEIGHT).build());
		y += ROW_HEIGHT;
		addRenderableWidget(Button.builder(everyLabel(), pressed -> {
				chosen = chosen.withEvery(next(SustainLength.EVERY_CHOICES, chosen.every()));
				pressed.setMessage(everyLabel());
			})
			.bounds(panelLeft + PADDING, y, rowWidth, BUTTON_HEIGHT).build());

		int buttonWidth = (rowWidth - 6) / 2;
		int buttonY = panelTop + panelHeight - PADDING - BUTTON_HEIGHT;
		addRenderableWidget(Button.builder(Component.literal("Done (Enter)"), pressed -> confirm())
			.bounds(panelLeft + PADDING, buttonY, buttonWidth, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel (Esc)"), pressed -> onClose())
			.bounds(panelLeft + PADDING + buttonWidth + 6, buttonY, buttonWidth, BUTTON_HEIGHT).build());
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

	/** The settings in a sentence, so the two buttons read as one rule. */
	private String summary() {
		String rate = chosen.every() == SustainLength.FINEST
			? "at the song's finest step"
			: "every " + chosen.every().label.toLowerCase(java.util.Locale.ROOT);
		return "Notes " + chosen.after().label.toLowerCase(java.util.Locale.ROOT)
			+ " or longer strike " + rate + ".";
	}

	private void confirm() {
		minecraft.gui.setScreen(parent);
		onConfirm.accept(chosen);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Enter confirms only when nothing is focused, or it would confirm the dialogue instead of
		// pressing the length button the player had tabbed to.
		boolean confirms = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
		if (confirms && getFocused() == null) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.x() < panelLeft || event.x() > panelLeft + PANEL_WIDTH
				|| event.y() < panelTop || event.y() > panelTop + panelHeight) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		if (parent != null) {
			parent.extractRenderState(graphics, -1, -1, partialTick);
		}
		graphics.fill(0, 0, width, height, 0x90101216);
		graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0xFF16191F);
		graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 1, 0xFF3C4450);
		graphics.fill(panelLeft, panelTop + panelHeight - 1, panelLeft + PANEL_WIDTH,
			panelTop + panelHeight, 0xFF3C4450);
		graphics.fill(panelLeft, panelTop, panelLeft + 1, panelTop + panelHeight, 0xFF3C4450);
		graphics.fill(panelLeft + PANEL_WIDTH - 1, panelTop, panelLeft + PANEL_WIDTH,
			panelTop + panelHeight, 0xFF3C4450);
		graphics.text(font, title.getString(), panelLeft + PADDING, panelTop + PADDING, 0xFFFFFFFF, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, summary(), panelLeft + PADDING,
			panelTop + panelHeight - PADDING - BUTTON_HEIGHT - 14, 0xFF8A9098, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
