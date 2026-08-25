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
	 * The serpentine the walk has always laid, as a route.
	 *
	 * <p>The transition rule is copied from the walk, not paraphrased: a lane whose
	 * {@code floor + climb} is a real floor ends in a staircase and the next leg runs there with the
	 * same climb; one whose {@code floor + climb} steps off the top or bottom ends in a flat turn
	 * and the next leg runs on the same floor with the climb reversed. Seeded with the walk's own
	 * starting state so a debug build started mid-snake follows the same snake.</p>
	 */
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
