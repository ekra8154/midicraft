package com.fastnoteblocks.client.compat;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch: the one build v2 still refuses, read off the blocks rather than off a theory.
 *
 * <p>Guardian 20 wide over four floors, {@code -1 76 73}: a note block is standing where the turn's
 * own stone wants to go. Six wrong explanations of this class of fault say that knowing about it is
 * not enough -- so this marks it, prints what is around it, and says which shape claimed the cell
 * first.</p>
 */
@Tag("sweep")
class BendFootprintProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void marksWhatTheTurnLandsIn() throws Exception {
		List<SongBuilder.EventNote> guardian = BreachView.song("deltarune-ch-4-guardian");
		SongBuilder.MARK_COLLISIONS = true;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, new SongBuilder.BuildLimits(4, 20, 4));
			System.out.println();
			System.out.println("==== guardian 20w x 4f, collisions marked ====");
			System.out.println("   breaches=" + plan.breaches() + " wrong=" + plan.wrongNotes()
				+ " walls=" + plan.nearWall() + ".." + plan.farWall());
			System.out.println("   collisions=" + plan.collisions().size());
			plan.padding().entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("planBus"))
				.forEach(entry -> System.out.println("   " + entry.getKey() + " = "
					+ entry.getValue()));
			for (Map.Entry<BlockPos, String> hit : plan.collisions().entrySet()) {
				System.out.println("   at " + hit.getKey().getX() + " " + hit.getKey().getY() + " "
					+ hit.getKey().getZ() + "   " + hit.getValue());
			}
			// What the build put in and around each marked cell, in build order, so the shape that
			// claimed it first can be named rather than guessed at.
			for (BlockPos hit : plan.collisions().keySet()) {
				System.out.println("   ---- what stands within two blocks of " + hit.getX() + " "
					+ hit.getY() + " " + hit.getZ() + ", in build order ----");
				int index = -1;
				for (String command : plan.commands()) {
					index++;
					String[] parts = command.split(" ");
					int x = Integer.parseInt(parts[1]);
					int y = Integer.parseInt(parts[2]);
					int z = Integer.parseInt(parts[3]);
					if (Math.abs(x - hit.getX()) <= 2 && Math.abs(y - hit.getY()) <= 2
							&& Math.abs(z - hit.getZ()) <= 2) {
						System.out.println("      #" + index + "  " + x + " " + y + " " + z + "  "
							+ parts[4]);
					}
				}
			}
		} finally {
			SongBuilder.MARK_COLLISIONS = false;
		}
		// And the same build with the marker off, which is the one that throws. The marker cannot show
		// this: with it on nothing throws at all, so the fallback that was supposed to catch the throw
		// is never reached and never counted. The stack names the call site outright.
		try {
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, new SongBuilder.BuildLimits(4, 20, 4));
			System.out.println("   marker off: it builds");
		} catch (IllegalArgumentException refused) {
			System.out.println("   marker off: " + refused.getMessage());
			for (StackTraceElement frame : refused.getStackTrace()) {
				if (frame.getClassName().contains("fastnoteblocks")) {
					System.out.println("      at " + frame.getMethodName() + ":" + frame.getLineNumber());
				}
			}
		}
	}
}
