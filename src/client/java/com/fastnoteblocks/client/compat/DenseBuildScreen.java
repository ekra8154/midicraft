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
	private final Runnable proceed;

	DenseBuildScreen(Screen parent, int wrongNotes, int breaches, int worstBreach,
			Runnable proceed) {
		super(Component.literal("Dense composition"));
		this.parent = parent;
		this.wrongNotes = wrongNotes;
		this.breaches = breaches;
		this.worstBreach = worstBreach;
		this.proceed = proceed;
	}

	/** Whether a plan is clean enough to paste without asking. */
	static boolean needsAsking(SongBuilder.PastePlan plan) {
		return plan.wrongNotes() > 0 || !plan.breaches().isEmpty();
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
