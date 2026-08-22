package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerProject;
import com.fastnoteblocks.client.composer.SongAnalysis;
import com.fastnoteblocks.client.composer.SongLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

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
	private static final int LIST_TOP = 62;

	private final Screen parent;
	private final FastNoteblocksConfig config;
	private final List<Row> rows = new ArrayList<>();
	private final List<Row> shown = new ArrayList<>();
	private final List<Button> rowButtons = new ArrayList<>();
	private final Map<String, SongAnalysis> analyses = new LinkedHashMap<>();
	/** Which build the cached analyses were judged against, so a mode change invalidates them. */
	private Boolean analysedTwoLanes;
	private EditBox searchBox;
	private int scroll;
	private String status = "";

	public SongsScreen(Screen parent, FastNoteblocksConfig config) {
		super(Component.literal("Songs"));
		this.parent = parent;
		this.config = config;
	}

	private record Row(String id, ComposerProject song) {
	}

	private String sortLabel() {
		return config.listSortByName() ? "Sort: A to Z" : "Sort: newest";
	}

	/**
	 * Newest first by default: the song you last saved is nearly always the one you came back for,
	 * and a library sorted by name makes you remember what you called it before you can find it.
	 */
	private void sortRows() {
		if (config.listSortByName()) {
			rows.sort(Comparator.comparing(row -> row.song().name().toLowerCase(Locale.ROOT)));
			return;
		}
		rows.sort(Comparator.comparingLong((Row row) -> SongLibrary.modifiedAt(row.id())).reversed()
			// A stable second key, so two songs written in the same millisecond do not swap places
			// between one opening of this screen and the next.
			.thenComparing(row -> row.song().name().toLowerCase(Locale.ROOT)));
	}

	/**
	 * A song file dragged onto the window from the desktop.
	 *
	 * <p>Minecraft hands whatever is dropped to the open screen, so accepting one costs an override.
	 * Here it is the shortest path a song can take from outside: a file on the desktop becomes a
	 * composition without visiting a folder, choosing a format, or knowing which of the three import
	 * buttons matches what you are holding -- the extension answers that.</p>
	 */
	@Override
	public void onFilesDrop(List<Path> dropped) {
		Path file = dropped == null ? null : dropped.stream()
			.filter(Files::isRegularFile)
			.filter(SongImports::importable)
			.findFirst()
			.orElse(null);
		if (file == null) {
			status = "Drop a .mid, .midi, .nbs, .nbt, .schem or .litematic file to import it.";
			return;
		}
		SongImports.openDropped(this, config, file, this::openImported);
	}

	/** Whether the paste mode in use lays a second lane, which is what reaches between ticks. */
	private boolean buildsTwoLanes() {
		try {
			return SongBuilder.PasteMode.valueOf(config.pasteMode()).gameTicks();
		} catch (IllegalArgumentException unknown) {
			return SongBuilder.PasteMode.COMPACT_CUBE.gameTicks();
		}
	}

	@Override
	protected void init() {
		clearWidgets();
		rowButtons.clear();
		rows.clear();
		// Kept across an init so a resize does not re-analyse the whole library, but the verdict now
		// depends on the paste mode, so a change of mode has to throw the cache away.
		boolean twoLanes = buildsTwoLanes();
		if (analysedTwoLanes == null || analysedTwoLanes != twoLanes) {
			analyses.clear();
			analysedTwoLanes = twoLanes;
		}
		SongLibrary library = FastNoteblocksConfig.songs();
		for (String id : library.ids()) {
			ComposerProject song = library.song(id);
			rows.add(new Row(id, song));
			// Judged against the build actually set, the same as the composer's own status line.
			// The picker says "Minecraft ready" beside every song here, and it would be saying it
			// about a two-lane build in a one-lane paste mode.
			analyses.computeIfAbsent(id, ignored -> SongAnalysis.of(song,
				config.dedupeIdenticalNotes(), twoLanes));
		}

		sortRows();

		// Rebuilt rather than kept, because init runs again on every resize -- but its text survives,
		// so deleting or copying a song out of a filtered list does not throw the filter away.
		String query = searchBox == null ? "" : searchBox.getValue();
		int sortWidth = 92;
		searchBox = new EditBox(font, 8, 40, width - 16 - sortWidth - 4, 18,
			Component.literal("Search"));
		searchBox.setMaxLength(120);
		searchBox.setHint(Component.literal("Search by name").withStyle(EditBox.SEARCH_HINT_STYLE));
		searchBox.setValue(query);
		searchBox.setResponder(value -> {
			scroll = 0;
			rebuildRows();
		});
		addRenderableWidget(searchBox);
		addRenderableWidget(Button.builder(Component.literal(sortLabel()), button -> {
				config.setListSortByName(!config.listSortByName());
				FastNoteblocksConfig.save();
				scroll = 0;
				init();
			})
			.bounds(width - 8 - sortWidth, 40, sortWidth, 18)
			.tooltip(Tooltip.create(Component.literal(
				"Newest first puts whatever you last saved at the top, which is nearly always what "
					+ "you came back for. A to Z is for finding one you know the name of. The file "
					+ "browser follows the same choice.")))
			.build());

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

		rebuildRows();
		// Typing goes to the search straight away. There is nothing else on this screen a keystroke
		// could have meant, and a library you have to reach for the mouse to search is not searched.
		setInitialFocus(searchBox);
	}

	private int visibleRows() {
		return Math.max(1, (height - 56 - LIST_TOP) / ROW_HEIGHT);
	}

	/**
	 * The songs the search leaves, in library order.
	 *
	 * <p>Every whitespace-separated word has to appear somewhere in the name, in any order, so
	 * "brown gold" finds "Golden Brown" -- the point of a search here is to type the two words you
	 * remember, not to reproduce the name you would otherwise have scrolled to.</p>
	 */
	private List<Row> matches() {
		String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) {
			return List.copyOf(rows);
		}
		String[] words = query.split("\\s+");
		return rows.stream().filter(row -> {
			String name = row.song().name().toLowerCase(Locale.ROOT);
			for (String word : words) {
				if (!name.contains(word)) {
					return false;
				}
			}
			return true;
		}).toList();
	}

	/**
	 * Re-lays the list's buttons for the current search and scroll.
	 *
	 * <p>Separate from {@link #init()} because the search box calls it on every keystroke, and init
	 * would replace the box being typed into.</p>
	 */
	private void rebuildRows() {
		for (Button button : rowButtons) {
			removeWidget(button);
		}
		rowButtons.clear();
		shown.clear();
		shown.addAll(matches());

		int visible = visibleRows();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - visible)));
		for (int index = scroll; index < Math.min(shown.size(), scroll + visible); index++) {
			Row row = shown.get(index);
			int y = LIST_TOP + (index - scroll) * ROW_HEIGHT;
			boolean active = row.id().equals(config.activeSongId());
			rowButtons.add(addRenderableWidget(Button.builder(
					Component.literal(active ? "Resume" : "Open"), button -> open(row.id()))
				.bounds(width - 152, y + 4, 52, 20)
				.tooltip(Tooltip.create(Component.literal("Edit this song in the composer")))
				.build()));
			rowButtons.add(addRenderableWidget(
				Button.builder(Component.literal("Copy"), button -> duplicate(row))
					.bounds(width - 96, y + 4, 44, 20)
					.tooltip(Tooltip.create(Component.literal("Duplicate this song")))
					.build()));
			Button delete = addRenderableWidget(Button.builder(
					Component.literal("×").withStyle(ChatFormatting.RED), button -> confirmDelete(row))
				.bounds(width - 48, y + 4, 20, 20)
				.build());
			// Against the whole library, not the search: hiding the last song from view is not the
			// same as it being the last one there is.
			delete.active = rows.size() > 1;
			rowButtons.add(delete);
		}
	}

	/**
	 * Enter opens the top match, so a search can be answered without leaving the keyboard.
	 *
	 * <p>Only while something is actually typed. On an unsearched list Enter would be opening
	 * whichever song happens to be first, which is not a thing anyone meant to ask for.</p>
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
		if (enter && searchBox != null && !searchBox.getValue().isBlank() && !shown.isEmpty()) {
			open(shown.get(0).id());
			return true;
		}
		return super.keyPressed(event);
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
					FastNoteblocksConfig.get().forgetPlacementPosition(row.id());
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
		return String.format(Locale.ROOT, "%d notes - %d layer%s - %s - %s - %.2fx - %s",
			analysis.totalNotes(), song.layers().size(), song.layers().size() == 1 ? "" : "s",
			analysis.lengthLabel(), bpmLabel(song), song.speedQuarters() / 4.0,
			savedLabel(SongLibrary.modifiedAt(row.id())));
	}

	/**
	 * When a song was last saved, as long ago rather than as a date.
	 *
	 * <p>Put on the row because the default order is by it, and an order you cannot see the key for
	 * is one you have to take on trust. Relative because the question this answers is "is this the
	 * one I was working on", and "2 hours ago" answers it where a timestamp has to be compared
	 * against a clock first.</p>
	 */
	private static String savedLabel(long modifiedMillis) {
		if (modifiedMillis <= 0L) {
			return "never saved";
		}
		long minutes = Math.max(0L, (System.currentTimeMillis() - modifiedMillis) / 60_000L);
		if (minutes < 1L) {
			return "just now";
		}
		if (minutes < 60L) {
			return minutes + (minutes == 1L ? " minute ago" : " minutes ago");
		}
		long hours = minutes / 60L;
		if (hours < 24L) {
			return hours + (hours == 1L ? " hour ago" : " hours ago");
		}
		long days = hours / 24L;
		if (days < 30L) {
			return days + (days == 1L ? " day ago" : " days ago");
		}
		long months = days / 30L;
		return months < 12L
			? months + (months == 1L ? " month ago" : " months ago")
			: (days / 365L) + " year" + (days / 365L == 1L ? "" : "s") + " ago";
	}

	private static String bpmLabel(ComposerProject song) {
		return Math.round(60_000_000.0 / song.tempoMicrosPerQuarter()) + " BPM";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, "Songs", 8, 16, 0xFFFFFFFF, false);
		boolean searching = shown.size() != rows.size();
		String count = searching
			? shown.size() + " of " + rows.size() + " in " + SongLibrary.directory().getFileName()
			: rows.size() + " in " + SongLibrary.directory().getFileName();
		graphics.text(font, count, 8, 28, 0xFF8A9098, false);

		int visible = visibleRows();
		for (int index = scroll; index < Math.min(shown.size(), scroll + visible); index++) {
			Row row = shown.get(index);
			int y = LIST_TOP + (index - scroll) * ROW_HEIGHT;
			boolean active = row.id().equals(config.activeSongId());
			graphics.fill(8, y, width - 8, y + ROW_HEIGHT - 4, active ? 0x40FFFFFF : 0x25FFFFFF);
			graphics.text(font, row.song().name(), 14, y + 5, active ? 0xFFFFFFFF : 0xFFD8DCE1, false);

			SongAnalysis analysis = analyses.get(row.id());
			boolean ready = analysis.buildable();
			// Same wording as the composer's status line, shortened to what fits a list row. A song
			// that reads one thing here and another there is the drift this analysis exists to stop.
			String verdict = ready
				? "Minecraft ready - " + analysis.lanesNeeded() + " lane"
					+ (analysis.lanesNeeded() == 1 ? "" : "s")
				: String.join(", ", analysis.problems());
			graphics.text(font, summary(row), 14, y + 17, 0xFF8A9098, false);
			int verdictX = 14 + font.width(summary(row)) + 10;
			if (verdictX < width - 160) {
				graphics.text(font, verdict, verdictX, y + 17,
					ready ? 0xFF5AD46A : 0xFFFFAA00, false);
			}
		}
		if (shown.isEmpty()) {
			graphics.text(font, rows.isEmpty()
					? "No songs yet. Start one, or import a MIDI or NBS file from the composer."
					: "No song's name has all of those words in it.",
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
		int maximum = Math.max(0, shown.size() - visibleRows());
		int updated = Math.max(0, Math.min(maximum, scroll - (int)Math.signum(scrollY)));
		if (updated != scroll) {
			scroll = updated;
			rebuildRows();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		FastNoteblocksConfig.save();
		minecraft.gui.setScreen(parent);
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
