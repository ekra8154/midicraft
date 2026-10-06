package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * The dead wire, read out of the blocks rather than guessed at.
 *
 * <p>Eight wide over two floors, pasted at 0 64 0 -- their exact paste, so that what this prints
 * and what they are standing in front of are the same build. A dead wire is not a missed note: the
 * rest of the song stops behind it, so the first one in the chain is the only one worth looking at
 * and everything after it is that one's shadow.</p>
 */
@Tag("sweep")
class RailDeadWireProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** The build under the glass. Whichever one is leaving the most of itself silent today. */
	private static final String SONG = "all-of-the-lights-kanye-west";
	private static final int WIDTH = 16;
	private static final int FLOORS = 3;

	@Test
	void dumpsWhereTheChainStops() throws Exception {
		List<SongBuilder.EventNote> notes = load(SONG);
		// The marking is what makes it visible in game and what makes it invisible here: a sea lantern
		// is not a note block, so a marked build reads back as having reached everything it has left.
		SongBuilder.MARK_UNREACHED = false;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, WIDTH, FLOORS));
		SongBuilder.MARK_UNREACHED = true;
		Map<BlockPos, BlockState> world = placeInWorld(plan);
		NoteMachineReader.Reading reading = readAll(world);
		for (String fault : plan.faults()) {
			System.out.println("PROBE fault: " + fault);
		}
		System.out.println("PROBE " + reading.unreachedNotes() + " never triggered of "
			+ notes.size() + " notes");
		System.out.println("PROBE reading: " + reading.report());
		if (reading.unreachedAt().isEmpty()) {
			return;
		}
		// Walk order, not any order in space. A dead wire is a break with everything the walk laid
		// after it in its shadow, so the one that matters is the earliest the walk placed.
		Map<BlockPos, Integer> laid = new HashMap<>();
		int index = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			laid.putIfAbsent(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), index++);
		}
		List<BlockPos> unreached = reading.unreachedAt().stream()
			.sorted((a, b) -> Integer.compare(laid.getOrDefault(a, Integer.MAX_VALUE),
				laid.getOrDefault(b, Integer.MAX_VALUE))).toList();
		System.out.println("PROBE unreached in walk order: " + unreached.stream().limit(12)
			.map(at -> at.getX() + " " + at.getY() + " " + at.getZ()).toList());
		BlockPos first = unreached.get(0);
		// The live frontier: the last note the signal did get to before the first it did not. A run
		// reads clean built on its own, so what stopped this one is upstream of it rather than in it,
		// and the frontier is where upstream ends.
		Set<BlockPos> dead = Set.copyOf(reading.unreachedAt());
		System.out.println("PROBE the last notes reached before the break:");
		for (Map.Entry<BlockPos, Integer> note : laid.entrySet().stream()
				.filter(entry -> world.get(entry.getKey()).is(
					net.minecraft.world.level.block.Blocks.NOTE_BLOCK))
				.filter(entry -> entry.getValue() < laid.get(first))
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
				.limit(4).toList()) {
			System.out.println("  #" + note.getValue() + "  " + note.getKey().getX() + " "
				+ note.getKey().getY() + " " + note.getKey().getZ()
				+ (dead.contains(note.getKey()) ? "  NEVER TRIGGERED" : "  reached"));
		}
		System.out.println("PROBE commands around " + first.getX() + " " + first.getY() + " "
			+ first.getZ() + ", in the order the walk laid them:");
		int order = 0;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			int x = Integer.parseInt(parts[1]);
			int y = Integer.parseInt(parts[2]);
			int z = Integer.parseInt(parts[3]);
			order++;
			if (Math.abs(z - first.getZ()) <= 2 && y >= first.getY() - 7 && y <= first.getY() + 2
					&& x >= first.getX() - 2 && x <= first.getX() + 14) {
				System.out.println("  #" + order + "  " + x + " " + y + " " + z + "  " + parts[4]);
			}
		}
		// The break as a picture rather than as a list of setblocks. AsciiDiagram is what the machine
		// reader's own command prints, so this is the same view as standing in front of it --
		// which matters when the client cannot be launched to go and look.
		BlockPos live = laid.entrySet().stream()
			.filter(entry -> world.get(entry.getKey()).is(Blocks.NOTE_BLOCK))
			.filter(entry -> !dead.contains(entry.getKey()))
			.filter(entry -> entry.getValue() < laid.get(first))
			.max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(first);
		System.out.println();
		System.out.println("PROBE the last live note is at " + live.getX() + " " + live.getY() + " "
			+ live.getZ() + " and the first dead one at " + first.getX() + " " + first.getY() + " "
			+ first.getZ());
		BlockPos from = new BlockPos(
			Math.min(live.getX(), first.getX()) - 3, Math.min(live.getY(), first.getY()) - 3,
			Math.min(live.getZ(), first.getZ()) - 2);
		BlockPos to = new BlockPos(
			Math.max(live.getX(), first.getX()) + 9, Math.max(live.getY(), first.getY()) + 2,
			Math.max(live.getZ(), first.getZ()) + 2);
		if (AsciiDiagram.volume(from, to) <= AsciiDiagram.MAX_BLOCKS) {
			System.out.println(AsciiDiagram.render(
				position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()),
				from, to, AsciiDiagram.View.NORTH, AsciiDiagram.Shape.CODE));
		}
		for (BlockPos at : unreached.stream().limit(2).toList()) {
			System.out.println();
			System.out.println("PROBE unreached note at " + at.getX() + " " + at.getY() + " "
				+ at.getZ());
			for (int z = at.getZ() - 1; z <= at.getZ() + 1; z++) {
				dump(world, at, z);
			}
		}
	}

	/** One z slice as a side view: x across, y down, the way in-game reading shows a lane off the
	/** world. */
	private static void dump(Map<BlockPos, BlockState> world, BlockPos at, int z) {
		int fromX = at.getX() - 9;
		int toX = at.getX() + 4;
		System.out.println("  z=" + z + (z == at.getZ() ? "  (the note's own slice)" : ""));
		StringBuilder header = new StringBuilder("      x  ");
		for (int x = fromX; x <= toX; x++) {
			header.append(String.format("%4d", x));
		}
		System.out.println(header);
		for (int y = at.getY() + 2; y >= at.getY() - 7; y--) {
			StringBuilder row = new StringBuilder(String.format("   y=%3d  ", y));
			for (int x = fromX; x <= toX; x++) {
				row.append(String.format("%4s",
					code(world.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()))));
			}
			System.out.println(row);
		}
	}

	/** The shorthand, so a dump from here and a dump from the world read the same. */
	private static String code(BlockState state) {
		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return switch (name) {
			case "air" -> ".";
			case "redstone_wire" -> "w" + state.getValue(
				net.minecraft.world.level.block.RedstoneWireBlock.POWER);
			case "repeater" -> (switch (state.getValue(
					net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
				case EAST -> ">";
				case WEST -> "<";
				case NORTH -> "^";
				default -> "v";
			}) + state.getValue(net.minecraft.world.level.block.RepeaterBlock.DELAY);
			case "stone" -> "ST";
			case "glass" -> "GL";
			case "note_block" -> "NB";
			case "sea_lantern" -> "SL";
			case "redstone_torch", "redstone_wall_torch" -> "RT";
			case "sticky_piston", "piston" -> "PI";
			case "observer" -> "OB";
			default -> name.length() <= 3 ? name : name.substring(0, 3);
		};
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
		return NoteMachineReader.read("probe", new BlockPos(minX, minY, minZ),
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
