package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;

/**
 * A GUI scale of its own for the mod's screens.
 *
 * <p>The Composer is a piano roll, and a piano roll wants pixels. The scale that makes a hotbar
 * comfortable is rarely the one that shows four bars of a song at once, and having to change
 * Minecraft's own scale before opening a song and back again afterwards is the kind of friction
 * that stops people opening the song.</p>
 *
 * <p>It works by moving the window's scale rather than by transforming what is drawn. That sounds
 * like the heavier option and is by far the lighter one: every hit test, every layout constant and
 * every mouse coordinate in the Composer is in scaled units already, so the whole screen follows
 * for free. Scaling the pose stack instead would leave the drawing at one size and the clicks at
 * another, and there are several thousand lines of hit testing that would each have to be told.</p>
 *
 * <p>Driven from the client tick rather than from the screens themselves. A screen can be left by
 * more routes than it can be entered by -- closing to the world, closing to a parent, being
 * replaced by a confirm, or the game putting something else up -- and a restore hung off any one
 * of them is a restore that eventually gets missed, leaving the world itself at the Composer's
 * scale. Asking once a tick what is on screen cannot miss.</p>
 */
public final class ComposerScale {
	/** Whether the window is currently carrying our scale rather than the game's. */
	private static boolean applied;
	/**
	 * Whether a screen is being laid out by this class right now.
	 *
	 * <p>See {@link #guarded}.</p>
	 */
	private static boolean laying;

	private ComposerScale() {
	}

	public static void tick(Minecraft minecraft) {
		update(minecraft, minecraft.gui.screen());
	}

	/**
	 * The same decision, taken as a screen opens rather than at the end of the tick.
	 *
	 * <p>The tick is what makes this reliable -- a screen can be left by more routes than it can be
	 * entered by, so nothing hung off an entry point can be trusted to cover every exit. But a tick
	 * is up to a twentieth of a second away, and the composer is expensive enough to open that the
	 * first few frames are slow ones: long enough to watch the whole screen sitting at the game's
	 * scale and then jump to its own. Deciding here as well means the first frame drawn is already
	 * the right size, and the tick goes on being the thing that cannot miss.</p>
	 *
	 * <p>Called after the screen's own init has finished, never before it. Changing the scale
	 * re-lays the screen out, and a relayout that happens part-way through init is undone by the
	 * rest of it: the size init was called with gets written back over the corrected one, and the
	 * screen draws at the new scale in the old window's shape.</p>
	 *
	 * <p>The screen is passed in rather than read back off the client, because the client's idea of
	 * what is on screen during a transition is not worth relying on.</p>
	 */
	public static void screenOpened(Minecraft minecraft, Screen screen) {
		guarded(() -> update(minecraft, screen));
	}

	/**
	 * Gives the game its scale back the moment one of our screens goes away.
	 *
	 * <p>Closing to the world opens nothing, so there is no init to hang this off -- the tick was
	 * the only thing putting the scale back, and up to a twentieth of a second of frames were drawn
	 * before it did. Nothing is on screen then except the HUD, so what that looked like was the
	 * hotbar changing size a moment after the composer closed.</p>
	 *
	 * <p>Restoring even when the next screen is also ours, because at this point there is no way to
	 * know that it is: the screen being removed is all anyone has been told. Its own init puts the
	 * scale back, and both happen before a frame is drawn, so the only cost is a layout pass on a
	 * screen that was about to be thrown away.</p>
	 *
	 * <p>Called from each of our screens rather than from Fabric's screen-removed event, which
	 * targets a method this version of the game does not have any more -- screens moved from the
	 * client to the GUI, and the event has been quietly firing for nobody. {@code Screen.removed}
	 * is vanilla, is called by the swap itself, and cannot go the same way.</p>
	 */
	public static void screenClosed(Screen screen) {
		if (!isOurs(screen)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		guarded(() -> restore(minecraft));
	}

	/**
	 * Runs one pass of the scale, and only one.
	 *
	 * <p>Changing it re-lays out whatever is on screen, which runs that screen's init, which fires
	 * the hook that brought us here. Without this the two would call each other -- and worse, a
	 * restore would be undone by the apply its own relayout triggered.</p>
	 */
	private static void guarded(Runnable work) {
		if (laying) {
			return;
		}
		laying = true;
		try {
			work.run();
		} finally {
			laying = false;
		}
	}

	private static void update(Minecraft minecraft, Screen screen) {
		if (isOurs(screen)) {
			apply(minecraft);
		} else if (!borrowsOurScale(screen)) {
			restore(minecraft);
		}
	}

	/**
	 * Whether a screen that is not ours should nonetheless keep our scale.
	 *
	 * <p>Only the two vanilla screens we put up ourselves: a confirm, and the key bindings the
	 * settings screen links to. Both are the middle of something started in our own UI, and
	 * flipping the scale under one and back is a jolt in the middle of answering a question.</p>
	 *
	 * <p>An allowlist rather than "restore only at the world", which was the first try and leaked:
	 * at the title screen there is always a screen and never a null one, so settings opened from
	 * Mod Menu there would have held the whole main menu at the Composer's scale.</p>
	 */
	private static boolean borrowsOurScale(Screen screen) {
		return screen instanceof ConfirmScreen || screen instanceof KeyBindsScreen;
	}

	/**
	 * Whether this screen is one of ours.
	 *
	 * <p>By package rather than by a marker interface, so a screen added later is covered without
	 * anyone having to remember this file exists.</p>
	 */
	private static boolean isOurs(Screen screen) {
		return screen != null && screen.getClass().getName().startsWith("com.fastnoteblocks.");
	}

	/**
	 * What a setting value means, said plainly.
	 *
	 * <p>Names the scale the window would really use when it differs from the one asked for. A
	 * slider whose top three notches all do the same thing, silently, is a slider that reads as
	 * broken.</p>
	 */
	public static String describe(int scale) {
		if (scale == FastNoteblocksConfig.SAME_GUI_SCALE_AS_MINECRAFT) {
			return "Same as Minecraft";
		}
		Minecraft minecraft = Minecraft.getInstance();
		int fits = minecraft.getWindow().calculateScale(scale, minecraft.isEnforceUnicode());
		return fits == scale ? scale + "x" : scale + "x (this window fits " + fits + "x)";
	}

	private static void apply(Minecraft minecraft) {
		int wanted = FastNoteblocksConfig.get().composerGuiScale();
		if (wanted == FastNoteblocksConfig.SAME_GUI_SCALE_AS_MINECRAFT) {
			restore(minecraft);
			return;
		}
		Window window = minecraft.getWindow();
		// The window has the last word on what fits, so ask it rather than trusting the setting.
		int scale = window.calculateScale(wanted, minecraft.isEnforceUnicode());
		if (applied && window.getGuiScale() == scale) {
			return;
		}
		window.setGuiScale(scale);
		applied = true;
		relayout(minecraft, window);
	}

	private static void restore(Minecraft minecraft) {
		if (!applied) {
			return;
		}
		applied = false;
		// resizeGui recomputes the scale from the game's own option, which is exactly the value we
		// took the window away from -- so putting it back needs no record of what it was.
		minecraft.resizeGui();
	}

	/**
	 * Everything {@code Minecraft.resizeGui} does after choosing a scale.
	 *
	 * <p>Copied rather than called, because the first thing that method does is set the scale from
	 * {@code options.guiScale()} -- so calling it here would overwrite the scale we just chose with
	 * the game's, one line before telling the screen about it. It did exactly that, silently, and
	 * the setting looked inert.</p>
	 *
	 * <p>Notably not by writing our scale into {@code options.guiScale()} and letting the game do
	 * the rest: the Keys tab calls {@code options.save()} when a binding changes, which would write
	 * the Composer's scale into options.txt as though the player had chosen it.</p>
	 */
	private static void relayout(Minecraft minecraft, Window window) {
		Screen screen = minecraft.gui.screen();
		if (screen != null) {
			screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
		}
		minecraft.mouseHandler.setIgnoreFirstMove();
	}
}
