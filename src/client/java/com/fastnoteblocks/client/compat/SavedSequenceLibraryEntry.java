package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.SavedSequence;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack;
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
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class SavedSequenceLibraryEntry extends AbstractConfigListEntry<List<SavedSequence>> {
	private final FastNoteblocksConfig config;
	private final ActiveSequenceEntry activeSequence;
	private final List<SavedRow> rows = new ArrayList<>();
	private final List<SavedSequence> initialValue;
	private final Button addButton;
	private final Button saveActiveButton;

	SavedSequenceLibraryEntry(FastNoteblocksConfig config, ActiveSequenceEntry activeSequence) {
		super(Component.literal("Saved sequences"), false);
		this.config = config;
		this.activeSequence = activeSequence;
		this.initialValue = config.savedSequences();
		for (SavedSequence saved : initialValue) {
			rows.add(new SavedRow(saved));
		}
		this.addButton = Button.builder(Component.literal("+ Add sequence"), button -> {
			rows.add(new SavedRow(new SavedSequence("Untitled sequence", "")));
			persistLibrary();
		}).bounds(0, 0, 120, 20)
			.tooltip(Tooltip.create(Component.literal("Add a blank one-track sequence")))
			.build();
		this.saveActiveButton = Button.builder(Component.literal("Save/update"), button -> {
			SavedSequence active = activeSequence.savedSequence();
			int existing = matchingRow(active.name());
			if (existing >= 0) {
				rows.set(existing, new SavedRow(active));
			} else {
				rows.add(new SavedRow(active));
			}
			persistLibrary();
		}).bounds(0, 0, 100, 20)
			.tooltip(Tooltip.create(Component.literal("Save the active composition, or update the saved composition with the same name")))
			.build();
	}

	private int matchingRow(String name) {
		String normalized = name.trim();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).value().name().equalsIgnoreCase(normalized)) {
				return i;
			}
		}
		return -1;
	}

	private void syncLibrary() {
		config.setSavedSequences(getValue());
	}

	private void persistLibrary() {
		syncLibrary();
		FastNoteblocksConfig.save();
	}

	private void confirmDeleteSequence(SavedRow row) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen returnScreen = minecraft.gui.screen();
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				rows.remove(row);
				persistLibrary();
			}
			minecraft.gui.setScreen(returnScreen);
		}, Component.literal("Delete saved sequence?"),
			Component.literal("Delete \"" + row.nameBox.getValue() + "\"? This cannot be undone."),
			CommonComponents.GUI_REMOVE, CommonComponents.GUI_CANCEL));
	}

	@Override
	public int getItemHeight() {
		int height = 42;
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
		int footerWidth = addButton.getWidth() + 4 + saveActiveButton.getWidth();
		addButton.setX(x + (entryWidth - footerWidth) / 2);
		addButton.setY(rowY);
		addButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		saveActiveButton.setX(addButton.getX() + addButton.getWidth() + 4);
		saveActiveButton.setY(rowY);
		saveActiveButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private List<AbstractWidget> widgets() {
		List<AbstractWidget> widgets = new ArrayList<>();
		for (SavedRow row : rows) {
			widgets.addAll(row.widgets());
		}
		widgets.add(addButton);
		widgets.add(saveActiveButton);
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
		syncLibrary();
	}

	private final class SavedRow {
		private final SavedSequence saved;
		private final EditBox nameBox;
		private final Button loadButton;
		private final Button expandButton;
		private final Button deleteButton;
		private boolean expanded;

		SavedRow(SavedSequence saved) {
			this.saved = saved;
			this.nameBox = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 20, Component.literal("Sequence name"));
			nameBox.setMaxLength(80);
			nameBox.setValue(saved.name());
			nameBox.setResponder(value -> syncLibrary());
			this.loadButton = Button.builder(Component.literal("Load"), button -> activeSequence.setSequence(value()))
				.bounds(0, 0, 42, 20)
				.tooltip(Tooltip.create(Component.literal("Restore this composition into the active editor")))
				.build();
			this.expandButton = Button.builder(expandLabel(), button -> {
				expanded = !expanded;
				button.setMessage(expandLabel());
			}).bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Show or hide this sequence's tracks")))
				.build();
			this.deleteButton = Button.builder(Component.literal("×").withStyle(ChatFormatting.RED), button -> confirmDeleteSequence(this))
				.bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Delete this saved sequence")))
				.build();
		}

		private Component expandLabel() {
			return Component.literal(expanded ? "▾" : "▸");
		}

		int height() {
			return expanded ? 24 + saved.tracks().size() * 22 : 20;
		}

		SavedSequence value() {
			return new SavedSequence(nameBox.getValue(), saved.tracks(), saved.activeTrackIndex(), saved.delayScaleQuarters());
		}

		void extract(GuiGraphicsExtractor graphics, int x, int y, int width,
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
			nameBox.setWidth(Math.max(70, width - 164));
			nameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
			String summary = saved.tracks().size() + (saved.tracks().size() == 1 ? " track" : " tracks")
				+ " · " + FastNoteblocksConfig.delayScaleLabel(saved.delayScaleQuarters());
			graphics.text(Minecraft.getInstance().font, summary, x + width - 88, y + 6, 0xFF999999, false);
			if (!expanded) {
				return;
			}
			int trackY = y + 24;
			for (int i = 0; i < saved.tracks().size(); i++) {
				SequenceTrack track = saved.tracks().get(i);
				PreviewInstrument instrument = PreviewInstrument.byId(track.instrument());
				graphics.item(new ItemStack(instrument.icon()), x + 4, trackY + 2);
				String prefix = (i == saved.activeTrackIndex() ? "● " : "") + track.name() + ": ";
				String text = prefix + singleLine(track.sequence());
				graphics.text(Minecraft.getInstance().font,
					Minecraft.getInstance().font.plainSubstrByWidth(text, Math.max(20, width - 28)),
					x + 26, trackY + 6, i == saved.activeTrackIndex() ? 0xFF55FF55 : 0xFFCCCCCC, false);
				trackY += 22;
			}
		}

		List<AbstractWidget> widgets() {
			return List.of(loadButton, expandButton, nameBox, deleteButton);
		}
	}

	private static String singleLine(String text) {
		return text.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
	}
}
