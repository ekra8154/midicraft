package com.midicraft.client.compat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Does the oldest paster -- one straight line, no turns -- still build Guardian soundly?
 *
 * <p>{@code PasteMode.LANE} is what used to be called Straight. It has no walls, no folding and no
 * cutting, so none of the machinery every other mode has grown applies to it; the question is only
 * whether the modules it lays still conduct and still sound each note once.</p>
 *
 * <p>Placement numbers cannot answer that on their own, so the plan is built into a block map and
 * read back: {@code unreachedNotes} is the dead-line count and {@code wrongNotes} the doubled-note
 * one.</p>
 */
@Tag("sweep")
class StraightLaneGuardianProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void buildsGuardianInAStraightLine() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		System.out.println("==== Straight (LANE), Guardian, " + guardian.size() + " notes ====");
		long planned = System.currentTimeMillis();
		// Limits given by hand rather than read from config: LANE has no wall to size, and reading
		// the config off disk needs FabricLoader, which is not there headless.
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
			SongBuilder.PasteMode.LANE, new SongBuilder.BuildLimits(4, 44, 3));
		System.out.println("planned in " + (System.currentTimeMillis() - planned) + "ms: "
			+ plan.commands().size() + " blocks, spanX=" + plan.spanX() + " spanZ=" + plan.spanZ()
			+ " height=" + plan.height());
		System.out.println("faults=" + plan.faults().size() + " wrongNotes=" + plan.wrongNotes()
			+ " collisions=" + plan.collisions().size());
		for (String fault : plan.faults().stream().limit(10).toList()) {
			System.out.println("  fault: " + fault);
		}

		Map<BlockPos, BlockState> world = placeInWorld(plan);
		long read = System.currentTimeMillis();
		NoteMachineReader.Reading reading = readAll(world);
		System.out.println("read back in " + (System.currentTimeMillis() - read) + "ms");
		System.out.println("READBACK noteBlocks=" + reading.noteBlocks()
			+ " unreached=" + reading.unreachedNotes()
			+ " versions=" + reading.versions()
			+ " readNotes=" + reading.project().noteCount()
			+ " sourceNotes=" + guardian.size());
		for (BlockPos at : reading.unreachedAt().stream().limit(10).toList()) {
			System.out.println("  unreached: " + at.getX() + " " + at.getY() + " " + at.getZ());
		}
		for (String warning : reading.warnings()) {
			System.out.println("  warning: " + warning);
		}

		// Reaching every note block is not the same as sounding the song: a note read back at the
		// wrong tick is reached and still wrong. So diff the performance, not the count.
		String difference = difference(sounds(guardian), sounds(reading.project()));
		System.out.println("PERFORMANCE " + (difference.isEmpty() ? "matches the song exactly"
			: "differs:" + difference));
	}

	/** One sounding: when, on what, at what pitch. */
	private record Sound(int time, String instrument, int pitch) implements Comparable<Sound> {
		@Override
		public int compareTo(Sound other) {
			return java.util.Comparator.comparingInt(Sound::time)
				.thenComparing(Sound::instrument).thenComparingInt(Sound::pitch)
				.compare(this, other);
		}
	}

	private static java.util.SortedMap<Sound, Integer> sounds(List<SongBuilder.EventNote> notes) {
		java.util.SortedMap<Sound, Integer> counts = new java.util.TreeMap<>();
		int first = notes.get(0).time();
		for (SongBuilder.EventNote note : notes) {
			counts.merge(new Sound(note.time() - first,
				parse(note.instrumentBlock()).instrument().name(), note.pitch()), 1, Integer::sum);
		}
		return counts;
	}

	private static java.util.SortedMap<Sound, Integer> sounds(
			com.midicraft.client.composer.ComposerProject project) {
		java.util.SortedMap<Sound, Integer> counts = new java.util.TreeMap<>();
		for (com.midicraft.client.composer.ComposerProject.Layer layer : project.layers()) {
			for (com.midicraft.client.composer.ComposerProject.NoteEvent note : layer.notes()) {
				counts.merge(new Sound(
					(int) (note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK),
					layer.instrument(),
					note.midiNote()
						- com.midicraft.client.composer.ComposerProject.NOTE_BLOCK_BASE_MIDI_NOTE),
					1, Integer::sum);
			}
		}
		return counts;
	}

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

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(Map<BlockPos, BlockState> world) {
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
		return NoteMachineReader.read("Straight", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
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
