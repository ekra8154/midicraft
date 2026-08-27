package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.PasteRate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	private int reseedDelay;
	/** The width control, so its line can be rewritten when a forecast says the width was raised. */
	private Choice widthChoice;
	/**
	 * The built width the width line was last written for.
	 *
	 * <p>Nought while no forecast is in: the line falls back to saying what was asked for, which is
	 * the truth for every width that is not raised and is what it said before any of this. Held so
	 * the message is rewritten when the answer changes rather than once a frame.</p>
	 */
	private int labelledWidth;

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
	 * <p>Every fault the plan knows about, where it used to be the breach count and the doubled notes.
	 * A cell two shapes both wanted and a run of wire the signal never reaches are the two that cost
	 * the most music, and they were the two this screen never mentioned -- a collision because it
	 * ended the paste somewhere else entirely and so was never a forecast at all, and a dead line
	 * because nothing asked the plan for it. Both are shown here whatever the debug paste is set to:
	 * what the marking decides is the colour of the blocks, never whether the player is told.</p>
	 *
	 * <p>The faults arrive as finished lines rather than as counts. They are composed off the plan on
	 * the forecasting thread, which is where the plan is: the alternative is holding five counts and
	 * five positions on this record and writing the sentences at render time, once a frame, for
	 * something that changes when the settings do and not otherwise.</p>
	 *
	 * @param above blocks the build reaches past the top of the world, and {@code below} past the
	 *     bottom. Both nought for a build that fits. Measured against the level's own limits and
	 *     never against 320 and -64: a datapack moves either of them, and a warning that assumed
	 *     vanilla would be wrong in exactly the worlds that have a reason to need it.
	 * @param error the reason there is no build at all, or {@code null} when there is one
	 */
	private record Forecast(int spanZ, int breachingLanes, int worstBreach, List<FaultLine> faults,
			int above, int below, String error, int builtWidth) {

		/**
		 * Whether anything is wrong with the machine itself, as opposed to with where it lands.
		 *
		 * <p>Which is why {@code above} and {@code below} are not in it. A build hanging out of the
		 * world is a fault of the ground you chose to stand on and it has its own line to say so;
		 * everything counted here would be wrong wherever you put it.</p>
		 */
		boolean broken() {
			return !faults.isEmpty();
		}
	}

	/**
	 * A fault, and what there is to say about it that will not fit on the line.
	 *
	 * <p>The line has room for a count and one block to walk to. A contested cell has more to it than
	 * that -- which two things wanted it, and which of them got it -- and that is the question you ask
	 * standing in front of the coordinate, not before you set off. So it hangs off the line as a
	 * tooltip rather than being spent on the width of the dialog.</p>
	 *
	 * @param detail empty where there is nothing further to say, which is every fault but the
	 *     collision so far
	 */
	record FaultLine(String text, List<String> detail) {
		FaultLine(String text) {
			this(text, List.of());
		}
	}

	/**
	 * How many fault lines the dialog keeps room for.
	 *
	 * <p>Three, which is every kind that has ever been seen together on one build -- a contested cell,
	 * the dead line under it, and a doubled note somewhere else. The room is kept whether or not there
	 * is anything to put in it, because the forecast arrives from another thread long after the
	 * buttons have been laid out and a dialog that grew when it landed would move the Paste button out
	 * from under the pointer. A fourth kind is counted into the last line rather than dropped.</p>
	 */
	private static final int FAULT_LINES = 3;

	/**
	 * One line per fault, each with a block you can walk to.
	 *
	 * <p>Worst first, and worst means what costs the most music. A contested cell opens the list
	 * because it is the fault the rest tend to be downstream of -- the shape that lost its cell is the
	 * reason the wire above it dies -- so read in this order they read as one story rather than as
	 * five separate things going wrong.</p>
	 *
	 * <p>Coordinates space-separated, because the thing to do with one is paste it into {@code /tp}.
	 * They are world positions off the same origin the build would use, so they are where the blocks
	 * will actually be, not offsets from something.</p>
	 */
	static List<FaultLine> faultLines(SongBuilder.PastePlan plan) {
		List<FaultLine> lines = new ArrayList<>();
		SongBuilder.FaultSites sites = plan.faultSites();
		if (!plan.collisions().isEmpty()) {
			lines.add(new FaultLine(many(plan.collisions().size(), "cell", "contested")
				+ at(plan.collisions().keySet().iterator().next(), plan.collisions().size()),
				contested(plan)));
		}
		if (plan.severedLanes() > 0) {
			lines.add(new FaultLine(many(plan.severedLanes(), "repeater", "reads nothing behind it")
				+ at(first(sites.severed()), plan.severedLanes())));
		}
		if (plan.deadNotes() > 0) {
			// The break rather than the note, and said so in the sentence: the count is notes and the
			// coordinate is a cell of wire some distance upstream of the first of them. Letting those
			// two share an unqualified "at" is exactly how somebody ends up standing in front of a note
			// block that is perfectly fine.
			int breaks = sites.breaks().size();
			BlockPos where = first(sites.breaks());
			// The number of breaks as well as the first of them, because they are different questions:
			// one break is a cell to go and look at, and a hundred and forty-five is a machine that is
			// dead everywhere and a width to stop trying.
			lines.add(new FaultLine(plan.deadNotes()
				+ (plan.deadNotes() == 1 ? " note silent" : " notes silent")
				+ (where == null ? ""
					: breaks > 1 ? ", " + breaks + " breaks, first at " + coords(where)
					: ", wire breaks at " + coords(where))));
		}
		if (plan.missingNotes() > 0) {
			lines.add(new FaultLine(many(plan.missingNotes(), "note", "left out")
				+ at(first(sites.missingNotes()), plan.missingNotes())));
		}
		if (plan.wrongNotes() > 0) {
			lines.add(new FaultLine(many(plan.wrongNotes(), "note", "sounds twice")
				+ at(first(sites.wrongNotes()), plan.wrongNotes())));
		}
		if (lines.size() > FAULT_LINES) {
			// Trimmed rather than truncated. A list that simply stopped at three would say a build has
			// three things wrong with it, which is a worse answer than a short one.
			int hidden = lines.size() - FAULT_LINES;
			lines = new ArrayList<>(lines.subList(0, FAULT_LINES));
			FaultLine last = lines.get(FAULT_LINES - 1);
			lines.set(FAULT_LINES - 1,
				new FaultLine(last.text() + " (+" + hidden + " more)", last.detail()));
		}
		return List.copyOf(lines);
	}

	/** How many contested cells the tooltip names before it starts counting them instead. */
	private static final int CELLS_LISTED = 6;

	/**
	 * Every contested cell, as the pair that wanted it.
	 *
	 * <p>Read off the sentence the walk stored rather than off a structure, because the sentence
	 * <em>is</em> the structure: {@code collisions} is a map of position to prose and several probes
	 * read it that way. A split that does not find its separator falls through to the whole sentence,
	 * which is worse to look at and still says the true thing.</p>
	 *
	 * <p>{@code minecraft:} comes off the front of both blocks. It is on every one of them, so it
	 * distinguishes nothing and costs a third of the width of a line that has two block names and a
	 * block state in it.</p>
	 */
	private static List<String> contested(SongBuilder.PastePlan plan) {
		List<String> detail = new ArrayList<>();
		detail.add(plan.collisions().size() == 1 ? "One cell two shapes both wanted:"
			: plan.collisions().size() + " cells two shapes both wanted:");
		for (Map.Entry<BlockPos, String> clash : plan.collisions().entrySet()) {
			if (detail.size() > CELLS_LISTED) {
				detail.add("...and " + (plan.collisions().size() - CELLS_LISTED) + " more");
				break;
			}
			// Both halves labelled, because which way round the pair goes is the whole diagnosis and the
			// names alone do not carry it. Which of the two is actually in the ground decides whether
			// what is broken is the wire above the cell or the note that never got hung -- and with one
			// of them routinely being air, an unlabelled pair reads as though nothing wanted it.
			String[] pair = clash.getValue().replace("minecraft:", "").split(" held off ", 2);
			detail.add(coords(clash.getKey()) + "  held: " + pair[0]
				+ (pair.length < 2 ? "" : "\n      wanted: " + pair[1]));
		}
		return List.copyOf(detail);
	}

	/** {@code 3 cells contested}, with the noun and the verb agreeing with the count. */
	private static String many(int count, String noun, String said) {
		return count == 1 ? "1 " + noun + " " + said
			: count + " " + noun + "s " + said.replaceFirst("^reads\\b", "read")
				.replaceFirst("^sounds\\b", "sound");
	}

	/**
	 * {@code  at 3 65 6}, or {@code , first at 3 65 6} where the one shown is one of several.
	 *
	 * <p>Empty when there is no position to give, which is the case a carried coordinate cannot rule
	 * out: the sites are filled in by whichever pass found the fault, and a fault found somewhere that
	 * has no block to point at would otherwise send the player to {@code 0 0 0}.</p>
	 */
	private static String at(BlockPos where, int outOf) {
		if (where == null) {
			return "";
		}
		return (outOf > 1 ? ", first at " : " at ") + coords(where);
	}

	/** Space-separated, because the thing to do with a position is paste it into {@code /tp}. */
	private static String coords(BlockPos where) {
		return where.getX() + " " + where.getY() + " " + where.getZ();
	}

	private static BlockPos first(List<BlockPos> sites) {
		return sites.isEmpty() ? null : sites.getFirst();
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
		this.reseedDelay = FastNoteblocksConfig.get().parityReseedDelay();
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

	private int reseedRow(int top) {
		return floorRow(top) + 22;
	}

	private int rateRow(int top) {
		return widthRow(top) + (hasLaneControls() ? 48 : 0)
			+ (hasReseedControl() ? 22 : 0) + 10;
	}

	/**
	 * Whether this layout runs two machines that may trade halves of the game tick.
	 *
	 * <p>Only the interleaved paste does. The other half-tick layouts give each machine one
	 * parity for the whole song, so there is no reseed for a threshold to govern.</p>
	 */
	private boolean hasReseedControl() {
		return mode == SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
	}

	/**
	 * The reseed thresholds offered, in game ticks, coarsely enough to be a choice.
	 *
	 * <p>A range of 16 to 512 one tick at a time is five hundred rungs of a slider nobody would
	 * turn. These are the points the measurement actually distinguishes.</p>
	 */
	private static final List<Integer> RESEED_DELAYS =
		List.of(16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512);

	private static int reseedRung(int ticks) {
		int best = 0;
		for (int rung = 1; rung < RESEED_DELAYS.size(); rung++) {
			if (Math.abs(RESEED_DELAYS.get(rung) - ticks)
					< Math.abs(RESEED_DELAYS.get(best) - ticks)) {
				best = rung;
			}
		}
		return best;
	}

	private String reseedLine(int ticks) {
		if (ticks >= FastNoteblocksConfig.MAX_PARITY_RESEED_DELAY) {
			return "never swap halves - each machine keeps one, and pads";
		}
		return ticks + " ticks of waiting before a machine swaps halves";
	}

	/** Whether this layout folds inside a width you choose, and so has a width and a floor count. */
	private boolean hasLaneControls() {
		return mode == SongBuilder.PasteMode.COMPACT_LANE
			|| mode == SongBuilder.PasteMode.ULTRA_COMPACT_LANE
			|| mode == SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2
			|| mode == SongBuilder.PasteMode.ULTRA_HALF_TICK_LANE
			|| mode == SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
	}

	@Override
	protected void init() {
		clearWidgets();
		int width = Math.min(300, this.width - 40);
		int left = (this.width - width) / 2;
		int top = topRow();

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
			labelledWidth = 0;
			widthChoice = addRenderableWidget(new Choice(left, widthRow(top), width,
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

		if (hasReseedControl()) {
			addRenderableWidget(new Choice(left, reseedRow(top), width, RESEED_DELAYS.size(),
				reseedRung(reseedDelay),
				rung -> reseedLine(RESEED_DELAYS.get(rung)),
				rung -> reseedDelay = RESEED_DELAYS.get(rung)));
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
			FastNoteblocksConfig.get().setParityReseedDelay(reseedDelay);
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
	 * <p>Twelve lower than it used to be, for the forecast line, and {@link #FAULT_LINES} lower again
	 * for the faults under it. They go above the buttons rather than below them because they are part
	 * of the choice being made, not a footnote to it.</p>
	 */
	private static final int BUTTON_ROW = 47 + FAULT_LINES * 11 + 2;

	/**
	 * Top of the dialog.
	 *
	 * <p>Raised by exactly what the fault lines added below, so the dialog grew upward into the empty
	 * half of the screen rather than downward into the two notes under the buttons. Those two are the
	 * lowest thing on the screen and they were already close to the bottom of a small window.</p>
	 */
	private int topRow() {
		return Math.max(40, height / 2 - 96 - (BUTTON_ROW - 46));
	}

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
					plan.worstBreach(), faultLines(plan),
					// In long, because the no-world sentinels are the int extremes and
					// MIN_VALUE minus a height wraps round to a large positive -- which would
					// warn that two billion levels are below the floor of a world that is not
					// there.
					(int) Math.max(0, (long) highest - worldTop),
					(int) Math.max(0, (long) worldFloor - lowest), null, plan.builtWidth());
			} catch (IllegalArgumentException refused) {
				result = new Forecast(0, 0, 0, List.of(), 0, 0, refused.getMessage(), 0);
			} catch (RuntimeException broken) {
				// A forecast that throws must not take the paste down with it: the build itself may
				// well be fine, and a screen that cannot tell you the depth is still a screen you
				// can paste from.
				result = new Forecast(0, 0, 0, List.of(), 0, 0, "could not work out the layout", 0);
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
		// end of it is worse than no line. Which is why the machine's own faults moved off this line
		// and onto the one below rather than being tacked on the end of it.
		String verdict = predicted.breachingLanes() == 0
			? "nothing outside the footprint"
			: predicted.breachingLanes() + (predicted.breachingLanes() == 1 ? " lane" : " lanes")
				+ " breach, worst " + predicted.worstBreach();
		return predicted.spanZ() + " blocks deep - " + verdict;
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
		if (predicted.error() != null || predicted.broken()) {
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

	/**
	 * What the width control says, which is not always the number it is set to.
	 *
	 * <p>A lane has to clear the song's widest single event, so the builder takes the larger of that
	 * and the width chosen here -- and a width below the floor is not refused, it is quietly raised.
	 * Every width below the floor then builds identically: on a song whose biggest chord wants
	 * thirteen columns, six through eighteen are the same build down to the block. The slider still
	 * goes wherever it likes; it just stops promising a footprint the build will not honour.</p>
	 */
	private String widthLine(int blocks) {
		return labelledWidth > blocks
			? blocks + " asked - built " + labelledWidth + " wide, the widest chord needs it"
			: blocks + " blocks wide before it folds back";
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
			case ULTRA_COMPACT_LANE_V2 -> "The same shapes, decided again from scratch. Every lane "
				+ "ends by cutting whichever chord reaches its wall, so nothing is padded out to get "
				+ "there. Chords above 25 notes are not built. Experimental: try it against the "
				+ "layout above rather than instead of it.";
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
			case INTERLEAVED_HALF_TICK -> "The two half-tick machines woven through one region: "
				+ "each one a serpentine whose long trunk turns leave room, and the other's fingers "
				+ "reach into it, mirrored, half a cycle along. Both halves of every bar play within "
				+ "a few blocks of each other. You wire the heads yourself: the second machine must "
				+ "start exactly one game tick after the first. Experimental.";
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int width = Math.min(300, this.width - 40);
		int left = (this.width - width) / 2;
		int top = topRow();

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
		// The width line is the one label on this screen that cannot be worked out from the setting it
		// shows, so it waits for the forecast and is rewritten when the answer changes. While one is in
		// flight the forecast is null, which puts the line back to plain rather than leaving a number
		// from the width before this one standing under a slider that has moved.
		int built = predicted == null ? 0 : predicted.builtWidth();
		if (widthChoice != null && built != labelledWidth) {
			labelledWidth = built;
			widthChoice.updateMessage();
		}
		graphics.text(font, forecastLine(predicted), left, rateY + 36, forecastColour(predicted),
			false);
		// Always red. Everything on these lines takes music away, and the line above them can be green
		// at the same time -- a build that lands entirely inside its footprint and plays half the song
		// is exactly the case worth catching before pasting rather than after.
		List<FaultLine> faults = predicted == null ? List.of() : predicted.faults();
		for (int line = 0; line < faults.size(); line++) {
			FaultLine fault = faults.get(line);
			int y = rateY + 47 + line * 11;
			graphics.text(font, fault.text(), left, y, 0xFFFF5555, false);
			// Hit-tested against the text rather than against the row, because these are drawn rather
			// than laid out: a row-wide target on a line that ends halfway across would put a tooltip up
			// over blank space with nothing under the pointer to explain it.
			if (!fault.detail().isEmpty() && mouseX >= left && mouseX < left + font.width(fault.text())
					&& mouseY >= y - 1 && mouseY < y + font.lineHeight) {
				graphics.setTooltipForNextFrame(font,
					font.split(Component.literal(String.join("\n", fault.detail())), 280),
					mouseX, mouseY);
			}
		}
		// Below the buttons, and stacked. These are about the paste rather than about the build --
		// what it needs permission to do, and where it would land -- so they sit under the thing you
		// press rather than among the faults above it.
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

	/**
	 * Hands the game its GUI scale back the instant this screen goes, whatever it is going to.
	 *
	 * <p>Vanilla calls this from the middle of the screen swap, so it lands before anything is
	 * drawn. If another of our screens is opening it puts the scale straight back in its own init,
	 * and if nothing is, the HUD behind this one is already the right size on the very next frame
	 * rather than a tick later.</p>
	 */
	@Override
	public void removed() {
		ComposerScale.screenClosed(this);
		super.removed();
	}
}
