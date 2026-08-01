package com.fastnoteblocks.nbs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes a song back out in Note Block Studio's format.
 *
 * <p>The exact inverse of {@link NbsReader}, field for field and in the same order, so that
 * anything written here reads back as itself. Version five is written rather than the newest the
 * reader accepts: four is where velocity, panning and pitch bend arrive, five adds the song length,
 * and six adds nothing this mod has to say. Writing the oldest version that carries everything we
 * hold is what keeps the file openable by the widest range of other tools.</p>
 *
 * <p>No Minecraft on the classpath here, deliberately -- this sits in the same source set as the
 * reader so both can be exercised without starting a game.</p>
 */
public final class NbsWriter {
	/** Note Block Studio's own vanilla instruments, which is the first sixteen and no more. */
	public static final int VANILLA_INSTRUMENT_COUNT = 16;
	private static final int WRITTEN_VERSION = 5;
	private static final int MAX_TICK = 65_535;

	private NbsWriter() {
	}

	public static void write(Path path, NbsSong song) throws IOException {
		Files.write(path, toBytes(song));
	}

	public static byte[] toBytes(NbsSong song) {
		Out out = new Out();
		List<NbsSong.Note> notes = new ArrayList<>(song.notes());
		notes.sort(Comparator.comparingInt(NbsSong.Note::tick).thenComparingInt(NbsSong.Note::layer));

		int songLength = notes.isEmpty() ? 0 : notes.get(notes.size() - 1).tick();
		int layerCount = Math.max(song.layers().size(),
			notes.stream().mapToInt(NbsSong.Note::layer).max().orElse(-1) + 1);

		// Header. The leading zero is what tells a reader this is not the ancient format whose
		// first field was the song length.
		out.shortValue(0);
		out.byteValue(WRITTEN_VERSION);
		out.byteValue(VANILLA_INSTRUMENT_COUNT);
		out.shortValue(songLength);
		out.shortValue(layerCount);
		out.string(song.header().name());
		out.string(song.header().author());
		out.string(song.header().originalAuthor());
		out.string(song.header().description());
		out.shortValue(song.header().tempoHundredths());
		out.byteValue(0);          // Legacy auto-save flag.
		out.byteValue(0);          // Legacy auto-save interval.
		out.byteValue(4);          // Time signature, four beats to the bar.
		for (int index = 0; index < 5; index++) {
			out.intValue(0);       // Editor statistics, which we do not keep.
		}
		out.string("");            // Original MIDI or schematic filename.
		out.byteValue(song.header().loop() ? 1 : 0);
		out.byteValue(song.header().maxLoopCount());
		out.shortValue(song.header().loopStartTick());

		// Notes, as jumps rather than positions: how far to the next tick that holds anything, then
		// how far to the next layer within it, and a zero to say there is no next.
		int lastTick = -1;
		int index = 0;
		while (index < notes.size()) {
			int tick = notes.get(index).tick();
			out.shortValue(tick - lastTick);
			lastTick = tick;
			int lastLayer = -1;
			while (index < notes.size() && notes.get(index).tick() == tick) {
				NbsSong.Note note = notes.get(index);
				// One note per layer per tick, and it is not a preference. A second note on the same
				// layer and tick has a jump of nought to the one before it, and nought is the byte
				// that ends the tick -- so the file does not come out wrong, it comes out
				// unreadable, and everything after this point is read as structure. Refused here
				// rather than written, because the caller can spread a chord across layers and the
				// format cannot.
				if (note.layer() == lastLayer) {
					throw new IllegalArgumentException("Two notes on layer " + note.layer()
						+ " at tick " + tick + ". NBS holds one note per layer per tick, so a "
						+ "chord has to be spread across layers before it is written.");
				}
				out.shortValue(note.layer() - lastLayer);
				lastLayer = note.layer();
				out.byteValue(note.instrument());
				out.byteValue(note.key());
				out.byteValue(note.velocity());
				out.byteValue(note.panning());
				out.shortValue(note.pitchCents());
				index++;
			}
			out.shortValue(0);
		}
		out.shortValue(0);

		for (int layer = 0; layer < layerCount; layer++) {
			NbsSong.Layer written = layer < song.layers().size()
				? song.layers().get(layer)
				: new NbsSong.Layer(layer, "", 0, 100, 100);
			out.string(written.name());
			out.byteValue(written.status());
			out.byteValue(written.volume());
			out.byteValue(written.panning());
		}

		out.byteValue(song.customInstruments().size());
		for (NbsSong.CustomInstrument instrument : song.customInstruments()) {
			out.string(instrument.name());
			out.string(instrument.soundFile());
			out.byteValue(instrument.key());
			out.byteValue(instrument.pressKey() ? 1 : 0);
		}
		return out.bytes.toByteArray();
	}

	/** The most a tick number can be before the format cannot say it. */
	public static int maxTick() {
		return MAX_TICK;
	}

	/** Little-endian, because that is what the format is. */
	private static final class Out {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		void byteValue(int value) {
			bytes.write(value & 0xFF);
		}

		void shortValue(int value) {
			bytes.write(value & 0xFF);
			bytes.write((value >> 8) & 0xFF);
		}

		void intValue(int value) {
			bytes.write(value & 0xFF);
			bytes.write((value >> 8) & 0xFF);
			bytes.write((value >> 16) & 0xFF);
			bytes.write((value >> 24) & 0xFF);
		}

		void string(String value) {
			byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
			intValue(encoded.length);
			bytes.write(encoded, 0, encoded.length);
		}
	}
}
