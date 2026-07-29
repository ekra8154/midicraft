package com.fastnoteblocks.client.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Save, discard or stay, when leaving a composition would drop edits.
 *
 * <p>Three answers rather than two on purpose. The usual yes/no confirm has no room for "I did not
 * mean to leave at all", and hands Escape to one of the other two -- which is how a stray keypress
 * silently throws away an afternoon.</p>
 */
final class UnsavedChangesScreen extends Screen {
	private final Screen parent;
	private final String songName;
	private final Runnable save;
	private final Runnable discard;

	UnsavedChangesScreen(Screen parent, String songName, Runnable save, Runnable discard) {
		super(Component.literal("Unsaved changes"));
		this.parent = parent;
		this.songName = songName;
		this.save = save;
		this.discard = discard;
	}

	@Override
	protected void init() {
		clearWidgets();
		int buttonWidth = Math.min(150, (width - 40) / 3);
		int top = height / 2 + 4;
		int left = (width - (buttonWidth * 3 + 12)) / 2;
		addRenderableWidget(Button.builder(Component.literal("Save and leave"), button -> save.run())
			.bounds(left, top, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(
				Component.literal("Discard changes").withStyle(net.minecraft.ChatFormatting.RED),
				button -> discard.run())
			.bounds(left + buttonWidth + 6, top, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + buttonWidth * 2 + 12, top, buttonWidth, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, Component.literal("Save changes to \"" + songName + "\"?"),
			width / 2, height / 2 - 30, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal(
				"Editing no longer saves as you go, so anything since your last save is only here."),
			width / 2, height / 2 - 16, 0xFF8A9098);
	}

	/** Escape means "I did not mean to leave", never "throw it away". */
	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
