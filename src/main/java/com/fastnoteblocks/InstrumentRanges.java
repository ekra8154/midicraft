package com.fastnoteblocks;

import java.util.Map;

/**
 * Where each note block instrument actually sounds, as the lowest MIDI note its 25 pitches reach.
 *
 * <p>Every instrument spans the same 25 semitones ({@link NotePitch#PITCH_COUNT}), but they do not
 * all span the <em>same</em> 25. A bass at pitch 0 plays F#1 where a harp plays F#3 and a bell
 * plays F#5 -- the samples themselves sit in different registers, staggered a whole octave apart
 * per tier, so that together they cover six octaves (F#1 to F#7, MIDI 30 to 102) and adjacent
 * tiers overlap by exactly an octave. The composer's own convention of writing every layer in the
 * harp window (54 to 78) treats the register as a timbre choice; this table is what lets a split
 * layer treat written pitch as true pitch instead.</p>
 *
 * <p>The tiers, verified against in-game measurement:</p>
 * <pre>
 *   F#1-F#3 (30-54)  BASS, DIDGERIDOO
 *   F#2-F#4 (42-66)  GUITAR, TRUMPET_WEATHERED, TRUMPET_OXIDIZED
 *   F#3-F#5 (54-78)  HARP, IRON_XYLOPHONE, BIT, BANJO, PLING, TRUMPET, TRUMPET_EXPOSED
 *   F#4-F#6 (66-90)  FLUTE, COW_BELL
 *   F#5-F#7 (78-102) BELL, CHIME, XYLOPHONE
 * </pre>
 *
 * <p>The percussion entries are not physical registers -- a snare's 25 pitches are 25 timbres of
 * one drum, not notes on a scale. They are virtual, chosen so that the three drums stack low to
 * high with no gap or overlap: a split percussion keyboard reads kick at the bottom, snare in the
 * middle, hats on top, and clicking a row still picks one of the drum's real 25 pitches.</p>
 */
public final class InstrumentRanges {
	/**
	 * Where the harp tier starts, which is also the window every ordinary layer is written in.
	 *
	 * <p>The same 54 as the composer's {@code NOTE_BLOCK_BASE_MIDI_NOTE}; declared here as well
	 * because this class must stay loadable without the client sources, for probes.</p>
	 */
	public static final int HARP_BASE_MIDI = 54;

	private static final Map<String, Integer> BASE_MIDI = Map.ofEntries(
		Map.entry("BASS", 30),
		Map.entry("DIDGERIDOO", 30),
		Map.entry("GUITAR", 42),
		Map.entry("HARP", 54),
		Map.entry("IRON_XYLOPHONE", 54),
		Map.entry("BIT", 54),
		Map.entry("BANJO", 54),
		Map.entry("PLING", 54),
		// The four trumpets are one voice at four weathering ages, and they do not walk down a
		// register as they age. Measured with a pitch detector rather than taken from the table
		// the rest of this came from: copper and exposed copper both bottom out at F#3, and
		// weathered and oxidized both bottom out at F#2. Two ages to a register, not one step
		// per age -- exposed is a dirtier copper playing the same notes, and oxidized is a
		// dirtier weathered. So the first pair sits with the harp and the second with the
		// guitar, and anybody tempted to make this a four-rung ladder should go and listen.
		Map.entry("TRUMPET", 54),
		Map.entry("TRUMPET_EXPOSED", 54),
		Map.entry("TRUMPET_WEATHERED", 42),
		Map.entry("TRUMPET_OXIDIZED", 42),
		Map.entry("FLUTE", 66),
		Map.entry("COW_BELL", 66),
		Map.entry("BELL", 78),
		Map.entry("CHIME", 78),
		Map.entry("XYLOPHONE", 78),
		// Virtual, stacked, contiguous: see the class comment.
		Map.entry("BASEDRUM", 30),
		Map.entry("SNARE", 55),
		Map.entry("HAT", 80)
	);

	private InstrumentRanges() {
	}

	/**
	 * The MIDI note this instrument's pitch 0 sounds as.
	 *
	 * <p>An id this table does not know answers the harp window, which is what the rest of the
	 * composer already assumes of everything.</p>
	 */
	public static int baseMidi(String instrumentId) {
		return BASE_MIDI.getOrDefault(instrumentId, HARP_BASE_MIDI);
	}

	/** The lowest MIDI note the instrument can sound, inclusive. */
	public static int lowestMidi(String instrumentId) {
		return baseMidi(instrumentId);
	}

	/** The highest MIDI note the instrument can sound, inclusive. */
	public static int highestMidi(String instrumentId) {
		return baseMidi(instrumentId) + NotePitch.PITCH_COUNT - 1;
	}
}
