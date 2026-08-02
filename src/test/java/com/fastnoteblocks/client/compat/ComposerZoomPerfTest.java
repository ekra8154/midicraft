package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.ComposerProject.Layer;
import com.fastnoteblocks.client.composer.ComposerProject.NoteEvent;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: what one composer frame costs at full zoom-out.
 *
 * <p>Mirrors {@code ComposerScreen}'s render arithmetic rather than running it, so the counts are
 * the ones the screen would produce without needing a window. Counts the quads each phase asks for
 * and times the per-frame work that is not drawing at all.</p>
 */
class ComposerZoomPerfTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = songsDirectory();
	private static final String[] DENSE = {
		// First one is a raw NBS import, unformatted for Minecraft: nearly every note is off grid,
		// which is the worst case for the note pass and the one that was measured at 2.5 fps.
		"sunset-of-seven-suns", "sunset-of-seven-suns-we-did-it", "illit-do-the-dance",
		"hammer-of-justice-2", "golden-brown-2xspeed", "seven-suns"
	};

	// ComposerScreen's layout constants.
	private static final int TOOLBAR_HEIGHT = 34;
	private static final int LAYER_PANEL_WIDTH = 196;
	private static final int PIANO_WIDTH = 48;
	private static final int TIMELINE_RULER_HEIGHT = 24;
	private static final int ROW_HEIGHT = 8;
	private static final int NOTE_TRIGGER_WIDTH = 7;
	private static final int MIN_ROW_HEIGHT = 4;
	private static final int MIN_GRID_PIXEL_SPACING = 4;
	private static final int[] LAYER_COLORS = {
		0xFF35D7E5, 0xFFFFB347, 0xFF9BE564, 0xFFD19BFF, 0xFFFF6B9A,
		0xFF7CA7FF, 0xFFFFE66D, 0xFF8CE0C3, 0xFFFF8C5A, 0xFFC3F584
	};

	/**
	 * The frame the composer is slowest on: zoomed out horizontally <em>and</em> vertically.
	 *
	 * <p>Vertical zoom-out is what the first pass missed. At four pixels a row the roll shows every
	 * pitch at once, so nothing is culled by pitch and the notes spread over a hundred and eleven
	 * rows instead of fifty-five — more rows means shorter runs and more quads, and the GUI charges
	 * for quads quadratically.</p>
	 */
	@Test
	void countsAFullyZoomedOutFrame() throws Exception {
		for (String name : DENSE) {
			if (!Files.isRegularFile(SONGS.resolve(name + ".json"))) {
				continue;
			}
			frame(name + " [row 4px]", load(name), 960, 540, MIN_ROW_HEIGHT);
		}
	}

	@Test
	void countsAFrame() throws Exception {
		for (String name : DENSE) {
			// A probe reports on whatever library it finds. Naming a song it cannot open is not a
			// failure -- these are one person's compositions, not fixtures, and a checkout without
			// them should still be able to run the suite.
			if (!Files.isRegularFile(SONGS.resolve(name + ".json"))) {
				System.out.println(name + " - not in this library, skipped");
				continue;
			}
			ComposerProject song = load(name);
			// GUI scale 2 on a 1920x1080 window, which is what a full-screen composer looks like.
			frame(name, song, 960, 540, ROW_HEIGHT);
		}
	}

	private void frame(String name, ComposerProject song, int width, int height, int rowHeight) {
		int rollX = LAYER_PANEL_WIDTH + PIANO_WIDTH;
		int rollY = TOOLBAR_HEIGHT + 14 + TIMELINE_RULER_HEIGHT;
		int rollWidth = Math.max(40, width - rollX - 8);
		int rollHeight = Math.max(40, height - rollY - 24);
		double ticksPerPixel = Math.max(80.0, Math.max(1L, song.endTick()) * 1.2 / Math.max(1, rollWidth));
		long horizontalScroll = 0L;
		int topMidiNote = 91;

		long analysisStart = System.nanoTime();
		SongAnalysis stats = SongAnalysis.of(song, true);
		long analysisNanos = System.nanoTime() - analysisStart;

		long tracksStart = System.nanoTime();
		List<com.fastnoteblocks.client.FastNoteblocksConfig.SequenceTrack> tracks =
			song.toSequenceTracks(Set.of(), true);
		long tracksNanos = System.nanoTime() - tracksStart;

		long countsStart = System.nanoTime();
		SongBuilder.BlockCounts blocks = SongBuilder.blockCounts(tracks);
		long countsNanos = System.nanoTime() - countsStart;
		// Second run, warm: the first pays class loading.
		countsStart = System.nanoTime();
		SongBuilder.blockCounts(tracks);
		long countsWarmNanos = System.nanoTime() - countsStart;

		// extractNotes
		long firstVisibleTick = Math.max(0L, horizontalScroll - stats.maximumNoteDuration());
		long lastVisibleTick = horizontalScroll + (long) Math.ceil(rollWidth * ticksPerPixel);
		int drawn = 0;
		int fills = 0;
		int warned = 0;
		Set<Long> offGrid = stats.offGrid();
		Set<Long> crowded = stats.crowded();
		NoteCellGrid grid = new NoteCellGrid();
		grid.begin(rollX, rollWidth, NOTE_TRIGGER_WIDTH, rowHeight - 2);
		int layerIndex = -1;
		for (Layer layer : song.layers()) {
			layerIndex++;
			if (!layer.visible()) {
				continue;
			}
			for (NoteEvent note : layer.notes()) {
				if (note.startTick() < firstVisibleTick || note.startTick() > lastVisibleTick) {
					continue;
				}
				int left = rollX + (int) Math.round((note.startTick() - horizontalScroll) / ticksPerPixel);
				int top = rollY + (topMidiNote - note.midiNote()) * rowHeight + 1;
				int right = left + NOTE_TRIGGER_WIDTH;
				int bottom = top + rowHeight - 2;
				if (right <= rollX || left >= rollX + rollWidth
						|| bottom <= rollY || top >= rollY + rollHeight) {
					continue;
				}
				drawn++;
				fills++;
				int flags = 0;
				if (crowded.contains(note.startTick())) {
					flags |= NoteCellGrid.CROWDED;
					fills += 4;
					warned++;
				} else if (offGrid.contains(note.startTick())) {
					flags |= NoteCellGrid.OFF_GRID;
					fills += 4;
					warned++;
				}
				grid.add(left, top, LAYER_COLORS[layerIndex % LAYER_COLORS.length], flags,
					note.midiNote());
			}
		}
		int collapsed = grid.draw((left, top, right, bottom, color) -> {
		});

		// Time the layout loop on its own, warm. In game the whole notes phase measured 380 ms; if
		// this is a millisecond or two then that time is all in the graphics calls, not in here.
		long layoutNanos = Long.MAX_VALUE;
		for (int repeat = 0; repeat < 20; repeat++) {
			long start = System.nanoTime();
			grid.begin(rollX, rollWidth, NOTE_TRIGGER_WIDTH, rowHeight - 2);
			for (Layer layer : song.layers()) {
				if (!layer.visible()) {
					continue;
				}
				for (NoteEvent note : layer.notes()) {
					if (note.startTick() < firstVisibleTick || note.startTick() > lastVisibleTick) {
						continue;
					}
					int left = rollX + (int) Math.round((note.startTick() - horizontalScroll) / ticksPerPixel);
					int top = rollY + (topMidiNote - note.midiNote()) * rowHeight + 1;
					if (left + NOTE_TRIGGER_WIDTH <= rollX || left >= rollX + rollWidth
							|| top + rowHeight - 2 <= rollY || top >= rollY + rollHeight) {
						continue;
					}
					int flags = crowded.contains(note.startTick()) ? NoteCellGrid.CROWDED
						: offGrid.contains(note.startTick()) ? NoteCellGrid.OFF_GRID : 0;
					grid.add(left, top, 0xFF35D7E5, flags, note.midiNote());
				}
			}
			grid.draw((left, top, right, bottom, color) -> {
			});
			layoutNanos = Math.min(layoutNanos, System.nanoTime() - start);
		}

		int chordTicks = stats.chordCounts().size();

		// extractTimeGrid's own lines.
		long measureTicks = Math.max(1L, song.ppq() * 4L);
		long step = Math.max(1L, song.ppq() / 4L);
		while (step / ticksPerPixel < MIN_GRID_PIXEL_SPACING) {
			step *= 2L;
		}
		if (step > measureTicks) {
			step = (step + measureTicks - 1L) / measureTicks * measureTicks;
		}
		long gridLines = (lastVisibleTick - horizontalScroll) / step + 2L;

		System.out.println(String.format(Locale.ROOT,
			"%-44s notes=%5d tpp=%7.1f  |  drawn=%5d quadsWas=%6d quadsNow=%5d (%.0f%% saved)"
				+ " warned=%5d layout=%5.2fms chordTicks=%5d"
				+ "  |  analysis=%5.2fms tracks=%5.2fms blockCounts=%5.2fms(warm %5.2fms) blocks=%d",
			name, stats.totalNotes(), ticksPerPixel, drawn, fills, collapsed,
			100.0 * (fills - collapsed) / Math.max(1, fills), warned, layoutNanos / 1e6, chordTicks,
			analysisNanos / 1e6, tracksNanos / 1e6, countsNanos / 1e6, countsWarmNanos / 1e6,
			blocks.total()));
	}

	/** A worktree has no {@code run} of its own, so fall back to the checkout the client uses. */
	private static Path songsDirectory() {
		Path local = Path.of("run", "config", "fast-noteblocks", "songs");
		if (Files.isDirectory(local)) {
			return local;
		}
		return Path.of("D:", "Documents", "modding", "fast-noteblocks",
			"run", "config", "fast-noteblocks", "songs");
	}

	private static ComposerProject load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			return new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
	}
}
