package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerHistory;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.ClipboardNote;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.ComposerProject.PasteResult;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
	private static final int ROW_HEIGHT = 12;
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
	private Button undoButton;
	private Button redoButton;
	private Button playButton;
	private Button snapButton;
	private Button previewModeButton;
	private Button addLayerButton;
	private EditBox layerNameBox;
	private boolean playing;
	private long playbackStartedAt;
	private final Set<Long> playedNotes = new LinkedHashSet<>();
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
	private boolean minecraftPreview;

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
		rollY = TOOLBAR_HEIGHT + 14;
		rollHeight = Math.max(40, height - rollY - 24);
		centerMinecraftRange();
		int x = 8;
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(x, 7, 54, 20).build());
		x += 58;
		undoButton = addRenderableWidget(Button.builder(Component.literal("Undo"), button -> undo())
			.bounds(x, 7, 52, 20).build());
		x += 56;
		redoButton = addRenderableWidget(Button.builder(Component.literal("Redo"), button -> redo())
			.bounds(x, 7, 52, 20).build());
		x += 56;
		playButton = addRenderableWidget(Button.builder(playLabel(), button -> togglePlayback())
			.bounds(x, 7, 54, 20)
			.tooltip(Tooltip.create(Component.literal("Preview all unmuted layers")))
			.build());
		x += 58;
		addRenderableWidget(Button.builder(Component.literal("Paste / Text"), button -> onClose())
			.bounds(x, 7, 86, 20)
			.tooltip(Tooltip.create(Component.literal("Return to the text sequence and settings screen")))
			.build());
		x += 90;
		addRenderableWidget(Button.builder(Component.literal("Import MIDI"), button -> importMidi())
			.bounds(x, 7, 82, 20)
			.tooltip(Tooltip.create(Component.literal("Import MIDI into this composition")))
			.build());
		x += 86;
		addRenderableWidget(Button.builder(Component.literal("−12"), button -> transposeSelected(-12))
			.bounds(x, 7, 42, 20)
			.tooltip(Tooltip.create(Component.literal("Move selected notes down one octave")))
			.build());
		x += 46;
		addRenderableWidget(Button.builder(Component.literal("+12"), button -> transposeSelected(12))
			.bounds(x, 7, 42, 20)
			.tooltip(Tooltip.create(Component.literal("Move selected notes up one octave")))
			.build());
		x += 46;
		addRenderableWidget(Button.builder(Component.literal("Fit range"), button -> fitSelectedToMinecraft())
			.bounds(x, 7, 70, 20)
			.tooltip(Tooltip.create(Component.literal("Octave-shift the selection into Minecraft's F♯3–F♯5 range")))
			.build());
		x += 74;
		snapButton = addRenderableWidget(Button.builder(snapLabel(), button -> cycleSnap())
			.bounds(x, 7, 78, 20)
			.tooltip(Tooltip.create(Component.literal("Grid used when adding or dragging notes")))
			.build());
		x += 82;
		previewModeButton = addRenderableWidget(Button.builder(previewModeLabel(), button -> {
			stopPlayback();
			minecraftPreview = !minecraftPreview;
			button.setMessage(previewModeLabel());
		}).bounds(x, 7, 94, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Source preserves MIDI timing and pitches; Minecraft snaps timing and skips out-of-range notes"
			)))
			.build());
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
			int y = 48 + index * 68;
			Button active = addRenderableWidget(Button.builder(layerLabel(index, layer), button -> {
				apply(project().withActiveLayer(layerIndex));
				selectedNotes.clear();
				rebuildLayerButtons();
			}).bounds(8, y, LAYER_PANEL_WIDTH - 16, 20).build());
			Button mute = addRenderableWidget(Button.builder(
				Component.literal(layer.muted() ? "Unmute" : "Mute"),
				button -> updateLayer(layerIndex, project().layers().get(layerIndex).withMuted(
					!project().layers().get(layerIndex).muted()
				))
			).bounds(8, y + 22, 50, 18).build());
			Button build = addRenderableWidget(Button.builder(
				Component.literal(layer.buildEnabled() ? "Build" : "Skip"),
				button -> updateLayer(layerIndex, project().layers().get(layerIndex).withBuildEnabled(
					!project().layers().get(layerIndex).buildEnabled()
				))
			).bounds(60, y + 22, 46, 18).build());
			Button visible = addRenderableWidget(Button.builder(
				Component.literal(layer.visible() ? "Shown" : "Hidden"),
				button -> updateLayer(layerIndex, project().layers().get(layerIndex).withVisible(
					!project().layers().get(layerIndex).visible()
				))
			).bounds(108, y + 22, 54, 18).build());
			Button instrument = addRenderableWidget(Button.builder(
				Component.literal(PreviewInstrument.byId(layer.instrument()).name()),
				button -> instrumentMenuLayer = instrumentMenuLayer == layerIndex ? -1 : layerIndex
			).bounds(8, y + 42, LAYER_PANEL_WIDTH - 16, 18)
				.tooltip(Tooltip.create(Component.literal("Open the note-block instrument palette")))
				.build());
			layerButtons.addAll(List.of(active, mute, build, visible, instrument));
		}
	}

	private void rebuildMoveLayerButtons() {
		for (Button button : moveLayerButtons) {
			removeWidget(button);
		}
		moveLayerButtons.clear();
		int y = height - 86;
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
		for (int index = 0; index < ComposerProject.MAX_LAYERS; index++) {
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

	private Component previewModeLabel() {
		return Component.literal(minecraftPreview ? "Minecraft" : "Source MIDI");
	}

	private void importMidi() {
		boolean nonempty = project().layers().stream().anyMatch(layer -> !layer.notes().isEmpty());
		if (!nonempty) {
			chooseAndImportMidi();
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.gui.setScreen(this);
			if (confirmed) {
				chooseAndImportMidi();
			}
		}, Component.literal("Replace this composition?"),
			Component.literal("Importing MIDI replaces the current piano roll. You can still Undo afterward."),
			Component.literal("Import"), CommonComponents.GUI_CANCEL));
	}

	private void chooseAndImportMidi() {
		String path;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(2);
			filters.put(stack.UTF8("*.mid"));
			filters.put(stack.UTF8("*.midi"));
			filters.flip();
			path = TinyFileDialogs.tinyfd_openFileDialog("Import MIDI", "", filters, "MIDI files", false);
		}
		if (path == null || path.isBlank()) {
			return;
		}
		try {
			MidiImporter.ProjectResult result = MidiImporter.importProject(path, config);
			apply(result.project());
			selectedNotes.clear();
			horizontalScroll = 0L;
			centerMinecraftRange();
			rebuildLayerButtons();
			rebuildMoveLayerButtons();
			minecraft.gui.hud.setOverlayMessage(Component.literal(result.report()), true);
		} catch (Exception exception) {
			minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(this),
				Component.literal("MIDI import failed"),
				Component.literal(exception.getMessage() == null
					? exception.getClass().getSimpleName()
					: exception.getMessage()),
				CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(graphics);
		extractTransparentBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		rollX = LAYER_PANEL_WIDTH + PIANO_WIDTH;
		rollY = TOOLBAR_HEIGHT + 14;
		rollWidth = Math.max(40, width - rollX - 8);
		rollHeight = Math.max(40, height - rollY - 24);
		extractPanels(graphics);
		extractPianoRoll(graphics, mouseX, mouseY);
		extractStatus(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		extractInstrumentMenu(graphics, mouseX, mouseY);
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
		int requestedY = 48 + instrumentMenuLayer * 68 + 62;
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
		int active = project().activeLayerIndex();
		graphics.text(font, Component.literal("Move selection:"), 8, height - 100, 0xFFAAAAAA, false);
		graphics.text(font, Component.literal("Active: Layer " + (active + 1)), 8, height - 18,
			LAYER_COLORS[active % LAYER_COLORS.length], false);
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
	}

	private void extractNotes(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ComposerProject shown = displayProject();
		for (int layerIndex = 0; layerIndex < shown.layers().size(); layerIndex++) {
			Layer layer = shown.layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			boolean active = layerIndex == shown.activeLayerIndex();
			for (NoteEvent note : layer.notes()) {
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
		if (!playing) {
			return;
		}
		long tick = playbackTick();
		int x = tickX(tick);
		if (x >= rollX && x <= rollX + rollWidth) {
			graphics.fill(x, rollY, x + 2, rollY + rollHeight, 0xFFFF5555);
		}
	}

	private void extractStatus(GuiGraphicsExtractor graphics) {
		int outOfRange = (int)project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> !note.isBuildable())
			.count();
		String status = selectedNotes.size() + " selected"
			+ (outOfRange > 0 ? "   " + outOfRange + " outside Minecraft range" : "")
			+ "   Double-click to add • drag to move • drag empty space to select • Shift+wheel changes pitch";
		graphics.text(font, status, rollX, height - 16, outOfRange > 0 ? 0xFFFF9999 : 0xFFBBBBBB, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
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
		int requestedY = 48 + instrumentMenuLayer * 68 + 62;
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

	@Override
	public void mouseMoved(double x, double y) {
		lastMouseX = x;
		lastMouseY = y;
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
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
		NoteHit hovered = noteAt(mouseX, mouseY);
		if (hovered != null && shiftDown()) {
			if (hovered.layerIndex() != project().activeLayerIndex()) {
				selectedNotes.clear();
				apply(project().withActiveLayer(hovered.layerIndex()));
				rebuildLayerButtons();
			}
			NoteEvent hoveredNote = hovered.note();
			if (!selectedNotes.contains(hoveredNote.id())) {
				selectedNotes.clear();
				selectedNotes.add(hoveredNote.id());
			}
			int delta = scrollY > 0 ? 1 : -1;
			apply(project().moveNotes(selectedNotes, 0L, delta));
			NoteEvent changed = findNote(hoveredNote.id());
			if (changed != null) {
				PreviewInstrument.byId(activeLayer().instrument()).play(
					changed.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
				);
			}
			return true;
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
			if (!selectedNotes.isEmpty()) {
				apply(project().deleteNotes(selectedNotes));
				selectedNotes.clear();
			}
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
			apply(project().deleteNotes(selectedNotes));
			selectedNotes.clear();
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

	private void togglePlayback() {
		if (playing) {
			stopPlayback();
		} else {
			playing = true;
			playbackStartedAt = Util.getMillis();
			playedNotes.clear();
			playButton.setMessage(playLabel());
		}
	}

	private void stopPlayback() {
		playing = false;
		playedNotes.clear();
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
		long elapsedMicros = Math.max(0L, Util.getMillis() - playbackStartedAt) * 1000L;
		for (Layer layer : project().layers()) {
			if (layer.muted()) {
				continue;
			}
			PreviewInstrument instrument = PreviewInstrument.byId(layer.instrument());
			for (NoteEvent note : layer.notes()) {
				long noteMicros = Math.round(
					note.startTick() * project().tempoMicrosPerQuarter() / (double)project().ppq()
				);
				if (minecraftPreview) {
					noteMicros = Math.round(noteMicros / 100_000.0) * 100_000L;
				}
				if (elapsedMicros >= noteMicros && (!minecraftPreview || note.isBuildable())
						&& playedNotes.add(note.id())) {
					instrument.play(note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE);
				}
			}
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
		return Math.round(elapsedMicros * project().ppq() / (double)project().tempoMicrosPerQuarter());
	}

	private void undo() {
		history.undo();
		selectedNotes.clear();
		saveProject();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void redo() {
		history.redo();
		selectedNotes.clear();
		saveProject();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void apply(ComposerProject project) {
		history.apply(project);
		saveProject();
		updateButtonStates();
	}

	private void saveProject() {
		config.setComposerProject(project());
		FastNoteblocksConfig.save();
	}

	private int layerHeaderAt(double x, double y) {
		if (x < 8 || x >= LAYER_PANEL_WIDTH - 8) {
			return -1;
		}
		for (int index = 0; index < project().layers().size(); index++) {
			int top = 48 + index * 68;
			if (y >= top && y < top + 20) {
				return index;
			}
		}
		return -1;
	}

	private void beginLayerRename(int layerIndex) {
		cancelLayerRename();
		editingLayer = layerIndex;
		int y = 48 + layerIndex * 68;
		layerNameBox = new EditBox(font, 32, y, LAYER_PANEL_WIDTH - 40, 20,
			Component.literal("Layer name"));
		layerNameBox.setMaxLength(48);
		layerNameBox.setValue(project().layers().get(layerIndex).name());
		addRenderableWidget(layerNameBox);
		setInitialFocus(layerNameBox);
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
		if (undoButton != null) {
			undoButton.active = history.canUndo();
		}
		if (redoButton != null) {
			redoButton.active = history.canRedo();
		}
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

	private NoteEvent findNote(long id) {
		return activeLayer().notes().stream().filter(note -> note.id() == id).findFirst().orElse(null);
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

	private boolean shiftDown() {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
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
}
