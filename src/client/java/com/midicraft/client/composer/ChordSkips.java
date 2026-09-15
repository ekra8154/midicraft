package com.midicraft.client.composer;

import com.midicraft.client.composer.ComposerProject.Layer;
import com.midicraft.client.composer.ComposerProject.NoteEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a song leaves out to keep every chord inside the thinning target, without deleting a note.
 *
 * <p>Only what was added on top of the written notes is ever left out: a sustain strike, and the
 * extra copies of a counted voice. A harp stacked ten times still asks for ten everywhere, and on a
 * tick where ten would push the chord over the target it plays as many as fit. Every other tick
 * plays as written, and nothing is stored: like the strikes themselves, this is worked out from the
 * song whenever the song is heard, measured or built, so preview and the machine agree.</p>
 *
 * <p>The order, on a tick that is over, each step only where {@link Rules} allows it:</p>
 * <ol>
 *   <li>Volume first, because a copy less is the least a chord can lose: copies go one at a time
 *   from whichever voice has the most left, strikes before written notes on a tie -- so a harp at
 *   ten and a bass at five meet in the middle rather than the bass going first. No voice goes below
 *   one.</li>
 *   <li>Then whole strikes, since a held note still rings from the strike before. A note never loses
 *   two strikes in a row, so no held note goes quiet for longer than one step. The strike picked is
 *   the smallest that clears the excess, or the biggest when none does. A strike that stands in for a
 *   duplicate dedupe dropped from another layer is never skipped, since leaving it out would silence
 *   that note too.</li>
 * </ol>
 * <p>A tick whose written notes alone are over the target is left over, in {@link #stillOver()}:
 * that is a chord for the chord thinner, which asks before deleting anything.</p>
 */
public final class ChordSkips {
	/** Nothing left out. */
	public static final ChordSkips NONE = new ChordSkips(Set.of(), Map.of(), Set.of(), Map.of(),
		Map.of(), Map.of(), Set.of(), 0, 0);

	/**
	 * What the chord limit may leave out, and above how many note blocks: the thinning settings.
	 *
	 * @param target the most note blocks a tick may hold before anything gives way
	 * @param volume whether a counted voice may play fewer copies on a crowded tick
	 * @param strikes whether a sustain strike may be skipped on a crowded tick
	 */
	public record Rules(int target, boolean volume, boolean strikes) {
		/** Nothing ever left out, whatever a chord holds. */
		public static final Rules NONE = new Rules(Integer.MAX_VALUE, true, true);

		/** Both kinds of thinning, at {@code target}. */
		public static Rules at(int target) {
			return new Rules(target, true, true);
		}
	}

	/** One sounding of one written note on one layer: the note itself, or one of its strikes. */
	private record Sounding(int layer, long noteId, long tick) {
	}

	/** One voice's copies at one sounding. */
	private record Copies(int layer, int voice, long noteId, long tick) {
	}

	private record VoiceKey(int layer, int voice) {
	}

	private record LayerNote(int layer, long noteId) {
	}

	/** A sound on a tick while the tick is being fitted. */
	private static final class Sound {
		final int layer;
		final int voice;
		final long noteId;
		final boolean strike;
		final int asked;
		int played;
		/** Stands in for an identical sound dedupe dropped from a later layer, so it is never skipped. */
		boolean covering;

		Sound(int layer, int voice, long noteId, boolean strike, int asked) {
			this.layer = layer;
			this.voice = voice;
			this.noteId = noteId;
			this.strike = strike;
			this.asked = asked;
			this.played = asked;
		}
	}

	private final Set<Sounding> skipped;
	private final Map<Copies, Integer> kept;
	private final Set<VoiceKey> reducedVoices;
	private final Map<Sounding, int[]> quieter;
	private final Map<LayerNote, Integer> skippedPerNote;
	private final Map<LayerNote, Integer> quieterStrikesPerNote;
	/** Every written note with any sounding left out or quieter, looked up once a note a frame. */
	private final Set<LayerNote> thinnedNotes;
	private final Set<Long> stillOver;
	private final int strikesSkipped;
	private final int blocksRemoved;

	private ChordSkips(Set<Sounding> skipped, Map<Copies, Integer> kept, Set<VoiceKey> reducedVoices,
			Map<Sounding, int[]> quieter, Map<LayerNote, Integer> skippedPerNote,
			Map<LayerNote, Integer> quieterStrikesPerNote, Set<Long> stillOver, int strikesSkipped,
			int blocksRemoved) {
		this.skipped = skipped;
		this.kept = kept;
		this.reducedVoices = reducedVoices;
		this.quieter = quieter;
		this.skippedPerNote = skippedPerNote;
		this.quieterStrikesPerNote = quieterStrikesPerNote;
		Set<LayerNote> notes = new HashSet<>(skippedPerNote.keySet());
		quieter.keySet().forEach(key -> notes.add(new LayerNote(key.layer(), key.noteId())));
		this.thinnedNotes = Set.copyOf(notes);
		this.stillOver = stillOver;
		this.strikesSkipped = strikesSkipped;
		this.blocksRemoved = blocksRemoved;
	}

	/**
	 * Fits every chord of a song's build to {@code target}, as the build flattens it: sustains
	 * expanded with {@code finest}, split and stacked layers expanded into voices, deduplication
	 * applied the way the build applies it, and only in-range notes counted.
	 *
	 * @param target the most note blocks a tick may hold; {@link Integer#MAX_VALUE} leaves everything
	 * @param finest what Finest is, or 0 for a song with no sustain
	 */
	public static ChordSkips of(ComposerProject project, boolean dedupeIdentical, int target,
			double finest) {
		return of(project, dedupeIdentical, Rules.at(target), finest);
	}

	/** The same, leaving out only what {@code rules} allows. */
	public static ChordSkips of(ComposerProject project, boolean dedupeIdentical, Rules rules,
			double finest) {
		int target = rules.target();
		if (target >= Integer.MAX_VALUE / 2) {
			return NONE;
		}
		TreeMap<Long, List<Sound>> byTick = new TreeMap<>();
		Set<ComposerProject.NoteSound> heard = dedupeIdentical ? new HashSet<>() : null;
		Map<ComposerProject.NoteSound, Sound> firstHeard = new HashMap<>();
		List<Layer> layers = project.layers();
		for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
			Layer layer = layers.get(layerIndex);
			if (!layer.inBuild()) {
				continue;
			}
			Layer placed = finest > 0.0 ? project.withSustainsExpanded(layer, finest) : layer;
			Map<Long, Long> writtenStart = placed == layer ? null : SongAnalysis.writtenStarts(layer);
			List<Layer> voices = placed.buildVoices();
			for (int voiceIndex = 0; voiceIndex < voices.size(); voiceIndex++) {
				Layer voice = voices.get(voiceIndex);
				int copies = voice.copies();
				for (NoteEvent note : voice.notes()) {
					// The build's own deduplication, in the build's order: a note it drops is not in
					// the chord at all.
					ComposerProject.NoteSound identity = heard != null && copies == 1
						? ComposerProject.NoteSound.of(voice, note) : null;
					if (identity != null && !heard.add(identity)) {
						// Dropped as a duplicate of a sound already placed, which now stands for both: leaving
						// that one out would silence this one too.
						Sound first = firstHeard.get(identity);
						if (first != null) {
							first.covering = true;
						}
						continue;
					}
					if (voice.pitched() && !note.isBuildable()) {
						continue;
					}
					boolean strike = writtenStart != null
						&& writtenStart.getOrDefault(note.id(), note.startTick()) != note.startTick();
					Sound placedSound = new Sound(layerIndex, voiceIndex, note.id(), strike, copies);
					if (identity != null) {
						firstHeard.put(identity, placedSound);
					}
					byTick.computeIfAbsent(note.startTick(), tick -> new ArrayList<>()).add(placedSound);
				}
			}
		}

		Set<Sounding> skipped = new HashSet<>();
		Map<Copies, Integer> kept = new HashMap<>();
		Set<VoiceKey> reducedVoices = new HashSet<>();
		Map<Sounding, int[]> quieter = new HashMap<>();
		Map<LayerNote, Integer> skippedPerNote = new HashMap<>();
		Map<LayerNote, Integer> quieterStrikesPerNote = new HashMap<>();
		Set<Long> stillOver = new TreeSet<>();
		Map<LayerNote, Boolean> lastSkipped = new HashMap<>();
		int strikesSkipped = 0;
		int blocksRemoved = 0;
		for (Map.Entry<Long, List<Sound>> entry : byTick.entrySet()) {
			long tick = entry.getKey();
			List<Sound> sounds = entry.getValue();
			Map<LayerNote, List<Sound>> strikes = new LinkedHashMap<>();
			int size = 0;
			for (Sound sound : sounds) {
				size += sound.played;
				if (sound.strike) {
					strikes.computeIfAbsent(new LayerNote(sound.layer, sound.noteId), key -> new ArrayList<>())
						.add(sound);
				}
			}
			Set<LayerNote> skippedHere = new HashSet<>();
			if (size > target) {
				// Volume first: a copy less is the least a chord can lose.
				while (rules.volume() && size > target) {
					Sound most = null;
					for (Sound sound : sounds) {
						if (sound.played > 1 && (most == null || louder(sound, most))) {
							most = sound;
						}
					}
					if (most == null) {
						break;
					}
					most.played--;
					size--;
					blocksRemoved++;
				}
				// Then strikes, where the copies were not enough or may not be touched.
				if (rules.strikes() && size > target) {
					List<LayerNote> candidates = new ArrayList<>();
					for (LayerNote key : strikes.keySet()) {
						if (!lastSkipped.getOrDefault(key, false)
								&& strikes.get(key).stream().noneMatch(sound -> sound.covering)) {
							candidates.add(key);
						}
					}
					while (size > target && !candidates.isEmpty()) {
						LayerNote pick = pickStrike(candidates, strikes, size - target);
						candidates.remove(pick);
						skippedHere.add(pick);
						int freed = 0;
						for (Sound sound : strikes.get(pick)) {
							freed += sound.played;
							sound.played = 0;
						}
						size -= freed;
						blocksRemoved += freed;
						strikesSkipped++;
						skipped.add(new Sounding(pick.layer(), pick.noteId(), tick));
						skippedPerNote.merge(pick, 1, Integer::sum);
					}
				}
				if (size > target) {
					stillOver.add(tick);
				}
				Map<Sounding, int[]> totals = new LinkedHashMap<>();
				Map<Sounding, Boolean> strikeSounding = new HashMap<>();
				for (Sound sound : sounds) {
					if (sound.played == 0) {
						continue;
					}
					Sounding at = new Sounding(sound.layer, sound.noteId, tick);
					int[] total = totals.computeIfAbsent(at, key -> new int[2]);
					total[0] += sound.asked;
					total[1] += sound.played;
					strikeSounding.put(at, sound.strike);
					if (sound.played < sound.asked) {
						kept.put(new Copies(sound.layer, sound.voice, sound.noteId, tick), sound.played);
						reducedVoices.add(new VoiceKey(sound.layer, sound.voice));
					}
				}
				for (Map.Entry<Sounding, int[]> total : totals.entrySet()) {
					if (total.getValue()[1] < total.getValue()[0]) {
						quieter.put(total.getKey(), total.getValue());
						if (strikeSounding.getOrDefault(total.getKey(), false)) {
							quieterStrikesPerNote.merge(
								new LayerNote(total.getKey().layer(), total.getKey().noteId()), 1, Integer::sum);
						}
					}
				}
			}
			for (LayerNote key : strikes.keySet()) {
				lastSkipped.put(key, skippedHere.contains(key));
			}
		}
		if (skipped.isEmpty() && kept.isEmpty() && stillOver.isEmpty()) {
			return NONE;
		}
		return new ChordSkips(Set.copyOf(skipped), Map.copyOf(kept), Set.copyOf(reducedVoices),
			Collections.unmodifiableMap(quieter), Map.copyOf(skippedPerNote),
			Map.copyOf(quieterStrikesPerNote), Collections.unmodifiableSet(stillOver), strikesSkipped,
			blocksRemoved);
	}

	/** The smallest strike that clears the excess, or the biggest when none does. Later layers first on a tie. */
	private static LayerNote pickStrike(List<LayerNote> candidates, Map<LayerNote, List<Sound>> strikes,
			int excess) {
		LayerNote clears = null;
		int clearsSize = Integer.MAX_VALUE;
		LayerNote biggest = null;
		int biggestSize = -1;
		for (LayerNote key : candidates) {
			int size = 0;
			for (Sound sound : strikes.get(key)) {
				size += sound.played;
			}
			if (size >= excess && (size < clearsSize || size == clearsSize && later(key, clears))) {
				clears = key;
				clearsSize = size;
			}
			if (size > biggestSize || size == biggestSize && later(key, biggest)) {
				biggest = key;
				biggestSize = size;
			}
		}
		return clears != null ? clears : biggest;
	}

	private static boolean later(LayerNote a, LayerNote b) {
		return b == null || a.layer() > b.layer() || a.layer() == b.layer() && a.noteId() > b.noteId();
	}

	/** Whether {@code a} gives up a copy before {@code b}: more left, then a strike, then later. */
	private static boolean louder(Sound a, Sound b) {
		if (a.played != b.played) {
			return a.played > b.played;
		}
		if (a.strike != b.strike) {
			return a.strike;
		}
		if (a.layer != b.layer) {
			return a.layer > b.layer;
		}
		if (a.voice != b.voice) {
			return a.voice > b.voice;
		}
		return a.noteId > b.noteId;
	}

	/** Whether this strike of a note is left out. Written notes never are. */
	public boolean skips(int layer, long noteId, long tick) {
		return !skipped.isEmpty() && skipped.contains(new Sounding(layer, noteId, tick));
	}

	/** How many of a voice's {@code asked} copies play at one sounding. */
	public int copiesAt(int layer, int voice, long noteId, long tick, int asked) {
		if (kept.isEmpty() || !reducedVoices.contains(new VoiceKey(layer, voice))) {
			return asked;
		}
		return kept.getOrDefault(new Copies(layer, voice, noteId, tick), asked);
	}

	/**
	 * One voice of one layer as the note blocks it places: its skipped strikes left out, and each
	 * copy holding only the soundings that copy still plays. What {@link Layer#asCopies} returns,
	 * thinned.
	 */
	public List<Layer> copiesOf(int layer, int voice, Layer projected) {
		Layer played = projected;
		if (!skipped.isEmpty()) {
			List<NoteEvent> notes = projected.notes().stream()
				.filter(note -> !skipped.contains(new Sounding(layer, note.id(), note.startTick())))
				.toList();
			if (notes.size() != projected.notes().size()) {
				played = projected.withNotes(notes);
			}
		}
		List<Layer> copies = played.asCopies();
		if (copies.size() < 2 || !reducedVoices.contains(new VoiceKey(layer, voice))) {
			return copies;
		}
		Layer once = copies.get(0);
		List<Layer> thinned = new ArrayList<>(copies.size());
		for (int copy = 0; copy < copies.size(); copy++) {
			int index = copy;
			int asked = copies.size();
			thinned.add(once.withNotes(once.notes().stream()
				.filter(note -> index < kept.getOrDefault(
					new Copies(layer, voice, note.id(), note.startTick()), asked))
				.toList()));
		}
		return thinned;
	}

	/** Whether any sounding of a written note is left out or played quieter. */
	public boolean thinned(int layer, long noteId) {
		return !thinnedNotes.isEmpty() && thinnedNotes.contains(new LayerNote(layer, noteId));
	}

	/** Note blocks asked for and played at one sounding that lost some, or null when it lost none. */
	public int[] playedAt(int layer, long noteId, long tick) {
		int[] total = quieter.get(new Sounding(layer, noteId, tick));
		return total == null ? null : total.clone();
	}

	/** How many of a note's strikes are left out. */
	public int skippedStrikes(int layer, long noteId) {
		return skippedPerNote.getOrDefault(new LayerNote(layer, noteId), 0);
	}

	/** How many of a note's strikes play with fewer copies than asked. */
	public int quieterStrikes(int layer, long noteId) {
		return quieterStrikesPerNote.getOrDefault(new LayerNote(layer, noteId), 0);
	}

	/** Every written note that is thinned anywhere, by id. */
	public Set<Long> thinnedNoteIds() {
		Set<Long> ids = new TreeSet<>();
		thinnedNotes.forEach(key -> ids.add(key.noteId()));
		return ids;
	}

	/** Ticks still over the target once everything that may be left out is: chords for the thinner. */
	public Set<Long> stillOver() {
		return stillOver;
	}

	public int strikesSkipped() {
		return strikesSkipped;
	}

	/** Note blocks left out altogether, strikes and copies together. */
	public int blocksRemoved() {
		return blocksRemoved;
	}

	/** Whether nothing is left out and nothing is still over. */
	public boolean isEmpty() {
		return this == NONE;
	}
}
