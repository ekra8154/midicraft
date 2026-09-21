package com.midicraft.client.compat;

import com.midicraft.client.MidicraftConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

/**
 * Sends a paste to the server a few blocks a tick, and does not lose any of them.
 *
 * <p>A {@code /setblock} into a chunk the server does not hold is refused, and the refusal is a
 * line of chat. Nothing else happens: the command is gone, the block is never placed, and a build
 * longer than the loaded region around you comes out with holes in it that nothing afterwards can
 * find. Which is a real shape for a build to have -- the lane modes run hundreds of blocks in a
 * straight line, and a render distance of 12 reaches 192.</p>
 *
 * <p>So a command is not sent until the world it names is one this client is holding, and until
 * then the whole queue waits. Two things follow from that, and both are the point:</p>
 *
 * <ul>
 *   <li><b>Order is never disturbed.</b> A command's position in the queue is a dependency and not
 *       a preference -- dust set into air breaks the moment it lands, and the title sign is
 *       inserted directly after the block it hangs on for exactly that reason. Waiting keeps every
 *       one of those relationships; skipping ahead to whatever happens to be nearby would not.</li>
 *   <li><b>The gate can stall when it did not have to.</b> The question it asks is "am I holding
 *       this?", which is not the same as "would the server accept it?" -- another player standing
 *       in that region, or a forceload, keeps chunks loaded that this client is never sent. It
 *       errs the only way that is safe to err.</li>
 * </ul>
 *
 * <p>The gate is a prediction. What makes the guarantee is reading the world back afterwards, which
 * is possible for exactly the blocks the gate allowed: the chunks this client holds are the chunks
 * it can see. Anything that does not read back is sent once more, and if it fails twice it is
 * reported rather than fought over -- one retry answers a dropped command, and a second failure is
 * the world disagreeing with you.</p>
 */
public final class CommandPasteSender {

	/**
	 * How far inside the loaded edge a block must sit, in chunks, before it is sent.
	 *
	 * <p>Insisting on the whole 3x3 around the target buys a block of margin against the edge
	 * without ever needing to know where the edge is. Which matters, because the number nobody can
	 * be told: your render distance is a cap and not a promise, a server's view distance may be
	 * shorter, and a chunk at the boundary may already be gone on the server while this client is
	 * still a packet away from hearing about it. The loaded set is the only thing that is true, and
	 * one chunk of slack turns "true now" into "still true when the packet lands".</p>
	 */
	private static final int MARGIN = 1;

	/** Ticks between placing a block and believing what the world says about it. */
	private static final int SETTLE = 20;

	/** Block reads a tick, so that a large build's checking cannot become the frame. */
	private static final int CHECKS_PER_TICK = 4096;

	/** How often the overlay is redrawn while paused, in ticks. */
	private static final int NAG = 40;

	/** How many times being told about blocks left unchecked is telling, and not nagging. */
	private static final int NAGS = 5;

	private static final Deque<Placement> QUEUE = new ArrayDeque<>();

	/**
	 * Sent, and not yet read back. Keyed by position because the world holds one block per cell and
	 * the last command to name it is the one that decides: the marking pass writes its lanterns over
	 * cells the build already filled, and only the lantern is what should be there.
	 */
	private static final Map<BlockPos, Sent> PENDING = new LinkedHashMap<>();

	/** Positions already given their one retry. */
	private static final Set<BlockPos> RETRIED = new HashSet<>();

	/**
	 * The world the paste was planned against, held by identity rather than by name.
	 *
	 * <p>A dimension change and a disconnect both replace this object, so one comparison catches
	 * both, and both cancel: a coordinate means something else in the Nether, and a paste that
	 * resumed after a reconnect would be pasting into whatever is standing there now.</p>
	 */
	private static ClientLevel world;

	private static int total;
	private static int sent;
	private static int repaired;
	/** Commands the world had already satisfied, so nothing was sent for them. */
	private static int skipped;
	private static int changed;
	private static BlockPos firstChanged;
	private static long now;
	/** Commands owed but not yet whole, for the rates below one a tick. */
	private static double credit;
	private static int nags;
	/** Ticks spent waiting behind a screen, which is also the flag that it is waiting. */
	private static int heldFor;
	private static boolean announced;

	/**
	 * Said once the last block is down, rather than before the first.
	 *
	 * <p>A debug paste is a few hundred {@code /setblock} calls and every one of them prints itself
	 * to chat, so a report sent before the paste has scrolled out of reach by the time the build
	 * exists. What the report says is only worth reading against the thing it describes.</p>
	 */
	private static Runnable whenDone;

	private CommandPasteSender() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(CommandPasteSender::tick);
	}

	/** One command, kept with the position it names so the sender can gate it and check it. */
	record Placement(String command, BlockPos at, String block) {
	}

	/** A placement and the tick it went out on, which is when its settling time starts. */
	private record Sent(Placement placement, long tick) {
	}

	static boolean isRunning() {
		return !QUEUE.isEmpty();
	}

	static void start(List<String> commands) {
		start(commands, List.of());
	}

	/**
	 * @param faults places the finished machine is known to be wrong at. Shown instead of the
	 *     progress line, and kept on screen, because a build worth looking at is one you have to be
	 *     told to look at -- it goes up, it looks right, and it plays one note in the wrong bar.
	 */
	static void start(List<String> commands, List<String> faults) {
		start(commands, faults, null);
	}

	/**
	 * @param done run once the last command has been sent, or at once if there are none. Not run if
	 *     the paste is cancelled: it describes a finished machine, and a cancelled one is not that.
	 */
	/**
	 * What is wrong with the build being pasted, kept until the paste is over.
	 *
	 * <p>Empty for a clean one, and emptied again the moment it is said, so that a report belonging
	 * to one paste cannot surface at the end of the next.</p>
	 */
	private static List<String> heldReport = List.of();

	static void start(List<String> commands, List<String> faults, Runnable done) {
		cancel(false);
		for (String command : commands) {
			QUEUE.addLast(read(command));
		}
		total = QUEUE.size();
		sent = 0;
		repaired = 0;
		skipped = 0;
		changed = 0;
		firstChanged = null;
		now = 0;
		credit = 0;
		nags = 0;
		heldFor = 0;
		announced = false;
		heldReport = List.of();
		whenDone = done;
		world = Minecraft.getInstance().level;
		if (QUEUE.isEmpty()) {
			finish();
			return;
		}
		// Held to the end rather than said here. The overlay line used to carry the report, and it
		// was true and unread: the next tick writes "Placing sequence: 1/4531" over the top of it
		// and nothing brings it back, so the one thing worth reading was the one thing guaranteed
		// to be missed. At the end it goes to chat with the completion line, where it stays.
		heldReport = List.copyOf(faults);
		show(Component.literal("Placing sequence: 0/" + total));
	}

	static void cancel(boolean notify) {
		if (QUEUE.isEmpty() && PENDING.isEmpty()) {
			return;
		}
		boolean placing = !QUEUE.isEmpty();
		QUEUE.clear();
		PENDING.clear();
		RETRIED.clear();
		world = null;
		if (notify && placing) {
			show(Component.literal("Sequence placement cancelled at " + sent + "/" + total));
		}
		total = 0;
		sent = 0;
		repaired = 0;
		skipped = 0;
		changed = 0;
		firstChanged = null;
		credit = 0;
		nags = 0;
		heldFor = 0;
		heldReport = List.of();
		whenDone = null;
	}

	private static void tick(Minecraft minecraft) {
		if (QUEUE.isEmpty() && PENDING.isEmpty()) {
			// Nothing is live, so let go of the world. Holding it would keep a disconnected level
			// alive for as long as the game runs.
			world = null;
			return;
		}
		if (minecraft.player == null || minecraft.player.connection == null
				|| minecraft.level == null || minecraft.level != world) {
			cancel(true);
			return;
		}
		if (held(minecraft)) {
			// Before the clock, so nothing ages while you are in there: a block sent a moment before
			// you opened the menu has not been settling, it has been sitting in a frozen world.
			// Reading it back now would find whatever the paused server had got to and call the rest
			// dropped.
			if (heldFor++ % NAG == 0) {
				show(Component.literal("Paste held at " + sent + "/" + total
						+ " -- resumes when you close this")
					.withStyle(net.minecraft.ChatFormatting.YELLOW));
			}
			return;
		}
		if (heldFor > 0) {
			heldFor = 0;
			show(Component.literal("Placing sequence: " + sent + "/" + total));
		}
		now++;
		place(minecraft, minecraft.level);
		check(minecraft, minecraft.level);
		// Here rather than inside the checking, because the last block to be read back is not always
		// read back last: a long pause empties the pending list while the queue still has work, and
		// a report left to the checker would be missed on exactly that run.
		if (QUEUE.isEmpty() && PENDING.isEmpty()) {
			report();
		}
	}

	/** Sends what it can, and stops dead at the first block that is not there to be written to. */
	private static void place(Minecraft minecraft, ClientLevel level) {
		if (QUEUE.isEmpty()) {
			return;
		}
		// Below one a tick the rate is a wait rather than a count, so it is banked and spent. Never
		// more than a tick's worth is banked: a paste that paused for two minutes at the edge of the
		// world would otherwise resume by firing every command it had been owed at once, which is
		// the disconnect the slow rates exist to avoid.
		double rate = MidicraftConfig.get().commandsPerTick();
		credit = bank(credit, rate);
		boolean waiting = false;
		int looked = 0;
		while (!QUEUE.isEmpty() && looked++ < CHECKS_PER_TICK) {
			Placement next = QUEUE.peekFirst();
			if (next.at() != null && !writable(level, next.at())) {
				waiting = true;
				break;
			}
			// Costs no credit, because it costs no packet. A rate is a limit on what the server is
			// asked to do, and this asks it for nothing.
			if (alreadyAir(level, next)) {
				QUEUE.removeFirst();
				sent++;
				skipped++;
				continue;
			}
			if (credit < 1) {
				break;
			}
			QUEUE.removeFirst();
			credit--;
			minecraft.player.connection.sendCommand(next.command());
			sent++;
			if (next.at() != null) {
				// Removed before it is put back so that the newest write to a cell is also the last
				// one checked: a LinkedHashMap leaves a re-used key where it first appeared.
				PENDING.remove(next.at());
				PENDING.put(next.at(), new Sent(next, now));
			}
		}
		if (QUEUE.isEmpty()) {
			if (!announced) {
				announced = true;
				// Both facts on one line: that it finished, and what is wrong with what it built.
				// The count is kept even when there is a report, because "complete" is the thing a
				// player who walked away comes back to look for -- which is also why it goes to
				// chat, where it waits for them, rather than to an overlay that fades.
				List<String> wrong = heldReport;
				heldReport = List.of();
				say(wrong.isEmpty()
					? Component.literal("Sequence placement complete: " + sent + "/" + total
						+ (skipped == 0 ? "" : " (" + skipped + " already air)"))
					: Component.literal("Placed " + sent + "/" + total + " -- " + wrong.size()
							+ " note" + (wrong.size() == 1 ? "" : "s") + " will be wrong. First: "
							+ wrong.get(0))
						.withStyle(net.minecraft.ChatFormatting.YELLOW));
				finish();
			}
			return;
		}
		if (waiting) {
			if (now % NAG == 0) {
				waitingAt(minecraft, QUEUE.peekFirst().at());
			}
			return;
		}
		if (now % 10 == 0) {
			show(Component.literal("Placing sequence: " + sent + "/" + total));
		}
	}

	/**
	 * Reads back what has settled, and sends anything missing once more.
	 *
	 * <p>Blocks are compared by their id and never by their state. Almost everything this places
	 * rewrites its own properties the moment it lands -- dust picks up its connections and its
	 * power, a note block takes its instrument from what is under it, a repeater and a bulb light
	 * up -- so a state comparison would report thousands of correct blocks as wrong. An id
	 * comparison has no false alarm worth having: the only way it misses a refused command is if
	 * the block that was already there is the block that was wanted, and then the cell is right
	 * anyway.</p>
	 */
	private static void check(Minecraft minecraft, ClientLevel level) {
		if (PENDING.isEmpty()) {
			return;
		}
		int budget = CHECKS_PER_TICK;
		boolean settled = false;
		List<Placement> again = new ArrayList<>();
		Iterator<Map.Entry<BlockPos, Sent>> entries = PENDING.entrySet().iterator();
		while (entries.hasNext() && budget > 0) {
			Sent pending = entries.next().getValue();
			if (now - pending.tick() < SETTLE) {
				// In send order, so everything past here is younger still.
				break;
			}
			settled = true;
			BlockPos at = pending.placement().at();
			// Readable, not writable: the margin exists to survive the trip to the server and back,
			// and a block already written is one this client can simply look at.
			if (!level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(at.getX()),
					SectionPos.blockToSectionCoord(at.getZ()))) {
				continue;
			}
			budget--;
			entries.remove();
			if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString()
					.equals(pending.placement().block())) {
				continue;
			}
			if (RETRIED.add(at)) {
				again.add(pending.placement());
			} else {
				changed++;
				if (firstChanged == null) {
					firstChanged = at;
				}
			}
		}
		if (!again.isEmpty()) {
			// At the back, in the order they were first sent, so that a support still precedes what
			// stands on it.
			QUEUE.addAll(again);
			total += again.size();
			repaired += again.size();
		}
		if (QUEUE.isEmpty() && !PENDING.isEmpty() && settled && budget == CHECKS_PER_TICK
				&& nags < NAGS && now % NAG == 0) {
			// Everything left has settled and none of it could be read, which only happens when the
			// build reaches somewhere you are not. Said a few times and then dropped: the checking
			// stays armed either way, so walking back still finishes it and still reports, and an
			// overlay that repeats forever is one you stop reading.
			nags++;
			uncheckedAt(minecraft);
		}
	}

	/**
	 * A tick's worth of rate added to what was owed, capped at what a single tick may spend.
	 *
	 * <p>The cap is the whole of it. Without one, a paste held at the edge of the world for two
	 * minutes would come back owed thousands of commands and send every one of them on the tick you
	 * walked into range -- which is the disconnect that the rates below one a tick exist to
	 * avoid.</p>
	 */
	static double bank(double credit, double rate) {
		return Math.min(credit + rate, Math.max(rate, 1));
	}

	/**
	 * Screens a paste waits behind rather than building through.
	 *
	 * <p>The pause menu because in singleplayer it stops the server: the commands keep leaving the
	 * client, nothing at the other end is processing them, and they arrive in one lump when you come
	 * back. Which is the burst the slow send rates exist to avoid, arrived at by a different road.
	 * The composer because a build going up behind an editor is a build nobody is watching, and the
	 * whole reason a paste is worth pausing is that it wants you nearby.</p>
	 *
	 * <p>Only holds it. Cancelling is something you ask for, and it is still one button away.</p>
	 */
	private static boolean held(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		return screen instanceof PauseScreen || screen instanceof ComposerScreen;
	}

	/**
	 * Whether a command would write air into a cell that is already exactly air.
	 *
	 * <p>Worth catching because it is not a rounding error: a build writes air over every note block,
	 * because that is what keeps a note audible, and in the open sky that is a fifth to nearly a
	 * third of every command it sends -- more commands than it spends on note blocks. Guardian sends
	 * close to twenty-five thousand of them. Each one is refused by the server with "Could not set
	 * the block", which is what {@code LevelChunk.setBlockState} says when the state asked for is the
	 * state already there, and each one takes a line of chat and a slot in the send rate to change
	 * nothing.</p>
	 *
	 * <p>Safe to drop only because no cell of a build is ever written twice with different blocks --
	 * the plan is a map, and a second claim on a cell is a collision it reports rather than a
	 * rewrite. If a build could lay stone and then clear it, this would have to know that the air was
	 * meant to undo something and not merely to describe what is already there.</p>
	 *
	 * <p>Exactly {@code minecraft:air} on both sides, never {@code isAir()}. Cave air and void air are
	 * different blocks, so writing air over one of them is a real change and goes out. That is also
	 * what makes this safe behind the gate rather than dangerous in front of it: a chunk this client
	 * does not hold reads as {@code void_air} -- from {@code EmptyLevelChunk}, and out of build height
	 * from {@code Level} itself -- so an unloaded cell can never look like one worth skipping.</p>
	 */
	private static boolean alreadyAir(ClientLevel level, Placement next) {
		return next.at() != null
			&& "minecraft:air".equals(next.block())
			&& level.getBlockState(next.at()).is(Blocks.AIR);
	}

	/** Whether a position is far enough inside what this client holds to be worth writing to. */
	private static boolean writable(ClientLevel level, BlockPos at) {
		int chunkX = SectionPos.blockToSectionCoord(at.getX());
		int chunkZ = SectionPos.blockToSectionCoord(at.getZ());
		for (int x = chunkX - MARGIN; x <= chunkX + MARGIN; x++) {
			for (int z = chunkZ - MARGIN; z <= chunkZ + MARGIN; z++) {
				if (!level.getChunkSource().hasChunk(x, z)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Splits a command into the position it writes to and the block it writes there.
	 *
	 * <p>Every command a paste is made of is a {@code setblock}, whose three coordinates are whole
	 * numbers before anything else on the line -- so the split is safe even for the title sign,
	 * whose block state carries a name with spaces in it. Anything that does not read that way is
	 * kept and sent unexamined rather than dropped: unable to gate is not the same as unable to
	 * place, and a paste is not the place to be strict about a command somebody added later.</p>
	 */
	static Placement read(String command) {
		String[] parts = command.split(" ", 6);
		if (parts.length < 5 || !parts[0].equals("setblock")) {
			return new Placement(command, null, null);
		}
		try {
			BlockPos at = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3]));
			return new Placement(command, at, blockId(parts[4]));
		} catch (NumberFormatException notAPosition) {
			return new Placement(command, null, null);
		}
	}

	/** The block's id alone, with its state and its data left behind, namespaced as a lookup is. */
	private static String blockId(String block) {
		int end = block.length();
		for (int i = 0; i < block.length(); i++) {
			if (block.charAt(i) == '[' || block.charAt(i) == '{') {
				end = i;
				break;
			}
		}
		String name = block.substring(0, end);
		return name.indexOf(':') < 0 ? "minecraft:" + name : name;
	}

	private static void waitingAt(Minecraft minecraft, BlockPos at) {
		if (at == null) {
			return;
		}
		int away = (int) Math.sqrt(minecraft.player.blockPosition().distSqr(at));
		show(Component.literal("Paused at " + sent + "/" + total + " -- walk to "
				+ at.getX() + " " + at.getY() + " " + at.getZ() + " (" + away + " blocks)")
			.withStyle(net.minecraft.ChatFormatting.YELLOW));
	}

	private static void uncheckedAt(Minecraft minecraft) {
		BlockPos at = PENDING.values().iterator().next().placement().at();
		int away = (int) Math.sqrt(minecraft.player.blockPosition().distSqr(at));
		show(Component.literal(PENDING.size() + " blocks unchecked -- walk to "
				+ at.getX() + " " + at.getY() + " " + at.getZ() + " (" + away + " blocks)")
			.withStyle(net.minecraft.ChatFormatting.YELLOW));
	}

	/** Said only when the reading back found something, since finding nothing is the ordinary case. */
	private static void report() {
		if (changed > 0) {
			say(Component.literal(changed + " block" + (changed == 1 ? "" : "s")
					+ " changed after placing. First: " + firstChanged.getX() + " "
					+ firstChanged.getY() + " " + firstChanged.getZ())
				.withStyle(net.minecraft.ChatFormatting.YELLOW));
		} else if (repaired > 0) {
			say(Component.literal("Replaced " + repaired + " block"
				+ (repaired == 1 ? "" : "s") + " the server dropped"));
		}
		repaired = 0;
		changed = 0;
		firstChanged = null;
		RETRIED.clear();
	}

	/** Cleared before the callback runs, so that a report which pastes again is not fighting it. */
	private static void finish() {
		Runnable done = whenDone;
		whenDone = null;
		if (done != null) {
			done.run();
		}
	}

	private static void show(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.gui.hud.setOverlayMessage(message, true);
		}
	}

	/**
	 * A line for chat rather than the overlay, for what is said once a paste is over.
	 *
	 * <p>The overlay is right for progress, which is rewritten every half second and worth nothing
	 * a moment later. The outcome is the opposite: said once, and read whenever you get back to it,
	 * so it goes where it is kept.</p>
	 */
	private static void say(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(message);
		}
	}
}
