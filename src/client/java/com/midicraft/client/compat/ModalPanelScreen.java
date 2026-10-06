package com.midicraft.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A small panel over the screen that opened it: the shape Convert, Sustained notes and Song info
 * all take.
 *
 * <p>The screen behind stays drawn, dimmed, because each of these is a question about the song on
 * it. Escape leaves, clicking outside the panel leaves, and Enter confirms -- only while nothing is
 * focused, or it would confirm the panel instead of pressing the button the player had tabbed to.
 * Written once here because the three had each been written out in full, and had already started
 * to differ in the details.</p>
 */
abstract class ModalPanelScreen extends Screen {
	protected static final int PADDING = 10;
	protected static final int BUTTON_HEIGHT = 20;
	/** Where the first row goes: under the title and a gap. */
	protected static final int TITLE_HEIGHT = 12 + 6;
	protected static final int TEXT_COLOR = 0xFFFFFFFF;
	protected static final int HINT_COLOR = 0xFF8A9098;

	protected final Screen parent;
	protected int panelLeft;
	protected int panelTop;
	protected int panelWidth;
	protected int panelHeight;

	protected ModalPanelScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Centres a panel of this size. Call from {@code init} before placing anything in it. */
	protected void panel(int width, int height) {
		panelWidth = Math.min(width, this.width - 16);
		panelHeight = height;
		panelLeft = (this.width - panelWidth) / 2;
		panelTop = Math.max(4, (this.height - panelHeight) / 2);
	}

	/** The top of the first row inside the panel. */
	protected int contentTop() {
		return panelTop + PADDING + TITLE_HEIGHT;
	}

	/** The width a full-width row inside the panel gets. */
	protected int rowWidth() {
		return panelWidth - PADDING * 2;
	}

	/** The y of the button row along the bottom of the panel. */
	protected int buttonTop() {
		return panelTop + panelHeight - PADDING - BUTTON_HEIGHT;
	}

	/** What Enter does. Leaving, unless the panel has something to confirm. */
	protected void confirm() {
		onClose();
	}

	/** Whether space confirms as well as Enter, for a panel whose buttons advertise both. */
	protected boolean spaceConfirms() {
		return false;
	}

	/** Anything the panel draws over its widgets: hints, read-only text. */
	protected void extractPanelContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean confirms = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER
			|| spaceConfirms() && event.key() == InputConstants.KEY_SPACE;
		if (confirms && getFocused() == null) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Judged against the panel and not against the widgets, so the padding around them is still
		// inside: clicking the gap beside a button should not throw the panel away.
		if (event.x() < panelLeft || event.x() > panelLeft + panelWidth
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
		int right = panelLeft + panelWidth;
		int bottom = panelTop + panelHeight;
		graphics.fill(panelLeft, panelTop, right, bottom, 0xFF16191F);
		graphics.fill(panelLeft, panelTop, right, panelTop + 1, 0xFF3C4450);
		graphics.fill(panelLeft, bottom - 1, right, bottom, 0xFF3C4450);
		graphics.fill(panelLeft, panelTop, panelLeft + 1, bottom, 0xFF3C4450);
		graphics.fill(right - 1, panelTop, right, bottom, 0xFF3C4450);
		// Cut to the panel, since a song's name can be any length.
		String heading = title.getString();
		if (font.width(heading) > rowWidth()) {
			heading = font.plainSubstrByWidth(heading, rowWidth() - font.width("...")) + "...";
		}
		graphics.text(font, heading, panelLeft + PADDING, panelTop + PADDING, TEXT_COLOR, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		extractPanelContents(graphics, mouseX, mouseY);
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
