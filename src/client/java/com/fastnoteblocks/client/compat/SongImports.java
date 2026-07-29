package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.client.FastNoteblocksConfig;
import com.fastnoteblocks.client.composer.ComposerProject;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Every way a song can arrive from outside, in one place.
 *
 * <p>There are four now -- MIDI, NBS, a schematic file and a region of the world -- and they are
 * wanted from two screens: the composer, which replaces what it is showing, and the song library,
 * which adds to the list. Left in the composer they were reachable from one of those and not the
 * other, which is why the library had no way to start from an import at all.</p>
 *
 * <p>Each entry point only chooses and reads. What to do with the result is the caller's business,
 * because that is the part the two screens genuinely disagree about.</p>
 */
final class SongImports {
	private SongImports() {
	}

	/** A song that has been read in, and a line about how the reading went. */
	record Imported(ComposerProject project, String report) {
	}

	/** MIDI and NBS files, which are songs already and only need converting. */
	static void chooseMidiOrNbs(Screen returnTo, FastNoteblocksConfig config,
			Consumer<Imported> onDone) {
		Minecraft.getInstance().gui.setScreen(new FileBrowserScreen(returnTo, "Import MIDI or NBS",
			Path.of(config.importDirectory()), List.of(".mid", ".midi", ".nbs"),
			folder -> {
				config.setImportDirectory(folder.toString());
				FastNoteblocksConfig.save();
			},
			file -> readSongFile(returnTo, config, file.toString(), onDone)));
	}

	private static void readSongFile(Screen returnTo, FastNoteblocksConfig config, String path,
			Consumer<Imported> onDone) {
		try {
			if (path.toLowerCase(Locale.ROOT).endsWith(".nbs")) {
				NbsImporter.Inspection inspection = NbsImporter.inspect(path, config);
				if (inspection.instruments().size() > ComposerProject.MAX_LAYERS) {
					// Too many instruments to become layers, so which ones matter is a question
					// only the person importing can answer.
					Minecraft.getInstance().gui.setScreen(new NbsInstrumentSelectionScreen(
						returnTo, inspection,
						selected -> readSelectedNbs(returnTo, config, path, selected, onDone)));
					return;
				}
				NbsImporter.ProjectResult result = NbsImporter.importProject(path, config);
				onDone.accept(new Imported(result.project(), result.report()));
				return;
			}
			MidiImporter.ProjectResult result = MidiImporter.importProject(path, config);
			onDone.accept(new Imported(result.project(), result.report()));
		} catch (Exception failed) {
			showFailure(returnTo, failed);
		}
	}

	private static void readSelectedNbs(Screen returnTo, FastNoteblocksConfig config, String path,
			Set<String> instruments, Consumer<Imported> onDone) {
		try {
			NbsImporter.ProjectResult result = NbsImporter.importProject(path, config, instruments);
			onDone.accept(new Imported(result.project(), result.report()));
		} catch (Exception failed) {
			showFailure(returnTo, failed);
		}
	}

	/** A saved build, read back into a song by following its redstone. */
	static void chooseSchematic(Screen returnTo, FastNoteblocksConfig config,
			Consumer<Imported> onDone) {
		Minecraft.getInstance().gui.setScreen(new FileBrowserScreen(returnTo, "Import a schematic",
			Path.of(config.importDirectory()), SchematicReader.EXTENSIONS,
			folder -> {
				config.setImportDirectory(folder.toString());
				FastNoteblocksConfig.save();
			},
			file -> readSchematic(returnTo, file, onDone)));
	}

	private static void readSchematic(Screen returnTo, Path path, Consumer<Imported> onDone) {
		try {
			SchematicReader.Schematic schematic = SchematicReader.load(path);
			String name = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
			NoteMachineReader.Reading reading = NoteMachineReader.read(name, BlockPos.ZERO,
				schematic.size().offset(-1, -1, -1), schematic::at);
			onDone.accept(new Imported(reading.project(),
				schematic.format() + " - " + reading.report()));
		} catch (Exception failed) {
			showFailure(returnTo, failed);
		}
	}

	/** A machine standing in the world, read back the same way a schematic is. */
	static void scanWorld(Screen returnTo, Consumer<Imported> onDone) {
		Minecraft.getInstance().gui.setScreen(new RegionScanScreen(returnTo,
			(from, to) -> readRegion(returnTo, from, to, onDone)));
	}

	private static void readRegion(Screen returnTo, BlockPos from, BlockPos to,
			Consumer<Imported> onDone) {
		try {
			NoteMachineReader.Reading reading = NoteMachineReader.read("World scan", from, to,
				Minecraft.getInstance().level::getBlockState);
			onDone.accept(new Imported(reading.project(), reading.report()));
		} catch (Exception failed) {
			showFailure(returnTo, failed);
		}
	}

	/**
	 * Puts an import in front of the player as a document with no file yet.
	 *
	 * <p>Nothing is written. An import used to go straight into the library, which meant deciding
	 * not to keep it still left it there to be deleted by hand -- and a song you have to go and
	 * tidy up is not a song you declined. It saves when told to, and because it has no file behind
	 * it, saving writes a new one instead of over whatever was open before.</p>
	 */
	static void open(Screen composerParent, FastNoteblocksConfig config, Runnable onReturn,
			Imported imported) {
		// Deliberately no config write either. The settings file carries a copy of whatever is being
		// edited, so saving it here would put most of a megabyte of imported song into the file that
		// holds every setting in the mod -- the exact coupling songs were moved out of it to avoid.
		// Left alone, the settings still point at the song that was open, so nothing is lost by
		// declining the import and nothing is written by accepting it until Save says so.
		config.openWorkingCopy(imported.project()
			.withSpeedQuarters(ComposerProject.DEFAULT_SPEED_QUARTERS));
		ComposerScreen opened = new ComposerScreen(composerParent, config, onReturn);
		opened.showResult(Component.literal(
			"Imported \"" + imported.project().name() + "\" - " + imported.report()
				+ ". Not saved yet."));
		Minecraft.getInstance().gui.setScreen(opened);
	}

	static void showFailure(Screen returnTo, Exception failure) {
		Minecraft.getInstance().gui.setScreen(new ConfirmScreen(
			confirmed -> Minecraft.getInstance().gui.setScreen(returnTo),
			Component.literal("Song import failed"),
			Component.literal(failure.getMessage() == null
				? failure.getClass().getSimpleName()
				: failure.getMessage()),
			CommonComponents.GUI_BACK, CommonComponents.GUI_CANCEL));
	}
}
