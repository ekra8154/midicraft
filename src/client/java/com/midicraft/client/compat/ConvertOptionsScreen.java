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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Which steps Convert is about to take, asked at the moment it is pressed.
 *
 * <p>Convert is six operations in a trench coat -- bake the speed, merge repeats, quantize, fit
 * the range, snap the tempo, snap the end -- and until now the only way to see which of them it
 * would do was to run it and read the report afterwards. Five of the six have a standalone menu
 * item, so the question "what is this button about to do to my song" was answerable only by
 * knowing the code.</p>
 *
 * <p>Small and dismissible on purpose, in the shape the chain-craft popup uses in the crafting
 * mod: escape leaves, enter or space confirms, clicking outside the panel leaves, and both buttons
 * name their key so none of that has to be discovered. It is not a settings page -- the boxes are
 * the steps themselves, in the order they happen, and they persist, so the popup doubles as the
 * place those settings live.</p>
 */
public final class ConvertOptionsScreen extends Screen {
	private static final int PANEL_WIDTH = 226;
	private static final int PADDING = 10;
	private static final int ROW_HEIGHT = 18;
	private static final int BUTTON_HEIGHT = 20;

	private final Screen parent;
	private final boolean gameTicks;
	private final Runnable onConfirm;
	private final List<Step> steps = new ArrayList<>();
	private int panelLeft;
	private int panelTop;
	private int panelHeight;

	/** One line of the popup: what it is called, and the setting it reads and writes. */
	private record Step(String label, String hint, BooleanSupplier get, Consumer<Boolean> set) {
	}

	public ConvertOptionsScreen(Screen parent, boolean gameTicks, Runnable onConfirm) {
		super(Component.literal(gameTicks
			? "Convert for Minecraft (game ticks)" : "Convert for Minecraft (repeater ticks)"));
		this.parent = parent;
		this.gameTicks = gameTicks;
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

		panelHeight = PADDING + 12 + 6 + steps.size() * ROW_HEIGHT + 8 + BUTTON_HEIGHT + PADDING;
		panelLeft = (width - PANEL_WIDTH) / 2;
		panelTop = (height - panelHeight) / 2;

		int y = panelTop + PADDING + 12 + 6;
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

		int buttonWidth = (PANEL_WIDTH - PADDING * 2 - 6) / 2;
		int buttonY = panelTop + panelHeight - PADDING - BUTTON_HEIGHT;
		addRenderableWidget(Button.builder(Component.literal("Convert (Enter)"),
				pressed -> confirm())
			.bounds(panelLeft + PADDING, buttonY, buttonWidth, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel (Esc)"),
				pressed -> onClose())
			.bounds(panelLeft + PADDING + buttonWidth + 6, buttonY, buttonWidth, BUTTON_HEIGHT)
			.build());
	}

	private void confirm() {
		minecraft.gui.setScreen(parent);
		onConfirm.run();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Enter and space both confirm, the pair the buttons advertise -- but only when nothing
		// is focused, or space would confirm the dialogue instead of ticking the box the player
		// had tabbed to.
		boolean confirms = event.key() == GLFW.GLFW_KEY_ENTER
			|| event.key() == GLFW.GLFW_KEY_KP_ENTER
			|| event.key() == GLFW.GLFW_KEY_SPACE;
		if (confirms && getFocused() == null) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Outside the panel is out. Judged against the panel and not against the widgets, so the
		// padding around them is still inside -- clicking the gap beside a checkbox should not
		// throw the dialogue away.
		if (event.x() < panelLeft || event.x() > panelLeft + PANEL_WIDTH
				|| event.y() < panelTop || event.y() > panelTop + panelHeight) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		// The composer behind it, dimmed: this is a question about that song, and hiding it while
		// asking would be hiding the thing being asked about.
		if (parent != null) {
			parent.extractRenderState(graphics, -1, -1, partialTick);
		}
		graphics.fill(0, 0, width, height, 0x90101216);
		graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight,
			0xFF16191F);
		graphics.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + 1, 0xFF3C4450);
		graphics.fill(panelLeft, panelTop + panelHeight - 1, panelLeft + PANEL_WIDTH,
			panelTop + panelHeight, 0xFF3C4450);
		graphics.fill(panelLeft, panelTop, panelLeft + 1, panelTop + panelHeight, 0xFF3C4450);
		graphics.fill(panelLeft + PANEL_WIDTH - 1, panelTop, panelLeft + PANEL_WIDTH,
			panelTop + panelHeight, 0xFF3C4450);
		graphics.text(font, title.getString(), panelLeft + PADDING, panelTop + PADDING,
			0xFFFFFFFF, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// The hint for whichever row the pointer is on, under the boxes rather than beside them:
		// a tooltip would cover the row it explains.
		int row = (mouseY - (panelTop + PADDING + 12 + 6)) / ROW_HEIGHT;
		if (mouseX >= panelLeft && mouseX <= panelLeft + PANEL_WIDTH
				&& row >= 0 && row < steps.size()) {
			graphics.text(font, steps.get(row).hint(), panelLeft + PADDING,
				panelTop + panelHeight - PADDING - BUTTON_HEIGHT - 11, 0xFF8A9098, false);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
