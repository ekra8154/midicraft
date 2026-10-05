# Paste looks: Normal, Light show and Color coded

The paste screen has a button that cycles through three looks for a build. The machine itself (where every block goes and how the signal runs) is the same in all three; only what some blocks are made of changes.

## Normal

The plain build, made of the blocks set on the Build Pasting tab of the settings: the lane blocks for each machine, the relay block, the transparent block for climbs, and the support block under each floor. By default these are stone slabs (laid as top slabs) and stone.

The block under a note is never swapped, because that block is the note's instrument.

## Light show

The plain build, plus redstone lamps along the song's path: the buses, stacked chords, cut heads, foldbacks and the top rail become lamps, so the song draws a glowing line as it plays. Plain lane and padding keep your paste blocks, and nothing under a note changes.

Lamps conduct redstone the same way stone does, so a light show build plays exactly like a normal one.

## Color coded

For debugging. Every block of the lane is coloured by what placed it, so you can see the chord types, where padding went, and where anything went wrong. Faults are built as they are, so a color coded build with faults won't play correctly.

The lane and relay blocks stay stone (stone is one of the colours); climbs and supports keep your paste blocks. The block under a note is never recoloured.

### Colour key

| Block | Means |
|---|---|
| `stone` | The lane: wire, repeaters, corners, staircases (machine A, even half of the game tick) |
| `stone_bricks` | Machine A's plain lane while it plays the odd half of the game tick |
| `tuff` | A standard bus. On a two-lane build, also machine B's plain lane on the even half |
| `tuff_bricks` | Machine B's plain lane while it plays the odd half of the game tick |
| `polished_tuff` | A sunken bus: a bus whose first cell is a note block, so it carries three notes for free |
| `andesite` | A standard stacked chord |
| `deepslate` | A stacked bus |
| `cobbled_deepslate` | A stacked bus whose tail ended in a single column |
| `deepslate_tiles` | The head of a chord that was cut across a staircase |
| `smooth_basalt` | A double rail |
| `polished_basalt` (lying along x) | A foldback cut on a descent |
| `polished_basalt` (standing up) | A foldback cut on a climb |
| `spruce_planks` | Parity padding: a chord moved to land on its beat |
| `dark_oak_planks` | Busy padding: a column spent to free the slots behind, so a chord can be cut |
| `birch_planks` | Corner padding: the columns a turn costs |
| `acacia_planks` | Closing padding: wire out to the wall, where a chord could not be cut |
| `bamboo_planks` | Any other padding |
| `sticky_piston` | A parity seam (a real part of the machine, not a colour): it moves a lane to the other half of the game tick |

### Faults

| Block | Means |
|---|---|
| `stripped_crimson_hyphae` | Lane standing outside the width you asked for |
| `red_nether_bricks` | Wire the signal never reaches |
| `waxed_copper_bulb` (lit) | A note that would sound at the wrong moment |
| `dragon_head` | A note with nothing to set it off |
| `sea_lantern` | A cell two parts of the machine both wanted (a collision) |

Dead wire wins over a breach where both apply. The paste screen lists every fault before you paste, and hovering a fault line shows where it is.
