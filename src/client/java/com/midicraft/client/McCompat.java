package com.midicraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Every call into the game whose shape differs between the Minecraft versions this mod is built for.
 *
 * <p>Each version worktree carries its own copy of this file and shares the rest of the source, so a
 * change cherry-picked from one version to another conflicts here or nowhere -- and here it is a
 * line. Before this, opening a screen alone was seventy call sites across twenty files that 26.1 and
 * 26.2 spell differently, and every cherry-pick that touched one of them stopped to be mended.</p>
 *
 * <p>So the rule: a call that moves between versions comes here, and its call sites go through it.
 * Only the shape lives here, never a decision -- what the mod does stays where it is done.</p>
 *
 * <p>This copy is the 26.3 one.</p>
 */
public final class McCompat {

	private McCompat() {
	}

	/** The screen in front of the world, or null when there is only the world. */
	public static Screen screen() {
		return Minecraft.getInstance().gui.screen();
	}

	/** Puts a screen in front of the world, or with null, takes it away. */
	public static void setScreen(Screen screen) {
		Minecraft.getInstance().gui.setScreen(screen);
	}

	/** The line of text above the hotbar, where the game says what a block just did. */
	public static void overlayMessage(Component message, boolean animate) {
		Minecraft.getInstance().gui.hud.setOverlayMessage(message, animate);
	}

	public static Camera mainCamera() {
		return Minecraft.getInstance().gameRenderer.mainCamera();
	}

	/**
	 * A label hung in the world over a block, drawn the way the game draws a name tag.
	 *
	 * @param pose translated to {@code block}, relative to the camera
	 * @param attachment where on that block the label hangs, in the block's own coordinates
	 */
	public static void submitLabel(SubmitNodeCollector collector, PoseStack pose, BlockPos block,
		Vec3 attachment, Component text, CameraRenderState camera) {
		collector.submitNameTag(pose, attachment, 0, text, true, LightCoordsUtil.FULL_BRIGHT, camera);
	}

	public static Item whiteWool() {
		return Items.WOOL.white();
	}

	/** Waxed copper at an age from 0, fresh, to 3, oxidized. */
	public static Item waxedCopper(int age) {
		var waxed = Items.COPPER_BLOCK.waxed();
		return switch (age) {
			case 0 -> waxed.unaffected();
			case 1 -> waxed.exposed();
			case 2 -> waxed.weathered();
			default -> waxed.oxidized();
		};
	}

	public static Item waxedCopperTrapdoor() {
		return Items.COPPER_TRAPDOOR.waxed().unaffected();
	}

	public static Item waxedCopperBulb() {
		return Items.COPPER_BULB.waxed().unaffected();
	}
}
