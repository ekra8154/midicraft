package com.fastnoteblocks.client;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.client.compat.ComposerScreen;
import com.fastnoteblocks.client.compat.PreviewInstrument;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class NoteBlockOverlay {
	private static final int RESCAN_INTERVAL_TICKS = 10;
	private static final int PLACEMENT_WATCH_TICKS = 12;
	private static final int INTERACTION_ACK_TIMEOUT_TICKS = 40;
	private static final int SEQUENCE_HUD_TICKS = 50;
	private static final int SEQUENCE_ADVANCE_HUD_TICKS = 32;
	/** One row of a chord card. Two of them make a card, as two notes make a block of bus. */
	private static final int TILE_ROW_HEIGHT = 20;
	private static final int CARD_GAP = 8;
	/**
	 * The block a note wants under it, drawn at full size and centred on its tile's top-left corner.
	 *
	 * <p>Sitting on the corner rather than inside the box is what lets it be the biggest thing on the
	 * card without the pitch moving aside for it. It costs the tile both of its corners, though: half
	 * an icon hangs into the tile to the left as well, so the pitch has to clear
	 * {@link #ICON_SIZE}/2 at each end and the tile is sized for that.</p>
	 */
	private static final int ICON_SIZE = 16;
	/**
	 * How far the icon's top-left sits outside its tile's.
	 *
	 * <p>Sitting fully inside left no room for the pitch; centred on the corner took half the icon
	 * into the tile before it and cost the pitch both ends of the tile. This is between the two.</p>
	 */
	private static final int ICON_OUT_X = 3;
	private static final int ICON_OUT_Y = 6;
	private static final float NOTE_SCALE = 0.65F;
	/**
	 * Small, but not below what the font can actually draw.
	 *
	 * <p>0.35 put a digit under three pixels tall and it came out as marks rather than numbers.</p>
	 */
	private static final float LAYER_SCALE = 0.5F;
	private static final int SEQUENCE_DOUBLE_TAP_TICKS = 7;
	private static final double LABEL_Y = 1.40;
	private static final double REPEATER_LABEL_Y = LABEL_Y - 0.75;
	private static final double MENU_HORIZONTAL_RADIUS = 0.72;
	private static final double MENU_VERTICAL_RADIUS = 0.50;
	private static final double REPEATER_MENU_HORIZONTAL_RADIUS = 0.24;
	private static final double REPEATER_MENU_VERTICAL_RADIUS = 0.20;
	private static final char[] FAMILIES = {'A', 'B', 'C', 'D', 'E', 'F', 'G'};
	private static List<FastNoteblocksConfig.SequenceTrack> flattenedFrom;
	private static List<NoteSequence.Placement> flattened = List.of();
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath("fast-noteblocks", "controls")
	);
	public static final NoteBlockOverlay INSTANCE = new NoteBlockOverlay();

	private final List<BlockPos> nearbyNoteBlocks = new ArrayList<>();
	private final List<BlockPos> nearbyRepeaters = new ArrayList<>();
	private final Deque<PendingClicks> clickQueue = new ArrayDeque<>();
	private final Map<BlockPos, ExpectedStep> expectedSteps = new HashMap<>();
	private final Map<BlockPos, PlacementWatch> placementWatches = new HashMap<>();
	private final KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.fast-noteblocks.toggle", InputConstants.Type.KEYSYM, 78, CATEGORY
	));
	private final KeyMapping placementSequenceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.fast-noteblocks.toggle_placement_sequence", InputConstants.Type.KEYSYM, -1, CATEGORY
	));
	// M, next to the overlay toggle on N. Bound by default because the composer is now the way in
	// to every song, and an unbound key made it reachable only through Mod Menu.
	private final KeyMapping composerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.fast-noteblocks.open_composer", InputConstants.Type.KEYSYM, 77, CATEGORY
	));

	private int ticksUntilRescan;
	private ClientLevel lastLevel;
	private BlockPos expandedBlock;
	private Character menuCenterFamily;
	private BlockPos expandedRepeater;
	private Integer repeaterBottomDelay;
	private BlockPos radialFocusCandidate;
	private boolean radialFocusCandidateRepeater;
	private long radialFocusStartedTick;
	private int placementSequenceIndex;
	private boolean sequencePositionSavePending;
	private boolean lastPlacementSequenceEnabled;
	private boolean lastAutoSelectSequenceBlock;
	private boolean lastSelectInstruments;
	private int lastActiveTrackIndex;
	private int lastDocumentGeneration;
	private int lastSequenceDelayScaleQuarters;
	private String lastPlacementSequenceText = "";
	/** The cursor as a moment in the music: the tick it sits at, and how far into that tick. */
	private int anchorTime;
	private int anchorOffset;
	private boolean performingAutomatedClick;
	private int sequenceHudTicks;
	private Component sequenceHudAction;
	private SequenceHudMode sequenceHudMode = SequenceHudMode.FULL;
	private int sequenceTapWindowTicks;
	private boolean sequenceControlKeyDown;
	private boolean sequenceGestureConsumed;
	private boolean sequenceSecondTap;
	/** Whether the current note is still waiting for the block that gives it its instrument. */
	private boolean awaitingInstrument;
	/** Ticks off each time the sequencer reaches for a hotbar slot, so least-recently-used has a clock. */
	private long hotbarClock;
	private final long[] hotbarUsedAt = new long[9];

	private NoteBlockOverlay() {
	}

	public void register() {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastAutoSelectSequenceBlock = config.autoSelectSequenceBlock();
		lastSelectInstruments = config.selectInstruments();
		lastActiveTrackIndex = config.activeTrackIndex();
		lastDocumentGeneration = config.documentGeneration();
		lastPlacementSequenceText = buildSequenceSignature(config);
		List<NoteSequence.Placement> sequence = configuredSequence();
		placementSequenceIndex = sequence.isEmpty()
			? 0
			: Math.min(config.placementSequencePosition(), sequence.size() - 1);
		if (placementSequenceIndex != config.placementSequencePosition()) {
			config.setPlacementSequencePosition(placementSequenceIndex);
			sequencePositionSavePending = true;
		}
		rememberPlacementMoment();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(this::render);
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.OVERLAY_MESSAGE,
			Identifier.fromNamespaceAndPath("fast-noteblocks", "sequence_window"),
			this::renderSequenceHud
		);
		UseBlockCallback.EVENT.register(this::watchForSequencePlacement);
	}

	public boolean handleScroll(double verticalAmount) {
		Minecraft minecraft = Minecraft.getInstance();
		if (verticalAmount != 0.0 && handleSequenceScroll(minecraft, verticalAmount)) {
			return true;
		}
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		if (placementSequenceKey.isDown() && !config.placementSequenceEnabled()) {
			return false;
		}
		if (sequenceRadialsBlocked(config)) {
			return false;
		}
		if (!overlaysActive(config)
			|| !config.interactiveControlsEnabled()
			|| verticalAmount == 0.0
			|| minecraft.gui.screen() != null
			|| !isReady(minecraft)) {
			return false;
		}

		HoveredLabel hovered = findHoveredLabel(minecraft);
		if (hovered == null || !minecraft.player.isWithinBlockInteractionRange(hovered.blockPos(), 0.0)) {
			return false;
		}
		if (!hovered.isRepeater() && !hovered.blockPos().equals(expandedBlock)) {
			return false;
		}
		if (hovered.isRepeater()
			&& config.repeaterControlStyle() == FastNoteblocksConfig.RepeaterControlStyle.RADIAL_SELECT
			&& !hovered.blockPos().equals(expandedRepeater)) {
			return false;
		}

		boolean forward = FastNoteblocksConfig.get().invertScrolling() ? verticalAmount < 0.0 : verticalAmount > 0.0;
		NoteSequence.Step target;
		int clicks;
		if (hovered.isRepeater()) {
			int currentDelay = displayedRepeaterDelay(minecraft.level, hovered.blockPos());
			int targetDelay = config.repeaterControlStyle() == FastNoteblocksConfig.RepeaterControlStyle.RADIAL_SELECT
				? hovered.repeaterDelay()
				: (forward ? currentDelay % 4 + 1 : Math.floorMod(currentDelay - 2, 4) + 1);
			target = NoteSequence.Step.repeater(targetDelay);
			clicks = Math.floorMod(targetDelay - currentDelay, 4);
		} else {
			int currentPitch = displayedPitch(minecraft.level, hovered.blockPos());
			int targetPitch = NotePitch.family(currentPitch) == hovered.family()
				? (forward
					? NotePitch.nextInFamily(currentPitch, hovered.family())
					: NotePitch.previousInFamily(currentPitch, hovered.family()))
				: NotePitch.nextInFamily(currentPitch, hovered.family());
			target = NoteSequence.Step.note(targetPitch);
			clicks = NotePitch.clicksForward(currentPitch, targetPitch);
		}
		if (clicks > 0) {
			clickQueue.addLast(PendingClicks.ready(hovered.blockPos(), clicks, false, target));
			expectedSteps.put(hovered.blockPos(), new ExpectedStep(target, 60, false));
		}
		return true;
	}

	private boolean handleSequenceScroll(Minecraft minecraft, double verticalAmount) {
		if (minecraft.gui.screen() != null || !placementSequenceKey.isDown()) {
			return false;
		}
		if (!FastNoteblocksConfig.get().placementSequenceEnabled()) {
			if (!sequenceControlKeyDown) {
				sequenceControlKeyDown = true;
			}
			sequenceGestureConsumed = true;
			sequenceSecondTap = false;
			sequenceTapWindowTicks = 0;
			return false;
		}
		if (!sequenceControlKeyDown) {
			sequenceControlKeyDown = true;
			sequenceGestureConsumed = false;
		}
		sequenceGestureConsumed = true;
		sequenceSecondTap = false;
		sequenceTapWindowTicks = 0;
		int amount = verticalAmount > 0.0 ? -1 : 1;
		if (controlDown(minecraft)) {
			movePlacementSequenceByUnit(amount);
		} else {
			movePlacementSequenceStep(amount);
		}
		showSequenceHud(minecraft, null);
		return true;
	}

	private static boolean controlDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private void tick(Minecraft minecraft) {
		if (sequenceHudTicks > 0) {
			sequenceHudTicks--;
		}
		if (sequenceTapWindowTicks > 0) {
			sequenceTapWindowTicks--;
		}
		if (sequencePositionSavePending) {
			FastNoteblocksConfig.save();
			sequencePositionSavePending = false;
		}
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		while (toggleKey.consumeClick()) {
			config.toggleOverlays();
			FastNoteblocksConfig.save();
			expandedBlock = null;
			menuCenterFamily = null;
			expandedRepeater = null;
			repeaterBottomDelay = null;
			clearRadialFocusCandidate();
			ticksUntilRescan = 0;
			if (minecraft.player != null) {
				minecraft.gui.hud.setOverlayMessage(Component.translatable(
					config.overlaysEnabled() ? "message.fast-noteblocks.enabled" : "message.fast-noteblocks.disabled"
				), true);
			}
		}
		while (composerKey.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new ComposerScreen(null, config));
			}
		}

		handleSequenceControls(minecraft, config);

		if (!config.placementSequenceEnabled() && lastPlacementSequenceEnabled) {
			cancelPlacementSequenceWork();
		} else if (config.placementSequenceEnabled() && !lastPlacementSequenceEnabled) {
			beginPlacementStep(minecraft);
		}
		if (config.autoSelectSequenceBlock() && !lastAutoSelectSequenceBlock) {
			beginPlacementStep(minecraft);
		}
		if (config.selectInstruments() != lastSelectInstruments) {
			beginPlacementStep(minecraft);
		}
		int generation = config.documentGeneration();
		String sequenceSignature = buildSequenceSignature(config);
		// Order matters. A different document is a different build with its own bookmark; the open
		// document with different text is the build you are on, edited under you, and that is the one
		// case where the cursor is worth carrying across rather than looked up.
		if (generation != lastDocumentGeneration
			|| config.activeTrackIndex() != lastActiveTrackIndex) {
			loadPlacementPosition(minecraft);
		} else if (!sequenceSignature.equals(lastPlacementSequenceText)) {
			remapPlacementSequence(minecraft);
		}
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastAutoSelectSequenceBlock = config.autoSelectSequenceBlock();
		lastSelectInstruments = config.selectInstruments();
		lastDocumentGeneration = generation;
		lastActiveTrackIndex = config.activeTrackIndex();
		lastPlacementSequenceText = sequenceSignature;

		if (minecraft.level != lastLevel) {
			lastLevel = minecraft.level;
			nearbyNoteBlocks.clear();
			nearbyRepeaters.clear();
			clickQueue.clear();
			expectedSteps.clear();
			placementWatches.clear();
			expandedBlock = null;
			menuCenterFamily = null;
			expandedRepeater = null;
			repeaterBottomDelay = null;
			clearRadialFocusCandidate();
			ticksUntilRescan = 0;
			beginPlacementStep(minecraft);
		}

		if (!isReady(minecraft)) {
			updatePlacementWatches(minecraft);
			return;
		}
		if (!config.modEnabled()) {
			nearbyNoteBlocks.clear();
			nearbyRepeaters.clear();
			clickQueue.clear();
			expectedSteps.clear();
			placementWatches.clear();
			expandedBlock = null;
			menuCenterFamily = null;
			expandedRepeater = null;
			repeaterBottomDelay = null;
			clearRadialFocusCandidate();
			return;
		}
		if (!config.interactiveControlsEnabled()
			|| !config.overlayMode().includesNotes()
			|| sequenceRadialsBlocked(config)) {
			expandedBlock = null;
			menuCenterFamily = null;
		}
		if (!config.interactiveControlsEnabled()
			|| !config.overlayMode().includesRepeaters()
			|| config.repeaterControlStyle() != FastNoteblocksConfig.RepeaterControlStyle.RADIAL_SELECT
			|| sequenceRadialsBlocked(config)) {
			expandedRepeater = null;
			repeaterBottomDelay = null;
		}

		updatePlacementWatches(minecraft);
		updateExpectations(minecraft.level);
		if (overlaysActive(config) && --ticksUntilRescan <= 0) {
			rescan(minecraft);
			ticksUntilRescan = RESCAN_INTERVAL_TICKS;
		} else if (!overlaysActive(config)) {
			nearbyNoteBlocks.clear();
			nearbyRepeaters.clear();
			expandedBlock = null;
			menuCenterFamily = null;
			expandedRepeater = null;
			repeaterBottomDelay = null;
			clearRadialFocusCandidate();
		}
		performNextClick(minecraft);
	}

	private void handleSequenceControls(Minecraft minecraft, FastNoteblocksConfig config) {
		boolean inGame = minecraft.gui.screen() == null;
		// Drain click counts so operating-system key repeats can never masquerade as extra taps.
		while (placementSequenceKey.consumeClick()) {
		}
		if (!inGame) {
			sequenceTapWindowTicks = 0;
			sequenceControlKeyDown = placementSequenceKey.isDown();
			sequenceGestureConsumed = sequenceControlKeyDown;
			sequenceSecondTap = false;
			return;
		}

		boolean keyDownNow = placementSequenceKey.isDown();
		boolean pressedNow = keyDownNow && !sequenceControlKeyDown;
		boolean releasedNow = !keyDownNow && sequenceControlKeyDown;
		if (pressedNow) {
			sequenceGestureConsumed = false;
			sequenceSecondTap = sequenceTapWindowTicks > 0;
			sequenceTapWindowTicks = 0;
		}

		if (releasedNow && sequenceSecondTap && !sequenceGestureConsumed) {
			config.setPlacementSequenceEnabled(!config.placementSequenceEnabled());
			FastNoteblocksConfig.save();
			if (!config.placementSequenceEnabled()) {
				cancelPlacementSequenceWork();
			}
			if (config.placementSequenceEnabled()) {
				showSequenceHud(minecraft, "message.fast-noteblocks.sequence_resumed");
			} else if (minecraft.player != null) {
				sequenceHudTicks = 0;
				minecraft.gui.hud.setOverlayMessage(Component.translatable(
					"message.fast-noteblocks.sequence_paused"
				), true);
			}
		} else if (releasedNow && !sequenceGestureConsumed) {
			sequenceTapWindowTicks = SEQUENCE_DOUBLE_TAP_TICKS;
		}
		if (releasedNow) {
			sequenceGestureConsumed = false;
			sequenceSecondTap = false;
		}
		sequenceControlKeyDown = keyDownNow;
	}

	private void movePlacementSequence(int amount) {
		if (!configuredSequence().isEmpty()) {
			goToPlacement(placementSequenceIndex + amount, true);
		}
	}

	/**
	 * One notch of the wheel, which is half a step wherever the block underneath is a step of its own.
	 *
	 * <p>With instruments switched on a note is two placements, so going back one had no way to say
	 * which of the two you meant and always landed on the instrument. Now the phase is part of what
	 * the cursor moves through: forward off an instrument lands on its note, back off a note lands on
	 * its instrument, and a step with nothing to lay underneath -- a repeater, or a harp note -- is
	 * the single notch it always was.</p>
	 */
	private void movePlacementSequenceStep(int amount) {
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (sequence.isEmpty()) {
			return;
		}
		int index = Math.max(0, Math.min(sequence.size() - 1, placementSequenceIndex));
		if (amount > 0) {
			if (awaitingInstrument) {
				goToPlacement(index, false);
				return;
			}
			int next = steppedPlacement(sequence, index, 1);
			if (next >= 0) {
				goToPlacement(next, true);
			}
			return;
		}
		if (!awaitingInstrument && instrumentIsAStep(sequence.get(index))) {
			goToPlacement(index, true);
			return;
		}
		// Back into the step before means its note, not its instrument: the far side of it.
		int previous = steppedPlacement(sequence, index, -1);
		if (previous >= 0) {
			goToPlacement(previous, false);
		}
	}

	/**
	 * The placement the walk reaches next, or -1 at the end.
	 *
	 * <p>Within a chord this is whichever note the chosen order visits next, which is not the next
	 * one along unless the order is alternating. Leaving a chord backwards lands on the last note of
	 * the one before -- the last one *visited*, which in two strips is not the last one stored.</p>
	 */
	private int steppedPlacement(List<NoteSequence.Placement> sequence, int index, int direction) {
		boolean twoStrips = FastNoteblocksConfig.get().walksChordsInTwoStrips();
		NoteSequence.Span chord = NoteSequence.chordSpan(sequence, index);
		int rank = NoteSequence.chordRank(chord, index, twoStrips);
		if (direction > 0) {
			if (rank + 1 < chord.size()) {
				return NoteSequence.chordAt(chord, rank + 1, twoStrips);
			}
			return chord.last() + 1 < sequence.size()
				? NoteSequence.chordAt(NoteSequence.chordSpan(sequence, chord.last() + 1), 0, twoStrips)
				: -1;
		}
		if (rank > 0) {
			return NoteSequence.chordAt(chord, rank - 1, twoStrips);
		}
		if (chord.first() == 0) {
			return -1;
		}
		NoteSequence.Span before = NoteSequence.chordSpan(sequence, chord.first() - 1);
		return NoteSequence.chordAt(before, before.size() - 1, twoStrips);
	}

	/** Puts the cursor somewhere, and says which half of that step it is standing on. */
	private void goToPlacement(int index, boolean instrumentPhase) {
		cancelPlacementSequenceWork();
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (sequence.isEmpty()) {
			return;
		}
		int target = Math.max(0, Math.min(sequence.size() - 1, index));
		boolean moved = target != placementSequenceIndex;
		placementSequenceIndex = target;
		awaitingInstrument = instrumentPhase && instrumentIsAStep(sequence.get(target));
		if (moved) {
			persistPlacementSequencePosition();
		}
		selectCurrentSequenceItem(Minecraft.getInstance());
	}

	private static boolean instrumentIsAStep(NoteSequence.Placement placement) {
		return FastNoteblocksConfig.get().selectInstruments() && needsInstrumentLaid(placement);
	}

	/** Moves by a whole chord, or by a whole run of delay, rather than by one placement. */
	private void movePlacementSequenceByUnit(int direction) {
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (sequence.isEmpty()) {
			return;
		}
		int index = Math.max(0, Math.min(sequence.size() - 1, placementSequenceIndex));
		movePlacementSequence(NoteSequence.jump(sequence, index, direction) - index);
	}

	private void showSequenceHud(Minecraft minecraft, String actionKey) {
		if (minecraft.player == null) {
			return;
		}
		sequenceHudAction = actionKey == null ? null : Component.translatable(actionKey);
		sequenceHudMode = SequenceHudMode.FULL;
		sequenceHudTicks = SEQUENCE_HUD_TICKS;
	}

	/** The brief look at the strip after a placement, without having to hold the key. */
	private void showSequenceAdvance() {
		sequenceHudAction = null;
		sequenceHudMode = SequenceHudMode.ADVANCE;
		sequenceHudTicks = SEQUENCE_ADVANCE_HUD_TICKS;
	}

	/**
	 * The strip: the placement order laid out left to right, centred on where you are.
	 *
	 * <p>A chord is one card of two rows, which is the shape it is about to be built in -- a bus
	 * carries two notes a block, so a column here is a block of bus and the two rows are its two
	 * sides. Reading down then right is the order the cursor walks and the order the notes go down.
	 * Delay stands between the cards as a single token on the midline, so the strip never changes
	 * shape under you as the cursor crosses out of a chord into the repeaters after it.</p>
	 */
	private void renderSequenceHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean keyHeld = placementSequenceKey.isDown();
		if (!FastNoteblocksConfig.get().placementSequenceEnabled()
			|| (!keyHeld && sequenceHudTicks <= 0)
			|| minecraft.player == null
			|| minecraft.gui.screen() != null) {
			return;
		}
		List<NoteSequence.Placement> sequence = configuredSequence();
		int centerX = graphics.guiWidth() / 2;
		int y = graphics.guiHeight() / 2 + 28;
		if (sequence.isEmpty()) {
			drawSequenceHudHeader(graphics, centerX, y);
			graphics.centeredText(minecraft.font, Component.translatable(
				"message.fast-noteblocks.sequence_hud_empty"
			), centerX, y, 0xFFFF5555);
			return;
		}
		y = Math.max(30, Math.min(y, graphics.guiHeight() - 2 * TILE_ROW_HEIGHT - 30));
		List<SequenceHudItem> items = sequenceHudItems(sequence);
		int index = Math.max(0, Math.min(sequence.size() - 1, placementSequenceIndex));
		NoteSequence.Span chord = NoteSequence.chordSpan(sequence, index);
		drawSequenceHudHeader(graphics, centerX, y);
		drawSequenceStrip(graphics, centerX, y, items, hudCards(items), index, awaitingInstrument);
		drawSequencePosition(graphics, centerX,
			y + 2 * TILE_ROW_HEIGHT - minecraft.font.lineHeight, sequence, chord, index);
	}

	/**
	 * Whatever the last control action was, and nothing else.
	 *
	 * <p>This used to head the window with the numbers of every build-enabled track. That reads as
	 * "Build tracks: 1, 2" on the songs it was written for and as a row of counting numbers wider
	 * than the screen, its own beginning cut off, on a song with forty layers. The strip says which
	 * instruments are in play by showing them.</p>
	 */
	private void drawSequenceHudHeader(GuiGraphicsExtractor graphics, int centerX, int y) {
		if (sequenceHudTicks > 0 && sequenceHudAction != null && sequenceHudMode == SequenceHudMode.FULL) {
			graphics.centeredText(Minecraft.getInstance().font, sequenceHudAction, centerX, y - 14, 0xFFCCCCCC);
		}
	}

	/** The strip broken into what gets drawn as one thing: a chord, or a run of delay. */
	private static List<HudCard> hudCards(List<SequenceHudItem> items) {
		Minecraft minecraft = Minecraft.getInstance();
		List<HudCard> cards = new ArrayList<>();
		int index = 0;
		while (index < items.size()) {
			if (items.get(index).step().step().type() == NoteSequence.StepType.NOTE) {
				int end = index;
				while (end + 1 < items.size() && chordAdjacent(items.get(end), items.get(end + 1))) {
					end++;
				}
				int tileWidth = 0;
				for (int at = index; at <= end; at++) {
					// What the icon leaves: it reaches ICON_SIZE - ICON_OUT_X into its own tile, and the
					// next tile's icon reaches ICON_OUT_X back into this one.
					tileWidth = Math.max(tileWidth, ICON_SIZE + ICON_OUT_X
						+ Math.round(minecraft.font.width(noteText(items.get(at).step())) * NOTE_SCALE));
				}
				// Two notes to a column, because two notes go on one block of bus.
				int columns = (end - index + 2) / 2;
				cards.add(new HudCard(index, end, true, columns, tileWidth, columns * tileWidth));
				index = end + 1;
			} else {
				int width = minecraft.font.width(delayText(items.get(index), -1)) + 8;
				cards.add(new HudCard(index, index, false, 1, width, width));
				index++;
			}
		}
		return cards;
	}

	private static void drawSequenceStrip(
		GuiGraphicsExtractor graphics,
		int centerX,
		int y,
		List<SequenceHudItem> items,
		List<HudCard> cards,
		int activeIndex,
		boolean instrumentFirst
	) {
		int activeCard = 0;
		for (int at = 0; at < cards.size(); at++) {
			if (activeIndex >= items.get(cards.get(at).firstItem()).startIndex()
				&& activeIndex <= items.get(cards.get(at).lastItem()).endIndex()) {
				activeCard = at;
				break;
			}
		}
		HudCard current = cards.get(activeCard);
		int currentX;
		if (current.width() <= graphics.guiWidth() - 8) {
			currentX = centerX - current.width() / 2;
		} else {
			// Thirty notes is fifteen columns, which at a large GUI scale is wider than the screen.
			// Centre the column you are on and let the rest of the card run off, rather than centring
			// the card and losing both ends of it.
			int column = 0;
			for (int at = current.firstItem(); at <= current.lastItem(); at++) {
				if (items.get(at).startIndex() == activeIndex) {
					column = (at - current.firstItem()) / 2;
					break;
				}
			}
			currentX = centerX - column * current.tileWidth() - current.tileWidth() / 2;
		}
		drawHudCard(graphics, current, currentX, y, items, activeIndex, instrumentFirst);

		// Neighbours fill outwards until the next one would not fit, rather than a fixed count: a
		// card is as wide as its chord, so four either side is a handful of tokens or half a mile.
		int leftEdge = currentX;
		for (int at = activeCard - 1; at >= 0; at--) {
			int x = leftEdge - CARD_GAP - cards.get(at).width();
			if (x < 2) {
				break;
			}
			drawHudCard(graphics, cards.get(at), x, y, items, activeIndex, instrumentFirst);
			leftEdge = x;
		}
		int rightEdge = currentX + current.width();
		for (int at = activeCard + 1; at < cards.size(); at++) {
			int x = rightEdge + CARD_GAP;
			if (x + cards.get(at).width() > graphics.guiWidth() - 2) {
				break;
			}
			drawHudCard(graphics, cards.get(at), x, y, items, activeIndex, instrumentFirst);
			rightEdge = x + cards.get(at).width();
		}
	}

	private static void drawHudCard(
		GuiGraphicsExtractor graphics,
		HudCard card,
		int x,
		int y,
		List<SequenceHudItem> items,
		int activeIndex,
		boolean instrumentFirst
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!card.chord()) {
			SequenceHudItem item = items.get(card.firstItem());
			boolean current = activeIndex >= item.startIndex() && activeIndex <= item.endIndex();
			String text = delayText(item, activeIndex);
			int width = minecraft.font.width(text);
			int textX = x + (card.width() - width) / 2;
			// On the midline between the two rows, so the cards either side of it stay put.
			int textY = y + TILE_ROW_HEIGHT - minecraft.font.lineHeight / 2;
			if (current) {
				graphics.fill(textX - 4, textY - 3, textX + width + 4,
					textY + minecraft.font.lineHeight + 3, 0xB8000000);
			}
			graphics.text(minecraft.font, text, textX, textY,
				current ? 0xFFFFAA00 : 0xFF999999, true);
			return;
		}
		// The tiles never move: a column is a block of bus and the two rows are its sides, whichever
		// order the cursor walks them in. Only what counts as behind you changes.
		NoteSequence.Span span = new NoteSequence.Span(items.get(card.firstItem()).startIndex(),
			items.get(card.lastItem()).endIndex());
		boolean twoStrips = FastNoteblocksConfig.get().walksChordsInTwoStrips();
		boolean holdsCursor = activeIndex >= span.first() && activeIndex <= span.last();
		int activeRank = holdsCursor ? NoteSequence.chordRank(span, activeIndex, twoStrips) : -1;
		for (int at = card.firstItem(); at <= card.lastItem(); at++) {
			int local = at - card.firstItem();
			int index = items.get(at).startIndex();
			boolean placed = holdsCursor
				? NoteSequence.chordRank(span, index, twoStrips) < activeRank
				: index < activeIndex;
			drawChordTile(graphics, items.get(at), x + local / 2 * card.tileWidth(),
				y + local % 2 * TILE_ROW_HEIGHT, card.tileWidth(), activeIndex, instrumentFirst, placed);
		}
	}

	/**
	 * One note of a chord: its pitch, with the block it wants underneath and its layer number.
	 *
	 * <p>The badge sits where the layer number alone used to, because the number never answered the
	 * question actually being asked -- what goes under this one. Placed, current and still to come
	 * are three colours, so the eye finds where it is in a chord of thirty without counting.</p>
	 */
	private static void drawChordTile(
		GuiGraphicsExtractor graphics,
		SequenceHudItem item,
		int x,
		int y,
		int tileWidth,
		int activeIndex,
		boolean instrumentFirst,
		boolean placed
	) {
		Minecraft minecraft = Minecraft.getInstance();
		NoteSequence.Placement placement = item.step();
		int index = item.startIndex();
		boolean current = index == activeIndex;
		// With instruments switched on the block comes first, so the highlight is on the icon and the
		// pitch waits its turn rather than both of them claiming to be next.
		boolean onIcon = current && instrumentFirst;
		graphics.fill(x + 1, y + 1, x + tileWidth - 1, y + TILE_ROW_HEIGHT - 1,
			current ? 0xC8000000 : 0x78000000);

		// The pitch, beside the icon rather than under it, and small: on a card you are reading the
		// shape and the materials, and the exact note is the detail you go to last.
		String text = noteText(placement);
		int textWidth = Math.round(minecraft.font.width(text) * NOTE_SCALE);
		int textHeight = Math.round(minecraft.font.lineHeight * NOTE_SCALE);
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + ICON_SIZE - ICON_OUT_X, y + (TILE_ROW_HEIGHT - textHeight) / 2);
		graphics.pose().scale(NOTE_SCALE, NOTE_SCALE);
		graphics.text(minecraft.font, text, 0, 0,
			current && !onIcon ? 0xFFFFAA00 : placed ? 0xFF4E4E4E : 0xFFFFFFFF, true);
		graphics.pose().popMatrix();

		String instrument = stepInstrument(placement);
		int iconX = x - ICON_OUT_X;
		int iconY = y - ICON_OUT_Y;
		if (onIcon) {
			graphics.fill(iconX - 1, iconY - 1, iconX + ICON_SIZE + 1, iconY + ICON_SIZE + 1, 0xC0FFAA00);
		}
		if (!instrument.isEmpty()) {
			graphics.item(new ItemStack(PreviewInstrument.byId(instrument).icon()), iconX, iconY);
			// Harp with its setting off is a block the sequencer will walk past. Saying so is better
			// than showing grass the same way as a block you are about to be handed. A sound effect
			// is never greyed: its icon is not a block laid underneath, it is the block you place.
			if (PreviewInstrument.byId(instrument).pitched() && !needsInstrumentLaid(placement)) {
				graphics.fill(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE, 0xA8101010);
			}
		}
		// A corner of the icon rather than a label of its own.
		if (placement.trackNumber() > 0) {
			String layer = Integer.toString(placement.trackNumber());
			graphics.pose().pushMatrix();
			graphics.pose().translate(
				iconX + ICON_SIZE - Math.round(minecraft.font.width(layer) * LAYER_SCALE) - 1,
				iconY + ICON_SIZE - Math.round(minecraft.font.lineHeight * LAYER_SCALE) - 1);
			graphics.pose().scale(LAYER_SCALE, LAYER_SCALE);
			graphics.text(minecraft.font, layer, 0, 0, 0xFFFFFFFF, true);
			graphics.pose().popMatrix();
		}
	}

	private static String noteText(NoteSequence.Placement step) {
		// A sound effect has one sound, and the roll writes it as pitch 0 only because a step has to
		// hold a number. Printing "F#0" over a door would be reading that number out as if it meant
		// something. The icon beside it is what says which effect this is.
		if (!PreviewInstrument.byId(stepInstrument(step)).pitched()) {
			return "•";
		}
		return NotePitch.name(step.step().value()) + step.step().value();
	}

	private static String trackInstrument(FastNoteblocksConfig.SequenceTrack track) {
		String instrument = track.instrument();
		return instrument == null || instrument.isBlank() ? "HARP" : instrument;
	}

	/** The instrument a placed step will sound as, which is to say the block it wants underneath. */
	private static String stepInstrument(NoteSequence.Placement step) {
		if (step.step().type() != NoteSequence.StepType.NOTE) {
			return "";
		}
		List<FastNoteblocksConfig.SequenceTrack> tracks = FastNoteblocksConfig.get().tracks();
		int trackIndex = step.trackNumber() - 1;
		return trackIndex >= 0 && trackIndex < tracks.size() ? trackInstrument(tracks.get(trackIndex)) : "";
	}

	/**
	 * The counters under the window.
	 *
	 * <p>Inside a chord the headline is how far through the chord you are. "14312/31000" is true and
	 * useless with a stack of note blocks in your hand; "17/24" is the number that says whether to
	 * keep going. The song-wide position is still there, just no longer the loudest thing.</p>
	 */
	private void drawSequencePosition(
		GuiGraphicsExtractor graphics,
		int centerX,
		int y,
		List<NoteSequence.Placement> sequence,
		NoteSequence.Span chord,
		int index
	) {
		Minecraft minecraft = Minecraft.getInstance();
		NoteSequence.Progress progress = buildProgress(sequence, placementSequenceIndex);
		String overall = progress.position() + "/" + progress.total();
		boolean inChord = chord.size() >= 2;
		String headline = inChord
			? NoteSequence.chordRank(chord, index, FastNoteblocksConfig.get().walksChordsInTwoStrips()) + 1
				+ "/" + chord.size() + " in chord"
			: overall;
		int positionY = y + minecraft.font.lineHeight + 6;
		graphics.centeredText(minecraft.font, headline, centerX, positionY, inChord ? 0xFFCCCCCC : 0xFF999999);

		List<String> small = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		if (inChord) {
			small.add(overall);
			colors.add(0xFF999999);
		}
		small.add(progress.noteBlockPosition() + "/" + progress.noteBlockTotal() + " nb");
		colors.add(0xFF55FFFF);
		small.add(progress.repeaterPosition() + "/" + progress.repeaterTotal() + " rp");
		colors.add(0xFFFFAA00);
		int smallX = centerX + minecraft.font.width(headline) / 2 + 5;
		float scale = 0.65F;
		graphics.pose().pushMatrix();
		graphics.pose().translate(smallX, positionY - 1);
		graphics.pose().scale(scale, scale);
		for (int line = 0; line < small.size(); line++) {
			graphics.text(minecraft.font, small.get(line), 0, line * (minecraft.font.lineHeight + 1),
				colors.get(line), false);
		}
		graphics.pose().popMatrix();
	}

	private static NoteSequence.Progress buildProgress(List<NoteSequence.Placement> sequence, int currentIndex) {
		List<NoteSequence.Step> steps = sequence.stream().map(NoteSequence.Placement::step).toList();
		return NoteSequence.progress(steps, currentIndex);
	}

	private static List<SequenceHudItem> sequenceHudItems(List<NoteSequence.Placement> sequence) {
		List<SequenceHudItem> items = new ArrayList<>();
		for (int index = 0; index < sequence.size();) {
			NoteSequence.Placement buildStep = sequence.get(index);
			NoteSequence.Step step = buildStep.step();
			if (step.type() == NoteSequence.StepType.REPEATER && step.delayCount() > 1) {
				int endIndex = index;
				while (endIndex + 1 < sequence.size()
					&& sequence.get(endIndex + 1).trackNumber() == buildStep.trackNumber()
					&& sequence.get(endIndex + 1).step().type() == NoteSequence.StepType.REPEATER
					&& sequence.get(endIndex + 1).step().delayTotal() == step.delayTotal()
					&& sequence.get(endIndex + 1).step().delayIndex() == sequence.get(endIndex).step().delayIndex() + 1) {
					endIndex++;
				}
				items.add(new SequenceHudItem(index, endIndex, buildStep));
				index = endIndex + 1;
			} else {
				items.add(new SequenceHudItem(index, index, buildStep));
				index++;
			}
		}
		return items;
	}

	/**
	 * What a run of delay reads as: the total, then a dot per repeater, filled in as they go down.
	 *
	 * <p>Only ever asked of delay now. A note is a tile on a card and says its pitch there.</p>
	 */
	private static String delayText(SequenceHudItem item, int activePhysicalIndex) {
		NoteSequence.Step step = item.step().step();
		if (step.delayCount() == 1 || item.startIndex() == item.endIndex()) {
			return step.value() + "d";
		}
		StringBuilder text = new StringBuilder().append(step.delayTotal()).append("d ");
		for (int dot = 0; dot < step.delayCount(); dot++) {
			if (dot > 0) {
				text.append(' ');
			}
			text.append(activePhysicalIndex == item.startIndex() + dot ? '\u2022' : '\u00b7');
		}
		return text.toString();
	}

	private static boolean chordAdjacent(SequenceHudItem left, SequenceHudItem right) {
		return NoteSequence.sameChord(left.step(), right.step());
	}

	/**
	 * The flat placement order: every note and repeater, in the order they are to be built.
	 *
	 * <p>Notes at one time are a chord, and within a chord the order is free -- any permutation of
	 * the same pitches at the same tick is the same music. So it is spent on the hand doing the
	 * placing: notes are grouped by instrument, because a note block's instrument comes from the
	 * block underneath it, and a chord in track order can ask for harp, bass, harp again. Two layers
	 * of one instrument are the only case that moves; a chord whose instruments each have one layer
	 * is already grouped and comes out unchanged.</p>
	 */
	private static List<NoteSequence.Placement> configuredSequence() {
		List<FastNoteblocksConfig.SequenceTrack> tracks = FastNoteblocksConfig.get().tracks();
		if (tracks != flattenedFrom) {
			flattened = flattenSequence(tracks);
			flattenedFrom = tracks;
		}
		return flattened;
	}

	/**
	 * Cached on the identity of the track list, which is sound because that list is itself cached on
	 * the composition and a composition is immutable: a different list means a different song.
	 *
	 * <p>Not an optimisation so much as a repair. This is called once a frame while the window is up
	 * and again on every placement, and it reparses every track from text -- 8.2 ms a call on
	 * Guardian, half a frame at sixty. It was free when a song was six notes.</p>
	 */
	private static List<NoteSequence.Placement> flattenSequence(
		List<FastNoteblocksConfig.SequenceTrack> tracks
	) {
		try {
			List<NoteEvent> events = new ArrayList<>();
			Map<String, Integer> instrumentRanks = new LinkedHashMap<>();
			for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
				FastNoteblocksConfig.SequenceTrack track = tracks.get(trackIndex);
				if (!track.buildEnabled()) {
					continue;
				}
				String instrument = trackInstrument(track);
				if (!instrumentRanks.containsKey(instrument)) {
					instrumentRanks.put(instrument, instrumentRanks.size());
				}
				List<NoteSequence.Step> steps = NoteSequence.parse(track.sequence());
				int rank = instrumentRanks.get(instrument);
				int time = 0;
				for (int localIndex = 0; localIndex < steps.size(); localIndex++) {
					NoteSequence.Step step = steps.get(localIndex);
					if (step.type() == NoteSequence.StepType.NOTE) {
						events.add(new NoteEvent(time, rank, trackIndex + 1, localIndex, step));
					} else {
						time += step.value();
					}
				}
			}
			events.sort(Comparator.comparingInt(NoteEvent::time)
				.thenComparingInt(NoteEvent::instrumentRank)
				.thenComparingInt(NoteEvent::trackNumber)
				.thenComparingInt(NoteEvent::localIndex));
			List<NoteSequence.Placement> merged = new ArrayList<>();
			int currentTime = 0;
			for (NoteEvent event : events) {
				if (event.time() > currentTime) {
					addTimelineDelay(merged, currentTime, event.time() - currentTime);
					currentTime = event.time();
				}
				merged.add(new NoteSequence.Placement(event.time(), event.trackNumber(), event.step()));
			}
			return List.copyOf(merged);
		} catch (IllegalArgumentException exception) {
			return List.of();
		}
	}

	private static void addTimelineDelay(List<NoteSequence.Placement> merged, int time, int delay) {
		int remaining = delay;
		while (remaining > 0) {
			int chunk = Math.min(NoteSequence.MAX_GROUPED_DELAY, remaining);
			for (NoteSequence.Step step : NoteSequence.parse(chunk + "d", FastNoteblocksConfig.DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS)) {
				merged.add(new NoteSequence.Placement(time, 0, step));
				time += step.value();
			}
			remaining -= chunk;
		}
	}

	private static String buildSequenceSignature(FastNoteblocksConfig config) {
		StringBuilder signature = new StringBuilder();
		for (FastNoteblocksConfig.SequenceTrack track : config.tracks()) {
			signature.append('|').append(track.buildEnabled()).append(':').append(track.sequence());
		}
		return signature.toString();
	}

	private void rescan(Minecraft minecraft) {
		nearbyNoteBlocks.clear();
		nearbyRepeaters.clear();
		FastNoteblocksConfig.OverlayMode overlayMode = FastNoteblocksConfig.get().overlayMode();
		int searchRadius = FastNoteblocksConfig.get().viewDistance();
		BlockPos origin = minecraft.player.blockPosition();
		BlockPos min = origin.offset(-searchRadius, -searchRadius, -searchRadius);
		BlockPos max = origin.offset(searchRadius, searchRadius, searchRadius);
		double radiusSquared = searchRadius * searchRadius;

		for (BlockPos mutablePos : BlockPos.betweenClosed(min, max)) {
			if (mutablePos.distToCenterSqr(minecraft.player.position()) <= radiusSquared) {
				BlockState state = minecraft.level.getBlockState(mutablePos);
				if (overlayMode.includesNotes() && state.is(Blocks.NOTE_BLOCK)) {
					nearbyNoteBlocks.add(mutablePos.immutable());
				} else if (overlayMode.includesRepeaters() && state.is(Blocks.REPEATER)) {
					nearbyRepeaters.add(mutablePos.immutable());
				}
			}
		}
	}

	private void performNextClick(Minecraft minecraft) {
		PendingClicks pending = clickQueue.peekFirst();
		if (pending == null) {
			return;
		}
		// Tuning is a right-click on the block, and a crouching right-click means place, not use --
		// which the server judges for itself from your pose, so there is nothing to fake here. Left
		// alone it does not merely fail to tune: with a note block in hand it lays another one. So the
		// queue waits out the crouch rather than spending clicks that will land as something else.
		if (minecraft.player.isSecondaryUseActive()) {
			return;
		}

		BlockState state = minecraft.level.getBlockState(pending.blockPos());
		if (!matchesTunableStep(state, pending.targetStep())
			|| !minecraft.player.isWithinBlockInteractionRange(pending.blockPos(), 0.0)) {
			cancelWorkForBlock(pending.blockPos());
			return;
		}

		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		int actualValue = stepValue(state, pending.targetStep());
		if (pending.expectedValueAfterClick() >= 0) {
			if (!config.waitForServerAcknowledgement()) {
				if (pending.remaining() <= 0) {
					clickQueue.removeFirst();
				} else {
					replaceFirstPending(pending.withoutAcknowledgement(config.interactionDelayTicks()));
				}
				return;
			}
			if (actualValue == pending.expectedValueAfterClick()) {
				if (pending.remaining() <= 0) {
					clickQueue.removeFirst();
				} else {
					replaceFirstPending(pending.afterAcknowledgement(config.interactionDelayTicks()));
				}
			} else if (pending.ackTicksRemaining() <= 0) {
				cancelWorkForBlock(pending.blockPos());
			} else {
				replaceFirstPending(pending.waitOneTick());
			}
			return;
		}

		if (pending.cooldownTicks() > 0) {
			replaceFirstPending(pending.coolDownOneTick());
			return;
		}

		BlockHitResult hitResult = interactionHitResult(minecraft, pending.blockPos(), config.requireLineOfSight());
		if (hitResult == null) {
			cancelWorkForBlock(pending.blockPos());
			return;
		}
		performingAutomatedClick = true;
		try {
			minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hitResult);
		} finally {
			performingAutomatedClick = false;
		}
		if (config.waitForServerAcknowledgement()) {
			replaceFirstPending(pending.afterClick(nextStepValue(pending.targetStep(), actualValue)));
		} else if (pending.remaining() <= 1) {
			clickQueue.removeFirst();
		} else {
			replaceFirstPending(pending.afterUnconfirmedClick(config.interactionDelayTicks()));
		}
	}

	private BlockHitResult interactionHitResult(Minecraft minecraft, BlockPos pos, boolean requireLineOfSight) {
		if (minecraft.player.isSecondaryUseActive()) {
			return null;
		}
		BlockHitResult result = minecraft.level.clip(new ClipContext(
			minecraft.player.getEyePosition(),
			Vec3.atCenterOf(pos),
			ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE,
			minecraft.player
		));
		if (!result.getBlockPos().equals(pos)) {
			if (requireLineOfSight) {
				return null;
			}
			Vec3 fallbackLocation = Vec3.atCenterOf(pos).add(0.0, 0.0, -0.5);
			return new BlockHitResult(fallbackLocation, Direction.NORTH, pos, false);
		}
		if (minecraft.level.getBlockState(pos).is(Blocks.NOTE_BLOCK)
			&& result.getDirection() == net.minecraft.core.Direction.UP
			&& minecraft.player.getMainHandItem().is(ItemTags.NOTE_BLOCK_TOP_INSTRUMENTS)) {
			return null;
		}
		return result;
	}

	private void replaceFirstPending(PendingClicks replacement) {
		clickQueue.removeFirst();
		clickQueue.addFirst(replacement);
	}

	private void cancelWorkForBlock(BlockPos pos) {
		clickQueue.removeIf(pending -> pending.blockPos().equals(pos));
		expectedSteps.remove(pos);
	}

	private void updateExpectations(ClientLevel level) {
		expectedSteps.entrySet().removeIf(entry -> {
			BlockState state = level.getBlockState(entry.getKey());
			ExpectedStep expected = entry.getValue();
			if (!matchesTunableStep(state, expected.step())) {
				clickQueue.removeIf(pending -> pending.blockPos().equals(entry.getKey()));
				return true;
			}
			if (stepValue(state, expected.step()) == expected.step().value()) {
				clickQueue.removeIf(pending -> pending.blockPos().equals(entry.getKey()));
				return true;
			}
			boolean interactionQueued = clickQueue.stream().anyMatch(
				pending -> pending.blockPos().equals(entry.getKey())
			);
			int remaining = interactionQueued ? expected.ticksRemaining() : expected.ticksRemaining() - 1;
			if (remaining <= 0) {
				return true;
			}
			entry.setValue(new ExpectedStep(expected.step(), remaining, expected.placementSequence()));
			return false;
		});
	}

	/**
	 * Whether a note block standing on this would play what the step asked for.
	 *
	 * <p>Asked of the block, not of the item that placed it: any wood is a bass and any wool is a
	 * guitar, so a named block would refuse three quarters of the right answers. The item is still
	 * compared first, in case an instrument this mod knows is one the game does not name the same
	 * way.</p>
	 *
	 * <p>Harp is named instead of asked. It is not an instrument so much as the absence of one: the
	 * game answers HARP for everything it does not recognise, down to redstone dust, a torch or a
	 * rail, so the question accepts almost anything you could be holding. Grass or the dirt under it
	 * is what a harp note wants, and saying so is both stricter and easier to predict than any test
	 * of what a block is.</p>
	 */
	private static boolean givesInstrument(BlockState state, String instrument) {
		if (state.isAir()) {
			return false;
		}
		if (isHarp(instrument)) {
			return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT);
		}
		if (PreviewInstrument.byId(instrument).wearsASkull()) {
			// Asked of the block, not of its instrument: a bare note block reads as a harp until the
			// skull is on it, and the skull is the very thing this step is waiting to let you place.
			return state.is(Blocks.NOTE_BLOCK);
		}
		return state.instrument().name().equalsIgnoreCase(instrument)
			|| state.getBlock().asItem() == PreviewInstrument.byId(instrument).icon();
	}

	private static boolean isHarp(String instrument) {
		return "HARP".equals(PreviewInstrument.byId(instrument).id());
	}

	private static boolean matchesStepBlock(BlockState state, Item stepBlock) {
		return !state.isAir() && state.is(net.minecraft.world.level.block.Block.byItem(stepBlock));
	}

	/**
	 * The block a step gets tuned on, which is only ever a note block or a repeater.
	 *
	 * <p>Kept apart from {@link #matchesStepBlock} on purpose. That one answers "is the thing this
	 * step places now standing here", and for a sound effect the answer is a door or a bell; this one
	 * guards the clicking, and nothing but these two has a value to click round.</p>
	 */
	private static boolean matchesTunableStep(BlockState state, NoteSequence.Step step) {
		return step.type() == NoteSequence.StepType.NOTE
			? state.is(Blocks.NOTE_BLOCK)
			: state.is(Blocks.REPEATER);
	}

	private static int stepValue(BlockState state, NoteSequence.Step step) {
		return step.type() == NoteSequence.StepType.NOTE
			? state.getValue(NoteBlock.NOTE)
			: state.getValue(RepeaterBlock.DELAY);
	}

	private static int nextStepValue(NoteSequence.Step step, int value) {
		return step.type() == NoteSequence.StepType.NOTE
			? (value + 1) % NotePitch.PITCH_COUNT
			: value % 4 + 1;
	}

	private static int findSequenceItemSlot(LocalPlayer player, Item stepBlock) {
		int selectedSlot = player.getInventory().getSelectedSlot();
		if (player.getInventory().getItem(selectedSlot).is(stepBlock)) {
			return selectedSlot;
		}
		return findHotbarSlot(player, stepBlock);
	}

	private InteractionResult watchForSequencePlacement(
		net.minecraft.world.entity.player.Player player,
		net.minecraft.world.level.Level level,
		InteractionHand hand,
		BlockHitResult hitResult
	) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (!level.isClientSide() || !config.modEnabled()) {
			return InteractionResult.PASS;
		}
		BlockState clickedState = level.getBlockState(hitResult.getBlockPos());
		boolean suppressUsingBlock = player.isSecondaryUseActive()
			&& (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty());
		if (!performingAutomatedClick
			&& isSequencingActive(config, sequence)
			&& config.sequencingEditProtection().blocksInteractions()
			&& !suppressUsingBlock
			&& (clickedState.is(Blocks.NOTE_BLOCK) || clickedState.is(Blocks.REPEATER))) {
			return InteractionResult.FAIL;
		}
		if (performingAutomatedClick
			|| !config.placementSequenceEnabled()
			|| sequence.isEmpty()
			|| !placementWatches.isEmpty()) {
			return InteractionResult.PASS;
		}
		NoteSequence.Step expected = sequence.get(Math.floorMod(placementSequenceIndex, sequence.size())).step();
		ItemStack held = player.getItemInHand(hand);
		NoteSequence.Placement placement = sequence.get(
			Math.floorMod(placementSequenceIndex, sequence.size()));
		Item stepBlock = stepBlockItem(placement);
		if (awaitingInstrument) {
			// Told apart by the block that sounds rather than by naming the note block, because for a
			// mob head the note block is the thing that goes underneath -- reaching for one there is
			// doing the step, not skipping it. What is excluded is whatever this step ends on.
			if (!held.is(stepBlock) && !held.is(Items.REPEATER)
				&& held.getItem() instanceof BlockItem blockItem) {
				BlockPos under = new BlockPlaceContext(player, hand, held, hitResult)
					.getClickedPos().immutable();
				String wanted = stepInstrument(placement);
				// Judged on what is in your hand, so a block that was never going to count does not sit
				// there being waited for. The same question is asked again of what actually lands.
				if (level.getBlockState(under).isAir()
					&& givesInstrument(blockItem.getBlock().defaultBlockState(), wanted)) {
					placementWatches.put(under,
						new PlacementWatch(expected, PLACEMENT_WATCH_TICKS, wanted, stepBlock));
				}
				return InteractionResult.PASS;
			}
			// Reaching for the block that sounds is how you say this one needs no instrument laid.
			awaitingInstrument = false;
		}
		if (!held.is(stepBlock)) {
			return InteractionResult.PASS;
		}

		BlockPlaceContext context = new BlockPlaceContext(player, hand, held, hitResult);
		BlockPos placementPos = context.getClickedPos().immutable();
		if (!matchesStepBlock(level.getBlockState(placementPos), stepBlock)) {
			placementWatches.put(placementPos, new PlacementWatch(expected, PLACEMENT_WATCH_TICKS, "", stepBlock));
		}
		return InteractionResult.PASS;
	}

	private void updatePlacementWatches(Minecraft minecraft) {
		if (minecraft.level == null) {
			placementWatches.clear();
			return;
		}
		if (!FastNoteblocksConfig.get().modEnabled() || !FastNoteblocksConfig.get().placementSequenceEnabled()) {
			placementWatches.clear();
			return;
		}

		placementWatches.entrySet().removeIf(entry -> {
			PlacementWatch watch = entry.getValue();
			if (watch.forInstrument()) {
				// Held to the same standard as the note itself: the step only opens once the block
				// under it would actually sound right. A wrong block waits here rather than counting.
				if (givesInstrument(minecraft.level.getBlockState(entry.getKey()), watch.instrument())) {
					awaitingInstrument = false;
					// Half a step is still a step: the window comes up for it the same way it does when a
					// note lands, rather than the instrument being the one placement that passes in
					// silence.
					showSequenceAdvance();
					selectCurrentSequenceItem(minecraft);
					return true;
				}
				int left = watch.ticksRemaining() - 1;
				if (left <= 0) {
					return true;
				}
				entry.setValue(new PlacementWatch(watch.step(), left, watch.instrument(), watch.stepBlock()));
				return false;
			}
			if (matchesStepBlock(minecraft.level.getBlockState(entry.getKey()), watch.stepBlock())) {
				applyPlacementStep(minecraft, entry.getKey(), watch.step(), watch.stepBlock());
				return true;
			}
			int remaining = watch.ticksRemaining() - 1;
			if (remaining <= 0) {
				return true;
			}
			entry.setValue(new PlacementWatch(watch.step(), remaining, "", watch.stepBlock()));
			return false;
		});
	}

	private void applyPlacementStep(Minecraft minecraft, BlockPos pos, NoteSequence.Step target,
			Item stepBlock) {
		// A sound effect is done the moment it is down. There is no pitch to click it round to, and
		// asking one for the value a note block keeps would not merely be pointless -- a door has no
		// such property, and reading it would throw.
		boolean tunable = stepBlock.equals(Items.NOTE_BLOCK) || stepBlock.equals(Items.REPEATER);
		int clicks = 0;
		if (tunable) {
			int currentValue = stepValue(minecraft.level.getBlockState(pos), target);
			clicks = target.type() == NoteSequence.StepType.NOTE
				? NotePitch.clicksForward(currentValue, target.value())
				: Math.floorMod(target.value() - currentValue, 4);
		}
		advancePlacementSequenceCursor();
		if (clicks > 0) {
			clickQueue.addLast(PendingClicks.ready(pos, clicks, true, target));
			expectedSteps.put(pos, new ExpectedStep(target, 60, true));
		}
	}

	private void advancePlacementSequenceCursor() {
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (!sequence.isEmpty()) {
			int previousIndex = Math.floorMod(placementSequenceIndex, sequence.size());
			int next = steppedPlacement(sequence, previousIndex, 1);
			placementSequenceIndex = next >= 0 ? next : 0;
			persistPlacementSequencePosition();
			showSequenceAdvance();
			beginPlacementStep(Minecraft.getInstance());
		}
	}

	private void selectCurrentSequenceItem(Minecraft minecraft) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (!config.placementSequenceEnabled()
			|| !config.autoSelectSequenceBlock()
			|| minecraft.player == null
			|| sequence.isEmpty()) {
			return;
		}
		NoteSequence.Placement current = sequence.get(Math.floorMod(placementSequenceIndex, sequence.size()));
		int hotbarSlot = awaitingInstrument
			? findInstrumentSlot(minecraft.player, stepInstrument(current))
			: findSequenceItemSlot(minecraft.player, stepBlockItem(current));
		if (hotbarSlot < 0 && awaitingInstrument) {
			hotbarSlot = fetchInstrumentToHotbar(minecraft, stepInstrument(current));
		}
		if (hotbarSlot >= 0) {
			minecraft.player.getInventory().setSelectedSlot(hotbarSlot);
			hotbarUsedAt[hotbarSlot] = ++hotbarClock;
		}
	}

	/**
	 * Brings the block a note needs down to the hotbar when it is only in the backpack.
	 *
	 * <p>A song with eight instruments does not fit in nine slots beside the note blocks and
	 * repeaters, so the alternative is opening the inventory every few notes. It goes to an empty
	 * slot if there is one, and otherwise takes the place of whichever instrument the sequencer has
	 * gone longest without asking for -- a swap, so that one lands in the slot this came out of and
	 * nothing is lost.</p>
	 *
	 * <p>Only ever displaces a block that is itself an instrument. A hotbar with no spare slot and
	 * nothing but tools on it is left exactly as it is.</p>
	 */
	private int fetchInstrumentToHotbar(Minecraft minecraft, String instrument) {
		LocalPlayer player = minecraft.player;
		if (minecraft.gui.screen() != null || minecraft.gameMode == null) {
			return -1;
		}
		int source = -1;
		for (int slot = 9; slot < player.getInventory().getContainerSize() && source < 0; slot++) {
			if (instrumentItemMatches(player.getInventory().getItem(slot), instrument)) {
				source = slot;
			}
		}
		if (source < 0) {
			return -1;
		}
		int target = -1;
		for (int slot = 0; slot < 9 && target < 0; slot++) {
			if (player.getInventory().getItem(slot).isEmpty()) {
				target = slot;
			}
		}
		if (target < 0) {
			long oldest = Long.MAX_VALUE;
			for (int slot = 0; slot < 9; slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				boolean spare = PreviewInstrument.isInstrumentBlock(stack) || stack.is(Items.DIRT);
				if (spare && hotbarUsedAt[slot] < oldest) {
					oldest = hotbarUsedAt[slot];
					target = slot;
				}
			}
		}
		if (target < 0) {
			return -1;
		}
		// The same exchange as hovering the slot and pressing its number, which is why the displaced
		// instrument ends up where this one was rather than anywhere loose.
		minecraft.gameMode.handleContainerInput(
			player.inventoryMenu.containerId, source, target, ContainerInput.SWAP, player);
		return target;
	}

	/** Whether a stack is something this instrument would accept, dirt included for harp. */
	private static boolean instrumentItemMatches(ItemStack stack, String instrument) {
		return isHarp(instrument)
			? stack.is(Items.GRASS_BLOCK) || stack.is(Items.DIRT)
			: stack.is(underBlockItem(instrument));
	}

	/**
	 * Opens a step: the block underneath first, when instruments are switched on, and then the note.
	 *
	 * <p>Called wherever the cursor lands rather than wherever it is read, so a jump, a placement and
	 * a reload all start the step the same way.</p>
	 */
	private void beginPlacementStep(Minecraft minecraft) {
		List<NoteSequence.Placement> sequence = configuredSequence();
		awaitingInstrument = !sequence.isEmpty()
			&& instrumentIsAStep(sequence.get(Math.floorMod(placementSequenceIndex, sequence.size())));
		selectCurrentSequenceItem(minecraft);
	}

	/**
	 * Whether this step wants a block underneath at all.
	 *
	 * <p>Harp does not: a note block over anything the game does not recognise already plays harp, so
	 * {@code SongBuilder} lays air for it rather than wasting a block a note. Stopping to ask for a
	 * block that the build itself would not place would be a step you could never satisfy.</p>
	 */
	private static boolean needsInstrumentLaid(NoteSequence.Placement placement) {
		if (placement.step().type() != NoteSequence.StepType.NOTE) {
			return false;
		}
		String instrument = stepInstrument(placement);
		PreviewInstrument voice = PreviewInstrument.byId(instrument);
		if (!voice.pitched()) {
			// A sound effect is the sound source itself, so there is nothing to stand it on. The mob
			// heads are the exception, and what goes under one is a note block.
			return voice.wearsASkull();
		}
		return !isHarp(instrument) || FastNoteblocksConfig.get().selectHarpBlocks();
	}

	/**
	 * What a step wants laid before the block that sounds, or empty when it wants nothing.
	 *
	 * <p>For a pitched note this is the instrument block and the note block goes over it. A mob head
	 * is the same shape read the other way round: the note block is what goes underneath and the
	 * skull is what lands on top, so the sequencer can walk it with the two phases it already has.</p>
	 */
	private static Item underBlockItem(String instrument) {
		PreviewInstrument voice = PreviewInstrument.byId(instrument);
		if (voice.wearsASkull()) {
			return Items.NOTE_BLOCK;
		}
		return voice.icon();
	}

	/** The block a step actually places -- an effect is its own, everything pitched is a note block. */
	private static Item stepBlockItem(NoteSequence.Placement placement) {
		if (placement.step().type() != NoteSequence.StepType.NOTE) {
			return Items.REPEATER;
		}
		PreviewInstrument voice = PreviewInstrument.byId(stepInstrument(placement));
		// A head's icon is its skull, which is exactly the block that goes on top, so both kinds of
		// effect answer this the same way.
		return voice.pitched() ? Items.NOTE_BLOCK : voice.icon();
	}

	/** A hotbar slot holding something this instrument will take, preferring the block on the tile. */
	private static int findInstrumentSlot(LocalPlayer player, String instrument) {
		if (isHarp(instrument)) {
			int grass = findHotbarSlot(player, Items.GRASS_BLOCK);
			return grass >= 0 ? grass : findHotbarSlot(player, Items.DIRT);
		}
		return findHotbarSlot(player, underBlockItem(instrument));
	}

	private static int findHotbarSlot(LocalPlayer player, net.minecraft.world.item.Item item) {
		if (player.getInventory().getItem(player.getInventory().getSelectedSlot()).is(item)) {
			return player.getInventory().getSelectedSlot();
		}
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getItem(slot).is(item)) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Carries the cursor across an edit to the song being placed.
	 *
	 * <p>Any change to any track used to send it back to step 0. That was nothing to lose when a
	 * song was six notes and is four thousand blocks of work when it is Guardian -- and it made
	 * fixing one wrong note mid-build something you would rather not do. The index is meaningless
	 * afterwards, because inserting a note moves every index behind it, so what is kept is the
	 * moment: the tick, and how far into it. If the edit deleted that moment outright, the next one
	 * that still exists is near enough to carry on from.</p>
	 */
	private void remapPlacementSequence(Minecraft minecraft) {
		cancelPlacementSequenceWork();
		List<NoteSequence.Placement> sequence = configuredSequence();
		placementSequenceIndex = sequence.isEmpty()
			? 0
			: NoteSequence.indexOfMoment(sequence, anchorTime, anchorOffset);
		persistPlacementSequencePosition();
		placementWatches.clear();
		beginPlacementStep(minecraft);
	}

	/** Takes up whatever bookmark the song now open was left at. */
	private void loadPlacementPosition(Minecraft minecraft) {
		cancelPlacementSequenceWork();
		List<NoteSequence.Placement> sequence = configuredSequence();
		placementSequenceIndex = sequence.isEmpty()
			? 0
			: Math.min(FastNoteblocksConfig.get().placementSequencePosition(), sequence.size() - 1);
		if (placementSequenceIndex != FastNoteblocksConfig.get().placementSequencePosition()) {
			FastNoteblocksConfig.get().setPlacementSequencePosition(placementSequenceIndex);
			sequencePositionSavePending = true;
		}
		rememberPlacementMoment();
		placementWatches.clear();
		beginPlacementStep(minecraft);
		showSequenceHud(minecraft, null);
	}

	private void persistPlacementSequencePosition() {
		FastNoteblocksConfig.get().setPlacementSequencePosition(placementSequenceIndex);
		rememberPlacementMoment();
		sequencePositionSavePending = true;
	}

	/**
	 * Notes where the cursor is in musical terms, against the sequence as it stands now.
	 *
	 * <p>Taken every time the cursor moves rather than every frame, which is what makes it the right
	 * answer: by the time an edit is noticed the new sequence is already in hand, and the anchor
	 * still describes the old one.</p>
	 */
	private void rememberPlacementMoment() {
		List<NoteSequence.Placement> sequence = configuredSequence();
		if (sequence.isEmpty()) {
			anchorTime = 0;
			anchorOffset = 0;
			return;
		}
		int index = Math.max(0, Math.min(sequence.size() - 1, placementSequenceIndex));
		anchorTime = sequence.get(index).time();
		anchorOffset = NoteSequence.momentOffset(sequence, index);
	}

	private void cancelPlacementSequenceWork() {
		clickQueue.removeIf(PendingClicks::placementSequence);
		expectedSteps.entrySet().removeIf(entry -> entry.getValue().placementSequence());
		placementWatches.clear();
	}

	private void render(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		if (!overlaysActive(config) || !isReady(minecraft)
			|| (nearbyNoteBlocks.isEmpty() && nearbyRepeaters.isEmpty())) {
			return;
		}

		CameraRenderState cameraState = context.levelState().cameraRenderState;
		Vec3 cameraPos = cameraState.pos;
		PoseStack poseStack = context.poseStack();
		HoveredLabel hovered = findHoveredLabel(minecraft);

		if (config.overlayMode().includesNotes()) {
			for (BlockPos pos : nearbyNoteBlocks) {
				BlockState state = minecraft.level.getBlockState(pos);
				if (!state.is(Blocks.NOTE_BLOCK)) {
					continue;
				}

				boolean focusedBlock = pos.equals(expandedBlock)
					|| hovered != null && hovered.blockPos().equals(pos);
				if (!config.nearbyPreviewsEnabled() && !focusedBlock) {
					continue;
				}

				int pitch = displayedPitch(minecraft.level, pos);
				boolean inRange = minecraft.player.isWithinBlockInteractionRange(pos, 0.0);
				Vec3 labelCenter = new Vec3(pos.getX() + 0.5, pos.getY() + LABEL_Y, pos.getZ() + 0.5);
				Vec3 right = labelRight(cameraPos, labelCenter);
				Vec3 up = labelUp(cameraPos, labelCenter, right);
				boolean expanded = config.interactiveControlsEnabled() && pos.equals(expandedBlock);
				poseStack.pushPose();
				poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
				char layoutCenterFamily = expanded && menuCenterFamily != null
					? menuCenterFamily
					: NotePitch.family(pitch);
				for (char family : FAMILIES) {
					if (!expanded && family != NotePitch.family(pitch)) {
						continue;
					}
					LabelOffset offset = labelOffset(layoutCenterFamily, family);
					Vec3 attachment = new Vec3(
						0.5 + right.x * offset.right() + up.x * offset.up(),
						LABEL_Y - 0.5 + right.y * offset.right() + up.y * offset.up(),
						0.5 + right.z * offset.right() + up.z * offset.up()
					);
					boolean isHovered = hovered != null && !hovered.isRepeater()
						&& hovered.blockPos().equals(pos) && hovered.family() == family;
					Component text = labelText(pitch, family, inRange, isHovered);
					context.submitNodeCollector().submitNameTag(
						poseStack, attachment, 0, text, true, LightCoordsUtil.FULL_BRIGHT, cameraState
					);
				}
				poseStack.popPose();
			}
		}

		if (config.overlayMode().includesRepeaters()) {
			for (BlockPos pos : nearbyRepeaters) {
				BlockState state = minecraft.level.getBlockState(pos);
				if (!state.is(Blocks.REPEATER)) {
					continue;
				}
				boolean focused = pos.equals(expandedRepeater)
					|| hovered != null && hovered.isRepeater() && hovered.blockPos().equals(pos);
				if (!config.nearbyPreviewsEnabled() && !focused) {
					continue;
				}
				boolean inRange = minecraft.player.isWithinBlockInteractionRange(pos, 0.0);
				int currentDelay = displayedRepeaterDelay(minecraft.level, pos);
				boolean expanded = config.interactiveControlsEnabled()
					&& config.repeaterControlStyle() == FastNoteblocksConfig.RepeaterControlStyle.RADIAL_SELECT
					&& pos.equals(expandedRepeater);
				int layoutBottomDelay = expanded && repeaterBottomDelay != null ? repeaterBottomDelay : currentDelay;
				Vec3 labelCenter = new Vec3(pos.getX() + 0.5, pos.getY() + REPEATER_LABEL_Y, pos.getZ() + 0.5);
				Vec3 right = labelRight(cameraPos, labelCenter);
				Vec3 up = labelUp(cameraPos, labelCenter, right);
				poseStack.pushPose();
				poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
				for (int delay = 1; delay <= 4; delay++) {
					if (!expanded && delay != currentDelay) {
						continue;
					}
					LabelOffset offset = expanded
						? repeaterLabelOffset(layoutBottomDelay, delay)
						: new LabelOffset(0.0, 0.0);
					Vec3 attachment = new Vec3(
						0.5 + right.x * offset.right() + up.x * offset.up(),
						REPEATER_LABEL_Y - 0.5 + right.y * offset.right() + up.y * offset.up(),
						0.5 + right.z * offset.right() + up.z * offset.up()
					);
					boolean isHovered = hovered != null && hovered.isRepeater()
						&& hovered.blockPos().equals(pos) && hovered.repeaterDelay() == delay;
					Component text = Component.literal(Integer.toString(delay));
					if (isHovered) {
						text = text.copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD, ChatFormatting.UNDERLINE);
					} else if (delay == currentDelay) {
						text = text.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
					} else {
						text = text.copy().withStyle(inRange ? ChatFormatting.WHITE : ChatFormatting.GRAY);
					}
					context.submitNodeCollector().submitNameTag(
						poseStack, attachment, 0, text, true, LightCoordsUtil.FULL_BRIGHT, cameraState
					);
				}
				poseStack.popPose();
			}
		}
	}

	private HoveredLabel findHoveredLabel(Minecraft minecraft) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		boolean interactiveControlsEnabled = config.interactiveControlsEnabled()
			&& !sequenceRadialsBlocked(config);
		if (!interactiveControlsEnabled || !config.overlayMode().includesNotes()) {
			expandedBlock = null;
			menuCenterFamily = null;
		}
		boolean repeaterRadialEnabled = interactiveControlsEnabled
			&& config.overlayMode().includesRepeaters()
			&& config.repeaterControlStyle() == FastNoteblocksConfig.RepeaterControlStyle.RADIAL_SELECT;
		if (!repeaterRadialEnabled) {
			expandedRepeater = null;
			repeaterBottomDelay = null;
		}
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 origin = camera.position();
		Vec3 direction = new Vec3(camera.forwardVector()).normalize();

		if (interactiveControlsEnabled
			&& expandedBlock != null
			&& minecraft.player.isWithinBlockInteractionRange(expandedBlock, 0.0)
			&& minecraft.level.getBlockState(expandedBlock).is(Blocks.NOTE_BLOCK)) {
			LabelHit expandedHit = hitLabelOnBlock(minecraft, expandedBlock, true, origin, direction);
			if (expandedHit != null) {
				return expandedHit.label();
			}
			menuCenterFamily = NotePitch.family(displayedPitch(minecraft.level, expandedBlock));
			if (isInsideMenuEnvelope(
				expandedBlock, LABEL_Y, 1.05, 0.78, origin, direction
			)) {
				return null;
			}
		}
		if (repeaterRadialEnabled
			&& expandedRepeater != null
			&& minecraft.player.isWithinBlockInteractionRange(expandedRepeater, 0.0)
			&& minecraft.level.getBlockState(expandedRepeater).is(Blocks.REPEATER)) {
			LabelHit expandedHit = hitRepeaterLabel(minecraft, expandedRepeater, true, origin, direction);
			if (expandedHit != null) {
				return expandedHit.label();
			}
			repeaterBottomDelay = displayedRepeaterDelay(minecraft.level, expandedRepeater);
			if (isInsideMenuEnvelope(
				expandedRepeater, REPEATER_LABEL_Y, 0.62, 0.58, origin, direction
			)) {
				return null;
			}
		}

		LabelHit closest = null;
		if (config.overlayMode().includesNotes()) {
			for (BlockPos pos : nearbyNoteBlocks) {
				if (pos.equals(expandedBlock)
					|| !minecraft.player.isWithinBlockInteractionRange(pos, 0.0)
					|| !minecraft.level.getBlockState(pos).is(Blocks.NOTE_BLOCK)) {
					continue;
				}
				LabelHit hit = hitLabelOnBlock(minecraft, pos, false, origin, direction);
				if (hit != null && (closest == null || hit.distance() < closest.distance())) {
					closest = hit;
				}
			}
		}
		if (config.overlayMode().includesRepeaters()) {
			for (BlockPos pos : nearbyRepeaters) {
				if (!minecraft.player.isWithinBlockInteractionRange(pos, 0.0)
					|| !minecraft.level.getBlockState(pos).is(Blocks.REPEATER)) {
					continue;
				}
				if (pos.equals(expandedRepeater)) {
					continue;
				}
				LabelHit hit = hitRepeaterLabel(minecraft, pos, false, origin, direction);
				if (hit != null && (closest == null || hit.distance() < closest.distance())) {
					closest = hit;
				}
			}
		}

		if (closest != null) {
			boolean noteRadial = interactiveControlsEnabled && !closest.label().isRepeater();
			boolean repeaterRadial = repeaterRadialEnabled && closest.label().isRepeater();
			if (noteRadial || repeaterRadial) {
				if (!radialFocusReady(minecraft, closest.label())) {
					expandedBlock = null;
					menuCenterFamily = null;
					expandedRepeater = null;
					repeaterBottomDelay = null;
					return closest.label();
				}
			} else {
				clearRadialFocusCandidate();
			}
			if (noteRadial) {
				expandedBlock = closest.label().blockPos();
				menuCenterFamily = NotePitch.family(displayedPitch(minecraft.level, expandedBlock));
				expandedRepeater = null;
				repeaterBottomDelay = null;
			} else if (repeaterRadial) {
				expandedRepeater = closest.label().blockPos();
				repeaterBottomDelay = displayedRepeaterDelay(minecraft.level, expandedRepeater);
				expandedBlock = null;
				menuCenterFamily = null;
			} else {
				expandedBlock = null;
				menuCenterFamily = null;
				expandedRepeater = null;
				repeaterBottomDelay = null;
			}
			return closest.label();
		}
		clearRadialFocusCandidate();
		expandedBlock = null;
		menuCenterFamily = null;
		expandedRepeater = null;
		repeaterBottomDelay = null;
		return null;
	}

	private boolean radialFocusReady(Minecraft minecraft, HoveredLabel label) {
		int delay = FastNoteblocksConfig.get().radialFocusDelayTicks();
		if (delay == 0) {
			clearRadialFocusCandidate();
			return true;
		}
		boolean sameCandidate = label.blockPos().equals(radialFocusCandidate)
			&& label.isRepeater() == radialFocusCandidateRepeater;
		if (!sameCandidate) {
			radialFocusCandidate = label.blockPos();
			radialFocusCandidateRepeater = label.isRepeater();
			radialFocusStartedTick = minecraft.level.getGameTime();
			return false;
		}
		if (minecraft.level.getGameTime() - radialFocusStartedTick < delay) {
			return false;
		}
		clearRadialFocusCandidate();
		return true;
	}

	private void clearRadialFocusCandidate() {
		radialFocusCandidate = null;
		radialFocusCandidateRepeater = false;
		radialFocusStartedTick = 0L;
	}

	private LabelHit hitLabelOnBlock(Minecraft minecraft, BlockPos pos, boolean expanded, Vec3 origin, Vec3 direction) {
		int pitch = displayedPitch(minecraft.level, pos);
		boolean inRange = minecraft.player.isWithinBlockInteractionRange(pos, 0.0);
		Vec3 baseCenter = new Vec3(pos.getX() + 0.5, pos.getY() + LABEL_Y, pos.getZ() + 0.5);
		Vec3 right = labelRight(origin, baseCenter);
		Vec3 layoutUp = labelUp(origin, baseCenter, right);
		LabelHit closest = null;
		char layoutCenterFamily = expanded && menuCenterFamily != null
			? menuCenterFamily
			: NotePitch.family(pitch);

		for (char family : FAMILIES) {
			if (!expanded && family != NotePitch.family(pitch)) {
				continue;
			}
			LabelOffset offset = labelOffset(layoutCenterFamily, family);
			Vec3 center = baseCenter
				.add(right.scale(offset.right()))
				.add(layoutUp.scale(offset.up()));
			Vec3 normal = origin.subtract(center).normalize();
			double denominator = direction.dot(normal);
			if (Math.abs(denominator) < 1.0E-5) {
				continue;
			}
			double distance = center.subtract(origin).dot(normal) / denominator;
			if (distance <= 0.0 || (closest != null && distance >= closest.distance())) {
				continue;
			}
			Vec3 hitOffset = origin.add(direction.scale(distance)).subtract(center);
			Vec3 up = normal.cross(right).normalize();
			String text = labelString(pitch, family, inRange);
			double halfWidth = minecraft.font.width(text) * 0.0125 + 0.04;
			if (Math.abs(hitOffset.dot(right)) <= halfWidth && Math.abs(hitOffset.dot(up)) <= 0.15) {
				closest = new LabelHit(new HoveredLabel(pos, family, -1), distance);
			}
		}
		return closest;
	}

	private LabelHit hitRepeaterLabel(
		Minecraft minecraft, BlockPos pos, boolean expanded, Vec3 origin, Vec3 direction
	) {
		int currentDelay = displayedRepeaterDelay(minecraft.level, pos);
		int layoutBottomDelay = expanded && repeaterBottomDelay != null ? repeaterBottomDelay : currentDelay;
		Vec3 baseCenter = new Vec3(pos.getX() + 0.5, pos.getY() + REPEATER_LABEL_Y, pos.getZ() + 0.5);
		Vec3 right = labelRight(origin, baseCenter);
		Vec3 layoutUp = labelUp(origin, baseCenter, right);
		LabelHit closest = null;
		for (int delay = 1; delay <= 4; delay++) {
			if (!expanded && delay != currentDelay) {
				continue;
			}
			LabelOffset offset = expanded
				? repeaterLabelOffset(layoutBottomDelay, delay)
				: new LabelOffset(0.0, 0.0);
			Vec3 center = baseCenter
				.add(right.scale(offset.right()))
				.add(layoutUp.scale(offset.up()));
			Vec3 normal = origin.subtract(center).normalize();
			double denominator = direction.dot(normal);
			if (Math.abs(denominator) < 1.0E-5) {
				continue;
			}
			double distance = center.subtract(origin).dot(normal) / denominator;
			if (distance <= 0.0 || closest != null && distance >= closest.distance()) {
				continue;
			}
			Vec3 hitOffset = origin.add(direction.scale(distance)).subtract(center);
			Vec3 up = normal.cross(right).normalize();
			double halfWidth = minecraft.font.width(Integer.toString(delay)) * 0.0125 + 0.04;
			if (Math.abs(hitOffset.dot(right)) <= halfWidth && Math.abs(hitOffset.dot(up)) <= 0.15) {
				closest = new LabelHit(new HoveredLabel(pos, null, delay), distance);
			}
		}
		return closest;
	}

	private boolean isInsideMenuEnvelope(
		BlockPos pos, double labelY, double halfWidth, double halfHeight, Vec3 origin, Vec3 direction
	) {
		Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + labelY, pos.getZ() + 0.5);
		Vec3 normal = origin.subtract(center).normalize();
		double denominator = direction.dot(normal);
		if (Math.abs(denominator) < 1.0E-5) {
			return false;
		}
		double distance = center.subtract(origin).dot(normal) / denominator;
		if (distance <= 0.0) {
			return false;
		}
		Vec3 right = labelRight(origin, Vec3.atCenterOf(pos));
		Vec3 up = normal.cross(right).normalize();
		Vec3 offset = origin.add(direction.scale(distance)).subtract(center);
		return Math.abs(offset.dot(right)) <= halfWidth && Math.abs(offset.dot(up)) <= halfHeight;
	}

	private int displayedPitch(ClientLevel level, BlockPos pos) {
		ExpectedStep expected = expectedSteps.get(pos);
		if (expected != null && expected.step().type() == NoteSequence.StepType.NOTE) {
			return expected.step().value();
		}
		BlockState state = level.getBlockState(pos);
		return state.is(Blocks.NOTE_BLOCK) ? state.getValue(NoteBlock.NOTE) : 0;
	}

	private int displayedRepeaterDelay(ClientLevel level, BlockPos pos) {
		ExpectedStep expected = expectedSteps.get(pos);
		if (expected != null && expected.step().type() == NoteSequence.StepType.REPEATER) {
			return expected.step().value();
		}
		BlockState state = level.getBlockState(pos);
		return state.is(Blocks.REPEATER) ? state.getValue(RepeaterBlock.DELAY) : 1;
	}

	private Component labelText(int pitch, char family, boolean inRange, boolean hovered) {
		Component label = Component.literal(labelString(pitch, family, inRange));
		if (hovered) {
			return label.copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD, ChatFormatting.UNDERLINE);
		}
		if (NotePitch.family(pitch) == family) {
			return label.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		}
		return label.copy().withStyle(inRange ? ChatFormatting.WHITE : ChatFormatting.GRAY);
	}

	private String labelString(int pitch, char family, boolean inRange) {
		if (!inRange && NotePitch.family(pitch) != family) {
			return Character.toString(family);
		}
		int shownPitch = NotePitch.family(pitch) == family ? pitch : NotePitch.nextInFamily(pitch, family);
		return NotePitch.name(shownPitch) + " " + shownPitch;
	}

	private static LabelOffset labelOffset(char centerFamily, char family) {
		if (family == centerFamily) {
			return new LabelOffset(0.0, 0.0);
		}

		int outerIndex = 0;
		for (char candidate : FAMILIES) {
			if (candidate == centerFamily) {
				continue;
			}
			if (candidate == family) {
				double angle = Math.PI / 2.0 + outerIndex * Math.PI / 3.0;
				return new LabelOffset(Math.cos(angle) * MENU_HORIZONTAL_RADIUS, Math.sin(angle) * MENU_VERTICAL_RADIUS);
			}
			outerIndex++;
		}
		throw new IllegalArgumentException("Unknown pitch family: " + family);
	}

	private static LabelOffset repeaterLabelOffset(int bottomDelay, int delay) {
		return switch (Math.floorMod(delay - bottomDelay, 4)) {
			case 0 -> new LabelOffset(0.0, -REPEATER_MENU_VERTICAL_RADIUS);
			case 1 -> new LabelOffset(-REPEATER_MENU_HORIZONTAL_RADIUS, 0.0);
			case 2 -> new LabelOffset(0.0, REPEATER_MENU_VERTICAL_RADIUS);
			case 3 -> new LabelOffset(REPEATER_MENU_HORIZONTAL_RADIUS, 0.0);
			default -> throw new IllegalStateException("Unexpected repeater delay offset");
		};
	}

	private static Vec3 labelRight(Vec3 cameraPos, Vec3 blockCenter) {
		Vec3 toCamera = cameraPos.subtract(blockCenter);
		Vec3 right = new Vec3(-toCamera.z, 0.0, toCamera.x);
		return right.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
	}

	private static Vec3 labelUp(Vec3 cameraPos, Vec3 labelCenter, Vec3 right) {
		Vec3 normal = cameraPos.subtract(labelCenter).normalize();
		Vec3 up = right.cross(normal);
		return up.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : up.normalize();
	}

	private static boolean isReady(Minecraft minecraft) {
		return minecraft.level != null && minecraft.player != null && minecraft.gameMode != null;
	}

	private static boolean overlaysActive(FastNoteblocksConfig config) {
		return config.modEnabled() && config.overlaysEnabled();
	}

	private static boolean sequenceRadialsBlocked(FastNoteblocksConfig config) {
		return isSequencingActive(config, configuredSequence())
			&& config.sequencingEditProtection().blocksRadials();
	}

	private static boolean isSequencingActive(FastNoteblocksConfig config, List<NoteSequence.Placement> sequence) {
		return config.modEnabled() && config.placementSequenceEnabled() && !sequence.isEmpty();
	}

	private record PendingClicks(
		BlockPos blockPos,
		int remaining,
		boolean placementSequence,
		NoteSequence.Step targetStep,
		int expectedValueAfterClick,
		int ackTicksRemaining,
		int cooldownTicks
	) {
		private static PendingClicks ready(
			BlockPos blockPos, int remaining, boolean placementSequence, NoteSequence.Step targetStep
		) {
			return new PendingClicks(blockPos, remaining, placementSequence, targetStep, -1, 0, 0);
		}

		private PendingClicks afterClick(int expectedValue) {
			return new PendingClicks(
				blockPos, remaining - 1, placementSequence, targetStep,
				expectedValue, INTERACTION_ACK_TIMEOUT_TICKS, 0
			);
		}

		private PendingClicks afterAcknowledgement(int cooldownTicks) {
			return new PendingClicks(blockPos, remaining, placementSequence, targetStep, -1, 0, cooldownTicks);
		}

		private PendingClicks withoutAcknowledgement(int cooldownTicks) {
			return new PendingClicks(blockPos, remaining, placementSequence, targetStep, -1, 0, cooldownTicks);
		}

		private PendingClicks afterUnconfirmedClick(int cooldownTicks) {
			return new PendingClicks(blockPos, remaining - 1, placementSequence, targetStep, -1, 0, cooldownTicks);
		}

		private PendingClicks waitOneTick() {
			return new PendingClicks(
				blockPos, remaining, placementSequence, targetStep,
				expectedValueAfterClick, ackTicksRemaining - 1, cooldownTicks
			);
		}

		private PendingClicks coolDownOneTick() {
			return new PendingClicks(
				blockPos, remaining, placementSequence, targetStep,
				expectedValueAfterClick, ackTicksRemaining, cooldownTicks - 1
			);
		}
	}

	private record ExpectedStep(NoteSequence.Step step, int ticksRemaining, boolean placementSequence) {
	}

	/**
	 * @param stepBlock what the block that sounds will be once it lands. Carried rather than worked
	 *     out from the step, because the step alone cannot say it: a note is a note block for every
	 *     pitched instrument and an oak door for one of the sound effects.
	 */
	private record PlacementWatch(NoteSequence.Step step, int ticksRemaining, String instrument,
			Item stepBlock) {
		boolean forInstrument() {
			return !instrument.isEmpty();
		}
	}

	private record HoveredLabel(BlockPos blockPos, Character family, int repeaterDelay) {
		private boolean isRepeater() {
			return family == null;
		}
	}

	private record LabelHit(HoveredLabel label, double distance) {
	}

	private record LabelOffset(double right, double up) {
	}

	private record NoteEvent(int time, int instrumentRank, int trackNumber, int localIndex, NoteSequence.Step step) {
	}

	private record SequenceHudItem(int startIndex, int endIndex, NoteSequence.Placement step) {
	}

	/** A chord laid out for drawing. Each row is {first, last, 1 if it opens an instrument}. */
	/** One thing on the strip: a chord as a two-row card, or a run of delay as a single token. */
	private record HudCard(int firstItem, int lastItem, boolean chord, int columns, int tileWidth, int width) {
	}

	private enum SequenceHudMode {
		FULL,
		ADVANCE
	}
}
