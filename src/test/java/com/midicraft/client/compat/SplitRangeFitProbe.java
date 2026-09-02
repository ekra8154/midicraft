package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Range fitting, on the two layer kinds, from songs built for it rather than from the library.
 *
 * <p>Every saved song is already in range, so the library says nothing at all about this: it
 * reports nought notes shifted whatever the code does. These are five notes and a bracket set.</p>
 *
 * <pre>
 * gradlew sweepTest --offline --tests "*SplitRangeFitProbe"
 * </pre>
 */
@Tag("sweep")
class SplitRangeFitProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void outsideEveryBracket() {
		ComposerProject.Split melodic = ComposerProject.Split.melodic();
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		for (int midi = 0; midi <= 127; midi++) {
			if (melodic.covers(midi)) {
				lo = Math.min(lo, midi);
				hi = Math.max(hi, midi);
			}
		}
		System.out.println("  melodic split covers MIDI " + lo + ".." + hi);
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		int[] pitches = {lo - 24, lo - 12, (lo + hi) / 2, hi + 12, hi + 24};
		long id = 1;
		long tick = 0;
		for (int pitch : pitches) {
			notes.add(new ComposerProject.NoteEvent(id++, pitch, tick, 24L, 100));
			tick += 48;
		}
		ComposerProject.Layer layer = new ComposerProject.Layer(
			"Melodic", "HARP", false, true, true, List.copyOf(notes), melodic);
		ComposerProject project = new ComposerProject("split-range", 96, 500_000,
			List.of(layer), 0, id, tick, 4, List.of());

		System.out.println("  before:");
		for (ComposerProject.NoteEvent note : project.layers().get(0).notes()) {
			System.out.println(String.format(Locale.ROOT, "    midi %3d  outOfRange=%s",
				note.midiNote(), project.layers().get(0).outOfRange(note)));
		}
		ComposerProject.MinecraftConversion fitted = project.convertToMinecraft(
			1, false, 0, false, ComposerProject.OctaveShifting.NOTES_ONLY, true);
		System.out.println("  after: " + fitted.project().layers().size() + " layer(s), "
			+ fitted.shiftedNotes() + " shifted, +" + fitted.addedLayers() + " layers");
		int stillOut = 0;
		for (ComposerProject.Layer got : fitted.project().layers()) {
			for (ComposerProject.NoteEvent note : got.notes()) {
				if (got.outOfRange(note)) {
					stillOut++;
				}
				System.out.println(String.format(Locale.ROOT,
					"    layer %-18s split=%-5s midi %3d  outOfRange=%s",
					got.name(), got.split() != null, note.midiNote(), got.outOfRange(note)));
			}
		}
		System.out.println("  " + stillOut + " notes still outside every bracket");
	}

	@Test
	void ordinaryLayerStillFits() {
		// The harp window, and notes an octave either side of it.
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		long id = 1;
		long tick = 0;
		for (int pitch : new int[] {42, 54, 66, 78, 90}) {
			notes.add(new ComposerProject.NoteEvent(id++, pitch, tick, 24L, 100));
			tick += 48;
		}
		ComposerProject.Layer layer = new ComposerProject.Layer(
			"Harp", "HARP", false, true, true, List.copyOf(notes), null);
		ComposerProject project = new ComposerProject("plain-range", 96, 500_000,
			List.of(layer), 0, id, tick, 4, List.of());
		long outBefore = project.layers().get(0).notes().stream()
			.filter(n -> project.layers().get(0).outOfRange(n)).count();
		ComposerProject.MinecraftConversion fitted = project.convertToMinecraft(
			1, false, 0, false, ComposerProject.OctaveShifting.NOTES_ONLY, true);
		long outAfter = fitted.project().layers().stream()
			.flatMap(l -> l.notes().stream().filter(n -> l.outOfRange(n))).count();
		System.out.println("  ordinary layer: " + outBefore + " out of range -> " + outAfter
			+ "; " + fitted.project().layers().size() + " layer(s), "
			+ fitted.shiftedNotes() + " shifted");
		for (ComposerProject.Layer got : fitted.project().layers()) {
			for (ComposerProject.NoteEvent note : got.notes()) {
				System.out.println(String.format(Locale.ROOT, "    layer %-18s midi %3d",
					got.name(), note.midiNote()));
			}
		}
	}
}
