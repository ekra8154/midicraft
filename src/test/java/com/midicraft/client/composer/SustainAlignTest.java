package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * That Align sustained notes puts every strike on a tick the build can place, and only moves them.
 *
 * <p>The case it exists for is a note value the tempo does not divide: at 384 ticks a quarter and
 * 600,000 microseconds, a repeater tick is 64 song ticks and a sixteenth is 96, a tick and a half.
 * Struck exactly, every other sixteenth falls between repeater ticks, and a song written for one
 * lane needs two. At a tempo where the sixteenth is not even a whole game tick -- Ghostbusters --
 * the strikes fall off the grid altogether.</p>
 */
class SustainAlignTest {
	private static ComposerProject song(SustainLength every) {
		return song(every, true);
	}

	private static ComposerProject song(SustainLength every, boolean align) {
		// One held note a bar long, and a short one after it, all on repeater ticks.
		List<NoteEvent> notes = List.of(
			new NoteEvent(1L, 66, 0L, 1536L, 96),
			new NoteEvent(2L, 66, 1664L, 1L, 96));
		Layer layer = new Layer("Held", "HARP", false, true, true, notes)
			.withSustain(new Sustain(true, SustainLength.QUARTER, every, align));
		return new ComposerProject("align", 384, 600_000, List.of(layer), 0, 10L, 1664L, 4);
	}

	private static List<Long> strikes(ComposerProject song) {
		Layer layer = song.layers().getFirst();
		List<Long> ticks = new ArrayList<>();
		song.sustainStrikes(layer, song.finestSustainStep())
			.forEach(layer.notes().getFirst(), 0L, Long.MAX_VALUE, ticks::add);
		return ticks;
	}

	@Test
	void aSixteenthOffTheGridIsMovedOntoIt() {
		ComposerProject song = song(SustainLength.SIXTEENTH);
		ComposerProject exactly = song(SustainLength.SIXTEENTH, false);
		double span = SongAnalysis.redstoneTickSpan(song);
		assertEquals(64.0, span, 1e-9);

		List<Long> exact = strikes(exactly);
		assertTrue(exact.stream().anyMatch(tick -> tick % 64L != 0L),
			"struck exactly, some sixteenths fall between repeater ticks: " + exact);
		// A sixteenth here is three game ticks, so the strikes between repeater ticks are on the
		// odd half of the game tick: a song written for one lane now wants two.
		assertEquals(2, SongAnalysis.of(exactly, true).lanesNeeded(),
			"and they drag a one-lane song onto two");

		List<Long> aligned = strikes(song);
		assertTrue(aligned.stream().allMatch(tick -> tick % 64L == 0L),
			"aligned, every strike is on a repeater tick: " + aligned);
		assertEquals(aligned.stream().distinct().count(), aligned.size(), "none struck twice");
		assertTrue(aligned.stream().allMatch(tick -> tick > 0L && tick < 1536L),
			"and none on the note's own start or end");
		assertEquals(exact.size(), aligned.size(), "a sixteenth is coarser than a tick, so none merge");
		assertEquals(1, SongAnalysis.of(song, true).lanesNeeded(),
			"and the song builds on the one lane it was written for");
	}

	@Test
	void aRateAlreadyOnTheGridIsLeftAlone() {
		assertEquals(strikes(song(SustainLength.TWO_REPEATER_TICKS, false)),
			strikes(song(SustainLength.TWO_REPEATER_TICKS)));
	}

	/** Ghostbusters, whose 1/16 sustain layer put 196 notes off the grid. */
	@Test
	void ghostbustersBuildsWithItsSixteenths() throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs", "ghost-busters-theme.json");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(file), "not in the library");
		ComposerProject raw;
		try (java.io.Reader reader = Files.newBufferedReader(file)) {
			raw = new com.google.gson.Gson().fromJson(reader, ComposerProject.class);
		}
		ComposerProject ghost = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
			raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(), raw.speedQuarters(),
			raw.speedEighths(), raw.markers());
		SongAnalysis exact = SongAnalysis.of(withAlign(ghost, false), true);
		SongAnalysis aligned = SongAnalysis.of(withAlign(ghost, true), true);
		System.out.println("GHOST exact offGrid=" + exact.offGridNotes().size() + " crowded="
			+ exact.crowdedNotes().size() + " build=" + exact.buildNotes() + " | aligned offGrid="
			+ aligned.offGridNotes().size() + " crowded=" + aligned.crowdedNotes().size() + " build="
			+ aligned.buildNotes() + " lanes=" + aligned.lanesNeeded());
		assertEquals(0, aligned.offGridNotes().size());
		assertEquals(0, aligned.crowdedNotes().size());
	}

	/** The song with every sustaining layer's alignment set one way. */
	private static ComposerProject withAlign(ComposerProject song, boolean align) {
		for (int index = 0; index < song.layers().size(); index++) {
			Layer layer = song.layers().get(index);
			if (layer.sustain() != null) {
				song = song.withLayer(index, layer.withSustain(layer.sustain().withAlign(align)));
			}
		}
		return song;
	}

	/** A layer saved before the choice existed has no align key, and comes in aligned. */
	@Test
	void aLayerSavedWithoutTheChoiceIsAligned() {
		Sustain read = new com.google.gson.Gson().fromJson(
			"{\"on\":true,\"after\":\"QUARTER\",\"every\":\"SIXTEENTH\"}", Sustain.class);
		assertTrue(read.aligned());
		assertEquals(false, new Sustain(true, SustainLength.QUARTER, SustainLength.SIXTEENTH, false)
			.withEvery(SustainLength.EIGHTH).aligned(), "and a choice made survives the other edits");
	}
}
