package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * The sequence of lanes a walk will lay, decided before it lays any of them.
 *
 * <p>Today the shape of a build is implicit: the walk carries a floor and a climb direction, and
 * when a lane ends it works out from those whether the next turn is a staircase or a flat turn --
 * so the serpentine exists nowhere except as the fixed point of that little state machine, and no
 * other shape can exist at all. This object is that state machine taken out and made a value: leg
 * {@code n} of a route is the {@code n}th lane the walk will lay, and the route answers what floor
 * it runs on and which way its vertical zigzag is going. The walk asks; it no longer decides.</p>
 *
 * <p>Pulled out for the interleaved half-tick paste, whose two machines wind through one region and
 * can only be kept apart by fixing both paths before either walk starts. A route the walk merely
 * follows is what makes that possible -- and later, what makes a paste that follows an arbitrary
 * drawn path possible, because by then the walk will not know the difference.</p>
 *
 * <p><b>This first cut carries the sequencing and nothing else.</b> A leg is still wall-to-wall and
 * the walls are still where the walk always put them; what the route owns is only which turn ends
 * each lane. The gate for every step of this migration is identity: a routed walk following the
 * serpentine route must build every song block for block as the unrouted walk builds it, which is
 * why {@link #serpentine} replicates the walk's own transition rule exactly rather than describing
 * the same shape some tidier way.</p>
 */
interface LaneRoute {

	/** The floor leg {@code leg} runs on, counted the way the walk counts floors. */
	int floorOf(int leg);

	/**
	 * The vertical direction leg {@code leg} carries, {@code +1} climbing and {@code -1}
	 * descending.
	 *
	 * <p>The pair {@code (floor, climb)} is deliberately the walk's own state, not a nicer
	 * abstraction of it: every question the walk asks about its turn -- staircase or flat, cost,
	 * which column the wall is -- is asked of these two numbers, and handing back exactly what the
	 * state machine would have held is what makes the routed walk provably the same build.</p>
	 */
	int climbOf(int leg);

	/**
	 * The perpendicular advance of the flat turn that ends leg {@code leg}, in cells -- or nought,
	 * meaning the walk's own lane spacing, which is what every turn in a plain serpentine takes.
	 *
	 * <p>This is the stretched flat turn: the interleaved shape runs long links down its trunk so
	 * the other machine's finger can pass between two of its own, and how long is a fact about the
	 * route, not about the walk. Only a flat turn reads it; a staircase advances no depth and asks
	 * nothing.</p>
	 */
	default int linkOf(int leg) {
		return 0;
	}

	/**
	 * Columns this leg's tip reaches past the machine's base far wall. Nought for a lane that
	 * stops at the wall, which is every lane of a plain serpentine.
	 *
	 * <p>The interleaved layout's reclaimed ground: the only thing of the partner machine that
	 * crosses this machine's depth rows near the partner's trunk is the partner's long
	 * perpendicular runs, and those lie only on the floors that host its flat turns. On every
	 * other floor the columns are empty at this machine's rows, and a lane may run through them --
	 * over and under the neighbour's turns. Both legs of a finger pair share one tip, so both
	 * answer with the same number.</p>
	 */
	default int tipExtension(int leg) {
		return 0;
	}

	/**
	 * Columns this leg's near-wall reach runs past the machine's base near wall -- the mirror of
	 * {@link #tipExtension} for a machine whose trunk is its far wall, so its hairpin tips point
	 * back toward the origin side and the reclaimed ground lies behind it.
	 */
	default int nearExtension(int leg) {
		return 0;
	}

	/**
	 * Whether the flat turn ending this leg must arm tight -- corner on the wall, run down the
	 * wall column -- rather than letting the walk guess.
	 *
	 * <p>In the nested interleave the short links turn on a shortened wall, and a wide corner
	 * there stands its dust one column further out, which is where the partner machine hangs its
	 * notes. Told rather than guessed, because the guess measures the machine's own chords and
	 * cannot see the neighbour.</p>
	 */
	default boolean linkArmsTight(int leg) {
		return false;
	}

	/**
	 * Whether a wait too long for the lane in front of it folds through the turns ahead instead of
	 * running straight past the wall.
	 *
	 * <p>A wait is a repeater chain: every repeater hands out a fresh fifteen, nothing hangs off
	 * it, and dust turns any corner -- so a wait never has to broaden a build. A route that says so
	 * lets the walk arm turns for its delay chains and lets the assembly clamp its lane width to
	 * the widest <em>chord</em> rather than the longest event. Off by default because the identity
	 * gates hold the routed walk to walkV2 block for block, and v2 has no fold: its waits run
	 * straight and its width clamp still counts them.</p>
	 */
	default boolean foldsWaits() {
		return false;
	}

	/**
	 * Whether this machine's slab creeps the other way round -- depth counterclockwise from
	 * forward instead of clockwise.
	 *
	 * <p>The interleaved paste's second machine is the first one MIRRORED, not rotated: its lanes
	 * run the opposite way but its slab must creep the same way, or the two combs part company a
	 * link at a time. A reflection flips handedness, and handedness is exactly one fact: which side
	 * of forward the depth is on.</p>
	 */
	default boolean mirrored() {
		return false;
	}

	/**
	 * The serpentine the walk has always laid, as a route.
	 *
	 * <p>The transition rule is copied from the walk, not paraphrased: a lane whose
	 * {@code floor + climb} is a real floor ends in a staircase and the next leg runs there with the
	 * same climb; one whose {@code floor + climb} steps off the top or bottom ends in a flat turn
	 * and the next leg runs on the same floor with the climb reversed. Seeded with the walk's own
	 * starting state so a debug build started mid-snake follows the same snake.</p>
	 */
	/** The same route with {@link #foldsWaits} switched on. */
	static LaneRoute folding(LaneRoute base) {
		return new LaneRoute() {
			@Override
			public int floorOf(int leg) {
				return base.floorOf(leg);
			}

			@Override
			public int climbOf(int leg) {
				return base.climbOf(leg);
			}

			@Override
			public int linkOf(int leg) {
				return base.linkOf(leg);
			}

			@Override
			public int tipExtension(int leg) {
				return base.tipExtension(leg);
			}

			@Override
			public int nearExtension(int leg) {
				return base.nearExtension(leg);
			}

			@Override
			public boolean linkArmsTight(int leg) {
				return base.linkArmsTight(leg);
			}

			@Override
			public boolean mirrored() {
				return base.mirrored();
			}

			@Override
			public boolean foldsWaits() {
				return true;
			}
		};
	}

	static LaneRoute serpentine(int floors, int startFloor, int startClimb) {
		List<int[]> legs = new ArrayList<>();
		legs.add(new int[] {startFloor, startClimb});
		return new LaneRoute() {
			private int[] leg(int index) {
				while (legs.size() <= index) {
					int[] last = legs.get(legs.size() - 1);
					int above = last[0] + last[1];
					legs.add(above >= 0 && above < floors
						? new int[] {above, last[1]}
						: new int[] {last[0], -last[1]});
				}
				return legs.get(index);
			}

			@Override
			public int floorOf(int leg) {
				return leg(leg)[0];
			}

			@Override
			public int climbOf(int leg) {
				return leg(leg)[1];
			}
		};
	}
}
