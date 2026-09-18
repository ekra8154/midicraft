package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.midicraft.client.composer.ComposerProject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * That Song info's duration is what the build really plays, from the button press to the last note.
 *
 * <p>{@link SongBuilder#gameTicksFromPress} is arithmetic on the event walk plus a fixed start-up
 * delay, and the delay is a property of whatever the build lays in front of its first note. So this
 * builds the song, puts a marker note block beside the starter button -- it fires on the press, so
 * the reader's clock starts there -- and reads when the last note sounds. Change the head or the
 * shared input and this is where it shows.</p>
 */
class PressTimingTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void oneMachineIsTimedFromThePress() throws Exception {
		check("illit-do-the-dance", 16, 2);
	}

	@Test
	void twoMachinesAreTimedFromThePress() throws Exception {
		check("a-dark-zone-2-lanes", 16, 2);
		check("on-the-floor-2-lanes", 8, 1);
	}

	private static void check(String name, int width, int floors) throws Exception {
		Assumptions.assumeTrue(BreachView.inLibrary(name), name + " is not in the library");
		ComposerProject song = GameSettings.project(BreachView.songFile(name));
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			SongBuilder.notesFor(mode, song.toSequenceTracks(Set.of(), true), song, true), mode,
			new SongBuilder.BuildLimits(16, width, floors));

		Map<BlockPos, BlockState> world = new HashMap<>();
		BlockPos button = null;
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			BlockPos at = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3]));
			world.put(at, BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, parts[4], false)
				.blockState());
			if (parts[4].contains("_button")) {
				button = at;
			}
		}
		assertNotNull(button, name + " was built with no starter button");
		boolean marked = false;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos marker = button.relative(side);
			if (!world.containsKey(marker) && !world.containsKey(marker.above())
					&& !world.containsKey(marker.below())) {
				world.put(marker, Blocks.NOTE_BLOCK.defaultBlockState());
				world.put(marker.below(), Blocks.STONE.defaultBlockState());
				marked = true;
				break;
			}
		}
		Assumptions.assumeTrue(marked, "no free cell beside the button for the marker");

		BlockPos low = new BlockPos(
			world.keySet().stream().mapToInt(BlockPos::getX).min().orElse(0) - 1,
			world.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0) - 1,
			world.keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0) - 1);
		BlockPos high = new BlockPos(
			world.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0) + 1,
			world.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0) + 1,
			world.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1);
		NoteMachineReader.Reading reading = NoteMachineReader.read(name, low, high,
			at -> world.getOrDefault(at, Blocks.AIR.defaultBlockState()));
		assertEquals(0, reading.unreachedNotes(), name + " did not play through");
		long lastTick = reading.project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.mapToLong(ComposerProject.NoteEvent::startTick).max().orElse(0L);
		int heard = (int)(lastTick / NoteMachineReader.TICKS_PER_GAME_TICK);

		List<SongBuilder.EventNote> walked = SongBuilder.gameTickEventNotes(song, true);
		assertEquals(heard, SongBuilder.gameTicksFromPress(walked),
			name + " at " + width + "x" + floors + ": Song info's duration is not what the build plays");
	}
}
