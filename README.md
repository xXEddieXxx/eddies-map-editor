<p align="center">
  <img src="docs/icon.png" alt="TripleA icon" width="128" height="128">
</p>

<h1 align="center">Eddie's Map Editor</h1>

<p align="center">
  <b>Make maps for <a href="https://github.com/triplea-game/triplea">TripleA</a> on one screen, without editing a single file by hand.</b>
</p>

<p align="center">
  <a href="https://github.com/xXEddieXxx/eddies-map-editor/releases/latest/download/eddies-map-editor-windows.zip"><b>⬇️ Download for Windows</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/xXEddieXxx/eddies-map-editor/releases/latest">All downloads</a>
  &nbsp;·&nbsp;
  <a href="https://buymeacoffee.com/xeddie">☕ Buy me a coffee</a>
</p>

TripleA is the open source turn-based strategy game. Make a new map from a picture or open an
existing map folder. Then change its territories, nations, units and rules, and play it.

![The overview: what a map's folder holds, and the next steps to a playable map](docs/screenshot-overview.png)

![Territories: the map as the game shows it, with each territory's owner](docs/screenshot-territories.png)

## Get started

1. **Windows:** download
   [`eddies-map-editor-windows.zip`](https://github.com/xXEddieXxx/eddies-map-editor/releases/latest/download/eddies-map-editor-windows.zip),
   unzip it anywhere and start `Eddies Map Editor.exe`. It brings its own Java and runs with your
   installed [TripleA](https://triplea-game.org/download/), which it finds by itself or asks for.
2. **macOS and Linux:** download `eddies-map-editor-<version>.zip` from the
   [latest release](https://github.com/xXEddieXxx/eddies-map-editor/releases/latest), install
   [Java 25](https://adoptium.net/) and TripleA, and start `bin/eddies-map-editor`.
3. On the start page, open a map folder (or one of the recent maps), or make a new one.
   The editor opens and saves maps in TripleA's maps folder, where TripleA finds them.
4. **Play (F7)** tries the map. It starts your installed TripleA if the editor finds one, so you
   test in the version your players use. *Map → TripleA for Play…* picks another TripleA.

Each release names the TripleA release it was tested with.

## What it does

The tools sit in the sidebar in the same groups as below. Every change can be undone, and
**Check (F6)** tells you at any time whether TripleA can load the map.

### 🏠 Start a map

- **Open** a map folder or one of your recent maps.
- **New map wizard:** name and folder, a picture (or *Skip* to add one later), and who plays.
- The editor writes every file a map needs: `map.yml`, the game XML, `polygons.txt`,
  `centers.txt`, `place.txt`, `map.properties`, tiles, flags and unit pictures.

### 🗺️ Map

| Tool | What you do there |
|------|-------------------|
| **Territories** | Find territories in the picture (areas between dark lines, blue = sea). Rename them, set owner, PUs, capital, victory city and starting units. |
| ↳ *Paint* | Give many territories an owner, PUs, units, rules or effects at once by clicking or dragging. |
| ↳ *Draw borders* | Draw border lines into the map picture by hand (like its lines, black or grey; width in px), then *Find territories* turns the areas between them into territories. |
| ↳ *Split with a line* | Cut one territory in two; the new border is drawn into the picture in the same line style. |
| ↳ *Move places* | Drag where the game draws units, names, flags and markers. |
| ↳ *Decorations* | Place pictures from the map's `misc` folder anywhere on the map. |
| **Connections** | Which territories are neighbours. |
| **Canals** | Click two sea zones and the land between; choose the units that pass freely. |
| **Terrain effects** | Effects like forest: what they do to each unit type, and their picture. |
| **Map look** | Unit size and tint, scrolling, small map, relief (loaded or created), mouse pointer. |

- **Redraw borders:** changed the picture in a paint program? *Picture menu ▾ → Redraw borders
  from a picture…* keeps names, owners and units; new areas become territories you name.
- Over a picture the map shows the picture's own lines as borders.

### ⚔️ Players

- **Nations:** color (shown live on the map), alliance, starting PUs, who plays it, and its own
  rules such as capitals to hold or unit limits. *In the nation's color* tints its unit pictures.
- **Politics:** relationship types (war, allied, neutral, what each allows) and how nations start.
- **Technology:** the technologies, who researches what, and what each nation starts with.

### 🛡️ Units & economy

- **Units:** cost, attack, defense, movement and hit points in one row; every ability in groups;
  the support a unit gives others, written as a sentence.
- **Unit pictures:** upload one picture, or import a whole folder for a nation.
- **Production:** what each nation can buy and repair, at which cost, and the resources with
  their icons and starting amounts.

### ⏱️ Game flow

- **Turn order:** each step in plain words ("Buy units"), grouped per nation; add, move, remove.
- **Game options:** in sections, with a lock for what players may change, and variables.
- **Triggers:** every trigger in plain words, plus an assistant that builds one step by step,
  starting from examples like *Give PUs when a nation holds territories*.
- **Actions:** political actions (declare war) and user actions, with their texts, conditions,
  cost and chance.

### 💬 Texts & sound

- **Texts:** objectives, trigger messages, unit tool tips and action texts, with an HTML preview.
- **Game notes:** a text editor with formatting buttons and a live preview.
- **Sounds:** the game's sounds, per nation too, with your own mp3 files.

### 🔧 Advanced

- **Attachments:** every attachment of the game in a table, for what no other tool covers.
- **Files:** the map folder as a tree beside a text editor that checks your files as you type;
  with no file open it explains what a map folder holds and what is missing.

### Always at hand

| | |
|---|---|
| **Check (F6)** | Loads the map the way TripleA does and lists the problems. |
| **Play (F7)** | Starts your installed TripleA with the map. |
| **Export** | Zips the map for sharing. |
| **Undo / Redo** | For every change. |
| **Safe saving** | Only what changed is written, down to single picture tiles; the first version of each file stays as `.bak`. |

## Support the project

Eddie's Map Editor is made in my spare time and is completely free. If it saves you an evening of
editing XML, you can buy me a coffee.

<p align="center">
  <a href="https://buymeacoffee.com/xeddie"><b>☕ Buy me a coffee</b></a>
</p>

Bug reports, ideas and feedback are just as welcome, see the
[issue tracker](https://github.com/xXEddieXxx/eddies-map-editor/issues).

## For developers

### Requirements

- **JDK 25.** Gradle runs with the Java in `JAVA_HOME`.
- **An installed TripleA.** The editor uses TripleA's engine (map XML model, game parser, unit
  pictures and look) from its jar, `bin/game-headed-*.jar`. Gradle looks in
  `%ProgramFiles%/TripleA` (macOS: `/Applications/TripleA.app/Contents/app`); for another place pass `-PtripleaHome=<folder>`, eg the unzipped
  `game-headed.zip` of a [TripleA release](https://github.com/triplea-game/triplea/releases).

### Run, test, package

```bash
./gradlew run       # start the editor
./gradlew test      # the tests
./gradlew appZip    # the program with its own Java, for this system, in build/distributions
./gradlew distZip   # the zip for every system, needs Java 25
```

### Release

Releases are built by the **Release** workflow: *Actions → Release → Run workflow*, enter the
version (eg `1.2.0`) and, if needed, the TripleA release to build with (default: the newest). It runs the
tests, builds the Windows program and the zip for every system, and publishes them as release
`v<version>`. The *Download for Windows* link above always points to the newest one.

### Where things are

All code is in `src/main/java/eddies/mapeditor`:

| Class                                                                       | What it is                                                                                    |
|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `EddiesMapEditor`                                                           | Starts the editor                                                                             |
| `MapMakerWindow`, `Sidebar`                                                 | The window: the header with the map's menu, the tools by name on the left, the chosen tool    |
| `MapProject`                                                                | The open map: its game and files, undo and redo, saving                                       |
| `MapXml`                                                                    | Reads and changes the game XML model; each change makes a new model                           |
| `RulesXml`, `ProductionXml`, `TechXml`, `GameRulesXml`                      | The same for turn order and politics, production, technology, options, dice and variables     |
| `ActionsXml`                                                                | The same for political and user actions                                                       |
| `UnitsXml`, `UnitOptions`                                                   | The same for a unit type's options and the support it gives                                   |
| `CanalXml`, `TerritoryEffectsXml`, `TerritoryXml`                          | The same for canals, territory effects and a territory's other options                        |
| `MapFiles`, `MapFolder`                                                     | The files beside the game XML: outlines, centers, pictures, `map.properties`                  |
| `MapPicture`                                                                | Finds territories, sea zones and neighbours in a picture                                      |
| `MapCanvas`, `MapView`                                                      | The map on screen                                                                             |
| `PlayWith`                                                                  | Which TripleA Play starts                                                                     |
| `HomeEditor`, `TerritoriesEditor`, `NationsEditor`, `UnitsEditor`, ...      | The tools                                                                                     |

## License

GPL-3.0, like TripleA, whose code this editor builds on. See [LICENSE](LICENSE).
