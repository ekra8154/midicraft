package com.fastnoteblocks.client;

import com.fastnoteblocks.NotePitch;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class NoteBlockOverlay {
	public static final NoteBlockOverlay INSTANCE = new NoteBlockOverlay();

	private static final int RESCAN_INTERVAL_TICKS = 10;
	private static final int PLACEMENT_WATCH_TICKS = 12;
	private static final int INTERACTION_ACK_TIMEOUT_TICKS = 40;
	private static final double LABEL_Y = 1.40;
	private static final double MENU_HORIZONTAL_RADIUS = 0.72;
	private static final double MENU_VERTICAL_RADIUS = 0.50;
	private static final char[] FAMILIES = {'A', 'B', 'C', 'D', 'E', 'F', 'G'};
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath("fast-noteblocks", "controls")
	);

	private final List<BlockPos> nearbyNoteBlocks = new ArrayList<>();
	private final Deque<PendingClicks> clickQueue = new ArrayDeque<>();
	private final Map<BlockPos, ExpectedPitch> expectedPitches = new HashMap<>();
	private final Map<BlockPos, Integer> placementWatches = new HashMap<>();
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

	private NoteBlockOverlay() {
	}

	public void register() {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		lastPlacementSequenceEnabled = config.placementSequenceEnabled();
		lastPlacementSequenceText = config.placementSequence();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(this::render);
		UseBlockCallback.EVENT.register(this::watchForNoteBlockPlacement);
	}

	public boolean handleScroll(double verticalAmount) {
		Minecraft minecraft = Minecraft.getInstance();
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		if (!noteBlockOverlaysActive(config)
			|| !config.radialControlsEnabled()
			|| verticalAmount == 0.0
			|| minecraft.gui.screen() != null
			|| !isReady(minecraft)) {
			return false;
		}

		HoveredLabel hovered = findHoveredLabel(minecraft);
		if (hovered == null || !minecraft.player.isWithinBlockInteractionRange(hovered.blockPos(), 0.0)) {
			return false;
		}

		int currentPitch = displayedPitch(minecraft.level, hovered.blockPos());
		boolean forward = FastNoteblocksConfig.get().invertScrolling() ? verticalAmount < 0.0 : verticalAmount > 0.0;
		int targetPitch = forward
			? NotePitch.nextInFamily(currentPitch, hovered.family())
			: NotePitch.previousInFamily(currentPitch, hovered.family());
		int clicks = NotePitch.clicksForward(currentPitch, targetPitch);
		if (clicks > 0) {
			clickQueue.addLast(PendingClicks.ready(hovered.blockPos(), clicks, false));
			expectedPitches.put(hovered.blockPos(), new ExpectedPitch(targetPitch, 60, false));
		}
		return true;
	}

	private void tick(Minecraft minecraft) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		while (toggleKey.consumeClick()) {
			config.setOverlaysEnabled(!config.overlaysEnabled());
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

		while (placementSequenceKey.consumeClick()) {
			config.setPlacementSequenceEnabled(!config.placementSequenceEnabled());
			FastNoteblocksConfig.save();
			if (config.placementSequenceEnabled()) {
				resetPlacementSequence();
			}
			if (minecraft.player != null) {
				minecraft.gui.hud.setOverlayMessage(Component.translatable(
					config.placementSequenceEnabled()
						? "message.fast-noteblocks.sequence_enabled"
						: "message.fast-noteblocks.sequence_disabled"
				), true);
			}
		}

		if (config.placementSequenceEnabled() && !lastPlacementSequenceEnabled) {
			resetPlacementSequence();
		} else if (!config.placementSequenceEnabled() && lastPlacementSequenceEnabled) {
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
			clickQueue.clear();
			expectedPitches.clear();
			placementWatches.clear();
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
			clickQueue.clear();
			expectedPitches.clear();
			placementWatches.clear();
			expandedBlock = null;
			menuCenterFamily = null;
			return;
		}
		if (!config.radialControlsEnabled()) {
			expandedBlock = null;
			menuCenterFamily = null;
		}

		updatePlacementWatches(minecraft);
		updateExpectations(minecraft.level);
		if (noteBlockOverlaysActive(config) && --ticksUntilRescan <= 0) {
			rescan(minecraft);
			ticksUntilRescan = RESCAN_INTERVAL_TICKS;
		} else if (!noteBlockOverlaysActive(config)) {
			nearbyNoteBlocks.clear();
			expandedBlock = null;
			menuCenterFamily = null;
		}
		performNextClick(minecraft);
	}

	private void rescan(Minecraft minecraft) {
		nearbyNoteBlocks.clear();
		int searchRadius = FastNoteblocksConfig.get().viewDistance();
		BlockPos origin = minecraft.player.blockPosition();
		BlockPos min = origin.offset(-searchRadius, -searchRadius, -searchRadius);
		BlockPos max = origin.offset(searchRadius, searchRadius, searchRadius);
		double radiusSquared = searchRadius * searchRadius;

		for (BlockPos mutablePos : BlockPos.betweenClosed(min, max)) {
			if (mutablePos.distToCenterSqr(minecraft.player.position()) <= radiusSquared
				&& minecraft.level.getBlockState(mutablePos).is(Blocks.NOTE_BLOCK)) {
				nearbyNoteBlocks.add(mutablePos.immutable());
			}
		}
	}

	private void performNextClick(Minecraft minecraft) {
		PendingClicks pending = clickQueue.peekFirst();
		if (pending == null) {
			return;
		}

		if (!minecraft.level.getBlockState(pending.blockPos()).is(Blocks.NOTE_BLOCK)
			|| !minecraft.player.isWithinBlockInteractionRange(pending.blockPos(), 0.0)) {
			cancelWorkForBlock(pending.blockPos());
			return;
		}

		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		int actualPitch = minecraft.level.getBlockState(pending.blockPos()).getValue(NoteBlock.NOTE);
		if (pending.expectedPitchAfterClick() >= 0) {
			if (!config.waitForServerAcknowledgement()) {
				if (pending.remaining() <= 0) {
					clickQueue.removeFirst();
				} else {
					replaceFirstPending(pending.withoutAcknowledgement(config.interactionDelayTicks()));
				}
				return;
			}
			if (actualPitch == pending.expectedPitchAfterClick()) {
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
			replaceFirstPending(pending.afterClick((actualPitch + 1) % NotePitch.PITCH_COUNT));
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
		if (result.getDirection() == net.minecraft.core.Direction.UP
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
		expectedPitches.remove(pos);
	}

	private void updateExpectations(ClientLevel level) {
		expectedPitches.entrySet().removeIf(entry -> {
			BlockState state = level.getBlockState(entry.getKey());
			if (!state.is(Blocks.NOTE_BLOCK) || state.getValue(NoteBlock.NOTE) == entry.getValue().pitch()) {
				return true;
			}
			int remaining = entry.getValue().ticksRemaining() - 1;
			if (remaining <= 0) {
				return true;
			}
			entry.setValue(new ExpectedPitch(entry.getValue().pitch(), remaining, entry.getValue().placementSequence()));
			return false;
		});
	}

	private InteractionResult watchForNoteBlockPlacement(
		net.minecraft.world.entity.player.Player player,
		net.minecraft.world.level.Level level,
		InteractionHand hand,
		BlockHitResult hitResult
	) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		List<Integer> configuredSequence;
		try {
			configuredSequence = FastNoteblocksConfig.parsePlacementSequence(config.placementSequence());
		} catch (IllegalArgumentException exception) {
			configuredSequence = List.of();
		}
		if (performingAutomatedClick
			|| !level.isClientSide()
			|| !config.modEnabled()
			|| !config.placementSequenceEnabled()
			|| configuredSequence.isEmpty()
			|| !player.getItemInHand(hand).is(Items.NOTE_BLOCK)) {
			return InteractionResult.PASS;
		}

		BlockPlaceContext context = new BlockPlaceContext(player, hand, player.getItemInHand(hand), hitResult);
		BlockPos placementPos = context.getClickedPos().immutable();
		if (!level.getBlockState(placementPos).is(Blocks.NOTE_BLOCK)) {
			placementWatches.put(placementPos, PLACEMENT_WATCH_TICKS);
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
			if (minecraft.level.getBlockState(entry.getKey()).is(Blocks.NOTE_BLOCK)) {
				applyNextPlacementPitch(minecraft, entry.getKey());
				return true;
			}
			int remaining = entry.getValue() - 1;
			if (remaining <= 0) {
				return true;
			}
			entry.setValue(remaining);
			return false;
		});
	}

	private void applyNextPlacementPitch(Minecraft minecraft, BlockPos pos) {
		List<Integer> sequence;
		try {
			sequence = FastNoteblocksConfig.parsePlacementSequence(FastNoteblocksConfig.get().placementSequence());
		} catch (IllegalArgumentException exception) {
			return;
		}
		if (sequence.isEmpty()) {
			return;
		}

		int targetPitch = sequence.get(placementSequenceIndex % sequence.size());
		placementSequenceIndex = (placementSequenceIndex + 1) % sequence.size();
		int currentPitch = minecraft.level.getBlockState(pos).getValue(NoteBlock.NOTE);
		int clicks = NotePitch.clicksForward(currentPitch, targetPitch);
		if (clicks > 0) {
			clickQueue.addLast(PendingClicks.ready(pos, clicks, true));
			expectedPitches.put(pos, new ExpectedPitch(targetPitch, 60, true));
		}
	}

	private void resetPlacementSequence() {
		cancelPlacementSequenceWork();
		placementSequenceIndex = 0;
		placementWatches.clear();
	}

	private void cancelPlacementSequenceWork() {
		clickQueue.removeIf(PendingClicks::placementSequence);
		expectedPitches.entrySet().removeIf(entry -> entry.getValue().placementSequence());
		placementWatches.clear();
	}

	private void render(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		if (!noteBlockOverlaysActive(config) || !isReady(minecraft) || nearbyNoteBlocks.isEmpty()) {
			return;
		}

		CameraRenderState cameraState = context.levelState().cameraRenderState;
		Vec3 cameraPos = cameraState.pos;
		PoseStack poseStack = context.poseStack();
		HoveredLabel hovered = findHoveredLabel(minecraft);

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
			boolean expanded = config.radialControlsEnabled() && pos.equals(expandedBlock);
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
				boolean isHovered = hovered != null && hovered.blockPos().equals(pos) && hovered.family() == family;
				Component text = labelText(pitch, family, inRange, isHovered);
				context.submitNodeCollector().submitNameTag(
					poseStack, attachment, 0, text, true, LightCoordsUtil.FULL_BRIGHT, cameraState
				);
			}
			poseStack.popPose();
		}
	}

	private HoveredLabel findHoveredLabel(Minecraft minecraft) {
		boolean radialControlsEnabled = FastNoteblocksConfig.get().radialControlsEnabled();
		if (!radialControlsEnabled) {
			expandedBlock = null;
			menuCenterFamily = null;
		}
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 origin = camera.position();
		Vec3 direction = new Vec3(camera.forwardVector()).normalize();

		if (radialControlsEnabled
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
		for (BlockPos pos : nearbyNoteBlocks) {
			if (pos.equals(expandedBlock) || !minecraft.level.getBlockState(pos).is(Blocks.NOTE_BLOCK)) {
				continue;
			}
			LabelHit hit = hitLabelOnBlock(minecraft, pos, false, origin, direction);
			if (hit != null && (closest == null || hit.distance() < closest.distance())) {
				closest = hit;
			}
		}

		if (closest != null) {
			if (radialControlsEnabled) {
				expandedBlock = closest.label().blockPos();
				menuCenterFamily = NotePitch.family(displayedPitch(minecraft.level, expandedBlock));
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
		ExpectedPitch expected = expectedPitches.get(pos);
		if (expected != null) {
			return expected.pitch();
		}
		BlockState state = level.getBlockState(pos);
		return state.is(Blocks.NOTE_BLOCK) ? state.getValue(NoteBlock.NOTE) : 0;
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

	private static boolean noteBlockOverlaysActive(FastNoteblocksConfig config) {
		return config.modEnabled() && config.overlaysEnabled() && config.noteBlockOverlaysEnabled();
	}

	private record PendingClicks(
		BlockPos blockPos,
		int remaining,
		boolean placementSequence,
		int expectedPitchAfterClick,
		int ackTicksRemaining,
		int cooldownTicks
	) {
		private static PendingClicks ready(BlockPos blockPos, int remaining, boolean placementSequence) {
			return new PendingClicks(blockPos, remaining, placementSequence, -1, 0, 0);
		}

		private PendingClicks afterClick(int expectedPitch) {
			return new PendingClicks(
				blockPos, remaining - 1, placementSequence, expectedPitch, INTERACTION_ACK_TIMEOUT_TICKS, 0
			);
		}

		private PendingClicks afterAcknowledgement(int cooldownTicks) {
			return new PendingClicks(blockPos, remaining, placementSequence, -1, 0, cooldownTicks);
		}

		private PendingClicks withoutAcknowledgement(int cooldownTicks) {
			return new PendingClicks(blockPos, remaining, placementSequence, -1, 0, cooldownTicks);
		}

		private PendingClicks afterUnconfirmedClick(int cooldownTicks) {
			return new PendingClicks(blockPos, remaining - 1, placementSequence, -1, 0, cooldownTicks);
		}

		private PendingClicks waitOneTick() {
			return new PendingClicks(
				blockPos, remaining, placementSequence, expectedPitchAfterClick, ackTicksRemaining - 1, cooldownTicks
			);
		}

		private PendingClicks coolDownOneTick() {
			return new PendingClicks(
				blockPos, remaining, placementSequence, expectedPitchAfterClick, ackTicksRemaining, cooldownTicks - 1
			);
		}
	}

	private record ExpectedPitch(int pitch, int ticksRemaining, boolean placementSequence) {
	}

	private record HoveredLabel(BlockPos blockPos, char family) {
	}

	private record LabelHit(HoveredLabel label, double distance) {
	}

	private record LabelOffset(double right, double up) {
	}
}
