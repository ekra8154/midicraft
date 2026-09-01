package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: how much of the breach count is footprint, and how much is the planner's optimism.
 *
 * <p>{@link WallTurnBisectTest} found a build whose breach doubled when {@code STACKED_BUS_HEADS}
 * came on and whose blocks did not change at all -- same 141 commands, same order, same columns.
 * What moved was {@code farWall}: the planner set the wall expecting a stacked bus, the walk
 * declined to build one, and the identical machine was then measured against a tighter promise.</p>
 *
 * <p>A breach is meant to mean the paste covers ground the player was told it would not. Where the
 * build is unchanged and only the wall moved, it means no such thing -- and the breach count is
 * what the paste dialog forecasts from and what every A/B here has been scored on. So the question
 * is not how many breaches there are but how many of them are in the blocks, and the only way to
 * answer it is to compare the commands rather than the counts.</p>
 *
 * <p><b>Answered: none of them, for a song.</b> Over 1,560 real builds, 300 come out with identical
 * commands under the shape and off it, and every one of those 300 reports identical breaches. The
 * metric is sound where it is used, and the shape is a real improvement rather than a bookkeeping
 * one -- 652 breaches down to 619, all of it on builds whose blocks actually changed.</p>
 *
 * <p>The divergence is real but belongs to the harness. Under a <em>seeded</em> start it happens
 * readily: five of the twelve identical builds here move their {@code farWall}, the repro among
 * them. A song never seeds -- it walks from the head of its first lane -- so this reaches exactly
 * two things: the repro tests pinned on breach depth, and the wall that
 * {@code /midicraft paste} prints, which is the number in-game reading shows a breach off when
 * diagnosing one.</p>
 */
@Tag("sweep")
class BreachIsRealTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.STACKED_BUS_HEADS = true;
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private static List<SongBuilder.EventNote> load(Path file) throws Exception {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	private static int worst(SongBuilder.PastePlan plan) {
		int worst = 0;
		for (int blocks : plan.breaches()) {
			worst = Math.max(worst, blocks);
		}
		return worst;
	}

	/**
	 * The same question of a seeded start, which is the one thing the repro does and a song never.
	 *
	 * <p>A song walks from the head of its first lane. A repro is handed a floor, a direction and a
	 * column, so that a descent can be met without building every lane in front of it. If the wall
	 * only diverges from the build under a seed, then the two repro tests pinned on breaches are
	 * measuring the harness rather than the builder.</p>
	 */
	@Test
	void asksTheSameOfASeededStart() throws Exception {
		long builds = 0;
		long identical = 0;
		long identicalButMoved = 0;

		for (String spec : List.of("21@1 1@1 9@1", "24@1 1@1 9@1", "18@1 2@1 10@1",
				"21@1 1@1 9@1 21@1", "30@1 1@1 9@1")) {
			for (int width = 12; width <= 24; width += 4) {
				for (int columnsToWall = 4; columnsToWall <= width - 2; columnsToWall += 2) {
					int column = Math.max(0, width - 2 - columnsToWall);
					SongBuilder.WalkStart seed = new SongBuilder.WalkStart(column, 2, -1, false);
					SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(4, width, 3);
					List<SongBuilder.EventNote> notes =
						DebugChords.notes(DebugChords.parse(spec, DebugChords.DEFAULT_GAP));
					SongBuilder.PastePlan off;
					SongBuilder.PastePlan on;
					try {
						SongBuilder.STACKED_BUS_HEADS = false;
						off = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE, limits, seed);
						SongBuilder.STACKED_BUS_HEADS = true;
						on = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE, limits, seed);
					} catch (RuntimeException refused) {
						continue;
					}
					builds++;
					if (!off.commands().equals(on.commands())) {
						continue;
					}
					identical++;
					if (off.breaches().size() == on.breaches().size() && worst(off) == worst(on)
							&& off.farWall() == on.farWall()) {
						continue;
					}
					identicalButMoved++;
					System.out.println("SEED   " + spec + " w" + width + " cols" + columnsToWall
						+ " | farWall " + off.farWall() + " -> " + on.farWall()
						+ " | worst " + worst(off) + " -> " + worst(on)
						+ " | blocks " + on.commands().size());
				}
			}
		}
		System.out.println("SEED builds " + builds + " identical " + identical
			+ " identicalButMoved " + identicalButMoved);

	}

	@Test
	void countsTheBreachesThatAreOnlyInThePromise() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		long builds = 0;
		// Builds where the blocks are identical with the shape on and off.
		long sameBuild = 0;
		// ... and of those, the ones whose breach count moved anyway. Pure accounting.
		long sameBuildBreachMoved = 0;
		long accountingBreaches = 0;
		long accountingBlocks = 0;
		// Builds the shape genuinely changed, and what that did to their breaches.
		long realChange = 0;
		long realBreachDelta = 0;
		long onBreaches = 0;
		long offBreaches = 0;
		TreeMap<String, Long> accountingBySong = new TreeMap<>();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(file);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(4, width, floors);
					SongBuilder.STACKED_BUS_HEADS = false;
					SongBuilder.PastePlan off = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE, limits);
					SongBuilder.STACKED_BUS_HEADS = true;
					SongBuilder.PastePlan on = SongBuilder.createPastePlan(
						new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE, limits);
					builds++;
					offBreaches += off.breaches().size();
					onBreaches += on.breaches().size();
					boolean identical = off.commands().equals(on.commands());
					if (identical) {
						sameBuild++;
						if (off.breaches().size() != on.breaches().size()
								|| worst(off) != worst(on)) {
							sameBuildBreachMoved++;
							accountingBreaches += on.breaches().size();
							for (int blocks : on.breaches()) {
								accountingBlocks += blocks;
							}
							accountingBySong.merge(name, 1L, Long::sum);
						}
					} else {
						realChange++;
						realBreachDelta += on.breaches().size() - off.breaches().size();
					}
				}
			}
		}
		System.out.println("REAL builds " + builds);
		System.out.println("REAL breaches off " + offBreaches + " -> on " + onBreaches);
		System.out.println("REAL identical builds " + sameBuild
			+ ", of which breach count or worst moved " + sameBuildBreachMoved);
		System.out.println("REAL breaches reported on identical builds " + accountingBreaches
			+ " (" + accountingBlocks + " blocks) -- these are promise, not footprint");
		System.out.println("REAL builds the shape really changed " + realChange
			+ ", their breach delta " + realBreachDelta);
		accountingBySong.forEach((song, count) ->
			System.out.println("REAL   " + String.format("%-46s", song) + count));
	}
}
