package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * Commands for building a stated run of chords, off unless the setting is on.
 *
 * <p>What they are for is cutting a fault down. A wrong note in a song of nine thousand is found by
 * standing in front of it; understanding it means asking whether it is the chord's size, or the gap
 * before it, or how near the wall it fell -- and that means building the same three chords again
 * with one of those changed. Doing that through the composer takes minutes and moves more than the
 * one variable, because corridor width and floor count are sized from the whole song.</p>
 *
 * <pre>
 *   /fastnoteblockpaste 12 1 6 2 18                 three chords, corridor twelve wide, one floor
 *   /fastnoteblockpaste 36 1 30x4                   four chords of thirty
 *   /fastnoteblockpaste dry 24 2 5x8@4 30@1         reported, not placed
 *   /fastnoteblockpaste 36 3 down 12 18 30          the next wall a descent, first chord twelve off
 *   /fastnoteblockpaste 36 1 flat turning 12 30 5@1 5 5
 *                                                   the thirty-chord turnaround, as a single line
 *   /fastnoteblockpaste 40 1 7:7b 7:7h              the stacked seven over gold, then over glass
 * </pre>
 *
 * <p>Width and floors first, then the chords, which run to the end of the line. Between them may go
 * a wall shape -- {@code flat}, {@code up} or {@code down} -- and how far from that wall the first
 * chord stands. Without it the build starts where a song does: at the head of the first lane, on the
 * bottom floor, climbing. With it the walk begins as though it had already got there, which is the
 * only way to meet a descent without building every lane in front of it first.</p>
 *
 * <p>The dry form is the one that gets used most: it reports the faults, the size and where the
 * build would land without touching the world, so a dozen widths can be tried in as many seconds.
 * Both forms report the same line, so what you read in chat is what you would have got.</p>
 */
public final class DebugCommands {
	private DebugCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
			dispatcher.register(literal("fastnoteblockpaste")
				// Checked here rather than by skipping registration, so that turning the setting on
				// takes effect where it is turned on rather than at the next launch.
				.requires(source -> FastNoteblocksConfig.get().debugCommandsEnabled())
				.then(wall(false))
				.then(literal("dry").then(wall(true))));
			dispatcher.register(literal("asciidiagram")
				.requires(source -> FastNoteblocksConfig.get().debugCommandsEnabled())
				.then(corner("x1").then(corner("y1").then(corner("z1")
					.then(corner("x2").then(corner("y2").then(views())))))));
		});
	}

	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> corner(String name) {
		return RequiredArgumentBuilder.argument(name, IntegerArgumentType.integer(-30_000_000,
			30_000_000));
	}

	/**
	 * The last corner, and then which way the reader is facing.
	 *
	 * <p>Left off it is {@code top}, because that is the one anybody draws by hand and the one a
	 * corridor is easiest to count columns along.</p>
	 */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> views() {
		RequiredArgumentBuilder<FabricClientCommandSource, Integer> last = corner("z2")
			.executes(context -> diagram(context, AsciiDiagram.View.TOP));
		for (AsciiDiagram.View view : AsciiDiagram.View.values()) {
			last = last.then(literal(view.name().toLowerCase(java.util.Locale.ROOT))
				.executes(context -> diagram(context, view)));
		}
		return last;
	}

	/**
	 * Reads the box and offers it, rather than printing it.
	 *
	 * <p>Chat is sixty-odd characters wide and wraps without warning, so a diagram printed into it
	 * is unreadable and, worse, unreadable in a way that looks like the build is wrong. What goes in
	 * chat is the shape of the thing and two links; the diagram itself goes to the clipboard whole.</p>
	 */
	private static int diagram(CommandContext<FabricClientCommandSource> context,
			AsciiDiagram.View view) {
		FabricClientCommandSource source = context.getSource();
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			source.sendError(Component.literal("No world loaded."));
			return 0;
		}
		BlockPos from = new BlockPos(IntegerArgumentType.getInteger(context, "x1"),
			IntegerArgumentType.getInteger(context, "y1"),
			IntegerArgumentType.getInteger(context, "z1"));
		BlockPos to = new BlockPos(IntegerArgumentType.getInteger(context, "x2"),
			IntegerArgumentType.getInteger(context, "y2"),
			IntegerArgumentType.getInteger(context, "z2"));
		int volume = AsciiDiagram.volume(from, to);
		if (volume > AsciiDiagram.MAX_BLOCKS) {
			source.sendError(Component.literal("That is " + volume + " blocks. "
				+ AsciiDiagram.MAX_BLOCKS + " is as much as this will draw."));
			return 0;
		}
		source.sendFeedback(Component.literal("asciidiagram " + volume + " blocks, looking "
			+ view.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.GRAY)
			.append(copy(level, from, to, view, AsciiDiagram.Shape.CODE, "  [code]"))
			.append(copy(level, from, to, view, AsciiDiagram.Shape.TABLE, "  [table]")));
		return 1;
	}

	/** One clickable offer of the box in one shape, rendered now so the click cannot fail. */
	private static Component copy(ClientLevel level, BlockPos from, BlockPos to,
			AsciiDiagram.View view, AsciiDiagram.Shape shape, String label) {
		String drawn = AsciiDiagram.render(level::getBlockState, from, to, view, shape);
		return Component.literal(label).withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(drawn))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy "
				+ drawn.lines().count() + " lines to the clipboard"))));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	/**
	 * The two numbers, and then the chords, which run to the end of the line.
	 *
	 * <p>The chords come last because they are the part with spaces in, and Brigadier only lets the
	 * last argument hold those. The numbers could not follow them and be told apart from a chord: a
	 * spec ends in a number and so does everything else, so {@code 12 1 6 2 18} would have no reading
	 * that is obviously right. Two numbers first, then everything else is chords.</p>
	 */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> wall(boolean dry) {
		RequiredArgumentBuilder<FabricClientCommandSource, Integer> floors = RequiredArgumentBuilder
			.<FabricClientCommandSource, Integer>argument("floors",
				IntegerArgumentType.integer(1, 16))
			.then(RequiredArgumentBuilder
				.<FabricClientCommandSource, String>argument("chords",
					StringArgumentType.greedyString())
				.executes(context -> run(context, dry, null, 0, false)));
		// The wall shapes, each with its own distance to that wall. Literals rather than a word
		// argument so that leaving them off is unambiguous: a chord spec always opens with a digit
		// and a shape never does, and Brigadier tries its literal children first.
		for (String shape : List.of("flat", "up", "down")) {
			floors = floors.then(literal(shape)
				.then(colsThenChords(dry, shape, false))
				// And optionally with the lane already bending towards that wall, which is what a lane
				// in the middle of a song is and what no run of chords can be written to produce.
				.then(literal("turning").then(colsThenChords(dry, shape, true))));
		}
		return RequiredArgumentBuilder
			.<FabricClientCommandSource, Integer>argument("width",
				IntegerArgumentType.integer(1, 512))
			.then(floors);
	}

	/** How far from that wall the first chord stands, and then the chords themselves. */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> colsThenChords(
			boolean dry, String shape, boolean turning) {
		return RequiredArgumentBuilder
			.<FabricClientCommandSource, Integer>argument("columnsToWall",
				IntegerArgumentType.integer(1, 512))
			.then(RequiredArgumentBuilder
				.<FabricClientCommandSource, String>argument("chords",
					StringArgumentType.greedyString())
				.executes(context -> run(context, dry, shape,
					IntegerArgumentType.getInteger(context, "columnsToWall"), turning)));
	}

	/**
	 * The seed that makes the next wall the shape asked for.
	 *
	 * <p>Read straight off {@code turnCost}, which calls the step above {@code floor + climb} and
	 * counts it a staircase when it lands inside the floors. So: bottom going up is a climb, top
	 * going down is a descent, and top going up is a step that lands outside the build, which is the
	 * flat turn. There is no fourth combination, which is why there are three words.</p>
	 */
	private static SongBuilder.WalkStart seed(String shape, int floors, int width,
			int columnsToWall, boolean turning) {
		// The wall is two columns inside the width -- two of it go on the fold itself -- so a chord
		// asked to stand twelve columns from the wall starts twelve short of there, not of the width.
		int column = Math.max(0, width - 2 - columnsToWall);
		return switch (shape) {
			case "up" -> new SongBuilder.WalkStart(column, 0, 1, turning);
			case "down" -> new SongBuilder.WalkStart(column, floors - 1, -1, turning);
			default -> new SongBuilder.WalkStart(column, floors - 1, 1, turning);
		};
	}

	private static int run(CommandContext<FabricClientCommandSource> context, boolean dry,
			String shape, int columnsToWall, boolean turning) {
		FabricClientCommandSource source = context.getSource();
		String spec = StringArgumentType.getString(context, "chords");
		int wall = IntegerArgumentType.getInteger(context, "width");
		int floors = IntegerArgumentType.getInteger(context, "floors");
		List<DebugChords.Chord> chords;
		SongBuilder.PastePlan plan;
		try {
			if (shape != null && !"flat".equals(shape) && floors < 2) {
				throw new IllegalArgumentException("A " + shape + " staircase needs at least two "
					+ "floors. On one floor every wall is a flat turn.");
			}
			chords = DebugChords.parse(spec, DebugChords.DEFAULT_GAP);
			// The mode the paste button would use, so that what this builds is what a song would get.
			SongBuilder.PasteMode mode = pasteMode();
			plan = SongBuilder.createPastePlan(origin(source), DebugChords.notes(chords), mode,
				new SongBuilder.BuildLimits(FastNoteblocksConfig.get().maxBuildFloors(), wall,
					floors),
				shape == null ? SongBuilder.WalkStart.HEAD : seed(shape, floors, wall, columnsToWall, turning));
		} catch (IllegalArgumentException refused) {
			source.sendError(Component.literal(refused.getMessage()));
			return 0;
		}
		source.sendFeedback(Component.literal(DebugChords.describe(chords) + " -> "
			+ plan.mode().label() + ", " + plan.width() + " long, " + plan.depth() + " deep, "
			+ plan.height() + " high, " + plan.commands().size() + " blocks")
			.withStyle(ChatFormatting.GRAY));
		// Where the walls came out, in the world, which cannot be worked out from where you are
		// standing: the plan slides after it is walked so that nothing lands behind you, and the
		// wall the walk measured against moves with it. A breach is a lane past one of these, so
		// reading one off the blocks means knowing which column it was meant to stop in.
		source.sendFeedback(Component.literal("  walls at x=" + plan.nearWall() + " and x="
			+ plan.farWall() + ", " + (plan.farWall() - plan.nearWall()) + " columns between; "
			+ "first chord opens at x=" + firstChordX(plan))
			.withStyle(ChatFormatting.GRAY));
		// Every fault, not a count of them. There are never many for a spec small enough to be worth
		// typing, and the whole point of building one is to read what it says.
		for (String fault : plan.faults()) {
			source.sendFeedback(Component.literal("  " + fault).withStyle(ChatFormatting.YELLOW));
		}
		if (plan.faults().isEmpty()) {
			source.sendFeedback(Component.literal("  no faults").withStyle(ChatFormatting.GREEN));
		}
		if (dry) {
			return plan.faults().size() + 1;
		}
		if (CommandPasteSender.isRunning()) {
			source.sendError(Component.literal("A paste is already running. Wait for it to finish."));
			return 0;
		}
		CommandPasteSender.start(plan.commands(), List.of());
		return plan.faults().size() + 1;
	}

	/** Where a real paste would land, so a debug build stands where a song would. */
	/**
	 * The lowest x any block of the build stands in, which is where the first chord opens.
	 *
	 * <p>Read off the commands rather than tracked, because the interesting number is where the
	 * build actually starts and not where the walk thought it would.</p>
	 */
	private static int firstChordX(SongBuilder.PastePlan plan) {
		int lowest = Integer.MAX_VALUE;
		for (String command : plan.commands()) {
			lowest = Math.min(lowest, Integer.parseInt(command.split(" ")[1]));
		}
		return lowest == Integer.MAX_VALUE ? 0 : lowest;
	}

	private static BlockPos origin(FabricClientCommandSource source) {
		return source.getPlayer().blockPosition().relative(Direction.EAST).immutable();
	}

	private static SongBuilder.PasteMode pasteMode() {
		try {
			return SongBuilder.PasteMode.valueOf(FastNoteblocksConfig.get().pasteMode());
		} catch (IllegalArgumentException unknown) {
			return SongBuilder.PasteMode.ULTRA_COMPACT_LANE;
		}
	}
}
