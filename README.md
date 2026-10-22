# OpenGGF Editor

A desktop level and scene editor for [OpenGGF](https://github.com/OpenGGF/OpenGGF), built with
[Dear ImGui](https://github.com/ocornut/imgui) through
[imgui-java](https://github.com/SpaiR/imgui-java).

> **Status: prototype.** This is the Dear ImGui editor spike moved out of the engine so it
> can grow as its own application. Expect rough edges and breaking changes.

OpenGGF keeps its own in-engine editor, designed for controllers and the game's look. This
project is the mouse-and-keyboard desktop editor: a menu bar, a toolbar, docked library and
inspector panels, a status bar and a searchable command palette, all drawn at window
resolution around the level, which stays at a crisp integer scale.

It is not a fork. The editor runs the OpenGGF engine as a library and plugs in through the
engine's `EditorWindowChrome` extension point. Every edit goes through the engine's editor
controller, so undo, saving, validation, export and playtesting behave exactly as they do
in OpenGGF.

## What it has today

- **Library:** ROM-decoded thumbnails of every block, chunk and pattern, with a hex filter
  and a size slider.
- **Inspector:**
  - World: the brush and the cell under the cursor.
  - Block: an 8x8 composition grid (click to paint, right-click to pick), flips, solidity
    and height-map index.
  - Chunk: a 2x2 grid, palette lines and priority.
  - Objects: stock object placement.
- **Toolbar:** mode, FG/BG layer, collision overlay and path, undo and redo, save,
  playtest from the cursor, and a clickable breadcrumb.
- **Command palette:** every editor command, plus OpenGGF's project commands (new, open,
  import, Save As, playtest).
- **Stageplay:** a directing workspace with a cast panel, actor/key inspector, frame
  scrubber and a chronological key/cue list. Direct movement, fixed sprite poses,
  animations, cameras, captions, sound, music and voice through the same authoring
  commands used by the in-engine editor.

## Stageplay

With a level open, press **F4** or choose **File → Stageplay workspace**. New actors use
**Puppet** mode: native character physics and floor snapping. Set the initial world centre near the floor;
snapping checks the native feet sensors up to 128 pixels below. Move the playhead and
choose **Move / jump / stop** or press **K**. A move specifies a destination and urgency
as a fraction of native maximum speed; the character tries to reach it through actual
acceleration and collision. Jump uses the native jump and a chosen button hold. Stop
brakes. The inspector shows actual position and moving, arrived or blocked state.
The **Direct** menu also exposes camera keys, shake/quake effects, scene changes,
dialogue, captions and audio cues. Property
forms use the engine's keyboard, mouse and controller controls; choose a field, edit
it, then use the form's final action to apply the change.

Choose **LIVE** in **Scene and set** (or an individual scene change) for native level
objects, rings, hazards and independently directed characters in one world. The
**Direct native actions** menu adds waits, rolling, crouching and looking up. S3K Tails
can fly toward a destination and hover; S3K Knuckles can glide. Release ends an ability
through its native controls. Movement can follow a placed object by its layout index,
expected object ID/subtype and local offset. Waits observe actual arrival, support,
landing, hurt, recovery or ring collection, with a finite timeout. The inspector shows
shared rings, hurt, ability state, supporting placement and target status; the timeline
retains those instructions and camera follow targets.

Current scenes use the same native-world owner for **LIVE** and **TERRAIN_ONLY**;
the latter clears initial objects and rings. Native performers use supplied-game
movement and ROM art over shared terrain, including saved editor sets. Film
dimensions and zoom work in both choices; completed-world resampling keeps dialogue
and captions separate. The source picture is bounded to 4096 pixels per side and
8 MiPixels; extreme framing fails explicitly. Logical-ROM image windows and
composites are transported to isolated preview/export workers without joined ROM
files. Versions one through five retain their original visible compatibility
policies until a shot is explicitly upgraded.

Camera keys can follow one actor or a group, with dead zones, look-ahead, smoothing
and offsets. Set **follow** to comma-separated actor IDs in the camera-key form.
Pan, shake, transitions and Q/E zoom are available in current native shots.
Compatibility LIVE shots retain their original 1x framing. Native activation bounds
and explicit route/jump authoring still apply; this phase does not add pathfinding.

**Space** plays or pauses; **Left / Right** steps a frame (**Shift** steps 60 frames).
**K** opens the selected actor's Puppet actions or Poser key at the playhead. **Ctrl+Z / Ctrl+Y** undo and redo;
**Ctrl+S** saves and **Ctrl+Shift+S** chooses a new path. **F1** lists every Stageplay
command. For Puppet actors, **Record actor** captures physics instructions: **A/D** directs movement,
release or **S** brakes, and **W** triggers a native jump. **Space** or **Stop** finishes
the take; one take is one undo step. Repeated preview reads and seeking use deterministic
physics replay, without advancing the live level.

In **Actor properties**, disable **Start on floor** for an airborne physical start,
or select **POSER** for deliberate poses or coordinate choreography. **Poser** mode retains
the position, animation and frame key editor and WASD position recording. Changing performance
mode shows an undoable conversion: Puppet retains shot placements and visibility;
Poser removes Puppet commands. Puppet placement resets only at shot boundaries;
interior keys control visibility. Existing version-one through version-three scenes
keep their existing Poser behavior when opened.

Press **C** for camera direction. **WASD / arrows** pan, **Q / E** step integer zoom around the film
centre (hold to repeat), and **Shift** pans faster. **K** captures the framing as a camera key; **R**
records a camera take with the same controls, committed as one undo step. **Space**
finishes recording. Framing remains a preview until captured; seeking discards unkeyed
framing. Numeric camera properties and key removal remain available in **Direct →
Camera keys**. Shake/quake effects have their own interval, amplitudes, frequency and
seed, so they layer over pan and framing without changing camera keys. Current native
scenes animate position and zoom with HOLD / LINEAR / SMOOTH easing and accept
fractional zoom (0.05x–64x); camera properties also offer 1x–4x presets. Fractional sampling
filters the completed world picture while dialogue and captions stay crisp. Compatibility
terrain scenes retain held zoom and their previous rendering; legacy LIVE stays 1x.
Current background parallax/deformation follows the directed camera using frozen native
effect phase. Separated performers activate independent native-sized regions sharing
the native object slot budget. Clear unnecessary objects or split the shot if it fills.

**Scene changes / transitions** adds, edits or deletes a switch to another ROM set or
relative `.ogfproject`. Choose a cut, black/white fade or left/right wipe. Transitions
are centred on the switch frame and cannot overlap. Inserting or moving a change adds
incoming actor and camera keys together; position those keys for the new shot. Removing
a change keeps its keys; physical actors normalize former placement keys to visibility
keys. Both editors save version-seven `.stageplay` documents and can open versions one
through six, preserving their existing world mode.

**Direct → ROM object cast / clear** lists the native registry and loaded placements.
Add a native object or released animal, or select an existing placement by its portable
layout index, object ID and subtype. **Free** runs its real native routine; arena-bound
bosses show **Free: native arena position, anchor ignored** with the owning routine.
Free roles require their authored source game, zone and act to match the current set.
A globally admitted boss still uses its native arena coordinates when that source
context is elsewhere; the tools do not relocate it. **Puppet** moves a visual graph
without running its AI, and **Pose** holds authored mapping frames. Child parts follow
their captured parent relationships. Pure ROM animation tables may play; state-machine
appearance remains held or authored, with exclusions shown in the inspector. The form
also exposes per-part frames, visual movement commands, individual removal and timed
**Clear badniks / Clear objects** commands. The same controls and document data are
available in the built-in editor.

**Direct → Dialogue lines** creates named speech panels. Choose a cast member or an
explicit offscreen speaker, then edit the text, interval and panel position. Actor
names appear immediately; text reveals along the timeline. A line's menu selects a
reusable **typer** (character speed and punctuation/space pauses) and **decoration**
(colors, padding, speaker pointer and optional PNG frame). These profiles can also be
created and edited from the Direct menu. Decorations can preserve PNG corners while
stretching edges and the centre; optional portrait PNGs belong to individual lines.

Use **Custom reveal timing** for exact character timing, with offsets measured from
the line's start; combining characters stay together. Long schedules are split into
editable groups and preserved when other fields change. **Use typer timing** restores
the reusable rhythm. Typing itself is silent; use an audio cue for a voice or sound.
Deleting a profile returns its lines to the built-in default in one undo step. Removing
a speaking actor offers to retain named offscreen speech or remove their lines.

Windowed authoring requests a temporary 16:9 window of at least **1280 × 720**, reduced
to fit smaller screens. Leaving Stageplay restores the previous window size and removes
those constraints; gameplay display settings are unchanged. Fullscreen and maximized
windows keep their placement. On smaller windows, Cast and Direction share a tabbed
side panel. **Edit → Film format presets** controls film dimensions independently;
the preview preserves film aspect with letterboxing, and sprites are never stretched.

Stageplay `.stageplay` files are shared with OpenGGF's built-in editor: save in either
interface and reopen in the other without conversion. Scene files reference your ROMs
and creator assets. Keep imported voice, decoration and portrait assets, and optional authored level
projects under the scene folder. Save As preserves relative asset paths and refuses
to move the scene away from assets still referenced by its undo or redo history. **File → Export film** uses the engine's shared exporter.
**F4** returns to level editing and keeps the scene and its undo history available.

The **TERRAIN_ONLY** world mode uses load-state scenery without live stock objects or
zone events. **LIVE** advances the production world as described above. Editor panel
positions and command-filter text are not saved into the scene.

## Requirements

- JDK 25 and Maven.
- An OpenGGF checkout of `develop` (0.7.prerelease), which has the `EditorWindowChrome`
  extension point. Without this editor, OpenGGF uses its own controller-first editor HUD.
- Your own ROM dump. OpenGGF loads all art and data from it. It supports:

  | Game | CRC32 | SHA-1 |
  |---|---|---|
  | Sonic the Hedgehog (World, REV01) | `AFE05EEE` | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
  | Sonic the Hedgehog 2 (World, REV01) | `7B905383` | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
  | Sonic 3 & Knuckles (locked-on) | `63522553` | `CFBF98C36C776677290A872547AC47C53D2761D6` |

  The ROM paths and every other setting are described in OpenGGF's
  [configuration guide](https://github.com/OpenGGF/OpenGGF/blob/develop/CONFIGURATION.md).

## Build and run

```bash
git clone https://github.com/OpenGGF/OpenGGF.git
git -C OpenGGF checkout develop
git clone https://github.com/OpenGGF/OpenGGF-Editor.git
cd OpenGGF-Editor
scripts/install-engine.sh ../OpenGGF   # builds the engine into ./.m2
mvn package                            # builds and tests the editor
mvn compile exec:exec                  # starts OpenGGF with the desktop editor
```

This project keeps its own Maven repository in `.m2/` (set in `.mvn/maven.config`), so
the engine you install here never mixes with any OpenGGF artifact in your `~/.m2`. Run
Maven from the project root, and rerun `install-engine.sh` whenever you update the
engine checkout.

The first start creates `config.yaml` in the working directory. Set your ROM path there,
or start from a directory where OpenGGF is already configured. At the title screen, open
**Tools → Editor projects** to create, open or import a level. This desktop provider
replaces the engine's built-in controller HUD. To use that HUD, run OpenGGF without
the desktop editor on its class path.

## Known limits

- **An internal ImGui error closes the program.** imgui-java ends the process after any
  internal ImGui assertion, and Java cannot catch it, so an unbalanced begin/end or
  push/pop would lose unsaved edits. Before relying on this editor, the code needs balanced
  helpers and a recovery save.
- **Untested in a real window.** Input focus, HiDPI and resizing have only been checked
  with offscreen rendering and simulated input.
- **No saved dock layout.** `imgui.ini` is disabled, so panels return to their default
  layout on every start.
- **The level-editing canvas is the engine's fixed camera view.** It is drawn at an
  integer scale. Stageplay camera keys provide authored pan and zoom for scenes.
- **No native image.** The editor runs on the JVM. The engine's GraalVM build has not been
  adapted to include it.

## Licence

GPL-3.0, the same as OpenGGF; see [LICENSE](LICENSE). Bundled third-party components:

| Component | Licence |
|---|---|
| [imgui-java](https://github.com/SpaiR/imgui-java), including Dear ImGui | MIT |
| [Noto Sans](https://github.com/notofonts/latin-greek-cyrillic) (`fonts/editor/NotoSans-*.ttf`) | SIL Open Font License 1.1, see `NotoSans-LICENSE.txt` |
| [Lucide](https://lucide.dev) icons (`fonts/editor/lucide.ttf`) | ISC, see `lucide-LICENSE.txt` |

OpenGGF and this editor are independent fan projects with no affiliation with Sega. No
game data is included.
