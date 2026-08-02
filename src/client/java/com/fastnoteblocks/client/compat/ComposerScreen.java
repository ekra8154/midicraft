package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ChordThinner;
import com.fastnoteblocks.client.composer.ComposerHistory;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.fastnoteblocks.client.composer.ComposerProject.ClipboardNote;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.MinecraftConversion;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.ComposerProject.PasteResult;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.slf4j.Logger;

public final class ComposerScreen extends Screen {
	private static final int TOOLBAR_HEIGHT = 22;
	/**
	 * The menus, as a menu bar rather than a row of buttons.
	 *
	 * <p>Five framed buttons twenty pixels tall and fifty-eight apart is a lot of furniture for
	 * five words, and a framed button that opens a nested menu is an odd object -- the frame says
	 * "press me", the arrow says "there is more inside". Drawn titles with a hover highlight say
	 * the second thing on their own, and give back twelve pixels of height to the roll.</p>
	 */
	private static final ToolbarMenu[] MENU_BAR = {
		ToolbarMenu.FILE, ToolbarMenu.EDIT, ToolbarMenu.IMPORT, ToolbarMenu.SELECT, ToolbarMenu.BUILD
	};
	private static final int MENU_BAR_LEFT = 6;
	private static final int MENU_BAR_TOP = 3;
	private static final int MENU_BAR_ROW_HEIGHT = 16;
	private static final int MENU_TITLE_PADDING = 8;
	/** Where a menu's panel hangs from, just under the bar. */
	private static final int MENU_PANEL_TOP = TOOLBAR_HEIGHT + 2;
	private static final int CONTROL_HEIGHT = 14;
	private static final int CONTROL_TOP = MENU_BAR_TOP + 1;
	private static final int CONTROL_GAP = 4;
	private static final int CONTROL_PADDING = 10;

	private static final int PIANO_WIDTH = 48;
	/** Tall enough for a bar number with the clock time under it. */
	private static final int TIMELINE_RULER_HEIGHT = 24;
	/**
	 * One height for every layer row.
	 *
	 * <p>Rows used to open into a 42-pixel panel of buttons, which is how a converted song ended up
	 * needing a collapse arrow on every line to stay navigable. Everything a row carries now fits on
	 * the row, so there is nothing to open and nothing to collapse.</p>
	 */
	private static final int LAYER_ROW_HEIGHT = 18;
	/** Layer names are drawn at this fraction of the font's one size. */
	private static final float LAYER_TEXT_SCALE = 0.75f;
	private static final int LAYER_STATE_X = 15;
	/** Side of the square button carrying a layer's state letter. */
	private static final int LAYER_CHIP = 12;
	private static final int LAYER_INSTRUMENT_X = 26;
	private static final int LAYER_NAME_X = 45;
	/**
	 * The grab strip on the panel's edge, and the cursor that says it can be grabbed.
	 *
	 * <p>The arrows are the whole affordance -- nothing about a flat edge suggests it is draggable,
	 * and a strip three pixels wide is not going to be found by accident. GLFW's own resize cursor
	 * rather than something drawn, so it matches every other window edge the player has ever
	 * dragged.</p>
	 */
	private void extractSplitter(GuiGraphicsExtractor graphics) {
		int edge = layerPanelWidth();
		boolean hovered = overSplitter(lastMouseX, lastMouseY);
		boolean lit = hovered || draggingSplitter;
		graphics.fill(edge - 1, TOOLBAR_HEIGHT, edge, height, lit ? 0xFF8FD3FF : 0xFF2C333D);
		if (lit) {
			// Three notches, the usual shorthand for a handle you can take hold of.
			int middle = TOOLBAR_HEIGHT + (height - TOOLBAR_HEIGHT) / 2;
			for (int notch = -1; notch <= 1; notch++) {
				graphics.fill(edge - 3, middle + notch * 5, edge + 2, middle + notch * 5 + 1,
					0xFFCDE9FF);
			}
		}
		setResizeCursor(lit);
	}

	private boolean overSplitter(double x, double y) {
		int edge = layerPanelWidth();
		return y >= TOOLBAR_HEIGHT && x >= edge - SPLITTER_GRAB && x <= edge + SPLITTER_GRAB;
	}

	/**
	 * Swaps in the horizontal-resize cursor while the split is in reach.
	 *
	 * <p>Only on the change, and put back on the way out and again when the screen closes: a cursor
	 * is process-wide state, so leaving it set would follow the player back into the world.</p>
	 */
	private void setResizeCursor(boolean wanted) {
		if (wanted == resizeCursorShown || minecraft == null || minecraft.getWindow() == null) {
			return;
		}
		resizeCursorShown = wanted;
		long window = minecraft.getWindow().handle();
		if (!wanted) {
			GLFW.glfwSetCursor(window, 0L);
			return;
		}
		if (RESIZE_CURSOR == 0L) {
			RESIZE_CURSOR = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
		}
		if (RESIZE_CURSOR != 0L) {
			GLFW.glfwSetCursor(window, RESIZE_CURSOR);
		}
	}

	/** How wide the layer panel is drawing right now, folded or not. */
	private int layerPanelWidth() {
		return config.layerPanelCollapsed()
			? COLLAPSED_LAYER_PANEL_WIDTH
			: config.layerPanelWidth();
	}

	/** Widest a layer's name may draw, which is whatever the panel leaves after the row number. */
	private int layerNameRight() {
		return layerRowLayout().nameRight();
	}

	/**
	 * What fits on a layer row at the panel's current width.
	 *
	 * <p>Parts drop off as it narrows -- the name first, then the build dot, then the state chip --
	 * until only the instrument and the row number are left, which is also what a folded panel
	 * shows. One description of the row shared by the drawing and by every hit test, because a
	 * panel where the chip is painted in one place and clicked in another is worse than one that
	 * never shrank.</p>
	 */
	private LayerRowLayout layerRowLayout() {
		int panel = layerPanelWidth();
		boolean chip = panel >= ROW_CHIP_AT;
		boolean dot = panel >= ROW_DOT_AT;
		boolean name = panel >= ROW_NAME_AT;
		int inset = chip ? 8 : 2;
		return new LayerRowLayout(
			inset,
			chip,
			dot,
			name,
			chip ? LAYER_INSTRUMENT_X : inset + 2,
			LAYER_NAME_X,
			dot ? panel - 40 : panel - 16,
			panel - 34,
			panel - 5);
	}

	/**
	 * A folded panel is the narrow end of the same layout, not a blank strip.
	 *
	 * <p>Which instrument a layer is and where it sits in the order are the two things you still
	 * want while it is out of the way -- they are how you find the layer you meant. Everything else
	 * is what folding is for getting rid of.</p>
	 */
	private static final int COLLAPSED_LAYER_PANEL_WIDTH = 30;
	/** Grab zone either side of the split, and how far left you must drag to fold it away. */
	private static final int SPLITTER_GRAB = 3;
	private static final int SPLITTER_COLLAPSE_AT = 34;
	/** Widths at which a row stops having room for each of its parts, narrowest last. */
	private static final int ROW_NAME_AT = 116;
	private static final int ROW_DOT_AT = 96;
	private static final int ROW_CHIP_AT = 76;
	private static final int LAYER_LIST_TOP = 48;
	private static final int MIN_ROW_HEIGHT = 4;
	private static final int MAX_ROW_HEIGHT = 26;
	private static final int INSTRUMENT_COLUMNS = 6;
	private static final int INSTRUMENT_CELL = 28;
	private static final int CONTEXT_MENU_WIDTH = 104;
	private static final int CONTEXT_MENU_ROW_HEIGHT = 16;
	private static final int LAYER_MENU_WIDTH = 120;
	private static final int TOOLBAR_MENU_WIDTH = 126;
	private static final int IMPORT_MENU_WIDTH = 196;
	private static final int TOOLBAR_MENU_ROW_HEIGHT = 18;
	private static final int VELOCITY_CUTOFF_STEP = 8;
	/**
	 * Starting height of a piano-roll row, and below it the starting horizontal zoom.
	 *
	 * <p>Both deliberately wide. A composition arrives from an import as thousands of notes across
	 * four octaves, and opening on a view that holds a bar and a half of it means the first thing
	 * anyone does is zoom out. Ctrl and the wheel go back in.</p>
	 */
	private static final int ROW_HEIGHT = 8;
	private static final double DEFAULT_TICKS_PER_PIXEL = 24.0;
		private static final int CHORD_WARNING_THRESHOLD = 24;
	private static final int MAX_PREVIEW_SOUNDS_PER_FRAME = 64;
	private static final int MIN_GRID_PIXEL_SPACING = 4;
	private static final int MIN_LABEL_PIXEL_SPACING = 32;
	/** Room a ruler label needs: a bar number with a clock time under it, and air after them. */
	private static final int RULER_LABEL_SPACING = 58;
	/**
	 * Bar counts the ruler is willing to count in.
	 *
	 * <p>It labels every nth bar for the smallest n here that leaves the labels far enough apart to
	 * read, so zooming out thins the ruler instead of packing it. Round numbers rather than powers
	 * of two: the ruler is for finding your way back to somewhere, and "bar 51" is easier to hold
	 * on to than "bar 65".</p>
	 */
	private static final int[] RULER_BAR_STEPS = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000};
	private static final double BOX_SCROLL_FULL_SPEED_PIXELS = 140.0;
	private static final double BOX_SCROLL_MAX_PIXELS = 22.0;
	private static final double BOX_SCROLL_MAX_ROWS = 2.0;
	/** How much faster holding at an edge eventually gets, and how long it takes to get there. */
	private static final double BOX_SCROLL_HELD_BOOST = 4.0;
	private static final double BOX_SCROLL_BOOST_MILLIS = 900.0;
	private static final long TOOLTIP_DWELL_MILLIS = 260L;
	private static final long SCALE_COALESCE_MILLIS = 400L;
	private static final long TOAST_MILLIS = 4500L;
	/**
	 * How much finer than the song's tightest gap its shared grid may be before Snap tempo declines.
	 *
	 * <p>They are equal in a song whose notes sit on a grid, and the tempo then costs nothing beyond
	 * what the music demands. Far apart means a few strays have dragged the grid below anything the
	 * song plays, and no tempo fixes that cheaply -- gaps of five and seven units force a span of
	 * one whatever the tempo does, so the answer is to move the notes.</p>
	 */
	private static final long MAX_GRID_STRETCH = 4L;
	private static final int NOTE_TRIGGER_WIDTH = 7;
	private static final int SNAP_REPEATER = -1;
	private static final long PREVIEW_BACKLOG_TOLERANCE_MICROS = 100_000L;
	private static final int MIN_MIDI_NOTE = 0;
	private static final int MAX_MIDI_NOTE = 127;
	private static final int[] LAYER_COLORS = {
		0xFF35D7E5, 0xFFFFB347, 0xFF9BE564, 0xFFD19BFF, 0xFFFF6B9A,
		0xFF7CA7FF, 0xFFFFE66D, 0xFF8CE0C3, 0xFFFF8C5A, 0xFFC3F584
	};

	private static final Logger PERF = LogUtils.getLogger();
	/**
	 * The phases one composer frame is split into, in the order they are drawn.
	 *
	 * <p>Named rather than nested so a phase costs one array slot and one subtraction. The point of
	 * the split is that "the composer is slow" is not actionable and "notes: 31ms of a 38ms frame"
	 * is -- every optimisation in this screen was chosen by reading these numbers, not by guessing
	 * which loop looked worst.</p>
	 */
	private static final String[] PHASES = {
		"panels", "ruler", "keys", "grid", "notes", "playhead", "widgets", "status", "menus"
	};
	private static final int PHASE_PANELS = 0;
	private static final int PHASE_RULER = 1;
	private static final int PHASE_KEYS = 2;
	private static final int PHASE_GRID = 3;
	private static final int PHASE_NOTES = 4;
	private static final int PHASE_PLAYHEAD = 5;
	private static final int PHASE_WIDGETS = 6;
	private static final int PHASE_STATUS = 7;
	private static final int PHASE_MENUS = 8;
	/** How long the profiler gathers before it reports, and clears, a window of frames. */
	private static final long PROFILE_WINDOW_MILLIS = 1000L;

	private final Screen parent;
	private final Runnable onReturn;
	private final FastNoteblocksConfig config;
	private final ComposerHistory history;
	private final Set<Long> selectedNotes = new LinkedHashSet<>();
	private final List<Button> moveLayerButtons = new ArrayList<>();
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
	private double ticksPerPixel = DEFAULT_TICKS_PER_PIXEL;
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
	/** The submenu standing open off a row of the current menu, and where it was drawn. */
	private ToolbarSubmenu openSubmenu;
	private int submenuLeft;
	private int submenuTop;
	private int submenuRight;
	private int submenuBottom;
	/**
	 * The parent row the open submenu hangs off.
	 *
	 * <p>Kept apart from {@link #submenuTop}, which is where the panel actually landed after being
	 * nudged to fit on screen. Feeding that back in as the anchor made the nudge compound: the
	 * panel climbed two pixels every frame the cursor was inside it, walked out from under the
	 * cursor, and vanished the moment it stopped being hovered.</p>
	 */
	private int submenuAnchorY;
	/** Left edge of the bar's controls, which is where the composition name has to stop. */
	private int toolbarControlsLeft = 320;
	private long lastScaleChangeAt;
	private Component toast;
	private long toastShownAt;
	private long hoveredNoteId = -1L;
	private long hoveredSince;
	private ComposerProject cachedStatsProject;
	private SongAnalysis cachedStats;
	private boolean cachedStatsDedupe;
	private List<FastNoteblocksConfig.SequenceTrack> cachedBlockTracks;
	private SongBuilder.BlockCounts cachedBlockCounts;
	private SongAnalysis cachedOverloadedStats;
	private long[] cachedOverloadedTicks = new long[0];
	/**
	 * The composition as it stands on disk, which is what "unsaved" is measured against.
	 *
	 * <p>Kept as a whole snapshot rather than a dirty flag so that undoing back to the saved state
	 * counts as saved again, and so a change that cancels itself out does not leave the composer
	 * insisting there is something to write.</p>
	 */
	private ComposerProject savedProject;
	private ComposerProject unsavedCacheProject;
	private ComposerProject unsavedCacheBaseline;
	private boolean unsavedCacheResult;
	private ComposerProject cachedColorProject;
	private int[] cachedLayerColors;
	/**
	 * A press that is setting the same thing on every row it is dragged across.
	 *
	 * <p>Whatever the first click produced is what the rest of the drag paints, rather than each row
	 * being toggled or cycled in turn. Dragging over a row that already agreed would otherwise flip
	 * it the wrong way, which makes the gesture useless for the thing it is for -- taking a run of
	 * layers out of the build in one stroke.</p>
	 */
	private LayerPaint painting = LayerPaint.NONE;
	private boolean paintBuildEnabled;
	private LayerState paintState = LayerState.ACTIVE;
	private final Set<Integer> paintedRows = new LinkedHashSet<>();
	/**
	 * Frame timings, gathered only while F9 has the profiler switched on.
	 *
	 * <p>Off by default and free when off: the phase marks return without reading the clock, so a
	 * player who never presses F9 pays nothing for any of this.</p>
	 */
	/** One frame's note quads, minus the ones a later note covers. Reused, never reallocated. */
	private final NoteCellGrid cells = new NoteCellGrid();
	/** The grid's way through to the frame's graphics, held so drawing does not allocate one. */
	private final GraphicsQuads quads = new GraphicsQuads();
	private boolean profiling;
	private final long[] phaseNanos = new long[PHASES.length];
	private long profileFrameNanos;
	private int profileFrames;
	private long profileWindowSince;
	private int noteQuads;
	private int notesConsidered;
	private int notesDrawn;
	/**
	 * The notes phase split in two: working out where the notes go, and handing quads to the GUI.
	 *
	 * <p>Kept apart because they are fixed by different things — the first scales with how many
	 * notes are on screen, the second with how many quads survive collapsing — and at full zoom-out
	 * in both directions those two numbers differ by a factor of eight. Guessing which one the time
	 * belonged to would have sent the next change to the wrong place.</p>
	 */
	private long notesLayoutNanos;
	private long notesDrawNanos;
	private String profileSummary = "";
	/** The layer a press landed on, and whether it has moved far enough to be a reorder. */
	private int layerDragIndex = -1;
	/** Dragging the split between the layer panel and the roll, and whether the cursor says so. */
	private boolean draggingSplitter;
	private boolean resizeCursorShown;
	private double layerDragStartY;
	private double layerDragY;
	private boolean layerDragActive;

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
		this.savedProject = baseline(config, history.current());
	}

	/**
	 * What "unsaved" is measured against.
	 *
	 * <p>Three cases, and the difference between the last two is the whole point. A song with a
	 * file is measured against the file. A document with no file that has nothing in it is measured
	 * against itself, so a blank composition is not something you are nagged to save until you put
	 * something in it. A document with no file that <em>does</em> have something in it -- an import,
	 * a scan -- is measured against nothing at all, so all of it counts as unsaved and closing asks
	 * before dropping it.</p>
	 */
	private static ComposerProject baseline(FastNoteblocksConfig config, ComposerProject current) {
		ComposerProject onDisk = config.savedComposerProject();
		if (onDisk != null) {
			return onDisk;
		}
		boolean empty = current.layers().stream().allMatch(layer -> layer.notes().isEmpty());
		return empty ? current : null;
	}

	@Override
	protected void init() {
		clearWidgets();
		rollY = TOOLBAR_HEIGHT + 14 + TIMELINE_RULER_HEIGHT;
		rollHeight = Math.max(40, height - rollY - 24);
		centerMinecraftRange();
		// The menus are drawn, not built: see extractMenuBar. Only the three controls are widgets,
		// because they carry state you read off them rather than opening anything.
		//
		// Pinned to the right edge and sized to their own widest label. The menus grow rightward as
		// they are added to and the controls do not move; keeping the two apart means neither can
		// push the other about. Each is measured against every caption it can ever show, so Snap
		// does not jump a pixel when it reaches "repeater" or Speed when it reaches "0.25x".
		int playWidth = widestLabel(CONTROL_PADDING, "Play", "Stop");
		int snapWidth = widestLabel(CONTROL_PADDING, "Snap 1/4", "Snap 1/8", "Snap 1/16",
			"Snap 1/32", "Snap repeater", "Snap off");
		int speedWidth = widestLabel(CONTROL_PADDING + 8, "Speed 0.25x", "Speed 2.00x", "Speed 8.00x");
		int speedX = width - 6 - speedWidth;
		int snapX = speedX - CONTROL_GAP - snapWidth;
		int playX = snapX - CONTROL_GAP - playWidth;
		toolbarControlsLeft = playX;
		playButton = addRenderableWidget(Button.builder(playLabel(), button -> togglePlayback())
			.bounds(playX, CONTROL_TOP, playWidth, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal("Preview all unmuted layers")))
			.build());
		snapButton = addRenderableWidget(Button.builder(snapLabel(), button -> cycleSnap())
			.bounds(snapX, CONTROL_TOP, snapWidth, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal("Grid used when adding or dragging notes")))
			.build());
		delayScaleSlider = addRenderableWidget(new DelayScaleSlider(
			speedX, CONTROL_TOP, speedWidth, CONTROL_HEIGHT, project().speedQuarters(),
			this::setDelayScale
		));
		delayScaleSlider.setTooltip(Tooltip.create(Component.literal(
			"Playback speed, 0.25x to 8.00x. Higher is faster. Saving to the sequence bakes this "
				+ "into the delays, so the build runs at the speed you hear here."
		)));
		if (!layerViewInitialised) {
			layerViewInitialised = true;
			resetLayerView();
		}
		layersChanged();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	/**
	 * Keeps view state that is held by layer index honest after layers move or disappear.
	 *
	 * <p>Solo is the one that matters: it is a set of positions rather than a flag on a layer, so a
	 * reorder or a merge would leave it silencing something at random.</p>
	 */
	private void layersChanged() {
		if (soloedLayers.removeIf(index -> index >= project().layers().size()) && playing) {
			resetPlaybackSchedule();
		}
		layerScroll = Math.min(layerScroll, maxLayerScroll());
	}

	private void rebuildMoveLayerButtons() {
		for (Button button : moveLayerButtons) {
			removeWidget(button);
		}
		moveLayerButtons.clear();
		if (config.layerPanelCollapsed()) {
			// Nothing to add a layer to while the panel is folded, and a button sixteen pixels wide
			// would only be something to click by accident.
			addLayerButton = null;
			return;
		}
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
				layersChanged();
				rebuildMoveLayerButtons();
			}
		}).bounds(8, y, layerPanelWidth() - 16, 18)
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

	/** Back to one layer selected and the top of the list, after something replaced the song. */
	private void resetLayerView() {
		selectedLayers.clear();
		selectedLayers.add(project().activeLayerIndex());
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
		layersChanged();
	}

	/**
	 * What one layer is doing, as a single dial from loudest to most out of the way.
	 *
	 * <p>Muting, hiding and soloing used to be three switches, which took three buttons on a row
	 * that had to open to hold them. They are really one question -- how much do I want this layer
	 * in the way -- and hiding is only muting that also leaves the piano roll.</p>
	 */
	private enum LayerState {
		ACTIVE("A", 0xFFE8EAEE, 0xFF3A4048, "Played and drawn."),
		MUTED("M", 0xFFFFB05A, 0xFF3E332A, "Silent, still drawn."),
		SOLO("S", 0xFFFFD65A, 0xFF453D22,
			"Heard alone. Listening only; it does not change what builds."),
		HIDDEN("H", 0xFF787D85, 0xFF24272B, "Silent and out of the piano roll.");

		private final String letter;
		private final int color;
		private final int chip;
		private final String description;

		LayerState(String letter, int color, int chip, String description) {
			this.letter = letter;
			this.color = color;
			this.chip = chip;
			this.description = description;
		}
	}

	private LayerState layerState(int index) {
		if (soloedLayers.contains(index)) {
			return LayerState.SOLO;
		}
		Layer layer = project().layers().get(index);
		if (!layer.visible()) {
			return LayerState.HIDDEN;
		}
		return layer.muted() ? LayerState.MUTED : LayerState.ACTIVE;
	}

	/**
	 * Steps a layer's state along the dial.
	 *
	 * <p>Reversible, the way the import settings cycle: left-click walks A to M to S to H and right
	 * -click walks back, which puts muting one click forward from where a layer usually sits and
	 * hiding one click back.</p>
	 */
	private LayerState cycleLayerState(int clickedIndex, int direction) {
		LayerState[] dial = LayerState.values();
		LayerState next = dial[Math.floorMod(layerState(clickedIndex).ordinal() + direction, dial.length)];
		setLayerState(layersToEdit(clickedIndex), next);
		return next;
	}

	private void setLayerState(List<Integer> indices, LayerState state) {
		setLayerState(indices, state, false);
	}

	/**
	 * @param coalesce fold this into the step already recorded, rather than adding one of its own.
	 *     A drag across thirty layers is one thing the user did, and should be one press of undo.
	 */
	private void setLayerState(List<Integer> indices, LayerState state, boolean coalesce) {
		ComposerProject updated = project();
		for (int index : indices) {
			if (index < 0 || index >= updated.layers().size()) {
				continue;
			}
			if (state == LayerState.SOLO) {
				soloedLayers.add(index);
			} else {
				soloedLayers.remove(index);
			}
			Layer layer = updated.layers().get(index);
			updated = updated.withLayer(index, switch (state) {
				case SOLO, ACTIVE -> layer.withMuted(false).withVisible(true);
				case MUTED -> layer.withMuted(true).withVisible(true);
				case HIDDEN -> layer.withMuted(true).withVisible(false);
			});
		}
		applyMaybeCoalesced(updated, coalesce);
		layersChanged();
		if (playing) {
			resetPlaybackSchedule();
		}
		updateButtonStates();
	}

	private void setLayerBuildEnabled(List<Integer> indices, boolean enabled) {
		setLayerBuildEnabled(indices, enabled, false);
	}

	private void setLayerBuildEnabled(List<Integer> indices, boolean enabled, boolean coalesce) {
		ComposerProject updated = project();
		for (int index : indices) {
			if (index >= 0 && index < updated.layers().size()) {
				updated = updated.withLayer(index, updated.layers().get(index).withBuildEnabled(enabled));
			}
		}
		applyMaybeCoalesced(updated, coalesce);
		layersChanged();
	}

	private void applyMaybeCoalesced(ComposerProject updated, boolean coalesce) {
		if (!coalesce) {
			apply(updated);
			return;
		}
		anchorPlayhead();
		history.replaceCurrent(updated);
		afterStateChange();
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
		layersChanged();
	}

	/**
	 * Folds the selected layers into the lowest-numbered one, and says what that cost.
	 *
	 * <p>Reported rather than done silently because the shortcut has no menu row to have read
	 * first. Merging keeps one layer's instrument and gives it to every note that arrives, so a
	 * merge across two instruments is not only a tidying-up -- and from the keyboard the only sign
	 * of that is the sound changing.</p>
	 */
	/** Re-lays whatever the panel's width decides: the roll's left edge and the panel's buttons. */
	private void resizeLayerPanel() {
		rollX = layerPanelWidth() + PIANO_WIDTH;
		rollWidth = Math.max(40, width - rollX - 8);
		layerScroll = Math.min(layerScroll, maxLayerScroll());
		rebuildMoveLayerButtons();
	}

	private void mergeSelectedLayers() {
		List<Integer> merging = selectedLayers.stream()
			.filter(index -> index >= 0 && index < project().layers().size())
			.sorted()
			.toList();
		if (merging.size() < 2) {
			showResult(Component.literal("Merge needs two or more layers selected - click one in "
				+ "the panel and shift-click another."));
			return;
		}
		Layer into = project().layers().get(merging.getFirst());
		int notes = merging.stream().mapToInt(index -> project().layers().get(index).notes().size()).sum();
		long instruments = merging.stream()
			.map(index -> project().layers().get(index).instrument())
			.distinct()
			.count();
		apply(project().mergeLayers(Set.copyOf(selectedLayers)));
		resetLayerView();
		layersChanged();
		rebuildMoveLayerButtons();
		String summary = "Merged " + merging.size() + " layers into \"" + into.name() + "\" - "
			+ notes + " notes.";
		if (instruments > 1) {
			summary += " They all play " + PreviewInstrument.byId(into.instrument()).name()
				+ " now; Ctrl+Z puts them back.";
		}
		showResult(Component.literal(summary));
	}

	private void updateLayer(int index, Layer layer) {
		apply(project().withLayer(index, layer));
		layersChanged();
	}

	private void moveSelectionToLayer(int target) {
		if (target < 0 || target >= project().layers().size()) {
			return;
		}
		apply(project().moveNotesToLayer(selectedNotes, target));
		layersChanged();
		rebuildMoveLayerButtons();
	}

	private void moveLayer(int layerIndex, int direction) {
		if (layerIndex < 0 || layerIndex >= project().layers().size() || direction == 0) {
			return;
		}
		instrumentMenuLayer = -1;
		cancelLayerRename();
		apply(project().moveLayer(layerIndex, direction));
		layersChanged();
	}

	/**
	 * Drops a dragged layer into a gap.
	 *
	 * <p>{@code insertion} counts gaps, not rows, so dropping below where the layer started lands
	 * one row short of it once the layer itself is out of the list.</p>
	 */
	private void dropLayer(int from, int insertion) {
		moveLayer(from, (insertion > from ? insertion - 1 : insertion) - from);
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
		// genuinely runs that fast rather than one that still depends on a slider.
		//
		// The bake has to reset the slider as well as change the tempo. It used to only change the
		// tempo, leaving the old speed on the project that conversion then read -- so every
		// speed-dependent step inside ran at the speed squared while the result was stamped back to
		// 1.00x. At 1.00x the two agree and nothing looked wrong, which is why converting a fresh
		// import worked and converting again after nudging the speed left the song off grid.
		ComposerProject source = project().withBakedSpeed();
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
			resetLayerView();
			centerMinecraftRange();
			layersChanged();
			rebuildMoveLayerButtons();
			String report = "Converted at " + conversionGridLabel(gridTicks)
				+ ": " + conversion.shiftedNotes() + " pitch-shifted"
				+ (conversion.addedLayers() > 0 ? ", +" + conversion.addedLayers() + " layers" : "")
				+ (conversion.mergedRepeats() > 0
					? ", " + conversion.mergedRepeats() + " repeats merged" : "")
				+ (conversion.duplicateLayers() > 0
					? ", " + conversion.duplicateLayers() + " duplicate layers dropped ("
						+ conversion.duplicateLayerNotes() + " notes)" : "");
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
		withUnsavedChangesChecked(() ->
			SongImports.chooseMidiOrNbs(this, config, this::applyImportedProject));
	}

	/**
	 * Reads a machine standing in the world back into a song.
	 *
	 * <p>An import like any other: it opens as a document of its own rather than editing this one,
	 * because a scan is a new song and not a change to whatever happened to be open.</p>
	 */
	private void scanWorldRegion() {
		withUnsavedChangesChecked(() ->
			SongImports.scanWorld(this, this::applyImportedProject));
	}

	private void importSchematic() {
		withUnsavedChangesChecked(() ->
			SongImports.chooseSchematic(this, config, this::applyImportedProject));
	}

	private void openSongs() {
		withUnsavedChangesChecked(() -> minecraft.gui.setScreen(new SongsScreen(parent, config)));
	}

	/**
	 * Writes what is open out as a Note Block Studio file.
	 *
	 * <p>No unsaved-changes check, and deliberately: an export reads the composition and writes
	 * somewhere else entirely, so there is nothing of yours it can lose. It exports what is on the
	 * screen rather than what is on disk, which is the whole point of being able to do it before
	 * saving.</p>
	 *
	 * <p>Written beside the files imports are read from, because that is the folder the player
	 * already thinks of as where songs come from and go.</p>
	 */
	private void exportAsNbs() {
		minecraft.gui.setScreen(new NamePromptScreen(this, "Export as .nbs",
			"Write \"" + project().name() + "\" to " + config.importDirectory(),
			project().name(), "Export", name -> {
				String wanted = name.trim().isEmpty() ? project().name() : name.trim();
				try {
					Path folder = Path.of(config.importDirectory());
					Files.createDirectories(folder);
					NbsExporter.Result written = NbsExporter.export(project(),
						folder.resolve(safeFileName(wanted) + ".nbs"));
					showResult(Component.literal("Exported to " + written.path()
						+ " - " + written.report()));
				} catch (Exception failed) {
					SongImports.showFailure(this, failed);
					return;
				}
				minecraft.gui.setScreen(this);
			}));
	}

	/** A name a filesystem will take, with the characters no filesystem takes turned into spaces. */
	private static String safeFileName(String name) {
		String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
		return cleaned.isEmpty() ? "composition" : cleaned;
	}

	/** Opens what was read as a document of its own. Nothing already saved is touched. */
	private void applyImportedProject(SongImports.Imported imported) {
		SongImports.open(parent, config, onReturn, imported);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractBlurredBackground(graphics);
		extractTransparentBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		long frameStart = profiling ? System.nanoTime() : 0L;
		updatePlayback();
		rollX = layerPanelWidth() + PIANO_WIDTH;
		rollY = TOOLBAR_HEIGHT + 14 + TIMELINE_RULER_HEIGHT;
		rollWidth = Math.max(40, width - rollX - 8);
		rollHeight = Math.max(40, height - rollY - 24);
		long mark = frameStart;
		extractPanels(graphics);
		mark = phase(PHASE_PANELS, mark);
		extractTimeRuler(graphics, mouseX, mouseY);
		mark = phase(PHASE_RULER, mark);
		mark = extractPianoRoll(graphics, mouseX, mouseY, mark);
		extractStatus(graphics);
		extractToast(graphics, mouseX, mouseY);
		mark = phase(PHASE_STATUS, mark);
		hoveredDescription = "";
		// The bar last of the backgrounds and first of the foregrounds: it has to cover the roll,
		// and its own controls and the composition name have to sit on top of it.
		extractMenuBar(graphics, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		mark = phase(PHASE_WIDGETS, mark);
		extractInstrumentMenu(graphics, mouseX, mouseY);
		extractContextMenu(graphics, mouseX, mouseY);
		extractLayerMenu(graphics, mouseX, mouseY);
		extractToolbarMenu(graphics, mouseX, mouseY);
		extractMenuDescription(graphics);
		phase(PHASE_MENUS, mark);
		endProfiledFrame(graphics, frameStart);
	}

	/** Charges the time since {@code since} to a phase, and returns the mark for the next one. */
	private long phase(int index, long since) {
		if (!profiling) {
			return 0L;
		}
		long now = System.nanoTime();
		phaseNanos[index] += now - since;
		return now;
	}

	/**
	 * Closes a profiled frame: draws the overlay, and once a second logs and clears the window.
	 *
	 * <p>Reported as an average over the window rather than per frame, because a single frame's
	 * numbers on this screen swing by a factor of three -- a garbage collection, a chunk build, a
	 * sound starting. The averages are stable enough to tell a real change from noise.</p>
	 */
	private void endProfiledFrame(GuiGraphicsExtractor graphics, long frameStart) {
		if (!profiling) {
			return;
		}
		profileFrameNanos += System.nanoTime() - frameStart;
		profileFrames++;
		long now = Util.getMillis();
		if (profileWindowSince == 0L) {
			profileWindowSince = now;
		} else if (now - profileWindowSince >= PROFILE_WINDOW_MILLIS && profileFrames > 0) {
			StringBuilder line = new StringBuilder(String.format(java.util.Locale.ROOT,
				"composer %.1f fps, %.2f ms/frame extracting", profileFrames * 1000.0 / (now - profileWindowSince),
				profileFrameNanos / 1.0e6 / profileFrames));
			for (int index = 0; index < PHASES.length; index++) {
				line.append(String.format(java.util.Locale.ROOT, "  %s %.2f",
					PHASES[index], phaseNanos[index] / 1.0e6 / profileFrames));
			}
			line.append(String.format(java.util.Locale.ROOT,
				"  |  notes=layout %.2f + quads %.2f  |  %d seen, %d drawn, %d quads,"
					+ " %.0f ticks/px, row %dpx, %d layers",
				notesLayoutNanos / 1.0e6 / profileFrames, notesDrawNanos / 1.0e6 / profileFrames,
				notesConsidered, notesDrawn, noteQuads, ticksPerPixel, rowHeight,
				project().layers().size()));
			profileSummary = line.toString();
			PERF.info(profileSummary);
			java.util.Arrays.fill(phaseNanos, 0L);
			profileFrameNanos = 0L;
			notesLayoutNanos = 0L;
			notesDrawNanos = 0L;
			profileFrames = 0;
			profileWindowSince = now;
		}
		if (!profileSummary.isEmpty()) {
			// Drawn last and unclipped, over everything, because it is a measuring instrument and
			// not part of the screen. Two lines so it fits without shrinking the font.
			int split = profileSummary.indexOf("  |  ");
			String top = split < 0 ? profileSummary : profileSummary.substring(0, split);
			String bottom = split < 0 ? "" : profileSummary.substring(split + 5);
			graphics.fill(4, TOOLBAR_HEIGHT + 2, 10 + Math.max(smallTextWidth(top), smallTextWidth(bottom)),
				TOOLBAR_HEIGHT + 22, 0xE0000000);
			smallText(graphics, top, 7, TOOLBAR_HEIGHT + 5, 0xFF8FD3FF);
			smallText(graphics, bottom, 7, TOOLBAR_HEIGHT + 13, 0xFF9BE564);
		}
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
			case MERGE_SELECTED -> "Merge " + selected + " layers (Ctrl+E)";
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
			case RENAME, SELECT_ALL -> true;
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
			case RENAME -> beginLayerRename(project().activeLayerIndex());
			case MERGE_SELECTED -> mergeSelectedLayers();
			case INCLUDE_SELECTED -> setIncludedLayers(true);
			case SET_INCLUDED_TO_SELECTION -> setIncludedLayers(false);
			case SELECT_ALL -> {
				selectedLayers.clear();
				for (int index = 0; index < project().layers().size(); index++) {
					selectedLayers.add(index);
				}
			}
		}
		layersChanged();
		rebuildMoveLayerButtons();
		return true;
	}

	private void extractToolbarMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<MenuRow> rows = menuRows(toolbarMenu);
		if (rows.isEmpty()) {
			openSubmenu = null;
			return;
		}
		int menuWidth = menuWidth(rows, toolbarMenu == ToolbarMenu.IMPORT
			? IMPORT_MENU_WIDTH : TOOLBAR_MENU_WIDTH, toolbarMenuX);
		int menuHeight = rows.size() * TOOLBAR_MENU_ROW_HEIGHT + 4;
		// Whether the cursor is in the open submenu has to be settled before the parent rows get a
		// say, or reaching across into the submenu would count as leaving the row that opened it.
		boolean insideSubmenu = openSubmenu != null && mouseX >= submenuLeft && mouseX < submenuRight
			&& mouseY >= submenuTop && mouseY < submenuBottom;
		graphics.fill(toolbarMenuX, MENU_PANEL_TOP, toolbarMenuX + menuWidth,
			MENU_PANEL_TOP + menuHeight, 0xF0101115);
		graphics.fill(toolbarMenuX, MENU_PANEL_TOP, toolbarMenuX + menuWidth,
			MENU_PANEL_TOP + 1, 0xFFAAAAAA);
		ToolbarSubmenu wanted = insideSubmenu ? openSubmenu : null;
		int wantedAnchorY = submenuAnchorY;
		for (int index = 0; index < rows.size(); index++) {
			MenuRow row = rows.get(index);
			int rowY = MENU_PANEL_TOP + 2 + index * TOOLBAR_MENU_ROW_HEIGHT;
			boolean enabled = rowEnabled(row);
			boolean hovered = mouseX >= toolbarMenuX && mouseX < toolbarMenuX + menuWidth
				&& mouseY >= rowY && mouseY < rowY + TOOLBAR_MENU_ROW_HEIGHT;
			boolean held = row.submenu() != null && row.submenu() == openSubmenu;
			if (hovered && enabled || held) {
				graphics.fill(toolbarMenuX + 2, rowY, toolbarMenuX + menuWidth - 2,
					rowY + TOOLBAR_MENU_ROW_HEIGHT, held && !hovered ? 0xFF24414C : 0xFF356070);
			}
			if (hovered && enabled) {
				hoveredDescription = rowDescription(row);
				if (row.submenu() != null) {
					// Opens on hover: the arrow is a promise that pointing at it is enough.
					wanted = row.submenu();
					wantedAnchorY = rowY;
				}
			}
			if (row.submenu() != null) {
				graphics.text(font, SUBMENU_ARROW, toolbarMenuX + menuWidth - 10, rowY + 5,
					enabled ? 0xFFAAB2BD : 0xFF5A5F66, false);
			} else if (row.action() != null) {
				String hint = toolbarShortcut(row.action());
				if (!hint.isEmpty()) {
					graphics.text(font, Component.literal(hint),
						toolbarMenuX + menuWidth - 6 - font.width(hint), rowY + 5, 0xFF6E7480, false);
				}
			}
			graphics.text(font, Component.literal(rowLabel(row)), toolbarMenuX + 6, rowY + 5,
				enabled ? 0xFFFFFFFF : 0xFF777777, false);
		}
		if (toolbarMenu == ToolbarMenu.IMPORT) {
			graphics.text(font, Component.literal("Left-click cycles, right-click reverses"),
				toolbarMenuX + 6, MENU_PANEL_TOP + menuHeight + 3, 0xFF888888, false);
		}
		openSubmenu = wanted;
		if (openSubmenu == null) {
			submenuLeft = submenuRight = submenuTop = submenuBottom = 0;
			return;
		}
		submenuAnchorY = wantedAnchorY;
		extractSubmenu(graphics, mouseX, mouseY, toolbarMenuX + menuWidth - 2, submenuAnchorY);
	}

	/** Where a submenu row starts, which the divider's gap shifts everything after it by. */
	private int submenuRowTop(int panelTop, int index) {
		int gap = openSubmenu.dividerBefore >= 0 && index >= openSubmenu.dividerBefore
			? SUBMENU_DIVIDER_GAP
			: 0;
		return panelTop + 2 + index * TOOLBAR_MENU_ROW_HEIGHT + gap;
	}

	/** The second panel, hanging off the row that opened it. */
	private void extractSubmenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			int left, int anchorY) {
		List<MenuRow> rows = new ArrayList<>();
		for (ToolbarAction action : openSubmenu.actions) {
			rows.add(MenuRow.of(action));
		}
		int panelWidth = 78;
		for (MenuRow row : rows) {
			String detail = submenuRowDetail(row.action());
			panelWidth = Math.max(panelWidth, font.width(openSubmenu.labelFor(row.action()))
				+ (detail.isEmpty() ? 14 : font.width(detail) + 26));
		}
		panelWidth = Math.min(panelWidth, Math.max(60, width - left - 4));
		// Flipped to the near side rather than run off the edge of the screen.
		if (left + panelWidth > width - 4) {
			left = Math.max(0, toolbarMenuX - panelWidth + 2);
		}
		int panelHeight = rows.size() * TOOLBAR_MENU_ROW_HEIGHT + 4
			+ (openSubmenu.dividerBefore >= 0 ? SUBMENU_DIVIDER_GAP : 0);
		// Always off the anchor, never off wherever the panel ended up last frame.
		int top = Math.max(MENU_PANEL_TOP, Math.min(anchorY - 2, height - panelHeight - 4));
		submenuLeft = left;
		submenuTop = top;
		submenuRight = left + panelWidth;
		submenuBottom = top + panelHeight;
		graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0141A20);
		graphics.fill(left, top, left + panelWidth, top + 1, 0xFFAAAAAA);
		for (int index = 0; index < rows.size(); index++) {
			MenuRow row = rows.get(index);
			int rowY = submenuRowTop(top, index);
			if (index == openSubmenu.dividerBefore) {
				graphics.fill(left + 6, rowY - SUBMENU_DIVIDER_GAP / 2,
					left + panelWidth - 6, rowY - SUBMENU_DIVIDER_GAP / 2 + 1, 0xFF3A424D);
			}
			boolean enabled = rowEnabled(row);
			boolean hovered = enabled && mouseX >= left && mouseX < left + panelWidth
				&& mouseY >= rowY && mouseY < rowY + TOOLBAR_MENU_ROW_HEIGHT;
			if (hovered) {
				graphics.fill(left + 2, rowY, left + panelWidth - 2,
					rowY + TOOLBAR_MENU_ROW_HEIGHT, 0xFF356070);
				hoveredDescription = rowDescription(row);
			}
			String detail = submenuRowDetail(row.action());
			if (!detail.isEmpty()) {
				graphics.text(font, Component.literal(detail),
					left + panelWidth - 6 - font.width(detail), rowY + 5,
					enabled ? 0xFF8A929E : 0xFF5A5F66, false);
			}
			graphics.text(font, Component.literal(openSubmenu.labelFor(row.action())),
				left + 6, rowY + 5, enabled ? 0xFFFFFFFF : 0xFF777777, false);
		}
	}

	/** Where each menu's title sits in the bar, measured off the font rather than a fixed pitch. */
	private List<MenuTitle> menuTitles() {
		List<MenuTitle> titles = new ArrayList<>(MENU_BAR.length);
		int x = MENU_BAR_LEFT;
		for (ToolbarMenu menu : MENU_BAR) {
			String label = menuBarLabel(menu);
			int wide = font.width(label) + 2 * MENU_TITLE_PADDING;
			titles.add(new MenuTitle(menu, label, x, x + wide));
			x += wide;
		}
		return titles;
	}

	/** The width a control needs to hold any caption it can ever show, plus its padding. */
	private int widestLabel(int padding, String... labels) {
		int widest = 0;
		for (String label : labels) {
			widest = Math.max(widest, font.width(label));
		}
		return widest + padding;
	}

	private static String menuBarLabel(ToolbarMenu menu) {
		return switch (menu) {
			case FILE -> "File";
			case EDIT -> "Edit";
			case IMPORT -> "Import";
			case SELECT -> "Select";
			case BUILD -> "Build";
			case NONE -> "";
		};
	}

	private void extractMenuBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, width, TOOLBAR_HEIGHT, 0xE0141821);
		graphics.fill(0, TOOLBAR_HEIGHT - 1, width, TOOLBAR_HEIGHT, 0xFF2C333D);
		for (MenuTitle title : menuTitles()) {
			boolean open = toolbarMenu == title.menu();
			boolean hovered = mouseY >= MENU_BAR_TOP && mouseY < MENU_BAR_TOP + MENU_BAR_ROW_HEIGHT
				&& mouseX >= title.left() && mouseX < title.right();
			if (open || hovered) {
				graphics.fill(title.left(), MENU_BAR_TOP, title.right(),
					MENU_BAR_TOP + MENU_BAR_ROW_HEIGHT, open ? 0xFF2B4C5A : 0x40FFFFFF);
			}
			graphics.text(font, title.label(), title.left() + MENU_TITLE_PADDING, MENU_BAR_TOP + 4,
				open ? 0xFFCDE9FF : 0xFFD6D8DD, false);
		}
		extractCompositionName(graphics);
	}

	/** The menu a point in the bar would open, or null. */
	private ToolbarMenu menuTitleAt(double x, double y) {
		if (y < MENU_BAR_TOP || y >= MENU_BAR_TOP + MENU_BAR_ROW_HEIGHT) {
			return null;
		}
		for (MenuTitle title : menuTitles()) {
			if (x >= title.left() && x < title.right()) {
				return title.menu();
			}
		}
		return null;
	}

	/** Menu width from the widest row it holds, including its shortcut hint or submenu arrow. */
	private int menuWidth(List<MenuRow> rows, int floor, int left) {
		int widest = floor;
		for (MenuRow row : rows) {
			int tail = row.submenu() != null
				? font.width(SUBMENU_ARROW) + 16
				: row.action() != null && !toolbarShortcut(row.action()).isEmpty()
					? font.width(toolbarShortcut(row.action())) + 30
					: 14;
			widest = Math.max(widest, font.width(rowLabel(row)) + tail);
		}
		return Math.min(widest, Math.max(60, width - left - 4));
	}

	/**
	 * The rows of a menu, in order.
	 *
	 * <p>Quantize and End are submenus because their members differ only in their last word, and a
	 * list of four rows that all begin "Quantize to" is a list you have to read rather than scan.
	 * Everything else stays where it is: a submenu costs a second movement to reach, which is only
	 * worth paying where it buys the parent menu back four rows of height.</p>
	 */
	private List<MenuRow> menuRows(ToolbarMenu menu) {
		List<MenuRow> rows = new ArrayList<>();
		switch (menu) {
			case FILE -> addActionRows(rows, ToolbarAction.FILE_ACTIONS);
			case EDIT -> {
				addActionRows(rows, ToolbarAction.EDIT_ACTIONS);
				rows.add(4, MenuRow.of(ToolbarSubmenu.QUANTIZE));
				rows.add(MenuRow.of(ToolbarSubmenu.END));
			}
			case BUILD -> addActionRows(rows, ToolbarAction.BUILD_ACTIONS);
			case SELECT -> addActionRows(rows, ToolbarAction.SELECT_ACTIONS);
			case IMPORT -> {
				for (ImportSetting setting : ImportSetting.values()) {
					rows.add(MenuRow.of(setting));
				}
			}
			case NONE -> {
			}
		}
		return rows;
	}

	private void addActionRows(List<MenuRow> rows, ToolbarAction[] actions) {
		for (ToolbarAction action : actions) {
			rows.add(MenuRow.of(action));
		}
	}

	private String rowLabel(MenuRow row) {
		if (row.submenu() != null) {
			return row.submenu().label;
		}
		if (row.setting() != null) {
			return importSettingLabel(row.setting());
		}
		return toolbarRowLabel(row.action())
			+ (selectedNotes.isEmpty() || !row.action().scopeable ? "" : " (selection)");
	}

	private boolean rowEnabled(MenuRow row) {
		if (row.setting() != null) {
			return true;
		}
		if (row.submenu() == null) {
			return toolbarActionEnabled(row.action());
		}
		// A submenu is worth opening while any one thing inside it can be done.
		for (ToolbarAction action : row.submenu().actions) {
			if (toolbarActionEnabled(action)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * How wide a step each quantize row snaps to, in song ticks, for the column on the right.
	 *
	 * <p>The fractions are note values -- 1/4 is a quarter note, which at 480 ticks to the quarter
	 * is 480 ticks and the <em>coarsest</em> row here, not the finest. Read as "a quarter of a
	 * repeater tick" they say the opposite of what they mean, and the repeater row looks like it
	 * ought to be the 1 they all divide. It is not a unit any of them divide: across this library
	 * its grid runs from 48 ticks to 480, finer than a 1/16 on one song and a whole 1/4 on
	 * another. Printing the tick counts settles all of it without anybody having to know the
	 * convention -- the numbers are directly comparable and each is what that row will actually
	 * snap to.</p>
	 */
	private String submenuRowDetail(ToolbarAction action) {
		long ticks = switch (action) {
			case QUANTIZE_QUARTER -> project().ppq();
			case QUANTIZE_EIGHTH -> Math.max(1, project().ppq() / 2);
			case QUANTIZE_SIXTEENTH -> Math.max(1, project().ppq() / 4);
			// The grid the operation will really use, not the width of one repeater tick. Those
			// differ whenever a repeater tick is not a whole number of song ticks, and quoting the
			// second one here said 1/8 for a song whose repeater grid was nothing of the kind.
			case QUANTIZE_REPEATERS -> project().repeaterGridTicks();
			default -> 0L;
		};
		return ticks == 0L ? "" : Long.toString(ticks);
	}

	private String rowDescription(MenuRow row) {
		if (row.submenu() != null) {
			return row.submenu().description;
		}
		return row.setting() != null
			? importSettingTooltip(row.setting())
			: toolbarActionTooltip(row.action());
	}

	private void toggleToolbarMenu(ToolbarMenu menu, int x) {
		if (toolbarMenu == menu) {
			toolbarMenu = ToolbarMenu.NONE;
			openSubmenu = null;
			return;
		}
		toolbarMenu = menu;
		toolbarMenuX = x;
		openSubmenu = null;
		contextMenuOpen = false;
		instrumentMenuLayer = -1;
	}

	private boolean toolbarActionEnabled(ToolbarAction action) {
		return switch (action) {
			case UNDO -> history.canUndo();
			case REDO -> history.canRedo();
			case CONVERT, MERGE_REPEATS, FIT_ALL_RANGE, SNAP_TEMPO,
				QUANTIZE_QUARTER, QUANTIZE_EIGHTH, QUANTIZE_SIXTEENTH, QUANTIZE_REPEATERS ->
				project().layers().stream().anyMatch(layer -> !layer.notes().isEmpty());
			case SELECT_OFF_GRID -> !projectStats().offGridNotes().isEmpty();
			case SELECT_TOO_FREQUENT -> !projectStats().crowdedNotes().isEmpty();
			case SELECT_OUT_OF_RANGE -> projectStats().outOfRange() > 0;
			case SELECT_OVERLOADED_CHORDS -> projectStats().overloadedTicks() > 0
				|| projectStats().peakChord() > config.chordThinTarget();
			case SELECT_NONE -> !selectedNotes.isEmpty();
			// Nothing to scan from the title screen, and the coordinate prompt would have no way
			// to tell you that the region you typed reads as empty because there is no world.
			case SCAN_WORLD -> minecraft.level != null;
			// Not disabled on an unbuildable song: greying it out would hide the reason. The status
			// bar already names the problem and the planner refuses with a specific one.
			case PASTE_IN_WORLD -> projectStats().totalNotes() > 0 || project().endTick() > 0L;
			case INCLUDE_SELECTED, SET_INCLUDED_TO_SELECTION -> !selectionLayers().isEmpty();
			case BUILD_CANCEL -> CommandPasteSender.isRunning();
			default -> true;
		};
	}

	private boolean handleToolbarMenuClick(double mouseX, double mouseY, int button) {
		// The submenu is drawn over the parent panel, so it gets the click first wherever they meet.
		if (openSubmenu != null && mouseX >= submenuLeft && mouseX < submenuRight
				&& mouseY >= submenuTop && mouseY < submenuBottom) {
			ToolbarAction action = null;
			for (int index = 0; index < openSubmenu.actions.length; index++) {
				int rowY = submenuRowTop(submenuTop, index);
				if (mouseY >= rowY && mouseY < rowY + TOOLBAR_MENU_ROW_HEIGHT) {
					action = openSubmenu.actions[index];
					break;
				}
			}
			if (action == null || !toolbarActionEnabled(action)) {
				return true;
			}
			toolbarMenu = ToolbarMenu.NONE;
			openSubmenu = null;
			performToolbarAction(action);
			return true;
		}
		List<MenuRow> rows = menuRows(toolbarMenu);
		int menuWidth = menuWidth(rows, toolbarMenu == ToolbarMenu.IMPORT
			? IMPORT_MENU_WIDTH : TOOLBAR_MENU_WIDTH, toolbarMenuX);
		if (rows.isEmpty() || mouseX < toolbarMenuX || mouseX >= toolbarMenuX + menuWidth) {
			return false;
		}
		int row = ((int)mouseY - MENU_PANEL_TOP - 2) / TOOLBAR_MENU_ROW_HEIGHT;
		if (row < 0 || row >= rows.size() || mouseY < MENU_PANEL_TOP + 2) {
			return false;
		}
		MenuRow clicked = rows.get(row);
		if (!rowEnabled(clicked)) {
			return true;
		}
		if (clicked.submenu() != null) {
			// Already opened by hovering; clicking it is neither a mistake nor a second thing.
			openSubmenu = clicked.submenu();
			return true;
		}
		if (clicked.setting() != null) {
			// Settings stay open so several can be adjusted in one visit.
			cycleImportSetting(clicked.setting(), button == 1 ? -1 : 1);
			return true;
		}
		toolbarMenu = ToolbarMenu.NONE;
		openSubmenu = null;
		performToolbarAction(clicked.action());
		return true;
	}

	private void performToolbarAction(ToolbarAction action) {
		switch (action) {
			case IMPORT -> importSong();
			case SCAN_WORLD -> scanWorldRegion();
			case IMPORT_SCHEMATIC -> importSchematic();
			case OPEN_SONGS -> openSongs();
			case RENAME_COMPOSITION -> renameComposition();
			case EXPORT_NBS -> exportAsNbs();
			case COPY_AS_TEXT -> copySequenceAsText();
			case INCLUDE_SELECTED -> setIncludedLayers(true);
			case SET_INCLUDED_TO_SELECTION -> setIncludedLayers(false);
			case PASTE_IN_WORLD -> pasteInWorld();
			case BUILD_CANCEL -> CommandPasteSender.cancel(true);
			case TOGGLE_DEDUPE -> {
				config.setDedupeIdenticalNotes(!config.dedupeIdenticalNotes());
				FastNoteblocksConfig.save();
				if (playing) {
					resetPlaybackSchedule();
				}
				showResult(Component.literal(config.dedupeIdenticalNotes()
					? "Identical simultaneous notes will be built once."
					: "Identical simultaneous notes will each be built."));
			}
			case SAVE_COMPOSITION -> saveComposition();
			case SAVE_COMPOSITION_AS -> saveCompositionAs();
			case BACK_TO_SEQUENCES -> onClose();
			case CLOSE_TO_GAME -> closeToGame();
			case UNDO -> undo();
			case REDO -> redo();
			case CONVERT -> convertToMinecraft();
			case MERGE_REPEATS -> applyStep("Merged",
				project().withMergedRepeats(config.repeatMergeTicks(), selectedNotes));
			case QUANTIZE_QUARTER -> quantizeTo(project().ppq());
			case QUANTIZE_EIGHTH -> quantizeTo(Math.max(1, project().ppq() / 2));
			case QUANTIZE_SIXTEENTH -> quantizeTo(Math.max(1, project().ppq() / 4));
			case QUANTIZE_REPEATERS -> quantizeToRepeaters();
			case FIT_ALL_RANGE -> applyStep("Fitted to range",
				project().withAllFittedToRange(selectedNotes));
			case SNAP_TEMPO -> snapTempo();
			case SNAP_END -> applyStep("End snapped", project().withEndTick(
				snapEndToRepeaterGrid()));
			case TRIM_END -> applyStep("Trimmed", project().trimmedToContent());
			case SELECT_OFF_GRID -> selectNotesWhere("off grid",
				note -> projectStats().offGrid().contains(note.startTick()), true);
			case SELECT_TOO_FREQUENT -> selectNotesWhere("too frequent",
				note -> projectStats().crowded().contains(note.startTick()), true);
			case SELECT_OUT_OF_RANGE -> selectNotesWhere("out of range",
				note -> !note.isBuildable(), true);
			case SELECT_OVERLOADED_CHORDS -> selectOverloadedChordNotes();
			case SELECT_ALL_NOTES -> selectNotesWhere("selected", note -> true, false);
			case SELECT_NONE -> {
				selectedNotes.clear();
				updateButtonStates();
			}
		}
	}

	/**
	 * The nearest tempo at which the song's own spacing is a whole number of repeater ticks.
	 *
	 * <p>Measured off the notes, not off a grid chosen elsewhere. It used to take the Import menu's
	 * quantize setting and force one step of that to be at least one repeater tick, whether or not
	 * any two notes in the song were ever that close -- so a song already sitting on the repeater
	 * grid got slowed by up to nine times and came back with hundreds of gaps it did not have
	 * before. On the same songs it is now a no-op, which is the right answer for something already
	 * aligned.</p>
	 */
	private void snapTempo() {
		ComposerProject baked = project().withBakedSpeed();
		ComposerProject.NoteSpacing spacing = baked.noteSpacing();
		if (spacing.gridTicks() <= 0L) {
			showResult(Component.literal("Not enough notes to work out a spacing."));
			return;
		}
		int tempo = baked.repeaterAlignedTempoFor(
			(int)Math.min(Integer.MAX_VALUE, spacing.gridTicks()));
		ComposerProject snapped = baked.withTempo(tempo);
		// Measured against the song's own tightest gap, not against a repeater tick. A slow song
		// whose notes are naturally sixteen ticks apart is not a problem and its tempo does not
		// move; a song whose grid is five times finer than anything it plays is one, because that
		// grid came from a few strays and the tempo would follow them down.
		if (spacing.smallestGapTicks() > spacing.gridTicks() * MAX_GRID_STRETCH) {
			showResult(Component.literal(String.format(java.util.Locale.ROOT,
				"The notes share no usable spacing: every gap is a multiple of %d ticks, but the "
					+ "closest two are %d apart. No tempo fixes that without slowing the song %.2fx, "
					+ "so nothing was changed. Quantize to repeater ticks moves the notes instead, "
					+ "which is what this needs.",
				spacing.gridTicks(), spacing.smallestGapTicks(),
				tempo / (double)baked.tempoMicrosPerQuarter()))
				.withStyle(net.minecraft.ChatFormatting.YELLOW));
			return;
		}
		applyTimingStep("Tempo snapped", snapped);
	}

	private void quantizeTo(int gridTicks) {
		applyStep("Quantized", project().withQuantized(gridTicks, selectedNotes));
	}

	/**
	 * Puts note starts on the grid redstone counts in, and says which grid that was.
	 *
	 * <p>Worth reporting rather than doing quietly: the grid is whatever the tempo and speed make
	 * it, so it is routinely something like 330 ticks that no musical grid would ever offer, and
	 * how many notes it folded together is the thing to listen for afterwards.</p>
	 */
	private void quantizeToRepeaters() {
		ComposerProject.RepeaterQuantize result = project().withQuantizedToRepeaters(selectedNotes);
		if (result.project().equals(project())) {
			showResult(Component.literal("Already on the repeater grid."));
			return;
		}
		int merged = distinctStartTicks(project()) - distinctStartTicks(result.project());
		apply(result.project());
		layersChanged();
		String report = String.format(java.util.Locale.ROOT,
			"Quantized to %d ticks (%d repeater tick%s)%s%s",
			result.gridTicks(), result.repeaterTicks(), result.repeaterTicks() == 1 ? "" : "s",
			merged > 0 ? ", " + merged + " notes folded into chords" : "",
			result.tempoNudged() ? ", tempo nudged to fit" : "");
		showResult(Component.literal(report));
	}

	private static int distinctStartTicks(ComposerProject project) {
		return (int)project.layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(ComposerProject.NoteEvent::startTick)
			.distinct()
			.count();
	}

	/** A step that may have folded the speed slider away, so the slider has to be told. */
	private void applyTimingStep(String label, ComposerProject updated) {
		applyStep(label, updated);
		if (delayScaleSlider != null) {
			delayScaleSlider.setScale(delayScaleQuarters());
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
		layersChanged();
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
	 * Selects what a chord could lose to fit, without deleting any of it.
	 *
	 * <p>Selecting rather than applying because this is the one edit here whose result has to be
	 * listened to. Everything else in the Select menu names notes that are already wrong; this one
	 * names notes that are merely the least missed, and the difference between those two is a
	 * judgement the composer is in no position to make on its own. Delete commits it, Escape walks
	 * away, and playback in between is the whole point.</p>
	 *
	 * <p>Not routed through {@link #selectNotesWhere}: that judges a note at a time and only looks
	 * at the layers being edited, while what a chord can spare depends on the whole chord and the
	 * limit applies to every layer that is going into the build.</p>
	 */
	private void selectOverloadedChordNotes() {
		int target = config.chordThinTarget();
		// Scoped to the layer selection like the rest of the Select menu, so thinning one part is
		// possible at all. How big a chord is still comes from every layer in the build -- only
		// where the notes may be taken from narrows. Select the whole panel for the whole song.
		Set<Integer> scope = new LinkedHashSet<>();
		for (int layerIndex : selectionLayers()) {
			if (layerIndex >= 0 && layerIndex < project().layers().size()
					&& project().layers().get(layerIndex).visible()) {
				scope.add(layerIndex);
			}
		}
		ChordThinner.Result thinned =
			ChordThinner.thin(project(), target, config.dedupeIdenticalNotes(), scope);
		selectedNotes.clear();
		selectedNotes.addAll(thinned.noteIds());
		updateButtonStates();
		int layerCount = project().layers().size();
		// Scope first, before any number it qualifies. The likeliest way to be surprised by this is
		// to run it on whichever layer happened to be active, get a fraction of the song, and read
		// that as the song being done -- and a caveat at the end of a paragraph arrives too late to
		// stop that. Everything after the colon is about the layers named before it.
		String where = scope.size() == layerCount
			? "Notes from all " + layerCount + " layers"
			: "Notes from " + scope.size() + " of " + layerCount
				+ " layers selected (select more layers to thin more at once)";
		if (thinned.chordsOver() == 0) {
			showResult(Component.literal("No chord is over " + target + " - nothing to thin."));
			return;
		}
		if (thinned.isEmpty()) {
			showResult(Component.literal(where + ": nothing here can be spared, so all "
				+ thinned.chordsOver() + " chords over " + target + " are untouched. Every sound "
				+ "in these layers is the last of its pitch or the last of its instrument."));
			return;
		}
		// Sounds and notes are different numbers whenever deduplication is on, and saying only one
		// of them invites the obvious wrong conclusion -- that deleting the selection will take the
		// count down by however many notes it holds.
		StringBuilder summary = new StringBuilder(where + ": " + thinned.chordsThinned() + " of "
			+ thinned.chordsOver() + " chords over " + target + " thinned, "
			+ thinned.soundsRemoved() + " sounds selected as " + thinned.noteIds().size()
			+ " notes. Delete to commit, Escape to keep them.");
		if (thinned.chordsStillOver() > 0) {
			summary.append(' ').append(thinned.chordsStillOver()).append(" still over ")
				.append(target).append('.');
		}
		showResult(Component.literal(summary.toString()));
	}

	/**
	 * What a menu row does, in a sentence.
	 *
	 * <p>Every row has one. Several of these actions are irreversible in the world or change the
	 * whole song, and a bare verb like "Convert" or "Quantize" does not tell you which.</p>
	 */
	private String toolbarActionTooltip(ToolbarAction action) {
		return switch (action) {
			case IMPORT -> "Reads a MIDI or NBS file in as a song of its own. Nothing you already "
				+ "have is touched. Settings below control how it is read.";
			case SCAN_WORLD -> "Reads a note block machine standing in the world back into a song, "
				+ "by following its redstone. Give two corners. Only chunks your client has "
				+ "loaded can be read, so stand near the build.";
			case IMPORT_SCHEMATIC -> "Reads a saved build back into a song by following its "
				+ "redstone. Structure (.nbt), Sponge (.schem) and Litematica (.litematic) files; "
				+ "the old MCEdit .schematic stores pre-1.13 numbered blocks and cannot be read.";
			case OPEN_SONGS -> "The song library: open another composition, start one, or make a "
				+ "copy.";
			case EXPORT_NBS -> "Writes this composition to a .nbs file that Note Block Studio and "
				+ "other tools can open, in the same folder imports are read from. Exports what "
				+ "is on screen, saved or not. Instruments outside NBS's sixteen are written as "
				+ "harp, and the report says how many were.";
			case COPY_AS_TEXT -> "Puts the build sequence on the clipboard, one line per included "
				+ "layer. Out-of-range notes and sub-tick timing do not survive the trip.";
			case SAVE_COMPOSITION -> "Writes this composition to its own file. Nothing else does: "
				+ "edits live in memory until you save them.";
			case SAVE_COMPOSITION_AS -> "Saves a copy under a new name and opens it. Naming a song "
				+ "that already exists offers to replace it.";
			case RENAME_COMPOSITION -> "Renames this composition. The change is an edit like any "
				+ "other, so it takes a save to keep.";
			case BACK_TO_SEQUENCES -> "Leave the composer. Unsaved edits are asked about first.";
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
			case QUANTIZE_QUARTER, QUANTIZE_EIGHTH, QUANTIZE_SIXTEENTH ->
				"Moves note starts onto that musical grid. These are note values, so 1/4 is a "
					+ "quarter note and the coarsest of them -- the number beside each is its step "
					+ "in song ticks. A coarser grid fixes more and changes more. Whether it makes "
					+ "the song buildable depends on the tempo: a 1/16 only helps if a 1/16 is a "
					+ "whole number of repeater ticks.";
			case QUANTIZE_REPEATERS -> "Moves note starts onto whole repeater ticks -- the ruler "
				+ "that actually decides, worked out from the tempo and the current speed, so it is "
				+ "usually not a musical fraction at all. Notes closer than one tick land together "
				+ "as a chord, which is how a passage faster than redstone becomes buildable without "
				+ "slowing the whole song down. Moves the tempo by a fraction of a percent if no "
				+ "small grid exists at the current one.";
			case FIT_ALL_RANGE -> "Octave-shifts notes outside F#3-F#5 into it. Quick rather than "
				+ "faithful: intervals across a layer can change.";
			case SNAP_TEMPO -> "Moves the tempo as little as it can while making the spacing the "
				+ "song already has land on whole repeater ticks, folding the speed slider in first. "
				+ "Leaves every note where it is, so it does nothing for a song whose notes share no "
				+ "usable grid -- it says so rather than dragging the tempo down to meet them.";
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
			case TOGGLE_DEDUPE -> "When two included layers ask for the same instrument and pitch at "
				+ "the same tick, build it once. Preview has always collapsed these, so they are "
				+ "inaudible either way, but each costs a note block and one of the thirty a tick "
				+ "can carry. Nothing is deleted: give one of those layers a different instrument "
				+ "and both notes come back.";
			case SELECT_OFF_GRID -> "Selects notes whose gap from the previous one is not a whole "
				+ "repeater tick.";
			case SELECT_TOO_FREQUENT -> "Selects notes arriving less than one repeater tick after "
				+ "the previous one -- faster than redstone can retrigger.";
			case SELECT_OUT_OF_RANGE -> "Selects notes outside the note-block range of F#3-F#5.";
			case SELECT_OVERLOADED_CHORDS -> "Selects the notes worth least in every chord bigger "
				+ "than the thinning target, so you can hear the song without them before deleting. "
				+ "Never the last of a pitch or the last of an instrument, so a chord keeps its "
				+ "harmony and keeps its drum -- only how thickly they are scored changes. Takes "
				+ "from the selected layers only; select them all to thin the whole song.";
			case SELECT_ALL_NOTES -> "Selects every note on the active layers.";
			case SELECT_NONE -> "Clears the selection.";
		};
	}

	private static String layerActionTooltip(LayerAction action) {
		return switch (action) {
			case RENAME -> "Renames this layer. Double-clicking its name does the same thing.";
			case MERGE_SELECTED -> "Folds the selected layers into the lowest-numbered one, which "
				+ "keeps its name and instrument -- so merging across two instruments gives every "
				+ "note the surviving one. Ctrl+E does the same thing.";
			case INCLUDE_SELECTED -> "Fills in the build dot on the selected layers, adding them to "
				+ "the sequence.";
			case SET_INCLUDED_TO_SELECTION -> "Makes the selected layers the only included ones, "
				+ "clearing the rest.";
			case SELECT_ALL -> "Selects every layer.";
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
			case CHORD_THIN_TARGET -> "How far Select > Overloaded chords cuts a chord back. Thirty "
				+ "is the most a build can place at one instant; under that leaves the paste room.";
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
		if (action == ToolbarAction.TOGGLE_DEDUPE) {
			return action.label + ": " + (config.dedupeIdenticalNotes() ? "On" : "Off");
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
			case CHORD_THIN_TARGET -> config.chordThinTarget() + " per chord";
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
			case CHORD_THIN_TARGET -> config.setChordThinTarget(config.chordThinTarget() + direction);
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

	/** The palette's box, shared by drawing, hit testing and "is the cursor over it". */
	private NoteRect instrumentMenuRect() {
		if (instrumentMenuLayer < 0 || instrumentMenuLayer >= project().layers().size()) {
			return null;
		}
		int rows = (PreviewInstrument.VALUES.size() + INSTRUMENT_COLUMNS - 1) / INSTRUMENT_COLUMNS;
		int menuWidth = INSTRUMENT_COLUMNS * INSTRUMENT_CELL + 6;
		int menuHeight = rows * INSTRUMENT_CELL + 6;
		int top = Math.max(TOOLBAR_HEIGHT + 4, Math.min(height - menuHeight - 24,
			layerY(instrumentMenuLayer) + LAYER_ROW_HEIGHT));
		return new NoteRect(8, top, 8 + menuWidth, top + menuHeight);
	}

	private void extractInstrumentMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		NoteRect menu = instrumentMenuRect();
		if (menu == null) {
			return;
		}
		graphics.fill(menu.left(), menu.top(), menu.right(), menu.bottom(), 0xF0101115);
		graphics.fill(menu.left(), menu.top(), menu.right(), menu.top() + 1, 0xFFAAAAAA);
		PreviewInstrument selected = PreviewInstrument.byId(project().layers().get(instrumentMenuLayer).instrument());
		for (int index = 0; index < PreviewInstrument.VALUES.size(); index++) {
			PreviewInstrument value = PreviewInstrument.VALUES.get(index);
			int cellX = menu.left() + 3 + index % INSTRUMENT_COLUMNS * INSTRUMENT_CELL;
			int cellY = menu.top() + 3 + index / INSTRUMENT_COLUMNS * INSTRUMENT_CELL;
			boolean hovered = mouseX >= cellX && mouseX < cellX + INSTRUMENT_CELL
				&& mouseY >= cellY && mouseY < cellY + INSTRUMENT_CELL;
			graphics.fill(cellX, cellY, cellX + INSTRUMENT_CELL - 2, cellY + INSTRUMENT_CELL - 2,
				value.equals(selected) ? 0xFF356070 : hovered ? 0xFF44484F : 0xFF25282D);
			graphics.item(new ItemStack(value.icon()), cellX + 5, cellY + 5);
			if (hovered) {
				graphics.setTooltipForNextFrame(Component.literal(value.name()), mouseX, mouseY);
			}
		}
	}

	/** The composition being edited, so which one it is never has to be remembered. */
	/**
	 * The song's name, in the gap between the menus and the controls.
	 *
	 * <p>Right-aligned against the controls rather than left-aligned after the menus, so that a
	 * long name runs back into the empty middle of the bar instead of into the Play button. The
	 * controls themselves are pinned to the window edge, so nothing here can move them -- renaming
	 * a song or picking up an unsaved dot leaves them exactly where your hand expects.</p>
	 */
	private void extractCompositionName(GuiGraphicsExtractor graphics) {
		int right = toolbarControlsLeft - 10;
		int left = menuTitles().getLast().right() + 12;
		if (right - left < 48) {
			return;
		}
		// A bullet rather than the usual asterisk, because the composer already spends asterisks on
		// nothing and dots on the build flag -- and this one has to read at a glance from across the
		// toolbar, which is where you look before deciding whether it is safe to leave.
		String name = project().name() + (unsaved() ? "  • unsaved" : "");
		String shown = font.width(name) <= right - left
			? name
			: font.plainSubstrByWidth(name, right - left - 8) + "...";
		graphics.text(font, shown, right - font.width(shown), MENU_BAR_TOP + 4,
			unsaved() ? 0xFFFFC864 : 0xFFD6D8DD, false);
	}

	private void extractPanels(GuiGraphicsExtractor graphics) {
		graphics.fill(0, TOOLBAR_HEIGHT, layerPanelWidth(), height, 0xB8101115);
		graphics.fill(layerPanelWidth(), TOOLBAR_HEIGHT, width, height, 0x99101115);
		extractSplitter(graphics);
		LayerRowLayout row = layerRowLayout();
		// The header doubles as the fold: an arrow pointing the way the panel would go.
		graphics.text(font, Component.literal(config.layerPanelCollapsed() ? ">" : "<"),
			row.inset(), TOOLBAR_HEIGHT + 4, 0xFF8A9098, false);
		if (row.name()) {
			graphics.text(font, "Layers", row.inset() + 10, TOOLBAR_HEIGHT + 4, 0xFF8A9098, false);
		}
		graphics.enableScissor(0, LAYER_LIST_TOP - 2, layerPanelWidth(), layerListBottom());
		for (int index = 0; index < project().layers().size(); index++) {
			int y = layerY(index);
			int rowHeight = LAYER_ROW_HEIGHT;
			if (y + rowHeight < LAYER_LIST_TOP - 2 || y > layerListBottom()) {
				continue;
			}
			int color = layerColor(index);
			boolean activeLayer = index == project().activeLayerIndex();
			boolean selected = selectedLayers.contains(index);
			int left = row.inset();
			int right = layerPanelWidth() - row.inset();
			graphics.fill(left, y - 2, right, y + rowHeight - 2,
				activeLayer ? 0x88425A6B : selected ? 0x88344657 : 0x44252A31);
			if (selected) {
				// Unmistakable outline: a tinted background alone reads as noise on a dark panel.
				int edge = activeLayer ? 0xFF8FD3FF : 0xFF5C93B8;
				graphics.fill(left, y - 2, right, y - 1, edge);
				graphics.fill(left, y + rowHeight - 3, right, y + rowHeight - 2, edge);
				graphics.fill(right - 1, y - 2, right, y + rowHeight - 2, edge);
			}
			graphics.fill(left, y - 2, right, y - 1, activeLayer ? color : 0x66383D44);
			graphics.fill(left, y + rowHeight - 3, right, y + rowHeight - 2,
				activeLayer ? color : 0x88383D44);
			// The colour stripe is how a layer is recognised once its name is gone, so it stays at
			// every width -- narrowed to two pixels rather than dropped.
			graphics.fill(left, y - 2, left + (row.chip() ? 4 : 2), y + rowHeight - 2, color);
			if (activeLayer) {
				graphics.fill(left + 4, y, right - 2, y + rowHeight - 4, 0x553D444D);
			}
			Layer layer = project().layers().get(index);
			LayerState state = layerState(index);
			if (row.chip()) {
				// Drawn as a bordered chip with a letter in it. Bare symbols read as decoration on a
				// row that is mostly decoration already, and this one is the layer's only switch.
				int chipLeft = LAYER_STATE_X - 2;
				graphics.fill(chipLeft, y + 2, chipLeft + LAYER_CHIP, y + 2 + LAYER_CHIP, 0x66FFFFFF);
				graphics.fill(chipLeft + 1, y + 3, chipLeft + LAYER_CHIP - 1, y + 1 + LAYER_CHIP,
					state.chip);
				graphics.text(font, Component.literal(state.letter),
					chipLeft + (LAYER_CHIP - font.width(state.letter)) / 2, y + 4, state.color, false);
			}
			// The instrument as the block it sounds like, which is the same picture the palette uses
			// and the only label short enough to leave the name any room.
			graphics.item(new ItemStack(PreviewInstrument.byId(layer.instrument()).icon()),
				row.instrumentX(), y - 1);
			if (row.name()) {
				String mark = selected ? "✓ " : "";
				String label = mark + layer.name() + "  (" + layer.notes().size() + ")";
				smallText(graphics, smallFit(label, row.nameRight() - row.nameLeft()),
					row.nameLeft(), y + 5,
					state == LayerState.HIDDEN ? 0xFF80858C
						: activeLayer ? 0xFFFFFFFF : selected ? 0xFFE8F4FF : 0xFFD6D8DD);
			}
			if (row.dot()) {
				// Filled means this layer goes into the build sequence. Deliberately not the same
				// control as the state icon beside it: what you hear while working and what gets
				// built are different questions, and one switch for both is how a layer goes missing.
				graphics.text(font, Component.literal(layer.buildEnabled() ? "●" : "○"),
					row.dotX(), y + 4, layer.buildEnabled() ? 0xFF5AD46A : 0xFF6A7078, false);
			}
			// The row number is for pointing at a layer out loud, nothing more, so it sits out at the
			// edge in the smallest thing that can still be read rather than in front of the name.
			String ordinal = Integer.toString(index + 1);
			smallText(graphics, ordinal, row.ordinalRight() - smallTextWidth(ordinal), y + 5,
				0xFF71767E);
		}
		extractLayerDropLine(graphics);
		graphics.disableScissor();
		extractLayerScrollbar(graphics);
		extractLayerTooltip(graphics);
	}

	/**
	 * Explains whichever icon on a row the cursor is over.
	 *
	 * <p>The row's controls are drawn rather than built out of widgets, so none of them carry a
	 * tooltip of their own. This panel also draws without mouse coordinates, so the cached position
	 * is what there is.</p>
	 */
	private void extractLayerTooltip(GuiGraphicsExtractor graphics) {
		// A row under an open menu is not what the cursor is pointing at. The panel draws first, so
		// its tooltip was the one that survived -- hovering the instrument palette explained the
		// layer behind it instead of the instrument being hovered.
		if (overOpenMenu(lastMouseX, lastMouseY)) {
			return;
		}
		int x = (int)lastMouseX;
		int y = (int)lastMouseY;
		Component text = null;
		int stateLayer = layerStateAt(lastMouseX, lastMouseY);
		int instrumentLayer = layerInstrumentAt(lastMouseX, lastMouseY);
		int dotLayer = buildDotAt(lastMouseX, lastMouseY);
		if (stateLayer >= 0) {
			text = Component.literal(layerState(stateLayer).description
				+ "\nClick steps A - M - S - H, right-click steps back.");
		} else if (instrumentLayer >= 0) {
			text = Component.literal(
				PreviewInstrument.byId(project().layers().get(instrumentLayer).instrument()).name()
					+ " - click to change the note-block instrument");
		} else if (dotLayer >= 0) {
			text = Component.literal(project().layers().get(dotLayer).buildEnabled()
				? "In the build sequence - click to leave it out"
				: "Left out of the build sequence - click to include it");
		}
		if (text != null) {
			graphics.setTooltipForNextFrame(font, font.split(text, 200), x, y);
		}
	}

	/** Whether a point lands on a menu drawn over the layer panel. */
	private boolean overOpenMenu(double x, double y) {
		NoteRect palette = instrumentMenuRect();
		if (palette != null && palette.contains(x, y)) {
			return true;
		}
		if (layerMenuOpen && x >= layerMenuX && x < layerMenuX + layerMenuWidth()
				&& y >= layerMenuY
				&& y < layerMenuY + LayerAction.values().length * CONTEXT_MENU_ROW_HEIGHT + 4) {
			return true;
		}
		if (y < TOOLBAR_HEIGHT) {
			return true;
		}
		if (toolbarMenu == ToolbarMenu.NONE) {
			return false;
		}
		if (openSubmenu != null && x >= submenuLeft && x < submenuRight
				&& y >= submenuTop && y < submenuBottom) {
			return true;
		}
		List<MenuRow> rows = menuRows(toolbarMenu);
		int menuWidth = menuWidth(rows, toolbarMenu == ToolbarMenu.IMPORT
			? IMPORT_MENU_WIDTH : TOOLBAR_MENU_WIDTH, toolbarMenuX);
		return !rows.isEmpty() && x >= toolbarMenuX && x < toolbarMenuX + menuWidth
			&& y >= MENU_PANEL_TOP && y < MENU_PANEL_TOP + rows.size() * TOOLBAR_MENU_ROW_HEIGHT + 4;
	}

	/**
	 * Draws text at three-quarter size.
	 *
	 * <p>Minecraft's font is one size, so smaller means scaling the matrix around it. Worth it in
	 * the layer panel: a converted song is dozens of layers whose names differ only in a suffix,
	 * and full-size text spent the panel's width on four of them at a time.</p>
	 */
	private void smallText(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(LAYER_TEXT_SCALE, LAYER_TEXT_SCALE);
		graphics.text(font, text, 0, 0, color, false);
		graphics.pose().popMatrix();
	}

	private int smallTextWidth(String text) {
		return Math.round(font.width(text) * LAYER_TEXT_SCALE);
	}

	/** Cuts small text down to a width in real pixels, since the font measures its own size. */
	private String smallFit(String text, int pixels) {
		int nominal = (int)(pixels / LAYER_TEXT_SCALE);
		if (font.width(text) <= nominal) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, nominal - font.width("..."))) + "...";
	}

	/**
	 * One colour per layer, grouped so layers split off the same original share a hue.
	 *
	 * <p>Converting for Minecraft turns one layer into up to five, one per octave shift it needed.
	 * Colouring by position gave those five unrelated colours, which is exactly backwards: the one
	 * thing worth seeing in a converted song is which pieces used to be one part. Family comes from
	 * the name, since that is what the split writes and what survives a save.</p>
	 */
	private int[] layerColors() {
		ComposerProject current = project();
		if (cachedColorProject == current && cachedLayerColors != null) {
			return cachedLayerColors;
		}
		List<Layer> layers = current.layers();
		Map<String, Integer> hues = new java.util.LinkedHashMap<>();
		Map<String, Integer> members = new java.util.LinkedHashMap<>();
		int[] colors = new int[layers.size()];
		for (int index = 0; index < layers.size(); index++) {
			String family = layerFamily(layers.get(index).name());
			Integer hue = hues.get(family);
			if (hue == null) {
				hue = hues.size();
				hues.put(family, hue);
			}
			int member = members.merge(family, 1, Integer::sum) - 1;
			colors[index] = shade(LAYER_COLORS[hue % LAYER_COLORS.length], member);
		}
		cachedColorProject = current;
		cachedLayerColors = colors;
		return colors;
	}

	/** One layer's colour, safe to ask for while a drag preview is standing in for the project. */
	private int layerColor(int index) {
		int[] colors = layerColors();
		return index >= 0 && index < colors.length
			? colors[index]
			: LAYER_COLORS[Math.floorMod(index, LAYER_COLORS.length)];
	}

	/** A layer's name with the suffix a Minecraft conversion added, if any, taken off. */
	private static String layerFamily(String name) {
		return name.replaceFirst("\\s*\\((in range|[+-]\\d+ oct)\\)$", "");
	}

	/**
	 * Steps a colour away from its base so members of one family stay apart.
	 *
	 * <p>Alternating darker and lighter rather than only fading: the colour is a four-pixel strip on
	 * a near-black panel, and four steps of darkening ends at something indistinguishable from the
	 * background.</p>
	 */
	private static int shade(int color, int step) {
		double[] steps = {0.0, -0.34, 0.42, -0.56, 0.68};
		double amount = steps[Math.min(Math.max(step, 0), steps.length - 1)];
		if (amount == 0.0) {
			return color;
		}
		int shaded = color & 0xFF000000;
		for (int shift = 16; shift >= 0; shift -= 8) {
			int channel = (color >> shift) & 0xFF;
			channel = amount < 0
				? (int)Math.round(channel * (1.0 + amount))
				: (int)Math.round(channel + (255 - channel) * amount);
			shaded |= Math.max(0, Math.min(255, channel)) << shift;
		}
		return shaded;
	}

	/** Where a dragged layer would land, drawn as the gap it would drop into. */
	private void extractLayerDropLine(GuiGraphicsExtractor graphics) {
		if (!layerDragActive) {
			return;
		}
		int insertion = layerDropIndex(layerDragY);
		if (project().layers().isEmpty()) {
			return;
		}
		int y = insertion >= project().layers().size()
			? layerY(project().layers().size() - 1) + LAYER_ROW_HEIGHT - 2
			: layerY(insertion) - 2;
		graphics.fill(8, y - 1, layerPanelWidth() - 8, y + 1, 0xFF8FD3FF);
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
		if (!layerRowLayout().dot()) {
			// Narrow, the track would sit on top of the row numbers, which are the last thing left.
			return;
		}
		int x = layerPanelWidth() - 6;
		graphics.fill(x, trackTop, x + 3, trackTop + trackHeight, 0x40FFFFFF);
		graphics.fill(x, thumbTop, x + 3, thumbTop + thumbHeight, 0xAAFFFFFF);
	}

	private void extractTimeRuler(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int rulerY = rollY - TIMELINE_RULER_HEIGHT;
		graphics.fill(rollX, rulerY, rollX + rollWidth, rollY, 0xCC15181D);
		graphics.fill(rollX, rollY - 1, rollX + rollWidth, rollY, 0xFF4A4F56);
		long lastTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		long measureTicks = Math.max(1L, project().ppq()) * 4L;
		long labelStep = rulerLabelStep(measureTicks);
		// Subdivisions of whatever is being labelled, not of a bar. Drawing a line per bar however
		// far out you zoom is what turned this into a picket fence with no room left to say which
		// bar any of them was.
		//
		// Still whole bars though, and a count that divides the label step: quartering a ten-bar
		// step would put lines on half-bars, which are not anywhere.
		long labelBars = labelStep / measureTicks;
		long minorStep = labelBars > 1L
			? largestProperDivisor(labelBars) * measureTicks
			: measureTicks / 4L;
		if (minorStep > 0L && minorStep / ticksPerPixel >= MIN_GRID_PIXEL_SPACING * 2) {
			for (long tick = Math.max(0L, horizontalScroll / minorStep * minorStep);
					tick <= lastTick + minorStep; tick += minorStep) {
				int x = tickX(tick);
				if (x >= rollX && x <= rollX + rollWidth && tick % labelStep != 0L) {
					graphics.fill(x, rollY - 6, x + 1, rollY, 0xFF686D73);
				}
			}
		}
		for (long tick = Math.max(0L, horizontalScroll / labelStep * labelStep);
				tick <= lastTick + labelStep; tick += labelStep) {
			int x = tickX(tick);
			if (x < rollX || x > rollX + rollWidth) {
				continue;
			}
			graphics.fill(x, rulerY + 1, x + 1, rollY, 0xFF9A9A9A);
			// Bar number over clock time. The bar is where you are in the music and the clock is
			// how long you will be standing there, and the second one moves when the speed does.
			graphics.text(font, Long.toString(tick / measureTicks + 1L), x + 3, rulerY + 2,
				0xFFBFC4CA, false);
			smallText(graphics, clockLabel(secondsAt(tick)), x + 3, rulerY + 13, 0xFF767C85);
		}
		int endX = endMarkerX();
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
			// Where the playhead is, to a tenth. Drawn last so it wins wherever it lands on top of a
			// bar's own label -- while something is playing this is the number being read.
			String at = preciseClockLabel(secondsAt(markerTick));
			int labelX = Math.min(rollX + rollWidth - smallTextWidth(at) - 2, markerX + 5);
			smallText(graphics, at, Math.max(rollX + 2, labelX), rulerY + 13, 0xFFFF8888);
		}
		if (mouseX >= rollX && mouseX < rollX + rollWidth && mouseY >= rulerY && mouseY < rollY) {
			graphics.setTooltipForNextFrame(Component.literal(overEndMarker(mouseX, mouseY)
				? "Drag to set where the song ends"
				: "Drag to set playback start"), mouseX, mouseY);
		}
	}

	/** The biggest step that still divides {@code value} evenly, or 1 when it is prime. */
	private static long largestProperDivisor(long value) {
		for (long divisor = 2L; divisor * divisor <= value; divisor++) {
			if (value % divisor == 0L) {
				return value / divisor;
			}
		}
		return 1L;
	}

	/**
	 * How many ticks apart the ruler's labelled lines should be.
	 *
	 * <p>Always a whole number of bars, so the numbers stay musical, and always far enough apart
	 * that a bar number and a clock time fit between them.</p>
	 */
	private long rulerLabelStep(long measureTicks) {
		for (int bars : RULER_BAR_STEPS) {
			if (bars * measureTicks / ticksPerPixel >= RULER_LABEL_SPACING) {
				return bars * measureTicks;
			}
		}
		return RULER_BAR_STEPS[RULER_BAR_STEPS.length - 1] * measureTicks;
	}

	/**
	 * Where a tick falls in real seconds, at the speed the song is set to.
	 *
	 * <p>Follows the speed slider, because the slider is what decides how fast the song is actually
	 * played and built. A clock that ignored it would be describing a performance nobody is giving.
	 * Same arithmetic as the length the songs screen reports.</p>
	 */
	private double secondsAt(long tick) {
		return tick * project().tempoMicrosPerQuarter()
			/ (project().ppq() * 1_000_000.0 * timescaleFactor());
	}

	private static String clockLabel(double seconds) {
		int whole = (int)Math.floor(Math.max(0.0, seconds));
		return String.format(java.util.Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
	}

	private static String preciseClockLabel(double seconds) {
		double clamped = Math.max(0.0, seconds);
		return String.format(java.util.Locale.ROOT, "%d:%04.1f",
			(int)(clamped / 60.0), clamped % 60.0);
	}

	private long extractPianoRoll(GuiGraphicsExtractor graphics, int mouseX, int mouseY, long mark) {
		int pianoX = layerPanelWidth();
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

		mark = phase(PHASE_KEYS, mark);
		extractTimeGrid(graphics);
		mark = phase(PHASE_GRID, mark);
		extractNotes(graphics, mouseX, mouseY);
		mark = phase(PHASE_NOTES, mark);
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
		return phase(PHASE_PLAYHEAD, mark);
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
		for (long overloaded : overloadedTicks()) {
			int x = tickX(overloaded);
			if (x >= rollX && x <= rollX + rollWidth) {
				graphics.fill(x - 1, rollY, x + 2, rollY + rollHeight, 0x66FF3333);
			}
		}
	}

	/**
	 * The ticks carrying more notes than a build can place, as a sorted array.
	 *
	 * <p>Sifted once per analysis rather than once per frame. The grid used to walk the whole chord
	 * map looking for them, which boxes a Long for every distinct tick in the song -- fourteen
	 * hundred of them on a dense import, to find the usual answer of none.</p>
	 */
	private long[] overloadedTicks() {
		SongAnalysis stats = projectStats();
		if (cachedOverloadedStats != stats) {
			cachedOverloadedStats = stats;
			cachedOverloadedTicks = stats.chordCounts().entrySet().stream()
				.filter(entry -> entry.getValue() > SongAnalysis.MAX_SIMULTANEOUS_NOTES)
				.mapToLong(Map.Entry::getKey)
				.sorted()
				.toArray();
		}
		return cachedOverloadedTicks;
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

	/**
	 * Works out where every visible note goes and hands the lot to {@link NoteCellGrid} to draw.
	 *
	 * <p>Nothing here draws directly. Zoomed all the way out a dense composition asks for thousands
	 * of quads on a few hundred pixel columns, most of them behind another note or touching one of
	 * the same colour, and the grid is what turns those into the few hundred that are actually
	 * visible. The loop's own job is to be cheap: no rectangle object per note, no boxed lookup
	 * where the answer is known to be no, and pitch culled on an int comparison.</p>
	 */
	private void extractNotes(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ComposerProject shown = displayProject();
		SongAnalysis stats = projectStats();
		Set<Long> offGrid = stats.offGrid();
		Set<Long> crowded = stats.crowded();
		// Hoisted out of the per-note loop: a Set<Long> lookup boxes the tick, and on a clean song
		// all three of these are empty, so nine thousand boxes a frame buy three known answers.
		boolean anyCrowded = !crowded.isEmpty();
		boolean anyOffGrid = !offGrid.isEmpty();
		boolean anySelected = !selectedNotes.isEmpty();
		NoteEvent hoveredCandidate = null;
		int hoveredCandidateLayer = -1;
		long firstVisibleTick = Math.max(0L, horizontalScroll - stats.maximumNoteDuration());
		long lastVisibleTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		int rollRight = rollX + rollWidth;
		int rollBottom = rollY + rollHeight;
		// Pitch rows the roll can show, so a note above or below the window is rejected on an int
		// comparison instead of on a rectangle built for it.
		int lowestVisibleMidi = topMidiNote - rollHeight / rowHeight - 1;
		notesConsidered = 0;
		notesDrawn = 0;
		long layoutStart = profiling ? System.nanoTime() : 0L;
		cells.begin(rollX, rollWidth, NOTE_TRIGGER_WIDTH, rowHeight - 2);
		for (int layerIndex : noteDrawOrder(shown)) {
			Layer layer = shown.layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			boolean active = layerIndex == shown.activeLayerIndex();
			boolean highlighted = active || selectedLayers.contains(layerIndex);
			int liveColor = highlighted ? layerColor(layerIndex) : 0xFF777A80;
			int deadColor = highlighted ? 0xFFFF6B6B : 0xFF755050;
			List<NoteEvent> notes = layer.notes();
			for (int noteIndex = lowerBoundStart(notes, firstVisibleTick);
					noteIndex < notes.size(); noteIndex++) {
				NoteEvent note = notes.get(noteIndex);
				if (note.startTick() > lastVisibleTick) {
					break;
				}
				notesConsidered++;
				int midi = note.midiNote();
				if (midi > topMidiNote || midi < lowestVisibleMidi) {
					continue;
				}
				int left = tickX(note.startTick());
				int right = left + NOTE_TRIGGER_WIDTH;
				if (right <= rollX || left >= rollRight) {
					continue;
				}
				int top = rollY + (topMidiNote - midi) * rowHeight + 1;
				int bottom = top + rowHeight - 2;
				if (bottom <= rollY || top >= rollBottom) {
					continue;
				}
				notesDrawn++;
				int flags = highlighted ? NoteCellGrid.HIGHLIGHTED : 0;
				if (anySelected && selectedNotes.contains(note.id())) {
					flags |= NoteCellGrid.SELECTED;
				}
				if (anyCrowded && crowded.contains(note.startTick())) {
					flags |= NoteCellGrid.CROWDED;
				} else if (anyOffGrid && offGrid.contains(note.startTick())) {
					flags |= NoteCellGrid.OFF_GRID;
				}
				cells.add(left, top, note.isBuildable() ? liveColor : deadColor, flags, midi);
				if (mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom) {
					// Topmost wins: draw order runs back to front, so a later hit overwrites.
					hoveredCandidate = note;
					hoveredCandidateLayer = layerIndex;
				}
			}
		}
		long drawStart = profiling ? System.nanoTime() : 0L;
		quads.graphics = graphics;
		noteQuads = cells.draw(quads);
		quads.graphics = null;
		if (profiling) {
			long now = System.nanoTime();
			notesDrawNanos += now - drawStart;
			notesLayoutNanos += drawStart - layoutStart;
		}
		int endX = endMarkerX();
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
	void showResult(Component message) {
		toast = message;
		toastShownAt = Util.getMillis();
	}

	/**
	 * Shows the last result, and stops counting down while the cursor is on it.
	 *
	 * <p>These carry the only copy of things worth reading twice -- what an import left out, what a
	 * thinning pass is about to delete -- and four and a half seconds is not long enough to read a
	 * wrapped paragraph you were not expecting. Pointing at one holds it, and moving off starts the
	 * time over rather than resuming with whatever was left, because a message that vanishes the
	 * instant you stop reading it is the problem this is fixing.</p>
	 */
	private void extractToast(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (toast == null) {
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
		int left = x - 6;
		int top = y - 5;
		int right = x + textWidth + 6;
		int bottom = y + height + 4;
		boolean held = mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
		if (held) {
			toastShownAt = Util.getMillis();
		} else if (Util.getMillis() - toastShownAt > TOAST_MILLIS) {
			toast = null;
			return;
		}
		graphics.fill(left, top, right, bottom, held ? 0xF8232A33 : 0xF01A1F26);
		int accent = held ? 0xFFCDE9FF : 0xFF8FD3FF;
		graphics.fill(left, top, right, top + 1, accent);
		if (held) {
			// The rest of the frame, so a held message reads as something being kept rather than
			// something that has not gone yet. Same accent as the strip it already had.
			graphics.fill(left, bottom - 1, right, bottom, accent);
			graphics.fill(left, top, left + 1, bottom, accent);
			graphics.fill(right - 1, top, right, bottom, accent);
		}
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
		// The rule the peak was counted under, beside the number. Whether two layers playing the
		// same sound at the same instant count once is a setting that lives in another screen
		// entirely, and it silently re-judges every song -- so the number says which rule made it.
		segments.add("peak " + peakChord + "/" + SongAnalysis.MAX_SIMULTANEOUS_NOTES
			+ (config.dedupeIdenticalNotes() ? " merged" : " unmerged")
			+ (overloaded > 0 ? " (" + overloaded + " over)" : ""));
		// Leads with the number that will be standing in the world. "6354 notes (788 deduped)" was
		// arithmetically fine and still misread -- a count in brackets after a count reads as the
		// remainder, not as the difference, and nothing on the line was the 5566 that got placed.
		segments.add((stats.buildNotes() == stats.totalNotes()
				? stats.totalNotes() + " notes"
				: stats.buildNotes() + " of " + stats.totalNotes() + " notes build"
					+ (stats.duplicateNotes() > 0 ? " (" + stats.duplicateNotes() + " deduped)" : ""))
			+ " · " + project().layers().size() + " layers");
		int included = (int)project().layers().stream()
			.filter(Layer::buildEnabled)
			.count();
		if (included == 0) {
			segments.add("nothing included");
		} else {
			// Counted off the sequence rather than off the composition, so it agrees with what the
			// paste would place -- including which notes deduplication left out of it.
			SongBuilder.BlockCounts blocks = blockCounts();
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

	/**
	 * How many blocks the sequence would place, cached on the sequence it was counted from.
	 *
	 * <p>Counting re-parses every track's text, allocates an event per note and sorts the lot --
	 * eight to ten milliseconds on a dense song, which the status line was paying <em>every
	 * frame</em> for a number that only moves when the composition does. Same identity trick as
	 * {@link #projectStats()}: {@code tracks()} hands back the same list until the project changes,
	 * so a hit is a reference comparison.</p>
	 */
	private SongBuilder.BlockCounts blockCounts() {
		List<FastNoteblocksConfig.SequenceTrack> tracks = config.tracks();
		if (cachedBlockTracks != tracks || cachedBlockCounts == null) {
			cachedBlockTracks = tracks;
			cachedBlockCounts = SongBuilder.blockCounts(tracks);
		}
		return cachedBlockCounts;
	}

	private SongAnalysis projectStats() {
		ComposerProject current = project();
		// The speed is part of the project, so identity covers everything the composition decides.
		// Deduplication is a setting rather than part of the song, so it has to be checked too.
		if (cachedStatsProject == current && cachedStats != null
				&& cachedStatsDedupe == config.dedupeIdenticalNotes()) {
			return cachedStats;
		}
		cachedStatsProject = current;
		cachedStatsDedupe = config.dedupeIdenticalNotes();
		cachedStats = SongAnalysis.of(current, cachedStatsDedupe);
		return cachedStats;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		ToolbarMenu title = menuTitleAt(event.x(), event.y());
		if (title != null) {
			for (MenuTitle bar : menuTitles()) {
				if (bar.menu() == title) {
					toggleToolbarMenu(title, bar.left());
					break;
				}
			}
			return true;
		}
		if (event.button() == 0 && overSplitter(event.x(), event.y())) {
			draggingSplitter = true;
			return true;
		}
		if (event.button() == 0 && overLayerPanelHeader(event.x(), event.y())) {
			config.setLayerPanelCollapsed(!config.layerPanelCollapsed());
			FastNoteblocksConfig.save();
			resizeLayerPanel();
			return true;
		}
		if (toolbarMenu != ToolbarMenu.NONE) {
			if (handleToolbarMenuClick(event.x(), event.y(), event.button())) {
				return true;
			}
			toolbarMenu = ToolbarMenu.NONE;
			openSubmenu = null;
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
		NoteRect palette = instrumentMenuRect();
		if (palette != null) {
			if (event.button() == 0 && handleInstrumentMenuClick(event.x(), event.y())) {
				return true;
			}
			if (palette.contains(event.x(), event.y())) {
				return true;
			}
			// Anywhere else puts it away, including the icon that opened it -- which is consumed so
			// the click does not fall through and open it straight back up.
			boolean onOpener = layerInstrumentAt(event.x(), event.y()) == instrumentMenuLayer;
			instrumentMenuLayer = -1;
			if (onOpener) {
				return true;
			}
		}
		if (event.button() == 1) {
			int stateLayer = layerStateAt(event.x(), event.y());
			if (stateLayer >= 0) {
				startPainting(LayerPaint.STATE, stateLayer, cycleLayerState(stateLayer, -1), false);
				return true;
			}
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
			if (layerIndex >= 0 && !controlDown() && !shiftDown()) {
				beginLayerRename(layerIndex);
				return true;
			}
		}
		if (layerNameBox != null && !layerNameBox.isMouseOver(event.x(), event.y())) {
			commitLayerRename();
		}
		if (event.button() == 0) {
			int dotLayer = buildDotAt(event.x(), event.y());
			if (dotLayer >= 0) {
				boolean next = !project().layers().get(dotLayer).buildEnabled();
				setLayerBuildEnabled(layersToEdit(dotLayer), next);
				showResult(Component.literal(sequenceSummary()));
				startPainting(LayerPaint.BUILD_DOT, dotLayer, LayerState.ACTIVE, next);
				return true;
			}
			int stateLayer = layerStateAt(event.x(), event.y());
			if (stateLayer >= 0) {
				startPainting(LayerPaint.STATE, stateLayer, cycleLayerState(stateLayer, 1), false);
				return true;
			}
			int instrumentLayer = layerInstrumentAt(event.x(), event.y());
			if (instrumentLayer >= 0) {
				instrumentMenuLayer = instrumentMenuLayer == instrumentLayer ? -1 : instrumentLayer;
				return true;
			}
		}
		if (event.button() == 0) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0) {
				selectLayer(layerIndex, controlDown(), shiftDown());
				selectedNotes.clear();
				// Armed, not started. A press on a header is nearly always a plain selection, so the
				// reorder only takes over once the cursor has actually left the row it started on.
				layerDragIndex = layerIndex;
				layerDragStartY = event.y();
				layerDragY = event.y();
				layerDragActive = false;
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
					layersChanged();
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
				layersChanged();
			} else if (hit.layerIndex() != project().activeLayerIndex()) {
				apply(project().withActiveLayer(hit.layerIndex()));
				layersChanged();
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
		NoteRect menu = instrumentMenuRect();
		if (menu == null) {
			return false;
		}
		int column = (int)(mouseX - menu.left() - 3) / INSTRUMENT_CELL;
		int row = (int)(mouseY - menu.top() - 3) / INSTRUMENT_CELL;
		if (mouseX < menu.left() + 3 || mouseY < menu.top() + 3
				|| column < 0 || column >= INSTRUMENT_COLUMNS || row < 0) {
			return false;
		}
		int index = row * INSTRUMENT_COLUMNS + column;
		if (index < 0 || index >= PreviewInstrument.VALUES.size()) {
			return false;
		}
		PreviewInstrument value = PreviewInstrument.VALUES.get(index);
		value.play(12);
		// Picking an instrument says nothing about whether the layer is heard. It used to, because
		// silence was one of the instruments; the state letter answers that now.
		// Left open on purpose: every pick plays its sound, so the palette is how you audition one
		// instrument against another. Clicking away is what puts it down.
		updateLayers(instrumentMenuLayer, target -> target.withInstrument(value.id()));
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
		// With one menu already open, sliding along the bar opens the next, which is what a menu
		// bar does everywhere else and what makes browsing five of them one gesture.
		ToolbarMenu title = menuTitleAt(x, y);
		if (toolbarMenu != ToolbarMenu.NONE && title != null && title != toolbarMenu) {
			for (MenuTitle bar : menuTitles()) {
				if (bar.menu() == title) {
					toolbarMenu = title;
					toolbarMenuX = bar.left();
					openSubmenu = null;
					break;
				}
			}
		}
		super.mouseMoved(x, y);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		lastMouseX = event.x();
		lastMouseY = event.y();
		if (draggingSplitter) {
			// Past the fold point it snaps shut rather than shrinking to a width no row fits in.
			// The width it had is left in the config, so unfolding puts it back where it was.
			if (event.x() < SPLITTER_COLLAPSE_AT) {
				config.setLayerPanelCollapsed(true);
			} else {
				config.setLayerPanelCollapsed(false);
				config.setLayerPanelWidth((int)Math.round(event.x()));
			}
			resizeLayerPanel();
			return true;
		}
		if (painting != LayerPaint.NONE) {
			// Only the row matters once the gesture is under way. Asking for the cursor to stay
			// inside a twelve-pixel column while dragging down thirty layers is not a gesture.
			int row = layerRowAtY(event.y());
			if (row >= 0 && paintedRows.add(row)) {
				if (painting == LayerPaint.BUILD_DOT) {
					setLayerBuildEnabled(List.of(row), paintBuildEnabled, true);
				} else {
					setLayerState(List.of(row), paintState, true);
				}
			}
			return true;
		}
		if (layerDragIndex >= 0) {
			layerDragY = event.y();
			if (!layerDragActive && Math.abs(layerDragY - layerDragStartY) > 4) {
				layerDragActive = true;
				cancelLayerRename();
				instrumentMenuLayer = -1;
			}
			return true;
		}
		if (draggingEndMarker) {
			setEndTick(snapTick(endMarkerTick(event.x())));
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
		if (draggingSplitter) {
			draggingSplitter = false;
			FastNoteblocksConfig.save();
			return true;
		}
		if (painting != LayerPaint.NONE) {
			// The sequence summary is only news when the build dots moved; a run of mutes has not
			// changed what would be built by a single block.
			boolean report = painting == LayerPaint.BUILD_DOT && paintedRows.size() > 1;
			painting = LayerPaint.NONE;
			paintedRows.clear();
			if (report) {
				showResult(Component.literal(sequenceSummary()));
				return true;
			}
		}
		if (layerDragIndex >= 0) {
			int from = layerDragIndex;
			boolean reordering = layerDragActive;
			layerDragIndex = -1;
			layerDragActive = false;
			if (reordering) {
				dropLayer(from, layerDropIndex(event.y()));
				return true;
			}
		}
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
		if (mouseX < layerPanelWidth() && mouseY >= LAYER_LIST_TOP - 2 && mouseY <= layerListBottom()) {
			scrollLayers(scrollY > 0 ? -LAYER_ROW_HEIGHT : LAYER_ROW_HEIGHT);
			return true;
		}
		if (mouseX >= layerPanelWidth() && mouseX < rollX
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
		if (shiftDown()) {
			// Fast scroll: a quarter of whatever is on screen per notch, rather than a fixed number
			// of beats. A beat a notch is fine when the roll holds a few bars and useless when it
			// holds four hundred -- and the whole reason to reach for this is that you are zoomed
			// out. Floored at a bar so it can never end up slower than the plain scroll it modifies.
			long span = Math.max(project().ppq() * 4L, Math.round(rollWidth * ticksPerPixel / 4.0));
			horizontalScroll = Math.max(0L, horizontalScroll - Math.round(scrollY * span));
			return true;
		}
		horizontalScroll = Math.max(0L, horizontalScroll - Math.round(scrollY * project().ppq()));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (toolbarMenu != ToolbarMenu.NONE && event.isEscape()) {
			toolbarMenu = ToolbarMenu.NONE;
			openSubmenu = null;
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
		if (event.key() == GLFW.GLFW_KEY_F9 && layerNameBox == null) {
			profiling = !profiling;
			java.util.Arrays.fill(phaseNanos, 0L);
			profileFrameNanos = 0L;
			notesLayoutNanos = 0L;
			notesDrawNanos = 0L;
			profileFrames = 0;
			profileWindowSince = 0L;
			profileSummary = "";
			showResult(Component.literal(profiling
				? "Frame profiler on - timings go to the log every second"
				: "Frame profiler off"));
			return true;
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
					openSongs();
					return true;
				}
				case GLFW.GLFW_KEY_E -> {
					mergeSelectedLayers();
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
	public void removed() {
		setResizeCursor(false);
		super.removed();
	}

	@Override
	public void onClose() {
		withUnsavedChangesChecked(() -> {
			stopPlayback();
			syncProject();
			onReturn.run();
			minecraft.gui.setScreen(parent);
		});
	}

	private void closeToGame() {
		withUnsavedChangesChecked(() -> {
			stopPlayback();
			syncProject();
			onReturn.run();
			minecraft.gui.setScreen(null);
		});
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
		// Collapsed only when the build would collapse it. Preview used to do this unconditionally,
		// which was fine while it was the only behaviour -- but with the setting off the point is to
		// hear a doubled note as louder, and a preview that quietly played it once would be
		// describing a different machine from the one about to be pasted.
		if (!config.dedupeIdenticalNotes()) {
			playbackEvents = List.copyOf(events);
			playbackEventIndex = 0;
			return;
		}
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
		syncProject();
		layersChanged();
		rebuildMoveLayerButtons();
		updateButtonStates();
	}

	private void apply(ComposerProject project) {
		anchorPlayhead();
		history.apply(project);
		afterStateChange();
	}

	/**
	 * Pins the playhead to where it has actually reached, before anything moves under it.
	 *
	 * <p>Playback position is derived: start tick plus however long ago the clock started. Every
	 * edit restarted that clock without moving the anchor, which threw the playhead back to
	 * wherever playback last began -- clicking a layer was enough to do it. Anchoring has to happen
	 * before the change lands, because an edit that alters the tempo would otherwise convert the
	 * whole elapsed stretch at the new rate.</p>
	 */
	private void anchorPlayhead() {
		if (playing) {
			playbackStartTick = Math.max(0L, Math.min(project().endTick(), playbackTick()));
			playbackStartedAt = Util.getMillis();
		}
	}

	private void afterStateChange() {
		playbackStartTick = Math.min(playbackStartTick, project().endTick());
		if (playing) {
			resetPlaybackSchedule();
		}
		syncProject();
		updateButtonStates();
	}

	/**
	 * Writes the composition to its own file and says so.
	 *
	 * <p>The only thing that writes a song, along with Save as. Editing used to write on every
	 * change, which meant there was no such thing as an edit you could walk away from.</p>
	 */
	private void saveComposition() {
		if (!writeProject()) {
			showResult(Component.literal("Could not write the song file.")
				.withStyle(net.minecraft.ChatFormatting.RED));
			return;
		}
		SongAnalysis stats = projectStats();
		showResult(Component.literal(String.format(java.util.Locale.ROOT,
			"Saved \"%s\" - %d notes, %d layers, %s at %s",
			project().name(), stats.totalNotes(), project().layers().size(),
			stats.lengthLabel(), FastNoteblocksConfig.delayScaleLabel(delayScaleQuarters()))));
	}

	/**
	 * Renames the composition in place.
	 *
	 * <p>The file keeps its name. Ids are only there to be unique, and renaming one would either
	 * break every reference to it or need a second file to be written and the first deleted, which
	 * is a lot of moving parts to hang off a typo correction.</p>
	 */
	private void renameComposition() {
		minecraft.gui.setScreen(new NamePromptScreen(this, "Rename composition",
			"A new name for \"" + project().name() + "\"",
			project().name(), "Rename", name -> {
				minecraft.gui.setScreen(this);
				apply(project().withName(name.trim()));
				showResult(Component.literal("Renamed to \"" + project().name() + "\"."));
			}));
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
		layersChanged();
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
				String wanted = name.trim();
				String existing = songIdNamed(wanted);
				if (existing == null) {
					saveCopyAs(FastNoteblocksConfig.songs().newId(wanted), wanted);
					return;
				}
				// A name already in the library used to be quietly numbered into "Foo (2)", which
				// is a strange answer to someone who typed the name of the song they meant.
				minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
					if (confirmed) {
						saveCopyAs(existing, wanted);
					} else {
						minecraft.gui.setScreen(this);
					}
				}, Component.literal("Replace \"" + wanted + "\"?"),
					Component.literal("A song already goes by that name. Saving over it cannot be undone."),
					Component.literal("Replace"), CommonComponents.GUI_CANCEL));
			}));
	}

	/** The id of the song going by this exact name, or null if the name is free. */
	private String songIdNamed(String name) {
		for (String id : FastNoteblocksConfig.songs().ids()) {
			ComposerProject song = FastNoteblocksConfig.songs().song(id);
			if (song != null && song.name().equalsIgnoreCase(name)) {
				return id;
			}
		}
		return null;
	}

	private void saveCopyAs(String id, String name) {
		FastNoteblocksConfig.songs().save(id, project().withName(name));
		config.setActiveSongId(id);
		FastNoteblocksConfig.save();
		minecraft.gui.setScreen(new ComposerScreen(parent, config));
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
			// A build the layout could not place cleanly still goes up if the player says so -- but
			// they are told first, because a breached footprint overwrites whatever was standing in
			// the ground the paste promised to stay out of.
			if (DenseBuildScreen.needsAsking(plan)) {
				minecraft.gui.setScreen(new DenseBuildScreen(this, plan.wrongNotes(),
					plan.breaches().size(), plan.worstBreach(), () -> {
						CommandPasteSender.start(plan.commands(), plan.faults());
						minecraft.gui.setScreen(null);
					}));
				return;
			}
			CommandPasteSender.start(plan.commands(), plan.faults());
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

	/**
	 * Hands the edit to the rest of the mod without writing anything.
	 *
	 * <p>The build sequence is a projection of the composition being edited, so it has to see every
	 * change immediately. The song file is a different question, and is answered by Save.</p>
	 */
	private void syncProject() {
		config.setComposerProject(project());
	}

	/**
	 * Whether the composition differs from the copy on disk.
	 *
	 * <p>Cached on the identity of both sides, which is sound because compositions are immutable:
	 * the comparison itself walks every note of every layer, and the toolbar asks once a frame.</p>
	 */
	private boolean unsaved() {
		ComposerProject current = project();
		if (unsavedCacheProject != current || unsavedCacheBaseline != savedProject) {
			unsavedCacheProject = current;
			unsavedCacheBaseline = savedProject;
			unsavedCacheResult = !current.equals(savedProject);
		}
		return unsavedCacheResult;
	}

	/**
	 * Asks about unsaved edits, then runs {@code leave}.
	 *
	 * <p>Every way out of the composer goes through here -- closing it, opening another song,
	 * importing over this one. Each of those replaces what is in memory, so each is a last chance
	 * to keep it.</p>
	 */
	private void withUnsavedChangesChecked(Runnable leave) {
		if (!unsaved()) {
			leave.run();
			return;
		}
		stopPlayback();
		minecraft.gui.setScreen(new UnsavedChangesScreen(this, project().name(),
			() -> {
				writeProject();
				leave.run();
			},
			() -> {
				discardEdits();
				leave.run();
			}));
	}

	/**
	 * Puts the composition back to what was last saved, or to nothing if it was never saved.
	 *
	 * <p>Has to reset this screen too, not just the config. Only updating the baseline left the
	 * editor still holding the discarded composition, so it compared unequal all over again and
	 * asked about the same edits every time -- discard, discard, discard, forever.</p>
	 */
	private void discardEdits() {
		config.discardComposerEdits();
		history.reset(config.composerProject());
		savedProject = baseline(config, history.current());
		afterStateChange();
		layersChanged();
		rebuildMoveLayerButtons();
	}

	private boolean writeProject() {
		syncProject();
		if (!config.saveComposerProject()) {
			return false;
		}
		savedProject = project();
		FastNoteblocksConfig.save();
		return true;
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
		// Left of where it used to sit, to leave the panel's right edge to the row number. The two
		// were close enough that the dot's generous hit box swallowed clicks meant for the number.
		return layerRowLayout().dotX();
	}

	private void startPainting(LayerPaint kind, int fromRow, LayerState state, boolean buildEnabled) {
		painting = kind;
		paintState = state;
		paintBuildEnabled = buildEnabled;
		paintedRows.clear();
		paintedRows.addAll(layersToEdit(fromRow));
	}

	/** Which row a point is on, whatever part of the row it lands in. */
	private int layerRowAt(double x, double y) {
		int inset = layerRowLayout().inset();
		return x < inset || x >= layerPanelWidth() - inset ? -1 : layerRowAtY(y);
	}

	/** The row at a height, for gestures that have already decided which column they are in. */
	private int layerRowAtY(double y) {
		if (y < LAYER_LIST_TOP - 2 || y > layerListBottom()) {
			return -1;
		}
		int index = (int)Math.floor((y - (LAYER_LIST_TOP - 2 - layerScroll)) / LAYER_ROW_HEIGHT);
		return index >= 0 && index < project().layers().size() && layerRowVisible(index) ? index : -1;
	}

	/** The build dot's clickable box, a little larger than the glyph so it is easy to hit. */
	private int buildDotAt(double x, double y) {
		return layerRowLayout().dot() && x >= buildDotX() - 4 && x <= buildDotX() + 10
			? layerRowAt(x, y)
			: -1;
	}

	/** The one control that decides whether a layer is soloed, heard, silent or gone. */
	private int layerStateAt(double x, double y) {
		LayerRowLayout row = layerRowLayout();
		return row.chip() && x >= LAYER_STATE_X - 2 && x < LAYER_INSTRUMENT_X
			? layerRowAt(x, y)
			: -1;
	}

	private int layerInstrumentAt(double x, double y) {
		LayerRowLayout row = layerRowLayout();
		return x >= row.instrumentX() && x < row.instrumentX() + 17 ? layerRowAt(x, y) : -1;
	}

	/**
	 * The part of a row that selects and drags it: everything the two icons do not claim.
	 *
	 * <p>Narrow, the instrument icon claims most of the row, so what is left is the row number at
	 * the far edge -- still enough to pick a layer with, which is the point of keeping the number.</p>
	 */
	private int layerHeaderAt(double x, double y) {
		LayerRowLayout row = layerRowLayout();
		int iconsFrom = row.chip() ? LAYER_STATE_X - 2 : row.instrumentX();
		boolean onIcons = x >= iconsFrom && x < row.instrumentX() + 17;
		return !onIcons && x < layerPanelWidth() - 2 ? layerRowAt(x, y) : -1;
	}

	/** The strip above the list, which folds the panel away and brings it back. */
	private boolean overLayerPanelHeader(double x, double y) {
		return x >= 0 && x < layerPanelWidth()
			&& y >= TOOLBAR_HEIGHT && y < LAYER_LIST_TOP - 2;
	}

	/** Which gap between rows a dragged layer is hovering over, counted as an insertion point. */
	private int layerDropIndex(double y) {
		int size = project().layers().size();
		for (int index = 0; index < size; index++) {
			if (y < layerY(index) + LAYER_ROW_HEIGHT / 2.0) {
				return index;
			}
		}
		return size;
	}

	private void beginLayerRename(int layerIndex) {
		cancelLayerRename();
		editingLayer = layerIndex;
		int y = layerY(layerIndex);
		layerNameBox = new EditBox(font, LAYER_NAME_X - 3, y - 2, layerNameRight() - LAYER_NAME_X + 6,
			LAYER_ROW_HEIGHT, Component.literal("Layer name"));
		layerNameBox.setMaxLength(48);
		layerNameBox.setValue(project().layers().get(layerIndex).name());
		addRenderableWidget(layerNameBox);
		setInitialFocus(layerNameBox);
	}

	private int layerY(int layerIndex) {
		return LAYER_LIST_TOP - layerScroll + layerIndex * LAYER_ROW_HEIGHT;
	}

	/** Bottom of the scrollable layer list, leaving room for the pinned "+ Layer" row and footer. */
	private int layerListBottom() {
		return height - 44;
	}

	private int layerContentHeight() {
		return project().layers().size() * LAYER_ROW_HEIGHT;
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
		}
	}

	/** True when a layer row is fully inside the scrollable viewport. */
	private boolean layerRowVisible(int layerIndex) {
		int top = layerY(layerIndex);
		return top >= LAYER_LIST_TOP - 2 && top + LAYER_ROW_HEIGHT <= layerListBottom() + 2;
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

	/**
	 * Where the end marker is drawn: the right-hand edge of a note standing on the end tick.
	 *
	 * <p>Not {@code tickX(endTick)}, which is where the tick itself falls. A note is drawn as a
	 * fixed-width trigger anchored at its start, so a note <em>on</em> the end tick occupies the
	 * seven pixels after it — and a marker at the bare tick therefore lands on that note's left
	 * edge, with the note sticking out past it. Every song whose last note sits on the end marker
	 * looked like it carried on after the end, most obviously zoomed out, where those seven pixels
	 * are thousands of ticks. Anchoring the marker to the far edge of that trigger puts it after
	 * everything that sounds, at every zoom.</p>
	 *
	 * <p>The offset is in glyph space, not tick space, so anything converting a cursor position
	 * back into a tick has to take it off again — see {@link #endMarkerTick(double)}.</p>
	 */
	private int endMarkerX() {
		return tickX(project().endTick()) + NOTE_TRIGGER_WIDTH;
	}

	/** The tick a cursor at {@code x} is pointing the end marker at, undoing the drawing offset. */
	private long endMarkerTick(double x) {
		return mouseTick(x - NOTE_TRIGGER_WIDTH);
	}

	/** The end marker's grab zone, a few pixels either side of it in the ruler. */
	private boolean overEndMarker(double x, double y) {
		return insideRuler(x, y) && Math.abs(x - endMarkerX()) <= 4.0;
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

	/** The note grid's outlet onto whichever frame is being drawn. One instance, reused. */
	private static final class GraphicsQuads implements NoteCellGrid.Quads {
		private GuiGraphicsExtractor graphics;

		@Override
		public void fill(int left, int top, int right, int bottom, int color) {
			graphics.fill(left, top, right, bottom, color);
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

	/** What a drag down the layer panel is setting on every row it crosses. */
	private enum LayerPaint {
		NONE,
		BUILD_DOT,
		STATE
	}

	private static final String SUBMENU_ARROW = "▸";
	/** Created once and kept: GLFW cursors are process-wide and there is no reason for two. */
	private static long RESIZE_CURSOR;
	/** Air around a submenu's rule, so the row after it does not sit on the line. */
	private static final int SUBMENU_DIVIDER_GAP = 5;

	/** A menu's title in the bar, and the span of it that reacts to the cursor. */
	private record MenuTitle(ToolbarMenu menu, String label, int left, int right) {
	}

	/** Where each part of a layer row goes, and whether the panel is wide enough to have it. */
	private record LayerRowLayout(int inset, boolean chip, boolean dot, boolean name,
			int instrumentX, int nameLeft, int nameRight, int dotX, int ordinalRight) {
	}

	/** One row of an open menu: a thing to do, a setting to cycle, or a submenu to open. */
	private record MenuRow(ToolbarAction action, ImportSetting setting, ToolbarSubmenu submenu) {
		static MenuRow of(ToolbarAction action) {
			return new MenuRow(action, null, null);
		}

		static MenuRow of(ImportSetting setting) {
			return new MenuRow(null, setting, null);
		}

		static MenuRow of(ToolbarSubmenu submenu) {
			return new MenuRow(null, null, submenu);
		}
	}

	/**
	 * A group of actions that differ only in their last word.
	 *
	 * <p>Rows inside carry the short label -- "1/8" rather than "Quantize to 1/8" -- because the
	 * row that opened the submenu has already said the rest of it, and repeating it is what made
	 * the flat list hard to scan in the first place.</p>
	 */
	private enum ToolbarSubmenu {
		QUANTIZE("Quantize", "Snaps note starts onto a grid, so their gaps become whole repeater "
				+ "delays instead of whatever the source file happened to hold.",
			new ToolbarAction[] {
				ToolbarAction.QUANTIZE_QUARTER, ToolbarAction.QUANTIZE_EIGHTH,
				ToolbarAction.QUANTIZE_SIXTEENTH, ToolbarAction.QUANTIZE_REPEATERS
			},
			new String[] {"1/4 note", "1/8 note", "1/16 note", "Repeater ticks"}, 3),
		END("End", "Where the song stops, which is a delay the build has to place like any other.",
			new ToolbarAction[] {ToolbarAction.SNAP_END, ToolbarAction.TRIM_END},
			new String[] {"Snap to grid", "Trim to last note"}, -1);

		private final String label;
		private final String description;
		private final ToolbarAction[] actions;
		private final String[] labels;
		/**
		 * Row to rule off above, or -1.
		 *
		 * <p>Repeater ticks is not a fourth note value and cannot be sorted among them: it is the
		 * machine's grid, and it slides as the speed slider moves -- 240 ticks at 2.00x, which is
		 * exactly a 1/8, and 120 at 1.00x, which is exactly a 1/16. Ordering it by coarseness would
		 * be right at one speed and wrong at the next, so it is set apart instead.</p>
		 */
		private final int dividerBefore;

		ToolbarSubmenu(String label, String description, ToolbarAction[] actions, String[] labels,
				int dividerBefore) {
			this.label = label;
			this.description = description;
			this.actions = actions;
			this.labels = labels;
			this.dividerBefore = dividerBefore;
		}

		String labelFor(ToolbarAction action) {
			for (int index = 0; index < actions.length; index++) {
				if (actions[index] == action) {
					return labels[index];
				}
			}
			return action.label;
		}
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
		CHORD_THIN_TARGET("Thin chords to"),
		IGNORE_PERCUSSION("Percussion"),
		MAX_TRACKS("Max tracks"),
		DEFAULT_INSTRUMENT("Instrument");

		private final String label;

		ImportSetting(String label) {
			this.label = label;
		}
	}

	private enum ToolbarAction {
		IMPORT("Import MIDI / NBS as a new song..."),
		SCAN_WORLD("Scan a build from the world as a new song..."),
		IMPORT_SCHEMATIC("Import a schematic as a new song..."),
		OPEN_SONGS("Open composition..."),
		EXPORT_NBS("Export as .nbs..."),
		COPY_AS_TEXT("Copy sequence as text"),
		SAVE_COMPOSITION("Save composition"),
		SAVE_COMPOSITION_AS("Save composition as..."),
		RENAME_COMPOSITION("Rename composition..."),
		BACK_TO_SEQUENCES("Back"),
		CLOSE_TO_GAME("Close to game"),
		UNDO("Undo"),
		REDO("Redo"),
		CONVERT("Convert for Minecraft"),
		MERGE_REPEATS("Merge repeats", true),
		QUANTIZE_QUARTER("Quantize to 1/4", true),
		QUANTIZE_EIGHTH("Quantize to 1/8", true),
		QUANTIZE_SIXTEENTH("Quantize to 1/16", true),
		QUANTIZE_REPEATERS("Quantize to repeater ticks", true),
		FIT_ALL_RANGE("Fit into range", true),
		SNAP_TEMPO("Snap tempo (whole song)"),
		INCLUDE_SELECTED("Include selected layers in sequence"),
		SET_INCLUDED_TO_SELECTION("Include only selected layers in sequence"),
		PASTE_IN_WORLD("Paste current sequence in world (requires op)..."),
		BUILD_CANCEL("Cancel paste"),
		TOGGLE_DEDUPE("Dedupe identical notes"),
		SNAP_END("Snap end to grid"),
		TRIM_END("Trim end to last note"),
		SELECT_OFF_GRID("Off grid"),
		SELECT_TOO_FREQUENT("Too frequent"),
		SELECT_OUT_OF_RANGE("Out of range"),
		SELECT_OVERLOADED_CHORDS("Overloaded chords"),
		SELECT_ALL_NOTES("Everything"),
		SELECT_NONE("Nothing");

		private static final ToolbarAction[] FILE_ACTIONS = {
			SAVE_COMPOSITION, SAVE_COMPOSITION_AS, RENAME_COMPOSITION, OPEN_SONGS, IMPORT,
			IMPORT_SCHEMATIC, SCAN_WORLD, EXPORT_NBS, COPY_AS_TEXT, BACK_TO_SEQUENCES,
			CLOSE_TO_GAME
		};
		/** Quantize slots in at index 4 and End goes on the end; see {@link #menuRows}. */
		private static final ToolbarAction[] EDIT_ACTIONS = {
			UNDO, REDO, CONVERT, MERGE_REPEATS, FIT_ALL_RANGE, SNAP_TEMPO
		};
		private static final ToolbarAction[] BUILD_ACTIONS = {
			INCLUDE_SELECTED, SET_INCLUDED_TO_SELECTION, TOGGLE_DEDUPE, PASTE_IN_WORLD, BUILD_CANCEL
		};
		private static final ToolbarAction[] SELECT_ACTIONS = {
			SELECT_OFF_GRID, SELECT_TOO_FREQUENT, SELECT_OUT_OF_RANGE, SELECT_OVERLOADED_CHORDS,
			SELECT_ALL_NOTES, SELECT_NONE
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
		RENAME("Rename layer..."),
		MERGE_SELECTED("Merge selected"),
		INCLUDE_SELECTED("Include selected layers in sequence"),
		SET_INCLUDED_TO_SELECTION("Include only selected layers in sequence"),
		SELECT_ALL("Select all layers");

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
