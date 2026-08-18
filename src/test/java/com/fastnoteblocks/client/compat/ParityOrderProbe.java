package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

/**
 * Which cell went down first, for a parity clash where each side's check covers the other.
 *
 * <p>A stacked module asks {@link SongBuilder#stackedClashes} about the lane beside it, and the
 * question is symmetric: whichever of two touching modules is built second sees the first. So a
 * clash that got through means one of the two never asked, and the only way to tell which is to
 * look at the order the blocks actually went down in.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*ParityOrderProbe" -Dparity.song=golden-brown-2xspeed \
 *     -Dparity.width=40 -Dparity.floors=5 -Dparity.cells=34,76,29;34,76,30
 * </pre>
 *
 * <p>Prints; asserts nothing.</p>
 */
@Tag("sweep")
class ParityOrderProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	@Test
	void whoWasFirst() throws Exception {
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
		List<SongBuilder.EventNote> notes;
		try (Reader reader = Files.newBufferedReader(
				songs.resolve(text("song", "golden-brown-2xspeed") + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
		SongBuilder.NAME_EVERY_CELL = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2,
			new SongBuilder.BuildLimits(16, Integer.parseInt(text("width", "40")),
				Integer.parseInt(text("floors", "5"))));
		List<String> commands = plan.commands();
		Map<BlockPos, Integer> first = new LinkedHashMap<>();
		Map<BlockPos, String> what = new LinkedHashMap<>();
		TreeMap<Integer, Integer> laneFirst = new TreeMap<>();
		int wantedY = Integer.parseInt(text("y", "76"));
		for (int index = 0; index < commands.size(); index++) {
			String[] word = commands.get(index).split(" ");
			if (word.length < 5) {
				continue;
			}
			BlockPos at;
			try {
				at = new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
					Integer.parseInt(word[3]));
			} catch (NumberFormatException wrongShape) {
				continue;
			}
			if (!first.containsKey(at)) {
				first.put(at, index);
				what.put(at, word[4]);
			}
			if (at.getY() == wantedY) {
				laneFirst.merge(at.getZ(), index, Math::min);
			}
		}
		System.out.println("commands " + commands.size());
		System.out.println();
		System.out.println("-- cells asked about");
		for (String cell : text("cells", "34,76,29|34,76,30").split("[;|_]")) {
			String[] part = cell.strip().split(",");
			BlockPos at = new BlockPos(Integer.parseInt(part[0]), Integer.parseInt(part[1]),
				Integer.parseInt(part[2]));
			System.out.println(String.format("%-14s order %8s  %-46s  tick %-6s  by %s",
				cell.strip(), first.containsKey(at) ? first.get(at) : "-",
				what.getOrDefault(at, "(nothing)"),
				String.valueOf(plan.noteTicks().get(at)),
				String.valueOf(plan.laidBy().get(at))));
		}
		System.out.println();
		System.out.println("-- first command in each z row at y=" + wantedY);
		laneFirst.forEach((z, index) ->
			System.out.println(String.format("z=%-4d order %8d", z, index)));
	}
}
