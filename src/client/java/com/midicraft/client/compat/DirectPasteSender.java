package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pastes straight into the world, for the case where the world is in this same game: singleplayer,
 * or a LAN world this game is hosting.
 *
 * <p>The command route pays for every block several times over. It is a packet from the client, a
 * command line for the server to parse, a block state to read out of text, and then the updates
 * {@code /setblock} makes: the block settled against its neighbours, its neighbours against it,
 * and a redstone update to everything around it. On a build that is mostly redstone those updates
 * are most of the lag. Here the build is settled once, up front, by {@link SchematicWriter} --
 * every note block's instrument, every wire's connections, worked out against the finished build
 * -- and each block is then set as exactly what it will be, telling the game its shape is already
 * known ({@link Block#UPDATE_KNOWN_SHAPE}) and leaving its neighbours alone. Litematica pastes the
 * same way, and for the same reason.</p>
 *
 * <p>The placing happens on the server's own thread, as much of it each tick as fits in what the
 * server can spare -- measured against its own recent tick times rather than set as a rate, so a
 * fast machine pastes faster and a struggling one is not pushed over. Everything else is the
 * command route's behaviour on purpose: the same order, waiting at the first block whose chunk is
 * not loaded rather than skipping it, holding behind the pause menu and the composer, and the same
 * lines in chat at the end.</p>
 *
 * <p>Only when the player could have run {@code /setblock} anyway. The client is sent only the
 * commands its player may use, so finding {@code setblock} among them is the same permission the
 * command route needs -- and a survival world without cheats gets no paste either way.</p>
 */
final class DirectPasteSender {
	private DirectPasteSender() {
	}

	/** The tick length the placing aims to leave the server at, in nanoseconds. Vanilla's is 50 ms. */
	private static final long TARGET_TICK_NANOS = 35_000_000L;
	/** The least a tick spends placing, so a busy server still makes progress. */
	private static final long MIN_BUDGET_NANOS = 2_000_000L;
	/** The most a tick spends placing, however idle the server is. */
	private static final long MAX_BUDGET_NANOS = 20_000_000L;
	/** Blocks placed between looks at the clock; reading it is not free either. */
	private static final int BLOCKS_PER_CLOCK_CHECK = 64;
	/** How often the overlay is redrawn, in client ticks. */
	private static final int PROGRESS_EVERY = 10;
	/** How often a stalled or held paste says so again, in client ticks. */
	private static final int NAG = 40;

	/** The building flags: tell the clients, and trust the state as given. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** One block of the build: where, what, and the block entity data for the few that carry it. */
	private record Cell(BlockPos at, BlockState state, CompoundTag data) {
	}

	/**
	 * A paste in progress, shared between the client thread, which watches it, and the server thread,
	 * which does it. The server owns {@link #placed} and {@link #waitingAt}; the client owns the rest.
	 */
	private static final class Job {
		final ResourceKey<Level> dimension;
		final ClientLevel world;
		final List<String> commands;
		final List<String> faults;
		final Runnable done;
		final CompletableFuture<List<Cell>> preparing;
		volatile List<Cell> cells;
		volatile int placed;
		volatile BlockPos waitingAt;
		volatile boolean held;
		volatile boolean cancelled;
		int ticks;
		int heldFor;

		Job(ClientLevel world, List<String> commands, List<String> faults, Runnable done) {
			this.world = world;
			this.dimension = world.dimension();
			this.commands = commands;
			this.faults = List.copyOf(faults);
			this.done = done;
			// Off the client thread: settling a big build is every block asked about every
			// neighbour, a few times over, and that is a visible hitch on the frame.
			this.preparing = CompletableFuture.supplyAsync(() -> cells(commands));
		}

		int total() {
			List<Cell> ready = cells;
			return ready == null ? commands.size() : ready.size();
		}
	}

	private static volatile Job job;

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(DirectPasteSender::serverTick);
	}

	/**
	 * Whether this paste can go straight into the world: the world is in this game, and the player
	 * could have used {@code /setblock} on it.
	 */
	static boolean available(Minecraft minecraft) {
		if (!minecraft.hasSingleplayerServer() || minecraft.getSingleplayerServer() == null
				|| minecraft.player == null || minecraft.player.connection == null
				|| minecraft.level == null) {
			return false;
		}
		return minecraft.player.connection.getCommands().getRoot().getChild("setblock") != null;
	}

	static boolean isRunning() {
		return job != null;
	}

	static void start(List<String> commands, List<String> faults, Runnable done) {
		cancel(false);
		job = new Job(Minecraft.getInstance().level, List.copyOf(commands), faults, done);
		CommandPasteSender.show(Component.literal("Preparing paste: " + commands.size()
			+ " blocks"));
	}

	static void cancel(boolean notify) {
		Job running = job;
		if (running == null) {
			return;
		}
		running.cancelled = true;
		job = null;
		if (notify) {
			CommandPasteSender.show(Component.literal("Sequence placement cancelled at "
				+ running.placed + "/" + running.total()));
		}
	}

	/** The build as cells in paste order, each settled against the finished build. */
	private static List<Cell> cells(List<String> commands) {
		SchematicWriter.Build build = SchematicWriter.fromCommands(commands);
		List<Cell> cells = new ArrayList<>(build.blocks().size());
		for (Map.Entry<BlockPos, BlockState> cell : build.blocks().entrySet()) {
			cells.add(new Cell(cell.getKey(), cell.getValue(),
				build.blockEntities().get(cell.getKey())));
		}
		return cells;
	}

	// ------------------------------------------------------------------ client thread

	/** Watches the paste: progress, holding, waiting, the end, and the world going away. */
	static void clientTick(Minecraft minecraft) {
		Job running = job;
		if (running == null) {
			return;
		}
		if (minecraft.player == null || minecraft.level == null || minecraft.level != running.world
				|| !minecraft.hasSingleplayerServer()) {
			cancel(true);
			return;
		}
		if (running.cells == null) {
			if (!running.preparing.isDone()) {
				return;
			}
			try {
				running.cells = running.preparing.join();
			} catch (RuntimeException unsettled) {
				// Something in the build the settling could not read. The command route can still
				// place it, so it goes that way rather than not at all.
				job = null;
				CommandPasteSender.startByCommand(running.commands, running.faults, running.done);
				return;
			}
			CommandPasteSender.show(Component.literal("Placing sequence: 0/" + running.total()));
		}
		running.ticks++;
		boolean held = CommandPasteSender.held(minecraft);
		running.held = held;
		if (held) {
			if (running.heldFor++ % NAG == 0) {
				CommandPasteSender.show(Component.literal("Paste held at " + running.placed + "/"
						+ running.total() + " -- resumes when you close this")
					.withStyle(net.minecraft.ChatFormatting.YELLOW));
			}
			return;
		}
		running.heldFor = 0;
		int placed = running.placed;
		int total = running.total();
		if (placed >= total) {
			job = null;
			List<String> wrong = running.faults;
			CommandPasteSender.say(wrong.isEmpty()
				? Component.literal("Sequence placement complete: " + placed + "/" + total)
				: Component.literal("Placed " + placed + "/" + total + " -- " + wrong.size()
						+ " note" + (wrong.size() == 1 ? "" : "s") + " will be wrong. First: "
						+ wrong.get(0))
					.withStyle(net.minecraft.ChatFormatting.YELLOW));
			if (running.done != null) {
				running.done.run();
			}
			return;
		}
		BlockPos waiting = running.waitingAt;
		if (waiting != null) {
			if (running.ticks % NAG == 0) {
				int away = (int) Math.sqrt(minecraft.player.blockPosition().distSqr(waiting));
				CommandPasteSender.show(Component.literal("Paused at " + placed + "/" + total
						+ " -- walk to " + waiting.getX() + " " + waiting.getY() + " "
						+ waiting.getZ() + " (" + away + " blocks)")
					.withStyle(net.minecraft.ChatFormatting.YELLOW));
			}
			return;
		}
		if (running.ticks % PROGRESS_EVERY == 0) {
			CommandPasteSender.show(Component.literal("Placing sequence: " + placed + "/" + total));
		}
	}

	// ------------------------------------------------------------------ server thread

	private static void serverTick(MinecraftServer server) {
		Job running = job;
		if (running == null || running.cancelled || running.held || !(server instanceof IntegratedServer)) {
			return;
		}
		List<Cell> cells = running.cells;
		if (cells == null) {
			return;
		}
		ServerLevel level = server.getLevel(running.dimension);
		if (level == null) {
			return;
		}
		long budget = Math.clamp(TARGET_TICK_NANOS - server.getAverageTickTimeNanos(),
			MIN_BUDGET_NANOS, MAX_BUDGET_NANOS);
		long deadline = System.nanoTime() + budget;
		int index = running.placed;
		int sinceClock = 0;
		running.waitingAt = null;
		while (index < cells.size() && !running.cancelled) {
			Cell cell = cells.get(index);
			// Loaded here, on the server, and not generated: setting a block in a chunk that is not
			// loaded would load it on the spot, and a chunk nobody has visited is generated first.
			// That is a stall measured in tenths of a second, inside a tick, per chunk. So the paste
			// waits at the edge, in order, exactly as the command route does.
			if (!level.hasChunk(SectionPos.blockToSectionCoord(cell.at().getX()),
					SectionPos.blockToSectionCoord(cell.at().getZ()))) {
				running.waitingAt = cell.at();
				break;
			}
			place(level, cell);
			index++;
			running.placed = index;
			if (++sinceClock >= BLOCKS_PER_CLOCK_CHECK) {
				sinceClock = 0;
				if (System.nanoTime() >= deadline) {
					break;
				}
			}
		}
	}

	private static void place(ServerLevel level, Cell cell) {
		if (cell.data() == null) {
			level.setBlock(cell.at(), cell.state(), FLAGS);
			return;
		}
		// The block entity's data is read in by the game's own command code, which knows how to load
		// it in whatever form this version keeps it. One sign a build, so its re-settling is free.
		new BlockInput(cell.state(), java.util.Set.of(), cell.data()).place(level, cell.at(), FLAGS);
	}
}
