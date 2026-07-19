package com.fastnoteblocks;

import java.util.ArrayList;
import java.util.List;

public final class NoteSequence {
	private NoteSequence() {
	}

	public static List<Integer> parse(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}

		List<Integer> pitches = new ArrayList<>();
		for (String rawPart : value.split(",", -1)) {
			String part = rawPart.trim();
			if (part.isEmpty()) {
				throw new IllegalArgumentException("Empty sequence entry");
			}
			int pitch;
			try {
				pitch = Integer.parseInt(part);
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Sequence entries must be integers", exception);
			}
			if (pitch < 0 || pitch >= NotePitch.PITCH_COUNT) {
				throw new IllegalArgumentException("Sequence entries must be between 0 and 24");
			}
			pitches.add(pitch);
		}
		return List.copyOf(pitches);
	}
}
