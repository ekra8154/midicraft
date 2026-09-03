package com.midicraft.client.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Asks before pasting a build the layout could not place cleanly at these settings.
 *
 * <p>Two things can go wrong and they are worth saying apart, because only one of them touches
 * anything outside the build. A doubled note is local: a note block fires on a rising edge, the
 * chain is one travelling pulse, so an extra sounding adds a sound and takes nothing away, and the
 * rest of the song plays as written. A breached footprint is not local at all -- the paste puts
 * blocks where the player was told it would not, over whatever was already standing there.</p>
 *
 * <p>Asked rather than refused. A song dense enough to do this is still worth building, and the
 * degenerate case degrades gracefully: a song of nothing but huge chords a tick apart simply
 * oversteps once and runs on in a straight line. Refusing it would be refusing the only shape it
 * has.</p>
 */
final class DenseBuildScreen extends Screen {
	private final Screen parent;
	private final int wrongNotes;
	private final int breaches;
	private final int worstBreach;
	private final int innerWalls;
	private final int outerWalls;
	private final int worstWall;
	private final int missingNotes;
	private final int deadNotes;
	private final int collisions;
	private final int severedLanes;
	private final Runnable proceed;

	DenseBuildScreen(Screen parent, SongBuilder.PastePlan plan, Runnable proceed) {
		super(Component.literal("Dense composition"));
		this.parent = parent;
		this.wrongNotes = plan.wrongNotes();
		this.breaches = plan.breaches().size();
		this.worstBreach = plan.worstBreach();
		this.innerWalls = (int) plan.innerWallBreaches();
		this.outerWalls = (int) plan.outerWallBreaches();
		this.worstWall = plan.worstWallBreach();
		this.missingNotes = plan.missingNotes();
		this.deadNotes = plan.deadNotes();
		this.collisions = plan.collisions().size();
		this.severedLanes = plan.severedLanes();
		this.proceed = proceed;
	}

	/**
	 * Whether a plan is clean enough to paste without asking.
	 *
	 * <p>All five faults, where it used to be two. A doubled note and a breach were asked about; a
	 * note the build does not contain, and a song silenced from a break onward, were not -- so the
	 * two that actually cost the player their music were the two that went up without a word. The
	 * plan has always known both.</p>
	 *
	 * <p>And the fifth, which used to end the paste rather than be asked about: a cell two shapes both
	 * wanted. Nothing here is to do with the debug paste, which only decides whether that cell also
	 * gets a lantern dropped on it.</p>
	 */
	static boolean needsAsking(SongBuilder.PastePlan plan) {
		return plan.wrongNotes() > 0 || !plan.breaches().isEmpty()
			|| !plan.wallBreaches().isEmpty()
			|| plan.missingNotes() > 0 || plan.deadNotes() > 0 || !plan.collisions().isEmpty()
			|| plan.severedLanes() > 0;
	}

	/**
	 * The lines to show, in the order the player needs them.
	 *
	 * <p>The footprint first when both apply: it is the one that can damage something that was not
	 * part of this song, and so the one that decides the answer.</p>
	 */
	private List<String> lines() {
		List<String> lines = new ArrayList<>();
		lines.add("This composition is dense, and at these settings:");
		// Worst first, and that is a different order from the one the footprint used to have to
		// itself. These two take music away -- a break silences everything after it, and a note with
		// nowhere to go is simply absent -- where a breach takes ground and a doubled note adds a
		// sound. Whichever of them applies is the one that decides the answer.
		// A contested cell first of all, because it is the only one of the five that is a fault in the
		// layout rather than in the fit: the other four say this song does not go in this space, and
		// this one says two shapes were both told they could have one block. It is also the cause of
		// most of what follows it -- the shape that lost its block has dead wire above it -- so naming
		// it first stops the same fault being read four times over.
		if (collisions > 0) {
			lines.add("- " + count(collisions, "cell") + " were wanted by two shapes at once. The");
			lines.add("  second one lost its block, and the wire above it is dead.");
		}
		// Before the note count, and separately from it, because the two are not the same measurement:
		// a repeater with nothing behind it reads to the machine reader as a second lever, so a build
		// cut in four places can report a cut lane here and nought silent notes below.
		if (severedLanes > 0) {
			lines.add("- the lane is cut in " + count(severedLanes, "place") + ": a repeater there has");
			lines.add("  nothing behind it to read, and everything after it is silent.");
		}
		if (deadNotes > 0) {
			lines.add("- " + count(deadNotes, "note") + " would never play at all: the wire dies");
			lines.add("  part way, and everything after the break is silent.");
		}
		if (missingNotes > 0) {
			lines.add("- " + count(missingNotes, "note") + " had nowhere to hang and would be left");
			lines.add("  out of the build entirely.");
		}
		// The walls, measured off the blocks rather than off the walk's opinion of itself. An inner
		// wall crossed is one machine of a dual build standing in the other's ground -- the two
		// collide there, and that is what most of the dead lines above come from. An outer wall
		// crossed is the build wider than it said, which the footprint line below also says where
		// the walk noticed it; this one says it where the walk did not.
		if (innerWalls > 0) {
			lines.add("- " + count(innerWalls, "lane") + " would run into the other machine's ground,");
			lines.add("  by at most " + count(worstWall, "block") + ". The two collide there.");
		}
		if (outerWalls > 0) {
			lines.add("- " + count(outerWalls, "lane") + " would run past the outer wall, by at most "
				+ count(worstWall, "block") + ".");
		}
		if (breaches > 0) {
			lines.add("- it would breach its footprint " + count(breaches, "time")
				+ ", by at most " + count(worstBreach, "block") + ".");
			lines.add("  Anything already built in that space would be overwritten.");
		}
		if (wrongNotes > 0) {
			lines.add("- it would likely cause " + count(wrongNotes, "note") + " to play twice.");
			lines.add("  The rest of the song is unaffected.");
		}
		lines.add("Try a greater width, a different floor count, or another paste type.");
		return lines;
	}

	private static String count(int many, String noun) {
		return many + " " + noun + (many == 1 ? "" : "s");
	}

	@Override
	protected void init() {
		clearWidgets();
		int buttonWidth = Math.min(150, (width - 30) / 2);
		int top = height / 2 + 40;
		int left = (width - (buttonWidth * 2 + 8)) / 2;
		addRenderableWidget(Button.builder(Component.literal("Paste anyway"), button -> proceed.run())
			.bounds(left, top, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(left + buttonWidth + 8, top, buttonWidth, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		List<String> lines = lines();
		int y = height / 2 - 10 - lines.size() * 6;
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			graphics.centeredText(font, Component.literal(line), width / 2, y,
				index == 0 ? 0xFFFFFFFF : 0xFF8A9098);
			y += index == 0 ? 16 : 12;
		}
	}

	/** Escape means "do not paste": the destructive answer is never the default. */
	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/**
	 * Hands the game its GUI scale back the instant this screen goes, whatever it is going to.
	 *
	 * <p>Vanilla calls this from the middle of the screen swap, so it lands before anything is
	 * drawn. If another of our screens is opening it puts the scale straight back in its own init,
	 * and if nothing is, the HUD behind this one is already the right size on the very next frame
	 * rather than a tick later.</p>
	 */
	@Override
	public void removed() {
		ComposerScale.screenClosed(this);
		super.removed();
	}
}
