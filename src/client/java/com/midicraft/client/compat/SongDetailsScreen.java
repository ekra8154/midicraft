package com.midicraft.client.compat;

import com.midicraft.client.composer.SongFile;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * What a song's file says about it: who arranged it, whose song it is, and a description, all
 * optional, over the facts the file keeps for itself.
 *
 * <p>The three boxes are the player's. The lines under them are filled in as the song is imported
 * and saved -- where it was read from, when it was first and last saved, and the versions that
 * wrote it -- and are shown, not edited, since a date or a source that could be typed over would
 * stop being worth reading.</p>
 *
 * <p>A panel over the composer like Song info, so the song stays in view and keeps playing while
 * the details are filled in.</p>
 */
final class SongDetailsScreen extends ModalPanelScreen {
	private static final int PANEL_WIDTH = 340;
	private static final int FIELD_GAP = 34;
	/** The label over each box. */
	private static final int LABEL_HEIGHT = 11;
	private static final int LINE_HEIGHT = 11;
	private static final int LABEL_COLOR = 0xFF8A9098;
	private static final int FACT_COLOR = 0xFFD6D8DD;
	private static final DateTimeFormatter WHEN = DateTimeFormatter
		.ofPattern("MMM d, yyyy HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

	private final SongFile.Details details;
	private final Consumer<SongFile.Details> accept;
	/** The name Tab fills the author box with, or {@code null}. See MidicraftConfig#suggestedAuthor. */
	private final String authorSuggestion;
	private EditBox author;
	private EditBox credits;
	private EditBox description;

	SongDetailsScreen(Screen parent, String songName, SongFile.Details details,
			String authorSuggestion, Consumer<SongFile.Details> accept) {
		super(Component.literal("Details of \"" + songName + "\""), parent);
		this.details = details;
		this.authorSuggestion = authorSuggestion == null || authorSuggestion.isBlank() ? null
			: authorSuggestion;
		this.accept = accept;
	}

	@Override
	protected void init() {
		// Three labelled boxes, the facts under them and the buttons along the bottom.
		panel(PANEL_WIDTH, PADDING + TITLE_HEIGHT + LABEL_HEIGHT + 3 * FIELD_GAP
			+ facts().size() * LINE_HEIGHT + 8 + BUTTON_HEIGHT + PADDING);
		int boxWidth = rowWidth();
		int left = panelLeft + PADDING;
		int top = fieldsTop();
		// Kept across a resize, as the name prompt keeps its text.
		// A song that has never been saved is a new one of the player's own, so the box starts with
		// the suggested name in it, plainly visible and removable. A saved song without an author
		// may be somebody else's, so there it is only offered, with Tab.
		String typedAuthor = author != null ? author.getValue()
			: details.author() != null ? details.author()
			: details.created() == null && authorSuggestion != null ? authorSuggestion
			: "";
		String typedCredits = credits == null ? orEmpty(details.credits()) : credits.getValue();
		String typedDescription = description == null ? orEmpty(details.description())
			: description.getValue();
		clearWidgets();
		// No hint where there is a suggestion: the two would be drawn over each other, and the
		// suggestion is the more useful of the two.
		author = box(left, top, boxWidth, 64, typedAuthor,
			authorSuggestion == null ? "Who arranged it" : "");
		author.setResponder(this::suggestAuthor);
		suggestAuthor(typedAuthor);
		credits = box(left, top + FIELD_GAP, boxWidth, 256, typedCredits,
			"The original song, and whose MIDI it was made from");
		description = box(left, top + 2 * FIELD_GAP, boxWidth, 512, typedDescription, "");

		addRenderableWidget(Button.builder(Component.literal("Save details"), button -> confirm())
			.bounds(left, buttonTop(), boxWidth / 2 - 3, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + boxWidth / 2 + 3, buttonTop(), boxWidth / 2 - 3, BUTTON_HEIGHT).build());
		setInitialFocus(author);
	}

	private EditBox box(int left, int y, int boxWidth, int maxLength, String value, String hint) {
		EditBox box = new EditBox(font, left, y, boxWidth, 20, Component.empty());
		box.setMaxLength(maxLength);
		box.setValue(value);
		if (!hint.isEmpty()) {
			box.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		}
		return addRenderableWidget(box);
	}

	/**
	 * Shows the rest of the suggested author after what has been typed, the way a command line
	 * shows the rest of a command: the whole name in an empty box, the remainder after a prefix of
	 * it, and nothing once the typing goes another way.
	 */
	private void suggestAuthor(String typed) {
		author.setSuggestion(authorRemainder(typed));
	}

	private String authorRemainder(String typed) {
		if (authorSuggestion == null || typed.length() >= authorSuggestion.length()
				|| !authorSuggestion.toLowerCase(Locale.ROOT)
					.startsWith(typed.toLowerCase(Locale.ROOT))) {
			return null;
		}
		return authorSuggestion.substring(typed.length());
	}

	/** Tab takes the suggestion, where there is one; otherwise it moves between the boxes. */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB && author != null
				&& author.isFocused() && authorRemainder(author.getValue()) != null) {
			author.setValue(authorSuggestion);
			author.moveCursorToEnd(false);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected void confirm() {
		accept.accept(details.withOwn(author.getValue(), credits.getValue(),
			description.getValue()));
	}

	/** The lines the file keeps for itself, each only where there is something to say. */
	private List<String> facts() {
		List<String> lines = new ArrayList<>();
		if (details.source() != null) {
			lines.add("Imported from  " + details.source());
		}
		if (details.created() != null) {
			lines.add("Created  " + when(details.created()));
		}
		if (details.modified() != null) {
			lines.add("Last saved  " + when(details.modified()));
		}
		if (details.mod() != null || details.minecraft() != null) {
			lines.add("Written by  midicraft " + orEmpty(details.mod())
				+ (details.minecraft() == null ? "" : " on Minecraft " + details.minecraft()));
		}
		if (lines.isEmpty()) {
			lines.add("Not saved yet: the dates are filled in on the first Save.");
		}
		return lines;
	}

	private static String when(String instant) {
		try {
			return WHEN.format(Instant.parse(instant));
		} catch (RuntimeException unparsed) {
			return instant;
		}
	}

	/** The top of the first box, under its label. */
	private int fieldsTop() {
		return contentTop() + LABEL_HEIGHT;
	}

	@Override
	protected void extractPanelContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelLeft + PADDING;
		int top = fieldsTop();
		String[] labels = {"Author", "Credits", "Description"};
		for (int field = 0; field < labels.length; field++) {
			String label = labels[field] + " (optional)";
			// Said beside the box, since the grey name in it does not say how to take it.
			if (field == 0 && author != null && authorRemainder(author.getValue()) != null) {
				label += " - Tab fills in " + authorSuggestion;
			}
			graphics.text(font, label, left, top + field * FIELD_GAP - LABEL_HEIGHT, LABEL_COLOR,
				false);
		}
		int y = top + 3 * FIELD_GAP - 4;
		for (String line : facts()) {
			// Cut to the panel, since the source line is a whole file path.
			if (font.width(line) > rowWidth()) {
				line = font.plainSubstrByWidth(line, rowWidth() - font.width("...")) + "...";
			}
			graphics.text(font, line, left, y, FACT_COLOR, false);
			y += LINE_HEIGHT;
		}
	}

	/** Hands the game its GUI scale back; see {@link NamePromptScreen#removed}. */
	@Override
	public void removed() {
		ComposerScale.screenClosed(this);
		super.removed();
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}
}
