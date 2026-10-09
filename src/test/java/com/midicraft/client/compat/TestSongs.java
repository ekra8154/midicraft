package com.midicraft.client.compat;

import com.google.gson.Gson;
import com.midicraft.client.composer.SongFile;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The library's songs as the probes read them, whichever form they are stored in.
 *
 * <p>The probes were written against a library of plain {@code .json} songs, read by handing a file
 * reader to Gson. The game now stores {@code .midicraft} -- gzipped, with a header in front of the
 * song -- and converts an old library the first time it starts. So every song a probe opens goes
 * through {@link #reader}, which hands back the song's plain JSON either way: a {@code .midicraft}
 * unwrapped, and anything else exactly as it is on disk, which is also why it is safe on the
 * settings file and every other file a probe reads.</p>
 */
public final class TestSongs {
	private TestSongs() {
	}

	/** The development client's song library, relative to the project root the tests run from. */
	static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** Whether a file is a song, in either form. */
	public static boolean isSong(Path file) {
		return SongFile.isSongFile(file.getFileName().toString());
	}

	/** A song's file name in the library: the {@code .midicraft} if there is one, else the old name. */
	public static String fileName(String id) {
		return Files.exists(SONGS.resolve(id + SongFile.EXTENSION))
			? id + SongFile.EXTENSION : id + SongFile.LEGACY_EXTENSION;
	}

	/**
	 * The file's text, with a song file reduced to the song.
	 *
	 * <p>Only a file that is gzip or carries the {@code midicraft} header is touched; a bare song, the
	 * settings file and anything else come back as they are.</p>
	 */
	public static BufferedReader reader(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		boolean gzip = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
		String text = gzip ? null : new String(bytes, StandardCharsets.UTF_8);
		if (gzip || text.contains("\"midicraft\"") && text.contains("\"song\"")) {
			try {
				return new BufferedReader(new StringReader(
					new Gson().toJson(SongFile.read(file).song())));
			} catch (IOException notASongFile) {
				if (gzip) {
					throw notASongFile;
				}
			}
		}
		return new BufferedReader(new StringReader(text));
	}
}
