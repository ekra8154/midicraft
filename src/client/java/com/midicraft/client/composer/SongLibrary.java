package com.midicraft.client.composer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The songs on disk, one composition per {@code .midicraft} file under
 * {@code config/midicraft/songs}. See {@link SongFile} for what is in one.
 *
 * <p>Songs used to live inside the settings file, which coupled two things that fail very
 * differently. Settings are a few dozen small values; a song is most of a megabyte and is rewritten
 * on every keystroke. Worse, the settings loader falls back to defaults on any exception, so a
 * single unreadable composition took every setting in the mod down with it. Here a song that will
 * not parse costs you that song, and says so.</p>
 */
public final class SongLibrary {
	private static final Path DIRECTORY = FabricLoader.getInstance()
		.getConfigDir()
		.resolve("midicraft")
		.resolve("songs");

	private final Map<String, ComposerProject> songs = new LinkedHashMap<>();
	private final Map<String, SongFile.Details> details = new LinkedHashMap<>();
	private final List<String> failures = new ArrayList<>();

	private SongLibrary() {
	}

	public static Path directory() {
		return DIRECTORY;
	}

	/**
	 * Where to drop MIDI and NBS files you want to import, next to the songs they become.
	 *
	 * <p>Created the first time anyone looks, with a note in it saying what it is for -- an empty
	 * folder appearing in a config directory is otherwise just something odd you found.</p>
	 */
	public static Path importDirectory() {
		Path folder = DIRECTORY.resolveSibling("import");
		if (Files.isDirectory(folder)) {
			return folder;
		}
		try {
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("README.txt"),
				"""
				Put .mid, .midi, .nbs and .midicraft files here to import them into Midicraft.

				The composer's Import opens this folder first. Nothing in here is read
				automatically and nothing is ever written to or deleted from it -- importing
				copies the music into a song of its own, next door in the songs folder.
				""");
		} catch (IOException | RuntimeException unwritable) {
			// A read-only config directory is unusual but survivable: the browser falls back to the
			// nearest folder that does exist, which is the one above this.
		}
		return folder;
	}

	/** Reads every song file, skipping and remembering any that will not parse. */
	public static SongLibrary load() {
		SongLibrary library = new SongLibrary();
		if (Files.notExists(DIRECTORY)) {
			return library;
		}
		library.failures.addAll(SongFile.convertLegacy(DIRECTORY, modVersion(),
			minecraftVersion()));
		List<Path> files;
		try (Stream<Path> listing = Files.list(DIRECTORY)) {
			files = listing.filter(path -> path.getFileName().toString().endsWith(SongFile.EXTENSION))
				.sorted()
				.toList();
		} catch (Exception unreadableDirectory) {
			return library;
		}
		for (Path file : files) {
			String id = stripExtension(file.getFileName().toString());
			try {
				SongFile.Loaded loaded = SongFile.read(file);
				library.songs.put(id, loaded.song());
				library.details.put(id, loaded.details());
			} catch (Exception unreadableSong) {
				library.failures.add(id);
			}
		}
		return library;
	}

	private static Path file(String id) {
		return DIRECTORY.resolve(id + SongFile.EXTENSION);
	}

	/** This midicraft's version, for the file to say what wrote it. */
	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer("midicraft")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
	}

	private static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
	}

	/**
	 * When a song was last written, for a picker that lists newest first.
	 *
	 * <p>Asked of the file rather than kept on the composition, because it is a fact about the save
	 * and not about the music -- a song carries no date of its own, and inventing one would mean
	 * deciding whether opening a song counted as touching it.</p>
	 *
	 * @return milliseconds since the epoch, or 0 for a song whose file cannot be read
	 */
	public static long modifiedAt(String id) {
		try {
			return Files.getLastModifiedTime(file(id)).toMillis();
		} catch (Exception unreadable) {
			return 0L;
		}
	}

	public List<String> ids() {
		return List.copyOf(songs.keySet());
	}

	public boolean isEmpty() {
		return songs.isEmpty();
	}

	public ComposerProject song(String id) {
		return id == null ? null : songs.get(id);
	}

	/** Song files that exist but could not be read, so the picker can say so rather than hide them. */
	public List<String> failures() {
		return List.copyOf(failures);
	}

	/** What a song's file says about it, or {@link SongFile.Details#NONE} for none. */
	public SongFile.Details details(String id) {
		return id == null ? SongFile.Details.NONE
			: details.getOrDefault(id, SongFile.Details.NONE);
	}

	/** Writes one song, keeping what its file already said about it. */
	public boolean save(String id, ComposerProject song) {
		return save(id, song, details(id));
	}

	/**
	 * Writes one song with these details. Only this file is touched, whatever else the library
	 * holds.
	 */
	public boolean save(String id, ComposerProject song, SongFile.Details about) {
		if (id == null || id.isBlank() || song == null) {
			return false;
		}
		try {
			// Stamped with the dates and versions and nothing else. The author, credits and
			// description are only ever what the player typed into Song details: a save never fills
			// them in on its own.
			SongFile.Details stamped = (about == null ? SongFile.Details.NONE : about)
				.written(Instant.now(), modVersion(), minecraftVersion());
			SongFile.write(file(id), song, stamped);
			songs.put(id, song);
			details.put(id, stamped);
			return true;
		} catch (Exception failed) {
			return false;
		}
	}

	/** Rewrites a saved song with new details and the music it already had. */
	public boolean setDetails(String id, SongFile.Details about) {
		ComposerProject song = song(id);
		return song != null && save(id, song, about);
	}

	public boolean delete(String id) {
		if (id == null || !songs.containsKey(id)) {
			return false;
		}
		try {
			Files.deleteIfExists(file(id));
		} catch (Exception ignored) {
			return false;
		}
		songs.remove(id);
		details.remove(id);
		return true;
	}

	/**
	 * The given name, numbered if a song already goes by it.
	 *
	 * <p>Ids are deduplicated on their own, so two songs could share a display name and be told
	 * apart only by which row they sat on.</p>
	 */
	public String uniqueName(String name) {
		String base = name == null || name.isBlank() ? "Untitled composition" : name.trim();
		if (songs.values().stream().noneMatch(song -> song.name().equals(base))) {
			return base;
		}
		for (int suffix = 2; suffix < 1000; suffix++) {
			String candidate = base + " (" + suffix + ")";
			String attempt = candidate;
			if (songs.values().stream().noneMatch(song -> song.name().equals(attempt))) {
				return candidate;
			}
		}
		return base + " (" + System.currentTimeMillis() + ")";
	}

	/** A filename-safe id derived from a song's name, numbered if that name is already taken. */
	public String newId(String name) {
		String base = slug(name);
		if (!songs.containsKey(base) && Files.notExists(file(base))) {
			return base;
		}
		for (int suffix = 2; suffix < 1000; suffix++) {
			String candidate = base + "-" + suffix;
			if (!songs.containsKey(candidate) && Files.notExists(file(candidate))) {
				return candidate;
			}
		}
		return base + "-" + System.currentTimeMillis();
	}

	private static String slug(String name) {
		String cleaned = (name == null ? "" : name).toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-+)|(-+$)", "");
		if (cleaned.isBlank()) {
			return "song";
		}
		return cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned;
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? fileName : fileName.substring(0, dot);
	}
}
