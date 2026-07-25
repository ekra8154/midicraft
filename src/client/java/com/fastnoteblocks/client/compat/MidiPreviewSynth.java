package com.fastnoteblocks.client.compat;

import java.util.HashMap;
import java.util.Map;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Synthesizer;

final class MidiPreviewSynth {
	private Synthesizer synthesizer;
	private MidiChannel[] channels;
	private boolean unavailable;
	private final Map<Long, ActiveNote> active = new HashMap<>();

	boolean noteOn(long id, int layerIndex, String instrument, int midiNote, int velocity) {
		if (!ensureOpen()) {
			return false;
		}
		int channelIndex = channelIndex(layerIndex);
		MidiChannel channel = channels[channelIndex];
		channel.programChange(program(instrument));
		channel.noteOn(Math.max(0, Math.min(127, midiNote)), Math.max(1, Math.min(127, velocity)));
		active.put(id, new ActiveNote(channelIndex, midiNote));
		return true;
	}

	void noteOff(long id) {
		ActiveNote note = active.remove(id);
		if (note != null && channels != null && note.channel() < channels.length) {
			channels[note.channel()].noteOff(note.midiNote());
		}
	}

	void stopAll() {
		if (channels != null) {
			for (MidiChannel channel : channels) {
				if (channel != null) {
					channel.allNotesOff();
				}
			}
		}
		active.clear();
	}

	void close() {
		stopAll();
		if (synthesizer != null && synthesizer.isOpen()) {
			synthesizer.close();
		}
		synthesizer = null;
		channels = null;
	}

	private boolean ensureOpen() {
		if (unavailable) {
			return false;
		}
		if (synthesizer != null && synthesizer.isOpen() && channels != null && channels.length > 0) {
			return true;
		}
		try {
			synthesizer = MidiSystem.getSynthesizer();
			synthesizer.open();
			channels = synthesizer.getChannels();
			if (channels == null || channels.length == 0) {
				throw new IllegalStateException("No MIDI synthesizer channels");
			}
			return true;
		} catch (Exception exception) {
			unavailable = true;
			close();
			return false;
		}
	}

	private int channelIndex(int layerIndex) {
		int usable = Math.max(1, Math.min(channels.length, 9));
		return Math.floorMod(layerIndex, usable);
	}

	private static int program(String instrument) {
		return switch (instrument == null ? "" : instrument) {
			case "BASS" -> 32;
			case "BASEDRUM" -> 116;
			case "SNARE", "HAT" -> 118;
			case "GUITAR" -> 24;
			case "FLUTE" -> 73;
			case "BELL" -> 14;
			case "CHIME" -> 9;
			case "XYLOPHONE", "IRON_XYLOPHONE" -> 13;
			case "COW_BELL" -> 8;
			case "DIDGERIDOO" -> 109;
			case "BIT" -> 80;
			case "BANJO" -> 105;
			case "PLING" -> 10;
			case "TRUMPET", "TRUMPET_EXPOSED", "TRUMPET_WEATHERED", "TRUMPET_OXIDIZED" -> 56;
			default -> 0;
		};
	}

	private record ActiveNote(int channel, int midiNote) {
	}
}
