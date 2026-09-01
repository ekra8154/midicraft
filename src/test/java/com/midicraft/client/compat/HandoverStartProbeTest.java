package com.midicraft.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What starting a run off a stacked bus's handover is worth, and whether it happens at all.
 *
 * <p>Both questions in one run, because they are not separable by any other measurement here: a
 * shape that never fires and a shape that fires and changes nothing produce the same corridor, the
 * same depth and the same faults. So the census count is printed beside the columns -- a nought
 * there means the walk never reached the shape, whatever the other numbers say.</p>
 */
@Tag("sweep")
class HandoverStartProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final List<String> SONGS = List.of(
		"ultra-rails-harp-gap2", "lady-brown-nujabes", "he-s-a-pirate",
		"all-of-the-lights-kanye-west", "illit-do-the-dance");

	private static final int[][] SIZES = {{48, 1}, {16, 3}, {24, 3}, {40, 5}};

	private record Arm(int totalCols, int spanZ, int fired, int wrong, int dead) {
	}

	private static Arm build(String song, List<SongBuilder.EventNote> notes, int width, int floors,
			boolean handover) {
		SongBuilder.RAIL_FROM_HANDOVER = handover;
		FaultView.Build built = FaultView.of(song, notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, width, floors, 4, false);
		return new Arm(built.plan().totalColumns(), built.plan().spanZ(),
			built.plan().padding().getOrDefault("railFromHandover", 0),
			built.plan().wrongNotes(), built.reading().unreachedNotes());
	}

	@Test
	void pricesTheHandoverStart() throws Exception {
		boolean was = SongBuilder.RAIL_FROM_HANDOVER;
		try {
			System.out.println();
			System.out.println("==== run started off a stacked bus handover: off -> on ====");
			for (String song : SONGS) {
				List<SongBuilder.EventNote> notes = BreachView.song(song);
				System.out.println();
				System.out.println("---- " + song + " ----");
				for (int[] size : SIZES) {
					Arm off;
					Arm on;
					try {
						off = build(song, notes, size[0], size[1], false);
						on = build(song, notes, size[0], size[1], true);
					} catch (RuntimeException refused) {
						System.out.println(String.format("   %2dw x %df  REFUSED: %s", size[0],
							size[1], refused.getMessage()));
						continue;
					}
					System.out.println(String.format(
						"   %2dw x %df  totalCols %6d -> %-6d (%+d)  spanZ %4d -> %-4d  fired %-4d"
						+ " wrong %2d -> %-2d  dead %5d -> %-5d",
						size[0], size[1], off.totalCols(), on.totalCols(),
						on.totalCols() - off.totalCols(), off.spanZ(), on.spanZ(), on.fired(),
						off.wrong(), on.wrong(), off.dead(), on.dead()));
				}
			}
		} finally {
			SongBuilder.RAIL_FROM_HANDOVER = was;
		}
	}
}
