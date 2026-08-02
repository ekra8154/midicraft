package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject.ClipboardNote;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.ComposerProject.PasteResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Three things a layer can survive or not: being pasted into, being deleted, and being clicked.
 *
 * <p>Grouped because they share the one fact that drives all of them -- a layer has exactly one
 * instrument, and the active layer is not part of the music.</p>
 */
class ClipboardAndLayersTest {
	private static long nextId = 1L;

	/** One instrument means the layers it came from were splitting a voice, not a sound. */
	@Test
	void oneInstrumentFlattensIntoTheLayerYouAimedAt() {
		ComposerProject song = songOf(
			layer("Melody high", "HARP", 60, 64),
			layer("Melody low", "HARP", 48, 52),
			layer("Target", "HARP"));
		PasteResult pasted = song.pasteNotes(2, copyOf(song, 0, 1), 0L);

		assertEquals(3, pasted.project().layers().size(), "nothing needed a layer of its own");
		assertEquals(0, pasted.addedLayers());
		assertEquals(4, pasted.project().layers().get(2).notes().size());
	}

	/**
	 * The bug this whole thing is about: a copy spanning two instruments used to arrive as one.
	 */
	@Test
	void severalInstrumentsGetALayerEach() {
		ComposerProject song = songOf(
			layer("Lead", "HARP", 60, 64),
			layer("Drums", "SNARE", 40),
			layer("Bass", "BASS", 30, 34),
			layer("Target", "HARP"));
		PasteResult pasted = song.pasteNotes(3, copyOf(song, 0, 1, 2), 0L);

		assertEquals(2, pasted.addedLayers(), "snare and bass each needed one; harp had a home");
		assertEquals(Set.of("HARP", "SNARE", "BASS"), instrumentsHolding(pasted, pasted.noteIds()),
			"every copied instrument still sounds like itself");
		assertEquals(2, pasted.project().layers().get(3).notes().size(),
			"the harp went where it was aimed rather than into a layer of its own");
	}

	/** Aiming at a scratch layer should not leave it empty beside the layers the paste made. */
	@Test
	void anEmptyLayerTakesTheFirstInstrumentRatherThanBeingStranded() {
		ComposerProject song = songOf(
			layer("Drums", "SNARE", 40),
			layer("Bass", "BASS", 30),
			layer("Scratch", "HARP"));
		PasteResult pasted = song.pasteNotes(2, copyOf(song, 0, 1), 0L);

		assertEquals(4, pasted.project().layers().size(), "one new layer, not two");
		assertEquals("SNARE", pasted.project().layers().get(2).instrument());
		assertFalse(pasted.project().layers().get(2).notes().isEmpty());
	}

	/**
	 * A layer with notes in it made a choice, and the paste does not get to overrule it.
	 *
	 * <p>Re-voicing a phrase by pasting it into another part is the reason a single-instrument
	 * paste takes the target's instrument, so a layer that is already playing something keeps
	 * what it plays whatever arrives.</p>
	 */
	@Test
	void aLayerThatIsAlreadyPlayingKeepsItsInstrument() {
		ComposerProject song = songOf(
			layer("Drums", "SNARE", 40),
			layer("Bells", "BELL", 72));
		PasteResult pasted = song.pasteNotes(1, copyOf(song, 0), 0L);

		assertEquals("BELL", pasted.project().layers().get(1).instrument());
		assertEquals(2, pasted.project().layers().get(1).notes().size());
		assertEquals(0, pasted.addedLayers());
	}

	/** In place is the offsets put back where they were read from, to the tick. */
	@Test
	void pastingInPlaceLandsOnTheOriginalTicks() {
		ComposerProject song = songOf(layer("Lead", "HARP"), layer("Copy", "HARP"));
		List<NoteEvent> notes = List.of(note(60, 480L), note(64, 720L), note(67, 1440L));
		song = song.withLayer(0, song.layers().get(0).withNotes(notes));

		long origin = 480L;
		List<ClipboardNote> clipboard = notes.stream()
			.map(note -> new ClipboardNote(note.startTick() - origin, note.midiNote(),
				note.durationTicks(), note.velocity(), "HARP", "Lead"))
			.toList();

		ComposerProject inPlace = song.pasteNotes(1, clipboard, origin).project();
		assertEquals(List.of(480L, 720L, 1440L), startTicks(inPlace.layers().get(1)));
	}

	@Test
	void deletingLayersTakesTheirNotesAndLeavesTheRest() {
		ComposerProject song = songOf(
			layer("One", "HARP", 60),
			layer("Two", "BASS", 40),
			layer("Three", "BELL", 72));
		ComposerProject after = song.deleteLayers(Set.of(0, 2));

		assertEquals(1, after.layers().size());
		assertEquals("Two", after.layers().getFirst().name());
		assertEquals(0, after.activeLayerIndex());
	}

	/** A composition with nowhere to put a note is not a composition anyone can carry on with. */
	@Test
	void deletingEveryLayerLeavesOneToWorkIn() {
		ComposerProject song = songOf(layer("One", "HARP", 60), layer("Two", "BASS", 40));
		ComposerProject after = song.deleteLayers(Set.of(0, 1));

		assertEquals(1, after.layers().size());
		assertTrue(after.layers().getFirst().notes().isEmpty());
		assertEquals(0, after.activeLayerIndex());
	}

	@Test
	void deletingNothingChangesNothing() {
		ComposerProject song = songOf(layer("One", "HARP", 60));
		assertEquals(song, song.deleteLayers(Set.of()));
		assertEquals(song, song.deleteLayers(Set.of(7)));
	}

	/**
	 * Clicking a layer is not an edit, and leaving after doing only that must not ask about saving.
	 */
	@Test
	void theActiveLayerIsNotPartOfTheMusic() {
		ComposerProject song = songOf(layer("One", "HARP", 60), layer("Two", "BASS", 40));
		ComposerProject clicked = song.withActiveLayer(1);

		assertNotEquals(song, clicked, "the record really does differ, which is why equals cannot say");
		assertTrue(song.sameContentAs(clicked), "but no note moved");
		assertTrue(clicked.sameContentAs(song), "and it reads the same way round");
	}

	/** Everything that is the music still counts, or the prompt would stop protecting anything. */
	@Test
	void everythingElseStillCountsAsAnEdit() {
		ComposerProject song = songOf(layer("One", "HARP", 60), layer("Two", "BASS", 40));

		assertFalse(song.sameContentAs(song.withName("Renamed")));
		assertFalse(song.sameContentAs(song.withTempo(600_000)));
		assertFalse(song.sameContentAs(song.withSpeedQuarters(8)));
		assertFalse(song.sameContentAs(song.withEndTick(song.endTick() + 480L)));
		assertFalse(song.sameContentAs(song.deleteLayers(Set.of(1))));
		assertFalse(song.sameContentAs(song.withLayer(0, song.layers().getFirst().withMuted(true))));
		assertFalse(song.sameContentAs(song.addNote(0, 62, 960L, 120L)));
		assertFalse(song.sameContentAs(null));
	}

	private static Set<String> instrumentsHolding(PasteResult pasted, Set<Long> ids) {
		return pasted.project().layers().stream()
			.filter(layer -> layer.notes().stream().anyMatch(note -> ids.contains(note.id())))
			.map(Layer::instrument)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** Every note of the given layers, as the screen's copy would put it on the clipboard. */
	private static List<ClipboardNote> copyOf(ComposerProject song, int... layerIndices) {
		List<ClipboardNote> clipboard = new ArrayList<>();
		long origin = Long.MAX_VALUE;
		for (int index : layerIndices) {
			for (NoteEvent note : song.layers().get(index).notes()) {
				origin = Math.min(origin, note.startTick());
			}
		}
		for (int index : layerIndices) {
			Layer layer = song.layers().get(index);
			for (NoteEvent note : layer.notes()) {
				clipboard.add(new ClipboardNote(note.startTick() - origin, note.midiNote(),
					note.durationTicks(), note.velocity(), layer.instrument(), layer.name()));
			}
		}
		return clipboard;
	}

	private static List<Long> startTicks(Layer layer) {
		return layer.notes().stream().map(NoteEvent::startTick).toList();
	}

	private static NoteEvent note(int midiNote, long startTick) {
		return new NoteEvent(nextId++, midiNote, startTick, 120L, 100);
	}

	private static Layer layer(String name, String instrument, int... pitches) {
		List<NoteEvent> notes = new ArrayList<>();
		long tick = 0L;
		for (int pitch : pitches) {
			notes.add(note(pitch, tick));
			tick += 240L;
		}
		return new Layer(name, instrument, false, true, true, notes);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("clipboard", 480, 500_000, List.of(layers), 0, nextId + 1000L,
			4000L, 4);
	}
}
