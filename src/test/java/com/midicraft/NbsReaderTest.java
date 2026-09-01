package com.midicraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.midicraft.nbs.NbsReader;
import com.midicraft.nbs.NbsSong;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NbsReaderTest {
	@Test
	void readsModernVersionFiveSong() throws Exception {
		LittleEndianOutput output = new LittleEndianOutput();
		output.shortValue(0);
		output.byteValue(5);
		output.byteValue(16);
		output.shortValue(12);
		output.shortValue(2);
		output.string("Test Song");
		output.string("Author");
		output.string("");
		output.string("Description");
		output.shortValue(1_250);
		output.byteValue(0);
		output.byteValue(10);
		output.byteValue(4);
		for (int index = 0; index < 5; index++) {
			output.intValue(0);
		}
		output.string("");
		output.byteValue(1);
		output.byteValue(2);
		output.shortValue(4);

		output.shortValue(1);
		output.shortValue(1);
		output.byteValue(1);
		output.byteValue(33);
		output.byteValue(80);
		output.byteValue(100);
		output.shortValue(-25);
		output.shortValue(1);
		output.byteValue(3);
		output.byteValue(60);
		output.byteValue(100);
		output.byteValue(120);
		output.shortValue(0);
		output.shortValue(0);
		output.shortValue(0);

		output.string("Bass");
		output.byteValue(0);
		output.byteValue(90);
		output.byteValue(100);
		output.string("Snare");
		output.byteValue(1);
		output.byteValue(100);
		output.byteValue(80);
		output.byteValue(0);

		NbsSong song = NbsReader.read(output.bytes());

		assertEquals(5, song.header().version());
		assertEquals("Test Song", song.header().name());
		assertEquals(12.5, song.header().ticksPerSecond());
		assertEquals(2, song.notes().size());
		assertEquals(33, song.notes().get(0).key());
		assertEquals(-25, song.notes().get(0).pitchCents());
		assertEquals(1, song.notes().get(1).layer());
		assertEquals("Snare", song.layers().get(1).name());
		assertEquals(80, song.layers().get(1).panning());
	}

	@Test
	void readsClassicSongDefaults() throws Exception {
		LittleEndianOutput output = new LittleEndianOutput();
		output.shortValue(4);
		output.shortValue(1);
		output.string("Classic");
		output.string("");
		output.string("");
		output.string("");
		output.shortValue(1_000);
		output.byteValue(0);
		output.byteValue(10);
		output.byteValue(4);
		for (int index = 0; index < 5; index++) {
			output.intValue(0);
		}
		output.string("");
		output.shortValue(1);
		output.shortValue(1);
		output.byteValue(0);
		output.byteValue(45);
		output.shortValue(0);
		output.shortValue(0);
		output.string("");
		output.byteValue(100);
		output.byteValue(0);

		NbsSong song = NbsReader.read(output.bytes());

		assertEquals(0, song.header().version());
		assertEquals(10, song.header().vanillaInstrumentCount());
		assertEquals(100, song.notes().getFirst().velocity());
		assertEquals(100, song.notes().getFirst().panning());
		assertEquals(0, song.notes().getFirst().pitchCents());
	}

	@Test
	void rejectsTruncatedFilesWithUsefulError() {
		IOException exception = assertThrows(IOException.class, () -> NbsReader.read(new byte[] {0, 0, 5}));
		assertEquals("Unexpected end of NBS file.", exception.getMessage());
	}

	private static final class LittleEndianOutput {
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();

		void byteValue(int value) {
			output.write(value);
		}

		void shortValue(int value) {
			output.write(value);
			output.write(value >>> 8);
		}

		void intValue(int value) {
			output.write(value);
			output.write(value >>> 8);
			output.write(value >>> 16);
			output.write(value >>> 24);
		}

		void string(String value) {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			intValue(bytes.length);
			output.writeBytes(bytes);
		}

		byte[] bytes() {
			return output.toByteArray();
		}
	}
}
