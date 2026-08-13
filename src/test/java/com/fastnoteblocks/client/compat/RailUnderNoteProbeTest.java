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

	/** The blocks either side of the first fault, so the two shapes that met can be named. */
	@Test
	void dumpsTheGroundAroundAFault() throws Exception {
		// SongBuilder.TRACE prints a RAIL line per column beside this, which is what named the run
		// that walked through a staircase. Left off: it is a thousand lines on this song.
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			load("ultra-ones-gap2"), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(16, 40, 2));
		Map<BlockPos, String> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parts[4]);
		}
		for (int y = 67; y >= 62; y--) {
			for (int z = 14; z <= 17; z++) {
				StringBuilder row = new StringBuilder(String.format("DUMP y=%d z=%2d ", y, z));
				for (int x = 35; x <= 43; x++) {
					row.append(String.format(" %-9s", shorten(world.get(new BlockPos(x, y, z)))));
				}
				if (!row.toString().isBlank() && row.toString().contains("minecraft") || true) {
					System.out.println(row);
				}
			}
		}
	}

	private static String shorten(String block) {
		if (block == null) {
			return ".";
		}
		String name = block.replace("minecraft:", "");
		if (name.startsWith("repeater")) {
			return "r" + name.replaceAll(".*delay=(\\d+).*", "$1")
				+ name.replaceAll(".*facing=(\\w)\\w*.*", "$1");
		}
		if (name.startsWith("note_block")) {
			return "NOTE";
		}
		return name.startsWith("redstone_wire") ? "dust" : name;
	}

	/** The faults themselves, which name the two cells and so the two shapes that met. */
	@Test
	void namesEveryFault() throws Exception {
		for (int[] size : new int[][] {{40, 2}, {16, 2}, {16, 5}}) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				load("ultra-ones-gap2"), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, size[0], size[1]));
			System.out.println("FAULT " + size[0] + " wide over " + size[1] + " floors: "
				+ plan.faults().size());
			plan.faults().stream().limit(4)
				.forEach(fault -> System.out.println("FAULT   " + fault));
		}
	}

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
