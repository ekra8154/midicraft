package com.fastnoteblocks.client.composer;

import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything about a composition that decides whether Minecraft can build it.
 *
 * <p>One place, because two places would drift. The composer draws its warnings from this and the
 * songs screen prints its verdict from the same instance, so a song can never be ready in one and
 * broken in the other.</p>
 */
public record SongAnalysis(
	int totalNotes,
	int outOfRange,
	Map<Long, Integer> chordCounts,
	int peakChord,
	long overloadedTicks,
	long maximumNoteDuration,
	Set<Long> offGrid,
	Set<Long> crowded,
	Map<Long, Double> gaps,
	long endTick,
	double secondsLong,
	int duplicateNotes
) {
	/** Two note blocks hang off each of redstone's 15 reachable bus blocks. */
	public static final int MAX_SIMULTANEOUS_NOTES = 30;

	/**
	 * @param dedupeIdentical judge the song the way the build will place it, with a sound that two
	 *     layers play at the same instant counted once. Duplicates are otherwise counted against
	 *     the thirty a tick can carry, which can call a chord unbuildable that would have fitted.
	 */
	public static SongAnalysis of(ComposerProject project, boolean dedupeIdentical) {
		Map<Long, Integer> counts = new HashMap<>();
		Set<ComposerProject.NoteSound> heard = dedupeIdentical ? new java.util.HashSet<>() : null;
		int outOfRange = 0;
		int totalNotes = 0;
		int duplicateNotes = 0;
		long maximumNoteDuration = 1L;
		for (Layer layer : project.layers()) {
			// Only included layers are judged. A layer left out of the sequence cannot stop a build
			// it is not part of, and importing a song to keep one line of it should not leave the
			// verdict red forever over notes nobody is going to place.
			boolean included = layer.buildEnabled();
			for (NoteEvent note : layer.notes()) {
				totalNotes++;
				maximumNoteDuration = Math.max(maximumNoteDuration, note.durationTicks());
				if (!included) {
					continue;
				}
				if (heard != null && !heard.add(ComposerProject.NoteSound.of(layer, note))) {
					duplicateNotes++;
					continue;
				}
				if (!note.isBuildable()) {
					outOfRange++;
				} else {
					counts.merge(note.startTick(), 1, Integer::sum);
				}
			}
		}
		int peak = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		long overloaded = counts.values().stream().filter(count -> count > MAX_SIMULTANEOUS_NOTES).count();

		double span = redstoneTickSpan(project);
		// The gap from the last note to the end marker is a delay the build has to place like any
		// other, so it is checked like any other. A marker sitting on the last note adds no delay at
		// all and is always fine -- only trailing silence can be unbuildable.
		Set<Long> checked = new LinkedHashSet<>(counts.keySet());
		if (!counts.isEmpty() && project.endTick() > Collections.max(counts.keySet())) {
			checked.add(project.endTick());
		}
		Set<Long> offGrid = new LinkedHashSet<>();
		Set<Long> crowded = new LinkedHashSet<>();
		Map<Long, Double> gaps = new HashMap<>();
		long previous = Long.MIN_VALUE;
		for (long tick : checked.stream().sorted().toList()) {
			if (previous != Long.MIN_VALUE) {
				// A build is a chain of repeater delays, so only the gap between consecutive events
				// has to be expressible. Where the song sits relative to time zero is irrelevant --
				// an absolute-position test just flags every note when the musical grid and the
				// repeater grid do not share a common multiple.
				double gap = (tick - previous) / span;
				gaps.put(tick, gap);
				if (gap < 1.0 - 1.0e-6) {
					crowded.add(tick);
				} else if (Math.abs(gap - Math.round(gap)) > 0.02) {
					offGrid.add(tick);
				}
			}
			previous = tick;
		}
		return new SongAnalysis(totalNotes, outOfRange, Map.copyOf(counts), peak, overloaded,
			maximumNoteDuration, Set.copyOf(offGrid), Set.copyOf(crowded), Map.copyOf(gaps),
			project.endTick(), project.endTick() / span / 10.0, duplicateNotes);
	}

	/**
	 * Composer ticks per Minecraft repeater tick, at the speed the song is set to.
	 *
	 * <p>A repeater cannot delay less than one tick, so this is the finest spacing a build can
	 * express. Raising the speed widens it: more of the song passes per real tick, so gaps that
	 * were a comfortable two ticks apart become one, and eventually less than one.</p>
	 *
	 * <p>This used to divide by the speed rather than multiply, which inverted it. The two agree at
	 * 1.00x so nothing looked wrong, but at 2.00x a song was measured against a span four times too
	 * small and reported buildable when it was not -- and its length was reported as twice its
	 * real duration rather than half. It must match {@code buildDelayTicks}, which is what actually
	 * decides the delays that get placed.</p>
	 */
	public static double redstoneTickSpan(ComposerProject project) {
		double span = project.ppq() * 100_000.0 / project.tempoMicrosPerQuarter();
		return Math.max(1.0e-6, span * Math.max(1, project.speedQuarters()) / 4.0);
	}

	/** True when nothing left in the composition would misbuild or fail to build at all. */
	public boolean buildable() {
		return outOfRange == 0 && overloadedTicks == 0 && crowded.isEmpty() && offGrid.isEmpty();
	}

	/** True when the end marker's own trailing delay is not one a build can place. */
	public boolean endMarkerIssue() {
		return offGrid.contains(endTick) || crowded.contains(endTick);
	}

	/**
	 * The problem ticks that actually have notes on them.
	 *
	 * <p>The end marker is checked alongside the notes but is not one, so counting the raw sets
	 * reported problems that Select could then find nothing for -- "1 too frequent" with no note
	 * anywhere to blame. Anything user-facing that counts notes, or offers to select them, has to
	 * use these; the raw sets stay for the note highlighting, where a tick with no note simply
	 * matches nothing.</p>
	 */
	public Set<Long> crowdedNotes() {
		return withoutMarker(crowded);
	}

	public Set<Long> offGridNotes() {
		return withoutMarker(offGrid);
	}

	private Set<Long> withoutMarker(Set<Long> ticks) {
		if (!ticks.contains(endTick) || chordCounts.containsKey(endTick)) {
			return ticks;
		}
		Set<Long> without = new LinkedHashSet<>(ticks);
		without.remove(endTick);
		return Set.copyOf(without);
	}

	/** How the end marker is at fault, or empty when it is not. */
	public String endMarkerProblem() {
		if (crowded.contains(endTick)) {
			return "end marker under a tick past the last note";
		}
		if (offGrid.contains(endTick)) {
			return "end marker off grid";
		}
		return "";
	}

	public String gapLabel(long tick) {
		Double gap = gaps.get(tick);
		return gap == null ? "?" : String.format(Locale.ROOT, "%.2f", gap);
	}

	/** How long the song runs, at the speed it is set to play and build at. */
	public String lengthLabel() {
		long seconds = Math.round(secondsLong);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
	}

	/** Why the song will not build, shortest first, or an empty list when it will. */
	public List<String> problems() {
		List<String> problems = new java.util.ArrayList<>();
		if (outOfRange > 0) {
			problems.add(outOfRange + " out of range");
		}
		if (!crowdedNotes().isEmpty()) {
			problems.add(crowdedNotes().size() + " too frequent");
		}
		if (!offGridNotes().isEmpty()) {
			problems.add(offGridNotes().size() + " off grid");
		}
		if (!endMarkerProblem().isEmpty()) {
			problems.add(endMarkerProblem());
		}
		if (overloadedTicks > 0) {
			problems.add(overloadedTicks + " chords over " + MAX_SIMULTANEOUS_NOTES);
		}
		return List.copyOf(problems);
	}
}
