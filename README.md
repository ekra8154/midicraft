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
- a four-way overlay selector: note blocks and repeaters (default), note blocks
  only, repeaters only, or off;
- optional nearby previews; when disabled, labels are hidden until their center
  position is targeted;
- optional interactive controls, including the note radial and scrolling for
  both block types;
- repeater control style: radial select (default) or directional scrolling;
- inverted scrolling;
- shared overlay view distance from 1 to 32 blocks;
- interaction delay from 0 to 10 extra ticks, defaulting to the original
  one-interaction-per-client-tick speed;
- optional server-confirmation waiting;
- optional unobstructed line-of-sight enforcement;
- a resumable unified note-block and repeater placement sequence;
- a validated, whitespace-agnostic sequence using note values `0` through `24`
  and explicit repeater delays `1d` through `4d`.

For example, `0, 2d, 4, 2d, 7` expects a note block tuned to pitch 0, a repeater
set to delay 2, and so on. A mismatched placement does not advance the sequence,
and the sequence repeats when it reaches the end. Consecutive steps of the same
block type can queue while earlier blocks are still adjusting; transitions
between note blocks and repeaters wait for the current batch to finish.

The sequence control key is unbound by default and can be assigned in
Minecraft's Controls screen. Tap it to pause or resume without losing the
current position, hold it and scroll up/down for the previous/next step, or
double-tap it to return to the beginning. A brief HUD preview appears after
these controls and shows the current and following steps. Holding the sequence
key by itself is inert; a tap is recognized only after the key is released.

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
