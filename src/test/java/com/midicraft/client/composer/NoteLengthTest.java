package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Dragging a note's far end: what changes is its length, and nothing else about it.
 */
class NoteLengthTest {
	private static ComposerProject songOf(NoteEvent... notes) {
		return new ComposerProject("Lengths", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Lead", "HARP", false, true, true, List.of(notes))), 0, 100L,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static NoteEvent find(ComposerProject song, long id) {
		return song.layers().get(0).notes().stream()
			.filter(note -> note.id() == id).findFirst().orElseThrow();
	}

	@Test
	void everyChosenNoteChangesLengthByTheSameAmount() {
		ComposerProject song = songOf(new NoteEvent(1, 60, 0, 120, 90),
			new NoteEvent(2, 64, 480, 240, 70), new NoteEvent(3, 67, 960, 120, 50));

		ComposerProject resized = song.withNotesResized(Set.of(1L, 2L), 360L);

		assertEquals(480L, find(resized, 1).durationTicks());
		assertEquals(600L, find(resized, 2).durationTicks());
		assertEquals(120L, find(resized, 3).durationTicks(), "a note left out keeps its length");
		assertEquals(480L, find(resized, 2).startTick(), "a resize never moves a start");
		assertEquals(70, find(resized, 2).velocity());
		assertEquals(64, find(resized, 2).midiNote());
	}

	@Test
	void shorteningStopsEachNoteAtOneTick() {
		ComposerProject song = songOf(new NoteEvent(1, 60, 0, 120, 90),
			new NoteEvent(2, 64, 480, 960, 70));

		ComposerProject resized = song.withNotesResized(Set.of(1L, 2L), -500L);

		assertEquals(1L, find(resized, 1).durationTicks());
		assertEquals(460L, find(resized, 2).durationTicks(), "the longer note goes on shortening");
	}

	@Test
	void nothingToResizeIsNoEdit() {
		ComposerProject song = songOf(new NoteEvent(1, 60, 0, 120, 90));

		assertSame(song, song.withNotesResized(Set.of(), 120L));
		assertSame(song, song.withNotesResized(Set.of(1L), 0L));
	}
}
