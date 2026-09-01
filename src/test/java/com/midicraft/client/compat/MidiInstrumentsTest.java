package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.midicraft.client.MidicraftConfig.MidiInstrumentSource;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reading the instrument out of a MIDI file instead of giving every track the same one.
 *
 * <p>Most of what is worth testing here is precedence, not the mapping: which source wins, and what
 * happens when the one that should win says nothing.</p>
 */
class MidiInstrumentsTest {
	private static final String FALLBACK = "HARP";

	/** Only the last test needs it, but PreviewInstrument's items will not load without it. */
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void theFileBeatsTheName() {
		// Program 33 is a fingered bass; the track is called "Piano". The file is the one that knows.
		assertEquals("BASS", MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE_THEN_NAME, 33, 0, "Piano", FALLBACK));
	}

	@Test
	void theNameAnswersWhenTheFileDidNot() {
		assertEquals("BIT", MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE_THEN_NAME, -1, 0, "Lead Saw", FALLBACK));
		assertEquals("BASEDRUM", MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE_THEN_NAME, -1, 0, "Kick 2", FALLBACK));
	}

	/** A family with no note block like it must not be answered, or the name never gets its turn. */
	@Test
	void theNameAnswersWhenTheFileSaidSomethingWithNoCounterpart() {
		assertNull(MidiInstruments.forProgram(48), "string ensemble is not any of these blocks");
		assertEquals("BIT", MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE_THEN_NAME, 48, 0, "Synth Strings", FALLBACK));
		assertEquals(FALLBACK, MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE_THEN_NAME, 48, 0, "Strings", FALLBACK));
	}

	@Test
	void fromFileAloneNeverConsultsTheName() {
		assertEquals(FALLBACK, MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE, -1, 0, "Lead Saw", FALLBACK));
		assertEquals("GUITAR", MidiInstruments.choose(
			MidiInstrumentSource.FROM_FILE, 25, 0, "Lead Saw", FALLBACK));
	}

	@Test
	void theDefaultOnlySettingReadsNothingAtAll() {
		assertEquals(FALLBACK, MidiInstruments.choose(
			MidiInstrumentSource.DEFAULT_ONLY, 33, 0, "Kick", FALLBACK));
	}

	/** Channel 10 is drums however the file describes it, and whatever it is called. */
	@Test
	void thePercussionChannelOutranksBoth() {
		assertEquals("SNARE", MidiInstruments.choose(MidiInstrumentSource.FROM_FILE_THEN_NAME,
			0, MidiInstruments.PERCUSSION_CHANNEL, "Grand Piano", FALLBACK));
	}

	/**
	 * The order of the keyword list is the design, so the pairs that would collide are pinned.
	 */
	@Test
	void readsTheCompoundNameBeforeTheWordInsideIt() {
		assertEquals("BASEDRUM", MidiInstruments.forName("Bass Drum"), "not a bass line");
		assertEquals("BASS", MidiInstruments.forName("Bass"));
		assertEquals("HAT", MidiInstruments.forName("Closed Hi-Hat"));
		assertEquals("BELL", MidiInstruments.forName("Glockenspiel"), "not a plain bell");
		assertEquals("IRON_XYLOPHONE", MidiInstruments.forName("Vibraphone"), "not a xylophone");
		assertEquals("COW_BELL", MidiInstruments.forName("Cowbell"), "not a bell");
	}

	@Test
	void matchesInsideARunTogetherName() {
		assertEquals("BASS", MidiInstruments.forName("Bass2"));
		assertEquals("BIT", MidiInstruments.forName("LeadSaw"));
		assertEquals("HARP", MidiInstruments.forName("EPiano1"));
	}

	@Test
	void hasNoOpinionAboutANameThatSaysNothing() {
		assertNull(MidiInstruments.forName("Track 3"));
		assertNull(MidiInstruments.forName(""));
		assertNull(MidiInstruments.forName(null));
	}

	@Test
	void mapsTheGeneralMidiDrumsOntoTheThreeThatExist() {
		assertEquals("BASEDRUM", MidiInstruments.forDrumNote(36), "bass drum 1");
		assertEquals("SNARE", MidiInstruments.forDrumNote(38), "acoustic snare");
		assertEquals("HAT", MidiInstruments.forDrumNote(42), "closed hi-hat");
		assertEquals("COW_BELL", MidiInstruments.forDrumNote(56), "cowbell");
		assertEquals("SNARE", MidiInstruments.forDrumNote(99), "and anything unaccounted for");
	}

	/** Whatever a program maps to has to be a real note block, or the layer silently falls back. */
	@Test
	void everyMappedProgramNamesAnInstrumentThatExists() {
		for (int program = 0; program <= 127; program++) {
			String id = MidiInstruments.forProgram(program);
			if (id != null) {
				assertEquals(id, PreviewInstrument.byId(id).id(), "program " + program);
			}
		}
		for (int note = 0; note <= 127; note++) {
			String id = MidiInstruments.forDrumNote(note);
			assertNotNull(id);
			assertEquals(id, PreviewInstrument.byId(id).id(), "drum note " + note);
		}
	}
}
