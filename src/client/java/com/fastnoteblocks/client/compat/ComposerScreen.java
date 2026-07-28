package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerHistory;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.ClipboardNote;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.MinecraftConversion;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.ComposerProject.PasteResult;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public final class ComposerScreen extends Screen {
	private static final int TOOLBAR_HEIGHT = 34;
	private static final int LAYER_PANEL_WIDTH = 196;
	private static final int PIANO_WIDTH = 48;
	private static final int TIMELINE_RULER_HEIGHT = 16;
	private static final int LAYER_ROW_HEIGHT = 42;
	private static final int CONTEXT_MENU_WIDTH = 104;
	private static final int CONTEXT_MENU_ROW_HEIGHT = 16;
	private static final int TOOLBAR_MENU_WIDTH = 126;
	private static final int TOOLBAR_MENU_ROW_HEIGHT = 18;
	private static final int ROW_HEIGHT = 12;
	private static final int MAX_SIMULTANEOUS_NOTES = 30;
	private static final int CHORD_WARNING_THRESHOLD = 24;
	private static final int MIN_MIDI_NOTE = 0;
	private static final int MAX_MIDI_NOTE = 127;
	private static final int[] LAYER_COLORS = {
		0xFF35D7E5, 0xFFFFB347, 0xFF9BE564, 0xFFD19BFF, 0xFFFF6B9A,
		0xFF7CA7FF, 0xFFFFE66D, 0xFF8CE0C3, 0xFFFF8C5A, 0xFFC3F584
	};

	private final Screen parent;
	private final Runnable onReturn;
	private final FastNoteblocksConfig config;
	private final ComposerHistory history;
	private final Set<Long> selectedNotes = new LinkedHashSet<>();
	private final List<Button> layerButtons = new ArrayList<>();
	private final List<Button> moveLayerButtons = new ArrayList<>();
	private List<ClipboardNote> clipboard = List.of();
	private Button playButton;
	private Button snapButton;
	private DelayScaleSlider delayScaleSlider;
	private Button addLayerButton;
	private EditBox layerNameBox;
	private boolean playing;
	private long playbackStartedAt;
	private long playbackStartTick;
	private int[] playbackIndices = new int[0];
	private boolean draggingPlayhead;
	private long horizontalScroll;
	private int topMidiNote = 91;
	private double ticksPerPixel = 10.0;
	private boolean draggingNotes;
	private boolean selectingBox;
	private double dragStartX;
	private double dragStartY;
	private double selectionEndX;
	private double selectionEndY;
	private ComposerProject dragBase;
	private ComposerProject dragPreview;
	private long dragTickDelta;
	private int dragPitchDelta;
	private int rollX;
	private int rollY;
	private int rollWidth;
	private int rollHeight;
	private double lastMouseX;
	private double lastMouseY;
	private int instrumentMenuLayer = -1;
	private int editingLayer = -1;
	private int snapSubdivision = 4;
	private boolean contextMenuOpen;
	private int contextMenuX;
	private int contextMenuY;
	private ToolbarMenu toolbarMenu = ToolbarMenu.NONE;
	private int toolbarMenuX;
	private ComposerProject cachedStatsProject;
	private ProjectStats cachedStats;

	public ComposerScreen(Screen parent, FastNoteblocksConfig config) {
		this(parent, config, () -> {
		});
	}

	public ComposerScreen(Screen parent, FastNoteblocksConfig config, Runnable onReturn) {
		super(Component.literal("Fast Noteblocks Composer"));
		this.parent = parent;
		this.config = config;
		this.onReturn = onReturn == null ? () -> {
		} : onReturn;
		this.history = new ComposerHistory(config.composerProject());
	}

	@Override
	protected void init() {
		clearWidgets();
		rollY = TOOLBAR_HEIGHT + 14 + TIMELINE_RULER_HEIGHT;
		rollHeight = Math.max(40, height - rollY - 24);
		centerMinecraftRange();
		int x = 8;
		addRenderableWidget(Button.builder(Component.literal("File"), button -> toggleToolbarMenu(ToolbarMenu.FILE, 8))
			.bounds(x, 7, 54, 20).build());
		x += 58;
		addRenderableWidget(Button.builder(Component.literal("Edit"), button -> toggleToolbarMenu(ToolbarMenu.EDIT, 66))
			.bounds(x, 7, 54, 20).build());
		x += 58;
		playButton = addRenderableWidget(Button.builder(playLabel(), button -> togglePlayback())
			.bounds(x, 7, 54, 20)
			.tooltip(Tooltip.create(Component.literal("Preview all unmuted layers")))
			.build());
		x += 58;
		snapButton = addRenderableWidget(Button.builder(snapLabel(), button -> cycleSnap())
			.bounds(x, 7, 78, 20)
			.tooltip(Tooltip.create(Component.literal("Grid used when adding or dragging notes")))
			.build());
		x += 82;
		delayScaleSlider = addRenderableWidget(new DelayScaleSlider(
			x, 7, 94, 20, config.activeSequenceDelayScaleQuarters(), this::setDelayScale
		));
		delayScaleSlider.setTooltip(Tooltip.create(Component.literal(
			"Timescale for exported repeater delays (0.25x to 8.00x)"
		)));
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void rebuildLayerButtons() {
		for (Button button : layerButtons) {
			removeWidget(button);
		}
		layerButtons.clear();
		ComposerProject project = project();
		for (int index = 0; index < project.layers().size(); index++) {
			final int layerIndex = index;
			Layer layer = project.layers().get(index);
			int y = layerY(index);
			Button mute = addRenderableWidget(Button.builder(
				Component.literal(layer.muted() ? "X" : "M"),
				button -> updateLayer(layerIndex, project().layers().get(layerIndex).withMuted(
					!project().layers().get(layerIndex).muted()
				))
			).bounds(14, y + 22, 24, 16)
				.tooltip(Tooltip.create(Component.literal(layer.muted() ? "Unmute layer" : "Mute layer")))
				.build());
			Button build = addRenderableWidget(Button.builder(
				Component.literal(layer.buildEnabled() ? "B" : "-"),
				button -> updateLayer(layerIndex, project().layers().get(layerIndex).withBuildEnabled(
					!project().layers().get(layerIndex).buildEnabled()
				))
			).bounds(40, y + 22, 24, 16)
				.tooltip(Tooltip.create(Component.literal(layer.buildEnabled() ? "Included when building" : "Skipped when building")))
				.build());
			Button visible = addRenderableWidget(Button.builder(
				Component.literal(layer.visible() ? "S" : "H"),
				button -> updateLayer(layerIndex, project().layers().get(layerIndex).withVisible(
					!project().layers().get(layerIndex).visible()
				))
			).bounds(66, y + 22, 24, 16)
				.tooltip(Tooltip.create(Component.literal(layer.visible() ? "Shown in the editor" : "Hidden in the editor")))
				.build());
			Button instrument = addRenderableWidget(Button.builder(
				Component.literal(PreviewInstrument.byId(layer.instrument()).name()),
				button -> instrumentMenuLayer = instrumentMenuLayer == layerIndex ? -1 : layerIndex
			).bounds(92, y + 22, 56, 16)
				.tooltip(Tooltip.create(Component.literal("Open the note-block instrument palette")))
				.build());
			Button up = addRenderableWidget(Button.builder(Component.literal("^"),
				button -> moveLayer(layerIndex, -1))
				.bounds(150, y + 22, 18, 16)
				.tooltip(Tooltip.create(Component.literal("Move layer up")))
				.build());
			Button down = addRenderableWidget(Button.builder(Component.literal("v"),
				button -> moveLayer(layerIndex, 1))
				.bounds(170, y + 22, 18, 16)
				.tooltip(Tooltip.create(Component.literal("Move layer down")))
				.build());
			up.active = layerIndex > 0;
			down.active = layerIndex < project.layers().size() - 1;
			layerButtons.addAll(List.of(mute, build, visible, instrument, up, down));
		}
	}

	private void rebuildMoveLayerButtons() {
		for (Button button : moveLayerButtons) {
			removeWidget(button);
		}
		moveLayerButtons.clear();
		int y = Math.min(height - 46, layerY(project().layers().size()) + 8);
		addLayerButton = addRenderableWidget(Button.builder(Component.literal("+ Layer"), button -> {
			if (project().layers().size() < ComposerProject.MAX_LAYERS) {
				ComposerProject added = project().addLayer();
				int newLayer = added.layers().size() - 1;
				if (!selectedNotes.isEmpty()) {
					added = added.moveNotesToLayer(selectedNotes, newLayer);
				}
				apply(added);
				rebuildLayerButtons();
				rebuildMoveLayerButtons();
			}
		}).bounds(8, y, LAYER_PANEL_WIDTH - 16, 18)
			.tooltip(Tooltip.create(Component.literal(
				"Add a layer and move the current selection into it (maximum 10)"
			)))
			.build());
		moveLayerButtons.add(addLayerButton);
		for (int index = 0; index < 0; index++) {
			final int target = index;
			int row = index / 5;
			int column = index % 5;
			Button move = addRenderableWidget(Button.builder(Component.literal("→" + (index + 1)),
				button -> moveSelectionToLayer(target))
				.bounds(8 + column * 37, y + 22 + row * 20, 34, 18)
				.tooltip(Tooltip.create(Component.literal("Move selected notes to Layer " + (index + 1))))
				.build());
			move.active = index < project().layers().size();
			moveLayerButtons.add(move);
		}
	}

	private Component layerLabel(int index, Layer layer) {
		String marker = index == project().activeLayerIndex() ? "▶ " : "  ";
		return Component.literal(marker + "L" + (index + 1) + "  " + layer.name());
	}

	private void updateLayer(int index, Layer layer) {
		apply(project().withLayer(index, layer));
		rebuildLayerButtons();
	}

	private void moveSelectionToLayer(int target) {
		if (target < 0 || target >= project().layers().size()) {
			return;
		}
		apply(project().moveNotesToLayer(selectedNotes, target));
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
	}

	private void moveLayer(int layerIndex, int direction) {
		if (layerIndex < 0 || layerIndex >= project().layers().size()) {
			return;
		}
		instrumentMenuLayer = -1;
		apply(project().moveLayer(layerIndex, direction));
		rebuildLayerButtons();
	}

	private void transposeSelected(int semitones) {
		if (!selectedNotes.isEmpty()) {
			apply(project().moveNotes(selectedNotes, 0L, semitones));
		}
	}

	private void fitSelectedToMinecraft() {
		List<NoteEvent> selected = project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selectedNotes.contains(note.id()))
			.toList();
		if (selected.isEmpty()) {
			minecraft.gui.hud.setOverlayMessage(Component.literal("Select notes to fit first."), true);
			return;
		}
		int minimum = selected.stream().mapToInt(NoteEvent::midiNote).min().orElse(0);
		int maximum = selected.stream().mapToInt(NoteEvent::midiNote).max().orElse(127);
		int bestShift = Integer.MAX_VALUE;
		for (int shift = -120; shift <= 120; shift += 12) {
			if (minimum + shift >= ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
					&& maximum + shift <= ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE
					&& (bestShift == Integer.MAX_VALUE || Math.abs(shift) < Math.abs(bestShift))) {
				bestShift = shift;
			}
		}
		if (bestShift == Integer.MAX_VALUE) {
			minecraft.gui.hud.setOverlayMessage(
				Component.literal("That selection spans more than Minecraft's 25-note range."), true
			);
			return;
		}
		transposeSelected(bestShift);
	}

	private void convertToMinecraft() {
		stopPlayback();
		int gridTicks = minecraftConversionGridTicks();
		boolean snapTempo = config.midiTempoFit() == FastNoteblocksConfig.MidiTempoFit.SNAP_TO_REPEATERS;
		try {
			MinecraftConversion conversion = project().convertToMinecraft(gridTicks, snapTempo);
			if (conversion.project().equals(project())) {
				minecraft.gui.hud.setOverlayMessage(
					Component.literal("This composition is already Minecraft-ready."), true
				);
				return;
			}
			apply(conversion.project());
			selectedNotes.clear();
			instrumentMenuLayer = -1;
			centerMinecraftRange();
			rebuildLayerButtons();
			rebuildMoveLayerButtons();
			String report = "Converted at " + conversionGridLabel(gridTicks)
				+ ": " + conversion.shiftedNotes() + " pitch-shifted"
				+ (conversion.addedLayers() > 0 ? ", +" + conversion.addedLayers() + " layers" : "")
				+ (conversion.tempoChanged() ? ", tempo aligned to repeaters" : "");
			minecraft.gui.hud.setOverlayMessage(Component.literal(report), true);
		} catch (IllegalStateException exception) {
			minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(this),
				Component.literal("Too many converted layers"),
				Component.literal(exception.getMessage()),
				CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
		}
	}

	private int minecraftConversionGridTicks() {
		return switch (config.midiQuantizeGrid()) {
			case QUARTER -> project().ppq();
			case EIGHTH -> Math.max(1, project().ppq() / 2);
			case SIXTEENTH -> Math.max(1, project().ppq() / 4);
			case AUTO -> automaticConversionGridTicks();
		};
	}

	private int automaticConversionGridTicks() {
		List<Long> starts = project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
		long smallestGap = Long.MAX_VALUE;
		for (int index = 1; index < starts.size(); index++) {
			long gap = starts.get(index) - starts.get(index - 1);
			if (gap > 0 && gap < smallestGap) {
				smallestGap = gap;
			}
		}
		if (smallestGap <= Math.max(1, project().ppq() * 3L / 8L)) {
			return Math.max(1, project().ppq() / 4);
		}
		if (smallestGap <= Math.max(1, project().ppq() * 3L / 4L)) {
			return Math.max(1, project().ppq() / 2);
		}
		return project().ppq();
	}

	private String conversionGridLabel(int gridTicks) {
		if (gridTicks <= Math.max(1, project().ppq() / 4)) {
			return "1/16";
		}
		if (gridTicks <= Math.max(1, project().ppq() / 2)) {
			return "1/8";
		}
		return "1/4";
	}

	private void cycleSnap() {
		snapSubdivision = switch (snapSubdivision) {
			case 1 -> 2;
			case 2 -> 4;
			case 4 -> 8;
			case 8 -> 0;
			default -> 1;
		};
		snapButton.setMessage(snapLabel());
	}

	private Component snapLabel() {
		return Component.literal(switch (snapSubdivision) {
			case 1 -> "Snap 1/4";
			case 2 -> "Snap 1/8";
			case 4 -> "Snap 1/16";
			case 8 -> "Snap 1/32";
			default -> "Snap off";
		});
	}

	private void importSong() {
		boolean nonempty = project().layers().stream().anyMatch(layer -> !layer.notes().isEmpty());
		if (!nonempty) {
			chooseAndImportSong();
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.gui.setScreen(this);
			if (confirmed) {
				chooseAndImportSong();
			}
		}, Component.literal("Replace this composition?"),
			Component.literal("Importing replaces the current piano roll. You can still Undo afterward."),
			Component.literal("Import"), CommonComponents.GUI_CANCEL));
	}

	private void chooseAndImportSong() {
		String path;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(3);
			filters.put(stack.UTF8("*.mid"));
			filters.put(stack.UTF8("*.midi"));
			filters.put(stack.UTF8("*.nbs"));
			filters.flip();
			path = TinyFileDialogs.tinyfd_openFileDialog(
				"Import MIDI or NBS", "", filters, "MIDI and Note Block Studio songs", false
			);
		}
		if (path == null || path.isBlank()) {
			return;
		}
		try {
			String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
			ComposerProject imported;
			String report;
			if (lowerPath.endsWith(".nbs")) {
				NbsImporter.Inspection inspection = NbsImporter.inspect(path, config);
				if (inspection.instruments().size() > ComposerProject.MAX_LAYERS) {
					minecraft.gui.setScreen(new NbsInstrumentSelectionScreen(this, inspection,
						selected -> importSelectedNbs(path, selected)));
					return;
				}
				NbsImporter.ProjectResult result = NbsImporter.importProject(path, config);
				imported = result.project();
				report = result.report();
			} else {
				MidiImporter.ProjectResult result = MidiImporter.importProject(path, config);
				imported = result.project();
				report = result.report();
			}
			applyImportedProject(imported, report);
		} catch (Exception exception) {
			showImportFailure(exception);
		}
	}

	private void importSelectedNbs(String path, Set<String> selectedInstruments) {
		minecraft.gui.setScreen(this);
		try {
			NbsImporter.ProjectResult result = NbsImporter.importProject(path, config, selectedInstruments);
			applyImportedProject(result.project(), result.report());
		} catch (Exception exception) {
			showImportFailure(exception);
		}
	}

	private void applyImportedProject(ComposerProject imported, String report) {
		apply(imported);
		selectedNotes.clear();
		horizontalScroll = 0L;
		centerMinecraftRange();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		minecraft.gui.hud.setOverlayMessage(Component.literal(report), true);
	}

	private void showImportFailure(Exception exception) {
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(this),
			Component.literal("Song import failed"),
			Component.literal(exception.getMessage() == null
				? exception.getClass().getSimpleName()
				: exception.getMessage()),
			CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(graphics);
		extractTransparentBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		rollX = LAYER_PANEL_WIDTH + PIANO_WIDTH;
		rollY = TOOLBAR_HEIGHT + 14 + TIMELINE_RULER_HEIGHT;
		rollWidth = Math.max(40, width - rollX - 8);
		rollHeight = Math.max(40, height - rollY - 24);
		extractPanels(graphics);
		extractTimeRuler(graphics, mouseX, mouseY);
		extractPianoRoll(graphics, mouseX, mouseY);
		extractStatus(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		extractInstrumentMenu(graphics, mouseX, mouseY);
		extractContextMenu(graphics, mouseX, mouseY);
		extractToolbarMenu(graphics, mouseX, mouseY);
	}

	private void extractToolbarMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ToolbarAction[] actions = toolbarActions();
		if (actions.length == 0) {
			return;
		}
		int menuY = 28;
		int menuHeight = actions.length * TOOLBAR_MENU_ROW_HEIGHT + 4;
		graphics.fill(toolbarMenuX, menuY, toolbarMenuX + TOOLBAR_MENU_WIDTH, menuY + menuHeight, 0xF0101115);
		graphics.fill(toolbarMenuX, menuY, toolbarMenuX + TOOLBAR_MENU_WIDTH, menuY + 1, 0xFFAAAAAA);
		for (int index = 0; index < actions.length; index++) {
			ToolbarAction action = actions[index];
			int rowY = menuY + 2 + index * TOOLBAR_MENU_ROW_HEIGHT;
			boolean enabled = toolbarActionEnabled(action);
			boolean hovered = enabled && mouseX >= toolbarMenuX
				&& mouseX < toolbarMenuX + TOOLBAR_MENU_WIDTH
				&& mouseY >= rowY && mouseY < rowY + TOOLBAR_MENU_ROW_HEIGHT;
			if (hovered) {
				graphics.fill(toolbarMenuX + 2, rowY, toolbarMenuX + TOOLBAR_MENU_WIDTH - 2,
					rowY + TOOLBAR_MENU_ROW_HEIGHT, 0xFF356070);
			}
			graphics.text(font, Component.literal(action.label), toolbarMenuX + 6, rowY + 5,
				enabled ? 0xFFFFFFFF : 0xFF777777, false);
		}
	}

	private void toggleToolbarMenu(ToolbarMenu menu, int x) {
		if (toolbarMenu == menu) {
			toolbarMenu = ToolbarMenu.NONE;
			return;
		}
		toolbarMenu = menu;
		toolbarMenuX = x;
		contextMenuOpen = false;
		instrumentMenuLayer = -1;
	}

	private ToolbarAction[] toolbarActions() {
		return switch (toolbarMenu) {
			case FILE -> ToolbarAction.FILE_ACTIONS;
			case EDIT -> ToolbarAction.EDIT_ACTIONS;
			case NONE -> new ToolbarAction[0];
		};
	}

	private boolean toolbarActionEnabled(ToolbarAction action) {
		return switch (action) {
			case UNDO -> history.canUndo();
			case REDO -> history.canRedo();
			case CONVERT -> project().layers().stream().anyMatch(layer -> !layer.notes().isEmpty());
			default -> true;
		};
	}

	private boolean handleToolbarMenuClick(double mouseX, double mouseY) {
		ToolbarAction[] actions = toolbarActions();
		if (actions.length == 0 || mouseX < toolbarMenuX
				|| mouseX >= toolbarMenuX + TOOLBAR_MENU_WIDTH) {
			return false;
		}
		int row = ((int)mouseY - 30) / TOOLBAR_MENU_ROW_HEIGHT;
		if (row < 0 || row >= actions.length) {
			return false;
		}
		int rowY = 30 + row * TOOLBAR_MENU_ROW_HEIGHT;
		if (mouseY < rowY || mouseY >= rowY + TOOLBAR_MENU_ROW_HEIGHT) {
			return false;
		}
		ToolbarAction action = actions[row];
		if (!toolbarActionEnabled(action)) {
			return true;
		}
		toolbarMenu = ToolbarMenu.NONE;
		performToolbarAction(action);
		return true;
	}

	private void performToolbarAction(ToolbarAction action) {
		switch (action) {
			case IMPORT -> importSong();
			case BACK_TO_SEQUENCES -> onClose();
			case CLOSE_TO_GAME -> closeToGame();
			case UNDO -> undo();
			case REDO -> redo();
			case CONVERT -> convertToMinecraft();
		}
	}

	private void extractContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!contextMenuOpen || selectedNotes.isEmpty()) {
			return;
		}
		ContextAction[] actions = ContextAction.values();
		int height = actions.length * CONTEXT_MENU_ROW_HEIGHT + 4;
		graphics.fill(contextMenuX, contextMenuY, contextMenuX + CONTEXT_MENU_WIDTH, contextMenuY + height, 0xF0101115);
		graphics.fill(contextMenuX, contextMenuY, contextMenuX + CONTEXT_MENU_WIDTH, contextMenuY + 1, 0xFFAAAAAA);
		for (int index = 0; index < actions.length; index++) {
			int rowY = contextMenuY + 2 + index * CONTEXT_MENU_ROW_HEIGHT;
			boolean hovered = mouseX >= contextMenuX && mouseX < contextMenuX + CONTEXT_MENU_WIDTH
				&& mouseY >= rowY && mouseY < rowY + CONTEXT_MENU_ROW_HEIGHT;
			if (hovered) {
				graphics.fill(contextMenuX + 2, rowY, contextMenuX + CONTEXT_MENU_WIDTH - 2,
					rowY + CONTEXT_MENU_ROW_HEIGHT, 0xFF356070);
			}
			graphics.text(font, Component.literal(actions[index].label), contextMenuX + 6, rowY + 4,
				0xFFFFFFFF, false);
		}
	}

	private void extractInstrumentMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (instrumentMenuLayer < 0 || instrumentMenuLayer >= project().layers().size()) {
			return;
		}
		int columns = 6;
		int cell = 28;
		int menuWidth = columns * cell + 6;
		int rows = (PreviewInstrument.VALUES.size() + columns - 1) / columns;
		int menuHeight = rows * cell + 6;
		int menuX = 8;
		int requestedY = layerY(instrumentMenuLayer) + LAYER_ROW_HEIGHT;
		int menuY = Math.max(TOOLBAR_HEIGHT + 4, Math.min(height - menuHeight - 24, requestedY));
		graphics.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, 0xF0101115);
		graphics.fill(menuX, menuY, menuX + menuWidth, menuY + 1, 0xFFAAAAAA);
		PreviewInstrument selected = PreviewInstrument.byId(project().layers().get(instrumentMenuLayer).instrument());
		for (int index = 0; index < PreviewInstrument.VALUES.size(); index++) {
			PreviewInstrument value = PreviewInstrument.VALUES.get(index);
			int cellX = menuX + 3 + index % columns * cell;
			int cellY = menuY + 3 + index / columns * cell;
			boolean hovered = mouseX >= cellX && mouseX < cellX + cell
				&& mouseY >= cellY && mouseY < cellY + cell;
			graphics.fill(cellX, cellY, cellX + cell - 2, cellY + cell - 2,
				value.equals(selected) ? 0xFF356070 : hovered ? 0xFF44484F : 0xFF25282D);
			graphics.item(new ItemStack(value.icon()), cellX + 5, cellY + 5);
			if (hovered) {
				graphics.setTooltipForNextFrame(Component.literal(value.name()), mouseX, mouseY);
			}
		}
	}

	private void extractPanels(GuiGraphicsExtractor graphics) {
		graphics.fill(0, TOOLBAR_HEIGHT, LAYER_PANEL_WIDTH, height, 0xB8101115);
		graphics.fill(LAYER_PANEL_WIDTH, TOOLBAR_HEIGHT, width, height, 0x99101115);
		graphics.text(font, title, 8, TOOLBAR_HEIGHT + 4, 0xFFFFFFFF, false);
		for (int index = 0; index < project().layers().size(); index++) {
			int y = layerY(index);
			int color = LAYER_COLORS[index % LAYER_COLORS.length];
			boolean activeLayer = index == project().activeLayerIndex();
			graphics.fill(8, y - 2, LAYER_PANEL_WIDTH - 8, y + LAYER_ROW_HEIGHT - 2,
				activeLayer ? 0x66303740 : 0x44252A31);
			graphics.fill(8, y - 2, LAYER_PANEL_WIDTH - 8, y - 1, activeLayer ? color : 0x66383D44);
			graphics.fill(8, y + LAYER_ROW_HEIGHT - 3, LAYER_PANEL_WIDTH - 8, y + LAYER_ROW_HEIGHT - 2,
				activeLayer ? color : 0x88383D44);
			graphics.fill(8, y - 2, 12, y + LAYER_ROW_HEIGHT - 2, color);
			if (activeLayer) {
				graphics.fill(12, y, LAYER_PANEL_WIDTH - 10, y + 20, 0x553D444D);
			}
			String marker = activeLayer ? "> " : "";
			graphics.text(font, Component.literal(marker + "L" + (index + 1) + "  " + project().layers().get(index).name()),
				22, y + 6, activeLayer ? 0xFFFFFFFF : 0xFFD6D8DD, false);
		}
		int active = project().activeLayerIndex();
		graphics.text(font, Component.literal("Active: Layer " + (active + 1)), 8, height - 18,
			LAYER_COLORS[active % LAYER_COLORS.length], false);
	}

	private void extractTimeRuler(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int rulerY = rollY - TIMELINE_RULER_HEIGHT;
		graphics.fill(rollX, rulerY, rollX + rollWidth, rollY, 0xCC15181D);
		graphics.fill(rollX, rollY - 1, rollX + rollWidth, rollY, 0xFF4A4F56);
		long lastTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		long beatTicks = Math.max(1L, project().ppq());
		long firstBeat = Math.max(0L, horizontalScroll / beatTicks);
		for (long beat = firstBeat; beat * beatTicks <= lastTick + beatTicks; beat++) {
			long tick = beat * beatTicks;
			int x = tickX(tick);
			if (x < rollX || x > rollX + rollWidth) {
				continue;
			}
			boolean measure = beat % 4L == 0L;
			graphics.fill(x, rulerY + (measure ? 1 : 6), x + 1, rollY, measure ? 0xFF9A9A9A : 0xFF686D73);
			if (measure) {
				graphics.text(font, Long.toString(beat / 4L + 1L), x + 3, rulerY + 2, 0xFFBFC4CA, false);
			}
		}
		long markerTick = playing ? playbackTick() : playbackStartTick;
		int markerX = tickX(markerTick);
		if (markerX >= rollX && markerX <= rollX + rollWidth) {
			graphics.fill(markerX - 3, rulerY + 1, markerX + 4, rulerY + 5, 0xFFFF5555);
			graphics.fill(markerX - 1, rulerY + 5, markerX + 2, rollY, 0xFFFF5555);
		}
		if (mouseX >= rollX && mouseX < rollX + rollWidth && mouseY >= rulerY && mouseY < rollY) {
			graphics.setTooltipForNextFrame(Component.literal("Drag to set playback start"), mouseX, mouseY);
		}
	}

	private void extractPianoRoll(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int pianoX = LAYER_PANEL_WIDTH;
		graphics.enableScissor(pianoX, rollY, rollX + rollWidth, rollY + rollHeight);
		for (int midi = topMidiNote; midi >= MIN_MIDI_NOTE; midi--) {
			int y = noteY(midi);
			if (y >= rollY + rollHeight) {
				break;
			}
			if (y + ROW_HEIGHT < rollY) {
				continue;
			}
			boolean black = isBlackKey(midi);
			boolean buildable = midi >= ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
				&& midi <= ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE;
			int gridColor = black ? 0xB9181A20 : 0xB91D2026;
			if (buildable) {
				gridColor = black ? 0xC31C3B42 : 0xC322454C;
			}
			graphics.fill(pianoX, y, rollX, y + ROW_HEIGHT - 1, 0xFFE7E7E7);
			if (black) {
				graphics.fill(pianoX, y, pianoX + PIANO_WIDTH * 2 / 3, y + ROW_HEIGHT - 1, 0xFF303238);
			}
			graphics.fill(pianoX, y + ROW_HEIGHT - 1, rollX, y + ROW_HEIGHT, 0xFF55575C);
			graphics.fill(rollX, y, rollX + rollWidth, y + ROW_HEIGHT - 1, gridColor);
			if (midi % 12 == 0) {
				graphics.text(font, midiName(midi), pianoX + 2, y + 2, 0xFF222222, false);
			}
		}

		extractTimeGrid(graphics);
		extractNotes(graphics, mouseX, mouseY);
		extractPlayhead(graphics);
		if (selectingBox) {
			int left = (int)Math.min(dragStartX, selectionEndX);
			int right = (int)Math.max(dragStartX, selectionEndX);
			int top = (int)Math.min(dragStartY, selectionEndY);
			int bottom = (int)Math.max(dragStartY, selectionEndY);
			graphics.fill(left, top, right, bottom, 0x3344CCFF);
			graphics.fill(left, top, right, top + 1, 0xFF55FFFF);
			graphics.fill(left, bottom - 1, right, bottom, 0xFF55FFFF);
			graphics.fill(left, top, left + 1, bottom, 0xFF55FFFF);
			graphics.fill(right - 1, top, right, bottom, 0xFF55FFFF);
		}
		graphics.disableScissor();

		graphics.text(font, "Minecraft F♯3–F♯5", rollX + 5, TOOLBAR_HEIGHT + 3, 0xFF65F4FF, false);
	}

	private void extractTimeGrid(GuiGraphicsExtractor graphics) {
		long subdivisionTicks = snapSubdivision == 0
			? Math.max(1L, project().ppq() / 4L)
			: Math.max(1L, project().ppq() / snapSubdivision);
		long lastTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		long firstGrid = Math.max(0L, horizontalScroll / subdivisionTicks);
		for (long grid = firstGrid; grid * subdivisionTicks <= lastTick + subdivisionTicks; grid++) {
			long tick = grid * subdivisionTicks;
			int x = tickX(tick);
			if (x < rollX || x > rollX + rollWidth) {
				continue;
			}
			boolean beat = tick % project().ppq() == 0;
			boolean measure = tick % (project().ppq() * 4L) == 0;
			graphics.fill(x, rollY, x + 1, rollY + rollHeight,
				measure ? 0x66777777 : beat ? 0x443F444A : 0x242F343A);
			if (measure) {
				graphics.text(font, Long.toString(tick / (project().ppq() * 4L) + 1),
					x + 3, rollY + 2, 0xFFAAAAAA, false);
			}
		}
		for (Map.Entry<Long, Integer> entry : projectStats().chordCounts().entrySet()) {
			if (entry.getValue() <= MAX_SIMULTANEOUS_NOTES) {
				continue;
			}
			int x = tickX(entry.getKey());
			if (x >= rollX && x <= rollX + rollWidth) {
				graphics.fill(x - 1, rollY, x + 2, rollY + rollHeight, 0x66FF3333);
			}
		}
	}

	private void extractNotes(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ComposerProject shown = displayProject();
		long firstVisibleTick = Math.max(0L, horizontalScroll - projectStats().maximumNoteDuration());
		long lastVisibleTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		for (int layerIndex = 0; layerIndex < shown.layers().size(); layerIndex++) {
			Layer layer = shown.layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			boolean active = layerIndex == shown.activeLayerIndex();
			List<NoteEvent> notes = layer.notes();
			for (int noteIndex = lowerBoundStart(notes, firstVisibleTick);
					noteIndex < notes.size(); noteIndex++) {
				NoteEvent note = notes.get(noteIndex);
				if (note.startTick() > lastVisibleTick) {
					break;
				}
				NoteRect rect = noteRect(note);
				if (!rect.intersects(rollX, rollY, rollX + rollWidth, rollY + rollHeight)) {
					continue;
				}
				boolean selected = selectedNotes.contains(note.id());
				int color = active ? LAYER_COLORS[layerIndex % LAYER_COLORS.length] : 0xFF777A80;
				if (!note.isBuildable()) {
					color = active ? 0xFFFF6B6B : 0xFF755050;
				}
				if (selected) {
					graphics.fill(rect.left - 1, rect.top - 1, rect.right + 1, rect.bottom + 1, 0xFFFFFFFF);
				}
				graphics.fill(rect.left, rect.top, rect.right, rect.bottom, color);
				if (active && mouseX >= rect.left && mouseX < rect.right && mouseY >= rect.top && mouseY < rect.bottom) {
					graphics.setTooltipForNextFrame(Component.literal(
						midiName(note.midiNote()) + "  tick " + note.startTick()
							+ (note.isBuildable() ? "  note " + note.noteBlockPitch() : "  outside Minecraft range")
					), mouseX, mouseY);
				}
			}
		}
	}

	private void extractPlayhead(GuiGraphicsExtractor graphics) {
		long tick = playing ? playbackTick() : playbackStartTick;
		int x = tickX(tick);
		if (x >= rollX && x <= rollX + rollWidth) {
			graphics.fill(x, rollY, x + 2, rollY + rollHeight, 0xFFFF5555);
		}
	}

	private void extractStatus(GuiGraphicsExtractor graphics) {
		ProjectStats stats = projectStats();
		int outOfRange = stats.outOfRange();
		int peakChord = stats.peakChord();
		long overloaded = stats.overloadedTicks();
		String status = selectedNotes.size() + " selected"
			+ (outOfRange > 0 ? "   " + outOfRange + " outside Minecraft range" : "")
			+ "   Build peak " + peakChord + "/" + MAX_SIMULTANEOUS_NOTES
			+ (overloaded > 0 ? " (" + overloaded + " overloaded)" : "")
			+ "   Double-click to add • drag to move • right-click deletes or opens selection actions";
		int color = overloaded > 0 || outOfRange > 0
			? 0xFFFF7777
			: peakChord >= CHORD_WARNING_THRESHOLD ? 0xFFFFAA00 : 0xFFBBBBBB;
		graphics.text(font, status, rollX, height - 16, color, false);
	}

	private ProjectStats projectStats() {
		ComposerProject current = project();
		if (cachedStatsProject == current && cachedStats != null) {
			return cachedStats;
		}
		Map<Long, Integer> counts = new HashMap<>();
		int outOfRange = 0;
		long maximumNoteDuration = 1L;
		for (Layer layer : current.layers()) {
			for (NoteEvent note : layer.notes()) {
				maximumNoteDuration = Math.max(maximumNoteDuration, note.durationTicks());
				if (!note.isBuildable()) {
					outOfRange++;
				} else if (layer.buildEnabled() && !layer.muted()) {
					counts.merge(note.startTick(), 1, Integer::sum);
				}
			}
		}
		int peak = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		long overloaded = counts.values().stream().filter(count -> count > MAX_SIMULTANEOUS_NOTES).count();
		cachedStatsProject = current;
		cachedStats = new ProjectStats(outOfRange, Map.copyOf(counts), peak, overloaded, maximumNoteDuration);
		return cachedStats;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (toolbarMenu != ToolbarMenu.NONE) {
			if (handleToolbarMenuClick(event.x(), event.y())) {
				return true;
			}
			toolbarMenu = ToolbarMenu.NONE;
		}
		if (contextMenuOpen) {
			if (handleContextMenuClick(event.x(), event.y())) {
				return true;
			}
			contextMenuOpen = false;
		}
		if (event.button() == 0 && doubleClick) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0) {
				beginLayerRename(layerIndex);
				return true;
			}
		}
		if (layerNameBox != null && !layerNameBox.isMouseOver(event.x(), event.y())) {
			commitLayerRename();
		}
		if (event.button() == 0 && handleInstrumentMenuClick(event.x(), event.y())) {
			return true;
		}
		if (event.button() == 0) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0) {
				apply(project().withActiveLayer(layerIndex));
				selectedNotes.clear();
				rebuildLayerButtons();
				return true;
			}
		}
		if (event.button() == 0 && insideRuler(event.x(), event.y())) {
			setPlaybackStart(mouseTick(event.x()), true);
			draggingPlayhead = true;
			return true;
		}
		if (event.button() == 1 && insideRoll(event.x(), event.y())) {
			NoteHit hit = noteAt(event.x(), event.y());
			if (hit != null) {
				if (selectedNotes.isEmpty()) {
					apply(project().deleteNotes(Set.of(hit.note().id())));
					return true;
				}
				if (hit.layerIndex() != project().activeLayerIndex()) {
					apply(project().withActiveLayer(hit.layerIndex()));
					rebuildLayerButtons();
				}
				if (!selectedNotes.contains(hit.note().id())) {
					selectedNotes.clear();
					selectedNotes.add(hit.note().id());
				}
				openContextMenu(event.x(), event.y());
				return true;
			}
			if (!selectedNotes.isEmpty()) {
				openContextMenu(event.x(), event.y());
				return true;
			}
		}
		if (event.button() != 0 || !insideRoll(event.x(), event.y())) {
			return super.mouseClicked(event, doubleClick);
		}
		NoteHit hit = noteAt(event.x(), event.y());
		if (hit != null) {
			if (hit.layerIndex() != project().activeLayerIndex()) {
				selectedNotes.clear();
				apply(project().withActiveLayer(hit.layerIndex()));
				rebuildLayerButtons();
			}
			NoteEvent hitNote = hit.note();
			if (!selectedNotes.contains(hitNote.id())) {
				if (!event.hasControlDownWithQuirk()) {
					selectedNotes.clear();
				}
				selectedNotes.add(hitNote.id());
			} else if (event.hasControlDownWithQuirk()) {
				selectedNotes.remove(hitNote.id());
			}
			if (selectedNotes.contains(hitNote.id())) {
				draggingNotes = true;
				dragStartX = event.x();
				dragStartY = event.y();
				dragBase = project();
				dragPreview = null;
				dragTickDelta = 0L;
				dragPitchDelta = 0;
				PreviewInstrument.byId(activeLayer().instrument()).play(hitNote.midiNote()
					- ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE);
			}
			return true;
		}
		if (doubleClick) {
			int midi = mouseMidi(event.y());
			long tick = snapTick(mouseTick(event.x()));
			apply(project().addNote(project().activeLayerIndex(), midi, tick, project().ppq() / 4L));
			NoteEvent added = activeLayer().notes().stream().max(Comparator.comparingLong(NoteEvent::id)).orElse(null);
			selectedNotes.clear();
			if (added != null) {
				selectedNotes.add(added.id());
				PreviewInstrument.byId(activeLayer().instrument()).play(
					added.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
				);
			}
			return true;
		}
		selectingBox = true;
		dragStartX = selectionEndX = event.x();
		dragStartY = selectionEndY = event.y();
		if (!event.hasControlDownWithQuirk()) {
			selectedNotes.clear();
		}
		return true;
	}

	private boolean handleInstrumentMenuClick(double mouseX, double mouseY) {
		if (instrumentMenuLayer < 0 || instrumentMenuLayer >= project().layers().size()) {
			return false;
		}
		int columns = 6;
		int cell = 28;
		int rows = (PreviewInstrument.VALUES.size() + columns - 1) / columns;
		int menuX = 8;
		int requestedY = layerY(instrumentMenuLayer) + LAYER_ROW_HEIGHT;
		int menuY = Math.max(TOOLBAR_HEIGHT + 4, Math.min(height - (rows * cell + 6) - 24, requestedY));
		int column = (int)(mouseX - menuX - 3) / cell;
		int row = (int)(mouseY - menuY - 3) / cell;
		if (mouseX < menuX + 3 || mouseY < menuY + 3 || column < 0 || column >= columns || row < 0) {
			return false;
		}
		int index = row * columns + column;
		if (index < 0 || index >= PreviewInstrument.VALUES.size()) {
			return false;
		}
		PreviewInstrument value = PreviewInstrument.VALUES.get(index);
		value.play(12);
		Layer layer = project().layers().get(instrumentMenuLayer);
		updateLayer(instrumentMenuLayer,
			layer.withInstrument(value.id()).withMuted("MUTE".equals(value.id())));
		return true;
	}

	private void openContextMenu(double mouseX, double mouseY) {
		ContextAction[] actions = ContextAction.values();
		int menuHeight = actions.length * CONTEXT_MENU_ROW_HEIGHT + 4;
		contextMenuX = Math.max(4, Math.min(width - CONTEXT_MENU_WIDTH - 4, (int)mouseX));
		contextMenuY = Math.max(TOOLBAR_HEIGHT + 4, Math.min(height - menuHeight - 4, (int)mouseY));
		contextMenuOpen = true;
	}

	private boolean handleContextMenuClick(double mouseX, double mouseY) {
		if (mouseX < contextMenuX || mouseX >= contextMenuX + CONTEXT_MENU_WIDTH) {
			return false;
		}
		int row = ((int)mouseY - contextMenuY - 2) / CONTEXT_MENU_ROW_HEIGHT;
		ContextAction[] actions = ContextAction.values();
		if (row < 0 || row >= actions.length) {
			return false;
		}
		int rowY = contextMenuY + 2 + row * CONTEXT_MENU_ROW_HEIGHT;
		if (mouseY < rowY || mouseY >= rowY + CONTEXT_MENU_ROW_HEIGHT) {
			return false;
		}
		performContextAction(actions[row]);
		contextMenuOpen = false;
		return true;
	}

	private void performContextAction(ContextAction action) {
		switch (action) {
			case OCTAVE_DOWN -> transposeSelected(-12);
			case OCTAVE_UP -> transposeSelected(12);
			case FIT_RANGE -> fitSelectedToMinecraft();
			case COPY -> copySelection();
			case CUT -> {
				copySelection();
				deleteSelectedNotes();
			}
			case DELETE -> deleteSelectedNotes();
		}
	}

	@Override
	public void mouseMoved(double x, double y) {
		lastMouseX = x;
		lastMouseY = y;
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingPlayhead) {
			setPlaybackStart(mouseTick(event.x()), false);
			return true;
		}
		if (draggingNotes) {
			long tickDelta = snapDelta(Math.round((event.x() - dragStartX) * ticksPerPixel));
			int pitchDelta = (int)Math.round((dragStartY - event.y()) / ROW_HEIGHT);
			if (tickDelta != dragTickDelta || pitchDelta != dragPitchDelta) {
				dragTickDelta = tickDelta;
				dragPitchDelta = pitchDelta;
				dragPreview = dragBase.moveNotes(selectedNotes, tickDelta, pitchDelta);
			}
			return true;
		}
		if (selectingBox) {
			selectionEndX = Math.max(rollX, Math.min(rollX + rollWidth, event.x()));
			selectionEndY = Math.max(rollY, Math.min(rollY + rollHeight, event.y()));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingPlayhead) {
			draggingPlayhead = false;
			return true;
		}
		if (draggingNotes) {
			draggingNotes = false;
			if (dragPreview != null) {
				apply(dragPreview);
			}
			dragPreview = null;
			dragBase = null;
			return true;
		}
		if (selectingBox) {
			selectingBox = false;
			selectNotesInBox();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= LAYER_PANEL_WIDTH && mouseX < rollX
				&& mouseY >= rollY && mouseY < rollY + rollHeight) {
			topMidiNote = Math.max(12, Math.min(MAX_MIDI_NOTE,
				topMidiNote + (scrollY > 0 ? 3 : -3)));
			return true;
		}
		if (!insideRoll(mouseX, mouseY)) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		if (controlDown()) {
			long anchoredTick = mouseTick(mouseX);
			ticksPerPixel = Math.max(1.5, Math.min(80.0,
				ticksPerPixel * (scrollY > 0 ? 0.8 : 1.25)));
			horizontalScroll = Math.max(0L,
				anchoredTick - Math.round((mouseX - rollX) * ticksPerPixel));
			return true;
		}
		horizontalScroll = Math.max(0L, horizontalScroll - Math.round(scrollY * project().ppq()));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (toolbarMenu != ToolbarMenu.NONE && event.isEscape()) {
			toolbarMenu = ToolbarMenu.NONE;
			return true;
		}
		if (contextMenuOpen && event.isEscape()) {
			contextMenuOpen = false;
			return true;
		}
		if (layerNameBox != null) {
			if (event.isConfirmation()) {
				commitLayerRename();
				return true;
			}
			if (event.isEscape()) {
				cancelLayerRename();
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_SPACE && layerNameBox == null) {
			if (playing || project().layers().stream()
					.anyMatch(layer -> !layer.muted() && !layer.notes().isEmpty())) {
				togglePlayback();
			}
			return true;
		}
		if (event.isSelectAll()) {
			selectedNotes.clear();
			activeLayer().notes().forEach(note -> selectedNotes.add(note.id()));
			return true;
		}
		if (event.isCopy()) {
			copySelection();
			return true;
		}
		if (event.isCut()) {
			copySelection();
			deleteSelectedNotes();
			return true;
		}
		if (event.isPaste()) {
			pasteClipboard();
			return true;
		}
		if (event.hasControlDownWithQuirk() && event.key() == GLFW.GLFW_KEY_Z) {
			if (event.hasShiftDown()) {
				redo();
			} else {
				undo();
			}
			return true;
		}
		if (event.hasControlDownWithQuirk() && event.key() == GLFW.GLFW_KEY_Y) {
			redo();
			return true;
		}
		int digit = event.getDigit();
		if (event.hasControlDownWithQuirk() && digit >= 0) {
			int targetLayer = digit == 0 ? 9 : digit - 1;
			if (targetLayer >= 0 && targetLayer < ComposerProject.MAX_LAYERS) {
				moveSelectionToLayer(targetLayer);
				return true;
			}
		}
		if ((event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE)
				&& !selectedNotes.isEmpty()) {
			deleteSelectedNotes();
			return true;
		}
		if (!selectedNotes.isEmpty() && (event.isLeft() || event.isRight() || event.isUp() || event.isDown())) {
			long tickDelta = event.isLeft() ? -gridTicks() : event.isRight() ? gridTicks() : 0L;
			int pitchDelta = event.isUp() ? (event.hasControlDownWithQuirk() ? 12 : 1)
				: event.isDown() ? (event.hasControlDownWithQuirk() ? -12 : -1) : 0;
			apply(project().moveNotes(selectedNotes, tickDelta, pitchDelta));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void tick() {
		updatePlayback();
		updateButtonStates();
	}

	@Override
	public void onClose() {
		stopPlayback();
		saveProject();
		onReturn.run();
		minecraft.gui.setScreen(parent);
	}

	private void closeToGame() {
		stopPlayback();
		saveProject();
		onReturn.run();
		minecraft.gui.setScreen(null);
	}

	private void togglePlayback() {
		if (playing) {
			stopPlayback();
		} else {
			playing = true;
			playbackStartedAt = Util.getMillis();
			resetPlaybackCursors();
			playButton.setMessage(playLabel());
		}
	}

	private void setPlaybackStart(long tick, boolean preview) {
		playbackStartTick = Math.max(0L, Math.min(project().endTick(), snapTick(tick)));
		if (playing) {
			playbackStartedAt = Util.getMillis();
			resetPlaybackCursors();
		}
		if (preview) {
			minecraft.gui.hud.setOverlayMessage(Component.literal("Playback start: tick " + playbackStartTick), true);
		}
	}

	private void stopPlayback() {
		playing = false;
		playbackIndices = new int[0];
		if (playButton != null) {
			playButton.setMessage(playLabel());
		}
		if (addLayerButton != null) {
			addLayerButton.active = project().layers().size() < ComposerProject.MAX_LAYERS;
		}
		for (int index = 1; index < moveLayerButtons.size(); index++) {
			moveLayerButtons.get(index).active = index - 1 < project().layers().size()
				&& !selectedNotes.isEmpty();
		}
	}

	private void updatePlayback() {
		if (!playing) {
			return;
		}
		long tick = playbackTick();
		if (playbackIndices.length != project().layers().size()) {
			resetPlaybackCursors();
		}
		for (int layerIndex = 0; layerIndex < project().layers().size(); layerIndex++) {
			Layer layer = project().layers().get(layerIndex);
			PreviewInstrument instrument = PreviewInstrument.byId(layer.instrument());
			List<NoteEvent> notes = layer.notes();
			int noteIndex = playbackIndices[layerIndex];
			while (noteIndex < notes.size() && notes.get(noteIndex).startTick() <= tick) {
				NoteEvent note = notes.get(noteIndex++);
				if (!layer.muted() && note.startTick() >= playbackStartTick) {
					instrument.play(note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE);
				}
			}
			playbackIndices[layerIndex] = noteIndex;
		}
		if (tick > project().endTick()) {
			stopPlayback();
			return;
		}
		int x = tickX(tick);
		if (x > rollX + rollWidth - 40) {
			horizontalScroll = Math.max(0L, tick - Math.round(rollWidth * ticksPerPixel * 0.2));
		}
	}

	private long playbackTick() {
		long elapsedMicros = Math.max(0L, Util.getMillis() - playbackStartedAt) * 1000L;
		return playbackStartTick + Math.round(elapsedMicros * project().ppq() / (double)project().tempoMicrosPerQuarter());
	}

	private void resetPlaybackCursors() {
		playbackIndices = new int[project().layers().size()];
		for (int layerIndex = 0; layerIndex < project().layers().size(); layerIndex++) {
			playbackIndices[layerIndex] = lowerBoundStart(
				project().layers().get(layerIndex).notes(), playbackStartTick
			);
		}
	}

	private void undo() {
		history.undo();
		playbackStartTick = Math.min(playbackStartTick, project().endTick());
		selectedNotes.clear();
		saveProject();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void redo() {
		history.redo();
		playbackStartTick = Math.min(playbackStartTick, project().endTick());
		selectedNotes.clear();
		saveProject();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void apply(ComposerProject project) {
		history.apply(project);
		playbackStartTick = Math.min(playbackStartTick, project.endTick());
		if (playing) {
			playbackStartedAt = Util.getMillis();
			resetPlaybackCursors();
		}
		saveProject();
		updateButtonStates();
	}

	private void saveProject() {
		config.setComposerProject(project());
		FastNoteblocksConfig.save();
	}

	private void setDelayScale(int scaleQuarters) {
		config.setComposerProject(project());
		config.setActiveSequenceDelayScaleQuarters(scaleQuarters);
		FastNoteblocksConfig.save();
	}

	private int layerHeaderAt(double x, double y) {
		if (x < 12 || x >= LAYER_PANEL_WIDTH - 10) {
			return -1;
		}
		for (int index = 0; index < project().layers().size(); index++) {
			int top = layerY(index);
			if (y >= top && y < top + 21) {
				return index;
			}
		}
		return -1;
	}

	private void beginLayerRename(int layerIndex) {
		cancelLayerRename();
		editingLayer = layerIndex;
		int y = layerY(layerIndex);
		layerNameBox = new EditBox(font, 32, y, LAYER_PANEL_WIDTH - 40, 20,
			Component.literal("Layer name"));
		layerNameBox.setMaxLength(48);
		layerNameBox.setValue(project().layers().get(layerIndex).name());
		addRenderableWidget(layerNameBox);
		setInitialFocus(layerNameBox);
	}

	private static int layerY(int layerIndex) {
		return 48 + layerIndex * LAYER_ROW_HEIGHT;
	}

	private void commitLayerRename() {
		if (layerNameBox == null || editingLayer < 0 || editingLayer >= project().layers().size()) {
			cancelLayerRename();
			return;
		}
		Layer layer = project().layers().get(editingLayer);
		String name = layerNameBox.getValue().isBlank() ? layer.name() : layerNameBox.getValue().trim();
		removeWidget(layerNameBox);
		layerNameBox = null;
		int layerIndex = editingLayer;
		editingLayer = -1;
		updateLayer(layerIndex, layer.withName(name));
	}

	private void cancelLayerRename() {
		if (layerNameBox != null) {
			removeWidget(layerNameBox);
			layerNameBox = null;
		}
		editingLayer = -1;
	}

	private void copySelection() {
		List<NoteEvent> selected = project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selectedNotes.contains(note.id()))
			.sorted(Comparator.comparingLong(NoteEvent::startTick)
				.thenComparingInt(NoteEvent::midiNote))
			.toList();
		if (selected.isEmpty()) {
			return;
		}
		long firstTick = selected.stream().mapToLong(NoteEvent::startTick).min().orElse(0L);
		clipboard = selected.stream()
			.map(note -> new ClipboardNote(note.startTick() - firstTick, note.midiNote(),
				note.durationTicks(), note.velocity()))
			.toList();
	}

	private void deleteSelectedNotes() {
		if (!selectedNotes.isEmpty()) {
			apply(project().deleteNotes(selectedNotes));
			selectedNotes.clear();
		}
	}

	private void pasteClipboard() {
		if (clipboard.isEmpty()) {
			return;
		}
		long startTick = insideRoll(lastMouseX, lastMouseY)
			? snapTick(mouseTick(lastMouseX))
			: snapTick(horizontalScroll);
		PasteResult result = project().pasteNotes(project().activeLayerIndex(), clipboard, startTick);
		apply(result.project());
		selectedNotes.clear();
		selectedNotes.addAll(result.noteIds());
	}

	private void updateButtonStates() {
		if (playButton != null) {
			playButton.active = project().layers().stream().anyMatch(layer -> !layer.muted() && !layer.notes().isEmpty());
			playButton.setMessage(playLabel());
		}
	}

	private Component playLabel() {
		return Component.literal(playing ? "Stop" : "Play");
	}

	private ComposerProject project() {
		return history.current();
	}

	private ComposerProject displayProject() {
		return dragPreview == null ? project() : dragPreview;
	}

	private Layer activeLayer() {
		return project().layers().get(project().activeLayerIndex());
	}

	private NoteHit noteAt(double mouseX, double mouseY) {
		int activeLayerIndex = project().activeLayerIndex();
		NoteEvent active = noteAt(project().layers().get(activeLayerIndex), mouseX, mouseY);
		if (active != null) {
			return new NoteHit(activeLayerIndex, active);
		}
		for (int layerIndex = project().layers().size() - 1; layerIndex >= 0; layerIndex--) {
			if (layerIndex == activeLayerIndex || !project().layers().get(layerIndex).visible()) {
				continue;
			}
			NoteEvent note = noteAt(project().layers().get(layerIndex), mouseX, mouseY);
			if (note != null) {
				return new NoteHit(layerIndex, note);
			}
		}
		return null;
	}

	private NoteEvent noteAt(Layer layer, double mouseX, double mouseY) {
		List<NoteEvent> notes = layer.notes();
		for (int index = notes.size() - 1; index >= 0; index--) {
			NoteEvent note = notes.get(index);
			if (noteRect(note).contains(mouseX, mouseY)) {
				return note;
			}
		}
		return null;
	}

	private void selectNotesInBox() {
		int left = (int)Math.min(dragStartX, selectionEndX);
		int right = (int)Math.max(dragStartX, selectionEndX);
		int top = (int)Math.min(dragStartY, selectionEndY);
		int bottom = (int)Math.max(dragStartY, selectionEndY);
		for (NoteEvent note : activeLayer().notes()) {
			if (noteRect(note).intersects(left, top, right, bottom)) {
				selectedNotes.add(note.id());
			}
		}
	}

	private NoteRect noteRect(NoteEvent note) {
		int left = tickX(note.startTick());
		int width = Math.max(7, (int)Math.round(note.durationTicks() / ticksPerPixel));
		int top = noteY(note.midiNote()) + 1;
		return new NoteRect(left, top, left + width, top + ROW_HEIGHT - 2);
	}

	private static int lowerBoundStart(List<NoteEvent> notes, long tick) {
		int low = 0;
		int high = notes.size();
		while (low < high) {
			int middle = low + (high - low) / 2;
			if (notes.get(middle).startTick() < tick) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return low;
	}

	private int tickX(long tick) {
		return rollX + (int)Math.round((tick - horizontalScroll) / ticksPerPixel);
	}

	private long mouseTick(double x) {
		return horizontalScroll + Math.round((x - rollX) * ticksPerPixel);
	}

	private int noteY(int midiNote) {
		return rollY + (topMidiNote - midiNote) * ROW_HEIGHT;
	}

	private int mouseMidi(double y) {
		return Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE,
			topMidiNote - (int)Math.floor((y - rollY) / ROW_HEIGHT)));
	}

	private long gridTicks() {
		return snapSubdivision == 0 ? 1L : Math.max(1L, project().ppq() / snapSubdivision);
	}

	private long snapTick(long tick) {
		long grid = gridTicks();
		return Math.max(0L, Math.round(tick / (double)grid) * grid);
	}

	private long snapDelta(long tickDelta) {
		long grid = gridTicks();
		return Math.round(tickDelta / (double)grid) * grid;
	}

	private boolean insideRoll(double x, double y) {
		return x >= rollX && x < rollX + rollWidth && y >= rollY && y < rollY + rollHeight;
	}

	private boolean insideRuler(double x, double y) {
		return x >= rollX && x < rollX + rollWidth
			&& y >= rollY - TIMELINE_RULER_HEIGHT && y < rollY;
	}

	private boolean controlDown() {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private void centerMinecraftRange() {
		int visibleRows = Math.max(8, rollHeight / ROW_HEIGHT);
		topMidiNote = Math.max(12, Math.min(MAX_MIDI_NOTE,
			(ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE + ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE) / 2
				+ visibleRows / 2));
	}

	private static boolean isBlackKey(int midi) {
		return switch (Math.floorMod(midi, 12)) {
			case 1, 3, 6, 8, 10 -> true;
			default -> false;
		};
	}

	private static String midiName(int midi) {
		String[] names = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
		return names[Math.floorMod(midi, 12)] + (midi / 12 - 1);
	}

	private static final class DelayScaleSlider extends AbstractSliderButton {
		private final java.util.function.IntConsumer listener;
		private int scaleQuarters;

		DelayScaleSlider(
			int x,
			int y,
			int width,
			int height,
			int scaleQuarters,
			java.util.function.IntConsumer listener
		) {
			super(x, y, width, height, Component.empty(), 0.0);
			this.listener = listener;
			setScale(scaleQuarters);
		}

		private void setScale(int scaleQuarters) {
			this.scaleQuarters = FastNoteblocksConfig.clampSequenceDelayScale(scaleQuarters);
			value = (this.scaleQuarters - FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS)
				/ (double)(FastNoteblocksConfig.MAX_SEQUENCE_DELAY_SCALE_QUARTERS
					- FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Time " + FastNoteblocksConfig.delayScaleLabel(scaleQuarters)));
		}

		@Override
		protected void applyValue() {
			int range = FastNoteblocksConfig.MAX_SEQUENCE_DELAY_SCALE_QUARTERS
				- FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS;
			int updated = FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS
				+ Math.round((float)(value * range));
			updated = FastNoteblocksConfig.clampSequenceDelayScale(updated);
			if (updated != scaleQuarters) {
				scaleQuarters = updated;
				updateMessage();
				listener.accept(scaleQuarters);
			}
		}
	}

	private record NoteRect(int left, int top, int right, int bottom) {
		boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}

		boolean intersects(int otherLeft, int otherTop, int otherRight, int otherBottom) {
			return right > otherLeft && left < otherRight && bottom > otherTop && top < otherBottom;
		}
	}

	private record NoteHit(int layerIndex, NoteEvent note) {
	}

	private record ProjectStats(
		int outOfRange,
		Map<Long, Integer> chordCounts,
		int peakChord,
		long overloadedTicks,
		long maximumNoteDuration
	) {
	}

	private enum ToolbarMenu {
		NONE,
		FILE,
		EDIT
	}

	private enum ToolbarAction {
		IMPORT("Import MIDI / NBS..."),
		BACK_TO_SEQUENCES("Back to sequences"),
		CLOSE_TO_GAME("Close to game"),
		UNDO("Undo"),
		REDO("Redo"),
		CONVERT("Convert for Minecraft");

		private static final ToolbarAction[] FILE_ACTIONS = {
			IMPORT, BACK_TO_SEQUENCES, CLOSE_TO_GAME
		};
		private static final ToolbarAction[] EDIT_ACTIONS = {
			UNDO, REDO, CONVERT
		};
		private final String label;

		ToolbarAction(String label) {
			this.label = label;
		}
	}

	private enum ContextAction {
		OCTAVE_DOWN("-12"),
		OCTAVE_UP("+12"),
		FIT_RANGE("Fit range"),
		COPY("Copy"),
		CUT("Cut"),
		DELETE("Delete");

		private final String label;

		ContextAction(String label) {
			this.label = label;
		}
	}
}
