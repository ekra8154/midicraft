package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The roll answers "is that note mine" with brightness, and it has to answer it every time.
 *
 * <p>Two things vary a note's colour and both of them used to do it by lightness: {@code shade}
 * tells the members of one family apart -- a converted song splits a part into a layer per octave
 * shift, so families of three and four are ordinary -- and {@code faded}/{@code vivid} tell a
 * selected layer from an unselected one. On a real song, "a dark zone 3", that collision put a
 * selected layer's notes <em>dimmer</em> than an unselected layer's, and there is nothing to look at
 * that would explain it: the colours are right, they are just the wrong way round.</p>
 *
 * <p>So the two are banded, and this is the guarantee. It is asserted over the whole palette rather
 * than over the colours that happened to break, because the next palette entry or the next shade
 * step is exactly how it would come back.</p>
 */
class LayerColorBandsTest {
	/** As many steps as {@code shade} distinguishes; past this it clamps to the last one. */
	private static final int FAMILY_MEMBERS = 5;

	@Test
	void everySelectedColourOutshinesEveryUnselectedOne() {
		double dimmestSelected = Double.MAX_VALUE;
		double brightestUnselected = -1.0;
		int dimmestBase = 0;
		int dimmestStep = 0;
		int brightestBase = 0;
		int brightestStep = 0;
		for (int base : ComposerScreen.LAYER_COLORS) {
			for (int member = 0; member < FAMILY_MEMBERS; member++) {
				int shaded = ComposerScreen.shade(0xFF000000 | base, member);
				double selected = ComposerScreen.luma(ComposerScreen.vivid(shaded));
				double unselected = ComposerScreen.luma(ComposerScreen.faded(shaded));
				if (selected < dimmestSelected) {
					dimmestSelected = selected;
					dimmestBase = base;
					dimmestStep = member;
				}
				if (unselected > brightestUnselected) {
					brightestUnselected = unselected;
					brightestBase = base;
					brightestStep = member;
				}
			}
		}
		assertTrue(dimmestSelected > brightestUnselected, String.format(java.util.Locale.ROOT,
			"the dimmest selected colour (%06X member %d, luma %.1f) must still outshine the "
				+ "brightest unselected one (%06X member %d, luma %.1f)",
			dimmestBase, dimmestStep, dimmestSelected,
			brightestBase, brightestStep, brightestUnselected));
	}

	/** The band is a floor and a ceiling, so neither side may wander out of its own half. */
	@Test
	void neitherSideLeavesItsBand() {
		for (int base : ComposerScreen.LAYER_COLORS) {
			for (int member = 0; member < FAMILY_MEMBERS; member++) {
				int shaded = ComposerScreen.shade(0xFF000000 | base, member);
				double selected = ComposerScreen.luma(ComposerScreen.vivid(shaded));
				double unselected = ComposerScreen.luma(ComposerScreen.faded(shaded));
				assertTrue(selected >= ComposerScreen.SELECTED_LUMA_FLOOR - 1.0,
					String.format(java.util.Locale.ROOT, "%06X member %d selected: luma %.1f",
						base, member, selected));
				assertTrue(unselected <= ComposerScreen.UNSELECTED_LUMA_CEILING + 1.0,
					String.format(java.util.Locale.ROOT, "%06X member %d unselected: luma %.1f",
						base, member, unselected));
			}
		}
	}

	/**
	 * Lifting a dark member must not spend its hue doing it.
	 *
	 * <p>Mixing toward white reaches the floor and arrives grey -- cyan's darkest member lost two
	 * thirds of its saturation on the way -- and a layer you cannot recognise by colour is the thing
	 * colours are for. Scaling the channels holds the ratios instead.</p>
	 */
	@Test
	void liftingADarkMemberKeepsItsHue() {
		for (int base : ComposerScreen.LAYER_COLORS) {
			int darkest = ComposerScreen.shade(0xFF000000 | base, 3);
			double before = saturation(darkest);
			double after = saturation(ComposerScreen.vivid(darkest));
			assertTrue(after >= before * 0.6, String.format(java.util.Locale.ROOT,
				"%06X: saturation fell from %.2f to %.2f lifting its darkest member into the band",
				base, before, after));
		}
	}

	private static double saturation(int color) {
		int red = color >> 16 & 0xFF;
		int green = color >> 8 & 0xFF;
		int blue = color & 0xFF;
		int high = Math.max(red, Math.max(green, blue));
		int low = Math.min(red, Math.min(green, blue));
		return high == 0 ? 0.0 : (high - low) / (double)high;
	}
}
