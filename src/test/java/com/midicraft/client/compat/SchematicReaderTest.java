package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes a real build out in each schematic format and reads the song back.
 *
 * <p>The formats are written here by hand rather than by the mod, so a decoder that agrees with
 * itself is not enough -- the palette order, the block order and the bit packing all have to be
 * what the format actually says they are.</p>
 */
class SchematicReaderTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@TempDir
	Path folder;

	@Test
	void readsAStructureFile() throws Exception {
		check(writeStructure(build(), folder.resolve("song.nbt")), "structure");
	}

	@Test
	void readsASpongeSchematic() throws Exception {
		check(writeSponge(build(), folder.resolve("song.schem")), "sponge v2");
	}

	@Test
	void readsALitematic() throws Exception {
		check(writeLitematic(build(), folder.resolve("song.litematic")), "litematica");
	}

	/** A file named .schem that is really a structure file still reads, because people rename them. */
	@Test
	void readsByContentNotByName() throws Exception {
		SchematicReader.Schematic schematic =
			SchematicReader.load(writeStructure(build(), folder.resolve("misnamed.schem")));
		assertEquals("structure", schematic.format());
	}

	private void check(Path path, String expectedFormat) throws Exception {
		SchematicReader.Schematic schematic = SchematicReader.load(path);
		assertEquals(expectedFormat, schematic.format(), "wrong format detected");

		NoteMachineReader.Reading reading = NoteMachineReader.read("From file", BlockPos.ZERO,
			schematic.size().offset(-1, -1, -1), schematic::at);

		List<SongBuilder.EventNote> expected = song();
		assertEquals(expected.size(), reading.noteBlocks(), "wrong number of note blocks found");
		assertEquals(0, reading.unreachedNotes(), "every note should be reachable");
		int notes = reading.project().layers().stream().mapToInt(layer -> layer.notes().size()).sum();
		assertEquals(expected.size(), notes, "wrong number of notes read");

		// The spacing is the part a format can quietly ruin: get the block order wrong and the
		// machine still contains every note block, just wired into a different tune.
		List<Long> ticks = reading.project().layers().stream()
			.flatMap(layer -> layer.notes().stream())
			.map(note -> note.startTick() / NoteMachineReader.TICKS_PER_REDSTONE_TICK)
			.distinct()
			.sorted()
			.toList();
		List<Long> wanted = expected.stream()
			.map(note -> (long)(note.time() - expected.get(0).time()))
			.distinct()
			.sorted()
			.toList();
		assertEquals(wanted, ticks, "the timing did not survive the format");
		assertTrue(reading.project().layers().size() > 1, "instruments should come back as layers");
	}

	// ------------------------------------------------------------------ the build under test

	private static List<SongBuilder.EventNote> song() {
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] instruments = {"minecraft:air", "minecraft:gold_block", "minecraft:oak_planks"};
		int time = 0;
		for (int event = 0; event < 24; event++) {
			time += 1 + event % 7;
			int chord = 1 + event % 5;
			for (int index = 0; index < chord; index++) {
				notes.add(new SongBuilder.EventNote(time, 1, index, (event * 3 + index) % 25,
					instruments[index % instruments.length]));
			}
		}
		return List.copyOf(notes);
	}

	/** The build laid out at the origin, as a dense box with air where nothing was placed. */
	private static Map<BlockPos, BlockState> build() {
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(BlockPos.ZERO, song(),
			SongBuilder.PasteMode.COMPACT_CUBE, new SongBuilder.BuildLimits(3, 20, 2));
		Map<BlockPos, BlockState> placed = new HashMap<>();
		for (String command : plan.commands()) {
			String[] parts = command.split(" ");
			placed.put(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3])), parse(parts[4]));
		}
		// Slide to the origin, since a schematic's own coordinates always start there.
		int minX = placed.keySet().stream().mapToInt(BlockPos::getX).min().orElse(0);
		int minY = placed.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
		int minZ = placed.keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0);
		Map<BlockPos, BlockState> shifted = new HashMap<>();
		placed.forEach((position, state) -> shifted.put(
			position.offset(-minX, -minY, -minZ), state));
		return shifted;
	}

	private static BlockPos sizeOf(Map<BlockPos, BlockState> blocks) {
		return new BlockPos(
			blocks.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0) + 1,
			blocks.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0) + 1,
			blocks.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1);
	}

	// ------------------------------------------------------------------ writing each format

	private static Path writeStructure(Map<BlockPos, BlockState> blocks, Path path) throws Exception {
		Map<String, Integer> palette = new LinkedHashMap<>();
		ListTag blocksTag = new ListTag();
		for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
			if (entry.getValue().isAir()) {
				continue;
			}
			int index = palette.computeIfAbsent(describe(entry.getValue()), key -> palette.size());
			CompoundTag block = new CompoundTag();
			block.putInt("state", index);
			ListTag position = new ListTag();
			position.add(IntTag.valueOf(entry.getKey().getX()));
			position.add(IntTag.valueOf(entry.getKey().getY()));
			position.add(IntTag.valueOf(entry.getKey().getZ()));
			block.put("pos", position);
			blocksTag.add(block);
		}
		ListTag paletteTag = new ListTag();
		palette.keySet().forEach(state -> paletteTag.add(nameAndProperties(state)));

		CompoundTag root = new CompoundTag();
		root.put("palette", paletteTag);
		root.put("blocks", blocksTag);
		BlockPos size = sizeOf(blocks);
		ListTag sizeTag = new ListTag();
		sizeTag.add(IntTag.valueOf(size.getX()));
		sizeTag.add(IntTag.valueOf(size.getY()));
		sizeTag.add(IntTag.valueOf(size.getZ()));
		root.put("size", sizeTag);
		return write(root, path);
	}

	/** Sponge stores a dense grid of varints, indexed y first, then z, then x. */
	private static Path writeSponge(Map<BlockPos, BlockState> blocks, Path path) throws Exception {
		BlockPos size = sizeOf(blocks);
		Map<String, Integer> palette = new LinkedHashMap<>();
		palette.put(describe(Blocks.AIR.defaultBlockState()), 0);
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (int y = 0; y < size.getY(); y++) {
			for (int z = 0; z < size.getZ(); z++) {
				for (int x = 0; x < size.getX(); x++) {
					BlockState state = blocks.getOrDefault(new BlockPos(x, y, z),
						Blocks.AIR.defaultBlockState());
					int index = palette.computeIfAbsent(describe(state), key -> palette.size());
					for (int value = index; ; ) {
						if ((value & ~0x7F) == 0) {
							data.write(value);
							break;
						}
						data.write(value & 0x7F | 0x80);
						value >>>= 7;
					}
				}
			}
		}
		CompoundTag paletteTag = new CompoundTag();
		palette.forEach(paletteTag::putInt);

		CompoundTag root = new CompoundTag();
		root.putInt("Version", 2);
		root.putShort("Width", (short)size.getX());
		root.putShort("Height", (short)size.getY());
		root.putShort("Length", (short)size.getZ());
		root.put("Palette", paletteTag);
		root.putByteArray("BlockData", data.toByteArray());
		return write(root, path);
	}

	/** Litematica packs palette indices into longs, letting an entry straddle two of them. */
	private static Path writeLitematic(Map<BlockPos, BlockState> blocks, Path path) throws Exception {
		BlockPos size = sizeOf(blocks);
		List<String> palette = new ArrayList<>();
		palette.add(describe(Blocks.AIR.defaultBlockState()));
		List<Integer> indices = new ArrayList<>();
		for (int y = 0; y < size.getY(); y++) {
			for (int z = 0; z < size.getZ(); z++) {
				for (int x = 0; x < size.getX(); x++) {
					String state = describe(blocks.getOrDefault(new BlockPos(x, y, z),
						Blocks.AIR.defaultBlockState()));
					int index = palette.indexOf(state);
					if (index < 0) {
						index = palette.size();
						palette.add(state);
					}
					indices.add(index);
				}
			}
		}
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
		long[] packed = new long[(int)(((long)indices.size() * bits + 63) / 64)];
		for (int index = 0; index < indices.size(); index++) {
			long startBit = (long)index * bits;
			int startLong = (int)(startBit >> 6);
			int endLong = (int)((startBit + bits - 1) >> 6);
			int offset = (int)(startBit & 63L);
			long value = indices.get(index);
			packed[startLong] |= value << offset;
			if (startLong != endLong) {
				packed[endLong] |= value >>> (64 - offset);
			}
		}
		ListTag paletteTag = new ListTag();
		palette.forEach(state -> paletteTag.add(nameAndProperties(state)));

		CompoundTag sizeTag = new CompoundTag();
		sizeTag.putInt("x", size.getX());
		sizeTag.putInt("y", size.getY());
		sizeTag.putInt("z", size.getZ());
		CompoundTag region = new CompoundTag();
		region.put("Size", sizeTag);
		region.put("BlockStatePalette", paletteTag);
		region.putLongArray("BlockStates", packed);
		CompoundTag regions = new CompoundTag();
		regions.put("Main", region);
		CompoundTag root = new CompoundTag();
		root.put("Regions", regions);
		return write(root, path);
	}

	private static Path write(CompoundTag root, Path path) throws Exception {
		Files.createDirectories(path.getParent());
		try (var out = Files.newOutputStream(path)) {
			NbtIo.writeCompressed(root, out);
		}
		return path;
	}

	// ------------------------------------------------------------------ block state text

	/** A block state written the way a command would, which is what the readers parse. */
	private static String describe(BlockState state) {
		return BlockStateParser.serialize(state);
	}

	/** The same text split back into the {@code {Name, Properties}} pair NBT formats store. */
	private static CompoundTag nameAndProperties(String blockState) {
		CompoundTag entry = new CompoundTag();
		int bracket = blockState.indexOf('[');
		if (bracket < 0) {
			entry.putString("Name", blockState);
			return entry;
		}
		entry.putString("Name", blockState.substring(0, bracket));
		CompoundTag properties = new CompoundTag();
		for (String pair : blockState.substring(bracket + 1, blockState.length() - 1).split(",")) {
			int equals = pair.indexOf('=');
			properties.put(pair.substring(0, equals), StringTag.valueOf(pair.substring(equals + 1)));
		}
		entry.put("Properties", properties);
		return entry;
	}

	private static BlockState parse(String blockState) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, blockState, false)
				.blockState();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
			throw new IllegalStateException(blockState, unparseable);
		}
	}
}
