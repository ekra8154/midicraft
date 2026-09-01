package com.midicraft.nbs;

import java.util.List;

public record NbsSong(
	Header header,
	List<Note> notes,
	List<Layer> layers,
	List<CustomInstrument> customInstruments
) {
	public record Header(
		int version,
		int vanillaInstrumentCount,
		int songLength,
		int layerCount,
		String name,
		String author,
		String originalAuthor,
		String description,
		int tempoHundredths,
		boolean loop,
		int maxLoopCount,
		int loopStartTick
	) {
		public double ticksPerSecond() {
			return tempoHundredths / 100.0;
		}
	}

	public record Note(
		int tick,
		int layer,
		int instrument,
		int key,
		int velocity,
		int panning,
		int pitchCents
	) {
	}

	public record Layer(int index, String name, int status, int volume, int panning) {
	}

	public record CustomInstrument(int id, String name, String soundFile, int key, boolean pressKey) {
	}
}
