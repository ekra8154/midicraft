package com.midicraft.client.composer;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Picks the notes to drop from a chord too big for a build to place.
 *
 * <p>Redstone reaches fifteen bus blocks with two note blocks hanging off each, so a tick can carry
 * thirty sounds and no more. A transcription routinely exceeds that without containing thirty
 * <em>notes</em>: the chords in one imported song hold nine to fourteen distinct pitches played by
 * thirty-one to thirty-six note blocks, because transcribers double every pitch across many
 * instruments to fake volume and timbre — one of its chords has the same C♯ on nine instruments.
 * So the note worth least is almost always a duplicate of a pitch that is still being played, and
 * dropping it changes the colour of the chord without touching a note of the harmony.</p>
 *
 * <p>That is the whole rule: take from the most doubled pitch first, on the most doubled
 * instrument, quietest first, and <strong>never take the last voice of a pitch or the last voice
 * of an instrument</strong>. Those two clauses are what make it safe to run without listening — a
 * chord comes out with the same pitches and the same instruments it went in with, however far the
 * count is pushed down. Only how thickly each of them is scored changes.</p>
 *
 * <p>The instrument half matters as much as the pitch half and is easier to forget. A snare and a
 * harp on the same pitch are not two of anything: one is a noise burst that reads as rhythm, the
 * other is a tone. Dropping the snare because its pitch was "already covered" takes the drum out of
 * the bar, and nothing about the pitch count notices.</p>
 *
 * <p>A chord that runs out of doubling before it reaches the target is left alone and counted in
 * {@link Result#chordsStillOver()}. Going further would mean deleting a pitch or an instrument
 * nothing else is playing, which is a change to the music rather than to how thickly it is scored,
 * and that is not a decision to make silently.</p>
 */
public final class ChordThinner {
	public static final int MIN_TARGET = 8;
	public static final int MAX_TARGET = SongAnalysis.MAX_SIMULTANEOUS_NOTES;
	/**
	 * Below the limit rather than on it, because a chord sitting exactly on thirty leaves the world
	 * paste no room to work with.
	 */
	public static final int DEFAULT_TARGET = 24;

	private ChordThinner() {
	}

	/**
	 * @param noteIds every note to delete — all of them, not one per sound. With deduplication on,
	 *     the sounds the limit counts are distinct instrument-and-pitch pairs, so deleting a single
	 *     copy of a doubled sound removes a note and moves the count by nothing at all.
	 * @param chordsOver how many chords are above the target at all
	 * @param chordsThinned how many of those had something that could be taken
	 * @param chordsStillOver chords that ran out of what they could spare before reaching the target
	 */
	public record Result(
		Set<Long> noteIds,
		int chordsOver,
		int chordsThinned,
		int soundsRemoved,
		int chordsStillOver
	) {
		public boolean isEmpty() {
			return noteIds.isEmpty();
		}
	}

	/**
	 * One sound as the build counts it.
	 *
	 * <p>{@code distinct} is zero while deduplication is on, which folds every layer playing the
	 * same instrument at the same pitch and instant into one entry — exactly what the paste does.
	 * With it off each note stands on its own and counts against the thirty by itself, so the note
	 * id goes in the key instead. Thinning has to count the way the thing it is feeding counts.</p>
	 */
	private record Voice(String instrument, int midiNote, long distinct) {
	}

	/** Thins using anything in the composition, which is what the whole song being fixed means. */
	public static Result thin(ComposerProject project, int target, boolean dedupeIdentical) {
		return thin(project, target, dedupeIdentical, null);
	}

	/**
	 * @param fromLayers layer indices whose notes may be taken, or null for all of them. How big a
	 *     chord is never depends on this — a chord is as big as every layer going into the build
	 *     makes it, and so is what counts as its last pitch or its last instrument. All this
	 *     narrows is where the notes to drop may come from, so that thinning obeys the layer
	 *     selection the way the rest of the Select menu does.
	 */
	public static Result thin(ComposerProject project, int target, boolean dedupeIdentical,
			Set<Integer> fromLayers) {
		int cap = Math.max(MIN_TARGET, Math.min(MAX_TARGET, target));
		Map<Long, Map<Voice, List<NoteEvent>>> byTick = new TreeMap<>();
		// A sound with a copy in a layer that is not on offer cannot go: removing it would mean
		// deleting that copy too, and reaching into layers nobody selected is not thinning, it is
		// editing something else.
		Map<Long, Set<Voice>> blocked = new HashMap<>();
		int layerIndex = -1;
		for (Layer layer : project.layers()) {
			layerIndex++;
			// Only what the build would place: a layer left out of the sequence cannot overload a
			// tick it is not part of, and an out-of-range note is not placed at all.
			if (!layer.inBuild()) {
				continue;
			}
			// A split layer's notes are never on offer, only counted. Deleting a note the document
			// stores once removes every voice it doubles into at once, so taking "one sound" from a
			// split layer could silently take two -- and the doubling on those layers is the point
			// of them, not padding to spare. They still weigh their full expanded size against the
			// thirty, so thinning the ordinary layers around them stays honest.
			boolean offered = (fromLayers == null || fromLayers.contains(layerIndex))
				&& layer.split() == null;
			for (Layer voiceLayer : layer.buildVoices()) {
				for (NoteEvent note : voiceLayer.notes()) {
					// The same clause as the analysis: an out-of-range note is not placed at all,
					// and a sound effect has no range to be outside of.
					if (voiceLayer.pitched() && !note.isBuildable()) {
						continue;
					}
					Voice voice = new Voice(voiceLayer.instrument(), note.midiNote(),
						dedupeIdentical ? 0L : note.id());
					byTick.computeIfAbsent(note.startTick(), tick -> new LinkedHashMap<>())
						.computeIfAbsent(voice, key -> new ArrayList<>())
						.add(note);
					if (!offered) {
						blocked.computeIfAbsent(note.startTick(), tick -> new HashSet<>()).add(voice);
					}
				}
			}
		}

		Set<Long> doomed = new LinkedHashSet<>();
		int chordsOver = 0;
		int chordsThinned = 0;
		int soundsRemoved = 0;
		int chordsStillOver = 0;
		for (Map.Entry<Long, Map<Voice, List<NoteEvent>>> entry : byTick.entrySet()) {
			Map<Voice, List<NoteEvent>> chord = entry.getValue();
			if (chord.size() <= cap) {
				continue;
			}
			chordsOver++;
			Set<Voice> offLimits = blocked.getOrDefault(entry.getKey(), Set.of());
			Map<Integer, List<Voice>> byPitch = new HashMap<>();
			Map<String, List<Voice>> byInstrument = new HashMap<>();
			for (Voice voice : chord.keySet()) {
				byPitch.computeIfAbsent(voice.midiNote(), pitch -> new ArrayList<>()).add(voice);
				byInstrument.computeIfAbsent(voice.instrument(), name -> new ArrayList<>()).add(voice);
			}
			int wanted = chord.size() - cap;
			int taken = 0;
			while (taken < wanted) {
				Voice worst = leastMissed(byPitch, byInstrument, chord, offLimits);
				if (worst == null) {
					break;
				}
				byPitch.get(worst.midiNote()).remove(worst);
				byInstrument.get(worst.instrument()).remove(worst);
				for (NoteEvent note : chord.get(worst)) {
					doomed.add(note.id());
				}
				soundsRemoved++;
				taken++;
			}
			if (taken > 0) {
				chordsThinned++;
			}
			if (taken < wanted) {
				chordsStillOver++;
			}
		}
		return new Result(Set.copyOf(doomed), chordsOver, chordsThinned, soundsRemoved,
			chordsStillOver);
	}

	/**
	 * The voice a chord would miss least: the most doubled pitch, on the most doubled instrument.
	 *
	 * <p>A voice is only a candidate when something else in the chord is still playing its pitch
	 * <em>and</em> something else is still playing its instrument. Those two clauses are the whole
	 * safety of this: the chord comes out with the same pitches and the same instruments it went in
	 * with, so neither the harmony nor the scoring can lose a member.</p>
	 *
	 * <p>The instrument half was missing at first, and pitch doubling alone is not enough to make a
	 * voice redundant. A snare and a harp on the same pitch are not two of anything — one is a noise
	 * burst that reads as rhythm and the other is a tone — so dropping the snare because its pitch
	 * was "already covered" takes the drum out of the bar. Measured before it was fixed: nine chords
	 * across the stress songs lost their only {@code BASEDRUM}, and four of Guardian's lost their
	 * only bass at a target of thirty. Instrument had been nothing but an alphabetical tie-break,
	 * which put {@code BASEDRUM} near the front of the queue.</p>
	 *
	 * <p>Ties are broken down to the note id so that thinning the same song twice picks the same
	 * notes.</p>
	 */
	private static Voice leastMissed(Map<Integer, List<Voice>> byPitch,
			Map<String, List<Voice>> byInstrument, Map<Voice, List<NoteEvent>> chord,
			Set<Voice> offLimits) {
		Comparator<Voice> order = Comparator
			.<Voice>comparingInt(voice -> -byPitch.get(voice.midiNote()).size())
			.thenComparingInt(voice -> -byInstrument.get(voice.instrument()).size())
			.thenComparingInt(voice -> loudest(chord.get(voice)))
			.thenComparing(Voice::instrument)
			.thenComparingLong(voice -> lowestId(chord.get(voice)));
		Voice best = null;
		for (List<Voice> onPitch : byPitch.values()) {
			if (onPitch.size() < 2) {
				continue;
			}
			for (Voice voice : onPitch) {
				if (byInstrument.get(voice.instrument()).size() < 2 || offLimits.contains(voice)) {
					continue;
				}
				if (best == null || order.compare(voice, best) < 0) {
					best = voice;
				}
			}
		}
		return best;
	}

	/** How loud a voice is: its loudest copy, since that is the one you would notice going. */
	private static int loudest(List<NoteEvent> notes) {
		int loudest = 0;
		for (NoteEvent note : notes) {
			loudest = Math.max(loudest, note.velocity());
		}
		return loudest;
	}

	private static long lowestId(List<NoteEvent> notes) {
		long lowest = Long.MAX_VALUE;
		for (NoteEvent note : notes) {
			lowest = Math.min(lowest, note.id());
		}
		return lowest;
	}
}
