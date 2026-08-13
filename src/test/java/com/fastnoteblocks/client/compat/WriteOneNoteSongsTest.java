package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Writes the synthetic songs the two-rail shape is developed against.
 *
 * <p>Nothing in the library is made of single notes -- the closest is lady-brown-nujabes, whose
 * largest chord is three -- so the base case has to be written. A scale walked up and down is
 * enough: every event is one note, every gap is the same, and any column the build spends beyond
 * one per note is the shape's own overhead rather than something the music asked for.</p>
 *
 * <p>Gaps of one and two redstone ticks only. A wider gap needs more repeater than one cell of the
 * rail can hold, and that is a separate thing to work out.</p>
 */
@Tag("sweep")
class WriteOneNoteSongsTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	/** Composer ticks per redstone tick at ppq 480, tempo 500000 and speed 4. Measured, not derived. */
	private static final long TICK = 96L;

	/** Semitones above {@link ComposerProject#NOTE_BLOCK_BASE_MIDI_NOTE}, two octaves of a major scale. */
	private static final int[] SCALE = {0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24};

	@Test
	void writesTheOneNoteScales() throws Exception {
		write("ultra-ones-gap2", 2, 600);
		write("ultra-ones-gap1", 1, 600);
		// The one that actually exercises the arithmetic. A rail's repeater carries t(k+1) - t(k-1),
		// so a uniform gap only ever asks it for the same number twice; mixing ones and twos walks
		// every sum from two to four, and the head, which is measured from t(k) instead, differs
		// from the columns after it.
		write("ultra-ones-mixed", 0, 600);
		writeChords("ultra-twos-mixed", 600, 2);
		// Chords of three as well, which is where a run has to swap its rails over: the floor rail
		// has no centre, so half of them land somewhere they do not fit. And some of them carry no
		// harp note at all, which the path rail cannot take either -- those are the ones a run has
		// to break for.
		writeChords("ultra-threes-mixed", 600, 3);
	}

	/** Instruments a rail note may carry. Snare is sand, which is the one that needs holding up. */
	private static final List<String> INSTRUMENTS =
		List.of("BASS", "BASEDRUM", "SNARE", "BELL", "FLUTE", "CHIME", "XYLOPHONE", "GUITAR");

	/**
	 * The same scale, with chords of one or two and instruments other than harp.
	 *
	 * <p>Only the centre of a rail column can be harp and only the path rail has one, so what this
	 * has to walk is every combination of those: a chord with a harp note and one without, on the
	 * rail that has a centre and the rail that does not.</p>
	 */
	private static void writeChords(String name, int events, int largest) throws Exception {
		java.util.Random random = new java.util.Random(20260814L);
		Map<String, List<ComposerProject.NoteEvent>> byInstrument = new LinkedHashMap<>();
		long tick = 0;
		long id = 1;
		for (int index = 0; index < events; index++) {
			int notes = 1 + random.nextInt(largest);
			// Nought to all of them on something other than harp, so a chord may be all harp, part
			// harp, or hold no harp note at all -- which is the case that has to give the centre up
			// and put every note out to the sides.
			int coloured = Math.min(notes, random.nextInt(largest + 1));
			for (int note = 0; note < notes; note++) {
				String instrument = note < coloured
					? INSTRUMENTS.get(random.nextInt(INSTRUMENTS.size())) : "HARP";
				byInstrument.computeIfAbsent(instrument, key -> new ArrayList<>())
					.add(new ComposerProject.NoteEvent(id++,
						ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE + upAndDown(index * 2 + note),
						tick * TICK, TICK, 96));
			}
			tick += 1 + random.nextInt(2);
		}
		List<ComposerProject.Layer> layers = new ArrayList<>();
		byInstrument.forEach((instrument, notes) ->
			layers.add(new ComposerProject.Layer(instrument, instrument, false, true, true, notes)));
		ComposerProject song = new ComposerProject(name.replace('-', ' '),
			ComposerProject.DEFAULT_PPQ, ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			layers, 0, id, (tick + 1) * TICK, ComposerProject.DEFAULT_SPEED_QUARTERS);
		Files.writeString(SONGS.resolve(name + ".json"), new Gson().toJson(song));
		List<SongBuilder.EventNote> built =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		Map<Integer, Integer> perTick = new java.util.TreeMap<>();
		for (SongBuilder.EventNote note : built) {
			perTick.merge(note.time(), 1, Integer::sum);
		}
		Map<String, Integer> instruments = new java.util.TreeMap<>();
		for (SongBuilder.EventNote note : built) {
			instruments.merge(String.valueOf(note.instrumentBlock()), 1, Integer::sum);
		}
		System.out.println("WROTE " + name + " notes=" + built.size()
			+ " chords=" + perTick.size()
			+ " largestChord=" + perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0)
			+ " instruments=" + instruments);
	}

	/** @param gap the gap in redstone ticks, or nought for a random mix of one and two */
	private static void write(String name, int gap, int events) throws Exception {
		java.util.Random random = new java.util.Random(20260813L);
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		long tick = 0;
		for (int index = 0; index < events; index++) {
			notes.add(new ComposerProject.NoteEvent(index + 1L,
				ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE + upAndDown(index),
				tick * TICK, TICK, 96));
			tick += gap > 0 ? gap : 1 + random.nextInt(2);
		}
		ComposerProject song = new ComposerProject(name.replace('-', ' '),
			ComposerProject.DEFAULT_PPQ, ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new ComposerProject.Layer("scale", "HARP", false, true, true, notes)),
			0, events + 1L, (tick + 1) * TICK, ComposerProject.DEFAULT_SPEED_QUARTERS);
		Files.writeString(SONGS.resolve(name + ".json"), new Gson().toJson(song));
		List<SongBuilder.EventNote> built =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		int max = built.stream()
			.collect(java.util.stream.Collectors.groupingBy(SongBuilder.EventNote::time,
				java.util.stream.Collectors.counting()))
			.values().stream().mapToInt(Long::intValue).max().orElse(0);
		List<Integer> ticks = built.stream().mapToInt(SongBuilder.EventNote::time).distinct()
			.sorted().boxed().toList();
		java.util.TreeSet<Integer> gaps = new java.util.TreeSet<>();
		for (int i = 1; i < ticks.size(); i++) {
			gaps.add(ticks.get(i) - ticks.get(i - 1));
		}
		System.out.println("WROTE " + name + " notes=" + built.size()
			+ " distinctTicks=" + ticks.size() + " largestChord=" + max + " gaps=" + gaps);
	}

	/** The scale walked up and then back down, so the melody never leaves the buildable range. */
	private static int upAndDown(int index) {
		int cycle = SCALE.length * 2 - 2;
		int step = index % cycle;
		return SCALE[step < SCALE.length ? step : cycle - step];
	}
}
