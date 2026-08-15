package com.fastnoteblocks.client.compat;

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
		try {
			SongBuilder.TWO_RAIL_RUNS = rails;
			SongBuilder.V2_RUNS_ON_RAILS = rails;
			System.out.println();
			System.out.println("runs " + (rails ? "on" : "OFF"));
			FaultView.report(FaultView.of(song, mode, width, floors, maxFloors, names), perKind);
		} finally {
			SongBuilder.TWO_RAIL_RUNS = wasV1;
			SongBuilder.V2_RUNS_ON_RAILS = wasV2;
		}
	}
}
