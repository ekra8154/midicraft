package com.fastnoteblocks.client.compat;

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
	private final int missingNotes;
	private final int deadNotes;
	private final Runnable proceed;

	DenseBuildScreen(Screen parent, SongBuilder.PastePlan plan, Runnable proceed) {
		super(Component.literal("Dense composition"));
		this.parent = parent;
		this.wrongNotes = plan.wrongNotes();
		this.breaches = plan.breaches().size();
		this.worstBreach = plan.worstBreach();
		this.missingNotes = plan.missingNotes();
		this.deadNotes = plan.deadNotes();
		this.proceed = proceed;
	}

	/**
	 * Whether a plan is clean enough to paste without asking.
	 *
	 * <p>All four faults, where it used to be two. A doubled note and a breach were asked about; a
	 * note the build does not contain, and a song silenced from a break onward, were not -- so the
	 * two that actually cost the player their music were the two that went up without a word. The
	 * plan has always known both. Nothing here is to do with the debug paste, which only decides what
	 * the blocks are coloured.</p>
	 */
	static boolean needsAsking(SongBuilder.PastePlan plan) {
		return plan.wrongNotes() > 0 || !plan.breaches().isEmpty()
			|| plan.missingNotes() > 0 || plan.deadNotes() > 0;
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
		if (deadNotes > 0) {
			lines.add("- " + count(deadNotes, "note") + " would never play at all: the wire dies");
			lines.add("  part way, and everything after the break is silent.");
		}
		if (missingNotes > 0) {
			lines.add("- " + count(missingNotes, "note") + " had nowhere to hang and would be left");
			lines.add("  out of the build entirely.");
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
}
