package com.fastnoteblocks.client.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: which change took {@link WallTurnReproTest}'s repro from two columns out to four.
 *
 * <p>The pin was written against a lane that turned two columns past its wall. It now turns four,
 * and that went unread for a while because the test was filed with three others as "known red" --
 * the other three are goals not yet met or a repro that got fixed, and this one is a repro that got
 * worse.</p>
 *
 * <p>Flags rather than commits. Every shape added since the pin is gated by one, so a flag sweep
 * answers the same question as a bisect in a fraction of the time, and answers it in the terms the
 * fix would be written in.</p>
 */
@Tag("sweep")
class WallTurnBisectTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.CHEAP_SPLIT_DESCENT = true;
		SongBuilder.GAP_ENDS_ON_BUS = true;
		SongBuilder.FRONT_ONLY_HEADS = true;
		SongBuilder.VARIABLE_HEAD_NOTES = true;
		SongBuilder.SPLIT_NUDGES = true;
		SongBuilder.STACKED_BUS_HEADS = true;
		SongBuilder.STACKED_SPLIT_HEADS = true;
		SongBuilder.HEAD_ONLY_NEAR_HALF = true;
		SongBuilder.PARITY_PREFERS_BUS = false;
		SongBuilder.PIN_DESCENTS = true;
		SongBuilder.LOOKAHEAD = true;
	}

	/** The repro exactly as {@link WallTurnReproTest} builds it. */
	private static String breachOf() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes(DebugChords.parse("21@1 1@1 9@1", DebugChords.DEFAULT_GAP)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 12, 3),
			new SongBuilder.WalkStart(2, 2, -1, false));
		for (String fault : plan.faults()) {
			if (fault.startsWith("a lane turned")) {
				return fault.substring(0, fault.indexOf(", where"))
					+ "  [" + plan.commands().size() + " blocks]";
			}
		}
		return "clean  [" + plan.commands().size() + " blocks]";
	}

	@Test
	void namesTheFlagThatDoubledIt() {
		Map<String, Consumer<Boolean>> flags = new LinkedHashMap<>();
		flags.put("CHEAP_SPLIT_DESCENT", on -> SongBuilder.CHEAP_SPLIT_DESCENT = on);
		flags.put("GAP_ENDS_ON_BUS", on -> SongBuilder.GAP_ENDS_ON_BUS = on);
		flags.put("FRONT_ONLY_HEADS", on -> SongBuilder.FRONT_ONLY_HEADS = on);
		flags.put("VARIABLE_HEAD_NOTES", on -> SongBuilder.VARIABLE_HEAD_NOTES = on);
		flags.put("SPLIT_NUDGES", on -> SongBuilder.SPLIT_NUDGES = on);
		flags.put("STACKED_BUS_HEADS", on -> SongBuilder.STACKED_BUS_HEADS = on);
		flags.put("STACKED_SPLIT_HEADS", on -> SongBuilder.STACKED_SPLIT_HEADS = on);
		flags.put("HEAD_ONLY_NEAR_HALF", on -> SongBuilder.HEAD_ONLY_NEAR_HALF = on);
		flags.put("PIN_DESCENTS", on -> SongBuilder.PIN_DESCENTS = on);
		flags.put("LOOKAHEAD", on -> SongBuilder.LOOKAHEAD = on);

		System.out.println("BISECT live          " + breachOf());
		// One at a time, off. Whichever one restores two columns is the one that moved it.
		for (Map.Entry<String, Consumer<Boolean>> flag : flags.entrySet()) {
			flag.getValue().accept(false);
			System.out.println("BISECT -" + String.format("%-20s", flag.getKey()) + breachOf());
			flag.getValue().accept(true);
		}
		// And PARITY_PREFERS_BUS the other way, since it ships off.
		SongBuilder.PARITY_PREFERS_BUS = true;
		System.out.println("BISECT +" + String.format("%-20s", "PARITY_PREFERS_BUS") + breachOf());
		SongBuilder.PARITY_PREFERS_BUS = false;

		// All the stacked-head work off together, which is the state the pin was written in.
		SongBuilder.STACKED_BUS_HEADS = false;
		SongBuilder.STACKED_SPLIT_HEADS = false;
		SongBuilder.HEAD_ONLY_NEAR_HALF = false;
		System.out.println("BISECT no heads at all   " + breachOf());
	}

	/**
	 * And what the extra two columns actually cost, which is the question the pin cannot answer.
	 *
	 * <p>A breach is footprint, so a shape that breaches further is only worse if the build is
	 * bigger for it. The block count is identical either way, so the thing to read is the span.</p>
	 */
	@Test
	void pricesTheTwoColumns() {
		for (boolean heads : new boolean[] {false, true}) {
			SongBuilder.STACKED_BUS_HEADS = heads;
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse("21@1 1@1 9@1", DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, 12, 3),
				new SongBuilder.WalkStart(2, 2, -1, false));
			System.out.println("PRICE heads=" + heads
				+ " spanX=" + plan.spanX() + " spanZ=" + plan.spanZ()
				+ " width=" + plan.width()
				+ " blocks=" + plan.commands().size()
				+ " breaches=" + plan.breaches() + " worst=" + plan.worstBreach()
				+ " wrong=" + plan.wrongNotes());
			plan.padding().forEach((key, value) ->
				System.out.println("PRICE   heads=" + heads + " pad " + key + "=" + value));
		}
	}
}
