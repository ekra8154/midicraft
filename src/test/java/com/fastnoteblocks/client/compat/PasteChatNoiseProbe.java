package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
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
 * Scratch probe: how much of a paste is a command that cannot succeed?
 *
 * <p>A paste prints a line per command, and a good number of them say "Could not set the block".
 * The builds are fine afterwards, which is the part that makes it a mystery rather than a bug --
 * and the game's own answer, read out of {@code LevelChunk.setBlockState}, is that it returns
 * nothing at all in two cases, both of which are successes as far as the build is concerned:</p>
 *
 * <ul>
 *   <li>the new state is the state that was already there, compared by identity, which works
 *       because block states are interned;</li>
 *   <li>the block is air and the whole 16x16x16 section is already air -- not merely the cell.</li>
 * </ul>
 *
 * <p>{@code Level.setBlock} turns that nothing into false, {@code BlockInput.place} passes the
 * false along, and {@code SetBlockCommand} turns it into ERROR_FAILED. So the message means "that
 * cell already looked like this", and this counts how many of a real build's commands are in that
 * position before it is ever sent.</p>
 */
@Tag("sweep")
class PasteChatNoiseProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void countsTheCommandsThatCannotChangeAnything() throws Exception {
		List<String> songs = List.of("aria-math-c418", "big-shot", "deltarune-ch-4-guardian",
			"hopes-and-dreams");
		for (String name : songs) {
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
					raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
					raw.speedQuarters());
			}
			List<SongBuilder.EventNote> notes =
				SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
			if (notes.isEmpty()) {
				continue;
			}
			for (SongBuilder.PasteMode mode : List.of(SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					SongBuilder.PasteMode.COMPACT_LANE)) {
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode,
						new SongBuilder.BuildLimits(4, 32, 3));
				} catch (RuntimeException refused) {
					continue;
				}
				TreeMap<String, Integer> byBlock = new TreeMap<>();
				for (String command : plan.commands()) {
					byBlock.merge(CommandPasteSender.read(command).block(), 1, Integer::sum);
				}
				int total = plan.commands().size();
				int air = byBlock.getOrDefault("minecraft:air", 0);
				System.out.println(String.format("%-26s %-20s notes=%d commands=%d air=%d (%.1f%%)",
					name, mode, notes.size(), total, air, 100.0 * air / total));
				byBlock.entrySet().stream()
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
					.limit(6)
					.forEach(entry -> System.out.println("      " + entry.getValue() + "  "
						+ entry.getKey()));
			}
		}
	}
}
