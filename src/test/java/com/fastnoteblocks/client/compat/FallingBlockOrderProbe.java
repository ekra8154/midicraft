package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: when does a build place sand before the thing that holds it up?
 *
 * <p>A few blocks of sand land on the ground on most pastes. Sand is the snare instrument, so every
 * snare in a song is a block that falls unless something is under it, and the layout already knows
 * that -- it refuses shapes it cannot prop. So the suspicion is not the plan but the order the plan
 * is sent in: a prop that arrives after the sand it holds arrives too late.</p>
 *
 * <p>Too late is a real quantity, and it is not zero. A falling block does not fall when it is
 * placed; placing it schedules a check two ticks out, and only that check turns it into an entity.
 * So a prop sent inside two ticks of its sand is in time, and at 32 commands a tick that is any of
 * the next 64 commands. Which is exactly why this is rare rather than constant, and exactly why it
 * is worth measuring in commands rather than in yes and no: the same build at a slower rate has a
 * smaller window, and the rate now goes down to one command every four ticks.</p>
 *
 * <p>Everything here asks the game rather than a table. Whether a block falls is
 * {@code instanceof FallingBlock}, and whether the cell under it counts as empty is
 * {@link FallingBlock#isFree}, which is the check the falling block itself makes.</p>
 */
@Tag("sweep")
class FallingBlockOrderProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	/** Commands a tick at the default rate, which sets how many commands two ticks is worth. */
	private static final int DEFAULT_RATE = 32;

	/** Ticks a falling block waits after being placed before it checks whether it can fall. */
	private static final int DELAY_AFTER_PLACE = 2;

	private static Map<String, Block> blocksById() {
		Map<String, Block> byId = new HashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			byId.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block);
		}
		return byId;
	}

	/**
	 * What one build's command order does to the falling blocks in it.
	 *
	 * @param endsUnheld sand the finished build leaves over air. Not an ordering question at all:
	 *     the prop is missing from the plan, or something later wrote air over it, and no send rate
	 *     saves it.
	 * @param gaps how many commands each late prop was late by, counted
	 */
	private record Verdict(int falling, int propped, int late, int never, int endsUnheld,
			int unheldOutsideWalls, int worstGap, TreeMap<Integer, Integer> gaps, String firstLate,
			String firstUnheld) {
	}

	private static Verdict examine(List<String> commands, Map<String, Block> byId,
			int nearWall, int farWall) {
		List<BlockPos> at = new ArrayList<>(commands.size());
		List<Block> blocks = new ArrayList<>(commands.size());
		Map<BlockPos, List<Integer>> writes = new HashMap<>();
		for (String command : commands) {
			CommandPasteSender.Placement placement = CommandPasteSender.read(command);
			at.add(placement.at());
			blocks.add(placement.at() == null ? null : byId.get(placement.block()));
			if (placement.at() != null) {
				writes.computeIfAbsent(placement.at(), cell -> new ArrayList<>()).add(at.size() - 1);
			}
		}

		int falling = 0;
		int propped = 0;
		int late = 0;
		int never = 0;
		int endsUnheld = 0;
		int unheldOutsideWalls = 0;
		int worstGap = 0;
		TreeMap<Integer, Integer> gaps = new TreeMap<>();
		String firstLate = null;
		String firstUnheld = null;
		for (int i = 0; i < blocks.size(); i++) {
			if (!(blocks.get(i) instanceof FallingBlock)) {
				continue;
			}
			falling++;
			BlockPos below = at.get(i).below();
			List<Integer> under = writes.getOrDefault(below, List.of());
			// What the build leaves under it when everything has been sent. A cell nothing ever
			// wrote to, or one whose last word is air, is sand standing on nothing at the end --
			// which is not an ordering fault and no send rate fixes it.
			Block finally_ = under.isEmpty() ? null : blocks.get(under.get(under.size() - 1));
			if (finally_ == null || FallingBlock.isFree(finally_.defaultBlockState())) {
				endsUnheld++;
				// The floor of slabs is laid between the walls. A note that overshoots one stands
				// past the end of it, so the suspicion is that unheld and outside are the same set.
				if (at.get(i).getX() < nearWall || at.get(i).getX() > farWall) {
					unheldOutsideWalls++;
				}
				if (firstUnheld == null) {
					firstUnheld = at.get(i).getX() + " " + at.get(i).getY() + " " + at.get(i).getZ()
						+ " (below is " + (finally_ == null ? "never written"
							: BuiltInRegistries.BLOCK.getKey(finally_).toString()) + ")";
				}
			}
			// What is under the sand at the moment the sand lands: the last write to that cell
			// before this one. Nothing written there at all counts as unheld, because a paste
			// overwrites a place rather than being fitted into it.
			Block standing = null;
			for (int index : under) {
				if (index < i) {
					standing = blocks.get(index);
				}
			}
			if (standing != null && !FallingBlock.isFree(standing.defaultBlockState())) {
				propped++;
				continue;
			}
			int arrives = -1;
			for (int index : under) {
				Block later = blocks.get(index);
				if (index > i && later != null && !FallingBlock.isFree(later.defaultBlockState())) {
					arrives = index;
					break;
				}
			}
			if (arrives < 0) {
				never++;
				if (firstLate == null) {
					firstLate = "never " + at.get(i).getX() + " " + at.get(i).getY() + " "
						+ at.get(i).getZ();
				}
				continue;
			}
			late++;
			int gap = arrives - i;
			gaps.merge(gap, 1, Integer::sum);
			if (gap > worstGap) {
				worstGap = gap;
			}
			if (firstLate == null) {
				firstLate = gap + " commands late at " + at.get(i).getX() + " " + at.get(i).getY()
					+ " " + at.get(i).getZ();
			}
		}
		return new Verdict(falling, propped, late, never, endsUnheld, unheldOutsideWalls, worstGap,
			gaps, firstLate, firstUnheld);
	}

	@Test
	void countsEverySandSentBeforeItsProp() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Map<String, Block> byId = blocksById();
		Gson gson = new Gson();
		long falling = 0;
		long propped = 0;
		long late = 0;
		long never = 0;
		long endsUnheld = 0;
		long unheldOutsideWalls = 0;
		int worstGap = 0;
		TreeMap<Integer, Integer> gaps = new TreeMap<>();
		int buildsAffected = 0;
		int builds = 0;
		int refused = 0;
		// How many of the late ones would actually fall, by how much slack the send rate leaves.
		TreeMap<String, Long> fallsAtRate = new TreeMap<>();
		TreeMap<String, Integer> bySong = new TreeMap<>();
		List<String> examples = new ArrayList<>();
		for (Path file : files) {
			String name = file.getFileName().toString().replace(".json", "");
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
			for (int floors : new int[] {1, 3}) {
				for (int width : new int[] {16, 32, 48}) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refusedHere) {
						refused++;
						continue;
					}
					builds++;
					Verdict verdict = examine(plan.commands(), byId, plan.nearWall(),
						plan.farWall());
					falling += verdict.falling();
					unheldOutsideWalls += verdict.unheldOutsideWalls();
					propped += verdict.propped();
					late += verdict.late();
					never += verdict.never();
					endsUnheld += verdict.endsUnheld();
					worstGap = Math.max(worstGap, verdict.worstGap());
					verdict.gaps().forEach((gap, count) -> gaps.merge(gap, count, Integer::sum));
					if (verdict.endsUnheld() > 0) {
						buildsAffected++;
						bySong.merge(name, verdict.endsUnheld(), Integer::sum);
						if (examples.size() < 12) {
							examples.add(name + " f" + floors + " w" + width + " : "
								+ verdict.endsUnheld() + " end unheld, first "
								+ verdict.firstUnheld());
						}
					}
				}
			}
		}
		System.out.println("FALLING BLOCKS over " + builds + " builds (" + refused + " refused)");
		System.out.println("  placed        " + falling);
		System.out.println("  propped first " + propped);
		System.out.println("  propped late  " + late);
		System.out.println("  never propped " + never);
		System.out.println("  ends over air " + endsUnheld + "   <- falls whatever the rate");
		System.out.println("  of those, outside the lane walls: " + unheldOutsideWalls);
		System.out.println("  worst gap     " + worstGap + " commands");
		System.out.println("  builds leaving sand over air: " + buildsAffected + "/" + builds);
		System.out.println("  how late a prop was, in commands:");
		gaps.forEach((gap, count) -> System.out.println("    gap " + gap + " : " + count));
		System.out.println("  what each rate survives (2 ticks of slack):");
		for (double rate : com.fastnoteblocks.client.PasteRate.RATES) {
			long slack = (long) Math.floor(rate * DELAY_AFTER_PLACE);
			long falls = gaps.entrySet().stream()
				.filter(entry -> entry.getKey() > Math.max(slack, 0))
				.mapToLong(Map.Entry::getValue).sum();
			System.out.println("    " + com.fastnoteblocks.client.PasteRate.label(rate)
				+ " -> " + slack + " commands of slack, " + (falls + endsUnheld) + " would fall");
		}
		fallsAtRate.forEach((rate, count) -> System.out.println("    " + rate + " " + count));
		System.out.println("  worst songs:");
		bySong.entrySet().stream()
			.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
			.limit(10)
			.forEach(entry -> System.out.println("    " + entry.getKey() + " " + entry.getValue()));
		examples.forEach(line -> System.out.println("  " + line));
	}

	/** The same question of every layout, on one song, in case this is not only the ultra lane. */
	@Test
	void asksEveryLayoutTheSameQuestion() throws Exception {
		Path file = BreachView.songFile("big-shot");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		Map<String, Block> byId = blocksById();
		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, mode, new SongBuilder.BuildLimits(4, 32, 3));
				Verdict verdict = examine(plan.commands(), byId, plan.nearWall(), plan.farWall());
				System.out.println("MODE " + mode + " falling=" + verdict.falling()
					+ " propped=" + verdict.propped() + " late=" + verdict.late()
					+ " never=" + verdict.never() + " worstGap=" + verdict.worstGap()
					+ (verdict.firstLate() == null ? "" : " first=" + verdict.firstLate()));
			} catch (RuntimeException refused) {
				System.out.println("MODE " + mode + " refused: " + refused.getMessage());
			}
		}
	}

	/**
	 * What the game itself says about falling, rather than what anybody remembers about it.
	 *
	 * <p>Two answers decide whether a one-command gap is harmless or is the whole fault: how long a
	 * falling block waits before it checks, and which of the blocks this builder puts under sand
	 * count as nothing at all.</p>
	 */
	@Test
	void asksTheGameWhatCountsAsNothingUnderSand() throws Exception {
		java.lang.reflect.Method delay =
			FallingBlock.class.getDeclaredMethod("getDelayAfterPlace");
		delay.setAccessible(true);
		System.out.println("DELAY sand waits "
			+ delay.invoke(net.minecraft.world.level.block.Blocks.SAND) + " ticks after placing");
		System.out.println("At " + DEFAULT_RATE + " commands a tick that is "
			+ DEFAULT_RATE * DELAY_AFTER_PLACE + " commands of slack.");
		for (String id : List.of("minecraft:air", "minecraft:stone", "minecraft:redstone_wire",
				"minecraft:repeater", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
				"minecraft:sand", "minecraft:note_block", "minecraft:glass", "minecraft:water",
				"minecraft:smooth_stone_slab", "minecraft:oak_wall_sign", "minecraft:lever",
				"minecraft:sea_lantern", "minecraft:deepslate_tiles", "minecraft:rail")) {
			Block block = blocksById().get(id);
			if (block == null) {
				System.out.println("FREE " + id + " : no such block");
				continue;
			}
			System.out.println("FREE " + id + " : "
				+ FallingBlock.isFree(block.defaultBlockState())
				+ (FallingBlock.isFree(block.defaultBlockState())
					? "  <- sand sitting on this falls" : ""));
		}
		// A top slab is the whole floor level under an ultra lane, so it is asked for by state
		// rather than by default.
		Block slab = blocksById().get("minecraft:smooth_stone_slab");
		if (slab != null) {
			slab.getStateDefinition().getPossibleStates().forEach(state ->
				System.out.println("FREE slab " + state + " : " + FallingBlock.isFree(state)));
		}
	}

	/**
	 * The unpropped sand drawn rather than listed, on the build that has the most of it.
	 *
	 * <p>Counting says how many and a slice says what shape they are, which is the only thing that
	 * decides what the fix is. Every one of these is a cell the build never wrote to at all -- not
	 * one it wrote air into -- so the question the picture has to answer is what is supposed to be
	 * there and why the walk did not put it there.</p>
	 */
	@Test
	void drawsTheSandThatIsHeldUpByNothing() throws Exception {
		Path file = BreachView.songFile("aria-math-c418");
		ComposerProject song;
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			song = new ComposerProject(raw.name(), raw.ppq(), raw.tempoMicrosPerQuarter(),
				raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(), raw.endTick(),
				raw.speedQuarters());
		}
		List<SongBuilder.EventNote> notes =
			SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 1));
		Map<String, Block> byId = blocksById();

		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
			new java.util.LinkedHashMap<>();
		Map<BlockPos, Block> placed = new java.util.LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ", 6);
			BlockPos cell = new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3]));
			world.put(cell, BreachView.parse(word[4]));
			placed.put(cell, byId.get(CommandPasteSender.read(command).block()));
		}

		List<BlockPos> unheld = new ArrayList<>();
		for (Map.Entry<BlockPos, Block> cell : placed.entrySet()) {
			if (!(cell.getValue() instanceof FallingBlock)) {
				continue;
			}
			Block under = placed.get(cell.getKey().below());
			if (under == null || FallingBlock.isFree(under.defaultBlockState())) {
				unheld.add(cell.getKey());
			}
		}
		System.out.println("UNHELD " + unheld.size() + " of "
			+ placed.values().stream().filter(b -> b instanceof FallingBlock).count()
			+ " falling blocks, nearWall=" + plan.nearWall() + " farWall=" + plan.farWall());
		for (BlockPos cell : unheld) {
			System.out.println("  tp " + cell.getX() + " " + cell.getY() + " " + cell.getZ()
				+ "   below=" + (placed.get(cell.below()) == null ? "never written"
					: BuiltInRegistries.BLOCK.getKey(placed.get(cell.below())).toString())
				+ "   above=" + (placed.get(cell.above()) == null ? "nothing"
					: BuiltInRegistries.BLOCK.getKey(placed.get(cell.above())).toString()));
		}
		for (BlockPos cell : unheld.subList(0, Math.min(3, unheld.size()))) {
			System.out.println();
			System.out.println("#### sand on nothing at " + cell.getX() + " " + cell.getY() + " "
				+ cell.getZ());
			System.out.println(AsciiDiagram.render(
				at -> world.getOrDefault(at, net.minecraft.world.level.block.Blocks.AIR
					.defaultBlockState()),
				new BlockPos(cell.getX() - 4, cell.getY() - 2, cell.getZ() - 2),
				new BlockPos(cell.getX() + 4, cell.getY() + 2, cell.getZ() + 2),
				AsciiDiagram.View.NORTH, AsciiDiagram.Shape.CODE));
		}
	}
}
