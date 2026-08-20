package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * That a two-rail run plays the song it was built from, tick for tick.
 *
 * <p>Nothing else here can say so. {@code verify} checks that every note block has something beside
 * it that should set it off, and a rail satisfies that whether or not the signal ever arrives;
 * {@code wrongNotes} only counts notes a <em>neighbour</em> would sound. A rail is two chains
 * carrying different delays past each other, so the failure to be afraid of is a note landing on the
 * wrong tick, and the reader is the only thing in this repo that would notice.</p>
 */
class RailReadBackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void readsBackEveryOneNoteSongAsItself() throws Exception {
		for (String name : List.of("ultra-ones-mixed", "ultra-twos-mixed", "ultra-threes-mixed", "ultra-gaps-mixed",
			"song-of-storms-but-noteblocks-dont-kill-me", "lady-brown-nujabes")) {
			List<SongBuilder.EventNote> notes = load(name);
			for (int[] size : new int[][] {{16, 2}, {24, 5}, {40, 2}}) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, size[0], size[1]));
				assertEquals("", readBackDifference(plan, notes, name),
					name + " at " + size[0] + " wide over " + size[1]
						+ " floors did not read back as itself");
			}
		}
	}

	/**
	 * How this build differs from the song it was made of, once read back out of the blocks.
	 *
	 * <p>Shared with the grid sweep, which asks the same question of every size the menu offers.
	 * A note that never fires and a note that fires on the wrong tick both show up here; nothing
	 * else in this repo sees either.</p>
	 */
	static String readBackDifference(SongBuilder.PastePlan plan,
			List<SongBuilder.EventNote> notes, String name) {
		NoteMachineReader.Reading reading = readAll(placeInWorld(plan), name);
		String difference = difference(sounds(notes), sounds(reading.project()));
		return reading.unreachedNotes() == 0 ? difference
			: difference + String.format("%n  and %d note blocks were never triggered",
				reading.unreachedNotes());
	}

	private static NoteMachineReader.Reading readAll(Map<BlockPos, BlockState> world, String name) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read(name, new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (Exception broken) {
			throw new IllegalStateException("could not parse " + blockState, broken);
		}
	}

	private record Sound(int time, String instrument, int pitch) implements Comparable<Sound> {
		@Override
		public int compareTo(Sound other) {
			int byTime = Integer.compare(time, other.time);
			int byInstrument = byTime != 0 ? byTime : instrument.compareTo(other.instrument);
			return byInstrument != 0 ? byInstrument : Integer.compare(pitch, other.pitch);
		}
	}

	private static Map<Sound, Integer> sounds(List<SongBuilder.EventNote> notes) {
		Map<Sound, Integer> counts = new TreeMap<>();
		int first = notes.get(0).time();
		for (SongBuilder.EventNote note : notes) {
			counts.merge(new Sound(note.time() - first,
				parse(note.instrumentBlock()).instrument().name(), note.pitch()), 1, Integer::sum);
		}
		return counts;
	}

	private static Map<Sound, Integer> sounds(ComposerProject project) {
		Map<Sound, Integer> counts = new TreeMap<>();
		for (ComposerProject.Layer layer : project.layers()) {
			for (ComposerProject.NoteEvent note : layer.notes()) {
				counts.merge(new Sound(
					(int) (note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK),
					layer.instrument(),
					note.midiNote() - ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE), 1, Integer::sum);
			}
		}
		return counts;
	}

	/** How many sounds are missing, how many are extra, and how many note blocks never fired. */
	static String readBackCensus(SongBuilder.PastePlan plan, List<SongBuilder.EventNote> notes,
			String name) {
		NoteMachineReader.Reading reading = readAll(placeInWorld(plan), name);
		Map<Sound, Integer> expected = sounds(notes);
		Map<Sound, Integer> actual = sounds(reading.project());
		java.util.TreeSet<Sound> all = new java.util.TreeSet<>(expected.keySet());
		all.addAll(actual.keySet());
		int missing = 0;
		int extra = 0;
		for (Sound sound : all) {
			int want = expected.getOrDefault(sound, 0);
			int got = actual.getOrDefault(sound, 0);
			missing += Math.max(0, want - got);
			extra += Math.max(0, got - want);
		}
		return missing + " missing, " + extra + " extra, " + reading.unreachedNotes() + " unreached";
	}

	/** The first dozen sounds the build and the song disagree about, or nothing at all. */
	private static String difference(Map<Sound, Integer> expected, Map<Sound, Integer> actual) {
		java.util.TreeSet<Sound> all = new java.util.TreeSet<>(expected.keySet());
		all.addAll(actual.keySet());
		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (Sound sound : all) {
			int want = expected.getOrDefault(sound, 0);
			int got = actual.getOrDefault(sound, 0);
			if (want == got) {
				continue;
			}
			if (shown++ < 12) {
				text.append(String.format("%n  t=%d %s pitch %d: built %d, read %d",
					sound.time(), sound.instrument(), sound.pitch(), want, got));
			}
		}
		if (shown > 12) {
			text.append(String.format("%n  ... and %d more", shown - 12));
		}
		return text.toString();
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
