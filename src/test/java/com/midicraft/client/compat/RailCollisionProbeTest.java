package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Which two shapes wanted the same cell, named rather than guessed at. */
@Tag("sweep")
class RailCollisionProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	@Test
	void namesTheShapesThatCollide() throws Exception {
		SongBuilder.DEBUG_PASTE = true;
		try {
			Map<String, Integer> byShapes = new TreeMap<>();
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				load("ultra-threes-mixed"), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, 24, 2));
			Map<BlockPos, String> world = new java.util.HashMap<>();
			for (String command : plan.commands()) {
				String[] parts = command.split(" ");
				world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
					Integer.parseInt(parts[3])), parts[4]);
			}
			plan.collisions().forEach((at, what) -> {
				byShapes.merge(what.replaceAll("minecraft:\\S+ ", ""), 1, Integer::sum);
				if (byShapes.size() <= 6) {
					System.out.println("CLASH at " + at.getX() + " " + at.getY() + " " + at.getZ()
						+ ": " + what);
					for (int y = at.getY() + 1; y >= at.getY() - 2; y--) {
						StringBuilder row = new StringBuilder("CLASH   y=" + y + "  ");
						for (int z = at.getZ() - 2; z <= at.getZ() + 2; z++) {
							row.append(String.format(" z%d[", z));
							for (int x = at.getX() - 1; x <= at.getX() + 1; x++) {
								String block = world.get(new BlockPos(x, y, z));
								row.append(block == null ? "." : block.replace("minecraft:", "")
									.replaceAll("repeater.*delay=(\\d).*", "r$1")).append(' ');
							}
							row.append(']');
						}
						System.out.println(row);
					}
				}
			});
			System.out.println("CLASH " + plan.collisions().size() + " cells");
			byShapes.forEach((what, count) -> System.out.println("CLASH  " + count + "x  " + what));
		} finally {
			SongBuilder.DEBUG_PASTE = false;
		}
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
