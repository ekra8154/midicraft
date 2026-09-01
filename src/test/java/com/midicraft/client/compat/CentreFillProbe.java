package com.midicraft.client.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: how many stacked centres stop being stone when the centre takes a note. */
@Tag("sweep")
class CentreFillProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Map<BlockPos, String> world(boolean centreFirst) throws Exception {
		boolean was = SongBuilder.CENTRE_TAKES_A_NOTE_WHEN_IT_CAN;
		SongBuilder.CENTRE_TAKES_A_NOTE_WHEN_IT_CAN = centreFirst;
		try {
			Map<BlockPos, String> blocks = new HashMap<>();
			for (String command : SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					BreachView.song(System.getProperty("probe.song", "guardian26")),
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
					new SongBuilder.BuildLimits(4, 20, 4)).commands()) {
				String[] parts = command.split(" ");
				blocks.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parts[4]);
			}
			return blocks;
		} finally {
			SongBuilder.CENTRE_TAKES_A_NOTE_WHEN_IT_CAN = was;
		}
	}

	@Test
	void countsTheCentresThatFill() throws Exception {
		Map<BlockPos, String> before = world(false);
		Map<BlockPos, String> after = world(true);
		Map<String, Integer> moves = new TreeMap<>();
		for (Map.Entry<BlockPos, String> cell : after.entrySet()) {
			String then = before.get(cell.getKey());
			if (then != null && !then.equals(cell.getValue())) {
				moves.merge(kind(then) + " -> " + kind(cell.getValue()), 1, Integer::sum);
			}
		}
		System.out.println("CENTREFILL cells " + before.size() + " -> " + after.size());
		moves.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(e -> System.out.println("CENTREFILL   " + e.getValue() + "  " + e.getKey()));
	}

	/** Block ids only: a note block's pitch changing is not a different kind of cell. */
	private static String kind(String block) {
		int state = block.indexOf('[');
		return state < 0 ? block : block.substring(0, state);
	}
}
