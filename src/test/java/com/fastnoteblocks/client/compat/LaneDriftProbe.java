package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where each lane's pulse is, moment by moment, and how far apart the two get.
 *
 * <p>The two lanes play at once but advance at their own rates: a lane spends columns on the chords
 * it happens to carry, and a song whose even ticks are busier than its odd ones runs one pulse
 * ahead of the other for the whole build. Nothing in the timing notices -- each lane is exactly on
 * its own beat -- but the sound comes out of wherever the pulse is standing, and a note block is
 * only audible for 48 blocks.</p>
 *
 * <p>So a drift wider than earshot means the listener cannot be in two places at once: stand by one
 * lane and half the song is missing. That is a property no read-back can see, because a read-back
 * asks when a note fires and this is about where.</p>
 */
@Tag("sweep")
class LaneDriftProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Pattern SETBLOCK = Pattern.compile(
		"setblock (-?\\d+) (-?\\d+) (-?\\d+) (\\S+) replace");

	/** One event's pulse: the game tick it sounds on and the column it sounds from. */
	private record Pulse(int gameTick, int x) {
	}

	@Test
	void measuresHowFarApartTheTwoPulsesGet() throws Exception {
		for (String name : new String[] {
				"deltarune-ch-4-guardian", "illit-do-the-dance", "a-dark-zone-2-lanes-maybe"}) {
			List<SongBuilder.EventNote> song;
			try {
				song = BreachView.song(name);
			} catch (java.io.IOException missing) {
				continue;
			}
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
				SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(4, 44, 3));

			int minZ = plan.commands().stream().map(SETBLOCK::matcher).filter(Matcher::matches)
				.mapToInt(block -> Integer.parseInt(block.group(3))).min().orElse(0);
			int maxZ = plan.commands().stream().map(SETBLOCK::matcher).filter(Matcher::matches)
				.mapToInt(block -> Integer.parseInt(block.group(3))).max().orElse(0);
			double split = (minZ + maxZ) / 2.0;

			List<Pulse> right = pulses(plan, song, 0, split, true);
			List<Pulse> left = pulses(plan, song, 1, split, false);

			// Walked together on one clock: at every moment either lane last spoke, ask where the
			// other one was standing when it last spoke too.
			int worst = 0;
			int worstTick = 0;
			long over20 = 0;
			long over48 = 0;
			long samples = 0;
			int rightAt = 0;
			int leftAt = 0;
			List<Integer> ticks = new ArrayList<>();
			right.forEach(pulse -> ticks.add(pulse.gameTick()));
			left.forEach(pulse -> ticks.add(pulse.gameTick()));
			ticks.sort(Comparator.naturalOrder());
			for (int tick : ticks) {
				while (rightAt + 1 < right.size() && right.get(rightAt + 1).gameTick() <= tick) {
					rightAt++;
				}
				while (leftAt + 1 < left.size() && left.get(leftAt + 1).gameTick() <= tick) {
					leftAt++;
				}
				if (right.isEmpty() || left.isEmpty()) {
					continue;
				}
				int apart = Math.abs(right.get(rightAt).x() - left.get(leftAt).x());
				if (apart > worst) {
					worst = apart;
					worstTick = tick;
				}
				if (apart > 20) {
					over20++;
				}
				if (apart > 48) {
					over48++;
				}
				samples++;
			}

			System.out.println();
			System.out.println("==== " + name + " ====");
			System.out.println("  " + song.size() + " notes, right lane " + right.size()
				+ " events, left lane " + left.size() + " events");
			System.out.println("  build spans " + plan.spanX() + " blocks");
			System.out.println(String.format(
				"  pulses drift apart by at most %d blocks (at game tick %d)", worst, worstTick));
			System.out.println(String.format(
				"  over 20 blocks apart for %.1f%% of the song, over 48 (earshot) for %.1f%%",
				100.0 * over20 / Math.max(1, samples), 100.0 * over48 / Math.max(1, samples)));
		}
	}

	/**
	 * Each event's column, read off the note blocks rather than recomputed.
	 *
	 * <p>Modules are laid in order along the lane and never overlap, so the note blocks sorted by
	 * column arrive in the order their events were built -- and each event takes as many of them as
	 * its chord has notes. That maps blocks back to moments without repeating any of the arithmetic
	 * that placed them, which is the point: a second implementation of the walk would only prove the
	 * two agree with each other.</p>
	 */
	private static List<Pulse> pulses(SongBuilder.PastePlan plan, List<SongBuilder.EventNote> song,
			int parity, double split, boolean rightLane) {
		List<int[]> noteBlocks = new ArrayList<>();
		for (String command : plan.commands()) {
			Matcher block = SETBLOCK.matcher(command);
			if (!block.matches() || !block.group(4).startsWith("minecraft:note_block")) {
				continue;
			}
			int z = Integer.parseInt(block.group(3));
			if (z > split != rightLane) {
				continue;
			}
			noteBlocks.add(new int[] {Integer.parseInt(block.group(1)), z});
		}
		noteBlocks.sort(Comparator.<int[]>comparingInt(at -> at[0]).thenComparingInt(at -> at[1]));

		List<int[]> chords = new ArrayList<>();
		for (int index = 0; index < song.size();) {
			int time = song.get(index).time();
			int size = 0;
			while (index < song.size() && song.get(index).time() == time) {
				size++;
				index++;
			}
			if (Math.floorMod(time, 2) == parity) {
				chords.add(new int[] {time, size});
			}
		}

		List<Pulse> pulses = new ArrayList<>();
		int at = 0;
		for (int[] chord : chords) {
			if (at >= noteBlocks.size()) {
				break;
			}
			pulses.add(new Pulse(chord[0], noteBlocks.get(at)[0]));
			at += chord[1];
		}
		return pulses;
	}
}
