package com.midicraft.client.compat;

import com.midicraft.client.McCompat;
import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.Split;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;

/**
 * Instruments a song can name that the running version of Minecraft cannot play, and what plays them
 * instead.
 *
 * <p>Today that is the four trumpets: copper only became an instrument in 26.1, so on anything older
 * a note block over copper plays harp. A song is the same file on every version, and a trumpet line
 * written on 26.1 has to come out as something on 1.21 rather than as a wrong-register harp or
 * nothing.</p>
 *
 * <p>Each stand-in shares its trumpet's register -- bit with the two bright coppers at F#3, guitar
 * with the two dark ones at F#2 -- so every note keeps its pitch value and only the timbre moves. Bit
 * for the bright pair because a square wave is the nearest thing a note block has to brass; guitar
 * for the dark pair because nothing else lives at F#2.</p>
 *
 * <p>Applied twice over. Opening a song in the composer rewrites it there and then and says so, so
 * what you hear and edit is what will be built. And every lookup of an instrument goes through
 * {@link #forThisVersion}, so a song that never passes through the composer -- built straight from
 * the songs list -- still builds with the stand-ins instead of with harp.</p>
 *
 * <p>On a version that has trumpets nothing is swapped and every call here is a no-op.</p>
 */
public final class InstrumentSubstitution {

	/** What stands in for each trumpet on a version without them. */
	static final Map<String, String> WITHOUT_TRUMPETS = orderedMap(
		"TRUMPET", "BIT",
		"TRUMPET_EXPOSED", "BIT",
		"TRUMPET_WEATHERED", "GUITAR",
		"TRUMPET_OXIDIZED", "GUITAR");

	private static final Map<String, String> NAMES = Map.of(
		"TRUMPET", "trumpet",
		"TRUMPET_EXPOSED", "exposed trumpet",
		"TRUMPET_WEATHERED", "weathered trumpet",
		"TRUMPET_OXIDIZED", "oxidized trumpet",
		"BIT", "bit",
		"GUITAR", "guitar");

	private static final Map<String, String> STAND_INS = McCompat.HAS_TRUMPETS ? Map.of() : WITHOUT_TRUMPETS;

	private InstrumentSubstitution() {
	}

	/** The instrument this version will actually play for {@code instrument}. */
	public static String forThisVersion(String instrument) {
		return instrument == null ? null : STAND_INS.getOrDefault(instrument, instrument);
	}

	/**
	 * A song after its stand-ins went in.
	 *
	 * @param layers how many layers had something swapped
	 * @param swaps each instrument that was swapped and what took its place, in the table's order
	 */
	public record Swapped(ComposerProject project, int layers, Map<String, String> swaps) {
		public boolean changed() {
			return layers > 0;
		}
	}

	/** {@link #swap(ComposerProject, Map)} with this version's stand-ins. */
	public static Swapped swap(ComposerProject project) {
		return swap(project, STAND_INS);
	}

	/**
	 * Rewrites every voice of every layer that names an instrument in {@code standIns}: the layer's
	 * own instrument, its split brackets, its stacked mix and the voices it is keeping aside.
	 *
	 * <p>A song with nothing to swap comes back as the same object, so a caller can tell at a glance
	 * that nothing happened.</p>
	 */
	static Swapped swap(ComposerProject project, Map<String, String> standIns) {
		if (project == null || standIns.isEmpty()) {
			return new Swapped(project, 0, Map.of());
		}
		Map<String, String> used = new LinkedHashMap<>();
		ComposerProject result = project;
		int layers = 0;
		for (int i = 0; i < project.layers().size(); i++) {
			Layer layer = project.layers().get(i);
			String instrument = swapOne(layer.instrument(), standIns, used);
			Split split = layer.split();
			if (split != null) {
				List<Split.Voice> voices = swapVoices(split.voices(), standIns, used);
				split = voices == split.voices() ? split : new Split(voices);
			}
			List<Split.Voice> mix = swapVoices(layer.mix(), standIns, used);
			List<Split.Voice> resting = swapVoices(layer.resting(), standIns, used);
			if (java.util.Objects.equals(instrument, layer.instrument()) && split == layer.split()
				&& mix == layer.mix() && resting == layer.resting()) {
				continue;
			}
			layers++;
			result = result.withLayer(i, new Layer(layer.name(), instrument, layer.muted(), layer.buildEnabled(),
				layer.visible(), layer.notes(), split, mix, resting, layer.sustain()));
		}
		if (layers == 0) {
			return new Swapped(project, 0, Map.of());
		}
		// Reported in the table's order rather than the order the song happened to name them.
		Map<String, String> ordered = new LinkedHashMap<>();
		standIns.forEach((from, to) -> {
			if (used.containsKey(from)) {
				ordered.put(from, to);
			}
		});
		return new Swapped(result, layers, ordered);
	}

	/** What the composer says after opening a song it had to swap instruments in. */
	public static Component describe(Swapped swapped) {
		String swaps = swapped.swaps().entrySet().stream()
			.map(entry -> name(entry.getKey()) + " to " + name(entry.getValue()))
			.collect(Collectors.joining(", "));
		return Component.literal("Trumpets need Minecraft 26.1 or newer, so they were swapped on "
			+ swapped.layers() + (swapped.layers() == 1 ? " layer" : " layers") + ": " + swaps
			+ ". Every note keeps its pitch. Saving keeps the swap.");
	}

	private static String name(String instrument) {
		return NAMES.getOrDefault(instrument, instrument.toLowerCase());
	}

	private static String swapOne(String instrument, Map<String, String> standIns, Map<String, String> used) {
		String standIn = instrument == null ? null : standIns.get(instrument);
		if (standIn == null) {
			return instrument;
		}
		used.put(instrument, standIn);
		return standIn;
	}

	/** The same list when nothing in it is swapped, so an untouched layer stays untouched. */
	private static List<Split.Voice> swapVoices(List<Split.Voice> voices, Map<String, String> standIns,
		Map<String, String> used) {
		if (voices == null || voices.stream().noneMatch(voice -> standIns.containsKey(voice.instrument()))) {
			return voices;
		}
		List<Split.Voice> swapped = new ArrayList<>(voices.size());
		for (Split.Voice voice : voices) {
			String instrument = swapOne(voice.instrument(), standIns, used);
			swapped.add(instrument.equals(voice.instrument()) ? voice
				: new Split.Voice(instrument, voice.lo(), voice.hi(), voice.count()));
		}
		return swapped;
	}

	private static Map<String, String> orderedMap(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return java.util.Collections.unmodifiableMap(map);
	}
}
