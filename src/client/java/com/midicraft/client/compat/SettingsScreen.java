package com.midicraft.client.compat;

import com.midicraft.client.MidicraftConfig;
import com.midicraft.client.MidicraftConfig.ChordPlaceOrder;
import com.midicraft.client.MidicraftConfig.MidiInstrumentSource;
import com.midicraft.client.MidicraftConfig.MidiQuantizeGrid;
import com.midicraft.client.MidicraftConfig.OverlayMode;
import com.midicraft.client.MidicraftConfig.RepeaterControlStyle;
import com.midicraft.client.MidicraftConfig.SequencingEditProtection;
import com.midicraft.client.NoteBlockOverlay;
import com.midicraft.client.composer.ComposerProject;
import com.midicraft.client.PasteRate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Every standing preference the mod has, in a screen of its own.
 *
 * <p>These used to live in a Cloth Config panel that only Mod Menu could open, which made two
 * optional mods the price of changing a setting -- and meant a player without them was stuck with
 * whatever the config file already held. The ten import settings had already been copied into a
 * menu in the Composer to work around that, and the copy had drifted: it was missing one of them.
 * So there is one definition of a setting now, and it is this one.</p>
 *
 * <p>The rows are deliberately uniform. Everything a setting can be is reduced to an int -- a raw
 * value for a slider, an index into a list for anything that cycles -- so one control, one caption
 * and one reset button serve all thirty-two of them, and adding the thirty-third is a line rather
 * than a widget.</p>
 */
public final class SettingsScreen extends Screen {
	private static final int SIDEBAR_LEFT = 8;
	private static final int SIDEBAR_WIDTH = 104;
	private static final int SIDEBAR_ROW_HEIGHT = 22;
	private static final int LIST_LEFT = SIDEBAR_LEFT + SIDEBAR_WIDTH + 8;
	private static final int LIST_TOP = 34;
	private static final int ROW_HEIGHT = 22;
	private static final int CONTROL_HEIGHT = 20;
	private static final int RESET_WIDTH = 20;
	/** Wide enough for the longest caption a row can show; wider only wastes the space. */
	private static final int LIST_MAX_WIDTH = 340;
	private static final int FOOTER_HEIGHT = 34;
	private static final String RESET_GLYPH = "↺";
	/** Off first, then narrowest to widest -- the order someone turning this up would walk. */
	private static final OverlayMode[] NEARBY_MODES = {
		OverlayMode.OFF, OverlayMode.REPEATERS_ONLY, OverlayMode.NOTES_ONLY, OverlayMode.BOTH
	};
	/** The same without OFF, which is what the toggle above it is for. */
	private static final OverlayMode[] INTERACTIVE_MODES = {
		OverlayMode.REPEATERS_ONLY, OverlayMode.NOTES_ONLY, OverlayMode.BOTH
	};

	private final Screen parent;
	private final MidicraftConfig config = MidicraftConfig.get();
	/**
	 * A config nobody edits, so a row can say what it would go back to.
	 *
	 * <p>Read from rather than written to: every default in the mod is written down once, in
	 * {@code MidicraftConfig.defaults()}, and this is that same builder run a second time.</p>
	 */
	private final MidicraftConfig defaults = MidicraftConfig.defaultValues();
	private final List<Row> rows = new ArrayList<>();
	/**
	 * The tab's settings, rebuilt only when the tab is.
	 *
	 * <p>Held rather than asked for, because the render pass wants the count every frame and each
	 * ask builds a row of fresh lambdas.</p>
	 */
	private List<Entry> showing = List.of();
	private final List<Heading> headings = new ArrayList<>();
	/** Held so it can grey out the moment the last row on the tab goes back to its default. */
	private Button resetCategory;
	private Category category = Category.COMPOSER;
	/** The binding waiting for a key, or null. Vanilla's Controls screen works the same way. */
	private KeyMapping listening;
	private int scroll;
	/**
	 * Whether anything has moved since the screen opened.
	 *
	 * <p>A change reaches the running mod the moment it is made, because a setting you cannot see
	 * take effect is a setting you cannot judge. Only the write to disk waits for the screen to
	 * close, so that dragging a slider across its range is one file write rather than thirty.</p>
	 */
	private boolean dirty;

	public SettingsScreen(Screen parent) {
		super(Component.translatable("title.midicraft.config"));
		this.parent = parent;
	}

	/**
	 * The tabs down the left.
	 *
	 * <p>Down rather than across: a title like "Server friendliness" is wider than a sixth of a
	 * narrow window, and a tab strip that has to elide its own labels is worse than a column.</p>
	 */
	private enum Category {
		// Composer first and open first: it is what the mod is for, and the tab someone arriving
		// from its own menu bar has already told you they were looking at.
		COMPOSER("composer"),
		PLACEMENT("placement"),
		IN_WORLD("in_world"),
		KEYS("keys"),
		SERVER("server_friendliness"),
		DEBUG("debug");

		private final String key;

		Category(String key) {
			this.key = key;
		}

		Component title() {
			return Component.translatable("category.midicraft." + key);
		}
	}

	/**
	 * One setting, as the screen needs it: what it is called, what it is worth, how to change it.
	 *
	 * <p>{@code read} takes the config it reads from rather than closing over the live one. That is
	 * the whole trick behind the reset buttons -- the same row can be pointed at the defaults to ask
	 * what it would be worth if it were put back, without a second table of defaults to maintain.</p>
	 */
	private record Option(String key, int min, int max, ToIntFunction<MidicraftConfig> read,
			IntConsumer write, IntFunction<Component> say, boolean stepped) {
		Option(String key, int min, int max, ToIntFunction<MidicraftConfig> read,
				IntConsumer write, IntFunction<Component> say) {
			this(key, min, max, read, write, say, false);
		}

		Component label() {
			return Component.translatable("option.midicraft." + key);
		}

		Component caption(int value) {
			return Component.empty().append(label()).append(": ").append(say.apply(value));
		}

		Tooltip tooltip() {
			return Tooltip.create(Component.translatable("tooltip.midicraft." + key));
		}

		/**
		 * Whether this row is a button rather than a slider.
		 *
		 * <p>Two values always are. More than two normally slide, but a setting that rebuilds the
		 * screen when it changes cannot: the slider being dragged is one of the widgets that gets
		 * thrown away, so the drag dies on the first notch. Those step instead.</p>
		 */
		boolean flips() {
			return stepped || max - min == 1;
		}
	}

	/**
	 * A line of a tab: either a setting, or a heading over the ones that follow.
	 *
	 * <p>Headings earn their row on the Composer tab, where conversion settings and import
	 * settings sit together and read as one undifferentiated list without them -- which is how
	 * the quantize grid spent this long being taken for something an import does.</p>
	 */
	private record Entry(String heading, Option option, KeyMapping key, String link,
			Runnable action) {
		static Entry of(Option option) {
			return new Entry(null, option, null, null, null);
		}

		static Entry of(KeyMapping key) {
			return new Entry(null, null, key, null, null);
		}

		static Entry heading(String key) {
			return new Entry(key, null, null, null, null);
		}

		/** A row that is only a way somewhere else. */
		static Entry link(String key, Runnable action) {
			return new Entry(null, null, null, key, action);
		}
	}

	/** A laid-out row, kept so its caption and its reset button can follow the value. */
	private record Row(Option option, IntConsumer show, Button reset) {
	}

	/** A heading that has been given a position, so the render pass can draw it. */
	private record Heading(Component text, int y) {
	}

	@Override
	protected void init() {
		clearWidgets();
		rows.clear();

		showing = entries(category);
		int visible = visibleRows();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, showing.size() - visible)));

		for (Category tab : Category.values()) {
			Button button = addRenderableWidget(Button.builder(tab.title(), pressed -> {
					category = tab;
					scroll = 0;
					init();
				})
				.bounds(SIDEBAR_LEFT, LIST_TOP + tab.ordinal() * SIDEBAR_ROW_HEIGHT,
					SIDEBAR_WIDTH, CONTROL_HEIGHT)
				.build());
			button.active = tab != category;
		}

		int listWidth = listWidth();
		headings.clear();
		for (int index = scroll; index < Math.min(showing.size(), scroll + visible); index++) {
			Entry entry = showing.get(index);
			int y = LIST_TOP + (index - scroll) * ROW_HEIGHT;
			if (entry.heading() != null) {
				headings.add(new Heading(
					Component.translatable("category.midicraft." + entry.heading()), y));
			} else if (entry.key() != null) {
				addKeyRow(entry.key(), y, listWidth);
			} else if (entry.link() != null) {
				addRenderableWidget(Button.builder(
						Component.translatable("option.midicraft." + entry.link()),
						pressed -> entry.action().run())
					.bounds(LIST_LEFT, y, listWidth, CONTROL_HEIGHT)
					.tooltip(Tooltip.create(
						Component.translatable("tooltip.midicraft." + entry.link())))
					.build());
			} else {
				addRow(entry.option(), y, listWidth);
			}
		}

		// The footer starts under the tabs rather than under the list: the three buttons do not
		// fit across the list column alone at the narrowest window the game will open.
		int footer = height - FOOTER_HEIGHT + 8;
		resetCategory = addRenderableWidget(Button.builder(
				Component.literal("Reset tab"), pressed -> {
					for (Entry entry : entries(category)) {
						if (entry.option() != null) {
							entry.option().write().accept(entry.option().read().applyAsInt(defaults));
							dirty = true;
						} else if (entry.key() != null) {
							entry.key().setKey(entry.key().getDefaultKey());
						}
					}
					KeyMapping.resetMapping();
					minecraft.options.save();
					init();
				})
			.bounds(SIDEBAR_LEFT, footer, 70, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.empty().append("Puts every setting under ")
				.append(category.title())
				.append(" back to what it was before anyone touched it.")))
			.build());
		addRenderableWidget(Button.builder(Component.literal("Reset all"), pressed -> confirmResetAll())
			.bounds(SIDEBAR_LEFT + 74, footer, 70, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal(
				"Puts every setting in the mod back to its default. Songs are not touched.")))
			.build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> onClose())
			.bounds(LIST_LEFT + listWidth - 70, footer, 70, CONTROL_HEIGHT)
			.build());

		refreshResets();
	}

	private void addRow(Option option, int y, int listWidth) {
		int wide = listWidth - RESET_WIDTH - 4;
		int current = option.read().applyAsInt(config);
		IntConsumer show;
		if (option.flips()) {
			Button flip = addRenderableWidget(Button.builder(option.caption(current), pressed -> {
					int held = option.read().applyAsInt(config);
					// Advance and wrap, which for a row of two values is the same as flipping it.
					option.write().accept(held >= option.max() ? option.min() : held + 1);
					changed();
				})
				.bounds(LIST_LEFT, y, wide, CONTROL_HEIGHT)
				.tooltip(option.tooltip())
				.build());
			show = value -> flip.setMessage(option.caption(value));
		} else {
			Rung rung = new Rung(LIST_LEFT, y, wide, option, current);
			rung.setTooltip(option.tooltip());
			addRenderableWidget(rung);
			show = rung::show;
		}

		int fallback = option.read().applyAsInt(defaults);
		Button reset = addRenderableWidget(Button.builder(Component.literal(RESET_GLYPH), pressed -> {
				option.write().accept(fallback);
				changed();
			})
			.bounds(LIST_LEFT + listWidth - RESET_WIDTH, y, RESET_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(
				Component.empty().append("Reset to ").append(option.say().apply(fallback))))
			.build());
		rows.add(new Row(option, show, reset));
	}

	/**
	 * A key binding row.
	 *
	 * <p>Nothing about the key is stored here. The button edits the {@link KeyMapping} the game
	 * itself holds and then saves vanilla's options, so a key changed here is already changed in
	 * the Controls screen and the other way about -- there is one binding, shown twice.</p>
	 */
	private void addKeyRow(KeyMapping mapping, int y, int listWidth) {
		int wide = listWidth - RESET_WIDTH - 4;
		Button bind = addRenderableWidget(Button.builder(keyCaption(mapping), pressed -> {
				listening = mapping;
				init();
			})
			.bounds(LIST_LEFT, y, wide, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(keyTooltip(mapping)))
			.build());
		bind.active = listening == null;

		Button reset = addRenderableWidget(Button.builder(Component.literal(RESET_GLYPH), pressed -> {
				bind(mapping, mapping.getDefaultKey());
			})
			.bounds(LIST_LEFT + listWidth - RESET_WIDTH, y, RESET_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.empty().append("Reset to ")
				.append(defaultKeyName(mapping))))
			.build());
		reset.active = listening == null && !mapping.isDefault();
	}

	private Component keyCaption(KeyMapping mapping) {
		Component name = Component.translatable(mapping.getName());
		if (listening == mapping) {
			return Component.empty().append(name).append(": ")
				.append(Component.literal("> ? <").withStyle(ChatFormatting.YELLOW));
		}
		Component bound = mapping.isUnbound()
			? Component.literal("unbound").withStyle(ChatFormatting.GRAY)
			: mapping.getTranslatedKeyMessage();
		if (!conflicts(mapping).isEmpty()) {
			bound = Component.literal(bound.getString()).withStyle(ChatFormatting.RED);
		}
		return Component.empty().append(name).append(": ").append(bound);
	}

	private Component keyTooltip(KeyMapping mapping) {
		List<KeyMapping> clashing = conflicts(mapping);
		if (clashing.isEmpty()) {
			return Component.literal("Click, then press a key or mouse button. "
				+ "Escape leaves it unbound.");
		}
		Component tooltip = Component.literal("Also bound to: ");
		for (int index = 0; index < clashing.size(); index++) {
			if (index > 0) {
				tooltip = Component.empty().append(tooltip).append(", ");
			}
			tooltip = Component.empty().append(tooltip)
				.append(Component.translatable(clashing.get(index).getName()));
		}
		return Component.empty().append(tooltip)
			.append(". Both will fire, which is rarely what anyone wants.");
	}

	/**
	 * Every other binding in the game that answers to the same key.
	 *
	 * <p>Asked of vanilla's whole list, not just ours, because the clash that matters is with the
	 * key someone already uses to sneak or open their inventory.</p>
	 */
	private List<KeyMapping> conflicts(KeyMapping mapping) {
		if (mapping.isUnbound()) {
			return List.of();
		}
		List<KeyMapping> found = new ArrayList<>();
		for (KeyMapping other : minecraft.options.keyMappings) {
			if (other != mapping && mapping.same(other)) {
				found.add(other);
			}
		}
		return found;
	}

	private Component defaultKeyName(KeyMapping mapping) {
		return mapping.getDefaultKey() == InputConstants.UNKNOWN
			? Component.literal("unbound")
			: Component.literal(mapping.getDefaultKey().getDisplayName().getString());
	}

	private void bind(KeyMapping mapping, InputConstants.Key key) {
		mapping.setKey(key);
		// Rebuilds the game's key-to-binding lookup. Without it the old key keeps working and the
		// new one does nothing, which reads as the rebind having silently failed.
		KeyMapping.resetMapping();
		minecraft.options.save();
		listening = null;
		init();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (listening != null) {
			bind(listening, event.key() == GLFW.GLFW_KEY_ESCAPE
				? InputConstants.UNKNOWN
				: InputConstants.getKey(event));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (listening != null) {
			bind(listening, InputConstants.Type.MOUSE.getOrCreate(event.button()));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** After a discrete change: the row that moved redraws itself and the resets catch up. */
	private void changed() {
		dirty = true;
		for (Row row : rows) {
			row.show().accept(row.option().read().applyAsInt(config));
		}
		refreshResets();
	}

	/**
	 * Greys out the reset buttons that have nothing to undo.
	 *
	 * <p>Kept apart from {@link #changed()} because a slider calls this on every notch of a drag,
	 * and it must not reach back and reposition the slider being dragged.</p>
	 */
	private void refreshResets() {
		for (Row row : rows) {
			row.reset().active = row.option().read().applyAsInt(config)
				!= row.option().read().applyAsInt(defaults);
		}
		if (resetCategory != null) {
			// Asked of the whole tab, not of the rows on screen: a scrolled-past setting still
			// counts as something this button would put back.
			resetCategory.active = showing.stream().anyMatch(entry ->
				entry.option() != null
					? entry.option().read().applyAsInt(config)
						!= entry.option().read().applyAsInt(defaults)
					: entry.key() != null && !entry.key().isDefault());
			// A link row is not a setting and has nothing to put back.
		}
	}

	private void confirmResetAll() {
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				for (Category tab : Category.values()) {
					for (Entry entry : entries(tab)) {
						if (entry.option() != null) {
							entry.option().write().accept(entry.option().read().applyAsInt(defaults));
						}
					}
				}
				dirty = true;
			}
			minecraft.gui.setScreen(this);
		}, Component.literal("Reset every setting?"),
			Component.literal("Every setting in the mod goes back to its default. Your key "
				+ "bindings, your songs, and where you had got to placing them, are not touched."),
			Component.literal("Reset all"), CommonComponents.GUI_CANCEL));
	}

	private int listWidth() {
		return Math.max(120, Math.min(LIST_MAX_WIDTH, width - LIST_LEFT - 8));
	}

	private int visibleRows() {
		return Math.max(1, (height - FOOTER_HEIGHT - LIST_TOP) / ROW_HEIGHT);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int maximum = Math.max(0, showing.size() - visibleRows());
		int updated = Math.max(0, Math.min(maximum, scroll - (int) Math.signum(scrollY)));
		if (updated != scroll) {
			scroll = updated;
			init();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, title.getString(), SIDEBAR_LEFT, 12, 0xFFFFFFFF, false);
		for (Heading heading : headings) {
			graphics.text(font, heading.text(), LIST_LEFT, heading.y() + 8, 0xFF8FD3FF, false);
			int rule = heading.y() + 19;
			graphics.fill(LIST_LEFT, rule, LIST_LEFT + listWidth(), rule + 1, 0xFF2C333D);
		}

		int hidden = showing.size() - visibleRows();
		if (hidden > 0) {
			String more = scroll == 0
				? hidden + " more below - scroll"
				: scroll >= hidden
					? "scroll up for " + hidden + " more"
					: "scrolled " + scroll + " of " + hidden;
			graphics.text(font, more, LIST_LEFT, height - FOOTER_HEIGHT - 4, 0xFF8A9098, false);
		}
	}

	@Override
	public void onClose() {
		if (dirty) {
			MidicraftConfig.save();
		}
		minecraft.gui.setScreen(parent);
	}

	private Option toggle(String key, Predicate<MidicraftConfig> read, Consumer<Boolean> write) {
		return new Option(key, 0, 1,
			source -> read.test(source) ? 1 : 0,
			value -> write.accept(value == 1),
			value -> Component.literal(value == 1 ? "On" : "Off"));
	}

	private <T extends Enum<T>> Option choice(String key, T[] values,
			Function<MidicraftConfig, T> read, Consumer<T> write) {
		// indexOf, not ordinal: a row may offer a subset of an enum, in an order of its own. The
		// interactive overlay type is exactly that -- every OverlayMode except OFF, which is what
		// the on/off beside it is for.
		List<T> offered = List.of(values);
		return new Option(key, 0, values.length - 1,
			source -> Math.max(0, offered.indexOf(read.apply(source))),
			value -> write.accept(values[value]),
			value -> Component.translatable("option.midicraft." + key + "."
				+ values[value].name().toLowerCase(Locale.ROOT)));
	}

	private Option slider(String key, int min, int max, ToIntFunction<MidicraftConfig> read,
			IntConsumer write, IntFunction<String> say) {
		return new Option(key, min, max, read, write, value -> Component.literal(say.apply(value)));
	}

	/** A slider's range, walked a click at a time. See {@link Option#flips()}. */
	private Option stepper(String key, int min, int max, ToIntFunction<MidicraftConfig> read,
			IntConsumer write, IntFunction<String> say) {
		return new Option(key, min, max, read, write,
			value -> Component.literal(say.apply(value)), true);
	}

	/**
	 * The lines of one tab, in the order they are worth reading.
	 *
	 * <p>The wordings for the conversion and import rows come from the Composer menu these replace,
	 * where a bare number had already proved to be the wrong thing to show: "0" says nothing, and
	 * "off (keep all)" says what the zero does.</p>
	 */
	private List<Entry> entries(Category category) {
		List<Entry> entries = new ArrayList<>();
		switch (category) {
			case IN_WORLD -> {
				entries.add(Entry.of(toggle("mod_enabled",
					MidicraftConfig::modEnabled, config::setModEnabled)));
				entries.add(Entry.of(choice("nearby_overlays", NEARBY_MODES,
					MidicraftConfig::nearbyOverlays, config::setNearbyOverlays)));
				entries.add(Entry.of(toggle("interactive_overlays",
					MidicraftConfig::interactiveOverlays, config::setInteractiveOverlays)));
				entries.add(Entry.of(choice("interactive_overlay_type", INTERACTIVE_MODES,
					MidicraftConfig::interactiveOverlayType,
					config::setInteractiveOverlayType)));
				entries.add(Entry.of(slider("radial_focus_delay",
					MidicraftConfig.MIN_RADIAL_FOCUS_DELAY_TICKS,
					MidicraftConfig.MAX_RADIAL_FOCUS_DELAY_TICKS,
					MidicraftConfig::radialFocusDelayTicks, config::setRadialFocusDelayTicks,
					value -> value == 0 ? "opens at once" : ticks(value))));
				entries.add(Entry.of(choice("repeater_control_style", RepeaterControlStyle.values(),
					MidicraftConfig::repeaterControlStyle, config::setRepeaterControlStyle)));
				entries.add(Entry.of(slider("view_distance",
					MidicraftConfig.MIN_VIEW_DISTANCE, MidicraftConfig.MAX_VIEW_DISTANCE,
					MidicraftConfig::viewDistance, config::setViewDistance,
					value -> value + " blocks")));
				entries.add(Entry.of(toggle("invert_scroll",
					MidicraftConfig::invertScrolling, config::setInvertScrolling)));
			}
			case DEBUG -> {
				entries.add(Entry.of(toggle("debug_commands",
					MidicraftConfig::debugCommandsEnabled, config::setDebugCommandsEnabled)));
			}
			case KEYS -> {
				for (KeyMapping mapping : NoteBlockOverlay.INSTANCE.keyMappings()) {
					entries.add(Entry.of(mapping));
				}
				entries.add(Entry.link("all_keys",
					() -> minecraft.gui.setScreen(
						new KeyBindsScreen(this, minecraft.options))));
			}
			case SERVER -> {
				entries.add(Entry.of(slider("interaction_delay",
					MidicraftConfig.MIN_INTERACTION_DELAY_TICKS,
					MidicraftConfig.MAX_INTERACTION_DELAY_TICKS,
					MidicraftConfig::interactionDelayTicks, config::setInteractionDelayTicks,
					value -> value == 0 ? "none - one a tick" : ticks(value))));
				entries.add(Entry.of(toggle("wait_for_ack",
					MidicraftConfig::waitForServerAcknowledgement,
					config::setWaitForServerAcknowledgement)));
				entries.add(Entry.of(toggle("require_line_of_sight",
					MidicraftConfig::requireLineOfSight, config::setRequireLineOfSight)));
			}
			case PLACEMENT -> {
				entries.add(Entry.of(choice("sequencing_edit_protection",
					SequencingEditProtection.values(),
					MidicraftConfig::sequencingEditProtection,
					config::setSequencingEditProtection)));
				entries.add(Entry.of(toggle("sequence_enabled",
					MidicraftConfig::placementSequenceEnabled,
					config::setPlacementSequenceEnabled)));
				entries.add(Entry.of(toggle("auto_select_sequence_block",
					MidicraftConfig::autoSelectSequenceBlock,
					config::setAutoSelectSequenceBlock)));
				entries.add(Entry.of(toggle("select_instruments",
					MidicraftConfig::selectInstruments, config::setSelectInstruments)));
				entries.add(Entry.of(toggle("select_harp_blocks",
					MidicraftConfig::selectHarpBlocks, config::setSelectHarpBlocks)));
				entries.add(Entry.of(choice("chord_place_order", ChordPlaceOrder.values(),
					MidicraftConfig::chordPlaceOrder, config::setChordPlaceOrder)));
				entries.add(Entry.of(slider("max_build_floors",
					MidicraftConfig.MIN_MAX_BUILD_FLOORS,
					MidicraftConfig.MAX_MAX_BUILD_FLOORS,
					MidicraftConfig::maxBuildFloors, config::setMaxBuildFloors,
					value -> value == 1 ? "1 floor" : value + " floors")));
				entries.add(Entry.of(toggle("ultra_lane_start_top",
					MidicraftConfig::ultraLaneStartTop, config::setUltraLaneStartTop)));
				// A rung of the ladder rather than the rate itself: the range runs from a quarter
				// to two hundred and fifty-six, and a slider that moved evenly across that would
				// spend nine tenths of its travel above the point where more is no longer the
				// question.
				entries.add(Entry.of(slider("commands_per_tick", 0, PasteRate.RATES.size() - 1,
					source -> PasteRate.index(source.commandsPerTick()),
					rung -> config.setCommandsPerTick(PasteRate.RATES.get(rung)),
					rung -> PasteRate.label(PasteRate.RATES.get(rung)))));
			}
			case COMPOSER -> {
				// Conversion first: it is what Edit > Convert for Minecraft does to a composition,
				// whatever that composition came from, and it applies to a song scanned out of the
				// world exactly as it does to an imported MIDI. Filing these two under MIDI Import
				// is how the quantize grid spent this long being taken for something import does.
				//
				// The heading is the button's own words, and it holds exactly the settings the
				// button reads -- all three of them, and nothing else. A group named for an action
				// is a claim about what that action consults, so the one row under it that Convert
				// never looks at was making the same mistake one heading further on.
				entries.add(Entry.heading("display"));
				entries.add(Entry.of(stepper("composer_gui_scale",
					MidicraftConfig.MIN_COMPOSER_GUI_SCALE,
					MidicraftConfig.MAX_COMPOSER_GUI_SCALE,
					MidicraftConfig::composerGuiScale, config::setComposerGuiScale,
					ComposerScale::describe)));

				entries.add(Entry.heading("conversion"));
				entries.add(Entry.of(choice("midi_quantize_grid", MidiQuantizeGrid.values(),
					MidicraftConfig::midiQuantizeGrid, config::setMidiQuantizeGrid)));
				entries.add(Entry.of(slider("conversion_gap_percentile",
					MidicraftConfig.MIN_CONVERSION_GAP_PERCENTILE,
					MidicraftConfig.MAX_CONVERSION_GAP_PERCENTILE,
					MidicraftConfig::conversionGapPercentile,
					config::setConversionGapPercentile,
					value -> value <= 0 ? "none (strict)" : "ignore closest " + value + "%")));
				// Both are buttons rather than sliders without being asked to be: a two-value option
				// has max - min == 1, which is what flips() already tests for.
				entries.add(Entry.of(choice("convert_octave_shifting",
					ComposerProject.OctaveShifting.values(),
					MidicraftConfig::convertOctaveShifting, config::setConvertOctaveShifting)));
				entries.add(Entry.of(toggle("convert_split_transposed",
					MidicraftConfig::convertSplitTransposed, config::setConvertSplitTransposed)));
				entries.add(Entry.of(slider("repeat_merge_ticks",
					MidicraftConfig.MIN_REPEAT_MERGE_TICKS,
					MidicraftConfig.MAX_REPEAT_MERGE_TICKS,
					MidicraftConfig::repeatMergeTicks, config::setRepeatMergeTicks,
					value -> value <= MidicraftConfig.MIN_REPEAT_MERGE_TICKS
						? "off (keep all)" : ticks(value))));
				// Next to Convert but not under it. The two are reached for at the same moment --
				// both answer "why will this song not build" -- and the row was filed with them for
				// that reason. But Convert never reads it: it belongs to Select > Overloaded chords
				// and to nothing else, and a heading naming a button is a claim about what that
				// button consults.
				entries.add(Entry.heading("chord_thinning"));
				entries.add(Entry.of(slider("chord_thin_target",
					MidicraftConfig.MIN_CHORD_THIN_TARGET,
					MidicraftConfig.MAX_CHORD_THIN_TARGET,
					MidicraftConfig::chordThinTarget, config::setChordThinTarget,
					value -> value + " per chord")));

				// What is left of import: which instrument a track arrives as, and the one setting
				// that still drops notes on the way in. Everything else an import used to decide
				// for you is an operation in the Composer, where it can be seen and undone.
				entries.add(Entry.heading("midi"));
				entries.add(Entry.of(choice("midi_instrument_source", MidiInstrumentSource.values(),
					MidicraftConfig::midiInstrumentSource, config::setMidiInstrumentSource)));
				// A list rather than the text field this used to be. The field accepted anything
				// and only an import would tell you it had not been an instrument name.
				entries.add(Entry.of(slider("midi_default_instrument", 0,
					PreviewInstrument.VALUES.size() - 1,
					source -> Math.max(0, PreviewInstrument.VALUES.indexOf(
						PreviewInstrument.byId(source.midiDefaultInstrument()))),
					index -> config.setMidiDefaultInstrument(
						PreviewInstrument.VALUES.get(index).id()),
					index -> PreviewInstrument.VALUES.get(index).name())));
				entries.add(Entry.of(slider("midi_velocity_cutoff",
					MidicraftConfig.MIN_MIDI_VELOCITY_CUTOFF,
					MidicraftConfig.MAX_MIDI_VELOCITY_CUTOFF,
					MidicraftConfig::midiVelocityCutoff, config::setMidiVelocityCutoff,
					value -> value == MidicraftConfig.MIN_MIDI_VELOCITY_CUTOFF
						? "off (keep all)" : Integer.toString(value))));
				entries.add(Entry.of(toggle("midi_ignore_percussion",
					MidicraftConfig::midiIgnorePercussion, config::setMidiIgnorePercussion)));
			}
		}
		return entries;
	}

	private static String ticks(int value) {
		return value + (value == 1 ? " tick" : " ticks");
	}

	/**
	 * A slider over any run of whole numbers, captioned with the sentence the value means.
	 *
	 * <p>Enums ride it too, as an index into their own values, which is why the caption comes from
	 * the option rather than from the number.</p>
	 */
	private final class Rung extends AbstractSliderButton {
		private final Option option;

		Rung(int x, int y, int width, Option option, int chosen) {
			super(x, y, width, CONTROL_HEIGHT, Component.empty(), position(option, chosen));
			this.option = option;
			updateMessage();
		}

		private static double position(Option option, int chosen) {
			int span = option.max() - option.min();
			return span <= 0 ? 0.0
				: (double) Math.max(0, Math.min(span, chosen - option.min())) / span;
		}

		private int chosen() {
			int span = option.max() - option.min();
			return option.min() + (span <= 0 ? 0 : (int) Math.round(value * span));
		}

		void show(int chosen) {
			value = position(option, chosen);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			// Called from the superclass constructor, before this class has its fields.
			if (option != null) {
				setMessage(option.caption(chosen()));
			}
		}

		@Override
		protected void applyValue() {
			if (option == null) {
				return;
			}
			option.write().accept(chosen());
			dirty = true;
			refreshResets();
		}
	}

	/**
	 * Hands the game its GUI scale back the instant this screen goes, whatever it is going to.
	 *
	 * <p>Vanilla calls this from the middle of the screen swap, so it lands before anything is
	 * drawn. If another of our screens is opening it puts the scale straight back in its own init,
	 * and if nothing is, the HUD behind this one is already the right size on the very next frame
	 * rather than a tick later.</p>
	 */
	@Override
	public void removed() {
		ComposerScale.screenClosed(this);
		super.removed();
	}
}
