package com.fastnoteblocks.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.client.composer.ComposerProject;
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
	public static final int DEFAULT_MIDI_MAX_IMPORTED_TRACKS = ComposerProject.MAX_LAYERS;
	public static final int MIN_MIDI_MAX_IMPORTED_TRACKS = 1;
	public static final int MAX_MIDI_MAX_IMPORTED_TRACKS = MAX_TRACKS;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("fast-noteblocks.json");
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
	private String previewInstrument;
	private List<SequenceTrack> tracks;
	private ComposerProject composerProject;
	private boolean buildTrackFlagsInitialized;
	private int activeTrackIndex;
	private List<SavedSequence> savedSequences;
	private MidiQuantizeGrid midiQuantizeGrid;
	private MidiRangeFit midiRangeFit;
	private boolean midiIgnorePercussion;
	private int midiMaxImportedTracks;
	private String midiDefaultInstrument;
	private MidiTempoFit midiTempoFit;

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
				instance.composerProject = stored.composerProject == null
					? ComposerProject.fromSequenceTracks(instance.activeSequenceName, instance.tracks,
						instance.activeTrackIndex, instance.activeSequenceDelayScaleQuarters)
					: stored.composerProject;
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
			}
		} catch (Exception ignored) {
			instance = defaults();
		}
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

	public void setActiveSequenceDelayScaleQuarters(int activeSequenceDelayScaleQuarters) {
		this.activeSequenceDelayScaleQuarters = clampSequenceDelayScale(activeSequenceDelayScaleQuarters);
		if (composerProject != null) {
			tracks = normalizeTracks(composerProject.toSequenceTracks(tracks, this.activeSequenceDelayScaleQuarters));
			syncLegacyTrackFields();
		}
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

	public void setTracks(List<SequenceTrack> tracks) {
		this.tracks = normalizeTracks(tracks);
		activeTrackIndex = clampTrackIndex(activeTrackIndex, this.tracks.size());
		composerProject = ComposerProject.fromSequenceTracks(
			activeSequenceName, this.tracks, activeTrackIndex, activeSequenceDelayScaleQuarters
		);
		syncLegacyTrackFields();
	}

	public ComposerProject composerProject() {
		if (composerProject == null) {
			composerProject = ComposerProject.fromSequenceTracks(
				activeSequenceName, tracks, activeTrackIndex, activeSequenceDelayScaleQuarters
			);
		}
		return composerProject;
	}

	public void setComposerProject(ComposerProject project) {
		composerProject = project == null
			? ComposerProject.fromSequenceTracks(activeSequenceName, tracks, activeTrackIndex,
				activeSequenceDelayScaleQuarters)
			: project;
		activeSequenceName = composerProject.name();
		activeTrackIndex = composerProject.activeLayerIndex();
		tracks = normalizeTracks(composerProject.toSequenceTracks(tracks, activeSequenceDelayScaleQuarters));
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

	public List<SavedSequence> savedSequences() {
		return List.copyOf(savedSequences);
	}

	public void setSavedSequences(List<SavedSequence> savedSequences) {
		this.savedSequences = savedSequences == null ? new ArrayList<>() : new ArrayList<>(savedSequences);
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
		private Boolean buildTrackFlagsInitialized;
		private Integer activeTrackIndex;
		private List<SavedSequence> savedSequences;
		private MidiQuantizeGrid midiQuantizeGrid;
		private MidiRangeFit midiRangeFit;
		private Boolean midiIgnorePercussion;
		private Integer midiMaxImportedTracks;
		private String midiDefaultInstrument;
		private MidiTempoFit midiTempoFit;

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
			this.composerProject = config.composerProject;
			this.buildTrackFlagsInitialized = config.buildTrackFlagsInitialized;
			this.activeTrackIndex = config.activeTrackIndex;
			this.savedSequences = config.savedSequences;
			this.midiQuantizeGrid = config.midiQuantizeGrid;
			this.midiRangeFit = config.midiRangeFit;
			this.midiIgnorePercussion = config.midiIgnorePercussion;
			this.midiMaxImportedTracks = config.midiMaxImportedTracks;
			this.midiDefaultInstrument = config.midiDefaultInstrument;
			this.midiTempoFit = config.midiTempoFit;
		}
	}
}
