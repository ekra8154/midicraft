# Fast Noteblocks

A fully client-side Fabric mod for Minecraft Java Edition 26.2 that displays
interactive pitch and delay controls above nearby note blocks and repeaters.

Run **`/fastnoteblocks`** to open the Composer -- or `/fastnoteblocks composer`,
which does the same thing and is easier to find by tab-completing. Settings are
at **`/fastnoteblocks settings`**. The mod binds no keys by
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
without a rejoin, and refuse to run while it is off. `/fastnoteblocks asciidiagram <from> <to> [view] [facing] [notes]`
draws a region of the world as text, and `/fastnoteblocks debugpaste [on|off]`
colours the next build by what laid each block -- dead wire red, wrong notes as
lit copper bulbs, collisions in sea lantern -- or, bare, prints that colour key.

The third is `/fastnoteblocks paste`, which builds a run of chords you type out
rather than a song, for testing layouts:

```
/fastnoteblocks paste <width> <floors> [flat|up|down [turning] <columns to wall>] <chords>
```

`/fastnoteblocks paste 12 1 6 2 18` builds chords of six, two and eighteen in a
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
hit. Nothing on such a layer is ever out of range.

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
