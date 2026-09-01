package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The straight half-tick lane with its two lanes trading halves of the game tick, against the
 * fixed parity split it used to run.
 *
 * <p>This layout pays for an idle lane twice -- once in the columns it lays to span a silence,
 * and again in the mirrored wire that keeps it beside its partner -- so it is the layout with the
 * most to gain from both lanes carrying half of whatever is sounding. Length here is the paste's
 * own span along the build, which for two straight lines is the whole of its size.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*HalfTickTradeProbe"
 * </pre>
 */
@Tag("sweep")
class HalfTickTradeProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");

	private record Built(int span, int seams, int mirrored, int unreached, int notes,
			int wrong, int missing, int collisions) { }

	private static Built build(List<SongBuilder.EventNote> notes, boolean readback)
			throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.HALF_TICK_LANE, new SongBuilder.BuildLimits(16, 24, 1));
		Map<String, Integer> pad = plan.padding();
		int unreached = -1;
		int heard = -1;
		if (readback) {
			Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
				new java.util.HashMap<>();
			int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
			int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
			for (String command : plan.commands()) {
				String[] token = command.split(" ", 5);
				BlockPos pos = new BlockPos(Integer.parseInt(token[1]), Integer.parseInt(token[2]),
					Integer.parseInt(token[3]));
				String text = token[4].substring(0, token[4].length() - " replace".length());
				if (text.startsWith("minecraft:oak_button")) {
					text = "minecraft:lever[face=floor,facing=east,powered=true]";
				}
				world.put(pos, net.minecraft.commands.arguments.blocks.BlockStateParser
					.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, text,
						false).blockState());
				for (int axis = 0; axis < 3; axis++) {
					int value = axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
					min[axis] = Math.min(min[axis], value);
					max[axis] = Math.max(max[axis], value);
				}
			}
			NoteMachineReader.Reading reading = NoteMachineReader.read("HalfTickTrade",
				new BlockPos(min[0] - 1, min[1] - 1, min[2] - 1),
				new BlockPos(max[0] + 1, max[1] + 1, max[2] + 1),
				pos -> world.getOrDefault(pos,
					net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
			unreached = reading.unreachedNotes();
			heard = reading.project().layers().stream()
				.mapToInt(layer -> layer.notes().size()).sum();
		}
		return new Built(plan.spanX(), pad.getOrDefault("paritySeams", 0),
			pad.getOrDefault("halfTickMirror", 0), unreached, heard,
			plan.wrongNotes(), plan.missingNotes(), plan.collisions().size());
	}

	@Test
	void tradingHalvesAgainstTheFixedSplit() throws Exception {
		Flags.Held held = Flags.set(System.getProperty("probe.set", ""));
		try {
			run(held);
		} finally {
			held.putBack();
		}
	}

	private void run(Flags.Held held) throws Exception {
		boolean readback = Boolean.getBoolean("probe.readback");
		List<Path> files;
		try (Stream<Path> listed = Files.list(SONGS)) {
			files = listed.filter(file -> file.toString().endsWith(".json")).sorted().toList();
		}
		List<String> rows = new ArrayList<>();
		long fixedTotal = 0;
		long tradedTotal = 0;
		long seamTotal = 0;
		int dual = 0;
		int broken = 0;
		for (Path file : files) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(file)) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.gameTickEventNotes(project, true);
			}
			String song = file.getFileName().toString().replace(".json", "");
			long evens = notes.stream().filter(note -> note.time() % 2 == 0).count();
			if (evens == 0 || evens == notes.size()) {
				// A one-parity song has nothing to trade: the second lane is empty either way.
				continue;
			}
			dual++;
			SongBuilder.HALF_TICK_TRADES_HALVES = false;
			Built fixed = build(notes, readback);
			SongBuilder.HALF_TICK_TRADES_HALVES = true;
			Built traded = build(notes, readback);
			fixedTotal += fixed.span();
			tradedTotal += traded.span();
			seamTotal += traded.seams();
			String note = "";
			if (traded.wrong() > fixed.wrong() || traded.missing() > fixed.missing()
					|| traded.collisions() > fixed.collisions()
					|| readback && (traded.unreached() > fixed.unreached()
						|| traded.notes() < fixed.notes())) {
				broken++;
				note = " BROKE unreached " + fixed.unreached() + "->" + traded.unreached()
					+ " heard " + fixed.notes() + "->" + traded.notes()
					+ " wrong " + fixed.wrong() + "->" + traded.wrong()
					+ " missing " + fixed.missing() + "->" + traded.missing()
					+ " clash " + fixed.collisions() + "->" + traded.collisions();
			}
			rows.add(String.format("%8d %s span %d -> %d (%+.1f%%) seams=%d mirror %d -> %d%s",
				fixed.span() - traded.span(), song, fixed.span(), traded.span(),
				fixed.span() == 0 ? 0.0 : 100.0 * (traded.span() - fixed.span()) / fixed.span(),
				traded.seams(), fixed.mirrored(), traded.mirrored(), note));
		}
		SongBuilder.HALF_TICK_TRADES_HALVES = true;
		rows.sort(java.util.Comparator.reverseOrder());
		rows.forEach(row -> System.out.println("  " + row));
		System.out.println(String.format(
			"HALF TICK TRADE" + held.said()
				+ ": %d two-parity songs, %d broken, seams=%d, span %d -> %d (%.1f%%)",
			dual, broken, seamTotal, fixedTotal, tradedTotal,
			fixedTotal == 0 ? 0.0 : 100.0 * (tradedTotal - fixedTotal) / fixedTotal));
	}
}
