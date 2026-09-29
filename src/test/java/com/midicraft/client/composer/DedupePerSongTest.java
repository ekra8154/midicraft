package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Merging duplicate notes is the song's own choice: saved with it, and kept through its edits. */
class DedupePerSongTest {
	private static ComposerProject song() {
		return new ComposerProject("Song", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Lead", "HARP", false, true, true, List.of(new NoteEvent(1, 60, 0, 120, 90)))),
			0, 2L, 0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	/** A song that never said merges, and saves exactly as a song did before it could say. */
	@Test
	void aSongThatNeverSaidMergesAndSavesNothingNew() {
		Gson gson = new Gson();
		assertTrue(song().dedupesIdentical());
		assertFalse(gson.toJson(song()).contains("dedupeIdenticalNotes"));
		assertTrue(song().withDedupeIdenticalNotes(true).dedupesIdentical());
		assertFalse(gson.toJson(song().withDedupeIdenticalNotes(true)).contains("dedupeIdenticalNotes"),
			"on is the default, so it is not written either");
	}

	/** Off is written, read back, and survives the edits that rebuild the song. */
	@Test
	void offSurvivesTheDiskAndTheEdits() {
		Gson gson = new Gson();
		ComposerProject unmerged = song().withDedupeIdenticalNotes(false);
		ComposerProject loaded = gson.fromJson(gson.toJson(unmerged), ComposerProject.class);
		assertFalse(loaded.dedupesIdentical());

		assertFalse(unmerged.withName("Renamed").dedupesIdentical());
		assertFalse(unmerged.withTempo(400_000).dedupesIdentical());
		assertFalse(unmerged.withEndTick(4000L).dedupesIdentical());
		assertFalse(unmerged.addNote(0, 64, 240L, 120L).dedupesIdentical());
		assertFalse(unmerged.convertToMinecraft(120, false).project().dedupesIdentical());
		assertEquals(unmerged.layers(), unmerged.withDedupeIdenticalNotes(true).layers(),
			"and turning it back on touches no note");
	}
}
