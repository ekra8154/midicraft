# Midicraft

A fully client-side Fabric mod for Minecraft Java Edition 26.2 that displays
interactive pitch and delay controls above nearby note blocks and repeaters.

Run **`/midicraft`** to open the Composer -- or `/midicraft composer`,
which does the same thing and is easier to find by tab-completing. Settings are
at **`/midicraft settings`**. The mod binds no keys by
default -- taking a letter key from someone who plays with a lot of mods is a
rude way to introduce yourself -- so the Composer, the interactive overlay and
the placement sequence control are all unbound until you say otherwise. Bind
them on the Keys tab of the mod's own settings, or in Minecraft's Controls
screen, which are two views of the same binding.

There are two overlays, and they are set separately. **Nearby overlays** label
every block of the chosen types within view distance: something to read, and off
by default. The **interactive overlay** is the one on the block under your
crosshair, the one you can scroll to retune. Turning it on is enough on its own
-- with nearby overlays off you see and change the block you are aiming at and
nothing else.

With note blocks interactive, each one shows a billboard label for its current pitch. Hover
that label to open a compact radial A-G menu for only that note block. While in
normal block-interaction range:

- scroll up to select the next natural/sharp pitch in that letter family;
- scroll down to select the previous pitch in that family.

The radial menu keeps the focused letter in place while scrolling repeatedly.
Its order runs clockwise, with A at the top, B at the upper right, and G at the
upper left whenever those letters are outside the center.

Each nearby repeater shows its current `1` through `4` delay. The default radial
style expands this into a four-number diamond with the current delay at the
bottom. Aim at another number and scroll either way to select it; the layout
stays pinned until focus leaves it, then rotates the new current delay to the
bottom. An optional single-number scroll style cycles delays directionally.

## Settings

The Settings tab in the Composer's menu bar opens them, and so does the
configuration button beside the mod in Mod Menu's list if that mod is installed.
Neither route needs anything beyond Fabric API. Settings are grouped into
In-world tools, Keys, Server Friendliness, Placement Sequence, Composer and
Debug; any one of them can be put back with the arrow beside it, and a whole tab
or the whole mod can be reset from the buttons underneath. Resetting the whole
mod leaves key bindings alone.

The in-world half of the mod is off on a fresh install and the Composer is not:
labels over blocks and scrolling to retune them are tools you turn on when you
want them, rather than the first thing a new world greets you with. What the
settings cover:

- a GUI scale for the mod's own screens, separate from Minecraft's, since a piano
  roll wants more pixels than a hotbar does;
- a master switch for the in-world tools, which does not touch the Composer;
- nearby overlays: off (the default), repeaters only, note blocks only, or both;
- the interactive overlay on or off, which is what the overlay key toggles, and
  which block types it answers for;
- repeater control style: radial select (default) or directional scrolling;
- inverted scrolling;
- configurable radial focus delay from 0 to 20 ticks, defaulting to 5;
- shared overlay view distance from 1 to 32 blocks;
- interaction delay from 0 to 10 extra ticks, defaulting to the original
  one-interaction-per-client-tick speed;
- optional server-confirmation waiting;
- optional unobstructed line-of-sight enforcement;
- resumable compositions with independently named tracks;
- one instrument per track, including a Barrier instrument that mutes the
  track without removing it from synchronized preview timing;
- sequencing edit protection: radials only, radials and ordinary interactions
  (default), or off;
- a validated, whitespace-agnostic sequence using note values `0` through `24`
  and explicit repeater delay groups `1d` through `64d`.

Each track has its own sequence, instrument, collapse state, and persistent
placement cursor. The selected Build track drives placement automation, the
in-game timeline, counters, and hotbar selection. Preview plays every track
together from time zero and follows the highlighted token vertically in each
expanded editor. Existing single-track configs and saved sequences migrate to
Track 1.

For example, `0, 2d, 4, 2d, 7` expects a note block tuned to pitch 0, a repeater
set to delay 2, and so on. A mismatched placement does not advance the sequence,
and the sequence repeats when it reaches the end. New placements can advance
regardless of whether earlier note blocks or repeaters are still tuning, so
switching block types does not impose a completion wait.
Long delay groups use the minimum number of physical repeaters: `10d` expands
to `4d + 4d + 2d`. The HUD keeps that group in one dotted capsule while H +
wheel and placement advance through its individual repeater dots.

The sequence control key is unbound by default and can be assigned in
Minecraft's Controls screen. Hold it to see the sequence, hold and scroll
up/down for the previous/next step, or double-tap it to pause or resume without
losing the current position. While paused, single taps, holds, and scrolling are
inert for sequence control; only a completed double-tap resumes it, and the
wheel continues to scroll the vanilla hotbar. Manual sequence scrolling stops
at the first and last steps instead of wrapping. The sliding HUD keeps the next
placement centered, with
compact repeater delay markers between the fuller note labels. Each successful
placement briefly shows only the placed step sliding aside and its successor
becoming current. A compact `x/X` counter appears under both HUD layouts, and
the current sequence position is saved so long melodies resume at the same
step after restarting.

The optional **Auto-select current step** setting passively changes to the note
block or repeater required by the current sequence step whenever the cursor
moves. This includes successful placements, H + wheel navigation in either
direction, sequence edits that reset the cursor, and resuming the sequence. It
never intercepts right-clicks. If the required item is not in the hotbar, the
selected slot is left unchanged.


## Convert for Minecraft

A note block plays two octaves and one semitone, MIDI 54 to 78, and most music
does not fit in that. Convert brings every note into it by moving notes whole
octaves -- always whole octaves, because a part moved by anything else is not in
a different octave, it is in a different key from the rest of the song.

Two settings under **Convert for Minecraft** decide how:

- **Out-of-range notes** -- `Shift the notes` moves only the notes that are out
  of range, each by its own nearest octave, and leaves everything else exactly
  where it was written. `Shift the layer, then the notes` moves the whole layer
  to wherever the fewest of its notes are out of range and then shifts whatever
  is still out, note by note. The second splits fewer layers and keeps a part's
  intervals together; the cost is that notes with nothing wrong with them can
  move, when moving them catches more strays than it creates. A layer already
  wholly in range scores nothing at all and stays where it is.
- **Split transposed notes into layers** -- when a note takes a different octave
  from the rest of its layer, give it a layer of its own, named with the octave
  it moved. Nothing about a layer requires this: it is so you can see what
  Convert moved, mute it, or put it back. Off keeps the layer whole and the layer
  count down, and two notes an octave apart that land on one pitch become one
  note instead of one dropped layer.

Both modes end with every note in range, whichever way the split is set, because
the last step of each is the same per-note octave shift and the window is wide
enough that no pitch class can fail. A wide part still splits either way if no
single octave holds it -- that is the part being wider than a note block, not a
setting being wrong.


## Copying and repeating

Dragging a box over a passage does two things. It selects the notes inside it,
and it leaves a **selection range** behind: a tinted band across the roll with
its length written in it, and a bracket with a handle at each end in the strip
along the bottom of the ruler.

The range is there because a set of notes is not a length. Notes end on the last
note; a passage ends on silence, and nothing in a copy says how much. So the
range is what Ctrl+V and Ctrl+D step by, and either end of it can be dragged --
pull the right-hand one past the last note and watch the number change. That is
how you say "and half a bar of rest". Right-click the strip to drop the range
without dropping the selection.

- **Ctrl+C, Ctrl+V** copy and paste. Paste lands at the playback marker and then
  moves the marker on by the range, so pressing Ctrl+V again continues the
  passage instead of laying a second copy on the first. Ctrl+Shift+V pastes back
  where the copy was taken from. A copy keeps the length it was made with until
  something else is copied.
- **Ctrl+D** duplicates the selection immediately after itself and leaves the
  selection on the copy, so pressing it again adds another repeat. No clipboard
  is involved and every note stays on the layer it is already on -- a four-part
  phrase comes back as four parts. A paste, by contrast, is aimed at a layer.

Selections made without a box -- Ctrl+A, or the Select menu -- have no range, so
the length is guessed from the notes: the last one, plus the tightest gap between
any two of them. That is usually right for a phrase of even steps and it is
always visible, drawn as a hairline in the same strip, before you commit to it.


## Sorting the lists

The song library and the file browser both list **newest first** by default: the
song you last saved, or the file you just downloaded, is nearly always the one
you came back for. A **Sort** button beside the search box switches either to
A to Z, and the choice sticks -- it is saved with the settings, because the file
browser is built fresh on every import and a choice that reset itself would not
be one. Both lists follow the same setting.

Each song row says when it was last saved, in the same words the order is in --
`2 hours ago`, `3 days ago` -- so the order has a visible key rather than one you
take on trust. Folders in the browser stay alphabetical whichever way files are
sorted: a folder's date is about whatever was last written inside it, which is no
help in finding the folder.


## Dragging a file in

Drag a `.mid`, `.midi`, `.nbs`, `.nbt`, `.schem` or `.litematic` file from your
desktop onto the Minecraft window and it is imported. This works on the song
library, in the composer, and in the file browser -- whichever is open takes it.

The extension chooses the reader, so there is no need to pick the matching import
button first. Dropping a folder onto the file browser opens that folder instead.
Dropping several files takes the first one it can read and says so rather than
guessing at a queue. Dropping something it cannot read says that too, because a
drop that is silently ignored looks exactly like one the window never got.

Onto the composer it goes through the same unsaved-changes check the Import menu
entry does: a drop is easy to make by accident in a way that choosing a menu
entry is not.


## Two panes, one keyboard

The composer has two halves that own a selection: the layer panel and the piano
roll. The keyboard points at whichever you clicked last, and that decides what
Delete, Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V, Ctrl+D and Ctrl+E act on. Clicking into
the roll leaves the layers selected -- it only stops the keyboard reaching them.

You can see which has it. A selection in the pane holding the keyboard draws at
full strength; the other pane's draws muted. Same highlight, two saturations, so
"these are still selected and Delete will not reach them" needs nothing new to
learn.

This replaces a rule you could not see. Delete used to mean "the notes, or the
layers if no note is selected", so reaching for it while believing a passage was
selected took a layer instead. Five other keys had the opposite fault and were
nailed to one pane whatever you were working in.

With the panel holding the keyboard:

- **Ctrl+C** takes the selected layers whole -- names, instruments, states and
  notes. **Ctrl+X** takes them and removes them. **Ctrl+V** puts them back
  directly below the lowest selected row, or on the end when nothing is selected.
  Pasted layers get fresh note ids and keep their names.
- **Ctrl+D** duplicates them, each copy under its own original.
- **Ctrl+E** merges them. **Ctrl+A** selects every layer.
- **Delete** and **Backspace** remove them.

Undo and redo are not routed and never were: there is one history for the whole
composition, and Ctrl+Z takes back the last thing you did whichever pane did it.


## What gets built

A layer goes into the build if you can hear it and see it. Muting a layer or
hiding it takes it out; setting it back to Active puts it back. There is nothing
else to set -- what you hear in the composer is what the machine plays.

That used to be a separate flag, a dot on each row, independent of mute. It made
the composer two things at once: preview played the unmuted layers and a build
placed the dotted ones, with nothing connecting them, so pressing Space was not a
preview of the build and there was no way to hear what would be built. A DAW does
not have this problem, because a bounce is the same signal chain as the transport
-- what you heard is what you got. This is that.

**Solo is the exception, on purpose.** Soloing is a lens for listening around a
part, not a decision about the song, so the layers it silences are still built.
That is the one case where preview and build disagree, and the status bar says so
while any layer is soloed.

Muting a layer to hear around it and then pasting is the mistake this invites, so
two things say the count: the status line reads `Build: 12 of 15 layers`, and
starting a paste says `3 layers are muted or hidden, so they are not in this
build.`

Older songs carry the old flag in their files and it is ignored. Nothing is lost
by it: a layer that was dotted but muted is now left out, and a layer that was
undotted but audible is now built.


## Tempo and speed

Two numbers decide how fast a song plays, and they multiply.

The **tempo** is the song's own, written in the file as microseconds per quarter
note and shown as BPM. The **Speed** slider is a ratio on top of it, from 0.25x
to 8.00x in quarter steps. It is not a preview: it is saved with the song and the
build runs at it, so a song at 150 BPM and 2.00x really is a 300 BPM song.

The status bar shows the sum -- `150 x 2.00 = 300 BPM` -- and the song's
resolution beside it, `480 ticks/beat`. That last number, ticks per quarter note,
is what the fraction grid is counted in: a 1/16 line is a quarter of it. It has
no bearing on how long anything lasts, which is why two songs at the same BPM and
different resolutions have the same 1/16 in real time.

**Edit > Apply speed to the tempo** folds the slider into the tempo and puts it
back to 1.00x. Nothing about the song changes -- 150 at 2.00x and 300 at 1.00x
are the same song, note for note -- but the number written in the file becomes
the one it plays at, and the slider is free to be a ratio of the new baseline.
Convert does this as its first step; this is that step by itself.


## The snap grid

The Snap control offers two different kinds of grid, and they are absolute about
different things.

The **note values** -- 1/4, 1/8, 1/16, 1/32 -- are absolute in the *song*. A 1/16
is a sixteenth of a quarter note whatever the tempo is doing. They are what bars
and beats are made of, and they are what you want when you are writing music.

**Repeater tick** and **game tick** are absolute in *real time*. A repeater tick
is 100 ms, the shortest delay a repeater can add and so the closest two notes can
be built; a game tick is half that, reachable only by a build laying a second
lane. Their lines are drawn where those moments actually fall, which on a song
whose tempo does not divide into them means visibly not on the beat. That is the
information, not a fault: it is what an unconverted song looks like, and
Edit > Convert for Minecraft is what moves the tempo until the two grids agree.

Because they measure different things, the same setting means different amounts
of time in different songs. `Snap 1/16` is one repeater tick at 150 BPM, two at
75, three at 50, and a quarter of one at 300 BPM played at 2.00x. So the status
bar carries the translation -- `grid 1/16 = 1 repeater tick` -- and it moves as
the tempo and the speed slider move. The Snap button turns amber when its grid is
not one the current paste mode can build on, and its tooltip says why.

Bar lines and bar numbers are drawn whatever the snap is set to. They used to
appear only where a snap line happened to land on one, so choosing a redstone
grid on an unaligned song took the bars off the roll entirely -- which is the one
thing that makes the roll readable.


## Markers

A marker is a named position on the composer's timeline. Nothing is built from
one and nothing sounds at one -- it is somewhere to write down what a stretch of
the song is, so that finding the second chorus again is reading a label rather
than counting bars.

**M** puts one where the playback marker is standing, or takes away the one
already there; **Edit > Markers** is the same three actions with the mouse. They
appear in a strip above the ruler, which is only there while the song has
markers -- with none, the ruler sits flush against the menu bar. Click a label to
jump the playback marker to it, double-click to rename it, right-click to remove
it, and click the empty part of the strip to add one where you clicked. A faint
line drops from each one through the roll.

One marker to a tick, so adding one where another already stands renames it.
They are saved with the composition, they come forward when **Snap to song
start** pulls the music forward, and Ctrl+Z takes back any of it.


## Debug commands

Off by default; the switch is on the Debug tab of the settings, and takes effect
the moment you throw it -- the three subcommands come and go from tab-completion
without a rejoin, and refuse to run while it is off. `/midicraft asciidiagram <from> <to> [view] [facing] [notes]`
draws a region of the world as text, and `/midicraft debugpaste [on|off]`
colours the next build by what laid each block -- dead wire red, wrong notes as
lit copper bulbs, collisions in sea lantern -- or, bare, prints that colour key.

The third is `/midicraft paste`, which builds a run of chords you type out
rather than a song, for testing layouts:

```
/midicraft paste <width> <floors> [flat|up|down [turning] <columns to wall>] <chords>
```

`/midicraft paste 12 1 6 2 18` builds chords of six, two and eighteen in a
corridor twelve wide. A chord may be repeated with `x`, given its own gap with
`@`, and given instruments with a colon: `30x4`, `18@1`, `7:7b`. The instrument
letters are `p` harp (air), `h` hi-hat (glass, will not carry power), `s` snare
(sand, falls) and `b` bell (gold), with harp for any note not named.

Naming a wall shape starts the walk as though it had already climbed there, and
adding `turning` has it arrive with that wall's corners already on its route, so
`36 1 flat turning 12` is a lane twelve columns short of a bend it is already
committed to. Put `dry` first to report the layout without placing anything.
Every note is the same pitch.

## Sound effects

The composer's instrument palette has two tabs. **Instruments** holds the tuned
note block voices. **Sound effects** holds blocks that make their own noise when
redstone reaches them: oak, iron and copper trapdoors, oak and iron doors, an oak
fence gate, an oak shelf, a bell, a copper bulb, a dropper, a piston, a sculk
shrieker, and the six note blocks that wear a mob head — skeleton, wither
skeleton, zombie, creeper, piglin and ender dragon.

There is no dispenser. An empty dispenser and an empty dropper both play
`block.dispenser.fail`, so the two were one voice wearing two icons.

A layer set to one of these is not tuned. Every hit sounds the same, so the row a
hit is drawn on is only somewhere to put it, and two hits on the same tick are one
hit. Nothing on such a layer is ever out of range, and Convert leaves it exactly
where it is -- there is no octave to move it to that would sound like anything
different, so it is neither transposed nor split.

Each effect names how far it can be heard, because they are not all alike. Most
carry 16 blocks; a bell carries 32, a mob head note block 48, and a sculk shrieker
80. The figure is vanilla's own — the range a sound event reports for the volume
that block plays at — so it is the distance to space a machine around rather than
an estimate. Every tuned instrument is 48, so the Instruments tab does not repeat
it twenty times.

Each of these makes a second sound when the power leaves again — the door shuts,
the bulb clicks off, the piston pulls back. A build sends a pulse, so that second
sound always follows a moment behind the first.

In a build each effect occupies exactly the three cells an ordinary note would:
itself, the cell below where an instrument block would sit, and the cell above
that a note block keeps as air. A door's upper half and a skull go in that air,
and so does a piston's head, which is why the pistons face up. Most of these
blocks do not carry a redstone signal onward — including the copper bulb, which
looks like it should — so a chord containing one is built as a bus, where every
sound is powered off its own block rather than through its neighbour. Dispensers,
droppers and the mob head note blocks do carry a signal, and a chord led by one of
those keeps the tighter shape.

A lone effect that cannot carry a signal is not left standing where the repeater
points, since that cell is also the one the next repeater reads. It gets a stone
there instead and moves one block to the near side, sounded off that stone the way
a bus sounds its notes.

The hand sequencer understands these too. A sound effect step hands you the effect
block itself rather than a note block on an instrument block, and the step is done
the moment the block is down — there is no pitch to click it round to, and the HUD
shows a dot instead of a note name. The mob heads take two placements, the same
two the pitched instruments take but the other way up: the note block goes down
first and the skull lands on top of it.

NBS has no way to hold any of this, so sound effect layers are left out of an
export and the report says how many went.

## Multiplayer safety

Automated tuning sends ordinary vanilla use-block interactions. By default it
can send one interaction per client tick, matching the mod's original behavior.
Optional settings can add delay, wait for each resulting block-state change
from the server, and require an unobstructed line from the player to the block. Normal
interaction range is always enforced so the mod does not send packets that the
server must reject. These controls cannot guarantee compatibility with every
server's rules or anti-cheat configuration.

The mod sends ordinary, rate-limited right-click interactions and requires no
server-side installation. Keep the main hand in a state where a normal
right-click can adjust the target block.

## Development

Requires Java 25. Build with:

```powershell
.\gradlew.bat build
```
