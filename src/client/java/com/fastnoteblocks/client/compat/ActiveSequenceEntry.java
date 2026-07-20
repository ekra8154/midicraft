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
	private static final String PITCH_GUIDE = "0:F♯  1:G  2:G♯  3:A  4:A♯  5:B  6:C  7:C♯  8:D  9:D♯  10:E  11:F  "
		+ "12:F♯  13:G  14:G♯  15:A  16:A♯  17:B  18:C  19:C♯  20:D  21:D♯  22:E  23:F  24:F♯";
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
	private List<TextRange> playbackRanges = List.of();
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
		nameBox.setResponder(config::setActiveSequenceName);
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
			playbackRanges = tokenRanges(getValue());
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
		playbackRanges = List.of();
		playbackIndex = 0;
		if (editor != null) {
			editor.clearPlaybackHighlight();
		}
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
			if (editor != null && playbackIndex < playbackRanges.size()) {
				TextRange range = playbackRanges.get(playbackIndex);
				editor.setPlaybackHighlight(range.from(), range.to());
			}
			if (step.type() == StepType.NOTE) {
				PreviewInstrument.byId(config.previewInstrument()).play(step.value());
				playbackIndex++;
				if (playbackIndex >= playbackSteps.size()) {
					nextPlaybackAt = now + 150L;
					return;
				}
			} else {
				int remainingGroupSteps = Math.max(1, step.delayCount() - step.delayIndex());
				playbackIndex = Math.min(playbackSteps.size(), playbackIndex + remainingGroupSteps);
				nextPlaybackAt += step.delayTotal() * 100L;
			}
		}
	}

	private static List<TextRange> tokenRanges(String value) {
		List<TextRange> ranges = new ArrayList<>();
		int tokenStart = 0;
		for (int i = 0; i <= value.length(); i++) {
			if (i != value.length() && value.charAt(i) != ',') {
				continue;
			}
			int from = tokenStart;
			int to = i;
			while (from < to && Character.isWhitespace(value.charAt(from))) {
				from++;
			}
			while (to > from && Character.isWhitespace(value.charAt(to - 1))) {
				to--;
			}
			String token = value.substring(from, to);
			int repeat = 1;
			if (token.endsWith("d") || token.endsWith("D")) {
				repeat = (Integer.parseInt(token.substring(0, token.length() - 1).trim()) + 3) / 4;
			}
			for (int copy = 0; copy < repeat; copy++) {
				ranges.add(new TextRange(from, to));
			}
			tokenStart = i + 1;
		}
		return List.copyOf(ranges);
	}

	private SequenceEditBox editor(int width) {
		if (editor == null || editor.getWidth() != width) {
			String current = editor == null ? value : editor.getValue();
			editor = new SequenceEditBox(Minecraft.getInstance().font, width, EDITOR_HEIGHT);
			editor.setCharacterLimit(12000);
			editor.setValueListener(config::setPlacementSequence);
			editor.setValue(current);
			value = current;
		}
		return editor;
	}

	@Override
	public int getItemHeight() {
		return expanded ? EDITOR_HEIGHT + 51 : 24;
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
			extractSequenceCounts(graphics, x, y + 25);
			extractPitchGuide(graphics, x, y + 36, entryWidth);
			SequenceEditBox box = editor(entryWidth);
			box.setX(x);
			box.setY(y + 47);
			box.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		if (!valid && mouseX >= labelX && mouseX <= nameX && mouseY >= y && mouseY < y + 20) {
			graphics.setTooltipForNextFrame(Component.translatable("error.fast-noteblocks.sequence"), mouseX, mouseY);
		}
	}

	private void extractSequenceCounts(GuiGraphicsExtractor graphics, int x, int y) {
		NoteSequence.Progress progress;
		try {
			progress = NoteSequence.progress(NoteSequence.parse(getValue()), config.placementSequencePosition());
		} catch (IllegalArgumentException ignored) {
			return;
		}
		if (progress.total() == 0) {
			return;
		}
		Component counts = Component.empty()
			.append(Component.literal(progress.position() + "/" + progress.total() + " overall   ").withStyle(net.minecraft.ChatFormatting.GRAY))
			.append(Component.literal(progress.noteBlockPosition() + "/" + progress.noteBlockTotal() + " note blocks   ")
				.withStyle(net.minecraft.ChatFormatting.AQUA))
			.append(Component.literal(progress.repeaterPosition() + "/" + progress.repeaterTotal() + " repeaters")
				.withStyle(net.minecraft.ChatFormatting.GOLD));
		graphics.text(Minecraft.getInstance().font, counts, x, y, 0xFFFFFFFF, false);
	}

	private static void extractPitchGuide(GuiGraphicsExtractor graphics, int x, int y, int width) {
		int textWidth = Minecraft.getInstance().font.width(PITCH_GUIDE);
		float scale = Math.min(1.0F, width / (float)Math.max(1, textWidth));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(Minecraft.getInstance().font, PITCH_GUIDE, 0, 0, 0xFFBBBBBB, false);
		graphics.pose().popMatrix();
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

	private record TextRange(int from, int to) {
	}
}
