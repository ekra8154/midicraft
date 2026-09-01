package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The song for standing in the two-rail shapes: everything harp, every gap two ticks, chords
 * mostly of one and two with the occasional big one.
 *
 * <p>Written to bait runs into existing rather than to be hard. A run wants three consecutive
 * chords of three or fewer, and the library has almost nothing like that -- Guardian lays nought
 * rail columns at every size because only 78 of its 1,556 chords are small enough and they never
 * come three in a row. Weighting the sizes so that more than half are one or two puts long
 * stretches of railable chords everywhere, and the bigger ones break them up into the shapes that
 * matter: a stacked bus arriving mid-run, a run that has to close, a run opening again after one.</p>
 *
 * <p>All harp on purpose. The centre of a path column is the cell the wire runs through, so it
 * sounds harp whatever was meant and only a harp note can take it -- an all-harp song is therefore
 * the one where every centre is available, which is the best case for the shape and the right place
 * to see it working before asking what it does with instruments in the way.</p>
 *
 * <p>Two-tick gaps throughout because a rail repeater carries {@code t(k+1) - t(k-1)}, so a uniform
 * gap of two asks it for four every time -- the largest a single repeater holds. Nothing here needs
 * a blank to hop a pair of gaps, so a blank that appears is the floor rail refusing a chord rather
 * than the timing.</p>
 */
@Tag("sweep")
class WriteRailBaitSongTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** Composer ticks per redstone tick at ppq 480, tempo 500000 and speed 4. Measured, not derived. */
	private static final long TICK = 96L;

	/** Semitones above {@link ComposerProject#NOTE_BLOCK_BASE_MIDI_NOTE}, two octaves of a major scale. */
	private static final int[] SCALE = {0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24};

	/**
	 * How often each chord size comes up, out of a hundred.
	 *
	 * <p>Index is the chord size, so nought is unused. Fifty-five in every hundred are one or two,
	 * which is what keeps runs alive; the rest thin out towards ten so that every size the ultra
	 * shapes care about turns up somewhere -- a small module, a stacked chord, a stacked bus with a
	 * short tail, and one with a tail long enough to stay a bus.</p>
	 */
	private static final int[] WEIGHTS = {0, 30, 25, 8, 7, 6, 6, 5, 5, 4, 4};

	private static int chordSize(Random random) {
		int roll = random.nextInt(100);
		int running = 0;
		for (int size = 1; size < WEIGHTS.length; size++) {
			running += WEIGHTS[size];
			if (roll < running) {
				return size;
			}
		}
		return 1;
	}

	@Test
	void writesTheRailBaitSong() throws Exception {
		String name = "ultra-rails-harp-gap2";
		Random random = new Random(20260815L);
		List<ComposerProject.NoteEvent> notes = new ArrayList<>();
		long tick = 0;
		long id = 1;
		int events = 600;
		for (int index = 0; index < events; index++) {
			int size = chordSize(random);
			for (int note = 0; note < size; note++) {
				// Distinct degrees within the chord, and deliberately not the up-and-down walk the
				// other synthetic songs use. That walk folds back on itself, and two notes of one
				// chord landing on the same pitch would be deduped away -- the live config builds
				// with dedupe on -- so a chord of ten would quietly arrive as a chord of nine.
				notes.add(new ComposerProject.NoteEvent(id++,
					ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE
						+ SCALE[(index + note * 2) % SCALE.length],
					tick * TICK, TICK, 96));
			}
			tick += 2;
		}
		ComposerProject song = new ComposerProject(name.replace('-', ' '),
			ComposerProject.DEFAULT_PPQ, ComposerProject.DEFAULT_TEMPO_MICROS_PER_QUARTER,
			List.of(new ComposerProject.Layer("harp", "HARP", false, true, true, notes)),
			0, id, (tick + 1) * TICK, ComposerProject.DEFAULT_SPEED_QUARTERS);
		Files.writeString(SONGS.resolve(name + ".json"), new Gson().toJson(song));

		// Read back off the sequence the game will actually build, not off the layer. Dedupe is on in
		// the live config, so the layer and the build disagree by up to a sixth on a real song -- and
		// a chord that arrived smaller than it was written is exactly what this song must not have.
		List<SongBuilder.EventNote> built =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		Map<Integer, Integer> perTick = new TreeMap<>();
		for (SongBuilder.EventNote note : built) {
			perTick.merge(note.time(), 1, Integer::sum);
		}
		Map<Integer, Integer> histogram = new TreeMap<>();
		for (int size : perTick.values()) {
			histogram.merge(size, 1, Integer::sum);
		}
		int railable = (int) perTick.values().stream().filter(size -> size <= 3).count();
		System.out.println("WROTE " + name + " notes=" + built.size()
			+ " chords=" + perTick.size() + " railable(<=3)=" + railable
			+ " largest=" + perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0));
		System.out.println("   sizes " + histogram);
		// The number the song exists for: how many chords sit in a stretch of three or more railable
		// ones, which is the shortest stretch a run is worth opening for.
		int inARow = 0;
		int longest = 0;
		int covered = 0;
		List<Integer> sizes = new ArrayList<>(perTick.values());
		for (int size : sizes) {
			inARow = size <= 3 ? inARow + 1 : 0;
			longest = Math.max(longest, inARow);
			if (inARow == 3) {
				covered += 3;
			} else if (inARow > 3) {
				covered++;
			}
		}
		System.out.println("   chords inside a runnable stretch of 3+: " + covered
			+ " of " + sizes.size() + ", longest stretch " + longest);
	}
}
