package com.midicraft.client.composer;

import com.google.gson.Gson;
import com.midicraft.InstrumentRanges;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What overloaded chords in the library are made of, and how much of them the thinner can fix.
 *
 * <p>Measurement for deciding what chord thinning should do next. For every chord over the target
 * it asks: how many sounds, how many distinct note-block pitches, how many distinct pitches actually
 * heard (a bass voice sounds two octaves under the value it is built on), how many pitch classes,
 * how many instruments -- and how far the neighbouring events are, which says whether a chord could
 * be spread onto the tick beside it.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*ChordDensityProbe"
 * </pre>
 */
@Tag("sweep")
class ChordDensityProbe {
	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private record Sound(String instrument, int built, int heard) {
	}

	/** Every sound the build places at each composer tick, as the build flattens the song. */
	private static TreeMap<Long, List<Sound>> chords(ComposerProject song) {
		TreeMap<Long, List<Sound>> byTick = new TreeMap<>();
		for (Layer voice : song.buildLayers(true)) {
			boolean pitched = voice.pitched();
			for (NoteEvent note : voice.notes()) {
				if (pitched && !note.isBuildable()) {
					continue;
				}
				int heard = pitched
					? note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
						+ InstrumentRanges.baseMidi(voice.instrument())
					: -1;
				byTick.computeIfAbsent(note.startTick(), tick -> new ArrayList<>())
					.add(new Sound(voice.instrument(), note.midiNote(), heard));
			}
		}
		return byTick;
	}

	private static ComposerProject load(Path file) throws Exception {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			return new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters(), raw.speedEighths(), raw.markers());
		}
	}

	/** {@code -Dprobe.sustain=EVERY/AFTER}: every layer sustains; empty leaves the song as written. */
	private static ComposerProject sustained(ComposerProject song) {
		String given = System.getProperty("probe.sustain", "");
		if (given.isBlank()) {
			return song;
		}
		String[] parts = given.strip().toUpperCase(Locale.ROOT).split("/");
		ComposerProject.Sustain held = new ComposerProject.Sustain(true,
			parts.length > 1 ? ComposerProject.SustainLength.valueOf(parts[1]) : ComposerProject.SustainLength.HALF,
			ComposerProject.SustainLength.valueOf(parts[0]));
		return new ComposerProject(song.name(), song.ppq(), song.tempoMicrosPerQuarter(),
			song.layers().stream().map(layer -> layer.withSustain(held)).toList(), song.activeLayerIndex(),
			song.nextNoteId(), song.endTick(), song.speedQuarters(), song.speedEighths(), song.markers());
	}

	@Test
	void measuresTheLibrarysChords() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		int songsOver30 = 0;
		int songsOver24 = 0;
		long ticksOver30 = 0;
		long ticksOver24 = 0;
		long[] stillOver = new long[2];
		long[] thinFixed = new long[2];
		// Chords the thinner left over whose written notes alone -- no strikes, no copies -- fit.
		long[] writtenFits = new long[2];
		// For chords the thinner leaves over 24: which cheaper measure would have fitted them.
		long byBuiltPitch = 0;
		long byHeardPitch = 0;
		long byPitchClassPerInstrument = 0;
		long roomBeside = 0;
		long stillOverTotal = 0;
		System.out.println("sustain: " + System.getProperty("probe.sustain", "off"));
		System.out.println();
		System.out.println(String.format(Locale.ROOT, "%-44s %5s %5s %5s | %-26s | %-26s",
			"song", "peak", ">30", ">24", "thin to 30: over/fixed/left", "thin to 24: over/fixed/left"));
		for (Path file : files) {
			ComposerProject written = load(file);
			ComposerProject song = sustained(written);
			String name = file.getFileName().toString().replace(".json", "");
			TreeMap<Long, List<Sound>> chords = chords(song);
			TreeMap<Long, List<Sound>> writtenChords = chords(written);
			int peak = chords.values().stream().mapToInt(List::size).max().orElse(0);
			long over30 = chords.values().stream().filter(chord -> chord.size() > 30).count();
			long over24 = chords.values().stream().filter(chord -> chord.size() > 24).count();
			if (over24 == 0) {
				continue;
			}
			songsOver24++;
			if (over30 > 0) {
				songsOver30++;
			}
			ticksOver30 += over30;
			ticksOver24 += over24;
			StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-44s %5d %5d %5d",
				name.length() > 44 ? name.substring(0, 44) : name, peak, over30, over24));
			double gameTick = SongAnalysis.redstoneTickSpan(song) / 2.0;
			List<Long> eventTicks = new ArrayList<>(chords.keySet());
			for (int pass = 0; pass < 2; pass++) {
				int target = pass == 0 ? 30 : 24;
				ChordThinner.Result thinned = ChordThinner.thin(song, target, true);
				TreeMap<Long, List<Sound>> after = chords(song.deleteNotes(thinned.noteIds()));
				long left = after.values().stream().filter(chord -> chord.size() > target).count();
				long before = chords.values().stream().filter(chord -> chord.size() > target).count();
				stillOver[pass] += left;
				thinFixed[pass] += before - left;
				line.append(String.format(Locale.ROOT, " | %8d / %5d / %5d   ", before, before - left, left));
				for (Map.Entry<Long, List<Sound>> chord : after.entrySet()) {
					if (chord.getValue().size() > target
							&& writtenChords.getOrDefault(chord.getKey(), List.of()).size() <= target) {
						writtenFits[pass]++;
					}
				}
				if (target != 24) {
					continue;
				}
				for (Map.Entry<Long, List<Sound>> chord : after.entrySet()) {
					if (chord.getValue().size() <= target) {
						continue;
					}
					stillOverTotal++;
					List<Sound> sounds = chord.getValue();
					Set<String> builtPitches = new HashSet<>();
					Set<Integer> heardPitches = new HashSet<>();
					Set<String> classPerInstrument = new HashSet<>();
					for (Sound sound : sounds) {
						builtPitches.add(sound.built() + "");
						heardPitches.add(sound.heard() < 0 ? -1000 - sound.instrument().hashCode() : sound.heard());
						classPerInstrument.add(sound.instrument() + " " + Math.floorMod(sound.heard(), 12));
					}
					if (builtPitches.size() <= target) {
						byBuiltPitch++;
					}
					if (heardPitches.size() <= target) {
						byHeardPitch++;
					}
					if (classPerInstrument.size() <= target) {
						byPitchClassPerInstrument++;
					}
					int at = eventTicks.indexOf(chord.getKey());
					double gapBefore = at > 0 ? (chord.getKey() - eventTicks.get(at - 1)) / gameTick : 99;
					double gapAfter = at + 1 < eventTicks.size() ? (eventTicks.get(at + 1) - chord.getKey()) / gameTick : 99;
					if (Math.max(gapBefore, gapAfter) >= 2.0 - 1.0e-6) {
						roomBeside++;
					}
				}
			}
			System.out.println(line);
		}
		System.out.println();
		System.out.println("songs with a chord over 24: " + songsOver24 + ", over 30: " + songsOver30
			+ " (of " + files.size() + ")");
		System.out.println("chords over 30: " + ticksOver30 + "   thinner fixes " + thinFixed[0]
			+ ", leaves " + stillOver[0]);
		System.out.println("chords over 24: " + ticksOver24 + "   thinner fixes " + thinFixed[1]
			+ ", leaves " + stillOver[1]);
		System.out.println("left over 30 whose written notes alone fit 30: " + writtenFits[0]
			+ "   left over 24 whose written notes alone fit 24: " + writtenFits[1]);
		System.out.println("of the " + stillOverTotal + " chords left over 24:");
		System.out.println("   fit if doublings of one built pitch value could go:    " + byBuiltPitch);
		System.out.println("   fit if doublings of one heard pitch could go:          " + byHeardPitch);
		System.out.println("   fit if one note per pitch class per instrument stayed: " + byPitchClassPerInstrument);
		System.out.println("   have a neighbouring event at least 2 game ticks away:  " + roomBeside);

		// The chord the library test says loses a pitch: faded-alan-walker, tick 138240, at 24.
		Path faded = SONGS.resolve("faded-alan-walker.json");
		if (Files.exists(faded)) {
			ComposerProject song = load(faded);
			long tick = 138240L;
			ChordThinner.Result thinned = ChordThinner.thin(song, 24, true);
			System.out.println();
			System.out.println("faded-alan-walker tick " + tick + " before thinning:");
			for (Sound sound : chords(song).getOrDefault(tick, List.of())) {
				System.out.println("   " + sound);
			}
			System.out.println("after:");
			for (Sound sound : chords(song.deleteNotes(thinned.noteIds())).getOrDefault(tick, List.of())) {
				System.out.println("   " + sound);
			}
			for (int index = 0; index < song.layers().size(); index++) {
				Layer layer = song.layers().get(index);
				boolean hit = layer.notes().stream().anyMatch(note -> note.startTick() == tick);
				if (hit) {
					System.out.println("   layer " + index + " " + layer.name() + " instrument " + layer.instrument()
						+ " split " + (layer.split() != null) + " inBuild " + layer.inBuild()
						+ " stored pitches " + layer.notes().stream().filter(note -> note.startTick() == tick)
							.map(NoteEvent::midiNote).toList()
						+ " removed " + layer.notes().stream().filter(note -> note.startTick() == tick
							&& thinned.noteIds().contains(note.id())).map(NoteEvent::midiNote).toList());
				}
			}
		}
	}
}
