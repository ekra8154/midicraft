package com.midicraft.client.compat;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Saves a build as a file someone else can paste: Litematica's {@code .litematic} and WorldEdit's
 * Sponge {@code .schem}, the two that people downloading a build actually have installed.
 *
 * <p>Made from the paste's own {@code /setblock} commands rather than from the walk, so the file is
 * the build the Paste button would have placed, block for block -- materials, light show and title
 * sign included. A second route from the walk to a file would be a second build to keep in step,
 * and the two drifting apart is the one way an exported song could play differently from a pasted
 * one.</p>
 *
 * <p>The inverse of {@link SchematicReader}, which is what the tests hold it to.</p>
 */
final class SchematicWriter {
	private SchematicWriter() {
	}

	/** The two files a build can be saved as, and the mod each one is for. */
	enum Format {
		LITEMATIC(".litematic", "Litematica"),
		SCHEM(".schem", "WorldEdit");

		private final String extension;
		private final String reader;

		Format(String extension, String reader) {
			this.extension = extension;
			this.reader = reader;
		}

		String extension() {
			return extension;
		}

		/** The mod that loads this kind of file, for saying where it went. */
		String reader() {
			return reader;
		}
	}

	/**
	 * Writes a build into a folder under a name nothing there has yet, and says where it went.
	 *
	 * <p>Never over an existing file: the folders these go into are the ones Litematica and
	 * WorldEdit load from, and they hold the player's own schematics too. A second export of the same
	 * song at the same size is numbered rather than written over the first.</p>
	 *
	 * @param standingAt where the player stood to plan it; see {@link #writeSponge}
	 */
	static Path save(Build build, Format format, Path folder, String fileName, String title,
			String author, String description, BlockPos standingAt) throws IOException {
		Files.createDirectories(folder);
		Path file = folder.resolve(fileName + format.extension());
		for (int copy = 2; Files.exists(file); copy++) {
			file = folder.resolve(fileName + " (" + copy + ")" + format.extension());
		}
		switch (format) {
			case LITEMATIC -> writeLitematic(build, file, title, author, description);
			case SCHEM -> writeSponge(build, file, title, author, standingAt);
		}
		return file;
	}

	/** Litematica's own format version as of 26.x, which is what its current builds write. */
	private static final int LITEMATIC_VERSION = 7;
	private static final int LITEMATIC_SUB_VERSION = 1;
	/** Sponge v3, what WorldEdit itself writes now; it still reads v2 and v1 as well. */
	private static final int SPONGE_VERSION = 3;

	/**
	 * A build as cells: the state placed at each one, and the block entity data for the few placed
	 * with some (the title sign).
	 *
	 * <p>Air is kept where the paste places it on purpose -- above a note block, which plays only
	 * with air over it -- so the box covers every cell the paste would have touched.</p>
	 */
	record Build(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities) {
		BlockPos min() {
			return corner(true);
		}

		BlockPos max() {
			return corner(false);
		}

		private BlockPos corner(boolean low) {
			int x = low ? Integer.MAX_VALUE : Integer.MIN_VALUE;
			int y = x;
			int z = x;
			for (BlockPos at : blocks.keySet()) {
				x = low ? Math.min(x, at.getX()) : Math.max(x, at.getX());
				y = low ? Math.min(y, at.getY()) : Math.max(y, at.getY());
				z = low ? Math.min(z, at.getZ()) : Math.max(z, at.getZ());
			}
			return new BlockPos(x, y, z);
		}

		/** Width, height and length of the box, as x, y and z. */
		BlockPos size() {
			BlockPos min = min();
			BlockPos max = max();
			return new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1,
				max.getZ() - min.getZ() + 1);
		}

		long solidBlocks() {
			return blocks.values().stream().filter(state -> !state.isAir()).count();
		}
	}

	/**
	 * Reads a paste back into cells, a later command winning a cell over an earlier one exactly as
	 * it would in the world.
	 *
	 * @throws IllegalArgumentException for anything but a {@code setblock}, which no plan sends; a
	 *     file that silently left a command out would be a build with a hole in it
	 */
	static Build fromCommands(List<String> commands) {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
		for (String command : commands) {
			String[] part = command.split(" ", 5);
			if (part.length < 5 || !"setblock".equals(part[0])) {
				throw new IllegalArgumentException("Only setblock builds can be saved: " + command);
			}
			BlockPos at = new BlockPos(Integer.parseInt(part[1]), Integer.parseInt(part[2]),
				Integer.parseInt(part[3]));
			String placed = part[4];
			// The mode goes last and can only be one of three words. Block data ends in a brace, so
			// it is never mistaken for one.
			for (String mode : new String[] {" replace", " keep", " destroy"}) {
				if (placed.endsWith(mode)) {
					placed = placed.substring(0, placed.length() - mode.length());
					break;
				}
			}
			BlockStateParser.BlockResult parsed;
			try {
				parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, placed, true);
			} catch (CommandSyntaxException unparsed) {
				throw new IllegalArgumentException("Not a block: " + placed, unparsed);
			}
			blocks.put(at, parsed.blockState());
			if (parsed.nbt() != null && !parsed.nbt().isEmpty()) {
				blockEntities.put(at, parsed.nbt());
			} else {
				blockEntities.remove(at);
			}
		}
		if (blocks.isEmpty()) {
			throw new IllegalArgumentException("The build places nothing.");
		}
		settle(blocks);
		return new Build(blocks, blockEntities);
	}

	/** How many times the whole build is settled before giving up on it coming to rest. */
	private static final int SETTLE_PASSES = 8;

	/**
	 * Each block as the world leaves it once its neighbours are down, rather than as it was handed
	 * to {@code /setblock}.
	 *
	 * <p>The paste leans on the game for part of every block's state. A note block is placed bare
	 * and takes its instrument from the block under it, and dust is placed as a dot and joins up
	 * with whatever it runs into: {@code /setblock} settles each block against its neighbours as it
	 * lands ({@code Block.updateFromNeighbourShapes}), then updates the neighbours in turn. Nothing
	 * pastes a schematic that way. Litematica and WorldEdit place exactly the state the file holds,
	 * so without this every note played harp and every wire was a dot.</p>
	 *
	 * <p>Settled against the finished build rather than replayed in paste order. That is where the
	 * world ends up too, since every block is re-settled when a neighbour arrives after it. Settling
	 * goes round until nothing changes, because a wire that joins up changes what the next wire
	 * sees.</p>
	 *
	 * <p>A block the settling would remove (one that cannot stand where it is) is kept as placed. A
	 * schematic that quietly dropped part of the machine would be worse than one that shows it.</p>
	 */
	private static void settle(Map<BlockPos, BlockState> blocks) {
		LevelAccessor level = standIn(blocks);
		for (int pass = 0; pass < SETTLE_PASSES; pass++) {
			boolean changed = false;
			for (Map.Entry<BlockPos, BlockState> cell : blocks.entrySet()) {
				BlockState placed = cell.getValue();
				if (placed.isAir()) {
					continue;
				}
				BlockState settled;
				try {
					settled = Block.updateFromNeighbourShapes(placed, level, cell.getKey());
				} catch (RuntimeException needsARealWorld) {
					continue;
				}
				if (!settled.isAir() && !settled.equals(placed)) {
					cell.setValue(settled);
					changed = true;
				}
			}
			if (!changed) {
				return;
			}
		}
	}

	/**
	 * Just enough of a world for blocks to settle in: the build's own blocks, air everywhere else.
	 *
	 * <p>A proxy, because a level is an interface with a hundred methods and settling a block asks
	 * for perhaps four of them. Answered by what a method returns rather than by its name, so it
	 * holds up under the obfuscated names the game runs with outside development. Anything else
	 * that is asked gets the interface's own default, or nothing.</p>
	 */
	private static LevelAccessor standIn(Map<BlockPos, BlockState> blocks) {
		RandomSource random = RandomSource.create(0L);
		InvocationHandler answer = (proxy, method, args) -> {
			Class<?> returns = method.getReturnType();
			int arity = method.getParameterCount();
			if (arity == 1 && method.getParameterTypes()[0] == BlockPos.class) {
				if (returns == BlockState.class) {
					return blocks.getOrDefault((BlockPos)args[0], Blocks.AIR.defaultBlockState());
				}
				if (returns == FluidState.class) {
					return blocks.getOrDefault((BlockPos)args[0], Blocks.AIR.defaultBlockState())
						.getFluidState();
				}
			}
			if (returns == RandomSource.class && arity == 0) {
				return random;
			}
			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}
			if (returns == boolean.class) {
				return false;
			}
			if (returns == int.class) {
				return 0;
			}
			if (returns == long.class) {
				return 0L;
			}
			if (returns == float.class) {
				return 0F;
			}
			if (returns == double.class) {
				return 0D;
			}
			return null;
		};
		return (LevelAccessor)Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
			new Class<?>[] {LevelAccessor.class}, answer);
	}

	// ------------------------------------------------------------------ litematica

	/**
	 * One region, named after the build, its corner at the schematic's origin.
	 *
	 * <p>Block indices are bit-packed into longs at the fewest bits the palette needs (never under
	 * two), and an entry may run on from one long into the next -- Litematica's packing, not the
	 * chunk format's padded one. Air has to be palette entry nought, because that is what an
	 * all-zero array means to the reader.</p>
	 */
	static void writeLitematic(Build build, Path file, String name, String author,
			String description) throws IOException {
		BlockPos min = build.min();
		BlockPos size = build.size();
		int width = size.getX();
		int height = size.getY();
		int length = size.getZ();
		long volume = (long)width * height * length;

		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> indexOf = new HashMap<>();
		palette.add(Blocks.AIR.defaultBlockState());
		indexOf.put(Blocks.AIR.defaultBlockState(), 0);
		for (BlockState state : build.blocks().values()) {
			indexOf.computeIfAbsent(state, added -> {
				palette.add(added);
				return palette.size() - 1;
			});
		}
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
		long[] packed = new long[(int)((volume * bits + 63) / 64)];
		for (Map.Entry<BlockPos, BlockState> cell : build.blocks().entrySet()) {
			BlockPos at = cell.getKey().subtract(min);
			long index = ((long)at.getY() * length + at.getZ()) * width + at.getX();
			long value = indexOf.get(cell.getValue());
			long startBit = index * bits;
			int startLong = (int)(startBit >> 6);
			int offset = (int)(startBit & 63L);
			packed[startLong] |= value << offset;
			if (offset + bits > 64) {
				packed[startLong + 1] |= value >>> (64 - offset);
			}
		}

		ListTag paletteTag = new ListTag();
		for (BlockState state : palette) {
			paletteTag.add(nameAndProperties(state));
		}
		ListTag tileEntities = new ListTag();
		build.blockEntities().forEach((at, data) -> {
			CompoundTag entry = data.copy();
			BlockPos relative = at.subtract(min);
			entry.putInt("x", relative.getX());
			entry.putInt("y", relative.getY());
			entry.putInt("z", relative.getZ());
			entry.putString("id", blockEntityId(at, build.blocks().get(at)));
			tileEntities.add(entry);
		});

		CompoundTag region = new CompoundTag();
		region.put("Position", xyz(0, 0, 0));
		region.put("Size", xyz(width, height, length));
		region.put("BlockStatePalette", paletteTag);
		region.putLongArray("BlockStates", packed);
		region.put("TileEntities", tileEntities);
		region.put("Entities", new ListTag());
		region.put("PendingBlockTicks", new ListTag());
		region.put("PendingFluidTicks", new ListTag());
		CompoundTag regions = new CompoundTag();
		regions.put(name, region);

		long now = System.currentTimeMillis();
		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", name);
		metadata.putString("Author", author);
		metadata.putString("Description", description);
		metadata.putInt("RegionCount", 1);
		metadata.putInt("TotalVolume", (int)volume);
		metadata.putInt("TotalBlocks", (int)build.solidBlocks());
		metadata.putLong("TimeCreated", now);
		metadata.putLong("TimeModified", now);
		metadata.put("EnclosingSize", xyz(width, height, length));

		CompoundTag root = new CompoundTag();
		root.putInt("Version", LITEMATIC_VERSION);
		root.putInt("SubVersion", LITEMATIC_SUB_VERSION);
		root.putInt("MinecraftDataVersion", dataVersion());
		root.put("Metadata", metadata);
		root.put("Regions", regions);
		write(root, file);
	}

	/**
	 * A palette entry as {@code {Name, Properties}}, the way Litematica writes them.
	 *
	 * <p>Not the game's own {@code NbtUtils.writeBlockState}: in 26.3 that writes
	 * {@code {id, properties}}, which Litematica for 26.3 reads but every older Litematica does not.
	 * The capitalised pair is what every version reads.</p>
	 */
	private static CompoundTag nameAndProperties(BlockState state) {
		String text = BlockStateParser.serialize(state);
		int bracket = text.indexOf('[');
		CompoundTag entry = new CompoundTag();
		entry.putString("Name", bracket < 0 ? text : text.substring(0, bracket));
		if (bracket >= 0) {
			CompoundTag properties = new CompoundTag();
			for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
				int equals = pair.indexOf('=');
				properties.putString(pair.substring(0, equals), pair.substring(equals + 1));
			}
			entry.put("Properties", properties);
		}
		return entry;
	}

	private static CompoundTag xyz(int x, int y, int z) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", x);
		tag.putInt("y", y);
		tag.putInt("z", z);
		return tag;
	}

	// ------------------------------------------------------------------ sponge .schem

	/**
	 * Sponge v3: a dense grid of varint palette indices, x fastest, then z, then y.
	 *
	 * @param standingAt where the player would stand to paste this build with the Paste button. The
	 *     file's offset is taken from it, so {@code //paste} from the same spot puts every block
	 *     where the Paste button would have.
	 */
	static void writeSponge(Build build, Path file, String name, String author,
			BlockPos standingAt) throws IOException {
		BlockPos min = build.min();
		BlockPos size = build.size();
		int width = size.getX();
		int height = size.getY();
		int length = size.getZ();
		if (width > 0xFFFF || height > 0xFFFF || length > 0xFFFF) {
			throw new IllegalArgumentException("Too big for a .schem: " + width + " x " + height
				+ " x " + length);
		}

		Map<BlockState, Integer> indexOf = new LinkedHashMap<>();
		indexOf.put(Blocks.AIR.defaultBlockState(), 0);
		int[] grid = new int[width * height * length];
		for (Map.Entry<BlockPos, BlockState> cell : build.blocks().entrySet()) {
			BlockPos at = cell.getKey().subtract(min);
			int value = indexOf.computeIfAbsent(cell.getValue(), added -> indexOf.size());
			grid[at.getX() + at.getZ() * width + at.getY() * width * length] = value;
		}
		ByteArrayOutputStream data = new ByteArrayOutputStream(grid.length);
		for (int value : grid) {
			// Varint: seven bits a byte, the high bit set on every byte but the last.
			while ((value & ~0x7F) != 0) {
				data.write(value & 0x7F | 0x80);
				value >>>= 7;
			}
			data.write(value);
		}
		CompoundTag palette = new CompoundTag();
		indexOf.forEach((state, index) -> palette.putInt(BlockStateParser.serialize(state), index));

		ListTag blockEntities = new ListTag();
		build.blockEntities().forEach((at, stored) -> {
			BlockPos relative = at.subtract(min);
			CompoundTag entry = new CompoundTag();
			entry.putIntArray("Pos", new int[] {relative.getX(), relative.getY(), relative.getZ()});
			entry.putString("Id", blockEntityId(at, build.blocks().get(at)));
			entry.put("Data", stored.copy());
			blockEntities.add(entry);
		});

		CompoundTag blocks = new CompoundTag();
		blocks.put("Palette", palette);
		blocks.putByteArray("Data", data.toByteArray());
		blocks.put("BlockEntities", blockEntities);

		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", name);
		metadata.putString("Author", author);
		metadata.putLong("Date", System.currentTimeMillis());
		metadata.put("RequiredMods", new ListTag());

		CompoundTag schematic = new CompoundTag();
		schematic.putInt("Version", SPONGE_VERSION);
		schematic.putInt("DataVersion", dataVersion());
		schematic.put("Metadata", metadata);
		schematic.putShort("Width", (short)width);
		schematic.putShort("Height", (short)height);
		schematic.putShort("Length", (short)length);
		BlockPos offset = min.subtract(standingAt);
		schematic.putIntArray("Offset", new int[] {offset.getX(), offset.getY(), offset.getZ()});
		schematic.put("Blocks", blocks);

		CompoundTag root = new CompoundTag();
		root.put("Schematic", schematic);
		write(root, file);
	}

	// ------------------------------------------------------------------ shared

	/** The block entity type a placed state carries, which both formats name the data by. */
	private static String blockEntityId(BlockPos at, BlockState state) {
		if (state != null && state.getBlock() instanceof EntityBlock entityBlock) {
			BlockEntity entity = entityBlock.newBlockEntity(at, state);
			if (entity != null) {
				return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
			}
		}
		throw new IllegalArgumentException("Block data on a block that keeps none: " + state);
	}

	private static int dataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}

	private static void write(CompoundTag root, Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		NbtIo.writeCompressed(root, file);
	}
}
