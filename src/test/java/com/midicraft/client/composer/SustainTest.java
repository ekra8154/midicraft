package com.midicraft.client.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import com.midicraft.client.composer.ComposerProject.Strikes;
import com.midicraft.client.composer.ComposerProject.Sustain;
import com.midicraft.client.composer.ComposerProject.SustainLength;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sustained notes: a long note struck again on a grid the whole song shares, while it lasts.
 *
 * <p>Notes here sit at the default 480 ticks a quarter, so a thirty-second is 60, a sixteenth 120,
 * an eighth 240 and a quarter 480. The song's first note is at tick 0 unless a test says otherwise,
 * which is where the shared grid is counted from.</p>
 */
class SustainTest {

	// These pin the exact strike arithmetic, so they measure it with alignment off. Aligning is
	// tested on its own in SustainAlignTest.
	@org.junit.jupiter.api.BeforeEach
	void strikeExactly() {
		com.midicraft.client.composer.ComposerProject.ALIGN_SUSTAINED_NOTES = false;
	}

	@org.junit.jupiter.api.AfterEach
	void alignAgain() {
		com.midicraft.client.composer.ComposerProject.ALIGN_SUSTAINED_NOTES = true;
	}
	private static final Sustain EVERY_SIXTEENTH =
		new Sustain(true, SustainLength.QUARTER, SustainLength.SIXTEENTH);
	/** What Finest stands for in tests that never pick it. */
	private static final double FINE = 60.0;
	private static long nextId = 1L;

	private static NoteEvent note(long tick) {
		return new NoteEvent(nextId++, 60, tick, 120, 90);
	}

	private static Layer layer(Sustain sustain, NoteEvent... notes) {
		return new Layer("Pad", "HARP", false, true, true, List.of(notes)).withSustain(sustain);
	}

	private static ComposerProject songOf(Layer... layers) {
		return new ComposerProject("Sustain", ComposerProject.DEFAULT_PPQ,
			ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER, List.of(layers), 0, 1000L,
			0L, ComposerProject.DEFAULT_SPEED_QUARTERS);
	}

	private static double finestOf(ComposerProject song) {
		return song.finestSustainStep(SongAnalysis.of(song, true));
	}

	private static List<Long> ticksOf(Layer expanded, int midi) {
		return expanded.notes().stream().filter(note -> note.midiNote() == midi)
			.map(NoteEvent::startTick).toList();
	}

	@Test
	void shortNotesStayOneStrike() {
		Layer pad = layer(EVERY_SIXTEENTH, new NoteEvent(1, 60, 0, 240, 90));
		ComposerProject song = songOf(pad);

		assertEquals(List.of(0L), ticksOf(song.withSustainsExpanded(pad, FINE), 60),
			"an eighth is under the quarter a note needs to sustain");
	}

	@Test
	void strikesFallOnTheSharedGridStrictlyInsideTheNote() {
		Layer early = layer(null, new NoteEvent(1, 72, 0, 120, 90));
		Layer late = layer(EVERY_SIXTEENTH, new NoteEvent(2, 60, 50, 600, 90));
		Layer onBeat = layer(EVERY_SIXTEENTH, new NoteEvent(3, 64, 0, 480, 90));
		ComposerProject song = songOf(early, late, onBeat);

		assertEquals(List.of(50L, 240L, 360L, 480L, 600L),
			ticksOf(song.withSustainsExpanded(late, FINE), 60),
			"the first strike is the first line a whole sixteenth after 50; none at the end, 650");
		assertEquals(List.of(0L, 120L, 240L, 360L),
			ticksOf(song.withSustainsExpanded(onBeat, FINE), 64),
			"a strike on the note's own end is not inside it");
	}

	@Test
	void aWrittenNoteKeepsItsCell() {
		NoteEvent held = new NoteEvent(1, 60, 0, 960, 90);
		NoteEvent written = new NoteEvent(7, 60, 480, 120, 40);
		Layer pad = layer(new Sustain(true, SustainLength.QUARTER, SustainLength.EIGHTH), held, written);
		Layer expanded = songOf(pad).withSustainsExpanded(pad, FINE);

		assertEquals(List.of(0L, 240L, 480L, 720L), ticksOf(expanded, 60));
		NoteEvent at480 = expanded.notes().stream().filter(note -> note.startTick() == 480L)
			.findFirst().orElseThrow();
		assertEquals(7L, at480.id(), "the strike that would land on the written note is left out");
		assertEquals(40, at480.velocity());
	}

	@Test
	void lengthsResolveAgainstTheSongsFinest() {
		ComposerProject song = songOf(layer(null, note(0)));

		assertEquals(37.5, song.sustainTicks(SustainLength.FINEST, 37.5), 1e-9);
		assertEquals(SongAnalysis.redstoneTickSpan(song) / 2.0,
			song.sustainTicks(SustainLength.GAME_TICK, 37.5), 1e-9);
		assertEquals(480.0, song.sustainTicks(SustainLength.QUARTER, 37.5), 1e-9);
		assertEquals(1920.0, song.sustainTicks(SustainLength.BAR, 37.5), 1e-9);
	}

	/** A song ready to paste is written in redstone ticks, and its lanes say which. */
	@Test
	void aPastableSongsFinestIsItsBuildTick() {
		double repeater = SongAnalysis.redstoneTickSpan(songOf());

		ComposerProject oneLane = songOf(layer(null, note(0), note(Math.round(2 * repeater)),
			note(Math.round(3 * repeater))));
		SongAnalysis oneLaneStats = SongAnalysis.of(oneLane, true);
		assertTrue(oneLaneStats.offGridNotes().isEmpty() && oneLaneStats.lanesNeeded() == 1);
		assertEquals(repeater, finestOf(oneLane), 1e-9, "one lane: a repeater tick");

		ComposerProject twoLanes = songOf(layer(null, note(0), note(Math.round(1.5 * repeater)),
			note(Math.round(3 * repeater))));
		SongAnalysis twoLaneStats = SongAnalysis.of(twoLanes, true);
		assertTrue(twoLaneStats.offGridNotes().isEmpty() && twoLaneStats.lanesNeeded() == 2);
		assertEquals(repeater / 2.0, finestOf(twoLanes), 1e-9, "two lanes: a game tick");
	}

	/** Any other song's Finest is the finest note value it is already written on. */
	@Test
	void anUnpastableSongsFinestIsItsNoteGrid() {
		ComposerProject thirtySeconds = songOf(layer(null, note(0), note(60), note(240), note(480)));
		assertFalse(SongAnalysis.of(thirtySeconds, true).offGridNotes().isEmpty(),
			"the test needs a song off the redstone grid");
		assertEquals(60.0, finestOf(thirtySeconds), 1e-9);

		ComposerProject tripletEighths = songOf(layer(null, note(0), note(160), note(320), note(480)));
		assertEquals(160.0, finestOf(tripletEighths), 1e-9);

		NoteEvent[] sixteenths = new NoteEvent[101];
		for (int index = 0; index < 100; index++) {
			sixteenths[index] = note(index * 120L);
		}
		sixteenths[100] = note(12_007L);
		assertEquals(120.0, finestOf(songOf(layer(null, sixteenths))), 1e-9,
			"one stray in a hundred and one does not drag a sixteenth song down");

		ComposerProject performed = songOf(layer(null, note(0), note(7), note(13), note(29)));
		assertEquals(60.0, finestOf(performed), 1e-9, "nothing holds a performance: a thirty-second");
	}

	@Test
	void anExpandedLayerDoesNotExpandAgain() {
		Layer pad = layer(EVERY_SIXTEENTH, new NoteEvent(1, 60, 0, 960, 90));
		ComposerProject song = songOf(pad);
		Layer expanded = song.withSustainsExpanded(pad, FINE);

		assertFalse(expanded.sustains());
		assertSame(expanded, song.withSustainsExpanded(expanded, FINE));
		Layer off = pad.withSustain(pad.sustainOrDefault().withOn(false));
		assertSame(off, song.withSustainsExpanded(off, FINE), "a layer that does not sustain comes back as it is");
	}

	/** The tick marks are the strikes in the window, not a second answer to where they fall. */
	@Test
	void theTicksInAWindowAreTheStrikesInIt() {
		NoteEvent held = new NoteEvent(1, 60, 37, 2000, 90);
		Strikes strikes = new Strikes(0.0, 47.5, 480.0);
		List<Long> all = new ArrayList<>();
		strikes.forEach(held, 0L, Long.MAX_VALUE, all::add);
		List<Long> window = new ArrayList<>();
		strikes.forEach(held, 700L, 1300L, window::add);

		assertEquals(all.stream().filter(tick -> tick >= 700L && tick <= 1300L).toList(), window);
		assertEquals(all.size() + 1, strikes.soundings(held));
		assertTrue(all.stream().allMatch(tick -> tick >= 37 + 47 && tick < 2037));
	}

	@Test
	void settingsSurviveTheDiskAndOldSongsReadAsOff() {
		Gson gson = new Gson();
		String old = """
			{"name":"Old","ppq":480,"tempoMicrosPerQuarter":500000,"layers":[
			  {"name":"Lead","instrument":"HARP","muted":false,"buildEnabled":true,"visible":true,
			   "notes":[{"id":1,"midiNote":60,"startTick":0,"durationTicks":960,"velocity":96}]}],
			 "activeLayerIndex":0,"nextNoteId":2,"endTick":1920,"speedQuarters":4}
			""";
		Layer loaded = gson.fromJson(old, ComposerProject.class).layers().get(0);
		assertNull(loaded.sustain());
		assertFalse(loaded.sustains());

		ComposerProject song = songOf(layer(new Sustain(true, SustainLength.HALF, SustainLength.GAME_TICK),
			new NoteEvent(1, 60, 0, 960, 90)));
		Layer reloaded = gson.fromJson(gson.toJson(song), ComposerProject.class).layers().get(0);
		assertEquals(new Sustain(true, SustainLength.HALF, SustainLength.GAME_TICK), reloaded.sustain());
	}
}
