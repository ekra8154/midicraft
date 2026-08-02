package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the shortest spec that turns past the wall the way Do The Dance does at tick 668.
 *
 * <p>The shape to hit is a big bus that lands the lane flush on its wall having spent the wire, then
 * a small chord standing on the wall that wants to turn and has not the signal left to cut the
 * staircase. Every other breach shape is noise here, so the search asks for exactly two columns.</p>
 */
class WallTurnReproTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private record Hit(String command, String fault, int blocks) {
	}

	/**
	 * The chosen repro fails the way the song does, not merely with the same words.
	 *
	 * <p>What has to match is the mechanism: the big bus lands the lane flush on its wall having
	 * spent the wire down to four, the small chord standing on the wall wants the turn and cannot
	 * afford the five cells of staircase, and the chord after it takes the turn two columns out.</p>
	 */
	@Test
	void tracesTheChosenRepro() {
		SongBuilder.TRACE = true;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse("21@1 1@1 9@1", DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 12, 3),
				new SongBuilder.WalkStart(2, 2, -1, false));
			System.out.println("REPRO walls near=" + plan.nearWall() + " far=" + plan.farWall());
			for (String fault : plan.faults()) {
				System.out.println("REPRO FAULT " + fault);
			}
			// Word for word what Do The Dance says at tick 668 and at tick 1324, bar the tick. When
			// this stops holding the repro has been fixed, and the song wants checking with it.
			org.junit.jupiter.api.Assertions.assertEquals(
				List.of("a lane turned -2 columns short of its wall at tick 3, where a chord of 9"
					+ " would not fit: 15 blocks of wire and 0 spare ticks to fill them with, and"
					+ " 5 blocks of bus plus 5 for the turn is too much to cut across it"),
				plan.faults(), "the repro is supposed to breach, and by two columns");
		} finally {
			SongBuilder.TRACE = false;
		}
	}

	@Test
	void findsTheWallTurnRepro() {
		List<Hit> hits = new ArrayList<>();
		for (String prefix : List.of("", "24@1 ", "24@1 24@1 ")) {
			for (int big = 18; big <= 24; big++) {
				for (int small = 1; small <= 4; small++) {
					for (int after = 5; after <= 12; after++) {
						String spec = prefix + big + "@1 " + small + "@1 " + after + "@1";
						for (int width = 12; width <= 24; width += 4) {
							for (int cols = 6; cols <= width - 2; cols += 2) {
								SongBuilder.PastePlan plan;
								try {
									plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
										DebugChords.notes(DebugChords.parse(spec,
											DebugChords.DEFAULT_GAP)),
										SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
										new SongBuilder.BuildLimits(4, width, 3),
										new SongBuilder.WalkStart(Math.max(0, width - 2 - cols), 2,
											-1, false));
								} catch (RuntimeException refused) {
									continue;
								}
								for (String fault : plan.faults()) {
									// Do The Dance's own numbers: the chord that ends up taking the
									// turn is nine or ten notes, so five cells of bus, and it takes
									// it two columns out. Anything else is a different fault.
									if (!fault.startsWith("a lane turned -2 columns")
											|| !fault.contains("5 blocks of bus plus 5 ")) {
										continue;
									}
									hits.add(new Hit("/fastnoteblockpaste " + width + " 3 down "
										+ cols + " " + spec, fault, plan.commands().size()));
								}
							}
						}
					}
				}
			}
		}
		hits.sort((a, b) -> Integer.compare(a.blocks(), b.blocks()));
		System.out.println("WALLTURN found " + hits.size());
		for (Hit hit : hits.subList(0, Math.min(10, hits.size()))) {
			System.out.println("WALLTURN " + hit.command() + "   [" + hit.blocks() + " blocks]");
			System.out.println("   " + hit.fault());
		}
	}
}
