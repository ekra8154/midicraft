package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.composer.ComposerProject;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The sign that says which song a corridor is.
 *
 * <p>Three things have to hold and none of them is obvious from reading the code. The command has to
 * parse, because it is the only one in a build carrying block entity data and a build that dies on
 * its last command dies after thirty thousand blocks are already down. The name has to survive being
 * folded onto four short lines, quotes and all. And the cell the sign goes in has to be empty in
 * real songs, not merely in the one that was tried -- a sign built over a note block would be a
 * silent note nobody could ever account for.</p>
 */
class TitleSignTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The lines as they read, with the quoting the command needs taken back off. */
	private static List<String> plain(String title) {
		return SongBuilder.signLines(title).stream()
			.map(quoted -> quoted.substring(1, quoted.length() - 1)
				.replace("\\\"", "\"").replace("\\\\", "\\"))
			.toList();
	}

	@Test
	void aShortNameGoesOnOneLine() {
		assertEquals(List.of("Big Shot", "", "", ""), plain("Big Shot"));
	}

	@Test
	void aNameWithNoNameIsStillASign() {
		assertEquals(List.of("Unnamed", "composition", "", ""), plain(null));
		assertEquals(List.of("Unnamed", "composition", "", ""), plain("   "));
	}

	@Test
	void wordsStayWholeWhereTheyFit() {
		assertEquals(List.of("all of the", "lights kanye", "west", ""),
			plain("all of the lights kanye west"));
	}

	/** A word longer than a line is split, because the alternative is a line left empty. */
	@Test
	void aWordTooLongForALineIsHyphenated() {
		List<String> lines = plain("Supercalifragilisticexpialidocious");
		System.out.println("SIGN " + lines);
		assertTrue(lines.get(0).endsWith("-"), "the first line should carry on: " + lines);
		assertEquals("Supercalifragilisticexpialidocious",
			String.join("", lines).replace("-", ""), "the word came back wrong: " + lines);
	}

	/** And a name too long for the whole sign says so rather than looking like a shorter name. */
	@Test
	void aNameTooLongForTheSignEndsInAnEllipsis() {
		List<String> lines = plain("Deltarune Chapter Four Guardian Of The Sunset Of Seven Suns");
		System.out.println("SIGN " + lines);
		assertTrue(lines.get(SongBuilder.SIGN_LINES - 1).endsWith("..."),
			"a trimmed title should say so: " + lines);
	}

	@Test
	void noLineIsWiderThanASign() {
		Stream.of("Big Shot", null, "all of the lights kanye west",
				"Supercalifragilisticexpialidocious",
				"Deltarune Chapter Four Guardian Of The Sunset Of Seven Suns",
				"a \"quoted\" name with a \\ in it")
			.forEach(title -> plain(title).forEach(line ->
				assertTrue(line.length() <= SongBuilder.SIGN_LINE, "too wide for a sign: '" + line + "'")));
	}

	/**
	 * That the command the build ends on is one the game will accept.
	 *
	 * <p>Parsed with the same parser {@code /setblock} uses, and with block entity data allowed,
	 * which is the whole question: every other command in a build is a block and a state, and this
	 * one carries four lines of text that came from a player naming a file.</p>
	 */
	@Test
	void theSignCommandParses() throws Exception {
		for (String title : new String[] {"Big Shot", null, "illit do the dance",
				"a \"quoted\" name with a \\ in it",
				"Deltarune Chapter Four Guardian Of The Sunset Of Seven Suns"}) {
			String command = sign(SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
				DebugChords.notes(DebugChords.parse("6 2 18", DebugChords.DEFAULT_GAP)),
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, 16, 2), SongBuilder.WalkStart.HEAD, title));
			System.out.println("SIGNCOMMAND " + command);
			// Everything after the coordinates, which is where the parser starts.
			String[] parts = command.split(" ", 5);
			String block = parts[4].substring(0, parts[4].length() - " replace".length());
			BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, block, true);
			assertTrue(command.length() < 256,
				"a command longer than chat allows would be dropped: " + command.length());
		}
	}

	private static String sign(SongBuilder.PastePlan plan) {
		List<String> signs = plan.commands().stream()
			.filter(command -> command.contains("wall_sign")).toList();
		assertEquals(1, signs.size(), "a build should carry exactly one sign");
		return signs.get(0);
	}

	/**
	 * That the sign goes up with the start of the song rather than after the last block of it.
	 *
	 * <p>A wide build is tens of thousands of commands and has to be flown along as it goes up, so a
	 * sign written last is written when the start of the song is long out of simulation range -- the
	 * command is sent, nothing is there to receive it, and the build has no name. It has to land
	 * within the first breath of the paste, and no earlier than the block it hangs on.</p>
	 */
	@Test
	void theSignGoesUpWithTheStartOfTheSong() throws Exception {
		Path songs = Path.of("run", "config", "midicraft", "songs");
		List<String> late = new ArrayList<>();
		for (String file : List.of("illit-do-the-dance.json", "deltarune-ch-4-guardian.json",
				"all-of-the-lights-kanye-west.json", "big-shot.json")) {
			List<SongBuilder.EventNote> notes;
			try (Reader reader = Files.newBufferedReader(songs.resolve(file))) {
				ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
				ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
					raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
					raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
				notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
			}
			SongBuilder.PastePlan plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
				SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
				new SongBuilder.BuildLimits(16, 40, 3), SongBuilder.WalkStart.HEAD, file);
			int where = -1;
			for (int index = 0; index < plan.commands().size(); index++) {
				if (plan.commands().get(index).contains("wall_sign")) {
					where = index;
				}
			}
			System.out.println("SIGNAT " + file + " command " + where + " of "
				+ plan.commands().size());
			// A hundred blocks is a handful of chords, and every build here is thirty thousand.
			if (where < 0 || where > 100) {
				late.add(file + " at command " + where + " of " + plan.commands().size());
			}
		}
		assertTrue(late.isEmpty(), "the sign is written too late to be in range: " + late);
	}

	/**
	 * That the sign never lands on a face the build wanted, whichever of the three it takes.
	 *
	 * <p>The whole reason there are three candidates rather than one. Placing it early means it is
	 * no longer last, so it can no longer rely on being the final word on a cell -- if it ever picks
	 * an occupied one, it now overwrites a block of the machine outright.</p>
	 */
	@Test
	void theSignNeverStandsWhereTheBuildDoes() throws Exception {
		Path songs = Path.of("run", "config", "midicraft", "songs");
		List<String> clashes = new ArrayList<>();
		int checked = 0;
		try (var listing = Files.list(songs)) {
			for (Path file : listing.filter(path -> path.toString().endsWith(".json")).toList()) {
				List<SongBuilder.EventNote> notes;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
						raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
						raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
					notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
				} catch (RuntimeException unreadable) {
					continue;
				}
				if (notes.isEmpty()) {
					continue;
				}
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(16, 40, 3), SongBuilder.WalkStart.HEAD,
						file.getFileName().toString());
				} catch (RuntimeException refused) {
					continue;
				}
				checked++;
				String signAt = null;
				List<String> others = new ArrayList<>();
				for (String command : plan.commands()) {
					String[] parts = command.split(" ", 5);
					String cell = parts[1] + " " + parts[2] + " " + parts[3];
					if (parts[4].startsWith("minecraft:oak_wall_sign")) {
						signAt = cell;
					} else {
						others.add(cell);
					}
				}
				if (signAt == null || others.contains(signAt)) {
					clashes.add(file.getFileName() + " sign at " + signAt);
				}
			}
		}
		System.out.println("SIGNCLASH " + clashes.size() + " of " + checked + " songs");
		assertTrue(checked > 20, "the song library did not load: " + checked);
		assertTrue(clashes.isEmpty(), "the sign stands on a block of the machine: " + clashes);
	}

	/** The size a build was made at, which is the one thing you cannot recover by looking at it. */
	@Test
	void theSignSaysWhatSizeTheBuildWasMadeAt() throws Exception {
		String command = sign(SongBuilder.createPastePlan(new BlockPos(0, 64, 0),
			DebugChords.notes(DebugChords.parse("6 2 18", DebugChords.DEFAULT_GAP)),
			SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
			new SongBuilder.BuildLimits(16, 40, 3), SongBuilder.WalkStart.HEAD, "Big Shot"));
		System.out.println("SIGNSIZE " + command);
		assertTrue(command.contains("\"40|3 Big Shot\""), "no size on the sign: " + command);
	}

	/**
	 * That no real song puts the sign where a block of the machine wanted to be.
	 *
	 * <p>The head of a build has nothing behind it, which is the reason this face was chosen, and
	 * "nothing behind it" is a claim about every layout rather than about one. If it ever stops
	 * holding, the sign is silently dropped rather than built over the machine -- so the thing to
	 * check is that it is not being dropped.</p>
	 */
	@Test
	void everySongGetsItsSign() throws Exception {
		Path songs = Path.of("run", "config", "midicraft", "songs");
		List<String> missing = new ArrayList<>();
		int built = 0;
		try (var listing = Files.list(songs)) {
			for (Path file : listing.filter(path -> path.toString().endsWith(".json")).toList()) {
				List<SongBuilder.EventNote> notes;
				try (Reader reader = Files.newBufferedReader(file)) {
					ComposerProject raw = new Gson().fromJson(reader, ComposerProject.class);
					ComposerProject project = new ComposerProject(raw.name(), raw.ppq(),
						raw.tempoMicrosPerQuarter(), raw.layers(), raw.activeLayerIndex(),
						raw.nextNoteId(), raw.endTick(), raw.speedQuarters());
					notes = SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true));
				} catch (RuntimeException unreadable) {
					continue;
				}
				if (notes.isEmpty()) {
					continue;
				}
				SongBuilder.PastePlan plan;
				try {
					plan = SongBuilder.createPastePlan(new BlockPos(0, 64, 0), notes,
						SongBuilder.PasteMode.ULTRA_COMPACT_LANE,
						new SongBuilder.BuildLimits(16, 40, 3), SongBuilder.WalkStart.HEAD,
						file.getFileName().toString());
				} catch (RuntimeException refused) {
					continue;
				}
				built++;
				if (plan.commands().stream().noneMatch(command -> command.contains("wall_sign"))) {
					missing.add(file.getFileName().toString());
				}
			}
		}
		System.out.println("SIGNS " + (built - missing.size()) + "/" + built + " songs signed");
		assertTrue(built > 20, "the song library did not load: " + built + " builds");
		assertTrue(missing.isEmpty(), "no room for a sign on: " + missing);
	}
}
