package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.NoteSequence.Step;
import com.fastnoteblocks.NoteSequence.StepType;
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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

final class ActiveSequenceEntry extends AbstractConfigListEntry<String> {
	private static final int EDITOR_HEIGHT = 86;
	private static final int PALETTE_CELL = 22;
	private static final int PALETTE_COLUMNS = 7;
	private static final int PALETTE_HEIGHT = 70;
	private static final String PITCH_GUIDE = "0:F♯  1:G  2:G♯  3:A  4:A♯  5:B  6:C  7:C♯  8:D  9:D♯  10:E  11:F  "
		+ "12:F♯  13:G  14:G♯  15:A  16:A♯  17:B  18:C  19:C♯  20:D  21:D♯  22:E  23:F  24:F♯";

	private final FastNoteblocksConfig config;
	private final String initialName;
	private final List<SequenceTrack> initialTracks;
	private final int initialActiveTrack;
	private final EditBox nameBox;
	private final Button expandButton;
	private final Button playButton;
	private final Button addTrackButton;
	private final List<TrackRow> tracks = new ArrayList<>();
	private boolean expanded = true;
	private boolean playing;
	private int activeTrackIndex;

	ActiveSequenceEntry(FastNoteblocksConfig config) {
		super(Component.literal("Active sequence:"), false);
		this.config = config;
		this.initialName = config.activeSequenceName();
		this.initialTracks = config.tracks();
		this.initialActiveTrack = config.activeTrackIndex();
		this.activeTrackIndex = initialActiveTrack;
		this.nameBox = new EditBox(Minecraft.getInstance().font, 0, 0, 150, 20, Component.literal("Active sequence name"));
		nameBox.setMaxLength(80);
		nameBox.setValue(initialName);
		nameBox.setResponder(value -> syncConfig());
		this.expandButton = Button.builder(expandLabel(), button -> {
			expanded = !expanded;
			button.setMessage(expandLabel());
		}).bounds(0, 0, 20, 20)
			.tooltip(Tooltip.create(Component.literal("Expand or collapse the active sequence")))
			.build();
		this.playButton = Button.builder(playLabel(), button -> togglePlayback())
			.bounds(0, 0, 52, 20)
			.tooltip(Tooltip.create(Component.literal("Preview every track together")))
			.build();
		this.addTrackButton = Button.builder(Component.literal("+ Add track"), button -> addTrack())
			.bounds(0, 0, 120, 20)
			.tooltip(Tooltip.create(Component.literal("Add another track (maximum 4)")))
			.build();
		setTracks(config.tracks(), config.activeTrackIndex());
	}

	String sequenceName() {
		return nameBox.getValue().isBlank() ? "Untitled sequence" : nameBox.getValue().trim();
	}

	SavedSequence savedSequence() {
		return new SavedSequence(sequenceName(), trackValues(), activeTrackIndex);
	}

	void setSequence(SavedSequence saved) {
		stopPlayback();
		nameBox.setValue(saved.name());
		setTracks(saved.tracks(), saved.activeTrackIndex());
		syncConfig();
	}

	private void setTracks(List<SequenceTrack> values, int selectedTrack) {
		tracks.clear();
		for (SequenceTrack track : values) {
			if (tracks.size() < FastNoteblocksConfig.MAX_TRACKS) {
				tracks.add(new TrackRow(track));
			}
		}
		if (tracks.isEmpty()) {
			tracks.add(new TrackRow(new SequenceTrack("Track 1", "", "HARP", 0)));
		}
		activeTrackIndex = Math.max(0, Math.min(tracks.size() - 1, selectedTrack));
		updateTrackControls();
	}

	private void addTrack() {
		if (tracks.size() >= FastNoteblocksConfig.MAX_TRACKS) {
			return;
		}
		tracks.add(new TrackRow(new SequenceTrack("Track " + (tracks.size() + 1), "", "HARP", 0), false));
		activeTrackIndex = tracks.size() - 1;
		updateTrackControls();
		syncAndSave();
	}

	private void deleteTrack(TrackRow row) {
		if (tracks.size() <= 1) {
			return;
		}
		int removed = tracks.indexOf(row);
		tracks.remove(row);
		if (activeTrackIndex > removed) {
			activeTrackIndex--;
		} else if (activeTrackIndex >= tracks.size()) {
			activeTrackIndex = tracks.size() - 1;
		}
		updateTrackControls();
		syncAndSave();
	}

	private void selectTrack(TrackRow row) {
		activeTrackIndex = Math.max(0, tracks.indexOf(row));
		updateTrackControls();
		syncAndSave();
	}

	private void updateTrackControls() {
		for (int i = 0; i < tracks.size(); i++) {
			tracks.get(i).updateControls(i == activeTrackIndex, tracks.size() > 1);
		}
		addTrackButton.active = tracks.size() < FastNoteblocksConfig.MAX_TRACKS;
	}

	private List<SequenceTrack> trackValues() {
		return tracks.stream().map(TrackRow::value).toList();
	}

	private void syncConfig() {
		config.setActiveSequenceName(sequenceName());
		config.setTracks(trackValues());
		config.setActiveTrackIndex(activeTrackIndex);
	}

	private void syncAndSave() {
		syncConfig();
		FastNoteblocksConfig.save();
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
		long start = Util.getMillis();
		boolean any = false;
		for (TrackRow track : tracks) {
			any |= track.startPlayback(start);
		}
		if (any) {
			playing = true;
			playButton.setMessage(playLabel());
		}
	}

	private void stopPlayback() {
		playing = false;
		for (TrackRow track : tracks) {
			track.stopPlayback();
		}
		playButton.setMessage(playLabel());
	}

	private void updatePlayback() {
		if (!playing) {
			return;
		}
		long now = Util.getMillis();
		boolean anyPlaying = false;
		for (TrackRow track : tracks) {
			track.updatePlayback(now);
			anyPlaying |= track.isPlaying();
		}
		if (!anyPlaying) {
			stopPlayback();
		}
	}

	private boolean sequencesValid() {
		boolean any = false;
		for (TrackRow track : tracks) {
			if (FastNoteblocksConfig.validatePlacementSequence(track.sequence()).isPresent()) {
				return false;
			}
			any |= !track.sequence().isBlank();
		}
		return any;
	}

	@Override
	public int getItemHeight() {
		if (!expanded) {
			return 24;
		}
		int height = 36 + 24;
		for (TrackRow track : tracks) {
			height += track.height() + 4;
		}
		return height;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
		updatePlayback();
		playButton.active = sequencesValid();
		expandButton.setX(x);
		expandButton.setY(y);
		expandButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int labelX = x + 26;
		graphics.text(Minecraft.getInstance().font, getFieldName(), labelX, y + 6, 0xFFFFFFFF);
		int nameX = labelX + Minecraft.getInstance().font.width(getFieldName()) + 6;
		playButton.setX(x + entryWidth - playButton.getWidth());
		playButton.setY(y);
		playButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		nameBox.setX(nameX);
		nameBox.setY(y);
		nameBox.setWidth(Math.max(60, playButton.getX() - nameX - 4));
		nameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (!expanded) {
			return;
		}

		extractPitchGuide(graphics, x, y + 25, entryWidth);
		int trackY = y + 36;
		for (TrackRow track : tracks) {
			track.extract(graphics, x, trackY, entryWidth, mouseX, mouseY, partialTick);
			trackY += track.height() + 4;
		}
		addTrackButton.setX(x + (entryWidth - addTrackButton.getWidth()) / 2);
		addTrackButton.setY(trackY);
		addTrackButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		nameBox.setFocused(false);
		for (TrackRow track : tracks) {
			track.blurBoxes();
		}
		for (TrackRow track : tracks) {
			if (track.handleInstrumentClick(event)) {
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private List<AbstractWidget> widgets() {
		List<AbstractWidget> widgets = new ArrayList<>(List.of(expandButton, nameBox, playButton));
		if (expanded) {
			for (TrackRow track : tracks) {
				widgets.addAll(track.widgets());
			}
			widgets.add(addTrackButton);
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
		return tracks.get(activeTrackIndex).sequence();
	}

	@Override
	public Optional<String> getDefaultValue() {
		return Optional.of("");
	}

	@Override
	public boolean isEdited() {
		return !sequenceName().equals(initialName)
			|| !trackValues().equals(initialTracks)
			|| activeTrackIndex != initialActiveTrack;
	}

	@Override
	public Optional<Component> getError() {
		for (TrackRow track : tracks) {
			Optional<Component> error = FastNoteblocksConfig.validatePlacementSequence(track.sequence());
			if (error.isPresent()) {
				return error;
			}
		}
		return Optional.empty();
	}

	@Override
	public void save() {
		syncConfig();
	}

	private final class TrackRow {
		private final EditBox trackName;
		private final Button buildButton;
		private final Button collapseButton;
		private final Button deleteButton;
		private SequenceEditBox editor;
		private String sequence;
		private String instrument;
		private int position;
		private boolean expanded = true;
		private boolean paletteOpen;
		private int iconX;
		private int iconY;
		private int paletteX;
		private int paletteY;
		private TrackPlayback playback;

		TrackRow(SequenceTrack track) {
			this(track, true);
		}

		TrackRow(SequenceTrack track, boolean initiallyExpanded) {
			this.sequence = track.sequence();
			this.instrument = PreviewInstrument.byId(track.instrument()).id();
			this.position = track.position();
			this.expanded = initiallyExpanded;
			this.trackName = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 20, Component.literal("Track name"));
			trackName.setMaxLength(60);
			trackName.setValue(track.name());
			trackName.setResponder(value -> syncConfig());
			this.buildButton = Button.builder(Component.literal("Build"), button -> selectTrack(this))
				.bounds(0, 0, 52, 20)
				.tooltip(Tooltip.create(Component.literal("Use this track for placement and the in-game timeline")))
				.build();
			this.collapseButton = Button.builder(collapseLabel(), button -> {
				expanded = !expanded;
				button.setMessage(collapseLabel());
			}).bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Expand or collapse this track")))
				.build();
			this.deleteButton = Button.builder(Component.literal("×").withStyle(ChatFormatting.RED), button -> deleteTrack(this))
				.bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Delete this track")))
				.build();
		}

		void updateControls(boolean active, boolean canDelete) {
			buildButton.setMessage(Component.literal(active ? "● Build" : "○ Build")
				.withStyle(active ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			deleteButton.active = canDelete;
		}

		private Component collapseLabel() {
			return Component.literal(expanded ? "▾" : "▸");
		}

		int height() {
			if (!expanded) {
				return 20;
			}
			return 35 + (paletteOpen ? PALETTE_HEIGHT : 0) + EDITOR_HEIGHT;
		}

		String sequence() {
			return editor == null ? sequence : editor.getValue();
		}

		SequenceTrack value() {
			return new SequenceTrack(trackName.getValue(), sequence(), instrument, position);
		}

		private SequenceEditBox editor(int width) {
			if (editor == null || editor.getWidth() != width) {
				String current = editor == null ? sequence : editor.getValue();
				editor = new SequenceEditBox(Minecraft.getInstance().font, width, EDITOR_HEIGHT);
				editor.setCharacterLimit(12000);
				editor.setValueListener(value -> {
					sequence = value;
					syncConfig();
				});
				editor.setValue(current);
				sequence = current;
			}
			return editor;
		}

		void extract(GuiGraphicsExtractor graphics, int x, int y, int width,
				int mouseX, int mouseY, float partialTick) {
			buildButton.setX(x);
			buildButton.setY(y);
			buildButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			collapseButton.setX(x + 56);
			collapseButton.setY(y);
			collapseButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			deleteButton.setX(x + width - 20);
			deleteButton.setY(y);
			deleteButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			iconX = x + width - 44;
			iconY = y;
			extractInstrumentIcon(graphics, mouseX, mouseY);
			trackName.setX(x + 80);
			trackName.setY(y);
			trackName.setWidth(Math.max(50, iconX - (x + 80) - 4));
			trackName.extractRenderState(graphics, mouseX, mouseY, partialTick);
			if (!expanded) {
				return;
			}

			extractCounts(graphics, x, y + 24);
			int contentY = y + 35;
			if (paletteOpen) {
				paletteX = x + Math.max(0, (width - PALETTE_COLUMNS * PALETTE_CELL) / 2);
				paletteY = contentY;
				extractPalette(graphics, mouseX, mouseY);
				contentY += PALETTE_HEIGHT;
			}
			SequenceEditBox box = editor(width);
			box.setX(x);
			box.setY(contentY);
			box.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}

		private void extractCounts(GuiGraphicsExtractor graphics, int x, int y) {
			try {
				NoteSequence.Progress progress = NoteSequence.progress(NoteSequence.parse(sequence()), position);
				if (progress.total() == 0) {
					return;
				}
				Component counts = Component.empty()
					.append(Component.literal(progress.position() + "/" + progress.total() + " overall   ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(progress.noteBlockPosition() + "/" + progress.noteBlockTotal() + " note blocks   ")
						.withStyle(ChatFormatting.AQUA))
					.append(Component.literal(progress.repeaterPosition() + "/" + progress.repeaterTotal() + " repeaters")
						.withStyle(ChatFormatting.GOLD));
				graphics.text(Minecraft.getInstance().font, counts, x, y, 0xFFFFFFFF, false);
			} catch (IllegalArgumentException ignored) {
			}
		}

		private void extractInstrumentIcon(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			PreviewInstrument selected = PreviewInstrument.byId(instrument);
			boolean hovered = mouseX >= iconX && mouseX < iconX + 20 && mouseY >= iconY && mouseY < iconY + 20;
			graphics.fill(iconX, iconY, iconX + 20, iconY + 20, paletteOpen ? 0xFFFFAA00 : hovered ? 0xFF888888 : 0xFF333333);
			graphics.fill(iconX + 1, iconY + 1, iconX + 19, iconY + 19, 0xFF111111);
			graphics.item(new ItemStack(selected.icon()), iconX + 2, iconY + 2);
			if (hovered) {
				graphics.setTooltipForNextFrame(Component.literal(selected.name()), mouseX, mouseY);
			}
		}

		private void extractPalette(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			for (int i = 0; i < PreviewInstrument.VALUES.size(); i++) {
				PreviewInstrument value = PreviewInstrument.VALUES.get(i);
				int cellX = paletteX + i % PALETTE_COLUMNS * PALETTE_CELL;
				int cellY = paletteY + i / PALETTE_COLUMNS * PALETTE_CELL;
				boolean selected = value.id().equals(instrument);
				boolean hovered = mouseX >= cellX && mouseX < cellX + 20 && mouseY >= cellY && mouseY < cellY + 20;
				graphics.fill(cellX, cellY, cellX + 20, cellY + 20, selected ? 0xFFFFAA00 : hovered ? 0xFF888888 : 0xFF333333);
				graphics.fill(cellX + 1, cellY + 1, cellX + 19, cellY + 19, 0xFF111111);
				graphics.item(new ItemStack(value.icon()), cellX + 2, cellY + 2);
				if (hovered) {
					graphics.setTooltipForNextFrame(Component.literal(value.name()), mouseX, mouseY);
				}
			}
		}

		boolean handleInstrumentClick(MouseButtonEvent event) {
			if (event.button() != 0) {
				return false;
			}
			if (event.x() >= iconX && event.x() < iconX + 20 && event.y() >= iconY && event.y() < iconY + 20) {
				if (!expanded) {
					expanded = true;
					paletteOpen = true;
					collapseButton.setMessage(collapseLabel());
				} else {
					paletteOpen = !paletteOpen;
				}
				for (TrackRow track : tracks) {
					if (track != this) {
						track.paletteOpen = false;
					}
				}
				return true;
			}
			if (!paletteOpen || event.x() < paletteX || event.y() < paletteY) {
				return false;
			}
			int column = (int)(event.x() - paletteX) / PALETTE_CELL;
			int row = (int)(event.y() - paletteY) / PALETTE_CELL;
			if (column < 0 || column >= PALETTE_COLUMNS || row < 0) {
				return false;
			}
			int index = row * PALETTE_COLUMNS + column;
			if (index >= PreviewInstrument.VALUES.size()
					|| event.x() >= paletteX + column * PALETTE_CELL + 20
					|| event.y() >= paletteY + row * PALETTE_CELL + 20) {
				return false;
			}
			PreviewInstrument selected = PreviewInstrument.VALUES.get(index);
			instrument = selected.id();
			selected.play();
			syncAndSave();
			return true;
		}

		List<AbstractWidget> widgets() {
			List<AbstractWidget> result = new ArrayList<>(List.of(buildButton, collapseButton, trackName, deleteButton));
			if (expanded && editor != null) {
				result.add(editor);
			}
			return result;
		}

		void blurBoxes() {
			trackName.setFocused(false);
			if (editor != null) {
				editor.setFocused(false);
			}
		}

		boolean startPlayback(long start) {
			List<Step> steps;
			try {
				steps = NoteSequence.parse(sequence());
			} catch (IllegalArgumentException ignored) {
				return false;
			}
			if (steps.isEmpty()) {
				return false;
			}
			playback = new TrackPlayback(steps, tokenRanges(sequence()), 0, start, true);
			return true;
		}

		boolean isPlaying() {
			return playback != null && playback.playing();
		}

		void stopPlayback() {
			playback = null;
			if (editor != null) {
				editor.clearPlaybackHighlight();
			}
		}

		void updatePlayback(long now) {
			while (isPlaying() && now >= playback.nextAt()) {
				if (playback.index() >= playback.steps().size()) {
					stopPlayback();
					return;
				}
				int index = playback.index();
				Step step = playback.steps().get(index);
				if (editor != null && index < playback.ranges().size()) {
					TextRange range = playback.ranges().get(index);
					editor.setPlaybackHighlight(range.from(), range.to());
				}
				if (step.type() == StepType.NOTE) {
					PreviewInstrument.byId(instrument).play(step.value());
					int nextIndex = index + 1;
					if (nextIndex >= playback.steps().size()) {
						playback = new TrackPlayback(playback.steps(), playback.ranges(), nextIndex, now + 150L, true);
						return;
					}
					playback = new TrackPlayback(playback.steps(), playback.ranges(), nextIndex, playback.nextAt(), true);
				} else {
					int remaining = Math.max(1, step.delayCount() - step.delayIndex());
					playback = new TrackPlayback(
						playback.steps(), playback.ranges(), Math.min(playback.steps().size(), index + remaining),
						playback.nextAt() + step.delayTotal() * 100L, true
					);
				}
			}
		}
	}

	private static List<TextRange> tokenRanges(String value) {
		List<TextRange> ranges = new ArrayList<>();
		for (NoteSequence.Token token : NoteSequence.tokens(value)) {
			int repeat = 1;
			if (token.text().endsWith("d") || token.text().endsWith("D")) {
				repeat = (Integer.parseInt(token.text().substring(0, token.text().length() - 1).trim()) + 3) / 4;
			}
			for (int copy = 0; copy < repeat; copy++) {
				ranges.add(new TextRange(token.from(), token.to()));
			}
		}
		return List.copyOf(ranges);
	}

	private record TextRange(int from, int to) {
	}

	private record TrackPlayback(List<Step> steps, List<TextRange> ranges, int index, long nextAt, boolean playing) {
	}
}
