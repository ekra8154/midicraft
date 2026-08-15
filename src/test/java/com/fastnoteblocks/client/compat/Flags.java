package com.fastnoteblocks.client.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The builder's switches, set by name from the command line.
 *
 * <p>Nearly every question asked of this codebase is "what does the build look like with that flag
 * the other way", and the answer has always cost an edit, a rebuild and a revert -- which is three
 * chances to leave the tree holding an experiment. There are over a hundred of these booleans and
 * new ones arrive with every shape, so a probe that wants to price one against another either grows
 * an arm per flag or cannot ask.</p>
 *
 * <pre>
 * gradlew sweepTest --tests "*FaultCensusProbe" -Dcensus.set=NOTE_BLOCK_MIDDLE_ALWAYS_BUSES=true
 * gradlew sweepTest --tests "*FaultProbeTest" -Dfault.set=SIMPLE_TAIL_ON_A_STACKED_BUS=false
 * </pre>
 *
 * <p>Restored by {@link Held#putBack()} in a finally, because a static left flipped is a probe that
 * quietly reports someone else's build -- and the whole test JVM is shared.</p>
 */
final class Flags {
	private Flags() {
	}

	/** What was set, and what it was before, so the run can put it back. */
	record Held(Map<String, Object> was) {
		void putBack() {
			was.forEach((name, value) -> {
				try {
					field(name).set(null, value);
				} catch (ReflectiveOperationException impossible) {
					throw new IllegalStateException("could not put " + name + " back", impossible);
				}
			});
		}

		/**
		 * What was asked for, for the heading of whatever printed the run.
		 *
		 * <p>Said even when nothing was flipped, and that is the point of it. A run whose flag never
		 * arrived -- a prefix the build does not forward, a property typed into the wrong task --
		 * prints a full page of numbers for the build it meant to change, and every one of them is
		 * right for a build nobody asked about.</p>
		 */
		String said() {
			if (was.isEmpty()) {
				return " [flags: none]";
			}
			List<String> each = new ArrayList<>();
			was.forEach((name, before) -> {
				try {
					each.add(name + " " + before + "->" + field(name).get(null));
				} catch (ReflectiveOperationException impossible) {
					each.add(name);
				}
			});
			return " [flags: " + String.join(", ", each) + "]";
		}
	}

	private static Field field(String name) throws ReflectiveOperationException {
		Field found = SongBuilder.class.getDeclaredField(name.strip());
		found.setAccessible(true);
		if (found.getType() != boolean.class && found.getType() != int.class) {
			throw new IllegalArgumentException(name + " is a " + found.getType()
				+ ", and only booleans and ints can be set this way");
		}
		return found;
	}

	/** {@code true}/{@code false} for a switch, a number for a threshold. */
	private static Object read(Field field, String given) {
		return field.getType() == int.class ? Integer.valueOf(given.strip())
			: Boolean.valueOf(given.strip());
	}

	/**
	 * Applies {@code NAME=true,OTHER=false}, or nothing at all for a blank.
	 *
	 * <p>Throws on a name that is not a flag rather than skipping it. A probe run with a typo in the
	 * flag it meant to flip prints a full page of numbers for the build it was trying to change, and
	 * nothing about that page says the experiment never happened.</p>
	 */
	static Held set(String given) {
		Map<String, Object> was = new LinkedHashMap<>();
		if (given == null || given.isBlank()) {
			return new Held(was);
		}
		List<String> failed = new ArrayList<>();
		for (String pair : given.split(",")) {
			String[] half = pair.split("=");
			if (half.length != 2) {
				failed.add(pair + " is not NAME=true, NAME=false or NAME=<number>");
				continue;
			}
			try {
				Field field = field(half[0]);
				was.put(half[0].strip(), field.get(null));
				field.set(null, read(field, half[1]));
			} catch (ReflectiveOperationException | IllegalArgumentException notAFlag) {
				failed.add(half[0] + ": " + notAFlag.getMessage());
			}
		}
		if (!failed.isEmpty()) {
			new Held(was).putBack();
			throw new IllegalArgumentException("no such flag on SongBuilder -- " + failed);
		}
		return new Held(was);
	}
}
