# Handoff — two-rail runs, and the invariant that is now held

Branch `claude/double-rail-worktree-handoff-dd47ed`, working tree on top of `1fcc581`. Everything
below was measured over the whole library, not derived.

**The dead wires are gone.** Both routes that produced them are named and fixed, and the whole
library now reads back clean at all seven sizes a song:

```
wrong notes   0 off ->    0 on
breach     2349 off -> 2349 on     (was 2353; the four extra went with the first fix)
dead wires    0 off ->    0 on     (was 14200 over seven songs)
depth              better on 202 sizes, worse on 44
runs opened   6052 off a stacked chord, 13064 building a head
```

What is left before this merges is the ordinary check, not a search: the red set. See the last
section.

## Draw the break. Do not read setblock lists.

The lesson of the session, and not a style note. Two long passes went into reading command dumps and
reasoning about redstone rules, and reached the wrong explanation twice. In-game reading showed the same fault
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

the, and it works: a run of small chords costs **one column per chord** instead of two, by running
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

## The two routes that killed the wire, and what each one was

Both produce the same picture and neither was the route the last handoff guessed at. Its two
suspects were both innocent, and instrumenting every run exit is what said so: 360 ends on thriller,
every one of them on a path column by its own arithmetic, none abandoned by `turning || bending`.
`railPairAfter` and `railNextDelay` do agree.

**The signature is always wire-block-wire.** A block powered only by dust is *soft* powered: it will
still sound the notes hung on it -- which is why the note on it reads as reached, and why the last
live note in a build is always sitting on the fault -- and it cannot light the dust on its far side.

### One: a booked pad landing inside a committed pair

The run's own columns were right; a column was inserted *between* them. The plan books its pad
against an event, and the walk lays that pad at the top of the event, before the rail branch is
reached. So when a pair had been committed, a booked column landed between the repeater a rail
column had already laid and the note that repeater existed to drive. On thriller at 16 wide over 3
floors, `z=31`, reading west:

```
x=9 PATH   centre note, repeater below
x=8 FLOOR  notes at floor level, repeater above
x=7        stone + dust  <- booked pad, not part of the run
x=6 PATH   stone centre -- soft powered, sounds its own notes, lights nothing
```

Three of these in that build; the first buried 7517 of 8027 notes. Fixed where the pair is
committed, as the last handoff predicted: `railPairAfter` refuses a pair that spans a booked pad, so
the run ends on its path column, the lane pads as planned, and a fresh run opens beyond it. Thriller
went 7517 -> 0, and the library 14200 -> 1337.

### Two: the cheap head priced when the walk will not build it

`railOpens` priced the head at one column whenever a stacked chord stood behind, but the walk only
takes that head where the padding leaves the lane where it stood -- the cross has to be the cell
behind the trigger. With a wait of eight ticks the padding moves the lane one column, the walk
builds the whole two-column head, and the run opens one column poorer than it was promised. It then
finds no room for its pair and ends on the column its head's *dust* drives, which lights no dust of
its own.

The plan already had the words for it: `addRailNote` raises *"a run at tick N ended on the column its
head's dust drives"*, and printing `plan.faults()` in the probe named it in one run. `railHeadColumns`
now takes the wait and charges the full head whenever the padding will spend a column. That is the
last 1337, over three songs.

**Both fixes are one-liners in effect and neither is a flag.** Nothing was disabled to get here.

## The numbers, whole library, seven sizes a song

```
wrong notes   0 off ->    0 on
breach     2349 off -> 2349 on
dead wires    0 off ->    0 on
depth              better on 202 sizes, worse on 44
runs opened   6052 off a stacked chord, 13064 building a head
```

`RailAgainstStacksTest` prints all three. It used to compare only wrong notes and depth, which is why
it kept coming back clean while `BreachTraceTest` went red -- **measure all three or the sweep lies
to you.** It takes about twenty minutes; do not run anything else against `sweepTest` while it does,
or the two collide on `build/test-results/sweepTest` and both die.

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
- **Floor notes contend with stacked chords, and both of the fixes work.** A chord with a side to
  spare hangs on the far side; a chord needing both sides is refused the floor column and the blank
  lifts it onto the path rail. 32 songs went from no wrong notes to some; back to nought.
- **A stacked chord is already a head.** Its dust cross sits on stone at the lane's own floor level,
  one column back from where it hands over, live at the module's tick — so a run opens off it for the
  price of the trigger column alone. Detected by reading the block behind, not by `lastStyle`.

## Flags

`TWO_RAIL_RUNS`, `RAIL_BLANKS`, `RAIL_BLANKS_FOR_DELAY`, `RAIL_MOVES_FOR_STACKS`, `RAIL_FROM_STACK`,
`MARK_UNREACHED` — all on. `RAIL_BLANKS_IN_A_ROW` is off (`Integer.MAX_VALUE`) with its numbers in
the javadoc. Nothing has been disabled or reverted; the request was for it that way twice.

## Red tests

`BreachReproSearchTest` is not this branch's -- its spec builds no rail column at all, and
`RailTouchesBreachReproTest` pins that. `BreachTraceTest.listsTheRealBreachesLeft` **is** ours: it
asserts no song somebody wrote turns past its wall, passed at `70b777e`, and has been red since.
Running it is the check that is left.

Run that method by name and nothing else in the class. The other three tests in the file are scratch
probes that set `SongBuilder.TRACE = true`, and a traced build pours every walk line through log4j --
that is where the eighteen-gigabyte `latest.log` came from. Two runs of the whole class sat at four
hundred megabytes of heap each and had to be killed.

```bash
./gradlew.bat test --offline --tests "com.fastnoteblocks.client.compat.BreachTraceTest.listsTheRealBreachesLeft"
```

## Running any of this in a fresh worktree

The probes read `run/config/fast-noteblocks/songs`, which is gitignored and lives in the main
checkout. A worktree has no `run` at all, and every probe dies on `NoSuchFileException` until it does.

**Do NOT junction `run` in.** On 2026-08-19 an automated worktree cleanup recursed through a
leftover worktree's `run` junction and deleted the real library, worlds and mods. Copy the songs
instead — they are small, and a copy cannot be deleted through:

```bash
cmd //c robocopy "D:/Documents/modding/fast-noteblocks/run/config/fast-noteblocks/songs" "run/config/fast-noteblocks/songs" //E
```
