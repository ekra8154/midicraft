package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The thing that starts the machine, which v2 now builds for you.
 *
 * <p>The head of a build is a repeater with nothing behind it, and the column behind it has always
 * been reserved -- one of the three blocks of the width the paste holds back. It was left empty for
 * the player to fill, which meant finding the head of a lane that has folded eleven times before
 * anything would play.</p>
 */
class StarterButtonTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A plain rising line, enough events to make a lane and fold it at least once. */
	private static List<SongBuilder.EventNote> scale(int events) {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		for (int index = 0; index < events; index++) {
			notes.add(new SongBuilder.EventNote(index * 2, 1, index, index % 25,
				"minecraft:grass_block"));
		}
		return notes;
	}

	private static SongBuilder.PastePlan build() {
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), scale(120),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2, new SongBuilder.BuildLimits(4, 16, 4));
	}

	/** Where the commands put a block, keyed by the position in the command. */
	private static Map<String, String> placed(SongBuilder.PastePlan plan) {
		Map<String, String> byPosition = new java.util.LinkedHashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.trim().split("\\s+");
			if (parts.length >= 5 && "setblock".equals(parts[0])) {
				byPosition.put(parts[1] + " " + parts[2] + " " + parts[3], parts[4]);
			}
		}
		return byPosition;
	}

	/**
	 * A build brings a button and something to stand it on.
	 *
	 * <p>The button is in the repeater's input cell at the repeater's own height, and the block it
	 * stands on is one level below that -- so the starter is inside the height the machine already
	 * occupies rather than a block above it.</p>
	 */
	@Test
	void aBuildComesWithSomethingToPressOnIt() {
		Map<String, String> blocks = placed(build());

		List<String> buttons = blocks.entrySet().stream()
			.filter(cell -> cell.getValue().startsWith("minecraft:oak_button"))
			.map(Map.Entry::getKey)
			.toList();
		assertEquals(1, buttons.size(), "one button, at the head and nowhere else: " + buttons);

		String[] at = buttons.getFirst().split(" ");
		String below = at[0] + " " + (Integer.parseInt(at[1]) - 1) + " " + at[2];
		assertEquals("minecraft:stone", blocks.get(below),
			"the button stands on the stone it powers");
		assertTrue(blocks.get(buttons.getFirst()).contains("face=floor"),
			"on the top face, which is the face that powers the block underneath");
	}

	/**
	 * The button is level with the head repeater and directly behind it.
	 *
	 * <p>That is the cell a repeater reads, so pressing the button drives it with nothing in
	 * between. Found through the button rather than by looking for stone: a build is full of stone
	 * for other reasons -- it is what a wire stands on -- so the button is the only cell that names
	 * itself, and every other cell of the starter is found by stepping off it.</p>
	 */
	@Test
	void theButtonIsLevelWithTheHeadRepeater() {
		Map<String, String> blocks = placed(build());
		String button = blocks.entrySet().stream()
			.filter(cell -> cell.getValue().startsWith("minecraft:oak_button"))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(null);
		assertNotNull(button, "the build laid a button");

		String[] at = button.split(" ");
		int x = Integer.parseInt(at[0]);
		int y = Integer.parseInt(at[1]);
		int z = Integer.parseInt(at[2]);
		assertEquals("minecraft:stone", blocks.get(x + " " + (y - 1) + " " + z),
			"and something to stand on, one level down");

		boolean besideARepeater = false;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			String beside = blocks.get((x + side.getStepX()) + " " + y + " " + (z + side.getStepZ()));
			besideARepeater |= beside != null && beside.startsWith("minecraft:repeater");
		}
		assertTrue(besideARepeater,
			"the button is in the repeater's own input cell, at " + x + " " + y + " " + z);
	}

	/**
	 * Turned off, a v2 build is the build it was before any of this: two blocks fewer and no button.
	 *
	 * <p>Counted rather than looked for, because stone is not the starter's alone -- what the switch
	 * takes out is exactly the two cells it put in.</p>
	 */
	@Test
	void theSwitchTakesItAllBackOut() {
		int withStarter = build().commands().size();
		SongBuilder.V2_BUILDS_ITS_OWN_STARTER = false;
		try {
			Map<String, String> blocks = placed(build());
			assertTrue(blocks.values().stream()
					.noneMatch(block -> block.startsWith("minecraft:oak_button")),
				"no button");
			assertEquals(withStarter - 2, build().commands().size(),
				"and the stone it stood on went with it");
		} finally {
			SongBuilder.V2_BUILDS_ITS_OWN_STARTER = true;
		}
	}

	/** What the two blocks cost the build in room, which the reserved column says should be nothing. */
	@Test
	void theStarterCostsTheBuildNoRoom() {
		SongBuilder.PastePlan with = build();
		SongBuilder.V2_BUILDS_ITS_OWN_STARTER = false;
		SongBuilder.PastePlan without;
		try {
			without = build();
		} finally {
			SongBuilder.V2_BUILDS_ITS_OWN_STARTER = true;
		}
		System.out.println("with    w=" + with.width() + " d=" + with.depth() + " h=" + with.height()
			+ " spanX=" + with.spanX() + " spanZ=" + with.spanZ()
			+ " built=" + with.builtWidth() + " cmds=" + with.commands().size());
		System.out.println("without w=" + without.width() + " d=" + without.depth() + " h="
			+ without.height() + " spanX=" + without.spanX() + " spanZ=" + without.spanZ()
			+ " built=" + without.builtWidth() + " cmds=" + without.commands().size());
		assertEquals(without.width(), with.width(), "width");
		assertEquals(without.depth(), with.depth(), "depth");
		assertEquals(without.height(), with.height(), "height");
	}

	/**
	 * The starter is sent first, and what holds it up is sent before it.
	 *
	 * <p>A paste is a stream of setblocks paced over seconds or minutes, so the end of the queue is
	 * a long wait for the one block the player is actually waiting for. The starter is worked out
	 * last -- the head of the machine is not known until there is a machine -- and sent first,
	 * which are separate questions and now have separate answers.</p>
	 *
	 * <p>The order within the pair is the part that would break rather than merely disappoint: a
	 * button is placed against the block under it and pops as an item the moment that block is
	 * missing, so a button sent ahead of its stand is a starter that arrives as a dropped item and
	 * a machine with nothing to press. Asserted as indices rather than as "somewhere near the
	 * front", because the failure this guards is the two swapping places.</p>
	 */
	@Test
	void theStarterLeadsTheQueue() {
		List<String> commands = build().commands();
		String stand = commands.get(0).split("\\s+")[4];
		String button = commands.get(1).split("\\s+")[4];
		assertEquals("minecraft:stone", stand, "the stand is the first command: " + commands.get(0));
		assertTrue(button.startsWith("minecraft:oak_button"),
			"the button is the second: " + commands.get(1));

		String[] standAt = commands.get(0).split("\\s+");
		String[] buttonAt = commands.get(1).split("\\s+");
		assertEquals(standAt[1], buttonAt[1], "same column, x");
		assertEquals(standAt[3], buttonAt[3], "same column, z");
		assertEquals(Integer.parseInt(standAt[2]) + 1, Integer.parseInt(buttonAt[2]),
			"and the button directly on top of it");

		// Moved rather than copied. The cell is written once however it is ordered -- a setblock is
		// a setblock and first-writer-wins upstream of this -- so a starter that led the queue and
		// also stayed at the end would be a second command for a cell that already has one.
		assertEquals(1, commands.stream()
				.filter(command -> command.contains("minecraft:oak_button")).count(),
			"the button is sent once");
	}

	/** The starter is v2's. The first layout is not being changed. */
	@Test
	void theOtherLaneModeIsUntouched() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), scale(120),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 16, 4));
		assertTrue(placed(plan).values().stream()
				.noneMatch(block -> block.startsWith("minecraft:oak_button")),
			"the first layout builds what it always built");
	}
}
