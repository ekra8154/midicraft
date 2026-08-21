package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lists where a build hung its extras -- the stair pair and the border wall harp -- so each can
 * be walked to in game. Coordinates are absolute for a paste at 0 64 0; re-aim with
 * {@code -Dprobe.song=} / {@code -Dprobe.width=} / {@code -Dprobe.floors=}.
 */
@Tag("sweep")
class ExtraCellsProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void listsTheExtraCells() throws Exception {
		boolean names = SongBuilder.NAME_EVERY_CELL;
		SongBuilder.NAME_EVERY_CELL = true;
		try {
			String name = System.getProperty("probe.song", "guardian25");
			int width = Integer.parseInt(System.getProperty("probe.width", "20"));
			int floors = Integer.parseInt(System.getProperty("probe.floors", "5"));
			java.nio.file.Path file = java.nio.file.Path.of("run", "config", "fast-noteblocks",
				"songs", name + ".json");
			ComposerProject raw;
			try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
				raw = new com.google.gson.Gson().fromJson(reader, ComposerProject.class);
			}
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
				raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true));
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
				new SongBuilder.BuildLimits(4, width, floors));
			Map<BlockPos, String> blocks = new LinkedHashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				blocks.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parts[4]);
			}
			Map<String, List<BlockPos>> found = new LinkedHashMap<>();
			for (Map.Entry<BlockPos, String> cell : plan.laidBy().entrySet()) {
				String by = cell.getValue();
				if (by == null) {
					continue;
				}
				String block = blocks.get(cell.getKey());
				if (block == null) {
					continue;
				}
				if (by.startsWith("stairExtras") && block.startsWith("minecraft:note_block")) {
					found.computeIfAbsent("stair extra", unused -> new ArrayList<>())
						.add(cell.getKey());
				} else if (by.startsWith("ascentRungExtra")
						&& block.startsWith("minecraft:note_block")) {
					found.computeIfAbsent("ascent rung extra", unused -> new ArrayList<>())
						.add(cell.getKey());
				}
			}
			System.out.println("EXTRAS " + name + " " + width + "x" + floors
				+ " (paste origin 0 64 0; coordinates are absolute)");
			for (Map.Entry<String, List<BlockPos>> kind : found.entrySet()) {
				System.out.println("EXTRAS   " + kind.getKey() + ": " + kind.getValue().size());
				for (int index = 0; index < kind.getValue().size() && index < 5; index++) {
					BlockPos at = kind.getValue().get(index);
					System.out.println("EXTRAS     " + at.getX() + " " + at.getY() + " "
						+ at.getZ());
				}
			}
		} finally {
			SongBuilder.NAME_EVERY_CELL = names;
		}
	}
}
