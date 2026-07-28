package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.NoteSequence.Step;
import com.fastnoteblocks.NoteSequence.StepType;
import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.SavedSequence;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

final class ActiveSequenceEntry extends AbstractConfigListEntry<String> {
	private static final int EDITOR_HEIGHT = 86;
	private static final int PALETTE_CELL = 22;
	private static final int PALETTE_COLUMNS = 7;
	private static final int PALETTE_HEIGHT = 70;
	private static final int TIMELINE_LEFT_WIDTH = 210;
	private static final int TIMELINE_ROW_HEIGHT = 22;
	private static final int TIMELINE_TICK_WIDTH = 22;
	private static final int TIMELINE_NOTE_WIDTH = 34;
	private static final int MAX_SIMULTANEOUS_NOTES = 30;
	private static final int CHORD_WARNING_THRESHOLD = 24;
	private static final int COMPACT_LANE_SPACING = 4;
	private static final String PITCH_GUIDE = "0:F♯  1:G  2:G♯  3:A  4:A♯  5:B  6:C  7:C♯  8:D  9:D♯  10:E  11:F  "
		+ "12:F♯  13:G  14:G♯  15:A  16:A♯  17:B  18:C  19:C♯  20:D  21:D♯  22:E  23:F  24:F♯";

	private final FastNoteblocksConfig config;
	private final String initialName;
	private final int initialDelayScaleQuarters;
	private final List<SequenceTrack> initialTracks;
	private final int initialActiveTrack;
	private final EditBox nameBox;
	private final DelayScaleSlider delayScaleSlider;
	private final Button expandButton;
	private final Button playButton;
	private final Button viewModeButton;
	private final Button importMidiButton;
	private final Button pasteLineButton;
	private final Button addTrackButton;
	private final List<TrackRow> tracks = new ArrayList<>();
	private boolean expanded = true;
	private boolean timelineMode;
	private boolean playing;
	private long playbackStartedAt;
	private int activeTrackIndex;
	private int timelineScroll;
	private int timelineViewportX;
	private int timelineViewportY;
	private int timelineViewportWidth;
	private int timelineViewportHeight;
	private int timelineContentWidth;

	ActiveSequenceEntry(FastNoteblocksConfig config) {
		super(Component.literal("Active sequence:"), false);
		this.config = config;
		this.initialName = config.activeSequenceName();
		this.initialDelayScaleQuarters = config.activeSequenceDelayScaleQuarters();
		this.initialTracks = config.tracks();
		this.initialActiveTrack = config.activeTrackIndex();
		this.activeTrackIndex = initialActiveTrack;
		this.nameBox = new EditBox(Minecraft.getInstance().font, 0, 0, 150, 20, Component.literal("Active sequence name"));
		nameBox.setMaxLength(80);
		nameBox.setValue(initialName);
		nameBox.setResponder(value -> syncConfig());
		this.delayScaleSlider = new DelayScaleSlider(0, 0, 96, 20, initialDelayScaleQuarters, value -> syncConfig());
		this.expandButton = Button.builder(expandLabel(), button -> {
			expanded = !expanded;
			button.setMessage(expandLabel());
		}).bounds(0, 0, 20, 20)
			.tooltip(Tooltip.create(Component.literal("Expand or collapse the active sequence")))
			.build();
		this.playButton = Button.builder(playLabel(), button -> togglePlayback())
			.bounds(0, 0, 52, 20)
			.tooltip(Tooltip.create(Component.literal("Preview every track together")))
			.build();
		this.viewModeButton = Button.builder(Component.literal("Composer"), button -> {
			syncAndSave();
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.gui.setScreen(new ComposerScreen(minecraft.gui.screen(), config, this::reloadFromComposer));
		}).bounds(0, 0, 72, 20)
			.tooltip(Tooltip.create(Component.literal("Open the full piano-roll composition editor")))
			.build();
		this.importMidiButton = Button.builder(Component.literal("Import MIDI"), button -> importMidi())
			.bounds(0, 0, 78, 20)
			.tooltip(Tooltip.create(Component.literal("Import a .mid or .midi file into the active composition")))
			.build();
		this.pasteLineButton = Button.builder(Component.literal("Place tracks"), button -> pastePlayableLine())
			.bounds(0, 0, 90, 20)
			.tooltip(Tooltip.create(Component.literal("Use commands to place enabled build tracks in the world")))
			.build();
		this.addTrackButton = Button.builder(Component.literal("+ Add track"), button -> addTrack())
			.bounds(0, 0, 120, 20)
			.tooltip(Tooltip.create(Component.literal("Add another track (maximum 10)")))
			.build();
		setTracks(config.tracks(), config.activeTrackIndex());
	}

	private void reloadFromComposer() {
		stopPlayback();
		setTracks(config.tracks(), config.activeTrackIndex());
	}

	String sequenceName() {
		return nameBox.getValue().isBlank() ? "Untitled sequence" : nameBox.getValue().trim();
	}

	SavedSequence savedSequence() {
		return new SavedSequence(sequenceName(), trackValues(), activeTrackIndex, delayScaleQuarters(),
			config.composerProject());
	}

	void setSequence(SavedSequence saved) {
		stopPlayback();
		nameBox.setValue(saved.name());
		delayScaleSlider.setScale(saved.delayScaleQuarters());
		List<SequenceTrack> savedTracks = saved.composerProject() == null
			? saved.tracks()
			: saved.composerProject().toSequenceTracks(saved.tracks(), saved.delayScaleQuarters());
		setTracks(savedTracks, saved.activeTrackIndex());
		syncConfig();
		if (saved.composerProject() != null) {
			config.setComposerProject(saved.composerProject());
			FastNoteblocksConfig.save();
		}
	}

	private void importMidi() {
		if (hasSequenceContent()) {
			confirmImportMidi();
			return;
		}
		chooseAndImportMidi();
	}

	private boolean hasSequenceContent() {
		return tracks.stream().anyMatch(track -> !track.sequence().isBlank());
	}

	private void confirmImportMidi() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen returnScreen = minecraft.gui.screen();
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			minecraft.gui.setScreen(returnScreen);
			if (confirmed) {
				chooseAndImportMidi();
			}
		}, Component.literal("Replace active composition?"),
			Component.literal("Importing MIDI will overwrite the active composition. Saved sequences are unchanged."),
			Component.literal("Import"), CommonComponents.GUI_CANCEL));
	}

	private void chooseAndImportMidi() {
		String path;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(2);
			filters.put(stack.UTF8("*.mid"));
			filters.put(stack.UTF8("*.midi"));
			filters.flip();
			path = TinyFileDialogs.tinyfd_openFileDialog("Import MIDI", "", filters, "MIDI files", false);
		}
		if (path == null || path.isBlank()) {
			return;
		}
		try {
			MidiImporter.Result result = MidiImporter.importFile(path, config);
			setSequence(result.sequence());
			syncAndSave();
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player != null) {
				minecraft.gui.hud.setOverlayMessage(Component.literal(result.report()), true);
			}
		} catch (Exception exception) {
			Minecraft minecraft = Minecraft.getInstance();
			Screen returnScreen = minecraft.gui.screen();
			minecraft.gui.setScreen(new ConfirmScreen(confirmed -> minecraft.gui.setScreen(returnScreen),
				Component.literal("MIDI import failed"),
				Component.literal(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()),
				CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
		}
	}

	private void pastePlayableLine() {
		if (CommandPasteSender.isRunning()) {
			CommandPasteSender.cancel(true);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		try {
			ChordStats stats = chordStats(enabledEventNotes());
			if (stats.peak() > MAX_SIMULTANEOUS_NOTES) {
				throw new IllegalArgumentException(overloadMessage(stats));
			}
		} catch (IllegalArgumentException exception) {
			minecraft.gui.hud.setOverlayMessage(Component.literal(exception.getMessage()).withStyle(ChatFormatting.RED), true);
			return;
		}
		Screen returnScreen = minecraft.gui.screen();
		minecraft.gui.setScreen(new PasteModeScreen(returnScreen, mode -> {
			minecraft.gui.setScreen(returnScreen);
			confirmPasteLayout(minecraft, mode, returnScreen);
		}));
	}

	private void confirmPasteLayout(Minecraft minecraft, PasteMode mode, Screen returnScreen) {
		PastePlan plan;
		try {
			plan = createPastePlan(minecraft, mode);
		} catch (IllegalArgumentException exception) {
			minecraft.gui.hud.setOverlayMessage(Component.literal(exception.getMessage()).withStyle(ChatFormatting.RED), true);
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				CommandPasteSender.start(plan.commands());
			}
			minecraft.gui.setScreen(returnScreen);
		}, Component.literal("Place active sequence tracks?"),
			Component.literal(plan.mode().label() + ": " + plan.width() + " x " + plan.depth() + " x " + plan.height()
				+ ", " + plan.commands().size() + " commands. Requires /setblock permission and overwrites blocks."),
			Component.literal("Place"), CommonComponents.GUI_CANCEL));
	}

	private PastePlan createPastePlan(Minecraft minecraft, PasteMode mode) {
		List<EventNote> notes = enabledEventNotes();
		if (notes.isEmpty()) {
			throw new IllegalArgumentException("No enabled non-muted notes to paste");
		}
		ChordStats stats = chordStats(notes);
		if (stats.peak() > MAX_SIMULTANEOUS_NOTES) {
			throw new IllegalArgumentException(overloadMessage(stats));
		}
		Direction forward = minecraft.player.getDirection();
		BlockPos origin = pasteOrigin(minecraft, forward);
		return switch (mode) {
			case COMPACT -> createCompactPastePlan(origin, forward, notes);
			case STRAIGHT -> createStraightPastePlan(origin, forward, notes);
		};
	}

	private PastePlan createStraightPastePlan(BlockPos origin, Direction forward, List<EventNote> notes) {
		PlacementPlan placements = new PlacementPlan();
		int cursor = 0;
		int currentTime = 0;
		for (int index = 0; index < notes.size();) {
			int time = notes.get(index).time();
			int delay = time - currentTime;
			List<EventNote> chord = new ArrayList<>();
			while (index < notes.size() && notes.get(index).time() == time) {
				chord.add(notes.get(index++));
			}
			DelayTrigger trigger = addDelayBeforeEvent(placements, origin, forward, cursor, delay);
			cursor = addEventModule(placements, origin, forward, trigger.cursor(), trigger.triggerDelay(), chord);
			currentTime = time;
		}
		return placements.finish(PasteMode.STRAIGHT);
	}

	private List<EventNote> enabledEventNotes() {
		List<EventNote> notes = new ArrayList<>();
		for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
			TrackRow track = tracks.get(trackIndex);
			if (!track.buildEnabled) {
				continue;
			}
			String instrumentBlock = instrumentBlockId(track.instrument);
			if (instrumentBlock == null) {
				continue;
			}
			int time = 0;
			int order = 0;
			for (Step step : NoteSequence.parse(track.sequence(), delayScaleQuarters())) {
				if (step.type() == StepType.NOTE) {
					notes.add(new EventNote(time, trackIndex + 1, order++, step.value(), instrumentBlock));
				} else {
					time += step.value();
				}
			}
		}
		notes.sort(Comparator.comparingInt(EventNote::time)
			.thenComparingInt(EventNote::trackNumber)
			.thenComparingInt(EventNote::order));
		return List.copyOf(notes);
	}

	private static ChordStats chordStats(List<EventNote> notes) {
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		for (EventNote note : notes) {
			counts.merge(note.time(), 1, Integer::sum);
		}
		int peak = 0;
		int peakTime = 0;
		int overloadedTimes = 0;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > peak) {
				peak = entry.getValue();
				peakTime = entry.getKey();
			}
			if (entry.getValue() > MAX_SIMULTANEOUS_NOTES) {
				overloadedTimes++;
			}
		}
		return new ChordStats(peak, peakTime, overloadedTimes);
	}

	private ChordStats currentChordStats() {
		if (!sequencesValid()) {
			return new ChordStats(0, 0, 0);
		}
		return chordStats(enabledEventNotes());
	}

	private static String overloadMessage(ChordStats stats) {
		return stats.peak() + " simultaneous notes at time " + stats.peakTime()
			+ " exceeds the build limit of " + MAX_SIMULTANEOUS_NOTES;
	}

	private static BlockPos pasteOrigin(Minecraft minecraft, Direction forward) {
		return minecraft.player.blockPosition().relative(forward).immutable();
	}

	private PastePlan createCompactPastePlan(BlockPos origin, Direction forward, List<EventNote> notes) {
		List<EventGroup> events = eventGroups(notes);
		CompactLayout layout = chooseCompactLayout(events);
		PlacementPlan placements = new PlacementPlan();
		BlockPos cursor = origin;
		Direction travel = forward;
		Direction laneStep = forward.getClockWise();
		int currentTime = 0;
		for (int index = 0; index < events.size(); index++) {
			EventGroup event = events.get(index);
			int delay = event.time() - currentTime;
			SpatialDelayTrigger trigger = addSpatialDelayBeforeEvent(placements, cursor, travel, delay);
			cursor = addSpatialEventModule(placements, trigger.cursor(), travel, trigger.triggerDelay(), event.notes());
			currentTime = event.time();
			if (layout.breakAfter().contains(index + 1)) {
				cursor = addCompactTurn(placements, cursor, travel, laneStep, COMPACT_LANE_SPACING);
				travel = travel.getOpposite();
			}
		}
		return placements.finish(PasteMode.COMPACT);
	}

	private static List<EventGroup> eventGroups(List<EventNote> notes) {
		List<EventGroup> result = new ArrayList<>();
		int currentTime = 0;
		for (int index = 0; index < notes.size();) {
			int time = notes.get(index).time();
			List<EventNote> chord = new ArrayList<>();
			while (index < notes.size() && notes.get(index).time() == time) {
				chord.add(notes.get(index++));
			}
			int delay = time - currentTime;
			int delayRepeaters = Math.max(0, (delay - 1) / 4);
			int eventLength = chord.size() <= 3 ? 2 : 1 + (chord.size() + 1) / 2;
			int busLength = chord.size() <= 3 ? 0 : (chord.size() + 1) / 2;
			int maxSafeTurnDistance = chord.size() <= 3 ? 13 : Math.max(0, 13 - busLength);
			result.add(new EventGroup(time, List.copyOf(chord), delayRepeaters + eventLength,
				maxSafeTurnDistance));
			currentTime = time;
		}
		return List.copyOf(result);
	}

	private static CompactLayout chooseCompactLayout(List<EventGroup> events) {
		int totalLength = totalEventLength(events);
		Set<Integer> candidates = compactTargetCandidates(events);
		CompactLayout best = null;
		for (int targetLength : candidates) {
			CompactLayout candidate = compactLayoutForTarget(events, targetLength);
			if (best == null
					|| candidate.squareSize() < best.squareSize()
					|| candidate.squareSize() == best.squareSize() && candidate.area() < best.area()) {
				best = candidate;
			}
		}
		return best == null ? new CompactLayout(Set.of(), totalLength, 3) : best;
	}

	private static int totalEventLength(List<EventGroup> events) {
		return events.stream().mapToInt(EventGroup::length).sum();
	}

	private static Set<Integer> compactTargetCandidates(List<EventGroup> events) {
		int totalLength = totalEventLength(events);
		int largestEvent = events.stream().mapToInt(EventGroup::length).max().orElse(1);
		Set<Integer> candidates = new HashSet<>();
		candidates.add(totalLength);
		candidates.add(largestEvent);
		for (int rows = 1; rows <= events.size(); rows++) {
			candidates.add(Math.max(largestEvent, (totalLength + rows - 1) / rows));
		}
		return candidates;
	}

	private static CompactLayout compactLayoutForTarget(List<EventGroup> events, int targetLength) {
		Set<Integer> breaks = new HashSet<>();
		int rowLength = 0;
		int rows = 1;
		int cursor = 0;
		int direction = 1;
		int minimum = 0;
		int maximum = 0;
		for (int index = 0; index < events.size(); index++) {
			int eventLength = events.get(index).length();
			boolean safeTurn = index > 0
				&& events.get(index - 1).maxSafeTurnDistance() >= COMPACT_LANE_SPACING;
			if (rowLength > 0 && rowLength + eventLength > targetLength && safeTurn) {
				breaks.add(index);
				int outerTurn = cursor + direction;
				minimum = Math.min(minimum, outerTurn);
				maximum = Math.max(maximum, outerTurn);
				direction = -direction;
				rowLength = 0;
				rows++;
			}
			int end = cursor + direction * eventLength;
			minimum = Math.min(minimum, Math.min(cursor, end));
			maximum = Math.max(maximum, Math.max(cursor, end));
			cursor = end;
			rowLength += eventLength;
		}
		int width = Math.max(1, maximum - minimum + 1);
		int depth = (rows - 1) * COMPACT_LANE_SPACING + 3;
		return new CompactLayout(Set.copyOf(breaks), width, depth);
	}

	private static BlockPos addCompactTurn(PlacementPlan placements, BlockPos cursor, Direction travel,
			Direction laneStep, int laneDistance) {
		if (laneDistance < 1 || laneDistance > 13) {
			throw new IllegalArgumentException("Compact turn distance " + laneDistance
				+ " exceeds the safe redstone range");
		}
		BlockPos outer = cursor.relative(travel);
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(), "minecraft:redstone_wire");
		set(placements, outer, "minecraft:stone");
		set(placements, outer.above(), "minecraft:redstone_wire");
		for (int offset = 1; offset <= laneDistance; offset++) {
			BlockPos turn = outer.relative(laneStep, offset);
			set(placements, turn, "minecraft:stone");
			set(placements, turn.above(), "minecraft:redstone_wire");
		}
		return outer.relative(laneStep, laneDistance).relative(travel.getOpposite());
	}

	private static SpatialDelayTrigger addSpatialDelayBeforeEvent(PlacementPlan placements, BlockPos cursor,
			Direction travel, int delay) {
		int remaining = delay;
		while (remaining > 4) {
			set(placements, cursor, "minecraft:stone");
			set(placements, cursor.above(),
				"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=4]");
			cursor = cursor.relative(travel);
			remaining -= 4;
		}
		return new SpatialDelayTrigger(cursor, Math.max(1, remaining));
	}

	private static BlockPos addSpatialEventModule(PlacementPlan placements, BlockPos cursor, Direction travel,
			int triggerDelay, List<EventNote> chord) {
		set(placements, cursor, "minecraft:stone");
		set(placements, cursor.above(),
			"minecraft:repeater[facing=" + repeaterFacing(travel) + ",delay=" + triggerDelay + "]");
		BlockPos anchor = cursor.relative(travel).above();
		Direction side = travel.getClockWise();
		if (chord.size() <= 3) {
			placeNote(placements, anchor, chord.get(0));
			if (chord.size() >= 2) {
				placeNote(placements, anchor.relative(side), chord.get(1));
			}
			if (chord.size() >= 3) {
				placeNote(placements, anchor.relative(side.getOpposite()), chord.get(2));
			}
			return cursor.relative(travel, 2);
		}
		int busLength = (chord.size() + 1) / 2;
		for (int bus = 0; bus < busLength; bus++) {
			BlockPos busPos = anchor.relative(travel, bus);
			set(placements, busPos, "minecraft:stone");
			set(placements, busPos.above(), "minecraft:redstone_wire");
		}
		for (int noteIndex = 0; noteIndex < chord.size(); noteIndex++) {
			int bus = noteIndex / 2;
			Direction noteSide = noteIndex % 2 == 0 ? side : side.getOpposite();
			placeNote(placements, anchor.relative(travel, bus).relative(noteSide), chord.get(noteIndex));
		}
		return cursor.relative(travel, 1 + busLength);
	}

	private static DelayTrigger addDelayBeforeEvent(PlacementPlan placements, BlockPos origin, Direction forward,
			int cursor, int delay) {
		int remaining = delay;
		while (remaining > 4) {
			BlockPos pos = at(origin, forward, cursor, 0, 0);
			set(placements, pos, "minecraft:stone");
			set(placements, pos.above(), "minecraft:repeater[facing=" + repeaterFacing(forward) + ",delay=4]");
			cursor++;
			remaining -= 4;
		}
		return new DelayTrigger(cursor, Math.max(1, remaining));
	}

	private static int addEventModule(PlacementPlan placements, BlockPos origin, Direction forward,
			int cursor, int triggerDelay, List<EventNote> chord) {
		Direction right = forward.getClockWise();
		BlockPos triggerPos = at(origin, forward, cursor, 0, 0);
		set(placements, triggerPos, "minecraft:stone");
		set(placements, triggerPos.above(), "minecraft:repeater[facing=" + repeaterFacing(forward) + ",delay=" + triggerDelay + "]");
		BlockPos anchor = at(origin, forward, cursor + 1, 1, 0);
		if (chord.size() <= 3) {
			placeNote(placements, anchor, chord.get(0));
			if (chord.size() >= 2) {
				placeNote(placements, anchor.relative(right), chord.get(1));
			}
			if (chord.size() >= 3) {
				placeNote(placements, anchor.relative(right.getOpposite()), chord.get(2));
			}
			return cursor + 2;
		}
		int busLength = (chord.size() + 1) / 2;
		for (int bus = 0; bus < busLength; bus++) {
			BlockPos busPos = anchor.relative(forward, bus);
			set(placements, busPos, "minecraft:stone");
			set(placements, busPos.above(), "minecraft:redstone_wire");
		}
		for (int noteIndex = 0; noteIndex < chord.size(); noteIndex++) {
			EventNote note = chord.get(noteIndex);
			int bus = noteIndex / 2;
			Direction side = noteIndex % 2 == 0 ? right : right.getOpposite();
			placeNote(placements, anchor.relative(forward, bus).relative(side), note);
		}
		return cursor + 1 + busLength;
	}

	private static void placeNote(PlacementPlan placements, BlockPos notePos, EventNote note) {
		set(placements, notePos.below(), note.instrumentBlock());
		set(placements, notePos, "minecraft:note_block[note=" + note.pitch() + "]");
		set(placements, notePos.above(), "minecraft:air");
	}

	private static BlockPos at(BlockPos origin, Direction forward, int forwardOffset, int upOffset, int rightOffset) {
		return origin.relative(forward, forwardOffset)
			.relative(forward.getClockWise(), rightOffset)
			.above(upOffset);
	}

	private static void set(PlacementPlan placements, BlockPos pos, String block) {
		placements.set(pos, block);
	}

	private static String directionName(Direction direction) {
		return switch (direction) {
			case NORTH -> "north";
			case SOUTH -> "south";
			case EAST -> "east";
			case WEST -> "west";
			default -> "north";
		};
	}

	private static String repeaterFacing(Direction lineDirection) {
		return directionName(lineDirection.getOpposite());
	}

	private static String instrumentBlockId(String instrument) {
		PreviewInstrument preview = PreviewInstrument.byId(instrument);
		if ("MUTE".equals(preview.id())) {
			return null;
		}
		return BuiltInRegistries.ITEM.getKey(preview.icon()).toString();
	}

	private void setTracks(List<SequenceTrack> values, int selectedTrack) {
		tracks.clear();
		for (SequenceTrack track : values) {
			if (tracks.size() < FastNoteblocksConfig.MAX_TRACKS) {
				tracks.add(new TrackRow(track));
			}
		}
		if (tracks.isEmpty()) {
			tracks.add(new TrackRow(new SequenceTrack("Track 1", "", "HARP", 0)));
		}
		activeTrackIndex = Math.max(0, Math.min(tracks.size() - 1, selectedTrack));
		updateTrackControls();
	}

	private void addTrack() {
		if (tracks.size() >= FastNoteblocksConfig.MAX_TRACKS) {
			return;
		}
		tracks.add(new TrackRow(new SequenceTrack("Track " + (tracks.size() + 1), "", "HARP", 0), false));
		activeTrackIndex = tracks.size() - 1;
		updateTrackControls();
		syncAndSave();
	}

	private void deleteTrack(TrackRow row) {
		if (tracks.size() <= 1) {
			return;
		}
		int removed = tracks.indexOf(row);
		tracks.remove(row);
		if (activeTrackIndex > removed) {
			activeTrackIndex--;
		} else if (activeTrackIndex >= tracks.size()) {
			activeTrackIndex = tracks.size() - 1;
		}
		updateTrackControls();
		syncAndSave();
	}

	private void confirmDeleteTrack(TrackRow row) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen returnScreen = minecraft.gui.screen();
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				deleteTrack(row);
			}
			minecraft.gui.setScreen(returnScreen);
		}, Component.literal("Delete track?"),
			Component.literal("Delete \"" + row.trackName.getValue() + "\"? This cannot be undone."),
			CommonComponents.GUI_REMOVE, CommonComponents.GUI_CANCEL));
	}

	private void selectTrack(TrackRow row) {
		activeTrackIndex = Math.max(0, tracks.indexOf(row));
		updateTrackControls();
		syncAndSave();
	}

	private void toggleBuildTrack(TrackRow row) {
		row.buildEnabled = !row.buildEnabled;
		updateTrackControls();
		syncAndSave();
	}

	private void updateTrackControls() {
		for (int i = 0; i < tracks.size(); i++) {
			tracks.get(i).updateControls(tracks.size() > 1);
		}
		addTrackButton.active = tracks.size() < FastNoteblocksConfig.MAX_TRACKS;
	}

	private List<SequenceTrack> trackValues() {
		return tracks.stream().map(TrackRow::value).toList();
	}

	private void syncConfig() {
		config.setActiveSequenceName(sequenceName());
		config.setActiveSequenceDelayScaleQuarters(delayScaleQuarters());
		config.setTracks(trackValues());
		config.setActiveTrackIndex(activeTrackIndex);
	}

	private int delayScaleQuarters() {
		return delayScaleSlider.scaleQuarters();
	}

	private void syncAndSave() {
		syncConfig();
		FastNoteblocksConfig.save();
	}

	private Component expandLabel() {
		return Component.literal(expanded ? "▾" : "▸");
	}

	private Component playLabel() {
		return Component.literal(playing ? "Stop" : "Play");
	}

	private Component viewModeLabel() {
		return Component.literal(timelineMode ? "Text" : "Timeline");
	}

	private void togglePlayback() {
		if (playing) {
			stopPlayback();
			return;
		}
		long start = Util.getMillis();
		boolean any = false;
		for (TrackRow track : tracks) {
			any |= track.startPlayback(start);
		}
		if (any) {
			playing = true;
			playbackStartedAt = start;
			playButton.setMessage(playLabel());
		}
	}

	private void stopPlayback() {
		playing = false;
		playbackStartedAt = 0L;
		for (TrackRow track : tracks) {
			track.stopPlayback();
		}
		playButton.setMessage(playLabel());
	}

	private void updatePlayback() {
		if (!playing) {
			return;
		}
		long now = Util.getMillis();
		boolean anyPlaying = false;
		for (TrackRow track : tracks) {
			track.updatePlayback(now);
			anyPlaying |= track.isPlaying();
		}
		if (!anyPlaying) {
			stopPlayback();
		}
	}

	private boolean sequencesValid() {
		boolean any = false;
		for (TrackRow track : tracks) {
			if (FastNoteblocksConfig.validatePlacementSequence(track.sequence()).isPresent()) {
				return false;
			}
			any |= !track.sequence().isBlank();
		}
		return any;
	}

	@Override
	public int getItemHeight() {
		if (!expanded) {
			return 24;
		}
		if (timelineMode) {
			return 72 + (tracks.size() + 1) * TIMELINE_ROW_HEIGHT + 18;
		}
		int height = 48 + 24;
		for (TrackRow track : tracks) {
			height += track.height() + 4;
		}
		return height;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
		updatePlayback();
		ChordStats chordStats = currentChordStats();
		playButton.active = sequencesValid();
		pasteLineButton.active = (sequencesValid() && chordStats.peak() <= MAX_SIMULTANEOUS_NOTES)
			|| CommandPasteSender.isRunning();
		pasteLineButton.setMessage(Component.literal(CommandPasteSender.isRunning() ? "Cancel place" : "Place tracks"));
		pasteLineButton.setWidth(CommandPasteSender.isRunning() ? 92 : 90);
		pasteLineButton.setTooltip(Tooltip.create(Component.literal(
			chordStats.peak() > MAX_SIMULTANEOUS_NOTES
				? overloadMessage(chordStats)
				: "Place enabled build tracks. Peak chord: " + chordStats.peak() + "/" + MAX_SIMULTANEOUS_NOTES
		)));
		expandButton.setX(x);
		expandButton.setY(y);
		expandButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int labelX = x + 26;
		graphics.text(Minecraft.getInstance().font, getFieldName(), labelX, y + 6, 0xFFFFFFFF);
		int nameX = labelX + Minecraft.getInstance().font.width(getFieldName()) + 6;
		playButton.setX(x + entryWidth - playButton.getWidth());
		playButton.setY(y);
		playButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		viewModeButton.setX(playButton.getX() - viewModeButton.getWidth() - 4);
		viewModeButton.setY(y);
		viewModeButton.setMessage(Component.literal("Composer"));
		viewModeButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		pasteLineButton.setX(viewModeButton.getX() - pasteLineButton.getWidth() - 4);
		pasteLineButton.setY(y);
		pasteLineButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		importMidiButton.setX(pasteLineButton.getX() - importMidiButton.getWidth() - 4);
		importMidiButton.setY(y);
		importMidiButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		delayScaleSlider.setX(importMidiButton.getX() - delayScaleSlider.getWidth() - 4);
		delayScaleSlider.setY(y);
		delayScaleSlider.extractRenderState(graphics, mouseX, mouseY, partialTick);
		nameBox.setX(nameX);
		nameBox.setY(y);
		nameBox.setWidth(Math.max(60, delayScaleSlider.getX() - nameX - 4));
		nameBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (!expanded) {
			return;
		}

		if (timelineMode) {
			extractTimeline(graphics, x, y + 26, entryWidth, mouseX, mouseY);
			extractBuildLimitStatus(graphics, x, y + entryHeight - 11, chordStats);
			return;
		}

		extractPitchGuide(graphics, x, y + 25, entryWidth);
		int trackY = y + 36;
		for (TrackRow track : tracks) {
			track.extract(graphics, x, trackY, entryWidth, mouseX, mouseY, partialTick);
			trackY += track.height() + 4;
		}
		addTrackButton.setX(x + (entryWidth - addTrackButton.getWidth()) / 2);
		addTrackButton.setY(trackY);
		addTrackButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
		extractBuildLimitStatus(graphics, x, y + entryHeight - 11, chordStats);
	}

	private static void extractBuildLimitStatus(GuiGraphicsExtractor graphics, int x, int y, ChordStats stats) {
		int color = stats.peak() > MAX_SIMULTANEOUS_NOTES
			? 0xFFFF5555
			: stats.peak() >= CHORD_WARNING_THRESHOLD ? 0xFFFFAA00 : 0xFF999999;
		String text = "Build peak: " + stats.peak() + "/" + MAX_SIMULTANEOUS_NOTES;
		if (stats.overloadedTimes() > 0) {
			text += " (" + stats.overloadedTimes() + " overloaded time"
				+ (stats.overloadedTimes() == 1 ? "" : "s") + ")";
		}
		graphics.text(Minecraft.getInstance().font, Component.literal(text), x, y, color, false);
	}

	private static void extractPitchGuide(GuiGraphicsExtractor graphics, int x, int y, int width) {
		int textWidth = Minecraft.getInstance().font.width(PITCH_GUIDE);
		float scale = Math.min(1.0F, width / (float)Math.max(1, textWidth));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(Minecraft.getInstance().font, PITCH_GUIDE, 0, 0, 0xFFBBBBBB, false);
		graphics.pose().popMatrix();
	}

	private void extractTimeline(GuiGraphicsExtractor graphics, int x, int y, int width, int mouseX, int mouseY) {
		Minecraft minecraft = Minecraft.getInstance();
		TimelineData data = timelineData();
		int viewportX = x + TIMELINE_LEFT_WIDTH;
		int viewportY = y + 20;
		int viewportWidth = Math.max(40, width - TIMELINE_LEFT_WIDTH);
		int viewportHeight = (tracks.size() + 1) * TIMELINE_ROW_HEIGHT;
		timelineViewportX = viewportX;
		timelineViewportY = viewportY;
		timelineViewportWidth = viewportWidth;
		timelineViewportHeight = viewportHeight;
		timelineContentWidth = Math.max(viewportWidth, (data.maxTime() + 2) * TIMELINE_TICK_WIDTH + 80);
		clampTimelineScroll();

		graphics.text(minecraft.font, Component.literal("Timeline: wheel-scroll horizontally; orange line = current build time"),
			x, y + 3, 0xFFBBBBBB, false);
		graphics.fill(x, viewportY - 1, x + width, viewportY + viewportHeight + 1, 0x66000000);
		graphics.fill(viewportX, viewportY - 1, viewportX + viewportWidth, viewportY + viewportHeight + 1, 0xAA101010);

		drawTimelineGrid(graphics, viewportX, viewportY, viewportWidth, viewportHeight, data.maxTime());
		drawTimelinePlayhead(graphics, viewportX, viewportY, viewportWidth, viewportHeight, data);

		int rowY = viewportY;
		for (int index = 0; index < data.tracks().size(); index++) {
			TimelineTrack track = data.tracks().get(index);
			int labelColor = track.buildEnabled() ? 0xFFFFFFFF : 0xFF777777;
			String label = "T" + (index + 1) + (track.buildEnabled() ? " ✓ " : " · ") + track.name();
			graphics.text(minecraft.font, Component.literal(label), x, rowY + 7, labelColor, false);
			drawTimelineNotes(graphics, track.notes(), viewportX, rowY, viewportWidth, mouseX, mouseY, false);
			if (track.error()) {
				graphics.text(minecraft.font, Component.literal("invalid sequence").withStyle(ChatFormatting.RED),
					viewportX + 4, rowY + 7, 0xFFFF5555, false);
			}
			rowY += TIMELINE_ROW_HEIGHT;
		}

		graphics.text(minecraft.font, Component.literal("Build"), x, rowY + 7, 0xFFFFAA00, false);
		drawTimelineNotes(graphics, data.buildNotes(), viewportX, rowY, viewportWidth, mouseX, mouseY, true);
		drawTimelineScrollbar(graphics, viewportX, viewportY + viewportHeight + 5, viewportWidth);
	}

	private void drawTimelineGrid(GuiGraphicsExtractor graphics, int viewportX, int viewportY, int viewportWidth,
			int viewportHeight, int maxTime) {
		int firstTick = Math.max(0, timelineScroll / TIMELINE_TICK_WIDTH);
		int lastTick = Math.min(maxTime + 2, (timelineScroll + viewportWidth) / TIMELINE_TICK_WIDTH + 1);
		for (int tick = firstTick; tick <= lastTick; tick++) {
			int screenX = viewportX + tick * TIMELINE_TICK_WIDTH - timelineScroll;
			int color = tick % 4 == 0 ? 0x55888888 : 0x33444444;
			graphics.fill(screenX, viewportY, screenX + 1, viewportY + viewportHeight, color);
			if (tick % 4 == 0) {
				graphics.text(Minecraft.getInstance().font, Integer.toString(tick), screenX + 2, viewportY - 10, 0xFF777777, false);
			}
		}
		for (int row = 1; row <= tracks.size(); row++) {
			int lineY = viewportY + row * TIMELINE_ROW_HEIGHT;
			graphics.fill(viewportX, lineY, viewportX + viewportWidth, lineY + 1, 0x33444444);
		}
	}

	private void drawTimelinePlayhead(GuiGraphicsExtractor graphics, int viewportX, int viewportY, int viewportWidth,
			int viewportHeight, TimelineData data) {
		int time = currentBuildTimelineTime(data);
		if (time < 0) {
			return;
		}
		int screenX = viewportX + time * TIMELINE_TICK_WIDTH - timelineScroll;
		if (screenX < viewportX || screenX > viewportX + viewportWidth) {
			return;
		}
		graphics.fill(screenX - 1, viewportY - 12, screenX + 2, viewportY + viewportHeight, 0xCCFFAA00);
		graphics.text(Minecraft.getInstance().font, Integer.toString(time), screenX + 4, viewportY - 11, 0xFFFFAA00, false);
	}

	private int currentBuildTimelineTime(TimelineData data) {
		if (data.buildNotes().isEmpty()) {
			return -1;
		}
		if (playing && playbackStartedAt > 0L) {
			int previewTime = Math.round((Util.getMillis() - playbackStartedAt) / 100.0F);
			return Math.max(0, Math.min(data.maxTime(), previewTime));
		}
		int target = Math.max(0, config.placementSequencePosition());
		int physical = 0;
		int currentTime = 0;
		for (int index = 0; index < data.buildNotes().size();) {
			int eventTime = data.buildNotes().get(index).time();
			int delay = Math.max(0, eventTime - currentTime);
			while (delay > 0) {
				if (physical++ == target) {
					return currentTime;
				}
				int chunk = Math.min(4, delay);
				currentTime += chunk;
				delay -= chunk;
			}
			while (index < data.buildNotes().size() && data.buildNotes().get(index).time() == eventTime) {
				if (physical++ == target) {
					return eventTime;
				}
				index++;
			}
			currentTime = eventTime;
		}
		return data.buildNotes().getLast().time();
	}

	private static String fitTimelineLabel(String value) {
		Minecraft minecraft = Minecraft.getInstance();
		int maxWidth = TIMELINE_LEFT_WIDTH - 8;
		if (minecraft.font.width(value) <= maxWidth) {
			return value;
		}
		String ellipsis = "…";
		while (!value.isEmpty() && minecraft.font.width(value + ellipsis) > maxWidth) {
			value = value.substring(0, value.length() - 1);
		}
		return value + ellipsis;
	}

	private void drawTimelineNotes(GuiGraphicsExtractor graphics, List<TimelineNote> notes, int viewportX, int rowY,
			int viewportWidth, int mouseX, int mouseY, boolean buildRow) {
		Minecraft minecraft = Minecraft.getInstance();
		for (TimelineNote note : notes) {
			int noteX = viewportX + note.time() * TIMELINE_TICK_WIDTH - timelineScroll + note.offset() * TIMELINE_NOTE_WIDTH;
			if (noteX + TIMELINE_NOTE_WIDTH < viewportX || noteX > viewportX + viewportWidth) {
				continue;
			}
			int left = Math.max(viewportX, noteX);
			int right = Math.min(viewportX + viewportWidth, noteX + TIMELINE_NOTE_WIDTH);
			int fill = buildRow ? 0xAA6B5200 : note.buildEnabled() ? 0xAA00546B : 0x66444444;
			graphics.fill(left, rowY + 3, right, rowY + TIMELINE_ROW_HEIGHT - 3, fill);
			graphics.fill(left, rowY + 3, right, rowY + 4, buildRow ? 0xFFFFAA00 : 0xFF55FFFF);
			if (noteX >= viewportX && noteX + 8 < viewportX + viewportWidth) {
				graphics.text(minecraft.font, NotePitch.name(note.pitch()) + note.pitch(), noteX + 2, rowY + 7,
					note.buildEnabled() ? 0xFFFFFFFF : 0xFF999999, false);
			}
			if (buildRow && noteX >= viewportX && noteX < viewportX + viewportWidth) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(noteX + 1, rowY + 1);
				graphics.pose().scale(0.55F, 0.55F);
				graphics.text(minecraft.font, Integer.toString(note.trackNumber()), 0, 0, 0xFF55FFFF, false);
				graphics.pose().popMatrix();
			}
			if (mouseX >= noteX && mouseX < noteX + TIMELINE_NOTE_WIDTH
					&& mouseY >= rowY + 3 && mouseY < rowY + TIMELINE_ROW_HEIGHT - 3) {
				graphics.setTooltipForNextFrame(Component.literal(
					"T" + note.trackNumber() + " " + NotePitch.name(note.pitch()) + note.pitch() + " at " + note.time() + "d"
				), mouseX, mouseY);
			}
		}
	}

	private TimelineHit timelineHit(double mouseX, double mouseY) {
		TimelineData data = timelineData();
		int rowY = timelineViewportY;
		for (TimelineTrack track : data.tracks()) {
			TimelineNote note = hitTimelineNote(track.notes(), timelineViewportX, rowY, mouseX, mouseY);
			if (note != null) {
				return new TimelineHit(note.trackNumber() - 1, note.textFrom(), note.textTo());
			}
			rowY += TIMELINE_ROW_HEIGHT;
		}
		TimelineNote note = hitTimelineNote(data.buildNotes(), timelineViewportX, rowY, mouseX, mouseY);
		if (note != null) {
			return new TimelineHit(note.trackNumber() - 1, note.textFrom(), note.textTo());
		}
		return null;
	}

	private TimelineNote hitTimelineNote(List<TimelineNote> notes, int viewportX, int rowY, double mouseX, double mouseY) {
		for (TimelineNote note : notes) {
			int noteX = viewportX + note.time() * TIMELINE_TICK_WIDTH - timelineScroll + note.offset() * TIMELINE_NOTE_WIDTH;
			if (mouseX >= noteX && mouseX < noteX + TIMELINE_NOTE_WIDTH
					&& mouseY >= rowY + 3 && mouseY < rowY + TIMELINE_ROW_HEIGHT - 3) {
				return note;
			}
		}
		return null;
	}

	private void drawTimelineScrollbar(GuiGraphicsExtractor graphics, int x, int y, int width) {
		graphics.fill(x, y, x + width, y + 3, 0x66333333);
		if (timelineContentWidth <= width) {
			graphics.fill(x, y, x + width, y + 3, 0xFF777777);
			return;
		}
		int thumbWidth = Math.max(18, width * width / timelineContentWidth);
		int thumbX = x + timelineScroll * Math.max(0, width - thumbWidth) / Math.max(1, timelineContentWidth - width);
		graphics.fill(thumbX, y, thumbX + thumbWidth, y + 3, 0xFFAAAAAA);
	}

	private TimelineData timelineData() {
		List<TimelineTrack> timelineTracks = new ArrayList<>();
		List<TimelineNote> buildNotes = new ArrayList<>();
		int maxTime = 0;
		for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
			TrackRow track = tracks.get(trackIndex);
			List<TimelineNote> notes = new ArrayList<>();
			boolean error = false;
			try {
				notes.addAll(timelineNotesForTrack(track, trackIndex + 1));
			} catch (IllegalArgumentException exception) {
				error = true;
			}
			for (TimelineNote note : notes) {
				maxTime = Math.max(maxTime, note.time());
				if (track.buildEnabled) {
					buildNotes.add(note);
				}
			}
			timelineTracks.add(new TimelineTrack(track.trackName.getValue(), track.buildEnabled, error, List.copyOf(notes)));
		}
		buildNotes.sort(java.util.Comparator.comparingInt(TimelineNote::time)
			.thenComparingInt(TimelineNote::trackNumber)
			.thenComparingInt(TimelineNote::order));
		buildNotes = assignTimelineOffsets(buildNotes);
		return new TimelineData(timelineTracks, List.copyOf(buildNotes), maxTime);
	}

	private List<TimelineNote> timelineNotesForTrack(TrackRow track, int trackNumber) {
		List<Step> steps = NoteSequence.parse(track.sequence(), delayScaleQuarters());
		List<TextRange> ranges = tokenRanges(track.sequence(), delayScaleQuarters());
		List<TimelineNote> notes = new ArrayList<>();
		int time = 0;
		int order = 0;
		for (int index = 0; index < steps.size(); index++) {
			Step step = steps.get(index);
			if (step.type() == StepType.NOTE) {
				TextRange range = index < ranges.size() ? ranges.get(index) : new TextRange(0, 0);
				notes.add(new TimelineNote(time, trackNumber, step.value(), order++, 0, track.buildEnabled,
					range.from(), range.to()));
			} else {
				time += step.value();
			}
		}
		return assignTimelineOffsets(notes);
	}

	private static List<TimelineNote> assignTimelineOffsets(List<TimelineNote> notes) {
		List<TimelineNote> result = new ArrayList<>();
		int lastTime = Integer.MIN_VALUE;
		int offset = 0;
		for (TimelineNote note : notes) {
			if (note.time() != lastTime) {
				lastTime = note.time();
				offset = 0;
			}
			result.add(new TimelineNote(note.time(), note.trackNumber(), note.pitch(), note.order(), offset++, note.buildEnabled(),
				note.textFrom(), note.textTo()));
		}
		return List.copyOf(result);
	}

	private void clampTimelineScroll() {
		timelineScroll = Math.max(0, Math.min(timelineScroll, Math.max(0, timelineContentWidth - timelineViewportWidth)));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (expanded && timelineMode && event.button() == 0) {
			TimelineHit hit = timelineHit(event.x(), event.y());
			if (hit != null && hit.trackIndex() >= 0 && hit.trackIndex() < tracks.size()) {
				timelineMode = false;
				activeTrackIndex = hit.trackIndex();
				tracks.get(hit.trackIndex()).focusRange(hit.from(), hit.to());
				updateTrackControls();
				syncAndSave();
				return true;
			}
		}
		nameBox.setFocused(false);
		for (TrackRow track : tracks) {
			track.blurBoxes();
		}
		for (TrackRow track : tracks) {
			if (track.handleInstrumentClick(event)) {
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (expanded && timelineMode
				&& mouseX >= timelineViewportX && mouseX < timelineViewportX + timelineViewportWidth
				&& mouseY >= timelineViewportY && mouseY < timelineViewportY + timelineViewportHeight) {
			timelineScroll -= (int)Math.round(scrollY * 42.0);
			clampTimelineScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private List<AbstractWidget> widgets() {
		List<AbstractWidget> widgets = new ArrayList<>(List.of(
			expandButton, nameBox, delayScaleSlider, importMidiButton, pasteLineButton, viewModeButton, playButton
		));
		if (expanded && !timelineMode) {
			for (TrackRow track : tracks) {
				widgets.addAll(track.widgets());
			}
			widgets.add(addTrackButton);
		}
		return widgets;
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return widgets();
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return widgets();
	}

	@Override
	public String getValue() {
		return tracks.get(activeTrackIndex).sequence();
	}

	@Override
	public Optional<String> getDefaultValue() {
		return Optional.of("");
	}

	@Override
	public boolean isEdited() {
		return !sequenceName().equals(initialName)
			|| delayScaleQuarters() != initialDelayScaleQuarters
			|| !trackValues().equals(initialTracks)
			|| activeTrackIndex != initialActiveTrack;
	}

	@Override
	public Optional<Component> getError() {
		for (TrackRow track : tracks) {
			Optional<Component> error = FastNoteblocksConfig.validatePlacementSequence(track.sequence());
			if (error.isPresent()) {
				return error;
			}
		}
		return Optional.empty();
	}

	@Override
	public void save() {
		syncConfig();
	}

	private final class TrackRow {
		private final EditBox trackName;
		private final Button buildButton;
		private final Button collapseButton;
		private final Button deleteButton;
		private SequenceEditBox editor;
		private String sequence;
		private String instrument;
		private int position;
		private boolean buildEnabled;
		private boolean expanded = true;
		private boolean paletteOpen;
		private int iconX;
		private int iconY;
		private int paletteX;
		private int paletteY;
		private int pendingFocusFrom = -1;
		private int pendingFocusTo = -1;
		private TrackPlayback playback;

		TrackRow(SequenceTrack track) {
			this(track, true);
		}

		TrackRow(SequenceTrack track, boolean initiallyExpanded) {
			this.sequence = track.sequence();
			this.instrument = PreviewInstrument.byId(track.instrument()).id();
			this.position = track.position();
			this.buildEnabled = track.buildEnabled();
			this.expanded = initiallyExpanded;
			this.trackName = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 20, Component.literal("Track name"));
			trackName.setMaxLength(60);
			trackName.setValue(track.name());
			trackName.setResponder(value -> syncConfig());
			this.buildButton = Button.builder(Component.literal("Build"), button -> toggleBuildTrack(this))
				.bounds(0, 0, 52, 20)
				.tooltip(Tooltip.create(Component.literal("Include this track in the in-game build sequence")))
				.build();
			this.collapseButton = Button.builder(collapseLabel(), button -> {
				expanded = !expanded;
				button.setMessage(collapseLabel());
			}).bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Expand or collapse this track")))
				.build();
			this.deleteButton = Button.builder(Component.literal("×").withStyle(ChatFormatting.RED), button -> confirmDeleteTrack(this))
				.bounds(0, 0, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Delete this track")))
				.build();
		}

		void updateControls(boolean canDelete) {
			buildButton.setMessage(Component.literal(buildEnabled ? "Build on" : "Build off")
				.withStyle(buildEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			deleteButton.active = canDelete;
		}

		private Component collapseLabel() {
			return Component.literal(expanded ? "▾" : "▸");
		}

		int height() {
			if (!expanded) {
				return 20;
			}
			return 35 + (paletteOpen ? PALETTE_HEIGHT : 0) + EDITOR_HEIGHT;
		}

		String sequence() {
			return editor == null ? sequence : editor.getValue();
		}

		SequenceTrack value() {
			return new SequenceTrack(trackName.getValue(), sequence(), instrument, position, buildEnabled);
		}

		private SequenceEditBox editor(int width) {
			if (editor == null || editor.getWidth() != width) {
				String current = editor == null ? sequence : editor.getValue();
				editor = new SequenceEditBox(Minecraft.getInstance().font, width, EDITOR_HEIGHT);
				editor.setCharacterLimit(12000);
				editor.setValueListener(value -> {
					sequence = value;
					syncConfig();
				});
				editor.setValue(current);
				sequence = current;
			}
			if (pendingFocusFrom >= 0 && pendingFocusTo > pendingFocusFrom) {
				editor.setPlaybackHighlight(pendingFocusFrom, pendingFocusTo);
			}
			return editor;
		}

		void focusRange(int from, int to) {
			expanded = true;
			collapseButton.setMessage(collapseLabel());
			pendingFocusFrom = from;
			pendingFocusTo = to;
			if (editor != null) {
				editor.setPlaybackHighlight(from, to);
				editor.setFocused(true);
			}
		}

		void extract(GuiGraphicsExtractor graphics, int x, int y, int width,
				int mouseX, int mouseY, float partialTick) {
			buildButton.setX(x);
			buildButton.setY(y);
			buildButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			collapseButton.setX(x + 56);
			collapseButton.setY(y);
			collapseButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			deleteButton.setX(x + width - 20);
			deleteButton.setY(y);
			deleteButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
			iconX = x + width - 44;
			iconY = y;
			extractInstrumentIcon(graphics, mouseX, mouseY);
			trackName.setX(x + 80);
			trackName.setY(y);
			trackName.setWidth(Math.max(50, iconX - (x + 80) - 4));
			trackName.extractRenderState(graphics, mouseX, mouseY, partialTick);
			if (!expanded) {
				return;
			}

			extractCounts(graphics, x, y + 24);
			int contentY = y + 35;
			if (paletteOpen) {
				paletteX = x + Math.max(0, (width - PALETTE_COLUMNS * PALETTE_CELL) / 2);
				paletteY = contentY;
				extractPalette(graphics, mouseX, mouseY);
				contentY += PALETTE_HEIGHT;
			}
			SequenceEditBox box = editor(width);
			box.setX(x);
			box.setY(contentY);
			box.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}

		private void extractCounts(GuiGraphicsExtractor graphics, int x, int y) {
			try {
				NoteSequence.Progress progress = NoteSequence.progress(NoteSequence.parse(sequence(), delayScaleQuarters()), position);
				if (progress.total() == 0) {
					return;
				}
				Component counts = Component.empty()
					.append(Component.literal(progress.total() + " overall   ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(progress.noteBlockTotal() + " note blocks   ")
						.withStyle(ChatFormatting.AQUA))
					.append(Component.literal(progress.repeaterTotal() + " repeaters")
						.withStyle(ChatFormatting.GOLD));
				graphics.text(Minecraft.getInstance().font, counts, x, y, 0xFFFFFFFF, false);
			} catch (IllegalArgumentException ignored) {
			}
		}

		private void extractInstrumentIcon(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			PreviewInstrument selected = PreviewInstrument.byId(instrument);
			boolean hovered = mouseX >= iconX && mouseX < iconX + 20 && mouseY >= iconY && mouseY < iconY + 20;
			graphics.fill(iconX, iconY, iconX + 20, iconY + 20, paletteOpen ? 0xFFFFAA00 : hovered ? 0xFF888888 : 0xFF333333);
			graphics.fill(iconX + 1, iconY + 1, iconX + 19, iconY + 19, 0xFF111111);
			graphics.item(new ItemStack(selected.icon()), iconX + 2, iconY + 2);
			if (hovered) {
				graphics.setTooltipForNextFrame(Component.literal(selected.name()), mouseX, mouseY);
			}
		}

		private void extractPalette(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			for (int i = 0; i < PreviewInstrument.VALUES.size(); i++) {
				PreviewInstrument value = PreviewInstrument.VALUES.get(i);
				int cellX = paletteX + i % PALETTE_COLUMNS * PALETTE_CELL;
				int cellY = paletteY + i / PALETTE_COLUMNS * PALETTE_CELL;
				boolean selected = value.id().equals(instrument);
				boolean hovered = mouseX >= cellX && mouseX < cellX + 20 && mouseY >= cellY && mouseY < cellY + 20;
				graphics.fill(cellX, cellY, cellX + 20, cellY + 20, selected ? 0xFFFFAA00 : hovered ? 0xFF888888 : 0xFF333333);
				graphics.fill(cellX + 1, cellY + 1, cellX + 19, cellY + 19, 0xFF111111);
				graphics.item(new ItemStack(value.icon()), cellX + 2, cellY + 2);
				if (hovered) {
					graphics.setTooltipForNextFrame(Component.literal(value.name()), mouseX, mouseY);
				}
			}
		}

		boolean handleInstrumentClick(MouseButtonEvent event) {
			if (event.button() != 0) {
				return false;
			}
			if (event.x() >= iconX && event.x() < iconX + 20 && event.y() >= iconY && event.y() < iconY + 20) {
				if (!expanded) {
					expanded = true;
					paletteOpen = true;
					collapseButton.setMessage(collapseLabel());
				} else {
					paletteOpen = !paletteOpen;
				}
				for (TrackRow track : tracks) {
					if (track != this) {
						track.paletteOpen = false;
					}
				}
				return true;
			}
			if (!paletteOpen || event.x() < paletteX || event.y() < paletteY) {
				return false;
			}
			int column = (int)(event.x() - paletteX) / PALETTE_CELL;
			int row = (int)(event.y() - paletteY) / PALETTE_CELL;
			if (column < 0 || column >= PALETTE_COLUMNS || row < 0) {
				return false;
			}
			int index = row * PALETTE_COLUMNS + column;
			if (index >= PreviewInstrument.VALUES.size()
					|| event.x() >= paletteX + column * PALETTE_CELL + 20
					|| event.y() >= paletteY + row * PALETTE_CELL + 20) {
				return false;
			}
			PreviewInstrument selected = PreviewInstrument.VALUES.get(index);
			instrument = selected.id();
			selected.play();
			syncAndSave();
			return true;
		}

		List<AbstractWidget> widgets() {
			List<AbstractWidget> result = new ArrayList<>(List.of(buildButton, collapseButton, trackName, deleteButton));
			if (expanded && editor != null) {
				result.add(editor);
			}
			return result;
		}

		void blurBoxes() {
			trackName.setFocused(false);
			if (editor != null) {
				editor.setFocused(false);
			}
		}

		boolean startPlayback(long start) {
			List<Step> steps;
			try {
				steps = NoteSequence.parse(sequence(), delayScaleQuarters());
			} catch (IllegalArgumentException ignored) {
				return false;
			}
			if (steps.isEmpty()) {
				return false;
			}
			playback = new TrackPlayback(steps, tokenRanges(sequence(), delayScaleQuarters()), 0, start, true);
			return true;
		}

		boolean isPlaying() {
			return playback != null && playback.playing();
		}

		void stopPlayback() {
			playback = null;
			if (editor != null) {
				editor.clearPlaybackHighlight();
			}
		}

		void updatePlayback(long now) {
			while (isPlaying() && now >= playback.nextAt()) {
				if (playback.index() >= playback.steps().size()) {
					stopPlayback();
					return;
				}
				int index = playback.index();
				Step step = playback.steps().get(index);
				if (editor != null && index < playback.ranges().size()) {
					TextRange range = playback.ranges().get(index);
					editor.setPlaybackHighlight(range.from(), range.to());
				}
				if (step.type() == StepType.NOTE) {
					PreviewInstrument.byId(instrument).play(step.value());
					int nextIndex = index + 1;
					if (nextIndex >= playback.steps().size()) {
						playback = new TrackPlayback(playback.steps(), playback.ranges(), nextIndex, now + 150L, true);
						return;
					}
					playback = new TrackPlayback(playback.steps(), playback.ranges(), nextIndex, playback.nextAt(), true);
				} else {
					int remaining = Math.max(1, step.delayCount() - step.delayIndex());
					playback = new TrackPlayback(
						playback.steps(), playback.ranges(), Math.min(playback.steps().size(), index + remaining),
						playback.nextAt() + step.delayTotal() * 100L, true
					);
				}
			}
		}
	}

	private static List<TextRange> tokenRanges(String value, int delayScaleQuarters) {
		List<TextRange> ranges = new ArrayList<>();
		for (NoteSequence.Token token : NoteSequence.tokens(value)) {
			int repeat = 1;
			if (token.text().endsWith("d") || token.text().endsWith("D")) {
				int delay = Math.max(1, Math.round(
					Integer.parseInt(token.text().substring(0, token.text().length() - 1).trim())
						* delayScaleQuarters / 4.0F
				));
				repeat = (delay + 3) / 4;
			}
			for (int copy = 0; copy < repeat; copy++) {
				ranges.add(new TextRange(token.from(), token.to()));
			}
		}
		return List.copyOf(ranges);
	}

	private record TimelineData(List<TimelineTrack> tracks, List<TimelineNote> buildNotes, int maxTime) {
	}

	private record TimelineTrack(String name, boolean buildEnabled, boolean error, List<TimelineNote> notes) {
	}

	private record TimelineNote(
		int time,
		int trackNumber,
		int pitch,
		int order,
		int offset,
		boolean buildEnabled,
		int textFrom,
		int textTo
	) {
	}

	private record EventNote(int time, int trackNumber, int order, int pitch, String instrumentBlock) {
	}

	private record EventGroup(int time, List<EventNote> notes, int length, int maxSafeTurnDistance) {
	}

	private record ChordStats(int peak, int peakTime, int overloadedTimes) {
	}

	private record CompactLayout(Set<Integer> breakAfter, int width, int depth) {
		int squareSize() {
			return Math.max(width, depth);
		}

		int area() {
			return width * depth;
		}
	}

	private enum PasteMode {
		COMPACT("Compact"),
		STRAIGHT("Straight");

		private final String label;

		PasteMode(String label) {
			this.label = label;
		}

		String label() {
			return label;
		}
	}

	private record PastePlan(List<String> commands, int width, int depth, int height, PasteMode mode) {
	}

	private record DelayTrigger(int cursor, int triggerDelay) {
	}

	private record SpatialDelayTrigger(BlockPos cursor, int triggerDelay) {
	}

	private static final class PlacementPlan {
		private final Map<BlockPos, String> blocks = new LinkedHashMap<>();
		private int minimumX = Integer.MAX_VALUE;
		private int minimumY = Integer.MAX_VALUE;
		private int minimumZ = Integer.MAX_VALUE;
		private int maximumX = Integer.MIN_VALUE;
		private int maximumY = Integer.MIN_VALUE;
		private int maximumZ = Integer.MIN_VALUE;

		void set(BlockPos position, String block) {
			BlockPos key = position.immutable();
			String existing = blocks.putIfAbsent(key, block);
			if (existing != null && !existing.equals(block)) {
				throw new IllegalArgumentException("Placement layout collision at "
					+ key.getX() + " " + key.getY() + " " + key.getZ());
			}
			if (!"minecraft:air".equals(block)) {
				minimumX = Math.min(minimumX, key.getX());
				minimumY = Math.min(minimumY, key.getY());
				minimumZ = Math.min(minimumZ, key.getZ());
				maximumX = Math.max(maximumX, key.getX());
				maximumY = Math.max(maximumY, key.getY());
				maximumZ = Math.max(maximumZ, key.getZ());
			}
		}

		PastePlan finish(PasteMode mode) {
			List<String> commands = blocks.entrySet().stream()
				.map(entry -> "setblock " + entry.getKey().getX() + " " + entry.getKey().getY() + " "
					+ entry.getKey().getZ() + " " + entry.getValue() + " replace")
				.toList();
			int widthX = maximumX < minimumX ? 0 : maximumX - minimumX + 1;
			int widthZ = maximumZ < minimumZ ? 0 : maximumZ - minimumZ + 1;
			int height = maximumY < minimumY ? 0 : maximumY - minimumY + 1;
			return new PastePlan(commands, Math.max(widthX, widthZ), Math.min(widthX, widthZ), height, mode);
		}
	}

	private static final class PasteModeScreen extends Screen {
		private final Screen parent;
		private final Consumer<PasteMode> selection;

		PasteModeScreen(Screen parent, Consumer<PasteMode> selection) {
			super(Component.literal("Choose placement layout"));
			this.parent = parent;
			this.selection = selection;
		}

		@Override
		protected void init() {
			int centerX = width / 2;
			int buttonY = height / 2 + 8;
			addRenderableWidget(Button.builder(Component.literal("Compact"), button -> choose(PasteMode.COMPACT))
				.bounds(centerX - 138, buttonY, 88, 20)
				.tooltip(Tooltip.create(Component.literal(
					"Fold one continuous signal path into a compact snake"
				)))
				.build());
			addRenderableWidget(Button.builder(Component.literal("Straight"), button -> choose(PasteMode.STRAIGHT))
				.bounds(centerX - 44, buttonY, 88, 20)
				.tooltip(Tooltip.create(Component.literal("Keep the original straight playable line")))
				.build());
			addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.bounds(centerX + 50, buttonY, 88, 20)
				.build());
		}

		private void choose(PasteMode mode) {
			selection.accept(mode);
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			extractBlurredBackground(graphics);
			extractTransparentBackground(graphics);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.centeredText(font, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
			graphics.centeredText(font,
				Component.literal("Both layouts use one continuous, non-crossing redstone path."),
				width / 2, height / 2 - 14, 0xFFBBBBBB);
		}

		@Override
		public void onClose() {
			minecraft.gui.setScreen(parent);
		}
	}

	private record TimelineHit(int trackIndex, int from, int to) {
	}

	private record TextRange(int from, int to) {
	}

	private record TrackPlayback(List<Step> steps, List<TextRange> ranges, int index, long nextAt, boolean playing) {
	}

	private static final class DelayScaleSlider extends AbstractSliderButton {
		private final java.util.function.IntConsumer listener;
		private int scaleQuarters;

		DelayScaleSlider(int x, int y, int width, int height, int scaleQuarters, java.util.function.IntConsumer listener) {
			super(x, y, width, height, Component.empty(), 0.0);
			this.listener = listener;
			setScale(scaleQuarters);
		}

		int scaleQuarters() {
			return scaleQuarters;
		}

		void setScale(int scaleQuarters) {
			this.scaleQuarters = FastNoteblocksConfig.clampSequenceDelayScale(scaleQuarters);
			value = (this.scaleQuarters - FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS)
				/ (double)(FastNoteblocksConfig.MAX_SEQUENCE_DELAY_SCALE_QUARTERS
					- FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Scale " + FastNoteblocksConfig.delayScaleLabel(scaleQuarters)));
		}

		@Override
		protected void applyValue() {
			int range = FastNoteblocksConfig.MAX_SEQUENCE_DELAY_SCALE_QUARTERS
				- FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS;
			int updated = FastNoteblocksConfig.MIN_SEQUENCE_DELAY_SCALE_QUARTERS
				+ Math.round((float)(value * range));
			updated = FastNoteblocksConfig.clampSequenceDelayScale(updated);
			if (updated != scaleQuarters) {
				scaleQuarters = updated;
				listener.accept(scaleQuarters);
			}
		}
	}
}
