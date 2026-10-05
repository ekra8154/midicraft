# Midicraft

Midicraft creates a dedicated piano-roll composer inside the game, provides tooling tailored for Minecraft note blocks, and allows you to paste those songs into the game!

Midicraft is a client-side mod. Import songs from a variety of sources, and shape them into real Minecraft songs that redstone can play! Unlike other note block song creation tools, Midicraft machines can play notes up to 20x/sec using half ticking, meaning they have double the precision without modifying the tick speed or using custom resource packs. It's also focused on survival feasibility, highly optimized for compactness of builds, and offers a sequencer to build simpler songs on the fly without the need for mods such as Litematica.

## Features

 - DAW-like composer built into the game, complete with Minecraft instruments
 - Import songs in any way, including scanning existing builds in-world
 - Auto-assigns instruments based on MIDI source
 - Supports chords of up to 30 notes, allowing complex and dense songs
 - Easy-to-use tools for advanced noteblocking techniques, tailored to creating the best sounding vanilla compatible songs
 - Optimized for compactness and resource usage
 - Highly customizable pasting, including the height and width of any song
 - Builds are pasted with vanilla commands at a customizable rate
 - In-world sequencer HUD to create simpler songs without a schematic
 - In-world noteblocking tools and overlays


## Quick Start
 - Open the composer with a keybind (unbound by default), through mod menu, or with /midicraft
 - The mod binds no keys by default, but supports binding composer, in-world sequencer, and overlay tools


## The Composer
 - Click to place notes. Right-click (or right-drag) to erase; with notes selected, right-click opens a menu instead. Start a song from scratch, or import from the file button at the top.
 - Click and drag to create a selection. Move multiple notes, or copy/cut and paste based on the selection
 - Create layers with the panel on the left. Shift click to select a range of layers, or ctrl click for individual. Operations will affect all selected layers
 - Change layers' instrument(s) with the panel on the left. One layer can have multiple instruments, even of the same kind (which will make it louder)
 - Instrument switching: Right click a layer and hit "convert layer: melodic". This distributes all instruments into brackets based on their true octave pitches, allowing a layer to have 3x the note range from normal.
 - "convert to percussion" and "convert to sfx" options do similar things, but for Minecraft percussion notes and for various sound making blocks
 - Minecraft notes cannot be held, but "sustained notes" simulates it with restrikes. Right click a layer to bring up its sustained menu and enable sustaining. MIDI files retain their original sustain trails on import
 - The composer highlights out of range areas in red, but still allows them to play in the preview
 - The bottom bar shows the song's status: whether it is buildable in-world or not, and why
 - Edit has many tools to make songs buildable in-world, such as quantization, fitting into range, and tempo snapping
 - "Convert for Minecraft" is a beginner friendly way to make any song buildable. It is not guaranteed to make an imported song sound the most faithful possible however
 - Dense songs, especially with melodic layers, composite layers (that use multiple instruments), and restrikes, reach the chord limit of 30 very fast. Chord thinning will skip copies of the same note to make builds stay within the specified limit
 - Playback speed does affect how fast the song plays in-world once pasted. Combined with quantization, it can be a good technique to find a balance between adjusting the original song's tempo and allowing notes to move to work with Minecraft.
 - Export any Midicraft song as an nbs file. Playback speed is baked in as tempo, split and composite layers are expanded, and restrikes become individual note hits


## Build Pasting
 - Paste a build with Song --> Paste current sequence in world
 - Pasting uses /setblock, so it needs permission to use commands (singleplayer with cheats on, or operator on a server). Without it, build simpler songs by hand with the in-world sequencer
 - Midicraft machines always fill a width in the +x direction, and expand in the +z direction in a snaking fashion
 - There is no hologram preview, so be sure there is nothing in the way of the build or it will be overwritten by the paste
 - Machines with multiple floors snake up/down first, then left to right. This ensures the music audibly moves gradually forward
 - Specify whether the machine starts at the top or the bottom floor
 - In settings, specify each of the paste blocks
 - Turn on light show to watch the song light up as it plays, or color coded to see the different chord types and how padding is distributed
 - Builds are NOT guaranteed to work perfectly at any given config (mainly extremely dense and/or fast songs). The paste screen should report issues before ever pasting. If that happens, change the dimensions or other settings, or paste anyway
 - On flat lane, a chord can play up to 33 notes. However, due to complications with turns, climbs, and edge cases, the limit is 30

## Half Ticking
 - Builds may be one lane (no half ticking), or two lanes (use half ticking)
 - Most redstone components use repeater ticks (the length of 1 repeater delay), but Minecraft operates on game ticks, which are twice as fast
 - So 1 repeater tick = 2 game ticks. There are 10 repeater ticks/sec, and 20 game ticks/sec. Since most redstone components take 2 game ticks, they cannot operate between repeater ticks
 - Pistons extend in 3 game ticks (1.5 repeater ticks). By creating a second lane in the build that starts with pistons, Midicraft can play notes that fall in between repeater delays. This allows double the precision, and makes many Midicraft songs able to sound much closer to their source
 - When a Midicraft song is converted for game ticks or reports "2 lanes needed" at the bottom, it will use half ticking
 - With 2 lanes, Midicraft will maintain 2 interleaved lanes throughout the entire song. They work together to play the notes both on "even" ticks and "odd" ticks
 - Using pistons, the lanes can dynamically switch between playing even and odd notes, allowing them to distribute the work equally. This makes builds more compact at the cost of adding pistons (which make noise)
 - Sort of like Midicraft is a CPU kernel delegating work between two threads
 - However, due to the uneven distribution of notes, the two lanes still must pad with redstone frequently in order to ensure the sounds happen close enough together that the song doesn't sound disjointed
 - Allowed drift and catch up amount are configurable, but this means two lane builds will almost always be less space efficient than one lane builds

## In-World Sequencer
 - Build simpler songs by hand, block by block, on any server. No operator permission or schematic mod needed
 - Only available for songs that build as one lane. Songs that need half ticking have to be pasted
 - The song open in the composer is the sequence. Bind the Placement sequence key and hold it to see the HUD: the next note block or repeater is in the center, with the steps before and after it on either side
 - Place blocks in order. Each note block and repeater is tuned automatically once it's placed, and a wrong block doesn't advance the sequence
 - Chords are built two notes per block, shown as two rows in the HUD
 - Double tap the key to pause and resume it. Hold the key and scroll to step backward or forward. Your place in each song is saved
 - Optional settings can select the right block from your hotbar for you, make the instrument block under each note its own step, and change the order chord notes are placed in

## In-World Tools
 - Off by default. Turn them on in the In-world tools settings
 - Nearby overlays label the note blocks and repeaters around you with their pitch or delay
 - The interactive overlay is on the block you're aiming at. Hover a note block's label to open a radial A to G menu and scroll to retune it, or scroll a repeater to change its delay
 - Tuning sends ordinary right-click interactions at a limited rate. The Server Friendliness settings can slow it down, wait for the server to confirm each change, or require a clear line of sight

## Tips
 - If you can make a song sound good as a one lane build, it will be more compact than if it was two lanes (and will use no pistons)
 - Quantize to repeater ticks or game ticks will always place the notes onto either a one lane or two lane compatible grid
 - If the high notes of a song barely don't fit, transpose the whole song down a few notes. It won't be true to the original key, but it will still sound like the song
 - The main melody of a song often benefits from being converted to a melodic layer (instrument switching) so that the high and low notes don't have to be transposed to fit
 - Many song speeds don't work with Minecraft well. Using one technique alone, such as tempo snapping, can significantly change the tempo of a song, even if note relations are kept perfect. Quantization on its own can cause the song to sound different, even though the tempo remains accurate
 - Combine repeater and game tick quantization with playback speed and tempo snapping / the Convert for Minecraft button to achieve a good balance of offset notes and modified tempo to make the song sound most accurate

## Shortcuts

| Key | Does |
|---|---|
| Space / Enter | Play from the marker / from the start |
| R | Record |
| B | Add or remove a marker |
| A, M, S, H | Set selected layers to active, muted, solo or hidden |
| Ctrl+Z / Ctrl+Y | Undo / redo |
| Ctrl+C, Ctrl+X, Ctrl+V | Copy, cut and paste notes or layers |
| Ctrl+Shift+V | Paste notes back where they were copied from |
| Ctrl+D | Duplicate the selection right after itself |
| Ctrl+E | Merge selected layers |
| Ctrl+1 to Ctrl+0 | Move selected notes to layer 1 to 10 |
| Ctrl+A | Select all notes |
| Ctrl+S / Ctrl+O / Ctrl+I | Save / open / import |
| Ctrl+scroll / Ctrl+Alt+scroll | Zoom time / zoom pitch |
| Shift+scroll / Alt+scroll | Scroll fast through time / scroll pitch |
| Escape | Step out of the selection, then close |

## License

MIT