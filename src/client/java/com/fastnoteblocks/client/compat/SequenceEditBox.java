package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NotePitch;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

final class SequenceEditBox extends MultiLineEditBox {
	private static final int TEXT_COLOR = -2039584;
	private static final int CURSOR_COLOR = -3092272;
	private final Font editorFont;
	private String styledFor;
	private Style[] styleCache;
	private int highlightFrom = -1;
	private int highlightTo = -1;

	SequenceEditBox(Font font, int width, int height) {
		super(font, 0, 0, width, height, CommonComponents.EMPTY, CommonComponents.EMPTY,
			TEXT_COLOR, true, CURSOR_COLOR, true, true);
		this.editorFont = font;
	}

	void setPlaybackHighlight(int from, int to) {
		this.highlightFrom = from;
		this.highlightTo = to;
		int lineIndex = 0;
		for (MultilineTextField.StringView line : this.textField.iterateLines()) {
			if (from >= line.beginIndex() && from <= line.endIndex()) {
				double lineTop = lineIndex * editorFont.lineHeight;
				double visibleTop = scrollAmount();
				double visibleBottom = visibleTop + getHeight() - 8;
				if (lineTop < visibleTop) {
					setScrollAmount(lineTop);
				} else if (lineTop + editorFont.lineHeight > visibleBottom) {
					setScrollAmount(lineTop + editorFont.lineHeight - getHeight() + 8);
				}
				break;
			}
			lineIndex++;
		}
	}

	void clearPlaybackHighlight() {
		this.highlightFrom = -1;
		this.highlightTo = -1;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractPlaybackHighlight(graphics);
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
		extractNoteTooltip(graphics, value, mouseX, mouseY);
	}

	private void extractNoteTooltip(GuiGraphicsExtractor graphics, String value, int mouseX, int mouseY) {
		if (!isMouseOver(mouseX, mouseY)) {
			return;
		}
		int tokenStart = 0;
		for (int i = 0; i <= value.length(); i++) {
			if (i != value.length() && value.charAt(i) != ',') {
				continue;
			}
			int from = tokenStart;
			int to = i;
			while (from < to && Character.isWhitespace(value.charAt(from))) {
				from++;
			}
			while (to > from && Character.isWhitespace(value.charAt(to - 1))) {
				to--;
			}
			String token = value.substring(from, to);
			if (!token.endsWith("d") && !token.endsWith("D")) {
				try {
					int note = Integer.parseInt(token);
					if (note >= 0 && note < NotePitch.PITCH_COUNT && tokenUnderMouse(value, from, to, mouseX, mouseY)) {
						graphics.setTooltipForNextFrame(Component.literal(NotePitch.name(note)), mouseX, mouseY);
						return;
					}
				} catch (NumberFormatException ignored) {
				}
			}
			tokenStart = i + 1;
		}
	}

	private boolean tokenUnderMouse(String value, int from, int to, int mouseX, int mouseY) {
		int y = getInnerTop();
		for (MultilineTextField.StringView line : this.textField.iterateLines()) {
			if (from >= line.beginIndex() && to <= line.endIndex()) {
				int screenY = (int)Math.round(y - scrollAmount());
				int left = getInnerLeft() + editorFont.width(value.substring(line.beginIndex(), from));
				int right = getInnerLeft() + editorFont.width(value.substring(line.beginIndex(), to));
				return mouseX >= left && mouseX <= right
					&& mouseY >= screenY - 1 && mouseY <= screenY + editorFont.lineHeight;
			}
			y += editorFont.lineHeight;
		}
		return false;
	}

	private void extractPlaybackHighlight(GuiGraphicsExtractor graphics) {
		String value = getValue();
		if (highlightFrom < 0 || highlightTo <= highlightFrom || highlightFrom >= value.length()) {
			return;
		}
		int from = Math.min(highlightFrom, value.length());
		int to = Math.min(highlightTo, value.length());
		int y = getInnerTop();
		for (MultilineTextField.StringView line : this.textField.iterateLines()) {
			int lineFrom = Math.max(from, line.beginIndex());
			int lineTo = Math.min(to, line.endIndex());
			if (lineFrom < lineTo && withinContentAreaTopBottom(y, y + editorFont.lineHeight)) {
				int left = getInnerLeft() + editorFont.width(value.substring(line.beginIndex(), lineFrom));
				int right = getInnerLeft() + editorFont.width(value.substring(line.beginIndex(), lineTo));
				graphics.fill(left - 1, y - 1, right + 1, y + editorFont.lineHeight, 0x996B5200);
			}
			y += editorFont.lineHeight;
		}
	}
}
