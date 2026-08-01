package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.nbs.NbsReader;
import com.fastnoteblocks.nbs.NbsSong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The conversions, checked against the importer's rather than against the format.
 *
 * <p>Every number here is one {@link NbsImporter} reads in the other direction, so the test that
 * means anything is that the two agree: a key the importer would turn back into this MIDI note, a
 * tempo it would turn back into these microseconds, an instrument it would name the same way.</p>
 */
class NbsExporterTest {
	private static ComposerProject project(List<ComposerProject.Layer> layers) {
		return new ComposerProject("Export Me", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, layers, 0, 1L, 0L,
			ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static ComposerProject.NoteEvent note(long id, int midiNote, long startTick) {
		return new ComposerProject.NoteEvent(id, midiNote, startTick,
			ComposerProject.DEFAULT_NOTE_DURATION_TICKS, 100);
	}

	@Test
	void writesKeysTheImporterWouldReadBackAsTheSameNotes(@TempDir Path folder) throws Exception {
		// The importer does midiNote = 21 + key, so the exporter owes key = midiNote - 21.
		ComposerProject source = project(List.of(new ComposerProject.Layer("Lead", "HARP",
			false, true, true, List.of(note(1, 54, 0), note(2, 66, 480), note(3, 78, 960)))));

		NbsExporter.Result result = NbsExporter.export(source, folder.resolve("out.nbs"));
		NbsSong read = NbsReader.read(result.path());

		assertEquals(List.of(54 - 21, 66 - 21, 78 - 21),
			read.notes().stream().map(NbsSong.Note::key).toList(),
			"keys should be MIDI notes less NBS's lowest");
		assertTrue(Files.size(result.path()) > 0, "the file should not be empty");
	}

	@Test
	void putsNotesOnTheTicksTheImporterWouldPutThemBack(@TempDir Path folder) throws Exception {
		// One NBS tick per ppq/4 composer ticks, which at the default ppq is 120.
		ComposerProject source = project(List.of(new ComposerProject.Layer("Lead", "HARP",
			false, true, true, List.of(note(1, 60, 0), note(2, 60, 120), note(3, 60, 1200)))));

		NbsSong read = NbsReader.read(NbsExporter.export(source, folder.resolve("t.nbs")).path());

		assertEquals(List.of(0, 1, 10), read.notes().stream().map(NbsSong.Note::tick).toList());
	}

	@Test
	void numbersInstrumentsTheWayTheImporterNamesThem(@TempDir Path folder) throws Exception {
		ComposerProject source = project(List.of(
			new ComposerProject.Layer("A", "HARP", false, true, true, List.of(note(1, 60, 0))),
			new ComposerProject.Layer("B", "PLING", false, true, true, List.of(note(2, 60, 0))),
			new ComposerProject.Layer("C", "DIDGERIDOO", false, true, true, List.of(note(3, 60, 0)))));

		NbsSong read = NbsReader.read(NbsExporter.export(source, folder.resolve("i.nbs")).path());

		assertEquals(List.of(0, 15, 12),
			read.notes().stream().map(NbsSong.Note::instrument).toList(),
			"harp, pling and didgeridoo are 0, 15 and 12 in NBS's numbering");
	}

	@Test
	void writesAnInstrumentNbsHasNoNumberForAsHarpAndSaysSo(@TempDir Path folder) throws Exception {
		ComposerProject source = project(List.of(new ComposerProject.Layer("Horn",
			"TRUMPET_OXIDIZED", false, true, true, List.of(note(1, 60, 0)))));

		NbsExporter.Result result = NbsExporter.export(source, folder.resolve("x.nbs"));
		NbsSong read = NbsReader.read(result.path());

		assertEquals(0, read.notes().get(0).instrument(), "it should fall back to harp");
		assertTrue(result.report().contains("written as harp"),
			"and the report should own up to it: " + result.report());
	}

	@Test
	void keepsTheTempoTheImporterWouldGiveBack(@TempDir Path folder) throws Exception {
		// importer: micros = 4,000,000 / ticksPerSecond. At the default 500,000 that is 8 t/s.
		ComposerProject source = project(List.of(new ComposerProject.Layer("Lead", "HARP",
			false, true, true, List.of(note(1, 60, 0)))));

		NbsSong read = NbsReader.read(NbsExporter.export(source, folder.resolve("s.nbs")).path());

		assertEquals(800, read.header().tempoHundredths(), "eight ticks per second, in hundredths");
		assertEquals(4_000_000.0 / ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			read.header().ticksPerSecond(), 0.001);
	}

	/**
	 * A chord inside one composer layer, which is the shape that broke the first version.
	 *
	 * <p>NBS holds one note per layer per tick, so a layer here becomes as many layers there as its
	 * thickest chord has notes. Getting it wrong did not write a wrong note -- it wrote a file
	 * neither Note Block Studio nor our own reader could get through.</p>
	 */
	@Test
	void spreadsAChordAcrossLayersAlongside(@TempDir Path folder) throws Exception {
		ComposerProject source = project(List.of(new ComposerProject.Layer("Lead", "HARP",
			false, true, true, List.of(note(1, 60, 0), note(2, 64, 0), note(3, 67, 0),
				note(4, 72, 480)))));

		NbsExporter.Result result = NbsExporter.export(source, folder.resolve("chord.nbs"));
		NbsSong read = NbsReader.read(result.path());

		assertEquals(4, read.notes().size(), "every note of the chord should survive");
		assertEquals(List.of(0, 0, 0, 4), read.notes().stream().map(NbsSong.Note::tick).toList());
		assertEquals(List.of(0, 1, 2, 0), read.notes().stream().map(NbsSong.Note::layer).toList(),
			"the chord spreads sideways and the later note goes back to the first layer");
		assertEquals(List.of(39, 43, 46, 51),
			read.notes().stream().map(NbsSong.Note::key).toList());
		assertEquals(3, read.layers().size(), "three voices means three layers");
		assertTrue(result.report().contains("chord notes moved"),
			"and the report should say so: " + result.report());
	}

	@Test
	void marksAMutedLayerMuted(@TempDir Path folder) throws Exception {
		ComposerProject source = project(List.of(
			new ComposerProject.Layer("Quiet", "HARP", true, true, true, List.of(note(1, 60, 0))),
			new ComposerProject.Layer("Loud", "HARP", false, true, true, List.of(note(2, 60, 0)))));

		NbsSong read = NbsReader.read(NbsExporter.export(source, folder.resolve("m.nbs")).path());

		assertEquals(0, read.layers().get(0).volume());
		assertEquals(100, read.layers().get(1).volume());
	}
}
