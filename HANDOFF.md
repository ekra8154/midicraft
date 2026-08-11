# Handoff — the handover column, and heads that look instead of guessing

State at `d0ebf59`. Everything below was measured on ekran's own library, not derived.

**Read the collision marks before reasoning about the code.** That is the lesson of the session and
it is not a style note. The last fault of the night got six explanations out of me, every one from
reading source, every one wrong. ekran pasted the broken build, looked at one sea lantern, and named
it in a sentence. `MARK_COLLISIONS` had been printing the answer for hours.

## The two rules this file keeps breaking

**One: fitting is not the same as being able to leave.** A chord that fits before the wall can still
leave the lane unable to turn. Five sites now, and the fifth was the interesting one because it was
never *asked* rather than asked wrongly: `overshoots` compared `landing > farWall`, which is false
when they are equal, so a chord whose last cell landed exactly on the wall read as fitting and the
lane handed over one column outside. Closed by `RESERVES_THE_HANDOVER_COLUMN`, which had to shut
three doors together — the fit test and the two pads that aim a chord at the wall on purpose. Shutting
only the fit test made the build *worse* than shutting none.

**Two: the planner and the walk must ask the same question of the same thing.** Three separate bugs
this session, all the same sentence: **the plan asked arithmetic where the walk asks blocks.**

- `planLane` was handed the coarse `columnBehindBusy` and charged a stand-off column on the first
  chord of a lane that the walk then did not build — carried through every event after it. That one
  column was the whole of ekran's breach of ten. **Fixed** — `PLAN_ASKS_THE_BLOCKS_BEHIND`.
- The nudge guard measured the room a lane had *before* the handover column was reserved. **Fixed.**
- A cut decided whether it could open with a full head from `columnBehindBusy` alone and never asked
  `backPairIsFree`, so where the flag said busy the full head was never attempted at all and the whole
  cut fell to a plain bus. **Fixed** — `CUT_ASKS_THE_BLOCKS_BEHIND`.

And its consequence: the walk can now see things the plan cannot, so **they can disagree**. `REPLAN_WHEN_BLOCKS_DISAGREE` tells the plan when the ground answered what it guessed.
That is the design ekran chose over making the walk stop looking.

## What changed

| commit | what |
|---|---|
| `8d4b360` | Charge every chord the column its lane hands over into. Three doors, shut together. |
| `b3fa8c3` | Charge it where a nudge is decided too — the guard was measuring a stale room. |
| `7acdc81` | The cut-pad search says why it gave up, not just that it did. |
| `5095145` | The plan asks the blocks behind. ekran's breach of ten, gone. |
| `9bc9df0` | A head gives up one flank behind, not both. `STACKED_BUS_HALF`. |
| `38e648c` | Say which of the two candidate builds the trace is showing. |
| `bc61494` | A cut asks the blocks behind, and a cut says what it built. |
| `a6812d1` | Redo the plan when the ground answers what it guessed. |
| `ecb6131` | Resolve a head's free flank by looking — at every site that builds one. |
| `f3ef7e7` | One rule for how many flanks a head keeps, and it does not mention cutting. |
| `76a3d87` | Ask all four low slots, not the back pair and a guess for the front. |
| `d0ebf59` | A head keeps one flank behind only where only one is spoken for. |

Guardian at 44 wide over three floors, ekran's own build: **`[1, 10]` → clean.**

Guardian, the 45 configurations from 16 wide up, start of session against end:

```
breaches      213 -> 144      breachBlocks  985 -> 729
clean           4 ->  10      worst          24 ->  12
wrong notes     0 either way  builds        45 of 45, none refused
```

A cut may now open with a head of seven, six or five, chosen by a rule both halves of the builder
agree on. That was the last thing outstanding and it is closed.

Suite: **11 failing → 7**, a strict subset. Nothing was broken; four were fixed.

## Open, in priority order

**1. `HeldOutWidthTest.noRealSongBreachesAtAWidthNobodyTunedOn` is red, and has been all week.**
It is the held-out check on whether hammering Guardian generalises, and it is currently answering
no. Worth more than another Guardian config: every number in this file is Guardian, and this is the
one test that is not.

**2. Twelve wide and sixteen wide are the same build.** Byte-identical command lists, same 22
breaches, `nearWall=11 farWall=26` either way — a 16-column corridor for both. Something floors the
width at 16, so every sweep this week has been 45 configurations reported as 50, with nothing at all
testing the narrow end.

**3. `worst 16` came from the replan trigger and went away again.** `REPLAN_WHEN_BLOCKS_DISAGREE`
took worst from 12 to 16; looking at the free slots took it back to 12. Neither is understood, and
the pair of them cancelling is luck rather than design.

**4. The front slot case is correct and unexercised.** `onTheFreeSlots` handles a contested *front*
slot because the geometry allows one. Guardian never produces it — 1,682 swaps, every one a back
slot. Do not spend a day on the front case without first finding a song that reaches it.

## Traps

- **`main` is red and was already red.** Baseline is **7 failing tests**, named in the table above.
  Diff names, never counts. The old note in this file said 7 with `BigSplitTest` among them; that was
  stale, `BigSplitTest` passes.
- **`createPastePlan` builds the song twice** — once without lookahead, once with — and keeps whichever
  `beats` the other. Both walks go past the trace. `PLANRUN` lines now say which is which and which
  won; before they existed, two chords were misidentified in one afternoon by reading the wrong half.
- **The two candidates disagree about which floor a lane sits on and about the paste shift.** A chord
  looked up by coordinate can be found in the plan that lost. Prefer `plan.commands()`, which is the
  winning plan in paste coordinates, over arithmetic on the trace.
- **`verify()` reports faults that do not happen.** `SHARED_PULSE_TICKS` was 4, which is shorter than
  the button that starts the machine, so a note re-powered 8 ticks later was called doubled. ekran
  played it: it sounds once. It is 10 now. This matters because `wrongNotes()` outranks every other
  number by `betterThan`, so a phantom here can veto real work — and did, for most of a day.
- **The invariant in `UltraSlots.slot` has an exception.** It says the lane alongside contests at
  most one of the four low slots, so a head may always give up one and keep the other. That is false
  when what stands behind is another stacked module's centre: then both slots behind are gone, and a
  head that keeps one has a note in somebody else's cell. `stackedSplitOf` takes `stackedBehind` for
  exactly this, read from `columnBehindBusy` in the walk and `sweep.busy()` in the planner so neither
  has to guess. ekran's words: *the first rule of stacked chords.*
- **A cut is built by its own path, not by `addChordModule`.** It gets none of that method's guards
  and, until `bc61494`, none of its trace either. If a chord cannot be found in the trace, look for a
  `SPLIT` line before concluding anything about it.
- **`BlitzSweepTest` only builds `ULTRA_COMPACT_LANE`.** Run `gradlew cleanTest test` before
  committing; `gradlew sweepTest` is the measurement, not the test.
- **Measure the built song, not the layers.** ekran has dedupe on, so
  `SongBuilder.eventNotes(project.toSequenceTracks(Set.of(), true))` is the right call.
- **`run/` is not in the repo.** The song library every probe reads is local only.
- **Only ekran ends what ekran asked for.** Shipping a flag off is disabling it. If a measurement
  argues against something they asked for, that is the start of the investigation and not a verdict.

## Probes worth knowing

- `HandoverCollisionProbe` — the cut collision repro, the per-size Guardian table, the side-swap
  count, and a region dump that prints any box of the winning plan in paste coordinates.
- `MARK_COLLISIONS` — builds through a collision and lights it up, naming the shape that laid each
  block. **Use this first.** It is the only tool this week that has never been wrong.
- `TRACE_TURNS` — one line per chord standing outside the footprint, with the wire it has.
- `TRACE` — the full walk, plus `SPLIT` for cut chords, `CUTTRY` for every refused closing pad, and
  `DRIFT` where a chord did not land where `landingOf` said.
- `GuardianStackedTest` — decision census, the 44×3 chord trace, the width/floor sweep.
