package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiInstrumentSource;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiQuantizeGrid;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiRangeFit;
import com.fastnoteblocks.client.FastNoteblocksConfig.MidiTempoFit;
import com.fastnoteblocks.client.FastNoteblocksConfig.OverlayMode;
import com.fastnoteblocks.client.FastNoteblocksConfig.RepeaterControlStyle;
import com.fastnoteblocks.client.FastNoteblocksConfig.SequencingEditProtection;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
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
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.debug_commands"),
				config.debugCommandsEnabled())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.debug_commands"))
			.setSaveConsumer(config::setDebugCommandsEnabled)
			.build());

		SubCategoryBuilder controlTuning = entries.startSubCategory(
				Component.translatable("category.fast-noteblocks.control_tuning"))
			.setExpanded(false);
		controlTuning.add(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.radial_focus_delay"),
				config.radialFocusDelayTicks(),
				FastNoteblocksConfig.MIN_RADIAL_FOCUS_DELAY_TICKS,
				FastNoteblocksConfig.MAX_RADIAL_FOCUS_DELAY_TICKS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_RADIAL_FOCUS_DELAY_TICKS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.radial_focus_delay"))
			.setSaveConsumer(config::setRadialFocusDelayTicks)
			.build());
		controlTuning.add(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.repeater_control_style"),
				RepeaterControlStyle.class,
				config.repeaterControlStyle())
			.setDefaultValue(RepeaterControlStyle.SCROLL)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.repeater_control_style."
					+ ((RepeaterControlStyle) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.repeater_control_style"))
			.setSaveConsumer(config::setRepeaterControlStyle)
			.build());
		controlTuning.add(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.view_distance"),
				config.viewDistance(),
				FastNoteblocksConfig.MIN_VIEW_DISTANCE,
				FastNoteblocksConfig.MAX_VIEW_DISTANCE)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_VIEW_DISTANCE)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.view_distance"))
			.setSaveConsumer(config::setViewDistance)
			.build());
		controlTuning.add(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.invert_scroll"), config.invertScrolling())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.invert_scroll"))
			.setSaveConsumer(config::setInvertScrolling)
			.build());
		general.addEntry((AbstractConfigListEntry<?>) controlTuning.build());

		SubCategoryBuilder serverFriendliness = entries.startSubCategory(
				Component.translatable("category.fast-noteblocks.server_friendliness"))
			.setExpanded(false);
		serverFriendliness.add(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.interaction_delay"),
				config.interactionDelayTicks(),
				FastNoteblocksConfig.MIN_INTERACTION_DELAY_TICKS,
				FastNoteblocksConfig.MAX_INTERACTION_DELAY_TICKS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_INTERACTION_DELAY_TICKS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.interaction_delay"))
			.setSaveConsumer(config::setInteractionDelayTicks)
			.build());
		serverFriendliness.add(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.wait_for_ack"), config.waitForServerAcknowledgement())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.wait_for_ack"))
			.setSaveConsumer(config::setWaitForServerAcknowledgement)
			.build());
		serverFriendliness.add(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.require_line_of_sight"), config.requireLineOfSight())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.require_line_of_sight"))
			.setSaveConsumer(config::setRequireLineOfSight)
			.build());
		general.addEntry((AbstractConfigListEntry<?>) serverFriendliness.build());

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
		placement.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.max_build_floors"),
				config.maxBuildFloors(),
				FastNoteblocksConfig.MIN_MAX_BUILD_FLOORS,
				FastNoteblocksConfig.MAX_MAX_BUILD_FLOORS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_MAX_BUILD_FLOORS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.max_build_floors"))
			.setSaveConsumer(config::setMaxBuildFloors)
			.build());
		placement.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.ultra_lane_start_top"),
				config.ultraLaneStartTop())
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.ultra_lane_start_top"))
			.setSaveConsumer(config::setUltraLaneStartTop)
			.build());
		placement.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.commands_per_tick"),
				config.commandsPerTick(),
				FastNoteblocksConfig.MIN_COMMANDS_PER_TICK,
				FastNoteblocksConfig.MAX_COMMANDS_PER_TICK)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_COMMANDS_PER_TICK)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.commands_per_tick"))
			.setSaveConsumer(config::setCommandsPerTick)
			.build());
		placement.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.auto_select_sequence_block"),
				config.autoSelectSequenceBlock())
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.auto_select_sequence_block"))
			.setSaveConsumer(config::setAutoSelectSequenceBlock)
			.build());


		ConfigCategory midi = builder.getOrCreateCategory(Component.translatable("category.fast-noteblocks.midi"));
		midi.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.midi_quantize_grid"),
				MidiQuantizeGrid.class,
				config.midiQuantizeGrid())
			.setDefaultValue(MidiQuantizeGrid.AUTO)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.midi_quantize_grid." + ((MidiQuantizeGrid) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_quantize_grid"))
			.setSaveConsumer(config::setMidiQuantizeGrid)
			.build());
		midi.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.midi_tempo_fit"),
				MidiTempoFit.class,
				config.midiTempoFit())
			.setDefaultValue(MidiTempoFit.SNAP_TO_REPEATERS)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.midi_tempo_fit." + ((MidiTempoFit) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_tempo_fit"))
			.setSaveConsumer(config::setMidiTempoFit)
			.build());
		midi.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.midi_instrument_source"),
				MidiInstrumentSource.class,
				config.midiInstrumentSource())
			.setDefaultValue(MidiInstrumentSource.FROM_FILE_THEN_NAME)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.midi_instrument_source."
					+ ((MidiInstrumentSource) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_instrument_source"))
			.setSaveConsumer(config::setMidiInstrumentSource)
			.build());
		midi.addEntry(entries.startEnumSelector(
				Component.translatable("option.fast-noteblocks.midi_range_fit"),
				MidiRangeFit.class,
				config.midiRangeFit())
			.setDefaultValue(MidiRangeFit.OCTAVE_SHIFT)
			.setEnumNameProvider(value -> Component.translatable(
				"option.fast-noteblocks.midi_range_fit." + ((MidiRangeFit) value).name().toLowerCase()
			))
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_range_fit"))
			.setSaveConsumer(config::setMidiRangeFit)
			.build());
		midi.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.conversion_gap_percentile"),
				config.conversionGapPercentile(),
				FastNoteblocksConfig.MIN_CONVERSION_GAP_PERCENTILE,
				FastNoteblocksConfig.MAX_CONVERSION_GAP_PERCENTILE)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_CONVERSION_GAP_PERCENTILE)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.conversion_gap_percentile"))
			.setSaveConsumer(config::setConversionGapPercentile)
			.build());
		midi.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.repeat_merge_ticks"),
				config.repeatMergeTicks(),
				FastNoteblocksConfig.MIN_REPEAT_MERGE_TICKS,
				FastNoteblocksConfig.MAX_REPEAT_MERGE_TICKS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_REPEAT_MERGE_TICKS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.repeat_merge_ticks"))
			.setSaveConsumer(config::setRepeatMergeTicks)
			.build());
		midi.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.midi_velocity_cutoff"),
				config.midiVelocityCutoff(),
				FastNoteblocksConfig.MIN_MIDI_VELOCITY_CUTOFF,
				FastNoteblocksConfig.MAX_MIDI_VELOCITY_CUTOFF)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_MIDI_VELOCITY_CUTOFF)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_velocity_cutoff"))
			.setSaveConsumer(config::setMidiVelocityCutoff)
			.build());
		midi.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.chord_thin_target"),
				config.chordThinTarget(),
				FastNoteblocksConfig.MIN_CHORD_THIN_TARGET,
				FastNoteblocksConfig.MAX_CHORD_THIN_TARGET)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_CHORD_THIN_TARGET)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.chord_thin_target"))
			.setSaveConsumer(config::setChordThinTarget)
			.build());
		midi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.fast-noteblocks.midi_ignore_percussion"),
				config.midiIgnorePercussion())
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_ignore_percussion"))
			.setSaveConsumer(config::setMidiIgnorePercussion)
			.build());
		midi.addEntry(entries.startIntSlider(
				Component.translatable("option.fast-noteblocks.midi_max_imported_tracks"),
				config.midiMaxImportedTracks(),
				FastNoteblocksConfig.MIN_MIDI_MAX_IMPORTED_TRACKS,
				FastNoteblocksConfig.MAX_MIDI_MAX_IMPORTED_TRACKS)
			.setDefaultValue(FastNoteblocksConfig.DEFAULT_MIDI_MAX_IMPORTED_TRACKS)
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_max_imported_tracks"))
			.setSaveConsumer(config::setMidiMaxImportedTracks)
			.build());
		midi.addEntry(entries.startStrField(
				Component.translatable("option.fast-noteblocks.midi_default_instrument"),
				config.midiDefaultInstrument())
			.setDefaultValue("HARP")
			.setTooltip(Component.translatable("tooltip.fast-noteblocks.midi_default_instrument"))
			.setSaveConsumer(config::setMidiDefaultInstrument)
			.build());

		builder.setSavingRunnable(FastNoteblocksConfig::save);
		return builder.build();
	}
}
