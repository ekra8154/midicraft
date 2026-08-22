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
	 *
	 * <p>Settings ahead of Build, because it is the one title that has to be reachable when the bar
	 * does not fit. The GUI scale is set in there, so at a scale too large for the window it is the
	 * way out of the problem -- and it was last in the row, which is the first place a window runs
	 * out of. Build is the one that can afford to go: it does nothing the composer cannot wait
	 * for.</p>
	 */
	private static final ToolbarMenu[] MENU_BAR = {
		ToolbarMenu.FILE, ToolbarMenu.EDIT, ToolbarMenu.SELECT, ToolbarMenu.SETTINGS,
		ToolbarMenu.BUILD
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
	/** The least roll worth leaving: a couple of bars at an editing zoom, and somewhere to drop a note. */
	private static final int NARROWEST_USEFUL_ROLL = 140;
	/** Tall enough for a bar number with the clock time under it. */
	private static final int TIMELINE_RULER_HEIGHT = 24;
	/**
	 * The strip of named positions above the ruler, and the colour they are drawn in.
	 *
	 * <p>Its own lane rather than a flag among the bar numbers: a marker's whole value is its name,
	 * and a name has to be readable next to a bar number without either being mistaken for the
	 * other. The lane is only there when the song has markers, so a composition with none keeps the
	 * ruler flush against the menu bar.</p>
	 */
	/**
	 * How tall a marker's tab is, at the foot of the ruler.
	 *
	 * <p>The markers had a strip of their own between the menu bar and the ruler, which cost every
	 * song eleven pixels of roll to carry something most songs have none of, and put a second
	 * timeline above the timeline. They live on the ruler now, alongside the playhead and the end
	 * marker, because they are the same kind of thing: a place in the song you have named.</p>
	 */
	private static final int MARKER_TAB_HEIGHT = 5;
	/**
	 * A colour each, so a marker is recognisable before its name is read.
	 *
	 * <p>Picked off the tick rather than off the position in the list, so adding a marker in the
	 * middle of a song does not recolour every marker after it. Mixed rather than taken modulo:
	 * ticks land on bar lines and bar lines are all multiples of the same number, so straight
	 * modulo gave every marker in a song the same colour.</p>
	 */
	private static final int[] MARKER_COLORS = {
		0xFFA294FF, 0xFF6FD3F0, 0xFF6FE0A4, 0xFFE8C05A,
		0xFFF08A8A, 0xFFE894D8, 0xFFB4E068, 0xFFF0A05A
	};
	/** How near the cursor a marker counts as the one being pointed at, in pixels. */
	private static final int MARKER_GRAB_PIXELS = 6;
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
	/** How far after the instrument icon a layer's name starts. */
	private static final int LAYER_NAME_GAP = 19;
	/** Side of the square button carrying a layer's state letter. */
	private static final int LAYER_CHIP = 12;
	/** Filled means the layer goes into the build sequence, hollow means it is left out. */
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
		boolean lit = overSplitter(lastMouseX, lastMouseY) || draggingSplitter;
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

	/**
	 * Whether the split is in reach, which it is not while something is open over it.
	 *
	 * <p>The instrument palette starts eight pixels in and runs a hundred and eighty across, so at
	 * any ordinary panel width the split runs straight down the middle of it. Nothing on screen said
	 * so -- the strip lit up, the cursor turned into the resize arrows, and a click meant to pick an
	 * instrument grabbed the panel edge instead. Roughly one column of the palette in six.</p>
	 */
	private boolean overSplitter(double x, double y) {
		int edge = layerPanelWidth();
		return y >= TOOLBAR_HEIGHT && x >= edge - SPLITTER_GRAB && x <= edge + SPLITTER_GRAB
			&& !overOpenMenu(x, y);
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
			: Math.min(config.layerPanelWidth(), widestUsefulLayerPanel());
	}

	/**
	 * The most of the window the panel may take, whatever width it has been dragged to.
	 *
	 * <p>The stored width is in scaled pixels, so what it comes to on screen depends on the GUI
	 * scale -- and the composer defaults to the game's, which on a small window or a high setting
	 * is four or six. Two hundred scaled pixels is a quarter of a wide window and getting on for
	 * half a narrow one, so opening the composer for the first time showed a list of layers with a
	 * sliver of music beside it.</p>
	 *
	 * <p>Capped on the way out rather than clamped into the config, so the width survives being
	 * looked at on a smaller window: drag the panel wide on a desktop, open the same song on a
	 * laptop, and it comes back to what you chose once there is room for it again.</p>
	 *
	 * <p>What is left is the cap, not a quarter of the window. That was the first try and it was
	 * the wrong shape: it stopped the panel eating a small window, which is what it was for, and it
	 * also stopped anyone widening the panel on a large one -- and a layer with a long name is
	 * exactly when you want to. A panel may take everything except the keyboard and a usable strip
	 * of roll, which is the real thing being protected.</p>
	 */
	private int widestUsefulLayerPanel() {
		return Math.max(ROW_NAME_AT, width - PIANO_WIDTH - NARROWEST_USEFUL_ROLL);
	}

	/**
	 * How much panel is left empty to the right of a row.
	 *
	 * <p>Wider than the gutter on the left, because it has a job the left one does not: clicking
	 * beside a row is how a layer selection is put down, and the scrollbar's track was standing in
	 * most of the space that gesture had. A card that stops short of the edge leaves somewhere to
	 * aim that is plainly not a row.</p>
	 */
	private static int rightGutter(int inset) {
		return inset + 3;
	}

	/** Widest a layer's name may draw, which is whatever the panel leaves after the note count. */
	private int layerNameRight() {
		return layerRowLayout().nameRight();
	}

	/**
	 * What fits on a layer row at the panel's current width.
	 *
	 * <p>Parts drop off as it narrows -- the name first, then the state chip -- until only the
	 * instrument and the row number are left, which is also what a folded panel
	 * shows. One description of the row shared by the drawing and by every hit test, because a
	 * panel where the chip is painted in one place and clicked in another is worse than one that
	 * never shrank.</p>
	 */
	private LayerRowLayout layerRowLayout() {
		int panel = layerPanelWidth();
		boolean chip = panel >= ROW_CHIP_AT;
		boolean name = panel >= ROW_NAME_AT;
		boolean count = panel >= ROW_COUNT_AT;
		int inset = chip ? 8 : 2;
		// The stripe is wide enough to write the row number in and no wider. A digit is four and a
		// half pixels at this size, so two of them are nine and the stripe cannot go back to the
		// four it was without putting the number somewhere else -- but the padding can go, and it
		// has. One pixel either side, which on a block of solid colour is enough to keep the digits
		// off the edge and is not enough to notice.
		int stripe = Math.max(4, smallTextWidth(Integer.toString(
			Math.max(1, project().layers().size()))) + 2);
		// Everything after the stripe is measured from it rather than from a fixed column, because
		// the stripe is as wide as the largest row number in the panel and that is not a constant.
		int stateX = inset + stripe + 2;
		int instrumentX = chip ? stateX + LAYER_CHIP + 3 : inset + stripe + 2;
		// What used to be the number's column is the note count's now. Every row reserves the width
		// of the largest count in the panel rather than its own, so the column does not jog left as
		// you scroll past a layer with four digits on it.
		int ordinalRight = panel - rightGutter(inset) - 3;
		int ordinalLeft = count
			? ordinalRight - smallTextWidth(Integer.toString(Math.max(1,
				project().layers().stream().mapToInt(layer -> layer.notes().size()).max().orElse(0))))
			: ordinalRight;
		return new LayerRowLayout(
			inset,
			stripe,
			chip,
			name,
			count,
			stateX,
			instrumentX,
			// A fixed gap after the instrument icon rather than a fixed column. Pinned to the
			// column, the name held the pixels the state chip had vacated and spent them on
			// nothing, which is most of the room a narrow panel has to give.
			instrumentX + LAYER_NAME_GAP,
			ordinalLeft - 4,
			ordinalLeft,
			ordinalRight);
	}

	/**
	 * A folded panel is the narrow end of the same layout, not a blank strip.
	 *
	 * <p>Which instrument a layer is and where it sits in the order are the two things you still
	 * want while it is out of the way -- they are how you find the layer you meant. Everything else
	 * is what folding is for getting rid of.</p>
	 */
	private static final int COLLAPSED_LAYER_PANEL_WIDTH = 34;
	/** Grab zone either side of the split, and how far left you must drag to fold it away. */
	private static final int SPLITTER_GRAB = 3;
	/**
	 * How far left you must drag to fold it away, which is also how wide folded is.
	 *
	 * <p>The same number twice on purpose: folding is the narrow end of the drag, and a fold that
	 * snapped to something narrower than you could drag to would be a second layout to get right.</p>
	 */
	private static final int SPLITTER_COLLAPSE_AT = COLLAPSED_LAYER_PANEL_WIDTH;
	/**
	 * Widths at which a row stops having room for each of its parts, narrowest last.
	 *
	 * <p>The name goes last of the three and only once there is no room to write anything in at
	 * all. It used to be dropped first, at a width where fifty pixels of it still fitted, so
	 * narrowing the panel took the names off every row at once instead of shortening them -- and a
	 * panel wide enough to read half a name showed none of it.</p>
	 */
	private static final int ROW_NAME_AT = 70;
	/**
	 * The note count goes before the name does.
	 *
	 * <p>Which layer this is beats how much is on it, and the count is a column of its own now
	 * rather than a suffix -- so at a width where one of them has to go, it is the one that goes.
	 * </p>
	 */
	private static final int ROW_COUNT_AT = 118;
	/**
	 * Below this there is not enough panel for a track to be worth the pixels it takes.
	 *
	 * <p>It used to be here because the track sat on the row numbers and drew over them. The
	 * numbers are in the colour stripe now, at the other end of the row, so the two cannot meet --
	 * what is left is only that a scrollbar on a panel this narrow is most of the panel.</p>
	 */
	private static final int ROW_SCROLLBAR_AT = 60;
	/**
	 * The state chip costs a name twenty pixels between its own width and the wider inset it brings,
	 * so it arrives later than it used to -- late enough that the name it appears beside survives.
	 */
	private static final int ROW_CHIP_AT = 100;
	private static final int LAYER_LIST_TOP = 48;
	private static final int MIN_ROW_HEIGHT = 4;
	private static final int MAX_ROW_HEIGHT = 26;
	private static final int INSTRUMENT_COLUMNS = 6;
	private static final int INSTRUMENT_CELL = 28;
	/** A strip under the palette's grid naming what a pick would land on. */
	private static final int INSTRUMENT_FOOTER = 12;
	/** The two tabs over the palette's grid: pitched instruments, or blocks that make their own noise. */
	private static final int INSTRUMENT_HEADER = 14;
	private static final int CONTEXT_MENU_WIDTH = 104;
	private static final int CONTEXT_MENU_ROW_HEIGHT = 16;
	private static final int LAYER_MENU_WIDTH = 120;
	private static final int TOOLBAR_MENU_WIDTH = 126;
	private static final int TOOLBAR_MENU_ROW_HEIGHT = 18;
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
	/**
	 * How close together the redstone grid is allowed to draw its lines before it doubles its step.
	 *
	 * <p>Tighter than the musical grid, on purpose, and it is the one grid that earns it. A repeater
	 * tick is the unit a build is made of, so it is what says whether two notes are really as close
	 * as they look -- and doubling the step at eight pixels meant that answer was gone by the time
	 * a dozen bars were on screen, which is not far out at all. At three it survives to something
	 * like thirty. Its floor on the crowding fade is higher for the same reason: a grid that is the
	 * point of looking should thin out rather than disappear.</p>
	 */
	private static final int REDSTONE_GRID_PIXEL_SPACING = 3;
	/** What one notch or one key press does to the time zoom, in and out. */
	private static final double ZOOM_IN = 0.8;
	private static final double ZOOM_OUT = 1.25;
	/** Ten repeater ticks to the second, which is the landmark a redstone grid is counted in. */
	private static final int REPEATER_TICKS_PER_SECOND = 10;
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
	/**
	 * How much room a take is given past the end of the song, in bars.
	 *
	 * <p>Enough that nobody hits the end of it by accident -- four minutes at the default tempo --
	 * and it costs nothing, because stopping puts the marker back where it was unless the take went
	 * further. Playback stops at the end marker and a new song's marker is four beats out, so
	 * without room a take would end before the count-in had finished.</p>
	 */
	private static final long RECORD_HEADROOM_BARS = 64L;

	/**
	 * How long the count-in lasts, in beats, and how many of them each number is held for.
	 *
	 * <p>Six beats over three numbers. One beat per number went by in a second and a half at an
	 * ordinary tempo -- long enough to see and not long enough to come in on. Holding each number for
	 * two beats buys the time without asking anyone to read six numbers, and the click stays on the
	 * numbers themselves: a tick on the halfway beats as well reads as a faster tempo than the one
	 * about to play.</p>
	 */
	private static final int COUNT_IN_BEATS = 6;
	private static final int BEATS_PER_COUNT = 2;
	/** Slowest a count-in beat may get, so a fast song still gets a countable one. */
	private static final long COUNT_IN_MIN_BEAT_MILLIS = 260L;
	/**
	 * How late a played note reaches the clock, in milliseconds, and so how far back to put it.
	 *
	 * <p>Sixty, and the number is derived rather than picked: flooring compensated by half a cell and
	 * felt right at the repeater snap on a default-tempo song, where half a cell is 60ms. So that is
	 * the amount of lateness being observed, and unlike floor it stays 60ms when the tempo changes.
	 * Worth re-deriving on other hardware -- it is mostly the sound engine's output latency, which is
	 * not the same on every machine -- and the way to tell is a take against a steady part: too small
	 * and everything lands late, too large and it lands early.</p>
	 */
	private static final long RECORD_LATENCY_MILLIS = 60L;

	/** How long a key stays lit after it sounds, which is about how long a note block rings for. */
	private static final long KEY_LIGHT_MILLIS = 260L;
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
	/**
	 * Snap to the game tick, which is half a repeater tick and the finest a build can ever place.
	 *
	 * <p>Only reachable by a song built on two lanes. One chain of repeaters cannot put a note on an
	 * odd game tick at all -- a repeater's shortest delay is two of them -- so until half ticking
	 * this grid would have offered placements no machine could hold. A piston takes three game ticks
	 * and a second chain tapped off one runs on the opposite half of every repeater tick, and
	 * between them the two reach every game tick there is.</p>
	 *
	 * <p>Notes drawn on it are buildable on {@code HALF_TICK_LANE} and are not buildable on one lane,
	 * which is what the status line's lane count is for.</p>
	 */
	private static final int SNAP_GAME_TICK = -2;
	private static final long PREVIEW_BACKLOG_TOLERANCE_MICROS = 100_000L;
	private static final int MIN_MIDI_NOTE = 0;
	private static final int MAX_MIDI_NOTE = 127;
	static final int[] LAYER_COLORS = {
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
	/**
	 * Which half of the screen the keyboard is pointing at.
	 *
	 * <p>Delete used to mean "the notes, or the layers if no note is selected". That is a mode you
	 * cannot see deciding an expensive thing: reach for Delete believing a passage is selected, and
	 * a layer goes instead. Five more keys had the opposite fault and were nailed to one pane
	 * whatever you were working in -- Ctrl+E always merged layers, Ctrl+D and Ctrl+C always acted on
	 * notes.</p>
	 *
	 * <p>So the keyboard follows the last pane clicked, the way it does in every file manager and
	 * every editor with two panes. It is caused by an act, it is drawn on screen, and it is already
	 * learned. Selections in both panes survive it -- clicking into the roll leaves the layers
	 * selected, it just stops the keyboard reaching them.</p>
	 */
	private Pane focusedPane = Pane.ROLL;
	private int rowHeight = ROW_HEIGHT;
	private int layerScroll;
	private boolean layerViewInitialised;
	private boolean layerMenuOpen;
	private int layerMenuX;
	private int layerMenuY;
	/**
	 * The row the layer menu was opened on, which is not always the active layer.
	 *
	 * <p>Right-clicking a row that is already part of a multi-layer selection leaves the active
	 * layer where it was, so the actions that work on one layer -- rename, duplicate -- were working
	 * on whichever row happened to be active rather than on the one under the cursor.</p>
	 */
	private int layerMenuRow = -1;
	private List<ClipboardNote> clipboard = List.of();
	/** Whole layers taken by Ctrl+C while the panel had the keyboard. Separate from the notes one. */
	private List<Layer> layerClipboard = List.of();
	/** The tick the copy started on, which is where Ctrl+Shift+V puts it back. */
	private long clipboardOriginTick;
	private Button playButton;
	private Button recordButton;
	/**
	 * Record mode, its count-in, and how much the take has written.
	 *
	 * <p>{@code countingInSince} is zero once the count is over, which is what separates armed from
	 * running -- see {@link #takingNotes()}. Recording without playing is the count-in and nothing
	 * else, so the two flags are not the same question.</p>
	 */
	private boolean recording;
	private long countingInSince;
	private int countedIn;
	private int recorded;
	/** Where the end marker was before a take pushed it out, so it can be put back. */
	private long endBeforeTake;
	/**
	 * Notes this take has written, which the take itself must not play back at you.
	 *
	 * <p>You already heard them: the key press sounds the note the instant it is pressed, which is
	 * what makes it feel like an instrument. Quantizing then moves the written note to the nearest
	 * grid line, which is often a little ahead of where it was played -- so the rebuilt schedule
	 * reached it a moment later and sounded it a second time. Two notes a fraction apart, and the
	 * second one is what reads as latency.</p>
	 */
	private final Set<Long> takeNotes = new LinkedHashSet<>();
	private Button snapButton;
	/** The snap settings, in the order the button used to walk through them one click at a time. */
	private static final int[] SNAP_CHOICES = { 1, 2, 4, 8, SNAP_REPEATER, SNAP_GAME_TICK, 0 };
	private boolean snapMenuOpen;
	private int snapMenuX;
	private int snapMenuY;
	private DelayScaleSlider delayScaleSlider;
	private Button addLayerButton;
	private EditBox layerNameBox;
	private boolean playing;
	private long playbackStartedAt;
	/**
	 * Where the clock is counting from, which is not where the marker goes back to.
	 *
	 * <p>Playback position is derived -- this tick, plus however long ago the clock started -- so
	 * every edit made mid-playback has to re-anchor it or the playhead jumps. That anchoring used to
	 * move the marker itself, which meant an edit as innocent as changing a layer's instrument
	 * quietly redefined where Stop would leave you and where the next Play would begin.</p>
	 */
	private long playbackStartTick;
	/**
	 * Where the marker actually is: what Stop returns to, and what Play starts from.
	 *
	 * <p>Only the ruler, Enter and the end of a song move it. Nothing an edit does moves it.</p>
	 */
	private long playbackReturnTick;
	private List<PlaybackEvent> playbackEvents = List.of();
	private int playbackEventIndex;
	/**
	 * When each key last sounded, and in what colour, so the keyboard shows what is playing.
	 *
	 * <p>Two flat arrays over the whole MIDI range rather than a list of live lights: a key is an
	 * index, the roll already walks the keys it can see, and the answer to "is this one lit" has to
	 * be one subtraction. A chord of thirty on a dense song fires thirty of these in a frame, and
	 * anything that allocated per note would be paying for it every frame it decays over.</p>
	 *
	 * <p>No clearing pass and no tick work: a light is a timestamp, so it goes out by being old.</p>
	 */
	private final long[] keyLitAt = new long[MAX_MIDI_NOTE + 1];
	private final int[] keyLitColor = new int[MAX_MIDI_NOTE + 1];
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
	/**
	 * Where a box select began, in the song rather than on the screen.
	 *
	 * <p>A box is drawn over a view that moves. Held as pixels, its far corner stayed where it was
	 * put on the glass while the song slid past underneath -- so scrolling or zooming mid-drag left
	 * a box that had stopped describing any passage at all, and the notes it took were whatever
	 * happened to be under a rectangle nobody had drawn. A tick and a pitch survive all four things
	 * the view can do: scroll either way, and zoom either way.</p>
	 */
	private double boxOriginTick;
	private double boxOriginMidi;
	/**
	 * The stretch of time a box select left behind, or -1 for none.
	 *
	 * <p>A box drag decides two different things and only ever kept one of them. Which notes were
	 * taken is answered the moment the button comes up; how long the passage they came from
	 * <em>is</em> is not answered at all, because a set of notes ends on its last note and a passage
	 * ends on silence. So the drag's own span stays -- drawn, labelled and draggable at both ends --
	 * and that is what a paste steps by. Pull the right-hand end past the last note and the number
	 * changes; that is how you say "and half a bar of rest".</p>
	 *
	 * <p>Time only, with no pitch to it. The box's height decided which notes were selected and has
	 * done its work; a length is a horizontal fact, so the band is drawn the full height of the roll
	 * rather than as the rectangle that made it.</p>
	 */
	/**
	 * Whether a box drag leaves a range behind, and a paste steps by its length.
	 *
	 * <p>Off while the cursor-relative paste below is tried. The whole feature answers to this one
	 * constant -- everything that draws it, grabs it or measures with it already asks
	 * {@link #hasRange()} first -- so it stays built and stays compiled rather than being carried
	 * around in comments, and turning it back on is one word.</p>
	 */
	private static final boolean SELECTION_RANGE = false;
	private long rangeStart = -1L;
	private long rangeEnd = -1L;
	/** Which end of the range is being dragged: 0 none, 1 the start, 2 the end. */
	private int draggingRangeHandle;
	/**
	 * How far a paste steps, captured when the copy was made.
	 *
	 * <p>Held on the clipboard rather than read off the live range, because the range is a selection
	 * and the selection moves on. What you copied keeps the length it was copied with until
	 * something else is copied.</p>
	 */
	private long clipboardSpanTicks;
	/**
	 * Where the notes stood relative to the cursor when they were copied, and how far a paste of
	 * them moves the cursor on.
	 *
	 * <p>A copy is a phrase and a place to stand while looking at it. The two together make a block
	 * with a bound at each end, and it is the block that gets laid down: the run-up before the first
	 * note, or the tail of silence after the last one, is inside it and comes with it. There is
	 * nothing else that carries the silence at the edges of a passage, because a set of notes begins
	 * on its first note and ends on its last.</p>
	 */
	private long clipboardCursorOffset;
	private long clipboardStepTicks;
	private ComposerProject dragBase;
	private ComposerProject dragPreview;
	private long dragTickDelta;
	private int dragPitchDelta;
	private DragAxis dragAxis = DragAxis.UNDECIDED;
	private int rollX;
	private int rollY;
	private int rollWidth;
	private int rollHeight;
	/**
	 * Whether the window had the keyboard at the last tick.
	 *
	 * <p>Read on the next click, so a press can tell whether it is the one handing focus back to
	 * Minecraft. A tick is the right grain for it: the click that raises the window arrives in the
	 * same batch of events as the focus itself, so nothing between them has run, while an alt-tab
	 * followed by a click a moment later gets a tick in between and counts as a real click.</p>
	 */
	private boolean windowWasFocused = true;
	/**
	 * Whether the press being handled is spending itself on pointing something at the roll.
	 *
	 * <p>Two ways a click can be about focus rather than about music: it gave the window back to
	 * Minecraft, or it moved the keyboard from the layer panel onto the roll. Either way it may
	 * still select, drag or box, because none of those leave anything behind -- but it may not
	 * write a note, which is the one thing you cannot undo by looking away.</p>
	 */
	private boolean pressClaimedFocus;
	private double lastMouseX;
	private double lastMouseY;
	private int instrumentMenuLayer = -1;
	/** Which of the palette's two tabs is showing. Follows the layer's own voice when it opens. */
	private boolean instrumentMenuEffects;
	private int editingLayer = -1;
	/**
	 * The grid new notes land on, defaulting to the repeater tick.
	 *
	 * <p>It used to open on a sixteenth, which is a grid from the notation the music arrived in
	 * rather than from the machine it is going into. A repeater tick is the only spacing a note block
	 * build can actually hold: anything finer is off grid and the status line says so. Opening on the
	 * one grid that is always buildable means a note drawn by hand needs no correcting afterwards.</p>
	 */
	private int snapSubdivision = SNAP_REPEATER;
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
	/**
	 * Where the last toast was drawn, so a click can be aimed at it.
	 *
	 * <p>Taken from the drawing rather than worked out again: the box is sized to wrapped text and
	 * centred on the roll, and a second copy of that arithmetic is a second thing to keep right.</p>
	 */
	private int toastLeft;
	private int toastTop;
	private int toastRight;
	private int toastBottom;
	/**
	 * The note actually under the hand during a drag, and the pitch it was on when the drag began.
	 *
	 * <p>A drag can be carrying thirty notes and only one of them is the one you are holding. That
	 * is the one worth hearing: a chord retuning under the cursor every time the hand crosses a row
	 * is noise, and the note you took hold of is the one you are aiming.</p>
	 */
	private long dragHeldNoteId = -1L;
	private int dragHeldMidi;
	private int dragHeldLayer = -1;
	private int dragHeardPitchDelta;
	private long hoveredNoteId = -1L;
	/** The key the cursor has been resting on, and since when, for the same dwell a note gets. */
	private int hoveredKeyMidi = -1;
	private long hoveredKeySince;
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
	private LayerState paintState = LayerState.ACTIVE;
	/** Whether the box being dragged was started with Ctrl, which makes it add rather than replace. */
	private boolean boxAdditive;
	/** Whether the press that started it had a selection to put down, which a plain click does. */
	private boolean boxDroppedSelection;
	/** A right-button sweep across the roll, deleting what it passes over. */
	private boolean erasing;
	private int erased;
	private double lastEraseX;
	private double lastEraseY;
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
	/** Whether the release of this press should still collapse a held-together multi-row selection. */
	private boolean layerDragCollapse;

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
	 * What the document would go back to, and so what leaving it would throw away.
	 *
	 * <p>A song with a file goes back to the file. A document without one goes back to how it
	 * arrived, which for an import is the import itself.</p>
	 *
	 * <p>An import used to be measured against nothing at all, so every note of it counted as
	 * unsaved work and glancing at one before opening something else was asked about as though
	 * edits were being dropped. Nothing was: an import is a function of a file that is still
	 * sitting there, and re-reading it costs one trip through the picker. What cannot be got back
	 * is the work done on top of it, which is exactly what measuring against the import asks
	 * about.</p>
	 */
	private static ComposerProject baseline(FastNoteblocksConfig config, ComposerProject current) {
		ComposerProject onDisk = config.savedComposerProject();
		return onDisk != null ? onDisk : current;
	}

	@Override
	protected void init() {
		clearWidgets();
		rollY = TOOLBAR_HEIGHT + TIMELINE_RULER_HEIGHT;
		rollHeight = Math.max(40, height - rollY - 24);
		centerMinecraftRange();
		// The menus are drawn, not built: see extractMenuBar. Only the three controls are widgets,
		// because they carry state you read off them rather than opening anything.
		//
		// Pinned to the right edge and sized to their own widest label. The menus grow rightward as
		// they are added to and the controls do not move; keeping the two apart means neither can
		// push the other about. Each is measured against every caption it can ever show, so Snap
		// does not jump a pixel when it reaches "repeater" or Speed when it reaches "0.25x".
		int recordWidth = widestLabel(CONTROL_PADDING, "Rec", "\u25cf Rec");
		int playWidth = widestLabel(CONTROL_PADDING, "Play", "Stop");
		int snapWidth = widestLabel(CONTROL_PADDING, "1/4", "1/8", "1/16", "1/32", "Repeater",
			"Game tick", "Off");
		int speedWidth = widestLabel(CONTROL_PADDING + 8, "Speed 0.25x", "Speed 2.00x", "Speed 8.00x");
		int speedX = width - 6 - speedWidth;
		int snapX = speedX - CONTROL_GAP - snapWidth;
		int playX = snapX - CONTROL_GAP - playWidth;
		int recordX = playX - CONTROL_GAP - recordWidth;
		toolbarControlsLeft = recordX;
		recordButton = addRenderableWidget(Button.builder(recordLabel(), button -> toggleRecording())
			.bounds(recordX, CONTROL_TOP, recordWidth, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal(
				"Record. The marker runs on its own, and every piano key you click is written in at "
					+ "the marker, snapped to the grid Snap is set to. Counts you in 3, 2, 1 first.\n"
					+ "Notes go into the selected layer; with none selected it starts one for the take, "
					+ "and the end marker is pushed out to leave room to play into.\n"
					+ "R or Escape stops it, and R starts it.")))
			.build());
		playButton = addRenderableWidget(Button.builder(playLabel(), button -> togglePlayback())
			.bounds(playX, CONTROL_TOP, playWidth, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal(
				"Preview all unmuted layers. Space plays from the marker, Enter from the start.")))
			.build());
		snapButton = addRenderableWidget(Button.builder(snapLabel(), button -> toggleSnapMenu())
			.bounds(snapX, CONTROL_TOP, snapWidth, CONTROL_HEIGHT)
			.build());
		snapMenuX = snapX;
		snapMenuY = CONTROL_TOP + CONTROL_HEIGHT + 1;
		refreshSnapButton();
		delayScaleSlider = addRenderableWidget(new DelayScaleSlider(
			speedX, CONTROL_TOP, speedWidth, CONTROL_HEIGHT, project().speedQuarters(),
			this::setDelayScale
		));
		refreshSpeedTooltip();
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
		// Pruned for the same reason solo is, and now for a second one: an empty selection is a mode
		// with its own behaviour, so a selection left holding only positions that no longer exist
		// would put the screen into that mode without saying so.
		selectedLayers.removeIf(index -> index >= project().layers().size());
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
		// An empty layer, always. This used to move whatever notes were selected into the new layer,
		// which reads as a cut nobody asked for: a selection outlives the copy that was made from
		// it, so copying a phrase and then adding a layer to paste it into took the phrase out of
		// the layer it was copied from. Adding a layer is one thing and moving notes is another,
		// and the second one now has to be asked for -- from a row's own menu, or Ctrl+1..0.
		addLayerButton = addRenderableWidget(Button.builder(Component.literal("+ Layer"), button -> {
			if (project().layers().size() < ComposerProject.MAX_LAYERS) {
				ComposerProject added = project().addLayer();
				apply("add layer", added);
				selectOnlyLayer(added.layers().size() - 1);
				layersChanged();
				rebuildMoveLayerButtons();
			}
		}).bounds(8, y, layerPanelWidth() - 16, 18)
			.tooltip(Tooltip.create(Component.literal(
				"Add an empty layer (maximum " + ComposerProject.MAX_LAYERS + ").\n"
					+ "Nothing selected is moved into it: to do that, right-click the new row and "
					+ "pick \"Move selected notes here\", or press Ctrl+1..0."
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

	/**
	 * Back to no layer selected and the top of the list, after something replaced the song.
	 *
	 * <p>A song arrives as a whole, not as one voice with the rest behind it, and which layer the
	 * file happened to be saved pointing at says nothing about which one you are about to work on.
	 * So opening one selects nothing: the roll shows every part at full strength, a box takes notes
	 * from all of them, and clicking one is how you say where you want to be.</p>
	 */
	private void resetLayerView() {
		selectedLayers.clear();
		clearRange();
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
	private void updateLayers(String label, int clickedIndex,
			java.util.function.UnaryOperator<Layer> mutation) {
		ComposerProject updated = project();
		for (int index : layersToEdit(clickedIndex)) {
			if (index >= 0 && index < updated.layers().size()) {
				updated = updated.withLayer(index, mutation.apply(updated.layers().get(index)));
			}
		}
		apply(label, updated);
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
		ACTIVE("A", "Active", 0xFFE8EAEE, 0xFF3A4048,
			"you hear it, it is in the piano roll, and it is in the build."),
		MUTED("M", "Muted", 0xFFFFB05A, 0xFF3E332A,
			"silent, and left out of the build. Still in the roll and still editable, and its "
				+ "instrument wears a red slash."),
		SOLO("S", "Solo", 0xFFFFD65A, 0xFF453D22,
			"the only thing you hear. Every other layer is slashed out until you turn it off -- "
				+ "but solo is only about listening, so the others are still built."),
		HIDDEN("H", "Hidden", 0xFF787D85, 0xFF24272B,
			"silent, out of the piano roll and left out of the build, so it is not in the way at "
				+ "all. Its row is greyed out.");

		private final String letter;
		private final String title;
		private final int color;
		private final int chip;
		private final String description;

		LayerState(String letter, String title, int color, int chip, String description) {
			this.letter = letter;
			this.title = title;
			this.color = color;
			this.chip = chip;
			this.description = description;
		}

		private String sentence() {
			return title + " - " + description;
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
		applyMaybeCoalesced("set " + layerCountLabel(indices.size()) + " to "
			+ state.title.toLowerCase(java.util.Locale.ROOT), updated, coalesce);
		layersChanged();
		if (playing) {
			resetPlaybackSchedule();
		}
		updateButtonStates();
	}

	private void applyMaybeCoalesced(String label, ComposerProject updated, boolean coalesce) {
		if (!coalesce) {
			apply(label, updated);
			return;
		}
		anchorPlayhead();
		history.replaceCurrent(updated);
		afterStateChange();
	}

	/**
	 * Selects a layer, or -- by ctrl-clicking the last one off -- selects none.
	 *
	 * <p>An empty selection used to be corrected back to the clicked row, on the assumption that
	 * nothing selected could only be a mistake. It is a mode of its own now, so the correction is
	 * gone and the two ways in agree: ctrl-click the last one off, or click the empty panel.</p>
	 */
	private void selectLayer(int layerIndex, boolean toggle, boolean range) {
		boolean wasEmpty = noLayerSelected();
		if (toggle) {
			if (!selectedLayers.remove(layerIndex)) {
				selectedLayers.add(layerIndex);
			}
		} else if (range && !wasEmpty) {
			int from = Math.min(project().activeLayerIndex(), layerIndex);
			int to = Math.max(project().activeLayerIndex(), layerIndex);
			for (int index = from; index <= to; index++) {
				selectedLayers.add(index);
			}
		} else {
			selectedLayers.clear();
			selectedLayers.add(layerIndex);
		}
		if (noLayerSelected()) {
			layersChanged();
			return;
		}
		apply("select layer " + (layerIndex + 1), project().withActiveLayer(layerIndex));
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

	/** The row the layer menu belongs to, falling back to the active layer if it has gone. */
	private int menuRow() {
		return layerMenuRow >= 0 && layerMenuRow < project().layers().size()
			? layerMenuRow
			: project().activeLayerIndex();
	}

	/**
	 * Copies the layer the menu was opened on -- or every selected layer, if it is one of them --
	 * and moves onto the copies.
	 *
	 * <p>Each copy goes directly after the layer it came from, so duplicating three parts of an
	 * arrangement leaves the pairs read together rather than the copies stacked at the bottom in an
	 * order nobody chose.</p>
	 */
	private void duplicateLayers(int source) {
		duplicateLayers(layersToEdit(source));
	}

	/** The rows in view order, for the keyboard, which has no clicked row to start from. */
	private List<Integer> sortedSelectedLayers() {
		return selectedLayers.stream()
			.filter(index -> index >= 0 && index < project().layers().size())
			.sorted()
			.toList();
	}

	private void duplicateLayers(List<Integer> sources) {
		if (sources.isEmpty()) {
			return;
		}
		ComposerProject copied = project().duplicateLayers(Set.copyOf(sources));
		if (copied.equals(project())) {
			showResult(Component.literal("No room for " + (sources.size() == 1 ? "another layer"
				: sources.size() + " more layers") + " - " + ComposerProject.MAX_LAYERS
				+ " is the limit."));
			return;
		}
		int notes = sources.stream()
			.mapToInt(index -> project().layers().get(index).notes().size()).sum();
		String only = sources.size() == 1
			? " \"" + project().layers().get(sources.getFirst()).name() + "\"" : "";
		apply(sources.size() == 1 ? "duplicate layer " + (sources.getFirst() + 1)
			: "duplicate " + sources.size() + " layers", copied);
		// Onto the copies, not the originals: a duplicate is made in order to change it. The kth
		// copy lands one past its source plus the k copies already inserted above it.
		selectedLayers.clear();
		for (int rank = 0; rank < sources.size(); rank++) {
			selectedLayers.add(sources.get(rank) + rank + 1);
		}
		layersChanged();
		rebuildMoveLayerButtons();
		showResult(Component.literal("Duplicated " + layerCountLabel(sources.size()) + only + " - "
			+ notes + " notes. The " + (sources.size() == 1 ? "copy is" : "copies are")
			+ " selected."));
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
		apply("merge " + merging.size() + " layers", project().mergeLayers(Set.copyOf(selectedLayers)));
		resetLayerView();
		// The one thing a merge leaves you wanting to look at is what came out of it, so this is the
		// exception to opening on nothing selected.
		selectOnlyLayer(merging.getFirst());
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

	/**
	 * Pulls the selected layers forward until the first of them plays on tick zero.
	 *
	 * <p>What an import nearly always needs: a MIDI written with a bar of count-in builds a bar of
	 * silence into the world, and the only sign of it before the build is a gap at the left of the
	 * roll that looks like part of the layout. Reported in bars as well as ticks because a tick
	 * count is not a thing anyone has an opinion about, and "2 bars" is.</p>
	 */
	private void snapSelectedLayersToStart() {
		Set<Integer> snapping = Set.copyOf(selectedLayers);
		long moved = project().firstNoteTick(snapping);
		if (moved <= 0L) {
			showResult(Component.literal(moved == 0L
				? "Already starts on tick zero."
				: "Nothing to move - the selected layers have no notes."));
			return;
		}
		apply("snap layers to start", project().snappedToStart(snapping));
		layersChanged();
		showResult(Component.literal("Pulled " + layerCountLabel(snapping.size()) + " forward by "
			+ moved + " ticks (" + String.format(java.util.Locale.ROOT, "%.2f", barsOf(moved))
			+ " bars)."));
	}

	/** A tick count as 4/4 bars, which is the unit the ruler numbers are already counted in. */
	private double barsOf(long ticks) {
		return ticks / (project().ppq() * 4.0);
	}

	/**
	 * Removes the selected layers and says what went with them.
	 *
	 * <p>No confirmation, because Ctrl+Z is one and a better one -- a dialog asks before you can see
	 * what you did, undo asks after. The count is reported for the same reason merging reports one:
	 * the panel scrolls, and a selection made three screens up can be larger than it looks.</p>
	 */
	private void deleteSelectedLayers() {
		List<Integer> deleting = selectedLayers.stream()
			.filter(index -> index >= 0 && index < project().layers().size())
			.sorted()
			.toList();
		if (deleting.isEmpty()) {
			return;
		}
		int notes = deleting.stream().mapToInt(index -> project().layers().get(index).notes().size()).sum();
		boolean all = deleting.size() == project().layers().size();
		String only = deleting.size() == 1
			? " \"" + project().layers().get(deleting.getFirst()).name() + "\""
			: "";
		apply("delete " + layerCountLabel(deleting.size()), project().deleteLayers(Set.copyOf(deleting)));
		// Solo is held by index, and every layer below a deleted one just moved up. Renumbering
		// rather than clearing, so deleting a layer you were not listening to does not also stop
		// you listening to the one you were.
		List<Integer> stillSoloed = soloedLayers.stream()
			.filter(index -> !deleting.contains(index))
			.map(index -> index - (int)deleting.stream().filter(gone -> gone < index).count())
			.toList();
		soloedLayers.clear();
		soloedLayers.addAll(stillSoloed);
		instrumentMenuLayer = -1;
		cancelLayerRename();
		resetLayerView();
		layersChanged();
		rebuildMoveLayerButtons();
		showResult(Component.literal("Deleted " + deleting.size()
			+ (deleting.size() == 1 ? " layer" : " layers") + only + " and " + notes + " notes."
			+ (all ? " That was all of them, so an empty layer is left to work in." : "")
			+ " Ctrl+Z puts them back."));
	}

	private void updateLayer(String label, int index, Layer layer) {
		apply(label, project().withLayer(index, layer));
		layersChanged();
	}

	private void moveSelectionToLayer(int target) {
		if (target < 0 || target >= project().layers().size()) {
			return;
		}
		apply("move notes to layer " + (target + 1), project().moveNotesToLayer(selectedNotes, target));
		selectOnlyLayer(target);
		layersChanged();
		rebuildMoveLayerButtons();
	}

	/**
	 * Steps the clicked layer, or the whole selection it belongs to, one row up or down.
	 *
	 * <p>The selection moves as a block: the gap it is aimed at is one past the end it is heading
	 * for, so three rows with something unselected between them arrive together rather than each
	 * hopping its own neighbour.</p>
	 */
	private void moveLayers(int clickedIndex, int direction) {
		if (clickedIndex < 0 || clickedIndex >= project().layers().size() || direction == 0) {
			return;
		}
		List<Integer> moving = layersToEdit(clickedIndex);
		int first = moving.stream().mapToInt(Integer::intValue).min().orElse(clickedIndex);
		int last = moving.stream().mapToInt(Integer::intValue).max().orElse(clickedIndex);
		reorderLayers(moving, direction < 0 ? first - 1 : last + 2);
	}

	/**
	 * Drops dragged layers into a gap.
	 *
	 * <p>{@code insertion} counts gaps, not rows, so dropping below where the block started lands
	 * one row short of it once the block itself is out of the list -- which is arithmetic
	 * {@link ComposerProject#layerOrderAfterMove} does, since it is the only thing that knows how
	 * many of the moving rows were above the gap.</p>
	 */
	private void dropLayers(int from, int insertion) {
		reorderLayers(layersToEdit(from), insertion);
	}

	/**
	 * Rearranges the layers and carries every set of layer positions along with them.
	 *
	 * <p>Three things are held by position, not by identity: the composition's active layer, the
	 * panel's selection and which layers are soloed. A reorder that renumbers only the first leaves
	 * the other two pointing at whatever slid into the vacated rows -- so a drag would move the row
	 * and leave the tick on the one below it, and could silence a part nobody touched.</p>
	 */
	private void reorderLayers(List<Integer> moving, int insertion) {
		if (moving.isEmpty()) {
			return;
		}
		List<Integer> order = project().layerOrderAfterMove(Set.copyOf(moving), insertion);
		boolean unchanged = true;
		for (int index = 0; index < order.size(); index++) {
			unchanged &= order.get(index) == index;
		}
		if (unchanged) {
			return;
		}
		instrumentMenuLayer = -1;
		cancelLayerRename();
		apply(moving.size() == 1 ? "reorder layers" : "reorder " + moving.size() + " layers",
			project().withLayerOrder(order));
		int[] moved = new int[order.size()];
		for (int placed = 0; placed < order.size(); placed++) {
			moved[order.get(placed)] = placed;
		}
		remapLayerPositions(selectedLayers, moved);
		remapLayerPositions(soloedLayers, moved);
		layersChanged();
		rebuildMoveLayerButtons();
	}

	/** Renumbers a set of layer positions through {@code moved}, old position to new. */
	private static void remapLayerPositions(Set<Integer> positions, int[] moved) {
		List<Integer> renumbered = positions.stream()
			.filter(index -> index >= 0 && index < moved.length)
			.map(index -> moved[index])
			.sorted()
			.toList();
		positions.clear();
		positions.addAll(renumbered);
	}

	private void transposeSelected(int semitones) {
		if (!selectedNotes.isEmpty()) {
			apply(semitones % 12 == 0
				? "transpose " + Math.abs(semitones / 12) + " octave" + (Math.abs(semitones) == 12 ? "" : "s")
					+ (semitones > 0 ? " up" : " down")
				: "transpose " + Math.abs(semitones) + " semitones"
					+ (semitones > 0 ? " up" : " down"),
				project().moveNotes(selectedNotes, 0L, semitones));
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

	/**
	 * Slides the whole song to wherever the least of its tune falls outside what can be built.
	 *
	 * <p>Its own action rather than a step inside Convert, because it is the one fix here that
	 * changes what the song <em>is</em>. Everything else conversion does is a repair -- an octave
	 * jump, a merged repeat, a nudged tempo -- and this changes the key, which is a musical decision
	 * and not a technical one. Run it before converting and the conversion has less to tear.</p>
	 */
	private void transposeToBestFit() {
		ComposerProject.TransposeFit fit = project().bestTransposeIntoRange();
		if (!fit.worthDoing()) {
			showResult(Component.literal(fit.outNow() == 0L
				? "Every note is already in range - nothing to gain by moving the song."
				: "No shift does better than where the song already sits. " + fit.outNow()
					+ " notes are out of range because it is wider than a note block's two octaves, "
					+ "which no key change can fix."));
			return;
		}
		apply("transpose into range", project().transposedBy(fit.semitones()));
		selectedNotes.clear();
		centerMinecraftRange();
		layersChanged();
		showResult(Component.literal(String.format(java.util.Locale.ROOT,
			"Transposed %+d semitones - out of range %d -> %d, and %d -> %d of them in the melody. "
				+ "Same tune, different key; Ctrl+Z puts it back.",
			fit.semitones(), fit.outNow(), fit.outAfter(), fit.melodyOutNow(), fit.melodyOutAfter())));
	}

	/**
	 * @param gameTicks fit the song to the grid two lanes can place rather than the one a single
	 *     chain can. Every step is the same; only the tick the tempo and the quantise aim at
	 *     changes, and it is half as coarse -- which is the whole reason to pick it. The build then
	 *     needs the Half-tick lane layout, and the status bar says so once this has run.
	 */
	private void convertToMinecraft(boolean gameTicks) {
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
		try {
			// Always aligned, and to the grid the button named. Whether to align used to be a
			// setting, from before there were two Convert buttons -- but landing the song on a
			// redstone grid is the whole of what Convert is for, and Edit > Quantize is there for
			// anyone who wants the notes moved without the tempo following.
			MinecraftConversion conversion = source.convertToMinecraft(
				gridTicks, true, config.repeatMergeTicks(), gameTicks,
				config.convertOctaveShifting(), config.convertSplitTransposed());
			if (conversion.project().equals(project())) {
				showResult(
					Component.literal("This composition is already Minecraft-ready."));
				return;
			}
			// Convert bakes the speed into the tempo, so the result plays at its own pace.
			apply("convert to Minecraft",
				conversion.project().withSpeedQuarters(ComposerProject.DEFAULT_SPEED_QUARTERS));
			delayScaleSlider.setScale(ComposerProject.DEFAULT_SPEED_QUARTERS);
			selectedNotes.clear();
			instrumentMenuLayer = -1;
			resetLayerView();
			centerMinecraftRange();
			layersChanged();
			rebuildMoveLayerButtons();
			String report = "Converted at " + conversionGridLabel(gridTicks)
				+ (gameTicks ? " on game ticks (2 lanes)" : " on repeater ticks")
				+ ": " + conversion.shiftedNotes() + " pitch-shifted"
				+ (conversion.addedLayers() > 0 ? ", +" + conversion.addedLayers() + " layers" : "")
				+ (conversion.mergedRepeats() > 0
					? ", " + conversion.mergedRepeats() + " repeats merged" : "")
				+ (conversion.duplicateLayers() > 0
					? ", " + conversion.duplicateLayers() + " duplicate layers dropped ("
						+ conversion.duplicateLayerNotes() + " notes)" : "")
				// The same dedupe as the line above, arriving a note at a time because with the
				// split off there is no second layer for it to arrive as. Said either way: a note
				// that stopped existing is worth a number even when it was already being played.
				+ (conversion.mergedIntoExisting() > 0
					? ", " + conversion.mergedIntoExisting() + " duplicate notes merged" : "");
			// Every tempo change says by how much. Aligning to the repeater grid moves the tempo to
			// whichever side is nearest, so a conversion speeds a song up about as often as it slows
			// one down -- but only the slowdown ever carried a number, and the speed-up was reported
			// as "tempo aligned to repeaters", which does not say that the song now plays faster,
			// let alone by how much.
			String tempoMove = tempoMove(source, conversion);
			if (conversion.slowedDown()) {
				report += String.format(java.util.Locale.ROOT,
					", SLOWED %.2fx%s - song is faster than redstone can play (max 10 notes/sec)",
					conversion.tempoFactor(), tempoMove);
			} else if (conversion.spedUp()) {
				report += String.format(java.util.Locale.ROOT,
					", SPED UP %.2fx%s - tempo aligned to repeaters",
					conversion.speedFactor(), tempoMove);
			} else if (conversion.tempoChanged()) {
				report += ", tempo aligned to repeaters" + tempoMove;
			}
			showResult(Component.literal(report));
		} catch (IllegalStateException exception) {
			minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(this),
				Component.literal("Too many converted layers"),
				Component.literal(exception.getMessage()),
				CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
		}
	}

	/**
	 * " (120 -> 150 BPM)", the tempo move a conversion made, or "" when it made none.
	 *
	 * <p>A factor says how far the song moved and a BPM pair says where it moved to, and the second
	 * is the one you can act on: it is the number the source file was written in and the number the
	 * next thing you do to this song will be working against.</p>
	 */
	private static String tempoMove(ComposerProject source, MinecraftConversion conversion) {
		int before = source.tempoMicrosPerQuarter();
		int after = conversion.project().tempoMicrosPerQuarter();
		if (before == after) {
			return "";
		}
		return String.format(java.util.Locale.ROOT, " (%.0f -> %.0f BPM)",
			60_000_000.0 / before, 60_000_000.0 / after);
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

	private void setSnap(int subdivision) {
		snapSubdivision = subdivision;
		snapMenuOpen = false;
		refreshSnapButton();
	}

	/**
	 * Opens the list of grids under the button.
	 *
	 * <p>Seven settings behind one button meant getting to the one you wanted by pressing it up to
	 * six times and reading the label each time, which is a worse way to choose from a list than
	 * any list. The button still says which grid is on -- that is the thing worth having on screen
	 * at a glance -- and the list is what changing it costs.</p>
	 */
	private void toggleSnapMenu() {
		boolean opening = !snapMenuOpen;
		closeMenus();
		snapMenuOpen = opening;
	}

	private int snapMenuWidth() {
		int widest = 0;
		for (int choice : SNAP_CHOICES) {
			widest = Math.max(widest, font.width(snapMenuLabel(choice).getString())
				+ 14 + smallTextWidth(snapDetail(choice)));
		}
		return widest + 20;
	}

	private int snapMenuHeight() {
		return SNAP_CHOICES.length * CONTEXT_MENU_ROW_HEIGHT + 4;
	}

	/**
	 * The grids, each with what one of its steps is worth in repeater ticks.
	 *
	 * <p>The number is the whole reason this is a list rather than a cycle. "1/16" says nothing on
	 * its own -- across a library it is anything from a quarter of a repeater tick to four of them,
	 * because it is a note value and a repeater tick is a hundred milliseconds. Amber marks the
	 * settings whose steps are not delays a build can place, so the ones that will hold up are
	 * visible before one of them is picked rather than after.</p>
	 */
	private void extractSnapMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!snapMenuOpen) {
			return;
		}
		int menuWidth = snapMenuWidth();
		int left = Math.max(0, Math.min(snapMenuX, width - menuWidth));
		int top = snapMenuY;
		graphics.fill(left, top, left + menuWidth, top + snapMenuHeight(), 0xF0101115);
		graphics.fill(left, top, left + menuWidth, top + 1, 0xFFAAAAAA);
		for (int index = 0; index < SNAP_CHOICES.length; index++) {
			int choice = SNAP_CHOICES[index];
			int rowY = top + 2 + index * CONTEXT_MENU_ROW_HEIGHT;
			boolean hovered = mouseX >= left && mouseX < left + menuWidth
				&& mouseY >= rowY && mouseY < rowY + CONTEXT_MENU_ROW_HEIGHT;
			boolean current = choice == snapSubdivision;
			if (hovered) {
				graphics.fill(left + 2, rowY, left + menuWidth - 2,
					rowY + CONTEXT_MENU_ROW_HEIGHT, 0xFF356070);
			}
			if (current) {
				graphics.fill(left + 2, rowY, left + 4, rowY + CONTEXT_MENU_ROW_HEIGHT, 0xFF8FD3FF);
			}
			graphics.text(font, snapMenuLabel(choice), left + 8, rowY + 4,
				current ? 0xFFFFFFFF : 0xFFCFD4DA, false);
			// Off is the one setting with no step, so it has no number to carry.
			if (choice != 0) {
				String detail = snapDetail(choice);
				smallText(graphics, detail, left + menuWidth - 8 - smallTextWidth(detail), rowY + 5,
					snapOnRedstoneGrid(choice) ? 0xFF8A9098 : 0xFFE8B04A);
			}
		}
	}

	private boolean handleSnapMenuClick(double mouseX, double mouseY) {
		int menuWidth = snapMenuWidth();
		int left = Math.max(0, Math.min(snapMenuX, width - menuWidth));
		if (mouseX < left || mouseX >= left + menuWidth) {
			return false;
		}
		int row = ((int)mouseY - snapMenuY - 2) / CONTEXT_MENU_ROW_HEIGHT;
		if (row < 0 || row >= SNAP_CHOICES.length || mouseY < snapMenuY + 2) {
			return false;
		}
		setSnap(SNAP_CHOICES[row]);
		return true;
	}

	/**
	 * How many repeater ticks one grid step covers, as a phrase.
	 *
	 * <p>The number nobody could get at. "Snap 1/16" is not an interpretable statement on its own --
	 * across this library the same setting is anything from a quarter of a repeater tick to four of
	 * them, because it is a note value and a repeater tick is a hundred milliseconds, and what stands
	 * between them is the tempo. A whole number here means the musical grid and the machine's grid
	 * are the same grid; a fraction means they are not, which is what an unconverted song looks
	 * like.</p>
	 */
	/** "1.50" as "1.5", "0.50" as "0.5" -- a decimal place that says nothing is noise on a button. */
	private static String trimZeros(String decimal) {
		String trimmed = decimal;
		while (trimmed.contains(".") && trimmed.endsWith("0")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	private String snapDetail() {
		return snapDetail(snapSubdivision);
	}

	private String snapDetail(int subdivision) {
		double ticks = gridSpan(subdivision)
			/ Math.max(1.0e-6, SongAnalysis.redstoneTickSpan(project()));
		String count = Math.abs(ticks - Math.rint(ticks)) < 0.005
			? Long.toString(Math.round(ticks))
			: trimZeros(String.format(java.util.Locale.ROOT, "%.2f", ticks));
		return count + " repeater tick" + ("1".equals(count) ? "" : "s");
	}

	/**
	 * Whether the lines of this grid are positions a build could actually place a note on.
	 *
	 * <p>Asked the way {@link SongAnalysis} asks it, in game ticks, because that is the finer of the
	 * two things a build can be made of. A whole number of game ticks is placeable; an odd one needs
	 * the second lane, so it is placeable only in a paste mode that has one. Anything else falls
	 * between the delays a repeater can make and cannot be built at all.</p>
	 */
	private boolean snapOnRedstoneGrid() {
		return snapOnRedstoneGrid(snapSubdivision);
	}

	private boolean snapOnRedstoneGrid(int subdivision) {
		return snapGameTicks(subdivision) >= 1L;
	}

	/**
	 * How many game ticks one step of a grid is, or 0 for a grid that is not a whole number of them.
	 *
	 * <p>Not asked of the paste mode. Whether the mode in hand happens to lay a second lane is a
	 * fact about a build nobody has started yet, and one that changes in a screen the composer
	 * cannot see; what is being described here is the grid. A whole number of game ticks is
	 * placeable, an odd one wants both lanes to do it, and saying so is more use than refusing.</p>
	 */
	private long snapGameTicks(int subdivision) {
		double gameTicks = gridSpan(subdivision)
			/ Math.max(1.0e-6, SongAnalysis.redstoneTickSpan(project()) / 2.0);
		long whole = Math.round(gameTicks);
		return whole >= 1L && Math.abs(gameTicks - whole) <= 0.01 ? whole : 0L;
	}

	private Tooltip snapTooltip() {
		long gameTicks = snapGameTicks(snapSubdivision);
		String where = gameTicks == 0L
			? "That is not a delay a build can place at all, so notes put on this grid fall between "
				+ "the ticks it can reach. Edit > Convert for Minecraft moves the tempo until the "
				+ "two line up."
			: gameTicks % 2L == 0L
				? "That is a whole number of repeater ticks, so a single chain places it and notes "
					+ "put on this grid are notes any build can reach."
				: "That is a whole number of game ticks and an odd one, so it lands between the "
					+ "repeater ticks: placeable, and only by a build of two lanes.";
		// Whether the lines on screen are the grid or a stand-in for it. Drawing every step at
		// this zoom would be a wall of pixels, so the grid doubles until its lines are far
		// enough apart -- which means "is that a game tick" sometimes answers no, and used to
		// be unaskable. The lines are drawn brighter when they are the grid itself; this puts
		// the same thing in words, with how far out of true the drawing is.
		double drawn = drawnGridSpan();
		String zoom = drawn <= gridSpan() * 1.001
			? "Every step is drawn at this zoom, which is what the amber lines mean."
			: "Zoomed out: one line drawn per " + Math.round(drawn / gridSpan())
				+ " steps. They are grey rather than amber to say they are not the grid itself.";
		return Tooltip.create(Component.literal("Grid used when adding or dragging notes."
			+ "\nOne step is " + snapDetail() + ".\n" + where + "\n" + zoom));
	}

	/**
	 * What a grid is called, without the word Snap in front of it.
	 *
	 * <p>The button used to carry the word, which cost it the width of five characters at every
	 * setting -- on a cluster pinned to the right-hand edge, sized to its longest possible caption,
	 * so Snap game tick was being paid for while 1/4 was showing. The button sits under a list that
	 * says Snap on every row and beside a status bar that says grid, so there was never much doubt
	 * about what the number was.</p>
	 */
	private Component snapLabel() {
		return snapLabel(snapSubdivision);
	}

	private Component snapLabel(int subdivision) {
		return Component.literal(gridName(subdivision));
	}

	/** The same, with the word, for the list where the settings are read one after another. */
	private Component snapMenuLabel(int subdivision) {
		return Component.literal("Snap " + gridName(subdivision).toLowerCase(java.util.Locale.ROOT));
	}

	private static String gridName(int subdivision) {
		return switch (subdivision) {
			case 1 -> "1/4";
			case 2 -> "1/8";
			case 4 -> "1/16";
			case 8 -> "1/32";
			case SNAP_REPEATER -> "Repeater";
			case SNAP_GAME_TICK -> "Game tick";
			default -> "Off";
		};
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
		rollY = TOOLBAR_HEIGHT + TIMELINE_RULER_HEIGHT;
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
		extractSnapMenu(graphics, mouseX, mouseY);
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
		int menuHeight = layerMenuHeight();
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
		int acting = layersToEdit(menuRow()).size();
		return switch (action) {
			case DUPLICATE -> acting == 1 ? action.label : "Duplicate " + acting + " layers";
			case MOVE_UP -> acting == 1 ? action.label : "Move " + acting + " layers up";
			case MOVE_DOWN -> acting == 1 ? action.label : "Move " + acting + " layers down";
			case MOVE_NOTES_HERE -> "Move " + selectedNotes.size()
				+ (selectedNotes.size() == 1 ? " note here" : " notes here");
			case MERGE_SELECTED -> "Merge " + selected + " layers (Ctrl+E)";
			case DELETE_SELECTED -> "Delete " + layerCountLabel(Math.max(1, selected));
			case SNAP_TO_START -> "Snap " + layerCountLabel(Math.max(1, selected)) + " to song start";
			default -> action.label;
		};
	}

	private int layerMenuHeight() {
		return LayerAction.values().length * CONTEXT_MENU_ROW_HEIGHT + 4;
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
			case DELETE_SELECTED -> !selectedLayers.isEmpty();
			// Greyed out when the selection already starts at zero, so the menu answers "is there
			// anything to pull forward" without having to click it and read the result.
			case SNAP_TO_START -> project().firstNoteTick(Set.copyOf(selectedLayers)) > 0L;
			case RENAME, SELECT_ALL -> true;
			case DUPLICATE -> project().layers().size() + layersToEdit(menuRow()).size()
				<= ComposerProject.MAX_LAYERS;
			// Greyed out at the ends of the list, where the step has nowhere to land -- and the ends
			// are the ends of the block, since a selection moves as one.
			case MOVE_UP -> layersToEdit(menuRow()).stream().mapToInt(Integer::intValue).min()
				.orElse(0) > 0;
			case MOVE_DOWN -> layersToEdit(menuRow()).stream().mapToInt(Integer::intValue).max()
				.orElse(0) < project().layers().size() - 1;
			case MOVE_NOTES_HERE -> !selectedNotes.isEmpty();
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
			case RENAME -> beginLayerRename(menuRow());
			case DUPLICATE -> duplicateLayers(menuRow());
			case MOVE_UP -> moveLayers(menuRow(), -1);
			case MOVE_DOWN -> moveLayers(menuRow(), 1);
			case MOVE_NOTES_HERE -> moveSelectionToLayer(menuRow());
			case MERGE_SELECTED -> mergeSelectedLayers();
			case SNAP_TO_START -> snapSelectedLayersToStart();
			case DELETE_SELECTED -> deleteSelectedLayers();
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
		int menuWidth = menuWidth(rows, TOOLBAR_MENU_WIDTH, toolbarMenuX);
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
			case SELECT -> "Select";
			case BUILD -> "Build";
			case SETTINGS -> "Settings";
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
				rows.add(MenuRow.of(ToolbarSubmenu.MARKERS));
				rows.add(MenuRow.of(ToolbarSubmenu.END));
			}
			case BUILD -> addActionRows(rows, ToolbarAction.BUILD_ACTIONS);
			case SELECT -> addActionRows(rows, ToolbarAction.SELECT_ACTIONS);
			// Settings opens a screen from the bar, so it never hangs a panel of its own.
			case SETTINGS, NONE -> {
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
		return toolbarRowLabel(row.action())
			+ (selectedNotes.isEmpty() || !row.action().scopeable ? "" : " (selection)");
	}

	private boolean rowEnabled(MenuRow row) {
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
			case QUANTIZE_GAME_TICKS -> project().buildGridTicks(true);
			default -> 0L;
		};
		return ticks == 0L ? "" : Long.toString(ticks);
	}

	private String rowDescription(MenuRow row) {
		if (row.submenu() != null) {
			return row.submenu().description;
		}
		return toolbarActionTooltip(row.action());
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
			case CONVERT, CONVERT_GAME_TICKS, MERGE_REPEATS, FIT_ALL_RANGE, SNAP_TEMPO,
				SNAP_TEMPO_GAME, QUANTIZE_QUARTER, QUANTIZE_EIGHTH, QUANTIZE_SIXTEENTH,
				QUANTIZE_REPEATERS, QUANTIZE_GAME_TICKS ->
				project().layers().stream().anyMatch(layer -> !layer.notes().isEmpty());
			// Greyed out when no shift beats standing still, so the menu answers "is my song already
			// sitting where it best can" without changing the key to find out.
			case TRANSPOSE_BEST_FIT -> project().bestTransposeIntoRange().worthDoing();
			case SELECT_OFF_GRID -> !projectStats().offGridNotes().isEmpty();
			case SELECT_HALF_TICKED -> !projectStats().halfTickedNotes().isEmpty();
			case SELECT_TOO_FREQUENT -> !projectStats().crowdedNotes().isEmpty();
			case SELECT_OUT_OF_RANGE -> projectStats().outOfRange() > 0;
			case SELECT_OVERLOADED_CHORDS -> projectStats().overloadedTicks() > 0
				|| projectStats().peakChord() > config.chordThinTarget();
			case SELECT_NONE -> focusedPane == Pane.LAYERS
				|| !selectedNotes.isEmpty() || !selectedLayers.isEmpty();
			case RENAME_MARKER -> markerAtCursor() != null;
			case DUPLICATE_SELECTION -> !selectedNotes.isEmpty();
			case BAKE_SPEED ->
				project().speedQuarters() != ComposerProject.DEFAULT_SPEED_QUARTERS;
			case CLEAR_MARKERS -> !project().markers().isEmpty();
			// Nothing to scan from the title screen, and the coordinate prompt would have no way
			// to tell you that the region you typed reads as empty because there is no world.
			case SCAN_WORLD -> minecraft.level != null;
			// Not disabled on an unbuildable song: greying it out would hide the reason. The status
			// bar already names the problem and the planner refuses with a specific one.
			case PASTE_IN_WORLD -> projectStats().totalNotes() > 0 || project().endTick() > 0L;
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
		int menuWidth = menuWidth(rows, TOOLBAR_MENU_WIDTH, toolbarMenuX);
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
			case CONVERT -> convertToMinecraft(false);
			case CONVERT_GAME_TICKS -> convertToMinecraft(true);
			case MERGE_REPEATS -> applyStep("Merged", "merge repeated notes",
				project().withMergedRepeats(config.repeatMergeTicks(), selectedNotes));
			case QUANTIZE_QUARTER -> quantizeTo(project().ppq());
			case QUANTIZE_EIGHTH -> quantizeTo(Math.max(1, project().ppq() / 2));
			case QUANTIZE_SIXTEENTH -> quantizeTo(Math.max(1, project().ppq() / 4));
			case QUANTIZE_REPEATERS -> quantizeToBuildTicks(false);
			case QUANTIZE_GAME_TICKS -> quantizeToBuildTicks(true);
			case FIT_ALL_RANGE -> applyStep("Fitted to range", "fit notes into range",
				project().withAllFittedToRange(selectedNotes));
			case TRANSPOSE_BEST_FIT -> transposeToBestFit();
			case BAKE_SPEED -> bakeSpeed();
			case SNAP_TEMPO -> snapTempo(false);
			case SNAP_TEMPO_GAME -> snapTempo(true);
			case DUPLICATE_SELECTION -> duplicateSelection();
			case ADD_MARKER -> toggleMarkerAtCursor();
			case RENAME_MARKER -> renameMarker(markerAtCursor());
			case CLEAR_MARKERS -> clearMarkers();
			case SNAP_END -> applyStep("End snapped", "snap the end to the grid", project().withEndTick(
				snapEndToRepeaterGrid()));
			case TRIM_END -> applyStep("Trimmed", "trim the end to the last note",
				project().trimmedToContent());
			case SELECT_OFF_GRID -> selectNotesWhere("off grid",
				note -> projectStats().offGrid().contains(note.startTick()), true);
			case SELECT_HALF_TICKED -> selectNotesWhere("half-ticked",
				note -> projectStats().halfTicked().contains(note.startTick()), true);
			case SELECT_TOO_FREQUENT -> selectNotesWhere("too frequent",
				note -> projectStats().crowded().contains(note.startTick()), true);
			case SELECT_OUT_OF_RANGE -> selectNotesWhere("out of range",
				(layer, note) -> layer.pitched() && !note.isBuildable(), true);
			case SELECT_OVERLOADED_CHORDS -> selectOverloadedChordNotes();
			case SELECT_ALL_NOTES -> selectNotesWhere("selected", note -> true, false);
			case SELECT_NONE -> dropSelection();
		}
	}

	/**
	 * Puts down whatever is selected, notes before layers.
	 *
	 * <p>Two selections live on this screen and only one of them had a way to be cleared from the
	 * keyboard. They are not equals: a note selection is what the next edit acts on, and a layer
	 * selection is where you are working -- so the first press drops the notes and leaves you in the
	 * layer, and only a second one steps out of the layer as well.</p>
	 *
	 * <p>One ladder, and every way out walks it: Escape, Ctrl+Shift+A, and the Select menu's None
	 * all take the same step from wherever you are. Which key you reached for should not change what
	 * one press does.</p>
	 *
	 * @return whether anything was actually stepped out of or put down
	 */
	private boolean dropSelection() {
		// Out of the panel first, because holding the keyboard is the innermost thing to be out of.
		// It is also the one step that costs nothing to take: the layers stay picked, so a press
		// spent on it is a press and not a mistake. The same step the blank space under the rows
		// takes when it is clicked.
		if (focusedPane == Pane.LAYERS) {
			focusedPane = Pane.ROLL;
			return true;
		}
		if (!selectedNotes.isEmpty() || hasRange()) {
			selectedNotes.clear();
			clearRange();
			contextMenuOpen = false;
			updateButtonStates();
			return true;
		}
		if (!selectedLayers.isEmpty()) {
			clearLayerSelection();
			return true;
		}
		return false;
	}

	/**
	 * The nearest tempo at which the song's own spacing is a whole number of repeater ticks.
	 *
	 * <p>Measured off the notes, not off a grid chosen elsewhere. It used to take the MIDI import
	 * quantize setting and force one step of that to be at least one repeater tick, whether or not
	 * any two notes in the song were ever that close -- so a song already sitting on the repeater
	 * grid got slowed by up to nine times and came back with hundreds of gaps it did not have
	 * before. On the same songs it is now a no-op, which is the right answer for something already
	 * aligned.</p>
	 */
	/**
	 * Makes the speed the tempo, and the slider a ratio of the new one.
	 *
	 * <p>Only reachable inside Convert until now, which also quantizes, transposes, splits layers
	 * and moves the end marker. Wanting the baseline written down is not wanting any of that.</p>
	 */
	private void bakeSpeed() {
		if (project().speedQuarters() == ComposerProject.DEFAULT_SPEED_QUARTERS) {
			return;
		}
		String was = tempoLabel();
		apply("apply the speed to the tempo", project().withBakedSpeed());
		// setScale moves the widget without firing its listener, so this cannot loop back into
		// another history entry.
		delayScaleSlider.setScale(ComposerProject.DEFAULT_SPEED_QUARTERS);
		showResult(Component.literal(was + " is now " + tempoLabel()
			+ ". The song sounds exactly as it did; only the number it is written at has moved."));
	}

	private void snapTempo(boolean gameTicks) {
		ComposerProject baked = project().withBakedSpeed();
		ComposerProject.NoteSpacing spacing = baked.noteSpacing();
		if (spacing.gridTicks() <= 0L) {
			showResult(Component.literal("Not enough notes to work out a spacing."));
			return;
		}
		int tempo = baked.alignedTempoFor(
			(int)Math.min(Integer.MAX_VALUE, spacing.gridTicks()), gameTicks);
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
		applyTimingStep("Tempo snapped", "snap the tempo", snapped);
	}

	private void quantizeTo(int gridTicks) {
		applyStep("Quantized", "quantize to " + conversionGridLabel(gridTicks),
			project().withQuantized(gridTicks, selectedNotes));
	}

	/**
	 * Puts note starts on the grid redstone counts in, and says which grid that was.
	 *
	 * <p>Worth reporting rather than doing quietly: the grid is whatever the tempo and speed make
	 * it, so it is routinely something like 330 ticks that no musical grid would ever offer, and
	 * how many notes it folded together is the thing to listen for afterwards.</p>
	 */
	private void quantizeToBuildTicks(boolean gameTicks) {
		ComposerProject.RepeaterQuantize result =
			project().withQuantizedToBuildTicks(selectedNotes, gameTicks);
		if (result.project().equals(project())) {
			showResult(Component.literal(
				"Already on the " + (gameTicks ? "game-tick" : "repeater") + " grid."));
			return;
		}
		int merged = distinctStartTicks(project()) - distinctStartTicks(result.project());
		apply("quantize to the " + (gameTicks ? "game-tick" : "repeater") + " grid",
			result.project());
		layersChanged();
		String report = String.format(java.util.Locale.ROOT,
			"Quantized to %d ticks (%d %s tick%s)%s%s",
			result.gridTicks(), result.repeaterTicks(), gameTicks ? "game" : "repeater",
			result.repeaterTicks() == 1 ? "" : "s",
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
	private void applyTimingStep(String label, String step, ComposerProject updated) {
		applyStep(label, step, updated);
		if (delayScaleSlider != null) {
			delayScaleSlider.setScale(delayScaleQuarters());
		}
	}

	/**
	 * Runs one conversion step on its own, so the preset does not have to be taken wholesale.
	 *
	 * @param label how the result reads in the toast, past tense: "Merged 12 selected notes"
	 * @param step how the step reads after "Undo", present tense: "Undo merge repeated notes"
	 */
	private void applyStep(String label, String step, ComposerProject updated) {
		int before = project().noteCount();
		int beforeTempo = project().tempoMicrosPerQuarter();
		if (updated.equals(project())) {
			showResult(Component.literal("Nothing to change."));
			return;
		}
		int scoped = selectedNotes.size();
		apply(step, updated);
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
		selectNotesWhere(label, (layer, note) -> match.test(note), narrowExisting);
	}

	/** For the one question whose answer depends on what the layer's voice is. */
	private void selectNotesWhere(
		String label,
		java.util.function.BiPredicate<Layer, NoteEvent> match,
		boolean narrowExisting
	) {
		Set<Long> previous = Set.copyOf(selectedNotes);
		boolean narrowing = narrowExisting && !previous.isEmpty();
		selectedNotes.clear();
		// Nothing chosen by a rule has a passage behind it. These pick notes wherever in the song
		// they happen to be, so a range left over from a box drag would be describing a stretch of
		// time that has nothing to do with what is now selected.
		clearRange();
		for (int layerIndex : selectionLayers()) {
			Layer layer = project().layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (match.test(layer, note) && (!narrowing || previous.contains(note.id()))) {
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
		clearRange();
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
			case UNDO -> (history.undoLabel() == null
				? "Nothing to step back to. "
				: "Steps back, taking back \"" + history.undoLabel() + "\". ")
				+ "History is kept for this visit only, not across sessions.";
			case REDO -> history.redoLabel() == null
				? "Nothing to step forward to."
				: "Steps forward again, putting back \"" + history.redoLabel() + "\".";
			case TRANSPOSE_BEST_FIT -> "Moves the whole song up or down by semitones until as little "
				+ "of it as possible falls outside the note block's two octaves. Every interval "
				+ "survives exactly -- it is the same tune in a different key -- so what conversion "
				+ "has left to do afterwards is that much less octave-jumping. The top note sounding "
				+ "at any moment counts triple, so the window goes where the melody is rather than "
				+ "where the most notes are. Greyed out when nothing beats where the song already "
				+ "sits.";
			case CONVERT -> "Runs every fix in order: bake the speed into the tempo and reset the "
				+ "slider to 1.00x; collapse same-pitch repeats closer than the merge window; "
				+ "quantize note starts onto the chosen grid; octave-shift out-of-range notes in, "
				+ "splitting a layer per shift it needs; snap the tempo so the grid lands on whole "
				+ "repeater ticks; snap the end marker to match.";
			case CONVERT_GAME_TICKS -> "The same conversion, aimed at game ticks instead of "
				+ "repeater ticks. A game tick is half a repeater tick, so the grid the notes land "
				+ "on is half as coarse and the tempo moves at most half as far to reach it -- a "
				+ "song that had to be slowed or swung to fit often needs neither. The build then "
				+ "needs the Half-tick lane layout, which plays the even game ticks down one lane "
				+ "and the odd ones down a second beside it. The status bar says how many lanes a "
				+ "song wants once this has run.";
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
			case QUANTIZE_GAME_TICKS -> "The same, on the grid two lanes can reach: half the step, "
				+ "so half the worst a note has to move, and notes a single game tick apart stay "
				+ "apart instead of folding together. Costs the second lane -- anything landing "
				+ "between repeater ticks needs the Half-tick lane layout to play it.";
			case FIT_ALL_RANGE -> "Octave-shifts notes outside F#3-F#5 into it. Quick rather than "
				+ "faithful: intervals across a layer can change.";
			case SNAP_TEMPO -> "Moves the tempo as little as it can while making the spacing the "
				+ "song already has land on whole repeater ticks, folding the speed slider in first. "
				+ "Leaves every note where it is, so it does nothing for a song whose notes share no "
				+ "usable grid -- it says so rather than dragging the tempo down to meet them.";
			case SNAP_TEMPO_GAME -> "The same, aimed at game ticks. Half the unit means half the "
				+ "distance the tempo ever has to move: a spacing that sits a quarter of a repeater "
				+ "tick off costs a fifth of the song's speed to snap, and a tenth of it here. "
				+ "Wants the Half-tick lane layout for the result.";
			case SNAP_END -> "Moves the end marker so its trailing delay is a whole number of "
				+ "repeater ticks.";
			case TRIM_END -> "Pulls the end marker back to the last note, discarding trailing "
				+ "silence.";
			case PASTE_IN_WORLD -> "Builds the sequence with /setblock. Needs permission, and "
				+ "overwrites whatever is standing there.";
			case BUILD_CANCEL -> "Stops a paste part-way. Blocks already placed stay put.";
			case TOGGLE_DEDUPE -> "When two included layers ask for the same instrument and pitch at "
				+ "the same tick, build it once. Preview has always collapsed these, so they are "
				+ "inaudible either way, but each costs a note block and one of the thirty a tick "
				+ "can carry. Nothing is deleted: give one of those layers a different instrument "
				+ "and both notes come back.";
			case SELECT_OFF_GRID -> "Selects the notes that do not stand on a game tick, counting "
				+ "from the first note in the song. These are the ones a build cannot place where "
				+ "they are written, and the ones the grid lines are drawn to show.";
			case SELECT_HALF_TICKED -> "Selects the notes that land between repeater ticks -- the "
				+ "ones a single chain cannot place, and so the reason a song needs two lanes. Not "
				+ "faults: a build of two lanes plays them exactly. Worth seeing when you would "
				+ "rather nudge a handful of notes than carry a second lane for them.";
			case SELECT_TOO_FREQUENT -> "Selects notes arriving less than one repeater tick after "
				+ "the previous one -- faster than redstone can retrigger.";
			case SELECT_OUT_OF_RANGE -> "Selects notes outside the note-block range of F#3-F#5.";
			case SELECT_OVERLOADED_CHORDS -> "Selects the notes worth least in every chord bigger "
				+ "than the thinning target, so you can hear the song without them before deleting. "
				+ "Never the last of a pitch or the last of an instrument, so a chord keeps its "
				+ "harmony and keeps its drum -- only how thickly they are scored changes. Takes "
				+ "from the selected layers only; select them all to thin the whole song.";
			case BAKE_SPEED -> "Folds the Speed slider into the song's own tempo and puts the slider "
				+ "back to 1.00x. Nothing about the song changes -- 150 BPM at 2.00x and 300 BPM at "
				+ "1.00x are the same song, note for note -- but the tempo written in the file becomes "
				+ "the tempo it actually plays at, and the slider is free to be a ratio of the new one. Convert does this first thing; this is that step on its own.";
			case DUPLICATE_SELECTION -> SELECTION_RANGE
				? "Lays the selected notes down again directly after themselves, and leaves the "
					+ "selection on the copy -- so Ctrl+D again adds another repeat. How far each "
					+ "one steps is the selection range drawn under the ruler, which a box drag "
					+ "leaves behind and either end of which can be dragged. Every note stays on "
					+ "its own layer."
				: "Lays the selected notes down again after themselves, and leaves the selection on "
					+ "the copy -- so Ctrl+D again adds another repeat. How far each one steps is "
					+ "the block the passage and the time marker make together, so the silence "
					+ "between them is repeated along with the notes: park the marker a beat before "
					+ "a phrase and every copy keeps that beat. The marker steps on with them. "
					+ "Every note stays on its own layer.";
			case ADD_MARKER -> "Puts a marker where the playback marker is standing, or takes away "
				+ "the one already there. M does the same thing. A marker names a position and nothing "
				+ "else: it is not built and it makes no sound.";
			case RENAME_MARKER -> "Renames the marker the playback marker is standing on. "
				+ "Double-clicking its label in the strip above the ruler does the same thing.";
			case CLEAR_MARKERS -> "Removes every marker, and with them the strip they are drawn in. "
				+ "Ctrl+Z puts them back.";
			case SELECT_ALL_NOTES -> "Selects every note on the active layers.";
			case SELECT_NONE -> "One step out of wherever you are. Working in the layer panel, that "
				+ "is the keyboard coming back to the roll with the layers still picked; on the roll "
				+ "it is the selected notes, and then the selected layers. Escape walks the same "
				+ "steps and then closes the composer.";
		};
	}

	private static String layerActionTooltip(LayerAction action) {
		return switch (action) {
			case RENAME -> "Renames this layer. Double-clicking its name does the same thing.";
			case DUPLICATE -> "Copies this layer, notes and all, into a new one directly below it, "
				+ "and selects the copy. The usual reason is to double a part on a second instrument, "
				+ "so the copy is where the change goes. With several layers selected it copies all "
				+ "of them, each copy under its own original. Ctrl+D does the same while this panel "
				+ "has the keyboard.";
			case MOVE_UP -> "Moves this layer one row up. With several selected they move together "
				+ "as a block, keeping their order. Dragging a row by its name does the same thing.";
			case MOVE_DOWN -> "Moves this layer one row down. With several selected they move "
				+ "together as a block, keeping their order.";
			case MOVE_NOTES_HERE -> "Moves the notes selected in the roll onto this layer, out of "
				+ "whichever layers they are on now. Ctrl+1 to Ctrl+0 do the same for the first ten "
				+ "layers.";
			case MERGE_SELECTED -> "Folds the selected layers into the lowest-numbered one, which "
				+ "keeps its name and instrument -- so merging across two instruments gives every "
				+ "note the surviving one. Ctrl+E does the same thing.";
			case DELETE_SELECTED -> "Removes the selected layers and every note on them. Deleting all "
				+ "of them leaves one empty layer to work in. Ctrl+Z puts them back, and so does "
				+ "Ctrl+V if you took them with Ctrl+X. Delete does this while the panel has the "
				+ "keyboard, which is what the brighter highlight on these rows means.";
			case SNAP_TO_START -> "Pulls the selected layers forward until the first of them plays "
				+ "on tick zero, taking the silence an import left at the front off the build. They "
				+ "all move by the same amount, so parts that did not start together still do not. "
				+ "Select one layer to move that one alone.";
			case SELECT_ALL -> "Selects every layer.";
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
			case SELECT_ALL_NOTES -> "Ctrl+A";
			case SELECT_NONE -> "Ctrl+Shift+A";
			case ADD_MARKER -> "M";
			case DUPLICATE_SELECTION -> "Ctrl+D";
			default -> "";
		};
	}

	private String toolbarRowLabel(ToolbarAction action) {
		// The menu row names the step the way every other editor does, so the answer to "what will
		// Ctrl+Z do" is on screen before it is pressed rather than only in the toast afterwards.
		// Clipped to about what the menu's own longest caption already costs, because this row's
		// caption changes with every edit and a menu that resizes as you work is worse than a short
		// label. The whole name is in the row's description, and in the toast the press produces.
		if (action == ToolbarAction.UNDO || action == ToolbarAction.REDO) {
			String step = action == ToolbarAction.UNDO ? history.undoLabel() : history.redoLabel();
			return step == null ? action.label : action.label + " " + clipped(step, 16);
		}
		if (action == ToolbarAction.TOGGLE_DEDUPE) {
			return action.label + ": " + (config.dedupeIdenticalNotes() ? "On" : "Off");
		}
		return action.label;
	}

	/** {@code text}, cut to {@code characters} with an ellipsis if it did not fit. */
	private static String clipped(String text, int characters) {
		return text.length() <= characters ? text : text.substring(0, characters - 1) + "...";
	}

	/** "this layer" reads better than "these 1 layer", and the count matters at any size. */
	private static String layerCountLabel(int count) {
		return count == 1 ? "this layer" : "these " + count + " layers";
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

	/** Whichever tab is showing: the tuned instruments, or the blocks that sound for themselves. */
	private List<PreviewInstrument> instrumentMenuPalette() {
		return instrumentMenuEffects ? PreviewInstrument.EFFECTS : PreviewInstrument.VALUES;
	}

	/** The palette's box, shared by drawing, hit testing and "is the cursor over it". */
	private NoteRect instrumentMenuRect() {
		if (instrumentMenuLayer < 0 || instrumentMenuLayer >= project().layers().size()) {
			return null;
		}
		int rows = (instrumentMenuPalette().size() + INSTRUMENT_COLUMNS - 1) / INSTRUMENT_COLUMNS;
		int menuWidth = INSTRUMENT_COLUMNS * INSTRUMENT_CELL + 6;
		int menuHeight = INSTRUMENT_HEADER + rows * INSTRUMENT_CELL + 6 + INSTRUMENT_FOOTER;
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
		extractInstrumentTabs(graphics, menu, mouseX, mouseY);
		PreviewInstrument selected = PreviewInstrument.byId(project().layers().get(instrumentMenuLayer).instrument());
		List<PreviewInstrument> palette = instrumentMenuPalette();
		for (int index = 0; index < palette.size(); index++) {
			PreviewInstrument value = palette.get(index);
			int cellX = menu.left() + 3 + index % INSTRUMENT_COLUMNS * INSTRUMENT_CELL;
			int cellY = instrumentMenuGridTop(menu) + index / INSTRUMENT_COLUMNS * INSTRUMENT_CELL;
			boolean hovered = mouseX >= cellX && mouseX < cellX + INSTRUMENT_CELL
				&& mouseY >= cellY && mouseY < cellY + INSTRUMENT_CELL;
			graphics.fill(cellX, cellY, cellX + INSTRUMENT_CELL - 2, cellY + INSTRUMENT_CELL - 2,
				value.equals(selected) ? 0xFF356070 : hovered ? 0xFF44484F : 0xFF25282D);
			graphics.item(new ItemStack(value.icon()), cellX + 5, cellY + 5);
			if (hovered) {
				// Effects carry how far they reach. The pitched half is every one of them 48, so
				// saying so on twenty cells would be twenty copies of one fact.
				graphics.setTooltipForNextFrame(
					Component.literal(value.pitched() ? value.name() : value.label()), mouseX, mouseY);
			}
		}
		// Whose instrument is about to change. Picking one has always landed on the whole selection,
		// and nothing on screen said so -- from a palette that looks like it belongs to the one row
		// it opened under, changing five layers at once is indistinguishable from a bug.
		int landing = layersToEdit(instrumentMenuLayer).size();
		graphics.text(font,
			landing > 1
				? "Sets all " + landing + " selected layers"
				: "Sets layer " + (instrumentMenuLayer + 1),
			menu.left() + 4, menu.bottom() - INSTRUMENT_FOOTER + 2,
			landing > 1 ? 0xFF8FD3FF : 0xFF8A9098, false);
	}

	/** Where the grid starts, once the tabs above it have had their strip. */
	private static int instrumentMenuGridTop(NoteRect menu) {
		return menu.top() + 3 + INSTRUMENT_HEADER;
	}

	/** Where one tab ends and the other begins. */
	private static int instrumentTabSplit(NoteRect menu) {
		return (menu.left() + menu.right()) / 2;
	}

	private void extractInstrumentTabs(GuiGraphicsExtractor graphics, NoteRect menu, int mouseX, int mouseY) {
		int split = instrumentTabSplit(menu);
		int top = menu.top() + 1;
		int bottom = top + INSTRUMENT_HEADER - 2;
		boolean overStrip = mouseY >= top && mouseY < bottom && mouseX >= menu.left() && mouseX < menu.right();
		drawInstrumentTab(graphics, menu.left() + 1, top, split, bottom, "Instruments",
			!instrumentMenuEffects, overStrip && mouseX < split);
		drawInstrumentTab(graphics, split, top, menu.right() - 1, bottom, "Sound effects",
			instrumentMenuEffects, overStrip && mouseX >= split);
	}

	private void drawInstrumentTab(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom,
			String label, boolean current, boolean hovered) {
		graphics.fill(left, top, right, bottom, current ? 0xFF356070 : hovered ? 0xFF44484F : 0xFF1B1E22);
		graphics.text(font, Component.literal(label),
			left + Math.max(2, (right - left - font.width(label)) / 2), top + 2,
			current ? 0xFFFFFFFF : 0xFF9AA0A8, false);
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
		// Where the blank run under the last row would be drawn, which decides whether it can say the
		// mode itself. Settled before anything is drawn because the header is drawn first and only
		// stands in when the run is out of sight.
		int blankY = layerY(project().layers().size()) + 3;
		boolean blankRunVisible = row.name()
			&& blankY >= LAYER_LIST_TOP && blankY < layerListBottom() - 6;
		if (row.name()) {
			graphics.text(font, "Layers", row.inset() + 10, TOOLBAR_HEIGHT + 4, 0xFF8A9098, false);
			// "Layers - none" read as "this song has no layers", which is a different and much more
			// alarming sentence. The mode gets a line of its own, and only when the run at the
			// bottom is scrolled out of reach and cannot carry it.
			if (noLayerSelected() && !blankRunVisible) {
				int from = row.inset() + 14 + font.width("Layers");
				smallText(graphics, smallFit("no layer selected", layerPanelWidth() - from - 4),
					from, TOOLBAR_HEIGHT + 5, 0xFF7FB6D8);
			}
		}
		graphics.enableScissor(0, LAYER_LIST_TOP - 2, layerPanelWidth(), layerListBottom());
		for (int index = 0; index < project().layers().size(); index++) {
			int y = layerY(index);
			int rowHeight = LAYER_ROW_HEIGHT;
			if (y + rowHeight < LAYER_LIST_TOP - 2 || y > layerListBottom()) {
				continue;
			}
			boolean activeLayer = index == editingLayerIndex();
			boolean selected = selectedLayers.contains(index);
			// The stripe carries the same answer the roll's notes carry, so the panel and the roll
			// agree about which colours are yours -- a layer whose notes are dimmed out there should
			// not be flying a full-strength flag in here, and one lit out there should not be dim.
			int color = layerLit(index) ? vivid(layerColor(index)) : faded(layerColor(index));
			int left = row.inset();
			int right = layerPanelWidth() - rightGutter(row.inset());
			// Two saturations of one highlight rather than a third colour. The row already carries
			// two states -- active, which is where a new note lands, and selected, which is what a
			// layer action acts on -- and a panel that does not hold the keyboard draws both of them
			// muted. That is the file-manager convention, and it says "these are still selected, and
			// Delete will not reach them" without needing a fourth thing to learn.
			boolean lit = focusedPane == Pane.LAYERS;
			graphics.fill(left, y - 2, right, y + rowHeight - 2,
				activeLayer ? (lit ? 0xAA4C6D82 : 0x66343C44)
					: selected ? (lit ? 0xAA3C5266 : 0x552E3740) : 0x33202429);
			if (selected) {
				// Unmistakable outline: a tinted background alone reads as noise on a dark panel.
				int edge = activeLayer
					? (lit ? 0xFFAEE2FF : 0xFF6E7A83)
					: (lit ? 0xFF6FA8CC : 0xFF566068);
				graphics.fill(left, y - 2, right, y - 1, edge);
				graphics.fill(left, y + rowHeight - 3, right, y + rowHeight - 2, edge);
				graphics.fill(right - 1, y - 2, right, y + rowHeight - 2, edge);
			}
			graphics.fill(left, y - 2, right, y - 1, activeLayer ? color : 0x66383D44);
			graphics.fill(left, y + rowHeight - 3, right, y + rowHeight - 2,
				activeLayer ? color : 0x88383D44);
			// The colour stripe is how a layer is recognised once its name is gone, so it stays at
			// every width -- and it is where the row number lives, because the number is the other
			// answer to "which layer is this" and the two belong together. Black or white over it
			// depending on how light the colour underneath came out, since the palette runs the
			// whole way round the wheel and one ink cannot be read on all of it.
			graphics.fill(left, y - 2, left + row.stripe(), y + rowHeight - 2, color);
			String ordinal = Integer.toString(index + 1);
			smallText(graphics, ordinal,
				left + (row.stripe() - smallTextWidth(ordinal) + 1) / 2, y + 5,
				luma(color) > STRIPE_DARK_INK_ABOVE ? 0xFF101318 : 0xFFF2F5F8);
			if (activeLayer) {
				graphics.fill(left + row.stripe(), y, right - 2, y + rowHeight - 4, 0x553D444D);
			}
			Layer layer = project().layers().get(index);
			LayerState state = layerState(index);
			if (row.chip()) {
				// Drawn as a bordered chip with a letter in it. Bare symbols read as decoration on a
				// row that is mostly decoration already, and this one is the layer's only switch.
				int chipLeft = row.stateX();
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
			// Whether you will hear this layer, marked on the thing that makes the sound -- and the
			// one part of a row that survives every width, so a folded panel still answers it. A
			// layer another layer's solo has quieted gets a fainter slash than one you muted
			// yourself: the same answer to "will I hear it", a different answer to "who decided".
			boolean soloElsewhere = state == LayerState.ACTIVE && !soloedLayers.isEmpty();
			if (state == LayerState.MUTED || state == LayerState.HIDDEN || soloElsewhere) {
				slashInstrument(graphics, row.instrumentX(), y - 1,
					soloElsewhere ? 0x88E0544F : 0xFFE0544F);
			}
			if (row.name()) {
				// The name and nothing else. A tick in front of it said what the row's own outline,
				// tint and stripe already say three times over, and it said it in the pixels the
				// name wanted; the note count moved out to a column of its own.
				smallText(graphics, smallFit(layer.name(), row.nameRight() - row.nameLeft()),
					row.nameLeft(), y + 5,
					activeLayer ? 0xFFFFFFFF : selected ? 0xFFE8F4FF : 0xFFD6D8DD);
			}
			if (state == LayerState.HIDDEN) {
				// Hidden is muted and also gone from the roll, so it gets the slash and then the row
				// dimmed over it. Two marks for two facts, which is what makes them read together:
				// slashed is silent, grey is not in the roll, and hidden is both.
				//
				// Stopping short of the build dot on purpose. Hiding a layer does not take it out of
				// the build, and greying the one control that says so would claim that it had. The
				// colour stripe survives for the same reason it survives every width -- once a layer
				// is dim and nameless the stripe is what is left to recognise it by.
				// Inside the row's own border, so that a hidden layer you have selected still shows
				// the outline saying so.
				graphics.fill(left + row.stripe(), y - 1,
					row.ordinalLeft() - 2, y + rowHeight - 3, 0xAA0E1014);
			}
			// How much is on the layer, out at the edge in the smallest thing that can still be
			// read. It used to be in brackets after the name, where it was the first thing a narrow
			// panel dropped and the last thing anyone wanted truncated into.
			if (row.count()) {
				String count = Integer.toString(layer.notes().size());
				smallText(graphics, count, row.ordinalRight() - smallTextWidth(count), y + 5,
					0xFF71767E);
			}
		}
		// The empty run under the last row is a control, and empty panel does not look like one. So
		// it is labelled, quietly, exactly where the click that uses it lands.
		if (blankRunVisible) {
			smallText(graphics,
				smallFit(noLayerSelected() ? "no layer selected" : "click here: no layer",
					layerPanelWidth() - 2 * row.inset() - 4),
				row.inset() + 3, blankY, noLayerSelected() ? 0xFF7FB6D8 : 0xFF565B63);
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
		if (stateLayer >= 0) {
			text = Component.literal(layerStateTooltip(stateLayer));
		} else if (instrumentLayer >= 0) {
			// The slash is drawn on this icon, so this is where someone points to ask about it.
			String silence = audibilityNote(instrumentLayer);
			int landing = layersToEdit(instrumentLayer).size();
			PreviewInstrument voice =
				PreviewInstrument.byId(project().layers().get(instrumentLayer).instrument());
			text = Component.literal(
				(voice.pitched() ? voice.name() : voice.label())
					+ (voice.pitched() ? " - click to change the note-block instrument"
						: " - click to change the voice")
					+ (landing > 1 ? "\nPicks land on all " + landing + " selected layers." : "")
					+ (silence == null ? "" : "\n" + silence));
		}
		if (text != null) {
			graphics.setTooltipForNextFrame(font, font.split(text, 200), x, y);
		}
	}

	/**
	 * What the state chip says, what the next click would make it, and what it will never touch.
	 *
	 * <p>It used to name the four letters and leave you to work out which way round they went. The
	 * four are a dial, so the useful thing while pointing at one is where a click lands, not a list
	 * of the other three. The last line is there because a row has two switches and mistaking them
	 * is how a layer that sounded right in the composer goes missing from the build.</p>
	 */
	private String layerStateTooltip(int index) {
		LayerState[] dial = LayerState.values();
		LayerState state = layerState(index);
		String silence = state == LayerState.ACTIVE ? audibilityNote(index) : null;
		return state.sentence()
			+ (silence == null ? "" : "\n" + silence)
			+ "\n\nClick for " + dial[Math.floorMod(state.ordinal() + 1, dial.length)].title
			+ ", right-click for " + dial[Math.floorMod(state.ordinal() - 1, dial.length)].title + "."
			+ "\nNone of the four decide what gets built. That is the dot at the end of the row.";
	}

	/**
	 * Why this layer is quiet, or null when it is not.
	 *
	 * <p>Includes the case the chip cannot show: a layer nobody muted, silent because something
	 * else is soloed. That is the one a player is most likely to be confused by, since its own
	 * letter still reads A.</p>
	 */
	private String audibilityNote(int index) {
		LayerState state = layerState(index);
		if (state == LayerState.MUTED || state == LayerState.HIDDEN) {
			return state.sentence();
		}
		if (state != LayerState.ACTIVE || soloedLayers.isEmpty()) {
			return null;
		}
		return soloedLayers.size() == 1
			? "Silent right now: layer " + (soloedLayers.iterator().next() + 1) + " is soloed."
			: "Silent right now: " + soloedLayers.size() + " layers are soloed.";
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
		int menuWidth = menuWidth(rows, TOOLBAR_MENU_WIDTH, toolbarMenuX);
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
	/**
	 * A red diagonal across a sixteen-pixel instrument icon: this layer is making no sound.
	 *
	 * <p>Stepped squares because there is no line to draw with here, and a dark square behind every
	 * red one because an item icon is a picture and a bare red diagonal vanishes into the ones with
	 * red in them. Fourteen quads a row, only for rows that are actually silent and only for rows
	 * the list is showing.</p>
	 */
	private void slashInstrument(GuiGraphicsExtractor graphics, int x, int y, int color) {
		for (int step = 0; step < 7; step++) {
			int px = x + 1 + step * 2;
			int py = y + 13 - step * 2;
			graphics.fill(px, py + 1, px + 3, py + 4, 0xAA05070A);
			graphics.fill(px, py, px + 3, py + 3, color);
		}
	}

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
	/**
	 * A colour per voice, arranged so the octave a voice sounds in is the hue.
	 *
	 * <p>Colouring by position said nothing, and colouring by which part a layer was split from said
	 * something true and useless -- on a composition of thirty layers the question being asked is
	 * "I can hear that line, which row is it", and the answer anyone has is what it sounds like. A
	 * note block's instrument is the block under it and the octave it plays in, and the octave is
	 * the half you can hear from across the room.</p>
	 *
	 * <p>So the ramp runs cold to warm as the voice runs high to low: ice blue two octaves up, mint
	 * one up, green in the middle, amber one down, red two down. Within a band each voice takes its
	 * own place along the hue, near its neighbours and not on them, and alternates a shade lighter
	 * so that five greens are five greens rather than one.</p>
	 *
	 * <p>The two families off the ramp are off it for a reason. Percussion has no pitch to place, so
	 * it sits in violet where it cannot be mistaken for a melody. The trumpets are one voice at four
	 * ages and read as a family in copper; where they sound relative to a harp is not something
	 * worth guessing at, and grouping them by a guess would be worse than grouping them by what
	 * they are.</p>
	 */
	private static final Map<String, Integer> INSTRUMENT_COLORS = Map.ofEntries(
		// Two octaves up.
		Map.entry("BELL", 0xFF6DD5E3),
		Map.entry("CHIME", 0xFF90C9EA),
		Map.entry("XYLOPHONE", 0xFF6D9BE3),
		// One octave up.
		Map.entry("FLUTE", 0xFF6DE3AA),
		Map.entry("COW_BELL", 0xFF90EAD3),
		// The middle, where a harp plays.
		Map.entry("HARP", 0xFFD2E36D),
		Map.entry("IRON_XYLOPHONE", 0xFFC9EA90),
		Map.entry("BIT", 0xFF9EE36D),
		Map.entry("BANJO", 0xFFA2EA90),
		Map.entry("PLING", 0xFF6DE36F),
		// One octave down.
		Map.entry("GUITAR", 0xFFE3C66D),
		// Two octaves down.
		Map.entry("BASS", 0xFFE36D6D),
		Map.entry("DIDGERIDOO", 0xFFEAA890),
		// Copper, four ages of one voice.
		Map.entry("TRUMPET", 0xFFE36DE3),
		Map.entry("TRUMPET_EXPOSED", 0xFFEA90DB),
		Map.entry("TRUMPET_WEATHERED", 0xFFE36DBC),
		Map.entry("TRUMPET_OXIDIZED", 0xFFEA90BD),
		// No pitch to place, so off the ramp entirely.
		Map.entry("BASEDRUM", 0xFF796DE3),
		Map.entry("SNARE", 0xFFB190EA),
		Map.entry("HAT", 0xFFB86DE3));

	private int[] layerColors() {
		ComposerProject current = project();
		if (cachedColorProject == current && cachedLayerColors != null) {
			return cachedLayerColors;
		}
		List<Layer> layers = current.layers();
		Map<String, Integer> members = new java.util.LinkedHashMap<>();
		int[] colors = new int[layers.size()];
		for (int index = 0; index < layers.size(); index++) {
			String instrument = layers.get(index).instrument();
			// Two layers on one voice still have to be told apart, and the commonest reason for two
			// is Convert splitting a part into the octaves it needed -- which is the one case where
			// they ought to read as relatives rather than as strangers. One hue, stepped.
			int member = members.merge(instrument, 1, Integer::sum) - 1;
			colors[index] = shade(instrumentColor(instrument), member);
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

	/**
	 * The colour a voice is drawn in, or a rotation of the old palette for one nothing knows about.
	 *
	 * <p>The fallback is for a song written by a later version of the mod than the one reading it.
	 * An instrument missing from the table still gets a colour, chosen off its own name, so it is
	 * at least the same colour every time that song is opened.</p>
	 */
	private static int instrumentColor(String instrument) {
		Integer known = INSTRUMENT_COLORS.get(instrument);
		return known != null ? known
			: LAYER_COLORS[Math.floorMod(instrument.hashCode(), LAYER_COLORS.length)];
	}

	/**
	 * Steps a colour away from its base so members of one family stay apart.
	 *
	 * <p>Alternating darker and lighter rather than only fading: the colour is a four-pixel strip on
	 * a near-black panel, and four steps of darkening ends at something indistinguishable from the
	 * background.</p>
	 */
	static int shade(int color, int step) {
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

	/**
	 * A layer's colour as it reads when the layer is not selected: still its own, and out of the way.
	 *
	 * <p>Unselected layers used to be drawn in one flat grey, which answered "not this one" and threw
	 * away the only thing that says <em>which</em> one -- on a song of twelve parts the whole
	 * background became a single colour, so working on one layer meant losing track of where every
	 * other one was. Mixed toward a neutral rather than dimmed, because dimming alone leaves the
	 * bright hues (yellow, cyan) still reading as foreground while the dark ones vanish; a mix lands
	 * every hue at the same weight, which is the point of a background.</p>
	 *
	 * <p>The gap between this and {@link #vivid(int)} is what answers "is that note mine", and it
	 * has to survive a roll zoomed out to two-pixel notes with a dozen layers on it. A first pass
	 * mixed 62% toward a lighter grey, which was legible as a distinction when you went looking for
	 * it and not at a glance -- so this goes further and darker, and the selected side goes the
	 * other way, rather than asking one end to carry the whole difference.</p>
	 *
	 * <p>Then held under a ceiling, which is the part that makes it a guarantee rather than a
	 * tendency. {@link #shade} tells family members apart by lightness and this tells selected from
	 * unselected by lightness, and two meanings on one channel collide: on a converted song a family
	 * of four reaches shade's -56% step, and that layer <em>selected</em> came out dimmer than a
	 * pale layer sitting in the background. Measured over the whole palette, six of fifty selected
	 * variants lost to the brightest unselected one. Banding the two ranges cannot regress.</p>
	 */
	static int faded(int color) {
		int dimmed = mix(color, 0xFF4E525A, 0.76);
		double bright = luma(dimmed);
		return bright <= UNSELECTED_LUMA_CEILING
			? dimmed
			: scaled(dimmed, UNSELECTED_LUMA_CEILING / bright);
	}

	/**
	 * A layer's colour as it reads when the layer is selected: the same hue, lifted clear of the dim.
	 *
	 * <p>Lifted by scaling the channels rather than by mixing toward white, which is what keeps the
	 * hue. A darkened family member dragged up to the floor by mixing arrives as a pale grey with a
	 * tint -- cyan lost two thirds of its saturation getting there, and telling layers apart is the
	 * only reason they have colours. Scaling holds the ratios, so it brightens along the hue and only
	 * falls back to mixing once a channel has run out of room at 255.</p>
	 */
	static int vivid(int color) {
		int lifted = mix(color, 0xFFFFFFFF, 0.18);
		double bright = luma(lifted);
		if (bright >= SELECTED_LUMA_FLOOR) {
			return lifted;
		}
		lifted = scaled(lifted, SELECTED_LUMA_FLOOR / Math.max(1.0, bright));
		bright = luma(lifted);
		return bright >= SELECTED_LUMA_FLOOR
			? lifted
			: mix(lifted, 0xFFFFFFFF, Math.min(1.0, (SELECTED_LUMA_FLOOR - bright) / (255.0 - bright)));
	}

	/** Perceived brightness, the thing both {@link #faded} and {@link #vivid} are really about. */
	static double luma(int color) {
		return 0.2126 * (color >> 16 & 0xFF) + 0.7152 * (color >> 8 & 0xFF) + 0.0722 * (color & 0xFF);
	}

	/** Every channel multiplied by {@code factor}, which brightens along the hue instead of across it. */
	private static int scaled(int color, double factor) {
		int out = color & 0xFF000000;
		for (int shift = 16; shift >= 0; shift -= 8) {
			int channel = (int)Math.round((color >> shift & 0xFF) * factor);
			out |= Math.max(0, Math.min(255, channel)) << shift;
		}
		return out;
	}

	/** {@code amount} of {@code toward}, the rest of {@code color}. Alpha comes from {@code color}. */
	private static int mix(int color, int toward, double amount) {
		int mixed = color & 0xFF000000;
		for (int shift = 16; shift >= 0; shift -= 8) {
			int from = (color >> shift) & 0xFF;
			int to = (toward >> shift) & 0xFF;
			int channel = (int)Math.round(from + (to - from) * amount);
			mixed |= Math.max(0, Math.min(255, channel)) << shift;
		}
		return mixed;
	}

	/**
	 * Which way a Shift-held note drag has committed to going.
	 *
	 * <p>The two axes of a piano roll are not two directions, they are two different edits. Across
	 * is when a note plays and down is what note it is, and "move this phrase a beat later" has
	 * nothing to do with "move this phrase up a tone" -- the one thing you never mean is a little of
	 * both. Unconstrained, that is exactly what a diagonal hand produces: you retime a run of notes
	 * and one of them comes to rest a semitone off, in a song of nine thousand, silently.</p>
	 */
	enum DragAxis {
		UNDECIDED,
		TIME,
		PITCH
	}

	/**
	 * The two bands a layer's colour must fall into, so the answer to "is that note mine" is not a
	 * matter of which family member it happens to be.
	 *
	 * <p>Forty-one points of luminance apart, on a scale where the roll's own background sits around
	 * thirty. Both are floors on a whole palette rather than tuned to one colour: see
	 * {@link #faded(int)} for the collision they exist to rule out.</p>
	 */
	/** Above this the stripe is light enough to want dark ink on it, below it light. */
	private static final double STRIPE_DARK_INK_ABOVE = 140.0;
	static final double SELECTED_LUMA_FLOOR = 150.0;
	static final double UNSELECTED_LUMA_CEILING = 108.0;

	/** How finely an eraser sweep is sampled along its path, in pixels. Under a note's width. */
	private static final double ERASE_STEP_PIXELS = 3.0;

	/** How far the cursor must travel before a Shift-held drag will say which way it is going. */
	private static final int DRAG_AXIS_THRESHOLD = 4;

	/**
	 * How far a press may wander and still count as a click rather than a drag.
	 *
	 * <p>Generous, because it is deciding between drawing a note and selecting nothing: a box three
	 * pixels across catches nothing anyway, so nothing is lost by reading it as a click, while a
	 * hand that shifted two pixels on the way down and got no note is a broken editor.</p>
	 */
	private static final int CLICK_SLOP = 3;

	/** Whether the cursor has left the click's slop since the button went down. */
	private boolean travelled(double x, double y) {
		return Math.abs(x - dragStartX) > CLICK_SLOP || Math.abs(y - dragStartY) > CLICK_SLOP;
	}

	/**
	 * Which axis a Shift-held drag is locked to, given how far it has come.
	 *
	 * <p>Decided once and then kept, which is the only part of this with a choice in it. Re-deciding
	 * every frame makes the lock flip back and forth for any drag that runs near the diagonal, and
	 * it flips at the end of a long horizontal drag -- when the hand relaxes -- which is the worst
	 * possible moment. So the first four pixels of travel choose, and the rest of the drag obeys.</p>
	 *
	 * <p>Measured in pixels rather than in ticks and semitones. The question being asked is which
	 * way the hand went, and the hand does not know that a pixel across is four hundred ticks at
	 * this zoom and a pixel down is half a row.</p>
	 */
	static DragAxis lockedAxis(DragAxis current, double acrossPixels, double downPixels) {
		if (current != DragAxis.UNDECIDED
				|| Math.max(acrossPixels, downPixels) <= DRAG_AXIS_THRESHOLD) {
			return current;
		}
		// A tie goes to time, which is the edit people reach for a drag to make.
		return acrossPixels >= downPixels ? DragAxis.TIME : DragAxis.PITCH;
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
		if (layerPanelWidth() < ROW_SCROLLBAR_AT) {
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
			int minorColor = crowdedGridColor(0xFF686D73, minorStep / ticksPerPixel,
				MIN_GRID_PIXEL_SPACING * 2);
			for (long tick = Math.max(0L, horizontalScroll / minorStep * minorStep);
					tick <= lastTick + minorStep; tick += minorStep) {
				int x = tickX(tick);
				if (x >= rollX && x <= rollX + rollWidth && tick % labelStep != 0L) {
					graphics.fill(x, rollY - 6, x + 1, rollY, minorColor);
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
		extractMarkerTabs(graphics, mouseX, mouseY);
		int endX = endMarkerX();
		if (endX >= rollX && endX <= rollX + rollWidth) {
			// Flag points back over the song, so the marker reads as the edge of something rather
			// than the start of it. Red when the trailing gap is not a delay a build can place.
			int endColor = projectStats().endMarkerIssue() ? 0xFFFF6B6B : 0xFFE8C05A;
			graphics.fill(endX, rulerY + 1, endX + 1, rollY, endColor);
			graphics.fill(endX - 7, rulerY + 1, endX, rulerY + 6, endColor);
		}
		long markerTick = playing ? playbackTick() : playbackReturnTick;
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
		if (mouseX >= rollX && mouseX < rollX + rollWidth && mouseY >= rulerY && mouseY < rollY
				&& markerAtPoint(mouseX, mouseY) == null) {
			graphics.setTooltipForNextFrame(Component.literal(overEndMarker(mouseX, mouseY)
				? "Drag to set where the song ends"
				: "Drag to set playback start"), mouseX, mouseY);
		}
	}

	private int markerColor(ComposerProject.Marker marker) {
		int mixed = Long.hashCode(marker.tick()) * 0x9E3779B1;
		mixed ^= mixed >>> 15;
		return MARKER_COLORS[Math.floorMod(mixed, MARKER_COLORS.length)];
	}

	/** The strip of ruler the marker tabs hang in, which is the foot of it. */
	private boolean insideMarkerBand(double x, double y) {
		return x >= rollX && x < rollX + rollWidth
			&& y >= rollY - MARKER_TAB_HEIGHT - 2 && y < rollY;
	}

	/** The marker whose tab a point in that strip is on, or null. */
	private ComposerProject.Marker markerAtPoint(double x, double y) {
		if (!insideMarkerBand(x, y)) {
			return null;
		}
		ComposerProject.Marker nearest = null;
		double best = MARKER_GRAB_PIXELS;
		for (ComposerProject.Marker marker : project().markers()) {
			double distance = Math.abs(x - tickX(marker.tick()));
			if (distance <= best) {
				best = distance;
				nearest = marker;
			}
		}
		return nearest;
	}

	/**
	 * The markers, as a coloured tab each, pointing down at the tick it stands on.
	 *
	 * <p>Drawn before the end marker and the playhead so those win where they overlap: one says
	 * where the song stops and the other says what is sounding, and at the moment you need either
	 * of them it is worth more than a landmark.</p>
	 */
	private void extractMarkerTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		ComposerProject.Marker hovered = overOpenMenu(mouseX, mouseY)
			? null
			: markerAtPoint(mouseX, mouseY);
		int top = rollY - MARKER_TAB_HEIGHT - 1;
		for (ComposerProject.Marker marker : project().markers()) {
			int x = tickX(marker.tick());
			if (x < rollX - MARKER_TAB_HEIGHT || x > rollX + rollWidth + MARKER_TAB_HEIGHT) {
				continue;
			}
			int color = markerColor(marker);
			// A triangle made of rows, narrowing onto the tick. Nothing in this screen draws
			// anything but rectangles, and a shape that points is worth five of them.
			for (int row = 0; row < MARKER_TAB_HEIGHT; row++) {
				int reach = MARKER_TAB_HEIGHT - 1 - row;
				graphics.fill(Math.max(rollX, x - reach), top + row,
					Math.min(rollX + rollWidth, x + reach + 1), top + row + 1, color);
			}
			if (marker == hovered) {
				graphics.fill(Math.max(rollX, x - MARKER_TAB_HEIGHT), top - 1,
					Math.min(rollX + rollWidth, x + MARKER_TAB_HEIGHT), top, 0xFFFFFFFF);
			}
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(Component.literal(
				"\"" + hovered.label() + "\"" + " at bar "
					+ (hovered.tick() / Math.max(1L, project().ppq() * 4L) + 1L)
					+ "\n" + "Click to jump, double-click to rename, right-click to remove"),
				mouseX, mouseY);
		}
	}

	/**
	 * Puts a marker on the playback marker, or takes away the one already there.
	 *
	 * <p>One key for both, because there is no state to get wrong: the cursor either has a marker on
	 * it or it does not, and that is visible before the key is pressed.</p>
	 */
	private void toggleMarkerAtCursor() {
		long tick = playbackReturnTick;
		if (project().markerAt(tick) != null) {
			removeMarkerAt(tick);
			return;
		}
		addMarkerAt(tick);
	}

	private void addMarkerAt(long tick) {
		long at = Math.max(0L, tick);
		if (project().markers().size() >= ComposerProject.MAX_MARKERS) {
			showResult(Component.literal("That is " + ComposerProject.MAX_MARKERS
				+ " markers, which is the limit."));
			return;
		}
		// Named for the bar it lands on, which is the one thing about it that is already true. The
		// name is the point of a marker, so it is a starting value rather than an answer -- but an
		// unnamed flag on a ruler full of bar numbers says nothing at all.
		String label = "Bar " + (at / Math.max(1L, project().ppq() * 4L) + 1L);
		apply("add marker", project().withMarkerAt(at, label));
		showResult(Component.literal("Marker \"" + label
			+ "\" added. Double-click it to rename, right-click it to remove."));
	}

	private void removeMarkerAt(long tick) {
		ComposerProject.Marker marker = project().markerAt(tick);
		if (marker == null) {
			return;
		}
		apply("remove marker", project().withoutMarkerAt(tick));
		showResult(Component.literal("Removed marker \"" + marker.label()
			+ "\". Ctrl+Z puts it back."));
	}

	private void renameMarker(ComposerProject.Marker marker) {
		if (marker == null) {
			return;
		}
		minecraft.gui.setScreen(new NamePromptScreen(this, "Rename marker",
			"Name for the marker at bar "
				+ (marker.tick() / Math.max(1L, project().ppq() * 4L) + 1L),
			marker.label(), "Rename", name -> {
				minecraft.gui.setScreen(this);
				apply("rename marker", project().withMarkerAt(marker.tick(), name.trim()));
				showResult(Component.literal("Marker renamed to \"" + name.trim() + "\"."));
			}));
	}

	/** The marker the menu actions point at: whichever one the playback marker is standing on. */
	private ComposerProject.Marker markerAtCursor() {
		return project().markerNear(playbackReturnTick,
			Math.max(1L, Math.round(MARKER_GRAB_PIXELS * ticksPerPixel)));
	}

	private void clearMarkers() {
		int count = project().markers().size();
		if (count == 0) {
			return;
		}
		apply("clear markers", project().withMarkers(List.of()));
		showResult(Component.literal("Removed " + count
			+ (count == 1 ? " marker" : " markers") + ". Ctrl+Z puts them back."));
	}

	/**
	 * The range across the roll, and its length written inside it.
	 *
	 * <p>Full height and time only: the box's height chose the notes and is finished, while a length
	 * is a horizontal fact. Drawn over the notes rather than under them so that the tint says which
	 * ones are inside it, and the label sits on a plate because it lands in the same corner the bar
	 * numbers are drawn in.</p>
	 */
	private void extractRangeBand(GuiGraphicsExtractor graphics) {
		syncRangeToSelection();
		if (!hasRange()) {
			return;
		}
		int from = tickX(rangeStart + rangeDragDelta());
		int to = tickX(rangeEnd + rangeDragDelta());
		if (to < rollX || from > rollX + rollWidth) {
			return;
		}
		// As tall as what is in it, not as tall as the roll. A column running the full height of the
		// window reads as a mode the whole composition is in; the range is a fact about one passage
		// on a few of its parts, and drawn around those it says so and leaves the rest alone. It
		// falls back to the full height only with no selection left to measure, which is a range
		// about to be put down anyway.
		int top = rangeBandTop();
		int bottom = rangeBandBottom();
		if (bottom <= top) {
			return;
		}
		graphics.fill(Math.max(rollX, from), top, Math.min(rollX + rollWidth, to), bottom,
			0x1444CCFF);
		// The two edges are the handles now. They used to be a bracket in a strip of its own above
		// the roll, which was a second drawing of a thing already drawn -- and once the band was
		// trimmed to its notes there was nothing the bracket said that the band did not. Thicker
		// and brighter under the cursor, because an edge you can take hold of has to look like one.
		int handle = rangeHandleAt(lastMouseX, lastMouseY);
		if (from >= rollX) {
			graphics.fill(from - (handle == 1 ? 1 : 0), top, from + (handle == 1 ? 2 : 1), bottom,
				handle == 1 ? 0xFFCFF3FF : 0x667FD8F0);
		}
		if (to <= rollX + rollWidth) {
			graphics.fill(to - (handle == 2 ? 2 : 1), top, to + (handle == 2 ? 1 : 0), bottom,
				handle == 2 ? 0xFFCFF3FF : 0x667FD8F0);
		}
		if (handle != 0) {
			graphics.setTooltipForNextFrame(Component.literal(
				"Selection range: " + rangeLabel(rangeLength()) + "\nDrag to change it, right-click "
					+ "to drop it. This is how far Ctrl+V and Ctrl+D step, so pull the right-hand "
					+ "end past the last note to leave a rest between repeats."),
				(int)lastMouseX, (int)lastMouseY);
		}
		String label = rangeLabel(rangeLength());
		int labelLeft = Math.max(rollX + 2, from + 3);
		// Above the band where there is room, so it does not sit on the notes it is measuring.
		int labelTop = top - 9 >= rollY ? top - 9 : top + 1;
		if (labelLeft + smallTextWidth(label) + 2 <= Math.min(rollX + rollWidth, to)) {
			graphics.fill(labelLeft - 2, labelTop, labelLeft + smallTextWidth(label) + 2,
				labelTop + 8, 0xCC0E2028);
			smallText(graphics, label, labelLeft, labelTop + 1, 0xFF9FE8FF);
		}
	}

	/** The band's top edge: the highest selected note, or the top of the roll with none left. */
	private int rangeBandTop() {
		int[] pitches = rangePitchExtent();
		return pitches == null ? rollY : Math.max(rollY, noteY(pitches[0]) - 1);
	}

	private int rangeBandBottom() {
		int[] pitches = rangePitchExtent();
		return pitches == null
			? rollY + rollHeight
			: Math.min(rollY + rollHeight, noteY(pitches[1]) + rowHeight);
	}

	/**
	 * The highest and lowest note the range's selection covers, or null when it covers none.
	 *
	 * <p>Bounded by the range at both ends, so this walks the notes inside the passage rather than
	 * the notes in the song. A selection is nearly always one phrase, and a phrase is a few dozen
	 * notes out of several thousand.</p>
	 */
	private int[] rangePitchExtent() {
		if (selectedNotes.isEmpty()) {
			return null;
		}
		// Read off what is on screen rather than off what is saved. Mid-drag those differ: the notes
		// being dragged are drawn from a preview and the composition still holds them where they
		// started, so a band measured against the composition sat at the old pitches until the
		// button came up and then jumped to the new ones.
		ComposerProject shown = displayProject();
		long from = rangeStart + rangeDragDelta();
		long to = rangeEnd + rangeDragDelta();
		int highest = Integer.MIN_VALUE;
		int lowest = Integer.MAX_VALUE;
		for (int layerIndex : selectionLayers()) {
			Layer layer = shown.layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			List<NoteEvent> notes = layer.notes();
			for (int index = lowerBoundStart(notes, from); index < notes.size(); index++) {
				NoteEvent note = notes.get(index);
				if (note.startTick() > to) {
					break;
				}
				if (selectedNotes.contains(note.id())) {
					highest = Math.max(highest, note.midiNote());
					lowest = Math.min(lowest, note.midiNote());
				}
			}
		}
		return highest == Integer.MIN_VALUE ? null : new int[] { highest, lowest };
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
		long now = Util.getMillis();
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
			// The two octaves a note block can play used to be tinted blue, which marked the part of
			// the roll where nothing is wrong -- and on a converted song that is all of it, so the
			// mark was on every row and said nothing. Inverted: the buildable rows are plain and the
			// ones outside carry a red wash. A raw import opens with the reason it will not build
			// visible as a shape, and a song that has been fixed has a clean roll to show for it.
			int gridColor = black ? 0xB9181A20 : 0xB91D2026;
			if (!buildable) {
				gridColor = black ? 0xC62B1519 : 0xC6361A1F;
			}
			// The keys carry the same answer as the rows beside them. Without it the red stopped dead
			// at the roll's edge and the keyboard went on claiming every note was available, which is
			// the one place you look to ask what a row is.
			graphics.fill(pianoX, y, rollX, y + rowHeight - 1, buildable ? 0xFFE7E7E7 : 0xFFEAC6C6);
			if (black) {
				graphics.fill(pianoX, y, pianoX + PIANO_WIDTH * 2 / 3, y + rowHeight - 1,
					buildable ? 0xFF303238 : 0xFF45272B);
			}
			// A key that has just sounded, fading out over a quarter of a second. Over the whole key
			// rather than part of it, black ones included: the question it answers is "which row was
			// that", and at four pixels a row there is no part of a key to be subtle in. One quad,
			// and only for the keys actually lit -- the arithmetic below runs for every visible row
			// and is a subtraction against a timestamp that is almost always long past.
			long litFor = now - keyLitAt[midi];
			if (litFor >= 0L && litFor < KEY_LIGHT_MILLIS) {
				int alpha = (int)Math.round(255.0 * (1.0 - litFor / (double)KEY_LIGHT_MILLIS));
				graphics.fill(pianoX, y, rollX, y + rowHeight - 1,
					alpha << 24 | keyLitColor[midi] & 0xFFFFFF);
			}
			graphics.fill(pianoX, y + rowHeight - 1, rollX, y + rowHeight, 0xFF55575C);
			graphics.fill(rollX, y, rollX + rollWidth, y + rowHeight - 1, gridColor);
			if (midi % 12 == 0) {
				graphics.text(font, midiName(midi), pianoX + 2, y + 2, 0xFF222222, false);
			}
		}

		extractKeyTooltip(graphics, mouseX, mouseY);
		mark = phase(PHASE_KEYS, mark);
		extractTimeGrid(graphics);
		mark = phase(PHASE_GRID, mark);
		extractNotes(graphics, mouseX, mouseY);
		mark = phase(PHASE_NOTES, mark);
		extractRangeBand(graphics);
		extractPlayhead(graphics);
		if (selectingBox) {
			// Held to the roll's edges. The anchor is a position in the song now, so once the view
			// has scrolled past it the corner is genuinely off to one side -- and the scissor here
			// starts at the piano keys, which would have let the box spill over them.
			int left = (int)Math.max(rollX, Math.min(boxOriginX(), selectionEndX));
			int right = (int)Math.min(rollX + rollWidth, Math.max(boxOriginX(), selectionEndX));
			int top = (int)Math.max(rollY, Math.min(boxOriginY(), selectionEndY));
			int bottom = (int)Math.min(rollY + rollHeight, Math.max(boxOriginY(), selectionEndY));
			graphics.fill(left, top, right, bottom, 0x3344CCFF);
			graphics.fill(left, top, right, top + 1, 0xFF55FFFF);
			graphics.fill(left, bottom - 1, right, bottom, 0xFF55FFFF);
			graphics.fill(left, top, left + 1, bottom, 0xFF55FFFF);
			graphics.fill(right - 1, top, right, bottom, 0xFF55FFFF);
		}
		// A locked drag looks exactly like a drag you are being sloppy about until it says so. Drawn
		// as a line through the notes rather than a label, because it is answering "which way can
		// this go" and a line is that answer.
		//
		// Pinned where the drag began, not to the cursor. Following the cursor meant the horizontal
		// guide slid up and down the roll while claiming the drag could not move up or down -- the
		// one thing it exists to say. Standing still on the rail the notes are travelling along says
		// it without having to be read.
		if (draggingNotes && dragAxis != DragAxis.UNDECIDED && shiftDown()) {
			if (dragAxis == DragAxis.TIME) {
				int guideY = (int)dragStartY;
				graphics.fill(rollX, guideY, rollX + rollWidth, guideY + 1, 0x8855FFFF);
			} else {
				int guideX = (int)dragStartX;
				graphics.fill(guideX, rollY, guideX + 1, rollY + rollHeight, 0x8855FFFF);
			}
		}
		graphics.disableScissor();

		// No legend for the range. It named the good range back when the good range was the tinted
		// one, and a caption explaining that the red rows are the bad ones is telling you what the
		// red already said -- in the corner of a roll whose whole width is worth more as roll.
		//
		// What does go there is whether the keys are writing into the song, which is not something
		// the roll otherwise shows and is the difference between a preview and an edit.
		if (recording) {
			extractRecordState(graphics);
		}
		return phase(PHASE_PLAYHEAD, mark);
	}

	/**
	 * Says that the keys are writing, and counts you in before they are.
	 *
	 * <p>Two states and they must not be confused, because in one of them pressing a key changes the
	 * song and in the other it does not. The count is drawn over the middle of the roll rather than
	 * in the corner: it is the one thing on screen worth looking at for the second it exists, and
	 * three beats is not long enough to go looking for it.</p>
	 */
	private void extractRecordState(GuiGraphicsExtractor graphics) {
		int number = countInNumber();
		if (number > 0) {
			String count = Integer.toString(number);
			int centreX = rollX + rollWidth / 2;
			int centreY = rollY + rollHeight / 2;
			// Four passes offset by a pixel, which is the only bold this font has.
			for (int offset = 0; offset < 4; offset++) {
				graphics.text(font, count, centreX - font.width(count) / 2 + offset % 2,
					centreY - 4 + offset / 2, 0xFFFF6B6B, false);
			}
			graphics.text(font, "get ready", centreX - font.width("get ready") / 2, centreY + 8,
				0xFFCDA0A0, false);
			return;
		}
		// A filled dot rather than the word alone: it is the shorthand, and it is legible at the edge
		// of vision while you are watching the marker rather than the corner.
		graphics.fill(rollX + 5, TOOLBAR_HEIGHT + 4, rollX + 10, TOOLBAR_HEIGHT + 9, 0xFFFF4040);
		graphics.text(font, "REC  " + recorded + (recorded == 1 ? " note" : " notes"),
			rollX + 14, TOOLBAR_HEIGHT + 3, 0xFFFF8A8A, false);
	}

	private void extractTimeGrid(GuiGraphicsExtractor graphics) {
		long lastTick = horizontalScroll + (long)Math.ceil(rollWidth * ticksPerPixel);
		long measureTicks = Math.max(1L, project().ppq() * 4L);

		// The grid drawn is the snap doubled until its lines are far enough apart to be lines, so
		// most of the time you are looking at every second or every fourth step rather than at the
		// step itself -- and nothing said which. Lines that are the grid are amber; lines standing
		// in for it stay grey. A brightness difference was the first try and was not one: two greys
		// a shade apart can only be told apart by comparing them to each other, and there is only
		// ever one of them on screen. A hue is a thing you can read on its own.
		double snapSpan = drawnGridSpan();
		boolean trueGrid = snapSubdivision != 0 && snapSpan <= gridSpan() * 1.001;
		int snapFloor = redstoneSnap() ? REDSTONE_GRID_PIXEL_SPACING : MIN_GRID_PIXEL_SPACING;
		boolean drawSnap = snapSpan / ticksPerPixel >= snapFloor;

		if (redstoneSnap()) {
			extractRedstoneGrid(graphics, lastTick, snapSpan, trueGrid, drawSnap);
		} else {
			extractMusicalGrid(graphics, lastTick, measureTicks, snapSpan, trueGrid, drawSnap);
		}

		for (long overloaded : overloadedTicks()) {
			int x = tickX(overloaded);
			if (x >= rollX && x <= rollX + rollWidth) {
				graphics.fill(x - 1, rollY, x + 2, rollY + rollHeight, 0x66FF3333);
			}
		}
		// The markers drop through the roll as another kind of grid line, which is what they are
		// being used as. Behind the notes, since a landmark is for finding the music by.
		for (ComposerProject.Marker marker : project().markers()) {
			int x = tickX(marker.tick());
			if (x >= rollX && x <= rollX + rollWidth) {
				// The tab's own colour carried down. Which line belongs to which tab is the thing a
				// colour per marker buys, and it is only bought if the line is coloured too.
				graphics.fill(x, rollY, x + 1, rollY + rollHeight,
					0x55000000 | markerColor(marker) & 0xFFFFFF);
			}
		}
	}

	/**
	 * The roll under a musical snap: the song's own bars and beats, with the snap under them.
	 *
	 * <p>The snap lines are positions rather than multiples of a step, and a line the beat pass is
	 * going to draw is skipped rather than drawn under it. All of these colours are part
	 * transparent, so drawing both composites them and every beat comes out darker than it should.
	 * </p>
	 */
	private void extractMusicalGrid(GuiGraphicsExtractor graphics, long lastTick, long measureTicks,
			double snapSpan, boolean trueGrid, boolean drawSnap) {
		long beatTicks = readableStep(Math.max(1L, project().ppq()), measureTicks);
		boolean showLabels = measureTicks / ticksPerPixel >= MIN_LABEL_PIXEL_SPACING;
		// The same crowding fade as everything else. These were the last grid drawn at one strength
		// whatever the zoom, and they fail hardest of all of them: once the beat step outgrows a
		// bar it is rounded up to whole bars, so every line on screen is a bar line and every one
		// of them is drawn at the bright weight. Zoomed out to a whole song that is a picket fence
		// at full contrast with the music behind it.
		//
		// Each judged by its own spacing rather than by the step of the loop. Bars are drawn every
		// bar or every beat-step, whichever is coarser; beats are drawn at the step. Judging both
		// by the step would dim the bars for the crowding of the beats between them, which is the
		// one grid that has to survive being zoomed out.
		double beatPixels = beatTicks / ticksPerPixel;
		double barPixels = Math.max(measureTicks, beatTicks) / ticksPerPixel;
		int barColor = crowdedGridColor(0x4C777777, barPixels, MIN_GRID_PIXEL_SPACING * 4);
		int beatColor = crowdedGridColor(0x443F444A, beatPixels, MIN_GRID_PIXEL_SPACING * 2);
		for (long tick = Math.max(0L, horizontalScroll / beatTicks * beatTicks);
				tick <= lastTick + beatTicks; tick += beatTicks) {
			int x = tickX(tick);
			if (x < rollX || x > rollX + rollWidth) {
				continue;
			}
			boolean measure = tick % measureTicks == 0;
			graphics.fill(x, rollY, x + 1, rollY + rollHeight, measure ? barColor : beatColor);
			if (measure && showLabels) {
				graphics.text(font, Long.toString(tick / measureTicks + 1),
					x + 3, rollY + 2, 0xFFAAAAAA, false);
			}
		}

		// The snap last, and over the beats rather than under them.
		//
		// It used to be drawn first and to skip any line the beat pass was going to draw, so that
		// two part-transparent colours could not composite on the same pixel and come out darker
		// than either. The cost of that only shows at the coarse settings: on Snap 1/4 every snap
		// line is a beat, so every one of them was skipped and the setting had no colour on the roll
		// at all. On 1/8 exactly half of them survived, which reads as the grid being every other
		// line, and it is not.
		//
		// So the skip is now only for the bar lines. A bar is a landmark, it carries the number, and
		// it is the one line worth keeping in its own colour. A beat that is also a snap line takes
		// the amber over the top of it -- lighter rather than darker, since the amber is the
		// brighter of the two -- and says what it is.
		if (drawSnap) {
			int snapColor = crowdedGridColor(trueGrid ? 0x17D9863C : 0x0F2A2F36,
				snapSpan / ticksPerPixel, MIN_GRID_PIXEL_SPACING);
			for (long index = (long)Math.floor(horizontalScroll / snapSpan);
					gridLineAt(index, snapSpan) <= lastTick + snapSpan; index++) {
				long line = gridLineAt(index, snapSpan);
				int x = tickX(line);
				if (x >= rollX && x <= rollX + rollWidth && line % measureTicks != 0L) {
					graphics.fill(x, rollY, x + 1, rollY + rollHeight, snapColor);
				}
			}
		}
	}

	/**
	 * The roll under a redstone snap: one grid, and it is real time.
	 *
	 * <p>The bars and beats are gone from here. Two spacings that share no common factor read as one
	 * set of lines at neither of them, and the whole reason to put a redstone grid on screen is to
	 * see where a build can actually place a note -- which is a fact about seconds, not about the
	 * music. Nothing is lost by dropping them: the ruler above the roll still carries bar numbers
	 * and the clock, which is where you look to ask where you are.</p>
	 *
	 * <p>What replaces the bar line is the second. Ten repeater ticks is exactly one second and
	 * twenty game ticks is the same second, so the same landmark serves both settings and agrees
	 * with the times written along the ruler.</p>
	 */
	private void extractRedstoneGrid(GuiGraphicsExtractor graphics, long lastTick, double snapSpan,
			boolean trueGrid, boolean drawSnap) {
		double secondSpan = Math.max(1.0,
			SongAnalysis.redstoneTickSpan(project()) * REPEATER_TICKS_PER_SECOND);
		boolean drawSeconds = secondSpan / ticksPerPixel >= MIN_GRID_PIXEL_SPACING * 2;
		if (drawSnap) {
			int snapColor = crowdedGridColor(trueGrid ? 0x1ED98A3C : 0x1628343D,
				snapSpan / ticksPerPixel, REDSTONE_GRID_PIXEL_SPACING, 0.55);
			for (long index = (long)Math.floor(horizontalScroll / snapSpan);
					gridLineAt(index, snapSpan) <= lastTick + snapSpan; index++) {
				int x = tickX(gridLineAt(index, snapSpan));
				if (x < rollX || x > rollX + rollWidth) {
					continue;
				}
				// Skipped rather than drawn under the second line, for the same reason the musical
				// grid skips its beats: both are part transparent and would composite.
				if (drawSeconds && tickX(gridLineAt(Math.round(gridLineAt(index, snapSpan) / secondSpan),
						secondSpan)) == x) {
					continue;
				}
				graphics.fill(x, rollY, x + 1, rollY + rollHeight, snapColor);
			}
		}
		if (drawSeconds) {
			// These went in as the landmark that replaced the bar line and then kept the bar line's
			// weight, which a second does not deserve: bars come every four beats and seconds come
			// as often as the tempo says, which on a quick song is twice a bar. Full height, opaque
			// enough to read, and never faded -- they were the brightest thing on the roll and the
			// only grid on screen that did not answer to how crowded it was.
			//
			// A wider floor than the lines it stands over, too. A second is only a landmark while
			// there is room to see it as one; a second every twenty pixels is a picket fence, and
			// this reaches full weight only once they are four times that far apart.
			int secondColor = crowdedGridColor(0x44828C97, secondSpan / ticksPerPixel,
				MIN_GRID_PIXEL_SPACING * 4);
			for (long index = (long)Math.floor(horizontalScroll / secondSpan);
					gridLineAt(index, secondSpan) <= lastTick + secondSpan; index++) {
				int x = tickX(gridLineAt(index, secondSpan));
				if (x >= rollX && x <= rollX + rollWidth) {
					graphics.fill(x, rollY, x + 1, rollY + rollHeight, secondColor);
				}
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
	/**
	 * The snap spacing, widened by doubling until its lines are far enough apart to look at.
	 *
	 * <p>Doubling rather than snapping to a coarser grid, so what is drawn stays a subset of what
	 * notes land on: every line you can see is a line, even when most of them are hidden. Zoomed out
	 * far enough there is nothing worth drawing and the caller stops.</p>
	 */
	private double readableSpan(double span) {
		return readableSpan(span, MIN_GRID_PIXEL_SPACING);
	}

	/** The same, for a grid that wants more room between its lines than the musical one does. */
	private double readableSpan(double span, int floorPixels) {
		double step = Math.max(1.0, span);
		while (step / ticksPerPixel < floorPixels && step < Long.MAX_VALUE / 4) {
			step *= 2.0;
		}
		return step;
	}

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
		int noteWidth = noteWidth();
		cells.begin(rollX, rollWidth, noteWidth, rowHeight - 2);
		for (int layerIndex : noteDrawOrder(shown)) {
			Layer layer = shown.layers().get(layerIndex);
			if (!layer.visible()) {
				continue;
			}
			boolean highlighted = layerLit(layerIndex);
			// One colour per layer whatever is wrong with the note. Out of range used to paint the
			// whole note red, which on an unconverted song is most of them -- so the roll answered
			// "this will not build", which you already knew, and stopped answering anything else.
			int color = highlighted ? vivid(layerColor(layerIndex)) : faded(layerColor(layerIndex));
			// A sound effect has no note block range to be outside of, so nothing on one of those
			// layers is ever marked unbuildable, wherever on the roll it was drawn.
			boolean rangeMatters = layer.pitched();
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
				int right = left + noteWidth;
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
				if (rangeMatters && !note.isBuildable()) {
					flags |= NoteCellGrid.UNBUILDABLE;
				}
				cells.add(left, top, color, flags, midi);
				if (mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom) {
					// Topmost wins: draw order runs back to front, so a later hit overwrites.
					hoveredCandidate = note;
					hoveredCandidateLayer = layerIndex;
				}
			}
		}
		long drawStart = profiling ? System.nanoTime() : 0L;
		quads.graphics = graphics;
		// Dimmed while the layer panel holds the keyboard, for the same reason and in the other
		// direction: a note selection Delete can no longer reach should not look like one it can.
		noteQuads = cells.draw(quads, focusedPane == Pane.ROLL ? 0xFFFFFFFF : 0xFF6E7A83);
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

	/**
	 * Names the key the cursor is resting on, after the same dwell a note takes to name itself.
	 *
	 * <p>Only every C is written on the keyboard -- there is no room for more at four pixels a row,
	 * and a column of twelve names an octave would be unreadable anyway. That leaves counting up
	 * from a C to answer "what note is this", which is a thing you should not have to do in a music
	 * editor with a keyboard drawn down the side of it.</p>
	 *
	 * <p>The dwell matters as much as the tooltip. The cursor crosses this strip on the way to
	 * everything, and a label that appeared the instant it did would be a label flickering under
	 * the hand all day.</p>
	 */
	private void extractKeyTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!overPianoKeys(mouseX, mouseY) || overOpenMenu(mouseX, mouseY)) {
			hoveredKeyMidi = -1;
			return;
		}
		int midi = mouseMidi(mouseY);
		if (midi != hoveredKeyMidi) {
			hoveredKeyMidi = midi;
			hoveredKeySince = Util.getMillis();
			return;
		}
		if (Util.getMillis() - hoveredKeySince < TOOLTIP_DWELL_MILLIS) {
			return;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(midiName(midi)));
		boolean buildable = midi >= ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
			&& midi <= ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE;
		lines.add(buildable
			? Component.literal("Note block pitch "
					+ (midi - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE))
				.withStyle(net.minecraft.ChatFormatting.GRAY)
			: Component.literal("Outside the note block range")
				.withStyle(net.minecraft.ChatFormatting.RED));
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
		int active = editingLayerIndex();
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

	/**
	 * Layers the roll will let you touch: the selected ones, or all of them while none is selected.
	 *
	 * <p>An empty selection is a real state now rather than something to be defended against -- see
	 * {@link #noLayerSelected()} -- and the reading that makes it useful is "no filter" rather than
	 * "nothing". Ctrl+A with no layer selected takes the whole song, which is what you want it to
	 * mean the moment you have stopped working on one part.</p>
	 */
	private List<Integer> selectionLayers() {
		List<Integer> valid = selectedLayers.stream()
			.filter(index -> index >= 0 && index < project().layers().size())
			.sorted()
			.toList();
		if (!valid.isEmpty()) {
			return valid;
		}
		return java.util.stream.IntStream.range(0, project().layers().size()).boxed().toList();
	}

	/**
	 * Whether the panel has nothing selected, which is a mode and not an accident.
	 *
	 * <p>In it the roll shows one song rather than one voice against a background of others: every
	 * layer is lit, every note can be picked up, and picking one is how you say which layer you
	 * meant. Out of it, the roll leaves the layers you did not select alone entirely -- that is what
	 * lets a note be placed on top of one that is already there.</p>
	 */
	private boolean noLayerSelected() {
		return selectedLayers.isEmpty();
	}

	/** The layer an edit lands in, or -1 while none is selected. */
	private int editingLayerIndex() {
		return noLayerSelected() ? -1 : project().activeLayerIndex();
	}

	/**
	 * Whether a layer is in the foreground: one you selected, or all of them while none is.
	 *
	 * <p>Asked in one place because two places had already disagreed. The roll read no-layer mode as
	 * "every part is yours" and lit all of them; the panel read it as "none of these is selected"
	 * and dimmed every colour stripe -- both defensible sentences, and side by side they say the
	 * panel and the roll are describing different songs.</p>
	 */
	private boolean layerLit(int index) {
		return noLayerSelected() || selectedLayers.contains(index);
	}

	/**
	 * Puts the panel's selection on one layer, for the actions that decide it rather than ask.
	 *
	 * <p>Adding a layer, moving a selection into one and drawing a note in no-layer mode all leave
	 * the composition pointing at a new active layer. The panel used to be left pointing at the old
	 * one, which mattered little while the roll highlighted the active layer regardless -- it does
	 * not now, so the two have to be moved together or the layer you just landed in draws dim.</p>
	 */
	private void selectOnlyLayer(int layerIndex) {
		selectedLayers.clear();
		if (layerIndex >= 0 && layerIndex < project().layers().size()) {
			selectedLayers.add(layerIndex);
		}
	}

	/**
	 * Steps out of every layer, leaving the whole song in the foreground.
	 *
	 * <p>Which notes are selected is a separate question and is left alone. It was not, at first,
	 * and throwing the notes away on the way into the mode took the right-click menu with them --
	 * that menu only opens on a selection, so after a click on blank panel a right-click on a note
	 * deleted it instead of offering anything.</p>
	 */
	private void clearLayerSelection() {
		if (noLayerSelected()) {
			return;
		}
		selectedLayers.clear();
		instrumentMenuLayer = -1;
		cancelLayerRename();
		showResult(Component.literal("No layer selected. Clicking a note now picks its layer, and "
			+ "drawing one starts a new layer."));
	}

	/** What a click on the keys or on a new note sounds like: the layer's voice, or a plain harp. */
	private PreviewInstrument previewInstrument() {
		return noLayerSelected()
			? PreviewInstrument.byId("HARP")
			: PreviewInstrument.byId(activeLayer().instrument());
	}

	/** What colour that click lights its key in: the layer's, or a plain grey with no layer. */
	private int previewColor() {
		return noLayerSelected() ? 0xFFBCC3CC : vivid(layerColor(project().activeLayerIndex()));
	}

	/**
	 * Plays one note and lights the key it landed on.
	 *
	 * <p>The two together because they are one event -- a preview that sounds without marking the
	 * keyboard leaves you working out which of two octaves you just heard.</p>
	 *
	 * @param midi the note in MIDI numbering, not the note block's 0-24
	 */
	/**
	 * Plays the note being dragged at the pitch the drag has moved it to, so a drag can be tuned by
	 * ear instead of by counting rows.
	 *
	 * <p>Clamped the way the move itself is clamped -- {@code moveNotes} will not push anything past
	 * the ends of the range, so a drag held above the top plays the note it will actually leave
	 * behind rather than one that does not exist.</p>
	 */
	private void soundHeldNote(int pitchDelta) {
		if (dragHeldNoteId < 0L || dragHeldLayer < 0
				|| dragHeldLayer >= project().layers().size()) {
			return;
		}
		int midi = Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE, dragHeldMidi + pitchDelta));
		soundNote(midi,
			PreviewInstrument.byId(project().layers().get(dragHeldLayer).instrument()),
			vivid(layerColor(dragHeldLayer)));
	}

	private void soundNote(int midi, PreviewInstrument instrument, int color) {
		instrument.play(midi - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE);
		lightKey(midi, color);
	}

	/** Marks a key as having just sounded. It goes out on its own; see {@link #keyLitAt}. */
	private void lightKey(int midi, int color) {
		if (midi >= MIN_MIDI_NOTE && midi <= MAX_MIDI_NOTE) {
			keyLitAt[midi] = Util.getMillis();
			keyLitColor[midi] = color;
		}
	}

	private void extractPlayhead(GuiGraphicsExtractor graphics) {
		long tick = playing ? playbackTick() : playbackReturnTick;
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
		toastLeft = left;
		toastTop = top;
		toastRight = right;
		toastBottom = bottom;
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
		// The lane count belongs on the verdict rather than beside it: it is not a caveat on being
		// buildable, it is what being buildable means for this song. One lane is a plain chain of
		// repeaters; two is that chain and a second one started half a tick later off a piston.
		segments.add(ready
			? "MINECRAFT READY - " + stats.lanesNeeded() + " lane"
				+ (stats.lanesNeeded() == 1 ? "" : "s") + " needed"
			: "NOT BUILDABLE");
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
			.filter(Layer::inBuild)
			.count();
		if (included == 0) {
			segments.add("every layer muted or hidden");
		} else {
			// Counted off the sequence rather than off the composition, so it agrees with what the
			// paste would place -- including which notes deduplication left out of it.
			SongBuilder.BlockCounts blocks = blockCounts();
			segments.add(blocks.total() + " blocks (" + blocks.noteBlocks() + " note · "
				+ blocks.repeaters() + " repeater)");
		}
		// The number the snap button has no room for. It moves with the tempo and the speed, so it
		// belongs on screen rather than behind a hover.
		segments.add(tempoLabel() + " · " + project().ppq() + " ticks/beat");
		// Lower case here and capitalised on the button, because this one is inside a sentence.
		segments.add("grid " + gridName(snapSubdivision).toLowerCase(java.util.Locale.ROOT)
			+ " = " + snapDetail());
		// The one place preview and build still disagree. Solo is a lens for listening around a
		// part, so it deliberately does not change what gets built -- which means that while it is
		// on, what you are hearing is not what would be placed. Said out loud rather than left to
		// be discovered, because it is the same trap a DAW's bounce sets when solo is left up.
		if (!soloedLayers.isEmpty()) {
			segments.add(soloedLayers.size() + (soloedLayers.size() == 1 ? " layer" : " layers")
				+ " soloed - solo does not change the build");
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
		// Amber after the green, in its own draw, because it is neither a problem nor part of the
		// verdict: the song builds, and it builds as two machines rather than one. A reader who
		// takes in only the colour should come away with "fine, but there is something to know",
		// which is exactly what a second colour after a green one says.
		if (ready && !stats.halfTickedNotes().isEmpty()) {
			String note = "   half-ticked: " + stats.halfTickedNotes().size()
				+ " notes land between repeater ticks, so the build uses 2 lanes";
			int after = 8 + font.width(status.toString());
			if (after + font.width(note) <= width - 8) {
				graphics.text(font, note, after, height - 16, 0xFFFFAA00, false);
			}
		}
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

	/**
	 * What is true of the composition, judged against what a build can do rather than against the
	 * paste mode last used.
	 *
	 * <p>The mode was in this for a while, so that a song with notes between the repeater ticks read
	 * NOT BUILDABLE while a one-lane mode was selected. It is the wrong question asked at the wrong
	 * moment: the bar is describing the song, the mode is a choice made later in another screen, and
	 * a composition that two lanes play perfectly is not broken because the last thing pasted was
	 * something else. It also could not be right for long -- nothing here knows what the next paste
	 * will be made of.</p>
	 *
	 * <p>So the verdict says what the song needs and the lane count carries the answer: one lane is a
	 * plain chain of repeaters, two is that chain and a second started half a tick later. Whether
	 * the mode in hand can supply the second one is a question for the paste, which asks it there.
	 * </p>
	 */
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
		cachedStats = SongAnalysis.of(current, cachedStatsDedupe, true);
		return cachedStats;
	}

	/** Whether anything is hanging open over the composition: a menu, a context menu, the palette. */
	private boolean anyMenuOpen() {
		return toolbarMenu != ToolbarMenu.NONE || layerMenuOpen || contextMenuOpen
			|| snapMenuOpen || instrumentMenuLayer >= 0;
	}

	/** Puts all of them away, which is what every way out of a menu ends up doing. */
	private void closeMenus() {
		toolbarMenu = ToolbarMenu.NONE;
		openSubmenu = null;
		layerMenuOpen = false;
		contextMenuOpen = false;
		snapMenuOpen = false;
		instrumentMenuLayer = -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Right-click is the way out of anything open, and is spent on getting out of it. Before
		// every other test, because the alternatives were all wrong in their own way: a right-click
		// on a menu row ran the row, one inside the palette did nothing at all, and one outside
		// either of them closed the menu and then went on to erase the notes underneath.
		if (event.button() == 1 && anyMenuOpen()) {
			closeMenus();
			return true;
		}
		// A result stays up for four and a half seconds, or for as long as you point at it, and
		// there was no way to say you had read it. Clicking one puts it away -- and the click is
		// spent on that, since it lands over the roll and would otherwise draw a note through the
		// message it was dismissing.
		if (toast != null && !anyMenuOpen() && event.x() >= toastLeft && event.x() < toastRight
				&& event.y() >= toastTop && event.y() < toastBottom) {
			toast = null;
			return true;
		}
		ToolbarMenu title = menuTitleAt(event.x(), event.y());
		if (title == ToolbarMenu.SETTINGS) {
			toolbarMenu = ToolbarMenu.NONE;
			openSubmenu = null;
			minecraft.gui.setScreen(new SettingsScreen(this));
			return true;
		}
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
		if (event.button() == 0 && overLayerPanelFold(event.x(), event.y())) {
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
		if (snapMenuOpen) {
			if (handleSnapMenuClick(event.x(), event.y())) {
				return true;
			}
			// Spent on closing, like the instrument palette's, and for the same two reasons. It
			// hangs over the roll, so falling through would draw a note under the list you were
			// putting away -- and that includes the button it came from, which would otherwise
			// take the press and open it straight back up.
			snapMenuOpen = false;
			return true;
		}
		NoteRect palette = instrumentMenuRect();
		if (palette != null) {
			if (event.button() == 0 && handleInstrumentMenuClick(event.x(), event.y())) {
				return true;
			}
			if (palette.contains(event.x(), event.y())) {
				return true;
			}
			// Anywhere else puts it away, and does nothing else. A click that dismisses a menu is
			// spent on dismissing it: it used to fall through, so putting the palette down landed on
			// whatever happened to be under it and picked a different layer, or sounded a piano key.
			// That includes the icon that opened it, which would otherwise open it straight back up.
			instrumentMenuLayer = -1;
			return true;
		}
		// Every menu above has had its say and none of them is a pane, so whatever is left is a
		// click on the composition itself and decides where the keyboard points.
		//
		// The roll is where the keyboard lives. Picking a layer is something you do in the middle of
		// writing notes -- to say which voice the next one goes on -- so a click on a row selects it
		// and leaves the keyboard where it was. Any click on the panel taking the keyboard with it
		// meant every one of those cost a click back, and Delete pointed at the wrong thing in
		// between.
		//
		// The panel is asked for by clicking a row that is already selected. That is a press with no
		// other job: the layer is picked, so the only thing left for it to mean is "and now I am
		// working in here". Two clicks to reach the layer shortcuts, and none of them ambiguous.
		int pressedRow = layerHeaderAt(event.x(), event.y());
		Pane wanted = event.x() >= layerPanelWidth() ? Pane.ROLL
			: pressedRow >= 0 && selectedLayers.contains(pressedRow) ? Pane.LAYERS
			: focusedPane;
		// A press that arrives while a pane does not have the keyboard is spent on giving it the
		// keyboard. On the roll that means it may not write a note -- which is also true of the
		// click that brings the window back to the front, and used to leave a note behind wherever
		// the cursor happened to be resting when you tabbed away. On the panel it means the press
		// may not collapse a selection of several rows down to the one under it, or asking for the
		// keyboard would cost you the selection you wanted it for.
		boolean claimingLayers = focusedPane != Pane.LAYERS && wanted == Pane.LAYERS;
		pressClaimedFocus = !windowWasFocused || (focusedPane != Pane.ROLL && wanted == Pane.ROLL);
		windowWasFocused = true;
		// The box is drawn to wherever the mouse was last seen, and after a spell outside the window
		// that is wherever it left. One frame of a selection box stretched across the whole song,
		// every time you clicked back in.
		lastMouseX = event.x();
		lastMouseY = event.y();
		focusedPane = wanted;
		if (event.button() == 1) {
			int stateLayer = layerStateAt(event.x(), event.y());
			if (stateLayer >= 0) {
				startPainting(LayerPaint.STATE, stateLayer, cycleLayerState(stateLayer, -1));
				return true;
			}
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0) {
				if (!selectedLayers.contains(layerIndex)) {
					selectLayer(layerIndex, false, false);
				}
				layerMenuOpen = true;
				layerMenuRow = layerIndex;
				// Held on screen. The menu hangs down and to the right of the press, and the panel it
				// belongs to runs the full height of the window, so a right-click on a row near the
				// bottom would put half of it past the edge.
				layerMenuX = Math.max(0, Math.min((int)event.x(), width - layerMenuWidth()));
				layerMenuY = Math.max(0, Math.min((int)event.y(), height - layerMenuHeight()));
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
			int stateLayer = layerStateAt(event.x(), event.y());
			if (stateLayer >= 0) {
				startPainting(LayerPaint.STATE, stateLayer, cycleLayerState(stateLayer, 1));
				return true;
			}
			int instrumentLayer = layerInstrumentAt(event.x(), event.y());
			if (instrumentLayer >= 0) {
				boolean closing = instrumentMenuLayer == instrumentLayer;
				instrumentMenuLayer = closing ? -1 : instrumentLayer;
				if (!closing) {
					// Opens on the tab the layer is already using, so the voice it has is the one
					// under the cursor rather than one page away from it.
					instrumentMenuEffects = !project().layers().get(instrumentLayer).pitched();
				}
				return true;
			}
		}
		if (event.button() == 0) {
			int layerIndex = layerHeaderAt(event.x(), event.y());
			if (layerIndex >= 0) {
				// A plain press on a row that is already one of several selected does not collapse
				// the selection yet. It used to, which made a multi-row selection impossible to
				// reorder: the press that should have picked up four layers put three of them down
				// first, and the drag that followed carried one. The collapse is deferred to the
				// release and only happens if the hand never moved.
				boolean holding = selectedLayers.size() > 1 && selectedLayers.contains(layerIndex)
					&& !controlDown() && !shiftDown();
				if (!holding) {
					selectLayer(layerIndex, controlDown(), shiftDown());
				}
				selectedNotes.clear();
				// Armed, not started. A press on a header is nearly always a plain selection, so the
				// reorder only takes over once the cursor has actually left the row it started on.
				layerDragIndex = layerIndex;
				layerDragStartY = event.y();
				layerDragY = event.y();
				layerDragActive = false;
				layerDragCollapse = holding && !claimingLayers;
				return true;
			}
		}
		if (event.button() == 0 && overLayerPanelBlank(event.x(), event.y())) {
			if (focusedPane == Pane.LAYERS) {
				// Clicking off the rows while the panel holds the keyboard is how you let go of it,
				// and that is all it is. Putting the selection down at the same time meant there was
				// no way to stop working in the panel without also losing the layers you had picked
				// -- and the way back was to find one of them and click it twice.
				focusedPane = Pane.ROLL;
			} else {
				clearLayerSelection();
			}
			return true;
		}
		if (event.button() == 0 && overPianoKeys(event.x(), event.y())) {
			// Where the marker is, is the cell -- but snapped to the nearest grid line rather than
			// into the cell the marker is inside, which is the opposite of what a click on the roll
			// wants. A click points at a cell and belongs in it. A note played by hand lands *near* a
			// beat, on either side of it, and quantizing a performance means moving it to the beat it
			// was aiming at.
			//
			// Flooring made that impossible to play against: it takes anything even a millisecond
			// early and throws it back a whole cell, so the only notes that landed where they were
			// meant were the late ones. A cell is 25ms of real time on the fastest song in this
			// library and 200ms on the slowest, and the slow ones were worse, because the cell being
			// thrown across is bigger. Nearest tolerates half a cell either side, which is what makes
			// it feel like it is listening.
			if (takingNotes() && !pressClaimedFocus) {
				int before = project().noteCount();
				placeNote(mouseMidi(event.y()), snapTick(recordTick()));
				int added = project().noteCount() - before;
				recorded += added;
				if (added > 0) {
					takeNotes.add(newestNoteId());
				}
			} else {
				soundNote(mouseMidi(event.y()), previewInstrument(), previewColor());
			}
			return true;
		}
		// A tab, and only a tab. Clicking the empty part of the strip used to put a marker down,
		// which made the bottom of the ruler a place you could not click without leaving something
		// behind -- and the ruler is a thing you click all day to move the playhead. M is how a
		// marker is added, which is one way rather than two and the one that says where it lands.
		ComposerProject.Marker markerHit = markerAtPoint(event.x(), event.y());
		if (markerHit != null) {
			if (event.button() == 1) {
				removeMarkerAt(markerHit.tick());
				return true;
			}
			if (event.button() == 0) {
				if (doubleClick) {
					renameMarker(markerHit);
				} else {
					setPlaybackStart(markerHit.tick(), false);
				}
				return true;
			}
		}
		if (event.button() == 0 && rangeHandleAt(event.x(), event.y()) != 0) {
			draggingRangeHandle = rangeHandleAt(event.x(), event.y());
			return true;
		}
		if (event.button() == 1 && rangeHandleAt(event.x(), event.y()) != 0) {
			// The way out of a range without also having to put the selection down. On the handles
			// rather than anywhere in the band, since the rest of the band is roll and right-click
			// on roll is the eraser.
			clearRange();
			return true;
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
			// Nothing selected: right-click is the eraser. It takes the note it lands on and every
			// note the drag then passes over, which is the gesture for clearing a passage you do not
			// want -- box-selecting it first and pressing Delete is three moves for one intention.
			//
			// Something selected: right-click is the menu, because the selection is what you are
			// pointing at. Nothing is deleted outright in that state, and nothing needs to be: a
			// note is only ever in the selection because you put it there.
			if (selectedNotes.isEmpty()) {
				beginErasing(event.x(), event.y());
				return true;
			}
			NoteHit hit = noteAt(event.x(), event.y());
			if (hit != null) {
				if (noLayerSelected()) {
					selectLayer(hit.layerIndex(), false, false);
				}
				if (!selectedNotes.contains(hit.note().id())) {
					selectedNotes.clear();
					selectedNotes.add(hit.note().id());
				}
			}
			openContextMenu(event.x(), event.y());
			return true;
		}
		if (event.button() != 0 || !insideRoll(event.x(), event.y())) {
			return super.mouseClicked(event, doubleClick);
		}
		NoteHit hit = noteAt(event.x(), event.y());
		if (hit != null) {
			NoteEvent hitNote = hit.note();
			// Which layer you are on is the panel's business now, not the roll's. Clicking a note
			// used to jump to its layer, which meant there was no way to put a note on top of one
			// that already existed: the click that should have started the placement moved you to
			// the other voice instead. The one exception is no-layer mode, where picking a note is
			// exactly how you say which voice you meant -- see noLayerSelected().
			//
			// Except when that note is already selected, which is not a pick but the start of a
			// drag. Taking the layer there collapsed a selection that spanned the whole song down to
			// the one note under the cursor, so a box select across four parts could only move one.
			if (noLayerSelected() && !selectedNotes.contains(hitNote.id())) {
				selectedNotes.clear();
				selectLayer(hit.layerIndex(), false, false);
			}
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
				dragAxis = DragAxis.UNDECIDED;
				dragHeldNoteId = hitNote.id();
				dragHeldMidi = hitNote.midiNote();
				dragHeldLayer = hit.layerIndex();
				dragHeardPitchDelta = 0;
				soundHeldNote(0);
			}
			return true;
		}
		if (doubleClick && !pressClaimedFocus) {
			placeNote(mouseMidi(event.y()), snapTickInto(mouseTick(event.x())));
			return true;
		}
		// Not decided here. A press on empty roll is the start of a box select and also, if the hand
		// never moves, a note being drawn -- and which one it was is not known until the button comes
		// back up. See mouseReleased.
		selectingBox = true;
		boxAdditive = event.hasControlDownWithQuirk();
		boxDroppedSelection = !boxAdditive && !selectedNotes.isEmpty();
		dragStartX = selectionEndX = event.x();
		dragStartY = selectionEndY = event.y();
		boxOriginTick = horizontalScroll + (event.x() - rollX) * ticksPerPixel;
		boxOriginMidi = topMidiNote - (event.y() - rollY) / (double)Math.max(1, rowHeight);
		if (!boxAdditive) {
			selectedNotes.clear();
		}
		return true;
	}

	/**
	 * Starts an eraser sweep, taking whatever the press landed on.
	 *
	 * <p>The whole sweep is one undo step, the way a drag across thirty layer switches is. Twenty
	 * separate entries for one movement of the hand would be twenty presses of Ctrl+Z to undo one
	 * mistake, and would evict twenty real edits from a history a hundred deep.</p>
	 */
	private void beginErasing(double x, double y) {
		// Not while another button is already dragging something. Two live drags share one release,
		// and whichever branch answers it first leaves the other one latched -- a right-click during
		// a box select used to be enough to strand the box on screen.
		if (selectingBox || draggingNotes || draggingSplitter || draggingEndMarker || draggingPlayhead
				|| layerDragIndex >= 0 || painting != LayerPaint.NONE) {
			return;
		}
		erasing = true;
		erased = 0;
		lastEraseX = x;
		lastEraseY = y;
		eraseAt(x, y);
	}

	/**
	 * Erases along the path the cursor took, not only where it ended up.
	 *
	 * <p>A drag arrives as one event per frame, and a hand moving at any speed covers more than a
	 * note's seven pixels between two of them -- so sampling only the endpoints leaves a dotted line
	 * of survivors through the middle of the sweep. Stepping along the segment costs a few hit tests
	 * on a gesture that is already deleting things.</p>
	 */
	private void eraseAlong(double toX, double toY) {
		double dx = toX - lastEraseX;
		double dy = toY - lastEraseY;
		int steps = Math.max(1, (int)Math.ceil(
			Math.max(Math.abs(dx), Math.abs(dy)) / ERASE_STEP_PIXELS));
		for (int step = 1; step <= steps; step++) {
			double at = step / (double)steps;
			eraseAt(lastEraseX + dx * at, lastEraseY + dy * at);
		}
		lastEraseX = toX;
		lastEraseY = toY;
	}

	/** Takes the topmost reachable note under one point, if there is one. */
	private void eraseAt(double x, double y) {
		if (!insideRoll(x, y)) {
			return;
		}
		NoteHit hit = noteAt(x, y);
		if (hit == null) {
			return;
		}
		applyMaybeCoalesced("erase notes", project().deleteNotes(Set.of(hit.note().id())), erased > 0);
		erased++;
	}

	/**
	 * Puts a note where you pointed, in the voice you are working in.
	 *
	 * <p>With no layer selected there is no voice for it to join, so it starts one. That is not a
	 * fallback so much as the quickest way to begin a part: click the empty panel, then the roll.</p>
	 *
	 * <p>The new note is <em>not</em> selected, and the selection is cleared instead. Selecting it
	 * read as helpful and made the note before it unreachable: right-click deletes what it points at
	 * only while the selection is empty or is that same note, so with the newest note always sitting
	 * in the selection, right-clicking anything else opened a menu. Drawing four notes and wanting
	 * the second one back is the ordinary case, not an unusual one.</p>
	 */
	private void placeNote(int midi, long tick) {
		ComposerProject before = project();
		if (noLayerSelected() && before.layers().size() >= ComposerProject.MAX_LAYERS) {
			showResult(Component.literal("No layer is selected and there is no room for another - "
				+ ComposerProject.MAX_LAYERS + " is the limit. Click a layer to draw into it."));
			return;
		}
		// Sounded first, before a single thing is edited. What follows is a deep comparison of the
		// composition against itself and, mid-playback, a rebuild and re-sort of every scheduled
		// event -- tens of milliseconds on a song of nine thousand notes, all of it between the key
		// going down and anything being audible. Nothing here needs the edit to have happened: the
		// pitch is the one that was asked for, since mouseMidi and NoteEvent clamp to the same range,
		// and the voice is the selected layer's, which is what a new layer would take anyway.
		soundNote(midi, previewInstrument(), previewColor());
		if (noLayerSelected()) {
			ComposerProject started = before.addLayer();
			int layer = started.layers().size() - 1;
			apply("start layer " + (layer + 1) + " with a note",
				started.addNote(layer, midi, tick, started.ppq() / 4L));
			selectOnlyLayer(layer);
			layersChanged();
			rebuildMoveLayerButtons();
		} else {
			apply("add note", before.addNote(before.activeLayerIndex(), midi, tick, before.ppq() / 4L));
		}
		selectedNotes.clear();
	}

	private boolean handleInstrumentMenuClick(double mouseX, double mouseY) {
		NoteRect menu = instrumentMenuRect();
		if (menu == null) {
			return false;
		}
		int gridTop = instrumentMenuGridTop(menu);
		if (mouseY < gridTop && mouseY >= menu.top()) {
			// The tabs. Switching pages is not a pick: nothing about the layer changes, and the
			// palette stays open so that one voice can be tried against another across both.
			instrumentMenuEffects = mouseX >= instrumentTabSplit(menu);
			return true;
		}
		int column = (int)(mouseX - menu.left() - 3) / INSTRUMENT_CELL;
		int row = (int)(mouseY - gridTop) / INSTRUMENT_CELL;
		if (mouseX < menu.left() + 3 || mouseY < gridTop
				|| column < 0 || column >= INSTRUMENT_COLUMNS || row < 0) {
			return false;
		}
		int index = row * INSTRUMENT_COLUMNS + column;
		List<PreviewInstrument> palette = instrumentMenuPalette();
		if (index < 0 || index >= palette.size()) {
			return false;
		}
		PreviewInstrument value = palette.get(index);
		value.play(12);
		// Picking an instrument says nothing about whether the layer is heard. It used to, because
		// silence was one of the instruments; the state letter answers that now.
		// Left open on purpose: every pick plays its sound, so the palette is how you audition one
		// instrument against another. Clicking away is what puts it down.
		updateLayers("set instrument to " + value.name(), instrumentMenuLayer,
			target -> target.withInstrument(value.id()));
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

	/** Whether Minecraft has the keyboard, asked of GLFW rather than inferred from anything. */
	private boolean windowFocused() {
		if (minecraft == null || minecraft.getWindow() == null) {
			return true;
		}
		return GLFW.glfwGetWindowAttrib(minecraft.getWindow().handle(), GLFW.GLFW_FOCUSED)
			== GLFW.GLFW_TRUE;
	}

	@Override
	public void mouseMoved(double x, double y) {
		lastMouseX = x;
		lastMouseY = y;
		// With one menu already open, sliding along the bar opens the next, which is what a menu
		// bar does everywhere else and what makes browsing five of them one gesture. Settings is
		// left out: it opens a screen, and a screen nobody asked for is not a thing to slide onto.
		ToolbarMenu title = menuTitleAt(x, y);
		if (toolbarMenu != ToolbarMenu.NONE && title != null && title != toolbarMenu
				&& title != ToolbarMenu.SETTINGS) {
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
		if (erasing) {
			eraseAlong(event.x(), event.y());
			return true;
		}
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
				setLayerState(List.of(row), paintState, true);
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
		if (draggingRangeHandle != 0) {
			// Held one grid line apart at the least, because a range of nothing is a paste that
			// never advances -- and dragging one end past the other is a gesture nobody means. The
			// line before or after the other end, rather than a rounded width off it, so both ends
			// stay on the grid the box put them on.
			double span = gridSpan();
			long at = Math.max(0L, snapTick(mouseTick(event.x())));
			if (draggingRangeHandle == 1) {
				long limit = gridLineAt(Math.max(0L, gridIndexNear(rangeEnd, span) - 1L), span);
				rangeStart = Math.min(at, limit);
			} else {
				rangeEnd = Math.max(at, gridLineAt(gridIndexNear(rangeStart, span) + 1L, span));
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
			// Held, not latched: letting go of Shift hands the other axis back mid-drag, while the
			// axis it picked is remembered in case you take hold of it again.
			if (shiftDown()) {
				dragAxis = lockedAxis(dragAxis, Math.abs(event.x() - dragStartX),
					Math.abs(event.y() - dragStartY));
				if (dragAxis == DragAxis.TIME) {
					pitchDelta = 0;
				} else if (dragAxis == DragAxis.PITCH) {
					tickDelta = 0L;
				}
			}
			if (tickDelta != dragTickDelta || pitchDelta != dragPitchDelta) {
				dragTickDelta = tickDelta;
				dragPitchDelta = pitchDelta;
				dragPreview = dragBase.moveNotes(selectedNotes, tickDelta, pitchDelta);
			}
			// Once per row crossed, not once per frame: the pitch is what changed, and a note
			// re-struck every frame while the hand sits still is a buzz rather than a pitch.
			if (pitchDelta != dragHeardPitchDelta) {
				dragHeardPitchDelta = pitchDelta;
				soundHeldNote(pitchDelta);
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
		if (erasing) {
			erasing = false;
			// Reported only for a sweep. Taking one note is a click whose result you are looking at;
			// taking nineteen off the far end of a passage is worth a number and a way back.
			if (erased > 1) {
				showResult(Component.literal("Erased " + erased + " notes. Ctrl+Z puts them back."));
			}
			return true;
		}
		if (draggingSplitter) {
			draggingSplitter = false;
			FastNoteblocksConfig.save();
			return true;
		}
		if (painting != LayerPaint.NONE) {
			// A run of state changes is worth a word, because it is now a run of layers going into
			// or out of the build and the panel alone does not say how many that came to.
			boolean report = paintedRows.size() > 1;
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
			boolean collapse = layerDragCollapse;
			layerDragIndex = -1;
			layerDragActive = false;
			layerDragCollapse = false;
			if (reordering) {
				dropLayers(from, layerDropIndex(event.y()));
				return true;
			}
			if (collapse) {
				// The press held the selection together in case this became a drag. It did not, so
				// it was a plain click after all and means what a plain click means.
				selectLayer(from, false, false);
				return true;
			}
		}
		if (draggingRangeHandle != 0) {
			draggingRangeHandle = 0;
			return true;
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
			dragHeldNoteId = -1L;
			dragHeldLayer = -1;
			// The passage goes with the notes, so the range that measures it goes too. Taken while
			// the drag is still on, because the clamp is worked out from where the notes are now
			// and a moment later that is where they have gone.
			long rangeShift = hasRange() ? rangeDragDelta() : 0L;
			draggingNotes = false;
			if (dragPreview != null) {
				apply(dragAxis == DragAxis.PITCH ? "transpose notes" : "move notes", dragPreview);
				if (rangeShift != 0L) {
					rangeStart = Math.max(0L, rangeStart + rangeShift);
					rangeEnd = Math.max(rangeStart + 1L, rangeEnd + rangeShift);
				}
			}
			dragPreview = null;
			dragBase = null;
			return true;
		}
		if (selectingBox) {
			selectingBox = false;
			horizontalEdgeSince = 0L;
			verticalEdgeSince = 0L;
			// A press that never travelled is a click, and with a layer selected a click on empty
			// roll draws a note there. Selecting a layer is how you say "this is the part I am
			// writing", so the plain gesture in that state is writing rather than selecting -- and
			// the box is still there the moment the hand moves, which is what tells them apart.
			//
			// Unless there was a selection, which this click has just dropped. Clicking off a thing
			// is how anyone puts it down, and a click that both put the selection down and left a
			// note behind meant you could not stop having a selection without making an edit. The
			// next click, with nothing left to drop, draws.
			//
			// Ctrl is the other exception: it means "add to what is selected", which is a selection
			// gesture whether or not it moved. With no layer selected there is nothing to draw into,
			// so a click there stays what it was.
			if (!boxAdditive && !boxDroppedSelection && !noLayerSelected()
					&& !pressClaimedFocus && !travelled(event.x(), event.y())) {
				placeNote(mouseMidi(dragStartY), snapTickInto(mouseTick(dragStartX)));
				return true;
			}
			selectNotesInBox();
			// The drag's span outlives the drag. Which notes it took is settled here; how long the
			// passage they came from is stays on screen to be read and adjusted. A click that took
			// nothing is how a selection is put down, so it puts the range down too.
			if (selectedNotes.isEmpty()) {
				clearRange();
			} else if (travelled(event.x(), event.y())) {
				setRangeFromBox(selectionEndX);
			}
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
				zoomPitch(scrollY > 0 ? 1 : -1, mouseY);
				return true;
			}
			scrollPitch(scrollY);
			return true;
		}
		if (!insideRoll(mouseX, mouseY)) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		if (altDown() && controlDown()) {
			// The pitch zoom, without having to put the cursor on the strip of keys first -- the
			// same reason Alt on its own scrolls pitch here. Ctrl is zoom and Alt is the pitch axis,
			// so the two together are the pitch zoom and nothing has to be remembered.
			zoomPitch(scrollY > 0 ? 1 : -1, mouseY);
			return true;
		}
		if (altDown()) {
			// Up and down the pitch range without having to put the cursor on the keyboard first.
			// The keyboard is a strip a few dozen pixels wide at the left-hand edge, so scrolling
			// pitch meant leaving whatever you were looking at to reach it and coming back.
			//
			// Alt rather than Shift because Shift is already the fast horizontal scroll, and a
			// modifier that means one thing over the roll and another over the keys is worse than
			// one more modifier.
			scrollPitch(scrollY);
			return true;
		}
		if (controlDown()) {
			zoomTime(scrollY > 0 ? ZOOM_IN : ZOOM_OUT, mouseX);
			return true;
		}
		if (shiftDown()) {
			// Fast scroll: a quarter of whatever is on screen per notch, rather than a fixed number
			// of beats. A beat a notch is fine when the roll holds a few bars and useless when it
			// holds four hundred -- and the whole reason to reach for this is that you are zoomed
			// out. Floored at twice the plain step so it can never be slower than what it modifies;
			// it used to be floored at a bar, which zoomed in is wider than the window, so the fast
			// scroll moved past a whole screen of music in one notch and landed somewhere with
			// nothing in common with where it started.
			long span = Math.max(2L * scrollStepTicks(), Math.round(rollWidth * ticksPerPixel / 4.0));
			horizontalScroll = Math.max(0L, horizontalScroll - Math.round(scrollY * span));
			return true;
		}
		horizontalScroll = Math.max(0L,
			horizontalScroll - Math.round(scrollY * scrollStepTicks()));
		return true;
	}

	/**
	 * How far one notch of the wheel moves the roll sideways.
	 *
	 * <p>A beat, capped at a tenth of what is on screen. A beat on its own is a fixed musical
	 * amount and a wildly varying visual one: zoomed out it is two pixels, and zoomed all the way
	 * in it is a third of the window, so three notches put you somewhere with nothing in common
	 * with where you started and no way to tell what had happened. The cap only ever binds when
	 * zoomed in, which is where a notch has to be small enough to follow.</p>
	 */
	private long scrollStepTicks() {
		double beat = Math.max(1.0, project().ppq());
		return Math.max(1L, Math.round(Math.min(beat, rollWidth * ticksPerPixel / 10.0)));
	}

	/** Moves the roll up and down the pitch range, from the keys or from the roll itself. */
	private void scrollPitch(double scrollY) {
		topMidiNote = Math.max(12, Math.min(MAX_MIDI_NOTE,
			topMidiNote + (scrollY > 0 ? 3 : -3)));
	}

	/**
	 * Zooms in time, holding whatever is at {@code anchorX} still.
	 *
	 * <p>Anchored rather than centred, so a zoom is a thing you aim: the tick under the anchor is
	 * where it was before and after, and everything else moves around it. From the wheel that
	 * anchor is the cursor; from the keyboard there is no cursor to speak of, so it is the middle
	 * of the roll -- what you are looking at.</p>
	 */
	private void zoomTime(double factor, double anchorX) {
		long anchoredTick = mouseTick(anchorX);
		ticksPerPixel = Math.max(1.5, Math.min(maxTicksPerPixel(), ticksPerPixel * factor));
		horizontalScroll = Math.max(0L,
			anchoredTick - Math.round((anchorX - rollX) * ticksPerPixel));
	}

	/** Zooms in pitch -- taller or shorter rows -- holding the row at {@code anchorY} still. */
	private void zoomPitch(int steps, double anchorY) {
		int anchoredMidi = mouseMidi(anchorY);
		rowHeight = Math.max(MIN_ROW_HEIGHT, Math.min(MAX_ROW_HEIGHT, rowHeight + steps));
		topMidiNote = Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE,
			anchoredMidi + (int)Math.floor((anchorY - rollY) / rowHeight)));
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
		if (snapMenuOpen && event.isEscape()) {
			snapMenuOpen = false;
			return true;
		}
		// Ahead of the screen's own Escape, which closes the composer. Stopping a take is what you
		// mean by it while one is running, and leaving the screen mid-record is not.
		if (recording && event.isEscape()) {
			stopRecording("Stopped");
			return true;
		}
		// A focused text box owns the keyboard. Enter and Escape are the two keys that are about the
		// box rather than in it; everything else goes to the box and stops there.
		//
		// It used to fall through to the roll's shortcuts, which quietly aimed them at the song
		// behind the name being typed: Ctrl+A selected every note instead of the text, Backspace
		// deleted the selected notes instead of a character, the arrow keys retimed and transposed
		// them, and Ctrl+V pasted a phrase into the composition. None of that was visible, because
		// what you were looking at was a text box.
		if (layerNameBox != null) {
			if (event.isConfirmation()) {
				commitLayerRename();
				return true;
			}
			if (event.isEscape()) {
				cancelLayerRename();
				return true;
			}
			return super.keyPressed(event);
		}
		// Escape puts the selection down before it closes the screen. Standing on a screen with
		// something selected, Escape means "never mind this", and leaving the composer is the last
		// thing it can mean -- a second press, with nothing left to put down, still does that.
		if (event.isEscape() && dropSelection()) {
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_F9) {
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
		if (event.key() == GLFW.GLFW_KEY_SPACE) {
			if (playing || anythingAudible()) {
				togglePlayback();
			}
			return true;
		}
		// Space plays from the marker, Enter takes the marker back to the top and plays -- the pair
		// every sequencer has. Claimed here rather than left to fall through, because falling through
		// pressed whichever button had the focus, which in this screen is "+ Layer": hitting Enter to
		// hear the song from the start added an empty layer to it instead.
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			playFromStart();
			return true;
		}
		// Beside Space and Enter because it is the third transport key. Bare R, so Ctrl+R is left to
		// mean whatever it comes to mean.
		if (event.key() == GLFW.GLFW_KEY_R && !event.hasControlDownWithQuirk()
				&& !event.hasShiftDown()) {
			toggleRecording();
			return true;
		}
		// M for marker, bare, next to the transport keys because it is aimed at the same thing they
		// are: wherever the playback marker is standing.
		if (event.key() == GLFW.GLFW_KEY_M && !event.hasControlDownWithQuirk()
				&& !event.hasShiftDown()) {
			toggleMarkerAtCursor();
			return true;
		}
		// Zoom, on the keys every application puts it on. Ctrl is the zoom and Alt is the pitch axis,
		// which is the same pair the wheel uses, so neither has to be learned twice. Ahead of the
		// pane routing because a zoom is about the view and not about what is selected -- there is
		// nothing in the layer panel it could mean instead.
		//
		// The middle of the roll is what is held still. The wheel anchors on the cursor because
		// there is one; a key press has no cursor to speak of, and the middle of what you are
		// looking at is the next best answer to "what am I zooming towards".
		if (event.hasControlDownWithQuirk() && zoomKeyDirection(event.key()) != 0) {
			int direction = zoomKeyDirection(event.key());
			if (altDown()) {
				zoomPitch(direction, rollY + rollHeight / 2.0);
			} else {
				zoomTime(direction > 0 ? ZOOM_IN : ZOOM_OUT, rollX + rollWidth / 2.0);
			}
			return true;
		}
		// The other half of Ctrl+A, and the shape every editor gives it. Ahead of isSelectAll, which
		// does not look at Shift and would otherwise answer this one too.
		if (event.hasControlDownWithQuirk() && event.hasShiftDown()
				&& event.key() == GLFW.GLFW_KEY_A) {
			dropSelection();
			return true;
		}
		// From here to the arrow keys, everything follows the pane that holds the keyboard.
		boolean onLayers = focusedPane == Pane.LAYERS;
		if (event.isSelectAll()) {
			if (onLayers) {
				selectedLayers.clear();
				for (int index = 0; index < project().layers().size(); index++) {
					selectedLayers.add(index);
				}
				return true;
			}
			selectedNotes.clear();
			clearRange();
			for (int layerIndex : selectionLayers()) {
				project().layers().get(layerIndex).notes()
					.forEach(note -> selectedNotes.add(note.id()));
			}
			return true;
		}
		if (event.isCopy()) {
			if (onLayers) {
				copyLayers();
			} else {
				copySelection();
			}
			return true;
		}
		if (event.isCut()) {
			if (onLayers) {
				copyLayers();
				deleteSelectedLayers();
			} else {
				copySelection();
				deleteSelectedNotes();
			}
			return true;
		}
		if (event.isPaste()) {
			if (onLayers) {
				pasteLayers();
			} else {
				pasteClipboard(false);
			}
			return true;
		}
		// isPaste is Ctrl+V with no shift, so the shifted one is free for the variant of it -- the
		// same shift-a-variant convention Ctrl+Shift+S and Ctrl+Shift+C already follow here.
		if (event.hasControlDownWithQuirk() && event.hasShiftDown()
				&& event.key() == GLFW.GLFW_KEY_V && !onLayers) {
			pasteClipboard(true);
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
		if (event.hasControlDownWithQuirk()) {
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
					if (onLayers) {
						mergeSelectedLayers();
					}
					return true;
				}
				case GLFW.GLFW_KEY_D -> {
					if (onLayers) {
						duplicateLayers(sortedSelectedLayers());
					} else {
						duplicateSelection();
					}
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
		// One rule instead of the old chain: whichever pane holds the keyboard is what Delete is
		// pointing at, and if that pane has nothing selected the key does nothing. Backspace reaches
		// layers too now -- it was held back only because Delete could arrive at them by accident,
		// and it no longer can.
		if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
			if (onLayers) {
				deleteSelectedLayers();
			} else {
				deleteSelectedNotes();
			}
			return true;
		}
		if (!selectedNotes.isEmpty() && (event.isLeft() || event.isRight() || event.isUp() || event.isDown())) {
			long tickDelta = event.isLeft() ? -nudgeToNextLine(-1)
				: event.isRight() ? nudgeToNextLine(1) : 0L;
			int pitchDelta = event.isUp() ? (event.hasControlDownWithQuirk() ? 12 : 1)
				: event.isDown() ? (event.hasControlDownWithQuirk() ? -12 : -1) : 0;
			apply(pitchDelta != 0 ? "transpose notes" : "move notes",
				project().moveNotes(selectedNotes, tickDelta, pitchDelta));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void tick() {
		updateCountIn();
		updatePlayback();
		// The marker running is the whole of record mode, so the take ends when it stops -- at the end
		// marker, or because Stop was pressed.
		if (recording && countingInSince == 0L && !playing) {
			stopRecording("Finished");
		}
		updateBoxScroll();
		updateButtonStates();
		syncRangeToSelection();
		windowWasFocused = windowFocused();
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
			horizontalScroll = Math.max(0L, horizontalScroll + Math.round(pixels * ticksPerPixel));
		}

		double vertical = below > 0 ? below : above > 0 ? -above : 0.0;
		verticalEdgeSince = vertical == 0.0 ? 0L
			: verticalEdgeSince == 0L ? Util.getMillis() : verticalEdgeSince;
		if (vertical != 0.0) {
			double runway = below > 0 ? height - (rollY + rollHeight) : rollY;
			double rows = Math.signum(vertical)
				* edgeRamp(Math.abs(vertical), runway, verticalEdgeSince)
				* BOX_SCROLL_MAX_ROWS;
			topMidiNote = Math.max(MIN_MIDI_NOTE, Math.min(MAX_MIDI_NOTE,
				topMidiNote - (int)Math.round(rows)));
		}
		selectionEndX = lastMouseX;
		selectionEndY = lastMouseY;
	}

	/**
	 * A song file dragged onto the window from the desktop.
	 *
	 * <p>An import replaces what is on screen, so it goes through the same unsaved check the Import
	 * menu entry does. A drop is easy to make by accident in a way that choosing a menu entry is
	 * not, and losing an hour's work to a slipped mouse is not a thing to find out about
	 * afterwards.</p>
	 */
	@Override
	public void onFilesDrop(List<Path> dropped) {
		Path file = dropped == null ? null : dropped.stream()
			.filter(Files::isRegularFile)
			.filter(SongImports::importable)
			.findFirst()
			.orElse(null);
		if (file == null) {
			showResult(Component.literal(
				"Drop a .mid, .midi, .nbs, .nbt, .schem or .litematic file to import it."));
			return;
		}
		withUnsavedChangesChecked(() ->
			SongImports.openDropped(this, config, file, this::applyImportedProject));
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
			// From the marker, not from wherever the last run's edits left the anchor.
			playbackStartTick = playbackReturnTick;
			playbackStartedAt = Util.getMillis();
			resetPlaybackSchedule();
			playButton.setMessage(playLabel());
		}
	}

	/**
	 * Record mode: the marker runs and the piano keys write into the song.
	 *
	 * <p>The composer could already place a note at a tick and play a song from a tick, and had no
	 * way to do both at once -- so writing a part meant working out which cell each note belonged in
	 * and clicking there, which is transcription rather than playing. This is the same two things
	 * with the clock left running: where the marker is <em>is</em> the cell.</p>
	 *
	 * <p>It counts in first because the alternative is that the first note of a take is always late.
	 * Three beats of the song's own tempo rather than three seconds, so the count is the tempo you
	 * are about to play against, and each one clicks so you can hear it without watching it.</p>
	 */
	private void toggleRecording() {
		if (recording) {
			stopRecording("Stopped");
			return;
		}
		// Somewhere for the notes to go, decided before the count rather than at the first keypress:
		// with no layer selected every note would otherwise start a layer of its own.
		ComposerProject prepared = project();
		int newLayer = -1;
		if (noLayerSelected()) {
			if (prepared.layers().size() >= ComposerProject.MAX_LAYERS) {
				showResult(Component.literal("No layer is selected and there is no room for another - "
					+ ComposerProject.MAX_LAYERS + " is the limit. Select a layer to record into."));
				return;
			}
			prepared = prepared.addLayer();
			newLayer = prepared.layers().size() - 1;
		}
		// And room to play into. Playback stops at the end marker, and a new song's marker is four
		// beats out -- so without this a take ends before it has started. Pushed out for the take and
		// pulled back to whichever is longer of where it was and what got recorded, so a take that
		// runs short does not leave four minutes of silence on the end of the song.
		endBeforeTake = prepared.endTick();
		prepared = prepared.withEndTick(
			endBeforeTake + RECORD_HEADROOM_BARS * prepared.ppq() * 4L);
		apply(newLayer < 0 ? "make room for a take" : "add a layer and room for a take", prepared);
		if (newLayer >= 0) {
			selectOnlyLayer(newLayer);
			layersChanged();
			rebuildMoveLayerButtons();
		}
		recording = true;
		recorded = 0;
		takeNotes.clear();
		countingInSince = Util.getMillis();
		countedIn = 0;
		stopPlayback();
		updateButtonStates();
	}

	/**
	 * Where a note played just now belongs, allowing for how late "just now" already is.
	 *
	 * <p>A played note reaches this clock later than the beat it was aimed at, by the sound engine's
	 * output latency -- you hear the song late, so you play late by the same amount and are in time
	 * with what you heard -- plus whatever the hand adds. Taking that off before snapping is what
	 * makes the beat you meant the beat you get.</p>
	 *
	 * <p>Flooring instead of snapping to the nearest line does the same job by accident: floor is
	 * nearest shifted half a cell earlier. It was better than plain nearest for exactly this reason,
	 * and it is still the wrong shape, because half a cell is 12ms on the fastest song in this
	 * library and 100ms on the slowest -- it under-corrects fast songs and over-corrects slow ones,
	 * while the thing being corrected for does not change with tempo at all. A constant in
	 * milliseconds does not have that fault, and it has no cliff: playing earlier than usual lands
	 * nearer the beat rather than a whole cell before it.</p>
	 */
	private long recordTick() {
		long micros = RECORD_LATENCY_MILLIS * 1000L;
		long late = Math.round(micros * project().ppq() * timescaleFactor()
			/ (double)project().tempoMicrosPerQuarter());
		return Math.max(0L, playbackTick() - late);
	}

	/** The id of the note most recently added, which is the highest one the project has issued. */
	private long newestNoteId() {
		return project().nextNoteId() - 1L;
	}

	/** How long the count-in has left, or 0 once it is over. */
	private long countInRemaining() {
		if (countingInSince == 0L) {
			return 0L;
		}
		return Math.max(0L,
			countingInSince + COUNT_IN_BEATS * countInBeatMillis() - Util.getMillis());
	}

	/**
	 * One beat of the song at the speed it will play back at, which is what to count against.
	 *
	 * <p>Floored, because a count-in faster than the hand can move is a flourish rather than a
	 * count -- on a fast song three beats went by in under a second.</p>
	 */
	private long countInBeatMillis() {
		return Math.max(COUNT_IN_MIN_BEAT_MILLIS, Math.round(
			project().tempoMicrosPerQuarter() / 1000.0 / timescaleFactor()));
	}

	/** Which number the count is showing, 3 down to 1, or 0 when it is not counting. */
	private int countInNumber() {
		long left = countInRemaining();
		if (left <= 0L) {
			return 0;
		}
		return Math.max(1, Math.min(COUNT_IN_BEATS / BEATS_PER_COUNT,
			(int)Math.ceil(left / (double)(BEATS_PER_COUNT * countInBeatMillis()))));
	}

	/**
	 * Advances the count-in, clicking once a beat, and starts the take when it runs out.
	 *
	 * <p>Driven from {@code tick} rather than from drawing, so it counts at the same rate whatever
	 * the frame rate is doing -- and a count-in that drifts with the frame rate is worse than none.</p>
	 */
	private void updateCountIn() {
		if (!recording || countingInSince == 0L) {
			return;
		}
		long beat = countInBeatMillis();
		long elapsed = Util.getMillis() - countingInSince;
		// Six beats long, so there is time to get ready, but a click only where a number lands: three
		// of them, on the beats you would say "three, two, one" on. Clicking the halfway beats as well
		// filled the gap with ticks that were not the count and read as a faster tempo than the one
		// about to play.
		int beats = Math.min(COUNT_IN_BEATS, (int)(elapsed / beat) + 1);
		if (beats > countedIn) {
			countedIn = beats;
			if ((beats - 1) % BEATS_PER_COUNT == 0) {
				PreviewInstrument.byId("HAT").play(18);
			}
		}
		if (elapsed >= COUNT_IN_BEATS * beat) {
			countingInSince = 0L;
			if (!playing) {
				togglePlayback();
			}
		}
	}

	/** Whether a keypress would land in the song right now, as opposed to only being heard. */
	private boolean takingNotes() {
		return recording && countingInSince == 0L && playing;
	}

	private void stopRecording(String why) {
		if (!recording) {
			return;
		}
		String into = activeLayer().name();
		recording = false;
		countingInSince = 0L;
		takeNotes.clear();
		stopPlayback();
		// The end marker goes back to whichever is longer: where it was, or the last note recorded.
		// withEndTick floors at the last note on its own, so one call says both. Folded into the step
		// the last note made rather than taking one of its own -- putting the room back is part of
		// the take, not an edit you would want to undo separately from it.
		applyMaybeCoalesced("record a take", project().withEndTick(endBeforeTake), true);
		updateButtonStates();
		showResult(Component.literal(why + " recording - " + recorded
			+ (recorded == 1 ? " note" : " notes")
			+ (recorded > 0 ? " into \"" + into + "\". Ctrl+Z takes them back." : " written.")));
	}

	/**
	 * What the record button says, which is three letters wide either way.
	 *
	 * <p>It used to read Record and Recording, and a control is as wide as the longest thing it can
	 * ever say -- so nine characters of caption were being paid for at every width, on a cluster
	 * pinned to the right-hand edge where the room runs out first. Rec is not ambiguous next to
	 * Play, and the dot is the mark every recorder has used for it.</p>
	 */
	private Component recordLabel() {
		return Component.literal(recording ? "\u25cf Rec" : "Rec");
	}

	/** Plays from the top, which is the one place worth a key of its own. */
	private void playFromStart() {
		// setPlaybackStart restarts the clock on its own when something is already playing, so this
		// only has to decide whether there is anything to start.
		setPlaybackStart(0L, false);
		if (!playing && anythingAudible()) {
			togglePlayback();
		}
	}

	private void setPlaybackStart(long tick, boolean preview) {
		movePlayheadTo(snapTick(tick));
		if (preview) {
			showResult(Component.literal("Playback start: tick " + playbackReturnTick));
		}
	}

	/**
	 * Puts the marker on an exact tick, without the snap the mouse gets.
	 *
	 * <p>Dragging the marker snaps because a hand cannot hit a tick. A tick arrived at by arithmetic
	 * -- the end of a paste, say -- already is one, and snapping it to a grid chosen for something
	 * else can only move it off the position it was computed to be.</p>
	 */
	private void movePlayheadTo(long tick) {
		playbackReturnTick = Math.max(0L, Math.min(project().endTick(), tick));
		playbackStartTick = playbackReturnTick;
		if (playing) {
			playbackStartedAt = Util.getMillis();
			resetPlaybackSchedule();
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
				lightKey(event.note() + ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE, event.color());
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
			int color = vivid(layerColor(layerIndex));
			List<NoteEvent> notes = layer.notes();
			for (int index = lowerBoundStart(notes, playbackStartTick); index < notes.size(); index++) {
				NoteEvent note = notes.get(index);
				if (!takeNotes.isEmpty() && takeNotes.contains(note.id())) {
					continue;
				}
				events.add(new PlaybackEvent(
					note.startTick(),
					instrument,
					note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE,
					color
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

	/**
	 * Steps back, and says what it took back.
	 *
	 * <p>Read before the move, because after it the label has changed sides. Said out loud because
	 * the history is a hundred deep and nothing else on the screen answers "what did that just
	 * undo" -- on a song of nine thousand notes an undo can move something off screen entirely, and
	 * a silent Ctrl+Z is then indistinguishable from one that did nothing.</p>
	 */
	private void undo() {
		String label = history.undoLabel();
		long cursor = history.undoCursor();
		history.undo(playbackReturnTick);
		afterHistoryMove();
		restoreCursor(cursor);
		showResult(Component.literal(label == null ? "Nothing left to undo." : "Undo: " + label));
	}

	private void redo() {
		String label = history.redoLabel();
		long cursor = history.redoCursor();
		history.redo(playbackReturnTick);
		afterHistoryMove();
		restoreCursor(cursor);
		showResult(Component.literal(label == null ? "Nothing left to redo." : "Redo: " + label));
	}

	/**
	 * Puts the time marker back where the step being undone or redone found it.
	 *
	 * <p>A paste and a duplicate move the marker as part of what they do -- that is what makes
	 * holding the key lay a passage down -- so taking one back and leaving the marker four bars on
	 * takes back half of it. Every other edit leaves the marker alone and records nothing, so
	 * undoing an old one does not drag you back to where you were standing at the time.</p>
	 *
	 * <p>After {@link #afterHistoryMove}, whose clamp is against the composition that has just been
	 * restored: an undone paste may have shortened the song, and the marker cannot stand past its
	 * end.</p>
	 */
	private void restoreCursor(long tick) {
		if (tick == ComposerHistory.NO_CURSOR) {
			return;
		}
		if (playing) {
			// The marker on screen is the playhead while something is running, and throwing the
			// running position across the song is not what Ctrl+Z asked for.
			playbackReturnTick = Math.max(0L, Math.min(project().endTick(), tick));
		} else {
			movePlayheadTo(tick);
			revealTick(playbackReturnTick);
		}
	}

	private void afterHistoryMove() {
		clampPlaybackToEnd();
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

	/**
	 * Records an edit under the name undo will give it.
	 *
	 * <p>The name is a short verb phrase reading as the object of "Undo" -- "add note", "paste 12
	 * notes" -- because that is the only place it is ever shown. Undo used to be an unnamed step in
	 * a stack a hundred deep, so pressing Ctrl+Z on a screen full of nine thousand notes was a guess
	 * about what would move.</p>
	 */
	private void apply(String label, ComposerProject project) {
		apply(label, project, ComposerHistory.NO_CURSOR);
	}

	/** The same, for an edit that moves the time marker and should put it back when undone. */
	private void apply(String label, ComposerProject project, long cursorBefore) {
		anchorPlayhead();
		history.apply(label, project, cursorBefore);
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
	 *
	 * <p>It moves the clock's anchor and nothing else. Moving the marker as well is what made
	 * changing a layer's instrument mid-song silently redefine where Stop would put you.</p>
	 */
	private void anchorPlayhead() {
		if (playing) {
			playbackStartTick = Math.max(0L, Math.min(project().endTick(), playbackTick()));
			playbackStartedAt = Util.getMillis();
		}
	}

	/** Neither position may sit past an end marker an edit has just pulled in behind it. */
	private void clampPlaybackToEnd() {
		playbackStartTick = Math.min(playbackStartTick, project().endTick());
		playbackReturnTick = Math.min(playbackReturnTick, project().endTick());
	}

	private void afterStateChange() {
		clampPlaybackToEnd();
		// Not during a take. A take's own notes are kept out of its schedule, so nothing it writes
		// can change what is left to play -- and rebuilding meant sorting every event in the song
		// again on every key press, which is the one moment in this screen where a millisecond of
		// delay is something you can hear.
		if (playing && !takingNotes()) {
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
				apply("rename composition", project().withName(name.trim()));
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
	/** What the build sequence now holds, for confirming a dot change did what was expected. */
	/** How many layers are left out of the build because they are muted or hidden. */
	private int leftOutLayers() {
		return (int)project().layers().stream().filter(layer -> !layer.inBuild()).count();
	}

	private String sequenceSummary() {
		var sequence = config.tracks();
		if (sequence.isEmpty()) {
			return "Every layer is muted or hidden, so the build is empty.";
		}
		SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(sequence);
		String report = "Build: " + sequence.size() + " of " + project().layers().size()
			+ (project().layers().size() == 1 ? " layer" : " layers")
			+ ", " + blocks.noteBlocks() + " note blocks, " + blocks.repeaters() + " repeaters";
		int out = leftOutLayers();
		if (out > 0) {
			report += "; " + out + (out == 1 ? " layer is" : " layers are")
				+ " muted or hidden and left out";
		}
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
			showResult(Component.literal("Nothing to build: every layer with notes on it is muted "
				+ "or hidden. Set one back to Active with the letter beside its name."));
			return;
		}
		// The last chance to notice. What is built is what you can hear, so a layer muted an hour
		// ago to listen around it is a layer that will not be in the world -- and this is the one
		// moment where that is expensive to find out afterwards.
		int leftOut = leftOutLayers();
		if (leftOut > 0) {
			showResult(Component.literal(leftOut + (leftOut == 1 ? " layer is" : " layers are")
				+ " muted or hidden, so " + (leftOut == 1 ? "it is" : "they are")
				+ " not in this build."));
		}
		minecraft.gui.setScreen(new BuildOptionsScreen(this, project().name(), config.tracks(),
				project(), config.dedupeIdenticalNotes(), pasteMode(), mode -> {
			SongBuilder.PastePlan plan;
			try {
				plan = SongBuilder.plan(minecraft, config.tracks(), mode, project(),
					config.dedupeIdenticalNotes());
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
				minecraft.gui.setScreen(new DenseBuildScreen(this, plan, () -> {
						CommandPasteSender.start(plan.commands(), plan.report());
						minecraft.gui.setScreen(null);
					}));
				return;
			}
			CommandPasteSender.start(plan.commands(), plan.report());
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
	 * Whether there is work here that leaving would throw away.
	 *
	 * <p>Compares the music and not the whole document. The record carries the active layer along
	 * with the notes, so clicking a different layer used to make the composition compare unequal to
	 * the one it came from -- looking around the composer and leaving asked whether to save changes
	 * nobody had made.</p>
	 *
	 * <p>Cached on the identity of both sides, which is sound because compositions are immutable:
	 * the comparison itself walks every note of every layer, and the toolbar asks once a frame.</p>
	 */
	private boolean unsavedEdits() {
		ComposerProject current = project();
		if (unsavedCacheProject != current || unsavedCacheBaseline != savedProject) {
			unsavedCacheProject = current;
			unsavedCacheBaseline = savedProject;
			unsavedCacheResult = !current.sameContentAs(savedProject);
		}
		return unsavedCacheResult;
	}

	/**
	 * Whether the composition differs from the copy on disk, which is what the title reports.
	 *
	 * <p>Wider than {@link #unsavedEdits} by one case, and the difference is the whole reason there
	 * are two. An untouched import has no unsaved work in it, so leaving does not ask -- but it has
	 * no file either, so the title says unsaved until Save gives it one. Warning about it and
	 * stopping you over it are different bars.</p>
	 *
	 * <p>A blank document with no file is not called unsaved, because there is nothing in it to
	 * save. It picks the mark up the moment it holds a note.</p>
	 */
	private boolean unsaved() {
		return unsavedEdits() || (!config.hasSongFile() && !isBlank(project()));
	}

	private static boolean isBlank(ComposerProject song) {
		return song.layers().stream().allMatch(layer -> layer.notes().isEmpty());
	}

	/**
	 * Asks about unsaved edits, then runs {@code leave}.
	 *
	 * <p>Every way out of the composer goes through here -- closing it, opening another song,
	 * importing over this one. Each of those replaces what is in memory, so each is a last chance
	 * to keep it.</p>
	 */
	private void withUnsavedChangesChecked(Runnable leave) {
		if (!unsavedEdits()) {
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
		if (config.hasSongFile()) {
			config.discardComposerEdits();
		} else {
			// No file to go back to, but there is what the document was when it opened -- for an
			// import, the import. Blanking it here would discard the file you picked as well as the
			// edits you declined, and only one of those was offered.
			config.setComposerProject(savedProject);
		}
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
			apply("change the playback speed", next);
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
			apply("move the end marker", next);
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

	private void startPainting(LayerPaint kind, int fromRow, LayerState state) {
		painting = kind;
		paintState = state;
		paintedRows.clear();
		paintedRows.addAll(layersToEdit(fromRow));
	}

	/** Which row a point is on, whatever part of the row it lands in. */
	private int layerRowAt(double x, double y) {
		int inset = layerRowLayout().inset();
		return x < inset || x >= layerPanelWidth() - rightGutter(inset) ? -1 : layerRowAtY(y);
	}

	/** The row at a height, for gestures that have already decided which column they are in. */
	private int layerRowAtY(double y) {
		if (y < LAYER_LIST_TOP - 2 || y > layerListBottom()) {
			return -1;
		}
		int index = (int)Math.floor((y - (LAYER_LIST_TOP - 2 - layerScroll)) / LAYER_ROW_HEIGHT);
		return index >= 0 && index < project().layers().size() && layerRowVisible(index) ? index : -1;
	}

	/**
	 * The build dot's clickable box: the glyph and a little air, and no more than that.
	 *
	 * <p>It used to reach ten pixels past the glyph, which was harmless while the number was out at
	 * the panel's edge and swallows clicks meant for the number now that the two sit together.</p>
	 */
	/** The one control that decides whether a layer is soloed, heard, silent or gone. */
	private int layerStateAt(double x, double y) {
		LayerRowLayout row = layerRowLayout();
		return row.chip() && x >= row.stateX() && x < row.stateX() + LAYER_CHIP + 2
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
		int iconsFrom = row.chip() ? row.stateX() : row.instrumentX();
		boolean onIcons = x >= iconsFrom && x < row.instrumentX() + 17;
		return !onIcons ? layerRowAt(x, y) : -1;
	}

	/**
	 * Panel that belongs to no layer, which is the way into no-layer mode.
	 *
	 * <p>Three places, and they are all the same place as far as the eye is concerned: the run under
	 * the last row, the gutter either side of every row, and the header strip beside its fold
	 * control. {@link #maxLayerScroll()} keeps a row of the first in reach however many layers there
	 * are, because a mode you can only enter when the list happens not to be full is not a mode.</p>
	 */
	private boolean overLayerPanelBlank(double x, double y) {
		if (x < 0 || x >= layerPanelWidth()) {
			return false;
		}
		if (y >= TOOLBAR_HEIGHT && y < LAYER_LIST_TOP - 2) {
			return !overLayerPanelFold(x, y) && !overOpenMenu(x, y);
		}
		// layerRowAt, not layerRowAtY: it is the one that knows about the gutters, which are panel
		// that no row is drawn on and so read as empty however close to a row they sit.
		return y >= LAYER_LIST_TOP - 2 && y < layerListBottom() && layerRowAt(x, y) < 0;
	}

	/** The white-and-black key strip down the roll's left edge, which plays what you point at. */
	private boolean overPianoKeys(double x, double y) {
		return x >= layerPanelWidth() && x < rollX && y >= rollY && y < rollY + rollHeight;
	}

	/**
	 * The fold control in the strip above the list: the arrow and the word beside it, and no more.
	 *
	 * <p>It used to be the whole strip, which spent the panel's widest piece of empty space on an
	 * action that has a perfectly good two-character target. That space is worth more as somewhere
	 * to click for no layer at all -- see {@link #overLayerPanelBlank}.</p>
	 *
	 * <p>Not while a menu is standing over it either: the instrument palette opens as high as four
	 * pixels under the toolbar, so its top row sits on this strip and picking an instrument from
	 * there folded the whole panel instead.</p>
	 */
	private boolean overLayerPanelFold(double x, double y) {
		LayerRowLayout row = layerRowLayout();
		int right = row.inset() + (row.name() ? 10 + font.width("Layers") : font.width(">")) + 3;
		return x >= 0 && x < Math.min(right, layerPanelWidth())
			&& y >= TOOLBAR_HEIGHT && y < LAYER_LIST_TOP - 2
			&& !overOpenMenu(x, y);
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
		// Over the name it is replacing, wherever the row's width has put that.
		int nameLeft = layerRowLayout().nameLeft();
		layerNameBox = new EditBox(font, nameLeft - 3, y - 2, layerNameRight() - nameLeft + 6,
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

	/**
	 * How far the list scrolls, with one row of empty panel kept past the end.
	 *
	 * <p>The slack is the point, not slop: empty panel is what you click to select no layer at all,
	 * and a list of forty layers on a tall window has none of its own to offer.</p>
	 */
	private int maxLayerScroll() {
		return Math.max(0,
			layerContentHeight() + LAYER_ROW_HEIGHT - (layerListBottom() - LAYER_LIST_TOP));
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
		updateLayer("rename layer", layerIndex, layer.withName(name));
	}

	private void cancelLayerRename() {
		if (layerNameBox != null) {
			removeWidget(layerNameBox);
			layerNameBox = null;
		}
		editingLayer = -1;
	}

	/**
	 * Copies the selection, keeping which voice each note was in.
	 *
	 * <p>Gathered layer by layer rather than by flattening every note first, because the instrument
	 * belongs to the layer and not the note -- flattening is where it used to get lost.</p>
	 */
	private void copySelection() {
		record Copied(Layer layer, NoteEvent note) {
		}
		List<Copied> selected = new ArrayList<>();
		for (Layer layer : project().layers()) {
			for (NoteEvent note : layer.notes()) {
				if (selectedNotes.contains(note.id())) {
					selected.add(new Copied(layer, note));
				}
			}
		}
		if (selected.isEmpty()) {
			return;
		}
		selected.sort(Comparator.comparingLong((Copied copied) -> copied.note().startTick())
			.thenComparingInt(copied -> copied.note().midiNote()));
		long firstNote = selected.stream().mapToLong(copied -> copied.note().startTick()).min()
			.orElse(0L);
		// The range is the length, when there is one. Its start is the origin as well as its end,
		// which is what carries the silence at the *front* of a phrase -- a pickup, or a riff that
		// begins off the downbeat, keeps its distance from the beat it was written against.
		//
		// Except for a straggler. A note is taken by the box if its trigger touches it, so one can
		// start a pixel before the range does, and an offset the clipboard would clamp to zero is a
		// note quietly moved. The origin gives way to it; the end does not, so the length grows by
		// however far it reached back rather than the copy overlapping itself.
		clipboardOriginTick = hasRange() ? Math.min(rangeStart, firstNote) : firstNote;
		clipboardSpanTicks = hasRange()
			? rangeEnd - clipboardOriginTick
			: spanOfStarts(selected.stream().map(copied -> copied.note().startTick())
				.distinct().sorted().toList());
		List<Long> starts = selected.stream().map(copied -> copied.note().startTick())
			.distinct().sorted().toList();
		long cursor = playbackReturnTick;
		clipboardCursorOffset = blockLeadIn(cursor, firstNote);
		clipboardStepTicks = blockStep(cursor, firstNote, starts.getLast(), stepOfStarts(starts));
		clipboard = selected.stream()
			.map(copied -> new ClipboardNote(copied.note().startTick() - clipboardOriginTick,
				copied.note().midiNote(), copied.note().durationTicks(), copied.note().velocity(),
				copied.layer().instrument(), copied.layer().name()))
			.toList();
	}

	/**
	 * Takes the selected layers, whole, so they can be put back somewhere else.
	 *
	 * <p>Held as layers rather than as notes: a layer is a name, an instrument and a state as much
	 * as it is the music on it, and copying one that arrived back as bare notes on whatever layer
	 * happened to be active would not be a copy of it.</p>
	 */
	private void copyLayers() {
		List<Layer> taken = selectedLayers.stream()
			.filter(index -> index >= 0 && index < project().layers().size())
			.sorted()
			.map(index -> project().layers().get(index))
			.toList();
		if (taken.isEmpty()) {
			return;
		}
		layerClipboard = taken;
		showResult(Component.literal("Copied " + layerCountLabel(taken.size()) + "."));
	}

	/**
	 * Puts the copied layers back, directly below the lowest selected row.
	 *
	 * <p>Below rather than above, and below the <em>lowest</em> rather than the first, so pasting
	 * next to a block of layers you have selected lands after all of them instead of splitting
	 * them. With nothing selected they go on the end, which is where a layer with no stated
	 * position belongs.</p>
	 */
	private void pasteLayers() {
		if (layerClipboard.isEmpty()) {
			return;
		}
		int at = selectedLayers.isEmpty()
			? project().layers().size()
			: selectedLayers.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
		ComposerProject pasted = project().withLayersInserted(at, layerClipboard);
		if (pasted.equals(project())) {
			showResult(Component.literal("No room for " + layerCountLabel(layerClipboard.size())
				+ " - " + ComposerProject.MAX_LAYERS + " is the limit."));
			return;
		}
		apply("paste " + layerCountLabel(layerClipboard.size()), pasted);
		selectedLayers.clear();
		for (int offset = 0; offset < layerClipboard.size(); offset++) {
			selectedLayers.add(at + offset);
		}
		layersChanged();
		rebuildMoveLayerButtons();
		showResult(Component.literal("Pasted " + layerCountLabel(layerClipboard.size())
			+ " below row " + at + "."));
	}

	private void deleteSelectedNotes() {
		if (!selectedNotes.isEmpty()) {
			apply("delete " + selectedNotes.size() + (selectedNotes.size() == 1 ? " note" : " notes"),
				project().deleteNotes(selectedNotes));
			selectedNotes.clear();
		}
	}

	/**
	 * Pastes against the time marker, or where it was copied from.
	 *
	 * <p>It used to land at the mouse, which put the paste wherever the hand happened to be resting
	 * -- Ctrl+V is a keyboard action and the keyboard has no idea where that is. The marker is the
	 * one position the composer already treats as "here": it is drawn, it is draggable, playback
	 * starts from it, and it does not move when you reach for a key.</p>
	 *
	 * <p>Against it rather than at it, and always in front of it. The copy remembered where the
	 * marker stood over the notes, and the two of them make a block: the phrase plus whichever
	 * silence lies between it and the marker. That block goes down starting at the marker, so a
	 * run-up before the phrase keeps its distance and a tail of rest after it comes along behind.
	 * Nothing else carries that silence, because a set of notes begins on its first note and ends
	 * on its last.</p>
	 *
	 * <p>In place is the one paste that has no aim to take: doubling a part onto another instrument
	 * or moving it between layers means landing on the same beat it left, and finding that beat by
	 * hand at the zoom the whole song fits in is not something a cursor can do.</p>
	 */
	private void pasteClipboard(boolean inPlace) {
		if (clipboard.isEmpty()) {
			return;
		}
		// Exact, and deliberately not snapped. The offsets are the whole substance of the copy, so
		// rounding the place they are measured from would move every note in the phrase by up to
		// half a grid step -- and a passage that is on the grid is on it because the cursor was,
		// which is a thing you can see and line up before pressing the key.
		long startTick = inPlace
			? clipboardOriginTick
			: SELECTION_RANGE
				? snapTick(playbackReturnTick)
				: Math.max(0L, playbackReturnTick + clipboardCursorOffset);
		int before = project().layers().size();
		PasteResult result = project().pasteNotes(project().activeLayerIndex(), clipboard, startTick);
		ComposerProject pasted = result.project();
		// The marker steps to the far end of the block, so a second Ctrl+V lays the phrase down after
		// the first rather than on top of it and a passage is built by holding the key. Measured
		// from where the marker is rather than from where the notes went, since it is the marker the
		// block was placed against. Not for paste-in-place, whose point is landing on the beat the
		// copy left.
		long cursor = inPlace ? -1L
			: SELECTION_RANGE
				? startTick + clipboardSpan()
				: playbackReturnTick + clipboardStepTicks;
		if (cursor >= 0L) {
			// The end marker comes with it. It is floored at the last note, so pasting at the end of
			// a song leaves it exactly on the note just laid -- and the next paste would then be
			// clamped back onto that note instead of landing after it.
			pasted = pasted.withEndTick(Math.max(pasted.endTick(), cursor));
		}
		apply("paste " + result.noteIds().size() + (result.noteIds().size() == 1 ? " note" : " notes"),
			pasted, cursor >= 0L ? playbackReturnTick : ComposerHistory.NO_CURSOR);
		if (cursor >= 0L && playing) {
			// Mid-playback the marker on screen is the playhead, not the tick a paste lands on, and
			// throwing the running position across the song is not what Ctrl+V asked for. Only the
			// return tick -- which is where the paste actually went -- steps on.
			playbackReturnTick = Math.max(0L, Math.min(project().endTick(), cursor));
		} else if (cursor >= 0L) {
			movePlayheadTo(cursor);
			revealTick(playbackReturnTick);
		}
		selectedNotes.clear();
		selectedNotes.addAll(result.noteIds());
		if (SELECTION_RANGE) {
			// The range moves onto what was just pasted, because the selection did. Leaving it behind
			// on the passage the copy was taken from would have the band describing one stretch of
			// the song and the selection sitting in another.
			rangeStart = startTick;
			rangeEnd = startTick + clipboardSpan();
		}
		layersChanged();
		rebuildMoveLayerButtons();
		// Said out loud only when the paste had to change the shape of the composition. A paste that
		// lands where you pointed it needs no announcement; one that made three layers does.
		if (result.addedLayers() > 0) {
			long instruments = clipboard.stream().map(ClipboardNote::instrument).distinct().count();
			showResult(Component.literal(result.noteIds().size() + " notes pasted. The copy spans "
				+ instruments + " instruments and a layer holds one, so "
				+ (project().layers().size() - before)
				+ (result.addedLayers() == 1 ? " layer was" : " layers were")
				+ " added to keep them apart."));
		}
	}

	/**
	 * How far in front of the cursor the block starts, which is nothing unless the cursor is inside
	 * it.
	 *
	 * <p>The block always goes down in front of the cursor. Standing before the phrase, the run-up
	 * is part of the block, so the first note keeps its distance and lands that far on. Standing
	 * anywhere else -- inside the phrase or past the end of it -- the first note is the front of the
	 * block and lands on the cursor itself.</p>
	 */
	private static long blockLeadIn(long cursor, long first) {
		return Math.max(0L, first - cursor);
	}

	/**
	 * How far the cursor moves after laying a block down: the length of the block.
	 *
	 * <p>The cursor and the notes make a block with a bound at each end -- the nearer of the cursor
	 * and the first note, and the further of the cursor and the last note. Whichever end the cursor
	 * is at, its silence is inside the block and travels with it, and the cursor comes to rest on
	 * the far bound ready for the next one.</p>
	 *
	 * <p>The one subtlety is what a bound made of a note is worth. Two positions {@code n} apart are
	 * a run of {@code n} only if the far end is empty; a note standing on it occupies a slot of its
	 * own, and the block is a slot longer than the distance across it. Miss that and every repeat
	 * starts one slot early: a bar of rest-note-note-note tiled at three beats instead of four,
	 * which comes out as an unbroken run of notes with the rest quietly eaten. So a block that ends
	 * on a note is a step longer, and the step is the passage's own -- the tightest gap between two
	 * of its starts, which is the resolution the material is written in.</p>
	 *
	 * <p>It also means the cursor never comes to rest exactly on a note, which is what stops the
	 * next paste laying its first note on top of the last one.</p>
	 */
	private static long blockStep(long cursor, long first, long last, long unit) {
		long lo = Math.min(cursor, first);
		long hi = Math.max(cursor, last);
		return Math.max(1L, hi - lo + (hi == last ? unit : 0L));
	}

	/** A passage's own resolution: the tightest gap between two of its starts, or a grid line. */
	private long stepOfStarts(List<Long> starts) {
		long step = Long.MAX_VALUE;
		for (int index = 1; index < starts.size(); index++) {
			step = Math.min(step, starts.get(index) - starts.get(index - 1));
		}
		return step == Long.MAX_VALUE || step <= 0L ? Math.max(1L, gridTicks()) : step;
	}

	/**
	 * How far a paste steps: the length the copy was made with.
	 *
	 * <p>Captured at copy time rather than worked out here, so it cannot change under a clipboard
	 * that has not. See {@link #copySelection}.</p>
	 */
	private long clipboardSpan() {
		return Math.max(1L, clipboardSpanTicks);
	}

	/**
	 * A guess at the length of some notes, for when there is no range saying.
	 *
	 * <p>A set of notes ends on its last note's start, and where the passage stops is one step
	 * further on. Which step is read off the notes rather than off the Snap control: the tightest
	 * gap between two of their own starts is the resolution the material is written in, so a phrase
	 * of even steps comes out exactly its own length and sixteen sixteenths make a bar. Snap only
	 * stands in when there is nothing to measure -- a single chord, one start tick, no gap at all.
	 * It is a guess either way, which is the whole reason the range exists and is drawn.</p>
	 */
	private long spanOfStarts(List<Long> starts) {
		long step = stepOfStarts(starts);
		return starts.isEmpty() ? step : starts.getLast() - starts.getFirst() + step;
	}

	/**
	 * How far {@link #duplicateSelection} steps: the range, or the selection's own guessed length.
	 */
	private long selectionSpan() {
		if (hasRange()) {
			return rangeLength();
		}
		List<Long> starts = project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selectedNotes.contains(note.id()))
			.map(NoteEvent::startTick)
			.distinct()
			.sorted()
			.toList();
		if (SELECTION_RANGE || starts.isEmpty()) {
			return spanOfStarts(starts);
		}
		// The same rule a paste steps by, so the two keys agree about what a passage is worth. Ctrl+D
		// is a copy and a paste with the cursor left where it is, and it would be strange for it to
		// land somewhere Ctrl+C and Ctrl+V would not.
		return blockStep(playbackReturnTick, starts.getFirst(), starts.getLast(),
			stepOfStarts(starts));
	}

	/**
	 * Lays the selection down again directly after itself, and leaves the selection on the copy.
	 *
	 * <p>The gesture for extending a passage, and the reason it is not Ctrl+V: no clipboard is
	 * involved, so it neither reads nor overwrites what was copied, and pressing it again adds one
	 * more repeat rather than the same repeat twice. Every note stays on the layer it was already
	 * on, which is the other difference -- a paste gathers a copy onto the layer you aimed it at,
	 * and a duplicate is not aimed anywhere.</p>
	 */
	private void duplicateSelection() {
		if (selectedNotes.isEmpty()) {
			showResult(Component.literal(SELECTION_RANGE
				? "Nothing selected to duplicate. Drag a box over a passage first - the box also "
					+ "sets how far each repeat steps."
				: "Nothing selected to duplicate. Select a passage first - how far each repeat "
					+ "steps is read from where the time marker stands over it."));
			return;
		}
		long span = selectionSpan();
		PasteResult result = project().duplicateNotes(selectedNotes, span);
		if (result.noteIds().isEmpty()) {
			return;
		}
		long furthest = project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selectedNotes.contains(note.id()))
			.mapToLong(NoteEvent::startTick)
			.max()
			.orElse(0L) + span;
		// The end marker has to clear the cursor as well as the notes. Standing past the passage,
		// the cursor steps further than the furthest note does -- and the marker is a ceiling on
		// where the cursor may be, so a song that ended on its last note would catch it and every
		// press after that would step from the same place.
		long stepped = playbackReturnTick + span;
		ComposerProject duplicated = result.project().withEndTick(
			Math.max(result.project().endTick(), Math.max(furthest, hasRange() ? 0L : stepped)));
		apply("duplicate " + result.noteIds().size()
			+ (result.noteIds().size() == 1 ? " note" : " notes"), duplicated,
			hasRange() ? ComposerHistory.NO_CURSOR : playbackReturnTick);
		selectedNotes.clear();
		selectedNotes.addAll(result.noteIds());
		if (hasRange()) {
			// The range travels with the selection it describes, so a second press continues the
			// passage instead of laying a second copy on the first.
			rangeStart += span;
			rangeEnd += span;
			revealTick(rangeEnd);
		} else if (playing) {
			// Mid-playback the marker on screen is the playhead, and throwing the running position
			// across the song is not what Ctrl+D asked for. Only the return tick steps on.
			playbackReturnTick = Math.max(0L, Math.min(project().endTick(), stepped));
			revealTick(furthest);
		} else {
			// The cursor travels for the same reason the range does and by the same amount: the step
			// is measured from where it stands over the notes, so leaving it behind while the notes
			// moved would make every press step further than the last.
			movePlayheadTo(stepped);
			revealTick(furthest);
		}
		layersChanged();
		showResult(Component.literal(result.noteIds().size() + " notes duplicated "
			+ rangeLabel(span) + " on. Ctrl+D again adds another."));
	}

	/**
	 * What one grid step is worth in repeater ticks moves with the tempo and with the speed slider,
	 * so the label has to be rebuilt on every edit rather than only when the snap changes. It used
	 * to be set in two places -- once where the screen is built and once where the setting moves --
	 * which is exactly the pair that misses a speed change, and a stale number is worse than none.
	 */
	/**
	 * The tempo the song is written at, the speed it is being played at, and what that comes to.
	 *
	 * <p>Two numbers doing one job, and only one of them was anywhere on screen -- the picker shows
	 * a song's BPM and the composer then hides it, while the multiplier beside it is saved in the
	 * file and goes into the build. Seven songs in a library of thirty-eight are playing at a tempo
	 * that is not the tempo written in them. Shown as the sum rather than the answer, because the
	 * baseline is worth keeping: it is what the slider is a ratio of.</p>
	 */
	private String tempoLabel() {
		double base = 60_000_000.0 / Math.max(1, project().tempoMicrosPerQuarter());
		double factor = Math.max(1, project().speedQuarters()) / 4.0;
		String played = trimZeros(String.format(java.util.Locale.ROOT, "%.1f", base * factor));
		if (Math.abs(factor - 1.0) < 1.0e-9) {
			return played + " BPM";
		}
		return trimZeros(String.format(java.util.Locale.ROOT, "%.1f", base)) + " x "
			+ trimZeros(String.format(java.util.Locale.ROOT, "%.2f", factor))
			+ " = " + played + " BPM";
	}

	private void refreshSpeedTooltip() {
		if (delayScaleSlider == null) {
			return;
		}
		delayScaleSlider.setTooltip(Tooltip.create(Component.literal(
			"Playback speed, 0.25x to 8.00x. Higher is faster."
				+ "\n" + tempoLabel() + "."
				+ "\nThe speed is part of the song: it is saved with it and the build runs at it. "
				+ "Edit > Apply speed to the tempo folds it in and puts the slider back to 1.00x.")));
	}

	private void refreshSnapButton() {
		if (snapButton == null) {
			return;
		}
		// Amber rather than a longer caption. The toolbar's controls are sized to their widest
		// possible label and pinned to the right edge, so spelling the number out on the button
		// would push the whole cluster leftward on every song forever. The colour is the signal, the
		// status bar carries the number, and the tooltip explains it.
		snapButton.setMessage(snapOnRedstoneGrid()
			? snapLabel()
			: snapLabel().copy().withStyle(net.minecraft.ChatFormatting.GOLD));
		snapButton.setTooltip(snapTooltip());
	}

	private void updateButtonStates() {
		refreshSnapButton();
		refreshSpeedTooltip();
		if (recordButton != null) {
			recordButton.setMessage(recordLabel());
		}
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

	/**
	 * The note under the cursor, looked for only in the layers the roll will let you touch.
	 *
	 * <p>Notes outside the selection are background. They are drawn, dimly, so you can see what you
	 * are writing against, and they are deliberately not clickable: a click that lands on one is a
	 * click on empty roll, which is what makes a note placeable on top of another. Selecting that
	 * layer -- or selecting none -- brings them back within reach.</p>
	 */
	private NoteHit noteAt(double mouseX, double mouseY) {
		List<Integer> reachable = selectionLayers();
		List<Integer> order = noteDrawOrder(project());
		for (int position = order.size() - 1; position >= 0; position--) {
			int layerIndex = order.get(position);
			if (!reachable.contains(layerIndex)) {
				continue;
			}
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
		int left = (int)Math.min(boxOriginX(), selectionEndX);
		int right = (int)Math.max(boxOriginX(), selectionEndX);
		int top = (int)Math.min(boxOriginY(), selectionEndY);
		int bottom = (int)Math.max(boxOriginY(), selectionEndY);
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
		return new NoteRect(left, top, left + noteWidth(), top + rowHeight - 2);
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

	/**
	 * Scrolls the roll only as far as it takes to put a tick back on screen.
	 *
	 * <p>Only when it is off, and only to the near edge with a margin: a view that recentres itself
	 * every time is a view that moves when you did not ask it to. What this is for is the marker
	 * walking off the right-hand side while a phrase is pasted over and over.</p>
	 */
	private void revealTick(long tick) {
		long margin = Math.round(rollWidth * ticksPerPixel / 8.0);
		long span = Math.round(rollWidth * ticksPerPixel);
		if (tick < horizontalScroll) {
			horizontalScroll = Math.max(0L, tick - margin);
		} else if (tick > horizontalScroll + span) {
			horizontalScroll = Math.max(0L, tick - span + margin);
		}
	}

	private boolean hasRange() {
		return SELECTION_RANGE && rangeStart >= 0L && rangeEnd > rangeStart;
	}

	private long rangeLength() {
		return hasRange() ? rangeEnd - rangeStart : 0L;
	}

	/**
	 * How far the range is standing from where it is stored, which is only ever a drag in progress.
	 *
	 * <p>A drag does not touch the composition until the button comes up -- it draws from a preview
	 * -- so the range cannot be moved as it goes without the two disagreeing about which one is
	 * real. It is offset for drawing instead, and moved for good when the drag commits.</p>
	 */
	private long rangeDragDelta() {
		if (!draggingNotes) {
			return 0L;
		}
		// Clamped the way the move itself is clamped. A drag pushed past the start of the song moves
		// the notes only as far as tick zero, so a band offset by the raw distance would slide out
		// from under them and off the left-hand edge.
		long earliest = earliestSelectedTick();
		return earliest < 0L ? dragTickDelta : Math.max(dragTickDelta, -earliest);
	}

	/**
	 * Drops a range whose selection has gone.
	 *
	 * <p>The range is a fact about a passage that is selected. Cut it, delete it, or put it down and
	 * the range has nothing left to be about -- and it was drawn anyway, falling back to the full
	 * height of the roll for want of any notes to measure, which is a pillar down the middle of the
	 * window until the next click.</p>
	 *
	 * <p>Reconciled here rather than at each of the sixteen places that empty a selection, because
	 * sixteen places is sixteen chances to add a seventeenth and not notice.</p>
	 */
	private void syncRangeToSelection() {
		if (rangeStart >= 0L && selectedNotes.isEmpty()) {
			clearRange();
		}
	}

	private void clearRange() {
		rangeStart = -1L;
		rangeEnd = -1L;
		draggingRangeHandle = 0;
	}

	/**
	 * Takes the range from the box that has just been drawn, widened to the nearest grid lines.
	 *
	 * <p>Outward rather than to the nearest, so the range always contains every note the box took --
	 * a range that ended before a selected note would be a length that cannot hold its own copy. It
	 * also means a box drawn roughly around sixteen sixteenths comes out exactly one bar, which is
	 * the answer nearly every time and visible when it is not.</p>
	 */
	private void setRangeFromBox(double endX) {
		if (!SELECTION_RANGE) {
			return;
		}
		// Grid lines, not multiples of a rounded width. Rounding the width first and stepping it out
		// is the drift that took the drawn grid off the real one: a repeater tick at 128 BPM is
		// 102.4 composer ticks, so a range measured in hundred-and-twos lands further and further
		// from the lines it is supposed to be sitting on, and a box drawn a minute into the song
		// bound itself to positions between them. The lines are where they are; ask for one.
		double span = gridSpan();
		long from = Math.max(0L, Math.round(Math.min(boxOriginTick, mouseTick(endX))));
		long to = Math.max(0L, Math.round(Math.max(boxOriginTick, mouseTick(endX))));
		long startIndex = gridIndexInside(from, span);
		long endIndex = (long)Math.ceil(Math.max(0L, to) / span - 1.0e-9);
		rangeStart = gridLineAt(startIndex, span);
		rangeEnd = gridLineAt(Math.max(endIndex, startIndex + 1L), span);
		draggingRangeHandle = 0;
	}

	/**
	 * The thin strip along the bottom of the ruler the range's handles live in.
	 *
	 * <p>Its own lane rather than sharing the ruler's full height with the playback marker and the
	 * end marker. Four draggable things in twenty-four pixels is a puzzle about which one a press
	 * meant; the top of the ruler stays scrubbing and the bottom five pixels are the range.</p>
	 */
	/** How far either side of a band edge counts as having hold of it. */
	private static final double RANGE_HANDLE_REACH = 4.0;

	/** Which edge of the band a point has hold of, or 0. */
	private int rangeHandleAt(double x, double y) {
		if (!hasRange() || x < rollX || x >= rollX + rollWidth) {
			return 0;
		}
		if (y < rangeBandTop() || y >= rangeBandBottom()) {
			return 0;
		}
		// Reach outward only. A range starts on a note, so the left edge sits on that note's own
		// left edge -- a zone spreading both ways would take the click that was meant to pick the
		// first note of the phrase and drag the range with it instead. Outside the band there is
		// nothing else to hit.
		double from = tickX(rangeStart + rangeDragDelta());
		double to = tickX(rangeEnd + rangeDragDelta());
		boolean onStart = x >= from - RANGE_HANDLE_REACH && x <= from + 1.0;
		boolean onEnd = x >= to - 1.0 && x <= to + RANGE_HANDLE_REACH;
		if (onEnd && (!onStart || Math.abs(x - to) <= Math.abs(x - from))) {
			return 2;
		}
		return onStart ? 1 : 0;
	}

	/** A tick count as the unit anyone actually thinks a loop in. */
	private String rangeLabel(long ticks) {
		double bars = ticks / (project().ppq() * 4.0);
		if (bars >= 0.995 && Math.abs(bars - Math.round(bars)) < 0.005) {
			long whole = Math.round(bars);
			return whole + (whole == 1L ? " bar" : " bars");
		}
		if (bars >= 0.1) {
			return String.format(java.util.Locale.ROOT, "%.2f bars", bars);
		}
		return ticks + " ticks";
	}

	/** The box's anchor corner, put back on the screen wherever the view has moved it to. */
	private double boxOriginX() {
		return rollX + (boxOriginTick - horizontalScroll) / ticksPerPixel;
	}

	private double boxOriginY() {
		return rollY + (topMidiNote - boxOriginMidi) * rowHeight;
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
	/**
	 * The grid's spacing in composer ticks, unrounded.
	 *
	 * <p>Two families of grid and they are absolute about different things. The note values are
	 * absolute in the <em>song</em>: a 1/16 is a sixteenth of a quarter note whatever the tempo does,
	 * and it is a whole number of composer ticks because that is what PPQ is for. The two redstone
	 * grids are absolute in <em>real time</em>: a repeater tick is 100 ms, and how many composer ticks
	 * that covers depends on the tempo and the speed, so it is very often not a whole number at all.
	 * At 128 BPM and 480 PPQ it is 102.4.</p>
	 *
	 * <p>Which is why this is a double and {@link #gridLineAt} rounds last. Rounding here and
	 * multiplying out -- which is what the grid used to do -- puts every line at a multiple of 102 and
	 * lets the error accumulate: three minutes in, the line claiming to be a repeater tick is seven
	 * repeater ticks away from one. Rounding each line from its own index instead holds every one of
	 * them within half a composer tick of the truth forever, which is under a millisecond.</p>
	 */
	private double gridSpan() {
		return gridSpan(snapSubdivision);
	}

	/** The same, asked of a setting the snap is not on -- which is what a menu of them needs. */
	private double gridSpan(int subdivision) {
		if (subdivision == SNAP_REPEATER) {
			return Math.max(1.0, SongAnalysis.redstoneTickSpan(project()));
		}
		if (subdivision == SNAP_GAME_TICK) {
			return Math.max(1.0, SongAnalysis.redstoneTickSpan(project()) / 2.0);
		}
		return subdivision == 0 ? 1.0 : Math.max(1.0, project().ppq() / (double)subdivision);
	}

	/** Whether the snap is one of the two that measure in real time rather than in note values. */
	private boolean redstoneSnap() {
		return snapSubdivision == SNAP_REPEATER || snapSubdivision == SNAP_GAME_TICK;
	}

	/**
	 * The grid actually on screen, which is the snap doubled until its lines are far enough apart
	 * to be lines.
	 *
	 * <p>One answer shared by the drawing and by everything sized against it. A note drawn wider
	 * than the cell it sits in is the drawing and the sizing disagreeing, and there is no version
	 * of that which is not a bug.</p>
	 */
	private double drawnGridSpan() {
		return readableSpan(snapSubdivision == 0
			? Math.max(1.0, project().ppq() / 4.0)
			: gridSpan(), redstoneSnap() ? REDSTONE_GRID_PIXEL_SPACING : MIN_GRID_PIXEL_SPACING);
	}

	/**
	 * How wide a note draws: a fixed trigger, unless the grid is finer than that.
	 *
	 * <p>A note is a moment, not a duration -- the block fires and the sound plays out on its own --
	 * so it is drawn as a fixed little block rather than as a length. Seven pixels reads well at
	 * every ordinary zoom and is a lie at a tight one: on a 1/32 grid zoomed out, one note covered
	 * its own cell and most of the next two, so the grid said the notes were a thirty-second apart
	 * and the notes said they were touching.</p>
	 */
	private int noteWidth() {
		if (snapSubdivision == 0) {
			return NOTE_TRIGGER_WIDTH;
		}
		// Never below two. A one-pixel note is a mark you cannot see, click or drag, and a grid
		// fine enough to demand that is one you are about to zoom into anyway.
		return Math.max(2, Math.min(NOTE_TRIGGER_WIDTH,
			(int)Math.floor(drawnGridSpan() / ticksPerPixel)));
	}

	/**
	 * A grid colour faded by how crowded its lines are.
	 *
	 * <p>A grid doubles its step when its lines would fall closer together than the floor, so the
	 * spacing runs from twice the floor down to the floor and then jumps back. Drawn at one
	 * strength the whole way, that is a grid which thickens into a wall as you zoom out and then
	 * pops back to open -- and the wall is the part you are looking at while you decide the zoom
	 * is wrong.</p>
	 *
	 * <p>Fading across that run turns the jump into a crossfade: the lines thin out as they crowd,
	 * and the coarser grid that replaces them arrives at full strength with room around it. Held
	 * off zero at the bottom, because a grid that disappears entirely for one notch of the wheel
	 * reads as broken rather than as faint.</p>
	 *
	 * <p>The ramp runs to four times the floor rather than to twice it. Over the shorter run a
	 * grid spent most of its zoom range at full strength and did all its fading in the last notch
	 * before the jump, which is not a crossfade -- it is the same wall with a softer edge. Lines
	 * only look open when there is several times their own width between them.</p>
	 */
	private static int crowdedGridColor(int color, double pixels, int floorPixels) {
		return crowdedGridColor(color, pixels, floorPixels, 0.25);
	}

	/**
	 * The same, for a grid that has to stay legible at its most crowded rather than get out of the
	 * way.
	 *
	 * @param faintest the share of its own weight the grid keeps once its lines are as close
	 *     together as they are allowed to get
	 */
	private static int crowdedGridColor(int color, double pixels, int floorPixels, double faintest) {
		double run = 3.0 * floorPixels;
		double room = Math.max(faintest, Math.min(1.0, (pixels - floorPixels) / run));
		int alpha = (int)Math.round((color >>> 24) * room);
		return alpha << 24 | color & 0xFFFFFF;
	}

	/** Where the nth line of a grid falls, rounded once so the error cannot accumulate. */
	static long gridLineAt(long index, double span) {
		return Math.max(0L, Math.round(index * span));
	}

	/** Which line of the grid a tick is nearest. */
	static long gridIndexNear(long tick, double span) {
		return Math.max(0L, Math.round(tick / span));
	}

	/** Which cell of the grid a tick is inside, which is the line at or before it. */
	static long gridIndexInside(long tick, double span) {
		return Math.max(0L, (long)Math.floor(Math.max(0L, tick) / span));
	}

	/**
	 * The rounded spacing, for the few places that want a length rather than a position.
	 *
	 * <p>Anything that lands a note belongs on {@link #gridLineAt}. This is for measuring: how far a
	 * paste steps when there is nothing else to say, how wide to round a selection range.</p>
	 */
	private long gridTicks() {
		return Math.max(1L, Math.round(gridSpan()));
	}

	/** The nearest grid line to a tick, for the things that are pointing at a line. */
	private long snapTick(long tick) {
		double span = gridSpan();
		return gridLineAt(gridIndexNear(tick, span), span);
	}

	static long nearestGridLine(long tick, long grid) {
		return Math.max(0L, Math.round(tick / (double)grid) * grid);
	}

	static long gridCellStart(long tick, long grid) {
		return Math.floorDiv(Math.max(0L, tick), grid) * grid;
	}

	/**
	 * The start of the grid cell a tick falls inside, for the things that are pointing at a cell.
	 *
	 * <p>Drawing a note is one of those, and it used to take the nearest line instead -- so a click
	 * on the right-hand half of a cell put the note in the cell after it, which reads as the editor
	 * being imprecise rather than as a rule. The other axis has always worked this way: mouseMidi
	 * floors, so a click anywhere in a row gives that row. This is the same sentence about time.</p>
	 *
	 * <p>The playhead and the end marker keep {@link #snapTick}. Those really are lines -- a marker
	 * sits between two cells rather than in one -- and nearest is what pointing at a line means.</p>
	 */
	private long snapTickInto(long tick) {
		double span = gridSpan();
		return gridLineAt(gridIndexInside(tick, span), span);
	}

	/**
	 * How far the arrow keys move the selection: to the next grid line, not by the grid's width.
	 *
	 * <p>Those are the same thing only when a grid line is a whole number of composer ticks apart
	 * from the next, which the redstone grids very often are not. Adding a fixed width repeatedly
	 * walks the notes off the grid the same way multiplying a rounded step out walks the lines off
	 * it -- press right eight times at 128 BPM and the selection is three composer ticks adrift of
	 * where the lines are drawn.</p>
	 *
	 * <p>Measured from the earliest selected note and applied to all of them, so the passage keeps
	 * its own shape and its leading edge is what lands on the line.</p>
	 */
	private long nudgeToNextLine(int direction) {
		double span = gridSpan();
		long anchor = Math.max(0L, earliestSelectedTick());
		long here = gridIndexNear(anchor, span);
		// A note sitting between lines steps onto the nearer one rather than past it, which is what
		// makes the key a way back onto the grid as well as a way along it.
		if (gridLineAt(here, span) != anchor) {
			long onto = direction > 0
				? (gridLineAt(here, span) > anchor ? here : here + 1)
				: (gridLineAt(here, span) < anchor ? here : here - 1);
			return Math.abs(gridLineAt(Math.max(0L, onto), span) - anchor);
		}
		return Math.abs(gridLineAt(Math.max(0L, here + direction), span) - anchor);
	}

	/**
	 * How far a drag actually moves the selection: onto the grid, not by the grid.
	 *
	 * <p>It used to round the distance travelled, which moves a passage by whole grid steps and
	 * therefore never changes where it sits between them. A note that started off the grid stayed
	 * off it at exactly the same offset, no matter how far it was dragged -- so the one gesture
	 * anyone would reach for to fix an off-grid note was the one gesture that could not, and the
	 * notes appeared to jump straight over the lines they were being aimed at.</p>
	 *
	 * <p>The earliest selected note is the one that lands. Everything else moves by the same amount,
	 * so the passage keeps its own shape and its leading edge is what meets the line -- the same
	 * rule the arrow keys use, see {@link #nudgeToNextLine}.</p>
	 */
	private long snapDelta(long tickDelta) {
		double span = gridSpan();
		long anchor = earliestSelectedTick();
		if (anchor < 0L) {
			return Math.round(Math.round(tickDelta / span) * span);
		}
		long landed = Math.max(0L, anchor + tickDelta);
		return gridLineAt(gridIndexNear(landed, span), span) - anchor;
	}

	/** The first tick anything selected stands on, or -1 with nothing selected. */
	private long earliestSelectedTick() {
		if (selectedNotes.isEmpty()) {
			return -1L;
		}
		return project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selectedNotes.contains(note.id()))
			.mapToLong(NoteEvent::startTick)
			.min()
			.orElse(-1L);
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
		return tickX(project().endTick()) + noteWidth();
	}

	/** The tick a cursor at {@code x} is pointing the end marker at, undoing the drawing offset. */
	private long endMarkerTick(double x) {
		return mouseTick(x - noteWidth());
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

	/**
	 * Which way a zoom key points, or nought for a key that is not one.
	 *
	 * <p>The number row and the keypad both, since a keypad's minus is the one within reach of the
	 * hand that is not on the mouse. Plus is read off the unshifted key: on most layouts the plus is
	 * the shift of equals, and asking for Ctrl+Shift+Equals to zoom in would be asking for a
	 * three-finger chord to do what every other application does with two.</p>
	 */
	private static int zoomKeyDirection(int key) {
		return switch (key) {
			case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> 1;
			case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> -1;
			default -> 0;
		};
	}

	private boolean altDown() {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
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

	/**
	 * One scheduled preview sound, and the colour of the layer it came from.
	 *
	 * <p>The colour is resolved here, while the layer is still in hand, rather than looked up when
	 * the event fires -- by then all that is left is a pitch. It takes no part in {@code sameSound},
	 * which asks whether the build would collapse the two, and the build has no colours.</p>
	 */
	private record PlaybackEvent(long tick, PreviewInstrument instrument, int note, int color) {
		private boolean sameSound(PlaybackEvent other) {
			return tick == other.tick && note == other.note && instrument.equals(other.instrument);
		}
	}

	private record NoteHit(int layerIndex, NoteEvent note) {
	}

	/** What a drag down the layer panel is setting on every row it crosses. */
	private enum LayerPaint {
		NONE,
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
	private record LayerRowLayout(int inset, int stripe, boolean chip, boolean name, boolean count,
			int stateX, int instrumentX, int nameLeft, int nameRight, int ordinalLeft,
			int ordinalRight) {
	}

	/** One row of an open menu: a thing to do, a setting to cycle, or a submenu to open. */
	/** The two halves of the screen that own a selection and can hold the keyboard. */
	private enum Pane {
		ROLL,
		LAYERS
	}

	private record MenuRow(ToolbarAction action, ToolbarSubmenu submenu) {
		static MenuRow of(ToolbarAction action) {
			return new MenuRow(action, null);
		}

		static MenuRow of(ToolbarSubmenu submenu) {
			return new MenuRow(null, submenu);
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
				ToolbarAction.QUANTIZE_SIXTEENTH, ToolbarAction.QUANTIZE_REPEATERS,
				ToolbarAction.QUANTIZE_GAME_TICKS
			},
			new String[] {"1/4 note", "1/8 note", "1/16 note", "Repeater ticks", "Game ticks"}, 3),
		END("End", "Where the song stops, which is a delay the build has to place like any other.",
			new ToolbarAction[] {ToolbarAction.SNAP_END, ToolbarAction.TRIM_END},
			new String[] {"Snap to grid", "Trim to last note"}, -1),
		MARKERS("Markers", "Named positions on the timeline. Nothing is built from one and nothing "
				+ "sounds at one -- they are somewhere to write down what a stretch of the song is, so "
				+ "that finding it again is reading a label rather than counting bars.",
			new ToolbarAction[] {
				ToolbarAction.ADD_MARKER, ToolbarAction.RENAME_MARKER, ToolbarAction.CLEAR_MARKERS
			},
			new String[] {"Add / remove (M)", "Rename...", "Remove all"}, 2);

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
		/** The odd one out: a title in the bar that opens a screen instead of hanging a menu. */
		SETTINGS
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
		CONVERT("Convert for Minecraft (redstone ticks, 1 lane)"),
		CONVERT_GAME_TICKS("Convert for Minecraft (game ticks, 2 lanes)"),
		MERGE_REPEATS("Merge repeats", true),
		QUANTIZE_QUARTER("Quantize to 1/4", true),
		QUANTIZE_EIGHTH("Quantize to 1/8", true),
		QUANTIZE_SIXTEENTH("Quantize to 1/16", true),
		QUANTIZE_REPEATERS("Quantize to repeater ticks", true),
		QUANTIZE_GAME_TICKS("Quantize to game ticks", true),
		DUPLICATE_SELECTION("Duplicate selection"),
		FIT_ALL_RANGE("Fit into range", true),
		TRANSPOSE_BEST_FIT("Transpose to best fit"),
		BAKE_SPEED("Apply speed to the tempo"),
		SNAP_TEMPO("Snap tempo (whole song)"),
		SNAP_TEMPO_GAME("Snap tempo to game ticks (whole song)"),
		PASTE_IN_WORLD("Paste current sequence in world (requires op)..."),
		BUILD_CANCEL("Cancel paste"),
		TOGGLE_DEDUPE("Dedupe identical notes"),
		ADD_MARKER("Add or remove at the playback marker"),
		RENAME_MARKER("Rename the marker here..."),
		CLEAR_MARKERS("Remove every marker"),
		SNAP_END("Snap end to grid"),
		TRIM_END("Trim end to last note"),
		SELECT_OFF_GRID("Off grid"),
		SELECT_HALF_TICKED("Half-ticked"),
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
			UNDO, REDO, DUPLICATE_SELECTION, CONVERT, CONVERT_GAME_TICKS, MERGE_REPEATS,
			TRANSPOSE_BEST_FIT,
			FIT_ALL_RANGE, BAKE_SPEED, SNAP_TEMPO, SNAP_TEMPO_GAME
		};
		private static final ToolbarAction[] BUILD_ACTIONS = {
			TOGGLE_DEDUPE, PASTE_IN_WORLD, BUILD_CANCEL
		};
		private static final ToolbarAction[] SELECT_ACTIONS = {
			SELECT_OFF_GRID, SELECT_HALF_TICKED, SELECT_TOO_FREQUENT, SELECT_OUT_OF_RANGE,
			SELECT_OVERLOADED_CHORDS, SELECT_ALL_NOTES, SELECT_NONE
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
		DUPLICATE("Duplicate layer"),
		MOVE_UP("Move up"),
		MOVE_DOWN("Move down"),
		MOVE_NOTES_HERE("Move selected notes here"),
		MERGE_SELECTED("Merge selected"),
		SNAP_TO_START("Snap to song start"),
		SELECT_ALL("Select all layers"),
		// Last, and not next to Merge. The two read alike in a hurry and only one of them can be
		// reached by a slip of the hand from a row you meant to rename.
		DELETE_SELECTED("Delete selected");

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
