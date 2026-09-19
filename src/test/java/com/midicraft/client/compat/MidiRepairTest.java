package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.jupiter.api.Test;

/**
 * That a MIDI file Java refuses over one bad track comes in with every other track's notes.
 *
 * <p>Built by hand rather than read off a real file, because the real one -- Let It Happen, with
 * pitch bends a byte short on sixteen note-less "Thumbnail" tracks -- lives in a downloads folder
 * and not in this repository.</p>
 */
class MidiRepairTest {
	private static final int[] NAME = {0x00, 0xFF, 0x03};
	private static final int[] END = {0x00, 0xFF, 0x2F, 0x00};

	/** A track: a name, then these events, then an End-of-Track unless it is left off. */
	private static byte[] track(String name, int[] events, boolean endOfTrack) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (int value : NAME) {
			body.write(value);
		}
		body.write(name.length());
		body.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		for (int value : events) {
			body.write(value);
		}
		if (endOfTrack) {
			for (int value : END) {
				body.write(value);
			}
		}
		byte[] bytes = body.toByteArray();
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();
		chunk.writeBytes("MTrk".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		chunk.write(bytes.length >>> 24);
		chunk.write(bytes.length >>> 16);
		chunk.write(bytes.length >>> 8);
		chunk.write(bytes.length);
		chunk.writeBytes(bytes);
		return chunk.toByteArray();
	}

	private static byte[] file(byte[]... tracks) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0, (byte)tracks.length, 1, (byte)0xE0});
		for (byte[] track : tracks) {
			out.writeBytes(track);
		}
		return out.toByteArray();
	}

	/** Two notes, C and E, each struck and let go. */
	private static final int[] MELODY = {
		0x00, 0x90, 0x3C, 0x60, 0x60, 0x80, 0x3C, 0x00,
		0x00, 0x90, 0x40, 0x60, 0x60, 0x80, 0x40, 0x00};

	private static int notes(Sequence sequence) {
		int notes = 0;
		for (javax.sound.midi.Track track : sequence.getTracks()) {
			for (int index = 0; index < track.size(); index++) {
				if (track.get(index).getMessage() instanceof ShortMessage message
						&& message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0) {
					notes++;
				}
			}
		}
		return notes;
	}

	@Test
	void aTruncatedTrackIsLeftOutAndTheRestComesIn() throws Exception {
		// A note-on one data byte short, at the very end: Java reads off the end of the track.
		byte[] broken = file(track("Melody", MELODY, true),
			track("Broken", new int[] {0x00, 0x90, 0x3C}, false));
		assertThrows(Exception.class, () -> MidiSystem.getSequence(new ByteArrayInputStream(broken)),
			"Java refuses the whole file over the one track");

		MidiRepair.Result repaired = MidiRepair.repair(broken);
		Sequence read = MidiSystem.getSequence(new ByteArrayInputStream(repaired.bytes()));
		assertEquals(List.of("Broken"), repaired.skipped());
		assertEquals(2, notes(read), "both of the melody's notes come in");
	}

	@Test
	void aTrackThatOnlyLacksItsEndIsGivenOne() throws Exception {
		byte[] unfinished = file(track("Melody", MELODY, false));
		MidiRepair.Result repaired = MidiRepair.repair(unfinished);
		Sequence read = MidiSystem.getSequence(new ByteArrayInputStream(repaired.bytes()));
		assertEquals(List.of(), repaired.skipped(), "nothing is left out");
		assertEquals(2, notes(read));
	}

	@Test
	void notAMidiFileIsNotRepaired() {
		assertEquals(null, MidiRepair.repair("RIFF, not a MIDI file".getBytes()));
	}
}
