package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiQuantizeGrid;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiRangeFit;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiTempoFit;
import com.fastnoteblocks.client.FastNoteblocksConfig.SavedSequence;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

final class MidiImporter {
	private static final int DEFAULT_TEMPO_US_PER_QUARTER = 500_000;
	private static final int MINECRAFT_REPEATER_TICK_US = 100_000;
	private static final int NOTE_BLOCK_BASE_MIDI_NOTE = 54;

	private MidiImporter() {
	}

	static Result importFile(String path, FastNoteblocksConfig config) throws Exception {
		javax.sound.midi.Sequence midi = MidiSystem.getSequence(new File(path));
		if (midi.getDivisionType() != javax.sound.midi.Sequence.PPQ) {
			throw new IllegalArgumentException("Only PPQ MIDI files are supported for now.");
		}
		int resolution = Math.max(1, midi.getResolution());
		int tempoUsPerQuarter = firstTempo(midi);
		List<Part> parts = collectParts(midi, config.midiIgnorePercussion());
		parts = parts.stream()
			.filter(part -> !part.notes().isEmpty())
			.sorted(Comparator.comparingInt((Part part) -> part.notes().size()).reversed())
			.limit(config.midiMaxImportedTracks())
			.toList();
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("No playable MIDI notes were found.");
		}

		int gridTicks = gridTicks(parts, resolution, config.midiQuantizeGrid());
		int originalTempoUsPerQuarter = tempoUsPerQuarter;
		tempoUsPerQuarter = fitTempo(tempoUsPerQuarter, gridTicks, resolution, config.midiTempoFit());
		String instrument = PreviewInstrument.byId(config.midiDefaultInstrument()).id();
		List<SequenceTrack> tracks = new ArrayList<>();
		int skipped = 0;
		int clamped = 0;
		int quiet = 0;
		for (int index = 0; index < parts.size(); index++) {
			ConvertedPart converted = convertPart(
				parts.get(index), gridTicks, resolution, tempoUsPerQuarter, config.midiRangeFit(),
				config.midiVelocityCutoff()
			);
			skipped += converted.skipped();
			clamped += converted.clamped();
			quiet += converted.quiet();
			if (!converted.sequence().isBlank()) {
				tracks.add(new SequenceTrack(trackName(index, parts.get(index)), converted.sequence(), instrument, 0));
			}
		}
		if (tracks.isEmpty()) {
			throw new IllegalArgumentException(quiet > 0
				? "Every note fell below the velocity cutoff of " + config.midiVelocityCutoff() + "."
				: "Every selected MIDI part was empty after range fitting.");
		}

		String name = fileName(path);
		String report = "Imported " + tracks.size() + (tracks.size() == 1 ? " track" : " tracks")
			+ " at " + gridLabel(gridTicks, resolution) + " quantize";
		if (tempoUsPerQuarter != originalTempoUsPerQuarter) {
			report += ", tempo snapped " + bpmLabel(originalTempoUsPerQuarter)
				+ " to " + bpmLabel(tempoUsPerQuarter);
		}
		if (skipped > 0 || clamped > 0) {
			report += " (" + skipped + " skipped, " + clamped + " clamped)";
		}
		if (quiet > 0) {
			report += ", " + quiet + " below velocity " + config.midiVelocityCutoff();
		}
		return new Result(new SavedSequence(
			name, tracks, 0, FastNoteblocksConfig.DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS
		), report);
	}

	static ProjectResult importProject(String path, FastNoteblocksConfig config) throws Exception {
		javax.sound.midi.Sequence midi = MidiSystem.getSequence(new File(path));
		if (midi.getDivisionType() != javax.sound.midi.Sequence.PPQ) {
			throw new IllegalArgumentException("Only PPQ MIDI files are supported for now.");
		}
		int resolution = Math.max(1, midi.getResolution());
		int tempo = firstTempo(midi);
		List<ExactPart> playable = collectExactParts(midi, config.midiIgnorePercussion(), resolution)
			.stream()
			.filter(part -> !part.notes().isEmpty())
			.sorted(Comparator.comparingInt((ExactPart part) -> part.notes().size()).reversed())
			.toList();
		// Busiest parts first, so the cut falls on the sparsest. A reasonable rule and a
		// treacherous one: a quiet intro can be its own sparse part, so trimming by note count can
		// remove a stretch of time rather than a bit of texture. Hence reporting what was cut.
		List<ExactPart> parts = playable.stream()
			.limit(config.midiMaxImportedTracks())
			.toList();
		int droppedParts = playable.size() - parts.size();
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("No playable MIDI notes were found.");
		}

		String defaultInstrument = PreviewInstrument.byId(config.midiDefaultInstrument()).id();
		List<Layer> layers = new ArrayList<>();
		long nextId = 1L;
		int outsideRange = 0;
		int quiet = 0;
		int velocityCutoff = config.midiVelocityCutoff();
		for (int index = 0; index < parts.size(); index++) {
			ExactPart part = parts.get(index);
			List<NoteEvent> notes = new ArrayList<>();
			for (ExactNote note : part.notes()) {
				if (note.velocity() < velocityCutoff) {
					quiet++;
					continue;
				}
				NoteEvent event = new NoteEvent(nextId++, note.midiNote(), note.startTick(),
					Math.max(1L, note.endTick() - note.startTick()), note.velocity());
				if (!event.isBuildable()) {
					outsideRange++;
				}
				notes.add(event);
			}
			if (notes.isEmpty()) {
				continue;
			}
			layers.add(new Layer(trackName(index, new Part(part.name(), part.trackIndex(), part.channel(), List.of())),
				defaultInstrument, false, true, true, notes));
		}
		if (layers.isEmpty()) {
			throw new IllegalArgumentException(
				"Every note fell below the velocity cutoff of " + velocityCutoff + "."
			);
		}
		// Standard MIDI files end at an explicit End of Track event, and getTickLength reports it.
		// That position is the song's real length: anything after the last note is trailing silence
		// the author wrote on purpose, and dropping it used to shorten every import.
		ComposerProject project = new ComposerProject(
			fileName(path), resolution, tempo, layers, 0, nextId,
			Math.max(0L, midi.getTickLength()), ComposerProject.DEFAULT_SPEED_QUARTERS
		);
		String report = "Imported " + layers.size() + (layers.size() == 1 ? " layer" : " layers")
			+ " at " + bpmLabel(tempo);
		if (outsideRange > 0) {
			report += "; " + outsideRange + " notes kept outside Minecraft's range";
		}
		if (quiet > 0) {
			report += "; " + quiet + " notes below velocity " + velocityCutoff + " dropped";
		}
		if (droppedParts > 0) {
			long droppedNotes = playable.stream()
				.skip(parts.size())
				.mapToLong(part -> part.notes().size())
				.sum();
			report += "; " + droppedParts + (droppedParts == 1 ? " sparser track" : " sparser tracks")
				+ " (" + droppedNotes + " notes) left out by the "
				+ config.midiMaxImportedTracks() + "-track limit - raise Import > Max tracks to keep them";
		}
		return new ProjectResult(project, report);
	}

	private static List<ExactPart> collectExactParts(
		javax.sound.midi.Sequence midi,
		boolean ignorePercussion,
		int resolution
	) {
		Map<PartKey, MutableExactPart> parts = new HashMap<>();
		Track[] tracks = midi.getTracks();
		for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
			int currentTrackIndex = trackIndex;
			Track track = tracks[trackIndex];
			String name = trackName(track);
			for (int eventIndex = 0; eventIndex < track.size(); eventIndex++) {
				MidiEvent event = track.get(eventIndex);
				if (!(event.getMessage() instanceof ShortMessage message)) {
					continue;
				}
				int channel = message.getChannel();
				if (ignorePercussion && channel == 9) {
					continue;
				}
				PartKey key = new PartKey(trackIndex, channel);
				MutableExactPart part = parts.computeIfAbsent(key,
					ignored -> new MutableExactPart(name, currentTrackIndex, channel));
				int command = message.getCommand();
				int midiNote = message.getData1();
				boolean noteOn = command == ShortMessage.NOTE_ON && message.getData2() > 0;
				boolean noteOff = command == ShortMessage.NOTE_OFF
					|| command == ShortMessage.NOTE_ON && message.getData2() == 0;
				if (noteOn) {
					part.active().computeIfAbsent(midiNote, ignored -> new ArrayDeque<>())
						.addLast(new PendingNote(event.getTick(), message.getData2()));
				} else if (noteOff) {
					Deque<PendingNote> pending = part.active().get(midiNote);
					if (pending != null && !pending.isEmpty()) {
						PendingNote start = pending.removeFirst();
						part.notes().add(new ExactNote(start.tick(), Math.max(start.tick() + 1L, event.getTick()),
							midiNote, start.velocity()));
					}
				}
			}
			long fallbackEnd = Math.max(track.ticks(), resolution / 4L);
			for (MutableExactPart part : parts.values()) {
				if (part.trackIndex() != trackIndex) {
					continue;
				}
				for (Map.Entry<Integer, Deque<PendingNote>> active : part.active().entrySet()) {
					while (!active.getValue().isEmpty()) {
						PendingNote start = active.getValue().removeFirst();
						part.notes().add(new ExactNote(start.tick(), Math.max(start.tick() + resolution / 4L, fallbackEnd),
							active.getKey(), start.velocity()));
					}
				}
			}
		}
		return parts.values().stream()
			.map(part -> new ExactPart(part.name(), part.trackIndex(), part.channel(),
				part.notes().stream()
					.sorted(Comparator.comparingLong(ExactNote::startTick)
						.thenComparingInt(ExactNote::midiNote))
					.toList()))
			.toList();
	}

	private static List<Part> collectParts(javax.sound.midi.Sequence midi, boolean ignorePercussion) {
		Map<PartKey, MutablePart> parts = new HashMap<>();
		Track[] midiTracks = midi.getTracks();
		for (int trackIndex = 0; trackIndex < midiTracks.length; trackIndex++) {
			int currentTrackIndex = trackIndex;
			Track track = midiTracks[trackIndex];
			String name = trackName(track);
			for (int eventIndex = 0; eventIndex < track.size(); eventIndex++) {
				MidiEvent event = track.get(eventIndex);
				MidiMessage message = event.getMessage();
				if (message instanceof ShortMessage shortMessage
						&& shortMessage.getCommand() == ShortMessage.NOTE_ON
						&& shortMessage.getData2() > 0) {
					int channel = shortMessage.getChannel();
					if (ignorePercussion && channel == 9) {
						continue;
					}
					PartKey key = new PartKey(currentTrackIndex, channel);
					parts.computeIfAbsent(key, ignored -> new MutablePart(name, currentTrackIndex, channel))
						.notes()
						.add(new NoteStart(event.getTick(), shortMessage.getData1(), shortMessage.getData2()));
				}
			}
		}
		return parts.values().stream()
			.map(part -> new Part(part.name(), part.trackIndex(), part.channel(), List.copyOf(part.notes())))
			.toList();
	}

	private static ConvertedPart convertPart(
		Part part,
		int gridTicks,
		int resolution,
		int tempoUsPerQuarter,
		MidiRangeFit rangeFit,
		int velocityCutoff
	) {
		List<NoteStart> audible = part.notes().stream()
			.filter(note -> note.velocity() >= velocityCutoff)
			.toList();
		int quiet = part.notes().size() - audible.size();
		int shift = rangeFit == MidiRangeFit.CLAMP ? 0 : bestOctaveShift(audible);
		TreeMap<Long, LinkedHashSet<Integer>> groups = new TreeMap<>();
		int skipped = 0;
		int clamped = 0;
		for (NoteStart note : audible) {
			int pitch = note.midiNote() - NOTE_BLOCK_BASE_MIDI_NOTE + shift;
			FittedNote fitted = fitPitch(pitch, rangeFit);
			if (fitted.skipped()) {
				skipped++;
				continue;
			}
			if (fitted.clamped()) {
				clamped++;
			}
			long quantizedTick = Math.round(note.tick() / (double)gridTicks) * (long)gridTicks;
			groups.computeIfAbsent(quantizedTick, ignored -> new LinkedHashSet<>()).add(fitted.pitch());
		}

		List<String> tokens = new ArrayList<>();
		int previousMinecraftTick = 0;
		for (Map.Entry<Long, LinkedHashSet<Integer>> entry : groups.entrySet()) {
			int minecraftTick = midiTickToMinecraftTick(entry.getKey(), resolution, tempoUsPerQuarter);
			if (minecraftTick > previousMinecraftTick) {
				int delay = minecraftTick - previousMinecraftTick;
				addDelayTokens(tokens, delay);
			}
			entry.getValue().stream().sorted().forEach(pitch -> tokens.add(Integer.toString(pitch)));
			previousMinecraftTick = minecraftTick;
		}
		return new ConvertedPart(String.join(", ", tokens), skipped, clamped, quiet);
	}

	private static int midiTickToMinecraftTick(long midiTick, int resolution, int tempoUsPerQuarter) {
		return Math.max(0, (int)Math.round(
			midiTick * tempoUsPerQuarter / (double)resolution / MINECRAFT_REPEATER_TICK_US
		));
	}

	private static int fitTempo(int tempoUsPerQuarter, int gridTicks, int resolution, MidiTempoFit fit) {
		if (fit != MidiTempoFit.SNAP_TO_REPEATERS) {
			return tempoUsPerQuarter;
		}
		double originalGridRepeaterTicks = gridTicks * tempoUsPerQuarter
			/ (double)resolution / MINECRAFT_REPEATER_TICK_US;
		int nearestGridRepeaterTicks = Math.max(1, (int)Math.round(originalGridRepeaterTicks));
		return Math.max(1, (int)Math.round(
			nearestGridRepeaterTicks * MINECRAFT_REPEATER_TICK_US * resolution / (double)gridTicks
		));
	}

	private static String bpmLabel(int tempoUsPerQuarter) {
		return String.format(java.util.Locale.ROOT, "%.1f BPM", 60_000_000.0 / tempoUsPerQuarter);
	}

	private static FittedNote fitPitch(int pitch, MidiRangeFit rangeFit) {
		if (pitch >= 0 && pitch < NotePitch.PITCH_COUNT) {
			return new FittedNote(pitch, false, false);
		}
		return switch (rangeFit) {
			case REJECT_OUT_OF_RANGE -> new FittedNote(0, false, true);
			case OCTAVE_WRAP -> new FittedNote(wrapPitch(pitch), false, false);
			case OCTAVE_SHIFT, CLAMP -> new FittedNote(
				Math.max(0, Math.min(NotePitch.PITCH_COUNT - 1, pitch)), true, false
			);
		};
	}

	private static int bestOctaveShift(List<NoteStart> notes) {
		int bestShift = 0;
		int bestCount = -1;
		for (int shift = -72; shift <= 72; shift += 12) {
			int count = 0;
			for (NoteStart note : notes) {
				int pitch = note.midiNote() - NOTE_BLOCK_BASE_MIDI_NOTE + shift;
				if (pitch >= 0 && pitch < NotePitch.PITCH_COUNT) {
					count++;
				}
			}
			if (count > bestCount || count == bestCount && Math.abs(shift) < Math.abs(bestShift)) {
				bestCount = count;
				bestShift = shift;
			}
		}
		return bestShift;
	}

	private static int wrapPitch(int pitch) {
		while (pitch < 0) {
			pitch += 12;
		}
		while (pitch >= NotePitch.PITCH_COUNT) {
			pitch -= 12;
		}
		return Math.max(0, Math.min(NotePitch.PITCH_COUNT - 1, pitch));
	}

	private static int gridTicks(List<Part> parts, int resolution, MidiQuantizeGrid grid) {
		return switch (grid) {
			case QUARTER -> resolution;
			case EIGHTH -> Math.max(1, resolution / 2);
			case SIXTEENTH -> Math.max(1, resolution / 4);
			case AUTO -> autoGridTicks(parts, resolution);
		};
	}

	private static int autoGridTicks(List<Part> parts, int resolution) {
		long smallestGap = Long.MAX_VALUE;
		for (Part part : parts) {
			List<Long> ticks = part.notes().stream().map(NoteStart::tick).distinct().sorted().toList();
			for (int index = 1; index < ticks.size(); index++) {
				long gap = ticks.get(index) - ticks.get(index - 1);
				if (gap > 0 && gap < smallestGap) {
					smallestGap = gap;
				}
			}
		}
		if (smallestGap <= Math.max(1, resolution * 3L / 8L)) {
			return Math.max(1, resolution / 4);
		}
		if (smallestGap <= Math.max(1, resolution * 3L / 4L)) {
			return Math.max(1, resolution / 2);
		}
		return resolution;
	}

	private static int firstTempo(javax.sound.midi.Sequence midi) {
		long bestTick = Long.MAX_VALUE;
		int tempo = DEFAULT_TEMPO_US_PER_QUARTER;
		for (Track track : midi.getTracks()) {
			for (int index = 0; index < track.size(); index++) {
				MidiEvent event = track.get(index);
				MidiMessage message = event.getMessage();
				if (message instanceof MetaMessage meta && meta.getType() == 0x51 && meta.getData().length >= 3
						&& event.getTick() < bestTick) {
					byte[] data = meta.getData();
					tempo = (data[0] & 0xFF) << 16 | (data[1] & 0xFF) << 8 | data[2] & 0xFF;
					bestTick = event.getTick();
				}
			}
		}
		return tempo;
	}

	private static String trackName(Track track) {
		for (int index = 0; index < track.size(); index++) {
			MidiMessage message = track.get(index).getMessage();
			if (message instanceof MetaMessage meta && meta.getType() == 0x03) {
				String name = new String(meta.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
				if (!name.isBlank()) {
					return name;
				}
			}
		}
		return "";
	}

	private static String trackName(int index, Part part) {
		String base = part.name().isBlank() ? "MIDI Track " + (part.trackIndex() + 1) : part.name();
		return base + " ch " + (part.channel() + 1);
	}

	private static String fileName(String path) {
		String name = Path.of(path).getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot <= 0 ? name : name.substring(0, dot);
	}

	private static String gridLabel(int gridTicks, int resolution) {
		if (gridTicks <= Math.max(1, resolution / 4)) {
			return "1/16";
		}
		if (gridTicks <= Math.max(1, resolution / 2)) {
			return "1/8";
		}
		return "1/4";
	}

	private static void addDelayTokens(List<String> tokens, int delay) {
		int remaining = delay;
		while (remaining > 0) {
			int chunk = Math.min(remaining, com.fastnoteblocks.NoteSequence.MAX_GROUPED_DELAY);
			tokens.add(chunk + "d");
			remaining -= chunk;
		}
	}

	record Result(SavedSequence sequence, String report) {
	}

	record ProjectResult(ComposerProject project, String report) {
	}

	private record PartKey(int trackIndex, int channel) {
	}

	private record NoteStart(long tick, int midiNote, int velocity) {
	}

	private record MutablePart(String name, int trackIndex, int channel, List<NoteStart> notes) {
		private MutablePart(String name, int trackIndex, int channel) {
			this(name, trackIndex, channel, new ArrayList<>());
		}
	}

	private record Part(String name, int trackIndex, int channel, List<NoteStart> notes) {
	}

	private record ConvertedPart(String sequence, int skipped, int clamped, int quiet) {
	}

	private record FittedNote(int pitch, boolean clamped, boolean skipped) {
	}

	private record PendingNote(long tick, int velocity) {
	}

	private record ExactNote(long startTick, long endTick, int midiNote, int velocity) {
	}

	private record ExactPart(String name, int trackIndex, int channel, List<ExactNote> notes) {
	}

	private record MutableExactPart(
		String name,
		int trackIndex,
		int channel,
		List<ExactNote> notes,
		Map<Integer, Deque<PendingNote>> active
	) {
		private MutableExactPart(String name, int trackIndex, int channel) {
			this(name, trackIndex, channel, new ArrayList<>(), new HashMap<>());
		}
	}
}
