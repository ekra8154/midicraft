package com.midicraft.client.composer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A song as a file: {@code .midicraft}, which is what the library stores and what gets shared.
 *
 * <p>Gzipped JSON, the way a {@code .litematic} is gzipped NBT: a {@code midicraft} header saying
 * what the file is and where it came from, and the {@code song} itself, exactly as the composer
 * keeps it. Compressed because songs are big and repetitive -- Guardian is two and a half
 * megabytes of JSON and a hundred and seventy kilobytes of file -- and a song is something people
 * download.</p>
 *
 * <p>Reads more than it writes. A {@code .midicraft} that is plain JSON rather than gzip is read the
 * same, so one written or fixed by hand still opens; and a bare song with no header -- the
 * {@code .json} the library used to store -- is read as a song with no details, which is how old
 * libraries are converted and how the probes still read the songs they were written against.</p>
 *
 * <p>No game classes, so it can be used without one running.</p>
 */
public final class SongFile {
	private SongFile() {
	}

	public static final String EXTENSION = ".midicraft";
	/** What the library stored before this, still read wherever a song is read. */
	public static final String LEGACY_EXTENSION = ".json";
	/**
	 * The layout this writes. Raised when a reader of the old layout would get the song wrong; a
	 * file with a higher number than this is refused rather than half read.
	 */
	public static final int FORMAT = 1;
	/** Where a library's {@code .json} songs go once they have been converted. */
	public static final String LEGACY_FOLDER = "legacy-json";

	/** Compact on purpose: the file is compressed anyway, and nobody reads it in that form. */
	private static final Gson GSON = new Gson();

	/**
	 * What a song file says about the song, as opposed to the song.
	 *
	 * <p>Every field may be {@code null}. The first three are the player's own, set in the composer
	 * and all optional; the rest are filled in as the file is written and imported.</p>
	 *
	 * @param author who arranged it
	 * @param credits the original song and whose MIDI it was made from
	 * @param description anything else
	 * @param source what it was imported from, such as {@code MIDI: Fireflies.mid}, kept from the
	 *     first import on
	 * @param created when the song was first saved, as an ISO-8601 instant
	 * @param modified when it was last saved, likewise. On the file and not only on the disk, because
	 *     a file's own date does not survive being uploaded and downloaded
	 * @param mod the midicraft version that last wrote it
	 * @param minecraft the Minecraft version it was last written under
	 */
	public record Details(String author, String credits, String description, String source,
			String created, String modified, String mod, String minecraft) {
		public static final Details NONE = new Details(null, null, null, null, null, null, null,
			null);

		/** Details for a song read from somewhere else, which knows only where. */
		public static Details importedFrom(String source) {
			return NONE.withSource(source);
		}

		public Details withSource(String value) {
			return new Details(author, credits, description, value, created, modified, mod,
				minecraft);
		}

		/** The three the player sets. Blank is stored as absent. */
		public Details withOwn(String author, String credits, String description) {
			return new Details(blankToNull(author), blankToNull(credits), blankToNull(description),
				source, created, modified, mod, minecraft);
		}

		/**
		 * Stamped as it is written: created if it never was, modified now, and the versions doing
		 * the writing.
		 */
		Details written(Instant now, String modVersion, String minecraftVersion) {
			String at = now.toString();
			return new Details(author, credits, description, source, created == null ? at : created,
				at, modVersion, minecraftVersion);
		}

		/** Kept when a song is copied: who made it and where it came from, not its dates. */
		public Details copied() {
			return new Details(author, credits, description, source, null, null, null, null);
		}
	}

	/** A song and what its file says about it. */
	public record Loaded(ComposerProject song, Details details) {
	}

	/** The header as stored. Absent fields stay absent; Gson leaves nulls out. */
	private static final class Header {
		Integer format;
		WrittenBy writtenBy;
		String created;
		String modified;
		String source;
		String author;
		String credits;
		String description;
	}

	private static final class WrittenBy {
		String mod;
		String minecraft;
	}

	/** Whether a file name is one this reads as a song. */
	public static boolean isSongFile(String fileName) {
		String lower = fileName.toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(EXTENSION) || lower.endsWith(LEGACY_EXTENSION);
	}

	public static Loaded read(Path file) throws IOException {
		return read(Files.readAllBytes(file), file.getFileName().toString());
	}

	/**
	 * @param name for the error, should there be one
	 * @throws IOException for a file that is not a song, or is one from a newer midicraft
	 */
	static Loaded read(byte[] bytes, String name) throws IOException {
		JsonElement parsed;
		try (Reader reader = new InputStreamReader(unzipped(bytes), StandardCharsets.UTF_8)) {
			parsed = JsonParser.parseReader(reader);
		} catch (RuntimeException notJson) {
			throw new IOException(name + " is not a song file", notJson);
		}
		if (!parsed.isJsonObject()) {
			throw new IOException(name + " is not a song file");
		}
		JsonObject root = parsed.getAsJsonObject();
		Details details = Details.NONE;
		JsonElement songJson = root;
		if (root.has("midicraft") && root.get("midicraft").isJsonObject() && root.has("song")) {
			Header header = GSON.fromJson(root.get("midicraft"), Header.class);
			int format = header.format == null ? 1 : header.format;
			if (format > FORMAT) {
				throw new IOException(name + " was saved by a newer midicraft (song format "
					+ format + "; this one reads up to " + FORMAT + "). Update midicraft to open it.");
			}
			details = new Details(header.author, header.credits, header.description, header.source,
				header.created, header.modified,
				header.writtenBy == null ? null : header.writtenBy.mod,
				header.writtenBy == null ? null : header.writtenBy.minecraft);
			songJson = root.get("song");
		}
		ComposerProject song;
		try {
			song = GSON.fromJson(songJson, ComposerProject.class);
		} catch (RuntimeException unreadable) {
			throw new IOException(name + " holds no readable song", unreadable);
		}
		if (song == null || song.layers() == null) {
			throw new IOException(name + " holds no song");
		}
		return new Loaded(canonical(song), details);
	}

	/**
	 * Put through the canonical constructor with every field the song keeps, so a hand-edited or
	 * older file gets the same invariants a freshly built one has.
	 */
	public static ComposerProject canonical(ComposerProject song) {
		return new ComposerProject(song.name(), song.ppq(), song.tempoMicrosPerQuarter(),
			song.layers(), song.activeLayerIndex(), song.nextNoteId(), song.endTick(),
			song.speedQuarters(), song.speedEighths(), song.markers(), song.dedupeIdenticalNotes());
	}

	/**
	 * Writes a song, beside the target first and then moved into place, so an interrupted save
	 * cannot leave half a song where a whole one used to be.
	 *
	 * @param details already stamped; see {@link Details#written}
	 */
	public static void write(Path file, ComposerProject song, Details details) throws IOException {
		Header header = new Header();
		header.format = FORMAT;
		if (details.mod() != null || details.minecraft() != null) {
			header.writtenBy = new WrittenBy();
			header.writtenBy.mod = details.mod();
			header.writtenBy.minecraft = details.minecraft();
		}
		header.created = details.created();
		header.modified = details.modified();
		header.source = details.source();
		header.author = details.author();
		header.credits = details.credits();
		header.description = details.description();
		JsonObject root = new JsonObject();
		root.add("midicraft", GSON.toJsonTree(header));
		root.add("song", GSON.toJsonTree(song));

		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = new OutputStreamWriter(
				new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Turns the {@code .json} songs a library used to store into {@code .midicraft} ones, once.
	 *
	 * <p>Each is read, written as {@code .midicraft} with the old file's date as both its created and
	 * its modified time -- the picker sorts by it, and a conversion is not an edit -- then read back,
	 * and only then is the {@code .json} moved into {@code legacy-json}. Moved and not deleted: it is
	 * the player's own file, and a converter that loses one is worse than no converter. A
	 * {@code .json} beside a {@code .midicraft} of the same id is a copy left behind, and is moved
	 * the same way.</p>
	 *
	 * @return the ids of {@code .json} songs that could not be read, which stay where they are and
	 *     are listed as unreadable the way any broken song is
	 */
	public static List<String> convertLegacy(Path directory, String modVersion,
			String minecraftVersion) {
		List<Path> legacy;
		try (Stream<Path> listing = Files.list(directory)) {
			legacy = listing.filter(path -> Files.isRegularFile(path)
					&& path.getFileName().toString().endsWith(LEGACY_EXTENSION))
				.sorted()
				.toList();
		} catch (Exception unreadableDirectory) {
			return List.of();
		}
		List<String> unreadable = new ArrayList<>();
		for (Path old : legacy) {
			String id = old.getFileName().toString().substring(0,
				old.getFileName().toString().length() - LEGACY_EXTENSION.length());
			Path converted = directory.resolve(id + EXTENSION);
			try {
				if (Files.notExists(converted)) {
					Loaded loaded = read(old);
					FileTime when = Files.getLastModifiedTime(old);
					String at = when.toInstant().toString();
					Details stamped = new Details(null, null, null, null, at, at, modVersion,
						minecraftVersion);
					write(converted, loaded.song(), stamped);
					Files.setLastModifiedTime(converted, when);
					// Read back before the original goes anywhere.
					read(converted);
				}
				Path backups = directory.resolve(LEGACY_FOLDER);
				Files.createDirectories(backups);
				Path kept = backups.resolve(old.getFileName());
				for (int copy = 2; Files.exists(kept); copy++) {
					kept = backups.resolve(id + " (" + copy + ")" + LEGACY_EXTENSION);
				}
				Files.move(old, kept);
			} catch (Exception unconverted) {
				unreadable.add(id);
			}
		}
		return unreadable;
	}

	/** The bytes as they are, or gunzipped if they start the way gzip does. */
	private static InputStream unzipped(byte[] bytes) throws IOException {
		InputStream raw = new ByteArrayInputStream(bytes);
		boolean gzip = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
		return gzip ? new GZIPInputStream(raw) : raw;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
