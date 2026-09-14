package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import com.midicraft.nbs.NbsReader;
import com.midicraft.nbs.NbsSong;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A sustained note leaves for Note Block Studio as the repeated notes it already uses for one. */
class SustainNbsExportTest {
	@Test
	void writesEveryStrikeAsANote(@TempDir Path folder) throws Exception {
		// A sixteenth is 120 composer ticks, which is one NBS tick, so a quarter held on sixteenths
		// is four notes on four ticks.
		ComposerProject.Layer pad = new ComposerProject.Layer("Pad", "HARP", false, true, true,
			List.of(new ComposerProject.NoteEvent(1, 60, 0, 480, 100)))
			.withSustain(new Sustain(true, SustainLength.QUARTER, SustainLength.SIXTEENTH));
		ComposerProject song = new ComposerProject("Held", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(pad), 0, 2L, 0L,
			ComposerProject.DEFAULT_SPEED_QUARTERS);

		NbsSong read = NbsReader.read(NbsExporter.export(song, folder.resolve("held.nbs")).path());

		assertEquals(List.of(0, 1, 2, 3), read.notes().stream().map(NbsSong.Note::tick).toList());
	}
}
