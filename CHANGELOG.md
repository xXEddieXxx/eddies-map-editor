# Changelog

What changed for map makers, release by release. The GitHub release of each version carries the
downloads; this file carries the words.

## 0.2.9

### Changed

- **Make territories lets you choose each change** (#69). On a map with badly drawn borders, eg
  1911 Theatre of Empires, a gap in an old line used to merge territories you never meant to
  merge. The dialog now lists every change as a row to take or keep as it was: a split, a new
  border, a merge, a new territory. What a line you drew made starts ticked; territories merge
  only where you erased their border; anything else, eg a gap in an old line or an island the
  search missed, starts unticked and says why ("Alsace would take part of Champagne through a gap
  in the line"). Changes that move land between them are one row, so no choice leaves a hole or
  an overlap, and territories you did not change keep their outlines exactly.
- **Faster save of big games** (#68). Saving a big game file takes about half the time, eg 0.55
  instead of 1.3 s on 270BC Wars; the saved file is the same as before.

### Fixed

- **Pictures in the preview of messages and other texts** (#72). Texts' preview now finds
  `<img src="prologue.png"/>` in the map's doc/images, as the game does, eg 11 Rings of Power's
  prologues.
- **Objectives that a trigger pays** (#73). Objectives also lists the triggers and conditions
  the objectives file gives a text, eg Path to Victory's Lend-Lease, as the game's Objectives tab
  does, with the way to Triggers and to their text. Removing a trigger or an attachment also
  removes its objectives text and group entry, which the game otherwise reports as an error.
- **Small islands found again** (#70). Redraw borders, Make territories and drawing borders
  found no island too small for the map's size, eg Cyprus, Malta and about 70 more territories
  of 1911 Theatre of Empires, and offered to merge it into its sea zone. An area with the center
  of one of the map's territories is now kept, however small; islets a sea zone holds stay part
  of it.
- **Disabled fields in the dark theme** (#71). A disabled text field, list box or label, eg in
  a change left unticked in Make territories, now looks clearly dimmer than an enabled one, as in
  the light theme.

## 0.2.8 · 2026-11-23

From a map maker's feedback on 0.2.6 and 0.2.7, tried with 11 Rings of Power, 1941 Global
Command Decision, Greyhawk Wars and Total World War; each change under its GitHub issue.

### Fixed

- **The game's notes were saved twice** (#56), in the game file and in its notes file, and only
  the notes file is what the game shows. A save now keeps them in the notes file alone; notes an
  old map had only in its game file move there with the next save, and *New game from this one…*
  copies them.
- **Compare nations counted income TripleA does not pay** (#60). Income is now TripleA's
  Production at the start: nothing from contested land, sea zones another nation owned first or
  cut convoy routes, times *Multiply PUs*. On 1941 Global Command Decision the USSR showed 75 PUs
  too many. Checked against TripleA on every downloaded map.
- **PUs written as productionOnly read as 0** (House of Habsburg, Invasion USA). Territories and
  Compare nations read them now, and a change goes into that option.
- **Technology did not show technologies a trigger gives** (#62), as 1941 Global Command Decision
  and Total World War give most of theirs. A nation's row now names the triggers and when they
  fire, and a click opens one in Triggers. *PUs per roll* shows only with the game option *Tech Development* on; without it nations
  never roll, which a line says with a link to Game options.
- **A unit's tool tip previewed other than in the game** (#58): other spacing, no pictures. The
  preview in Texts now looks like the game's tool tip, the unit and its owner in bold first,
  pictures from `doc/images`, and a "?" says which HTML a tool tip takes.
- **A row's tool tip in Texts said one thing three times** (#59). It leaves out the key now when
  the title or line tells it.
- **Resize map lost its bar halfway** (#51): the bar now shows until the map is resized. Find
  textures shows the bar while it looks, and a save on a big map the wait cursor.
- **Flag… and a unit's picture opened in the home folder** (#57). They start in the map's
  `flags/` or `units/` folder.

### New

- **PUs from units and Upkeep** (#61) in Compare nations, where a map's units make or cost PUs
  each turn (`createsResourcesList`, eg factories, oil fields, upkeep), as TripleA pays them.
- **Action texts a row per action** (#63). Texts' political and user actions list one folded row
  per action, eg 320 for 11 Rings of Power's 2240 texts, and count actions.
- **Long texts with a line at each `<br>` and `<p>`** (#64) to read and change in Texts; they are
  written back as one line, as the files keep them.
- **TripleA's stats as a unit's tool tip** (#65): hovering a unit in Units shows the tool tip the
  game shows on the map, and a line leads to its text in Texts.
- **What an objective pays** (#66) beside its text in Texts, with a link to it in Objectives.
- **Suggested colors** (#67) in a nation's color chooser: colors apart from the other nations'
  and greys for minor nations, taken at a click.
- **Units at the start, off and on** (#54): the View menu switches the Game view's starting units
  off, eg to see the picture under them. Territories' Places now says **Unit places** for
  place.txt, and *Reload all places from the files* what it reads.
- **Release notes from the changelog** (#29): each release's page starts with its section here,
  which is now split by release, each with its date, and the editor's offer of a new version
  lists what is new in it.
- **Random starts** (#53). On a map whose game hands out neutral land when it starts, as 11 Rings
  of Power does, the guide asks no land of the nations that get theirs then, Home says how it is
  handed out, and a failed Check says another start may go otherwise. A unit a loop makes says
  which pair made it, eg "for Orc × Black Pool, one of 384". A chance reads in plain words, eg
  "2 in 6, about 33 %".

## 0.2.7 · 2026-11-23

Fixes and wishes from the work on 0.2.6, each under its GitHub issue.

### Fixed

- **Loops (`foreach`) were read only in Units** (#43). Territories, Nations, Technology,
  Politics and the other tools showed defaults (PUs 0, no capital) where a map writes its
  attachments in a loop, as 11 Rings of Power, mega_new_elk or D-Day do, and a change there
  replaced everything the loop gave that thing. Every tool now reads them as TripleA does. A
  change of something a loop makes gives it an attachment of its own with all the loop's values,
  written after the loop, so nothing is lost; a toast says so, with Undo.
- **Units sat on other unit places than in the game** (#30). The Game view took a territory's
  units in the file's order; it now sorts them as TripleA does (the owner's first, then allies,
  units that cannot move, sea, air, land, attack, …), so a map that keeps its first place for
  one kind of unit, eg a flag on the name plate, looks as in the game.
- **Of two owner lines for one territory the editor took the first**; TripleA takes the last.
  Now the last counts everywhere.
- **Game options: ▲ ▼ across a section's border seemed to do nothing** (#48). They now move an
  option within its section, are off at its ends and while a search or the tick hides options,
  and say why.
- **Files cut every file name** in the folder overview at 1280 px (#47). The names stay whole;
  the purpose is cut instead and shows whole in its tool tip.

### New

- **Change for this unit only** (#44): a unit whose options a loop writes gets its own
  attachment with all the loop's values in one step, and is then changed in Units like any unit.
- **Uses up units** (#45): a section in Units, a row per used-up unit with its count and unit
  type, + to add one (`consumesUnits`).
- **A cost per resource** (#46): beside PUs a Cost field for each resource the unit's rule
  costs, and + for another one, eg Fame. A rule's only cost stays as 0, as TripleA loads no rule
  without a cost.
- **The map makers' forum** (#42) in Guides & links, the user guide and the README:
  https://forums.triplea-game.org/category/28/map-making

### User guide

- Its pictures no longer show folder paths or the user name of the computer that made them (#52).

## 0.2.6 · 2026-11-19

From the feedback of a map maker on 0.2.5, tried with 11 Rings of Power and Total World War; each
change under its GitHub issue.

### Fixed

- **Units showed 0-0-0 for unit types whose options a loop writes** (#31). A map like 11 Rings
  of Power writes one unitAttachment for each unit of a variable (`foreach`). Units now reads
  these as the game does: the Leader of each nation shows its 4-4-2 with 2 hit points. Such a
  unit's options are shown but not changed in Units, with a link to **Attachments**, where the
  loop is changed for all its unit types at once; the cost and the unit's own supports stay to
  change. Loops over two variables side by side (`$A$:$B$`) are read too.
- **Ticks in Production › Buy showed late on a big map** (#32). Every change of the map, in any
  tool, read a text file again for each row of the Actions list, and built the Units page anew
  although it was not on screen: on 11 Rings of Power over a second per tick. A text file is now
  read once, and Units waits until it shows: a tick there takes under a tenth of a second.
- **Every change is quicker on big maps.** A tool that is not on screen (Politics, Technology,
  Terrain effects, Connections, Map look, Game options, Attachments, Units)
  no longer builds its page anew at every change, only when it shows again; and Actions finds
  its actions' texts without looking through all attachments for each. On 270BC Wars a change
  took 0.6 s in any tool and now takes about 0.1 s.
- **Units showed only a unit's PUs** (#33). A unit that costs other resources too, as 11 Rings'
  leaders do, now shows all of them in the list and under its name: "1 PU + 5 Fame". The Cost
  field stays the PUs; its tool tip names the rest, which are changed in Production. A unit that
  costs no PUs but another resource no longer reads "not for sale". A unit that uses up other
  units when it is placed, as Total World War's do, says that too: "6 PUs · uses 2 Material".
- **Moving and ordering rules in Production did not show** (#40). ▲ ▼ and ⇅ changed the game,
  but the table kept its old order until another list was chosen. The rows now follow at once.
  With All rules shown, which is the game's order, the three buttons are off and say why; ▲ ▼
  without a rule chosen say to choose one.
- **Terrain effects cut off its "In the game" text** at the right end; it wraps now.

### Changed

- **The map's views are one button** (#38). **View: Game ▾** above the map, in every tool and at
  every window width, in place of a row of buttons that became this one only when short of room.
  Its menu has four views: Game, Owners, Political and Values. The Units view is gone, as Game
  shows the units (zoomed far out, where Units showed a count per territory, zoom in to see
  them); the neighbours' lines belong to Connections, which shows them always and has
  no view button.
- **Units' sections in a new order** (#36): what a unit does in a battle first (Combat, Gives
  support, Air and anti-air, Bombing, Sea), then how it moves (Movement, Transport and carrier),
  then the rest (Miscellaneous, Production and infrastructure, Special). Bombing shows for air
  units. "Not for land units" now holds only the other kinds' sections; Production and
  infrastructure and Special, which are for no kind, wait in "More sections".
- **Units' Value says what it is** (#34). Its tool tip names the TUV and says what the grey "its
  cost" in the empty field means: TripleA takes what the unit costs. A "?" beside it has a short
  guide: when to set it, and a rule of thumb to work it out.
- **Values and Places › PUs say how they differ** (#35). Both show a territory's PUs: the Values
  view at the territory's center, Places › PUs where the game draws the number (pu_place.txt).
  The view's tool tip and the tool tip of Places › PUs now say so.
- **Production reads easier** (#37). In Buy and Repair each unit's row shows the unit's picture,
  as the nation that buys from the list shows it, and the numbers stand in the middle of their columns. In Resources › At the start each
  resource has its icon above its name, the numbers are centered, and the number columns stay as
  narrow as they need: room to spare goes to the nations' names.
- **Tool tips show sooner**, after a third of a second, so the reason why a field is off is not
  missed.

### Documentation

- **The user guide in a new design** (#39). A quiet site without frames: the editor's groups
  as tabs on top, the tools of a group as a row above each page, and each page a list of tasks
  ("Add a unit type", "Move a capital") that open in place, with short steps, a tip where one
  helps, and a small picture of that part of the editor. The pictures follow the site's light
  or dark theme and show big on a click. "Every control" at the end of a page keeps the full
  description of each control. The whole-window pictures with numbered callouts are gone.

## 0.2.5 · 2026-11-18

### Added

- **A setup with a window of its own** (#28). The Windows download is now
  `eddies-map-editor-setup.exe`: one window in the editor's colors, dark or light as Windows is
  set, in place of Windows Installer's pages. It shows the folder it installs into, with
  **Change…**, two boxes for the Start menu entry and the desktop shortcut, and **Install**;
  then the progress, and at the end where to start the editor and where its files are, with
  **Start the editor**. Run again, or from Windows' list of programs, it offers to remove the
  editor. Inside it is the same msi as before, which still comes with each release. An update
  from within the editor takes the setup and shows its progress.

### Changed

- **File > Look for a new version** (#28): the menu stays open and says beside the words when
  this is the newest.

### Fixed

- **The installer did not say where it installs, and its Start menu entry was hard to find**
  (#28). The entry sat in a Start menu folder called "Unknown". It is now under **Eddies Map
  Editor**, and the installer offers a desktop shortcut as well. A new page shows the folder the
  editor is installed into, `C:\Users\<you>\AppData\Local\Eddies Map Editor`, and lets you choose
  another; an update from within the editor keeps the folder chosen.

## 0.2.4 · 2026-11-18

### Fixed

- **Units drawn bigger than in the game, PU pictures a line too low** (#27). The editor fitted
  every unit's picture into a square of the map's unit width, so the small flags that 1888A, the
  Shoguns and 11 Rings of Power place to the pixel on their name plates, 20 x 28 px, came out as
  blocks of 72 x 72, and a picture of 72 x 54 was stretched. A unit is now drawn at its
  picture's own size by `units.scale`, from its place's top left, as TripleA draws it. A PU
  picture sat a line of text below its place with names drawn from the top left, and its own
  height too low without: now its top left is at the place, or its bottom left. Both compared
  with TripleA's own drawing of the same territories.

## 0.2.3 · 2026-11-18

### Added

- **A Windows installer** (#28). Each release brings `eddies-map-editor-windows.msi` beside the
  zip. It installs the editor for you alone, so it asks for no administrator rights, adds a
  Start menu entry and an uninstaller, and a newer installer replaces the installed version in
  place. Its pages carry the editor's icon and colors. It is not signed, so Windows warns at its first start: More info, Run anyway. The zip
  stays for those who want no installer.
- **A new version installed from within the editor** (#28). An editor installed by the installer
  offers **Install** on a new version: it downloads the release's installer, checks
  its size and SHA-256 against what GitHub tells of the release, asks, also about unsaved
  changes, then closes; Windows installs the new version showing only its progress, and the
  editor starts again. An editor run from the zip is offered the release's page.
  **File > Look for a new version** asks at any time.
- **A new version shows where it is seen.** No longer a toast at the window's bottom for eight
  seconds: the offer comes once in the middle of the window, and the button **⬆ New version**
  stays at the top right, on the start page and beside a map's buttons, until you have the
  version.

### Changed

- **A word when a new version is out.** Once a day when the editor starts, it asks GitHub for
  the latest release and shows a toast with Download when there is a newer one, once per version.
  Nothing but that request leaves the computer; **Tell me of new versions** in the File menu
  turns it off.

### Fixed

- **Flags on sea zones and their PU pictures missing in the Game view** (#27). Maps like 11
  Rings of Power, 1888A and the Shoguns place each sea zone's flag by convoy.txt, to the pixel
  over the map's picture. The Game view now draws a sea zone's convoy flag, blockade marker and
  kamikaze flag at their places, as the game does. The flag is the one the game takes: the
  nation's `_convoy` flag with Map look's "Each nation's own convoy flags", else its normal
  flag, the faded flag on a kamikaze zone, the Neutral's for a zone nobody owns. A sea zone's
  value shows as its PU picture where the game draws it, with sea zone names on or along with the
  zone's comment, and Places offers its PUs place to drag. A sea zone's name shows only with
  both name boxes ticked, and no name the map says not to draw.

## 0.2.2 · 2026-11-18

### Changed

- **Flags in Nations and Politics** (#19). The Nations list shows each nation's colour and flag side
  by side; the Politics matrix shows the flag before each row and above each column.
- **Politics says what a change costs and how it holds** (#20). A relationship type's page lists the
  political actions that set a pair to it, each with its pairs, cost, chance and who must accept,
  with a link to Actions, and a note on the type's upkeep. TripleA knows no length of a change: a
  `relationshipChange` with a fourth part such as `:3` is refused when the game loads; a pair keeps
  its type until an action or trigger changes it, `upkeepCost` is paid each turn.
- **Terrain effects say how the game shows an effect** (#21): its name in brackets on the status
  bar, a picture there with `territoryEffects/<name>.png`, or on the map too at the places in
  territory_effects.txt with `map.useTerritoryEffectMarkers`. The line says which this map does
  and what the next way needs. The game draws no effect values; "picture with text" is text in the
  picture.
- **Publish > Write it** names description.html and goes to the Description box, which is marked
  for a moment so the cursor is found; **Shorten it** when the text is too long (#24).
- **Move and order the entries of a list** (#26). Production > Buy: ▲ ▼ move the chosen rule, ⇅
  orders the list A to Z, by cost or by kind as TripleA's buy tabs sort units, for one list or
  every list at once. Units: ▲ ▼ and ⇅ for the unit types, the order TripleA lists and draws
  them in. Game options: ▲ ▼ on every row, ⇅ sorts A to Z. Each is one step Undo takes back.
- **Choose lists offer the game's $variables$** (#22) of their kind, nations, territories or unit
  types, under a Variables heading below the names, wherever the chosen names go into an
  attachment's option, which is where TripleA expands them: Units, Nations, Technology, Terrain
  effects, Canals and the trigger assistant. A production rule or placement takes none, so none
  is offered there.

### For developers

- A **pre-commit hook** (`.githooks`, installed by the first build) runs UserGuideTest before a
  commit that touches the sources or the guide, so a tool, view or menu item the user guide does
  not name cannot be committed. `SKIP_GUIDE_CHECK=1` skips it once.

## 0.2.1 · 2026-11-18

### Fixed

- **Check and Play said nothing about a game that does not start, with today's TripleA.** The
  start check reads the game with TripleA's own parser to get its reason; the TripleA releases of
  2026 changed that parser's shape, so the check fell back to "loads or not" and never played the
  first round. The reasons are back: a nation without a capital, a step without its nation, land
  units at sea, a territory without its attachment, each named with the step TripleA stops at.
- **Production > Resources cut off headings and values** (#23). The table of starting resources
  sizes each column to its heading and its widest number, at least five figures, takes the
  window's width when its columns need more than the cards above, and scrolls sideways only
  beyond that.
- **Publish said "No version yet" with a version in the game file** (#25). Publish now has
  both: **Version**, the whole number in map.yml that TripleA's guide asks for, raised with
  each update, and **Game version**, the game file's own (info version) as free text, which
  TripleA's upload guide recommends changing at each update though the game does not read it.
  **Raise** raises both, 1 and 0.020 to 2 and 0.021. The list names both and says which is
  which. A map.yml version with a point or letters is listed as to do, and typing one is refused
  with a word on the format.
- **Backup files in the map's folder** (#15). The first version of each file a save changes is no
  longer kept as `.bak` beside it, but in the editor's own Backups folder, `eddies-map-editor/Backups`
  in your home folder, a folder per map with the map's paths below it. A copied or uploaded map
  folder holds the map alone. The toast after a first save shows the backup in Files, read only.
  Old `.bak` files in a folder are still listed by Publish and left out of the zip.
- **Names, PUs, capitals and unit counts drawn off from where TripleA draws them** (#16, part of
  #17). The editor drew a letter badge or a star centered on a place, while the game draws a name
  from the place's left end on its baseline and a flag or PU picture from the place's top left.
  Now a place in the Places mode shows what the game draws there, the name in the map's font, the
  PU picture, the large flag, the victory city marker, with an outline and a dot on the point the
  file keeps. The Game view draws the same at the game's positions, also for territories without
  a place, as far as Map look's "On the map" boxes show them. A stack's count sits where the game
  puts it, a quarter in and a unit's height down, or as `units.counter.offset` says. Create
  missing places centers the name's text and the map's own flag on the territory.

### Documentation

- A **user guide** in `docs/guide`: an overview with the window, keys and menus, a page per tool
  that describes every control, and a picture per page with numbered callouts. The pictures are
  made from the running editor (GuideScreenshots) in the dark theme; a test keeps every tool,
  view and menu item named in the guide. The guide is published as a site at
  https://xxeddiexxx.github.io/eddies-map-editor/ by MkDocs Material, built on each push.

### For developers

- The tests run against the installed TripleA. With TripleA 2026-09.16 one test fails,
  StackingLimitsTest.setsUnitLimits: that TripleA stores a unit's movementLimit as its
  placementLimit, which TripleA has fixed since. With TripleA 2026-11.16 every test passes.

## 0.2.0 · 2026-11-17

From the feedback of map makers on 0.1.91, each change under its GitHub issue.

### Fixed

- **Units drawn as name badges** (#2). Pictures a map keeps in its `units` folder itself, shared
  by every nation as 1941 Global Command Decision keeps its factories and bunkers, are found. The
  nation's own picture still wins. Not yet confirmed on that map by the map maker who reported it.
- **Gives support said None, limits were blank** (#9, #10). A map that writes one attachment for
  each unit or nation of a variable (`foreach="$All-Fighters$"`) was read as if it had none. Such
  supports and stacking limits now read as TripleA reads them. They show read-only, with a link
  to Attachments, where the one written is changed. Units also lists the nations' limits that
  count the unit. Checked against 1941 Global Command Decision.
- **A renamed nation keeps every flag** (#12). Flags with a suffix of the map's own, such as
  `_star_fill`, `_large_alt`, `_medium`, `_fade2` or `_flag`, were left under the old name and
  then deleted with it; only the game's four variants moved. Now every file in flags named after
  the nation moves. Checked by renaming a nation in 201 downloaded maps: unit pictures always
  moved, these flags were the only loss.

### Changed

- **Value (tuv)** sits in the top row beside the cost, blank for "its cost" (#3).
- **Resources it makes each turn** (`createsResourcesList`) is a field under Production and
  infrastructure: a count and a resource, a minus count for upkeep (#3). A unit with two such
  options keeps them under More options.
- The **Stacking limits** section is **Miscellaneous**, with Stacking limits as its heading (#4).
- **Rename map** in the File menu and beside the title on Home (#5). It renames the game and the
  entries in map.yml that carried the old name. The folder keeps its name, which the game finds
  the map by. Publish keeps its Map name field.
- The message after the **save of a resized map** ends with the map's new size (#6).
- The **list of unit types** shows each unit's picture before its name, at most 32 px (#8).
- **Resize map at any size** (#1). A saved map is resized tile by tile from its tiles on disk,
  never holding the whole picture, so 1941 Global Command Decision (16816 × 8085) can be made
  bigger. The new tiles show at once, undo is free, and a save moves them into the map, keeping the
  old ones aside so an undo after the save still has them. A picture not saved yet is still held
  whole, up to 50 million pixels. A bar under the map's toolbar shows how far a resize is.
- **Loading bars** where a big map makes you wait, in the banner's row above the map so nothing
  jumps: while its picture is read from the tiles for the small zooms, and while a resize works. In
  Map look a spinner beside Resize map says it is working. The New map wizard's bar also shows
  while the picture is read.
- **Save the map's picture or relief as one PNG at any size** (#13). The map picture menu writes
  the tiles into one file row by row, so a map too big to hold still comes out whole, for a paint
  program. New: the relief the same way.

### Tried, taken out again

- Enlarging a picture **bicubic**, and copying a flat picture pixel by pixel at a whole step (#7).
  Neither looked good on Settlers: Fallen Empire: bicubic fringed the lines blue and yellow. The
  resize is as in 0.1.91 again. Enlarging by an odd factor cannot keep a painted relief sharp;
  enlarge it in an image editor and import it.

### Known, not fixed

- A unit's icon sometimes disappears (#11). No repro yet.
