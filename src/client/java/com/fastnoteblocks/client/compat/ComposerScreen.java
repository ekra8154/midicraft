package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerHistory;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.fastnoteblocks.client.composer.ComposerProject.ClipboardNote;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.MinecraftConversion;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.ComposerProject.PasteResult;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
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
	/** Right edge of the toolbar's buttons: File..Build, Play, Snap and the speed slider. */
	private static final int TOOLBAR_CONTROLS_RIGHT = 8 + 6 * 58 + 82 + 94;
	private static final int LAYER_PANEL_WIDTH = 196;
	private static final int PIANO_WIDTH = 48;
	private static final int TIMELINE_RULER_HEIGHT = 16;
	private static final int LAYER_ROW_HEIGHT = 42;
	private static final int LAYER_COLLAPSED_ROW_HEIGHT = 20;
	private static final int LAYER_LIST_TOP = 48;
	private static final int MIN_ROW_HEIGHT = 4;
	private static final int MAX_ROW_HEIGHT = 26;
	private static final int CONTEXT_MENU_WIDTH = 104;
	private static final int CONTEXT_MENU_ROW_HEIGHT = 16;
	private static final int LAYER_MENU_WIDTH = 120;
	private static final int TOOLBAR_MENU_WIDTH = 126;
	private static final int IMPORT_MENU_WIDTH = 196;
	private static final int TOOLBAR_MENU_ROW_HEIGHT = 18;
	private static final int VELOCITY_CUTOFF_STEP = 8;
	private static final int ROW_HEIGHT = 12;
		private static final int CHORD_WARNING_THRESHOLD = 24;
	private static final int MAX_PREVIEW_SOUNDS_PER_FRAME = 64;
	private static final int MIN_GRID_PIXEL_SPACING = 4;
	private static final int MIN_LABEL_PIXEL_SPACING = 32;
	private static final double BOX_SCROLL_FULL_SPEED_PIXELS = 140.0;
	private static final double BOX_SCROLL_MAX_PIXELS = 22.0;
	private static final double BOX_SCROLL_MAX_ROWS = 2.0;
	/** How much faster holding at an edge eventually gets, and how long it takes to get there. */
	private static final double BOX_SCROLL_HELD_BOOST = 4.0;
	private static final double BOX_SCROLL_BOOST_MILLIS = 900.0;
	private static final long TOOLTIP_DWELL_MILLIS = 260L;
	private static final long SCALE_COALESCE_MILLIS = 400L;
	private static final long TOAST_MILLIS = 4500L;
	private static final int NOTE_TRIGGER_WIDTH = 7;
	private static final int SNAP_REPEATER = -1;
	private static final long PREVIEW_BACKLOG_TOLERANCE_MICROS = 100_000L;
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
	private final Set<Integer> collapsedLayers = new LinkedHashSet<>();
	private final Set<Integer> selectedLayers = new LinkedHashSet<>();
	private int rowHeight = ROW_HEIGHT;
	private int layerScroll;
	private boolean layerViewInitialised;
	private boolean layerMenuOpen;
	private int layerMenuX;
	private int layerMenuY;
	private List<ClipboardNote> clipboard = List.of();
	private Button playButton;
	private Button snapButton;
	private DelayScaleSlider delayScaleSlider;
	private Button addLayerButton;
	private EditBox layerNameBox;
	private boolean playing;
	private long playbackStartedAt;
	private long playbackStartTick;
	private List<PlaybackEvent> playbackEvents = List.of();
	private int playbackEventIndex;
	private boolean draggingPlayhead;
	/**
	 * Layers being listened to alone.
	 *
	 * <p>Deliberately not on the composition and not saved. Solo is a way of hearing one thing
	 * while you work on it, not a property of the song, and it has no bearing on what builds.</p>
	 */
	private final Set<Integer> soloedLayers = new LinkedHashSet<>();
	/**
	 * What the menu row under the cursor does, drawn beneath the menu rather than as a tooltip.
	 *
	 * <p>A tooltip loses this fight. Menus draw over the layer panel, and the widget underneath
	 * keeps its own tooltip registered, so hovering "Convert for Minecraft" showed the instrument
	 * palette's description instead. Drawing it as part of the menu also means the explanation
	 * cannot end up covering the thing it explains.</p>
	 */
	private String hoveredDescription = "";
	private boolean draggingEndMarker;
	private long lastEndDragAt;
	private long horizontalScroll;
	private int topMidiNote = 91;
	private double ticksPerPixel = 10.0;
	private boolean draggingNotes;
	private boolean selectingBox;
	private long horizontalEdgeSince;
	private long verticalEdgeSince;
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
	private long lastScaleChangeAt;
	private Component toast;
	private long toastShownAt;
	private long hoveredNoteId = -1L;
	private long hoveredSince;
	private ComposerProject cachedStatsProject;
	private SongAnalysis cachedStats;

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
		addRenderableWidget(Button.builder(Component.literal("Import"), button -> toggleToolbarMenu(ToolbarMenu.IMPORT, 124))
			.bounds(x, 7, 54, 20)
			.tooltip(Tooltip.create(Component.literal("Settings applied when importing MIDI and NBS songs")))
			.build());
		x += 58;
		addRenderableWidget(Button.builder(Component.literal("Select"), button -> toggleToolbarMenu(ToolbarMenu.SELECT, 182))
			.bounds(x, 7, 54, 20)
			.tooltip(Tooltip.create(Component.literal("Select every note with a given build problem")))
			.build());
		x += 58;
		addRenderableWidget(Button.builder(Component.literal("Build"), button -> toggleToolbarMenu(ToolbarMenu.BUILD, 240))
			.bounds(x, 7, 54, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Move layers into the build sequence, or paste them with commands")))
			.build());
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
			x, 7, 94, 20, project().speedQuarters(), this::setDelayScale
		));
		delayScaleSlider.setTooltip(Tooltip.create(Component.literal(
			"Playback speed, 0.25x to 8.00x. Higher is faster. Saving to the sequence bakes this "
				+ "into the delays, so the build runs at the speed you hear here."
		)));
		if (!layerViewInitialised) {
			// Imports and conversions routinely produce dozens of layers; an all-expanded list
			// buries the one being worked on before the user has done anything.
			layerViewInitialised = true;
			collapseAllButActive();
		}
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
		// Solo is held by index, so anything that adds, removes or reorders layers would leave it
		// pointing at the wrong one. Dropping it is better than silencing something at random.
		if (soloedLayers.removeIf(index -> index >= project.layers().size()) && playing) {
			resetPlaybackSchedule();
		}
		for (int index = 0; index < project.layers().size(); index++) {
			final int layerIndex = index;
			Layer layer = project.layers().get(index);
			int y = layerY(index);
			// Collapsed rows show only their header, and rows scrolled outside the panel must not
			// exist as widgets at all or they stay clickable over the piano roll.
			if (collapsedLayers.contains(index) || !layerRowVisible(index)) {
				continue;
			}
			Button mute = addRenderableWidget(Button.builder(
				Component.literal(layer.muted() ? "X" : "M"),
				button -> {
					boolean next = !project().layers().get(layerIndex).muted();
					updateLayers(layerIndex, target -> target.withMuted(next));
				}
			).bounds(14, y + 22, 22, 16)
				.tooltip(Tooltip.create(Component.literal(layer.muted() ? "Unmute layer" : "Mute layer")))
				.build());
			boolean soloed = soloedLayers.contains(index);
			Button solo = addRenderableWidget(Button.builder(
				Component.literal("S").withStyle(soloed
					? net.minecraft.ChatFormatting.YELLOW
					: net.minecraft.ChatFormatting.GRAY),
				button -> {
					if (!soloedLayers.remove(layerIndex)) {
						soloedLayers.add(layerIndex);
					}
					if (playing) {
						resetPlaybackSchedule();
					}
					rebuildLayerButtons();
				}
			).bounds(38, y + 22, 22, 16)
				.tooltip(Tooltip.create(Component.literal(soloed
					? "Stop soloing - hear everything again"
					: "Hear this layer alone. Listening only; it does not change what builds.")))
				.build());
			Button visible = addRenderableWidget(Button.builder(
				// An eye rather than a letter, so S could go to solo. Same Geometric Shapes block
				// as the arrows and dots already drawn here, so the font covers it.
				Component.literal(layer.visible() ? "◉" : "◌"),
				button -> {
					boolean next = !project().layers().get(layerIndex).visible();
					updateLayers(layerIndex, target -> target.withVisible(next));
				}
			).bounds(62, y + 22, 26, 16)
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
			layerButtons.addAll(List.of(mute, solo, visible, instrument, up, down));
		}
	}

	private void rebuildMoveLayerButtons() {
		for (Button button : moveLayerButtons) {
			removeWidget(button);
		}
		moveLayerButtons.clear();
		// Pinned to the bottom of the panel: with up to MAX_LAYERS rows the list scrolls, so this
		// must not ride along with the last row or it drifts off screen.
		int y = layerListBottom() + 4;
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
				"Add a layer and move the current selection into it (maximum "
					+ ComposerProject.MAX_LAYERS + ")"
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

	/** Keeps collapse state attached to the right rows, mirroring {@link ComposerProject#moveLayer}. */
	private void remapCollapsedLayers(int layerIndex, int direction) {
		int size = project().layers().size();
		int from = Math.max(0, Math.min(size - 1, layerIndex));
		int to = Math.max(0, Math.min(size - 1, from + direction));
		if (from == to || collapsedLayers.isEmpty()) {
			return;
		}
		Set<Integer> remapped = new LinkedHashSet<>();
		for (int collapsed : collapsedLayers) {
			if (collapsed == from) {
				remapped.add(to);
				continue;
			}
			int shifted = collapsed > from ? collapsed - 1 : collapsed;
			remapped.add(shifted >= to ? shifted + 1 : shifted);
		}
		collapsedLayers.clear();
		collapsedLayers.addAll(remapped);
	}

	/**
	 * Collapses everything except the active layer. An import or a Minecraft conversion can produce
	 * dozens of layers at once, and an all-expanded list buries the one being worked on.
	 */
	private void collapseAllButActive() {
		collapsedLayers.clear();
		int active = project().activeLayerIndex();
		for (int index = 0; index < project().layers().size(); index++) {
			if (index != active) {
				collapsedLayers.add(index);
			}
		}
		selectedLayers.clear();
		selectedLayers.add(active);
		layerScroll = 0;
		layerMenuOpen = false;
	}

	private List<Integer> layersToEdit(int clickedIndex) {
		if (selectedLayers.size() > 1 && selectedLayers.contains(clickedIndex)) {
			return selectedLayers.stream().sorted().toList();
		}
		return List.of(clickedIndex);
	}

	/** Applies one change across every layer the click should affect, as a single undo step. */
	private void updateLayers(int clickedIndex, java.util.function.UnaryOperator<Layer> mutation) {
		ComposerProject updated = project();
		for (int index : layersToEdit(clickedIndex)) {
			if (index >= 0 && index < updated.layers().size()) {
				updated = updated.withLayer(index, mutation.apply(updated.layers().get(index)));
			}
		}
		apply(updated);
		rebuildLayerButtons();
	}

	private void selectLayer(int layerIndex, boolean toggle, boolean range) {
		if (toggle) {
			if (!selectedLayers.remove(layerIndex)) {
				selectedLayers.add(layerIndex);
			}
		} else if (range) {
			int from = Math.min(project().activeLayerIndex(), layerIndex);
			int to = Math.max(project().activeLayerIndex(), layerIndex);
			for (int index = from; index <= to; index++) {
				selectedLayers.add(index);
			}
		} else {
			selectedLayers.clear();
			selectedLayers.add(layerIndex);
		}
		if (selectedLayers.isEmpty()) {
			selectedLayers.add(layerIndex);
		}
		apply(project().withActiveLayer(layerIndex));
		rebuildLayerButtons();
	}

	private void mergeSelectedLayers() {
		if (selectedLayers.size() < 2) {
			return;
		}
		apply(project().mergeLayers(Set.copyOf(selectedLayers)));
		collapseAllButActive();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
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
		remapCollapsedLayers(layerIndex, direction);
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
			showResult(Component.literal("Select notes to fit first."));
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
			showResult(
				Component.literal("That selection spans more than Minecraft's 25-note range."));
			return;
		}
		transposeSelected(bestShift);
	}

	private void convertToMinecraft() {
		stopPlayback();
		// Bake the timescale into the tempo first, so converting at 2.00x produces a project that
		// genuinely runs that fast rather than one that still depends on a slider. The slider then
		// resets, which is what makes a second convert pass meaningful instead of compounding.
		double factor = timescaleFactor();
		ComposerProject source = factor == 1.0
			? project()
			: project().withTempo(Math.max(1,
				(int)Math.round(project().tempoMicrosPerQuarter() / factor)));
		int gridTicks = minecraftConversionGridTicks(source);
		boolean snapTempo = config.midiTempoFit() == FastNoteblocksConfig.MidiTempoFit.SNAP_TO_REPEATERS;
		try {
			MinecraftConversion conversion = source.convertToMinecraft(
				gridTicks, snapTempo, config.repeatMergeTicks());
			if (conversion.project().equals(project())) {
				showResult(
					Component.literal("This composition is already Minecraft-ready."));
				return;
			}
			// Convert bakes the speed into the tempo, so the result plays at its own pace.
			apply(conversion.project().withSpeedQuarters(ComposerProject.DEFAULT_SPEED_QUARTERS));
			delayScaleSlider.setScale(ComposerProject.DEFAULT_SPEED_QUARTERS);
			selectedNotes.clear();
			instrumentMenuLayer = -1;
			collapseAllButActive();
			centerMinecraftRange();
			rebuildLayerButtons();
			rebuildMoveLayerButtons();
			String report = "Converted at " + conversionGridLabel(gridTicks)
				+ ": " + conversion.shiftedNotes() + " pitch-shifted"
				+ (conversion.addedLayers() > 0 ? ", +" + conversion.addedLayers() + " layers" : "")
				+ (conversion.mergedRepeats() > 0
					? ", " + conversion.mergedRepeats() + " repeats merged" : "");
			if (conversion.slowedDown()) {
				report += String.format(java.util.Locale.ROOT,
					", SLOWED %.2fx - song is faster than redstone can play (max 10 notes/sec)",
					conversion.tempoFactor());
			} else if (conversion.tempoChanged()) {
				report += ", tempo aligned to repeaters";
			}
			showResult(Component.literal(report));
		} catch (IllegalStateException exception) {
			minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(this),
				Component.literal("Too many converted layers"),
				Component.literal(exception.getMessage()),
				CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
		}
	}

	private int minecraftConversionGridTicks(ComposerProject source) {
		return switch (config.midiQuantizeGrid()) {
			case QUARTER -> source.ppq();
			case EIGHTH -> Math.max(1, source.ppq() / 2);
			case SIXTEENTH -> Math.max(1, source.ppq() / 4);
			case AUTO -> automaticConversionGridTicks(source);
		};
	}

	private int automaticConversionGridTicks(ComposerProject source) {
		List<Long> starts = source.mergedStartTicks(config.repeatMergeTicks());
		List<Long> gaps = new ArrayList<>();
		for (int index = 1; index < starts.size(); index++) {
			long gap = starts.get(index) - starts.get(index - 1);
			if (gap > 0) {
				gaps.add(gap);
			}
		}
		long smallestGap = Long.MAX_VALUE;
		if (!gaps.isEmpty()) {
			gaps.sort(null);
			// Skip the closest few gaps so a handful of outliers cannot set the grid, and
			// therefore the tempo, for the entire song. 0% is the strict minimum.
			int skip = (int)(gaps.size() * (long)config.conversionGapPercentile() / 100L);
			smallestGap = gaps.get(Math.min(gaps.size() - 1, Math.max(0, skip)));
		}
		if (smallestGap <= Math.max(1, source.ppq() * 3L / 8L)) {
			return Math.max(1, source.ppq() / 4);
		}
		if (smallestGap <= Math.max(1, source.ppq() * 3L / 4L)) {
			return Math.max(1, source.ppq() / 2);
		}
		return source.ppq();
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
			case 8 -> SNAP_REPEATER;
			case SNAP_REPEATER -> 0;
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
			case SNAP_REPEATER -> "Snap repeater";
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

	/**
	 * Asks the operating system for a file, in a window rather than in fullscreen.
	 *
	 * <p>The picker is a native dialog and it blocks the render thread until it is answered. Over an
	 * exclusive-fullscreen window the compositor hides it outright: the dialog is alive and holding
	 * the game hostage, but it cannot be seen, focused or alt-tabbed to, and the frozen game is
	 * still covering the screen. There is no way out of that from inside the game. Dropping to a
	 * window for as long as the dialog is up costs a flicker and is the whole fix.</p>
	 *
	 * <p>Toggling is not enough on its own -- {@code toggleFullScreen} only sets a flag that the
	 * frame loop acts on, and the frame loop is what is about to stop.</p>
	 */
	private String chooseSongFile() {
		Window window = minecraft.getWindow();
		boolean wasFullscreen = window.isFullscreen();
		if (wasFullscreen) {
			window.toggleFullScreen();
			window.updateFullscreenIfChanged();
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(3);
			filters.put(stack.UTF8("*.mid"));
			filters.put(stack.UTF8("*.midi"));
			filters.put(stack.UTF8("*.nbs"));
			filters.flip();
			return TinyFileDialogs.tinyfd_openFileDialog(
				"Import MIDI or NBS", "", filters, "MIDI and Note Block Studio songs", false
			);
		} finally {
			if (wasFullscreen) {
				window.toggleFullScreen();
				window.updateFullscreenIfChanged();
			}
		}
	}

	private void chooseAndImportSong() {
		String path = chooseSongFile();
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
		apply(imported.withSpeedQuarters(ComposerProject.DEFAULT_SPEED_QUARTERS));
		selectedNotes.clear();
		horizontalScroll = 0L;
		delayScaleSlider.setScale(ComposerProject.DEFAULT_SPEED_QUARTERS);
		collapseAllButActive();
		centerMinecraftRange();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		showResult(Component.literal(report));
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
		updatePlayback();
		rollX = LAYER_PANEL_WIDTH + PIANO_WIDTH;
		rollY = TOOLBAR_HEIGHT + 14 + TIMELINE_RULER_HEIGHT;
		rollWidth = Math.max(40, width - rollX - 8);
		rollHeight = Math.max(40, height - rollY - 24);
		extractPanels(graphics);
		extractTimeRuler(graphics, mouseX, mouseY);
		extractPianoRoll(graphics, mouseX, mouseY);
		extractStatus(graphics);
		extractToast(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		hoveredDescription = "";
		extractInstrumentMenu(graphics, mouseX, mouseY);
		extractContextMenu(graphics, mouseX, mouseY);
		extractLayerMenu(graphics, mouseX, mouseY);
		extractToolbarMenu(graphics, mouseX, mouseY);
		extractMenuDescription(graphics);
	}

	/** Draws the hovered menu row's explanation in a strip along the bottom of the screen. */
	private void extractMenuDescription(GuiGraphicsExtractor graphics) {
		if (hoveredDescription.isEmpty()) {
			return;
		}
		int maxWidth = Math.max(160, width - 32);
		List<net.minecraft.util.FormattedCharSequence> lines =
			font.split(Component.literal(hoveredDescription), maxWidth);
		int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
		int height = lines.size() * (font.lineHeight + 2);
		int top = this.height - 26 - height;
		graphics.fill(6, top - 4, 14 + textWidth, top + height, 0xF0101115);
		graphics.fill(6, top - 4, 14 + textWidth, top - 3, 0xFF8FD3FF);
		for (int index = 0; index < lines.size(); index++) {
			graphics.text(font, lines.get(index), 10, top + index * (font.lineHeight + 2),
				0xFFD6D8DD, false);
		}
	}

	private void extractLayerMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!layerMenuOpen) {
			return;
		}
		LayerAction[] actions = LayerAction.values();
		int menuHeight = actions.length * CONTEXT_MENU_ROW_HEIGHT + 4;
		int menuWidth = layerMenuWidth();
		graphics.fill(layerMenuX, layerMenuY, layerMenuX + menuWidth, layerMenuY + menuHeight, 0xF0101115);
		graphics.fill(layerMenuX, layerMenuY, layerMenuX + menuWidth, layerMenuY + 1, 0xFFAAAAAA);
		for (int index = 0; index < actions.length; index++) {
			int rowY = layerMenuY + 2 + index * CONTEXT_MENU_ROW_HEIGHT;
			boolean enabled = layerActionEnabled(actions[index]);
			boolean hovered = enabled && mouseX >= layerMenuX && mouseX < layerMenuX + menuWidth
				&& mouseY >= rowY && mouseY < rowY + CONTEXT_MENU_ROW_HEIGHT;
			if (hovered) {
				graphics.fill(layerMenuX + 2, rowY, layerMenuX + menuWidth - 2,
					rowY + CONTEXT_MENU_ROW_HEIGHT, 0xFF356070);
			}
			if (hovered) {
				hoveredDescription = layerActionTooltip(actions[index]);
			}
			graphics.text(font, Component.literal(layerActionLabel(actions[index])), layerMenuX + 6, rowY + 4,
				enabled ? 0xFFFFFFFF : 0xFF777777, false);
		}
	}

	private String layerActionLabel(LayerAction action) {
		int selected = selectedLayers.size();
		return switch (action) {
			case MERGE_SELECTED -> "Merge " + selected + " layers";
			case INCLUDE_SELECTED -> "Include " + layerCountLabel(Math.max(1, selected)) + " in sequence";
			case SET_INCLUDED_TO_SELECTION ->
				"Include only " + layerCountLabel(Math.max(1, selected)) + " in sequence";
			default -> action.label;
		};
	}

	private int layerMenuWidth() {
		int widest = LAYER_MENU_WIDTH;
		for (LayerAction action : LayerAction.values()) {
			widest = Math.max(widest, font.width(layerActionLabel(action)) + 14);
		}
		return widest;
	}

	private boolean layerActionEnabled(LayerAction action) {
		return switch (action) {
			case MERGE_SELECTED -> selectedLayers.size() >= 2;
			case INCLUDE_SELECTED, SET_INCLUDED_TO_SELECTION -> !selectedLayers.isEmpty();
			case SELECT_ALL, COLLAPSE_OTHERS -> true;
		};
	}

	private boolean handleLayerMenuClick(double mouseX, double mouseY) {
		LayerAction[] actions = LayerAction.values();
		if (mouseX < layerMenuX || mouseX >= layerMenuX + layerMenuWidth()) {
			return false;
		}
		int row = ((int)mouseY - layerMenuY - 2) / CONTEXT_MENU_ROW_HEIGHT;
		if (row < 0 || row >= actions.length) {
			return false;
		}
		LayerAction action = actions[row];
		if (!layerActionEnabled(action)) {
			return true;
		}
		layerMenuOpen = false;
		switch (action) {
			case MERGE_SELECTED -> mergeSelectedLayers();
			case INCLUDE_SELECTED -> setIncludedLayers(true);
			case SET_INCLUDED_TO_SELECTION -> setIncludedLayers(false);
			case SELECT_ALL -> {
				selectedLayers.clear();
				for (int index = 0; index < project().layers().size(); index++) {
					selectedLayers.add(index);
				}
			}
			case COLLAPSE_OTHERS -> collapseAllButActive();
		}
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		return true;
	}

	private void extractToolbarMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> rows = toolbarRows();
		if (rows.isEmpty()) {
			return;
		}
		int menuWidth = toolbarMenuWidth();
		int menuY = 28;
		int menuHeight = rows.size() * TOOLBAR_MENU_ROW_HEIGHT + 4;
		graphics.fill(toolbarMenuX, menuY, toolbarMenuX + menuWidth, menuY + menuHeight, 0xF0101115);
		graphics.fill(toolbarMenuX, menuY, toolbarMenuX + menuWidth, menuY + 1, 0xFFAAAAAA);
		for (int index = 0; index < rows.size(); index++) {
			int rowY = menuY + 2 + index * TOOLBAR_MENU_ROW_HEIGHT;
			boolean enabled = toolbarRowEnabled(index);
			boolean hovered = enabled && mouseX >= toolbarMenuX
				&& mouseX < toolbarMenuX + menuWidth
				&& mouseY >= rowY && mouseY < rowY + TOOLBAR_MENU_ROW_HEIGHT;
			if (hovered) {
				graphics.fill(toolbarMenuX + 2, rowY, toolbarMenuX + menuWidth - 2,
					rowY + TOOLBAR_MENU_ROW_HEIGHT, 0xFF356070);
			}
			if (toolbarMenu != ToolbarMenu.IMPORT && index < toolbarActions().length) {
				String hint = toolbarShortcut(toolbarActions()[index]);
				if (!hint.isEmpty()) {
					graphics.text(font, Component.literal(hint),
						toolbarMenuX + menuWidth - 6 - font.width(hint), rowY + 5, 0xFF6E7480, false);
				}
			}
			if (hovered) {
				hoveredDescription = toolbarMenu == ToolbarMenu.IMPORT
					? (index < ImportSetting.values().length
						? importSettingTooltip(ImportSetting.values()[index])
						: "")
					: (index < toolbarActions().length
						? toolbarActionTooltip(toolbarActions()[index])
						: "");
			}
			graphics.text(font, Component.literal(rows.get(index)), toolbarMenuX + 6, rowY + 5,
				enabled ? 0xFFFFFFFF : 0xFF777777, false);
		}
		if (toolbarMenu == ToolbarMenu.IMPORT) {
			graphics.text(font, Component.literal("Left-click cycles, right-click reverses"),
				toolbarMenuX + 6, menuY + menuHeight + 3, 0xFF888888, false);
		}
	}

	/**
	 * Menu width from the widest row it holds.
	 *
	 * <p>Fixed widths were fine while every label was two words. Labels that count what they will
	 * act on are not a fixed length, and were running past the edge of the panel they were drawn
	 * in.</p>
	 */
	private int toolbarMenuWidth() {
		int widest = toolbarMenu == ToolbarMenu.IMPORT ? IMPORT_MENU_WIDTH : TOOLBAR_MENU_WIDTH;
		List<String> rows = toolbarRows();
		ToolbarAction[] actions = toolbarActions();
		for (int index = 0; index < rows.size(); index++) {
			int hint = toolbarMenu == ToolbarMenu.IMPORT || index >= actions.length
				? 0
				: font.width(toolbarShortcut(actions[index]));
			widest = Math.max(widest, font.width(rows.get(index)) + (hint == 0 ? 14 : hint + 30));
		}
		return Math.min(widest, Math.max(60, width - toolbarMenuX - 4));
	}

	private List<String> toolbarRows() {
		ToolbarAction[] actions = toolbarActions();
		if (toolbarMenu == ToolbarMenu.IMPORT) {
			List<String> rows = new ArrayList<>();
			for (ImportSetting setting : ImportSetting.values()) {
				rows.add(importSettingLabel(setting));
			}
			return rows;
		}
		List<String> rows = new ArrayList<>(actions.length);
		for (ToolbarAction action : actions) {
			rows.add(toolbarRowLabel(action)
				+ (selectedNotes.isEmpty() || !action.scopeable ? "" : " (selection)"));
		}
		return rows;
	}

	private boolean toolbarRowEnabled(int index) {
		if (toolbarMenu == ToolbarMenu.IMPORT) {
			return true;
		}
		ToolbarAction[] actions = toolbarActions();
		return index >= 0 && index < actions.length && toolbarActionEnabled(actions[index]);
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
			case BUILD -> ToolbarAction.BUILD_ACTIONS;
			case SELECT -> ToolbarAction.SELECT_ACTIONS;
			case IMPORT, NONE -> new ToolbarAction[0];
		};
	}

	private boolean toolbarActionEnabled(ToolbarAction action) {
		return switch (action) {
			case UNDO -> history.canUndo();
			case REDO -> history.canRedo();
			case CONVERT, MERGE_REPEATS, QUANTIZE, FIT_ALL_RANGE, SNAP_TEMPO ->
				project().layers().stream().anyMatch(layer -> !layer.notes().isEmpty());
			case SELECT_OFF_GRID -> !projectStats().offGridNotes().isEmpty();
			case SELECT_TOO_FREQUENT -> !projectStats().crowdedNotes().isEmpty();
			case SELECT_OUT_OF_RANGE -> projectStats().outOfRange() > 0;
			case SELECT_NONE -> !selectedNotes.isEmpty();
			// Not disabled on an unbuildable song: greying it out would hide the reason. The status
			// bar already names the problem and the planner refuses with a specific one.
			case PASTE_IN_WORLD -> projectStats().totalNotes() > 0 || project().endTick() > 0L;
			case INCLUDE_SELECTED, SET_INCLUDED_TO_SELECTION -> !selectionLayers().isEmpty();
			case BUILD_CANCEL -> CommandPasteSender.isRunning();
			default -> true;
		};
	}

	private boolean handleToolbarMenuClick(double mouseX, double mouseY, int button) {
		List<String> rows = toolbarRows();
		if (rows.isEmpty() || mouseX < toolbarMenuX
				|| mouseX >= toolbarMenuX + toolbarMenuWidth()) {
			return false;
		}
		int row = ((int)mouseY - 30) / TOOLBAR_MENU_ROW_HEIGHT;
		if (row < 0 || row >= rows.size()) {
			return false;
		}
		int rowY = 30 + row * TOOLBAR_MENU_ROW_HEIGHT;
		if (mouseY < rowY || mouseY >= rowY + TOOLBAR_MENU_ROW_HEIGHT) {
			return false;
		}
		if (!toolbarRowEnabled(row)) {
			return true;
		}
		if (toolbarMenu == ToolbarMenu.IMPORT) {
			// Settings stay open so several can be adjusted in one visit.
			cycleImportSetting(ImportSetting.values()[row], button == 1 ? -1 : 1);
			return true;
		}
		ToolbarAction action = toolbarActions()[row];
		toolbarMenu = ToolbarMenu.NONE;
		performToolbarAction(action);
		return true;
	}

	private void performToolbarAction(ToolbarAction action) {
		switch (action) {
			case IMPORT -> importSong();
			case OPEN_SONGS -> minecraft.gui.setScreen(new SongsScreen(parent, config));
			case COPY_AS_TEXT -> copySequenceAsText();
			case INCLUDE_SELECTED -> setIncludedLayers(true);
			case SET_INCLUDED_TO_SELECTION -> setIncludedLayers(false);
			case PASTE_IN_WORLD -> pasteInWorld();
			case BUILD_CANCEL -> CommandPasteSender.cancel(true);
			case SAVE_COMPOSITION -> saveComposition();
			case SAVE_COMPOSITION_AS -> saveCompositionAs();
			case BACK_TO_SEQUENCES -> onClose();
			case CLOSE_TO_GAME -> closeToGame();
			case UNDO -> undo();
			case REDO -> redo();
			case CONVERT -> convertToMinecraft();
			case MERGE_REPEATS -> applyStep("Merged",
				project().withMergedRepeats(config.repeatMergeTicks(), selectedNotes));
			case QUANTIZE -> applyStep("Quantized",
				project().withQuantized(minecraftConversionGridTicks(project()), selectedNotes));
			case FIT_ALL_RANGE -> applyStep("Fitted to range",
				project().withAllFittedToRange(selectedNotes));
			case SNAP_TEMPO -> applyStep("Tempo snapped", project().withTempo(
				project().repeaterAlignedTempoFor(minecraftConversionGridTicks(project()))));
			case SNAP_END -> applyStep("End snapped", project().withEndTick(
				snapEndToRepeaterGrid()));
			case TRIM_END -> applyStep("Trimmed", project().trimmedToContent());
			case SELECT_OFF_GRID -> selectNotesWhere("off grid",
				note -> projectStats().offGrid().contains(note.startTick()), true);
			case SELECT_TOO_FREQUENT -> selectNotesWhere("too frequent",
				note -> projectStats().crowded().contains(note.startTick()), true);
			case SELECT_OUT_OF_RANGE -> selectNotesWhere("out of range",
				note -> !note.isBuildable(), true);
			case SELECT_ALL_NOTES -> selectNotesWhere("selected", note -> true, false);
			case SELECT_NONE -> {
				selectedNotes.clear();
				updateButtonStates();
			}
		}
	}

	/** Runs one conversion step on its own, so the preset does not have to be taken wholesale. */
	private void applyStep(String label, ComposerProject updated) {
		int before = project().noteCount();
		int beforeTempo = project().tempoMicrosPerQuarter();
		if (updated.equals(project())) {
			showResult(Component.literal("Nothing to change."));
			return;
		}
		int scoped = selectedNotes.size();
		apply(updated);
		rebuildLayerButtons();
		int removed = before - updated.noteCount();
		String report = label + (scoped > 0 ? " " + scoped + " selected notes" : " whole composition")
			+ (removed > 0 ? ": " + removed + " removed" : "");
		if (updated.tempoMicrosPerQuarter() != beforeTempo) {
			report += String.format(java.util.Locale.ROOT, ": tempo x%.2f",
				beforeTempo / (double)updated.tempoMicrosPerQuarter());
		}
		showResult(Component.literal(report));
	}

	/**
	 * Selects notes matching a build problem, within the selected layers.
	 *
	 * <p>When notes are already selected this narrows that set rather than replacing it, so box
	 * selecting a passage and then picking a fault leaves only the faulty notes of that passage.
	 * Selecting everything is the one action that widens, since it is how you start over.</p>
	 */
	private void selectNotesWhere(
		String label,
		java.util.function.Predicate<NoteEvent> match,
		boolean narrowExisting
	) {
		Set<Long> previous = Set.copyOf(selectedNotes);
		boolean narrowing = narrowExisting && !previous.isEmpty();
		selectedNotes.clear();
		for (int layerIndex : selectionLayers()) {
			Layer layer = project().layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (match.test(note) && (!narrowing || previous.contains(note.id()))) {
					selectedNotes.add(note.id());
				}
			}
		}
		updateButtonStates();
		String scope = narrowing
			? " of " + previous.size() + " selected"
			: " across " + selectionLayers().size()
				+ (selectionLayers().size() == 1 ? " layer" : " layers");
		showResult(Component.literal(
			selectedNotes.size() + " notes " + label + scope));
	}

	/**
	 * What a menu row does, in a sentence.
	 *
	 * <p>Every row has one. Several of these actions are irreversible in the world or change the
	 * whole song, and a bare verb like "Convert" or "Quantize" does not tell you which.</p>
	 */
	private String toolbarActionTooltip(ToolbarAction action) {
		return switch (action) {
			case IMPORT -> "Replace this composition with a MIDI or NBS file. Settings below "
				+ "control how it is read.";
			case OPEN_SONGS -> "The song library: open another composition, start one, or make a "
				+ "copy.";
			case COPY_AS_TEXT -> "Puts the build sequence on the clipboard, one line per included "
				+ "layer. Out-of-range notes and sub-tick timing do not survive the trip.";
			case SAVE_COMPOSITION -> "Writes this composition to its own file. Editing already "
				+ "saves on every change, so this only confirms it.";
			case SAVE_COMPOSITION_AS -> "Saves a copy under a new name and opens it. How to keep a "
				+ "version still while you carry on editing.";
			case BACK_TO_SEQUENCES -> "Leave the composer. Nothing is lost; edits are already saved.";
			case CLOSE_TO_GAME -> "Close straight back to the game.";
			case UNDO -> "Step back. History is kept for this visit only, not across sessions.";
			case REDO -> "Step forward again.";
			case CONVERT -> "Runs every fix in order: bake the speed into the tempo and reset the "
				+ "slider to 1.00x; collapse same-pitch repeats closer than the merge window; "
				+ "quantize note starts onto the chosen grid; octave-shift out-of-range notes in, "
				+ "splitting a layer per shift it needs; snap the tempo so the grid lands on whole "
				+ "repeater ticks; snap the end marker to match.";
			case MERGE_REPEATS -> "Collapses a pitch that re-triggers faster than the repeat "
				+ "window. Songs fake sustain this way, and note blocks cannot sustain.";
			case QUANTIZE -> "Moves note starts onto the musical grid. Fixes notes between beats, "
				+ "not a grid that disagrees with redstone -- that is Snap tempo.";
			case FIT_ALL_RANGE -> "Octave-shifts notes outside F#3-F#5 into it. Quick rather than "
				+ "faithful: intervals across a layer can change.";
			case SNAP_TEMPO -> "Nudges the tempo so the grid lands on whole repeater ticks. The "
				+ "other half of quantize, and neither works alone.";
			case SNAP_END -> "Moves the end marker so its trailing delay is a whole number of "
				+ "repeater ticks.";
			case TRIM_END -> "Pulls the end marker back to the last note, discarding trailing "
				+ "silence.";
			case INCLUDE_SELECTED -> "Fills in the build dot on the selected layers, adding them to "
				+ "the sequence. The others are left as they are.";
			case SET_INCLUDED_TO_SELECTION -> "Makes the selected layers the only included ones, "
				+ "clearing the rest. A batch off as well as a batch on.";
			case PASTE_IN_WORLD -> "Builds the sequence with /setblock. Needs permission, and "
				+ "overwrites whatever is standing there.";
			case BUILD_CANCEL -> "Stops a paste part-way. Blocks already placed stay put.";
			case SELECT_OFF_GRID -> "Selects notes whose gap from the previous one is not a whole "
				+ "repeater tick.";
			case SELECT_TOO_FREQUENT -> "Selects notes arriving less than one repeater tick after "
				+ "the previous one -- faster than redstone can retrigger.";
			case SELECT_OUT_OF_RANGE -> "Selects notes outside the note-block range of F#3-F#5.";
			case SELECT_ALL_NOTES -> "Selects every note on the active layers.";
			case SELECT_NONE -> "Clears the selection.";
		};
	}

	private static String layerActionTooltip(LayerAction action) {
		return switch (action) {
			case MERGE_SELECTED -> "Folds the selected layers into the lowest-numbered one, which "
				+ "keeps its name and instrument.";
			case INCLUDE_SELECTED -> "Fills in the build dot on the selected layers, adding them to "
				+ "the sequence.";
			case SET_INCLUDED_TO_SELECTION -> "Makes the selected layers the only included ones, "
				+ "clearing the rest.";
			case SELECT_ALL -> "Selects every layer.";
			case COLLAPSE_OTHERS -> "Collapses every layer but the active one, to get a long list "
				+ "out of the way.";
		};
	}

	private static String importSettingTooltip(ImportSetting setting) {
		return switch (setting) {
			case QUANTIZE_GRID -> "Grid that imported notes are snapped onto. Auto picks one from "
				+ "the song's own spacing.";
			case TEMPO_FIT -> "Snap to repeaters nudges the tempo so the grid lands on whole "
				+ "repeater ticks. Preserve original keeps the tempo and leaves the timing to you.";
			case RANGE_FIT -> "What to do with notes outside F#3-F#5: shift them by octaves, wrap "
				+ "them, clamp them to the edges, or keep them out of range for editing.";
			case REPEAT_MERGE -> "How close a repeat of the same pitch must be to be collapsed. "
				+ "This, not the velocity cutoff, is what removes fake sustain.";
			case GRID_OUTLIERS -> "Share of the closest note gaps the automatic grid may ignore. "
				+ "Without it a single tight pair sets the tempo for the whole song.";
			case VELOCITY_CUTOFF -> "Notes quieter than this are dropped, since note blocks have no "
				+ "volume. Set it too high and a quiet passage disappears -- the import report says "
				+ "what range the file uses.";
			case IGNORE_PERCUSSION -> "Skip MIDI channel 10, which is drums. They rarely map onto "
				+ "note-block pitches.";
			case MAX_TRACKS -> "How many parts to keep. Busiest first, so a sparse intro can be "
				+ "cut; the import report says when that happens.";
			case DEFAULT_INSTRUMENT -> "Note-block instrument given to every imported MIDI layer. "
				+ "NBS imports keep their own.";
		};
	}

	private static String contextActionTooltip(ContextAction action) {
		return switch (action) {
			case OCTAVE_DOWN -> "Drops the selected notes an octave.";
			case OCTAVE_UP -> "Raises the selected notes an octave.";
			case FIT_RANGE -> "Octave-shifts the selected notes into F#3-F#5, the range note "
				+ "blocks can play.";
			case COPY -> "Copies the selected notes.";
			case CUT -> "Copies the selected notes and removes them.";
			case DELETE -> "Removes the selected notes.";
		};
	}

	/** Keyboard equivalent, shown beside a menu row so the shortcut can be discovered by using it. */
	private static String toolbarShortcut(ToolbarAction action) {
		return switch (action) {
			case SAVE_COMPOSITION -> "Ctrl+S";
			case SAVE_COMPOSITION_AS -> "Ctrl+Shift+S";
			case OPEN_SONGS -> "Ctrl+O";
			case IMPORT -> "Ctrl+I";
			case COPY_AS_TEXT -> "Ctrl+Shift+C";
			case UNDO -> "Ctrl+Z";
			case REDO -> "Ctrl+Y";
			default -> "";
		};
	}

	private String toolbarRowLabel(ToolbarAction action) {
		int selected = selectionLayers().size();
		if (selected > 0 && action == ToolbarAction.INCLUDE_SELECTED) {
			return "Include " + layerCountLabel(selected) + " in sequence";
		}
		if (selected > 0 && action == ToolbarAction.SET_INCLUDED_TO_SELECTION) {
			return "Include only " + layerCountLabel(selected) + " in sequence";
		}
		return action.label;
	}

	/** "this layer" reads better than "these 1 layer", and the count matters at any size. */
	private static String layerCountLabel(int count) {
		return count == 1 ? "this layer" : "these " + count + " layers";
	}

	private String importSettingLabel(ImportSetting setting) {
		return setting.label + ": " + switch (setting) {
			case QUANTIZE_GRID -> switch (config.midiQuantizeGrid()) {
				case AUTO -> "Auto";
				case QUARTER -> "1/4";
				case EIGHTH -> "1/8";
				case SIXTEENTH -> "1/16";
			};
			case TEMPO_FIT -> config.midiTempoFit() == FastNoteblocksConfig.MidiTempoFit.SNAP_TO_REPEATERS
				? "Snap to repeaters"
				: "Preserve original";
			case RANGE_FIT -> switch (config.midiRangeFit()) {
				case OCTAVE_SHIFT -> "Octave shift";
				case OCTAVE_WRAP -> "Octave wrap";
				case CLAMP -> "Clamp";
				case REJECT_OUT_OF_RANGE -> "Reject";
			};
			case GRID_OUTLIERS -> config.conversionGapPercentile() <= 0
				? "none (strict)"
				: "ignore closest " + config.conversionGapPercentile() + "%";
			case REPEAT_MERGE -> config.repeatMergeTicks() <= FastNoteblocksConfig.MIN_REPEAT_MERGE_TICKS
				? "off (keep all)"
				: config.repeatMergeTicks() + (config.repeatMergeTicks() == 1 ? " tick" : " ticks");
			case VELOCITY_CUTOFF -> config.midiVelocityCutoff() <= FastNoteblocksConfig.MIN_MIDI_VELOCITY_CUTOFF
				? "off (keep all)"
				: Integer.toString(config.midiVelocityCutoff());
			case IGNORE_PERCUSSION -> config.midiIgnorePercussion() ? "ignored" : "imported";
			case MAX_TRACKS -> Integer.toString(config.midiMaxImportedTracks());
			case DEFAULT_INSTRUMENT -> PreviewInstrument.byId(config.midiDefaultInstrument()).name();
		};
	}

	private void cycleImportSetting(ImportSetting setting, int direction) {
		switch (setting) {
			case QUANTIZE_GRID -> config.setMidiQuantizeGrid(
				cycle(FastNoteblocksConfig.MidiQuantizeGrid.values(), config.midiQuantizeGrid(), direction));
			case TEMPO_FIT -> config.setMidiTempoFit(
				cycle(FastNoteblocksConfig.MidiTempoFit.values(), config.midiTempoFit(), direction));
			case RANGE_FIT -> config.setMidiRangeFit(
				cycle(FastNoteblocksConfig.MidiRangeFit.values(), config.midiRangeFit(), direction));
			case VELOCITY_CUTOFF -> config.setMidiVelocityCutoff(cycleVelocityCutoff(direction));
			case GRID_OUTLIERS -> {
				int span = FastNoteblocksConfig.MAX_CONVERSION_GAP_PERCENTILE
					- FastNoteblocksConfig.MIN_CONVERSION_GAP_PERCENTILE + 1;
				int offset = config.conversionGapPercentile()
					- FastNoteblocksConfig.MIN_CONVERSION_GAP_PERCENTILE;
				config.setConversionGapPercentile(FastNoteblocksConfig.MIN_CONVERSION_GAP_PERCENTILE
					+ Math.floorMod(offset + direction, span));
			}
			case REPEAT_MERGE -> {
				int span = FastNoteblocksConfig.MAX_REPEAT_MERGE_TICKS
					- FastNoteblocksConfig.MIN_REPEAT_MERGE_TICKS + 1;
				int offset = config.repeatMergeTicks() - FastNoteblocksConfig.MIN_REPEAT_MERGE_TICKS;
				config.setRepeatMergeTicks(FastNoteblocksConfig.MIN_REPEAT_MERGE_TICKS
					+ Math.floorMod(offset + direction, span));
			}
			case IGNORE_PERCUSSION -> config.setMidiIgnorePercussion(!config.midiIgnorePercussion());
			case MAX_TRACKS -> {
				int span = FastNoteblocksConfig.MAX_MIDI_MAX_IMPORTED_TRACKS
					- FastNoteblocksConfig.MIN_MIDI_MAX_IMPORTED_TRACKS + 1;
				int offset = config.midiMaxImportedTracks() - FastNoteblocksConfig.MIN_MIDI_MAX_IMPORTED_TRACKS;
				config.setMidiMaxImportedTracks(FastNoteblocksConfig.MIN_MIDI_MAX_IMPORTED_TRACKS
					+ Math.floorMod(offset + direction, span));
			}
			case DEFAULT_INSTRUMENT -> {
				int index = PreviewInstrument.VALUES.indexOf(
					PreviewInstrument.byId(config.midiDefaultInstrument()));
				config.setMidiDefaultInstrument(PreviewInstrument.VALUES.get(
					Math.floorMod(index + direction, PreviewInstrument.VALUES.size())).id());
			}
		}
		FastNoteblocksConfig.save();
	}

	/** Steps the cutoff in even increments, with a dedicated "off" stop at zero. */
	private int cycleVelocityCutoff(int direction) {
		int stops = FastNoteblocksConfig.MAX_MIDI_VELOCITY_CUTOFF / VELOCITY_CUTOFF_STEP + 1;
		int current = config.midiVelocityCutoff() / VELOCITY_CUTOFF_STEP;
		return Math.floorMod(current + direction, stops) * VELOCITY_CUTOFF_STEP;
	}

	private static <T extends Enum<T>> T cycle(T[] values, T current, int direction) {
		return values[Math.floorMod(current.ordinal() + direction, values.length)];
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
			if (hovered) {
				hoveredDescription = contextActionTooltip(actions[index]);
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

	/** The composition being edited, so which one it is never has to be remembered. */
	private void extractCompositionName(GuiGraphicsExtractor graphics) {
		int left = TOOLBAR_CONTROLS_RIGHT + 12;
		if (left > width - 40) {
			return;
		}
		String name = project().name();
		String shown = font.width(name) <= width - left - 8
			? name
			: font.plainSubstrByWidth(name, width - left - 16) + "...";
		graphics.text(font, shown, left, 13, 0xFFD6D8DD, false);
	}

	private void extractPanels(GuiGraphicsExtractor graphics) {
		graphics.fill(0, TOOLBAR_HEIGHT, LAYER_PANEL_WIDTH, height, 0xB8101115);
		graphics.fill(LAYER_PANEL_WIDTH, TOOLBAR_HEIGHT, width, height, 0x99101115);
		graphics.text(font, "Layers", 8, TOOLBAR_HEIGHT + 4, 0xFF8A9098, false);
		graphics.enableScissor(0, LAYER_LIST_TOP - 2, LAYER_PANEL_WIDTH, layerListBottom());
		for (int index = 0; index < project().layers().size(); index++) {
			int y = layerY(index);
			int rowHeight = layerRowHeight(index);
			if (y + rowHeight < LAYER_LIST_TOP - 2 || y > layerListBottom()) {
				continue;
			}
			int color = LAYER_COLORS[index % LAYER_COLORS.length];
			boolean activeLayer = index == project().activeLayerIndex();
			boolean selected = selectedLayers.contains(index);
			boolean collapsed = collapsedLayers.contains(index);
			graphics.fill(8, y - 2, LAYER_PANEL_WIDTH - 8, y + rowHeight - 2,
				activeLayer ? 0x88425A6B : selected ? 0x88344657 : 0x44252A31);
			if (selected) {
				// Unmistakable outline: a tinted background alone reads as noise on a dark panel.
				int edge = activeLayer ? 0xFF8FD3FF : 0xFF5C93B8;
				graphics.fill(8, y - 2, LAYER_PANEL_WIDTH - 8, y - 1, edge);
				graphics.fill(8, y + rowHeight - 3, LAYER_PANEL_WIDTH - 8, y + rowHeight - 2, edge);
				graphics.fill(LAYER_PANEL_WIDTH - 9, y - 2, LAYER_PANEL_WIDTH - 8, y + rowHeight - 2, edge);
			}
			graphics.fill(8, y - 2, LAYER_PANEL_WIDTH - 8, y - 1, activeLayer ? color : 0x66383D44);
			graphics.fill(8, y + rowHeight - 3, LAYER_PANEL_WIDTH - 8, y + rowHeight - 2,
				activeLayer ? color : 0x88383D44);
			graphics.fill(8, y - 2, 12, y + rowHeight - 2, color);
			if (activeLayer) {
				graphics.fill(12, y, LAYER_PANEL_WIDTH - 10, y + 20, 0x553D444D);
			}
			graphics.text(font, Component.literal(collapsed ? "▸" : "▾"), 15, y + 6,
				activeLayer ? 0xFFFFFFFF : 0xFF9BA0A6, false);
			Layer layer = project().layers().get(index);
			String summary = collapsed ? "  (" + layer.notes().size() + ")" : "";
			String mark = selected ? "✓ " : "";
			graphics.text(font, Component.literal(mark + "L" + (index + 1) + "  " + layer.name() + summary),
				26, y + 6, activeLayer ? 0xFFFFFFFF : selected ? 0xFFE8F4FF : 0xFFD6D8DD, false);
			// Filled means this layer goes into the build sequence. Drawn on the header rather than
			// in the button strip so it survives collapsing -- the point is telling at a glance what
			// is in, and a row you cannot see cannot tell you anything.
			graphics.text(font, Component.literal(layer.buildEnabled() ? "●" : "○"),
				buildDotX(), y + 6, layer.buildEnabled() ? 0xFF5AD46A : 0xFF6A7078, false);
		}
		graphics.disableScissor();
		extractLayerScrollbar(graphics);
		// This panel draws without mouse coordinates, so the cached position is what there is.
		int hoveredDot = buildDotAt(lastMouseX, lastMouseY);
		if (hoveredDot >= 0) {
			graphics.setTooltipForNextFrame(Component.literal(
				project().layers().get(hoveredDot).buildEnabled()
					? "In the build sequence - click to leave it out"
					: "Left out of the build sequence - click to include it"),
				(int)lastMouseX, (int)lastMouseY);
		}
	}

	private void extractLayerScrollbar(GuiGraphicsExtractor graphics) {
		int maximum = maxLayerScroll();
		if (maximum <= 0) {
			return;
		}
		int trackTop = LAYER_LIST_TOP - 2;
		int trackHeight = layerListBottom() - trackTop;
		int contentHeight = layerContentHeight();
		int thumbHeight = Math.max(16, trackHeight * trackHeight / Math.max(1, contentHeight));
		int thumbTop = trackTop + (trackHeight - thumbHeight) * layerScroll / maximum;
		int x = LAYER_PANEL_WIDTH - 6;
		graphics.fill(x, trackTop, x + 3, trackTop + trackHeight, 0x40FFFFFF);
		graphics.fill(x, thumbTop, x + 3, thumbTop + thumbHeight, 0xAAFFFFFF);
	}

	private void extractTimeRuler(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int rulerY = rollY - TIMELINE_RULER_HEIGHT;
		graphics.fill(rollX, rulerY, rollX + rollWidth, rollY, 0xCC15181D);
		graphics.fill(rollX, rollY - 1, rollX + rollWidth, rollY, 0xFF4A4F56);
		long lastTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		long beatTicks = Math.max(1L, project().ppq());
		long measureTicks = beatTicks * 4L;
		long stepTicks = readableStep(beatTicks, measureTicks);
		boolean showLabels = Math.max(stepTicks, measureTicks) / ticksPerPixel >= MIN_LABEL_PIXEL_SPACING;
		for (long tick = Math.max(0L, horizontalScroll / stepTicks * stepTicks);
				tick <= lastTick + stepTicks; tick += stepTicks) {
			int x = tickX(tick);
			if (x < rollX || x > rollX + rollWidth) {
				continue;
			}
			boolean measure = tick % measureTicks == 0L;
			graphics.fill(x, rulerY + (measure ? 1 : 6), x + 1, rollY, measure ? 0xFF9A9A9A : 0xFF686D73);
			if (measure && showLabels) {
				graphics.text(font, Long.toString(tick / measureTicks + 1L), x + 3, rulerY + 2, 0xFFBFC4CA, false);
			}
		}
		int endX = tickX(project().endTick());
		if (endX >= rollX && endX <= rollX + rollWidth) {
			// Flag points back over the song, so the marker reads as the edge of something rather
			// than the start of it. Red when the trailing gap is not a delay a build can place.
			int endColor = projectStats().endMarkerIssue() ? 0xFFFF6B6B : 0xFFE8C05A;
			graphics.fill(endX, rulerY + 1, endX + 1, rollY, endColor);
			graphics.fill(endX - 7, rulerY + 1, endX, rulerY + 6, endColor);
		}
		long markerTick = playing ? playbackTick() : playbackStartTick;
		int markerX = tickX(markerTick);
		if (markerX >= rollX && markerX <= rollX + rollWidth) {
			graphics.fill(markerX - 3, rulerY + 1, markerX + 4, rulerY + 5, 0xFFFF5555);
			graphics.fill(markerX - 1, rulerY + 5, markerX + 2, rollY, 0xFFFF5555);
		}
		if (mouseX >= rollX && mouseX < rollX + rollWidth && mouseY >= rulerY && mouseY < rollY) {
			graphics.setTooltipForNextFrame(Component.literal(overEndMarker(mouseX, mouseY)
				? "Drag to set where the song ends"
				: "Drag to set playback start"), mouseX, mouseY);
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
			if (y + rowHeight < rollY) {
				continue;
			}
			boolean black = isBlackKey(midi);
			boolean buildable = midi >= ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
				&& midi <= ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE;
			int gridColor = black ? 0xB9181A20 : 0xB91D2026;
			if (buildable) {
				gridColor = black ? 0xC31C3B42 : 0xC322454C;
			}
			graphics.fill(pianoX, y, rollX, y + rowHeight - 1, 0xFFE7E7E7);
			if (black) {
				graphics.fill(pianoX, y, pianoX + PIANO_WIDTH * 2 / 3, y + rowHeight - 1, 0xFF303238);
			}
			graphics.fill(pianoX, y + rowHeight - 1, rollX, y + rowHeight, 0xFF55575C);
			graphics.fill(rollX, y, rollX + rollWidth, y + rowHeight - 1, gridColor);
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
		extractCompositionName(graphics);
	}

	private void extractTimeGrid(GuiGraphicsExtractor graphics) {
		long subdivisionTicks = snapSubdivision == 0
			? Math.max(1L, project().ppq() / 4L)
			: gridTicks();
		long lastTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		long measureTicks = Math.max(1L, project().ppq() * 4L);
		long stepTicks = readableStep(subdivisionTicks, measureTicks);
		boolean showLabels = Math.max(stepTicks, measureTicks) / ticksPerPixel >= MIN_LABEL_PIXEL_SPACING;
		for (long tick = Math.max(0L, horizontalScroll / stepTicks * stepTicks);
				tick <= lastTick + stepTicks; tick += stepTicks) {
			int x = tickX(tick);
			if (x < rollX || x > rollX + rollWidth) {
				continue;
			}
			boolean beat = tick % project().ppq() == 0;
			boolean measure = tick % measureTicks == 0;
			graphics.fill(x, rollY, x + 1, rollY + rollHeight,
				measure ? 0x66777777 : beat ? 0x443F444A : 0x242F343A);
			if (measure && showLabels) {
				graphics.text(font, Long.toString(tick / measureTicks + 1),
					x + 3, rollY + 2, 0xFFAAAAAA, false);
			}
		}
		for (Map.Entry<Long, Integer> entry : projectStats().chordCounts().entrySet()) {
			if (entry.getValue() <= SongAnalysis.MAX_SIMULTANEOUS_NOTES) {
				continue;
			}
			int x = tickX(entry.getKey());
			if (x >= rollX && x <= rollX + rollWidth) {
				graphics.fill(x - 1, rollY, x + 2, rollY + rollHeight, 0x66FF3333);
			}
		}
	}

	/**
	 * Widens a grid step until its lines are at least {@link #MIN_GRID_PIXEL_SPACING} pixels apart,
	 * snapping to whole measures once the step outgrows one. Low-resolution projects — NBS imports,
	 * coarse MIDI files — otherwise ask for a line every tick, which at low zoom means tens of
	 * thousands of fills and measure labels every single frame.
	 */
	private long readableStep(long stepTicks, long measureTicks) {
		long step = Math.max(1L, stepTicks);
		while (step / ticksPerPixel < MIN_GRID_PIXEL_SPACING) {
			step *= 2L;
		}
		if (step > measureTicks) {
			step = (step + measureTicks - 1L) / measureTicks * measureTicks;
		}
		return step;
	}

	private void extractNotes(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ComposerProject shown = displayProject();
		SongAnalysis stats = projectStats();
		Set<Long> offGrid = stats.offGrid();
		Set<Long> crowded = stats.crowded();
		NoteEvent hoveredCandidate = null;
		int hoveredCandidateLayer = -1;
		long firstVisibleTick = Math.max(0L, horizontalScroll - stats.maximumNoteDuration());
		long lastVisibleTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		for (int layerIndex : noteDrawOrder(shown)) {
			Layer layer = shown.layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			boolean active = layerIndex == shown.activeLayerIndex();
			boolean highlighted = active || selectedLayers.contains(layerIndex);
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
				int color = highlighted ? LAYER_COLORS[layerIndex % LAYER_COLORS.length] : 0xFF777A80;
				if (!note.isBuildable()) {
					color = highlighted ? 0xFFFF6B6B : 0xFF755050;
				}
				if (selected) {
					graphics.fill(rect.left - 1, rect.top - 1, rect.right + 1, rect.bottom + 1, 0xFFFFFFFF);
				}
				graphics.fill(rect.left, rect.top, rect.right, rect.bottom, color);
				boolean tooFrequent = crowded.contains(note.startTick());
				if (tooFrequent || offGrid.contains(note.startTick())) {
					// Outline rather than recolour: a layer colour may itself be orange.
					// Orange means arrives-too-soon-to-build; yellow means lands-between-ticks.
					int warn = tooFrequent
						? (highlighted ? 0xFFFF9A2E : 0x55FF9A2E)
						: (highlighted ? 0xFFFFE45C : 0x55FFE45C);
					graphics.fill(rect.left, rect.top, rect.right, rect.top + 1, warn);
					graphics.fill(rect.left, rect.bottom - 1, rect.right, rect.bottom, warn);
					graphics.fill(rect.left, rect.top, rect.left + 1, rect.bottom, warn);
					graphics.fill(rect.right - 1, rect.top, rect.right, rect.bottom, warn);
				}
				if (mouseX >= rect.left && mouseX < rect.right
						&& mouseY >= rect.top && mouseY < rect.bottom) {
					// Topmost wins: draw order runs back to front, so a later hit overwrites.
					hoveredCandidate = note;
					hoveredCandidateLayer = layerIndex;
				}
			}
		}
		int endX = tickX(project().endTick());
		if (endX >= rollX && endX <= rollX + rollWidth) {
			graphics.fill(endX, rollY, endX + 1, rollY + rollHeight, 0x66E8C05A);
		}
		extractHoveredNoteTooltip(graphics, hoveredCandidate, hoveredCandidateLayer,
			crowded, offGrid, mouseX, mouseY);
	}

	/**
	 * Shows details for the note under the cursor once it has been held there briefly.
	 *
	 * <p>The dwell gate matters on dense imports: without it, sweeping the mouse across a packed
	 * roll fires a different tooltip every frame.</p>
	 */
	private void extractHoveredNoteTooltip(
		GuiGraphicsExtractor graphics,
		NoteEvent note,
		int layerIndex,
		Set<Long> crowded,
		Set<Long> offGrid,
		int mouseX,
		int mouseY
	) {
		if (note == null || layerIndex < 0 || layerIndex >= project().layers().size()) {
			hoveredNoteId = -1L;
			return;
		}
		if (note.id() != hoveredNoteId) {
			hoveredNoteId = note.id();
			hoveredSince = Util.getMillis();
			return;
		}
		if (Util.getMillis() - hoveredSince < TOOLTIP_DWELL_MILLIS) {
			return;
		}
		// One Component per line: the single-Component overload does not break on newlines, it
		// renders them as missing-glyph boxes.
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(midiName(note.midiNote()) + "   tick " + note.startTick()));
		lines.add(Component.literal("Layer " + (layerIndex + 1) + "  "
				+ project().layers().get(layerIndex).name())
			.withStyle(net.minecraft.ChatFormatting.GRAY));
		if (note.isBuildable()) {
			lines.add(Component.literal("Note block pitch " + note.noteBlockPitch())
				.withStyle(net.minecraft.ChatFormatting.GRAY));
			long sustained = note.durationTicks();
			if (sustained > project().ppq() / 4L) {
				double seconds = sustained * project().tempoMicrosPerQuarter()
					/ (double)project().ppq() / 1_000_000.0 / timescaleFactor();
				lines.add(Component.literal(String.format(java.util.Locale.ROOT,
						"Spans %.2fs - struck once, note blocks do not sustain", seconds))
					.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			}
		} else {
			int shift = octaveShiftIntoRange(note.midiNote());
			lines.add(Component.literal("Outside Minecraft range" + (shift == 0
					? " - no octave fits"
					: " - shifts " + (shift > 0 ? "+" : "-") + Math.abs(shift / 12) + " oct on convert"))
				.withStyle(net.minecraft.ChatFormatting.RED));
		}
		SongAnalysis timing = projectStats();
		if (crowded.contains(note.startTick())) {
			lines.add(Component.literal("Too frequent - only "
					+ timing.gapLabel(note.startTick()) + " repeater ticks after the previous note")
				.withStyle(net.minecraft.ChatFormatting.GOLD));
		}
		if (offGrid.contains(note.startTick())) {
			lines.add(Component.literal("Off grid - "
					+ timing.gapLabel(note.startTick())
					+ " repeater ticks after the previous note, not a whole number")
				.withStyle(net.minecraft.ChatFormatting.YELLOW));
		}
		graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
	}

	/** Nearest octave shift that would bring a note into the note-block range, or 0 if none does. */
	private static int octaveShiftIntoRange(int midiNote) {
		int best = 0;
		int bestDistance = Integer.MAX_VALUE;
		for (int shift = -120; shift <= 120; shift += 12) {
			int shifted = midiNote + shift;
			if (shifted >= ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
					&& shifted <= ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE
					&& Math.abs(shift) < bestDistance) {
				best = shift;
				bestDistance = Math.abs(shift);
			}
		}
		return best;
	}

	/**
	 * Paint order, back to front. Panel order is inverted so the row nearest the top of the list
	 * wins overlaps, selected layers lift above unselected ones, and the active layer is drawn
	 * last so it is never buried by a layer that happens to sit below it.
	 */
	private List<Integer> noteDrawOrder(ComposerProject shown) {
		int active = shown.activeLayerIndex();
		List<Integer> order = new ArrayList<>();
		for (int index = shown.layers().size() - 1; index >= 0; index--) {
			if (index != active && !selectedLayers.contains(index)) {
				order.add(index);
			}
		}
		for (int index = shown.layers().size() - 1; index >= 0; index--) {
			if (index != active && selectedLayers.contains(index)) {
				order.add(index);
			}
		}
		if (active >= 0 && active < shown.layers().size()) {
			order.add(active);
		}
		return order;
	}

	/** Layers that note selection and box-select act on. */
	private List<Integer> selectionLayers() {
		List<Integer> valid = selectedLayers.stream()
			.filter(index -> index >= 0 && index < project().layers().size())
			.sorted()
			.toList();
		return valid.isEmpty() ? List.of(project().activeLayerIndex()) : valid;
	}

	private void extractPlayhead(GuiGraphicsExtractor graphics) {
		long tick = playing ? playbackTick() : playbackStartTick;
		int x = tickX(tick);
		if (x >= rollX && x <= rollX + rollWidth) {
			graphics.fill(x, rollY, x + 2, rollY + rollHeight, 0xFFFF5555);
		}
	}

	/**
	 * Shows a result inside the composer.
	 *
	 * <p>These used to go to the HUD overlay message, which is drawn behind an open screen, so
	 * every import report, conversion result and save confirmation was invisible until the composer
	 * was closed -- by which point it had faded.</p>
	 */
	private void showResult(Component message) {
		toast = message;
		toastShownAt = Util.getMillis();
	}

	private void extractToast(GuiGraphicsExtractor graphics) {
		if (toast == null) {
			return;
		}
		if (Util.getMillis() - toastShownAt > TOAST_MILLIS) {
			toast = null;
			return;
		}
		// Wrapped, not centred on one line. Import reports list everything they left out and are
		// routinely longer than the roll is wide, so the single line ran off the edge taking the
		// most useful half of the message with it.
		int maxWidth = Math.max(120, rollWidth - 32);
		List<net.minecraft.util.FormattedCharSequence> lines = font.split(toast, maxWidth);
		int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
		int x = rollX + Math.max(4, (rollWidth - textWidth) / 2);
		int y = rollY + 8;
		int height = lines.size() * font.lineHeight + (lines.size() - 1) * 2;
		graphics.fill(x - 6, y - 5, x + textWidth + 6, y + height + 4, 0xF01A1F26);
		graphics.fill(x - 6, y - 5, x + textWidth + 6, y - 4, 0xFF8FD3FF);
		for (int index = 0; index < lines.size(); index++) {
			graphics.text(font, lines.get(index), x, y + index * (font.lineHeight + 2), 0xFFFFFFFF, false);
		}
	}

	private void extractStatus(GuiGraphicsExtractor graphics) {
		SongAnalysis stats = projectStats();
		int peakChord = stats.peakChord();
		long overloaded = stats.overloadedTicks();
		boolean ready = stats.buildable();

		// Most important first: the verdict, then whatever is blocking it, then context.
		List<String> segments = new ArrayList<>();
		segments.add(ready ? "MINECRAFT READY" : "NOT BUILDABLE");
		if (stats.outOfRange() > 0) {
			segments.add(stats.outOfRange() + " out of range");
		}
		if (!stats.crowdedNotes().isEmpty()) {
			segments.add(stats.crowdedNotes().size() + " too frequent");
		}
		if (!stats.offGridNotes().isEmpty()) {
			segments.add(stats.offGridNotes().size() + " off grid");
		}
		// Named rather than counted: it is one thing, it is not a note, and saying "1 too
		// frequent" sent you looking for a note that does not exist.
		if (!stats.endMarkerProblem().isEmpty()) {
			segments.add(stats.endMarkerProblem() + " (Edit > Snap end to grid)");
		}
		segments.add("peak " + peakChord + "/" + SongAnalysis.MAX_SIMULTANEOUS_NOTES
			+ (overloaded > 0 ? " (" + overloaded + " over)" : ""));
		segments.add(stats.totalNotes() + " notes · " + project().layers().size() + " layers");
		int included = (int)project().layers().stream()
			.filter(Layer::buildEnabled)
			.count();
		if (included == 0) {
			segments.add("nothing included");
		} else {
			SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(project());
			segments.add(blocks.total() + " blocks (" + blocks.noteBlocks() + " note · "
				+ blocks.repeaters() + " repeater)");
		}
		if (!selectedNotes.isEmpty()) {
			segments.add(selectedNotes.size() + " selected");
		}

		// Drop trailing detail instead of running off the edge.
		int available = width - 16;
		StringBuilder status = new StringBuilder();
		for (String segment : segments) {
			String candidate = status.isEmpty() ? segment : status + "   " + segment;
			if (font.width(candidate) > available) {
				break;
			}
			status.setLength(0);
			status.append(candidate);
		}
		int color = ready
			? 0xFF5AD46A
			: peakChord >= CHORD_WARNING_THRESHOLD || overloaded > 0 ? 0xFFFF7777 : 0xFFFFAA00;
		graphics.text(font, status.toString(), 8, height - 16, color, false);
	}

	private SongAnalysis projectStats() {
		ComposerProject current = project();
		// The speed is part of the project now, so identity is the whole cache key.
		if (cachedStatsProject == current && cachedStats != null) {
			return cachedStats;
		}
		cachedStatsProject = current;
		cachedStats = SongAnalysis.of(current);
		return cachedStats;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (toolbarMenu != ToolbarMenu.NONE) {
			if (handleToolbarMenuClick(event.x(), event.y(), event.button())) {
				return true;
			}
			toolbarMenu = ToolbarMenu.NONE;
		}
		if (layerMenuOpen) {
			if (handleLayerMenuClick(event.x(), event.y())) {
				return true;
			}
			layerMenuOpen = false;
		}
		if (contextMenuOpen) {
			if (handleContextMenuClick(event.x(), event.y())) {
				return true;
			}
			contextMenuOpen = false;
		}
		if (event.button() == 1) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0) {
				if (!selectedLayers.contains(layerIndex)) {
					selectLayer(layerIndex, false, false);
				}
				layerMenuOpen = true;
				layerMenuX = (int)event.x();
				layerMenuY = (int)event.y();
				contextMenuOpen = false;
				return true;
			}
		}
		if (event.button() == 0 && doubleClick) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0 && !isLayerCollapseArrow(event.x())
					&& !controlDown() && !shiftDown()) {
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
			int dotLayer = buildDotAt(event.x(), event.y());
			if (dotLayer >= 0) {
				boolean next = !project().layers().get(dotLayer).buildEnabled();
				updateLayers(dotLayer, target -> target.withBuildEnabled(next));
				showResult(Component.literal(sequenceSummary()));
				return true;
			}
		}
		if (event.button() == 0) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0 && isLayerCollapseArrow(event.x())) {
				toggleLayerCollapsed(layerIndex);
				return true;
			}
			if (layerIndex >= 0) {
				selectLayer(layerIndex, controlDown(), shiftDown());
				selectedNotes.clear();
				return true;
			}
		}
		if (event.button() == 0 && overEndMarker(event.x(), event.y())) {
			draggingEndMarker = true;
			lastEndDragAt = 0L;
			return true;
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
			if (!selectedLayers.contains(hit.layerIndex())) {
				selectedNotes.clear();
				selectedLayers.clear();
				selectedLayers.add(hit.layerIndex());
				apply(project().withActiveLayer(hit.layerIndex()));
				rebuildLayerButtons();
			} else if (hit.layerIndex() != project().activeLayerIndex()) {
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
		updateLayers(instrumentMenuLayer,
			target -> target.withInstrument(value.id()).withMuted("MUTE".equals(value.id())));
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
		lastMouseX = event.x();
		lastMouseY = event.y();
		if (draggingEndMarker) {
			setEndTick(snapTick(mouseTick(event.x())));
			return true;
		}
		if (draggingPlayhead) {
			setPlaybackStart(mouseTick(event.x()), false);
			return true;
		}
		if (draggingNotes) {
			long tickDelta = snapDelta(Math.round((event.x() - dragStartX) * ticksPerPixel));
			int pitchDelta = (int)Math.round((dragStartY - event.y()) / rowHeight);
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
		if (draggingEndMarker) {
			draggingEndMarker = false;
			return true;
		}
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
			horizontalEdgeSince = 0L;
			verticalEdgeSince = 0L;
			selectNotesInBox();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX < LAYER_PANEL_WIDTH && mouseY >= LAYER_LIST_TOP - 2 && mouseY <= layerListBottom()) {
			scrollLayers(scrollY > 0 ? -LAYER_COLLAPSED_ROW_HEIGHT : LAYER_COLLAPSED_ROW_HEIGHT);
			return true;
		}
		if (mouseX >= LAYER_PANEL_WIDTH && mouseX < rollX
				&& mouseY >= rollY && mouseY < rollY + rollHeight) {
			if (controlDown()) {
				// Vertical zoom: shrink the rows to fit more of the pitch range on screen at once.
				int anchoredMidi = mouseMidi(mouseY);
				rowHeight = Math.max(MIN_ROW_HEIGHT, Math.min(MAX_ROW_HEIGHT,
					rowHeight + (scrollY > 0 ? 1 : -1)));
				topMidiNote = Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE,
					anchoredMidi + (int)Math.floor((mouseY - rollY) / rowHeight)));
				return true;
			}
			topMidiNote = Math.max(12, Math.min(MAX_MIDI_NOTE,
				topMidiNote + (scrollY > 0 ? 3 : -3)));
			return true;
		}
		if (!insideRoll(mouseX, mouseY)) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		if (controlDown()) {
			long anchoredTick = mouseTick(mouseX);
			ticksPerPixel = Math.max(1.5, Math.min(maxTicksPerPixel(),
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
			if (playing || anythingAudible()) {
				togglePlayback();
			}
			return true;
		}
		if (event.isSelectAll()) {
			selectedNotes.clear();
			for (int layerIndex : selectionLayers()) {
				project().layers().get(layerIndex).notes()
					.forEach(note -> selectedNotes.add(note.id()));
			}
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
		if (event.hasControlDownWithQuirk() && layerNameBox == null) {
			// Ctrl+C is already taken by copying notes, so copying the sequence takes the shifted
			// one, the way editors usually shift a variant of an existing action.
			switch (event.key()) {
				case GLFW.GLFW_KEY_S -> {
					if (event.hasShiftDown()) {
						saveCompositionAs();
					} else {
						saveComposition();
					}
					return true;
				}
				case GLFW.GLFW_KEY_O -> {
					minecraft.gui.setScreen(new SongsScreen(parent, config));
					return true;
				}
				case GLFW.GLFW_KEY_I -> {
					importSong();
					return true;
				}
				default -> {
					if (event.hasShiftDown() && event.key() == GLFW.GLFW_KEY_C) {
						copySequenceAsText();
						return true;
					}
				}
			}
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
		updateBoxScroll();
		updateButtonStates();
	}

	/**
	 * Scrolls the roll while a selection box is dragged past its edge, faster the further out the
	 * cursor is. The drag anchor is moved by the same amount so the box stays pinned to the notes
	 * it was started over rather than sliding across them.
	 */
	/**
	 * Speed multiplier for a cursor {@code distance} past an edge with {@code runway} pixels of
	 * screen beyond it.
	 *
	 * <p>Distance alone cannot work near a window edge, where there is nowhere left to move the
	 * mouse. So the ramp is measured against the runway that edge actually has, and holding there
	 * keeps accelerating -- which is the only control left once the cursor is against the glass.</p>
	 */
	private static double edgeRamp(double distance, double runway, long since) {
		double ramp = Math.min(1.0, distance / Math.max(1.0, Math.min(BOX_SCROLL_FULL_SPEED_PIXELS, runway)));
		double held = since == 0L ? 0.0 : (Util.getMillis() - since) / BOX_SCROLL_BOOST_MILLIS;
		return ramp * (1.0 + Math.min(1.0, held) * (BOX_SCROLL_HELD_BOOST - 1.0));
	}

	private void updateBoxScroll() {
		if (!selectingBox) {
			return;
		}
		double right = lastMouseX - (rollX + rollWidth);
		double left = rollX - lastMouseX;
		double below = lastMouseY - (rollY + rollHeight);
		double above = rollY - lastMouseY;

		double horizontal = right > 0 ? right : left > 0 ? -left : 0.0;
		horizontalEdgeSince = horizontal == 0.0 ? 0L
			: horizontalEdgeSince == 0L ? Util.getMillis() : horizontalEdgeSince;
		if (horizontal != 0.0) {
			// Runway is what the window actually leaves beyond that edge. The roll ends eight
			// pixels from the right, so ramping over a fixed 140 meant rightward scrolling could
			// only ever reach six per cent of the speed leftward got from the layer panel's width.
			double runway = right > 0 ? width - (rollX + rollWidth) : rollX;
			double pixels = Math.signum(horizontal)
				* edgeRamp(Math.abs(horizontal), runway, horizontalEdgeSince)
				* BOX_SCROLL_MAX_PIXELS;
			long previous = horizontalScroll;
			horizontalScroll = Math.max(0L, horizontalScroll + Math.round(pixels * ticksPerPixel));
			dragStartX -= (horizontalScroll - previous) / ticksPerPixel;
		}

		double vertical = below > 0 ? below : above > 0 ? -above : 0.0;
		verticalEdgeSince = vertical == 0.0 ? 0L
			: verticalEdgeSince == 0L ? Util.getMillis() : verticalEdgeSince;
		if (vertical != 0.0) {
			double runway = below > 0 ? height - (rollY + rollHeight) : rollY;
			double rows = Math.signum(vertical)
				* edgeRamp(Math.abs(vertical), runway, verticalEdgeSince)
				* BOX_SCROLL_MAX_ROWS;
			int previous = topMidiNote;
			topMidiNote = Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE,
				topMidiNote - (int)Math.round(rows)));
			dragStartY += (topMidiNote - previous) * (double)rowHeight;
		}
		selectionEndX = lastMouseX;
		selectionEndY = lastMouseY;
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
			resetPlaybackSchedule();
			playButton.setMessage(playLabel());
		}
	}

	private void setPlaybackStart(long tick, boolean preview) {
		playbackStartTick = Math.max(0L, Math.min(project().endTick(), snapTick(tick)));
		if (playing) {
			playbackStartedAt = Util.getMillis();
			resetPlaybackSchedule();
		}
		if (preview) {
			showResult(Component.literal("Playback start: tick " + playbackStartTick));
		}
	}

	private void stopPlayback() {
		playing = false;
		playbackEvents = List.of();
		playbackEventIndex = 0;
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
		long staleBefore = Math.max(playbackStartTick, tick - previewBacklogToleranceTicks());
		int soundsPlayed = 0;
		while (playbackEventIndex < playbackEvents.size()
				&& playbackEvents.get(playbackEventIndex).tick() <= tick) {
			PlaybackEvent event = playbackEvents.get(playbackEventIndex++);
			if (event.tick() >= staleBefore && soundsPlayed < MAX_PREVIEW_SOUNDS_PER_FRAME) {
				event.instrument().play(event.note());
				soundsPlayed++;
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

	/**
	 * Playback speed multiplier from the timescale slider.
	 *
	 * <p>The exported delay for a gap is {@code physical * 4 / delayScaleQuarters}, so a larger
	 * scale means shorter repeater delays and a faster build. Preview applies the same factor so
	 * what you hear matches what gets placed — the slider is the knob for finding a speed Minecraft
	 * can actually play, which is useless if you cannot hear its effect.</p>
	 */
	private double timescaleFactor() {
		return Math.max(1, delayScaleQuarters()) / (double)ComposerProject.DEFAULT_SPEED_QUARTERS;
	}

	private long playbackTick() {
		long elapsedMicros = Math.max(0L, Util.getMillis() - playbackStartedAt) * 1000L;
		return playbackStartTick + Math.round(elapsedMicros * project().ppq() * timescaleFactor()
			/ (double)project().tempoMicrosPerQuarter());
	}

	private long previewBacklogToleranceTicks() {
		return Math.max(1L, (long)Math.ceil(
			PREVIEW_BACKLOG_TOLERANCE_MICROS * project().ppq() * timescaleFactor()
				/ (double)project().tempoMicrosPerQuarter()
		));
	}

	private boolean anythingAudible() {
		for (int index = 0; index < project().layers().size(); index++) {
			if (audible(index) && !project().layers().get(index).notes().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Whether a layer is heard: soloing any layer silences the rest until it is cleared. */
	private boolean audible(int layerIndex) {
		if (layerIndex < 0 || layerIndex >= project().layers().size()) {
			return false;
		}
		if (!soloedLayers.isEmpty()) {
			return soloedLayers.contains(layerIndex);
		}
		return !project().layers().get(layerIndex).muted();
	}

	private void resetPlaybackSchedule() {
		List<PlaybackEvent> events = new ArrayList<>();
		for (int layerIndex = 0; layerIndex < project().layers().size(); layerIndex++) {
			Layer layer = project().layers().get(layerIndex);
			if (!audible(layerIndex)) {
				continue;
			}
			PreviewInstrument instrument = PreviewInstrument.byId(layer.instrument());
			if (!instrument.playable()) {
				continue;
			}
			List<NoteEvent> notes = layer.notes();
			for (int index = lowerBoundStart(notes, playbackStartTick); index < notes.size(); index++) {
				NoteEvent note = notes.get(index);
				events.add(new PlaybackEvent(
					note.startTick(),
					instrument,
					note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
				));
			}
		}
		events.sort(Comparator.comparingLong(PlaybackEvent::tick)
			.thenComparing(event -> event.instrument().id())
			.thenComparingInt(PlaybackEvent::note));
		List<PlaybackEvent> deduplicated = new ArrayList<>(events.size());
		PlaybackEvent previous = null;
		for (PlaybackEvent event : events) {
			if (previous == null || !event.sameSound(previous)) {
				deduplicated.add(event);
				previous = event;
			}
		}
		playbackEvents = List.copyOf(deduplicated);
		playbackEventIndex = 0;
	}

	private void undo() {
		history.undo();
		afterHistoryMove();
	}

	private void redo() {
		history.redo();
		afterHistoryMove();
	}

	private void afterHistoryMove() {
		playbackStartTick = Math.min(playbackStartTick, project().endTick());
		selectedNotes.clear();
		// setScale only moves the widget; it does not fire the listener, so this cannot loop back
		// into another history entry.
		if (delayScaleSlider != null) {
			delayScaleSlider.setScale(delayScaleQuarters());
		}
		saveProject();
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void apply(ComposerProject project) {
		history.apply(project);
		afterStateChange();
	}

	private void afterStateChange() {
		playbackStartTick = Math.min(playbackStartTick, project().endTick());
		if (playing) {
			playbackStartedAt = Util.getMillis();
			resetPlaybackSchedule();
		}
		saveProject();
		updateButtonStates();
	}

	/**
	 * Writes the composition onto the build tracks.
	 *
	 * <p>Explicit rather than automatic on close: the projection drops everything the track text
	 * cannot express, so leaving the composer should never quietly rewrite a sequence.</p>
	 */
	/**
	 * Writes the composition to its own file and says so.
	 *
	 * <p>Editing already saves on every change, so this changes nothing on disk that was not
	 * already there. It exists because autosave is invisible, and being able to press save and be
	 * told it worked is worth more than the keystroke costs.</p>
	 */
	private void saveComposition() {
		saveProject();
		SongAnalysis stats = projectStats();
		showResult(Component.literal(String.format(java.util.Locale.ROOT,
			"Saved \"%s\" - %d notes, %d layers, %s at %s",
			project().name(), stats.totalNotes(), project().layers().size(),
			stats.lengthLabel(), FastNoteblocksConfig.delayScaleLabel(delayScaleQuarters()))));
	}

	/**
	 * Fills in build dots from the current selection.
	 *
	 * <p>The dot is the one thing that decides what builds, so this writes dots rather than going
	 * around them -- which is also the only way to change a lot at once. Adding leaves layers
	 * outside the selection alone; setting clears them, so that one is a batch off as well as a
	 * batch on.</p>
	 *
	 * <p>Nothing needs moving afterwards. The sequence is derived from these dots, so it has
	 * already changed by the time this returns.</p>
	 */
	private void setIncludedLayers(boolean add) {
		Set<Integer> chosen = new java.util.LinkedHashSet<>(selectionLayers());
		if (chosen.isEmpty()) {
			showResult(Component.literal("Select some layers first."));
			return;
		}
		ComposerProject updated = project();
		for (int index = 0; index < updated.layers().size(); index++) {
			boolean include = chosen.contains(index)
				|| (add && updated.layers().get(index).buildEnabled());
			if (updated.layers().get(index).buildEnabled() != include) {
				updated = updated.withLayer(index, updated.layers().get(index).withBuildEnabled(include));
			}
		}
		if (updated.equals(project())) {
			showResult(Component.literal("Those layers were already the included ones."));
			return;
		}
		apply(updated);
		rebuildLayerButtons();
		showResult(Component.literal(sequenceSummary()));
	}

	/** What the build sequence now holds, for confirming a dot change did what was expected. */
	private String sequenceSummary() {
		var sequence = config.tracks();
		if (sequence.isEmpty()) {
			return "No layers included - the build sequence is empty.";
		}
		SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(sequence);
		String report = "Sequence: " + sequence.size() + (sequence.size() == 1 ? " layer" : " layers")
			+ ", " + blocks.noteBlocks() + " note blocks, " + blocks.repeaters() + " repeaters";
		SongAnalysis stats = projectStats();
		if (stats.outOfRange() > 0) {
			report += "; " + stats.outOfRange() + " out-of-range notes left out";
		}
		return report;
	}

	/**
	 * Puts the build sequence on the clipboard, one line per included layer.
	 *
	 * <p>Text leaves the composer; it never comes back in over a composition. The projection drops
	 * out-of-range notes, rounds every gap to a whole repeater tick and bakes the tempo away, so
	 * reading it back would silently discard all three. Import text as a new composition instead.</p>
	 */
	private void copySequenceAsText() {
		var sequence = config.tracks();
		if (sequence.isEmpty()) {
			showResult(Component.literal("Nothing is included, so there is no sequence to copy."));
			return;
		}
		String text = sequence.stream()
			.filter(track -> !track.sequence().isBlank())
			.map(track -> ComposerProject.toSequenceLine(
				track.name(), track.instrument(), track.sequence()))
			.collect(java.util.stream.Collectors.joining(System.lineSeparator()));
		if (text.isBlank()) {
			showResult(Component.literal("The included layers have nothing buildable in them."));
			return;
		}
		minecraft.keyboardHandler.setClipboard(text);
		SongAnalysis stats = projectStats();
		String report = "Copied the sequence - " + sequence.size()
			+ (sequence.size() == 1 ? " layer, " : " layers, ") + text.length() + " characters at "
			+ FastNoteblocksConfig.delayScaleLabel(delayScaleQuarters());
		if (stats.outOfRange() > 0) {
			report += ", " + stats.outOfRange() + " out-of-range notes left out";
		}
		showResult(Component.literal(report));
	}

	/**
	 * Saves a copy under a new name and switches to it.
	 *
	 * <p>This is how a version gets held still. The sequence follows whichever composition is open,
	 * so freezing a build means having a second composition rather than a frozen projection.</p>
	 */
	private void saveCompositionAs() {
		minecraft.gui.setScreen(new NamePromptScreen(this, "Save composition as",
			"Save a copy of \"" + project().name() + "\" under a new name",
			project().name() + " copy", "Save copy", name -> {
				String unique = FastNoteblocksConfig.songs().uniqueName(name);
				ComposerProject copy = project().withName(unique);
				String id = FastNoteblocksConfig.songs().newId(unique);
				FastNoteblocksConfig.songs().save(id, copy);
				config.setActiveSongId(id);
				FastNoteblocksConfig.save();
				minecraft.gui.setScreen(new ComposerScreen(parent, config));
			}));
	}

	/** Pastes the build sequence with commands, for when you have op and would rather not place it by hand. */
	private void pasteInWorld() {
		if (CommandPasteSender.isRunning()) {
			CommandPasteSender.cancel(true);
			return;
		}
		if (minecraft.player == null || minecraft.level == null) {
			showResult(Component.literal("Join a world before pasting."));
			return;
		}
		if (config.tracks().stream().allMatch(track -> track.sequence().isBlank())) {
			showResult(Component.literal(
				"The build sequence is empty. Include some layers first."));
			return;
		}
		minecraft.gui.setScreen(new BuildOptionsScreen(this, project().name(), config.tracks(),
				pasteMode(), mode -> {
			SongBuilder.PastePlan plan;
			try {
				plan = SongBuilder.plan(minecraft, config.tracks(), mode);
			} catch (IllegalArgumentException refused) {
				minecraft.gui.setScreen(this);
				showResult(Component.literal(refused.getMessage())
					.withStyle(net.minecraft.ChatFormatting.RED));
				return;
			}
			CommandPasteSender.start(plan.commands());
			minecraft.gui.setScreen(null);
		}));
	}

	private SongBuilder.PasteMode pasteMode() {
		try {
			return SongBuilder.PasteMode.valueOf(config.pasteMode());
		} catch (IllegalArgumentException unknown) {
			return SongBuilder.PasteMode.COMPACT_CUBE;
		}
	}

	private void saveProject() {
		config.setComposerProject(project());
		FastNoteblocksConfig.save();
	}

	private void setDelayScale(int scaleQuarters) {
		// Re-anchor first: the playhead is derived from elapsed real time, so changing the factor
		// without pinning the current tick would make playback jump.
		if (playing) {
			playbackStartTick = Math.max(0L, Math.min(project().endTick(), playbackTick()));
			playbackStartedAt = Util.getMillis();
		}
		// The slider fires on every increment of a drag. Record one step for the gesture and fold
		// the rest into it, or a single drag would push dozens of entries and evict real edits.
		ComposerProject next = project().withSpeedQuarters(scaleQuarters);
		long now = Util.getMillis();
		if (now - lastScaleChangeAt < SCALE_COALESCE_MILLIS) {
			history.replaceCurrent(next);
			afterStateChange();
		} else {
			apply(next);
		}
		lastScaleChangeAt = now;
		if (playing) {
			resetPlaybackSchedule();
		}
	}

	/**
	 * Moves the end marker, folding a whole drag into one history entry.
	 *
	 * <p>{@code withEndTick} refuses to go before the last note, so dragging left simply stops
	 * there instead of silently cutting notes out of the build.</p>
	 */
	private void setEndTick(long tick) {
		ComposerProject next = project().withEndTick(tick);
		if (next.equals(project())) {
			return;
		}
		long now = Util.getMillis();
		if (now - lastEndDragAt < SCALE_COALESCE_MILLIS) {
			history.replaceCurrent(next);
			afterStateChange();
		} else {
			apply(next);
		}
		lastEndDragAt = now;
	}

	/**
	 * The nearest end position whose trailing delay is a whole number of repeater ticks.
	 *
	 * <p>Quantizing cannot fix this the way it fixes a note: the marker's gap is measured from the
	 * last note, wherever that landed, so it has to be snapped relative to that rather than to the
	 * musical grid.</p>
	 */
	private long snapEndToRepeaterGrid() {
		long content = project().contentEndTick();
		double span = SongAnalysis.redstoneTickSpan(project());
		long gap = Math.max(0L, project().endTick() - content);
		return content + Math.round(Math.round(gap / span) * span);
	}

	/** Left edge of the build dot, inset from the panel's right edge. */
	private int buildDotX() {
		return LAYER_PANEL_WIDTH - 24;
	}

	/** The build dot's clickable box, a little larger than the glyph so it is easy to hit. */
	private int buildDotAt(double x, double y) {
		if (x < buildDotX() - 4 || x > buildDotX() + 12) {
			return -1;
		}
		for (int index = 0; index < project().layers().size(); index++) {
			if (!layerRowVisible(index)) {
				continue;
			}
			int top = layerY(index);
			if (y >= top + 2 && y < top + 18) {
				return index;
			}
		}
		return -1;
	}

	private int layerHeaderAt(double x, double y) {
		if (x < 12 || x >= LAYER_PANEL_WIDTH - 10
				|| y < LAYER_LIST_TOP - 2 || y > layerListBottom()) {
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

	/** The collapse arrow occupies the left edge of a layer header, before the name. */
	private boolean isLayerCollapseArrow(double x) {
		return x >= 12 && x < 26;
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

	private int layerY(int layerIndex) {
		int y = LAYER_LIST_TOP - layerScroll;
		for (int index = 0; index < layerIndex; index++) {
			y += layerRowHeight(index);
		}
		return y;
	}

	private int layerRowHeight(int layerIndex) {
		return collapsedLayers.contains(layerIndex) ? LAYER_COLLAPSED_ROW_HEIGHT : LAYER_ROW_HEIGHT;
	}

	/** Bottom of the scrollable layer list, leaving room for the pinned "+ Layer" row and footer. */
	private int layerListBottom() {
		return height - 44;
	}

	private int layerContentHeight() {
		int total = 0;
		for (int index = 0; index < project().layers().size(); index++) {
			total += layerRowHeight(index);
		}
		return total;
	}

	private int maxLayerScroll() {
		return Math.max(0, layerContentHeight() - (layerListBottom() - LAYER_LIST_TOP));
	}

	private void scrollLayers(int deltaPixels) {
		int clamped = Math.max(0, Math.min(maxLayerScroll(), layerScroll + deltaPixels));
		if (clamped != layerScroll) {
			layerScroll = clamped;
			cancelLayerRename();
			instrumentMenuLayer = -1;
			rebuildLayerButtons();
			rebuildMoveLayerButtons();
		}
	}

	private void toggleLayerCollapsed(int layerIndex) {
		if (!collapsedLayers.remove(layerIndex)) {
			collapsedLayers.add(layerIndex);
		}
		cancelLayerRename();
		instrumentMenuLayer = -1;
		layerScroll = Math.min(layerScroll, maxLayerScroll());
		rebuildLayerButtons();
		rebuildMoveLayerButtons();
	}

	/** True when a layer row is fully inside the scrollable viewport. */
	private boolean layerRowVisible(int layerIndex) {
		int top = layerY(layerIndex);
		return top >= LAYER_LIST_TOP - 2 && top + layerRowHeight(layerIndex) <= layerListBottom() + 2;
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
			playButton.active = anythingAudible();
			playButton.setMessage(playLabel());
		}
	}

	private Component playLabel() {
		return Component.literal(playing ? "Stop" : "Play");
	}

	private ComposerProject project() {
		return history.current();
	}

	private int delayScaleQuarters() {
		return project().speedQuarters();
	}

	private ComposerProject displayProject() {
		return dragPreview == null ? project() : dragPreview;
	}

	private Layer activeLayer() {
		return project().layers().get(project().activeLayerIndex());
	}

	private NoteHit noteAt(double mouseX, double mouseY) {
		List<Integer> order = noteDrawOrder(project());
		for (int position = order.size() - 1; position >= 0; position--) {
			int layerIndex = order.get(position);
			Layer layer = project().layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			NoteEvent note = noteAt(layer, mouseX, mouseY);
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
		for (int layerIndex : selectionLayers()) {
			Layer layer = project().layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (noteRect(note).intersects(left, top, right, bottom)) {
					selectedNotes.add(note.id());
				}
			}
		}
	}

	/**
	 * Notes draw as fixed-width triggers rather than bars spanning their length.
	 *
	 * <p>Nothing downstream reads a note's duration: preview schedules one sound at its start and
	 * the build places one note block there. A note block cannot sustain at all. Drawing a long bar
	 * showed a note holding for a length that never sounds, which read as sustain that does not
	 * exist -- most misleadingly after a repeat merge, where the absorbed span became a bar.</p>
	 */
	private NoteRect noteRect(NoteEvent note) {
		int left = tickX(note.startTick());
		int top = noteY(note.midiNote()) + 1;
		return new NoteRect(left, top, left + NOTE_TRIGGER_WIDTH, top + rowHeight - 2);
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
		return rollY + (topMidiNote - midiNote) * rowHeight;
	}

	private int mouseMidi(double y) {
		return Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE,
			topMidiNote - (int)Math.floor((y - rollY) / rowHeight)));
	}

	/**
	 * Zoom-out limit, wide enough to always fit the whole song plus margin.
	 *
	 * <p>A fixed cap only worked while projects were low-resolution; a 480-PPQ import is two orders
	 * of magnitude longer in ticks, and an 80 tick/pixel ceiling would strand most of it off screen.
	 * Zooming this far out is only affordable because the grid step widens with the zoom.</p>
	 */
	private double maxTicksPerPixel() {
		long span = Math.max(1L, project().endTick());
		return Math.max(80.0, span * 1.2 / Math.max(1, rollWidth));
	}

	/**
	 * Spacing that placement and dragging snap to.
	 *
	 * <p>The musical subdivisions need not line up with the repeater grid: at 480 PPQ and this
	 * project's tempo a quarter note is 1.25 repeater ticks, so notes on a 1/16 grid only coincide
	 * with a repeater tick once every four quarter notes. {@code SNAP_REPEATER} snaps to the
	 * repeater grid itself so every placement is directly buildable.</p>
	 */
	private long gridTicks() {
		if (snapSubdivision == SNAP_REPEATER) {
			return Math.max(1L, Math.round(SongAnalysis.redstoneTickSpan(project())));
		}
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

	/** The end marker's grab zone, a few pixels either side of it in the ruler. */
	private boolean overEndMarker(double x, double y) {
		return insideRuler(x, y) && Math.abs(x - tickX(project().endTick())) <= 4.0;
	}

	private boolean insideRuler(double x, double y) {
		return x >= rollX && x < rollX + rollWidth
			&& y >= rollY - TIMELINE_RULER_HEIGHT && y < rollY;
	}

	private boolean controlDown() {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private boolean shiftDown() {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private void centerMinecraftRange() {
		int visibleRows = Math.max(8, rollHeight / rowHeight);
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
			setMessage(Component.literal("Speed " + FastNoteblocksConfig.delayScaleLabel(scaleQuarters)));
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

	private record PlaybackEvent(long tick, PreviewInstrument instrument, int note) {
		private boolean sameSound(PlaybackEvent other) {
			return tick == other.tick && note == other.note && instrument.equals(other.instrument);
		}
	}

	private record NoteHit(int layerIndex, NoteEvent note) {
	}

	private enum ToolbarMenu {
		NONE,
		BUILD,
		FILE,
		EDIT,
		SELECT,
		IMPORT
	}

	/** MIDI and NBS import settings, surfaced here so the Composer does not depend on Mod Menu. */
	private enum ImportSetting {
		QUANTIZE_GRID("Quantize"),
		TEMPO_FIT("Tempo"),
		RANGE_FIT("Range fit"),
		REPEAT_MERGE("Merge repeats"),
		GRID_OUTLIERS("Grid outliers"),
		VELOCITY_CUTOFF("Velocity cutoff"),
		IGNORE_PERCUSSION("Percussion"),
		MAX_TRACKS("Max tracks"),
		DEFAULT_INSTRUMENT("Instrument");

		private final String label;

		ImportSetting(String label) {
			this.label = label;
		}
	}

	private enum ToolbarAction {
		IMPORT("Import MIDI / NBS..."),
		OPEN_SONGS("Open composition..."),
		COPY_AS_TEXT("Copy sequence as text"),
		SAVE_COMPOSITION("Save composition"),
		SAVE_COMPOSITION_AS("Save composition as..."),
		BACK_TO_SEQUENCES("Back"),
		CLOSE_TO_GAME("Close to game"),
		UNDO("Undo"),
		REDO("Redo"),
		CONVERT("Convert for Minecraft"),
		MERGE_REPEATS("Merge repeats", true),
		QUANTIZE("Quantize to grid", true),
		FIT_ALL_RANGE("Fit into range", true),
		SNAP_TEMPO("Snap tempo (whole song)"),
		INCLUDE_SELECTED("Include selected layers in sequence"),
		SET_INCLUDED_TO_SELECTION("Include only selected layers in sequence"),
		PASTE_IN_WORLD("Paste current sequence in world (requires op)..."),
		BUILD_CANCEL("Cancel paste"),
		SNAP_END("Snap end to grid"),
		TRIM_END("Trim end to last note"),
		SELECT_OFF_GRID("Off grid"),
		SELECT_TOO_FREQUENT("Too frequent"),
		SELECT_OUT_OF_RANGE("Out of range"),
		SELECT_ALL_NOTES("Everything"),
		SELECT_NONE("Nothing");

		private static final ToolbarAction[] FILE_ACTIONS = {
			SAVE_COMPOSITION, SAVE_COMPOSITION_AS, OPEN_SONGS, IMPORT, COPY_AS_TEXT,
			BACK_TO_SEQUENCES, CLOSE_TO_GAME
		};
		private static final ToolbarAction[] EDIT_ACTIONS = {
			UNDO, REDO, CONVERT, MERGE_REPEATS, QUANTIZE, FIT_ALL_RANGE, SNAP_TEMPO,
			SNAP_END, TRIM_END
		};
		private static final ToolbarAction[] BUILD_ACTIONS = {
			INCLUDE_SELECTED, SET_INCLUDED_TO_SELECTION, PASTE_IN_WORLD, BUILD_CANCEL
		};
		private static final ToolbarAction[] SELECT_ACTIONS = {
			SELECT_OFF_GRID, SELECT_TOO_FREQUENT, SELECT_OUT_OF_RANGE, SELECT_ALL_NOTES, SELECT_NONE
		};
		private final String label;
		/** Whether the action can be limited to the selected notes. Tempo is a property of the
		 * whole composition, so it can never be. */
		private final boolean scopeable;

		ToolbarAction(String label) {
			this(label, false);
		}

		ToolbarAction(String label, boolean scopeable) {
			this.label = label;
			this.scopeable = scopeable;
		}
	}

	private enum LayerAction {
		MERGE_SELECTED("Merge selected"),
		INCLUDE_SELECTED("Include selected layers in sequence"),
		SET_INCLUDED_TO_SELECTION("Include only selected layers in sequence"),
		SELECT_ALL("Select all layers"),
		COLLAPSE_OTHERS("Collapse others");

		private final String label;

		LayerAction(String label) {
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
