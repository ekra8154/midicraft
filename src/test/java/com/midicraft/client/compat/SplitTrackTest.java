package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The split track: the interleaved paste's two machines as two one-lane tracks either side of a
 * walkway, started from one input.
 *
 * <p>Read back through {@link NoteMachineReader}, game tick by game tick, against the song. The
 * plan's own numbers cannot see the one thing this layout could get wrong that the nested one
 * could not: the input crossing the walkway. A run a cell too long for its wire is a machine that
 * never starts, and a repeater too many on one side is a machine a tick out -- every note on that
 * half of the song landing on the other half's beat, which reads as the song, played wrong.</p>
 */
class SplitTrackTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<SongBuilder.EventNote> notes(String song) throws Exception {
		try (Reader reader = TestSongs.reader(BreachView.songFile(song))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.gameTickEventNotes(project, true);
		}
	}

	/** A one-lane song at double speed: exact, since a repeater-tick song's times are all even. */
	private static List<SongBuilder.EventNote> doubled(String song) throws Exception {
		return notes(song).stream()
			.map(note -> new SongBuilder.EventNote(note.time() / 2, note.trackNumber(),
				note.order(), note.pitch(), note.instrumentBlock()))
			.toList();
	}

	@Test
	void twoTracksEitherSideOfTheWalkwayPlayTheSong() throws Exception {
		playsTheSong(notes("neverending-night-2-lanes"), 24, 1, 4, false);
	}

	@Test
	void aMachineOnTheOddHalfIsFedThroughItsPiston() throws Exception {
		// Machine B opens on the odd half here, so its feed is the double piston with the
		// three-tick repeater in the walkway's last cell.
		SongBuilder.PastePlan plan = playsTheSong(doubled("illit-do-the-dance"), 20, 2, 3, false);
		assertTrue(plan.commands().stream().anyMatch(command -> command.contains("sticky_piston")),
			"the odd machine should be fed through a piston");
	}

	@Test
	void aWideWalkwayRefreshesBothRunsAlike() throws Exception {
		SongBuilder.PastePlan plan = playsTheSong(notes("neverending-night-2-lanes"), 16, 3, 40,
			true);
		assertTrue(plan.padding().getOrDefault("splitInputRefreshes", 0) > 0,
			"forty blocks of walkway is more than one run of dust: " + plan.padding());
	}

	private static SongBuilder.PastePlan playsTheSong(List<SongBuilder.EventNote> notes, int width,
			int floors, int walkway, boolean startTop) {
		long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
		assertTrue(evens > 0 && evens < notes.size(),
			"a split build needs both halves of the tick: " + evens + " of " + notes.size());
		SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
			new BlockPos(0, 64, 0), Direction.EAST, notes,
			new SongBuilder.BuildLimits(16, width, floors, startTop, 0, -1, -1, walkway),
			SongBuilder.WalkStart.HEAD);
		assertEquals(walkway, plan.padding().getOrDefault("splitTrackWalkway", -1),
			"the walkway asked for: " + plan.padding());
		assertEquals(0, plan.padding().getOrDefault("splitInputUnshared", 0)
			+ plan.padding().getOrDefault("splitInputNoRoom", 0), "both machines on one input");
		assertEquals(1, plan.commands().stream()
			.filter(command -> command.contains("minecraft:oak_button")).count(),
			"one way in for both machines");
		assertEquals(0, plan.wrongNotes(), "wrong notes: " + plan.faults());
		assertEquals(0, plan.missingNotes(), "missing notes: " + plan.faults());
		// Each track the width asked for, and the walkway between them.
		assertTrue(plan.spanX() <= 2 * width + walkway,
			"span " + plan.spanX() + " for two " + width + "-wide tracks and " + walkway + " of walkway");

		Map<BlockPos, BlockState> world = new HashMap<>();
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			BlockPos at = new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
				Integer.parseInt(token[3]));
			String block = token[4].substring(0, token[4].length() - " replace".length());
			// A lever for the button, which a bare bootstrap cannot find through its block tag.
			if (block.startsWith("minecraft:oak_button")) {
				block = "minecraft:lever[face=floor,facing=east,powered=true]";
			}
			world.put(at, parse(block));
			min[0] = Math.min(min[0], at.getX());
			min[1] = Math.min(min[1], at.getY());
			min[2] = Math.min(min[2], at.getZ());
			max[0] = Math.max(max[0], at.getX());
			max[1] = Math.max(max[1], at.getY());
			max[2] = Math.max(max[2], at.getZ());
		}
		NoteMachineReader.Reading reading = NoteMachineReader.read("Split",
			new BlockPos(min[0] - 1, min[1] - 1, min[2] - 1),
			new BlockPos(max[0] + 1, max[1] + 1, max[2] + 1),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
		assertEquals(0, reading.unreachedNotes(), "a machine's wire died: " + reading.report());
		assertEquals(1, reading.versions(), "one performance, not a machine each: "
			+ reading.report());

		// The song as written and the song as read, counted per game tick from the first note.
		TreeMap<Integer, Integer> written = new TreeMap<>();
		int firstWritten = notes.stream().mapToInt(SongBuilder.EventNote::time).min().orElse(0);
		for (SongBuilder.EventNote note : notes) {
			written.merge(note.time() - firstWritten, 1, Integer::sum);
		}
		TreeMap<Integer, Integer> read = new TreeMap<>();
		long firstRead = reading.project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(ComposerProject.NoteEvent::startTick).min().orElse(0);
		reading.project().layers().stream().flatMap(layer -> layer.notes().stream())
			.forEach(note -> read.merge((int) ((note.startTick() - firstRead)
				/ NoteMachineReader.TICKS_PER_GAME_TICK), 1, Integer::sum));
		int off = 0;
		StringBuilder first = new StringBuilder();
		for (int tick : new java.util.TreeSet<>(java.util.stream.Stream.concat(
				written.keySet().stream(), read.keySet().stream()).toList())) {
			int want = written.getOrDefault(tick, 0);
			int got = read.getOrDefault(tick, 0);
			if (want != got) {
				off += Math.abs(want - got);
				if (first.length() < 200) {
					first.append(" t").append(tick).append(' ').append(got).append('/').append(want);
				}
			}
		}
		assertEquals(0, off, "notes read on another game tick than written (read/written):"
			+ first);
		return plan;
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}
}
