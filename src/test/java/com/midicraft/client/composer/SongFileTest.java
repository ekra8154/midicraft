package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code .midicraft} file, and the one-time conversion of a library of {@code .json} songs. */
class SongFileTest {
	private static ComposerProject song() {
		ComposerProject song = ComposerProject.empty("Fireflies");
		return song.withLayer(0, song.layers().get(0).withNotes(List.of(
			new ComposerProject.NoteEvent(1, 60, 0L, 120L, 100),
			new ComposerProject.NoteEvent(2, 64, 480L, 120L, 90))));
	}

	private static final SongFile.Details DETAILS = new SongFile.Details("An Arranger", "Owl City",
		"A cover.", "MIDI: Fireflies.mid", "2026-10-01T10:00:00Z", "2026-10-09T10:00:00Z", "1.0.0",
		"26.3");

	@Test
	void aSongAndItsDetailsComeBackAsTheyWent(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("fireflies.midicraft");
		SongFile.write(file, song(), DETAILS);

		byte[] bytes = Files.readAllBytes(file);
		assertEquals(0x1F, bytes[0] & 0xFF, "gzip");
		assertEquals(0x8B, bytes[1] & 0xFF, "gzip");
		SongFile.Loaded loaded = SongFile.read(file);
		assertEquals(SongFile.canonical(song()), loaded.song());
		assertEquals(DETAILS, loaded.details());
	}

	/** What the library stored before: the bare song, read as one with nothing to say about it. */
	@Test
	void anOldJsonSongReadsWithNoDetails(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("fireflies.json");
		Files.writeString(file, new Gson().toJson(song()));
		SongFile.Loaded loaded = SongFile.read(file);
		assertEquals(SongFile.canonical(song()), loaded.song());
		assertEquals(SongFile.Details.NONE, loaded.details());
	}

	/** Not gzipped, as one written or mended by hand would be. */
	@Test
	void anUncompressedMidicraftFileReads(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("fireflies.midicraft");
		Files.writeString(file, "{\"midicraft\":{\"format\":1,\"author\":\"someone\"},\"song\":"
			+ new Gson().toJson(song()) + "}", StandardCharsets.UTF_8);
		SongFile.Loaded loaded = SongFile.read(file);
		assertEquals(SongFile.canonical(song()), loaded.song());
		assertEquals("someone", loaded.details().author());
	}

	@Test
	void aFileFromANewerMidicraftIsRefusedAndSaysSo(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("future.midicraft");
		Files.writeString(file, "{\"midicraft\":{\"format\":" + (SongFile.FORMAT + 1)
			+ "},\"song\":" + new Gson().toJson(song()) + "}");
		IOException refused = assertThrows(IOException.class, () -> SongFile.read(file));
		assertTrue(refused.getMessage().contains("newer midicraft"), refused.getMessage());
	}

	@Test
	void writingStampsTheDatesAndCopyingForgetsThem() {
		Instant now = Instant.parse("2026-10-10T12:00:00Z");
		SongFile.Details first = SongFile.Details.importedFrom("MIDI: a.mid").written(now, "1.0.0",
			"26.3");
		assertEquals("2026-10-10T12:00:00Z", first.created());
		assertEquals("2026-10-10T12:00:00Z", first.modified());
		assertEquals("MIDI: a.mid", first.source());

		SongFile.Details again = first.written(now.plusSeconds(60), "1.0.1", "26.3");
		assertEquals("2026-10-10T12:00:00Z", again.created(), "created is kept");
		assertEquals("2026-10-10T12:01:00Z", again.modified());
		assertEquals("1.0.1", again.mod());

		SongFile.Details copy = DETAILS.copied();
		assertEquals("Owl City", copy.credits());
		assertEquals("MIDI: Fireflies.mid", copy.source());
		assertNull(copy.created());
		assertNull(copy.modified());

		assertNull(DETAILS.withOwn(" ", "", null).author(), "blank is absent");
	}

	/**
	 * A library of {@code .json} songs: each becomes a {@code .midicraft} keeping its date, the
	 * original goes into {@code legacy-json} and is never deleted, a stale copy beside an existing
	 * {@code .midicraft} is set aside without touching it, and an unreadable song stays put.
	 */
	@Test
	void anOldLibraryIsConvertedOnceAndNothingIsLost(@TempDir Path songs) throws IOException {
		FileTime then = FileTime.from(Instant.parse("2025-05-05T05:05:05Z"));
		Path old = songs.resolve("fireflies.json");
		Files.writeString(old, new Gson().toJson(song()));
		Files.setLastModifiedTime(old, then);
		Files.writeString(songs.resolve("space-song.json"), new Gson().toJson(song()));
		SongFile.write(songs.resolve("space-song.midicraft"), song(), DETAILS);
		Files.writeString(songs.resolve("broken.json"), "{not a song");

		List<String> unreadable = SongFile.convertLegacy(songs, "1.0.0", "26.3");

		assertEquals(List.of("broken"), unreadable);
		SongFile.Loaded converted = SongFile.read(songs.resolve("fireflies.midicraft"));
		assertEquals(SongFile.canonical(song()), converted.song());
		assertEquals("2025-05-05T05:05:05Z", converted.details().created());
		assertEquals("26.3", converted.details().minecraft());
		assertEquals(then, Files.getLastModifiedTime(songs.resolve("fireflies.midicraft")));
		assertFalse(Files.exists(old));
		assertTrue(Files.exists(songs.resolve("legacy-json").resolve("fireflies.json")));
		assertEquals(DETAILS, SongFile.read(songs.resolve("space-song.midicraft")).details(),
			"an existing .midicraft is left alone");
		assertTrue(Files.exists(songs.resolve("legacy-json").resolve("space-song.json")));
		assertTrue(Files.exists(songs.resolve("broken.json")));

		// Again: nothing left to do but the broken one.
		assertEquals(List.of("broken"), SongFile.convertLegacy(songs, "1.0.0", "26.3"));
	}
}
