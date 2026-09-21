package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: every cell the walk called live that the reader never reached, in laid order. */
@Tag("sweep")
class DeadCellProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("fault." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	@Test
	void lists() throws Exception {
		String song = text("song", "michael-jackson-thriller");
		int width = Integer.parseInt(text("width", "20"));
		int floors = Integer.parseInt(text("floors", "2"));
		GameSettings.Values game = GameSettings.get();
		FaultView.Build built = FaultView.of(song, SongBuilder.PasteMode.INTERLEAVED_HALF_TICK,
			width, floors, game.maxBuildFloors(), game.debugPaste());
		Set<BlockPos> reached = built.reading().reachedAt();
		Map<BlockPos, String> laidBy = built.plan().laidBy();
		System.out.println("DEADCELLS " + built.where() + " dead=" + built.reading().unreachedNotes()
			+ " versions=" + built.reading().versions() + " " + game.said());
		List<BlockPos> unreachedPowered = new ArrayList<>();
		for (BlockPos at : built.plan().poweredAt()) {
			if (!reached.contains(at)) {
				unreachedPowered.add(at);
			}
		}
		unreachedPowered.sort((a, b) -> Integer.compare(
			built.laid().getOrDefault(a, Integer.MAX_VALUE), built.laid().getOrDefault(b, Integer.MAX_VALUE)));
		System.out.println("   powered-but-unreached cells: " + unreachedPowered.size());
		for (BlockPos at : unreachedPowered.subList(0, Math.min(40, unreachedPowered.size()))) {
			System.out.println("   #" + built.laid().getOrDefault(at, -1) + "  " + FaultView.say(at)
				+ "  " + built.at(at).getBlock().getName().getString() + "  laid by "
				+ laidBy.getOrDefault(at, "?"));
		}
		List<BlockPos> notes = new ArrayList<>(built.reading().unreachedAt());
		notes.sort((a, b) -> Integer.compare(
			built.laid().getOrDefault(a, Integer.MAX_VALUE), built.laid().getOrDefault(b, Integer.MAX_VALUE)));
		System.out.println("   unreached note blocks: " + notes.size() + ", first 12 in laid order:");
		for (BlockPos at : notes.subList(0, Math.min(12, notes.size()))) {
			System.out.println("   #" + built.laid().getOrDefault(at, -1) + "  " + FaultView.say(at)
				+ "  laid by " + laidBy.getOrDefault(at, "?"));
		}
		// The box round the named cell, powered and reached per cell.
		String[] c = text("at", "11,64,43").split("[ ,]+");
		BlockPos centre = new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
		System.out.println("   round " + FaultView.say(centre) + " (P = walk says powered, R = reader reached):");
		for (int y = centre.getY() + 1; y >= centre.getY(); y--) {
			for (int z = centre.getZ() - 1; z <= centre.getZ() + 1; z++) {
				StringBuilder line = new StringBuilder("   y=" + y + " z=" + z + " ");
				for (int x = centre.getX() - 5; x <= centre.getX() + 5; x++) {
					BlockPos at = new BlockPos(x, y, z);
					String block = built.at(at).getBlock().getName().getString().replace("Block of ", "");
					block = block.length() > 8 ? block.substring(0, 8) : block;
					line.append(String.format("%9s%s%s", block,
						built.plan().poweredAt().contains(at) ? "P" : " ", reached.contains(at) ? "R" : " "));
				}
				System.out.println(line);
			}
		}
		// Who laid the centre row, cell by cell, one level up (the wire) and at the centre (the ground).
		for (int y = centre.getY() + 1; y >= centre.getY(); y--) {
			for (int x = centre.getX() - 5; x <= centre.getX() + 5; x++) {
				BlockPos at = new BlockPos(x, y, centre.getZ());
				System.out.println("   " + FaultView.say(at) + "  "
					+ built.at(at).getBlock().getName().getString() + "  laid by "
					+ laidBy.getOrDefault(at, "-") + "  #" + built.laid().getOrDefault(at, -1));
			}
		}
	}
}
