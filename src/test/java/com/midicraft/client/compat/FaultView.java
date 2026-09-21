package com.midicraft.client.compat;

import com.midicraft.NotePitch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Any fault in any build, drawn rather than listed.
 *
 * <p>{@link BreachView} does this for one fault kind in one paster. This does it for all three --
 * dead wire, wrong note, breach -- in whichever mode is asked for, because the three are not the
 * same kind of wrong and a session hunting one usually turns up the others. A breach is a lane
 * outside the width it promised: visible, and harmless to the music. A wrong note is a note block
 * something else sounds, at a tick nobody wrote. A dead wire is wire the signal never crosses,
 * which silences everything downstream and which nothing but reading the blocks back can find.</p>
 *
 * <p>Drawing rather than listing is the whole point, and it is measured. Two long passes went into
 * reading {@code setblock} lists and reasoning about redstone rules for one dead wire and reached
 * the wrong explanation twice; in-game reading showed the same fault off a single rendered slice
 * immediately: a wire on both sides of a block, and a wire cannot be soft powered. So the
 * rule this class exists to enforce is: draw it <b>before</b> forming a theory, and draw the shape
 * rather than quoting the coordinates.</p>
 *
 * <p>What to look for, per kind:</p>
 * <ul>
 * <li><b>Dead wire</b> -- wire, block, wire. A block powered only by dust is soft powered: it still
 * sounds the notes hung on it, so they read as reached, and it cannot light the dust on its far
 * side. Every dead wire in this project has been that shape. Then check where the repeater that
 * should have drawn the signal out went -- often up a staircase onto the next floor.</li>
 * <li><b>Wrong note</b> -- the fault text says which side; the slice says which <i>level</i>, which
 * the text cannot. Along the lane is one module reaching into the next, across it is the corridor
 * alongside, and the two want opposite fixes.</li>
 * <li><b>Breach</b> -- the last columns against the wall, and the staircase that wanted them.</li>
 * </ul>
 */
final class FaultView {
	private FaultView() {
	}

	/** Floors stand four apart, and a lane occupies three of them. */
	private static final int FLOOR_HEIGHT = 4;

	/**
	 * A build stood up as blocks, and what the machine reader made of them.
	 *
	 * @param laid the command index each cell was first written at, which is walk order -- and walk
	 *     order is signal order within a structure, so it is what says which of two faults is
	 *     upstream of the other
	 * @param named whether shapes were named, which costs the build its collision throw. See
	 *     {@link #of}.
	 */
	record Build(String name, SongBuilder.PasteMode mode, int width, int floors,
			SongBuilder.PastePlan plan, Map<BlockPos, BlockState> world, Map<BlockPos, Integer> laid,
			NoteMachineReader.Reading reading, int ground, boolean named) {

		BlockState at(BlockPos position) {
			return world.getOrDefault(position, Blocks.AIR.defaultBlockState());
		}

		/** The floor a block belongs to, which is the level a lane's own path runs at. */
		int floorOf(int y) {
			return ground + Math.floorDiv(y - ground, FLOOR_HEIGHT) * FLOOR_HEIGHT;
		}

		String where() {
			return name + " " + width + "w x " + floors + "f, " + mode.label();
		}
	}

	/**
	 * The build to look at, with the two flags that decide what there is to see set deliberately.
	 *
	 * <p>{@code MARK_UNREACHED} is off for the duration. A marked build turns every note the signal
	 * never reached into a sea lantern, which is what makes a dead wire visible in game and invisible
	 * here: a sea lantern is not a note block, so the reader comes back saying everything left was
	 * reached.</p>
	 *
	 * <p>{@code nameShapes} is the caller's, and it is <b>off by default on purpose</b>. Turning it
	 * on is not free instrumentation: it stops a layout collision throwing, so the first claim on a
	 * cell wins and the walk carries on. That is usually what you want -- a build to look at beats an
	 * exception -- but it is a different build. It matters most in v2, whose trial-and-rollback
	 * <i>depends</i> on that throw to fall back to the conservative shape, so a v2 build made with
	 * names on has quietly skipped every fallback it would have taken. Ask for names when reading a
	 * wrong note, whose {@code whose} clause needs them; leave them off when the question is whether
	 * this build is right.</p>
	 */
	static Build of(String song, SongBuilder.PasteMode mode, int width, int floors, int maxFloors,
			boolean nameShapes) throws Exception {
		// Read at the resolution the mode builds at. The no-mode overload answers for the sequence,
		// which for a game-tick layout is a different song at twice the speed -- and a fault drawn off
		// that is a fault in a machine the paste would never lay.
		return of(song, BreachView.song(song, mode), mode, width, floors, maxFloors, nameShapes);
	}

	static Build of(String name, List<SongBuilder.EventNote> notes, SongBuilder.PasteMode mode,
			int width, int floors, int maxFloors, boolean nameShapes) {
		return of(name, notes, mode, width, floors, maxFloors, nameShapes, null);
	}

	/**
	 * @param start where the walk begins, the way the debug paste seeds it -- a chord a stated
	 *     distance from its wall with nothing in front of it to push it there -- or null for the
	 *     origin
	 */
	static Build of(String name, List<SongBuilder.EventNote> notes, SongBuilder.PasteMode mode,
			int width, int floors, int maxFloors, boolean nameShapes, SongBuilder.WalkStart start) {
		boolean marking = SongBuilder.MARK_UNREACHED;
		boolean naming = SongBuilder.DEBUG_PASTE;
		boolean labelling = SongBuilder.NAME_EVERY_CELL;
		boolean marking2 = SongBuilder.MARK_SHAPES;
		SongBuilder.PastePlan plan;
		try {
			SongBuilder.MARK_UNREACHED = false;
			SongBuilder.DEBUG_PASTE = nameShapes;
			// Always, and it is not the same switch as the one above. This one only remembers what laid
			// each cell; the one above also stops a collision throwing, which in v2 skips every trial
			// fallback and builds a different machine. So every fault drawn here can name the shapes
			// either end of it without the drawing having changed what it is looking at.
			SongBuilder.NAME_EVERY_CELL = true;
			// The shape colours, always, and without DEBUG_PASTE's other half. A diagram of a build is
			// no use if it cannot say which shape laid which block, and turning DEBUG_PASTE on to get
			// that would draw a different machine -- it suppresses collisions, which in v2 skips every
			// trial fallback. See SongBuilder.MARK_SHAPES.
			SongBuilder.MARK_SHAPES = true;
			// The game's limits -- its reseed delay and start floor travel on them -- with the cap
			// the caller names, which only the cube layout reads. See GameSettings.
			SongBuilder.BuildLimits limits = GameSettings.get().limits(maxFloors, width, floors);
			plan = start == null
				? SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits)
				: SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes, mode, limits, start);
		} finally {
			SongBuilder.MARK_UNREACHED = marking;
			SongBuilder.DEBUG_PASTE = naming;
			SongBuilder.NAME_EVERY_CELL = labelling;
			SongBuilder.MARK_SHAPES = marking2;
		}
		Map<BlockPos, BlockState> world = new LinkedHashMap<>();
		Map<BlockPos, Integer> laid = new LinkedHashMap<>();
		int ground = Integer.MAX_VALUE;
		int index = 0;
		for (String command : plan.commands()) {
			String[] word = command.split(" ");
			BlockPos at = new BlockPos(Integer.parseInt(word[1]), Integer.parseInt(word[2]),
				Integer.parseInt(word[3]));
			world.put(at, BreachView.parse(word[4]));
			laid.putIfAbsent(at, index++);
			ground = Math.min(ground, at.getY());
		}
		// A build the reader refuses -- no way in, or a loop -- is still a build worth drawing:
		// the refusal is the fault, and the blocks round the input are where it is read off.
		NoteMachineReader.Reading reading;
		try {
			reading = BreachView.readBack(name, plan);
		} catch (NoteMachineReader.UnreadableException refused) {
			System.out.println("#### REFUSED -- " + refused.getMessage());
			reading = new NoteMachineReader.Reading(null, 0, 0, List.of(), Set.of(), 0, 0, 1,
				List.of("REFUSED: " + refused.getMessage()));
		}
		return new Build(name, mode, width, floors, plan, world, laid, reading, ground, nameShapes);
	}

	// ---- the three faults -------------------------------------------------------------------

	/**
	 * A note the signal never reaches, and the last one it did reach before it.
	 *
	 * <p>Only the earliest in walk order is worth looking at: a break puts everything the walk laid
	 * after it in its shadow, so the rest are that one's shadow rather than faults of their own.
	 * The live frontier is the other half of the picture -- a run built on its own reads clean, so
	 * what stopped this one is upstream of it, and the frontier is where upstream ends.</p>
	 */
	record Dead(BlockPos at, BlockPos frontier, int shadowed) {
		@Override
		public String toString() {
			return "dead from " + say(at) + ", last live note " + say(frontier) + ", "
				+ shadowed + " notes in its shadow";
		}
	}

	/**
	 * The first cell of wire the signal never got to, and the shapes either side of it.
	 *
	 * <p>The dead <i>note</i> is what gets counted, and it is the wrong end of the fault: a note is
	 * silent because a cell of wire upstream of it never went live, and that cell is where the two
	 * shapes that disagreed meet. One line of this classifies a build; the drawing is for when the
	 * line is not enough.</p>
	 *
	 * <p>Walk order is signal order within a structure, so the earliest-laid dead cell is the break
	 * and everything after it is that break's shadow.</p>
	 */
	record Break(BlockPos at, String what, String before) {
		@Override
		public String toString() {
			return "break at " + say(at) + "   " + before + " -> " + what;
		}
	}

	/**
	 * The frontier, not the earliest-laid dead cell.
	 *
	 * <p>The earliest-laid cell the reader never reached was the rule until 2026-09-20, and for a
	 * whole census it named the wrong cell: a closing pad's descent is laid before the chords of
	 * the leg it closes, so a dead seam at the head of that leg was reported as
	 * {@code delayBeforeChord -> descent4}; and a stone under a repeater, recorded live and powered
	 * by nothing, stood in for every break laid after it -- {@code noteMiddle ->
	 * repeaterOffASoftTip}, five builds of thriller, a family that did not exist. So the break is
	 * the dead cell that touches a live one, earliest laid among those, and {@code before} is the
	 * live neighbour's shape: the one that failed to hand the signal on. Where no dead cell touches
	 * a live one at all the old rule stands, so a build the reader never entered still names a
	 * cell.</p>
	 */
	static Break firstBreak(Build build) {
		Set<BlockPos> reached = build.reading().reachedAt();
		BlockPos first = null;
		BlockPos liveBehind = null;
		int bestOrder = Integer.MAX_VALUE;
		BlockPos earliest = null;
		int earliestOrder = Integer.MAX_VALUE;
		for (BlockPos at : build.plan().poweredAt()) {
			if (reached.contains(at)) {
				continue;
			}
			int order = order(build, at);
			if (order < earliestOrder) {
				earliestOrder = order;
				earliest = at;
			}
			if (order >= bestOrder) {
				continue;
			}
			for (Direction direction : Direction.values()) {
				BlockPos beside = at.relative(direction);
				if (reached.contains(beside)) {
					bestOrder = order;
					first = at;
					liveBehind = beside;
					break;
				}
			}
		}
		if (first == null) {
			if (earliest == null) {
				return null;
			}
			return new Break(earliest, build.plan().laidBy().getOrDefault(earliest, "?"), "nothing live");
		}
		return new Break(first, build.plan().laidBy().getOrDefault(first, "?"),
			build.plan().laidBy().getOrDefault(liveBehind, "?"));
	}

	static List<Dead> deadWires(Build build) {
		if (build.reading().unreachedAt().isEmpty()) {
			return List.of();
		}
		List<BlockPos> unreached = new ArrayList<>(build.reading().unreachedAt());
		unreached.sort((a, b) -> Integer.compare(order(build, a), order(build, b)));
		BlockPos first = unreached.get(0);
		Set<BlockPos> dead = Set.copyOf(build.reading().unreachedAt());
		BlockPos frontier = first;
		int bestOrder = -1;
		for (Map.Entry<BlockPos, Integer> cell : build.laid().entrySet()) {
			if (cell.getValue() >= order(build, first) || dead.contains(cell.getKey())
					|| !build.at(cell.getKey()).is(Blocks.NOTE_BLOCK)) {
				continue;
			}
			if (cell.getValue() > bestOrder) {
				bestOrder = cell.getValue();
				frontier = cell.getKey();
			}
		}
		return List.of(new Dead(first, frontier, unreached.size()));
	}

	private static int order(Build build, BlockPos at) {
		return build.laid().getOrDefault(at, Integer.MAX_VALUE);
	}

	/**
	 * A note block something else sounds, read out of the plan's own fault list.
	 *
	 * <p>Parsed rather than recomputed, so that what is drawn is what the layout check complained
	 * about and the two can never drift. A fault naming no neighbour -- "has nothing to set it off"
	 * -- carries a null aggressor and is still worth drawing: the missing trigger is the thing to
	 * look for.</p>
	 */
	record Wrong(BlockPos at, BlockPos from, String text) {
		@Override
		public String toString() {
			return text;
		}
	}

	/**
	 * A shape's name with the numbers taken out of it, so a census can count shapes and not chords.
	 *
	 * <p>{@code chord:STACKED_BUS_HALF head6/tail9 reachesBack} and the same shape with a tail of
	 * four are one shape and one rule. Left as they are, a single fault appears in a ranked list as
	 * nine separate ones with a count of one each, and the thing that is actually happening
	 * thirty-one times looks like nothing much happening fifteen times.</p>
	 */
	static String family(String shape) {
		return shape.replaceAll("(head|tail|notes|delay|near|far)-?\\d+", "$1")
			.replaceAll("\\s+", " ").strip();
	}

	/**
	 * The two shapes a wrong note is between, as {@code aggressor -> victim}.
	 *
	 * <p>The pair, not the blocks. A note sounded by something else is always two shapes disagreeing
	 * about a cell, and which two decides whose rule was broken -- the same reason the collision
	 * marker prints a pair. Grouping a library's worth of them by pair is what says whether thirty
	 * wrong notes are thirty bugs or three.</p>
	 *
	 * <p>The victim is named off the block <b>under</b> the note, because the note block itself is
	 * hung by a shape that shares its label with the module, and it is the instrument block that says
	 * which module owns the column.</p>
	 */
	static String whose(Build build, Wrong wrong) {
		String victim = build.plan().laidBy().getOrDefault(wrong.at(),
			build.plan().laidBy().getOrDefault(wrong.at().below(), "?"));
		String aggressor = wrong.from() == null ? "nothing"
			: build.plan().laidBy().getOrDefault(wrong.from(), "?");
		return aggressor + " -> " + victim;
	}

	static List<Wrong> wrongNotes(Build build) {
		List<Wrong> found = new ArrayList<>();
		for (String fault : build.plan().faults()) {
			if (!fault.startsWith("the note at ")) {
				continue;
			}
			BlockPos at = positionAfter(fault, "the note at ");
			BlockPos from = fault.contains(" from the ") ? positionAfter(fault, " at ",
				fault.indexOf(" from the ")) : null;
			if (at != null) {
				found.add(new Wrong(at, from, fault));
			}
		}
		return found;
	}

	/**
	 * Notes the layout had nowhere to hang, which the build simply does not contain.
	 *
	 * <p>The fourth fault kind, and the only one with nothing to draw: there is no cell to look at,
	 * because the whole complaint is that no cell was found. So it is listed rather than rendered,
	 * with the chord it was cut out of -- which is the thing that decides whether the shape was too
	 * small or the chord too big.</p>
	 */
	static List<String> missing(Build build) {
		return build.plan().faults().stream()
			.filter(fault -> fault.contains("had nowhere to hang")).toList();
	}

	/**
	 * Every note in a box, in the order it is meant to sound, with the gap to the one before it.
	 *
	 * <p>What the walk wrote, not what the machine does. Both halves matter and they are different
	 * questions: the blocks say what is standing there, and this says what it was standing there
	 * <i>for</i>. Around a break it is the only way to see whether the two sides of the break belong
	 * to the same phrase or to different ones, and how far apart the machine was supposed to put
	 * them -- which is what decides whether a missing column can simply be filled in or whether the
	 * timing has to move with it.</p>
	 *
	 * <p>Ticks are the walk's own, and one of them is one repeater tick: every delay in the builder is
	 * counted in these, which is why a gap of 1 is a repeater of 1 and a gap over 4 needs a repeater
	 * of its own.</p>
	 */
	static String notesIn(Build build, BlockPos from, BlockPos to) {
		record Sounding(BlockPos at, int tick, String instrument, int pitch) {
		}
		List<Sounding> found = new ArrayList<>();
		build.plan().noteTicks().forEach((at, tick) -> {
			if (at.getX() < from.getX() || at.getX() > to.getX() || at.getY() < from.getY()
					|| at.getY() > to.getY() || at.getZ() < from.getZ() || at.getZ() > to.getZ()) {
				return;
			}
			BlockState note = build.at(at);
			found.add(new Sounding(at, tick,
				build.at(at.below()).getBlock().getDescriptionId().replace("block.minecraft.", ""),
				note.hasProperty(net.minecraft.world.level.block.NoteBlock.NOTE)
					? note.getValue(net.minecraft.world.level.block.NoteBlock.NOTE) : -1));
		});
		if (found.isEmpty()) {
			return "   (no notes in this window)";
		}
		found.sort((a, b) -> a.tick() != b.tick() ? Integer.compare(a.tick(), b.tick())
			: Integer.compare(a.at().getX(), b.at().getX()));
		// A chord to a line, because a chord is the thing the walk lays and the thing that sounds. One
		// note to a line is the same information and nobody can see the phrase in it.
		java.util.LinkedHashMap<Integer, List<Sounding>> byTick = new java.util.LinkedHashMap<>();
		found.forEach(one -> byTick.computeIfAbsent(one.tick(), key -> new ArrayList<>()).add(one));
		StringBuilder said = new StringBuilder(
			"   what this window is meant to play, in repeater ticks:");
		int previous = Integer.MIN_VALUE;
		for (Map.Entry<Integer, List<Sounding>> chord : byTick.entrySet()) {
			said.append("\n      t").append(String.format("%-6d", chord.getKey()))
				.append(previous == Integer.MIN_VALUE ? "        "
					: String.format("(+%-2d)   ", chord.getKey() - previous));
			for (Sounding one : chord.getValue()) {
				said.append(String.format("%02d", one.pitch()))
					.append(NotePitch.name(one.pitch()).replace('♯', '#'))
					.append(' ').append(one.instrument()).append("   ");
			}
			previous = chord.getKey();
		}
		return said.toString();
	}

	/** A lane that turned outside its walls, and the furthest block it got. */
	record Breach(BlockPos turn, int past, boolean nearSide, BreachView.Overrun run) {
		@Override
		public String toString() {
			return "turned at " + say(turn) + ", " + past + " past the "
				+ (nearSide ? "near" : "far") + " wall"
				+ (run == null ? "" : "   furthest block " + run.out() + " out, tp " + run.x() + " "
					+ run.y() + " " + run.z());
		}
	}

	/**
	 * Every lane that ended outside the width it promised, worst first.
	 *
	 * <p>Driven off the <b>turns</b> rather than off the blocks, because a breach is a lane that
	 * turned outside its wall and the block scan cannot tell that from the corner sitting on the wall
	 * by construction. A turn exactly one column past is the latter -- every build has them, they are
	 * not faults, and listing them puts three non-faults above the two real ones. This is the same
	 * cut {@code plan.breaches()} makes, so the two agree by construction rather than by luck.</p>
	 *
	 * <p>The block overrun is still attached where there is one, because the turn says the lane broke
	 * its promise and the furthest block says where to go and stand.</p>
	 */
	static List<Breach> breaches(Build build) {
		List<Breach> found = new ArrayList<>();
		for (BlockPos turn : build.plan().turns()) {
			boolean near = turn.getX() < build.plan().nearWall();
			int past = near ? build.plan().nearWall() - turn.getX()
				: turn.getX() > build.plan().farWall() ? turn.getX() - build.plan().farWall() : 0;
			if (past < 2) {
				continue;
			}
			// Same side of the corridor, same floor, and then the nearest in z. A lane can overrun at
			// both ends, and matching on floor and z alone paired a far-side turn with a near-side
			// block and drew a window round the wrong end of the build entirely -- which reads as an
			// answer rather than as a mismatch, and is how a theory gets formed about a lane nobody
			// was looking at.
			BreachView.Overrun worst = null;
			for (BreachView.Overrun run : BreachView.overruns(build.plan())) {
				if (run.nearSide() != near || build.floorOf(run.y()) != build.floorOf(turn.getY())
						|| Math.abs(run.z() - turn.getZ()) > 1) {
					continue;
				}
				if (worst == null || Math.abs(run.z() - turn.getZ())
						< Math.abs(worst.z() - turn.getZ())) {
					worst = run;
				}
			}
			found.add(new Breach(turn, past, near, worst));
		}
		found.sort((a, b) -> Integer.compare(b.past(), a.past()));
		return found;
	}

	// ---- drawing ----------------------------------------------------------------------------

	/**
	 * A box of the build, or a line saying why not.
	 *
	 * <p>Refused rather than truncated when the box is too big: a diagram nobody will read is worse
	 * than an admission that the window was wrong, because it looks like an answer.</p>
	 */
	static String draw(Build build, BlockPos from, BlockPos to, AsciiDiagram.View view) {
		int volume = AsciiDiagram.volume(from, to);
		if (volume > AsciiDiagram.MAX_BLOCKS) {
			return "   (window of " + volume + " blocks is past the " + AsciiDiagram.MAX_BLOCKS
				+ " a diagram is worth reading -- narrow it)";
		}
		return AsciiDiagram.render(build::at, from, to, view, AsciiDiagram.Shape.CODE);
	}

	/**
	 * The box around one or two cells, padded by how far the shape reaches rather than by a guess.
	 *
	 * <p>Measured from the lane's own floor upward rather than from whichever block happens to be
	 * highest, because the block that stands out is as likely to be the dust on top of a bus as the
	 * path, and a window hung off it starts two levels above the lane and shows none of it.</p>
	 */
	static String around(Build build, BlockPos one, BlockPos two, AsciiDiagram.View view,
			int eitherX, int eitherZ, int below, int above) {
		BlockPos[] box = box(build, one, two, eitherX, eitherZ, below, above);
		return draw(build, box[0], box[1], view);
	}

	/** The corners {@link #around} would draw between, so the same box can be asked other questions. */
	static BlockPos[] box(Build build, BlockPos one, BlockPos two, int eitherX, int eitherZ,
			int below, int above) {
		BlockPos other = two == null ? one : two;
		// Both floors, not the lower one twice.
		//
		// This took the floor of whichever end was lower and drew {@code below..above} around that
		// alone, so a fault whose two ends sit on different floors had one of them off the top of the
		// picture -- and that is the case worth drawing, because a break that crosses a staircase is
		// the one whose levels cannot be guessed from the text. A window of {@code y=65..70} was
		// once drawn for a break at {@code 5 73 7}: every block in the diagram was innocent and
		// the guilty one was three levels above the frame.
		//
		// Same mistake as drawing the dead-wire window on the notes' own z, which cost the first fault
		// this class was ever pointed at: a window that excludes the fault is worse than no window,
		// because it looks like an answer.
		int under = build.floorOf(Math.min(one.getY(), other.getY()));
		int over = build.floorOf(Math.max(one.getY(), other.getY()));
		return new BlockPos[] {
			new BlockPos(Math.min(one.getX(), other.getX()) - eitherX, under - below,
				Math.min(one.getZ(), other.getZ()) - eitherZ),
			new BlockPos(Math.max(one.getX(), other.getX()) + eitherX, over + above,
				Math.max(one.getZ(), other.getZ()) + eitherZ)};
	}

	/**
	 * Which shape laid each cell of a box, grouped by shape.
	 *
	 * <p>The half of a fault the picture cannot draw. Every stone in a lane looks like every other
	 * one, so a slice says a note block stands in a run of dust and cannot say whether that note
	 * belongs to the chord in front, the tail behind it or the rail alongside -- and which of those it
	 * is decides whose rule was broken. Grouped rather than listed per cell because the answer wanted
	 * is nearly always "how many shapes are in this picture, and which", and a cell-by-cell dump of a
	 * fifteen-column window buries that under sixty lines.</p>
	 */
	static String shapesIn(Build build, BlockPos from, BlockPos to) {
		Map<String, List<BlockPos>> byShape = new java.util.TreeMap<>();
		build.plan().laidBy().forEach((at, what) -> {
			if (at.getX() < from.getX() || at.getX() > to.getX() || at.getY() < from.getY()
					|| at.getY() > to.getY() || at.getZ() < from.getZ() || at.getZ() > to.getZ()) {
				return;
			}
			byShape.computeIfAbsent(what, key -> new ArrayList<>()).add(at);
		});
		if (byShape.isEmpty()) {
			return "   (nothing in this window remembers what laid it)";
		}
		StringBuilder said = new StringBuilder("   what laid this window:");
		byShape.forEach((what, cells) -> {
			cells.sort((a, b) -> a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX())
				: a.getZ() != b.getZ() ? Integer.compare(a.getZ(), b.getZ())
				: Integer.compare(a.getY(), b.getY()));
			said.append("\n      ").append(String.format("%-46s", what)).append(cells.size())
				.append(cells.size() == 1 ? " cell  " : " cells ");
			for (BlockPos at : cells.subList(0, Math.min(6, cells.size()))) {
				said.append(' ').append(say(at)).append(" |");
			}
			if (cells.size() > 6) {
				said.append(" ...");
			}
		});
		return said.toString();
	}

	// ---- the page -----------------------------------------------------------------------------

	/** Everything wrong with one build, each fault drawn, worst kind first. */
	static void report(Build build, int perKind) {
		System.out.println();
		System.out.println("======== " + build.where() + " ========");
		System.out.println("   walls x=" + build.plan().nearWall() + ".." + build.plan().farWall()
			+ "   spans x " + build.plan().spanX() + " z " + build.plan().spanZ()
			+ "   breach blocks=" + build.plan().breaches().stream().mapToInt(Integer::intValue).sum()
			+ " walls=" + build.plan().wallBreaches().size()
			+ " (inner " + build.plan().innerWallBreaches() + ", worst "
			+ build.plan().worstWallBreach() + ")"
			+ " wrong=" + build.plan().wrongNotes()
			+ " dead=" + build.reading().unreachedNotes());
		if (build.named()) {
			System.out.println("   shapes named, so collisions did not throw: "
				+ build.plan().collisions().size() + " cells were held by whoever got there first"
				+ (build.mode() == SongBuilder.PasteMode.ULTRA_COMPACT_LANE_V2
					? " -- and v2's trial fallback never fired, so this is not the build that ships"
					: ""));
		}
		deadLines(build, perKind);
		// Repeaters with nothing behind them, which the reader cannot see: it starts a fresh
		// performance at each and calls everything after it reached. Listed with their cells,
		// because the census counts them and nothing else here said where they were.
		// And what the reader itself counted as ways in -- the census's "severed" column is its
		// versions less one -- with whatever it warned about them.
		if (build.reading().versions() > 1 || !build.reading().warnings().isEmpty()) {
			System.out.println();
			System.out.println("#### VERSIONS -- the reader found " + build.reading().versions()
				+ " performances");
			for (String warning : build.reading().warnings()) {
				System.out.println("   " + warning);
			}
		}
		if (!build.plan().faultSites().severed().isEmpty()) {
			System.out.println();
			System.out.println("#### SEVERED -- " + build.plan().faultSites().severed().size()
				+ " repeaters have nothing behind them to read, at "
				+ build.plan().faultSites().severed().stream()
					.map(at -> at.getX() + " " + at.getY() + " " + at.getZ())
					.collect(java.util.stream.Collectors.joining(" | ")));
		}
		gone(build, perKind);
		wrong(build, perKind);
		outside(build, perKind);
	}

	private static void gone(Build build, int perKind) {
		List<String> missing = missing(build);
		if (missing.isEmpty()) {
			System.out.println("   no missing note: every note the song holds is in the build");
			return;
		}
		System.out.println();
		for (String one : missing.subList(0, Math.min(perKind, missing.size()))) {
			System.out.println("#### MISSING NOTE -- " + one);
		}
		if (missing.size() > perKind) {
			System.out.println("   ... and " + (missing.size() - perKind) + " more chords");
		}
	}

	private static void deadLines(Build build, int perKind) {
		List<Dead> dead = deadWires(build);
		if (dead.isEmpty()) {
			System.out.println("   no dead wire: every note the build holds is reached");
			return;
		}
		for (Dead one : dead.subList(0, Math.min(perKind, dead.size()))) {
			System.out.println();
			System.out.println("#### DEAD WIRE -- " + one);
			Break broke = firstBreak(build);
			if (broke != null) {
				System.out.println("   " + broke);
			}
			System.out.println("   look for wire, block, wire: a block lit only by dust is soft "
				+ "powered and lights no dust of its own");
			// Along the lane, because that is the axis a run of dust lies on and the break is a
			// three-cell shape along it. The frontier and the break are usually the same lane, so one
			// window holds both.
			//
			// One column either side in z, and it is not decoration. A dead note is a note, and a note
			// hangs *beside* the lane that failed to reach it -- so a window drawn on the notes' own z
			// shows the two notes and none of the wire between them, which is every block that matters.
			// This drew exactly that empty picture for the first fault it was ever pointed at.
			BlockPos[] box = box(build, one.frontier(), one.at(), 2, 1, 1, 4);
			System.out.println(draw(build, box[0], box[1], AsciiDiagram.View.NORTH));
			System.out.println(shapesIn(build, box[0], box[1]));
			// Both views, and neither is optional -- the same rule the wrong-note render already keeps.
			// The side view shows the levels, which is where a soft-powered block or a run that steps
			// down a level can be seen at all; the top view shows which lane the other end is in and how
			// the route runs through the break. This used to draw the top view only when the two ends sat
			// in different lanes, which is exactly the fault that does not need it: a break within one
			// lane is the commoner kind and the one whose route is hardest to read off levels alone.
			System.out.println("   from above, which shows the route through the break:");
			System.out.println(around(build, one.frontier(), one.at(), AsciiDiagram.View.TOP,
				2, 1, 0, 3));
		}
	}

	private static void wrong(Build build, int perKind) {
		List<Wrong> wrong = wrongNotes(build);
		if (wrong.isEmpty()) {
			System.out.println("   no wrong note: every note is set off on its own tick and no other");
			return;
		}
		for (Wrong one : wrong.subList(0, Math.min(perKind, wrong.size()))) {
			System.out.println();
			System.out.println("#### WRONG NOTE -- " + one);
			System.out.println("   " + whose(build, one));
			// Both views, and neither is optional. The levels are what the fault text cannot say, and
			// which lane the other end is in is what tells a module reaching into the next from the
			// corridor alongside -- and those two want opposite fixes.
			System.out.println("   along the lane, which shows the levels:");
			System.out.println(around(build, one.at(), one.from(), AsciiDiagram.View.NORTH,
				3, 0, 1, 4));
			System.out.println("   from above, which shows which lane the other end is in:");
			BlockPos[] box = box(build, one.at(), one.from(), 3, 2, 0, 3);
			System.out.println(draw(build, box[0], box[1], AsciiDiagram.View.TOP));
			System.out.println(shapesIn(build, box[0], box[1]));
		}
	}

	private static void outside(Build build, int perKind) {
		// The plan's own reading first: every cell measured against the wall of the leg that laid it,
		// inner walls included, which is the one reading that sees a seam or a walk-out standing in
		// the partner's ground. The turn-based reading below is the walk's opinion of itself.
		List<SongBuilder.WallBreach> walls = build.plan().wallBreaches();
		if (walls.isEmpty()) {
			System.out.println("   no wall breach: every leg stayed inside its own two walls");
		}
		for (SongBuilder.WallBreach one : walls.subList(0, Math.min(perKind, walls.size()))) {
			System.out.println();
			System.out.println("#### WALL BREACH -- " + one);
			BlockPos at = one.furthest();
			// Out to the far wall of the build, so both walls and the partner's rows are in frame.
			BlockPos wallward = new BlockPos(
				one.nearSide() ? build.plan().farWall() : build.plan().nearWall(),
				at.getY(), at.getZ());
			System.out.println("   from above, the leg, its furthest cell and both walls:");
			System.out.println(around(build, at, wallward, AsciiDiagram.View.TOP, 2, 3, 0, 3));
			BlockPos[] box = box(build, at, wallward, 2, 3, 0, 3);
			System.out.println(shapesIn(build, box[0], box[1]));
		}
		List<Breach> breaches = breaches(build);
		if (breaches.isEmpty()) {
			System.out.println("   no breach: every lane stayed inside the width it promised");
			return;
		}
		for (Breach one : breaches.subList(0, Math.min(perKind, breaches.size()))) {
			System.out.println();
			System.out.println("#### BREACH -- " + one);
			for (String fault : build.plan().faults()) {
				if (fault.startsWith("a lane turned")) {
					System.out.println("   fault " + fault);
				}
			}
			// Centred on the turn, because the turn is what the breach is. The furthest block is
			// quoted in the heading and is worth going to stand at, but it is as likely to be the
			// sideways run or the slab step -- which are supposed to be outside -- as the fault.
			BlockPos at = one.turn();
			// Out to the other wall, not merely around the turn, because a breach is a lane against a
			// wall and the wall has to be in the picture for the number of columns to mean anything.
			BlockPos wallward = new BlockPos(
				one.nearSide() ? build.plan().farWall() : build.plan().nearWall(),
				at.getY(), at.getZ());
			System.out.println("   from above, the corridor and both walls:");
			System.out.println(around(build, at, wallward, AsciiDiagram.View.TOP, 2, 2, 0, 3));
			System.out.println("   along the lane, which shows the staircase that wanted the columns:");
			BlockPos[] box = box(build, at, wallward, 2, 0, 1, 6);
			System.out.println(draw(build, box[0], box[1], AsciiDiagram.View.NORTH));
			System.out.println(shapesIn(build, box[0], box[1]));
		}
	}

	// ---- odds and ends ------------------------------------------------------------------------

	/** Space separated, because these are pasted straight into /tp. */
	static String say(BlockPos at) {
		return at.getX() + " " + at.getY() + " " + at.getZ();
	}

	private static BlockPos positionAfter(String text, String marker) {
		return positionAfter(text, marker, 0);
	}

	/** A coordinate with the sentence's punctuation taken off it. */
	private static int number(String word) {
		return Integer.parseInt(word.replaceAll("[^-0-9].*$", ""));
	}

	/**
	 * The three numbers following a marker, or null.
	 *
	 * <p>Null rather than a throw: the fault list is prose and it has gained clauses twice already,
	 * so a parser that stops the run when the wording moves is worse than one that says it could not
	 * read this line.</p>
	 */
	private static BlockPos positionAfter(String text, String marker, int startAt) {
		int at = text.indexOf(marker, startAt);
		if (at < 0) {
			return null;
		}
		String[] word = text.substring(at + marker.length()).strip().split(" ");
		if (word.length < 3) {
			return null;
		}
		try {
			// Trimmed of whatever punctuation the sentence carries on with. A position at the end of a
			// clause reads "at 39 72 3, too late for..." and the comma made the parse fail, which this
			// returns as null -- and null here means "no aggressor", so a wrong note with a perfectly
			// well named other end was reported as a note with nothing at all to set it off. One of the
			// two wrong notes left in the library was mis-described that way, by me, in this session.
			return new BlockPos(number(word[0]), number(word[1]), number(word[2]));
		} catch (NumberFormatException notAPosition) {
			return null;
		}
	}
}
