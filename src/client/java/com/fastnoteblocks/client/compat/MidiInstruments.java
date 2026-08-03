package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig.MidiInstrumentSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which note block a MIDI part should be played on.
 *
 * <p>An import used to give every track the one instrument set in the options, which threw away
 * something the file was carrying: a General MIDI file says what each part is, and a file that does
 * not say usually has it written in the track name instead. A drum track arriving as a harp is not a
 * quantisation decision or a range decision, it is information that was on disk and was not read.</p>
 *
 * <p>Two sources, tried in order. The program change is the file telling you; the name is a person
 * telling you, and people write "Lead Saw" and "Kick" far more reliably than exporters write
 * program changes -- plenty of files leave everything on program 0, which is a grand piano and is
 * therefore indistinguishable from "nobody set this". That is why the name is the fallback and not
 * the other way round: when the file has bothered to say, it is right.</p>
 *
 * <p>Nothing here looks at pitch. A part's register would be a third source, and a better one than
 * either of these for choosing a note block that sounds in the right octave -- but only once the
 * instruments' own registers are modelled, which they are not, so it would be guessing at a thing
 * the build cannot act on yet.</p>
 */
final class MidiInstruments {
	/** The percussion channel in every General MIDI file, where the note is a drum and not a pitch. */
	static final int PERCUSSION_CHANNEL = 9;

	private MidiInstruments() {
	}

	/**
	 * The instrument for one part, from whichever source the setting allows and the file supplies.
	 *
	 * @param program the General MIDI program on the part's channel, or -1 if it never said
	 * @param channel the MIDI channel, since channel 9 is drums whatever program it claims
	 * @param name the track name, which is where a non-GM file keeps the same information
	 * @param fallback the configured default, used when nothing else answers
	 */
	static String choose(
		MidiInstrumentSource source,
		int program,
		int channel,
		String name,
		String fallback
	) {
		if (source == MidiInstrumentSource.DEFAULT_ONLY) {
			return fallback;
		}
		if (channel == PERCUSSION_CHANNEL) {
			// The channel outranks both, and outranks the name too: a drum part called "Piano Kick"
			// is still a drum part. Which drum is a per-note question, answered by the caller.
			return "SNARE";
		}
		if (program >= 0) {
			String fromProgram = forProgram(program);
			if (fromProgram != null) {
				return fromProgram;
			}
		}
		if (source == MidiInstrumentSource.FROM_FILE_THEN_NAME) {
			String fromName = forName(name);
			if (fromName != null) {
				return fromName;
			}
		}
		return fallback;
	}

	/**
	 * The note block nearest a General MIDI program, or null for programs with nothing like them.
	 *
	 * <p>By family of eight, which is how GM is laid out, with the handful of individual programs
	 * that have an exact match here pulled out -- a glockenspiel is a bell and there is no sense
	 * calling it a xylophone because its neighbours are. The families with no counterpart at all
	 * (strings, pads, sound effects) return null rather than being forced onto a harp, so the name
	 * still gets its turn and the configured default still means something.</p>
	 */
	static String forProgram(int program) {
		if (program < 0 || program > 127) {
			return null;
		}
		String exact = EXACT_PROGRAMS.get(program);
		if (exact != null) {
			return exact;
		}
		return switch (program / 8) {
			case 0 -> "HARP";           // pianos
			case 1 -> "XYLOPHONE";      // chromatic percussion
			case 2 -> "BIT";            // organs
			case 3 -> "GUITAR";         // guitars
			case 4 -> "BASS";           // basses
			case 7 -> "TRUMPET";        // brass
			case 8, 9 -> "FLUTE";       // reeds and pipes
			case 10, 11 -> "BIT";       // synth leads and pads
			case 13 -> "BANJO";         // ethnic
			case 14 -> "COW_BELL";      // percussive
			// Strings, ensembles, synth effects and sound effects: nothing here sounds like them,
			// and saying "harp" would be a guess wearing the clothes of an answer.
			default -> null;
		};
	}

	/** Programs whose own match beats their family's. */
	private static final Map<Integer, String> EXACT_PROGRAMS = Map.ofEntries(
		Map.entry(8, "BELL"),             // celesta
		Map.entry(9, "BELL"),             // glockenspiel
		Map.entry(10, "CHIME"),           // music box
		Map.entry(11, "IRON_XYLOPHONE"),  // vibraphone
		Map.entry(14, "BELL"),            // tubular bells
		Map.entry(15, "HARP"),            // dulcimer
		Map.entry(105, "BANJO"),          // banjo
		Map.entry(108, "XYLOPHONE"),      // kalimba
		Map.entry(112, "BELL"),           // tinkle bell
		Map.entry(114, "XYLOPHONE"),      // steel drums
		Map.entry(115, "HAT"),            // woodblock
		Map.entry(116, "BASEDRUM"),       // taiko
		Map.entry(117, "BASEDRUM"),       // melodic tom
		Map.entry(118, "BASEDRUM"),       // synth drum
		Map.entry(119, "HAT")             // reverse cymbal
	);

	/**
	 * The note block a track name asks for, or null if it does not ask for one.
	 *
	 * <p>First match down the list wins, so the order is the whole design: "bass drum" has to be
	 * read before "bass" or every kick in every file becomes a bass line, and "hi-hat" before "hat"
	 * for the same reason in reverse. Substring rather than whole-word, because names arrive as
	 * "Bass2", "LeadSaw" and "Drums (Ch10)" at least as often as they arrive as words.</p>
	 */
	static String forName(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (Keyword keyword : KEYWORDS) {
			if (lower.contains(keyword.text())) {
				return keyword.instrument();
			}
		}
		return null;
	}

	private record Keyword(String text, String instrument) {
	}

	private static final List<Keyword> KEYWORDS = List.of(
		// Drums first, and the compound names before the words they contain.
		new Keyword("bass drum", "BASEDRUM"),
		new Keyword("bassdrum", "BASEDRUM"),
		new Keyword("basedrum", "BASEDRUM"),
		new Keyword("kick", "BASEDRUM"),
		new Keyword("taiko", "BASEDRUM"),
		new Keyword("timpani", "BASEDRUM"),
		new Keyword("tom", "BASEDRUM"),
		new Keyword("snare", "SNARE"),
		new Keyword("clap", "SNARE"),
		new Keyword("rim", "SNARE"),
		new Keyword("hat", "HAT"),
		new Keyword("cymbal", "HAT"),
		new Keyword("crash", "HAT"),
		new Keyword("ride", "HAT"),
		new Keyword("shaker", "HAT"),
		new Keyword("tamb", "HAT"),
		new Keyword("perc", "HAT"),
		new Keyword("drum", "BASEDRUM"),
		new Keyword("cowbell", "COW_BELL"),
		new Keyword("cow bell", "COW_BELL"),
		// Pitched. "sub" and "808" are how a bass is named when it is not called one.
		new Keyword("bass", "BASS"),
		new Keyword("808", "BASS"),
		new Keyword("sub", "BASS"),
		new Keyword("contrabass", "BASS"),
		new Keyword("didg", "DIDGERIDOO"),
		new Keyword("guitar", "GUITAR"),
		new Keyword("gtr", "GUITAR"),
		new Keyword("pluck", "GUITAR"),
		new Keyword("pizz", "GUITAR"),
		new Keyword("banjo", "BANJO"),
		new Keyword("sitar", "BANJO"),
		new Keyword("koto", "BANJO"),
		new Keyword("shamisen", "BANJO"),
		new Keyword("mandolin", "BANJO"),
		new Keyword("glocken", "BELL"),
		new Keyword("celesta", "BELL"),
		new Keyword("tubular", "BELL"),
		new Keyword("chime", "CHIME"),
		new Keyword("music box", "CHIME"),
		new Keyword("bell", "BELL"),
		new Keyword("iron xylo", "IRON_XYLOPHONE"),
		new Keyword("vibra", "IRON_XYLOPHONE"),
		new Keyword("xylo", "XYLOPHONE"),
		new Keyword("marimba", "XYLOPHONE"),
		new Keyword("kalimba", "XYLOPHONE"),
		new Keyword("trumpet", "TRUMPET"),
		new Keyword("trombone", "TRUMPET"),
		new Keyword("brass", "TRUMPET"),
		new Keyword("horn", "TRUMPET"),
		new Keyword("tuba", "TRUMPET"),
		new Keyword("flute", "FLUTE"),
		new Keyword("piccolo", "FLUTE"),
		new Keyword("recorder", "FLUTE"),
		new Keyword("whistle", "FLUTE"),
		new Keyword("clarinet", "FLUTE"),
		new Keyword("oboe", "FLUTE"),
		new Keyword("bassoon", "FLUTE"),
		new Keyword("sax", "FLUTE"),
		new Keyword("ocarina", "FLUTE"),
		// Synth voices. A saw or a square is a chiptune sound and bit is the chiptune note block.
		new Keyword("saw", "BIT"),
		new Keyword("square", "BIT"),
		new Keyword("pulse", "BIT"),
		new Keyword("chip", "BIT"),
		new Keyword("8bit", "BIT"),
		new Keyword("8-bit", "BIT"),
		new Keyword("arp", "BIT"),
		new Keyword("organ", "BIT"),
		new Keyword("synth", "BIT"),
		new Keyword("lead", "BIT"),
		new Keyword("pling", "PLING"),
		new Keyword("piano", "HARP"),
		new Keyword("rhodes", "HARP"),
		new Keyword("keys", "HARP"),
		new Keyword("harp", "HARP")
	);

	/**
	 * Which drum a General MIDI percussion note is, by the standard drum map.
	 *
	 * <p>A note block instrument belongs to a whole layer where a drum kit changes with every note,
	 * so a percussion part cannot be one thing. This is what lets the caller pick the one its notes
	 * mostly are, which keeps a beat recognisable where a single instrument for the lot does not:
	 * kick and snare on the same block is a drum machine with one drum.</p>
	 */
	static String forDrumNote(int midiNote) {
		return switch (midiNote) {
			case 35, 36, 41, 43, 45, 47, 48, 50 -> "BASEDRUM";  // kicks and toms
			case 42, 44, 46, 49, 51, 52, 53, 55, 57, 59 -> "HAT";  // hats and cymbals
			case 54, 56, 58 -> "COW_BELL";  // tambourine, cowbell, vibraslap
			default -> "SNARE";  // snares, sticks, claps, and everything unaccounted for
		};
	}
}
