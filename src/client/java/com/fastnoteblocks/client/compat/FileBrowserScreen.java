package com.fastnoteblocks.client.compat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.SongLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Picks a file from disk without leaving the game.
 *
 * <p>This replaces a native open dialog, which sounds like the obvious thing to use and is not. A
 * native dialog blocks the render thread until it is answered, and over an exclusive-fullscreen GL
 * window Windows hides it: the dialog holds the keyboard and the game behind it stops drawing, so
 * there is nothing on screen to answer and no way to reach it. Leaving fullscreen around it worked
 * but made every import flicker the whole display. A screen has none of those problems, and it can
 * do things the OS dialog could not -- open where you were last time, and put the newest file at
 * the top, which is nearly always the one you just downloaded.</p>
 */
final class FileBrowserScreen extends Screen {
	private static final int ROW_HEIGHT = 16;
	private static final int LIST_TOP = 84;
	private static final DateTimeFormatter MODIFIED =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final Screen parent;
	private final List<String> extensions;
	private final Consumer<Path> chosen;
	private final Consumer<Path> rememberDirectory;

	private final List<Entry> entries = new ArrayList<>();
	private Path directory;
	private EditBox filterBox;
	private int scroll;
	private int selected = -1;
	private String status = "";

	/**
	 * @param extensions lower-case, leading dot, e.g. {@code ".mid"}
	 * @param rememberDirectory told where the browse ended, so the next one can start there
	 */
	FileBrowserScreen(
		Screen parent,
		String title,
		Path start,
		List<String> extensions,
		Consumer<Path> rememberDirectory,
		Consumer<Path> chosen
	) {
		super(Component.literal(title));
		this.parent = parent;
		this.extensions = List.copyOf(extensions);
		this.chosen = chosen;
		this.rememberDirectory = rememberDirectory;
		this.directory = firstReadable(start);
	}

	/** The nearest ancestor of {@code start} that exists, so a stale saved folder still opens. */
	private static Path firstReadable(Path start) {
		for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
			if (Files.isDirectory(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		return Path.of("").toAbsolutePath();
	}

	private record Entry(Path path, boolean folder, String name, String detail) {
	}

	@Override
	protected void init() {
		clearWidgets();
		listDirectory();

		String filter = filterBox == null ? "" : filterBox.getValue();
		int sortWidth = 92;
		filterBox = new EditBox(font, 8, 36, width - 16 - sortWidth - 4, 18,
			Component.literal("Filter"));
		filterBox.setMaxLength(260);
		filterBox.setValue(filter);
		filterBox.setResponder(value -> {
			scroll = 0;
			selected = -1;
			clampScroll();
		});
		addRenderableWidget(filterBox);
		addRenderableWidget(Button.builder(Component.literal(sortLabel()), button -> {
				FastNoteblocksConfig config = FastNoteblocksConfig.get();
				config.setListSortByName(!config.listSortByName());
				FastNoteblocksConfig.save();
				scroll = 0;
				selected = -1;
				init();
			})
			.bounds(width - 8 - sortWidth, 36, sortWidth, 18).build());

		addRenderableWidget(Button.builder(Component.literal("Up"), button -> enter(directory.getParent()))
			.bounds(8, 58, 34, 18).build())
			.active = directory.getParent() != null;
		int shortcutX = 46;
		for (Shortcut shortcut : shortcuts()) {
			int shortcutWidth = font.width(shortcut.label()) + 12;
			addRenderableWidget(Button.builder(Component.literal(shortcut.label()),
					button -> enter(shortcut.path()))
				.bounds(shortcutX, 58, shortcutWidth, 18).build());
			shortcutX += shortcutWidth + 4;
		}

		addRenderableWidget(Button.builder(Component.literal("Open"), button -> openSelected())
			.bounds(width - 160, height - 26, 72, 18).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(width - 84, height - 26, 76, 18).build());
		setInitialFocus(filterBox);
	}

	private static String sortLabel() {
		return FastNoteblocksConfig.get().listSortByName() ? "Sort: A to Z" : "Sort: newest";
	}

	private void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, Math.max(0, matches().size() - visibleRows())));
	}

	private record Shortcut(String label, Path path) {
	}

	/**
	 * The few folders worth one click.
	 *
	 * <p>Ours first, since it is the one place a file is there on purpose. Downloads earns second:
	 * a MIDI arrives there and is imported once, which is the whole life of the file.</p>
	 */
	private List<Shortcut> shortcuts() {
		List<Shortcut> found = new ArrayList<>();
		found.add(new Shortcut("Import folder", SongLibrary.importDirectory()));
		Path home = Path.of(System.getProperty("user.home", "."));
		for (String name : new String[] {"Downloads", "Desktop", "Documents", "Music"}) {
			Path candidate = home.resolve(name);
			if (Files.isDirectory(candidate)) {
				found.add(new Shortcut(name, candidate));
			}
		}
		if (minecraft != null) {
			found.add(new Shortcut("Game folder", minecraft.gameDirectory.toPath()));
		}
		return found;
	}

	private void listDirectory() {
		entries.clear();
		status = "";
		List<Entry> folders = new ArrayList<>();
		List<Entry> files = new ArrayList<>();
		try (Stream<Path> listing = Files.list(directory)) {
			for (Path entry : listing.toList()) {
				String name = entry.getFileName().toString();
				if (name.startsWith(".")) {
					continue;
				}
				if (Files.isDirectory(entry)) {
					folders.add(new Entry(entry, true, name, ""));
				} else if (matchesExtension(name)) {
					files.add(new Entry(entry, false, name, describe(entry)));
				}
			}
		} catch (IOException | RuntimeException unreadable) {
			status = "Cannot read this folder: " + unreadable.getMessage();
		}
		// Folders are always alphabetical. Their dates are about whatever was last written inside
		// them, which is not a fact about the folder and is no help in finding one.
		folders.sort(Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
		// Files default to newest first: the one you are looking for is nearly always the one that
		// arrived most recently, and a Downloads folder sorted by name is a wall of strangers.
		files.sort(FastNoteblocksConfig.get().listSortByName()
			? Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT))
			: Comparator.comparingLong(FileBrowserScreen::modifiedAt).reversed());
		if (directory.getParent() == null) {
			for (Path root : FileSystems.getDefault().getRootDirectories()) {
				if (!root.equals(directory) && Files.isDirectory(root)) {
					entries.add(new Entry(root, true, root.toString(), "drive"));
				}
			}
		}
		entries.addAll(folders);
		entries.addAll(files);
	}

	private static long modifiedAt(Entry entry) {
		try {
			return Files.getLastModifiedTime(entry.path()).toMillis();
		} catch (IOException unreadable) {
			return 0L;
		}
	}

	private boolean matchesExtension(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return extensions.stream().anyMatch(lower::endsWith);
	}

	private static String describe(Path file) {
		try {
			long size = Files.size(file);
			FileTime modified = Files.getLastModifiedTime(file);
			String readable = size >= 1024L * 1024L
				? String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0)
				: String.format(Locale.ROOT, "%d KB", Math.max(1L, size / 1024L));
			return readable + "   " + MODIFIED.format(modified.toInstant());
		} catch (IOException | RuntimeException unreadable) {
			return "";
		}
	}

	private List<Entry> matches() {
		String filter = filterBox == null ? "" : filterBox.getValue().trim().toLowerCase(Locale.ROOT);
		if (filter.isEmpty()) {
			return entries;
		}
		return entries.stream()
			.filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(filter))
			.toList();
	}

	private int visibleRows() {
		return Math.max(1, (height - 44 - LIST_TOP) / ROW_HEIGHT);
	}

	private void enter(Path folder) {
		if (folder == null || !Files.isDirectory(folder)) {
			return;
		}
		directory = folder.toAbsolutePath().normalize();
		scroll = 0;
		selected = -1;
		if (filterBox != null) {
			filterBox.setValue("");
		}
		init();
	}

	private void openSelected() {
		List<Entry> visible = matches();
		if (selected < 0 || selected >= visible.size()) {
			return;
		}
		Entry entry = visible.get(selected);
		if (entry.folder()) {
			enter(entry.path());
			return;
		}
		rememberDirectory.accept(directory);
		minecraft.gui.setScreen(parent);
		chosen.accept(entry.path());
	}

	/**
	 * Files dragged onto the window from the desktop.
	 *
	 * <p>Minecraft installs a GLFW drop callback and hands whatever lands to whichever screen is
	 * open, so this needs nothing but the override. It is the shortest route there is between a file
	 * you have just downloaded and a song: no navigating to the folder, no remembering where the
	 * browser was last pointed.</p>
	 *
	 * <p>A dropped folder is opened rather than refused, since dragging one here plainly means "look
	 * in that". A dropped file of the wrong kind says so instead of doing nothing, because a drop
	 * that is silently ignored is indistinguishable from one the window never received.</p>
	 */
	@Override
	public void onFilesDrop(List<Path> dropped) {
		if (dropped == null || dropped.isEmpty()) {
			return;
		}
		for (Path path : dropped) {
			if (Files.isDirectory(path)) {
				enter(path);
				return;
			}
		}
		List<Path> usable = dropped.stream()
			.filter(Files::isRegularFile)
			.filter(path -> matchesExtension(path.getFileName().toString()))
			.toList();
		if (usable.isEmpty()) {
			status = dropped.size() == 1
				? "That is not a " + String.join(" or ", extensions) + " file."
				: "None of those " + dropped.size() + " files is a "
					+ String.join(" or ", extensions) + ".";
			return;
		}
		// One song at a time, because opening one is what the caller asked for and a queue of them
		// would need somewhere to wait that this screen does not have.
		Path file = usable.getFirst();
		if (usable.size() > 1) {
			status = "Opening " + file.getFileName() + "; the other "
				+ (usable.size() - 1) + " were left.";
		}
		Path folder = file.toAbsolutePath().getParent();
		if (folder != null) {
			rememberDirectory.accept(folder);
		}
		minecraft.gui.setScreen(parent);
		chosen.accept(file);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		List<Entry> visible = matches();
		int row = ((int)event.y() - LIST_TOP) / ROW_HEIGHT + scroll;
		if (event.y() >= LIST_TOP && event.y() < LIST_TOP + visibleRows() * ROW_HEIGHT
				&& row >= 0 && row < visible.size()) {
			// A folder opens on one click because stepping into the wrong one costs nothing, and a
			// file needs two because choosing one replaces what you are working on.
			boolean reselected = selected == row;
			selected = row;
			if (visible.get(row).folder() || doubleClick || reselected) {
				openSelected();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		List<Entry> visible = matches();
		switch (event.key()) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				// A pasted path is a path, not a filter -- people paste them, so take the hint.
				if (jumpToTypedPath()) {
					return true;
				}
				openSelected();
				return true;
			}
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (filterBox != null && filterBox.getValue().isEmpty()) {
					enter(directory.getParent());
					return true;
				}
			}
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
				if (!visible.isEmpty()) {
					int step = event.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1;
					selected = Math.max(0, Math.min(visible.size() - 1, selected + step));
					scroll = Math.max(0, Math.max(Math.min(scroll, selected),
						selected - visibleRows() + 1));
					return true;
				}
			}
			default -> {
				// Everything else belongs to the filter box.
			}
		}
		return super.keyPressed(event);
	}

	/** Navigates to whatever was typed, if it is a real path. */
	private boolean jumpToTypedPath() {
		String typed = filterBox == null ? "" : filterBox.getValue().trim();
		if (typed.length() < 2) {
			return false;
		}
		try {
			Path path = Path.of(typed);
			if (Files.isDirectory(path)) {
				enter(path);
				return true;
			}
			if (Files.isRegularFile(path) && matchesExtension(path.getFileName().toString())) {
				if (path.getParent() != null) {
					rememberDirectory.accept(path.getParent());
				}
				minecraft.gui.setScreen(parent);
				chosen.accept(path);
				return true;
			}
		} catch (InvalidPathException notAPath) {
			return false;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int maximum = Math.max(0, matches().size() - visibleRows());
		int updated = Math.max(0, Math.min(maximum, scroll - (int)Math.signum(scrollY) * 3));
		if (updated != scroll) {
			scroll = updated;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, title.getString(), 8, 12, 0xFFFFFFFF, false);
		graphics.text(font, elide(directory.toString(), width - 16), 8, 24, 0xFF8A9098, false);

		List<Entry> visible = matches();
		int rows = visibleRows();
		for (int index = scroll; index < Math.min(visible.size(), scroll + rows); index++) {
			Entry entry = visible.get(index);
			int y = LIST_TOP + (index - scroll) * ROW_HEIGHT;
			if (index == selected) {
				graphics.fill(8, y - 2, width - 8, y + ROW_HEIGHT - 4, 0x40FFFFFF);
			}
			graphics.text(font, entry.folder() ? entry.name() + "/" : entry.name(), 14, y,
				entry.folder() ? 0xFF8FD3FF : 0xFFD8DCE1, false);
			if (!entry.detail().isEmpty()) {
				graphics.text(font, entry.detail(),
					width - 14 - font.width(entry.detail()), y, 0xFF8A9098, false);
			}
		}

		if (visible.isEmpty() && status.isEmpty()) {
			graphics.text(font, entries.isEmpty()
					? "No " + String.join(" or ", extensions) + " files here. Drag one onto the "
						+ "window from anywhere, or browse to where they are."
					: "Nothing matches that filter.",
				14, LIST_TOP, 0xFF8A9098, false);
		}
		String footer = status.isEmpty()
			? visible.size() + " item" + (visible.size() == 1 ? "" : "s")
				+ "   -   double-click a file, drag one in from anywhere, "
				+ "or type a path and press Enter"
			: status;
		graphics.text(font, footer, 8, height - 42, status.isEmpty() ? 0xFF8A9098 : 0xFFFF6B6B, false);
	}

	private String elide(String text, int available) {
		if (font.width(text) <= available) {
			return text;
		}
		String elided = text;
		while (elided.length() > 1 && font.width("..." + elided) > available) {
			elided = elided.substring(1);
		}
		return "..." + elided;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
