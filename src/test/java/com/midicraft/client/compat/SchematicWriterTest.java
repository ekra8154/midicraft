package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The writer against the reader: what is saved must load back as the same blocks. */
class SchematicWriterTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Enough different blocks for six bits an entry, which does not divide 64, so entries straddle
	 * longs in the Litematica packing -- the case a padded reader or writer gets wrong.
	 */
	private static List<String> commands() {
		List<String> commands = new ArrayList<>();
		List<String> ids = BuiltInRegistries.BLOCK.keySet().stream()
			.map(Object::toString)
			.filter(id -> id.endsWith("_wool") || id.endsWith("_concrete")
				|| id.endsWith("_terracotta") && !id.contains("glazed"))
			.sorted()
			.toList();
		int i = 0;
		for (int y = 60; y < 63; y++) {
			for (int z = -3; z < 4; z++) {
				for (int x = 5; x < 14; x++) {
					commands.add("setblock " + x + " " + y + " " + z + " " + ids.get(i++ % ids.size())
						+ " replace");
				}
			}
		}
		commands.add("setblock 6 63 0 minecraft:repeater[facing=east,delay=3] replace");
		commands.add("setblock 7 63 0 minecraft:note_block[instrument=harp,note=12] replace");
		commands.add("setblock 7 64 0 minecraft:air replace");
		// A later command wins its cell, as it does in the world.
		commands.add("setblock 5 60 -3 minecraft:stone replace");
		commands.add("setblock 5 60 -3 minecraft:glass replace");
		commands.add("setblock 8 63 1 minecraft:oak_wall_sign[facing=north]"
			+ "{front_text:{messages:[\"Fireflies\",\"\",\"\",\"\"]}} replace");
		return commands;
	}

	private static Map<BlockPos, BlockState> solid(SchematicWriter.Build build) {
		BlockPos min = build.min();
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		build.blocks().forEach((at, state) -> {
			if (!state.isAir()) {
				blocks.put(at.subtract(min), state);
			}
		});
		return blocks;
	}

	@Test
	void litematicLoadsBackAsTheSameBlocks(@TempDir Path dir) throws Exception {
		SchematicWriter.Build build = SchematicWriter.fromCommands(commands());
		Path file = dir.resolve("song.litematic");
		SchematicWriter.writeLitematic(build, file, "Song", "test", "");

		SchematicReader.Schematic read = SchematicReader.load(file);
		assertEquals(build.size(), read.size());
		assertEquals(solid(build), read.blocks());

		CompoundTag region = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
			.getCompoundOrEmpty("Regions").getCompoundOrEmpty("Song");
		CompoundTag sign = region.getListOrEmpty("TileEntities").getCompoundOrEmpty(0);
		assertEquals("minecraft:sign", sign.getStringOr("id", ""));
		assertEquals(3, sign.getIntOr("x", -1));
		assertEquals("Fireflies", sign.getCompoundOrEmpty("front_text").getListOrEmpty("messages")
			.getStringOr(0, ""));
	}

	@Test
	void schemLoadsBackAsTheSameBlocks(@TempDir Path dir) throws Exception {
		SchematicWriter.Build build = SchematicWriter.fromCommands(commands());
		Path file = dir.resolve("song.schem");
		SchematicWriter.writeSponge(build, file, "Song", "test", new BlockPos(-1, 64, 0));

		SchematicReader.Schematic read = SchematicReader.load(file);
		assertEquals("sponge v3", read.format());
		assertEquals(build.size(), read.size());
		assertEquals(solid(build), read.blocks());

		CompoundTag schematic = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
			.getCompoundOrEmpty("Schematic");
		// The corner sits six east, four down and three north of where the player stood.
		assertEquals(List.of(6, -4, -3), toList(schematic.getIntArray("Offset").orElseThrow()));
		CompoundTag sign = schematic.getCompoundOrEmpty("Blocks").getListOrEmpty("BlockEntities")
			.getCompoundOrEmpty(0);
		assertEquals("minecraft:sign", sign.getStringOr("Id", ""));
		assertEquals(List.of(3, 3, 4), toList(sign.getIntArray("Pos").orElseThrow()));
	}

	/**
	 * A schematic is pasted without the updates {@code /setblock} makes, so the file has to hold what
	 * those updates would have left: the instrument read off the block below, and dust joined up.
	 */
	@Test
	void blocksAreSavedAsTheWorldSettlesThem() {
		SchematicWriter.Build build = SchematicWriter.fromCommands(List.of(
			"setblock 0 0 0 minecraft:stone replace",
			"setblock 0 1 0 minecraft:note_block[note=5] replace",
			"setblock 1 0 0 minecraft:gold_block replace",
			"setblock 1 1 0 minecraft:note_block replace",
			"setblock 0 0 2 minecraft:stone replace",
			"setblock 1 0 2 minecraft:stone replace",
			"setblock 2 0 2 minecraft:stone replace",
			"setblock 0 1 2 minecraft:redstone_wire replace",
			"setblock 1 1 2 minecraft:redstone_wire replace",
			"setblock 2 1 2 minecraft:redstone_wire replace"));
		assertEquals("minecraft:note_block[instrument=basedrum,note=5,powered=false]",
			net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
				build.blocks().get(new BlockPos(0, 1, 0))));
		assertEquals("minecraft:note_block[instrument=bell,note=0,powered=false]",
			net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
				build.blocks().get(new BlockPos(1, 1, 0))));
		assertEquals("minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]",
			net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
				build.blocks().get(new BlockPos(1, 1, 2))));
	}

	/** The export folders hold the player's own schematics too, so nothing there is written over. */
	@Test
	void savingNumbersRatherThanOverwrites(@TempDir Path dir) throws Exception {
		SchematicWriter.Build build = SchematicWriter.fromCommands(commands());
		Path first = SchematicWriter.save(build, SchematicWriter.Format.LITEMATIC, dir, "Song 32x1",
			"Song", "test", "", BlockPos.ZERO);
		Path second = SchematicWriter.save(build, SchematicWriter.Format.LITEMATIC, dir, "Song 32x1",
			"Song", "test", "", BlockPos.ZERO);
		Path schem = SchematicWriter.save(build, SchematicWriter.Format.SCHEM, dir, "Song 32x1",
			"Song", "test", "", BlockPos.ZERO);
		assertEquals("Song 32x1.litematic", first.getFileName().toString());
		assertEquals("Song 32x1 (2).litematic", second.getFileName().toString());
		assertEquals("Song 32x1.schem", schem.getFileName().toString());
		assertEquals("sponge v3", SchematicReader.load(schem).format());
	}

	@Test
	void refusesWhatIsNotASetblock() {
		assertThrows(IllegalArgumentException.class,
			() -> SchematicWriter.fromCommands(List.of("fill 0 0 0 1 1 1 minecraft:stone")));
	}

	private static List<Integer> toList(int[] values) {
		List<Integer> list = new ArrayList<>();
		for (int value : values) {
			list.add(value);
		}
		return list;
	}
}
