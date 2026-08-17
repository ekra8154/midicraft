package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	 * @param error the reason there is no build at all, or {@code null} when there is one
	 */
	private record Forecast(int spanZ, int breachingLanes, int worstBreach, List<FaultLine> faults,
			String error) {

		/** Whether anything is wrong with the machine itself, as opposed to with where it lands. */
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
			|| mode == SongBuilder.PasteMode.ULTRA_COMPACT_LANE
			|| mode == SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2;
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
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(
			FastNoteblocksConfig.get().maxBuildFloors(), laneWidth, laneFloors,
			FastNoteblocksConfig.get().ultraLaneStartTop());
		FORECASTER.execute(() -> {
			Forecast result;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
					origin, SongBuilder.eventNotes(sequence), planned, limits);
				result = new Forecast(plan.spanZ(), plan.breaches().size(),
					plan.worstBreach(), faultLines(plan), null);
			} catch (IllegalArgumentException refused) {
				result = new Forecast(0, 0, 0, List.of(), refused.getMessage());
			} catch (RuntimeException broken) {
				// A forecast that throws must not take the paste down with it: the build itself may
				// well be fine, and a screen that cannot tell you the depth is still a screen you
				// can paste from.
				result = new Forecast(0, 0, 0, List.of(), "could not work out the layout");
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
			case ULTRA_COMPACT_LANE_V2 -> "The same shapes, decided again from scratch. Every lane "
				+ "ends by cutting whichever chord reaches its wall, so nothing is padded out to get "
				+ "there. Chords above 25 notes are not built. Experimental: try it against the "
				+ "layout above rather than instead of it.";
			case LANE -> "One straight line. Easiest to read and repair, largest footprint.";
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
