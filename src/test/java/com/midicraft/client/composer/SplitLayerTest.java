package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.InstrumentRanges;
import com.midicraft.NoteSequence.Step;
import com.midicraft.NoteSequence.StepType;
import com.midicraft.client.MidicraftConfig.SequenceTrack;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Split;
import com.google.gson.Gson;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The split layer's one rule -- a note sounds every voice whose bracket covers it -- and the
 * expansion that keeps the rest of the composer ignorant of it.
 *
 * <p>The numbers here come straight off the register table: bass starts at MIDI 30, guitar 42,
 * harp 54, flute 66, bell 78, each spanning 25 semitones, so adjacent tiers overlap by exactly an
 * octave. A note at 60 sits under guitar and harp at once and must become two note blocks; a note
 * at 40 sits under bass alone and must come out as pitch value 10 on a bass block, not as an
 * out-of-range casualty of the harp window.</p>
 */
class SplitLayerTest {
	private static long nextId = 1L;

	private static NoteEvent note(int midi, long tick) {
		return new NoteEvent(nextId++, midi, tick, 120L, 100);
	}

	private static Layer splitLayer(Split split, NoteEvent... notes) {
		return new Layer("Lead", "HARP", false, true, true, List.of(notes), split);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Split", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, nextId,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	/**
	 * Where the trumpets really sound, measured in game with a pitch detector.
	 *
	 * <p>The one row of the table that was guessed rather than measured, and half of the guess
	 * was wrong. Copper does not drop a register per weathering age: copper and exposed share
	 * F#3, weathered and oxidized share F#2. Pinned here because the obvious assumption -- four
	 * ages, four registers -- is wrong in a way that only listening catches.</p>
	 */
	@Test
	void trumpetsSitTwoAgesToARegister() {
		assertEquals(54, InstrumentRanges.baseMidi("TRUMPET"), "F#3, with the harp");
		assertEquals(54, InstrumentRanges.baseMidi("TRUMPET_EXPOSED"), "F#3, a dirtier copper");
		assertEquals(42, InstrumentRanges.baseMidi("TRUMPET_WEATHERED"), "F#2, with the guitar");
		assertEquals(42, InstrumentRanges.baseMidi("TRUMPET_OXIDIZED"), "F#2, a dirtier weathered");
	}

	/** A weathered trumpet answers the guitar's bracket, not the harp's, once the table moved. */
	@Test
	void aWeatheredTrumpetJoinsTheGuitarRegister() {
		Split split = new Split(List.of(Split.Voice.fullRange("TRUMPET_WEATHERED")));
		Layer layer = splitLayer(split, note(50, 0L));

		assertFalse(layer.outOfRange(layer.notes().get(0)),
			"MIDI 50 is inside F#2-F#4 and would have been outside the harp window");
		List<Layer> voices = layer.buildVoices();
		assertEquals(1, voices.size());
		assertEquals(50 + 12, voices.get(0).notes().get(0).midiNote(),
			"the guitar register sits 12 below the harp window, so the note rides 12 up");
	}

	/** The clamp: a handle can shrink a bracket but never drag it past the instrument's register. */
	@Test
	void bracketsClampToTheRegister() {
		Split.Voice flute = new Split.Voice("FLUTE", 40, 120);

		assertEquals(66, flute.lo(), "a flute cannot reach below F#4 however far the handle goes");
		assertEquals(90, flute.hi());
		assertTrue(new Split.Voice("BASS", 54, 30).covers(42), "swapped ends still make a bracket");
	}

	private static List<String> instruments(Split split) {
		return split.voices().stream().map(Split.Voice::instrument).toList();
	}

	/** A copper layer made melodic has copper in the middle rather than the harp. */
	@Test
	void aLayerMadeMelodicKeepsItsInstrumentOnItsTier() {
		Layer copper = new Layer("Lead", "TRUMPET", false, true, true, List.of());

		Split split = Split.melodicKeeping(copper.sounding());

		assertEquals(List.of("BASS", "GUITAR", "TRUMPET", "FLUTE", "BELL"), instruments(split));
		Split.Voice trumpet = split.voices().get(2);
		assertEquals(54, trumpet.lo(), "at its full register, like any fresh bracket");
		assertEquals(78, trumpet.hi());
	}

	/** A stacked layer keeps every instrument, each on its own tier, counts and all. */
	@Test
	void aStackedLayerMadeMelodicKeepsEveryInstrument() {
		Layer stacked = new Layer("Lead", "TRUMPET", false, true, true, List.of())
			.withMix(List.of(Split.Voice.fullRange("TRUMPET"), Split.Voice.fullRange("PLING"),
				Split.Voice.fullRange("DIDGERIDOO").withCount(2)));

		Split split = Split.melodicKeeping(stacked.sounding());

		assertEquals(List.of("DIDGERIDOO", "GUITAR", "PLING", "TRUMPET", "FLUTE", "BELL"),
			instruments(split), "the bass and the harp stood aside for the layer's own");
		assertEquals(2, split.voices().get(0).count());
	}

	/** A layer with notes gets only the tiers they use; the rest stay empty. */
	@Test
	void aLayerWithNotesGetsOnlyTheTiersItUses() {
		Layer copper = new Layer("Lead", "TRUMPET", false, true, true, List.of());

		assertEquals(List.of("GUITAR", "TRUMPET"), instruments(Split.melodicFor(copper.sounding(),
			List.of(note(58, 0L), note(62, 0L)))), "the overlap keeps both tiers that sound it");
		assertEquals(List.of("BASS", "GUITAR", "TRUMPET"), instruments(Split.melodicFor(
			copper.sounding(), List.of(note(36, 0L), note(60, 0L)))),
			"flute and bell reach no note, so they are the ones left off");
		assertEquals(List.of("BASS", "BELL"), instruments(Split.melodicFor(copper.sounding(),
			List.of(note(36, 0L), note(96, 0L)))), "copper reaches neither, so it is left off");
		assertEquals(List.of("BASS", "GUITAR", "TRUMPET", "FLUTE", "BELL"),
			instruments(Split.melodicFor(copper.sounding(), List.of())),
			"an empty layer still gets every tier");
	}

	/** A drum or an effect has no tier to keep, so the melodic default is untouched. */
	@Test
	void drumsAndEffectsKeepNothingOnAMelodicSplit() {
		assertEquals(Split.melodic(), Split.melodicKeeping(
			new Layer("Kit", "SNARE", false, true, true, List.of()).sounding()));
		assertEquals(Split.melodic(), Split.melodicKeeping(Split.soundEffects().voices()));
		assertEquals(Split.melodic(), Split.melodicKeeping(
			new Layer("Lead", "HARP", false, true, true, List.of()).sounding()),
			"and a harp layer gets exactly what it always got");
	}

	/** Covered notes are in range wherever they sit; uncovered ones are out wherever they sit. */
	@Test
	void rangeIsTheBracketsNotTheHarpWindow() {
		Layer layer = splitLayer(Split.melodic(), note(40, 0L), note(60, 0L), note(110, 0L));

		assertFalse(layer.outOfRange(layer.notes().get(0)), "F#2-ish is deep in the bass bracket");
		assertFalse(layer.outOfRange(layer.notes().get(1)));
		assertTrue(layer.outOfRange(layer.notes().get(2)), "above every tier there is nobody to play it");
	}

	/** One stored note under one bracket becomes one note block at the register-shifted value. */
	@Test
	void aVoiceTransposesIntoItsOwnRegister() {
		Layer layer = splitLayer(Split.melodic(), note(40, 0L));

		List<Layer> voices = layer.buildVoices();

		assertEquals(1, voices.size(), "empty voices are dropped, not emitted as bare lanes");
		assertEquals("BASS", voices.get(0).instrument());
		assertEquals(40 + 24, voices.get(0).notes().get(0).midiNote(),
			"bass sits 24 below the harp window, so the note rides 24 up to keep its true pitch");
		long values = songOf(layer).toSteps(voices.get(0)).stream()
			.filter(step -> step.type() == StepType.NOTE).count();
		assertEquals(1L, values);
	}

	/** The overlap: a note two brackets cover is two note blocks, which is the whole feature. */
	@Test
	void anOverlappedNoteDoubles() {
		Layer layer = splitLayer(Split.melodic(), note(60, 0L));

		List<Layer> voices = layer.buildVoices();

		assertEquals(List.of("GUITAR", "HARP"),
			voices.stream().map(Layer::instrument).toList());
		assertEquals(60 + 12, voices.get(0).notes().get(0).midiNote());
		assertEquals(60, voices.get(1).notes().get(0).midiNote(),
			"the harp voice is the window itself, so its notes do not move");
	}

	/** The percussion stack: virtual registers, kick low, snare mid, hats high, no overlap. */
	@Test
	void percussionStacksLowToHigh() {
		Layer layer = splitLayer(Split.percussion(), note(42, 0L), note(60, 0L), note(90, 0L));

		List<Layer> voices = layer.buildVoices();

		assertEquals(List.of("BASEDRUM", "SNARE", "HAT"),
			voices.stream().map(Layer::instrument).toList());
		for (Layer voice : voices) {
			assertEquals(1, voice.notes().size());
			assertTrue(voice.notes().get(0).isBuildable(), "every drum lands on a real pitch value");
		}
	}

	/** The build path sees only plain layers: tracks per voice, dedupe across them. */
	@Test
	void sequenceTracksExpandAndDeduplicate() {
		Layer first = splitLayer(Split.melodic(), note(60, 0L));
		Layer second = splitLayer(Split.melodic(), note(60, 0L)).withName("Echo");
		ComposerProject song = songOf(first, second);

		List<SequenceTrack> tracks = song.toSequenceTracks(Set.of(), true);

		assertEquals(4, tracks.size(), "two voices each, the second pair emptied by the first");
		assertEquals("GUITAR", tracks.get(0).instrument());
		assertEquals("HARP", tracks.get(1).instrument());
		assertFalse(tracks.get(0).sequence().isBlank());
		assertTrue(song.buildLayers(true).stream()
			.filter(layer -> !layer.notes().isEmpty()).count() == 2L,
			"deduplication caught the doubled pair the way it catches any identical sound");
	}

	/** The verdict counts note blocks, not stored notes -- and uncovered notes stay in it. */
	@Test
	void analysisCountsThePhysicalNotes() {
		Layer layer = splitLayer(Split.melodic(), note(60, 0L), note(110, 0L));

		SongAnalysis stats = SongAnalysis.of(songOf(layer), true);

		assertEquals(2, stats.totalNotes(), "the document holds two notes, doubling or not");
		assertEquals(2, stats.buildNotes(), "the covered note is two note blocks in the machine");
		assertEquals(2, stats.peakChord());
		assertEquals(1, stats.outOfRange(), "the note no bracket covers is still a problem");
	}

	/** Convert leaves a split layer's pitches alone: true pitch is what the layer is for. */
	@Test
	void convertDoesNotFoldASplitLayer() {
		Layer layer = splitLayer(Split.melodic(), note(40, 0L));

		ComposerProject.MinecraftConversion conversion = songOf(layer)
			.convertToMinecraft(1, false, 0, false,
				ComposerProject.OctaveShifting.NOTES_ONLY, true);

		Layer converted = conversion.project().layers().get(0);
		assertNotNull(converted.split(), "the brackets survive the conversion");
		assertEquals(40, converted.notes().get(0).midiNote());
		assertEquals(0, conversion.shiftedNotes());
	}

	/** Fit into range likewise: the quick fix must not wreck the layer built to not need it. */
	@Test
	void fitIntoRangeSkipsSplitLayers() {
		Layer layer = splitLayer(Split.melodic(), note(40, 0L));

		ComposerProject fitted = songOf(layer).withAllFittedToRange(Set.of());

		assertEquals(40, fitted.layers().get(0).notes().get(0).midiNote());
	}

	/** The document survives the disk: brackets ride the same plain Gson the library uses. */
	@Test
	void splitSurvivesAJsonRoundTrip() {
		ComposerProject song = songOf(splitLayer(Split.melodic(), note(60, 0L)));

		Gson gson = new Gson();
		ComposerProject reloaded = gson.fromJson(gson.toJson(song), ComposerProject.class);

		assertNotNull(reloaded.layers().get(0).split());
		assertEquals(5, reloaded.layers().get(0).split().voices().size());
		assertEquals(2, reloaded.buildLayers(true).size());
	}

	/** An ordinary layer saved before the split existed loads as one: null means what it meant. */
	@Test
	void anOrdinaryLayerIsItsOwnOnlyVoice() {
		Layer plain = new Layer("Track 1", "BASS", false, true, true, List.of(note(60, 0L)));

		assertEquals(List.of(plain), plain.buildVoices());
		assertTrue(plain.outOfRange(note(40, 0L)), "off a split layer the harp window still rules");
	}

	/** Two same-tick rows on a split layer stay two notes, whatever instrument string it wears. */
	@Test
	void splitLayersKeepThePitchInTheCell() {
		Layer layer = splitLayer(Split.melodic(), note(60, 0L), note(64, 0L));

		assertEquals(2, layer.notes().size());
		assertTrue(layer.pitched());
	}
}
