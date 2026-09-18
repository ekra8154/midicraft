package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.nbs.NbsSong;
import com.midicraft.nbs.NbsWriter;
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
		return export(project, path, true,
			com.midicraft.client.composer.ChordSkips.Rules.at(com.midicraft.client.composer.SongAnalysis.MAX_SIMULTANEOUS_NOTES));
	}

	/**
	 * @param thinning the chord thinning settings, so the file plays what preview and the build
	 *     play: strikes and copies the chord limit leaves out are left out of it too
	 */
	static Result export(ComposerProject project, Path path, boolean dedupeIdentical,
			com.midicraft.client.composer.ChordSkips.Rules thinning)
			throws IOException {
		int scale = tickScale(project);
		List<NbsSong.Note> notes = new ArrayList<>();
		List<NbsSong.Layer> layers = new ArrayList<>();
		int outsideKeyRange = 0;
		int pastTheEnd = 0;
		int remappedInstruments = 0;
		int culledEffects = 0;

		int spilled = 0;
		// A sustaining layer is written with its strikes, as the repeated notes Note Block Studio
		// uses for a held note anyway.
		double finest = project.layers().stream().anyMatch(ComposerProject.Layer::sustains)
			? project.finestSustainStep() : 0.0;
		com.midicraft.client.composer.ChordSkips skips =
			com.midicraft.client.composer.ChordSkips.of(project, dedupeIdentical, thinning, finest);
		int layerIndex = -1;
		for (ComposerProject.Layer layer : project.layers()) {
			layerIndex++;
			// A sound effect layer has nothing to become here. NBS numbers note block instruments,
			// and a door is not one of those -- writing it as a harp would put a wrong note in the
			// file where the composition has a door, which is worse than leaving it out. Left out
			// and counted, so the report says a layer went missing rather than the file lying.
			if (!layer.pitched()) {
				culledEffects++;
				continue;
			}
			// NBS holds one note per layer per tick, and a composer layer holds chords. So a layer
			// here becomes as many layers there as its thickest chord has notes: the first voice in
			// the layer proper, the rest in layers beside it. That is what Note Block Studio does
			// with a chord too, which is why a song imported from it arrives spread the same way.
			//
			// Getting this wrong does not produce a wrong note, it produces an unreadable file: two
			// notes on one tick in one layer write a layer jump of nought, and nought is the byte
			// that means "no more notes on this tick". Everything after it is read as structure and
			// the file dissolves. In-game testing found it the moment a real song went through -- Note Block
			// Studio read past the end of its buffer, and so did we.
			int base = layers.size();
			int voices = 0;
			java.util.Map<Integer, Integer> takenAt = new java.util.HashMap<>();
			// Written the way the build places it: a split or stacked layer as its voices, each note
			// at the pitch value its voice builds it on, and a counted voice as that many copies.
			// NBS has no count, and its velocity is already the note's own loudness, so a note
			// stacked three times is three notes -- which Note Block Studio plays three times as
			// loud, the same as the machine does.
			List<ComposerProject.Layer> voiceLayers =
				project.placedForBuild(layer, finest).buildVoices();
			for (int voiceIndex = 0; voiceIndex < voiceLayers.size(); voiceIndex++) {
				ComposerProject.Layer voiceLayer = voiceLayers.get(voiceIndex);
				if (!voiceLayer.pitched()) {
					// A door stacked onto a tuned layer: left out, and counted, for the reason a
					// whole sound effect layer is.
					culledEffects++;
					continue;
				}
				int instrument = instrumentId(voiceLayer.instrument());
				if (instrument < 0) {
					// Beyond the sixteen Note Block Studio knows. Written as a piano so the file
					// opens everywhere, and counted so the report can say so.
					instrument = 0;
					remappedInstruments++;
				}
				for (int copy = 0; copy < voiceLayer.copies(); copy++) {
					for (ComposerProject.NoteEvent note : voiceLayer.notes()) {
						if (skips.skips(layerIndex, note.id(), note.startTick())
								|| copy >= skips.copiesAt(layerIndex, voiceIndex, note.id(),
									note.startTick(), voiceLayer.copies())) {
							continue;
						}
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
				}
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

		// And then written on the coarsest grid the music actually uses. The composer's resolution
		// is its own business, and carrying it through means a song whose notes are four ticks apart
		// is written as though a note could land on every one of them. That plays correctly -- the
		// tempo and the spacing cancel -- but Note Block Studio reads the tempo field alone when it
		// decides whether a song could be built in Minecraft, and a rate above ten a second is a
		// rate redstone cannot keep. Hammer, exported after baking its speed in, came out at forty a
		// second and was flagged incompatible with a game it had already been pasted into.
		//
		// So divide out whatever every note has in common. Same notes, same moments, same music, on
		// a grid that says what the song is really doing -- and for anything written to redstone in
		// the first place, that grid is ten a second or slower.
		long common = endTick;
		for (NbsSong.Note note : notes) {
			common = gcd(common, note.tick());
		}
		// Nought means every note is on tick nought, so there is no grid to find and nothing to
		// divide by. Without this the gcd of nothing is the tempo itself, and the song comes out at
		// a hundredth of a tick a second.
		int coarser = common > 0 ? (int)gcd(common, tempoHundredths) : 1;
		if (coarser > 1) {
			List<NbsSong.Note> onTheCoarserGrid = new ArrayList<>(notes.size());
			for (NbsSong.Note note : notes) {
				onTheCoarserGrid.add(new NbsSong.Note(note.tick() / coarser, note.layer(),
					note.instrument(), note.key(), note.velocity(), note.panning(),
					note.pitchCents()));
			}
			notes = onTheCoarserGrid;
			endTick /= coarser;
			tempoHundredths /= coarser;
			ticksPerSecond /= coarser;
		}

		NbsSong.Header header = new NbsSong.Header(5, NbsWriter.VANILLA_INSTRUMENT_COUNT,
			(int)endTick, layers.size(), project.name(), "", "",
			"Exported from Midicraft", tempoHundredths, false, 0, 0);
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
		if (culledEffects > 0) {
			report.append("; ").append(culledEffects)
				.append(culledEffects == 1 ? " sound effect layer" : " sound effect layers")
				.append(" left out, because NBS has no way to hold one");
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

	/** What every note's tick has in common, which is the coarsest grid they all sit on. */
	private static long gcd(long one, long two) {
		long left = Math.abs(one);
		long right = Math.abs(two);
		while (right != 0) {
			long carry = left % right;
			left = right;
			right = carry;
		}
		return left;
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
