package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The smallest {@code /midicraft paste} that leaves a run of wire past fifteen.
 *
 * <p>Guardian shows the fault at eighty-six thousand blocks, which is not something to stand in
 * front of. The shape to find is the same one: a run that crosses more than one staircase, or a
 * staircase followed by enough bus, without a repeater in between.</p>
 *
 * <p>A dead line is a length, so unlike a breach it is safe to look for with a seeded walk -- the
 * seed moves where the wall is, and this does not ask about walls.</p>
 */
@Tag("sweep")
class DeadLineReproSearchTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private record Hit(String command, int blocks, int longest, String where) implements
			Comparable<Hit> {
		@Override
		public int compareTo(Hit other) {
			return Integer.compare(blocks, other.blocks);
		}
	}

	/** The longest run and where it passes fifteen, read off the commands in build order. */
	private static int[] longestRun(SongBuilder.PastePlan plan, BlockPos[] diesAt) {
		int longest = 0;
		int dust = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			String block = parts[4];
			if (block.startsWith("minecraft:redstone_wire")) {
				dust++;
				if (dust > longest) {
					longest = dust;
				}
				if (dust == 16 && diesAt[0] == null) {
					diesAt[0] = new BlockPos(Integer.parseInt(parts[1]),
						Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
				}
			} else if (block.startsWith("minecraft:repeater")) {
				dust = 0;
			}
		}
		return new int[] {longest};
	}

	@Test
	void findsTheSmallestDeadLine() {
		List<Hit> hits = new ArrayList<>();
		List<String> specs = List.of(
			"18@1 18@1", "22@1 22@1", "24@1 24@1",
			"18@1 4@1 18@1", "22@1 2@1 22@1", "30@1 30@1",
			"12@1 12@1 12@1", "20@1 20@1 20@1", "24@1 6@1 24@1",
			"16@1 16@1 16@1 16@1", "22@1 22@1 22@1");
		for (String spec : specs) {
			for (String shape : List.of("up", "down", "flat")) {
				for (int floors = 2; floors <= 4; floors++) {
					for (int width = 12; width <= 24; width += 4) {
						for (int cols = 2; cols <= width - 2; cols += 2) {
							SongBuilder.PastePlan plan;
							try {
								plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
									DebugChords.notes(DebugChords.parse(spec,
										DebugChords.DEFAULT_GAP)),
									SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
									new SongBuilder.BuildLimits(4, width, floors),
									seed(shape, floors, width, cols));
							} catch (RuntimeException refused) {
								continue;
							}
							BlockPos[] diesAt = {null};
							int longest = longestRun(plan, diesAt)[0];
							if (longest <= 15) {
								continue;
							}
							hits.add(new Hit("/midicraft paste " + width + " " + floors + " "
								+ shape + " " + cols + " " + spec,
								plan.commands().size(), longest,
								diesAt[0] == null ? "?" : diesAt[0].getX() + " " + diesAt[0].getY()
									+ " " + diesAt[0].getZ()));
						}
					}
				}
			}
		}
		hits.sort(null);
		System.out.println("DEADREPRO found " + hits.size());
		for (Hit hit : hits.subList(0, Math.min(8, hits.size()))) {
			System.out.println("DEADREPRO " + hit.command());
			System.out.println("DEADREPRO    " + hit.blocks() + " blocks, longest run "
				+ hit.longest() + ", dies at " + hit.where());
		}
	}

	/** Whether the chosen repro is the branch's doing or was there already, and what it looks like. */
	@Test
	void showsTheChosenRepro() throws Exception {
		for (int four = 1; four >= 0; four--) {
			SongBuilder.UNIVERSAL_FOUR_DESCENT = four == 1;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse("22@1 22@1", DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 16, 2), seed("up", 2, 16, 12));
			BlockPos[] diesAt = {null};
			int longest = longestRun(plan, diesAt)[0];
			System.out.println("PICK four=" + (four == 1) + " blocks=" + plan.commands().size()
				+ " longestRun=" + longest
				+ " dies at " + (diesAt[0] == null ? "-"
					: diesAt[0].getX() + " " + diesAt[0].getY() + " " + diesAt[0].getZ()));
			for (String fault : plan.faults()) {
				System.out.println("PICK fault: " + fault);
			}
			if (four == 1 && diesAt[0] != null) {
				java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
					new java.util.HashMap<>();
				for (String command : plan.commands()) {
					String[] parts = command.split(" ");
					world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
						Integer.parseInt(parts[3])),
						net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
							net.minecraft.core.registries.BuiltInRegistries.BLOCK, parts[4], false)
							.blockState());
				}
				System.out.println(AsciiDiagram.render(
					position -> world.getOrDefault(position,
						net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),
					diesAt[0].offset(-8, -6, -3), diesAt[0].offset(4, 3, 3),
					AsciiDiagram.View.SOUTH, AsciiDiagram.Shape.CODE));
			}
		}
		SongBuilder.UNIVERSAL_FOUR_DESCENT = true;
	}

	/** The same seed {@link DebugCommands} builds, so the printed command is the one that runs. */
	private static SongBuilder.WalkStart seed(String shape, int floors, int width, int cols) {
		int column = Math.max(0, width - 2 - cols);
		return switch (shape) {
			case "up" -> new SongBuilder.WalkStart(column, 0, 1, false);
			case "down" -> new SongBuilder.WalkStart(column, floors - 1, -1, false);
			default -> new SongBuilder.WalkStart(column, floors - 1, 1, false);
		};
	}
}
