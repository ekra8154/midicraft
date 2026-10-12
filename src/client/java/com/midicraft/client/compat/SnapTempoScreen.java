package com.midicraft.client.compat;

import com.midicraft.client.McCompat;
import com.midicraft.client.MidicraftConfig;
import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.composer.ComposerProject.TempoPick;
import com.midicraft.client.composer.ComposerProject.TempoPicks;
import com.midicraft.client.composer.ComposerProject.TempoScan;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Snap tempo, asked as a share: the nearest tempo, faster and slower, at which at least so many of
 * the notes land on whole build ticks.
 *
 * <p>The old snap asked for every note, through the gcd of every gap, and a few strays drove that
 * grid so fine it either slowed the song to a crawl or gave up. Here the player says how many is
 * enough, and sees both directions at once: closest is usually right, but where the two are near
 * a tie, faster buys a shorter build and slower gives the tightest notes room, and that is the
 * player's call rather than a setting to forget about. The closer of the two is what Enter takes.</p>
 *
 * <p>Counted the way the status line counts "off grid", so the notes this leaves off are the ones
 * it will then name. See {@link ComposerProject#tempoScan}.</p>
 */
final class SnapTempoScreen extends ModalPanelScreen {
	private static final int PANEL_WIDTH = 330;
	private static final int ROW_HEIGHT = 24;
	/** How far either way the tempo may move: up to 1.25x faster or slower. */
	private static final double MAX_CHANGE = 0.25;

	/** What the player chose: the tempo, and whether to quantize what it leaves off the grid. */
	record Choice(int tempo, boolean quantize, boolean gameTicks, int onTick, int total) {
	}

	private final ComposerProject song;
	private final java.util.function.Consumer<Choice> onChoose;
	/** Scanned once per kind of tick, on first asking; the share only re-picks from it. */
	private TempoScan repeaterScan;
	private TempoScan gameScan;
	private EditBox percent;
	private Button faster;
	private Button slower;
	private TempoPicks picks;

	/**
	 * @param song the song with its speed already folded into the tempo, as every timing step
	 *     works on it
	 */
	SnapTempoScreen(Screen parent, ComposerProject song, java.util.function.Consumer<Choice> onChoose) {
		super(Component.literal("Snap tempo"), parent);
		this.song = song;
		this.onChoose = onChoose;
	}

	@Override
	protected void init() {
		MidicraftConfig config = MidicraftConfig.get();
		panel(PANEL_WIDTH, PADDING + TITLE_HEIGHT + 3 * ROW_HEIGHT + 14 + 2 * ROW_HEIGHT + 6
			+ BUTTON_HEIGHT + PADDING);
		int left = panelLeft + PADDING;
		int y = contentTop();

		String typed = percent == null ? Integer.toString(config.snapTempoPercent())
			: percent.getValue();
		int labelWidth = font.width("Notes on grid: ");
		percent = new EditBox(font, left + labelWidth, y, 36, BUTTON_HEIGHT, Component.literal("Percent"));
		percent.setMaxLength(3);
		percent.setValue(typed);
		percent.setResponder(text -> {
			// Digits only. The box has no filter of its own on this version, so anything else is
			// taken back out, which calls this again with the cleaned text.
			String digits = text.replaceAll("[^0-9]", "");
			if (!digits.equals(text)) {
				percent.setValue(digits);
				return;
			}
			if (!text.isEmpty()) {
				config.setSnapTempoPercent(Integer.parseInt(text));
				MidicraftConfig.save();
			}
			refresh();
		});
		addRenderableWidget(percent);
		y += ROW_HEIGHT;

		addRenderableWidget(Checkbox.builder(Component.literal("2-lane snap (game ticks)"), font)
			.pos(left, y)
			.selected(config.snapTempoGameTicks())
			.tooltip(Tooltip.create(Component.literal("Aim at game ticks, which a 2-lane build "
				+ "can place: the tempo moves about half as far. Off aims at repeater ticks.")))
			.onValueChange((box, value) -> {
				config.setSnapTempoGameTicks(value);
				MidicraftConfig.save();
				refresh();
			})
			.build());
		y += ROW_HEIGHT;
		addRenderableWidget(Checkbox.builder(Component.literal("Quantize the notes left off the grid"), font)
			.pos(left, y)
			.selected(config.snapTempoQuantizes())
			.tooltip(Tooltip.create(Component.literal("Then move the remaining notes onto the "
				+ "nearest tick, as Convert would.")))
			.onValueChange((box, value) -> {
				config.setSnapTempoQuantizes(value);
				MidicraftConfig.save();
			})
			.build());
		y += ROW_HEIGHT + 14;

		faster = addRenderableWidget(Button.builder(Component.empty(), pressed -> use(picks.faster()))
			.bounds(left, y, rowWidth(), BUTTON_HEIGHT).build());
		y += ROW_HEIGHT;
		slower = addRenderableWidget(Button.builder(Component.empty(), pressed -> use(picks.slower()))
			.bounds(left, y, rowWidth(), BUTTON_HEIGHT).build());

		addRenderableWidget(Button.builder(Component.literal("Cancel (Esc)"), pressed -> onClose())
			.bounds(left, buttonTop(), rowWidth(), BUTTON_HEIGHT).build());
		refresh();
	}

	private boolean gameTicks() {
		return MidicraftConfig.get().snapTempoGameTicks();
	}

	private TempoScan scan() {
		if (gameTicks()) {
			if (gameScan == null) {
				gameScan = song.tempoScan(true, MAX_CHANGE);
			}
			return gameScan;
		}
		if (repeaterScan == null) {
			repeaterScan = song.tempoScan(false, MAX_CHANGE);
		}
		return repeaterScan;
	}

	private double share() {
		String text = percent == null ? "" : percent.getValue();
		int value = text.isEmpty() ? MidicraftConfig.get().snapTempoPercent() : Integer.parseInt(text);
		return Math.max(1, Math.min(100, value)) / 100.0;
	}

	/** Picks again for the share and the tick, and says so on the two buttons. */
	private void refresh() {
		if (faster == null) {
			return;
		}
		TempoScan scan = scan();
		picks = scan == null ? null : scan.pick(share());
		TempoPick closer = closer();
		label(faster, "Faster", picks == null ? null : picks.faster(), closer);
		label(slower, "Slower", picks == null ? null : picks.slower(), closer);
	}

	/** The pick Enter takes: the nearer of the two that reach the share. */
	private TempoPick closer() {
		if (picks == null) {
			return null;
		}
		TempoPick fast = picks.faster() != null && picks.faster().reaches() ? picks.faster() : null;
		TempoPick slow = picks.slower() != null && picks.slower().reaches() ? picks.slower() : null;
		if (fast == null || slow == null) {
			return fast == null ? slow : fast;
		}
		TempoScan scan = scan();
		return scan.distance(fast.tempo()) <= scan.distance(slow.tempo()) ? fast : slow;
	}

	private void label(Button button, String side, TempoPick pick, TempoPick closer) {
		if (pick == null) {
			button.setMessage(Component.literal(side + ": nothing within "
				+ Math.round(MAX_CHANGE * 100) + "%"));
			button.active = false;
			return;
		}
		String share = percentOf(pick.tempo().onTick());
		if (!pick.reaches()) {
			button.setMessage(Component.literal(side + ": best is " + share + " at "
				+ TempoText.factor(song.tempoMicrosPerQuarter(), pick.tempo().tempo())));
			button.active = false;
			return;
		}
		int tempo = pick.tempo().tempo();
		button.setMessage(Component.literal(side + ": "
			+ TempoText.factor(song.tempoMicrosPerQuarter(), tempo) + " (" + TempoText.bpm(tempo)
			+ "), " + share + " on grid" + (pick == closer ? " (Enter)" : "")));
		button.active = true;
	}

	private void use(TempoPick pick) {
		if (pick == null || !pick.reaches()) {
			return;
		}
		McCompat.setScreen(parent);
		onChoose.accept(new Choice(pick.tempo().tempo(), MidicraftConfig.get().snapTempoQuantizes(),
			gameTicks(), pick.tempo().onTick(), scan().total()));
	}

	@Override
	protected void confirm() {
		TempoPick closer = closer();
		if (closer == null) {
			onClose();
			return;
		}
		use(closer);
	}

	@Override
	protected void extractPanelContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelLeft + PADDING;
		int y = contentTop();
		graphics.text(font, "Notes on grid:", left, y + 6, TEXT_COLOR, false);
		graphics.text(font, "%", left + font.width("Notes on grid: ") + 40, y + 6, TEXT_COLOR, false);
		TempoScan scan = scan();
		String now;
		if (scan == null) {
			now = "Not enough notes to work out a spacing.";
		} else {
			int on = scan.now() == null ? 0 : scan.now().onTick();
			now = "Now: " + TempoText.bpm(scan.current()) + ", " + percentOf(on) + " on grid ("
				+ String.format(Locale.ROOT, "%,d / %,d", on, scan.total()) + ")";
			if (picks != null && on >= picks.needed()) {
				now += ", already enough";
			}
		}
		graphics.text(font, now, left, contentTop() + 3 * ROW_HEIGHT, HINT_COLOR, false);
	}

	private String percentOf(int notes) {
		TempoScan scan = scan();
		int total = scan == null ? 0 : scan.total();
		return total == 0 ? "0%" : (int)Math.floor(100.0 * notes / total) + "%";
	}

}
