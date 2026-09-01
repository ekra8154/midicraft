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
 * The mirrored walk is the plain walk reflected, cell for cell.
 *
 * <p>The handedness gate. The interleaved paste's second machine is the first one mirrored --
 * lanes running the other way, slab creeping the same way -- and a reflection is not a rotation:
 * the rotation gate cannot see a handedness bug, because turning a build never flips which side of
 * forward its depth is on. So this asserts the reflection directly: a mirrored east walk must
 * equal the plain east walk flipped across the lane axis, {@code dz -> -dz} about the origin, with
 * every north and south facing swapped and east and west left alone.</p>
 *
 * <p>Frugal for the same reason the rotation gate is: two plans and two long lists do not fit the
 * test JVM's heap together, so only walk-space command lists are held.</p>
 */
class RouteMirrorIdentityTest {
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

	private static LaneRoute mirrored(int floors, SongBuilder.WalkStart start) {
		LaneRoute base = LaneRoute.serpentine(floors, start.floor(), start.climb());
		return new LaneRoute() {
			@Override
			public int floorOf(int leg) {
				return base.floorOf(leg);
			}

			@Override
			public int climbOf(int leg) {
				return base.climbOf(leg);
			}

			@Override
			public boolean mirrored() {
				return true;
			}
		};
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

	/** One walk-space command reflected across the lane axis through the origin. */
	private static String reflected(String command) {
		String[] token = command.split(" ", 5);
		int dz = Integer.parseInt(token[3]) - ORIGIN.getZ();
		String rest = token[4]
			.replace("=south", "=@1@").replace("=north", "=@2@")
			.replace("@1@", "north").replace("@2@", "south");
		return "setblock " + token[1] + " " + token[2] + " " + (ORIGIN.getZ() - dz) + " " + rest;
	}

	@Test
	void theMirroredWalkIsTheReflection() throws Exception {
		List<SongBuilder.EventNote> notes = notes();
		int[][] sizes = {{40, 3}, {24, 3}, {20, 5}, {16, 1}};
		for (int[] size : sizes) {
			int width = size[0];
			int floors = size[1];
			for (SongBuilder.WalkStart start : List.of(SongBuilder.WalkStart.HEAD,
					new SongBuilder.WalkStart(0, floors - 1, -1))) {
				List<String> plain = walkSpace(SongBuilder.createRoutedPastePlan(ORIGIN,
					Direction.EAST, notes, width, floors, start));
				assertTrue(plain.size() > 1000, "suspiciously small build: " + plain.size());
				List<String> mirror = walkSpace(SongBuilder.createRoutedPastePlan(ORIGIN,
					Direction.EAST, notes, width, floors, start, mirrored(floors, start)));
				String at = width + "x" + floors + " from " + start;
				assertEquals(plain.size(), mirror.size(), at);
				List<String> diverged = new ArrayList<>();
				for (int line = 0; line < plain.size() && diverged.size() < 3; line++) {
					String predicted = reflected(plain.get(line));
					if (!predicted.equals(mirror.get(line))) {
						diverged.add(at + " line " + line + ": predicted " + predicted
							+ " but built " + mirror.get(line));
					}
				}
				assertEquals(List.of(), diverged);
			}
		}
	}
}
