package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where the sixteenth block of wire stands when a chord that fits is cut.
 *
 * <p>{@link SongBuilder#CUTS_A_CHORD_THAT_FITS} takes Guardian's breaches from 229 blocks to 86 and
 * silences 3,606 note blocks on {@link BigSplitTest}'s song at {@code f3 w20}. {@code runCells}
 * cannot see it -- the sum it makes is transition plus pairs plus staircase, and that comes to
 * fourteen or fifteen for every chord this builds. So the extra wire is wire the cut does not know
 * it laid, and the only way to find out which is to count the run off the commands and go and look
 * at the block it ends on.</p>
 *
 * <p>The commands are in build order, so the stretch between one repeater and the next is the run
 * between them -- the cheapest useful measurement in this codebase. The crossed wire state is
 * skipped: only the stacked module lays it, it is redstone like any other, and counting it makes
 * every run through a head measure one longer than it is.</p>
 */
@Tag("sweep")
class FitsCutDeadRunTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The same song {@link BigSplitTest} builds: nothing but chords too big to cut as a plain bus. */
	private static List<SongBuilder.EventNote> hugeChordSong(int low, int high, long seed) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:packed_ice"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 120; event++) {
			time += 2 + random.nextInt(6);
			int chord = low + random.nextInt(high - low + 1);
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}

	@Test
	void findsTheRunThatIsTooLong() throws Exception {
		List<SongBuilder.EventNote> notes = hugeChordSong(23, 27, 11L);
		for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 20, 3));
			int dust = 0;
			int longest = 0;
			String longestAt = "";
			String startedAt = "";
			String runStart = "";
			for (String command : plan.commands()) {
				String[] word = command.split(" ");
				String block = word[4];
				if (block.startsWith("minecraft:redstone_wire[")) {
					continue;
				}
				if (block.startsWith("minecraft:redstone_wire")) {
					if (dust == 0) {
						runStart = word[1] + " " + word[2] + " " + word[3];
					}
					dust++;
					if (dust > longest) {
						longest = dust;
						longestAt = word[1] + " " + word[2] + " " + word[3];
						startedAt = runStart;
					}
				} else if (block.startsWith("minecraft:repeater")) {
					dust = 0;
				}
			}
			System.out.println();
			System.out.println("FITSCUT cuts=" + cuts + "  longestRun=" + longest
				+ "  from tp " + startedAt + "  to tp " + longestAt
				+ "  breaches=" + plan.breaches()
				+ "  lanesOutsideTheWalls=" + BreachView.overruns(plan).size());
			if (longest > 15 && !longestAt.isEmpty()) {
				String[] end = longestAt.split(" ");
				BreachView.Overrun where = new BreachView.Overrun(Integer.parseInt(end[1]),
					Integer.parseInt(end[2]), 1, Integer.parseInt(end[0]), false);
				System.out.println(BreachView.draw(plan, where, 2, 1, 5));
			}
		}
		SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
	}
}
