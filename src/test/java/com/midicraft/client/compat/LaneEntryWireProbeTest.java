package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: the blocks actually laid around the lane that breaches, rather than the
 * arithmetic that says what should be there.
 *
 * <p>The planner says the wire arriving at that lane is worth three and that moving its first
 * chord three columns forward is therefore one column too far. Moved three in world, it
 * fired. One of those is wrong, and blocks settle it.</p>
 */
@Tag("sweep")
class LaneEntryWireProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dumpsTheBlocksAroundTheBreachingLane() throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs",
			"illit-do-the-dance.json");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 20, 6));
		System.out.println("WALLS near=" + plan.nearWall() + " far=" + plan.farWall());
		// Everything in the corridor the failing lane runs down, keyed by column then height, so a
		// run of dust can be followed along the lane and counted.
		TreeMap<Integer, TreeMap<Integer, String>> byColumn = new TreeMap<>();
		for (String command : plan.commands()) {
			String[] words = command.split(" ");
			int x = Integer.parseInt(words[1]);
			int y = Integer.parseInt(words[2]);
			int z = Integer.parseInt(words[3]);
			if (z < 133 || z > 137 || x < -10 || x > 22) {
				continue;
			}
			String block = words[4].split("\\[")[0].replace("minecraft:", "");
			if (block.equals("air") || block.equals("note_block") || block.equals("glass")) {
				continue;
			}
			byColumn.computeIfAbsent(x, key -> new TreeMap<>())
				.merge(y, block + "@z" + z, (a, b) -> a + " " + b);
		}
		for (var column : byColumn.entrySet()) {
			for (var level : column.getValue().entrySet()) {
				if (level.getValue().contains("repeater") || level.getValue().contains("wire")) {
					System.out.println("COL x=" + column.getKey() + " y=" + level.getKey()
						+ " : " + level.getValue());
				}
			}
		}
	}
}
