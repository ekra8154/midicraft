package com.fastnoteblocks.client;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.NoteSequence;
import com.fastnoteblocks.client.compat.ComposerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class NoteBlockOverlay {
	private static final int RESCAN_INTERVAL_TICKS = 10;
	private static final int PLACEMENT_WATCH_TICKS = 12;
	private static final int INTERACTION_ACK_TIMEOUT_TICKS = 40;
	private static final int SEQUENCE_HUD_TICKS = 50;
	private static final int SEQUENCE_ADVANCE_HUD_TICKS = 32;
	private static final int SEQUENCE_ADVANCE_ANIMATION_TICKS = 6;
	private static final int SEQUENCE_HUD_RADIUS = 4;
	private static final int SEQUENCE_DOUBLE_TAP_TICKS = 7;
	private static final double LABEL_Y = 1.40;
	private static final double REPEATER_LABEL_Y = LABEL_Y - 0.75;
	private static final double MENU_HORIZONTAL_RADIUS = 0.72;
	private static final double MENU_VERTICAL_RADIUS = 0.50;
	private static final double REPEATER_MENU_HORIZONTAL_RADIUS = 0.24;
	private static final double REPEATER_MENU_VERTICAL_RADIUS = 0.20;
	private static final char[] FAMILIES = {'A', 'B', 'C', 'D', 'E', 'F', 'G'};
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
	private final KeyMapping composerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.fast-noteblocks.open_composer", InputConstants.Type.KEYSYM, -1, CATEGORY
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
	private int lastActiveTrackIndex;
	private int lastSequenceDelayScaleQuarters;
	private String lastPlacementSequenceText = "";
	private boolean performingAutomatedClick;
	private int sequenceHudTicks;
	private Component sequenceHudAction;
	private SequenceHudMode sequenceHudMode = SequenceHudMode.FULL;
	private int sequenceAdvanceFromIndex = -1;
	private int sequenceAdvanceToIndex = -1;
	private int sequenceTapWindowTicks;
	private boolean sequenceControlKeyDown;
	private boolean sequenceGestureConsumed;
	private boolean sequenceSecondTap;

	private NoteBlockOverlay() {
	}

	public void register() {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastAutoSelectSequenceBlock = config.autoSelectSequenceBlock();
		lastActiveTrackIndex = config.activeTrackIndex();
		lastSequenceDelayScaleQuarters = config.activeSequenceDelayScaleQuarters();
		lastPlacementSequenceText = buildSequenceSignature(config);
		List<BuildStep> sequence = configuredSequence();
		placementSequenceIndex = sequence.isEmpty()
			? 0
			: Math.min(config.placementSequencePosition(), sequence.size() - 1);
		if (placementSequenceIndex != config.placementSequencePosition()) {
			config.setPlacementSequencePosition(placementSequenceIndex);
			sequencePositionSavePending = true;
		}
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
		movePlacementSequence(amount);
		showSequenceHud(minecraft, null);
		return true;
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
			selectCurrentSequenceItem(minecraft);
		}
		if (config.autoSelectSequenceBlock() && !lastAutoSelectSequenceBlock) {
			selectCurrentSequenceItem(minecraft);
		}
		String sequenceSignature = buildSequenceSignature(config);
		if (config.activeTrackIndex() != lastActiveTrackIndex) {
			loadActiveTrack(minecraft);
		} else if (!sequenceSignature.equals(lastPlacementSequenceText)
			|| config.activeSequenceDelayScaleQuarters() != lastSequenceDelayScaleQuarters) {
			resetPlacementSequence();
		}
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastAutoSelectSequenceBlock = config.autoSelectSequenceBlock();
		lastActiveTrackIndex = config.activeTrackIndex();
		lastSequenceDelayScaleQuarters = config.activeSequenceDelayScaleQuarters();
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
			selectCurrentSequenceItem(minecraft);
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
		List<BuildStep> sequence = configuredSequence();
		cancelPlacementSequenceWork();
		if (!sequence.isEmpty()) {
			int previousIndex = placementSequenceIndex;
			placementSequenceIndex = Math.max(0, Math.min(
				sequence.size() - 1, placementSequenceIndex + amount
			));
			if (placementSequenceIndex != previousIndex) {
				persistPlacementSequencePosition();
				selectCurrentSequenceItem(Minecraft.getInstance());
			}
		}
	}

	private void showSequenceHud(Minecraft minecraft, String actionKey) {
		if (minecraft.player == null) {
			return;
		}
		sequenceHudAction = actionKey == null ? null : Component.translatable(actionKey);
		sequenceHudMode = SequenceHudMode.FULL;
		sequenceHudTicks = SEQUENCE_HUD_TICKS;
	}

	private void showSequenceAdvance(int placedIndex, int nextIndex) {
		sequenceAdvanceFromIndex = placedIndex;
		sequenceAdvanceToIndex = nextIndex;
		sequenceHudAction = null;
		sequenceHudMode = SequenceHudMode.ADVANCE;
		sequenceHudTicks = SEQUENCE_ADVANCE_HUD_TICKS;
	}

	private void renderSequenceHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean keyHeld = placementSequenceKey.isDown();
		if (!FastNoteblocksConfig.get().placementSequenceEnabled()
			|| (!keyHeld && sequenceHudTicks <= 0)
			|| minecraft.player == null
			|| minecraft.gui.screen() != null) {
			return;
		}
		List<BuildStep> sequence = configuredSequence();
		int centerX = graphics.guiWidth() / 2;
		int y = graphics.guiHeight() / 2 + 28;
		boolean showingAdvanceOnly = !keyHeld && sequenceHudMode == SequenceHudMode.ADVANCE
			&& sequenceAdvanceFromIndex >= 0 && sequenceAdvanceToIndex >= 0;
		if (!showingAdvanceOnly) {
			String trackLabel = buildTrackLabel(FastNoteblocksConfig.get());
			graphics.centeredText(minecraft.font, trackLabel, centerX, y - 14, 0xFFAAAAAA);
		}
		if (sequenceHudTicks > 0 && sequenceHudAction != null && sequenceHudMode == SequenceHudMode.FULL) {
			graphics.centeredText(minecraft.font, sequenceHudAction, centerX, y - 25, 0xFFCCCCCC);
		}
		if (sequence.isEmpty()) {
			graphics.centeredText(minecraft.font, Component.translatable(
				"message.fast-noteblocks.sequence_hud_empty"
			), centerX, y, 0xFFFF5555);
			return;
		}
		List<SequenceHudItem> items = sequenceHudItems(sequence);
		if (showingAdvanceOnly) {
			renderSequenceAdvance(graphics, deltaTracker, centerX, y, sequence, items);
			drawSequencePosition(graphics, centerX, y, sequence);
			return;
		}

		int index = Math.max(0, Math.min(sequence.size() - 1, placementSequenceIndex));
		int itemIndex = sequenceHudItemIndex(items, index);
		SequenceHudToken current = sequenceHudToken(items.get(itemIndex), index);
		int currentWidth = minecraft.font.width(current.text());
		int currentX = centerX - currentWidth / 2;
		drawSequenceHudToken(graphics, current, currentX, y, true,
			itemIndex > 0 && chordAdjacent(items.get(itemIndex - 1), items.get(itemIndex)),
			itemIndex + 1 < items.size() && chordAdjacent(items.get(itemIndex), items.get(itemIndex + 1)));

		int leftX = currentX;
		for (int offset = 1; offset <= SEQUENCE_HUD_RADIUS && itemIndex - offset >= 0; offset++) {
			int leftItemIndex = itemIndex - offset;
			SequenceHudItem leftItem = items.get(leftItemIndex);
			SequenceHudToken token = sequenceHudToken(leftItem, -1);
			int width = minecraft.font.width(token.text());
			leftX -= sequenceHudItemGap(leftItem, items.get(leftItemIndex + 1)) + width;
			drawSequenceHudToken(graphics, token, leftX, y, false, false, false);
		}

		int rightX = currentX + currentWidth;
		for (int offset = 1; offset <= SEQUENCE_HUD_RADIUS && itemIndex + offset < items.size(); offset++) {
			int rightItemIndex = itemIndex + offset;
			SequenceHudItem rightItem = items.get(rightItemIndex);
			rightX += sequenceHudItemGap(items.get(rightItemIndex - 1), rightItem);
			SequenceHudToken token = sequenceHudToken(rightItem, -1);
			drawSequenceHudToken(graphics, token, rightX, y, false, false, false);
			rightX += minecraft.font.width(token.text());
		}
		drawSequencePosition(graphics, centerX, y, sequence);
	}

	private static String instrumentLabel(String id) {
		if (id == null || id.isBlank()) {
			return "Harp";
		}
		String[] words = id.toLowerCase(java.util.Locale.ROOT).split("_");
		StringBuilder result = new StringBuilder();
		for (String word : words) {
			if (!result.isEmpty()) {
				result.append(' ');
			}
			result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}

	private static String buildTrackLabel(FastNoteblocksConfig config) {
		List<FastNoteblocksConfig.SequenceTrack> tracks = config.tracks();
		List<String> enabled = new ArrayList<>();
		for (int index = 0; index < tracks.size(); index++) {
			if (tracks.get(index).buildEnabled() && !tracks.get(index).sequence().isBlank()) {
				enabled.add(Integer.toString(index + 1));
			}
		}
		return enabled.isEmpty() ? "Build: no enabled tracks" : "Build tracks: " + String.join(", ", enabled);
	}

	private void drawSequencePosition(GuiGraphicsExtractor graphics, int centerX, int y, List<BuildStep> sequence) {
		Minecraft minecraft = Minecraft.getInstance();
		NoteSequence.Progress progress = buildProgress(sequence, placementSequenceIndex);
		String position = progress.position() + "/" + progress.total();
		int positionY = y + minecraft.font.lineHeight + 6;
		graphics.centeredText(minecraft.font, position, centerX, positionY, 0xFF999999);

		String noteBlocks = progress.noteBlockPosition() + "/" + progress.noteBlockTotal() + " nb";
		String repeaters = progress.repeaterPosition() + "/" + progress.repeaterTotal() + " rp";
		int smallX = centerX + minecraft.font.width(position) / 2 + 5;
		float scale = 0.65F;
		graphics.pose().pushMatrix();
		graphics.pose().translate(smallX, positionY - 1);
		graphics.pose().scale(scale, scale);
		graphics.text(minecraft.font, noteBlocks, 0, 0, 0xFF55FFFF, false);
		graphics.text(minecraft.font, repeaters, 0, minecraft.font.lineHeight + 1, 0xFFFFAA00, false);
		graphics.pose().popMatrix();
	}

	private static NoteSequence.Progress buildProgress(List<BuildStep> sequence, int currentIndex) {
		List<NoteSequence.Step> steps = sequence.stream().map(BuildStep::step).toList();
		return NoteSequence.progress(steps, currentIndex);
	}

	private void renderSequenceAdvance(
		GuiGraphicsExtractor graphics,
		DeltaTracker deltaTracker,
		int centerX,
		int y,
		List<BuildStep> sequence,
		List<SequenceHudItem> items
	) {
		Minecraft minecraft = Minecraft.getInstance();
		int placedIndex = Math.max(0, Math.min(sequence.size() - 1, sequenceAdvanceFromIndex));
		int nextIndex = Math.max(0, Math.min(sequence.size() - 1, sequenceAdvanceToIndex));
		int placedItemIndex = sequenceHudItemIndex(items, placedIndex);
		int nextItemIndex = sequenceHudItemIndex(items, nextIndex);
		SequenceHudItem placedItem = items.get(placedItemIndex);
		SequenceHudItem nextItem = items.get(nextItemIndex);
		float elapsed = SEQUENCE_ADVANCE_HUD_TICKS - sequenceHudTicks
			+ deltaTracker.getGameTimeDeltaPartialTick(false);
		float progress = Math.max(0.0F, Math.min(1.0F, elapsed / SEQUENCE_ADVANCE_ANIMATION_TICKS));
		if (placedItem == nextItem) {
			int activeIndex = progress < 0.5F ? placedIndex : nextIndex;
			SequenceHudToken token = sequenceHudToken(nextItem, activeIndex);
			int x = centerX - minecraft.font.width(token.text()) / 2;
			drawSequenceHudToken(graphics, token, x, y, true,
				nextItemIndex > 0 && chordAdjacent(items.get(nextItemIndex - 1), nextItem),
				nextItemIndex + 1 < items.size() && chordAdjacent(nextItem, items.get(nextItemIndex + 1)));
			return;
		}
		int chordStart = nextItemIndex;
		int chordEnd = nextItemIndex;
		if (nextItem.step().step().type() == NoteSequence.StepType.NOTE) {
			while (chordStart > 0 && chordAdjacent(items.get(chordStart - 1), items.get(chordStart))) {
				chordStart--;
			}
			while (chordEnd + 1 < items.size() && chordAdjacent(items.get(chordEnd), items.get(chordEnd + 1))) {
				chordEnd++;
			}
		}
		int chordSize = chordEnd - chordStart + 1;
		if (chordSize >= 2 && chordSize <= 4) {
			renderCompleteChordAdvance(
				graphics, centerX, y, items, chordStart, chordEnd, placedItemIndex, nextItemIndex, nextIndex, progress
			);
			return;
		}
		SequenceHudToken placed = sequenceHudToken(placedItem, placedIndex);
		SequenceHudToken next = sequenceHudToken(nextItem, nextIndex);
		int placedWidth = minecraft.font.width(placed.text());
		int nextWidth = minecraft.font.width(next.text());
		int placedStartX = centerX - placedWidth / 2;
		int itemGap = nextItemIndex == placedItemIndex + 1
			? sequenceHudItemGap(placedItem, nextItem)
			: 7;
		int nextStartX = placedStartX + placedWidth + itemGap;
		int nextFinalX = centerX - nextWidth / 2;
		int placedFinalX = nextFinalX - placedWidth - itemGap;
		int placedX = Math.round(placedStartX + (placedFinalX - placedStartX) * progress);
		int nextX = Math.round(nextStartX + (nextFinalX - nextStartX) * progress);
		boolean sliding = progress < 1.0F;
		if (sliding) {
			drawSequenceHudToken(graphics, placed, placedX, y, false, false, false);
		}

		int upcomingLimit = sliding ? 3 : 4;
		int upcomingX = nextX;
		for (int offset = 0; offset < upcomingLimit && nextItemIndex + offset < items.size(); offset++) {
			int upcomingItemIndex = nextItemIndex + offset;
			SequenceHudItem upcomingItem = items.get(upcomingItemIndex);
			if (offset > 0 && !chordAdjacent(items.get(upcomingItemIndex - 1), upcomingItem)) {
				break;
			}
			SequenceHudToken upcomingToken = sequenceHudToken(upcomingItem, offset == 0 ? nextIndex : -1);
			boolean current = offset == 0;
			drawSequenceHudToken(graphics, upcomingToken, upcomingX, y, current,
				current && upcomingItemIndex > 0 && chordAdjacent(items.get(upcomingItemIndex - 1), upcomingItem),
				current && upcomingItemIndex + 1 < items.size()
					&& chordAdjacent(upcomingItem, items.get(upcomingItemIndex + 1)));
			upcomingX += minecraft.font.width(upcomingToken.text());
			if (upcomingItemIndex + 1 < items.size()
					&& chordAdjacent(upcomingItem, items.get(upcomingItemIndex + 1))) {
				upcomingX += sequenceHudItemGap(upcomingItem, items.get(upcomingItemIndex + 1));
			}
		}
	}

	private static void renderCompleteChordAdvance(
		GuiGraphicsExtractor graphics,
		int centerX,
		int y,
		List<SequenceHudItem> items,
		int chordStart,
		int chordEnd,
		int placedItemIndex,
		int nextItemIndex,
		int nextPhysicalIndex,
		float progress
	) {
		Minecraft minecraft = Minecraft.getInstance();
		List<Integer> relativeX = new ArrayList<>();
		int cursor = 0;
		for (int itemIndex = chordStart; itemIndex <= chordEnd; itemIndex++) {
			relativeX.add(cursor);
			SequenceHudToken token = sequenceHudToken(items.get(itemIndex), -1);
			cursor += minecraft.font.width(token.text());
			if (itemIndex < chordEnd) {
				cursor += sequenceHudItemGap(items.get(itemIndex), items.get(itemIndex + 1));
			}
		}

		int nextRelativeIndex = nextItemIndex - chordStart;
		SequenceHudToken nextToken = sequenceHudToken(items.get(nextItemIndex), nextPhysicalIndex);
		int finalOffset = centerX - relativeX.get(nextRelativeIndex) - minecraft.font.width(nextToken.text()) / 2;
		int startOffset = finalOffset;
		if (placedItemIndex >= chordStart && placedItemIndex <= chordEnd) {
			int placedRelativeIndex = placedItemIndex - chordStart;
			SequenceHudToken placedToken = sequenceHudToken(items.get(placedItemIndex), -1);
			startOffset = centerX - relativeX.get(placedRelativeIndex) - minecraft.font.width(placedToken.text()) / 2;
		}
		int groupOffset = Math.round(startOffset + (finalOffset - startOffset) * progress);

		for (int itemIndex = chordStart; itemIndex <= chordEnd; itemIndex++) {
			SequenceHudItem item = items.get(itemIndex);
			boolean current = itemIndex == nextItemIndex;
			SequenceHudToken token = sequenceHudToken(item, current ? nextPhysicalIndex : -1);
			drawSequenceHudToken(
				graphics,
				token,
				groupOffset + relativeX.get(itemIndex - chordStart),
				y,
				current,
				current && itemIndex > chordStart,
				current && itemIndex < chordEnd
			);
		}
	}

	private static List<SequenceHudItem> sequenceHudItems(List<BuildStep> sequence) {
		List<SequenceHudItem> items = new ArrayList<>();
		for (int index = 0; index < sequence.size();) {
			BuildStep buildStep = sequence.get(index);
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

	private static int sequenceHudItemIndex(List<SequenceHudItem> items, int physicalIndex) {
		for (int index = 0; index < items.size(); index++) {
			SequenceHudItem item = items.get(index);
			if (physicalIndex >= item.startIndex() && physicalIndex <= item.endIndex()) {
				return index;
			}
		}
		return Math.max(0, items.size() - 1);
	}

	private static SequenceHudToken sequenceHudToken(SequenceHudItem item, int activePhysicalIndex) {
		BuildStep buildStep = item.step();
		NoteSequence.Step step = buildStep.step();
		if (step.type() == NoteSequence.StepType.NOTE) {
			return new SequenceHudToken(NotePitch.name(step.value()) + step.value(), false, false, buildStep.trackNumber());
		}
		if (step.delayCount() == 1 || item.startIndex() == item.endIndex()) {
			return new SequenceHudToken(step.value() + "d", true, false, 0);
		}
		StringBuilder text = new StringBuilder().append(step.delayTotal()).append("d ");
		for (int dot = 0; dot < step.delayCount(); dot++) {
			if (dot > 0) {
				text.append(' ');
			}
			text.append(activePhysicalIndex == item.startIndex() + dot ? '\u2022' : '\u00b7');
		}
		return new SequenceHudToken(text.toString(), true, true, 0);
	}

	private static int sequenceHudTokenGap(SequenceHudToken token) {
		return token.repeater() && !token.grouped() ? 4 : 7;
	}

	private static int sequenceHudItemGap(SequenceHudItem left, SequenceHudItem right) {
		if (chordAdjacent(left, right)) {
			return 5;
		}
		return sequenceHudTokenGap(sequenceHudToken(left, -1));
	}

	private static boolean chordAdjacent(SequenceHudItem left, SequenceHudItem right) {
		return left.step().step().type() == NoteSequence.StepType.NOTE
			&& right.step().step().type() == NoteSequence.StepType.NOTE
			&& left.step().time() == right.step().time();
	}

	private static void drawSequenceHudToken(
		GuiGraphicsExtractor graphics,
		SequenceHudToken token,
		int x,
		int y,
		boolean current,
		boolean chordOnLeft,
		boolean chordOnRight
	) {
		Minecraft minecraft = Minecraft.getInstance();
		int width = minecraft.font.width(token.text());
		if (current) {
			int leftPadding = chordOnLeft ? 2 : 4;
			int rightPadding = chordOnRight ? 2 : 4;
			graphics.fill(x - leftPadding, y - 3, x + width + rightPadding, y + minecraft.font.lineHeight + 3, 0xB8000000);
		} else if (!token.repeater() || token.grouped()) {
			graphics.fill(x - 2, y - 2, x + width + 2, y + minecraft.font.lineHeight + 2, 0x78000000);
		}
		int color = current ? 0xFFFFAA00 : token.repeater() ? 0xFF999999 : 0xFFFFFFFF;
		graphics.text(minecraft.font, token.text(), x, y, color, true);
		if (!token.repeater() && token.trackNumber() > 0) {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x - 4, y - 6);
			graphics.pose().scale(0.55F, 0.55F);
			graphics.text(minecraft.font, Integer.toString(token.trackNumber()), 0, 0, 0xFF55FFFF, true);
			graphics.pose().popMatrix();
		}
	}

	private static List<BuildStep> configuredSequence() {
		try {
			FastNoteblocksConfig config = FastNoteblocksConfig.get();
			List<NoteEvent> events = new ArrayList<>();
			List<FastNoteblocksConfig.SequenceTrack> tracks = config.tracks();
			for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
				FastNoteblocksConfig.SequenceTrack track = tracks.get(trackIndex);
				if (!track.buildEnabled()) {
					continue;
				}
				List<NoteSequence.Step> steps = NoteSequence.parse(track.sequence(), config.activeSequenceDelayScaleQuarters());
				int time = 0;
				for (int localIndex = 0; localIndex < steps.size(); localIndex++) {
					NoteSequence.Step step = steps.get(localIndex);
					if (step.type() == NoteSequence.StepType.NOTE) {
						events.add(new NoteEvent(time, trackIndex + 1, localIndex, step));
					} else {
						time += step.value();
					}
				}
			}
			events.sort(Comparator.comparingInt(NoteEvent::time)
				.thenComparingInt(NoteEvent::trackNumber)
				.thenComparingInt(NoteEvent::localIndex));
			List<BuildStep> merged = new ArrayList<>();
			int currentTime = 0;
			for (NoteEvent event : events) {
				if (event.time() > currentTime) {
					addTimelineDelay(merged, currentTime, event.time() - currentTime);
					currentTime = event.time();
				}
				merged.add(new BuildStep(event.time(), event.trackNumber(), event.step()));
			}
			return List.copyOf(merged);
		} catch (IllegalArgumentException exception) {
			return List.of();
		}
	}

	private static void addTimelineDelay(List<BuildStep> merged, int time, int delay) {
		int remaining = delay;
		while (remaining > 0) {
			int chunk = Math.min(NoteSequence.MAX_GROUPED_DELAY, remaining);
			for (NoteSequence.Step step : NoteSequence.parse(chunk + "d", FastNoteblocksConfig.DEFAULT_SEQUENCE_DELAY_SCALE_QUARTERS)) {
				merged.add(new BuildStep(time, 0, step));
				time += step.value();
			}
			remaining -= chunk;
		}
	}

	private static String buildSequenceSignature(FastNoteblocksConfig config) {
		StringBuilder signature = new StringBuilder().append(config.activeSequenceDelayScaleQuarters());
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

		BlockState state = minecraft.level.getBlockState(pending.blockPos());
		if (!matchesStepBlock(state, pending.targetStep())
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
			if (!matchesStepBlock(state, expected.step())) {
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

	private static boolean matchesStepBlock(BlockState state, NoteSequence.Step step) {
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

	private static int findSequenceItemSlot(LocalPlayer player, NoteSequence.Step expected) {
		int selectedSlot = player.getInventory().getSelectedSlot();
		if (sequenceItemMatches(player, expected, selectedSlot)) {
			return selectedSlot;
		}
		for (int slot = 0; slot < 9; slot++) {
			if (sequenceItemMatches(player, expected, slot)) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean sequenceItemMatches(LocalPlayer player, NoteSequence.Step expected, int slot) {
		return expected.type() == NoteSequence.StepType.NOTE
			? player.getInventory().getItem(slot).is(Items.NOTE_BLOCK)
			: player.getInventory().getItem(slot).is(Items.REPEATER);
	}

	private InteractionResult watchForSequencePlacement(
		net.minecraft.world.entity.player.Player player,
		net.minecraft.world.level.Level level,
		InteractionHand hand,
		BlockHitResult hitResult
	) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		List<BuildStep> sequence = configuredSequence();
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
		boolean matchingItem = expected.type() == NoteSequence.StepType.NOTE
			? player.getItemInHand(hand).is(Items.NOTE_BLOCK)
			: player.getItemInHand(hand).is(Items.REPEATER);
		if (!matchingItem) {
			return InteractionResult.PASS;
		}

		BlockPlaceContext context = new BlockPlaceContext(player, hand, player.getItemInHand(hand), hitResult);
		BlockPos placementPos = context.getClickedPos().immutable();
		if (!matchesStepBlock(level.getBlockState(placementPos), expected)) {
			placementWatches.put(placementPos, new PlacementWatch(expected, PLACEMENT_WATCH_TICKS));
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
			if (matchesStepBlock(minecraft.level.getBlockState(entry.getKey()), watch.step())) {
				applyPlacementStep(minecraft, entry.getKey(), watch.step());
				return true;
			}
			int remaining = watch.ticksRemaining() - 1;
			if (remaining <= 0) {
				return true;
			}
			entry.setValue(new PlacementWatch(watch.step(), remaining));
			return false;
		});
	}

	private void applyPlacementStep(Minecraft minecraft, BlockPos pos, NoteSequence.Step target) {
		int currentValue = stepValue(minecraft.level.getBlockState(pos), target);
		int clicks = target.type() == NoteSequence.StepType.NOTE
			? NotePitch.clicksForward(currentValue, target.value())
			: Math.floorMod(target.value() - currentValue, 4);
		advancePlacementSequenceCursor();
		if (clicks > 0) {
			clickQueue.addLast(PendingClicks.ready(pos, clicks, true, target));
			expectedSteps.put(pos, new ExpectedStep(target, 60, true));
		}
	}

	private void advancePlacementSequenceCursor() {
		List<BuildStep> sequence = configuredSequence();
		if (!sequence.isEmpty()) {
			int previousIndex = Math.floorMod(placementSequenceIndex, sequence.size());
			placementSequenceIndex = (previousIndex + 1) % sequence.size();
			persistPlacementSequencePosition();
			showSequenceAdvance(previousIndex, placementSequenceIndex);
			selectCurrentSequenceItem(Minecraft.getInstance());
		}
	}

	private void selectCurrentSequenceItem(Minecraft minecraft) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		List<BuildStep> sequence = configuredSequence();
		if (!config.placementSequenceEnabled()
			|| !config.autoSelectSequenceBlock()
			|| minecraft.player == null
			|| sequence.isEmpty()) {
			return;
		}
		NoteSequence.Step current = sequence.get(Math.floorMod(placementSequenceIndex, sequence.size())).step();
		int hotbarSlot = findSequenceItemSlot(minecraft.player, current);
		if (hotbarSlot >= 0) {
			minecraft.player.getInventory().setSelectedSlot(hotbarSlot);
		}
	}

	private void resetPlacementSequence() {
		cancelPlacementSequenceWork();
		placementSequenceIndex = 0;
		persistPlacementSequencePosition();
		placementWatches.clear();
		selectCurrentSequenceItem(Minecraft.getInstance());
	}

	private void loadActiveTrack(Minecraft minecraft) {
		cancelPlacementSequenceWork();
		List<BuildStep> sequence = configuredSequence();
		placementSequenceIndex = sequence.isEmpty()
			? 0
			: Math.min(FastNoteblocksConfig.get().placementSequencePosition(), sequence.size() - 1);
		if (placementSequenceIndex != FastNoteblocksConfig.get().placementSequencePosition()) {
			FastNoteblocksConfig.get().setPlacementSequencePosition(placementSequenceIndex);
			sequencePositionSavePending = true;
		}
		placementWatches.clear();
		selectCurrentSequenceItem(minecraft);
		showSequenceHud(minecraft, null);
	}

	private void persistPlacementSequencePosition() {
		FastNoteblocksConfig.get().setPlacementSequencePosition(placementSequenceIndex);
		sequencePositionSavePending = true;
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

	private static boolean isSequencingActive(FastNoteblocksConfig config, List<BuildStep> sequence) {
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

	private record PlacementWatch(NoteSequence.Step step, int ticksRemaining) {
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

	private record BuildStep(int time, int trackNumber, NoteSequence.Step step) {
	}

	private record NoteEvent(int time, int trackNumber, int localIndex, NoteSequence.Step step) {
	}

	private record SequenceHudToken(String text, boolean repeater, boolean grouped, int trackNumber) {
	}

	private record SequenceHudItem(int startIndex, int endIndex, BuildStep step) {
	}

	private enum SequenceHudMode {
		FULL,
		ADVANCE
	}
}
