package com.midicraft.client.compat;

import java.util.Locale;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks a paste is built from where the machine does not care which block it is.
 *
 * <p>The planner lays stone, glass and top slabs and never anything else in those roles, so the
 * player's choice is swapped in on the way out, in {@code marked}, and the walk never sees it.
 * That is what keeps a custom block from changing the machine: every rule the walk applies --
 * which cells collide, which carry power -- is decided against the defaults it was written for.</p>
 *
 * <p>Which is also why each role is checked here against the one property the machine does need
 * from it. A relay block carries pulses, so it has to conduct. A lane block only holds up dust and
 * repeaters, so it needs a solid top and may be glass or a slab. A climb has dust running up over
 * it and must not conduct, or the diagonal is cut and the wire powers what stands beside it. A
 * support holds up dust and repeaters, so it needs a solid top. A thin spacing block holds up the
 * dust on a lane packed two from the next, where every cell beside the wire is the next lane's
 * note, so it must not conduct either.</p>
 */
public final class LaneMaterials {
	private LaneMaterials() {
	}

	public enum Role {
		LANE("minecraft:stone"),
		RELAY("minecraft:stone"),
		TRANSPARENT("minecraft:glass"),
		SUPPORT("minecraft:stone_slab"),
		THIN("minecraft:smooth_stone_slab");

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
	 * <p>Judged on exactly the state that will be pasted -- what {@link #placed} makes of it, parsed
	 * the way {@code /setblock} parses it -- so a slab is judged as the top half it is laid as, and a
	 * slab typed as {@code [type=bottom]} is judged as the bottom half it asked for. Asked of the
	 * registry, so only once the game is up.</p>
	 *
	 * <p>The support rules are the game's own: dust stays on a block with a sturdy top, or on a
	 * hopper ({@code RedStoneWireBlock.canSurviveOn}), and a repeater on one whose top is sturdy for
	 * a rigid block ({@code DiodeBlock.canSurviveOn}).</p>
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
		BlockState state;
		try {
			state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, placed(role, normalised),
				false).blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException wrongState) {
			return "Not a state that block has";
		}
		if (state.isAir()) {
			return "Air holds nothing up";
		}
		if (state.getBlock() instanceof FallingBlock) {
			return "Falls when anything under it is gone";
		}
		boolean conducts = state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		boolean holdsDust = state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)
			|| state.is(Blocks.HOPPER);
		boolean holdsRepeaters = state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
			Direction.UP, SupportType.RIGID);
		return switch (role) {
			case LANE -> holdsDust && holdsRepeaters ? null
				: "Redstone dust and repeaters cannot stand on top of it";
			case RELAY -> !conducts
				? "Does not conduct redstone, so the chords that relay through it go silent"
				: !holdsDust || !holdsRepeaters ? "Redstone cannot stand on top of it" : null;
			case TRANSPARENT -> conducts
				? "Conducts redstone, which cuts the wire climbing over it"
				: holdsDust ? null : "Redstone dust cannot sit on top of it";
			case SUPPORT -> holdsDust && holdsRepeaters ? null
				: "Redstone dust and repeaters cannot stand on top of it";
			case THIN -> conducts
				? "Conducts redstone, which sounds the next lane's notes off the dust on it"
				: holdsDust ? null : "Redstone dust cannot sit on top of it";
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
	 * What actually goes into the setblock for a role: a slab laid as its top half.
	 *
	 * <p>In every role, because in every role something stands on it. A bottom slab has no top face
	 * for dust or a repeater, and the game's default slab is the bottom one, so a slab named without
	 * a {@code type} is given the top. One named with a type keeps it, and {@link #problem} judges
	 * it as that. Read off the name rather than the registry, so it can run before the game is up;
	 * a slab whose name does not end in {@code _slab} is left as the bottom half, and refused.</p>
	 */
	public static String placed(Role role, String value) {
		int bracket = value.indexOf('[');
		String id = bracket < 0 ? value : value.substring(0, bracket);
		if (!id.endsWith("_slab") || value.contains("type=")) {
			return value;
		}
		if (bracket < 0) {
			return value + "[type=top]";
		}
		String state = value.substring(bracket + 1, value.length() - 1);
		return id + "[" + (state.isEmpty() ? "" : state + ",") + "type=top]";
	}
}
