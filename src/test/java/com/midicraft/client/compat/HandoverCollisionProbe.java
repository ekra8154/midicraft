package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one thing standing against {@link SongBuilder#RESERVES_THE_HANDOVER_COLUMN}.
 *
 * <p>{@code NoteMachineReaderTest.readsBackEveryNoteOfItsOwnBuild[3]} does not read back wrong --
 * it never gets that far. The plan is refused outright with a collision at {@code 0 71 32}, oak
 * planks against stone. This builds the same song and limits with the collision marked instead of
 * thrown, so the shape that laid each block can be read off rather than guessed at.</p>
 */
@Tag("sweep")
class HandoverCollisionProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final SongBuilder.BuildLimits LIMITS = new SongBuilder.BuildLimits(4, 24, 3);

	@Test
	void marksTheCollision() {
		List<SongBuilder.EventNote> notes = sampleSong();
		for (boolean on : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = on;
			SongBuilder.DEBUG_PASTE = true;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE, LIMITS);
				System.out.println("COLLIDE on=" + on + " collisions=" + plan.collisions().size()
					+ " breaches=" + plan.breaches().size() + " wrong=" + plan.wrongNotes());
				plan.collisions().forEach((at, what) -> System.out.println("    " + at.getX() + " "
					+ at.getY() + " " + at.getZ() + "  " + what));
			} catch (RuntimeException no) {
				System.out.println("COLLIDE on=" + on + " REFUSED: " + no.getMessage());
			} finally {
				SongBuilder.DEBUG_PASTE = false;
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
	}

	/**
	 * Guardian per configuration, so the arms can be compared over the configs both of them build.
	 *
	 * <p>With the handover column on, twelve configurations that used to refuse now paste -- and a
	 * config that refuses contributes no breaches at all, so a straight total flatters the arm that
	 * builds less. What is worth knowing is what happened to the builds that existed either way.</p>
	 */
	@Test
	void comparesOnlyTheConfigsBothArmsBuild() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		Map<String, int[]> off = new LinkedHashMap<>();
		Map<String, int[]> on = new LinkedHashMap<>();
		for (boolean arm : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = arm;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes,
								SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							int blocks = plan.breaches().stream().mapToInt(Integer::intValue).sum();
							int ones = (int) plan.breaches().stream().filter(b -> b == 1).count();
							(arm ? on : off).put(width + "x" + floors, new int[] {
								plan.breaches().size(), blocks, ones,
								plan.breaches().stream().mapToInt(Integer::intValue).max().orElse(0),
								plan.commands().size()});
						} catch (RuntimeException no) {
							// A refusal is the absence of a build, not a build with no breaches.
						}
					}
				}
			} finally {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
		int[] shared = new int[10];
		int both = 0;
		for (String config : off.keySet()) {
			if (!on.containsKey(config)) {
				continue;
			}
			both++;
			int[] a = off.get(config);
			int[] b = on.get(config);
			for (int field = 0; field < 5; field++) {
				shared[field] += a[field];
				shared[5 + field] += b[field];
			}
		}
		System.out.println("SHARED configs=" + both
			+ " | off breaches=" + shared[0] + " blocks=" + shared[1] + " ofOne=" + shared[2]
			+ " worst=" + shared[3] + " cmds=" + shared[4]
			+ " | on breaches=" + shared[5] + " blocks=" + shared[6] + " ofOne=" + shared[7]
			+ " worst=" + shared[8] + " cmds=" + shared[9]);
		System.out.println("ONLY-ON configs=" + (on.size() - both) + " of " + on.size());
		on.forEach((config, value) -> {
			if (!off.containsKey(config)) {
				System.out.println("    " + config + " breaches=" + value[0]
					+ " blocks=" + value[1] + " worst=" + value[3]);
			}
		});
	}

	private static List<SongBuilder.EventNote> guardian() throws Exception {
		java.nio.file.Path songs = java.nio.file.Path.of("run", "config", "midicraft", "songs");
		try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(
				BreachView.songFile("deltarune-ch-4-guardian"))) {
			com.midicraft.client.composer.ComposerProject raw =
				new com.google.gson.Gson().fromJson(reader,
					com.midicraft.client.composer.ComposerProject.class);
			com.midicraft.client.composer.ComposerProject song =
				new com.midicraft.client.composer.ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(java.util.Set.of(), true));
		}
	}

	/** The same song {@code NoteMachineReaderTest} builds, copied so this probe stands alone. */
	private static List<SongBuilder.EventNote> sampleSong() {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:stone",
			"minecraft:oak_planks", "minecraft:sand"};
		Random random = new Random(20260729L);
		int time = 0;
		for (int event = 0; event < 220; event++) {
			time += 1 + random.nextInt(9);
			int chord = switch (event % 7) {
				case 0 -> 1;
				case 1 -> 2;
				case 2 -> 3;
				case 3 -> 4;
				case 4 -> 7;
				case 5 -> 12;
				default -> 2;
			};
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1 + index % 3, index,
					random.nextInt(25), instruments[random.nextInt(instruments.length)]));
			}
		}
		return List.copyOf(notes);
	}

	/** Pad the chord that fits forward until it cuts. Both flags, every way round. */
	@Test
	void pricesCuttingAChordThatFits() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean cuts : new boolean[] {false, true}) {
			for (int pad : new int[] {2, 4}) {
				SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
				SongBuilder.CUT_PAD_COLUMNS = pad;
				int built = 0;
				int refused = 0;
				int clean = 0;
				int breaches = 0;
				int breachBlocks = 0;
				int worst = 0;
				long wrong = 0;
				long blocks = 0;
				try {
					for (int floors = 2; floors <= 6; floors++) {
						for (int width = 12; width <= 48; width += 4) {
							try {
								SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
									new BlockPos(0, 64, 0), notes,
									SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
									new SongBuilder.BuildLimits(4, width, floors));
								built++;
								clean += plan.breaches().isEmpty() ? 1 : 0;
								breaches += plan.breaches().size();
								for (int breach : plan.breaches()) {
									breachBlocks += breach;
									worst = Math.max(worst, breach);
								}
								wrong += plan.wrongNotes();
								blocks += plan.commands().size();
							} catch (RuntimeException no) {
								refused++;
							}
						}
					}
				} finally {
					SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
					SongBuilder.CUT_PAD_COLUMNS = 2;
				}
				System.out.println("CUTFITS cuts=" + (cuts ? "on " : "off") + " cutPad=" + pad
					+ " built=" + built + " refused=" + refused + " clean=" + clean
					+ " breaches=" + breaches + " breachBlocks=" + breachBlocks
					+ " worst=" + worst + " wrong=" + wrong + " blocks=" + blocks);
			}
		}
	}

	/** And the same on the live 44 by 3, where the breach of ten is. */
	@Test
	void pricesItOnTheRealBuild() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 44, 3));
				System.out.println("CUTFITS44 cuts=" + (cuts ? "on " : "off")
					+ " breaches=" + plan.breaches() + " wrong=" + plan.wrongNotes()
					+ " blocks=" + plan.commands().size() + " spanZ=" + plan.spanZ());
			} catch (RuntimeException no) {
				System.out.println("CUTFITS44 cuts=" + (cuts ? "on " : "off") + " REFUSED: "
					+ no.getMessage());
			} finally {
				SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
			}
		}
	}

	/** What the cut actually does at the breach of ten, rather than what it ought to. */
	@Test
	void tracesTheCutAtTheBreachOfTen() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean cuts : new boolean[] {false, true}) {
			SongBuilder.CUTS_A_CHORD_THAT_FITS = cuts;
			System.out.println("ARM cuts=" + cuts);
			SongBuilder.TRACE_TURNS = true;
			try {
				SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 44, 3));
			} finally {
				SongBuilder.TRACE_TURNS = false;
				SongBuilder.CUTS_A_CHORD_THAT_FITS = true;
			}
		}
	}

	/** The breach of ten on the live build, in the coordinates a paste at 0 64 0 lands on. */
	@Test
	void dumpsTheBreachOfTen() throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 44, 3));
		System.out.println("PLAN breaches=" + plan.breaches() + " spanX=" + plan.spanX()
			+ " nearWall=" + plan.nearWall() + " farWall=" + plan.farWall());
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			if (x >= 40 && y >= 68 && y <= 70 && z >= 225 && z <= 227) {
				System.out.println("    " + x + " " + y + " " + z + "  " + word[4]);
			}
		}
	}

	/** The breach of ten: does the plan asking the blocks close that lane? */
	@Test
	void pricesAskingTheBlocksBehind() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean asks : new boolean[] {false, true}) {
			SongBuilder.PLAN_ASKS_THE_BLOCKS_BEHIND = asks;
			try {
				SongBuilder.PastePlan real = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 44, 3));
				System.out.println("ASKS44 asks=" + asks + " breaches=" + real.breaches()
					+ " wrong=" + real.wrongNotes() + " blocks=" + real.commands().size()
					+ " spanZ=" + real.spanZ());
			} catch (RuntimeException no) {
				System.out.println("ASKS44 asks=" + asks + " REFUSED: " + no.getMessage());
			}
			int built = 0;
			int refused = 0;
			int clean = 0;
			int breaches = 0;
			int breachBlocks = 0;
			int worst = 0;
			long wrong = 0;
			long blocks = 0;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							built++;
							clean += plan.breaches().isEmpty() ? 1 : 0;
							breaches += plan.breaches().size();
							for (int breach : plan.breaches()) {
								breachBlocks += breach;
								worst = Math.max(worst, breach);
							}
							wrong += plan.wrongNotes();
							blocks += plan.commands().size();
						} catch (RuntimeException no) {
							refused++;
						}
					}
				}
			} finally {
				SongBuilder.PLAN_ASKS_THE_BLOCKS_BEHIND = true;
			}
			System.out.println("ASKSSWEEP asks=" + asks + " built=" + built + " refused=" + refused
				+ " clean=" + clean + " breaches=" + breaches + " breachBlocks=" + breachBlocks
				+ " worst=" + worst + " wrong=" + wrong + " blocks=" + blocks);
		}
	}

	/** Guardian at every width and floor count, one line each, the plan's question both ways. */
	@Test
	void tabulatesEveryGuardianSize() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		Map<String, String> off = new LinkedHashMap<>();
		for (boolean asks : new boolean[] {false, true}) {
			SongBuilder.PLAN_ASKS_THE_BLOCKS_BEHIND = asks;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 12; width <= 48; width += 4) {
						String key = String.format("%2dw x %df", width, floors);
						String value;
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							value = String.format("%3d breaches %4d blocks worst %2d",
								plan.breaches().size(),
								plan.breaches().stream().mapToInt(Integer::intValue).sum(),
								plan.breaches().stream().mapToInt(Integer::intValue).max().orElse(0));
						} catch (RuntimeException no) {
							value = "REFUSED";
						}
						if (asks) {
							System.out.println("SIZE " + key + "  off: " + off.get(key)
								+ "   |  on: " + value);
						} else {
							off.put(key, value);
						}
					}
				}
			} finally {
				SongBuilder.PLAN_ASKS_THE_BLOCKS_BEHIND = true;
			}
		}
	}

	/** Are twelve and sixteen wide the same build, or only the same numbers? */
	@Test
	void comparesTwelveAndSixteenWide() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		SongBuilder.PastePlan twelve = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 12, 6));
		SongBuilder.PastePlan sixteen = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 6));
		System.out.println("WIDTH 12: cmds=" + twelve.commands().size() + " spanX=" + twelve.spanX()
			+ " spanZ=" + twelve.spanZ() + " width=" + twelve.width()
			+ " nearWall=" + twelve.nearWall() + " farWall=" + twelve.farWall()
			+ " breaches=" + twelve.breaches());
		System.out.println("WIDTH 16: cmds=" + sixteen.commands().size() + " spanX=" + sixteen.spanX()
			+ " spanZ=" + sixteen.spanZ() + " width=" + sixteen.width()
			+ " nearWall=" + sixteen.nearWall() + " farWall=" + sixteen.farWall()
			+ " breaches=" + sixteen.breaches());
		System.out.println("WIDTH identical=" + twelve.commands().equals(sixteen.commands()));
	}

	/** The worst breach at sixteen wide over six floors, in paste coordinates. */
	@Test
	void findsTheWorstBreachAtSixteenBySix() throws Exception {
		SongBuilder.TRACE = true;
		SongBuilder.HEAD_KEEPS_ONE_BACK_FLANK = false;
		try {
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 6));
		} finally {
			SongBuilder.TRACE = false;
			SongBuilder.HEAD_KEEPS_ONE_BACK_FLANK = true;
		}
	}

	/** Every block outside the walls at sixteen wide over six floors, worst runs first. */
	@Test
	void findsTheOutsideBlocks() throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 6));
		System.out.println("WALLS near=" + plan.nearWall() + " far=" + plan.farWall()
			+ " breaches=" + plan.breaches());
		Map<String, int[]> worst = new LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			int out = x < plan.nearWall() ? plan.nearWall() - x
				: x > plan.farWall() ? x - plan.farWall() : 0;
			if (out == 0) {
				continue;
			}
			String key = y + " " + z;
			int[] seen = worst.get(key);
			if (seen == null || out > seen[0]) {
				worst.put(key, new int[] {out, x, y, z});
			}
		}
		worst.values().stream()
			.sorted((a, b) -> Integer.compare(b[0], a[0]))
			.limit(8)
			.forEach(run -> System.out.println("    out=" + run[0] + "  at " + run[1] + " " + run[2]
				+ " " + run[3]));
	}

	/** The head of six: 16 by 6 where they built it, then every Guardian size. */
	@Test
	void pricesTheHalfHead() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean half : new boolean[] {false, true}) {
			SongBuilder.HEAD_KEEPS_ONE_BACK_FLANK = half;
			try {
				SongBuilder.PastePlan one = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 6));
				System.out.println("HALF16x6 half=" + half + " breaches=" + one.breaches().size()
					+ " blocks=" + one.breaches().stream().mapToInt(Integer::intValue).sum()
					+ " worst=" + one.breaches().stream().mapToInt(Integer::intValue).max().orElse(0)
					+ " wrong=" + one.wrongNotes() + " cmds=" + one.commands().size());
			} catch (RuntimeException no) {
				System.out.println("HALF16x6 half=" + half + " REFUSED: " + no.getMessage());
			}
			int built = 0;
			int refused = 0;
			int clean = 0;
			int breaches = 0;
			int breachBlocks = 0;
			int worst = 0;
			long wrong = 0;
			long blocks = 0;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 16; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							built++;
							clean += plan.breaches().isEmpty() ? 1 : 0;
							breaches += plan.breaches().size();
							for (int breach : plan.breaches()) {
								breachBlocks += breach;
								worst = Math.max(worst, breach);
							}
							wrong += plan.wrongNotes();
							blocks += plan.commands().size();
						} catch (RuntimeException no) {
							refused++;
						}
					}
				}
			} finally {
				SongBuilder.HEAD_KEEPS_ONE_BACK_FLANK = true;
			}
			System.out.println("HALFSWEEP half=" + half + " built=" + built + " refused=" + refused
				+ " clean=" + clean + " breaches=" + breaches + " breachBlocks=" + breachBlocks
				+ " worst=" + worst + " wrong=" + wrong + " blocks=" + blocks);
		}
	}

	/** The next worst breach at sixteen by six: its blocks, and the run that leads into it. */
	@Test
	void dumpsTheNextBreach() throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 6));
		System.out.println("WALLS near=" + plan.nearWall() + " far=" + plan.farWall()
			+ " breaches=" + plan.breaches());
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			int x = Integer.parseInt(word[1]);
			int y = Integer.parseInt(word[2]);
			int z = Integer.parseInt(word[3]);
			if (y >= 80 && y <= 82 && z >= 122 && z <= 125) {
				System.out.println("    " + x + " " + y + " " + z + "  " + word[4]);
			}
		}
	}

	/** Does looking for the free side ever fire, and does it change anything? */
	@Test
	void countsTheSideSwaps() throws Exception {
		List<SongBuilder.EventNote> notes = guardian();
		for (boolean look : new boolean[] {false, true}) {
			SongBuilder.HEAD_LOOKS_FOR_ITS_FREE_SIDE = look;
			SongBuilder.HEAD_SIDES_SWAPPED = 0;
			int built = 0;
			int clean = 0;
			int breaches = 0;
			int breachBlocks = 0;
			int worst = 0;
			long wrong = 0;
			try {
				for (int floors = 2; floors <= 6; floors++) {
					for (int width = 16; width <= 48; width += 4) {
						try {
							SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
								new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
								new SongBuilder.BuildLimits(4, width, floors));
							built++;
							clean += plan.breaches().isEmpty() ? 1 : 0;
							breaches += plan.breaches().size();
							for (int breach : plan.breaches()) {
								breachBlocks += breach;
								worst = Math.max(worst, breach);
							}
							wrong += plan.wrongNotes();
						} catch (RuntimeException no) {
							// counted by built
						}
					}
				}
			} finally {
				SongBuilder.HEAD_LOOKS_FOR_ITS_FREE_SIDE = true;
			}
			System.out.println("SIDES look=" + look + " swapped=" + SongBuilder.HEAD_SIDES_SWAPPED
				+ " built=" + built + " clean=" + clean + " breaches=" + breaches
				+ " breachBlocks=" + breachBlocks + " worst=" + worst + " wrong=" + wrong);
		}
	}

	/** The live 44 by 3, with the cut head of six on and collisions marked. */
	@Test
	void marksTheCutCollisionOnTheLiveBuild() throws Exception {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian(),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 44, 3));
		System.out.println("LIVE44 collisions=" + plan.collisions().size()
			+ " breaches=" + plan.breaches().size() + " wrong=" + plan.wrongNotes()
			+ " nearWall=" + plan.nearWall() + " farWall=" + plan.farWall());
		int shown = 0;
		for (Map.Entry<net.minecraft.core.BlockPos, String> mark : plan.collisions().entrySet()) {
			if (shown++ >= 40) {
				break;
			}
			System.out.println("    " + mark.getKey().getX() + " " + mark.getKey().getY() + " "
				+ mark.getKey().getZ() + "  " + mark.getValue());
		}
	}
}
