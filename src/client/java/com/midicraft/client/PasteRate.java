package com.midicraft.client;

import java.util.List;

/**
 * How fast a paste sends, and the rungs a button or a slider will stop at.
 *
 * <p>Its own class rather than four more constants on the config, for one reason worth having: the
 * config cannot be loaded outside a running game -- it reaches {@code FabricLoader} for the folder
 * it lives in before it has run a line -- and a rate ladder is arithmetic that ought to be
 * checkable without launching anything. Everything here is pure.</p>
 */
public final class PasteRate {

	/**
	 * The rates a press or a slider will stop at, a thousand-fold range in fifteen steps.
	 *
	 * <p>Geometric rather than even, because the interesting difference in a rate is always a
	 * ratio: eight more a tick is nothing at 128 and is the whole setting at 1. The three rungs
	 * below one are what a server needs -- a command every second, third or fourth tick -- and on
	 * one of those the difference between a paste that finishes slowly and a paste that ends in a
	 * disconnect for command spam.</p>
	 */
	public static final List<Double> RATES = List.of(
		0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 24.0, 32.0, 48.0, 64.0, 96.0, 128.0, 192.0, 256.0);

	public static final double MIN = RATES.get(0);
	public static final double MAX = RATES.get(RATES.size() - 1);

	/** Singleplayer tolerates far more than the original fixed rate of 2. */
	public static final double DEFAULT = 32;

	private PasteRate() {
	}

	public static double clamp(double rate) {
		return Math.max(MIN, Math.min(MAX, rate));
	}

	/**
	 * The next rung past a rate, in the direction asked for.
	 *
	 * <p>Strictly past, so a rate sitting between two rungs moves to the nearer one instead of
	 * snapping onto it and going nowhere -- twenty a tick goes up to twenty-four and down to
	 * sixteen, and neither press is silently nothing. Which matters because a rate set before this
	 * ladder existed is kept exactly as it was: only stepping and the slider snap.</p>
	 */
	public static double step(double from, int direction) {
		if (direction > 0) {
			for (double rate : RATES) {
				if (rate > from) {
					return rate;
				}
			}
			return MAX;
		}
		for (int i = RATES.size() - 1; i >= 0; i--) {
			if (RATES.get(i) < from) {
				return RATES.get(i);
			}
		}
		return MIN;
	}

	/** Which rung a rate is nearest, for a slider that can only be at one of them. */
	public static int index(double rate) {
		int nearest = 0;
		for (int i = 1; i < RATES.size(); i++) {
			if (Math.abs(RATES.get(i) - rate) < Math.abs(RATES.get(nearest) - rate)) {
				nearest = i;
			}
		}
		return nearest;
	}

	/**
	 * A rate in the words that describe it, which are not the same words at both ends.
	 *
	 * <p>Below one a tick, "0.25 commands per tick" is a number you have to do arithmetic on before
	 * it means anything, and that arithmetic is the part you actually care about: how long the wait
	 * is between one block and the next.</p>
	 */
	public static String label(double rate) {
		if (rate < 1) {
			return "one command every " + Math.round(1 / rate) + " ticks";
		}
		String count = rate == Math.rint(rate) ? String.valueOf((long) rate) : String.valueOf(rate);
		return count + (rate == 1 ? " command per tick" : " commands per tick");
	}
}
