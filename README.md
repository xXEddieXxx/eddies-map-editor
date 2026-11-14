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
   It needs a TripleA from June 2026 or newer (older ones lack the look the editor uses); with an
   older one it says so at its start.
2. **macOS and Linux:** download `eddies-map-editor-<version>.zip` from the
   [latest release](https://github.com/xXEddieXxx/eddies-map-editor/releases/latest), install
   [Java 25](https://adoptium.net/) and TripleA, and start `bin/eddies-map-editor`.
3. On the start page, open a map folder (or one of the recent maps), or make a new one.
   The editor opens and saves maps in TripleA's maps folder, where TripleA finds them.
4. **Play (F7)** tries the map. It starts your installed TripleA if the editor finds one, so you
   test in the version your players use. *File → Play with…* picks another TripleA.

Each release names the TripleA release it was tested with.

## What it does

The tools sit in the sidebar in the same groups as below. Every change can be undone (but lines
drawn into a very large picture, which the editor says first), and
**Check (F6)** tells you at any time whether TripleA can load and start the map. *File → Show all options*
opens every `▸ More options` row in every tool, also the next time; the File menu and the start
page show the editor's version.

**Home** shows the next step to a playable map; *Go to* opens its tool ready for it, with the
step, its progress and *Next ›* over the map. Beside it is an overview: the map small in its
owners' colors (problems outlined red; a click shows the territory) and a tile per tool with what
the map has there and its problems; a click on a tile opens the tool. What the steps already say
(a nation without land or capital) is no problem beside them (but for a game of just Russians,
Germans, British, Japanese and Americans, which TripleA stops without their capitals), and a territory without neighbours
is listed apart under *Worth a look*, as many finished maps have one on purpose; so are a nation's
unit types and flags the saved map has no picture for (the game would draw a placeholder). What you meant so
gets *Fine as it is* on its row: it then shows and counts nowhere, kept with the map, and is listed under
*Marked as fine* with *Count again*; what keeps TripleA from loading or starting the game cannot be marked.
A Check stays good over a save. A new map shows
only the tiles of its steps. An opened map is fitted to the view once (at most at 100 %). Where a setting comes from another tool, a small
link leads there, and a remove that triggers still need offers *Show in Attachments*.

### 🏠 Start a map

- **Open** a map folder or one of your recent maps.
- **New map wizard:** name and folder, a picture (or *Skip* to add one later; once chosen, the
  wizard shows the territories and sea zones it found, outlined), and who plays (three nations to
  start with).
- The editor writes every file a map needs: `map.yml`, the game XML, `polygons.txt`,
  `centers.txt`, `place.txt`, `map.properties`, tiles, flags and unit pictures.

### 🗺️ Map

| Tool | What you do there |
|------|-------------------|
| **Territories** | Make territories from the picture (areas between dark or thin grey lines, blue = sea). Rename them, set owner, PUs, capital, victory city and starting units (a × removes a row, as does a number of 0; *Remove all* clears them). A click on any island of a group chooses the group, not the sea around it. |
| ↳ *Paint* | Give many territories an owner, PUs (any value, changed in the banner while painting), units, rules, effects or textures at once: click them, or hold the left button and drag across them (one undo step per stroke). The *Capital* brush is clicked, not dragged: a nation has one capital, so a click moves it (a second one is ticked in the territory's panel), and the brush ends once every nation that wants a capital has one. With a terrain effect brush the map shows every territory's effects as small pictures. Textures (light line drawings that hint at open sea, coastal sea, grass, forest, hills, mountains, desert, swamp or snow, or your own pictures, *Import texture…*; *Find textures in the map…* takes the ones painted into the map's picture or relief) mark territories in the relief that *Map look → Create relief* makes; while one is chosen the map shows them all, and *Offset X/Y* moves a texture to line it up with its neighbour. |
| ↳ *Draw borders* | Draw border lines into the map picture by hand (like its lines, black or grey; width in px), freehand, as a line of clicked points (double-click or Enter ends it, Backspace takes back a point) or as a circle dragged from corner to corner (Shift: round); a line's ends snap to the borders near them, and a line that ends back at its start is a closed border of its own, eg an island. The *Eraser* takes border lines out of the picture under a band you drag (width in px), each side's color growing over the line; a click on a border erases the whole border between its two territories, shown while the mouse is on it, so that they become one. *Make territories* then turns the areas between the lines into territories, looking only where lines were drawn or erased since: territories elsewhere stay as they are, and where a line went it asks which territory keeps the joined area. A very large picture (over 50 million pixels, eg 16816 × 8085) is drawn into itself: the editor asks first, as such lines cannot be undone (save before, to have a point to return to), and a save writes only the tiles they touched. It needs the memory for the picture (4 bytes a pixel) and says how much is missing; while such a picture is held, *Make territories* over the whole picture, *Redraw borders from a picture…* and, without enough memory, *Create relief…* are refused, saying why. |
| ↳ *Split with a line* | Cut one territory in two; the new border is drawn into the picture in the same line style. |
| ↳ *Merge* | Several islands can be one territory: *Merge into this…* (in a territory's "On the map"), then click each territory that belongs to it; or *Merge into…* to make the chosen one part of another. The keeper gets the other's outlines and neighbours and keeps its own owner, units, settings and places. Land and sea stay apart. |
| ↳ *Remove territories* | In the list's ⋯ menu: tick many territories, the smallest first with their area, eg islands found by mistake, and remove them in one step. |
| ↳ *Export territory pictures* | In the list's ⋯ menu: a PNG of each territory you tick, cut from the map picture and see-through outside its border, to draw on in a paint program; `territory-pictures.txt` says where each lies on the map. |
| ↳ *Move places* | Drag where the game draws one territory's units, names, flags and markers. *Show its name on the map* off: the game draws no name there. |
| ↳ *Places* | Show units, centers, names, PUs, capitals, victory cities, convoys and the other markers on every territory, several kinds at once, and drag them into place. *Lay out unit places* for one or all territories, 4 px apart, at most 16 (or as set) per territory, numbered: *Round the center* (clockwise from 12:00) or *In rows below the center* (the first at 6:00); the last place ends the bottom row on the side with room, and the units beyond go that way (overflowToLeft); where the rows leave room unused, places between them fill it, and a territory with room for fewer than three gets up to three that stick out a little over its border. With *Units* shown, a click in the chosen territory adds a place, also where a unit does not wholly fit. *Create missing places* gives every territory without a line in a place file (names, PUs, capitals, ...) its found place, per kind or for all, and says how many are missing; the next save writes them, making the file if the map has none (Files' map folder overview links there). *Decorations* places pictures from the map's `misc` folder anywhere on the map. *Reload places from the files* reads place.txt and the marker files again, eg changed by hand or with another program meanwhile: one undo step (the map then has changes to save), and no save writes over what you changed by hand, also after an undo of the reload. |
| **Connections** | Which territories are neighbours. On a map that goes on at its other side (map.scrollWrapX, the game's default, or map.scrollWrapY) a connection across the edge is drawn the short way: dashed, from each territory to its nearer edge. *Find neighbours again…* compares them with the outlines: territories that touch without a connection, and connections between territories that do not touch, for you to tick. |
| **Canals** | Click two sea zones and the land between; choose the units that pass freely. The map shows every canal, the open one strongest. |
| **Terrain effects** | Effects like forest: what they do to each unit type, and their picture. |
| **Map look** | Unit size (smaller units: more fit into a territory) and tint, *Resize map…* (the whole map bigger or smaller, 25 to 400 %: picture, relief, borders, centers and every place as one undo step; units keep their size, so a bigger map gives them room; also in the map's picture menu), scrolling, which territories show no name, small map (*Update the small map* makes it anew with the relief; a save only makes one for a map without), relief (loaded or created, with the painted textures; a link leads to the texture brushes in Territories), mouse pointer. |

- **Redraw borders:** changed the picture in a paint program? *Picture menu ▾ → Redraw borders
  from a picture…* keeps names, owners and units; new areas become territories you name. It looks at the whole map.
- **Lost the picture's source?** *Picture menu ▾ → Save the map's picture as a file…* puts it
  together from its tiles, to change in a paint program.
- Over a picture the map shows the picture's own lines as borders.

### ⚔️ Players

- **Nations:** the list of nations in their colors; a click on one, or on its land, opens it: its name (type a new one to rename it everywhere in the game, its colors and texts; its flags and unit pictures follow with the next save; refused while a territory has the name or the game builds other names from it with foreach), color (shown live on the map), alliance, starting PUs, *Has no capital* for a nation that has none on purpose (the steps then stop asking for one), who plays it, and its own
  rules such as capitals to hold, *Stacking limits* (at most so many of a group of unit types
  per territory when placing, moving in or attacking), where it may place units (any land it owns,
  captured land, how many per territory), the units it holds at the start to place in its first
  turn, and where it may move (only into, or not into, chosen
  territories). *In the nation's color* tints its unit pictures;
  click a unit's picture to keep it in its own colors for every nation. *Flag…* makes the nation's
  flags in all the game's sizes from one picture (gif flags of the nation make way, kept as `.bak`). *Add nation…* can give the new nation the purchase and repair lists of another, as lists of its own. *Compare nations ›* lists all nations side by side: alliance, territories, income, starting PUs and other resources, units and their value (TUV, as TripleA counts it), with a sum per alliance; click a column to sort, a nation to open it.
- **Politics:** how nations start, in a diplomacy matrix like the game's (a cell per pair, colored
  war, allied or neutral; click to change, a kind the map has no type of yet makes that type), and the relationship types with what each allows.
- **Objectives:** the national objectives, nation by nation: the PUs each pays, the text the
  players read, what it checks (territories owned, units there, war, technology, rounds; the
  same fields as the trigger assistant) and how often it pays. It tells when the game option
  *National Objectives* is off, and turns it on.
- **Technology:** the technologies, who researches what at which cost, what each nation starts
  with, and what a technology gives: bonuses per unit type (eg +1 movement for fighters), rockets,
  war bonds, factories, airborne forces.

### 🛡️ Units & economy

- **Units:** cost, attack, defense, movement, hit points and the kind in one row beside the picture; every ability in groups,
  the rarer ones folded (`▸ More combat options (9)`), those of other kinds of unit in one row
  (`▸ Not for land units (50)`); *Search options…* finds an ability by its words or TripleA's name;
  *Stacking limits* (how many of the unit one territory may hold when placing, moving in or
  attacking, and whose units count); the support a unit gives others, right after Combat, written as a sentence (green for allied units, red for enemy ones). *Draw it on the map* off hides a unit
  type on the map, eg one that only marks something. *In each nation's color* off keeps a
  unit in its own colors for everyone, eg one all nations share.
- **Unit pictures:** upload one picture, or import a whole folder for a nation; pictures clearly
  bigger than the map's units can be scaled to their size. Units says which nations have no
  picture of the chosen unit.
- **Production:** what each nation can buy and repair (its purchase list, called by the nations
  that use it; *Copy from…* takes another list's rules), at which cost, the resources with
  their icons and starting amounts, and the tabs of the buy window (TripleA's own, or yours, for
  all nations or one).

### ⏱️ Game flow

- **Turn order:** each step in plain words ("Buy units"), in a band per nation that folds
  (`▸ Germans (9)`; a long round starts folded); filters for one nation or one kind of step; add,
  move, remove, and move a whole band with ▲ ▼; the number the first round has.
- **Game options:** in sections, with a lock for what players may change, a "?" for what
  TripleA's own options do, and variables.
- **Triggers:** every trigger in plain words (a message by its text, a step by what happens in
  it), grouped by nation, by what it does or by when; plus an assistant that builds one step by
  step, starting from examples like *Give PUs when a nation holds territories*.
- **Actions:** political actions (declare war) and user actions, with their texts, conditions,
  cost and chance.

### 💬 Texts & sound

- **Texts:** objectives, trigger messages, unit tool tips and action texts, with an HTML preview.
- **Game notes:** a text editor with formatting buttons and a live preview; saved in the game's `<game>.notes.html`. Pictures (*Picture…*) are copied into the map's `doc/images`, where the game finds `<img src="name.png"/>`, and show in the preview.
- **Sounds:** the game's sounds, per nation too, with your own mp3 files.

### 📤 Share

- **Publish:** the map's name and version (`map.yml`), the description and preview picture
  TripleA's list of maps shows (`description.html`, `preview.png`), a list of what is still
  missing before an upload, each with the button that fixes it, and the steps of TripleA's upload
  guide with their links. The editor does not upload itself.

### 🔧 Advanced

- **Attachments:** every attachment of the game in a table, for what no other tool covers.
- **Files:** the map folder as a tree beside a text editor that checks your files as you type;
  with no file open it explains what a map folder holds and what is missing.

### Always at hand

| | |
|---|---|
| **File** | Open, new and recent maps, another game of this map, export, Publish, the map's folder. |
| **Check (F6)** | Loads the map the way TripleA does, plays its first round in memory, and lists the problems. The highlighted button in the header is what comes next: Save, then Check, then Play. |
| **Play (F7)** | Checks that the game starts, then starts your installed TripleA with the map; else it says why not. |
| **Export** | Zips the map for sharing, without the `.bak` files. |
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
| `MapMakerWindow`, `Sidebar`                                                 | The window: the header with the File menu, the tools by name on the left, the chosen tool     |
| `MapProject`                                                                | The open map: its game and files, undo and redo, saving                                       |
| `MapXml`                                                                    | Reads and changes the game XML model; each change makes a new model                           |
| `RulesXml`, `ProductionXml`, `TechXml`, `GameRulesXml`                      | The same for turn order and politics, production, technology, options, dice and variables     |
| `ActionsXml`, `ObjectiveXml`                                                | The same for political and user actions, and for national objectives                          |
| `UnitsXml`, `UnitOptions`                                                   | The same for a unit type's options and the support it gives                                   |
| `CanalXml`, `TerritoryEffectsXml`, `TerritoryXml`                          | The same for canals, territory effects and a territory's other options                        |
| `MapFiles`, `MapFolder`                                                     | The files beside the game XML: outlines, centers, pictures, `map.properties`                  |
| `MapPicture`                                                                | Finds territories, sea zones and neighbours in a picture                                      |
| `MapCanvas`, `MapView`                                                      | The map on screen                                                                             |
| `PlayWith`                                                                  | Which TripleA Play starts                                                                     |
| `HomeEditor`, `TerritoriesEditor`, `NationsEditor`, `UnitsEditor`, ...      | The tools                                                                                     |
| `NationsBalance`                                                            | Compare nations: what each starts with, and the sums per alliance                             |

## License

GPL-3.0, like TripleA, whose code this editor builds on. See [LICENSE](LICENSE).
