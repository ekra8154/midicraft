package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.NoteSequence.Step;
import com.fastnoteblocks.NoteSequence.StepType;
import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

final class ActiveSequenceEntry extends AbstractConfigListEntry<String> {
	private static final int EDITOR_HEIGHT = 86;
	private final FastNoteblocksConfig config;
	private final String initialValue;
	private final String initialName;
	private final EditBox nameBox;
	private final Button expandButton;
	private final Button playButton;
	private SequenceEditBox editor;
	private String value;
	private boolean expanded = true;
	private boolean playing;
	private List<Step> playbackSteps = List.of();
	private int playbackIndex;
	private long nextPlaybackAt;

	ActiveSequenceEntry(FastNoteblocksConfig config) {
		super(Component.literal("Active sequence:"), false);
		this.config = config;
		this.initialValue = config.placementSequence();
		this.initialName = config.activeSequenceName();
		this.value = initialValue;
		this.nameBox = new EditBox(Minecraft.getInstance().font, 0, 0, 150, 20, Component.literal("Active sequence name"));
		nameBox.setMaxLength(80);
		nameBox.setValue(initialName);
		this.expandButton = Button.builder(expandLabel(), button -> {
			expanded = !expanded;
			button.setMessage(expandLabel());
		}).bounds(0, 0, 20, 20)
			.tooltip(Tooltip.create(Component.literal("Expand or collapse the active sequence")))
			.build();
		this.playButton = Button.builder(playLabel(), button -> togglePlayback())
			.bounds(0, 0, 52, 20)
			.tooltip(Tooltip.create(Component.literal("Preview this sequence with the selected instrument")))
			.build();
	}

	void setSequence(String name, String value) {
		nameBox.setValue(name == null || name.isBlank() ? "Untitled sequence" : name);
		setValue(value);
		stopPlayback();
	}

	String sequenceName() {
		return nameBox.getValue().isBlank() ? "Untitled sequence" : nameBox.getValue().trim();
	}

	void setValue(String value) {
		this.value = value == null ? "" : value;
		if (editor != null) {
			editor.setValue(this.value);
		}
	}

	private Component expandLabel() {
		return Component.literal(expanded ? "▾" : "▸");
	}

	private Component playLabel() {
		return Component.literal(playing ? "Stop" : "Play");
	}

	private void togglePlayback() {
		if (playing) {
			stopPlayback();
			return;
		}
		try {
			playbackSteps = NoteSequence.parse(getValue());
		} catch (IllegalArgumentException ignored) {
			return;
		}
		if (playbackSteps.isEmpty()) {
			return;
		}
		playing = true;
		playbackIndex = 0;
		nextPlaybackAt = Util.getMillis();
		playButton.setMessage(playLabel());
	}

	private void stopPlayback() {
		playing = false;
		playbackSteps = List.of();
		playbackIndex = 0;
		playButton.setMessage(playLabel());
	}

	private void updatePlayback() {
		if (!playing) {
			return;
		}
		long now = Util.getMillis();
		while (playing && now >= nextPlaybackAt) {
			if (playbackIndex >= playbackSteps.size()) {
				stopPlayback();
				return;
			}
			Step step = playbackSteps.get(playbackIndex);
			if (step.type() == StepType.NOTE) {
				PreviewInstrument.byId(config.previewInstrument()).play(step.value());
				playbackIndex++;
			} else {
				int remainingGroupSteps = Math.max(1, step.delayCount() - step.delayIndex());
				playbackIndex = Math.min(playbackSteps.size(), playbackIndex + remainingGroupSteps);
				nextPlaybackAt += step.delayTotal() * 100L;
			}
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
		return expanded ? EDITOR_HEIGHT + 28 : 24;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
		updatePlayback();
		boolean valid = FastNoteblocksConfig.validatePlacementSequence(getValue()).isEmpty();
		playButton.active = valid && !getValue().isBlank();
		expandButton.setX(x);
		expandButton.setY(y);
		expandButton.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int labelX = x + 26;
		graphics.text(Minecraft.getInstance().font, getFieldName(), labelX, y + 6, valid ? 0xFFFFFFFF : 0xFFFF5555);
		int nameX = labelX + Minecraft.getInstance().font.width(getFieldName()) + 6;
		playButton.setX(x + entryWidth - playButton.getWidth());
		playButton.setY(y);
		playButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		nameBox.setX(nameX);
		nameBox.setY(y);
		nameBox.setWidth(Math.max(60, playButton.getX() - nameX - 4));
		nameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (expanded) {
			SequenceEditBox box = editor(entryWidth);
			box.setX(x);
			box.setY(y + 24);
			box.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		if (!valid && mouseX >= labelX && mouseX <= nameX && mouseY >= y && mouseY < y + 20) {
			graphics.setTooltipForNextFrame(Component.translatable("error.fast-noteblocks.sequence"), mouseX, mouseY);
		}
	}

	private List<AbstractWidget> widgets() {
		List<AbstractWidget> widgets = new ArrayList<>(List.of(expandButton, nameBox, playButton));
		if (expanded && editor != null) {
			widgets.add(editor);
		}
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
	public String getValue() {
		return editor == null ? value : editor.getValue();
	}

	@Override
	public Optional<String> getDefaultValue() {
		return Optional.of("");
	}

	@Override
	public boolean isEdited() {
		return !getValue().equals(initialValue) || !sequenceName().equals(initialName);
	}

	@Override
	public Optional<Component> getError() {
		return FastNoteblocksConfig.validatePlacementSequence(getValue());
	}

	@Override
	public void save() {
		config.setActiveSequenceName(sequenceName());
		config.setPlacementSequence(getValue());
	}
}
