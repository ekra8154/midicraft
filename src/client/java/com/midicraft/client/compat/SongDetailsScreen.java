package com.midicraft.client.compat;

import com.midicraft.client.McCompat;
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
 */
final class SongDetailsScreen extends Screen {
	private static final int FIELD_GAP = 34;
	private static final int LABEL_COLOR = 0xFF8A9098;
	private static final int TEXT_COLOR = 0xFFD6D8DD;
	private static final DateTimeFormatter WHEN = DateTimeFormatter
		.ofPattern("MMM d, yyyy HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

	private final Screen parent;
	private final SongFile.Details details;
	private final Consumer<SongFile.Details> accept;
	/** The name Tab fills the author box with, or {@code null}. See MidicraftConfig#suggestedAuthor. */
	private final String authorSuggestion;
	private EditBox author;
	private EditBox credits;
	private EditBox description;

	SongDetailsScreen(Screen parent, String songName, SongFile.Details details,
			String authorSuggestion, Consumer<SongFile.Details> accept) {
		super(Component.literal("Details of \"" + songName + "\""));
		this.parent = parent;
		this.details = details;
		this.authorSuggestion = authorSuggestion == null || authorSuggestion.isBlank() ? null
			: authorSuggestion;
		this.accept = accept;
	}

	@Override
	protected void init() {
		int boxWidth = boxWidth();
		int left = (width - boxWidth) / 2;
		int top = top();
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

		int buttons = top + 3 * FIELD_GAP + facts().size() * 11 + 8;
		addRenderableWidget(Button.builder(Component.literal("Save details"), button -> confirm())
			.bounds(left, buttons, boxWidth / 2 - 3, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + boxWidth / 2 + 3, buttons, boxWidth / 2 - 3, 20).build());
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

	private void confirm() {
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

	private int boxWidth() {
		return Math.min(320, width - 40);
	}

	private int top() {
		return Math.max(36, height / 2 - 90);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int left = (width - boxWidth()) / 2;
		int top = top();
		graphics.text(font, title.getString(), left, top - 26, 0xFFFFFFFF, false);
		String[] labels = {"Author", "Credits", "Description"};
		for (int field = 0; field < labels.length; field++) {
			String label = labels[field] + " (optional)";
			// Said beside the box, since the grey name in it does not say how to take it.
			if (field == 0 && author != null && authorRemainder(author.getValue()) != null) {
				label += " - Tab fills in " + authorSuggestion;
			}
			graphics.text(font, label, left, top + field * FIELD_GAP - 11, LABEL_COLOR, false);
		}
		int y = top + 3 * FIELD_GAP - 4;
		for (String line : facts()) {
			graphics.text(font, line, left, y, TEXT_COLOR, false);
			y += 11;
		}
	}

	@Override
	public void onClose() {
		McCompat.setScreen(parent);
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
