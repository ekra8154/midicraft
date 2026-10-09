package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.midicraft.client.composer.ComposerProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A new palette put on a finished plan must be the plan that palette would have made.
 *
 * <p>That is the whole claim {@code PastePlan.withPalette} rests on -- the walk never depends on the
 * blocks -- so it is checked the only way that proves it: plan once in one palette and recolour, plan
 * again from scratch in the other, and compare every line.</p>
 */
class PaletteRecolourTest {
	/** The builder's own palette, read once the game is up: reading it starts the builder. */
	private static List<String> DEFAULTS;

	/** A different block in every slot, each one the screen would accept for it. */
	private static final List<String> OTHER = List.of("minecraft:smooth_stone_slab[type=top]",
		"minecraft:oak_slab[type=top]", "minecraft:iron_block", "minecraft:white_stained_glass",
		"minecraft:andesite_slab[type=top]", "minecraft:tinted_glass");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		DEFAULTS = List.of(SongBuilder.LANE_ONE_BLOCK, SongBuilder.LANE_TWO_BLOCK,
			SongBuilder.RELAY_BLOCK, SongBuilder.TRANSPARENT_BLOCK, SongBuilder.SUPPORT_BLOCK,
			SongBuilder.THIN_LANE_BLOCK);
	}

	@AfterEach
	void putTheDefaultsBack() {
		use(DEFAULTS);
		SongBuilder.LIGHT_SHOW = false;
	}

	private static void use(List<String> palette) {
		SongBuilder.LANE_ONE_BLOCK = palette.get(0);
		SongBuilder.LANE_TWO_BLOCK = palette.get(1);
		SongBuilder.RELAY_BLOCK = palette.get(2);
		SongBuilder.TRANSPARENT_BLOCK = palette.get(3);
		SongBuilder.SUPPORT_BLOCK = palette.get(4);
		SongBuilder.THIN_LANE_BLOCK = palette.get(5);
	}

	private static SongBuilder.PastePlan plan(String id, int width, int floors) throws Exception {
		Path file = TestSongs.SONGS.resolve(TestSongs.fileName(id));
		assumeTrue(Files.exists(file), "song not in the library: " + id);
		GameSettings.Values game = GameSettings.get();
		ComposerProject song = GameSettings.project(file);
		SongBuilder.PasteMode mode = SongBuilder.PasteMode.INTERLEAVED_HALF_TICK;
		return SongBuilder.createPastePlan(new BlockPos(0, 64, 0), game.notes(song, mode), mode,
			game.limits(width, floors), SongBuilder.WalkStart.HEAD, song.name());
	}

	private static void recolouredMatchesReplanned(String id, int width, int floors)
			throws Exception {
		use(DEFAULTS);
		SongBuilder.PastePlan planned = plan(id, width, floors);
		use(OTHER);
		SongBuilder.PastePlan replanned = plan(id, width, floors);
		assertNotEquals(planned.commands(), replanned.commands(), "the palette shows in the build");
		assertEquals(replanned.commands(), planned.withPalette(OTHER).commands());
		// And back again, from the other side.
		assertEquals(planned.commands(), replanned.withPalette(DEFAULTS).commands());
	}

	@Test
	void fireflies() throws Exception {
		recolouredMatchesReplanned("fireflies-owl-city", 32, 1);
	}

	@Test
	void spaceSongOnThreeFloors() throws Exception {
		recolouredMatchesReplanned("space-song-beach-house", 24, 3);
	}

	/** Packed lanes, where the thin slot and its relay exception are used. */
	@Test
	void packedLanes() throws Exception {
		recolouredMatchesReplanned("fireflies-owl-city", 12, 2);
	}

	/** The light show's lamps go in just before the palette, and are never palette cells. */
	@Test
	void lightShow() throws Exception {
		SongBuilder.LIGHT_SHOW = true;
		recolouredMatchesReplanned("space-song-beach-house", 24, 2);
	}
}
