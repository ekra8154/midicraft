package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.PasteRate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
	/**
	 * The composition behind that sequence, for the one layout that has to read it.
	 *
	 * <p>Only the half-tick lane looks at this, and only because a sequence delay is denominated in
	 * repeater ticks and its notes are not. Everything else forecasts from the sequence, which is
	 * still the thing that gets built.</p>
	 */
	private final com.fastnoteblocks.client.composer.ComposerProject project;
	private final boolean dedupeIdenticalNotes;
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
	private double commandsPerTick;
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
	 * @param above blocks the build reaches past the top of the world, and {@code below} past the
	 *     bottom. Both nought for a build that fits. Measured against the level's own limits and
	 *     never against 320 and -64: a datapack moves either of them, and a warning that assumed
	 *     vanilla would be wrong in exactly the worlds that have a reason to need it.
	 * @param error the reason there is no build at all, or {@code null} when there is one
	 */
	private record Forecast(int spanZ, int breachingLanes, int worstBreach, int wrongNotes,
			int above, int below, String error) {
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
		com.fastnoteblocks.client.composer.ComposerProject project,
		boolean dedupeIdenticalNotes,
		SongBuilder.PasteMode initialMode,
		Consumer<SongBuilder.PasteMode> confirm
	) {
		super(Component.literal("Paste sequence in world"));
		this.parent = parent;
		this.songName = songName;
		this.sequence = sequence;
		this.project = project;
		this.dedupeIdenticalNotes = dedupeIdenticalNotes;
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
			|| mode == SongBuilder.PasteMode.ULTRA_COMPACT_LANE
			|| mode == SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE;
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
			addRenderableWidget(new Choice(left, widthRow(top), width,
				FastNoteblocksConfig.MAX_BUILD_LANE_WIDTH - FastNoteblocksConfig.MIN_BUILD_LANE_WIDTH
					+ 1,
				laneWidth - FastNoteblocksConfig.MIN_BUILD_LANE_WIDTH,
				rung -> widthLine(FastNoteblocksConfig.MIN_BUILD_LANE_WIDTH + rung),
				rung -> laneWidth = FastNoteblocksConfig.MIN_BUILD_LANE_WIDTH + rung));
			addRenderableWidget(new Choice(left, floorRow(top), width,
				FastNoteblocksConfig.MAX_BUILD_LANE_FLOORS
					- FastNoteblocksConfig.MIN_BUILD_LANE_FLOORS + 1,
				laneFloors - FastNoteblocksConfig.MIN_BUILD_LANE_FLOORS,
				rung -> floorLine(FastNoteblocksConfig.MIN_BUILD_LANE_FLOORS + rung),
				rung -> laneFloors = FastNoteblocksConfig.MIN_BUILD_LANE_FLOORS + rung));
		}

		y = rateRow(top);
		addRenderableWidget(new Choice(left, y, width, PasteRate.RATES.size(),
			PasteRate.index(commandsPerTick),
			rung -> PasteRate.label(PasteRate.RATES.get(rung)),
			rung -> commandsPerTick = PasteRate.RATES.get(rung)));

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
		// Read here with the origin, for the same reason: the level is the render thread's.
		int worldTop = minecraft.level == null ? Integer.MAX_VALUE : minecraft.level.getMaxY();
		int worldFloor = minecraft.level == null ? Integer.MIN_VALUE : minecraft.level.getMinY();
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(
			FastNoteblocksConfig.get().maxBuildFloors(), laneWidth, laneFloors,
			FastNoteblocksConfig.get().ultraLaneStartTop());
		FORECASTER.execute(() -> {
			// Dropped before it is worked out, not after. A press asked for one forecast; a drag
			// across the width slider asks for a hundred and twenty, and planning a big song is tens
			// of milliseconds -- so a queue that does all of them is a screen that keeps answering
			// questions nobody is asking any more, minutes after the slider stopped moving.
			if (forecastGeneration.get() != generation) {
				return;
			}
			Forecast result;
			try {
				// The same events the Paste button would build from, which for the half-tick lane
				// means the composition rather than the sequence -- forecasting the sequence there
				// would predict a build at twice the speed of the one it is about to make.
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(origin,
					SongBuilder.notesFor(planned, sequence, project, dedupeIdenticalNotes),
					planned, limits);
				// Off the blocks rather than off the height, which is a span and says nothing
				// about where the span sits. getMaxY is the highest cell that takes a block, not
				// the first that refuses one.
				int highest = Integer.MIN_VALUE;
				int lowest = Integer.MAX_VALUE;
				for (String command : plan.commands()) {
					int y = Integer.parseInt(command.split(" ", 5)[2]);
					highest = Math.max(highest, y);
					lowest = Math.min(lowest, y);
				}
				result = new Forecast(plan.spanZ(), plan.breaches().size(),
					plan.worstBreach(), plan.wrongNotes(),
					// In long, because the no-world sentinels are the int extremes and
					// MIN_VALUE minus a height wraps round to a large positive -- which would
					// warn that two billion levels are below the floor of a world that is not
					// there.
					(int) Math.max(0, (long) highest - worldTop),
					(int) Math.max(0, (long) worldFloor - lowest), null);
			} catch (IllegalArgumentException refused) {
				result = new Forecast(0, 0, 0, 0, 0, 0, refused.getMessage());
			} catch (RuntimeException broken) {
				// A forecast that throws must not take the paste down with it: the build itself may
				// well be fine, and a screen that cannot tell you the depth is still a screen you
				// can paste from.
				result = new Forecast(0, 0, 0, 0, 0, 0, "could not work out the layout");
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

	/**
	 * What the world will not accept, when the build asks for more of it than there is.
	 *
	 * <p>A warning and not a refusal. Where a build ends up is your business -- you may be standing
	 * somewhere on purpose, or about to move -- and the paste that goes ahead loses only the cells
	 * outside the world. Everything else still lands, which is why this says how much rather than
	 * whether.</p>
	 *
	 * <p>{@code null} while the forecast is still being worked out, and for a build that fits.</p>
	 */
	private static String outsideTheWorld(Forecast predicted) {
		if (predicted == null || predicted.error() != null) {
			return null;
		}
		// A count of levels and not of blocks: what is known is how far past the edge the build
		// reaches, and saying "12 blocks" of something measured in height reads as twelve setblocks.
		if (predicted.above() > 0 && predicted.below() > 0) {
			return "top " + predicted.above() + " and bottom " + predicted.below()
				+ " levels are outside the world - both will be cut off";
		}
		if (predicted.above() > 0) {
			return "top " + levels(predicted.above()) + " above the world's ceiling - cut off";
		}
		if (predicted.below() > 0) {
			return "bottom " + levels(predicted.below()) + " below the world's floor - cut off";
		}
		return null;
	}

	private static String levels(int deep) {
		return deep == 1 ? "level is" : deep + " levels are";
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

	/**
	 * How long it takes, in the largest unit that still says something.
	 *
	 * <p>A rate of one command every four ticks turns a big build into hours, and "31984.0s" is a
	 * number nobody reads as a length of time.</p>
	 */
	private static String howLong(double seconds) {
		if (seconds < 90) {
			return String.format(Locale.ROOT, "%.1fs", seconds);
		}
		if (seconds < 5400) {
			return String.format(Locale.ROOT, "%.0f min", seconds / 60);
		}
		return String.format(Locale.ROOT, "%.1f hours", seconds / 3600);
	}

	/**
	 * A slider over a fixed run of settings, which is what all three of these are.
	 *
	 * <p>Sliders rather than a pair of buttons because two of the three ranges are long -- four to a
	 * hundred and twenty-eight blocks wide, a quarter of a command a tick to two hundred and
	 * fifty-six -- and stepping either of them end to end was thirty presses. The value is carried
	 * as a rung rather than as the number itself so that the rate can use it too: its settings are a
	 * ladder with gaps in it, not a run, and nothing here needs to know the difference.</p>
	 *
	 * <p>The slider says what the setting means rather than what it is set to. A number needs a
	 * sentence next to it either way, and the sentence has to move as the slider does.</p>
	 */
	private final class Choice extends AbstractSliderButton {
		private final int rungs;
		private final IntFunction<String> say;
		private final IntConsumer choose;

		Choice(int x, int y, int width, int rungs, int chosen, IntFunction<String> say,
				IntConsumer choose) {
			super(x, y, width, 20, Component.empty(),
				rungs <= 1 ? 0 : (double) Math.max(0, Math.min(rungs - 1, chosen)) / (rungs - 1));
			this.rungs = rungs;
			this.say = say;
			this.choose = choose;
			updateMessage();
		}

		private int rung() {
			return rungs <= 1 ? 0 : (int) Math.round(value * (rungs - 1));
		}

		@Override
		protected void updateMessage() {
			// Called from the superclass constructor, before this class has its fields.
			if (say != null) {
				setMessage(Component.literal(say.apply(rung())));
			}
		}

		@Override
		protected void applyValue() {
			choose.accept(rung());
			requestForecast();
		}
	}

	private String widthLine(int blocks) {
		return blocks + " blocks wide before it folds back";
	}

	private String floorLine(int floors) {
		return floors == 1
			? "1 floor - flat, and folds sideways instead"
			: floors + " floors, " + (4 * floors - 1) + " blocks tall - one " + nth(floors)
				+ " the length";
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
			case ULTRA_HALF_TICK_LANE -> "Two Ultra compact lane snakes side by side, one playing "
				+ "the even game ticks and one the odd, with four blocks between their corridors. "
				+ "Same width and floor controls, applied to each. You wire the head yourself: the "
				+ "second snake must start exactly one game tick after the first. Experimental -- "
				+ "the two are not yet paced against each other, so they drift apart as the song "
				+ "goes on.";
			case HALF_TICK_LANE -> "Two straight lines, the right one playing the even game ticks "
				+ "and the left the odd. Plays the song at double speed and twice the timing "
				+ "precision. You wire the head yourself: the left lane must start exactly one game "
				+ "tick after the right.";
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

		int rateY = rateRow(top);
		SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(sequence);
		int commands = blocks.total();
		double seconds = commands / (commandsPerTick * 20.0);
		// "If you stay with it" is the whole of the honesty here: a build longer than the loaded
		// region around you pauses at the edge and waits to be walked to, so the figure is a floor
		// and not an estimate.
		graphics.text(font, String.format(Locale.ROOT,
				"about %d blocks, roughly %s if you stay with it", commands, howLong(seconds)),
			left, rateY + 24, 0xFF8A9098, false);
		Forecast predicted = forecast;
		graphics.text(font, forecastLine(predicted), left, rateY + 36, forecastColour(predicted),
			false);
		int note = rateY + BUTTON_ROW + 44;
		graphics.text(font, "Needs /setblock permission. Overwrites whatever is there.",
			left, note, 0xFF8A9098, false);
		// Stacked rather than each at its own fixed height, because either of them can be absent and
		// a warning with a gap above it reads as a warning about something else.
		String outside = outsideTheWorld(predicted);
		if (outside != null) {
			note += 12;
			graphics.text(font, outside, left, note, 0xFFFFAA00, false);
		}
		if (commandsPerTick > 64) {
			note += 12;
			graphics.text(font, "High rates can trip server command spam limits.",
				left, note, 0xFFFFAA00, false);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
