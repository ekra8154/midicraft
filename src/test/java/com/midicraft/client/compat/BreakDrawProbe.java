package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A build's first break drawn, and every seam landing against its walls.
 *
 * <p>Untagged classes run in the default suite; this plans every song, so it is a sweep. See
 * {@link InterleavedDeadProbe} for the cell-by-cell view of one build.</p>
 */
@Tag("sweep")
class BreakDrawProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private static String say(FaultView.Build built, BlockPos at) {
		BlockState state = built.at(at);
		String name = state.isAir() ? "air"
			: BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (state.is(Blocks.REPEATER)) {
			name += "[reads " + state.getValue(
				net.minecraft.world.level.block.RepeaterBlock.FACING).getName() + "]";
		}
		return name + " " + built.plan().laidBy().getOrDefault(at, "-");
	}

	@Test
	void seamLandings() throws Exception {
		String song = text("song", "moonlight-sonata-3rd-movement");
		int width = Integer.parseInt(text("width", "10"));
		int floors = Integer.parseInt(text("floors", "1"));
		FaultView.Build built = FaultView.of(song, SongBuilder.PasteMode.INTERLEAVED_HALF_TICK,
			width, floors, 4, false);
		SongBuilder.PastePlan plan = built.plan();
		int near = Math.min(plan.nearWall(), plan.farWall());
		int far = Math.max(plan.nearWall(), plan.farWall());
		System.out.println("==== " + built.where() + "  never fired="
			+ built.reading().unreachedNotes() + "/" + built.reading().noteBlocks()
			+ "  walls x=" + near + ".." + far);
		plan.padding().forEach((key, value) -> {
			if (key.toLowerCase(Locale.ROOT).contains("seam")) {
				System.out.println(String.format(Locale.ROOT, "    %-34s %d", key, value));
			}
		});
		List<BlockPos> pistons = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
			if (cell.getValue().is(Blocks.STICKY_PISTON)
					&& "paritySeam".equals(plan.laidBy().get(cell.getKey()))) {
				pistons.add(cell.getKey());
			}
		}
		pistons.sort(Comparator.comparingInt((BlockPos at) -> at.getZ())
			.thenComparingInt(BlockPos::getX));
		System.out.println("  " + pistons.size() + " seam pistons; each landing and what follows:");
		int outsideLandings = 0;
		for (BlockPos piston : pistons) {
			Direction pushes = built.at(piston).getValue(PistonBaseBlock.FACING);
			BlockPos landing = piston.relative(pushes, 2);
			BlockPos beyond = landing.relative(pushes);
			// Only cells actually LAID outside count. A landing on the wall with nothing past
			// it is the seam ending flush and turning, which is the shape that keeps it inside.
			boolean landingOut = (landing.getX() < near || landing.getX() > far)
				&& !built.at(landing).isAir();
			boolean beyondOut = (beyond.getX() < near || beyond.getX() > far)
				&& !built.at(beyond).isAir();
			if (landingOut || beyondOut) {
				outsideLandings++;
			}
			if (landingOut || beyondOut || Boolean.parseBoolean(text("all", "false"))) {
				System.out.println(String.format(Locale.ROOT,
					"    piston %-13s pushes %-5s landing %-13s%s  beyond %-13s%s  %s",
					piston.toShortString(), pushes.getName(), landing.toShortString(),
					landingOut ? " OUT" : "    ", beyond.toShortString(),
					beyondOut ? " OUT" : "    ", say(built, beyond)));
			}
		}
		System.out.println("  " + outsideLandings + " of " + pistons.size()
			+ " have a landing or the cell past it outside the walls");
	}

	@Test
	void drawTheBreak() throws Exception {
		String song = text("song", "sunset-of-seven-suns");
		int width = Integer.parseInt(text("width", "10"));
		int floors = Integer.parseInt(text("floors", "1"));
		int around = Integer.parseInt(text("around", "6"));
		FaultView.Build built = FaultView.of(song, SongBuilder.PasteMode.INTERLEAVED_HALF_TICK,
			width, floors, 4, false);
		SongBuilder.PastePlan plan = built.plan();
		java.util.Set<BlockPos> live = built.reading().reachedAt();
		System.out.println("==== " + built.where() + "  never fired="
			+ built.reading().unreachedNotes() + "/" + built.reading().noteBlocks()
			+ "  walls x=" + plan.nearWall() + ".." + plan.farWall());
		// The break in the order the music happens: the earliest tick that never sounds.
		Integer firstTick = null;
		BlockPos firstSilent = null;
		for (Map.Entry<BlockPos, Integer> note : plan.noteTicks().entrySet()) {
			if (built.reading().unreachedAt().contains(note.getKey())
					&& (firstTick == null || note.getValue() < firstTick)) {
				firstTick = note.getValue();
				firstSilent = note.getKey();
			}
		}
		if (firstSilent == null) {
			System.out.println("  nothing silent");
			return;
		}
		System.out.println("  first tick that never sounds: t" + firstTick + " at "
			+ firstSilent.toShortString() + "  " + plan.laidBy().getOrDefault(firstSilent, "-"));
		BlockPos at = firstSilent;
		System.out.println(AsciiDiagram.render(pos -> built.at(pos),
			new BlockPos(at.getX() - around - 3, at.getY() - 1, at.getZ() - around),
			new BlockPos(at.getX() + around + 3, at.getY() + 1, at.getZ() + around),
			AsciiDiagram.View.TOP, Direction.SOUTH, true, AsciiDiagram.Shape.CODE));
		System.out.println("  wire, repeaters and pistons in that box:");
		List<BlockPos> box = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
			BlockPos p = cell.getKey();
			if ((cell.getValue().is(Blocks.REDSTONE_WIRE) || cell.getValue().is(Blocks.REPEATER)
					|| cell.getValue().is(Blocks.STICKY_PISTON)
					|| cell.getValue().is(Blocks.REDSTONE_BLOCK))
					&& Math.abs(p.getZ() - at.getZ()) <= around
					&& Math.abs(p.getX() - at.getX()) <= around + 3) {
				box.add(p);
			}
		}
		box.sort(Comparator.comparingInt((BlockPos p) -> p.getZ())
			.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX));
		for (BlockPos p : box) {
			System.out.println(String.format(Locale.ROOT, "    %-6s %-13s %s",
				live.contains(p) ? "LIVE" : "dead", p.toShortString(), say(built, p)));
		}
	}
}
