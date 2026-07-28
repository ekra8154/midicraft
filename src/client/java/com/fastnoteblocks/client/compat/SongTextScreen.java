package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongLibrary;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Turns sequence text into a song, once.
 *
 * <p>The one place text still flows inwards. It parses on the way in and then closes: no field
 * anywhere stays bound to the text, because a composition can say things text cannot and reading
 * text back over one would quietly throw those away.</p>
 *
 * <p>Anything expressible as text is buildable by definition -- whole repeater delays, note-block
 * pitches -- so a song made this way always arrives Minecraft ready.</p>
 */
final class SongTextScreen extends Screen {
	private final Screen parent;
	private final FastNoteblocksConfig config;
	private EditBox nameBox;
	private EditBox textBox;
	private Button createButton;
	private String problem = "";

	SongTextScreen(Screen parent, FastNoteblocksConfig config) {
		super(Component.literal("New song from text"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		clearWidgets();
		int boxWidth = Math.min(420, width - 32);
		int left = (width - boxWidth) / 2;
		int top = Math.max(48, height / 2 - 60);

		nameBox = new EditBox(font, left, top, boxWidth, 20, Component.literal("Name"));
		nameBox.setValue(nameBox.getValue().isEmpty() ? "Untitled song" : nameBox.getValue());
		nameBox.setMaxLength(64);
		addRenderableWidget(nameBox);

		textBox = new EditBox(font, left, top + 44, boxWidth, 20, Component.literal("Sequence"));
		textBox.setMaxLength(32000);
		textBox.setResponder(value -> refresh());
		addRenderableWidget(textBox);

		createButton = addRenderableWidget(Button.builder(Component.literal("Create song"), button -> create())
			.bounds(left, top + 96, 110, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Parses once into a composition. The text is not kept.")))
			.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + 116, top + 96, 80, 20).build());
		setInitialFocus(textBox);
		refresh();
	}

	/** Reports what the text will become, or why it will not parse, on every keystroke. */
	private void refresh() {
		String value = textBox.getValue();
		if (value.isBlank()) {
			problem = "";
			createButton.active = false;
			return;
		}
		try {
			List<NoteSequence.Step> steps = NoteSequence.parse(value, 4);
			long notes = steps.stream().filter(step -> step.type() == NoteSequence.StepType.NOTE).count();
			long delayTicks = steps.stream()
				.filter(step -> step.type() == NoteSequence.StepType.REPEATER)
				.mapToLong(NoteSequence.Step::value)
				.sum();
			problem = notes + (notes == 1 ? " note, " : " notes, ")
				+ delayTicks + " repeater ticks ("
				+ String.format(java.util.Locale.ROOT, "%.1fs", delayTicks / 10.0) + ")"
				+ (notes == 0 ? " - a bare repeater chain, which the end marker now carries" : "");
			createButton.active = true;
		} catch (IllegalArgumentException invalid) {
			problem = invalid.getMessage();
			createButton.active = false;
		}
	}

	private void create() {
		String name = nameBox.getValue().isBlank() ? "Untitled song" : nameBox.getValue().trim();
		ComposerProject song = ComposerProject.fromSequenceTracks(
			name,
			List.of(new SequenceTrack("Track 1", textBox.getValue(), config.previewInstrument(), 0)),
			0,
			FastNoteblocksConfig.DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS
		);
		SongLibrary library = FastNoteblocksConfig.songs();
		String id = library.newId(name);
		library.save(id, song);
		config.setActiveSongId(id);
		FastNoteblocksConfig.save();
		minecraft.gui.setScreen(new ComposerScreen(parent, config));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int boxWidth = Math.min(420, width - 32);
		int left = (width - boxWidth) / 2;
		int top = Math.max(48, height / 2 - 60);
		graphics.text(font, "Name", left, top - 12, 0xFF8A9098, false);
		graphics.text(font, "Sequence - pitches 0-24, delays like 4d", left, top + 32, 0xFF8A9098, false);
		if (!problem.isEmpty()) {
			graphics.text(font, problem, left, top + 70,
				createButton != null && createButton.active ? 0xFF5AD46A : 0xFFFF6B6B, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
