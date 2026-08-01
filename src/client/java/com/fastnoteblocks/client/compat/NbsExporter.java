package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.nbs.NbsSong;
import com.fastnoteblocks.nbs.NbsWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes a composition out as a Note Block Studio file.
 *
 * <p>The inverse of {@link NbsImporter}, and written against it rather than against the format:
 * every conversion here is the one there read backwards, so a song that came in as NBS goes back
 * out as the same notes at the same moments. Where the two formats disagree the loss is counted and
 * reported rather than quietly taken, because a silent export is one you find out about in somebody
 * else's editor.</p>
 */
final class NbsExporter {
	/** NBS counts from A0; the composer counts in MIDI notes. */
	private static final int NBS_LOWEST_MIDI_NOTE = 21;
	/** What Note Block Studio itself will show: A0 to C8. Outside it the note still writes. */
	private static final int NBS_HIGHEST_KEY = 87;
	/** Composer ticks per NBS tick, at the project's own resolution. */
	private static int tickScale(ComposerProject project) {
		return Math.max(1, project.ppq() / 4);
	}

	private NbsExporter() {
	}

	/** A written file and a line about what the writing cost. */
	record Result(Path path, String report) {
	}

	static Result export(ComposerProject project, Path path) throws IOException {
		int scale = tickScale(project);
		List<NbsSong.Note> notes = new ArrayList<>();
		List<NbsSong.Layer> layers = new ArrayList<>();
		int outsideKeyRange = 0;
		int pastTheEnd = 0;
		int remappedInstruments = 0;

		int spilled = 0;
		for (ComposerProject.Layer layer : project.layers()) {
			int instrument = instrumentId(layer.instrument());
			if (instrument < 0) {
				// Beyond the sixteen Note Block Studio knows. Written as a piano so the file opens
				// everywhere, and counted so the report can say so.
				instrument = 0;
				remappedInstruments++;
			}
			// NBS holds one note per layer per tick, and a composer layer holds chords. So a layer
			// here becomes as many layers there as its thickest chord has notes: the first voice in
			// the layer proper, the rest in layers beside it. That is what Note Block Studio does
			// with a chord too, which is why a song imported from it arrives spread the same way.
			//
			// Getting this wrong does not produce a wrong note, it produces an unreadable file: two
			// notes on one tick in one layer write a layer jump of nought, and nought is the byte
			// that means "no more notes on this tick". Everything after it is read as structure and
			// the file dissolves. Ekran found it the moment a real song went through -- Note Block
			// Studio read past the end of its buffer, and so did we.
			int base = layers.size();
			int voices = 0;
			java.util.Map<Integer, Integer> takenAt = new java.util.HashMap<>();
			for (ComposerProject.NoteEvent note : layer.notes()) {
				long tick = note.startTick() / scale;
				if (tick > NbsWriter.maxTick()) {
					pastTheEnd++;
					continue;
				}
				int voice = takenAt.merge((int)tick, 1, Integer::sum) - 1;
				if (voice > 0) {
					spilled++;
				}
				voices = Math.max(voices, voice + 1);
				int key = note.midiNote() - NBS_LOWEST_MIDI_NOTE;
				if (key < 0 || key > NBS_HIGHEST_KEY) {
					outsideKeyRange++;
				}
				notes.add(new NbsSong.Note((int)tick, base + voice, instrument,
					Math.max(0, Math.min(255, key)),
					Math.max(0, Math.min(100, Math.round(note.velocity() * 100.0f / 127.0f))),
					100, 0));
			}
			for (int voice = 0; voice < Math.max(1, voices); voice++) {
				layers.add(new NbsSong.Layer(base + voice,
					voice == 0 ? layer.name() : layer.name() + " " + (voice + 1),
					0, layer.muted() ? 0 : 100, 100));
			}
		}

		// The tempo formula inverted. The importer takes 4,000,000 / ticks-per-second as the
		// microseconds in a quarter note, so the ticks per second is that division undone, and NBS
		// keeps it in hundredths.
		double ticksPerSecond = 4_000_000.0 / project.tempoMicrosPerQuarter();
		int tempoHundredths = Math.max(1, Math.min(65_535, (int)Math.round(ticksPerSecond * 100.0)));
		long endTick = Math.min(NbsWriter.maxTick(), project.endTick() / scale);

		NbsSong.Header header = new NbsSong.Header(5, NbsWriter.VANILLA_INSTRUMENT_COUNT,
			(int)endTick, layers.size(), project.name(), "", "",
			"Exported from Fast Noteblocks", tempoHundredths, false, 0, 0);
		NbsWriter.write(path, new NbsSong(header, List.copyOf(notes), List.copyOf(layers), List.of()));

		StringBuilder report = new StringBuilder()
			.append("Wrote ").append(notes.size()).append(notes.size() == 1 ? " note" : " notes")
			.append(" in ").append(layers.size())
			.append(layers.size() == 1 ? " layer" : " layers")
			.append(" at ").append(String.format(Locale.ROOT, "%.2f", ticksPerSecond))
			.append(" t/s");
		if (remappedInstruments > 0) {
			report.append("; ").append(remappedInstruments)
				.append(remappedInstruments == 1 ? " layer" : " layers")
				.append(" had an instrument NBS has no number for, written as harp");
		}
		if (outsideKeyRange > 0) {
			report.append("; ").append(outsideKeyRange)
				.append(" outside the range Note Block Studio shows");
		}
		if (pastTheEnd > 0) {
			report.append("; ").append(pastTheEnd)
				.append(" past tick ").append(NbsWriter.maxTick()).append(" and left out");
		}
		if (spilled > 0) {
			report.append("; ").append(spilled)
				.append(spilled == 1 ? " chord note" : " chord notes")
				.append(" moved to layers alongside, which is how NBS holds a chord");
		}
		return new Result(path, report.toString());
	}

	/** Where this instrument sits in Note Block Studio's numbering, or -1 if it does not. */
	private static int instrumentId(String instrument) {
		for (int index = 0; index < NbsWriter.VANILLA_INSTRUMENT_COUNT
				&& index < NbsImporter.VANILLA_INSTRUMENTS.length; index++) {
			if (NbsImporter.VANILLA_INSTRUMENTS[index].equalsIgnoreCase(instrument)) {
				return index;
			}
		}
		return -1;
	}
}
