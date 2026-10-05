# Debug commands

Two extra subcommands for testing build layouts. They're off by default: turn on **Debug commands** on the Debug tab of the settings. The switch takes effect immediately, and while it's off the commands refuse to run.

## /midicraft paste

Builds a run of chords you type out, instead of a song. Useful for reproducing a layout fault without the rest of the song around it.

```
/midicraft paste [layout] [dry] <width> <floors> [flat|up|down [on <floor>] [turning] [<columns to wall>]] <chords>
```

- **width** (1 to 512) and **floors** (1 to 16) are the size of the whole paste, like the paste screen's width and floors.
- **dry** reports the result in chat (size, walls, faults, collisions) without placing anything.
- **layout** picks a paste layout by name (for example `interleaved_half_tick`, or `v1` / `v2` for the ultra compact lanes). Left off, it builds what the Paste button would.

### Chords

Chords run to the end of the line, separated by spaces. Each one is a number of notes:

| Spec | Means |
|---|---|
| `18` | A chord of 18 notes |
| `30x4` | Four chords of 30 |
| `18@1` | A chord of 18 with a gap of 1 tick before it (the default gap is 4) |
| `7:7b` | A chord of 7, with instruments given after the colon |

Every note is the same pitch. Instrument letters: `p` harp (air under the note), `h` hi-hat (glass, doesn't carry power), `s` snare (sand, falls), `b` bell (gold block). Notes not named are harp.

### Wall shapes

Naming a wall shape starts the lane as though it's already heading for that kind of wall:

- `flat` a turn on the same floor, `up` a climb, `down` a descent.
- `on 3` says which floor the lane is on (counted from 1). Left off, it's the only floor that shape can happen on.
- `turning` has the lane arrive already committed to the bend.
- A number after the shape is how many columns from the wall the first chord stands (0 is hard against it). Left off, the walk places the chords itself, the way a real song would.

A single chord with no distance has to be written as `18x1` or `18@4`, since a lone number right after the shape is read as the distance.

### Examples

```
/midicraft paste 12 1 6 2 18                 three chords, 12 wide, one floor
/midicraft paste 36 1 30x4                   four chords of thirty
/midicraft paste dry 24 2 5x8@4 30@1         reported, not placed
/midicraft paste 36 3 down 12 18 30          heading for a descent, first chord 12 columns off
/midicraft paste 28 5 up on 3 0 25x8         a climb from floor 3, hard against the wall
/midicraft paste 36 1 flat turning 12 30 5@1 5 5
/midicraft paste 40 1 7:7b 7:7h              a stacked seven over gold, then over glass
```

## /midicraft asciidiagram

Draws a region of the world as text, one slice at a time, and puts it on your clipboard (chat is too narrow to print it).

```
/midicraft asciidiagram <from> <to> [view] [up] [notes] [signs]
```

- **from** and **to** are corners, taken like `/setblock` coordinates: `~` works, and pressing Tab fills in the block you're looking at. `^` coordinates aren't supported. The box can be up to 40,000 blocks.
- **view** is which way you're looking: `north`, `south`, `east`, `west`, `top` (the default) or `bottom`.
- **up** (only for `top` and `bottom`) is which compass direction is at the top of the page. Default north.
- **notes** (`true`/`false`) labels each note block with its note.
- **signs** (`true`/`false`) turns signs with writing into footnotes in the legend.

```
/midicraft asciidiagram 13 72 108 15 76 113 east
/midicraft asciidiagram ~-8 ~ ~-8 ~8 ~4 ~8 top south true
```
