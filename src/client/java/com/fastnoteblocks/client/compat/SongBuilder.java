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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
			int time = 0;
			int order = 0;
			for (Step step : project.toSteps(layer)) {
				if (step.type() == StepType.NOTE) {
					notes.add(new EventNote(time, layerIndex + 1, order++, step.value(), instrumentBlock));
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
					notes.add(new EventNote(time, trackIndex + 1, order++, step.value(), instrumentBlock));
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
		return createPastePlan(minecraft, eventNotes(tracks), mode);
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

	/** How far a chord too small to need a bus reaches either side of its lane. */
	private static LaneReach smallChordReach(int chordSize, int margin) {
		if (chordSize <= 1) {
			return new LaneReach(0, 0, margin, false);
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
			PasteMode mode) {
		// Fixed world axes rather than the player's facing: travel runs +X and lanes step +Z, so a
		// build always grows into positive coordinates and you know where it will land before you
		// commit to it. Alternating floors double back inside that volume, never past the origin.
		return createPastePlan(pasteOrigin(minecraft, Direction.EAST), notes, mode);
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
	record BuildLimits(int maxFloors, int laneWidth, int laneFloors) {
		static BuildLimits fromConfig() {
			FastNoteblocksConfig config = FastNoteblocksConfig.get();
			return new BuildLimits(config.maxBuildFloors(), config.buildLaneWidth(),
				config.buildLaneFloors());
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
		if (notes.isEmpty()) {
			throw new IllegalArgumentException(
				"The build sequence is empty. Move some layers into it first.");
		}
		ChordStats stats = chordStats(notes);
		if (stats.peak() > MAX_SIMULTANEOUS_NOTES) {
			throw new IllegalArgumentException(overloadMessage(stats));
		}
		Direction forward = Direction.EAST;
		return switch (mode) {
			case COMPACT_CUBE -> createCubePastePlan(origin, forward, notes, limits.maxFloors());
			case COMPACT -> createCompactPastePlan(origin, forward, notes);
			case COMPACT_LANE -> createLanePastePlan(origin, forward, notes, limits.laneWidth(),
				limits.laneFloors(), Layout.STANDARD, PasteMode.COMPACT_LANE);
			case ULTRA_COMPACT_LANE -> createLanePastePlan(origin, forward, notes, limits.laneWidth(),
				limits.laneFloors(), Layout.ultra(limits.laneFloors(), origin), PasteMode.ULTRA_COMPACT_LANE);
			case LANE -> createStraightPastePlan(origin, forward, notes);
		};
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
			List<EventNote> notes, int width, int floors, Layout layout, PasteMode mode) {
		List<EventGroup> events = eventGroups(notes, layout);
		// Two blocks of the width go on the fold itself: the turn steps one past the end of a lane
		// and a corner carrying notes reaches one past that. The wall still has to clear the longest
		// single event, or an event too big to fit would turn on every attempt and never advance.
		int longest = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		int laneWidth = Math.max(longest + 2, width - 2);
		PlacementPlan placements = new PlacementPlan();
		if (floors <= 1) {
			walkFolded(events, origin, forward, laneWidth, Integer.MAX_VALUE, placements, layout);
		} else {
			walkWall(events, origin, forward, laneWidth, floors, placements, layout);
		}
		return placements.finish(mode, origin);
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
			int laneWidth, int floors, PlacementPlan placements, Layout layout) {
		BlockPos cursor = origin;
		Direction travel = forward;
		// Where chords grow, and the direction the whole slab creeps once a sweep is done.
		Direction depth = forward.getClockWise();
		// A descent is the one thing in a build that steps a column off its own centre line, and
		// it steps back the way the slabs came. The slab behind this one is climbing where this one
		// descends -- they alternate -- and a climb keeps to the centre line, so that column is the
		// one with nothing in it. Stepping the other way would put live stone against the notes of
		// the slab not yet built.
		Direction descentSide = layout.ultra() ? depth.getOpposite() : depth;
		int nearWall = origin.getX();
		int farWall = origin.getX() + laneWidth;
		int currentTime = 0;
		int floor = 0;
		int climb = 1;
		boolean laneStarted = false;
		// Whether the column a stacked module would want behind it is already spoken for -- either
		// by the module before it, whose relays reach into it, or by a turn, whose run of powered
		// stone lies right alongside it at the same level.
		boolean columnBehindBusy = false;
		ChordStyle lastStyle = ChordStyle.SMALL;
		// What the wire at the end of the lane is still worth. Every module opens with a repeater, so
		// this only ever counts what the module just built spent: nothing, unless it was a bus.
		int tipSignal = DUST_RANGE;
		LaneReach reach = laneReach(events, 0, events.size());
		int slabStep = laneSpacing(reach, reach);
		// Pad this lane has to lay before it reaches its last chord, settled when the lane starts.
		// See planLane: by the time a lane finds out it cannot fill the gap in front of it, the
		// chords that could have filled it are built.
		Map<Integer, Integer> booked = Map.of();
		boolean replan = layout.ultra();
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			// Settled before the event is placed rather than after it. A turn hands back a cursor at
			// the same point along the wall the last event reached, so an event that overshoots
			// leaves the next lane starting outside the wall -- and nothing measured afterwards can
			// help, because by then the overshoot is built. Asking first costs a lane its last event
			// and keeps the wall a wall.
			TurnCost turn = turnCost(floor, climb, floors, slabStep);
			int above = turn.above();
			int turnCells = turn.cells();
			int offBus = turn.offBus();
			int wall = travel == forward ? farWall : nearWall;
			int stepOffAhead = turn.stepOff();
			if (replan) {
				booked = planLane(events, index, cursor.getX(), travel.getStepX(), wall, currentTime,
					tipSignal, columnBehindBusy, turnCells, offBus, stepOffAhead, layout);
				replan = false;
			}
			// A lane that ends flush with the wall on a wire too weak to reach the top of a staircase
			// has nowhere left to stand the repeater that would revive it, and a lane that cannot turn
			// runs on past the wall instead. So an event that would leave the wire that weak is asked
			// to fit a column short, and the pad puts a repeater in the column that buys.
			int reserve = turnReserve(event, offBus, layout);
			int wait = event.time() - currentTime;
			// Measured with the same arithmetic the planner uses, and not with a length taken from
			// the shape the chord was measured in. A stacked chord that finds the pair of slots
			// behind it spoken for is built as a bus instead, and a bus is longer -- so a lane could
			// be told a chord fitted, build it, and land a column past its own wall.
			Landing here = landingOf(cursor.getX(), travel.getStepX(), event, wait, columnBehindBusy,
				layout);
			int landing = here.end() + travel.getStepX() * reserve;
			boolean wantsTurn = laneStarted && (landing > farWall || landing < nearWall);
			// One tick has to be left for the next event's own repeater, which is the only thing that
			// can drive the module it stands in front of.
			int columns = (wall - cursor.getX()) * travel.getStepX();
			Pad pad = layout.ultra() && wantsTurn
				? planPad(columns, tipSignal, turnCells, Math.max(0, wait - 1))
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
			boolean split = layout.ultra() && wantsTurn && index > 0
				&& room >= 2 && room - 1 < cells && cells + offBus + stepOff <= DUST_RANGE;
			// Unless leaving that tick is what stops the pad reaching the wall. Then spend the whole
			// wait on the pad and carry the event over the turn on the wire instead, which is the one
			// way a lane whose next event is a single tick away can still end where it is meant to.
			boolean carried = false;
			if (layout.ultra() && wantsTurn && pad.cells().size() < columns) {
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
			boolean canTurn = layout.ultra()
				? index > 0 && pad.signal() >= (pad.cells().isEmpty() && lastStyle == ChordStyle.BUS
					? offBus : turnCells)
				: index > 0 && events.get(index - 1).maxSafeTurnDistance() >= MAX_LANE_SPACING;
			int spentPadding = 0;
			carried &= canTurn && !split;
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
			if (layout.ultra() && wantsTurn && canTurn && !onWall && !split && !carried) {
				placements.trouble("a lane turned " + columns + " columns short of its wall at tick "
					+ event.time() + ", where a chord of " + event.notes().size()
					+ " would not fit: " + tipSignal + " blocks of wire and " + (wait - 1)
					+ " spare ticks to fill them with, and " + cells + " blocks of bus plus "
					+ offBus + " for the turn is too much to cut across it");
			}
			if (split) {
				SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel,
					event.time() - currentTime);
				currentTime = event.time();
				List<EventNote> chord = busOrder(event.notes());
				int near = 2 * (room - 1);
				cursor = addSplitEventModule(placements, trigger.cursor(), travel, depth,
					trigger.triggerDelay(), chord.subList(0, near));
				if (above >= 0 && above < floors) {
					cursor = climb > 0
						? addGlassClimb(placements, cursor, travel, true, currentTime)
						: addSpiralDescent(placements, cursor, travel, descentSide, currentTime);
					floor = above;
				} else {
					cursor = addCompactTurn(placements, cursor, travel, depth, slabStep, currentTime);
					climb = -climb;
				}
				travel = travel.getOpposite();
				cursor = addCarriedEventModule(placements, cursor, travel, depth,
					chord.subList(near, chord.size()), stepOff);
				lastStyle = ChordStyle.BUS;
				tipSignal = DUST_RANGE - cells - offBus - stepOff;
				// The far half starts where the turn left off, so its first pair of notes stands
				// alongside the run of powered stone the turn is made of.
				columnBehindBusy = true;
				laneStarted = true;
				replan = layout.ultra();
				continue;
			}
			if (canTurn && wantsTurn) {
				// A chord carried whole to the next lane, where the lane it left had to be filled with
				// wire instead. Reported because it is the thing worth being annoyed about: every one of
				// these is a chord that could have filled those columns itself.
				if (!pad.cells().isEmpty()) {
					placements.moved(event.notes().size());
				}
				cursor = emitPad(placements, cursor, travel, pad);
				spentPadding = pad.delaySpent();
				if (above >= 0 && above < floors) {
					// Asked of the shape the lane actually ended on, not of how many notes it held.
					// A big chord used to mean a bus and now may mean a stacked module, which ends
					// on its centre block a level lower -- and a climb that skips the two rungs it
					// needs starts a floor above the signal and never gets it. A pad puts the wire
					// back down on the path either way, so a padded lane never skips them.
					cursor = climb > 0
						? addGlassClimb(placements, cursor, travel,
							lastStyle == ChordStyle.BUS && pad.cells().isEmpty(), currentTime)
						: addSpiralDescent(placements, cursor, travel, descentSide, currentTime);
					floor = above;
				} else {
					// Out of floors: step the slab sideways once, and come back the way we climbed.
					// Sized from the widest chord in the whole song rather than from the lane we
					// happen to be leaving. A sideways step separates two slabs, and every floor of
					// one sits beside the matching floor of the other -- so a quiet lane at the top
					// is no promise about the chord four floors down that it would be answering for.
					cursor = addCompactTurn(placements, cursor, travel, depth, slabStep, currentTime);
					climb = -climb;
				}
				// What the staircase leaves the next lane. It matters because the next lane may want
				// to lay dust of its own before its first repeater, and a turn is the one handover in
				// a build that spends wire without a repeater at either end of it. Charged at what this
				// turn actually spends: a climb taken straight off a bus skips two rungs, and counting
				// them anyway left every lane after one two blocks poorer than it was.
				tipSignal = pad.signal() - (above >= 0 && above < floors && climb > 0
					&& lastStyle == ChordStyle.BUS && pad.cells().isEmpty() ? offBus : turnCells);
				travel = travel.getOpposite();
				laneStarted = false;
				columnBehindBusy = true;
				// Planned here and not at the top of the next event, because this event is about to be
				// built on the far side of the turn -- it is the new lane's first chord. Deferring the
				// plan by one left every lane's opening chord outside its own plan's reach: the search
				// could book a pad in front of any chord but that one, so a lane whose opening chord
				// was the thing that had to move came back with nothing and turned wherever it stood.
				// The turn ahead of the new lane is a different turn from the one just built -- the
				// floor and the direction of climb have both moved on -- so it is asked again.
				if (layout.ultra()) {
					TurnCost next = turnCost(floor, climb, floors, slabStep);
					// The pad before the turn has already held some of the wait this event was going to
					// spend on its own repeater, so the plan is told the clock has moved on by that
					// much. Otherwise it counts columns of delay the walk is not going to place.
					booked = planLane(events, index, cursor.getX(), travel.getStepX(),
						travel == forward ? farWall : nearWall, currentTime + spentPadding, tipSignal,
						columnBehindBusy, next.cells(), next.offBus(), next.stepOff(), layout);
					replan = false;
				}
			}
			if (carried) {
				// The pad's repeater has already held this event's whole wait, and everything between
				// it and here is dust. Nothing left to time it with, and nothing needed.
				currentTime = event.time();
				cursor = addCarriedEventModule(placements, cursor, travel, depth, event.notes(),
					stepOff);
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
			int owing = index > 0 && booked != null ? booked.getOrDefault(index, 0) : 0;
			if (owing > 0) {
				Pad early = planPad(owing, tipSignal, 1, Math.max(0, wait - 1 - spentPadding));
				cursor = emitPad(placements, cursor, travel, early);
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
			if (layout.ultra() && index > 0 && index + 1 < events.size()
				&& (event.notes().size() + 1) / 2 + offBus <= DUST_RANGE) {
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
					wait - spentPadding, columnBehindBusy, layout);
				int end = reached.end();
				EventGroup next = events.get(index + 1);
				int beyond = landingOf(end, travel.getStepX(), next, next.time() - event.time(),
					reached.busy(), layout).end()
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
				if (!cuttable && (beyond > farWall || beyond < nearWall)) {
					int ahead = prePad(cursor.getX(), travel.getStepX(), event, wait - spentPadding,
						columnBehindBusy, layout, laneWall,
						(laneWall - cursor.getX()) * travel.getStepX());
					// Planned like the pad behind, and for the same reason: dust in front of an event
					// spends the same wire dust behind it does, so a lane wanting a dozen columns off a
					// wire worth eight laid what it could and stopped short of the wall anyway. What is
					// kept back here is one cell rather than a staircase, for the column a stacked
					// module may take between the pad and its own repeater to land on its beat.
					Pad front = planPad(ahead, tipSignal, 1,
						Math.max(0, wait - 1 - spentPadding));
					if (ahead > 0 && front.cells().size() == ahead) {
						cursor = emitPad(placements, cursor, travel, front);
						spentPadding += front.delaySpent();
					}
				}
			}
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel,
				event.time() - currentTime - spentPadding);
			currentTime = event.time();
			Placed placed = addChordModule(placements, trigger.cursor(), travel, depth,
				trigger.triggerDelay(), event, !columnBehindBusy || !trigger.cursor().equals(cursor),
				layout);
			cursor = placed.cursor();
			columnBehindBusy = placed.stacked();
			lastStyle = placed.style();
			// A bus is the one module that hands the next thing along a wire rather than a block: its
			// stones are lit by the dust running over them, and that dust has been counting down since
			// the repeater at the head of it. Everything else ends on a block a repeater drives
			// directly, which is worth the full fifteen to whatever touches it.
			tipSignal = placed.style() == ChordStyle.BUS
				? DUST_RANGE - (event.notes().size() + 1) / 2 : DUST_RANGE;
			laneStarted = true;
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
		return layout.ultra() && event.style() == ChordStyle.BUS
			&& DUST_RANGE - (event.notes().size() + 1) / 2 < turnCells ? 1 : 0;
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
	private record TurnCost(int above, int cells, int offBus, int stepOff) {
	}

	private static TurnCost turnCost(int floor, int climb, int floors, int slabStep) {
		int above = floor + climb;
		boolean staircase = above >= 0 && above < floors;
		int cells = staircase ? TURN_DUST_CELLS : slabStep + 2;
		return new TurnCost(above, cells, staircase && climb > 0 ? cells - 2 : cells,
			staircase && climb < 0 ? 1 : 0);
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

	private static Landing landingOf(int startX, int stepX, EventGroup event, int wait, boolean busy,
			Layout layout) {
		int delayColumns = Math.max(0, (wait - 1) / 4);
		ChordStyle style = event.style() == ChordStyle.STACKED_FULL && busy && delayColumns == 0
			? ChordStyle.BUS : event.style();
		int cells = (event.notes().size() + 1) / 2;
		int length;
		if (style.stacked()) {
			int centre = startX + stepX * (delayColumns + 1);
			length = delayColumns + 2 + (Math.floorMod(centre, 2) == layout.centreParity() ? 0 : 1);
		} else {
			length = delayColumns + (style == ChordStyle.BUS ? 1 + cells : 2);
		}
		return new Landing(startX + stepX * length,
			style == ChordStyle.BUS ? DUST_RANGE - cells : DUST_RANGE, style.stacked(), style);
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
			int stepX, int wall, int startTime, int tip, boolean busy, int turnCells, int offBus,
			int stepOff, Layout layout) {
		Sweep bare = sweep(events, from, startX, stepX, wall, startTime, tip, busy, offBus, layout,
			Map.of());
		if (bare.last() < from
			|| closes(events, bare, from, bare.last(), wall, stepX, turnCells, offBus, stepOff)) {
			return Map.of();
		}
		// The natural end cannot close. Try landing on the wall a chord at a time further back, since
		// every chord given up is a chord the next lane has to carry instead.
		for (int last = bare.last(); last >= from; last--) {
			Map<Integer, Integer> pads = new LinkedHashMap<>();
			for (int attempt = 0; attempt < 8; attempt++) {
				Sweep tried = sweep(events, from, startX, stepX, wall, startTime, tip, busy, offBus, layout,
					pads);
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
				Pad end = closingPad(events, tried, from, last, owing, turnCells);
				int need = end.cells().isEmpty()
					&& tried.styles().get(last - from) == ChordStyle.BUS ? offBus : turnCells;
				if (end.cells().size() == owing && end.signal() >= need) {
					return Map.copyOf(pads);
				}
				if (owing - end.cells().size() <= 0
					|| !book(pads, tried, from, last, owing - end.cells().size())) {
					break;
				}
			}
		}
		// Nothing lands this lane on its wall. Said so rather than pretending, because a lane that
		// cannot be pinned is better turned where it stands than run on past its wall and turned out
		// in the open, where the staircase has a whole corridor of somebody else's notes to land in.
		return null;
	}

	/** A lane walked on paper: where each event ends, what it leaves, and what its gap could hold. */
	private record Sweep(List<Integer> ends, List<Integer> tips, List<ChordStyle> styles,
			List<Integer> room, int last) {
	}

	private static Sweep sweep(List<EventGroup> events, int from, int startX, int stepX, int wall,
			int startTime, int tip, boolean busy, int offBus, Layout layout,
			Map<Integer, Integer> pads) {
		List<Integer> ends = new ArrayList<>();
		List<Integer> tips = new ArrayList<>();
		List<ChordStyle> styles = new ArrayList<>();
		List<Integer> room = new ArrayList<>();
		int cursor = startX;
		int time = startTime;
		int last = from - 1;
		for (int index = from; index < events.size(); index++) {
			EventGroup event = events.get(index);
			int wait = event.time() - time;
			int pad = pads.getOrDefault(index, 0);
			room.add(planPad(DUST_RANGE * 2, tip, 1, Math.max(0, wait - 1)).cells().size() - pad);
			Landing landed = landingOf(cursor + stepX * pad, stepX, event, wait, busy, layout);
			// Kept back the same column the walk keeps back. A bus that would leave the wire too weak
			// to reach the top of the staircase is asked to stop one column short, so the pad has
			// somewhere to stand the repeater that revives it. The walk has always done that and the
			// sweep did not, so the planner counted chords into a lane the walk then refused to put
			// there, and the two disagreed about which chords the lane even held.
			if ((landed.end() + stepX * turnReserve(event, offBus, layout) - wall) * stepX > 0) {
				break;
			}
			ends.add(landed.end());
			tips.add(landed.tip());
			styles.add(landed.style());
			cursor = landed.end();
			tip = landed.tip();
			busy = landed.busy();
			time = event.time();
			last = index;
		}
		return new Sweep(ends, tips, styles, room, last);
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
		return planPad(owing, sweep.tips().get(last - from), turnCells,
			last + 1 < events.size()
				? Math.max(0, events.get(last + 1).time() - events.get(last).time() - 1) : 0);
	}

	/** Whether the lane can hand over after this event, either by filling it out or by a cut. */
	private static boolean closes(List<EventGroup> events, Sweep sweep, int from, int last, int wall,
			int stepX, int turnCells, int offBus, int stepOff) {
		if (last + 1 >= events.size()) {
			return true;
		}
		int room = (wall - sweep.ends().get(last - from)) * stepX;
		if (room == 0) {
			return sweep.tips().get(last - from)
				>= (sweep.styles().get(last - from) == ChordStyle.BUS ? offBus : turnCells);
		}
		// Cut across the turn: as much of the next chord as reaches the wall, then the staircase, then
		// the rest of it, all off the one repeater. It closes a lane wherever the lane has got to, and
		// costs nothing, because the columns it fills are filled with music.
		int cells = (events.get(last + 1).notes().size() + 1) / 2;
		return room >= 2 && room - 1 < cells && cells + offBus + stepOff <= DUST_RANGE;
	}

	/** Books what the end of a lane cannot pay for into the latest gaps that can. */
	private static boolean book(Map<Integer, Integer> pads, Sweep sweep, int from, int last,
			int owing) {
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
			Layout layout, int wall, int limit) {
		for (int pad = 0; pad <= limit; pad++) {
			if (landingOf(startX + stepX * pad, stepX, event, wait, busy, layout).end() == wall) {
				return pad;
			}
		}
		return -1;
	}

	/** Lays a run of dust, on glass so that nothing under it comes alive. */
	private static BlockPos emitDust(PlacementPlan placements, BlockPos cursor, Direction travel,
			int columns) {
		for (int cell = 0; cell < columns; cell++) {
			addParityPad(placements, cursor);
			cursor = cursor.relative(travel);
		}
		return cursor;
	}

	/** Lays a planned pad down, and hands back the block the turn now starts on. */
	private static BlockPos emitPad(PlacementPlan placements, BlockPos cursor, Direction travel,
			Pad pad) {
		for (int delay : pad.cells()) {
			if (delay == 0) {
				addParityPad(placements, cursor);
			} else {
				// Stone rather than glass, because a repeater needs something to stand on -- and it is
				// safe here where dust is not, since a repeater leaves the block under it alone.
				set(placements, cursor, "minecraft:stone");
				set(placements, cursor.above(),
					"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + delay + "]");
			}
			cursor = cursor.relative(travel);
		}
		return cursor;
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
	private static BlockPos addSpiralDescent(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction depth, int time) {
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
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel, delay);
			currentTime = event.time();
			Integer spacing = layout.spacingAt().get(index + 1);
			if (spacing != null && fitsInCorner(event)) {
				cursor = addTurnEventModule(placements, trigger.cursor(), travel, laneStep, spacing,
					trigger.triggerDelay(), event.notes());
				travel = travel.getOpposite();
				continue;
			}
			cursor = addSpatialEventModule(placements, trigger.cursor(), travel, laneStep,
				trigger.triggerDelay(), event.notes());
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
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel, delay);
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
				Placed placed = addChordModule(placements, trigger.cursor(), travel, laneStep,
					trigger.triggerDelay(), event, roomBehind, layout);
				cursor = placed.cursor();
				columnBehindBusy = placed.stacked();
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
			cursor = addChordModule(placements, trigger.cursor(), travel, laneStep,
				trigger.triggerDelay(), event, roomBehind, layout).cursor();
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
			int eventLength = style == ChordStyle.BUS ? 1 + busLength : 2;
			int maxSafeTurnDistance = style == ChordStyle.BUS ? Math.max(0, 13 - busLength) : 13;
			result.add(new EventGroup(time, List.copyOf(chord), delayRepeaters + eventLength,
				maxSafeTurnDistance, style, laneReachOf(layout, style, chord.size())));
			currentTime = time;
			previousTookTheGap = style.stacked();
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
	private static ChordStyle chooseStyle(Layout layout, List<EventNote> chord, boolean roomBehind) {
		if (chord.size() <= 3) {
			return ChordStyle.SMALL;
		}
		if (!layout.ultra()) {
			return ChordStyle.BUS;
		}
		if (ultraSlots(chord, false) != null) {
			return ChordStyle.STACKED_FRONT;
		}
		if (roomBehind && ultraSlots(chord, true) != null) {
			return ChordStyle.STACKED_FULL;
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
	private static LaneReach laneReachOf(Layout layout, ChordStyle style, int chordSize) {
		int margin = layout.ultra() ? 0 : 1;
		if (style.stacked()) {
			// The two blocks a cross hands its signal to sit one either side of the centre line,
			// at the same level as the four low notes. A chord measured as this and then dropped
			// to a bus keeps the wider claim, which is the harmless direction to be wrong in.
			return new LaneReach(1, 1, margin, false);
		}
		return style == ChordStyle.BUS ? new LaneReach(1, 1, margin, false)
			: smallChordReach(chordSize, margin);
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
		BlockPos outer = cursor.relative(travel);
		layTurnFloor(placements, cursor, time);
		layTurnFloor(placements, outer, time);
		for (int offset = 1; offset <= laneDistance; offset++) {
			layTurnFloor(placements, outer.relative(laneStep, offset), time);
		}
		return outer.relative(laneStep, laneDistance).relative(travel.getOpposite());
	}

	private static void layTurnFloor(PlacementPlan placements, BlockPos position, int time) {
		placements.powered(position, "minecraft:stone", time);
		set(placements, position.above(), "minecraft:redstone_wire");
	}

	private static SpatialDelayTrigger addSpatialDelayBeforeEvent(PlacementPlan placements, BlockPos cursor,
			Direction travel, int delay) {
		int remaining = delay;
		while (remaining > 4) {
			set(placements, cursor, "minecraft:stone");
			set(placements, cursor.above(),
				"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=4]");
			cursor = cursor.relative(travel);
			remaining -= 4;
		}
		return new SpatialDelayTrigger(cursor, Math.max(1, remaining));
	}

	/**
	 * @param laneStep the direction the serpentine walks, which is the side a chord grows into
	 *     first. Pinning it to the lane step rather than to travel -- which reverses every lane --
	 *     is what makes {@link LaneReach} predictable enough to pack lanes closer than four apart.
	 */
	private static BlockPos addSpatialEventModule(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, int triggerDelay, List<EventNote> chord) {
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		return layEventBody(placements, cursor, travel, laneStep, chord);
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
		cursor = emitDust(placements, cursor, travel, stepOff);
		// The wire arrives level with the repeater that is not there, and climbs the side of the
		// first stone onto the dust running over it.
		return cursor.relative(travel,
			layBus(placements, cursor.above(), travel, laneStep, chord, chord.get(0).time()));
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
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		return cursor.relative(travel, 1 + layBus(placements, cursor.relative(travel).above(),
			travel, laneStep, chord, chord.get(0).time()));
	}

	/**
	 * A run of powered stone with a note down each side of it, and the dust that lights the run.
	 *
	 * @return how many blocks of it there are, which is also how much wire it spends
	 */
	private static int layBus(PlacementPlan placements, BlockPos anchor, Direction travel,
			Direction laneStep, List<EventNote> chord, int time) {
		int busLength = (chord.size() + 1) / 2;
		for (int bus = 0; bus < busLength; bus++) {
			BlockPos busPos = anchor.relative(travel, bus);
			placements.powered(busPos, "minecraft:stone", time);
			set(placements, busPos.above(), "minecraft:redstone_wire");
		}
		List<EventNote> ordered = busOrder(chord);
		for (int noteIndex = 0; noteIndex < ordered.size(); noteIndex++) {
			placeNote(placements, anchor.relative(travel, noteIndex / 2)
				.relative(noteIndex % 2 == 0 ? laneStep : laneStep.getOpposite()),
				ordered.get(noteIndex));
		}
		return busLength;
	}

	private static BlockPos layEventBody(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, List<EventNote> chord) {
		int time = chord.get(0).time();
		BlockPos anchor = cursor.relative(travel).above();
		if (chord.size() <= 3) {
			placeNote(placements, anchor, chord.get(0));
			// The repeater drives the anchor directly, and a note block is a full block, so the
			// anchor passes that power on to whatever is beside it -- including the next repeater.
			placements.powered(anchor, time);
			if (chord.size() >= 2) {
				placeNote(placements, anchor.relative(laneStep), chord.get(1));
			}
			if (chord.size() >= 3) {
				placeNote(placements, anchor.relative(laneStep.getOpposite()), chord.get(2));
			}
			return cursor.relative(travel, 2);
		}
		return cursor.relative(travel,
			1 + layBus(placements, anchor, travel, laneStep, chord, time));
	}

	/**
	 * Builds a chord in the shape chosen for it, settling the one thing the choice could not know.
	 *
	 * <p>Whether the pair of slots behind this module is free depends on where the lanes turned,
	 * and lanes turn according to lengths that were measured from these very choices. The knot is
	 * cut by only ever moving in the direction that shortens: a full stacked module that finds its
	 * pair taken drops to a bus, and a bus that finds a turn has freed the pair takes it.</p>
	 */
	private static Placed addChordModule(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction across, int triggerDelay, EventGroup event, boolean roomBehind, Layout layout) {
		ChordStyle style = event.style();
		if (style == ChordStyle.STACKED_FULL && !roomBehind) {
			style = ChordStyle.BUS;
		}
		if (!style.stacked()) {
			return new Placed(addSpatialEventModule(placements, cursor, travel, across, triggerDelay,
				event.notes()), style);
		}
		BlockPos start = cursor;
		if (Math.floorMod(start.relative(travel).getX(), 2) != layout.centreParity()) {
			addParityPad(placements, start);
			start = start.relative(travel);
		}
		return new Placed(addStackedEventModule(placements, start, travel, across, triggerDelay,
			event.time(), ultraSlots(event.notes(), style == ChordStyle.STACKED_FULL)), style);
	}

	/**
	 * A column of path that costs a block and no time, to put a module on the right footing.
	 *
	 * <p>A stacked module's outer column alternates strictly: the block a cross relays through sits
	 * at the module's centre, and the two low notes sit either side of it. So along the lane it runs
	 * live, note, live, note. Two lanes whose modules agree on which of those falls on an even
	 * coordinate therefore meet live against live and note against note -- and a note block beside
	 * a note block cannot be set off, because block power never crosses. That agreement is the whole
	 * reason two lanes of stacked modules can sit three apart instead of four, and this is what buys
	 * it: every module centre is put on an even coordinate, and a module that would have landed odd
	 * is nudged one along first.</p>
	 *
	 * <p>Glass and not stone. Dust makes the block beneath it live, and the blocks either side of
	 * that one are exactly where low notes hang -- notes belonging to a later chord, which an
	 * earlier live block would sound before its time. Glass cannot be powered at all. The dust on
	 * top has a solid block behind it and a repeater in front, so it takes the straight shape and
	 * points along the lane only, never sideways into a note.</p>
	 */
	private static void addParityPad(PlacementPlan placements, BlockPos cursor) {
		set(placements, cursor, "minecraft:glass");
		set(placements, cursor.above(), "minecraft:redstone_wire");
	}

	/**
	 * Where a module left the path, and the shape it was actually built in.
	 *
	 * <p>The shape and not the one the chord was measured with: a turn can hand a chord the stacked
	 * module its measurement gave up on, and what follows -- the pair of slots left free, the level
	 * the signal ends on -- turns on what went down, not on what was planned.</p>
	 */
	private record Placed(BlockPos cursor, ChordStyle style) {
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
	private static BlockPos addStackedEventModule(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction across, int triggerDelay, int time, UltraSlots slots) {
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		BlockPos centre = cursor.relative(travel).above();
		BlockPos cross = centre.below();
		set(placements, cross.below(), "minecraft:stone");
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
				placements.support(instrument.below(), "minecraft:stone");
			}
			placeNoteBlock(placements, centre.relative(out), relay);
			if (side < slots.front().size()) {
				placeNote(placements, instrument.relative(travel), slots.front().get(side));
			}
			if (side < slots.back().size()) {
				placeNote(placements, instrument.relative(travel.getOpposite()), slots.back().get(side));
			}
		}
		return cursor.relative(travel, 2);
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
	}

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
		int hangers = reachingBack ? 6 : 4;
		if (chord.size() < 4 || chord.size() > hangers + 1) {
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
			List.copyOf(hanging.subList(0, Math.min(2, hanging.size()))),
			List.copyOf(hanging.subList(Math.min(2, hanging.size()), hanging.size())));
	}

	private static boolean isHarpNote(EventNote note) {
		return "minecraft:air".equals(note.instrumentBlock());
	}

	/** Whether this note's instrument block would pass power on to a note block beside it. */
	private static boolean conductsSideways(EventNote note) {
		return isHarpNote(note) || CONDUCTING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock());
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
		if (chord.size() <= 3) {
			placeNote(placements, anchor, chord.get(0));
			placements.powered(anchor, time);
			if (chord.size() >= 2) {
				placeNote(placements, anchor.relative(right), chord.get(1));
			}
			if (chord.size() >= 3) {
				placeNote(placements, anchor.relative(right.getOpposite()), chord.get(2));
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
		set(placements, notePos.below(), note.instrumentBlock());
		if (FALLING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock())) {
			// Sand and friends drop the moment /setblock places them over air, taking the note
			// block's instrument with them. Fill the space beneath, but never overwrite: anything
			// already planned there is load-bearing and already does the supporting.
			placements.support(notePos.below().below(), "minecraft:stone");
		}
		placeNoteBlock(placements, notePos, note);
	}

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
		if ("HARP".equals(preview.id())) {
			// A note block over anything unrecognised already plays harp, so air is identical in
			// sound and costs nothing. Placing grass would waste a block per piano note.
			return "minecraft:air";
		}
		return BuiltInRegistries.ITEM.getKey(preview.icon()).toString();
	}

	record EventNote(int time, int trackNumber, int order, int pitch, String instrumentBlock) {
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
	private record Layout(boolean ultra, boolean risers, int centreParity) {
		static final Layout STANDARD = new Layout(false, true, 0);

		static Layout ultra(int floors, BlockPos origin) {
			// Counted from the origin and not from the world, so the same song pasted a block over
			// is the same build. Anchored where the first module would land anyway, which is one
			// past the origin, so a song that never drifts off the beat never pays for a pad.
			return new Layout(true, floors > 1, Math.floorMod(origin.getX() + 1, 2));
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
		BUS;

		boolean stacked() {
			return this == STACKED_FRONT || this == STACKED_FULL;
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

	private record SpatialDelayTrigger(BlockPos cursor, int triggerDelay) {
	}

	enum PasteMode {
		COMPACT_CUBE("Compact cube"),
		COMPACT("Compact square"),
		COMPACT_LANE("Compact lane"),
		ULTRA_COMPACT_LANE("Ultra compact lane"),
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
	record PastePlan(List<String> commands, int width, int depth, int height, PasteMode mode,
			List<String> faults, List<BlockPos> turns, List<Integer> moved, List<Integer> breaches) {

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

		/** How far past the promised width the worst-behaved lane went, in blocks. */
		int worstBreach() {
			return breaches.stream().mapToInt(Integer::intValue).max().orElse(0);
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
				powered.put(position.immutable(), time);
			}
		}

		/**
		 * Whether a note block could still go here, with its instrument under it and air over it.
		 *
		 * <p>A plan that records nothing answers yes to everything, which is right: a dry walk is
		 * measuring a shape, and the shape is the one the real walk would get if nothing were in
		 * the way.</p>
		 */
		boolean freeForNote(BlockPos position) {
			return !recording
				|| !blocks.containsKey(position.immutable())
					&& !blocks.containsKey(position.below().immutable())
					&& !blocks.containsKey(position.above().immutable());
		}

		void note(BlockPos position, int time) {
			if (recording) {
				notes.put(position.immutable(), time);
			}
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
						faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
							+ " belongs to tick " + time + " but would sound early, at tick "
							+ neighbour);
					} else if (neighbour > time + SHARED_PULSE_TICKS) {
						faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
							+ " belongs to tick " + time + " but would sound again at tick "
							+ neighbour + ", too late for the first pulse to still be covering it");
					}
				}
				if (!triggered) {
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
		 * <p>One repeater at its longest. That is not a margin picked for comfort: it is exactly as
		 * far apart as two modules can be and still touch at all, since anything longer puts a
		 * repeater of its own between them and there is nothing left to share.</p>
		 */
		private static final int SHARED_PULSE_TICKS = 4;

		private static String describe(BlockPos position) {
			return describe(position, 0, 0);
		}

		private static String describe(BlockPos position, int shiftX, int shiftZ) {
			return (position.getX() + shiftX) + " " + position.getY() + " "
				+ (position.getZ() + shiftZ);
		}

		void set(BlockPos position, String block) {
			if (!recording) {
				return;
			}
			BlockPos key = position.immutable();
			String existing = blocks.putIfAbsent(key, block);
			if (existing != null && !existing.equals(block)) {
				throw new IllegalArgumentException("Placement layout collision at "
					+ describe(key) + ": " + existing + " is already there and " + block
					+ " wants the same block");
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

		PastePlan finish(PasteMode mode, BlockPos origin) {
			// Where the plan lands, worked out before anything is checked so that a fault can name a
			// block you are able to go and stand in front of. Nothing lands behind you: the walk
			// reaches outside its own walls here and there -- a chord hanging off the far side of the
			// first lane, a corner overshooting the end of one, a floor not quite the width of the
			// one below it -- and a build that starts a block behind where you were standing is a
			// build you cannot line up from a corner. Sliding it is exact and costs nothing; the
			// height is left alone, because that is measured from your feet and not from a wall.
			int shiftX = minimumX == Integer.MAX_VALUE ? 0 : origin.getX() - minimumX;
			int shiftZ = minimumZ == Integer.MAX_VALUE ? 0 : origin.getZ() - minimumZ;
			List<String> faults = new ArrayList<>(trouble);
			faults.addAll(verify(shiftX, shiftZ));
			// Every other layout is finished, so a fault in one is a bug and the build is refused.
			// The ultra lane is still being worked out on multiple floors, where the run that carries
			// the signal sideways passes under the notes of the corridors either side of it, and a
			// machine you cannot stand in front of is a machine you cannot work out. So it goes up,
			// and says what is wrong with it.
			if (!faults.isEmpty() && mode != PasteMode.ULTRA_COMPACT_LANE) {
				throw new IllegalArgumentException("Refusing to build a broken machine: "
					+ faults.get(0) + ". This is a bug in the layout, not in the song.");
			}
			List<String> commands = blocks.entrySet().stream()
				.map(entry -> "setblock " + (entry.getKey().getX() + shiftX) + " "
					+ entry.getKey().getY() + " " + (entry.getKey().getZ() + shiftZ) + " "
					+ entry.getValue() + " replace")
				.toList();
			int widthX = maximumX < minimumX ? 0 : maximumX - minimumX + 1;
			int widthZ = maximumZ < minimumZ ? 0 : maximumZ - minimumZ + 1;
			int height = maximumY < minimumY ? 0 : maximumY - minimumY + 1;
			return new PastePlan(commands, Math.max(widthX, widthZ), Math.min(widthX, widthZ), height,
				mode, List.copyOf(faults),
				turns.stream().map(turn -> turn.offset(shiftX, 0, shiftZ)).toList(),
				List.copyOf(moved), List.copyOf(breaches));
		}
	}
}
