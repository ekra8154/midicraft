package com.midicraft.client.compat;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * The blocks a paste is built from where the machine does not care which block it is.
 *
 * <p>The planner lays stone, glass and top slabs and never anything else in those roles, so the
 * player's choice is swapped in on the way out, in {@code marked}, and the walk never sees it.
 * That is what keeps a custom block from changing the machine: every rule the walk applies --
 * which cells collide, which carry power -- is decided against the defaults it was written for.</p>
 *
 * <p>Which is also why each role is checked here against the one property the machine does need
 * from it. A lane block relays pulses, so it has to conduct. A climb has dust running up over it
 * and must not, or the diagonal is cut and the wire powers what stands beside it. A support
 * holds up dust and repeaters, so it needs a solid top.</p>
 */
public final class LaneMaterials {
	private LaneMaterials() {
	}

	public enum Role {
		LANE("minecraft:stone"),
		TRANSPARENT("minecraft:glass"),
		SUPPORT("minecraft:stone_slab");

		private final String fallback;

		Role(String fallback) {
			this.fallback = fallback;
		}

		public String fallback() {
			return fallback;
		}
	}

	/**
	 * What was typed, as a block id: spaces gone, lower case, {@code minecraft:} where no namespace
	 * was given. A state in brackets is kept as written.
	 *
	 * @return {@code null} for something that cannot be a block id at all
	 */
	public static String normalise(String typed) {
		if (typed == null) {
			return null;
		}
		String squeezed = typed.replaceAll("\\s+", "");
		int bracket = squeezed.indexOf('[');
		String id = (bracket < 0 ? squeezed : squeezed.substring(0, bracket)).toLowerCase(Locale.ROOT);
		String state = bracket < 0 ? "" : squeezed.substring(bracket);
		if (id.isEmpty()) {
			return null;
		}
		if (id.indexOf(':') < 0) {
			id = "minecraft:" + id;
		}
		if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
				|| !state.isEmpty() && !state.matches("\\[[a-z0-9_=,]*]")) {
			return null;
		}
		return id + state;
	}

	/**
	 * Why a block will not do for a role, or {@code null} when it will.
	 *
	 * <p>Asked of the registry, so only once the game is up. Judged on the block's default state --
	 * a slab as its top half, since that is how a support is laid -- whatever state was typed.</p>
	 */
	public static String problem(Role role, String normalised) {
		if (normalised == null) {
			return "Not a block id";
		}
		int bracket = normalised.indexOf('[');
		Identifier id = Identifier.tryParse(bracket < 0 ? normalised : normalised.substring(0, bracket));
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return "No such block";
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		BlockState state = block.defaultBlockState();
		if (block instanceof SlabBlock) {
			state = state.setValue(SlabBlock.TYPE, SlabType.TOP);
		}
		if (state.isAir()) {
			return "Air holds nothing up";
		}
		if (block instanceof FallingBlock) {
			return "Falls when anything under it is gone";
		}
		boolean conducts = state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		boolean solidTop = state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
		return switch (role) {
			case LANE -> conducts ? null
				: "Does not conduct redstone, so the chords that relay through it go silent";
			case TRANSPARENT -> conducts
				? "Conducts redstone, which cuts the wire climbing over it"
				: solidTop ? null : "Dust cannot sit on top of it";
			case SUPPORT -> solidTop ? null : "Has no solid top for dust and repeaters to stand on";
		};
	}

	/**
	 * The value to keep: what was typed if it will do, and the role's default if it will not.
	 *
	 * <p>For a config file, which may have been edited by hand. Where the registry cannot be asked
	 * -- a test that never started the game -- anything that reads as a block id is kept.</p>
	 */
	public static String accepted(Role role, String typed) {
		String normalised = normalise(typed);
		if (normalised == null) {
			return role.fallback();
		}
		try {
			return problem(role, normalised) == null ? normalised : role.fallback();
		} catch (RuntimeException | LinkageError notBootstrapped) {
			return normalised;
		}
	}

	/**
	 * What actually goes into the setblock for a role: a support slab laid as its top half.
	 *
	 * <p>A bottom slab has no top face to stand a repeater on, and a slab is what the default is,
	 * so a slab named without a state is given the one the default carries.</p>
	 */
	public static String placed(Role role, String value) {
		if (role == Role.SUPPORT && value.indexOf('[') < 0 && value.endsWith("_slab")) {
			return value + "[type=top]";
		}
		return value;
	}
}
