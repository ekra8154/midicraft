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
 * The third option: a contested note moves out of its slot, and the module keeps its column.
 *
 * <p>The old rule could only push a note onto a bus, so only a stacked-bus ever had the option. Two
 * things are new: the note may take a free centre, which every stacked shape has and which costs no
 * cell at all, and any of the four low slots may be the one that moves rather than the back pair.</p>
 *
 * <p>What settles it is {@code wrong} and {@code unreached}, not the size. This is a parity rule,
 * and a parity rule got wrong reads back clean: a note sounding at somebody else's tick is still a
 * note block in the right place, so the counts agree and the build is wrong anyway. {@code wrong}
 * is the one that sees it.</p>
 */
@Tag("sweep")
class RelocationTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void restore() {
		SongBuilder.RELOCATES_CONTESTED_NOTE = true;
		SongBuilder.RELOCATES_TO_CENTRE = true;
		SongBuilder.RELOCATES_ANY_CORNER = true;
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

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
		long toCentre;
		long toTail;
		long refusedGrowth;
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
			toCentre += plan.padding().getOrDefault("planRelocateToCentre", 0);
			toTail += plan.padding().getOrDefault("planRelocateToTail", 0);
			refusedGrowth += plan.padding().getOrDefault("planRelocationWouldGrowTheBus", 0);
			if (read) {
				readBuilds++;
				unreached += readAll(placeInWorld(plan)).unreachedNotes();
			}
		}

		String line(String label) {
			return "MOVE " + label + " wrong=" + wrong + " wrongBuilds=" + wrongBuilds
				+ " nudges=" + nudges + " gaveUpToBus=" + gaveUp
				+ " toCentre=" + toCentre + " toTail=" + toTail + " grewTheBus=" + refusedGrowth
				+ " breaches=" + breaches + " (guardian=" + guardian + ")"
				+ " breachBlocks=" + breachBlocks + " spanZ=" + spanZ + " blocks=" + blocks
				+ " readBuilds=" + readBuilds + " unreached=" + unreached;
		}
	}

	/** The whole third option against having none: shift or give the shape up. */
	@Test
	void pricesRelocatingAgainstNudging() throws Exception {
		sweep("none      ", "relocating", () -> {
			SongBuilder.RELOCATES_CONTESTED_NOTE = false;
		}, () -> {
			SongBuilder.RELOCATES_CONTESTED_NOTE = true;
			SongBuilder.RELOCATES_TO_CENTRE = true;
			SongBuilder.RELOCATES_ANY_CORNER = true;
		});
	}

	/**
	 * And the new half alone, against the back-flank-onto-the-bus rule it grew out of.
	 *
	 * <p>This is the comparison that says whether the centre was worth adding, because the old rule
	 * already had the stacked-bus. What the centre opens is the plain stacked chord, which has no bus
	 * to give a note to and so had no third option at all.</p>
	 */
	@Test
	void pricesTheCentreAgainstTheBusOnly() throws Exception {
		sweep("busOnly   ", "centreToo ", () -> {
			SongBuilder.RELOCATES_CONTESTED_NOTE = true;
			SongBuilder.RELOCATES_TO_CENTRE = false;
			SongBuilder.RELOCATES_ANY_CORNER = false;
		}, () -> {
			SongBuilder.RELOCATES_CONTESTED_NOTE = true;
			SongBuilder.RELOCATES_TO_CENTRE = true;
			SongBuilder.RELOCATES_ANY_CORNER = true;
		});
	}

	/**
	 * Both arms on every build, and the sums taken only where both of them built something.
	 *
	 * <p>Measured in one pass and not two. The rule changes which builds are possible at all, so two
	 * independent sweeps add up over different sets of builds and every total moves for a reason that
	 * has nothing to do with the shape.</p>
	 */
	private static void sweep(String offLabel, String onLabel, Runnable off, Runnable on)
			throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(f -> f.toString().endsWith(".json")).sorted().toList();
		}
		Totals without = new Totals();
		Totals with = new Totals();
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
					off.run();
					SongBuilder.PastePlan before = build(notes, width, floors);
					on.run();
					SongBuilder.PastePlan after = build(notes, width, floors);
					if (before == null && after == null) {
						continue;
					}
					if (before == null) {
						onlyOn++;
						continue;
					}
					if (after == null) {
						onlyOff++;
						continue;
					}
					both++;
					without.add(before, name, width == 24);
					with.add(after, name, width == 24);
				}
			}
		}
		System.out.println("MOVE builds: both=" + both + " only" + onLabel.trim() + "=" + onlyOn
			+ " only" + offLabel.trim() + "=" + onlyOff);
		System.out.println(without.line(offLabel));
		System.out.println(with.line(onLabel));
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
		try (Reader reader = Files.newBufferedReader(BreachView.songFile(name))) {
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
		return NoteMachineReader.read("Relocation", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			position -> world.getOrDefault(position, Blocks.AIR.defaultBlockState()));
	}
}
