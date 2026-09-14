package com.midicraft.client.composer;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
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
	Set<Long> halfTicked,
	Map<Long, Double> gaps,
	long endTick,
	double secondsLong,
	int duplicateNotes,
	int buildNotes,
	/**
	 * Whether the build this song is judged against can reach between repeater ticks.
	 *
	 * <p>Two lanes started a game tick apart can; one lane cannot. It is a property of the paste
	 * mode, not of the song, and leaving it out is what let a song be called ready for a build
	 * nobody was making.</p>
	 */
	boolean halfTicksAvailable
) {
	/** Two note blocks hang off each of redstone's 15 reachable bus blocks. */
	public static final int MAX_SIMULTANEOUS_NOTES = 30;

	/**
	 * @param dedupeIdentical judge the song the way the build will place it, with a sound that two
	 *     layers play at the same instant counted once. Duplicates are otherwise counted against
	 *     the thirty a tick can carry, which can call a chord unbuildable that would have fitted.
	 */
	public static SongAnalysis of(ComposerProject project, boolean dedupeIdentical) {
		return of(project, dedupeIdentical, true);
	}

	/**
	 * @param halfTicksAvailable whether the paste mode in use builds two lanes. With one, a gap of
	 *     an odd number of game ticks cannot be placed at all: the delay chain rounds it to the
	 *     nearest whole repeater tick and the note plays late. This used to be assumed true for
	 *     every song, so a one-lane build of a half-ticked song reported MINECRAFT READY and then
	 *     quietly moved the notes.
	 */
	public static SongAnalysis of(ComposerProject project, boolean dedupeIdentical,
			boolean halfTicksAvailable) {
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
			boolean included = layer.inBuild();
			for (NoteEvent note : layer.notes()) {
				totalNotes++;
				maximumNoteDuration = Math.max(maximumNoteDuration, note.durationTicks());
				// A split layer's out-of-range notes are the ones no bracket covers, and they are
				// counted here off the stored notes: the expansion below only ever sees covered
				// ones, so an uncovered note would otherwise vanish from the verdict entirely.
				if (included && layer.split() != null && layer.outOfRange(note)) {
					outOfRange++;
				}
			}
			if (!included) {
				continue;
			}
			// Judged as the build will place it: a split layer expands into its voices first, so a
			// note two brackets cover counts twice against the thirty a tick can carry -- it is two
			// note blocks in the machine, whatever the document stores it as.
			// A counted voice is as many note blocks as its count, and is never deduplicated
			// either way, exactly as the build places it.
			for (Layer voice : layer.buildVoices()) {
				int copies = voice.copies();
				for (NoteEvent note : voice.notes()) {
					if (heard != null && copies == 1
							&& !heard.add(ComposerProject.NoteSound.of(voice, note))) {
						duplicateNotes++;
						continue;
					}
					// A sound effect layer has no range: the row is somewhere to put a hit, not a
					// pitch, so every note on one counts towards the build rather than the verdict.
					if (voice.pitched() && !note.isBuildable()) {
						outOfRange++;
					} else {
						counts.merge(note.startTick(), copies, Integer::sum);
					}
				}
			}
		}
		int peak = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		// Exactly the note blocks a paste would place: included layers, in range, counted once.
		int buildNotes = counts.values().stream().mapToInt(Integer::intValue).sum();
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
		Set<Long> halfTicked = new LinkedHashSet<>();
		Map<Long, Double> gaps = new HashMap<>();
		// Where a note stands, measured from the first event rather than from time zero.
		//
		// This used to ask of each consecutive pair whether the distance between them was a whole
		// number of game ticks, on the grounds that a build is a chain of delays and only the gaps
		// have to be expressible. True, and it names the wrong notes: one stray note makes two bad
		// gaps, the one before it and the one after, so the note standing exactly on a line after a
		// stray one was reported off the grid along with the stray. On a raw import that was 31 of
		// 428 flagged notes, all of them in the right place, and the answer to "which notes do I
		// need to move" had a tenth of the wrong notes in it.
		//
		// Measuring from the first event rather than from time zero is what makes this the same
		// question and not a stricter one. Every note a whole number of ticks from the first is
		// exactly every gap being whole; where the song sits relative to zero still does not
		// matter, and a passage shifted bodily off the beat is as buildable as it ever was.
		List<Long> sorted = checked.stream().sorted().toList();
		long origin = sorted.isEmpty() ? 0L : sorted.get(0);
		long previous = Long.MIN_VALUE;
		for (long tick : sorted) {
			// Measured in game ticks, because that is the finest a build can now place. A repeater
			// still cannot delay by less than one repeater tick, but a second lane started half a
			// tick late can, and the two together reach every game tick. So the grid this is held
			// to is twice as fine as the repeaters laying it, and a note that falls between two
			// repeater ticks is not an error any more -- it is the reason the second lane exists.
			double fromOrigin = (tick - origin) / span * 2.0;
			if (Math.abs(fromOrigin - Math.round(fromOrigin)) > 0.04) {
				offGrid.add(tick);
			}
			if (previous != Long.MIN_VALUE) {
				double gap = (tick - previous) / span;
				gaps.put(tick, gap);
				double gameGap = gap * 2.0;
				long whole = Math.round(gameGap);
				if (gameGap < 1.0 - 1.0e-6) {
					crowded.add(tick);
				} else if (Math.abs(gameGap - whole) < 0.04 && whole % 2L != 0L) {
					// A whole number of game ticks, and an odd one. Everything before this gap and
					// everything after it are on opposite halves of the tick, so they cannot share
					// a chain and the build needs both lanes.
					halfTicked.add(tick);
				}
			}
			previous = tick;
		}
		return new SongAnalysis(totalNotes, outOfRange, Map.copyOf(counts), peak, overloaded,
			maximumNoteDuration, Set.copyOf(offGrid), Set.copyOf(crowded), Set.copyOf(halfTicked),
			Map.copyOf(gaps), project.endTick(), project.endTick() / span / 10.0, duplicateNotes,
			buildNotes, halfTicksAvailable);
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
		return Math.max(1.0e-6, span * project.speedFactor());
	}

	/** True when nothing left in the composition would misbuild or fail to build at all. */
	public boolean buildable() {
		return outOfRange == 0 && overloadedTicks == 0 && crowded.isEmpty() && offGrid.isEmpty()
			&& (halfTicksAvailable || halfTickedNotes().isEmpty());
	}

	/** Half-ticked notes the build in use cannot place, which is all of them on one lane. */
	public Set<Long> unreachableHalfTicks() {
		return halfTicksAvailable ? Set.of() : halfTickedNotes();
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

	public Set<Long> halfTickedNotes() {
		return withoutMarker(halfTicked);
	}

	/**
	 * Lanes a build of this song needs: one, or two where anything half-ticks.
	 *
	 * <p>Two events an odd number of game ticks apart sit on opposite halves of the redstone tick,
	 * and no single chain of repeaters can hold both -- so one such gap anywhere in the song is
	 * enough to need the second lane, and a thousand of them need no more than that.</p>
	 *
	 * <p>Notes only. The end marker is measured with them but is not one of them, and a lane exists
	 * to sound notes: trailing silence landing half a tick out asks nothing of a second lane, since
	 * nothing sounds there. Counting it said "2 lanes" over songs with nothing half-ticked in them,
	 * which is the same shape of nonsense as reporting a problem note that does not exist.</p>
	 */
	public int lanesNeeded() {
		return halfTickedNotes().isEmpty() ? 1 : 2;
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
