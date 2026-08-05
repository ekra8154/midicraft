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
				limits.laneFloors(), Layout.STANDARD, PasteMode.COMPACT_LANE, start);
			case ULTRA_COMPACT_LANE -> bestUltraPlan(origin, forward, notes, limits, start);
			case LANE -> createStraightPastePlan(origin, forward, notes);
		};
	}

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
	private static PastePlan bestUltraPlan(BlockPos origin, Direction forward,
			List<EventNote> notes, BuildLimits limits, WalkStart start) {
		Layout plain = Layout.ultra(limits.laneFloors(), origin);
		// Where the snake is put down. Only when nobody has asked for somewhere in particular: a
		// seeded walk is a debug build reproducing a fault at a stated floor, and it means it.
		// The lookahead pair is built inside this rather than beside it, because plain against
		// lookahead is the same snake either way -- the start decides which snake they both are.
		WalkStart head = start == WalkStart.HEAD && limits.startTop()
			? new WalkStart(0, limits.laneFloors() - 1, -1)
			: start;
		PastePlan without = createLanePastePlan(origin, forward, notes, limits.laneWidth(),
			limits.laneFloors(), plain, PasteMode.ULTRA_COMPACT_LANE, head);
		PastePlan with = createLanePastePlan(origin, forward, notes, limits.laneWidth(),
			limits.laneFloors(), plain.withLookahead(), PasteMode.ULTRA_COMPACT_LANE, head);
		boolean won = beats(with, without);
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
				booked = above >= 0 && above < floors
					? planLane(events, index, lane.pos().getX(), lane.travel().getStepX(), wall,
						lane.travel() == forward ? nearWall : farWall,
						currentTime, tipSignal, columnBehindBusy, turnCells, offBus, stepOffAhead,
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
			int landing = here.end() + lane.travel().getStepX() * reserve;
			// Never while the route is still bending. Inside a turn the wire runs across the corridor
			// rather than along it, so every one of these measurements is taken down the wrong axis --
			// and there is nothing to decide anyway, because the walk has already committed to the
			// corner it is standing in. A turn ends when the route runs out of corners, not when some
			// arithmetic about walls says so.
			// Whether this event will not fit before the wall, which is a different question from
			// whether the lane may end here.
			boolean overshoots = !turning && (landing > farWall || landing < nearWall);
			// A lane has to hold something before it can end, or a turn that lands short would turn
			// again at once and the walk would climb the whole build without laying a note.
			boolean wantsTurn = laneStarted && overshoots;
			// One tick has to be left for the next event's own repeater, which is the only thing that
			// can drive the module it stands in front of.
			int columns = (wall - lane.pos().getX()) * lane.travel().getStepX();
			Pad pad = layout.ultra() && wantsTurn && !straddles
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
			StackedSplit headed = layout.ultra() && overshoots && index > 0 && above >= 0
				&& above < floors
				? stackedSplitOf(event.notes(), room, splitCells, climb > 0,
					!columnBehindBusy || delayColumns > 0)
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
						!columnBehindBusy || delayColumns + 1 > 0);
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
			boolean couldSplit = layout.ultra() && overshoots && index > 0 && above >= 0
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
				int turnCost = pad.cells().isEmpty() && unpaid == 0 && lastStyle.buses()
					? offBus : turnCells;
				boolean reachesWall = !PIN_DESCENTS || flatAhead
					|| pad.signal() - unpaid >= turnCost;
			boolean canTurn = layout.ultra()
				? index > 0 && reachesWall && (flatAhead ? straddles && pad.signal() >= 1
					: pad.signal()
						>= (pad.cells().isEmpty() && lastStyle.buses() ? offBus : turnCells))
				: index > 0 && events.get(index - 1).maxSafeTurnDistance() >= MAX_LANE_SPACING;
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
						// on the parity it wants.
						placements.padded("parity");
						addParityPad(placements, opening);
						opening = opening.relative(travel);
					}
					if (headed.head().size() < STACKED_HEAD_NOTES) {
						SHORT_HEAD_CUT_AT = opening;
					}
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
				// Measured from where the staircase actually lands, which for a split is past the near
				// half of the chord rather than where the lane stood when it decided to split.
				placements.recessed(((travel == forward ? farWall : nearWall) - cursor.getX())
					* travel.getStepX());
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
					? DUST_RANGE - STACKED_BUS_TRANSITION
						- (headed.nearTail().size() + headed.farTail().size() + 1) / 2 - splitCells
					: DUST_RANGE - cells - splitCells;
				gradeLaneTip(placements, turnCells, tipSignal,
					climb > 0 ? "SplitClimb" : "SplitDescent");
				// The far half starts where the staircase left off, so its first pair of notes stands
				// alongside the run of powered stone the turn is made of.
				columnBehindBusy = true;
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
				lane = emitPad(placements, lane, pad);
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
							placements.padded("padPinned");
						}
						lane = emitDust(placements, lane, pinned);
					}
					// Recorded after the pin and not before it, so this is where the staircase actually
					// stands rather than where the lane would have left it. Told apart by direction as
					// well: only descents are pinned, so a recessed climb is a real one and a recessed
					// descent, on this branch, should not exist at all.
					placements.recessed(shortBy - pinned);
					if (shortBy - pinned > 0) {
						placements.padded(climb > 0 ? "recessedClimb" : "recessedDescent");
					}
					BlockPos landed = climb > 0
						? addGlassClimb(placements, lane.pos(), travel,
							lastStyle.buses() && pad.cells().isEmpty(), currentTime)
						: descend(placements, lane.pos(), travel, descentSide, currentTime);
					floor = above;
					// What the staircase leaves the next lane. It matters because the next lane may
					// want to lay dust of its own before its first repeater, and a staircase is the one
					// handover in a build that spends wire without a repeater at either end of it.
					// Charged at what it actually spends: a climb taken straight off a bus skips two
					// rungs, and counting them anyway left every lane after one two blocks poorer.
					tipSignal = pad.signal() - pinned - (climb > 0 && lastStyle.buses()
						&& pad.cells().isEmpty() ? offBus : turnCells);
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
					parity).end() - wall)
					* lane.travel().getStepX() <= 0) {
				while (owing > 0 && (landingOf(lane.pos().getX() + lane.travel().getStepX() * owing,
						lane.travel().getStepX(), event, wait - spentPadding, columnBehindBusy, wall,
						layout, inTurn(turning, leavingTurn, lane.pos(), lastCorner), parity).end() - wall)
						* lane.travel().getStepX() > 0) {
					owing--;
				}
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
					if (ahead > 0 && front.cells().size() == ahead) {
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
			// What the planner would say this chord does, asked at the moment the walk is about to do
			// it. Kept as a counter rather than a fault because a gap here is not wrong in itself --
			// a nudge is decided against blocks on the ground and no arithmetic can foresee it -- but
			// every one of these is a column the plan spent somewhere the walk did not, and the two
			// disagreeing is the bug shape this file keeps producing. The corner-bus gap showed up
			// here as two columns on the opening chord of every lane leaving a flat turn.
			int foretold = layout.ultra() && !turning
				? landingOf(before.getX(), lane.travel().getStepX(), event,
					event.time() - currentTime - spentPadding, columnBehindBusy, wall, layout,
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
				!columnBehindBusy || !opening.pos().equals(before),
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
			style = FRONT_HEAD_WHEN_BEHIND_BUSY || (FRONT_ONLY_HEADS && style.busHeaded())
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
				&& (wall - (startX + stepX * (delayColumns + behindShift))) * stepX
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
				tailCells = (split.tail().size() + 1) / 2;
				// Head, transition and bus all have to be inside the wall, where a plain stacked
				// module only ever had to fit its two columns.
				//
				// Unless the bus it would fall to is longer than the head it is giving up, which for
				// any chord the head takes seven from it is: see {@link #addChordModule}, which has
				// to make this same substitution or the lane is measured for one shape and built as
				// another.
				if ((wall - (startX + stepX * (delayColumns + behindShift))) * stepX
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
			if (verdict < 0 || behindShift > 0 && verdict == 1) {
				style = ChordStyle.BUS;
				behindShift = 0;
			} else {
				// A relocation costs nothing in columns -- that is the condition it is offered under
				// -- so only a shift lengthens the chord.
				nudged = verdict == 1;
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
		boolean vetoCut = shuts && cut > 0;
		// The natural end cannot close. Try landing on the wall a chord at a time further back, since
		// every chord given up is a chord the next lane has to carry instead.
		for (int last = bare.last(); last >= from; last--) {
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
				Pad end = closingPad(events, tried, from, last, owing, turnCells);
				int need = end.cells().isEmpty()
					&& tried.styles().get(last - from).buses() ? offBus : turnCells;
				if (TRACE) {
					System.out.println("  TRY last=" + last + " attempt=" + attempt + " pads=" + pads
						+ " triedLast=" + tried.last() + " owing=" + owing + " padCells="
						+ end.cells().size() + " padSignal=" + end.signal() + " need=" + need);
				}
				if (end.cells().size() == owing && end.signal() >= need
					&& !strandsNext(events, from, last, wall, otherWall, stepX, turnCells, offBus,
						stepOff, climbing, layout, 0)) {
					if (vetoCut) {
						pads.put(NO_SPLIT - (last + 1), 1);
					}
					return Map.copyOf(pads);
				}
				if (owing - end.cells().size() <= 0
					|| !book(pads, tried, from, last, owing - end.cells().size())) {
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
		return planPad(owing, sweep.tips().get(last - from), turnCells,
			last + 1 < events.size()
				? Math.max(0, events.get(last + 1).time() - events.get(last).time() - 1) : 0);
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
			climbing, roomBehind);
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
	private static boolean strandsNext(List<EventGroup> events, int from, int last, int wall,
			int otherWall, int stepX, int turnCells, int offBus, int stepOff, boolean climbing,
			Layout layout, int carriedCells) {
		if (!LOOKAHEAD || !layout.lookahead()) {
			return false;
		}
		// The cut chord belongs to the turn, so the lane after a cut opens on the chord past it.
		int spent = carriedCells > 0 ? last + 1 : last;
		int first = spent + 1;
		if (first >= events.size()) {
			return false;
		}
		Sweep after = sweep(events, first,
			nextLaneStart(wall, stepX, carriedCells, climbing, stepOff), -stepX, otherWall,
			events.get(spent).time(), DUST_RANGE - turnCells, true, offBus, layout, Map.of(),
			false, null);
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
		if (stackedSplitOf(events.get(last + 1).notes(), room, splitCells, climbing, roomBehind)
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
		for (int pad = 0; pad <= limit; pad++) {
			if (landingOf(startX + stepX * pad, stepX, event, wait, busy, wall, layout, inTurn,
					parity).end() == wall) {
				return pad;
			}
		}
		return -1;
	}

	/** Lays a run of dust, on glass so that nothing under it comes alive. */
	private static Lane emitDust(PlacementPlan placements, Lane lane, int columns) {
		placements.placing("pad");
		for (int cell = 0; cell < columns; cell++) {
			addParityPad(placements, lane.pos());
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
		for (int delay : pad.cells()) {
			if (delay == 0) {
				placements.padded(why);
				addParityPad(placements, lane.pos());
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
				tailCells = split == null ? 0 : (split.tail().size() + 1) / 2;
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
				maxSafeTurnDistance, style, laneReachOf(layout, style, chord.size())));
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
		return style.busHeaded() ? stackedBusSplit(chord, style == ChordStyle.STACKED_BUS) : null;
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
		while (lane.cornerAt(0)) {
			placements.padded("corner");
			lane = emitDust(placements, lane, 1);
		}
		return lane;
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
			if (placements.describeBlock(behind).startsWith("minecraft:stone")
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
		cursor = emitDust(placements, Lane.straight(cursor, travel, laneStep), stepOff).pos();
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
	 * Scratch: build through a collision instead of refusing, and light the cell up in sea lantern.
	 *
	 * <p>Ninety-five builds in the library will not paste, and every one of them is two shapes
	 * wanting the same block. The message says which two and where, which is enough to know the rung
	 * lands at instrument height and not enough to know whose column it lands in -- the chord going
	 * into the turn, or the one coming out of it, or a lane on another floor entirely.</p>
	 *
	 * <p>So: keep whatever was standing, remember the cell, and after everything else is placed go
	 * back over the list with sea lantern. The build is then wrong on purpose and glowing where it is
	 * wrong, which can be walked round and looked at. Never leave this on for a real build.</p>
	 */
	static boolean MARK_COLLISIONS = false;

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
		if (chord.size() <= 3 && !forceBus) {
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
	 * Builds a chord in the shape chosen for it, settling the one thing the choice could not know.
	 *
	 * <p>Whether the pair of slots behind this module is free depends on where the lanes turned,
	 * and lanes turn according to lengths that were measured from these very choices. The knot is
	 * cut by only ever moving in the direction that shortens: a full stacked module that finds its
	 * pair taken drops to a bus, and a bus that finds a turn has freed the pair takes it.</p>
	 */
	private static Placed addChordModule(PlacementPlan placements, Lane lane, int triggerDelay,
			EventGroup event, int slackColumns, boolean roomBehind, boolean inTurn, int roomAhead,
			int signal, Layout layout) {
		Direction travel = lane.travel();
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
			style = FRONT_HEAD_WHEN_BEHIND_BUSY || (FRONT_ONLY_HEADS && style.busHeaded())
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
			style = !inTurn && ultraSlots(event.notes(), false) != null
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
			Lane asking = behindShift ? start.ahead(1) : start;
			int verdict = parityVerdict(placements, asking, event.time(), slots, room);
			if (verdict == 2) {
				moved = relocate(placements, asking, event.time(), style, event.notes(), slots, room);
				placements.padded("planRelocateTo" + moved.where());
			}
			boolean clashesHere = verdict == 1 || verdict < 0;
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
		int stackedRoom = STACKED_CELLS + 1;
		if (style.busHeaded()) {
			StackedBusSplit measured = splitFor(style, event.notes());
			stackedRoom = measured == null ? STACKED_CELLS + 1
				: STACKED_CELLS + STACKED_BUS_TRANSITION + (measured.tail().size() + 1) / 2 + 1;
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
		if (style.stacked() && roomAhead < stackedRoom && !busIsLonger) {
			gaveUp = "roomAhead" + roomAhead + "<" + stackedRoom;
			placements.padded("planBusForRoom");
			style = ChordStyle.BUS;
			nudge = false;
		} else if (busIsLonger && roomAhead < stackedRoom) {
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
		if (!style.stacked()) {
			trace(event, lane, style, style, gaveUp);
			Body body = addSpatialEventModule(placements, lane, triggerDelay, event.notes(),
				style == ChordStyle.BUS);
			return new Placed(body.lane(), style, body.busCells(), false);
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
			placements.commitTrial();
			return placed;
		} catch (IllegalArgumentException collided) {
			placements.rollbackTrial();
			placements.padded("planBusForCollision");
			trace(event, lane, style, ChordStyle.BUS, "collided");
			Body body = addSpatialEventModule(placements, lane, triggerDelay, event.notes(), true);
			return new Placed(body.lane(), ChordStyle.BUS, body.busCells(), false);
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
				Body body = addSpatialEventModule(placements, lane, triggerDelay, event.notes(), true);
				return new Placed(body.lane(), ChordStyle.BUS, body.busCells(), false);
			}
			start = pastAnyCorner(placements, start);
			if (nudge) {
				placements.padded("parity");
				addParityPad(placements, start.pos());
				start = start.ahead(1);
			}
			trace(event, lane, style, style, nudge ? "nudged" : gaveUp);
			placements.placing("chord:" + style + (nudge ? "+nudge" : "")
				+ " head" + split.head().size() + "/tail" + split.tail().size()
				+ (split.slots().backFlanks() == 0 ? " frontOnly" : " reachesBack"));
			Body body = addStackedBusModule(placements, start, triggerDelay, event.time(),
				split.slots(), split.tail());
			return new Placed(body.lane(), style, body.busCells(), nudge);
		}
		// The delay no longer walks off the corner for us -- the two-swap turn wants it -- so the one
		// shape that cannot use it walks off it here.
		start = pastAnyCorner(placements, start);
		if (nudge) {
			placements.padded("parity");
			addParityPad(placements, start.pos());
			start = start.ahead(1);
		}
		trace(event, lane, style, style, nudge ? "nudged" : gaveUp);
		placements.placing("chord:" + style + (nudge ? "+nudge" : "")
			+ (moved == null ? "" : " moved" + moved.where()));
		// The rigid shape has no bus to hand a note to, so until the centre became a target it had
		// no third option at all: it shifted or it fell to a bus.
		return new Placed(addStackedEventModule(placements, start, triggerDelay, event.time(),
			moved != null ? moved.slots()
				: ultraSlots(event.notes(), style == ChordStyle.STACKED_FULL)), style, 0, nudge);
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
	 * <p>0 to build where it stands, 2 to move one low note somewhere free, 1 to shift a column, -1
	 * to give the shape up. Asked in that order, and asked by the planner and the walk through this
	 * one method so that neither can answer it differently from the other.</p>
	 */
	private static int parityVerdict(PlacementPlan placements, Lane at, int time, UltraSlots slots,
			RelocationRoom room) {
		if (!stackedClashes(placements, at, time, slots)) {
			return 0;
		}
		if (relocatableSlot(placements, at, time, slots, room) >= 0) {
			return 2;
		}
		return stackedClashes(placements, at.ahead(1), time, slots) ? -1 : 1;
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
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(), "minecraft:redstone_wire");
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
	 */
	private record StackedSplit(UltraSlots slots, List<EventNote> head, List<EventNote> nearTail,
			List<EventNote> farTail) {
	}

	private static StackedSplit stackedSplitOf(List<EventNote> chord, int room, int splitCells,
			boolean climbing, boolean roomBehind) {
		if (!STACKED_SPLIT_HEADS) {
			return null;
		}
		// The full head first, the head of five when the column behind is spoken for. This is the
		// same fallback an ordinary chord has had since 1da9154 -- that commit taught chordStyle,
		// landingOf and addChordModule to drop to a head of five rather than give the shape up, and
		// left the cut asking for the back flanks unconditionally. So a chord that could have been
		// cut with a short head was laid as a plain bus instead, and illit-do-the-dance at twelve
		// wide lost its lane five columns past the wall for exactly that.
		StackedBusSplit split = roomBehind ? stackedBusSplit(chord, true) : null;
		if (split == null && FRONT_ONLY_HEADS && FRONT_ONLY_CUTS) {
			split = stackedBusSplit(chord, false);
		}
		if (split == null) {
			return null;
		}
		// Head, transition, and -- unless the near half is allowed to be the head alone -- at least
		// one cell of bus to end on.
		int nearBusCells = room - STACKED_CELLS - STACKED_BUS_TRANSITION;
		// Descents only for now. A climb leaves the near half by a glass staircase whose first rung
		// is a level up and a column over, and the handover -- which sits beside the centre on the
		// module's own level -- has nothing bridging it to that rung. Ekran pasted one and found the
		// glass simply disconnected. A descent has no such gap and works today.
		if (nearBusCells < (HEAD_ONLY_NEAR_HALF && !climbing ? 0 : 1)) {
			if (nearBusCells == 0 && !split.tail().isEmpty()
					&& STACKED_BUS_TRANSITION + (split.tail().size() + 1) / 2 + splitCells
						<= DUST_RANGE) {
				HEAD_ONLY_NEAR_HALVES++;
			}
			return null;
		}
		List<EventNote> tail = split.tail();
		int nearNotes = Math.min(tail.size(), 2 * nearBusCells);
		// Nothing to carry over the staircase means this was never a chord that had to be cut.
		if (nearNotes >= tail.size()) {
			return null;
		}
		if (STACKED_BUS_TRANSITION + (tail.size() + 1) / 2 + splitCells > DUST_RANGE) {
			return null;
		}
		return new StackedSplit(split.slots(), split.head(), tail.subList(0, nearNotes),
			tail.subList(nearNotes, tail.size()));
	}

	/** The near half of a cut chord, built as a stacked head with a bus behind it. */
	private static BlockPos addStackedSplitModule(PlacementPlan placements, BlockPos cursor,
			Direction travel, Direction laneStep, int triggerDelay, StackedSplit split, int time) {
		placements.placing("cutHead" + split.head().size()
			+ "/near" + split.nearTail().size() + "/far" + split.farTail().size()
			+ (split.slots().backFlanks() == 0 ? " frontOnly" : " reachesBack"));
		Body body = addStackedBusModule(placements, Lane.straight(cursor, travel, laneStep),
			triggerDelay, time, split.slots(), split.nearTail());
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
		int hangers = reachingBack ? 6 : 4;
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
			StackedBusSplit straight = splitAt(chord, headSize, reachingBack);
			if (straight != null) {
				return straight;
			}
			StackedBusSplit sorted = splitAt(preferred, headSize, reachingBack);
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

	private static StackedBusSplit splitAt(List<EventNote> chord, int headSize,
			boolean reachingBack) {
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
		UltraSlots slots = ultraSlots(head, reachingBack);
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
		Lane afterHead = addStackedEventModule(placements, lane, triggerDelay, time, slots);
		// The cell the centre lights. Stone with dust over it, in the column the head came to rest
		// in -- beside the centre and level with it, never above, because above the centre is the
		// air a note block there insists on.
		addParityPad(placements, afterHead.pos());
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
			if (slots.front(side) != null) {
				placeNote(placements, instrument.relative(travel), slots.front(side));
			}
			if (slots.back(side) != null) {
				placeNote(placements, instrument.relative(travel.getOpposite()), slots.back(side));
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

	/** Whether a lone back flank hangs away from the lane the walk has not built yet. */
	static boolean BACK_FLANK_AWAY_FROM_NEXT_LANE = true;

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
		int hangers = reachingBack ? 6 : 4;
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
	private record Layout(boolean ultra, boolean risers, int centreParity, boolean lookahead) {
		static final Layout STANDARD = new Layout(false, true, 0, false);

		static Layout ultra(int floors, BlockPos origin) {
			// Counted from the origin and not from the world, so the same song pasted a block over
			// is the same build. Anchored where the first module would land anyway, which is one
			// past the origin, so a song that never drifts off the beat never pays for a pad.
			return new Layout(true, floors > 1, Math.floorMod(origin.getX() + 1, 2), false);
		}

		Layout withLookahead() {
			return new Layout(ultra, risers, centreParity, true);
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
		STACKED_BUS_FRONT;

		boolean stacked() {
			return this == STACKED_FRONT || this == STACKED_FULL || busHeaded();
		}

		/** Whether the shape ends in a run of bus, and so spends a cell of wire a note pair. */
		boolean buses() {
			return this == BUS || busHeaded();
		}

		/** Whether the shape needs the pair of low slots behind it free. */
		boolean reachesBack() {
			return this == STACKED_FULL || this == STACKED_BUS;
		}

		/** Whether the shape is a head with a bus behind it, reaching back or not. */
		boolean busHeaded() {
			return this == STACKED_BUS || this == STACKED_BUS_FRONT;
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
	record PastePlan(List<String> commands, int width, int depth, int height, int spanX, int spanZ,
			PasteMode mode, List<String> faults, List<BlockPos> turns, List<Integer> moved,
			List<Integer> breaches, List<Integer> recesses, Map<String, Integer> padding,
			int nearWall, int farWall, Map<BlockPos, String> collisions) {

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
		/** Cells two shapes both wanted, and what each pair was, when {@link #MARK_COLLISIONS}. */
		private final Map<BlockPos, String> collisions = new LinkedHashMap<>();
		/**
		 * What is being built right now, and what built each cell, when {@link #MARK_COLLISIONS}.
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

		void padded(String reason) {
			if (recording) {
				padding.merge(reason, 1, Integer::sum);
			}
		}

		void corner(BlockPos position) {
			if (recording) {
				BlockPos key = position.immutable();
				if (corners.add(key) && trial != null) {
					trial.cornersAdded().add(key);
				}
			}
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
						faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
							+ " belongs to tick " + time + " but would sound early, at tick "
							+ neighbour + ", from the " + direction + " at "
							+ describe(note.getKey().relative(direction), shiftX, shiftZ));
					} else if (neighbour > time + SHARED_PULSE_TICKS) {
						// Named the same way round as the early case. Which side a second sounding comes
						// from is as much the diagnosis here as it is there -- along the lane is one
						// module reaching into the next, across it is the corridor alongside -- and
						// leaving it off meant every one of these had to be traced by hand.
						faults.add("the note at " + describe(note.getKey(), shiftX, shiftZ)
							+ " belongs to tick " + time + " but would sound again at tick "
							+ neighbour + ", from the " + direction + " at "
							+ describe(note.getKey().relative(direction), shiftX, shiftZ)
							+ ", too late for the first pulse to still be covering it");
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

		/** Names the shape about to be built, so a collision can say whose column it is. */
		void placing(String what) {
			placing = what;
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
			if (MARK_COLLISIONS && existing == null) {
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
				if (!MARK_COLLISIONS) {
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
			if (undo == null) {
				return;
			}
			for (BlockPos at : undo.blocksAdded()) {
				blocks.remove(at);
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
			if (!faults.isEmpty() && mode != PasteMode.ULTRA_COMPACT_LANE) {
				throw new IllegalArgumentException("Refusing to build a broken machine: "
					+ faults.get(0) + ". This is a bug in the layout, not in the song.");
			}
			List<String> commands = new ArrayList<>(blocks.entrySet().stream()
				.map(entry -> "setblock " + (entry.getKey().getX() + shiftX) + " "
					+ entry.getKey().getY() + " " + (entry.getKey().getZ() + shiftZ) + " "
					+ entry.getValue() + " replace")
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
				Map.copyOf(padding), nearWall + shiftX, farWall + shiftX, Map.copyOf(marked));
		}
	}
}
