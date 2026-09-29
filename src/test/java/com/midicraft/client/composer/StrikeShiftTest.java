package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A note's own strike pattern, slid along the song's grid by dragging one of its ticks.
 *
 * <p>480 ticks a quarter, so the sixteenth these layers strike every is 120 ticks, and the grid is
 * counted from tick 0 where the first note is.</p>
 */
class StrikeShiftTest {
	private static final Sustain EVERY_SIXTEENTH =
		new Sustain(true, SustainLength.QUARTER, SustainLength.SIXTEENTH, false);
	private static final double FINE = 60.0;

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Shift", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, 1000L,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static List<Long> strikes(ComposerProject song, int midi) {
		Layer layer = song.layers().getFirst();
		return song.withSustainsExpanded(layer, FINE).notes().stream()
			.filter(note -> note.midiNote() == midi && note.startTick() > 0L)
			.map(NoteEvent::startTick).toList();
	}

	/** Half a chord slid half a step strikes between the other half: alternating restrikes. */
	@Test
	void slidingHalfAChordHalfAStepAlternatesTheStrikes() {
		NoteEvent low = new NoteEvent(1, 60, 0, 480, 90);
		NoteEvent high = new NoteEvent(2, 64, 0, 480, 90);
		ComposerProject song = songOf(new Layer("Pad", "HARP", false, true, true,
			List.of(low, high)).withSustain(EVERY_SIXTEENTH));

		ComposerProject slid = song.withStrikesShifted(Set.of(high.id()), 60L, FINE);

		assertEquals(List.of(120L, 240L, 360L), strikes(slid, 60), "the low note is where it was");
		assertEquals(List.of(60L, 180L, 300L, 420L), strikes(slid, 64),
			"and the high one strikes in each gap");
	}

	/** Only the phase counts: a whole step round is back on the grid, and saves as never moved. */
	@Test
	void aWholeStepRoundIsBackOnTheGrid() {
		NoteEvent held = new NoteEvent(1, 60, 0, 480, 90);
		ComposerProject song = songOf(new Layer("Pad", "HARP", false, true, true, List.of(held))
			.withSustain(EVERY_SIXTEENTH));

		assertNull(song.withStrikesShifted(Set.of(1L), 120L, FINE).layers().getFirst().notes()
			.getFirst().strikeShift(), "a whole sixteenth is no shift at all");
		assertEquals(60L, song.withStrikesShifted(Set.of(1L), -60L, FINE).layers().getFirst().notes()
			.getFirst().strikeShiftTicks(), "and half a step back is half a step on");
	}

	/** A layer that does not sustain has no strikes to slide, so nothing is stored on it. */
	@Test
	void aLayerThatDoesNotSustainIsLeftAlone() {
		ComposerProject song = songOf(new Layer("Lead", "HARP", false, true, true,
			List.of(new NoteEvent(1, 60, 0, 480, 90))));

		assertSame(song, song.withStrikesShifted(Set.of(1L), 60L, FINE));
	}

	/** A note nobody dragged saves as it always did; one that was dragged keeps it on disk. */
	@Test
	void theShiftSurvivesTheDiskAndIsAbsentWhenUnset() {
		Gson gson = new Gson();
		NoteEvent plain = new NoteEvent(1, 60, 0, 480, 90);
		NoteEvent shifted = new NoteEvent(2, 64, 0, 480, 90).withStrikeShift(60L);

		assertFalse(gson.toJson(plain).contains("strikeShift"), "nothing new in an old-shaped note");
		assertTrue(gson.toJson(shifted).contains("strikeShift"));
		ComposerProject song = songOf(new Layer("Pad", "HARP", false, true, true,
			List.of(plain, shifted)).withSustain(EVERY_SIXTEENTH));
		ComposerProject loaded = gson.fromJson(gson.toJson(song), ComposerProject.class);
		assertEquals(60L, loaded.layers().getFirst().notes().get(1).strikeShiftTicks());
		assertNull(loaded.layers().getFirst().notes().get(0).strikeShift());
	}

	/** Moving, copying and lengthening a note keep where it strikes. */
	@Test
	void editsThatRebuildANoteCarryItsShift() {
		NoteEvent shifted = new NoteEvent(1, 60, 0, 480, 90).withStrikeShift(60L);

		assertEquals(60L, shifted.movedTo(240L, 62).strikeShiftTicks());
		assertEquals(60L, shifted.withId(9L).strikeShiftTicks());
		assertEquals(60L, shifted.withDuration(960L).strikeShiftTicks());
		ComposerProject song = songOf(new Layer("Pad", "HARP", false, true, true, List.of(shifted))
			.withSustain(EVERY_SIXTEENTH));
		assertEquals(60L, song.duplicateLayer(0).layers().get(1).notes().getFirst().strikeShiftTicks(),
			"a duplicated layer strikes where its original does");
	}
}
