package com.midicraft.client.compat;

import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Point {@link FaultView} at a build and read what is wrong with it.
 *
 * <p>Re-targetable from the command line, so that following a fault costs a run rather than an edit
 * and a run:</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*FaultProbeTest" -Dfault.song=deltarune-ch-4-guardian \
 *     -Dfault.mode=v2 -Dfault.width=20 -Dfault.floors=5
 * </pre>
 *
 * <p>Defaults are the worst Guardian breach on the v2 paster, which is the one currently worth
 * standing in. {@code -Dfault.names=true} makes a wrong note say which shape laid each end of it --
 * read the caveat on {@link FaultView#of} before believing a build made that way.</p>
 *
 * <p>Prints; asserts nothing. A probe that fails the build when it finds a fault is a probe nobody
 * can point at a broken build, which is the only kind worth pointing it at.</p>
 */
@Tag("sweep")
class FaultProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("fault." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private static int number(String key, int fallback) {
		return Integer.parseInt(text(key, String.valueOf(fallback)));
	}

	/** The menu's own names, plus the short ones worth typing. */
	private static SongBuilder.PasteMode mode(String given) {
		return switch (given.toLowerCase(Locale.ROOT)) {
			case "v2", "ultra2", "ultra_compact_lane_v2" -> SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2;
			case "v1", "ultra", "ultra_compact_lane" -> SongBuilder.PasteMode.ULTRA_COMPACT_LANE;
			default -> SongBuilder.PasteMode.valueOf(given.toUpperCase(Locale.ROOT));
		};
	}

	@Test
	void drawsWhateverIsWrongWithIt() throws Exception {
		String song = text("song", "deltarune-ch-4-guardian");
		SongBuilder.PasteMode mode = mode(text("mode", "v2"));
		int width = number("width", 20);
		int floors = number("floors", 5);
		int maxFloors = number("maxFloors", 4);
		boolean names = Boolean.parseBoolean(text("names", "false"));
		int perKind = number("each", 2);
		// The runs, off as well as on. Two reasons worth the property: a fault that appears with them
		// on wants to be seen with them off before it is blamed on them, and the plain lane has faults
		// of its own that the runs happen to cover -- which is the only way to reach a dead wire on
		// purpose, there being no known live one to point this at.
		boolean rails = Boolean.parseBoolean(text("rails", "true"));
		boolean wasV1 = SongBuilder.TWO_RAIL_RUNS;
		boolean wasV2 = SongBuilder.V2_RUNS_ON_RAILS;
		Flags.Held held = Flags.set(text("set", ""));
		try {
			SongBuilder.TWO_RAIL_RUNS = rails;
			SongBuilder.V2_RUNS_ON_RAILS = rails;
			System.out.println();
			System.out.println("runs " + (rails ? "on" : "OFF"));
			// What the song says, before any of this touched it. -Dfault.ticks=795-825
			//
			// The other half of every "is the build faithful" question, and the half nothing here
			// could print. FaultView.notesIn says what the build is meant to play; this says what was
			// written. Where they agree, a fault is in the redstone; where they differ, the fault is
			// upstream of the redstone and no amount of staring at blocks will show it.
			String window = text("ticks", "");
			if (!window.isEmpty()) {
				String[] ends = window.split("[-.]+");
				int first = Integer.parseInt(ends[0]);
				int last = Integer.parseInt(ends[ends.length - 1]);
				System.out.println();
				System.out.println("======== " + song + " as written, ticks " + first + ".." + last
					+ " ========");
				java.util.TreeMap<Integer, StringBuilder> byTick = new java.util.TreeMap<>();
				for (SongBuilder.EventNote note : BreachView.song(song)) {
					if (note.time() < first || note.time() > last) {
						continue;
					}
					byTick.computeIfAbsent(note.time(), key -> new StringBuilder())
						.append(String.format("%02d", note.pitch()))
						.append(com.midicraft.NotePitch.name(note.pitch()).replace('♯', '#'))
						.append(' ')
						.append(note.instrumentBlock().replace("minecraft:", "")).append("   ");
				}
				int previous = Integer.MIN_VALUE;
				for (java.util.Map.Entry<Integer, StringBuilder> chord : byTick.entrySet()) {
					System.out.println("   t" + String.format("%-6d", chord.getKey())
						+ (previous == Integer.MIN_VALUE ? "        "
							: String.format("(+%-2d)   ", chord.getKey() - previous))
						+ chord.getValue());
					previous = chord.getKey();
				}
				if (byTick.isEmpty()) {
					System.out.println("   (the song has nothing in that range)");
				}
			}
			FaultView.Build built = FaultView.of(song, mode, width, floors, maxFloors, names);
			// A box somebody asked for, rather than the one a fault picked. -Dfault.at=x,y,z with
			// -Dfault.span=xSpan,ySpan,zSpan and -Dfault.view=top|north. There is no substitute for
			// looking at the place someone is standing in.
			String at = text("at", "");
			if (!at.isEmpty()) {
				String[] middle = at.split("[ ,]+");
				String[] span = text("span", "12,6,3").split("[ ,]+");
				net.minecraft.core.BlockPos centre = new net.minecraft.core.BlockPos(
					Integer.parseInt(middle[0]), Integer.parseInt(middle[1]),
					Integer.parseInt(middle[2]));
				System.out.println();
				System.out.println("======== " + built.where() + " round " + FaultView.say(centre)
					+ " ========");
				net.minecraft.core.BlockPos from = centre.offset(-Integer.parseInt(span[0]),
					-Integer.parseInt(span[1]), -Integer.parseInt(span[2]));
				net.minecraft.core.BlockPos to = centre.offset(Integer.parseInt(span[0]),
					Integer.parseInt(span[1]), Integer.parseInt(span[2]));
				System.out.println(FaultView.draw(built, from, to,
					AsciiDiagram.View.of(text("view", "top"))));
				System.out.println(FaultView.shapesIn(built, from, to));
				System.out.println(FaultView.notesIn(built, from, to));
				// The drawing shows a wire only as its power, so the exact state of the cell
				// someone is standing on is said in full -- a cross and a dot draw the same.
				System.out.println("   block at " + FaultView.say(centre) + ": " + built.at(centre));
				// A small window is also listed cell by cell -- what stands there, whether the plan
				// marked it live, and what laid it -- which is what a "sounded by another" refusal
				// has to be read off.
				if (AsciiDiagram.volume(from, to) <= 125) {
					for (int y = from.getY(); y <= to.getY(); y++) {
						for (int z = from.getZ(); z <= to.getZ(); z++) {
							for (int x = from.getX(); x <= to.getX(); x++) {
								net.minecraft.core.BlockPos cell = new net.minecraft.core.BlockPos(x, y, z);
								String state = built.at(cell).toString();
								boolean live = built.plan().poweredAt().contains(cell);
								String by = built.plan().laidBy().get(cell);
								if (!state.contains("minecraft:air") || live || by != null) {
									boolean reached = built.reading().reachedAt().contains(cell);
									boolean unreached = built.reading().unreachedAt().contains(cell);
									System.out.println("      " + FaultView.say(cell) + "  "
										+ state.replace("Block{", "").replace("}", "")
										+ (live ? "  LIVE" : "") + (reached ? "  reached" : "")
										+ (unreached ? "  UNREACHED" : "")
										+ (by == null ? "" : "  by " + by));
								}
							}
						}
					}
				}
				return;
			}
			FaultView.report(built, perKind);
		} finally {
			SongBuilder.TWO_RAIL_RUNS = wasV1;
			SongBuilder.V2_RUNS_ON_RAILS = wasV2;
			held.putBack();
		}
	}
}
