package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * A cut whose near half is the head and nothing else, with the whole tail past the staircase.
 *
 * <p>The claim under test is that this costs the descent nothing. A near half ending on a bus hands
 * the spiral its wire from two levels up; a near half ending on the handover hands it from one, in
 * the column the spiral's own first rung stands in. If that is right the spiral is still four cells.
 * Reasoning about levels in this file has been wrong before, so the first test dumps the blocks and
 * the rest read the machine back.</p>
 */
class HeadOnlyNearHalfTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.HEAD_ONLY_NEAR_HALF = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	/** A run of chords too big to fit, on a corridor narrow enough that the near half is just a head. */
	private static List<SongBuilder.EventNote> bigChords(int size, int count) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		for (int event = 0; event < count; event++) {
			for (int index = 0; index < size; index++) {
				notes.add(new SongBuilder.EventNote(event + 1, 1 + index % 3, index,
					index % 25, "minecraft:air"));
			}
		}
		return notes;
	}

	/**
	 * Finds a real build that contains a head-only cut and prints it in build order.
	 *
	 * <p>Build order is a probe of its own: the stretch between one repeater and the next is the
	 * wire run between them, and the levels can be read off rather than derived. Every derivation of
	 * these levels by hand in this file has been wrong.</p>
	 */
	@Test
	@Tag("sweep")
	void dumpsAHeadOnlyCutInBuildOrder() throws Exception {
		SongBuilder.HEAD_ONLY_NEAR_HALF = true;
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
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
			for (int floors = 2; floors <= 4; floors++) {
				for (int width = 12; width <= 24; width += 4) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					if (!plan.padding().containsKey("planStackedSplitHeadOnly")) {
						continue;
					}
					System.out.println("HEADONLY found " + name + " w" + width + " f" + floors
						+ " headOnly=" + plan.padding().get("planStackedSplitHeadOnly")
						+ " of " + plan.padding().getOrDefault("planStackedSplitDescent", 0)
						+ " descents");
					// The commands are in build order, so the stretch between one repeater and the
					// next is the wire run between them. A run past fifteen is dead wire, and dead
					// wire is what a machine that stops sounding looks like from here.
					int dust = 0;
					int longest = 0;
					for (String command : plan.commands()) {
						String block = command.split(" ")[4];
						if (block.startsWith("minecraft:redstone_wire[")) {
							continue;
						}
						if (block.startsWith("minecraft:redstone_wire")) {
							dust++;
						} else if (block.startsWith("minecraft:repeater")) {
							longest = Math.max(longest, dust);
							dust = 0;
						}
					}
					System.out.println("HEADONLY longest wire run " + longest
						+ " (fifteen is the most a repeater reaches)");
					NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
					int sounded = 0;
					for (var layer : reading.project().layers()) {
						sounded += layer.notes().size();
					}
					System.out.println("HEADONLY unreached=" + reading.unreachedNotes()
						+ " sounded=" + sounded + " of " + notes.size()
						+ " faults=" + plan.faults().size()
						+ " breaches=" + plan.breaches().size());
					// And the same song and settings with the window shut. If this is broken too
					// then the head-only cut is not what broke it, and every conclusion above about
					// the shape is about the wrong thing.
					SongBuilder.HEAD_ONLY_NEAR_HALF = false;
					SongBuilder.PastePlan without = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					NoteMachineReader.Reading before = readAll(placeInWorld(without));
					int soundedBefore = 0;
					for (var layer : before.project().layers()) {
						soundedBefore += layer.notes().size();
					}
					System.out.println("HEADONLY control (window shut) unreached="
						+ before.unreachedNotes() + " sounded=" + soundedBefore
						+ " of " + notes.size() + " descents="
						+ without.padding().getOrDefault("planStackedSplitDescent", 0));
					// The cut records where it handed over, so the spiral can be found rather than
					// counted from the beginning of the build.
					BlockPos turn = SongBuilder.HEAD_ONLY_AT;
					System.out.println("HEADONLY spiral at " + turn.getX() + " " + turn.getY()
						+ " " + turn.getZ());
					Map<BlockPos, String> world = new HashMap<>();
					for (String command : plan.commands()) {
						String[] parts = command.split(" ");
						world.put(new BlockPos(Integer.parseInt(parts[1]),
							Integer.parseInt(parts[2]), Integer.parseInt(parts[3])), parts[4]);
					}
					for (int z = turn.getZ() - 1; z <= turn.getZ() + 1; z++) {
						System.out.println("HEADONLY --- z=" + z);
						for (int y = turn.getY() + 2; y >= turn.getY() - 5; y--) {
							StringBuilder row = new StringBuilder("HEADONLY y=" + y + " ");
							for (int x = turn.getX() - 9; x <= turn.getX() + 3; x++) {
								String block = world.get(new BlockPos(x, y, z));
								row.append(String.format("%-10s",
									block == null ? "." : block.replace("minecraft:", "")
										.replaceAll("\\[.*", "")));
							}
							System.out.println(row);
						}
					}
					return;
				}
			}
		}
		System.out.println("HEADONLY no build in the library contains one");
	}

	/** No run of wire past what a repeater reaches, which is what a wrong level shows up as. */
	@Test
	void leavesNoDeadRun() {
		for (int floors = 2; floors <= 4; floors++) {
			for (int width = 12; width <= 24; width += 4) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					bigChords(20, 30), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, width, floors));
				int dust = 0;
				int longest = 0;
				for (String command : plan.commands()) {
					String block = command.split(" ")[4];
					if (block.startsWith("minecraft:redstone_wire[")) {
						continue;
					}
					if (block.startsWith("minecraft:redstone_wire")) {
						dust++;
					} else if (block.startsWith("minecraft:repeater")) {
						longest = Math.max(longest, dust);
						dust = 0;
					}
				}
				assertTrue(longest <= 15, "f" + floors + " w" + width + ": a run of " + longest
					+ " blocks of wire is longer than the fifteen a repeater reaches");
			}
		}
	}

	/** And the real library still reads back, with the window measured against being shut. */
	@Test
	@Tag("sweep")
	void pricesTheWindowAndReadsBack() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		for (int on = 0; on <= 1; on++) {
			SongBuilder.HEAD_ONLY_NEAR_HALF = on == 1;
			long breaches = 0;
			long guardian = 0;
			long other = 0;
			long breachBlocks = 0;
			long spanZ = 0;
			long cuts = 0;
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
				for (int floors = 1; floors <= 8; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						breaches += plan.breaches().size();
						breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						spanZ += plan.spanZ();
						cuts += plan.padding().getOrDefault("planStackedSplitDescent", 0)
							+ plan.padding().getOrDefault("planStackedSplitClimb", 0);
						if (name.equals("deltarune-ch-4-guardian")) {
							guardian += plan.breaches().size();
						} else {
							other += plan.breaches().size();
						}
						// Every build that contains a cut at all -- the shape only differs there.
						if (plan.padding().containsKey("planStackedSplitDescent")
								|| plan.padding().containsKey("planStackedSplitClimb")) {
							readBuilds++;
							NoteMachineReader.Reading reading = readAll(placeInWorld(plan));
							unreached += reading.unreachedNotes();
							int sounded = 0;
							for (var layer : reading.project().layers()) {
								sounded += layer.notes().size();
							}
							if (sounded != notes.size()) {
								mismatched++;
								System.out.println("HEADONLY mismatch " + name + " w" + width
									+ " f" + floors + " sounded=" + sounded + " of " + notes.size());
							}
						}
					}
				}
			}
			System.out.println("HEADONLY " + (on == 1 ? "allowed" : "refused")
				+ " breaches=" + breaches + " (guardian=" + guardian + " other=" + other + ")"
				+ " breachBlocks=" + breachBlocks + " spanZ=" + spanZ + " cuts=" + cuts
				+ " || readBuilds=" + readBuilds + " unreached=" + unreached
				+ " mismatched=" + mismatched);
			assertEquals(0, unreached, "note blocks the signal never got to");
			assertEquals(0, mismatched, "builds that did not read back as their song");
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
		return NoteMachineReader.read("Head only", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
