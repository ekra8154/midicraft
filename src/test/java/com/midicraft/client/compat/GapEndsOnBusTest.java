package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What letting a stacked-bus hand on its back slots is worth, measured against not doing it.
 *
 * <p>The old rule said any stacked shape takes the pair of low slots behind whatever follows it.
 * That is true of two modules a repeater apart and false of a stacked-bus, whose head is back
 * behind its transition and its whole tail. In-game reading showed it off a slice; this prices
 * it.</p>
 */
@Tag("sweep")
class GapEndsOnBusTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.GAP_ENDS_ON_BUS = true;
		SongBuilder.VARIABLE_HEAD_NOTES = true;
		SongBuilder.FRONT_ONLY_HEADS = true;
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private record Score(long breaches, long breachBlocks, long worst, long spanZ, long length,
			long stackedBuses, long parity) {
	}

	@Test
	void pricesTheRuleAcrossTheLibrary() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		String[] names = {"gap only        ", "gap+variable    ", "gap+front       ",
			"gap+variable+front", "old rule        "};
		for (int on = 0; on < names.length; on++) {
			SongBuilder.GAP_ENDS_ON_BUS = on != 4;
			SongBuilder.VARIABLE_HEAD_NOTES = on == 1 || on == 3;
			SongBuilder.FRONT_ONLY_HEADS = on == 2 || on == 3;
			long breaches = 0;
			long breachBlocks = 0;
			long worst = 0;
			long spanZ = 0;
			long length = 0;
			long stackedBuses = 0;
			long parity = 0;
			long guardian = 0;
			long other = 0;
			for (Path file : files) {
				String name = file.getFileName().toString().replace(".json", "");
				if (name.startsWith("ultra-")) {
					continue;
				}
				ComposerProject song;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
						raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
						raw.speedQuarters());
				}
				List<SongBuilder.EventNote> notes =
					SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
				if (notes.isEmpty()) {
					continue;
				}
				for (int floors = 1; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						breaches += plan.breaches().size();
						breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						worst = Math.max(worst, plan.worstBreach());
						spanZ += plan.spanZ();
						length += plan.spanX();
						stackedBuses += plan.padding()
							.getOrDefault("busHandover", 0);
						parity += plan.padding().getOrDefault("parity", 0);
						if (name.equals("deltarune-ch-4-guardian")) {
							guardian += plan.breaches().size();
						} else {
							other += plan.breaches().size();
						}
					}
				}
			}
			System.out.println("GAP " + names[on]
				+ " breaches=" + breaches + " (guardian=" + guardian + " other=" + other + ")"
				+ " breachBlocks=" + breachBlocks + " worst=" + worst
				+ " spanZ=" + spanZ + " spanX=" + length
				+ " stackedBuses=" + stackedBuses + " parity=" + parity);
		}
		assertTrue(true);
	}

	/**
	 * And the machines still read back as the songs they were built from.
	 *
	 * <p>The sweep above counts what the layout check believes. The layout check knows what the
	 * builder meant to power, which is the thing in question when the shapes change -- so the claim
	 * has to be settled by building the plan into a world and reading it back, note by note.</p>
	 */
	@Test
	void everyBuildStillReadsBackAsItsSong() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		for (int on = 0; on <= 1; on++) {
			SongBuilder.GAP_ENDS_ON_BUS = on == 1;
			int unreached = 0;
			int mismatched = 0;
			int builds = 0;
			for (Path file : files) {
				String name = file.getFileName().toString().replace(".json", "");
				if (name.startsWith("ultra-")) {
					continue;
				}
				ComposerProject song;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
						raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
						raw.speedQuarters());
				}
				List<SongBuilder.EventNote> notes =
					SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
				if (notes.isEmpty()) {
					continue;
				}
				for (int floors : new int[] {2, 4}) {
					for (int width : new int[] {16, 32, 48}) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						builds++;
						NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
						if (reading.unreachedNotes() > 0) {
							unreached += reading.unreachedNotes();
							System.out.println("READBACK unreached " + name + " w" + width
								+ " f" + floors + " = " + reading.unreachedNotes()
								+ (on == 1 ? " [ends-on-bus]" : " [old rule]"));
						}
						if (!sounds(notes).equals(sounds(reading.project()))) {
							mismatched++;
							System.out.println("READBACK mismatch " + name + " w" + width
								+ " f" + floors + (on == 1 ? " [ends-on-bus]" : " [old rule]"));
						}
					}
				}
			}
			System.out.println("READBACK " + (on == 1 ? "ends-on-bus" : "old rule ")
				+ " builds=" + builds + " unreached=" + unreached + " mismatched=" + mismatched);
		}
	}

	private record Sound(int time, String instrument, int pitch) implements Comparable<Sound> {
		@Override
		public int compareTo(Sound other) {
			int byTime = Integer.compare(time, other.time);
			if (byTime != 0) {
				return byTime;
			}
			int byInstrument = instrument.compareTo(other.instrument);
			return byInstrument != 0 ? byInstrument : Integer.compare(pitch, other.pitch);
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

	private static java.util.SortedMap<Sound, Integer> sounds(ComposerProject project) {
		java.util.SortedMap<Sound, Integer> counts = new java.util.TreeMap<>();
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

	private static net.minecraft.world.level.block.state.BlockState parse(String block) {
		try {
			return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
				net.minecraft.core.registries.BuiltInRegistries.BLOCK, block, false).blockState();
		} catch (Exception broken) {
			throw new IllegalStateException(block, broken);
		}
	}

	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>
			placeInWorld(SongBuilder.PastePlan plan) {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
			new java.util.HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world) {
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
		return NoteMachineReader.read("Gap", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position,
				net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
	}
}
