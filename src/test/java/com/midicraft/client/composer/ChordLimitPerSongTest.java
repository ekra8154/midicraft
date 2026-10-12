package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The most notes a chord may have is the song's own: saved with it, and kept through its edits. */
class ChordLimitPerSongTest {
	private static ComposerProject song() {
		return new ComposerProject("Song", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Lead", "HARP", false, true, true, List.of(new NoteEvent(1, 60, 0, 120, 90)))),
			0, 2L, 0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	/** A song that never said is held to what a build can place, and saves nothing new. */
	@Test
	void aSongThatNeverSaidIsHeldToTheBuildAndSavesNothingNew() {
		Gson gson = new Gson();
		assertEquals(SongAnalysis.MAX_SIMULTANEOUS_NOTES, song().maxNotesPerChord());
		assertFalse(gson.toJson(song()).contains("maxChordNotes"));
		ComposerProject atTheBuild = song().withMaxNotesPerChord(SongAnalysis.MAX_SIMULTANEOUS_NOTES);
		assertNull(atTheBuild.maxChordNotes(), "the build's own limit is the default, so not written");
		assertFalse(gson.toJson(atTheBuild).contains("maxChordNotes"));
	}

	/** Kept between one note and the build's thirty, whatever a hand-edited file says. */
	@Test
	void theLimitStaysBetweenOneAndThirty() {
		assertEquals(ComposerProject.MIN_CHORD_NOTES, song().withMaxNotesPerChord(0).maxNotesPerChord());
		assertEquals(ComposerProject.MIN_CHORD_NOTES, song().withMaxNotesPerChord(-5).maxNotesPerChord());
		assertEquals(SongAnalysis.MAX_SIMULTANEOUS_NOTES, song().withMaxNotesPerChord(99).maxNotesPerChord());
	}

	/** Thinning is a distance under the song's limit, never thinning a chord to nothing. */
	@Test
	void thinningFollowsTheSongsLimit() {
		assertEquals(25, ChordThinner.targetUnder(SongAnalysis.MAX_SIMULTANEOUS_NOTES, 5),
			"the old default of twenty-five, for a song left at thirty");
		assertEquals(24, ChordThinner.targetUnder(SongAnalysis.MAX_SIMULTANEOUS_NOTES, 6));
		assertEquals(4, ChordThinner.targetUnder(4, 0));
		assertEquals(1, ChordThinner.targetUnder(1, 6), "never under one note");
	}

	/** Down to one note a chord, which is what packing wants, where a doubling can give way. */
	@Test
	void theThinnerReachesOneNoteAChord() {
		ComposerProject doubled = new ComposerProject("Song", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Lead", "HARP", false, true, true, List.of(new NoteEvent(1, 60, 0, 120, 90))),
				new Layer("Double", "HARP", false, true, true, List.of(new NoteEvent(2, 60, 0, 120, 60)))),
			0, 3L, 0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
		ChordThinner.Result thinned = ChordThinner.thin(doubled, 1, false, java.util.Set.of(0, 1));
		assertEquals(1, thinned.noteIds().size(), "one of the two harps on the same pitch goes");
		assertEquals(0, thinned.chordsStillOver());
	}

	/** A limit is written, read back, and survives the edits that rebuild the song. */
	@Test
	void aLimitSurvivesTheDiskAndTheEdits() {
		Gson gson = new Gson();
		ComposerProject packed = song().withMaxNotesPerChord(1);
		ComposerProject loaded = SongFile.canonical(gson.fromJson(gson.toJson(packed), ComposerProject.class));
		assertEquals(1, loaded.maxNotesPerChord());

		assertEquals(1, packed.withName("Renamed").maxNotesPerChord());
		assertEquals(1, packed.withTempo(400_000).maxNotesPerChord());
		assertEquals(1, packed.withEndTick(4000L).maxNotesPerChord());
		assertEquals(1, packed.withDedupeIdenticalNotes(false).maxNotesPerChord());
		assertEquals(1, packed.addNote(0, 64, 240L, 120L).maxNotesPerChord());
		assertEquals(1, packed.convertToMinecraft(120, false).project().maxNotesPerChord());
		assertTrue(packed.withDedupeIdenticalNotes(false).withMaxNotesPerChord(1).dedupeIdenticalNotes() != null,
			"and the merging choice survives it in turn");
		assertEquals(packed.layers(), song().layers(), "and setting it touches no note");
	}
}
