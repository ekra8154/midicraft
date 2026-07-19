package com.fastnoteblocks;

import java.util.ArrayList;
import java.util.List;

/** Utilities for Minecraft's 25 chromatic note-block pitches (F-sharp 3 through F-sharp 5). */
public final class NotePitch {
	public static final int PITCH_COUNT = 25;
	private static final String[] NAMES = {
		"F\u266f", "G", "G\u266f", "A", "A\u266f", "B", "C", "C\u266f", "D", "D\u266f", "E", "F",
		"F\u266f", "G", "G\u266f", "A", "A\u266f", "B", "C", "C\u266f", "D", "D\u266f", "E", "F", "F\u266f"
	};

	private NotePitch() {
	}

	public static String name(int pitch) {
		return NAMES[normalize(pitch)];
	}

	public static char family(int pitch) {
		return name(pitch).charAt(0);
	}

	public static int nextInFamily(int pitch, char family) {
		return targetInFamily(pitch, family, true);
	}

	public static int previousInFamily(int pitch, char family) {
		return targetInFamily(pitch, family, false);
	}

	public static int clicksForward(int fromPitch, int toPitch) {
		return Math.floorMod(normalize(toPitch) - normalize(fromPitch), PITCH_COUNT);
	}

	private static int targetInFamily(int pitch, char family, boolean forward) {
		List<Integer> candidates = pitchesFor(Character.toUpperCase(family));
		if (candidates.isEmpty()) {
			throw new IllegalArgumentException("Pitch family must be A through G: " + family);
		}

		int current = normalize(pitch);
		if (forward) {
			for (int candidate : candidates) {
				if (candidate > current) {
					return candidate;
				}
			}
			return candidates.getFirst();
		}

		for (int i = candidates.size() - 1; i >= 0; i--) {
			int candidate = candidates.get(i);
			if (candidate < current) {
				return candidate;
			}
		}
		return candidates.getLast();
	}

	private static List<Integer> pitchesFor(char family) {
		List<Integer> result = new ArrayList<>(5);
		for (int pitch = 0; pitch < PITCH_COUNT; pitch++) {
			if (family(pitch) == family) {
				result.add(pitch);
			}
		}
		return result;
	}

	private static int normalize(int pitch) {
		return Math.floorMod(pitch, PITCH_COUNT);
	}
}
