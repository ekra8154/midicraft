package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * A box of world, written out as flat slices somebody can paste into a conversation.
 *
 * <p>What this is for is settling arguments about geometry. A build that does not fire is read by
 * arguing from the plan about where the blocks must be, and the plan is the thing under suspicion
 * -- so the argument runs for hours and both sides are reasoning from the same possibly-wrong
 * model. Slices of the actual world end that: whatever is there is there.</p>
 *
 * <p>Everything is oriented from a stated point of view, and every slice says which way is which,
 * because a diagram whose handedness has to be guessed at is worse than none. Looking east, the
 * slices advance away from you and south is on your right, the same as standing there.</p>
 */
public final class AsciiDiagram {
	private AsciiDiagram() {
	}

	/** Which way the reader is looking. Slices advance away from them. */
	public enum View {
		NORTH, SOUTH, EAST, WEST, TOP, BOTTOM;

		public static View of(String name) {
			return valueOf(name.toUpperCase(java.util.Locale.ROOT));
		}

		/**
		 * Whether this view looks along the y axis, and so has a free choice of which way is up.
		 *
		 * <p>Seen from the side, up the page is up in the world and there is nothing to decide. Seen
		 * from above or below it is a map, and a map can be turned: which compass direction is at the
		 * top is a real question, and the one a lane running east-west wants answered differently
		 * from a lane running north-south.</p>
		 */
		public boolean flat() {
			return this == TOP || this == BOTTOM;
		}
	}

	/** Aligned columns, or a markdown table. Both carry the same cells. */
	public enum Shape {
		CODE, TABLE
	}

	/** More than this and it is not a diagram, it is a data dump nobody will read. */
	public static final int MAX_BLOCKS = 40_000;

	/**
	 * How the three world axes lie on the page.
	 *
	 * @param sliceAscends whether successive slices go up the axis. Slices always advance away from
	 *     the reader, so this is what decides which end of the box is the front.
	 * @param colAscends whether the column coordinate grows rightwards
	 * @param rowAscends whether the row coordinate grows downwards, since rows run down the page
	 */
	private record Axes(Direction.Axis slice, boolean sliceAscends, Direction.Axis col,
			boolean colAscends, Direction.Axis row, boolean rowAscends) {
	}

	/** Which way is up the page when nothing says otherwise, and what every old diagram used. */
	public static final Direction DEFAULT_UP = Direction.NORTH;

	private static Axes axesOf(View view, Direction up) {
		// Rows run down the page, so for anything seen from the side the row axis is y and it
		// descends: the top of the diagram is the top of the build. Seen from above or below the rows
		// are horizontal and which one is which is the caller's to say -- north up by default, which
		// is what every map ever drawn does and what every diagram in this repo was drawn with.
		return switch (view) {
			case NORTH -> new Axes(Direction.Axis.Z, false, Direction.Axis.X, true,
				Direction.Axis.Y, false);
			case SOUTH -> new Axes(Direction.Axis.Z, true, Direction.Axis.X, false,
				Direction.Axis.Y, false);
			case EAST -> new Axes(Direction.Axis.X, true, Direction.Axis.Z, true,
				Direction.Axis.Y, false);
			case WEST -> new Axes(Direction.Axis.X, false, Direction.Axis.Z, false,
				Direction.Axis.Y, false);
			case TOP, BOTTOM -> flatAxes(view == View.TOP, up == null ? DEFAULT_UP : up);
		};
	}

	/**
	 * A map, turned so that the given direction is up the page.
	 *
	 * <p>Worked out rather than tabulated, because four ups times two views is eight rows of
	 * hand-written axis flags and seven of them would be wrong at least once. Two facts settle all
	 * eight. The row axis is the up direction's own axis, running down the page away from it -- so an
	 * up that points along the negative direction ascends downwards. And rightwards is up turned
	 * clockwise seen from above, anticlockwise seen from below, which is the whole of the difference
	 * between the two views: looking up at a ceiling, east is on your left.</p>
	 *
	 * <p>{@code TOP} with north up comes out {@code (Y down, X right, Z down)}, which is exactly the
	 * row this replaced, so nothing already drawn moves.</p>
	 */
	private static Axes flatAxes(boolean fromAbove, Direction up) {
		Direction right = fromAbove ? up.getClockWise() : up.getCounterClockWise();
		return new Axes(Direction.Axis.Y, !fromAbove,
			right.getAxis(), right.getAxisDirection() == Direction.AxisDirection.POSITIVE,
			up.getAxis(), up.getAxisDirection() == Direction.AxisDirection.NEGATIVE);
	}

	/** How many blocks the box holds, so a caller can refuse one before reading any of it. */
	public static int volume(BlockPos from, BlockPos to) {
		return (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1)
			* (Math.abs(from.getZ() - to.getZ()) + 1);
	}

	/** North up and note blocks unnumbered, which is what every caller wanted before there was a choice. */
	public static String render(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to,
			View view, Shape shape) {
		return render(world, from, to, view, null, false, shape);
	}

	/**
	 * @param up which compass direction is up the page, for a {@link View#flat()} view. Null, and for
	 *     a view from the side, means {@link #DEFAULT_UP}.
	 * @param numberNotes whether a note block says which note it plays rather than only that it is
	 *     one. Off by default: {@code NB} is two characters where {@code N01} is three, and widening
	 *     every column of every diagram to carry a number nobody asked for is not a trade worth
	 *     making by default.
	 */
	public static String render(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to,
			View view, Direction up, boolean numberNotes, Shape shape) {
		return render(world, from, to, view, up, numberNotes, null, shape);
	}

	/**
	 * @param signNotes the text on the sign at a position, or null where there is none -- or null
	 *     altogether to leave signs drawn as plain blocks. With it, a sign carrying text becomes a
	 *     footnote: {@code S1} in the grid and its words in the legend, which is how notes written
	 *     in the world -- "this is the fix" on an oak sign -- travel with the diagram they were
	 *     written about instead of being lost to it.
	 */
	public static String render(Function<BlockPos, BlockState> world, BlockPos from, BlockPos to,
			View view, Direction up, boolean numberNotes, Function<BlockPos, String> signNotes,
			Shape shape) {
		Axes axes = axesOf(view, up);
		int[] low = {Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
			Math.min(from.getZ(), to.getZ())};
		int[] high = {Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()),
			Math.max(from.getZ(), to.getZ())};
		// Shrunk to what is actually in the box, so a box can be thrown around a build rather than
		// measured to it, and the point is that
		// {@code /midicraft asciidiagram ~10 ~10 ~10 ~-10 ~-10 ~-10} draws whatever is inside without anybody
		// reading six numbers off the debug screen first.
		//
		// Only whole empty layers go. Air *between* blocks is most of what a diagram is read for --
		// wire, block, wire is a dead line and the gap over a note block is why it sounds -- so
		// nothing inside the shrunk box is touched, and the header says which box was drawn.
		//
		// A box with nothing in it at all is left exactly as asked for, because "there is nothing
		// here" is an answer and an empty diagram is how it gets said.
		int[] tightLow = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] tightHigh = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int x = low[0]; x <= high[0]; x++) {
			for (int y = low[1]; y <= high[1]; y++) {
				for (int z = low[2]; z <= high[2]; z++) {
					if (world.apply(new BlockPos(x, y, z)).isAir()) {
						continue;
					}
					int[] here = {x, y, z};
					for (int axis = 0; axis < 3; axis++) {
						tightLow[axis] = Math.min(tightLow[axis], here[axis]);
						tightHigh[axis] = Math.max(tightHigh[axis], here[axis]);
					}
				}
			}
		}
		if (tightLow[0] <= tightHigh[0]) {
			low = tightLow;
			high = tightHigh;
		}
		// Symbol to what it stands for, filled in as the box is read rather than declared up front,
		// so the legend names the blocks that are actually there and nothing else. Insertion ordered
		// because a legend that reshuffles between two runs of the same build is hard to diff.
		Map<String, String> legend = new LinkedHashMap<>();
		// Signs already footnoted, by position, so a sign spanning two slices of a box cannot be
		// numbered twice -- and a counter, since the number a sign gets is the order the scan met
		// it in.
		Map<BlockPos, String> footnotes = new LinkedHashMap<>();
		StringBuilder out = new StringBuilder();
		out.append("# ").append(low[0]).append(' ').append(low[1]).append(' ').append(low[2])
			.append("  ..  ").append(high[0]).append(' ').append(high[1]).append(' ')
			.append(high[2]).append(", looking ").append(view.name().toLowerCase(
				java.util.Locale.ROOT))
			// Said out loud for a flat view even when it is the default, because a diagram whose
			// handedness has to be guessed at is worse than none -- and now there is something to
			// guess at.
			.append(view.flat() ? ", " + (up == null ? DEFAULT_UP : up).getName() + " up" : "")
			.append('\n');
		out.append(orientation(axes)).append('\n');
		out.append("arrows point the way the signal leaves; x = away from you, o = towards you\n");
		int empty = 0;
		for (int slice : run(low, high, axes.slice(), axes.sliceAscends())) {
			List<Integer> cols = run(low, high, axes.col(), axes.colAscends());
			List<Integer> rows = run(low, high, axes.row(), axes.rowAscends());
			String[][] cells = new String[rows.size()][cols.size()];
			boolean anything = false;
			for (int row = 0; row < rows.size(); row++) {
				for (int col = 0; col < cols.size(); col++) {
					BlockPos here = at(axes, slice, cols.get(col), rows.get(row));
					BlockState state = world.apply(here);
					cells[row][col] = symbol(state, here, axes, legend, numberNotes, signNotes,
						footnotes);
					anything |= !state.isAir();
				}
			}
			// A box drawn round a build catches air above and beside it, and a page of dots tells
			// nobody anything. Counted rather than dropped silently, so the slices that are left
			// cannot be mistaken for the whole box.
			if (!anything) {
				empty++;
				continue;
			}
			out.append('\n').append(shape == Shape.TABLE ? "## " : "## ")
				.append(axes.slice().getSerializedName()).append('=').append(slice).append('\n');
			if (shape == Shape.TABLE) {
				table(out, axes, cols, rows, cells);
			} else {
				code(out, axes, cols, rows, cells);
			}
		}
		if (empty > 0) {
			out.append("\n(").append(empty).append(empty == 1 ? " slice was" : " slices were")
				.append(" all air, left out)\n");
		}
		out.append("\nlegend\n");
		int keys = 0;
		for (String key : legend.keySet()) {
			keys = Math.max(keys, key.length());
		}
		for (Map.Entry<String, String> entry : legend.entrySet()) {
			out.append("  ").append(pad(entry.getKey(), keys)).append("  ")
				.append(entry.getValue()).append('\n');
		}
		return out.toString();
	}

	private static String orientation(Axes axes) {
		return "slices advance " + (axes.sliceAscends() ? "+" : "-")
			+ axes.slice().getSerializedName() + " (away from you); columns are "
			+ axes.col().getSerializedName() + ", " + (axes.colAscends() ? "+" : "-")
			+ axes.col().getSerializedName() + " to the right; rows are "
			+ axes.row().getSerializedName() + ", " + (axes.rowAscends() ? "+" : "-")
			+ axes.row().getSerializedName() + " downwards";
	}

	private static void code(StringBuilder out, Axes axes, List<Integer> cols, List<Integer> rows,
			String[][] cells) {
		int width = 2;
		for (String[] line : cells) {
			for (String cell : line) {
				width = Math.max(width, cell.length());
			}
		}
		for (int col : cols) {
			width = Math.max(width, String.valueOf(col).length());
		}
		int gutter = 0;
		for (int row : rows) {
			gutter = Math.max(gutter, (axes.row().getSerializedName() + "=" + row).length());
		}
		// The column axis named on its own header row as well as in the orientation line above,
		// because the header is what somebody reads while counting along a corridor and having to
		// scroll back up to remember whether they are counting x or z is how a column gets lost.
		out.append(pad(axes.col().getSerializedName(), gutter)).append("  ");
		for (int col : cols) {
			out.append(' ').append(pad(String.valueOf(col), width));
		}
		out.append('\n');
		for (int row = 0; row < rows.size(); row++) {
			out.append(pad(axes.row().getSerializedName() + "=" + rows.get(row), gutter))
				.append("  ");
			for (int col = 0; col < cols.size(); col++) {
				out.append(' ').append(pad(cells[row][col], width));
			}
			out.append('\n');
		}
	}

	private static void table(StringBuilder out, Axes axes, List<Integer> cols, List<Integer> rows,
			String[][] cells) {
		out.append("\n| ").append(axes.row().getSerializedName()).append(" \\ ")
			.append(axes.col().getSerializedName()).append(" |");
		for (int col : cols) {
			out.append(' ').append(col).append(" |");
		}
		out.append("\n|---|");
		for (int col = 0; col < cols.size(); col++) {
			out.append("---|");
		}
		out.append('\n');
		for (int row = 0; row < rows.size(); row++) {
			out.append("| **").append(rows.get(row)).append("** |");
			for (int col = 0; col < cols.size(); col++) {
				out.append(' ').append(cells[row][col].replace("|", "\\|")).append(" |");
			}
			out.append('\n');
		}
	}

	private static List<Integer> run(int[] low, int[] high, Direction.Axis axis, boolean ascends) {
		int index = index(axis);
		List<Integer> values = new ArrayList<>();
		if (ascends) {
			for (int value = low[index]; value <= high[index]; value++) {
				values.add(value);
			}
		} else {
			for (int value = high[index]; value >= low[index]; value--) {
				values.add(value);
			}
		}
		return values;
	}

	private static BlockPos at(Axes axes, int slice, int col, int row) {
		int[] coords = new int[3];
		coords[index(axes.slice())] = slice;
		coords[index(axes.col())] = col;
		coords[index(axes.row())] = row;
		return new BlockPos(coords[0], coords[1], coords[2]);
	}

	private static int index(Direction.Axis axis) {
		return switch (axis) {
			case X -> 0;
			case Y -> 1;
			case Z -> 2;
		};
	}

	private static String pad(String text, int width) {
		return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
	}

	/**
	 * One block, in as few characters as say what it is.
	 *
	 * <p>The redstone is spelled out and everything else is abbreviated, because what these diagrams
	 * get read for is which way a signal went and how much of it was left. Wire carries its power and
	 * a repeater its delay and its heading; a slab says which half of the block it is, since that is
	 * what a staircase is made of; a copper bulb says whether it is lit, since that is what a build
	 * is judged by. The rest is two letters and a line in the legend.</p>
	 */
	private static String symbol(BlockState state, BlockPos pos, Axes axes,
			Map<String, String> legend, boolean numberNotes, Function<BlockPos, String> signNotes,
			Map<BlockPos, String> footnotes) {
		if (state.isAir()) {
			legend.putIfAbsent(".", "air");
			return ".";
		}
		String raw = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		// A sign with words on it is a footnote: the words are the point of the block, so they go
		// in the legend under the sign's own number and the grid says only which sign is which. A
		// blank sign stays a plain block, since it has nothing to say.
		if (signNotes != null && raw.endsWith("_sign")) {
			String noted = footnotes.get(pos.immutable());
			if (noted != null) {
				return noted;
			}
			String words = signNotes.apply(pos);
			if (words != null && !words.isBlank()) {
				String mark = "S" + (footnotes.size() + 1);
				footnotes.put(pos.immutable(), mark);
				legend.put(mark, "sign: \"" + words.strip() + "\"");
				return mark;
			}
		}
		// What goes in the legend, which is the block and -- where a marked paste gives a block a
		// meaning -- what that block is telling you. A slice pasted into a conversation is often all
		// anybody has of a build, and "TU = minecraft:tuff" says nothing on its own.
		String id = SongBuilder.DEBUG_PASTE_KEY.containsKey(raw)
			? raw + " (color-coded paste: " + SongBuilder.DEBUG_PASTE_KEY.get(raw) + ")"
			: raw;
		String path = raw.substring(raw.indexOf(':') + 1);
		if (state.hasProperty(RedstoneWireBlock.POWER)) {
			legend.putIfAbsent("w<n>", id + ", n = power, 0 to 15");
			return "w" + state.getValue(RedstoneWireBlock.POWER);
		}
		if (state.hasProperty(RepeaterBlock.DELAY)) {
			legend.putIfAbsent("<arrow><n>", id + ", n = delay in ticks, 1 to 4");
			return arrow(signalOut(state), axes) + String.valueOf(state.getValue(RepeaterBlock.DELAY));
		}
		if (path.equals("comparator")) {
			legend.putIfAbsent("c<arrow>", id);
			return "c" + arrow(signalOut(state), axes);
		}
		if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
			SlabType half = state.getValue(BlockStateProperties.SLAB_TYPE);
			String mark = switch (half) {
				case TOP -> "s^";
				case BOTTOM -> "s_";
				case DOUBLE -> "s=";
			};
			legend.putIfAbsent(mark, id + ", " + half.getSerializedName() + " half");
			return mark;
		}
		if (path.contains("copper_bulb")) {
			boolean lit = state.hasProperty(BlockStateProperties.LIT)
				&& state.getValue(BlockStateProperties.LIT);
			legend.putIfAbsent(lit ? "B*" : "B-", id + (lit ? ", lit" : ", unlit"));
			return lit ? "B*" : "B-";
		}
		if (path.equals("note_block")) {
			// Which note, where the caller asked for it. Two digits and always two, so a column of
			// them lines up and the eye can read the shape of a phrase down the page; a note block
			// holds 0 to 24 and nothing wider is possible.
			if (numberNotes && state.hasProperty(BlockStateProperties.NOTE)) {
				legend.putIfAbsent("N<nn>", id + ", nn = note, 00 to 24");
				return String.format(java.util.Locale.ROOT, "N%02d",
					state.getValue(BlockStateProperties.NOTE));
			}
			legend.putIfAbsent("NB", id);
			return "NB";
		}
		if (path.contains("glass")) {
			legend.putIfAbsent("GL", id);
			return "GL";
		}
		// Everything else gets the first two letters of its name, and a second, third or fourth
		// block that wants the same two gets a digit after them. Nothing is ever left unexplained:
		// whatever comes back, the legend says which block it was.
		String base = (path.length() >= 2 ? path.substring(0, 2) : path + "_").toUpperCase(
			java.util.Locale.ROOT);
		for (int suffix = 0; suffix < 10; suffix++) {
			String mark = suffix == 0 ? base : base.charAt(0) + String.valueOf(suffix);
			String seen = legend.get(mark);
			if (seen == null) {
				legend.put(mark, id);
				return mark;
			}
			if (seen.equals(id)) {
				return mark;
			}
		}
		return "??";
	}

	/**
	 * The way a repeater or comparator sends its signal.
	 *
	 * <p>Which is the opposite of {@code FACING}: {@code DiodeBlock.getInputSignal} reads the block
	 * at {@code pos.relative(FACING)}, so the property points at where the signal comes from. Drawn
	 * the other way round every diagram would have its arrows reversed, which is the one mistake
	 * that would make these worse than useless.</p>
	 */
	private static Direction signalOut(BlockState state) {
		return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
	}

	private static char arrow(Direction direction, Axes axes) {
		boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		if (direction.getAxis() == axes.col()) {
			return positive == axes.colAscends() ? '>' : '<';
		}
		if (direction.getAxis() == axes.row()) {
			return positive == axes.rowAscends() ? 'v' : '^';
		}
		return positive == axes.sliceAscends() ? 'x' : 'o';
	}
}
