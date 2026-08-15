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
		assertEquals(plan.commands().get(plan.commands().size() - 1), signs.get(0),
			"the sign should be the last command, so nothing builds over it");
		return signs.get(0);
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
		Path songs = Path.of("run", "config", "fast-noteblocks", "songs");
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
