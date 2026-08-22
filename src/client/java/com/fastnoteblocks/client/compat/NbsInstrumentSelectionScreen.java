package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class NbsInstrumentSelectionScreen extends Screen {
	private final Screen parent;
	private final NbsImporter.Inspection inspection;
	private final Consumer<Set<String>> selection;
	private final Set<String> selected = new LinkedHashSet<>();

	NbsInstrumentSelectionScreen(
		Screen parent,
		NbsImporter.Inspection inspection,
		Consumer<Set<String>> selection
	) {
		super(Component.literal("Choose NBS instruments"));
		this.parent = parent;
		this.inspection = inspection;
		this.selection = selection;
		inspection.instruments().stream()
			.limit(ComposerProject.MAX_LAYERS)
			.forEach(option -> selected.add(option.id()));
	}

	@Override
	protected void init() {
		clearWidgets();
		int columns = width >= 720 ? 2 : 1;
		int buttonWidth = columns == 2 ? 240 : Math.min(360, width - 32);
		int rows = (inspection.instruments().size() + columns - 1) / columns;
		int startY = Math.max(48, height / 2 - rows * 11);
		for (int index = 0; index < inspection.instruments().size(); index++) {
			NbsImporter.InstrumentOption option = inspection.instruments().get(index);
			int column = index % columns;
			int row = index / columns;
			int x = width / 2 - (columns * buttonWidth + (columns - 1) * 6) / 2
				+ column * (buttonWidth + 6);
			int y = startY + row * 22;
			Button button = addRenderableWidget(Button.builder(optionLabel(option), clicked -> toggle(option.id()))
				.bounds(x, y, buttonWidth, 20)
				.tooltip(Tooltip.create(Component.literal(
					option.noteCount() + " notes using " + option.name()
				)))
				.build());
			button.active = selected.contains(option.id()) || selected.size() < ComposerProject.MAX_LAYERS;
		}
		int actionsY = Math.min(height - 28, startY + rows * 22 + 12);
		Button importButton = addRenderableWidget(Button.builder(Component.literal("Import selected"), button -> {
			selection.accept(Set.copyOf(selected));
		}).bounds(width / 2 - 104, actionsY, 100, 20).build());
		importButton.active = !selected.isEmpty() && selected.size() <= ComposerProject.MAX_LAYERS;
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(width / 2 + 4, actionsY, 100, 20)
			.build());
	}

	private Component optionLabel(NbsImporter.InstrumentOption option) {
		return Component.literal((selected.contains(option.id()) ? "[x] " : "[ ] ")
			+ option.name() + " (" + option.noteCount() + ")");
	}

	private void toggle(String id) {
		if (!selected.remove(id) && selected.size() < ComposerProject.MAX_LAYERS) {
			selected.add(id);
		}
		init();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(graphics);
		extractTransparentBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);
		graphics.centeredText(font,
			Component.literal("This song uses more than " + ComposerProject.MAX_LAYERS
				+ " instruments. Select which groups to import ("
				+ selected.size() + "/" + ComposerProject.MAX_LAYERS + ")."),
			width / 2, 32, 0xFFBBBBBB);
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
