package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;



import net.minecraft.SharedConstants;
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

	@Test
	void everySubstituteSoundsLikeWhatItReplaces() {
		String[][] substitutions = {
			// The low slots, where an instrument block goes on the half-block so a floor can be walked
			// between and seen through. In the state the builder actually lays it: a bottom slab under
			// a note block leaves the note hanging a half-block clear of it, which is not a floor.
			{"minecraft:oak_planks", "minecraft:oak_slab[type=top]"},
			{"minecraft:stone", "minecraft:stone_slab[type=top]"},
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
