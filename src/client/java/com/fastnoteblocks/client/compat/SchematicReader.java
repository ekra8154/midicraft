package com.fastnoteblocks.client.compat;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Loads the block formats people actually share note block builds in.
 *
 * <p>Three of them, all NBT underneath and all differing only in how the blocks are indexed:
 * the vanilla structure block {@code .nbt}, which is what Note Block Studio itself exports;
 * Sponge {@code .schem}, which is what WorldEdit writes; and Litematica's {@code .litematic},
 * which is what the nbs-to-schematic converters produce.</p>
 *
 * <p>Deliberately not the old MCEdit {@code .schematic}. That one stores numeric block ids from
 * before the 1.13 flattening, so reading it means carrying a translation table for a version of the
 * game this mod cannot otherwise talk about -- and getting one entry of that table wrong is a
 * silently mistuned song rather than an error.</p>
 */
final class SchematicReader {
	private SchematicReader() {
	}

	/** The file extensions this can open, in the order a file browser should offer them. */
	static final List<String> EXTENSIONS = List.of(".nbt", ".schem", ".litematic");

	/** A loaded schematic: its blocks, and how big the box holding them is. */
	record Schematic(Map<BlockPos, BlockState> blocks, BlockPos size, String format) {
		BlockState at(BlockPos position) {
			return blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
		}
	}

	static Schematic load(Path path) throws IOException {
		CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		// By content rather than by extension. People rename these constantly, and a .schem that is
		// really a structure file is far commoner than a reader that says so.
		if (root.getList("palette").isPresent() || root.getList("blocks").isPresent()) {
			return structure(root);
		}
		if (root.getCompound("Schematic").isPresent()) {
			return sponge(root.getCompoundOrEmpty("Schematic"), 3);
		}
		if (root.getByteArray("BlockData").isPresent()) {
			return sponge(root, 2);
		}
		if (root.getCompound("Regions").isPresent()) {
			return litematic(root);
		}
		throw new NoteMachineReader.UnreadableException("Could not tell what kind of schematic "
			+ name + " is. Structure (.nbt), Sponge (.schem) and Litematica (.litematic) files can "
			+ "be read; the old MCEdit .schematic, which stores numbered blocks from before 1.13, "
			+ "cannot.");
	}

	// ------------------------------------------------------------------ vanilla structure block

	/**
	 * The structure block format, which lists the blocks it contains one by one.
	 *
	 * <p>The only one of the three that stores positions rather than a dense grid, so it is also
	 * the only one where air is simply absent instead of being an entry in the palette.</p>
	 */
	private static Schematic structure(CompoundTag root) {
		List<BlockState> palette = new ArrayList<>();
		// Some writers store several palettes, one per variant of the structure. The first is the
		// one a structure block would place.
		ListTag paletteTag = root.getList("palette").orElseGet(
			() -> root.getListOrEmpty("palettes").getListOrEmpty(0));
		for (int index = 0; index < paletteTag.size(); index++) {
			palette.add(fromNameAndProperties(paletteTag.getCompoundOrEmpty(index)));
		}
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		ListTag blocksTag = root.getListOrEmpty("blocks");
		for (int index = 0; index < blocksTag.size(); index++) {
			CompoundTag entry = blocksTag.getCompoundOrEmpty(index);
			ListTag position = entry.getListOrEmpty("pos");
			int state = entry.getIntOr("state", 0);
			if (position.size() == 3 && state >= 0 && state < palette.size()) {
				blocks.put(new BlockPos(position.getIntOr(0, 0), position.getIntOr(1, 0),
					position.getIntOr(2, 0)), palette.get(state));
			}
		}
		ListTag size = root.getListOrEmpty("size");
		return new Schematic(blocks, size.size() == 3
			? new BlockPos(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0))
			: bounds(blocks), "structure");
	}

	// ------------------------------------------------------------------ sponge .schem

	/**
	 * The Sponge format, a dense grid of varint palette indices in y, then z, then x order.
	 *
	 * <p>Version three moved everything down a level into a {@code Schematic} compound and the
	 * blocks into a {@code Blocks} one, which is the whole of the difference that matters here.</p>
	 */
	private static Schematic sponge(CompoundTag root, int version) {
		CompoundTag blocksTag = version >= 3 ? root.getCompoundOrEmpty("Blocks") : root;
		CompoundTag paletteTag = blocksTag.getCompoundOrEmpty("Palette");
		byte[] data = blocksTag.getByteArray(version >= 3 ? "Data" : "BlockData")
			.orElseThrow(() -> new NoteMachineReader.UnreadableException(
				"That .schem has no block data in it."));

		BlockState[] palette = new BlockState[paletteTag.size()];
		for (String key : paletteTag.keySet()) {
			int index = paletteTag.getIntOr(key, -1);
			if (index >= 0 && index < palette.length) {
				palette[index] = parse(key);
			}
		}

		int width = root.getShortOr("Width", (short)0) & 0xFFFF;
		int height = root.getShortOr("Height", (short)0) & 0xFFFF;
		int length = root.getShortOr("Length", (short)0) & 0xFFFF;
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		int cursor = 0;
		for (int index = 0; cursor < data.length; index++) {
			// Varint: seven bits a byte, high bit meaning another byte follows.
			int value = 0;
			int shift = 0;
			byte read;
			do {
				if (cursor >= data.length) {
					break;
				}
				read = data[cursor++];
				value |= (read & 0x7F) << shift;
				shift += 7;
			} while ((read & 0x80) != 0);
			if (width <= 0 || length <= 0) {
				break;
			}
			BlockState state = value >= 0 && value < palette.length ? palette[value] : null;
			if (state != null && !state.isAir()) {
				blocks.put(new BlockPos(index % width, index / (width * length),
					index / width % length), state);
			}
		}
		return new Schematic(blocks, new BlockPos(width, height, length), "sponge v" + version);
	}

	// ------------------------------------------------------------------ litematica

	/**
	 * Litematica's format, whose block indices are bit-packed into an array of longs.
	 *
	 * <p>Unlike the game's own chunk storage, an entry here is allowed to straddle the boundary
	 * between two longs -- the bits simply run on. Reading it as if entries were padded to fit
	 * would come out plausible for a while and then drift, which is the worst way to be wrong.</p>
	 *
	 * <p>Only the first region is read. Multiple regions are a Litematica editing convenience and a
	 * machine split across two of them would need their relative offsets honoured to trace at all;
	 * one region is what every note block export writes.</p>
	 */
	private static Schematic litematic(CompoundTag root) {
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		String first = regions.keySet().stream().findFirst().orElseThrow(
			() -> new NoteMachineReader.UnreadableException("That .litematic has no regions in it."));
		CompoundTag region = regions.getCompoundOrEmpty(first);

		ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int index = 0; index < palette.length; index++) {
			palette[index] = fromNameAndProperties(paletteTag.getCompoundOrEmpty(index));
		}
		long[] packed = region.getLongArray("BlockStates").orElseThrow(
			() -> new NoteMachineReader.UnreadableException("That .litematic has no block data."));

		CompoundTag size = region.getCompoundOrEmpty("Size");
		int width = Math.abs(size.getIntOr("x", 0));
		int height = Math.abs(size.getIntOr("y", 0));
		int length = Math.abs(size.getIntOr("z", 0));
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
		long mask = (1L << bits) - 1L;

		Map<BlockPos, BlockState> blocks = new HashMap<>();
		long total = (long)width * height * length;
		for (long index = 0; index < total; index++) {
			long startBit = index * bits;
			int startLong = (int)(startBit >> 6);
			int endLong = (int)(((index + 1) * bits - 1) >> 6);
			int offset = (int)(startBit & 63L);
			if (startLong >= packed.length) {
				break;
			}
			long value = startLong == endLong
				? packed[startLong] >>> offset & mask
				: (packed[startLong] >>> offset | packed[endLong] << (64 - offset)) & mask;
			BlockState state = value >= 0 && value < palette.length ? palette[(int)value] : null;
			if (state != null && !state.isAir()) {
				int y = (int)(index / ((long)width * length));
				int remainder = (int)(index % ((long)width * length));
				blocks.put(new BlockPos(remainder % width, y, remainder / width), state);
			}
		}
		return new Schematic(blocks, new BlockPos(width, height, length), "litematica");
	}

	// ------------------------------------------------------------------ shared

	/** Builds a block state from the {@code {Name, Properties}} pair both NBT formats use. */
	private static BlockState fromNameAndProperties(CompoundTag entry) {
		String name = entry.getStringOr("Name", "minecraft:air");
		CompoundTag properties = entry.getCompoundOrEmpty("Properties");
		if (properties.isEmpty()) {
			return parse(name);
		}
		StringBuilder text = new StringBuilder(name).append('[');
		boolean firstProperty = true;
		for (Map.Entry<String, Tag> property : properties.entrySet()) {
			if (!firstProperty) {
				text.append(',');
			}
			firstProperty = false;
			text.append(property.getKey()).append('=')
				.append(properties.getStringOr(property.getKey(), ""));
		}
		return parse(text.append(']').toString());
	}

	/**
	 * A block state from its text, or air.
	 *
	 * <p>Air rather than a failure, because a schematic made on a modded or newer server will name
	 * blocks this game has never heard of. None of them can be part of the redstone being traced,
	 * and refusing the whole file over a decorative block would be the wrong trade.</p>
	 */
	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (CommandSyntaxException | RuntimeException unknown) {
			return Blocks.AIR.defaultBlockState();
		}
	}

	private static BlockPos bounds(Map<BlockPos, BlockState> blocks) {
		int maxX = 0;
		int maxY = 0;
		int maxZ = 0;
		for (BlockPos position : blocks.keySet()) {
			maxX = Math.max(maxX, position.getX());
			maxY = Math.max(maxY, position.getY());
			maxZ = Math.max(maxZ, position.getZ());
		}
		return new BlockPos(maxX + 1, maxY + 1, maxZ + 1);
	}
}
