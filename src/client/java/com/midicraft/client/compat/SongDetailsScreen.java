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
	private EditBox author;
	private EditBox credits;
	private EditBox description;

	SongDetailsScreen(Screen parent, String songName, SongFile.Details details,
			Consumer<SongFile.Details> accept) {
		super(Component.literal("Details of \"" + songName + "\""));
		this.parent = parent;
		this.details = details;
		this.accept = accept;
	}

	@Override
	protected void init() {
		int boxWidth = boxWidth();
		int left = (width - boxWidth) / 2;
		int top = top();
		// Kept across a resize, as the name prompt keeps its text.
		String typedAuthor = author == null ? orEmpty(details.author()) : author.getValue();
		String typedCredits = credits == null ? orEmpty(details.credits()) : credits.getValue();
		String typedDescription = description == null ? orEmpty(details.description())
			: description.getValue();
		clearWidgets();
		author = box(left, top, boxWidth, 64, typedAuthor, "Who arranged it");
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
			graphics.text(font, labels[field] + " (optional)", left, top + field * FIELD_GAP - 11,
				LABEL_COLOR, false);
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
