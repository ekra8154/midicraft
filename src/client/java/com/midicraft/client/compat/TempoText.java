package com.midicraft.client.compat;

import java.util.Locale;

/**
 * How a tempo change is said, in one place: as the speed it plays at, "tempo x0.88", with the BPM
 * beside it where there is room.
 *
 * <p>Convert used to say "slowed 1.13x", the snap popup "-11.8%" and the step that applied it
 * "tempo x0.88" -- three numbers for one change, two of them going the other way. The player's
 * pick, as the clearest: a multiplier on the speed, under one for slower, the way a playback
 * speed control reads.</p>
 */
final class TempoText {
	private TempoText() {
	}

	/** "tempo x0.88": how fast the song plays after, relative to before. */
	static String factor(int beforeMicros, int afterMicros) {
		return String.format(Locale.ROOT, "tempo x%.2f", beforeMicros / (double)Math.max(1, afterMicros));
	}

	/** "133.3 BPM", to a tenth and no trailing ".0". */
	static String bpm(int micros) {
		String text = String.format(Locale.ROOT, "%.1f", 60_000_000.0 / Math.max(1, micros));
		return (text.endsWith(".0") ? text.substring(0, text.length() - 2) : text) + " BPM";
	}

	/** "tempo x0.88 (150 -> 133.3 BPM)". */
	static String move(int beforeMicros, int afterMicros) {
		String to = bpm(afterMicros);
		return factor(beforeMicros, afterMicros) + " (" + bpm(beforeMicros).replace(" BPM", "")
			+ " -> " + to + ")";
	}
}
