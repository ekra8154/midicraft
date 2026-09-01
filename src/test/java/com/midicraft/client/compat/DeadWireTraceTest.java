package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
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

/** Scratch probe: the walk trace for the one build with a dead run, plus the run itself unshifted. */
@Tag("sweep")
class DeadWireTraceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void traces() throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs",
			"ultra-limit-two-thirties.json");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		SongBuilder.TRACE = true;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 36, 1));
		SongBuilder.TRACE = false;
		List<String> commands = plan.commands();
		int dust = 0;
		int runStart = 0;
		String from = "start";
		for (int index = 0; index < commands.size(); index++) {
			String[] parts = commands.get(index).split(" ");
			String where = parts[1] + " " + parts[2] + " " + parts[3];
			if (parts[4].startsWith("minecraft:redstone_wire")) {
				if (dust == 0) {
					runStart = index;
				}
				dust++;
				continue;
			}
			if (!parts[4].startsWith("minecraft:repeater")) {
				continue;
			}
			if (dust > 15) {
				System.out.println("WALK !!! run of " + dust + " from [" + from + "] to [" + where
					+ "] commands " + runStart + ".." + index);
				for (int near = Math.max(0, runStart - 40); near <= index + 4
						&& near < commands.size(); near++) {
					System.out.println("WALK   " + near + " " + commands.get(near));
				}
			}
			dust = 0;
			from = where;
		}
		System.out.println("WALK done");
		java.util.Map<String, String> world = new java.util.HashMap<>();
		for (String command : commands) {
			String[] parts = command.split(" ");
			world.put(parts[1] + " " + parts[2] + " " + parts[3], parts[4]);
		}
		for (int y = 66; y >= 63; y--) {
			System.out.println("MAP y=" + y + "   x= 50 .. 60");
			for (int z = 17; z <= 26; z++) {
				StringBuilder row = new StringBuilder("MAP z=" + (z < 10 ? " " : "") + z + "  ");
				for (int x = 50; x <= 60; x++) {
					row.append(' ').append(symbol(world.get(x + " " + y + " " + z)));
				}
				System.out.println(row);
			}
		}
	}

	private static char symbol(String block) {
		if (block == null || block.startsWith("minecraft:air")) {
			return '.';
		}
		if (block.startsWith("minecraft:redstone_wire")) {
			return '-';
		}
		if (block.startsWith("minecraft:repeater")) {
			return 'R';
		}
		if (block.startsWith("minecraft:note_block")) {
			return 'N';
		}
		if (block.startsWith("minecraft:stone")) {
			return '#';
		}
		return 'i';
	}
}
