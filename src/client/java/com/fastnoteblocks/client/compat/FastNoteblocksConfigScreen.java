package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.OverlayMode;
import com.fastnoteblocks.client.FastNoteblocksConfig.RepeaterControlStyle;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequencingEditProtection;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class FastNoteblocksConfigScreen {
	private FastNoteblocksConfigScreen() {
	}

	public static Screen create(Screen parent) {
		FastNoteblocksConfig config = FastNoteblocksConfig.get();
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.translatable("title.fast-noteblocks.config"));
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.fast-noteblocks.general"));
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.mod_enabled"), config.modEnabled())
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.mod_enabled"))
			.setSaveConsumer(config::setModEnabled)
			.build());
		general.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.overlays"), OverlayMode.class, config.overlayMode())
			.setDefaultValue(OverlayMode.NOTES_ONLY)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.overlays." + ((OverlayMode) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.overlays"))
			.setSaveConsumer(config::setOverlayMode)
			.build());
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.nearby_previews"), config.nearbyPreviewsEnabled())
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.nearby_previews"))
			.setSaveConsumer(config::setNearbyPreviewsEnabled)
			.build());
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.interactive_controls"), config.interactiveControlsEnabled())
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.interactive_controls"))
			.setSaveConsumer(config::setInteractiveControlsEnabled)
			.build());
		general.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.radial_focus_delay"),
				config.radialFocusDelayTicks(),
				FastNoteblocksConfig.MIN_RADIAL_FOCUS_DELAY_TICKS,
				FastNoteblocksConfig.MAX_RADIAL_FOCUS_DELAY_TICKS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_RADIAL_FOCUS_DELAY_TICKS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.radial_focus_delay"))
			.setSaveConsumer(config::setRadialFocusDelayTicks)
			.build());
		general.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.repeater_control_style"),
				RepeaterControlStyle.class,
				config.repeaterControlStyle())
			.setDefaultValue(RepeaterControlStyle.RADIAL_SELECT)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.repeater_control_style."
					+ ((RepeaterControlStyle) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.repeater_control_style"))
			.setSaveConsumer(config::setRepeaterControlStyle)
			.build());
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.invert_scroll"), config.invertScrolling())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.invert_scroll"))
			.setSaveConsumer(config::setInvertScrolling)
			.build());
		general.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.view_distance"),
				config.viewDistance(),
				FastNoteblocksConfig.MIN_VIEW_DISTANCE,
				FastNoteblocksConfig.MAX_VIEW_DISTANCE)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_VIEW_DISTANCE)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.view_distance"))
			.setSaveConsumer(config::setViewDistance)
			.build());
		general.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.interaction_delay"),
				config.interactionDelayTicks(),
				FastNoteblocksConfig.MIN_INTERACTION_DELAY_TICKS,
				FastNoteblocksConfig.MAX_INTERACTION_DELAY_TICKS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_INTERACTION_DELAY_TICKS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.interaction_delay"))
			.setSaveConsumer(config::setInteractionDelayTicks)
			.build());
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.wait_for_ack"), config.waitForServerAcknowledgement())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.wait_for_ack"))
			.setSaveConsumer(config::setWaitForServerAcknowledgement)
			.build());
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.require_line_of_sight"), config.requireLineOfSight())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.require_line_of_sight"))
			.setSaveConsumer(config::setRequireLineOfSight)
			.build());

		ConfigCategory placement = builder.getOrCreateCategory(Component.translatable("category.fast-noteblocks.placement"));
		placement.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.sequencing_edit_protection"),
				SequencingEditProtection.class,
				config.sequencingEditProtection())
			.setDefaultValue(SequencingEditProtection.RADIALS_AND_INTERACTIONS)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.sequencing_edit_protection."
					+ ((SequencingEditProtection) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.sequencing_edit_protection"))
			.setSaveConsumer(config::setSequencingEditProtection)
			.build());
		placement.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.sequence_enabled"), config.placementSequenceEnabled())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.sequence_enabled"))
			.setSaveConsumer(config::setPlacementSequenceEnabled)
			.build());
		placement.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.auto_select_sequence_block"),
				config.autoSelectSequenceBlock())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.auto_select_sequence_block"))
			.setSaveConsumer(config::setAutoSelectSequenceBlock)
			.build());
		placement.addEntry(entries.startTextDescription(
			Component.translatable("guide.fast-noteblocks.sequence")
		).build());
		placement.addEntry(entries.startStrField(
				Component.translatable("option.fast-noteblocks.sequence"), config.placementSequence())
			.setDefaultValue("")
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.sequence"))
			.setErrorSupplier(FastNoteblocksConfig::validatePlacementSequence)
			.setSaveConsumer(config::setPlacementSequence)
			.build());

		builder.setSavingRunnable(FastNoteblocksConfig::save);
		return builder.build();
	}
}
