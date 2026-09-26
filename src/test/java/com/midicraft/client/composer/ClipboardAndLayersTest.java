package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject.ClipboardNote;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.PasteResult;
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
	 * A layer keeps a stack -- notes sharing a pitch and a tick -- but only the gestures that land a
	 * copy on a note make one, and the edits that squash notes together on purpose merge it.
	 *
	 * <p>Asserted through the operations, because what used to be one rule in the constructor is now
	 * a rule about which roads arrive at a shared cell and what each leaves there.</p>
	 */
	@Test
	void stacksComeFromCopiesAndSquashingEditsMergeThem() {
		ComposerProject song = songOf(new Layer("One", "HARP", false, true, true,
			List.of(note(60, 0L), note(60, 0L), note(60, 0L), note(64, 0L))));
		Layer stacked = song.layers().getFirst();
		assertEquals(4, stacked.notes().size(), "a layer keeps all three notes of a stack");
		assertEquals(3, stacked.stackSize(stacked.notes().getFirst()));
		assertEquals(2, stacked.stackedExtras().size(), "two of them sit on top of the first");
		assertEquals(2, stacked.withStacksMerged().notes().size(),
			"merged, the stack is one note and the other pitch survives");

		ComposerProject placed = song.addNote(0, 60, 0L, 120L);
		assertEquals(song, placed, "placing into a taken cell changes nothing at all, not even an id");
		ComposerProject free = song.addNote(0, 62, 0L, 120L);
		assertEquals(5, free.layers().getFirst().notes().size(), "an empty cell still takes a note");

		// Dragged on top of each other: both stay, the later one on top.
		ComposerProject spread = songOf(layer("One", "HARP", 60, 60));
		long later = spread.layers().getFirst().notes().get(1).id();
		ComposerProject collided = spread.moveNotes(Set.of(later), -240L, 0);
		assertEquals(2, collided.layers().getFirst().notes().size(),
			"dragging one onto the other keeps both, as a stack");
		assertEquals(later, collided.layers().getFirst().notes().getLast().id(),
			"with the one that was dragged on top");

		// Quantized onto each other: two neighbours inside one grid cell have become one note.
		ComposerProject offGrid = songOf(new Layer("One", "HARP", false, true, true,
			List.of(note(60, 0L), note(60, 30L))));
		assertEquals(2, offGrid.layers().getFirst().notes().size(), "30 ticks apart they are two");
		assertEquals(1, offGrid.withQuantized(480, Set.of()).layers().getFirst().notes().size(),
			"quantized to a quarter they are one");

		// And a stack always plays once, with the merge setting on or off.
		for (boolean merge : new boolean[] {true, false}) {
			SongAnalysis stats = SongAnalysis.of(song, merge);
			assertEquals(2, stats.buildNotes(), "a stack builds one note block (merge " + merge + ")");
			assertEquals(2, stats.duplicateNotes(), "and says the other two were merged");
		}
	}

	/** A stack on a counted layer is one note played the count's number of times, not more. */
	@Test
	void aStackOnACountedLayerPlaysTheCountOnce() {
		Layer harp = new Layer("One", "HARP", false, true, true,
			List.of(note(60, 0L), note(60, 0L))).withCountStepped("HARP", 2);
		assertEquals(3, harp.copies());
		SongAnalysis stats = SongAnalysis.of(songOf(harp), false);
		assertEquals(3, stats.buildNotes(), "two stacked notes at three copies are three blocks");
		assertEquals(3, stats.duplicateNotes(), "the stack's second note was three blocks merged");
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

	/** The copies as one block under the lowest source, in their sources' order. */
	@Test
	void duplicatingSeveralLayersPutsTheCopiesInABlockUnderTheLowest() {
		ComposerProject song = songOf(
			layer("A", "HARP", 60),
			layer("B", "HARP", 61),
			layer("C", "HARP", 62));

		ComposerProject copied = song.duplicateLayers(Set.of(0, 2));

		assertEquals(List.of("A", "B", "C", "A copy", "C copy"), names(copied));
		assertEquals(3, copied.activeLayerIndex(), "onto the first copy");
		Set<Long> sourceIds = song.layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		assertTrue(copied.layers().get(4).notes().stream()
				.noneMatch(note -> sourceIds.contains(note.id())),
			"the second copy's notes are as new as the first's");
	}

	/** Copies are numbered rather than growing a word each time, a copy of a copy included. */
	@Test
	void copiesAreNumberedNotRenamedAgain() {
		ComposerProject song = songOf(layer("A", "HARP", 60));
		ComposerProject once = song.duplicateLayer(0);
		assertEquals(List.of("A", "A copy"), names(once));
		ComposerProject twice = once.duplicateLayer(0);
		assertEquals(List.of("A", "A copy (1)", "A copy"), names(twice));
		ComposerProject ofACopy = twice.duplicateLayer(2);
		assertEquals(List.of("A", "A copy (1)", "A copy", "A copy (2)"), names(ofACopy),
			"a copy of a copy is another copy of A");
		ComposerProject block = song.duplicateLayers(Set.of(0));
		assertEquals(List.of("A", "A copy (1)", "A copy"),
			names(block.duplicateLayers(Set.of(0))), "and the block duplicate numbers the same way");
		assertEquals("A copy", ComposerProject.copyName("A copy", Set.of("A")),
			"the plain name is used while it is free");
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

	/**
	 * A duplicate leaves every note where it lives, which is what makes it not a paste.
	 *
	 * <p>A paste gathers a copy onto the layer it was aimed at and splits only what that layer's
	 * instrument cannot hold. Ctrl+D is not aimed anywhere, so a four-part phrase has to come back
	 * as four parts.</p>
	 */
	@Test
	void duplicatingNotesKeepsEachOnItsOwnLayer() {
		ComposerProject song = songOf(
			layer("Lead", "HARP", 60, 62),
			layer("Drums", "SNARE", 40));
		Set<Long> all = song.layers().stream()
			.flatMap(current -> current.notes().stream())
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		PasteResult duplicated = song.duplicateNotes(all, 1920L);

		assertEquals(2, duplicated.project().layers().size(), "no layer was needed for a duplicate");
		assertEquals(0, duplicated.addedLayers());
		assertEquals(3, duplicated.noteIds().size());
		assertEquals(List.of(0L, 240L, 1920L, 2160L), startTicks(duplicated.project().layers().getFirst()));
		assertEquals(List.of(0L, 1920L), startTicks(duplicated.project().layers().get(1)),
			"the snare stayed a snare and stayed where it was");
		assertTrue(duplicated.noteIds().stream().noneMatch(all::contains),
			"the copies answer to their own ids");
	}

	/** Stepping nowhere is not a duplicate, and neither is duplicating nothing. */
	@Test
	void duplicatingNothingOrNowhereChangesNothing() {
		ComposerProject song = songOf(layer("Lead", "HARP", 60));
		long only = song.layers().getFirst().notes().getFirst().id();

		assertEquals(song, song.duplicateNotes(Set.of(only), 0L).project());
		assertEquals(song, song.duplicateNotes(Set.of(), 480L).project());
		assertEquals(song, song.duplicateNotes(null, 480L).project());
	}

	/**
	 * Repeating a duplicate builds a passage rather than a pile.
	 *
	 * <p>The screen re-selects the copies after each press, so the next one steps off them. Asserted
	 * here because it is the whole claim of the gesture: four presses of Ctrl+D on a one-bar phrase
	 * are five bars of it, evenly spaced.</p>
	 */
	@Test
	void repeatedDuplicatesLandEndToEnd() {
		ComposerProject song = songOf(new Layer("Lead", "HARP", false, true, true,
			List.of(note(60, 0L), note(64, 960L))));
		Set<Long> selected = song.layers().getFirst().notes().stream()
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		for (int press = 0; press < 3; press++) {
			PasteResult step = song.duplicateNotes(selected, 1920L);
			song = step.project();
			selected = new LinkedHashSet<>(step.noteIds());
		}

		assertEquals(List.of(0L, 960L, 1920L, 2880L, 3840L, 4800L, 5760L, 6720L),
			startTicks(song.layers().getFirst()));
	}

	/**
	 * A layer sitting two octaves low moves as one under either mode, and does not split.
	 *
	 * <p>The baseline every other case is read against: when every note needs the same octave there
	 * is nothing for the modes to disagree about.</p>
	 */
	@Test
	void aLayerEntirelyOutOfRangeTakesOneOctaveAndStaysOneLayer() {
		ComposerProject song = songOf(new Layer("Bass", "HARP", false, true, true,
			List.of(note(30, 0L), note(34, 480L), note(37, 960L))));

		for (ComposerProject.OctaveShifting mode : ComposerProject.OctaveShifting.values()) {
			ComposerProject converted =
				song.convertToMinecraft(480, false, 0, false, mode, true).project();
			assertEquals(1, converted.layers().size(), mode + " needed no split");
			assertTrue(everyNoteInRange(converted), mode + " left a note out of range");
		}
	}

	/**
	 * The mode's whole claim: moving the layer first costs one layer where moving notes costs two.
	 *
	 * <p>Four notes inside one octave, sitting a fifth below the window. Per note, the two lowest
	 * need an octave and the two highest do not, so the layer splits. Moved as a unit, one octave
	 * takes all four in, and the notes keep their intervals -- which is the musical difference, not
	 * merely the layer count: split, the part is torn an octave apart down its middle.</p>
	 */
	@Test
	void shiftingTheLayerFirstAvoidsASplitThatShiftingNotesCannot() {
		ComposerProject song = songOf(new Layer("Lead", "HARP", false, true, true,
			List.of(note(47, 0L), note(50, 480L), note(54, 960L), note(57, 1440L))));

		ComposerProject perNote = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.NOTES_ONLY, true).project();
		ComposerProject perLayer = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.LAYER_THEN_NOTES, true).project();

		assertEquals(2, perNote.layers().size(), "47 and 50 need an octave, 54 and 57 do not");
		assertEquals(1, perLayer.layers().size(), "one octave takes the whole part in");
		assertEquals(List.of(59, 62, 66, 69), pitches(perLayer.layers().getFirst()),
			"and the intervals between the four notes survive");
		assertTrue(perLayer.layers().getFirst().notes().stream().allMatch(NoteEvent::isBuildable));
	}

	/** A part already in range is not moved to make a point. */
	@Test
	void aLayerAlreadyInRangeIsLeftWhereItIs() {
		ComposerProject song = songOf(new Layer("Lead", "HARP", false, true, true,
			List.of(note(60, 0L), note(64, 480L), note(67, 960L))));

		ComposerProject converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.LAYER_THEN_NOTES, true).project();

		assertEquals(List.of(60, 64, 67), pitches(converted.layers().getFirst()),
			"nought out of range at nought shift, and ties go to the smaller move");
		assertEquals("Lead", converted.layers().getFirst().name(), "so the name is untouched too");
	}

	/**
	 * Whatever one octave cannot reach still moves note by note, so the result is always buildable.
	 *
	 * <p>The guarantee that makes every mode safe to leave on: the last step of each is a per-note
	 * octave shift, aimed at a window at least 25 semitones wide, so no pitch class can fail. In
	 * range means in range <em>for the layer the note ended on</em> -- which for the melodic mode
	 * is six octaves of brackets rather than the harp window, and is why this asks the layer.</p>
	 */
	@Test
	void notesTheLayerShiftCannotReachAreStillBroughtIntoRange() {
		ComposerProject song = songOf(new Layer("Piano", "HARP", false, true, true,
			List.of(note(36, 0L), note(60, 480L), note(84, 960L), note(96, 1440L))));

		for (ComposerProject.OctaveShifting mode : ComposerProject.OctaveShifting.values()) {
			ComposerProject converted =
				song.convertToMinecraft(480, false, 0, false, mode, true).project();
			assertTrue(everyNoteInRange(converted),
				mode + " left something its layer cannot reach");
		}
	}

	/**
	 * Split into melodic's whole claim: the notes move layer, and not one of them moves pitch.
	 *
	 * <p>Both ends of the part go to the same companion, which is the difference from the
	 * transposing modes -- there, low and high are two layers named for the two octaves they took.
	 * Here there is no octave to name, so there is one layer, named for what it is.</p>
	 */
	@Test
	void splitIntoMelodicMovesStraysToOneLayerWithoutRetuningThem() {
		ComposerProject song = songOf(new Layer("Piano", "HARP", false, true, true,
			List.of(note(36, 0L), note(60, 480L), note(84, 960L))));

		ComposerProject.MinecraftConversion converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC, true);
		ComposerProject result = converted.project();

		assertEquals(List.of("Piano", "Piano (melodic)"), names(result),
			"one companion, catching both ends");
		assertEquals(List.of(60), pitches(result.layers().getFirst()), "the window keeps its own");
		assertEquals(List.of(36, 84), pitches(result.layers().get(1)),
			"and the strays arrive at the pitch they were written at");
		assertNull(result.layers().getFirst().split(), "the source layer is still one instrument");
		assertEquals(List.of("BASS", "FLUTE", "BELL"), voiceNames(result.layers().get(1)),
			"the companion has every tier its two notes sound on, and no other");
		assertTrue(everyNoteInRange(result));
		assertEquals(0, converted.shiftedNotes(), "nothing was retuned");
		assertEquals(2, converted.melodicNotes(), "and the move is counted where it happened");
		assertEquals(1, converted.melodicLayers(), "onto the one layer that was added");
	}

	/**
	 * A part written wholly outside the window becomes the melodic layer rather than emptying out.
	 *
	 * <p>The split would otherwise leave an empty layer beside a full one for a single part, which
	 * is two rows saying what one says. It keeps its name for the same reason: nothing was taken
	 * off it, so there is no companion to tell it apart from.</p>
	 */
	@Test
	void aPartWhollyOutOfTheWindowBecomesTheMelodicLayerInPlace() {
		ComposerProject song = songOf(new Layer("Bass", "HARP", false, true, true,
			List.of(note(30, 0L), note(34, 480L), note(37, 960L))));

		ComposerProject result = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC, true).project();

		assertEquals(List.of("Bass"), names(result), "one part, one layer, its own name");
		assertEquals(List.of(30, 34, 37), pitches(result.layers().getFirst()),
			"and a bass line stays where a bass line was written");
		assertEquals(List.of("BASS"), voiceNames(result.layers().getFirst()),
			"a bass line needs the bass tier and nothing else");
		assertTrue(everyNoteInRange(result));
	}

	/**
	 * Six octaves is not all of MIDI, so the last step is still an octave shift.
	 *
	 * <p>What makes this mode as safe to leave on as the other two: a note under F#1 or over F#7 is
	 * outside every bracket, and moves by whole octaves until one covers it. Whole octaves, because
	 * a part moved by anything else is in a different key from the rest of the song.</p>
	 */
	@Test
	void notesOutsideEvenTheBracketsAreStillOctaveShiftedUnderThem() {
		ComposerProject song = songOf(new Layer("Piano", "HARP", false, true, true,
			List.of(note(18, 0L), note(60, 480L), note(120, 960L))));

		ComposerProject.MinecraftConversion converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC, true);
		Layer companion = converted.project().layers().get(1);

		assertTrue(everyNoteInRange(converted.project()), "both ends were brought under a bracket");
		assertEquals(List.of(30, 96), pitches(companion).stream().sorted().toList(),
			"18 up one octave; 120 down two, because 108 is over the top of the bell");
		assertEquals(2, converted.shiftedNotes(), "and these two really were retuned");
		assertEquals(2, converted.melodicNotes());
	}

	/** A part the window already holds gets no companion and is not made a split. */
	@Test
	void aPartAlreadyInRangeGetsNoMelodicCompanion() {
		ComposerProject song = songOf(new Layer("Lead", "HARP", false, true, true,
			List.of(note(60, 0L), note(64, 480L), note(67, 960L))));

		ComposerProject.MinecraftConversion converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC, true);

		assertEquals(List.of("Lead"), names(converted.project()));
		assertNull(converted.project().layers().getFirst().split());
		assertEquals(0, converted.melodicNotes());
		assertEquals(0, converted.addedLayers());
	}

	/**
	 * A layer that is already split is still fitted to its own brackets, whatever the mode says.
	 *
	 * <p>The mode answers what to do with a layer that cannot reach its notes. A split layer that
	 * already reaches them is not that layer, and giving it a melodic companion would move notes
	 * off a bracket that covers them onto one that covers them no better.</p>
	 */
	@Test
	void aLayerThatIsAlreadySplitIsLeftToItsOwnBrackets() {
		ComposerProject song = songOf(new Layer("Keys", "HARP", false, true, true,
			List.of(note(36, 0L), note(60, 480L), note(84, 960L)),
			ComposerProject.Split.melodic()));

		ComposerProject.MinecraftConversion converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC, true);

		assertEquals(List.of("Keys"), names(converted.project()));
		assertEquals(List.of(36, 60, 84), pitches(converted.project().layers().getFirst()));
		assertEquals(0, converted.melodicNotes(), "it was already the layer this mode makes");
	}

	/**
	 * A drum layer is folded into the window like always, not handed a melodic companion.
	 *
	 * <p>The exclusion sound effects get, one step further along. A snare's 25 pitches are 25
	 * timbres of one drum, so the melodic tiers have nothing to say about where one belongs --
	 * moving a snare into the bass register does not make it a lower snare, it makes it a kick.
	 * The same notes on a harp are the control: there, the companion is the whole point.</p>
	 */
	@Test
	void aDrumLayerIsShiftedIntoTheWindowRatherThanGivenAMelodicLayer() {
		List<NoteEvent> line = List.of(note(36, 0L), note(60, 480L), note(84, 960L));
		ComposerProject drums = songOf(new Layer("Snare", "SNARE", false, true, true, line));
		ComposerProject harp = songOf(new Layer("Lead", "HARP", false, true, true, line));

		for (ComposerProject.OctaveShifting mode : List.of(
				ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC,
				ComposerProject.OctaveShifting.CONVERT_TO_MELODIC)) {
			ComposerProject.MinecraftConversion beaten =
				drums.convertToMinecraft(480, false, 0, false, mode, true);

			assertEquals(0, beaten.melodicNotes(), mode + " sent a drum to a bell");
			assertTrue(beaten.project().layers().stream().allMatch(layer -> layer.split() == null),
				mode + " made a drum layer a split");
			assertTrue(beaten.project().layers().stream()
					.flatMap(layer -> layer.notes().stream())
					.allMatch(NoteEvent::isBuildable),
				mode + " left a drum outside the window, which is where a drum layer belongs");
			assertTrue(harp.convertToMinecraft(480, false, 0, false, mode, true).melodicNotes() > 0,
				"the same notes on a pitched instrument do go melodic under " + mode);
		}
	}

	/**
	 * Convert to melodic takes the whole part and adds nothing.
	 *
	 * <p>The layer count is the point. Split into melodic leaves the notes the window can hold on
	 * their own instrument and gives the strays a companion, so the part ends up as two rows; this
	 * gives the part the layer it needed and stops there. The part's own instrument keeps its tier
	 * on the split either way, and the two are set side by side here rather than apart, because
	 * choosing between them is choosing between exactly this.</p>
	 */
	@Test
	void convertToMelodicTakesTheWholePartWhereSplitTakesOnlyTheStrays() {
		ComposerProject song = songOf(new Layer("Piano", "PLING", false, true, true,
			List.of(note(36, 0L), note(60, 480L), note(84, 960L))));

		ComposerProject.MinecraftConversion split = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.SPLIT_INTO_MELODIC, true);
		ComposerProject.MinecraftConversion whole = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.CONVERT_TO_MELODIC, true);

		assertEquals(List.of("Piano", "Piano (melodic)"), names(split.project()));
		assertEquals(1, split.addedLayers(), "one companion for the two strays");

		assertEquals(List.of("Piano"), names(whole.project()), "one part, still one layer");
		assertEquals(0, whole.addedLayers(), "which is the whole of why you would pick it");
		assertEquals(List.of(36, 60, 84), pitches(whole.project().layers().getFirst()),
			"and every note is where it was written, the in-range one included");
		assertEquals(List.of("BASS", "GUITAR", "PLING", "FLUTE", "BELL"),
			voiceNames(whole.project().layers().getFirst()),
			"the part keeps PLING on its own tier, in place of the harp");
		assertEquals(3, whole.melodicNotes(), "all three are on the split now, not just the strays");
		assertEquals(1, whole.melodicLayers(), "and the layer count is what says so");
		assertEquals(0, whole.shiftedNotes());
		assertTrue(everyNoteInRange(whole.project()));
	}

	/**
	 * Convert to melodic answers a part that cannot be built, not every part in the song.
	 *
	 * <p>A layer already inside the window is left on its instrument. Converting it would trade a
	 * timbre somebody chose for a fix to a problem it does not have -- and, because the harp window
	 * sits under three of the melodic brackets, would double most of its notes for nothing.</p>
	 */
	@Test
	void convertToMelodicLeavesAPartThatAlreadyFits() {
		ComposerProject song = songOf(
			new Layer("Lead", "PLING", false, true, true,
				List.of(note(60, 0L), note(64, 480L), note(67, 960L))),
			new Layer("Bass", "HARP", false, true, true,
				List.of(note(36, 0L), note(40, 480L))));

		ComposerProject.MinecraftConversion converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.CONVERT_TO_MELODIC, true);

		assertNull(converted.project().layers().getFirst().split(),
			"the part that fits keeps its instrument");
		assertEquals(List.of(60, 64, 67), pitches(converted.project().layers().getFirst()));
		assertEquals(List.of("BASS"), voiceNames(converted.project().layers().get(1)),
			"and the part that does not is converted, on its own");
		assertEquals(2, converted.melodicNotes());
		assertEquals(1, converted.melodicLayers(), "one layer of the two needed it");
		assertEquals(0, converted.addedLayers());
	}

	/** A counted voice keeps its count on the melodic layer: three chimes stay three chimes. */
	@Test
	void convertToMelodicKeepsTheLayersCounts() {
		ComposerProject song = songOf(new Layer("Ice", "CHIME", false, true, true,
			List.of(note(90, 0L), note(100, 480L)))
			.withMix(List.of(ComposerProject.Split.Voice.fullRange("CHIME").withCount(3))));

		Layer converted = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.CONVERT_TO_MELODIC, true).project().layers().getFirst();

		assertEquals(List.of("FLUTE", "CHIME"), voiceNames(converted),
			"chime takes the bell's tier, and the flute sounds the note they share");
		assertEquals(3, converted.countOf("CHIME"), "at the count the layer had");
		assertEquals(1, converted.countOf("FLUTE"), "and a default tier sounds once");
	}

	private static List<String> voiceNames(Layer layer) {
		return layer.split().voices().stream().map(ComposerProject.Split.Voice::instrument).toList();
	}

	/** Off, the octaves land in the layer they came from and nothing is split off it. */
	@Test
	void withoutSplittingATransposedNoteStaysOnItsOwnLayer() {
		ComposerProject song = songOf(new Layer("Piano", "HARP", false, true, true,
			List.of(note(36, 0L), note(60, 480L), note(96, 960L))));

		ComposerProject split = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.NOTES_ONLY, true).project();
		ComposerProject whole = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.NOTES_ONLY, false).project();

		assertTrue(split.layers().size() > 1, "three octaves apart, the split is what happens today");
		assertEquals(1, whole.layers().size());
		assertEquals("Piano", whole.layers().getFirst().name());
		assertEquals(3, whole.layers().getFirst().notes().size(), "and every note survived");
	}

	/**
	 * Two notes an octave apart become one note, and Convert says how many did.
	 *
	 * <p>With the split on this is a duplicate layer being dropped and is already reported. Off,
	 * there is no second layer to drop, so the same dedupe happens a note at a time inside the
	 * layer -- which would be silent if it were not counted.</p>
	 */
	@Test
	void mergedDuplicatesAreCounted() {
		ComposerProject song = songOf(new Layer("Snare", "SNARE", false, true, true,
			List.of(note(48, 0L), note(60, 0L))));

		ComposerProject.MinecraftConversion whole = song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.NOTES_ONLY, false);

		assertEquals(1, whole.project().layers().getFirst().notes().size(),
			"one pitch at one tick is one note");
		assertEquals(1, whole.mergedIntoExisting(), "and the one that went is counted");
		assertEquals(0, song.convertToMinecraft(480, false, 0, false,
			ComposerProject.OctaveShifting.NOTES_ONLY, true).mergedIntoExisting(),
			"with the split on it is a dropped duplicate layer instead, counted there");
	}

	/**
	 * A sound-effect layer is not transposed, is not split, and is not renamed.
	 *
	 * <p>{@code toSteps} does not filter an unpitched layer by range, so every note on one builds
	 * wherever it is drawn and the row a hit sits on is only somewhere to put it. A shift therefore
	 * moved nothing while the split it caused was pure cost. The same notes on a harp are the
	 * control: there, both the shift and the split are the whole point.</p>
	 */
	@Test
	void soundEffectLayersAreLeftAlone() {
		List<NoteEvent> line = List.of(note(20, 0L), note(23, 480L), note(96, 960L));
		ComposerProject effects = songOf(new Layer("Door", "FX_OAK_DOOR", false, true, true, line));
		ComposerProject harp = songOf(new Layer("Lead", "HARP", false, true, true, line));

		for (ComposerProject.OctaveShifting mode : ComposerProject.OctaveShifting.values()) {
			ComposerProject.MinecraftConversion converted =
				effects.convertToMinecraft(480, false, 0, false, mode, true);
			assertEquals(List.of("Door"), names(converted.project()), mode + " split a door");
			assertEquals(List.of(20, 23, 96), pitches(converted.project().layers().getFirst()),
				mode + " retuned a block that has no pitch");
			assertEquals(0, converted.shiftedNotes(), mode + " counted a move that did not happen");
		}
		assertTrue(harp.convertToMinecraft(480, false, 0, false,
				ComposerProject.OctaveShifting.NOTES_ONLY, true).project().layers().size() > 1,
			"the same notes on a pitched instrument do split");
	}

	/**
	 * What gets built is what you can hear: active layers, and nothing else.
	 *
	 * <p>There used to be a flag of its own for this, so the composer had two independent signal
	 * paths -- preview played what was unmuted, a build placed what was dotted, and nothing tied
	 * them together. Pressing Space was not a preview of the build, and there was no way to hear
	 * what would be built. The flag stays on the record so older song files still load; it decides
	 * nothing.</p>
	 */
	@Test
	void onlyAudibleLayersGoIntoTheBuild() {
		ComposerProject song = songOf(
			new Layer("Active", "HARP", false, true, true, List.of(note(60, 0L))),
			new Layer("Muted", "HARP", true, true, true, List.of(note(62, 0L))),
			new Layer("Hidden", "HARP", false, true, false, List.of(note(64, 0L))),
			// The old flag says leave it out; audible says build it. Audible wins now.
			new Layer("Undotted", "HARP", false, false, true, List.of(note(65, 0L))));

		assertEquals(List.of(true, false, false, true),
			song.layers().stream().map(Layer::inBuild).toList());
		assertEquals(List.of("Active", "Undotted"),
			song.toSequenceTracks(Set.of(), false).stream()
				.map(com.midicraft.client.MidicraftConfig.SequenceTrack::name).toList());
	}

	/** An explicit set of layers still overrides it, which is how a probe builds one part alone. */
	@Test
	void namingLayersOutrightIgnoresWhetherTheyAreAudible() {
		ComposerProject song = songOf(
			new Layer("Active", "HARP", false, true, true, List.of(note(60, 0L))),
			new Layer("Muted", "HARP", true, true, true, List.of(note(62, 0L))));

		assertEquals(List.of("Muted"),
			song.toSequenceTracks(Set.of(1), false).stream()
				.map(com.midicraft.client.MidicraftConfig.SequenceTrack::name).toList());
	}

	/**
	 * Pasted layers land where they were aimed, under fresh note ids, keeping their own names.
	 *
	 * <p>Fresh ids for the reason a duplicate needs them: layers are selected and moved by note id,
	 * and a paste sharing them would be a second view of the layer it came from rather than a new
	 * one. Names are left alone, which reads oddly for a copy and is exactly right for a cut being
	 * moved -- and at the moment it lands the clipboard cannot tell those apart.</p>
	 */
	@Test
	void pastedLayersArriveWhereTheyWereAimedWithTheirOwnNotes() {
		ComposerProject song = songOf(
			layer("A", "HARP", 60),
			layer("B", "BASS", 40),
			layer("C", "HARP", 62));
		List<Layer> taken = List.of(song.layers().getFirst(), song.layers().get(1));

		ComposerProject pasted = song.withLayersInserted(2, taken);

		assertEquals(List.of("A", "B", "A", "B", "C"), names(pasted));
		assertEquals("BASS", pasted.layers().get(3).instrument(), "and their instruments came too");
		Set<Long> before = song.layers().stream()
			.flatMap(current -> current.notes().stream())
			.map(NoteEvent::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		assertTrue(pasted.layers().get(2).notes().stream().noneMatch(note -> before.contains(note.id())),
			"the arriving notes answer to their own ids");
		assertEquals(startTicks(song.layers().getFirst()), startTicks(pasted.layers().get(2)),
			"and sit where they did");
	}

	/** Landing past either end is clamped, and a paste that would pass the cap does not happen. */
	@Test
	void insertingLayersIsClampedAndAllOrNothing() {
		ComposerProject song = songOf(layer("A", "HARP", 60));
		List<Layer> taken = List.of(song.layers().getFirst());

		assertEquals(List.of("A", "A"), names(song.withLayersInserted(99, taken)), "past the end");
		assertEquals(List.of("A", "A"), names(song.withLayersInserted(-5, taken)), "past the start");
		assertEquals(song, song.withLayersInserted(0, List.of()), "nothing to paste is no edit");

		List<Layer> tooMany = new ArrayList<>();
		for (int index = 0; index < ComposerProject.MAX_LAYERS; index++) {
			tooMany.add(song.layers().getFirst());
		}
		assertEquals(song, song.withLayersInserted(0, tooMany),
			"one over the cap and none of them arrive");
	}

	/**
	 * Whether every note is one its own layer can build.
	 *
	 * <p>Deliberately asks the layer rather than {@link NoteEvent#isBuildable}, which only knows
	 * the harp window. A note at F#2 is out of range on a harp layer and squarely inside a bass
	 * voice on a split one, and the melodic mode's whole output is the second kind.</p>
	 */
	private static boolean everyNoteInRange(ComposerProject song) {
		return song.layers().stream()
			.allMatch(layer -> layer.notes().stream().noneMatch(layer::outOfRange));
	}

	private static List<Integer> pitches(Layer layer) {
		return layer.notes().stream().map(NoteEvent::midiNote).toList();
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
