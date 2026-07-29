package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Asks for two corners and reads whatever note block machine stands between them.
 *
 * <p>Coordinates rather than a selection wand on purpose: the machines worth reading are other
 * people's, often somewhere you are looking at from a distance, and the corners are usually already
 * written down somewhere -- in a WorldEdit selection, in a forum post, on a sign.</p>
 */
final class RegionScanScreen extends Screen {
	/**
	 * The largest selection worth walking.
	 *
	 * <p>Every position in the box is looked at once to find the note blocks, so the cost is the
	 * volume and not the size of the machine. Eight million is a two-hundred-block cube, comfortably
	 * larger than any note block build and small enough to finish while the menu is still open.</p>
	 */
	private static final long MAX_VOLUME = 8_000_000L;

	private final Screen parent;
	private final BiConsumer<BlockPos, BlockPos> scan;
	private final EditBox[] first = new EditBox[3];
	private final EditBox[] second = new EditBox[3];
	private Button scanButton;
	private List<String> status = List.of();

	RegionScanScreen(Screen parent, BiConsumer<BlockPos, BlockPos> scan) {
		super(Component.literal("Scan note blocks from the world"));
		this.parent = parent;
		this.scan = scan;
	}

	@Override
	protected void init() {
		String[] carried = new String[6];
		for (int axis = 0; axis < 3; axis++) {
			carried[axis] = first[axis] == null ? "" : first[axis].getValue();
			carried[axis + 3] = second[axis] == null ? "" : second[axis].getValue();
		}
		clearWidgets();

		int panel = Math.min(320, width - 40);
		int left = (width - panel) / 2;
		int top = height / 2 - 60;
		int cell = (panel - 2 * 6) / 3;

		for (int axis = 0; axis < 3; axis++) {
			first[axis] = coordinateBox(left + axis * (cell + 6), top, cell, carried[axis]);
			second[axis] = coordinateBox(left + axis * (cell + 6), top + 44, cell, carried[axis + 3]);
		}
		// Both corners default to where you are standing, so the commonest case -- stand at one
		// corner, read the other off F3 -- is two edits rather than six.
		if (minecraft.player != null && carried[0].isEmpty()) {
			fill(first, minecraft.player.blockPosition());
			fill(second, minecraft.player.blockPosition());
		}

		addRenderableWidget(Button.builder(Component.literal("Here"),
			button -> setCorner(first)).bounds(left + panel - 44, top - 15, 44, 14).build());
		addRenderableWidget(Button.builder(Component.literal("Here"),
			button -> setCorner(second)).bounds(left + panel - 44, top + 29, 44, 14).build());

		scanButton = addRenderableWidget(Button.builder(Component.literal("Scan"),
			button -> confirm()).bounds(left, top + 100, panel / 2 - 3, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + panel / 2 + 3, top + 100, panel / 2 - 3, 20).build());
		setInitialFocus(first[0]);
		refresh();
	}

	private EditBox coordinateBox(int x, int y, int cellWidth, String value) {
		EditBox box = new EditBox(font, x, y, cellWidth, 18, Component.literal("0"));
		box.setMaxLength(8);
		box.setValue(value);
		box.setResponder(ignored -> refresh());
		return addRenderableWidget(box);
	}

	private void setCorner(EditBox[] corner) {
		if (minecraft.player != null) {
			fill(corner, minecraft.player.blockPosition());
			refresh();
		}
	}

	private static void fill(EditBox[] corner, BlockPos position) {
		corner[0].setValue(Integer.toString(position.getX()));
		corner[1].setValue(Integer.toString(position.getY()));
		corner[2].setValue(Integer.toString(position.getZ()));
	}

	private BlockPos corner(EditBox[] boxes) {
		try {
			return new BlockPos(Integer.parseInt(boxes[0].getValue()),
				Integer.parseInt(boxes[1].getValue()), Integer.parseInt(boxes[2].getValue()));
		} catch (NumberFormatException incomplete) {
			return null;
		}
	}

	/**
	 * Says what the selection is and what is wrong with it, before anything is read.
	 *
	 * <p>The chunk check is the one that matters. A client only holds the world near the player, so
	 * a region reaching past that reads as air -- and air is indistinguishable from a machine that
	 * simply stops, which would quietly produce a song missing its second half.</p>
	 */
	private void refresh() {
		BlockPos from = corner(first);
		BlockPos to = corner(second);
		List<String> lines = new ArrayList<>();
		boolean ready = from != null && to != null && minecraft.level != null;
		if (from == null || to == null) {
			lines.add("Both corners need three whole numbers.");
		} else {
			long volume = NoteMachineReader.volume(from, to);
			lines.add(String.format(Locale.ROOT, "%d x %d x %d, %,d blocks",
				Math.abs(from.getX() - to.getX()) + 1, Math.abs(from.getY() - to.getY()) + 1,
				Math.abs(from.getZ() - to.getZ()) + 1, volume));
			if (volume > MAX_VOLUME) {
				lines.add("Too big to walk. Narrow it to about 200 blocks a side.");
				ready = false;
			}
			if (minecraft.level == null) {
				lines.add("Not in a world.");
			} else {
				int missing = unloadedChunks(from, to);
				if (missing > 0) {
					lines.add(missing + " chunks are not loaded and would read as empty.");
					lines.add("Move closer, or raise render distance, so the whole build is loaded.");
					ready = false;
				}
			}
		}
		status = List.copyOf(lines);
		if (scanButton != null) {
			scanButton.active = ready;
		}
	}

	private int unloadedChunks(BlockPos from, BlockPos to) {
		int minX = SectionPos.blockToSectionCoord(Math.min(from.getX(), to.getX()));
		int maxX = SectionPos.blockToSectionCoord(Math.max(from.getX(), to.getX()));
		int minZ = SectionPos.blockToSectionCoord(Math.min(from.getZ(), to.getZ()));
		int maxZ = SectionPos.blockToSectionCoord(Math.max(from.getZ(), to.getZ()));
		int missing = 0;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!minecraft.level.getChunkSource().hasChunk(x, z)) {
					missing++;
				}
			}
		}
		return missing;
	}

	private void confirm() {
		BlockPos from = corner(first);
		BlockPos to = corner(second);
		if (from != null && to != null && scanButton.active) {
			scan.accept(from, to);
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int panel = Math.min(320, width - 40);
		int left = (width - panel) / 2;
		int top = height / 2 - 60;
		graphics.text(font, Component.literal(title.getString()), left, top - 44, 0xFFFFFFFF, false);
		graphics.text(font, Component.literal("First corner  (x  y  z)"), left, top - 14,
			0xFFB9BEC6, false);
		graphics.text(font, Component.literal("Second corner"), left, top + 30, 0xFFB9BEC6, false);
		int line = top + 68;
		for (String text : status) {
			graphics.text(font, Component.literal(text), left, line, 0xFF9198A2, false);
			line += 11;
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
