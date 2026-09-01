package com.midicraft.client.compat;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: does the dead-wire count change when the stacked module's cross is not counted?
 *
 * <p>The cross sits under the module's centre block, not on the signal path -- the path is repeater,
 * note block, next cell. But it is a redstone_wire like any other, so a run measured by counting
 * wire between repeaters counts it. If every "dead" run only reaches sixteen by including a cross,
 * the runs are fine and the probe is wrong.</p>
 */
class CrossCountingTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static boolean isCross(String block) {
		return block.startsWith("minecraft:redstone_wire[");
	}

	@Test
	void counts() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(
				Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		int deadCounting = 0;
		int deadIgnoringCross = 0;
		for (Path file : files) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = gson.fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refused) {
						continue;
					}
					int all = 0;
					int noCross = 0;
					boolean deadAll = false;
					boolean deadNo = false;
					for (String command : plan.commands()) {
						String block = command.split(" ")[4];
						if (block.startsWith("minecraft:redstone_wire")) {
							all++;
							if (!isCross(block)) {
								noCross++;
							}
							continue;
						}
						if (!block.startsWith("minecraft:repeater")) {
							continue;
						}
						deadAll |= all > 15;
						deadNo |= noCross > 15;
						all = 0;
						noCross = 0;
					}
					if (deadAll) {
						deadCounting++;
					}
					if (deadNo) {
						deadIgnoringCross++;
					}
				}
			}
		}
		System.out.println("CROSS dead counting every wire   = " + deadCounting);
		System.out.println("CROSS dead ignoring the cross    = " + deadIgnoringCross);
	}
}
