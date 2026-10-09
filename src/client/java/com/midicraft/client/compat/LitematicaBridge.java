package com.midicraft.client.compat;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/**
 * Litematica, asked for the one thing midicraft wants of it: load a file and place it.
 *
 * <p>Reached by name rather than compiled against. Litematica is optional -- most players will not
 * have it -- and its classes are not an API it promises to keep: they move between its versions as
 * the game's do. So nothing here is touched unless it is installed, and if a version has moved
 * something, the answer is a sentence saying so rather than a crash. The file is written first and
 * is fine either way; only the loading is lost.</p>
 *
 * <p>Four calls, the ones its own Load Schematics screen makes: {@code SchematicHolder.getOrLoad}
 * reads the file, {@code SchematicPlacement.createFor} puts it at a position, and the placement
 * manager's {@code addSchematicPlacement} adds it and selects it, saying so in Litematica's own
 * words.</p>
 */
final class LitematicaBridge {
	private LitematicaBridge() {
	}

	static boolean installed() {
		return FabricLoader.getInstance().isModLoaded("litematica");
	}

	/**
	 * Loads a {@code .litematic} and places it with its corner at {@code origin}.
	 *
	 * @param kept whether Litematica keeps the placement with the rest of the world's placements, so
	 *     it is still there next time. A preview is not kept: its file is gone the moment it has been
	 *     read, and a placement saved against a file that no longer exists would only come back as an
	 *     error. Litematica's own flag for this is {@code SchematicPlacement.setShouldBeSaved}.
	 * @return {@code null} once it is placed, or why it could not be
	 */
	static String loadAndPlace(Path file, BlockPos origin, String name, boolean kept) {
		try {
			ClassLoader loader = LitematicaBridge.class.getClassLoader();
			Class<?> holderClass = Class.forName("fi.dy.masa.litematica.data.SchematicHolder", true,
				loader);
			Class<?> schematicClass = Class.forName(
				"fi.dy.masa.litematica.schematic.LitematicaSchematic", true, loader);
			Class<?> placementClass = Class.forName(
				"fi.dy.masa.litematica.schematic.placement.SchematicPlacement", true, loader);
			Class<?> dataClass = Class.forName("fi.dy.masa.litematica.data.DataManager", true,
				loader);

			Object holder = holderClass.getMethod("getInstance").invoke(null);
			Object schematic = holderClass.getMethod("getOrLoad", Path.class).invoke(holder, file);
			if (schematic == null) {
				return "Litematica could not read the file";
			}
			Object placement = placementClass.getMethod("createFor", schematicClass, BlockPos.class,
					String.class, boolean.class, boolean.class)
				.invoke(null, schematic, origin, name, true, true);
			if (!kept) {
				placementClass.getMethod("setShouldBeSaved", boolean.class).invoke(placement, false);
			}
			Object manager = dataClass.getMethod("getSchematicPlacementManager").invoke(null);
			manager.getClass().getMethod("addSchematicPlacement", placementClass, boolean.class)
				.invoke(manager, placement, true);
			return null;
		} catch (InvocationTargetException failed) {
			Throwable cause = failed.getCause();
			return "Litematica stopped with " + (cause == null ? "an error"
				: cause.getClass().getSimpleName()
					+ (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException mismatch) {
			return "this Litematica does not load files the way midicraft expects";
		}
	}
}
