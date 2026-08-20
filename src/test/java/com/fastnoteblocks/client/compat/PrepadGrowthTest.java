package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the off-bus prepad is worth now the raised ascent exists.
 *
 * <p>The prepad chases the wall so that no cell of dust stands between the bus and the staircase,
 * because one used to cost the lane its discount. A raised pad keeps that discount however many
 * columns stand before the wall, so on the arm that raises pads the loop is buying something the
 * lane already has -- and it is buying it with wire the next chord needed. Measured rather than
 * argued: the cap, the wire it must leave, and switching it off altogether, on both arms.</p>
 */
@Tag("sweep")
class PrepadGrowthTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final Path SONGS = Path.of("run", "config", "fast-noteblocks", "songs");

	private static final List<String> PICKED = List.of(
		"deltarune-ch-4-guardian",
		"illit-do-the-dance",
		"big-shot",
		"hopes-and-dreams",
		"golden-brown-2xspeed",
		"adventure-of-a-lifetime",
		"michael-jackson-thriller",
		"aria-math-c418");

	private static List<SongBuilder.EventNote> load(String name) throws Exception {
		try (Reader reader = Files.newBufferedReader(SONGS.resolve(name + ".json"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject song = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(song.toSequenceTracks(Set.of(), true));
		}
	}

	private record Result(int lanes, int blocks, int worst, int wrong, long length, long volume,
			int refused) {
	}

	private static Result sweep(List<List<SongBuilder.EventNote>> songs) {
		int lanes = 0;
		int blocks = 0;
		int worst = 0;
		int wrong = 0;
		long length = 0;
		long volume = 0;
		int refused = 0;
		for (List<SongBuilder.EventNote> notes : songs) {
			for (int floors = 1; floors <= 6; floors++) {
				for (int width = 12; width <= 48; width += 4) {
					try {
						SongBuilder.PastePlan plan = SongBuilder.createPastePlan(
							new BlockPos(0, 64, 0), notes, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
							new SongBuilder.BuildLimits(4, width, floors));
						lanes += plan.breaches().size();
						blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
						worst = Math.max(worst, plan.worstBreach());
						wrong += plan.wrongNotes();
						length += plan.width();
						volume += plan.commands().size();
					} catch (RuntimeException refusedHere) {
						refused++;
					}
				}
			}
		}
		return new Result(lanes, blocks, worst, wrong, length, volume, refused);
	}

	@Test
	void weighsEveryBrakeOnThePrepad() throws Exception {
		List<List<SongBuilder.EventNote>> songs = new ArrayList<>();
		for (String name : PICKED) {
			songs.add(load(name));
		}
		System.out.println();
		System.out.println("==== the off-bus prepad, braked every way, 480 builds an arm ====");
		for (boolean raised : new boolean[] {true, false}) {
			for (String arm : ARMS) {
				arm(arm);
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = raised;
				Result result = sweep(songs);
				// println and not printf: Bootstrap redirects stdout into log4j and only a completed
				// line reaches it, so a printf without a newline of its own is simply lost.
				System.out.println(String.format(
					"   raised=%-3s prepad=%-9s  lanes=%4d blocks=%5d worst=%2d "
						+ "wrong=%d refused=%d length=%d volume=%d",
					raised ? "on" : "off", arm, result.lanes(), result.blocks(), result.worst(),
					result.wrong(), result.refused(), result.length(), result.volume()));
			}
		}
		reset();
	}

	private static void reset() {
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
		// The shipping default, which is off -- see the flag. A reset that restores something else is
		// how a flag leaks out of the class that set it.
		SongBuilder.PREPADS_FOR_THE_OFF_BUS_DISCOUNT = false;
		SongBuilder.PREPAD_NEVER_STRANDS_THE_NEXT = true;
		SongBuilder.PREPAD_NEXT_MUST_FIT = true;
		SongBuilder.PREPAD_GROWTH_CAP = Integer.MAX_VALUE;
		SongBuilder.PREPAD_LEAVES_WIRE = 0;
	}

	/** Sets every prepad control from one short arm name, so the arms cannot drift apart. */
	private static void arm(String arm) {
		reset();
		SongBuilder.PREPADS_FOR_THE_OFF_BUS_DISCOUNT = !arm.equals("off");
		SongBuilder.PREPAD_NEVER_STRANDS_THE_NEXT = arm.startsWith("guard")
			|| arm.startsWith("strict");
		SongBuilder.PREPAD_NEXT_MUST_FIT = arm.startsWith("strict");
		SongBuilder.PREPAD_GROWTH_CAP = arm.contains("cap ")
			? Integer.parseInt(arm.substring(arm.indexOf("cap ") + 4)) : Integer.MAX_VALUE;
	}

	private static final String[] ARMS = {
		"as it is", "cap 1", "guard", "strict", "strict+cap 1", "strict+cap 2", "off"};

	/**
	 * The same three arms at the live size, and read back through the machine.
	 *
	 * <p>A sweep total says a build is smaller and says nothing about whether it fires. Turning the
	 * prepad off moves chords, and a moved chord is exactly what puts a note on somebody else's tick
	 * -- so every arm quoted has to be read by {@link NoteMachineReader} before it is quoted at all.
	 * See the eight hundred and forty-three notes a {@code PastePlan} was happy about.</p>
	 */
	@Test
	void readsBackTheArmsWorthShipping() throws Exception {
		System.out.println();
		System.out.println("==== live config 40w x 5f, read back ====");
		for (String arm : ARMS) {
			arm(arm);
			int lanes = 0;
			int blocks = 0;
			int unreached = 0;
			int wrong = 0;
			long length = 0;
			StringBuilder perSong = new StringBuilder();
			for (String name : PICKED) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					load(name), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(16, 40, 5));
				int dead = readAll(placeInWorld(plan)).unreachedNotes();
				lanes += plan.breaches().size();
				blocks += plan.breaches().stream().mapToInt(Integer::intValue).sum();
				unreached += dead;
				wrong += plan.wrongNotes();
				length += plan.width();
				perSong.append("      ").append(name)
					.append("  lanes=").append(plan.breaches().size())
					.append(" blocks=").append(plan.breaches().stream()
						.mapToInt(Integer::intValue).sum())
					.append(" worst=").append(plan.worstBreach())
					.append(" unreached=").append(dead)
					.append(" wrong=").append(plan.wrongNotes())
					.append(" length=").append(plan.width()).append('\n');
			}
			System.out.println("   prepad=" + arm + "   lanes=" + lanes + " blocks=" + blocks
				+ " unreached=" + unreached + " wrong=" + wrong + " length=" + length);
			System.out.print(perSong);
		}
		reset();
	}

	/**
	 * Every Guardian size that still breaches, now the prepad is off.
	 *
	 * <p>Nought on Guardian is the goal, so what is wanted is not a total but a list of the sizes that
	 * are not there yet, worst first, each with the fault the walk wrote at the time. Read back as
	 * well: a config with no breaches and a dead machine is not a config that passes.</p>
	 */
	@Test
	void listsEveryGuardianSizeStillBreaching() throws Exception {
		List<SongBuilder.EventNote> guardian = load("deltarune-ch-4-guardian");
		reset();
		System.out.println();
		System.out.println("==== Guardian, prepad off, every size ====");
		int clean = 0;
		int dirty = 0;
		List<String> lines = new ArrayList<>();
		for (int floors = 1; floors <= 6; floors++) {
			for (int width = 12; width <= 48; width += 4) {
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, width, floors));
				int dead = readAll(placeInWorld(plan)).unreachedNotes();
				if (plan.breaches().isEmpty() && dead == 0) {
					clean++;
					continue;
				}
				dirty++;
				lines.add(String.format("   %2dw x %df   worst=%2d lanes=%d blocks=%3d unreached=%d"
					+ " wrong=%d   %s", width, floors, plan.worstBreach(), plan.breaches().size(),
					plan.breaches().stream().mapToInt(Integer::intValue).sum(), dead,
					plan.wrongNotes(), plan.breaches()));
			}
		}
		lines.sort((a, b) -> b.compareTo(a));
		lines.forEach(System.out::println);
		System.out.println("   clean " + clean + " of " + (clean + dirty));
		// And the live size, which is not in the grid above.
		SongBuilder.PastePlan mine = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), guardian,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(16, 40, 5));
		System.out.println("   40w x 5f (live)   breaches=" + mine.breaches() + " unreached="
			+ readAll(placeInWorld(mine)).unreachedNotes() + " wrong=" + mine.wrongNotes()
			+ " length=" + mine.width());
	}

	/**
	 * Guardian at 44 wide over three floors, which is the breach the prepad was written for.
	 *
	 * <p>The breach of eleven: a lane one column short of its wall with five blocks of wire,
	 * wanting one for the column and five for the climb. Taking the prepad away has to be measured
	 * against the case it exists to fix, not only against the totals -- a change that wins on average
	 * and loses the one build somebody went and stood in is not a win.</p>
	 */
	@Test
	void checksTheBreachThePrepadWasWrittenFor() throws Exception {
		List<SongBuilder.EventNote> guardian = load("deltarune-ch-4-guardian");
		System.out.println();
		System.out.println("==== Guardian 44w x 3f, the prepad's founding case ====");
		for (boolean raised : new boolean[] {true, false}) {
			for (String arm : ARMS) {
				arm(arm);
				SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = raised;
				SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
					guardian, SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
					new SongBuilder.BuildLimits(4, 44, 3));
				System.out.println("   raised=" + (raised ? "on " : "off") + " prepad=" + arm
					+ "   breaches=" + plan.breaches() + " worst=" + plan.worstBreach()
					+ " unreached=" + readAll(placeInWorld(plan)).unreachedNotes()
					+ " wrong=" + plan.wrongNotes() + " length=" + plan.width());
			}
		}
		SongBuilder.PADS_AT_BUS_HEIGHT_INTO_A_CLIMB = true;
		reset();
	}

	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>
			placeInWorld(SongBuilder.PastePlan plan) {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world =
			new java.util.HashMap<>();
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			world.put(new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3])), parse(word[4]));
		}
		return world;
	}

	private static NoteMachineReader.Reading readAll(
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world) {
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
		return NoteMachineReader.read("Prepad", new BlockPos(minX, minY, minZ),
			new BlockPos(maxX, maxY, maxZ),
			at -> world.getOrDefault(at, net.minecraft.world.level.block.Blocks.AIR
				.defaultBlockState()));
	}

	private static net.minecraft.world.level.block.state.BlockState parse(String blockState) {
		try {
			return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
				net.minecraft.core.registries.BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException("unparseable block state: " + blockState, unparseable);
		}
	}
}
