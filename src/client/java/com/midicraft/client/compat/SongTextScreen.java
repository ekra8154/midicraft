package com.midicraft.client.compat;

import com.midicraft.NoteSequence;
import com.midicraft.client.MidicraftConfig;
import com.midicraft.client.MidicraftConfig.SequenceTrack;
import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.SongLibrary;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
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
	private final MidicraftConfig config;
	private EditBox nameBox;
	private MultiLineEditBox textBox;
	private Button createButton;
	private String problem = "";

	SongTextScreen(Screen parent, MidicraftConfig config) {
		super(Component.literal("New composition from text"));
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
		nameBox.setValue(nameBox.getValue().isEmpty() ? "Untitled composition" : nameBox.getValue());
		nameBox.setMaxLength(64);
		addRenderableWidget(nameBox);

		// Multi-line, because a sequence's lines are parallel layers and pasting one back in has to
		// keep them apart. A single-line box would silently flatten a chord into an arpeggio.
		textBox = addRenderableWidget(MultiLineEditBox.builder()
			.setX(left)
			.setY(top + 44)
			.setPlaceholder(Component.literal("12, 4d, 7    (one line per layer)"))
			.build(font, boxWidth, 72, Component.literal("Sequence")));
		textBox.setCharacterLimit(32000);
		textBox.setValueListener(value -> refresh());

		createButton = addRenderableWidget(Button.builder(Component.literal("Create composition"), button -> create())
			.bounds(left, top + 124, 130, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Parses once into a composition. The text is not kept.")))
			.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + 136, top + 124, 80, 20).build());
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
			long notes = 0;
			long longestDelay = 0;
			int layers = 0;
			for (String line : value.split("\\R")) {
				if (line.isBlank()) {
					continue;
				}
				layers++;
				List<NoteSequence.Step> steps = NoteSequence.parse(line, 4);
				notes += steps.stream().filter(step -> step.type() == NoteSequence.StepType.NOTE).count();
				longestDelay = Math.max(longestDelay, steps.stream()
					.filter(step -> step.type() == NoteSequence.StepType.REPEATER)
					.mapToLong(NoteSequence.Step::value)
					.sum());
			}
			problem = layers + (layers == 1 ? " layer, " : " layers (parallel), ")
				+ notes + (notes == 1 ? " note, " : " notes, ")
				+ String.format(java.util.Locale.ROOT, "%.1fs", longestDelay / 10.0)
				+ (notes == 0 ? " - a bare repeater chain, which the end marker carries" : "");
			createButton.active = layers > 0;
		} catch (IllegalArgumentException invalid) {
			problem = invalid.getMessage();
			createButton.active = false;
		}
	}

	private void create() {
		SongLibrary library = MidicraftConfig.songs();
		String name = library.uniqueName(nameBox.getValue().isBlank()
			? "Untitled composition"
			: nameBox.getValue().trim());
		ComposerProject song = ComposerProject.fromSequenceText(
			name, textBox.getValue(), config.previewInstrument());
		String id = library.newId(name);
		library.save(id, song);
		config.setActiveSongId(id);
		MidicraftConfig.save();
		minecraft.gui.setScreen(new ComposerScreen(parent, config));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int boxWidth = Math.min(420, width - 32);
		int left = (width - boxWidth) / 2;
		int top = Math.max(48, height / 2 - 60);
		graphics.text(font, "Name", left, top - 12, 0xFF8A9098, false);
		graphics.text(font, "Sequence - pitches 0-24, delays like 4d, one line per parallel layer",
			left, top + 32, 0xFF8A9098, false);
		if (!problem.isEmpty()) {
			graphics.text(font, problem, left, top + 122 - 12,
				createButton != null && createButton.active ? 0xFF5AD46A : 0xFFFF6B6B, false);
		}
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
