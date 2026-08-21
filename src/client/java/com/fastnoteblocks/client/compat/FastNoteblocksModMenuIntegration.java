package com.fastnoteblocks.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * The button beside the mod in Mod Menu's list.
 *
 * <p>A second door to {@link SettingsScreen}, not the only one -- the Composer's own Settings tab
 * opens the same screen. This used to hand back a Cloth Config panel, and between them those two
 * optional mods were the only way to change a setting at all.</p>
 */
public final class FastNoteblocksModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
