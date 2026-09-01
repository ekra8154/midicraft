package com.midicraft.nbs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NbsReader {
	private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
	private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
	private static final int MAX_NOTES = 2_000_000;
	private static final int MAX_LAYERS = 65_535;
	private static final int MAX_SUPPORTED_VERSION = 6;

	private NbsReader() {
	}

	public static NbsSong read(Path path) throws IOException {
		long size = Files.size(path);
		if (size <= 0 || size > MAX_FILE_BYTES) {
			throw new IOException("NBS file size must be between 1 byte and " + MAX_FILE_BYTES + " bytes.");
		}
		return read(Files.readAllBytes(path));
	}

	public static NbsSong read(byte[] bytes) throws IOException {
		try {
			return readBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
		} catch (NbsFormatException exception) {
			throw new IOException(exception.getMessage(), exception);
		} catch (RuntimeException exception) {
			throw new IOException("Malformed or truncated NBS file.", exception);
		}
	}

	private static NbsSong readBuffer(ByteBuffer input) {
		int firstLength = unsignedShort(input);
		int version;
		int vanillaInstrumentCount;
		int songLength;
		if (firstLength == 0) {
			version = unsignedByte(input);
			if (version < 1 || version > MAX_SUPPORTED_VERSION) {
				throw format("Unsupported NBS version " + version + ".");
			}
			vanillaInstrumentCount = unsignedByte(input);
			songLength = version >= 3 ? unsignedShort(input) : -1;
		} else {
			version = 0;
			vanillaInstrumentCount = 10;
			songLength = firstLength;
		}

		int layerCount = unsignedShort(input);
		if (layerCount > MAX_LAYERS) {
			throw format("NBS layer count is too large: " + layerCount + ".");
		}
		String name = string(input);
		String author = string(input);
		String originalAuthor = string(input);
		String description = string(input);
		int tempoHundredths = unsignedShort(input);
		if (tempoHundredths <= 0) {
			throw format("NBS tempo must be greater than zero.");
		}
		unsignedByte(input); // Legacy auto-save flag.
		unsignedByte(input); // Legacy auto-save interval.
		unsignedByte(input); // Time signature.
		for (int index = 0; index < 5; index++) {
			signedInt(input); // Editor statistics.
		}
		string(input); // Original MIDI/schematic filename.
		boolean loop = false;
		int maxLoopCount = 0;
		int loopStartTick = 0;
		if (version >= 4) {
			loop = unsignedByte(input) != 0;
			maxLoopCount = unsignedByte(input);
			loopStartTick = unsignedShort(input);
		}

		List<NbsSong.Note> notes = new ArrayList<>();
		int tick = -1;
		while (true) {
			int tickJump = unsignedShort(input);
			if (tickJump == 0) {
				break;
			}
			tick = Math.addExact(tick, tickJump);
			int layer = -1;
			while (true) {
				int layerJump = unsignedShort(input);
				if (layerJump == 0) {
					break;
				}
				layer = Math.addExact(layer, layerJump);
				int instrument = unsignedByte(input);
				int key = unsignedByte(input);
				int velocity = 100;
				int panning = 100;
				int pitchCents = 0;
				if (version >= 4) {
					velocity = unsignedByte(input);
					panning = unsignedByte(input);
					pitchCents = signedShort(input);
				}
				notes.add(new NbsSong.Note(tick, layer, instrument, key, velocity, panning, pitchCents));
				if (notes.size() > MAX_NOTES) {
					throw format("NBS file contains more than " + MAX_NOTES + " notes.");
				}
			}
		}

		List<NbsSong.Layer> layers = new ArrayList<>(layerCount);
		if (input.hasRemaining()) {
			for (int index = 0; index < layerCount; index++) {
				String layerName = string(input);
				int status = version >= 4 ? unsignedByte(input) : 0;
				int volume = unsignedByte(input);
				int panning = version >= 2 ? unsignedByte(input) : 100;
				layers.add(new NbsSong.Layer(index, layerName, status, volume, panning));
			}
		} else {
			for (int index = 0; index < layerCount; index++) {
				layers.add(new NbsSong.Layer(index, "", 0, 100, 100));
			}
		}

		List<NbsSong.CustomInstrument> customInstruments = new ArrayList<>();
		if (input.hasRemaining()) {
			int customCount = unsignedByte(input);
			for (int index = 0; index < customCount; index++) {
				customInstruments.add(new NbsSong.CustomInstrument(
					vanillaInstrumentCount + index,
					string(input),
					string(input),
					unsignedByte(input),
					unsignedByte(input) != 0
				));
			}
		}

		NbsSong.Header header = new NbsSong.Header(
			version,
			vanillaInstrumentCount,
			songLength,
			layerCount,
			name,
			author,
			originalAuthor,
			description,
			tempoHundredths,
			loop,
			maxLoopCount,
			loopStartTick
		);
		return new NbsSong(header, List.copyOf(notes), List.copyOf(layers), List.copyOf(customInstruments));
	}

	private static int unsignedByte(ByteBuffer input) {
		require(input, Byte.BYTES);
		return Byte.toUnsignedInt(input.get());
	}

	private static int signedShort(ByteBuffer input) {
		require(input, Short.BYTES);
		return input.getShort();
	}

	private static int unsignedShort(ByteBuffer input) {
		return Short.toUnsignedInt((short)signedShort(input));
	}

	private static int signedInt(ByteBuffer input) {
		require(input, Integer.BYTES);
		return input.getInt();
	}

	private static String string(ByteBuffer input) {
		int length = signedInt(input);
		if (length < 0 || length > MAX_STRING_BYTES) {
			throw format("Invalid NBS string length " + length + ".");
		}
		require(input, length);
		byte[] bytes = new byte[length];
		input.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void require(ByteBuffer input, int bytes) {
		if (bytes < 0 || input.remaining() < bytes) {
			throw format("Unexpected end of NBS file.");
		}
	}

	private static NbsFormatException format(String message) {
		return new NbsFormatException(message);
	}

	private static final class NbsFormatException extends RuntimeException {
		NbsFormatException(String message) {
			super(message);
		}
	}
}
