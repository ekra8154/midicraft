package com.fastnoteblocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NotePitchTest {
	@Test
	void vanillaPitchNamesCoverTheFullRange() {
		assertEquals("F♯", NotePitch.name(0));
		assertEquals("G", NotePitch.name(1));
		assertEquals("G♯", NotePitch.name(2));
		assertEquals("F", NotePitch.name(23));
		assertEquals("F♯", NotePitch.name(24));
	}

	@Test
	void gFamilyMatchesTheRequestedSequence() {
		assertEquals(1, NotePitch.nextInFamily(0, 'G'));
		assertEquals(2, NotePitch.nextInFamily(1, 'G'));
		assertEquals(13, NotePitch.nextInFamily(2, 'G'));
		assertEquals(14, NotePitch.nextInFamily(13, 'G'));
	}

	@Test
	void forwardClicksWrapAtTheEndOfTheVanillaRange() {
		assertEquals(1, NotePitch.clicksForward(24, 0));
		assertEquals(14, NotePitch.clicksForward(0, 14));
	}

	@Test
	void reverseFamilySelectionStillUsesForwardVanillaClicks() {
		assertEquals(14, NotePitch.previousInFamily(0, 'G'));
		assertEquals(14, NotePitch.clicksForward(0, NotePitch.previousInFamily(0, 'G')));
	}
}
