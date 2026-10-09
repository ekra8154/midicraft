package com.midicraft.client.compat;

import com.midicraft.client.McCompat;
import com.midicraft.client.MidicraftConfig;
import com.midicraft.client.PasteRate;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
	private final List<MidicraftConfig.SequenceTrack> sequence;
	/**
	 * The composition behind that sequence, for the one layout that has to read it.
	 *
	 * <p>Only the half-tick lane looks at this, and only because a sequence delay is denominated in
	 * repeater ticks and its notes are not. Everything else forecasts from the sequence, which is
	 * still the thing that gets built.</p>
	 */
	private final com.midicraft.client.composer.ComposerProject project;
	private final boolean dedupeIdenticalNotes;
	private final Consumer<SongBuilder.PasteMode> confirm;
	/**
	 * Saves the build these settings make as a file instead of pasting it, or {@code null} where
	 * the screen offers no export.
	 *
	 * <p>Here rather than in a menu of its own because a schematic is a build, not a song: it only
	 * exists once a width, a floor count and the blocks are chosen, and these are the rows that
	 * choose them. Exporting from here is exporting exactly what Paste would have built.</p>
	 */
	private final BiConsumer<SongBuilder.PasteMode, SchematicWriter.Format> export;
	/**
	 * The layout being pasted, which is no longer asked for.
	 *
	 * <p>Only the interleaved layout is kept up, so there is nothing to choose between and no row
	 * that names it. Still carried, because the forecast and the paste both plan by it.</p>
	 */
	private final SongBuilder.PasteMode mode;
	/**
	 * Whether the build starts on its top floor and works down.
	 *
	 * <p>Asked here rather than in the settings, because it is a question about the ground you are
	 * standing on: you start from the top when what is under the origin is what you cannot dig. The
	 * last answer is remembered like the width is, as where the next paste starts from.</p>
	 */
	private boolean startTop;
	/**
	 * What the blocks are made of: the palette, lamps, or coloured by what laid them.
	 *
	 * <p>Put into effect the moment it is picked rather than on Paste, because the colour-coded build
	 * also changes what gets built -- a collision is built through instead of refused -- and the
	 * forecast plans off the builder's copies of it. {@link #coloursOnOpen} is what goes back if the
	 * screen is left without pasting.</p>
	 */
	private MidicraftConfig.ColorCodedPaste colours;
	private final MidicraftConfig.ColorCodedPaste coloursOnOpen;
	private double commandsPerTick;
	/**
	 * Whether this paste will go straight into the world rather than by command, which decides what
	 * the rate row and the lines under it say. Asked once, when the dialog opens: the answer is about
	 * the world you are in, and that does not change under an open screen.
	 */
	private final boolean direct = DirectPasteSender.available(Minecraft.getInstance());
	private int laneWidth;
	private int laneFloors;
	private int reseedDelay;
	private int paceTolerance;
	private int paceCatchUpTo;
	/**
	 * The three faces of the settings column. One shows at a time, which is what keeps the panel
	 * one height whatever is being changed: the rows used to open downward in place, and with the
	 * palette and the advanced rows both open the buttons went off the bottom of the window.
	 */
	private enum Tab {
		LAYOUT("Layout"),
		PALETTE("Palette"),
		ADVANCED("Advanced");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	/**
	 * The tab showing. Static, so the screen opens on the tab it was left on for the rest of the
	 * session: the palette and the advanced rows are what a player who uses them keeps going back to.
	 */
	private static Tab tab = Tab.LAYOUT;

	/**
	 * One block of the palette: the role it fills, and where the config keeps it.
	 *
	 * <p>Typed rather than picked, because there are a thousand blocks and the useful ones are
	 * whichever the player has a stack of. Each is checked as it is typed against what that role
	 * has to do in the machine -- see {@link LaneMaterials#problem} -- and only a block that would
	 * work is ever kept.</p>
	 */
	private record PaletteSlot(String key, String shortLabel, LaneMaterials.Role role,
			Function<MidicraftConfig, String> read, BiConsumer<MidicraftConfig, String> write) {
		/** The full name and what the block has to do, since the label beside the box is short. */
		Component description() {
			return Component.empty()
				.append(Component.translatable("option.midicraft." + key))
				.append(": ")
				.append(Component.translatable("tooltip.midicraft." + key));
		}

		Tooltip tooltip() {
			return Tooltip.create(description());
		}
	}

	/**
	 * The palette, in the order its rows are shown.
	 *
	 * <p>Here and not in Settings, beside the width and the floors, because it is what this build is
	 * made of: chosen per build, remembered for the next one the way the width is, and in the same
	 * place as the buttons that build or export with it. It used to be a Settings tab of its own,
	 * which was the one part of a build you could not change from the screen that makes one.</p>
	 */
	private static final List<PaletteSlot> PALETTE = List.of(
		new PaletteSlot("lane_one_block", "Lane 1", LaneMaterials.Role.LANE,
			MidicraftConfig::laneOneBlock, MidicraftConfig::setLaneOneBlock),
		new PaletteSlot("lane_two_block", "Lane 2", LaneMaterials.Role.LANE,
			MidicraftConfig::laneTwoBlock, MidicraftConfig::setLaneTwoBlock),
		new PaletteSlot("relay_block", "Relay", LaneMaterials.Role.RELAY,
			MidicraftConfig::relayBlock, MidicraftConfig::setRelayBlock),
		new PaletteSlot("transparent_block", "Climbs", LaneMaterials.Role.TRANSPARENT,
			MidicraftConfig::transparentBlock, MidicraftConfig::setTransparentBlock),
		new PaletteSlot("support_block", "Support", LaneMaterials.Role.SUPPORT,
			MidicraftConfig::supportBlock, MidicraftConfig::setSupportBlock),
		new PaletteSlot("thin_lane_block", "Packed", LaneMaterials.Role.THIN,
			MidicraftConfig::thinLaneBlock, MidicraftConfig::setThinLaneBlock));

	/** The open palette's columns: two blocks a row, so six fit in three rows. */
	private static final int PALETTE_COLUMNS = 2;
	/** Room for the Default button at the end of the palette toggle, while it is open. */
	/**
	 * The palette being chosen, in {@link #PALETTE} order: always the last block each box held that
	 * would work, so a half-typed name never reaches a build. Written to the config on Paste or
	 * Export, like the width; leaving the screen keeps the palette you had.
	 */
	private final String[] palette = new String[PALETTE.size()];
	/** Where each palette label goes on screen, as {x, y}, for drawing it beside its box. */
	private final List<int[]> paletteLabelAt = new ArrayList<>();
	/** The catch-up control, so its line can follow the tolerance above it. */
	private Choice catchUpChoice;
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
		Thread thread = new Thread(job, "midicraft-forecast");
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
	private record Forecast(int longSide, int shortSide, int breachingLanes, int worstBreach,
			List<FaultLine> faults, int above, int below, String error, int builtWidth) {

		/**
		 * Whether anything is wrong with the machine itself, as opposed to with where it lands.
		 *
		 * <p>Which is why {@code above} and {@code below} are not in it. A build hanging out of the
		 * world is a fault of the ground you chose to stand on and it has its own line to say so;
		 * everything counted here would be wrong wherever you put it.</p>
		 */
		boolean broken() {
			// A warning is not a break: a lane past the outer wall is the footprint question the
			// size line already answers in amber, not music the build loses.
			return faults.stream().anyMatch(fault -> !fault.warning());
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
	 * @param warning drawn amber rather than red: the build still plays everything, it only stands
	 *     somewhere it was not meant to
	 */
	record FaultLine(String text, List<String> detail, boolean warning) {
		FaultLine(String text, List<String> detail) {
			this(text, detail, false);
		}

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
		// The inner walls next, for the same reason: a machine of a dual build standing in the
		// other's ground is what the contested cells and the dead lines under them come from, and
		// unlike an outer breach nothing about it sticks out of the footprint to be seen from
		// outside. The coordinate is the furthest cell of the worst lane; the tooltip has every
		// lane, worst first.
		List<SongBuilder.WallBreach> inner = plan.wallBreaches().stream()
			.filter(SongBuilder.WallBreach::inner).toList();
		if (!inner.isEmpty()) {
			lines.add(new FaultLine(many(inner.size(), "lane", "runs into the other machine's ground")
				+ at(inner.getFirst().furthest(), inner.size()), crossings(inner, "inner")));
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
		List<SongBuilder.WallBreach> outer = plan.wallBreaches().stream()
			.filter(one -> !one.inner()).toList();
		if (!outer.isEmpty()) {
			// Amber, the colour of a lane past the footprint on the line above, because it is the same
			// thing: the machine still plays every note, it only reaches further than asked.
			lines.add(new FaultLine(many(outer.size(), "lane", "runs past the outer wall")
				+ at(outer.getFirst().furthest(), outer.size()), crossings(outer, "outer"), true));
		}
		if (lines.size() > FAULT_LINES) {
			// Trimmed rather than truncated. A list that simply stopped at three would say a build has
			// three things wrong with it, which is a worse answer than a short one.
			int hidden = lines.size() - FAULT_LINES;
			lines = new ArrayList<>(lines.subList(0, FAULT_LINES));
			FaultLine last = lines.get(FAULT_LINES - 1);
			lines.set(FAULT_LINES - 1,
				new FaultLine(last.text() + " (+" + hidden + " more)", last.detail(), last.warning()));
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
				.replaceFirst("^sounds\\b", "sound").replaceFirst("^runs\\b", "run");
	}

	/**
	 * Every lane that crossed a wall, worst first, each with the cell to stand on.
	 *
	 * <p>One entry a lane -- the plan already keeps only the furthest cell of each -- so a lane a
	 * whole link out reads as one line and not as thirty. Which machine and which leg, because a
	 * dual build's two machines interleave and the coordinate alone does not say whose ground
	 * you are looking at; how far, because one column is a hung note and three is a lane.</p>
	 */
	private static List<String> crossings(List<SongBuilder.WallBreach> lanes, String which) {
		List<String> detail = new ArrayList<>();
		detail.add(lanes.size() == 1 ? "Where it crosses the " + which + " wall:"
			: "Where each crosses the " + which + " wall, worst first:");
		for (SongBuilder.WallBreach lane : lanes) {
			if (detail.size() > CELLS_LISTED) {
				detail.add("...and " + (lanes.size() - CELLS_LISTED) + " more");
				break;
			}
			detail.add("  " + coords(lane.furthest()) + "  "
				+ (lane.machine() < 0 ? "lane" : "machine " + (char) ('A' + lane.machine()))
				+ " leg " + lane.leg() + ", " + lane.past()
				+ (lane.past() == 1 ? " block" : " blocks") + " out, laid by "
				+ lane.placing().replace("chord:", ""));
		}
		return List.copyOf(detail);
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
		List<MidicraftConfig.SequenceTrack> sequence,
		com.midicraft.client.composer.ComposerProject project,
		boolean dedupeIdenticalNotes,
		SongBuilder.PasteMode initialMode,
		Consumer<SongBuilder.PasteMode> confirm,
		BiConsumer<SongBuilder.PasteMode, SchematicWriter.Format> export
	) {
		super(Component.literal("Build composition"));
		this.parent = parent;
		this.songName = songName;
		this.sequence = sequence;
		this.project = project;
		this.dedupeIdenticalNotes = dedupeIdenticalNotes;
		this.confirm = confirm;
		this.export = export;
		this.mode = SongBuilder.PasteMode.OFFERED.contains(initialMode)
			? initialMode : SongBuilder.PasteMode.OFFERED.getFirst();
		this.startTop = MidicraftConfig.get().pasteStartTop();
		this.colours = MidicraftConfig.get().colorCodedPaste();
		this.coloursOnOpen = colours;
		this.commandsPerTick = MidicraftConfig.get().commandsPerTick();
		this.laneWidth = MidicraftConfig.get().buildLaneWidth();
		this.laneFloors = MidicraftConfig.get().buildLaneFloors();
		this.reseedDelay = MidicraftConfig.get().parityReseedDelay();
		this.paceTolerance = MidicraftConfig.get().paceTolerance();
		this.paceCatchUpTo = MidicraftConfig.get().paceCatchUpTo();
		for (int slot = 0; slot < PALETTE.size(); slot++) {
			palette[slot] = PALETTE.get(slot).read().apply(MidicraftConfig.get());
		}
	}

	/**
	 * Whether this layout paces two machines against each other, which only the interleaved paste
	 * does: its two machines are walked together, and the pace tolerance is how far one may fall
	 * behind the other. See {@code SongBuilder.JOINT_PACE_TOLERANCE}.
	 */
	private boolean hasPaceControl() {
		return mode == SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
	}

	/** The pace tolerances offered, in path columns: fine near the default, coarse past it. */
	private static final List<Integer> PACE_TOLERANCES = List.of(0, 4, 8, 12, 16, 20, 24, 32, 48, 64);

	private static int paceRung(int columns) {
		int best = 0;
		for (int rung = 1; rung < PACE_TOLERANCES.size(); rung++) {
			if (Math.abs(PACE_TOLERANCES.get(rung) - columns)
					< Math.abs(PACE_TOLERANCES.get(best) - columns)) {
				best = rung;
			}
		}
		return best;
	}

	private static String paceLine(int columns) {
		return columns == 0 ? "2 lanes kept level - pads the most"
			: "2 lanes may drift " + columns + " blocks before padding";
	}

	/** What the catch-up row says, which depends on the tolerance above it. */
	private String catchUpLine(int columns) {
		if (paceTolerance == 0) {
			return "Catch up: nothing to catch up from";
		}
		if (columns >= paceTolerance) {
			return "Catch up: only back to the drift limit";
		}
		return columns == 0 ? "Catch up: all the way to level"
			: "Catch up: to within " + columns + " blocks";
	}

	/**
	 * Whether this layout runs two machines that may trade halves of the game tick.
	 *
	 * <p>The interleaved paste and the straight half-tick lane both do. The ultra half-tick
	 * layout still gives each machine one parity for the whole song, so there is no reseed there
	 * for a threshold to govern.</p>
	 */
	private boolean hasReseedControl() {
		return mode == SongBuilder.PasteMode.INTERLEAVED_HALF_TICK
			|| mode == SongBuilder.PasteMode.HALF_TICK_LANE;
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
		if (ticks >= MidicraftConfig.MAX_PARITY_RESEED_DELAY) {
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

	// ------------------------------------------------------------------ layout

	/** The popups' own measurements, so this panel sits beside Song info and Convert as one of them. */
	private static final int PADDING = ModalPanelScreen.PADDING;
	private static final int TITLE_HEIGHT = ModalPanelScreen.TITLE_HEIGHT;
	private static final int PANEL_FILL = 0xFF16191F;
	private static final int PANEL_EDGE = 0xFF3C4450;
	private static final int HEADING_COLOR = 0xFF8FD3FF;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int LABEL_COLOR = 0xFFD6D8DD;
	private static final int HINT_COLOR = 0xFF8A9098;
	private static final int GOOD_COLOR = 0xFF7ACF7A;
	private static final int WARN_COLOR = 0xFFFFAA00;
	private static final int BAD_COLOR = 0xFFFF5555;

	private static final int ROW = 22;
	private static final int LINE = 11;
	private static final int TAB_HEIGHT = 14;
	/** The tallest tab's rows -- layout and advanced both reach four -- which every tab is given. */
	private static final int CONTENT_ROWS = 4;
	/** Lines the forecast card keeps room for: two of size, a gap, two checks and three faults. */
	private static final int CARD_LINES = 9;
	/** Lines kept above the buttons for what the paste needs and where it may go wrong. */
	private static final int NOTE_LINES = 2;
	/** Narrower than this and the forecast goes under the settings instead of beside them. */
	private static final int TWO_COLUMNS_FROM = 400;
	/** As wide as the longest line on the layout tab, the floor count, and no wider. */
	private static final int SETTINGS_WIDTH = 250;
	private static final int PANEL_MAX_WIDTH = 440;

	private int panelLeft;
	private int panelTop;
	private int panelWidth;
	private int panelHeight;
	private boolean twoColumns;
	/** The settings column. */
	private int columnLeft;
	private int columnWidth;
	private int tabsTop;
	private int contentTop;
	/** The forecast card. */
	private int cardLeft;
	private int cardTop;
	private int cardWidth;
	private int cardBottom;
	private int notesTop;
	private int buttonsTop;

	private Button exportButton;
	/** Whether the export menu is open over the buttons. */
	private boolean exportMenuOpen;
	/** Where the export menu was last drawn, as {left, top, width}, for a click to be aimed at it. */
	private int[] exportMenuAt;
	/** Where each fault line of the card was drawn, as {x, y, width}, for its tooltip. */
	private final List<int[]> cardFaultAt = new ArrayList<>();
	private final List<List<String>> cardFaultDetail = new ArrayList<>();

	/**
	 * Where everything goes, worked out once a layout from the window's size.
	 *
	 * <p>One height whatever the tab, and the buttons pinned to the bottom of the panel, so nothing
	 * that opens can push them out of reach. Two columns where there is room for them -- the settings
	 * on the left and what they will build on the right, so moving a slider shows its effect beside
	 * it -- and one where there is not, the card under the settings.</p>
	 */
	private void layout() {
		panelWidth = Math.min(PANEL_MAX_WIDTH, width - 16);
		twoColumns = panelWidth >= TWO_COLUMNS_FROM;
		int inner = panelWidth - 2 * PADDING;
		columnWidth = twoColumns ? Math.min(SETTINGS_WIDTH, inner * 3 / 5) : inner;
		int settings = TAB_HEIGHT + 6 + CONTENT_ROWS * ROW;
		int card = LINE + 4 + CARD_LINES * LINE;
		int body = twoColumns ? Math.max(settings, card) : settings + 8 + card;
		panelHeight = PADDING + TITLE_HEIGHT + body + 8 + NOTE_LINES * LINE + 4 + 20 + PADDING;
		panelLeft = (width - panelWidth) / 2;
		panelTop = Math.max(4, (height - panelHeight) / 2);
		columnLeft = panelLeft + PADDING;
		tabsTop = panelTop + PADDING + TITLE_HEIGHT;
		contentTop = tabsTop + TAB_HEIGHT + 6;
		cardLeft = twoColumns ? columnLeft + columnWidth + 12 : columnLeft;
		cardTop = twoColumns ? tabsTop : tabsTop + settings + 8;
		cardWidth = twoColumns ? panelLeft + panelWidth - PADDING - cardLeft : inner;
		buttonsTop = panelTop + panelHeight - PADDING - 20;
		notesTop = buttonsTop - 4 - NOTE_LINES * LINE;
		cardBottom = notesTop - 8;
	}

	@Override
	protected void init() {
		clearWidgets();
		layout();
		widthChoice = null;
		catchUpChoice = null;
		paletteLabelAt.clear();
		exportMenuOpen = false;
		switch (tab) {
			case LAYOUT -> addLayoutRows();
			case PALETTE -> addPaletteRows();
			case ADVANCED -> addAdvancedRows();
		}

		// Paste, Export and Cancel along the bottom, the way Convert puts its two: the action and the
		// key for it on the button.
		int inner = panelWidth - 2 * PADDING;
		int buttons = export == null ? 2 : 3;
		int each = (inner - 4 * (buttons - 1)) / buttons;
		int x = columnLeft;
		addRenderableWidget(Button.builder(Component.literal("Paste (Enter)"), clicked -> paste())
			.bounds(x, buttonsTop, each, 20)
			.tooltip(Tooltip.create(Component.literal(direct
				? "Builds it beside you, straight into the world. Needs permission to use commands."
				: "Builds it beside you with /setblock, at the paste rate under Advanced. Needs "
					+ "permission to use commands.")))
			.build());
		x += each + 4;
		exportButton = null;
		if (export != null) {
			exportButton = addRenderableWidget(Button.builder(Component.literal(exportLabel()),
					clicked -> setExportMenuOpen(!exportMenuOpen))
				.bounds(x, buttonsTop, each, 20)
				.tooltip(Tooltip.create(Component.literal("Save this build as a file instead: a "
					+ ".litematic for Litematica or a .schem for WorldEdit. Nothing is placed.")))
				.build());
			x += each + 4;
		}
		addRenderableWidget(Button.builder(Component.literal("Cancel (Esc)"), clicked -> onClose())
			.bounds(x, buttonsTop, columnLeft + inner - x, 20).build());
		requestForecast();
	}

	/** Where the build starts, what it is made of, and how wide and tall it folds. */
	private void addLayoutRows() {
		int y = contentTop;
		// A button that flips rather than a slider of two rungs: there are two answers, and the one
		// showing is the one chosen.
		addRenderableWidget(Button.builder(Component.literal(startLine(startTop)), clicked -> {
				startTop = !startTop;
				clicked.setMessage(Component.literal(startLine(startTop)));
				requestForecast();
			})
			.bounds(columnLeft, y, columnWidth, 20)
			.tooltip(Tooltip.create(Component.literal("On multifloor builds, whether the beginning "
				+ "of the song is on the top floor or the bottom floor.")))
			.build());
		y += ROW;
		// Cycles in the order offered, plain first: the ordinary build is the one most pastes want.
		addRenderableWidget(Button.builder(Component.literal(blocksLine(colours)), clicked -> {
				MidicraftConfig.ColorCodedPaste[] all = MidicraftConfig.ColorCodedPaste.values();
				colours = all[(colours.ordinal() + 1) % all.length];
				MidicraftConfig.get().setColorCodedPaste(colours);
				clicked.setMessage(Component.literal(blocksLine(colours)));
				clicked.setTooltip(Tooltip.create(Component.literal(blocksTooltip(colours))));
				requestForecast();
			})
			.bounds(columnLeft, y, columnWidth, 20)
			.tooltip(Tooltip.create(Component.literal(blocksTooltip(colours))))
			.build());
		y += ROW;
		if (hasLaneControls()) {
			labelledWidth = 0;
			widthChoice = addRenderableWidget(new Choice(columnLeft, y, columnWidth,
				MidicraftConfig.MAX_BUILD_LANE_WIDTH - MidicraftConfig.MIN_BUILD_LANE_WIDTH
					+ 1,
				laneWidth - MidicraftConfig.MIN_BUILD_LANE_WIDTH,
				rung -> widthLine(MidicraftConfig.MIN_BUILD_LANE_WIDTH + rung),
				rung -> laneWidth = MidicraftConfig.MIN_BUILD_LANE_WIDTH + rung));
			y += ROW;
			addRenderableWidget(new Choice(columnLeft, y, columnWidth,
				MidicraftConfig.MAX_BUILD_LANE_FLOORS
					- MidicraftConfig.MIN_BUILD_LANE_FLOORS + 1,
				laneFloors - MidicraftConfig.MIN_BUILD_LANE_FLOORS,
				rung -> floorLine(MidicraftConfig.MIN_BUILD_LANE_FLOORS + rung),
				rung -> laneFloors = MidicraftConfig.MIN_BUILD_LANE_FLOORS + rung));
		}
	}

	/**
	 * How the build is made rather than what it is: machine swapping and drift where the layout has
	 * them, and the rate a server paste sends at. Their defaults are the measured ones.
	 */
	private void addAdvancedRows() {
		int row = 0;
		if (hasReseedControl()) {
			addRenderableWidget(new Choice(columnLeft, contentTop + ROW * row++, columnWidth,
				RESEED_DELAYS.size(), reseedRung(reseedDelay),
				rung -> reseedLine(RESEED_DELAYS.get(rung)),
				rung -> reseedDelay = RESEED_DELAYS.get(rung)))
				.setTooltip(Tooltip.create(Component.literal("How long a lane can wait idly "
					+ "before swapping to the other half. Lower values mean more pistons but a "
					+ "more compact build.")));
		}
		if (hasPaceControl()) {
			addRenderableWidget(new Choice(columnLeft, contentTop + ROW * row++, columnWidth,
				PACE_TOLERANCES.size(), paceRung(paceTolerance),
				rung -> paceLine(PACE_TOLERANCES.get(rung)),
				rung -> {
					paceTolerance = PACE_TOLERANCES.get(rung);
					// Its line reads off the tolerance, so it is rewritten when that moves.
					if (catchUpChoice != null) {
						catchUpChoice.updateMessage();
					}
				}))
				.setTooltip(Tooltip.create(Component.literal("How far the quieter machine may "
					+ "fall behind before it pads to keep up. Higher values are more compact but "
					+ "can cause one lane to get out of earshot of the other.")));
			catchUpChoice = addRenderableWidget(new Choice(columnLeft, contentTop + ROW * row++,
				columnWidth, PACE_TOLERANCES.size(), paceRung(paceCatchUpTo),
				rung -> catchUpLine(PACE_TOLERANCES.get(rung)),
				rung -> paceCatchUpTo = PACE_TOLERANCES.get(rung)));
			catchUpChoice.setTooltip(Tooltip.create(Component.literal("How close a lane catches "
				+ "up to the other after going past the drift limit. Lower values catch up further "
				+ "but cause slightly more padding.")));
		}
		Choice rate = addRenderableWidget(new Choice(columnLeft, contentTop + ROW * row,
			columnWidth, PasteRate.RATES.size(), PasteRate.index(commandsPerTick),
			rung -> PasteRate.label(PasteRate.RATES.get(rung), direct),
			rung -> commandsPerTick = PasteRate.RATES.get(rung)));
		if (direct) {
			rate.mute();
			rate.setTooltip(Tooltip.create(Component.literal("Only used on servers. Here the "
				+ "paste goes straight into the world, as fast as the game can spare.")));
		}
	}

	/**
	 * A box per block, two to a row with its short label beside it, and a way back to the defaults.
	 *
	 * <p>Checked as it is typed, the way Settings checked it: a block that would not do turns red
	 * and says why, and the build keeps the last block that would.</p>
	 */
	private void addPaletteRows() {
		int labelWidth = 0;
		for (PaletteSlot slot : PALETTE) {
			labelWidth = Math.max(labelWidth, font.width(slot.shortLabel()));
		}
		labelWidth += 6;
		int column = (columnWidth - 4 * (PALETTE_COLUMNS - 1)) / PALETTE_COLUMNS;
		List<EditBox> boxes = new ArrayList<>();
		for (int index = 0; index < PALETTE.size(); index++) {
			PaletteSlot slot = PALETTE.get(index);
			int slotIndex = index;
			int x = columnLeft + (index % PALETTE_COLUMNS) * (column + 4);
			int y = contentTop + ROW * (index / PALETTE_COLUMNS);
			paletteLabelAt.add(new int[] {x, y});
			EditBox box = new EditBox(font, x + labelWidth, y, column - labelWidth, 20,
				Component.literal(slot.shortLabel()));
			box.setMaxLength(256);
			box.setValue(typable(palette[index]));
			box.setTooltip(slot.tooltip());
			// After the value, so filling the box in does not count as typing into it.
			box.setResponder(typed -> {
				String block = LaneMaterials.normalise(typed);
				String wrong = LaneMaterials.problem(slot.role(), block);
				if (wrong == null) {
					palette[slotIndex] = block;
					box.setTextColor(0xFFE0E0E0);
					box.setTooltip(slot.tooltip());
				} else {
					box.setTextColor(BAD_COLOR);
					box.setTooltip(Tooltip.create(Component.literal(wrong + ". The build keeps "
						+ typable(palette[slotIndex]) + ".")));
				}
			});
			boxes.add(addRenderableWidget(box));
		}
		int rows = (PALETTE.size() + PALETTE_COLUMNS - 1) / PALETTE_COLUMNS;
		addRenderableWidget(Button.builder(Component.literal("Default blocks"), clicked -> {
				for (int index = 0; index < PALETTE.size(); index++) {
					boxes.get(index).setValue(typable(PALETTE.get(index).role().fallback()));
				}
			})
			.bounds(columnLeft, contentTop + ROW * rows, columnWidth, 20)
			.tooltip(Tooltip.create(Component.literal("Stone slabs, with stone where the build "
				+ "carries power.")))
			.build());
	}

	/**
	 * A block as it is shown in its box: without {@code minecraft:}, which is what anyone types and
	 * what {@link LaneMaterials#normalise} puts back. Another mod's namespace stays, since without it
	 * the name would be read as the game's own. The config keeps the full id either way.
	 */
	private static String typable(String block) {
		return block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
	}

	private void paste() {
		remember();
		confirm.accept(mode);
	}

	private void exportAs(SchematicWriter.Format format) {
		remember();
		export.accept(mode, format);
	}

	/** Export says which way its menu goes: sideways shut, up once it has opened upward. */
	private String exportLabel() {
		return exportMenuOpen ? "Export ^" : "Export >";
	}

	private void setExportMenuOpen(boolean open) {
		exportMenuOpen = open;
		if (exportButton != null) {
			exportButton.setMessage(Component.literal(exportLabel()));
		}
	}

	/** What the export menu offers, top to bottom. */
	private List<String> exportItems() {
		return List.of("Save .litematic", "Save .schem");
	}

	private void runExportItem(int item) {
		switch (item) {
			case 0 -> exportAs(SchematicWriter.Format.LITEMATIC);
			case 1 -> exportAs(SchematicWriter.Format.SCHEM);
			default -> {
			}
		}
	}

	/**
	 * Writes the settings down, before a paste or an export plans off them.
	 *
	 * <p>The same for both, because an export is meant to be the build Paste would have made, and the
	 * plan reads its limits and its blocks off the config rather than off this screen.</p>
	 */
	private void remember() {
		MidicraftConfig.get().setCommandsPerTick(commandsPerTick);
		MidicraftConfig.get().setBuildLaneWidth(laneWidth);
		MidicraftConfig.get().setBuildLaneFloors(laneFloors);
		MidicraftConfig.get().setParityReseedDelay(reseedDelay);
		MidicraftConfig.get().setPaceTolerance(paceTolerance);
		MidicraftConfig.get().setPaceCatchUpTo(paceCatchUpTo);
		MidicraftConfig.get().setPasteStartTop(startTop);
		MidicraftConfig.get().setPasteMode(mode.name());
		// The builder's copies are written by the setters, so the plan made next uses these blocks.
		for (int slot = 0; slot < PALETTE.size(); slot++) {
			PALETTE.get(slot).write().accept(MidicraftConfig.get(), palette[slot]);
		}
		MidicraftConfig.save();
	}

	/**
	 * Asks for a forecast of the settings as they stand, unless one was already asked for.
	 *
	 * <p>The rate is deliberately not part of the key: it changes how long the paste takes to run
	 * and nothing at all about what gets built.</p>
	 */
	private void requestForecast() {
		// The reseed threshold is in the key because it changes what gets built: past it a lane
		// gives up its parity to a piston instead of padding across the silence, and a build that
		// swaps halves is a different length from one that waits. Left out, the screen answered
		// every position of that slider with the forecast it had already made for the first.
		// The pace tolerance likewise: it decides how much dust the lane behind lays.
		String key = mode.name() + " " + laneWidth + " " + laneFloors + " " + reseedDelay + " "
			+ paceTolerance + " " + paceCatchUpTo + " " + startTop + " " + colours;
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
		// Read here with the rest of what the forecast needs, on the render thread.
		com.midicraft.client.composer.ChordSkips.Rules thinning = MidicraftConfig.get().chordFitRules();
		// Read here with the origin, for the same reason: the level is the render thread's.
		int worldTop = minecraft.level == null ? Integer.MAX_VALUE : minecraft.level.getMaxY();
		int worldFloor = minecraft.level == null ? Integer.MIN_VALUE : minecraft.level.getMinY();
		// The threshold travels on the limits rather than being written to the builder's static:
		// this runs on the forecasting thread while a paste may be planning on the render thread,
		// and a setting one of them has to write down first is a setting the other can read
		// half-written.
		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(
			MidicraftConfig.get().maxBuildFloors(), laneWidth, laneFloors,
			startTop, reseedDelay, paceTolerance, paceCatchUpTo);
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
					SongBuilder.notesFor(planned, sequence, project, dedupeIdenticalNotes, thinning),
					planned, limits);
				// Off the blocks rather than off the height, which is a span and says nothing
				// about where the span sits. getMaxY is the highest cell that takes a block, not
				// the first that refuses one.
				int highest = Integer.MIN_VALUE;
				int lowest = Integer.MAX_VALUE;
				// The ground spans come off the blocks for the same reason the height does. The
				// plan's own spanX and spanZ are what the walk believes it covered, and on some
				// builds that is a block short of what it laid -- the starter and the button stand
				// outside the corridor the walk was measuring. Off the commands there is nothing
				// left to be short of.
				int eastmost = Integer.MIN_VALUE;
				int westmost = Integer.MAX_VALUE;
				int southmost = Integer.MIN_VALUE;
				int northmost = Integer.MAX_VALUE;
				for (String command : plan.commands()) {
					String[] parts = command.split(" ", 5);
					int x = Integer.parseInt(parts[1]);
					int y = Integer.parseInt(parts[2]);
					int z = Integer.parseInt(parts[3]);
					highest = Math.max(highest, y);
					lowest = Math.min(lowest, y);
					eastmost = Math.max(eastmost, x);
					westmost = Math.min(westmost, x);
					southmost = Math.max(southmost, z);
					northmost = Math.min(northmost, z);
				}
				// Longer side first. Which world axis each one is depends on which way you were
				// facing when the forecast was taken, so naming them would be naming something
				// that changes when you turn round; the pair of numbers does not.
				int acrossX = eastmost - westmost + 1;
				int acrossZ = southmost - northmost + 1;
				result = new Forecast(Math.max(acrossX, acrossZ), Math.min(acrossX, acrossZ),
					// The walk's own breaches and the plan's wall reading together: a lane the
					// walk did not know was outside is still outside. See SongBuilder.WallBreach.
					plan.breaches().size() + plan.wallBreaches().size(),
					Math.max(plan.worstBreach(), plan.worstWallBreach()), faultLines(plan),
					// In long, because the no-world sentinels are the int extremes and
					// MIN_VALUE minus a height wraps round to a large positive -- which would
					// warn that two billion levels are below the floor of a world that is not
					// there.
					(int) Math.max(0, (long) highest - worldTop),
					(int) Math.max(0, (long) worldFloor - lowest), null, plan.builtWidth());
			} catch (IllegalArgumentException refused) {
				result = new Forecast(0, 0, 0, 0, List.of(), 0, 0, refused.getMessage(), 0);
			} catch (RuntimeException | LinkageError broken) {
				// A forecast that throws must not take the paste down with it: the build itself may
				// well be fine, and a screen that cannot tell you the depth is still a screen you
				// can paste from.
				// LinkageError as well: a forecast that dies of an uncaught error leaves the screen
				// waiting for a result that never comes. Seen as a ClassNotFoundException for the
				// FaultLine record after a rebuild under a running dev client -- the class is first
				// loaded when a plan has something wrong, and the class directory had been rewritten
				// underneath the game -- and the preview sat on "assessing" for minutes.
				result = new Forecast(0, 0, 0, 0, List.of(), 0, 0, "could not work out the layout", 0);
			}
			if (forecastGeneration.get() == generation) {
				forecast = result;
			}
		});
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

		/** Drawn grey, for a setting shown here but not used here. */
		private boolean muted;

		void mute() {
			muted = true;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			// Called from the superclass constructor, before this class has its fields.
			if (say != null) {
				Component message = Component.literal(say.apply(rung()));
				setMessage(muted ? message.copy().withStyle(net.minecraft.ChatFormatting.GRAY)
					: message);
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
			? blocks + " blocks wide - raised to " + labelledWidth + " to fit largest chord"
			: blocks + " blocks wide before it folds back";
	}

	private static String startLine(boolean fromTop) {
		return fromTop ? "Starts at the top and works down" : "Starts at the bottom and climbs";
	}

	private static String blocksLine(MidicraftConfig.ColorCodedPaste colours) {
		return switch (colours) {
			case OFF -> "Normal - plain build";
			case LIGHT_SHOW -> "Light show - lamps along the song";
			case NORMAL -> "Color coded - marked for reading";
		};
	}

	private static String blocksTooltip(MidicraftConfig.ColorCodedPaste colours) {
		return switch (colours) {
			case OFF -> "Uses only the blocks in the palette below (default stone slabs and "
				+ "stone).";
			case LIGHT_SHOW -> "Uses the palette blocks + lamps along the song's path "
				+ "(mostly replaces relay blocks), so it lights up as it plays.";
			case NORMAL -> "For debugging purposes. Marks each chord type, padding type, etc. so it's "
				+ "easy to tell what the paster did.";
		};
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

	/** One line of the forecast card, and what to say if the pointer stops on it. */
	private record CardLine(String text, int color, List<String> detail) {
		CardLine(String text, int color) {
			this(text, color, List.of());
		}
	}

	/**
	 * What the current settings would build, as the card says it: the size first, then a short list
	 * of checks, each green when it holds and amber or red with what is wrong when it does not.
	 *
	 * <p>The faults are the ones the screen always showed -- every kind, whatever the build's colours
	 * are set to -- and keep their tooltips. Where the build would hang out of the world moved here
	 * from the notes under the buttons: it is a fact about this build, not about pasting.</p>
	 */
	private List<CardLine> cardLines(Forecast predicted) {
		List<CardLine> lines = new ArrayList<>();
		if (predicted == null) {
			lines.add(new CardLine("Measuring the build...", HINT_COLOR));
			return lines;
		}
		if (predicted.error() != null) {
			lines.add(new CardLine(predicted.error(), BAD_COLOR));
			return lines;
		}
		// Both ground sides and not the height: which side grows is the layout's business, and the
		// height falls out of the floor count, a setting on this screen already.
		lines.add(new CardLine(predicted.longSide() + " x " + predicted.shortSide() + " blocks",
			TEXT_COLOR));
		int commands = SongBuilder.blockCounts(sequence).total();
		// "If you stay with it" is the whole of the honesty here: a build longer than the loaded
		// region around you pauses at the edge and waits to be walked to, so the figure is a floor
		// and not an estimate. A direct paste has no rate to divide by -- it goes as fast as the
		// server can spare, which nothing here can know -- so it is not given a time it would miss.
		lines.add(new CardLine(direct
			? String.format(Locale.ROOT, "About %d blocks", commands)
			: String.format(Locale.ROOT, "About %d blocks, roughly %s if you stay with it",
				commands, howLong(commands / (commandsPerTick * 20.0))), HINT_COLOR));
		lines.add(new CardLine("", HINT_COLOR));
		// Problems only, each on its own line. A build with none says so once rather than ticking
		// off every check it passed: the list is worth reading when something is on it.
		List<CardLine> problems = new ArrayList<>();
		if (predicted.breachingLanes() > 0) {
			problems.add(new CardLine(predicted.breachingLanes()
				+ (predicted.breachingLanes() == 1 ? " lane breaches" : " lanes breach")
				+ " the footprint, worst " + predicted.worstBreach(), WARN_COLOR));
		}
		String outside = outsideTheWorld(predicted);
		if (outside != null) {
			problems.add(new CardLine(Character.toUpperCase(outside.charAt(0)) + outside.substring(1),
				WARN_COLOR));
		}
		// Red, but for a warning. Everything else here takes music away; a lane past the outer wall
		// takes nothing away, and is amber like a breach of the footprint.
		for (FaultLine fault : predicted.faults()) {
			problems.add(new CardLine(fault.text(), fault.warning() ? WARN_COLOR : BAD_COLOR,
				fault.detail()));
		}
		if (problems.isEmpty()) {
			lines.add(new CardLine("No issues found", GOOD_COLOR));
		}
		lines.addAll(problems);
		return lines;
	}

	/** The panel, drawn over the blurred world and under the buttons, as the other popups draw theirs. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int right = panelLeft + panelWidth;
		int bottom = panelTop + panelHeight;
		graphics.fill(panelLeft, panelTop, right, bottom, PANEL_FILL);
		graphics.fill(panelLeft, panelTop, right, panelTop + 1, PANEL_EDGE);
		graphics.fill(panelLeft, bottom - 1, right, bottom, PANEL_EDGE);
		graphics.fill(panelLeft, panelTop, panelLeft + 1, bottom, PANEL_EDGE);
		graphics.fill(right - 1, panelTop, right, bottom, PANEL_EDGE);
		if (twoColumns) {
			graphics.fill(cardLeft - 7, tabsTop, cardLeft - 6, cardBottom, PANEL_EDGE);
		}
		graphics.fill(columnLeft, notesTop - 5, right - PADDING, notesTop - 4, PANEL_EDGE);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int inner = panelWidth - 2 * PADDING;

		// Cut to the panel, since a song's name can be any length.
		String heading = "Build \"" + songName + "\"";
		if (font.width(heading) > inner) {
			heading = font.plainSubstrByWidth(heading, inner - font.width("...")) + "...";
		}
		graphics.text(font, heading, columnLeft, panelTop + PADDING, TEXT_COLOR, false);

		Tab hoveredTab = tabAt(mouseX, mouseY);
		int x = columnLeft;
		for (Tab each : Tab.values()) {
			int w = font.width(each.label) + 12;
			int color = each == tab ? HEADING_COLOR : each == hoveredTab ? TEXT_COLOR : HINT_COLOR;
			graphics.text(font, each.label, x + 6, tabsTop + 3, color, false);
			if (each == tab) {
				graphics.fill(x, tabsTop + TAB_HEIGHT - 1, x + w, tabsTop + TAB_HEIGHT, HEADING_COLOR);
			}
			x += w + 4;
		}
		for (int index = 0; index < paletteLabelAt.size(); index++) {
			int[] at = paletteLabelAt.get(index);
			PaletteSlot slot = PALETTE.get(index);
			graphics.text(font, slot.shortLabel(), at[0], at[1] + 6, LABEL_COLOR, false);
			// The label is one word, so it says the rest when pointed at, as the box beside it does.
			if (mouseX >= at[0] && mouseX < at[0] + font.width(slot.shortLabel()) + 4
					&& mouseY >= at[1] && mouseY < at[1] + 20) {
				graphics.setTooltipForNextFrame(font, font.split(slot.description(), 240),
					mouseX, mouseY);
			}
		}

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
		extractCard(graphics, predicted, mouseX, mouseY);

		// Over the buttons, about the paste rather than the build: what it needs and what it does.
		List<CardLine> notes = new ArrayList<>();
		notes.add(new CardLine("Paste builds it right beside you, replacing anything in the way. "
			+ "Export saves it as a file instead.", HINT_COLOR));
		if (!direct && commandsPerTick > PasteRate.SPAM_RISK_ABOVE) {
			notes.add(new CardLine("High rates can trip server command spam limits.", WARN_COLOR));
		}
		int y = notesTop;
		for (CardLine note : notes) {
			for (net.minecraft.util.FormattedCharSequence part
					: font.split(Component.literal(note.text()), inner)) {
				if (y + LINE > notesTop + NOTE_LINES * LINE) {
					break;
				}
				graphics.text(font, part, columnLeft, y, note.color(), false);
				y += LINE;
			}
		}

		if (exportMenuOpen && exportButton != null) {
			extractExportMenu(graphics, mouseX, mouseY);
		}
	}

	/** The forecast card: a heading, then the lines, wrapped to the card and cut at its foot. */
	private void extractCard(GuiGraphicsExtractor graphics, Forecast predicted, int mouseX,
			int mouseY) {
		graphics.text(font, "This build", cardLeft, cardTop + 3, HEADING_COLOR, false);
		cardFaultAt.clear();
		cardFaultDetail.clear();
		int y = cardTop + LINE + 6;
		for (CardLine line : cardLines(predicted)) {
			if (line.text().isEmpty()) {
				y += LINE / 2;
				continue;
			}
			for (net.minecraft.util.FormattedCharSequence part
					: font.split(Component.literal(line.text()), cardWidth)) {
				if (y + LINE > cardBottom) {
					return;
				}
				graphics.text(font, part, cardLeft, y, line.color(), false);
				int partWidth = font.width(part);
				// Hit-tested against the text rather than the row, so a tooltip only comes up over
				// something it explains.
				if (!line.detail().isEmpty() && mouseX >= cardLeft && mouseX < cardLeft + partWidth
						&& mouseY >= y - 1 && mouseY < y + font.lineHeight) {
					graphics.setTooltipForNextFrame(font,
						font.split(Component.literal(String.join("\n", line.detail())), 280),
						mouseX, mouseY);
				}
				y += LINE;
			}
		}
	}

	/** The export menu, opening upward from its button so it never runs off the bottom. */
	private void extractExportMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> items = exportItems();
		int menuWidth = exportButton.getWidth();
		for (String item : items) {
			menuWidth = Math.max(menuWidth, font.width(item) + 16);
		}
		int itemHeight = 14;
		int left = exportButton.getX();
		int top = buttonsTop - 2 - items.size() * itemHeight - 4;
		exportMenuAt = new int[] {left, top, menuWidth};
		int bottom = top + items.size() * itemHeight + 4;
		graphics.fill(left, top, left + menuWidth, bottom, PANEL_FILL);
		graphics.fill(left, top, left + menuWidth, top + 1, PANEL_EDGE);
		graphics.fill(left, bottom - 1, left + menuWidth, bottom, PANEL_EDGE);
		graphics.fill(left, top, left + 1, bottom, PANEL_EDGE);
		graphics.fill(left + menuWidth - 1, top, left + menuWidth, bottom, PANEL_EDGE);
		int hovered = exportItemAt(mouseX, mouseY);
		for (int index = 0; index < items.size(); index++) {
			int y = top + 2 + index * itemHeight;
			if (index == hovered) {
				graphics.fill(left + 1, y, left + menuWidth - 1, y + itemHeight, 0xFF2A313B);
			}
			graphics.text(font, items.get(index), left + 8, y + 3, TEXT_COLOR, false);
		}
	}

	/** The export menu item under the pointer, or -1. */
	private int exportItemAt(double mouseX, double mouseY) {
		if (!exportMenuOpen || exportMenuAt == null) {
			return -1;
		}
		int left = exportMenuAt[0];
		int top = exportMenuAt[1] + 2;
		if (mouseX < left || mouseX >= left + exportMenuAt[2] || mouseY < top) {
			return -1;
		}
		int item = (int) ((mouseY - top) / 14);
		return item < exportItems().size() ? item : -1;
	}

	/** The tab under the pointer, or {@code null}. */
	private Tab tabAt(double mouseX, double mouseY) {
		if (mouseY < tabsTop || mouseY >= tabsTop + TAB_HEIGHT) {
			return null;
		}
		int x = columnLeft;
		for (Tab each : Tab.values()) {
			int w = font.width(each.label) + 12;
			if (mouseX >= x && mouseX < x + w) {
				return each;
			}
			x += w + 4;
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (exportMenuOpen) {
			int item = exportItemAt(event.x(), event.y());
			setExportMenuOpen(false);
			if (item >= 0) {
				runExportItem(item);
				return true;
			}
			// A second press on Export closes it, and so does a click anywhere else -- spent on the
			// closing, the way a menu is, rather than also landing on whatever was under it.
			return true;
		}
		Tab clicked = tabAt(event.x(), event.y());
		if (clicked != null) {
			if (clicked != tab) {
				tab = clicked;
				init();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * Escape shuts an open export menu before it shuts the screen, and Enter pastes -- only while
	 * nothing is focused, as in the other popups, so Enter in a palette box is not a paste.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (exportMenuOpen && event.key() == InputConstants.KEY_ESCAPE) {
			setExportMenuOpen(false);
			return true;
		}
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
				&& getFocused() == null) {
			paste();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		// Left without pasting, so the block choice made here goes back to what it was: it took
		// effect when it was picked, for the forecast's sake, not because it was meant.
		MidicraftConfig.get().setColorCodedPaste(coloursOnOpen);
		McCompat.setScreen(parent);
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
