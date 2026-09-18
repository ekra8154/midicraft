package com.midicraft.client.compat;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The numbers about a song that are worth looking up and not worth having in view all the time.
 *
 * <p>These used to ride along on the status bar, which is read at a glance for one thing: will this
 * build, and if not, why. Note and block counts, the tempo, the tick grid and how the notes fall
 * across the game tick are all true and all occasionally wanted, and every one of them was pushing
 * that answer off the edge of a narrow window. Read-only: every number here is changed somewhere
 * else.</p>
 */
public final class SongInfoScreen extends ModalPanelScreen {
	/** Wide enough for a paired receipt line, "Not built: muted or hidden | out of range". */
	private static final int PANEL_WIDTH = 340;
	private static final int LINE_HEIGHT = 11;
	private static final int HEADING_GAP = 4;
	private static final int SPACER_HEIGHT = 5;
	private static final int HEADING_COLOR = 0xFF8FD3FF;
	private static final int RULE_COLOR = 0xFF3C4450;

	/** How a line is drawn. */
	enum Kind {
		HEADING,
		ROW,
		/** A step on the way to a total, indented under the row it adjusts. */
		ADJUSTMENT,
		/** The sum of the rows above it, under a rule. */
		TOTAL,
		/** A gap and nothing else, to set a group of rows apart from the ones above it. */
		SPACER
	}

	/** A line of the panel: a label and its value, or a heading with no value. */
	record Line(Kind kind, String label, String value) {
		static Line heading(String text) {
			return new Line(Kind.HEADING, text, null);
		}

		static Line of(String label, String value) {
			return new Line(Kind.ROW, label, value);
		}

		static Line adjustment(String label, String value) {
			return new Line(Kind.ADJUSTMENT, label, value);
		}

		static Line total(String label, String value) {
			return new Line(Kind.TOTAL, label, value);
		}

		static Line spacer() {
			return new Line(Kind.SPACER, "", "");
		}

		boolean isHeading() {
			return kind == Kind.HEADING;
		}
	}

	private final List<Line> lines;

	public SongInfoScreen(Screen parent, String songName, List<Line> lines) {
		super(Component.literal(songName == null || songName.isBlank()
			? "Song info" : "Song info: " + songName), parent);
		this.lines = List.copyOf(lines);
	}

	@Override
	protected void init() {
		panel(PANEL_WIDTH, PADDING + TITLE_HEIGHT + contentHeight() + 8 + BUTTON_HEIGHT + PADDING);
		addRenderableWidget(Button.builder(Component.literal("Close (Esc)"), pressed -> onClose())
			.bounds(panelLeft + PADDING, buttonTop(), rowWidth(), BUTTON_HEIGHT).build());
	}

	private int contentHeight() {
		int height = 0;
		for (int index = 0; index < lines.size(); index++) {
			Line line = lines.get(index);
			if (line.kind() == Kind.SPACER) {
				height += SPACER_HEIGHT;
				continue;
			}
			if (line.isHeading() && index > 0) {
				height += HEADING_GAP;
			}
			height += LINE_HEIGHT;
		}
		return height;
	}

	@Override
	protected void extractPanelContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelLeft + PADDING;
		int right = panelLeft + panelWidth - PADDING;
		int y = contentTop();
		for (int index = 0; index < lines.size(); index++) {
			Line line = lines.get(index);
			if (line.kind() == Kind.SPACER) {
				y += SPACER_HEIGHT;
				continue;
			}
			switch (line.kind()) {
				case HEADING -> {
					if (index > 0) {
						y += HEADING_GAP;
					}
					graphics.text(font, line.label(), left, y, HEADING_COLOR, false);
				}
				case ROW, ADJUSTMENT -> {
					graphics.text(font, line.label(), left + (line.kind() == Kind.ROW ? 6 : 14), y,
						HINT_COLOR, false);
					graphics.text(font, line.value(), right - font.width(line.value()), y,
						TEXT_COLOR, false);
				}
				case SPACER -> {
				}
				case TOTAL -> {
					graphics.fill(left + 6, y - 2, right, y - 1, RULE_COLOR);
					graphics.text(font, line.label(), left + 6, y, HINT_COLOR, false);
					graphics.text(font, line.value(), right - font.width(line.value()), y,
						TEXT_COLOR, false);
				}
			}
			y += LINE_HEIGHT;
		}
	}
}
