package com.midicraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.midicraft.NoteSequence;
import com.midicraft.client.composer.ChordThinner;
import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.SongLibrary;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public final class MidicraftConfig {
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

		/** The types either of two settings asks for -- what the scan and the render must cover. */
		public OverlayMode or(OverlayMode other) {
			boolean bothNotes = notes || other.notes;
			boolean bothRepeaters = repeaters || other.repeaters;
			return bothNotes && bothRepeaters ? BOTH
				: bothNotes ? NOTES_ONLY
				: bothRepeaters ? REPEATERS_ONLY
				: OFF;
		}
	}

	public enum RepeaterControlStyle {
		RADIAL_SELECT,
		SCROLL
	}

	/** How the sequencer walks the notes of one chord. See {@link #chordPlaceOrder()}. */
	public enum ChordPlaceOrder {
		TWO_STRIPS,
		ALTERNATING
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


	/**
	 * Where an imported track's note block instrument comes from.
	 *
	 * <p>{@code DEFAULT_ONLY} is what every import did before there was a choice: one instrument for
	 * the whole file. The other two read what the file is carrying -- its General MIDI program
	 * changes, and then its track names, which is where a file that never sent a program change
	 * usually keeps the same information.</p>
	 */
	public enum MidiInstrumentSource {
		DEFAULT_ONLY,
		FROM_FILE,
		FROM_FILE_THEN_NAME
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
	 * How wide a Compact lane build is allowed to get before it folds back.
	 *
	 * <p>A build decision rather than a preference, but it belongs here because it is the one thing
	 * about a paste you want to keep between pastes -- it is usually a property of the plot you are
	 * building on, not of the song.</p>
	 */
	public static final int DEFAULT_BUILD_LANE_WIDTH = 32;
	// Eight, up from four. Below about a dozen the walls stand no closer than the widest chord needs
	// anyway, so the paste comes out wider than the slider says whatever it says, and a width of
	// four is not worth supporting. A saved value below this reads as this.
	public static final int MIN_BUILD_LANE_WIDTH = 8;
	public static final int MAX_BUILD_LANE_WIDTH = 128;
	/**
	 * Floors a Compact lane build folds onto. Each one retraces the one below it, so three floors is
	 * a third of the length in the same footprint. Capped low on purpose: unlike a cube, which is
	 * sized to be looked at from outside, a lane is something you stand next to.
	 */
	public static final int DEFAULT_BUILD_LANE_FLOORS = 1;
	public static final int MIN_BUILD_LANE_FLOORS = 1;
	public static final int MAX_BUILD_LANE_FLOORS = 16;
	/**
	 * Game ticks a half-tick lane may pad through before it reseeds onto the other parity.
	 *
	 * <p>Only the Interleaved half-tick paste reads this. Both its machines run the whole song,
	 * and a machine whose half of the game tick has nothing to play still spends corridor: delay
	 * chain to cross the silence, and dust to stay within earshot of its partner. Past this much
	 * waiting it gives its half up instead -- a sticky piston's three game ticks -- and both
	 * machines share the busy half, which carries the same music in half the depth.</p>
	 *
	 * <p>Lower spends more pistons to save more space. The maximum leaves each machine on one
	 * half of the tick for the whole song, which is what this mode did before reseeding.</p>
	 */
	public static final int DEFAULT_PARITY_RESEED_DELAY = 64;
	public static final int MIN_PARITY_RESEED_DELAY = 16;
	public static final int MAX_PARITY_RESEED_DELAY = 512;
	/** Repeats of a pitch closer than this many repeater ticks collapse on convert. 0 disables. */
	public static final int DEFAULT_REPEAT_MERGE_TICKS = 1;
	public static final int MIN_REPEAT_MERGE_TICKS = 0;
	public static final int MAX_REPEAT_MERGE_TICKS = 8;
	/**
	 * Notes quieter than this are dropped on import, because note blocks have no volume and a
	 * near-silent note arrives at full volume.
	 *
	 * <p>Was 32, the MIDI "pp" threshold, which is far too eager. A quiet piece can spend its
	 * whole dynamic range below it -- Zoltraak.mid spans 16 to 47, so 32 removed its entire
	 * fourteen-second opening crescendo. 8 still catches the near-inaudible tail of a fake-sustain
	 * ramp while leaving real music alone, and Merge repeats is the tool that actually handles
	 * fake sustain, regardless of how loud it is.</p>
	 */
	public static final int DEFAULT_MIDI_VELOCITY_CUTOFF = 0;
	public static final int MIN_MIDI_VELOCITY_CUTOFF = 0;
	public static final int MAX_MIDI_VELOCITY_CUTOFF = 127;
	/**
	 * How many sounds a chord is thinned down to when Select > Overloaded chords is used.
	 *
	 * <p>Thirty is the hard limit -- fifteen reachable bus blocks with two note blocks on each --
	 * so anything at or under it builds. The default sits below rather than on it because a chord
	 * exactly on the limit leaves the world paste nothing to work with.</p>
	 */
	/**
	 * How wide the composer's layer panel is, and whether it is folded away entirely.
	 *
	 * <p>Kept here rather than in the screen because dragging it shut is a decision about how you
	 * want to work, and having to make it again every time the composer opens would be a reason
	 * not to bother.</p>
	 */
	public static final int DEFAULT_LAYER_PANEL_WIDTH = 150;
	/**
	 * Narrow enough to hold an instrument icon and a row number and nothing else, which is as far
	 * as dragging goes before the panel folds instead.
	 */
	public static final int MIN_LAYER_PANEL_WIDTH = 34;
	public static final int MAX_LAYER_PANEL_WIDTH = 900;
	/**
	 * GUI scale for the mod's own screens, or 0 to leave the game's alone.
	 *
	 * <p>The Composer is a piano roll, and a piano roll wants pixels: the scale that suits
	 * reading a hotbar is not the scale that suits seeing four bars of a song at once. The cap is
	 * nominal -- the window clamps whatever it is given to what actually fits.</p>
	 */
	public static final int SAME_GUI_SCALE_AS_MINECRAFT = 0;
	public static final int MIN_COMPOSER_GUI_SCALE = SAME_GUI_SCALE_AS_MINECRAFT;
	public static final int MAX_COMPOSER_GUI_SCALE = 6;
	public static final int DEFAULT_COMPOSER_GUI_SCALE = SAME_GUI_SCALE_AS_MINECRAFT;
	public static final int DEFAULT_CHORD_THIN_TARGET = 25;
	public static final int MIN_CHORD_THIN_TARGET = ChordThinner.MIN_TARGET;
	public static final int MAX_CHORD_THIN_TARGET = ChordThinner.MAX_TARGET;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("midicraft.json");
	private static SongLibrary songs = SongLibrary.load();
	private static MidicraftConfig instance = defaults();

	private boolean modEnabled;
	/**
	 * Labels drawn over every block in range, whether or not you are looking at one.
	 *
	 * <p>Purely something to read. Kept apart from the interactive overlay because the two are
	 * wanted at different times: seeing what a wall of note blocks is tuned to is not the same
	 * job as retuning the one under your crosshair, and a single mode governing both meant
	 * turning labels on also armed the scroll wheel, or worse, showed labels that ignored it.</p>
	 */
	private OverlayMode nearbyOverlays;
	/** The overlay you can act on: the block under the crosshair. What the overlay key toggles. */
	private boolean interactiveOverlays;
	/** Which block types the interactive overlay answers for. Never {@code OFF}. */
	private OverlayMode interactiveOverlayType;
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
	private boolean selectInstruments;
	private ChordPlaceOrder chordPlaceOrder;
	private boolean selectHarpBlocks;
	private boolean dedupeIdenticalNotes;
	private String activeSequenceName;
	private int activeSequenceDelayScaleQuarters;
	private int composerSpeedQuarters;
	private String previewInstrument;
	/**
	 * How far along placing you are, per song. The only build state that outlives an edit.
	 *
	 * <p>One number for the whole mod was right when there was one composition. A library of
	 * thirty-six is thirty-six builds that can be in progress at once, and opening a song to check
	 * something should not cost you your place in the one you were laying.</p>
	 */
	private Map<String, Integer> placementCursors = new LinkedHashMap<>();
	/** The place in a composition with no file behind it yet, which has no id to be keyed by. */
	private int placementCursor;
	private transient int documentGeneration;
	private transient ComposerProject cachedSequenceProject;
	private transient List<SequenceTrack> cachedSequence;
	private ComposerProject composerProject;
	private List<SequenceTrack> legacyTracks = List.of();
	private String activeSongId;
	private boolean buildTrackFlagsInitialized;
	private int activeTrackIndex;
	private List<SavedSequence> savedSequences;
	private MidiQuantizeGrid midiQuantizeGrid;
	private ComposerProject.OctaveShifting convertOctaveShifting;
	private boolean convertSplitTransposed;
	private boolean convertBakesSpeed;
	private boolean convertMergesRepeats;
	private boolean convertQuantizes;
	private boolean convertFitsRange;
	private boolean convertSnapsTempo;
	private boolean convertSnapsEnd;
	private boolean midiIgnorePercussion;
	private String midiDefaultInstrument;
	private MidiInstrumentSource midiInstrumentSource;
	private boolean debugCommandsEnabled;
	/** Whether the one-time "the Composer is behind /midicraft" line has been said. */
	private boolean seenWelcome;
	private boolean debugPasteEnabled;
	private int midiVelocityCutoff;
	private int chordThinTarget;
	private int composerGuiScale;
	private int layerPanelWidth;
	private boolean layerPanelCollapsed;
	/**
	 * Whether the song library and the file browser list by name rather than newest first.
	 *
	 * <p>Kept beside the panel width rather than offered on the settings screen: it is view state,
	 * set where it applies. It has to persist because the file browser is built fresh on every
	 * import, and a choice that reset itself each time would not be a choice.</p>
	 */
	private boolean listSortByName;
	/**
	 * When each song was last opened, by id.
	 *
	 * <p>Here rather than in the song itself, because opening a song is not a change to it: writing
	 * the time into the file would rewrite most of a megabyte to record that somebody looked, and
	 * would move the very timestamp it was meant to replace.</p>
	 *
	 * <p>Ids that no longer name a song are dropped on the way out, so deleting a composition takes
	 * its entry with it rather than leaving the map to grow for the life of the install.</p>
	 */
	private final Map<String, Long> songOpenedAt = new LinkedHashMap<>();
	private int repeatMergeTicks;
	private int conversionGapPercentile;
	private double commandsPerTick;
	private int buildLaneWidth;
	private int buildLaneFloors;
	private int parityReseedDelay;
	private boolean ultraLaneStartTop;
	private String importDirectory;
	private int maxBuildFloors;
	private String pasteMode;

	private MidicraftConfig() {
	}

	public static MidicraftConfig get() {
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
				// One mode used to govern both overlays, with two booleans beside it. Split in two:
				// the nearby labels take that mode if previews were on and go dark if they were
				// not, and the interactive overlay keeps its own on/off and inherits the same
				// types -- so a config from before the split comes back looking as it did.
				OverlayMode legacyMode = stored.overlayMode == null
					? migrateOverlayMode(stored)
					: stored.overlayMode;
				boolean legacyPreviews = stored.nearbyPreviewsEnabled == null
					|| stored.nearbyPreviewsEnabled;
				instance.nearbyOverlays = stored.nearbyOverlays != null
					? stored.nearbyOverlays
					: legacyPreviews ? legacyMode : OverlayMode.OFF;
				instance.interactiveOverlays = stored.interactiveOverlays != null
					? stored.interactiveOverlays
					: stored.interactiveControlsEnabled == null
						? stored.radialControlsEnabled == null || stored.radialControlsEnabled
						: stored.interactiveControlsEnabled;
				instance.setInteractiveOverlayType(stored.interactiveOverlayType != null
					? stored.interactiveOverlayType
					: legacyMode);
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
				instance.selectInstruments = Boolean.TRUE.equals(stored.selectInstruments);
				instance.chordPlaceOrder = stored.chordPlaceOrder == null
					? ChordPlaceOrder.TWO_STRIPS
					: stored.chordPlaceOrder;
				instance.selectHarpBlocks = stored.selectHarpBlocks == null || stored.selectHarpBlocks;
				instance.dedupeIdenticalNotes = stored.dedupeIdenticalNotes == null || stored.dedupeIdenticalNotes;
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
				// Tracks in an old settings file are migration input, not state: the sequence is
				// derived now. They are kept only long enough to rebuild a composition that predates
				// the library, in migrateSongsOutOfSettings.
				instance.legacyTracks = stored.tracks == null || stored.tracks.isEmpty()
					? List.of(new SequenceTrack("Track 1",
						stored.placementSequence == null ? "" : stored.placementSequence,
						instance.previewInstrument,
						stored.placementSequencePosition == null ? 0 : stored.placementSequencePosition))
					: normalizeTracks(stored.tracks);
				instance.buildTrackFlagsInitialized = true;
				instance.placementCursor = Math.max(0,
					stored.placementSequencePosition == null ? 0 : stored.placementSequencePosition);
				instance.activeTrackIndex = stored.activeTrackIndex == null ? 0 : stored.activeTrackIndex;
				// Read only: whatever is here is pre-library data waiting to be migrated out.
				instance.composerProject = stored.composerProject == null
					? null
					: stored.composerProject.withSpeedQuarters(instance.composerSpeedQuarters);
				instance.activeSongId = stored.activeSongId;
				instance.placementCursors = stored.placementCursors == null
					? new LinkedHashMap<>()
					: new LinkedHashMap<>(stored.placementCursors);
				// A settings file written before bookmarks carries one position, and it belonged to
				// whichever song was open when it was written. Give it to that song rather than
				// dropping it, so an existing build in progress survives the upgrade.
				if (stored.placementCursors == null
					&& instance.activeSongId != null
					&& instance.placementCursor > 0) {
					instance.placementCursors.put(instance.activeSongId, instance.placementCursor);
				}
				instance.savedSequences = stored.savedSequences == null
					? new ArrayList<>()
					: new ArrayList<>(stored.savedSequences);
				if (!Boolean.TRUE.equals(stored.buildTrackFlagsInitialized)) {
					instance.savedSequences = instance.savedSequences.stream()
						.map(saved -> new SavedSequence(saved.name(), enableAllBuildTracks(saved.tracks()),
							saved.activeTrackIndex(), saved.delayScaleQuarters(), saved.composerProject()))
						.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
				}
				instance.midiQuantizeGrid = stored.midiQuantizeGrid == null ? MidiQuantizeGrid.AUTO : stored.midiQuantizeGrid;
				instance.midiIgnorePercussion = stored.midiIgnorePercussion == null || stored.midiIgnorePercussion;
				instance.debugCommandsEnabled = Boolean.TRUE.equals(stored.debugCommandsEnabled);
				// An existing config means an existing player, who does not need introducing.
				instance.seenWelcome = stored.seenWelcome == null || stored.seenWelcome;
				instance.setDebugPasteEnabled(Boolean.TRUE.equals(stored.debugPasteEnabled));
				instance.midiDefaultInstrument = stored.midiDefaultInstrument == null || stored.midiDefaultInstrument.isBlank()
					? "HARP"
					: stored.midiDefaultInstrument;
				// A config written before the setting existed is one that never asked for a single
				// instrument -- it just never had the choice -- so it opens on reading the file.
				instance.midiInstrumentSource = stored.midiInstrumentSource == null
					? MidiInstrumentSource.FROM_FILE_THEN_NAME
					: stored.midiInstrumentSource;
				instance.maxBuildFloors = clampMaxBuildFloors(
					stored.maxBuildFloors == null ? DEFAULT_MAX_BUILD_FLOORS : stored.maxBuildFloors
				);
				instance.commandsPerTick = clampCommandsPerTick(
					stored.commandsPerTick == null ? PasteRate.DEFAULT : stored.commandsPerTick
				);
				instance.buildLaneWidth = clampBuildLaneWidth(
					stored.buildLaneWidth == null ? DEFAULT_BUILD_LANE_WIDTH : stored.buildLaneWidth
				);
				instance.buildLaneFloors = clampBuildLaneFloors(
					stored.buildLaneFloors == null ? DEFAULT_BUILD_LANE_FLOORS : stored.buildLaneFloors
				);
				instance.setParityReseedDelay(
					stored.parityReseedDelay == null
						? DEFAULT_PARITY_RESEED_DELAY : stored.parityReseedDelay
				);
				instance.ultraLaneStartTop = stored.ultraLaneStartTop != null
					&& stored.ultraLaneStartTop;
				instance.importDirectory = stored.importDirectory;
				instance.conversionGapPercentile = clampConversionGapPercentile(
					stored.conversionGapPercentile == null
						? DEFAULT_CONVERSION_GAP_PERCENTILE
						: stored.conversionGapPercentile
				);
				// A file written before these existed says nothing about them, which is the default.
				instance.convertOctaveShifting = stored.convertOctaveShifting == null
					? ComposerProject.OctaveShifting.NOTES_ONLY
					: stored.convertOctaveShifting;
				instance.convertSplitTransposed = stored.convertSplitTransposed == null
					|| stored.convertSplitTransposed;
				instance.convertBakesSpeed = stored.convertBakesSpeed == null || stored.convertBakesSpeed;
				instance.convertMergesRepeats = stored.convertMergesRepeats == null || stored.convertMergesRepeats;
				instance.convertQuantizes = stored.convertQuantizes == null || stored.convertQuantizes;
				instance.convertFitsRange = stored.convertFitsRange == null || stored.convertFitsRange;
				instance.convertSnapsTempo = stored.convertSnapsTempo == null || stored.convertSnapsTempo;
				instance.convertSnapsEnd = stored.convertSnapsEnd == null || stored.convertSnapsEnd;
				instance.repeatMergeTicks = clampRepeatMergeTicks(
					stored.repeatMergeTicks == null
						? DEFAULT_REPEAT_MERGE_TICKS
						: stored.repeatMergeTicks
				);
				instance.pasteMode = stored.pasteMode;
				instance.midiVelocityCutoff = clampMidiVelocityCutoff(
					stored.midiVelocityCutoff == null
						? DEFAULT_MIDI_VELOCITY_CUTOFF
						: stored.midiVelocityCutoff
				);
				instance.chordThinTarget = clampChordThinTarget(
					stored.chordThinTarget == null
						? DEFAULT_CHORD_THIN_TARGET
						: stored.chordThinTarget
				);
				instance.setComposerGuiScale(stored.composerGuiScale == null
					? DEFAULT_COMPOSER_GUI_SCALE
					: stored.composerGuiScale);
				instance.layerPanelWidth = clampLayerPanelWidth(
					stored.layerPanelWidth == null
						? DEFAULT_LAYER_PANEL_WIDTH
						: stored.layerPanelWidth
				);
				instance.layerPanelCollapsed = Boolean.TRUE.equals(stored.layerPanelCollapsed);
				instance.listSortByName = Boolean.TRUE.equals(stored.listSortByName);
				if (stored.songOpenedAt != null) {
					stored.songOpenedAt.forEach((id, at) -> {
						if (id != null && at != null && at > 0L) {
							instance.songOpenedAt.put(id, at);
						}
					});
				}
			}
		} catch (Exception ignored) {
			instance = defaults();
		}
		songs = SongLibrary.load();
		boolean migrated = songs.isEmpty();
		instance.activeSongId = migrateSongsOutOfSettings(instance.activeSongId);
		if (migrated) {
			// Rewrite immediately so the settings file sheds the compositions it used to carry,
			// rather than staying huge until something else happens to save.
			save();
		}
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
		ComposerProject active = instance.composerProject;
		if (active == null || active.noteCount() == 0) {
			// No composition stored, but the old settings file may still hold build tracks from
			// before compositions existed at all. Rebuilding from them is lossy in the usual ways,
			// and is the only thing there is to rebuild from.
			ComposerProject fromTracks = ComposerProject.fromSequenceTracks(
				instance.activeSequenceName, instance.legacyTracks, 0,
				instance.activeSequenceDelayScaleQuarters);
			active = fromTracks.noteCount() > 0 ? fromTracks : null;
		}
		if (active != null) {
			activeId = songs.newId(active.name());
			songs.save(activeId, active);
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
			return Optional.of(Component.translatable("error.midicraft.sequence"));
		}
	}

	public boolean modEnabled() {
		return modEnabled;
	}

	public void setModEnabled(boolean modEnabled) {
		this.modEnabled = modEnabled;
	}

	public OverlayMode nearbyOverlays() {
		return nearbyOverlays == null ? OverlayMode.OFF : nearbyOverlays;
	}

	public void setNearbyOverlays(OverlayMode nearbyOverlays) {
		this.nearbyOverlays = nearbyOverlays == null ? OverlayMode.OFF : nearbyOverlays;
	}

	public boolean interactiveOverlays() {
		return interactiveOverlays;
	}

	public void setInteractiveOverlays(boolean interactiveOverlays) {
		this.interactiveOverlays = interactiveOverlays;
	}

	public OverlayMode interactiveOverlayType() {
		return interactiveOverlayType == null || interactiveOverlayType == OverlayMode.OFF
			? OverlayMode.BOTH
			: interactiveOverlayType;
	}

	public void setInteractiveOverlayType(OverlayMode interactiveOverlayType) {
		this.interactiveOverlayType = interactiveOverlayType == null
				|| interactiveOverlayType == OverlayMode.OFF
			? OverlayMode.BOTH
			: interactiveOverlayType;
	}

	/** Which types the interactive overlay actually answers for right now, {@code OFF} if none. */
	public OverlayMode activeInteractiveOverlays() {
		return interactiveOverlays ? interactiveOverlayType() : OverlayMode.OFF;
	}

	/**
	 * Every type either overlay wants.
	 *
	 * <p>What the block scan collects and what the render loop bothers to walk. Neither cares
	 * which of the two settings asked for it, only that something did.</p>
	 */
	public OverlayMode shownOverlays() {
		return nearbyOverlays().or(activeInteractiveOverlays());
	}

	/** The overlay key: the interactive half only, which is the half you can tell is on. */
	public void toggleInteractiveOverlays() {
		interactiveOverlays = !interactiveOverlays;
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

	/**
	 * Whether harp notes stop for a block of their own too.
	 *
	 * <p>Harp is the instrument a note block plays over anything the game does not recognise, so
	 * {@code SongBuilder} lays air for it and saves a block a note. Building by hand is not the same
	 * job: the ground under a hand-laid machine is usually there anyway, and a harp note that walks
	 * past without asking for anything reads as a step the sequencer forgot. On, harp asks for its
	 * block like everything else -- grass by default, though anything the game leaves unrecognised
	 * sounds the same and counts.</p>
	 */
	public boolean selectHarpBlocks() {
		return selectHarpBlocks;
	}

	public void setSelectHarpBlocks(boolean selectHarpBlocks) {
		this.selectHarpBlocks = selectHarpBlocks;
	}

	/**
	 * The order the sequencer walks the notes of one chord.
	 *
	 * <p>A chord is built two notes to a block of bus, one either side. Two strips walks the whole of
	 * one side and comes back along the other, which is what a hand carrying one stack does.
	 * Alternating crosses the bus at every note, which is the order the placements are stored in.
	 * Neither moves a note: which pitch belongs to which side of which block is the same either
	 * way, and so is the card on screen.</p>
	 */
	public ChordPlaceOrder chordPlaceOrder() {
		return chordPlaceOrder == null ? ChordPlaceOrder.TWO_STRIPS : chordPlaceOrder;
	}

	public void setChordPlaceOrder(ChordPlaceOrder chordPlaceOrder) {
		this.chordPlaceOrder = chordPlaceOrder;
	}

	public boolean walksChordsInTwoStrips() {
		return chordPlaceOrder() == ChordPlaceOrder.TWO_STRIPS;
	}

	/**
	 * Whether the block under a note is a step of its own.
	 *
	 * <p>A note block's instrument is the block beneath it, so building by hand is really two
	 * placements a note and the sequencer only ever counted one. Switched on, each note waits for its
	 * instrument first: the icon lights, the hotbar picks that block up, and the note follows once
	 * something is down.</p>
	 */
	public boolean selectInstruments() {
		return selectInstruments;
	}

	public void setSelectInstruments(boolean selectInstruments) {
		this.selectInstruments = selectInstruments;
	}

	public boolean autoSelectSequenceBlock() {
		return autoSelectSequenceBlock;
	}

	/**
	 * Whether the build plays a sound once when two layers ask for it at the same instant.
	 *
	 * <p>On by default. Preview has always collapsed these, so a duplicate is something you cannot
	 * hear while composing but still pay for in blocks and in the thirty notes a tick can carry.
	 * It is a setting rather than an edit because the layers only agree for now -- change one of
	 * their instruments and they are two different sounds again, and a delete would have been
	 * unrecoverable.</p>
	 */
	public boolean dedupeIdenticalNotes() {
		return dedupeIdenticalNotes;
	}

	public void setDedupeIdenticalNotes(boolean dedupeIdenticalNotes) {
		this.dedupeIdenticalNotes = dedupeIdenticalNotes;
		cachedSequence = null;
	}

	public void setAutoSelectSequenceBlock(boolean autoSelectSequenceBlock) {
		this.autoSelectSequenceBlock = autoSelectSequenceBlock;
	}

	public String placementSequence() {
		return activeTrack().sequence();
	}

	public int placementSequencePosition() {
		return activeSongId == null
			? Math.max(0, placementCursor)
			: Math.max(0, placementCursors.getOrDefault(activeSongId, 0));
	}

	public void setPlacementSequencePosition(int placementSequencePosition) {
		int position = Math.max(0, placementSequencePosition);
		if (activeSongId == null) {
			placementCursor = position;
		} else {
			placementCursors.put(activeSongId, position);
		}
	}

	/** Drops the bookmark of a song that is no longer there, so the file cannot grow without bound. */
	public void forgetPlacementPosition(String songId) {
		if (songId != null) {
			placementCursors.remove(songId);
		}
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
		return previewInstrument;
	}

	public void setPreviewInstrument(String previewInstrument) {
		this.previewInstrument = previewInstrument == null || previewInstrument.isBlank()
			? "HARP"
			: previewInstrument;
	}

	/**
	 * The build sequence: the flat timeline of notes and repeaters this composition builds as.
	 *
	 * <p>Derived, never stored. It is a pure function of the active composition and which of its
	 * layers are included, so filling in a layer's dot changes it at once instead of leaving it
	 * stale until a separate command is run. Include nothing and there is no sequence, rather than
	 * whatever was last published lingering on.</p>
	 *
	 * <p>Cached on the project's identity, which is sound because ComposerProject is immutable:
	 * every edit produces a new instance, so a stale cache cannot happen.</p>
	 */
	public List<SequenceTrack> tracks() {
		ComposerProject project = composerProject();
		if (cachedSequenceProject != project || cachedSequence == null) {
			cachedSequenceProject = project;
			cachedSequence = project.toSequenceTracks(java.util.Set.of(), dedupeIdenticalNotes);
		}
		return cachedSequence;
	}

	public String activeSongId() {
		return activeSongId;
	}

	/**
	 * Bumped whenever a different document is opened, as opposed to the open one being edited.
	 *
	 * <p>The song id cannot answer this on its own: an import has no id, so importing twice in a row
	 * is null to null, and the placement cursor would carry from one song into a different one as
	 * though it were an edit. Counting the openings says it plainly.</p>
	 */
	public int documentGeneration() {
		return documentGeneration;
	}

	/** Opens a different song. The one being left is already on disk; nothing is carried over. */
	public void setActiveSongId(String id) {
		if (id == null || songs.song(id) == null) {
			return;
		}
		activeSongId = id;
		songOpenedAt.put(id, System.currentTimeMillis());
		documentGeneration++;
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
	 * Holds the composition being edited, in memory only.
	 *
	 * <p>Nothing reaches disk until {@link #saveComposerProject}. This used to write the song file
	 * on every edit, which made an import land on top of whichever song happened to be open --
	 * same file, raw unconverted notes, and the Minecraft-ready song that was there is gone. An
	 * editor that only writes when told to cannot do that.</p>
	 *
	 * <p>The build sequence still follows this instantly, because it is a projection of whatever
	 * is being edited rather than of whatever was last saved.</p>
	 */
	public void setComposerProject(ComposerProject project) {
		if (project == null) {
			return;
		}
		composerProject = project;
		activeSequenceName = project.name();
	}

	/** Writes the composition being edited to its own file. */
	public boolean saveComposerProject() {
		if (composerProject == null) {
			return false;
		}
		if (activeSongId == null) {
			activeSongId = songs.newId(composerProject.name());
			// However far placing an import had got follows it into the file it now has. Saving is
			// not somewhere to lose your place.
			if (placementCursor > 0) {
				placementCursors.put(activeSongId, placementCursor);
				placementCursor = 0;
			}
		}
		return songs.save(activeSongId, composerProject);
	}

	/** The active song as it currently stands on disk, which is what unsaved edits differ from. */
	public ComposerProject savedComposerProject() {
		return songs.song(activeSongId);
	}

	/**
	 * Opens a composition that has no file behind it yet.
	 *
	 * <p>What an import produces. It used to be written into the library the moment it was read,
	 * which meant deciding not to keep it still left it there -- and a song you have to go and
	 * delete is not a song you declined. Nothing reaches disk until Save, and because there is no
	 * active id, Save writes a new file rather than over whatever was open before.</p>
	 */
	public void openWorkingCopy(ComposerProject project) {
		if (project == null) {
			return;
		}
		activeSongId = null;
		documentGeneration++;
		// A document with no file behind it has no history to resume from.
		placementCursor = 0;
		composerProject = project;
		activeSequenceName = project.name();
	}

	/** Whether what is being edited has a file behind it. */
	public boolean hasSongFile() {
		return activeSongId != null && songs.song(activeSongId) != null;
	}

	/** Throws away unsaved edits, so leaving without saving really does leave nothing behind. */
	public void discardComposerEdits() {
		ComposerProject saved = songs.song(activeSongId);
		if (saved != null) {
			composerProject = saved;
			activeSequenceName = saved.name();
			return;
		}
		// Nothing on disk to go back to, so go back to nothing. Discarding an import leaves an
		// empty document rather than the import with its edits undone, which would still be the
		// thing the user just declined to keep.
		activeSongId = null;
		documentGeneration++;
		placementCursor = 0;
		composerProject = ComposerProject.empty("Untitled composition");
		activeSequenceName = composerProject.name();
	}

	public int activeTrackIndex() {
		return clampTrackIndex(activeTrackIndex, tracks().size());
	}

	public SequenceTrack activeTrack() {
		List<SequenceTrack> sequence = tracks();
		return sequence.isEmpty()
			? new SequenceTrack("Track 1", "", previewInstrument, 0)
			: sequence.get(clampTrackIndex(activeTrackIndex, sequence.size()));
	}

	/** Remembered layout for the next build. Stored by name so the enum can move. */
	public String pasteMode() {
		if (pasteMode == null) {
			return "COMPACT_CUBE";
		}
		// The one straight line used to be called Straight, and is called Lane now that there is a
		// folded version of it. Anyone who had it selected keeps it selected.
		return "STRAIGHT".equals(pasteMode) ? "LANE" : pasteMode;
	}

	public void setPasteMode(String pasteMode) {
		this.pasteMode = pasteMode;
	}

	public MidiQuantizeGrid midiQuantizeGrid() {
		return midiQuantizeGrid;
	}

	public void setMidiQuantizeGrid(MidiQuantizeGrid midiQuantizeGrid) {
		this.midiQuantizeGrid = midiQuantizeGrid == null ? MidiQuantizeGrid.AUTO : midiQuantizeGrid;
	}


	public boolean midiIgnorePercussion() {
		return midiIgnorePercussion;
	}

	public void setMidiIgnorePercussion(boolean midiIgnorePercussion) {
		this.midiIgnorePercussion = midiIgnorePercussion;
	}

	/**
	 * Whether the debug commands are registered.
	 *
	 * <p>Off unless asked for, because what they exist to do is build a shape nobody wrote: a run of
	 * chords of stated sizes, all one note, at a stated distance from the wall. That is how a fault
	 * found in a real song gets cut down to the half dozen chords that actually cause it.</p>
	 */
	public boolean seenWelcome() {
		return seenWelcome;
	}

	public void setSeenWelcome(boolean seenWelcome) {
		this.seenWelcome = seenWelcome;
	}

	public boolean debugCommandsEnabled() {
		return debugCommandsEnabled;
	}

	public void setDebugCommandsEnabled(boolean debugCommandsEnabled) {
		this.debugCommandsEnabled = debugCommandsEnabled;
	}

	/**
	 * Whether a paste comes out marked up rather than plain.
	 *
	 * <p>Remembered between sessions because it is a way of working rather than a one-off: the
	 * builds worth marking are the ones being read over several evenings, and having to turn it back
	 * on after every launch is how a build gets pasted plain by accident and read for an hour before
	 * anybody notices the colours are missing.</p>
	 */
	public boolean debugPasteEnabled() {
		return debugPasteEnabled;
	}

	/**
	 * Sets the flag and the builder's copy of it together.
	 *
	 * <p>The builder holds its own {@code static} because the tests drive it without a config file
	 * and the walk reads it per block. Setting them apart is how the two drift, so nothing outside
	 * this method writes either one.</p>
	 */
	public void setDebugPasteEnabled(boolean debugPasteEnabled) {
		this.debugPasteEnabled = debugPasteEnabled;
		com.midicraft.client.compat.SongBuilder.DEBUG_PASTE = debugPasteEnabled;
	}


	public String midiDefaultInstrument() {
		return midiDefaultInstrument;
	}

	public MidiInstrumentSource midiInstrumentSource() {
		return midiInstrumentSource;
	}

	public void setMidiInstrumentSource(MidiInstrumentSource midiInstrumentSource) {
		this.midiInstrumentSource = midiInstrumentSource == null
			? MidiInstrumentSource.FROM_FILE_THEN_NAME
			: midiInstrumentSource;
	}

	public void setMidiDefaultInstrument(String midiDefaultInstrument) {
		this.midiDefaultInstrument = midiDefaultInstrument == null || midiDefaultInstrument.isBlank()
			? "HARP"
			: midiDefaultInstrument.trim();
	}

	public int maxBuildFloors() {
		return maxBuildFloors;
	}

	public void setMaxBuildFloors(int maxBuildFloors) {
		this.maxBuildFloors = clampMaxBuildFloors(maxBuildFloors);
	}

	public double commandsPerTick() {
		return commandsPerTick;
	}

	public void setCommandsPerTick(double commandsPerTick) {
		this.commandsPerTick = clampCommandsPerTick(commandsPerTick);
	}

	/**
	 * Where the file browser last was, or the mod's own import folder the first time.
	 *
	 * <p>Somewhere of ours rather than the home directory: a new player has nowhere obvious to put
	 * a MIDI, and a folder that exists and says what it is for is an answer to that.</p>
	 */
	public String importDirectory() {
		return importDirectory == null || importDirectory.isBlank()
			? SongLibrary.importDirectory().toString()
			: importDirectory;
	}

	public void setImportDirectory(String importDirectory) {
		this.importDirectory = importDirectory;
	}

	public int buildLaneWidth() {
		return clampBuildLaneWidth(buildLaneWidth);
	}

	public void setBuildLaneWidth(int buildLaneWidth) {
		this.buildLaneWidth = clampBuildLaneWidth(buildLaneWidth);
	}

	public int buildLaneFloors() {
		return buildLaneFloors;
	}

	/**
	 * Whether an Ultra compact lane build starts on its top floor and works down.
	 *
	 * <p>Off by default, which is where every build made so far started. Remembered between
	 * pastes like the lane width is, because it is a property of the plot rather than of the
	 * song -- you start from the top when the ground under the origin is what you cannot dig.</p>
	 */
	public boolean ultraLaneStartTop() {
		return ultraLaneStartTop;
	}

	public void setUltraLaneStartTop(boolean ultraLaneStartTop) {
		this.ultraLaneStartTop = ultraLaneStartTop;
	}

	public void setBuildLaneFloors(int buildLaneFloors) {
		this.buildLaneFloors = clampBuildLaneFloors(buildLaneFloors);
	}

	/** @see #DEFAULT_PARITY_RESEED_DELAY */
	public int parityReseedDelay() {
		return parityReseedDelay;
	}

	/**
	 * Sets the setting and the builder's copy of it together, as
	 * {@link #setDebugPasteEnabled} does and for the same reason: the tests and the census
	 * probes drive the scheduler with no config file to read.
	 */
	public void setParityReseedDelay(int parityReseedDelay) {
		this.parityReseedDelay = clampParityReseedDelay(parityReseedDelay);
		com.midicraft.client.compat.SongBuilder.PARITY_MIN_DELAY_BEFORE_RESEED =
			this.parityReseedDelay;
	}

	public ComposerProject.OctaveShifting convertOctaveShifting() {
		return convertOctaveShifting;
	}

	public void setConvertOctaveShifting(ComposerProject.OctaveShifting value) {
		this.convertOctaveShifting = value == null
			? ComposerProject.OctaveShifting.NOTES_ONLY
			: value;
	}

	public boolean convertSplitTransposed() {
		return convertSplitTransposed;
	}

	public void setConvertSplitTransposed(boolean value) {
		this.convertSplitTransposed = value;
	}

	/** Whether Convert folds the speed slider into the tempo. See {@link com.midicraft.client.compat.ConvertOptionsScreen}. */
	public boolean convertBakesSpeed() {
		return convertBakesSpeed;
	}

	public void setConvertBakesSpeed(boolean value) {
		this.convertBakesSpeed = value;
	}

	/** Whether Convert collapses re-triggered notes. See {@link com.midicraft.client.compat.ConvertOptionsScreen}. */
	public boolean convertMergesRepeats() {
		return convertMergesRepeats;
	}

	public void setConvertMergesRepeats(boolean value) {
		this.convertMergesRepeats = value;
	}

	/** Whether Convert lands note starts on a grid. See {@link com.midicraft.client.compat.ConvertOptionsScreen}. */
	public boolean convertQuantizes() {
		return convertQuantizes;
	}

	public void setConvertQuantizes(boolean value) {
		this.convertQuantizes = value;
	}

	/** Whether Convert octave-shifts notes their layer cannot reach, and splits it. See {@link com.midicraft.client.compat.ConvertOptionsScreen}. */
	public boolean convertFitsRange() {
		return convertFitsRange;
	}

	public void setConvertFitsRange(boolean value) {
		this.convertFitsRange = value;
	}

	/** Whether Convert moves the tempo until the spacing lands on build ticks. See {@link com.midicraft.client.compat.ConvertOptionsScreen}. */
	public boolean convertSnapsTempo() {
		return convertSnapsTempo;
	}

	public void setConvertSnapsTempo(boolean value) {
		this.convertSnapsTempo = value;
	}

	/** Whether Convert lands the end marker on the grid. See {@link com.midicraft.client.compat.ConvertOptionsScreen}. */
	public boolean convertSnapsEnd() {
		return convertSnapsEnd;
	}

	public void setConvertSnapsEnd(boolean value) {
		this.convertSnapsEnd = value;
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

	public int composerGuiScale() {
		return composerGuiScale;
	}

	public void setComposerGuiScale(int composerGuiScale) {
		this.composerGuiScale = Math.max(MIN_COMPOSER_GUI_SCALE,
			Math.min(MAX_COMPOSER_GUI_SCALE, composerGuiScale));
	}

	public int chordThinTarget() {
		return chordThinTarget;
	}

	public void setChordThinTarget(int chordThinTarget) {
		this.chordThinTarget = clampChordThinTarget(chordThinTarget);
	}

	public int layerPanelWidth() {
		return layerPanelWidth;
	}

	public void setLayerPanelWidth(int layerPanelWidth) {
		this.layerPanelWidth = clampLayerPanelWidth(layerPanelWidth);
	}

	/**
	 * When a song was last opened, or 0 for one that has not been since the mod started counting.
	 *
	 * <p>Every way into a composition goes through {@link #setActiveSongId}, so that is where the
	 * time is written and this is the whole of the record.</p>
	 */
	public long songOpenedAt(String id) {
		Long at = songOpenedAt.get(id);
		return at == null ? 0L : at;
	}

	public boolean listSortByName() {
		return listSortByName;
	}

	public void setListSortByName(boolean value) {
		this.listSortByName = value;
	}

	public boolean layerPanelCollapsed() {
		return layerPanelCollapsed;
	}

	public void setLayerPanelCollapsed(boolean layerPanelCollapsed) {
		this.layerPanelCollapsed = layerPanelCollapsed;
	}

	/**
	 * A config nobody is using, holding what every setting is worth before anyone touches it.
	 *
	 * <p>For the settings screen's reset controls, which need to ask what a value would go back to
	 * without changing it first. {@link #defaults()} was already the one place those are written
	 * down -- the settings panel used to repeat them as literals beside each entry, and a default
	 * written twice is a default that drifts -- so a reset reads them from here rather than
	 * carrying a table of its own.</p>
	 */
	public static MidicraftConfig defaultValues() {
		return defaults();
	}

	private static MidicraftConfig defaults() {
		MidicraftConfig config = new MidicraftConfig();
		config.modEnabled = true;
		// Both overlays off until asked for. The mod's centre of gravity is the Composer now, and a
		// fresh install covering every note block in sight with labels is not the first impression
		// it wants. The type the interactive overlay will answer for is still set, so the overlay
		// key alone is enough to get a working one.
		config.nearbyOverlays = OverlayMode.OFF;
		config.interactiveOverlays = false;
		config.interactiveOverlayType = OverlayMode.NOTES_ONLY;
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
		config.dedupeIdenticalNotes = true;
		config.activeSequenceName = "Untitled sequence";
		config.activeSequenceDelayScaleQuarters = DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS;
		config.previewInstrument = "HARP";
		config.legacyTracks = List.of();
		config.composerProject = ComposerProject.empty(config.activeSequenceName);
		config.buildTrackFlagsInitialized = true;
		config.activeTrackIndex = 0;
		config.savedSequences = new ArrayList<>();
		config.midiQuantizeGrid = MidiQuantizeGrid.AUTO;
		config.midiIgnorePercussion = false;
		config.debugCommandsEnabled = false;
		config.seenWelcome = false;
		// The field, not the setter: setDebugPasteEnabled also writes SongBuilder.DEBUG_PASTE,
		// which is global. defaults() builds a throwaway config every time the settings screen
		// asks what a value would go back to, and going through the setter meant opening the
		// settings quietly switched the debug paste off underneath whoever had turned it on.
		config.debugPasteEnabled = false;
		config.midiDefaultInstrument = "HARP";
		config.midiInstrumentSource = MidiInstrumentSource.FROM_FILE_THEN_NAME;
		config.composerSpeedQuarters = DEFAULT_COMPOSER_SPEED_QUARTERS;
		config.midiVelocityCutoff = DEFAULT_MIDI_VELOCITY_CUTOFF;
		config.chordThinTarget = DEFAULT_CHORD_THIN_TARGET;
		config.composerGuiScale = DEFAULT_COMPOSER_GUI_SCALE;
		config.layerPanelWidth = DEFAULT_LAYER_PANEL_WIDTH;
		config.layerPanelCollapsed = false;
		config.listSortByName = false;
		config.songOpenedAt.clear();
		config.repeatMergeTicks = DEFAULT_REPEAT_MERGE_TICKS;
		config.conversionGapPercentile = DEFAULT_CONVERSION_GAP_PERCENTILE;
		config.convertOctaveShifting = ComposerProject.OctaveShifting.NOTES_ONLY;
		config.convertSplitTransposed = true;
		config.convertBakesSpeed = true;
		config.convertMergesRepeats = true;
		config.convertQuantizes = true;
		config.convertFitsRange = true;
		config.convertSnapsTempo = true;
		config.convertSnapsEnd = true;
		config.commandsPerTick = PasteRate.DEFAULT;
		config.buildLaneWidth = DEFAULT_BUILD_LANE_WIDTH;
		config.buildLaneFloors = DEFAULT_BUILD_LANE_FLOORS;
		config.parityReseedDelay = DEFAULT_PARITY_RESEED_DELAY;
		config.ultraLaneStartTop = false;
		config.maxBuildFloors = DEFAULT_MAX_BUILD_FLOORS;
		config.pasteMode = "COMPACT_CUBE";
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

	/**
	 * The composer speed, which counts in eighths and so needs a third decimal to say 1.125x.
	 *
	 * <p>Only where it earns one: a whole quarter reads "1.25x" as it always has, and the extra
	 * digit appears on the half-steps between them and nowhere else. A slider whose every label
	 * grew a decimal to accommodate the ones that needed it would be harder to read at every
	 * setting to be honest at half of them.</p>
	 */
	public static String speedLabel(int speedEighths) {
		int clamped = Math.max(ComposerProject.MIN_SPEED_EIGHTHS,
			Math.min(ComposerProject.MAX_SPEED_EIGHTHS, speedEighths));
		return String.format(java.util.Locale.ROOT,
			clamped % 2 == 0 ? "%.2fx" : "%.3fx", clamped / 8.0F);
	}


	private static int clampMaxBuildFloors(int floors) {
		return Math.max(MIN_MAX_BUILD_FLOORS, Math.min(MAX_MAX_BUILD_FLOORS, floors));
	}

	private static int clampBuildLaneWidth(int blocks) {
		return Math.max(MIN_BUILD_LANE_WIDTH, Math.min(MAX_BUILD_LANE_WIDTH, blocks));
	}

	private static int clampBuildLaneFloors(int floors) {
		return Math.max(MIN_BUILD_LANE_FLOORS, Math.min(MAX_BUILD_LANE_FLOORS, floors));
	}

	private static int clampParityReseedDelay(int ticks) {
		return Math.max(MIN_PARITY_RESEED_DELAY, Math.min(MAX_PARITY_RESEED_DELAY, ticks));
	}

	private static double clampCommandsPerTick(double commands) {
		return PasteRate.clamp(commands);
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

	private static int clampChordThinTarget(int target) {
		return Math.max(MIN_CHORD_THIN_TARGET, Math.min(MAX_CHORD_THIN_TARGET, target));
	}

	private static int clampLayerPanelWidth(int pixels) {
		return Math.max(MIN_LAYER_PANEL_WIDTH, Math.min(MAX_LAYER_PANEL_WIDTH, pixels));
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

	private static final class StoredConfig {
		private Boolean modEnabled;
		private OverlayMode nearbyOverlays;
		private Boolean interactiveOverlays;
		private OverlayMode interactiveOverlayType;
		// Legacy fields, read on load and never written again. They are how a config from before
		// the overlay selector, and from before the nearby/interactive split, still opens.
		private OverlayMode overlayMode;
		private OverlayMode previousOverlayMode;
		private Boolean overlaysEnabled;
		private Boolean noteBlockOverlaysEnabled;
		private Boolean nearbyPreviewsEnabled;
		private Boolean interactiveControlsEnabled;
		private Boolean previousInteractiveControls;
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
		private Boolean selectInstruments;
		private ChordPlaceOrder chordPlaceOrder;
		private Boolean selectHarpBlocks;
		private Boolean dedupeIdenticalNotes;
		private String placementSequence;
		private Integer placementSequencePosition;
		private Map<String, Integer> placementCursors;
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
		private Boolean midiIgnorePercussion;
		private Boolean debugCommandsEnabled;
		private Boolean seenWelcome;
		private Boolean debugPasteEnabled;
		private String midiDefaultInstrument;
		private MidiInstrumentSource midiInstrumentSource;
		private Integer midiVelocityCutoff;
		private Integer chordThinTarget;
		private Integer composerGuiScale;
		private Integer layerPanelWidth;
		private Boolean layerPanelCollapsed;
		private Boolean listSortByName;
		private Map<String, Long> songOpenedAt;
		private Integer composerSpeedQuarters;
		private Integer repeatMergeTicks;
		private Integer conversionGapPercentile;
		private ComposerProject.OctaveShifting convertOctaveShifting;
		private Boolean convertSplitTransposed;
		private Boolean convertBakesSpeed;
		private Boolean convertMergesRepeats;
		private Boolean convertQuantizes;
		private Boolean convertFitsRange;
		private Boolean convertSnapsTempo;
		private Boolean convertSnapsEnd;
		private Double commandsPerTick;
		private Integer buildLaneWidth;
		private Integer buildLaneFloors;
		private Integer parityReseedDelay;
		private Boolean ultraLaneStartTop;
		private String importDirectory;
		private Integer maxBuildFloors;
		private String pasteMode;

		private StoredConfig() {
		}

		private StoredConfig(MidicraftConfig config) {
			this.modEnabled = config.modEnabled;
			this.nearbyOverlays = config.nearbyOverlays;
			this.interactiveOverlays = config.interactiveOverlays;
			this.interactiveOverlayType = config.interactiveOverlayType;
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
			this.selectInstruments = config.selectInstruments;
			this.chordPlaceOrder = config.chordPlaceOrder;
			this.selectHarpBlocks = config.selectHarpBlocks;
			this.dedupeIdenticalNotes = config.dedupeIdenticalNotes;
			this.placementSequence = config.activeTrack().sequence();
			this.placementSequencePosition = config.placementCursor;
			this.placementCursors = new LinkedHashMap<>(config.placementCursors);
			this.activeSequenceName = config.activeSequenceName;
			this.activeSequenceDelayScaleQuarters = config.activeSequenceDelayScaleQuarters;
			this.previewInstrument = config.previewInstrument;
			// The sequence is derived from the composition, so there is nothing here to store.
			this.tracks = null;
			// Songs live in their own files now. Leaving these null keeps the settings file small
			// and stops one bad composition from taking every setting down with it on load.
			this.composerProject = null;
			this.activeSongId = config.activeSongId;
			this.buildTrackFlagsInitialized = config.buildTrackFlagsInitialized;
			this.activeTrackIndex = config.activeTrackIndex;
			this.savedSequences = null;
			this.midiQuantizeGrid = config.midiQuantizeGrid;
			this.midiIgnorePercussion = config.midiIgnorePercussion;
			this.debugCommandsEnabled = config.debugCommandsEnabled;
			this.seenWelcome = config.seenWelcome;
			this.debugPasteEnabled = config.debugPasteEnabled;
			this.midiDefaultInstrument = config.midiDefaultInstrument;
			this.midiInstrumentSource = config.midiInstrumentSource;
			this.midiVelocityCutoff = config.midiVelocityCutoff;
			this.chordThinTarget = config.chordThinTarget;
			this.composerGuiScale = config.composerGuiScale;
			this.layerPanelWidth = config.layerPanelWidth;
			this.layerPanelCollapsed = config.layerPanelCollapsed;
			this.listSortByName = config.listSortByName;
			// Only the songs that still exist. A library the player has been pruning would otherwise
			// leave a line in the settings file for every composition ever opened.
			this.songOpenedAt = new LinkedHashMap<>();
			config.songOpenedAt.forEach((id, at) -> {
				if (songs.song(id) != null) {
					this.songOpenedAt.put(id, at);
				}
			});
			this.composerSpeedQuarters = config.composerSpeedQuarters;
			this.repeatMergeTicks = config.repeatMergeTicks;
			this.conversionGapPercentile = config.conversionGapPercentile;
			this.convertOctaveShifting = config.convertOctaveShifting;
			this.convertSplitTransposed = config.convertSplitTransposed;
			this.convertBakesSpeed = config.convertBakesSpeed;
			this.convertMergesRepeats = config.convertMergesRepeats;
			this.convertQuantizes = config.convertQuantizes;
			this.convertFitsRange = config.convertFitsRange;
			this.convertSnapsTempo = config.convertSnapsTempo;
			this.convertSnapsEnd = config.convertSnapsEnd;
			this.commandsPerTick = config.commandsPerTick;
			this.buildLaneWidth = config.buildLaneWidth;
			this.buildLaneFloors = config.buildLaneFloors;
			this.parityReseedDelay = config.parityReseedDelay;
			this.ultraLaneStartTop = config.ultraLaneStartTop;
			this.importDirectory = config.importDirectory;
			this.maxBuildFloors = config.maxBuildFloors;
			this.pasteMode = config.pasteMode;
		}
	}
}
