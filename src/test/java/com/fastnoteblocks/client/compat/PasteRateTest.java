package com.fastnoteblocks.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fastnoteblocks.client.PasteRate;
import org.junit.jupiter.api.Test;

/**
 * The rate, at the end where it is a wait rather than a count.
 *
 * <p>A quarter of a command a tick is the setting that matters on a server, and it is the one that
 * cannot be checked by looking at it: the sender spends whole commands, so the rate only exists as
 * the pattern of ticks it sends on. These count that pattern out.</p>
 */
class PasteRateTest {

	/** Ticks a rate for a while and says how many commands it let through. */
	private static int overTicks(double rate, int ticks) {
		double credit = 0;
		int sent = 0;
		for (int tick = 0; tick < ticks; tick++) {
			credit = CommandPasteSender.bank(credit, rate);
			while (credit >= 1) {
				credit--;
				sent++;
			}
		}
		return sent;
	}

	@Test
	void aQuarterIsOneCommandEveryFourTicks() {
		assertEquals(5, overTicks(0.25, 20));
		assertEquals(25, overTicks(0.25, 100));
	}

	@Test
	void aHalfIsOneCommandEveryOtherTick() {
		assertEquals(10, overTicks(0.5, 20));
	}

	@Test
	void oneIsOneATick() {
		assertEquals(20, overTicks(1, 20));
	}

	@Test
	void theRatesAboveOneAreUntouched() {
		assertEquals(640, overTicks(32, 20));
		assertEquals(5120, overTicks(256, 20));
	}

	/**
	 * The cap, which is what keeps a resumed paste from being a disconnect.
	 *
	 * <p>A pause at the edge of the world banks nothing beyond a single tick's worth, so walking
	 * back into range sends one tick of commands and not the two minutes it was owed.</p>
	 */
	@Test
	void doesNotBankMoreThanATickCanSpend() {
		double credit = 0;
		for (int tick = 0; tick < 2400; tick++) {
			credit = CommandPasteSender.bank(credit, 0.25);
		}

		assertTrue(credit <= 1, "two minutes of waiting banked " + credit + " commands, which "
			+ "would all go out on the tick the paste resumed");
	}

	@Test
	void theLadderHoldsTheThreeSlowRatesAndTheOldRange() {
		assertTrue(PasteRate.RATES.containsAll(
			java.util.List.of(0.25, 0.5, 1.0, 32.0, 256.0)));
		assertEquals(0.25, PasteRate.MIN);
		assertEquals(256, PasteRate.MAX);
	}

	/** Every press moves, including from a value that was set before the ladder existed. */
	@Test
	void stepsToTheNextRungAndNeverStandsStill() {
		assertEquals(0.5, PasteRate.step(0.25, 1));
		assertEquals(0.25, PasteRate.step(0.5, -1));
		assertEquals(24, PasteRate.step(20, 1));
		assertEquals(16, PasteRate.step(20, -1));
		assertEquals(256, PasteRate.step(256, 1));
		assertEquals(0.25, PasteRate.step(0.25, -1));
	}

	@Test
	void saysAWaitAsAWaitAndACountAsACount() {
		assertEquals("one command every 4 ticks", PasteRate.label(0.25));
		assertEquals("one command every 2 ticks", PasteRate.label(0.5));
		assertEquals("1 command per tick", PasteRate.label(1));
		assertEquals("32 commands per tick", PasteRate.label(32));
	}

	@Test
	void findsTheRungASliderShouldSitOn() {
		assertEquals(0, PasteRate.index(0.25));
		assertEquals(PasteRate.RATES.indexOf(32.0),
			PasteRate.index(PasteRate.DEFAULT));
		assertEquals(PasteRate.RATES.indexOf(24.0),
			PasteRate.index(23));
	}
}
