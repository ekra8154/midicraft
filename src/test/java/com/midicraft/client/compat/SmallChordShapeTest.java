package com.midicraft.client.compat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Why a chord of three comes out shaped like a bus, which in-game reading shows in game and cannot
 * explain.
 *
 * <p>One line can do it. A chord of three or fewer is {@code SMALL}, and where the lane is crowded
 * and the chord will not fit it falls back -- to the stacked front shape normally, and to a
 * <b>bus</b> whenever the walk is inside a turn, whatever the chord could have taken:</p>
 *
 * <pre>
 *   style = !inTurn &amp;&amp; ultraSlots(notes, false) != null ? STACKED_FRONT : BUS;
 * </pre>
 *
 * <p>The stated reason is that a stacked module in a turn stands across the run rather than along
 * it. That is the same claim {@link SongBuilder#TURN_BAN_OUTLASTS} makes about the chord after a
 * turn, and in-game testing has already read it off the blocks once and doubted it: a turn's bus
 * comes out of its second bend running the new lane's way, so the cells behind the chord are
 * collinear with it. Whether it is still true here is a question with a number, so this counts
 * rather than argues:</p>
 *
 * <ul>
 *   <li>{@code smallBecameStacked} -- fell back and took the denser shape.</li>
 *   <li>{@code smallBusForTheTurnRule} -- the stacked shape was available and the turn rule refused
 *       it. <b>This is the one that matters.</b> Every one of these is a column of lane spent to
 *       obey a rule nobody has measured.</li>
 *   <li>{@code smallBusNoSlots} -- no slots, so a bus was the only thing left. Not this rule's
 *       doing.</li>
 * </ul>
 */
@Tag("sweep")
class SmallChordShapeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final List<String> PICKED = List.of(
		"deltarune-ch-4-guardian", "illit-do-the-dance", "big-shot", "hopes-and-dreams",
		"golden-brown-2xspeed", "adventure-of-a-lifetime", "michael-jackson-thriller",
		"aria-math-c418");

	@Test
	void countsWhySmallChordsBecomeBuses() throws Exception {
		Map<String, Integer> total = new TreeMap<>();
		int chords = 0;
		for (String name : PICKED) {
			List<SongBuilder.EventNote> notes = BreachView.song(name);
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 16; width <= 48; width += 8) {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(4, width, floors));
					for (Map.Entry<String, Integer> entry : plan.padding().entrySet()) {
						if (entry.getKey().startsWith("small")) {
							total.merge(entry.getKey(), entry.getValue(), Integer::sum);
						}
					}
					chords++;
				}
			}
		}
		System.out.println();
		System.out.println("==== why a small chord stops being small, over " + chords + " builds ====");
		total.forEach((key, count) -> System.out.println("   " + key + " " + count));
		int turnRule = total.getOrDefault("smallBusForTheTurnRule", 0);
		int stacked = total.getOrDefault("smallBecameStacked", 0);
		int noSlots = total.getOrDefault("smallBusNoSlots", 0);
		System.out.println("   -- of the fallbacks that had a stacked shape available, "
			+ (stacked + turnRule == 0 ? 0 : 100 * turnRule / (stacked + turnRule))
			+ "% were refused it by the turn rule alone");
		System.out.println("   -- buses laid: " + (turnRule + noSlots) + ", of which "
			+ turnRule + " need not have been");
	}

	/**
	 * And whether the turn rule is about geometry or about caution, asked of the machine.
	 *
	 * <p>A rule that is really physical shows up here as a wrong note or a dead line the moment it is
	 * relaxed. One that is not shows up as nothing but a smaller build. Read back through
	 * {@link NoteMachineReader} rather than counted off the plan, because a plan reads nought over a
	 * machine whose wire has been cut.</p>
	 */
	@Test
	void readsBackSmallChordsStackedInTurns() throws Exception {
		System.out.println();
		System.out.println("==== small chords allowed to stack inside a turn ====");
		try {
			for (boolean allow : new boolean[] {false, true}) {
				SongBuilder.SMALL_MAY_STACK_IN_A_TURN = allow;
				int lanes = 0;
				int blocks = 0;
				int wrong = 0;
				int unreached = 0;
				long length = 0;
				long volume = 0;
				for (String name : PICKED) {
					List<SongBuilder.EventNote> notes = BreachView.song(name);
					for (int floors = 1; floors <= 6; floors++) {
						for (int width = 16; width <= 48; width += 8) {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
								notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							lanes += plan.breaches().size();
							blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
							wrong += plan.wrongNotes();
							length += plan.width();
							volume += plan.commands().size();
						}
					}
					// The machine, at the live size and at a narrow one where turns come thick.
					for (int[] size : new int[][] {{40, 5}, {16, 3}}) {
						unreached += BreachView.readBack(name, SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(16, size[0], size[1]))).unreachedNotes();
					}
				}
				System.out.println("   stackInTurn=" + (allow ? "on " : "off") + "   lanes=" + lanes
					+ " blocks=" + blocks + " wrong=" + wrong + " unreached=" + unreached
					+ " length=" + length + " volume=" + volume);
			}
		} finally {
			SongBuilder.SMALL_MAY_STACK_IN_A_TURN = false;
		}
	}
}
