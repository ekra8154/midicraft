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
import org.junit.jupiter.api.Test;

/** Scratch probe: the walk lines around a breach, so the overshoot can be read off. */
class BreachTraceTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static void trace(String name, int floors, int width) throws Exception {
		Path file = Path.of("run", "config", "midicraft", "songs", name + ".json");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		System.out.println("=== TRACE " + name + " f" + floors + " w" + width + " ===");
		SongBuilder.TRACE = true;
		try {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, width, floors));
			for (String fault : plan.faults()) {
				System.out.println("FAULT " + fault);
			}
		} finally {
			SongBuilder.TRACE = false;
		}
	}

	/**
	 * No song somebody actually wrote turns past its wall, at any width or any number of floors.
	 *
	 * <p>The goal itself rather than a number that moves with it. Stress songs are left out on
	 * purpose: they exist to be impossible, and a build that has to drop notes to fit has already
	 * lost the argument this is about. What is pinned here is the promise the mode makes to somebody
	 * pasting their own music -- that the walls are where the walls are.</p>
	 */
	@Test
	void listsTheRealBreachesLeft() throws Exception {
		List<String> breaches = new java.util.ArrayList<>();
		List<Path> files;
		try (java.util.stream.Stream<Path> listing =
				Files.list(Path.of("run", "config", "midicraft", "songs"))) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			ComposerProject song;
			try (Reader reader = Files.newBufferedReader(file)) {
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
					for (String fault : plan.faults()) {
						if (fault.startsWith("a lane turned -")) {
							System.out.println("LEFT " + name + " f" + floors + " w" + width
								+ " : " + fault);
							breaches.add(name + " f" + floors + " w" + width + " : " + fault);
						}
					}
				}
			}
		}
		org.junit.jupiter.api.Assertions.assertEquals(List.of(), breaches,
			"no song somebody wrote should turn past its wall");
	}

	@Test
	void illitFourTwelve() throws Exception {
		trace("illit-do-the-dance", 4, 12);
	}

	@Test
	void illitSixTwelve() throws Exception {
		trace("illit-do-the-dance", 6, 12);
	}

	/** The breach the one-lane lookahead bought, and the shape all four left in the library share. */
	@Test
	void bigShotThreeTwelve() throws Exception {
		trace("big-shot", 3, 12);
	}

	/**
	 * The same build with the lookahead switched off, so the lane it changed can be read side by side.
	 *
	 * <p>Every hand-derivation of this arithmetic in the session that found it was off by one. The
	 * cheapest honest way to find out what a change did to a lane is to build the lane both ways.</p>
	 */
	@Test
	void bigShotThreeTwelveWithoutLookahead() throws Exception {
		SongBuilder.LOOKAHEAD = false;
		try {
			trace("big-shot", 3, 12);
		} finally {
			SongBuilder.LOOKAHEAD = true;
		}
	}

	/** The same shape in the song the lookahead was written for, now that its old one is gone. */
	@Test
	void illitTwoTwelve() throws Exception {
		trace("illit-do-the-dance", 2, 12);
	}
}
