package com.midicraft.client.compat;

import com.midicraft.client.MidicraftConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Which steps Convert is about to take, asked at the moment it is pressed.
 *
 * <p>Convert is six operations in a trench coat -- bake the speed, merge repeats, quantize, fit
 * the range, snap the tempo, snap the end -- and until now the only way to see which of them it
 * would do was to run it and read the report afterwards. Five of the six have a standalone menu
 * item, so the question "what is this button about to do to my song" was answerable only by
 * knowing the code.</p>
 *
 * <p>Small and dismissible on purpose; see {@link ModalPanelScreen}. Enter or space confirms, and
 * both buttons name their key so none of that has to be discovered. It is not a settings page --
 * the boxes are the steps themselves, in the order they happen, and they persist, so the popup
 * doubles as the place those settings live.</p>
 */
public final class ConvertOptionsScreen extends ModalPanelScreen {
	private static final int PANEL_WIDTH = 226;
	private static final int ROW_HEIGHT = 18;

	private final Runnable onConfirm;
	private final List<Step> steps = new ArrayList<>();

	/** One line of the popup: what it is called, and the setting it reads and writes. */
	private record Step(String label, String hint, BooleanSupplier get, Consumer<Boolean> set) {
	}

	public ConvertOptionsScreen(Screen parent, boolean gameTicks, Runnable onConfirm) {
		super(Component.literal(gameTicks
			? "Convert for Minecraft (game ticks)" : "Convert for Minecraft (repeater ticks)"), parent);
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		MidicraftConfig config = MidicraftConfig.get();
		steps.clear();
		steps.add(new Step("Apply speed to the tempo", "so the result plays at its own pace",
			config::convertBakesSpeed, config::setConvertBakesSpeed));
		steps.add(new Step("Merge repeats", "collapse notes that re-trigger too fast to build",
			config::convertMergesRepeats, config::setConvertMergesRepeats));
		steps.add(new Step("Quantize", "land note starts on a grid the build can place",
			config::convertQuantizes, config::setConvertQuantizes));
		steps.add(new Step("Fit into range", "octave-shift notes no instrument can reach",
			config::convertFitsRange, config::setConvertFitsRange));
		steps.add(new Step("Snap tempo", "move the tempo until the spacing lands on build ticks",
			config::convertSnapsTempo, config::setConvertSnapsTempo));
		steps.add(new Step("Snap end", "land the end marker on the grid too",
			config::convertSnapsEnd, config::setConvertSnapsEnd));

		panel(PANEL_WIDTH, PADDING + TITLE_HEIGHT + steps.size() * ROW_HEIGHT + 8 + BUTTON_HEIGHT
			+ PADDING);

		int y = contentTop();
		for (Step step : steps) {
			addRenderableWidget(Checkbox.builder(Component.literal(step.label()), font)
				.pos(panelLeft + PADDING, y)
				.selected(step.get().getAsBoolean())
				.onValueChange((box, value) -> {
					step.set().accept(value);
					MidicraftConfig.save();
				})
				.build());
			y += ROW_HEIGHT;
		}

		int buttonWidth = (rowWidth() - 6) / 2;
		addRenderableWidget(Button.builder(Component.literal("Convert (Enter)"),
				pressed -> confirm())
			.bounds(panelLeft + PADDING, buttonTop(), buttonWidth, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel (Esc)"),
				pressed -> onClose())
			.bounds(panelLeft + PADDING + buttonWidth + 6, buttonTop(), buttonWidth, BUTTON_HEIGHT)
			.build());
	}

	@Override
	protected void confirm() {
		minecraft.gui.setScreen(parent);
		onConfirm.run();
	}

	@Override
	protected boolean spaceConfirms() {
		return true;
	}

	@Override
	protected void extractPanelContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// The hint for whichever row the pointer is on, under the boxes rather than beside them:
		// a tooltip would cover the row it explains.
		int row = (mouseY - contentTop()) / ROW_HEIGHT;
		if (mouseX >= panelLeft && mouseX <= panelLeft + panelWidth && mouseY >= contentTop()
				&& row < steps.size()) {
			graphics.text(font, steps.get(row).hint(), panelLeft + PADDING, buttonTop() - 11,
				HINT_COLOR, false);
		}
	}
}
