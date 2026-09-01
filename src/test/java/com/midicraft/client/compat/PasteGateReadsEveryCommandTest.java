package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * That the sender can tell where every command it is given wants to go.
 *
 * <p>This is the assumption the whole range gate rests on, and the one that fails quietly. A
 * command the sender cannot read is sent unexamined -- which is the old behaviour, and the old
 * behaviour is the one that loses blocks when you walk away. So the gate is only worth as much as
 * this: every command a build is made of, read.</p>
 *
 * <p>The title sign is the case worth naming. It is the one command in a paste with spaces inside
 * it, because it carries a song's name in its block state, and {@code SongBuilder} says so where it
 * inserts it -- "every pass that reads the list back by splitting on those would choke on" it. The
 * split here stops after the coordinates for that reason, and the sign is in every build below.</p>
 */
class PasteGateReadsEveryCommandTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void readsAPlainSetblock() {
		CommandPasteSender.Placement placement =
			CommandPasteSender.read("setblock 3 65 -6 minecraft:sea_lantern replace");

		assertEquals(new BlockPos(3, 65, -6), placement.at());
		assertEquals("minecraft:sea_lantern", placement.block());
	}

	@Test
	void leavesTheBlockStateOutOfTheBlock() {
		assertEquals("minecraft:repeater", CommandPasteSender.read(
			"setblock -14 70 8 minecraft:repeater[facing=east,delay=2] replace").block());
	}

	/** The one command with spaces in it, and the one that a naive split would read as a number. */
	@Test
	void readsATitleSignWhoseNameHasSpacesInIt() {
		CommandPasteSender.Placement placement = CommandPasteSender.read(
			"setblock 0 64 0 minecraft:oak_wall_sign[facing=north]"
			+ "{front_text:{messages:[\"16|3 Song of\",\"Storms\",\"\",\"\"]}} replace");

		assertEquals(new BlockPos(0, 64, 0), placement.at());
		assertEquals("minecraft:oak_wall_sign", placement.block());
	}

	@Test
	void namespacesABlockThatDidNotSayOne() {
		assertEquals("minecraft:stone",
			CommandPasteSender.read("setblock 1 2 3 stone replace").block());
	}

	/**
	 * Anything unreadable is kept and sent, not dropped. Unable to gate is not unable to place.
	 */
	@Test
	void keepsACommandItCannotRead() {
		CommandPasteSender.Placement placement = CommandPasteSender.read("say hello");

		assertEquals("say hello", placement.command());
		assertNull(placement.at());
		assertNull(placement.block());
	}

	/**
	 * The one that matters: real builds, in every mode, read to the last command.
	 *
	 * <p>A spec rather than a song, so this runs anywhere -- the library lives outside the repo.
	 * Thirty notes in a chord and a name with spaces in it is enough to reach the sign, the walls,
	 * the marking pass and the staircases.</p>
	 */
	@Test
	void readsEveryCommandOfARealBuildInEveryMode() {
		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes("5x4 30 2x6 24 18@1", 4), mode,
				new SongBuilder.BuildLimits(4, 16, 3), SongBuilder.WalkStart.HEAD,
				"Song of Storms");

			for (String command : plan.commands()) {
				CommandPasteSender.Placement placement = CommandPasteSender.read(command);
				assertNotNull(placement.at(),
					mode + " emitted a command the sender cannot place a position on, so it would "
					+ "be sent without the range gate: " + command);
				assertNotNull(placement.block(), mode + " emitted a command the sender cannot read "
					+ "a block off, so it could never be checked: " + command);
				assertEquals(command.split(" ")[1] + " " + command.split(" ")[2] + " "
					+ command.split(" ")[3],
					placement.at().getX() + " " + placement.at().getY() + " "
					+ placement.at().getZ(), "read the wrong position off " + command);
			}
		}
	}

	/**
	 * No command writing air is undoing an earlier command of the same paste.
	 *
	 * <p>This is what lets the sender drop an air command when the cell is already air, which is a
	 * fifth to nearly a third of everything a build sends. Dropping it is only equivalent while air
	 * means "nothing goes here" and never "take away what I just put here" -- if a build could lay
	 * stone and then clear it, skipping the clearing would leave the stone standing.</p>
	 *
	 * <p>It holds because the plan is a map keyed by cell, so a second claim on one is a collision it
	 * reports rather than a rewrite. The passes that do add a second command for a cell -- the
	 * marking pass and its lanterns, the title sign -- never add air. This asserts that, so that a
	 * pass which one day does gets caught here rather than by a hole in a build.</p>
	 */
	@Test
	void noAirCommandTakesBackAnEarlierBlock() {
		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes("5x4 30 2x6 24:12s12p 18@1", 4), mode,
				new SongBuilder.BuildLimits(4, 16, 3), SongBuilder.WalkStart.HEAD, "Song of Storms");

			Set<BlockPos> written = new java.util.HashSet<>();
			for (String command : plan.commands()) {
				CommandPasteSender.Placement placement = CommandPasteSender.read(command);
				if ("minecraft:air".equals(placement.block()) && written.contains(placement.at())) {
					throw new AssertionError(mode + " writes air over a cell it already filled, at "
						+ placement.at().getX() + " " + placement.at().getY() + " "
						+ placement.at().getZ() + ", so that air is undoing something and the "
						+ "sender must stop skipping it");
				}
				written.add(placement.at());
			}
		}
	}

	/**
	 * Every note block claims the cell above it, whatever happens to be standing there.
	 *
	 * <p>The other half of what the air skip is allowed to assume, and the half that is easy to get
	 * backwards. A note block needs air over it to sound, so the plan writes air there -- and it
	 * writes it <em>unconditionally</em>, because a plan is built in its own space and never asks the
	 * world what is already at a cell. So the cell is claimed, and a second module reaching for it
	 * collides in the ordinary way.</p>
	 *
	 * <p>Which is what makes the skip safe against the obvious worry: that a cell already holding air
	 * in the world would go unclaimed in the plan, and a later erroneous placement into it would land
	 * silently. The skip happens in the sender, per command, after the whole plan and every collision
	 * in it has been decided. It can drop a command; it cannot un-claim a cell.</p>
	 */
	@Test
	void everyNoteBlockClaimsTheCellAboveIt() {
		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes("5x4 30 2x6 24:12s12p 18@1", 4), mode,
				new SongBuilder.BuildLimits(4, 16, 3), SongBuilder.WalkStart.HEAD, "Song of Storms");

			Set<BlockPos> claimed = new java.util.HashSet<>();
			List<BlockPos> notes = new java.util.ArrayList<>();
			for (String command : plan.commands()) {
				CommandPasteSender.Placement placement = CommandPasteSender.read(command);
				claimed.add(placement.at());
				if (placement.block().equals("minecraft:note_block")) {
					notes.add(placement.at());
				}
			}

			assertNotEquals(0, notes.size(), mode + " built no note blocks to check");
			List<String> unclaimed = notes.stream()
				.filter(at -> !claimed.contains(at.above()))
				.map(at -> at.getX() + " " + at.getY() + " " + at.getZ())
				.limit(5)
				.toList();
			assertEquals(List.of(), unclaimed, mode + ": these note blocks leave the cell above them "
				+ "unclaimed, so nothing would collide with a block placed there");
		}
	}

	/** Every block read off a build is one the game knows, or the check would fail on every cell. */
	@Test
	void readsBlockIdsTheGameActuallyHas() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes("5x4 30 2x6 24", 4), SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(4, 16, 3), SongBuilder.WalkStart.HEAD, "Song of Storms");

		Set<String> known = BuiltInRegistries.BLOCK.keySet().stream()
			.map(Object::toString)
			.collect(Collectors.toSet());
		List<String> unknown = plan.commands().stream()
			.map(command -> CommandPasteSender.read(command).block())
			.distinct()
			.filter(block -> !known.contains(block))
			.toList();

		assertEquals(List.of(), unknown, "the read-back compares these against what the world "
			+ "holds, so a name the registry does not have would report every one of those cells "
			+ "as dropped and paste it again forever");
	}
}
