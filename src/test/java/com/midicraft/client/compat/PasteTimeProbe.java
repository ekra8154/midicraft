package com.midicraft.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: how long one build of the client's own settings takes, and whether it throws. */
@Tag("sweep")
class PasteTimeProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void timesTheClientsBuild() throws Exception {
		String name = System.getProperty("probe.song", "guardian26");
		int width = Integer.parseInt(System.getProperty("probe.width", "18"));
		int floors = Integer.parseInt(System.getProperty("probe.floors", "4"));
		int maxFloors = Integer.parseInt(System.getProperty("probe.maxFloors", "16"));
		java.util.List<SongBuilder.EventNote> notes = BreachView.song(name);
		System.out.println("TIME song=" + name + " notes=" + notes.size()
			+ " " + width + "x" + floors + " maxFloors=" + maxFloors);
		long started = System.currentTimeMillis();
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(maxFloors, width, floors));
			System.out.println("TIME ok in " + (System.currentTimeMillis() - started) + " ms, "
				+ plan.commands().size() + " commands");
		} catch (Throwable thrown) {
			System.out.println("TIME THREW after " + (System.currentTimeMillis() - started)
				+ " ms: " + thrown);
			for (StackTraceElement frame : thrown.getStackTrace()) {
				if (frame.getClassName().contains("midicraft")) {
					System.out.println("TIME   at " + frame);
				}
			}
		}
	}
}
