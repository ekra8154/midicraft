# Fast Noteblocks

A fully client-side Fabric mod for Minecraft Java Edition 26.2 that displays
interactive A-G pitch controls above nearby note blocks.

Press **N** to toggle the overlay. Each nearby note block shows one billboard
label for its current pitch. Hover that label to open a compact radial A-G menu
for only that note block. While in normal block-interaction range:

- scroll up to select the next natural/sharp pitch in that letter family;
- scroll down to select the previous pitch in that family.

The radial menu keeps the focused letter in place while scrolling repeatedly.
Its order runs clockwise, with A at the top, B at the upper right, and G at the
upper left whenever those letters are outside the center.

## Settings

When the optional Mod Menu and Cloth Config mods are installed, the Mod Menu
configuration button provides:

- inverted scrolling;
- note-label view distance from 1 to 32 blocks;
- interaction delay from 0 to 10 extra ticks, defaulting to the original
  one-interaction-per-client-tick speed;
- optional server-confirmation waiting;
- optional unobstructed line-of-sight enforcement;
- automatic note placement sequence enable/disable;
- a validated, whitespace-agnostic comma-separated pitch sequence using values
  from 0 through 24.

The placement sequence repeats for each newly placed note block. Its toggle key
is unbound by default and can be assigned in Minecraft's Controls screen. Every
time the sequence is enabled, it restarts at its first value.

The settings screen includes a complete `0` through `24` pitch-name guide.

## Multiplayer safety

Automated tuning sends ordinary vanilla use-block interactions. By default it
can send one interaction per client tick, matching the mod's original behavior.
Optional settings can add delay, wait for each resulting note change from the
server, and require an unobstructed line from the player to the block. Normal
interaction range is always enforced so the mod does not send packets that the
server must reject. These controls cannot guarantee compatibility with every
server's rules or anti-cheat configuration.

The mod sends ordinary, rate-limited right-click interactions and requires no
server-side installation. Keep the main hand in a state where a normal
right-click can tune the note block.

## Development

Requires Java 25. Build with:

```powershell
.\gradlew.bat build
```
