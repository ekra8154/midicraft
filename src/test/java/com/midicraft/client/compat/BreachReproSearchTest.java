package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the smallest spec that breaches, so one can be built by hand and looked at.
 *
 * <p>Every breach in the library is inside a song of a thousand events, which is no use for
 * standing in front of. This walks the spec grammar looking for the same fault in three chords.</p>
 */
class BreachReproSearchTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private record Hit(String command, String fault, int blocks) {
	}

	/**
	 * The walls the command reports are the walls the build was measured against.
	 *
	 * <p>Checked rather than trusted because the plan slides after it is walked, so the number is
	 * not one anybody can work out from where they were standing -- which is the whole reason it is
	 * worth printing. A breaching build is the case that proves it: the breach is blocks past the
	 * far wall, so if the reported wall were wrong the overshoot would not line up with the fault.</p>
	 */
	@Test
	void reportsTheWallsTheBuildWasMeasuredAgainst() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes(DebugChords.parse("24@1 24@1 1@1 19@1 1@1", DebugChords.DEFAULT_GAP)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 28, 3), new SongBuilder.WalkStart(0, 2, -1, false));
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (String command : plan.commands()) {
			int x = Integer.parseInt(command.split(" ")[1]);
			lowest = Math.min(lowest, x);
			highest = Math.max(highest, x);
		}
		System.out.println("WALLS near=" + plan.nearWall() + " far=" + plan.farWall()
			+ " blocks span x=" + lowest + ".." + highest);
		assertEquals(26, plan.farWall() - plan.nearWall(), "twenty-eight wide is twenty-six columns");
		assertEquals(lowest, plan.nearWall(), "the build starts on the near wall");
		int overshoot = 0;
		for (String fault : plan.faults()) {
			if (fault.startsWith("a lane turned -")) {
				overshoot = Math.max(overshoot, -Integer.parseInt(fault.split(" ")[3]));
			}
		}
		System.out.println("WALLS worst breach " + overshoot + " columns, blocks reach "
			+ (highest - plan.farWall()) + " past the far wall");
		assertTrue(overshoot > 0, "this spec is supposed to breach");
		assertTrue(highest > plan.farWall(),
			"a breaching build has blocks past the far wall it reports");
	}

	@Test
	void tracesTheChosenRepro() {
		SongBuilder.TRACE = true;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse("24@1 24@1 1@1 19@1 1@1", DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 28, 3),
				new SongBuilder.WalkStart(0, 2, -1, false));
			for (String fault : plan.faults()) {
				System.out.println("FAULT " + fault);
			}
		} finally {
			SongBuilder.TRACE = false;
		}
	}

	@Test
	void findsTheSmallestSpecThatBreaches() {
		List<Hit> hits = new ArrayList<>();
		// A prefix that fills a lane and turns, so the lane under test starts on a spent wire the
		// way every failing lane in Do The Dance does. A seeded walk always opens on a full fifteen,
		// which is why the short specs all came back clean.
		for (String prefix : List.of("24@1 24@1", "23@1 23@1", "24@1 2@1 24@1", "20@1 20@1 20@1",
				"24@1 24@1 24@1")) {
			for (int lead = 1; lead <= 4 && hits.size() < 120; lead++) {
				for (int big = 19; big <= 26; big++) {
					for (int after = 1; after <= 12; after++) {
						String spec = prefix + " " + lead + "@1 " + big + "@1 " + after + "@1";
						for (int width = 12; width <= 28; width += 4) {
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
									if (!fault.startsWith("a lane turned -")) {
										continue;
									}
									// Only the small overshoots. A chord wider than the whole build
									// breaches by fifteen columns and is not a layout fault at all,
									// it is a build that was never going to fit -- and it drowns out
									// the one column that is.
									int over = -Integer.parseInt(fault.split(" ")[3]);
									if (over > 3) {
										continue;
									}
									hits.add(new Hit("/midicraft paste " + width + " 3 down "
										+ cols + " " + spec, fault, plan.commands().size()));
								}
							}
						}
					}
				}
			}
		}
		hits.sort((a, b) -> Integer.compare(a.blocks(), b.blocks()));
		System.out.println("REPRO found " + hits.size());
		for (Hit hit : hits.subList(0, Math.min(8, hits.size()))) {
			System.out.println("REPRO " + hit.command() + "   [" + hit.blocks() + " blocks]");
			System.out.println("   " + hit.fault());
		}
	}
}
