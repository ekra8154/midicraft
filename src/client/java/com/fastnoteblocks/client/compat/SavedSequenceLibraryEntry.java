package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.SavedSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

final class SavedSequenceLibraryEntry extends AbstractConfigListEntry<List<SavedSequence>> {
	private static final int EXPANDED_EDITOR_HEIGHT = 60;
	private final FastNoteblocksConfig config;
	private final ActiveSequenceEntry activeSequence;
	private final List<SavedRow> rows = new ArrayList<>();
	private final List<SavedSequence> initialValue;
	private final Button addButton;

	SavedSequenceLibraryEntry(FastNoteblocksConfig config, ActiveSequenceEntry activeSequence) {
		super(Component.literal("Saved sequences"), false);
		this.config = config;
		this.activeSequence = activeSequence;
		this.initialValue = config.savedSequences();
		for (SavedSequence saved : initialValue) {
			rows.add(new SavedRow(saved));
		}
		this.addButton = Button.builder(Component.literal("+ Add sequence"), button ->
			rows.add(new SavedRow(new SavedSequence("Untitled sequence", activeSequence.getValue()))))
			.bounds(0, 0, 150, 20)
			.tooltip(Tooltip.create(Component.literal("Save a new editable copy of the active sequence")))
			.build();
	}

	@Override
	public int getItemHeight() {
		int height = 18 + 24;
		for (SavedRow row : rows) {
			height += row.height() + 4;
		}
		return height;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
		graphics.text(Minecraft.getInstance().font, getFieldName(), x, y, 0xFFFFFFFF);
		int rowY = y + 16;
		for (SavedRow row : rows) {
			row.extract(graphics, x, rowY, entryWidth, mouseX, mouseY, partialTick);
			rowY += row.height() + 4;
		}
		addButton.setX(x + (entryWidth - addButton.getWidth()) / 2);
		addButton.setY(rowY);
		addButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private List<AbstractWidget> widgets() {
		List<AbstractWidget> widgets = new ArrayList<>();
		for (SavedRow row : rows) {
			widgets.addAll(row.widgets());
		}
		widgets.add(addButton);
		return widgets;
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return widgets();
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return widgets();
	}

	@Override
	public List<SavedSequence> getValue() {
		return rows.stream().map(SavedRow::value).toList();
	}

	@Override
	public Optional<List<SavedSequence>> getDefaultValue() {
		return Optional.of(List.of());
	}

	@Override
	public boolean isEdited() {
		return !getValue().equals(initialValue);
	}

	@Override
	public void save() {
		config.setSavedSequences(getValue());
	}

	private final class SavedRow {
		private final EditBox nameBox;
		private final EditBox singleBox;
		private SequenceEditBox multiBox;
		private String multiValue;
		private boolean expanded;
		private final Button loadButton;
		private final Button expandButton;
		private final Button deleteButton;

		SavedRow(SavedSequence saved) {
			this.multiValue = saved.sequence();
			this.expanded = saved.sequence().contains("\n");
			this.nameBox = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 20, Component.literal("Sequence name"));
			nameBox.setMaxLength(80);
			nameBox.setValue(saved.name());
			this.singleBox = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 20, Component.literal("Sequence"));
			singleBox.setMaxLength(12000);
			singleBox.setValue(singleLine(saved.sequence()));
			singleBox.addFormatter(this::formatCollapsed);
			this.loadButton = Button.builder(Component.literal("Load"), button -> activeSequence.setValue(sequence()))
				.bounds(0, 0, 42, 20)
				.tooltip(Tooltip.create(Component.literal("Restore this into the active sequence editor")))
				.build();
			this.expandButton = Button.builder(expandLabel(), button -> toggleExpanded())
				.bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Expand or collapse this sequence")))
				.build();
			this.deleteButton = Button.builder(Component.literal("×").withStyle(ChatFormatting.RED), button -> rows.remove(this))
				.bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Delete this saved sequence")))
				.build();
		}

		int height() {
			return expanded ? 84 : 20;
		}

		private Component expandLabel() {
			return Component.literal(expanded ? "▾" : "▸");
		}

		private void toggleExpanded() {
			if (expanded) {
				multiValue = multiBox == null ? multiValue : multiBox.getValue();
				singleBox.setValue(singleLine(multiValue));
			} else {
				multiValue = singleBox.getValue();
				if (multiBox != null) {
					multiBox.setValue(multiValue);
				}
			}
			expanded = !expanded;
			expandButton.setMessage(expandLabel());
		}

		private SequenceEditBox multiBox(int width) {
			if (multiBox == null || multiBox.getWidth() != width) {
				String current = multiBox == null ? multiValue : multiBox.getValue();
				multiBox = new SequenceEditBox(Minecraft.getInstance().font, width, EXPANDED_EDITOR_HEIGHT);
				multiBox.setCharacterLimit(12000);
				multiBox.setValue(current);
				multiValue = current;
			}
			return multiBox;
		}

		private void extract(GuiGraphicsExtractor graphics, int x, int y, int width,
				int mouseX, int mouseY, float partialTick) {
			loadButton.setX(x);
			loadButton.setY(y);
			loadButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			expandButton.setX(x + 46);
			expandButton.setY(y);
			expandButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			deleteButton.setX(x + width - 20);
			deleteButton.setY(y);
			deleteButton.extractRenderState(graphics, mouseX, mouseY, partialTick);

			nameBox.setX(x + 70);
			nameBox.setY(y);
			if (expanded) {
				nameBox.setWidth(width - 94);
				nameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
				SequenceEditBox box = multiBox(width - 70);
				box.setX(x + 70);
				box.setY(y + 24);
				box.extractRenderState(graphics, mouseX, mouseY, partialTick);
			} else {
				int nameWidth = Math.min(110, Math.max(70, width / 3));
				nameBox.setWidth(nameWidth);
				nameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
				singleBox.setX(x + 74 + nameWidth);
				singleBox.setY(y);
				singleBox.setWidth(Math.max(30, width - 98 - nameWidth));
				singleBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
			}
		}

		private List<AbstractWidget> widgets() {
			List<AbstractWidget> widgets = new ArrayList<>(List.of(loadButton, expandButton, nameBox, deleteButton));
			widgets.add(expanded && multiBox != null ? multiBox : singleBox);
			return widgets;
		}

		private String sequence() {
			return expanded ? (multiBox == null ? multiValue : multiBox.getValue()) : singleBox.getValue();
		}

		private SavedSequence value() {
			return new SavedSequence(nameBox.getValue(), sequence());
		}

		private FormattedCharSequence formatCollapsed(String partial, int offset) {
			String value = singleBox.getValue();
			Style[] styles = SequenceTextStyler.styles(value);
			return SequenceTextStyler.sequence(value, styles, offset, offset + partial.length());
		}
	}

	private static String singleLine(String text) {
		return text.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
	}
}
