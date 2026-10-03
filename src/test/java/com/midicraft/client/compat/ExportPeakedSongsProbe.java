package com.midicraft.client.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.midicraft.client.composer.ChordSkips;
import com.midicraft.client.composer.ComposerProject;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Saves a song cut to its first note a tick -- the cut {@code -Dcensus.peak=1} makes -- as a song of
 * its own, so a build the census found can be pasted in game.
 *
 * <p>The cut is made on the game's own reading of the song, after thinning and dedupe, and traced
 * back to the notes in the file by id: each tick keeps the note the census kept and nothing else.
 * The saved file is then read back the way the Paste button reads it and must give the census's
 * notes exactly, or the probe says so. Each build named is then planned and read back, as the game
 * would choose it and with the packed plan forced.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*ExportPeakedSongsProbe" -Dprobe.builds=faded-alan-walker:12x2+24x3
 * </pre>
 */
@Tag("sweep")
class ExportPeakedSongsProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private record Timed(int time, int track, int order, long id, int pitch, String instrument,
			int midiNote, int velocity, String layerInstrument) {
	}

	@Test
	void exportsEachSongCutToOneNoteATick() throws Exception {
		GameSettings.Values game = GameSettings.get();
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		System.out.println();
		System.out.println("==== export " + game.said() + " ====");
		for (String entry : System.getProperty("probe.builds", "").split(",")) {
			if (entry.isBlank()) {
				continue;
			}
			String[] half = entry.strip().split(":");
			String file = half[0];
			ComposerProject song = GameSettings.project(SONGS.resolve(file + ".json"));
			List<Timed> kept = peakedWalk(song, game.thinning());
			List<SongBuilder.EventNote> census = GameSettings.peaked(game.notes(song, mode), 1);
			boolean walkAgrees = kept.size() == census.size();
			for (int index = 0; walkAgrees && index < kept.size(); index++) {
				walkAgrees = kept.get(index).time() == census.get(index).time()
					&& kept.get(index).pitch() == census.get(index).pitch();
			}
			Set<Long> ids = new HashSet<>();
			kept.forEach(note -> ids.add(note.id()));
			Path out = SONGS.resolve(file + "-1-tick.json");
			int written = write(song.name(), out, kept);
			ComposerProject saved = GameSettings.project(out);
			List<SongBuilder.EventNote> reread = game.notes(saved, mode);
			boolean same = reread.size() == census.size();
			for (int index = 0; same && index < reread.size(); index++) {
				SongBuilder.EventNote a = reread.get(index);
				SongBuilder.EventNote b = census.get(index);
				same = a.time() == b.time() && a.pitch() == b.pitch()
					&& a.instrumentBlock().equals(b.instrumentBlock());
			}
			System.out.println("SONG " + file + " -> " + out.getFileName() + "  \"" + saved.name()
				+ "\"  census notes " + census.size() + ", kept ids " + ids.size() + ", file notes "
				+ written + ", walk agrees " + walkAgrees + ", reread identical " + same);
			for (String size : half.length > 1 ? half[1].split("[+]") : new String[0]) {
				String[] wf = size.toLowerCase(java.util.Locale.ROOT).split("x");
				int width = Integer.parseInt(wf[0]);
				int floors = Integer.parseInt(wf[1]);
				for (boolean forced : new boolean[] {false, true}) {
					boolean was = SongBuilder.PACKED_PLAN_ALWAYS_WINS;
					try {
						SongBuilder.PACKED_PLAN_ALWAYS_WINS = forced;
						FaultView.Build built = FaultView.of(out.getFileName().toString(), reread, mode,
							width, floors, game.maxBuildFloors(), game.debugPaste());
						int dead = built.reading().unreachedNotes();
						System.out.println("   " + width + "x" + floors + (forced ? " forced packed" : " as chosen   ")
							+ "  dead " + dead + "  severed " + Math.max(0, built.reading().versions() - 1)
							+ "  wrong " + built.plan().wrongNotes() + "  spanZ " + built.plan().spanZ()
							+ (dead == 0 ? "" : "  break " + FaultView.firstBreak(built)));
					} finally {
						SongBuilder.PACKED_PLAN_ALWAYS_WINS = was;
					}
				}
			}
		}
	}

	/** The game's walk of the song, sorted as the build sorts it, cut to the first note a tick. */
	private static List<Timed> peakedWalk(ComposerProject song, ChordSkips.Rules thinning)
			throws Exception {
		Class<?> visitor = Class.forName("com.midicraft.client.compat.SongBuilder$GameTickVisitor");
		Method walk = SongBuilder.class.getDeclaredMethod("walkGameTicks", ComposerProject.class,
			boolean.class, ChordSkips.Rules.class, visitor);
		walk.setAccessible(true);
		List<ComposerProject.Layer> layers = song.buildLayers(song.dedupesIdentical(), thinning);
		List<Timed> all = new ArrayList<>();
		Object collect = Proxy.newProxyInstance(visitor.getClassLoader(), new Class<?>[] {visitor},
			(proxy, method, args) -> {
				if (!"note".equals(method.getName())) {
					return null;
				}
				ComposerProject.NoteEvent note = (ComposerProject.NoteEvent) args[3];
				int layer = (Integer) args[1];
				all.add(new Timed((Integer) args[0], layer + 1, (Integer) args[2], note.id(),
					note.noteBlockPitch(), (String) args[4], note.midiNote(), note.velocity(),
					layers.get(layer).instrument()));
				return null;
			});
		walk.invoke(null, song, song.dedupesIdentical(), thinning, collect);
		all.sort(Comparator.comparingInt(Timed::time).thenComparingInt(Timed::track)
			.thenComparingInt(Timed::order));
		Map<Integer, Timed> first = new java.util.LinkedHashMap<>();
		for (Timed note : all) {
			first.putIfAbsent(note.time(), note);
		}
		return new ArrayList<>(first.values());
	}

	/**
	 * A fresh song holding exactly these notes: one plain layer per instrument they were built with, no
	 * split, mix or sustain, each note at its own game tick.
	 *
	 * <p>Rebuilt rather than cut from the original, because the original's notes fan out: a split
	 * layer sounds one note on several voices and a mix plays it several times, and the census kept
	 * one of those. Timed so that a composer tick is exactly a game tick -- ppq 20 at sixty a minute,
	 * speed eight eighths -- so no gap rounds: {@code buildDelayGameTicks(n) = n}.</p>
	 */
	private static int write(String name, Path to, List<Timed> kept) throws Exception {
		Map<String, JsonArray> byInstrument = new java.util.LinkedHashMap<>();
		long id = 1;
		int last = 0;
		for (Timed note : kept) {
			JsonObject event = new JsonObject();
			event.addProperty("id", id++);
			event.addProperty("midiNote", note.midiNote());
			event.addProperty("startTick", note.time());
			event.addProperty("durationTicks", 1);
			event.addProperty("velocity", note.velocity());
			byInstrument.computeIfAbsent(note.layerInstrument(), key -> new JsonArray()).add(event);
			last = Math.max(last, note.time());
		}
		JsonArray layers = new JsonArray();
		byInstrument.forEach((instrument, notes) -> {
			JsonObject layer = new JsonObject();
			layer.addProperty("name", instrument);
			layer.addProperty("instrument", instrument);
			layer.addProperty("muted", false);
			layer.addProperty("buildEnabled", true);
			layer.addProperty("visible", true);
			layer.add("mix", new JsonArray());
			layer.add("resting", new JsonArray());
			layer.add("notes", notes);
			layers.add(layer);
		});
		JsonObject song = new JsonObject();
		song.addProperty("name", name + " 1-tick");
		song.addProperty("ppq", 20);
		song.addProperty("tempoMicrosPerQuarter", 1_000_000);
		song.add("layers", layers);
		song.addProperty("activeLayerIndex", 0);
		song.addProperty("nextNoteId", id);
		song.addProperty("endTick", last + 40);
		song.addProperty("speedQuarters", 4);
		song.addProperty("speedEighths", 8);
		try (Writer writer = Files.newBufferedWriter(to)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(song, writer);
		}
		return (int) (id - 1);
	}
}
