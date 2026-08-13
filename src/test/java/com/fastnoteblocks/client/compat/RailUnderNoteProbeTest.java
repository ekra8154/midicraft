package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

/** What is standing under every note block of a rail build, counted rather than guessed at. */
@Tag("sweep")
class RailUnderNoteProbeTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void countsWhatEveryNoteStandsOn() throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			load("ultra-ones-gap2"), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(16, 16, 2));
		Map<BlockPos, String> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parts[4]);
		}
		Map<String, Integer> under = new TreeMap<>();
		int shown = 0;
		for (Map.Entry<BlockPos, String> block : world.entrySet()) {
			if (!block.getValue().startsWith("minecraft:note_block")) {
				continue;
			}
			String below = world.getOrDefault(block.getKey().below(), "<nothing>");
			under.merge(below, 1, Integer::sum);
			if (!below.equals("minecraft:air") && !below.equals("<nothing>") && shown++ < 6) {
				System.out.println("UNDER note at " + block.getKey().getX() + " "
					+ block.getKey().getY() + " " + block.getKey().getZ() + " stands " + below
					+ ", and around it: above=" + world.get(block.getKey().above())
					+ " behindLow=" + world.get(block.getKey().below().west())
					+ " aheadLow=" + world.get(block.getKey().below().east()));
			}
		}
		System.out.println("UNDER " + under);
	}

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}
}
