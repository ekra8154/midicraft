package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.nbs.NbsSong;
import com.midicraft.nbs.NbsWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongUnaryOperator;

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
	/** The tempo field is an unsigned short of hundredths of a tick a second. */
	private static final int MAX_TEMPO_HUNDREDTHS = 65_535;
	/** Game ticks a second: the finest grid a build can place a note on, and the fallback here. */
	private static final double GAME_TICKS_PER_SECOND = 20.0;

	private NbsExporter() {
	}

	/** A written file and a line about what the writing cost. */
	record Result(Path path, String report) {
	}

	/** One note on its way out, in composer ticks, before the grid it is written on is known. */
	private record Pending(int group, long composerTick, int instrument, int key, int velocity) {
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
		// The speed slider is part of the song -- the build runs at it -- and NBS has only the
		// tempo, so it is folded in first. Left out, a song set to 2.00x exported at half speed.
		ComposerProject source = project.withBakedSpeed();
		List<Pending> pending = new ArrayList<>();
		List<ComposerProject.Layer> groups = new ArrayList<>();
		int outsideKeyRange = 0;
		int remappedInstruments = 0;
		int culledEffects = 0;

		// A sustaining layer is written with its strikes, as the repeated notes Note Block Studio
		// uses for a held note anyway.
		double finest = source.layers().stream().anyMatch(ComposerProject.Layer::sustains)
			? source.finestSustainStep() : 0.0;
		com.midicraft.client.composer.ChordSkips skips =
			com.midicraft.client.composer.ChordSkips.of(source, dedupeIdentical, thinning, finest);
		int layerIndex = -1;
		for (ComposerProject.Layer layer : source.layers()) {
			layerIndex++;
			// A sound effect layer has nothing to become here. NBS numbers note block instruments,
			// and a door is not one of those -- writing it as a harp would put a wrong note in the
			// file where the composition has a door, which is worse than leaving it out. Left out
			// and counted, so the report says a layer went missing rather than the file lying.
			if (!layer.pitched()) {
				culledEffects++;
				continue;
			}
			int group = groups.size();
			groups.add(layer);
			// Written the way the build places it: a split or stacked layer as its voices, each note
			// at the pitch value its voice builds it on, and a counted voice as that many copies.
			// NBS has no count, and its velocity is already the note's own loudness, so a note
			// stacked three times is three notes -- which Note Block Studio plays three times as
			// loud, the same as the machine does.
			List<ComposerProject.Layer> voiceLayers =
				source.placedForBuild(layer, finest).buildVoices();
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
						int key = note.midiNote() - NBS_LOWEST_MIDI_NOTE;
						if (key < 0 || key > NBS_HIGHEST_KEY) {
							outsideKeyRange++;
						}
						pending.add(new Pending(group, note.startTick(), instrument,
							Math.max(0, Math.min(255, key)),
							Math.max(0, Math.min(100, Math.round(note.velocity() * 100.0f / 127.0f)))));
					}
				}
			}
		}

		// The grid the file is written on: the coarsest one every note actually sits on, so the
		// notes land exactly where they were and the tempo says what the song is really doing. This
		// used to be a sixteenth note, with each tick floored onto it -- which is no grid a build
		// uses, and dragged every note of a song converted to game ticks back onto the sixteenth
		// before it. A song that came in as NBS still goes out on its own grid, since that is the
		// one its notes share.
		//
		// Only where that grid fits the tempo field. A song nobody has converted can have notes a
		// composer tick apart, which would be hundreds of NBS ticks a second; that one is written on
		// game ticks instead, rounded to the nearest, which is where a build would put them anyway.
		double composerTicksPerSecond =
			source.ppq() * 1_000_000.0 / Math.max(1, source.tempoMicrosPerQuarter());
		long grid = Math.max(0L, source.endTick());
		for (Pending note : pending) {
			grid = gcd(grid, note.composerTick());
		}
		double ticksPerSecond;
		LongUnaryOperator toNbs;
		boolean roundedToGameTicks = false;
		if (grid > 0 && composerTicksPerSecond / grid * 100.0 <= MAX_TEMPO_HUNDREDTHS) {
			long step = exactStep(grid, composerTicksPerSecond);
			ticksPerSecond = composerTicksPerSecond / step;
			toNbs = tick -> tick / step;
		} else if (grid == 0) {
			// Every note on tick nought and no length: nothing to measure a grid by, so the
			// sixteenth note, which is the grid the importer reads a file on.
			ticksPerSecond = composerTicksPerSecond / Math.max(1, source.ppq() / 4);
			toNbs = tick -> 0L;
		} else {
			ticksPerSecond = GAME_TICKS_PER_SECOND;
			toNbs = tick -> Math.round(tick * GAME_TICKS_PER_SECOND / composerTicksPerSecond);
			roundedToGameTicks = true;
		}
		int tempoHundredths = Math.max(1,
			Math.min(MAX_TEMPO_HUNDREDTHS, (int)Math.round(ticksPerSecond * 100.0)));

		// NBS holds one note per layer per tick, and a composer layer holds chords. So a layer
		// here becomes as many layers there as its thickest chord has notes: the first voice in
		// the layer proper, the rest in layers beside it. That is what Note Block Studio does
		// with a chord too, which is why a song imported from it arrives spread the same way.
		//
		// Getting this wrong does not produce a wrong note, it produces an unreadable file: two
		// notes on one tick in one layer write a layer jump of nought, and nought is the byte
		// that means "no more notes on this tick". Everything after it is read as structure and
		// the file dissolves. In-game testing found it the moment a real song went through -- Note
		// Block Studio read past the end of its buffer, and so did we. Counted on the written tick,
		// not the composer's, because two notes rounded onto one game tick are a chord there too.
		List<java.util.Map<Long, Integer>> takenAt = new ArrayList<>();
		int[] voices = new int[groups.size()];
		for (int group = 0; group < groups.size(); group++) {
			takenAt.add(new java.util.HashMap<>());
		}
		int pastTheEnd = 0;
		int spilled = 0;
		List<long[]> placed = new ArrayList<>(pending.size());
		for (Pending note : pending) {
			long tick = toNbs.applyAsLong(note.composerTick());
			if (tick > NbsWriter.maxTick()) {
				pastTheEnd++;
				continue;
			}
			int voice = takenAt.get(note.group()).merge(tick, 1, Integer::sum) - 1;
			if (voice > 0) {
				spilled++;
			}
			voices[note.group()] = Math.max(voices[note.group()], voice + 1);
			placed.add(new long[] {tick, voice});
		}
		int[] base = new int[groups.size()];
		List<NbsSong.Layer> layers = new ArrayList<>();
		for (int group = 0; group < groups.size(); group++) {
			base[group] = layers.size();
			ComposerProject.Layer layer = groups.get(group);
			for (int voice = 0; voice < Math.max(1, voices[group]); voice++) {
				layers.add(new NbsSong.Layer(layers.size(),
					voice == 0 ? layer.name() : layer.name() + " " + (voice + 1),
					0, layer.muted() ? 0 : 100, 100));
			}
		}
		List<NbsSong.Note> notes = new ArrayList<>(placed.size());
		int at = 0;
		for (Pending note : pending) {
			long tick = toNbs.applyAsLong(note.composerTick());
			if (tick > NbsWriter.maxTick()) {
				continue;
			}
			long[] where = placed.get(at++);
			notes.add(new NbsSong.Note((int)where[0], base[note.group()] + (int)where[1],
				note.instrument(), note.key(), note.velocity(), 100, 0));
		}
		long endTick = Math.min(NbsWriter.maxTick(), toNbs.applyAsLong(source.endTick()));

		// On the game-tick fallback the notes may still share a coarser grid -- a song converted for
		// one lane sits on every other game tick -- and Note Block Studio judges whether a song could
		// be built in Minecraft by its tempo alone, so divide out whatever they have in common.
		long common = endTick;
		for (NbsSong.Note note : notes) {
			common = gcd(common, note.tick());
		}
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
			(int)endTick, layers.size(), source.name(), "", "",
			"Exported from Midicraft", tempoHundredths, false, 0, 0);
		NbsWriter.write(path, new NbsSong(header, List.copyOf(notes), List.copyOf(layers), List.of()));

		StringBuilder report = new StringBuilder()
			.append("Wrote ").append(notes.size()).append(notes.size() == 1 ? " note" : " notes")
			.append(" in ").append(layers.size())
			.append(layers.size() == 1 ? " layer" : " layers")
			.append(" at ").append(String.format(Locale.ROOT, "%.2f", ticksPerSecond))
			.append(" t/s");
		if (roundedToGameTicks) {
			report.append("; timing rounded to the nearest game tick, since the song is not on a "
				+ "grid NBS can hold");
		}
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

	/**
	 * The coarsest step dividing {@code grid} whose tempo the file can hold exactly.
	 *
	 * <p>NBS keeps the tempo in hundredths of a tick a second, so a grid of 6.6667 a second is
	 * written as 6.67 -- a twentieth of a percent fast, which is most of a tenth of a second by the
	 * end of a three-minute song. A finer grid the notes also sit on often lands on a round number:
	 * a third of that one is exactly 20. Where none does, the grid itself, rounding and all.</p>
	 */
	private static long exactStep(long grid, double composerTicksPerSecond) {
		for (long step = grid; step >= 1; step--) {
			if (grid % step != 0) {
				continue;
			}
			double hundredths = composerTicksPerSecond / step * 100.0;
			if (hundredths > MAX_TEMPO_HUNDREDTHS) {
				break;
			}
			if (Math.abs(Math.round(hundredths) - hundredths) <= hundredths * 1.0e-5) {
				return step;
			}
		}
		return grid;
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
