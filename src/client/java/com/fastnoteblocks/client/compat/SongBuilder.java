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

	/** How many blocks of each kind the build would place, for the composer's status bar. */
	static BlockCounts blockCounts(ComposerProject project) {
		return countBlocks(eventNotes(project));
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
	 * How far a lane's modules reach either side of its centre line.
	 *
	 * <p>Measured against the lane step -- the direction the serpentine walks -- and not against
	 * travel, which reverses every lane. That is the whole trick: because a two-note chord always
	 * puts its second note the same way round, two neighbouring lanes can never both grow into the
	 * gap between them, so a sparse pair can sit three apart instead of four.</p>
	 */
	private record LaneReach(int back, int forward) {
		static final LaneReach NONE = new LaneReach(0, 0);

		static LaneReach of(int chordSize) {
			if (chordSize <= 1) {
				return NONE;
			}
			return chordSize <= 2 ? new LaneReach(0, 1) : new LaneReach(1, 1);
		}

		LaneReach widest(LaneReach other) {
			return new LaneReach(Math.max(back, other.back), Math.max(forward, other.forward));
		}

		int width() {
			return back + forward + 1;
		}
	}

	/** The reach of the widest chord in a run of events. */
	private static LaneReach laneReach(List<EventGroup> events, int from, int to) {
		LaneReach reach = LaneReach.NONE;
		for (int index = from; index < to && index < events.size(); index++) {
			reach = reach.widest(LaneReach.of(events.get(index).notes().size()));
		}
		return reach;
	}

	/** Lane centres far enough apart to leave exactly one empty column between two lanes. */
	private static int laneSpacing(LaneReach lane, LaneReach next) {
		return Math.max(2, lane.forward() + next.back() + 2);
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
		if (notes.isEmpty()) {
			throw new IllegalArgumentException(
				"The build sequence is empty. Move some layers into it first.");
		}
		ChordStats stats = chordStats(notes);
		if (stats.peak() > MAX_SIMULTANEOUS_NOTES) {
			throw new IllegalArgumentException(overloadMessage(stats));
		}
		// Fixed world axes rather than the player's facing: travel runs +X and lanes step +Z, so a
		// build always grows into positive coordinates and you know where it will land before you
		// commit to it. Alternating floors double back inside that volume, never past the origin.
		Direction forward = Direction.EAST;
		BlockPos origin = pasteOrigin(minecraft, forward);
		return switch (mode) {
			case COMPACT_CUBE -> createCubePastePlan(origin, forward, notes);
			case COMPACT -> createCompactPastePlan(origin, forward, notes);
			case COMPACT_LANE -> createLanePastePlan(origin, forward, notes,
				FastNoteblocksConfig.get().buildLaneWidth(),
				FastNoteblocksConfig.get().buildLaneFloors());
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
			List<EventNote> notes, int width, int floors) {
		List<EventGroup> events = eventGroups(notes);
		// Two blocks of the width go on the fold itself: the turn steps one past the end of a lane
		// and a corner carrying notes reaches one past that. The wall still has to clear the longest
		// single event, or an event too big to fit would turn on every attempt and never advance.
		int longest = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		int laneWidth = Math.max(longest + 2, width - 2);
		PlacementPlan placements = new PlacementPlan();
		if (floors <= 1) {
			walkFolded(events, origin, forward, laneWidth, Integer.MAX_VALUE, placements);
		} else {
			walkWall(events, origin, forward, laneWidth, floors, placements);
		}
		return placements.finish(PasteMode.COMPACT_LANE, origin);
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
			int laneWidth, int floors, PlacementPlan placements) {
		BlockPos cursor = origin;
		Direction travel = forward;
		// Where chords grow, and the direction the whole slab creeps once a sweep is done.
		Direction depth = forward.getClockWise();
		int nearWall = origin.getX();
		int farWall = origin.getX() + laneWidth;
		int currentTime = 0;
		int floor = 0;
		int climb = 1;
		boolean laneStarted = false;
		LaneReach reach = laneReach(events, 0, events.size());
		int slabStep = laneSpacing(reach, reach);
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			// Settled before the event is placed rather than after it. A turn hands back a cursor at
			// the same point along the wall the last event reached, so an event that overshoots
			// leaves the next lane starting outside the wall -- and nothing measured afterwards can
			// help, because by then the overshoot is built. Asking first costs a lane its last event
			// and keeps the wall a wall.
			boolean canTurn = index > 0
				&& events.get(index - 1).maxSafeTurnDistance() >= MAX_LANE_SPACING;
			int landing = cursor.getX() + travel.getStepX() * event.length();
			if (canTurn && laneStarted && (landing > farWall || landing < nearWall)) {
				int above = floor + climb;
				if (above >= 0 && above < floors) {
					cursor = climb > 0
						? addGlassClimb(placements, cursor, travel,
							events.get(index - 1).notes().size() > 3, currentTime)
						: addSpiralDescent(placements, cursor, travel, depth, currentTime);
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
				travel = travel.getOpposite();
				laneStarted = false;
			}
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel,
				event.time() - currentTime);
			currentTime = event.time();
			cursor = addSpatialEventModule(placements, trigger.cursor(), travel, depth,
				trigger.triggerDelay(), event.notes());
			laneStarted = true;
		}
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
			&& walkFolded(events, origin, forward, laneWidth, corridor, PlacementPlan.dry()) > floors;
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
	private static PastePlan createCubePastePlan(BlockPos origin, Direction forward, List<EventNote> notes) {
		List<EventGroup> events = eventGroups(notes);
		int totalLength = totalEventLength(events);
		int floors = chooseCubeFloors(totalLength);
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
		walkFolded(events, origin, forward, laneWidth, corridor, placements);
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
			int laneWidth, int corridor, PlacementPlan placements) {
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
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			int delay = event.time() - currentTime;
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel, delay);
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
				cursor = addSpatialEventModule(placements, trigger.cursor(), travel, laneStep,
					trigger.triggerDelay(), event.notes());
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
			if (!riser && cornerBuilt) {
				cursor = addTurnEventModule(placements, trigger.cursor(), travel, laneStep, spacing,
					trigger.triggerDelay(), event.notes());
				travel = travel.getOpposite();
				continue;
			}
			cursor = addSpatialEventModule(placements, trigger.cursor(), travel, laneStep,
				trigger.triggerDelay(), event.notes());
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
	private static int chooseCubeFloors(int totalLength) {
		int best = 1;
		int bestSpan = Integer.MAX_VALUE;
		int maximum = FastNoteblocksConfig.get().maxBuildFloors();
		for (int floors = 1; floors <= maximum; floors++) {
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
		List<EventGroup> result = new ArrayList<>();
		int currentTime = 0;
		for (int index = 0; index < notes.size();) {
			int time = notes.get(index).time();
			List<EventNote> chord = new ArrayList<>();
			while (index < notes.size() && notes.get(index).time() == time) {
				chord.add(notes.get(index++));
			}
			int delay = time - currentTime;
			int delayRepeaters = Math.max(0, (delay - 1) / 4);
			int eventLength = chord.size() <= 3 ? 2 : 1 + (chord.size() + 1) / 2;
			int busLength = chord.size() <= 3 ? 0 : (chord.size() + 1) / 2;
			int maxSafeTurnDistance = chord.size() <= 3 ? 13 : Math.max(0, 13 - busLength);
			result.add(new EventGroup(time, List.copyOf(chord), delayRepeaters + eventLength,
				maxSafeTurnDistance));
			currentTime = time;
		}
		return List.copyOf(result);
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
		List<BlockPos> slots = new ArrayList<>();
		for (int offset = 0; offset <= laneDistance; offset++) {
			BlockPos run = corner.relative(laneStep, offset);
			placements.powered(run, "minecraft:stone", time);
			set(placements, run.above(), "minecraft:redstone_wire");
			slots.add(run.relative(travel));
			// The near side of the first cell is the repeater driving it, and the near side of the
			// last is where the next lane's repeater has to stand.
			if (offset > 0 && offset < laneDistance) {
				slots.add(run.relative(travel.getOpposite()));
			}
		}
		for (int index = 0; index < chord.size(); index++) {
			placeNote(placements, slots.get(index), chord.get(index));
		}
		return cursor.relative(laneStep, laneDistance);
	}

	private static BlockPos addCompactTurn(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, int laneDistance, int time) {
		if (laneDistance < 1 || laneDistance > 13) {
			throw new IllegalArgumentException("Compact turn distance " + laneDistance
				+ " exceeds the safe redstone range");
		}
		BlockPos outer = cursor.relative(travel);
		placements.powered(cursor, "minecraft:stone", time);
		set(placements, cursor.above(), "minecraft:redstone_wire");
		placements.powered(outer, "minecraft:stone", time);
		set(placements, outer.above(), "minecraft:redstone_wire");
		for (int offset = 1; offset <= laneDistance; offset++) {
			BlockPos turn = outer.relative(laneStep, offset);
			placements.powered(turn, "minecraft:stone", time);
			set(placements, turn.above(), "minecraft:redstone_wire");
		}
		return outer.relative(laneStep, laneDistance).relative(travel.getOpposite());
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
		int time = chord.get(0).time();
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
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
		int busLength = (chord.size() + 1) / 2;
		for (int bus = 0; bus < busLength; bus++) {
			BlockPos busPos = anchor.relative(travel, bus);
			placements.powered(busPos, "minecraft:stone", time);
			set(placements, busPos.above(), "minecraft:redstone_wire");
		}
		for (int noteIndex = 0; noteIndex < chord.size(); noteIndex++) {
			int bus = noteIndex / 2;
			Direction noteSide = noteIndex % 2 == 0 ? laneStep : laneStep.getOpposite();
			placeNote(placements, anchor.relative(travel, bus).relative(noteSide), chord.get(noteIndex));
		}
		return cursor.relative(travel, 1 + busLength);
	}

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
		for (int noteIndex = 0; noteIndex < chord.size(); noteIndex++) {
			EventNote note = chord.get(noteIndex);
			int bus = noteIndex / 2;
			Direction side = noteIndex % 2 == 0 ? right : right.getOpposite();
			placeNote(placements, anchor.relative(forward, bus).relative(side), note);
		}
		return cursor + 1 + busLength;
	}

	private static void placeNote(PlacementPlan placements, BlockPos notePos, EventNote note) {
		set(placements, notePos.below(), note.instrumentBlock());
		if (FALLING_INSTRUMENT_BLOCKS.contains(note.instrumentBlock())) {
			// Sand and friends drop the moment /setblock places them over air, taking the note
			// block's instrument with them. Fill the space beneath, but never overwrite: anything
			// already planned there is load-bearing and already does the supporting.
			placements.support(notePos.below().below(), "minecraft:stone");
		}
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
		PreviewInstrument preview = PreviewInstrument.byId(instrument);
		if ("MUTE".equals(preview.id())) {
			return null;
		}
		if ("HARP".equals(preview.id())) {
			// A note block over anything unrecognised already plays harp, so air is identical in
			// sound and costs nothing. Placing grass would waste a block per piano note.
			return "minecraft:air";
		}
		return BuiltInRegistries.ITEM.getKey(preview.icon()).toString();
	}

	record EventNote(int time, int trackNumber, int order, int pitch, String instrumentBlock) {
	}

	private record EventGroup(int time, List<EventNote> notes, int length, int maxSafeTurnDistance) {
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
		LANE("Lane");

		private final String label;

		PasteMode(String label) {
			this.label = label;
		}

		String label() {
			return label;
		}
	}

	record PastePlan(List<String> commands, int width, int depth, int height, PasteMode mode) {
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

		void note(BlockPos position, int time) {
			if (recording) {
				notes.put(position.immutable(), time);
			}
		}

		/**
		 * Checks that every note block plays, and plays once, at the moment it is supposed to.
		 *
		 * <p>A layout mistake here is silent: the build goes up, looks right, and plays a note two
		 * lanes away half a bar early. Both halves matter -- an untriggered note is a hole in the
		 * song, and a note reached by a foreign event is a wrong note -- and between them they are
		 * what makes it safe to pack lanes by measurement rather than by a constant.</p>
		 */
		void verify() {
			for (Map.Entry<BlockPos, Integer> note : notes.entrySet()) {
				int time = note.getValue();
				Integer own = powered.get(note.getKey());
				boolean triggered = own != null && own == time;
				for (Direction direction : Direction.values()) {
					Integer neighbour = powered.get(note.getKey().relative(direction));
					if (neighbour == null) {
						continue;
					}
					if (neighbour != time) {
						throw new IllegalArgumentException("Refusing to build a broken machine: the "
							+ "note at " + describe(note.getKey()) + " belongs to tick " + time
							+ " but would also sound at tick " + neighbour
							+ ". This is a bug in the layout, not in the song.");
					}
					triggered = true;
				}
				if (!triggered) {
					throw new IllegalArgumentException("Refusing to build a broken machine: the note "
						+ "at " + describe(note.getKey()) + " has nothing to set it off. This is a "
						+ "bug in the layout, not in the song.");
				}
			}
		}

		private static String describe(BlockPos position) {
			return position.getX() + " " + position.getY() + " " + position.getZ();
		}

		void set(BlockPos position, String block) {
			if (!recording) {
				return;
			}
			BlockPos key = position.immutable();
			String existing = blocks.putIfAbsent(key, block);
			if (existing != null && !existing.equals(block)) {
				throw new IllegalArgumentException("Placement layout collision at "
					+ key.getX() + " " + key.getY() + " " + key.getZ());
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
			verify();
			// Nothing lands behind you. The walk reaches a block outside its own walls here and
			// there -- a chord hanging off the far side of the first lane, a corner overshooting the
			// end of one, a floor not quite the width of the one below it -- and a build that starts
			// a block behind where you were standing is a build you cannot line up from a corner.
			// Sliding the finished plan is exact and costs nothing; the height is left alone,
			// because that is measured from your feet and not from a wall.
			int shiftX = minimumX == Integer.MAX_VALUE ? 0 : origin.getX() - minimumX;
			int shiftZ = minimumZ == Integer.MAX_VALUE ? 0 : origin.getZ() - minimumZ;
			List<String> commands = blocks.entrySet().stream()
				.map(entry -> "setblock " + (entry.getKey().getX() + shiftX) + " "
					+ entry.getKey().getY() + " " + (entry.getKey().getZ() + shiftZ) + " "
					+ entry.getValue() + " replace")
				.toList();
			int widthX = maximumX < minimumX ? 0 : maximumX - minimumX + 1;
			int widthZ = maximumZ < minimumZ ? 0 : maximumZ - minimumZ + 1;
			int height = maximumY < minimumY ? 0 : maximumY - minimumY + 1;
			return new PastePlan(commands, Math.max(widthX, widthZ), Math.min(widthX, widthZ), height, mode);
		}
	}
}
