# OpenGGF Editor

A desktop level editor for [OpenGGF](https://github.com/OpenGGF/OpenGGF), built with
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
- **The canvas is the engine's fixed camera view.** It is drawn at an integer scale, with
  no zoom or pan yet.
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
