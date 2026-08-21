package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
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
import java.util.List;
import java.util.Map;
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
			// Capped only by what a project can physically hold. This used to be a setting that
			// defaulted to 16, which silently dropped the sparsest tracks of any larger file --
			// and NBS import never honoured it anyway, so the two importers disagreed.
			.limit(ComposerProject.MAX_LAYERS)
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
		int softest = 127;
		int loudest = 0;
		long firstKeptTick = Long.MAX_VALUE;
		long firstDroppedTick = Long.MAX_VALUE;
		for (int index = 0; index < parts.size(); index++) {
			ExactPart part = parts.get(index);
			List<NoteEvent> notes = new ArrayList<>();
			for (ExactNote note : part.notes()) {
				softest = Math.min(softest, note.velocity());
				loudest = Math.max(loudest, note.velocity());
				if (note.velocity() < velocityCutoff) {
					quiet++;
					firstDroppedTick = Math.min(firstDroppedTick, note.startTick());
					continue;
				}
				firstKeptTick = Math.min(firstKeptTick, note.startTick());
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
			layers.add(new Layer(
				trackName(index, new Part(part.name(), part.trackIndex(), part.channel(),
					part.program(), List.of())),
				instrumentFor(part, config, defaultInstrument), false, true, true, notes));
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
		// Said out loud because it is the one import decision you cannot see by looking at the roll,
		// and because a file that sent no program changes at all is worth knowing about before you
		// go looking for why everything came in as a harp.
		long readInstruments = layers.stream()
			.filter(layer -> !layer.instrument().equals(defaultInstrument))
			.count();
		if (config.midiInstrumentSource() != FastNoteblocksConfig.MidiInstrumentSource.DEFAULT_ONLY) {
			report += "; instruments read for " + readInstruments + " of " + layers.size()
				+ (readInstruments == 0
					? " layers - this file names none of them, so they took the default" : " layers");
		}
		if (outsideRange > 0) {
			report += "; " + outsideRange + " notes kept outside Minecraft's range";
		}
		if (quiet > 0) {
			report += "; " + quiet + " notes below velocity " + velocityCutoff + " dropped (this file "
				+ "spans " + softest + "-" + loudest + ")";
			// A cutoff inside the file's own range is the case that silently removes music rather
			// than re-trigger noise, and a fade-in is the shape that gets hit worst: everything
			// before the crescendo crosses the line goes, taking the opening with it.
			if (firstDroppedTick < firstKeptTick && firstKeptTick != Long.MAX_VALUE) {
				double secondsLost = (firstKeptTick - firstDroppedTick)
					* (tempo / 1_000_000.0 / resolution);
				report += String.format(java.util.Locale.ROOT,
					"; the first %.1fs are all below the cutoff - lower Settings > MIDI Import > Velocity cutoff to keep them",
					secondsLost);
			}
		}
		if (droppedParts > 0) {
			long droppedNotes = playable.stream()
				.skip(parts.size())
				.mapToLong(part -> part.notes().size())
				.sum();
			report += "; " + droppedParts + (droppedParts == 1 ? " sparser track" : " sparser tracks")
				+ " (" + droppedNotes + " notes) left out - a composition holds "
				+ ComposerProject.MAX_LAYERS + " layers at most";
		}
		return new ProjectResult(project, report);
	}

	/**
	 * The note block one part is played on, with drums resolved to the drum it mostly is.
	 *
	 * <p>A note block instrument belongs to a whole layer, and a drum kit is a different instrument
	 * every note, so a percussion part has to pick one. It picks the one it plays most: a beat whose
	 * kicks and snares all land on the same block is a drum machine with one drum, and losing the
	 * hats off the top of a mostly-kick part still leaves something you can recognise the song by.
	 * The part having been split by channel already means the kit is usually one part, not three --
	 * this is the cost of that, and it is why percussion is worth its own pass one day.</p>
	 */
	private static String instrumentFor(ExactPart part, FastNoteblocksConfig config, String fallback) {
		String chosen = MidiInstruments.choose(config.midiInstrumentSource(), part.program(),
			part.channel(), part.name(), fallback);
		if (part.channel() != MidiInstruments.PERCUSSION_CHANNEL
				|| config.midiInstrumentSource() == FastNoteblocksConfig.MidiInstrumentSource.DEFAULT_ONLY
				|| part.notes().isEmpty()) {
			return chosen;
		}
		Map<String, Integer> counts = new HashMap<>();
		for (ExactNote note : part.notes()) {
			counts.merge(MidiInstruments.forDrumNote(note.midiNote()), 1, Integer::sum);
		}
		return counts.entrySet().stream()
			.max(Map.Entry.comparingByValue())
			.map(Map.Entry::getKey)
			.orElse(chosen);
	}

	private static List<ExactPart> collectExactParts(
		javax.sound.midi.Sequence midi,
		boolean ignorePercussion,
		int resolution
	) {
		Map<PartKey, MutableExactPart> parts = new HashMap<>();
		Map<PartKey, Integer> programs = new HashMap<>();
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
				if (ignorePercussion && channel == MidiInstruments.PERCUSSION_CHANNEL) {
					continue;
				}
				PartKey key = new PartKey(trackIndex, channel);
				// The first program a part selects, not the last. A part that switches instrument
				// part way through cannot be two note blocks, and what it opens on is what it is.
				if (message.getCommand() == ShortMessage.PROGRAM_CHANGE) {
					programs.putIfAbsent(key, message.getData1());
					continue;
				}
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
		return parts.entrySet().stream()
			.map(entry -> new ExactPart(entry.getValue().name(), entry.getValue().trackIndex(),
				entry.getValue().channel(), programs.getOrDefault(entry.getKey(), -1),
				entry.getValue().notes().stream()
					.sorted(Comparator.comparingLong(ExactNote::startTick)
						.thenComparingInt(ExactNote::midiNote))
					.toList()))
			.toList();
	}

	private static String bpmLabel(int tempoUsPerQuarter) {
		return String.format(java.util.Locale.ROOT, "%.1f BPM", 60_000_000.0 / tempoUsPerQuarter);
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

	record ProjectResult(ComposerProject project, String report) {
	}

	private record PartKey(int trackIndex, int channel) {
	}

	private record NoteStart(long tick, int midiNote, int velocity) {
	}

	/** @param program the General MIDI program this part last selected, or -1 if it never said. */
	private record Part(String name, int trackIndex, int channel, int program, List<NoteStart> notes) {
	}

	private record PendingNote(long tick, int velocity) {
	}

	private record ExactNote(long startTick, long endTick, int midiNote, int velocity) {
	}

	/** @param program the General MIDI program this part last selected, or -1 if it never said. */
	private record ExactPart(String name, int trackIndex, int channel, int program,
		List<ExactNote> notes) {
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
