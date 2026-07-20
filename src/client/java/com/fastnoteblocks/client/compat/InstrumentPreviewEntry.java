package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class InstrumentPreviewEntry extends AbstractConfigListEntry<String> {
	private static final int CELL = 22;
	private static final int COLUMNS = 10;
	private final FastNoteblocksConfig config;
	private String selected;
	private int gridX;
	private int gridY;

	InstrumentPreviewEntry(FastNoteblocksConfig config) {
		super(Component.literal("Preview instrument"), false);
		this.config = config;
		this.selected = PreviewInstrument.byId(config.previewInstrument()).id();
	}

	@Override
	public int getItemHeight() {
		return 58;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
		graphics.text(Minecraft.getInstance().font, getFieldName(), x, y, 0xFFFFFFFF);
		gridX = x + Math.max(0, (entryWidth - COLUMNS * CELL) / 2);
		gridY = y + 14;
		for (int i = 0; i < PreviewInstrument.VALUES.size(); i++) {
			PreviewInstrument instrument = PreviewInstrument.VALUES.get(i);
			int cellX = gridX + i % COLUMNS * CELL;
			int cellY = gridY + i / COLUMNS * CELL;
			boolean active = instrument.id().equals(selected);
			boolean cellHovered = mouseX >= cellX && mouseX < cellX + 20 && mouseY >= cellY && mouseY < cellY + 20;
			graphics.fill(cellX, cellY, cellX + 20, cellY + 20, active ? 0xFFFFAA00 : cellHovered ? 0xFF888888 : 0xFF333333);
			graphics.fill(cellX + 1, cellY + 1, cellX + 19, cellY + 19, 0xFF111111);
			graphics.item(new ItemStack(instrument.icon()), cellX + 2, cellY + 2);
			if (cellHovered) {
				graphics.setTooltipForNextFrame(Component.literal(instrument.name() + " — "
					+ new ItemStack(instrument.icon()).getHoverName().getString()), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return false;
		}
		int column = (int)(event.x() - gridX) / CELL;
		int row = (int)(event.y() - gridY) / CELL;
		if (event.x() < gridX || event.y() < gridY || column < 0 || column >= COLUMNS || row < 0 || row >= 2) {
			return false;
		}
		int i = row * COLUMNS + column;
		if (i >= PreviewInstrument.VALUES.size()
				|| event.x() >= gridX + column * CELL + 20 || event.y() >= gridY + row * CELL + 20) {
			return false;
		}
		PreviewInstrument instrument = PreviewInstrument.VALUES.get(i);
		selected = instrument.id();
		config.setPreviewInstrument(selected);
		FastNoteblocksConfig.save();
		instrument.play();
		return true;
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return List.of();
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of();
	}

	@Override
	public String getValue() {
		return selected;
	}

	@Override
	public Optional<String> getDefaultValue() {
		return Optional.of("HARP");
	}

	@Override
	public void save() {
		config.setPreviewInstrument(selected);
	}
}
