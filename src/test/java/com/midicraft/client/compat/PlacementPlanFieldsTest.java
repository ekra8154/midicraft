package com.midicraft.client.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every field of the placement plan is either the world or the current walker's context, and the
 * plan says which.
 *
 * <p>Under the joint walk two machines take turns on one plan, and the walker context is swapped at
 * every handover from the list the plan keeps of it. A field added without being classified would
 * be shared by both walkers by accident -- one machine's soft tip read by the other -- and nothing
 * in a census would name the cause. So this fails the build instead.</p>
 */
class PlacementPlanFieldsTest {
	@SuppressWarnings("unchecked")
	private static Set<String> named(Class<?> plan, String set) throws ReflectiveOperationException {
		Field field = plan.getDeclaredField(set);
		field.setAccessible(true);
		return (Set<String>) field.get(null);
	}

	@Test
	void everyFieldIsWorldOrWalker() throws ReflectiveOperationException {
		Class<?> plan = Class.forName("com.midicraft.client.compat.SongBuilder$PlacementPlan");
		Set<String> walker = named(plan, "WALKER_FIELDS");
		Set<String> world = named(plan, "WORLD_FIELDS");
		Set<String> empty = named(plan, "EMPTY_AT_HANDOFF_FIELDS");
		Set<String> declared = new TreeSet<>();
		for (Field field : plan.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
				declared.add(field.getName());
			}
		}
		Set<String> classified = new HashSet<>(walker);
		classified.addAll(world);
		classified.addAll(empty);
		Set<String> unclassified = new TreeSet<>(declared);
		unclassified.removeAll(classified);
		assertTrue(unclassified.isEmpty(), "PlacementPlan fields not named as world or walker "
			+ "context (see WALKER_FIELDS): " + unclassified);
		Set<String> gone = new TreeSet<>(classified);
		gone.removeAll(declared);
		assertTrue(gone.isEmpty(), "names in the field lists that PlacementPlan no longer has: " + gone);
		Set<String> twice = new TreeSet<>(walker);
		twice.retainAll(world);
		assertTrue(twice.isEmpty(), "fields named as both world and walker: " + twice);
		assertEquals(declared.size(), walker.size() + world.size() + empty.size(),
			"a field is in more than one list");
	}
}
