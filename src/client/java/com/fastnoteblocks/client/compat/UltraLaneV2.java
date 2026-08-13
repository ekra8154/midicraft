package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The ultra lane, decided again from scratch.
 *
 * <h2>Why there is a second one</h2>
 *
 * <p>The first grew a rule at a time, each one fixing a breach ekran had gone and stood in front of.
 * That works and it is how nearly everything here was learned, but the rules were added beside one
 * another rather than into one another, and by the end there were sixty-six flags of which
 * forty-seven were live. Several answer the same question from different call sites and disagree:
 * nine of them are variations of <em>is the space behind this module free</em>, and four exist
 * because three places priced the same staircase differently.</p>
 *
 * <p>The largest single piece of it is obsolete rather than wrong. The pad layer -- {@code planLane},
 * {@code strandsNext}, the veto, the booking search, six prepad flags -- is the answer to a chord too
 * big to cut, from before stacked buses existed. Stacked buses raised the cut ceiling to 27 notes
 * descending and 29 climbing, and nobody went back and removed the thing they replaced.</p>
 *
 * <h2>The one arithmetic fact this is built on</h2>
 *
 * <p>A cut costs a transition cell, the two halves of the chord, and the staircase, all off the
 * repeater every chord opens with:</p>
 *
 * <pre>
 *   1 + ceil(near / 2) + ceil(far / 2) + splitCells  &lt;=  15
 * </pre>
 *
 * <p>With a stacked head that reaches 27 descending and 29 climbing. <b>Chords are capped at 25</b>,
 * and the cap is the whole design rather than a limitation: a 25 is a head of seven and a tail of
 * eighteen, {@code 1 + 9 + 4 = 14}, which leaves exactly one cell spare -- and a parity pad costs at
 * most one. So a chord of 25 or less can be cut <b>anywhere along its length</b>, in either
 * direction, with a parity pad, always. A 27 comes to exactly fifteen and has no room for one.</p>
 *
 * <p>Which is what makes lanes uniform. Every lane runs to its wall and closes by cutting whichever
 * chord reaches it; the near half is sized to land flush, so the lane ends exactly on the wall the
 * way it always did. Nothing is padded out to get there, because nothing needs to be.</p>
 *
 * <h2>What v2 does differently</h2>
 *
 * <ol>
 *   <li><b>No planner.</b> No booking, no {@code strandsNext}, no veto. A lane cannot fail to close,
 *       so there is nothing to plan for it. Parity pads stay -- they move a module a column so its
 *       slots land right, which cutting does not make unnecessary.</li>
 *   <li><b>One occupancy answer.</b> Every "is that space free" question is answered by building the
 *       shape in a trial and rolling it back if it collides, never by a predicate about position.
 *       {@link SongBuilder.PlacementPlan#beginTrial} already does this for stacked shapes; here it is
 *       the only mechanism. The nine flags go.
 *       <p>This is not tidiness. The small-chord turn ban was measured on this branch: over 240
 *       builds a small chord that fell back and had slots available was refused the denser shape by
 *       the turn clause 1,733 times out of 1,733, spending a column of lane each time -- and the ban
 *       is standing in for an occupancy check nobody wrote. It cannot simply be deleted, because
 *       doing that collides; it has to be replaced by asking.</li>
 *   <li><b>One turn price.</b> Computed once, read by both the decision and the build, so they cannot
 *       disagree. That disagreement was the whole raised-ascent regression -- {@code canTurn} said
 *       three and {@code reachesWall} said five.</li>
 *   <li><b>One cut rule.</b> The cut is offered on the chord that <em>reaches</em> the wall, not the
 *       one that overshoots it. A chord landing flush leaves no near half, nothing to cut, and a lane
 *       funding its own turn out of whatever a long bus left it -- which is the exact hole the pad
 *       layer was built around. Measured on v1 in isolation this alone took Guardian from 86 breach
 *       blocks to 68.</li>
 * </ol>
 *
 * <h2>What is kept, and kept as it stands</h2>
 *
 * <p>Everything that knows about blocks. {@code addSplitBusDescent}, {@code addGlassClimb},
 * {@code addStackedSplitModule}, {@code onTheFreeSlots}, the parity pad, relocation. These were built
 * by hand and verified against real worlds, and the file's own warning is that every descent geometry
 * <em>derived</em> rather than built has been wrong at least once. The rewrite is of the decision
 * layer above them, not of them.</p>
 *
 * <p>Kept from ekran's list and worth naming because they are objectively better and were only ever
 * contentious through the pad layer: the raised pad into a climb, the universal four-cell descent,
 * head-only cuts, shedding, parity padding, relocation.</p>
 *
 * <h2>State</h2>
 *
 * <p><b>Scaffolding only.</b> The mode exists, is selectable, and takes the width and floor controls;
 * the walk it runs is still the shared one under v2's two flags. The decision layer described above
 * is the next thing to write, and it is written here rather than in {@link SongBuilder} so that it
 * can be written without the other forty-seven flags in scope.</p>
 */
final class UltraLaneV2 {
	private UltraLaneV2() {
	}

	/**
	 * The largest chord this builder will cut, and so the largest it will build.
	 *
	 * <p>Twenty-five, because that is the last size with a cell to spare for parity. Songs holding
	 * bigger chords are not refused -- they are built by the layout above, which is what the two modes
	 * standing side by side is for.</p>
	 */
	static final int MAX_CHORD = 25;

	/** Whether a song is one this builder claims it can do properly. */
	static boolean fits(List<SongBuilder.EventNote> notes) {
		return biggestChord(notes) <= MAX_CHORD;
	}

	static int biggestChord(List<SongBuilder.EventNote> notes) {
		java.util.Map<Integer, Integer> byTime = new java.util.HashMap<>();
		int biggest = 0;
		for (SongBuilder.EventNote note : notes) {
			biggest = Math.max(biggest, byTime.merge(note.time(), 1, Integer::sum));
		}
		return biggest;
	}

	/**
	 * A song built the v2 way.
	 *
	 * <p>Its own walk, {@code SongBuilder.walkV2}, which began as a copy of the first layout's and
	 * had the decision layer cut out of it: no booking search, no veto, no lookahead pair. Copied and
	 * cut rather than written afresh on purpose -- deleting a rule can be checked and re-deriving one
	 * cannot, and every descent geometry in this repository that was derived rather than built by
	 * hand has been wrong at least once.</p>
	 */
	static SongBuilder.PastePlan plan(BlockPos origin, Direction forward,
			List<SongBuilder.EventNote> notes, SongBuilder.BuildLimits limits,
			SongBuilder.WalkStart start) {
		// Where the snake is put down, matched to the first layout deliberately. A seeded walk is a
		// debug build reproducing a fault at a stated floor and means it; everything else starts at
		// the top when asked to. Passing the caller's start through raw here instead sent v2 up from
		// floor nought while v1 came down from the top, so the two were not building the same snake
		// and every number compared between them was comparing two different shapes.
		SongBuilder.WalkStart head = start == SongBuilder.WalkStart.HEAD && limits.startTop()
			? new SongBuilder.WalkStart(0, limits.laneFloors() - 1, -1)
			: start;
		return SongBuilder.createV2PastePlan(origin, forward, notes, limits.laneWidth(),
			limits.laneFloors(), head);
	}
}
