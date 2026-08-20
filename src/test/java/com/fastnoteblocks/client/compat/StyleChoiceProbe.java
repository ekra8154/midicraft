package com.fastnoteblocks.client.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What shape a chord is given, and which test refused the ones before it.
 *
 * <p>{@link SongBuilder#chooseStyle} settles a chord's shape at grouping time and says nothing
 * about why. That is fine until a chord comes out as a shape nobody expected, and then there is no
 * way to ask it anything -- the walk is a thousand lines downstream and every census key it prints
 * is about a shape being <em>dropped</em>, which is a different question from a shape never being chosen.
 *
 * <p>A chord of four at {@code am-i-dreaming} 12 wide over seven floors: three harps and a bass,
 * laid as a sunken bus where it should have been a stacked chord. Reading the method said
 * it should be. This asks it instead.
 *
 * <p>Reflective because the whole decision is private, and it should stay private -- a probe that
 * forces a method open is better than a method opened for a probe.
 */
@Tag("sweep")
class StyleChoiceProbe {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SongBuilder.EventNote note(int pitch, String instrument) {
		return new SongBuilder.EventNote(0, 0, 0, pitch, instrument);
	}

	private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
		Method method = SongBuilder.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(null, args);
	}

	/** {@code SongBuilder.Layout} is a private record, so it is built the way the builder builds it. */
	private static Object ultraV2() throws Exception {
		Class<?> layout = Class.forName("com.fastnoteblocks.client.compat.SongBuilder$Layout");
		Method ultra = layout.getDeclaredMethod("ultra", int.class, net.minecraft.core.BlockPos.class);
		ultra.setAccessible(true);
		Object made = ultra.invoke(null, 7, new net.minecraft.core.BlockPos(0, 64, 0));
		Method asV2 = layout.getDeclaredMethod("asV2");
		asV2.setAccessible(true);
		return asV2.invoke(made);
	}

	@Test
	void saysWhatThatChordOfFourIsGiven() throws Exception {
		Class<?> layoutType = Class.forName("com.fastnoteblocks.client.compat.SongBuilder$Layout");
		Object layout = ultraV2();
		List<SongBuilder.EventNote> chord = new ArrayList<>(List.of(
			note(18, "minecraft:air"),
			note(3, "minecraft:oak_planks"),
			note(3, "minecraft:air"),
			note(10, "minecraft:air")));
		System.out.println("==== the chord in question: 18C harp, 03A bass, 03A harp, 10E harp ====");
		for (boolean roomBehind : new boolean[] {true, false}) {
			Object style = call("chooseStyle",
				new Class<?>[] {layoutType, List.class, boolean.class},
				layout, chord, roomBehind);
			System.out.println("   roomBehind=" + roomBehind + "  ->  " + style);
		}
		System.out.println("   fitsSmallModule = " + call("fitsSmallModule",
			new Class<?>[] {List.class}, chord));
		System.out.println("   hasEffect       = " + call("hasEffect",
			new Class<?>[] {List.class}, chord));
		for (int back : new int[] {0, 1, 2}) {
			System.out.println("   ultraSlots(back=" + back + ") = " + call("ultraSlots",
				new Class<?>[] {List.class, int.class}, chord, back));
		}
		// And each note's own answer to the two questions the slots are filled by, because "three
		// harps and a bass" is a description of the music and not of what the redstone can use.
		for (SongBuilder.EventNote one : chord) {
			Class<?> noteType = Class.forName("com.fastnoteblocks.client.compat.SongBuilder$EventNote");
			System.out.println("   " + one.pitch() + " " + one.instrumentBlock()
				+ "  harp=" + call("isHarpNote", new Class<?>[] {noteType}, one)
				+ "  conductsSideways=" + call("conductsSideways", new Class<?>[] {noteType}, one));
		}
	}
}
