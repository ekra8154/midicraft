package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * A run of chords written out by hand, for reproducing a layout fault without a song around it.
 *
 * <p>Every fault in the lane layouts so far has been found in a real song and then understood by
 * cutting it down: which chord, how big, how far from the wall, how long after the one before. That
 * cutting down was done by editing a composition and pasting it, which takes minutes and changes
 * more than the one thing under test -- corridor width and floor count are sized from the whole
 * song, so a shortened song builds a different machine. This writes the chords directly instead.</p>
 *
 * <p>The notes are all one pitch on one instrument on purpose. What is being tested is where the
 * builder puts things, and a chord of eighteen that is eighteen of the same note is the same
 * problem for it as eighteen different ones -- while being very much easier to look at.</p>
 *
 * <p>Deliberately free of Minecraft, so the same spec a command builds in world can be built in a
 * test and read back.</p>
 */
final class DebugChords {
	/** Ticks between chords when the spec does not say. Four is a repeater's worth. */
	static final int DEFAULT_GAP = 4;
	/** The block under a note block that leaves it a harp, which is what an empty cell gives. */
	private static final String HARP = "minecraft:air";
	/** The one pitch every note gets. Mid-range, so it is audible and obviously uniform. */
	private static final int PITCH = 12;

	/**
	 * The four instruments worth writing, one for each way a block under a note changes the build.
	 *
	 * <p>Not a list of instruments -- a list of physics. What the builder actually asks of the block
	 * under a note block is whether it conducts, whether it falls, and whether it is there at all, and
	 * these are the four answers. Everything else in the game sounds different and builds identically,
	 * so it would only make a spec longer.</p>
	 *
	 * <pre>
	 *   p  piano/harp   air          nothing under the note at all, which is the default
	 *   h  hi-hat       glass        solid but transparent, so it will not carry power
	 *   s  snare        sand         falls, so it needs something under it to stand on
	 *   b  bell         gold block   solid, conducting, and stays where it is put
	 * </pre>
	 */
	private static final java.util.Map<Character, String> INSTRUMENTS = java.util.Map.of(
		'p', HARP,
		'h', "minecraft:glass",
		's', "minecraft:sand",
		'b', "minecraft:gold_block");

	private DebugChords() {
	}

	/**
	 * One chord: its notes, how many ticks after the one before it lands, and what is under each note.
	 *
	 * @param blocks one entry per note, in the order the spec names them, harp for the rest
	 */
	record Chord(int notes, int gap, List<String> blocks) {
		Chord {
			if (blocks.size() != notes) {
				throw new IllegalArgumentException("A chord of " + notes + " with " + blocks.size()
					+ " instruments under it.");
			}
		}

		Chord(int notes, int gap) {
			this(notes, gap, java.util.Collections.nCopies(notes, HARP));
		}
	}

	/**
	 * Reads a spec into the chords it names.
	 *
	 * <p>Terms are separated by spaces or commas, whichever is easier to type, and each one is
	 * {@code <notes>[:<instruments>][x<repeat>][@<gap>]}:</p>
	 *
	 * <pre>
	 *   6            one chord of six, at the default gap
	 *   6 2 18       three chords, sizes six, two and eighteen
	 *   30x4         four chords of thirty
	 *   18@1         a chord of eighteen one tick after the one before
	 *   5x8@4 30@1   eight chords of five four ticks apart, then a thirty one tick later
	 *   7:7b         a chord of seven, every note over gold
	 *   30:2h        a chord of thirty with two hi-hats in it and the rest harp
	 *   12:4s4b:x2   ...is not a thing; the instruments go in one run, as 12:4s4b then x2
	 * </pre>
	 *
	 * <p>A gap sticks: once a term says {@code @1}, every term after it is a tick apart until one
	 * says otherwise. Writing it on every chord of a run is the sort of thing you get wrong once and
	 * then spend ten minutes looking for.</p>
	 *
	 * @throws IllegalArgumentException with a message worth showing a player, because both callers
	 *     show it: the command prints it in chat and the test asserts on it
	 */
	static List<Chord> parse(String spec, int defaultGap) {
		if (spec == null || spec.isBlank()) {
			throw new IllegalArgumentException("Nothing to build. Try 6 2 18 for three chords.");
		}
		List<Chord> chords = new ArrayList<>();
		int carried = defaultGap;
		for (String piece : spec.trim().split("[,\\s]+")) {
			String term = piece.trim();
			if (term.isEmpty()) {
				continue;
			}
			int gap = carried;
			int at = term.indexOf('@');
			if (at >= 0) {
				gap = number(term.substring(at + 1), term, "the gap after @");
				if (gap < 1) {
					throw new IllegalArgumentException("A gap of " + gap + " in \"" + piece.trim()
						+ "\". Chords land on ticks, so the gap has to be at least one.");
				}
				carried = gap;
				term = term.substring(0, at);
			}
			int repeat = 1;
			int times = term.lastIndexOf('x');
			if (times >= 0) {
				repeat = number(term.substring(times + 1), piece.trim(), "the count after x");
				if (repeat < 1) {
					throw new IllegalArgumentException("A repeat of " + repeat + " in \""
						+ piece.trim() + "\". Use x2 or more, or leave the x off.");
				}
				term = term.substring(0, times);
			}
			String mix = "";
			int colon = term.indexOf(':');
			if (colon >= 0) {
				mix = term.substring(colon + 1);
				term = term.substring(0, colon);
			}
			int notes = number(term, piece.trim(), "the chord size");
			if (notes < 1 || notes > SongBuilder.MAX_SIMULTANEOUS_NOTES) {
				throw new IllegalArgumentException("A chord of " + notes + " in \"" + piece.trim()
					+ "\". A chord is between one note and "
					+ SongBuilder.MAX_SIMULTANEOUS_NOTES + ".");
			}
			List<String> blocks = blocks(mix, notes, piece.trim());
			for (int copy = 0; copy < repeat; copy++) {
				chords.add(new Chord(notes, gap, blocks));
			}
		}
		return List.copyOf(chords);
	}

	/**
	 * What goes under each note of a chord, read from a mix like {@code 10p3s2b}.
	 *
	 * <p>Counts and letters in pairs, and harp for whatever is left over -- so a chord of thirty
	 * written {@code 30:2h} is two hi-hats and twenty-eight harps, which is the shape worth typing
	 * when what is being tested is whether two blocks that will not carry power break a module.</p>
	 */
	private static List<String> blocks(String mix, int notes, String term) {
		List<String> blocks = new ArrayList<>();
		int index = 0;
		while (index < mix.length()) {
			int start = index;
			while (index < mix.length() && Character.isDigit(mix.charAt(index))) {
				index++;
			}
			if (start == index) {
				throw new IllegalArgumentException("Expected a count before '" + mix.charAt(index)
					+ "' in \"" + term + "\", as in 10p3s2b.");
			}
			int count = Integer.parseInt(mix.substring(start, index));
			if (index >= mix.length()) {
				throw new IllegalArgumentException("No instrument after " + count + " in \"" + term
					+ "\". The letters are p harp, h hi-hat, s snare, b bell.");
			}
			char code = Character.toLowerCase(mix.charAt(index++));
			String block = INSTRUMENTS.get(code);
			if (block == null) {
				throw new IllegalArgumentException("No instrument '" + code + "' in \"" + term
					+ "\". The letters are p harp, h hi-hat, s snare, b bell.");
			}
			if (blocks.size() + count > notes) {
				throw new IllegalArgumentException("\"" + term + "\" names more instruments than the "
					+ "chord has notes. A chord of " + notes + " cannot hold "
					+ (blocks.size() + count) + ".");
			}
			for (int copy = 0; copy < count; copy++) {
				blocks.add(block);
			}
		}
		while (blocks.size() < notes) {
			blocks.add(HARP);
		}
		return List.copyOf(blocks);
	}

	private static int number(String text, String term, String what) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException notANumber) {
			throw new IllegalArgumentException("Could not read " + what + " in \"" + term
				+ "\" as a number.");
		}
	}

	/**
	 * The chords as the builder wants them: one note per event note, on the tick the gaps add up to.
	 *
	 * <p>The first chord lands on its own gap rather than on tick nought, which is what the walk
	 * expects of a real song -- the head of a machine is a repeater with nothing behind it, and a
	 * chord on tick nought leaves nowhere to put it.</p>
	 */
	static List<SongBuilder.EventNote> notes(List<Chord> chords) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		int time = 0;
		for (Chord chord : chords) {
			time += chord.gap();
			for (int index = 0; index < chord.notes(); index++) {
				notes.add(new SongBuilder.EventNote(time, 1, index, PITCH,
					chord.blocks().get(index)));
			}
		}
		return List.copyOf(notes);
	}

	static List<SongBuilder.EventNote> notes(String spec, int defaultGap) {
		return notes(parse(spec, defaultGap));
	}

	/** What the spec came to, for the line printed back at whoever typed it. */
	static String describe(List<Chord> chords) {
		int notes = 0;
		int biggest = 0;
		for (Chord chord : chords) {
			notes += chord.notes();
			biggest = Math.max(biggest, chord.notes());
		}
		return chords.size() + (chords.size() == 1 ? " chord, " : " chords, ") + notes
			+ (notes == 1 ? " note, " : " notes, ") + "biggest " + biggest;
	}
}
