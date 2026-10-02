package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One song planned spread and packed, side by side, with every number the choice between them reads.
 *
 * <p>For the question "why did this paste come out three apart": the packed plan is built for every
 * single-note song on one floor and kept only where it wins, so a spread build is either a song that
 * never asked or a packed plan that lost -- and this says which, and on what.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*PackedChoiceProbe" -Dprobe.song=neverending-night-toby-fox-2 -Dprobe.size=15x1
 * </pre>
 */
@Tag("sweep")
class PackedChoiceProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void plansBothWays() throws Exception {
		String song = System.getProperty("probe.song", "neverending-night-toby-fox-2");
		String[] size = System.getProperty("probe.size", "15x1").split("x");
		int width = Integer.parseInt(size[0]);
		int floors = Integer.parseInt(size[1]);
		GameSettings.Values game = GameSettings.get();
		ComposerProject project = GameSettings.project(
			Path.of("run", "config", "midicraft", "songs", song + ".json"));
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		List<SongBuilder.EventNote> notes = game.notes(project, mode);
		java.util.Map<Integer, Integer> perTick = new java.util.HashMap<>();
		notes.forEach(note -> perTick.merge(note.time(), 1, Integer::sum));
		System.out.println();
		System.out.println("==== " + song + " " + width + "x" + floors + "  notes " + notes.size()
			+ "  peak " + perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0)
			+ "  " + game.said() + " ====");
		boolean packs = SongBuilder.SINGLE_NOTE_SONGS_PACK_TWO_APART;
		boolean always = SongBuilder.PACKED_PLAN_ALWAYS_WINS;
		try {
			SongBuilder.SINGLE_NOTE_SONGS_PACK_TWO_APART = false;
			say("spread", plan(notes, mode, game, width, floors));
			SongBuilder.SINGLE_NOTE_SONGS_PACK_TWO_APART = true;
			SongBuilder.PACKED_PLAN_ALWAYS_WINS = true;
			say("packed", plan(notes, mode, game, width, floors));
			SongBuilder.PACKED_PLAN_ALWAYS_WINS = false;
			say("chosen", plan(notes, mode, game, width, floors));
		} finally {
			SongBuilder.SINGLE_NOTE_SONGS_PACK_TWO_APART = packs;
			SongBuilder.PACKED_PLAN_ALWAYS_WINS = always;
		}
	}

	private static SongBuilder.PastePlan plan(List<SongBuilder.EventNote> notes,
			SongBuilder.PasteMode mode, GameSettings.Values game, int width, int floors) {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode,
			game.limits(width, floors));
	}

	private static void say(String label, SongBuilder.PastePlan plan) {
		System.out.println(String.format(
			"%-7s spanX %3d spanZ %4d area %6d cols %6d  faults %d wrong %d collisions %d breaches %d"
				+ " walls in %d out %d  railCols %d",
			label, plan.spanX(), plan.spanZ(), plan.spanX() * plan.spanZ(), plan.totalColumns(),
			plan.faults().size(), plan.wrongNotes(), plan.collisions().size(), plan.breaches().size(),
			plan.innerWallBreaches(), plan.outerWallBreaches(),
			plan.padding().entrySet().stream().filter(entry -> entry.getKey().startsWith("railPathColumn")
				|| entry.getKey().equals("railFloorNotes")).mapToInt(java.util.Map.Entry::getValue).sum()));
		plan.faults().forEach(fault -> System.out.println("          fault: " + fault));
		new java.util.TreeMap<>(plan.padding()).forEach((key, count) -> {
			if (key.toLowerCase(java.util.Locale.ROOT).contains("rail") || key.startsWith("pad")
					|| key.contains("corner") || key.contains("Corner")) {
				System.out.println("          " + key + " " + count);
			}
		});
	}
}
