package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.phys.Vec3;

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
 *   /fastnoteblocks paste 12 1 6 2 18                 three chords, corridor twelve wide, one floor
 *   /fastnoteblocks paste 36 1 30x4                   four chords of thirty
 *   /fastnoteblocks paste dry 24 2 5x8@4 30@1         reported, not placed
 *   /fastnoteblocks paste 36 3 down 12 18 30          the next wall a descent, first chord twelve off
 *   /fastnoteblocks paste 28 5 up 25x8                a whole lane ending in a climb, every chord
 *                                                   placed where the walk itself wants it
 *   /fastnoteblocks paste 36 1 flat turning 12 30 5@1 5 5
 *                                                   the thirty-chord turnaround, as a single line
 *   /fastnoteblocks paste 40 1 7:7b 7:7h              the stacked seven over gold, then over glass
 * </pre>
 *
 * <p>Width and floors first, then the chords, which run to the end of the line. Between them may go
 * a wall shape -- {@code flat}, {@code up} or {@code down} -- and, optionally, how far from that
 * wall the first chord stands. With the distance the walk begins as though it had already got
 * there, which pins where the first chord lands -- the right tool for rebuilding one fault.
 * Without it the lane starts at its own origin with that wall ahead and the walk places the
 * chords itself, which is the only form that can sim a shape the walk has to choose -- a padded
 * approach, a cut at whatever room the lane arrives at. (A first token that is a lone integer
 * still binds as the distance; a single chord with no distance is said as {@code 18x1} or with a
 * gap, {@code 18@4}.) With no shape at all the build starts where a song does.</p>
 *
 * <p>The dry form is the one that gets used most: it reports the faults, the size and where the
 * build would land without touching the world, so a dozen widths can be tried in as many seconds.
 * Both forms report the same line, so what you read in chat is what you would have got.</p>
 *
 * <p>And the other half of the same job, reading a build rather than making one:</p>
 *
 * <pre>
 *   /fastnoteblocks asciidiagram 13 72 108 15 76 113 east    that box, sliced west to east
 *   /fastnoteblocks asciidiagram ~-8 ~ ~-8 ~8 ~4 ~8          the ground around you, sliced downwards
 *   /fastnoteblocks asciidiagram ~-8 ~ ~-8 ~8 ~4 ~8 top south      the same, turned so south is up the page
 *   /fastnoteblocks asciidiagram ~-8 ~ ~-8 ~8 ~4 ~8 top south true and with every note block saying which note
 *   /fastnoteblocks asciidiagram ~-8 ~ ~-8 ~8 ~4 ~8 south false true    signs become footnotes: S1 in the grid,
 *                                                                        the words in the legend
 * </pre>
 *
 * <p>A view from above or below is a map and a map can be turned, so those two take a direction for
 * which way is up the page; north if it is left off, which is what every diagram in this repo was
 * drawn with. The views from the side have nothing to decide -- up the page is up in the world --
 * and take no such word. The two boolean arguments are off by default: the first numbers the note
 * blocks ({@code NB} is two characters and {@code N01} is three, and it widens every column in the
 * box), the second turns signs with writing into footnotes -- which is how a note left in the world
 * beside a build travels with the diagram of it.</p>
 *
 * <p>Two corners and a point of view, the corners taken the way {@code /setblock} takes them -- so
 * looking at one and pressing tab fills it in. Chat gets the size and two links; clicking either
 * copies the diagram itself to the clipboard, since chat is too narrow to print one into and wraps
 * without saying so, which reads as a broken build rather than a broken line.</p>
 */
public final class DebugCommands {
	private DebugCommands() {
	}

	/**
	 * The paster, as {@code /fastnoteblocks paste}.
	 *
	 * <p>Handed to {@link ComposerCommand} to hang under the mod's own command rather than
	 * registered here, so the mod owns one name in a list everybody's mods are competing for. The
	 * gate stays on this node: the Composer and the settings are how anyone uses the mod, and
	 * neither should disappear because a debugging switch is off.</p>
	 */
	static LiteralArgumentBuilder<FabricClientCommandSource> pasteCommand() {
		LiteralArgumentBuilder<FabricClientCommandSource> paste = literal("paste")
			// Checked here rather than by skipping registration, so that turning the setting on
			// takes effect where it is turned on rather than at the next launch.
			.requires(source -> FastNoteblocksConfig.get().debugCommandsEnabled())
			.then(wall(false, null))
			.then(literal("dry").then(wall(true, null)));
		// And the same again under each paster's own name, so a spec can be built both ways
		// without going to the config screen and back. Left off it is whatever the paste button
		// would use, and that default is deliberately not spelled out here: the command exists to
		// build what a song would get, so the two must not be able to disagree.
		//
		// Literals rather than a word argument, for the reason the wall shapes are literals: a
		// chord spec always opens with a digit and a mode name never does, so leaving it off is
		// unambiguous and Brigadier tries its literal children first.
		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			String name = mode.name().toLowerCase(java.util.Locale.ROOT);
			paste = paste.then(literal(name)
				.then(wall(false, mode))
				.then(literal("dry").then(wall(true, mode))));
		}
		// And the two names anybody actually says. The ultra lanes are v1 and v2 in conversation
		// and in every commit message, and nobody is going to type ultra_compact_lane_v2 twice.
		paste = paste
			.then(literal("v1")
				.then(wall(false, SongBuilder.PasteMode.ULTRA_COMPACT_LANE))
				.then(literal("dry")
					.then(wall(true, SongBuilder.PasteMode.ULTRA_COMPACT_LANE))))
			.then(literal("v2")
				.then(wall(false, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2))
				.then(literal("dry")
					.then(wall(true, SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2))));
		return paste;
	}

	/** The diagram, as {@code /fastnoteblocks asciidiagram}. */
	static LiteralArgumentBuilder<FabricClientCommandSource> asciiDiagramCommand() {
		return literal("asciidiagram")
			.requires(source -> FastNoteblocksConfig.get().debugCommandsEnabled())
			.then(corner("from").then(views()));
	}

	/**
	 * The collision marking, as {@code /fastnoteblocks debugpaste}.
	 *
	 * <p>A toggle rather than an argument to the paste, because the builds worth looking at this
	 * way are songs pasted from the build screen, which takes no arguments. On its own it says what
	 * the colours mean rather than toggling: reading a marked build is the thing you do far more
	 * often than turning the marking on, and a toggle you have to read the state of afterwards is a
	 * toggle that gets pressed twice by accident.</p>
	 */
	static LiteralArgumentBuilder<FabricClientCommandSource> debugPasteCommand() {
		return literal("debugpaste")
			.requires(source -> FastNoteblocksConfig.get().debugCommandsEnabled())
			.executes(context -> colourKey(context.getSource()))
			.then(literal("on").executes(context -> debugPaste(context.getSource(), true)))
			.then(literal("off").executes(context -> debugPaste(context.getSource(), false)));
	}

	/**
	 * A corner, as the same argument {@code /setblock} takes.
	 *
	 * <p>Which is worth it for the suggestions alone. Fabric mixes its client command source into
	 * {@code ClientSuggestionProvider}, whose {@code getRelevantCoordinates} hands back whatever
	 * block the crosshair is on -- so the corners of the box can be filled in by looking at them and
	 * pressing tab, rather than read off the debug screen and typed. {@code ~} works for the same
	 * reason. Absolute numbers parse exactly as they did when this took six of them.</p>
	 */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Coordinates> corner(
			String name) {
		return RequiredArgumentBuilder.argument(name, BlockPosArgument.blockPos());
	}

	/**
	 * The far corner, and then which way the reader is facing.
	 *
	 * <p>Left off it is {@code top}, because that is the one anybody draws by hand and the one a
	 * corridor is easiest to count columns along.</p>
	 */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Coordinates> views() {
		RequiredArgumentBuilder<FabricClientCommandSource, Coordinates> last = corner("to")
			.executes(context -> diagram(context, AsciiDiagram.View.TOP, null, false, false))
			.then(numbered(AsciiDiagram.View.TOP, null));
		for (AsciiDiagram.View view : AsciiDiagram.View.values()) {
			LiteralArgumentBuilder<FabricClientCommandSource> named =
				literal(view.name().toLowerCase(java.util.Locale.ROOT))
					.executes(context -> diagram(context, view, null, false, false))
					.then(numbered(view, null));
			// Only the two views along y. A view from the side has up the page pinned to up in the
			// world, so offering it a compass direction would be offering a rotation it cannot do.
			if (view.flat()) {
				for (Direction up : Direction.Plane.HORIZONTAL) {
					named = named.then(literal(up.getName())
						.executes(context -> diagram(context, view, up, false, false))
						.then(numbered(view, up)));
				}
			}
			last = last.then(named);
		}
		return last;
	}

	/** The tail every form shares: whether note blocks say which note they play. */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Boolean> numbered(
			AsciiDiagram.View view, Direction up) {
		return RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument(
				"notes", BoolArgumentType.bool())
			.executes(context -> diagram(context, view, up,
				BoolArgumentType.getBool(context, "notes"), false))
			.then(signed(view, up));
	}

	/**
	 * And after it, whether signs with writing on them become footnotes: {@code S1} in the grid
	 * and the words in the legend. Off unless asked for, so a diagram of a build that happens to
	 * have signs near it does not fill its legend with them.
	 */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Boolean> signed(
			AsciiDiagram.View view, Direction up) {
		return RequiredArgumentBuilder.<FabricClientCommandSource, Boolean>argument(
				"signs", BoolArgumentType.bool())
			.executes(context -> diagram(context, view, up,
				BoolArgumentType.getBool(context, "notes"),
				BoolArgumentType.getBool(context, "signs")));
	}

	/**
	 * Where a corner actually is, resolved without a server.
	 *
	 * <p>{@code BlockPosArgument.getBlockPos} wants a {@code CommandSourceStack}, which a client
	 * command has not got. It does not need one: the parsed value is a record of three coordinates
	 * that are each either absolute or an offset, and the client knows where the player is standing.
	 * Caret coordinates are the one form that also wants the anchor and the facing, and they are no
	 * use for picking the corners of a box, so they are refused rather than half-supported.</p>
	 */
	private static BlockPos corner(FabricClientCommandSource source,
			CommandContext<FabricClientCommandSource> context, String name) {
		if (!(context.getArgument(name, Coordinates.class) instanceof WorldCoordinates corner)) {
			return null;
		}
		Vec3 standing = source.getPosition();
		return BlockPos.containing(corner.x().get(standing.x), corner.y().get(standing.y),
			corner.z().get(standing.z));
	}

	/**
	 * Reads the box and offers it, rather than printing it.
	 *
	 * <p>Chat is sixty-odd characters wide and wraps without warning, so a diagram printed into it
	 * is unreadable and, worse, unreadable in a way that looks like the build is wrong. What goes in
	 * chat is the shape of the thing and two links; the diagram itself goes to the clipboard whole.</p>
	 */
	private static int diagram(CommandContext<FabricClientCommandSource> context,
			AsciiDiagram.View view, Direction up, boolean numberNotes, boolean signs) {
		FabricClientCommandSource source = context.getSource();
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			source.sendError(Component.literal("No world loaded."));
			return 0;
		}
		BlockPos from = corner(source, context, "from");
		BlockPos to = corner(source, context, "to");
		if (from == null || to == null) {
			source.sendError(Component.literal("Caret coordinates (^) are not supported here. "
				+ "Give numbers, or ~ offsets, or look at a corner and press tab."));
			return 0;
		}
		int volume = AsciiDiagram.volume(from, to);
		if (volume > AsciiDiagram.MAX_BLOCKS) {
			source.sendError(Component.literal("That is " + volume + " blocks. "
				+ AsciiDiagram.MAX_BLOCKS + " is as much as this will draw."));
			return 0;
		}
		source.sendFeedback(Component.literal("asciidiagram " + volume + " blocks, looking "
			+ view.name().toLowerCase(java.util.Locale.ROOT)
			+ (view.flat()
				? ", " + (up == null ? AsciiDiagram.DEFAULT_UP : up).getName() + " up" : "")
			+ (numberNotes ? ", notes numbered" : "")
			+ (signs ? ", signs footnoted" : "")).withStyle(ChatFormatting.GRAY)
			.append(copy(level, from, to, view, up, numberNotes, signs,
				AsciiDiagram.Shape.CODE, "  [code]"))
			.append(copy(level, from, to, view, up, numberNotes, signs,
				AsciiDiagram.Shape.TABLE, "  [table]")));
		return 1;
	}

	/** One clickable offer of the box in one shape, rendered now so the click cannot fail. */
	private static Component copy(ClientLevel level, BlockPos from, BlockPos to,
			AsciiDiagram.View view, Direction up, boolean numberNotes, boolean signs,
			AsciiDiagram.Shape shape, String label) {
		String drawn = AsciiDiagram.render(level::getBlockState, from, to, view, up, numberNotes,
			signs ? position -> signText(level, position) : null, shape);
		return Component.literal(label).withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(drawn))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy "
				+ drawn.lines().count() + " lines to the clipboard"))));
	}

	/**
	 * The words on a sign, front face first and the back only where the front is blank, joined
	 * into one line. Null where the block is not a sign or has nothing written on it.
	 */
	private static String signText(ClientLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos)
				instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) {
			return null;
		}
		String front = signFace(sign.getFrontText());
		return front.isBlank() ? signFace(sign.getBackText()) : front;
	}

	private static String signFace(net.minecraft.world.level.block.entity.SignText text) {
		StringBuilder words = new StringBuilder();
		for (Component line : text.getMessages(false)) {
			String said = line.getString().strip();
			if (!said.isEmpty()) {
				words.append(words.isEmpty() ? "" : " ").append(said);
			}
		}
		return words.toString();
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
	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> wall(
			boolean dry, SongBuilder.PasteMode mode) {
		RequiredArgumentBuilder<FabricClientCommandSource, Integer> floors = RequiredArgumentBuilder
			.<FabricClientCommandSource, Integer>argument("floors",
				IntegerArgumentType.integer(1, 16))
			.then(RequiredArgumentBuilder
				.<FabricClientCommandSource, String>argument("chords",
					StringArgumentType.greedyString())
				.executes(context -> run(context, dry, null, 0, false, mode)));
		// The wall shapes, each optionally with its own distance to that wall. Literals rather
		// than a word argument so that leaving them off is unambiguous: a chord spec always opens
		// with a digit and a shape never does, and Brigadier tries its literal children first.
		//
		// The distance itself is optional now. With it, the walk begins as though it had already
		// got to that column, which pins where the first chord lands -- the right tool for
		// rebuilding one fault. Without it the lane starts at its own origin with that wall
		// ahead, and the walk places the chords wherever it wants them -- the only form that can
		// sim a shape the walk has to choose for itself, like the centre-fed climbing cuts.
		// One ambiguity comes with that: a first token that is a lone integer binds as the
		// distance, so "up 12 18" is a chord of eighteen twelve columns off the wall. A single
		// chord with no distance is said as "18x1" or given a gap, "18@4".
		for (String shape : List.of("flat", "up", "down")) {
			floors = floors.then(literal(shape)
				.then(colsThenChords(dry, shape, false, mode))
				.then(chordsFromTheOrigin(dry, shape, false, mode))
				// And optionally with the lane already bending towards that wall, which is what a lane
				// in the middle of a song is and what no run of chords can be written to produce.
				.then(literal("turning")
					.then(colsThenChords(dry, shape, true, mode))
					.then(chordsFromTheOrigin(dry, shape, true, mode))));
		}
		return RequiredArgumentBuilder
			.<FabricClientCommandSource, Integer>argument("width",
				IntegerArgumentType.integer(1, 512))
			.then(floors);
	}

	/** How far from that wall the first chord stands, and then the chords themselves. */
	private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> colsThenChords(
			boolean dry, String shape, boolean turning, SongBuilder.PasteMode mode) {
		return RequiredArgumentBuilder
			.<FabricClientCommandSource, Integer>argument("columnsToWall",
				IntegerArgumentType.integer(1, 512))
			.then(RequiredArgumentBuilder
				.<FabricClientCommandSource, String>argument("chords",
					StringArgumentType.greedyString())
				.executes(context -> run(context, dry, shape,
					IntegerArgumentType.getInteger(context, "columnsToWall"), turning, mode)));
	}

	/**
	 * The chords alone: the lane starts at its own origin with the chosen wall ahead.
	 *
	 * <p>The distance forces where the first chord lands, and a forced landing cannot sim a shape
	 * the walk chooses by where things fall -- a padded approach, a cut at the room the lane
	 * happens to arrive at. This form gives the walk the whole lane and lets it decide, which is
	 * what a real paste does.</p>
	 */
	private static RequiredArgumentBuilder<FabricClientCommandSource, String> chordsFromTheOrigin(
			boolean dry, String shape, boolean turning, SongBuilder.PasteMode mode) {
		return RequiredArgumentBuilder
			.<FabricClientCommandSource, String>argument("chords",
				StringArgumentType.greedyString())
			.executes(context -> run(context, dry, shape, FROM_THE_ORIGIN, turning, mode));
	}

	/** The distance meaning "no distance": the lane runs from its origin to the wall. */
	private static final int FROM_THE_ORIGIN = -1;

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
		// No distance at all is the head of the lane: the walk crosses the whole corridor to reach
		// the wall and places every chord itself.
		int column = columnsToWall == FROM_THE_ORIGIN ? 0
			: Math.max(0, width - 2 - columnsToWall);
		return switch (shape) {
			case "up" -> new SongBuilder.WalkStart(column, 0, 1, turning);
			case "down" -> new SongBuilder.WalkStart(column, floors - 1, -1, turning);
			default -> new SongBuilder.WalkStart(column, floors - 1, 1, turning);
		};
	}

	private static int run(CommandContext<FabricClientCommandSource> context, boolean dry,
			String shape, int columnsToWall, boolean turning, SongBuilder.PasteMode mode) {
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
			SongBuilder.PasteMode chosen = mode == null ? pasteMode() : mode;
			plan = SongBuilder.createPastePlan(origin(source), DebugChords.notes(chords), chosen,
				new SongBuilder.BuildLimits(FastNoteblocksConfig.get().maxBuildFloors(), wall,
					floors),
				shape == null ? SongBuilder.WalkStart.HEAD : seed(shape, floors, wall, columnsToWall, turning));
		} catch (IllegalArgumentException refused) {
			source.sendError(Component.literal(refused.getMessage()));
			return 0;
		}
		SongBuilder.PastePlan built = plan;
		List<DebugChords.Chord> parsed = chords;
		Runnable report = () -> {
			source.sendFeedback(Component.literal(DebugChords.describe(parsed) + " -> "
				+ built.mode().label() + ", " + built.totalColumns() + " columns of corridor, "
				+ built.width() + " long, " + built.depth() + " deep, "
				+ built.height() + " high, " + built.commands().size() + " blocks")
				.withStyle(ChatFormatting.GRAY));
			// Where the walls came out, in the world, which cannot be worked out from where you are
			// standing: the plan slides after it is walked so that nothing lands behind you, and the
			// wall the walk measured against moves with it. A breach is a lane past one of these, so
			// reading one off the blocks means knowing which column it was meant to stop in.
			source.sendFeedback(Component.literal("  walls at x=" + built.nearWall() + " and x="
				+ built.farWall() + ", " + (built.farWall() - built.nearWall())
				+ " columns between; first chord opens at x=" + firstChordX(built))
				.withStyle(ChatFormatting.GRAY));
			// Every fault, not a count of them. There are never many for a spec small enough to be
			// worth typing, and the whole point of building one is to read what it says.
			for (String fault : built.faults()) {
				source.sendFeedback(Component.literal("  " + fault)
					.withStyle(ChatFormatting.YELLOW));
			}
			if (built.faults().isEmpty()) {
				source.sendFeedback(Component.literal("  no faults")
					.withStyle(ChatFormatting.GREEN));
			}
			reportCollisions(source, built);
		};
		if (dry) {
			report.run();
			return plan.faults().size() + 1;
		}
		if (CommandPasteSender.isRunning()) {
			source.sendError(Component.literal("A paste is already running. Wait for it to finish."));
			return 0;
		}
		// After the blocks, not before them. Each command echoes itself into chat, so a report sent
		// first is several hundred lines above the build it describes by the time the build is there.
		CommandPasteSender.start(plan.commands(), List.of(), report);
		return plan.faults().size() + 1;
	}

	/**
	 * Every marked cell, as coordinates that can be pasted straight into {@code /tp}.
	 *
	 * <p>All of them rather than a count: there are never many, and the pair of blocks differs from
	 * one to the next -- which is the thing worth reading, since it says what wanted the cell.</p>
	 */
	static void reportCollisions(FabricClientCommandSource source, SongBuilder.PastePlan plan) {
		if (plan.collisions().isEmpty()) {
			return;
		}
		source.sendFeedback(Component.literal("  " + plan.collisions().size()
			+ " collisions, marked with sea lantern:").withStyle(ChatFormatting.YELLOW));
		for (Map.Entry<BlockPos, String> clash : plan.collisions().entrySet()) {
			BlockPos at = clash.getKey();
			source.sendFeedback(Component.literal("    " + at.getX() + " " + at.getY() + " "
				+ at.getZ() + "  " + clash.getValue()).withStyle(ChatFormatting.YELLOW));
		}
	}

	/**
	 * Paste marked up, or paste plain, and remember which.
	 *
	 * <p>What comes back marked is a machine you can read standing in it: the stone says which shape
	 * laid it, wire the signal never gets to is red, a note that sounds at the wrong moment is a lit
	 * bulb and one with nothing to set it off wears a dragon head. All of that is still a machine
	 * that runs. The one part that is not is the collisions -- the block that got there first is
	 * kept, whatever wanted it second is dropped, and the cell is a sea lantern -- so a build with
	 * any of those in it is wrong on purpose and meant to be walked round rather than heard.</p>
	 */
	/**
	 * What the blocks of a marked build mean, and whether the next one will be marked.
	 *
	 * <p>Read off {@link SongBuilder#DEBUG_PASTE_KEY}, which is the same table the builder colours
	 * from and the same one an {@code /fastnoteblocks asciidiagram} legend explains itself with, so the three can
	 * never come apart.</p>
	 */
	private static int colourKey(FabricClientCommandSource source) {
		boolean on = FastNoteblocksConfig.get().debugPasteEnabled();
		source.sendFeedback(Component.literal("Debug paste is " + (on ? "on" : "off")
			+ ". /fastnoteblocks debugpaste on|off to change it.")
			.withStyle(on ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
		SongBuilder.DEBUG_PASTE_KEY.forEach((block, means) -> source.sendFeedback(Component
			.literal("  " + block.substring(block.indexOf(':') + 1) + "  ")
			.withStyle(ChatFormatting.WHITE)
			.append(Component.literal(means).withStyle(ChatFormatting.GRAY))));
		source.sendFeedback(Component.literal("  hyphae and red nether brick replace whichever stone "
			+ "colour a cell had, and dead wire wins over a breach")
			.withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int debugPaste(FabricClientCommandSource source, boolean on) {
		FastNoteblocksConfig.get().setDebugPasteEnabled(on);
		FastNoteblocksConfig.save();
		source.sendFeedback(Component.literal(on
			? "Debug paste on. Stone is coloured by the shape that laid it, dead wire goes red, "
				+ "wrong notes become lit copper bulbs and missed ones wear a dragon head. "
				+ "Collisions build through and light up in sea lantern, which is broken on purpose "
				+ "-- turn this off before building anything you want to hear."
			: "Debug paste off. Builds come out plain and a collision refuses them again.")
			.withStyle(on ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
		return 1;
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
