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
		// Songs at the speed they are saved at, and the same songs doubled. A song already on the
		// repeater grid puts every event on an even game tick, so its second lane is empty and it
		// cannot drift at all -- doubling is what makes it a two-lane song and what makes the
		// question mean anything. Which is also why these are the repro: a drift needs a song whose
		// two halves carry unequal weight, and it needs the song to have two halves in the first.
		for (Object[] subject : new Object[][] {
				{"a-dark-zone-2-lanes-maybe", 1}, {"deltarune-ch-4-guardian", 2},
				{"illit-do-the-dance", 2}, {"michael-jackson-thriller", 2}}) {
			String name = (String)subject[0];
			int speedFactor = (Integer)subject[1];
			List<SongBuilder.EventNote> song;
			try {
				com.fastnoteblocks.client.composer.ComposerProject project = BreachView.project(name);
				if (speedFactor != 1) {
					project = project.withSpeedQuarters(
						Math.max(1, project.speedQuarters()) * speedFactor);
				}
				song = SongBuilder.notesFor(SongBuilder.PasteMode.HALF_TICK_LANE,
					project.toSequenceTracks(java.util.Set.of(), true), project, true);
			} catch (java.io.IOException missing) {
				continue;
			}
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
				SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(4, 44, 3));

			double split = laneSplit(plan);

			List<Pulse> right = pulses(plan, song, 0, split, true);
			List<Pulse> left = pulses(plan, song, 1, split, false);

			if (right.isEmpty() || left.isEmpty()) {
				continue;
			}
			System.out.println();
			System.out.println("==== " + name + (speedFactor == 1 ? "" : " at " + speedFactor + "x") + " ====");
			System.out.println("  " + song.size() + " notes, right lane " + right.size()
				+ " events, left lane " + left.size() + " events");
			System.out.println("  build spans " + plan.spanX() + " blocks");
			System.out.println("  " + apart(right, left).summary());
		}
	}

	/**
	 * Every song there is, at its own speed and doubled, against the tolerance padding aims at.
	 *
	 * <p>The named songs above are a repro; this is the question. A tolerance of sixteen is where
	 * padding starts rather than where drift stops -- a lane closes a gap one module at a time and
	 * only with what its run has spare -- so what matters is which songs it fails to hold, and by
	 * how much. Anything past 48 is out of earshot and is the real failure; past 16 is the tolerance
	 * being missed, which is worth knowing before it becomes the other thing.</p>
	 */
	@Test
	void sweepsEverySongForDriftPastTheTolerance() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs");
		List<java.nio.file.Path> files = new ArrayList<>();
		try (var listing = java.nio.file.Files.list(songs)) {
			listing.filter(file -> file.toString().endsWith(".json")).sorted().forEach(files::add);
		}
		System.out.println();
		System.out.println("==== drift across the library, half-tick lane ====");
		System.out.println(String.format("  %-42s %-7s %-9s %-8s %s",
			"", "speed", "lanes", "worst", "out of earshot"));
		int twoLane = 0;
		int pastTolerance = 0;
		int pastEarshot = 0;
		int worstEver = 0;
		int twoLaneAtOwnSpeed = 0;
		int pastToleranceAtOwnSpeed = 0;
		int pastEarshotAtOwnSpeed = 0;
		int worstAtOwnSpeed = 0;
		for (java.nio.file.Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			for (int speedFactor : new int[] {1, 2}) {
				com.fastnoteblocks.client.composer.ComposerProject project = BreachView.project(name);
				if (speedFactor != 1) {
					project = project.withSpeedQuarters(
						Math.max(1, project.speedQuarters()) * speedFactor);
				}
				List<SongBuilder.EventNote> song = SongBuilder.notesFor(
					SongBuilder.PasteMode.HALF_TICK_LANE,
					project.toSequenceTracks(java.util.Set.of(), true), project, true);
				if (song.isEmpty()) {
					continue;
				}
				long odd = song.stream().filter(note -> Math.floorMod(note.time(), 2) == 1).count();
				// A song entirely on one parity builds one lane and has nothing to drift from.
				if (odd == 0 || odd == song.size()) {
					continue;
				}
				twoLane++;
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
						SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(4, 44, 3));
				} catch (RuntimeException refused) {
					System.out.println(String.format("  %-42s %-7s REFUSED %s",
						name.length() > 41 ? name.substring(0, 41) : name,
						speedFactor + "x", refused.getMessage()));
					continue;
				}
				double split = laneSplit(plan);
				List<Pulse> right = pulses(plan, song, 0, split, true);
				List<Pulse> left = pulses(plan, song, 1, split, false);
				if (right.isEmpty() || left.isEmpty()) {
					continue;
				}
				Apart gap = apart(right, left);
				int worst = gap.worst();
				long beyond = gap.beyond();
				long samples = gap.pairs();
				worstEver = Math.max(worstEver, worst);
				if (worst > 48) {
					pastEarshot++;
				}
				if (worst > 16) {
					pastTolerance++;
				}
				// Counted apart, because they are different claims. A song at the speed it is saved
				// at is one that would actually be pasted; the same song doubled is a stress case made
				// up. Reporting them together said "49 of 51 drift" about a library whose songs
				// mostly do not have two lanes at all, which is a true sentence about the wrong set.
				if (speedFactor == 1) {
					twoLaneAtOwnSpeed++;
					worstAtOwnSpeed = Math.max(worstAtOwnSpeed, worst);
					pastToleranceAtOwnSpeed += worst > 16 ? 1 : 0;
					pastEarshotAtOwnSpeed += worst > 48 ? 1 : 0;
				}
				// Every song that really has two lanes gets a row whatever its drift, so the ones
				// that are fine are visible too. Doubled ones appear only when they misbehave.
				if (speedFactor == 1 || worst > 16) {
					System.out.println(String.format("  %-42s %-7s %-9s %-8s %.1f%%",
						name.length() > 41 ? name.substring(0, 41) : name,
						speedFactor + "x",
						right.size() + "/" + left.size(),
						worst + " blk",
						100.0 * beyond / Math.max(1, samples)));
				}
			}
		}
		System.out.println();
		System.out.println("  AT THEIR OWN SPEED -- songs that really do build two lanes:");
		System.out.println("    " + twoLaneAtOwnSpeed + " of " + files.size() + " songs, "
			+ pastToleranceAtOwnSpeed + " past the 16-block tolerance, "
			+ pastEarshotAtOwnSpeed + " past 48 (earshot); worst " + worstAtOwnSpeed + " blocks");
		System.out.println("  DOUBLED -- a stress case, not a build anyone would paste:");
		System.out.println("    " + (twoLane - twoLaneAtOwnSpeed) + " two-lane builds, "
			+ (pastTolerance - pastToleranceAtOwnSpeed) + " past the tolerance, "
			+ (pastEarshot - pastEarshotAtOwnSpeed) + " past earshot; worst overall "
			+ worstEver + " blocks");
	}

	/**
	 * What holding the lanes level at every event costs, against holding them only within earshot.
	 *
	 * <p>Both arms in the same run, because the library grows between sessions and a number
	 * remembered from an hour ago is not comparable to one taken now. The question is a trade with
	 * two sides and both have to be printed: {@code spanX} is what the build costs, and the worst
	 * gap is what it buys. A shorter build that puts the two pulses out of earshot is not an
	 * improvement, it is the fault this padding exists to prevent.</p>
	 */
	@Test
	void weighsLockstepLanesAgainstPaddingOnlyWhenBehind() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "fast-noteblocks", "songs");
		List<java.nio.file.Path> files = new ArrayList<>();
		try (var listing = java.nio.file.Files.list(songs)) {
			listing.filter(file -> file.toString().endsWith(".json")).sorted().forEach(files::add);
		}
		System.out.println();
		System.out.println("==== lockstep lanes vs padding only when behind ====");
		System.out.println(String.format("  %-38s %-5s %-11s %-11s %-8s %-13s %-13s %s",
			"", "speed", "lockstep", "when behind", "shorter", "worst lk / bhd",
			"mean lk / bhd", "% of pairs past 16"));
		long lockstepSpan = 0;
		long behindSpan = 0;
		int worstLockstep = 0;
		int worstBehind = 0;
		int pastEarshotBehind = 0;
		int builds = 0;
		long pastTolerance = 0;
		long pairs = 0;
		boolean restore = SongBuilder.MIRRORS_ONLY_WHEN_BEHIND;
		try {
			for (java.nio.file.Path file : files) {
				String name = file.getFileName().toString().replace(".json", "");
				for (int speedFactor : new int[] {1, 2}) {
					com.fastnoteblocks.client.composer.ComposerProject project = BreachView.project(name);
					if (speedFactor != 1) {
						project = project.withSpeedQuarters(
							Math.max(1, project.speedQuarters()) * speedFactor);
					}
					List<SongBuilder.EventNote> song = SongBuilder.notesFor(
						SongBuilder.PasteMode.HALF_TICK_LANE,
						project.toSequenceTracks(java.util.Set.of(), true), project, true);
					if (song.isEmpty()) {
						continue;
					}
					long odd = song.stream().filter(note -> Math.floorMod(note.time(), 2) == 1).count();
					if (odd == 0 || odd == song.size()) {
						continue;
					}
					SongBuilder.MIRRORS_ONLY_WHEN_BEHIND = false;
					Arm lockstep = arm(song);
					SongBuilder.MIRRORS_ONLY_WHEN_BEHIND = true;
					Arm behind = arm(song);
					if (lockstep == null || behind == null) {
						continue;
					}
					builds++;
					lockstepSpan += lockstep.spanX();
					behindSpan += behind.spanX();
					worstLockstep = Math.max(worstLockstep, lockstep.worst());
					worstBehind = Math.max(worstBehind, behind.worst());
					pastEarshotBehind += behind.worst() > 48 ? 1 : 0;
					pastTolerance += behind.pastTolerance();
					pairs += behind.pairs();
					System.out.println(String.format(
						"  %-38s %-5s %-11s %-11s %-8s %-13s %-13s %s",
						name.length() > 37 ? name.substring(0, 37) : name,
						speedFactor + "x",
						lockstep.spanX() + " blk", behind.spanX() + " blk",
						String.format("%.1f%%",
							100.0 * (lockstep.spanX() - behind.spanX())
								/ Math.max(1, lockstep.spanX())),
						lockstep.worst() + " / " + behind.worst(),
						String.format("%.1f / %.1f", lockstep.mean(), behind.mean()),
						String.format("%.2f%%", 100.0 * behind.pastTolerance()
							/ Math.max(1, behind.pairs()))));
				}
			}
		} finally {
			SongBuilder.MIRRORS_ONLY_WHEN_BEHIND = restore;
		}
		System.out.println();
		System.out.println("  " + builds + " two-lane builds");
		System.out.println("  total length  lockstep " + lockstepSpan + " -> when behind "
			+ behindSpan + String.format("  (%.1f%% shorter)",
				100.0 * (lockstepSpan - behindSpan) / Math.max(1, lockstepSpan)));
		System.out.println("  worst gap between simultaneous notes  lockstep " + worstLockstep
			+ " -> when behind " + worstBehind + " blocks (earshot is 48; "
			+ pastEarshotBehind + " builds past it)");
		System.out.println("  when behind: " + pastTolerance + " of " + pairs
			+ String.format(" simultaneous pairs (%.3f%%) sit further apart than the %d-block "
				+ "tolerance", 100.0 * pastTolerance / Math.max(1, pairs),
				SongBuilder.HALF_TICK_LANE_TOLERANCE));
	}

	/** One build under whichever rule is set, and the numbers the trade is made of. */
	private record Arm(int spanX, int worst, int mirrored, long pairs, long pastTolerance,
			long total) {
		double mean() {
			return total / (double)Math.max(1, pairs);
		}
	}

	private static Arm arm(List<SongBuilder.EventNote> song) {
		SongBuilder.PastePlan plan;
		try {
			plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), song,
				SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(4, 44, 3));
		} catch (RuntimeException refused) {
			return null;
		}
		double split = laneSplit(plan);
		List<Pulse> right = pulses(plan, song, 0, split, true);
		List<Pulse> left = pulses(plan, song, 1, split, false);
		if (right.isEmpty() || left.isEmpty()) {
			return null;
		}
		Apart gap = apart(right, left);
		return new Arm(plan.spanX(), gap.worst(),
			plan.padding().getOrDefault("halfTickMirror", 0), gap.pairs(), gap.pastTolerance(),
			gap.total());
	}

	/**
	 * How far apart two lanes get, and how often that is further than a note can be heard.
	 *
	 * @param pastTolerance pairs further apart than the tolerance padding aims at, which is the
	 *     number that says whether the rule is being held rather than merely aimed at
	 * @param total the sum of every gap, for a mean -- a worst case is one pair out of thousands and
	 *     says nothing about where the two pulses usually stand
	 */
	private record Apart(int worst, int worstTick, long beyond, long pairs, long pastTolerance,
			long total) {
		String summary() {
			return String.format("%d blocks apart at worst (game tick %d), past earshot for %.2f%% "
				+ "of %d simultaneous pairs", worst, worstTick, 100.0 * beyond / Math.max(1, pairs),
				pairs);
		}
	}

	/**
	 * The gap between notes that sound at the same moment, which is the whole of the question.
	 *
	 * <p>Not where each lane <em>last</em> spoke, which is what this measured before and what it can
	 * no longer mean. A mirroring lane lays wire through its silences, so while it has nothing to
	 * play its signal is running down that wire alongside its partner's and making no sound at all.
	 * Its last note block is simply where it last made a noise, which may be thousands of blocks
	 * back and says nothing about whether a listener is missing anything -- there is nothing there
	 * to miss. Aria Math's odd lane speaks 22 times in 1,715 events, and measured the old way it
	 * read 1,000 blocks adrift while every note it played landed within five blocks of its
	 * partner's.</p>
	 *
	 * <p>So each event is matched with the nearest event in time on the other lane, and the pair is
	 * only counted when the two are within two game ticks of each other -- the tightest reading of
	 * "at once", since the lanes hold opposite parities and one tick is as close as they can be. If
	 * the other lane has nothing near in time, there is no listener problem to have.</p>
	 */
	private static Apart apart(List<Pulse> right, List<Pulse> left) {
		int worst = 0;
		int worstTick = 0;
		long beyond = 0;
		long pairs = 0;
		long pastTolerance = 0;
		long total = 0;
		int at = 0;
		for (Pulse pulse : right) {
			while (at + 1 < left.size()
					&& Math.abs(left.get(at + 1).gameTick() - pulse.gameTick())
						<= Math.abs(left.get(at).gameTick() - pulse.gameTick())) {
				at++;
			}
			if (Math.abs(left.get(at).gameTick() - pulse.gameTick()) > 2) {
				continue;
			}
			int gap = Math.abs(left.get(at).x() - pulse.x());
			if (gap > worst) {
				worst = gap;
				worstTick = pulse.gameTick();
			}
			if (gap > 48) {
				beyond++;
			}
			if (gap > SongBuilder.HALF_TICK_LANE_TOLERANCE) {
				pastTolerance++;
			}
			total += gap;
			pairs++;
		}
		return new Apart(worst, worstTick, beyond, pairs, pastTolerance, total);
	}

	/**
	 * The line between the two lanes, taken from the geometry rather than the middle.
	 *
	 * <p>The even lane sits at higher z and is three columns wide wherever one of its chords has
	 * three notes; the odd lane is below it and may be narrower. Splitting the z range down the
	 * middle assumes both are three wide, and Aria Math's odd lane is a single column -- its forty
	 * notes are all lone ones. That put a third of the <em>even</em> lane on the odd side and had
	 * this probe comparing the even lane against itself, reporting 6,171 blocks of drift for a lane
	 * that does not drift: it simply stops, forty notes in.</p>
	 */
	private static double laneSplit(SongBuilder.PastePlan plan) {
		return plan.commands().stream().map(SETBLOCK::matcher).filter(Matcher::matches)
			.filter(block -> block.group(4).startsWith("minecraft:note_block"))
			.mapToInt(block -> Integer.parseInt(block.group(3))).max().orElse(0) - 2.5;
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
