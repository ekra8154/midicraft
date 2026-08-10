# Handoff — stacked chords, parity and breaches

State at `9c8d23a`. Everything below was measured on ekran's own library, not derived.

**Start with open item 1.** It is ekran's own words, verbatim, and it is the fourth appearance of
the one rule this whole session has been circling.

## The rule this file keeps breaking

Three separate bugs this week were the same sentence: **fitting is not the same as being able
to leave.** A chord that fits before the wall can still leave the lane unable to turn, and every
place that asks "does it fit" while meaning "can the lane still get out" produces a breach that
looks unrelated to the shape that caused it.

- `roomAhead < stackedRoom` refused a stacked-bus at 12 columns and handed it a plain bus that
  wants 13. **Fixed** — `KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER`.
- `reachesWall` refused the turn for one block of wire, because covering the last column with dust
  costs the off-bus discount too. **Fixed** — `PREPADS_FOR_THE_OFF_BUS_DISCOUNT`.
- `stackedSplitOf` refused the cut whenever the chord fitted before the wall, so the lane was left
  with nothing to climb with. **Fixed** — `PADS_UNTIL_THE_NEXT_CHORD_CUTS`.
- And now: cutting is not the same as being able to leave either. **Open — item 1.**

Every one of those was fixed by teaching one more decision site to pad instead of giving up, and
each got its own flag. Four times is a pattern, not four coincidences. The rule wants stating once:
**before giving up, ask what pad would make this work, and take the smallest that does.** Try that
generalisation before adding a fifth flag — it may well subsume the three flags above.

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
| `cda0cc9` | `CUTS_A_CHORD_THAT_FITS` — half a fix, off. |
| `af5a019` | `PREPADS_FOR_THE_OFF_BUS_DISCOUNT` — the walk's pad clamp counts up as well as down. |
| `e19b407` | `PADS_UNTIL_THE_NEXT_CHORD_CUTS` — pad only as far as it takes to force a cut. |
| `01211e0` | `CUT_PAD_COLUMNS` = 2, not 4: reaching further lost four builds. |

Guardian 44×3, ekran's own build, across this session: **worst breach 11 → 1.**

Guardian, 50 configs (floors 2–6, widths 12–48), before this week vs after:

```
breach blocks   5,376 -> 1,808      worst breach   63 -> 19
builds pasting     45 -> 48         clean builds    5 -> 11
```

Cost: blocks +6%, spanZ +7.5%.

## Open, in priority order

**1. ekran's next one, in their words.** Paste-in, verbatim:

> nice its fixed! now looking at the single breach on the same build (44 wide 3 floors) at the
> chord starting at 34 69 208 is the next breach chord, only of 1. i see exactly what happened. the
> chord is long and didn't fit by 1 cell. if it had cut, the power wouldn't have been abnle to reach
> the chord after it.
>
> this is literally the exact same issue. idk how many times we need to fix the same thing in
> slightly different scenarios. if it had padded forward literally 1 or 2 blocks if would have been
> able to cut and then place the repeater afterwards just fine. and it had enough power from the
> previous chord to do so. i counted 7 cells + 1 handover from the chord before the breach, so our
> breached chord could have easily padded forward and we would have no breach.

Repro: `GuardianStackedTest.tracesTurnsFortyFourByThree`, the `columns=-1 at 43 68 207` hand-over.

Start: the cut refusal is either `stackedSplitOf`'s wire check
(`STACKED_BUS_TRANSITION + (tail + 1) / 2 + splitCells > DUST_RANGE`) or `strandsNext`, which asks
whether the next lane can lay its first chord and **always assumes `turnCells` rather than
`offBus`** — a live suspect, given how much that discount has explained this session. Confirm which
before building anything.

See the note above about making this one rule rather than a fifth flag.

**2. ~~The breach of eleven~~ — FIXED.** `PREPADS_FOR_THE_OFF_BUS_DISCOUNT`. A climb straight off a
bus skips two rungs (`offBus` 3 against `turnCells` 5), and what decides "off a bus" is whether
anything stands between the bus and the staircase. A lane stopping one column short had to cover it
with dust, which cost the column *and* the discount — one-and-three became one-and-five, which it
could not afford, so it never turned and laid the chord that beat it eleven columns past the wall.

The plan already booked a pad to land it flush and `closes` already prices the discount in its
`room == 0` branch. What was missing was in the walk: its clamp on a booked pad only ever counted
*down*, and the sweep and the walk disagreed about where the chord ended, so two booked columns
landed it one short with nothing to take it the last step. The clamp now counts up as well, while
the chord still lands short and the wire covers the pad.

Guardian, 50 configs, this flag alone: breaches 267 → 246, breach blocks **1,539 → 1,160**, worst
**17 → 12**, wrong notes 0 either way, blocks +180 and spanZ +13 across 37 builds. Repro
`GuardianStackedTest.tracesTurnsFortyFourByThree`, breach at `-11 68 84`, now gone.

**3. `CUTS_A_CHORD_THAT_FITS` needs its other half.** `SongBuilder.java` → `stackedSplitOf`.
The cut is written and works; the near half is then however long the chord happened to be rather
than long enough to reach the wall, so the lane hands over short and its staircase stands where no
other lane's does. ekran's missing piece: **pad the near half out to the wall first, then cut.**
`planPad` is the other side. On Guardian 44×3 the cut alone is breaches 3 → 5. The two are worth
nothing apart.

**4. Four wrong notes.** `UltraLaneFaultsTest.reportsHowManyBuildsHaveAWrongNoteInThem` went
0 → 4 of 360 builds when `KEEPS_HEAD_WHEN_THE_BUS_IS_LONGER` went in. Worst is a note belonging to
tick 418 that would sound again at 425 from the block south of it. Keeping more heads means more
stacked modules and more parity contention; something in that is not being asked. **By this file's
own `betterThan` ordering a wrong note outranks every breach number above.** ekran's call was to
fix it without taking the behaviour away.

**5. `BigSplitTest` dead run.** A run of 16 dust and 1,287 unreached notes at f2 w20, caused by
`NUDGE_WHEN_BEHIND_BUSY`. The shift's pad is a cell of dust *before* the module's repeater, so it
spends the incoming signal — and `landingOf` is not handed the signal, so the planner cannot refuse
the shift the way the walk's `outOfWire` test does. Threading the signal into `landingOf` is the fix.

**6. The library A/B has never been run.** `RelocationTest` is written and waiting.

**7. The turn ban.** 122 stacked-buses become plain buses on Guardian because they are near a
corner, each handing on two blocks less wire than the head would. The rule is deferred rather than
physical, and this is a second and much larger cost than the columns it was being judged on.

## Traps

- **`main` is red and was already red.** Baseline at `d9f84d5` is **7 failing tests**. Diff names,
  never counts. New since: `BigSplitTest` ×2 (item 5).
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
