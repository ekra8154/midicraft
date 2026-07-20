package com.fastnoteblocks.client;

import com.fastnoteblocks.NotePitch;
import com.fastnoteblocks.NoteSequence;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
	public static final NoteBlockOverlay INSTANCE = new NoteBlockOverlay();

	private static final int RESCAN_INTERVAL_TICKS = 10;
	private static final int PLACEMENT_WATCH_TICKS = 12;
	private static final int INTERACTION_ACK_TIMEOUT_TICKS = 40;
	private static final int SEQUENCE_DOUBLE_TAP_TICKS = 7;
	private static final double LABEL_Y = 1.40;
	private static final double REPEATER_LABEL_Y = LABEL_Y - 0.75;
	private static final double MENU_HORIZONTAL_RADIUS = 0.72;
	private static final double MENU_VERTICAL_RADIUS = 0.50;
	private static final char[] FAMILIES = {'A', 'B', 'C', 'D', 'E', 'F', 'G'};
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath("fast-noteblocks", "controls")
	);

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

	private int ticksUntilRescan;
	private ClientLevel lastLevel;
	private BlockPos expandedBlock;
	private Character menuCenterFamily;
	private int placementSequenceIndex;
	private boolean lastPlacementSequenceEnabled;
	private String lastPlacementSequenceText = "";
	private boolean performingAutomatedClick;
	private NoteSequence.StepType inFlightSequenceType;
	private int inFlightSequenceSteps;
	private int pendingSequenceToggleTicks;
	private boolean sequenceControlKeyDown;
	private boolean sequenceGestureConsumed;
	private boolean sequenceResetGesture;
	private boolean leftArrowDown;
	private boolean rightArrowDown;

	private NoteBlockOverlay() {
	}

	public void register() {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastPlacementSequenceText = config.placementSequence();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(this::render);
		UseBlockCallback.EVENT.register(this::watchForSequencePlacement);
	}

	public boolean handleScroll(double verticalAmount) {
		Minecraft minecraft = Minecraft.getInstance();
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
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

		boolean forward = FastNoteblocksConfig.get().invertScrolling() ? verticalAmount < 0.0 : verticalAmount > 0.0;
		NoteSequence.Step target;
		int clicks;
		if (hovered.isRepeater()) {
			int currentDelay = displayedRepeaterDelay(minecraft.level, hovered.blockPos());
			int targetDelay = forward ? currentDelay % 4 + 1 : Math.floorMod(currentDelay - 2, 4) + 1;
			target = NoteSequence.Step.repeater(targetDelay);
			clicks = Math.floorMod(targetDelay - currentDelay, 4);
		} else {
			int currentPitch = displayedPitch(minecraft.level, hovered.blockPos());
			int targetPitch = forward
				? NotePitch.nextInFamily(currentPitch, hovered.family())
				: NotePitch.previousInFamily(currentPitch, hovered.family());
			target = NoteSequence.Step.note(targetPitch);
			clicks = NotePitch.clicksForward(currentPitch, targetPitch);
		}
		if (clicks > 0) {
			clickQueue.addLast(PendingClicks.ready(hovered.blockPos(), clicks, false, target));
			expectedSteps.put(hovered.blockPos(), new ExpectedStep(target, 60, false));
		}
		return true;
	}

	private void tick(Minecraft minecraft) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		while (toggleKey.consumeClick()) {
			config.toggleOverlays();
			FastNoteblocksConfig.save();
			expandedBlock = null;
			menuCenterFamily = null;
			ticksUntilRescan = 0;
			if (minecraft.player != null) {
				minecraft.gui.hud.setOverlayMessage(Component.translatable(
					config.overlaysEnabled() ? "message.fast-noteblocks.enabled" : "message.fast-noteblocks.disabled"
				), true);
			}
		}

		handleSequenceControls(minecraft, config);

		if (!config.placementSequenceEnabled() && lastPlacementSequenceEnabled) {
			cancelPlacementSequenceWork();
		}
		if (!config.placementSequence().equals(lastPlacementSequenceText)) {
			resetPlacementSequence();
		}
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastPlacementSequenceText = config.placementSequence();

		if (minecraft.level != lastLevel) {
			lastLevel = minecraft.level;
			nearbyNoteBlocks.clear();
			nearbyRepeaters.clear();
			clickQueue.clear();
			expectedSteps.clear();
			placementWatches.clear();
			clearInFlightSequenceSteps();
			expandedBlock = null;
			menuCenterFamily = null;
			ticksUntilRescan = 0;
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
			clearInFlightSequenceSteps();
			expandedBlock = null;
			menuCenterFamily = null;
			return;
		}
		if (!config.interactiveControlsEnabled() || !config.overlayMode().includesNotes()) {
			expandedBlock = null;
			menuCenterFamily = null;
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
		}
		performNextClick(minecraft);
	}

	private void handleSequenceControls(Minecraft minecraft, FastNoteblocksConfig config) {
		boolean inGame = minecraft.gui.screen() == null;
		// Drain click counts so operating-system key repeats can never masquerade as extra taps.
		while (placementSequenceKey.consumeClick()) {
		}
		if (!inGame) {
			pendingSequenceToggleTicks = 0;
			sequenceControlKeyDown = placementSequenceKey.isDown();
			sequenceGestureConsumed = sequenceControlKeyDown;
			sequenceResetGesture = sequenceControlKeyDown;
			leftArrowDown = false;
			rightArrowDown = false;
			return;
		}

		boolean keyDownNow = placementSequenceKey.isDown();
		boolean pressedNow = keyDownNow && !sequenceControlKeyDown;
		boolean releasedNow = !keyDownNow && sequenceControlKeyDown;
		if (pressedNow) {
			if (pendingSequenceToggleTicks > 0) {
				pendingSequenceToggleTicks = 0;
				resetPlacementSequence();
				showSequenceHud(minecraft, "message.fast-noteblocks.sequence_reset");
				sequenceGestureConsumed = true;
				sequenceResetGesture = true;
			} else {
				sequenceGestureConsumed = false;
				sequenceResetGesture = false;
			}
		}

		boolean leftDownNow = InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LEFT);
		boolean rightDownNow = InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RIGHT);
		boolean movedThisTick = false;
		if (keyDownNow && !sequenceResetGesture) {
			if (leftDownNow && !leftArrowDown) {
				pendingSequenceToggleTicks = 0;
				movePlacementSequence(-1);
				showSequenceHud(minecraft, "message.fast-noteblocks.sequence_previous");
				sequenceGestureConsumed = true;
				movedThisTick = true;
			}
			if (!movedThisTick && rightDownNow && !rightArrowDown) {
				pendingSequenceToggleTicks = 0;
				movePlacementSequence(1);
				showSequenceHud(minecraft, "message.fast-noteblocks.sequence_next");
				sequenceGestureConsumed = true;
			}
		}
		if (releasedNow && !sequenceGestureConsumed) {
			pendingSequenceToggleTicks = SEQUENCE_DOUBLE_TAP_TICKS;
		}
		if (releasedNow) {
			sequenceGestureConsumed = false;
			sequenceResetGesture = false;
		}
		sequenceControlKeyDown = keyDownNow;
		leftArrowDown = leftDownNow;
		rightArrowDown = rightDownNow;

		if (pendingSequenceToggleTicks > 0 && --pendingSequenceToggleTicks == 0) {
			config.setPlacementSequenceEnabled(!config.placementSequenceEnabled());
			FastNoteblocksConfig.save();
			if (!config.placementSequenceEnabled()) {
				cancelPlacementSequenceWork();
			}
			showSequenceHud(minecraft, config.placementSequenceEnabled()
				? "message.fast-noteblocks.sequence_resumed"
				: "message.fast-noteblocks.sequence_paused");
		}
	}

	private void movePlacementSequence(int amount) {
		List<NoteSequence.Step> sequence = configuredSequence();
		cancelPlacementSequenceWork();
		if (!sequence.isEmpty()) {
			placementSequenceIndex = Math.floorMod(placementSequenceIndex + amount, sequence.size());
		}
	}

	private void showSequenceHud(Minecraft minecraft, String actionKey) {
		if (minecraft.player == null) {
			return;
		}
		List<NoteSequence.Step> sequence = configuredSequence();
		if (sequence.isEmpty()) {
			minecraft.gui.hud.setOverlayMessage(Component.translatable(
				"message.fast-noteblocks.sequence_hud_empty", Component.translatable(actionKey)
			), true);
			return;
		}
		int index = Math.floorMod(placementSequenceIndex, sequence.size());
		NoteSequence.Step current = sequence.get(index);
		NoteSequence.Step after = sequence.get((index + 1) % sequence.size());
		minecraft.gui.hud.setOverlayMessage(Component.translatable(
			"message.fast-noteblocks.sequence_hud",
			Component.translatable(actionKey),
			index + 1,
			sequence.size(),
			describeStep(current),
			describeStep(after)
		), true);
	}

	private static Component describeStep(NoteSequence.Step step) {
		return step.type() == NoteSequence.StepType.NOTE
			? Component.translatable("message.fast-noteblocks.sequence_note", step.value(), NotePitch.name(step.value()))
			: Component.translatable("message.fast-noteblocks.sequence_repeater", step.value());
	}

	private static List<NoteSequence.Step> configuredSequence() {
		try {
			return FastNoteblocksConfig.parsePlacementSequence(FastNoteblocksConfig.get().placementSequence());
		} catch (IllegalArgumentException exception) {
			return List.of();
		}
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
		boolean placementSequenceWork = clickQueue.stream().anyMatch(
			pending -> pending.blockPos().equals(pos) && pending.placementSequence()
		);
		clickQueue.removeIf(pending -> pending.blockPos().equals(pos));
		ExpectedStep removed = expectedSteps.remove(pos);
		if (placementSequenceWork || removed != null && removed.placementSequence()) {
			finishInFlightSequenceStep();
		}
	}

	private void updateExpectations(ClientLevel level) {
		expectedSteps.entrySet().removeIf(entry -> {
			BlockState state = level.getBlockState(entry.getKey());
			ExpectedStep expected = entry.getValue();
			if (!matchesStepBlock(state, expected.step())) {
				clickQueue.removeIf(pending -> pending.blockPos().equals(entry.getKey()));
				if (expected.placementSequence()) {
					finishInFlightSequenceStep();
				}
				return true;
			}
			if (stepValue(state, expected.step()) == expected.step().value()) {
				clickQueue.removeIf(pending -> pending.blockPos().equals(entry.getKey()));
				if (expected.placementSequence()) {
					finishInFlightSequenceStep();
				}
				return true;
			}
			boolean interactionQueued = clickQueue.stream().anyMatch(
				pending -> pending.blockPos().equals(entry.getKey())
			);
			int remaining = interactionQueued ? expected.ticksRemaining() : expected.ticksRemaining() - 1;
			if (remaining <= 0) {
				if (expected.placementSequence()) {
					finishInFlightSequenceStep();
				}
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

	private InteractionResult watchForSequencePlacement(
		net.minecraft.world.entity.player.Player player,
		net.minecraft.world.level.Level level,
		InteractionHand hand,
		BlockHitResult hitResult
	) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		List<NoteSequence.Step> sequence = configuredSequence();
		if (performingAutomatedClick
			|| !level.isClientSide()
			|| !config.modEnabled()
			|| !config.placementSequenceEnabled()
			|| sequence.isEmpty()
			|| !placementWatches.isEmpty()) {
			return InteractionResult.PASS;
		}
		NoteSequence.Step expected = sequence.get(Math.floorMod(placementSequenceIndex, sequence.size()));
		if (inFlightSequenceSteps > 0 && expected.type() != inFlightSequenceType) {
			return InteractionResult.PASS;
		}
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
			clearInFlightSequenceSteps();
			return;
		}
		if (!FastNoteblocksConfig.get().modEnabled() || !FastNoteblocksConfig.get().placementSequenceEnabled()) {
			placementWatches.clear();
			clearInFlightSequenceSteps();
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
			startInFlightSequenceStep(target.type());
			clickQueue.addLast(PendingClicks.ready(pos, clicks, true, target));
			expectedSteps.put(pos, new ExpectedStep(target, 60, true));
		}
	}

	private void advancePlacementSequenceCursor() {
		List<NoteSequence.Step> sequence = configuredSequence();
		if (!sequence.isEmpty()) {
			placementSequenceIndex = (Math.floorMod(placementSequenceIndex, sequence.size()) + 1) % sequence.size();
		}
	}

	private void startInFlightSequenceStep(NoteSequence.StepType type) {
		if (inFlightSequenceSteps == 0) {
			inFlightSequenceType = type;
		}
		inFlightSequenceSteps++;
	}

	private void finishInFlightSequenceStep() {
		if (inFlightSequenceSteps > 0 && --inFlightSequenceSteps == 0) {
			inFlightSequenceType = null;
		}
	}

	private void clearInFlightSequenceSteps() {
		inFlightSequenceSteps = 0;
		inFlightSequenceType = null;
	}

	private void resetPlacementSequence() {
		cancelPlacementSequenceWork();
		placementSequenceIndex = 0;
		placementWatches.clear();
	}

	private void cancelPlacementSequenceWork() {
		clickQueue.removeIf(PendingClicks::placementSequence);
		expectedSteps.entrySet().removeIf(entry -> entry.getValue().placementSequence());
		placementWatches.clear();
		clearInFlightSequenceSteps();
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
				Vec3 right = labelRight(cameraPos, Vec3.atCenterOf(pos));
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
						0.5 + right.x * offset.right(), LABEL_Y - 0.5 + offset.up(), 0.5 + right.z * offset.right()
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
				boolean isHovered = hovered != null && hovered.isRepeater() && hovered.blockPos().equals(pos);
				if (!config.nearbyPreviewsEnabled() && !isHovered) {
					continue;
				}
				boolean inRange = minecraft.player.isWithinBlockInteractionRange(pos, 0.0);
				Component text = Component.literal(Integer.toString(displayedRepeaterDelay(minecraft.level, pos)));
				if (isHovered) {
					text = text.copy().withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD, ChatFormatting.UNDERLINE);
				} else {
					text = text.copy().withStyle(inRange ? ChatFormatting.GOLD : ChatFormatting.GRAY, ChatFormatting.BOLD);
				}
				poseStack.pushPose();
				poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
				context.submitNodeCollector().submitNameTag(
					poseStack, new Vec3(0.5, REPEATER_LABEL_Y - 0.5, 0.5), 0, text, true,
					LightCoordsUtil.FULL_BRIGHT, cameraState
				);
				poseStack.popPose();
			}
		}
	}

	private HoveredLabel findHoveredLabel(Minecraft minecraft) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		boolean interactiveControlsEnabled = config.interactiveControlsEnabled();
		if (!interactiveControlsEnabled || !config.overlayMode().includesNotes()) {
			expandedBlock = null;
			menuCenterFamily = null;
		}
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 origin = camera.position();
		Vec3 direction = new Vec3(camera.forwardVector()).normalize();

		if (interactiveControlsEnabled
			&& expandedBlock != null
			&& minecraft.level.getBlockState(expandedBlock).is(Blocks.NOTE_BLOCK)) {
			LabelHit expandedHit = hitLabelOnBlock(minecraft, expandedBlock, true, origin, direction);
			if (expandedHit != null) {
				return expandedHit.label();
			}
			menuCenterFamily = NotePitch.family(displayedPitch(minecraft.level, expandedBlock));
			if (isInsideMenuEnvelope(expandedBlock, origin, direction)) {
				return null;
			}
		}

		LabelHit closest = null;
		if (config.overlayMode().includesNotes()) {
			for (BlockPos pos : nearbyNoteBlocks) {
				if (pos.equals(expandedBlock) || !minecraft.level.getBlockState(pos).is(Blocks.NOTE_BLOCK)) {
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
				if (!minecraft.level.getBlockState(pos).is(Blocks.REPEATER)) {
					continue;
				}
				LabelHit hit = hitRepeaterLabel(minecraft, pos, origin, direction);
				if (hit != null && (closest == null || hit.distance() < closest.distance())) {
					closest = hit;
				}
			}
		}

		if (closest != null) {
			if (interactiveControlsEnabled && !closest.label().isRepeater()) {
				expandedBlock = closest.label().blockPos();
				menuCenterFamily = NotePitch.family(displayedPitch(minecraft.level, expandedBlock));
			} else {
				expandedBlock = null;
				menuCenterFamily = null;
			}
			return closest.label();
		}
		expandedBlock = null;
		menuCenterFamily = null;
		return null;
	}

	private LabelHit hitLabelOnBlock(Minecraft minecraft, BlockPos pos, boolean expanded, Vec3 origin, Vec3 direction) {
		int pitch = displayedPitch(minecraft.level, pos);
		boolean inRange = minecraft.player.isWithinBlockInteractionRange(pos, 0.0);
		Vec3 right = labelRight(origin, Vec3.atCenterOf(pos));
		LabelHit closest = null;
		char layoutCenterFamily = expanded && menuCenterFamily != null
			? menuCenterFamily
			: NotePitch.family(pitch);

		for (char family : FAMILIES) {
			if (!expanded && family != NotePitch.family(pitch)) {
				continue;
			}
			LabelOffset offset = labelOffset(layoutCenterFamily, family);
			Vec3 center = new Vec3(
				pos.getX() + 0.5 + right.x * offset.right(),
				pos.getY() + LABEL_Y + offset.up(),
				pos.getZ() + 0.5 + right.z * offset.right()
			);
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
				closest = new LabelHit(new HoveredLabel(pos, family), distance);
			}
		}
		return closest;
	}

	private LabelHit hitRepeaterLabel(Minecraft minecraft, BlockPos pos, Vec3 origin, Vec3 direction) {
		Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + REPEATER_LABEL_Y, pos.getZ() + 0.5);
		Vec3 normal = origin.subtract(center).normalize();
		double denominator = direction.dot(normal);
		if (Math.abs(denominator) < 1.0E-5) {
			return null;
		}
		double distance = center.subtract(origin).dot(normal) / denominator;
		if (distance <= 0.0) {
			return null;
		}
		Vec3 right = labelRight(origin, Vec3.atCenterOf(pos));
		Vec3 up = normal.cross(right).normalize();
		Vec3 hitOffset = origin.add(direction.scale(distance)).subtract(center);
		String text = Integer.toString(displayedRepeaterDelay(minecraft.level, pos));
		double halfWidth = minecraft.font.width(text) * 0.0125 + 0.04;
		return Math.abs(hitOffset.dot(right)) <= halfWidth && Math.abs(hitOffset.dot(up)) <= 0.15
			? new LabelHit(new HoveredLabel(pos, null), distance)
			: null;
	}

	private boolean isInsideMenuEnvelope(BlockPos pos, Vec3 origin, Vec3 direction) {
		Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + LABEL_Y, pos.getZ() + 0.5);
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
		return Math.abs(offset.dot(right)) <= 1.05 && Math.abs(offset.dot(up)) <= 0.78;
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

	private static Vec3 labelRight(Vec3 cameraPos, Vec3 blockCenter) {
		Vec3 toCamera = cameraPos.subtract(blockCenter);
		Vec3 right = new Vec3(-toCamera.z, 0.0, toCamera.x);
		return right.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
	}

	private static boolean isReady(Minecraft minecraft) {
		return minecraft.level != null && minecraft.player != null && minecraft.gameMode != null;
	}

	private static boolean overlaysActive(FastNoteblocksConfig config) {
		return config.modEnabled() && config.overlaysEnabled();
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

	private record HoveredLabel(BlockPos blockPos, Character family) {
		private boolean isRepeater() {
			return family == null;
		}
	}

	private record LabelHit(HoveredLabel label, double distance) {
	}

	private record LabelOffset(double right, double up) {
	}
}
