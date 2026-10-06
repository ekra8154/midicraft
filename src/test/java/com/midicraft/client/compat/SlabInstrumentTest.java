package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;



import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * That a block swapped in for another one still sounds like it.
 *
 * <p>Every substitution in the builder is a bet on what the game thinks a block is, and the game is
 * the only thing that can settle it. A note block reads its instrument off the block underneath, so
 * a slab put there to make a floor walkable, or a waxed copper block put there to stop it ageing,
 * has to answer {@code instrument()} exactly as the block it replaces did -- and "a slab of the same
 * material obviously does" is precisely the kind of thing this file has been wrong about before.</p>
 *
 * <p>Asked of the registry rather than reasoned from tags, and asserted rather than printed, so a
 * version that moves an instrument says so here instead of in a build nobody can hear.</p>
 */
class SlabInstrumentTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Parsed the same way the paste itself parses one, so this is the block a build would get. */
	private static BlockState state(String id) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, id, false).blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unknown) {
			throw new AssertionError("no such block: " + id, unknown);
		}
	}

	/**
	 * The map the builder actually reads, rather than a copy of it written out here.
	 *
	 * <p>The list below is the history of every substitution this file has made; this is the one
	 * that is live. A slab added to {@link SongBuilder#INSTRUMENT_SLABS} and not to the list would
	 * otherwise go into every build in the library untested, and a slab that plays a different
	 * instrument is a whole line of a song retuned by a floor.</p>
	 */
	@Test
	void everySlabTheBuilderLaysSoundsLikeTheBlockItStandsIn() {
		assertFalse(SongBuilder.INSTRUMENT_SLABS.isEmpty(), "the builder lays no slabs at all, "
			+ "which means this test is asserting nothing");
		SongBuilder.INSTRUMENT_SLABS.forEach((block, slab) -> {
			System.out.println("LAID " + block + " " + state(block).instrument()
				+ "  ->  " + slab + " " + state(slab).instrument());
			assertEquals(state(block).instrument(), state(slab).instrument(),
				slab + " does not sound like " + block);
			// The whole reason a half-block is safe under a note and not under a relay. If one ever
			// conducts, the rule that keeps them off the wire has quietly stopped being needed --
			// or, far more likely, something else has changed and this is the warning.
			assertFalse(state(slab).isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
				slab + " conducts redstone, so it is not the inert floor the builder takes it for");
		});
	}

	@Test
	void everySubstituteSoundsLikeWhatItReplaces() {
		String[][] substitutions = {
			// The low slots, where an instrument block goes on the half-block so a floor can be walked
			// between and seen through. In the state the builder actually lays it: a bottom slab under
			// a note block leaves the note hanging a half-block clear of it, which is not a floor.
			{"minecraft:oak_planks", "minecraft:oak_slab[type=top]"},
			{"minecraft:stone", "minecraft:stone_slab[type=top]"},
			{"minecraft:white_wool", "minecraft:white_wool_slab[type=top]"},
			{"minecraft:copper_block", "minecraft:waxed_cut_copper_slab[type=top]"},
			{"minecraft:exposed_copper", "minecraft:waxed_exposed_cut_copper_slab[type=top]"},
			{"minecraft:weathered_copper", "minecraft:waxed_weathered_cut_copper_slab[type=top]"},
			{"minecraft:oxidized_copper", "minecraft:waxed_oxidized_cut_copper_slab[type=top]"},
			// And the copper that stays a full block, waxed so a build does not retune itself over the
			// weeks it stands there.
			{"minecraft:copper_block", "minecraft:waxed_copper_block"},
			{"minecraft:exposed_copper", "minecraft:waxed_exposed_copper"},
			{"minecraft:weathered_copper", "minecraft:waxed_weathered_copper"},
			{"minecraft:oxidized_copper", "minecraft:waxed_oxidized_copper"},
		};
		for (String[] pair : substitutions) {
			System.out.println("INSTRUMENT " + pair[0] + " " + state(pair[0]).instrument()
				+ "  ->  " + pair[1] + " " + state(pair[1]).instrument());
			assertEquals(state(pair[0]).instrument(), state(pair[1]).instrument(),
				pair[1] + " does not sound like " + pair[0]);
		}
	}
}
