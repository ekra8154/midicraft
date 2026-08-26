package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway: the builds the wait fold broke, collisions tolerated and every cell named. */
@Tag("sweep")
class FoldFaultProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void nameTheCollisions() throws Exception {
		SongBuilder.NAME_EVERY_CELL = true;
		SongBuilder.DEBUG_PASTE = true;
		SongBuilder.TRACE_TURNS = Boolean.getBoolean("probe.trace");
		String[][] failing = java.util.Arrays.stream(System.getProperty("probe.builds",
				"field-of-hopes-and-dreams:24x1").split(","))
			.map(spec -> spec.strip().split("[:x]"))
			.toArray(String[][]::new);
		for (String[] build : failing) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(BreachView.songFile(build[0]))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			SongBuilder.PastePlan plan = SongBuilder.createInterleavedHalfTickPastePlan(
				new BlockPos(0, 64, 0), Direction.EAST, notes,
				new SongBuilder.BuildLimits(16, Integer.parseInt(build[1]),
					Integer.parseInt(build[2])),
				SongBuilder.WalkStart.HEAD);
			System.out.println("== " + build[0] + " " + build[1] + "x" + build[2]
				+ " wrong=" + plan.wrongNotes() + " missing=" + plan.missingNotes()
				+ " collisions=" + plan.collisions().size()
				+ " span=" + plan.spanX() + " built=" + plan.builtWidth()
				+ " walls=" + plan.nearWall() + ".." + plan.farWall());
			plan.collisions().entrySet().stream().limit(12)
				.forEach(clash -> System.out.println("   " + clash.getKey().getX() + " "
					+ clash.getKey().getY() + " " + clash.getKey().getZ() + " "
					+ clash.getValue()));
			plan.faults().stream()
				.filter(fault -> fault.startsWith("the note"))
				.limit(12)
				.forEach(fault -> System.out.println("   " + fault));
			if (Boolean.getBoolean("probe.extremes")) {
				int minX = plan.commands().stream().mapToInt(c -> Integer.parseInt(c.split(" ")[1]))
					.min().orElse(0);
				int maxX = plan.commands().stream().mapToInt(c -> Integer.parseInt(c.split(" ")[1]))
					.max().orElse(0);
				System.out.println("  extremes minX=" + minX + " maxX=" + maxX);
				for (String command : plan.commands()) {
					int x = Integer.parseInt(command.split(" ")[1]);
					if (x <= minX || x >= maxX) {
						String[] token = command.split(" ", 5);
						BlockPos at = new BlockPos(x, Integer.parseInt(token[2]),
							Integer.parseInt(token[3]));
						System.out.println("   " + command + "   <- "
							+ plan.laidBy().getOrDefault(at, "?"));
					}
				}
			}
			String[] area = System.getProperty("probe.area", "").split("[,x]");
			if (area.length == 4) {
				int x0 = Integer.parseInt(area[0]);
				int x1 = Integer.parseInt(area[1]);
				int z0 = Integer.parseInt(area[2]);
				int z1 = Integer.parseInt(area[3]);
				for (int y = 66; y >= 63; y--) {
					System.out.println("  -- y=" + y);
					for (int z = z0; z <= z1; z++) {
						StringBuilder row = new StringBuilder(String.format("  z=%3d ", z));
						for (int x = x0; x <= x1; x++) {
							String who = plan.laidBy().get(new BlockPos(x, y, z));
							row.append(String.format("%-26.26s",
								who == null ? "." : who.replace("minecraft:", "")));
						}
						System.out.println(row);
					}
				}
			}
		}
		SongBuilder.NAME_EVERY_CELL = false;
		SongBuilder.DEBUG_PASTE = false;
	}
}
