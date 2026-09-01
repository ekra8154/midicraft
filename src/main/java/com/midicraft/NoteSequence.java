package com.midicraft;

import java.util.ArrayList;
import java.util.List;

public final class NoteSequence {
	public static final int MAX_GROUPED_DELAY = 64;

	public enum StepType {
		NOTE,
		REPEATER
	}

	public record Step(StepType type, int value, int delayTotal, int delayIndex, int delayCount) {
		public static Step note(int pitch) {
			return new Step(StepType.NOTE, pitch, 0, 0, 1);
		}

		public static Step repeater(int delay) {
			return new Step(StepType.REPEATER, delay, delay, 0, 1);
		}

		private static Step groupedRepeater(int delay, int total, int index, int count) {
			return new Step(StepType.REPEATER, delay, total, index, count);
		}
	}

	public record Progress(
		int position,
		int total,
		int noteBlockPosition,
		int noteBlockTotal,
		int repeaterPosition,
		int repeaterTotal
	) {
	}

	public record Token(String text, int from, int to) {
	}

	/** One step of a flattened build order, carrying the time it sounds at and the track it came from. */
	public record Placement(int time, int trackNumber, Step step) {
	}

	/** An inclusive run of placements. */
	public record Span(int first, int last) {
		public int size() {
			return last - first + 1;
		}
	}

	/** Whether two placements are notes of one chord, which is to say two notes at one tick. */
	public static boolean sameChord(Placement left, Placement right) {
		return left.step().type() == StepType.NOTE
			&& right.step().type() == StepType.NOTE
			&& left.time() == right.time();
	}

	/**
	 * The run of notes the placement at {@code index} belongs to, or that placement on its own.
	 *
	 * <p>The one place that decides what a chord is. What the overlay brackets and what a jump key
	 * covers are both read off this, so the shape you are shown and the distance you travel cannot
	 * come apart.</p>
	 */
	public static Span chordSpan(List<Placement> placements, int index) {
		if (placements.isEmpty()) {
			return new Span(0, 0);
		}
		int at = Math.max(0, Math.min(placements.size() - 1, index));
		int first = at;
		int last = at;
		while (first > 0 && sameChord(placements.get(first - 1), placements.get(first))) {
			first--;
		}
		while (last + 1 < placements.size() && sameChord(placements.get(last), placements.get(last + 1))) {
			last++;
		}
		return new Span(first, last);
	}

	/**
	 * What one press of a jump covers: a whole chord, or the whole stretch of delay between two.
	 *
	 * <p>Delay is a unit of its own rather than part of the chord either side of it, because those
	 * repeaters still have to be placed -- skipping a chord should leave you in front of them, not
	 * past them.</p>
	 */
	public static Span placementUnit(List<Placement> placements, int index) {
		if (placements.isEmpty()) {
			return new Span(0, 0);
		}
		int at = Math.max(0, Math.min(placements.size() - 1, index));
		if (placements.get(at).step().type() == StepType.NOTE) {
			return chordSpan(placements, at);
		}
		int first = at;
		int last = at;
		while (first > 0 && placements.get(first - 1).step().type() == StepType.REPEATER) {
			first--;
		}
		while (last + 1 < placements.size()
			&& placements.get(last + 1).step().type() == StepType.REPEATER) {
			last++;
		}
		return new Span(first, last);
	}

	/**
	 * Where a placement sits in the order a chord is walked.
	 *
	 * <p>A chord is built two notes to a block of bus, one hanging either side, and there are two
	 * sane ways to lay that. Alternating crosses the bus at every note, which is the order the
	 * placements are stored in. Two strips walks the whole of one side and then comes back along the
	 * other, which is what a hand does when it is carrying one stack and walking a line.</p>
	 *
	 * <p>Only the walking order changes. Which pitch belongs to which side of which block is fixed by
	 * the placement order itself and is the same either way -- so is the card you are shown.</p>
	 */
	public static int chordRank(Span chord, int index, boolean twoStrips) {
		int local = Math.max(0, Math.min(chord.size() - 1, index - chord.first()));
		if (!twoStrips) {
			return local;
		}
		int nearSide = (chord.size() + 1) / 2;
		return local % 2 == 0 ? local / 2 : nearSide + local / 2;
	}

	/** The placement standing at a given rank in that walk. The inverse of {@link #chordRank}. */
	public static int chordAt(Span chord, int rank, boolean twoStrips) {
		int at = Math.max(0, Math.min(chord.size() - 1, rank));
		if (!twoStrips) {
			return chord.first() + at;
		}
		int nearSide = (chord.size() + 1) / 2;
		return chord.first() + (at < nearSide ? at * 2 : (at - nearSide) * 2 + 1);
	}

	/**
	 * How far into its own tick a placement sits.
	 *
	 * <p>Half of what a cursor is in musical terms. The index itself means nothing across an edit --
	 * inserting one note near the start moves every index after it -- but "the third thing at tick
	 * 640" still names the same moment afterwards.</p>
	 */
	public static int momentOffset(List<Placement> placements, int index) {
		if (placements.isEmpty()) {
			return 0;
		}
		int at = Math.max(0, Math.min(placements.size() - 1, index));
		int first = at;
		while (first > 0 && placements.get(first - 1).time() == placements.get(at).time()) {
			first--;
		}
		return at - first;
	}

	/**
	 * The placement {@code offset} steps into tick {@code time}, or the first one after it.
	 *
	 * <p>Where a cursor goes when the composition under it changes. An edit can delete the moment
	 * outright, so landing on the next one that still exists is the answer rather than giving up and
	 * going back to the beginning. Times do not decrease down the list, which is what lets this
	 * binary search rather than walk thirty thousand steps.</p>
	 */
	public static int indexOfMoment(List<Placement> placements, int time, int offset) {
		if (placements.isEmpty()) {
			return 0;
		}
		int low = 0;
		int high = placements.size();
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (placements.get(mid).time() < time) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		if (low >= placements.size()) {
			return placements.size() - 1;
		}
		if (placements.get(low).time() != time) {
			return low;
		}
		int last = low;
		while (last + 1 < placements.size() && placements.get(last + 1).time() == time) {
			last++;
		}
		return Math.min(low + Math.max(0, offset), last);
	}

	/**
	 * Where a jump from {@code index} lands.
	 *
	 * <p>Backwards means the start of the unit you are standing in, and only the one before it once
	 * you are already at that start -- what the previous-track button on anything else does, and the
	 * reason restarting a chord needs no key of its own.</p>
	 */
	public static int jump(List<Placement> placements, int index, int direction) {
		if (placements.isEmpty()) {
			return 0;
		}
		int at = Math.max(0, Math.min(placements.size() - 1, index));
		Span unit = placementUnit(placements, at);
		if (direction > 0) {
			return Math.min(placements.size() - 1, unit.last() + 1);
		}
		if (at > unit.first()) {
			return unit.first();
		}
		return unit.first() > 0 ? placementUnit(placements, unit.first() - 1).first() : 0;
	}

	private NoteSequence() {
	}

	public static List<Step> parse(String value) {
		return parse(value, 4);
	}

	public static List<Step> parse(String value, int delayScaleQuarters) {
		if (value == null || value.isBlank()) {
			return List.of();
		}

		int scaleQuarters = Math.max(1, delayScaleQuarters);
		List<Step> steps = new ArrayList<>();
		for (Token token : tokens(value)) {
			String part = token.text();
			boolean repeater = part.endsWith("d") || part.endsWith("D");
			String number = repeater ? part.substring(0, part.length() - 1).trim() : part;
			int parsed;
			try {
				parsed = Integer.parseInt(number);
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Sequence entries must be notes or repeater delays", exception);
			}
			if (repeater) {
				if (parsed < 1 || parsed > MAX_GROUPED_DELAY) {
					throw new IllegalArgumentException("Repeater delays must be between 1d and 64d");
				}
				addDelaySteps(steps, Math.max(1, Math.round(parsed * scaleQuarters / 4.0F)));
			} else {
				if (parsed < 0 || parsed >= NotePitch.PITCH_COUNT) {
					throw new IllegalArgumentException("Note pitches must be between 0 and 24");
				}
				steps.add(Step.note(parsed));
			}
		}
		return List.copyOf(steps);
	}

	/**
	 * Appends the repeaters that reproduce a delay of {@code totalTicks} redstone ticks.
	 *
	 * <p>Two limits apply and they are different. A repeater tops out at 4 ticks, so a delay needs
	 * {@code ceil(n/4)} of them; the steps of one delay carry grouping metadata so the overlay can
	 * show "12d" once rather than three unexplained 4s. A single {@code Nd} token tops out at
	 * {@link #MAX_GROUPED_DELAY}, so longer delays become several groups -- chunking here as well
	 * keeps a projection's steps and its text identical rather than merely equivalent.</p>
	 */
	public static void addDelaySteps(List<Step> steps, int totalTicks) {
		int outstanding = totalTicks;
		while (outstanding > 0) {
			int group = Math.min(outstanding, MAX_GROUPED_DELAY);
			int repeaterCount = (group + 3) / 4;
			int remaining = group;
			for (int index = 0; index < repeaterCount; index++) {
				int delay = Math.min(4, remaining);
				steps.add(Step.groupedRepeater(delay, group, index, repeaterCount));
				remaining -= delay;
			}
			outstanding -= group;
		}
	}

	public static List<Token> tokens(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		List<Token> tokens = new ArrayList<>();
		if (value.indexOf(',') >= 0) {
			int tokenStart = 0;
			for (int i = 0; i <= value.length(); i++) {
				if (i != value.length() && value.charAt(i) != ',') {
					continue;
				}
				int from = tokenStart;
				int to = i;
				while (from < to && Character.isWhitespace(value.charAt(from))) {
					from++;
				}
				while (to > from && Character.isWhitespace(value.charAt(to - 1))) {
					to--;
				}
				if (from == to) {
					throw new IllegalArgumentException("Empty sequence entry");
				}
				tokens.add(new Token(value.substring(from, to), from, to));
				tokenStart = i + 1;
			}
		} else {
			int index = 0;
			while (index < value.length()) {
				while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
					index++;
				}
				if (index >= value.length()) {
					break;
				}
				int from = index;
				while (index < value.length() && !Character.isWhitespace(value.charAt(index))) {
					index++;
				}
				tokens.add(new Token(value.substring(from, index), from, index));
			}
		}
		return List.copyOf(tokens);
	}

	public static Progress progress(List<Step> steps, int currentIndex) {
		if (steps == null || steps.isEmpty()) {
			return new Progress(0, 0, 0, 0, 0, 0);
		}
		int index = Math.floorMod(currentIndex, steps.size());
		int noteTotal = 0;
		int repeaterTotal = 0;
		int notePosition = 0;
		int repeaterPosition = 0;
		for (int i = 0; i < steps.size(); i++) {
			if (steps.get(i).type() == StepType.NOTE) {
				noteTotal++;
				if (i <= index) {
					notePosition++;
				}
			} else {
				repeaterTotal++;
				if (i <= index) {
					repeaterPosition++;
				}
			}
		}
		return new Progress(index + 1, steps.size(), notePosition, noteTotal, repeaterPosition, repeaterTotal);
	}
}
