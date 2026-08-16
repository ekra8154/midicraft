package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.NoteSequence.Step;
import com.fastnoteblocks.NoteSequence.StepType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;

/**
 * Turns a composition into the {@code /setblock} commands that build it in the world.
 *
 * <p>Lifted out of the Mod Menu sequence entry, which was a settings widget that happened to
 * contain the build. Nothing here reads text: the events come from
 * {@link ComposerProject#toSteps} directly, so what gets placed is decided by the composition and
 * by nothing else.</p>
 */
public final class SongBuilder {
	private SongBuilder() {
	}

	/**
	 * The composition flattened into the events a build places, in the order it places them.
	 *
	 * <p>Layers left out of the sequence are skipped. Mute is not consulted: it decides what you
	 * hear, and the dot decides what gets built.</p>
	 */
	static List<EventNote> eventNotes(ComposerProject project) {
		List<EventNote> notes = new ArrayList<>();
		for (int layerIndex = 0; layerIndex < project.layers().size(); layerIndex++) {
			Layer layer = project.layers().get(layerIndex);
			if (!layer.buildEnabled()) {
				continue;
			}
			String instrumentBlock = instrumentBlockId(layer.instrument());
			PreviewInstrument.Effect effect = effectOf(layer.instrument());
			int time = 0;
			int order = 0;
			for (Step step : project.toSteps(layer)) {
				if (step.type() == StepType.NOTE) {
					notes.add(new EventNote(time, layerIndex + 1, order++, step.value(), instrumentBlock, effect));
				} else {
					time += step.value();
				}
			}
		}
		notes.sort(Comparator.comparingInt(EventNote::time)
			.thenComparingInt(EventNote::trackNumber)
			.thenComparingInt(EventNote::order));
		return List.copyOf(notes);
	}

	/**
	 * The build sequence flattened into events.
	 *
	 * <p>The sequence is what actually gets placed, whether by hand in survival or by command, so
	 * pasting reads this rather than the composition. Planning from the composition instead would
	 * mean the two disagreed the moment you moved a subset of layers across.</p>
	 */
	static List<EventNote> eventNotes(List<FastNoteblocksConfig.SequenceTrack> tracks) {
		List<EventNote> notes = new ArrayList<>();
		for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
			FastNoteblocksConfig.SequenceTrack track = tracks.get(trackIndex);
			String instrumentBlock = instrumentBlockId(track.instrument());
			PreviewInstrument.Effect effect = effectOf(track.instrument());
			int time = 0;
			int order = 0;
			List<Step> steps;
			try {
				steps = com.fastnoteblocks.NoteSequence.parse(track.sequence());
			} catch (IllegalArgumentException unparseable) {
				continue;
			}
			for (Step step : steps) {
				if (step.type() == StepType.NOTE) {
					notes.add(new EventNote(time, trackIndex + 1, order++, step.value(), instrumentBlock, effect));
				} else {
					time += step.value();
				}
			}
		}
		notes.sort(Comparator.comparingInt(EventNote::time)
			.thenComparingInt(EventNote::trackNumber)
			.thenComparingInt(EventNote::order));
		return List.copyOf(notes);
	}

	static BlockCounts blockCounts(List<FastNoteblocksConfig.SequenceTrack> tracks) {
		return countBlocks(eventNotes(tracks));
	}

	private static BlockCounts countBlocks(List<EventNote> notes) {
		int noteBlocks = notes.size();
		int repeaters = 0;
		int previous = 0;
		for (EventNote note : notes) {
			if (note.time() != previous) {
				repeaters += Math.max(1, (note.time() - previous + 3) / 4);
				previous = note.time();
			}
		}
		return new BlockCounts(noteBlocks, repeaters);
	}

	record BlockCounts(int noteBlocks, int repeaters) {
		int total() {
			return noteBlocks + repeaters;
		}
	}

	/**
	 * Plans the build, or explains why it cannot be built.
	 *
	 * @throws IllegalArgumentException with a message meant to be shown to the player
	 */
	static PastePlan plan(Minecraft minecraft, List<FastNoteblocksConfig.SequenceTrack> tracks,
			PasteMode mode) {
		return plan(minecraft, tracks, mode, null);
	}

	/**
	 * @param title the composition's name, for the sign at the block the machine starts from. A
	 *     build is thirty thousand blocks of stone that looks exactly like every other build, and a
	 *     world with four of them standing in it has no way at all of saying which is which.
	 */
	static PastePlan plan(Minecraft minecraft, List<FastNoteblocksConfig.SequenceTrack> tracks,
			PasteMode mode, String title) {
		return createPastePlan(minecraft, eventNotes(tracks), mode, title);
	}

	static final int MAX_SIMULTANEOUS_NOTES = 30;

	/**
	 * The widest two lane centres ever have to be apart: a chord of three or more reaches a block
	 * to either side of its lane, and one empty column has to separate the two.
	 *
	 * <p>Also the spacing every estimate assumes. Where a lane ends is settled on the travel axis
	 * alone, so it never depends on how wide the lanes turned out -- which is what lets a lane be
	 * measured before it is walked.</p>
	 */
	private static final int MAX_LANE_SPACING = 4;

	/** The tightest two lane centres are ever placed: one note wide each, one empty column between. */
	private static final int MIN_LANE_SPACING = 2;

	/**
	 * Whether a chord can be built into a corner instead of into the lane.
	 *
	 * <p>Judged against the tightest corner rather than the one this pair of lanes will actually
	 * get, because the answer decides where the next lane starts and so has to be settled before
	 * the corner has been measured.</p>
	 */
	private static boolean fitsInCorner(EventGroup event) {
		return event.notes().size() <= 2 * MIN_LANE_SPACING;
	}

	/**
	 * How far a lane's modules reach either side of its centre line, in blocks and in power.
	 *
	 * <p>Measured against the lane step -- the direction the serpentine walks -- and not against
	 * travel, which reverses every lane. That is the whole trick: because a two-note chord always
	 * puts its second note the same way round, two neighbouring lanes can never both grow into the
	 * gap between them, so a sparse pair can sit three apart instead of four.</p>
	 *
	 * <p>Blocks may not overlap, which is the easy half. The other half is that nothing live may
	 * come within one of a <em>foreign</em> note block. A note block touching a note block is
	 * harmless -- block power never crosses from one block to the next -- and that is why an empty
	 * column between two lanes is usually paying for nothing.</p>
	 *
	 * <p>Which leaves one case that does need it. Every layout but the stacked module keeps what it
	 * powers on its own centre line, or a level above where the neighbour has only instrument
	 * blocks. The stacked module is the one that hangs notes and live relays side by side on the
	 * <em>same</em> low level, so two lanes that both use it, and only those, still need the gap.
	 *
	 * @param margin an empty column this lane insists on regardless, which is how the older modes
	 *     keep the spacing every build made with them already has
	 * @param lowLive whether this lane has live blocks out at the level a neighbour hangs low
	 *     notes at
	 */
	private record LaneReach(int back, int forward, int margin, boolean lowLive) {
		static final LaneReach NONE = new LaneReach(0, 0, 0, false);

		LaneReach widest(LaneReach other) {
			return new LaneReach(Math.max(back, other.back), Math.max(forward, other.forward),
				Math.max(margin, other.margin), lowLive || other.lowLive);
		}

		int width() {
			return back + forward + 1;
		}
	}

	/**
	 * How far a chord too small to need a bus reaches either side of its lane.
	 *
	 * <p>A chord of one normally sits on the centre line and reaches nothing. The exception is one
	 * that had to be lent a middle: its sound is off to the back side, so the lane owes it that
	 * column even though there is only one sound in it.</p>
	 */
	private static LaneReach smallChordReach(int chordSize, boolean lentMiddle, int margin) {
		if (chordSize <= 1) {
			return new LaneReach(lentMiddle ? 1 : 0, 0, margin, false);
		}
		return chordSize <= 2 ? new LaneReach(0, 1, margin, false)
			: new LaneReach(1, 1, margin, false);
	}

	/** The reach of the widest chord in a run of events. */
	private static LaneReach laneReach(List<EventGroup> events, int from, int to) {
		LaneReach reach = LaneReach.NONE;
		for (int index = from; index < to && index < events.size(); index++) {
			reach = reach.widest(events.get(index).reach());
		}
		return reach;
	}

	/**
	 * Lane centres far enough apart that neither lane can reach into the other.
	 *
	 * <p>Four conditions. The blocks must not overlap, plus whatever empty column the older modes
	 * insist on. Neither lane's notes may come within one of the other's <em>centre line</em>,
	 * which is live in every layout there is -- an anchor note block, a bus, the block a cross
	 * hangs under. And two lanes that both hang notes out at the low level, which only the stacked
	 * module does, need a column between them as well.</p>
	 */
	private static int laneSpacing(LaneReach lane, LaneReach next) {
		int clear = Math.max(lane.forward() + next.back() + 1
				+ Math.max(lane.margin(), next.margin()),
			Math.max(lane.forward() + 2, next.back() + 2));
		if (lane.lowLive() && next.lowLive()) {
			clear = Math.max(clear, 4);
		}
		return Math.max(2, clear);
	}

	/**
	 * Spacing for each lane boundary, keyed by the event index the next lane starts at.
	 *
	 * @param starts the event index each lane begins at, in order, starting with 0
	 */
	private static Map<Integer, Integer> laneSpacings(List<EventGroup> events, List<Integer> starts) {
		Map<Integer, Integer> spacings = new LinkedHashMap<>();
		for (int lane = 1; lane < starts.size(); lane++) {
			LaneReach before = laneReach(events, starts.get(lane - 1), starts.get(lane));
			LaneReach after = laneReach(events, starts.get(lane),
				lane + 1 < starts.size() ? starts.get(lane + 1) : events.size());
			spacings.put(starts.get(lane), laneSpacing(before, after));
		}
		return spacings;
	}

	/**
	 * Vertical period of a cube floor: a module occupies its floor level, the note-block level
	 * above it, the air gap above that, and one level below for a falling instrument block's
	 * support. Four is the tightest spacing that never lets two floors touch.
	 */
	private static final int CUBE_FLOOR_HEIGHT = 4;

	private static PastePlan createPastePlan(Minecraft minecraft, List<EventNote> notes,
			PasteMode mode, String title) {
		// Fixed world axes rather than the player's facing: travel runs +X and lanes step +Z, so a
		// build always grows into positive coordinates and you know where it will land before you
		// commit to it. Alternating floors double back inside that volume, never past the origin.
		return createPastePlan(pasteOrigin(minecraft, Direction.EAST), notes, mode,
			BuildLimits.fromConfig(), WalkStart.HEAD, title);
	}

	/**
	 * How far a folding mode may grow before it has to double back.
	 *
	 * <p>Passed in rather than read from the config where the plan is made, so a build can be
	 * planned without a game around it. The saved settings are still what a real build uses; this
	 * only means the numbers arrive as arguments instead of being fetched, which is what lets every
	 * mode -- not just the two that happen not to consult the config -- be planned and read back in
	 * a test.</p>
	 */
	/**
	 * Where a wall walk begins, which is at the head of the song unless something says otherwise.
	 *
	 * <p>Exists for the debug command, and for one reason: the shape of the wall a chord meets is not
	 * something a song can ask for. It falls out of how many floors there are and how far the walk has
	 * already climbed -- a build starts on floor nought going up, so the first wall is a climb, and a
	 * descent only happens once the floors run out and the direction flips. Reproducing a fault that
	 * happens on a descent therefore meant building every lane in front of it first.</p>
	 *
	 * <p>Seeding those three numbers instead says "start as though you had". Nothing in a real paste
	 * passes anything but {@link #HEAD}, and with {@link #HEAD} the walk is the walk it always was.</p>
	 *
	 * @param column how far along its first lane the walk starts. The walls are still measured from
	 *     the origin, so this is what puts a chord a stated distance from one.
	 * @param climb which way the next wall goes, +1 or -1
	 */
	record WalkStart(int column, int floor, int climb, boolean turning) {
		static final WalkStart HEAD = new WalkStart(0, 0, 1, false);

		WalkStart(int column, int floor, int climb) {
			this(column, floor, climb, false);
		}
	}

	/**
	 * @param startTop whether the serpentine starts on the top floor and works down, rather than on
	 *     the bottom floor and up. The same snake either way -- it is where it is put down and which
	 *     way the first wall goes, nothing else -- but the two are not mirror images: the first turn
	 *     of a top start is a descent, and a descent is the dearer turn, so a song can come out
	 *     better one way round than the other. Both start their first lane at the origin, so a
	 *     top start grows downward from where you stand and wants clear ground below rather
	 *     than above. At one floor the two are the same build: every turn is flat already.
	 */
	record BuildLimits(int maxFloors, int laneWidth, int laneFloors, boolean startTop) {
		BuildLimits(int maxFloors, int laneWidth, int laneFloors) {
			this(maxFloors, laneWidth, laneFloors, false);
		}

		static BuildLimits fromConfig() {
			FastNoteblocksConfig config = FastNoteblocksConfig.get();
			return new BuildLimits(config.maxBuildFloors(), config.buildLaneWidth(),
				config.buildLaneFloors(), config.ultraLaneStartTop());
		}
	}

	/**
	 * Plans a build at a stated origin rather than at the player's feet.
	 *
	 * <p>Separated out so the plan can be made without a world to stand in, which is what lets a
	 * build be planned and then read back in a test.</p>
	 */
	static PastePlan createPastePlan(BlockPos origin, List<EventNote> notes, PasteMode mode) {
		return createPastePlan(origin, notes, mode, BuildLimits.fromConfig());
	}

	static PastePlan createPastePlan(BlockPos origin, List<EventNote> notes, PasteMode mode,
			BuildLimits limits) {
		return createPastePlan(origin, notes, mode, limits, WalkStart.HEAD);
	}

	static PastePlan createPastePlan(BlockPos origin, List<EventNote> notes, PasteMode mode,
			BuildLimits limits, WalkStart start) {
		return createPastePlan(origin, notes, mode, limits, start, null);
	}

	static PastePlan createPastePlan(BlockPos origin, List<EventNote> notes, PasteMode mode,
			BuildLimits limits, WalkStart start, String title) {
		if (notes.isEmpty()) {
			throw new IllegalArgumentException(
				"The build sequence is empty. Move some layers into it first.");
		}
		ChordStats stats = chordStats(notes);
		if (stats.peak() > MAX_SIMULTANEOUS_NOTES) {
			throw new IllegalArgumentException(overloadMessage(stats));
		}
		Direction forward = Direction.EAST;
		// The sign last of all, after the marking pass has finished rewriting blocks. It is the one
		// command in a build that is not a block of the machine, and the one command with spaces in
		// it, which every pass that reads the list back by splitting on those would choke on.
		return withTitleSign(withUnreachedMarked(switch (mode) {
			case COMPACT_CUBE -> createCubePastePlan(origin, forward, notes, limits.maxFloors());
			case COMPACT -> createCompactPastePlan(origin, forward, notes);
			case COMPACT_LANE -> createLanePastePlan(origin, forward, notes, limits.laneWidth(),
				limits.laneFloors(), Layout.STANDARD, PasteMode.COMPACT_LANE, start);
			case ULTRA_COMPACT_LANE -> bestUltraPlan(origin, forward, notes, limits, start);
			case ULTRA_COMPACT_LANE_V2 -> UltraLaneV2.plan(origin, forward, notes, limits, start);
			case LANE -> createStraightPastePlan(origin, forward, notes);
		}), title, mode, limits);
	}

	/**
	 * Whether a build is read back before it is handed over, so that note blocks the signal never
	 * reaches are reported as a fault -- and, on a marked paste, shown in the blocks.
	 *
	 * <p>A note that never fires is as much a wrong note as one that fires twice, and it is the worse
	 * of the two to find: nothing else here can see it. {@code verify} asks whether every note block
	 * has something beside it that ought to set it off, and a machine whose wire dies upstream
	 * satisfies that completely -- the notes downstream still have their triggers, those triggers
	 * just never fire. So the only way to know is to read the blocks back, and the only place that
	 * can be done is after they are laid.</p>
	 *
	 * <p>The count always goes to chat. The marking is the dead <em>wire</em> and not the dead notes:
	 * a run that stops halfway silences everything after it, and every one of those notes is
	 * blameless. What is worth standing in front of is the cell the signal got to and no further, so
	 * a marked paste turns the stone of the dead run red and leaves the notes alone -- they still
	 * play their own instrument, they simply never hear anything.</p>
	 */
	static boolean MARK_UNREACHED = true;

	/** The same plan with the wire the signal never reaches turned red, on a marked paste. */
	private static PastePlan withUnreachedMarked(PastePlan plan) {
		if (!MARK_UNREACHED) {
			return plan;
		}
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
			new java.util.HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			try {
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
						Integer.parseInt(parts[3])),
					net.minecraft.commands.arguments.blocks.BlockStateParser
						.parseForBlock(BuiltInRegistries.BLOCK, parts[4], false)
						.blockState());
			} catch (RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException broken) {
				return plan;
			}
		}
		if (world.isEmpty()) {
			return plan;
		}
		BlockPos low = new BlockPos(
			world.keySet().stream().mapToInt(BlockPos::getX).min().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0));
		BlockPos high = new BlockPos(
			world.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0),
			world.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0));
		// Asked of the blocks before the reader is asked anything, because this is the one fault the
		// reader cannot see. A repeater reads the single cell behind it, so one with air there can
		// never fire and everything downstream of it is silent -- but startingPoints treats exactly
		// that shape as another way into the machine, starts a fresh performance at it, and counts
		// all of it as reached. Over the library that hid 182 severed lanes, 178 of them on one song
		// that every number called clean. One starved repeater is expected: it is where the player
		// throws the lever.
		List<BlockPos> starved = new ArrayList<>();
		for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> cell
				: world.entrySet()) {
			if (cell.getValue().is(net.minecraft.world.level.block.Blocks.REPEATER)
					&& !world.containsKey(cell.getKey().relative(cell.getValue()
						.getValue(net.minecraft.world.level.block.RepeaterBlock.FACING)))) {
				starved.add(cell.getKey());
			}
		}
		List<String> severed = new ArrayList<>();
		if (starved.size() > 1) {
			starved.sort(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY));
			BlockPos cut = starved.get(1);
			severed.add((starved.size() - 1) + " repeaters have nothing behind them to read, so the "
				+ "lane is cut there and everything after it is silent. The first is at "
				+ cut.getX() + " " + cut.getY() + " " + cut.getZ());
		}
		NoteMachineReader.Reading reading;
		try {
			reading = NoteMachineReader.read("unreached", low, high,
				position -> world.getOrDefault(position,
					net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
		} catch (RuntimeException unreadable) {
			return severed.isEmpty() ? plan : withFaults(plan, severed);
		}
		if (reading.unreachedNotes() == 0) {
			return severed.isEmpty() ? plan : withFaults(plan, severed);
		}
		// The disagreement, and only the disagreement. A cell counts as dead wire when the walk meant
		// it to carry the signal and the reader never got to it -- so the instrument blocks, the
		// staircase fill and everything else that was never meant to be live stay out of it. Without
		// that half the mark is every stone in the build, which says nothing.
		Set<BlockPos> deadWire = plan.poweredAt().stream()
			.filter(at -> !reading.reachedAt().contains(at))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		List<String> commands = plan.commands();
		if (DEBUG_PASTE && !deadWire.isEmpty()) {
			List<String> marked = new ArrayList<>(commands.size());
			for (String command : commands) {
				String[] parts = command.split(" ");
				BlockPos at = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3]));
				// Last word on the cell, over any colour the shape gave it: what a dead run was going
				// to be is beside the point once it is dead. Stone only -- the dust and the repeaters
				// standing on it have to stay themselves or the run stops being readable as a run.
				marked.add(deadWire.contains(at) && STONE_COLOURS.contains(parts[4])
					? "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
						+ " minecraft:red_nether_bricks replace"
					: command);
			}
			commands = marked;
		}
		BlockPos first = reading.unreachedAt().getFirst();
		List<String> faults = new ArrayList<>(plan.faults());
		faults.addAll(severed);
		faults.add(reading.unreachedNotes() + " note blocks would never be triggered -- the signal "
			+ "does not reach them, so that much of the song is silent. The first is at "
			+ first.getX() + " " + first.getY() + " " + first.getZ()
			+ (DEBUG_PASTE && !deadWire.isEmpty()
				? "; the " + deadWire.size() + " cells of wire that never go live are built in red "
					+ "nether brick"
				: ""));
		return new PastePlan(List.copyOf(commands), plan.width(), plan.depth(), plan.height(),
			plan.spanX(), plan.spanZ(), plan.mode(), List.copyOf(faults), plan.turns(), plan.moved(),
			plan.breaches(), plan.recesses(), plan.padding(), plan.nearWall(), plan.farWall(),
			plan.collisions(), plan.poweredAt(), plan.laidBy(), plan.noteTicks());
	}

	/** The same plan with more wrong with it than it knew. */
	private static PastePlan withFaults(PastePlan plan, List<String> extra) {
		List<String> faults = new ArrayList<>(plan.faults());
		faults.addAll(extra);
		return new PastePlan(plan.commands(), plan.width(), plan.depth(), plan.height(),
			plan.spanX(), plan.spanZ(), plan.mode(), List.copyOf(faults), plan.turns(), plan.moved(),
			plan.breaches(), plan.recesses(), plan.padding(), plan.nearWall(), plan.farWall(),
			plan.collisions(), plan.poweredAt(), plan.laidBy(), plan.noteTicks());
	}

	/** What a build with no name to put on it is called. */
	static final String UNNAMED = "Unnamed composition";

	/** Characters that fit on one line of a sign before the font runs out of room. */
	static final int SIGN_LINE = 15;

	/** Lines on the front of a sign. */
	static final int SIGN_LINES = 4;

	/**
	 * The composition's name, on a sign at the block the machine starts from.
	 *
	 * <p>Every build is thirty thousand blocks of grey that looks exactly like every other build, and
	 * four of them standing in one world are four identical corridors. The name goes where the song
	 * starts, on the side of the block the first repeater stands on.</p>
	 *
	 * <p>Facing negative Z where it can, which is the outward face of the first lane and the one you
	 * walk up to. It is free in fifty-one of the sixty-one songs in the library; in the other ten the
	 * first chord has hung a note there, because a chord fills both sides of its lane and on the
	 * first lane one of those sides is the edge of the build. Then the sign simply steps one further
	 * out and hangs off the note block instead, which keeps it on the same face of the build and
	 * reading the same way. Behind the head is the last resort, and is empty in every song there is:
	 * nothing is ever built there, because that is where the player stands to start the machine.</p>
	 *
	 * <p>Placed as soon as the block it hangs on exists, and not at the end. A build is tens of
	 * thousands of commands and a wide one has to be flown along as it goes up, so a sign written
	 * last is a sign written when the start of the song is long out of simulation range -- which is
	 * to say, never seen. It is still the only command in a build with spaces in it, so anything that
	 * reads the list back by splitting on those has to run before this does.</p>
	 */
	private static PastePlan withTitleSign(PastePlan plan, String title, PasteMode mode,
			BuildLimits limits) {
		BlockPos head = null;
		for (String command : plan.commands()) {
			// The first repeater the walk laid, which is the one the player throws a switch at: blocks
			// come out in the order the signal travels them, so nothing feeds this one.
			String[] parts = command.split(" ", 5);
			if (parts[4].startsWith("minecraft:repeater")) {
				head = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])).below();
				break;
			}
		}
		if (head == null) {
			return plan;
		}
		// Never over the machine, wherever it lands. A sign that quietly ate a note block would be a
		// silent note with no cause anybody could ever find. Asked of three cells rather than by
		// collecting every position first: this runs on plans that are only being measured -- the
		// build screen forecasts a dozen of them a keystroke -- and a set of forty thousand positions
		// to answer three questions is a set nobody needs.
		BlockPos stone = head;
		BlockPos at = Stream.of(stone.north(), stone.north().north(), stone.west())
			.filter(cell -> !holds(plan, cell)).findFirst().orElse(null);
		if (at == null) {
			return plan;
		}
		// Whichever block it ended up beside: the head stone, the note hanging off it, or the head
		// stone from behind.
		Direction face = at.equals(stone.west()) ? Direction.WEST : Direction.NORTH;
		BlockPos anchor = at.relative(face.getOpposite());
		String sign = "setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
			+ " minecraft:oak_wall_sign[facing=" + directionName(face)
			+ "]{front_text:{messages:[" + String.join(",", signLines(titled(title, mode, limits)))
			+ "]}} replace";
		List<String> commands = new ArrayList<>(plan.commands());
		// Straight after the block it hangs on, which is the earliest it can go: a sign whose support
		// is not there yet pops off the first time anything updates it.
		int after = 0;
		String prefix = "setblock " + anchor.getX() + " " + anchor.getY() + " " + anchor.getZ() + " ";
		while (after < commands.size() && !commands.get(after).startsWith(prefix)) {
			after++;
		}
		commands.add(Math.min(after + 1, commands.size()), sign);
		return new PastePlan(List.copyOf(commands), plan.width(), plan.depth(), plan.height(),
			plan.spanX(), plan.spanZ(), plan.mode(), plan.faults(), plan.turns(), plan.moved(),
			plan.breaches(), plan.recesses(), plan.padding(), plan.nearWall(), plan.farWall(),
			plan.collisions(), plan.poweredAt(), plan.laidBy(), plan.noteTicks());
	}

	/**
	 * The name with the size it was built at in front of it, as {@code 40|3}.
	 *
	 * <p>Which is the first thing anybody asks of a build standing in a world and the one thing that
	 * cannot be recovered by looking at it: a corridor's width is not its lane width -- the walls sit
	 * inside it -- and the floor count is only countable by flying up. Two songs built at different
	 * sizes are otherwise the same grey corridor with the same name on it.</p>
	 *
	 * <p>Only where those two numbers mean anything, which is the two lane modes. A cube is sized
	 * from the song rather than asked for.</p>
	 */
	private static String titled(String title, PasteMode mode, BuildLimits limits) {
		String name = title == null || title.isBlank() ? UNNAMED : title.trim();
		return mode == PasteMode.COMPACT_LANE || mode == PasteMode.ULTRA_COMPACT_LANE
			? limits.laneWidth() + "|" + limits.laneFloors() + " " + name
			: name;
	}

	/** Whether the build puts anything at all in a cell, air included. */
	private static boolean holds(PastePlan plan, BlockPos at) {
		String prefix = "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " ";
		return plan.commands().stream().anyMatch(command -> command.startsWith(prefix));
	}

	/**
	 * A title laid out over the four lines of a sign, each one quoted ready for the command.
	 *
	 * <p>Words stay whole while they can, because a title read in two pieces is harder than a title
	 * one word short. When one cannot -- a word longer than a line, or a last line that would drop
	 * something -- it is split with a hyphen, which reads as a continuation rather than as a
	 * mistake. Only the very end is given up on, and with an ellipsis so that it says so.</p>
	 */
	static List<String> signLines(String title) {
		String name = title == null || title.isBlank() ? UNNAMED : title.trim();
		List<String> lines = new ArrayList<>(SIGN_LINES);
		StringBuilder line = new StringBuilder();
		for (String word : name.split("\\s+")) {
			while (!word.isEmpty() && lines.size() < SIGN_LINES) {
				int room = SIGN_LINE - line.length() - (line.isEmpty() ? 0 : 1);
				if (word.length() <= room) {
					line.append(line.isEmpty() ? "" : " ").append(word);
					word = "";
					break;
				}
				// A hyphen costs one of the characters it is trying to save, so it is only worth it
				// where enough of the word is left to be worth reading -- otherwise the word simply
				// starts on the next line.
				if (room >= 3 && word.length() > SIGN_LINE) {
					line.append(line.isEmpty() ? "" : " ").append(word, 0, room - 1).append('-');
					word = word.substring(room - 1);
				}
				lines.add(line.toString());
				line.setLength(0);
			}
			if (lines.size() >= SIGN_LINES) {
				break;
			}
		}
		if (!line.isEmpty() && lines.size() < SIGN_LINES) {
			lines.add(line.toString());
		}
		// What did not fit. An ellipsis rather than a clean edge, because a title that stops early
		// looks like the name of a different song.
		if (lines.size() == SIGN_LINES && trimmed(name, lines)) {
			String last = lines.get(SIGN_LINES - 1);
			lines.set(SIGN_LINES - 1, (last.length() > SIGN_LINE - 3
				? last.substring(0, SIGN_LINE - 3) : last).stripTrailing() + "...");
		}
		while (lines.size() < SIGN_LINES) {
			lines.add("");
		}
		return lines.stream().map(SongBuilder::quoted).toList();
	}

	/**
	 * Whether the lines hold fewer letters of the name than the name has.
	 *
	 * <p>Counted without spaces on both sides and without the hyphens the wrapping added, because
	 * neither is part of the name. Counting them was the whole of a bug: four full lines came out
	 * longer than the name they were a shortening of, and every over-long title said it fitted.</p>
	 */
	private static boolean trimmed(String name, List<String> lines) {
		int written = lines.stream()
			.mapToInt(line -> line.replace("-", "").replaceAll("\\s+", "").length()).sum();
		return written < name.replaceAll("\\s+", "").length();
	}

	/**
	 * One line as the command wants it: a quoted string, with the two characters that would end it
	 * early put out of the way.
	 */
	private static String quoted(String line) {
		return '"' + line.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	/**
	 * Every block a cell of lane can be built from, so that a later pass can recognise one.
	 *
	 * <p>The dead-wire mark runs after the colouring and has to overwrite whatever the colouring
	 * decided, which means asking "is this a piece of lane" of a string that is no longer
	 * {@code minecraft:stone}. Kept beside {@link #shapeStone} because the two have to list the same
	 * blocks, and a colour added to one and not the other is a run that marks red in patches.</p>
	 */
	/**
	 * How far outside its walls a build stands without anything being wrong.
	 *
	 * <p>Two, and it is not slack -- {@code finish} says why: the walk reaches outside its own walls
	 * routinely, for a chord hanging off the far side of the first lane, for a corner overshooting
	 * the end of one, for a floor not quite the width of the one below. The wall is where a lane
	 * <em>turns</em>, and turning takes columns of its own.</p>
	 *
	 * <p>Measured rather than reasoned: twelve builds across four songs and four widths that record
	 * no breach at all reach exactly this far past the wall and no further, and Guardian's near-side
	 * breach of one at 24 wide over six floors is the first block past it. Marking from the wall
	 * itself painted 1,231 blocks for three breaching lanes, which is a colour that means nothing.
	 * If a layout change moves this, {@link com.fastnoteblocks.client.compat.DebugPasteMarkTest}
	 * fails rather than the mark quietly lying.</p>
	 */
	private static final int WALL_OVERSHOOT = 2;

	private static final Set<String> STONE_COLOURS = Set.of("minecraft:stone", "minecraft:tuff",
		"minecraft:andesite", "minecraft:deepslate", "minecraft:cobbled_deepslate",
		"minecraft:deepslate_tiles", "minecraft:smooth_basalt",
		"minecraft:stripped_crimson_hyphae[axis=x]");

	/**
	 * The ultra build made both ways, keeping whichever of them came out better.
	 *
	 * <p>The lookahead cannot be judged where it fires. Big Shot at three floors and twelve wide was
	 * built both ways and the two walks part company at lane 541, then breach at 551 -- five lanes
	 * and four clean turns later. {@link #strandsNext} was not wrong about the lane it looked at. It
	 * closed a lane differently, and every lane after it landed somewhere else. No check made where
	 * the veto fires can see that, because the damage is not there.</p>
	 *
	 * <p>So it is not asked to be right. It is asked to win, against the same song built without it,
	 * on the whole build -- which is the only place the question has an answer. A walk costs about
	 * fifteen milliseconds, and this buys a guarantee for two of them: the lookahead can no longer
	 * cost a build anything, because a build it makes worse is a build that gets thrown away.</p>
	 */
	static PastePlan bestUltraPlan(BlockPos origin, Direction forward,
			List<EventNote> notes, BuildLimits limits, WalkStart start) {
		Layout plain = Layout.ultra(limits.laneFloors(), origin);
		// Where the snake is put down. Only when nobody has asked for somewhere in particular: a
		// seeded walk is a debug build reproducing a fault at a stated floor, and it means it.
		// The lookahead pair is built inside this rather than beside it, because plain against
		// lookahead is the same snake either way -- the start decides which snake they both are.
		WalkStart head = start == WalkStart.HEAD && limits.startTop()
			? new WalkStart(0, limits.laneFloors() - 1, -1)
			: start;
		// Two whole builds go past the trace, one after the other, and for a long time nothing said
		// which was which. Every coordinate read out of that file was a coin flip: the two disagree
		// about which floor a lane sits on and about where the finished build slides to, so a chord
		// looked up by position could be found in the plan that lost. Two chords were misidentified
		// that way in one afternoon before anybody suspected the trace rather than the build.
		if (TRACE || TRACE_TURNS) {
			System.out.println("PLANRUN lookahead=no");
		}
		PastePlan without = createLanePastePlan(origin, forward, notes, limits.laneWidth(),
			limits.laneFloors(), plain, PasteMode.ULTRA_COMPACT_LANE, head);
		if (TRACE || TRACE_TURNS) {
			System.out.println("PLANRUN lookahead=yes");
		}
		PastePlan with = createLanePastePlan(origin, forward, notes, limits.laneWidth(),
			limits.laneFloors(), plain.withLookahead(), PasteMode.ULTRA_COMPACT_LANE, head);
		boolean won = beats(with, without);
		if (TRACE || TRACE_TURNS) {
			System.out.println("PLANRUN won=" + (won ? "lookahead=yes" : "lookahead=no")
				+ " wrong=" + wrongNotes(with) + "/" + wrongNotes(without)
				+ " breached=" + breachedColumns(with) + "/" + breachedColumns(without)
				+ " width=" + with.width() + "/" + without.width());
		}
		if (won) {
			LOOKAHEAD_WINS++;
		} else {
			LOOKAHEAD_LOSES++;
		}
		return won ? with : without;
	}

	/**
	 * Whether a build is worth having over the one it would replace, ties going to the incumbent.
	 *
	 * <p>Ranked the way the faults are worth caring about and not the way they are easy to count. A
	 * wrong note is a song that plays wrong and outranks everything. Then breaches, in columns
	 * rather than lanes, because the ground a lane covers that it promised not to is the thing
	 * ekran finds in the world. Length last, and only as a tie-break: a shorter build that breaches
	 * is not a better build.</p>
	 *
	 * <p>The tie going to the incumbent is the point rather than a detail. A move that has to be
	 * strictly better to be taken cannot regress anything, which is what makes it safe to leave a
	 * move switched on while it is still being worked out.</p>
	 */
	private static boolean beats(PastePlan challenger, PastePlan holder) {
		int wrongThere = wrongNotes(holder);
		int wrongHere = wrongNotes(challenger);
		if (wrongHere != wrongThere) {
			return wrongHere < wrongThere;
		}
		int breachedThere = breachedColumns(holder);
		int breachedHere = breachedColumns(challenger);
		if (breachedHere != breachedThere) {
			return breachedHere < breachedThere;
		}
		return challenger.width() < holder.width();
	}

	private static int wrongNotes(PastePlan plan) {
		int wrong = 0;
		for (String fault : plan.faults()) {
			if (fault.startsWith("the note")) {
				wrong++;
			}
		}
		return wrong;
	}

	private static int breachedColumns(PastePlan plan) {
		int columns = 0;
		for (int breach : plan.breaches()) {
			columns += breach;
		}
		return columns;
	}

	private static PastePlan createStraightPastePlan(BlockPos origin, Direction forward, List<EventNote> notes) {
		PlacementPlan placements = new PlacementPlan();
		int cursor = 0;
		int currentTime = 0;
		for (int index = 0; index < notes.size();) {
			int time = notes.get(index).time();
			int delay = time - currentTime;
			List<EventNote> chord = new ArrayList<>();
			while (index < notes.size() && notes.get(index).time() == time) {
				chord.add(notes.get(index++));
			}
			DelayTrigger trigger = addDelayBeforeEvent(placements, origin, forward, cursor, delay);
			cursor = addEventModule(placements, origin, forward, trigger.cursor(), trigger.triggerDelay(), chord);
			currentTime = time;
		}
		return placements.finish(PasteMode.LANE, origin);
	}

	private static ChordStats chordStats(List<EventNote> notes) {
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		for (EventNote note : notes) {
			counts.merge(note.time(), 1, Integer::sum);
		}
		int peak = 0;
		int peakTime = 0;
		int overloadedTimes = 0;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > peak) {
				peak = entry.getValue();
				peakTime = entry.getKey();
			}
			if (entry.getValue() > MAX_SIMULTANEOUS_NOTES) {
				overloadedTimes++;
			}
		}
		return new ChordStats(peak, peakTime, overloadedTimes);
	}

	private static String overloadMessage(ChordStats stats) {
		return stats.peak() + " simultaneous notes at time " + stats.peakTime()
			+ " exceeds the build limit of " + MAX_SIMULTANEOUS_NOTES;
	}

	/**
	 * Where a paste from this screen would land.
	 *
	 * <p>Reachable so the build options screen can forecast the very build its Paste button would
	 * make, rather than one at a stand-in origin that is only probably the same.</p>
	 */
	static BlockPos pasteOrigin(Minecraft minecraft) {
		return pasteOrigin(minecraft, Direction.EAST);
	}

	private static BlockPos pasteOrigin(Minecraft minecraft, Direction forward) {
		return minecraft.player.blockPosition().relative(forward).immutable();
	}

	/**
	 * Folds back and forth inside a width you choose, growing away from you as far as it needs.
	 *
	 * <p>It has to be the walled walk and not the square one: a lane that ends on a length budget
	 * stops wherever the budget runs out, and those ragged ends wander, so a 32-wide fold came out
	 * anywhere from 34 to 72 blocks across. A wall is a wall.</p>
	 *
	 * <p>Extra floors shorten it rather than widen it. One floor fills forward, the next retraces it
	 * backwards overhead, the third goes forward again, so three floors is a third of the length in
	 * the same footprint. Three chains started together by a common riser would give the same three
	 * floors, and cost more: each chain spans the whole song, so each one builds its own repeaters
	 * for every silence in it. Measured over the songs in run/config that was 3% worse on the
	 * densest and 64% worse on the sparsest, and it is the sparse ones that are already long.</p>
	 */
	private static PastePlan createLanePastePlan(BlockPos origin, Direction forward,
			List<EventNote> notes, int width, int floors, Layout layout, PasteMode mode,
			WalkStart start) {
		List<EventGroup> events = eventGroups(notes, layout);
		// Two blocks of the width go on the fold itself: the turn steps one past the end of a lane
		// and a corner carrying notes reaches one past that. The wall still has to clear the longest
		// single event, or an event too big to fit would turn on every attempt and never advance.
		int longest = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		int laneWidth = Math.max(longest + 2, width - 2);
		PlacementPlan placements = new PlacementPlan();
		// One floor is not a different kind of build, it is a build whose every turn is flat -- and
		// walkWall already says so: with one floor the step above is never inside it, so the walk
		// takes the sideways slab step every time and the floor never moves. Sending it to the older
		// serpentine instead meant the planner, the closing pad, the turn reserve and the footprint
		// count were all switched off at exactly the setting where every turn is the flat one. The
		// older lane mode keeps its own walk, because that mode is finished and this is not its
		// change to carry.
		if (floors <= 1 && !layout.ultra()) {
			walkFolded(events, origin, forward, laneWidth, Integer.MAX_VALUE, placements, layout);
		} else {
			walkWall(events, origin, forward, laneWidth, floors, placements, layout, start);
		}
		return placements.finish(mode, origin, origin.getX(), origin.getX() + laneWidth);
	}

	/**
	 * The second layout, planned and walked.
	 *
	 * <p>Built once, where {@link #bestUltraPlan} builds twice and keeps the winner. That pair exists
	 * to judge the lookahead, and the lookahead is only ever consulted by {@link #planLane} -- which
	 * v2 does not call. Two identical builds and a comparison between them is not a guarantee, it is
	 * fifteen milliseconds.</p>
	 */
	static PastePlan createV2PastePlan(BlockPos origin, Direction forward, List<EventNote> notes,
			int width, int floors, WalkStart start) {
		// Without the lookahead, and measured rather than assumed. It is read only by planLane, and
		// the first layout builds both ways and keeps the winner -- so turning it on here looked like
		// the obvious fix for Guardian. It is not: with it on, the all-25 song breaks too, a lane
		// twenty-five columns past its wall on the first chord it meets. The lookahead books pads for
		// a lane that closes on overshoot, and v2's lanes close a column earlier.
		Layout layout = Layout.ultra(floors, origin).asV2();
		List<EventGroup> events = eventGroups(notes, layout);
		int longest = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		int laneWidth = Math.max(longest + 2, width - 2);
		PlacementPlan placements = new PlacementPlan();
		walkV2(events, origin, forward, laneWidth, floors, placements, layout, start);
		return placements.finish(PasteMode.ULTRA_COMPACT_LANE_V2, origin, origin.getX(),
			origin.getX() + laneWidth);
	}

	/**
	 * Folds upward instead of sideways, so the build only ever grows one way.
	 *
	 * <p>The same walk as everywhere else with two of its axes swapped. Lanes still run across the
	 * width, but a lane now steps <em>up</em> to the next one, and only when the floors are used up
	 * does the whole thing move once into open ground and come back down. That makes the third axis
	 * monotonic: the music sweeps up and down a slab that creeps steadily away from you, so you can
	 * follow it in a straight line -- walk it, or lay a rail and ride it -- instead of doubling back
	 * across a corridor for every lane.</p>
	 *
	 * <p>Chords grow along that third axis rather than with the lane step, because the lane step is
	 * now vertical and a note block needs air directly above it to sound. That also fixes the floor
	 * pitch at four with nothing to measure: only the sideways step, where the chords went, has to
	 * be sized to what the lanes hold.</p>
	 */
	private static void walkWall(List<EventGroup> events, BlockPos origin, Direction forward,
			int laneWidth, int floors, PlacementPlan placements, Layout layout, WalkStart start) {
		// Where chords grow, and the direction the whole slab creeps once a sweep is done.
		Direction depth = forward.getClockWise();
		// The walk's whole position: where it stands, which way the wire is running, and any corners
		// still ahead of it. One object rather than a cursor and a heading, because a turn is now a
		// stretch of this route with two bends in it -- the walk carries on through a corner the same
		// way it carries on through anything else, and the route is what remembers that it bent.
		// Marked crowded for ultra from the very first cell. Ultra is the mode that packs lanes until
		// they touch, so no lane in one owns the ground its notes hang over: the cells beside it belong
		// to the lane before, to the turn it came round, or to the corridor alongside. Every note it
		// hangs is therefore offered rather than assumed, and a bus that finds a slot taken carries on
		// a block further and hangs it there. The other modes leave a clear column and can assume.
		// Started partway along the lane where a debug build asks for it, so a chord can be put a
		// stated distance from the wall without a song in front of it to push it there. The walls
		// below are measured from the origin and not from here, which is the whole point: the
		// corridor is the width it would be, and the walk simply begins further down it.
		Lane lane = layout.ultra()
			? Lane.straight(origin.relative(forward, start.column()), forward, depth).crowding()
			: Lane.straight(origin.relative(forward, start.column()), forward, depth);
		// Whether those bends are the ones a turn put there, so that the walk knows to re-pin the
		// note side and start a new lane the moment it comes out the far side.
		boolean turning = false;
		// And whether the chord about to be placed is the first one after coming out. The stated
		// reason was that a stacked module there is still perpendicular to the ones along the
		// sideways run it has just left -- the corner behind it rather than under it, near enough to
		// be the same problem -- so the restriction outlasts the turn by exactly one chord.
		//
		// Ekran doubts that reason and the blocks are on their side. A turn's bus comes out of the
		// second bend running the new lane's way, so by the time this chord is placed the cells
		// behind it are collinear with it, not across it: Kick Back's turn at tick 340 is nine cells,
		// bends after one and after four, so its last five run along x on the new z and the chord at
		// tick 344 follows them in line. The sideways run is three cells further back. Gated by
		// {@link #TURN_BAN_OUTLASTS} so the claim can be measured rather than argued.
		boolean leavingTurn = false;
		// The last corner the route took, for the distance form of the rule above.
		BlockPos lastCorner = null;
		// A descent is the one thing in a build that steps a column off its own centre line, and
		// it steps back the way the slabs came. The slab behind this one is climbing where this one
		// descends -- they alternate -- and a climb keeps to the centre line, so that column is the
		// one with nothing in it. Stepping the other way would put live stone against the notes of
		// the slab not yet built.
		Direction descentSide = layout.ultra() ? depth.getOpposite() : depth;
		int nearWall = origin.getX();
		int farWall = origin.getX() + laneWidth;
		int currentTime = 0;
		// Which floor the walk believes it is on and which way it is going, which together decide
		// whether the wall ahead is a climb, a descent or a flat turn. Nought and up at the head of a
		// song; anything else is a debug build asking to start in the middle of one, because the
		// shape of a wall is not a thing you can ask for directly -- you arrive at it.
		int floor = start.floor();
		int climb = start.climb();
		boolean laneStarted = false;
		/** Whether a chord has been laid on the bend the walk is currently going round. */
		boolean placedWhileTurning = false;
		// Whether the column a stacked module would want behind it is already spoken for -- either
		// by the module before it, whose relays reach into it, or by a turn, whose run of powered
		// stone lies right alongside it at the same level.
		boolean columnBehindBusy = false;
		ChordStyle lastStyle = ChordStyle.SMALL;
		// Which rail of a two-rail run the next note falls on: 0 the path, 1 the floor beneath it,
		// and -1 for a walk that is not in a run. A run cannot end on the floor rail -- its notes sit
		// a level below the path and there is nothing to bring the wire back up -- so this only ever
		// returns to -1 from 0. See {@link #addRailNote}.
		int railPhase = -1;
		// When each rail's last anchor went live, indexed the same way. Every repeater a run lays is
		// measured from one of these rather than from an event two back, because a blank column
		// swaps the rails over and after that the two no longer line up with the event order.
		int[] railLive = new int[2];
		// The tick a blank owed to the next floor column goes live at, or {@link #NO_BLANK}. Decided
		// one column ahead, because the repeater that drives the blank is laid before the walk
		// reaches it and the two have to agree about when it fires.
		int railBlank = NO_BLANK;
		// How many blanks this run has laid one after another, so that a floor rail carrying nothing
		// stops rather than being paid for to the end of the lane. See {@link #RAIL_BLANKS_IN_A_ROW}.
		int railBlanksRunning = 0;
		// And whether the floor rail has held a chord yet at all. A run that has never got one down
		// there is the one ekran read off lady brown, and the only one where the blank is not paying
		// for itself: once the floor rail is carrying, breaking the run costs a whole fresh head.
		boolean railFloorCarried = false;
		// What the wire at the end of the lane is still worth. Every module opens with a repeater, so
		// this only ever counts what the module just built spent: nothing, unless it was a bus.
		int tipSignal = DUST_RANGE;
		LaneReach reach = laneReach(events, 0, events.size());
		int slabStep = laneSpacing(reach, reach);
		// A seeded walk may also start mid-turn, with the corners at the end of the lane already on
		// its route. That is the ordinary state of a lane in the middle of a song and the one thing a
		// run of chords cannot be written to produce: a lane arms its turn once, when it starts, and
		// a spec can only choose what stands in it afterwards. Without this the seed can put a chord
		// the right distance from a wall or in a bending lane, never both.
		if (start.turning() && layout.ultra()) {
			lane = armTurn(placements, lane, depth,
				(farWall - lane.pos().getX()) * forward.getStepX(), slabStep);
			turning = true;
		}
		// Pad this lane has to lay before it reaches its last chord, settled when the lane starts.
		// See planLane: by the time a lane finds out it cannot fill the gap in front of it, the
		// chords that could have filled it are built.
		Map<Integer, Integer> booked = Map.of();
		boolean replan = layout.ultra();
		// Anchored on the lane, not the cursor: ahead(n) from here reaches every column of
		// this lane, and a lane's travel and depth do not change once it has begun.
		ParityOracle parity = layout.ultra() ? parityOracle(placements, lane) : null;
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			// Where the last corner is, in the world, kept while the route still carries the bend --
			// once it is taken there is nothing left to ask. Bend offsets are relative and shift as
			// the lane advances, so the position has to be read now rather than reconstructed later.
			if (lane.bending()) {
				int furthest = 0;
				for (Lane.Bend bend : lane.bends()) {
					furthest = Math.max(furthest, bend.after());
				}
				lastCorner = lane.ahead(furthest).pos();
			}
			// Out the far side of a turn. The route stops bending of its own accord once the walk has
			// passed both corners, so there is nothing to count down and nothing to ask how long a turn
			// was: the moment no corner is left, this is a new lane. Its note side is re-pinned to the
			// slab's own depth, because a chord riding a corner turns with the path -- which is what
			// makes a bend nothing but more lane -- while a lane's chords all grow the same way in the
			// world, whichever way that lane happens to run.
			if (turning && !lane.bending()) {
				lane = lane.pinned(depth);
				turning = false;
				leavingTurn = TURN_BAN_OUTLASTS;
				// Unless the turn itself held music, in which case this lane has already started.
				//
				// The rule this clears is "a lane must hold something before it can end", and it is
				// there so a turn landing short does not turn again at once. A walked turn *is* lane
				// -- that is the whole of the flat-turn design -- so a bend that carried a chord has
				// already satisfied it, and clearing the flag anyway exempts the event after the bend
				// from ever noticing it does not fit. On Kick Back at twelve wide over three floors
				// that is a chord of twenty-four landing flush against the wall with the column its
				// own turn reserve asked for already spent, three blocks of wire left, and a
				// staircase wanting five: the lane cannot turn, cannot cut, and runs seven columns
				// out. Ekran found it in a vertical slice.
				laneStarted = placedWhileTurning;
				placedWhileTurning = false;
				// How far from the corner, not whether the last chord was in the bend.
				//
				// What fills the pair behind the first chord of a new lane is not the turn's own run,
				// which lies perpendicular to it. It is the chord standing *on* the turn -- the one
				// inTurn converts to a plain bus -- hanging its notes along the corner. So how far
				// that reaches is a distance, and asking "did the route just stop bending" answers a
				// question about the route rather than about the blocks. ekran: this chord is already
				// well out of the bend, and four blocks clear of a flat corner is enough for any chord
				// to stand without collisions.
				//
				// The same shape as the turn ban's own STACKED_CLEAR_OF_CORNER test, and set on the
				// one variable both the planner and the walk read, so neither can answer it
				// differently from the other.
				columnBehindBusy = true;
				replan = layout.ultra();
			}
			// Settled before the event is placed rather than after it. A turn hands back a cursor at
			// the same point along the wall the last event reached, so an event that overshoots
			// leaves the next lane starting outside the wall -- and nothing measured afterwards can
			// help, because by then the overshoot is built. Asking first costs a lane its last event
			// and keeps the wall a wall.
			TurnCost turn = turnCost(floor, climb, floors, slabStep);
			int above = turn.above();
			int turnCells = turn.cells();
			int offBus = turn.offBus();
			int wall = lane.travel() == forward ? farWall : nearWall;
			int stepOffAhead = turn.stepOff();
			int splitCells = turn.splitCells();
			if (replan) {
				parity = layout.ultra() ? parityOracle(placements, lane) : null;
				// Only worth doing ahead of a staircase. The plan's whole job is to work out how much
				// pad each chord owes so the lane arrives flush at its wall, and a lane that ends in a
				// flat turn does not need to arrive flush at anything -- the chord that meets the corner
				// carries on across it. Planning one anyway is where most of the pad in a build came
				// from: a song of nothing but chords of twenty-two, on one floor, has no staircase in it
				// at all and should lay no pad anywhere.
				// v2 books nothing at all. A lane that can always cut never has to be walked out to its
				// wall, so there is nothing for the pad search to buy -- see {@link #CUT_ONLY_LANES}.
				booked = above >= 0 && above < floors && !CUT_ONLY_LANES
					? planLane(events, index, lane.pos().getX(), lane.travel().getStepX(), wall,
						lane.travel() == forward ? nearWall : farWall,
						currentTime, tipSignal,
						PLAN_ASKS_THE_BLOCKS_BEHIND
							? columnBehindBusy && !backPairIsFree(placements, lane, event.time())
							: columnBehindBusy,
						turnCells, offBus, stepOffAhead,
						splitCells, climb > 0, layout, inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity)
					: Map.of();
				replan = false;
			}
			// A lane that ends flush with the wall on a wire too weak to reach the top of a staircase
			// has nowhere left to stand the repeater that would revive it, and a lane that cannot turn
			// runs on past the wall instead. So an event that would leave the wire that weak is asked
			// to fit a column short, and the pad puts a repeater in the column that buys.
			// Whether what lies ahead is a flat turn rather than a staircase, and so whether it is
			// walked or crossed. A staircase is still a gap in the path that the lane must arrive flush
			// at; a flat turn is more lane, and a chord meeting one simply carries on round it.
			boolean flatAhead = !(above >= 0 && above < floors);
			// A chord small enough to lie across a flat turn needs nothing done for it at all. It is
			// built where it stands and runs on into the corner, and the turn lays whatever is left --
			// which is the same columns filled with music instead of with wire. Padding the lane out to
			// meet the turn is what makes a run too long for the repeater at the end of it to clear,
			// and it was buying nothing: the chord was going to cover that ground anyway.
			boolean straddles = layout.ultra() && flatAhead
				&& straddleFits(event.notes().size(),
					(wall - lane.pos().getX()) * lane.travel().getStepX(), slabStep);
			int reserve = turnReserve(event, offBus, layout);
			int wait = event.time() - currentTime;
			// Measured with the same arithmetic the planner uses, and not with a length taken from
			// the shape the chord was measured in. A stacked chord that finds the pair of slots
			// behind it spoken for is built as a bus instead, and a bus is longer -- so a lane could
			// be told a chord fitted, build it, and land a column past its own wall.
			Landing here = landingOf(lane.pos().getX(), lane.travel().getStepX(), event, wait,
				columnBehindBusy, wall, layout, inTurn(turning, leavingTurn, lane.pos(), lastCorner),
				parity);
			// A cell of a rail run is one column, and the column that opens one is three -- the
			// repeater, the dust, then the note. Overridden here rather than taught to landingOf,
			// because whether a run is under way is a fact about the walk rather than about the
			// chord: the same event opens a run in one lane and continues one in the next.
			boolean railContinues = railPhase >= 0;
			// Two where a chord of three has landed on the floor rail, which has no centre to give it:
			// the blank column that gets it onto the path rail, and then its own.
			int railColumns = railContinues
				? railPhase == 1 && railBlank != NO_BLANK ? 2 : 1
				: railOpens(events, index, lane, wall, layout, turning, reserve, wait,
					railStackSeed(placements, lane, lastStyle, currentTime, turning), booked)
					// The head's columns, its chord, and the repeater a four-tick stretch of the wait
					// in front of it costs -- the same sum the plain path makes of it.
					? railHeadColumns(railStackSeed(placements, lane, lastStyle, currentTime, turning),
						wait) + 1 + railPadColumns(wait) : 0;
			int landing = railColumns > 0
				? lane.pos().getX() + lane.travel().getStepX() * (railColumns + reserve)
				: here.end() + lane.travel().getStepX() * reserve;
			// Never while the route is still bending. Inside a turn the wire runs across the corridor
			// rather than along it, so every one of these measurements is taken down the wrong axis --
			// and there is nothing to decide anyway, because the walk has already committed to the
			// corner it is standing in. A turn ends when the route runs out of corners, not when some
			// arithmetic about walls says so.
			// Whether this event will not fit before the wall, which is a different question from
			// whether the lane may end here.
			// Never while a run owes its next column. A run that has laid a repeater has promised the
			// column in front of it a note, and every way a lane can be interrupted takes that column
			// away: the note ends up on the far side of a staircase, a floor down and running the
			// other way, while the repeater meant to drive it stays at the wall driving the staircase.
			//
			// Said here, on the overshoot, rather than on {@code wantsTurn}. The cut is deliberately
			// asked of the overshoot and not of the turn -- see the split below -- so a guard on the
			// turn alone left the cut free to take the column, which is exactly what it did.
			//
			// Safe to refuse because a run only extends where the pair it commits to fits before the
			// wall with the turn's column still to spare, asked column by column as it goes. The run
			// always ends first, and then the lane turns.
			boolean overshoots = !turning && railPhase < 0
				&& (landing > farWall || landing < nearWall);
			// And whether it merely gets there. A chord ending on the wall, or one column short of
			// it, has not overshot and so was never offered a cut -- which leaves the lane holding a
			// long bus, no near half to cut, and a turn to pay for out of the two cells that bus left
			// it. That is the case the whole pad layer was built around. See
			// {@link #CUTS_THE_CHORD_THAT_REACHES}.
			//
			// Carries the run's guard as well. This is the other way into the cut, and it is off in v1
			// -- so it changes nothing today -- but a cut offered here would take the column the run
			// has already promised, which is the whole reason the guard is on the overshoot rather
			// than on the turn.
			boolean reaches = !turning && railPhase < 0
				&& (wall - landing) * lane.travel().getStepX() <= 1;
			// A lane has to hold something before it can end, or a turn that lands short would turn
			// again at once and the walk would climb the whole build without laying a note.
			boolean wantsTurn = laneStarted && overshoots;
			// What the cut is offered on. The same question in v1, one column earlier in v2.
			boolean cutOffered = overshoots || CUTS_THE_CHORD_THAT_REACHES && reaches;
			// One tick has to be left for the next event's own repeater, which is the only thing that
			// can drive the module it stands in front of.
			int columns = (wall - lane.pos().getX()) * lane.travel().getStepX();
			Pad pad = layout.ultra() && wantsTurn && !straddles
				? planTurnPad(columns, tipSignal, turnCells, offBus, Math.max(0, wait - 1),
					climb > 0, above >= 0 && above < floors,
					lastStyle.buses())
				: Pad.none(tipSignal);
			// A split comes before any of that. The event that will not fit is cut in two: as much of
			// it as reaches the wall, then the staircase, then the rest -- one repeater, one tick, one
			// chord, because dust takes no time however far it runs or however many levels it climbs.
			// It fills the lane with the music that was going to be built anyway, where a pad fills it
			// with wire it then has to pay for, so it is tried first and padding is what is left when
			// the chord is too big to cut: a run from a repeater is fifteen blocks, a staircase takes
			// four of them off a bus, and a bus carries two notes a block.
			int delayColumns = Math.max(0, (event.time() - currentTime - 1) / 4);
			int room = columns - delayColumns;
			int cells = (event.notes().size() + 1) / 2;
			// A descent lands where it cannot be built on straight away and spends a block stepping
			// off, which is a block the chord could have used.
			int stepOff = stepOffAhead;
			// Only ahead of a staircase now. A flat turn is walked rather than crossed, so a chord that
			// will not fit before it is not cut in two: the walk takes the corner and carries on laying
			// the same chord along the sideways run, which is the cut done by the ordinary machinery
			// and without a near half and a far half to keep in step.
			// The whole run and nothing more: both halves of the chord, and the staircase between
			// them, reaching from the repeater this module opens with to the next one. There is no
			// further cell to charge at the far end. A carried module hands back the cell after its
			// last bus block, and the next module stands its repeater on that cell a level up --
			// which puts the repeater against the bus, not a block short of it. This once carried a
			// cell for that gap and the gap is not there; it cost 256 lanes their wall to buy
			// nothing. Ekran built the descent by hand and counted the wire through it: eight cells
			// of bus, six of staircase, one cell more, and the last of them still reads one.
			// Asked of the overshoot and not of {@code wantsTurn}, which is the same question plus
			// "and this lane already holds something". That extra clause is there to stop a lane
			// turning the instant it opens, and it has no business here: a split *builds* -- it fills
			// the columns to the wall with the near half of the chord before it turns -- so it always
			// makes progress and can never loop. Charging it that clause meant the first event of a
			// lane could not be cut, and the first event of a lane is exactly the one that lands
			// wherever the staircase happened to put it. A chord needing eight columns opened on a
			// lane with seven and was laid anyway, a column past the wall. Ekran found it as the
			// second of two breaches on Kick Back, and it is the same exemption that put the old
			// build seven columns out.
			// Charged at what a split's own crossing costs, which is not the turn plus the step off
			// any more. A split always arrives on a bus, and a descent that may assume that is four
			// cells rather than six -- see {@link #addSplitBusDescent}. The same sum is made in
			// {@link #closes}, and the two have to be the same sum: a lane the planner closes by a
			// cut and the walk refuses to cut is a lane that runs on past its wall.
			// The head carries seven for nothing, so a chord too big to cut as a plain bus may
			// still be cuttable with one. Asked first, and the plain sum is what is left when the
			// chord cannot take a head -- too many falling instruments, no harp for the centre, or
			// no room for a head and a cell of bus before the wall.
			StackedSplit headed = layout.ultra() && cutOffered && index > 0 && above >= 0
				&& above < floors
				? stackedSplitOf(event.notes(), room, splitCells, climb > 0,
					!columnBehindBusy || delayColumns > 0
						|| CUT_ASKS_THE_BLOCKS_BEHIND
							&& backPairIsFree(placements, lane.ahead(delayColumns), event.time()),
					columnBehindBusy)
				: null;
			// A cut is built straight from the module rather than through {@link #addChordModule},
			// so none of that method's guards are applied to it -- and the one that matters is the
			// parity check. Without it a head can land its low notes against a live block of the
			// lane behind, which sounds them at that lane's tick: one BELL of a hundred and fifty
			// events read twenty-one ticks early, and nothing else in the build said a word.
			//
			// Refused rather than nudged. A nudge moves the module a column and the near half is
			// measured to land on the wall exactly, so shifting it is how a cut ends up outside the
			// footprint. Giving the head up falls back to the plain sum below, which is what the
			// walk did before any of this and is always safe.
			// Two guards, and both are ones {@link #addChordModule} applies that a cut never went
			// through. The head hangs a pair of low notes in the column *behind* it, so it needs
			// that column free -- the same rule {@link #landingOf} states as reachesBack and busy.
			// Without it the head's instrument block lands in the air a note of the chord before it
			// insists on, and the build refuses outright.
			// Counted, not refused. The cut is asked for above knowing whether the column behind is
			// free, so what arrives here is already a head of five where it had to be -- and a chord
			// that could not make even that has no headed cut at all.
			if (headed != null && columnBehindBusy && delayColumns == 0) {
				placements.padded("planStackedSplitShortHead");
			}
			// A cut whose head lands on the wrong parity is moved a column, not given up.
			//
			// It used to be given up, on the grounds that a cut's near half is measured to land
			// flush on the wall and shifting it a column puts it past. True of the shift alone --
			// but the near half is not rigid. Cut it for one column less and the pad in front makes
			// the difference up: one column of wire, a head, a transition and a bus one cell
			// shorter is exactly the same total, so the lane still comes to rest on its wall and
			// nothing the planner worked out has to change. The two notes that no longer fit the
			// near half simply ride over the staircase with the rest of the far half, which is
			// where they were always going anyway.
			//
			// A cut opens on a repeater and is handed the whole fifteen, so there is no question of
			// the wire reaching -- which is the other thing that stops an ordinary chord nudging.
			// Ekran found this on Do The Dance at forty wide over eight floors: a chord of
			// twenty-four that would not cut, and a lane five columns past its wall for want of one.
			boolean splitNudge = false;
			boolean splitClashed = false;
			if (headed != null && stackedClashes(placements, lane.ahead(delayColumns),
					event.time(), headed.slots())) {
				splitClashed = true;
				// And the same question the chord nudge is asked: does the wire still reach. This
				// nudge lays a cell of dust where the head's repeater would have stood, on top of the
				// delay about to be laid in front of it -- so what has to fit is the run so far, the
				// delay, and the pad. ekran found this one from the blocks: the module really did
				// have to move, and moving it put its repeater a cell past the wire.
				//
				// Asked before the delay exists, so the delay is added by hand. Where a repeater
				// falls inside that delay the run resets and this is too careful by however much it
				// reset, which is the safe way to be wrong about a nudge.
				boolean splitOutOfWire = MEASURED_NUDGE_REACH
					&& placements.runSinceRepeater() + delayColumns + 1 > DUST_RANGE;
				if (splitOutOfWire) {
					placements.padded("planSplitNudgePastTheWire");
				}
				StackedSplit shifted = !SPLIT_NUDGES || splitOutOfWire
					|| stackedClashes(placements, lane.ahead(delayColumns + 1), event.time(),
						headed.slots())
					? null : stackedSplitOf(event.notes(), room - 1, splitCells, climb > 0,
						!columnBehindBusy || delayColumns + 1 > 0
							|| CUT_ASKS_THE_BLOCKS_BEHIND
								&& backPairIsFree(placements, lane.ahead(delayColumns + 1), event.time()),
						columnBehindBusy);
				if (shifted == null) {
					// Both cells wrong, or nothing left to cut once a column is spent. Then the head
					// goes, which is what this did in every case before.
					placements.padded("planStackedSplitClashed");
					headed = null;
				} else {
					placements.padded("planStackedSplitNudged");
					headed = shifted;
					splitNudge = true;
				}
			}
			boolean couldSplit = layout.ultra() && cutOffered && index > 0 && above >= 0
				&& above < floors && (headed != null
					|| (room >= 2 && room - 1 < cells && cells + splitCells <= DUST_RANGE));
			// Counted where it bites rather than where it is decided. The planner books the veto on a
			// lane it is only considering, and most of those plans are thrown away; what matters is how
			// often a split the walk was about to build actually got stopped, because that is the number
			// the regression has to be paid for out of.
			boolean vetoed = couldSplit && booked != null && booked.containsKey(NO_SPLIT - index);
			if (vetoed) {
				placements.padded("planVetoBit");
			}
			boolean split = couldSplit && !vetoed;
			// Unless leaving that tick is what stops the pad reaching the wall. Then spend the whole
			// wait on the pad and carry the event over the turn on the wire instead, which is the one
			// way a lane whose next event is a single tick away can still end where it is meant to.
			boolean carried = false;
			// Asked only when carrying is still on the table. This plans a pad that fills the lane to
			// the wall so the next chord can be carried over a staircase without a repeater -- and it
			// used to plan it whatever, then have the carry rejected further down for want of a
			// staircase to cross. The carry went away and the pad stayed, which is a lane padded flush
			// for a reason that no longer existed: four blocks of wire in front of a chord that was
			// about to lie across the corner by itself.
			if (layout.ultra() && wantsTurn && !straddles && above >= 0 && above < floors
					&& pad.cells().size() < columns) {
				Pad whole = spending(planPad(columns, tipSignal, turnCells, wait), wait);
				if (whole != null && whole.cells().size() == columns
					&& whole.signal() >= turnCells + stepOff + cells) {
					pad = whole;
					carried = true;
				}
			}
			// The old rule asked the last event whether a turn would still be in range. It answers for
			// the wire it laid and nothing else, so a lane ending on a long bus could not turn at all
			// and ran on until one ending on a short chord came along -- which is most of why the
			// staircases were scattered rather than merely off by a column. The pad can put a repeater
			// in and make the range question go away; when it cannot, the lane still has to run on.
			// And on the wall or not at all. A turn is the one thing in a build that steps off its own
			// centre line, so a turn standing anywhere else stands beside whatever that column happens
			// to hold. A lane that cannot reach its wall carries on to the next chord and tries again;
			// the only turn allowed elsewhere is one on a lane already past its wall, where carrying on
			// would never bring it back.
			boolean onWall = pad.cells().size() == columns;
			// What the wire must still be worth to take the turn. A staircase has to be crossed in one
			// run and costs its whole length; a flat turn only has to be *reached*, because the chord
			// standing on it opens with a repeater of its own that hands out a fresh fifteen. Charging
			// a walked turn as though it were a staircase is what made lanes give up and run on while a
			// perfectly good corner was two blocks away.
			// A straddling chord has nothing to reach. Its repeater goes down where the lane has got
			// to, and a repeater hands out a fresh fifteen however dead the wire arriving was, so the
			// only run that matters is the one inside the chord itself. Charging it for a staircase it
			// is not going to cross is what made a lane give up with a usable corner in front of it.
			// A flat turn may only be taken by a chord that can actually get across it. The straddle
			// test used to decide only whether to pad, which left the chord itself free to be laid
			// over both corners regardless -- and a chord of thirty cannot be: two corners cost it two
			// slots, sixteen blocks of bus, and the last of them is past what its repeater reaches. It
			// runs on instead and turns in front of a chord that fits, which breaches the footprint
			// and says so, rather than building a tail that never fires and saying nothing.
			// And where a descent is pinned, the wire has to reach the wall as well as the staircase.
			// Pinning makes the turn absolute: the lane walks out to the wall whether the pad paid for
			// those columns or not, so a lane allowed to hand over without the signal to cross them
			// hands over onto dead wire. The rule the planner has always used is the one to match --
			// {@link #closes} will only end a lane at its wall or by a cut, never on a pad that got
			// most of the way -- and a lane refused here simply lays one more chord, whose repeater
			// hands out a fresh fifteen, and turns after that. It costs footprint, which says so, and
			// not a tail that never fires, which does not.
			int unpaid = Math.max(0, columns - pad.cells().size());
			// Priced through the one place that knows whether the pad can be lifted onto the climb.
			// A lane that could not afford five may well afford three, and this is the test that
			// decides whether it turns here at all -- so it has to ask the same question the pad was
			// planned with and the same one the walk will charge itself when it builds the staircase.
			// Ungated, and it has to be: this prices the staircase the walk is about to build, and the
			// walk builds a raised pad whether or not the search was allowed to plan for one. Gating
			// it here also took the empty-pad bus discount out with it, which predates all of this.
			int turnPrice = turnPrice(pad, climb > 0, above >= 0 && above < floors,
				lastStyle.buses(), turnCells, offBus);
			// Priced after the line above, because this is the second place the same turn is priced
			// and the two were answering differently. {@link #turnPrice} knows a pad standing on a bus
			// can be lifted onto the climb and charges three; this knew only the older discount, for a
			// pad with no cells at all, and charged five for everything else -- so a lane holding four
			// and needing three was refused its turn by the arm that had not been told.
			int turnCost = unpaid == 0
				? Math.min(WALL_REACH_PRICES_THE_RAISED_PAD ? turnPrice : turnCells,
					pad.cells().isEmpty() && lastStyle.buses() ? offBus : turnCells)
				: turnCells;
			boolean reachesWall = !PIN_DESCENTS || flatAhead
				|| pad.signal() - unpaid >= turnCost;
			boolean canTurn = layout.ultra()
				? index > 0 && reachesWall && (flatAhead ? straddles && pad.signal() >= 1
					: pad.signal() >= turnPrice)
				: index > 0 && events.get(index - 1).maxSafeTurnDistance() >= MAX_LANE_SPACING;
			// A veto is a preference, not a prohibition.
			//
			// The plan forbids a cut where it has found a way to close the lane on a pad instead, so
			// that the walk -- which decides to split on its own arithmetic -- does not quietly close a
			// chord earlier than the plan did. That is the right instinct where the pad close works.
			// Where it does not, the lane has been told it may not cut *and* cannot turn, so it does
			// neither and walks out past its wall carrying the chord whole.
			//
			// ekran found one at Guardian 16 wide over four floors, {@code 20 77 148}: a chord of 24
			// at {@code x=13} with the wall at 15, {@code headed=0+18} and {@code couldSplit=true} --
			// the cut was there, already shed, and the veto threw it away for a pad of one cell with
			// four blocks of wire, which {@code reachesWall} then refused. Ten columns outside.
			//
			// Asked here rather than where {@code split} is first worked out, because this is the
			// first line at which the walk knows whether the plan's alternative is open to it.
			if (CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN && vetoed && couldSplit && !canTurn) {
				placements.padded("planVetoTakenBack");
				split = true;
			}
			int spentPadding = 0;
			// Ahead of a staircase only, for the same reason a split is. A chord that would have been
			// carried across a flat turn on bare wire is now simply built on the turn.
			carried &= canTurn && !split && above >= 0 && above < floors;
			// On the wall or not at all -- except that a machine you cannot paste is a machine you
			// cannot go and look at. So a lane that will not reach its wall turns where it stands and
			// says so, on the same overlay a wrong note would appear on, naming the chord that beat it
			// and what it had left to work with. Every one of these is a thing to go and fix.
			// Counted where the lane actually hands over, which is the only moment its final extent is
			// known. A negative count is a lane that walked out past the wall its width was promised
			// at -- so the paste covers ground the player was told it would not, and that is the half
			// worth stopping to ask about rather than merely listing.
			if (layout.ultra() && wantsTurn && canTurn && columns < 0) {
				placements.breached(-columns);
			}
			// Every chord standing outside the footprint, not only the ones that asked to turn.
			// A chord that does not overshoot prints nothing on the old condition, and a lane already
			// past its wall can lay several of those in a row -- which is exactly the run ekran has
			// been reading in game and the old trace could not see.
			if (TRACE_TURNS && layout.ultra() && (wantsTurn || columns < 0)) {
				System.out.println("PAST t=" + event.time() + " notes=" + event.notes().size()
					+ " columns=" + columns + " overshoots=" + overshoots
					+ " wantsTurn=" + wantsTurn + " canTurn=" + canTurn + " turning=" + turning
					+ " laneStarted=" + laneStarted + " straddles=" + straddles
					+ " split=" + split + " carried=" + carried + " tip=" + tipSignal
					+ " flatAhead=" + flatAhead + " reachesWall=" + reachesWall
					+ " at " + lane.pos().getX() + " " + lane.pos().getY() + " "
					+ lane.pos().getZ());
			}
			if (TRACE_TURNS && layout.ultra() && wantsTurn) {
				// Why the head went, when it went. A cut is refused either because the chord cannot
				// make a head at all or because the far half would be out of reach, and the two want
				// completely different fixes.
				StackedBusSplit why = stackedBusSplit(event.notes(), true);
				String head = why == null ? "noHead"
					: "head" + why.head().size() + "+tail" + why.tail().size()
						+ "/run" + (STACKED_BUS_TRANSITION + (why.tail().size() + 1) / 2
							+ splitCells);
				// Coordinates space-separated, so the line can be pasted straight into /tp.
				System.out.println("TURN t=" + event.time() + " notes=" + event.notes().size()
					+ " at " + lane.pos().getX() + " " + lane.pos().getY() + " " + lane.pos().getZ()
					+ " wall=" + wall + " columns=" + columns
					+ " | flatAhead=" + flatAhead + " straddles=" + straddles
					+ " canTurn=" + canTurn + " onWall=" + onWall + " split=" + split
					+ " carried=" + carried
					+ " | cells=" + cells + " tip=" + tipSignal + " wait=" + wait
					+ " pad=" + pad.cells().size() + "c/" + pad.signal() + "s"
					+ " turnCells=" + turnCells + " offBus=" + offBus
					+ " last=" + lastStyle
					+ " | room=" + room + " splitCells=" + splitCells
					+ " headed=" + (headed == null ? "no"
						: headed.nearTail().size() + "+" + headed.farTail().size())
					+ " couldSplit=" + couldSplit + " vetoed=" + vetoed
					+ " reachesWall=" + reachesWall + " unpaid=" + unpaid
					+ " why=" + head + " clashed=" + splitClashed
					+ " nudged=" + splitNudge + " behindBusy=" + columnBehindBusy
					+ " delayColumns=" + delayColumns);
			}
			// And the other side of the same measurement. A lane that hands over short of its wall
			// leaves that many columns of corridor holding nothing, and puts its staircase or its
			// sideways run somewhere no other lane's is -- which is the recessed turn that reaches
			// into the neighbour it was never meant to touch. Counted in columns rather than in lanes,
			// because one lane eleven columns short and eleven lanes one column short are the same
			// number of wasted columns and nothing like the same problem. Recorded here beside the
			// breach for the reason the breach is recorded here: it is the moment the lane's extent
			// stops changing.
			if (layout.ultra() && wantsTurn && canTurn && !onWall && !split && !carried
					&& !straddles) {
				placements.trouble("a lane turned " + columns + " columns short of its wall at tick "
					+ event.time() + ", where a chord of " + event.notes().size()
					+ " would not fit: " + tipSignal + " blocks of wire and " + (wait - 1)
					+ " spare ticks to fill them with, and " + cells + " blocks of bus plus "
					+ offBus + " for the turn is too much to cut across it");
			}
			if (split) {
				Direction travel = lane.travel();
				int wallLeft = wall;
				int stepLeft = travel.getStepX();
				SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, lane,
					event.time() - currentTime);
				currentTime = event.time();
				List<EventNote> chord = busOrder(event.notes());
				int near = 2 * (room - 1);
				List<EventNote> far;
				BlockPos cursor;
				if (headed != null) {
					BlockPos opening = trigger.cursor();
					if (splitNudge) {
						// The column the shortened cut gave back. Laid as the parity pad an ordinary
						// chord uses, so the head starts one further along and meets the lane behind
						// on the parity it wants. Named for the marker: addParityPad does not name itself
						// and the label it would otherwise wear belongs to whatever ran before it.
						placements.placing("cutNudgePad");
						placements.padded("parity");
						addParityPad(placements, opening);
						opening = opening.relative(travel);
					}
					if (headed.head().size() < STACKED_HEAD_NOTES) {
						SHORT_HEAD_CUT_AT = opening;
					}
					headed = new StackedSplit(
						onTheFreeSlots(placements, opening, travel, depth, event.time(),
							headed.slots(), headed.shed() ? DESCENT_FLANK_SLOT : -1),
						headed.head(), headed.nearTail(), headed.farTail(), headed.shed());
					cursor = addStackedSplitModule(placements, opening, travel, depth,
						trigger.triggerDelay(), headed, event.time());
					far = headed.farTail();
					placements.padded("planStackedSplit" + (climb > 0 ? "Climb" : "Descent"));
					if (headed.nearTail().isEmpty()) {
						placements.padded("planStackedSplitHeadOnly");
						HEAD_ONLY_AT = cursor;
					}
				} else {
					cursor = addSplitEventModule(placements, trigger.cursor(), travel, depth,
						trigger.triggerDelay(), chord.subList(0, near));
					far = near < chord.size() ? chord.subList(near, chord.size()) : List.of();
				}
				// A cut chord is built here and not by {@link #addChordModule}, so none of it ever reached
				// the CHORD line -- the one place the trace says what a chord was planned as, what it came
				// out as, and why it gave the shape up. Every chord laid across a staircase was therefore
				// invisible, which is a whole class of the build: the shape that carries ten cells on one
				// floor and one on the next is exactly the shape a lane closes on. ekran pointed at one and
				// it could not be found at all, through three separate readings of the trace.
				if (TRACE) {
					System.out.println("SPLIT t=" + event.time() + " at " + trigger.cursor().getX() + ","
						+ trigger.cursor().getY() + "," + trigger.cursor().getZ() + " travel=" + travel
						+ " notes=" + chord.size() + " near=" + near + " far=" + far.size()
						+ " headed=" + (headed == null ? "no"
							: headed.head().size() + "+" + headed.nearTail().size()
								+ "/" + headed.farTail().size())
						+ " planned=" + event.style()
						+ " built=" + (headed == null ? "BUS" : "head" + headed.head().size())
						+ " depth=" + depth);
				}
				// Measured from where the staircase actually lands, which for a split is past the near
				// half of the chord rather than where the lane stood when it decided to split.
				//
				// Told apart by the shape that left the gap, because the two have quite different causes.
				// A plain cut sizes its near half at {@code room - 1} cells and fills every one of them,
				// so it lands flush. A headed cut sizes its near half from what the chord had left over
				// after the head, and where the chord runs out first the staircase stands wherever the
				// module happened to end -- which is the recess {@link #CUT_PINS_ITS_STAIRCASE} pays for.
				int shortOfWall = ((travel == forward ? farWall : nearWall) - cursor.getX())
					* travel.getStepX();
				placements.recessed(shortOfWall);
				for (int cell = 0; cell < shortOfWall; cell++) {
					placements.padded(headed != null ? "recessedCutHeaded" : "recessedCutPlain");
				}
				// And the same number worked out from the shape rather than read off the block it landed
				// on. A recess that can be predicted can be paid for before the module is laid; one that
				// cannot has some other cause. Counted rather than asserted because it is asked of every
				// cut in the build, including the ones nothing is going to be done about.
				if (headed != null && room - (splitNudge ? 1 : 0) - headed.columns() != shortOfWall) {
					placements.padded("recessMispredicted");
				}
				// The near half is a bus by construction, so the descent may take the short way down
				// and there is nothing to step off onto: the far half opens on the column the spiral
				// started from, which is exactly where the old landing plus its step off arrived.
				int splitStepOff = climb > 0 || CHEAP_SPLIT_DESCENT ? stepOff : stepOff;
				splitStepOff = climb > 0 ? stepOff : (CHEAP_SPLIT_DESCENT ? 0 : stepOff);
				cursor = climb > 0
					? addGlassClimb(placements, cursor, travel, true, currentTime)
					: CHEAP_SPLIT_DESCENT
						? addSplitBusDescent(placements, cursor, travel, descentSide, currentTime)
						: addSpiralDescent(placements, cursor, travel, descentSide, currentTime);
				floor = above;
				travel = travel.getOpposite();
				if (!far.isEmpty()) {
					cursor = addCarriedEventModule(placements, cursor, travel, depth, far,
						splitStepOff);
				}
				lane = Lane.straight(cursor, travel, depth);
				// Graded against where the lane actually opened, because every hand-derivation of this
				// arithmetic in the session that found it was off by one, in both directions. A
				// prediction the planner is going to search backwards on has to be checked against the
				// walk before it is trusted, not after.
				gradeLaneStart(placements, wallLeft, stepLeft,
					far.isEmpty() ? 0 : (far.size() + 1) / 2, climb > 0, splitStepOff,
					lane.pos().getX(), climb > 0 ? "SplitClimb" : "SplitDescent");
				lastStyle = ChordStyle.BUS;
				// The whole run, not the half of it past the staircase. Both halves are dust from the
				// one repeater this module opened with, and the near half does not stop costing wire
				// because a staircase comes after it. Counting only the far half reported three
				// blocks left on a run that had already overspent by one. The same sum {@link #closes}
				// makes, which is the point: the planner closes a lane on the promise of a split, and
				// a walk that charges the split more than the planner did refuses it and leaves the
				// lane standing short of the wall it was measured for.
				tipSignal = headed != null
					? DUST_RANGE - headed.runCells(splitCells)
					: DUST_RANGE - cells - splitCells;
				gradeLaneTip(placements, turnCells, tipSignal,
					climb > 0 ? "SplitClimb" : "SplitDescent");
				// What the far half leaves behind it, asked of the shape it was built in rather than
				// asserted.
				//
				// This said {@code true} unconditionally, on the grounds that the far half's first
				// pair of notes stands alongside the staircase's powered stone. That is a fact about
				// the far half's own position; {@code columnBehindBusy} is read by the chord *after*
				// it, and asks a different question -- whether that chord may hang notes in the pair
				// of slots behind its own repeater.
				//
				// ekran: after a cut the first chord's back flanks are always free, because a stacked
				// head is never placed at the bottom of a cut. The code agrees with them everywhere
				// else -- {@link #takesTheGapBehind} is {@code style.stacked() && ...} and the far half
				// is laid as {@link ChordStyle#BUS} four lines above -- so every other site would
				// answer false here. Two places deciding one thing, which is the bug this file keeps
				// producing.
				//
				// An empty far half is the exception and stays busy: nothing was laid after the
				// staircase, so what stands behind the next chord is the landing itself.
				columnBehindBusy = far.isEmpty()
					|| !CUT_FAR_HALF_FREES_THE_GAP && true
					|| takesTheGapBehind(ChordStyle.BUS, (far.size() + 1) / 2);
				laneStarted = true;
				replan = layout.ultra();
				continue;
			}
			if (canTurn && wantsTurn) {
				// The shape the lane actually came to rest on, against the wall it is turning at.
				// This is the question ekran asked -- not what shapes a lane holds, but what shape
				// is standing in front of the staircase when it turns.
				placements.padded("planLaneEndedOn" + lastStyle
					+ (above >= 0 && above < floors ? (climb > 0 ? "Climb" : "Descent") : "Flat"));
				// A chord carried whole to the next lane, where the lane it left had to be filled with
				// wire instead. Reported because it is the thing worth being annoyed about: every one of
				// these is a chord that could have filled those columns itself.
				if (!pad.cells().isEmpty()) {
					placements.moved(event.notes().size());
				}
				// ekran's: where the rest of the lane is nothing but wire up to the wall and then a
				// climb, run that wire at bus height. It costs the same columns and the staircase then
				// starts off a bus, which is two cells cheaper. Off a bus the run simply stays up; off
				// anything else the first cell has to hold the path so the rest has a live wire to
				// climb from, and a pad of one column has no cell to spare for that.
				int liftAfter = raiseAfter(pad, climb > 0, above >= 0 && above < floors,
					lastStyle.buses());
				boolean raisedPad = liftAfter >= 0;
				lane = emitPad(placements, lane, pad, "padClosing", liftAfter);
				spentPadding = pad.delaySpent();
				if (above >= 0 && above < floors) {
					// Asked of the shape the lane actually ended on, not of how many notes it held.
					// A big chord used to mean a bus and now may mean a stacked module, which ends
					// on its centre block a level lower -- and a climb that skips the two rungs it
					// needs starts a floor above the signal and never gets it. A pad puts the wire
					// back down on the path either way, so a padded lane never skips them.
					Direction travel = lane.travel();
					// A staircase is built where the walk is standing, so this is the one turn that can
					// be recessed. A flat turn cannot: its corner is pinned to the wall however far
					// short the lane has got, and the chords still to come fill the gap in between. A
					// staircase set back from the wall stands in a column no other corridor's turn
					// stands in, which is what reaches into the lane alongside.
					int shortBy = ((travel == forward ? farWall : nearWall)
						- lane.pos().getX()) * travel.getStepX();
					// Pinned: a descent is walked out to the wall whether the pad could afford it or
					// not, so that every descent in the build stands in the same column as every
					// other. What the pad would not pay for is laid as bare dust here, which is wire
					// the signal has to cross with nothing to revive it -- so this is the experiment
					// and the fallout is whatever the wire does about it.
					int pinned = 0;
					// Ultra only. The other lane modes share this walk once they have more than one
					// floor, and their corridors are spaced on the promise that a turn is bare -- so
					// walking one out to the wall puts powered stone where a neighbour's notes are
					// entitled to be. COMPACT_LANE read one of its own notes back wrong the moment
					// this was let loose on it.
					if (PIN_DESCENTS && layout.ultra() && shortBy > 0) {
						pinned = shortBy;
						for (int cell = 0; cell < pinned; cell++) {
							placements.padded("padPinned" + (raisedPad ? "Raised" : ""));
						}
						// At the height the pad in front of it is running at. This block is not gated
						// on the direction of the turn -- the comment below says only descents are
						// pinned and the condition does not say so -- so a climb reaches here too, and
						// a pin that came back down to the path while the staircase had been told it
						// was starting off a bus is a staircase with nothing under its first rung.
						lane = emitDust(placements, lane, pinned, raisedPad);
					}
					// Recorded after the pin and not before it, so this is where the staircase actually
					// stands rather than where the lane would have left it. Told apart by direction as
					// well: only descents are pinned, so a recessed climb is a real one and a recessed
					// descent, on this branch, should not exist at all.
					placements.recessed(shortBy - pinned);
					if (shortBy - pinned > 0) {
						placements.padded(climb > 0 ? "recessedClimb" : "recessedDescent");
					}
					// A raised pad leaves exactly what a bus leaves -- stone at path+1 with dust on top,
					// one column back from here -- so the staircase joins it two rungs in just the same.
					// And a lane that ended on a bus keeps its discount through a raised pad, which it
					// never could through a pad laid on the path.
					BlockPos landed = climb > 0
						? addGlassClimb(placements, lane.pos(), travel,
							raisedPad || lastStyle.buses() && pad.cells().isEmpty(), currentTime)
						: descend(placements, lane.pos(), travel, descentSide, currentTime);
					floor = above;
					// What the staircase leaves the next lane. It matters because the next lane may
					// want to lay dust of its own before its first repeater, and a staircase is the one
					// handover in a build that spends wire without a repeater at either end of it.
					// Charged at what it actually spends: a climb taken straight off a bus skips two
					// rungs, and counting them anyway left every lane after one two blocks poorer.
					// Through the same one place canTurn asked, so the wire this lane books itself and
					// the wire it demanded before turning cannot be two different sums.
					tipSignal = pad.signal() - pinned - turnPrice(pad, climb > 0,
						above >= 0 && above < floors, lastStyle.buses(), turnCells, offBus);
					lane = Lane.straight(landed, travel.getOpposite(), depth);
					gradeLaneStart(placements, wall, travel.getStepX(), 0, climb > 0, stepOffAhead,
						lane.pos().getX(), climb > 0 ? "Climb" : "Descent");
					gradeLaneTip(placements, turnCells, tipSignal, climb > 0 ? "Climb" : "Descent");
					laneStarted = false;
					// A staircase leaves the pair beside the landing free. Only a flat turn takes it,
					// and what makes a turn take it is that it hangs notes along its own run of
					// powered stone -- not that it bends. A climb is glass and dust up two columns of
					// the lane's own centre line; a descent spirals round a two-by-two column. Both
					// carry the signal and neither hangs a note, so there is nothing there to be in
					// the way. ekran, who has read both in game.
					columnBehindBusy = !BACK_PAIR_FREE_AFTER_A_STAIRCASE;
					// Planned here and not at the top of the next event, because this event is about to
					// be built on the far side of the staircase -- it is the new lane's first chord.
					// Deferring the plan by one left every lane's opening chord outside its own plan's
					// reach: the search could book a pad in front of any chord but that one, so a lane
					// whose opening chord was the thing that had to move came back with nothing and
					// turned wherever it stood. The turn ahead of the new lane is a different turn from
					// the one just built -- the floor and the direction of climb have both moved on --
					// so it is asked again.
					if (layout.ultra()) {
						TurnCost next = turnCost(floor, climb, floors, slabStep);
						// The pad before the staircase has already held some of the wait this event was
						// going to spend on its own repeater, so the plan is told the clock has moved on
						// by that much. Otherwise it counts columns of delay the walk will not place.
						booked = planLane(events, index, lane.pos().getX(), lane.travel().getStepX(),
							lane.travel() == forward ? farWall : nearWall,
							lane.travel() == forward ? nearWall : farWall,
							currentTime + spentPadding,
							tipSignal, columnBehindBusy, next.cells(), next.offBus(), next.stepOff(),
							next.splitCells(), climb > 0, layout,
							inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity);
						replan = false;
					}
				} else {
					// Out of floors: step the slab sideways once, and come back the way we climbed.
					// Sized from the widest chord in the whole song rather than from the lane we
					// happen to be leaving. A sideways step separates two slabs, and every floor of
					// one sits beside the matching floor of the other -- so a quiet lane at the top
					// is no promise about the chord four floors down that it would be answering for.
					//
					// And in ultra, no turn is built here at all. The route is given the two corners and
					// the walk carries straight on into them, so this event -- and every one after it
					// until the corners run out -- is laid along the turn by the ordinary machinery, as
					// a chord in a lane that happens to bend. What used to be a run of dead wire paid
					// for out of the lane's signal is now the lane, and it holds music.
					//
					// Ultra only, because the other lane modes are spaced on the promise that a turn is
					// bare: their corridors sit as close as they do precisely because nothing hangs off
					// the sideways run, and putting notes there reaches straight into the neighbour.
					if (layout.ultra()) {
						if (slabStep < 1 || slabStep > 13) {
							throw new IllegalArgumentException("Compact turn distance " + slabStep
								+ " exceeds the safe redstone range");
						}
						// The corner stands at the wall, however far short of it the lane has got. What
						// fills the gap is the chord about to be built: it opens where the walk is
						// standing, runs on into the corner and comes out the far side -- the same
						// columns covered with music instead of with the wire a pad would have laid,
						// and wire the next repeater would then have had to reach across.
						//
						// At the wall and not at the cursor. Turning where the lane happens to have got
						// to is the version that does not work: every corridor's sideways run then sits
						// at a different column, and the whole reason a turn can never reach a
						// neighbouring corridor's notes is that turns occupy the same reserved columns
						// in every corridor.
						lane = armTurn(placements, lane, depth, columns, slabStep);
						turning = true;
						// Nothing is spent on the corner itself: the wire crossing it is whatever the
						// chords standing on it lay, and each of those opens with a repeater worth
						// fifteen.
						tipSignal = pad.signal();
						// The plan belonged to the lane that has just ended. Inside the turn the walk
						// places what it can where it stands, and the next lane is planned when it
						// begins.
						booked = Map.of();
					} else {
						lane = Lane.straight(addCompactTurn(placements, lane.pos(), lane.travel(), depth,
							slabStep, currentTime), lane.travel().getOpposite(), depth);
						tipSignal = pad.signal() - turnCells;
						laneStarted = false;
						columnBehindBusy = true;
					}
					climb = -climb;
				}
			}
			if (carried) {
				// The pad's repeater has already held this event's whole wait, and everything between
				// it and here is dust. Nothing left to time it with, and nothing needed.
				currentTime = event.time();
				lane = Lane.straight(addCarriedEventModule(placements, lane.pos(), lane.travel(), depth,
					event.notes(), stepOff), lane.travel(), depth);
				lastStyle = ChordStyle.BUS;
				tipSignal = pad.signal() - turnCells - stepOff - (event.notes().size() + 1) / 2;
				// A carried bus starts where the turn left off, so its first pair of notes stands where
				// the turn's own run of powered stone does. Nothing behind the next module is free.
				columnBehindBusy = true;
				laneStarted = true;
				replan = layout.ultra();
				continue;
			}
			// Pad this lane was told to lay early rather than at its end, in front of the event's own
			// repeater so that repeater stands between it and the wall.
			int owing = booked != null ? booked.getOrDefault(index, 0) : 0;
			// Both clamps below aim the chord's far end at the wall. That is a column too far: the
			// column after the far end is where the lane hands over, so a chord landing flush leaves
			// the staircase outside the footprint. They aim a column short of it instead -- which is
			// the same place for the off-bus discount, since what earns that is nothing standing
			// between the bus and the staircase, and the staircase simply moves back with the bus.
			int padWall = wall - lane.travel().getStepX() * handoverReserve(layout);
			// Never past the wall, though. The pad is booked to land the lane flush on its wall, so a
			// booking that would carry the chord over it is a booking that has already failed at its
			// own job -- and the column it spends is the column the lane comes to rest outside by.
			// Every breach of exactly one left in Do The Dance was this: a bus of twenty that fitted
			// its eleven columns to the block, one column of pad booked in front of it, and the lane
			// a column out. The plan is worked out ahead of the walk from an arithmetic that cannot
			// see everything the walk does, so it is checked here against the one thing it must never
			// do rather than trusted.
			// Only where the pad is what carries it over. A chord that lands outside the wall with no
			// pad at all is a chord with a different problem, and taking its pad away does not fix
			// that one -- it just moves the lane, and a lane moved for no reason lands its notes
			// against somebody else's tick. Measured both ways: clamping regardless cost 11 wrong
			// notes to save 2 breaches.
			if (owing > 0 && (landingOf(lane.pos().getX(), lane.travel().getStepX(), event,
					wait - spentPadding, columnBehindBusy, wall, layout,
					inTurn(turning, leavingTurn, lane.pos(), lastCorner),
					parity).end() - padWall)
					* lane.travel().getStepX() <= 0) {
				while (owing > 0 && (landingOf(lane.pos().getX() + lane.travel().getStepX() * owing,
						lane.travel().getStepX(), event, wait - spentPadding, columnBehindBusy, wall,
						layout, inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity).end() - padWall)
						* lane.travel().getStepX() > 0) {
					owing--;
				}
			}
			// And up, where the plan asked for a pad and the walk still lands the chord short.
			//
			// The sweep and the walk do not always agree about where a chord ends -- the walk spends
			// columns on things the arithmetic did not model -- so a pad booked to land a lane flush
			// can arrive a column or two short of doing it. One column short is not a small miss here:
			// a climb taken straight off a bus skips two rungs, and what decides "off a bus" is whether
			// anything stands between the bus and the staircase. Land flush and the climb costs offBus;
			// stop one short and the lane has to cover that column with dust, which costs the column
			// *and* the discount -- turnCells instead of offBus, two blocks more than it just spent one
			// to lose. A lane that could have afforded the first cannot afford the second, so it does
			// not turn at all, lays the chord that beat it whole, and comes to rest past its wall.
			//
			// ekran read exactly that as a breach of eleven: one column short with five blocks of wire,
			// wanting one and five where landing flush wants one and three.
			//
			// Only upward from a pad the plan already asked for, and only while the wire covers it, so
			// this can move a lane onto its wall and never off it.
			// How far this loop is allowed to run past what the plan actually booked, and how much
			// wire it must leave behind it. Both were unbounded: the only brakes were the plan's own
			// landing arithmetic and "can the wire pay for one more column", so a lane standing on
			// eleven blocks of wire could spend ten of them on bare dust to buy a two-cell discount.
			// See {@link #PREPAD_GROWTH_CAP}.
			int grownFrom = owing;
			// Whether the event after this one still has somewhere to go at the booking as it stands.
			// If it is already stranded there, the growth below is not what stranded it.
			boolean strandedAlready = PREPAD_NEVER_STRANDS_THE_NEXT
				&& strandsTheEventAfter(events, index, event, owing, lane, wait - spentPadding,
					columnBehindBusy, wall, layout,
					inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity, splitCells);
			while (PREPADS_FOR_THE_OFF_BUS_DISCOUNT && owing > 0 && tipSignal >= owing + 1
					&& owing - grownFrom < PREPAD_GROWTH_CAP
					&& tipSignal - owing >= PREPAD_LEAVES_WIRE
					&& (landingOf(lane.pos().getX() + lane.travel().getStepX() * owing,
						lane.travel().getStepX(), event, wait - spentPadding, columnBehindBusy, wall,
						layout, inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity).end() - padWall)
						* lane.travel().getStepX() < 0) {
				// One column further is one column the next chord has not got. Taken only where the
				// next chord can still do something with what is left.
				if (PREPAD_NEVER_STRANDS_THE_NEXT && !strandedAlready
						&& strandsTheEventAfter(events, index, event, owing + 1, lane,
							wait - spentPadding, columnBehindBusy, wall, layout,
							inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity, splitCells)) {
					placements.padded("prepadWouldStrandTheNext");
					break;
				}
				owing++;
			}
			if (TRACE && booked != null && booked.getOrDefault(index, 0) > 0) {
				System.out.println("  PADBOOK index=" + index + " booked=" + booked.get(index) + " owing=" + owing + " tip=" + tipSignal + " at " + lane.pos().getX());
			}
			if (owing > 0) {
				Pad early = planPad(owing, tipSignal, 0, Math.max(0, wait - 1 - spentPadding));
				lane = emitPad(placements, lane, early, "padBooked");
				spentPadding += early.delaySpent();
				tipSignal = early.signal();
			}
			// One event of lookahead. If the next one will not fit after this one, this is the last
			// event of its lane, and the pad that fills the lane out to the wall is better spent in
			// front of it than behind it: in front, this event's own repeater stands between the pad
			// and the staircase and hands it a full fifteen, where behind, the pad has to be paid for
			// out of whatever the event left -- and a lane ending on a long bus has left almost
			// nothing. It also costs no time at all, where a pad behind may have to buy a repeater
			// with a tick borrowed from the wait.
			// Never in front of the first event of all, which has no wire arriving to lay dust from:
			// the head of a machine is a repeater with nothing behind it, and that is how you can tell
			// where to put the lever -- and how the reader tells where the song starts.
			// And never mid-turn, where the wire runs across the corridor and a wall means nothing.
			if (layout.ultra() && !turning && index > 0 && index + 1 < events.size()
				&& (event.notes().size() + 1) / 2 + offBus <= DUST_RANGE) {
				// Off the corner before a column of this is measured. A pad that opens with a repeater
				// cannot stand one on a corner -- the repeater moves along and dust takes the corner --
				// so a pad planned for eleven columns spends twelve, and the chord in front of it lands
				// a column past the wall it was padded to meet. That column is spent either way: this
				// walks off the corner now, where the arithmetic can see it, instead of inside
				// emitPad where it cannot. Ekran found it as two breaches of exactly one on Kick Back,
				// both on the event coming out of a turn, which is the only place a lane stands on a
				// corner with a pad still to lay.
				// Off the corner before a column of this is measured, and charged for. A pad that opens
				// with a repeater cannot stand one on a corner -- the repeater moves along and dust
				// takes the corner -- so a pad planned for eleven columns spends twelve, and the chord
				// in front of it lands a column past the wall it was padded to meet. Ekran found that
				// as two breaches of exactly one on Kick Back, both on the event coming out of a turn,
				// which is the only place a lane stands on a corner with a pad still to lay.
				// The charge is the point. Walking off the corner here and *not* taking it off the
				// wire only moves the error: the column is still spent, the next pad is still planned
				// as though it were not, and what was a breach becomes a run of sixteen. Measured both
				// ways over the library -- nine breaches traded for nine dead builds, which is the
				// wrong way round.
				// Unless the wire cannot afford it and the two-swap turn can take the corner instead.
				// That trade spends no column -- the corner ends up holding a note rather than dust --
				// so where the cell walked off here is the one that runs the wire out, the swap is the
				// difference between a lane that plays and a lane that does not. Ekran found it on the
				// one-floor build of {@code ultra-limit-two-thirties}: a chord of thirty riding a
				// turnaround, tip worth nought, and the cell spent here taking it to sixteen blocks of
				// wire with the last at nothing.
				//
				// Asked only when the tip cannot pay, and that restraint is the whole of it. The swap
				// is free in wire and not free in everything else: it moves a note onto a cell two
				// lanes touch and rebuilds the chord after it as a bus. Offered wherever it would fit,
				// it takes four and a half thousand corners the old route was walking off perfectly
				// well and buys sixty-one wrong notes in real songs for three dead builds. Offered only
				// where the alternative is dead wire, the library trades one corner net -- two taken
				// here, two given back where the layout downstream moved -- and comes out the same
				// size to the block.
				//
				// And only where the wire arrives without a repeater in front of it. The delay keeps
				// the corner intact for the swap already, but a wait long enough to want a repeater of
				// its own lays one on the way past, and then there is no corner left to trade.
				BlockPos onCorner = lane.pos();
				if (tipSignal > 0 || event.time() - currentTime - spentPadding > 4
						|| planSwapTurn(placements, lane, event.notes(), false, false) == null) {
					lane = pastAnyCorner(placements, lane);
					tipSignal -= Math.abs(lane.pos().getX() - onCorner.getX())
						+ Math.abs(lane.pos().getZ() - onCorner.getZ());
				}
				Direction travel = lane.travel();
				BlockPos cursor = lane.pos();
				int laneWall = travel == forward ? farWall : nearWall;
				// Where this event really ends and what it really leaves. Asked of a second piece of
				// arithmetic before, and that one measured every chord in the shape it was sorted into
				// rather than the shape it gets built in -- so a stacked module the walk was about to
				// drop to a bus was measured two columns long when it was going to be five, and the pad
				// laid to land it on the wall landed the lane two columns past the wall instead.
				// Asked here and not reused from the turn decision above, because the pad this lane was
				// booked to lay early has moved the cursor since, and the ticks it spent have come off
				// the wait -- so the event no longer starts where it did or carries the delay it did.
				Landing reached = landingOf(cursor.getX(), travel.getStepX(), event,
					wait - spentPadding, columnBehindBusy, laneWall, layout,
					inTurn(turning, leavingTurn, cursor, lastCorner), parity);
				int end = reached.end();
				EventGroup next = events.get(index + 1);
				int beyond = landingOf(end, travel.getStepX(), next, next.time() - event.time(),
					reached.busy(), laneWall, layout, false, parity).end()
					+ travel.getStepX() * turnReserve(next, turnCells, layout);
				// Unless the chord that will not fit can be cut across the turn, in which case the gap
				// is its to fill. A cut costs nothing and fills the columns with music; a pad fills the
				// same columns with wire and then charges the staircase for it. Padding first left the
				// lane flush against its wall with no gap left, so the cut had nothing to do and never
				// happened -- two of it in a build of a hundred and forty-six turns.
				int nextCells = (next.notes().size() + 1) / 2;
				int gap = (laneWall - end) * travel.getStepX()
					- Math.max(0, (next.time() - event.time() - 1) / 4);
				boolean cuttable = gap >= 2 && gap - 1 < nextCells
					&& nextCells + offBus + stepOffAhead <= DUST_RANGE;
				// And the next chord may simply lie across the turn, in which case the gap is its to
				// fill and filling it with wire first is exactly the mistake this pad exists to avoid.
				// Measured from where the chord in front of it ends, which is where it will start.
				boolean nextStraddles = !(above >= 0 && above < floors)
					&& straddleFits(next.notes().size(), (laneWall - end) * travel.getStepX(),
						slabStep);
				// And only when the pad behind could not have done it. Both pads fill the same gap
				// with the same columns; the one in front is preferred because this event's own
				// repeater then stands between the pad and the staircase and hands it a fresh
				// fifteen, where the pad behind is paid for out of whatever the event left. That is
				// a real reason, but it is only a reason where the event has left too little -- and
				// the pad in front was taken whenever it was available rather than whenever it was
				// needed.
				//
				// Which is not free, because a pad in front is not only wire. planPad buys a
				// repeater when dust alone will not reach, and a repeater costs a tick out of the
				// wait -- 1,071 of them over the library. Every one changes spentPadding, and so the
				// next event's wait, and so where it lands. Ekran's reading: chord one pads, chord
				// two sees the room that bought and pads in turn, and a preference cascades down the
				// lane as though it were a requirement.
				Pad behind = planPad((laneWall - end) * travel.getStepX(), reached.tip(),
					reached.style().buses() ? offBus : turnCells,
					Math.max(0, next.time() - event.time() - 1));
				boolean behindReaches = (laneWall - end) * travel.getStepX() >= 0
					&& behind.cells().size() == (laneWall - end) * travel.getStepX()
					&& behind.signal() >= (reached.style().buses() ? offBus : turnCells);
				if (!cuttable && !nextStraddles && !behindReaches
						&& (beyond > farWall || beyond < nearWall)) {
					int ahead = prePad(cursor.getX(), travel.getStepX(), event, wait - spentPadding,
						columnBehindBusy, layout, laneWall,
						(laneWall - cursor.getX()) * travel.getStepX(),
						inTurn(turning, leavingTurn, cursor, lastCorner), parity);
					// Planned like the pad behind, and for the same reason: dust in front of an event
					// spends the same wire dust behind it does, so a lane wanting a dozen columns off a
					// wire worth eight laid what it could and stopped short of the wall anyway. What is
					// kept back here is one cell rather than a staircase, for the column a stacked
					// module may take between the pad and its own repeater to land on its beat.
					Pad front = planPad(ahead, tipSignal, 1,
						Math.max(0, wait - 1 - spentPadding));
					// Counted three ways, because the guard above has already decided a front pad is
					// wanted and the two ways it can still not happen want opposite fixes. Asked for
					// nothing at all means {@link #prePad} and the run disagree about how many columns
					// short the wire is; asked for more than the wire could lay means the pad is
					// abandoned wholesale where laying what it can would still have moved the chord.
					// ekran has watched a lane die on a staircase with a gap in front of it, and this
					// says which of the two was standing in the way.
					placements.padded("padAheadWanted");
					if (ahead == 0) {
						placements.padded("padAheadAskedForNothing");
					} else if (front.cells().size() != ahead) {
						// Not "one column too long", which is what this said before planPad was read:
						// planPad never returns more cells than it is asked for, so a negative
						// difference is prePad handing back -1 for want of an exact landing.
						placements.padded(ahead < 0 ? "padAheadNoExactFit"
							: "padAheadWireShort" + Math.min(ahead - front.cells().size(), 6));
					}
					// All of it, or as much of it as the wire reaches.
					//
					// The pad behind already lays what it can -- {@link #planPad} stops where the wire
					// stops and hands back a short pad rather than none, and the comment above says why.
					// The pad in front demanded the whole thing and dropped it otherwise, which is 633
					// of 817 wanted pads over the library. Both faults are the same one: a perfect pad
					// or nothing, where a partial pad still moves the chord towards its wall and still
					// takes those columns off the run that has to cross the staircase.
					if (ahead > 0 && (front.cells().size() == ahead
							|| PREPAD_LAYS_WHAT_IT_CAN && !front.cells().isEmpty())) {
						if (front.cells().size() < ahead) {
							placements.padded("padAheadPartial");
						}
						lane = emitPad(placements, lane, front, "padAhead");
						spentPadding += front.delaySpent();
					}
				}
			}
			if (TRACE) {
				System.out.println("WALK i=" + index + " t=" + event.time() + " n="
					+ event.notes().size() + " x=" + lane.pos().getX() + " z=" + lane.pos().getZ()
					+ " travel=" + lane.travel() + " wall=" + wall + " cols=" + columns
					+ " wants=" + wantsTurn + " can=" + canTurn + " straddle=" + straddles
					+ " pad=" + pad.cells().size() + " owing=" + owing + " turning=" + turning
					+ " tip=" + tipSignal + " spent=" + spentPadding + " style=" + event.style());
			}
			BlockPos before = lane.pos();
			// A turn ends a run: the far side of a staircase is a fresh lane and wants a head of its
			// own. Only ever reached from the path rail, because the floor rail refuses to turn, so
			// the wire is already at path level and there is nothing to undo.
			// Asked again here rather than taken from the top of the event, because by this point a
			// staircase may already have been built: the lane stands somewhere else, running the
			// other way, with the other wall in front of it. The answer from before the turn is
			// about a lane that no longer exists, and measured against the old wall it is always no
			// -- which is why a run used to take several chords to come back after a floor change,
			// when it can open on the very first one. Only a flat turn is a reason to wait, and that
			// is the bending test: its sideways run lies across the way both rails go.
			int laneWall = lane.travel() == forward ? farWall : nearWall;
			if (turning || lane.bending()) {
				railPhase = -1;
			} else if (railPhase >= 0 || railOpens(events, index, lane, laneWall, layout, false,
					reserve, wait, railStackSeed(placements, lane, lastStyle, currentTime, false),
					booked)) {
				boolean opening = railPhase < 0;
				boolean fromDust = false;
				if (opening) {
					int seed = railStackSeed(placements, lane, lastStyle, currentTime, false);
					// The wait in front of the run, laid the way every other module lays it: a repeater
					// for every four ticks of it, and the remainder in the head's own. Clamping it to
					// four instead is right only while no gap exceeds four, which is true of every
					// synthetic song here and of no real one -- song of storms opened a run on a wait
					// of eight and sounded its whole first phrase early.
					SpatialDelayTrigger opener = addSpatialDelayBeforeEvent(placements, lane,
						event.time() - currentTime - spentPadding, layout.ultra());
					// Off a stacked chord only where the padding did not move the lane on: the cross
					// has to be the cell behind the trigger, and a column of wire in between puts the
					// whole thing out of reach.
					boolean offStack = seed != NO_BLANK && opener.lane().pos().equals(lane.pos());
					fromDust = !offStack;
					lane = offStack
						? addRailFromStack(placements, opener.lane(), opener.triggerDelay(), seed)
						: addRailHead(placements, opener.lane(), opener.triggerDelay(), event.time());
					railPhase = 0;
					railBlanksRunning = 0;
					railFloorCarried = false;
					// The head's own stone and the stone under its dust both go live with this chord,
					// so both rails are timed from here.
					railLive[0] = event.time();
					// The floor rail is live from whatever seeded it: this chord where a head was
					// built, and the stacked chord behind it where one was inherited.
					railLive[1] = offStack ? seed : event.time();
				} else if (railPhase == 1 && railBlank != NO_BLANK) {
					// The floor column this chord would have taken, laid empty: either the chord is a
					// three and the floor rail has no centre for it, or the pair of gaps behind it is
					// too long for one repeater. Either way the chord takes the path column after it
					// and the two rails come out swapped over.
					lane = addRailBlank(placements, lane, railBlank,
						railDelay(railLive[0], event.time()));
					railLive[1] = railBlank;
					railPhase = 0;
				}
				railBlank = NO_BLANK;
				int nextDelay;
				// Nought except where the run ends on a floor column, which is the one place it has to
				// leave something behind for the lane to read. See below.
				int handUp = 0;
				if (railPhase == 0) {
					// The path rail is the only place a run may end, so a column here only carries on
					// where the whole pair after it fits -- both its columns, and whatever the lane
					// keeps back for its turn. Measured against the wall rather than against the
					// turn's cells: those are a wire budget, and every column of a run holds a
					// repeater.
					RailPair pair = railRoom(lane, laneWall) >= 2 + reserve
						? railPairAfter(events, index, event.time(), railLive[1], placements,
							lane.ahead(1), booked)
						: null;
					// And a floor rail that keeps taking blanks is not carrying anything: a chord and a
					// blank between them cost the two columns the plain lane charges for the chord
					// alone, so a stretch of them is the head's two columns thrown away and nothing
					// gained. The run stops instead and the lane carries on plainly, which is what
					// ekran asked for -- "it can just continue if it doesn't know it will be able to
					// place a note there later".
					if (pair != null && pair.blank() && !railFloorCarried
							&& railBlanksRunning >= RAIL_BLANKS_IN_A_ROW) {
						placements.padded("railStoppedForBlanks");
						pair = null;
					}
					railBlanksRunning = pair == null || !pair.blank() ? 0 : railBlanksRunning + 1;
					nextDelay = pair == null ? 0 : railDelay(railLive[1], pair.floorTime());
					railBlank = pair != null && pair.blank() ? pair.floorTime() : NO_BLANK;
				} else {
					// A floor column always carries on: the pair it belongs to was committed to at the
					// path column behind it. Its repeater is the path rail's, measured from the path
					// rail's last anchor.
					nextDelay = railNextDelay(events, index, railLive[0]);
					// Except that it does not, and nothing said so. railNextDelay comes back nought
					// three ways -- the song ends, the next chord is too big for a rail column, or the
					// path hop is outside one to four -- and two of those leave the run ending here, on
					// a floor column. A floor column fills the floor and only lays the path cell above
					// it when it has a repeater to put there, so an ending one leaves that cell as air:
					// the lane resumes at path level, its next trigger reads the gap, and the song stops.
					// The path branch of addRailNote fills the other rail's cell when it stops for
					// exactly this reason and carries a tripwire besides. This half had neither.
					// A floor column that ends the run has to hand the path rail up before it goes.
					// It fills the floor and lays the cell above it only when it has a repeater to put
					// there, so an ending one leaves that cell as air -- and the lane resumes at path
					// level and reads exactly that cell. Measured rather than reasoned about: 182 runs
					// over the library end this way and the same 182 repeaters are left reading air,
					// one for one (OrphanRepeaterProbe).
					//
					// The repeater that closes it is timed to leave the cell live at this chord's own
					// tick, because that is what the lane's next trigger is measured from. The path
					// rail last went live two events back, so the span is this tick less that one.
					if (nextDelay == 0 && index + 1 < events.size()) {
						placements.padded("railEndedOnAFloorColumn"
							+ (railFits(events.get(index + 1)) ? "PathHop" : "ChordTooBig"));
						handUp = RAIL_HANDS_THE_PATH_UP ? railDelay(railLive[0], event.time()) : 0;
						placements.padded(handUp > 0 ? "railHandedThePathUp" : "railHandUpTooLong");
					}
				}
				if (TRACE) {
					System.out.println("RAIL t=" + event.time() + " at " + lane.pos().getX() + ","
						+ lane.pos().getY() + "," + lane.pos().getZ() + " phase=" + railPhase
						+ " opening=" + opening + " nextDelay=" + nextDelay
						+ " wall=" + laneWall + " room=" + railRoom(lane, laneWall)
						+ " travel=" + lane.travel());
				}
				// A floor column that is not a blank is the floor rail earning its keep.
					railFloorCarried |= railPhase == 1;
					lane = addRailNote(placements, lane, railPhase, event.notes(), event.time(),
					nextDelay, fromDust, handUp);
				RAIL_COLUMNS++;
				railLive[railPhase] = event.time();
				railPhase = nextDelay > 0 ? 1 - railPhase : -1;
				currentTime = event.time();
				// Every column of a run holds a repeater bar the one it opens on, so the wire never
				// runs: whatever touches the end of one is reading a block a repeater drives directly.
				// The opening column is the exception and it is not allowed to be the last, which is
				// what {@link #railOpens} asks its room for.
				tipSignal = DUST_RANGE;
				columnBehindBusy = true;
				lastStyle = ChordStyle.SMALL;
				laneStarted = true;
				leavingTurn = false;
				continue;
			}
			// What the planner would say this chord does, asked at the moment the walk is about to do
			// it. Kept as a counter rather than a fault because a gap here is not wrong in itself --
			// a nudge is decided against blocks on the ground and no arithmetic can foresee it -- but
			// every one of these is a column the plan spent somewhere the walk did not, and the two
			// disagreeing is the bug shape this file keeps producing. The corner-bus gap showed up
			// here as two columns on the opening chord of every lane leaving a flat turn.
			// Asked the same way the walk is about to ask it, or this counter reports a gap it made
			// up itself. Where a pad moves the opening the walk answers free by the other clause and
			// the cells here are not the ones it will use -- but then the pad has already made the
			// answer yes, so the two still agree.
			boolean foretoldBusy = columnBehindBusy
				&& !backPairIsFree(placements, lane, event.time());
			int foretold = layout.ultra() && !turning
				? landingOf(before.getX(), lane.travel().getStepX(), event,
					event.time() - currentTime - spentPadding, foretoldBusy, wall, layout,
					leavingTurn, parity).end()
				: Integer.MIN_VALUE;
			// Columns of dust the wait in front of this chord is going to lay anyway. A module that
			// has to shift a column to agree with the lane behind can slide inside those for
			// nothing; with none of them the shift is a fresh column. Counted so the difference
			// between the two is known before anything is built on the guess that it matters.
			int slackColumns = Math.max(0, (event.time() - currentTime - spentPadding - 1) / 4);
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, lane,
				event.time() - currentTime - spentPadding, layout.ultra());
			currentTime = event.time();
			// Already clear of any corner: the delay hands back a cell a repeater may stand on, which
			// is the one rule every repeater in the build obeys and so is applied where they are laid.
			Lane opening = trigger.lane();
			// Which way the wall at the end of this lane goes, recorded against the shape the chord
			// came out as. Ekran noticed heads seemed never to appear on a lane ending in a climb,
			// and a shape that can only be built going one way is a shape half of whose value is
			// missing -- so it is counted rather than argued about.
			boolean climbingLane = climb > 0;
			Placed placed = addChordModule(placements, opening, trigger.triggerDelay(), event,
				slackColumns,
				!columnBehindBusy || !opening.pos().equals(before)
					|| backPairIsFree(placements, opening, event.time()),
				inTurn(turning, leavingTurn, opening.pos(), lastCorner),
				turning ? Integer.MAX_VALUE
					: (wall - opening.pos().getX()) * opening.travel().getStepX(), tipSignal, layout);
			if (event.style().busHeaded()) {
				placements.padded("planStackedBusWanted" + (climbingLane ? "Climb" : "Descent"));
				placements.padded("planStackedBusGot" + placed.style()
					+ (climbingLane ? "Climb" : "Descent"));
			}
			// Where the chord did not land where the plan said it would, and why, as far as the walk
			// can tell. Almost all of it is the nudge, which already re-plans and which no arithmetic
			// could have foreseen -- it is decided against blocks on the ground. What is left is a bus
			// that had to skip an occupied slot and so came out a column or three longer than its note
			// count implies. Re-planning after those as well was tried and changed nothing measurable,
			// so this stays a counter: it is the cheapest way to notice the next time the two drift
			// apart, which is the bug shape this file keeps producing.
			if (foretold != Integer.MIN_VALUE && foretold != placed.lane().pos().getX()) {
				int off = (placed.lane().pos().getX() - foretold) * lane.travel().getStepX();
				if (TRACE) {
					System.out.println("  DRIFT t=" + event.time() + " n=" + event.notes().size()
						+ " from=" + before.getX() + " foretold=" + foretold
						+ " landed=" + placed.lane().pos().getX() + " off=" + off
						+ " style=" + placed.style() + " nudged=" + placed.nudged()
						+ " foretoldBusy=" + foretoldBusy + " busy=" + columnBehindBusy);
				}
				placements.padded("plan" + (off > 0 ? "Short" : "Long") + Math.min(Math.abs(off), 4)
					+ (placed.nudged() ? "Nudged" : "") + placed.style());
				// And re-plan on it, not only count it. Everything the plan still owes this lane is
				// owed from the column the chord actually ended in, and a plan that keeps handing out
				// pad measured from a column three back spends wire the next repeater is never told
				// about -- which is a run of sixteen and a lane that stops.
				//
				// This was tried once before and reported as changing nothing measurable. It was
				// true then: the only thing that drifted was the nudge, which already re-plans here.
				// What drifts now is the shape, because whether the pair beside a module is free is
				// answered from blocks and predicted from arithmetic, and shedding a back flank moves
				// the answer without moving the arithmetic.
				if (REPLAN_ON_DRIFT) {
					replan = layout.ultra();
				}
			}
			leavingTurn = false;
			lane = placed.lane();
			columnBehindBusy = takesTheGapBehind(placed.style(), placed.busCells());
			lastStyle = placed.style();
			// A nudge spends a column the plan was not told about, so everything the plan still owes
			// this lane is owed from a column further along than it thinks. Left alone, the lane
			// arrives carrying pad that was measured to close a gap the nudge has already closed --
			// and lands past the wall by exactly the columns nudged. Ekran found it on Big Shot at
			// thirty-six wide: a stacked chord of six nudged, four chords later a pad of one was laid
			// for a shortfall that no longer existed, and the bus behind it came to rest a column out.
			//
			// Re-planning is the answer rather than predicting the nudge, because a nudge is decided
			// against blocks already on the ground and the planner has only arithmetic. What it cannot
			// foresee it can at least be told about afterwards.
			if (placed.nudged()) {
				replan = layout.ultra();
			}
			// And whenever the ground answered a question the plan had to guess at.
			//
			// Whether the pair of slots behind a module is free is answered from blocks by the walk and
			// predicted from arithmetic by the plan, and the arithmetic is pessimistic: it says busy far
			// more often than the ground does. Where the two differ the walk builds a shape the plan did
			// not price -- a head of seven where a plain bus was booked -- and every column the plan still
			// owes this lane is owed against the wrong length.
			//
			// The drift counter above cannot see it. That compares where a chord landed against where
			// {@link #landingOf} said it would, and the walk asks landingOf with the same blocks-based
			// answer it built with, so the two agree exactly and nothing is reported. What went stale is
			// the sweep, which ran before any of this was on the ground, and nothing compares against
			// that.
			//
			// So the trigger is the disagreement itself rather than its consequences: the guess said
			// busy, the blocks said free, and from here the plan is answering about a lane that no longer
			// exists.
			if (REPLAN_WHEN_BLOCKS_DISAGREE && columnBehindBusy && !foretoldBusy) {
				placements.padded("planReplanBlocksDisagreed");
				replan = layout.ultra();
			}
			// A bus is the one module that hands the next thing along a wire rather than a block: its
			// stones are lit by the dust running over them, and that dust has been counting down since
			// the repeater at the head of it. A chord of three or fewer ends on a block the repeater
			// drives directly, which is worth the full fifteen to whatever touches it.
			// Charged at the blocks the bus actually laid, not at the blocks its note count implies.
			// A bus that had to skip slots is longer than that, and the difference is wire the next
			// repeater never gets told about.
			// A stacked module is neither. It ends on a cell of dust -- the relay its outer column
			// reads through -- and that cell is the first of the fifteen, not a free block in front of
			// them. Handing on the whole fifteen let a lane lay fifteen more cells after it and land
			// the last one at nought, which is the exact width of a dead line: sixteen blocks of wire
			// where the budget said fifteen. Ekran found it on Do The Dance.
			// A stacked module hands on the whole fifteen, exactly as a chord of three does. Its one
			// cell of dust is the cross *underneath* the centre block, feeding the two side relays;
			// the signal path over the top is repeater, centre block, next cell, and a centre block
			// driven by a repeater is a solid block strongly powered, so the cell after it reads
			// fifteen. Ekran measured it with lamps: fifteen lit from the module, in and out.
			// A stacked-bus is the one shape that is both. Its head hands on the full fifteen the way
			// any module does, and then its own transition and bus spend out of that before the next
			// chord ever sees it -- so reading it as a module, which is what {@code !BUS} used to
			// mean, told the lane it had fifteen when it had four. That is dead wire, and the sweep
			// found 260 builds of it. The same sum as {@link #landingOf}, which had it right.
			tipSignal = placed.style() == ChordStyle.BUS
				? DUST_RANGE - placed.busCells()
				: placed.style().busHeaded()
					? DUST_RANGE - STACKED_BUS_TRANSITION - placed.busCells()
					: DUST_RANGE;
			laneStarted = true;
			placedWhileTurning |= turning;
		}
	}

	/**
	 * The second layout's own walk. See {@link UltraLaneV2} for why there is one.
	 *
	 * <p>Started as a copy of {@link #walkWall} and cut down, deliberately: deleting a rule is
	 * verifiable and re-deriving one is not, and every descent geometry in this file that was derived
	 * rather than built by hand has been wrong at least once. What is gone is the decision layer --
	 * the booking search, the veto, the closing pad. What is kept is every line that knows about
	 * blocks.</p>
	 */
	static void walkV2(List<EventGroup> events, BlockPos origin, Direction forward,
			int laneWidth, int floors, PlacementPlan placements, Layout layout, WalkStart start) {
		// Where chords grow, and the direction the whole slab creeps once a sweep is done.
		Direction depth = forward.getClockWise();
		// The walk's whole position: where it stands, which way the wire is running, and any corners
		// still ahead of it. One object rather than a cursor and a heading, because a turn is now a
		// stretch of this route with two bends in it -- the walk carries on through a corner the same
		// way it carries on through anything else, and the route is what remembers that it bent.
		// Marked crowded for ultra from the very first cell. Ultra is the mode that packs lanes until
		// they touch, so no lane in one owns the ground its notes hang over: the cells beside it belong
		// to the lane before, to the turn it came round, or to the corridor alongside. Every note it
		// hangs is therefore offered rather than assumed, and a bus that finds a slot taken carries on
		// a block further and hangs it there. The other modes leave a clear column and can assume.
		// Started partway along the lane where a debug build asks for it, so a chord can be put a
		// stated distance from the wall without a song in front of it to push it there. The walls
		// below are measured from the origin and not from here, which is the whole point: the
		// corridor is the width it would be, and the walk simply begins further down it.
		Lane lane = layout.ultra()
			? Lane.straight(origin.relative(forward, start.column()), forward, depth).crowding()
			: Lane.straight(origin.relative(forward, start.column()), forward, depth);
		// Whether those bends are the ones a turn put there, so that the walk knows to re-pin the
		// note side and start a new lane the moment it comes out the far side.
		boolean turning = false;
		// And whether the chord about to be placed is the first one after coming out. The stated
		// reason was that a stacked module there is still perpendicular to the ones along the
		// sideways run it has just left -- the corner behind it rather than under it, near enough to
		// be the same problem -- so the restriction outlasts the turn by exactly one chord.
		//
		// Ekran doubts that reason and the blocks are on their side. A turn's bus comes out of the
		// second bend running the new lane's way, so by the time this chord is placed the cells
		// behind it are collinear with it, not across it: Kick Back's turn at tick 340 is nine cells,
		// bends after one and after four, so its last five run along x on the new z and the chord at
		// tick 344 follows them in line. The sideways run is three cells further back. Gated by
		// {@link #TURN_BAN_OUTLASTS} so the claim can be measured rather than argued.
		boolean leavingTurn = false;
		// The last corner the route took, for the distance form of the rule above.
		BlockPos lastCorner = null;
		// A descent is the one thing in a build that steps a column off its own centre line, and
		// it steps back the way the slabs came. The slab behind this one is climbing where this one
		// descends -- they alternate -- and a climb keeps to the centre line, so that column is the
		// one with nothing in it. Stepping the other way would put live stone against the notes of
		// the slab not yet built.
		Direction descentSide = layout.ultra() ? depth.getOpposite() : depth;
		int nearWall = origin.getX();
		int farWall = origin.getX() + laneWidth;
		int currentTime = 0;
		// Which floor the walk believes it is on and which way it is going, which together decide
		// whether the wall ahead is a climb, a descent or a flat turn. Nought and up at the head of a
		// song; anything else is a debug build asking to start in the middle of one, because the
		// shape of a wall is not a thing you can ask for directly -- you arrive at it.
		int floor = start.floor();
		int climb = start.climb();
		boolean laneStarted = false;
		/** Whether a chord has been laid on the bend the walk is currently going round. */
		boolean placedWhileTurning = false;
		// Whether the column a stacked module would want behind it is already spoken for -- either
		// by the module before it, whose relays reach into it, or by a turn, whose run of powered
		// stone lies right alongside it at the same level.
		boolean columnBehindBusy = false;
		ChordStyle lastStyle = ChordStyle.SMALL;
		// What the wire at the end of the lane is still worth. Every module opens with a repeater, so
		// this only ever counts what the module just built spent: nothing, unless it was a bus.
		int tipSignal = DUST_RANGE;
		// Which rail the next column of a run belongs to: 0 for the path, 1 for the floor, -1 for no
		// run under way. The same state walkWall keeps, ported rather than re-derived -- every
		// geometry in this file that was worked out a second time has been wrong at least once.
		int railPhase = -1;
		// When each rail last went live. Held apart because a blank swaps the rails over, and after a
		// swap they no longer line up with the event order.
		int[] railLive = new int[2];
		// The tick a blank owes the next floor column, or {@link #NO_BLANK}.
		int railBlank = NO_BLANK;
		int railBlanksRunning = 0;
		// And whether the floor rail has held a chord yet at all, rather than only blanks: that is
		// what says the run is earning the head it paid for.
		boolean railFloorCarried = false;
		LaneReach reach = laneReach(events, 0, events.size());
		int slabStep = laneSpacing(reach, reach);
		// A seeded walk may also start mid-turn, with the corners at the end of the lane already on
		// its route. That is the ordinary state of a lane in the middle of a song and the one thing a
		// run of chords cannot be written to produce: a lane arms its turn once, when it starts, and
		// a spec can only choose what stands in it afterwards. Without this the seed can put a chord
		// the right distance from a wall or in a bending lane, never both.
		if (start.turning() && layout.ultra()) {
			lane = armTurn(placements, lane, depth,
				(farWall - lane.pos().getX()) * forward.getStepX(), slabStep);
			turning = true;
		}
		// Pad this lane has to lay before it reaches its last chord, settled when the lane starts.
		// See planLane: by the time a lane finds out it cannot fill the gap in front of it, the
		// chords that could have filled it are built.
		Map<Integer, Integer> booked = Map.of();
		boolean replan = layout.ultra();
		// Anchored on the lane, not the cursor: ahead(n) from here reaches every column of
		// this lane, and a lane's travel and depth do not change once it has begun.
		ParityOracle parity = layout.ultra() ? parityOracle(placements, lane) : null;
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			// Where the last corner is, in the world, kept while the route still carries the bend --
			// once it is taken there is nothing left to ask. Bend offsets are relative and shift as
			// the lane advances, so the position has to be read now rather than reconstructed later.
			if (lane.bending()) {
				int furthest = 0;
				for (Lane.Bend bend : lane.bends()) {
					furthest = Math.max(furthest, bend.after());
				}
				lastCorner = lane.ahead(furthest).pos();
			}
			// Out the far side of a turn. The route stops bending of its own accord once the walk has
			// passed both corners, so there is nothing to count down and nothing to ask how long a turn
			// was: the moment no corner is left, this is a new lane. Its note side is re-pinned to the
			// slab's own depth, because a chord riding a corner turns with the path -- which is what
			// makes a bend nothing but more lane -- while a lane's chords all grow the same way in the
			// world, whichever way that lane happens to run.
			if (turning && !lane.bending()) {
				lane = lane.pinned(depth);
				turning = false;
				leavingTurn = TURN_BAN_OUTLASTS;
				// Unless the turn itself held music, in which case this lane has already started.
				//
				// The rule this clears is "a lane must hold something before it can end", and it is
				// there so a turn landing short does not turn again at once. A walked turn *is* lane
				// -- that is the whole of the flat-turn design -- so a bend that carried a chord has
				// already satisfied it, and clearing the flag anyway exempts the event after the bend
				// from ever noticing it does not fit. On Kick Back at twelve wide over three floors
				// that is a chord of twenty-four landing flush against the wall with the column its
				// own turn reserve asked for already spent, three blocks of wire left, and a
				// staircase wanting five: the lane cannot turn, cannot cut, and runs seven columns
				// out. Ekran found it in a vertical slice.
				laneStarted = placedWhileTurning;
				placedWhileTurning = false;
				// How far from the corner, not whether the last chord was in the bend.
				//
				// What fills the pair behind the first chord of a new lane is not the turn's own run,
				// which lies perpendicular to it. It is the chord standing *on* the turn -- the one
				// inTurn converts to a plain bus -- hanging its notes along the corner. So how far
				// that reaches is a distance, and asking "did the route just stop bending" answers a
				// question about the route rather than about the blocks. ekran: this chord is already
				// well out of the bend, and four blocks clear of a flat corner is enough for any chord
				// to stand without collisions.
				//
				// The same shape as the turn ban's own STACKED_CLEAR_OF_CORNER test, and set on the
				// one variable both the planner and the walk read, so neither can answer it
				// differently from the other.
				columnBehindBusy = true;
				replan = layout.ultra();
			}
			// Settled before the event is placed rather than after it. A turn hands back a cursor at
			// the same point along the wall the last event reached, so an event that overshoots
			// leaves the next lane starting outside the wall -- and nothing measured afterwards can
			// help, because by then the overshoot is built. Asking first costs a lane its last event
			// and keeps the wall a wall.
			TurnCost turn = turnCost(floor, climb, floors, slabStep);
			int above = turn.above();
			int turnCells = turn.cells();
			int offBus = turn.offBus();
			int wall = lane.travel() == forward ? farWall : nearWall;
			int stepOffAhead = turn.stepOff();
			int splitCells = turn.splitCells();
			if (replan) {
				parity = layout.ultra() ? parityOracle(placements, lane) : null;
				// Only worth doing ahead of a staircase. The plan's whole job is to work out how much
				// pad each chord owes so the lane arrives flush at its wall, and a lane that ends in a
				// flat turn does not need to arrive flush at anything -- the chord that meets the corner
				// carries on across it. Planning one anyway is where most of the pad in a build came
				// from: a song of nothing but chords of twenty-two, on one floor, has no staircase in it
				// at all and should lay no pad anywhere.
				// The booking search, kept as a last resort rather than the way a lane closes.
				//
				// v2 closes on a cut, and with chords capped at 25 a cut is always arithmetically
				// available. What is not always available is the *head* it needs -- a plain cut of 25 is
				// 13 cells against 11 descending and 12 climbing -- and a head can be refused for parity
				// or for the cells behind it being taken. Without this the first such refusal walked a
				// lane nine columns past its wall and the build refused itself: "13 blocks of bus plus 3
				// for the turn is too much to cut across it".
				//
				// So it stays until head refusal is fixed rather than caught, which is what the occupancy
				// model is for. What has gone from v2 is the veto -- the plan no longer forbids a cut it
				// would rather close differently -- and the lookahead pair above it.
				booked = V2_BOOKS_PADS && above >= 0 && above < floors
					? planLane(events, index, lane.pos().getX(), lane.travel().getStepX(), wall,
						lane.travel() == forward ? nearWall : farWall,
						currentTime, tipSignal,
						PLAN_ASKS_THE_BLOCKS_BEHIND
							? columnBehindBusy && !backPairIsFree(placements, lane, event.time())
							: columnBehindBusy,
						turnCells, offBus, stepOffAhead,
						splitCells, climb > 0, layout,
						inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity)
					: Map.of();
				replan = false;
			}
			// A lane that ends flush with the wall on a wire too weak to reach the top of a staircase
			// has nowhere left to stand the repeater that would revive it, and a lane that cannot turn
			// runs on past the wall instead. So an event that would leave the wire that weak is asked
			// to fit a column short, and the pad puts a repeater in the column that buys.
			// Whether what lies ahead is a flat turn rather than a staircase, and so whether it is
			// walked or crossed. A staircase is still a gap in the path that the lane must arrive flush
			// at; a flat turn is more lane, and a chord meeting one simply carries on round it.
			boolean flatAhead = !(above >= 0 && above < floors);
			// A chord small enough to lie across a flat turn needs nothing done for it at all. It is
			// built where it stands and runs on into the corner, and the turn lays whatever is left --
			// which is the same columns filled with music instead of with wire. Padding the lane out to
			// meet the turn is what makes a run too long for the repeater at the end of it to clear,
			// and it was buying nothing: the chord was going to cover that ground anyway.
			boolean straddles = layout.ultra() && flatAhead
				&& straddleFits(event.notes().size(),
					(wall - lane.pos().getX()) * lane.travel().getStepX(), slabStep);
			int reserve = turnReserve(event, offBus, layout);
			int wait = event.time() - currentTime;
			// Measured with the same arithmetic the planner uses, and not with a length taken from
			// the shape the chord was measured in. A stacked chord that finds the pair of slots
			// behind it spoken for is built as a bus instead, and a bus is longer -- so a lane could
			// be told a chord fitted, build it, and land a column past its own wall.
			// Decided once, here, and then measured from the decision.
			//
			// This is the whole of ekran's complaint about v1 in one place. A chord's shape used to be
			// answered three times: {@link #chooseStyle} guessing at grouping time, {@link #landingOf}
			// predicting from a different set of rules, and {@link #addChordModule} deciding for real
			// when it came to build. Any two of them disagreeing is a lane measured for one shape and
			// built as another, which is a lane outside its wall -- and there is no planner in v2 to
			// blame, because there is no planner in v2. The decision is the decision.
			//
			// So the walk asks {@link #shapeFor} at the column the chord will actually open on -- past
			// the delay, which is where the module's repeater goes -- and {@link #landingFrom} works out
			// where that shape ends. The same record is handed to {@link #buildShaped} below, so what is
			// built is the shape that was measured, by construction rather than by agreement.
			int delayAhead = Math.max(0, (wait - 1) / 4);
			Lane willOpenOn = lane.ahead(delayAhead);
			// The same slack the build site works out, which at this point in the lane is the whole wait
			// less the pad already spent -- nothing has been spent yet when a chord is measured.
			int slackAhead = Math.max(0, (event.time() - currentTime - 1) / 4);
			Shape shaped = shapeFor(placements, willOpenOn, event, slackAhead,
				!columnBehindBusy || delayAhead > 0
					|| backPairIsFree(placements, willOpenOn, event.time()),
				inTurn(placements, turning, leavingTurn, willOpenOn.pos(), lastCorner),
				turning ? Integer.MAX_VALUE
					: (wall - willOpenOn.pos().getX()) * lane.travel().getStepX(),
				tipSignal, layout);
			// The columns a corner takes before the module starts.
			//
			// A repeater may not stand on a corner, so every shape walks past one first -- that is what
			// {@link #pastAnyCorner} is, and it is applied inside the builders where no prediction can
			// see it. The chord is then a column or two longer than the walk measured, in the shape the
			// walk measured, which is the one disagreement deciding once cannot fix by itself: it is
			// not a disagreement about the shape, it is a term missing from the arithmetic.
			//
			// Counted the same way the builder walks it, off the same lane, so the two cannot drift.
			int cornerWalk = 0;
			for (Lane probe = willOpenOn; probe.cornerAt(0) && cornerWalk < 4; probe = probe.ahead(1)) {
				cornerWalk++;
			}
			Landing here = landingFrom(shaped,
				lane.pos().getX() + lane.travel().getStepX() * cornerWalk,
				lane.travel().getStepX(), event, wait);
			// Against the prediction v2 used to make, while both exist. The old one deliberately erred
			// towards the bus -- "the safe way round", because a lane measured long and built short
			// lands inside its wall -- so where the two differ is where that safety was being spent.
			if (TRACE_ONE_DECISION) {
				Landing was = landingOf(lane.pos().getX(), lane.travel().getStepX(), event, wait,
					columnBehindBusy, wall, layout,
					inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity);
				int drift = (here.end() - was.end()) * lane.travel().getStepX();
				if (drift != 0) {
					placements.padded("v2Landing" + (drift > 0 ? "Longer" : "Shorter")
						+ Math.abs(drift));
					placements.padded("v2LandingWas" + was.style() + "Now" + here.style());
				}
			}
			// A cell of a rail run is one column, and the column that opens one is three -- the
			// repeater, the dust, then the note. Overridden here rather than taught to
			// {@link #landingFrom}, because whether a run is under way is a fact about the walk rather
			// than about the chord: the same event opens a run in one lane and continues one in the
			// next. The same override walkWall makes, and for the same reason.
			//
			// It does mean {@link #shapeFor} has already decided a shape for a chord a rail column will
			// take instead, which puts a decision in the census that nothing built. A miscount, not a
			// misbuild -- the run lays its own notes and never asks for the shape -- but it is the one
			// place v2's "the decision is the decision" does not hold, and it is worth closing by
			// asking the rail question before the shape one.
			boolean railContinues = V2_RUNS_ON_RAILS && railPhase >= 0;
			// Two where a chord of three has landed on the floor rail, which has no centre to give it:
			// the blank column that gets it onto the path rail, and then its own.
			int railColumns = railContinues
				? railPhase == 1 && railBlank != NO_BLANK ? 2 : 1
				: V2_RUNS_ON_RAILS
					&& railOpens(events, index, lane, wall, layout, turning, reserve, wait,
						railStackSeed(placements, lane, lastStyle, currentTime, turning), booked)
					// The head's columns, its chord, and the repeater a four-tick stretch of the wait in
					// front of it costs -- the same sum the plain path makes of it.
					? railHeadColumns(railStackSeed(placements, lane, lastStyle, currentTime, turning),
						wait) + 1 + railPadColumns(wait) : 0;
			int landing = railColumns > 0
				? lane.pos().getX() + lane.travel().getStepX() * (railColumns + reserve)
				: here.end() + lane.travel().getStepX() * reserve;
			// A chord that fits and leaves the lane unable to pay for its own turn does not fit.
			//
			// This is what deciding once exposed rather than caused. While the prediction erred towards the
			// bus, every lane carried a column or two of slack it never used, and that slack was what paid
			// for the staircase. Measuring honestly spends it on music -- builds come out 2.6% shorter --
			// and the lane arrives at its wall with the wire gone.
			//
			// v1 answered this inside the pad search, with strandsNext booking a column so the lane could
			// close. v2 has no search and does not need one: it can close the lane a chord earlier and let
			// this chord open the next. No pad, no booking, and no looking further ahead than the chord in
			// hand.
			//
			// Asked of the wire the chord hands on, which is what canTurn asks for when the lane does end.
			// A lane holding nothing is exempt -- it has nothing to close on, and a lane that turned the
			// moment it opened would never lay a note.
			// Room, and not wire, is what a lane runs out of.
			//
			// v2 closes every lane by cutting the chord that reaches its wall, and a cut needs somewhere
			// to put the head that makes it: the head's own columns and the cell it hands over on. A
			// lane packed so tight that the chord reaching the wall has two columns left cannot be cut
			// at all, and a lane that cannot cut and cannot pay for a turn walks out past its wall
			// carrying the chord whole. That is every long breach on the all-25 song.
			//
			// Asked of the wire and not of the room, because the room was measured and is not it. Closing
			// a lane whose next chord would have too few columns left to cut fires 98 times on the
			// all-25 song and moves its breach count by nothing at all, and takes Guardian from 62 to
			// 95. Whatever leaves those lanes unable to close, it is not that they were one chord too
			// greedy about the columns.
			boolean strandsTheTurn = STRANDED_LANE_CLOSES_EARLY && !turning && layout.ultra()
				&& laneStarted && !flatAhead && here.tip() < turnCells;
			if (strandsTheTurn) {
				placements.padded("v2ClosedBeforeStranding");
			}
			// Never while the route is still bending. Inside a turn the wire runs across the corridor
			// rather than along it, so every one of these measurements is taken down the wrong axis --
			// and there is nothing to decide anyway, because the walk has already committed to the
			// corner it is standing in. A turn ends when the route runs out of corners, not when some
			// arithmetic about walls says so.
			// Whether this event will not fit before the wall, which is a different question from
			// whether the lane may end here.
			// Never while a run owes its next column. A run that has laid a repeater has promised the
			// column in front of it a note, and every way a lane can be interrupted takes that column
			// away: the note ends up on the far side of a staircase, a floor down and running the other
			// way, while the repeater meant to drive it stays at the wall driving the staircase.
			//
			// This invariant broke twice in v1, both times because it was said on the turn rather than
			// on the overshoot. It matters more here than there: v1 offers the cut on the overshoot and
			// v2 offers it a column earlier, on {@code reaches}, so in v2 the reach test is a live way
			// into the cut rather than a flag-gated one.
			boolean overshoots = !turning && railPhase < 0
				&& (landing > farWall || landing < nearWall || strandsTheTurn);
			// And whether it merely gets there. A chord ending on the wall, or one column short of
			// it, has not overshot and so was never offered a cut -- which leaves the lane holding a
			// long bus, no near half to cut, and a turn to pay for out of the two cells that bus left
			// it. That is the case the whole pad layer was built around. See
			// {@link #CUTS_THE_CHORD_THAT_REACHES}.
			boolean reaches = !turning && railPhase < 0
				&& (wall - landing) * lane.travel().getStepX() <= 1;
			// A lane has to hold something before it can end, or a turn that lands short would turn
			// again at once and the walk would climb the whole build without laying a note.
			boolean wantsTurn = laneStarted && overshoots;
			// Said to the builders, for the one shape that has to know. A simple tail is read by the
			// repeater standing in front of it, and a lane that turns does not put one there -- it
			// climbs, and the repeater is built at the top of the staircase, leaving the tail driving
			// nothing. ekran read exactly that off a paste at 16 wide over three floors.
			//
			// Asked of {@code reaches} and not only of {@code wantsTurn}, which is a whole event too
			// late: wantsTurn says *this* chord overshoots and so the lane turns before laying it, and
			// a tail laid now is stranded by the turn decided at the *next* event, when it is already
			// down. reaches is the same fact one event earlier -- this chord ends on the wall or a
			// column short of it, so the lane closes after it -- and it is the moment the tail can
			// still choose to be a bus.
			placements.turnAhead(wantsTurn || reaches);
			// And the other fact the chord cannot see for itself: how soon the next event arrives. A pad
			// laid in front of it can only be spent as a repeater where there are two ticks to split, so
			// a gap of one is the case a simple tail with a note-block middle cannot survive being padded
			// into. The last event has nothing after it to be padded for.
			placements.gapAhead(index + 1 < events.size()
				? events.get(index + 1).time() - event.time() : Integer.MAX_VALUE);
			// And that the two above were answered at all. Everything the simple tail's safety rests on
			// is handed down from here, and walkWall hands down none of it -- where this walk says
			// "there is a turn ahead" or "the next event is a tick away", the older one says nothing and
			// the fields read false, which is the answer that keeps the shape rather than the answer
			// that gives it up. So v1 was taking a shape whose guards were all switched off.
			placements.answersWhatIsAhead(true);
			// What the cut is offered on. The same question in v1, one column earlier in v2.
			boolean cutOffered = reaches;
			// One tick has to be left for the next event's own repeater, which is the only thing that
			// can drive the module it stands in front of.
			int columns = (wall - lane.pos().getX()) * lane.travel().getStepX();
			Pad pad = layout.ultra() && wantsTurn && !straddles
				? planTurnPad(columns, tipSignal, turnCells, offBus, Math.max(0, wait - 1),
					climb > 0, above >= 0 && above < floors,
					lastStyle.buses())
				: Pad.none(tipSignal);
			// A split comes before any of that. The event that will not fit is cut in two: as much of
			// it as reaches the wall, then the staircase, then the rest -- one repeater, one tick, one
			// chord, because dust takes no time however far it runs or however many levels it climbs.
			// It fills the lane with the music that was going to be built anyway, where a pad fills it
			// with wire it then has to pay for, so it is tried first and padding is what is left when
			// the chord is too big to cut: a run from a repeater is fifteen blocks, a staircase takes
			// four of them off a bus, and a bus carries two notes a block.
			int delayColumns = Math.max(0, (event.time() - currentTime - 1) / 4);
			int room = columns - delayColumns;
			int cells = (event.notes().size() + 1) / 2;
			// A descent lands where it cannot be built on straight away and spends a block stepping
			// off, which is a block the chord could have used.
			int stepOff = stepOffAhead;
			// Only ahead of a staircase now. A flat turn is walked rather than crossed, so a chord that
			// will not fit before it is not cut in two: the walk takes the corner and carries on laying
			// the same chord along the sideways run, which is the cut done by the ordinary machinery
			// and without a near half and a far half to keep in step.
			// The whole run and nothing more: both halves of the chord, and the staircase between
			// them, reaching from the repeater this module opens with to the next one. There is no
			// further cell to charge at the far end. A carried module hands back the cell after its
			// last bus block, and the next module stands its repeater on that cell a level up --
			// which puts the repeater against the bus, not a block short of it. This once carried a
			// cell for that gap and the gap is not there; it cost 256 lanes their wall to buy
			// nothing. Ekran built the descent by hand and counted the wire through it: eight cells
			// of bus, six of staircase, one cell more, and the last of them still reads one.
			// Asked of the overshoot and not of {@code wantsTurn}, which is the same question plus
			// "and this lane already holds something". That extra clause is there to stop a lane
			// turning the instant it opens, and it has no business here: a split *builds* -- it fills
			// the columns to the wall with the near half of the chord before it turns -- so it always
			// makes progress and can never loop. Charging it that clause meant the first event of a
			// lane could not be cut, and the first event of a lane is exactly the one that lands
			// wherever the staircase happened to put it. A chord needing eight columns opened on a
			// lane with seven and was laid anyway, a column past the wall. Ekran found it as the
			// second of two breaches on Kick Back, and it is the same exemption that put the old
			// build seven columns out.
			// Charged at what a split's own crossing costs, which is not the turn plus the step off
			// any more. A split always arrives on a bus, and a descent that may assume that is four
			// cells rather than six -- see {@link #addSplitBusDescent}. The same sum is made in
			// {@link #closes}, and the two have to be the same sum: a lane the planner closes by a
			// cut and the walk refuses to cut is a lane that runs on past its wall.
			// The head carries seven for nothing, so a chord too big to cut as a plain bus may
			// still be cuttable with one. Asked first, and the plain sum is what is left when the
			// chord cannot take a head -- too many falling instruments, no harp for the centre, or
			// no room for a head and a cell of bus before the wall.
			// What kind of thing is behind, which is not the same question as whether the column is
			// taken. That last argument says in its own docstring that it means *another stacked
			// module's centre* -- the one case where both slots behind are gone rather than one -- and
			// the walk was handing it {@code columnBehindBusy}, which is also true after a rail column,
			// after a carried chord, after a staircase landing and anywhere within reach of a corner.
			// So a cut standing behind a plain bus was refused the head of six that
			// {@link #HEAD_KEEPS_ONE_BACK_FLANK} exists to give it, fell through to a head of five,
			// could not make one of those either, and was laid whole. Both of Guardian's remaining
			// breaches at 20 wide over three floors were that: {@code last=BUS behindBusy=true},
			// {@code refused=NoHeadFromTheFront}, a chord of 24 with nine columns of room -- which a
			// head of six cuts flush, at fourteen cells of the fifteen.
			//
			// The ground cannot be asked here, and that was tried first. A head's back flanks stand in
			// the column it opens on, and a cut opens {@code splitPin} columns further along than this --
			// a pin worked out below, from the head this line is choosing. Asked at
			// {@code lane.ahead(delayColumns)} it is the wrong cell and comes back yes every time:
			// measured, it changed not one decision in 1,054 builds.
			boolean stackedIsBehind = CUT_ASKS_WHAT_KIND_IS_BEHIND
				? columnBehindBusy && lastStyle.stacked() : columnBehindBusy;
			StackedSplit headed = layout.ultra() && cutOffered && index > 0 && above >= 0
				&& above < floors
				? stackedSplitOf(event.notes(), room, splitCells, climb > 0,
					!columnBehindBusy || delayColumns > 0
						|| CUT_ASKS_THE_BLOCKS_BEHIND
							&& backPairIsFree(placements, lane.ahead(delayColumns), event.time()),
					stackedIsBehind)
				: null;
			// A cut is built straight from the module rather than through {@link #addChordModule},
			// so none of that method's guards are applied to it -- and the one that matters is the
			// parity check. Without it a head can land its low notes against a live block of the
			// lane behind, which sounds them at that lane's tick: one BELL of a hundred and fifty
			// events read twenty-one ticks early, and nothing else in the build said a word.
			//
			// Refused rather than nudged. A nudge moves the module a column and the near half is
			// measured to land on the wall exactly, so shifting it is how a cut ends up outside the
			// footprint. Giving the head up falls back to the plain sum below, which is what the
			// walk did before any of this and is always safe.
			// Two guards, and both are ones {@link #addChordModule} applies that a cut never went
			// through. The head hangs a pair of low notes in the column *behind* it, so it needs
			// that column free -- the same rule {@link #landingOf} states as reachesBack and busy.
			// Without it the head's instrument block lands in the air a note of the chord before it
			// insists on, and the build refuses outright.
			// Counted, not refused. The cut is asked for above knowing whether the column behind is
			// free, so what arrives here is already a head of five where it had to be -- and a chord
			// that could not make even that has no headed cut at all.
			if (headed != null && columnBehindBusy && delayColumns == 0) {
				placements.padded("planStackedSplitShortHead");
			}
			// ekran's rule, and the whole of it: every climb, every descent and every flat turn stands
			// at the wall. A staircase set back from it stands in a column no other corridor's turn
			// stands in, and that is what reaches into the lane alongside. The closing pad is walked out
			// to the wall for exactly this reason -- see {@link #PIN_DESCENTS} -- and the cut, which is
			// how v2 closes nearly every lane, was never held to the same rule.
			//
			// It is a headed cut every time. A plain cut sizes its near half at {@code room - 1} cells
			// and fills all of them, so it lands flush by construction; a headed cut takes what the head
			// left over, and a chord that runs out first leaves its staircase wherever the module ended.
			// Measured across both songs at sixteen sizes: 2,725 columns of it in v2 and 425 in v1, and
			// every single one a headed cut, predicted to the column by {@link StackedSplit#columns}.
			//
			// Paid for in front of the module rather than behind it. The two put the same wire in the
			// same corridor, but a pad behind the near half is spent out of the fifteen the cut opens
			// with -- which already carries the transition, both halves and the staircase -- while a pad
			// in front stands before the module's own repeater and is spent out of the run the lane is
			// already on. So the cut asked for here is the same cut in a room that much smaller: same
			// head, same halves, same wire, starting further along and ending on the wall.
			//
			// Refused rather than recessed where it will not go, because "no give at all" is the rule.
			// A cut that cannot be pinned is given up and the chord is laid whole, which is the fallback
			// that has always been there.
			int splitPin = 0;
			int splitPinBehind = 0;
			if (CUT_PINS_ITS_STAIRCASE && headed != null && room - headed.columns() > 0) {
				int gap = room - headed.columns();
				// As much of it in front as the wire there will carry, and the rest behind the near half.
				// Neither side is reliably the affordable one -- see {@link #CUT_PINS_BEHIND} -- and a
				// column is a column wherever it goes, so the gap is not one decision but two budgets to
				// spend out of in order. Taking the front first is deliberate: it leaves the cut's own
				// fifteen alone, and that fifteen is the one that also has to reach the far half.
				//
				// The front figure is the reach question the nudge asks, for the same reason: this pad is
				// laid on top of the delay in front of the module, so what has to fit inside a repeater's
				// fifteen is the run so far, the delay and the pin.
				int inFront = Math.max(0, Math.min(gap,
					DUST_RANGE - placements.runSinceRepeater() - delayColumns));
				// The same cut in a room that much smaller -- same head, same halves, same wire, opening
				// further along. Asked again rather than shifted, because a smaller room can come back
				// with a different shape and the walk has to build the shape that was priced.
				StackedSplit flush = inFront == 0 ? headed
					: stackedSplitOf(event.notes(), room - inFront, splitCells, climb > 0,
						!columnBehindBusy || delayColumns + inFront > 0
							|| CUT_ASKS_THE_BLOCKS_BEHIND
								&& backPairIsFree(placements, lane.ahead(delayColumns + inFront),
									event.time()),
						stackedIsBehind);
				// And what that shape still leaves is checked rather than assumed. A shape that overshoots
				// the smaller room is no use at all; one that falls short of it wants the rest behind.
				int behind = flush == null ? -1 : room - inFront - flush.columns();
				// Behind the near half is downstream of the module's repeater, so it comes out of the
				// fifteen the cut opens with -- which already carries the transition, both halves and the
				// staircase. Never on a shed head: a shed hands over onto the staircase's own first rung
				// instead of laying a transition cell of its own, so it is the one shape that has to touch
				// its staircase.
				if (flush != null && behind >= 0 && (behind == 0
						|| CUT_PINS_BEHIND && !flush.shed()
							&& flush.runCells(splitCells) + behind <= DUST_RANGE)) {
					placements.padded(behind == 0 ? "cutPinned" : "cutPinnedBehind");
					headed = flush;
					splitPin = inFront;
					splitPinBehind = behind;
				} else {
					// Neither budget covers it. Then the head goes and the chord is laid whole, which is a
					// lane past its wall -- a breach the build knows how to count, and the price of the rule.
					placements.padded(inFront < gap ? "cutPinPastTheWire" : "cutPinRefused");
					headed = null;
				}
			}
			// A cut whose head lands on the wrong parity is moved a column, not given up.
			//
			// It used to be given up, on the grounds that a cut's near half is measured to land
			// flush on the wall and shifting it a column puts it past. True of the shift alone --
			// but the near half is not rigid. Cut it for one column less and the pad in front makes
			// the difference up: one column of wire, a head, a transition and a bus one cell
			// shorter is exactly the same total, so the lane still comes to rest on its wall and
			// nothing the planner worked out has to change. The two notes that no longer fit the
			// near half simply ride over the staircase with the rest of the far half, which is
			// where they were always going anyway.
			//
			// A cut opens on a repeater and is handed the whole fifteen, so there is no question of
			// the wire reaching -- which is the other thing that stops an ordinary chord nudging.
			// Ekran found this on Do The Dance at forty wide over eight floors: a chord of
			// twenty-four that would not cut, and a lane five columns past its wall for want of one.
			boolean splitNudge = false;
			boolean splitClashed = false;
			if (headed != null && stackedClashes(placements, lane.ahead(delayColumns + splitPin),
					event.time(), headed.slots())) {
				splitClashed = true;
				// And the same question the chord nudge is asked: does the wire still reach. This
				// nudge lays a cell of dust where the head's repeater would have stood, on top of the
				// delay about to be laid in front of it -- so what has to fit is the run so far, the
				// delay, and the pad. ekran found this one from the blocks: the module really did
				// have to move, and moving it put its repeater a cell past the wire.
				//
				// Asked before the delay exists, so the delay is added by hand. Where a repeater
				// falls inside that delay the run resets and this is too careful by however much it
				// reset, which is the safe way to be wrong about a nudge.
				boolean splitOutOfWire = MEASURED_NUDGE_REACH
					&& placements.runSinceRepeater() + delayColumns + splitPin + 1 > DUST_RANGE;
				if (splitOutOfWire) {
					placements.padded("planSplitNudgePastTheWire");
				}
				StackedSplit shifted = !SPLIT_NUDGES || splitOutOfWire
					|| stackedClashes(placements, lane.ahead(delayColumns + splitPin + 1), event.time(),
						headed.slots())
					? null : stackedSplitOf(event.notes(), room - splitPin - 1, splitCells, climb > 0,
						!columnBehindBusy || delayColumns + splitPin + 1 > 0
							|| CUT_ASKS_THE_BLOCKS_BEHIND
								&& backPairIsFree(placements, lane.ahead(delayColumns + splitPin + 1), event.time()),
						stackedIsBehind);
				if (shifted == null) {
					// Both cells wrong, or nothing left to cut once a column is spent. Then the head
					// goes, which is what this did in every case before.
					placements.padded("planStackedSplitClashed");
					headed = null;
					// And with the head goes its pin. What follows is a plain cut, which fills the room it
					// is given and needs none.
					splitPin = 0;
					splitPinBehind = 0;
				} else {
					placements.padded("planStackedSplitNudged");
					headed = shifted;
					splitNudge = true;
				}
			}
			boolean couldSplit = layout.ultra() && cutOffered && index > 0 && above >= 0
				&& above < floors && (headed != null
					|| (room >= 2 && room - 1 < cells && cells + splitCells <= DUST_RANGE));
			// Why the head went, where losing it costs the lane its wall.
			//
			// A refused head is the commonest way a v2 lane ends up outside its wall: a chord of
			// twenty-four is twelve cells and cannot be cut plain against a staircase, so a head is the
			// only cut it has, and the trace could only ever say {@code headed=no}.
			// {@link #LAST_CUT_REFUSAL} says which of the four refusals it was, and nothing overwrites
			// it between there and here: everything that asks again asks only when a head came back.
			//
			// Counted here rather than at the call, and only where the plain cut has gone too. Every
			// chord that reaches its wall is offered a head and most are small enough to cut plain and
			// have no use for one -- counted at the call this is 14,496 refusals over the library, of
			// which the great majority cost nothing at all.
			if (!couldSplit && wantsTurn && layout.ultra() && cutOffered && index > 0
					&& above >= 0 && above < floors) {
				placements.padded("cutRefused" + LAST_CUT_REFUSAL);
				placements.padded("cutRefusedAt" + Math.min(event.notes().size(), 30) + "Notes");
			}
			// Counted where it bites rather than where it is decided. The planner books the veto on a
			// lane it is only considering, and most of those plans are thrown away; what matters is how
			// often a split the walk was about to build actually got stopped, because that is the number
			// the regression has to be paid for out of.
				// The veto stays while the booking search does, and for the same reason: the two have to
				// agree about where a lane closes. The plan books pads on the understanding that a chord
				// is cut when it overshoots; the walk cuts when it reaches. Removing the veto and keeping
				// the plan let the walk cut where the plan had not, and Guardian walked a lane five columns
				// past its wall on a chord of 14 -- a chord whose cut is 7 cells against a budget of 11.
				// Nothing was too big; the two halves were answering different questions.
				boolean vetoed = couldSplit && booked != null && booked.containsKey(NO_SPLIT - index);
				if (vetoed) {
					placements.padded("planVetoBit");
				}
				boolean split = couldSplit && !vetoed;
			// Unless leaving that tick is what stops the pad reaching the wall. Then spend the whole
			// wait on the pad and carry the event over the turn on the wire instead, which is the one
			// way a lane whose next event is a single tick away can still end where it is meant to.
			boolean carried = false;
			// Asked only when carrying is still on the table. This plans a pad that fills the lane to
			// the wall so the next chord can be carried over a staircase without a repeater -- and it
			// used to plan it whatever, then have the carry rejected further down for want of a
			// staircase to cross. The carry went away and the pad stayed, which is a lane padded flush
			// for a reason that no longer existed: four blocks of wire in front of a chord that was
			// about to lie across the corner by itself.
			if (layout.ultra() && wantsTurn && !straddles && above >= 0 && above < floors
					&& pad.cells().size() < columns) {
				Pad whole = spending(planPad(columns, tipSignal, turnCells, wait), wait);
				if (whole != null && whole.cells().size() == columns
					&& whole.signal() >= turnCells + stepOff + cells) {
					pad = whole;
					carried = true;
				}
			}
			// The old rule asked the last event whether a turn would still be in range. It answers for
			// the wire it laid and nothing else, so a lane ending on a long bus could not turn at all
			// and ran on until one ending on a short chord came along -- which is most of why the
			// staircases were scattered rather than merely off by a column. The pad can put a repeater
			// in and make the range question go away; when it cannot, the lane still has to run on.
			// And on the wall or not at all. A turn is the one thing in a build that steps off its own
			// centre line, so a turn standing anywhere else stands beside whatever that column happens
			// to hold. A lane that cannot reach its wall carries on to the next chord and tries again;
			// the only turn allowed elsewhere is one on a lane already past its wall, where carrying on
			// would never bring it back.
			boolean onWall = pad.cells().size() == columns;
			// What the wire must still be worth to take the turn. A staircase has to be crossed in one
			// run and costs its whole length; a flat turn only has to be *reached*, because the chord
			// standing on it opens with a repeater of its own that hands out a fresh fifteen. Charging
			// a walked turn as though it were a staircase is what made lanes give up and run on while a
			// perfectly good corner was two blocks away.
			// A straddling chord has nothing to reach. Its repeater goes down where the lane has got
			// to, and a repeater hands out a fresh fifteen however dead the wire arriving was, so the
			// only run that matters is the one inside the chord itself. Charging it for a staircase it
			// is not going to cross is what made a lane give up with a usable corner in front of it.
			// A flat turn may only be taken by a chord that can actually get across it. The straddle
			// test used to decide only whether to pad, which left the chord itself free to be laid
			// over both corners regardless -- and a chord of thirty cannot be: two corners cost it two
			// slots, sixteen blocks of bus, and the last of them is past what its repeater reaches. It
			// runs on instead and turns in front of a chord that fits, which breaches the footprint
			// and says so, rather than building a tail that never fires and saying nothing.
			// And where a descent is pinned, the wire has to reach the wall as well as the staircase.
			// Pinning makes the turn absolute: the lane walks out to the wall whether the pad paid for
			// those columns or not, so a lane allowed to hand over without the signal to cross them
			// hands over onto dead wire. The rule the planner has always used is the one to match --
			// {@link #closes} will only end a lane at its wall or by a cut, never on a pad that got
			// most of the way -- and a lane refused here simply lays one more chord, whose repeater
			// hands out a fresh fifteen, and turns after that. It costs footprint, which says so, and
			// not a tail that never fires, which does not.
			int unpaid = Math.max(0, columns - pad.cells().size());
			// Priced through the one place that knows whether the pad can be lifted onto the climb.
			// A lane that could not afford five may well afford three, and this is the test that
			// decides whether it turns here at all -- so it has to ask the same question the pad was
			// planned with and the same one the walk will charge itself when it builds the staircase.
			// Ungated, and it has to be: this prices the staircase the walk is about to build, and the
			// walk builds a raised pad whether or not the search was allowed to plan for one. Gating
			// it here also took the empty-pad bus discount out with it, which predates all of this.
			int turnPrice = turnPrice(pad, climb > 0, above >= 0 && above < floors,
				lastStyle.buses(), turnCells, offBus);
			// Priced after the line above, because this is the second place the same turn is priced
			// and the two were answering differently. {@link #turnPrice} knows a pad standing on a bus
			// can be lifted onto the climb and charges three; this knew only the older discount, for a
			// pad with no cells at all, and charged five for everything else -- so a lane holding four
			// and needing three was refused its turn by the arm that had not been told.
			int turnCost = unpaid == 0
				? Math.min(WALL_REACH_PRICES_THE_RAISED_PAD ? turnPrice : turnCells,
					pad.cells().isEmpty() && lastStyle.buses() ? offBus : turnCells)
				: turnCells;
			boolean reachesWall = !PIN_DESCENTS || flatAhead
				|| pad.signal() - unpaid >= turnCost;
			// A straddling chord is charged for wire it is not going to use. The comment fifteen lines
			// above already says why it should not be -- "its repeater goes down where the lane has got
			// to, and a repeater hands out a fresh fifteen however dead the wire arriving was" -- and
			// then the test asks for a block of wire anyway. See {@link #STRADDLE_NEEDS_NO_WIRE}.
			boolean straddleAffordable = STRADDLE_NEEDS_NO_WIRE || pad.signal() >= 1;
			boolean canTurn = layout.ultra()
				? index > 0 && reachesWall && (flatAhead ? straddles && straddleAffordable
					: pad.signal() >= turnPrice)
				: index > 0 && events.get(index - 1).maxSafeTurnDistance() >= MAX_LANE_SPACING;
			// A veto is a preference, not a prohibition.
			//
			// The plan forbids a cut where it has found a way to close the lane on a pad instead, so
			// that the walk -- which decides to split on its own arithmetic -- does not quietly close a
			// chord earlier than the plan did. That is the right instinct where the pad close works.
			// Where it does not, the lane has been told it may not cut *and* cannot turn, so it does
			// neither and walks out past its wall carrying the chord whole.
			//
			// ekran found one at Guardian 16 wide over four floors, {@code 20 77 148}: a chord of 24
			// at {@code x=13} with the wall at 15, {@code headed=0+18} and {@code couldSplit=true} --
			// the cut was there, already shed, and the veto threw it away for a pad of one cell with
			// four blocks of wire, which {@code reachesWall} then refused. Ten columns outside.
			//
			// Asked here rather than where {@code split} is first worked out, because this is the
			// first line at which the walk knows whether the plan's alternative is open to it.
			int spentPadding = 0;
			// Ahead of a staircase only, for the same reason a split is. A chord that would have been
			// carried across a flat turn on bare wire is now simply built on the turn.
			carried &= canTurn && !split && above >= 0 && above < floors;
			// On the wall or not at all -- except that a machine you cannot paste is a machine you
			// cannot go and look at. So a lane that will not reach its wall turns where it stands and
			// says so, on the same overlay a wrong note would appear on, naming the chord that beat it
			// and what it had left to work with. Every one of these is a thing to go and fix.
			// Counted where the lane actually hands over, which is the only moment its final extent is
			// known. A negative count is a lane that walked out past the wall its width was promised
			// at -- so the paste covers ground the player was told it would not, and that is the half
			// worth stopping to ask about rather than merely listing.
			if (layout.ultra() && wantsTurn && canTurn && columns < 0) {
				placements.breached(-columns);
			}
			// Every chord standing outside the footprint, not only the ones that asked to turn.
			// A chord that does not overshoot prints nothing on the old condition, and a lane already
			// past its wall can lay several of those in a row -- which is exactly the run ekran has
			// been reading in game and the old trace could not see.
			if (TRACE_TURNS && layout.ultra() && (wantsTurn || columns < 0)) {
				System.out.println("PAST t=" + event.time() + " notes=" + event.notes().size()
					+ " columns=" + columns + " overshoots=" + overshoots
					+ " wantsTurn=" + wantsTurn + " canTurn=" + canTurn + " turning=" + turning
					+ " laneStarted=" + laneStarted + " straddles=" + straddles
					+ " split=" + split + " carried=" + carried + " tip=" + tipSignal
					+ " flatAhead=" + flatAhead + " reachesWall=" + reachesWall
					+ " at " + lane.pos().getX() + " " + lane.pos().getY() + " "
					+ lane.pos().getZ());
			}
			if (TRACE_TURNS && layout.ultra() && wantsTurn) {
				// Why the head went, when it went. A cut is refused either because the chord cannot
				// make a head at all or because the far half would be out of reach, and the two want
				// completely different fixes.
				StackedBusSplit why = stackedBusSplit(event.notes(), true);
				String head = why == null ? "noHead"
					: "head" + why.head().size() + "+tail" + why.tail().size()
						+ "/run" + (STACKED_BUS_TRANSITION + (why.tail().size() + 1) / 2
							+ splitCells);
				// Coordinates space-separated, so the line can be pasted straight into /tp.
				System.out.println("TURN t=" + event.time() + " notes=" + event.notes().size()
					+ " at " + lane.pos().getX() + " " + lane.pos().getY() + " " + lane.pos().getZ()
					+ " wall=" + wall + " columns=" + columns
					+ " | flatAhead=" + flatAhead + " straddles=" + straddles
					+ " canTurn=" + canTurn + " onWall=" + onWall + " split=" + split
					+ " carried=" + carried
					+ " | cells=" + cells + " tip=" + tipSignal + " wait=" + wait
					+ " pad=" + pad.cells().size() + "c/" + pad.signal() + "s"
					+ " turnCells=" + turnCells + " offBus=" + offBus
					+ " last=" + lastStyle
					+ " | room=" + room + " splitCells=" + splitCells
					+ " headed=" + (headed == null ? "no"
						: headed.nearTail().size() + "+" + headed.farTail().size())
					+ " couldSplit=" + couldSplit + " vetoed=" + vetoed
					+ " reachesWall=" + reachesWall + " unpaid=" + unpaid
					+ " why=" + head + " refused=" + LAST_CUT_REFUSAL
					+ " clashed=" + splitClashed
					+ " nudged=" + splitNudge + " behindBusy=" + columnBehindBusy
					+ " delayColumns=" + delayColumns);
			}
			// And the other side of the same measurement. A lane that hands over short of its wall
			// leaves that many columns of corridor holding nothing, and puts its staircase or its
			// sideways run somewhere no other lane's is -- which is the recessed turn that reaches
			// into the neighbour it was never meant to touch. Counted in columns rather than in lanes,
			// because one lane eleven columns short and eleven lanes one column short are the same
			// number of wasted columns and nothing like the same problem. Recorded here beside the
			// breach for the reason the breach is recorded here: it is the moment the lane's extent
			// stops changing.
			if (layout.ultra() && wantsTurn && canTurn && !onWall && !split && !carried
					&& !straddles) {
				placements.trouble("a lane turned " + columns + " columns short of its wall at tick "
					+ event.time() + ", where a chord of " + event.notes().size()
					+ " would not fit: " + tipSignal + " blocks of wire and " + (wait - 1)
					+ " spare ticks to fill them with, and " + cells + " blocks of bus plus "
					+ offBus + " for the turn is too much to cut across it");
			}
			if (split) {
				Direction travel = lane.travel();
				int wallLeft = wall;
				int stepLeft = travel.getStepX();
				SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, lane,
					event.time() - currentTime);
				currentTime = event.time();
				List<EventNote> chord = busOrder(event.notes());
				int near = 2 * (room - 1);
				List<EventNote> far;
				BlockPos cursor;
				if (headed != null) {
					BlockPos opening = trigger.cursor();
					// The columns that carry the module out to its wall. Laid as the parity pad an ordinary
					// chord uses -- plain dust on the path, before the head's own repeater -- so the near
					// half ends flush and the staircase stands where every other lane's does.
					// Only the first cell of pad can be against the module behind, so only the first one
					// asks. Whichever of the two comes first is that cell -- the pin where there is one,
					// the nudge otherwise -- and the delay it spends comes off the head's own trigger.
					int headDelay = trigger.triggerDelay();
					boolean firstCell = true;
					if (splitPin > 0) {
						placements.placing("cutPin");
					}
					for (int cell = 0; cell < splitPin; cell++) {
						placements.padded("cutPin");
						headDelay = padCellOrSplitRepeater(placements, opening, travel, headDelay,
							firstCell && placements.softTip(), false, "cutPin");
						firstCell = false;
						opening = opening.relative(travel);
					}
					if (splitNudge) {
						// The column the shortened cut gave back. Laid as the parity pad an ordinary
						// chord uses, so the head starts one further along and meets the lane behind
						// on the parity it wants. Named for the marker: addParityPad does not name itself
						// and the label it would otherwise wear belongs to whatever ran before it.
						placements.placing("cutNudgePad");
						placements.padded("parity");
						headDelay = padCellOrSplitRepeater(placements, opening, travel, headDelay,
							firstCell && placements.softTip(), false, "cutNudgePad");
						firstCell = false;
						opening = opening.relative(travel);
					}
					if (headed.head().size() < STACKED_HEAD_NOTES) {
						SHORT_HEAD_CUT_AT = opening;
					}
					headed = new StackedSplit(
						onTheFreeSlots(placements, opening, travel, depth, event.time(),
							headed.slots(), headed.shed() ? DESCENT_FLANK_SLOT : -1),
						headed.head(), headed.nearTail(), headed.farTail(), headed.shed());
					cursor = addStackedSplitModule(placements, opening, travel, depth,
						headDelay, headed, event.time());
					far = headed.farTail();
					placements.padded("planStackedSplit" + (climb > 0 ? "Climb" : "Descent"));
					if (headed.nearTail().isEmpty()) {
						placements.padded("planStackedSplitHeadOnly");
						HEAD_ONLY_AT = cursor;
					}
				} else {
					cursor = addSplitEventModule(placements, trigger.cursor(), travel, depth,
						trigger.triggerDelay(), chord.subList(0, near));
					far = near < chord.size() ? chord.subList(near, chord.size()) : List.of();
				}
				// A cut chord is built here and not by {@link #addChordModule}, so none of it ever reached
				// the CHORD line -- the one place the trace says what a chord was planned as, what it came
				// out as, and why it gave the shape up. Every chord laid across a staircase was therefore
				// invisible, which is a whole class of the build: the shape that carries ten cells on one
				// floor and one on the next is exactly the shape a lane closes on. ekran pointed at one and
				// it could not be found at all, through three separate readings of the trace.
				if (TRACE) {
					System.out.println("SPLIT t=" + event.time() + " at " + trigger.cursor().getX() + ","
						+ trigger.cursor().getY() + "," + trigger.cursor().getZ() + " travel=" + travel
						+ " notes=" + chord.size() + " near=" + near + " far=" + far.size()
						+ " headed=" + (headed == null ? "no"
							: headed.head().size() + "+" + headed.nearTail().size()
								+ "/" + headed.farTail().size())
						+ " planned=" + event.style()
						+ " built=" + (headed == null ? "BUS" : "head" + headed.head().size())
						+ " depth=" + depth);
				}
				// The columns that carry the staircase out to the wall, where they could not be laid in
				// front of the module. Raised, so what the staircase starts off is what a bus leaves.
				if (splitPinBehind > 0) {
					for (int cell = 0; cell < splitPinBehind; cell++) {
						placements.padded("cutPinBehind");
					}
					cursor = emitDust(placements, Lane.straight(cursor, travel, depth), splitPinBehind,
						true, "cutPinBehind").pos();
				}
				// Measured from where the staircase actually lands, which for a split is past the near
				// half of the chord rather than where the lane stood when it decided to split.
				//
				// Told apart by the shape that left the gap, because the two have quite different causes.
				// A plain cut sizes its near half at {@code room - 1} cells and fills every one of them,
				// so it lands flush. A headed cut sizes its near half from what the chord had left over
				// after the head, and where the chord runs out first the staircase stands wherever the
				// module happened to end -- which is the recess {@link #CUT_PINS_ITS_STAIRCASE} pays for.
				int shortOfWall = ((travel == forward ? farWall : nearWall) - cursor.getX())
					* travel.getStepX();
				placements.recessed(shortOfWall);
				for (int cell = 0; cell < shortOfWall; cell++) {
					placements.padded(headed != null ? "recessedCutHeaded" : "recessedCutPlain");
				}
				// And the same number worked out from the shape rather than read off the block it landed
				// on. A recess that can be predicted can be paid for before the module is laid; one that
				// cannot has some other cause. Counted rather than asserted because it is asked of every
				// cut in the build, including the ones nothing is going to be done about.
				if (headed != null && room - splitPin - splitPinBehind - (splitNudge ? 1 : 0)
						- headed.columns() != shortOfWall) {
					placements.padded("recessMispredicted");
				}
				// The near half is a bus by construction, so the descent may take the short way down
				// and there is nothing to step off onto: the far half opens on the column the spiral
				// started from, which is exactly where the old landing plus its step off arrived.
				int splitStepOff = climb > 0 || CHEAP_SPLIT_DESCENT ? stepOff : stepOff;
				splitStepOff = climb > 0 ? stepOff : (CHEAP_SPLIT_DESCENT ? 0 : stepOff);
				cursor = climb > 0
					? addGlassClimb(placements, cursor, travel, true, currentTime)
					: CHEAP_SPLIT_DESCENT
						? addSplitBusDescent(placements, cursor, travel, descentSide, currentTime)
						: addSpiralDescent(placements, cursor, travel, descentSide, currentTime);
				floor = above;
				travel = travel.getOpposite();
				if (!far.isEmpty()) {
					cursor = addCarriedEventModule(placements, cursor, travel, depth, far,
						splitStepOff);
				}
				lane = Lane.straight(cursor, travel, depth);
				// Graded against where the lane actually opened, because every hand-derivation of this
				// arithmetic in the session that found it was off by one, in both directions. A
				// prediction the planner is going to search backwards on has to be checked against the
				// walk before it is trusted, not after.
				gradeLaneStart(placements, wallLeft, stepLeft,
					far.isEmpty() ? 0 : (far.size() + 1) / 2, climb > 0, splitStepOff,
					lane.pos().getX(), climb > 0 ? "SplitClimb" : "SplitDescent");
				lastStyle = ChordStyle.BUS;
				// The whole run, not the half of it past the staircase. Both halves are dust from the
				// one repeater this module opened with, and the near half does not stop costing wire
				// because a staircase comes after it. Counting only the far half reported three
				// blocks left on a run that had already overspent by one. The same sum {@link #closes}
				// makes, which is the point: the planner closes a lane on the promise of a split, and
				// a walk that charges the split more than the planner did refuses it and leaves the
				// lane standing short of the wall it was measured for.
				tipSignal = headed != null
					? DUST_RANGE - headed.runCells(splitCells) - splitPinBehind
					: DUST_RANGE - cells - splitCells;
				gradeLaneTip(placements, turnCells, tipSignal,
					climb > 0 ? "SplitClimb" : "SplitDescent");
				// What the far half leaves behind it, asked of the shape it was built in rather than
				// asserted.
				//
				// This said {@code true} unconditionally, on the grounds that the far half's first
				// pair of notes stands alongside the staircase's powered stone. That is a fact about
				// the far half's own position; {@code columnBehindBusy} is read by the chord *after*
				// it, and asks a different question -- whether that chord may hang notes in the pair
				// of slots behind its own repeater.
				//
				// ekran: after a cut the first chord's back flanks are always free, because a stacked
				// head is never placed at the bottom of a cut. The code agrees with them everywhere
				// else -- {@link #takesTheGapBehind} is {@code style.stacked() && ...} and the far half
				// is laid as {@link ChordStyle#BUS} four lines above -- so every other site would
				// answer false here. Two places deciding one thing, which is the bug this file keeps
				// producing.
				//
				// An empty far half is the exception and stays busy: nothing was laid after the
				// staircase, so what stands behind the next chord is the landing itself.
				columnBehindBusy = far.isEmpty()
					|| !CUT_FAR_HALF_FREES_THE_GAP && true
					|| takesTheGapBehind(ChordStyle.BUS, (far.size() + 1) / 2);
				laneStarted = true;
				replan = layout.ultra();
				continue;
			}
			if (canTurn && wantsTurn) {
				// The shape the lane actually came to rest on, against the wall it is turning at.
				// This is the question ekran asked -- not what shapes a lane holds, but what shape
				// is standing in front of the staircase when it turns.
				placements.padded("planLaneEndedOn" + lastStyle
					+ (above >= 0 && above < floors ? (climb > 0 ? "Climb" : "Descent") : "Flat"));
				// A chord carried whole to the next lane, where the lane it left had to be filled with
				// wire instead. Reported because it is the thing worth being annoyed about: every one of
				// these is a chord that could have filled those columns itself.
				if (!pad.cells().isEmpty()) {
					placements.moved(event.notes().size());
				}
				// ekran's: where the rest of the lane is nothing but wire up to the wall and then a
				// climb, run that wire at bus height. It costs the same columns and the staircase then
				// starts off a bus, which is two cells cheaper. Off a bus the run simply stays up; off
				// anything else the first cell has to hold the path so the rest has a live wire to
				// climb from, and a pad of one column has no cell to spare for that.
				int liftAfter = raiseAfter(pad, climb > 0, above >= 0 && above < floors,
					lastStyle.buses());
				boolean raisedPad = liftAfter >= 0;
				lane = emitPad(placements, lane, pad, "padClosing", liftAfter);
				spentPadding = pad.delaySpent();
				if (above >= 0 && above < floors) {
					// Asked of the shape the lane actually ended on, not of how many notes it held.
					// A big chord used to mean a bus and now may mean a stacked module, which ends
					// on its centre block a level lower -- and a climb that skips the two rungs it
					// needs starts a floor above the signal and never gets it. A pad puts the wire
					// back down on the path either way, so a padded lane never skips them.
					Direction travel = lane.travel();
					// A staircase is built where the walk is standing, so this is the one turn that can
					// be recessed. A flat turn cannot: its corner is pinned to the wall however far
					// short the lane has got, and the chords still to come fill the gap in between. A
					// staircase set back from the wall stands in a column no other corridor's turn
					// stands in, which is what reaches into the lane alongside.
					int shortBy = ((travel == forward ? farWall : nearWall)
						- lane.pos().getX()) * travel.getStepX();
					// Pinned: a descent is walked out to the wall whether the pad could afford it or
					// not, so that every descent in the build stands in the same column as every
					// other. What the pad would not pay for is laid as bare dust here, which is wire
					// the signal has to cross with nothing to revive it -- so this is the experiment
					// and the fallout is whatever the wire does about it.
					int pinned = 0;
					// Ultra only. The other lane modes share this walk once they have more than one
					// floor, and their corridors are spaced on the promise that a turn is bare -- so
					// walking one out to the wall puts powered stone where a neighbour's notes are
					// entitled to be. COMPACT_LANE read one of its own notes back wrong the moment
					// this was let loose on it.
					if (PIN_DESCENTS && layout.ultra() && shortBy > 0) {
						pinned = shortBy;
						for (int cell = 0; cell < pinned; cell++) {
							placements.padded("padPinned" + (raisedPad ? "Raised" : ""));
						}
						// At the height the pad in front of it is running at. This block is not gated
						// on the direction of the turn -- the comment below says only descents are
						// pinned and the condition does not say so -- so a climb reaches here too, and
						// a pin that came back down to the path while the staircase had been told it
						// was starting off a bus is a staircase with nothing under its first rung.
						lane = emitDust(placements, lane, pinned, raisedPad,
							raisedPad ? "pinToWallRaised" : "pinToWall");
					}
					// Recorded after the pin and not before it, so this is where the staircase actually
					// stands rather than where the lane would have left it. Told apart by direction as
					// well: only descents are pinned, so a recessed climb is a real one and a recessed
					// descent, on this branch, should not exist at all.
					placements.recessed(shortBy - pinned);
					if (shortBy - pinned > 0) {
						placements.padded(climb > 0 ? "recessedClimb" : "recessedDescent");
					}
					// A raised pad leaves exactly what a bus leaves -- stone at path+1 with dust on top,
					// one column back from here -- so the staircase joins it two rungs in just the same.
					// And a lane that ended on a bus keeps its discount through a raised pad, which it
					// never could through a pad laid on the path.
					BlockPos landed = climb > 0
						? addGlassClimb(placements, lane.pos(), travel,
							raisedPad || lastStyle.buses() && pad.cells().isEmpty(), currentTime)
						: descend(placements, lane.pos(), travel, descentSide, currentTime);
					floor = above;
					// What the staircase leaves the next lane. It matters because the next lane may
					// want to lay dust of its own before its first repeater, and a staircase is the one
					// handover in a build that spends wire without a repeater at either end of it.
					// Charged at what it actually spends: a climb taken straight off a bus skips two
					// rungs, and counting them anyway left every lane after one two blocks poorer.
					// Through the same one place canTurn asked, so the wire this lane books itself and
					// the wire it demanded before turning cannot be two different sums.
					tipSignal = pad.signal() - pinned - turnPrice(pad, climb > 0,
						above >= 0 && above < floors, lastStyle.buses(), turnCells, offBus);
					lane = Lane.straight(landed, travel.getOpposite(), depth);
					gradeLaneStart(placements, wall, travel.getStepX(), 0, climb > 0, stepOffAhead,
						lane.pos().getX(), climb > 0 ? "Climb" : "Descent");
					gradeLaneTip(placements, turnCells, tipSignal, climb > 0 ? "Climb" : "Descent");
					laneStarted = false;
					// A staircase leaves the pair beside the landing free. Only a flat turn takes it,
					// and what makes a turn take it is that it hangs notes along its own run of
					// powered stone -- not that it bends. A climb is glass and dust up two columns of
					// the lane's own centre line; a descent spirals round a two-by-two column. Both
					// carry the signal and neither hangs a note, so there is nothing there to be in
					// the way. ekran, who has read both in game.
					columnBehindBusy = !BACK_PAIR_FREE_AFTER_A_STAIRCASE;
					// Planned here and not at the top of the next event, because this event is about to
					// be built on the far side of the staircase -- it is the new lane's first chord.
					// Deferring the plan by one left every lane's opening chord outside its own plan's
					// reach: the search could book a pad in front of any chord but that one, so a lane
					// whose opening chord was the thing that had to move came back with nothing and
					// turned wherever it stood. The turn ahead of the new lane is a different turn from
					// the one just built -- the floor and the direction of climb have both moved on --
					// so it is asked again.
					if (layout.ultra()) {
						TurnCost next = turnCost(floor, climb, floors, slabStep);
						// The pad before the staircase has already held some of the wait this event was
						// going to spend on its own repeater, so the plan is told the clock has moved on
						// by that much. Otherwise it counts columns of delay the walk will not place.
						// going to spend on its own repeater, so the plan is told the clock has moved on
						// by that much. Otherwise it counts columns of delay the walk will not place.
						booked = V2_BOOKS_PADS
							? planLane(events, index, lane.pos().getX(), lane.travel().getStepX(),
								lane.travel() == forward ? farWall : nearWall,
								lane.travel() == forward ? nearWall : farWall,
								currentTime + spentPadding,
								tipSignal, columnBehindBusy, next.cells(), next.offBus(), next.stepOff(),
								next.splitCells(), climb > 0, layout,
								inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity)
							: Map.of();
						replan = false;
					}
				} else {
					// Out of floors: step the slab sideways once, and come back the way we climbed.
					// Sized from the widest chord in the whole song rather than from the lane we
					// happen to be leaving. A sideways step separates two slabs, and every floor of
					// one sits beside the matching floor of the other -- so a quiet lane at the top
					// is no promise about the chord four floors down that it would be answering for.
					//
					// And in ultra, no turn is built here at all. The route is given the two corners and
					// the walk carries straight on into them, so this event -- and every one after it
					// until the corners run out -- is laid along the turn by the ordinary machinery, as
					// a chord in a lane that happens to bend. What used to be a run of dead wire paid
					// for out of the lane's signal is now the lane, and it holds music.
					//
					// Ultra only, because the other lane modes are spaced on the promise that a turn is
					// bare: their corridors sit as close as they do precisely because nothing hangs off
					// the sideways run, and putting notes there reaches straight into the neighbour.
					if (layout.ultra()) {
						if (slabStep < 1 || slabStep > 13) {
							throw new IllegalArgumentException("Compact turn distance " + slabStep
								+ " exceeds the safe redstone range");
						}
						// The corner stands at the wall, however far short of it the lane has got. What
						// fills the gap is the chord about to be built: it opens where the walk is
						// standing, runs on into the corner and comes out the far side -- the same
						// columns covered with music instead of with the wire a pad would have laid,
						// and wire the next repeater would then have had to reach across.
						//
						// At the wall and not at the cursor. Turning where the lane happens to have got
						// to is the version that does not work: every corridor's sideways run then sits
						// at a different column, and the whole reason a turn can never reach a
						// neighbouring corridor's notes is that turns occupy the same reserved columns
						// in every corridor.
						lane = armTurn(placements, lane, depth, columns, slabStep);
						turning = true;
						// Nothing is spent on the corner itself: the wire crossing it is whatever the
						// chords standing on it lay, and each of those opens with a repeater worth
						// fifteen.
						tipSignal = pad.signal();
						// The plan belonged to the lane that has just ended. Inside the turn the walk
						// places what it can where it stands, and the next lane is planned when it
						// begins.
						booked = Map.of();
					} else {
						lane = Lane.straight(addCompactTurn(placements, lane.pos(), lane.travel(), depth,
							slabStep, currentTime), lane.travel().getOpposite(), depth);
						tipSignal = pad.signal() - turnCells;
						laneStarted = false;
						columnBehindBusy = true;
					}
					climb = -climb;
				}
			}
			if (carried) {
				// The pad's repeater has already held this event's whole wait, and everything between
				// it and here is dust. Nothing left to time it with, and nothing needed.
				currentTime = event.time();
				lane = Lane.straight(addCarriedEventModule(placements, lane.pos(), lane.travel(), depth,
					event.notes(), stepOff), lane.travel(), depth);
				lastStyle = ChordStyle.BUS;
				tipSignal = pad.signal() - turnCells - stepOff - (event.notes().size() + 1) / 2;
				// A carried bus starts where the turn left off, so its first pair of notes stands where
				// the turn's own run of powered stone does. Nothing behind the next module is free.
				columnBehindBusy = true;
				laneStarted = true;
				replan = layout.ultra();
				continue;
			}
			// Pad this lane was told to lay early rather than at its end, in front of the event's own
			// repeater so that repeater stands between it and the wall.
			int owing = booked != null ? booked.getOrDefault(index, 0) : 0;
			// Both clamps below aim the chord's far end at the wall. That is a column too far: the
			// column after the far end is where the lane hands over, so a chord landing flush leaves
			// the staircase outside the footprint. They aim a column short of it instead -- which is
			// the same place for the off-bus discount, since what earns that is nothing standing
			// between the bus and the staircase, and the staircase simply moves back with the bus.
			int padWall = wall - lane.travel().getStepX() * handoverReserve(layout);
			// Never past the wall, though. The pad is booked to land the lane flush on its wall, so a
			// booking that would carry the chord over it is a booking that has already failed at its
			// own job -- and the column it spends is the column the lane comes to rest outside by.
			// Every breach of exactly one left in Do The Dance was this: a bus of twenty that fitted
			// its eleven columns to the block, one column of pad booked in front of it, and the lane
			// a column out. The plan is worked out ahead of the walk from an arithmetic that cannot
			// see everything the walk does, so it is checked here against the one thing it must never
			// do rather than trusted.
			// Only where the pad is what carries it over. A chord that lands outside the wall with no
			// pad at all is a chord with a different problem, and taking its pad away does not fix
			// that one -- it just moves the lane, and a lane moved for no reason lands its notes
			// against somebody else's tick. Measured both ways: clamping regardless cost 11 wrong
			// notes to save 2 breaches.
			if (owing > 0 && (landingOf(lane.pos().getX(), lane.travel().getStepX(), event,
					wait - spentPadding, columnBehindBusy, wall, layout,
					inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner),
					parity).end() - padWall)
					* lane.travel().getStepX() <= 0) {
				while (owing > 0 && (landingOf(lane.pos().getX() + lane.travel().getStepX() * owing,
						lane.travel().getStepX(), event, wait - spentPadding, columnBehindBusy, wall,
						layout, inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity).end() - padWall)
						* lane.travel().getStepX() > 0) {
					owing--;
				}
			}
			// And up, where the plan asked for a pad and the walk still lands the chord short.
			//
			// The sweep and the walk do not always agree about where a chord ends -- the walk spends
			// columns on things the arithmetic did not model -- so a pad booked to land a lane flush
			// can arrive a column or two short of doing it. One column short is not a small miss here:
			// a climb taken straight off a bus skips two rungs, and what decides "off a bus" is whether
			// anything stands between the bus and the staircase. Land flush and the climb costs offBus;
			// stop one short and the lane has to cover that column with dust, which costs the column
			// *and* the discount -- turnCells instead of offBus, two blocks more than it just spent one
			// to lose. A lane that could have afforded the first cannot afford the second, so it does
			// not turn at all, lays the chord that beat it whole, and comes to rest past its wall.
			//
			// ekran read exactly that as a breach of eleven: one column short with five blocks of wire,
			// wanting one and five where landing flush wants one and three.
			//
			// Only upward from a pad the plan already asked for, and only while the wire covers it, so
			// this can move a lane onto its wall and never off it.
			// How far this loop is allowed to run past what the plan actually booked, and how much
			// wire it must leave behind it. Both were unbounded: the only brakes were the plan's own
			// landing arithmetic and "can the wire pay for one more column", so a lane standing on
			// eleven blocks of wire could spend ten of them on bare dust to buy a two-cell discount.
			// See {@link #PREPAD_GROWTH_CAP}.
			int grownFrom = owing;
			// Whether the event after this one still has somewhere to go at the booking as it stands.
			// If it is already stranded there, the growth below is not what stranded it.
			boolean strandedAlready = PREPAD_NEVER_STRANDS_THE_NEXT
				&& strandsTheEventAfter(events, index, event, owing, lane, wait - spentPadding,
					columnBehindBusy, wall, layout,
					inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity, splitCells);
			while (PREPADS_FOR_THE_OFF_BUS_DISCOUNT && owing > 0 && tipSignal >= owing + 1
					&& owing - grownFrom < PREPAD_GROWTH_CAP
					&& tipSignal - owing >= PREPAD_LEAVES_WIRE
					&& (landingOf(lane.pos().getX() + lane.travel().getStepX() * owing,
						lane.travel().getStepX(), event, wait - spentPadding, columnBehindBusy, wall,
						layout, inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity).end() - padWall)
						* lane.travel().getStepX() < 0) {
				// One column further is one column the next chord has not got. Taken only where the
				// next chord can still do something with what is left.
				if (PREPAD_NEVER_STRANDS_THE_NEXT && !strandedAlready
						&& strandsTheEventAfter(events, index, event, owing + 1, lane,
							wait - spentPadding, columnBehindBusy, wall, layout,
							inTurn(placements, turning, leavingTurn, lane.pos(), lastCorner), parity, splitCells)) {
					placements.padded("prepadWouldStrandTheNext");
					break;
				}
				owing++;
			}
			if (TRACE && booked != null && booked.getOrDefault(index, 0) > 0) {
				System.out.println("  PADBOOK index=" + index + " booked=" + booked.get(index) + " owing=" + owing + " tip=" + tipSignal + " at " + lane.pos().getX());
			}
			if (owing > 0) {
				Pad early = planPad(owing, tipSignal, 0, Math.max(0, wait - 1 - spentPadding));
				lane = emitPad(placements, lane, early, "padBooked");
				spentPadding += early.delaySpent();
				tipSignal = early.signal();
			}
			// One event of lookahead. If the next one will not fit after this one, this is the last
			// event of its lane, and the pad that fills the lane out to the wall is better spent in
			// front of it than behind it: in front, this event's own repeater stands between the pad
			// and the staircase and hands it a full fifteen, where behind, the pad has to be paid for
			// out of whatever the event left -- and a lane ending on a long bus has left almost
			// nothing. It also costs no time at all, where a pad behind may have to buy a repeater
			// with a tick borrowed from the wait.
			// Never in front of the first event of all, which has no wire arriving to lay dust from:
			// the head of a machine is a repeater with nothing behind it, and that is how you can tell
			// where to put the lever -- and how the reader tells where the song starts.
			// And never mid-turn, where the wire runs across the corridor and a wall means nothing.
			if (layout.ultra() && !turning && index > 0 && index + 1 < events.size()
				&& (event.notes().size() + 1) / 2 + offBus <= DUST_RANGE) {
				// Off the corner before a column of this is measured. A pad that opens with a repeater
				// cannot stand one on a corner -- the repeater moves along and dust takes the corner --
				// so a pad planned for eleven columns spends twelve, and the chord in front of it lands
				// a column past the wall it was padded to meet. That column is spent either way: this
				// walks off the corner now, where the arithmetic can see it, instead of inside
				// emitPad where it cannot. Ekran found it as two breaches of exactly one on Kick Back,
				// both on the event coming out of a turn, which is the only place a lane stands on a
				// corner with a pad still to lay.
				// Off the corner before a column of this is measured, and charged for. A pad that opens
				// with a repeater cannot stand one on a corner -- the repeater moves along and dust
				// takes the corner -- so a pad planned for eleven columns spends twelve, and the chord
				// in front of it lands a column past the wall it was padded to meet. Ekran found that
				// as two breaches of exactly one on Kick Back, both on the event coming out of a turn,
				// which is the only place a lane stands on a corner with a pad still to lay.
				// The charge is the point. Walking off the corner here and *not* taking it off the
				// wire only moves the error: the column is still spent, the next pad is still planned
				// as though it were not, and what was a breach becomes a run of sixteen. Measured both
				// ways over the library -- nine breaches traded for nine dead builds, which is the
				// wrong way round.
				// Unless the wire cannot afford it and the two-swap turn can take the corner instead.
				// That trade spends no column -- the corner ends up holding a note rather than dust --
				// so where the cell walked off here is the one that runs the wire out, the swap is the
				// difference between a lane that plays and a lane that does not. Ekran found it on the
				// one-floor build of {@code ultra-limit-two-thirties}: a chord of thirty riding a
				// turnaround, tip worth nought, and the cell spent here taking it to sixteen blocks of
				// wire with the last at nothing.
				//
				// Asked only when the tip cannot pay, and that restraint is the whole of it. The swap
				// is free in wire and not free in everything else: it moves a note onto a cell two
				// lanes touch and rebuilds the chord after it as a bus. Offered wherever it would fit,
				// it takes four and a half thousand corners the old route was walking off perfectly
				// well and buys sixty-one wrong notes in real songs for three dead builds. Offered only
				// where the alternative is dead wire, the library trades one corner net -- two taken
				// here, two given back where the layout downstream moved -- and comes out the same
				// size to the block.
				//
				// And only where the wire arrives without a repeater in front of it. The delay keeps
				// the corner intact for the swap already, but a wait long enough to want a repeater of
				// its own lays one on the way past, and then there is no corner left to trade.
				BlockPos onCorner = lane.pos();
				if (tipSignal > 0 || event.time() - currentTime - spentPadding > 4
						|| planSwapTurn(placements, lane, event.notes(), false, false) == null) {
					lane = pastAnyCorner(placements, lane);
					tipSignal -= Math.abs(lane.pos().getX() - onCorner.getX())
						+ Math.abs(lane.pos().getZ() - onCorner.getZ());
				}
				Direction travel = lane.travel();
				BlockPos cursor = lane.pos();
				int laneWall = travel == forward ? farWall : nearWall;
				// Where this event really ends and what it really leaves. Asked of a second piece of
				// arithmetic before, and that one measured every chord in the shape it was sorted into
				// rather than the shape it gets built in -- so a stacked module the walk was about to
				// drop to a bus was measured two columns long when it was going to be five, and the pad
				// laid to land it on the wall landed the lane two columns past the wall instead.
				// Asked here and not reused from the turn decision above, because the pad this lane was
				// booked to lay early has moved the cursor since, and the ticks it spent have come off
				// the wait -- so the event no longer starts where it did or carries the delay it did.
				Landing reached = landingOf(cursor.getX(), travel.getStepX(), event,
					wait - spentPadding, columnBehindBusy, laneWall, layout,
					inTurn(placements, turning, leavingTurn, cursor, lastCorner), parity);
				int end = reached.end();
				EventGroup next = events.get(index + 1);
				int beyond = landingOf(end, travel.getStepX(), next, next.time() - event.time(),
					reached.busy(), laneWall, layout, false, parity).end()
					+ travel.getStepX() * turnReserve(next, turnCells, layout);
				// Unless the chord that will not fit can be cut across the turn, in which case the gap
				// is its to fill. A cut costs nothing and fills the columns with music; a pad fills the
				// same columns with wire and then charges the staircase for it. Padding first left the
				// lane flush against its wall with no gap left, so the cut had nothing to do and never
				// happened -- two of it in a build of a hundred and forty-six turns.
				int nextCells = (next.notes().size() + 1) / 2;
				int gap = (laneWall - end) * travel.getStepX()
					- Math.max(0, (next.time() - event.time() - 1) / 4);
				boolean cuttable = gap >= 2 && gap - 1 < nextCells
					&& nextCells + offBus + stepOffAhead <= DUST_RANGE;
				// And the next chord may simply lie across the turn, in which case the gap is its to
				// fill and filling it with wire first is exactly the mistake this pad exists to avoid.
				// Measured from where the chord in front of it ends, which is where it will start.
				boolean nextStraddles = !(above >= 0 && above < floors)
					&& straddleFits(next.notes().size(), (laneWall - end) * travel.getStepX(),
						slabStep);
				// And only when the pad behind could not have done it. Both pads fill the same gap
				// with the same columns; the one in front is preferred because this event's own
				// repeater then stands between the pad and the staircase and hands it a fresh
				// fifteen, where the pad behind is paid for out of whatever the event left. That is
				// a real reason, but it is only a reason where the event has left too little -- and
				// the pad in front was taken whenever it was available rather than whenever it was
				// needed.
				//
				// Which is not free, because a pad in front is not only wire. planPad buys a
				// repeater when dust alone will not reach, and a repeater costs a tick out of the
				// wait -- 1,071 of them over the library. Every one changes spentPadding, and so the
				// next event's wait, and so where it lands. Ekran's reading: chord one pads, chord
				// two sees the room that bought and pads in turn, and a preference cascades down the
				// lane as though it were a requirement.
				Pad behind = planPad((laneWall - end) * travel.getStepX(), reached.tip(),
					reached.style().buses() ? offBus : turnCells,
					Math.max(0, next.time() - event.time() - 1));
				boolean behindReaches = (laneWall - end) * travel.getStepX() >= 0
					&& behind.cells().size() == (laneWall - end) * travel.getStepX()
					&& behind.signal() >= (reached.style().buses() ? offBus : turnCells);
				if (V2_PADS_AHEAD && !cuttable && !nextStraddles && !behindReaches
						&& (beyond > farWall || beyond < nearWall)) {
					int ahead = prePad(cursor.getX(), travel.getStepX(), event, wait - spentPadding,
						columnBehindBusy, layout, laneWall,
						(laneWall - cursor.getX()) * travel.getStepX(),
						inTurn(placements, turning, leavingTurn, cursor, lastCorner), parity);
					// Planned like the pad behind, and for the same reason: dust in front of an event
					// spends the same wire dust behind it does, so a lane wanting a dozen columns off a
					// wire worth eight laid what it could and stopped short of the wall anyway. What is
					// kept back here is one cell rather than a staircase, for the column a stacked
					// module may take between the pad and its own repeater to land on its beat.
					Pad front = planPad(ahead, tipSignal, 1,
						Math.max(0, wait - 1 - spentPadding));
					// Counted three ways, because the guard above has already decided a front pad is
					// wanted and the two ways it can still not happen want opposite fixes. Asked for
					// nothing at all means {@link #prePad} and the run disagree about how many columns
					// short the wire is; asked for more than the wire could lay means the pad is
					// abandoned wholesale where laying what it can would still have moved the chord.
					// ekran has watched a lane die on a staircase with a gap in front of it, and this
					// says which of the two was standing in the way.
					placements.padded("padAheadWanted");
					if (ahead == 0) {
						placements.padded("padAheadAskedForNothing");
					} else if (front.cells().size() != ahead) {
						// Not "one column too long", which is what this said before planPad was read:
						// planPad never returns more cells than it is asked for, so a negative
						// difference is prePad handing back -1 for want of an exact landing.
						placements.padded(ahead < 0 ? "padAheadNoExactFit"
							: "padAheadWireShort" + Math.min(ahead - front.cells().size(), 6));
					}
					// All of it, or as much of it as the wire reaches.
					//
					// The pad behind already lays what it can -- {@link #planPad} stops where the wire
					// stops and hands back a short pad rather than none, and the comment above says why.
					// The pad in front demanded the whole thing and dropped it otherwise, which is 633
					// of 817 wanted pads over the library. Both faults are the same one: a perfect pad
					// or nothing, where a partial pad still moves the chord towards its wall and still
					// takes those columns off the run that has to cross the staircase.
					if (ahead > 0 && (front.cells().size() == ahead
							|| PREPAD_LAYS_WHAT_IT_CAN && !front.cells().isEmpty())) {
						if (front.cells().size() < ahead) {
							placements.padded("padAheadPartial");
						}
						lane = emitPad(placements, lane, front, "padAhead");
						spentPadding += front.delaySpent();
					}
				}
			}
			if (TRACE) {
				System.out.println("WALK i=" + index + " t=" + event.time() + " n="
					+ event.notes().size() + " x=" + lane.pos().getX() + " z=" + lane.pos().getZ()
					+ " travel=" + lane.travel() + " wall=" + wall + " cols=" + columns
					+ " wants=" + wantsTurn + " can=" + canTurn + " straddle=" + straddles
					+ " pad=" + pad.cells().size() + " owing=" + owing + " turning=" + turning
					+ " tip=" + tipSignal + " spent=" + spentPadding + " style=" + event.style());
			}
			BlockPos before = lane.pos();
			// A run of small chords, one column a chord instead of two. Ported from walkWall rather
			// than re-derived: the shape is ekran's, the physics under it was measured rather than
			// reasoned about, and every geometry in this file that was worked out a second time has
			// been wrong at least once.
			//
			// A turn ends a run: the far side of a staircase is a fresh lane and wants a head of its
			// own. Only ever reached from the path rail, because the floor rail refuses to turn, so
			// the wire is already at path level and there is nothing to undo.
			// Asked again here rather than taken from the top of the event, because by this point a
			// staircase may already have been built: the lane stands somewhere else, running the
			// other way, with the other wall in front of it. The answer from before the turn is
			// about a lane that no longer exists, and measured against the old wall it is always no
			// -- which is why a run used to take several chords to come back after a floor change,
			// when it can open on the very first one. Only a flat turn is a reason to wait, and that
			// is the bending test: its sideways run lies across the way both rails go.
			int laneWall = lane.travel() == forward ? farWall : nearWall;
			if (!V2_RUNS_ON_RAILS || turning || lane.bending()) {
				railPhase = -1;
			} else if (railPhase >= 0 || railOpens(events, index, lane, laneWall, layout, false,
					reserve, wait, railStackSeed(placements, lane, lastStyle, currentTime, false),
					booked)) {
				boolean opening = railPhase < 0;
				boolean fromDust = false;
				// A stacked bus's rail-ready tail is already this run's first path column, so there is
				// nothing to open with: the floor rail's repeater goes in the free cell under that tail
				// and the run picks up on the floor rail. Asked before the head, because a head laid
				// here would be two columns spent on a column that already exists.
				// Never where the lane is closing. The tail is already down as this run's first path
				// column, so a run opened here cannot be given up later -- and a lane that closes puts
				// the repeater that would carry the run on at the top of a staircase, a floor away,
				// which is the same thing that stranded the tail one layer down. Asked of the same
				// {@code reaches} the tail asks, so the two cannot disagree about when a lane ends.
				// And asked the floor rail's own three questions about the chord it is about to lay
				// there, which is the one floor column in a run that nobody asks. Every other one is
				// committed to by {@link #railPairAfter} at the path column behind it; this one has no
				// path column behind it -- the tail is it -- so it inherited only {@link #railMayStart}'s
				// head test. That test is asked of the *head*, which is a path column, and its
				// {@code RAIL_FLOOR_SLOTS} limit is a coincidence of the two being two. The gravity rule
				// is not: a floor note hangs at the lane's own floor level, so sand under it wants
				// propping from the level below that, which is the cell the floor underneath keeps empty
				// over its own path notes. ekran read exactly that off all of the lights at 40 by 5 --
				// "it put a sand block on the lower rail, which is not allowed. then its support block
				// below collided with the mandatory air block above every noteblock".
				//
				// All three, not just the gravity one. {@link #railFloorTakes} is the wrong-note test --
				// a floor note hangs where the lane alongside hangs a stacked module's low half -- and
				// dropping it here sounds four notes at the wrong tick across three rows of
				// {@code HandoverStartProbeTest}, for 38 columns on the harp song at 40 by 5. It refuses
				// 10 of 32 openings there, which is the price. {@code railDelay} fired nought times in
				// that sweep and is here for the set to be complete: a delay out of a repeater's range
				// would reach {@link #addRailFromHandover} as {@code delay=0} and fail to parse.
				BlockPos openOffTail = RAIL_FROM_HANDOVER && opening && !reaches && !wantsTurn
						&& railHolds(event, false)
						&& railFloorTakes(placements, lane, event)
						&& railDelay(currentTime, event.time()) > 0
						&& placements.railTail() != null
						&& lane.pos().equals(placements.railTail().relative(lane.travel()))
					? placements.railTail() : null;
				if (openOffTail != null) {
					addRailFromHandover(placements, openOffTail, lane.travel(),
						railDelay(currentTime, event.time()));
					railPhase = 1;
					railBlanksRunning = 0;
					railFloorCarried = false;
					// Both rails live at the module's tick: the tail sounded with the head, and the
					// handover's stone went live with it.
					railLive[0] = currentTime;
					railLive[1] = currentTime;
					opening = false;
				} else if (opening) {
					int seed = railStackSeed(placements, lane, lastStyle, currentTime, false);
					// The wait in front of the run, laid the way every other module lays it: a repeater
					// for every four ticks of it, and the remainder in the head's own. Clamping it to
					// four instead is right only while no gap exceeds four, which is true of every
					// synthetic song here and of no real one -- song of storms opened a run on a wait
					// of eight and sounded its whole first phrase early.
					SpatialDelayTrigger opener = addSpatialDelayBeforeEvent(placements, lane,
						event.time() - currentTime - spentPadding, layout.ultra());
					// Off a stacked chord only where the padding did not move the lane on: the cross
					// has to be the cell behind the trigger, and a column of wire in between puts the
					// whole thing out of reach.
					boolean offStack = seed != NO_BLANK && opener.lane().pos().equals(lane.pos());
					fromDust = !offStack;
					lane = offStack
						? addRailFromStack(placements, opener.lane(), opener.triggerDelay(), seed)
						: addRailHead(placements, opener.lane(), opener.triggerDelay(), event.time());
					railPhase = 0;
					railBlanksRunning = 0;
					railFloorCarried = false;
					// The head's own stone and the stone under its dust both go live with this chord,
					// so both rails are timed from here.
					railLive[0] = event.time();
					// The floor rail is live from whatever seeded it: this chord where a head was
					// built, and the stacked chord behind it where one was inherited.
					railLive[1] = offStack ? seed : event.time();
				} else if (railPhase == 1 && railBlank != NO_BLANK) {
					// The floor column this chord would have taken, laid empty: either the chord is a
					// three and the floor rail has no centre for it, or the pair of gaps behind it is
					// too long for one repeater. Either way the chord takes the path column after it
					// and the two rails come out swapped over.
					lane = addRailBlank(placements, lane, railBlank,
						railDelay(railLive[0], event.time()));
					railLive[1] = railBlank;
					railPhase = 0;
				}
				railBlank = NO_BLANK;
				int nextDelay;
				// Nought except where the run ends on a floor column, which is the one place it has to
				// leave something behind for the lane to read. See below.
				int handUp = 0;
				if (railPhase == 0) {
					// The path rail is the only place a run may end, so a column here only carries on
					// where the whole pair after it fits -- both its columns, and whatever the lane
					// keeps back for its turn. Measured against the wall rather than against the
					// turn's cells: those are a wire budget, and every column of a run holds a
					// repeater.
					RailPair pair = railRoom(lane, laneWall) >= 2 + reserve
						? railPairAfter(events, index, event.time(), railLive[1], placements,
							lane.ahead(1), booked)
						: null;
					// And a floor rail that keeps taking blanks is not carrying anything: a chord and a
					// blank between them cost the two columns the plain lane charges for the chord
					// alone, so a stretch of them is the head's two columns thrown away and nothing
					// gained. The run stops instead and the lane carries on plainly, which is what
					// ekran asked for -- "it can just continue if it doesn't know it will be able to
					// place a note there later".
					if (pair != null && pair.blank() && !railFloorCarried
							&& railBlanksRunning >= RAIL_BLANKS_IN_A_ROW) {
						placements.padded("railStoppedForBlanks");
						pair = null;
					}
					railBlanksRunning = pair == null || !pair.blank() ? 0 : railBlanksRunning + 1;
					nextDelay = pair == null ? 0 : railDelay(railLive[1], pair.floorTime());
					railBlank = pair != null && pair.blank() ? pair.floorTime() : NO_BLANK;
				} else {
					// A floor column always carries on: the pair it belongs to was committed to at the
					// path column behind it. Its repeater is the path rail's, measured from the path
					// rail's last anchor.
					nextDelay = railNextDelay(events, index, railLive[0]);
					// Except that it does not, and nothing said so. railNextDelay comes back nought
					// three ways -- the song ends, the next chord is too big for a rail column, or the
					// path hop is outside one to four -- and two of those leave the run ending here, on
					// a floor column. A floor column fills the floor and only lays the path cell above
					// it when it has a repeater to put there, so an ending one leaves that cell as air:
					// the lane resumes at path level, its next trigger reads the gap, and the song stops.
					// The path branch of addRailNote fills the other rail's cell when it stops for
					// exactly this reason and carries a tripwire besides. This half had neither.
					// A floor column that ends the run has to hand the path rail up before it goes.
					// It fills the floor and lays the cell above it only when it has a repeater to put
					// there, so an ending one leaves that cell as air -- and the lane resumes at path
					// level and reads exactly that cell. Measured rather than reasoned about: 182 runs
					// over the library end this way and the same 182 repeaters are left reading air,
					// one for one (OrphanRepeaterProbe).
					//
					// The repeater that closes it is timed to leave the cell live at this chord's own
					// tick, because that is what the lane's next trigger is measured from. The path
					// rail last went live two events back, so the span is this tick less that one.
					if (nextDelay == 0 && index + 1 < events.size()) {
						placements.padded("railEndedOnAFloorColumn"
							+ (railFits(events.get(index + 1)) ? "PathHop" : "ChordTooBig"));
						handUp = RAIL_HANDS_THE_PATH_UP ? railDelay(railLive[0], event.time()) : 0;
						placements.padded(handUp > 0 ? "railHandedThePathUp" : "railHandUpTooLong");
					}
				}
				if (TRACE) {
					System.out.println("RAIL t=" + event.time() + " at " + lane.pos().getX() + ","
						+ lane.pos().getY() + "," + lane.pos().getZ() + " phase=" + railPhase
						+ " opening=" + opening + " nextDelay=" + nextDelay
						+ " wall=" + laneWall + " room=" + railRoom(lane, laneWall)
						+ " travel=" + lane.travel());
				}
				// A floor column that is not a blank is the floor rail earning its keep.
				railFloorCarried |= railPhase == 1;
				lane = addRailNote(placements, lane, railPhase, event.notes(), event.time(),
					nextDelay, fromDust, handUp);
				RAIL_COLUMNS++;
				railLive[railPhase] = event.time();
				railPhase = nextDelay > 0 ? 1 - railPhase : -1;
				currentTime = event.time();
				// Every column of a run holds a repeater bar the one it opens on, so the wire never
				// runs: whatever touches the end of one is reading a block a repeater drives directly.
				// The opening column is the exception and it is not allowed to be the last, which is
				// what {@link #railOpens} asks its room for.
				tipSignal = DUST_RANGE;
				columnBehindBusy = true;
				lastStyle = ChordStyle.SMALL;
				laneStarted = true;
				leavingTurn = false;
				continue;
			}
			// What the planner would say this chord does, asked at the moment the walk is about to do
			// it. Kept as a counter rather than a fault because a gap here is not wrong in itself --
			// a nudge is decided against blocks on the ground and no arithmetic can foresee it -- but
			// every one of these is a column the plan spent somewhere the walk did not, and the two
			// disagreeing is the bug shape this file keeps producing. The corner-bus gap showed up
			// here as two columns on the opening chord of every lane leaving a flat turn.
			// Asked the same way the walk is about to ask it, or this counter reports a gap it made
			// up itself. Where a pad moves the opening the walk answers free by the other clause and
			// the cells here are not the ones it will use -- but then the pad has already made the
			// answer yes, so the two still agree.
			boolean foretoldBusy = columnBehindBusy
				&& !backPairIsFree(placements, lane, event.time());
			int foretold = layout.ultra() && !turning
				? landingOf(before.getX(), lane.travel().getStepX(), event,
					event.time() - currentTime - spentPadding, foretoldBusy, wall, layout,
					leavingTurn, parity).end()
				: Integer.MIN_VALUE;
			// Columns of dust the wait in front of this chord is going to lay anyway. A module that
			// has to shift a column to agree with the lane behind can slide inside those for
			// nothing; with none of them the shift is a fresh column. Counted so the difference
			// between the two is known before anything is built on the guess that it matters.
			int slackColumns = Math.max(0, (event.time() - currentTime - spentPadding - 1) / 4);
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, lane,
				event.time() - currentTime - spentPadding, layout.ultra());
			currentTime = event.time();
			// Already clear of any corner: the delay hands back a cell a repeater may stand on, which
			// is the one rule every repeater in the build obeys and so is applied where they are laid.
			Lane opening = trigger.lane();
			// Which way the wall at the end of this lane goes, recorded against the shape the chord
			// came out as. Ekran noticed heads seemed never to appear on a lane ending in a climb,
			// and a shape that can only be built going one way is a shape half of whose value is
			// missing -- so it is counted rather than argued about.
			boolean climbingLane = climb > 0;
			// Placed against the blocks, not against a rule about where the walk is.
			//
			// v2 asks the permissive question first -- may this chord take the denser shape here --
			// and lets the world answer. A shape whose blocks overlap something throws, and a throw
			// here is not a broken build: it is the answer. The trial is rolled back and the chord is
			// laid again with the conservative answer, exactly as if the ban had been in force.
			//
			// This is the piece both of today's bans were standing in for. The small-chord turn ban
			// refuses 1,733 chords out of 1,733 that had a denser shape available, and the bend ban
			// refuses a stacked bus the room to wrap a corner it fits round -- and neither can simply
			// be deleted, because deleting them collides. Asking is what makes deleting them safe.
			int slack = slackColumns;
			boolean behind = !columnBehindBusy || !opening.pos().equals(before)
				|| backPairIsFree(placements, opening, event.time());
			int ahead = turning ? Integer.MAX_VALUE
				: (wall - opening.pos().getX()) * opening.travel().getStepX();
			// The shape the walk decided when it measured this chord, if the chord is still standing
			// where it was measured. Every other route to here has moved it -- a pad the lane laid, a
			// turn it took -- and a shape decided for one column is not an answer about another, so
			// those ask again from where the chord actually is.
			//
			// This is what makes the decision the decision. The measurement above and the build below
			// are one call apart rather than two rule sets apart, so the length the lane was closed on
			// is the length that goes down.
			Shape shape = opening.pos().equals(willOpenOn.pos()) && !turning
				? shaped
				: shapeFor(placements, opening, event, slack, behind,
					inTurn(placements, turning, leavingTurn, opening.pos(), lastCorner), ahead,
					tipSignal, layout);
			if (shape != shaped) {
				placements.padded("v2ShapeAskedAgain");
			}
			Placed placed;
			placements.beginTrial();
			try {
				placed = buildShaped(placements, opening, trigger.triggerDelay(), event, shape, layout);
				placements.commitTrial();
			} catch (IllegalArgumentException collided) {
				placements.rollbackTrial();
				placements.padded("v2ShapeCollidedFellBack");
				placed = addChordModule(placements, opening, trigger.triggerDelay(), event, slack,
					behind, true, ahead, tipSignal, layout);
			}
			// The disagreement the one-decision walk exists to remove, counted rather than assumed
			// gone. What the lane was measured for against what went into the ground -- and if those
			// two are ever different, the lane was closed on a length that is not the length it got.
			if (placed.style() != shape.style()) {
				placements.padded("v2Built" + placed.style() + "Decided" + shape.style());
			}
			if (placed.nudged() && !shape.nudge()) {
				placements.padded("v2NudgedAfterDeciding");
			}
			// And the length, which is the number the lane was actually closed on. The style agreeing
			// is not the same as the length agreeing: a bus that finds a note's cell taken carries the
			// run a block further and comes out longer in the shape it was measured in.
			int predicted = here.end();
			int actual = placed.lane().pos().getX();
			int over = (actual - predicted) * lane.travel().getStepX();
			if (over != 0) {
				placements.padded("v2Built" + (over > 0 ? "Longer" : "Shorter") + Math.abs(over)
					+ "Than" + shape.style());
			}
			if (event.style().busHeaded()) {
				placements.padded("planStackedBusWanted" + (climbingLane ? "Climb" : "Descent"));
				placements.padded("planStackedBusGot" + placed.style()
					+ (climbingLane ? "Climb" : "Descent"));
			}
			// Where the chord did not land where the plan said it would, and why, as far as the walk
			// can tell. Almost all of it is the nudge, which already re-plans and which no arithmetic
			// could have foreseen -- it is decided against blocks on the ground. What is left is a bus
			// that had to skip an occupied slot and so came out a column or three longer than its note
			// count implies. Re-planning after those as well was tried and changed nothing measurable,
			// so this stays a counter: it is the cheapest way to notice the next time the two drift
			// apart, which is the bug shape this file keeps producing.
			if (foretold != Integer.MIN_VALUE && foretold != placed.lane().pos().getX()) {
				int off = (placed.lane().pos().getX() - foretold) * lane.travel().getStepX();
				if (TRACE) {
					System.out.println("  DRIFT t=" + event.time() + " n=" + event.notes().size()
						+ " from=" + before.getX() + " foretold=" + foretold
						+ " landed=" + placed.lane().pos().getX() + " off=" + off
						+ " style=" + placed.style() + " nudged=" + placed.nudged()
						+ " foretoldBusy=" + foretoldBusy + " busy=" + columnBehindBusy);
				}
				placements.padded("plan" + (off > 0 ? "Short" : "Long") + Math.min(Math.abs(off), 4)
					+ (placed.nudged() ? "Nudged" : "") + placed.style());
				// And re-plan on it, not only count it. Everything the plan still owes this lane is
				// owed from the column the chord actually ended in, and a plan that keeps handing out
				// pad measured from a column three back spends wire the next repeater is never told
				// about -- which is a run of sixteen and a lane that stops.
				//
				// This was tried once before and reported as changing nothing measurable. It was
				// true then: the only thing that drifted was the nudge, which already re-plans here.
				// What drifts now is the shape, because whether the pair beside a module is free is
				// answered from blocks and predicted from arithmetic, and shedding a back flank moves
				// the answer without moving the arithmetic.
				if (REPLAN_ON_DRIFT) {
					replan = layout.ultra();
				}
			}
			leavingTurn = false;
			lane = placed.lane();
			columnBehindBusy = takesTheGapBehind(placed.style(), placed.busCells());
			lastStyle = placed.style();
			// A nudge spends a column the plan was not told about, so everything the plan still owes
			// this lane is owed from a column further along than it thinks. Left alone, the lane
			// arrives carrying pad that was measured to close a gap the nudge has already closed --
			// and lands past the wall by exactly the columns nudged. Ekran found it on Big Shot at
			// thirty-six wide: a stacked chord of six nudged, four chords later a pad of one was laid
			// for a shortfall that no longer existed, and the bus behind it came to rest a column out.
			//
			// Re-planning is the answer rather than predicting the nudge, because a nudge is decided
			// against blocks already on the ground and the planner has only arithmetic. What it cannot
			// foresee it can at least be told about afterwards.
			if (placed.nudged()) {
				replan = layout.ultra();
			}
			// And whenever the ground answered a question the plan had to guess at.
			//
			// Whether the pair of slots behind a module is free is answered from blocks by the walk and
			// predicted from arithmetic by the plan, and the arithmetic is pessimistic: it says busy far
			// more often than the ground does. Where the two differ the walk builds a shape the plan did
			// not price -- a head of seven where a plain bus was booked -- and every column the plan still
			// owes this lane is owed against the wrong length.
			//
			// The drift counter above cannot see it. That compares where a chord landed against where
			// {@link #landingOf} said it would, and the walk asks landingOf with the same blocks-based
			// answer it built with, so the two agree exactly and nothing is reported. What went stale is
			// the sweep, which ran before any of this was on the ground, and nothing compares against
			// that.
			//
			// So the trigger is the disagreement itself rather than its consequences: the guess said
			// busy, the blocks said free, and from here the plan is answering about a lane that no longer
			// exists.
			if (REPLAN_WHEN_BLOCKS_DISAGREE && columnBehindBusy && !foretoldBusy) {
				placements.padded("planReplanBlocksDisagreed");
				replan = layout.ultra();
			}
			// A bus is the one module that hands the next thing along a wire rather than a block: its
			// stones are lit by the dust running over them, and that dust has been counting down since
			// the repeater at the head of it. A chord of three or fewer ends on a block the repeater
			// drives directly, which is worth the full fifteen to whatever touches it.
			// Charged at the blocks the bus actually laid, not at the blocks its note count implies.
			// A bus that had to skip slots is longer than that, and the difference is wire the next
			// repeater never gets told about.
			// A stacked module is neither. It ends on a cell of dust -- the relay its outer column
			// reads through -- and that cell is the first of the fifteen, not a free block in front of
			// them. Handing on the whole fifteen let a lane lay fifteen more cells after it and land
			// the last one at nought, which is the exact width of a dead line: sixteen blocks of wire
			// where the budget said fifteen. Ekran found it on Do The Dance.
			// A stacked module hands on the whole fifteen, exactly as a chord of three does. Its one
			// cell of dust is the cross *underneath* the centre block, feeding the two side relays;
			// the signal path over the top is repeater, centre block, next cell, and a centre block
			// driven by a repeater is a solid block strongly powered, so the cell after it reads
			// fifteen. Ekran measured it with lamps: fifteen lit from the module, in and out.
			// A stacked-bus is the one shape that is both. Its head hands on the full fifteen the way
			// any module does, and then its own transition and bus spend out of that before the next
			// chord ever sees it -- so reading it as a module, which is what {@code !BUS} used to
			// mean, told the lane it had fifteen when it had four. That is dead wire, and the sweep
			// found 260 builds of it. The same sum as {@link #landingOf}, which had it right.
			tipSignal = placed.style() == ChordStyle.BUS
				? DUST_RANGE - placed.busCells()
				: placed.style().busHeaded()
					? DUST_RANGE - STACKED_BUS_TRANSITION - placed.busCells()
					: DUST_RANGE;
			laneStarted = true;
			placedWhileTurning |= turning;
		}
	}

	/**
	 * The column a lane keeps back for an event that will leave the wire too weak to turn.
	 *
	 * <p>A lane that ends flush with the wall on a dead wire has nowhere left to stand the repeater
	 * that would revive it. Asked in both places a lane's end is worked out -- of the event about to
	 * be placed, and of the next one when deciding whether this is the last -- because when the two
	 * disagreed, the lookahead saw an event fitting that the walk then refused, and the pad that
	 * should have gone in front of the last event was never laid.</p>
	 */
	private static int turnReserve(EventGroup event, int turnCells, Layout layout) {
		// The handover column, which every chord needs and only a dead-wire bus was ever charged for.
		// A chord whose last cell lands on the wall exactly has fitted by this method's old answer and
		// left the lane standing a column outside it, which is a breach nothing asked about.
		if (handoverReserve(layout) > 0) {
			return 1;
		}
		return layout.ultra() && event.style() == ChordStyle.BUS
			&& DUST_RANGE - (event.notes().size() + 1) / 2 < turnCells ? 1 : 0;
	}

	/**
	 * The column a lane hands over into, owed by every chord whatever its wire is worth.
	 *
	 * <p>Read in the three places that decide where a chord ends: the fit test through
	 * {@link #turnReserve}, and the two pads that aim a chord at the wall on purpose --
	 * {@link #prePad} and the booked-pad clamps. They are one rule, and a build that closed only some
	 * of those doors was measurably worse than closing none: see {@link #RESERVES_THE_HANDOVER_COLUMN}.
	 * </p>
	 */
	private static int handoverReserve(Layout layout) {
		return RESERVES_THE_HANDOVER_COLUMN && layout.ultra() ? 1 : 0;
	}

	/**
	 * What the turn at the end of the lane the walk is in will cost it, in cells and in columns.
	 *
	 * <p>Asked in two places now: at the top of each event, and again the moment a turn has been
	 * built, because the lane on the far side of a staircase is a different lane with a different
	 * turn ahead of it -- and its first chord is placed before the walk comes round again.</p>
	 *
	 * @param cells what the turn spends of the wire arriving at it. A staircase and a sideways slab
	 *     step are not the same price, and which is coming has to be known before the pad is
	 *     planned, since the pad is what has to leave enough.
	 * @param offBus the same for a lane ending on a bus, which is two cells cheaper at a climb: a
	 *     bus already carries its wire a block above the path, so the rung it would have stepped off
	 *     and the first rung itself are both already there. Worth counting apart from the dearer
	 *     turns rather than rounding up to them -- a lane ending on a bus is exactly the lane with no
	 *     wire to spare, and those two cells are four notes of chord that can straddle the turn
	 *     instead of stopping short of it. A descent is not the climb upside down and gets no such
	 *     discount: to go down four levels its wire has to stand on five of them.
	 * @param stepOff columns in front of a descent's landing that belong to its own spiral. One, not
	 *     two: the builder refuses outright at nought because the stone is already there, but the
	 *     second column was bought to answer three wrong notes that turned out to be a lane landing
	 *     past its wall, and it had been paid for at every descent since.
	 */
	/**
	 * What a split's staircase costs the one repeater both halves run off, turn and step off
	 * together.
	 *
	 * <p>Counted apart from {@link #offBus} and {@link #stepOff} because a split is the one crossing
	 * that always arrives on a bus, and a bus runs a level above its lane. A climb was already
	 * getting the good of that -- it skips two rungs and steps off nowhere, so three. A descent was
	 * not: it paid five and a step off, because {@link #addSpiralDescent} has to serve a lane
	 * arriving on anything at all. {@link #addSplitBusDescent} is the version that may assume the
	 * bus, and it is four.</p>
	 */
	private record TurnCost(int above, int cells, int offBus, int stepOff, int splitCells) {
	}

	/**
	 * Cells a split spends crossing a descent, given the far half opens where it always did.
	 *
	 * <p>Four, and every one of them is a rung: the wire leaves the bus a level up, drops through
	 * the four rungs of the spiral, and the far half's own bus meets the last of them level and
	 * one across. Nothing is spent meeting the lane it came from or stepping off onto the one it
	 * lands on, which is what the other two used to be.</p>
	 */
	private static final int SPLIT_DESCENT_CELLS = 4;

	/**
	 * Experimental: one descent cost everywhere, four cells, rather than four for a cut and five
	 * plus a step off for an ordinary turn.
	 *
	 * <p>ekran's, and the reasoning is theirs: the two descents differ only in where the wire
	 * arrives. {@link #addSplitBusDescent} may assume a bus, which runs a level above its lane, so
	 * its first rung connects on its own and its landing is the column the spiral started from.
	 * {@link #addSpiralDescent} serves a lane arriving at lane level, so it spends one cell standing
	 * on that level to meet it and one more stepping back off the column it lands past.</p>
	 *
	 * <p>But the cheap spiral's first rung is a powered stone with wire on top of it, which is
	 * exactly a cell of bus -- so putting the handover where a bus would start should let every
	 * descent take the short way down. If that holds, the flush-chord problem that costs Kick Back
	 * its wall stops existing rather than needing a special case.</p>
	 *
	 * <p>Untested against a world. Every derivation of descent geometry in this file that was not
	 * built by hand has been wrong at least once.</p>
	 */
	static boolean UNIVERSAL_FOUR_DESCENT = true;

	/**
	 * Experimental: the descent's last rung stands beside the landing rather than below it.
	 *
	 * <p>ekran's reading of the two diagrams. The spiral currently drops until its dust is level with
	 * the repeater and feeds it from behind; a repeater takes its input from the block behind it, and
	 * dust on top of that block powers it just as well. So the last stone can sit at the landing's
	 * own level with its dust one up.</p>
	 *
	 * <p>Worth trying against two separate faults at once. The four-cell descent does not conduct,
	 * and the run that FRONT_ONLY_CUTS overspends does so by exactly one cell -- sixteen dust
	 * reaching a repeater at Guardian w24 f5, 48 65 88. If that cell is this cell, one change
	 * settles both.</p>
	 */
	static boolean LAST_RUNG_ON_THE_BLOCK = true;

	/** Off puts the old six-cell spiral back, so the two can be dumped side by side. */
	static boolean CHEAP_SPLIT_DESCENT = true;

	/**
	 * Gives a route the two corners of the turn at the end of its lane, and marks them as corners.
	 *
	 * <p>Pulled out because the walk is no longer the only thing that arms a turn: a seeded walk has
	 * to arm one exactly the way this does, or every chord in the lane is measured against a wall in
	 * the wrong place. Two pieces of code deciding where a corner goes is the bug this file keeps
	 * producing, so there is one.</p>
	 */
	private static Lane armTurn(PlacementPlan placements, Lane lane, Direction depth, int columns,
			int slabStep) {
		placements.placing("corner");
		int toCorner = Math.max(1, columns + 1);
		placements.turnedAt(lane.ahead(toCorner - 1).pos());
		placements.corner(lane.ahead(toCorner).pos());
		placements.corner(lane.ahead(toCorner + slabStep).pos());
		boolean clockwise = lane.travel().getClockWise() == depth;
		return lane.bending(List.of(new Lane.Bend(toCorner, clockwise),
			new Lane.Bend(toCorner + slabStep, clockwise))).crowding();
	}

	private static TurnCost turnCost(int floor, int climb, int floors, int slabStep) {
		int above = floor + climb;
		boolean staircase = above >= 0 && above < floors;
		int cells = staircase ? TURN_DUST_CELLS : slabStep + 2;
		int offBus = staircase && climb > 0 ? cells - 2 : cells;
		int stepOff = staircase && climb < 0 ? 1 : 0;
		// ekran's: a descent is four everywhere, not four for a cut and five plus a step off for
		// everyone else. The ordinary descent paid the extra cell to meet a lane arriving at lane
		// level, and the step off to walk back into the spiral it landed past. Put the handover where
		// a bus would start and neither is needed -- which is what addSplitBusDescent already builds,
		// since its first rung is a powered stone with wire on top, and that is a cell of bus.
		if (UNIVERSAL_FOUR_DESCENT && staircase && climb < 0) {
			cells = SPLIT_DESCENT_CELLS;
			offBus = SPLIT_DESCENT_CELLS;
			stepOff = 0;
		}
		// A climb's split already costs what a climb off a bus costs, because a climb off a bus is
		// what it is. Only the descent has a cheaper form of itself to be told about.
		return new TurnCost(above, cells, offBus, stepOff,
			staircase && climb < 0 && CHEAP_SPLIT_DESCENT ? SPLIT_DESCENT_CELLS
				: offBus + stepOff);
	}

	/**
	 * Where an event leaves the cursor and what it leaves behind, worked out rather than built.
	 *
	 * <p>The one piece of arithmetic the planner and the walk both go through, so that a lane planned
	 * to end on the wall is a lane that ends on the wall. Everything that decides an event's length is
	 * in here: the repeaters the wait in front of it needs, the column a stacked module takes to land
	 * on its beat, and the drop to a bus when the pair of slots behind it is already spoken for.</p>
	 */
	private record Landing(int end, int tip, boolean busy, ChordStyle style) {
	}

	/**
	 * Whether padding this chord forward by so many columns leaves the next one with nowhere to go.
	 *
	 * <p>A pad in front of a chord moves everything behind it along, and the event after is the one
	 * that pays: it starts that many columns nearer its wall. There are two ways it can still be all
	 * right -- it fits in what is left, or it is big enough to be cut across the turn and the cut
	 * reaches -- and if neither holds, the pad has spent the lane's last room on a discount and the
	 * chord comes to rest outside. That is the illit lane exactly: a chord of eighteen with nine
	 * columns in front of it would have been cut, and ten columns of prepad left it {@code room=-2},
	 * where a cut is not offered at all.</p>
	 *
	 * <p>Asked as a difference rather than as an absolute. A chord already doomed at the booking the
	 * plan made is not doomed <em>by</em> the growth, and refusing to grow does not save it -- it only
	 * moves the lane, and a lane moved for no reason lands its notes against somebody else's tick.
	 * The same reasoning, and the same measurement, as the down-clamp above.</p>
	 */
	private static boolean strandsTheEventAfter(List<EventGroup> events, int index, EventGroup event,
			int columnsAhead, Lane lane, int wait, boolean busy, int wall, Layout layout,
			boolean inTurn, ParityOracle parity, int splitCells) {
		if (index + 1 >= events.size()) {
			return false;
		}
		int step = lane.travel().getStepX();
		Landing here = landingOf(lane.pos().getX() + step * columnsAhead, step, event, wait, busy,
			wall, layout, inTurn, parity);
		// Where the next chord opens is the column after this one's last, and what it has to work
		// with is whatever stands between there and the wall.
		int room = (wall - (here.end() + step)) * step;
		int cells = (events.get(index + 1).notes().size() + 1) / 2;
		boolean fits = cells <= room - 1;
		// The same sum {@code couldSplit} makes in the walk. Two places, one rule -- see the note on
		// {@link #closes}.
		//
		// Necessary and nothing like sufficient, which is what the first version of this got wrong.
		// A cut is offered right down to {@code room == 2}, where the near half is
		// {@code 2 * (room - 1)} = two notes and the whole rest of the chord has to land in the next
		// lane -- so "it can be cut" was true for every column the growth wanted to take, and the
		// guard let it take them. Measured at 357 breached lanes against 63 for not growing at all.
		boolean cuts = !PREPAD_NEXT_MUST_FIT
			&& room >= 2 && room - 1 < cells && cells + splitCells <= DUST_RANGE;
		return !fits && !cuts;
	}

	/**
	 * What the walk will do to a stacked module standing at a given column.
	 *
	 * <p>The one thing the planner used to be unable to answer, and it turned out only to be
	 * missing the blocks. A nudge is decided by looking at the lane behind, and lanes are built in
	 * order, so by the time a lane is planned the lane behind it is already down and can simply be
	 * asked. The planner was never blind to parity; nobody handed it the block map.</p>
	 *
	 * <p>It matters because a nudge makes a chord a column longer than the plan said, and a lane
	 * measured to land flush on its wall drifts by exactly that much. Sixteen thousand of them is
	 * how a shape that fits perfectly well ends up breaching.</p>
	 */
	private interface ParityOracle {
		/** 1 to shift a column, -1 to give the shape up, 2 to move a note, 0 to build as it stands. */
		int verdictAt(int x, int time, UltraSlots slots, RelocationRoom room);
	}

	/**
	 * Asks the blocks, and remembers the answer.
	 *
	 * <p>Cached because the closing search sweeps the same lane many times over, and each ask is up
	 * to six lookups into the placement map. Keyed on the column and the tick, which together name
	 * the question.</p>
	 */
	private static ParityOracle parityOracle(PlacementPlan placements, Lane laneStart) {
		int originX = laneStart.pos().getX();
		int step = laneStart.travel().getStepX();
		// Keyed on the flanks as well as on the column now, because two chords standing in the same
		// place no longer get the same answer: one that hangs four low notes can clash where one that
		// hangs two does not.
		Map<Integer, Map<Long, Integer>> answered = new java.util.HashMap<>();
		return (x, time, slots, room) -> answered
			.computeIfAbsent(flankMask(slots) * 4 + room.key(), mask -> new java.util.HashMap<>())
			.computeIfAbsent(((long) x << 32) ^ (time & 0xffffffffL), key ->
				parityVerdict(placements, laneStart.ahead((x - originX) * step), time, slots,
					room));
	}

	/**
	 * Where a decided shape ends, and what it hands on.
	 *
	 * <p>The arithmetic half of {@link #landingOf}, taking the shape as settled instead of working it
	 * out again. That is the point: v2 decides once through {@link #shapeFor} and measures the answer,
	 * so a lane cannot be measured for one shape and built as another.</p>
	 *
	 * <p>One column for a shape that asked for a pad, whether it asked because the pair behind was
	 * spoken for or because parity wanted it. {@link #addStackedShape} lays exactly one either way,
	 * and {@code nudge} is already true whenever {@code behindShift} is.</p>
	 */
	private static Landing landingFrom(Shape shape, int startX, int stepX, EventGroup event,
			int wait) {
		int delayColumns = Math.max(0, (wait - 1) / 4);
		ChordStyle style = shape.style();
		int shift = shape.nudge() ? 1 : 0;
		int cells = (event.notes().size() + 1) / 2;
		// Off the split the shape is carrying, not off a fresh one. A note relocated to the tail makes
		// the tail a note longer, and asking splitFor again is asking about the chord this module is
		// no longer being built as.
		int tailCells = 0;
		if (style.busHeaded()) {
			StackedBusSplit split = shape.moved() != null && shape.moved().split() != null
				? shape.moved().split() : splitFor(style, event.notes());
			tailCells = split == null ? 0 : stackedBusTailColumns(split.tail());
		}
		int length = style.busHeaded()
			? delayColumns + shift + STACKED_CELLS + STACKED_BUS_TRANSITION + tailCells
			: style.stacked()
				? delayColumns + shift + 2
				: delayColumns + (style == ChordStyle.BUS ? 1 + cells : 2);
		int tip = style == ChordStyle.BUS ? DUST_RANGE - cells
			: style.busHeaded()
				? DUST_RANGE - STACKED_BUS_TRANSITION - tailCells
				: DUST_RANGE;
		return new Landing(startX + stepX * length, tip, takesTheGapBehind(style, tailCells), style);
	}

	private static Landing landingOf(int startX, int stepX, EventGroup event, int wait, boolean busy,
			int wall, Layout layout, boolean inTurn,
			ParityOracle parity) {
		int delayColumns = Math.max(0, (wait - 1) / 4);
		ChordStyle style = event.style();
		// A column stood off the lane behind, so that the pair of low slots between the two modules
		// is nobody's but this one's. The same thing a wait already does, which is why the rule below
		// only fires at delayColumns == 0, and why one column of pad is all it takes.
		int behindShift = 0;
		if (style.reachesBack() && busy && delayColumns == 0
				&& NUDGE_WHEN_BEHIND_BUSY && losesTheHeadWithoutTheBackPair(style, event.notes())) {
			behindShift = 1;
		} else if (style.reachesBack() && busy && delayColumns == 0) {
			// The same substitution the walk makes: a head with a bus behind it keeps a head of
			// five, and only the rigid shape falls all the way to a bus.
			// The rigid shape falls to a head of five with a bus behind it too, not all the way to a
			// plain bus. ekran, reading one in game: there was no stacked chord anywhere near it to
			// justify a plain bus, and a front-only head fits. A chord of seven is a repeater and
			// four cells as a bus -- five columns -- against two, a handover and one cell as a head
			// of five with a tail of two, which is four. A chord of six is four either way, so this
			// never loses; and where the chord cannot make a head of five at all, addStackedShape
			// still drops it to a bus and counts it.
			style = HEAD_KEEPS_ONE_BACK_FLANK && style.busHeaded()
					&& stackedBusSplit(event.notes(), 1) != null
				? ChordStyle.STACKED_BUS_HALF
				: FRONT_HEAD_WHEN_BEHIND_BUSY || (FRONT_ONLY_HEADS && style.busHeaded())
					? ChordStyle.STACKED_BUS_FRONT : ChordStyle.BUS;
		}
		// And a chord being built in a turn, or stepping off the far side of one, is a bus whatever
		// shape it was sorted into: a stacked module may not sit perpendicular to another, and the two
		// modules either side of a corner are perpendicular by construction. {@link #addChordModule}
		// has always known that and this did not, so the first chord of every lane leaving a flat
		// corner was measured at two columns and built at four or five. Big Shot at twelve wide is a
		// five-note chord measured for two, built as a bus over four, and a bus of nineteen behind it
		// with one column too few -- the lane came to rest a column outside its wall having been
		// planned to land flush on it.
		if (style.stacked() && inTurn) {
			style = ChordStyle.BUS;
			behindShift = 0;
		}
		int cells = (event.notes().size() + 1) / 2;
		// A stacked module too near the wall to be nudged is built as a bus instead, and a bus of the
		// same chord is longer. Predicted here as well as decided there, because when only the walk
		// knew, the lane was measured for two cells, got five, and came to rest three past its wall.
		// Erring towards the bus is the safe way round: the walk may yet find the module needs no
		// nudge and build it short, which lands the lane inside its wall rather than outside it.
		if (style.stacked()
				&& (wall - (startX + stepX * (delayColumns + behindShift))) * stepX - handoverReserve(layout)
					< STACKED_CELLS + 1) {
			style = ChordStyle.BUS;
			behindShift = 0;
		}
		// The stacked-bus is measured from the same split the walk will build, not from an
		// arithmetic that happens to agree with it. Its head is seven notes only when the chord has
		// seven the head can take, and a chord of two snares and eight harps does not -- so asking
		// the same function is the only way the two can be talking about the same chord.
		int tailCells = 0;
		if (style.busHeaded()) {
			StackedBusSplit split = splitFor(style, event.notes());
			if (split == null) {
				style = ChordStyle.BUS;
				behindShift = 0;
			} else {
				tailCells = stackedBusTailColumns(split.tail());
				// Head, transition and bus all have to be inside the wall, where a plain stacked
				// module only ever had to fit its two columns.
				//
				// Unless the bus it would fall to is longer than the head it is giving up, which for
				// any chord the head takes seven from it is: see {@link #addChordModule}, which has
				// to make this same substitution or the lane is measured for one shape and built as
				// another.
				if ((wall - (startX + stepX * (delayColumns + behindShift))) * stepX - handoverReserve(layout)
						< STACKED_CELLS + STACKED_BUS_TRANSITION + tailCells + 1
						&& !(KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER && 1 + cells
							> STACKED_CELLS + STACKED_BUS_TRANSITION + tailCells)) {
					style = ChordStyle.BUS;
					behindShift = 0;
				}
			}
		}
		// What the walk will do to this module when it meets the lane behind, asked of the blocks
		// rather than guessed. A shift costs the chord a column; a module boxed in on both cells
		// gives the shape up and is built as a bus. Both were surprises to the plan until now.
		boolean nudged = false;
		if (style.stacked() && parity != null) {
			int verdict = parity.verdictAt(startX + stepX * (delayColumns + behindShift),
				event.time(), slotsFor(style, event.notes()),
				relocationRoom(style, event.notes()));
			// One column is what this shape can express. A module already stood off the lane behind
			// and then asked for a second column gives the head up after all, which is exactly what
			// it would have done without the shift -- and a plain bus, because the reason it was
			// shifted rather than shortened is that the short head was never available.
			if (verdict < 0 || behindShift > 0 && (verdict == 1 || verdict == 3)) {
				style = ChordStyle.BUS;
				behindShift = 0;
			} else {
				// A relocation costs nothing in columns -- that is the condition it is offered under
				// -- so only a shift lengthens the chord. Three is a shift with a relocation on the
				// far side of it, and costs exactly what the shift costs.
				nudged = verdict == 1 || verdict == 3;
			}
		}
		int length;
		if (style.busHeaded()) {
			length = delayColumns + behindShift + STACKED_CELLS + STACKED_BUS_TRANSITION + tailCells;
		} else if (style.stacked()) {
			length = delayColumns + behindShift + 2;
		} else {
			length = delayColumns + (style == ChordStyle.BUS ? 1 + cells : 2);
		}
		// The same two cases the walk charges: a bus spends a cell a note pair, and everything else
		// hands on the whole fifteen.
		int tip = style == ChordStyle.BUS ? DUST_RANGE - cells
			: style.busHeaded()
				? DUST_RANGE - STACKED_BUS_TRANSITION - tailCells
				: DUST_RANGE;
		return new Landing(startX + stepX * (length + (nudged ? 1 : 0)), tip,
			takesTheGapBehind(style, tailCells), style);
	}

	/**
	 * How much pad to lay in front of each of a lane's events, decided before any of it is built.
	 *
	 * <p>Everything else about a lane can be settled as it goes. This cannot, and the reason is worth
	 * stating plainly: a lane takes chords until one will not fit and then has to close on whatever it
	 * is holding. If that is a bus of twenty-two notes it is holding four blocks of wire and a twelve
	 * column gap, and nothing fills twelve columns with four blocks of wire. The lane needed to stop a
	 * chord earlier -- and by the time it finds that out, the chord is built.</p>
	 *
	 * <p>Most lanes need nothing from this. A lane whose next chord is small enough to cut closes by
	 * cutting it, which fills the lane exactly with music that was going to be built anyway and costs
	 * neither wire nor a tick. It is the chord too big to cut -- over twenty-two notes at a climb,
	 * over eighteen at a descent -- that forces the lane to land on the wall under its own steam, and
	 * that is what is planned here.</p>
	 */
	private static Map<Integer, Integer> planLane(List<EventGroup> events, int from, int startX,
			int stepX, int wall, int otherWall, int startTime, int tip, boolean busy, int turnCells,
			int offBus, int stepOff, int splitCells, boolean climbing, Layout layout,
			boolean leaving, ParityOracle parity) {
		Sweep bare = sweep(events, from, startX, stepX, wall, startTime, tip, busy, offBus, layout,
			Map.of(), leaving, parity);
		if (TRACE) {
			System.out.println("PLAN from=" + from + " x=" + startX + " step=" + stepX + " wall="
				+ wall + " tip=" + tip + " busy=" + busy + " turnCells=" + turnCells + " offBus="
				+ offBus + " stepOff=" + stepOff + " bareLast=" + bare.last() + " barePast="
				+ bare.past() + " ends=" + bare.ends() + " tips=" + bare.tips() + " room="
				+ bare.room() + " closes="
				+ (bare.last() >= from && closes(events, bare, from, bare.last(), wall, stepX,
					turnCells, offBus, splitCells, climbing)));
		}
		if (bare.last() < from) {
			// Nothing this lane can do for itself: its own opening chord will not fit, and a pad only
			// pushes chords nearer the wall. The column has to come from the lane before, which is
			// planned by its own call and asks {@link #strandsNext} the same question.
			return Map.of();
		}
		boolean shuts = closes(events, bare, from, bare.last(), wall, stepX, turnCells, offBus,
			splitCells, climbing);
		int cut = carriedCells(events, bare, from, bare.last(), wall, stepX, splitCells, climbing);
		if (shuts && !strandsNext(events, from, bare.last(), wall, otherWall, stepX, turnCells,
				offBus, stepOff, climbing, layout, cut)) {
			return Map.of();
		}
		// The lane closes, but only by cutting a chord across the turn into a lane that then cannot
		// lay its own first chord. So the cut has to go, and the walk has to be told -- it decides to
		// split on its own arithmetic, so a plan that quietly closes a chord earlier is a plan the
		// walk ignores. Recorded under a negative key, which no event index can collide with.
		// Unless the chord it is said to strand can simply be cut in its own turn.
		//
		// ekran: "it's fine if the next chord can't fit entirely inside the next lane -- that's why we
		// have cuts." {@link #strandsNext} sweeps that lane and a sweep only asks whether a chord
		// *fits*; a chord that does not fit is precisely the chord that gets cut, and the walk cuts a
		// lane's first event on purpose. So a lane reported stranded may be one that closes perfectly
		// well by cutting.
		//
		// Asked here rather than inside strandsNext, because that answer is also read as "this lane
		// closes cleanly, book nothing" -- relaxing it there stops the planner laying pads that other
		// lanes rely on, and takes Guardian from 20 breached lanes and 86 blocks to 48 and 202. Here
		// it decides only whether to forbid a cut, which is the decision the point is about.
		boolean vetoCut = shuts && cut > 0
			&& !(STRANDED_CHORD_MAY_STILL_CUT && nextChordCanBeCut(events, bare.last(), wall,
				otherWall, stepX, cut, climbing, stepOff));
		// The natural end cannot close. Try landing on the wall a chord at a time further back, since
		// every chord given up is a chord the next lane has to carry instead.
		for (int last = bare.last(); last >= from; last--) {
			// The smallest pad that makes the next chord cut, before the pad that fills the lane.
			//
			// Filling out to the wall is one way to close a lane and the expensive one: every column
			// has to be paid for in wire, and a lane arriving on a long bus has none. A cut wants no
			// wire at all -- the columns are filled with the chord's own music -- but it is only
			// offered where the chord does not fit, and a chord that fits by a column or two is laid
			// whole. The lane then has nothing left to climb with, cannot turn, and runs on.
			//
			// So try the smallest pad that makes it not fit. ekran: move the start of the chord forward
			// a couple of blocks so that it cuts the stacked bus. On Guardian at 44 wide over three
			// floors that is two columns in front of a chord of twenty-four, which turns a run of
			// eleven columns past the wall into a lane that ends on it.
			if (PADS_UNTIL_THE_NEXT_CHORD_CUTS && last + 1 < events.size()) {
				Map<Integer, Integer> cutting = new LinkedHashMap<>();
				for (int column = 1; column <= CUT_PAD_COLUMNS; column++) {
					if (!book(cutting, bare, from, last, 1)) {
						// Nowhere left to charge the column to: every gap back to the start of the lane is
						// already spent, so this chord cannot be moved at all.
						if (TRACE) {
							System.out.println("  CUTTRY last=" + last + " column=" + column
								+ " refused=noRoomToBook pads=" + cutting);
						}
						break;
					}
					Sweep shorter = sweep(events, from, startX, stepX, wall, startTime, tip, busy, offBus,
						layout, cutting, leaving, parity);
					if (shorter.last() < last) {
						// The pad pushed the lane far enough that it no longer reaches this chord.
						if (TRACE) {
							System.out.println("  CUTTRY last=" + last + " column=" + column
								+ " refused=sweepStopsShort shorterLast=" + shorter.last() + " pads=" + cutting);
						}
						break;
					}
					boolean shut = closes(events, shorter, from, last, wall, stepX, turnCells, offBus,
						splitCells, climbing);
					// Which of the two refused, and what the cut was being asked about. A search that only
					// says no leaves the reason to be inferred, and the reasons want opposite fixes: a chord
					// that still fits wants more pad, one whose cut is out of wire wants none at all, and a
					// lane stranded past it wants the chord before this one to move instead.
					if (TRACE) {
						int end = shorter.ends().get(last - from);
						List<EventNote> next = events.get(last + 1).notes();
						int nextCells = (next.size() + 1) / 2;
						int roomAfter = (wall - end) * stepX;
						boolean roomBehind = last < from || !shorter.busy().get(last - from);
						System.out.println("  CUTTRY last=" + last + " column=" + column
							+ " end=" + end + " room=" + roomAfter + " nextNotes=" + next.size()
							+ " nextCells=" + nextCells + " fits=" + (roomAfter - 1 >= nextCells)
							+ " headed=" + (stackedSplitOf(next, roomAfter, splitCells, climbing, roomBehind)
								!= null)
							+ " plainWire=" + (nextCells + splitCells) + "/" + DUST_RANGE
							+ " closes=" + shut + " strands=" + strandsNext(events, from, last, wall, otherWall,
								stepX, turnCells, offBus, stepOff, climbing, layout,
								carriedCells(events, shorter, from, last, wall, stepX, splitCells, climbing))
							+ " pads=" + cutting);
					}
					if (shut
						&& !strandsNext(events, from, last, wall, otherWall, stepX, turnCells, offBus,
							stepOff, climbing, layout, carriedCells(events, shorter, from, last, wall, stepX,
								splitCells, climbing))) {
						if (TRACE) {
							System.out.println("  CUTPAD last=" + last + " columns=" + column
								+ " pads=" + cutting);
						}
						return Map.copyOf(cutting);
					}
				}
			}
			Map<Integer, Integer> pads = new LinkedHashMap<>();
			for (int attempt = 0; attempt < 8; attempt++) {
				Sweep tried = sweep(events, from, startX, stepX, wall, startTime, tip, busy, offBus, layout,
					pads, leaving, parity);
				if (tried.last() < last) {
					break;
				}
				int owing = (wall - tried.ends().get(last - from)) * stepX;
				if (owing < 0) {
					break;
				}
				// What the lane would lay behind this chord, asked the way the walk asks it. The lane
				// does not have to land flush against the wall on the chord itself, and demanding that
				// is what made the search give up on lanes it could have closed: a chord opens with a
				// repeater, so the wire behind it is worth a full fifteen however little arrived, and
				// the pad that spends it fills the last columns out to the wall. The chord that has to
				// move only needs to move far enough that its own repeater can see the top of the
				// staircase -- two columns on the lane this was found on, where landing it flush would
				// have wanted twelve and there was room for three.
				// A climb staircase is the only turn that discounts an off-bus start -- a descent costs
				// four whichever way it is met and a flat turn costs what it costs -- so offBus being
				// the cheaper of the two is exactly the condition, and the planner can read it off the
				// prices it was handed rather than being told the direction separately.
				boolean discounts = offBus < turnCells;
				Pad end = closingPad(events, tried, from, last, owing, turnCells, offBus,
					discounts, discounts);
				int need = turnPrice(end, discounts, discounts,
					tried.styles().get(last - from).buses(), turnCells, offBus);
				if (TRACE) {
					System.out.println("  TRY last=" + last + " attempt=" + attempt + " pads=" + pads
						+ " triedLast=" + tried.last() + " owing=" + owing + " padCells="
						+ end.cells().size() + " padSignal=" + end.signal() + " need=" + need
						+ " strands=" + strandsNext(events, from, last, wall, otherWall, stepX, turnCells, offBus, stepOff, climbing, layout, 0));
				}
				// The wire this turn really leaves, which this is the one place that knows: the pad
				// has been planned and the staircase priced, so what the next lane opens on is what
				// the pad had left minus what the turn takes. Handing the constant instead credits
				// the next lane with wire this lane never had.
				if (end.cells().size() == owing && end.signal() >= need
					&& !strandsNext(events, from, last, wall, otherWall, stepX, turnCells, offBus,
						stepOff, climbing, layout, 0, end.signal() - need)) {
					if (vetoCut) {
						pads.put(NO_SPLIT - (last + 1), 1);
					}
					return Map.copyOf(pads);
				}
				int shortfall = owing - end.cells().size();
				if (shortfall <= 0 || !book(pads, tried, from, last, shortfall)) {
					break;
				}
			}
		}
		// Nothing lands this lane on its wall. It used to say so by handing back nothing at all, and
		// nothing at all means no pad anywhere: a lane that could not be closed did not even spend the
		// wire it was holding. So it turned where it stood with cells to spare, and the staircase came
		// down in the middle of the corridor, against whatever the lane alongside had hung there.
		//
		// Spend it anyway, if spending it helps. Every column of pad is a column the turn happens
		// further along, and the difference between a staircase that lands beside somebody's notes and
		// one that does not is usually a column or two. {@link #book} already works backwards from the
		// last chord through whatever room the ones before it have, so a chord arriving on a strong
		// wire pays for the one arriving on a weak one. Ekran read it off the blocks on Do The Dance at
		// twenty wide: a lane of one chord, fifteen columns of wall and three blocks of wire, padding
		// nothing at all.
		//
		// And offered rather than imposed, which is the whole of what made the first attempt worse. A
		// pad is not free: the walk lays it out of the same wire the turn has to be made on, so a lane
		// that books more than it can pay for arrives at its wall with nothing left and runs straight
		// past it. Booked blind that way it cost 39 breaches to save 3 dead runs. So the plan is swept
		// again with the pad in it and kept only if the lane is genuinely better for it -- nearer its
		// wall, still short of it, and still holding every chord it held before.
		//
		// And never at the cost of the chord that ends the lane. This is the branch where the lane
		// does not close, and a lane that does not close still has to lay the chord that beat it --
		// past its wall, because that is what "does not close" means. Pad moves that chord along with
		// everything else, so a pad bought to bring the staircase a column nearer the wall pushes the
		// breach a column further past it. Both halves of Do The Dance's worst breach were this: a
		// single column booked in front of a five-note chord, and a bus of twenty two columns out
		// instead of one. So the sweep is asked where that chord lands as well as where the lane
		// stops, and a pad that costs the breach more than it saves the staircase is not taken.
		int owed = (wall - bare.ends().get(bare.last() - from)) * stepX;
		Map<Integer, Integer> most = new LinkedHashMap<>();
		book(most, bare, from, bare.last(), owed);
		if (most.isEmpty()) {
			return Map.of();
		}
		Sweep padded = sweep(events, from, startX, stepX, wall, startTime, tip, busy, offBus, layout,
			most, leaving, parity);
		if (padded.last() < bare.last() || padded.past() > bare.past()) {
			return Map.of();
		}
		int left = (wall - padded.ends().get(padded.last() - from)) * stepX;
		return left >= 0 && left < owed ? Map.copyOf(most) : Map.of();
	}

	/**
	 * A lane walked on paper: where each event ends, what it leaves, and what its gap could hold.
	 *
	 * <p>{@code past} is the one thing here that is not about the lane's own chords: it is how far
	 * beyond the wall the first chord that would not fit comes to rest. That chord is the lane's
	 * problem even though it is not the lane's chord -- when nothing closes the lane, the walk lays
	 * it anyway and the columns it covers past the wall are the breach.</p>
	 */
	private record Sweep(List<Integer> ends, List<Integer> tips, List<ChordStyle> styles,
			List<Boolean> busy, List<Integer> room, int last, int past) {
	}

	private static Sweep sweep(List<EventGroup> events, int from, int startX, int stepX, int wall,
			int startTime, int tip, boolean busy, int offBus, Layout layout,
			Map<Integer, Integer> pads, boolean leaving, ParityOracle parity) {
		List<Integer> ends = new ArrayList<>();
		List<Integer> tips = new ArrayList<>();
		List<ChordStyle> styles = new ArrayList<>();
		// Whether each chord leaves the low slots behind the next one spoken for. Recorded because
		// closes() and carriedCells() have to make the same sum the walk does, and a cut's head is
		// seven notes or five depending on exactly this.
		List<Boolean> busyAfter = new ArrayList<>();
		List<Integer> room = new ArrayList<>();
		int cursor = startX;
		int time = startTime;
		int last = from - 1;
		int past = 0;
		for (int index = from; index < events.size(); index++) {
			EventGroup event = events.get(index);
			int wait = event.time() - time;
			int pad = pads.getOrDefault(index, 0);
			room.add(planPad(DUST_RANGE * 2, tip, 0, Math.max(0, wait - 1)).cells().size() - pad);
			Landing landed = landingOf(cursor + stepX * pad, stepX, event, wait, busy, wall, layout,
				index == from && leaving, parity);
			// Kept back the same column the walk keeps back. A bus that would leave the wire too weak
			// to reach the top of the staircase is asked to stop one column short, so the pad has
			// somewhere to stand the repeater that revives it. The walk has always done that and the
			// sweep did not, so the planner counted chords into a lane the walk then refused to put
			// there, and the two disagreed about which chords the lane even held.
			if ((landed.end() + stepX * turnReserve(event, offBus, layout) - wall) * stepX > 0) {
				// Where that chord would come to rest if it were laid regardless, which is what the walk
				// does when nothing closes the lane. Measured from the chord's own end and not from its
				// turn reserve: the reserve is room the lane wanted to keep, and a chord laid over it
				// breaches by what it actually covers.
				past = Math.max(0, (landed.end() - wall) * stepX);
				break;
			}
			ends.add(landed.end());
			tips.add(landed.tip());
			styles.add(landed.style());
			busyAfter.add(landed.busy());
			cursor = landed.end();
			tip = landed.tip();
			busy = landed.busy();
			time = event.time();
			last = index;
		}
		return new Sweep(ends, tips, styles, busyAfter, room, last, past);
	}

	/**
	 * The pad a lane ending on this chord would lay behind it, worked out the way the walk does.
	 *
	 * <p>A lane does not have to land flush against its wall on the chord itself, and asking for
	 * that is what made the search give up on lanes it could have closed. Every chord opens with a
	 * repeater, so the wire behind one is worth a full fifteen however little arrived at it, and the
	 * pad that spends it fills the last columns out to the wall. The chord that has to move only
	 * needs to move far enough that its own repeater can see the top of the staircase -- two columns
	 * in the lane this was found on, where landing it flush would have wanted twelve and there was
	 * room for three.</p>
	 */
	private static Pad closingPad(List<EventGroup> events, Sweep sweep, int from, int last,
			int owing, int turnCells) {
		return closingPad(events, sweep, from, last, owing, turnCells, turnCells, false, false);
	}

	/**
	 * @param climbing and {@code staircase} so the planner prices a raised pad the way the walk
	 *     builds one. Without them the plan books five cells for a climb the walk spends three on,
	 *     and every lane it closes is closed against a wall it did not have to reach.
	 */
	private static Pad closingPad(List<EventGroup> events, Sweep sweep, int from, int last,
			int owing, int turnCells, int offBus, boolean climbing, boolean staircase) {
		// The shape the lane comes to rest on, which is what decides whether the pad may start up at
		// bus height or has to spend its first cell on the path getting there. The sweep records it
		// for exactly this kind of question, and it is the same thing the walk calls lastStyle.
		boolean fromBus = sweep.styles().get(last - from).buses();
		return planTurnPad(owing, sweep.tips().get(last - from), turnCells, offBus,
			last + 1 < events.size()
				? Math.max(0, events.get(last + 1).time() - events.get(last).time() - 1) : 0,
			climbing, staircase, fromBus);
	}

	/**
	 * Which column the lane after this one opens in, given how this one hands over.
	 *
	 * <p>The planner needs this and has never had it. It plans one lane at a time from a start it is
	 * handed, so a lane that cannot seat its own first chord has no way to say so to the lane whose
	 * turn put it there -- and that is the whole of the breach ekran has been pointing at: the
	 * planner is right that the chord will not fit, the walk lays it anyway, and the column that
	 * would have fixed it is sitting unspent in the lane before.</p>
	 *
	 * <p>Two shapes, because there are two ways to hand over. A plain close walks out to the wall and
	 * the staircase steps one column past it, which is where the next lane opens. A split lays as
	 * much of the chord as reaches the wall, takes the staircase from there, and then lays the rest
	 * back the other way -- so the next lane opens as far short of the wall as that carried half is
	 * long.</p>
	 *
	 * @param carriedCells cells of a split chord laid after the staircase, or zero for a plain close
	 */
	private static int nextLaneStart(int wall, int stepX, int carriedCells, boolean climbing,
			int stepOff) {
		// A climb comes back two columns nearer the wall than a descent does. Not derived -- measured,
		// by grading this against every handover in the library: the first version had descents right
		// 33,178 times against 623 and climbs right not once, off by exactly two every time.
		return carriedCells > 0
			? wall - stepX * (carriedCells + (climbing ? 1 : 0))
			: wall + stepX * (climbing ? -1 : 1);
	}

	/**
	 * Records how far {@link #nextLaneStart} was out, counted the way the chord landings are.
	 *
	 * <p>Named with the {@code plan} prefix the sweep already knows to keep out of its wasted-pad
	 * total: this is not pad anybody laid, it is the planner and the walk disagreeing, which is a
	 * different thing to be annoyed about and wants its own column in the table.</p>
	 */
	private static void gradeLaneStart(PlacementPlan placements, int wall, int stepX,
			int carriedCells, boolean climbing, int stepOff, int opened, String how) {
		int foretold = nextLaneStart(wall, stepX, carriedCells, climbing, stepOff);
		if (TRACE_TURNS && foretold != opened) {
			System.out.println("STARTOFF " + how + " wall=" + wall + " stepX=" + stepX
				+ " carried=" + carriedCells + " climbing=" + climbing + " stepOff=" + stepOff
				+ " foretold=" + foretold + " opened=" + opened + " off=" + (foretold - opened));
		}
		if (foretold == opened) {
			placements.padded("planStartRight" + how);
			return;
		}
		int off = (opened - foretold) * stepX;
		placements.padded("planStart" + (off > 0 ? "Late" : "Early")
			+ Math.min(Math.abs(off), 4) + how);
	}

	/**
	 * Records how far the tip {@link #strandsNext} assumes was out, against the tip the walk handed on.
	 *
	 * <p>The lookahead sweeps the next lane on {@code DUST_RANGE - turnCells}, which is what a lane
	 * arriving at its turn on a full fifteen leaves behind it. Lanes do not all arrive on a full
	 * fifteen. Whether that matters is the open question behind the two breaches the veto bought, and
	 * it is a question with a number, so here is the number rather than another argument about it.</p>
	 */
	private static void gradeLaneTip(PlacementPlan placements, int turnCells, int handedOn,
			String how) {
		int guessed = DUST_RANGE - turnCells;
		if (guessed == handedOn) {
			placements.padded("planTipRight" + how);
			return;
		}
		int off = guessed - handedOn;
		placements.padded("planTip" + (off > 0 ? "Rich" : "Poor") + Math.min(Math.abs(off), 6) + how);
	}

	/**
	 * How much of the next chord the turn carries, for a lane closing here.
	 *
	 * <p>Zero when the lane lands on its wall under its own steam, and the far half of the cut when
	 * it closes by cutting the chord across the turn -- read off {@link #closes}, whose two branches
	 * these are, and {@code near = 2 * (room - 1)} in the walk, which is where the split is built.</p>
	 */
	private static int carriedCells(List<EventGroup> events, Sweep sweep, int from, int last,
			int wall, int stepX, int splitCells, boolean climbing) {
		boolean roomBehind = last < from || !sweep.busy().get(last - from);
		int room = (wall - sweep.ends().get(last - from)) * stepX;
		if (room == 0 || last + 1 >= events.size()) {
			return 0;
		}
		// Read off the same split the walk will build. A headed cut carries what the head and the
		// near bus between them could not take, which is not the same as what a plain bus leaves.
		StackedSplit headed = stackedSplitOf(events.get(last + 1).notes(), room, splitCells,
			climbing, roomBehind, !roomBehind);
		if (headed != null) {
			return (headed.farTail().size() + 1) / 2;
		}
		int carried = events.get(last + 1).notes().size() - 2 * (room - 1);
		return carried > 0 ? (carried + 1) / 2 : 0;
	}

	/**
	 * Whether closing here leaves the lane after this one unable to lay its own first chord.
	 *
	 * <p>The one thing the planner has never been able to ask. A lane that closes perfectly well may
	 * hand over into a lane whose opening chord does not fit the columns it was left -- and that lane
	 * has no move of its own, because a pad pushes chords towards the wall and what it needs is to
	 * start further from one. So it lays the chord anyway and breaches two chords later, which is
	 * every breach left in the library.</p>
	 *
	 * <p>Asked one lane deep and no further. That is enough for the fault ekran found, and a search
	 * that reached back further would be re-planning the song rather than closing a lane.</p>
	 *
	 * @param carriedCells cells of a cut chord laid after the staircase, zero for a plain close
	 */
	/**
	 * Whether the chord said to strand the next lane could just be cut across that lane's own turn.
	 *
	 * <p>Priced at the dearest a crossing can be, because the next lane turns the other way and its
	 * cost is not known here. Strict, and still generous enough for the case that prompted it: a
	 * headed cut of twenty-four is a transition, nine cells and a staircase -- {@code 1 + 9 + 5 = 15}
	 * even at the dear price, so on a song whose chords stop at twenty-four very nearly all of them
	 * can be cut wherever they land.</p>
	 */
	private static boolean nextChordCanBeCut(List<EventGroup> events, int last, int wall,
			int otherWall, int stepX, int carriedCells, boolean climbing, int stepOff) {
		int first = (carriedCells > 0 ? last + 1 : last) + 1;
		if (first >= events.size()) {
			return false;
		}
		List<EventNote> stuck = events.get(first).notes();
		int room = Math.abs(otherWall
			- nextLaneStart(wall, stepX, carriedCells, climbing, stepOff));
		int cells = (stuck.size() + 1) / 2;
		return room >= 2 && room - 1 < cells && cells + TURN_DUST_CELLS <= DUST_RANGE
			|| stackedSplitOf(stuck, room, TURN_DUST_CELLS, !climbing, true) != null;
	}

	private static boolean strandsNext(List<EventGroup> events, int from, int last, int wall,
			int otherWall, int stepX, int turnCells, int offBus, int stepOff, boolean climbing,
			Layout layout, int carriedCells) {
		return strandsNext(events, from, last, wall, otherWall, stepX, turnCells, offBus, stepOff,
			climbing, layout, carriedCells, DUST_RANGE - turnCells);
	}

	/**
	 * @param handedOn the wire the turn actually leaves the next lane, where the caller knows it.
	 *     It used to be {@code DUST_RANGE - turnCells} everywhere, which is what a lane hands on
	 *     only when it arrived at its turn on a full fifteen -- and {@code gradeLaneTip} says that
	 *     is true about one time in seventy. Every other time the guess is <em>rich</em>: the plan
	 *     credits the next lane with wire the turn did not leave it, so a lane that will strand its
	 *     neighbour reads as closing cleanly. Making turns cheaper makes that worse rather than
	 *     better, because a cheaper turn is one a lane may take with less in hand.
	 */
	private static boolean strandsNext(List<EventGroup> events, int from, int last, int wall,
			int otherWall, int stepX, int turnCells, int offBus, int stepOff, boolean climbing,
			Layout layout, int carriedCells, int handedOn) {
		if (!LOOKAHEAD || !layout.lookahead()) {
			return false;
		}
		// The cut chord belongs to the turn, so the lane after a cut opens on the chord past it.
		int spent = carriedCells > 0 ? last + 1 : last;
		int first = spent + 1;
		if (first >= events.size()) {
			return false;
		}
		int start = nextLaneStart(wall, stepX, carriedCells, climbing, stepOff);
		Sweep after = sweep(events, first, start, -stepX, otherWall,
			events.get(spent).time(), Math.max(0, handedOn), true, offBus, layout, Map.of(),
			false, null);
		// Left alone deliberately. ekran's point is right -- a chord the next lane cannot lay whole is
		// the chord that gets cut, not a stranded lane -- but this answer is not only used to veto a
		// cut. `planLane` reads a false here as "this lane closes cleanly, book nothing", so relaxing
		// it stops the planner laying pads that lanes were relying on: Guardian goes from 20 breached
		// lanes and 86 blocks to 48 and 202. The over-conservative answer was buying real bookings by
		// accident. The insight belongs at the veto instead, where {@link #nextChordCanBeCut} applies
		// it without touching what the planner books.
		return after.last() < first;
	}

	/** Whether the lane can hand over after this event, either by filling it out or by a cut. */
	private static boolean closes(List<EventGroup> events, Sweep sweep, int from, int last, int wall,
			int stepX, int turnCells, int offBus, int splitCells, boolean climbing) {
		if (last + 1 >= events.size()) {
			return true;
		}
		boolean roomBehind = last < from || !sweep.busy().get(last - from);
		int room = (wall - sweep.ends().get(last - from)) * stepX;
		if (room == 0) {
			return sweep.tips().get(last - from)
				>= (sweep.styles().get(last - from).buses() ? offBus : turnCells);
		}
		// Cut across the turn: as much of the next chord as reaches the wall, then the staircase, then
		// the rest of it, all off the one repeater. It closes a lane wherever the lane has got to, and
		// costs nothing, because the columns it fills are filled with music.
		int cells = (events.get(last + 1).notes().size() + 1) / 2;
		// The walk makes this same sum in {@code couldSplit}. They are one rule in two places and a
		// disagreement between them is a lane closed on a cut that never happens -- so the head is
		// offered here in the same order the walk offers it, and the plain sum is the fallback in
		// both.
		if (stackedSplitOf(events.get(last + 1).notes(), room, splitCells, climbing, roomBehind,
				!roomBehind)
				!= null) {
			return true;
		}
		return room >= 2 && room - 1 < cells && cells + splitCells <= DUST_RANGE;
	}

	/** Books what the end of a lane cannot pay for into the latest gaps that can. */
	private static boolean book(Map<Integer, Integer> pads, Sweep sweep, int from, int last,
			int owing) {
		if (TRACE) {
			System.out.println("    BOOK owing=" + owing + " from=" + from + " last=" + last
				+ " room=" + sweep.room());
		}
		for (int index = last; index >= from && owing > 0; index--) {
			int free = sweep.room().get(index - from);
			int take = Math.min(owing, free);
			if (take > 0) {
				pads.merge(index, take, Integer::sum);
				owing -= take;
			}
		}
		return owing == 0;
	}

	/**
	 * Key offset under which a plan says "do not cut the chord at this index across the turn".
	 *
	 * <p>Negative, so it cannot be mistaken for the pad owed by an event: every other key in a plan
	 * is an index into the song.</p>
	 */
	private static final int NO_SPLIT = -1;

	/**
	 * Whether a lane that has been refused its cut may take it back when it cannot turn either.
	 *
	 * <p>{@code planLane} books a {@link #NO_SPLIT} bit where it has found a way to close the lane on
	 * a pad, so that the walk does not cut a chord earlier than the plan did and leave the next lane
	 * unable to lay its own first one. Sound where the pad close works. Where it does not, the lane is
	 * told it may not cut and finds it cannot turn, so it does neither: the chord is laid whole and
	 * the lane comes to rest outside its wall.</p>
	 *
	 * <p>ekran's, off the blocks at Guardian 16 wide over four floors, {@code 20 77 148}. Tick 548, a
	 * chord of 24 standing at {@code x=13} with its wall at 15: {@code headed=0+18},
	 * {@code couldSplit=true}, {@code vetoed=true}, and a pad of one cell holding four blocks of wire
	 * that {@code reachesWall} refuses. The cut was there and already shed. Ten columns outside.</p>
	 */
	static boolean CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN = true;

	/**
	 * v2: the planner is not consulted, and a lane closes on a cut or not at all.
	 *
	 * <p>ekran's, and the argument is arithmetic rather than taste. A cut costs a transition cell,
	 * the two halves of the chord, and the staircase, all off the repeater the chord opens with:
	 * {@code 1 + ⌈near/2⌉ + ⌈far/2⌉ + splitCells ≤ 15}. Plain that reaches 22 notes descending and 24
	 * climbing; with a head it reaches 27 and 29. So on a song whose chords stop at twenty-four --
	 * Guardian does -- very nearly every chord can be cut wherever it happens to be standing, and a
	 * lane that can always cut never needs to be walked out to its wall on wire it has to pay for.</p>
	 *
	 * <p>Which is what {@link #planLane} and everything hanging off it exist to do. Its own docstring
	 * justifies itself with a bus of twenty-two holding four blocks of wire and a twelve column gap --
	 * and a bus of twenty-two is precisely the chord that cuts plain, anywhere, for nothing. So the
	 * whole booking layer is switched off here rather than tuned: no pads booked, no
	 * {@link #strandsNext}, no veto.</p>
	 *
	 * <p>Parity pads stay. They are not this kind of padding -- they move a module a column so its
	 * slots land on a parity that works, and nothing about cutting makes that unnecessary.</p>
	 *
	 * <p><b>On, on this branch.</b> It loses on Guardian -- 86 breach blocks to 273 on its own, 97
	 * with the earlier cut alongside it -- and wins on the song that isolates what it claims, ekran's
	 * chords of twenty-five at a gap of one, 164 blocks to 36. It is on so that it can be pasted and
	 * looked at, which is the point of the branch. It is not ready for main.</p>
	 */
	static boolean CUT_ONLY_LANES = false;

	/**
	 * v2: cut the chord that <em>reaches</em> the wall, rather than the one that fails to fit.
	 *
	 * <p>The one rule that has to change with {@link #CUT_ONLY_LANES}, and the hole it fills is the
	 * hole the pad machinery was built around. A chord that lands flush leaves {@code room == 0}:
	 * there is no near half, so there is nothing to cut, and the lane has to pay for its own turn out
	 * of whatever wire a long bus left it -- which for a chord of twenty-five is two cells against a
	 * staircase that costs four. That is the case a pad used to be booked for.</p>
	 *
	 * <p>Take the chord that ends at or past {@code wall - 1} instead and the near half is never
	 * empty, so the lane always has a cut available and never has to fund a turn. The chord being cut
	 * may be one that would have fitted whole, which {@link #CUTS_A_CHORD_THAT_FITS} already knows how
	 * to divide.</p>
	 *
	 * <p><b>On, on this branch, and it stands on its own.</b> Measured with the planner left alone it
	 * takes Guardian from 86 breach blocks to 68 over the same 21 lanes, for 1.7% more length, with
	 * ekran's 40 wide over five floors still clean. That is the one part of v2 that is ready to go to
	 * main by itself.</p>
	 */
	static boolean CUTS_THE_CHORD_THAT_REACHES = false;

	/**
	 * v2: whether a lane still lays wire in front of a chord to walk the next one out to its wall.
	 *
	 * <p>The last pad-to-wall in v2 that is neither a corner nor a parity pad. {@link #V2_BOOKS_PADS}
	 * is off and {@link #CUT_ONLY_LANES} and {@link #CUTS_THE_CHORD_THAT_REACHES} are read only by
	 * {@link #walkWall} -- v2 has both of those permanently on -- so the booking layer is already
	 * gone from this walk. What is left is this one and the closing pad.</p>
	 *
	 * <p>ekran's position is that neither should be needed: v2 supports chords of twenty-five and a
	 * chord of twenty-five cuts wherever it is standing, so a lane never has to be walked out to its
	 * wall on wire it has to pay for. The guard above already exempts a next chord that can be cut
	 * across the staircase or laid across a flat turn, so what fires here is what those two miss.</p>
	 *
	 * <p><b>Off, and it costs nothing to switch off.</b> Over the library at five widths: 1,084 breach
	 * blocks to 1,079, three columns of depth <em>back</em>, one more clean build, and nothing dead,
	 * missing or wrong moves. Guardian goes to nought. So the pad was not buying the lane its wall --
	 * it was spending the wire the chord in front of it then wanted.</p>
	 */
	static boolean V2_PADS_AHEAD = false;

	/**
	 * v2: a chord that lies across a flat turn is charged nothing for the wire arriving at it.
	 *
	 * <p>A flat turn is taken only by a chord that straddles it, and the test for that used to be
	 * {@code straddles && pad.signal() >= 1}. The second half is superstition, and the comment three
	 * lines above it says so in the file's own words: a straddling chord's repeater goes down where
	 * the lane has got to and hands out a fresh fifteen however dead the wire arriving was, so the
	 * only run that matters is the one inside the chord, which {@link #straddleFits} has already
	 * checked. The pad in this branch is {@link Pad#none}, so {@code pad.signal()} is just the tip --
	 * and the lane lays the very same chord in the very same column when it is refused the turn. The
	 * wire is identical either way. Only the corner goes.</p>
	 *
	 * <p><b>Every one of Guardian's seven breaches at 20 wide over three floors was this line</b>, and
	 * they are identical to the field: {@code flatAhead=true straddles=true pad=0c/0s}, a lane at
	 * {@code x=11} with its wall at 18, and a chord of 16 to 24 that fits across the corner and is
	 * told it cannot have it. The lane runs the chord straight out instead and the *next* chord opens
	 * outside the footprint. A lane comes out of a cut with its fifteen spent on the transition, both
	 * halves and the staircase, so {@code tip == 0} is the normal state of the first chord after a
	 * climb -- which is why this bites over and over rather than once.</p>
	 */
	static boolean STRADDLE_NEEDS_NO_WIRE = true;

	/**
	 * v2: two stacked centres are never left two columns apart; a pad makes it three.
	 *
	 * <p>ekran's, and it replaces a fallback with a guarantee. Two stacked modules whose centres sit
	 * two apart contend for the same slots, and the answer until now was to give the second one up and
	 * build it as a bus. A bus is wider and carries half as much, so the fallback costs the lane the
	 * columns it was trying to save.</p>
	 *
	 * <p>Spend one column instead. At three apart the next module's parity pad has somewhere to stand,
	 * so it can be placed as a stacked chord -- with relocation if it needs it -- rather than refused.
	 * One column bought, a module's worth of width saved.</p>
	 *
	 * <p>It can only fire where there is a tick to spend: a pad column is a column of the gap in front
	 * of the chord, and at a gap of one redstone tick that gap is empty. On the all-twenty-fives song
	 * it therefore never fires at all, and every chord is thirteen cells so no two centres are ever
	 * two apart anyway. Guardian is where it earns its keep. The times it wanted a column and there
	 * was none are counted under {@code parityGapNoTick} rather than passed over.</p>
	 */
	static boolean NEVER_TWO_APART = false;

	/**
	 * Whether a small chord that will not fit its crowded lane may stack while inside a turn.
	 *
	 * <p>ekran, from the blocks: small chords come out bus-shaped <em>"with no explanation,
	 * especially on flat turns"</em>. One line does it, and the census says it does nothing else --
	 * over 240 builds of eight songs, a small chord that fell back and had slots available was refused
	 * the stacked shape by the turn clause <b>1,733 times out of 1,733</b>. The other branch never
	 * fires at all: a small chord only ever fails to fit when it is in a turn, so in practice the
	 * fallback is always a bus, and 1,733 of the 2,264 buses laid had a denser shape available.</p>
	 *
	 * <p>The stated reason is that a stacked module in a turn stands across the run rather than along
	 * it. That is the same claim {@link #TURN_BAN_OUTLASTS} makes about the chord <em>after</em> a
	 * turn, which ekran has already read off the blocks and doubted -- a turn's bus comes out of its
	 * second bend running the new lane's way, so the cells behind are collinear with the chord.</p>
	 *
	 * <p>Whether it is physical or deferred is a question the machine can answer: allow it and read
	 * every build back. A rule that is really about geometry shows up as a wrong note or a dead line;
	 * one that is not shows up as nothing at all.</p>
	 */
	static boolean SMALL_MAY_STACK_IN_A_TURN = false;

	/**
	 * Whether a chord the next lane cannot lay whole counts as stranding it, or as a chord to be cut.
	 *
	 * <p>ekran's, and it is a gap rather than a tuning: <em>"it's fine if the next chord can't fit
	 * entirely inside the next lane -- that's why we have cuts."</em> {@link #strandsNext} sweeps the
	 * following lane and calls it stranded when the sweep does not reach its first event, and a sweep
	 * only asks whether a chord <b>fits</b>. A chord that will not fit is precisely the chord that
	 * gets cut, and the walk cuts a lane's first event on purpose -- {@code couldSplit} carries an
	 * exemption for it, because the first event of a lane is the one that lands wherever the staircase
	 * happened to put it.</p>
	 *
	 * <p>What it costs to have it wrong: the lane before is told its cut would strand its neighbour,
	 * so the cut is vetoed, so it looks for a pad close instead, and where the walk cannot afford that
	 * pad the chord is laid whole and the lane comes to rest outside its wall. ekran read one at
	 * Guardian 16 wide over four floors -- and the lane it was protecting turns on its own wall with
	 * room to spare.</p>
	 *
	 * <p>On Guardian nothing exceeds twenty-four notes, and a headed cut of twenty-four is a
	 * transition, nine cells and a staircase. Inside fifteen at every kind of turn. So very nearly
	 * every chord in that song can be cut wherever it lands, and reading them as stranded vetoes cuts
	 * that were never in danger.</p>
	 *
	 * <p><b>Off, and the reasoning is not what is wrong with it.</b> Measured on Guardian, every size:
	 * letting those cuts through takes 20 breached lanes and 86 blocks to <b>37 and 157</b>. Applied
	 * inside {@link #strandsNext} instead -- where a false is also read as "this lane closes cleanly,
	 * book nothing" -- it is worse still, 48 and 202, because it stops the planner laying pads other
	 * lanes rely on.</p>
	 *
	 * <p>So the veto is earning its keep by an argument better than the one it states. The cuts it
	 * forbids really can be made; making them costs more than it saves, and what that cost is has not
	 * been read off a lane yet. Until it has, this stays off and
	 * {@link #CUTS_WHEN_THE_VETOED_LANE_CANNOT_TURN} handles the narrower case -- a lane that has been
	 * refused its cut <em>and</em> cannot turn, which is the one where the veto's alternative does not
	 * exist at all.</p>
	 */
	static boolean STRANDED_CHORD_MAY_STILL_CUT = false;

	/**
	 * Whether the chord after a cut may use the pair of slots behind it.
	 *
	 * <p>The walk set {@code columnBehindBusy = true} after every split, because the far half's first
	 * pair of notes stands alongside the staircase's powered stone. True about the far half, and not
	 * the question: {@code columnBehindBusy} is read by the chord <em>after</em> it and asks whether
	 * that chord may hang notes in the two slots behind its own repeater.</p>
	 *
	 * <p>ekran: <em>"there's no reason behind-busy should be true. after a cut, if it's the first
	 * chord, they always can -- we don't place stacked heads at the bottom of a cut."</em> The file
	 * agrees with them everywhere else. {@link #takesTheGapBehind} is {@code style.stacked() && ...},
	 * the far half is laid as {@link ChordStyle#BUS}, and a bus keeps its notes beside its stone with
	 * the instrument blocks under those -- a column further back than the slots in question. Every
	 * other site that tracks the gap would answer false.</p>
	 *
	 * <p>An empty far half stays busy: nothing was laid after the staircase, so what stands behind the
	 * next chord is the landing itself.</p>
	 *
	 * <p><b>Off, and the reasoning is not what is wrong with it.</b> The machine plays either way --
	 * {@code unreached=0 readWrong=0} in both arms, read back with {@link NoteMachineReader} over
	 * eight songs -- so nothing here is asserting something false any more. But the slots it frees get
	 * used, and a chord that uses them packs tighter: the library goes from 29 breached lanes and 135
	 * blocks to 31 and 176, against builds 11 columns shorter over 336 configs. Guardian is mixed,
	 * fewer dirty configs and lanes for more blocks.</p>
	 *
	 * <p>So it is a compaction trade wearing a correctness argument's clothes, and ekran's rule is
	 * that a breach is the last resort even where it costs space. One word to flip if the shorter
	 * builds are ever worth more than the forty blocks.</p>
	 */
	static boolean CUT_FAR_HALF_FREES_THE_GAP = false;

	/** How far dust carries a signal before something has to repeat it. */
	private static final int DUST_RANGE = 15;

	/**
	 * Dust cells a floor change spends before the next lane's first repeater reads it.
	 *
	 * <p>Five: the block the wire steps off, and one for each level of the staircase. A climb coming
	 * straight off a bus skips the first of them, which is a block in hand and not a block short.</p>
	 */
	private static final int TURN_DUST_CELLS = 5;

	/**
	 * The cells that will fill the end of a lane, as a delay each: zero for dust, more for a repeater.
	 *
	 * <p>Planned before any of it is placed, because whether the wire survives the turn is what
	 * decides whether the lane may turn at all -- and a pad written down and then not turned on would
	 * be sitting in the next module's way.</p>
	 *
	 * @param signal what the wire is worth at the end of it, which the turn spends
	 * @param delaySpent ticks taken from the wait before the next event, which its own repeater no
	 *     longer has to hold
	 */
	private record Pad(List<Integer> cells, int signal, int delaySpent) {
		static Pad none(int signal) {
			return new Pad(List.of(), signal, 0);
		}
	}

	/**
	 * Works out how to fill a lane from where it stopped to the wall it is meant to turn at.
	 *
	 * <p>Dust for preference: it costs a block and no time, so an event keeps the tick it was written
	 * for however far it is nudged. What dust cannot do is carry: fifteen blocks from the last
	 * repeater the signal is gone, and a lane that has just laid a bus of fifteen has none of that
	 * left. So where the wire would not reach the top of the staircase, one cell of the pad becomes a
	 * repeater instead -- which costs a tick, taken out of the wait the next event was going to spend
	 * on its own repeater anyway.</p>
	 *
	 * <p>When there is neither range nor a tick to spare the pad stops short, and the caller is meant
	 * to read that and not turn: better a lane that runs long than a lane that hands the rest of the
	 * song to a wire that fades out halfway up.</p>
	 */
	private static Pad planPad(int columns, int signal, int turnCells, int spareDelay) {
		List<Integer> cells = new ArrayList<>();
		int spent = 0;
		int remaining = columns;
		while (remaining > 0) {
			if (signal >= remaining + turnCells) {
				for (; remaining > 0; remaining--) {
					cells.add(0);
					signal--;
				}
				break;
			}
			if (spareDelay - spent < 1) {
				// Nothing left to pay a repeater with. Get as near the wall as the wire alone
				// reaches, keeping back what the turn will need, and leave the caller to read that
				// the wall was not made and think again about turning at all.
				while (remaining > 0 && signal > turnCells) {
					cells.add(0);
					signal--;
					remaining--;
				}
				break;
			}
			// A repeater, placed as late as it can be: dust up to the last cell from which the rest
			// still fits inside one run, or as far as this wire goes, whichever comes first. Placing
			// them early instead spends the ticks on cells that did not need them and leaves the pad
			// short of the wall with nothing to pay for the rest.
			int before = Math.min(Math.max(0, remaining - 1 - (DUST_RANGE - turnCells)), signal);
			for (int cell = 0; cell < before; cell++) {
				cells.add(0);
				signal--;
				remaining--;
			}
			// A tick each, the least a repeater can hold. Taking the longest it can hold instead
			// spends the whole wait on the first one and leaves the rest of the pad with nothing to
			// buy a second with, which is how a pad that needed two came up short of the wall.
			cells.add(1);
			spent++;
			signal = DUST_RANGE;
			remaining--;
		}
		return new Pad(List.copyOf(cells), signal, spent);
	}

	/**
	 * The same pad with its repeaters made to hold a stated total between them, or null if they
	 * cannot: none to hold it, too few to reach it, or too many to add up to so little.
	 *
	 * <p>For a pad that has to account for the whole wait before the next event, because that event
	 * is going to be carried over the turn without a repeater of its own.</p>
	 */
	private static Pad spending(Pad pad, int total) {
		int repeaters = (int)pad.cells().stream().filter(delay -> delay > 0).count();
		if (repeaters == 0 || total < repeaters || total > repeaters * MAX_LANE_SPACING) {
			return null;
		}
		List<Integer> cells = new ArrayList<>(pad.cells());
		int shared = total / repeaters;
		int over = total % repeaters;
		for (int index = 0; index < cells.size(); index++) {
			if (cells.get(index) > 0) {
				cells.set(index, shared + (over-- > 0 ? 1 : 0));
			}
		}
		return new Pad(List.copyOf(cells), pad.signal(), total);
	}

	/**
	 * How many columns of dust in front of an event put its far end exactly on the wall.
	 *
	 * <p>Asked by trying, because the answer is not the arithmetic it looks like: a stacked module
	 * takes a column of its own now and then to land on the beat it needs, and whether it does turns
	 * on where it starts -- which is what this is moving. So a pad of four can lengthen the event by
	 * one and overshoot where a pad of three would have landed it.</p>
	 *
	 * @param busy whether the pair of slots behind the module is already spoken for, which is what
	 *     drops a full stacked module to a bus. Passed in rather than assumed, because that drop is
	 *     the difference between a module two columns long and one five columns long -- and a pad
	 *     measured against the shorter one lands the lane past the wall it was meant to stop at.
	 * @param limit how many blocks of dust the wire arriving here can afford
	 * @return the pad, or -1 if no pad within the limit lands the event on the wall
	 */
	private static int prePad(int startX, int stepX, EventGroup event, int wait, boolean busy,
			Layout layout, int wall, int limit, boolean inTurn, ParityOracle parity) {
		// The far end lands a column short of the wall, because landing it *on* the wall is landing
		// the handover one past it -- see {@link #handoverReserve}.
		int target = wall - stepX * handoverReserve(layout);
		// The best pad that does not carry the chord past the target, for when none lands on it.
		//
		// An exact hit is not always available: a stacked module that nudges, or one whose shape the
		// walk changes under it, moves its own end by a column, so the search can step from short of
		// the target to past it without ever standing on it. Returning -1 there throws the whole pad
		// away and the lane keeps every column of its gap -- which is the gap the run then has to
		// cover, and dying on the staircase is exactly what the pad exists to prevent.
		//
		// Measured: 183 of 837 wanted pads came back -1, better than a fifth of them.
		int nearest = -1;
		for (int pad = 0; pad <= limit; pad++) {
			int end = landingOf(startX + stepX * pad, stepX, event, wait, busy, wall, layout, inTurn,
				parity).end();
			if (end == target) {
				return pad;
			}
			// Still short of where it is aimed, so this much pad is safe and more may yet be better.
			if ((target - end) * stepX > 0) {
				nearest = pad;
			}
		}
		return PREPAD_TAKES_THE_NEAREST ? nearest : -1;
	}

	/**
	 * Whether the pad in front of a lane's last chord may take the nearest fit when none is exact.
	 *
	 * <p>{@link #prePad} walks pad sizes until the chord's end lands exactly on the column the
	 * handover wants, and returns -1 if none does. An exact hit is not always there: a stacked module
	 * that nudges moves its own end by a column, so the search can step from short of the target to
	 * past it without ever standing on it. Refusing there keeps the whole gap, and the gap is what the
	 * run has to cover to reach the staircase -- which is the death the pad exists to prevent.</p>
	 *
	 * <p>Measured over eight songs at sixty configs each, before this: the pad in front is wanted 837
	 * times and laid 135. Of the 702 it gives up on, <b>183 are this</b> -- {@code ahead} came back
	 * -1. The rest asked for more columns than the arriving wire could lay and were abandoned rather
	 * than laying what they could, which is a separate question and a larger one.</p>
	 *
	 * <p><b>Off: it does what it says and does not pay.</b> The abandonments go from 183 to 3 and the
	 * pads actually laid from 135 to 165, and the library reads 29 lanes / 138 blocks against
	 * 33 / 142 -- four more breached lanes for one column off the worst breach and eighteen off the
	 * total length. Conduction is unchanged either way.</p>
	 *
	 * <p>So the exact-match search is not what holds this pad back; the wire is. Of 817 wanted pads,
	 * <b>633 ask for more columns than the arriving wire can lay</b> and are dropped whole rather than
	 * laying what they can -- which is what the pad behind already does. That is the one worth trying
	 * next.</p>
	 */
	static boolean PREPAD_TAKES_THE_NEAREST = false;

	/**
	 * Whether the pad in front lays as much as the wire reaches rather than all of it or none.
	 *
	 * <p>The pad behind already works this way: {@link #planPad} stops where the wire stops and hands
	 * back a short pad, and the comment beside it says a lane wanting a dozen columns off a wire worth
	 * eight lays what it can. The pad in front asked for the whole thing and dropped it otherwise --
	 * <b>633 of 817</b> wanted pads over the library, four fifths of every one it gives up on.</p>
	 *
	 * <p>Worth trying with {@link #PREPAD_TAKES_THE_NEAREST} rather than against it. They are the same
	 * mistake at two sizes -- a perfect pad or nothing -- and neither is likely to show its worth
	 * alone, because a pad that gets the chord closer without getting it there pays the columns and
	 * collects none of the benefit.</p>
	 *
	 * <p><b>Worse together, which refuted that.</b> Eight songs at sixty configs each:</p>
	 *
	 * <pre>
	 * nearest off  laysWhatItCan off   29 lanes / 138 blocks / worst 11
	 * nearest on   laysWhatItCan off   33 lanes / 142 blocks / worst 10
	 * nearest off  laysWhatItCan on    26 lanes / 166 blocks / worst 12
	 * nearest on   laysWhatItCan on    45 lanes / 264 blocks / worst 11
	 * </pre>
	 *
	 * <p>Not two halves of one fix -- two ways of laying an imperfect pad, and doing both compounds
	 * the imprecision. Alone this one is a trade rather than a loss: <b>three fewer lanes breach</b>
	 * and the ones that do go deeper, which is what a pad that moves a chord towards its wall without
	 * landing it there would be expected to do. Off because it is not a win, not because it is
	 * wrong.</p>
	 */
	static boolean PREPAD_LAYS_WHAT_IT_CAN = false;

	/** Lays a run of dust, on glass so that nothing under it comes alive. */
	private static Lane emitDust(PlacementPlan placements, Lane lane, int columns) {
		return emitDust(placements, lane, columns, false);
	}

	/**
	 * @param raised whether this run continues at bus height. It has to when the pad in front of it
	 *     was raised: the pin is laid after the pad and before the staircase, so a pin that drops
	 *     back to the path leaves the climb starting two rungs above a wire that is no longer there.
	 *     That is a staircase which builds cleanly and conducts nothing.
	 */
	private static Lane emitDust(PlacementPlan placements, Lane lane, int columns, boolean raised) {
		return emitDust(placements, lane, columns, raised, raised ? "padRaised" : "pad");
	}

	/**
	 * @param why which run of dust this is, for the collision marker to name. Four call sites lay
	 *     dust and they all reported {@code pad}, so a marked cell said only that some pad somewhere
	 *     had wanted it -- which is the question, not the answer.
	 */
	private static Lane emitDust(PlacementPlan placements, Lane lane, int columns, boolean raised,
			String why) {
		placements.placing(why);
		for (int cell = 0; cell < columns; cell++) {
			// The first cell is the only one that can be against the module behind, and a corner is
			// never tradeable -- the corner cell belongs to the route and no bus may stand in it.
			// The tail already buses itself for a corner, on repeaterComesOutOf.
			//
			// A raised cell is tradeable and was excluded here on the assumption that it was not. It is
			// the most tradeable shape of the lot: raised means stone at bus height with dust over it,
			// which is exactly what a cell of bus tail is. That exclusion is the whole of
			// ultra-stress-enormous-chord at 24 wide over three floors.
			if (cell == 0 && !"corner".equals(why)
					&& (placements.softTip() || placements.softBehind())
					&& undoTheSoftTailFor(placements, lane.pos())) {
				placements.padded(why + "UndidTheTail");
				lane = lane.ahead(1);
				continue;
			}
			if (raised) {
				addRaisedPad(placements, lane.pos());
			} else {
				addParityPad(placements, lane.pos());
			}
			lane = lane.ahead(1);
		}
		return lane;
	}

	/** Lays a planned pad down, and hands back the block the turn now starts on. */
	private static Lane emitPad(PlacementPlan placements, Lane lane, Pad pad) {
		return emitPad(placements, lane, pad, "padClosing");
	}

	/**
	 * @param why which of the three pads this is. They are laid for quite different reasons and the
	 *     census could not tell them apart, which made the one number that matters most for
	 *     compactness -- how much lane is filled with wire rather than music -- impossible to aim at.
	 */
	private static Lane emitPad(PlacementPlan placements, Lane lane, Pad pad, String why) {
		return emitPad(placements, lane, pad, why, -1);
	}

	/**
	 * @param raiseAfter how many cells stay on the path before the rest run at bus height, or -1 to
	 *     lay the whole pad on the path -- see {@link #raiseAfter}. Zero off a bus, where the wire is
	 *     already up; one off anything else, because a lane ending on a note block has nothing at bus
	 *     height to step off and that first cell is what gives it one.
	 */
	private static Lane emitPad(PlacementPlan placements, Lane lane, Pad pad, String why,
			int raiseAfter) {
		// Named for the collision marker, which otherwise reports whatever the last shape to lay a
		// block called itself. A pad's cells were coming out as "pad" -- the label a run of dust sets
		// -- so a marked cell said a pad had wanted it and could not say which pad or from where.
		placements.placing(why);
		int cell = -1;
		for (int delay : pad.cells()) {
			cell++;
			boolean raised = raiseAfter >= 0 && cell >= raiseAfter;
			if (delay == 0) {
				placements.padded(why + (raised ? "Raised" : ""));
				if (cell == 0 && (placements.softTip() || placements.softBehind())
						&& undoTheSoftTailFor(placements, lane.pos())) {
					placements.padded(why + "UndidTheTail");
				} else if (raised) {
					addRaisedPad(placements, lane.pos());
				} else {
					addParityPad(placements, lane.pos());
				}
			} else {
				placements.padded(why + "Repeater");
				// A corner takes the dust and the repeater stands one along, here as everywhere else.
				lane = pastAnyCorner(placements, lane);
				// Stone rather than glass, because a repeater needs something to stand on -- and it is
				// safe here where dust is not, since a repeater leaves the block under it alone.
				set(placements, lane.pos(), "minecraft:stone");
				set(placements, lane.pos().above(), "minecraft:repeater[facing="
					+ repeaterFacing(lane.travel()) + ",delay=" + delay + "]");
			}
			lane = lane.ahead(1);
		}
		return lane;
	}

	/**
	 * Carries the signal one floor up at the end of a lane, and turns it around.
	 *
	 * <p>Dust climbs a block at a time, and a step up needs the block that would otherwise sit
	 * between the two dusts to be see-through -- so the staircase is glass, transparent enough for
	 * the step to connect and solid enough to hold the next dust up. Alternating between two columns
	 * keeps the whole turn two blocks deep.</p>
	 *
	 * <p>A lane that ends on a chord of four or more joins the climb two rungs in. That chord built
	 * a bus -- stone at the note level with dust along the top of it -- so the live wire is already
	 * a block up, and the first two rungs would only be rebuilding what is there. A lane ending on
	 * a smaller chord ends on a note block instead, a block lower, and needs them.</p>
	 *
	 * @param fromBus whether the lane's last event was wide enough to have built a bus
	 * @return the cursor for the next lane, which travels back the way this one came
	 */
	private static BlockPos addGlassClimb(PlacementPlan placements, BlockPos cursor,
			Direction travel, boolean fromBus, int time) {
		placements.placing("climb");
		placements.turnedAt(cursor);
		BlockPos near = cursor;
		BlockPos far = cursor.relative(travel);
		if (!fromBus) {
			placements.powered(near, "minecraft:stone", time);
			set(placements, near.above(), "minecraft:redstone_wire");
		}
		for (int step = fromBus ? 2 : 1; step <= CUBE_FLOOR_HEIGHT; step++) {
			BlockPos column = step % 2 == 1 ? far : near;
			set(placements, column.above(step), "minecraft:glass");
			set(placements, column.above(step + 1), "minecraft:redstone_wire");
		}
		// The next repeater stands one back the way we came and reads the top of the climb, which
		// is the block in front of it.
		return cursor.relative(travel.getOpposite()).above(CUBE_FLOOR_HEIGHT);
	}

	/**
	 * Carries the signal one floor down at the end of a lane, and turns it around.
	 *
	 * <p>Not the climb upside down. Going up, the block between two dusts has to be see-through and
	 * glass obliges. Going down, dust cannot step onto glass at all -- the staircase has to be
	 * solid, and a solid staircase cannot alternate between two columns, because the block it would
	 * step onto is the one already holding up the step two above it.</p>
	 *
	 * <p>So it spirals instead: four positions around a two-by-two column, a block down at each, so
	 * no step ever lands directly beneath the one before last. That is why a descent comes out a
	 * block further along than a climb, and a block to the side as well.</p>
	 */
	/**
	 * The same descent, four cells instead of six, for a lane arriving on a bus.
	 *
	 * <p>A bus runs a level above its lane. So the spiral does not need the cell the ordinary
	 * descent spends standing on the lane's own level to meet it: anchored one column back, its
	 * first rung's wire sits directly below the last block of bus and one across, which is a
	 * step down and connects on its own. That is one cell saved.</p>
	 *
	 * <p>The other is the step off. The ordinary descent lands a column past its spiral and walks
	 * back into it, which is a column of dust; this one lands on the column the spiral started from,
	 * where the far half of the chord was going to open anyway. The far half does not move -- it is
	 * built in exactly the spot the old landing plus its step off arrived at -- which is what makes
	 * this safe to swap in: only the staircase between the two halves changes.</p>
	 *
	 * <p>Four cells rather than six is two more cells of bus each side, so a split over a descent
	 * reaches {@code (15 - 4) * 2 = 22} notes where it used to reach eighteen.</p>
	 */
	private static BlockPos addSplitBusDescent(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction depth, int time) {
		placements.placing("descent4");
		placements.turnedAt(cursor);
		List<BlockPos> ring = List.of(
			cursor,
			cursor.relative(depth),
			cursor.relative(travel).relative(depth),
			cursor.relative(travel));
		// Starting level with the lane, not a step below it. The bus hands over from two levels up,
		// so the four rungs' wire runs 65, 64, 63, 62 to a lane at 60 -- the last of them level with
		// the far half's own bus, which is what lets the far half meet it head on instead of being
		// stepped off onto. Anchored a step lower, the second rung stands in the air a note block
		// needs above it and the build refuses outright, which is how this was found.
		for (int step = 1; step <= CUBE_FLOOR_HEIGHT; step++) {
			// ekran, from the blocks: the last rung does not have to come down to the repeater's own
			// level to feed it. A repeater reads the block behind it, and dust resting on top of that
			// block is what powers it -- so the final stone stands beside the landing rather than
			// under the dust that used to reach across to it, and the run is a cell shorter.
			//
			// Guardian at twenty-four wide over five floors laid the old shape as
			//   y=65   w0 >1 ST      wire level with the repeater, behind it
			// where ekran's hand-built descent is
			//   y=129  w0            dust one level up ...
			//   y=128  ST >1 NB      ... on the stone the repeater actually reads
			int drop = step - 1;
			BlockPos stone = ring.get((step - 1) % ring.size()).below(drop);
			placements.powered(stone, "minecraft:stone", time);
			set(placements, stone.above(), "minecraft:redstone_wire");
		}
		return cursor.below(CUBE_FLOOR_HEIGHT);
	}

	/**
	 * The short way down where it fits, the old way where it does not.
	 *
	 * <p>Falling back to the six-cell spiral on a collision was tried and is not kept. It cured the
	 * 91 refusals and made everything else worse: {@link #turnCost} charges every descent four, so a
	 * build that quietly spends six leaves the planner and the walk disagreeing by two cells, and
	 * the depth came out worse than not taking the short descent at all. A charge that does not know
	 * which spiral it got is the bug this file keeps producing.</p>
	 *
	 * <p>So the collisions stand as refusals until the columns the short spiral wants can be asked
	 * about before the plan is made, rather than discovered while building.</p>
	 */
	private static BlockPos descend(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction depth, int time) {
		if (!UNIVERSAL_FOUR_DESCENT) {
			return addSpiralDescent(placements, cursor, travel, depth, time);
		}
		return addSplitBusDescent(placements, cursor, travel, depth, time);
	}

	private static BlockPos addSpiralDescent(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction depth, int time) {
		placements.placing("descent6");
		placements.turnedAt(cursor);
		placements.powered(cursor, "minecraft:stone", time);
		set(placements, cursor.above(), "minecraft:redstone_wire");
		List<BlockPos> ring = List.of(
			cursor.relative(travel),
			cursor.relative(travel).relative(depth),
			cursor.relative(travel, 2).relative(depth),
			cursor.relative(travel, 2));
		for (int step = 1; step <= CUBE_FLOOR_HEIGHT; step++) {
			BlockPos stone = ring.get((step - 1) % ring.size()).below(step);
			placements.powered(stone, "minecraft:stone", time);
			set(placements, stone.above(), "minecraft:redstone_wire");
		}
		return cursor.relative(travel).below(CUBE_FLOOR_HEIGHT);
	}

	/**
	 * The narrowest corridor that still folds this walk into {@code floors} floors or fewer.
	 *
	 * <p>Two steps because the cheap answer is close but not exact: the estimate reads a lane
	 * partition and the walk decides its lanes as it goes, so they differ by a lane here and there
	 * -- enough to spill one lane onto a floor of its own. Walking it dry costs nothing and settles
	 * it.</p>
	 */
	private static int foldedCorridor(List<EventGroup> events, BlockPos origin, Direction forward,
			int laneWidth, int floors) {
		int corridor = cubeCorridor(
			List.copyOf(laneSpacings(events, cubeLanePartition(events, laneWidth)).values()), floors);
		for (int attempt = 0; attempt < 12
			&& walkFolded(events, origin, forward, laneWidth, corridor, PlacementPlan.dry(), Layout.STANDARD) > floors;
				attempt++) {
			corridor += Math.max(MAX_LANE_SPACING, corridor / 8);
		}
		return corridor;
	}

	private static PastePlan createCompactPastePlan(BlockPos origin, Direction forward, List<EventNote> notes) {
		List<EventGroup> events = eventGroups(notes);
		CompactLayout layout = chooseCompactLayout(events);
		PlacementPlan placements = new PlacementPlan();
		BlockPos cursor = origin;
		Direction travel = forward;
		Direction laneStep = forward.getClockWise();
		int currentTime = 0;
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			int delay = event.time() - currentTime;
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements,
				Lane.straight(cursor, travel, laneStep), delay);
			currentTime = event.time();
			Integer spacing = layout.spacingAt().get(index + 1);
			if (spacing != null && fitsInCorner(event)) {
				cursor = addTurnEventModule(placements, trigger.cursor(), travel, laneStep, spacing,
					trigger.triggerDelay(), event.notes());
				travel = travel.getOpposite();
				continue;
			}
			cursor = addSpatialEventModule(placements,
				Lane.straight(trigger.cursor(), travel, laneStep), trigger.triggerDelay(),
				event.notes(), false).lane().pos();
			if (spacing != null) {
				cursor = addCompactTurn(placements, cursor, travel, laneStep, spacing, event.time());
				travel = travel.getOpposite();
			}
		}
		return placements.finish(PasteMode.COMPACT, origin);
	}

	/**
	 * Folds the signal path into a cube: serpentine across a floor, climb a glass riser, then
	 * serpentine back across the next floor.
	 *
	 * <p>Floors deliberately do not map to composer layers. The path is one continuous chain
	 * through the timeline, exactly as in the flat compact mode, and a layer only decides which
	 * instrument block sits under a note. A conversion can easily produce dozens of layers, so
	 * giving each one a floor would neither fit nor mean anything musically.</p>
	 */
	private static PastePlan createCubePastePlan(BlockPos origin, Direction forward,
			List<EventNote> notes, int maxFloors) {
		List<EventGroup> events = eventGroups(notes);
		int totalLength = totalEventLength(events);
		int floors = chooseCubeFloors(totalLength, maxFloors);
		int perFloor = Math.max(1, (totalLength + floors - 1) / floors);
		int lanesPerFloor = Math.max(1,
			(int)Math.round(Math.sqrt(perFloor / (double)MAX_LANE_SPACING)));
		int longestEvent = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		// The wall has to clear the longest single event, or an event that cannot fit between the
		// walls would turn on every attempt and never advance.
		int laneWidth = Math.max(longestEvent + 2, Math.max(1, perFloor / lanesPerFloor));
		// A floor is as wide as the stack is, not as many lanes as the one below it. Counting lanes
		// was fine while every lane was four apart; now that a sparse lane pair sits two apart, a
		// count leaves a floor of narrow lanes covering half the footprint of the floor under it --
		// and a floor of wide ones hanging off the edge of it.
		int corridor = foldedCorridor(events, origin, forward, laneWidth, floors);
		PlacementPlan placements = new PlacementPlan();
		walkFolded(events, origin, forward, laneWidth, corridor, placements, Layout.STANDARD);
		return placements.finish(PasteMode.COMPACT_CUBE, origin);
	}

	/**
	 * Walks the events back and forth between two walls, placing as it goes and climbing a floor
	 * whenever the fold reaches the far side of its corridor.
	 *
	 * @param corridor how far, across the lanes, a floor may reach from the edge it starts at.
	 *     {@link Integer#MAX_VALUE} means it never has to climb, which is the flat lane mode.
	 * @return how many floors the stack ended up with
	 */
	private static int walkFolded(List<EventGroup> events, BlockPos origin, Direction forward,
			int laneWidth, int corridor, PlacementPlan placements, Layout layout) {
		int floorsUsed = 1;
		BlockPos cursor = origin;
		Direction travel = forward;
		Direction laneStep = forward.getClockWise();
		// Lanes turn at a fixed wall rather than after a fixed amount of content. Events run up to
		// a dozen blocks long, so a length budget let each lane stop anywhere in a wide window and
		// the ragged ends compounded into visible shear across the stack.
		int nearWall = origin.getX();
		int farWall = origin.getX() + laneWidth;
		int currentTime = 0;
		int laneStart = 0;
		// See walkWall: whether the column a stacked module would want behind it is already claimed.
		boolean columnBehindBusy = false;
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			int delay = event.time() - currentTime;
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements,
				Lane.straight(cursor, travel, laneStep), delay);
			boolean roomBehind = !columnBehindBusy || !trigger.cursor().equals(cursor);
			currentTime = event.time();
			// One event of lookahead. Whether this event is the last of its lane has to be settled
			// before it is placed, because the last one is built into the corner; asking after the
			// fact, as this walk used to, leaves the corner with nothing to carry.
			int landing = cursor.getX() + travel.getStepX() * event.length();
			int next = index + 1 < events.size()
				? landing + travel.getStepX() * events.get(index + 1).length()
				: landing;
			boolean turnAfter = index + 1 < events.size()
				&& event.maxSafeTurnDistance() >= MAX_LANE_SPACING
				&& (next > farWall || next < nearWall);
			if (!turnAfter) {
				Placed placed = addChordModule(placements,
					Lane.straight(trigger.cursor(), travel, laneStep), trigger.triggerDelay(), event,
					0, roomBehind, false, Integer.MAX_VALUE, DUST_RANGE, layout);
				cursor = placed.lane().pos();
				columnBehindBusy = takesTheGapBehind(placed.style(), placed.busCells());
				continue;
			}
			// A corner has to be as wide as the two lanes it joins, so the next lane has to be
			// measured before it is walked. Where it ends is arithmetic -- the same walls, the same
			// rule -- and a corner built into an event leaves the travel position where the event's
			// trigger stood rather than past it.
			boolean cornerBuilt = fitsInCorner(event);
			int nextStart = cornerBuilt ? trigger.cursor().getX() : landing;
			int spacing = laneSpacing(laneReach(events, laneStart, index + 1),
				laneReach(events, index + 1,
					laneEnd(events, index + 1, nextStart, -travel.getStepX(), nearWall, farWall)));
			laneStart = index + 1;
			int nextLane = cursor.getZ() + laneStep.getStepZ() * spacing - origin.getZ();
			boolean riser = nextLane < 0 || nextLane > corridor;
			// Asked once the corner's width is known, which is why it cannot be folded into the
			// question above: how much of a corner is left depends on how far it reaches. A lane
			// ending on a stacked module has taken a cell or two of its own corner already, and an
			// event that no longer fits is laid out in the lane instead, with a plain turn after.
			if (!riser && cornerBuilt && cornerSlots(placements, trigger.cursor(), travel, laneStep,
					spacing).size() >= event.notes().size()) {
				cursor = addTurnEventModule(placements, trigger.cursor(), travel, laneStep, spacing,
					trigger.triggerDelay(), event.notes());
				travel = travel.getOpposite();
				columnBehindBusy = true;
				continue;
			}
			cursor = addChordModule(placements, Lane.straight(trigger.cursor(), travel, laneStep),
				trigger.triggerDelay(), event, 0, roomBehind, false, Integer.MAX_VALUE, DUST_RANGE,
			layout).lane().pos();
			// A turn or a riser is about to be built into the block the next module would stand
			// behind, so whatever this one did, the next one cannot stack.
			columnBehindBusy = true;
			if (riser) {
				// Land back into the floor rather than one step further out. Stepping out was
				// harmless while every floor was exactly as wide as the last, and is how the stack
				// crept past its own starting edge once they stopped being.
				cursor = addGlassRiser(placements, cursor, travel, laneStep.getOpposite(),
					currentTime);
				// Reverse both axes so the next floor retraces this one within the same volume.
				travel = travel.getOpposite();
				laneStep = laneStep.getOpposite();
				floorsUsed++;
			} else {
				cursor = addCompactTurn(placements, cursor, travel, laneStep, spacing, currentTime);
				travel = travel.getOpposite();
			}
		}
		return floorsUsed;
	}

	/**
	 * The narrowest corridor that still stacks the lanes in {@code floors} floors or fewer.
	 *
	 * <p>Dividing the lanes evenly is not enough. A floor stops when the <em>next</em> lane would
	 * cross the far side, so it always ends a little short of the corridor, and those shortfalls add
	 * up into one more floor carrying a single lane -- the thin slab on top that gives away that the
	 * stack was sized by arithmetic rather than by walking it.</p>
	 */
	private static int cubeCorridor(List<Integer> spacings, int floors) {
		int span = Math.max(MAX_LANE_SPACING, spacings.stream().mapToInt(Integer::intValue).sum());
		for (int corridor = MAX_LANE_SPACING; corridor < span; corridor++) {
			if (cubeFloorCount(spacings, corridor) <= floors) {
				return corridor;
			}
		}
		return span;
	}

	/** How many floors the lanes need if a floor may reach {@code corridor} across. */
	private static int cubeFloorCount(List<Integer> spacings, int corridor) {
		int used = 1;
		int position = 0;
		int step = 1;
		for (int spacing : spacings) {
			int next = position + step * spacing;
			if (next < 0 || next > corridor) {
				used++;
				// A riser lands one lane back into the floor it leaves, and the next floor
				// retraces it from there.
				position -= step;
				step = -step;
			} else {
				position = next;
			}
		}
		return used;
	}

	/**
	 * Where the lane starting at {@code from} ends, without placing anything.
	 *
	 * <p>Mirrors the turn rule in the walk itself: a lane ends when the event after the next one
	 * would cross a wall.</p>
	 */
	private static int laneEnd(List<EventGroup> events, int from, int position, int step,
			int nearWall, int farWall) {
		for (int index = from; index < events.size(); index++) {
			int landing = position + step * events.get(index).length();
			if (index + 1 < events.size()
					&& events.get(index).maxSafeTurnDistance() >= MAX_LANE_SPACING) {
				int next = landing + step * events.get(index + 1).length();
				if (next > farWall || next < nearWall) {
					return index + 1;
				}
			}
			position = landing;
		}
		return events.size();
	}

	/** The lanes a wall-bounded walk needs, as the event index each one starts at. */
	private static List<Integer> cubeLanePartition(List<EventGroup> events, int laneWidth) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		int position = 0;
		int step = 1;
		for (int index = 0; index < events.size(); index++) {
			position += step * events.get(index).length();
			if (index + 1 >= events.size()
					|| events.get(index).maxSafeTurnDistance() < MAX_LANE_SPACING) {
				continue;
			}
			int next = position + step * events.get(index + 1).length();
			if (next > laneWidth || next < 0) {
				starts.add(index + 1);
				step = -step;
			}
		}
		return starts;
	}

	/** Picks the floor count whose largest dimension is smallest, i.e. the most cube-like. */
	private static int chooseCubeFloors(int totalLength, int maxFloors) {
		int best = 1;
		int bestSpan = Integer.MAX_VALUE;
		for (int floors = 1; floors <= maxFloors; floors++) {
			int perFloor = Math.max(1, (totalLength + floors - 1) / floors);
			int side = (int)Math.ceil(2.0 * Math.sqrt(perFloor));
			int height = floors * CUBE_FLOOR_HEIGHT;
			int span = Math.max(side, height);
			if (span < bestSpan) {
				bestSpan = span;
				best = floors;
			}
		}
		return best;
	}

	/**
	 * Carries the signal up one floor in a 1x2 footprint by alternating glass and dust between two
	 * columns.
	 *
	 * <p>Each dust sits one block up and one across from the previous one, which redstone connects
	 * diagonally, and the block directly above each lower dust is glass. Because glass is
	 * transparent the diagonal is allowed, and because glass still supports dust the next step has
	 * something to sit on. This only works upward.</p>
	 *
	 * @return the floor-level cursor for the first module of the next floor, which travels back
	 *     the way it came
	 */
	private static BlockPos addGlassRiser(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, int time) {
		placements.turnedAt(cursor);
		placements.powered(cursor, "minecraft:stone", time);
		set(placements, cursor.above(), "minecraft:redstone_wire");
		BlockPos columnA = cursor.relative(travel);
		BlockPos columnB = columnA.relative(travel);
		set(placements, columnA.above(1), "minecraft:glass");
		set(placements, columnA.above(2), "minecraft:redstone_wire");
		set(placements, columnB.above(2), "minecraft:glass");
		set(placements, columnB.above(3), "minecraft:redstone_wire");
		set(placements, columnA.above(3), "minecraft:glass");
		set(placements, columnA.above(4), "minecraft:redstone_wire");
		set(placements, columnB.above(4), "minecraft:glass");
		set(placements, columnB.above(5), "minecraft:redstone_wire");
		// Step sideways at the top so the path can leave heading back the way it came. Without
		// this the next floor could only continue forwards, since a repeater reads from the side
		// it faces and would otherwise have the riser behind it.
		BlockPos landing = columnB.relative(laneStep);
		set(placements, landing.above(CUBE_FLOOR_HEIGHT), "minecraft:stone");
		set(placements, landing.above(CUBE_FLOOR_HEIGHT + 1), "minecraft:redstone_wire");
		return landing.relative(travel.getOpposite()).above(CUBE_FLOOR_HEIGHT);
	}

	private static List<EventGroup> eventGroups(List<EventNote> notes) {
		return eventGroups(notes, Layout.STANDARD);
	}

	/**
	 * Groups the notes into chords and settles how each one will be built.
	 *
	 * <p>Settled here rather than where the blocks go down because everything after this measures
	 * the walk before it walks it, and a chord's shape is what decides how long it is. The one
	 * thing this cannot know is where the lanes will turn, and a turn is the one thing that can
	 * take the stacked module away: the run of powered stone a turn is made of lies at the same
	 * level as the module's four low notes, so a module built straight after one would have that
	 * run alongside notes that are not its. The walk drops such a chord to a bus when it gets
	 * there. Only ever dropping and never adding is what keeps this honest -- a lane can then come
	 * out longer than it was measured, which costs a little width, where the other way round would
	 * cost a wrong note.</p>
	 */
	private static List<EventGroup> eventGroups(List<EventNote> notes, Layout layout) {
		List<EventGroup> result = new ArrayList<>();
		int currentTime = 0;
		boolean previousTookTheGap = false;
		for (int index = 0; index < notes.size();) {
			int time = notes.get(index).time();
			List<EventNote> chord = new ArrayList<>();
			while (index < notes.size() && notes.get(index).time() == time) {
				chord.add(notes.get(index++));
			}
			int delay = time - currentTime;
			int delayRepeaters = Math.max(0, (delay - 1) / 4);
			// One repeater between two modules and they stand two apart, which is close enough that
			// the four low slots of one are the four low slots of the other. Any further and they
			// are its own.
			boolean roomBehind = delayRepeaters > 0 || !previousTookTheGap;
			ChordStyle style = chooseStyle(layout, chord, roomBehind);
			int busLength = (chord.size() + 1) / 2;
			// A stacked-bus is neither of the two shapes this used to know about: it is a head, a
			// transition and a bus of whatever the head could not take. Measured as a module it came
			// out at two columns however long its tail was.
			int tailCells = 0;
			if (style.busHeaded()) {
				StackedBusSplit split = splitFor(style, chord);
				tailCells = split == null ? 0 : stackedBusTailColumns(split.tail());
			}
			int eventLength = style == ChordStyle.BUS ? 1 + busLength
				: style.busHeaded()
					? STACKED_CELLS + STACKED_BUS_TRANSITION + tailCells
					: 2;
			// And it ends on its tail, so what a turn may still cross is measured from the tail and
			// not from the whole chord -- which is the point of the head.
			int maxSafeTurnDistance = style == ChordStyle.BUS ? Math.max(0, 13 - busLength)
				: style.busHeaded() ? Math.max(0, 13 - tailCells)
				: 13;
			result.add(new EventGroup(time, List.copyOf(chord), delayRepeaters + eventLength,
				maxSafeTurnDistance, style, laneReachOf(layout, style, chord)));
			currentTime = time;
			previousTookTheGap = takesTheGapBehind(style, tailCells);
		}
		return List.copyOf(result);
	}

	/**
	 * How a chord is built, given what a lane may use and what came before it.
	 *
	 * <p>Two stacked modules a repeater apart stand two blocks apart, which means the two low slots
	 * ahead of one are the two low slots behind the other. The first module takes them, and takes
	 * them for good: it sounds them at its own tick, and the second module's relays only reach them
	 * again afterwards, adding no edge to a note already powered. So reaching forward is always
	 * free and reaching back is what has to be asked for.</p>
	 *
	 * <p>A turn takes the pair behind as well. A turn is a run of powered stone at the level the
	 * low notes hang at, live at the tick of the lane it is leaving -- which is <em>earlier</em>
	 * than the module that follows it, and early is the one direction a note cannot be reached
	 * from.</p>
	 */
	/**
	 * Whether a chord of this shape leaves the low slots behind the next module spoken for.
	 *
	 * <p>Two stacked modules a repeater apart stand two columns apart, and the pair of low slots
	 * between them is the same pair for both -- the first one down takes it. That is the whole of
	 * the rule, and it is a rule about <em>two modules two columns apart</em>.</p>
	 *
	 * <p>A stacked-bus that ends on even one cell of bus is not two columns from whatever follows
	 * it: its head sits back behind the transition cell and the whole tail. The slots in question
	 * hang under the next module's own repeater, and a bus lying behind that repeater never reaches
	 * them -- a bus keeps its notes beside its stone and its instrument blocks under those, a column
	 * further back. So the head that follows a stacked-bus has its back slots free, and asking it to
	 * give them up cost 185,760 heads across the library for nothing at all.</p>
	 *
	 * <p>Ekran found this from a vertical slice of Do The Dance at forty wide over two floors, and
	 * was right about the geometry before it was measured.</p>
	 *
	 * <p>Every place that tracks the gap asks this one question, because they have to give the same
	 * answer: {@link #eventGroups} choosing the shape, {@link #landingOf} predicting the length, and
	 * the walk recording what it built. A lane whose planner and walk disagree about a column is a
	 * lane that comes to rest outside its wall.</p>
	 */
	private static boolean takesTheGapBehind(ChordStyle style, int busCells) {
		return style.stacked()
			&& !(GAP_ENDS_ON_BUS && style.busHeaded() && busCells >= 1);
	}

	/**
	 * Whether a chord cut across a staircase may open with a head of five when the back is taken.
	 *
	 * <p>An ordinary chord has had this since 1da9154; the cut asked for the back flanks
	 * unconditionally, so a chord that could have been cut with a short head was laid as a plain bus
	 * instead. Turning it on fixes both breaches traced on 2026-08-03 and takes library breaches from
	 * 588 to 519 with breach blocks from 11,082 to 8,865.</p>
	 *
	 * <p><b>On, and known to lose notes.</b> 3,353 note blocks the signal never reaches over 145 read
	 * builds, and one build that does not read back as its song. Located: Guardian at twenty-four
	 * wide over five floors, the first silent note at {@code 32 77 247}, and the six earliest are all
	 * one stacked module -- so the signal reaches the repeater on {@code 33 77 247} and stops there
	 * rather than failing in scattered places.</p>
	 *
	 * <p>It is on because ekran asked for it and because a build that pastes is a build that can be
	 * stood in front of. It was switched off once without being asked and that was mine to undo.
	 * Where to look: a head of five keeps its two low slots forward rather than behind, and the cut
	 * measures its far half from a tail length that assumed the other shape.</p>
	 */
	static boolean FRONT_ONLY_CUTS = true;

	/** Whether a stacked-bus that ends on a bus lets the next chord keep its back slots. */
	static boolean GAP_ENDS_ON_BUS = true;

	/** Whether a chord with no room behind opens with a head of five rather than giving it up. */
	static boolean FRONT_ONLY_HEADS = true;

	/** Whether a head may hold four, five or six notes rather than only the most it could fill. */
	static boolean VARIABLE_HEAD_NOTES = true;

	/** Whether a headed cut on the wrong parity moves a column instead of giving up the head. */
	static boolean SPLIT_NUDGES = true;

	/**
	 * The head and tail a chord of this shape opens with, or null if it cannot take a head.
	 *
	 * <p>The one place the head size is decided. {@link #eventGroups} sizes the chord from it,
	 * {@link #landingOf} predicts the landing from it and the walk builds from it, and a lane whose
	 * three sums disagree by a column is a lane that comes to rest outside its wall.</p>
	 */
	private static StackedBusSplit splitFor(ChordStyle style, List<EventNote> chord) {
		return style.busHeaded() ? stackedBusSplit(chord, backFlanksOf(style)) : null;
	}

	/**
	 * How many of the two slots behind a headed shape keeps: two, one, or none.
	 *
	 * <p>The one place that says it. Head size decides the tail, the tail decides the length, and
	 * three separate places make that sum -- so they read the style and the style is read here.</p>
	 */
	private static int backFlanksOf(ChordStyle style) {
		return style == ChordStyle.STACKED_BUS ? 2 : style == ChordStyle.STACKED_BUS_HALF ? 1 : 0;
	}

	/**
	 * Whether a short tail is laid as a simple chord rather than as a bus. ekran's.
	 *
	 * <p>The handover cell a stacked bus transitions on sits at the height of the lane rather than
	 * raised -- it is there so the tail comes on with no delay -- and that is what makes this
	 * possible: a bus cell stands a level up, but a simple chord's anchor stands exactly where the
	 * handover's dust can drive it. So a tail of three, which is two cells of bus, becomes one
	 * column; a tail of one or two is a column either way but comes out at lane height instead of
	 * bus height, which is the plane a rail column runs in.</p>
	 *
	 * <p>"Try our best", ekran: a tail that cannot make the shape stays a bus rather than the chord
	 * giving anything up. {@link #fitsSmallModule} is the same test every other simple chord is
	 * priced with, so a tail of three doors -- which cannot hold a shape whose middle has to conduct
	 * -- is refused here exactly as it would be anywhere else.</p>
	 */
	static boolean SIMPLE_TAIL_ON_A_STACKED_BUS = true;

	/**
	 * Whether a simple tail with a stone middle lays the cell of dust that makes it a bus tail.
	 *
	 * <p>ekran's, said three times before I stopped trying to predict it and just laid the block:
	 * <i>"Literally ALL it had to do was place a dust on top and the wire wouldn't have died"</i>.</p>
	 *
	 * <p>The rule is that a repeater must always come directly out of a simple tail, because the middle
	 * is soft powered -- lit by the handover's dust and nothing else -- so it drives a repeater and
	 * lights no dust of its own. Two attempts were made at knowing in advance when that would fail, and
	 * both were too narrow. {@link #repeaterComesOutOf} follows the route, so it sees a bend and the
	 * lane closing and nothing else. Hanging the answer off {@link #parityPadOrSplitRepeater} sees the
	 * parity pad and nothing else -- and a wait, a turn pad and a rail column all lay dust there too.
	 * <b>The set of things that can follow a tail is not enumerable from where the tail is laid.</b></p>
	 *
	 * <p>So it is not asked. A stone middle is the one middle with a free cell above it, dust there
	 * costs a single block, and what it produces is exactly a bus tail: {@link #layBus} anchors at
	 * {@code afterHead.ahead(1).above()} and lays stone there with dust over it and the notes either
	 * side -- the same stone, the same two slots, the same one column. The handover's own dust climbs
	 * onto it the way it climbs onto any bus. And it risks no length, because
	 * {@link #stackedBusTailColumns} already prices every tail as a bus, so this is the shape the plan
	 * was measured on. The whole cost is one cell of the lane's fifteen: {@code busCells} 0 becomes 1.</p>
	 *
	 * <p>A harp middle and a tail of three's kept note are note blocks, which insist on air above them.
	 * They have no cell to put dust in, stay soft tips, and give way instead --
	 * {@link #SIMPLE_TAIL_GIVES_WAY_TO_A_TIGHT_GAP}.</p>
	 */
	static boolean STONE_MIDDLE_CARRIES_ITS_OWN_DUST = true;

	/**
	 * Whether a harp is only taken for the tail's middle when the two sides cannot hold the tail.
	 *
	 * <p>The middle has no instrument block under it and sounds harp whatever was meant, so a harp is
	 * the one note that can stand there for free -- but that is a reason it <i>may</i>, not a reason it
	 * <i>should</i>. The sides hold two notes, so a tail of one or two never needs the middle for
	 * storage at all, and a harp put there anyway costs nothing except the thing that matters most: a
	 * note block insists on air above it, so the middle can no longer carry the cell of dust that makes
	 * this tail immune to whatever is laid in front of it.</p>
	 *
	 * <p>The harp loses nothing on a side -- a harp note block is built over air there too. So at two
	 * notes or fewer the middle is left to stone and the tail is dustable whatever it is made of, which
	 * takes the last shape that could not be rescued down to one: a tail of <b>three</b> with a harp
	 * in it, where the middle really is the only place the third note can go.</p>
	 */
	static boolean HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE = true;

	/**
	 * Whether a run may start off a stacked bus's handover, the way it already starts off a stacked
	 * chord's cross. ekran's, and the reason the tail work was asked for at all.
	 *
	 * <p>The handover is stone on the lane with dust over it. The dust powers the stone, and a
	 * repeater facing out of that stone reads it -- so the bottom rail starts instantly and the two
	 * columns {@link #addRailHead} would have spent are not spent. Until this, {@link #railStackSeed}
	 * refused every headed style outright, so on a song full of chords of eight and ten it never fired
	 * once.</p>
	 */
	static boolean RAIL_FROM_HANDOVER = true;

	/**
	 * Whether this tail is laid as a simple chord.
	 *
	 * <p>The one place that answers it, because the answer sets the module's length and three
	 * separate sums read that length. A shape built a column shorter than it was measured is a lane
	 * that comes to rest outside its wall, and that is the bug this file keeps producing.</p>
	 */
	private static boolean simpleTail(List<EventNote> tail) {
		return SIMPLE_TAIL_ON_A_STACKED_BUS && !tail.isEmpty() && tail.size() <= 3
			&& fitsSmallModule(tail);
	}

	/**
	 * Whether this tail's middle would be a note block rather than stone.
	 *
	 * <p>Exactly the negation of the stone case the builder works out, and written the same way round
	 * so the two cannot drift: the builder refuses the harp the middle below three
	 * ({@link #HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE}) and lays stone there instead, so at one or two
	 * notes the middle is stone whatever the tail is made of, and at three it is a note block either
	 * way -- a harp taken for the middle, or the third note kept in it because the two sides are full.
	 * A note block insists on air above it, which is the cell the dust would go in.</p>
	 *
	 * <p>It read {@code takeHarpNote(...) != null || size > 2} until the harp stopped taking the
	 * middle below three, and then it said yes for a two-note tail with a harp in it -- a tail whose
	 * middle is stone, carries its own dust and was never at risk. Wrong in the safe direction, which
	 * is why it cost columns rather than notes, and why nothing caught it.</p>
	 */
	private static boolean noteBlockInTheMiddle(List<EventNote> tail) {
		List<EventNote> hanging = new ArrayList<>(tail);
		if (HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE && hanging.size() <= 2) {
			return false;
		}
		return !(takeHarpNote(hanging) == null && hanging.size() <= 2);
	}

	/**
	 * Whether a simple tail with a note-block middle gives way where a pad could not be a repeater.
	 *
	 * <p>ekran's invariant: a repeater always comes directly out of a simple tail, and where padding
	 * moves that repeater forward the tail falls back to a bus instead -- two cells for a tail of
	 * three. <b>The tail is kept</b>; it is worth its complexity, and up to three notes is the shape.
	 * What is decided here is only <i>when</i> it gives way.</p>
	 *
	 * <p>A pad is survivable in two of the three cases. Where the delay is two or more the pad is spent
	 * as {@code r1} and the cell against the middle is a repeater after all
	 * ({@link #SPLIT_THE_PAD_REPEATER}); where the middle is stone the tail is finished off into a bus
	 * cell after the fact ({@link #SOFT_TIP_DUSTED_FOR_THE_PAD}). What is left is a note-block middle
	 * at a delay of one: no repeater is shorter than a tick, and there is no cell above a note block to
	 * put dust in. That one has to be decided before the tail goes down.</p>
	 *
	 * <p><b>Asked of the gap, not of the pad,</b> and that is an approximation with a known direction.
	 * Whether a pad is laid at all is {@code parityVerdict} read off blocks in the lane behind, which
	 * is a simulation and not arithmetic -- the walk says so itself: <i>a nudge is decided against
	 * blocks on the ground and no arithmetic can foresee it</i>. But the gap to the next event is known
	 * a whole event ahead, so a tight gap is refused and everything else is kept.</p>
	 *
	 * <p><b>It is wrong in both directions, and only one of them is harmless.</b> It gives up tails
	 * that would never have been padded, which costs nothing. But a raw gap of two is not the same as
	 * a trigger delay of two -- {@code spentPadding} comes out of it first -- so a tail kept at a gap
	 * of two can still meet a pad it cannot split. That is measured, not feared:
	 * {@code parityPadOnASoftTip} still reads 2 and 4 on two rows of all-of-the-lights with this on.
	 * Closing it means handing down the delay the next event will actually be triggered at, which is
	 * settled an event later than this. It costs the tails it does refuse nothing anyway:
	 * {@link #stackedBusTailColumns} already prices every tail as a bus, so the second column was
	 * reserved and the simple shape was only ever spending one of them. ekran: <i>"the bus just would
	 * have 2 cols"</i>.</p>
	 */
	static boolean SIMPLE_TAIL_GIVES_WAY_TO_A_TIGHT_GAP = true;

	/**
	 * The gap above which a simple tail is allowed to keep a note block in its middle.
	 *
	 * <p>The threshold behind {@link #SIMPLE_TAIL_GIVES_WAY_TO_A_TIGHT_GAP}, made a number because it
	 * had to be measured rather than argued. The rule wants the <b>delay the next chord's repeater is
	 * actually set to</b>, and what the walk can hand down is the <b>gap to the next event</b>. The
	 * two are not the same: padding is spent out of the gap first, so a gap of four can arrive as a
	 * delay of one, and a delay of one is the case no repeater is short enough for.</p>
	 *
	 * <p>So the gap is an upper bound and the threshold is the margin allowed for what padding eats.
	 * Swept over the library at five widths, once every site that lays a cell of dust after a module
	 * asks whether the module ended soft: <b>1 leaves 3 dead builds at 22,340 columns, 2 and 3 leave
	 * one at 22,410, and refusing the shape outright is 22,429.</b> Three is what ships -- it also
	 * takes 17 breach blocks off, where two does not -- so the three-note tail is kept wherever the
	 * next event is more than three ticks away, which is nearly everywhere it occurs.</p>
	 *
	 * <p><b>One dead build is left and it is known:</b> {@code ultra-stress-enormous-chord} at 24 wide
	 * over three floors, 1,245 notes in the shadow of a parity pad at a delay of one. Closing it is
	 * {@link #SIMPLE_TAIL_UNDONE_FOR_A_PAD}, and closing it is what makes a margin of one safe --
	 * which is the other 70 columns.</p>
	 *
	 * <p>Re-sweep it rather than trusting this: {@code -Dcensus.set=SIMPLE_TAIL_KEEPS_A_NOTE_MIDDLE_ABOVE=N}.
	 * The first sweep of it was taken against a builder with four unasked questions in it and said
	 * no margin was worth anything, which was true of that builder and false of this one.</p>
	 */
	static int SIMPLE_TAIL_KEEPS_A_NOTE_MIDDLE_ABOVE = 3;

	/**
	 * Whether a note-block middle gives the simple shape up always, rather than only at a tight gap.
	 *
	 * <p>The same move that worked for the stone middle, made for the middle that cannot take dust:
	 * stop asking whether a repeater will follow and take the shape that does not care. ekran's own
	 * fallback, and their point about what it costs -- <i>"bottom rail seeding is only canceled if my
	 * tail has more than 2 notes, since at 2, i can place notes on either side and still take up only
	 * one col"</i>. A tail of one or two is a single bus cell with the notes either side, which is the
	 * same column the simple shape spends, so at that size the fallback is free outright. Only a tail
	 * of three goes to two cells -- and {@link #stackedBusTailColumns} priced it at two from the
	 * start.</p>
	 *
	 * <p>Priced against the tight-gap guess rather than assumed better than it: the guess keeps more
	 * tails, and whether keeping them is worth the ones it gets wrong is a measurement.</p>
	 *
	 * <p><b>Off, and it was on for half a day.</b> When it went on, the library read <b>20 dead builds
	 * and 65,196 silenced notes</b> with the three-note tail kept, and no value of
	 * {@link #SIMPLE_TAIL_KEEPS_A_NOTE_MIDDLE_ABOVE} bought anything: the smallest margin that reached
	 * nought was one where the corridor came out the same length as never taking the shape at all.
	 * That measurement was against a builder in which <b>one of the five places that lay a cell of
	 * dust after a module asked whether the module ended soft</b>. With the other four asking too
	 * ({@link #padCellOrSplitRepeater}) the same sweep reads 3 dead builds at a margin of one and
	 * <b>one</b> at a margin of three, and the threshold is worth 89 columns of 22,429.</p>
	 *
	 * <p>So the blunt rule is kept, off, as the thing to fall back to and the baseline the conditional
	 * is priced against -- not as the shape that ships. ekran asked for the conditional and the
	 * conditional is what there is: <i>"note middles should be allowed, but then it should fallback to
	 * a 2 cell bus if the repeater gets padded forward"</i>.</p>
	 *
	 * <p><b>The lesson is worth more than the flag.</b> A measurement is only as good as the builder
	 * it was taken against, and "no threshold is worth anything" was a true statement about a builder
	 * with four unasked questions in it. See {@link #SIMPLE_TAIL_UNDONE_FOR_A_PAD} for the shape that
	 * would close the last of it.</p>
	 */
	static boolean NOTE_BLOCK_MIDDLE_ALWAYS_BUSES = false;


	/**
	 * The columns a stacked bus spends on its tail.
	 *
	 * <p>Priced as a bus even where the tail is going to be simple, which is deliberate and is the
	 * one place in v2 that keeps a column of slack on purpose. Whether the simple shape may be used
	 * is not a fact about the chord -- it is whether a repeater comes out of the tail in line, and
	 * that depends on what the walk does next. Measuring the shorter shape and building the longer
	 * one is a lane outside its wall; measuring the longer and building the shorter is a lane that
	 * stops a column early. Only one of those is a fault.</p>
	 *
	 * <p>It costs nothing at all on a tail of one or two, where the bus is a single cell anyway. Only
	 * a tail of three carries the column, and closing that means telling {@link #landingFrom} what is
	 * ahead of the module rather than only what is in it.</p>
	 */
	private static int stackedBusTailColumns(List<EventNote> tail) {
		return tail.isEmpty() ? 0 : (tail.size() + 1) / 2;
	}

	/**
	 * Whether a repeater will come directly out of this cell, in line, with nothing in between.
	 *
	 * <p>ekran's test, and the one that decides whether a tail may be simple: <i>"can we / do we have
	 * a repeater to continue this line leading directly out of this block? if the answer is no, THEN
	 * we fallback to bus cell"</i>.</p>
	 *
	 * <p>The physics under it is narrower than the note this file used to carry. A block powered only
	 * by dust is soft powered, and a soft-powered block cannot light <b>dust</b> on its far side --
	 * but it drives a <b>repeater</b> against it perfectly well. So a simple tail is fine wherever the
	 * next module's own trigger stands right in front of it, which is the ordinary case, and fails
	 * only where something moves that repeater off the line: corner padding walking the route round a
	 * bend, or a parity pad standing the next module a column over. ekran pasted the corner case and
	 * read it back as sea lanterns.</p>
	 *
	 * <p>Asked of the route, which is what corner padding follows. A parity pad is decided later, by
	 * the next chord's own shape against blocks that are not down yet, so it is not answered here --
	 * that one is still open.</p>
	 */
	private static boolean repeaterComesOutOf(Lane at) {
		return !at.cornerAt(0) && !at.ahead(1).cornerAt(0);
	}

	/**
	 * Chords that could have opened with a head and were refused one for want of room behind.
	 *
	 * <p>A plain counter rather than a plan entry, because this decision is made before there is a
	 * {@link PlacementPlan} to record it on: {@link #eventGroups} settles every chord's shape up
	 * front. Read by the probes, and by nothing that builds.</p>
	 */
	static int HEADLESS_FOR_ROOM_BEHIND = 0;

	private static ChordStyle chooseStyle(Layout layout, List<EventNote> chord, boolean roomBehind) {
		if (fitsSmallModule(chord)) {
			return ChordStyle.SMALL;
		}
		if (!layout.ultra() || hasEffect(chord)) {
			// Every stacked shape leans on its own notes to relay power outward, and an effect is a
			// block that either sounds or conducts, mostly not both. The bus asks nothing of them:
			// it powers each one directly off its own stone, so it is what a chord with an effect
			// in it gets. A song of pure note blocks reaches this line exactly as it always did.
			return ChordStyle.BUS;
		}
		if (ultraSlots(chord, false) != null) {
			return ChordStyle.STACKED_FRONT;
		}
		if (roomBehind && ultraSlots(chord, true) != null) {
			return ChordStyle.STACKED_FULL;
		}
		// A chord too big for the rigid shape can still open with it. The head takes seven and the
		// bus takes the rest, which is seven notes that cost two columns instead of four.
		if (STACKED_BUS_HEADS && roomBehind && stackedBusSplit(chord, true) != null) {
			return ChordStyle.STACKED_BUS;
		}
		// And with the pair behind spoken for, a head of five rather than none at all. It costs a
		// cell against the full head, which still leaves it level with the plain bus on an even
		// chord and a cell shorter on an odd one -- so there is never a reason to prefer the bus.
		if (STACKED_BUS_HEADS && FRONT_ONLY_HEADS && stackedBusSplit(chord, false) != null) {
			return ChordStyle.STACKED_BUS_FRONT;
		}
		if (STACKED_BUS_HEADS && stackedBusSplit(chord, true) != null) {
			HEADLESS_FOR_ROOM_BEHIND++;
		}
		return ChordStyle.BUS;
	}

	/**
	 * What a chord of this shape does to the lane it sits in.
	 *
	 * <p>Only the ultra layout claims a narrow reach. The older modes report every block they
	 * occupy as live whether it is or not, which is what keeps their spacing -- and so every build
	 * anyone has already made with them -- exactly as it was.</p>
	 */
	private static LaneReach laneReachOf(Layout layout, ChordStyle style, List<EventNote> chord) {
		int margin = layout.ultra() ? 0 : 1;
		int chordSize = chord.size();
		if (style.stacked()) {
			// The two blocks a cross hands its signal to sit one either side of the centre line,
			// at the same level as the four low notes. A chord measured as this and then dropped
			// to a bus keeps the wider claim, which is the harmless direction to be wrong in.
			return new LaneReach(1, 1, margin, false);
		}
		if (style == ChordStyle.BUS) {
			return new LaneReach(1, 1, margin, false);
		}
		// The lent middle comes from main: a lone note carrying no module of its own borrows the
		// centre cell, so it reaches one way where it used to reach nowhere. Asked before the rail
		// claim below and kept underneath it, because a chord small enough for a run may still be
		// that lone note, and the run's own claim is the wider of the two.
		LaneReach small = smallChordReach(chordSize, needsLentMiddle(chord), margin);
		// A run of single notes used to be the one lane that reached nowhere at all -- its whole
		// module is the anchor on its own centre line -- so lanes of them packed two apart. A rail
		// column hangs its notes out to the sides, which makes that untrue, and a lane spaced for the
		// old answer meets its neighbour's live centre line one block from that note. Claimed of
		// every chord small enough for a run rather than of the ones that end up in one, because
		// whether a run is under way is a fact about the walk and this is asked of the chord.
		//
		// Both sides, because the floor rail has no centre and a chord of two there fills each of
		// them. It costs nothing over claiming one: a lane reaching one way already spaces at three,
		// which is what {@link #laneSpacing} answers for either.
		return TWO_RAIL_RUNS && layout.ultra() && chordSize <= RAIL_MAX_NOTES
			? new LaneReach(1, 1, margin, small.lowLive())
			: small;
	}

	private static CompactLayout chooseCompactLayout(List<EventGroup> events) {
		int totalLength = totalEventLength(events);
		Set<Integer> candidates = compactTargetCandidates(events);
		CompactLayout best = null;
		for (int targetLength : candidates) {
			CompactLayout candidate = compactLayoutForTarget(events, targetLength);
			if (best == null
					|| candidate.squareSize() < best.squareSize()
					|| candidate.squareSize() == best.squareSize() && candidate.area() < best.area()) {
				best = candidate;
			}
		}
		return best == null ? new CompactLayout(Map.of(), totalLength, 3) : best;
	}

	private static int totalEventLength(List<EventGroup> events) {
		return events.stream().mapToInt(EventGroup::length).sum();
	}

	private static Set<Integer> compactTargetCandidates(List<EventGroup> events) {
		int totalLength = totalEventLength(events);
		int largestEvent = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		Set<Integer> candidates = new HashSet<>();
		candidates.add(totalLength);
		candidates.add(largestEvent);
		for (int rows = 1; rows <= events.size(); rows++) {
			candidates.add(Math.max(largestEvent, (totalLength + rows - 1) / rows));
		}
		return candidates;
	}

	private static CompactLayout compactLayoutForTarget(List<EventGroup> events, int targetLength) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		int rowLength = 0;
		int cursor = 0;
		int direction = 1;
		int minimum = 0;
		int maximum = 0;
		for (int index = 0; index < events.size(); index++) {
			int eventLength = events.get(index).length();
			// The widest spacing, not the one this pair will end up with. Where a lane ends has to
			// be settled before the lanes can be measured, because the measurement reads the lanes.
			boolean safeTurn = index > 0
				&& events.get(index - 1).maxSafeTurnDistance() >= MAX_LANE_SPACING;
			if (rowLength > 0 && rowLength + eventLength > targetLength && safeTurn) {
				starts.add(index);
				int outerTurn = cursor + direction;
				minimum = Math.min(minimum, outerTurn);
				maximum = Math.max(maximum, outerTurn);
				direction = -direction;
				rowLength = 0;
			}
			int end = cursor + direction * eventLength;
			minimum = Math.min(minimum, Math.min(cursor, end));
			maximum = Math.max(maximum, Math.max(cursor, end));
			cursor = end;
			rowLength += eventLength;
		}
		Map<Integer, Integer> spacings = laneSpacings(events, starts);
		int width = Math.max(1, maximum - minimum + 1);
		int depth = spacings.values().stream().mapToInt(Integer::intValue).sum()
			+ laneReach(events, 0, starts.size() > 1 ? starts.get(1) : events.size()).width();
		return new CompactLayout(spacings, width, depth);
	}

	/**
	 * Builds an event and the turn out of its lane as one piece.
	 *
	 * <p>A corner is a run of stone with dust along it, which is exactly what a chord's bus is, so
	 * the event arriving at the end of a lane can hang its notes off the corner rather than be laid
	 * out first and leave the corner bare. No later event can use it: dust carries no delay, so
	 * everything touching a corner sounds at the instant the signal crosses it, and that instant
	 * belongs to this event and no other.</p>
	 *
	 * @return the cursor for the next lane, which travels back the way this one came
	 */
	private static BlockPos addTurnEventModule(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, int laneDistance, int triggerDelay, List<EventNote> chord) {
		int time = chord.get(0).time();
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		// One level up from the lane floor, like a bus and unlike a plain turn: notes have to sit
		// beside a block that is powered, and dust does not pass its signal sideways into a note
		// block. Hung off the dust itself they would stay silent.
		BlockPos corner = cursor.relative(travel).above();
		for (int offset = 0; offset <= laneDistance; offset++) {
			BlockPos run = corner.relative(laneStep, offset);
			placements.powered(run, "minecraft:stone", time);
			set(placements, run.above(), "minecraft:redstone_wire");
		}
		List<BlockPos> slots = cornerSlots(placements, cursor, travel, laneStep, laneDistance);
		for (int index = 0; index < chord.size(); index++) {
			placeNote(placements, slots.get(index), chord.get(index));
		}
		return cursor.relative(laneStep, laneDistance);
	}

	/**
	 * The places along a corner a note can still be hung, in the order they get used.
	 *
	 * <p>Filtered rather than simply counted off, because a corner's notes take the block under
	 * them for their instrument, and that block is at the level the stacked module hangs its low
	 * notes at. A lane that ends on one of those has already spoken for a cell or two of its own
	 * corner. Asking first is what lets the walk lay the event out in the lane instead when what is
	 * left will not hold it.</p>
	 */
	private static List<BlockPos> cornerSlots(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction laneStep, int laneDistance) {
		BlockPos corner = cursor.relative(travel).above();
		List<BlockPos> slots = new ArrayList<>();
		for (int offset = 0; offset <= laneDistance; offset++) {
			BlockPos run = corner.relative(laneStep, offset);
			if (placements.freeForNote(run.relative(travel))) {
				slots.add(run.relative(travel));
			}
			// The near side of the first cell is the repeater driving it, and the near side of the
			// last is where the next lane's repeater has to stand.
			if (offset > 0 && offset < laneDistance
					&& placements.freeForNote(run.relative(travel.getOpposite()))) {
				slots.add(run.relative(travel.getOpposite()));
			}
		}
		return slots;
	}

	/**
	 * Carries the signal across to where the next lane starts, and turns it around.
	 *
	 * <p>The run is live, at the tick of the lane it is <em>leaving</em>, and it crosses the whole
	 * gap at the level the lanes keep their notes at. That looks like it ought to sound the incoming
	 * lane's notes a verse early, and it does not: the only cell of the incoming lane it comes
	 * alongside is the low slot behind that lane's first repeater, and a module is already barred
	 * from reaching backwards into the block a turn was built through. So the run stays on stone,
	 * and two lanes can still sit with their notes touching.</p>
	 */
	private static BlockPos addCompactTurn(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, int laneDistance, int time) {
		if (laneDistance < 1 || laneDistance > 13) {
			throw new IllegalArgumentException("Compact turn distance " + laneDistance
				+ " exceeds the safe redstone range");
		}
		placements.turnedAt(cursor);
		// The turn as a route rather than as a shape: one cell along, a corner, then the sideways
		// run. Laid off the same Lane every module uses, so that when modules start being placed
		// along here rather than plain wire, there is nothing left to teach them.
		Lane route = turnRoute(cursor, travel, laneStep, laneDistance);
		BlockPos outer = cursor.relative(travel);
		for (int cell = 0; cell <= laneDistance + 1; cell++) {
			layTurnFloor(placements, route.ahead(cell).pos(), time);
		}
		return outer.relative(laneStep, laneDistance).relative(travel.getOpposite());
	}

	/**
	 * The route a flat turn's wire takes: one cell along travel, a corner, then the sideways run.
	 *
	 * <p>The note side is handed in as the way the sideways run goes, which is the direction a chord
	 * riding the turn will find itself hanging off once the corner has rotated it -- so the corner
	 * rotates the notes onto the travel axis, along the lane, ground this build already owns.</p>
	 *
	 * <p>Two corners and not one. The second is the one that used to be left implicit in the cursor
	 * a turn handed back, and leaving it implicit is what made a turn a thing rather than a stretch
	 * of route: with both of them stated, the route carries on past the turn into the next lane by
	 * itself, and a chord that overruns the sideways run simply spills round the corner and keeps
	 * going. Nothing has to know where the turn ends.</p>
	 */
	private static Lane turnRoute(BlockPos cursor, Direction travel, Direction laneStep,
			int laneDistance) {
		boolean clockwise = travel.getClockWise() == laneStep;
		return Lane.straight(cursor, travel, laneStep)
			.bending(List.of(new Lane.Bend(1, clockwise),
				new Lane.Bend(1 + laneDistance, clockwise)))
			.crowding();
	}

	private static void layTurnFloor(PlacementPlan placements, BlockPos position, int time) {
		placements.powered(position, "minecraft:stone", time);
		set(placements, position.above(), "minecraft:redstone_wire");
	}

	private static SpatialDelayTrigger addSpatialDelayBeforeEvent(PlacementPlan placements,
			Lane lane, int delay) {
		return addSpatialDelayBeforeEvent(placements, lane, delay, false);
	}

	/**
	 * @param keepCorner whether to hand back a route still standing on its corner. Normally the
	 *     delay walks off one, because what it hands back is where a repeater goes and a repeater may
	 *     not stand on a corner. The two-swap turn wants the corner intact: it has a use for that
	 *     cell -- a note -- and walking off it first is precisely the cell of dust it exists to save.
	 *     Callers that pass true must deal with the corner themselves.
	 */
	private static SpatialDelayTrigger addSpatialDelayBeforeEvent(PlacementPlan placements,
			Lane lane, int delay, boolean keepCorner) {
		int remaining = delay;
		placements.placing("delayBeforeChord");
		while (remaining > 4) {
			lane = pastAnyCorner(placements, lane);
			set(placements, lane.pos(), "minecraft:stone");
			set(placements, lane.pos().above(),
				"minecraft:repeater[facing=" + repeaterFacing(lane.travel()) + ",delay=4]");
			lane = lane.ahead(1);
			remaining -= 4;
		}
		return new SpatialDelayTrigger(keepCorner ? lane : pastAnyCorner(placements, lane),
			Math.max(1, remaining));
	}

	/**
	 * The route moved past a corner, laying the wire that carries the signal round it.
	 *
	 * <p>A repeater conveys power one way only: it reads the cell behind it and drives the cell in
	 * front, both along the way it faces. A corner is the one cell where the wire arrives along one
	 * axis and leaves along another, so a repeater standing there can only ever be fed from a
	 * direction nothing is coming from, or drive a direction nothing is going. It is not a thing to
	 * get right at corners -- it is a thing that must never happen at one.</p>
	 *
	 * <p>And it never has to. Dust turns a corner as readily as the wire does, so the corner takes
	 * dust and the repeater goes one cell further along. Only its position moves; its delay, and so
	 * the tick the music lands on, is untouched.</p>
	 *
	 * <p>Asked here, where every repeater in a walked lane is ultimately placed, rather than at each
	 * call site. Putting it only in front of a chord's own repeater left the ones the delay lays --
	 * one for every four ticks of silence -- free to land on a bend, which is most of the repeaters
	 * in a slow passage.</p>
	 */
	private static Lane pastAnyCorner(PlacementPlan placements, Lane lane) {
		// The label is put back afterwards, and that is not tidiness. Walking a corner lays a cell of
		// dust, which named itself "pad" and left every block the caller placed next wearing that name
		// -- so a repeater column laid after a corner reported itself as a pad in the collision
		// marker. Six explanations of one bend collision were argued in front of a marker that was
		// naming the wrong shape.
		String was = placements.placing();
		while (lane.cornerAt(0)) {
			placements.padded("corner");
			lane = emitDust(placements, lane, 1, false, "corner");
		}
		placements.placing(was);
		return lane;
	}

	/**
	 * Whether a run of small chords is laid as two interleaved rails rather than as a module each.
	 *
	 * <p>A chord of three or fewer costs two columns however small it is: a repeater, then the block
	 * that repeater drives. Ekran's shape halves that by running two chains at once -- one on the
	 * path, one on the floor beneath it -- each carrying twice the gap and offset from the other by
	 * one gap. Every column then holds one note and the repeater that drives the next, and the notes
	 * alternate between the two levels:</p>
	 *
	 * <pre>
	 *   path    r2  w   sA  r4  C   r4  E   r4  G
	 *   floor   s   s   r2  sB  r4  sD  r4  sF
	 *   below           s       s       s
	 * </pre>
	 *
	 * <p>{@code sX} is the cell the wire runs through with a note hung off the side of it. Each column
	 * offers its slots in one order -- the centre, then the near side, then the far one -- and what
	 * decides how many it needs is which of them the chord can use:</p>
	 *
	 * <ul>
	 *   <li>The <b>centre</b> is the wire itself, so a note there has no instrument block of its own
	 *       and plays harp whatever was meant. It is offered to a harp note of the chord and to
	 *       nothing else.</li>
	 *   <li>The <b>floor rail has no centre at all</b>: the path rail's repeater stands directly over
	 *       it, and a note block plays only with air above. Both its notes go to the sides, which is
	 *       what caps a run at chords of two.</li>
	 *   <li>The <b>head's centre</b> can never hold a note, and that is the one thing here that had to
	 *       be measured rather than reasoned about: a note block relays to the repeater in front of it
	 *       when a <em>repeater</em> drives it and does not when <em>dust</em> points into it, while
	 *       stone relays either way.</li>
	 * </ul>
	 *
	 * <p>The sides hang off the centre with a block each of their own, so they keep any instrument
	 * there is.</p>
	 *
	 * <p>The dust is what makes it start, and it is why the run opens with its repeater a column back
	 * rather than facing the first note: dust powers the stone it sits on, and that stone is what the
	 * floor rail's first repeater reads. Every repeater is then {@code t(k+1) - t(k-1)}, which is why
	 * the run is only available where two consecutive gaps come to four or less.</p>
	 */
	static boolean TWO_RAIL_RUNS = true;

	/**
	 * Whether v2 runs on rails too.
	 *
	 * <p>Separate from {@link #TWO_RAIL_RUNS} because the two pasters can be wrong about this
	 * independently, and because the lane spacing the runs need is claimed by
	 * {@link #laneReachOf} for every ultra build already -- so v2 has been paying for the runs
	 * since main was merged without being able to use them. This is what stops that being a
	 * one-way trade.</p>
	 *
	 * <p>The port is walkWall's block moved across rather than rewritten, and it is not finished:
	 * {@link #shapeFor} still decides a shape for every chord before the walk asks whether a rail
	 * column will take it instead, so a run leaves shape decisions in the census that nothing built.
	 * That is a miscount rather than a misbuild -- a run lays its own notes and never reads the
	 * shape -- but it is the one place v2's "the decision is the decision" does not hold, and
	 * closing it means asking the rail question first.</p>
	 */
	static boolean V2_RUNS_ON_RAILS = true;

	/**
	 * Columns laid as part of a run, counted by whichever walk laid them.
	 *
	 * <p>A plain counter because the question it answers is "did this fire at all", and that question
	 * has a failure mode worth guarding against: a feature that never runs and a feature that runs and
	 * changes nothing produce the same numbers everywhere else. Guardian came back byte-identical with
	 * the runs on and off, which is either fact, and no other measurement here can tell them apart.
	 *
	 * <p>Reset by the caller, like {@link #HEADLESS_FOR_ROOM_BEHIND}. Read by the probes and by
	 * nothing that builds.</p>
	 */
	static int RAIL_COLUMNS = 0;

	/** A path column's cells: a centre and a side each way. Only a harp note can take the centre. */
	private static final int RAIL_PATH_SLOTS = 3;

	/**
	 * A floor column's cells: the two sides and no centre.
	 *
	 * <p>The path rail's repeater stands directly over a floor column's centre, and a note block
	 * plays only with air above it. So the floor rail is the narrower of the two, and a chord of
	 * three landing on it is what {@link #addRailBlank} exists for.</p>
	 */
	private static final int RAIL_FLOOR_SLOTS = 2;

	/** The largest chord any column of a run can hold, which is a path column with a harp note. */
	static int RAIL_MAX_NOTES = RAIL_PATH_SLOTS;

	/**
	 * Whether a column of this rail could hold this chord.
	 *
	 * @param path whether the column is a path column, which has a centre where a floor column has
	 *     none -- and the centre is the cell the wire runs through, so only a harp note can take it
	 */
	private static boolean railHolds(EventGroup event, boolean path) {
		if (!path && event.notes().stream()
				.anyMatch(note -> FALLING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock()))) {
			// A floor column is the one shape in this build that hangs a note at the lane's own floor
			// level, so its instrument block sits a level below that and sand wants holding up from
			// the level below *that* -- which is the cell the floor underneath needs left empty over
			// its own path notes. Floors are four apart and a rail's side column already uses all
			// four, so there is nowhere for that stone to go. The path rail sits a level higher and
			// has the room, so a chord carrying sand goes there instead.
			return false;
		}
		int notes = event.notes().size();
		return notes <= RAIL_FLOOR_SLOTS
			|| path && notes <= RAIL_PATH_SLOTS
				&& event.notes().stream().anyMatch(SongBuilder::isHarpNote);
	}

	/** The repeater and the dust in front of it, laid before the first note of a run. */
	private static final int RAIL_HEAD_COLUMNS = 2;

	/**
	 * Whether this event could stand in a rail run at all, leaving aside where its neighbours are.
	 *
	 * <p>Asked of the path rail, which is the wider of the two. A chord of three that lands on the
	 * floor rail is not turned away for it -- {@link #addRailBlank} lays that column empty and the
	 * chord takes the path column after it.</p>
	 */
	private static boolean railFits(EventGroup event) {
		return railHolds(event, RAIL_BLANKS);
	}

	/** Off, a run refuses any chord the floor rail cannot hold rather than blanking a column for it. */
	static boolean RAIL_BLANKS = true;

	/**
	 * Off, a blank is only laid for a chord the floor rail cannot hold, and never for a pair of gaps
	 * too long for one repeater. Here to tell the two triggers apart when one of them misbehaves.
	 */
	static boolean RAIL_BLANKS_FOR_DELAY = true;

	/**
	 * How many blanks a run may lay one after another before it is not worth carrying on.
	 *
	 * <p>A run of {@code k} chords holding {@code b} blanks costs {@code 2 + k + b} columns where the
	 * plain lane costs {@code 2k}, so a run is only ahead while {@code b < k - 2}: a floor rail that
	 * takes a blank every time is a chord and a column each, which is what the plain lane charges,
	 * plus the head. ekran read the shape off lady brown -- "several bottom rails that are carried on
	 * for many blocks but NEVER actually get a chord on the bottom before ending. at that point its
	 * just a waste of resources not an optimization of space".</p>
	 *
	 * <p>A limit on how many come in a row rather than on how many there are, because that is the
	 * question a run can answer where it stands. Whether a chord four events away will fit the floor
	 * rail depends on where the wall is by then, and a run that guessed would be the third place the
	 * same budget is kept. And it is only asked while the floor rail has carried nothing at all,
	 * which is the shape ekran read.</p>
	 *
	 * <p><b>Off, because the arithmetic above is wrong about what a blank is against.</b> A blank is
	 * not against the plain lane, it is against <em>ending the run</em> -- and a run that ends pays a
	 * fresh head of two columns to start again, on top of the two columns a chord costs while there
	 * is no run. Measured over every song at three widths and three floor counts
	 * ({@code RailBlankLimitTest}), total depth: plain 1843, and with runs on 1557 at one blank ever,
	 * 1498 at one in a row, 1484 at two, 1476 at three, and <b>1473 with no limit at all</b>. Every
	 * limit is worse than none, and the tighter the limit the worse it gets. So the floor rails ekran
	 * saw carrying nothing are not waste -- they are the cheapest way to keep the path rail's one
	 * column per chord, which is the whole win. Left here as a flag rather than deleted, because it
	 * is ekran's call whether a build that reads wasteful in game is worth three lanes.</p>
	 */
	static int RAIL_BLANKS_IN_A_ROW = Integer.MAX_VALUE;

	/** A repeater's delay, or nought where it is not one a repeater can hold. */
	private static int railDelay(int from, int to) {
		return to - from >= 1 && to - from <= 4 ? to - from : 0;
	}

	/**
	 * The delay of the repeater this column holds for the next note, or nought if the run ends here.
	 *
	 * @param behindTime the tick the block behind that repeater goes live, which is whenever the
	 *     other rail's last anchor did -- normally the note two events back, at the head the stone
	 *     under the opening dust, and after a blank the blank itself
	 */
	private static int railNextDelay(List<EventGroup> events, int index, int behindTime) {
		return index + 1 >= events.size() || !railFits(events.get(index + 1)) ? 0
			: railDelay(behindTime, events.get(index + 1).time());
	}

	/**
	 * Whether a run may open here: three events that fit their natural rails, and both of the
	 * repeaters the first two columns will have to hold.
	 *
	 * <p>Asked without blanks. A run can swap its rails over mid-way to get a chord of three onto the
	 * path rail, but the head is the one column that has no centre at all -- its stone has to relay
	 * the dust -- so opening one is the place to be plain about what fits.</p>
	 */
	private static boolean railMayStart(List<EventGroup> events, int index, int floorSeed,
			Map<Integer, Integer> booked) {
		if (!TWO_RAIL_RUNS || index + 2 >= events.size()) {
			return false;
		}
		EventGroup head = events.get(index);
		// The head column is the one path column with no centre to offer -- its stone has to relay the
		// dust -- so it holds what a floor column holds. Which also means a run can never open on a
		// chord of three: three notes need a centre, and the head has none.
		if (head.notes().size() > RAIL_FLOOR_SLOTS) {
			return false;
		}
		// And then the same question a run in progress asks, so that opening one and carrying one on
		// cannot answer differently. Both rails go live with this chord: the head's own stone, and the
		// stone under the dust in front of it.
		// Timed from wherever the floor rail actually starts: the chord itself where a head is built,
		// and the stacked chord behind it where one is inherited -- which is earlier, so the first
		// floor repeater has less of its four ticks left to play with.
		return railPairAfter(events, index, head.time(),
			floorSeed == NO_BLANK ? head.time() : floorSeed, null, null, booked) != null;
	}

	/** Columns between this cell and the wall the lane is running at. */
	private static int railRoom(Lane lane, int wall) {
		return (wall - lane.pos().getX()) * lane.travel().getStepX();
	}

	/** Columns of repeater the wait in front of a run costs before its head can be laid. */
	private static int railPadColumns(int wait) {
		return Math.max(0, (wait - 1) / 4);
	}

	/**
	 * Whether a run may open at this cell of this lane.
	 *
	 * <p>The room asked for is the room the run's <em>first pair</em> needs, because a run that stops
	 * at the column it opened on is a dead wire. Every other column of a run is driven by a repeater,
	 * and a block a repeater drives hands a full fifteen to whatever the lane lays next; the opening
	 * column alone is driven by the head's dust, and a block that dust powers cannot light dust of
	 * its own. So the lane carries on into its padding and the signal stops there, with the rest of
	 * the song behind it. ekran read one off the world as a stone with wire running into it and out
	 * of it, and prescribed a repeater beside the block -- which is the physics exactly. The column
	 * is simply not worth building: a run of one costs three columns where the plain module costs
	 * two, so the answer is to want the room up front rather than to buy a way out of it.</p>
	 *
	 * <p>Which makes this the same sum the column-by-column test makes, moved to before the head is
	 * laid: the pair and the turn's reserve, plus the two columns of head and whatever the wait in
	 * front of it spends, since the opening column stands that much further down the lane. The two
	 * used to disagree by the reserve and by the padding, and every dead wire in the two real songs
	 * measured was that disagreement.</p>
	 */
	private static boolean railOpens(List<EventGroup> events, int index, Lane lane, int wall,
			Layout layout, boolean turning, int reserve, int wait, int floorSeed,
			Map<Integer, Integer> booked) {
		return TWO_RAIL_RUNS && layout.ultra() && !turning && !lane.bending()
			&& railMayStart(events, index, floorSeed, booked)
			&& railRoom(lane, wall)
				>= railHeadColumns(floorSeed, wait) + railPadColumns(wait) + 2 + reserve;
	}

	/**
	 * What opening a run costs in columns: both of the head's, or only the trigger off a stack.
	 *
	 * <p>The cheap head is only available where the wait in front of the run lays nothing. A stacked
	 * chord is a head because its cross of dust sits in the cell behind the trigger; a column of the
	 * wait's own wire in between puts it out of reach, and the walk builds a whole head instead --
	 * which is what {@code offStack} asks when it checks the padding left the lane where it stood.
	 * Asking it there and not here is how a run came to open with one column of room less than it
	 * had been promised: it laid its head, found no room for the pair, and ended on the column its
	 * head's dust drives. That column lights no dust of its own, so the song stopped there. One run
	 * of eight ticks of wait in all-of-the-lights left 113 notes silent behind it.</p>
	 */
	private static int railHeadColumns(int floorSeed, int wait) {
		return floorSeed == NO_BLANK || railPadColumns(wait) > 0
			? RAIL_HEAD_COLUMNS : RAIL_HEAD_COLUMNS - 1;
	}

	/**
	 * A floor column carrying no notes, so that the chord which wanted it goes on the path rail.
	 *
	 * <p>A chord of three cannot stand on the floor rail, which has no centre. Rather than end the
	 * run for it -- a run pays two columns of head to start again, and an odd three in the middle of
	 * a long sequence is not worth that -- the floor column is laid empty and the chord takes the
	 * path column after it. The two rails come out of it swapped over and carry on (ekran).</p>
	 *
	 * <p>A blank is exactly an ordinary floor column with its notes left off, and that is what makes
	 * it cheap: the floor repeater driving it keeps the delay it would have had anyway, two gaps, so
	 * the blank goes live on the very tick the chord would have. The one thing that changes is the
	 * path repeater standing here -- one gap instead of two -- so the path rail's next anchor comes
	 * round in time to take the chord.</p>
	 *
	 * @param pathDelay one gap: from the path rail's last anchor to the chord this makes room for
	 */
	private static Lane addRailBlank(PlacementPlan placements, Lane at, int time, int pathDelay) {
		placements.placing("rail:BLANK");
		placements.padded("railBlankForOddThree");
		placements.powered(at.pos(), "minecraft:stone", time);
		set(placements, at.pos().above(), "minecraft:repeater[facing="
			+ repeaterFacing(at.travel()) + ",delay=" + pathDelay + "]");
		return at.ahead(1);
	}

	/** Whether the plan has booked pad at this event, which the walk lays before any rail column. */
	private static boolean railPadBooked(Map<Integer, Integer> booked, int index) {
		return booked != null && booked.getOrDefault(index, 0) > 0;
	}

	/** What the floor column after a path column carries: the next chord, or a blank at this tick. */
	private record RailPair(boolean blank, int floorTime) {
	}

	/** No blank is owed. A real one is a tick, and ticks in a build are never this. */
	private static final int NO_BLANK = Integer.MIN_VALUE;

	/**
	 * What comes after this path column, or {@code null} where the run has to end here.
	 *
	 * <p>A run may only end on a path column -- the floor rail's notes sit a level below the path and
	 * nothing brings the wire back up -- so carrying on is a commitment to two more columns, and both
	 * of the ways those two can go are settled here rather than discovered halfway.</p>
	 *
	 * <p>The blank is the interesting one, and it answers two questions with one column. A chord of
	 * three cannot stand on the floor rail, which has no centre; and a pair of gaps coming to more
	 * than four cannot be spanned by one repeater, which is what a rail asks of every repeater it
	 * lays. Both are answered by laying the floor column empty: the chord takes the path column after
	 * it, where the path rail has only the single gap to span, and the floor rail hops to a tick of
	 * its own choosing and covers the rest from there. ekran, from the opening of song of storms,
	 * where the two ticks to the second chord and the four to the third come to six.</p>
	 *
	 * @param pathLive when this column's own note goes live, which the path rail is timed from
	 * @param floorLive when the floor rail's last anchor went live
	 */
	private static RailPair railPairAfter(List<EventGroup> events, int index, int pathLive,
			int floorLive, PlacementPlan placements, Lane floor, Map<Integer, Integer> booked) {
		if (index + 1 >= events.size()) {
			return null;
		}
		// A booked pad inside the pair is what breaks the chain, so the pair is never committed to
		// across one. The plan books its pad against events, and the walk lays it at the top of the
		// event, before the rail branch is reached -- so a column of it lands between the repeater a
		// rail column has already laid and the note that repeater was laid to drive. What comes out
		// is repeater, dust, block: the block is soft powered, it sounds the notes hung on it and it
		// lights nothing after it, and the rest of the song is behind that. Refused here, where the
		// pair is committed, rather than noticed two events later where the column is already built
		// -- the run ends on this path column, the lane pads as the plan asked, and the next run
		// opens on the far side.
		if (railPadBooked(booked, index + 1) || railPadBooked(booked, index + 2)) {
			return null;
		}
		EventGroup next = events.get(index + 1);
		// The plain pair: the next chord on the floor column, and the one after it on the path column
		// where the run is allowed to stop. A chord the floor column cannot hold without sounding it
		// early falls through to the blank below, which is the column that moves it up to the path
		// rail -- ekran's second option, and the one already built.
		if (railHolds(next, false) && railFloorTakes(placements, floor, next)
				&& railDelay(floorLive, next.time()) > 0
				&& index + 2 < events.size() && railHolds(events.get(index + 2), true)
				&& railDelay(pathLive, events.get(index + 2).time()) > 0) {
			return new RailPair(false, next.time());
		}
		if (!railHolds(next, true) || railDelay(pathLive, next.time()) == 0
				|| !RAIL_BLANKS_FOR_DELAY && railHolds(next, false)) {
			return null;
		}
		// As late as the floor rail can reach, so that whatever follows has the most room -- but
		// never past the anchor after it, which has to be within a repeater of this one.
		int latest = floorLive + 4;
		int blankTime = index + 2 < events.size()
			? Math.min(latest, events.get(index + 2).time() - 1) : latest;
		return railDelay(floorLive, blankTime) > 0 ? new RailPair(true, blankTime) : null;
	}

	/** Off, a run always pays for its own head even where a stacked chord has already built one. */
	static boolean RAIL_FROM_STACK = true;

	/**
	 * The tick a run opening at this cell would find its floor rail already live at, or
	 * {@link #NO_BLANK} where it would have to build a head of its own.
	 *
	 * <p>ekran's: a stacked module already <em>is</em> a head. It lays a cross of dust at the lane's
	 * floor level standing on a stone, one column back from where it hands over -- which is the same
	 * pair of blocks {@link #addRailHead} spends a column building, at the same level, live at the
	 * module's own tick. The cross points along the lane as well as across it, so the cell the next
	 * module's trigger repeater stands on is already powered. A repeater pulls that out into the floor
	 * rail and the module's centre carries the path rail, so both rails are running from the column
	 * after it, for the price of the trigger column every module pays anyway.</p>
	 *
	 * <p>Read off the blocks rather than off the style, because what matters is that the cross is
	 * actually behind this cell -- a nudge, a corner or a column of padding puts it somewhere else,
	 * and the style would still say stacked.</p>
	 */
	private static int railStackSeed(PlacementPlan placements, Lane lane, ChordStyle lastStyle,
			int currentTime, boolean turning) {
		// Standing on a corner counts, and bending() does not say so: it asks whether a bend is still
		// *ahead*, and a lane on the last corner of a turn has none left. The same gap Lane.crowding
		// exists for.
		if (!RAIL_FROM_STACK || turning || lane.bending() || lane.cornerAt(0)) {
			return NO_BLANK;
		}
		BlockPos behind = lane.pos().relative(lane.travel().getOpposite());
		if (lastStyle == ChordStyle.STACKED_FRONT || lastStyle == ChordStyle.STACKED_FULL) {
			// A rigid stacked module leaves its cross on the lane's own level, and the cross powers
			// the stone in front of it.
			return placements.describeBlock(behind).startsWith("minecraft:redstone_wire")
				? currentTime : NO_BLANK;
		}
		// And a stacked bus leaves its handover there, which is the same thing one level apart: stone
		// on the lane with dust over it. The dust powers the stone it sits on, and that stone is what
		// a repeater facing out of it reads -- so the floor rail starts with no column spent on it,
		// exactly as it does off a stacked chord. ekran built both by hand and the pair came out
		// almost twice as compact as what the walk lays today.
		//
		// Asked of the cell the shape said it handed over in, and not of what is standing there. Those
		// two blocks -- stone with dust over it -- are also a corner and also a cell of pad, so the
		// look-alike test said yes to a lane whose own wire had merely come round a bend. It seeded a
		// floor rail off a corner on all-my-fellas 40x5: a corner's dust powers the stone beneath
		// itself and nothing in front, so the repeater facing out of it read nothing and the run's two
		// notes never sounded. Three blocks from the nearest marked corner, so asking about corners
		// would not have caught it either.
		//
		// Naming the cell also settles what the style could not. A stacked bus with a tail leaves that
		// tail between its handover and here, so the handover is not the cell behind and this reads
		// false -- which is what the old comment claimed the block test was doing by accident.
		if (RAIL_FROM_HANDOVER && lastStyle.busHeaded()) {
			return behind.equals(placements.handover()) ? currentTime : NO_BLANK;
		}
		return NO_BLANK;
	}

	/**
	 * The trigger column of a run that opens off a stacked chord, and nothing else.
	 *
	 * <p>The same stone and repeater every module opens with. What makes it a head is what is already
	 * behind it: the stacked module's cross powers this stone, so the floor rail starts here without
	 * a column being spent on it, and the repeater carries the path rail on to the first chord.</p>
	 */
	private static Lane addRailFromStack(PlacementPlan placements, Lane lane, int delay, int seed) {
		placements.placing("rail:FROM-STACK delay" + delay);
		placements.padded("railFromStack");
		lane = pastAnyCorner(placements, lane);
		// Live at the stacked chord's tick, because the cross behind it is. Said out loud so that
		// anything hung beside it is checked against that tick rather than against the run's.
		placements.powered(lane.pos(), "minecraft:stone", seed);
		set(placements, lane.pos().above(),
			"minecraft:repeater[facing=" + repeaterFacing(lane.travel()) + ",delay=" + delay + "]");
		return lane.ahead(1);
	}

	/**
	 * A run opening straight off a stacked bus, which costs no column at all.
	 *
	 * <p>The bus has already laid the first path column: its tail. That tail sounds at the head's own
	 * tick -- the handover adds no delay, and it is the same chord -- and it left the cell below it
	 * free. So the only thing owed is the floor rail's first repeater, and it goes in that free cell,
	 * facing out of the handover's stone. The dust above that stone powers it and the head's cross
	 * powers it from the side, which is exactly the block a run off a plain stacked chord reads.</p>
	 *
	 * <p>ekran, having built both by hand: <i>"it quite literally is the EXACT same as when we get an
	 * instant start from a normal stacked chord. only difference is the first top rail slot is taken
	 * by the tail of the stacked bus."</i> Eight columns against the eleven the walk laid before it.</p>
	 *
	 * <p>The run therefore resumes on the <b>floor</b> rail, not the path one, and both rails are live
	 * from the module's own tick.</p>
	 */
	private static void addRailFromHandover(PlacementPlan placements, BlockPos tail, Direction travel,
			int delay) {
		placements.placing("rail:FROM-HANDOVER delay" + delay);
		placements.padded("railFromHandover");
		set(placements, tail, "minecraft:repeater[facing=" + repeaterFacing(travel)
			+ ",delay=" + delay + "]");
		// And the block it stands on, which every other floor-rail repeater gets from
		// {@link #addRailNote} and this one was not getting from anywhere. A repeater on air is not a
		// repeater. ekran's fixed slice has stone directly under it; the built one had neither.
		set(placements, tail.below(), "minecraft:stone");
	}

	/** The two columns a run opens with: its own repeater, then the dust that starts the floor rail. */
	private static Lane addRailHead(PlacementPlan placements, Lane lane, int delay, int time) {
		placements.placing("rail:HEAD delay" + delay);
		placements.padded("railHead");
		lane = pastAnyCorner(placements, lane);
		set(placements, lane.pos(), "minecraft:stone");
		set(placements, lane.pos().above(),
			"minecraft:repeater[facing=" + repeaterFacing(lane.travel()) + ",delay=" + delay + "]");
		lane = lane.ahead(1);
		// The one cell in this build that starts a second chain. The dust powers the stone under it,
		// and the floor rail's first repeater reads that stone; the path rail carries straight on
		// through the dust into the first note.
		placements.powered(lane.pos(), "minecraft:stone", time);
		set(placements, lane.pos().above(), "minecraft:redstone_wire");
		return lane.ahead(1);
	}

	/**
	 * One column of a run: this event's note on the rail it falls on and, unless the run ends here,
	 * the repeater that drives the next note on the other rail.
	 *
	 * <p>Slots go in one order, ekran's: the centre, then the near side, then the far one. The centre
	 * is the cell the wire runs through, so a note there has no instrument block of its own and plays
	 * harp whatever was meant -- it is offered to a harp note of the chord and to nothing else. The
	 * sides hang off it with a block each of their own and keep any instrument there is.</p>
	 *
	 * @param phase 0 for the path rail, 1 for the floor rail
	 * @param nextDelay the delay of that repeater, or nought to end the run at this column
	 * @param fromDust whether what drives this column is the head's dust rather than a repeater, in
	 *     which case the centre cannot be a note at all: dust hands a stone on to the repeater in
	 *     front of it and does not hand on a note block. Measured; see RailLinkProbeTest.
	 */
	private static Lane addRailNote(PlacementPlan placements, Lane at, int phase,
			List<EventNote> chord, int time, int nextDelay, boolean fromDust, int handUp) {
		String facing = repeaterFacing(at.travel());
		List<EventNote> hanging = new ArrayList<>(chord);
		if (phase == 0) {
			BlockPos centre = at.pos().above();
			// The one shape of run that cannot hand its signal on. {@link #railOpens} is what keeps
			// this from happening and it is arithmetic against a wall, so it is worth saying out loud
			// rather than trusting: dust powers this centre, and a block dust powers lights no dust of
			// its own, so the padding the lane lays next is dead and the song stops there.
			if (fromDust && nextDelay == 0) {
				placements.trouble("a run at tick " + time
					+ " ended on the column its head's dust drives, which cannot light the wire after it");
			}
			// The centre goes to a harp note wherever the chord has one, the column a run opens on
			// included. That column was excluded for a long time on the grounds that the head's dust
			// hands a stone on to the repeater in front of it and does not hand on a note block --
			// which was never true. It came from one clause in the reader that refused to carry a
			// dust's power into a note block, and the game says the two blocks are the same:
			// isRedstoneConductor, isSignalSource, canOcclude and isSolidRender all read alike
			// (NoteBlockConductsTest). ekran said so plainly -- "a note block can be powered just like
			// a stone, there's no difference. a noteblock just cant have something on top, but that
			// doesn't happen here" -- and the air above every centre is laid for exactly that reason.
			EventNote harp = takeHarpNote(hanging);
			placements.placing("rail:PATH notes" + chord.size()
				+ (harp == null ? " sidesOnly" : " centred") + (fromDust ? " head" : ""));
			placements.padded("railPath" + (harp == null
				? chord.stream().anyMatch(SongBuilder::isHarpNote) ? "SidesWithAHarp" : "SidesOnly"
				: "Centred"));
			if (harp == null) {
				placements.powered(centre, "minecraft:stone", time);
			} else {
				placeNoteBlock(placements, centre, harp);
				placements.powered(centre, time);
			}
			// The same way round as the floor rail. Both rails filling towards the lane behind leaves
			// the whole column on the other side empty, so a run occupies its centre and one flank
			// rather than straddling both -- and every lane spaced three apart then has a clear
			// column between it and the next. ekran, who found the two rails disagreeing.
			hangRailNotes(placements, centre, at.noteSide().getOpposite(), hanging, time, false);
			if (nextDelay > 0) {
				set(placements, at.pos(),
					"minecraft:repeater[facing=" + facing + ",delay=" + nextDelay + "]");
				set(placements, at.pos().below(), UNDERFLOOR);
			} else if (harp == null) {
				// The floor under a centre the run has stopped driving. Only where that centre is a
				// stone: a note block plays what it stands on, and filling the floor under one read
				// back basedrum sixty-two times over -- every tick right and every instrument wrong.
				set(placements, at.pos(), "minecraft:stone");
			}
			return at.ahead(1);
		}
		// The floor rail has no centre to offer. The path rail's repeater stands directly over it and
		// a note block plays only with air above, so both notes go out to the sides.
		placements.placing("rail:FLOOR notes" + chord.size());
		placements.padded("railFloorNotes");
		placements.powered(at.pos(), "minecraft:stone", time);
		// And filled towards the lane behind first, which is the opposite way round from the path
		// rail. A floor note sits at the lane's own floor level, which is exactly where a stacked
		// module hangs its four low notes -- so a chord of one or two that fills the side facing
		// ground the walk has not built yet leaves live stone against a slot the next lane's stacked
		// modules were going to want. The side already built is one {@link #soundedByAnother} can see
		// the whole of. ekran, reading a floor column in game.
		hangRailNotes(placements, at.pos(), at.noteSide().getOpposite(), hanging, time, true);
		// The path rail's own repeater, carrying it on to the next column -- or, where the run stops
		// here, the one that hands the path rail up so the lane after it has something to read. The
		// two are the same block in the same cell and differ only in what they are timed against, so
		// the ending case is the delay the caller worked out rather than a second shape.
		int above = nextDelay > 0 ? nextDelay : handUp;
		if (above > 0) {
			set(placements, at.pos().above(),
				"minecraft:repeater[facing=" + facing + ",delay=" + above + "]");
		}
		return at.ahead(1);
	}

	/**
	 * The chord's first harp note, taken out of it, or {@code null} where the chord holds none.
	 *
	 * <p>Only a harp note can take a centre, so a chord that has one spends a side fewer than a chord
	 * that has not -- which is the whole of why the order is centre first.</p>
	 */
	private static EventNote takeHarpNote(List<EventNote> chord) {
		for (int index = 0; index < chord.size(); index++) {
			if (isHarpNote(chord.get(index))) {
				return chord.remove(index);
			}
		}
		return null;
	}

	/** Whether a note may stand in this slot without something else sounding it at another tick. */
	private static boolean railSlotTakes(PlacementPlan placements, BlockPos slot, int time) {
		return placements.freeForNote(slot) && !soundedByAnother(placements, slot, time);
	}

	/**
	 * Whether a floor column standing here could hold this chord without sounding it early.
	 *
	 * <p>The one contention a run has. A floor column's notes sit at the lane's own floor level,
	 * which is where the lane alongside hangs the low half of a stacked module, and a stacked
	 * module's low notes stand on instrument blocks that conduct sideways. The near side is the side
	 * facing the lane <em>behind</em> -- deliberately, so a run cannot reach into ground the walk has
	 * not built yet -- and that is the lane whose stacked modules are already standing there. So this
	 * is asked of a column that does not exist yet, one cell ahead of where the walk is, at the tick
	 * the chord would go live. ekran, who named the shape before it was measured: "a lower rail note
	 * can get mispowered by a stacked chord if the center adjacent instrument of that stacked chord is
	 * up against it".</p>
	 *
	 * @param floor the cell the floor column would stand in, or {@code null} where the caller is
	 *     asking about events rather than about a place -- which is every question asked before the
	 *     walk knows where the head will land
	 */
	private static boolean railFloorTakes(PlacementPlan placements, Lane floor, EventGroup event) {
		if (!RAIL_MOVES_FOR_STACKS || floor == null) {
			return true;
		}
		Direction near = floor.noteSide().getOpposite();
		int room = 0;
		for (Direction side : List.of(near, near.getOpposite())) {
			if (railSlotTakes(placements, floor.pos().relative(side), event.time())) {
				room++;
			}
		}
		return room >= event.notes().size();
	}

	/**
	 * Off, a run hangs its notes on the near side whatever is already standing there, and takes a
	 * floor column whatever it would sound.
	 */
	static boolean RAIL_MOVES_FOR_STACKS = true;

	/** What is left of a chord, hung either side of the cell that drives it, near side first. */
	/**
	 * @param laneHeight whether this is a floor column, whose two notes hang at the lane's own floor
	 *     level. A path column sits a level higher and its notes are ordinary ones.
	 */
	private static void hangRailNotes(PlacementPlan placements, BlockPos anchor, Direction near,
			List<EventNote> notes, int time, boolean laneHeight) {
		List<Direction> sides = List.of(near, near.getOpposite());
		// Unless the near side is spoken for and the chord is small enough to go the other way. The
		// near side is the one facing the lane already built, so it is the only one that can have
		// something in it -- and a single note that moves across costs nothing at all, where the same
		// note left where it was sounds a stacked chord's tick instead of its own.
		if (RAIL_MOVES_FOR_STACKS && notes.size() < sides.size()
				&& !railSlotTakes(placements, anchor.relative(near), time)
				&& railSlotTakes(placements, anchor.relative(near.getOpposite()), time)) {
			placements.padded("railNoteMovedForStack");
			sides = List.of(near.getOpposite(), near);
		}
		for (int index = 0; index < notes.size(); index++) {
			if (index >= sides.size()) {
				placements.trouble((notes.size() - index) + " notes of a chord at tick " + time
					+ " had nowhere to hang: a rail column has a centre and two sides");
				return;
			}
			placeNote(placements, anchor.relative(sides.get(index)), notes.get(index), laneHeight);
		}
	}

	/**
	 * @param laneStep the direction the serpentine walks, which is the side a chord grows into
	 *     first. Pinning it to the lane step rather than to travel -- which reverses every lane --
	 *     is what makes {@link LaneReach} predictable enough to pack lanes closer than four apart.
	 */
	private static Body addSpatialEventModule(PlacementPlan placements, Lane lane,
			int triggerDelay, List<EventNote> chord, boolean forceBus) {
		placements.placing("chord:" + (forceBus ? "BUS" : "SMALL") + " notes" + chord.size());
		Body swapped = twoSwapTurn(placements, lane, triggerDelay, chord, forceBus);
		if (swapped != null) {
			return swapped;
		}
		lane = pastAnyCorner(placements, lane);
		set(placements, lane.pos(), "minecraft:stone");
		set(placements, lane.pos().above(), "minecraft:repeater[facing="
			+ repeaterFacing(lane.travel()) + ",delay=" + triggerDelay + "]");
		return layEventBody(placements, lane, chord, forceBus);
	}

	/**
	 * The two-swap turn: a repeater that would land on a corner trades places with a note instead of
	 * being padded past it (ekran, 2026-07-31, built by hand in world first).
	 *
	 * <p>A repeater may not stand on a corner, so until now dust took the corner and the repeater
	 * moved one cell along. That cell carries nothing and costs a block of the fifteen a repeater
	 * reaches, which is where every over-long run in the library comes from -- and it is not rare:
	 * Hammer at twelve wide spends ninety-three cells on it.</p>
	 *
	 * <p>The trick is to notice that a corner is a perfectly good place for a <em>note</em>, and that
	 * the chord already turning through the bend has notes to spare. Picture the repeater placed on
	 * the corner illegally, before anything has been padded, and then make two trades:</p>
	 *
	 * <pre>
	 *   swap 1   the corner repeater  &lt;-&gt;  the last note inside the bend
	 *   swap 2   the note that repeater now faces  &lt;-&gt;  the next chord's first bus block
	 * </pre>
	 *
	 * <p>After the first, the repeater stands on the inside diagonal of the corner, reading the last
	 * block of the bus behind it, and the corner holds the note it displaced. After the second, what
	 * the repeater drives is a bus block rather than a note, so the line lives. Four blocks change
	 * places; nothing is added and nothing is lost, and the corner cell earns its keep.</p>
	 *
	 * <p>Both chords have to be buses. The next one is built here so that falls out; the one turning
	 * through the bend has to have left a note on the inside diagonal, which is what the check for it
	 * amounts to. Where either fails this returns null and the old dust-and-shuffle runs.</p>
	 *
	 * <p>Only at the bend a route ends on. A bend with another still to come is mid-turn, and the
	 * cell the repeater would take is one the rest of the turn is about to want.</p>
	 */
	/** Whether a note here would be set off by something belonging to another tick. */
	private static boolean soundedByAnother(PlacementPlan placements, BlockPos slot, int time) {
		for (Direction direction : Direction.values()) {
			if (placements.liveAt(slot.relative(direction), time)) {
				return true;
			}
		}
		return false;
	}

	/** The cells a route occupies, so a bus laid along it does not hang a note in its own way. */
	private static Set<BlockPos> route(Lane along, int cells) {
		Set<BlockPos> taken = new java.util.HashSet<>();
		for (int cell = 0; cell < cells; cell++) {
			taken.add(along.ahead(cell).pos().immutable());
		}
		return taken;
	}

	/** Where a two-swap turn's blocks would go: the corner, the note it displaces, the bus opening. */
	private record SwapTurn(BlockPos corner, BlockPos inside, String note, int when,
			String instrument, Lane opening) {
	}

	private static SwapTurn noSwap(PlacementPlan placements, boolean census, String why) {
		if (census) {
			placements.padded(why);
		}
		if (TRACE) {
			System.out.println("SWAPNO " + why);
		}
		return null;
	}

	/**
	 * The blocks a two-swap turn would trade, worked out before any of them moves.
	 *
	 * <p>Asked twice, and by the same function both times on purpose. The walk asks first, because
	 * the column that stepping off a corner costs has to be known before anything is measured against
	 * the wall -- and a corner a swap is going to take costs nothing. The turn asks again when it is
	 * time to move the blocks. Answering that in two places is the shape of every long-lived bug in
	 * this file, a planner and a walk disagreeing over a single cell, so there is one.</p>
	 *
	 * @param census whether a refusal is recorded in the padding count. The walk's question is
	 *     speculative and would otherwise be counted twice.
	 */
	private static SwapTurn planSwapTurn(PlacementPlan placements, Lane lane, List<EventNote> chord,
			boolean forceBus, boolean census) {
		if (!lane.cornerAt(0)) {
			return null;
		}
		if (census) {
			placements.padded("swapSeen");
		}
		if (TRACE) {
			System.out.println("SWAPTRY at " + lane.pos().getX() + " " + lane.pos().getY() + " "
				+ lane.pos().getZ() + " travel=" + lane.travel() + " side=" + lane.noteSide()
				+ " bends=" + lane.bends() + " notes=" + chord.size() + " forceBus=" + forceBus
				+ " census=" + census);
		}
		if (!(forceBus || chord.size() > 3)) {
			return noSwap(placements, census, "swapNotBus");
		}
		// A bend still to come is carried onto the opening route below, one cell nearer, because the
		// swap cuts a cell out of the path. One that would land on the opening's own first bend is a
		// turn too tight to cut into.
		for (Lane.Bend pending : lane.bends()) {
			if (pending.after() < 3) {
				return noSwap(placements, census, "swapBendTooNear");
			}
		}
		BlockPos corner = lane.pos().above();
		Direction travel = lane.travel();
		// Which side the wire arrived from. At a corner it is one of the two perpendiculars, and the
		// one that already holds a bus block is the one the chord came round.
		Direction arrival = null;
		for (Direction side : List.of(lane.noteSide(), lane.noteSide().getOpposite())) {
			BlockPos behind = corner.relative(side);
			// Exactly stone, not merely starting with it. A bus block is laid as the bare string, and
			// there are half-blocks in a build now whose names begin the same way.
			if ("minecraft:stone".equals(placements.describeBlock(behind))
					&& placements.describeBlock(behind.above())
						.startsWith("minecraft:redstone_wire")) {
				arrival = side;
			}
		}
		if (arrival == null) {
			return noSwap(placements, census, "swapNoArrival");
		}
		// The inside diagonal of the bend: one back along the way the wire came, one on along the way
		// it leaves. The last note the turning chord hung inside the corner sits here.
		BlockPos inside = corner.relative(arrival).relative(travel);
		String note = placements.describeBlock(inside);
		Integer when = placements.noteTime(inside);
		String instrument = placements.describeBlock(inside.below());
		// Air under the note is not a reason to refuse. Air under a note block *is* harp -- the block
		// below only names the instrument, and anything unrecognised already plays one, so harp is
		// written as air and costs nothing. Reading that as "no instrument here" meant the swap could
		// never take a harp note, which is to say it could never take the commonest note there is.
		// Nothing recorded at all is still a refusal: that is a cell the plan has never heard of.
		if (!note.startsWith("minecraft:note_block") || when == null || "-".equals(instrument)) {
			if (TRACE) {
				System.out.println("SWAPNO inside=" + inside.getX() + " " + inside.getY() + " "
					+ inside.getZ() + " holds " + note + " over " + instrument + " when=" + when);
			}
			return noSwap(placements, census, "swapNoNote");
		}
		if (!placements.freeForNote(corner)) {
			if (TRACE) {
				System.out.println("SWAPNO corner=" + corner.getX() + " " + corner.getY() + " "
					+ corner.getZ() + " holds " + placements.describeBlock(corner));
			}
			return noSwap(placements, census, "swapCornerBusy");
		}
		// The cell the repeater will drive is the one cell of the opening that is not on the route
		// the turn reserved, so it is the one that can already be spoken for -- by a note of the lane
		// alongside, most often. Asked before anything moves, because the swaps are not undoable:
		// {@link PlacementPlan#set} refuses to overwrite and half a swap is a broken machine.
		BlockPos opens = inside.relative(travel);
		Direction step = arrival.getOpposite();
		List<Lane.Bend> route = new ArrayList<>();
		route.add(new Lane.Bend(1, step.getClockWise() == travel));
		for (Lane.Bend pending : lane.bends()) {
			route.add(new Lane.Bend(pending.after() - 1, pending.clockwise()));
		}
		Lane opening = Lane.straight(opens, step, travel).bending(route).crowding();
		// Every cell the bus could stand on, before anything moves. The swaps are not undoable --
		// {@link PlacementPlan#set} refuses to overwrite on purpose, and half a swap is a broken
		// machine -- so the whole run has to be known clear first. The opening's own cell is the one
		// off the reserved route, but a shifted bend puts the cells after it a column off too, and
		// those are where a note of the lane alongside is already standing.
		int wanted = Math.min(DUST_RANGE, (chord.size() + 1) / 2 + 2);
		for (int cell = 0; cell < wanted; cell++) {
			BlockPos at = opening.ahead(cell).pos();
			if (!"-".equals(placements.describeBlock(at))
					|| !"-".equals(placements.describeBlock(at.above()))) {
				if (TRACE) {
					System.out.println("SWAPNO opening cell " + cell + " of " + wanted + " at "
						+ at.getX() + " " + at.getY() + " " + at.getZ() + " holds "
						+ placements.describeBlock(at) + " under "
						+ placements.describeBlock(at.above()));
				}
				return noSwap(placements, census, "swapOpeningBusy");
			}
		}
		return new SwapTurn(corner, inside, note, when, instrument, opening);
	}

	private static Body twoSwapTurn(PlacementPlan placements, Lane lane, int triggerDelay,
			List<EventNote> chord, boolean forceBus) {
		SwapTurn swap = planSwapTurn(placements, lane, chord, forceBus, true);
		if (swap == null) {
			return null;
		}
		Direction travel = lane.travel();
		BlockPos corner = swap.corner();
		BlockPos inside = swap.inside();
		String note = swap.note();
		int when = swap.when();
		String instrument = swap.instrument();
		Lane opening = swap.opening();
		placements.padded("swapDone");
		if (TRACE) {
			System.out.println("SWAP corner=" + corner.getX() + "," + corner.getY() + ","
				+ corner.getZ() + " inside=" + inside.getX() + "," + inside.getY() + ","
				+ inside.getZ() + " opens=" + opening.pos().getX() + "," + opening.pos().getY()
				+ "," + opening.pos().getZ() + " travel=" + travel
				+ " bends=" + lane.bends() + " notes=" + chord.size());
		}
		// Swap one. The note goes to the corner, which powers it just as well -- the bus block the
		// wire arrived on is right beside it -- and the repeater takes the cell the note left.
		placements.take(inside);
		placements.take(inside.below());
		set(placements, corner.below(), instrument);
		set(placements, corner, note);
		placements.note(corner, when);
		set(placements, inside.below(), "minecraft:stone");
		set(placements, inside, "minecraft:repeater[facing=" + repeaterFacing(travel)
			+ ",delay=" + triggerDelay + "]");
		// Swap two. What the repeater drives has to be a bus block, so the next chord opens one cell
		// off the lane and steps onto it, rather than opening on the lane and being faced by a note.
		//
		// The opening steps sideways once and turns back onto the route, and every bend the turn had
		// still to make comes with it a cell nearer -- the swap takes a cell out of the path, so what
		// stood at offset n from the corner now stands at n minus one. Getting that wrong does not
		// break the wire, it lays the rest of the turn down crooked, which is why it is arithmetic
		// here rather than a route rebuilt from scratch.
		// Reserved well past what this chord will use. The cells beyond the bus are not free either:
		// they are where the walk carries on, and the next module stands its repeater on the first of
		// them. A note hung there by this bus is a collision the walk finds two events later. Ekran's
		// on Hammer at twelve wide: a chord of two, one cell of bus, a note on the cell the route
		// bends into, and the repeater after it had nowhere to stand.
		int cells = layBus(placements, opening, chord, chord.get(0).time(),
			route(opening, DUST_RANGE + 2));
		Lane landed = opening.ahead(cells);
		return new Body(new Lane(landed.pos().below(), landed.travel(), landed.noteSide(),
			landed.bends(), landed.cornerAt(0), lane.crowded()), cells);
	}

	/**
	 * The same chord, set off by the wire that arrives rather than by a repeater of its own.
	 *
	 * <p>For the event that has to cross a floor change. A lane ends where the wall says and not where
	 * the music does, so now and then the wait before an event is a single tick -- the shortest there
	 * is -- and the pad that fills the lane still needs a repeater to carry the wire up the staircase.
	 * There is only one tick to go round, so the two become one: the pad's repeater holds the whole
	 * wait, the staircase and the run into this module are dust, and dust takes no time at all. The
	 * chord lands on the tick it was written for, one floor along from the repeater that sent it.</p>
	 *
	 * <p>Which is why it is always a bus. Every other module hangs its notes off a block a repeater
	 * drives directly, and there is no repeater here; a bus lights its own stone from the dust running
	 * over it, so it does not need one.</p>
	 */
	/**
	 * @param stepOff how many columns along from where the turn left off the bus starts, each of them
	 *     glass with dust over it. Not nought after a descent, because a descent is three columns wide
	 *     and steps one of them sideways into the corridor alongside -- so those columns are reserved
	 *     for turns, in every corridor, and notes begin past them. That is what makes it impossible
	 *     for one corridor's staircase to reach another corridor's notes: they are never at the same
	 *     x. It used to be a flag meaning "clear the spiral", which cleared the spiral in front of it
	 *     and left the notes in the column the neighbour's spiral comes down
	 *     of glass with dust over it, rather than on that block itself. A descent needs it: the
	 *     spiral comes back up through the column right in front of where it lands, and a note hung
	 *     there would sit under live stone. A climb does not, and the column it saves is the
	 *     difference between a chord of twenty-two straddling a turn and not.
	 */
	private static BlockPos addCarriedEventModule(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction laneStep, List<EventNote> chord, int stepOff) {
		placements.placing("farHalf");
		for (int column = 0; column < stepOff; column++) {
			placements.padded("stepOff");
		}
		cursor = emitDust(placements, Lane.straight(cursor, travel, laneStep), stepOff,
			false, "farHalfStepOff").pos();
		// The wire arrives level with the repeater that is not there, and climbs the side of the
		// first stone onto the dust running over it.
		return cursor.relative(travel, layBus(placements,
			Lane.straight(cursor.above(), travel, laneStep), chord, chord.get(0).time()));
	}

	/**
	 * The lane's last chord, cut so that what fits before the wall ends exactly on it.
	 *
	 * <p>A chord is the one thing in a build that can be cut, because everything from its repeater
	 * onwards is dust and dust takes no time: the far half is on the same tick as the near half
	 * however many blocks and however much staircase lie between them. And cutting one costs
	 * nothing, where filling the same columns with a pad costs the wire that runs through them --
	 * which is the whole reason a lane used to stop short of its wall.</p>
	 */
	private static BlockPos addSplitEventModule(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction laneStep, int triggerDelay, List<EventNote> chord) {
		placements.placing("nearHalf");
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		return cursor.relative(travel, 1 + layBus(placements,
			Lane.straight(cursor.relative(travel).above(), travel, laneStep), chord,
			chord.get(0).time()));
	}

	/**
	 * The route a lane's blocks are laid along: where cell {@code n} is, and which way the wire is
	 * running when it gets there.
	 *
	 * <p>Every position in a build used to be {@code cursor.relative(travel, n)}, which says a lane
	 * is a straight line and says it in every placement function at once. That is why a turn has to
	 * be a separate object with geometry of its own, and why a chord straddling one has to be carved
	 * into a near half and a far half by hand. Asking the route instead leaves one place that knows
	 * a lane can bend.</p>
	 *
	 * <p>Straight for now, and deliberately so: this hands back exactly what the arithmetic it
	 * replaces did, which is what makes the change provable rather than merely plausible.</p>
	 *
	 * @param noteSide the way the first note of a pair hangs off the run. Pinned to the lane step
	 *     rather than to travel -- which reverses every lane -- because that is what makes
	 *     {@link LaneReach} predictable enough to pack lanes closer than four apart.
	 */
	private record Lane(BlockPos pos, Direction travel, Direction noteSide, List<Bend> bends,
			boolean onCorner, boolean crowded) {

		/**
		 * A corner the route takes: after this many more cells, the wire turns to face a new way.
		 *
		 * @param clockwise which way it turns, so that a chord riding through it turns with it
		 */
		record Bend(int after, boolean clockwise) {
		}

		static Lane straight(BlockPos pos, Direction travel, Direction noteSide) {
			return new Lane(pos, travel, noteSide, List.of(), false, false);
		}

		/** The same route with corners ahead of it, at cell offsets counted from here. */
		Lane bending(List<Bend> corners) {
			return new Lane(pos, travel, noteSide, List.copyOf(corners), onCorner, crowded);
		}

		/**
		 * The cell this many further along, having taken any corner that falls in between.
		 *
		 * <p>The note side turns with the path and not with the world. A chord riding through a
		 * corner comes out the far side lying the same way round relative to the wire it hangs on,
		 * which is what makes a bend nothing more than more lane. Turning it with the world instead
		 * would mirror every chord at every corner.</p>
		 *
		 * <p>Straight, this is exactly {@code pos.relative(travel, cells)} -- which is what it
		 * replaced, and why nothing in a build without corners moves.</p>
		 */
		Lane ahead(int cells) {
			BlockPos where = pos;
			Direction facing = travel;
			Direction side = noteSide;
			List<Bend> remaining = bends;
			boolean corner = cells == 0 && onCorner;
			for (int step = 0; step < cells; step++) {
				where = where.relative(facing);
				corner = false;
				List<Bend> next = new ArrayList<>();
				for (Bend bend : remaining) {
					if (bend.after() == step + 1) {
						facing = bend.clockwise() ? facing.getClockWise() : facing.getCounterClockWise();
						side = bend.clockwise() ? side.getClockWise() : side.getCounterClockWise();
						corner = true;
					} else {
						next.add(bend);
					}
				}
				remaining = next;
			}
			List<Bend> shifted = new ArrayList<>();
			for (Bend bend : remaining) {
				shifted.add(new Bend(bend.after() - cells, bend.clockwise()));
			}
			return new Lane(where, facing, side, List.copyOf(shifted), corner, crowded);
		}

		Lane above() {
			return new Lane(pos.above(), travel, noteSide, bends, onCorner, crowded);
		}

		/** Whether the wire changes direction at this cell, where no repeater may ever stand. */
		boolean cornerAt(int cells) {
			return cells == 0 ? onCorner : bends.stream().anyMatch(bend -> bend.after() == cells);
		}

		/** Whether the route still has a corner ahead of it -- that is, whether it is mid-turn. */
		boolean bending() {
			return !bends.isEmpty();
		}

		/**
		 * The same route, marked as running through ground the walk does not own outright.
		 *
		 * <p>Carried by every cell derived from it, and not merely by the ones with a corner still
		 * ahead. A chord that starts on the last corner has no bend left in front of it and is very
		 * much still in the corridor -- so asking {@link #bending()} said the coast was clear exactly
		 * where it was not, which is a bus laying a note into a neighbour's repeater. The mark is
		 * dropped by starting a fresh straight route, which is what the walk does when it comes out
		 * the far side of a turn.</p>
		 */
		Lane crowding() {
			return new Lane(pos, travel, noteSide, bends, onCorner, true);
		}

		/**
		 * This same cell with its corners spent and the note side pinned back to a fixed direction.
		 *
		 * <p>For the moment a lane comes out of a turn. Rebuilding it with {@link #straight} instead
		 * loses what this cell knows about itself -- and the one thing it knows is the thing that
		 * matters here, because a route leaves a turn *standing on* the second corner. Throwing that
		 * away put a repeater on that corner every time, which is the one place a repeater can never
		 * go, and it did so at the exact cell the walk had just been told about.</p>
		 */
		Lane pinned(Direction side) {
			return new Lane(pos, travel, side, List.of(), onCorner, crowded);
		}

		/** Where the note on one side of this cell hangs. */
		BlockPos note(boolean firstOfPair) {
			return pos.relative(firstOfPair ? noteSide : noteSide.getOpposite());
		}

		/**
		 * Every cell a note could hang in off this one, in the order they should be filled.
		 *
		 * <p>Two down a straight run, and three at a corner. The extra one is the cell behind the
		 * direction the wire leaves on, which is free for exactly the reason the corner exists: the
		 * wire turned away from it. It is as good a place for a note as either of the others -- the
		 * block it hangs on is the same powered stone -- and nothing was offering it, because the
		 * pair either side of the run is worked out from the outgoing direction alone.</p>
		 *
		 * <p>That mattered more than it looks. A corner already loses one of its pair to the cell the
		 * wire arrived from, so without this a turn costs a chord two note slots, and a bus that has
		 * to grow two blocks to make them up can be pushed past the fifteen its repeater reaches --
		 * at which point the far end of it never fires at all. Ekran found it as two copper bulbs
		 * that never lit, on a chord of twenty-eight that should have fitted round the bend.</p>
		 */
		List<BlockPos> noteSlots() {
			if (!onCorner) {
				return List.of(note(true), note(false));
			}
			// Three at a corner. The pair either side of the run is worked out from the direction the
			// wire *leaves* on, which makes the cell opposite that direction invisible -- and that
			// cell is free for exactly the reason the corner exists, because the wire turned away
			// from it. Traced on ekran's failing chord: at both corners the run's own stone took one
			// of the pair, so a turn was costing the chord two slots and the bus grew two blocks to
			// make them up. Sixteen blocks is past the fifteen a repeater reaches, so the tail of it
			// never fired -- two copper bulbs that stayed lit on a chord of twenty-eight.
			return List.of(note(true), note(false), pos.relative(travel.getOpposite()));
		}
	}

	/**
	 * A run of powered stone with a note down each side of it, and the dust that lights the run.
	 *
	 * @return how many blocks of it there are, which is also how much wire it spends
	 */
	private static int layBus(PlacementPlan placements, Lane anchor, List<EventNote> chord,
			int time) {
		return layBus(placements, anchor, chord, time, Set.of());
	}

	/**
	 * @param reserved cells the run itself is going to want later, which a note may not be hung in
	 *     however free they look right now. A bus normally owns its own columns, so nothing needs
	 *     saying; a bus laid round a cut corner doubles back past its own opening, and asking "is
	 *     this block free" of a cell three cells of route away answers the wrong question. The
	 *     collision it caused was a note from the first cell standing where the fourth wanted stone.
	 */
	private static int layBus(PlacementPlan placements, Lane anchor, List<EventNote> chord,
			int time, Set<BlockPos> reserved) {
		List<EventNote> ordered = busOrder(chord);
		int placed = 0;
		int cells = 0;
		// Two notes a block, unless the ground will not have one -- and then the run simply carries on
		// a block further and hangs it there. That is what makes a bus the fallback every other shape
		// drops to: it needs nothing of its surroundings except somewhere to put the next note, and
		// where there is nowhere, it grows. A corner is the case that wants it. The inner slot of a
		// bend is the cell the wire arrived from, so a bus riding round one has a slot missing, and
		// counting the length up front is what used to make it place a note into its own wire.
		// Asked only where the route bends. Down a straight lane the walk owns its own columns and
		// both slots are free by construction, so asking there is answering a question nobody posed --
		// and answering it with a check that is deliberately cautious about what counts as free, which
		// turned buses into longer buses all over builds that had nothing wrong with them.
		boolean crowded = anchor.crowded();
		// One cell shorter where the route bends, because a bus riding a bend does not hand the next
		// repeater its own last block. It comes off the bend a level down -- a bus runs above the
		// lane it stands on -- and that step is a cell of dust on this same run, so fifteen cells of
		// bus round a corner is sixteen blocks of wire and the far end of sixteen is worth nothing.
		// Two notes reported as having nowhere to hang is a far cheaper failure than a tail that
		// never fires and takes the rest of the song with it.
		//
		// Only where the route bends. Down a straight lane the next repeater does stand on the bus's
		// own last block, and giving up a cell there would cost a note pair for nothing.
		int limit = DUST_RANGE;
		while (placed < ordered.size() && cells < limit) {
			Lane at = anchor.ahead(cells);
			placements.powered(at.pos(), "minecraft:stone", time);
			set(placements, at.pos().above(), "minecraft:redstone_wire");
			cells++;
			// Filled away from the next lane first. The note side is pinned to the lane step, so the
			// first slot of the pair is the one facing ground the walk has not built yet -- and a
			// slot whose far neighbour does not exist is a slot no check can clear. Taking the other
			// one first puts each note where {@link #soundedByAnother} can actually see what it will
			// be standing against.
			// Only where every slot of the cell would be taken anyway, which is what keeps this from
			// changing the length of the run. Reversed unconditionally it also reverses which slot
			// gets skipped when one is blocked, and a bus that skips a different slot grows to a
			// different length -- that put a run of sixteen into fast-and-dense at two floors.
			// And never near a bend, because the two-swap turn wants a note on exactly the slot this
			// would move away from. The inside diagonal of a corner is the note that turn trades for
			// the corner cell, and a chord that fills the far side first leaves it empty -- which is
			// a swap refused, a corner taken by dust, and a run of sixteen. The two changes want
			// opposite things within two cells of a corner, and there the older one wins.
			List<BlockPos> slots = at.noteSlots();
			boolean nearBend = at.cornerAt(0)
				|| at.bends().stream().anyMatch(bend -> bend.after() <= 2);
			if (crowded && !nearBend && slots.stream().allMatch(slot -> !reserved.contains(slot)
					&& placements.freeForNote(slot)
					&& !soundedByAnother(placements, slot, time))) {
				slots = new ArrayList<>(slots);
				java.util.Collections.reverse(slots);
			}
			// And the chord's last note, wherever it falls, goes to the low-z slot of the pair.
			//
			// The rule above is the same idea and does most of the work, but it is all-or-nothing per
			// cell and it stands down near a bend, which is exactly where the odd note lands when a
			// chord rides a corner. A cell holding one note has two slots and one note to put in them,
			// so which it takes cannot change the length of the run -- only which lane the note ends
			// up beside, and the high-z slot is beside the lane the walk has not built yet.
			//
			// ekran's, off the corner at 32 wide over two floors: a bus of five with both slots of its
			// last cell free took the one facing the next lane, and the corner cell of that lane came
			// down against it. Not free, though -- the bend exception it overrides is there because the
			// two-swap turn wants a note on the inside diagonal, so this can cost a swap where the odd
			// note is that diagonal. Measured either way; see {@link #BUS_ODD_NOTE_AWAY_FROM_NEXT_LANE}.
			if (BUS_ODD_NOTE_AWAY_FROM_NEXT_LANE && ordered.size() - placed == 1 && slots.size() >= 2
					&& slots.get(1).getZ() < slots.get(0).getZ()) {
				List<BlockPos> lowFirst = new ArrayList<>(slots);
				lowFirst.set(0, slots.get(1));
				lowFirst.set(1, slots.get(0));
				slots = lowFirst;
			}
			for (BlockPos slot : slots) {
				if (TRACE) {
					System.out.println("  BUS cell=" + (cells - 1) + " corner=" + at.cornerAt(0)
						+ " slot=" + slot.getX() + "," + slot.getY() + "," + slot.getZ()
						+ " free=" + placements.freeForNote(slot)
						+ " [at=" + placements.describeBlock(slot)
						+ " below=" + placements.describeBlock(slot.below())
						+ " above=" + placements.describeBlock(slot.above()) + "]");
				}
				// Free is not enough where lanes touch: a slot with nothing in it may still be beside
				// a block that goes live on somebody else's tick, and a note hung there sounds with
				// them instead of with its own chord. The two-swap turn made that common -- it opens
				// the next chord's bus one cell into the air gap, and the air gap is where the lane
				// beyond hangs its notes -- but the rule is not about the swap and belongs here, next
				// to the other question about whether a slot will do.
				if (placed < ordered.size() && !reserved.contains(slot)
						&& (!crowded || placements.freeForNote(slot) && !soundedByAnother(
							placements, slot, time))) {
					placeNote(placements, slot, ordered.get(placed++));
				}
			}
		}
		if (placed < ordered.size()) {
			placements.trouble((ordered.size() - placed) + " notes of a chord of " + ordered.size()
				+ " at tick " + time + " had nowhere to hang: a bus is fifteen blocks at the most, "
				+ "and this one filled them without room for the rest");
		}
		return Math.max(1, cells);
	}

	/**
	 * How far a bus is allowed to grow looking for slots, which is well past what it can power.
	 *
	 * <p>A bus that skips slots is longer than the chord it carries, and long enough is unpowered at
	 * the far end. The cap is not the redstone range, though: it is only there so that a bus with
	 * nowhere at all to put its notes stops rather than runs for ever. Being past fifteen is a
	 * complaint the layout check makes, and it should be allowed to make it.</p>
	 */
	private static final int MAX_BUS_CELLS = 32;

	/** Cells of lane a stacked module stands in: the repeater it opens with, and its centre. */
	private static final int STACKED_CELLS = 2;

	/**
	 * The cell a chord pays for the bend it stops in front of.
	 *
	 * <p>A repeater may not stand on a corner, so dust takes the corner and the repeater goes one
	 * further along. That dust belongs to the run of whatever chord last put a repeater down.</p>
	 */
	private static final int CORNER_AHEAD = 1;

	/**
	 * What the wire must still be worth for a module to be nudged a cell along.
	 *
	 * <p>One, because that is exactly what the nudge spends: the dust goes where the repeater would
	 * have stood and the repeater goes one further, so the signal has one more block to travel. A
	 * module built where it stands needs nothing, since the wire is already there.</p>
	 */
	private static final int NUDGE_REACH = 1;

	/**
	 * Whether a stacked shape is refused here for the turn.
	 *
	 * <p>Asked at the moment of use rather than once per event, which is not a style choice: the walk
	 * takes its turn part way through an iteration, so {@code turning} means different things before
	 * and after. Hoisting it into a local broke every mode at once and refused 1,528 builds.</p>
	 *
	 * <p>Two forms. The old one asks "is this the first chord after the turn", a count. ekran's asks
	 * how far the repeater stands from the second corner, which is what the blocks care about --
	 * Kick Back's chord of twenty-one was refused six columns clear of its corner while i-wonder's
	 * was refused standing against the exit run, and a count cannot tell those apart.</p>
	 */
	/**
	 * The same question, asked of the blocks rather than of where the walk has got to.
	 *
	 * <p>See {@link #STACKED_MAY_WRAP_A_BEND}. Off, this is the rule above exactly.</p>
	 */
	private static boolean inTurn(PlacementPlan placements, boolean turning, boolean leavingTurn,
			BlockPos at, BlockPos lastCorner) {
		if (!STACKED_MAY_WRAP_A_BEND) {
			return inTurn(turning, leavingTurn, at, lastCorner);
		}
		// No short circuit on `turning`. A chord on the bend is judged by how far its centre stands
		// from the corners, which is the thing that decides whether anything can reach it, and the
		// walk being mid-turn is not that thing.
		return placements.cornerDistance(at) < STACKED_CLEAR_OF_CORNER;
	}

	private static boolean inTurn(boolean turning, boolean leavingTurn, BlockPos at,
			BlockPos lastCorner) {
		if (turning) {
			return true;
		}
		if (!TURN_BAN_BY_DISTANCE) {
			return leavingTurn;
		}
		return lastCorner != null
			&& Math.abs(at.getX() - lastCorner.getX()) + Math.abs(at.getZ() - lastCorner.getZ())
				< STACKED_CLEAR_OF_CORNER;
	}

	/**
	 * Scratch: whether the turn ban is asked as a distance from the corner rather than a chord count.
	 *
	 * <p>{@link #TURN_BAN_OUTLASTS} asks "is this the first chord after the turn", which is not the
	 * question the blocks care about. Kick Back's chord of twenty-one was refused six columns clear
	 * of its corner -- the turn's own bus was filling those columns -- and i-wonder's was refused
	 * standing directly against the exit run, which is the case that actually breaks. Both are "the
	 * first chord after the turn".</p>
	 *
	 * <p>ekran's rule instead: the centre of a stacked chord is clear of a corner at four blocks, so
	 * the repeater may stand at {@code corner + 3}.</p>
	 */
	static boolean TURN_BAN_BY_DISTANCE = true;

	/**
	 * v2: a chord may wrap a bend, so long as its centre keeps clear of both corners.
	 *
	 * <p>{@link #inTurn} answers the distance question ekran's rule asks -- and never gets to ask it
	 * while the route is still bending, because {@code if (turning) return true;} comes first. So a
	 * chord laid on the bend is refused the stacked shape for <em>where the walk is</em> rather than
	 * for where its blocks are, falls back to a bus, and the lane runs out of room.</p>
	 *
	 * <p>ekran found one at Guardian 15 wide over six floors, {@code 15 81 379}: a stacked bus of
	 * twenty that breached a <b>flat</b> turn, and which they then rebuilt by hand taking both ends
	 * of the bend with no breach and no dead wire. Their rule, read off those blocks: a stacked
	 * centre is safe at exactly three blocks from a corner, the corners being the two stone cells the
	 * lane turns on. Nothing laid along the perpendicular arm can reach it there.</p>
	 *
	 * <p>Which the constant already says -- {@code STACKED_CLEAR_OF_CORNER} is three and the test is
	 * {@code < 3}, so three is allowed. All this does is let the test be reached, and measure it
	 * against every corner on the level rather than the last one recorded, because a bend has two.</p>
	 */
	static boolean STACKED_MAY_WRAP_A_BEND = true;

	/** How far past a corner a stacked module's repeater may stand. */
	private static final int STACKED_CLEAR_OF_CORNER = 3;

	/**
	 * Scratch: whether the no-stacked-shapes-in-a-turn rule outlasts the turn by one chord.
	 *
	 * <p>It costs more than any other refusal in a folded build -- 55 stacked buses on Kick Back at
	 * twenty wide alone, against 41 for the column behind and 9 for room. And it is charged on a
	 * chord that is in line with what it follows, not across it, because a turn's bus leaves its
	 * second bend already running the new lane's way.</p>
	 *
	 * <p>The cost is not the refusal itself but what the longer shape leaves behind: a bus of
	 * twenty-one hands on {@code 15 - 11 = 4} where a stacked bus hands on {@code 15 - 1 - 7 = 7},
	 * and a descent wants five. So the lane reaches its wall unable to pay for the staircase.</p>
	 *
	 * <p><b>Measured, and it stays on.</b> The whole library, six floor counts, ten widths:</p>
	 *
	 * <pre>
	 *   on    breaches 619   blocks 31,990,356   spanZ 228,460   wrong        0
	 *   off   breaches 654   blocks 31,938,732   spanZ 227,057   wrong    1,903
	 * </pre>
	 *
	 * <p>Off is a fifth of a percent smaller and sounds one thousand nine hundred notes at the wrong
	 * tick. So the reason in the comment above is wrong about the geometry and right about the
	 * conclusion: whatever the chord after a turn is perpendicular to, something is there, and the
	 * collinear argument -- that a turn's bus leaves its second bend already running the new lane's
	 * way, so the cells behind the chord are in line with it -- does not describe all of it.</p>
	 *
	 * <p>Breaches get <em>worse</em> off, too, which was not the expected direction and is the more
	 * interesting half: the shape that saves a lane a column near a turn costs some other lane more
	 * than it saved. Worth understanding before anyone tries a narrower version of this rule.</p>
	 *
	 * <p>Note for whoever measures the narrower version: reading the machines back did not catch
	 * this. All 135 read builds came back with every note sounded and nothing unreached both ways,
	 * because a note at the wrong tick still sounds. {@code wrongNotes()} is what caught it.</p>
	 */
	static boolean TURN_BAN_OUTLASTS = true;

	/** Scratch: one line per chord placed, for finding the first one that goes wrong. */
	static boolean TRACE = false;

	/**
	 * Scratch: one line per turn decision, which {@link #TRACE} does not cover.
	 *
	 * <p>Separate because the two answer different questions and neither wants the other's volume.
	 * {@code TRACE} follows a chord that landed somewhere wrong; this follows a lane that walked
	 * past its wall, where every chord is placed correctly and the fault is that none of them was
	 * allowed to turn. Reading that out of a plain build means guessing which of {@code straddles},
	 * {@code canTurn}, {@code onWall} and the pad said no, and guessing is what this file's history
	 * says not to do.</p>
	 */
	static boolean TRACE_TURNS = false;

	/**
	 * Whether a paste comes out marked up: coloured by shape, and wrong where it is wrong.
	 *
	 * <p>Everything this file knows about a build it knows in plan space, and every one of those
	 * facts dies at {@code finish()} -- which shape laid a cell, which note has nothing to set it
	 * off, which lane went past its wall. The build that goes up is stone either way, so reading one
	 * means holding the report in one hand and counting columns with the other. This puts the report
	 * into the blocks instead:</p>
	 *
	 * <ul>
	 *   <li>the stone says what laid it -- tuff a bus, andesite a stacked chord, deepslate a stacked
	 *     bus, deepslate tiles the head of a cut, smooth basalt a rail, and plain stone the lane;</li>
	 *   <li>stripped crimson hyphae is a lane standing outside its wall, and red nether brick is wire
	 *     the signal never got to, which wins over everything because everything under it is moot;</li>
	 *   <li>a note that would sound at the wrong moment is a lit copper bulb, one with nothing to set
	 *     it off wears a dragon head -- both still leave a machine that runs;</li>
	 *   <li>and a cell two shapes both wanted is a sea lantern, the build going up rather than being
	 *     refused, which is what this flag originally did and all it did.</li>
	 * </ul>
	 *
	 * <p>The marking is a pass over the finished commands and never over the walk. Layout decisions
	 * in this file read the blocks back -- {@code describeBlock(behind).startsWith("minecraft:stone")}
	 * is one -- so a colour applied while walking would quietly build something else. Marked and
	 * plain must be the same machine, or the marks are describing a build nobody has.</p>
	 *
	 * <p>Never leave this on for a real build: the collision half of it keeps whatever was standing
	 * and drops the loser, which is broken on purpose.</p>
	 */
	public static boolean DEBUG_PASTE = false;

	/**
	 * Whether every cell remembers which shape laid it, without any of the debug paste's other doing.
	 *
	 * <p>"Which shape laid this cell" is the question a fault raises first and the one the blocks
	 * cannot answer, and until now the only way to ask it was {@link #DEBUG_PASTE} -- which also
	 * stops a layout collision throwing. That matters most in v2, whose trial-and-rollback
	 * <i>depends</i> on the throw to fall back to the conservative shape, so a v2 build made with
	 * names on has quietly skipped every fallback it would have taken. Naming the cells and changing
	 * the build were one switch, so every shape a fault was read off came off a different build from
	 * the one that has the fault.</p>
	 *
	 * <p>This is the naming half on its own: a string reference per cell, put down beside the block
	 * and taken back up with it on a rollback. It changes nothing a walk decides, so a probe may
	 * leave it on and read a build that is the build that ships.</p>
	 */
	static boolean NAME_EVERY_CELL = false;

	/**
	 * What a cell of lane is built from, given the name of the shape that laid it.
	 *
	 * <p>Grouped by family rather than by style, because the question a build raises is almost never
	 * "is this a {@code STACKED_BUS_HALF}" -- it is "is this thing a bus at all", or "did that chord
	 * get its head". So the stacked-bus family is one colour and the head of a cut is another, and
	 * the three head sizes that cost so much argument are deliberately not told apart: they differ by
	 * a note, which the chord itself shows you.</p>
	 *
	 * <p>Plain stone is everything that is not a chord -- wire, repeaters, pads, corners, staircases.
	 * That is most of a build, and it wants to stay the colour a build has always been, so the
	 * chords are what stand out.</p>
	 *
	 * <p>{@code minecraft:cobbled_deepslate} is the simple tail, which arrived with this branch: a
	 * stacked bus whose tail came to rest in one column rather than a run of them. It wears the
	 * handover column as well as the tail itself, because the two are one shape to look at -- and it
	 * is told apart from the plain stacked bus precisely because from outside it looks like one that
	 * came out a column short.</p>
	 */
	/**
	 * What every block a marked paste uses means, in the words you would say out loud.
	 *
	 * <p>Here rather than in a comment because three separate things have to explain this table and
	 * none of them should be holding its own copy: the chat key the command prints, the legend under
	 * an {@code /asciidiagram}, and {@link #shapeStone}, which is the thing that actually decides.
	 * The diagram is the one that matters most and is the easiest to forget -- a slice pasted into a
	 * conversation is often all anybody has of a build, and "TU = minecraft:tuff" tells a reader
	 * nothing at all unless it goes on to say that tuff is how a bus looks.</p>
	 *
	 * <p>Keyed by block id without its state, since that is what a reader has in hand.</p>
	 */
	static final Map<String, String> DEBUG_PASTE_KEY = debugPasteKey();

	private static Map<String, String> debugPasteKey() {
		Map<String, String> key = new LinkedHashMap<>();
		key.put("minecraft:stone", "the lane -- wire, repeaters, pads, corners, staircases");
		key.put("minecraft:tuff", "a standard bus");
		key.put("minecraft:andesite", "a standard stacked chord");
		key.put("minecraft:deepslate", "a stacked bus");
		key.put("minecraft:deepslate_tiles",
			"a cut chord's stacked head, with its tail across the staircase");
		key.put("minecraft:cobbled_deepslate", "a stacked simple tail");
		key.put("minecraft:smooth_basalt", "a double rail");
		key.put("minecraft:stripped_crimson_hyphae", "a lane standing outside its wall");
		key.put("minecraft:red_nether_bricks", "wire the signal never reaches");
		key.put("minecraft:waxed_copper_bulb", "a note that would sound at the wrong moment");
		key.put("minecraft:dragon_head", "a note with nothing to set it off");
		key.put("minecraft:sea_lantern", "a cell two shapes both wanted");
		return java.util.Collections.unmodifiableMap(key);
	}

	private static String shapeStone(String laidBy) {
		if (laidBy == null) {
			return "minecraft:stone";
		}
		if (laidBy.startsWith("rail:")) {
			return "minecraft:smooth_basalt";
		}
		// Only where the cut put the head on one side of the staircase and tail on the other. A
		// headed chord that fitted entirely before the staircase calls itself a stacked bus and is
		// coloured as one -- see addStackedSplitModule.
		if (laidBy.startsWith("cutHead")) {
			return "minecraft:deepslate_tiles";
		}
		// The two halves of a chord cut across a staircase. Both are runs of bus -- that is what makes
		// a chord the one shape here that can be cut at all -- so they wear what a bus wears.
		if (laidBy.startsWith("nearHalf") || laidBy.startsWith("farHalf")) {
			return "minecraft:tuff";
		}
		if (!laidBy.startsWith("chord:")) {
			return "minecraft:stone";
		}
		String style = laidBy.substring("chord:".length());
		// Asked before the family it belongs to, because it is one: a simple tail is a stacked bus
		// whose tail came to rest in a single column instead of a run of them, and the whole reason
		// for giving it a colour of its own is that from outside it looks like an ordinary stacked
		// bus that came out a column short.
		if (style.contains("simpleTail")) {
			return "minecraft:cobbled_deepslate";
		}
		if (style.startsWith("STACKED_BUS")) {
			return "minecraft:deepslate";
		}
		if (style.startsWith("STACKED_")) {
			return "minecraft:andesite";
		}
		// A small chord lays no stone of its own -- its notes hang off the repeater's own block, which
		// the lane laid -- so this arm is here for completeness rather than because it is reached.
		return style.startsWith("BUS") ? "minecraft:tuff" : "minecraft:stone";
	}

	/** Scratch: turn {@link #strandsNext} off, so a lane it changed can be diffed against itself. */
	static boolean LOOKAHEAD = true;

	/** Scratch: how often the lookahead walk was worth keeping, and how often it was thrown away. */
	static int LOOKAHEAD_WINS = 0;
	static int LOOKAHEAD_LOSES = 0;

	/**
	 * Whether a descent is walked out to its wall rather than built where the lane stopped.
	 *
	 * <p>The experiment behind the {@code pinned-descents} branch. A recessed descent is the one
	 * turn that stands in a column no other corridor's turn stands in, and ekran has traced wrong
	 * notes to one twice. Pinning it costs whatever the wire cannot pay for.</p>
	 */
	static boolean PIN_DESCENTS = true;

	/**
	 * Whether a chord can simply be laid across a flat turn, needing nothing done for it.
	 *
	 * <p>A bus is the sturdy shape: it wants nothing of its surroundings but somewhere to put the
	 * next note, and a corner takes it as readily as a straight lane does. So a chord that fits is
	 * not a problem to be solved -- the lane is not padded out to meet the turn, the chord is not cut
	 * in two, nothing is planned. It is built where it stands and the turn lays the rest of it.</p>
	 *
	 * <p>What "fits" means is not a single number, which is what this used to be. Slots come two to a
	 * block, less one at every corner the bus rides over, because there the run's own stone takes one
	 * of the pair. And the whole run has to stay inside the fifteen blocks a repeater reaches, or its
	 * tail never fires. So the limit depends on how many corners this chord will actually cross:</p>
	 *
	 * <pre>
	 *   stops before the bend        15 blocks, no corner   -- up to 30 notes
	 *   ends on the sideways run     15 blocks, one corner  -- up to 29
	 *   comes out into the next lane 15 blocks, two corners -- up to 28
	 * </pre>
	 *
	 * <p>Ekran's, and twenty-eight is only the worst of the three. Holding every chord to it padded
	 * out lanes in front of chords of twenty-nine and thirty that were never going to reach the
	 * second bend in the first place.</p>
	 *
	 * <p>The first line of that table wants one more thing said, and it is the thing that was
	 * missing. A chord that stops <em>in front of</em> a bend still pays a cell for it: a repeater
	 * may not stand on a corner, so dust takes the corner and the repeater goes one further, and
	 * that dust is on this chord's run. Where the bus rides <em>over</em> the bend the corner is a
	 * bus block and is counted already -- which is why riding a corner is, oddly, cheaper than
	 * stopping against one. Thirty notes is fifteen blocks, which is the whole of what a repeater
	 * reaches, so a chord of thirty has nothing left to pay a corner with and may only end a run
	 * where a repeater follows it directly. Ekran found it as two copper bulbs on a chord of thirty
	 * a block short of the bend, on the one-floor build of {@code ultra-limit-two-thirties}.</p>
	 *
	 * <p>Charged only where the bus actually reaches the bend. A chord ending well short of one is
	 * not the thing that has to carry the wire to it -- something else will stand a repeater in
	 * between -- and charging it anyway is the padding this function exists to stop.</p>
	 *
	 * <p>And charged at whichever bend the bus stops in front of, not only the first. A chord that
	 * rides the first corner and comes to rest one cell short of the second pays for the second in
	 * exactly the same way, and every run of sixteen left in the library after the first version of
	 * this was that case: fifteen cells of bus round one bend, then the cell that steps off it onto
	 * the lane. Riding <em>both</em> corners is the one shape that pays nothing, because the route
	 * comes out into the next lane and the repeater after the chord stands on the end of the bus.</p>
	 *
	 * @param columns how far the chord starts from the wall, which is where the first corner is
	 */
	private static boolean straddleFits(int notes, int columns, int slabStep) {
		for (int corners = 0; corners <= 2; corners++) {
			int cells = (notes + corners + 1) / 2;
			int crossed = cells <= columns ? 0 : cells <= columns + slabStep ? 1 : 2;
			// The next corner this bus does not ride over, which is the one it has to pay a cell
			// for. Riding both leaves none: the route comes out into the next lane and the repeater
			// after the chord stands directly on the end of the bus.
			int nextCorner = switch (crossed) {
				case 0 -> columns;
				case 1 -> columns + slabStep;
				default -> Integer.MAX_VALUE;
			};
			int run = cells + (cells + CORNER_AHEAD >= nextCorner ? CORNER_AHEAD : 0);
			if (run > DUST_RANGE) {
				continue;
			}
			if (crossed <= corners) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What the wire must still be worth for a lane to take a flat turn.
	 *
	 * <p>A corner and the cell after it, because that is as far as the signal has to get: the chord
	 * standing on the turn opens with a repeater, and a repeater hands out a fresh fifteen however
	 * dead the wire arriving at it was. A staircase is charged its whole length because it must be
	 * crossed in one unbroken run, and carrying that charge over to a turn the walk now simply walks
	 * through is what had lanes giving up with a usable corner two blocks in front of them.</p>
	 */
	private static final int FLAT_TURN_REACH = 2;

	private static Body layEventBody(PlacementPlan placements, Lane lane,
			List<EventNote> chord, boolean forceBus) {
		int time = chord.get(0).time();
		// Asked of the route rather than worked out from the heading, so that a chord standing on a
		// corner hangs its notes the way the wire leaves the cell and not the way it arrived.
		Lane body = lane.ahead(1);
		Direction laneStep = body.noteSide();
		BlockPos anchor = body.pos().above();
		// The shape the walk settled on, not the one the size implies. A chord of three that could
		// not have its two side slots is handed here as a bus, and branching on the size alone built
		// it in the shape that had just been rejected -- which is the note that lands in a neighbour.
		// And not the one the physics implies either: a chord of three doors is small enough for this
		// shape and cannot hold it, because the anchor has to pass power on and a door does not.
		// {@link #fitsSmallModule} is the same test {@link #chooseStyle} priced the chord with.
		if (!forceBus && fitsSmallModule(chord)) {
			List<EventNote> ordered = smallOrder(chord);
			if (needsLentMiddle(ordered)) {
				// Stone in the middle and the sound beside it. The cell the repeater faces has to
				// carry the signal onward, and this one sound cannot, so it borrows a block that can
				// and is sounded the way a bus sounds its notes -- from the side, off powered stone.
				placements.powered(anchor, "minecraft:stone", time);
				placeNote(placements, anchor.relative(laneStep.getOpposite()), ordered.getFirst());
				return new Body(lane.ahead(2), 0);
			}
			placeNote(placements, anchor, ordered.get(0));
			// The repeater drives the anchor directly, and a note block is a full block, so the
			// anchor passes that power on to whatever is beside it -- including the next repeater.
			placements.powered(anchor, time);
			if (ordered.size() >= 2) {
				placeNote(placements, anchor.relative(laneStep), ordered.get(1));
			}
			if (ordered.size() >= 3) {
				placeNote(placements, anchor.relative(laneStep.getOpposite()), ordered.get(2));
			}
			return new Body(lane.ahead(2), 0);
		}
		int cells = layBus(placements, lane.ahead(1).above(), chord, time);
		return new Body(lane.ahead(1 + cells), cells);
	}

	/**
	 * Where a module left the route, and how many blocks of bus it laid getting there.
	 *
	 * <p>The count matters because a bus is the one module whose length is not settled in advance.
	 * It skips a slot the ground will not have and carries on a block further, so a chord of
	 * twenty-six can end up seven blocks of wire poorer than the fourteen its note count implies --
	 * and the wire it spends is charged against the fifteen a repeater hands out. Charging the
	 * nominal length instead is a run that overshoots its next repeater and nothing notices.</p>
	 */
	private record Body(Lane lane, int busCells) {
	}

	/**
	 * Whether a chord of three or fewer can be built where it stands.
	 *
	 * <p>The shape is rigid: the anchor is the block the repeater faces, and the second and third
	 * notes hang off the two sides of it, at exactly those two places and nowhere else. So unlike a
	 * bus it cannot be asked to grow past an obstruction, and asking first is the whole of what lets
	 * the walk hand the chord to a bus instead of building it into something.</p>
	 */
	private static boolean smallChordFits(PlacementPlan placements, Lane lane, int notes, int time) {
		Lane body = lane.ahead(1);
		BlockPos anchor = body.pos().above();
		return slotIsQuiet(placements, lane, anchor, time)
			&& (notes < 2
				|| slotIsQuiet(placements, lane, anchor.relative(body.noteSide()), time))
			&& (notes < 3
				|| slotIsQuiet(placements, lane, anchor.relative(body.noteSide().getOpposite()),
					time));
	}

	/**
	 * Whether a note may hang here without being sounded by somebody else's tick.
	 *
	 * <p>Free was never enough. An empty slot can still sit against a block that goes live at a tick
	 * this chord was not written for, and a note block fires on any rising edge that reaches it --
	 * so it sounds twice, once where it belongs and once with the chord next door. The bus has asked
	 * this since it started opening into the air gap beside another lane; the rigid shape never did,
	 * and it is the shape that cannot grow out of the way, so it is the shape that was hit.</p>
	 *
	 * <p>Ekran found it at 3 65 6 of jojo-il-vento-d-oro at thirty-six wide: a chord of three hung a
	 * note against the last bus block of the chord a tick earlier. Nothing looked wrong -- the blocks
	 * are all correct and the bulb test passes, because the extra sounding takes nothing away.</p>
	 */
	private static boolean slotIsQuiet(PlacementPlan placements, Lane lane, BlockPos slot, int time) {
		return slotIsFree(placements, lane, slot) && !soundedByAnother(placements, slot, time);
	}

	/**
	 * Whether a note could hang here, counting the module's own repeater as something in the way.
	 *
	 * <p>Asked before the module is built, so the two blocks it is about to lay are not there to be
	 * found yet. That matters at exactly one place and it is the place this is for: a chord whose
	 * body lands on a corner has its side slots running back down the leg the wire came in on, and
	 * the first cell of that leg is the repeater this very chord is about to stand on. The check
	 * said the ground was clear, the module laid its repeater, and then hung a note through it.</p>
	 *
	 * <p>Which is the same mistake in miniature as asking whether a turn was free while the things
	 * that fill it had not been placed: a checker answers about the world as it is, and what is
	 * wanted is an answer about the world this module is in the middle of making.</p>
	 */
	private static boolean slotIsFree(PlacementPlan placements, Lane lane, BlockPos slot) {
		return !slot.equals(lane.pos()) && !slot.equals(lane.pos().above())
			&& placements.freeForNote(slot);
	}

	/**
	 * One line per chord placed: what the planner chose, what was built, and what changed it.
	 *
	 * <p>The reason is the part worth having. A lane past its wall is nearly always a chord measured
	 * as one shape and built as another, and seven separate rules in {@link #addChordModule} can do
	 * that -- so printing the shapes alone leaves the same reading of all seven that this is meant
	 * to save.</p>
	 */
	private static void trace(EventGroup event, Lane lane, ChordStyle planned, ChordStyle built,
			String gaveUp) {
		if (!TRACE) {
			return;
		}
		System.out.println("CHORD t=" + event.time() + " at " + lane.pos().getX() + ","
			+ lane.pos().getY() + "," + lane.pos().getZ() + " travel=" + lane.travel()
			+ " side=" + lane.noteSide() + " crowded=" + lane.crowded()
			+ " bends=" + lane.bends() + " style=" + event.style() + "->" + built
			+ " notes=" + event.notes().size()
			+ (gaveUp == null ? "" : " gaveUp=" + gaveUp));
	}

	/**
	 * The shape a chord is to be built in, and everything the choice of it costs.
	 *
	 * <p>One record so that the decision can be made once and then read, rather than made again by
	 * whoever needs to know. {@code behindShift} and {@code nudge} are columns of pad the shape has
	 * asked for, {@code moved} is a low note lifted out of a slot something else owns, and
	 * {@code gaveUp} names the rule that took the shape away, for the trace.</p>
	 */
	private record Shape(ChordStyle style, boolean behindShift, boolean nudge, Relocation moved,
			String gaveUp) {
	}

	/**
	 * What shape this chord takes here, decided in one place and by asking.
	 *
	 * <p>Pulled out of {@link #addChordModule} whole, so that the walk can decide a chord's shape
	 * before it measures where the chord will land and then build exactly the shape it measured. While
	 * this lived inside the builder there were three answers to the question -- {@link #chooseStyle}
	 * guessing at grouping time, {@link #landingOf} predicting, and this deciding -- and a lane
	 * measured for one shape and built as another lands outside its wall.</p>
	 *
	 * <p>It writes to the census as it goes, so it is called once per chord and the answer is passed
	 * on. Calling it twice counts every decision twice.</p>
	 */
	private static Shape shapeFor(PlacementPlan placements, Lane lane, EventGroup event,
			int slackColumns, boolean roomBehind, boolean inTurn, int roomAhead, int signal,
			Layout layout) {
		ChordStyle style = event.style();
		// Which rule took the shape the planner chose, for {@link #TRACE}. A lane past its wall is
		// usually a chord that was measured as one shape and built as another, and there are seven
		// places that can happen -- naming the one that fired beats reading all seven.
		String gaveUp = null;
		boolean behindShift = false;
		if (style.reachesBack() && !roomBehind
				&& NUDGE_WHEN_BEHIND_BUSY && losesTheHeadWithoutTheBackPair(style, event.notes())) {
			// The pair of low slots between two modules a repeater apart belongs to whichever went
			// down first. A column of pad stands this one three columns off instead of two, and then
			// the pair is nobody's but its own -- the same thing any wait already does for it, which
			// is why the rule this replaces only fired with no wait at all.
			//
			// Only where the shape would otherwise lose its head altogether, because that is the only
			// place the column is cheap. Giving up the back pair costs a head of seven a head of five,
			// which is one cell of bus -- the same column the shift costs, so neither wins. Where the
			// short head cannot be made at all the chord falls to a plain bus instead, and for a chord
			// of fifteen that is nine columns against the eight a shifted head takes.
			//
			// ekran found it by pasting the chord in the air: the shape was there all along.
			behindShift = true;
			gaveUp = "behindBusyShifted";
			placements.padded(style.busHeaded() ? "planShiftForBehindStackedBus"
				: "planShiftForBehind");
		} else if (style.reachesBack() && !roomBehind) {
			gaveUp = "behindBusy";
			placements.padded(style.busHeaded() ? "planBusForBehindStackedBus"
				: "planBusForBehind");
			// A head with a bus behind it keeps the head and drops to a head of five; only the rigid
			// shape has to give up entirely. The short head is never longer than the bus this used
			// to fall back to and is a cell shorter on an odd chord, so this can only shorten what
			// the walk builds -- and shortening is the safe direction to surprise the plan in.
			// {@link #landingOf} makes the same substitution, so most of the time it is not a
			// surprise at all.
			// The rigid shape falls to a head of five with a bus behind it too, not all the way to a
			// plain bus. ekran, reading one in game: there was no stacked chord anywhere near it to
			// justify a plain bus, and a front-only head fits. A chord of seven is a repeater and
			// four cells as a bus -- five columns -- against two, a handover and one cell as a head
			// of five with a tail of two, which is four. A chord of six is four either way, so this
			// never loses; and where the chord cannot make a head of five at all, addStackedShape
			// still drops it to a bus and counts it.
			style = HEAD_KEEPS_ONE_BACK_FLANK && style.busHeaded()
					&& stackedBusSplit(event.notes(), 1) != null
				? ChordStyle.STACKED_BUS_HALF
				: FRONT_HEAD_WHEN_BEHIND_BUSY || (FRONT_ONLY_HEADS && style.busHeaded())
					? ChordStyle.STACKED_BUS_FRONT : ChordStyle.BUS;
		}
		// A stacked module may not sit perpendicular to another one, and the two modules either side
		// of a corner are perpendicular by construction. So anywhere in a turn the stacked shape is
		// given up and the chord is built as a bus, which minds nothing about which way its
		// neighbours lie. The whole turn and not merely the corners, for now: which cells of a
		// sideways run are far enough from both bends to be safe is worth working out, and worth
		// working out after there is something to compare it against.
		if (style.stacked() && inTurn) {
			gaveUp = "inTurn";
			behindShift = false;
			placements.padded(style.busHeaded() ? "planBusForTurnStackedBus"
				: "planBusForTurn");
			style = ChordStyle.BUS;
		}
		// And a chord of three or fewer drops too, if the ground will not take the notes where that
		// shape insists on putting them -- which at a corner it will not, because one of the two side
		// slots is the cell the wire came in from. A bus can put them anywhere down its length and
		// grow until it has, so it is what every shape falls back to rather than a shape that fails.
		//
		// The stacked shape first and the bus only after it. Both leave the contested cell open, but
		// the bus pays a column of lane to do it and the stacked module does not -- it is the denser
		// shape, and a chord of three leaves one of its hangers empty rather than growing. Ekran built
		// both by hand before choosing: bus-converting works and costs space, stacking works and does
		// not. In a turn the stacked shape is unavailable whatever its size, and a chord with fewer
		// than two notes that will pass power sideways has no relays to stand the module on, so the
		// bus is still what is left when neither holds.
		if (style == ChordStyle.SMALL && lane.crowded()
				&& !smallChordFits(placements, lane, event.notes().size(), event.time())) {
			// Counted three ways, because ekran reads small chords coming out bus-shaped in game "with
			// no explanation, especially on flat turns" and this is the only line that can do it. The
			// one worth knowing is the middle: a chord the stacked shape would have taken, refused it
			// for the turn rule alone and given a column of lane to a bus instead.
			//
			// A chord with an effect in it is not stackable at all, whatever its slots say. Every
			// stacked shape leans on its own notes to relay power outward and an effect block either
			// sounds or conducts, mostly not both -- which is why {@link #chooseStyle} sends effects
			// to the bus before it ever gets here. A small chord is the one shape that reaches this
			// line ahead of that test, so the same question is asked again.
			boolean stackable = !hasEffect(event.notes())
				&& ultraSlots(event.notes(), false) != null;
			placements.padded(stackable
				? inTurn ? "smallBusForTheTurnRule" : "smallBecameStacked"
				: "smallBusNoSlots");
			style = (SMALL_MAY_STACK_IN_A_TURN || !inTurn) && stackable
				? ChordStyle.STACKED_FRONT
				: ChordStyle.BUS;
		}
		// Nudged only when the lane behind actually disagrees, and given up on when both cells
		// disagree.
		//
		// A stacked module's outer column reads live, note, live, note along the lane, so two lanes
		// close enough to touch have to agree which cell is live or one sounds the other's notes at
		// the wrong tick. Asked of both cells this module could stand in, because one may be no
		// better than the other: two stacked modules in the lane behind at odd spacing put *both*
		// parities in that lane, and then a nudge only swaps which of them this chord disagrees with.
		// Ekran found exactly that in world, on two copper bulbs left lit.
		//
		// Boxed in like that, the chord gives up the stacked shape and is built as a bus, which has no
		// alternating column to disagree with at all. That costs no pad, where nudging into a clash
		// costs a column and still sounds wrong -- and it is cheaper than the other way round, which
		// would be to forbid odd spacing everywhere and convert chords that nothing was ever going to
		// sit above.
		Lane start = lane;
		boolean nudge = false;
		Relocation moved = null;
		if (style.stacked()) {
			UltraSlots slots = slotsFor(style, event.notes());
			RelocationRoom room = relocationRoom(style, event.notes());
			// A module already stood a column off the lane behind asks about the column it will
			// actually stand in, not the one it would have stood in.
			//
			// And so does one standing on a corner. addStackedShape walks past any corner before it
			// builds -- a repeater may not stand on one -- so a module asking here is asking about a
			// column it is about to be moved out of, and moved by exactly the one column parity is
			// about. The walk is mirrored rather than shared because pastAnyCorner lays dust as it
			// goes and this is a question, not a placement; it is the same loop over the same
			// cornerAt, so the two cannot land in different places.
			Lane onTheGround = start;
			if (PARITY_ASKS_PAST_THE_CORNER) {
				while (onTheGround.cornerAt(0)) {
					onTheGround = onTheGround.ahead(1);
				}
			}
			Lane asking = behindShift ? onTheGround.ahead(1) : onTheGround;
			int verdict = parityVerdict(placements, asking, event.time(), slots, room);
			if (verdict == 2) {
				moved = relocate(placements, asking, event.time(), style, event.notes(), slots, room);
				placements.padded("planRelocateTo" + moved.where());
			}
			// A shift and a relocation together, which is the shape that used to go to a bus. The note
			// is lifted out at the column the pad puts the module in, not at the one it is standing in
			// -- a different neighbour, and so a different slot in contention. That is the whole reason
			// the second ask is worth making.
			if (verdict == 3) {
				moved = relocate(placements, asking.ahead(1), event.time(), style, event.notes(), slots,
					room);
				placements.padded("planShiftAndRelocateTo" + moved.where());
			}
			boolean clashesHere = verdict == 1 || verdict == 3 || verdict < 0;
			if (behindShift && clashesHere) {
				// One column is what this shape can express, and it has spent it. Asked for a second
				// it gives the head up after all -- straight to a bus, because the short head it
				// would otherwise have fallen to was never available; that is why it shifted.
				gaveUp = "behindBusyAndParity";
				placements.padded("planBusForBehindAndParity");
				behindShift = false;
				moved = null;
				style = ChordStyle.BUS;
			} else if (verdict < 0) {
				gaveUp = "parityBothCells";
				placements.padded(slackColumns > 0 ? "planParityGaveUpSlack"
					: "planParityGaveUpTight");
				style = ChordStyle.BUS;
			} else if (clashesHere && PARITY_PREFERS_BUS && style.busHeaded()) {
				// A stacked-bus that has to shift is worth about what the plain bus it came from is
				// worth. Nudged, a chord of twenty is a pad, a head, a transition and seven cells --
				// eleven columns, two of them holding no music. As a plain bus it is a repeater and
				// ten cells, also eleven columns, and only the repeater holds no music. Same length,
				// one less column of wire, so the shape is given up rather than shifted.
				//
				// Only the stacked-bus. A plain stacked module is two columns and three when nudged,
				// where the same chord as a bus is five, so shifting it is much the better bargain.
				gaveUp = "parityPreferredBus";
				placements.padded("planParityPreferredBus");
				style = ChordStyle.BUS;
			} else {
				// One column covers both errands. A module stood off the lane behind is already a
				// column along, which is the same column a parity shift would have asked for -- and
				// the verdict above was taken at that column, so it is the shifted position that came
				// back clean.
				nudge = behindShift || clashesHere;
				if (clashesHere) {
					placements.padded(slackColumns > 0 ? "planParityHadSlack" : "planParityTight");
				}
			}
		}
		// A stacked module near the wall is built as a bus, whether or not it wanted a nudge.
		//
		// It is two cells, three if nudged, and what the lane was measured for is two -- so one nudged
		// with only two columns left puts whatever comes next, a staircase or a turn, a block past the
		// wall. Ekran found it as a single glass step outside the footprint.
		//
		// No condition on how much room the bus wants, because it does not want any: a stacked chord
		// is seven notes at the most, so as a bus it is four blocks at the most, always small enough
		// to lie across a staircase or a turn -- which the rigid shape cannot do. The one that has to
		// fit before the wall is the stacked module.
		//
		// And asked of the room alone, not of whether a nudge is wanted, so that {@link #landingOf}
		// can ask exactly the same question. When the walk converted here on a clash the planner could
		// not see, the two disagreed about how long the chord was, and a lane measured for two cells
		// that got five came to rest three past its wall.
		// Less the column the lane hands over into, which is the fifth site of the same rule and the
		// one that made the readback test refuse to build. The guard below exists so a nudge cannot
		// push the staircase past the wall -- but it measures the room a lane had before that column
		// was reserved, so with the reserve on it let a module be nudged into the descent'''s own
		// steps. ekran'''s sample song at 24 wide over three floors, read off DEBUG_PASTE: a
		// STACKED_FRONT+nudge holding off descent4 for a note block and its oak planks.
		int roomToWall = roomAhead - handoverReserve(layout);
		int stackedRoom = STACKED_CELLS + 1;
		if (style.busHeaded()) {
			StackedBusSplit measured = splitFor(style, event.notes());
			stackedRoom = measured == null ? STACKED_CELLS + 1
				: STACKED_CELLS + STACKED_BUS_TRANSITION + stackedBusTailColumns(measured.tail()) + 1;
		}
		// A fallback has to be shorter than the shape it replaces, or it is not a fallback.
		//
		// A stacked-bus of twenty-four is a head, a transition and nine cells: twelve columns. It is
		// refused here at twelve, because a shift would want a thirteenth. The plain bus it drops to
		// is a repeater and twelve cells, which is thirteen columns for certain -- so a chord one
		// column short of its head is handed a shape a column longer than the head, and lands past
		// the wall it was being kept inside.
		//
		// And then it cannot cut its way out either, which is the expensive half. A headed cut of
		// twenty-four is a transition, nine cells and the staircase -- fourteen blocks of wire, well
		// inside the fifteen a repeater reaches. Unheaded it is twelve cells and the staircase,
		// which is sixteen, so `couldSplit` says no. The lane runs on, and every chord of that size
		// after it hands on 15 - 12 = 3 blocks of wire where the staircase wants four, so it cannot
		// turn either. That is where ekran's long breaches start, and it starts here.
		int busColumns = 1 + (event.notes().size() + 1) / 2;
		boolean busIsLonger = KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER && style.busHeaded()
			&& busColumns > stackedRoom - 1;
		if (style.stacked() && roomToWall < stackedRoom && !busIsLonger) {
			gaveUp = "roomAhead" + roomToWall + "<" + stackedRoom;
			placements.padded("planBusForRoom");
			style = ChordStyle.BUS;
			nudge = false;
		} else if (busIsLonger && roomToWall < stackedRoom) {
			placements.padded("planKeptHeadBusWasLonger");
		}
		// And a nudge has to be reachable. Moving the repeater a cell forward puts it a cell further
		// down the wire, and the wire may not have a cell left to give -- after a chord has been cut
		// down a staircase there is often nothing spare at all. Then the module is built as a bus with
		// its repeater left where it stands, which is the one place the signal is known to reach.
		//
		// Ekran found it on Big Shot at 44 wide: a chord of eighteen split down a staircase powered
		// perfectly, and the stacked chord after it was nudged one past the end of the wire.
		//
		// Asked of the wire and not of the budget. `signal` is the tip carried down the lane by
		// arithmetic, and it only ever refused a nudge at nought -- "is there any wire left", where
		// the question is "does the wire still reach after I add a cell". The plan is holding the run
		// itself, so it can be asked: the nudge lays one cell of dust where the repeater would have
		// stood, so it is affordable exactly when the run plus that cell is within a repeater's
		// reach. The bus needs nothing, because its repeater stands where the wire already is.
		boolean outOfWire = MEASURED_NUDGE_REACH
			&& placements.runSinceRepeater() + 1 > DUST_RANGE;
		if (TRACE_NUDGE && nudge) {
			System.out.println("NUDGE at " + lane.pos().getX() + " " + lane.pos().getY() + " "
				+ lane.pos().getZ() + " run=" + placements.runSinceRepeater()
				+ " signal=" + signal + " style=" + style + " notes=" + event.notes().size()
				+ " outOfWire=" + outOfWire);
		}
		if (nudge && (signal < NUDGE_REACH || outOfWire)) {
			gaveUp = outOfWire ? "nudgePastTheWire" : "nudgeOutOfReach";
			placements.padded(outOfWire ? "planBusForRunMeasured" : "planBusForSignal");
			style = ChordStyle.BUS;
			nudge = false;
		}
		return new Shape(style, behindShift, nudge, moved, gaveUp);
	}

	private static Placed addChordModule(PlacementPlan placements, Lane lane, int triggerDelay,
			EventGroup event, int slackColumns, boolean roomBehind, boolean inTurn, int roomAhead,
			int signal, Layout layout) {
		return buildShaped(placements, lane, triggerDelay, event,
			shapeFor(placements, lane, event, slackColumns, roomBehind, inTurn, roomAhead, signal,
				layout), layout);
	}

	/**
	 * Lays down the shape that was decided, and the one thing the decision cannot settle.
	 *
	 * <p>Every rule above this tries to work out whether the ground is free. This is what happens when
	 * one of them is wrong: the shape is built inside a trial, and a collision rolls it back and lays a
	 * bus instead. Asking is cheaper than being right, and it is the only answer that cannot be
	 * out of date.</p>
	 */
	private static Placed buildShaped(PlacementPlan placements, Lane lane, int triggerDelay,
			EventGroup event, Shape shape, Layout layout) {
		// Cleared here and set by the one shape that ends soft, so the flag always describes the module
		// that just went down rather than some earlier one.
		placements.rollSoftTip();
		ChordStyle style = shape.style();
		boolean nudge = shape.nudge();
		Relocation moved = shape.moved();
		String gaveUp = shape.gaveUp();
		Lane start = lane;
		if (!style.stacked()) {
			trace(event, lane, style, style, gaveUp);
			return layBus(placements, lane, triggerDelay, event, style,
				style == ChordStyle.BUS, layout);
		}
		// A stacked shape that will not fit becomes a bus rather than ending the build. Every rule
		// above tries to predict whether the ground is free, and this is what happens when one of
		// them is wrong -- which used to mean a song that would not paste at all, on the first
		// collision anywhere in it. A bus grows until it has somewhere for every note, so it is
		// always available to fall back to.
		//
		// Counted as planBusForCollision, because a fallback nobody can see is a rule nobody fixes.
		// Move it, then check. A bus is not always half its notes: where the ground will not take one
		// layBus carries the run on a block further, and then the note the head gave away costs a
		// cell after all -- the module comes out a column longer than the plan paid for and the next
		// repeater stands sixteen dust away. So the move is built, measured against the same module
		// without it, and kept only if the bus really did have the cell spare.
		//
		// Where it did not, the chord takes the shift it would have taken before, and hands back
		// nudged so that the lane's plan learns about the column. That is what Placed.nudged is for.
		//
		// Only the ones that go to the bus. A note moved to the centre touches no cell of run at all:
		// the centre block was going to be placed either way, as stone if nothing sounded there, and
		// the slot it came from is simply left empty. Nothing about the length can have changed, so
		// there is nothing to measure -- and two trial builds a chord is not free.
		if (moved != null && "Tail".equals(moved.where())) {
			int spent = trialCells(placements, lane, triggerDelay, event, style, start, gaveUp, moved);
			int plain = trialCells(placements, lane, triggerDelay, event, style, start, gaveUp, null);
			if (spent < 0 || plain < 0 || spent > plain) {
				placements.padded("planRelocationWouldGrowTheBus");
				moved = null;
				nudge = true;
			}
		}
		placements.beginTrial();
		try {
			Placed placed = addStackedShape(placements, lane, triggerDelay, event, style, nudge,
				start, gaveUp, moved);
			// And then the shape is asked where it landed, which is a different question from whether
			// it fitted. A stacked module hangs notes to the side of the path, and to the side of the
			// path is a safe place to hang them only while the path is straight. Through a bend the
			// route comes back along the other arm, so a cell that was beside the module when it was
			// placed is a cell the lane walks down afterwards -- and the wire arrives at a note block
			// with nowhere to go. That is not a collision the module can see: it is a collision with a
			// corridor that does not exist yet.
			//
			// Which is why {@code inTurn} bans stacked shapes near corners at all, and why relaxing
			// that ban by distance was not enough: three columns from a corner is far enough for the
			// shape and not far enough for what the route does next. Asked here instead of guessed at,
			// against the cells the route will actually occupy, built and then read back.
			if (layout.v2() && STACKED_KEEPS_OFF_THE_ROUTE
					&& placements.trialTouches(routeAhead(placed.lane(), ROUTE_LOOKS_AHEAD))) {
				placements.rollbackTrial();
				placements.padded("planBusForStandingOnTheRoute");
				trace(event, lane, style, ChordStyle.BUS, "onTheRoute");
				return layBus(placements, lane, triggerDelay, event, ChordStyle.BUS, true, layout);
			}
			// And the third thing the decision cannot settle, asked the same way. onTheFreeSlots tries
			// to rearrange the low notes onto quiet cells and, when no arrangement works, hands the
			// slots back exactly as they were -- so the note is hung in the noisy cell anyway. That is
			// 22 of the library's 31 wrong notes, always the same pair: a plain bus's live stone with
			// the module built after it hanging a note against it. A bus can grow out of the way; the
			// rigid shape cannot, which is why it is the shape that gets hit.
			if (layout.v2() && STACKED_GIVES_UP_A_NOISY_SLOT
					&& placements.trialHangsANoisyNote(event.time())) {
				placements.rollbackTrial();
				placements.padded("planBusForANoisySlot");
				trace(event, lane, style, ChordStyle.BUS, "noisySlot");
				return layBus(placements, lane, triggerDelay, event, ChordStyle.BUS, true, layout);
			}
			placements.commitTrial();
			return placed;
		} catch (IllegalArgumentException collided) {
			placements.rollbackTrial();
			placements.padded("planBusForCollision");
			trace(event, lane, style, ChordStyle.BUS, "collided");
			// Through the same move as every other bus, and this is the site that mattered: the shape
			// collided, dropped to a bus, and the bus landed in the same occupied ground with nothing
			// left to try. That is what ended the build rather than the first collision.
			return layBus(placements, lane, triggerDelay, event, ChordStyle.BUS, true, layout);
		}
	}

	/**
	 * A bus, moved along a column at a time until the ground will take it.
	 *
	 * <p>A shape that is already the fallback has nothing to fall back to. Every stacked shape is
	 * built in a trial and drops to a bus when it collides; a bus that collides ends the build, and
	 * the song does not paste at all. That is v2's last refusal -- Guardian twenty wide over four
	 * floors, where a chord of three laid as a bus wants the column a stacked module standing across
	 * the bend has already taken, on a build whose every other number reads clean, breaches
	 * included.</p>
	 *
	 * <p>What took the cell is a shape of finite width standing in front of this one, not a wall, so
	 * the column after it is free. The chord is offered the next column, and the one after, for a cell
	 * of dust each -- the parity pad an ordinary chord uses, which spends no ticks and so cannot move
	 * a note off its beat. The move is handed back as a nudge, because a chord that quietly grew by
	 * two columns is a lane two columns past its wall.</p>
	 *
	 * <p>Each attempt is the pads <em>and</em> the module in one trial from where the chord actually
	 * stands. Wrapping only the module is the version that does not work: the cell the module was
	 * refused is often the cell the next pad wants, and that throw goes straight past the catch that
	 * was meant to handle it.</p>
	 *
	 * <p>v2 only, and asked of the layout rather than of a static, because this is shared with the
	 * first layout and a chord that moves is a chord that lands somewhere else.</p>
	 */
	private static Placed layBus(PlacementPlan placements, Lane lane, int triggerDelay,
			EventGroup event, ChordStyle style, boolean forceBus, Layout layout) {
		if (!layout.v2() || !BUS_MOVES_OFF_A_COLLISION) {
			Body body = addSpatialEventModule(placements, lane, triggerDelay, event.notes(), forceBus);
			return new Placed(body.lane(), style, body.busCells(), false);
		}
		for (int shifted = 0; ; shifted++) {
			placements.beginTrial();
			try {
				Lane at = lane;
				for (int cell = 0; cell < shifted; cell++) {
					placements.placing("busMove");
					if (cell != 0 || !(placements.softTip() || placements.softBehind())
							|| !undoTheSoftTailFor(placements, at.pos())) {
						addParityPad(placements, at.pos());
					}
					at = at.ahead(1);
				}
				Body body = addSpatialEventModule(placements, at, triggerDelay, event.notes(), forceBus);
				placements.commitTrial();
				for (int cell = 0; cell < shifted; cell++) {
					placements.padded("parity");
				}
				if (shifted > 0) {
					placements.padded("planBusMoved" + shifted);
				}
				return new Placed(body.lane(), style, body.busCells(), shifted > 0);
			} catch (IllegalArgumentException collided) {
				placements.rollbackTrial();
				if (TRACE_BUS_MOVE) {
					System.out.println("BUSMOVE shifted=" + shifted + " from "
						+ lane.pos().getX() + " " + lane.pos().getY() + " " + lane.pos().getZ()
						+ " travel=" + lane.travel() + " notes=" + event.notes().size()
						+ " : " + collided.getMessage());
				}
				if (shifted >= BUS_MOVES_AT_MOST) {
					placements.padded("planBusStuckAfter" + shifted);
					throw collided;
				}
			}
		}
	}

	/**
	 * How many cells of bus this module would actually spend, built and then undone.
	 *
	 * <p>-1 where it will not build at all, which the caller reads the same way as too many: the
	 * shed is not worth taking if the thing it produces cannot be placed.</p>
	 */
	private static int trialCells(PlacementPlan placements, Lane lane, int triggerDelay,
			EventGroup event, ChordStyle style, Lane start, String gaveUp, Relocation moved) {
		placements.beginTrial();
		try {
			return addStackedShape(placements, lane, triggerDelay, event, style, false, start,
				gaveUp, moved).busCells();
		} catch (IllegalArgumentException collided) {
			return -1;
		} finally {
			placements.rollbackTrial();
		}
	}

	/** The stacked half of {@link #addChordModule}, apart so that a collision in it can be undone. */
	private static Placed addStackedShape(PlacementPlan placements, Lane lane, int triggerDelay,
			EventGroup event, ChordStyle style, boolean nudge, Lane start, String gaveUp,
			Relocation moved) {
		if (style.busHeaded()) {
			// The moved split when the lane behind wanted one slot left empty, and the chord's own
			// otherwise. Same notes either way: what changes is which slot holds which of them.
			StackedBusSplit split = moved != null ? moved.split() : splitFor(style, event.notes());
			if (split == null) {
				// Counted, where it used to be the one downgrade in this method that said nothing.
				// The planner measured a head here; the chord could not make one, and the bus it
				// gets instead is a cell longer. A silent disagreement is the expensive kind.
				gaveUp = "noHeadPossible";
				placements.padded("planBusForNoHead");
				trace(event, lane, style, ChordStyle.BUS, gaveUp);
				// Plainly, and deliberately: this runs inside the caller's trial, so a collision here
				// is rolled back and re-laid through {@link #layBus} by the catch that owns it. Moving
				// a chord inside a trial that is about to be undone moves nothing.
				Body body = addSpatialEventModule(placements, lane, triggerDelay, event.notes(), true);
				return new Placed(body.lane(), ChordStyle.BUS, body.busCells(), false);
			}
			Lane askedAt = start;
			start = pastAnyCorner(placements, start);
			// The parity verdict was taken before this walk, at the column the module was standing in
			// rather than the one it ends up in. Corner padding then moves it, and a shift of one
			// column is exactly what parity is about -- so the answer it was given is the answer for
			// somewhere else.
			if (!start.pos().equals(askedAt.pos())) {
				placements.padded("parityAskedBeforeTheCorner");
			}
			if (nudge) {
				triggerDelay = parityPadOrSplitRepeater(placements, start, triggerDelay);
				start = start.ahead(1);
			}
			trace(event, lane, style, style, nudge ? "nudged" : gaveUp);
			placements.placing("chord:" + style + (nudge ? "+nudge" : "")
				+ " head" + split.head().size() + "/tail" + split.tail().size()
				+ (split.slots().backFlanks() == 0 ? " frontOnly" : " reachesBack"));
			Body body = addStackedBusModule(placements, start, triggerDelay, event.time(),
				onTheFreeSlots(placements, start.pos(), start.travel(), start.noteSide(),
					event.time(), split.slots()),
				split.tail());
			return new Placed(body.lane(), style, body.busCells(), nudge);
		}
		// The delay no longer walks off the corner for us -- the two-swap turn wants it -- so the one
		// shape that cannot use it walks off it here.
		Lane askedAt = start;
		start = pastAnyCorner(placements, start);
		if (!start.pos().equals(askedAt.pos())) {
			placements.padded("parityAskedBeforeTheCorner");
		}
		if (nudge) {
			triggerDelay = parityPadOrSplitRepeater(placements, start, triggerDelay);
			start = start.ahead(1);
		}
		trace(event, lane, style, style, nudge ? "nudged" : gaveUp);
		placements.placing("chord:" + style + (nudge ? "+nudge" : "")
			+ (moved == null ? "" : " moved" + moved.where()));
		// The rigid shape has no bus to hand a note to, so until the centre became a target it had
		// no third option at all: it shifted or it fell to a bus.
		return new Placed(addStackedEventModule(placements, start, triggerDelay, event.time(),
			onTheFreeSlots(placements, start.pos(), start.travel(), start.noteSide(), event.time(),
				moved != null ? moved.slots()
					: ultraSlots(event.notes(), style == ChordStyle.STACKED_FULL))),
			style, 0, nudge);
	}

	/**
	 * Whether a stacked module built here would meet the lane behind it live against note.
	 *
	 * <p>A stacked module's outer column reads live, note, live, note along the lane: the two blocks
	 * the dust cross relays through are live, and the low notes sit in front of and behind them. Two
	 * lanes close enough to touch therefore have to agree which of those falls where, or one lane's
	 * live block sits against the other's note and sounds it at the wrong tick. That much is real --
	 * ekran's copper bulbs caught it the one time this was taken out altogether.</p>
	 *
	 * <p>What is <em>not</em> needed is the global convention it used to be enforced with, where
	 * every module centre was pushed onto an even coordinate whether anything was beside it or not.
	 * Lanes are built in order, so the lane behind is already down and can simply be asked. Only the
	 * lane behind, too: the lane in front will ask the same question of this one when its turn
	 * comes. So a song whose chords already line up pays nothing, which is most of them.</p>
	 *
	 * <p>Two cells out and not one, because one cell out is this module's own outer column. What
	 * sits beyond that is the neighbour.</p>
	 */
	private static boolean stackedClashes(PlacementPlan placements, Lane lane, int time,
			UltraSlots slots) {
		BlockPos cross = lane.ahead(1).pos();
		Direction travel = lane.travel();
		List<Direction> outward = List.of(lane.noteSide(), lane.noteSide().getOpposite());
		for (int side = 0; side < outward.size(); side++) {
			Direction out = outward.get(side);
			BlockPos beyond = cross.relative(out, 2);
			// The block this module would relay through, against a note of the lane behind. Always
			// asked: both relays are built whatever the chord holds, and both are live.
			if (placements.noteAt(beyond, time)) {
				return true;
			}
			// And this module's own low notes, against a live block of the lane behind -- but only the
			// ones it is actually going to hang. ekran, from the collision that started this: the two
			// modules in it use four hangers each and would not have powered one another, so the nudge
			// bought nothing and cost the column the staircase needed.
			//
			// Which flank falls where is the whole of it. Touching lanes run opposite ways, so this
			// module's back flank stands against the neighbour's front and vice versa, and the slots
			// fill front-first. A chord of five therefore has no back flank on either side, and two of
			// them a column apart never meet at all.
			boolean hasFront = !FLANK_AWARE_PARITY || slots == null || slots.front(side) != null;
			boolean hasBack = !FLANK_AWARE_PARITY || slots == null || slots.back(side) != null;
			// And the same two cells the other way round: a note this module is about to hang, against a
			// block of the lane behind that goes live at somebody else's tick. Only one direction of the
			// pair was ever asked. See {@link #CLASH_ASKS_IF_THE_NEIGHBOUR_IS_LIVE}.
			if (CLASH_ASKS_IF_THE_NEIGHBOUR_IS_LIVE && (hasFront || hasBack)
					&& placements.liveAt(beyond, time)) {
				return true;
			}
			if (hasFront && placements.liveAt(beyond.relative(travel), time)) {
				return true;
			}
			if (hasBack && placements.liveAt(beyond.relative(travel.getOpposite()), time)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the parity check asks about flanks this chord has, rather than about every flank.
	 *
	 * <p>Off, this is the rule as it stood: a stacked module is assumed to hang all four low notes,
	 * so any live block in the lane behind at either end of its outer column is a clash. On, it is
	 * asked of the slots the chord actually fills.</p>
	 */
	static boolean FLANK_AWARE_PARITY = true;

	/**
	 * Whether a contested low note moves out of its slot instead of the module moving a column.
	 *
	 * <p>ekran's third option, against the two this file had: a stacked chord that disagrees with the
	 * lane behind used to either shift a column or give the shape up. It can also simply not hang the
	 * one note that is in contention, and hang it somewhere else in the same module.</p>
	 *
	 * <p>It is available exactly when the clash is <em>ours</em>. A stacked module's outer column
	 * reads note, live, note along the lane, so two columns that disagree disagree in two ways at
	 * once: their note against our relay, and their relay against our note. The second is a note of
	 * ours and can be moved. The first is a note of theirs, already built, and nothing this chord
	 * does to its own slots will save it -- so that one is still a shift or a bus. Both fall out of
	 * one question rather than two: take the note out and ask {@link #stackedClashes} again, and a
	 * clash that was never ours does not clear.</p>
	 *
	 * <p>And only where the note has somewhere free to go, because a relocation that lengthens the
	 * module is one the planner budgeted two columns for and the walk spends three on, and this file
	 * has already paid for that lesson once. Free means the centre, which costs nothing at all, or a
	 * bus cell the tail had spare.</p>
	 */
	static boolean RELOCATES_CONTESTED_NOTE = true;

	/**
	 * Whether a relocated note may take a free centre.
	 *
	 * <p>The half of this that is new. The old rule could only push a note onto a bus, so it only
	 * ever helped a stacked-bus; the centre belongs to every stacked shape, so a plain stacked chord
	 * -- which had no third option at all -- gets one. It costs no cell either, where an even tail
	 * costs one, so it is tried first.</p>
	 *
	 * <p>Harps only, and not as a preference. The centre note block stands directly on the dust
	 * cross with no instrument block of its own, so whatever is written on it sounds as a harp.</p>
	 */
	static boolean RELOCATES_TO_CENTRE = true;

	/**
	 * Whether any of the four low slots may be the one that moves, or only the back pair.
	 *
	 * <p>The back pair is where nearly every contention is, and that is geometry rather than luck.
	 * Touching lanes run opposite ways, so a neighbour whose relay lands on this module's back flank
	 * has its own <em>back</em> flank against this module's relay -- and back flanks fill last, so
	 * that one is often not there and the clash is ours alone. The neighbour a column the other way
	 * puts its <em>front</em> flank against our relay, and the front pair fills first, so that clash
	 * is nearly always theirs and no relocation will clear it.</p>
	 *
	 * <p>So the front pair is here for completeness and for the cases the neighbour is not a plain
	 * module at all, and it should fire rarely. If it fires often, the table above is wrong about
	 * something and the thing to do is find out what.</p>
	 */
	static boolean RELOCATES_ANY_CORNER = true;

	/**
	 * Whether a rigid stacked chord that loses the pair beside its opening keeps a head of five.
	 *
	 * <p>Off, only a stacked-bus kept its head and the plain stacked shape fell to a bus. The shape
	 * it should have fallen to already existed -- a head of five with the rest on a short bus -- and
	 * is never longer than the plain bus, so this can only shorten what the walk builds. Set in both
	 * {@link #landingOf} and {@link #addChordModule}, which have to make the same substitution or
	 * the lane is measured for one shape and built as another.</p>
	 *
	 * <p>Measured and not kept on. Paired over the 1,408 builds both ways can make it is a wash --
	 * 4,640 blocks and 63 breach blocks saved against 9 more breaches, 20 blocks more depth and 2,381
	 * more nudges -- and it refuses 20 builds that the plain bus manages, gaining 5. The per-chord
	 * arithmetic is still right; what it buys back one lane, it spends in another. One word from
	 * being measured again.</p>
	 */
	static boolean FRONT_HEAD_WHEN_BEHIND_BUSY = false;

	/**
	 * Whether a module whose back pair the lane behind took stands a column off instead of shrinking.
	 *
	 * <p>The pair of low slots between two modules a repeater apart belongs to whichever went down
	 * first. One column of pad stands this one three columns off instead of two and the pair is its
	 * own again -- the same thing any wait already does for it, which is why the rule only ever fired
	 * with no wait at all.</p>
	 *
	 * <p>Only where the shape would otherwise lose its head altogether, because that is the only
	 * place the column is cheap. See {@link #losesTheHeadWithoutTheBackPair}.</p>
	 */
	static boolean NUDGE_WHEN_BEHIND_BUSY = true;

	/**
	 * Whether losing the pair of low slots behind would cost this chord its head altogether.
	 *
	 * <p>The substitution for a module whose back pair is taken is a head of five with the notes that
	 * would have hung there on the bus instead. That costs one cell, which is the same column a shift
	 * costs, so where the short head can be made neither move wins and the shorter one is kept.</p>
	 *
	 * <p>Where it cannot -- four hangers is two fewer places to put a note that will not conduct, and
	 * a head of five wants the centre, so a chord with no harp to spare cannot make one -- the chord
	 * falls all the way to a plain bus. A chord of fifteen is then nine columns where a shifted head
	 * of seven is eight, and that is the case the shift is for. On Hammer of Justice at twenty-four
	 * wide over four floors this is 74 chords of the 89 that lose the pair.</p>
	 *
	 * <p>Mirrors the substitution in {@link #landingOf} and {@link #addChordModule} rather than
	 * restating it: what it asks is whether that expression would land on a bus.</p>
	 */
	private static boolean losesTheHeadWithoutTheBackPair(ChordStyle style, List<EventNote> chord) {
		if (!(FRONT_HEAD_WHEN_BEHIND_BUSY || FRONT_ONLY_HEADS && style.busHeaded())) {
			return true;
		}
		return stackedBusSplit(chord, false) == null;
	}

	/**
	 * Whether a chord landing anywhere but where it was foretold re-plans the rest of its lane.
	 *
	 * <p>Off, because it does nothing: Guardian at 24 wide over five floors comes out byte for byte
	 * the same with it on, which is what the counter beside it already said the last time somebody
	 * tried this. Kept because the reasoning for it is still sound and the next drift may not be
	 * this one.</p>
	 */
	static boolean REPLAN_ON_DRIFT = false;

	/**
	 * Whether a nudge is refused by measuring the run rather than by trusting the lane's budget.
	 *
	 * <p>ekran: nudge when it can, and fall back to a bus when nudging would leave a dead wire. The
	 * rule for that already existed and could not fire -- it refused only when the tip had reached
	 * nought, which is a different question from whether the wire reaches one cell further.</p>
	 */
	static boolean MEASURED_NUDGE_REACH = true;

	/** Scratch: one line per nudge decision, with the run the wire has actually laid. */
	static boolean TRACE_NUDGE = false;

	/**
	 * Somewhere a contested note can go without the module growing a column.
	 *
	 * <p>Both halves are facts about the chord alone, not about the ground, which is what lets the
	 * planner work them out as readily as the walk. The centre is free when the chord did not need it
	 * to hold its notes; the bus has a cell spare when its tail is odd, because a bus carries two
	 * notes a cell and an odd tail is half a cell short of using its last one.</p>
	 */
	private record RelocationRoom(boolean centre, boolean tail) {
		static final RelocationRoom NONE = new RelocationRoom(false, false);

		boolean any() {
			return centre || tail;
		}

		/** Part of the oracle's key: two chords in the same place get different answers. */
		int key() {
			return (centre ? 1 : 0) + (tail ? 2 : 0);
		}
	}

	private static RelocationRoom relocationRoom(ChordStyle style, List<EventNote> chord) {
		if (!RELOCATES_CONTESTED_NOTE || !style.stacked()) {
			return RelocationRoom.NONE;
		}
		UltraSlots slots = slotsFor(style, chord);
		if (slots == null) {
			return RelocationRoom.NONE;
		}
		StackedBusSplit split = splitFor(style, chord);
		return new RelocationRoom(RELOCATES_TO_CENTRE && slots.centre() == null,
			split != null && split.tail().size() % 2 == 1);
	}

	/**
	 * Whether the note in this particular slot has somewhere free to go.
	 *
	 * <p>The centre only sounds as a harp, so a note that is not one cannot simply move there. It can
	 * still be got out of the way where some <em>other</em> low slot holds a harp: that harp takes the
	 * centre and this note takes the slot it left. Two notes move and one slot ends up empty, which is
	 * the slot that was in contention -- and the module is the same size, in the same two columns,
	 * with the same notes in it.</p>
	 *
	 * <p>ekran found the case: a chord of six with the centre standing as plain stone, a harp
	 * somewhere in it, and the module padded a column forward anyway -- into a staircase, which is
	 * what turned it into a collision rather than merely a wasted column.</p>
	 */
	private static boolean roomFor(UltraSlots slots, RelocationRoom room, int slot) {
		if (room.tail()) {
			return true;
		}
		if (!room.centre()) {
			return false;
		}
		return isHarpNote(slots.slot(slot))
			|| CENTRE_TAKES_A_SPARE_HARP && spareHarpFlank(slots, slot) >= 0;
	}

	/** Whether a note that is not a harp may still take the centre, by trading with one that is. */
	static boolean CENTRE_TAKES_A_SPARE_HARP = true;

	/** A low slot other than this one holding a harp, which could take the centre in its place. */
	private static int spareHarpFlank(UltraSlots slots, int slot) {
		for (int index = 0; index < 4; index++) {
			if (index != slot && slots.slot(index) != null && isHarpNote(slots.slot(index))) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * Which low slot, emptied, would clear the clash -- or -1 if moving one note cannot.
	 *
	 * <p>Asked by taking the note out and putting the same question to {@link #stackedClashes} again,
	 * rather than by working out from the geometry which corner must be the guilty one. Deciding that
	 * by hand is the easy thing to get wrong, and getting it wrong is silent: the module builds, the
	 * note count comes out right, and one note sounds on somebody else's tick. Asked this way a
	 * wrong guess simply does not clear the check and the chord takes the shift it would have taken.
	 * It also settles the case the geometry cannot, where what is across the way is a turn or a bus
	 * rather than a module and more than one of the four is in contention.</p>
	 */
	private static int relocatableSlot(PlacementPlan placements, Lane at, int time, UltraSlots slots,
			RelocationRoom room) {
		if (slots == null || !room.any()) {
			return -1;
		}
		// Back pair first. Not a preference so much as an order: it is where nearly every real
		// contention is, and trying it first leaves this answering what the back-flank rule before it
		// answered wherever the other two slots change nothing.
		int[] order = RELOCATES_ANY_CORNER ? new int[] {2, 3, 0, 1} : new int[] {2, 3};
		for (int slot : order) {
			if (slots.slot(slot) != null && roomFor(slots, room, slot)
					&& !stackedClashes(placements, at, time, slots.without(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * What a stacked module standing here has to do about the lane behind it.
	 *
	 * <p>0 to build where it stands, 2 to move one low note somewhere free, 1 to shift a column, 3 to
	 * shift a column <em>and</em> move a note there, -1 to give the shape up. Asked in that order, and
	 * asked by the planner and the walk through this one method so that neither can answer it
	 * differently from the other.</p>
	 *
	 * <p>The order is ekran's, and the point of it is that the bus is last. Every one of these keeps
	 * the shape; the fallback throws it away for four cells of lane, so it is what is left when the
	 * others have all been tried rather than the third thing reached for.</p>
	 */
	/**
	 * Whether parity is judged at the column a module ends up in rather than the one it asks from.
	 *
	 * <p>Off, a stacked chord standing on a corner is judged where it stands and built one column
	 * further along, because {@link #addStackedShape} walks past the corner first -- a repeater may
	 * not stand on one. One column is the whole of what parity means, so the verdict it was given is
	 * the verdict for somewhere else. ekran read it off a build: <i>"the question is why the other
	 * stacked chord didn't parity pad forward. perhaps cause it had already padded forward from the
	 * corner padding. corner padding and parity padding should be able to both happen."</i></p>
	 *
	 * <p>It fires 38 times over the library and produces one wrong note, so the two usually agree by
	 * luck. Counted as {@code parityAskedBeforeTheCorner}.</p>
	 */
	static boolean PARITY_ASKS_PAST_THE_CORNER = true;

	private static int parityVerdict(PlacementPlan placements, Lane at, int time, UltraSlots slots,
			RelocationRoom room) {
		if (!stackedClashes(placements, at, time, slots)) {
			return 0;
		}
		if (relocatableSlot(placements, at, time, slots, room) >= 0) {
			return 2;
		}
		if (!stackedClashes(placements, at.ahead(1), time, slots)) {
			return 1;
		}
		// Asked again from the column the pad would put it in, before the shape is given up.
		//
		// ekran's ordering, and the bus is meant to be the last thing tried rather than the third.
		// Relocation is offered where the module stands and a shift is offered after it, but the two
		// were never combined: a module that clashes in both columns went straight to a bus, even
		// where one note moved out of one slot would have settled the shifted column. And the pad is
		// exactly what makes that likely -- it walks the module into a different neighbour, so the
		// slot in contention is a different slot.
		if (RELOCATES_AFTER_THE_SHIFT
				&& relocatableSlot(placements, at.ahead(1), time, slots, room) >= 0) {
			return 3;
		}
		return -1;
	}

	/** A contested low note lifted out of its slot, and the module that results. */
	private record Relocation(UltraSlots slots, StackedBusSplit split, String where) {
	}

	/**
	 * The module with its one contested note moved, or {@code null} if none of them can be.
	 *
	 * <p>The centre wherever it is free and the note can sound there, because it costs nothing and is
	 * the only target a plain stacked chord has. Otherwise the bus, which is the old rule: the note
	 * joins the tail, a head of seven becomes one of six, and the chord is whole either way.</p>
	 */
	private static Relocation relocate(PlacementPlan placements, Lane at, int time, ChordStyle style,
			List<EventNote> chord, UltraSlots slots, RelocationRoom room) {
		int slot = relocatableSlot(placements, at, time, slots, room);
		if (slot < 0) {
			return null;
		}
		EventNote note = slots.slot(slot);
		UltraSlots emptied = slots.without(slot);
		StackedBusSplit split = splitFor(style, chord);
		if (room.centre() && isHarpNote(note)) {
			UltraSlots moved = emptied.withCentre(note);
			return new Relocation(moved, split == null ? null
				: new StackedBusSplit(moved, split.head(), split.tail()), "Centre");
		}
		// Or the note trades places with a harp the module was hanging somewhere it does not mind
		// losing: the harp takes the centre, this note takes the harp's slot, and the contested one
		// is left empty. Same notes, same slots filled bar the one that had to go, same two columns --
		// so nothing downstream can tell the difference, and stackedClashes was asked about exactly
		// this arrangement.
		int harp = room.centre() ? spareHarpFlank(slots, slot) : -1;
		if (harp >= 0) {
			UltraSlots moved = emptied.with(harp, note).withCentre(slots.slot(harp));
			return new Relocation(moved, split == null ? null
				: new StackedBusSplit(moved, split.head(), split.tail()), "CentreSwap");
		}
		List<EventNote> tail = new ArrayList<>(split.tail());
		tail.add(note);
		List<EventNote> head = new ArrayList<>(split.head());
		head.remove(note);
		return new Relocation(emptied, new StackedBusSplit(emptied, head, tail), "Tail");
	}

	/**
	 * Whether a stacked head in front of a descent gives up the one flank the staircase wants.
	 *
	 * <p>ekran's, read off the breach at forty wide over five floors. A head is three columns --
	 * repeater, centre, front flanks -- and it hands over on a transition cell, which is stone with
	 * dust on it. A descent's first rung is stone with dust on it, in the same place, at the same
	 * level: {@link #addParityPad} and the first step of {@link #addSplitBusDescent} lay the same two
	 * blocks. So the two can share a column. The staircase begins where the transition would have
	 * been, the centre lights its first rung exactly as it lit the pad, and the head needs one column
	 * fewer to close a lane -- which is the column the lane was breaching for.</p>
	 *
	 * <p>Exactly one block is in the way. The spiral steps off its centre line towards
	 * {@code descentSide}, so its second rung's dust lands in that same column, on that side, at the
	 * height the front flanks hang at. The front flank on the descent side is in it. The other one is
	 * not, and never can be: the spiral only ever steps one way.</p>
	 *
	 * <p>So this is a relocation rather than a smaller head, which is what makes it cheap. The note
	 * comes out of that slot and goes somewhere else in the same module for nothing -- a free centre,
	 * a spare harp's slot, a low slot the chord did not fill -- or, where the chord has a bus, over
	 * the staircase with the rest of the far half. A head with nowhere to put it does not shed, and
	 * the lane does what it does today.</p>
	 */
	static boolean SHEDS_THE_FLANK_THE_DESCENT_WANTS = true;

	/**
	 * Whether a cut one cell too long may shed the descent's flank to buy that cell.
	 *
	 * <p>{@link #SHEDS_THE_FLANK_THE_DESCENT_WANTS} already sheds, but only where
	 * {@code nearBusCells == floorCells - 1}, which for a descent is {@code room == 2} exactly -- the
	 * shed as a saving of <em>columns</em>, for a corridor too tight to hold the near half. This is
	 * the same shed as a saving of <em>wire</em>, for a corridor with room to spare where the fifteen
	 * is what ran out. The two want it at opposite ends and neither covers the other.</p>
	 *
	 * <p>ekran's, and their arithmetic for a chord of 28 going down: six in the head once the flank is
	 * shed, twenty-two left for the bus at eleven cells, no transition cell because the head hands
	 * over onto the staircase's own first rung, and a staircase of four. Fifteen exactly.</p>
	 *
	 * <p>The note the head gives up is not dropped -- {@link #shedDescentFlank} rehomes it, to the
	 * centre, to a spare harp's slot, to an unfilled low slot, or failing all of those to the bus,
	 * where it pairs into whichever half was ending on a half-empty cell.</p>
	 *
	 * <p><b>Off, and it works -- that is not the problem.</b> A chord of 28 goes from 5 descent cuts
	 * to 25 and that config's breaches from 30 blocks to 19, {@code BigSplitTest} reads every note
	 * back, and over the library it fires 68 times for three columns and seventy blocks less. What it
	 * costs is the wall: a shed cut's near half is the head and nothing else, two columns, so the lane
	 * hands over almost where it started. {@link UltraLaneFaultsTest} counts <b>31 lanes that could
	 * not be landed on their wall</b> and goes red on it.</p>
	 *
	 * <p>Which is the same debt as {@link #CUTS_A_CHORD_THAT_FITS}, and it wants the same payment:
	 * <b>pad the near half out to the wall</b>. Until that exists this buys a cut by giving up the
	 * handover, and a lane that hands over short puts its staircase where no other lane's is.</p>
	 */
	static boolean SHED_BUYS_THE_LAST_CELL = false;

	/** How often shedding the flank turned a cut that was one cell over into one that fits. */
	static int SHED_BOUGHT_THE_CELL;

	/**
	 * The chord sizes that needed it, so over-use is visible rather than argued about.
	 *
	 * <p>ekran's worry, and the right one: a head-only near half is the dear shape, and it should be
	 * reached for only where nothing else will do. The gate says it cannot be reached for otherwise --
	 * it fires at {@code runCells == 16} exactly, which is one over, and a cut that already fits
	 * returns before it. On a descent that arithmetic is {@code 1 + 11 + 4}, so the tail is 21 or 22
	 * and the chord is 28 or 29. This says whether the build agrees.</p>
	 */
	static final java.util.TreeMap<Integer, Integer> SHED_BOUGHT_BY_SIZE = new java.util.TreeMap<>();

	/**
	 * The low slot a descent's second rung stands in.
	 *
	 * <p>Side 1 is {@code descentSide}, and it is that for the whole build rather than for one lane:
	 * {@code depth} is fixed once at the head of the walk, {@code descentSide} is its opposite, and
	 * {@link #addStackedEventModule} numbers its sides {@code [depth, depth.getOpposite()]}. So side
	 * 1 is the side every spiral in the build steps towards, whichever way the lane holding it
	 * travels. Front rather than back because the staircase is always ahead of the module.</p>
	 */
	private static final int DESCENT_FLANK_SLOT = 1;

	/** A head with the descent's flank rehomed, and the note the bus must take if no slot could. */
	private record ShedFlank(UltraSlots slots, EventNote toBus) {
	}

	/**
	 * The same head with nothing hanging where the staircase's second rung goes, or {@code null}.
	 *
	 * <p>Ordered by what it costs, which is the order {@link #relocate} uses and for the same reason:
	 * the centre and a spare slot cost no cell at all, and the bus costs one wherever the tail was
	 * even. A head that can only shed onto the bus still sheds -- the column at the wall is worth
	 * more than the cell -- but it is tried last so that the chords which can do it for free do.</p>
	 *
	 * @param granted how many of the back pair this head was allowed. A back slot standing empty
	 *     because the lane behind owns it is not one this chord may fill, and the two look identical
	 *     from the slots alone. Getting that wrong hangs a note in somebody else's cell, which is
	 *     the one kind of mistake in this file that builds cleanly and plays wrong.
	 * @param hasBus whether there is a tail to take the note when no slot can
	 */
	private static ShedFlank shedDescentFlank(UltraSlots slots, int granted, boolean hasBus) {
		if (slots == null) {
			return null;
		}
		EventNote note = slots.slot(DESCENT_FLANK_SLOT);
		if (note == null) {
			// Nothing hangs there, so this head already stands alongside a staircase quite happily.
			return new ShedFlank(slots, null);
		}
		UltraSlots emptied = slots.without(DESCENT_FLANK_SLOT);
		if (slots.centre() == null) {
			if (isHarpNote(note)) {
				return new ShedFlank(emptied.withCentre(note), null);
			}
			// Or it trades with a harp the module is hanging somewhere it does not mind losing: the
			// harp takes the centre, this note takes the harp's slot, and the contested one empties.
			int harp = CENTRE_TAKES_A_SPARE_HARP ? spareHarpFlank(slots, DESCENT_FLANK_SLOT) : -1;
			if (harp >= 0) {
				return new ShedFlank(emptied.with(harp, note).withCentre(slots.slot(harp)), null);
			}
		}
		// Then a low slot the chord did not fill. The front slot on the other side is always this
		// module's to use. A back slot only where the head was granted it, and {@link #backPair}
		// fills the far side first, so a grant of one means slot three and not slot two.
		int[] spare = granted >= 2 ? new int[] {0, 3, 2}
			: granted == 1 ? new int[] {0, 3}
			: new int[] {0};
		for (int slot : spare) {
			if (slots.slot(slot) == null) {
				return new ShedFlank(emptied.with(slot, note), null);
			}
		}
		return hasBus ? new ShedFlank(emptied, note) : null;
	}

	/** Which of the four low-note slots a chord fills, as one number, for keying the oracle. */
	private static int flankMask(UltraSlots slots) {
		if (slots == null) {
			return 15;
		}
		int mask = 0;
		for (int side = 0; side < 2; side++) {
			mask |= slots.front(side) != null ? 1 << side : 0;
			mask |= slots.back(side) != null ? 4 << side : 0;
		}
		return mask;
	}

	/** The slots a chord of this style would fill, for asking which flanks it hangs. */
	private static UltraSlots slotsFor(ChordStyle style, List<EventNote> chord) {
		if (style.busHeaded()) {
			StackedBusSplit split = splitFor(style, chord);
			return split == null ? null : split.slots();
		}
		return ultraSlots(chord, style == ChordStyle.STACKED_FULL);
	}

	/**
	 * A column of path that carries the wire along and costs no time: glass with dust over it.
	 *
	 * <p>What a pad is made of, and what a corner takes so that no repeater has to stand on one.</p>
	 *
	 * <p>It used to be laid for a third reason as well, and that reason was not real. Stacked
	 * modules were nudged onto an even coordinate on the story that two lanes had to agree which
	 * of their alternating outer cells was live, or they would set off each other's notes. They
	 * cannot: lane centres are four apart, and a module is driven from its own centre, so nothing
	 * of one lane ever reaches the notes of the next. Removed 2026-07-30 -- ekran, who did not
	 * invent the stacked module but took it from a working world built by somebody else, and so
	 * knew the constraint had never existed.</p>
	 *
	 * <p>Glass and not stone. Dust makes the block beneath it live, and the blocks either side of
	 * that one are exactly where low notes hang -- notes belonging to a later chord, which an
	 * earlier live block would sound before its time. Glass cannot be powered at all. The dust on
	 * top has a solid block behind it and a repeater in front, so it takes the straight shape and
	 * points along the lane only, never sideways into a note.</p>
	 */
	private static void addParityPad(PlacementPlan placements, BlockPos cursor) {
		if (TRACE) {
			System.out.println("PAD at " + cursor.getX() + " " + cursor.getY() + " " + cursor.getZ()
				+ " by " + placements.placing() + " softTip=" + placements.softTip()
				+ " softBehind=" + placements.softBehind()
				+ " tail=" + (placements.softTail() != null)
				+ " tailBehind=" + (placements.softTailBehind() != null));
		}
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(), "minecraft:redstone_wire");
	}

	/**
	 * The parity pad, or the same two columns with the delay split across them. ekran's.
	 *
	 * <p>A parity pad is a cell of dust, and dust after a module that ended soft is a dead wire: the
	 * simple tail's middle is lit by the handover and by nothing else, so it drives a repeater and
	 * cannot light dust. ekran read exactly that off a paste -- <i>"the chord got parity padded,
	 * causing wire to be on both sides"</i>.</p>
	 *
	 * <p>But the pad and the module's own trigger are two columns either way, and a delay of two or
	 * more can be spent as two repeaters instead of a pad and one: {@code r1} here, and the rest in
	 * the trigger. Same columns, same total delay, and the cell against the soft middle is now a
	 * repeater, which is the one thing that reads it. Nothing is undone to do it -- the pad is laid
	 * after the tail, so this is a local decision that only has to know what came before, which is
	 * what {@link PlacementPlan#softTip()} carries.</p>
	 *
	 * <p>A delay of one cannot be split: there is no repeater shorter than one tick. That case is
	 * still open -- the tail would have had to be a bus, which is a decision a column too early --
	 * and it is counted here rather than papered over.</p>
	 *
	 * @return what the module's own trigger should now be delayed by
	 */
	private static int parityPadOrSplitRepeater(PlacementPlan placements, Lane at, int triggerDelay) {
		// Named, because it did not name itself and a label is sticky. Every cell this laid wore
		// whatever the last shape to set one called itself -- in practice "delayBeforeChord", which
		// sets the label and never puts it back -- so eleven dead builds read as a fault in the
		// spatial delay when the pad is what laid the block. See pastAnyCorner for the same trap.
		placements.placing("parityPad");
		placements.padded("parity");
		return padCellOrSplitRepeater(placements, at.pos(), at.travel(), triggerDelay,
			placements.softBehind(), true, "parityPad");
	}

	/**
	 * One cell of pad, spent as a repeater where the module behind ended soft and there is a tick.
	 *
	 * <p>Three separate places lay a cell of dust immediately after a module -- the parity pad, the
	 * pin a headed cut spends carrying itself out to its wall, and the column a shortened cut hands
	 * back -- and until now <b>only the first of them knew the question existed</b>. Seven of the
	 * library's twenty dead builds were the pin and one was the nudge, and no amount of care in the
	 * pad could have reached either. One helper, so the three cannot drift again.</p>
	 *
	 * <p>The trade is ekran's and it costs nothing: the cell against the tip becomes {@code r1} and
	 * the module's own trigger carries one tick less, so the columns and the total delay are both
	 * unchanged and the thing standing against the soft middle is a repeater, which is the one thing
	 * that reads it.</p>
	 *
	 * @param soft whether the module immediately behind this cell ended on a cell only dust powers.
	 *     Passed in rather than read here because the two walks carry it in different fields: an
	 *     ordinary chord goes through {@link #buildShaped}, which rolls the flag on entry, so during
	 *     its build the module behind is {@code softBehind}. A cut is built straight from the walk and
	 *     never rolls, so during a cut the module behind is still {@code softTip}. Reading the wrong
	 *     one of those is a guard that never fires and reports numbers identical to the digit.
	 * @return the delay the module's own trigger should now carry
	 */
	private static int padCellOrSplitRepeater(PlacementPlan placements, BlockPos at, Direction travel,
			int triggerDelay, boolean soft, boolean rolled, String why) {
		if (soft && SPLIT_THE_PAD_REPEATER && triggerDelay >= 2) {
			placements.padded(why + "SplitRepeater");
			placements.powered(at, "minecraft:stone", NO_BLANK);
			set(placements, at.above(), "minecraft:repeater[facing="
				+ repeaterFacing(travel) + ",delay=1]");
			return triggerDelay - 1;
		}
		if (soft) {
			// The one the split cannot reach: a delay of one, where there is no repeater short enough.
			// Only a note-block middle can be here at all -- a stone middle carries its own dust and is
			// never a soft tip -- and a note block has no cell above it to be rescued in. Counted by the
			// delay as well as by the site, because "how often" and "at what delay" are different
			// questions and only the second says whether anything short of undoing the tail can help.
			placements.padded(why + "OnASoftTip");
			placements.padded("softTipDelay" + triggerDelay);
			// So take the tail back up and lay it as a bus instead. ekran's, and the reason it is free:
			// a bus of the same notes starts in the tail's own column and its second cell is this one,
			// the pad's -- so no pad is laid, nothing moves, and the module after it opens exactly
			// where it was going to. The tail no longer has to guess what follows it, because by here
			// the thing that follows it is standing right there.
			if (undoTheSoftTailFor(placements, at)) {
				placements.padded(why + "UndidTheTail");
				return triggerDelay;
			}
		}
		addParityPad(placements, at);
		return triggerDelay;
	}

	/**
	 * Whether the tail behind this cell was taken back up and re-laid as a bus that reaches it.
	 *
	 * <p>Refused unless the geometry is exactly the one that makes the swap free: the bus's second
	 * cell has to be <em>this</em> column, at bus height. Anything between the tail and here -- a
	 * corner, a cell of spatial delay, a second pad -- and the bus would not reach, so the trade is
	 * off and the pad goes down as it always did. Checked against the position rather than assumed
	 * from the shape, because "nothing came between" is exactly the kind of thing this file keeps
	 * being wrong about.</p>
	 */
	private static boolean undoTheSoftTailFor(PlacementPlan placements, BlockPos at) {
		// Both of them, and the position decides which. The walk carries two copies of every "what did
		// the module behind me do" answer -- one rolled and one not -- because buildShaped rolls on
		// entry and the walk's own pads are laid on either side of that. Which copy is live at a given
		// call site turned out to be genuinely hard to reason about, and I got it wrong twice in
		// opposite directions. It does not have to be reasoned about: only a tail whose second bus
		// cell is *this* column can be traded for *this* pad, so asking both and letting the geometry
		// answer is exact where the flag was a guess.
		boolean rolled = false;
		PlacementPlan.SoftTail tail = placements.softTail();
		if (tail == null || !tail.at().ahead(1).pos().equals(at.above())) {
			tail = placements.softTailBehind();
			rolled = true;
		}
		if (tail == null) {
			placements.padded("softTailNotRecorded");
			return false;
		}
		BlockPos second = tail.at().ahead(1).pos();
		if (!second.equals(at.above())) {
			placements.padded("softTailOutOfReach");
			if (TRACE) {
				System.out.println("SOFTTAIL out of reach: pad at " + at.getX() + " " + at.getY() + " "
					+ at.getZ() + "  now=" + say(placements.softTail())
					+ "  behind=" + say(placements.softTailBehind()));
			}
			return false;
		}
		placements.undoSoftTail(rolled);
		// The handover's cross was shaped for a middle that does not join it. A bus cell in the very
		// next column does join, so it goes back to plain wire -- which is what the bus tail has always
		// been handed.
		set(placements, tail.handoverDust(), "minecraft:redstone_wire");
		String was = placements.placing();
		placements.placing("chord:STACKED_BUS+busTailAfterAPad");
		layBus(placements, tail.at(), tail.notes(), tail.time());
		// Put back, for the reason pastAnyCorner puts it back: a label is sticky, and every cell the
		// caller lays after this would otherwise be reported as part of a tail laid a chord ago.
		placements.placing(was);
		placements.softTip(false);
		return true;
	}

	private static String say(PlacementPlan.SoftTail tail) {
		return tail == null ? "none" : tail.at().pos().getX() + "," + tail.at().pos().getY() + ","
			+ tail.at().pos().getZ() + "->" + tail.at().ahead(1).pos().getX();
	}

	/**
	 * Whether a run ending on a floor column leaves the path rail live for the lane after it.
	 *
	 * <p>Off, it leaves air there and the lane's next trigger reads nothing -- which is 182 severed
	 * lanes over the library, 178 of them on one song, and every dead-wire number in this repo blind
	 * to all of them because a starved repeater reads to {@link NoteMachineReader} as a second way
	 * into the machine rather than as a break.</p>
	 *
	 * <p>The cell is one the column was going to leave empty and the repeater costs no column at all.
	 * It cannot always be done: the delay is this chord's tick less the path rail's last, and where
	 * that is over four there is no repeater long enough -- counted as {@code railHandUpTooLong},
	 * which wants the run to end a pair earlier instead.</p>
	 */
	static boolean RAIL_HANDS_THE_PATH_UP = true;

	/** Off, a simple tail that a pad lands in front of stays where it is and the lane dies there. */
	static boolean SIMPLE_TAIL_UNDONE_FOR_A_PAD = true;

	/** Whether a parity pad after a soft-ended module is spent as a repeater rather than as dust. */
	static boolean SPLIT_THE_PAD_REPEATER = true;

	/**
	 * Whether a pad that runs into a climb is laid at bus height rather than on the path.
	 *
	 * <p>ekran's. A climb off a bus costs three cells and a climb off the path costs five, and the
	 * only difference between them is one level: a bus is stone at path+1 with dust at path+2, a pad
	 * is stone on the path with dust at path+1, and {@link #addGlassClimb} skips its first two rungs
	 * exactly when the live wire is already a block up. So a pad laid one level higher is a bus as
	 * far as the staircase is concerned, and the two cells come back.</p>
	 *
	 * <p>It costs nothing in columns. A pad is one cell of dust a column whatever height it runs at,
	 * and dust steps up a level of its own accord as long as the block above the lower dust is clear
	 * -- which it is, because nothing else is built over a pad.</p>
	 *
	 * <p>And the "or stay at it" half is the sharper one. {@link #PREPADS_FOR_THE_OFF_BUS_DISCOUNT}
	 * exists only because a single cell of dust between a bus and a staircase drops the staircase
	 * back to the dear form -- the pad came down to the path and took the discount with it. A pad
	 * that stays up does not, so a lane ending on a bus keeps its discount however many columns of
	 * pad stand between it and the wall.</p>
	 */
	static boolean PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;

	/**
	 * Whether the closing search prices a climb at the raised pad's three cells rather than five.
	 *
	 * <p>Separate from the rule itself so the two can be told apart, because they do not move the
	 * same way. The walk building raised pads takes Guardian's worst breach from twelve to nine; the
	 * planner then booking them as well takes breaches from 184 to 339. Both are measurements, not
	 * predictions.</p>
	 *
	 * <p>Which is the interesting result, and the reason this is a flag rather than a fix. The plan
	 * spending the discount is not wrong in itself -- it is the same discount the walk spends -- but
	 * a cheaper reserve makes {@link #planPad} fill more columns before it gives up, so the search
	 * closes lanes it used to leave open. Where those two agree the lane is better off; where they
	 * do not, the plan has closed a lane on a pad the walk builds differently, and the gap between
	 * {@code sweep.tips()} and the walk's own {@code tipSignal} is exactly the ground that disagrees.
	 * That gap is already counted under the {@code plan*} keys and is not new here -- this change
	 * only makes it expensive.</p>
	 */
	static boolean PLANS_THE_RAISED_PAD = true;

	/**
	 * The same pad one level up, which is where a bus runs and where a climb wants to start.
	 *
	 * <p>Glass, and that is the whole of it. The lane arrives with its dust on the path and this run
	 * carries it a block higher, which is a step up -- and {@link #addGlassClimb} says in writing why
	 * a step up is glass: the block that would otherwise sit between the two dusts has to be
	 * see-through or they do not connect at all. Built with stone this laid perfectly, refused
	 * nothing, reported no wrong notes and no dead wire, and conducted nothing: eight hundred and
	 * forty-three note blocks the signal never reached, on a machine that looked right in every
	 * number a {@code PastePlan} carries. Only {@link NoteMachineReader#read} could see it.</p>
	 *
	 * <p><b>Stone, on ekran's say-so, and the glass above is my inference rather than a reading.</b>
	 * A floor of stone under a run of dust is what every other pad in this build is, and the step up
	 * onto it is one block -- so if that will not conduct, the current is being cut somewhere else
	 * and glass here would only paper over it. Left as stone so the fault is the one being looked at.
	 * {@link NoteMachineReader} agrees something is wrong; it does not say what, and it is a model
	 * rather than the game.</p>
	 */
	private static void addRaisedPad(PlacementPlan placements, BlockPos cursor) {
		set(placements, cursor.above(), "minecraft:stone");
		set(placements, cursor.above(2), "minecraft:redstone_wire");
	}

	/**
	 * Whether this pad, standing where it does, is one the climb after it can be lifted onto.
	 *
	 * <p>All dust and not empty. A repeater has to stand on the path with its own block under it, so
	 * a pad holding one cannot be lifted wholesale; and an empty pad has nothing to lift, which is
	 * the case {@code lastStyle.buses()} already answers.</p>
	 */
	private static boolean padRaises(Pad pad, boolean climbing, boolean staircase, boolean fromBus) {
		return raiseAfter(pad, climbing, staircase, fromBus) >= 0;
	}

	/**
	 * How many cells of pad stay on the path before the rest is lifted, or -1 for no lift at all.
	 *
	 * <p>ekran, reading a dead wire at {@code 453 92 -52}: a lane that ends on a bus already has its
	 * wire a block up and the pad simply stays there, but a lane that ends on a note block has
	 * nothing up there to step off. A note block is a full block and the repeater drives it, but a
	 * driven block does not pass power on to its neighbour -- so the lifted stone beside it is fed by
	 * nothing and the dust on top reads {@code w0}. One cell of pad on the path gives the run a live
	 * wire to climb from, and then it can go up.</p>
	 *
	 * <p>Which means a pad of one column cannot do it at all: its single cell is the one that has to
	 * stay down, and there is nothing left to lift. That lane takes the five-cell ascent, and the
	 * planner has to know it -- a lane budgeted three that builds five is a lane two blocks short of
	 * the top of its own staircase.</p>
	 */
	private static int raiseAfter(Pad pad, boolean climbing, boolean staircase, boolean fromBus) {
		if (!PADS_AT_BUS_HEIGHT_INTO_A_CLIMB || !climbing || !staircase
				|| pad.cells().isEmpty()
				|| !pad.cells().stream().allMatch(cell -> cell == 0)) {
			return -1;
		}
		if (fromBus) {
			return 0;
		}
		return pad.cells().size() >= 2 ? 1 : -1;
	}

	/**
	 * The pad a lane closes on, priced at what the staircase after it is actually going to cost.
	 *
	 * <p>The price and the pad have to be decided together, because each depends on the other: a pad
	 * planned at the dear price may buy a repeater it would not have needed at the cheap one, and a
	 * pad with a repeater in it cannot be lifted, so it does not get the cheap one. Asked here, once,
	 * by both the planner and the walk -- try the cheap price, keep it only if what comes back is a
	 * pad that can actually be raised, and fall back to the dear price otherwise.</p>
	 *
	 * <p>Two code paths deciding this separately is the bug this file keeps producing, and it is
	 * worse here than usual: a plan that closes a lane on three cells the walk spends five on is a
	 * lane that hands over two blocks of wire short of the top of its own staircase.</p>
	 */
	private static Pad planTurnPad(int columns, int signal, int turnCells, int offBus,
			int spareDelay, boolean climbing, boolean staircase, boolean fromBus) {
		if (PADS_AT_BUS_HEIGHT_INTO_A_CLIMB && climbing && staircase && columns > 0
				&& offBus < turnCells) {
			Pad cheap = planPad(columns, signal, offBus, spareDelay);
			if (padRaises(cheap, climbing, staircase, fromBus)) {
				return cheap;
			}
		}
		return planPad(columns, signal, turnCells, spareDelay);
	}

	/**
	 * Whether the walk charges itself the three cells a raised pad actually spends.
	 *
	 * <p>Split from building them so there is an arm where the raise changes nothing anybody decides
	 * on. Off, the walk lays raised pads and then books the dear price for them: {@code canTurn}
	 * still wants five in hand and {@code tipSignal} still hands the next lane five fewer, so every
	 * lane turns exactly where it turned before and the only difference in the whole build is two
	 * blocks of wire the lane really has and does not know about. That arm cannot make a build worse
	 * by any argument, so if it does, the fault is in this code and not in the trade.</p>
	 */
	static boolean PRICES_THE_RAISED_PAD = true;

	/**
	 * Whether {@code reachesWall} charges the raised ascent's three cells the way {@code canTurn} does.
	 *
	 * <p>The turn is priced in two places in the same block. {@link #turnPrice} asks whether the pad
	 * can be lifted onto the climb; {@code turnCost} knew only the older discount, which needs a pad
	 * with no cells at all, and charged the dear price for everything else. A lane arriving with four
	 * blocks of wire on a one-cell pad off a stacked bus therefore turned on one arm's arithmetic and
	 * was refused by the other's -- and being refused, it laid its chord whole and walked out past its
	 * wall.</p>
	 *
	 * <p>ekran found it at illit, 32 wide over four floors: tick 1311, a chord of four standing one
	 * column short of the wall with a pad of one cell and four blocks of wire. {@code turnPrice} said
	 * three, {@code turnCost} said five, {@code reachesWall} went false, and the chord was laid whole
	 * two columns outside. The next chord then arrived already past the wall with nothing it could do
	 * about it.</p>
	 *
	 * <p>Kept as {@code min} of the two rather than replacing one with the other, so the empty-pad
	 * discount that predates all of this cannot be lost on a descent by the change.</p>
	 */
	static boolean WALL_REACH_PRICES_THE_RAISED_PAD = true;

	/** What the turn after this pad costs the wire arriving at it, raise included. */
	private static int turnPrice(Pad pad, boolean climbing, boolean staircase, boolean fromBus,
			int turnCells, int offBus) {
		return climbing && staircase
			&& (padRaises(pad, climbing, staircase, fromBus)
				|| fromBus && pad.cells().isEmpty())
			? offBus : turnCells;
	}

	/**
	 * Where a module left the path, and the shape it was actually built in.
	 *
	 * <p>The shape and not the one the chord was measured with: a turn can hand a chord the stacked
	 * module its measurement gave up on, and what follows -- the pair of slots left free, the level
	 * the signal ends on -- turns on what went down, not on what was planned.</p>
	 */
	/**
	 * @param nudged whether the module stepped a column sideways to agree with the lane behind it.
	 *     Handed back because {@link #landingOf} cannot see it: a nudge is decided against blocks
	 *     that are already placed, and the planner works from arithmetic alone. A lane whose plan
	 *     was made without that column is a lane measured for a wall it no longer reaches.
	 */
	private record Placed(Lane lane, ChordStyle style, int busCells, boolean nudged) {
		boolean stacked() {
			return style.stacked();
		}
	}

	/**
	 * Seven note blocks around one repeater, in the footprint a chord of three used to need.
	 *
	 * <p>The repeater drives a solid block; under that block sits a lone piece of dust, and the two
	 * blocks that dust points sideways into are the instrument blocks of the two notes level with
	 * it. Each of those relays to the two notes flanking it a level down. So one pulse reaches the
	 * centre, two beside it and four below -- and the centre itself can be a note too, because the
	 * thing under it is dust, and a note block over dust plays harp.</p>
	 *
	 * <p>The two blocks the dust relays through therefore have to conduct, which rules out the
	 * three instruments that are not solid blocks and makes a harp there grass rather than the air
	 * it is everywhere else. The four below cannot be snare, because sand needs propping and the
	 * prop would land on the head of a note block one floor down and silence it.</p>
	 */
	/** Whether a long chord may open with a stacked head instead of being all bus. */
	static boolean STACKED_BUS_HEADS = true;

	/** Whether a stacked-bus that would have to shift a column gives up the head instead. */
	static boolean PARITY_PREFERS_BUS = false;

	/**
	 * The most a stacked head can hold: two relays, four low notes and a centre.
	 *
	 * <p>The centre is only reached when the chord fills the hangers, and it has to be a harp,
	 * because everything else in that slot is wanted for its block rather than its sound.</p>
	 */
	private static final int STACKED_HEAD_NOTES = 7;

	/**
	 * Cells the wire spends getting from a stacked head onto its own bus.
	 *
	 * <p>One. The centre is strongly powered, so the dust beside it starts at fifteen like the first
	 * block of any bus; the bus then climbs a level onto it and is a cell poorer than it would have
	 * been off a repeater. That single cell is the whole price of the head.</p>
	 */
	private static final int STACKED_BUS_TRANSITION = 1;

	/** Whether a chord cut across a staircase may open with a stacked head. */
	static boolean STACKED_SPLIT_HEADS = true;

	/** Cuts refused a head only because the near half would have been the head and nothing else. */
	static int HEAD_ONLY_NEAR_HALVES = 0;

	/** Where the last head-only cut handed over to its staircase, for the probe to dump around. */
	static BlockPos HEAD_ONLY_AT = null;

	/** Scratch: where the last cut opened with a head of five, so its blocks can be looked at. */
	static BlockPos SHORT_HEAD_CUT_AT = null;

	/**
	 * Whether a cut's near half may be the head alone, with the whole tail past the staircase.
	 *
	 * <p>The near half used to owe a cell of bus, so that the staircase was always entered from a
	 * bus and {@link #addSplitBusDescent} could take its short way down. A head with nothing behind
	 * it enters from the handover cell instead -- whose dust sits one level up and one column back,
	 * which is precisely where the spiral's first rung puts its own wire. Same level, adjacent
	 * column, so the spiral is still four cells and the ceiling is unchanged: the handover spends
	 * one, the descent four, and ten cells of bus past it carry twenty notes to the head's seven.</p>
	 *
	 * <p><b>Descents only.</b> A descent works and is worth having: real library, eighty configs,
	 * breaches 982 -> 854 and breach blocks 19,073 -> 16,500, with a third again as many headed cuts
	 * and every build still reading back.
	 *
	 * <p>A climb does not, and the reason is one ekran found by pasting it rather than by any amount
	 * of reading. A climb leaves the near half by a glass staircase whose first rung is a level up
	 * and a column over. After a bus that is fine -- the bus is already a level up. After a head the
	 * handover sits beside the centre, on the module's own level, and there is simply nothing
	 * bridging it to the glass: the staircase is disconnected. Three slice-by-slice derivations here
	 * failed to see it because they were all made against a descent, which has no such gap.
	 *
	 * <p>Two ways out, both ekran's, both built by hand and neither yet coded:
	 * <ul>
	 *   <li>Spend a cell: a second glass block with dust on it, level with the last of the three
	 *       bus-staircase rungs, plus dust on top of the head's high block. Costs one column.</li>
	 *   <li>Or start the chord a column later, so the handover lands on top of the head's high block
	 *       and reaches the glass by itself. Costs nothing, but the planner has to know the chord
	 *       begins a column along -- which is the same kind of agreement the parity nudge needs, and
	 *       is why it is the more interesting of the two.</li>
	 * </ul>
	 */
	static boolean HEAD_ONLY_NEAR_HALF = true;

	/**
	 * How a chord cut across a staircase divides when it opens with a stacked head.
	 *
	 * <p>Seven notes ride in the head for two columns, a cell carries the wire onto the bus, and
	 * what is left runs out as bus either side of the staircase. So the wire spends one cell on the
	 * transition, one a note pair, and the staircase -- and the seven in the head cost it nothing
	 * at all. That is why the head raises the ceiling rather than merely shortening the chord: a
	 * descent reaches {@code 7 + (15 - 1 - 4) * 2 = 27} notes and a climb, which keeps the off-bus
	 * discount and so pays three, reaches 29.</p>
	 *
	 * @param nearTail notes of the bus before the staircase. Never empty: the near half has to end
	 *     on a bus block for {@link #addSplitBusDescent} to be entitled to its short spiral, and a
	 *     head with no bus behind it ends on the transition cell instead.
	 * @param shed whether the near half is a head that gave up the flank the staircase wants and so
	 *     hands over on the staircase's own first rung instead of on a transition cell of its own.
	 *     One column shorter and one cell of wire cheaper than the same head without it -- see
	 *     {@link #SHEDS_THE_FLANK_THE_DESCENT_WANTS}. Carried on the record because the walk has to
	 *     build the shape the planner priced, and the two read this from the one oracle.
	 */
	private record StackedSplit(UltraSlots slots, List<EventNote> head, List<EventNote> nearTail,
			List<EventNote> farTail, boolean shed) {
		/**
		 * Cells of wire from the head's repeater to the far half, staircase included.
		 *
		 * <p>The two halves are counted <b>separately</b>, because they are built separately: each
		 * ends its own last cell, and a half holding an odd number of notes leaves that cell half
		 * empty rather than borrowing the other half's first note. Counting
		 * {@code (near + far + 1) / 2} is the same number whenever the near half fills whole cells,
		 * which every cut does except the one {@link #CUTS_A_CHORD_THAT_FITS} forces -- that one puts
		 * a single note in the far half deliberately, so both halves are odd and the sum is short by
		 * exactly one.</p>
		 *
		 * <p>One cell, and it is the difference between a machine and a wall. {@link BigSplitTest}
		 * at {@code f3 w20}: a run of <b>sixteen</b> from {@code 9 69 4} to {@code 20 66 4} and
		 * <b>3,606</b> note blocks the signal never reached, on a build whose every number read
		 * nought. Both the planner and the walk price a cut through this one method, so both were
		 * wrong by the same cell and neither could see it.</p>
		 */
		int runCells(int splitCells) {
			return (shed ? 0 : STACKED_BUS_TRANSITION)
				+ (nearTail.size() + 1) / 2 + (farTail.size() + 1) / 2 + splitCells;
		}

		/**
		 * Columns from the cell the module opens on to the cell the staircase starts on.
		 *
		 * <p>The head, its transition, and whatever the near tail fills. A shed head hands over on the
		 * staircase's own first rung and lays no transition of its own, which is one column as well as
		 * one cell of wire.</p>
		 *
		 * <p>What this is for: {@code room} minus this is how far short of the wall the staircase would
		 * stand. {@link #stackedSplitOf} sizes the near tail to fill the room it was given, so the
		 * difference is nought whenever the chord had enough notes left to fill it -- and where it did
		 * not, this is the recess. Derived rather than measured, so the walk counts
		 * {@code recessMispredicted} against the block it actually reaches.</p>
		 */
		int columns() {
			return STACKED_CELLS + (shed ? 0 : STACKED_BUS_TRANSITION) + (nearTail.size() + 1) / 2;
		}
	}

	/**
	 * Whether a chord that fits before the wall may still be cut across the staircase.
	 *
	 * <p>Asked only where the chord overshoots, and overshooting already counts the turn reserve --
	 * so this is the chord that fits but leaves the lane nothing to climb with. Every caller of
	 * {@link #stackedSplitOf} is behind that same guard, which is why the cut can be widened here
	 * without the planner and the walk parting company over it.</p>
	 * <p><b>On since 2026-08-11.</b> It was off, and the reason given was sound at the time: forcing
	 * the cut puts the last pair over the staircase, but the near half is then however long the chord
	 * happened to be rather than long enough to reach the wall, so the lane hands over short. Measured
	 * then at Guardian 44x3 breaches 3 -> 5. Both halves of that measurement have since moved -- the
	 * wall reach is priced through {@link #turnPrice} now
	 * ({@link #WALL_REACH_PRICES_THE_RAISED_PAD}) and {@link #PREPADS_FOR_THE_OFF_BUS_DISCOUNT} is
	 * gone -- so it was asked again:</p>
	 *
	 * <pre>
	 * eight songs x 60 configs   off  63 lanes / 369 blocks / worst 12   volume 17,279,975
	 *                            on   29 lanes / 138 blocks / worst 11   volume 17,272,603
	 * </pre>
	 *
	 * <p>Guardian alone 43 lanes / 229 blocks -> 20 / 86, and 40x4, 32x5, 24x4, 24x3, 20x2 and 16x2
	 * go to nought. {@code wrong=0} and {@code refused=0} both ways, {@code unreached=0} read back
	 * through {@link NoteMachineReader} at ekran's 40x5 across all eight songs and at the four
	 * Guardian configs this moves most. Builds are 101 columns longer over 480 of them and 7,372
	 * blocks smaller.</p>
	 *
	 * <p><b>Why it matters so much:</b> a chord of 24 is twelve cells, and a plain cut of it wants
	 * {@code 12 + splitCells} -- sixteen of a possible fifteen. Its only cut is a headed one. Refusing
	 * that cut because the chord fits without it means laying twelve cells whole, thirteen columns
	 * with the repeater, and any lane holding fewer than thirteen runs outside.</p>
	 *
	 * <p><b>Back off, and the breach numbers above are not the reason.</b> {@link BigSplitTest} reads
	 * the machine at {@code f3 w20} and finds <b>3,606 note blocks the signal never reaches</b> and a
	 * run of <b>sixteen</b> blocks of wire. The readback that said this was safe covered eight songs at
	 * 40x5 and four Guardian sizes; it did not cover the one config that builds nothing but chords too
	 * big to cut plain. {@code runCells} cannot see the fault, because the wire it counts is the
	 * transition, the pairs and the staircase -- and the columns between where the near half ends and
	 * where the staircase starts are covered with dust that nobody charged for.</p>
	 *
	 * <p><b>And the dead machine was not the near half at all.</b> It was one cell of arithmetic:
	 * {@code runCells} counted {@code (near + far + 1) / 2}, which is right whenever the near half
	 * fills whole cells and short by one when it does not -- and this flag is the only thing that ever
	 * leaves it half empty, because it puts a single note in the far half on purpose. Counting the two
	 * halves separately fixes it; see {@link StackedSplit#runCells}. The run at {@code f3 w20} goes
	 * back to fifteen.</p>
	 *
	 * <p>Paid, in v2, by {@link #CUT_PINS_ITS_STAIRCASE}.</p>
	 */
	static boolean CUTS_A_CHORD_THAT_FITS = true;

	/**
	 * v2: a cut lands its staircase on the wall, or it is not cut.
	 *
	 * <p>ekran's rule for the whole builder, and it is a rule rather than a preference: every climb,
	 * every descent and every flat turn stands at the wall, with no give at all for a staircase set
	 * back inside the corridor. A recessed turn occupies a column no other corridor's turn occupies,
	 * and that is the one thing that reaches into the lane alongside. The closing pad has been walked
	 * out to its wall since the {@code pinned-descents} branch ({@link #PIN_DESCENTS}); the cut never
	 * was, and v2 closes nearly every lane on a cut.</p>
	 *
	 * <p>It is a headed cut every time, and the shape says by how much before it is built --
	 * {@code room - }{@link StackedSplit#columns}. Measured across both songs at sixteen sizes each,
	 * every recessed column in either layout was a headed cut and every one was predicted exactly:
	 * 2,725 columns in v2, 425 in v1.</p>
	 *
	 * <p><b>In front of the module, not behind it.</b> Both put the same wire in the same corridor,
	 * but the two are spent from different budgets: a pad laid behind the near half comes out of the
	 * fifteen the cut opens with, which already carries the transition, both halves and the staircase,
	 * while a pad in front stands before the module's own repeater and comes out of the run the lane
	 * is already on. So the cut is simply asked for again in a room that much smaller -- same head,
	 * same halves, same wire, {@link StackedSplit#runCells} unchanged -- which is also what keeps the
	 * planner and the walk pricing one cut rather than two.</p>
	 *
	 * <p>Where the wire in front will not stretch, or the smaller room comes back with a shape that
	 * does not fill it either, the head is given up and the chord is laid whole. That is a breach the
	 * build already knows how to count, and it is the price of the rule.</p>
	 */
	static boolean CUT_PINS_ITS_STAIRCASE = true;

	/**
	 * Whether a cut that cannot pay for its pin in front may lay it behind the near half instead.
	 *
	 * <p>The same columns of the same corridor either way; what differs is which budget they come out
	 * of. In front of the module the pad stands before the cut's own repeater and is spent out of the
	 * run the lane was already on; behind the near half it is downstream of that repeater and comes out
	 * of the fifteen the cut opens with -- which already carries the transition, both halves and the
	 * staircase. Neither is reliably the affordable one: on a song of chords of twenty-five at a gap of
	 * one the lane in front is nearly spent, and on a cut of that size the fifteen is too.</p>
	 */
	static boolean CUT_PINS_BEHIND = true;

	/**
	 * Whether v2's walk still books pads ahead of itself, which is the last of the planner it kept.
	 *
	 * <p>v2 was opened on the claim that a lane which can always cut never has to be walked out to its
	 * wall, so there is nothing for a pad search to buy. The booking survived anyway, as a last resort:
	 * a cut is arithmetically always available under the 25-note cap, but the <em>head</em> it needs is
	 * not, and a refused head used to walk a lane nine columns past its wall.</p>
	 *
	 * <p>Here so the claim can be asked rather than assumed. The veto and the lookahead pair are
	 * already gone from v2; this is what is left of {@code planLane}.</p>
	 *
	 * <p><b>Off, ekran's call, 2026-08-13.</b> Guardian is better without it -- 35 breach blocks in 13
	 * lanes with, 25 in 11 without -- and Guardian is the benchmark. The all-25 song pays for it: 0
	 * with the booking and 56 blocks in 6 lanes without, three of them at 40 wide over five floors.</p>
	 *
	 * <p>Those 56 blocks are owed back, and the note above says where to look for them. Whatever the
	 * search is doing for that song it is <em>not</em> padding, because at a gap of one tick there is
	 * nowhere to put a pad: it is {@code owing} clamping where a chord may land, which is the walk
	 * choosing where a lane closes through a side door. Find that rule, state it in the cut decision
	 * where it belongs, and the booking has nothing left to do.</p>
	 */
	static boolean V2_BOOKS_PADS = false;

	/**
	 * v2: a chord that collides as a bus takes the next column rather than ending the build.
	 *
	 * <p>The other half of "one occupancy answer". A stacked shape is built in a trial and drops to a
	 * bus when it collides; a bus is where that road ends, so a bus that collides throws and the song
	 * will not paste at all. That is v2's last refusal -- Guardian twenty wide over four floors, a
	 * chord of three wanting a column a stacked module across the bend had taken, on a build whose
	 * every other number reads clean.</p>
	 *
	 * <p>What took the cell is a shape standing in front of this one, not a wall, so the column after
	 * it is free. Moving costs a cell of dust and no ticks, and the move is handed back as a nudge so
	 * the lane's arithmetic hears about the column it spent.</p>
	 *
	 * <p><b>It does not fix that refusal, and the reason is worth keeping.</b> The move fires -- three
	 * attempts, all three refused -- and it is the <em>pad</em> that is refused, not the module: the
	 * shape in the way is a stacked module that wrapped the bend, and it occupies the lane's own path
	 * on the arm coming out of the corner. Walking forward through an obstruction that is standing in
	 * the corridor cannot get round it. What that build needs is for the wrapped module's footprint on
	 * the outgoing arm to be somewhere the lane does not then walk, which is a rule about the bend and
	 * not about the bus.</p>
	 *
	 * <p>On because it is the right answer to the case it was written for -- a bus is the one shape
	 * with nothing to fall back to -- and because it costs nothing where it does not fire. It moved
	 * no build on either song, in either direction.</p>
	 */
	static boolean BUS_MOVES_OFF_A_COLLISION = true;

	/** How many columns a colliding bus may walk before the build gives up on it. */
	private static final int BUS_MOVES_AT_MOST = 3;

	/** Scratch: every column a colliding bus tried, and what it met there. */
	static boolean TRACE_BUS_MOVE = false;

	/** Scratch: the one-decision prediction against the one it replaces, chord by chord. */
	static boolean TRACE_ONE_DECISION = false;

	/**
	 * v2: a lane closes a chord early rather than take one that leaves it unable to turn.
	 *
	 * <p>The other half of deciding a chord's shape once. While {@link #landingOf} erred towards the
	 * bus, every lane carried a column or two of slack it never used, and that slack was what paid for
	 * the staircase at the end of it. Measuring honestly through {@link #shapeFor} spends the slack on
	 * music -- builds come out 2.6% shorter -- and the lane reaches its wall with the wire gone.</p>
	 *
	 * <p>v1's answer was {@code strandsNext}, inside the pad search: book a column so the lane can
	 * close. This is the same question without the search. A chord that would leave less wire than the
	 * staircase costs is treated as not fitting, so the lane closes on what it is already holding and
	 * the chord opens the next one. Nothing is booked and nothing is looked at beyond the chord in
	 * hand, which is what v2 is for.</p>
	 */
	static boolean STRANDED_LANE_CLOSES_EARLY = true;

	/**
	 * v2: a stacked module may not stand where its own lane is going to walk.
	 *
	 * <p>A stacked module hangs notes to the side of the path. Beside the path is a safe place for
	 * them only while the path is straight: through a bend the route comes back along the other arm,
	 * so a cell that was beside the module when it was placed is a cell the lane runs down afterwards.
	 * The wire then arrives at a note block with nowhere to go, and the build refuses -- Guardian
	 * twenty wide over four floors, a chord of three meeting a {@code STACKED_FULL+nudge} at
	 * {@code -1 76 73}, on a build whose every other number reads clean.</p>
	 *
	 * <p>It cannot be moved out of the way afterwards. The obstruction stands <em>in</em> the corridor
	 * rather than beside it, so every column the following chord tries is refused at the same cell --
	 * measured, four attempts, all four at {@code -1 76 73}. The shape has to not go there.</p>
	 *
	 * <p>This is what {@code inTurn} is standing in for, and why relaxing it by distance was not
	 * enough: three columns from a corner is far enough for the module and not far enough for what the
	 * route does next. Asked rather than guessed -- the module is built in the trial it was already
	 * being built in, and then asked whether anything it wrote landed on the route ahead. A shape that
	 * did gives the head up and is laid as a bus, which is what a refused stacked shape has always
	 * done.</p>
	 */
	static boolean STACKED_KEEPS_OFF_THE_ROUTE = true;

	/**
	 * Whether a stacked shape that hung a note somewhere else sounds gives itself up for a bus.
	 *
	 * <p>Off, {@code onTheFreeSlots} rearranges the low notes onto quiet cells where it can and hands
	 * the slots back untouched where it cannot -- which puts the note in the noisy cell after all. A
	 * rigid shape has no other move; a bus grows until it has somewhere for every note, so falling
	 * back is the move.</p>
	 *
	 * <p>Asked after the shape is built, like {@link #STACKED_KEEPS_OFF_THE_ROUTE}, because a
	 * module's low notes are decided against ground the module has not finished occupying. The cost
	 * is columns -- a bus is longer than the shape it replaces -- and the census is where to read it.</p>
	 */
	static boolean STACKED_GIVES_UP_A_NOISY_SLOT = true;

	/**
	 * Whether a module that clashes in both columns is offered a relocation in the second one.
	 *
	 * <p>ekran's ordering, and what it fixes is that the bus was the third thing tried rather than the
	 * last. {@link #parityVerdict} offers a relocation where the module stands, then a shift into the
	 * next column -- and if the shifted column clashes too, it gives the shape up. It never asks
	 * whether the shifted column could be settled by moving a note, though moving into a column is
	 * exactly what makes that likely: a different neighbour is alongside, so a different slot is in
	 * contention.</p>
	 *
	 * <p>Every verdict but the last keeps the shape. The fallback throws it away and spends four cells
	 * of lane on the bus that replaces it, so it is what is left when everything else has been tried.
	 * This is one of the things that was missing between "everything else" and "the bus".</p>
	 */
	static boolean RELOCATES_AFTER_THE_SHIFT = true;

	/**
	 * How far down the route a module is asked to keep off, in columns.
	 *
	 * <p>Fifteen, which is what a repeater reaches and so as far as the lane can get before something
	 * stands in it anyway. Counted from where this module hands over, so the module's own cells are
	 * never in the set -- a module occupies the path it stands on by definition, and asking it to keep
	 * off that would refuse every shape in the build.</p>
	 */
	private static final int ROUTE_LOOKS_AHEAD = DUST_RANGE;

	/**
	 * The cells the route runs through from here, both the path and the wire above it.
	 *
	 * <p>Read off the lane rather than worked out, so it follows the bends the turn armed. That is the
	 * whole point: on a straight lane this is a line, and through a turn it is the shape that makes a
	 * module beside the path a module in the way.</p>
	 */
	private static Set<BlockPos> routeAhead(Lane lane, int columns) {
		Set<BlockPos> cells = new HashSet<>();
		Lane at = lane;
		for (int column = 0; column <= columns; column++) {
			cells.add(at.pos().immutable());
			cells.add(at.pos().above().immutable());
			at = at.ahead(1);
		}
		return cells;
	}

	private static StackedSplit stackedSplitOf(List<EventNote> chord, int room, int splitCells,
			boolean climbing, boolean roomBehind) {
		return stackedSplitOf(chord, room, splitCells, climbing, roomBehind, true);
	}

	/**
	 * @param stackedBehind whether what stands behind is another stacked module's centre. Then both
	 *     slots behind are gone rather than one, and a head that keeps one of them is not a smaller
	 *     head -- it is a head with a note in somebody else's cell. ekran, standing at {@code 6 72 51}:
	 *     the first rule of stacked chords is that if the block behind is the centre of another
	 *     stacked chord you cannot place back flanks at all, because that chord would sound them.
	 */
	/**
	 * Why the last call to {@link #stackedSplitOf} came back with nothing.
	 *
	 * <p>A headed cut is how v2 closes a lane on a chord too big to cut plain, and when it is refused
	 * the chord is laid whole and the lane walks out past its wall. The trace could say
	 * {@code headed=no} and nothing else, and the four ways to get there want four different fixes:
	 * a chord whose notes will not fill any head, a corridor with no room for the near half, a chord
	 * with nothing left to carry over, and a cut that simply runs out of wire.</p>
	 *
	 * <p>Set at each refusal and read by the walk, so there is one place deciding and one place
	 * reporting rather than a second copy of the arithmetic that can drift from this one.</p>
	 */
	static String LAST_CUT_REFUSAL = "";

	private static StackedSplit stackedSplitOf(List<EventNote> chord, int room, int splitCells,
			boolean climbing, boolean roomBehind, boolean stackedBehind) {
		LAST_CUT_REFUSAL = "";
		if (!STACKED_SPLIT_HEADS) {
			LAST_CUT_REFUSAL = "SwitchedOff";
			return null;
		}
		// The full head first, the head of five when the column behind is spoken for. This is the
		// same fallback an ordinary chord has had since 1da9154 -- that commit taught chordStyle,
		// landingOf and addChordModule to drop to a head of five rather than give the shape up, and
		// left the cut asking for the back flanks unconditionally. So a chord that could have been
		// cut with a short head was laid as a plain bus instead, and illit-do-the-dance at twelve
		// wide lost its lane five columns past the wall for exactly that.
		StackedBusSplit split = roomBehind ? stackedBusSplit(chord, 2) : null;
		// How many of the back pair the head that came back was granted, which is not the same as how
		// many it filled. {@link #shedDescentFlank} needs the grant: a back slot empty because the
		// lane behind owns it is not a slot this chord may rehome a note into.
		int granted = split == null ? 0 : 2;
		// One slot behind before none, here as in {@link #addChordModule}: the lane behind has a
		// single live cell in the column that touches this one, so the most it can ever contest is
		// one, and a head of six keeps two more notes out of the bus than a head of five.
		// One rule, and it is not about cutting. How many slots behind a head can use is a question
		// about the ground beside it, so the answer cannot depend on whether the chord is going to be
		// split across a staircase -- gating it on the cut is telling the builder not to optimise in
		// the one case it most needs to. ekran said so; it was two flags because it was bolted onto
		// two code paths separately, which is the mistake this file keeps making.
		// Keeping one of the two is only a shape at all where only one of the two is spoken for. The
		// file's own invariant says that is the usual case -- the lane alongside has a single live cell
		// in the column that touches this one, so it lines up with one slot and never with both -- but
		// a stacked centre behind is the exception, and there the fallback from a full head is five.
		if (split == null && HEAD_KEEPS_ONE_BACK_FLANK && !stackedBehind) {
			split = stackedBusSplit(chord, 1);
			granted = split == null ? 0 : 1;
		}
		if (split == null && FRONT_ONLY_HEADS && FRONT_ONLY_CUTS) {
			split = stackedBusSplit(chord, 0);
			granted = 0;
		}
		if (split == null) {
			// Told apart by what was on offer. A chord refused a head with every slot available is a
			// chord whose notes will not make one; a chord refused only because the column behind is
			// spoken for is a chord that would have cut somewhere else in the lane.
			LAST_CUT_REFUSAL = stackedBusSplit(chord, 2) == null ? "NoHeadAtAll" : "NoHeadFromTheFront";
			return null;
		}
		// Head, transition, and -- unless the near half is allowed to be the head alone -- at least
		// one cell of bus to end on.
		int nearBusCells = room - STACKED_CELLS - STACKED_BUS_TRANSITION;
		int floorCells = HEAD_ONLY_NEAR_HALF && !climbing ? 0 : 1;
		// One column short of a head-only near half, and going down: then the head may hand over onto
		// the staircase's own first rung rather than onto a transition cell of its own, which is the
		// same two blocks in the same place. It costs the flank the second rung wants -- rehomed, not
		// dropped -- and it buys the column the lane could not otherwise reach its wall in.
		//
		// Descents only, and not merely because a climb's staircase is shaped differently. A climb
		// leaves by glass a level up and a column over, so there is no rung standing where the
		// transition stands and nothing for the centre to light. That is the same gap that keeps
		// {@link #HEAD_ONLY_NEAR_HALF} to descents, and it is the same reason.
		boolean shed = false;
		if (SHEDS_THE_FLANK_THE_DESCENT_WANTS && !climbing && HEAD_ONLY_NEAR_HALF
				&& nearBusCells == floorCells - 1) {
			ShedFlank rehomed = shedDescentFlank(split.slots(), granted, !split.tail().isEmpty());
			if (rehomed != null) {
				List<EventNote> head = split.head();
				List<EventNote> tail = split.tail();
				if (rehomed.toBus() != null) {
					head = new ArrayList<>(head);
					head.remove(rehomed.toBus());
					tail = new ArrayList<>(tail);
					tail.add(rehomed.toBus());
				}
				split = new StackedBusSplit(rehomed.slots(), head, tail);
				nearBusCells = floorCells;
				shed = true;
			}
		}
		// Descents only for now. A climb leaves the near half by a glass staircase whose first rung
		// is a level up and a column over, and the handover -- which sits beside the centre on the
		// module's own level -- has nothing bridging it to that rung. Ekran pasted one and found the
		// glass simply disconnected. A descent has no such gap and works today.
		if (nearBusCells < floorCells) {
			if (nearBusCells == 0 && !split.tail().isEmpty()
					&& STACKED_BUS_TRANSITION + (split.tail().size() + 1) / 2 + splitCells
						<= DUST_RANGE) {
				HEAD_ONLY_NEAR_HALVES++;
			}
			LAST_CUT_REFUSAL = "NoRoomForTheNearHalf";
			return null;
		}
		List<EventNote> tail = split.tail();
		int nearNotes = Math.min(tail.size(), 2 * nearBusCells);
		// Fitting before the wall is not the same as being able to leave.
		//
		// This used to read "nothing to carry over the staircase means this was never a chord that
		// had to be cut", and it is asked only where the chord already overshoots -- and overshooting
		// counts the turn reserve, so the chord that reaches this line does not fit *with its
		// staircase*, however comfortably it fits without one. Refusing the cut there lays it whole,
		// and the lane then discovers it has no wire left to climb with and runs on. ekran read one
		// as a breach of eleven: a stacked-bus that ended without the power to reach the top.
		//
		// So the last pair goes over the staircase deliberately. A cut spends one repeater on the
		// whole chord -- transition, near cells, the staircase and the far cells, which the wire
		// check below still holds to fifteen -- and the far half opens past the climb with a fresh
		// fifteen behind it. That is the difference between a lane that ends on its wall and a lane
		// that cannot turn at all.
		if (nearNotes >= tail.size()) {
			if (!CUTS_A_CHORD_THAT_FITS || tail.size() < 2) {
				LAST_CUT_REFUSAL = "NothingToCarryOver";
				return null;
			}
			// One note over the staircase, and not two.
			//
			// Two is arithmetically better and does not work. A tail of twenty divided 19/1 leaves
			// both halves odd, so each ends on a half-empty cell and the run is
			// 1 + 10 + 1 + 4 = 16 -- refused. Divided 18/2 both halves are even and it is fifteen, so
			// every chord of 27 would cut instead of falling through to the shed, and on a song of
			// nothing but 27s the breaches did go from 54 blocks to none.
			//
			// And the machine died: 5,064 note blocks the signal never reached at 40w x 5f, where the
			// same build with one note over reads nought. Breach counts cannot see that, which is why
			// the change looked like a fix. Whatever the far half's first cell shares with the
			// staircase's last rung, it is not what {@code runCells} models, and until that is read off
			// the blocks a second note over the staircase is not safe to lay.
			nearNotes = tail.size() - 1;
		}
		// The transition cell drops out of the run as well as out of the columns when the head hands
		// over onto the staircase, because the cell it would have laid is a rung the staircase lays
		// anyway and {@code splitCells} already counts every rung. So a shed cut reaches one cell
		// further than the same cut without it. Stated through the record rather than here, because
		// the walk makes this same sum for {@code tipSignal} and the two must not drift apart.
		StackedSplit cut = new StackedSplit(split.slots(), split.head(), tail.subList(0, nearNotes),
			tail.subList(nearNotes, tail.size()), shed);
		// One cell over, and the head still has a flank the staircase wants: then shedding it is worth
		// the whole cut. The shed hands over on the staircase's own first rung instead of on a
		// transition cell, so it gives the run that cell back -- and the note it displaces goes to the
		// bus, where it pairs into whichever half was ending on a half-empty cell.
		//
		// The block above sheds too, but only where {@code nearBusCells == floorCells - 1}, which for
		// a descent is {@code room == 2} exactly. That is the shed as a *room* saving. This is the same
		// shed as a *wire* saving, and it is worth its own attempt because the two want it at opposite
		// ends: one where the corridor is too tight to hold the near half, the other where the corridor
		// is roomy and the fifteen is what ran out.
		//
		// ekran's arithmetic, for a chord of 28 going down: six in the head once the flank is shed,
		// twenty-two left for the bus at eleven cells, no transition, and a staircase of four -- which
		// is fifteen exactly. Measured before this, descents cut 43 chords of 27 and only 5 of 28.
		if (SHED_BUYS_THE_LAST_CELL && !shed && !climbing && HEAD_ONLY_NEAR_HALF
				&& cut.runCells(splitCells) == DUST_RANGE + STACKED_BUS_TRANSITION) {
			ShedFlank rehomed = shedDescentFlank(split.slots(), granted, !tail.isEmpty());
			if (rehomed != null) {
				List<EventNote> shedHead = split.head();
				List<EventNote> shedTail = tail;
				if (rehomed.toBus() != null) {
					shedHead = new ArrayList<>(shedHead);
					shedHead.remove(rehomed.toBus());
					shedTail = new ArrayList<>(shedTail);
					shedTail.add(rehomed.toBus());
				}
				// Head only, and the whole bus below the staircase. Not a choice: a shed cut is built
				// by {@link #addStackedSplitModule} as the head and nothing else -- it lays the module,
				// counts the handover and returns -- so a near half with notes in it is a near half
				// whose notes are planned and never placed. Two snares went missing at {@code f2 w36}
				// that way, built 1 and read 0, before this line said nought.
				//
				// It is also what the arithmetic wanted. ekran, for a chord of 28: six in the head once
				// the flank is shed and twenty-two left "at the bottom" -- below the descent, not before
				// it -- which is eleven cells, no transition, and a staircase of four. Fifteen.
				int shedNear = 0;
				if (!shedTail.isEmpty()) {
					StackedSplit shedCut = new StackedSplit(rehomed.slots(), shedHead,
						shedTail.subList(0, shedNear), shedTail.subList(shedNear, shedTail.size()),
						true);
					if (shedCut.runCells(splitCells) <= DUST_RANGE) {
						SHED_BOUGHT_THE_CELL++;
						SHED_BOUGHT_BY_SIZE.merge(chord.size(), 1, Integer::sum);
						return shedCut;
					}
				}
			}
		}
		if (cut.runCells(splitCells) > DUST_RANGE) {
			// By how much, because one cell over is a shape question and five over is a chord that was
			// never going to be cut here.
			LAST_CUT_REFUSAL = "OutOfWireBy"
				+ Math.min(cut.runCells(splitCells) - DUST_RANGE, 6);
			return null;
		}
		return cut;
	}

	/**
	 * Scratch: colour every headed cut in deepslate tiles, even one whose tail never crossed.
	 *
	 * <p>Off, which is the rule ekran asked for -- tiles mean the head stands on one side of the
	 * staircase and tail on the other, not merely that a cut opened with a head.</p>
	 *
	 * <p>It has never yet made a difference, and the flag is here because of what that turned out to
	 * mean. {@link CutCrossesProbe} builds the whole library both ways, 244 builds over four widths,
	 * and the two agree on every block: <b>a headed cut always crosses.</b> Which follows once it is
	 * said out loud -- a cut exists because the chord did not fit before the wall, so a chord whose
	 * tail stays on the near side is not cut at all, it is simply built, and the walk never arrives
	 * at {@link #addStackedSplitModule}. That invariant is stated nowhere else in this file, and the
	 * branch is cheap insurance for the day something makes it false.</p>
	 */
	static boolean TILES_EVERY_HEADED_CUT = false;

	/** The near half of a cut chord, built as a stacked head with a bus behind it. */
	private static BlockPos addStackedSplitModule(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction laneStep, int triggerDelay, StackedSplit split, int time) {
		// Deepslate tiles are for a cut that actually is one: the head standing on this side of the
		// staircase and tail on the other. Where the far half came out empty nothing crossed, and
		// what got built is a stacked bus that happens to stand at a cut -- so it says that instead,
		// and takes the stacked bus's own colour rather than announcing a crossing that never
		// happened. ekran, who has to tell the two apart standing in front of them.
		placements.placing((split.farTail().isEmpty() && !TILES_EVERY_HEADED_CUT
				? "chord:STACKED_BUS atCut " : "cutHead")
			+ split.head().size()
			+ "/near" + split.nearTail().size() + "/far" + split.farTail().size()
			+ (split.slots().backFlanks() == 0 ? " frontOnly" : " reachesBack")
			+ (split.shed() ? " shedFlank" : ""));
		if (split.shed()) {
			// No transition cell and no column for it. The head is laid and handed straight back at
			// the column it came to rest in, which is the column the staircase starts in -- and the
			// staircase's own first rung is the two blocks the transition would have been.
			Lane afterHead = addStackedEventModule(placements,
				Lane.straight(cursor, travel, laneStep), triggerDelay, time, split.slots());
			placements.padded("busHandoverShed");
			return afterHead.pos();
		}
		// Never the simple tail here. This near half is pinned flush to its wall, and its length is
		// summed against a wire budget in {@link #stackedSplitOf} and {@link #closes} rather than as
		// columns -- so a tail that quietly came out a column shorter would move the staircase and
		// not the sum. That is the shape of every regression this file has produced.
		Body body = addStackedBusModule(placements, Lane.straight(cursor, travel, laneStep),
			triggerDelay, time, split.slots(), split.nearTail(), false);
		return body.lane().pos();
	}

	/** A stacked head and the notes left over for its bus. */
	private record StackedBusSplit(UltraSlots slots, List<EventNote> head, List<EventNote> tail) {
	}

	/**
	 * Picks the seven notes that go in the head and leaves the rest for the bus.
	 *
	 * <p>The head is the fussy half: at most two notes whose instrument block falls, two that will
	 * pass power sideways, and a harp for the centre. The bus minds none of that -- it will hang
	 * anything anywhere down its length -- so every note the head cannot take is simply a note the
	 * bus takes instead, and the split is never a reason to give the shape up.</p>
	 *
	 * <p>Tried twice: once in the order the chord arrived, and once with the notes the head is
	 * short of moved to the front. A chord whose only harp is its last note would otherwise lose
	 * the centre, and the centre is the seventh note.</p>
	 */
	private static StackedBusSplit stackedBusSplit(List<EventNote> chord, boolean reachingBack) {
		return stackedBusSplit(chord, reachingBack ? 2 : 0);
	}

	private static StackedBusSplit stackedBusSplit(List<EventNote> chord, int backFlanks) {
		int hangers = 4 + backFlanks;
		int largest = Math.min(STACKED_HEAD_NOTES, hangers + 1);
		// Harps and sideways conductors first, because those are the slots that can go unfilled.
		List<EventNote> preferred = new ArrayList<>(chord);
		preferred.sort(Comparator.comparingInt(note ->
			FALLING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock()) ? 1
				: isHarpNote(note) || conductsSideways(note) ? 0 : 2));
		// Down from the biggest head the chord could fill rather than only at it: a chord that cannot
		// fill the centre has no reason to give up the hangers along with it, and one that cannot
		// reach back has no reason to give up the front.
		int smallest = VARIABLE_HEAD_NOTES ? MIN_STACKED_HEAD_NOTES : largest;
		for (int headSize = largest; headSize >= smallest; headSize--) {
			StackedBusSplit straight = splitAt(chord, headSize, backFlanks);
			if (straight != null) {
				return straight;
			}
			StackedBusSplit sorted = splitAt(preferred, headSize, backFlanks);
			if (sorted != null) {
				return sorted;
			}
		}
		return null;
	}

	/**
	 * The smallest head worth building.
	 *
	 * <p>Five, and the reason is that a head of four is a bus wearing a different shape. Four notes
	 * is two relays and two low notes over two columns -- two notes a column, which is exactly what
	 * a bus carries -- and the head then pays a transition cell on top. Three columns for four notes
	 * either way, except that the stacked one also has to keep parity with the lane behind it.</p>
	 *
	 * <p>So a head earns its place only by using something a bus has not got: the centre block, or
	 * the pair of slots behind. Five is the first size that uses either. Ekran caught this after the
	 * loop had already been written down to four.</p>
	 */
	private static final int MIN_STACKED_HEAD_NOTES = 5;

	private static StackedBusSplit splitAt(List<EventNote> chord, int headSize, int backFlanks) {
		List<EventNote> head = new ArrayList<>();
		List<EventNote> tail = new ArrayList<>();
		int falling = 0;
		for (EventNote note : chord) {
			boolean sinks = FALLING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock());
			if (head.size() < headSize && !(sinks && falling >= 2)) {
				head.add(note);
				if (sinks) {
					falling++;
				}
			} else {
				tail.add(note);
			}
		}
		UltraSlots slots = ultraSlots(head, backFlanks);
		return slots == null || tail.isEmpty() ? null : new StackedBusSplit(slots, head, tail);
	}

	/**
	 * A stacked head with the rest of the chord run out beside it as a bus.
	 *
	 * <p>Built as the two shapes it is made of, joined by one cell: {@link #addStackedEventModule}
	 * lays the head and comes to rest two columns along, a cell of dust goes in the third where the
	 * centre can light it, and the bus starts in the fourth a level higher, which is where a bus
	 * always runs. Nothing here is new redstone -- it is the module's existing hand-over with a run
	 * of bus in place of the repeater that used to take it.</p>
	 */
	private static Body addStackedBusModule(PlacementPlan placements, Lane lane, int triggerDelay,
			int time, UltraSlots slots, List<EventNote> tail) {
		return addStackedBusModule(placements, lane, triggerDelay, time, slots, tail, true);
	}

	/**
	 * @param mayGoSimple whether a short tail may take the simple chord shape rather than a bus.
	 *     False on the cut path and true everywhere else. A cut's near half is pinned flush to its
	 *     wall -- that pinning is what put every staircase back on its wall -- and its length is
	 *     summed in {@link #stackedSplitOf} and {@link #closes} against a wire budget rather than a
	 *     column count. A tail that quietly came out a column shorter there would move the staircase
	 *     and not the sum, which is a lane measured for one shape and built as another.
	 */
	private static Body addStackedBusModule(PlacementPlan placements, Lane lane, int triggerDelay,
			int time, UltraSlots slots, List<EventNote> tail, boolean mayGoSimple) {
		Lane afterHead = addStackedEventModule(placements, lane, triggerDelay, time, slots);
		// The cell the centre lights. Stone with dust over it, in the column the head came to rest
		// in -- beside the centre and level with it, never above, because above the centre is the
		// air a note block there insists on.
		// A short tail as a simple chord, in the column the bus would have opened in and a level
		// lower. ekran's: the handover sits at the height of the lane rather than raised, so a middle
		// standing right there is driven by it, where a bus cell has to be a level up. A tail of
		// three is two cells of bus and one column of this.
		boolean simple = mayGoSimple && placements.answersWhatIsAhead() && simpleTail(tail)
			&& !(SIMPLE_TAIL_GIVES_WAY_TO_A_TIGHT_GAP && noteBlockInTheMiddle(tail)
				&& (NOTE_BLOCK_MIDDLE_ALWAYS_BUSES
					|| placements.gapAhead() <= SIMPLE_TAIL_KEEPS_A_NOTE_MIDDLE_ABOVE));
		if (mayGoSimple && simpleTail(tail) && !simple) {
			placements.padded("simpleTailBusedForATightGap");
		}
		// The handover is dust over stone either way, but its *shape* is not the same either way.
		//
		// Dust with nothing beside it to join takes the dot shape, and a dot powers only the block
		// beneath it -- it lights the stone under it, which is what a floor rail would read, and
		// nothing in front. A bus tail hides that completely: the bus stone stands in the very next
		// cell and joins the dust into a line. A simple tail's middle is a cell the dust does not
		// connect to, so left to itself the handover collapses to a dot and everything downstream of
		// it goes silent -- 4,767 notes on all of the lights, read off the slice by ekran. Naming the
		// four sides makes it a cross and a cross stays one, which is the same reason
		// {@link #STACKED_CROSS} is stated rather than left to the game.
		// Named before the handover goes down rather than after, so the column that hands over to a
		// simple tail wears the tail's own colour in a debug paste. The two are one shape to look at:
		// the tail is a single column and the handover is what drives it, and colouring only the far
		// half of that leaves the thing you are trying to see cut in two.
		if (simple) {
			placements.placing("chord:STACKED_BUS+simpleTail" + tail.size() + " handover");
		}
		// From here until the tail is down, so that whatever is laid after it can put it back. Only a
		// middle that is a note block is worth recording -- a stone middle carries its own dust and
		// was never at risk -- and only the shape a bus of the same notes would start in the same
		// column, which is what makes the swap free. See PlacementPlan.SoftTail.
		if (simple && noteBlockInTheMiddle(tail) && SIMPLE_TAIL_UNDONE_FOR_A_PAD) {
			placements.padded("softTailRecorded");
			placements.beginSoftTail(new PlacementPlan.SoftTail(afterHead.ahead(1).above(),
				List.copyOf(tail), time, afterHead.pos().above()));
		}
		set(placements, afterHead.pos(), "minecraft:stone");
		set(placements, afterHead.pos().above(), simple ? STACKED_CROSS : "minecraft:redstone_wire");
		// Said out loud, because these two blocks are also what a corner is and what a cell of pad is,
		// and the rail's floor seed was telling them apart by looking at them.
		placements.handover(afterHead.pos());
		// Counted as a hand-over and not as pad, which is what it used to be called. Pad is a column
		// a chord could have been standing in; this cell stands where the shape's repeater would
		// have stood, so a chord of twenty is eleven columns as a plain bus and ten as a stacked-bus
		// with this included. Calling it pad made the shape that saves a column look like the one
		// that spends seventy-eight thousand of them, and lumped it in with the parity pad -- which
		// is a real column given up, and a different thing entirely.
		placements.padded("busHandover");
		if (tail.isEmpty()) {
			return new Body(afterHead.ahead(1), 0);
		}
		Lane tailAt = afterHead.ahead(1);
		if (simple && (!repeaterComesOutOf(tailAt) || placements.turnAhead())) {
			// The one scenario that falls back, and only this one. A simple tail's middle is soft
			// powered -- lit by the handover's dust and nothing else -- which drives a repeater
			// standing against it and cannot light dust. So where the route bends and the corner
			// padding walks the next repeater off this line, the tail has to be a bus cell instead,
			// which carries its own dust and hands on the ordinary way. It costs nothing but a couple
			// more blocks.
			placements.padded("simpleTailBusedForTheCorner");
			simple = false;
			// And the recorder goes with it. It is opened before the handover, which is before this
			// decision can be taken back, so a tail that ends up a bus would otherwise be recorded as
			// one that could be undone -- and the journal would go on recording into the bus itself.
			placements.discardSoftTail();
		}
		if (simple) {
			BlockPos middle = tailAt.pos().above();
			List<EventNote> hanging = new ArrayList<>(tail);
			// The middle, by the same rule a stacked centre and a rail's path column use. A harp takes
			// it outright -- the middle has no instrument block of its own and sounds harp whatever was
			// meant, so a harp is the one note that loses nothing there, and it leaves the cell below
			// free for a repeater out of the handover.
			//
			// With no harp: stone where a run may want to quick-start off this, the notes on the sides.
			// Taken whenever it is free, which is every tail of two or fewer since the two sides hold
			// them all. Only a tail of three with no harp has to choose, and it keeps the note.
			// Asked only where the middle is actually needed to hold a note. A harp is the one note that
			// can stand in the middle -- the cell has no instrument block under it and sounds harp
			// whatever was meant -- but taking it there is only worth anything when the two sides cannot
			// hold the tail on their own, which is a tail of three. At one or two the sides hold all of
			// it, and the harp loses nothing by sitting on one: a harp is built over air there too.
			//
			// And what it buys is the whole invariant. A harp in the middle is a note block, which
			// insists on air above it and so cannot carry the dust that makes this tail safe. Leaving
			// the middle to stone makes every tail of two or fewer dustable, whatever it is made of.
			EventNote harp = HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE && hanging.size() <= 2
				? null : takeHarpNote(hanging);
			boolean railReady = harp != null || hanging.size() <= 2;
			// The one middle that is a block rather than a note block, and so the one that can have
			// something set on top of it. See {@link #SIMPLE_TAIL_CARRIES_ITS_OWN_DUST}.
			boolean stoneMiddle = harp == null && hanging.size() <= 2;
			placements.placing("chord:STACKED_BUS+simpleTail" + tail.size()
				+ (harp != null ? " harpMiddle" : railReady ? " stoneMiddle" : " noteMiddle"));
			placements.padded("simpleTail" + (harp != null ? "Harp"
				: railReady ? "Stone" : "NoteInTheMiddle"));
			if (harp != null) {
				placeNoteBlock(placements, middle, harp);
				placements.powered(middle, time);
			} else if (railReady) {
				placements.powered(middle, "minecraft:stone", time);
			} else {
				placeNote(placements, middle, hanging.removeFirst());
				placements.powered(middle, time);
			}
			Direction laneStep = tailAt.noteSide();
			List<Direction> sides = List.of(laneStep.getOpposite(), laneStep);
			for (int side = 0; side < sides.size() && side < hanging.size(); side++) {
				placeNote(placements, middle.relative(sides.get(side)), hanging.get(side));
			}
			// A stone middle carries its own dust and is not a soft tip at all. A note-block middle has
			// nowhere to put it and is, so it says so for whatever is laid next: it drives a repeater
			// standing against it and anything else against it dies. See
			// {@link #STONE_MIDDLE_CARRIES_ITS_OWN_DUST}.
			boolean dusted = STONE_MIDDLE_CARRIES_ITS_OWN_DUST && stoneMiddle;
			if (dusted) {
				set(placements, middle.above(), "minecraft:redstone_wire");
				placements.padded("stoneMiddleDusted");
			}
			placements.softTip(!dusted);
			// And where the middle left the cell below it free -- a harp or a stone, never a note with
			// an instrument block under it -- this column is a path rail's first slot, already laid.
			if (railReady) {
				placements.railTail(tailAt.pos());
			}
			placements.endSoftTail();
			// One column either way. The number beside it is how much dust the lane is still carrying:
			// a dusted middle lays the one cell it just set, which is the same one a bus tail of this
			// length would have reported, and a note-block middle lays none.
			return new Body(afterHead.ahead(2), dusted ? 1 : 0);
		}
		int cells = layBus(placements, afterHead.ahead(1).above(), tail, time);
		return new Body(afterHead.ahead(1 + cells), cells);
	}

	private static Lane addStackedEventModule(PlacementPlan placements, Lane lane,
			int triggerDelay, int time, UltraSlots slots) {
		lane = pastAnyCorner(placements, lane);
		BlockPos cursor = lane.pos();
		Direction travel = lane.travel();
		Direction across = lane.noteSide();
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		BlockPos centre = cursor.relative(travel).above();
		BlockPos cross = centre.below();
		set(placements, cross.below(), UNDERFLOOR);
		set(placements, cross, STACKED_CROSS);
		if (slots.centre() == null) {
			set(placements, centre, "minecraft:stone");
		} else {
			placeNoteBlock(placements, centre, slots.centre());
		}
		// Strongly powered by the repeater, whether it is stone or a note block: either way it
		// sounds the two beside it and lights the dust underneath. A note block is a full block.
		placements.powered(centre, time);
		List<Direction> sides = List.of(across, across.getOpposite());
		for (int side = 0; side < sides.size(); side++) {
			Direction out = sides.get(side);
			EventNote relay = slots.sides().get(side);
			BlockPos instrument = cross.relative(out);
			placements.powered(instrument, conductingInstrumentBlock(relay), time);
			if (FALLING_INSTRUMENT_BLOCKS.contains(relay.instrumentBlock())) {
				placements.support(instrument.below(), UNDERFLOOR);
			}
			placeNoteBlock(placements, centre.relative(out), relay);
			// The four low notes, which are the ones sitting at the lane's own floor level.
			if (slots.front(side) != null) {
				placeNote(placements, instrument.relative(travel), slots.front(side), true);
			}
			if (slots.back(side) != null) {
				placeNote(placements, instrument.relative(travel.getOpposite()), slots.back(side),
					true);
			}
		}
		return lane.ahead(2);
	}

	/**
	 * The one piece of dust in any build whose shape is stated rather than left to the game.
	 *
	 * <p>Dust placed with nothing to join takes the dot shape, and a dot powers only the block
	 * beneath it -- which here is the floor, and nothing else. This dust is walled in on all four
	 * sides by the two instrument blocks it has to reach and the two blocks holding up the path, so
	 * left to itself it would land as a dot and the module would go silent. Naming the four sides
	 * makes it a cross, and a cross stays one through every later block update: only dust that was
	 * already a dot is allowed to remain one.</p>
	 */
	private static final String STACKED_CROSS =
		"minecraft:redstone_wire[north=side,east=side,south=side,west=side]";

	/** The note in each slot of a stacked module; {@code centre} is null when the chord fits without it. */
	private record UltraSlots(EventNote centre, List<EventNote> sides, List<EventNote> front,
			List<EventNote> back) {
		/**
		 * The low note hanging at the far end of the outer column on this side, or {@code null}.
		 *
		 * <p>Both lists are always two long and may hold nulls, because which side a flank falls on
		 * is now a decision rather than a count. A module may keep the flank the lane behind does not
		 * mind and drop only the one it does, which a dense list cannot say.</p>
		 */
		EventNote front(int side) {
			return front.get(side);
		}

		EventNote back(int side) {
			return back.get(side);
		}

		int backFlanks() {
			return (back.get(0) == null ? 0 : 1) + (back.get(1) == null ? 0 : 1);
		}

		/**
		 * The four low slots under one numbering: 0 and 1 the front pair, 2 and 3 the back.
		 *
		 * <p>A number rather than a description because the planner and the walk both have to name
		 * the same slot, and a number is the one thing they cannot say differently. Which one is in
		 * contention is never more than one of the four: the lane behind has exactly one live cell in
		 * the column that touches this one -- its own relay -- so it can line up with this module's
		 * front pair or its back pair, and never with both.</p>
		 */
		EventNote slot(int index) {
			return index < 2 ? front.get(index) : back.get(index - 2);
		}

		/** The same module with one low slot left empty, for the caller to rehome its note. */
		UltraSlots without(int index) {
			return new UltraSlots(centre, sides,
				index < 2 ? leaving(front, index) : front,
				index < 2 ? back : leaving(back, index - 2));
		}

		/** The same module with its back pair the other way round. Same size, same columns. */
		UltraSlots mirroredBack() {
			return new UltraSlots(centre, sides, front,
				java.util.Collections.unmodifiableList(
					java.util.Arrays.asList(back.get(1), back.get(0))));
		}

		/** The same module with a note in the centre, which is where a rehomed harp goes for free. */
		UltraSlots withCentre(EventNote note) {
			return new UltraSlots(note, sides, front, back);
		}

		/** The same module with one low slot holding a different note. */
		UltraSlots with(int index, EventNote note) {
			return new UltraSlots(centre, sides,
				index < 2 ? holding(front, index, note) : front,
				index < 2 ? back : holding(back, index - 2, note));
		}

		private static List<EventNote> holding(List<EventNote> pair, int side, EventNote note) {
			return java.util.Collections.unmodifiableList(java.util.Arrays.asList(
				side == 0 ? note : pair.get(0), side == 1 ? note : pair.get(1)));
		}

		private static List<EventNote> leaving(List<EventNote> pair, int side) {
			return java.util.Collections.unmodifiableList(java.util.Arrays.asList(
				side == 0 ? null : pair.get(0), side == 1 ? null : pair.get(1)));
		}
	}

	/** Two slots, either of which may be empty, from however many notes are left to hang. */
	private static List<EventNote> pair(List<EventNote> notes) {
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			notes.size() > 0 ? notes.get(0) : null,
			notes.size() > 1 ? notes.get(1) : null));
	}

	/**
	 * The back pair, filled from the side the walk has already been.
	 *
	 * <p>Side 0 is pinned to the lane step, so it is the side facing ground the walk has not laid
	 * yet -- {@link #layBus} says the same thing about its own slots. A back flank hung there is a
	 * note block in the column the <em>next</em> lane will stand its module against, and a note of
	 * ours against a relay of theirs is the one clash that lane cannot relocate its way out of: the
	 * note is already built, and nothing it does to its own slots reaches it. Hung on the far side
	 * instead it costs that lane nothing, and the module here is identical either way.</p>
	 *
	 * <p>ekran, reading a module with one back flank and asking for it the other way round. Only the
	 * back pair, because the front pair is full whenever the back has anything in it at all.</p>
	 */
	private static List<EventNote> backPair(List<EventNote> notes) {
		if (!BACK_FLANK_AWAY_FROM_NEXT_LANE) {
			return pair(notes);
		}
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			notes.size() > 1 ? notes.get(1) : null,
			notes.size() > 0 ? notes.get(0) : null));
	}

	/**
	 * Whether a lane may pad a chord forward so its own bus lands on the wall.
	 *
	 * <p>The closing search already pads a lane out to its wall, but it pads at the <em>end</em> --
	 * dust laid after the last chord, filling the columns the chord did not reach. That works for the
	 * wall and breaks the staircase: a climb taken straight off a bus skips two rungs, and a single
	 * cell of dust between the bus and the staircase is enough to stop it being off a bus at all. So
	 * a lane one column short is offered a pad that costs it two blocks of wire it has not got, and
	 * gives up -- then lays the chord that beat it whole and past the wall.</p>
	 *
	 * <p>On, the same columns are booked in front of the chord instead. The chord moves out to the
	 * wall itself, its bus ends there, the staircase starts on it and costs {@code offBus}. Nothing
	 * about what the chord hands on changes, because it opens with its own repeater however little
	 * wire arrives -- so the test is the tip the sweep already recorded.</p>
	 *
	 * <p>ekran's, from a breach of eleven on Guardian at 44 wide over three floors: a lane one column
	 * short of its wall with five blocks of wire, wanting one for the column and five for the climb,
	 * where moving the chord makes it one and three.</p>
	 *
	 * <p><b>Off since 2026-08-11, on ekran's instruction, and the idea is not dead.</b> It was added
	 * (af5a019) a day before {@link #PADS_AT_BUS_HEIGHT_INTO_A_CLIMB}, and the two are competing
	 * answers to one question -- how to keep a climb starting on a bus so it costs three. This one
	 * moves the chord to the wall; the raise leaves the pad up so the contact is never broken. The
	 * raise wins because it costs no columns.</p>
	 *
	 * <p>And this one costs a great many. Measured over eight songs at sixty configs each, it is worth
	 * <b>772 breached lanes with the raise off and 773 with it on</b> -- the damage is its own and has
	 * nothing to do with the raise. It was only ever measured against Guardian, 37 builds, where it
	 * did win: 267 lanes to 246, 1,539 breach blocks to 1,160, worst 17 to 12.</p>
	 *
	 * <p><b>What a better version has to do</b>, in ekran's words: pad only where it stops a breach,
	 * pad the fewest columns that does it, and never be the cause of one. The controls below are left
	 * in for that, and one of them is already known not to be enough --
	 * {@link #PREPAD_NEVER_STRANDS_THE_NEXT} looks one event ahead and scored 357 breached lanes
	 * against 63 for simply not growing. One event of lookahead cannot see the harm; the planner's can,
	 * which is where a rewrite should start.</p>
	 */
	static boolean PREPADS_FOR_THE_OFF_BUS_DISCOUNT = false;

	/**
	 * How many columns past the plan's own booking the off-bus prepad may grow, and what it must
	 * leave in the wire when it stops.
	 *
	 * <p>The loop had neither brake. It stopped when the chord it is padding lands flush on the wall
	 * or when the wire could not buy one more column -- and nothing in either test asks what the pad
	 * costs the lane. ekran read the result off illit at 32 wide over two floors: the lane stood at
	 * {@code x=17} with eleven blocks of wire and thirteen columns to its wall, the plan booked
	 * <b>two</b> columns of prepad, and this loop grew them to <b>ten</b>. That left {@code tip=1}, so
	 * the chord of seven it was padding gave up its stacked shape and was laid as a wider plain bus,
	 * and the chord of eighteen behind it arrived at {@code x=32} -- two columns outside a wall at
	 * thirty, where {@code room} is negative and so {@code couldSplit} is false. Unpadded that chord
	 * had nine columns to work with and would simply have been cut.</p>
	 *
	 * <p>Ten columns of wire to buy a discount worth two. The trade is the whole objection.</p>
	 *
	 * <p>And it is a trade the raised ascent has largely already won: this loop exists because a cell
	 * of dust between a bus and a staircase drops the staircase back to the dear form, and a pad that
	 * stays at bus height does not -- so where {@link #PADS_AT_BUS_HEIGHT_INTO_A_CLIMB} applies, the
	 * lane keeps its discount however many columns of pad stand before the wall and has no reason to
	 * chase the wall at all.</p>
	 */
	static int PREPAD_GROWTH_CAP = Integer.MAX_VALUE;

	/** @see #PREPAD_GROWTH_CAP */
	static int PREPAD_LEAVES_WIRE = 0;

	/**
	 * Whether the off-bus prepad may take the last room the chord behind it had.
	 *
	 * <p>ekran's rule, and the sharper statement of the whole thing: a pad is worth laying where a
	 * dense chord would not otherwise place, it should be the smallest number of columns that does
	 * the job, and <b>it must never be the reason something breaches</b>. The growth loop had no way
	 * to know it was the reason -- it looks only at the chord it is padding.</p>
	 *
	 * <p>So each column is offered to {@link #strandsTheEventAfter} before it is taken, and refused
	 * where it would leave the next chord unable either to fit or to be cut. Counted as
	 * {@code prepadWouldStrandTheNext} so the number of times it bites is visible beside every other
	 * reason a column gets spent.</p>
	 */
	static boolean PREPAD_NEVER_STRANDS_THE_NEXT = true;

	/**
	 * Whether the chord after a prepad has to actually fit, or merely to be cuttable.
	 *
	 * <p>The looser reading was measured first and is not enough: {@code couldSplit} stays true down
	 * to two columns of room, so "the next one can still be cut" permitted almost every column the
	 * growth asked for. This requires the next chord to fit in what is left of the lane, which is the
	 * strict reading of ekran's rule -- the pad may not be the reason anything has to be cut, never
	 * mind the reason anything breaches.</p>
	 */
	static boolean PREPAD_NEXT_MUST_FIT = true;

	/**
	 * Whether a lane may pad a chord forward only as far as it takes to make the next one cut.
	 *
	 * <p>The closing search has one target: fill the lane out to its wall. That is the expensive way
	 * to close a lane -- every column is paid for in wire, and a lane arriving on a long bus has none
	 * to pay with. A cut costs no wire at all, because the columns are filled with the chord's own
	 * music, but it is only offered where the chord does not fit. A chord that fits by a column or
	 * two is therefore laid whole, and the lane is left with nothing to climb with.</p>
	 *
	 * <p>ekran: move the start of the chord forward a couple of blocks so that it cuts the stacked
	 * bus. That is what this tries -- one column at a time, keeping the first that closes the lane.</p>
	 */
	static boolean PADS_UNTIL_THE_NEXT_CHORD_CUTS = true;

	/**
	 * How far a lane will pad looking for a cut.
	 *
	 * <p>Two, and reaching further costs builds. Over fifty configurations of Guardian, with the
	 * off-bus pre-pad on in every arm:</p>
	 *
	 * <pre>
	 *   rule off      built 37  clean 1  breaches 246  breachBlocks 1,160  worst 12
	 *   four columns  built 33  clean 4  breaches 159  breachBlocks   625  worst 12
	 *   two columns   built 38  clean 4  breaches 224  breachBlocks   987  worst 13
	 * </pre>
	 *
	 * <p>Four buys the most breach and loses four builds that used to paste. Two builds one more
	 * than having the rule off at all, keeps every one of the four clean builds, and still takes
	 * breach blocks down 15% -- for one column on the worst breach. A build that will not paste is a
	 * build nobody can go and stand in front of, which outranks the column it was bought with.</p>
	 *
	 * <p>Two is also what ekran counted off the lane: room thirteen holds the whole tail of a chord
	 * of twenty-four and room eleven does not.</p>
	 */
	static int CUT_PAD_COLUMNS = 2;

	/**
	 * Whether every chord is charged the column its lane hands over into.
	 *
	 * <p>The fifth appearance of one sentence: fitting is not the same as being able to leave. The
	 * other four were a decision that asked the wrong question and refused. This one never asked. A
	 * chord whose last cell lands on the wall exactly reads as fitting -- {@code landing > farWall} is
	 * false when {@code landing == farWall} -- so {@code overshoots} is false, so {@code wantsTurn} is
	 * false, and every turn decision in the walk sits behind that gate. The pad, the cut, the
	 * lookahead and {@link #closes} are all skipped, and the lane comes to rest on the column after
	 * the wall with nothing having objected.</p>
	 *
	 * <p>ekran's, from the last breaches on Guardian at 44 wide over three floors, both the same
	 * shape: a stacked bus of twenty-four ending on the wall at {@code 34 69 208} and the lane handing
	 * over at {@code 43}. {@link #turnReserve} already kept this column back, but only for a plain bus
	 * whose wire was too dead to turn -- a wire reserve that happened to be a column. The column is
	 * owed whatever the wire says and whatever the shape is.</p>
	 *
	 * <p>There are three doors to the same breach and they only work shut together. The fit test is
	 * one. The other two are pads that aim a chord's far end at the wall <em>on purpose</em>:
	 * {@link #prePad}, whose whole job is stated that way, and the two clamps on a booked pad -- one
	 * of which is {@link #PREPADS_FOR_THE_OFF_BUS_DISCOUNT}, added this week, which counts a pad
	 * <em>up</em> until the chord lands flush. Shutting the fit test alone made the build worse, not
	 * better, because it drove more traffic through the pads. On Guardian at 44 wide over three
	 * floors:</p>
	 *
	 * <pre>
	 *   off                    breaches 2  worst  1  breachBlocks  2  blocks 84,806  spanZ 255
	 *   fit test only          breaches 10 worst 11  breachBlocks 40  blocks 85,098  spanZ 255
	 *   all three doors        breaches 1  worst 10  breachBlocks 10  blocks 85,133  spanZ 258
	 * </pre>
	 *
	 * <p>And across fifty configurations of Guardian, floors two to six and widths twelve to
	 * forty-eight, where {@code ofOne} counts breaches of exactly one column -- the signature of this
	 * class, a lane at rest one past its wall:</p>
	 *
	 * <pre>
	 *   off  built 38  refused 12  clean 4  breaches 224  ofOne 104  breachBlocks   987  worst 13
	 *   on   built 41  refused  9  clean 9  breaches 196  ofOne  22  breachBlocks 1,283  worst 12
	 *                                                     wrong 0 -> 2, blocks +8.5%, spanZ +11.6%
	 * </pre>
	 *
	 * <p>The class really does mostly die -- {@code ofOne} down 79% -- and three more builds paste
	 * and five more come out clean, which is the count this file has ranked first all week. But the
	 * breaches that remain are bigger, not fewer in blocks: 987 becomes 1,283, because a lane that no
	 * longer stops one column out stops ten. And two wrong notes appear where there were none, which
	 * by this file's own {@code betterThan} ordering outranks every breach number on the page. That
	 * is what holds it off, not the columns.</p>
	 *
	 * <p>Both wrong notes are one fault in two configurations, and it is not this rule's: it is a
	 * corner. At 32 wide over two floors, plan space, shift {@code x+2 z+1}: the chord at tick 536 is
	 * a plain bus of five walked round the bend, and its cell two hangs a note block at
	 * {@code 1,69,139} -- the south flank, in the ground between the lane at {@code z=138} and the
	 * lane at {@code z=141}. Four ticks later than the pulse can cover, the opening chord of that next
	 * lane lays its <em>corner</em> cell at {@code 1,69,140}: stone with wire on top, directly south
	 * of the note, live at tick 544. Neither shape ever asked the other's question, because they do
	 * not collide -- they are merely adjacent, and adjacency across a corner is what nothing checks.
	 * The parity machinery asks about a lane's own columns; the lane the walk has just left is not one
	 * of them. Turning this flag on does not create that gap, it walks a lane into it.</p>
	 *
	 * <p><b>Off, and not because the diagnosis was wrong.</b> With all three shut the flush-landing
	 * class is gone outright -- every {@code columns=-1} in the build, including both of ekran's --
	 * and what is left is one lane ten columns out at {@code -10 72 243}. That lane is a different
	 * fault wearing this one's clothes: at {@code t=1546} it has three columns left, five of wire and
	 * a chord of twenty-four, its head is refused by a parity clash and its plain cut by one block of
	 * wire ({@code cells 12 + splitCells 4} against fifteen), so it cannot turn and lays the chord
	 * whole. Ten blocks of breach against two is worse by the count that matters, so this waits on
	 * that cut being affordable -- which is {@code CUTS_A_CHORD_THAT_FITS} and the turn ban, not this.
	 * </p>
	 *
	 * <p><b>On.</b> It shipped off first, on the two wrong notes above -- and those turned out to be
	 * phantoms of {@code SHARED_PULSE_TICKS} being tighter than the button that starts the machine,
	 * so the one number that outranked everything else here was never real. What is left against it is
	 * one named test, {@code NoteMachineReaderTest.readsBackEveryNoteOfItsOwnBuild[3]}, which is a bug
	 * with an address rather than a verdict. ekran asked for this one, so it stays on and red until
	 * that readback is understood.</p>
	 */
	static boolean RESERVES_THE_HANDOVER_COLUMN = true;

	/**
	 * Whether a bus puts its last unpaired note in the low-z slot of the cell.
	 *
	 * <p>ekran's, and the same instinct as {@link #BACK_FLANK_AWAY_FROM_NEXT_LANE}: the build grows
	 * towards +z, so the high-z slot of every pair faces the lane the walk has not laid yet, and a
	 * note left there is a note whose neighbour does not exist to be checked against.</p>
	 */
	static boolean BUS_ODD_NOTE_AWAY_FROM_NEXT_LANE = true;

	/**
	 * Whether the plan asks the blocks behind a chord the way the walk does.
	 *
	 * <p>A stacked module whose pair of slots behind is spoken for stands a column off the lane
	 * behind. The walk decides that from the ground -- {@code backPairIsFree}, which looks at what is
	 * actually there -- and the plan was deciding it from {@code columnBehindBusy} alone, which is the
	 * coarse arithmetic answer and says busy far more often. So the sweep charged a stand-off column
	 * the walk then did not build, on the <em>first</em> chord of a lane, and carried the error through
	 * every event after it.</p>
	 *
	 * <p>It costs a lane its turn at the far end. ekran's breach of ten on Guardian at 44 wide over
	 * three floors: the plan had the lane owing one column at the wall and exactly enough wire for a
	 * one-column pad, and the walk arrived owing two. One column it could not pay for, so
	 * {@code reachesWall} went false, so it never turned, so it laid a chord of twenty-four whole and
	 * came to rest ten columns outside.</p>
	 *
	 * <p>Nothing caught it because there was nothing to catch: the walk's own drift counter compares
	 * {@link #landingOf} against the blocks and those two agree exactly -- zero drift over the whole
	 * lane. The disagreement was only ever between the sweep's call and the walk's, and the single
	 * argument between them was this one.</p>
	 */
	static boolean PLAN_ASKS_THE_BLOCKS_BEHIND = true;

	/**
	 * Whether a head may keep the one slot behind it that nothing else wants.
	 *
	 * <p>ekran's, rebuilt by hand at the breach on Guardian at 16 wide over six floors. The pair of
	 * slots behind a head was one thing, available or not, so a module whose neighbour reached into
	 * one of them gave up both. The lane behind has exactly one live cell in the column that touches
	 * this one -- {@link UltraSlots#slot} says so in as many words -- so the most it can ever contest
	 * is one.</p>
	 */
	static boolean HEAD_KEEPS_ONE_BACK_FLANK = true;

	/**
	 * v2: whether a cut asks what kind of thing stands behind it, or only whether anything does.
	 *
	 * <p>{@link #stackedSplitOf}'s last argument is documented as <em>another stacked module's
	 * centre</em>, which is the one case where both back slots are gone rather than one -- ekran's
	 * rule, and the exception {@link #HEAD_KEEPS_ONE_BACK_FLANK} is written around. The walk was
	 * handing it {@code columnBehindBusy}, which also says busy for a rail column, a carried chord, a
	 * staircase landing and anywhere within reach of a corner.</p>
	 *
	 * <p>So a cut standing behind a plain bus was refused the head of six, fell through to the head of
	 * five, could not make one of those either, and was laid whole -- a lane outside its wall for a
	 * rule about a shape that was not there. The ordinary chord path never had this: the matching line
	 * in {@link #chooseStyle} asks {@code style.busHeaded()} and no more.</p>
	 *
	 * <p><b>On.</b> Guardian 34 breach blocks to 17 over seventeen widths, the library 4,689 to 4,640.
	 * It also opens a fault that was already there and rarer:
	 * {@link #CLASH_ASKS_IF_THE_NEIGHBOUR_IS_LIVE}.</p>
	 */
	static boolean CUT_ASKS_WHAT_KIND_IS_BEHIND = true;

	/**
	 * Whether a clash test asks the neighbour's cell about being live, as well as about being a note.
	 *
	 * <p>{@link #stackedClashes} looks at the cell two out from the module's cross column -- the lane
	 * behind -- and asks {@code noteAt}: is there a note of theirs that our live relay would sound.
	 * The same pair of cells has a second way to go wrong and nothing asked about it: a note of
	 * <em>ours</em> hung against a block of theirs that goes live at their tick. Two cells, two
	 * directions, one question.</p>
	 *
	 * <p>Read off the paste rather than reasoned about, which took two wrong guesses first. The render
	 * at {@code no-batid-o} 12 wide over three floors: the note at {@code 6 64 72} belongs to tick 408
	 * and sounds at 404 from {@code 6 64 71} -- the cell directly north, in the lane behind, which is
	 * exactly {@code beyond}. Both earlier attempts looked in this module's own lane, along travel,
	 * and the slice says the aggressor was never there.</p>
	 *
	 * <p><b>Off: the cell is right and the question is too broad.</b> {@code liveAt(cell, time)} means
	 * <em>live at some tick other than this one</em>, and the lane behind's own wire is that
	 * everywhere -- so this refuses nearly every module with a flank on that side. Over the library at
	 * seventeen widths: wrong notes 9 to 87, breach blocks 4,640 to 6,129, and the new wrong notes are
	 * in shapes that had none. The narrower question is whether a note hung at one out would be
	 * sounded, which is what {@link #soundedByAnother} asks -- and the three lines below already ask
	 * two of its four horizontal directions and not this one. Which cell holds which flank is the part
	 * to establish first, off a paste, before the fourth attempt.</p>
	 */
	static boolean CLASH_ASKS_IF_THE_NEIGHBOUR_IS_LIVE = false;

	/**
	 * Whether a cut across a staircase asks the blocks behind rather than the coarse flag.
	 *
	 * <p>The same gap as {@link #PLAN_ASKS_THE_BLOCKS_BEHIND}, one path over. A cut asked
	 * {@code columnBehindBusy}, which is the arithmetic answer and says busy far more often than the
	 * ground does -- so a chord whose back pair was actually free was never offered a full head, and
	 * the whole cut fell to a plain bus.</p>
	 *
	 * <p>ekran's, from the breach at 16 wide over six floors: a chord of twenty-two cut ten cells on
	 * one floor and one on the next, {@code headed=no}, which they rebuilt by hand with a full head of
	 * seven and no parity trouble at all.</p>
	 */
	static boolean CUT_ASKS_THE_BLOCKS_BEHIND = true;

	/**
	 * Whether the plan is redone when the blocks answer what it had to guess.
	 *
	 * <p>ekran: be more intelligent whenever possible. The walk can see things the plan cannot -- the
	 * plan runs before any of the lane exists -- so the choice when they disagree is either to make
	 * the walk stop looking, or to let it look and tell the plan. This is the second.</p>
	 */
	static boolean REPLAN_WHEN_BLOCKS_DISAGREE = true;

	/** Whether a lone back flank hangs away from the lane the walk has not built yet. */
	static boolean BACK_FLANK_AWAY_FROM_NEXT_LANE = true;

	/**
	 * Whether the pair of low slots behind a module is asked of the blocks rather than of a flag.
	 *
	 * <p>{@code columnBehindBusy} is a promise made by whatever went down last, and the promise a
	 * turn makes is far bigger than what it does. A turn claims the pair because its run of powered
	 * stone lies at the level the low notes hang at and is live at the tick of the lane it is
	 * leaving -- true of the cells beside the corner, and nothing to do with a module standing five
	 * columns further along. ekran, on Guardian at 44 wide over three floors: a chord of twelve at
	 * {@code 38 72 15}, first of the lane after a flat turn, that lost its head of seven for a head
	 * of five and a cell of bus with both cells behind it plain air.</p>
	 *
	 * <p>So the flag stays as the cheap first answer and this is the appeal: the two cells the back
	 * flanks would hang in are asked whether a note may go there at all, and whether anything beside
	 * them goes live on somebody else's tick. That second question is the one that matters and it is
	 * the same one {@link #layBus} asks of its own slots -- an empty cell next to a foreign live
	 * block is a cell that would sound its note at the wrong time.</p>
	 */
	static boolean BACK_PAIR_ASKS_THE_BLOCKS = true;

	/**
	 * Whether both cells a module's back flanks would hang in are free and quiet.
	 *
	 * <p>They sit one either side of the module's <em>opening</em> column, level with its repeater --
	 * not behind the module, which is what makes them the pair a shape two columns back would have
	 * taken.</p>
	 */
	/**
	 * The same module with its one back note on whichever side is actually free.
	 *
	 * <p>A head that keeps one of the two slots behind it has to choose a side, and until now that
	 * was a guess: {@link #backPair} hangs it away from the lane the walk has not laid yet, which is
	 * a good rule and never once looks. For an ordinary chord the guess is right often enough to
	 * cost nothing. For a cut it is wrong all over -- a cut stands at the end of a lane against a
	 * staircase, where the ground is not the ground the guess was written for -- and the note lands
	 * in a cell some other module already owns.</p>
	 *
	 * <p>So it looks. Both cells are asked the two questions {@link #backPairIsFree} asks of them,
	 * and if the chosen side fails while the other passes, the pair is mirrored. Nothing about the
	 * shape changes: same head, same size, same columns, same tail -- so the plan does not need to
	 * know, and the planner and the walk cannot part company over it.</p>
	 *
	 * <p>ekran: look instead of guess. Applies to any stacked shape carrying exactly one back note,
	 * not to the head of six alone.</p>
	 */
	/**
	 * The same module with its low notes on whichever of the four slots are actually free.
	 *
	 * <p>A head hangs up to four low notes: a front and a back slot on each side of its centre. At
	 * most one of the four is ever in contention -- {@link UltraSlots#slot} says why, the lane
	 * alongside has a single live cell in the column that touches this one, so it lines up with the
	 * front pair or the back pair and never with both. Which one it is was never asked. The front
	 * pair filled in order and the back pair was guessed at by {@link #backPair}, which hangs a lone
	 * flank away from the lane the walk has not laid yet -- a good rule that never once looks.</p>
	 *
	 * <p>So it looks, at all four, and rehomes the notes onto the free ones. Same notes, same count,
	 * same head, same columns: only which cell each note hangs in changes, so the plan does not need
	 * to know and the two cannot part company over it. Where there are not enough free slots to hold
	 * them the module is handed back untouched, and the caller's own fallback takes over.</p>
	 *
	 * <p>ekran: look at both, not just guess for the front and actually look for the back. Either of
	 * the two flanks on the contested side may be the one given up, and it could be the front.</p>
	 */
	private static UltraSlots onTheFreeSlots(PlacementPlan placements, BlockPos pos, Direction travel,
			Direction noteSide, int time, UltraSlots slots) {
		return onTheFreeSlots(placements, pos, travel, noteSide, time, slots, -1);
	}

	/**
	 * @param banned a slot this module may not hang a note in whatever the ground says, or -1.
	 *     A head that shed the flank the staircase wants has an empty slot that reads perfectly free
	 *     -- the staircase is not built yet -- and this would fill it straight back in, silently
	 *     undoing the one thing the shape was measured on. The ground cannot answer for a block that
	 *     is not there, so the shape has to say so.
	 */
	private static UltraSlots onTheFreeSlots(PlacementPlan placements, BlockPos pos, Direction travel,
			Direction noteSide, int time, UltraSlots slots, int banned) {
		if (!HEAD_LOOKS_FOR_ITS_FREE_SIDE || slots == null) {
			return slots;
		}
		BlockPos cross = pos.relative(travel);
		// Slot order is the record's own: 0 and 1 the front pair, 2 and 3 the back.
		BlockPos[] cell = new BlockPos[4];
		for (int side = 0; side < 2; side++) {
			BlockPos instrument = cross.relative(side == 0 ? noteSide : noteSide.getOpposite());
			cell[side] = instrument.relative(travel);
			cell[2 + side] = instrument.relative(travel.getOpposite());
		}
		List<EventNote> hanging = new ArrayList<>(4);
		boolean settled = true;
		for (int slot = 0; slot < 4; slot++) {
			EventNote note = slots.slot(slot);
			if (note == null) {
				continue;
			}
			hanging.add(note);
			settled &= quietAndFree(placements, cell[slot], time);
		}
		if (settled || hanging.isEmpty()) {
			return slots;
		}
		// Front pair first and the far side of the back pair before the near one, which is the order
		// the shape filled in before anybody looked -- so a module whose slots are all free comes out
		// of here exactly as it went in.
		EventNote[] placed = new EventNote[4];
		int next = 0;
		for (int slot : new int[] {0, 1, 3, 2}) {
			if (slot != banned && next < hanging.size()
					&& quietAndFree(placements, cell[slot], time)) {
				placed[slot] = hanging.get(next++);
			}
		}
		if (next < hanging.size()) {
			return slots;
		}
		HEAD_SIDES_SWAPPED++;
		return new UltraSlots(slots.centre(), slots.sides(),
			java.util.Collections.unmodifiableList(
				java.util.Arrays.asList(placed[0], placed[1])),
			java.util.Collections.unmodifiableList(
				java.util.Arrays.asList(placed[2], placed[3])));
	}

	private static boolean quietAndFree(PlacementPlan placements, BlockPos cell, int time) {
		return placements.freeForNote(cell) && !soundedByAnother(placements, cell, time);
	}

	/** How often looking found a low note on a cell something else already owned. */
	static int HEAD_SIDES_SWAPPED;

	/** Whether a stacked shape puts its low notes on the free slots instead of assuming. */
	static boolean HEAD_LOOKS_FOR_ITS_FREE_SIDE = true;

	private static boolean backPairIsFree(PlacementPlan placements, Lane opening, int time) {
		if (!BACK_PAIR_ASKS_THE_BLOCKS) {
			return false;
		}
		BlockPos cursor = opening.pos();
		for (Direction out : List.of(opening.noteSide(), opening.noteSide().getOpposite())) {
			BlockPos flank = cursor.relative(out);
			if (!placements.freeForNote(flank) || soundedByAnother(placements, flank, time)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether a stacked-bus too near the wall keeps its head when the plain bus is longer still.
	 *
	 * <p>The room check allows the shape a column to shift into, so it refuses at the length the
	 * shape actually is. For the rigid module that is fine -- as a bus it is four blocks at the most
	 * and fits anywhere. For a stacked-bus it is not: the head carries seven notes in two columns
	 * that the bus spends four cells on, so the bus is always the longer of the two, and refusing the
	 * head for want of one column hands the chord a shape that wants one more.</p>
	 *
	 * <p>What it costs is not the column. A headed cut of twenty-four spends a transition, nine cells
	 * and a staircase -- fourteen of the fifteen a repeater reaches -- and an unheaded one spends
	 * sixteen, so the chord that lost its head can no longer be cut across the descent either. It is
	 * laid whole, past the wall, and hands on {@code 15 - 12 = 3} blocks of wire where the staircase
	 * wants four. Every chord of that size after it does the same, so the lane cannot turn until a
	 * smaller chord comes along. ekran: the code is already supposed to be able to split a chord of
	 * twenty-seven, and all of these are below that.</p>
	 */
	static boolean KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER = true;

	/**
	 * Whether the first module of a lane that landed off a staircase may use the pair behind it.
	 *
	 * <p>A staircase used to be treated like a turn. What makes a turn claim the pair is not that it
	 * bends: it is that a turn is a run of powered stone at the level the low notes hang at, live at
	 * the tick of the lane it is leaving, and <em>with notes hung along it</em> -- so the cells behind
	 * the next module are already spoken for. Neither staircase is that. A climb is glass and dust up
	 * two columns of the lane's own centre line; a descent spirals round a two-by-two column. Both
	 * carry the signal and neither hangs a note, so the pair beside the landing is nobody's.</p>
	 *
	 * <p>ekran, who has read both in game: only the flat turn should have the rule, and a stacked-bus
	 * should be able to stand immediately either side of a descent. The climb half of this was theirs
	 * too, from a stacked-bus that landed off an ascent with both back cells plain air.</p>
	 *
	 * <p>What still guards the descent, so this is not the only thing standing between a spiral and a
	 * module: a shape whose blocks actually overlap something is built in a trial and rolled back to a
	 * bus, and the lane alongside is still asked about by {@link #stackedClashes}.</p>
	 */
	static boolean BACK_PAIR_FREE_AFTER_A_STAIRCASE = true;

	/**
	 * Which note goes where in a stacked module, or {@code null} if this chord cannot use one.
	 *
	 * <p>The order the slots are filled in is the whole of it. Snare can only be one of the two
	 * relays, so snares are placed first. The relays otherwise want an instrument that is not harp,
	 * so that a harp is left over for the centre -- but only while there is a harp to spare, which
	 * is what settles the awkward chord of two harps and five instruments that do not conduct:
	 * both harps become relays, and the centre goes unused.</p>
	 */
	private static UltraSlots ultraSlots(List<EventNote> chord, boolean reachingBack) {
		return ultraSlots(chord, reachingBack ? 2 : 0);
	}

	private static UltraSlots ultraSlots(List<EventNote> chord, int backFlanks) {
		int hangers = 4 + backFlanks;
		// No floor on the size. A chord of three or fewer is built as the small shape because that is
		// cheaper, not because the stacked one could not hold it -- fewer notes than hangers simply
		// leaves a hanger empty. {@link #chooseStyle} still answers SMALL for those before it ever
		// asks here, so the only caller this opens the shape to is the one that has already found the
		// small shape will not do: the chord whose rigid slots are free but not quiet.
		if (chord.isEmpty() || chord.size() > hangers + 1) {
			return null;
		}
		long snares = chord.stream().filter(note -> FALLING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock()))
			.count();
		if (snares > 2) {
			return null;
		}
		boolean useCentre = chord.size() > hangers;
		long spareHarps = chord.stream().filter(SongBuilder::isHarpNote).count()
			- (useCentre ? 1 : 0);
		boolean[] used = new boolean[chord.size()];
		List<EventNote> sides = new ArrayList<>(2);
		for (int index = 0; index < chord.size() && sides.size() < 2; index++) {
			if (FALLING_INSTRUMENT_BLOCKS.contains(chord.get(index).instrumentBlock())) {
				sides.add(chord.get(index));
				used[index] = true;
			}
		}
		for (int index = 0; index < chord.size() && sides.size() < 2; index++) {
			EventNote note = chord.get(index);
			if (!used[index] && !isHarpNote(note) && conductsSideways(note)) {
				sides.add(note);
				used[index] = true;
			}
		}
		for (int index = 0; index < chord.size() && sides.size() < 2 && spareHarps > 0; index++) {
			if (!used[index] && isHarpNote(chord.get(index))) {
				sides.add(chord.get(index));
				used[index] = true;
				spareHarps--;
			}
		}
		if (sides.size() < 2) {
			return null;
		}
		EventNote centre = null;
		if (useCentre) {
			for (int index = 0; index < chord.size() && centre == null; index++) {
				if (!used[index] && isHarpNote(chord.get(index))) {
					centre = chord.get(index);
					used[index] = true;
				}
			}
			if (centre == null) {
				return null;
			}
		}
		List<EventNote> hanging = new ArrayList<>(4);
		for (int index = 0; index < chord.size(); index++) {
			if (!used[index]) {
				hanging.add(chord.get(index));
			}
		}
		return new UltraSlots(centre, List.copyOf(sides),
			pair(hanging.subList(0, Math.min(2, hanging.size()))),
			backPair(hanging.subList(Math.min(2, hanging.size()), hanging.size())));
	}

	private static boolean isHarpNote(EventNote note) {
		return note.effect() == null && "minecraft:air".equals(note.instrumentBlock());
	}

	/** Whether this note's instrument block would pass power on to a note block beside it. */
	private static boolean conductsSideways(EventNote note) {
		return note.effect() == null
			&& (isHarpNote(note) || CONDUCTING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock()));
	}

	/** Whether anything in this chord is a block that sounds for itself rather than a note block. */
	private static boolean hasEffect(List<EventNote> chord) {
		for (EventNote note : chord) {
			if (note.effect() != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this can stand in the middle of a small module and pass the power on to its flanks.
	 *
	 * <p>A note block always can: it is a full solid block, whatever instrument block it stands on.
	 * Most effects cannot -- a door, a bell, a shrieker and, less obviously, a copper bulb are all
	 * blocks power stops at. The ones that can are the two droppers and the note blocks in skulls,
	 * which are note blocks.</p>
	 */
	private static boolean carriesAModule(EventNote note) {
		return note.effect() == null || CONDUCTING_EFFECT_BLOCKS.contains(note.effect().blockId());
	}

	/**
	 * Effects solid enough to sound and pass the signal on in the same breath.
	 *
	 * <p>Asked of the state that actually gets placed rather than of the block's default, since that
	 * is the one whose neighbours have to be right.</p>
	 */
	private static final Set<String> CONDUCTING_EFFECT_BLOCKS = PreviewInstrument.EFFECTS.stream()
		.filter(voice -> conducts(voice.effect().block()))
		.map(voice -> voice.effect().blockId())
		.collect(java.util.stream.Collectors.toUnmodifiableSet());

	private static boolean conducts(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unplaceable sound effect: " + blockState, unparseable);
		}
	}

	/**
	 * A chord ordered so that whatever holds the middle of a small module comes first, or null if
	 * nothing in it can.
	 *
	 * <p>The repeater points at the first note and the two after it are sounded by that first one
	 * being a strongly powered solid block. A chord of one needs no such favour -- the repeater
	 * faces it directly -- so anything at all can stand alone.</p>
	 */
	/**
	 * Whether this chord has to be lent a middle: a stone in the cell the repeater faces, with the
	 * one sound moved off to the side.
	 *
	 * <p>A chord of one used to be treated as needing no favour, on the grounds that the repeater
	 * faces it directly and so anything at all can sound there. That is true of the sound and false
	 * of everything after it. The cell the repeater faces is also the cell the <em>next</em>
	 * repeater reads, so whatever stands in it has to be solid: a lone sculk shrieker sounded once
	 * and took the rest of the lane with it.</p>
	 *
	 * <p>Only ever a chord of one. With two or more, {@link #smallOrder} finds something in the
	 * chord that can hold the middle, or the chord goes to a bus where every sound has its own
	 * stone.</p>
	 */
	private static boolean needsLentMiddle(List<EventNote> chord) {
		return chord.size() == 1 && !carriesAModule(chord.getFirst());
	}

	private static List<EventNote> smallOrder(List<EventNote> chord) {
		if (chord.size() <= 1 || carriesAModule(chord.get(0))) {
			return chord;
		}
		for (int index = 1; index < chord.size(); index++) {
			if (carriesAModule(chord.get(index))) {
				List<EventNote> ordered = new ArrayList<>(chord);
				ordered.add(0, ordered.remove(index));
				return List.copyOf(ordered);
			}
		}
		return null;
	}

	/**
	 * Whether the small two-column module can hold this chord.
	 *
	 * <p>Asked by {@link #chooseStyle} and again by {@link #addEventModule}, from the one method, so
	 * that the shape the plan priced and the shape the walk lays are the same shape. A chord of three
	 * doors is the case that made this necessary: it fits by size and does not fit by physics, and a
	 * planner that only counted would have reserved two columns for something that needs three.</p>
	 */
	private static boolean fitsSmallModule(List<EventNote> chord) {
		return chord.size() <= 3 && smallOrder(chord) != null;
	}

	/**
	 * The block to put under a note that has to relay.
	 *
	 * <p>Harp is the odd one out. Everywhere else a harp note is built over air, which sounds the
	 * same and costs nothing, but air conducts nothing at all -- so the two relays in a stacked
	 * module get the block harp is actually named for.</p>
	 */
	private static String conductingInstrumentBlock(EventNote note) {
		return isHarpNote(note) ? HARP_BLOCK : note.instrumentBlock();
	}

	private static final String HARP_BLOCK =
		BuiltInRegistries.ITEM.getKey(PreviewInstrument.byId("HARP").icon()).toString();

	/** Instrument blocks solid enough to carry power to a note block beside them. */
	private static final Set<String> CONDUCTING_INSTRUMENT_BLOCKS = PreviewInstrument.VALUES.stream()
		.filter(instrument -> net.minecraft.world.level.block.Block.byItem(instrument.icon())
			.defaultBlockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
		.map(instrument -> BuiltInRegistries.ITEM.getKey(instrument.icon()).toString())
		.collect(java.util.stream.Collectors.toUnmodifiableSet());

	private static DelayTrigger addDelayBeforeEvent(PlacementPlan placements, BlockPos origin, Direction forward,
			int cursor, int delay) {
		int remaining = delay;
		while (remaining > 4) {
			BlockPos pos = at(origin, forward, cursor, 0, 0);
			set(placements, pos, "minecraft:stone");
			set(placements, pos.above(), "minecraft:repeater[facing=" + repeaterFacing(forward) + ",delay=4]");
			cursor++;
			remaining -= 4;
		}
		return new DelayTrigger(cursor, Math.max(1, remaining));
	}

	private static int addEventModule(PlacementPlan placements, BlockPos origin, Direction forward,
			int cursor, int triggerDelay, List<EventNote> chord) {
		Direction right = forward.getClockWise();
		BlockPos triggerPos = at(origin, forward, cursor, 0, 0);
		set(placements, triggerPos, "minecraft:stone");
		set(placements, triggerPos.above(), "minecraft:repeater[facing=" + repeaterFacing(forward) + ",delay=" + triggerDelay + "]");
		BlockPos anchor = at(origin, forward, cursor + 1, 1, 0);
		int time = chord.get(0).time();
		if (fitsSmallModule(chord)) {
			// Reordered, not merely taken as it came: the middle of this shape has to be something
			// that passes power to its flanks, and on a chord that mixes a door with a note block
			// only one of the two will do. {@link #chooseStyle} priced this same shape from the same
			// test, so a chord that cannot be reordered into it never arrives here at all.
			List<EventNote> ordered = smallOrder(chord);
			if (needsLentMiddle(ordered)) {
				// See layEventBody: the cell the repeater faces has to pass the signal on, so a sound
				// that cannot is moved aside and given a stone to be powered from.
				placements.powered(anchor, "minecraft:stone", time);
				placeNote(placements, anchor.relative(right.getOpposite()), ordered.getFirst());
				return cursor + 2;
			}
			placeNote(placements, anchor, ordered.get(0));
			placements.powered(anchor, time);
			if (ordered.size() >= 2) {
				placeNote(placements, anchor.relative(right), ordered.get(1));
			}
			if (ordered.size() >= 3) {
				placeNote(placements, anchor.relative(right.getOpposite()), ordered.get(2));
			}
			return cursor + 2;
		}
		int busLength = (chord.size() + 1) / 2;
		for (int bus = 0; bus < busLength; bus++) {
			BlockPos busPos = anchor.relative(forward, bus);
			placements.powered(busPos, "minecraft:stone", time);
			set(placements, busPos.above(), "minecraft:redstone_wire");
		}
		List<EventNote> ordered = busOrder(chord);
		for (int noteIndex = 0; noteIndex < ordered.size(); noteIndex++) {
			EventNote note = ordered.get(noteIndex);
			int bus = noteIndex / 2;
			Direction side = noteIndex % 2 == 0 ? right : right.getOpposite();
			placeNote(placements, anchor.relative(forward, bus).relative(side), note);
		}
		return cursor + 1 + busLength;
	}

	/**
	 * A chord reordered so each instrument sits on one side of the bus.
	 *
	 * <p>Notes hang off alternating sides as the list is walked, so a chord in track order puts a
	 * run of six harp notes three to a side and interleaves them with whatever comes next. Every
	 * note still sounds, but the machine reads as scattered blocks rather than as the parts it is
	 * actually made of.</p>
	 *
	 * <p>The bus does not get any longer. A bus block carries one note per side, so the near side
	 * holds ceil(n/2) and the far side the rest, and the only question is which instrument goes
	 * where. That is a subset sum: find a set of instruments totalling exactly the near side's
	 * capacity. When none exists -- five gold and one stone cannot split three and three -- the
	 * shortfall is borrowed from the longest run left on the far side, and that instrument
	 * straddles. Over the 2781 chords in the library that leaves 2166 with every instrument on one
	 * side and 615 with exactly one straddling; nothing worse turned up, though a large enough
	 * shortfall against short runs could in principle split a second.</p>
	 */
	static List<EventNote> busOrder(List<EventNote> chord) {
		Map<String, List<EventNote>> byInstrument = new LinkedHashMap<>();
		for (EventNote note : chord) {
			byInstrument.computeIfAbsent(String.valueOf(note.instrumentBlock()),
				ignored -> new ArrayList<>()).add(note);
		}
		if (byInstrument.size() < 2) {
			return chord;
		}
		List<List<EventNote>> groups = new ArrayList<>(byInstrument.values());
		int target = (chord.size() + 1) / 2;

		// Which totals the near side can reach using whole instruments.
		boolean[][] reachable = new boolean[groups.size() + 1][target + 1];
		reachable[0][0] = true;
		for (int group = 0; group < groups.size(); group++) {
			int size = groups.get(group).size();
			for (int sum = 0; sum <= target; sum++) {
				if (!reachable[group][sum]) {
					continue;
				}
				reachable[group + 1][sum] = true;
				if (sum + size <= target) {
					reachable[group + 1][sum + size] = true;
				}
			}
		}
		int best = target;
		while (best > 0 && !reachable[groups.size()][best]) {
			best--;
		}

		boolean[] onNearSide = new boolean[groups.size()];
		int remaining = best;
		for (int group = groups.size(); group > 0; group--) {
			if (reachable[group - 1][remaining]) {
				continue;
			}
			onNearSide[group - 1] = true;
			remaining -= groups.get(group - 1).size();
		}

		List<EventNote> near = new ArrayList<>(target);
		List<EventNote> far = new ArrayList<>(chord.size() - target);
		for (int group = 0; group < groups.size(); group++) {
			(onNearSide[group] ? near : far).addAll(groups.get(group));
		}
		// Nothing summed to the target, so one instrument has to lend the near side the shortfall.
		// Taken from the largest group left on the far side, which leaves the most of it together.
		for (int shortfall = target - near.size(); shortfall > 0; shortfall--) {
			near.add(far.remove(largestFarGroupEnd(far)));
		}

		List<EventNote> ordered = new ArrayList<>(chord.size());
		for (int index = 0; index < near.size(); index++) {
			ordered.add(near.get(index));
			if (index < far.size()) {
				ordered.add(far.get(index));
			}
		}
		return ordered;
	}

	/** The last note of the longest run of one instrument on the far side. */
	private static int largestFarGroupEnd(List<EventNote> far) {
		int bestEnd = far.size() - 1;
		int bestRun = 0;
		int run = 0;
		for (int index = 0; index < far.size(); index++) {
			run = index > 0 && String.valueOf(far.get(index).instrumentBlock())
				.equals(String.valueOf(far.get(index - 1).instrumentBlock())) ? run + 1 : 1;
			if (run > bestRun) {
				bestRun = run;
				bestEnd = index;
			}
		}
		return bestEnd;
	}

	private static void placeNote(PlacementPlan placements, BlockPos notePos, EventNote note) {
		placeNote(placements, notePos, note, false);
	}

	/**
	 * @param laneHeight whether this note hangs at the lane's own floor level, where its instrument
	 *     block is laid as a half-block instead of a full one
	 */
	private static void placeNote(PlacementPlan placements, BlockPos notePos, EventNote note,
			boolean laneHeight) {
		if (note.effect() != null) {
			placeEffect(placements, notePos, note);
			return;
		}
		String instrument = note.instrumentBlock();
		String slab = laneHeight ? LANE_HEIGHT_SLABS.get(instrument) : null;
		set(placements, notePos.below(), slab == null ? instrument : slab);
		if (FALLING_INSTRUMENT_BLOCKS.contains(instrument)) {
			// Sand and friends drop the moment /setblock places them over air, taking the note
			// block's instrument with them. Fill the space beneath, but never overwrite: anything
			// already planned there is load-bearing and already does the supporting.
			//
			// A top slab holds it up as well as a full block does -- what makes a falling block fall
			// is air or something replaceable underneath, and a slab is neither -- and it is a floor
			// short of blocking the way through, which is the whole of why it is one.
			placements.support(notePos.below().below(), UNDERFLOOR);
		}
		placeNoteBlock(placements, notePos, note);
	}

	/**
	 * Everything a build lays in the level below the lane, which is nothing but floor.
	 *
	 * <p>Four things end up there and not one of them is machinery: the block a falling instrument
	 * needs under it, the block the stacked cross's dust sits on, the block the bottom rail's
	 * repeater stands on, and the instrument blocks of the notes hanging at lane level. All four are
	 * half-blocks now, which is what makes a floor something you can see down through and walk
	 * between rather than a solid ceiling over the floor below.</p>
	 *
	 * <p>Safe there and nowhere else, for the reason ekran gave: a slab cannot be strongly powered.
	 * Nothing in this level is ever asked to carry the signal -- a falling block's prop holds it up,
	 * the cross's floor holds up dust, the rail's floor holds up a repeater, and a repeater does not
	 * care what it stands on. One level higher and the same substitution would silence a module,
	 * because that is where the blocks that relay a pulse out to the flanks live.</p>
	 */
	private static final String UNDERFLOOR = "minecraft:stone_slab[type=top]";

	/**
	 * The half-block an instrument sits on where its note hangs at the lane's own floor level.
	 *
	 * <p>Two reasons, and the second is the one that decides which slots are in here. Half a block is
	 * half a floor you can see through and walk between, and the low slots are the only ones at the
	 * height where that helps. But a slab cannot be strongly powered, and the instrument blocks
	 * further up a stacked module are not decoration -- the two beside the centre are what relay its
	 * pulse out to the flanks. So the substitution stops exactly where the block stops being a floor
	 * and starts being a wire: the four low notes of a stacked module and the two of a floor rail
	 * column, and nothing above them.</p>
	 *
	 * <p>Only the instruments that have a half-block at all, which is why this is a lookup and not a
	 * rule. Everything else -- a bell's gold, a chime's ice, a banjo's hay -- stays a full block and
	 * is none the worse for it. The game confirms each of these sounds the same as what it replaces
	 * ({@link com.fastnoteblocks.client.compat.SlabInstrumentTest}); a slab that plays a different
	 * instrument would be a whole line of a song retuned by a floor.</p>
	 */
	private static final Map<String, String> LANE_HEIGHT_SLABS = Map.of(
		"minecraft:oak_planks", "minecraft:oak_slab[type=top]",
		"minecraft:stone", "minecraft:stone_slab[type=top]",
		"minecraft:waxed_copper_block", "minecraft:waxed_cut_copper_slab[type=top]",
		"minecraft:waxed_exposed_copper", "minecraft:waxed_exposed_cut_copper_slab[type=top]",
		"minecraft:waxed_weathered_copper", "minecraft:waxed_weathered_cut_copper_slab[type=top]",
		"minecraft:waxed_oxidized_copper", "minecraft:waxed_oxidized_cut_copper_slab[type=top]");

	/**
	 * The note block itself, the air it needs over it, and the record that it owes its event a note.
	 *
	 * <p>Separate from the instrument block underneath because a stacked module's centre has no
	 * instrument block of its own: what is under it is the dust that drives the whole module.</p>
	 */
	private static void placeNoteBlock(PlacementPlan placements, BlockPos notePos, EventNote note) {
		set(placements, notePos, "minecraft:note_block[note=" + note.pitch() + "]");
		placements.note(notePos, note.time());
		set(placements, notePos.above(), "minecraft:air");
	}

	/**
	 * A sound effect, in the same three cells a note would have used.
	 *
	 * <p>The effect goes where the note block would have gone, so everything that decided where a
	 * note could stand -- reach, spacing, whether the ground is free -- decided this too, and none
	 * of it had to learn a new shape. What differs is the two cells around it. Underneath, where an
	 * instrument block would sit, most effects want nothing at all and a few want a floor. Above,
	 * where a note block insists on air, a door puts its upper half and a note block wearing a skull
	 * puts the skull -- and the air is still what a piston extends into, which is why the pistons
	 * face up.</p>
	 *
	 * <p>Recorded as a note on the plan exactly like a note block is. It is a sound at a tick, and
	 * {@code verify} has to hold it to the same rule: something next to it has to be powered on its
	 * own tick and on no other.</p>
	 */
	private static void placeEffect(PlacementPlan placements, BlockPos notePos, EventNote note) {
		PreviewInstrument.Effect effect = note.effect();
		if (effect.floor()) {
			placements.support(notePos.below(), "minecraft:stone");
		}
		set(placements, notePos, effect.block());
		placements.note(notePos, note.time());
		set(placements, notePos.above(), effect.above() == null ? "minecraft:air" : effect.above());
	}

	/** Instrument blocks affected by gravity, which need something solid underneath them. */
	private static final Set<String> FALLING_INSTRUMENT_BLOCKS = PreviewInstrument.VALUES.stream()
		.filter(instrument -> net.minecraft.world.level.block.Block.byItem(instrument.icon())
			instanceof net.minecraft.world.level.block.FallingBlock)
		.map(instrument -> BuiltInRegistries.ITEM.getKey(instrument.icon()).toString())
		.collect(java.util.stream.Collectors.toUnmodifiableSet());

	private static void set(PlacementPlan placements, BlockPos pos, String block) {
		placements.set(pos, block);
	}

	private static BlockPos at(BlockPos origin, Direction forward, int forwardOffset, int upOffset, int rightOffset) {
		return origin.relative(forward, forwardOffset)
			.relative(forward.getClockWise(), rightOffset)
			.above(upOffset);
	}

	private static String repeaterFacing(Direction lineDirection) {
		return directionName(lineDirection.getOpposite());
	}

	private static String directionName(Direction direction) {
		return switch (direction) {
			case NORTH -> "north";
			case SOUTH -> "south";
			case EAST -> "east";
			case WEST -> "west";
			default -> "north";
		};
	}

	private static String instrumentBlockId(String instrument) {
		// Tested against the stored string, not against a looked-up instrument. Mute is no longer one
		// of the instruments, so a layer saved with it now looks up as harp -- and would build as
		// harp rather than staying out of the machine.
		if ("MUTE".equals(instrument)) {
			return null;
		}
		PreviewInstrument preview = PreviewInstrument.byId(instrument);
		if (preview.effect() != null) {
			// The effect is the sound source, so this is the source's own id rather than a block for
			// it to stand on. Carried so that chords still group and run-length by instrument.
			return preview.effect().blockId();
		}
		if ("HARP".equals(preview.id())) {
			// A note block over anything unrecognised already plays harp, so air is identical in
			// sound and costs nothing. Placing grass would waste a block per piano note.
			return "minecraft:air";
		}
		return BuiltInRegistries.ITEM.getKey(preview.icon()).toString();
	}

	/** The effect a layer's voice builds as, or null when it is a tuned instrument. */
	private static PreviewInstrument.Effect effectOf(String instrument) {
		return "MUTE".equals(instrument) ? null : PreviewInstrument.byId(instrument).effect();
	}

	/**
	 * One sound to place.
	 *
	 * <p>For a pitched note {@code instrumentBlock} is what goes under the note block and
	 * {@code effect} is null. For a sound effect there is no note block at all: {@code effect} says
	 * what to lay in its place and {@code instrumentBlock} is that block's id, so that the grouping
	 * and run-length code that sorts a chord by instrument keeps sorting doors next to doors.
	 * {@code pitch} is carried either way and simply ignored on an effect, which cannot be tuned.</p>
	 */
	record EventNote(int time, int trackNumber, int order, int pitch, String instrumentBlock,
			PreviewInstrument.Effect effect) {
		/** A note block over an instrument block, which is every note this mod placed before effects. */
		EventNote(int time, int trackNumber, int order, int pitch, String instrumentBlock) {
			this(time, trackNumber, order, pitch, instrumentBlock, null);
		}
	}

	/**
	 * Which module shapes a build may reach for, and how tightly its lanes may sit.
	 *
	 * <p>A flag rather than a subclass because the two layouts differ in exactly two decisions --
	 * what a chord is built out of, and how close two lanes may sit -- and both of those are
	 * settled once, up front, and then carried on the events themselves. Nothing downstream has to
	 * know which mode it is walking.</p>
	 *
	 * @param risers whether this build changes floors, which is what decides which way a descent
	 *     steps off its own centre line
	 */
	/**
	 * @param v2 whether this is the second layout. Carried on the layout rather than read off a
	 *     static, because the pieces below the walk -- {@link #addChordModule} and everything it
	 *     calls -- are shared by both and are where v2's rules have to differ without v1 moving. The
	 *     walk itself does not need it: {@code walkV2} is its own method and simply does the other
	 *     thing.
	 */
	private record Layout(boolean ultra, boolean risers, int centreParity, boolean lookahead,
			boolean v2) {
		static final Layout STANDARD = new Layout(false, true, 0, false, false);

		static Layout ultra(int floors, BlockPos origin) {
			// Counted from the origin and not from the world, so the same song pasted a block over
			// is the same build. Anchored where the first module would land anyway, which is one
			// past the origin, so a song that never drifts off the beat never pays for a pad.
			return new Layout(true, floors > 1, Math.floorMod(origin.getX() + 1, 2), false, false);
		}

		Layout withLookahead() {
			return new Layout(ultra, risers, centreParity, true, v2);
		}

		Layout asV2() {
			return new Layout(ultra, risers, centreParity, lookahead, true);
		}
	}

	/** How a chord is laid out around the repeater that sets it off. */
	private enum ChordStyle {
		/** One note block driven straight off the repeater, with up to two hung either side. */
		SMALL,
		/** The stacked module, reaching only forward: two relays, two low notes, and a centre. */
		STACKED_FRONT,
		/** The stacked module reaching both ways, which needs the two low slots behind it free. */
		STACKED_FULL,
		/** A run of powered stone with note blocks down both sides of it. */
		BUS,
		/**
		 * A stacked module with a bus growing out of the side of it.
		 *
		 * <p>The head holds seven and hands on a full fifteen, because its centre block is strongly
		 * powered by the module's own repeater -- which is how a stacked module has always driven the
		 * repeater after it. Here that power goes into a cell of dust beside the centre instead, and
		 * the bus climbs onto that. One cell for the transition and no second repeater, so a chord
		 * that would have been a bus of {@code n} is a head of seven and a bus of {@code n - 7}.</p>
		 *
		 * <p>The dust may not run over the top of the head: a note block in the centre needs air
		 * directly above it, and that air is exactly the level a bus's wire runs at. So the way out
		 * is sideways, along the level the centre itself sits on.</p>
		 */
		STACKED_BUS,
		/**
		 * The same shape with a head of five, for when the two low slots behind are spoken for.
		 *
		 * <p>{@link #STACKED_FRONT} is to {@link #STACKED_FULL} what this is to
		 * {@link #STACKED_BUS}: the back pair goes unfilled and the notes that would have hung there
		 * go on the bus instead. That costs one cell, which still leaves it never longer than the
		 * plain bus it replaces and a cell shorter whenever the chord is odd -- so a chord that
		 * cannot reach back has no reason to give the head up entirely.</p>
		 *
		 * <p>Kept as its own style rather than as a flag on {@link #STACKED_BUS} because the head
		 * size decides the tail, the tail decides the length, and three separate places have to
		 * make that sum the same way. A style they all already read is the one thing they cannot
		 * disagree about.</p>
		 */
		STACKED_BUS_FRONT,
		/**
		 * The same shape with a head of six, for when only one of the two slots behind is spoken for.
		 *
		 * <p>The pair behind was one thing that was either available or not, so a module whose
		 * neighbour reached into one of its back slots gave up both -- a head of five, two notes pushed
		 * onto the bus, a cell longer than it had to be. The lane behind has exactly one live cell in
		 * the column that touches this one, so the most it can ever contest is one slot.</p>
		 *
		 * <p>ekran built it by hand at the breach on Guardian at 16 wide over six floors: a chord of
		 * twenty-three laid as a plain bus, rebuilt with one note in the centre and one in the free
		 * back flank, exactly one cell shorter. That cell is the whole difference between a bus of
		 * twelve cells, which wants sixteen blocks of wire to cut across a staircase, and eleven, which
		 * wants fifteen and has them.</p>
		 */
		STACKED_BUS_HALF;

		boolean stacked() {
			return this == STACKED_FRONT || this == STACKED_FULL || busHeaded();
		}

		/** Whether the shape ends in a run of bus, and so spends a cell of wire a note pair. */
		boolean buses() {
			return this == BUS || busHeaded();
		}

		/** Whether the shape needs the pair of low slots behind it free. */
		boolean reachesBack() {
			return this == STACKED_FULL || this == STACKED_BUS || this == STACKED_BUS_HALF;
		}

		/** Whether the shape is a head with a bus behind it, reaching back or not. */
		boolean busHeaded() {
			return this == STACKED_BUS || this == STACKED_BUS_FRONT || this == STACKED_BUS_HALF;
		}
	}

	private record EventGroup(int time, List<EventNote> notes, int length, int maxSafeTurnDistance,
			ChordStyle style, LaneReach reach) {
	}

	private record ChordStats(int peak, int peakTime, int overloadedTimes) {
	}

	private record CompactLayout(Map<Integer, Integer> spacingAt, int width, int depth) {
		int squareSize() {
			return Math.max(width, depth);
		}

		int area() {
			return width * depth;
		}
	}

	private record DelayTrigger(int cursor, int triggerDelay) {
	}

	private record SpatialDelayTrigger(Lane lane, int triggerDelay) {

		/**
		 * Where the module that this delay drives begins.
		 *
		 * <p>The whole route and not just the point, because a delay laid inside a turn has to hand
		 * on the corners still ahead of it: a chord placed from here bends where the wire does.</p>
		 */
		BlockPos cursor() {
			return lane.pos();
		}
	}

	enum PasteMode {
		COMPACT_CUBE("Compact cube"),
		COMPACT("Compact square"),
		COMPACT_LANE("Compact lane"),
		ULTRA_COMPACT_LANE("Ultra compact lane"),
		/**
		 * The same shapes, decided again from scratch. See {@link UltraLaneV2}.
		 *
		 * <p>Beside the mode above rather than replacing it, because that one builds every song in
		 * run/config today and this one has to earn its way past it on the numbers before anybody's
		 * world depends on it.</p>
		 */
		ULTRA_COMPACT_LANE_V2("Ultra compact lane v2"),
		LANE("Lane");

		private final String label;

		PasteMode(String label) {
			this.label = label;
		}

		String label() {
			return label;
		}
	}

	/**
	 * @param faults notes this layout would sound at the wrong moment, or not at all. Empty for
	 *     every finished layout; a build that has any is one you are meant to go and look at.
	 * @param turns the block each lane ended on, which is where it handed the signal to the next
	 *     one. A folding build wants these in as few columns as it has walls.
	 */
	/**
	 * @param nearWall world x of the wall a lane running back stops at, after the plan has slid
	 * @param farWall world x of the wall a lane running forward stops at, after the plan has slid.
	 *     Both are here so a debug build can say where the walls it was measured against actually
	 *     came out: a breach is a lane past one of them, and reading that off blocks means knowing
	 *     which column it was supposed to stop in. The slide is the reason it cannot be worked out
	 *     by hand -- a plan moves after it is walked, so the wall the walk used is not the wall you
	 *     are standing in front of.
	 */
	/**
	 * @param width the longer of the two ground spans, and {@code depth} the shorter. Which axis
	 *     each is depends on the build: a folded song can easily run further across than along, and
	 *     then {@code width} is the Z span. Use {@link #spanX} and {@link #spanZ} when the question
	 *     is about a direction rather than about which side is longer.
	 * @param spanX blocks the build covers along travel, the axis a lane runs down
	 * @param spanZ blocks the build covers across, the axis lanes step along. This is the one you
	 *     stand in front of and the one that grows when a turn goes flat or a slab steps sideways,
	 *     so it is the number to quote when the question is how the build looks rather than how
	 *     much of it there is.
	 */
	/**
	 * @param poweredAt every cell the walk believes carries the signal, in world space. Only for the
	 *     dead-wire pass, which needs both halves of the disagreement: this is what the build was
	 *     meant to light up, and the reader says what actually does. The difference is the dead line,
	 *     and neither side can name it alone -- most of a build is stone that was never meant to be
	 *     live, so "not reached" on its own would paint the whole thing.
	 */
	/**
	 * @param laidBy which shape laid each cell, in world space, and empty unless
	 *     {@link #NAME_EVERY_CELL} or {@link #DEBUG_PASTE} was on. The one fact about a build that
	 *     dies at {@code finish()} and that every fault wants first.
	 */
	record PastePlan(List<String> commands, int width, int depth, int height, int spanX, int spanZ,
			PasteMode mode, List<String> faults, List<BlockPos> turns, List<Integer> moved,
			List<Integer> breaches, List<Integer> recesses, Map<String, Integer> padding,
			int nearWall, int farWall, Map<BlockPos, String> collisions, Set<BlockPos> poweredAt,
			Map<BlockPos, String> laidBy, Map<BlockPos, Integer> noteTicks) {

		/**
		 * Cells of lane filled with wire rather than with music, counted by what asked for them.
		 *
		 * <p>The number worth watching is everything that is not {@code corner}. A corner has to hold
		 * dust because a repeater may not stand on one, so that much is the cost of turning at all.
		 * Every other entry is a column a chord could have been standing in -- and for a song whose
		 * every chord fits across a flat turn, built on one floor, the right total is nought.</p>
		 */
		int padCells() {
			return padding.entrySet().stream()
				.filter(entry -> !"corner".equals(entry.getKey()))
				.mapToInt(Map.Entry::getValue).sum();
		}

		/**
		 * Notes the layout check says would be set off a second time, at a moment nobody wrote.
		 *
		 * <p>Worth telling the player about separately from a breach, because it is a different kind
		 * of damage: an extra sounding is local to itself. A note block fires on a rising edge and
		 * the chain is one travelling pulse, so a doubled note adds a sound and takes nothing away --
		 * everything downstream of it plays exactly as written.</p>
		 */
		int wrongNotes() {
			return (int)faults.stream().filter(fault -> fault.startsWith("the note")).count();
		}

		/**
		 * Notes the layout had nowhere to hang, which the build simply does not contain.
		 *
		 * <p>Worse than a doubled note and worse than a breach, and until now the only fault of the
		 * four that nothing asked the plan for -- so the paste went up without mentioning it. A
		 * doubled note adds a sound; this one takes a sound away and there is no undoing it by
		 * standing somewhere else.</p>
		 */
		int missingNotes() {
			int lost = 0;
			for (String fault : faults) {
				if (fault.contains("had nowhere to hang")) {
					lost += leadingCount(fault);
				}
			}
			return lost;
		}

		/**
		 * Notes the signal never reaches, which is the whole song from the break onward.
		 *
		 * <p>The worst of the four by a distance: a break silences everything downstream of it, so
		 * this is not a count of faults but a count of what is left of the song. Read off the fault
		 * {@link #withUnreachedMarked} adds, which is only added when there is one.</p>
		 */
		int deadNotes() {
			for (String fault : faults) {
				if (fault.contains("would never be triggered")) {
					return leadingCount(fault);
				}
			}
			return 0;
		}

		/**
		 * The number a fault opens with, or nought.
		 *
		 * <p>Prose, parsed, which is the same shape {@link #wrongNotes} has always had. It is worth
		 * naming as a weakness rather than leaving it to be discovered: the wording of these faults
		 * has moved twice, and each time it moved the count would have gone quietly to nought rather
		 * than failing. Anything that starts depending on these in the walk should carry the number
		 * instead of reading it back out of the sentence.
		 */
		private static int leadingCount(String fault) {
			try {
				return Integer.parseInt(fault.split(" ")[0]);
			} catch (NumberFormatException notACount) {
				return 0;
			}
		}

		/**
		 * The faults, with every marked collision after them, for the paste to print when it is done.
		 *
		 * <p>Coordinates space-separated, so a line can be dragged into {@code /tp} as it stands.</p>
		 */
		List<String> report() {
			if (collisions.isEmpty()) {
				return faults;
			}
			List<String> lines = new ArrayList<>(faults);
			lines.add(collisions.size() + " collisions, marked with sea lantern:");
			collisions.forEach((at, what) -> lines.add("  " + at.getX() + " " + at.getY() + " "
				+ at.getZ() + "  " + what));
			return lines;
		}

		/** How far past the promised width the worst-behaved lane went, in blocks. */
		int worstBreach() {
			return breaches.stream().mapToInt(Integer::intValue).max().orElse(0);
		}

		/**
		 * How many steps forward the snake took, added up over every lane on every floor.
		 *
		 * <p>ekran's, and it replaces {@code depth} as the number to watch. Depth is a bad measure of a
		 * build: it moves in jumps, it depends on how many floors the config was handed, and a real
		 * optimisation can shorten the snake by hundreds of columns without moving it at all.</p>
		 *
		 * <p>A column is one step of corridor -- an {@code x, z} on one floor -- so the same
		 * {@code x, z} one floor up counts again, because the snake runs through both and pays for
		 * both. What hangs off the corridor does not count: a chord's note blocks are the music, they
		 * are the same however the build is laid out, and counting them buries the thing being
		 * measured under a constant.</p>
		 *
		 * <p>Measured off the walls and the turns, not off the blocks. A lane runs from one wall to the
		 * other, so its length is the distance between them -- and the turn recorded at the end of it
		 * says where it actually stopped. Counting blocks instead needs the corridor told apart from
		 * what hangs off it by block name, and it cannot be: a pad lays its dust on glass exactly as a
		 * climb does, and a descent's spiral is the same stone and dust as the lane.</p>
		 *
		 * <p><b>Climbs and descents are out.</b> A staircase is the handover between two lanes rather
		 * than either of them running forward, it costs the same whatever the build does, and counting
		 * it makes a build look longer for having more floors -- which is the complaint against depth
		 * in the first place. The wall-to-wall measure leaves them out by construction.</p>
		 *
		 * <p><b>A lane that breached is measured to where it turned</b>, not to the wall it should have
		 * turned at. That corridor was spent; hiding it inside the promised width is how it stops being
		 * counted.</p>
		 */
		int totalColumns() {
			int columns = 0;
			for (BlockPos turn : turns) {
				// The wall this lane came from is the far one of the two, so the run is the longer of
				// the two distances. A lane that overran is measured to where it turned rather than to
				// the wall it should have turned at, which is the point: a breach is corridor the build
				// spent, and hiding it inside the promised width is how it stops being counted.
				columns += Math.max(Math.abs(turn.getX() - nearWall), Math.abs(farWall - turn.getX()));
			}
			return columns;
		}

		/** Columns of corridor left empty by lanes that handed over before reaching their wall. */
		int recessedColumns() {
			return recesses.stream().mapToInt(Integer::intValue).sum();
		}

		/** How far inside its wall the worst-recessed lane turned, in columns. */
		int worstRecess() {
			return recesses.stream().mapToInt(Integer::intValue).max().orElse(0);
		}
	}

	private static final class PlacementPlan {
		/** A plan that records nothing, for walking the layout to measure it rather than build it. */
		static PlacementPlan dry() {
			PlacementPlan plan = new PlacementPlan();
			plan.recording = false;
			return plan;
		}

		private boolean recording = true;
		private final Map<BlockPos, String> blocks = new LinkedHashMap<>();
		/** Cells two shapes both wanted, and what each pair was, when {@link #DEBUG_PASTE}. */
		private final Map<BlockPos, String> collisions = new LinkedHashMap<>();
		/**
		 * What is being built right now, and what built each cell, when {@link #DEBUG_PASTE}.
		 *
		 * <p>The pair of blocks in a collision message says a note block met a staircase, which is one
		 * question short: a note block belongs to a chord, and which chord -- the one the lane ended
		 * on, the one across the turn, the head of a cut -- is what decides whose column has to move.
		 * Kept only while marking, because it is a string per block otherwise.</p>
		 */
		private String placing = "?";
		private final Map<BlockPos, String> placedBy = new LinkedHashMap<>();
		/**
		 * Cells of dust laid since the last repeater went down, counted rather than predicted.
		 *
		 * <p>Every other answer to "will the wire reach" in this file is arithmetic carried down the
		 * lane, and the counters beside it exist because that arithmetic drifts. This is the run
		 * itself: blocks are laid in the order the signal travels them, so the length of the current
		 * run is something the plan is holding rather than something it is predicting.</p>
		 *
		 * <p>The stacked cross does not count. It is dust the centre block lights, off to the side of
		 * the path, and counting it reads every run through a stacked module a cell long -- a mistake
		 * this file has made twice and spent an afternoon on both times.</p>
		 */
		private int runSinceRepeater;
		private int trialRun;

		int runSinceRepeater() {
			return runSinceRepeater;
		}
		/** Note block positions and the event tick each one belongs to. */
		private final Map<BlockPos, Integer> notes = new LinkedHashMap<>();
		/**
		 * Positions that receive direct power, and when.
		 *
		 * <p>Only these can set a note block off: a note block plays when a neighbour is
		 * <em>directly</em> powered, which means the block a repeater faces or a block with dust
		 * sitting on it. A block that is merely next to one of those does not pass it on, which is
		 * why a chord's side notes stay silent until their anchor fires and why two lanes can be
		 * packed to within one empty column of each other.</p>
		 */
		private final Map<BlockPos, Integer> powered = new LinkedHashMap<>();
		/** Where each lane handed over to the next one, in the order they were built. */
		private final List<BlockPos> turns = new ArrayList<>();
		/** How big each chord was that a lane gave up on and padded round instead of cutting. */
		private final List<Integer> moved = new ArrayList<>();
		/** Lanes that could not be landed on their wall, and what defeated them. */
		private final List<String> trouble = new ArrayList<>();
		/**
		 * Blocks by which a lane overstepped the wall its width was promised at, one per lane.
		 *
		 * <p>A different thing from a lane that stops short, and the reason to count them apart. A
		 * lane that stops short stays inside the footprint the paste offered and risks only its own
		 * staircase landing somewhere odd. A lane that oversteps puts blocks where the player was
		 * told nothing would go, which is how a paste quietly eats something already built there.</p>
		 */
		private final List<Integer> breaches = new ArrayList<>();
		/**
		 * Columns by which a lane stopped short of its wall, one per lane that did.
		 *
		 * <p>The other half of a breach, and worth as much watching. A short lane wastes the columns
		 * it never reached, but the expensive part is where it leaves the turn: a staircase that comes
		 * down inside the corridor instead of at the wall stands where no other lane's does, and the
		 * lane beside it hangs its notes into ground that is suddenly live. Ekran has traced two
		 * separate wrong-note faults to exactly that and asked for the source treated rather than the
		 * symptom, which needs the source counted first.</p>
		 */
		private final List<Integer> recesses = new ArrayList<>();
		/** Why each cell of wire-instead-of-music was laid, so that the ones with no reason show up. */
		private final Map<String, Integer> padding = new java.util.LinkedHashMap<>();
		/** Route cells the wire changes direction on, where a repeater can never work. */
		private final Set<BlockPos> corners = new java.util.HashSet<>();

		/**
		 * How far this cell is from the nearest corner on its own level, in blocks along the floor.
		 *
		 * <p>Both corners of a bend rather than the last one recorded, which is what
		 * {@link #inTurn} asks and is not the same question: a bend has two turning points and a
		 * chord wrapping it is near both. Levels are kept apart because a corner four floors down
		 * cannot reach anything here, and Manhattan because that is the shape of the rule ekran
		 * measured -- a stacked centre is clear at exactly three from a corner.</p>
		 */
		int cornerDistance(BlockPos at) {
			int nearest = Integer.MAX_VALUE;
			for (BlockPos corner : corners) {
				if (corner.getY() != at.getY()) {
					continue;
				}
				nearest = Math.min(nearest, Math.abs(at.getX() - corner.getX())
					+ Math.abs(at.getZ() - corner.getZ()));
			}
			return nearest;
		}

		/**
		 * A savepoint, so a shape that will not fit can be undone and a bus built instead.
		 *
		 * <p>The alternative is what this replaced: the first collision anywhere ends the whole
		 * build with an exception, and a song that will not paste at all is worse than any breach.
		 * A stacked module is the only shape that can be refused this way -- a bus grows until it
		 * has somewhere to put every note, so it is always the safe thing to fall back to.</p>
		 *
		 * <p>Journals rather than a copy of the plan. A build is thirty million blocks and a trial is
		 * opened per chord, so anything that walks the whole map per chord is not affordable; what a
		 * module writes is a few dozen positions, and those are what get remembered.</p>
		 */
		private record Trial(List<BlockPos> blocksAdded, Map<BlockPos, Integer> notesBefore,
				Map<BlockPos, Integer> poweredBefore, List<BlockPos> cornersAdded,
				int turnCount, int movedCount, int troubleCount, int breachCount, int recessCount,
				Map<String, Integer> padding,
				int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		}

		private Trial trial;

		/**
		 * A simple tail that can still be taken back up and laid again as a bus.
		 *
		 * <p>ekran's shape, and the reason it is safe: a simple tail is one column and the bus that
		 * replaces it is two, and the second of those two is <b>the pad's own column</b> -- so the
		 * chord after it opens in exactly the same place and nothing downstream moves. Which means the
		 * tail does not have to guess what follows it. It can be laid as the compact shape and undone
		 * by whatever turns up behind it, at the one moment that thing is known.</p>
		 *
		 * <p>Only a tail whose middle is a note block is ever recorded. A stone middle carries its own
		 * dust, is not a soft tip, and has nothing to be rescued from.</p>
		 *
		 * @param at the tail's own column, at bus height, which is where {@link #layBus} would start
		 * @param handoverDust the cell whose cross has to go back to plain wire, because the cross is
		 *     shaped for a middle that does not join and a bus cell joins
		 */
		record SoftTail(Lane at, List<EventNote> notes, int time, BlockPos handoverDust) {
		}

		/**
		 * What was written while the soft tail was going down, so it can be taken back up.
		 *
		 * <p>Independent of {@link #trial} rather than nested inside it. A trial is last-in-first-out
		 * and this has to outlive its enclosing one: the tail is laid deep inside the module's own
		 * trial, that trial commits at the end of the chord, and the question this exists to answer is
		 * not asked until the <em>next</em> chord. There is no stack discipline that expresses that,
		 * so it is a second recorder running alongside.</p>
		 */
		private Trial tailJournal;
		private Trial tailJournalBehind;
		private SoftTail softTail;
		private SoftTail softTailBehind;

		void padded(String reason) {
			if (recording) {
				padding.merge(reason, 1, Integer::sum);
			}
		}

		void corner(BlockPos position) {
			if (recording) {
				BlockPos key = position.immutable();
				boolean fresh = corners.add(key);
				if (fresh && trial != null) {
					trial.cornersAdded().add(key);
				}
				if (fresh && tailJournal != null) {
					tailJournal.cornersAdded().add(key);
				}
			}
		}

		/**
		 * Whether this cell is one the wire changes direction on.
		 *
		 * <p>Asked by anything that reads a block back and draws a conclusion from what it is. A
		 * corner is stone with dust over it, which is exactly what a stacked bus's handover is and
		 * what the cell in front of a stacked chord's cross is, so the blocks alone cannot tell a
		 * shape that hands power on from a lane that merely turned here.</p>
		 */
		boolean isCorner(BlockPos position) {
			return corners.contains(position.immutable());
		}
		private int minimumX = Integer.MAX_VALUE;
		private int minimumY = Integer.MAX_VALUE;
		private int minimumZ = Integer.MAX_VALUE;
		private int maximumX = Integer.MIN_VALUE;
		private int maximumY = Integer.MIN_VALUE;
		private int maximumZ = Integer.MIN_VALUE;

		/**
		 * Fills a position only if nothing is planned there yet.
		 *
		 * <p>Deliberately does not replace a planned air block: air above a note block is what keeps
		 * it audible, so overwriting one to prop something up would silence a note.</p>
		 */
		void support(BlockPos position, String block) {
			if (blocks.containsKey(position.immutable())) {
				return;
			}
			set(position, block);
		}


		/** Places a block and records that the signal reaches it at {@code time}. */
		void powered(BlockPos position, String block, int time) {
			set(position, block);
			powered(position, time);
		}

		void powered(BlockPos position, int time) {
			if (recording) {
				BlockPos key = position.immutable();
				if (trial != null && !trial.poweredBefore().containsKey(key)) {
					trial.poweredBefore().put(key, powered.get(key));
				}
				if (tailJournal != null && !tailJournal.poweredBefore().containsKey(key)) {
					tailJournal.poweredBefore().put(key, powered.get(key));
				}
				powered.put(key, time);
			}
		}

		/**
		 * Whether a note block could still go here, with its instrument under it and air over it.
		 *
		 * <p>A plan that records nothing answers yes to everything, which is right: a dry walk is
		 * measuring a shape, and the shape is the one the real walk would get if nothing were in
		 * the way.</p>
		 */
		/** Whether a note belonging to another tick is already planned here. */
		boolean noteAt(BlockPos position, int time) {
			Integer when = notes.get(position.immutable());
			return when != null && when != time;
		}

		/** Whether a block that goes live at another tick is already planned here. */
		boolean liveAt(BlockPos position, int time) {
			Integer when = powered.get(position.immutable());
			return when != null && when != time;
		}

		/** Scratch: what is planned here, for working out why a slot was refused. */
		String describeBlock(BlockPos position) {
			return blocks.getOrDefault(position.immutable(), "-");
		}

		boolean freeForNote(BlockPos position) {
			return !recording
				|| !blocks.containsKey(position.immutable())
					&& !blocks.containsKey(position.below().immutable())
					&& !blocks.containsKey(position.above().immutable());
		}

		void note(BlockPos position, int time) {
			if (recording) {
				BlockPos key = position.immutable();
				if (trial != null && !trial.notesBefore().containsKey(key)) {
					trial.notesBefore().put(key, notes.get(key));
				}
				if (tailJournal != null && !tailJournal.notesBefore().containsKey(key)) {
					tailJournal.notesBefore().put(key, notes.get(key));
				}
				notes.put(key, time);
			}
		}

		/**
		 * Lifts a block back out of the plan so it can be put down somewhere else.
		 *
		 * <p>The one place anything is un-placed. {@link #set} refuses to overwrite on purpose --
		 * two things wanting the same block is a layout fault and should say so -- so a block that
		 * genuinely moves has to be taken up first rather than written over. Used by the two-swap
		 * turn, which trades a note and a repeater rather than adding either.</p>
		 *
		 * @return what was there, or {@code "-"} if nothing was
		 */
		String take(BlockPos position) {
			if (!recording) {
				return "-";
			}
			BlockPos key = position.immutable();
			notes.remove(key);
			powered.remove(key);
			String was = blocks.remove(key);
			return was == null ? "-" : was;
		}

		/** When the note that was here belonged, for putting it down again elsewhere. */
		Integer noteTime(BlockPos position) {
			return notes.get(position.immutable());
		}

		/**
		 * Records the block a lane ended on, which is where its turn begins.
		 *
		 * <p>Reported rather than inferred from the blocks. Every candidate signature is shared with
		 * something else -- glass belongs to a climb and to a parity pad both, powered stone to a
		 * descent and to every bus in the build -- so counting blocks answers a different question
		 * than the one worth asking, which is whether the turns stand in the same columns.</p>
		 */
		void turnedAt(BlockPos position) {
			if (recording) {
				if (TRACE_TURNS) {
					// Which shape laid it, not just where. A turn is recorded by four different
					// shapes and the trace above only prints the one that asked to turn, so a turn
					// that moves between two builds cannot otherwise be attributed to anything.
					System.out.println("TURNEDAT " + position.getX() + " " + position.getY() + " "
						+ position.getZ() + "  " + placing);
				}
				turns.add(position.immutable());
			}
		}

		void moved(int notes) {
			if (recording) {
				moved.add(notes);
			}
		}

		void trouble(String what) {
			if (recording) {
				trouble.add(what);
			}
		}

		void breached(int blocks) {
			if (recording && blocks > 0) {
				breaches.add(blocks);
			}
		}

		void recessed(int columns) {
			if (recording && columns > 0) {
				recesses.add(columns);
			}
		}

		/**
		 * Checks that every note block plays, and plays once, at the moment it is supposed to.
		 *
		 * <p>A layout mistake here is silent: the build goes up, looks right, and plays a note two
		 * lanes away half a bar early. Both halves matter -- an untriggered note is a hole in the
		 * song, and a note reached by a foreign event is a wrong note -- and between them they are
		 * what makes it safe to pack lanes by measurement rather than by a constant.</p>
		 *
		 * <p>Being reached <em>later</em> is a different matter, and this is where a note block
		 * differs from every other thing redstone drives: it sounds on the rising edge and on
		 * nothing else. A whole chain is one travelling pulse from one lever, so every repeater it
		 * has already passed is still holding its output high. A block going live beside a note
		 * that is already powered therefore adds no edge and makes no sound, and two modules can
		 * share a block, which is how a hand-built machine gets six notes out of one repeater. Only
		 * within a repeater of each other, though: further apart and the first pulse may have ended
		 * before the second arrives, which would be a second edge and a second note.</p>
		 */
		/**
		 * @param shiftX how far the finished plan slides before it is built, so a fault names the
		 *     block you can actually go and stand in front of rather than one in plan space
		 */
		/**
		 * Which shape laid each end of a wrong note, where the plan was recorded with names on.
		 *
		 * <p>Both halves, because the fix belongs to whichever of the two can move and that is not
		 * always the one that sounds early. Every wrong note a two-rail run brought with it had to be
		 * read off a block dump to find out which side was the run, and the answer was in the plan the
		 * whole time.</p>
		 */
		private String whose(BlockPos note, BlockPos neighbour) {
			String victim = placedBy.get(note);
			String aggressor = placedBy.get(neighbour);
			return victim == null && aggressor == null ? ""
				: " -- " + (victim == null ? "?" : victim) + " against "
					+ (aggressor == null ? "?" : aggressor);
		}

		/**
		 * Notes {@link #verify} found sounding at a moment nobody wrote, and notes it found silent.
		 *
		 * <p>The same two things the faults say in words. Kept as positions as well because a fault is
		 * a line of chat and a build is nine thousand notes: reading one means finding it, and finding
		 * it is what the marks are for.</p>
		 */
		private final Set<BlockPos> wrongNotesAt = new java.util.HashSet<>();
		private final Set<BlockPos> missedNotesAt = new java.util.HashSet<>();

		List<String> verify(int shiftX, int shiftZ) {
			List<String> faults = new ArrayList<>();
			for (Map.Entry<BlockPos, Integer> note : notes.entrySet()) {
				int time = note.getValue();
				Integer own = powered.get(note.getKey());
				boolean triggered = own != null && own == time;
				for (Direction direction : Direction.values()) {
					Integer neighbour = powered.get(note.getKey().relative(direction));
					if (neighbour == null) {
						continue;
					}
					if (neighbour == time) {
						triggered = true;
						continue;
					}
					if (neighbour < time) {
						// Naming the block that does it, not just the note it happens to. Which side it
						// comes from is the whole diagnosis: along the lane is one module reaching into
						// the next, across the lane is the corridor alongside, and the two want opposite
						// fixes. Without it there is nothing to do but guess, which is what happened.
						wrongNotesAt.add(note.getKey());
						faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
							+ " belongs to tick " + time + " but would sound early, at tick "
							+ neighbour + ", from the " + direction + " at "
							+ describe(note.getKey().relative(direction), shiftX, shiftZ)
							+ whose(note.getKey(), note.getKey().relative(direction)));
					} else if (neighbour > time + SHARED_PULSE_TICKS) {
						// Named the same way round as the early case. Which side a second sounding comes
						// from is as much the diagnosis here as it is there -- along the lane is one
						// module reaching into the next, across it is the corridor alongside -- and
						// leaving it off meant every one of these had to be traced by hand.
						wrongNotesAt.add(note.getKey());
						faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
							+ " belongs to tick " + time + " but would sound again at tick "
							+ neighbour + ", from the " + direction + " at "
							+ describe(note.getKey().relative(direction), shiftX, shiftZ)
							+ ", too late for the first pulse to still be covering it");
					}
				}
				if (!triggered) {
					missedNotesAt.add(note.getKey());
					faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
						+ " has nothing to set it off");
				}
			}
			faults.sort(null);
			return faults;
		}

		/**
		 * How much later a block may go live beside a note without sounding it a second time.
		 *
		 * <p>The length of a stone button press, which is what the machine is actually started with.
		 * What decides whether a second block adds an edge is how long the first pulse is still
		 * holding the note high, and that is a property of the source, not of the distance between
		 * two modules.</p>
		 *
		 * <p>It was four -- one repeater at its longest, on the reasoning that anything further apart
		 * has a repeater of its own between it and nothing left to share. That reasoning is about
		 * whether two modules can touch, which is a different question from whether the pulse has
		 * ended, and being wrong about it costs more than being cautious usually does: {@code
		 * wrongNotes()} is built on this, and by {@code betterThan} a wrong note outranks every
		 * breach number in the file. So a phantom here can veto a real improvement, and did.</p>
		 *
		 * <p>ekran found it from the world, which is the only place it could have been found. The
		 * layout check called a note at {@code 3 69 140} doubled -- eight ticks between its own pulse
		 * and the corner cell laid beside it -- and playing the build showed it sounding once, because
		 * the button was still holding the first pulse high when the second arrived.</p>
		 */
		private static final int SHARED_PULSE_TICKS = 10;

		private static String describe(BlockPos position) {
			return describe(position, 0, 0);
		}

		private static String describe(BlockPos position, int shiftX, int shiftZ) {
			return (position.getX() + shiftX) + " " + position.getY() + " "
				+ (position.getZ() + shiftZ);
		}

		/** Names the shape about to be built, so a collision can say whose column it is. */
		void placing(String what) {
			placing = what;
		}

		/** What it is called at the moment, for a helper that has to name itself and put it back. */
		String placing() {
			return placing;
		}

		/**
		 * Whether the module just built ended on a cell that only dust powers.
		 *
		 * <p>Carried forward rather than looked back at, which is what makes the parity pad's answer
		 * cheap. A simple tail's middle is lit by the handover's dust and by nothing else: it drives a
		 * repeater standing against it and it cannot light dust. So the cell after it may be a
		 * repeater and may not be a pad -- and the pad is laid by the *next* chord, which is a
		 * decision made after this one and with no reason to look back at it. One boolean travelling
		 * forward turns a retroactive question into a local one, the same way {@code tipSignal} and
		 * {@code columnBehindBusy} already do for the walk.</p>
		 */
		private boolean softTip;

		/** What the module before this one ended on, which is what a pad has to ask. */
		private boolean softBehind;

		/**
		 * Where a stacked bus left a tail a run can open straight off, or null.
		 *
		 * <p>The column of the tail itself. A rail-ready tail is already the run's first path column --
		 * it sounds with the head, at the head's own tick, because the handover adds no delay -- and
		 * the cell below it is empty, which is where the floor rail's repeater goes. So a run opening
		 * here spends no column at all: no head, no trigger, nothing. Carried forward for the same
		 * reason {@code softBehind} is, and rolled at the same moment.</p>
		 */
		private BlockPos railTail;

		private BlockPos railTailBehind;

		/**
		 * The cell the module just built hands its power forward out of, or null.
		 *
		 * <p>Recorded rather than recognised. A stacked bus's handover is a stone at lane level with
		 * dust over it, and so is <b>a corner</b>, and so is an ordinary cell of pad -- the blocks are
		 * the same blocks. The rail's seed was reading those two levels and concluding a handover was
		 * behind it, and on {@code all-my-fellas} 40x5 what was actually behind it was the lane's own
		 * wire coming round a bend: three blocks from the nearest marked corner, so no corner test
		 * could have caught it either. The dust on a corner carries the path round and powers the
		 * stone <i>beneath itself</i>, not the cell in front, so the floor rail seeded off it never
		 * went live and two notes went with it.</p>
		 *
		 * <p>Rolled with {@link #railTail} and for the same reason. Null is the answer that refuses
		 * the seed, which is the safe way round: a shape that does not say where its handover is does
		 * not get a free floor rail.</p>
		 */
		private BlockPos handover;

		private BlockPos handoverBehind;

		void softTip(boolean soft) {
			softTip = soft;
		}

		/**
		 * Whether the module just built ended soft, before the flag has been rolled.
		 *
		 * <p>The cut path wants this one and an ordinary chord wants {@link #softBehind()}, and the
		 * difference is only which of them has run yet: {@link #buildShaped} rolls on entry, so during
		 * a chord's build the module behind is {@code softBehind}; a cut is built straight from the
		 * walk and never rolls, so during a cut the module behind is still this. Asking the wrong one
		 * is a guard that never fires.</p>
		 */
		boolean softTip() {
			return softTip;
		}

		/**
		 * Hand this module's answer back a step and start a fresh one.
		 *
		 * <p>Two fields and not one, because the module being built and the module before it are both
		 * live at the same moment: the pad is laid at the front of module N and asks about module N-1.
		 * Clearing a single flag at the top of the build read the module's own answer -- which is nought,
		 * because it has not been built yet -- and the split never fired once.</p>
		 */
		void rollSoftTip() {
			softBehind = softTip;
			softTip = false;
			railTailBehind = railTail;
			railTail = null;
			handoverBehind = handover;
			handover = null;
			tailJournalBehind = tailJournal;
			tailJournal = null;
			softTailBehind = softTail;
			softTail = null;
		}

		/**
		 * Starts recording a simple tail, so whatever is laid after it can take it back up.
		 *
		 * <p>Opened before the handover rather than before the tail, because the handover's dust is
		 * shaped for the tail it drives -- a cross where the middle does not join it, plain wire where
		 * a bus cell does -- so undoing the tail has to undo that as well.</p>
		 */
		void beginSoftTail(SoftTail what) {
			softTail = what;
			tailJournal = new Trial(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
				new ArrayList<>(), 0, 0, 0, 0, 0, Map.of(), 0, 0, 0, 0, 0, 0);
		}

		/** Stops recording, keeping what was recorded. The tail is down; the question comes later. */
		void endSoftTail() {
			if (softTail == null) {
				tailJournal = null;
			}
		}

		/** Forgets a tail that is not going to be laid after all, and stops recording for it. */
		void discardSoftTail() {
			tailJournal = null;
			softTail = null;
		}

		/** The tail the module being built has just laid, for the cut path, which does not roll. */
		SoftTail softTail() {
			return softTail;
		}

		/** The tail the module <em>behind</em> laid, for an ordinary chord, whose build has rolled. */
		SoftTail softTailBehind() {
			return softTailBehind;
		}

		/**
		 * Takes the recorded tail back up, leaving the ground as it was before the handover.
		 *
		 * <p>Only the blocks, the notes, the powered cells and the corners. Not the census, not the
		 * bounds, not the turn and breach lists -- unlike a trial this undoes something that was laid
		 * an entire chord ago, so those have moved on for reasons that have nothing to do with the
		 * tail and putting them back would erase them.</p>
		 */
		void undoSoftTail(boolean rolled) {
			Trial undo = rolled ? tailJournalBehind : tailJournal;
			if (rolled) {
				tailJournalBehind = null;
				softTailBehind = null;
			} else {
				tailJournal = null;
				softTail = null;
			}
			if (undo == null) {
				return;
			}
			for (BlockPos at : undo.blocksAdded()) {
				blocks.remove(at);
				placedBy.remove(at);
			}
			undo.notesBefore().forEach((at, was) -> {
				if (was == null) {
					notes.remove(at);
				} else {
					notes.put(at, was);
				}
			});
			undo.poweredBefore().forEach((at, was) -> {
				if (was == null) {
					powered.remove(at);
				} else {
					powered.put(at, was);
				}
			});
			corners.removeAll(undo.cornersAdded());
		}

		/**
		 * Whether the lane changes floors after the chord being built.
		 *
		 * <p>Handed down rather than worked out below, because it is a fact about the walk: the chord
		 * cannot see that the repeater meant to follow it is about to be built at the top of a
		 * staircase instead of in the next column.</p>
		 */
		private boolean turnAhead;

		void turnAhead(boolean ahead) {
			turnAhead = ahead;
		}

		/**
		 * How many ticks after this chord the next event lands, or a large number for the last one.
		 *
		 * <p>Handed down for the same reason {@code turnAhead} is: it is a fact about the walk, and the
		 * chord being built cannot see the event after it. See
		 * {@link #SIMPLE_TAIL_GIVES_WAY_TO_A_TIGHT_GAP}.</p>
		 *
		 * <p>The number and not the verdict, because the verdict wanted a threshold and the threshold
		 * had to be measured. This is the <b>gap</b>, which is an upper bound on the delay the next
		 * chord's own repeater is actually set to -- {@code spentPadding} comes out of it first -- so
		 * a rule written against it is right in one direction and optimistic in the other.</p>
		 */
		private int gapAhead = Integer.MAX_VALUE;

		void gapAhead(int ticks) {
			gapAhead = ticks;
		}

		int gapAhead() {
			return gapAhead;
		}

		/**
		 * Whether the walk laying this build answers the questions a shape can only ask of the walk.
		 *
		 * <p>The difference between a guard that says no and a guard nobody asked. {@code turnAhead}
		 * and {@code gapAhead} default to the answers that <i>keep</i> the
		 * simple tail -- so a walk that never sets them takes the shape with every one of its guards
		 * switched off. Only {@link #walkV2} sets them, so only walkV2 may have the shape.</p>
		 */
		private boolean answersWhatIsAhead;

		void answersWhatIsAhead(boolean answers) {
			answersWhatIsAhead = answers;
		}

		boolean answersWhatIsAhead() {
			return answersWhatIsAhead;
		}

		boolean turnAhead() {
			return turnAhead;
		}

		void railTail(BlockPos at) {
			railTail = at;
		}

		void handover(BlockPos at) {
			handover = at.immutable();
		}

		/**
		 * Where the module just built hands over, asked the same way {@link #railTail()} is.
		 *
		 * <p>This one and not the rolled copy, for the reason written there: a run is decided between
		 * builds, so the module just laid is still the current one.</p>
		 */
		BlockPos handover() {
			return handover;
		}

		BlockPos railTailBehind() {
			return railTailBehind;
		}

		/**
		 * The tail the module just built left, if a run can open off it.
		 *
		 * <p>This one and not {@link #railTailBehind()}, which is the trap that made the whole thing
		 * never fire. The roll happens inside {@code buildShaped}, and the walk asks this question
		 * *before* building anything -- indeed it asks it in order to decide not to build a chord at
		 * all. So at the moment of asking, the tail just laid is still here and the rolled copy is a
		 * module older. A pad asks the rolled one because a pad is laid during the next build; a run
		 * asks this one because a run is decided between builds.</p>
		 */
		BlockPos railTail() {
			return railTail;
		}

		boolean softBehind() {
			return softBehind;
		}

		void set(BlockPos position, String block) {
			if (!recording) {
				return;
			}
			// Caught here rather than at each placement, because the whole point is to notice the one
			// that got past the rule. A repeater cannot convey power round a right angle, so one
			// standing on a corner is a broken machine however it came to be there.
			if (block.startsWith("minecraft:repeater") && corners.contains(position.below())) {
				padded("REPEATER-ON-CORNER");
			}
			BlockPos key = position.immutable();
			String existing = blocks.putIfAbsent(key, block);
			if (existing == null && trial != null) {
				trial.blocksAdded().add(key);
			}
			if (existing == null && tailJournal != null) {
				tailJournal.blocksAdded().add(key);
			}
			if ((DEBUG_PASTE || NAME_EVERY_CELL) && existing == null) {
				placedBy.put(key, placing);
			}
			if (existing == null) {
				if (block.startsWith("minecraft:repeater")) {
					runSinceRepeater = 0;
				} else if ("minecraft:redstone_wire".equals(block)) {
					runSinceRepeater++;
					// The cell that puts a run past what a repeater reaches, and the shape that laid
					// it. Every guard in this file asks before building; this one notices after, which
					// is the only way to catch the shape nobody thought to guard.
					if (runSinceRepeater > DUST_RANGE) {
						padded("overran " + placing);
					}
				}
			}
			if (existing != null && !existing.equals(block)) {
				if (!DEBUG_PASTE) {
					throw new IllegalArgumentException("Placement layout collision at "
						+ describe(key) + ": " + existing + " is already there and " + block
						+ " wants the same block");
				}
				// What was standing wins, so the rest of the walk carries on over the layout it would
				// have had anyway. Only the first claim on a cell is remembered: a column that gets
				// wanted three times is still one place to go and stand.
				collisions.putIfAbsent(key, existing + " (" + placedBy.getOrDefault(key, "?")
					+ ") held off " + block + " (" + placing + ")");
			}
			if (!"minecraft:air".equals(block)) {
				minimumX = Math.min(minimumX, key.getX());
				minimumY = Math.min(minimumY, key.getY());
				minimumZ = Math.min(minimumZ, key.getZ());
				maximumX = Math.max(maximumX, key.getX());
				maximumY = Math.max(maximumY, key.getY());
				maximumZ = Math.max(maximumZ, key.getZ());
			}
		}

		/**
		 * Whether anything written since the savepoint stands in one of these cells.
		 *
		 * <p>The occupancy question asked the other way round. Everywhere else in this file a shape
		 * asks whether the ground is free before it builds; this asks, once it is built, whether it
		 * has landed anywhere it should not have -- which is the only form of the question that can
		 * be put to a shape whose footprint nobody has written down.</p>
		 */
		boolean trialTouches(Set<BlockPos> cells) {
			if (trial == null) {
				return false;
			}
			for (BlockPos at : trial.blocksAdded()) {
				if (cells.contains(at)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Whether the trial hung a note against something that goes live on another tick.
		 *
		 * <p>{@link #trialTouches} for the other fault a shape cannot see coming. Every slot check in
		 * this file asks before it builds, and the answer keeps being wrong for the same reason: a
		 * module's low notes are decided against ground the module has not finished occupying. Asked
		 * afterwards it is not a prediction at all -- the blocks are down, the ticks are recorded, and
		 * the question is simply whether any note the trial added has a neighbour belonging to someone
		 * else. The module's own cells are live at its own tick and {@link #liveAt} excludes those, so
		 * a shape standing on its own does not answer yes.</p>
		 */
		boolean trialHangsANoisyNote(int time) {
			if (trial == null) {
				return false;
			}
			for (Map.Entry<BlockPos, Integer> was : trial.notesBefore().entrySet()) {
				// Added by this trial, not merely retimed: an entry that had a note before belongs to
				// whoever put it there.
				if (was.getValue() != null || !notes.containsKey(was.getKey())) {
					continue;
				}
				for (Direction direction : Direction.values()) {
					if (liveAt(was.getKey().relative(direction), time)) {
						return true;
					}
				}
			}
			return false;
		}

		/** Opens a savepoint. Nested trials are not needed and not supported. */
		void beginTrial() {
			trialRun = runSinceRepeater;
			trial = new Trial(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
				new ArrayList<>(), turns.size(), moved.size(), trouble.size(), breaches.size(),
				recesses.size(), new LinkedHashMap<>(padding),
				minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
		}

		void commitTrial() {
			trial = null;
		}

		/** Puts back everything written since {@link #beginTrial()}. */
		void rollbackTrial() {
			runSinceRepeater = trialRun;
			Trial undo = trial;
			// Cleared first: putting the old values back goes through the same writers, and a trial
			// still open would journal the undo as though it were more building.
			trial = null;
			// And the tail recorder with it. A tail is always laid inside the module's own trial, so a
			// trial that rolls back is a tail that never happened -- and a journal pointing at cells
			// this is about to remove would put stale notes back if anything undid it afterwards.
			tailJournal = null;
			softTail = null;
			if (undo == null) {
				return;
			}
			for (BlockPos at : undo.blocksAdded()) {
				blocks.remove(at);
				// The name goes back with the block. A stacked module that will not fit is rolled back
				// and a bus built over the same cells, and a label left behind from the shape that was
				// undone would colour that bus after something the build has not got.
				placedBy.remove(at);
			}
			undo.notesBefore().forEach((at, was) -> {
				if (was == null) {
					notes.remove(at);
				} else {
					notes.put(at, was);
				}
			});
			undo.poweredBefore().forEach((at, was) -> {
				if (was == null) {
					powered.remove(at);
				} else {
					powered.put(at, was);
				}
			});
			corners.removeAll(undo.cornersAdded());
			trim(turns, undo.turnCount());
			trim(moved, undo.movedCount());
			trim(trouble, undo.troubleCount());
			trim(breaches, undo.breachCount());
			trim(recesses, undo.recessCount());
			padding.clear();
			padding.putAll(undo.padding());
			minimumX = undo.minX();
			minimumY = undo.minY();
			minimumZ = undo.minZ();
			maximumX = undo.maxX();
			maximumY = undo.maxY();
			maximumZ = undo.maxZ();
		}

		private static void trim(List<?> list, int to) {
			while (list.size() > to) {
				list.remove(list.size() - 1);
			}
		}

		PastePlan finish(PasteMode mode, BlockPos origin) {
			return finish(mode, origin, origin.getX(), origin.getX());
		}

		/**
		 * The block actually built at a cell, which is the planned one unless the paste is marked up.
		 *
		 * <p>Everything here is in plan space, before the slide, because that is the space the walk
		 * recorded its own opinions in. The one thing it must not do is change what gets built for a
		 * plain paste, which is why every arm falls through to {@code block}.</p>
		 *
		 * @param walled whether {@code nearWall} and {@code farWall} are real walls, so that a block
		 *     outside them means a lane that overstepped rather than a mode that has no walls
		 */
		private String marked(BlockPos at, String block, int nearWall, int farWall, boolean walled) {
			if (!DEBUG_PASTE) {
				return block;
			}
			// Silent, and lit so it is findable down a corridor. Nothing downstream loses anything by
			// it: a note hangs off the side of the wire and never carries it, so a note block swapped
			// for something that conducts nothing still leaves every note after it sounding.
			//
			// Note blocks only. A sound effect is two cells that belong together -- a door has a top
			// half, a piston has something to push -- and replacing the lower one leaves the other
			// half of it standing on nothing.
			if (block.startsWith("minecraft:note_block") && wrongNotesAt.contains(at)) {
				return "minecraft:waxed_copper_bulb[lit=true]";
			}
			// The air a note block insists on, which a head may stand in without silencing it. Only
			// where that cell really is air: an effect puts its own second half up there.
			if ("minecraft:air".equals(block) && missedNotesAt.contains(at.below())) {
				return "minecraft:dragon_head[rotation=8]";
			}
			if (!"minecraft:stone".equals(block)) {
				return block;
			}
			// Never the block under a note. The instrument is read off it, so recolouring one is
			// retuning it -- and stripped hyphae under a note block is not a marked kick, it is a bass.
			if (notes.containsKey(at.above())) {
				return block;
			}
			if (walled && (at.getX() < nearWall - WALL_OVERSHOOT
					|| at.getX() > farWall + WALL_OVERSHOOT)) {
				return "minecraft:stripped_crimson_hyphae[axis=x]";
			}
			return shapeStone(placedBy.get(at));
		}

		/**
		 * The tick each note block was laid for, in world space.
		 *
		 * <p>The walk's own answer to "what is this note, and when is it meant to sound", which until
		 * now died at {@code finish()} along with everything else it knew. The reader reconstructs the
		 * same thing from the blocks, and that is the point: where the two disagree, the machine is not
		 * playing what the walk wrote, and only having both in hand can say so. Ticks here are the
		 * walk's own -- one of them is one repeater tick, which is what every delay in this file is
		 * counted in.</p>
		 */
		Map<BlockPos, Integer> noteTicks(int shiftX, int shiftZ) {
			Map<BlockPos, Integer> when = new LinkedHashMap<>();
			notes.forEach((at, tick) -> when.put(at.offset(shiftX, 0, shiftZ), tick));
			return Map.copyOf(when);
		}

		/** What laid each cell, in world space, for a fault that wants to name the shapes either end. */
		Map<BlockPos, String> laidBy(int shiftX, int shiftZ) {
			Map<BlockPos, String> named = new LinkedHashMap<>();
			placedBy.forEach((at, what) -> named.put(at.offset(shiftX, 0, shiftZ), what));
			return Map.copyOf(named);
		}

		/** Cells the walk expects to carry the signal, in world space, for the dead-wire pass. */
		Set<BlockPos> poweredAt(int shiftX, int shiftZ) {
			return powered.keySet().stream().map(at -> at.offset(shiftX, 0, shiftZ))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}

		PastePlan finish(PasteMode mode, BlockPos origin, int nearWall, int farWall) {
			// Where the plan lands, worked out before anything is checked so that a fault can name a
			// block you are able to go and stand in front of. Nothing lands behind you: the walk
			// reaches outside its own walls here and there -- a chord hanging off the far side of the
			// first lane, a corner overshooting the end of one, a floor not quite the width of the
			// one below it -- and a build that starts a block behind where you were standing is a
			// build you cannot line up from a corner. Sliding it is exact and costs nothing; the
			// height is left alone, because that is measured from your feet and not from a wall.
			int shiftX = minimumX == Integer.MAX_VALUE ? 0 : origin.getX() - minimumX;
			int shiftZ = minimumZ == Integer.MAX_VALUE ? 0 : origin.getZ() - minimumZ;
			if (TRACE) {
				System.out.println("SHIFT x+" + shiftX + " z+" + shiftZ
					+ " (add these to any position the walk trace prints)");
			}
			List<String> faults = new ArrayList<>(trouble);
			faults.addAll(verify(shiftX, shiftZ));
			// Every other layout is finished, so a fault in one is a bug and the build is refused.
			// The ultra lane is still being worked out on multiple floors, where the run that carries
			// the signal sideways passes under the notes of the corridors either side of it, and a
			// machine you cannot stand in front of is a machine you cannot work out. So it goes up,
			// and says what is wrong with it.
			// And v2 for the same reason, more so: it is the one being worked out now, and refusing to
			// paste it is refusing ekran the only view of it that has ever settled an argument here.
			// A broken v2 build goes up and says what is wrong with it, in the faults overlay.
			if (!faults.isEmpty() && mode != PasteMode.ULTRA_COMPACT_LANE
					&& mode != PasteMode.ULTRA_COMPACT_LANE_V2) {
				throw new IllegalArgumentException("Refusing to build a broken machine: "
					+ faults.get(0) + ". This is a bug in the layout, not in the song.");
			}
			// Only the ultra lane has walls worth measuring a breach against: every other mode passes
			// its own origin for both, so outside-the-walls would mean the whole build.
			boolean walled = mode == PasteMode.ULTRA_COMPACT_LANE && !breaches.isEmpty();
			List<String> commands = new ArrayList<>(blocks.entrySet().stream()
				.map(entry -> "setblock " + (entry.getKey().getX() + shiftX) + " "
					+ entry.getKey().getY() + " " + (entry.getKey().getZ() + shiftZ) + " "
					+ marked(entry.getKey(), entry.getValue(), nearWall, farWall, walled) + " replace")
				.toList());
			// The marking pass, last so that it wins: every other claim on the cell has been made by
			// now, and a lantern placed halfway through would be quietly built over by the next chord.
			Map<BlockPos, String> marked = new LinkedHashMap<>();
			for (Map.Entry<BlockPos, String> clash : collisions.entrySet()) {
				BlockPos at = clash.getKey().offset(shiftX, 0, shiftZ);
				marked.put(at, clash.getValue());
				commands.add("setblock " + at.getX() + " " + at.getY() + " " + at.getZ()
					+ " minecraft:sea_lantern replace");
			}
			int widthX = maximumX < minimumX ? 0 : maximumX - minimumX + 1;
			int widthZ = maximumZ < minimumZ ? 0 : maximumZ - minimumZ + 1;
			int height = maximumY < minimumY ? 0 : maximumY - minimumY + 1;
			return new PastePlan(commands, Math.max(widthX, widthZ), Math.min(widthX, widthZ), height,
				widthX, widthZ,
				mode, List.copyOf(faults),
				turns.stream().map(turn -> turn.offset(shiftX, 0, shiftZ)).toList(),
				List.copyOf(moved), List.copyOf(breaches), List.copyOf(recesses),
				Map.copyOf(padding), nearWall + shiftX, farWall + shiftX, Map.copyOf(marked),
				poweredAt(shiftX, shiftZ), laidBy(shiftX, shiftZ), noteTicks(shiftX, shiftZ));
		}
	}
}
