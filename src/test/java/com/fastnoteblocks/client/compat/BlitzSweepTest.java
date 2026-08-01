package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scratch probe: faults and size together, over the whole library.
 *
 * <p>Size is tracked beside the faults because compactness is the product. A change that buys a
 * fault with volume is a regression however green it looks.</p>
 */
class BlitzSweepTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void sweeps() throws Exception {
		List<Path> files;
		try (Stream<Path> listing = Files.list(SONGS)) {
			files = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
		Gson gson = new Gson();
		long volume = 0;
		long length = 0;
		long blocks = 0;
		long padWasted = 0;
		long corners = 0;
		int breaches = 0;
		int shortLanes = 0;
		int wrongNotes = 0;
		int dropped = 0;
		int refused = 0;
		int deadBuilds = 0;
		int realDead = 0;
		int realWrong = 0;
		int realBreach = 0;
		int realBreachBlocks = 0;
		int realWorstBreach = 0;
		int realDropped = 0;
		int recessLanes = 0;
		long recessColumns = 0;
		int worstRecess = 0;
		int realRecessLanes = 0;
		long realRecessColumns = 0;
		long allTurns = 0;
		int buildsRecessed = 0, buildsClean = 0, wrongInRecessed = 0, wrongInClean = 0;
		int buildsRecessedWithWrong = 0, buildsCleanWithWrong = 0;
		TreeMap<Integer, Integer> recessSize = new TreeMap<>();
		TreeMap<String, Integer> worstBySong = new TreeMap<>();
		TreeMap<Integer, Integer> droppedByChord = new TreeMap<>();
		TreeMap<String, Integer> wrongKind = new TreeMap<>();
		TreeMap<String, Integer> wrongDir = new TreeMap<>();
		TreeMap<String, Integer> wrongKindReal = new TreeMap<>();
		TreeMap<String, Integer> droppedBySong = new TreeMap<>();
		TreeMap<Integer, Integer> droppedByWidth = new TreeMap<>();
		TreeMap<String, Long> padBy = new TreeMap<>();
		TreeMap<String, Integer> wrongBySong = new TreeMap<>();
		TreeMap<String, Integer> breachBySong = new TreeMap<>();
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
			boolean stress = name.startsWith("ultra-");
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					SongBuilder.PastePlan plan;
					try {
						plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
							SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
					} catch (RuntimeException refusedHere) {
						refused++;
						continue;
					}
					volume += (long) plan.width() * plan.height() * plan.depth();
					length += plan.width();
					blocks += plan.commands().size();
					allTurns += plan.turns().size();
					for (int r : plan.recesses()) {
						recessSize.merge(r >= 16 ? 16 : r >= 8 ? 8 : r >= 4 ? 4 : r, 1, Integer::sum);
					}
					worstBySong.merge(name, plan.worstRecess(), Math::max);
					recessLanes += plan.recesses().size();
					recessColumns += plan.recessedColumns();
					worstRecess = Math.max(worstRecess, plan.worstRecess());
					if (!stress) {
						realRecessLanes += plan.recesses().size();
						realRecessColumns += plan.recessedColumns();
					}
					System.out.println("SIZE " + name + " f" + floors + " w" + width
						+ " : " + plan.width() + " " + plan.depth() + " " + plan.height()
						+ " " + plan.commands().size());
					for (Map.Entry<String, Integer> entry : plan.padding().entrySet()) {
						padBy.merge(entry.getKey(), (long) entry.getValue(), Long::sum);
						if (entry.getKey().equals("corner")) {
							corners += entry.getValue();
						} else if (!entry.getKey().startsWith("swap")) {
							padWasted += entry.getValue();
						}
					}
					int breachHere = 0;
					int wrongHere = 0;
					for (String fault : plan.faults()) {
						if (fault.startsWith("a lane turned -")) {
							breachHere++;
						} else if (fault.startsWith("a lane turned ")) {
							shortLanes++;
						} else if (fault.startsWith("the note")) {
							wrongHere++;
							String kind = fault.contains("sound early") ? "early"
								: fault.contains("sound again") ? "late" : "never";
							wrongKind.merge(kind, 1, Integer::sum);
							if (!stress) { wrongKindReal.merge(kind, 1, Integer::sum); }
							if (fault.contains(" from the ")) {
								String d = fault.substring(fault.indexOf(" from the ") + 10);
								wrongDir.merge(d.substring(0, d.indexOf(" ")), 1, Integer::sum);
							}
						} else if (fault.contains("had nowhere to hang")) {
							String[] words = fault.split(" ");
							int lost = Integer.parseInt(words[0]);
							dropped += lost;
							// "N notes of a chord of M at tick T ..."
							droppedByChord.merge(Integer.parseInt(words[6]), lost, Integer::sum);
							droppedBySong.merge(name + (stress ? " [stress]" : ""), lost,
								Integer::sum);
							droppedByWidth.merge(width, lost, Integer::sum);
							if (!stress) {
								realDropped += lost;
							}
						}
					}
					boolean hasRecess = !plan.recesses().isEmpty();

					if (hasRecess) { buildsRecessed++; wrongInRecessed += wrongHere; if (wrongHere > 0) { buildsRecessedWithWrong++; } }

					else { buildsClean++; wrongInClean += wrongHere; if (wrongHere > 0) { buildsCleanWithWrong++; } }

					breaches += breachHere;
					wrongNotes += wrongHere;
					if (!stress && breachHere > 0) {
						realBreach += breachHere;

						for (int b : plan.breaches()) { realBreachBlocks += b; realWorstBreach = Math.max(realWorstBreach, b); }
						breachBySong.merge(name, breachHere, Integer::sum);
					}
					if (!stress && wrongHere > 0) {
						realWrong += wrongHere;
						wrongBySong.merge(name, wrongHere, Integer::sum);
					}
					int dust = 0;
					boolean dead = false;
					for (String command : plan.commands()) {
						String block = command.split(" ")[4];
						if (block.startsWith("minecraft:redstone_wire")) {
							dust++;
							continue;
						}
						if (!block.startsWith("minecraft:repeater")) {
							continue;
						}
						if (dust > 15) {
							dead = true;
						}
						dust = 0;
					}
					if (dead) {
						deadBuilds++;
						if (!stress) {
							realDead++;
						}
					}
				}
			}
		}
		System.out.println("BLITZ length=" + length + " volume=" + volume + " blocks=" + blocks
			+ " padWasted=" + padWasted + " corners=" + corners);
		System.out.println("BLITZ breaches=" + breaches + " short=" + shortLanes
			+ " wrong=" + wrongNotes + " dropped=" + dropped + " dead=" + deadBuilds
			+ " refused=" + refused);
		System.out.println("BLITZ recess lanes=" + recessLanes + " columns=" + recessColumns
				+ " worst=" + worstRecess + " realLanes=" + realRecessLanes
				+ " realColumns=" + realRecessColumns + " ofTurns=" + allTurns);
		System.out.println("BLITZ recessSize(bucket>=) " + recessSize);
		System.out.println("BLITZ worstRecessBySong " + worstBySong);
		System.out.println("BLITZ recessedBuilds=" + buildsRecessed + " ofWhichWrong=" + buildsRecessedWithWrong + " wrongNotes=" + wrongInRecessed);
		System.out.println("BLITZ cleanBuilds=" + buildsClean + " ofWhichWrong=" + buildsCleanWithWrong + " wrongNotes=" + wrongInClean);
		System.out.println("BLITZ real: breaches=" + realBreach + " wrong=" + realWrong
			+ " dead=" + realDead);
		System.out.println("BLITZ realBreachBlocks=" + realBreachBlocks + " worst=" + realWorstBreach);
		System.out.println("BLITZ padBy " + padBy);
		System.out.println("BLITZ wrongBySong " + wrongBySong);
		System.out.println("BLITZ wrongKind " + wrongKind + " real " + wrongKindReal);
		System.out.println("BLITZ wrongFrom " + wrongDir);
		System.out.println("BLITZ realDropped=" + realDropped);
		System.out.println("BLITZ droppedByChordSize " + droppedByChord);
		System.out.println("BLITZ droppedByWidth " + droppedByWidth);
		System.out.println("BLITZ droppedBySong " + droppedBySong);
		System.out.println("BLITZ breachBySong " + breachBySong);
	}
}
