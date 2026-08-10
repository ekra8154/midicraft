# Handoff — stacked chords, parity and breaches

State at `cda0cc9`. Everything below was measured on ekran's own library, not derived.

## The rule this file keeps breaking

Three separate bugs this week were the same sentence: **fitting is not the same as being able
to leave.** A chord that fits before the wall can still leave the lane unable to turn, and every
place that asks "does it fit" while meaning "can the lane still get out" produces a breach that
looks unrelated to the shape that caused it.

- `roomAhead < stackedRoom` refused a stacked-bus at 12 columns and handed it a plain bus that
  wants 13. Fixed by `KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER`.
- `reachesWall` refused the turn for one block of wire, and the only way to get more wire is to
  lay a whole chord. Diagnosed, not fixed — see open item 1.
- `stackedSplitOf` refused the cut whenever the tail fitted before the wall, though it is only
  ever asked where the chord overshoots *including* the turn reserve. Half-fixed, off — item 1.

The second rule, older and just as expensive: **the planner and the walk must make the same
substitution.** `landingOf` and `addChordModule` are the pair. Every disagreement between them
has shown up as a lane coming to rest outside its wall, and the fix is always to route both
through one method rather than to restate the arithmetic in each.

## What changed this week

| commit | what |
|---|---|
| `6add997` | Relocation: a contested note moves, not the module. build → relocate → nudge → bus. |
| `39588a1` | A lone back flank hangs on the side the walk has *been*, leaving the next lane something to relocate. |
| `5dd6b6f` | `NUDGE_WHEN_BEHIND_BUSY` — stand a column off rather than lose the head entirely. |
| `54a7e7d` | Harp trade for the centre; a climb leaves the pair behind free. |
| `dc25226` | A descent is not a turn either. |
| `f5ae473` | `KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER` — the fallback has to be shorter than the shape it replaces. |
| `45df00b` | The post-turn back pair asks the two cells instead of assuming. |
| `cda0cc9` | `CUTS_A_CHORD_THAT_FITS` — half a fix, off, see item 1. |

Guardian, 50 configs (floors 2–6, widths 12–48), before this week vs after:

```
breach blocks   5,376 -> 1,808      worst breach   63 -> 19
builds pasting     45 -> 48         clean builds    5 -> 11
```

Cost: blocks +6%, spanZ +7.5%.

## Open, in priority order

**1. The breach of eleven: pre-pad chord 0 so its bus lands on the wall.** Fully diagnosed, not
built. Repro is `GuardianStackedTest.tracesTurnsFortyFourByThree` — Guardian 44x3 from `0 64 0`,
worst breach 11 at `1 68 84`:

```
t=628 notes=24 columns=1  tip=5 wait=1 pad=0c/5s turnCells=5 offBus=3
      room=1 couldSplit=false reachesWall=false canTurn=false
t=629 notes=11 columns=-11 tip=5                 reachesWall=true  -> turns
```

The lane stands one column short of the wall with five blocks of wire, needing one for the column
and five for the climb. No cut is available at `room=1` (a head is two columns and a transition;
even the unheaded form wants `room >= 2`), and `planPad` lays nothing because its dust-only fallback
runs `while (signal > turnCells)` and five is not more than five. A repeater would refresh the run,
but a repeater costs a tick and `wait = 1` leaves none. So the chord of 24 is laid whole, twelve
columns, and that is the breach.

**ekran's fix, and it is exact.** The chord *before* this one — chord 0 — ends one column short of
the wall. Pad one column in *front of chord 0* and its bus ends flush on the wall instead. Chord 0
opens with its own repeater, so its tip is unchanged; what changes is that the climb now starts
straight off a bus. `turnCost` drops from `turnCells = 5` to `offBus = 3`, `unpaid` becomes 0, and
`reachesWall` is `5 >= 3`. The lane turns on its wall with wire to spare and the breach is gone.
The column spent on the pad is paid back many times over by the eleven not breached.

So the planner must be willing to pad a chord that **already fits**, when landing flush is what buys
the discount. Where to look: `closes` already models the discount in its `room == 0` branch
(`tip >= (buses ? offBus : turnCells)`) but its `room != 0` branch only asks whether the *next* chord
can cut — it never asks whether a column of pad would make `room == 0` and close the lane that way.
`book` distributes owed columns into `sweep.room()`, so the machinery to pay for it exists; what is
missing is `closes` offering the option at all.

**6. `CUTS_A_CHORD_THAT_FITS` needs its other half.** `SongBuilder.java` → `stackedSplitOf`.
The cut is written and works; the near half is then however long the chord happened to be rather
than long enough to reach the wall, so the lane hands over short and its staircase stands where no
other lane's does. ekran's missing piece: **pad the near half out to the wall first, then cut.**
`planPad` is the other side. On Guardian 44×3 the cut alone is breaches 3 → 5. The two are worth
nothing apart.

**2. Four wrong notes.** `UltraLaneFaultsTest.reportsHowManyBuildsHaveAWrongNoteInThem` went
0 → 4 of 360 builds when `KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER` went in. Worst is a note belonging to
tick 418 that would sound again at 425 from the block south of it. Keeping more heads means more
stacked modules and more parity contention; something in that is not being asked. **By this file's
own `betterThan` ordering a wrong note outranks every breach number above.** ekran's call was to
fix it without taking the behaviour away.

**3. `BigSplitTest` dead run.** A run of 16 dust and 1,287 unreached notes at f2 w20, caused by
`NUDGE_WHEN_BEHIND_BUSY`. The shift's pad is a cell of dust *before* the module's repeater, so it
spends the incoming signal — and `landingOf` is not handed the signal, so the planner cannot refuse
the shift the way the walk's `outOfWire` test does. Threading the signal into `landingOf` is the fix.

**4. The library A/B has never been run.** `RelocationTest` is written and waiting.

**5. The turn ban.** 122 stacked-buses become plain buses on Guardian because they are near a
corner, each handing on two blocks less wire than the head would. The rule is deferred rather than
physical, and this is a second and much larger cost than the columns it was being judged on.

## Traps

- **`main` is red and was already red.** Baseline at `d9f84d5` is **7 failing tests**. Diff names,
  never counts. New since: `BigSplitTest` ×2 (item 3).
- **`BlitzSweepTest` only builds `ULTRA_COMPACT_LANE`.** A green sweep says nothing about the other
  four paste modes, and `walkWall` is shared. Run `gradlew cleanTest test` before committing;
  `gradlew sweepTest` is the measurement, not the test.
- **Measure the built song, not the layers.** ekran has dedupe on, so
  `SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true))` is the right call — builds are
  up to 16% smaller than the layers suggest.
- **`run/` is not in the repo.** The song library every probe reads is local only.
- **Don't revert a red change.** ekran diagnoses in world, and reverting breaks that loop.
  Leave it flagged with the numbers instead.
- **`backPairIsFree` / `BACK_PAIR_ASKS_THE_BLOCKS`** appeared in `SongBuilder.java` mid-session and
  I did not write them. They do the right thing and are wired into both call sites, so I built on
  them — but they are worth a read rather than an assumption.

## Probes worth knowing

- `GuardianStackedTest` — decision census, collision marks, the 44×3 chord trace, the width/floor sweep.
- `HammerBusTest` — every reason a stacked shape was given up, per build.
- `TRACE_TURNS` — one line per chord standing outside the footprint, with the wire it has.
  This is what found the long-breach mechanism after three sessions of reading the code had not.
- `MARK_COLLISIONS` — builds through a collision and lights it up in sea lantern, with the shape
  that laid each block. Ground truth for anything about occupancy.
