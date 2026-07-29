package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.nbs.NbsReader;
import com.fastnoteblocks.nbs.NbsSong;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class NbsImporter {
	private static final int NBS_LOWEST_MIDI_NOTE = 21;
	private static final int NBS_CUSTOM_INSTRUMENT_CENTER_KEY = 45;
	private static final String TEMPO_CHANGER_NAME = "tempo changer";
	/**
	 * Composer ticks per NBS tick. NBS stores one tick per event, which is far too coarse for the
	 * piano roll's zoom and grid limits, so events are spread over a standard-resolution timeline.
	 * The tempo formula below cancels this factor out exactly, leaving playback speed unchanged.
	 */
	private static final int NBS_TICK_SCALE = ComposerProject.DEFAULT_PPQ / 4;
	private static final String[] VANILLA_INSTRUMENTS = {
		"HARP", "BASS", "BASEDRUM", "SNARE", "HAT", "GUITAR", "FLUTE", "BELL",
		"CHIME", "XYLOPHONE", "IRON_XYLOPHONE", "COW_BELL", "DIDGERIDOO", "BIT", "BANJO", "PLING",
		"TRUMPET", "TRUMPET_EXPOSED", "TRUMPET_WEATHERED", "TRUMPET_OXIDIZED"
	};

	private NbsImporter() {
	}

	static ProjectResult importProject(String path, FastNoteblocksConfig config) throws Exception {
		return importProject(path, config, null);
	}

	static ProjectResult importProject(
		String path,
		FastNoteblocksConfig config,
		Set<String> selectedInstruments
	) throws Exception {
		NbsSong song = NbsReader.read(Path.of(path));
		double baseTicksPerSecond = song.header().ticksPerSecond();
		if (!Double.isFinite(baseTicksPerSecond) || baseTicksPerSecond <= 0.0) {
			throw new IllegalArgumentException("NBS tempo must be greater than zero.");
		}

		Map<Integer, NbsSong.CustomInstrument> customById = new LinkedHashMap<>();
		for (NbsSong.CustomInstrument instrument : song.customInstruments()) {
			customById.put(instrument.id(), instrument);
		}
		TreeMap<Integer, Double> tempoEvents = new TreeMap<>();
		for (NbsSong.Note note : song.notes()) {
			NbsSong.CustomInstrument custom = customById.get(note.instrument());
			if (custom != null && TEMPO_CHANGER_NAME.equals(normalize(custom.name()))) {
				double changedTempo = Math.abs(note.pitchCents() / 15.0);
				if (changedTempo > 0.0 && Double.isFinite(changedTempo)) {
					tempoEvents.put(note.tick(), changedTempo);
				}
			}
		}
		TimingMap timing = new TimingMap(baseTicksPerSecond, tempoEvents);

		Map<String, Group> groups = new LinkedHashMap<>();
		int outsideRange = 0;
		int roundedPitch = 0;
		int customFallback = 0;
		int skippedEvents = 0;
		int skippedSilent = 0;
		int skippedQuiet = 0;
		int skippedUnselected = 0;
		int pannedNotes = 0;
		int velocityCutoff = config.midiVelocityCutoff();
		for (NbsSong.Note note : song.notes()) {
			NbsSong.CustomInstrument custom = customById.get(note.instrument());
			if (custom != null && TEMPO_CHANGER_NAME.equals(normalize(custom.name()))) {
				skippedEvents++;
				continue;
			}
			int layerVolume = layerVolume(song, note.layer());
			if (note.velocity() <= 0 || layerVolume <= 0) {
				skippedSilent++;
				continue;
			}
			int velocity = scaledVelocity(note.velocity(), layerVolume);
			if (velocity < velocityCutoff) {
				skippedQuiet++;
				continue;
			}
			InstrumentMapping mapping = mapInstrument(note.instrument(), song, custom, config);
			if (selectedInstruments != null && !selectedInstruments.contains(mapping.id())) {
				skippedUnselected++;
				continue;
			}
			if (mapping.fallback()) {
				customFallback++;
			}
			if (note.panning() != 100 || layerPanning(song, note.layer()) != 100) {
				pannedNotes++;
			}
			Group group = groups.computeIfAbsent(mapping.id(), ignored -> new Group(mapping.id()));
			String sourceName = layerName(song, note.layer());
			if (!sourceName.isBlank()) {
				group.sourceNames.add(sourceName);
			}

			double exactKey = note.key() + note.pitchCents() / 100.0;
			if (custom != null) {
				exactKey += custom.key() - NBS_CUSTOM_INSTRUMENT_CENTER_KEY;
			}
			int roundedKey = (int)Math.round(exactKey);
			if (Math.abs(exactKey - roundedKey) > 0.0001) {
				roundedPitch++;
			}
			int midiNote = Math.max(0, Math.min(127, NBS_LOWEST_MIDI_NOTE + roundedKey));
			if (midiNote < ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
					|| midiNote > ComposerProject.NOTE_BLOCK_MAX_MIDI_NOTE) {
				outsideRange++;
			}
			group.notes.add(new PendingNote(
				timing.composerTick(note.tick()),
				midiNote,
				velocity
			));
		}
		if (groups.isEmpty()) {
			throw new IllegalArgumentException("No playable notes were found in this NBS file.");
		}
		if (groups.size() > ComposerProject.MAX_LAYERS) {
			String instruments = groups.keySet().stream()
				.map(id -> PreviewInstrument.byId(id).name())
				.limit(12)
				.reduce((left, right) -> left + ", " + right)
				.orElse("");
			throw new IllegalArgumentException(
				"This song uses " + groups.size() + " instrument groups, but Composer supports "
					+ ComposerProject.MAX_LAYERS + ". Instruments: " + instruments
			);
		}

		List<Layer> layers = new ArrayList<>();
		long nextId = 1L;
		for (Group group : groups.values()) {
			group.notes.sort(Comparator.comparingLong(PendingNote::tick).thenComparingInt(PendingNote::midiNote));
			List<NoteEvent> notes = new ArrayList<>(group.notes.size());
			for (PendingNote note : group.notes) {
				notes.add(new NoteEvent(nextId++, note.midiNote(),
					note.tick() * NBS_TICK_SCALE, NBS_TICK_SCALE, note.velocity()));
			}
			String layerName = group.sourceNames.size() == 1
				? group.sourceNames.iterator().next()
				: PreviewInstrument.byId(group.instrument).name();
			layers.add(new Layer(layerName, group.instrument, false, true, true, notes));
		}

		int ppq = ComposerProject.DEFAULT_PPQ;
		int tempoMicrosPerQuarter = Math.max(1, (int)Math.round(4_000_000.0 / baseTicksPerSecond));
		String name = song.header().name().isBlank() ? fileName(path) : song.header().name();
		// NBS has always carried the song's length in its header; we simply never read it. Without
		// it a song ends on its last note, losing whatever trailing silence the author wrote --
		// which for a looping song is exactly the gap that makes the loop come round evenly.
		long endTick = song.header().songLength() > 0
			? timing.composerTick(song.header().songLength()) * NBS_TICK_SCALE
			: 0L;
		ComposerProject project = new ComposerProject(name, ppq, tempoMicrosPerQuarter, layers, 0, nextId,
			endTick, ComposerProject.DEFAULT_SPEED_QUARTERS);
		StringBuilder report = new StringBuilder()
			.append("Imported NBS v").append(song.header().version())
			.append(": ").append(song.notes().size()).append(" notes into ")
			.append(layers.size()).append(layers.size() == 1 ? " layer" : " layers")
			.append(" at ").append(String.format(Locale.ROOT, "%.2f", baseTicksPerSecond)).append(" t/s");
		if (outsideRange > 0) {
			report.append("; ").append(outsideRange).append(" outside Minecraft range");
		}
		if (roundedPitch > 0) {
			report.append("; ").append(roundedPitch).append(" detuned notes rounded");
		}
		if (customFallback > 0) {
			report.append("; ").append(customFallback).append(" custom notes used fallback instrument");
		}
		if (skippedEvents > 0) {
			report.append("; ").append(tempoEvents.size()).append(" tempo changes preserved");
		}
		if (skippedSilent > 0) {
			report.append("; ").append(skippedSilent).append(" silent notes skipped");
		}
		if (skippedQuiet > 0) {
			report.append("; ").append(skippedQuiet)
				.append(" notes below velocity ").append(velocityCutoff).append(" dropped");
		}
		if (skippedUnselected > 0) {
			report.append("; ").append(skippedUnselected).append(" notes excluded by instrument selection");
		}
		if (pannedNotes > 0) {
			report.append("; stereo panning ignored");
		}
		if (song.header().loop()) {
			report.append("; loop setting not imported");
		}
		return new ProjectResult(project, report.toString());
	}

	static Inspection inspect(String path, FastNoteblocksConfig config) throws Exception {
		NbsSong song = NbsReader.read(Path.of(path));
		Map<Integer, NbsSong.CustomInstrument> customById = new LinkedHashMap<>();
		for (NbsSong.CustomInstrument instrument : song.customInstruments()) {
			customById.put(instrument.id(), instrument);
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		int velocityCutoff = config.midiVelocityCutoff();
		for (NbsSong.Note note : song.notes()) {
			NbsSong.CustomInstrument custom = customById.get(note.instrument());
			if (custom != null && TEMPO_CHANGER_NAME.equals(normalize(custom.name()))) {
				continue;
			}
			int layerVolume = layerVolume(song, note.layer());
			if (note.velocity() <= 0 || layerVolume <= 0) {
				continue;
			}
			if (scaledVelocity(note.velocity(), layerVolume) < velocityCutoff) {
				continue;
			}
			String id = mapInstrument(note.instrument(), song, custom, config).id();
			counts.merge(id, 1, Integer::sum);
		}
		List<InstrumentOption> options = counts.entrySet().stream()
			.map(entry -> new InstrumentOption(
				entry.getKey(),
				PreviewInstrument.byId(entry.getKey()).name(),
				entry.getValue()
			))
			.sorted(Comparator.comparingInt(InstrumentOption::noteCount).reversed())
			.toList();
		return new Inspection(options);
	}

	private static InstrumentMapping mapInstrument(
		int instrumentId,
		NbsSong song,
		NbsSong.CustomInstrument custom,
		FastNoteblocksConfig config
	) {
		if (instrumentId < song.header().vanillaInstrumentCount() && instrumentId < VANILLA_INSTRUMENTS.length) {
			return new InstrumentMapping(VANILLA_INSTRUMENTS[instrumentId], false);
		}
		if (custom != null) {
			String searchable = normalize(custom.name() + " " + custom.soundFile());
			for (PreviewInstrument instrument : PreviewInstrument.VALUES) {
				String id = normalize(instrument.id());
				String name = normalize(instrument.name());
				if ((!name.isBlank() && searchable.contains(name))
						|| (!id.isBlank() && searchable.contains(id))) {
					return new InstrumentMapping(instrument.id(), false);
				}
			}
			String heuristic = heuristicInstrument(searchable);
			if (heuristic != null) {
				return new InstrumentMapping(heuristic, false);
			}
		}
		return new InstrumentMapping(PreviewInstrument.byId(config.midiDefaultInstrument()).id(), true);
	}

	private static String heuristicInstrument(String value) {
		if (value.contains("piano") || value.contains("harp")) return "HARP";
		if (value.contains("kick") || value.contains("bass drum")) return "BASEDRUM";
		if (value.contains("double bass") || value.contains("contrabass")) return "BASS";
		if (value.contains("snare")) return "SNARE";
		if (value.contains("hat") || value.contains("click")) return "HAT";
		if (value.contains("guitar")) return "GUITAR";
		if (value.contains("flute")) return "FLUTE";
		if (value.contains("chime")) return "CHIME";
		if (value.contains("bell")) return "BELL";
		if (value.contains("xylophone")) return "XYLOPHONE";
		if (value.contains("banjo")) return "BANJO";
		if (value.contains("pling")) return "PLING";
		if (value.contains("trumpet")) return "TRUMPET";
		return null;
	}

	/** Converts an NBS 0-100 velocity, scaled by its layer volume, onto the MIDI 1-127 scale. */
	private static int scaledVelocity(int noteVelocity, int layerVolume) {
		return Math.max(1, Math.min(127, (int)Math.round(noteVelocity * layerVolume / 100.0 * 1.27)));
	}

	private static String layerName(NbsSong song, int index) {
		if (index < 0 || index >= song.layers().size()) {
			return "";
		}
		String name = song.layers().get(index).name().trim();
		return name.matches("(?i)layer\\s+\\d+") ? "" : name;
	}

	private static int layerVolume(NbsSong song, int index) {
		return index >= 0 && index < song.layers().size() ? song.layers().get(index).volume() : 100;
	}

	private static int layerPanning(NbsSong song, int index) {
		return index >= 0 && index < song.layers().size() ? song.layers().get(index).panning() : 100;
	}

	private static String normalize(String value) {
		return value == null
			? ""
			: value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	private static String fileName(String path) {
		String name = Path.of(path).getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot <= 0 ? name : name.substring(0, dot);
	}

	record ProjectResult(ComposerProject project, String report) {
	}

	record Inspection(List<InstrumentOption> instruments) {
	}

	record InstrumentOption(String id, String name, int noteCount) {
	}

	private record InstrumentMapping(String id, boolean fallback) {
	}

	private record PendingNote(long tick, int midiNote, int velocity) {
	}

	private static final class Group {
		private final String instrument;
		private final Set<String> sourceNames = new LinkedHashSet<>();
		private final List<PendingNote> notes = new ArrayList<>();

		private Group(String instrument) {
			this.instrument = instrument;
		}
	}

	private static final class TimingMap {
		private final double baseTicksPerSecond;
		private final int[] eventTicks;
		private final double[] normalizedAtEvent;
		private final double[] tempoAfterEvent;

		private TimingMap(double baseTicksPerSecond, TreeMap<Integer, Double> tempoEvents) {
			this.baseTicksPerSecond = baseTicksPerSecond;
			this.eventTicks = new int[tempoEvents.size()];
			this.normalizedAtEvent = new double[tempoEvents.size()];
			this.tempoAfterEvent = new double[tempoEvents.size()];
			int index = 0;
			int previousTick = 0;
			double previousTempo = baseTicksPerSecond;
			double normalized = 0.0;
			for (Map.Entry<Integer, Double> event : tempoEvents.entrySet()) {
				normalized += (event.getKey() - previousTick) * baseTicksPerSecond / previousTempo;
				eventTicks[index] = event.getKey();
				normalizedAtEvent[index] = normalized;
				tempoAfterEvent[index] = event.getValue();
				previousTick = event.getKey();
				previousTempo = event.getValue();
				index++;
			}
		}

		private long composerTick(int sourceTick) {
			int index = java.util.Arrays.binarySearch(eventTicks, sourceTick);
			if (index < 0) {
				index = -index - 2;
			}
			if (index < 0) {
				return sourceTick;
			}
			double normalized = normalizedAtEvent[index]
				+ (sourceTick - eventTicks[index]) * baseTicksPerSecond / tempoAfterEvent[index];
			return Math.max(0L, Math.round(normalized));
		}
	}
}
