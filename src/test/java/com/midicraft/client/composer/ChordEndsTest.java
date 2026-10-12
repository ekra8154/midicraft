package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Select > Overloaded chords > Keep top and Keep bottom: a chord over the song's limit keeps the
 * notes at one end and gives up the rest, judged across every layer and taken only from the
 * selected ones.
 */
class ChordEndsTest {
	private static final long MELODY = 1L;
	private static final long INNER = 2L;
	private static final long BASS = 3L;
	private static final long SNARE = 4L;

	/** Melody, an inner voice and a bass on one tick, each on its own layer, and a snare. */
	private static ComposerProject chord() {
		return new ComposerProject("Song", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(
				new Layer("Melody", "HARP", false, true, true, List.of(new NoteEvent(MELODY, 72, 0, 120, 90))),
				new Layer("Inner", "HARP", false, true, true, List.of(new NoteEvent(INNER, 64, 0, 120, 90))),
				new Layer("Bass", "BASS", false, true, true, List.of(new NoteEvent(BASS, 56, 0, 120, 90))),
				new Layer("Drums", "SNARE", false, true, true, List.of(new NoteEvent(SNARE, 60, 0, 120, 90)))),
			0, 5L, 0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static ChordThinner.Result keep(int limit, Set<Integer> layers, ChordThinner.End end,
			boolean keepPercussion) {
		return ChordThinner.keepEnds(chord(), ChordSkips.Rules.at(limit), true, layers, end,
			keepPercussion);
	}

	@Test
	void keepingTheTopTakesEverythingUnderIt() {
		ChordThinner.Result kept = keep(1, null, ChordThinner.End.TOP, false);
		assertEquals(Set.of(INNER, BASS, SNARE), kept.noteIds());
		assertEquals(0, kept.chordsStillOver());
	}

	@Test
	void keepingTheBottomTakesEverythingOverIt() {
		ChordThinner.Result kept = keep(2, null, ChordThinner.End.BOTTOM, false);
		assertEquals(Set.of(MELODY, SNARE), kept.noteIds(), "bass and inner stay, the snare has no place");
	}

	/** The bass layer alone still loses its note to the melody above it on another layer. */
	@Test
	void theChordIsJudgedAcrossEveryLayer() {
		ChordThinner.Result kept = keep(1, Set.of(2), ChordThinner.End.TOP, false);
		assertEquals(Set.of(BASS), kept.noteIds());
		assertEquals(1, kept.chordsStillOver(), "the inner voice and snare are on layers not selected");
	}

	/** With the melody's layer alone selected, the top stays and there is nothing to take. */
	@Test
	void whatHasToStayIsNeverTaken() {
		ChordThinner.Result kept = keep(1, Set.of(0), ChordThinner.End.TOP, false);
		assertEquals(Set.of(), kept.noteIds());
		assertEquals(1, kept.chordsStillOver());
	}

	/** Kept percussion is never taken, and uses up the room before any tuned note. */
	@Test
	void keptPercussionTakesItsRoomFirst() {
		ChordThinner.Result kept = keep(2, null, ChordThinner.End.TOP, true);
		assertEquals(Set.of(INNER, BASS), kept.noteIds(), "the snare and the melody are the two kept");
	}

	/**
	 * Room for two with the chord limit's own thinning off: left on, it would play a counted voice
	 * with fewer copies before anything was selected, and the chord would never be over.
	 */
	private static final ChordSkips.Rules UNTHINNED_2 = new ChordSkips.Rules(2, false, false);

	/** A melody over a second layer whose one note at 60 is {@code under}. */
	private static ComposerProject over(Layer under) {
		return new ComposerProject("Song", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Melody", "HARP", false, true, true, List.of(new NoteEvent(MELODY, 72, 0, 120, 90))),
				under),
			0, 5L, 0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static Layer second(long id) {
		return new Layer("Second", "HARP", false, true, true, List.of(new NoteEvent(id, 60, 0, 120, 90)));
	}

	/** A melodic layer gives its notes up like any other: the screenshot's song, one voice a note. */
	@Test
	void aMelodicLayerGivesUpItsNotes() {
		ComposerProject song = over(second(INNER).withSplit(new ComposerProject.Split(List.of(
			new ComposerProject.Split.Voice("HARP", 54, 66, 1),
			new ComposerProject.Split.Voice("FLUTE", 67, 78, 1)))));
		ChordThinner.Result kept = ChordThinner.keepEnds(song, ChordSkips.Rules.at(1), true, Set.of(1),
			ChordThinner.End.TOP, false);
		assertEquals(Set.of(INNER), kept.noteIds());
		assertEquals(0, kept.chordsStillOver());
	}

	/**
	 * A note that expands into two voices is two sounds, kept or taken whole. Under a melody with
	 * room for two it does not fit and goes; keeping the bottom, it is the two kept.
	 */
	@Test
	void aNoteIsWeighedByEveryVoiceItPlaces() {
		ComposerProject song = over(second(INNER).withSplit(new ComposerProject.Split(List.of(
			ComposerProject.Split.Voice.fullRange("HARP"), ComposerProject.Split.Voice.fullRange("PLING")))));
		ChordThinner.Result top = ChordThinner.keepEnds(song, UNTHINNED_2, true, null,
			ChordThinner.End.TOP, false);
		assertEquals(Set.of(INNER), top.noteIds());
		assertEquals(2, top.soundsRemoved());
		ChordThinner.Result bottom = ChordThinner.keepEnds(song, UNTHINNED_2, true, null,
			ChordThinner.End.BOTTOM, false);
		assertEquals(Set.of(MELODY), bottom.noteIds());
		assertEquals(0, bottom.chordsStillOver());
	}

	/** A counted voice and a composite layer weigh every block they place. */
	@Test
	void countedAndCompositeNotesAreWeighedWhole() {
		ComposerProject counted = over(second(INNER).withMix(List.of(
			ComposerProject.Split.Voice.fullRange("HARP").withCount(2))));
		assertEquals(Set.of(INNER), ChordThinner.keepEnds(counted, UNTHINNED_2, true, null,
			ChordThinner.End.TOP, false).noteIds(), "two copies do not fit beside the melody");
		assertEquals(0, ChordThinner.keepEnds(counted, new ChordSkips.Rules(3, false, false), true, null,
			ChordThinner.End.TOP, false).chordsOver(), "three blocks is not over three");

		ComposerProject composite = over(second(INNER).withMix(List.of(
			ComposerProject.Split.Voice.fullRange("HARP"), ComposerProject.Split.Voice.fullRange("PLING"))));
		assertEquals(Set.of(MELODY), ChordThinner.keepEnds(composite, UNTHINNED_2, true, null,
			ChordThinner.End.BOTTOM, false).noteIds());
	}

	/** Under the limit, nothing is touched. */
	@Test
	void aChordAtTheLimitIsLeftAlone() {
		ChordThinner.Result kept = keep(4, null, ChordThinner.End.TOP, false);
		assertEquals(0, kept.chordsOver());
		assertEquals(Set.of(), kept.noteIds());
	}
}
