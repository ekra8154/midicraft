package com.midicraft.client.compat;

import com.google.gson.Gson;
import com.midicraft.client.composer.ComposerProject;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Saves a song's build as a {@code .litematic} and a {@code .schem}, from the command line:
 *
 * <pre>
 *   gradlew exportBuild --args="Fireflies --out &lt;folder&gt;"
 *   gradlew exportBuild --args="Fireflies --out &lt;folder&gt; --width 24 --floors 2 --relay redstone_block"
 *   gradlew exportBuild --args="--help"
 * </pre>
 *
 * <p>Plans the song exactly as the Paste button would, from the settings in
 * {@code run/config/midicraft.json} (see {@link GameSettings}), with any of them overridden by a
 * flag. Then it hands the plan's commands to {@link SchematicWriter}. Nothing is built in a world.</p>
 *
 * <p>Lives with the tests because that is where the game can be started without a client, and
 * where the paste settings are already read the game's way. The writer it calls is in the mod.</p>
 */
final class ExportBuild {
	private ExportBuild() {
	}

	/**
	 * The console as it was before the game started. Starting it routes standard out through the
	 * logger, which puts a timestamp and a thread name in front of every line of the summary.
	 */
	private static final PrintStream OUT = System.out;
	private static final PrintStream ERR = System.err;

	private static final Path SONGS = Path.of("run", "config", "midicraft", "songs");
	/** Where the plan is laid out. Only the shape is saved, so any spot would do. */
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

	private static final String USAGE = """
		Saves a song's build as <name>.litematic and <name>.schem.

		  gradlew exportBuild --args="<song> --out <folder> [options]"

		<song>  a song file, a file name in run/config/midicraft/songs, or the song's name
		        (or a unique part of either)

		Every option defaults to the paste settings in run/config/midicraft.json.
		  --out <folder>        where the two files go (required)
		  --title <title>       the name on the build's sign (default: the song's name)
		  --name <name>         file name, without extension (default: the title and size)
		  --mode <layout>       paste layout by name, e.g. interleaved, ultra_compact_lane
		  --width <n>           lane width
		  --floors <n>          floors per lane
		  --max-floors <n>      floor cap for the cube layouts
		  --start top|bottom    which floor the lanes start on
		  --style plain|light-show
		                        light show swaps chord blocks for redstone lamps
		  --lane-one <block>    palette: machine one's lane
		  --lane-two <block>    palette: machine two's lane
		  --relay <block>       palette: blocks that carry the signal (must conduct)
		  --transparent <block> palette: blocks the climbing wire runs over
		  --support <block>     palette: the level under a floor
		  --thin <block>        palette: lanes packed two apart
		  --author <name>       written into both files (default "midicraft")

		@<file> in place of everything reads the arguments from that file, one a line, which
		saves quoting a folder with spaces in it through Gradle.
		""";

	/** Palette flags to the role they fill and the config key they default from. */
	private static final Map<String, LaneMaterials.Role> PALETTE_FLAGS = new LinkedHashMap<>();

	static {
		PALETTE_FLAGS.put("lane-one", LaneMaterials.Role.LANE);
		PALETTE_FLAGS.put("lane-two", LaneMaterials.Role.LANE);
		PALETTE_FLAGS.put("relay", LaneMaterials.Role.RELAY);
		PALETTE_FLAGS.put("transparent", LaneMaterials.Role.TRANSPARENT);
		PALETTE_FLAGS.put("support", LaneMaterials.Role.SUPPORT);
		PALETTE_FLAGS.put("thin", LaneMaterials.Role.THIN);
	}

	/** The config keys the palette and the style are read from, as the game stores them. */
	private static final class StoredLook {
		String laneOneBlock;
		String laneTwoBlock;
		String relayBlock;
		String transparentBlock;
		String supportBlock;
		String thinLaneBlock;
		String colorCodedPaste;

		String block(String flag) {
			return switch (flag) {
				case "lane-one" -> laneOneBlock;
				case "lane-two" -> laneTwoBlock;
				case "relay" -> relayBlock;
				case "transparent" -> transparentBlock;
				case "support" -> supportBlock;
				case "thin" -> thinLaneBlock;
				default -> null;
			};
		}
	}

	public static void main(String[] args) throws Exception {
		try {
			run(args);
		} catch (IllegalArgumentException refused) {
			ERR.println(refused.getMessage());
			System.exit(2);
		}
	}

	private static void run(String[] args) throws IOException {
		if (args.length == 1 && args[0].startsWith("@")) {
			args = Files.readAllLines(Path.of(args[0].substring(1))).stream()
				.map(String::strip).filter(line -> !line.isEmpty()).toArray(String[]::new);
		}
		Map<String, String> flags = new LinkedHashMap<>();
		List<String> words = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--help") || args[i].equals("-h")) {
				OUT.print(USAGE);
				return;
			}
			if (args[i].startsWith("--")) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException(args[i] + " needs a value.\n\n" + USAGE);
				}
				flags.put(args[i].substring(2), args[++i]);
			} else {
				words.add(args[i]);
			}
		}
		if (words.size() != 1 || !flags.containsKey("out")) {
			throw new IllegalArgumentException(USAGE);
		}
		for (String flag : flags.keySet()) {
			if (!USAGE.contains("--" + flag + " ")) {
				throw new IllegalArgumentException("Unknown option --" + flag + "\n\n" + USAGE);
			}
		}

		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		Path songFile = song(words.getFirst());
		ComposerProject project = GameSettings.project(songFile);
		InstrumentSubstitution.Swapped swapped = InstrumentSubstitution.swap(project);
		if (swapped.layers() > 0) {
			OUT.println(InstrumentSubstitution.describe(swapped).getString());
			project = swapped.project();
		}

		GameSettings.Values game = GameSettings.get();
		StoredLook look = look();
		SongBuilder.PasteMode mode = flags.containsKey("mode") ? mode(flags.get("mode"))
			: SongBuilder.PasteMode.offered(game.pasteMode());
		int width = number(flags, "width", game.laneWidth());
		int floors = number(flags, "floors", game.laneFloors());
		int maxFloors = number(flags, "max-floors", game.maxBuildFloors());
		boolean startTop = flags.containsKey("start")
			? choice(flags.get("start"), "top", "bottom")
			: game.startTop();
		// The colour-coded paste's diagnostic setting builds a different, deliberately broken
		// machine, so a file for other people is never made with it: that setting is built plain.
		boolean lightShow = flags.containsKey("style")
			? choice(flags.get("style"), "light-show", "plain")
			: "LIGHT_SHOW".equals(look.colorCodedPaste);

		for (Map.Entry<String, LaneMaterials.Role> palette : PALETTE_FLAGS.entrySet()) {
			String flag = palette.getKey();
			String typed = flags.containsKey(flag) ? flags.get(flag) : look.block(flag);
			String block;
			if (flags.containsKey(flag)) {
				block = LaneMaterials.normalise(typed);
				String problem = LaneMaterials.problem(palette.getValue(), block);
				if (problem != null) {
					throw new IllegalArgumentException("--" + flag + " " + typed + ": " + problem);
				}
			} else {
				block = typed == null ? palette.getValue().fallback()
					: LaneMaterials.accepted(palette.getValue(), typed);
			}
			setPalette(flag, LaneMaterials.placed(palette.getValue(), block));
		}
		SongBuilder.DEBUG_PASTE = false;
		SongBuilder.MARK_SHAPES = false;
		SongBuilder.LIGHT_SHOW = lightShow;

		SongBuilder.BuildLimits limits = new SongBuilder.BuildLimits(maxFloors, width, floors,
			startTop, game.reseedDelay(), game.paceTolerance(), game.paceCatchUpTo());
		String title = flags.getOrDefault("title", project.name()).strip();
		SongBuilder.PastePlan plan = SongBuilder.createPastePlan(ORIGIN, game.notes(project, mode),
			mode, limits, SongBuilder.WalkStart.HEAD, title);
		SchematicWriter.Build build = SchematicWriter.fromCommands(plan.commands());

		boolean lanes = mode != SongBuilder.PasteMode.COMPACT_CUBE
			&& mode != SongBuilder.PasteMode.COMPACT;
		String size = lanes ? " " + width + "x" + floors : "";
		String name = flags.getOrDefault("name", fileSafe(title) + size);
		String described = mode.label() + (lanes ? ", " + width + " wide, " + floors
			+ (floors == 1 ? " floor" : " floors") : "") + (lightShow ? ", light show" : "")
			+ ". Made with midicraft.";
		String author = flags.getOrDefault("author", "midicraft");
		Path out = Path.of(flags.get("out"));
		Path litematic = out.resolve(name + ".litematic");
		Path schem = out.resolve(name + ".schem");
		SchematicWriter.writeLitematic(build, litematic, title, author, described);
		// The Paste button lays the build one block east of the player.
		SchematicWriter.writeSponge(build, schem, title, author, ORIGIN.west());

		BlockPos box = build.size();
		OUT.println(title + " (" + songFile.getFileName() + ")");
		OUT.println("  " + described);
		OUT.println("  " + box.getX() + " x " + box.getY() + " x " + box.getZ() + ", "
			+ build.solidBlocks() + " blocks");
		List<String> report = plan.report();
		if (!report.isEmpty()) {
			OUT.println("  This build has faults, the same ones the paste screen would show:");
			report.forEach(line -> OUT.println("    " + line));
		}
		OUT.println("  wrote " + litematic.toAbsolutePath());
		OUT.println("  wrote " + schem.toAbsolutePath());
	}

	/** A song by file, by file name in the library, or by name, whole or a unique part. */
	private static Path song(String asked) throws IOException {
		Path given = Path.of(asked);
		if (asked.toLowerCase(Locale.ROOT).endsWith(".json") && Files.isRegularFile(given)) {
			return given;
		}
		Path byId = SONGS.resolve(asked + ".json");
		if (Files.isRegularFile(byId)) {
			return byId;
		}
		String wanted = asked.toLowerCase(Locale.ROOT);
		Map<Path, String> names = new LinkedHashMap<>();
		try (Stream<Path> files = Files.list(SONGS)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				names.put(file, nameOf(file));
			}
		}
		List<Path> exact = names.keySet().stream()
			.filter(file -> names.get(file).equalsIgnoreCase(asked)).toList();
		if (exact.size() == 1) {
			return exact.getFirst();
		}
		List<Path> partial = names.keySet().stream()
			.filter(file -> names.get(file).toLowerCase(Locale.ROOT).contains(wanted)
				|| file.getFileName().toString().toLowerCase(Locale.ROOT).contains(wanted))
			.toList();
		if (partial.size() == 1) {
			return partial.getFirst();
		}
		if (partial.isEmpty()) {
			throw new IllegalArgumentException("No song matches \"" + asked + "\" in " + SONGS);
		}
		StringBuilder choices = new StringBuilder("\"" + asked + "\" matches "
			+ partial.size() + " songs; give the file name of the one you mean:");
		partial.forEach(file -> choices.append("\n  ")
			.append(file.getFileName().toString().replaceFirst("\\.json$", ""))
			.append("  (").append(names.get(file)).append(")"));
		throw new IllegalArgumentException(choices.toString());
	}

	private static String nameOf(Path file) {
		try (Reader reader = Files.newBufferedReader(file)) {
			ComposerProject song = new Gson().fromJson(reader, ComposerProject.class);
			return song == null || song.name() == null ? "" : song.name();
		} catch (IOException | RuntimeException unreadable) {
			return "";
		}
	}

	/** A layout by its name, or by the start of one when only one starts that way. */
	private static SongBuilder.PasteMode mode(String asked) {
		String wanted = asked.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for (SongBuilder.PasteMode mode : SongBuilder.PasteMode.values()) {
			if (mode.name().equals(wanted)) {
				return mode;
			}
		}
		List<SongBuilder.PasteMode> starting = Stream.of(SongBuilder.PasteMode.values())
			.filter(mode -> mode.name().startsWith(wanted)).toList();
		if (starting.size() == 1) {
			return starting.getFirst();
		}
		throw new IllegalArgumentException("--mode " + asked + " is not one layout. The layouts: "
			+ Stream.of(SongBuilder.PasteMode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT))
				.toList());
	}

	private static int number(Map<String, String> flags, String flag, int fallback) {
		String given = flags.get(flag);
		if (given == null) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(given.trim());
			if (value < 1) {
				throw new NumberFormatException();
			}
			return value;
		} catch (NumberFormatException notANumber) {
			throw new IllegalArgumentException("--" + flag + " wants a whole number of at least 1, "
				+ "not " + given);
		}
	}

	/** True for {@code yes}, false for {@code no}, refused otherwise. */
	private static boolean choice(String given, String yes, String no) {
		String wanted = given.trim().toLowerCase(Locale.ROOT);
		if (wanted.equals(yes)) {
			return true;
		}
		if (wanted.equals(no)) {
			return false;
		}
		throw new IllegalArgumentException("Wanted " + yes + " or " + no + ", not " + given);
	}

	private static void setPalette(String flag, String placed) {
		switch (flag) {
			case "lane-one" -> SongBuilder.LANE_ONE_BLOCK = placed;
			case "lane-two" -> SongBuilder.LANE_TWO_BLOCK = placed;
			case "relay" -> SongBuilder.RELAY_BLOCK = placed;
			case "transparent" -> SongBuilder.TRANSPARENT_BLOCK = placed;
			case "support" -> SongBuilder.SUPPORT_BLOCK = placed;
			case "thin" -> SongBuilder.THIN_LANE_BLOCK = placed;
			default -> throw new IllegalStateException(flag);
		}
	}

	private static StoredLook look() throws IOException {
		if (!Files.exists(GameSettings.CONFIG)) {
			return new StoredLook();
		}
		try (Reader reader = Files.newBufferedReader(GameSettings.CONFIG)) {
			StoredLook found = new Gson().fromJson(reader, StoredLook.class);
			return found == null ? new StoredLook() : found;
		}
	}

	/** A name Windows will take as a file name. */
	private static String fileSafe(String name) {
		String safe = name.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
		return safe.isEmpty() ? "build" : safe;
	}
}
