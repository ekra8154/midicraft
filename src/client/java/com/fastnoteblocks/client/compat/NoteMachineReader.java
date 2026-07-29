package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads a note block machine back out of blocks, whether they came from the world or from a file.
 *
 * <p>A machine does not store its music. What it stores is a circuit, and the tune only exists as
 * the order that circuit fires in -- so reading one back means working out, for every note block,
 * the moment the signal reaches it. That is what this does: it traces power out from wherever the
 * machine starts, accumulating repeater delay as it goes, and reads each note block's time off the
 * neighbour that sets it off.</p>
 *
 * <p>Tracing rather than pattern-matching is the whole point. Note Block Studio's schematic import
 * only ever understood the layout Note Block Studio itself exported, and was dropped for being
 * outdated; a layout reader would in the same way only understand builds this mod made, and would
 * be defeated by someone hand-placing a repeater. Following the signal understands any machine
 * whose timing is repeaters and dust, which is nearly all of them.</p>
 *
 * <p>What it does not understand is anything that generates or gates a signal rather than carrying
 * it: comparators, observers, pistons, droppers, clocks. Those are reported rather than guessed at,
 * because a machine read wrongly looks exactly like a machine read rightly until you play it.</p>
 *
 * <p>It follows rising edges and nothing else: a note block sounds once, at the first moment power
 * reaches it, however many times power reaches it afterwards. That is what a note block does, and
 * it is what makes the compact overlapping modules of community builds read correctly -- where the
 * front note blocks of one module are the back note blocks of the next, the shared ones are still
 * held high when the second module arrives, find no fresh edge, and stay silent. Keeping the
 * earliest arrival reproduces that exactly, and credits the overlap to the module a listener hears
 * it in.</p>
 *
 * <p>The assumption underneath is that power never <em>falls</em> between two arrivals, which holds
 * while the gap is shorter than the pulse driving the machine -- forever for a lever, about ten
 * repeater ticks for a button. A build that deliberately re-sounds one note block after its power
 * has dropped is playing that block twice and this will report it once. Nothing here can tell the
 * difference, because that answer lives in the length of a pulse nobody wrote down.</p>
 */
public final class NoteMachineReader {
	private NoteMachineReader() {
	}

	/**
	 * Composer ticks per repeater tick in the song this produces.
	 *
	 * <p>At 480 ppq the composer's span is {@code ppq * 100000 / tempo}, so a tempo of 400000 --
	 * 150 BPM -- makes one repeater tick exactly 120 ticks, a sixteenth note. Choosing the tempo to
	 * suit the machine rather than the other way round means what comes out is already buildable:
	 * every gap in it is a whole number of repeater ticks because every gap in the machine was.</p>
	 */
	public static final int TICKS_PER_REDSTONE_TICK = 120;
	private static final int TEMPO_MICROS_PER_QUARTER =
		ComposerProject.DEFAULT_PPQ * 100_000 / TICKS_PER_REDSTONE_TICK;

	/** Blocks a region is made of, however they are stored. */
	@FunctionalInterface
	public interface BlockLookup {
		BlockState at(BlockPos position);
	}

	/**
	 * A machine read back, and everything worth saying about how the reading went.
	 *
	 * @param unreachedNotes note blocks the signal never got to, which are left out of the song
	 * @param headNotes note blocks standing on a mob head, whose sound the composer has no layer
	 *     for and which therefore come back as a harp
	 * @param versions complete performances found -- more than one when a machine has ways in that
	 *     neither contains the other, or when the selection holds machines that share nothing
	 * @param warnings circuitry that carries no timing this can follow
	 */
	public record Reading(
		ComposerProject project,
		int noteBlocks,
		int unreachedNotes,
		int headNotes,
		int redstoneTicks,
		int versions,
		List<String> warnings
	) {
		public String report() {
			StringBuilder text = new StringBuilder();
			if (versions > 1) {
				text.append(versions).append(" versions, ");
			}
			text.append(project.layers().size()).append(" layers, ")
				.append(project.noteCount()).append(" notes");
			if (redstoneTicks > 0) {
				text.append(String.format(Locale.ROOT, ", %.1fs long",
					redstoneTicks / 10.0));
			}
			if (unreachedNotes > 0) {
				text.append(", ").append(unreachedNotes).append(" never triggered");
			}
			if (headNotes > 0) {
				text.append(", ").append(headNotes).append(" on a mob head");
			}
			for (String warning : warnings) {
				text.append(" - ").append(warning);
			}
			return text.toString();
		}
	}

	/** A machine that could be found but not read, with the reason put in the player's terms. */
	public static class UnreadableException extends RuntimeException {
		public UnreadableException(String message) {
			super(message);
		}
	}

	/**
	 * Reads every note block between two corners as a song.
	 *
	 * @param name what to call the song
	 * @param from one corner, inclusive
	 * @param to the other corner, inclusive
	 */
	public static Reading read(String name, BlockPos from, BlockPos to, BlockLookup blocks) {
		Region region = new Region(from, to, blocks);
		Survey survey = survey(region);
		if (survey.noteBlocks.isEmpty()) {
			throw new UnreadableException("No note blocks in that region. Check the coordinates -- "
				+ "both corners are inclusive, and the region is read as a box between them.");
		}
		return assemble(name, region, survey, trace(region, survey));
	}

	// ------------------------------------------------------------------ what is in the region

	/** Everything in the region worth a second look, found in one pass so the region is walked once. */
	private static final class Survey {
		final List<BlockPos> noteBlocks = new ArrayList<>();
		final List<BlockPos> repeaters = new ArrayList<>();
		/** Blocks that make power on their own: levers, buttons, torches, blocks of redstone. */
		final List<BlockPos> sources = new ArrayList<>();
		final Set<String> unsupported = new LinkedHashSet<>();
		/** Torches specifically, because what they usually mean is inversion, which is not read. */
		int torches;
	}

	private static Survey survey(Region region) {
		Survey survey = new Survey();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = region.minX; x <= region.maxX; x++) {
			for (int y = region.minY; y <= region.maxY; y++) {
				for (int z = region.minZ; z <= region.maxZ; z++) {
					BlockState state = region.at(cursor.set(x, y, z));
					if (state.isAir()) {
						continue;
					}
					BlockPos position = cursor.immutable();
					if (state.is(Blocks.NOTE_BLOCK)) {
						survey.noteBlocks.add(position);
					} else if (state.is(Blocks.REPEATER)) {
						survey.repeaters.add(position);
					} else if (isSource(state)) {
						survey.sources.add(position);
						if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
							survey.torches++;
						}
					} else if (state.getBlock() instanceof ComparatorBlock) {
						survey.unsupported.add("comparators");
					} else if (state.is(Blocks.OBSERVER)) {
						survey.unsupported.add("observers");
					} else if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) {
						survey.unsupported.add("pistons");
					}
				}
			}
		}
		return survey;
	}

	private static boolean isSource(BlockState state) {
		return state.is(Blocks.REDSTONE_BLOCK)
			|| state.is(Blocks.LEVER)
			|| state.is(Blocks.REDSTONE_TORCH)
			|| state.is(Blocks.REDSTONE_WALL_TORCH)
			|| state.is(net.minecraft.tags.BlockTags.BUTTONS)
			|| state.is(net.minecraft.tags.BlockTags.PRESSURE_PLATES);
	}

	// ------------------------------------------------------------------ following the signal

	/**
	 * A pulse arriving somewhere: strong power into a block, or a level of dust.
	 *
	 * <p>Dust carries a strength because dust fades, and a run that fades to nothing is a run that
	 * does not reach -- reading a machine as if every wire were infinite would connect two halves
	 * that in the world are separate.</p>
	 */
	private record Pulse(int time, BlockPos position, int strength, boolean dust, BlockPos from,
		BlockPos origin) {
	}

	/**
	 * One walk out from a set of starts.
	 *
	 * @param looped whether the signal came back to a repeater it had already been through, which
	 *     is a machine that repeats rather than one that ends
	 */
	private record Walk(Map<BlockPos, Integer> firedAt, boolean looped) {
	}

	/**
	 * Walks power out from the machine's start and reports when each note block first sounds.
	 *
	 * <p>Earliest arrival wins, so this is a shortest-path walk rather than a flood: a note block
	 * reached twice plays on the first pulse and is already powered when the second arrives.</p>
	 */
	private static Walk traceFrom(Region region, Survey survey, List<BlockPos> starts) {
		PriorityQueue<Pulse> queue = new PriorityQueue<>(
			Comparator.comparingInt(Pulse::time).thenComparing(Comparator.comparingInt(Pulse::strength).reversed()));
		for (BlockPos start : starts) {
			BlockState state = region.at(start);
			if (state.is(Blocks.REPEATER)) {
				// A head repeater with nothing behind it: whatever the player throws to start the
				// machine arrives here, and the song begins when this repeater lets go.
				queue.add(new Pulse(state.getValue(RepeaterBlock.DELAY),
					start.relative(state.getValue(RepeaterBlock.FACING).getOpposite()), 15, false,
					start, start));
			} else {
				for (Direction direction : Direction.values()) {
					queue.add(new Pulse(0, start.relative(direction), 15, false, start, start));
				}
			}
		}

		Map<BlockPos, Integer> strongAt = new HashMap<>();
		Map<BlockPos, Integer> dustTime = new HashMap<>();
		Map<BlockPos, Integer> dustStrength = new HashMap<>();
		Map<BlockPos, Integer> repeaterInput = new HashMap<>();
		Map<BlockPos, Integer> firedAt = new HashMap<>();
		// What hands the signal to what, repeater by repeater. Recorded even for handoffs the walk
		// then refuses, because the handoff that closes a loop is precisely the refused one: it
		// arrives at a repeater that has already been through, which is what refusal means here.
		Map<BlockPos, Set<BlockPos>> feeds = new LinkedHashMap<>();
		Set<BlockPos> noteBlocks = new HashSet<>(survey.noteBlocks);

		while (!queue.isEmpty()) {
			Pulse pulse = queue.poll();
			BlockPos position = pulse.position();
			if (!region.contains(position)) {
				continue;
			}
			BlockState state = region.at(position);
			if (pulse.dust()) {
				if (!state.is(Blocks.REDSTONE_WIRE) || pulse.strength() < 1) {
					continue;
				}
				Integer seenTime = dustTime.get(position);
				Integer seenStrength = dustStrength.get(position);
				boolean better = seenTime == null || pulse.time() < seenTime
					|| pulse.time() == seenTime && pulse.strength() > seenStrength;
				if (!better) {
					for (Direction direction : Direction.values()) {
						recordFeed(region, position.relative(direction), position, pulse.origin(),
							feeds);
					}
					continue;
				}
				dustTime.put(position, pulse.time());
				dustStrength.put(position, pulse.strength());
				spreadFromDust(region, queue, position, pulse.time(), pulse.strength(),
					firedAt, noteBlocks, repeaterInput, pulse.origin(), feeds);
				continue;
			}
			if (state.is(Blocks.REDSTONE_WIRE)) {
				// Strong power lands on a wire as a full-strength pulse rather than as block power.
				queue.add(new Pulse(pulse.time(), position, 15, true, pulse.from(), pulse.origin()));
				continue;
			}
			if (noteBlocks.contains(position)) {
				firedAt.merge(position, pulse.time(), Math::min);
			}
			// A repeater pointed straight at another one, with no block in between. Every delay
			// longer than a single repeater can hold is built that way, so missing this reads a
			// machine as ending at its first long silence -- which is to say, almost at once.
			feedRepeater(region, queue, position, pulse.from(), pulse.time(), repeaterInput,
				pulse.origin(), feeds);
			if (!isConductor(state)) {
				continue;
			}
			Integer seen = strongAt.get(position);
			if (seen != null && seen <= pulse.time()) {
				// Nothing new to spread -- but the handoff still happened, and around a loop it is
				// exactly this handoff that closes the ring. The signal comes back to a block it
				// has already powered, so the walk rightly stops here; recording the edge anyway is
				// the only way the shape is ever visible.
				for (Direction direction : Direction.values()) {
					recordFeed(region, position.relative(direction), position, pulse.origin(), feeds);
				}
				continue;
			}
			strongAt.put(position, pulse.time());
			spreadFromPoweredBlock(region, queue, position, pulse.time(), pulse.strength() > 0,
				firedAt, noteBlocks, repeaterInput, pulse.origin(), feeds);
		}
		return new Walk(firedAt, hasCycle(feeds));
	}

	/**
	 * Whether the signal can get back to a repeater it has already been through.
	 *
	 * <p>An ordinary depth-first search for a back edge: a repeater found again while it is still
	 * being explored is one the path has come round to. Machines converge all the time -- two
	 * routes meeting at one repeater is not a loop -- so it has to be this and not simply a repeater
	 * reached twice.</p>
	 */
	private static boolean hasCycle(Map<BlockPos, Set<BlockPos>> feeds) {
		Set<BlockPos> exploring = new HashSet<>();
		Set<BlockPos> settled = new HashSet<>();
		for (BlockPos node : feeds.keySet()) {
			if (!settled.contains(node) && reachesItself(node, feeds, exploring, settled)) {
				return true;
			}
		}
		return false;
	}

	private static boolean reachesItself(BlockPos node, Map<BlockPos, Set<BlockPos>> feeds,
			Set<BlockPos> exploring, Set<BlockPos> settled) {
		if (exploring.contains(node)) {
			return true;
		}
		if (!settled.add(node)) {
			return false;
		}
		exploring.add(node);
		for (BlockPos next : feeds.getOrDefault(node, Set.of())) {
			if (reachesItself(next, feeds, exploring, settled)) {
				return true;
			}
		}
		exploring.remove(node);
		return false;
	}

	/**
	 * Passes a dust's signal on to everything it touches.
	 *
	 * @param strength what this length of dust is carrying, which the next length of dust gets one
	 *     less of
	 */
	private static void spreadFromDust(Region region, PriorityQueue<Pulse> queue, BlockPos position,
			int time, int strength, Map<BlockPos, Integer> firedAt, Set<BlockPos> noteBlocks,
			Map<BlockPos, Integer> repeaterInput, BlockPos origin,
			Map<BlockPos, Set<BlockPos>> feeds) {
		// Dust powers the block it sits on, and any block it runs into. Those are the two ways a
		// note block ever hears about it -- dust does not reach through a block to the far side.
		BlockPos below = position.below();
		if (noteBlocks.contains(below)) {
			firedAt.merge(below, time, Math::min);
		}
		// Marked as dust-fed: a block powered only by the wire on top of it must not turn round and
		// re-power that wire to fifteen, which is a loop the game avoids by ignoring wires entirely
		// while it works out what a wire is carrying.
		if (isConductor(region.at(below))) {
			queue.add(new Pulse(time, below, 0, false, position, origin));
		}
		Set<Direction> pointsAt = pointsAt(region, position);
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			for (BlockPos next : dustNeighbours(region, position, direction)) {
				queue.add(new Pulse(time, next, strength - 1, true, position, origin));
			}
			if (!pointsAt.contains(direction)) {
				continue;
			}
			BlockPos side = position.relative(direction);
			if (noteBlocks.contains(side)) {
				firedAt.merge(side, time, Math::min);
			}
			BlockState sideState = region.at(side);
			if (isConductor(sideState) && !sideState.is(Blocks.NOTE_BLOCK)) {
				queue.add(new Pulse(time, side, 0, false, position, origin));
			}
			feedRepeater(region, queue, side, position, time, repeaterInput, origin, feeds);
		}
		feedRepeater(region, queue, position.above(), position, time, repeaterInput, origin, feeds);
		feedRepeater(region, queue, position.below(), position, time, repeaterInput, origin, feeds);
	}

	/**
	 * The horizontal directions a length of dust actually pushes its signal into.
	 *
	 * <p>Not all four. Dust powers what it points at, and what it points at is decided by its shape:
	 * a wire running north-south does nothing to the block sitting east of it. Two shape rules do
	 * the rest of the work, and both matter here -- an isolated dust with nothing to join becomes a
	 * cross and powers all four ways, and a dust joined on one side only straightens into a line
	 * and powers the far side too. That second rule is the one that makes the commonest hand-built
	 * arrangement of all work: a wire run ending against a note block, joined only from behind.</p>
	 */
	private static Set<Direction> pointsAt(Region region, BlockPos position) {
		Set<Direction> joined = new LinkedHashSet<>();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (!dustNeighbours(region, position, direction).isEmpty()
					|| acceptsWire(region.at(position.relative(direction)), direction)) {
				joined.add(direction);
			}
		}
		if (joined.isEmpty()) {
			return Set.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
		}
		if (joined.size() == 1) {
			Direction only = joined.iterator().next();
			return Set.of(only, only.getOpposite());
		}
		return joined;
	}

	/**
	 * Whether a block joins a wire coming at it from {@code from}, as opposed to merely being hit
	 * by one.
	 *
	 * @param from the direction the wire runs to reach this block
	 */
	private static boolean acceptsWire(BlockState state, Direction from) {
		if (state.is(Blocks.REPEATER)) {
			// Only from behind. A repeater ignores a wire arriving at its side, which is exactly
			// what keeps two neighbouring lanes of a build from feeding each other.
			return state.getValue(RepeaterBlock.FACING) == from.getOpposite();
		}
		return state.getBlock() instanceof ComparatorBlock
			|| state.is(Blocks.REDSTONE_BLOCK)
			|| state.is(Blocks.LEVER)
			|| state.is(Blocks.REDSTONE_TORCH)
			|| state.is(Blocks.REDSTONE_WALL_TORCH);
	}

	/**
	 * The dust a run continues into in one horizontal direction: level, up a step, or down one.
	 *
	 * <p>A step up only connects when the block over the lower dust lets the signal past, which is
	 * why staircases are built out of glass; a step down only connects when nothing is standing in
	 * the way at the top of it. Getting these two right is what lets a build's risers and descents
	 * be followed instead of reading as two unrelated machines.</p>
	 */
	private static List<BlockPos> dustNeighbours(Region region, BlockPos position, Direction direction) {
		List<BlockPos> found = new ArrayList<>(2);
		BlockPos level = position.relative(direction);
		if (region.at(level).is(Blocks.REDSTONE_WIRE)) {
			found.add(level);
		}
		if (!isOccluding(region.at(position.above())) && region.at(level.above()).is(Blocks.REDSTONE_WIRE)) {
			found.add(level.above());
		}
		if (!isOccluding(region.at(level)) && region.at(level.below()).is(Blocks.REDSTONE_WIRE)) {
			found.add(level.below());
		}
		return found;
	}

	/**
	 * Passes a powered block's signal on.
	 *
	 * @param fromSource whether the power came from something other than dust. Only then does it
	 *     light dust of its own, because a block that is only powered by the wire lying on it is
	 *     not a second source of that same wire's signal.
	 */
	private static void spreadFromPoweredBlock(Region region, PriorityQueue<Pulse> queue,
			BlockPos position, int time, boolean fromSource, Map<BlockPos, Integer> firedAt,
			Set<BlockPos> noteBlocks, Map<BlockPos, Integer> repeaterInput, BlockPos origin,
			Map<BlockPos, Set<BlockPos>> feeds) {
		for (Direction direction : Direction.values()) {
			BlockPos side = position.relative(direction);
			if (noteBlocks.contains(side)) {
				firedAt.merge(side, time, Math::min);
			}
			if (fromSource && region.at(side).is(Blocks.REDSTONE_WIRE)) {
				queue.add(new Pulse(time, side, 15, true, position, origin));
			}
			feedRepeater(region, queue, side, position, time, repeaterInput, origin, feeds);
		}
	}

	/** Drives a repeater, if the block at {@code candidate} is one and {@code from} is its back. */
	private static void feedRepeater(Region region, PriorityQueue<Pulse> queue, BlockPos candidate,
			BlockPos from, int time, Map<BlockPos, Integer> repeaterInput, BlockPos origin,
			Map<BlockPos, Set<BlockPos>> feeds) {
		BlockState state = region.at(candidate);
		if (!state.is(Blocks.REPEATER)) {
			return;
		}
		Direction facing = state.getValue(RepeaterBlock.FACING);
		if (!candidate.relative(facing).equals(from)) {
			return;
		}
		recordFeed(region, candidate, from, origin, feeds);
		Integer seen = repeaterInput.get(candidate);
		if (seen != null && seen <= time) {
			return;
		}
		repeaterInput.put(candidate, time);
		// A repeater holds the signal for its delay and then hands it to the block in front. That
		// hold is the only thing in a machine that makes time pass, so the whole song's rhythm is
		// this one addition, repeated.
		queue.add(new Pulse(time + state.getValue(RepeaterBlock.DELAY),
			candidate.relative(facing.getOpposite()), 15, false, candidate, candidate));
	}

	/** How many ways in to follow one at a time before giving up and running them all together. */
	private static final int MAX_TRACED_STARTS = 24;

	/** One complete performance: press this way in, hear these note blocks at these times. */
	private record Version(BlockPos entry, Map<BlockPos, Integer> firedAt) {
	}

	/**
	 * @param overlapping whether any two kept versions share note blocks, which is the difference
	 *     between alternative readings of one machine and machines that merely stand side by side
	 * @param subsumed ways in dropped for reaching only part of what another reaches
	 */
	private record Trace(List<Version> versions, boolean overlapping, int subsumed, boolean looped) {
		Set<BlockPos> played() {
			Set<BlockPos> all = new HashSet<>();
			versions.forEach(version -> all.addAll(version.firedAt().keySet()));
			return all;
		}
	}

	/**
	 * Follows each way in separately and keeps the ones that are not merely part of another.
	 *
	 * <p>Running them all as one walk and keeping each note's earliest pulse is right only if every
	 * start really fires together. Where starts lead into the same note blocks they do not: those
	 * are different ways to begin, only one happens when a player throws a switch, and read together
	 * the shared part takes the nearest chain's timing and lands on top of its own beginning.</p>
	 *
	 * <p>What to do about that turns on one question -- is a way in <em>contained</em> in another?
	 * A checkpoint is: it reaches a tail of what the real beginning reaches and nothing besides, so
	 * dropping it loses no music and keeping it would only duplicate. Two openings meeting at a
	 * shared chorus are not: each holds notes the other never touches, and the chorus sits a
	 * different distance from each, so no single timeline holds both. Those are genuinely two
	 * performances and both are kept, each timed from its own start, with the shared chorus
	 * appearing in both because it really is played by both.</p>
	 *
	 * <p>So the rule is simply: keep every way in that no other way in contains. Checkpoints fall
	 * out, alternatives survive, and machines that share nothing survive too -- they contain each
	 * other not at all, which is the same test arriving at the same answer for a different reason.
	 * It also retires a tie-break that had been settling a musical question by comparing
	 * coordinates: two openings no longer compete, they both play.</p>
	 */
	private static Trace trace(Region region, Survey survey) {
		List<BlockPos> starts = startingPoints(region, survey);
		if (starts.size() == 1 || starts.size() > MAX_TRACED_STARTS) {
			Walk walk = traceFrom(region, survey, starts);
			return new Trace(List.of(new Version(starts.get(0), walk.firedAt())), false, 0,
				walk.looped());
		}

		boolean looped = false;
		List<Version> traced = new ArrayList<>(starts.size());
		for (BlockPos start : starts) {
			Walk walk = traceFrom(region, survey, List.of(start));
			looped |= walk.looped();
			traced.add(new Version(start, walk.firedAt()));
		}

		List<Version> kept = new ArrayList<>();
		for (int index = 0; index < traced.size(); index++) {
			Set<BlockPos> mine = traced.get(index).firedAt().keySet();
			boolean contained = false;
			for (int other = 0; other < traced.size() && !contained; other++) {
				Set<BlockPos> theirs = traced.get(other).firedAt().keySet();
				// Two ways in that reach exactly the same notes are the same performance twice --
				// the timings differ only by whatever repeater sits before the join, which rebasing
				// removes. Keeping the earlier one settles that without it mattering which.
				contained = other != index && theirs.containsAll(mine)
					&& (theirs.size() > mine.size() || other < index);
			}
			if (!contained) {
				kept.add(traced.get(index));
			}
		}

		boolean overlapping = false;
		for (int left = 0; left < kept.size() && !overlapping; left++) {
			for (int right = left + 1; right < kept.size() && !overlapping; right++) {
				overlapping = !java.util.Collections.disjoint(
					kept.get(left).firedAt().keySet(), kept.get(right).firedAt().keySet());
			}
		}
		return new Trace(List.copyOf(kept), overlapping, traced.size() - kept.size(), looped);
	}

	/** Notes that {@code from} hands the signal to {@code candidate}, if it does. */
	private static void recordFeed(Region region, BlockPos candidate, BlockPos from, BlockPos origin,
			Map<BlockPos, Set<BlockPos>> feeds) {
		BlockState state = region.at(candidate);
		if (origin == null || !state.is(Blocks.REPEATER)
				|| !candidate.relative(state.getValue(RepeaterBlock.FACING)).equals(from)) {
			return;
		}
		feeds.computeIfAbsent(origin, ignored -> new LinkedHashSet<>()).add(candidate);
	}

	/**
	 * Everywhere the signal could get in.
	 *
	 * <p>Two kinds. A lever, a button or a block of redstone makes power on its own. A repeater with
	 * nothing behind it is the other: every repeater in a chain is fed by the one before, so one fed
	 * by nothing is where a player pushes power in.</p>
	 *
	 * <p>Both kinds, always -- not one kind when there is one. Preferring sources meant a single
	 * block of redstone anywhere in the selection threw away the real head of the chain, and blocks
	 * of redstone are exactly what a piston contraption is made of, so a build could be gutted by a
	 * component that was not even part of its timing. Offering everything is safe now that a way in
	 * contained by another is dropped: a stray source reaches a tail of what the real beginning
	 * reaches, or nothing at all, and falls out on its own either way.</p>
	 */
	private static List<BlockPos> startingPoints(Region region, Survey survey) {
		List<BlockPos> heads = new ArrayList<>(survey.sources);
		for (BlockPos repeater : survey.repeaters) {
			BlockPos behind = repeater.relative(region.at(repeater).getValue(RepeaterBlock.FACING));
			BlockState input = region.at(behind);
			// A repeater fed by a lever is fed, and counting it as a beginning as well would offer
			// the same chain twice under two names.
			boolean fed = !input.isAir() && (isConductor(input) || input.is(Blocks.REDSTONE_WIRE)
				|| input.is(Blocks.REPEATER) || isSource(input));
			if (!fed) {
				heads.add(repeater);
			}
		}
		if (heads.isEmpty()) {
			// Two quite different situations, and telling someone their machine is a loop when what
			// they actually selected is a wall of bare note blocks helps nobody.
			throw new UnreadableException(survey.repeaters.isEmpty()
				? "Found " + survey.noteBlocks.size() + " note blocks but no redstone to play them. "
					+ "A note block machine needs repeaters to carry its timing; without them there "
					+ "is no order to read, only blocks."
				: "Found note blocks but no way in: every repeater is fed by another one, so there "
					+ "is no start to follow. A machine driven by a clock or a loop cannot be read "
					+ "this way -- include the lever or button that starts it.");
		}
		return heads;
	}

	// ------------------------------------------------------------------ turning it into a song

	private static Reading assemble(String name, Region region, Survey survey, Trace trace) {
		// Instrument first, then pitch and time, so a layer's notes come out already in order.
		// Keyed by version too when there is more than one: each version is a whole performance,
		// and folding their harps into one layer would throw away the only handle for muting the
		// one you did not want.
		Map<String, List<NoteEvent>> byInstrument = new LinkedHashMap<>();
		int headNotes = 0;
		long nextId = 1L;
		long span = 0L;

		for (int number = 0; number < trace.versions().size(); number++) {
			Map<BlockPos, Integer> firedAt = trace.versions().get(number).firedAt();
			// Each version is timed from its own start, so muting one leaves the other beginning
			// at the beginning rather than however far along it happens to join.
			int earliest = firedAt.values().stream().mapToInt(Integer::intValue).min().orElse(0);
			List<BlockPos> played = new ArrayList<>(firedAt.keySet());
			played.sort(Comparator.comparingInt((BlockPos position) -> firedAt.get(position))
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getZ));
			for (BlockPos position : played) {
				Instrument instrument = instrumentAt(region, position);
				if (instrument.fromHead()) {
					headNotes++;
				}
				int pitch = region.at(position).getValue(NoteBlock.NOTE);
				long tick = (firedAt.get(position) - earliest) * (long)TICKS_PER_REDSTONE_TICK;
				span = Math.max(span, tick);
				String key = trace.versions().size() > 1
					? number + "/" + instrument.id()
					: instrument.id();
				byInstrument.computeIfAbsent(key, ignored -> new ArrayList<>())
					.add(new NoteEvent(nextId++, ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE + pitch,
						tick, ComposerProject.DEFAULT_NOTE_DURATION_TICKS, 96));
			}
		}

		List<Layer> layers = new ArrayList<>();
		for (Map.Entry<String, List<NoteEvent>> entry : byInstrument.entrySet()) {
			int slash = entry.getKey().indexOf(47);
			String id = slash < 0 ? entry.getKey() : entry.getKey().substring(slash + 1);
			String label = PreviewInstrument.byId(id).name();
			layers.add(new Layer(slash < 0
					? label
					: label + " " + (Integer.parseInt(entry.getKey().substring(0, slash)) + 1),
				id, false, true, true, List.copyOf(entry.getValue())));
		}
		ComposerProject project = new ComposerProject(name, ComposerProject.DEFAULT_PPQ,
			TEMPO_MICROS_PER_QUARTER, layers, 0, nextId, span,
			ComposerProject.DEFAULT_SPEED_QUARTERS);

		List<String> warnings = new ArrayList<>();
		int versions = trace.versions().size();
		if (versions > 1 && trace.overlapping()) {
			// Both are real performances and both are here, which is the only answer that loses no
			// music -- but they cannot both be playing, so what arrives is two songs on top of each
			// other until one is silenced. Saying which layers belong to which is the whole of what
			// makes that recoverable.
			warnings.add(versions + " ways in that share notes but neither contains the other, so "
				+ "each is here as its own numbered layers -- they are alternatives, not parts, and "
				+ "will sound at once until you hide the ones you do not want");
		} else if (versions > 1) {
			// Where a machine begins is readable; when it begins is not. Ones that share nothing are
			// laid out as if one lever starts them all, which is how a build plays more than one
			// line at once -- but if they are really started apart, the parts are out by however far
			// apart that is, and nothing in the blocks says.
			warnings.add(versions + " separate machines, split into numbered layers and read as "
				+ "though started together");
		}
		if (survey.torches > 0) {
			// A torch is usually there to invert something, and inversion means a note sounds when
			// power is taken away rather than given. Nothing here follows a falling edge, so a
			// torch is read as simply being on, which is right for one used as a plain battery and
			// wrong for one used as a gate.
			warnings.add(survey.torches + " redstone torch"
				+ (survey.torches == 1 ? "" : "es") + " read as always on; anything they invert is "
				+ "read the wrong way round");
		}
		if (trace.looped()) {
			// Read one lap and stopped, because the walk refuses a pulse that arrives no later than
			// one a block has already had -- which is also why it stops at all rather than going
			// round forever. A bassline meant to repeat under the whole song therefore arrives
			// having played once.
			warnings.add("the signal loops back on itself, so anything that repeats -- a bassline "
				+ "under the rest of the song, say -- was read once through rather than repeated");
		}
		if (trace.subsumed() > 0) {
			warnings.add(trace.subsumed() + " other way"
				+ (trace.subsumed() == 1 ? "" : "s") + " in ignored for reaching only part of what "
				+ "another already covers");
		}
		if (!survey.unsupported.isEmpty()) {
			warnings.add("ignored " + String.join(" and ", survey.unsupported)
				+ ", which carry timing this cannot follow");
		}
		return new Reading(project, survey.noteBlocks.size(),
			survey.noteBlocks.size() - trace.played().size(), headNotes,
			(int)(span / TICKS_PER_REDSTONE_TICK), versions, List.copyOf(warnings));
	}

	private static int startingPointCount(Region region, Survey survey) {
		try {
			return startingPoints(region, survey).size();
		} catch (UnreadableException unreadable) {
			return 0;
		}
	}

	/**
	 * @param fromHead whether the sound is one of the mob heads, which the composer has no layer
	 *     for and which therefore comes back as a harp
	 */
	private record Instrument(String id, boolean fromHead) {
	}

	/**
	 * What a note block plays, decided the way the game decides it: by the block underneath.
	 *
	 * <p>Not by the note block's own recorded instrument, which is only refreshed when something
	 * next to it changes and so can be stale in a file that was saved mid-edit. The block below is
	 * what a player would hear.</p>
	 */
	private static Instrument instrumentAt(Region region, BlockPos position) {
		String id = region.at(position.below()).instrument().name().toUpperCase(Locale.ROOT);
		boolean known = PreviewInstrument.VALUES.stream().anyMatch(value -> value.id().equals(id));
		return new Instrument(known ? id : "HARP", !known);
	}

	// ------------------------------------------------------------------ the region itself

	/** Blocks between two corners, with anything outside them reading as air. */
	private static final class Region {
		private final int minX;
		private final int minY;
		private final int minZ;
		private final int maxX;
		private final int maxY;
		private final int maxZ;
		private final BlockLookup blocks;

		Region(BlockPos from, BlockPos to, BlockLookup blocks) {
			this.minX = Math.min(from.getX(), to.getX());
			this.minY = Math.min(from.getY(), to.getY());
			this.minZ = Math.min(from.getZ(), to.getZ());
			this.maxX = Math.max(from.getX(), to.getX());
			this.maxY = Math.max(from.getY(), to.getY());
			this.maxZ = Math.max(from.getZ(), to.getZ());
			this.blocks = blocks;
		}

		boolean contains(BlockPos position) {
			return position.getX() >= minX && position.getX() <= maxX
				&& position.getY() >= minY && position.getY() <= maxY
				&& position.getZ() >= minZ && position.getZ() <= maxZ;
		}

		BlockState at(BlockPos position) {
			if (!contains(position)) {
				// Nothing outside the box exists. A machine half inside the selection reads as one
				// that stops at the edge, which is the honest answer -- and it is what makes the
				// count of notes the signal never got to worth showing.
				return Blocks.AIR.defaultBlockState();
			}
			// Deliberately not cached. A selection is walked once to survey it and then only near
			// the wiring, so a cache would buy little and would hold a block state for every
			// position in a region the player is free to make enormous.
			return blocks.at(position);
		}
	}

	private static boolean isConductor(BlockState state) {
		return !state.isAir() && state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	/** Whether a block stops a dust step passing over or under it. */
	private static boolean isOccluding(BlockState state) {
		return !state.isAir() && state.isSolidRender();
	}

	/** How many blocks a region holds, as a long so an absurd selection cannot overflow. */
	public static long volume(BlockPos from, BlockPos to) {
		long width = Math.abs((long)from.getX() - to.getX()) + 1L;
		long height = Math.abs((long)from.getY() - to.getY()) + 1L;
		long depth = Math.abs((long)from.getZ() - to.getZ()) + 1L;
		return width * height * depth;
	}

	static {
		// Kept honest against the composer: if the default ppq ever changes, a repeater tick stops
		// being a whole number of composer ticks and every song read out of the world is off-grid.
		if (ComposerProject.DEFAULT_PPQ * 100_000 % TICKS_PER_REDSTONE_TICK != 0) {
			throw new IllegalStateException("A repeater tick is no longer a whole number of ticks");
		}
	}
}
