package com.fastnoteblocks.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongLibrary;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public final class FastNoteblocksConfig {
	public static final int MAX_TRACKS = ComposerProject.MAX_LAYERS;
	public static final int DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS = 4;
	/**
	 * Composer playback speed, kept separate from the sequence delay scale because the two mean
	 * opposite things: raising the composer speed makes the song faster, while raising the sequence
	 * scale lengthens each delay and makes it slower. Sharing one number made a composition preview
	 * at one speed and build at another.
	 */
	public static final int DEFAULT_COMPOSER_SPEED_QUARTERS = 4;
	public static final int MIN_SEQUENCE_DELAY_SCALE_QUARTERS = 1;
	public static final int MAX_SEQUENCE_DELAY_SCALE_QUARTERS = 32;

	public record SequenceTrack(String name, String sequence, String instrument, int position, boolean buildEnabled) {
		public SequenceTrack {
			name = name == null || name.isBlank() ? "Track" : name.trim();
			sequence = sequence == null ? "" : sequence;
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			position = Math.max(0, position);
		}

		public SequenceTrack(String name, String sequence, String instrument, int position) {
			this(name, sequence, instrument, position, true);
		}

		public SequenceTrack withName(String value) {
			return new SequenceTrack(value, sequence, instrument, position, buildEnabled);
		}

		public SequenceTrack withSequence(String value) {
			return new SequenceTrack(name, value, instrument, position, buildEnabled);
		}

		public SequenceTrack withInstrument(String value) {
			return new SequenceTrack(name, sequence, value, position, buildEnabled);
		}

		public SequenceTrack withPosition(int value) {
			return new SequenceTrack(name, sequence, instrument, value, buildEnabled);
		}

		public SequenceTrack withBuildEnabled(boolean value) {
			return new SequenceTrack(name, sequence, instrument, position, value);
		}
	}

	public record SavedSequence(
		String name,
		List<SequenceTrack> tracks,
		int activeTrackIndex,
		int delayScaleQuarters,
		String sequence,
		ComposerProject composerProject
	) {
		public SavedSequence {
			name = name == null || name.isBlank() ? "Untitled sequence" : name.trim();
			tracks = tracks == null || tracks.isEmpty()
				? List.of(new SequenceTrack("Track 1", sequence, "HARP", 0))
				: normalizeTracks(tracks);
			activeTrackIndex = clampTrackIndex(activeTrackIndex, tracks.size());
			delayScaleQuarters = delayScaleQuarters < MIN_SEQUENCE_DELAY_SCALE_QUARTERS
				? DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS
				: clampSequenceDelayScale(delayScaleQuarters);
			sequence = null;
		}

		public SavedSequence(String name, String sequence) {
			this(name, null, 0, DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS, sequence, null);
		}

		public SavedSequence(String name, List<SequenceTrack> tracks, int activeTrackIndex) {
			this(name, tracks, activeTrackIndex, DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS, null, null);
		}

		public SavedSequence(String name, List<SequenceTrack> tracks, int activeTrackIndex, int delayScaleQuarters) {
			this(name, tracks, activeTrackIndex, delayScaleQuarters, null, null);
		}

		public SavedSequence(
			String name,
			List<SequenceTrack> tracks,
			int activeTrackIndex,
			int delayScaleQuarters,
			ComposerProject composerProject
		) {
			this(name, tracks, activeTrackIndex, delayScaleQuarters, null, composerProject);
		}
	}

	public enum OverlayMode {
		BOTH(true, true),
		NOTES_ONLY(true, false),
		REPEATERS_ONLY(false, true),
		OFF(false, false);

		private final boolean notes;
		private final boolean repeaters;

		OverlayMode(boolean notes, boolean repeaters) {
			this.notes = notes;
			this.repeaters = repeaters;
		}

		public boolean includesNotes() {
			return notes;
		}

		public boolean includesRepeaters() {
			return repeaters;
		}
	}

	public enum RepeaterControlStyle {
		RADIAL_SELECT,
		SCROLL
	}

	public enum SequencingEditProtection {
		RADIALS_ONLY(true, false),
		RADIALS_AND_INTERACTIONS(true, true),
		OFF(false, false);

		private final boolean radials;
		private final boolean interactions;

		SequencingEditProtection(boolean radials, boolean interactions) {
			this.radials = radials;
			this.interactions = interactions;
		}

		public boolean blocksRadials() {
			return radials;
		}

		public boolean blocksInteractions() {
			return interactions;
		}
	}

	public enum MidiQuantizeGrid {
		AUTO,
		QUARTER,
		EIGHTH,
		SIXTEENTH
	}

	public enum MidiRangeFit {
		OCTAVE_SHIFT,
		OCTAVE_WRAP,
		CLAMP,
		REJECT_OUT_OF_RANGE
	}

	public enum MidiTempoFit {
		PRESERVE_ORIGINAL,
		SNAP_TO_REPEATERS
	}

	public static final int DEFAULT_VIEW_DISTANCE = 10;
	public static final int MIN_VIEW_DISTANCE = 1;
	public static final int MAX_VIEW_DISTANCE = 32;
	public static final int DEFAULT_INTERACTION_DELAY_TICKS = 0;
	public static final int MIN_INTERACTION_DELAY_TICKS = 0;
	public static final int MAX_INTERACTION_DELAY_TICKS = 10;
	public static final int DEFAULT_RADIAL_FOCUS_DELAY_TICKS = 5;
	public static final int MIN_RADIAL_FOCUS_DELAY_TICKS = 0;
	public static final int MAX_RADIAL_FOCUS_DELAY_TICKS = 20;
	/** Kept independent of the layer cap: importing 128 MIDI tracks by default helps nobody. */
	public static final int DEFAULT_MIDI_MAX_IMPORTED_TRACKS = 16;
	public static final int MIN_MIDI_MAX_IMPORTED_TRACKS = 1;
	public static final int MAX_MIDI_MAX_IMPORTED_TRACKS = MAX_TRACKS;
	/**
	 * Share of the closest note gaps the automatic convert grid is allowed to ignore. Picking the
	 * grid from the single smallest gap lets one outlier in thousands of notes dictate the tempo
	 * for the whole song. 0 restores that strict-minimum behaviour.
	 */
	public static final int DEFAULT_CONVERSION_GAP_PERCENTILE = 5;
	public static final int MIN_CONVERSION_GAP_PERCENTILE = 0;
	public static final int MAX_CONVERSION_GAP_PERCENTILE = 25;
	/**
	 * Floors a Compact cube build may stack. Each floor's first repeater refreshes the signal, so
	 * the glass riser only ever carries it four blocks -- the old four-floor ceiling came from a
	 * parallel-power design and does not apply to a single continuous chain.
	 */
	public static final int DEFAULT_MAX_BUILD_FLOORS = 16;
	public static final int MIN_MAX_BUILD_FLOORS = 1;
	public static final int MAX_MAX_BUILD_FLOORS = 64;
	/**
	 * Commands sent per client tick when pasting a build. Singleplayer tolerates far more than the
	 * original fixed rate of 2; servers may treat a high rate as command spam.
	 */
	public static final int DEFAULT_COMMANDS_PER_TICK = 32;
	public static final int MIN_COMMANDS_PER_TICK = 1;
	public static final int MAX_COMMANDS_PER_TICK = 256;
	/** Repeats of a pitch closer than this many repeater ticks collapse on convert. 0 disables. */
	public static final int DEFAULT_REPEAT_MERGE_TICKS = 1;
	public static final int MIN_REPEAT_MERGE_TICKS = 0;
	public static final int MAX_REPEAT_MERGE_TICKS = 8;
	/** Note blocks have no volume, so quiet imported notes become full-volume noise. 32 is the MIDI "pp" threshold. */
	public static final int DEFAULT_MIDI_VELOCITY_CUTOFF = 32;
	public static final int MIN_MIDI_VELOCITY_CUTOFF = 0;
	public static final int MAX_MIDI_VELOCITY_CUTOFF = 127;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("fast-noteblocks.json");
	private static SongLibrary songs = SongLibrary.load();
	private static FastNoteblocksConfig instance = defaults();

	private boolean modEnabled;
	private OverlayMode overlayMode;
	private OverlayMode previousOverlayMode;
	private boolean nearbyPreviewsEnabled;
	private boolean interactiveControlsEnabled;
	private int radialFocusDelayTicks;
	private RepeaterControlStyle repeaterControlStyle;
	private boolean invertScrolling;
	private int viewDistance;
	private int interactionDelayTicks;
	private boolean waitForServerAcknowledgement;
	private boolean requireLineOfSight;
	private boolean placementSequenceEnabled;
	private SequencingEditProtection sequencingEditProtection;
	private boolean autoSelectSequenceBlock;
	private String placementSequence;
	private int placementSequencePosition;
	private String activeSequenceName;
	private int activeSequenceDelayScaleQuarters;
	private int composerSpeedQuarters;
	private String previewInstrument;
	private List<SequenceTrack> tracks;
	private ComposerProject composerProject;
	private String activeSongId;
	private boolean buildTrackFlagsInitialized;
	private int activeTrackIndex;
	private List<SavedSequence> savedSequences;
	private MidiQuantizeGrid midiQuantizeGrid;
	private MidiRangeFit midiRangeFit;
	private boolean midiIgnorePercussion;
	private int midiMaxImportedTracks;
	private String midiDefaultInstrument;
	private MidiTempoFit midiTempoFit;
	private int midiVelocityCutoff;
	private int repeatMergeTicks;
	private int conversionGapPercentile;
	private int commandsPerTick;
	private int maxBuildFloors;

	private FastNoteblocksConfig() {
	}

	public static FastNoteblocksConfig get() {
		return instance;
	}

	public static void load() {
		instance = defaults();
		if (Files.notExists(CONFIG_PATH)) {
			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
			StoredConfig stored = GSON.fromJson(reader, StoredConfig.class);
			if (stored != null) {
				instance.modEnabled = stored.modEnabled == null || stored.modEnabled;
				instance.overlayMode = stored.overlayMode == null ? migrateOverlayMode(stored) : stored.overlayMode;
				instance.previousOverlayMode = stored.previousOverlayMode == null || stored.previousOverlayMode == OverlayMode.OFF
					? (instance.overlayMode == OverlayMode.OFF ? OverlayMode.NOTES_ONLY : instance.overlayMode)
					: stored.previousOverlayMode;
				instance.nearbyPreviewsEnabled = stored.nearbyPreviewsEnabled == null || stored.nearbyPreviewsEnabled;
				instance.interactiveControlsEnabled = stored.interactiveControlsEnabled == null
					? stored.radialControlsEnabled == null || stored.radialControlsEnabled
					: stored.interactiveControlsEnabled;
				instance.radialFocusDelayTicks = clampRadialFocusDelay(
					stored.radialFocusDelayTicks == null ? DEFAULT_RADIAL_FOCUS_DELAY_TICKS : stored.radialFocusDelayTicks
				);
				instance.repeaterControlStyle = stored.repeaterControlStyle == null
					? RepeaterControlStyle.SCROLL
					: stored.repeaterControlStyle;
				instance.invertScrolling = Boolean.TRUE.equals(stored.invertScrolling);
				instance.viewDistance = clampViewDistance(stored.viewDistance == null ? DEFAULT_VIEW_DISTANCE : stored.viewDistance);
				instance.interactionDelayTicks = clampInteractionDelay(
					stored.interactionDelayTicks == null ? DEFAULT_INTERACTION_DELAY_TICKS : stored.interactionDelayTicks
				);
				instance.waitForServerAcknowledgement = Boolean.TRUE.equals(stored.waitForServerAcknowledgement);
				instance.requireLineOfSight = Boolean.TRUE.equals(stored.requireLineOfSight);
				instance.placementSequenceEnabled = Boolean.TRUE.equals(stored.placementSequenceEnabled);
				instance.sequencingEditProtection = stored.sequencingEditProtection == null
					? SequencingEditProtection.RADIALS_AND_INTERACTIONS
					: stored.sequencingEditProtection;
				instance.autoSelectSequenceBlock = stored.autoSelectSequenceBlock == null || stored.autoSelectSequenceBlock;
				instance.placementSequence = stored.placementSequence == null ? "" : stored.placementSequence;
				instance.placementSequencePosition = Math.max(0,
					stored.placementSequencePosition == null ? 0 : stored.placementSequencePosition
				);
				instance.activeSequenceName = stored.activeSequenceName == null || stored.activeSequenceName.isBlank()
					? "Untitled sequence"
					: stored.activeSequenceName;
				instance.activeSequenceDelayScaleQuarters = clampSequenceDelayScale(
					stored.activeSequenceDelayScaleQuarters == null
						? legacyTimescaleToQuarters(stored.activeSequenceTimescale)
						: stored.activeSequenceDelayScaleQuarters
				);
					// Configs written before the two were separated only carry one number, and it
					// was the composer's speed slider that last set it. Seed from there so an
					// existing composition keeps the speed it was authored at.
					instance.composerSpeedQuarters = clampSequenceDelayScale(
						stored.composerSpeedQuarters == null
							? instance.activeSequenceDelayScaleQuarters
							: stored.composerSpeedQuarters
					);
					instance.previewInstrument = stored.previewInstrument == null ? "HARP" : stored.previewInstrument;
				boolean buildTrackFlagsInitialized = Boolean.TRUE.equals(stored.buildTrackFlagsInitialized);
				instance.tracks = stored.tracks == null || stored.tracks.isEmpty()
					? List.of(new SequenceTrack("Track 1", instance.placementSequence,
						instance.previewInstrument, instance.placementSequencePosition))
					: normalizeTracks(stored.tracks);
				if (!buildTrackFlagsInitialized) {
					instance.tracks = enableAllBuildTracks(instance.tracks);
				}
				instance.buildTrackFlagsInitialized = true;
				instance.activeTrackIndex = clampTrackIndex(
					stored.activeTrackIndex == null ? 0 : stored.activeTrackIndex, instance.tracks.size()
				);
				// Read only: whatever is here is pre-library data waiting to be migrated out.
				instance.composerProject = stored.composerProject == null
					? null
					: stored.composerProject.withSpeedQuarters(instance.composerSpeedQuarters);
				instance.activeSongId = stored.activeSongId;
				instance.savedSequences = stored.savedSequences == null
					? new ArrayList<>()
					: new ArrayList<>(stored.savedSequences);
				if (!buildTrackFlagsInitialized) {
					instance.savedSequences = instance.savedSequences.stream()
						.map(saved -> new SavedSequence(saved.name(), enableAllBuildTracks(saved.tracks()),
							saved.activeTrackIndex(), saved.delayScaleQuarters(), saved.composerProject()))
						.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
				}
				instance.midiQuantizeGrid = stored.midiQuantizeGrid == null ? MidiQuantizeGrid.AUTO : stored.midiQuantizeGrid;
				instance.midiRangeFit = stored.midiRangeFit == null ? MidiRangeFit.OCTAVE_SHIFT : stored.midiRangeFit;
				instance.midiIgnorePercussion = stored.midiIgnorePercussion == null || stored.midiIgnorePercussion;
				instance.midiMaxImportedTracks = clampMidiMaxImportedTracks(
					stored.midiMaxImportedTracks == null
						? DEFAULT_MIDI_MAX_IMPORTED_TRACKS
						: stored.midiMaxImportedTracks
				);
				instance.midiDefaultInstrument = stored.midiDefaultInstrument == null || stored.midiDefaultInstrument.isBlank()
					? "HARP"
					: stored.midiDefaultInstrument;
				instance.midiTempoFit = stored.midiTempoFit == null ? MidiTempoFit.SNAP_TO_REPEATERS : stored.midiTempoFit;
				instance.maxBuildFloors = clampMaxBuildFloors(
					stored.maxBuildFloors == null ? DEFAULT_MAX_BUILD_FLOORS : stored.maxBuildFloors
				);
				instance.commandsPerTick = clampCommandsPerTick(
					stored.commandsPerTick == null ? DEFAULT_COMMANDS_PER_TICK : stored.commandsPerTick
				);
				instance.conversionGapPercentile = clampConversionGapPercentile(
					stored.conversionGapPercentile == null
						? DEFAULT_CONVERSION_GAP_PERCENTILE
						: stored.conversionGapPercentile
				);
				instance.repeatMergeTicks = clampRepeatMergeTicks(
					stored.repeatMergeTicks == null
						? DEFAULT_REPEAT_MERGE_TICKS
						: stored.repeatMergeTicks
				);
				instance.midiVelocityCutoff = clampMidiVelocityCutoff(
					stored.midiVelocityCutoff == null
						? DEFAULT_MIDI_VELOCITY_CUTOFF
						: stored.midiVelocityCutoff
				);
			}
		} catch (Exception ignored) {
			instance = defaults();
		}
		songs = SongLibrary.load();
		instance.activeSongId = migrateSongsOutOfSettings(instance.activeSongId);
	}

	public static SongLibrary songs() {
		return songs;
	}

	/**
	 * Moves compositions out of the settings file the first time this build runs.
	 *
	 * <p>Only runs while the library is empty, so it cannot overwrite songs a later session wrote.
	 * Library entries saved before compositions were stored alongside them have to be rebuilt from
	 * their track text, which is lossy in the usual ways -- text carries neither tempo, nor pitches
	 * outside the note-block range, nor anything finer than a repeater tick.</p>
	 */
	private static String migrateSongsOutOfSettings(String preferredId) {
		if (!songs.isEmpty()) {
			return preferredId != null && songs.song(preferredId) != null
				? preferredId
				: songs.ids().stream().findFirst().orElse(null);
		}
		String activeId = null;
		if (instance.composerProject != null && instance.composerProject.noteCount() > 0) {
			activeId = songs.newId(instance.composerProject.name());
			songs.save(activeId, instance.composerProject);
		}
		for (SavedSequence saved : instance.savedSequences) {
			ComposerProject song = saved.composerProject() != null
				? saved.composerProject()
				: ComposerProject.fromSequenceTracks(saved.name(), saved.tracks(),
					saved.activeTrackIndex(), saved.delayScaleQuarters());
			songs.save(songs.newId(saved.name()), song.withName(saved.name()));
		}
		if (activeId == null) {
			activeId = songs.ids().stream().findFirst().orElse(null);
		}
		if (activeId == null) {
			activeId = songs.newId(instance.activeSequenceName);
			songs.save(activeId, ComposerProject.empty(instance.activeSequenceName));
		}
		// The settings file keeps only a pointer from here on; save() no longer writes songs.
		instance.savedSequences = new ArrayList<>();
		return activeId;
	}

	public static void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(new StoredConfig(instance), writer);
			}
		} catch (Exception ignored) {
		}
	}

	public static List<NoteSequence.Step> parsePlacementSequence(String value) {
		return NoteSequence.parse(value, get().activeSequenceDelayScaleQuarters());
	}

	public static List<NoteSequence.Step> parsePlacementSequence(String value, int delayScaleQuarters) {
		return NoteSequence.parse(value, delayScaleQuarters);
	}

	public static Optional<Component> validatePlacementSequence(String value) {
		try {
			parsePlacementSequence(value);
			return Optional.empty();
		} catch (IllegalArgumentException exception) {
			return Optional.of(Component.translatable("error.fast-noteblocks.sequence"));
		}
	}

	public boolean modEnabled() {
		return modEnabled;
	}

	public void setModEnabled(boolean modEnabled) {
		this.modEnabled = modEnabled;
	}

	public OverlayMode overlayMode() {
		return overlayMode;
	}

	public void setOverlayMode(OverlayMode overlayMode) {
		this.overlayMode = overlayMode == null ? OverlayMode.NOTES_ONLY : overlayMode;
		if (this.overlayMode != OverlayMode.OFF) {
			previousOverlayMode = this.overlayMode;
		}
	}

	public void toggleOverlays() {
		if (overlayMode == OverlayMode.OFF) {
			overlayMode = previousOverlayMode == null || previousOverlayMode == OverlayMode.OFF
				? OverlayMode.NOTES_ONLY
				: previousOverlayMode;
		} else {
			previousOverlayMode = overlayMode;
			overlayMode = OverlayMode.OFF;
		}
	}

	public boolean overlaysEnabled() {
		return overlayMode != OverlayMode.OFF;
	}

	public boolean nearbyPreviewsEnabled() {
		return nearbyPreviewsEnabled;
	}

	public void setNearbyPreviewsEnabled(boolean nearbyPreviewsEnabled) {
		this.nearbyPreviewsEnabled = nearbyPreviewsEnabled;
	}

	public boolean interactiveControlsEnabled() {
		return interactiveControlsEnabled;
	}

	public void setInteractiveControlsEnabled(boolean interactiveControlsEnabled) {
		this.interactiveControlsEnabled = interactiveControlsEnabled;
	}

	public int radialFocusDelayTicks() {
		return radialFocusDelayTicks;
	}

	public void setRadialFocusDelayTicks(int radialFocusDelayTicks) {
		this.radialFocusDelayTicks = clampRadialFocusDelay(radialFocusDelayTicks);
	}

	public RepeaterControlStyle repeaterControlStyle() {
		return repeaterControlStyle;
	}

	public void setRepeaterControlStyle(RepeaterControlStyle repeaterControlStyle) {
		this.repeaterControlStyle = repeaterControlStyle == null
			? RepeaterControlStyle.SCROLL
			: repeaterControlStyle;
	}

	public boolean invertScrolling() {
		return invertScrolling;
	}

	public void setInvertScrolling(boolean invertScrolling) {
		this.invertScrolling = invertScrolling;
	}

	public int viewDistance() {
		return viewDistance;
	}

	public void setViewDistance(int viewDistance) {
		this.viewDistance = clampViewDistance(viewDistance);
	}

	public int interactionDelayTicks() {
		return interactionDelayTicks;
	}

	public void setInteractionDelayTicks(int interactionDelayTicks) {
		this.interactionDelayTicks = clampInteractionDelay(interactionDelayTicks);
	}

	public boolean waitForServerAcknowledgement() {
		return waitForServerAcknowledgement;
	}

	public void setWaitForServerAcknowledgement(boolean waitForServerAcknowledgement) {
		this.waitForServerAcknowledgement = waitForServerAcknowledgement;
	}

	public boolean requireLineOfSight() {
		return requireLineOfSight;
	}

	public void setRequireLineOfSight(boolean requireLineOfSight) {
		this.requireLineOfSight = requireLineOfSight;
	}

	public boolean placementSequenceEnabled() {
		return placementSequenceEnabled;
	}

	public void setPlacementSequenceEnabled(boolean placementSequenceEnabled) {
		this.placementSequenceEnabled = placementSequenceEnabled;
	}

	public SequencingEditProtection sequencingEditProtection() {
		return sequencingEditProtection;
	}

	public void setSequencingEditProtection(SequencingEditProtection sequencingEditProtection) {
		this.sequencingEditProtection = sequencingEditProtection == null
			? SequencingEditProtection.RADIALS_AND_INTERACTIONS
			: sequencingEditProtection;
	}

	public boolean autoSelectSequenceBlock() {
		return autoSelectSequenceBlock;
	}

	public void setAutoSelectSequenceBlock(boolean autoSelectSequenceBlock) {
		this.autoSelectSequenceBlock = autoSelectSequenceBlock;
	}

	public String placementSequence() {
		return activeTrack().sequence();
	}

	public void setPlacementSequence(String placementSequence) {
		updateActiveTrack(activeTrack().withSequence(placementSequence));
		this.placementSequence = activeTrack().sequence();
	}

	public int placementSequencePosition() {
		return activeTrack().position();
	}

	public void setPlacementSequencePosition(int placementSequencePosition) {
		updateActiveTrack(activeTrack().withPosition(placementSequencePosition));
		this.placementSequencePosition = activeTrack().position();
	}

	public String activeSequenceName() {
		return activeSequenceName;
	}

	public void setActiveSequenceName(String activeSequenceName) {
		this.activeSequenceName = activeSequenceName == null || activeSequenceName.isBlank()
			? "Untitled sequence"
			: activeSequenceName.trim();
		if (composerProject != null) {
			composerProject = composerProject.withName(this.activeSequenceName);
		}
	}

	public int activeSequenceDelayScaleQuarters() {
		return activeSequenceDelayScaleQuarters;
	}

	/**
	 * Sets the timescale. Build tracks keep whatever text they already had.
	 *
	 * <p>Rewriting them here made a drag of the slider republish the composition over the
	 * sequence. Track delays are stored scale-normalised, so the existing text already changes
	 * meaning with the scale without being regenerated.</p>
	 */
	public void setActiveSequenceDelayScaleQuarters(int activeSequenceDelayScaleQuarters) {
		this.activeSequenceDelayScaleQuarters = clampSequenceDelayScale(activeSequenceDelayScaleQuarters);
	}

	/**
	 * Speed the composer last used, kept only to migrate documents saved before compositions
	 * carried their own. New saves take it from the composition and this stops being read.
	 */
	public int composerSpeedQuarters() {
		return composerSpeedQuarters;
	}

	public String previewInstrument() {
		return activeTrack().instrument();
	}

	public void setPreviewInstrument(String previewInstrument) {
		updateActiveTrack(activeTrack().withInstrument(previewInstrument));
		this.previewInstrument = activeTrack().instrument();
	}

	public List<SequenceTrack> tracks() {
		return List.copyOf(tracks);
	}

	/**
	 * Replaces the build tracks. Deliberately leaves the composition alone.
	 *
	 * <p>This runs on every keystroke in the sequence editor. Rebuilding the composition from the
	 * track text here meant any visit to the sequencer silently replaced it with a reconstruction:
	 * tempo reset to the default, velocities flattened, note durations rounded, and every pitch
	 * outside the note-block range dropped. The composition is the source of truth and only an
	 * explicit publish writes tracks from it.</p>
	 */
	public void setTracks(List<SequenceTrack> tracks) {
		this.tracks = normalizeTracks(tracks);
		activeTrackIndex = clampTrackIndex(activeTrackIndex, this.tracks.size());
		syncLegacyTrackFields();
	}

	public String activeSongId() {
		return activeSongId;
	}

	/** Opens a different song. The one being left is already on disk; nothing is carried over. */
	public void setActiveSongId(String id) {
		if (id == null || songs.song(id) == null) {
			return;
		}
		activeSongId = id;
		composerProject = songs.song(id);
		activeSequenceName = composerProject.name();
	}

	public ComposerProject composerProject() {
		if (composerProject == null) {
			ComposerProject stored = songs.song(activeSongId);
			if (stored == null) {
				stored = ComposerProject.empty(activeSequenceName);
				activeSongId = songs.newId(activeSequenceName);
				songs.save(activeSongId, stored);
			}
			composerProject = stored;
		}
		return composerProject;
	}

	/**
	 * Stores the composition, writing only its own file.
	 *
	 * <p>Build tracks are untouched until {@link #publishComposerProject}: the projection drops
	 * everything track text cannot express, so it stays an explicit action.</p>
	 */
	public void setComposerProject(ComposerProject project) {
		if (project == null) {
			return;
		}
		composerProject = project;
		activeSequenceName = project.name();
		if (activeSongId == null) {
			activeSongId = songs.newId(project.name());
		}
		songs.save(activeSongId, project);
	}

	/**
	 * Projects the composition onto the build tracks, replacing whatever was there.
	 *
	 * <p>Lossy by nature -- the track text can only hold whole repeater delays and note-block
	 * pitches -- which is exactly why it is an explicit action rather than a side effect of
	 * leaving the composer.</p>
	 */
	public void publishComposerProject() {
		ComposerProject project = composerProject();
		activeTrackIndex = project.activeLayerIndex();
		// Write the delays already divided by the composer speed, then leave the sequence scale at
		// 1.00x so nothing multiplies them back. Publishing at the sequence scale meant the two
		// cancelled and the build always ran at the raw project tempo, however the composer was
		// previewing it.
		tracks = normalizeTracks(project.toSequenceTracks(tracks));
		activeSequenceDelayScaleQuarters = DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS;
		activeTrackIndex = clampTrackIndex(activeTrackIndex, tracks.size());
		syncLegacyTrackFields();
	}

	public int activeTrackIndex() {
		return activeTrackIndex;
	}

	public void setActiveTrackIndex(int activeTrackIndex) {
		this.activeTrackIndex = clampTrackIndex(activeTrackIndex, tracks.size());
		syncLegacyTrackFields();
	}

	public SequenceTrack activeTrack() {
		return tracks.get(clampTrackIndex(activeTrackIndex, tracks.size()));
	}

	/**
	 * The library as the old sequence list saw it, so the Mod Menu screen keeps working while the
	 * songs screen is built. Reading this projects every song, which is why nothing else uses it.
	 */
	public List<SavedSequence> savedSequences() {
		List<SavedSequence> result = new ArrayList<>();
		for (String id : songs.ids()) {
			ComposerProject song = songs.song(id);
			result.add(new SavedSequence(song.name(), song.toSequenceTracks(null),
				song.activeLayerIndex(), DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS, song));
		}
		return List.copyOf(result);
	}

	public void setSavedSequences(List<SavedSequence> updated) {
		List<SavedSequence> entries = updated == null ? List.of() : updated;
		List<String> existing = songs.ids();
		for (int index = 0; index < entries.size(); index++) {
			SavedSequence entry = entries.get(index);
			ComposerProject song = entry.composerProject() != null
				? entry.composerProject().withName(entry.name())
				: ComposerProject.fromSequenceTracks(entry.name(), entry.tracks(),
					entry.activeTrackIndex(), entry.delayScaleQuarters());
			String id = index < existing.size() ? existing.get(index) : songs.newId(entry.name());
			songs.save(id, song);
		}
		for (int index = entries.size(); index < existing.size(); index++) {
			songs.delete(existing.get(index));
		}
	}

	public MidiQuantizeGrid midiQuantizeGrid() {
		return midiQuantizeGrid;
	}

	public void setMidiQuantizeGrid(MidiQuantizeGrid midiQuantizeGrid) {
		this.midiQuantizeGrid = midiQuantizeGrid == null ? MidiQuantizeGrid.AUTO : midiQuantizeGrid;
	}

	public MidiRangeFit midiRangeFit() {
		return midiRangeFit;
	}

	public void setMidiRangeFit(MidiRangeFit midiRangeFit) {
		this.midiRangeFit = midiRangeFit == null ? MidiRangeFit.OCTAVE_SHIFT : midiRangeFit;
	}

	public boolean midiIgnorePercussion() {
		return midiIgnorePercussion;
	}

	public void setMidiIgnorePercussion(boolean midiIgnorePercussion) {
		this.midiIgnorePercussion = midiIgnorePercussion;
	}

	public int midiMaxImportedTracks() {
		return midiMaxImportedTracks;
	}

	public void setMidiMaxImportedTracks(int midiMaxImportedTracks) {
		this.midiMaxImportedTracks = clampMidiMaxImportedTracks(midiMaxImportedTracks);
	}

	public String midiDefaultInstrument() {
		return midiDefaultInstrument;
	}

	public void setMidiDefaultInstrument(String midiDefaultInstrument) {
		this.midiDefaultInstrument = midiDefaultInstrument == null || midiDefaultInstrument.isBlank()
			? "HARP"
			: midiDefaultInstrument.trim();
	}

	public MidiTempoFit midiTempoFit() {
		return midiTempoFit;
	}

	public void setMidiTempoFit(MidiTempoFit midiTempoFit) {
		this.midiTempoFit = midiTempoFit == null ? MidiTempoFit.SNAP_TO_REPEATERS : midiTempoFit;
	}

	public int maxBuildFloors() {
		return maxBuildFloors;
	}

	public void setMaxBuildFloors(int maxBuildFloors) {
		this.maxBuildFloors = clampMaxBuildFloors(maxBuildFloors);
	}

	public int commandsPerTick() {
		return commandsPerTick;
	}

	public void setCommandsPerTick(int commandsPerTick) {
		this.commandsPerTick = clampCommandsPerTick(commandsPerTick);
	}

	public int conversionGapPercentile() {
		return conversionGapPercentile;
	}

	public void setConversionGapPercentile(int conversionGapPercentile) {
		this.conversionGapPercentile = clampConversionGapPercentile(conversionGapPercentile);
	}

	public int repeatMergeTicks() {
		return repeatMergeTicks;
	}

	public void setRepeatMergeTicks(int repeatMergeTicks) {
		this.repeatMergeTicks = clampRepeatMergeTicks(repeatMergeTicks);
	}

	public int midiVelocityCutoff() {
		return midiVelocityCutoff;
	}

	public void setMidiVelocityCutoff(int midiVelocityCutoff) {
		this.midiVelocityCutoff = clampMidiVelocityCutoff(midiVelocityCutoff);
	}

	private static FastNoteblocksConfig defaults() {
		FastNoteblocksConfig config = new FastNoteblocksConfig();
		config.modEnabled = true;
		config.overlayMode = OverlayMode.NOTES_ONLY;
		config.previousOverlayMode = OverlayMode.NOTES_ONLY;
		config.nearbyPreviewsEnabled = true;
		config.interactiveControlsEnabled = true;
		config.radialFocusDelayTicks = DEFAULT_RADIAL_FOCUS_DELAY_TICKS;
		config.repeaterControlStyle = RepeaterControlStyle.SCROLL;
		config.invertScrolling = false;
		config.viewDistance = DEFAULT_VIEW_DISTANCE;
		config.interactionDelayTicks = DEFAULT_INTERACTION_DELAY_TICKS;
		config.waitForServerAcknowledgement = false;
		config.requireLineOfSight = false;
		config.placementSequenceEnabled = false;
		config.sequencingEditProtection = SequencingEditProtection.RADIALS_AND_INTERACTIONS;
		config.autoSelectSequenceBlock = true;
		config.placementSequence = "";
		config.placementSequencePosition = 0;
		config.activeSequenceName = "Untitled sequence";
		config.activeSequenceDelayScaleQuarters = DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS;
		config.previewInstrument = "HARP";
		config.tracks = List.of(new SequenceTrack("Track 1", "", "HARP", 0));
		config.composerProject = ComposerProject.empty(config.activeSequenceName);
		config.buildTrackFlagsInitialized = true;
		config.activeTrackIndex = 0;
		config.savedSequences = new ArrayList<>();
		config.midiQuantizeGrid = MidiQuantizeGrid.AUTO;
		config.midiRangeFit = MidiRangeFit.OCTAVE_SHIFT;
		config.midiIgnorePercussion = true;
		config.midiMaxImportedTracks = DEFAULT_MIDI_MAX_IMPORTED_TRACKS;
		config.midiDefaultInstrument = "HARP";
		config.midiTempoFit = MidiTempoFit.SNAP_TO_REPEATERS;
		config.composerSpeedQuarters = DEFAULT_COMPOSER_SPEED_QUARTERS;
		config.midiVelocityCutoff = DEFAULT_MIDI_VELOCITY_CUTOFF;
		config.repeatMergeTicks = DEFAULT_REPEAT_MERGE_TICKS;
		config.conversionGapPercentile = DEFAULT_CONVERSION_GAP_PERCENTILE;
		config.commandsPerTick = DEFAULT_COMMANDS_PER_TICK;
		config.maxBuildFloors = DEFAULT_MAX_BUILD_FLOORS;
		return config;
	}

	private static OverlayMode migrateOverlayMode(StoredConfig stored) {
		if (Boolean.FALSE.equals(stored.overlaysEnabled)) {
			return OverlayMode.OFF;
		}
		return Boolean.FALSE.equals(stored.noteBlockOverlaysEnabled)
			? OverlayMode.OFF
			: OverlayMode.BOTH;
	}

	private static int clampViewDistance(int distance) {
		return Math.max(MIN_VIEW_DISTANCE, Math.min(MAX_VIEW_DISTANCE, distance));
	}

	private static int clampInteractionDelay(int ticks) {
		return Math.max(MIN_INTERACTION_DELAY_TICKS, Math.min(MAX_INTERACTION_DELAY_TICKS, ticks));
	}

	private static int clampRadialFocusDelay(int ticks) {
		return Math.max(MIN_RADIAL_FOCUS_DELAY_TICKS, Math.min(MAX_RADIAL_FOCUS_DELAY_TICKS, ticks));
	}

	public static int clampSequenceDelayScale(int delayScaleQuarters) {
		return Math.max(MIN_SEQUENCE_DELAY_SCALE_QUARTERS, Math.min(MAX_SEQUENCE_DELAY_SCALE_QUARTERS, delayScaleQuarters));
	}

	public static String delayScaleLabel(int delayScaleQuarters) {
		return String.format(java.util.Locale.ROOT, "%.2fx", clampSequenceDelayScale(delayScaleQuarters) / 4.0F);
	}

	private static int clampMidiMaxImportedTracks(int tracks) {
		return Math.max(MIN_MIDI_MAX_IMPORTED_TRACKS, Math.min(MAX_MIDI_MAX_IMPORTED_TRACKS, tracks));
	}

	private static int clampMaxBuildFloors(int floors) {
		return Math.max(MIN_MAX_BUILD_FLOORS, Math.min(MAX_MAX_BUILD_FLOORS, floors));
	}

	private static int clampCommandsPerTick(int commands) {
		return Math.max(MIN_COMMANDS_PER_TICK, Math.min(MAX_COMMANDS_PER_TICK, commands));
	}

	private static int clampConversionGapPercentile(int percentile) {
		return Math.max(MIN_CONVERSION_GAP_PERCENTILE,
			Math.min(MAX_CONVERSION_GAP_PERCENTILE, percentile));
	}

	private static int clampRepeatMergeTicks(int ticks) {
		return Math.max(MIN_REPEAT_MERGE_TICKS, Math.min(MAX_REPEAT_MERGE_TICKS, ticks));
	}

	private static int clampMidiVelocityCutoff(int velocity) {
		return Math.max(MIN_MIDI_VELOCITY_CUTOFF, Math.min(MAX_MIDI_VELOCITY_CUTOFF, velocity));
	}

	private static int legacyTimescaleToQuarters(Integer timescale) {
		return timescale == null ? DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS : timescale * 4;
	}

	private static List<SequenceTrack> normalizeTracks(List<SequenceTrack> tracks) {
		List<SequenceTrack> normalized = new ArrayList<>();
		if (tracks != null) {
			for (SequenceTrack track : tracks) {
				if (track != null && normalized.size() < MAX_TRACKS) {
					normalized.add(new SequenceTrack(track.name(), track.sequence(), track.instrument(), track.position(),
						track.buildEnabled()));
				}
			}
		}
		if (normalized.isEmpty()) {
			normalized.add(new SequenceTrack("Track 1", "", "HARP", 0));
		}
		return List.copyOf(normalized);
	}

	private static List<SequenceTrack> enableAllBuildTracks(List<SequenceTrack> tracks) {
		List<SequenceTrack> enabled = new ArrayList<>();
		for (SequenceTrack track : normalizeTracks(tracks)) {
			enabled.add(track.withBuildEnabled(true));
		}
		return List.copyOf(enabled);
	}

	private static int clampTrackIndex(int index, int size) {
		return Math.max(0, Math.min(Math.max(1, size) - 1, index));
	}

	private void updateActiveTrack(SequenceTrack track) {
		List<SequenceTrack> updated = new ArrayList<>(tracks);
		updated.set(clampTrackIndex(activeTrackIndex, updated.size()), track);
		tracks = List.copyOf(updated);
	}

	private void syncLegacyTrackFields() {
		SequenceTrack active = activeTrack();
		placementSequence = active.sequence();
		placementSequencePosition = active.position();
		previewInstrument = active.instrument();
	}

	private static final class StoredConfig {
		private Boolean modEnabled;
		private OverlayMode overlayMode;
		private OverlayMode previousOverlayMode;
		// Legacy fields retained for migration from versions before the overlay selector.
		private Boolean overlaysEnabled;
		private Boolean noteBlockOverlaysEnabled;
		private Boolean nearbyPreviewsEnabled;
		private Boolean interactiveControlsEnabled;
		private Integer radialFocusDelayTicks;
		private RepeaterControlStyle repeaterControlStyle;
		private Boolean radialControlsEnabled;
		private Boolean invertScrolling;
		private Integer viewDistance;
		private Integer interactionDelayTicks;
		private Boolean waitForServerAcknowledgement;
		private Boolean requireLineOfSight;
		private Boolean placementSequenceEnabled;
		private SequencingEditProtection sequencingEditProtection;
		private Boolean autoSelectSequenceBlock;
		private String placementSequence;
		private Integer placementSequencePosition;
		private String activeSequenceName;
		private Integer activeSequenceDelayScaleQuarters;
		private Integer activeSequenceTimescale;
		private String previewInstrument;
		private List<SequenceTrack> tracks;
		private ComposerProject composerProject;
		private String activeSongId;
		private Boolean buildTrackFlagsInitialized;
		private Integer activeTrackIndex;
		private List<SavedSequence> savedSequences;
		private MidiQuantizeGrid midiQuantizeGrid;
		private MidiRangeFit midiRangeFit;
		private Boolean midiIgnorePercussion;
		private Integer midiMaxImportedTracks;
		private String midiDefaultInstrument;
		private MidiTempoFit midiTempoFit;
		private Integer midiVelocityCutoff;
		private Integer composerSpeedQuarters;
		private Integer repeatMergeTicks;
		private Integer conversionGapPercentile;
		private Integer commandsPerTick;
		private Integer maxBuildFloors;

		private StoredConfig() {
		}

		private StoredConfig(FastNoteblocksConfig config) {
			this.modEnabled = config.modEnabled;
			this.overlayMode = config.overlayMode;
			this.previousOverlayMode = config.previousOverlayMode;
			this.nearbyPreviewsEnabled = config.nearbyPreviewsEnabled;
			this.interactiveControlsEnabled = config.interactiveControlsEnabled;
			this.radialFocusDelayTicks = config.radialFocusDelayTicks;
			this.repeaterControlStyle = config.repeaterControlStyle;
			this.invertScrolling = config.invertScrolling;
			this.viewDistance = config.viewDistance;
			this.interactionDelayTicks = config.interactionDelayTicks;
			this.waitForServerAcknowledgement = config.waitForServerAcknowledgement;
			this.requireLineOfSight = config.requireLineOfSight;
			this.placementSequenceEnabled = config.placementSequenceEnabled;
			this.sequencingEditProtection = config.sequencingEditProtection;
			this.autoSelectSequenceBlock = config.autoSelectSequenceBlock;
			this.placementSequence = config.placementSequence;
			this.placementSequencePosition = config.placementSequencePosition;
			this.activeSequenceName = config.activeSequenceName;
			this.activeSequenceDelayScaleQuarters = config.activeSequenceDelayScaleQuarters;
			this.previewInstrument = config.previewInstrument;
			this.tracks = config.tracks;
			// Songs live in their own files now. Leaving these null keeps the settings file small
			// and stops one bad composition from taking every setting down with it on load.
			this.composerProject = null;
			this.activeSongId = config.activeSongId;
			this.buildTrackFlagsInitialized = config.buildTrackFlagsInitialized;
			this.activeTrackIndex = config.activeTrackIndex;
			this.savedSequences = null;
			this.midiQuantizeGrid = config.midiQuantizeGrid;
			this.midiRangeFit = config.midiRangeFit;
			this.midiIgnorePercussion = config.midiIgnorePercussion;
			this.midiMaxImportedTracks = config.midiMaxImportedTracks;
			this.midiDefaultInstrument = config.midiDefaultInstrument;
			this.midiTempoFit = config.midiTempoFit;
			this.midiVelocityCutoff = config.midiVelocityCutoff;
			this.composerSpeedQuarters = config.composerSpeedQuarters;
			this.repeatMergeTicks = config.repeatMergeTicks;
			this.conversionGapPercentile = config.conversionGapPercentile;
			this.commandsPerTick = config.commandsPerTick;
			this.maxBuildFloors = config.maxBuildFloors;
		}
	}
}
