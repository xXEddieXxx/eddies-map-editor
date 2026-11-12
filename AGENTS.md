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
  colors and styles. The editor runs in English (`Locale.ENGLISH`), whatever the system's language.
- Stop a running editor before a build: it runs from the jar the build writes.

## Code map

All code is in `src/main/java/eddies/mapeditor`, the tests in `src/test/java/eddies/mapeditor`.

| Class                         | Role                                                                                            |
|-------------------------------|-------------------------------------------------------------------------------------------------|
| `EddiesMapEditor`             | `main`: finds the installed TripleA and starts again with its jar, then `MapMakerWindow.start`  |
| `MapMakerWindow`, `Sidebar`   | The window: header (the map's name as its menu, save state, problems pill, Undo/Redo, Check, Save only with changes, else Play primary), `Sidebar` (tools by name in groups, Advanced folded, icons only below 1280 px unless pinned), the chosen tool's screen under its title, "?" and intro line; a tool on the map gets the one `MapView` left of its panel. News as toasts; Alt+1 Home, Alt+2..9 the next tools |
| `StartPage`, `NewMapWizard`, `RecentMaps` | The start page: Open, New map, the recent maps (`RecentMaps`, Java preferences); the wizard: name and folder, picture (or Skip), nations |
| `Editor`                      | One tool: `title`, `hint` (its "?"), `glyph`, `page`, `usesMap`, `showing`, `commit` |
| `MapGuide`                    | Next steps to a playable map, problems, the map folder's anatomy (shown in Files)               |
| `HomeEditor`                  | Home, the first tool; the pill and a problem count on the map open its problems: the next step with the one main action (`MapGuide.nextStep`), the steps ✓/○/! (! only when a step is due, eg capitals once nations own land), the problems with Show (territory, else the problem's tool) |
| `TerritoriesEditor`, `ConnectionsEditor`, `NationsEditor`, `UnitsEditor`, `RulesEditors`, `NotesEditor`, `MapLookEditor`, `SoundsEditor`, `FilesEditor` | The tools; `NationsEditor`: beside the map (a click on land chooses its owner, the chosen nation's land marked, the color chooser previews on the map via `MapView.previewColor`), units tinted in the nation's color (`MapProject.tintUnits`, map.properties `units.transform.color.`, which follows `setNationColor` while it is the old color; `unitPicture` shows the tint as the game does); `NotesEditor`: insert bar (bold, italic, heading, list, line break, link), Text/Split/Preview, a template for blank notes; `MapLookEditor`: map.properties settings (units, small map, blending, map picture title, pointer) and the relief; `RulesEditors`: game options in sections (`group` by name), a lock per option, variables collapsed below; `SoundsEditor`: sounds.properties (a `MapTexts` kind), mp3s copied into `sounds/`; `FilesEditor`: tree and file side by side, with no file open the map folder's anatomy (`MapGuide.anatomy`); also map.yml's games to rename and the picture folders (PUs, dice, misc, territoryNames, territoryEffects) with what is missing, to import |
| `MapProject`                  | The open map: game + files per undo step, save, problems; `removeTerritory` (refused while `RulesXml.whereNamed` finds it), `freeName`; `MapXml.withTerritory`/`withoutTerritory` and `MapFiles.withOutlines`/`withoutTerritory` are the shared core for adding and removing territories |
| `MapXml`                      | Reads and changes the XML model; every change returns a new `Game`                              |
| `GameXmlKeeper`               | Writes the game XML, keeping from the file it replaces what TripleA's model drops: DOCTYPE, comments, unknown elements and attributes |
| `TurnOrderEditor`, `PoliticsEditor`, `TechnologyEditor`, `ProductionEditor` | Turn order steps; relationship types (their kind and what they allow) and how nations start; technologies (without a list in the game, TripleA's standard ones, written with the first change as one step); Buy and Repair (frontiers and rules), Resources (icons, amounts at the start). Turn order: steps in bands per nation, in plain words (`GameRulesXml.kindOf`); a step added or changed to a kind gets the game's delegate of that class and a name with the ending TripleA reads (`withDelegateStep`, `withStepKind`; a renaming refused while `whereNamed` finds the old name) |
| `ActionsEditor`, `ActionsXml` | Political and user actions, listed by their button text: texts (via `MapTexts`; an emptied message is written as `NONE`, which TripleA skips), conditions and triggers declared before them, relationship changes, cost, chance, who accepts; a new one comes last with the prefix TripleA needs, it and its button text one step |
| `RulesXml`, `ProductionXml`, `TechXml`, `GameRulesXml` | Like `MapXml` for turn order, politics, a nation's player list flags and playerAttachment rules (Nations), production (repair through `ProductionXml.repairView`, resources), technology, game options (typed; a `<value>` child stays one), dice, variables, delegates; `RulesXml.whereNamed` keeps what triggers name (a nation they name is not removed) |
| `MapFiles`                    | The files beside the XML as one value: outlines, centers, properties, pictures, places         |
| `OutlineCut`                  | Cuts a territory's outlines along a line drawn on the map (Territories' *Split with a line*, `MapProject.splitTerritory`), and whether outlines touch |
| `TextsEditor`, `MapTexts`     | Objectives, trigger messages, unit tool tips, action texts and sounds (objectives/notifications/tooltips/politicstext/actionstext/sounds.properties): kept as text, changed by key in place; sounds are edited in `SoundsEditor`. A tab per file, keys in plain words (`MapTexts.title`), an objective group's conditions as a list in words (its raw text behind "Edit the list as text"); objective keys all of another game's name (`foreignPrefix`) are a problem until "Use them for this game" renames them (`withKeyPrefix`, one step) |
| `UnitsXml`, `UnitOptions`    | A unit type's options (grouped in `UnitOptions` with plain labels and TripleA's names and defaults; `ESSENTIALS` in the top row, the kind (land, sea, air) as isSea/isAir, `COMMON` shown in a section for every unit it is for, `BLANK_WORDS` for -1 defaults shown empty, `EXTRA_HINTS` for Add option; the rest under "More options") and its support attachments; unknown options, counts and order stay |
| `AttachmentsEditor`, `AttachmentsXml` | Every attachment (triggers, conditions, ...) with its options; kinds in plain words (`AttachmentsXml.KIND_NAMES`), rows indexed once per change (35,000 on big maps); known option names read from TripleA's classes; `show(name)` chooses one |
| `TriggersEditor`, `TriggerAssistant`, `TriggerXml` | Triggers in plain words (listed in a `SwingWorker` while the tool shows, as maps have up to 35,000; of two with one name and nation the last counts, as in TripleA), filtered by nation, duplicated (sharing the conditions) or removed (refused while `RulesXml.whereNamed` finds it); the assistant (a page per `TriggerAssistant.Step`: Start with the `Pattern` examples for a new trigger, If, Then, When and how often, Review; a rail with each step's summary, the footer's first problem leads to its step; for a step of a new trigger not reached yet it says what to do there, eg "Next: choose the territories"; its problems are checked over the whole game, so typing checks after a 150 ms pause, Save at once) writes a trigger, its new conditions (right before it, as the game finds names only above) and its message text as one step; it shows `resource` and `resourceCount` as one "Give PUs" effect (`TriggerXml.GIVE_RESOURCE`, the amount written only when set) and all relationship changes as one table; `TriggerXml.clauses` and `TriggerAssistant.clauseCard` say what a trigger does in parts. `TriggerXml.SPECS`: each option's parts and which go to `count` (TripleA reads `count:value`); an unchanged option keeps its form, unknown options and condition names (eg made by foreach) are kept |
| `CanalsEditor`, `CanalXml`, `TerritoryEffectsEditor`, `TerritoryEffectsXml`, `TerritoryXml` | Canals (clicked on the map, shown with `MapView.mark`; a canalAttachment on each sea zone), territory effects (the Terrain effects tool: list, attack, defence and moves to enter per unit type, who may enter, picture in `territoryEffects/`, the territories using it with *Show on map* into Territories' paint brush; rename and remove refused where `RulesXml.whereNamed` still finds them), the territory attachment's other options (impassable, unit production, original owner, effects, sea zone kinds, repeated ones as lists) |
| `MapFolder`                   | Disk: map.yml (games renamed keeping unknown keys), new map folders, tiles, flags, unit pictures, picture folders and imports, zip, `.bak` handling, Play |
| `PlayWith`                    | Which TripleA Play starts: another installed one (found or chosen, kept in Java preferences) or the one the editor runs with |
| `MapPicture`                  | Finds territories (areas between dark lines, blue = sea) and neighbours in a picture; outlines reach into the line (`grow`), so neighbours meet on it; `lineStyle`: the picture's usual line width and color, for the border a split draws into the picture (`MapProject.BorderLine`) and for Territories' *Draw borders* (`MapProject.drawBorder`, then *Find territories* as Redraw borders); a picture only in baseTiles is first held (`MapProject.holdPicture`, `MapFiles.tilesPicture`, in every undo state), and a save of a picture with lines drawn into the saved one (same lineage, `drawnOn`) writes only the changed tiles (`MapFolder.writeChangedTiles`, each with .bak); a new picture is written whole |
| `RedrawDialog`                | Redraw borders: which new areas become territories (name, land or sea) and which territory keeps a merged area (a refused name turns the verb off); `howTo` before the picture is chosen (once a session, "Don't show again" in Java preferences); `MapFiles.withBordersFrom` finds them, `MapProject.takeRedrawn` takes all as one step |
| `ReliefMaker`                 | A relief made from the outlines: coast lines and shadows, waves, raised land, texture          |
| `MapCanvas`, `MapView`, `MapLegend`, `MapMode`, `MapViewsButton` | The map on screen: one toolbar row (the tool's controls left, views, zoom, Fit, picture menu right), a tool's mode banner, the next step instead of the map while it has no outlines; the legend: nations as many as fit, changed territories, problems (a click lists them) |
| `MapChecks`, `FileLint`       | Problems of the map, each with its territory or the tool that fixes it (`Problem.tool`, eg Connections); checks of files as they are typed |
| `XmlTextArea`                 | Text in Files and Notes: XML and HTML in colors, Enter keeps the indentation                   |
| `Dialogs`, `Toast`          | Every dialog of the editor (only `EddiesMapEditor`, which runs before TripleA is loaded, keeps a plain `JOptionPane`): text, [Cancel] [verb] at the right (Enter the verb, Esc Cancel), a danger verb for removes, names checked as typed (`FieldError`), pick one or many (a search from 12), warn with "Close", save / don't save; `Toast`: news that needs no answer at the window's bottom, 4 s (8 s with an action), never focused |
| `Form`, `FieldError`, `Panels`, `Details`, `Help`, `MakerIcon`, `SearchList`, `LineNumbers`, `OnTyping`, `MakerResources`, `Theme` | Shared UI pieces; `Theme`: colors (`Editor.*` and FlatLaf's, in `resources/eddies/mapeditor/theme/<FlatLaf theme>.properties`, registered by `Theme.install` before TripleA sets up the look and feel; a getter has a fallback for other looks), button kinds and labels, `htmlWidth` for an HTML text's `width:` (Swing draws a CSS px 1.3 screen px wide); `Panels`: list and inspector splits, `empty` (glyph, title, a line, and the tool's main action when there is nothing yet); `Help`: "?" bubbles and a tool's intro line (hidden per tool in Java preferences); `SearchList`: the list column (count, search, rows of a title and a line below, cut in the middle, a shared key start shown once); `Form`: an inspector's fields, numbers inline, sections side by side from 820 px (`columns`), one "More options" (`more`, open per id for the session); `Details`: an item's header and table cells to choose from; `LineNumbers`: a text's line numbers, one per line also when wrapped (Files, Notes); `OnTyping`: runs an action on every change of a text, at once on the Swing thread (not TripleA's `DocumentListenerBuilder`, which runs it 100 ms later on a timer thread, so typed text could miss a commit) |
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
  and redo, and the saved state; the oldest undo steps are forgotten once their pictures (eg one
  per line drawn into the picture) take over 600 MB. Changes go through `MapProject.edit` or one
  of its methods, never by mutating a model. `onChange` listeners refresh the tools. Not steps:
  resource icons, picture imports, new picture folders, copied sounds and map.yml changes (new game, rename) are written to
  disk at once, outside undo, with `.bak`; a rewritten map.yml loses its YAML comments (kept in the
  `.bak`).
- **A save writes what changed.** Text files go through `MapFolder.replace`: a failed save loses
  nothing, and the first version of a file stays as `.bak`. `MapFolder.readText`/`replaceText` take
  a file as the game reads it: .properties as ISO-8859-1 (other characters as `\uXXXX`), the rest UTF-8.
- **Places are part of the model.** `MapFiles.Places` holds place.txt, the marker files
  (`MapFiles.Marker`: name_place.txt, pu_place.txt, capitols.txt, vc.txt, blockade.txt, convoy.txt,
  comments.txt, kamikaze_place.txt, territory_effects.txt) and decorations.txt as read when the
  map opened; Territories' *Move places* (in a territory's "On the map") and its *Decorations*
  mode (with a picture box in the banner) change them on the map. A marker shows where the game draws it
  (`Marker.isOn`) or, if a save keeps it there (`Marker.keepsUnused`), where the file has it. A
  territory without places gets found ones (`unitPlaces`, `markerPlaces`); a redrawn border that
  changed noticeably finds them again. A save writes a place changed here since the last save,
  else keeps the file's (maybe set by hand meanwhile), keeps lines of territories the editor does
  not know (but not of one removed since the last save), and writes a file only when it differs
  (`writeKeeping`).
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
