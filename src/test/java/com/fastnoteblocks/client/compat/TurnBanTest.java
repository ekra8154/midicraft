package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Whether the no-stacked-shapes-in-a-turn rule should outlast the turn by one chord.
 *
 * <p>It is the most expensive refusal in a folded build, and the reason given for it does not
 * survive looking at the blocks: a turn's bus comes out of its second bend already running the new
 * lane's way, so the chord after it is in line with what it follows rather than across it.</p>
 *
 * <p>Which is an argument, and arguments about this file have been wrong before. So the machines
 * are read back rather than merely counted -- a shape that saves a column and stops the music is
 * not a saving.</p>
 */
@Tag("sweep")
class TurnBanTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.TURN_BAN_OUTLASTS = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void pricesTheRuleAndReadsTheMachinesBack() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		for (int outlasts = 1; outlasts >= 0; outlasts--) {
			SongBuilder.TURN_BAN_OUTLASTS = outlasts == 1;
			long breaches = 0;
			long guardian = 0;
			long other = 0;
			long breachBlocks = 0;
			long spanZ = 0;
			long spanX = 0;
			long blocks = 0;
			long refusedForTurn = 0;
			long wrong = 0;
			int unreached = 0;
			int mismatched = 0;
			int readBuilds = 0;
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
						spanZ += plan.spanZ();
						spanX += plan.spanX();
						blocks += plan.commands().size();
						wrong += plan.wrongNotes();
						refusedForTurn += plan.padding().getOrDefault("planBusForTurnStackedBus", 0)
							+ plan.padding().getOrDefault("planBusForTurn", 0);
						if (name.equals("deltarune-ch-4-guardian")) {
							guardian += plan.breaches().size();
						} else {
							other += plan.breaches().size();
						}
						// Read back the folded builds, which are the only ones with a turn in them.
						// One width per floor count keeps this affordable while still covering every
						// song and every fold.
						if (floors >= 2 && width == 24) {
							readBuilds++;
							NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
							unreached += reading.unreachedNotes();
							int sounded = 0;
							for (var layer : reading.project().layers()) {
								sounded += layer.notes().size();
							}
							if (sounded != notes.size()) {
								mismatched++;
								System.out.println("TURNBAN mismatch " + name + " w" + width
									+ " f" + floors + " sounded=" + sounded + " of " + notes.size()
									+ " outlasts=" + (outlasts == 1));
							}
						}
					}
				}
			}
			System.out.println("TURNBAN outlasts=" + (outlasts == 1)
				+ " breaches=" + breaches + " (guardian=" + guardian + " other=" + other + ")"
				+ " breachBlocks=" + breachBlocks
				+ " spanZ=" + spanZ + " spanX=" + spanX + " blocks=" + blocks
				+ " refusedForTurn=" + refusedForTurn + " wrong=" + wrong);
			System.out.println("TURNBAN outlasts=" + (outlasts == 1)
				+ " readBuilds=" + readBuilds + " unreached=" + unreached
				+ " mismatched=" + mismatched);
		}
	}

	/**
	 * What the 1,903 wrong notes actually touch, and the smallest build that shows one.
	 *
	 * <p>The A/B says the naive version of this loses. It says nothing about whether the rule is
	 * right everywhere or right in one shape, and the fault message carries the diagnosis already --
	 * it names the side the stray power arrives from, which is the difference between one module
	 * reaching into the next along the lane and the corridor alongside reaching across it.</p>
	 */
	@Test
	void namesWhatTheWrongNotesTouch() throws Exception {
		SongBuilder.TURN_BAN_OUTLASTS = false;
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		Map<String, Integer> bySide = new java.util.TreeMap<>();
		Map<String, Integer> byKind = new java.util.TreeMap<>();
		Map<String, Integer> bySong = new java.util.TreeMap<>();
		String smallest = null;
		int smallestBlocks = Integer.MAX_VALUE;
		String example = null;
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
					int here = 0;
					for (String fault : plan.faults()) {
						if (!fault.startsWith("the note")) {
							continue;
						}
						here++;
						byKind.merge(fault.contains("would sound early") ? "early"
							: fault.contains("would sound again") ? "again" : "never fires",
							1, Integer::sum);
						int from = fault.indexOf(" from the ");
						if (from >= 0) {
							String side = fault.substring(from + 10);
							bySide.merge(side.substring(0, side.indexOf(' ')), 1, Integer::sum);
						}
						if (example == null) {
							example = name + " w" + width + " f" + floors + " :: " + fault;
						}
					}
					if (here > 0) {
						bySong.merge(name, here, Integer::sum);
						if (plan.commands().size() < smallestBlocks) {
							smallestBlocks = plan.commands().size();
							smallest = name + " w" + width + " f" + floors
								+ " (" + plan.commands().size() + " blocks, " + here + " wrong)";
						}
					}
				}
			}
		}
		bySide.forEach((side, count) -> System.out.println("WHERE side " + side + " " + count));
		byKind.forEach((kind, count) -> System.out.println("WHERE kind " + kind + " " + count));
		bySong.forEach((song, count) -> System.out.println("WHERE song "
			+ String.format("%-46s", song) + count));
		System.out.println("WHERE smallest " + smallest);
		System.out.println("WHERE example " + example);
	}

	/** The smallest build that shows one: one floor, one wrong note, thirteen hundred blocks. */
	@Test
	void dumpsTheSmallestWrongNote() throws Exception {
		SongBuilder.TURN_BAN_OUTLASTS = false;
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(SONGS.resolve("i-wonder.json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		SongBuilder.TRACE = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 44, 1));
		SongBuilder.TRACE = false;
		System.out.println("SMALL i-wonder w44 f1: " + plan.commands().size() + " blocks, "
			+ plan.spanX() + " by " + plan.spanZ());
		for (String fault : plan.faults()) {
			System.out.println("SMALL fault: " + fault);
		}
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (Exception broken) {
			throw new IllegalStateException(blockState, broken);
		}
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
		return NoteMachineReader.read("Turn ban", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
