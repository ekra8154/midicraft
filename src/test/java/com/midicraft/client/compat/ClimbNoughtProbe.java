package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch: the raised-tail climb and the dropped-repeater descent, seeded the way the debug paste
 * seeds them -- a bus landing flush on the wall, then the chord that has to turn at a room of
 * nought -- drawn and read back.
 *
 * <p>{@code -Dprobe.spec=30x3 -Dprobe.toWall=16 -Dprobe.shape=up -Dprobe.width=28 -Dprobe.floors=3}.
 * The distance is the seed's: columns from the lane's first repeater to its wall.</p>
 */
@Tag("sweep")
class ClimbNoughtProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	/**
	 * Where each dropped-repeater descent and raised-tail climb stands, read off the finished
	 * plan rather than off the walk -- the plan slides after the walk, so a traced coordinate is
	 * not a coordinate to stand on. Space-separated, to paste into /tp.
	 */
	static String where(FaultView.Build built) {
		Map<String, List<BlockPos>> found = new TreeMap<>();
		built.plan().laidBy().forEach((at, by) -> {
			if (by != null && (by.startsWith("descentNought") || by.startsWith("climbNought"))) {
				found.computeIfAbsent(by, key -> new ArrayList<>()).add(at);
			}
		});
		StringBuilder said = new StringBuilder();
		found.forEach((by, cells) -> {
			BlockPos low = cells.stream().min((a, b) -> a.getY() != b.getY()
				? Integer.compare(a.getY(), b.getY()) : Integer.compare(a.getX(), b.getX())).get();
			BlockPos high = cells.stream().max((a, b) -> a.getY() != b.getY()
				? Integer.compare(a.getY(), b.getY()) : Integer.compare(a.getX(), b.getX())).get();
			said.append("   ").append(by).append("  ").append(cells.size()).append(" cells  tp ")
				.append(low.getX()).append(' ').append(low.getY()).append(' ').append(low.getZ())
				.append("  ..  ").append(high.getX()).append(' ').append(high.getY()).append(' ')
				.append(high.getZ()).append(System.lineSeparator());
		});
		return said.length() == 0 ? "   (no nought shape in this build)" : said.toString().strip();
	}

	/**
	 * The same question of a real song: where its dropped-repeater descents stand.
	 * {@code -Dprobe.song=deltarune-ch-4-guardian -Dprobe.width=8 -Dprobe.floors=4}.
	 */
	@Test
	void saysWhereASongsNoughtShapesStand() throws Exception {
		String song = text("song", "");
		if (song.isEmpty()) {
			System.out.println("(no -Dprobe.song, nothing to look at)");
			return;
		}
		Flags.Held held = Flags.set(text("set", ""));
		try {
			for (String pair : text("sizes", "8x4").split(",")) {
				String[] half = pair.strip().toLowerCase(Locale.ROOT).split("x");
				int width = Integer.parseInt(half[0]);
				int floors = Integer.parseInt(half[1]);
				FaultView.Build built = FaultView.of(song, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
					width, floors, 4, false);
				System.out.println();
				System.out.println("==== " + built.where() + "  breach="
					+ built.plan().breaches().size() + " dead=" + built.reading().unreachedNotes()
					+ " wrong=" + built.plan().wrongNotes() + " ====");
				System.out.println(where(built));
			}
		} finally {
			held.putBack();
		}
	}

	@Test
	void drawsTheTurnAtRoomNought() {
		String shape = text("shape", "up");
		int width = Integer.parseInt(text("width", "28"));
		int floors = Integer.parseInt(text("floors", "3"));
		String spec = text("spec", "30x3");
		List<SongBuilder.EventNote> notes = DebugChords.notes(spec, 4);
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2;
		int laneWidth = SongBuilder.laneWidthFor(mode, notes, width, floors, new BlockPos(0, 64, 0));
		Flags.Held held = Flags.set(text("set", ""));
		try {
			for (String distance : text("toWall", "16").split(",")) {
				int toWall = Integer.parseInt(distance.strip());
				int column = Math.max(0, laneWidth - toWall);
				int floor = "up".equals(shape) ? 0 : floors - 1;
				int climb = "up".equals(shape) ? 1 : -1;
				FaultView.Build built = FaultView.of("seed " + spec, notes, mode, width, floors, 4,
					false, new SongBuilder.WalkStart(column, floor, climb, false));
				Map<String, Integer> shapes = new TreeMap<>();
				for (String by : built.plan().laidBy().values()) {
					if (by != null) {
						shapes.merge(by.split(" ")[0].replaceAll("[0-9/]+.*", ""), 1, Integer::sum);
					}
				}
				Map<String, Integer> counters = new TreeMap<>();
				built.plan().padding().forEach((key, count) -> {
					if (key.contains("Nought")) {
						counters.put(key, count);
					}
				});
				System.out.println();
				System.out.println("==== " + shape + " " + spec + " toWall=" + toWall + " lane="
					+ laneWidth + " col=" + column + "  breach=" + built.plan().breaches().size()
					+ " dead=" + built.reading().unreachedNotes()
					+ " wrong=" + built.plan().wrongNotes()
					+ " ====");
				System.out.println("   shapes " + shapes);
				System.out.println("   nought " + counters);
				System.out.println(where(built));
				FaultView.report(built, 1);
				String at = text("at", "");
				if (!at.isEmpty()) {
					String[] middle = at.split("[ ,]+");
					String[] span = text("span", "8,6,1").split("[ ,]+");
					BlockPos centre = new BlockPos(Integer.parseInt(middle[0]),
						Integer.parseInt(middle[1]), Integer.parseInt(middle[2]));
					BlockPos from = centre.offset(-Integer.parseInt(span[0]),
						-Integer.parseInt(span[1]), -Integer.parseInt(span[2]));
					BlockPos to = centre.offset(Integer.parseInt(span[0]),
						Integer.parseInt(span[1]), Integer.parseInt(span[2]));
					System.out.println(FaultView.draw(built, from, to,
						AsciiDiagram.View.of(text("view", "north"))));
					System.out.println(FaultView.shapesIn(built, from, to));
				}
			}
		} finally {
			held.putBack();
		}
	}
}
