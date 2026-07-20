package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

final class ActiveSequenceEntry extends AbstractConfigListEntry<String> {
	private static final int EDITOR_HEIGHT = 86;
	private final FastNoteblocksConfig config;
	private final String initialValue;
	private SequenceEditBox editor;
	private String value;

	ActiveSequenceEntry(FastNoteblocksConfig config) {
		super(Component.literal("Active sequence"), false);
		this.config = config;
		this.initialValue = config.placementSequence();
		this.value = initialValue;
	}

	void setValue(String value) {
		this.value = value == null ? "" : value;
		if (editor != null) {
			editor.setValue(this.value);
		}
	}

	private SequenceEditBox editor(int width) {
		if (editor == null || editor.getWidth() != width) {
			String current = editor == null ? value : editor.getValue();
			editor = new SequenceEditBox(Minecraft.getInstance().font, width, EDITOR_HEIGHT);
			editor.setCharacterLimit(12000);
			editor.setValue(current);
			value = current;
		}
		return editor;
	}

	@Override
	public int getItemHeight() {
		return EDITOR_HEIGHT + 18;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
		boolean valid = FastNoteblocksConfig.validatePlacementSequence(getValue()).isEmpty();
		graphics.text(Minecraft.getInstance().font, getFieldName(), x, y, valid ? 0xFFFFFFFF : 0xFFFF5555);
		SequenceEditBox box = editor(entryWidth);
		box.setX(x);
		box.setY(y + 14);
		box.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (!valid && mouseX >= x && mouseX <= x + entryWidth && mouseY >= y && mouseY < y + 12) {
			graphics.setTooltipForNextFrame(Component.translatable("error.fast-noteblocks.sequence"), mouseX, mouseY);
		}
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return editor == null ? List.of() : List.of(editor);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return editor == null ? List.of() : List.of(editor);
	}

	@Override
	public String getValue() {
		return editor == null ? value : editor.getValue();
	}

	@Override
	public Optional<String> getDefaultValue() {
		return Optional.of("");
	}

	@Override
	public boolean isEdited() {
		return !getValue().equals(initialValue);
	}

	@Override
	public Optional<Component> getError() {
		return FastNoteblocksConfig.validatePlacementSequence(getValue());
	}

	@Override
	public void save() {
		config.setPlacementSequence(getValue());
	}
}
