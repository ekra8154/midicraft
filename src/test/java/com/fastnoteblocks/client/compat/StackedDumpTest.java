package com.fastnoteblocks.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scratch probe: a stacked module and what follows it, block by block, on a straight lane. */
class StackedDumpTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void dumps() {
		SongBuilder.STACKED_BUS_HEADS = true;
		// A seven-note chord that can use the centre needs a harp there, plus two relays that pass
		// power sideways. Mixed instruments, the way a real song arrives.
		List<SongBuilder.EventNote> notes = new ArrayList<>();
		String[] mix = {"minecraft:air", "minecraft:stone", "minecraft:air", "minecraft:air",
			"minecraft:gold_block", "minecraft:air", "minecraft:air"};
		for (int index = 0; index < 7; index++) {
			notes.add(new SongBuilder.EventNote(8, 1, index, 10 + index, mix[index]));
		}
		// Then a long chord after it, so we can see what a bus looks like right behind a module.
		for (int index = 0; index < 12; index++) {
			notes.add(new SongBuilder.EventNote(16, 1, index, 5, "minecraft:air"));
		}
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE, new SongBuilder.BuildLimits(4, 48, 1));
		for (String command : plan.commands()) {
			System.out.println("SD " + command.replace("setblock ", "").replace(" replace", ""));
		}
		System.out.println("SDPAD " + plan.padding());
	}
}
