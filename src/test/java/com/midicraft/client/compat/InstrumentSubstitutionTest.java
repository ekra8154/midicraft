package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.InstrumentRanges;
import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Split;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The trumpet stand-ins a version without copper instruments swaps in.
 *
 * <p>Run against the 1.21 table on every version, so the swap is checked even where it never
 * fires.</p>
 */
class InstrumentSubstitutionTest {
	private static final Map<String, String> STAND_INS = InstrumentSubstitution.WITHOUT_TRUMPETS;

	private static Layer layer(String name, String instrument) {
		return new Layer(name, instrument, false, true, true, List.of(new NoteEvent(1L, 60, 0L, 120L, 100)), null);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Brass", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, 2L,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	/** The whole reason the stand-ins are these two: no note moves. */
	@Test
	void everyStandInSharesItsTrumpetsRegister() {
		STAND_INS.forEach((trumpet, standIn) -> assertEquals(InstrumentRanges.baseMidi(trumpet),
			InstrumentRanges.baseMidi(standIn), standIn + " does not sit where " + trumpet + " does"));
	}

	@Test
	void aSongWithoutTrumpetsComesBackUntouched() {
		ComposerProject song = songOf(layer("Lead", "HARP"), layer("Bass", "BASS"));
		InstrumentSubstitution.Swapped swapped = InstrumentSubstitution.swap(song, STAND_INS);
		assertFalse(swapped.changed());
		assertSame(song, swapped.project());
	}

	@Test
	void layerInstrumentsSwapAndTheOthersStay() {
		ComposerProject song = songOf(layer("Horns", "TRUMPET"), layer("Lead", "HARP"),
			layer("Low brass", "TRUMPET_OXIDIZED"));
		InstrumentSubstitution.Swapped swapped = InstrumentSubstitution.swap(song, STAND_INS);

		assertEquals(2, swapped.layers());
		assertEquals("BIT", swapped.project().layers().get(0).instrument());
		assertEquals(song.layers().get(1), swapped.project().layers().get(1));
		assertEquals("GUITAR", swapped.project().layers().get(2).instrument());
		assertEquals(song.layers().get(0).notes(), swapped.project().layers().get(0).notes());
		assertEquals(List.of("TRUMPET", "TRUMPET_OXIDIZED"), List.copyOf(swapped.swaps().keySet()),
			"reported in the table's order, each once");
	}

	@Test
	void splitBracketsMixAndRestingVoicesAllSwap() {
		Layer split = layer("Section", "HARP")
			.withSplit(new Split(List.of(Split.Voice.fullRange("TRUMPET_WEATHERED"), Split.Voice.fullRange("FLUTE"))));
		Layer stacked = layer("Stack", "HARP")
			.withMix(List.of(Split.Voice.fullRange("TRUMPET_EXPOSED"), Split.Voice.fullRange("HARP")))
			.withResting(List.of(Split.Voice.fullRange("TRUMPET")));
		ComposerProject song = songOf(split, stacked);
		assertEquals(2, song.layers().get(1).mix().size(), "the stack this test means to swap is really there");
		InstrumentSubstitution.Swapped swapped = InstrumentSubstitution.swap(song, STAND_INS);

		assertEquals(2, swapped.layers());
		Layer splitResult = swapped.project().layers().get(0);
		assertTrue(splitResult.split().voices().stream().anyMatch(voice -> voice.instrument().equals("GUITAR")));
		assertTrue(splitResult.split().voices().stream().noneMatch(voice -> voice.instrument().startsWith("TRUMPET")));
		Layer stackResult = swapped.project().layers().get(1);
		assertEquals(List.of("BIT", "HARP"), stackResult.mix().stream().map(Split.Voice::instrument).toList());
		assertTrue(stackResult.resting().stream().noneMatch(voice -> voice.instrument().startsWith("TRUMPET")),
			stackResult.resting().toString());
	}

	@Test
	void theNoteSaysWhatWasSwapped() {
		InstrumentSubstitution.Swapped swapped = InstrumentSubstitution.swap(
			songOf(layer("Horns", "TRUMPET"), layer("Low brass", "TRUMPET_WEATHERED")), STAND_INS);
		String note = InstrumentSubstitution.describe(swapped).getString();
		assertTrue(note.contains("2 layers"), note);
		assertTrue(note.contains("trumpet to bit, weathered trumpet to guitar"), note);
	}
}
