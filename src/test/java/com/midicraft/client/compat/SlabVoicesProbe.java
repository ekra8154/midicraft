package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Which of the builder's instruments have a half-block that plays the same voice.
 *
 * <p>Asked of the registry rather than reasoned from what a material is called. "A slab of the same
 * stuff obviously sounds the same" is the kind of thing that is true of oak and stone and then not
 * true of the one that matters -- and the answer decides how much of a build can be halved.</p>
 */
@Tag("sweep")
class SlabVoicesProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void listsEverySlabThatCouldStandInForAnInstrument() {
		Map<String, List<String>> slabsByVoice = new TreeMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (!(block instanceof SlabBlock)) {
				continue;
			}
			BlockState top = block.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
			// Whether it conducts is worth printing beside the voice: the whole reason a slab is
			// safe under a note and not under a relay is that it cannot be strongly powered.
			slabsByVoice.computeIfAbsent(top.instrument().name(), voice -> new ArrayList<>())
				.add(BuiltInRegistries.BLOCK.getKey(block)
					+ (top.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
						? " (CONDUCTS)" : ""));
		}
		System.out.println("==== the builder's instruments, and whether a slab plays that voice ====");
		for (PreviewInstrument instrument : PreviewInstrument.VALUES) {
			Block block = Block.byItem(instrument.icon());
			String id = BuiltInRegistries.ITEM.getKey(instrument.icon()).toString();
			String voice = block.defaultBlockState().instrument().name();
			List<String> slabs = slabsByVoice.getOrDefault(voice, List.of());
			System.out.println(String.format("VOICE %-18s %-32s %-16s %s",
				instrument.id(), id, voice,
				slabs.isEmpty() ? "-- no slab --"
					: String.join(", ", slabs.size() > 5 ? slabs.subList(0, 5) : slabs)));
		}
	}
}
