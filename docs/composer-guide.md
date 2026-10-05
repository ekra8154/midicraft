# Composer guide

The details behind the composer's features. For the overview, see the [README](../README.md).

## What gets built

A layer goes into the build if you can hear it and see it. **Active** layers are built; **Muted** and **Hidden** layers are left out. What you hear in preview is what the machine plays.

**Solo is the exception, on purpose.** Soloing is for listening around a part, not a decision about the song, so the layers it silences are still built. The status bar says so while any layer is soloed.

**A**, **M**, **S** and **H** set the selected layers to active, muted, solo or hidden. Each is a toggle: pressing the same letter again, once they're all in that state, puts each layer back to what it was doing before. Muting a hidden layer to hear it and then pressing M again hides it again rather than leaving it on.

Right-clicking a note selection offers **Solo selection**: only the selected notes play, until the selection is dropped.

Because muting a layer to listen around it is easy to forget, the status bar shows how many layers will be built (`Build: 12 of 15 layers`), and starting a paste warns when some are muted or hidden.

## Notes and layers on one keyboard

Shortcuts act on notes or layers depending on what's selected, not on which panel you clicked last:

- **Ctrl+A** always selects notes: every note on the selected layers, or on every layer if none is selected. To select every layer, click the first and shift-click the last.
- **Ctrl+E** always merges the selected layers.
- **Ctrl+C**, **Ctrl+X** and **Ctrl+D** act on the selected notes if there are any, and on the selected layers if not. Layers are copied whole: name, instruments, state and notes.
- **Ctrl+V** pastes whatever was copied last (there is one clipboard). Layers go directly below the lowest selected layer, or at the end if none is selected. Notes go at the playback marker. **Ctrl+Shift+V** pastes notes back where they were copied from.
- **Delete** and **Backspace** remove notes, never layers. Delete a layer from its right-click menu.
- **Escape** drops the selected notes first, then the selected layers.
- **Ctrl+1** to **Ctrl+0** move the selected notes onto layers 1 to 10.

Changing which layers are selected drops the note selection, so Ctrl+C can't copy notes you picked earlier when you meant the layers. Right-clicking a layer keeps the notes, so **Move selected notes here** has something to move.

Undo and redo cover everything in one history, whichever panel did it.

## Copying and repeating

Dragging a box over a passage selects the notes in it and leaves a **selection range**: a tinted band across the roll with its length written in it, and a bracket with a handle at each end along the bottom of the ruler.

The range is how far Ctrl+V and Ctrl+D step, because a passage ends on silence and the notes alone don't say how much. Drag either end of the bracket to change it: pull the right end past the last note to add a rest between repeats. Right-click the bracket to drop the range without dropping the selection.

- **Ctrl+V** pastes at the playback marker and then moves the marker on by the range, so pressing it again continues the passage instead of stacking a second copy on the first.
- **Ctrl+D** duplicates the selection right after itself and moves the selection onto the copy, so pressing it again adds another repeat. Every note stays on its own layer.

Selections made without a box (Ctrl+A, or the Select menu) have no range, so the length is guessed from the notes: the last note plus the tightest gap between any two of them. The guess is drawn as a thin line in the same strip before you use it.

## Tempo and speed

Two numbers decide how fast a song plays, and they multiply.

The **tempo** is the song's own, shown in BPM. The **Speed** slider is a ratio on top of it, from 0.25x to 8.00x. It's not just for preview: it's saved with the song and the build plays at it, so a song at 150 BPM and 2.00x really is a 300 BPM song.

The status bar shows both, like `150 x 2.00 = 300 BPM`, and the song's resolution in ticks per beat. The resolution is what the note-value grid is counted in; it has no effect on how long anything lasts.

**Edit > Apply speed to the tempo** folds the slider into the tempo and puts it back to 1.00x. The song sounds exactly the same; only the number written in it changes. Convert for Minecraft does this first.

## The snap grid

The Snap button offers two kinds of grid.

**Note values** (1/4, 1/8, 1/16, 1/32) follow the song: a 1/16 is a sixteenth of a quarter note whatever the tempo. Use them for writing music.

**Repeater tick** and **game tick** follow real time. A repeater tick is 100 ms, the shortest gap one lane can build; a game tick is half that, which needs a two-lane build. Their lines are drawn where those moments actually fall, which on a song whose tempo doesn't divide into them means visibly off the beat. That's the information, not a fault: Convert for Minecraft is what moves the tempo until the two grids agree.

Because they measure different things, the same setting means different amounts of real time in different songs. 1/16 is one repeater tick at 150 BPM, two at 75, and half of one at 300. The status bar shows the translation (`grid 1/16 = 1 repeater tick`), and the Snap button turns amber when its grid isn't one the build can place notes on.

Bar lines and numbers are drawn whatever the snap is set to.

## Convert for Minecraft: out-of-range notes

A note block plays two octaves, F#3 to F#5. The **Out-of-range notes** setting decides how Convert brings everything else into range. All four modes only ever move notes by whole octaves, so the song stays in key.

- **Shift the notes** moves only the notes that are out of range, each by its own nearest octave. Everything else stays exactly where it was written.
- **Shift the layer, then the notes** moves the whole layer to wherever the fewest of its notes are out of range, then shifts whatever is still out. Fewer split layers and the part keeps its shape, but notes that were fine can move.
- **Split into melodic** moves the stray notes onto a melodic split layer, which plays F#1 to F#7 at true pitch, and leaves the rest of the part on its own instrument. Adds one layer per part that needs it.
- **Convert to melodic** turns the whole part into a melodic split layer as soon as one note needs it. Adds no layers at all, which helps near the layer limit.

The melodic tiers overlap by an octave, and a split layer sounds every instrument whose bracket covers a note. Notes in an overlap therefore build twice until you drag a bracket in.

**Split transposed notes into layers** puts notes that Convert moved by an octave on a layer of their own, named for the shift, so you can see what moved, mute it or move it back. It doesn't apply to the two melodic modes.

## Markers

A marker is a named position on the timeline. Nothing is built from it and it makes no sound; it's a label for finding parts of the song.

**B** adds one at the playback marker, or removes the one already there. **Edit > Markers** has the same actions. Markers sit in a strip above the ruler, which only appears once the song has one. Click a marker to jump to it, double-click to rename it, right-click to remove it, or click an empty part of the strip to add one there.

There's one marker per tick, so adding one where another stands renames it. Markers are saved with the song, move with **Snap to song start**, and can be undone.

## Sound effects

The instrument palette has two tabs. **Instruments** holds the tuned note block voices. **Sound effects** holds blocks that make their own noise when redstone reaches them: trapdoors, doors, a fence gate, a shelf, a bell, a copper bulb, a dropper, a piston, a sculk shrieker, and the note blocks that wear a mob head.

- **They aren't tuned.** Every hit sounds the same, so the row a hit sits on is just a place to put it, and two hits on the same tick are one hit. Convert leaves them alone.
- **They carry different distances.** Most are heard up to 16 blocks away; a bell 32, a mob head note block 48, and a sculk shrieker 80. Every tuned instrument is 48.
- **Most make a second sound when the power leaves** (the door shuts, the piston pulls back), so that sound always follows a moment after the first.
- **In a build** each effect takes the same cells as a note. Most of them don't pass a signal on, so a chord containing one is built as a bus. The in-world sequencer hands you the effect block itself instead of a note block.
- **NBS can't hold them**, so sound effect layers are left out of an `.nbs` export and the result message says how many.

## Importing by drag and drop

Drag a `.mid`, `.midi`, `.nbs`, `.nbt`, `.schem` or `.litematic` file onto the Minecraft window to import it. This works in the song library, the composer and the file browser. The file's extension picks the importer.

- Dropping a folder onto the file browser opens it.
- Dropping several files imports the first one that can be read, and says so.
- Dropping onto the composer asks about unsaved changes first, like the Import menu does.

## Sorting the lists

The song library and the file browser list the newest first by default. The **Sort** button beside the search box switches to A to Z, and the choice is saved for both lists. Folders in the file browser always stay alphabetical.
