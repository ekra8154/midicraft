package com.midicraft.client.compat;

import java.util.function.Consumer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Asks for a name and hands it back. Used by Save composition as. */
final class NamePromptScreen extends Screen {
	private final Screen parent;
	private final String prompt;
	private final String initialValue;
	private final String confirmLabel;
	private final Consumer<String> accept;
	private EditBox nameBox;

	NamePromptScreen(
		Screen parent,
		String title,
		String prompt,
		String initialValue,
		String confirmLabel,
		Consumer<String> accept
	) {
		super(Component.literal(title));
		this.parent = parent;
		this.prompt = prompt;
		this.initialValue = initialValue;
		this.confirmLabel = confirmLabel;
		this.accept = accept;
	}

	@Override
	protected void init() {
		clearWidgets();
		int boxWidth = Math.min(300, width - 40);
		int left = (width - boxWidth) / 2;
		int top = height / 2 - 20;

		String current = nameBox == null ? initialValue : nameBox.getValue();
		nameBox = new EditBox(font, left, top, boxWidth, 20, Component.literal("Name"));
		nameBox.setMaxLength(64);
		nameBox.setValue(current);
		addRenderableWidget(nameBox);

		addRenderableWidget(Button.builder(Component.literal(confirmLabel), button -> confirm())
			.bounds(left, top + 30, boxWidth / 2 - 3, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + boxWidth / 2 + 3, top + 30, boxWidth / 2 - 3, 20).build());
		setInitialFocus(nameBox);
	}

	private void confirm() {
		String value = nameBox.getValue().trim();
		if (!value.isEmpty()) {
			accept.accept(value);
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN
				|| event.key() == InputConstants.KEY_NUMPADENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int boxWidth = Math.min(300, width - 40);
		int left = (width - boxWidth) / 2;
		graphics.text(font, prompt, left, height / 2 - 34, 0xFFD6D8DD, false);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/**
	 * Hands the game its GUI scale back the instant this screen goes, whatever it is going to.
	 *
	 * <p>Vanilla calls this from the middle of the screen swap, so it lands before anything is
	 * drawn. If another of our screens is opening it puts the scale straight back in its own init,
	 * and if nothing is, the HUD behind this one is already the right size on the very next frame
	 * rather than a tick later.</p>
	 */
	@Override
	public void removed() {
		ComposerScale.screenClosed(this);
		super.removed();
	}
}
