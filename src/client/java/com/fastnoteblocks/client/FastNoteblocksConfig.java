package com.fastnoteblocks.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.fastnoteblocks.NoteSequence;
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
	public static final int MAX_TRACKS = 4;

	public record SequenceTrack(String name, String sequence, String instrument, int position) {
		public SequenceTrack {
			name = name == null || name.isBlank() ? "Track" : name.trim();
			sequence = sequence == null ? "" : sequence;
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			position = Math.max(0, position);
		}

		public SequenceTrack withName(String value) {
			return new SequenceTrack(value, sequence, instrument, position);
		}

		public SequenceTrack withSequence(String value) {
			return new SequenceTrack(name, value, instrument, position);
		}

		public SequenceTrack withInstrument(String value) {
			return new SequenceTrack(name, sequence, value, position);
		}

		public SequenceTrack withPosition(int value) {
			return new SequenceTrack(name, sequence, instrument, value);
		}
	}

	public record SavedSequence(String name, List<SequenceTrack> tracks, int activeTrackIndex, String sequence) {
		public SavedSequence {
			name = name == null || name.isBlank() ? "Untitled sequence" : name.trim();
			tracks = tracks == null || tracks.isEmpty()
				? List.of(new SequenceTrack("Track 1", sequence, "HARP", 0))
				: normalizeTracks(tracks);
			activeTrackIndex = clampTrackIndex(activeTrackIndex, tracks.size());
			sequence = null;
		}

		public SavedSequence(String name, String sequence) {
			this(name, null, 0, sequence);
		}

		public SavedSequence(String name, List<SequenceTrack> tracks, int activeTrackIndex) {
			this(name, tracks, activeTrackIndex, null);
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

	public static final int DEFAULT_VIEW_DISTANCE = 10;
	public static final int MIN_VIEW_DISTANCE = 1;
	public static final int MAX_VIEW_DISTANCE = 32;
	public static final int DEFAULT_INTERACTION_DELAY_TICKS = 0;
	public static final int MIN_INTERACTION_DELAY_TICKS = 0;
	public static final int MAX_INTERACTION_DELAY_TICKS = 10;
	public static final int DEFAULT_RADIAL_FOCUS_DELAY_TICKS = 5;
	public static final int MIN_RADIAL_FOCUS_DELAY_TICKS = 0;
	public static final int MAX_RADIAL_FOCUS_DELAY_TICKS = 20;
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
	private String previewInstrument;
	private List<SequenceTrack> tracks;
	private int activeTrackIndex;
	private List<SavedSequence> savedSequences;

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
					? RepeaterControlStyle.RADIAL_SELECT
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
				instance.autoSelectSequenceBlock = Boolean.TRUE.equals(stored.autoSelectSequenceBlock);
				instance.placementSequence = stored.placementSequence == null ? "" : stored.placementSequence;
				instance.placementSequencePosition = Math.max(0,
					stored.placementSequencePosition == null ? 0 : stored.placementSequencePosition
				);
				instance.activeSequenceName = stored.activeSequenceName == null || stored.activeSequenceName.isBlank()
					? "Untitled sequence"
					: stored.activeSequenceName;
				instance.previewInstrument = stored.previewInstrument == null ? "HARP" : stored.previewInstrument;
				instance.tracks = stored.tracks == null || stored.tracks.isEmpty()
					? List.of(new SequenceTrack("Track 1", instance.placementSequence,
						instance.previewInstrument, instance.placementSequencePosition))
					: normalizeTracks(stored.tracks);
				instance.activeTrackIndex = clampTrackIndex(
					stored.activeTrackIndex == null ? 0 : stored.activeTrackIndex, instance.tracks.size()
				);
				instance.savedSequences = stored.savedSequences == null
					? new ArrayList<>()
					: new ArrayList<>(stored.savedSequences);
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
		return NoteSequence.parse(value);
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
			? RepeaterControlStyle.RADIAL_SELECT
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

	private static FastNoteblocksConfig defaults() {
		FastNoteblocksConfig config = new FastNoteblocksConfig();
		config.modEnabled = true;
		config.overlayMode = OverlayMode.NOTES_ONLY;
		config.previousOverlayMode = OverlayMode.NOTES_ONLY;
		config.nearbyPreviewsEnabled = true;
		config.interactiveControlsEnabled = true;
		config.radialFocusDelayTicks = DEFAULT_RADIAL_FOCUS_DELAY_TICKS;
		config.repeaterControlStyle = RepeaterControlStyle.RADIAL_SELECT;
		config.invertScrolling = false;
		config.viewDistance = DEFAULT_VIEW_DISTANCE;
		config.interactionDelayTicks = DEFAULT_INTERACTION_DELAY_TICKS;
		config.waitForServerAcknowledgement = false;
		config.requireLineOfSight = false;
		config.placementSequenceEnabled = false;
		config.sequencingEditProtection = SequencingEditProtection.RADIALS_AND_INTERACTIONS;
		config.autoSelectSequenceBlock = false;
		config.placementSequence = "";
		config.placementSequencePosition = 0;
		config.activeSequenceName = "Untitled sequence";
		config.previewInstrument = "HARP";
		config.tracks = List.of(new SequenceTrack("Track 1", "", "HARP", 0));
		config.activeTrackIndex = 0;
		config.savedSequences = new ArrayList<>();
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

	private static List<SequenceTrack> normalizeTracks(List<SequenceTrack> tracks) {
		List<SequenceTrack> normalized = new ArrayList<>();
		if (tracks != null) {
			for (SequenceTrack track : tracks) {
				if (track != null && normalized.size() < MAX_TRACKS) {
					normalized.add(new SequenceTrack(track.name(), track.sequence(), track.instrument(), track.position()));
				}
			}
		}
		if (normalized.isEmpty()) {
			normalized.add(new SequenceTrack("Track 1", "", "HARP", 0));
		}
		return List.copyOf(normalized);
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
		private String previewInstrument;
		private List<SequenceTrack> tracks;
		private Integer activeTrackIndex;
		private List<SavedSequence> savedSequences;

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
			this.previewInstrument = config.previewInstrument;
			this.tracks = config.tracks;
			this.activeTrackIndex = config.activeTrackIndex;
			this.savedSequences = config.savedSequences;
		}
	}
}
