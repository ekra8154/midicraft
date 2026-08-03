package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pulling the silence off the front of an import.
 *
 * <p>The whole question this has to answer correctly is what "the start" means when more than one
 * layer is selected, since the two readings differ by exactly the thing the music is made of.</p>
 */
class SnapToStartTest {
	private static final long PPQ = 480L;

	/** Every selected layer moves by the same amount, so parts keep their offsets from each other. */
	@Test
	void movesEverySelectedLayerByTheSameAmount() {
		ComposerProject song = songOf(
			layer("Pickup", 1920L, 2160L),
			layer("Bass", 2400L, 2880L));

		ComposerProject snapped = song.snappedToStart(Set.of(0, 1));

		assertEquals(List.of(0L, 240L), startsOf(snapped, 0), "the earliest layer reaches zero");
		assertEquals(List.of(480L, 960L), startsOf(snapped, 1),
			"and the one behind it stays exactly as far behind it as it was");
	}

	/** One layer selected is how you ask for that layer alone, and the rest must not move. */
	@Test
	void leavesUnselectedLayersWhereTheyAre() {
		ComposerProject song = songOf(
			layer("Pickup", 1920L),
			layer("Bass", 2400L));

		ComposerProject snapped = song.snappedToStart(Set.of(1));

		assertEquals(List.of(1920L), startsOf(snapped, 0), "the unselected layer did not move");
		assertEquals(List.of(0L), startsOf(snapped, 1));
	}

	/** Nothing to pull forward is not an edit, so it must not land on the undo stack as one. */
	@Test
	void doesNothingWhenTheSelectionAlreadyStartsAtZero() {
		ComposerProject song = songOf(layer("Melody", 0L, 480L), layer("Bass", 960L));
		assertSame(song, song.snappedToStart(Set.of(0, 1)));
		assertSame(song, song.snappedToStart(Set.of()), "and nothing selected is nothing to move");
	}

	/**
	 * The end marker comes back by the same amount, so the silence does not simply move to the tail.
	 *
	 * <p>Floored at the last note by the constructor, which is what an unselected layer running on
	 * past the selection relies on.</p>
	 */
	@Test
	void bringsTheEndMarkerBackWithTheNotes() {
		ComposerProject song = songOf(layer("Melody", 1920L, 3840L));
		assertEquals(9600L, song.endTick(), "the fixture's own marker");

		ComposerProject snapped = song.snappedToStart(Set.of(0));

		assertEquals(9600L - 1920L, snapped.endTick(), "moved by what the notes moved by");
	}

	@Test
	void holdsTheEndMarkerOutForALayerThatWasNotSnapped() {
		ComposerProject song = songOf(layer("Melody", 1920L), layer("Tail", 9000L));

		ComposerProject snapped = song.snappedToStart(Set.of(0));

		assertEquals(9000L, snapped.endTick(),
			"the unsnapped layer still plays there, so the marker cannot come back past it");
	}

	/** What the menu greys itself out on. */
	@Test
	void reportsTheFirstTickOfASelection() {
		ComposerProject song = songOf(layer("Pickup", 1920L), layer("Bass", 960L));

		assertEquals(960L, song.firstNoteTick(Set.of(0, 1)));
		assertEquals(1920L, song.firstNoteTick(Set.of(0)));
		assertEquals(-1L, song.firstNoteTick(Set.of()), "no layers is no first note");
		assertEquals(-1L, songOf(layer("Empty")).firstNoteTick(Set.of(0)),
			"an empty layer is no first note either");
	}

	private static List<Long> startsOf(ComposerProject song, int layer) {
		return song.layers().get(layer).notes().stream().map(NoteEvent::startTick).toList();
	}

	private static Layer layer(String name, long... ticks) {
		List<NoteEvent> notes = new ArrayList<>();
		long id = 1L;
		for (long tick : ticks) {
			notes.add(new NoteEvent(id++, 60, tick, PPQ / 4L, 100));
		}
		return new Layer(name, "HARP", false, true, true, notes);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("snap", (int)PPQ, 500_000, List.of(layers), 0, 1000L, 9600L, 4);
	}
}
