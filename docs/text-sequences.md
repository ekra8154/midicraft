# Text sequences

A song can be written as plain text: a list of note block pitches and repeater delays. The song library's **New from text** creates a song from it, and **File > Copy sequence as text** writes the open song out in the same format.

## Format

```
0, 4, 7, 2d, 5, 2d, 12, 4d
```

- A **number from 0 to 24** is a note, given as note block pitch (the number of right-clicks on a fresh note block). 0 is F#3, 12 is F#4 and 24 is F#5.
- A number followed by **d** is a delay in repeater ticks, from `1d` to `64d`. Delays longer than 4 are built as several repeaters, using as few as possible: `10d` is `4d + 4d + 2d`.
- Notes with no delay between them play together as a chord. Above, `0, 4, 7` is one chord.
- Entries are separated by commas or by spaces.

## Layers

Each line is its own layer, and every line starts at the same moment, so lines play together rather than one after another.

A line can start with a name and an instrument, which is how **Copy sequence as text** writes them:

```
Melody [FLUTE]: 12, 2d, 14, 2d, 16, 4d
Bass [BASS]: 0, 4d, 5, 4d
```

A line with no header becomes "Layer 1", "Layer 2" and so on, on the default instrument.

Instrument ids: `HARP`, `BASS`, `BASEDRUM`, `SNARE`, `HAT`, `GUITAR`, `FLUTE`, `BELL`, `CHIME`, `XYLOPHONE`, `IRON_XYLOPHONE`, `COW_BELL`, `DIDGERIDOO`, `BIT`, `BANJO`, `PLING`, `TRUMPET`, `TRUMPET_EXPOSED`, `TRUMPET_WEATHERED`, `TRUMPET_OXIDIZED`.

## Limits

Text sequences can only say what a single repeater lane can build: notes on whole repeater ticks, inside the note block's two octaves. When you copy a song as text, out-of-range notes are left out (the result message says how many), and timing between repeater ticks is lost.
