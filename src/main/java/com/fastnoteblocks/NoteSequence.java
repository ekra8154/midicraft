package com.fastnoteblocks;

import java.util.ArrayList;
import java.util.List;

public final class NoteSequence {
	public enum StepType {
		NOTE,
		REPEATER
	}

	public record Step(StepType type, int value) {
		public static Step note(int pitch) {
			return new Step(StepType.NOTE, pitch);
		}

		public static Step repeater(int delay) {
			return new Step(StepType.REPEATER, delay);
		}
	}

	private NoteSequence() {
	}

	public static List<Step> parse(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}

		List<Step> steps = new ArrayList<>();
		for (String rawPart : value.split(",", -1)) {
			String part = rawPart.trim();
			if (part.isEmpty()) {
				throw new IllegalArgumentException("Empty sequence entry");
			}
			boolean repeater = part.endsWith("d") || part.endsWith("D");
			String number = repeater ? part.substring(0, part.length() - 1).trim() : part;
			int parsed;
			try {
				parsed = Integer.parseInt(number);
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Sequence entries must be notes or repeater delays", exception);
			}
			if (repeater) {
				if (parsed < 1 || parsed > 4) {
					throw new IllegalArgumentException("Repeater delays must be between 1d and 4d");
				}
				steps.add(Step.repeater(parsed));
			} else {
				if (parsed < 0 || parsed >= NotePitch.PITCH_COUNT) {
					throw new IllegalArgumentException("Note pitches must be between 0 and 24");
				}
				steps.add(Step.note(parsed));
			}
		}
		return List.copyOf(steps);
	}
}
