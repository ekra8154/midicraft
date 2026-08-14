# Handoff — two-rail runs, and the one invariant still broken

State at `2a659b5`, branch `claude/compact-chord-sequences-623d9d`. Everything below was measured
over ekran's whole library, not derived.

**The branch is not mergeable.** Wrong notes and breaches are where they need to be; dead wires are
not. One invariant, stated in the source and violated by some route the guard does not cover,
accounts for all of it. That is the whole of what is left.

## Draw the break. Do not read setblock lists.

The lesson of the session, and not a style note. Two long passes went into reading command dumps and
reasoning about redstone rules, and reached the wrong explanation twice. ekran read the same fault
off one rendered slice immediately:

```
y=69     .  . ST ST w0 ST w0
```

> *"a wire on both sides of a block. a wire can't be soft powered ... its repeater got sent up to the
> floor above, but if it doesn't come directly out of the stone, it can't conduct"*

`AsciiDiagram.render(world, from, to, View, Shape)` takes any `BlockPos -> BlockState`, so a plan's
own commands pour straight into it. `RailDeadWireProbeTest` does exactly that: it finds the last live
note and the first dead one, boxes the span between them and prints every slice. Change
`SONG`/`WIDTH`/`FLOORS` at the top of that file and it re-renders any build in one run, with no
client needed.

**The signature is wire–block–wire.** A block powered only by dust is *soft* powered: it will still
sound the notes hung on it — which is why they read as reached — and it cannot light the dust on its
far side. Every dead wire this project has produced has been that shape.

## The shape of a two-rail run

ekran's, and it works: a run of small chords costs **one column per chord** instead of two, by running
two chains past each other. The path chain runs at `lane.pos().above()`, the floor chain at
`lane.pos()`, each carrying twice the gap and offset by one gap. Every column holds one chord's notes
on one rail and the repeater driving the next chord on the other.

- **Path column**: centre at P+1 (a harp note, or stone where the chord has none), repeater at P
  driving the next floor anchor, support at P−1.
- **Floor column**: stone at P carrying its notes to the sides, repeater at P+1 driving the next
  path centre. No centre of its own — the path rail's repeater stands over it.
- **Head**: repeater, then dust on stone. Two columns, of which one is the trigger every module pays
  for anyway.
- **Blank**: a floor column with its notes left off, for a chord of three the floor rail cannot hold,
  for a pair of gaps over four ticks, and now for a chord a stacked neighbour would sound early.

## What is open, and it is the only thing

**A run must end on a path column, and sometimes it ends on a floor column.** The reason is written
into [`SongBuilder.java:887`](src/client/java/com/fastnoteblocks/client/compat/SongBuilder.java#L887)
and the guard there is correct as far as it goes — `overshoots` refuses to interrupt a run while
`railPhase >= 0`. Some second route into ending mid-pair does not pass through it.

Why it matters: a path column's centre is a block a **repeater** drives, so it lights whatever comes
next. A floor column instead hands its repeater out at path level into plain dust, and the first
block that dust reaches is soft powered — and the lane then climbs out of it, leaving the repeater
that should have drawn the signal onward a floor above and past the staircase.

Reproduce it in one run:

```bash
./gradlew.bat sweepTest --offline -i --tests "com.fastnoteblocks.client.compat.RailDeadWireProbeTest"
```

It is pointed at `michael-jackson-thriller`, 16 wide over 3 floors — **7517 of 8027 notes silent**.
Reading right to left in the rendered `z=31` slice: `x=11` head repeater, `x=10` head dust, `x=9`
path column, `x=8` floor column, then dust. The run ended on the floor column. Had it run one column
further to `x=7`, that centre would have been repeater-driven and the dust at `x=6` would have lit.

Two things worth checking that were never resolved:

- `railPairAfter` commits to both columns of a pair at the path column; the floor column re-asks
  independently through `railNextDelay`. They *look* like the same question — same event index, same
  `railHolds(e, true)`, same anchor time — so if they are agreeing, the run is being abandoned by
  `turning || lane.bending()` at the top of the rail branch rather than ending by its own arithmetic.
- Whichever it is, the fix belongs where the pair is committed, not where it is discovered.

## The numbers, whole library, seven sizes a song

```
wrong notes   0 off ->     0 on
breach     2349 off ->  2353 on     (four blocks, three songs gaining one or two)
dead wires    0 off -> 14200 on     (seven songs)
depth              better on 201 sizes, worse on 43
runs opened   6112 off a stacked chord, 13119 building a head
```

`RailAgainstStacksTest` prints all three now. It used to compare only wrong notes and depth, which is
why it kept coming back clean while `BreachTraceTest` went red — **measure all three or the sweep
lies to you.**

The six synthetic songs (`ultra-ones/twos/threes/gaps-mixed`, song of storms, lady brown) are clean
at all 252 plan sizes and all 55 read back, and none of them holds a chord big enough to stack, so
none of them can see any of this. That is why the library sweep exists.

## Settled during this session, with the measurement

- **A note block carries power exactly as a stone does.** `NoteMachineReader.spreadFromDust` refused
  to carry a dust's power into a note block — one clause, no comment, no matching refusal for the
  block below. `NoteBlockConductsTest` asks the game: `isRedstoneConductor`, `isSignalSource`,
  `canOcclude`, `isSolidRender` all read the same for stone and note block. **Ask Minecraft's own
  block properties before believing the reader about physics.**
- **A run may not end where it opened.** The opening column is the one column the head's *dust*
  drives, and a block dust powers cannot light dust. `railOpens` now asks up front for the same room
  the column-by-column test asks for after the head and the padding.
- **Blanks are not waste**, though they look it. A blank is not against the plain lane, it is against
  *ending the run*, and a run that ends pays a fresh head. Total depth: plain 1843, one blank ever
  1557, one in a row 1498, two 1484, three 1476, **no limit 1473**. `RAIL_BLANKS_IN_A_ROW` holds the
  switch, off.
- **Floor notes contend with stacked chords, and both of ekran's fixes work.** A chord with a side to
  spare hangs on the far side; a chord needing both sides is refused the floor column and the blank
  lifts it onto the path rail. 32 songs went from no wrong notes to some; back to nought.
- **A stacked chord is already a head.** Its dust cross sits on stone at the lane's own floor level,
  one column back from where it hands over, live at the module's tick — so a run opens off it for the
  price of the trigger column alone. Detected by reading the block behind, not by `lastStyle`.

## Flags

`TWO_RAIL_RUNS`, `RAIL_BLANKS`, `RAIL_BLANKS_FOR_DELAY`, `RAIL_MOVES_FOR_STACKS`, `RAIL_FROM_STACK`,
`MARK_UNREACHED` — all on. `RAIL_BLANKS_IN_A_ROW` is off (`Integer.MAX_VALUE`) with its numbers in
the javadoc. Nothing has been disabled or reverted; ekran asked for it that way twice.

## Red tests

`BreachReproSearchTest` is not this branch's — its spec builds no rail column at all, and
`RailTouchesBreachReproTest` pins that. `BreachTraceTest` **is** ours: it asserts no song turns past
its wall, passed at `70b777e`, and has been red since. It should come back with the invariant.
