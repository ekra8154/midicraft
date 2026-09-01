package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;

/**
 * Takes the debug subcommands out of tab-completion, and puts them back, as the setting moves.
 *
 * <p>Brigadier's own answer to "should this be offered" is {@code requires}, and it does not work
 * here: Fabric copies the client commands into the tree that drives tab-completion when a world is
 * joined, and never consults {@code requires} again. A predicate reading a live setting therefore
 * offers names the parser will refuse, and hides a switch's effect until the next rejoin.</p>
 *
 * <p>So the nodes are registered unconditionally -- which is what makes them present to take away
 * -- and this detaches them from the live tree instead. Adding one back is Brigadier's own
 * {@code addChild}; taking one out is not, because Brigadier has no removal, so the three child
 * maps are cleared by reflection. The same shape the sibling mod in this directory uses.</p>
 *
 * <p>Cosmetic only, and deliberately so. Every debug command also refuses on its own when the
 * setting is off, so a failure here costs a name in a list rather than a working command.</p>
 */
public final class DebugCommandSuggestions {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Brigadier files a child in {@code children} and again in one of the other two. */
	private static final String[] CHILD_MAPS = {"children", "literals", "arguments"};
	private static final List<String> GATED = List.of("paste", "asciidiagram", "debugpaste");

	/** Nodes taken out of the current connection's tree, waiting to go back. */
	private static final Map<String, CommandNode<?>> detached = new HashMap<>();
	/**
	 * The tree the detached nodes came from, and the setting they were filed under.
	 *
	 * <p>Both are here to keep this idle. Reflection every tick to discover that nothing has moved
	 * would be a poor way to spend a tick, and the tree belongs to one connection -- nodes held
	 * across a disconnect belong to a tree nobody is using.</p>
	 */
	private static Object lastRoot;
	private static boolean lastEnabled;

	private DebugCommandSuggestions() {
	}

	public static void tick(Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			detached.clear();
			lastRoot = null;
			return;
		}
		CommandNode<?> root = connection.getCommands().getRoot();
		boolean enabled = FastNoteblocksConfig.get().debugCommandsEnabled();
		if (root == lastRoot && enabled == lastEnabled) {
			return;
		}
		if (root != lastRoot) {
			detached.clear();
		}
		lastRoot = root;
		lastEnabled = enabled;

		CommandNode<?> ours = root.getChild("midicraft");
		if (ours == null) {
			return;
		}
		for (String name : GATED) {
			if (enabled) {
				CommandNode<?> node = detached.remove(name);
				if (node != null) {
					attach(ours, node);
				}
			} else {
				CommandNode<?> node = detach(ours, name);
				if (node != null) {
					detached.put(name, node);
				}
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void attach(CommandNode<?> parent, CommandNode<?> node) {
		try {
			((CommandNode) parent).addChild(node);
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not put the {} suggestion back: {}", node.getName(), exception.toString());
		}
	}

	private static CommandNode<?> detach(CommandNode<?> parent, String name) {
		CommandNode<?> removed = null;
		try {
			for (String mapName : CHILD_MAPS) {
				Field field = CommandNode.class.getDeclaredField(mapName);
				field.setAccessible(true);
				Object out = ((Map<?, ?>) field.get(parent)).remove(name);
				if (out instanceof CommandNode<?> node) {
					removed = node;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			LOGGER.warn("Could not hide the {} suggestion: {}", name, exception.toString());
		}
		return removed;
	}
}
