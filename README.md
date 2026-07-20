# Fast Noteblocks

A fully client-side Fabric mod for Minecraft Java Edition 26.2 that displays
interactive pitch and delay controls above nearby note blocks and repeaters.

Press **N** to switch overlays off or restore the last selected overlay mode.
Each nearby note block shows one billboard label for its current pitch. Hover
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

When the optional Mod Menu and Cloth Config mods are installed, the Mod Menu
configuration button provides:

- a master switch for the entire mod;
- a four-way overlay selector: note blocks and repeaters, note blocks only
  (default), repeaters only, or off;
- optional nearby previews; when disabled, labels are hidden until their center
  position is targeted;
- optional interactive controls, including the note radial and scrolling for
  both block types;
- repeater control style: radial select (default) or directional scrolling;
- inverted scrolling;
- configurable radial focus delay from 0 to 20 ticks, defaulting to 5;
- shared overlay view distance from 1 to 32 blocks;
- interaction delay from 0 to 10 extra ticks, defaulting to the original
  one-interaction-per-client-tick speed;
- optional server-confirmation waiting;
- optional unobstructed line-of-sight enforcement;
- resumable compositions with up to four independently named tracks;
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

The settings screen includes a complete `0` through `24` pitch-name guide.

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
