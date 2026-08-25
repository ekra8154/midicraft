package com.fastnoteblocks.client.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Which of the twenty voices can stand quietly beside a stacked centre, asked of the registry.
 *
 * <p>Three ways to be quiet and they are easy to confuse by eye: a harp is quiet because its block
 * is air, glass and glowstone because the game says they conduct nothing, and bass, bass drum and
 * the coppers because they have a half-block that plays the same voice. Everything else is a full
 * solid block whatever it looks like -- clay, wool and packed ice are not the same answer even
 * though two of them are see-through. See {@link SongBuilder#STACKED_SIDES_MAY_GO_QUIET}.</p>
 */
@Tag("sweep")
class QuietSideVoicesProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void namesTheVoicesThatCanGoQuiet() {
		for (PreviewInstrument instrument : PreviewInstrument.VALUES) {
			if (instrument.effect() != null) {
				continue;
			}
			String block = SongBuilder.instrumentBlockFor(instrument.id());
			if (block == null) {
				continue;
			}
			String slab = SongBuilder.INSTRUMENT_SLABS.get(block);
			boolean quiet = !SongBuilder.sideWouldConduct(block);
			System.out.println(String.format("VOICE %-16s %-34s %s%s",
				instrument.id(), block, quiet ? "QUIET" : "relays",
				slab == null ? "" : "  (as " + slab + ")"));
		}
	}
}
