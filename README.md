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

- **Start a map:** open a map folder or a recent map, or let a wizard build a new map: its name and
  folder, a picture (or Skip, to add one later) and who plays. The editor writes every file a map needs: `map.yml`, the game XML, `polygons.txt`,
  `centers.txt`, `place.txt`, `map.properties`, tiles, flags and unit pictures.
- **Territories:** find them in the picture (areas between dark lines; blue areas are sea zones).
  Rename them one at a time or many at once. Set owners, PUs, capitals, victory cities and starting
  units, or paint them on the map with *Paint* (owner, PUs, units, rules, effects). *Move places* shows where the game draws a
  territory's units, name, PUs, capital flag, victory city, a sea zone's convoy, blockade, kamikaze
  and comment markers and its territory effects: drag them, add or remove places for units
  (right-click or Delete). *Decorations* puts the picture chosen in the banner, from the map's
  `misc` folder, anywhere on the map (decorations.txt).
  Impassable, original owner, unit production, territory effects, blockade, kamikaze and convoy
  zones are set there too; impassable and effects also with *Paint*. *Remove territory* takes a
  territory away with its owner, units, connections, canals, outline and places; a territory the
  rules still name stays until they are changed in Attachments. *Split with a line* cuts the
  chosen territory along a line dragged across it: the larger part keeps it with all it has, the
  smaller, shown hatched, becomes a new territory you name, with its owner and the neighbours it touches.
  For a picture chosen in the editor, the new border is drawn into it, as wide as you choose (at
  first as wide as most of the picture's lines, in their color); for a map whose picture is only
  in tiles, draw the new border into the picture yourself. Over a picture the map shows the picture's own lines
  as the borders; found outlines lie on them.
- **Canals and territory effects:** *Canals* makes a canal by clicking its two sea zones and its
  land on the map, with the units that pass freely. *Terrain effects* adds, renames and removes effects
  like forest, with what they do to each unit type in battle and on the move, and their picture.
- **Redraw borders:** draw new borders into the map's picture in any paint program, then choose
  the map picture menu (the picture button above the map, ▾) → *Redraw borders from a picture…*.
  Names, owners and units stay;
  territories whose border moved get their places found again. New areas become territories you
  name; where a border was rubbed out, you choose which territory keeps the area, and the others
  are removed (one still named in triggers keeps its old border).
- **Units:** cost, attack, defense, movement and hit points in one row, land, sea or air, the
  picture, and every ability in groups (combat, movement, transport and carrier, sea, air and
  anti-air, production, bombing, special): each shows its common options and those set, "Show
  all" the rest. The support a unit gives other units (like artillery) reads as a sentence, and
  any other unitAttachment option is added by its name.
- **Connections, nations, units, game options, turn order and game notes,** each in its own tool.
  The turn order shows each step in plain words ("Buy units"), in bands per nation; steps are
  added from TripleA's kinds (eg politics, user actions), moved, removed and changed (what
  happens, nation, every round or only the first rounds, step properties).
  Game options: in sections (dice, victory, bids, luck, politics and triggers, more options), a
  box, number or text each, a lock for whether players may change it, the bounds of a number
  they may change; a search; options are added from TripleA's names; variables (lists of names
  that attachments are made for each of) in a section below them.
- **Nations:** besides color, alliance and PUs, who plays a nation by default, whether it is
  optional or hidden, and its own rules (capitals it must hold, PUs lost with its capital,
  blockades, handing over units, sharing technology; any other playerAttachment rule by name,
  changed or removed in place, a new one, eg a unit limit, built from its parts by *Add rule*).
  The map beside it shows the nations' colors, a new color already while it is chosen; *In the
  nation's color* (or *All nations*) has the game tint the unit pictures in the nation's color.
- **Actions:** political actions (eg declare war) and user actions (fire a trigger), listed by
  their button: their texts in `politicstext.properties` / `actionstext.properties`, the
  conditions they need (in plain words), what they
  change, cost, chance, attempts per turn and who must accept them.
- **Politics, technology and production:** relationship types (war, allied, neutral, and what
  each allows, eg moving through or flying over the other's land) and how each two nations start; technologies (TripleA's standard ones for a game without its own list), the research chart
  each nation researches them in and the built-in ones it starts with; frontiers, what they sell, what it costs and which nation buys from which,
  the same for repairs; resources (added, renamed, removed, with icons) and what each nation has
  of them at the start.
- **Texts:** the objectives tab, the messages triggers show, unit tool tips
  and the texts of actions (`objectives.properties`, `notifications.properties`,
  `tooltips.properties`, `politicstext.properties`, `actionstext.properties`): each key explained,
  new keys offered from the game's conditions, triggers, units and actions; HTML texts with a
  preview. Objective texts copied from another game are shown as such and made this game's with
  one click.
- **Triggers:** every trigger in plain words, filtered by nation, to duplicate or remove (refused
  while another still names it), and an assistant that makes or changes one step by
  step, a page per step (a new one can start from an example, eg *Give PUs when a nation holds
  territories*; an existing one opens on Review): whose it is, when it fires, its conditions (new ones or the map's), what happens (units,
  PUs as one effect, owners, relationships as one table, production, technology, messages,
  victory, ...) and how often. Its
  problems show before it is written; it turns on *Use Triggers* if the game has it off.
- **Attachments:** every attachment of the game, eg triggers and conditions, filtered by kind in
  plain words, with its options in a table; the option names TripleA knows are offered. A trigger
  opens in the trigger assistant with *Open in assistant*.
- **Map look:** unit size and a nation's unit tint, scrolling, what the map shows, the small map,
  relief blending, the title of an exported map picture and the mouse pointer (map.properties), and
  a relief picture cut into relief tiles: loaded, or created from the map with *Create relief…* (dark coasts
  with waves, land raised at its borders), with a preview.
- **Unit pictures:** upload a picture for a unit type, or import a whole folder of pictures for one
  nation, for example another map's `units/Germans`.
- **The map as the game shows it:** owners, values, units and connections views, with a legend that also counts the territories changed since the last save and the problems
  outlined red (a click lists them).
  Territories with a problem have a red outline.
- **Sounds:** the game's sounds, also per nation and for trigger messages, with your mp3 files or
  folders (copied into the map's `sounds/`) or the game's own; play them to try (sounds.properties).
- **Files:** the map's folder as a tree beside a text editor that checks your files as you type
  (search, wrap lines). With no file open it explains what a map folder holds, what is missing and
  which tool makes each part. Add other maps' folders (⋯) to copy from them. Games… renames the
  map's games (map.yml); a picture folder (PUs, dice, misc, territoryNames, territoryEffects)
  shows which pictures the game looks for, to import them.
- **Check (F6)** loads the game the way TripleA does, **Play (F7)** starts TripleA with it, and
  **Export** zips the map for sharing.
- **Undo and redo** for every change. A save writes only what changed, and the first version of
  each file is kept as `.bak`.

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
