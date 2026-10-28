package com.midicraft.client.compat;

import com.midicraft.client.MidicraftConfig;
import com.midicraft.client.mixin.SoundEngineAccessor;
import com.midicraft.client.mixin.SoundManagerAccessor;
import com.mojang.blaze3d.audio.Channel;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Pauses Minecraft's music while a composer song is playing, and gives it back from the same place.
 *
 * <p>Composer notes play on the UI source, so only MUSIC channels are touched. Vanilla can pause by
 * source but only resumes everything at once, which would wake world sounds the game is holding
 * under a paused singleplayer world, so the channels are paused and unpaused here directly.</p>
 *
 * <p>Re-applied every tick while held rather than once on the way in: the game's own unpause
 * resumes every channel, and anything that starts a music track while the song plays would
 * otherwise be heard over it.</p>
 */
public final class ComposerMusicPause {
	private static boolean holding;

	private ComposerMusicPause() {
	}

	/** Whether the music is being held for a composer song right now. */
	public static boolean holding() {
		return holding;
	}

	public static void tick(Minecraft minecraft) {
		boolean hold = MidicraftConfig.get().pauseMusicDuringPlayback() && ComposerScreen.songSounding();
		if (hold) {
			forEachMusicChannel(minecraft, Channel::pause);
		} else if (holding) {
			forEachMusicChannel(minecraft, Channel::unpause);
		}
		holding = hold;
	}

	private static void forEachMusicChannel(Minecraft minecraft, Consumer<Channel> action) {
		var engine = ((SoundManagerAccessor) minecraft.getSoundManager()).midicraft$soundEngine();
		((SoundEngineAccessor) engine).midicraft$instanceToChannel().forEach((instance, handle) -> {
			if (instance.getSource() == SoundSource.MUSIC) {
				handle.execute(action);
			}
		});
	}
}
