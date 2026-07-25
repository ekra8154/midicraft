package com.fastnoteblocks.client.composer;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.NoteSequence.Step;
import com.fastnoteblocks.NoteSequence.StepType;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent, MIDI-shaped representation of a composition.
 *
 * <p>The legacy sequence text remains a projection used by the in-world builder.
 * Composer notes deliberately retain pitches outside the note-block range.</p>
 */
public record ComposerProject(
	String name,
	int ppq,
	int tempoMicrosPerQuarter,
	List<Layer> layers,
	int activeLayerIndex,
	long nextNoteId
) {
	public static final int DEFAULT_PPQ = 480;
	public static final int DEFAULT_TEMPO_MICROS_PER_QUARTER = 500_000;
	public static final int NOTE_BLOCK_BASE_MIDI_NOTE = 54;
	public static final int NOTE_BLOCK_MAX_MIDI_NOTE = NOTE_BLOCK_BASE_MIDI_NOTE + NotePitch.PITCH_COUNT - 1;
	public static final long DEFAULT_NOTE_DURATION_TICKS = DEFAULT_PPQ / 4L;

	public ComposerProject {
		name = name == null || name.isBlank() ? "Untitled sequence" : name.trim();
		ppq = Math.max(1, ppq);
		tempoMicrosPerQuarter = Math.max(1, tempoMicrosPerQuarter);
		layers = normalizeLayers(layers);
		activeLayerIndex = Math.max(0, Math.min(layers.size() - 1, activeLayerIndex));
		long highestId = layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(NoteEvent::id)
			.max()
			.orElse(0L);
		nextNoteId = Math.max(highestId + 1L, nextNoteId);
	}

	public record NoteEvent(long id, int midiNote, long startTick, long durationTicks, int velocity) {
		public NoteEvent {
			id = Math.max(1L, id);
			midiNote = Math.max(0, Math.min(127, midiNote));
			startTick = Math.max(0L, startTick);
			durationTicks = Math.max(1L, durationTicks);
			velocity = Math.max(1, Math.min(127, velocity));
		}

		public boolean isBuildable() {
			return midiNote >= NOTE_BLOCK_BASE_MIDI_NOTE && midiNote <= NOTE_BLOCK_MAX_MIDI_NOTE;
		}

		public int noteBlockPitch() {
			return midiNote - NOTE_BLOCK_BASE_MIDI_NOTE;
		}

		public NoteEvent movedTo(long tick, int note) {
			return new NoteEvent(id, note, tick, durationTicks, velocity);
		}
	}

	public record Layer(
		String name,
		String instrument,
		boolean muted,
		boolean buildEnabled,
		boolean visible,
		List<NoteEvent> notes
	) {
		public Layer {
			name = name == null || name.isBlank() ? "Layer" : name.trim();
			instrument = instrument == null || instrument.isBlank() ? "HARP" : instrument;
			notes = notes == null
				? List.of()
				: notes.stream()
					.filter(java.util.Objects::nonNull)
					.sorted(Comparator.comparingLong(NoteEvent::startTick)
						.thenComparingInt(NoteEvent::midiNote)
						.thenComparingLong(NoteEvent::id))
					.toList();
		}

		public Layer withNotes(List<NoteEvent> value) {
			return new Layer(name, instrument, muted, buildEnabled, visible, value);
		}

		public Layer withName(String value) {
			return new Layer(value, instrument, muted, buildEnabled, visible, notes);
		}

		public Layer withInstrument(String value) {
			return new Layer(name, value, muted, buildEnabled, visible, notes);
		}

		public Layer withMuted(boolean value) {
			return new Layer(name, instrument, value, buildEnabled, visible, notes);
		}

		public Layer withBuildEnabled(boolean value) {
			return new Layer(name, instrument, muted, value, visible, notes);
		}

		public Layer withVisible(boolean value) {
			return new Layer(name, instrument, muted, buildEnabled, value, notes);
		}
	}

	public static ComposerProject empty(String name) {
		return new ComposerProject(name, DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new Layer("Track 1", "HARP", false, true, true, List.of())), 0, 1L);
	}

	public static ComposerProject fromSequenceTracks(
		String name,
		List<SequenceTrack> tracks,
		int activeTrackIndex,
		int delayScaleQuarters
	) {
		if (tracks == null || tracks.isEmpty()) {
			return empty(name);
		}
		List<Layer> layers = new ArrayList<>();
		long nextId = 1L;
		for (SequenceTrack track : tracks) {
			List<NoteEvent> notes = new ArrayList<>();
			long time = 0L;
			for (Step step : NoteSequence.parse(track.sequence(), delayScaleQuarters)) {
				if (step.type() == StepType.REPEATER) {
					time += minecraftTickToComposerTick(step.value(), DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER);
				} else {
					notes.add(new NoteEvent(nextId++, NOTE_BLOCK_BASE_MIDI_NOTE + step.value(), time,
						DEFAULT_NOTE_DURATION_TICKS, 96));
				}
			}
			layers.add(new Layer(track.name(), track.instrument(), "MUTE".equals(track.instrument()),
				track.buildEnabled(), true, notes));
		}
		return new ComposerProject(name, DEFAULT_PPQ, DEFAULT_TEMPO_MICROS_PER_QUARTER,
			layers, activeTrackIndex, nextId);
	}

	public List<SequenceTrack> toSequenceTracks(List<SequenceTrack> previousTracks, int delayScaleQuarters) {
		List<SequenceTrack> result = new ArrayList<>();
		for (int index = 0; index < layers.size(); index++) {
			Layer layer = layers.get(index);
			SequenceTrack previous = previousTracks != null && index < previousTracks.size()
				? previousTracks.get(index)
				: new SequenceTrack(layer.name(), "", layer.instrument(), 0, layer.buildEnabled());
			result.add(new SequenceTrack(
				layer.name(),
				sequenceText(layer, delayScaleQuarters),
				layer.muted() ? "MUTE" : layer.instrument(),
				previous.position(),
				layer.buildEnabled()
			));
		}
		return List.copyOf(result);
	}

	public ComposerProject withLayer(int index, Layer layer) {
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(Math.max(0, Math.min(updated.size() - 1, index)), layer);
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
	}

	public ComposerProject withActiveLayer(int index) {
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, index, nextNoteId);
	}

	public ComposerProject addLayer() {
		if (layers.size() >= 4) {
			return this;
		}
		List<Layer> updated = new ArrayList<>(layers);
		int number = updated.size() + 1;
		updated.add(new Layer("Layer " + number, "HARP", false, true, true, List.of()));
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, updated.size() - 1, nextNoteId);
	}

	public ComposerProject moveNotesToLayer(Set<Long> ids, int targetLayer) {
		if (ids == null || ids.isEmpty()) {
			return withActiveLayer(targetLayer);
		}
		int target = Math.max(0, Math.min(layers.size() - 1, targetLayer));
		Set<Long> selected = new LinkedHashSet<>(ids);
		List<NoteEvent> moving = layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.filter(note -> selected.contains(note.id()))
			.toList();
		List<Layer> updated = new ArrayList<>();
		for (Layer layer : layers) {
			updated.add(layer.withNotes(layer.notes().stream()
				.filter(note -> !selected.contains(note.id()))
				.toList()));
		}
		List<NoteEvent> targetNotes = new ArrayList<>(updated.get(target).notes());
		targetNotes.addAll(moving);
		updated.set(target, updated.get(target).withNotes(targetNotes));
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, target, nextNoteId);
	}

	public ComposerProject withName(String value) {
		return new ComposerProject(value, ppq, tempoMicrosPerQuarter, layers, activeLayerIndex, nextNoteId);
	}

	public ComposerProject addNote(int layerIndex, int midiNote, long startTick, long durationTicks) {
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Layer layer = layers.get(target);
		List<NoteEvent> notes = new ArrayList<>(layer.notes());
		notes.add(new NoteEvent(nextNoteId, midiNote, startTick, durationTicks, 96));
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(target, layer.withNotes(notes));
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, target, nextNoteId + 1L);
	}

	public PasteResult pasteNotes(int layerIndex, List<ClipboardNote> clipboard, long startTick) {
		if (clipboard == null || clipboard.isEmpty()) {
			return new PasteResult(this, Set.of());
		}
		int target = Math.max(0, Math.min(layers.size() - 1, layerIndex));
		Layer layer = layers.get(target);
		List<NoteEvent> notes = new ArrayList<>(layer.notes());
		Set<Long> addedIds = new LinkedHashSet<>();
		long id = nextNoteId;
		for (ClipboardNote copied : clipboard) {
			NoteEvent added = new NoteEvent(id++, copied.midiNote(),
				Math.max(0L, startTick + copied.tickOffset()), copied.durationTicks(), copied.velocity());
			notes.add(added);
			addedIds.add(added.id());
		}
		List<Layer> updated = new ArrayList<>(layers);
		updated.set(target, layer.withNotes(notes));
		return new PasteResult(
			new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, target, id),
			Set.copyOf(addedIds)
		);
	}

	public ComposerProject deleteNotes(Set<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return this;
		}
		Set<Long> selected = new LinkedHashSet<>(ids);
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.filter(note -> !selected.contains(note.id()))
				.toList()))
			.toList();
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
	}

	public ComposerProject moveNotes(Set<Long> ids, long tickDelta, int pitchDelta) {
		if (ids == null || ids.isEmpty() || tickDelta == 0L && pitchDelta == 0) {
			return this;
		}
		Set<Long> selected = new LinkedHashSet<>(ids);
		List<Layer> updated = layers.stream()
			.map(layer -> layer.withNotes(layer.notes().stream()
				.map(note -> selected.contains(note.id())
					? note.movedTo(Math.max(0L, note.startTick() + tickDelta),
						Math.max(0, Math.min(127, note.midiNote() + pitchDelta)))
					: note)
				.toList()))
			.toList();
		return new ComposerProject(name, ppq, tempoMicrosPerQuarter, updated, activeLayerIndex, nextNoteId);
	}

	public long endTick() {
		return layers.stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(note -> note.startTick() + note.durationTicks())
			.max()
			.orElse(ppq * 4L);
	}

	private String sequenceText(Layer layer, int delayScaleQuarters) {
		List<NoteEvent> buildable = layer.notes().stream().filter(NoteEvent::isBuildable).toList();
		List<String> tokens = new ArrayList<>();
		long previousTick = 0L;
		for (int index = 0; index < buildable.size();) {
			long eventTick = buildable.get(index).startTick();
			int physicalDelay = composerTicksToMinecraftTicks(
				Math.max(0L, eventTick - previousTick), ppq, tempoMicrosPerQuarter
			);
			int rawDelay = Math.max(0, Math.round(physicalDelay * 4.0F / Math.max(1, delayScaleQuarters)));
			addDelayTokens(tokens, rawDelay);
			while (index < buildable.size() && buildable.get(index).startTick() == eventTick) {
				tokens.add(Integer.toString(buildable.get(index).noteBlockPitch()));
				index++;
			}
			previousTick = eventTick;
		}
		return String.join(", ", tokens);
	}

	private static List<Layer> normalizeLayers(List<Layer> source) {
		List<Layer> normalized = new ArrayList<>();
		if (source != null) {
			for (Layer layer : source) {
				if (layer != null && normalized.size() < 4) {
					normalized.add(new Layer(layer.name(), layer.instrument(), layer.muted(),
						layer.buildEnabled(), layer.visible(), layer.notes()));
				}
			}
		}
		if (normalized.isEmpty()) {
			normalized.add(new Layer("Track 1", "HARP", false, true, true, List.of()));
		}
		return List.copyOf(normalized);
	}

	private static long minecraftTickToComposerTick(int minecraftTicks, int ppq, int tempoMicrosPerQuarter) {
		return Math.max(0L, Math.round(minecraftTicks * 100_000.0 * ppq / tempoMicrosPerQuarter));
	}

	private static int composerTicksToMinecraftTicks(long ticks, int ppq, int tempoMicrosPerQuarter) {
		return Math.max(0, (int)Math.round(ticks * tempoMicrosPerQuarter / (double)ppq / 100_000.0));
	}

	private static void addDelayTokens(List<String> tokens, int delay) {
		int remaining = delay;
		while (remaining > 0) {
			int chunk = Math.min(remaining, NoteSequence.MAX_GROUPED_DELAY);
			tokens.add(chunk + "d");
			remaining -= chunk;
		}
	}

	public record ClipboardNote(long tickOffset, int midiNote, long durationTicks, int velocity) {
		public ClipboardNote {
			tickOffset = Math.max(0L, tickOffset);
			midiNote = Math.max(0, Math.min(127, midiNote));
			durationTicks = Math.max(1L, durationTicks);
			velocity = Math.max(1, Math.min(127, velocity));
		}
	}

	public record PasteResult(ComposerProject project, Set<Long> noteIds) {
	}
}
