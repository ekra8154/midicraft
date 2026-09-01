package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch probe: what the first turn of a top start actually is, and whether it wastes floors. */
@Tag("sweep")
class TopFirstTurnTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void showsTheFirstTurn() throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs",
			"deltarune-ch-4-guardian.json");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		for (int floors = 1; floors <= 4; floors++) {
			for (int top = 0; top <= 1; top++) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, 24, floors, top == 1));
				// Where the build actually stands, and where its first two turns land.
				int minY = plan.commands().stream()
					.mapToInt(c -> Integer.parseInt(c.split(" ")[2])).min().orElse(0);
				int maxY = plan.commands().stream()
					.mapToInt(c -> Integer.parseInt(c.split(" ")[2])).max().orElse(0);
				StringBuilder firstTurns = new StringBuilder();
				for (int i = 0; i < Math.min(3, plan.turns().size()); i++) {
					firstTurns.append(" y=").append(plan.turns().get(i).getY());
				}
				System.out.println("FIRSTTURN f" + floors + (top == 1 ? " top   " : " bottom")
					+ " y=" + minY + ".." + maxY + " height=" + plan.height()
					+ " blocks=" + plan.commands().size()
					+ " firstTurns" + firstTurns);
			}
		}
	}
}
