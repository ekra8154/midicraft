package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.midicraft.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The routed walk lays the same build whichever way forward points.
 *
 * <p>The axis-agnostic gate. A song walked south, west or north must be the song walked east
 * turned by quarters -- every cell in the same place relative to the origin, every repeater facing
 * the turned way -- and any site still reasoning in raw {@code getX()}, or blind to the sign of
 * forward, breaks this the moment it is asked. The comparison runs in walk space (the paste slide
 * is undone with {@code LAST_SHIFT_X/Z}); one clockwise quarter maps {@code (dx, dz)} to
 * {@code (-dz, dx)} and turns every facing in the block states with it.</p>
 *
 * <p>The origin is chosen with its x and z of the same parity, because module parity is counted
 * from the along-axis coordinate of the origin: equal parities make the four builds comparable
 * without making the parity term invisible.</p>
 *
 * <p>Deliberately frugal: only the command lists are kept and the divergences are collected a few
 * at a time, because a {@code PastePlan} carries its whole powered map and the test JVM's heap is
 * modest -- holding two plans and two fifty-thousand-line lists at once was a heap death, not a
 * finding.</p>
 */
class RouteRotationIdentityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static final BlockPos ORIGIN = new BlockPos(5, 64, 7);

	private static List<SongBuilder.EventNote> notes() throws Exception {
		try (Reader reader = Files.newBufferedReader(BreachView.songFile("illit-do-the-dance"))) {
			ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
			ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
				raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(), raw.nextNoteId(),
				raw.endTick(), raw.speedQuarters());
			return SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
		}
	}

	/** The plan's commands in walk space, everything else dropped so the plan can be collected. */
	private static List<String> walkSpace(SongBuilder.PastePlan plan) {
		int shiftX = SongBuilder.LAST_SHIFT_X;
		int shiftZ = SongBuilder.LAST_SHIFT_Z;
		List<String> commands = new ArrayList<>(plan.commands().size());
		for (String command : plan.commands()) {
			String[] token = command.split(" ", 5);
			commands.add("setblock " + (Integer.parseInt(token[1]) - shiftX) + " " + token[2]
				+ " " + (Integer.parseInt(token[3]) - shiftZ) + " " + token[4]);
		}
		return commands;
	}

	/** One walk-space command turned clockwise by quarters about the origin. */
	private static String turned(String command, int quarters) {
		String[] token = command.split(" ", 5);
		int dx = Integer.parseInt(token[1]) - ORIGIN.getX();
		int dz = Integer.parseInt(token[3]) - ORIGIN.getZ();
		String rest = token[4];
		for (int turn = 0; turn < quarters; turn++) {
			int turnedX = -dz;
			dz = dx;
			dx = turnedX;
			rest = rest
				.replace("=east", "=@1@").replace("=south", "=@2@")
				.replace("=west", "=@3@").replace("=north", "=@4@")
				.replace("@1@", "south").replace("@2@", "west")
				.replace("@3@", "north").replace("@4@", "east");
		}
		return "setblock " + (ORIGIN.getX() + dx) + " " + token[2] + " "
			+ (ORIGIN.getZ() + dz) + " " + rest;
	}

	@Test
	void everyForwardIsEastTurnedByQuarters() throws Exception {
		List<SongBuilder.EventNote> notes = notes();
		List<Direction> forwards = List.of(Direction.EAST, Direction.SOUTH, Direction.WEST,
			Direction.NORTH);
		int[][] sizes = {{40, 3}, {24, 3}, {20, 5}, {16, 1}};
		for (int[] size : sizes) {
			int width = size[0];
			int floors = size[1];
			for (SongBuilder.WalkStart start : List.of(SongBuilder.WalkStart.HEAD,
					new SongBuilder.WalkStart(0, floors - 1, -1))) {
				List<String> east = walkSpace(SongBuilder.createRoutedPastePlan(ORIGIN,
					Direction.EAST, notes, width, floors, start));
				assertTrue(east.size() > 1000, "suspiciously small build: " + east.size());
				for (int quarters = 1; quarters < forwards.size(); quarters++) {
					List<String> other = walkSpace(SongBuilder.createRoutedPastePlan(ORIGIN,
						forwards.get(quarters), notes, width, floors, start));
					String at = width + "x" + floors + " from " + start + " facing "
						+ forwards.get(quarters);
					assertEquals(east.size(), other.size(), at);
					List<String> diverged = new ArrayList<>();
					for (int line = 0; line < east.size() && diverged.size() < 3; line++) {
						String predicted = turned(east.get(line), quarters);
						if (!predicted.equals(other.get(line))) {
							diverged.add(at + " line " + line + ": predicted " + predicted
								+ " but built " + other.get(line));
						}
					}
					assertEquals(List.of(), diverged);
				}
			}
		}
	}
}
