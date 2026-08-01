package com.fastnoteblocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.nbs.NbsReader;
import com.fastnoteblocks.nbs.NbsSong;
import com.fastnoteblocks.nbs.NbsWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What is written has to read back as itself.
 *
 * <p>The writer is the reader's inverse and nothing else, so the test that matters is the round
 * trip rather than a byte-for-byte fixture: a fixture only says the bytes match what I thought they
 * should be, where a round trip says the two halves agree.</p>
 */
class NbsWriterTest {
	private static NbsSong.Header header(int songLength, int layerCount) {
		return new NbsSong.Header(5, NbsWriter.VANILLA_INSTRUMENT_COUNT, songLength, layerCount,
			"Round Trip", "Author", "Original", "Description", 1_250, false, 0, 0);
	}

	@Test
	void readsBackEveryNoteItWrote() throws Exception {
		List<NbsSong.Note> notes = List.of(
			new NbsSong.Note(0, 0, 0, 45, 100, 100, 0),
			new NbsSong.Note(0, 1, 5, 50, 80, 100, 0),
			new NbsSong.Note(4, 0, 2, 33, 100, 100, 0),
			new NbsSong.Note(9, 2, 15, 60, 60, 100, 0));
		List<NbsSong.Layer> layers = List.of(
			new NbsSong.Layer(0, "Lead", 0, 100, 100),
			new NbsSong.Layer(1, "Bass", 0, 100, 100),
			new NbsSong.Layer(2, "Drums", 0, 0, 100));

		NbsSong read = NbsReader.read(NbsWriter.toBytes(
			new NbsSong(header(9, 3), notes, layers, List.of())));

		assertEquals(notes.size(), read.notes().size(), "a note was lost in writing");
		for (int index = 0; index < notes.size(); index++) {
			assertEquals(notes.get(index), read.notes().get(index),
				"note " + index + " did not survive the round trip");
		}
		assertEquals("Round Trip", read.header().name());
		assertEquals("Author", read.header().author());
		assertEquals(1_250, read.header().tempoHundredths());
		assertEquals(9, read.header().songLength());
		assertEquals(List.of("Lead", "Bass", "Drums"),
			read.layers().stream().map(NbsSong.Layer::name).toList());
		assertEquals(0, read.layers().get(2).volume(), "a muted layer should come back muted");
	}

	@Test
	void writesNotesOutOfOrderInTheOrderTheFormatWants() throws Exception {
		// The format is jumps, not positions, so anything unsorted would encode as gibberish.
		NbsSong read = NbsReader.read(NbsWriter.toBytes(new NbsSong(header(7, 2), List.of(
			new NbsSong.Note(7, 1, 0, 40, 100, 100, 0),
			new NbsSong.Note(0, 1, 0, 41, 100, 100, 0),
			new NbsSong.Note(3, 0, 0, 42, 100, 100, 0),
			new NbsSong.Note(0, 0, 0, 43, 100, 100, 0)),
			List.of(new NbsSong.Layer(0, "A", 0, 100, 100),
				new NbsSong.Layer(1, "B", 0, 100, 100)),
			List.of())));

		assertEquals(List.of(0, 0, 3, 7), read.notes().stream().map(NbsSong.Note::tick).toList(),
			"notes should come back in tick order");
		assertEquals(List.of(43, 41, 42, 40), read.notes().stream().map(NbsSong.Note::key).toList(),
			"and each tick's notes in layer order");
	}

	/**
	 * The failure that shipped: two notes on one layer at one tick write a layer jump of nought,
	 * and nought is the byte that ends the tick. The file does not come out wrong, it comes out
	 * unreadable -- everything past that point is read as structure. Note Block Studio reported it
	 * as reading past the end of its buffer; our own reader called it an unexpected end of file.
	 */
	@Test
	void refusesTwoNotesOnOneLayerAtOneTick() {
		NbsSong song = new NbsSong(header(0, 1), List.of(
			new NbsSong.Note(0, 0, 0, 45, 100, 100, 0),
			new NbsSong.Note(0, 0, 0, 49, 100, 100, 0)),
			List.of(new NbsSong.Layer(0, "Lead", 0, 100, 100)), List.of());

		IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
			IllegalArgumentException.class, () -> NbsWriter.toBytes(song));
		assertTrue(refused.getMessage().contains("one note per layer per tick"),
			"the refusal should say why: " + refused.getMessage());
	}

	@Test
	void writesAnEmptySongThatStillReads() throws Exception {
		NbsSong read = NbsReader.read(NbsWriter.toBytes(
			new NbsSong(header(0, 0), List.of(), List.of(), List.of())));

		assertTrue(read.notes().isEmpty(), "an empty song should read back empty");
		assertEquals("Round Trip", read.header().name());
	}
}
