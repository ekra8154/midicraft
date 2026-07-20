package com.fastnoteblocks.client.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Style;

final class SequenceEditBox extends MultiLineEditBox {
	private static final int TEXT_COLOR = -2039584;
	private static final int CURSOR_COLOR = -3092272;
	private final Font editorFont;
	private String styledFor;
	private Style[] styleCache;

	SequenceEditBox(Font font, int width, int height) {
		super(font, 0, 0, width, height, CommonComponents.EMPTY, CommonComponents.EMPTY,
			TEXT_COLOR, true, CURSOR_COLOR, true, true);
		this.editorFont = font;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractContents(graphics, mouseX, mouseY, partialTick);
		String value = getValue();
		if (value.isEmpty()) {
			return;
		}
		if (!value.equals(styledFor)) {
			styleCache = SequenceTextStyler.styles(value);
			styledFor = value;
		}

		int y = getInnerTop();
		for (MultilineTextField.StringView line : this.textField.iterateLines()) {
			if (withinContentAreaTopBottom(y, y + editorFont.lineHeight)) {
				graphics.text(editorFont,
					SequenceTextStyler.sequence(value, styleCache, line.beginIndex(), line.endIndex()),
					getInnerLeft(), y, TEXT_COLOR, true);
			}
			y += editorFont.lineHeight;
		}
	}
}
