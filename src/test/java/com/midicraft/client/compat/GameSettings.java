package com.midicraft.client.compat;

import com.google.gson.Gson;
import com.midicraft.client.MidicraftConfig;
import com.midicraft.client.composer.ChordSkips;
import com.midicraft.client.composer.ComposerProject;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The game's own paste settings, read off {@code run/config/midicraft.json}, so a probe builds the
 * song the way the Paste button does.
 *
 * <p>What is pasted in the world has to be what is simulated, and until now the probes answered
 * for a build nobody could paste: chords thinned at the analysis cap of thirty where the game thins
 * at the configured target, a project put back together with two fields fewer than
 * {@link com.midicraft.client.composer.SongLibrary} keeps, limits without the configured reseed
 * delay, and collisions thrown where the game records them. Every one of those changes the plan.
 * This is the one place the probes ask, and it asks the same file the game does, with the game's
 * defaults where a key is missing -- {@link MidicraftConfig} cannot be loaded here, its path comes
 * off the mod loader.</p>
 *
 * <p>Not read from here: the paste origin and the direction the player faces, which turn the build
 * in the world and change nothing in it; and the project as it stands unsaved in the composer,
 * which no probe can see.</p>
 */
final class GameSettings {
	private GameSettings() {
	}

	/** The file the game reads, relative to the project root the tests run from. */
	static final Path CONFIG = Path.of("run", "config", "midicraft.json");

	/** The keys the paste reads, typed as the game stores them, so a missing one reads null. */
	private static final class Stored {
		Integer chordThinTarget;
		Boolean thinChordsByVolume;
		Boolean thinChordsByStrikes;
		Integer maxBuildFloors;
		Integer parityReseedDelay;
		Integer paceTolerance;
		Boolean pasteStartTop;
		/** The old name of {@link #pasteStartTop}, read where a file predates it. */
		Boolean ultraLaneStartTop;
		/** The old on/off, read where a file predates {@link #colorCodedPaste}. */
		Boolean debugPasteEnabled;
		String colorCodedPaste;
		Integer buildLaneWidth;
		Integer buildLaneFloors;
		String pasteMode;
	}

	/**
	 * Merging duplicate notes is not here: it is the song's own choice now, read off each song as
	 * the Paste button reads it -- see {@link ComposerProject#dedupesIdentical()}.
	 *
	 * @param thinning {@code chordFitRules()}: the target with both kinds of thinning as set
	 * @param debugPaste {@code colorCodedPaste} at {@code NORMAL}, which is
	 *     {@code SongBuilder.DEBUG_PASTE} in the game: a collision is recorded rather than thrown, and
	 *     the walk that follows differs. The light show changes no walk and reads as false.
	 */
	record Values(ChordSkips.Rules thinning, int maxBuildFloors, int reseedDelay,
			int paceTolerance, boolean startTop, boolean debugPaste, int laneWidth, int laneFloors,
			String pasteMode) {

		SongBuilder.BuildLimits limits(int width, int floors) {
			return limits(maxBuildFloors, width, floors);
		}

		/** @param maxFloors an override for the cube layout's cap, the one thing it decides */
		SongBuilder.BuildLimits limits(int maxFloors, int width, int floors) {
			return new SongBuilder.BuildLimits(maxFloors, width, floors, startTop, reseedDelay,
				paceTolerance);
		}

		/**
		 * The notes the Paste button would build from: {@code notesFor} with the song's merging and
		 * the config's thinning, the sequence made the way {@code MidicraftConfig.tracks()} makes it.
		 */
		List<SongBuilder.EventNote> notes(ComposerProject project, SongBuilder.PasteMode mode) {
			boolean dedupe = project.dedupesIdentical();
			return SongBuilder.notesFor(mode, project.toSequenceTracks(Set.of(), dedupe, thinning),
				project, dedupe, thinning);
		}

		/** For a heading, so a run says what it built with. */
		String said() {
			return "[game: thin " + thinning.target() + (thinning.volume() ? " volume" : "")
				+ (thinning.strikes() ? " strikes" : "") + ", dedupe per song, reseed "
				+ reseedDelay + ", pace " + paceTolerance + ", debugPaste " + debugPaste + ", maxFloors " + maxBuildFloors
				+ ", lanes start " + (startTop ? "top" : "bottom") + "]";
		}
	}

	private static Values loaded;

	static synchronized Values get() {
		if (loaded == null) {
			loaded = overridden(read());
		}
		return loaded;
	}

	/**
	 * The paste settings a probe wants to hold still while the config moves under it, given as
	 * {@code -Dcensus.startTop=false}, {@code -Dfault.debugPaste=false} or
	 * {@code -Dcensus.paceTolerance=8}.
	 *
	 * <p>A census is only comparable to the one before it if the build it asked for is the same
	 * build, and both of these change the walk rather than the song: the lanes going up instead of
	 * down, and a collision recorded rather than thrown. Reading them off the live file means a
	 * player who toggled either one between two runs gets two answers to different questions with
	 * nothing in the report to say so -- which is why {@link Values#said()} now names them. These
	 * overrides are for asking the other question on purpose, and nothing else reads them.</p>
	 */
	private static Values overridden(Values read) {
		boolean startTop = flag("startTop", read.startTop());
		boolean debugPaste = flag("debugPaste", read.debugPaste());
		// The limits carry the configured tolerance, so a probe setting JOINT_PACE_TOLERANCE
		// through Flags changes nothing; this is the way to ask for another one.
		String pace = given("paceTolerance");
		int paceTolerance = pace == null ? read.paceTolerance() : Integer.parseInt(pace);
		if (startTop == read.startTop() && debugPaste == read.debugPaste()
				&& paceTolerance == read.paceTolerance()) {
			return read;
		}
		return new Values(read.thinning(), read.maxBuildFloors(), read.reseedDelay(),
			paceTolerance, startTop, debugPaste, read.laneWidth(), read.laneFloors(),
			read.pasteMode());
	}

	private static boolean flag(String key, boolean fallback) {
		String given = given(key);
		return given == null ? fallback : Boolean.parseBoolean(given);
	}

	private static String given(String key) {
		String given = System.getProperty("census." + key);
		if (given == null || given.isBlank()) {
			given = System.getProperty("fault." + key);
		}
		return given == null || given.isBlank() ? null : given.strip();
	}

	private static Values read() {
		Stored stored = new Stored();
		if (Files.exists(CONFIG)) {
			try (Reader reader = Files.newBufferedReader(CONFIG)) {
				Stored found = new Gson().fromJson(reader, Stored.class);
				if (found != null) {
					stored = found;
				}
			} catch (IOException | RuntimeException unreadable) {
				throw new IllegalStateException("could not read " + CONFIG.toAbsolutePath(), unreadable);
			}
		}
		// The same defaults and clamps MidicraftConfig.load applies, key for key.
		int target = clamp(stored.chordThinTarget == null
				? MidicraftConfig.DEFAULT_CHORD_THIN_TARGET : stored.chordThinTarget,
			MidicraftConfig.MIN_CHORD_THIN_TARGET, MidicraftConfig.MAX_CHORD_THIN_TARGET);
		boolean volume = stored.thinChordsByVolume == null || stored.thinChordsByVolume;
		boolean strikes = stored.thinChordsByStrikes == null || stored.thinChordsByStrikes;
		return new Values(
			new ChordSkips.Rules(target, volume, strikes),
			clamp(stored.maxBuildFloors == null
					? MidicraftConfig.DEFAULT_MAX_BUILD_FLOORS : stored.maxBuildFloors,
				MidicraftConfig.MIN_MAX_BUILD_FLOORS, MidicraftConfig.MAX_MAX_BUILD_FLOORS),
			clamp(stored.parityReseedDelay == null
					? MidicraftConfig.DEFAULT_PARITY_RESEED_DELAY : stored.parityReseedDelay,
				MidicraftConfig.MIN_PARITY_RESEED_DELAY, MidicraftConfig.MAX_PARITY_RESEED_DELAY),
			clamp(stored.paceTolerance == null
					? MidicraftConfig.DEFAULT_PACE_TOLERANCE : stored.paceTolerance,
				MidicraftConfig.MIN_PACE_TOLERANCE, MidicraftConfig.MAX_PACE_TOLERANCE),
			stored.pasteStartTop != null ? stored.pasteStartTop
				: stored.ultraLaneStartTop != null && stored.ultraLaneStartTop,
			stored.colorCodedPaste != null ? "NORMAL".equals(stored.colorCodedPaste)
				: stored.debugPasteEnabled != null && stored.debugPasteEnabled,
			stored.buildLaneWidth == null ? MidicraftConfig.DEFAULT_BUILD_LANE_WIDTH
				: stored.buildLaneWidth,
			stored.buildLaneFloors == null ? MidicraftConfig.DEFAULT_BUILD_LANE_FLOORS
				: stored.buildLaneFloors,
			stored.pasteMode == null ? "COMPACT_CUBE" : stored.pasteMode);
	}

	private static int clamp(int value, int low, int high) {
		return Math.max(low, Math.min(high, value));
	}

	/**
	 * A saved song, loaded exactly as {@link com.midicraft.client.composer.SongLibrary} loads it:
	 * parsed, then put through the canonical constructor with every field it keeps, so a hand-edited
	 * or older file gets the same invariants in the probe as in the game.
	 */
	static ComposerProject project(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject song = new Gson().fromJson(reader, ComposerProject.class);
			if (song == null || song.layers() == null) {
				throw new IllegalArgumentException(file + " holds no song");
			}
			return new ComposerProject(song.name(), song.ppq(), song.tempoMicrosPerQuarter(),
				song.layers(), song.activeLayerIndex(), song.nextNoteId(), song.endTick(),
				song.speedQuarters(), song.speedEighths(), song.markers(), song.dedupeIdenticalNotes());
		}
	}
}
