package com.midicraft.client.compat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a MIDI file Java's reader refused readable, by giving up only the tracks it cannot read.
 *
 * <p>Java's reader is strict where most players are forgiving. One malformed event anywhere -- a
 * Let It Happen transcription carries pitch bends one data byte short, on tracks named "Thumbnail"
 * that hold no notes at all -- puts it out of step for the rest of that track, it runs off the end
 * of the track, and it rejects the whole file. Every note on every other track is lost to a track
 * the import would have ignored anyway.</p>
 *
 * <p>So each track is walked by the rules of the format, and one that follows them is kept exactly
 * as it was. One that does not is put to Java's reader on its own, and kept if Java reads notes out
 * of it -- Java is the judge that matters, and a stricter one here would throw away music it plays
 * happily. But a broken track Java reads with no notes in it is left out too: whatever it read, it
 * read out of step, and all it can add is a wrong length -- one of Let It Happen's reads as a song
 * five times longer than the music. One Java refuses outright gets an End-of-Track where that is
 * all it lacks, and is left out otherwise. Every track left out is named, so the import can say so
 * rather than the file coming in quietly short.</p>
 */
final class MidiRepair {
	private MidiRepair() {
	}

	/**
	 * @param bytes the file with every unreadable track emptied
	 * @param skipped a name for each track that was emptied: its own name, or its number
	 */
	record Result(byte[] bytes, List<String> skipped) {
	}

	/** The file made readable, or null where it is not a standard MIDI file at all. */
	static Result repair(byte[] file) {
		if (file.length < 14 || !chunkIs(file, 0, "MThd")) {
			return null;
		}
		int headerLength = readInt(file, 4);
		int at = 8 + headerLength;
		if (headerLength < 6 || at > file.length) {
			return null;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(file.length);
		out.write(file, 0, at);
		List<String> skipped = new ArrayList<>();
		int trackNumber = 0;
		while (at + 8 <= file.length) {
			int length = readInt(file, at + 4);
			int start = at + 8;
			if (length < 0 || start + length > file.length) {
				return null;
			}
			if (!chunkIs(file, at, "MTrk")) {
				// A chunk the format does not define is skipped by every reader, Java's included.
				out.write(file, at, 8 + length);
				at = start + length;
				continue;
			}
			trackNumber++;
			Walk walk = walk(file, start, start + length);
			int javaNotes = walk.clean && walk.endOfTrack ? 1 : notesJavaReads(file, at, 8 + length);
			if (javaNotes > 0) {
				out.write(file, at, 8 + length);
			} else if (javaNotes < 0 && walk.clean) {
				writeTrack(out, file, start, length, true);
			} else {
				skipped.add(walk.name != null && !walk.name.isBlank()
					? walk.name.strip() : "track " + trackNumber);
				writeTrack(out, file, start, 0, true);
			}
			at = start + length;
		}
		return new Result(out.toByteArray(), List.copyOf(skipped));
	}

	/** What walking a track found: whether it held together, and whether it ended on the marker. */
	private record Walk(boolean clean, boolean endOfTrack, String name) {
	}

	private static Walk walk(byte[] file, int start, int end) {
		int at = start;
		int status = -1;
		String name = null;
		while (at < end) {
			// The delta time: at most four bytes, each but the last with its top bit set.
			int length = 0;
			while (at < end && (file[at] & 0x80) != 0 && length < 3) {
				at++;
				length++;
			}
			if (at >= end || (file[at] & 0x80) != 0) {
				return new Walk(false, false, name);
			}
			at++;
			if (at >= end) {
				return new Walk(false, false, name);
			}
			int first = file[at] & 0xFF;
			if (first >= 0x80) {
				status = first;
				at++;
			} else if (status < 0 || status >= 0xF0) {
				// Running status only ever carries a channel event forward.
				return new Walk(false, false, name);
			}
			if (status == 0xFF) {
				if (at >= end) {
					return new Walk(false, false, name);
				}
				int type = file[at++] & 0xFF;
				long[] read = readVariable(file, at, end);
				if (read == null || read[1] + read[0] > end) {
					return new Walk(false, false, name);
				}
				at = (int)read[1];
				if (type == 0x03 && name == null) {
					name = new String(file, at, (int)read[0], StandardCharsets.ISO_8859_1);
				}
				at += (int)read[0];
				if (type == 0x2F) {
					return new Walk(true, true, name);
				}
				status = -1;
			} else if (status == 0xF0 || status == 0xF7) {
				long[] read = readVariable(file, at, end);
				if (read == null || read[1] + read[0] > end) {
					return new Walk(false, false, name);
				}
				at = (int)(read[1] + read[0]);
				status = -1;
			} else if (status >= 0xF0) {
				// System common and real-time messages have no place in a file.
				return new Walk(false, false, name);
			} else {
				int data = (status & 0xF0) == 0xC0 || (status & 0xF0) == 0xD0 ? 1 : 2;
				for (int index = 0; index < data; index++) {
					if (at >= end || (file[at] & 0x80) != 0) {
						return new Walk(false, false, name);
					}
					at++;
				}
			}
		}
		return new Walk(at == end, false, name);
	}

	/**
	 * How many notes Java's reader finds in this one track, in a file of its own under the same
	 * division, or -1 where it refuses the track.
	 */
	private static int notesJavaReads(byte[] file, int chunk, int chunkLength) {
		byte[] alone = new byte[14 + chunkLength];
		System.arraycopy(file, 0, alone, 0, 8);
		alone[4] = 0;
		alone[5] = 0;
		alone[6] = 0;
		alone[7] = 6;
		alone[8] = 0;
		alone[9] = 1;
		alone[10] = 0;
		alone[11] = 1;
		alone[12] = file[12];
		alone[13] = file[13];
		System.arraycopy(file, chunk, alone, 14, chunkLength);
		try {
			javax.sound.midi.Sequence read = javax.sound.midi.MidiSystem.getSequence(
				new java.io.ByteArrayInputStream(alone));
			int notes = 0;
			for (javax.sound.midi.Track track : read.getTracks()) {
				for (int index = 0; index < track.size(); index++) {
					if (track.get(index).getMessage() instanceof javax.sound.midi.ShortMessage message
							&& message.getCommand() == javax.sound.midi.ShortMessage.NOTE_ON
							&& message.getData2() > 0) {
						notes++;
					}
				}
			}
			return notes;
		} catch (javax.sound.midi.InvalidMidiDataException | java.io.IOException refused) {
			return -1;
		}
	}

	/** A variable-length number: {value, the offset after it}, or null where it runs off the end. */
	private static long[] readVariable(byte[] file, int at, int end) {
		long value = 0;
		for (int length = 0; length < 4 && at < end; length++) {
			int next = file[at++] & 0xFF;
			value = (value << 7) | (next & 0x7F);
			if ((next & 0x80) == 0) {
				return new long[] {value, at};
			}
		}
		return null;
	}

	/** A track chunk holding {@code length} bytes of the original, and an End-of-Track after them. */
	private static void writeTrack(ByteArrayOutputStream out, byte[] file, int start, int length,
			boolean endOfTrack) {
		int total = length + (endOfTrack ? 4 : 0);
		out.write('M');
		out.write('T');
		out.write('r');
		out.write('k');
		out.write(total >>> 24);
		out.write(total >>> 16);
		out.write(total >>> 8);
		out.write(total);
		out.write(file, start, length);
		if (endOfTrack) {
			out.write(0x00);
			out.write(0xFF);
			out.write(0x2F);
			out.write(0x00);
		}
	}

	private static boolean chunkIs(byte[] file, int at, String id) {
		for (int index = 0; index < 4; index++) {
			if (file[at + index] != id.charAt(index)) {
				return false;
			}
		}
		return true;
	}

	private static int readInt(byte[] file, int at) {
		return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16 | (file[at + 2] & 0xFF) << 8
			| (file[at + 3] & 0xFF);
	}
}
