package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.fastnoteblocks.client.composer.SongLibrary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The song library: pick one, or start a new one.
 *
 * <p>Deliberately not the entry point. The composer is, opened on whichever song was last worked
 * on, because that is what you nearly always want; this exists to answer "a different one". The
 * numbers here are the ones a person can act on -- how long it is, whether it will build -- not
 * ticks and pitch indices, which belong next to something clickable in the composer.</p>
 */
public final class SongsScreen extends Screen {
	private static final int ROW_HEIGHT = 34;
	private static final int LIST_TOP = 44;

	private final Screen parent;
	private final FastNoteblocksConfig config;
	private final List<Row> rows = new ArrayList<>();
	private final Map<String, SongAnalysis> analyses = new LinkedHashMap<>();
	private int scroll;
	private String status = "";

	public SongsScreen(Screen parent, FastNoteblocksConfig config) {
		super(Component.literal("Songs"));
		this.parent = parent;
		this.config = config;
	}

	private record Row(String id, ComposerProject song) {
	}

	@Override
	protected void init() {
		clearWidgets();
		rows.clear();
		SongLibrary library = FastNoteblocksConfig.songs();
		for (String id : library.ids()) {
			ComposerProject song = library.song(id);
			rows.add(new Row(id, song));
			analyses.computeIfAbsent(id,
				ignored -> SongAnalysis.of(song, config.dedupeIdenticalNotes()));
		}

		int listBottom = height - 56;
		int visible = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
		for (int index = scroll; index < Math.min(rows.size(), scroll + visible); index++) {
			Row row = rows.get(index);
			int y = LIST_TOP + (index - scroll) * ROW_HEIGHT;
			boolean active = row.id().equals(config.activeSongId());
			addRenderableWidget(Button.builder(
					Component.literal(active ? "Resume" : "Open"), button -> open(row.id()))
				.bounds(width - 152, y + 4, 52, 20)
				.tooltip(Tooltip.create(Component.literal("Edit this song in the composer")))
				.build());
			addRenderableWidget(Button.builder(Component.literal("Copy"), button -> duplicate(row))
				.bounds(width - 96, y + 4, 44, 20)
				.tooltip(Tooltip.create(Component.literal("Duplicate this song")))
				.build());
			Button delete = addRenderableWidget(Button.builder(
					Component.literal("×").withStyle(ChatFormatting.RED), button -> confirmDelete(row))
				.bounds(width - 48, y + 4, 20, 20)
				.build());
			delete.active = rows.size() > 1;
		}

		addRenderableWidget(Button.builder(Component.literal("+ New song"), button -> create())
			.bounds(8, height - 26, 82, 20).build());
		addRenderableWidget(Button.builder(Component.literal("New from text"), button -> newFromText())
			.bounds(94, height - 26, 92, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Build a song from sequence text like \"12, 4d, 7\". Anything text can say is "
					+ "buildable by definition, so it arrives Minecraft ready.")))
			.build());
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(width - 76, height - 26, 68, 20).build());

		// Starting from an import belongs here as much as in the composer. Reaching it only from
		// inside an open song meant having to open something you did not want in order to leave it.
		// Shared out across whatever width there is, rather than three fixed sizes that run off the
		// side of a narrow window or a large GUI scale.
		int importWidth = Math.min(110, (width - 16 - 8) / 3);
		int importY = height - 50;
		addRenderableWidget(Button.builder(Component.literal("From MIDI / NBS"),
				button -> SongImports.chooseMidiOrNbs(this, config, this::openImported))
			.bounds(8, importY, importWidth, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Read a MIDI or NBS file in as a new song.")))
			.build());
		addRenderableWidget(Button.builder(Component.literal("From schematic"),
				button -> SongImports.chooseSchematic(this, config, this::openImported))
			.bounds(12 + importWidth, importY, importWidth, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Read a saved build back into a song by following its redstone. Structure (.nbt), "
					+ "Sponge (.schem) and Litematica (.litematic).")))
			.build());
		Button scan = addRenderableWidget(Button.builder(Component.literal("From world"),
				button -> SongImports.scanWorld(this, this::openImported))
			.bounds(16 + importWidth * 2, importY, importWidth, 20)
			.tooltip(Tooltip.create(Component.literal(
				"Read a note block machine standing in the world back into a song. Only chunks "
					+ "your client has loaded can be read, so stand near the build.")))
			.build());
		// Nothing to scan from the title screen, and the coordinate prompt could not tell you why
		// the region you typed came back empty.
		scan.active = minecraft.level != null;
	}

	private void open(String id) {
		config.setActiveSongId(id);
		FastNoteblocksConfig.save();
		minecraft.gui.setScreen(new ComposerScreen(parent, config));
	}

	/**
	 * Opens an import without adding it to the list.
	 *
	 * <p>It becomes a song here only when it is saved. Writing it straight in would put something
	 * in the library that nobody has decided to keep yet, which is the state this screen exists to
	 * show the truth about.</p>
	 */
	private void openImported(SongImports.Imported imported) {
		SongImports.open(parent, config, () -> {
		}, imported);
	}

	private void create() {
		SongLibrary library = FastNoteblocksConfig.songs();
		String name = library.uniqueName("Untitled composition");
		String id = library.newId(name);
		library.save(id, ComposerProject.empty(name));
		open(id);
	}

	private void newFromText() {
		minecraft.gui.setScreen(new SongTextScreen(this, config));
	}

	private void duplicate(Row row) {
		SongLibrary library = FastNoteblocksConfig.songs();
		String name = library.uniqueName(row.song().name() + " copy");
		library.save(library.newId(name), row.song().withName(name));
		status = "Copied " + row.song().name();
		init();
	}

	private void confirmDelete(Row row) {
		minecraft.gui.setScreen(new ConfirmScreen(
			confirmed -> {
				if (confirmed) {
					FastNoteblocksConfig.songs().delete(row.id());
					status = "Deleted " + row.song().name();
				}
				minecraft.gui.setScreen(this);
			},
			Component.literal("Delete \"" + row.song().name() + "\"?"),
			Component.literal(summary(row) + " - this cannot be undone"),
			Component.literal("Delete"),
			CommonComponents.GUI_CANCEL
		));
	}

	private String summary(Row row) {
		SongAnalysis analysis = analyses.get(row.id());
		ComposerProject song = row.song();
		return String.format(Locale.ROOT, "%d notes - %d layer%s - %s - %s - %.2fx",
			analysis.totalNotes(), song.layers().size(), song.layers().size() == 1 ? "" : "s",
			analysis.lengthLabel(), bpmLabel(song), song.speedQuarters() / 4.0);
	}

	private static String bpmLabel(ComposerProject song) {
		return Math.round(60_000_000.0 / song.tempoMicrosPerQuarter()) + " BPM";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, "Songs", 8, 16, 0xFFFFFFFF, false);
		graphics.text(font, FastNoteblocksConfig.songs().ids().size() + " in "
			+ SongLibrary.directory().getFileName(), 8, 28, 0xFF8A9098, false);

		int listBottom = height - 56;
		int visible = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
		for (int index = scroll; index < Math.min(rows.size(), scroll + visible); index++) {
			Row row = rows.get(index);
			int y = LIST_TOP + (index - scroll) * ROW_HEIGHT;
			boolean active = row.id().equals(config.activeSongId());
			graphics.fill(8, y, width - 8, y + ROW_HEIGHT - 4, active ? 0x40FFFFFF : 0x25FFFFFF);
			graphics.text(font, row.song().name(), 14, y + 5, active ? 0xFFFFFFFF : 0xFFD8DCE1, false);

			SongAnalysis analysis = analyses.get(row.id());
			boolean ready = analysis.buildable();
			String verdict = ready ? "Minecraft ready" : String.join(", ", analysis.problems());
			graphics.text(font, summary(row), 14, y + 17, 0xFF8A9098, false);
			int verdictX = 14 + font.width(summary(row)) + 10;
			if (verdictX < width - 160) {
				graphics.text(font, verdict, verdictX, y + 17,
					ready ? 0xFF5AD46A : 0xFFFFAA00, false);
			}
		}
		if (rows.isEmpty()) {
			graphics.text(font, "No songs yet. Start one, or import a MIDI or NBS file from the composer.",
				14, LIST_TOP + 8, 0xFF8A9098, false);
		}
		List<String> failures = FastNoteblocksConfig.songs().failures();
		if (!failures.isEmpty()) {
			graphics.text(font, failures.size() + " song file(s) could not be read: "
				+ String.join(", ", failures), 8, height - 40, 0xFFFF6B6B, false);
		} else if (!status.isEmpty()) {
			graphics.text(font, status, 8, height - 40, 0xFF8A9098, false);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.max(1, (height - 32 - LIST_TOP) / ROW_HEIGHT);
		int maximum = Math.max(0, rows.size() - visible);
		int updated = Math.max(0, Math.min(maximum, scroll - (int)Math.signum(scrollY)));
		if (updated != scroll) {
			scroll = updated;
			init();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		FastNoteblocksConfig.save();
		minecraft.gui.setScreen(parent);
	}
}
