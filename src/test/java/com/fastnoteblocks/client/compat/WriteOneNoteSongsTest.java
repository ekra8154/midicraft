package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
	}

	private static void write(String name, int gap, int events) throws Exception {
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		for (int index = 0; index < events; index++) {
			notes.add(new ComposerProject.NoteEvent(index + 1L,
				ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE + upAndDown(index),
				index * (long) gap * TICK, TICK, 96));
		}
		ComposerProject song = new ComposerProject(name.replace('-', ' '),
			ComposerProject.DEFAULT_PPQ, ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new ComposerProject.Layer("scale", "HARP", false, true, true, notes)),
			0, events + 1L, (events + 1L) * gap * TICK, ComposerProject.DEFAULT_SPEED_QUARTERS);
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
