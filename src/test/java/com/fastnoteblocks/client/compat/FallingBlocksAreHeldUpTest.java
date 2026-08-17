package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Two things about sand, both of which a build got wrong and neither of which announces itself.
 *
 * <p>Sand is the snare instrument, so a song with a drum line in it is a build full of blocks that
 * fall. When one does, it does not merely land on the ground: the note block over it reads its
 * instrument off whatever is underneath, and by then that is air -- so the snare becomes a harp and
 * the build plays a wrong note while looking exactly right.</p>
 *
 * <p>The two faults were separate and are checked separately. <b>Nothing to stand on</b>: the walk
 * props the sand it plans for, but a chord overshooting a wall lands past the end of the floor laid
 * between them. <b>Propped too late</b>: the walk lays sand and then fills underneath it, one
 * command behind, which survives at 32 commands a tick and does not at one command every four.</p>
 *
 * <p>Both are asked of the game rather than of a list -- {@code instanceof FallingBlock} for what
 * falls, {@link FallingBlock#isFree} for what counts as nothing underneath -- because that is the
 * check the falling block itself makes.</p>
 */
class FallingBlocksAreHeldUpTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * Specs that put snares where the trouble is.
	 *
	 * <p>A chord wide enough to overshoot a wall is the shape that produced the unpropped sand, so
	 * the big all-snare chords matter more than the count of them. The narrow widths are here for
	 * the same reason: the narrower the lane, the more a chord hangs out of it.</p>
	 */
	private static final List<String> SPECS = List.of(
		"20:20s",
		"24:24s",
		"5:5sx8",
		"30:30s",
		"12:12s 25:25s@1",
		"8:5s3p 16:8s8p@2");

	private static Map<String, Block> byId() {
		Map<String, Block> blocks = new HashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			blocks.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block);
		}
		return blocks;
	}

	private record Build(String what, List<BlockPos> at, List<Block> blocks) {
	}

	private static List<Build> builds() {
		Map<String, Block> byId = byId();
		List<Build> built = new ArrayList<>();
		for (String spec : SPECS) {
			for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
				for (int width : new int[] {12, 16, 32}) {
					for (int floors : new int[] {1, 3}) {
						SongBuilder.PastePlan plan;
						try {
							plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
								DebugChords.notes(spec, 4), mode,
								new SongBuilder.BuildLimits(4, width, floors));
						} catch (RuntimeException refused) {
							continue;
						}
						List<BlockPos> at = new ArrayList<>();
						List<Block> blocks = new ArrayList<>();
						for (String command : plan.commands()) {
							CommandPasteSender.Placement placement =
								CommandPasteSender.read(command);
							at.add(placement.at());
							blocks.add(byId.get(placement.block()));
						}
						built.add(new Build(mode + " w" + width + " f" + floors + " \"" + spec + "\"",
							at, blocks));
					}
				}
			}
		}
		return built;
	}

	/** Nothing this build places ends up standing on air, wherever the walk put it. */
	@Test
	void everyFallingBlockHasSomethingUnderItWhenTheBuildIsDone() {
		List<String> standingOnNothing = new ArrayList<>();
		int falling = 0;
		for (Build build : builds()) {
			Map<BlockPos, Block> world = new HashMap<>();
			for (int i = 0; i < build.at().size(); i++) {
				if (build.at().get(i) != null) {
					world.put(build.at().get(i), build.blocks().get(i));
				}
			}
			for (Map.Entry<BlockPos, Block> cell : world.entrySet()) {
				if (!(cell.getValue() instanceof FallingBlock)) {
					continue;
				}
				falling++;
				Block under = world.get(cell.getKey().below());
				if (under == null || FallingBlock.isFree(under.defaultBlockState())) {
					standingOnNothing.add(build.what() + " at " + cell.getKey().getX() + " "
						+ cell.getKey().getY() + " " + cell.getKey().getZ() + " (below is "
						+ (under == null ? "never written"
							: BuiltInRegistries.BLOCK.getKey(under).toString()) + ")");
				}
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(falling > 500,
			"these specs should be full of sand, and held " + falling + " falling blocks");
		assertEquals(List.of(), standingOnNothing.stream().limit(8).toList(),
			standingOnNothing.size() + " falling blocks are left standing on air. Each one lands on "
			+ "the ground and takes its note block's instrument with it");
	}

	/**
	 * No falling block is sent before the thing that holds it up.
	 *
	 * <p>Which makes the build independent of how fast it is pasted. Two ticks is all the slack a
	 * falling block gives, and the slowest send rate spends four ticks on a single command -- so a
	 * prop even one command late is a block on the floor at that rate.</p>
	 */
	@Test
	void everyPropIsSentBeforeTheBlockItHoldsUp() {
		List<String> sentTooLate = new ArrayList<>();
		for (Build build : builds()) {
			Map<BlockPos, Integer> written = new HashMap<>();
			for (int i = 0; i < build.at().size(); i++) {
				BlockPos at = build.at().get(i);
				Block block = build.blocks().get(i);
				if (at == null) {
					continue;
				}
				if (block instanceof FallingBlock) {
					Integer under = written.get(at.below());
					if (under == null) {
						sentTooLate.add(build.what() + " at " + at.getX() + " " + at.getY() + " "
							+ at.getZ() + ": nothing under it had been sent yet");
					}
				}
				written.put(at, i);
			}
		}
		assertEquals(List.of(), sentTooLate.stream().limit(8).toList(),
			sentTooLate.size() + " falling blocks go out before their prop does, which lands them "
			+ "on the floor at any rate slower than about two commands a tick");
	}
}
