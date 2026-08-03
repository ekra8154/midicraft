package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Layout and pacing for a command-driven paste, chosen at the moment of pasting.
 *
 * <p>Both are decisions about this build rather than standing preferences -- which layout suits a
 * song depends on where you are standing and how much room is there, and the safe command rate
 * depends on the server. Asking here also puts the resulting footprint next to the choice that
 * determines it, so the trade is visible while it is being made.</p>
 */
final class BuildOptionsScreen extends Screen {
	private final Screen parent;
	private final String songName;
	private final List<FastNoteblocksConfig.SequenceTrack> sequence;
	private final Consumer<SongBuilder.PasteMode> confirm;
	private SongBuilder.PasteMode mode;
	/**
	 * Whether the layout list is showing.
	 *
	 * <p>A list of every layout was fine while there were four of them. Since layouts are meant to
	 * keep being added rather than replaced -- an old one still suits the song it was made for --
	 * the list has to stop being the thing you look at first, so it folds away into the one line
	 * that says which layout is chosen.</p>
	 */
	private boolean layoutsShowing;
	private int commandsPerTick;
	private int laneWidth;
	private int laneFloors;

	/**
	 * One background thread for forecasts, shared by every instance of this screen.
	 *
	 * <p>Planning a build is not a thing to do while a frame is being drawn -- Guardian at thirty-two
	 * wide is twenty-two thousand blocks of arithmetic -- and it is not a thing to do once per frame
	 * either. So it happens off the render thread, once per change of settings, and the answer is
	 * picked up whenever it arrives.</p>
	 */
	private static final ExecutorService FORECASTER = Executors.newSingleThreadExecutor(job -> {
		Thread thread = new Thread(job, "fast-noteblocks-forecast");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * What the current settings would build, or why they would not.
	 *
	 * <p>{@code null} while a forecast is in flight, which is the state the screen opens in.</p>
	 *
	 * @param error the reason there is no build at all, or {@code null} when there is one
	 */
	private record Forecast(int spanZ, int breachingLanes, int worstBreach, int wrongNotes,
			String error) {
	}

	private volatile Forecast forecast;
	/** Which settings the forecast in flight is for, so a stale answer can be dropped. */
	private final AtomicInteger forecastGeneration = new AtomicInteger();
	/** The settings the last forecast was asked for, so redraws do not ask again. */
	private String forecastKey;

	BuildOptionsScreen(
		Screen parent,
		String songName,
		List<FastNoteblocksConfig.SequenceTrack> sequence,
		SongBuilder.PasteMode initialMode,
		Consumer<SongBuilder.PasteMode> confirm
	) {
		super(Component.literal("Paste sequence in world"));
		this.parent = parent;
		this.songName = songName;
		this.sequence = sequence;
		this.confirm = confirm;
		this.mode = initialMode;
		this.commandsPerTick = FastNoteblocksConfig.get().commandsPerTick();
		this.laneWidth = FastNoteblocksConfig.get().buildLaneWidth();
		this.laneFloors = FastNoteblocksConfig.get().buildLaneFloors();
	}

	/** Rows the layout control takes: the chosen one, plus every option while the list is open. */
	private int layoutRows() {
		return layoutsShowing ? 1 + SongBuilder.PasteMode.values().length : 1;
	}

	/** Top of the width row, which only a lane build has. */
	private int widthRow(int top) {
		return top + 14 + layoutRows() * 22 + 6;
	}

	private int floorRow(int top) {
		return widthRow(top) + 22;
	}

	private int rateRow(int top) {
		return widthRow(top) + (hasLaneControls() ? 48 : 0) + 10;
	}

	/** Whether this layout folds inside a width you choose, and so has a width and a floor count. */
	private boolean hasLaneControls() {
		return mode == SongBuilder.PasteMode.COMPACT_LANE
			|| mode == SongBuilder.PasteMode.ULTRA_COMPACT_LANE;
	}

	@Override
	protected void init() {
		clearWidgets();
		int width = Math.min(300, this.width - 40);
		int left = (this.width - width) / 2;
		int top = Math.max(40, height / 2 - 96);

		int y = top + 14;
		addRenderableWidget(Button.builder(
				Component.literal(mode.label() + (layoutsShowing ? "  ^" : "  v")),
				clicked -> {
					layoutsShowing = !layoutsShowing;
					init();
				})
			.bounds(left, y, width, 20)
			.tooltip(Tooltip.create(Component.literal(describe(mode))))
			.build());
		y += 22;
		if (layoutsShowing) {
			for (SongBuilder.PasteMode option : SongBuilder.PasteMode.values()) {
				Button button = addRenderableWidget(Button.builder(
						Component.literal((option == mode ? "> " : "  ") + option.label()),
						clicked -> {
							mode = option;
							layoutsShowing = false;
							init();
						})
					.bounds(left, y, width, 20)
					.tooltip(Tooltip.create(Component.literal(describe(option))))
					.build());
				button.active = option != mode;
				y += 22;
			}
		}

		if (hasLaneControls()) {
			int widthY = widthRow(top);
			addRenderableWidget(Button.builder(Component.literal("-"), clicked -> changeWidth(-4))
				.bounds(left, widthY, 20, 20).build());
			addRenderableWidget(Button.builder(Component.literal("+"), clicked -> changeWidth(4))
				.bounds(left + width - 20, widthY, 20, 20).build());
			int floorY = floorRow(top);
			addRenderableWidget(Button.builder(Component.literal("-"), clicked -> changeFloors(-1))
				.bounds(left, floorY, 20, 20).build());
			addRenderableWidget(Button.builder(Component.literal("+"), clicked -> changeFloors(1))
				.bounds(left + width - 20, floorY, 20, 20).build());
		}

		y = rateRow(top);
		addRenderableWidget(Button.builder(Component.literal("-"), clicked -> changeRate(-8))
			.bounds(left, y, 20, 20).build());
		addRenderableWidget(Button.builder(Component.literal("+"), clicked -> changeRate(8))
			.bounds(left + width - 20, y, 20, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Paste"), clicked -> {
			FastNoteblocksConfig.get().setCommandsPerTick(commandsPerTick);
			FastNoteblocksConfig.get().setBuildLaneWidth(laneWidth);
			FastNoteblocksConfig.get().setBuildLaneFloors(laneFloors);
			FastNoteblocksConfig.get().setPasteMode(mode.name());
			FastNoteblocksConfig.save();
			confirm.accept(mode);
		}).bounds(left, y + BUTTON_ROW, width / 2 - 3, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, clicked -> onClose())
			.bounds(left + width / 2 + 3, y + BUTTON_ROW, width / 2 - 3, 20).build());
		requestForecast();
	}

	/**
	 * How far below the rate row the buttons sit.
	 *
	 * <p>Twelve lower than it used to be, for the forecast line. It goes above the buttons rather
	 * than below them because it is part of the choice being made, not a footnote to it.</p>
	 */
	private static final int BUTTON_ROW = 46;

	/**
	 * Asks for a forecast of the settings as they stand, unless one was already asked for.
	 *
	 * <p>The rate is deliberately not part of the key: it changes how long the paste takes to run
	 * and nothing at all about what gets built.</p>
	 */
	private void requestForecast() {
		String key = mode.name() + " " + laneWidth + " " + laneFloors;
		if (key.equals(forecastKey)) {
			return;
		}
		forecastKey = key;
		forecast = null;
		int generation = forecastGeneration.incrementAndGet();
		// Read on the render thread. The origin comes off the player, and the limits off the config,
		// and neither is a thing to be touching from another thread.
		BlockPos origin = SongBuilder.pasteOrigin(minecraft);
		SongBuilder.PasteMode planned = mode;
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(
			FastNoteblocksConfig.get().maxBuildFloors(), laneWidth, laneFloors,
			FastNoteblocksConfig.get().ultraLaneStartTop());
		FORECASTER.execute(() -> {
			Forecast result;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					origin, SongBuilder.eventNotes(sequence), planned, limits);
				result = new Forecast(plan.spanZ(), plan.breaches().size(),
					plan.worstBreach(), plan.wrongNotes(), null);
			} catch (IllegalArgumentException refused) {
				result = new Forecast(0, 0, 0, 0, refused.getMessage());
			} catch (RuntimeException broken) {
				// A forecast that throws must not take the paste down with it: the build itself may
				// well be fine, and a screen that cannot tell you the depth is still a screen you
				// can paste from.
				result = new Forecast(0, 0, 0, 0, "could not work out the layout");
			}
			if (forecastGeneration.get() == generation) {
				forecast = result;
			}
		});
	}

	/**
	 * The one line that says what these settings come out as.
	 *
	 * <p>Depth first, because it is the only dimension of the three that is not already a setting on
	 * this screen: the width is chosen above, the height falls out of the floor count, and how deep
	 * it ends up is the builder's answer rather than the player's.</p>
	 */
	private static String forecastLine(Forecast predicted) {
		if (predicted == null) {
			return "measuring the build...";
		}
		if (predicted.error() != null) {
			return predicted.error();
		}
		// Kept short on purpose: the dialog is three hundred pixels wide, and a line that runs off the
		// end of it is worse than no line.
		String verdict = predicted.breachingLanes() == 0
			? "nothing outside the footprint"
			: predicted.breachingLanes() + (predicted.breachingLanes() == 1 ? " lane" : " lanes")
				+ " breach, worst " + predicted.worstBreach();
		String wrong = predicted.wrongNotes() == 0 ? ""
			: ", " + predicted.wrongNotes()
				+ (predicted.wrongNotes() == 1 ? " note sounds twice" : " notes sound twice");
		return predicted.spanZ() + " blocks deep - " + verdict + wrong;
	}

	/** Grey while it is being worked out, green when it is clean, and warm when it is not. */
	private static int forecastColour(Forecast predicted) {
		if (predicted == null) {
			return 0xFF8A9098;
		}
		if (predicted.error() != null || predicted.wrongNotes() > 0) {
			return 0xFFFF5555;
		}
		return predicted.breachingLanes() == 0 ? 0xFF7ACF7A : 0xFFFFAA00;
	}

	private void changeRate(int delta) {
		commandsPerTick = Math.max(FastNoteblocksConfig.MIN_COMMANDS_PER_TICK,
			Math.min(FastNoteblocksConfig.MAX_COMMANDS_PER_TICK, commandsPerTick + delta));
		init();
	}

	private void changeWidth(int delta) {
		laneWidth = Math.max(FastNoteblocksConfig.MIN_BUILD_LANE_WIDTH,
			Math.min(FastNoteblocksConfig.MAX_BUILD_LANE_WIDTH, laneWidth + delta));
		init();
	}

	private void changeFloors(int delta) {
		laneFloors = Math.max(FastNoteblocksConfig.MIN_BUILD_LANE_FLOORS,
			Math.min(FastNoteblocksConfig.MAX_BUILD_LANE_FLOORS, laneFloors + delta));
		init();
	}

	private static String nth(int floors) {
		return switch (floors) {
			case 2 -> "half";
			case 3 -> "third";
			case 4 -> "quarter";
			default -> floors + "th";
		};
	}

	private static String describe(SongBuilder.PasteMode option) {
		return switch (option) {
			case COMPACT_CUBE -> "Folds onto stacked floors joined by a glass redstone riser. "
				+ "Smallest footprint, and the only layout that keeps a long song inside earshot.";
			case COMPACT -> "Folds back and forth on one level into a square. Compact, but a long "
				+ "song still reaches past the 48-block range note blocks can be heard from.";
			case COMPACT_LANE -> "Folds up and down inside a width you set, and creeps away from you "
				+ "one step at a time. The only layout you can follow in a straight line: walk it, "
				+ "or lay a rail. More floors make it taller and shorter.";
			case ULTRA_COMPACT_LANE -> "The Compact lane, packed harder. A chord of four to seven "
				+ "stacks around a single repeater instead of stringing out along a bus, and lanes "
				+ "sit three apart rather than four wherever their notes can touch safely. Same "
				+ "width and floor controls.";
			case LANE -> "One straight line. Easiest to read and repair, largest footprint.";
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int width = Math.min(300, this.width - 40);
		int left = (this.width - width) / 2;
		int top = Math.max(40, height / 2 - 96);

		graphics.text(font, "Paste the build sequence of \"" + songName + "\" with /setblock",
			left, top - 14, 0xFFFFFFFF, false);
		graphics.text(font, "Layout", left, top + 2, 0xFF8A9098, false);

		if (hasLaneControls()) {
			graphics.text(font, laneWidth + " blocks wide before it folds back",
				left + 26, widthRow(top) + 6, 0xFFD6D8DD, false);
			graphics.text(font, laneFloors == 1
					? "1 floor - flat, and folds sideways instead"
					: laneFloors + " floors, " + (4 * laneFloors - 1) + " blocks tall - one "
						+ nth(laneFloors) + " the length",
				left + 26, floorRow(top) + 6, 0xFFD6D8DD, false);
		}

		int rateY = rateRow(top);
		SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(sequence);
		int commands = blocks.total();
		double seconds = commands / (commandsPerTick * 20.0);
		graphics.text(font, String.format(Locale.ROOT, "%d commands per tick", commandsPerTick),
			left + 26, rateY + 6, 0xFFD6D8DD, false);
		graphics.text(font, String.format(Locale.ROOT,
				"about %d blocks, roughly %.1fs", commands, seconds),
			left, rateY + 24, 0xFF8A9098, false);
		Forecast predicted = forecast;
		graphics.text(font, forecastLine(predicted), left, rateY + 36, forecastColour(predicted),
			false);
		graphics.text(font, "Needs /setblock permission. Overwrites whatever is there.",
			left, rateY + BUTTON_ROW + 44, 0xFF8A9098, false);
		if (commandsPerTick > 64) {
			graphics.text(font, "High rates can trip server command spam limits.",
				left, rateY + BUTTON_ROW + 56, 0xFFFFAA00, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
