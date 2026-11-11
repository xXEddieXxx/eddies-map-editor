# Eddie's Map Editor

A standalone map editor for TripleA: Java 25, Swing with FlatLaf, one Gradle project. It edits a
TripleA map (its game XML and the files beside it) on one screen, so a map maker never has to
touch the files by hand.

## Build and run

| Command                      | What it does                                                    |
|------------------------------|-----------------------------------------------------------------|
| `./gradlew run`              | Starts the editor (`EddiesMapEditor`)                           |
| `./gradlew test`             | Runs the tests                                                  |
| `./gradlew distZip`          | The zip for every system (needs Java 25), in `build/distributions/` |
| `./gradlew appZip`           | The program with its own Java (jpackage) for this system, eg `eddies-map-editor-windows.zip`; `-PappVersion=1.2.0` |

- **Workflows**: `build.yml` tests and packages every push; `release.yml` (started by hand with a
  version) builds on Windows and publishes the release, its Windows zip under a fixed name for the
  README's download link, and the TripleA release it was built with. Both take TripleA's jar from
  the `game-headed.zip` of a TripleA release.

- **JDK 25** in `JAVA_HOME`; Gradle uses a Java 25 toolchain.
- **TripleA is not part of the editor: it runs with the player's installed TripleA.** Its one jar
  (`bin/game-headed-*.jar`, with FlatLaf, logback and the rest) is `compileOnly` and on the test
  and `run` class path; the build takes it from `tripleaHome` (default: `%ProgramFiles%/TripleA`,
  on macOS `/Applications/TripleA.app/Contents/app`; `-PtripleaHome=<folder>`, eg an unzipped
  `game-headed.zip` of a release). At start, `EddiesMapEditor` (JDK classes only) finds the
  installed TripleA (`PlayWith`: usual folders, then Windows' uninstall list), or asks for it, and
  starts the editor again with that jar on the class path. Keep it so: the editor uses only what a
  TripleA release has. Where TripleA lacks something, the editor brings it: `Builders` gives the
  map-data model `toBuilder()` (`@ExtensionMethod`; a TripleA model with its own wins),
  `MapChecks.findError` asks TripleA's parser why a game does not load, `Theme` holds the editor's
  colors and styles.
- Stop a running editor before a build: it runs from the jar the build writes.

## Code map

All code is in `src/main/java/eddies/mapeditor`, the tests in `src/test/java/eddies/mapeditor`.

| Class                         | Role                                                                                            |
|-------------------------------|-------------------------------------------------------------------------------------------------|
| `EddiesMapEditor`             | `main`: finds the installed TripleA and starts again with its jar, then `MapMakerWindow.start`  |
| `MapMakerWindow`              | The window: tool strip, file browser, middle (map, preview or file), the tool's panel, header   |
| `StartPage`, `NewMapWizard`   | The start page's three choices; a new map from a picture                                        |
| `Editor`                      | One tool: `title`, `hint` (its "?"), `glyph`, `page`, `preview`, `usesMap`, `showing`, `commit` |
| `OverviewEditor`, `MapGuide`  | Next steps to a playable map, problems, the map folder's anatomy                                |
| `TerritoriesEditor`, `ConnectionsEditor`, `NationsEditor`, `UnitsEditor`, `RulesEditors`, `NotesEditor`, `MapLookEditor`, `SoundsEditor`, `FilesEditor` | The tools; `MapLookEditor`: map.properties settings (units, small map, blending, map picture title, pointer) and the relief; `SoundsEditor`: sounds.properties (a `MapTexts` kind), mp3s copied into `sounds/`; `FilesEditor`: also map.yml's games to rename and the picture folders (PUs, dice, misc, territoryNames, territoryEffects) with what is missing, to import |
| `MapProject`                  | The open map: game + files per undo step, save, problems                                        |
| `MapXml`                      | Reads and changes the XML model; every change returns a new `Game`                              |
| `GameXmlKeeper`               | Writes the game XML, keeping from the file it replaces what TripleA's model drops: DOCTYPE, comments, unknown elements and attributes |
| `TurnOrderEditor`, `PoliticsEditor`, `TechnologyEditor`, `ProductionEditor` | Turn order steps; relationship types (their kind and what they allow) and how nations start; technologies; Buy and Repair (frontiers and rules), Resources (icons, amounts at the start). Turn order adds new kinds of step from TripleA's delegates |
| `ActionsEditor`, `ActionsXml` | Political and user actions: texts (via `MapTexts`), conditions and triggers declared before them, relationship changes, cost, chance, who accepts; a new one comes last with the prefix TripleA needs, it and its button text one step |
| `RulesXml`, `ProductionXml`, `TechXml`, `GameRulesXml` | Like `MapXml` for turn order, politics, a nation's player list flags and playerAttachment rules (Nations), production (repair through `ProductionXml.repairView`, resources), technology, game options (typed; a `<value>` child stays one), dice, variables, delegates; `RulesXml.whereNamed` keeps what triggers name (a nation they name is not removed) |
| `MapFiles`                    | The files beside the XML as one value: outlines, centers, properties, pictures, places         |
| `TextsEditor`, `MapTexts`     | Objectives, trigger messages, unit tool tips, action texts and sounds (objectives/notifications/tooltips/politicstext/actionstext/sounds.properties): kept as text, changed by key in place; sounds are edited in `SoundsEditor` |
| `UnitsXml`, `UnitOptions`    | A unit type's options (grouped in `UnitOptions` with plain labels and TripleA's names and defaults; the rest under "More options") and its support attachments; unknown options, counts and order stay |
| `AttachmentsEditor`, `AttachmentsXml` | Every attachment (triggers, conditions, ...) with its options; known option names read from TripleA's classes |
| `TriggersEditor`, `TriggerAssistant`, `TriggerXml` | Triggers in plain words (listed in a `SwingWorker` while the tool shows, as maps have up to 35,000); the assistant (who, when, conditions, effects, how often) writes a trigger, its new conditions (right before it, as the game finds names only above) and its message text as one step. `TriggerXml.SPECS`: each option's parts and which go to `count` (TripleA reads `count:value`); an unchanged option keeps its form, unknown options and condition names (eg made by foreach) are kept |
| `CanalsEditor`, `CanalXml`, `TerritoryEffectsEditor`, `TerritoryEffectsXml`, `TerritoryXml` | Canals (clicked on the map, shown with `MapView.mark`; a canalAttachment on each sea zone), territory effects (list, combat and movement per unit type, picture in `territoryEffects/`; rename and remove refused where `RulesXml.whereNamed` still finds them), the territory attachment's other options (impassable, unit production, original owner, effects, sea zone kinds, repeated ones as lists) |
| `MapFolder`                   | Disk: map.yml (games renamed keeping unknown keys), new map folders, tiles, flags, unit pictures, picture folders and imports, zip, `.bak` handling, Play |
| `PlayWith`                    | Which TripleA Play starts: another installed one (found or chosen, kept in Java preferences) or the one the editor runs with |
| `MapPicture`                  | Finds territories (areas between dark lines, blue = sea) and neighbours in a picture            |
| `ReliefMaker`                 | A relief made from the outlines: coast lines and shadows, waves, raised land, texture          |
| `MapCanvas`, `MapView`, `MapLegend`, `MapMode`, `MapViewsButton` | The map on screen, its views and legend                      |
| `MapChecks`, `FileLint`       | Problems of the map; checks of files as they are typed                                         |
| `XmlTextArea`                 | Text in Files and Notes: XML and HTML in colors, Enter keeps the indentation                   |
| `Form`, `Panels`, `Help`, `MakerIcon`, `SearchList`, `MakerResources`, `Theme` | Shared UI pieces; `Theme`: colors and styles     |
| `Builders`                    | `toBuilder()` for the XML model, for TripleA versions without it                                |

## How it works

- **The XML model, not `GameData`.** The editor reads the game XML into
  `org.triplea.map.data.elements.Game` (`XmlMapper`), changes it with `MapXml`, and writes it with
  `GameXmlKeeper`: TripleA's JAXB export of the model, plus what the file on disk has that the model
  drops (the difference between the file and the file read and exported by the model). Elements
  are written in the model's order (the editor may move them, eg turn order steps); comments and
  dropped elements go before the element they were before, a removed element's comments where it
  was. A removed and a new element whose keys differ in one attribute count as renamed. `GameData` is only used to ask TripleA itself: by Check
  (`MapChecks.findError`), to load the game as TripleA does, and by `TechXml.withStandardTechs`, for
  the standard technologies of a game without its own list.
- **Every change is a step.** `MapProject` keeps a `State(game, files, texts)` per step for undo
  and redo, and the saved state. Changes go through `MapProject.edit` or one of its methods, never by
  mutating a model. `onChange` listeners refresh the tools. Not steps: resource icons, picture
  imports, new picture folders, copied sounds and map.yml changes (new game, rename) are written to
  disk at once, outside undo, with `.bak`; a rewritten map.yml loses its YAML comments (kept in the
  `.bak`).
- **A save writes what changed.** Text files go through `MapFolder.replace`: a failed save loses
  nothing, and the first version of a file stays as `.bak`. `MapFolder.readText`/`replaceText` take
  a file as the game reads it: .properties as ISO-8859-1 (other characters as `\uXXXX`), the rest UTF-8.
- **Places are part of the model.** `MapFiles.Places` holds place.txt, the marker files
  (`MapFiles.Marker`: name_place.txt, pu_place.txt, capitols.txt, vc.txt, blockade.txt, convoy.txt,
  comments.txt, kamikaze_place.txt, territory_effects.txt) and decorations.txt as read when the
  map opened; Territories' *Move places* brush and its *Place decorations* brush (with a
  picture box beside it) change them on the map. A marker shows where the game draws it
  (`Marker.isOn`) or, if a save keeps it there (`Marker.keepsUnused`), where the file has it. A
  territory without places gets found ones (`unitPlaces`, `markerPlaces`); a redrawn border that
  changed noticeably finds them again. A save writes a place changed here since the last save,
  else keeps the file's (maybe set by hand meanwhile), keeps lines of territories the editor does
  not know, and writes a file only when it differs (`writeKeeping`).
- **Map folders**: `map.yml` at the top, the content (polygons.txt, baseTiles, ...) there or in a
  folder below it like `map/`, games in `games/` (`MapFolder.rootOf`, `gamesOf`).
- **Background work** (finding territories, redrawing borders, Check) runs in a `SwingWorker` and
  is applied only if the map did not change meanwhile; `MapFiles` and `Game` are compared by
  identity, as each step makes new ones.
- **The canvas is fast on big maps** (700+ territories): it caches 512 px squares per zoom and
  view, works out each territory's look once per change, and draws a smaller copy of the picture
  at zoom 0.6 or less. Keep per-paint work small.

## Rules

- **Map XML is community content.** A map the editor saves must still load in TripleA: Check (F6)
  proves it. Never drop elements or attributes the editor does not understand.
- **No data loss.** Never write over a map file without the `.bak` of its first version; never
  drop typed text without asking; when a file in Files does not read, save nothing from the other
  tools.
- **UI**: simple and calm. Icons with tool tips instead of long labels; explanations behind a
  small "?" (`Help`, a hint bubble that stays open); the map only beside the tools that work on it
  (`Editor.usesMap`), the other tools take the whole width. Short, plain texts.
- **Code**: Google Java Style. Lombok for boilerplate. `Optional` instead of `null`, `@Nullable`
  where unavoidable. No `boolean` parameters on package APIs (overloads or two methods instead).
  Immutable values, `List.of()`/`Map.of()`. Step-down order: a method below its first caller.
  Comments say why, not what.
- **Tests**: JUnit 5 and AssertJ with `.as("why")`, `@DisplayName` on every test, given / when /
  then with setup in `given...()` helpers. A behaviour change comes with a test that fails without
  it. Game data: TripleA's `minimap.xml` and, for a big game, `ww2global40.xml`
  (`GameRulesXmlTest.givenG40()`), copied into `src/test/resources`; pictures from
  `MapPictureTest.givenPicture()`.
- **Swing checks without a screen**: render a window offscreen in a temporary test (build it by
  reflection, lay it out, `paint` into a `BufferedImage`, look at the PNG), then delete the test.

## Goal

Everything TripleA's map format can do should be doable in the editor without opening a file.
Triggers and conditions have a guided assistant (Triggers); options it does not know (eg
foreach, chance increments) stay as they are and are edited as plain attachments (Attachments).
