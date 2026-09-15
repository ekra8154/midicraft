package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Split;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Counts on the instrument palette: an instrument sounding three times is three note blocks a note.
 *
 * <p>What these hold is the promise the count makes -- what the build places, the analysis weighs
 * and the thinner protects all agree on the copies -- and the palette's click rules, which live on
 * the layer so they can be tested without a screen.</p>
 */
class InstrumentCountTest {
	private static long nextId = 1L;

	private static NoteEvent note(int midi, long tick) {
		return new NoteEvent(nextId++, midi, tick, 120L, 100);
	}

	private static Layer plain(String instrument, NoteEvent... notes) {
		return new Layer("Lead", instrument, false, true, true, List.of(notes));
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Counts", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, nextId,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static int builtNotes(ComposerProject song) {
		return song.buildLayers(true).stream().mapToInt(layer -> layer.notes().size()).sum();
	}

	@Test
	void aCountPlacesThatManyNoteBlocks() {
		Layer layer = plain("HARP", note(60, 0L), note(62, 480L)).withCountStepped("HARP", 2);
		ComposerProject song = songOf(layer);

		assertEquals(3, layer.countOf("HARP"));
		assertEquals(3, song.buildLayers(true).size(), "one plain layer per copy");
		assertEquals(3, song.toSequenceTracks(Set.of(), true).size());
		SongAnalysis stats = SongAnalysis.of(song, true);
		assertEquals(6, stats.buildNotes());
		assertEquals(3, stats.peakChord());
		assertEquals(0, stats.duplicateNotes(), "copies of one voice are never duplicates");
	}

	@Test
	void countingBackToOneIsAnOrdinaryLayerAgain() {
		Layer layer = plain("HARP", note(60, 0L)).withCountStepped("HARP", 1).withCountStepped("HARP", -1);

		assertTrue(layer.mix().isEmpty(), "one instrument once is stored the way it always was");
		assertEquals("HARP", layer.instrument());
		assertEquals(1, layer.countOf("HARP"));
	}

	/** A counted note is never merged away, and never merges away a note on another layer. */
	@Test
	void countedNotesAreExemptFromDedupeBothWays() {
		ComposerProject plainFirst = songOf(
			plain("HARP", note(60, 0L)),
			plain("HARP", note(60, 0L)).withCountStepped("HARP", 1),
			plain("HARP", note(60, 0L)));
		assertEquals(3, builtNotes(plainFirst),
			"the first plain note and both copies; the last plain note repeats the first");
		assertEquals(1, SongAnalysis.of(plainFirst, true).duplicateNotes());

		ComposerProject countedFirst = songOf(
			plain("HARP", note(60, 0L)).withCountStepped("HARP", 1),
			plain("HARP", note(60, 0L)));
		assertEquals(3, builtNotes(countedFirst), "a counted note is not heard for the dedupe");
		assertEquals(3, SongAnalysis.of(countedFirst, true).peakChord());
	}

	@Test
	void copiesCountAgainstTheChordCap() {
		NoteEvent[] chord = new NoteEvent[16];
		for (int index = 0; index < chord.length; index++) {
			chord[index] = note(54 + index, 0L);
		}
		ComposerProject song = songOf(plain("HARP", chord).withCountStepped("HARP", 1));

		assertEquals(32, SongAnalysis.of(song, true, true, Integer.MAX_VALUE).peakChord(),
			"with nothing thinned, sixteen notes twice is thirty-two");
		SongAnalysis stats = SongAnalysis.of(song, true);
		assertEquals(30, stats.peakChord(), "at the cap two copies give way");
		assertEquals(0L, stats.overloadedTicks());
		assertEquals(2, stats.skips().blocksRemoved());
	}

	@Test
	void aStackedLayerSoundsEveryInstrumentOnEveryNote() {
		Layer layer = plain("HARP", note(60, 0L)).withCountStepped("BASS", 1).withCountStepped("BASS", 1);

		assertEquals(List.of("HARP", "BASS"),
			layer.sounding().stream().map(Split.Voice::instrument).toList());
		assertEquals(2, layer.countOf("BASS"));
		List<Layer> built = songOf(layer).buildLayers(true);
		assertEquals(1, built.stream().filter(each -> each.instrument().equals("HARP")).count());
		assertEquals(2, built.stream().filter(each -> each.instrument().equals("BASS")).count());
		for (Layer each : built) {
			assertEquals(60, each.notes().get(0).midiNote(),
				"an ordinary layer's voices all play the pitch value as written");
		}
	}

	@Test
	void withOneInstrumentAClickSwapsAndTheCountGoesWithIt() {
		Layer harp = plain("HARP").withCountStepped("HARP", 2);
		Layer flute = harp.withInstrumentPicked("FLUTE");

		assertEquals(0, flute.countOf("HARP"));
		assertEquals(3, flute.countOf("FLUTE"));
		assertEquals("FLUTE", flute.instrument());
		assertSame(flute, flute.withInstrumentPicked("FLUTE"), "clicking what is sounding changes nothing");

		Layer quietFlute = flute.withCountStepped("FLUTE", -2);
		assertEquals(3, quietFlute.withInstrumentPicked("HARP").countOf("HARP"),
			"an instrument that remembers its own count comes back at it");
	}

	@Test
	void withSeveralAClickTogglesAndTheCountIsKept() {
		Layer layer = plain("HARP").withCountStepped("BASS", 1).withCountStepped("BASS", 3);
		assertEquals(4, layer.countOf("BASS"));

		Layer off = layer.withInstrumentPicked("BASS");
		assertEquals(0, off.countOf("BASS"));
		assertEquals(4, off.restingCountOf("BASS"));
		assertTrue(off.mix().isEmpty(), "back to one harp, once");

		Layer back = off.withCountStepped("BASS", 1);
		assertEquals(4, back.countOf("BASS"), "the up arrow brings it back at the count it left with");
		assertEquals(0, back.restingCountOf("BASS"));
		assertEquals(1, back.countOf("HARP"));
	}

	@Test
	void theLastInstrumentStaysAndDownStopsAtOne() {
		Layer layer = plain("HARP");

		assertSame(layer, layer.withCountStepped("HARP", -1));
		assertSame(layer, layer.withInstrumentPicked("HARP"));
		assertSame(layer, layer.withCountStepped("FLUTE", -1), "down on a silent instrument does nothing");
	}

	@Test
	void splitVoicesTakeCountsInsideTheirBrackets() {
		Layer drums = new Layer("Drums", "HARP", false, true, true, List.of(), Split.percussion())
			.withCountStepped("BASEDRUM", 2);
		int kick = drums.split().voices().stream()
			.filter(voice -> voice.instrument().equals("BASEDRUM")).findFirst().orElseThrow().lo();
		drums = drums.withNotes(List.of(note(kick, 0L)));

		assertNotNull(drums.split());
		assertEquals(3, drums.countOf("BASEDRUM"));
		List<Layer> built = songOf(drums).buildLayers(true);
		assertEquals(3, built.size());
		assertTrue(built.stream().allMatch(each -> each.instrument().equals("BASEDRUM")));

		Layer hatOff = drums.withInstrumentPicked("HAT");
		Split.Voice hat = drums.split().voices().stream()
			.filter(voice -> voice.instrument().equals("HAT")).findFirst().orElseThrow();
		assertEquals(hat, hatOff.withInstrumentPicked("HAT").split().voices().stream()
			.filter(voice -> voice.instrument().equals("HAT")).findFirst().orElseThrow(),
			"a split voice switched off and on keeps its bracket");
	}

	@Test
	void theThinnerNeverRemovesCountedCopies() {
		NoteEvent counted = note(60, 0L);
		NoteEvent doubledHarp = note(60, 0L);
		ComposerProject song = songOf(
			plain("HARP", counted).withCountStepped("HARP", 5),
			plain("HARP", doubledHarp, note(62, 0L), note(64, 0L), note(65, 0L), note(67, 0L),
				note(69, 0L), note(71, 0L), note(72, 0L)),
			plain("BASS", note(60, 0L)));

		// Fifteen sounds against eight: the chord limit first takes the harp's copies down to one,
		// which leaves ten, and only those ten are the thinner's to weigh.
		assertEquals(1, ChordSkips.of(song, true, 8, 0.0).copiesAt(0, 0, counted.id(), 0L, 6));
		ChordThinner.Result thinned = ChordThinner.thin(song, 8, true);

		assertEquals(Set.of(doubledHarp.id()), thinned.noteIds(),
			"only the plain doubled harp can go; the counted harp's copy never does, and the bass is the only bass");
		assertEquals(1, thinned.chordsStillOver(), "nine left of fifteen is still over eight");
	}

	/** A song saved before counts reads as counts of one, and a counted one survives the disk. */
	@Test
	void countsSurviveTheDiskAndOldSongsReadAsOnce() {
		String old = """
			{"name":"Old","ppq":480,"tempoMicrosPerQuarter":500000,"layers":[
			  {"name":"Bass","instrument":"BASS","muted":false,"buildEnabled":true,"visible":true,
			   "notes":[{"id":1,"midiNote":60,"startTick":0,"durationTicks":120,"velocity":96}]},
			  {"name":"Split","instrument":"HARP","muted":false,"buildEnabled":true,"visible":true,
			   "notes":[{"id":2,"midiNote":60,"startTick":0,"durationTicks":120,"velocity":96}],
			   "split":{"voices":[{"instrument":"HARP","lo":54,"hi":78}]}}],
			 "activeLayerIndex":0,"nextNoteId":3,"endTick":1920,"speedQuarters":4}
			""";
		Gson gson = new Gson();
		ComposerProject loaded = gson.fromJson(old, ComposerProject.class);

		assertEquals(1, loaded.layers().get(0).countOf("BASS"));
		assertTrue(loaded.layers().get(0).mix().isEmpty());
		assertEquals(1, loaded.layers().get(1).countOf("HARP"));
		assertEquals(2, loaded.buildLayers(true).size());

		ComposerProject counted = songOf(plain("HARP", note(60, 0L))
			.withCountStepped("FLUTE", 1).withCountStepped("FLUTE", 2).withInstrumentPicked("HARP"));
		ComposerProject reloaded = gson.fromJson(gson.toJson(counted), ComposerProject.class);
		assertEquals(3, reloaded.layers().get(0).countOf("FLUTE"));
		assertEquals(0, reloaded.layers().get(0).countOf("HARP"));
		assertEquals(3, builtNotes(reloaded));
	}
}
