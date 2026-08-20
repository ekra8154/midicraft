package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The chord of 27 that will not cut, written out so it can be built by hand.
 *
 * <p>A chord of 27 is head seven and tail twenty, and a descent's cut of it is
 * {@code 1 + 10 + 4 = 15} -- inside the fifteen with nothing to spare but nothing over either. It
 * should cut essentially whenever, and on a song of nothing but 27s it does not: 54 blocks of
 * breach where the same song built without {@link SongBuilder#SHED_BUYS_THE_LAST_CELL} has none.</p>
 *
 * <p>Parity is the suspect, forcing either a column of pad or a fall back to a plain bus, and
 * either of those stops the cut. So this prints the census keys that name those two decisions
 * beside the breach, and writes out one whole chord -- every note, its instrument and its pitch --
 * so the case can be stood up in the world rather than argued about from totals.</p>
 */
@Tag("sweep")
class TwentySevenCutTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The same generator {@link CutCeilingTest} uses, so the numbers line up with that table. */
	private static List<SongBuilder.EventNote> song(int size, long seed) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:packed_ice"};
		Random random = new Random(seed);
		int time = 0;
		for (int event = 0; event < 90; event++) {
			time += 3 + random.nextInt(5);
			for (int index = 0; index < size; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25),
					index == 0 ? "minecraft:air" : instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}

	@Test
	void writesOutTheChordThatWillNotCut() throws Exception {
		List<SongBuilder.EventNote> notes = song(27, 7L);
		for (boolean shed : new boolean[] {false, true}) {
			SongBuilder.SHED_BUYS_THE_LAST_CELL = shed;
			SongBuilder.SHED_BOUGHT_THE_CELL = 0;
			SongBuilder.SHED_BOUGHT_BY_SIZE.clear();
			BreachView.Traced traced = BreachView.build(notes, 20, 3, 4);
			SongBuilder.PastePlan plan = traced.plan();
			System.out.println();
			System.out.println("######## 27s, 20w x 3f, shed=" + shed
				+ "   breaches=" + plan.breaches()
				+ "   boughtTheCell=" + SongBuilder.SHED_BOUGHT_THE_CELL
				+ " by size " + SongBuilder.SHED_BOUGHT_BY_SIZE);
			// The decisions that stop a cut, named. parity is the pad; the two Got/Wanted keys are the
			// fall back to a plain bus; clashed and the veto are the other two ways a cut goes away.
			Map<String, Integer> why = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : plan.padding().entrySet()) {
				String key = entry.getKey();
				if (key.startsWith("parity") || key.startsWith("planParity")
						|| key.startsWith("planStackedSplit") || key.startsWith("planStackedBus")
						|| key.equals("planVetoBit") || key.startsWith("planBusFor")) {
					why.put(key, entry.getValue());
				}
			}
			why.forEach((key, count) -> System.out.println("   " + key + " " + count));
		}

		// And one chord, whole, so a player can stand it up. Instruments as block names because that is
		// what the note block is tuned by, and pitch because that is what it is tuned to.
		System.out.println();
		System.out.println("######## one chord of 27, as built");
		int first = notes.get(0).time();
		List<SongBuilder.EventNote> chord = notes.stream().filter(n -> n.time() == first).toList();
		System.out.println("   tick " + first + ", " + chord.size() + " notes");
		for (SongBuilder.EventNote note : chord) {
			System.out.println("   " + note.instrumentBlock() + "  pitch " + note.pitch());
		}
		SongBuilder.SHED_BUYS_THE_LAST_CELL = false;
	}
}
