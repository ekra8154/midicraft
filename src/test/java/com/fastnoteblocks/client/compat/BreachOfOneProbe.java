package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Scratch: The whole walk around the single breach on Guardian at 44 wide over three floors. */
@Tag("sweep")
class BreachOfOneProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	@Test
	void countsTheBreaches() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (String arm : new String[] {"off", "on"}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = arm.equals("on");
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 44, 3));
				int worst = plan.breaches().stream().mapToInt(Integer::intValue).max().orElse(0);
				System.out.println("HANDOVER " + arm + " breaches=" + plan.breaches().size()
					+ " worst=" + worst + " blocks=" + plan.breaches().stream()
						.mapToInt(Integer::intValue).sum()
					+ " wrong=" + plan.wrongNotes() + " cmds=" + plan.commands().size()
					+ " spanZ=" + plan.spanZ() + " " + plan.breaches());
			} catch (RuntimeException refused) {
				System.out.println("HANDOVER " + arm + " REFUSED: " + refused.getMessage());
			} finally {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
	}

	/**
	 * Guardian across every width and floor count, the handover column on and off.
	 *
	 * <p>Breaches of exactly one are counted apart, because that is the signature of the class this
	 * is meant to close: a lane coming to rest one column past its wall because the chord before it
	 * landed flush and the staircase had nowhere inside to stand. A proxy, not a proof -- a lane can
	 * reach minus one by other routes -- but if the class is shut, this number goes to nothing.</p>
	 */
	@Test
	void sweepsTheHandoverColumnEveryWidthAndFloor() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean on : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = on;
			int built = 0;
			int refused = 0;
			int clean = 0;
			int breaches = 0;
			int breachBlocks = 0;
			int ones = 0;
			int worst = 0;
			long blocks = 0;
			long span = 0;
			long wrong = 0;
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
								ones += breach == 1 ? 1 : 0;
								worst = Math.max(worst, breach);
							}
							blocks += plan.commands().size();
							span += plan.spanZ();
							wrong += plan.wrongNotes();
							if (plan.wrongNotes() > 0) {
								System.out.println("  WRONG w=" + width + " f=" + floors
									+ " on=" + on);
								plan.faults().stream().filter(fault -> fault.startsWith("the note"))
									.forEach(fault -> System.out.println("    " + fault));
							}
						} catch (RuntimeException no) {
							refused++;
						}
					}
				}
			} finally {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
			System.out.println("HANDOVERSWEEP " + (on ? "on " : "off")
				+ " built=" + built + " refused=" + refused + " clean=" + clean
				+ " breaches=" + breaches + " ofOne=" + ones + " breachBlocks=" + breachBlocks
				+ " worst=" + worst + " wrong=" + wrong + " blocks=" + blocks + " spanZ=" + span);
		}
	}

	/** The one wrong note the handover column costs, at 32 wide over two floors. */
	@Test
	void walksTheWrongNote() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean on : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = on;
			System.out.println("ARM " + (on ? "on" : "off"));
			SongBuilder.TRACE = true;
			try {
				SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
					SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, 32, 2));
			} finally {
				SongBuilder.TRACE = false;
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
	}

	/** Which maxFloors a player can leave alone and still get the wrong note. */
	@Test
	void checksTheRepro() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
		try {
			for (int[] limits : new int[][] {{16, 32, 2}, {4, 32, 2}, {16, 48, 3}, {4, 48, 3}}) {
				try {
					SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
						notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(limits[0], limits[1], limits[2]));
					System.out.println("REPRO maxFloors=" + limits[0] + " w=" + limits[1]
						+ " f=" + limits[2] + " wrong=" + plan.wrongNotes()
						+ " breaches=" + plan.breaches().size() + " spanZ=" + plan.spanZ());
					plan.faults().stream().filter(fault -> fault.startsWith("the note"))
						.forEach(fault -> System.out.println("    " + fault));
				} catch (RuntimeException no) {
					System.out.println("REPRO maxFloors=" + limits[0] + " w=" + limits[1]
						+ " f=" + limits[2] + " REFUSED: " + no.getMessage());
				}
			}
		} finally {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
		}
	}

	/** What is actually standing at the corner in each arm, in coordinates to /tp straight to. */
	@Test
	void dumpsTheCorner() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean on : new boolean[] {false, true}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = on;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 32, 2));
				System.out.println("CORNER on=" + on + " wrong=" + plan.wrongNotes()
					+ " blocks=" + plan.commands().size() + " spanZ=" + plan.spanZ());
				for (String command : plan.commands()) {
					String[] word = command.split(" ");
					int x = Integer.parseInt(word[1]);
					int y = Integer.parseInt(word[2]);
					int z = Integer.parseInt(word[3]);
					if (x >= 0 && x <= 7 && y >= 68 && y <= 70 && z >= 138 && z <= 143) {
						System.out.println("    " + x + " " + y + " " + z + "  " + word[4]);
					}
				}
			} catch (RuntimeException no) {
				System.out.println("CORNER on=" + on + " REFUSED: " + no.getMessage());
			} finally {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
	}

	/** Which build is the one being stood in: the two blocks, in every candidate config. */
	@Test
	void namesTheBuildFromTwoBlocks() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (int[] arm : new int[][] {{32, 2, 0}, {32, 2, 1}, {44, 3, 0}, {44, 3, 1}}) {
			SongBuilder.RESERVES_THE_HANDOVER_COLUMN = arm[2] == 1;
			try {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, arm[0], arm[1]));
				StringBuilder found = new StringBuilder();
				for (String command : plan.commands()) {
					String[] word = command.split(" ");
					String at = word[1] + " " + word[2] + " " + word[3];
					if (at.equals("3 69 140") || at.equals("3 69 141") || at.equals("3 70 141")
							|| at.equals("2 69 140") || at.equals("2 70 140")) {
						found.append("\n      ").append(at).append("  ").append(word[4]);
					}
				}
				System.out.println("WHICH w=" + arm[0] + " f=" + arm[1]
					+ " handover=" + (arm[2] == 1 ? "on " : "off")
					+ " wrong=" + plan.wrongNotes() + " blocks=" + plan.commands().size()
					+ " spanZ=" + plan.spanZ() + found);
			} catch (RuntimeException no) {
				System.out.println("WHICH w=" + arm[0] + " f=" + arm[1] + " REFUSED");
			} finally {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
			}
		}
	}

	/** The odd note's side, across every width and floor count, and with the handover both ways. */
	@Test
	void sweepsTheOddNoteSide() throws Exception {
		List<SongBuilder.EventNote> notes = load("deltarune-ch-4-guardian");
		for (boolean handover : new boolean[] {false, true}) {
			for (boolean lowZ : new boolean[] {false, true}) {
				SongBuilder.RESERVES_THE_HANDOVER_COLUMN = handover;
				SongBuilder.BUS_ODD_NOTE_AWAY_FROM_NEXT_LANE = lowZ;
				int built = 0;
				int refused = 0;
				int clean = 0;
				int breaches = 0;
				int breachBlocks = 0;
				int worst = 0;
				long blocks = 0;
				long span = 0;
				long wrong = 0;
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
								blocks += plan.commands().size();
								span += plan.spanZ();
								wrong += plan.wrongNotes();
							} catch (RuntimeException no) {
								refused++;
							}
						}
					}
				} finally {
					SongBuilder.RESERVES_THE_HANDOVER_COLUMN = true;
					SongBuilder.BUS_ODD_NOTE_AWAY_FROM_NEXT_LANE = true;
				}
				System.out.println("ODDSIDE handover=" + (handover ? "on " : "off")
					+ " lowZ=" + (lowZ ? "on " : "off")
					+ " built=" + built + " refused=" + refused + " clean=" + clean
					+ " breaches=" + breaches + " breachBlocks=" + breachBlocks
					+ " worst=" + worst + " wrong=" + wrong + " blocks=" + blocks
					+ " spanZ=" + span);
			}
		}
	}

	@Test
	void walksTheWholeThing() throws Exception {
		SongBuilder.TRACE = true;
		try {
			SongBuilder.createPastePlan(new BlockPos(0, 64, 0), load("deltarune-ch-4-guardian"),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 44, 3));
		} finally {
			SongBuilder.TRACE = false;
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
}
