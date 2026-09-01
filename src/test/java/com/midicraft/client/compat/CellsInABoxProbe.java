package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every cell in a box, one to a line: the shape that laid it, the block, and the tick it sounds on.
 *
 * <p>{@link FaultProbeTest} draws the same box and is the thing to reach for first -- a picture is
 * how a fault gets read. This is for the question a picture cannot answer, which is <em>which of
 * these blocks belong to the same chord</em>. {@link FaultView#shapesIn} names the shapes but prints
 * only six cells of each, and a module of six notes is thirty cells, so the one thing worth knowing
 * -- where its notes ended up -- is inside the ellipsis. That cost a round trip on the illit break
 * and this is what closed it: the note at {@code 40 81 52} and the bus stone that wanted the same
 * column both said {@code chord:SUNKEN_BUS notes6}, which named the fault as a module standing in
 * its own way rather than two shapes meeting.</p>
 *
 * <pre>
 * gradlew sweepTest --offline -i --tests "*CellsInABoxProbe" -Dprobe.song=illit-do-the-dance \
 *     -Dprobe.width=40 -Dprobe.floors=5 -Dprobe.at=41,80,51 -Dprobe.span=2,3,3
 * </pre>
 *
 * <p>Names are off, so the build is the one that ships: naming shapes stops a collision throwing,
 * and v2's trial fallback depends on that throw. See {@link FaultView#of}.</p>
 */
@Tag("sweep")
class CellsInABoxProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	private static int number(String key, int fallback) {
		String given = text(key, "");
		return given.isEmpty() ? fallback : Integer.parseInt(given);
	}

	@Test
	void listsWhatIsInTheBox() throws Exception {
		// The same -Dprobe.set=NAME=value every other probe takes, because a dump of a build made
		// with a flag the other way is a dump of a build nobody is asking about.
		Flags.Held held = Flags.set(text("set", ""));
		try {
			listThem();
		} finally {
			held.putBack();
		}
	}

	private void listThem() throws Exception {
		FaultView.Build built = FaultView.of(text("song", "illit-do-the-dance"),
			SongBuilder.PasteMode.valueOf(text("mode", "ULTRA_COMPACT_LANE_V2")),
			number("width", 40), number("floors", 5), number("maxFloors", 16),
			"true".equals(text("names", "false")));
		String[] middle = text("at", "41,80,51").split("[ ,]+");
		String[] span = text("span", "2,3,3").split("[ ,]+");
		BlockPos centre = new BlockPos(Integer.parseInt(middle[0]), Integer.parseInt(middle[1]),
			Integer.parseInt(middle[2]));
		BlockPos from = centre.offset(-Integer.parseInt(span[0]), -Integer.parseInt(span[1]),
			-Integer.parseInt(span[2]));
		BlockPos to = centre.offset(Integer.parseInt(span[0]), Integer.parseInt(span[1]),
			Integer.parseInt(span[2]));
		List<String> lines = new ArrayList<>();
		built.plan().laidBy().forEach((at, what) -> {
			if (at.getX() < from.getX() || at.getX() > to.getX() || at.getY() < from.getY()
					|| at.getY() > to.getY() || at.getZ() < from.getZ() || at.getZ() > to.getZ()) {
				return;
			}
			Integer tick = built.plan().noteTicks().get(at);
			lines.add(String.format("%4d %3d %4d  %-46s %-52s %s", at.getX(), at.getY(), at.getZ(),
				what, built.at(at), tick == null ? "" : "note@t" + tick));
		});
		// Sorted as text, which sorts by x then y then z, because a module is read column by column.
		lines.sort(String::compareTo);
		built.plan().collisions().forEach((at, what) -> {
			if (at.getX() >= from.getX() && at.getX() <= to.getX() && at.getY() >= from.getY()
					&& at.getY() <= to.getY() && at.getZ() >= from.getZ() && at.getZ() <= to.getZ()) {
				lines.add(String.format("%4d %3d %4d  COLLISION %s", at.getX(), at.getY(),
					at.getZ(), what));
			}
		});
		System.out.println();
		System.out.println("======== " + built.where() + " in " + FaultView.say(from) + " .. "
			+ FaultView.say(to) + " ========");
		lines.forEach(System.out::println);
		System.out.println("   " + lines.size() + " cells");
	}
}
