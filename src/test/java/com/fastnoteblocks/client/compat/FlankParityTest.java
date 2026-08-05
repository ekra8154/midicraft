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
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ekran's rule: a nudge is only owed when the flanks that would meet actually exist.
 *
 * <p>Touching lanes run opposite ways and the stacked slots fill front-first, so a chord of five
 * hangs no back flank at all and two of them a column apart never reach each other. The check did
 * not know that -- it assumed all four low notes -- and every one of the 95 refusals is a module
 * nudged into the staircase by a clash that was not there.</p>
 *
 * <p>What settles it is {@code wrongNotes()}, not the size. Relaxing a parity rule is exactly the
 * change that reads back clean and sounds wrong: a note at the wrong tick still sounds, so the
 * count of note blocks comes out right either way. The read-back is here for what it does catch --
 * a module that goes silent -- and the fault count for the rest.</p>
 */
@Tag("sweep")
class FlankParityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.FLANK_AWARE_PARITY = true;
		SongBuilder.RELOCATES_CONTESTED_NOTE = true;
		SongBuilder.FRONT_HEAD_WHEN_BEHIND_BUSY = true;
		SongBuilder.REPLAN_ON_DRIFT = true;
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	/** One arm's running totals, so the two are added up over exactly the same builds. */
	private static final class Totals {
		long breaches;
		long guardian;
		long breachBlocks;
		long spanZ;
		long blocks;
		long wrong;
		long wrongBuilds;
		long nudges;
		long gaveUp;
		long unreached;
		long readBuilds;

		void add(SongBuilder.PastePlan plan, String song, boolean read) {
			breaches += plan.breaches().size();
			breachBlocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
			spanZ += plan.spanZ();
			blocks += plan.commands().size();
			wrong += plan.wrongNotes();
			wrongBuilds += plan.wrongNotes() > 0 ? 1 : 0;
			guardian += song.equals("deltarune-ch-4-guardian") ? plan.breaches().size() : 0;
			for (Map.Entry<String, Integer> pad : plan.padding().entrySet()) {
				if (pad.getKey().startsWith("planParityHadSlack")
						|| pad.getKey().startsWith("planParityTight")) {
					nudges += pad.getValue();
				}
				if (pad.getKey().startsWith("planParityGaveUp")) {
					gaveUp += pad.getValue();
				}
			}
			if (read) {
				readBuilds++;
				unreached += readAll(placeInWorld(plan)).unreachedNotes();
			}
		}

		String line(String label) {
			return "FLANK " + label + " wrong=" + wrong + " wrongBuilds=" + wrongBuilds
				+ " nudges=" + nudges + " gaveUpToBus=" + gaveUp
				+ " breaches=" + breaches + " (guardian=" + guardian + ")"
				+ " breachBlocks=" + breachBlocks + " spanZ=" + spanZ + " blocks=" + blocks
				+ " readBuilds=" + readBuilds + " unreached=" + unreached;
		}
	}

	/**
	 * Both rules on every build, and the sums taken only where both of them built something.
	 *
	 * <p>Measured in one pass and not two. The flag changes which builds are possible at all -- that
	 * is most of the point of it -- so two independent sweeps add up over different sets of builds,
	 * and every total moves for a reason that has nothing to do with the shape. That mistake has
	 * been made in this file before and reported as a result.</p>
	 */
	@Test
	void pricesTheFlankAwareRuleAgainstTheOldOne() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		Totals off = new Totals();
		Totals on = new Totals();
		int both = 0;
		int neither = 0;
		int onlyOn = 0;
		int onlyOff = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.FLANK_AWARE_PARITY = false;
					SongBuilder.PastePlan without = build(notes, width, floors);
					SongBuilder.FLANK_AWARE_PARITY = true;
					SongBuilder.PastePlan with = build(notes, width, floors);
					if (without == null && with == null) {
						neither++;
						continue;
					}
					if (without == null) {
						onlyOn++;
						continue;
					}
					if (with == null) {
						onlyOff++;
						continue;
					}
					both++;
					off.add(without, name, width == 24);
					on.add(with, name, width == 24);
				}
			}
		}
		System.out.println("FLANK builds: both=" + both + " onlyFlankAware=" + onlyOn
			+ " onlyOldRule=" + onlyOff + " neither=" + neither);
		System.out.println(off.line("oldRule "));
		System.out.println(on.line("flankAware"));
	}

	/**
	 * And the same for shedding: a contested back flank given to the bus instead of a nudge.
	 *
	 * <p>Paired the same way and for the same reason. What to watch is {@code wrong} -- the shed
	 * note moves to a cell the bus already had, so if the arithmetic is off it is off by a column and
	 * shows up as a breach, but if the <em>parity</em> reasoning is off it shows up here.</p>
	 */
	@Test
	void pricesSheddingABackFlankAgainstNudging() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		Totals off = new Totals();
		Totals on = new Totals();
		int both = 0;
		int onlyOn = 0;
		int onlyOff = 0;
		long sheds = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.RELOCATES_CONTESTED_NOTE = false;
					SongBuilder.PastePlan without = build(notes, width, floors);
					SongBuilder.RELOCATES_CONTESTED_NOTE = true;
					SongBuilder.PastePlan with = build(notes, width, floors);
					if (without == null && with == null) {
						continue;
					}
					if (without == null) {
						onlyOn++;
						continue;
					}
					if (with == null) {
						onlyOff++;
						continue;
					}
					both++;
					sheds += moves(with.padding());
					off.add(without, name, width == 24);
					on.add(with, name, width == 24);
				}
			}
		}
		System.out.println("SHED builds: both=" + both + " onlyShedding=" + onlyOn
			+ " onlyNudging=" + onlyOff + " shedsTaken=" + sheds);
		System.out.println(off.line("nudgeOnly "));
		System.out.println(on.line("shedsFlank"));
	}

	/**
	 * The front-only fallback, and re-planning when a chord lands off-plan, against neither.
	 *
	 * <p>A rigid stacked chord that loses the pair beside its opening used to fall all the way to a
	 * plain bus. The shape it should fall to already existed and is never longer. Paired, because
	 * the fallback changes which builds are possible.</p>
	 */
	@Test
	void pricesTheFrontOnlyFallback() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		Totals off = new Totals();
		Totals on = new Totals();
		int both = 0;
		int onlyOn = 0;
		int onlyOff = 0;
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
			if (name.startsWith("ultra-")) {
				continue;
			}
			List<SongBuilder.EventNote> notes = load(name);
			if (notes.isEmpty()) {
				continue;
			}
			for (int floors = 2; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.FRONT_HEAD_WHEN_BEHIND_BUSY = false;
					SongBuilder.REPLAN_ON_DRIFT = false;
					SongBuilder.PastePlan without = build(notes, width, floors);
					SongBuilder.FRONT_HEAD_WHEN_BEHIND_BUSY = true;
					SongBuilder.REPLAN_ON_DRIFT = true;
					SongBuilder.PastePlan with = build(notes, width, floors);
					if (without == null && with == null) {
						continue;
					}
					if (without == null) {
						onlyOn++;
						continue;
					}
					if (with == null) {
						onlyOff++;
						continue;
					}
					both++;
					off.add(without, name, width == 24);
					on.add(with, name, width == 24);
				}
			}
		}
		System.out.println("FRONTHEAD builds: both=" + both + " onlyFallback=" + onlyOn
			+ " onlyPlainBus=" + onlyOff);
		System.out.println(off.line("plainBus  "));
		System.out.println(on.line("frontHead "));
	}

	/** Every relocation the plan took, whichever slot it freed and wherever the note went. */
	private static int moves(java.util.Map<String, Integer> padding) {
		return padding.entrySet().stream()
			.filter(pad -> pad.getKey().startsWith("planRelocateTo"))
			.mapToInt(java.util.Map.Entry::getValue).sum();
	}

	private static SongBuilder.PastePlan build(List<SongBuilder.EventNote> notes, int width,
			int floors) {
		try {
			return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(4, width, floors));
		} catch (RuntimeException refused) {
			return null;
		}
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

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (Exception broken) {
			throw new IllegalStateException(blockState, broken);
		}
	}

	private static Map<BlockPos, BlockState> placeInWorld(SongBuilder.PastePlan plan) {
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(Map<BlockPos, BlockState> world) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos at : world.keySet()) {
			minX = Math.min(minX, at.getX());
			minY = Math.min(minY, at.getY());
			minZ = Math.min(minZ, at.getZ());
			maxX = Math.max(maxX, at.getX());
			maxY = Math.max(maxY, at.getY());
			maxZ = Math.max(maxZ, at.getZ());
		}
		return NoteMachineReader.read("Flank parity", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
