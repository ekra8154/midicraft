package com.fastnoteblocks.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	/**
	 * A phrase dragged into the start of the song keeps its shape.
	 *
	 * <p>Clamping note by note stops the ones that have arrived at tick zero while the ones behind
	 * them keep coming, so every gap in the phrase closes up: three notes a beat apart, dragged far
	 * enough left, used to arrive as a chord. Nothing on screen says that happened -- the notes are
	 * where you dropped them -- and undo is the only way back.</p>
	 */
	@Test
	void aPhraseStopsAtTheStartWithoutFoldingUp() {
		ComposerProject song = songOf(layer("Melody", "HARP", 60, 62, 64));
		Set<Long> all = song.layers().getFirst().notes().stream()
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		assertEquals(List.of(0L, 240L, 480L), startTicks(song.layers().getFirst()));

		ComposerProject dragged = song.moveNotes(all, -10_000L, 0);

		assertEquals(List.of(0L, 240L, 480L), startTicks(dragged.layers().getFirst()),
			"the phrase was already against the start, so nothing should have moved at all");

		ComposerProject fromLater = song.moveNotes(all, 960L, 0).moveNotes(all, -10_000L, 0);
		assertEquals(List.of(0L, 240L, 480L), startTicks(fromLater.layers().getFirst()),
			"dragged in from later it should stop with its first note on zero, still a beat apart");
	}

	/** The same for pitch, where losing the intervals is not an early phrase but a wrong chord. */
	@Test
	void aChordStopsAtTheEdgeOfTheRangeWithoutCollapsing() {
		ComposerProject song = songOf(layer("Chord", "HARP", 120, 124, 127));
		Set<Long> all = song.layers().getFirst().notes().stream()
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		ComposerProject up = song.moveNotes(all, 0L, 40);

		assertEquals(List.of(120, 124, 127), up.layers().getFirst().notes().stream()
			.map(NoteEvent::midiNote).toList(),
			"the top note is already on 127, so the chord cannot rise at all");

		ComposerProject down = song.moveNotes(all, 0L, -200);
		assertEquals(List.of(0, 4, 7), down.layers().getFirst().notes().stream()
			.map(NoteEvent::midiNote).toList(),
			"pushed down it should keep every interval and stop with its lowest note on zero");
	}

	/**
	 * A duplicate lands next to its source, carries the notes, and shares none of their identity.
	 *
	 * <p>Fresh ids are the whole of it: the two layers are selected, moved and deleted by note id,
	 * so a copy that reused them would be a second view of the first layer rather than a new one.</p>
	 */
	@Test
	void duplicatingALayerCopiesItsNotesUnderNewIds() {
		ComposerProject song = songOf(
			layer("Lead", "HARP", 60, 62),
			layer("Bass", "BASS", 40));

		ComposerProject copied = song.duplicateLayer(0);

		assertEquals(3, copied.layers().size());
		assertEquals("Lead copy", copied.layers().get(1).name(), "next to its source, not at the end");
		assertEquals("Bass", copied.layers().get(2).name(), "and the rest moved down");
		assertEquals(1, copied.activeLayerIndex(), "a duplicate is made in order to change it");
		assertEquals(startTicks(song.layers().getFirst()), startTicks(copied.layers().get(1)));

		Set<Long> sourceIds = song.layers().getFirst().notes().stream()
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		assertTrue(copied.layers().get(1).notes().stream().noneMatch(note -> sourceIds.contains(note.id())),
			"the copy's notes must not answer to the original's ids");
		assertEquals("HARP", copied.layers().get(1).instrument());
	}

	/** Nothing to duplicate is not an error, it is nothing to do. */
	@Test
	void duplicatingALayerThatIsNotThereChangesNothing() {
		ComposerProject song = songOf(layer("Lead", "HARP", 60));

		assertEquals(song, song.duplicateLayer(-1));
		assertEquals(song, song.duplicateLayer(7));
	}

	/**
	 * A layer holds at most one note on a pitch at a tick, whichever road arrives at the cell.
	 *
	 * <p>Asserted through the operations rather than on the constructor alone, because the point of
	 * putting the rule in the constructor is that no caller has to remember it -- and the way that
	 * claim fails is a new operation building its layers some other way.</p>
	 */
	@Test
	void aLayerNeverHoldsTwoNotesInOneCell() {
		ComposerProject song = songOf(new Layer("One", "HARP", false, true, true,
			List.of(note(60, 0L), note(60, 0L), note(60, 0L), note(64, 0L))));
		assertEquals(2, song.layers().getFirst().notes().size(),
			"three notes on one pitch at one tick are one note; the other pitch survives");

		ComposerProject placed = song.addNote(0, 60, 0L, 120L);
		assertEquals(song, placed, "placing into a taken cell changes nothing at all, not even an id");

		ComposerProject free = song.addNote(0, 62, 0L, 120L);
		assertEquals(3, free.layers().getFirst().notes().size(), "an empty cell still takes a note");

		// Dragged on top of each other: one gesture, and the note that was there first survives.
		ComposerProject spread = songOf(layer("One", "HARP", 60, 60));
		assertEquals(List.of(0L, 240L), startTicks(spread.layers().getFirst()));
		long later = spread.layers().getFirst().notes().get(1).id();
		ComposerProject collided = spread.moveNotes(Set.of(later), -240L, 0);
		assertEquals(1, collided.layers().getFirst().notes().size(),
			"dragging one onto the other leaves one note");
		assertTrue(collided.layers().getFirst().notes().getFirst().id() < later,
			"and it is the one that was already there");

		// Transposed onto each other: two pitches a tone apart, moved a tone.
		ComposerProject chord = songOf(new Layer("Chord", "HARP", false, true, true,
			List.of(note(60, 0L), note(62, 0L))));
		long lower = chord.layers().getFirst().notes().getFirst().id();
		assertEquals(1, chord.moveNotes(Set.of(lower), 0L, 2).layers().getFirst().notes().size(),
			"transposing one onto the other leaves one note");

		// Quantized onto each other: two neighbours inside one grid cell.
		ComposerProject offGrid = songOf(new Layer("One", "HARP", false, true, true,
			List.of(note(60, 0L), note(60, 30L))));
		assertEquals(2, offGrid.layers().getFirst().notes().size(), "30 ticks apart they are two");
		assertEquals(1, offGrid.withQuantized(480, Set.of()).layers().getFirst().notes().size(),
			"quantized to a quarter they are one");
	}

	/** Merging two layers that play the same note at the same time gives one note, not two. */
	@Test
	void mergingCollapsesWhatWouldHaveBeenADoubledNote() {
		ComposerProject song = songOf(
			new Layer("One", "HARP", false, true, true, List.of(note(60, 0L), note(64, 240L))),
			new Layer("Two", "HARP", false, true, true, List.of(note(60, 0L), note(67, 480L))));

		ComposerProject merged = song.mergeLayers(Set.of(0, 1));

		assertEquals(1, merged.layers().size());
		assertEquals(3, merged.layers().getFirst().notes().size(),
			"the shared note is one note; the two that differ are their own");
	}

	/**
	 * A block of layers arrives together and in its own order, however scattered it started.
	 *
	 * <p>The alternative -- each selected layer stepping one place on its own -- turns a selection
	 * inside out the moment anything unselected sits between two of them.</p>
	 */
	@Test
	void movingSeveralLayersKeepsThemInOrderAndTogether() {
		ComposerProject song = songOf(
			layer("A", "HARP", 60),
			layer("B", "HARP", 61),
			layer("C", "HARP", 62),
			layer("D", "HARP", 63));

		assertEquals(List.of("C", "A", "B", "D"), names(song.moveLayersTo(Set.of(0, 1), 3)),
			"A and B step down past C as one block");
		assertEquals(List.of("A", "B", "D", "C"), names(song.moveLayersTo(Set.of(3), 2)),
			"D lands in the gap above C");
		assertEquals(List.of("B", "D", "A", "C"), names(song.moveLayersTo(Set.of(1, 3), 0)),
			"two rows with something between them arrive adjacent, in their own order");
	}

	/** A gap the block already fills is not a move, so the layers come back untouched. */
	@Test
	void movingLayersNowhereChangesNothing() {
		ComposerProject song = songOf(layer("A", "HARP", 60), layer("B", "HARP", 61));

		assertEquals(List.of("A", "B"), names(song.moveLayersTo(Set.of(0), 0)));
		assertEquals(List.of("A", "B"), names(song.moveLayersTo(Set.of(0, 1), 0)),
			"moving all of them is moving none of them");
		assertEquals(List.of("A", "B"), names(song.moveLayersTo(Set.of(), 1)));
	}

	/**
	 * The permutation is what the screen renumbers its own layer positions through, so it has to
	 * describe the same rearrangement the project performed.
	 */
	@Test
	void theLayerOrderMatchesTheMoveItDescribes() {
		ComposerProject song = songOf(
			layer("A", "HARP", 60),
			layer("B", "HARP", 61),
			layer("C", "HARP", 62));
		List<Integer> order = song.layerOrderAfterMove(Set.of(2), 0);

		assertEquals(List.of(2, 0, 1), order);
		assertEquals(names(song.withLayerOrder(order)), names(song.moveLayersTo(Set.of(2), 0)));
	}

	/** Each copy under its own original, so duplicating three parts leaves three readable pairs. */
	@Test
	void duplicatingSeveralLayersPutsEachCopyUnderItsSource() {
		ComposerProject song = songOf(
			layer("A", "HARP", 60),
			layer("B", "HARP", 61),
			layer("C", "HARP", 62));

		ComposerProject copied = song.duplicateLayers(Set.of(0, 2));

		assertEquals(List.of("A", "A copy", "B", "C", "C copy"), names(copied));
		assertEquals(1, copied.activeLayerIndex(), "onto the first copy");
		Set<Long> sourceIds = song.layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		assertTrue(copied.layers().get(4).notes().stream()
				.noneMatch(note -> sourceIds.contains(note.id())),
			"the second copy's notes are as new as the first's");
	}

	/**
	 * A marker is one to a tick, kept in order, and survives every edit that does not name it.
	 */
	@Test
	void markersAreOnePerTickAndInOrder() {
		ComposerProject song = songOf(layer("A", "HARP", 60))
			.withMarkerAt(960L, "Chorus")
			.withMarkerAt(0L, "Intro");

		assertEquals(List.of("Intro", "Chorus"), markerLabels(song), "sorted by tick, not by arrival");
		assertEquals(List.of("Intro", "Bridge"), markerLabels(song.withMarkerAt(960L, "Bridge")),
			"writing to an occupied tick is a rename, not a second flag");
		assertEquals(List.of("Intro"), markerLabels(song.withoutMarkerAt(960L)));
		assertEquals(song, song.withoutMarkerAt(123L), "nothing there is nothing to do");
		assertEquals("Chorus", song.markerNear(950L, 20L).label());
		assertNull(song.markerNear(950L, 5L), "outside the tolerance is not near");
	}

	/** The one thing that has to carry them: a layer edit is not an opinion about the timeline. */
	@Test
	void markersSurviveLayerEdits() {
		ComposerProject song = songOf(layer("A", "HARP", 60), layer("B", "HARP", 61))
			.withMarkerAt(480L, "Verse");

		assertEquals(List.of("Verse"), markerLabels(song.moveLayersTo(Set.of(1), 0)));
		assertEquals(List.of("Verse"), markerLabels(song.duplicateLayers(Set.of(0))));
		assertEquals(List.of("Verse"), markerLabels(song.deleteLayers(Set.of(1))));
		assertEquals(List.of("Verse"), markerLabels(song.withTempo(400_000)));
		assertEquals(List.of("Verse"), markerLabels(song.withName("renamed")));
	}

	/**
	 * Pulling a song forward pulls its markers with it: a marker names a position in the music, and
	 * music that has moved leaves every one of them pointing a bar of silence away.
	 */
	@Test
	void snappingToStartCarriesTheMarkers() {
		ComposerProject song = songOf(new Layer("A", "HARP", false, true, true,
			List.of(note(60, 960L), note(62, 1920L))))
			.withMarkerAt(960L, "First note")
			.withMarkerAt(1440L, "Middle")
			.withMarkerAt(480L, "Count-in");

		ComposerProject snapped = song.snappedToStart(Set.of(0));

		assertEquals(List.of(0L, 480L), snapped.markers().stream()
			.map(ComposerProject.Marker::tick).toList(),
			"the two beyond the silence come back 960 with the notes, and the one inside it lands "
				+ "on zero -- where the first of them already is, so the two become one");
		assertEquals(List.of("Count-in", "Middle"), markerLabels(snapped),
			"first written wins the shared tick");
	}

	/**
	 * A song file written before markers existed still reads, and one written now still round-trips.
	 *
	 * <p>The library reads its songs with a plain {@code Gson} straight into this record, so adding
	 * a component changes the file format for every composition already on disk. The old ones have
	 * no {@code markers} field at all, which arrives as null -- and the whole of what makes that
	 * survivable is the compact constructor turning it into an empty list before anything reads it.
	 * The same call is what {@link SongLibrary} makes, minus the loader it needs a game for.</p>
	 */
	@Test
	void aSongFileWithoutMarkersStillReads() {
		com.google.gson.Gson gson = new com.google.gson.Gson();
		ComposerProject old = gson.fromJson(
			"{\"name\":\"old\",\"ppq\":480,\"tempoMicrosPerQuarter\":500000,\"layers\":"
				+ "[{\"name\":\"A\",\"instrument\":\"HARP\",\"muted\":false,\"buildEnabled\":true,"
				+ "\"visible\":true,\"notes\":[{\"id\":1,\"midiNote\":60,\"startTick\":0,"
				+ "\"durationTicks\":120,\"velocity\":100}]}],\"activeLayerIndex\":0,"
				+ "\"nextNoteId\":2,\"endTick\":1920,\"speedQuarters\":4}",
			ComposerProject.class);

		assertEquals(List.of(), old.markers(), "a missing field is no markers, not a null list");
		assertEquals(1, old.layers().size());

		ComposerProject marked = old.withMarkerAt(960L, "Chorus");
		ComposerProject reread = gson.fromJson(gson.toJson(marked), ComposerProject.class);
		assertTrue(marked.sameContentAs(reread), "and one written now comes back the same song");
		assertEquals(List.of("Chorus"), markerLabels(reread));
	}

	/** An unmarked composition is not a marked one, whatever else the two agree about. */
	@Test
	void markersCountAsContent() {
		ComposerProject song = songOf(layer("A", "HARP", 60));

		assertFalse(song.sameContentAs(song.withMarkerAt(240L, "Here")));
		assertTrue(song.withMarkerAt(240L, "Here").sameContentAs(song.withMarkerAt(240L, "Here")));
	}

	private static List<String> names(ComposerProject song) {
		return song.layers().stream().map(Layer::name).toList();
	}

	private static List<String> markerLabels(ComposerProject song) {
		return song.markers().stream().map(ComposerProject.Marker::label).toList();
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
