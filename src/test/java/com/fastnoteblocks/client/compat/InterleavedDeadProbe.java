package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where an interleaved build's signal actually stops, cell by cell rather than note by note.
 *
 * <p>{@link FaultView#firstBreak} answers with the first note block that never sounded, which for a
 * machine that never started at all is its first note -- true, and no help. This walks the laid
 * cells in walk order and prints the boundary between reached and unreached with the blocks either
 * side of it, so the shape that failed to hand the signal on is the thing on the page.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*InterleavedDeadProbe" -Dprobe.song=neverending-night-2-lanes
 * </pre>
 */
@Tag("sweep")
class InterleavedDeadProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static String text(String key, String fallback) {
		String given = System.getProperty("probe." + key);
		return given == null || given.isBlank() ? fallback : given.strip();
	}

	/**
	 * One cell, and which of the two things that can be wrong with it is.
	 *
	 * <p>Two sets, because they are two questions. {@code reachedAt} is what the signal
	 * <em>energised</em> -- wire it ran along, blocks it strongly powered, repeaters it arrived at.
	 * {@code unreachedAt} is note blocks that never <em>sounded</em>. A note hung on a bus is
	 * sounded without being energised: nothing is ever queued to it, the block beside it is powered
	 * and it plays. So a note block judged by {@code reachedAt} reads as dead whenever it is doing
	 * exactly what it was built to do, which is most of the library. Judged the wrong way round
	 * here, one flank of every stacked chord in the build looked silent and none of them were.</p>
	 */
	private static String say(FaultView.Build built, BlockPos at) {
		BlockState state = built.at(at);
		String name = state.isAir() ? "air"
			: net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		String shape = built.plan().laidBy().getOrDefault(at, "?");
		String how = state.is(Blocks.NOTE_BLOCK)
			? (built.reading().unreachedAt().contains(at) ? "SILENT" : "sounds")
			: (built.reading().reachedAt().contains(at) ? "LIVE  " : "dead  ");
		return String.format(Locale.ROOT, "%s %d %d %d  %-18s %s", how, at.getX(), at.getY(),
			at.getZ(), name, shape);
	}

	@Test
	void whereTheSignalStops() throws Exception {
		String song = text("song", "neverending-night-2-lanes");
		int width = Integer.parseInt(text("width", "24"));
		int floors = Integer.parseInt(text("floors", "3"));
		int around = Integer.parseInt(text("around", "14"));
		Flags.Held held = Flags.set(text("set", ""));
		try {
			System.out.println("   oak_button is in BlockTags.BUTTONS: "
				+ Blocks.OAK_BUTTON.defaultBlockState().is(net.minecraft.tags.BlockTags.BUTTONS)
				+ "   lever is a Blocks constant: " + Blocks.LEVER.defaultBlockState().is(Blocks.LEVER));
			SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
			FaultView.Build built = FaultView.of(song, mode, width, floors, 4, false);
			System.out.println();
			System.out.println("==== " + built.where() + held.said() + " ====");
			System.out.println("   note blocks=" + built.reading().noteBlocks() + "  never fired="
				+ built.reading().unreachedNotes() + "  versions=" + built.reading().versions()
				+ "  machineANotes="
				+ built.plan().padding().getOrDefault(SongBuilder.MACHINE_A_NOTES, -1)
				+ "  machineBNotes="
				+ built.plan().padding().getOrDefault(SongBuilder.MACHINE_B_NOTES, -1));
			for (String warning : built.reading().warnings()) {
				System.out.println("   warning: " + warning);
			}
			// Where the signal actually stopped: a dead cell with a live one beside it. Walk order
			// cannot answer this -- the plan is sorted along the build before it is handed over, so
			// "the first cell laid that was never reached" is the first in *paste* order, which for a
			// machine that snakes through three floors is somewhere in the middle of it. The frontier
			// is a fact about the blocks and needs no order at all.
			Set<BlockPos> powered = built.plan().poweredAt();
			Set<BlockPos> live = built.reading().reachedAt();
			List<BlockPos> frontier = new ArrayList<>();
			for (BlockPos at : powered) {
				if (live.contains(at)) {
					continue;
				}
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					if (live.contains(at.relative(way))) {
						frontier.add(at);
						break;
					}
				}
			}
			frontier.sort(Comparator.comparingInt((BlockPos at) -> at.getZ()).thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX));
			long dead = powered.stream().filter(at -> !live.contains(at)).count();
			System.out.println("   " + powered.size() + " powered cells, " + dead + " never reached, "
				+ frontier.size() + " of them with a live cell beside them");
			// The shape that stopped and the shape that was live next to it, which is the pair a fix
			// has to be about. Grouped, because one rule failing forty times is one thing to fix.
			Map<String, int[]> pairs = new TreeMap<>();
			Map<String, BlockPos> firstOf = new TreeMap<>();
			for (BlockPos at : frontier) {
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					BlockPos beside = at.relative(way);
					if (!live.contains(beside) || !powered.contains(beside)) {
						continue;
					}
					String pair = FaultView.family(built.plan().laidBy().getOrDefault(beside, "?"))
						+ "  =>  " + FaultView.family(built.plan().laidBy().getOrDefault(at, "?"));
					pairs.computeIfAbsent(pair, key -> new int[1])[0]++;
					firstOf.putIfAbsent(pair, at);
				}
			}
			System.out.println("   live shape => dead shape, at the frontier:");
			pairs.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
				.limit(20)
				.forEach(entry -> System.out.println(String.format(Locale.ROOT, "      %4d  %-72s at %s",
					entry.getValue()[0], entry.getKey(), firstOf.get(entry.getKey()).toShortString())));
			// Which rows are dead, as runs of z. A serial machine that breaks once has one run
			// reaching the end; several separate runs mean several breaks, and that is the whole
			// difference between one fix and a family of them.
			java.util.TreeMap<Integer, int[]> byRow = new java.util.TreeMap<>();
			for (BlockPos at : powered) {
				int[] tally = byRow.computeIfAbsent(at.getZ(), key -> new int[2]);
				tally[live.contains(at) ? 0 : 1]++;
			}
			StringBuilder runs = new StringBuilder();
			Integer runStart = null;
			int previous = Integer.MIN_VALUE;
			for (Map.Entry<Integer, int[]> row : byRow.entrySet()) {
				boolean anyDead = row.getValue()[1] > 0;
				if (anyDead && runStart == null) {
					runStart = row.getKey();
				} else if (!anyDead && runStart != null) {
					runs.append(" z").append(runStart).append("..").append(previous);
					runStart = null;
				}
				previous = row.getKey();
			}
			if (runStart != null) {
				runs.append(" z").append(runStart).append("..").append(previous);
			}
			System.out.println("   rows holding a dead cell (build spans z" + byRow.firstKey() + ".."
				+ byRow.lastKey() + "):" + (runs.isEmpty() ? " none" : runs.toString()));
			// One cell and everything touching it: -Dprobe.at=x,y,z. When the picture says a cell
			// ought to be lit and the reader says it is not, the disagreement is about one of the six
			// neighbours, and guessing which has been wrong three times.
			String asked = text("at", "");
			if (!asked.isEmpty()) {
				String[] part = asked.split("[ ,]+");
				BlockPos at = new BlockPos(Integer.parseInt(part[0]), Integer.parseInt(part[1]),
					Integer.parseInt(part[2]));
				System.out.println("   asked about " + say(built, at) + "  (powered="
					+ powered.contains(at) + ")");
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					BlockPos beside = at.relative(way);
					System.out.println("      " + String.format(Locale.ROOT, "%-6s", way.getName())
						+ say(built, beside) + "  (powered=" + powered.contains(beside) + ")");
				}
			}
			// The signal path on its own. Every other dead cell is a consequence -- a note block the
			// wire never reached, the instrument block under it -- and two of those side by side look
			// like a break only because they are next to each other in space. Wire and repeaters are
			// what actually carries anything, so the first dead one along the corridor is the break.
			// Over the whole world, not over poweredAt: that set is narrower than the wire is --
			// a plain run of dust between two chords is not in it -- so a scan of it finds three
			// cells in a corridor with hundreds dark and calls the corridor healthy.
			List<BlockPos> deadPath = new ArrayList<>();
			List<BlockPos> livePath = new ArrayList<>();
			for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
				if (!cell.getValue().is(Blocks.REDSTONE_WIRE)
						&& !cell.getValue().is(Blocks.REPEATER)) {
					continue;
				}
				(live.contains(cell.getKey()) ? livePath : deadPath).add(cell.getKey());
			}
			deadPath.sort(Comparator.comparingInt((BlockPos at) -> at.getZ())
				.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX));
			// Every parity seam, and whether the signal got through it. A seam is a piston pair
			// that moves a lane to the other half of the game tick, and it is the one place in this
			// layout where the signal leaves the wire entirely -- so a seam the walk reached and
			// did not come out of is invisible to every check that follows the dust.
			List<BlockPos> pistons = new ArrayList<>();
			for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
				if (cell.getValue().is(Blocks.STICKY_PISTON) || cell.getValue().is(Blocks.PISTON)) {
					pistons.add(cell.getKey());
				}
			}
			pistons.sort(Comparator.comparingInt((BlockPos at) -> at.getZ())
				.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX));
			System.out.println("   " + pistons.size() + " pistons:");
			for (BlockPos at : pistons) {
				System.out.println("     " + say(built, at));
			}
			// The fault in the order the music happens, which is the only order that means
			// anything here. Paste order is sorted along the build before the plan is handed over,
			// and geometry cannot tell a lane row from the one three columns along it -- both
			// machines snake through the same corridor. The tick a note was built for is the
			// walk's own answer, so the earliest tick that never sounds is where the signal
			// actually stopped, and the latest that does is the last thing it reached.
			// Against unreachedAt, not against reachedAt. Those are different sets and the
			// difference is the whole of this shape: reachedAt is what the signal energised, and a
			// note block hung on a powered block is sounded without ever being energised itself --
			// nothing is queued to it, it is simply played. Asked the wrong one, every note in the
			// library that hangs off a bus reads as dead, which is most of them.
			Set<BlockPos> silent = Set.copyOf(built.reading().unreachedAt());
			Integer firstSilentTick = null;
			BlockPos firstSilent = null;
			Integer lastHeardTick = null;
			BlockPos lastHeard = null;
			for (Map.Entry<BlockPos, Integer> note : built.plan().noteTicks().entrySet()) {
				if (!silent.contains(note.getKey())) {
					if (lastHeardTick == null || note.getValue() > lastHeardTick) {
						lastHeardTick = note.getValue();
						lastHeard = note.getKey();
					}
				} else if (firstSilentTick == null || note.getValue() < firstSilentTick) {
					firstSilentTick = note.getValue();
					firstSilent = note.getKey();
				}
			}
			if (firstSilent != null) {
				System.out.println("   first tick that never sounds: t" + firstSilentTick + " at "
					+ firstSilent.toShortString() + "  " + say(built, firstSilent));
				System.out.println("   last tick that does:          t" + lastHeardTick + " at "
					+ lastHeard.toShortString() + "  " + say(built, lastHeard));
				// Everything the walk meant to sound around that moment, live or not: a machine
				// that stops has one last chord, and a machine that skips a shape has a hole with
				// music either side of it. The two look nothing alike and the counts cannot say
				// which this is.
				int from = firstSilentTick - 8;
				int to = firstSilentTick + 8;
				List<Map.Entry<BlockPos, Integer>> near = new ArrayList<>();
				built.plan().noteTicks().forEach((at, tick) -> {
					if (tick >= from && tick <= to) {
						near.add(Map.entry(at, tick));
					}
				});
				near.sort(Comparator.comparingInt(Map.Entry::getValue));
				System.out.println("   the notes either side of it:");
				for (Map.Entry<BlockPos, Integer> note : near) {
					System.out.println("     t" + String.format(Locale.ROOT, "%-6d",
						note.getValue()) + say(built, note.getKey()));
				}
			}
			// A box of the machine, wire and repeaters only, each marked live or dead:
			// -Dprobe.box=x1,y1,z1,x2,y2,z2. The renderer draws the blocks and cannot say which of
			// them the signal reached, and a staircase is exactly where that is the whole question
			// -- the steps of two machines stand in one column and look like one broken stair.
			String box = text("box", "");
			if (!box.isEmpty()) {
				String[] ends = box.split("[ ,]+");
				BlockPos low = new BlockPos(Integer.parseInt(ends[0]), Integer.parseInt(ends[1]),
					Integer.parseInt(ends[2]));
				BlockPos high = new BlockPos(Integer.parseInt(ends[3]), Integer.parseInt(ends[4]),
					Integer.parseInt(ends[5]));
				List<BlockPos> inBox = new ArrayList<>();
				for (BlockPos at : livePath) {
					inBox.add(at);
				}
				inBox.addAll(deadPath);
				inBox.removeIf(at -> at.getX() < low.getX() || at.getX() > high.getX()
					|| at.getY() < low.getY() || at.getY() > high.getY()
					|| at.getZ() < low.getZ() || at.getZ() > high.getZ());
				inBox.sort(Comparator.comparingInt((BlockPos at) -> at.getZ())
					.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX));
				System.out.println("   " + inBox.size() + " cells of wire or repeater in that box:");
				for (BlockPos at : inBox) {
					System.out.println("     " + say(built, at) + "   on "
						+ net.minecraft.core.registries.BuiltInRegistries.BLOCK
							.getKey(built.at(at.below()).getBlock()).getPath());
				}
			}
			// The break itself: a dead cell of the path with a live cell of the path beside it.
			// Restricted to wire and repeaters, because those are the only things that hand the
			// signal on -- two instrument blocks side by side look like a break and are only two
			// chords standing next to each other.
			Set<BlockPos> livePathSet = Set.copyOf(livePath);
			List<BlockPos> breaks = new ArrayList<>();
			// Two steps as well as one. Nothing adjacent means the handoff that failed went
			// *through* something -- a bus cell, a note block, a staircase step -- which is the
			// wire-block-wire shape every dead line in this repo has turned out to be. The block
			// in the middle is the thing to look at, so it is printed with them.
			Map<BlockPos, BlockPos> through = new java.util.HashMap<>();
			for (BlockPos at : deadPath) {
				boolean found = false;
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					if (livePathSet.contains(at.relative(way))) {
						found = true;
						break;
					}
				}
				if (!found) {
					for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
						if (livePathSet.contains(at.relative(way, 2))
								&& !built.at(at.relative(way)).isAir()) {
							through.put(at, at.relative(way));
							found = true;
							break;
						}
					}
				}
				if (found) {
					breaks.add(at);
				}
			}
			// And where nothing is within two, the closest pair there is. A handoff can cross more
			// than a block -- a staircase step goes up and along at once, a note block relays
			// sideways -- so "no break found" means the gap is bigger than the search, never that
			// the machine is whole.
			if (breaks.isEmpty() && !deadPath.isEmpty() && !livePath.isEmpty()) {
				BlockPos nearestDead = null;
				BlockPos nearestLive = null;
				int best = Integer.MAX_VALUE;
				for (BlockPos gone : deadPath) {
					for (BlockPos alive : livePath) {
						int gap = Math.abs(gone.getX() - alive.getX())
							+ Math.abs(gone.getY() - alive.getY())
							+ Math.abs(gone.getZ() - alive.getZ());
						if (gap < best) {
							best = gap;
							nearestDead = gone;
							nearestLive = alive;
						}
					}
				}
				System.out.println("   nothing within two; the closest live and dead wire are "
					+ best + " blocks apart:");
				System.out.println("     dead  " + say(built, nearestDead));
				System.out.println("     live  " + say(built, nearestLive));
			}
			breaks.sort(Comparator.comparingInt((BlockPos at) -> at.getZ())
				.thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX));
			System.out.println("   " + breaks.size() + " of the dead have live wire beside them:");
			for (BlockPos at : breaks) {
				System.out.println("     " + say(built, at));
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					BlockPos beside = at.relative(way);
					if (livePathSet.contains(beside)) {
						System.out.println("         live " + String.format(Locale.ROOT, "%-6s",
							way.getName()) + say(built, beside));
					}
				}
				BlockPos middle = through.get(at);
				if (middle != null) {
					System.out.println("         through " + say(built, middle));
					System.out.println("         then     "
						+ say(built, middle.subtract(at.subtract(middle))));
				}
			}
			System.out.println("   " + livePath.size() + " live cells of wire or repeater and "
				+ deadPath.size() + " dead; the first dead few along the corridor:");
			for (int index = 0; index < Math.min(deadPath.size(), around); index++) {
				System.out.println("     " + say(built, deadPath.get(index)));
			}
			System.out.println("   the first few frontier cells, in build order along z:");
			for (int index = 0; index < Math.min(frontier.size(), around); index++) {
				System.out.println("     " + say(built, frontier.get(index)));
				for (net.minecraft.core.Direction way : net.minecraft.core.Direction.values()) {
					BlockPos beside = frontier.get(index).relative(way);
					if (live.contains(beside) && powered.contains(beside)) {
						System.out.println("         fed from " + way.getName() + ": " + say(built, beside));
					}
				}
			}
			// Sources: what the reader thought it could start from, which is the other half of a
			// machine that never began.
			int redstoneBlocks = 0;
			int buttons = 0;
			for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
				if (cell.getValue().is(Blocks.REDSTONE_BLOCK)) {
					redstoneBlocks++;
				} else if (cell.getValue().is(net.minecraft.tags.BlockTags.BUTTONS)
						|| cell.getValue().is(Blocks.LEVER)) {
					buttons++;
				}
			}
			System.out.println("   sources in the build: " + buttons + " levers/buttons, "
				+ redstoneBlocks + " blocks of redstone");
			// Landmarks, so whoever pastes this can check they are in the same frame before they
			// walk to a coordinate. A plan slides after it is walked and a paste lands wherever the
			// player is standing, so a number quoted from here is only worth what the frame is.
			StringBuilder marks = new StringBuilder();
			for (Map.Entry<BlockPos, BlockState> cell : built.world().entrySet()) {
				if (cell.getValue().getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
						|| cell.getValue().is(Blocks.LEVER)) {
					marks.append("  button/lever at ").append(cell.getKey().toShortString());
				}
			}
			System.out.println("   span x " + built.plan().spanX() + " y " + built.plan().height()
				+ " z " + built.plan().spanZ() + "   walls x=" + built.plan().nearWall() + ".."
				+ built.plan().farWall() + marks);
		} finally {
			held.putBack();
		}
	}
}
