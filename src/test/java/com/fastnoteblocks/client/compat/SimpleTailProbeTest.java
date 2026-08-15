package com.fastnoteblocks.client.compat;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a stacked bus's simple tail is worth, and what it silences.
 *
 * <p>Priced off against on like {@link HandoverStartProbeTest}, for the same reason: a shape that
 * never fires and a shape that fires and changes nothing are the same in every other number here.
 * The census beside the faults says which of the three middles were laid -- a harp note, a stone, or
 * a note kept in the middle of a tail of three -- and how many tails gave the shape up for a corner,
 * which is the only thing standing between the tail and a parity pad landing in front of it.</p>
 */
@Tag("sweep")
class SimpleTailProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final List<String> SONGS = List.of(
		"illit-do-the-dance", "all-of-the-lights-kanye-west", "ultra-rails-harp-gap2",
		"lady-brown-nujabes", "he-s-a-pirate");

	private static final int[][] SIZES = {{40, 3}, {40, 5}, {24, 3}, {48, 1}};

	private record Arm(int totalCols, int wrong, int dead, int harp, int stone, int note, int bused, int stuck, int gave) {
	}

	private static int census(FaultView.Build built, String key) {
		return built.plan().padding().getOrDefault(key, 0);
	}

	private static Arm build(String song, List<SongBuilder.EventNote> notes, int width, int floors,
			boolean simple, boolean dust) {
		SongBuilder.SIMPLE_TAIL_ON_A_STACKED_BUS = simple;
		SongBuilder.HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE = dust;
		FaultView.Build built = FaultView.of(song, notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, width, floors, 4, false);
		return new Arm(built.plan().totalColumns(), built.plan().wrongNotes(),
			built.reading().unreachedNotes(), census(built, "simpleTailHarp"),
			census(built, "simpleTailStone"), census(built, "simpleTailNoteInTheMiddle"),
			census(built, "stoneMiddleDusted"), census(built, "parityPadOnASoftTip"), census(built, "simpleTailBusedForATightGap"));
	}

	@Test
	void pricesTheSimpleTail() throws Exception {
		boolean was = SongBuilder.SIMPLE_TAIL_ON_A_STACKED_BUS;
		boolean wasDust = SongBuilder.HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE;
		try {
			System.out.println();
			System.out.println("==== a stacked bus's simple tail: no tail | tail, harp takes the middle always | only at three ====");
			for (String song : SONGS) {
				List<SongBuilder.EventNote> notes = BreachView.song(song);
				System.out.println();
				System.out.println("---- " + song + " ----");
				for (int[] size : SIZES) {
					Arm off;
					Arm bare;
					Arm on;
					try {
						off = build(song, notes, size[0], size[1], false, false);
						bare = build(song, notes, size[0], size[1], true, false);
						on = build(song, notes, size[0], size[1], true, true);
					} catch (RuntimeException refused) {
						System.out.println(String.format("   %2dw x %df  REFUSED: %s", size[0],
							size[1], refused.getMessage()));
						continue;
					}
					System.out.println(String.format(
						"   %2dw x %df  cols %5d|%5d|%-5d  wrong %2d|%2d|%-2d  dead %5d|%5d|%-5d"
						+ "   middles harp %3d stone %3d note %3d  dusted %3d STUCK %3d gaveWay %3d",
						size[0], size[1], off.totalCols(), bare.totalCols(), on.totalCols(),
						off.wrong(), bare.wrong(), on.wrong(), off.dead(), bare.dead(), on.dead(),
						on.harp(), on.stone(), on.note(), on.bused(), on.stuck(), on.gave()));
				}
			}
		} finally {
			SongBuilder.SIMPLE_TAIL_ON_A_STACKED_BUS = was;
			SongBuilder.HARP_KEEPS_THE_MIDDLE_ONLY_AT_THREE = wasDust;
		}
	}
}
