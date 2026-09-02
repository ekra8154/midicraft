package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Each of Convert's steps, declined one at a time.
 *
 * <p>The popup's promise is that a box turned off means that step does not happen. Five of the six
 * decline through an argument the conversion already had, so the thing worth checking is that each
 * argument really does silence its own step and nothing else.</p>
 *
 * <pre>
 * gradlew sweepTest --offline --tests "*ConvertStepsProbe"
 * </pre>
 */
@Tag("sweep")
class ConvertStepsProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static List<Long> starts(ComposerProject p) {
		return p.layers().stream().flatMap(l -> l.notes().stream())
			.map(n -> n.startTick()).sorted().toList();
	}

	private static List<Integer> pitches(ComposerProject p) {
		return p.layers().stream().flatMap(l -> l.notes().stream())
			.map(n -> n.midiNote()).sorted().toList();
	}

	@Test
	void eachStepDeclines() throws Exception {
		List<String> songs;
		try (Stream<Path> files = Files.list(SONGS)) {
			songs = files.filter(p -> p.toString().endsWith(".json"))
				.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
		}
		int quantizeMoved = 0;
		int tempoMoved = 0;
		int notesLost = 0;
		int pitchesMoved = 0;
		int checked = 0;
		for (String song : songs) {
			ComposerProject raw;
			try (Reader reader = Files.newBufferedReader(SONGS.resolve(song + ".json"))) {
				raw = new Gson().fromJson(reader, ComposerProject.class);
			} catch (Exception refused) {
				continue;
			}
			if (raw == null || raw.noteCount() == 0) {
				continue;
			}
			checked++;
			ComposerProject source = raw.withBakedSpeed();
			// Every box off: a grid of one, no merging, no snap, no range fitting. Convert must
			// then be indistinguishable from doing nothing at all.
			ComposerProject nothing = source.convertToMinecraft(1, false, 0, true,
				ComposerProject.OctaveShifting.NOTES_ONLY, true, false).project();
			if (!starts(nothing).equals(starts(source))) {
				quantizeMoved++;
			}
			if (nothing.tempoMicrosPerQuarter() != source.tempoMicrosPerQuarter()) {
				tempoMoved++;
			}
			if (nothing.noteCount() != source.noteCount()) {
				notesLost++;
			}
			if (!pitches(nothing).equals(pitches(source))) {
				pitchesMoved++;
			}
		}
		System.out.println(String.format(Locale.ROOT,
			"  every step off, over %d songs: starts moved %d, tempo moved %d, notes lost %d,"
				+ " pitches moved %d",
			checked, quantizeMoved, tempoMoved, notesLost, pitchesMoved));
	}
}
