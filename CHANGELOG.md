# Changelog

What changed for map makers, release by release. The GitHub release of each version carries the
downloads; this file carries the words.

## Unreleased

From the feedback of map makers on 0.1.91, each change under its GitHub issue.

### Added

- **A setup with a window of its own** (#28). The Windows download is now
  `eddies-map-editor-setup.exe`: one window in the editor's colors, dark or light as Windows is
  set, in place of Windows Installer's pages. It shows the folder it installs into, with
  **Change…**, two boxes for the Start menu entry and the desktop shortcut, and **Install**;
  then the progress, and at the end where to start the editor and where its files are, with
  **Start the editor**. Run again, or from Windows' list of programs, it offers to remove the
  editor. Inside it is the same msi as before, which still comes with each release. An update
  from within the editor takes the setup and shows its progress.

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
  **File > Look for a new version** asks at any time; the menu stays open and says beside the
  words when this is the newest.
- **A new version shows where it is seen.** No longer a toast at the window's bottom for eight
  seconds: the offer comes once in the middle of the window, and the button **⬆ New version**
  stays at the top right, on the start page and beside a map's buttons, until you have the
  version.

### Fixed

- **Units showed only a unit's PUs** (#33). A unit that costs other resources too, as 11 Rings'
  leaders do, now shows all of them in the list and under its name: "1 PU + 5 Fame". The Cost
  field stays the PUs; its tool tip names the rest, which are changed in Production. A unit that
  costs no PUs but another resource no longer reads "not for sale".

- **Ticks in Production › Buy showed late on a big map** (#32). Every change of the map, in any
  tool, read a text file again for each row of the Actions list, and built the Units page anew
  although it was not on screen: on 11 Rings of Power over a second per tick. A text file is now
  read once, and Units waits until it shows: a tick there takes under a tenth of a second.
- **Every change is quicker on big maps.** A tool that is not on screen (Politics, Technology,
  Terrain effects, Connections, Map look, Game options, Attachments, Units)
  no longer builds its page anew at every change, only when it shows again; and Actions finds
  its actions' texts without looking through all attachments for each. On 270BC Wars a change
  took 0.6 s in any tool and now takes about 0.1 s.

- **Units showed 0-0-0 for unit types whose options a loop writes** (#31). A map like 11 Rings
  of Power writes one unitAttachment for each unit of a variable (`foreach`). Units now reads
  these as the game does: the Leader of each nation shows its 4-4-2 with 2 hit points. Such a
  unit's options are shown but not changed in Units, with a link to **Attachments**, where the
  loop is changed for all its unit types at once; the cost and the unit's own supports stay to
  change. Loops over two variables side by side (`$A$:$B$`) are read too.

- **The installer did not say where it installs, and its Start menu entry was hard to find**
  (#28). The entry sat in a Start menu folder called "Unknown". It is now under **Eddies Map
  Editor**, and the installer offers a desktop shortcut as well. A new page shows the folder the
  editor is installed into, `C:\Users\<you>\AppData\Local\Eddies Map Editor`, and lets you choose
  another; an update from within the editor keeps the folder chosen.

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
- **Units drawn bigger than in the game, PU pictures a line too low** (#27). The editor fitted
  every unit's picture into a square of the map's unit width, so the small flags that 1888A, the
  Shoguns and 11 Rings of Power place to the pixel on their name plates, 20 x 28 px, came out as
  blocks of 72 x 72, and a picture of 72 x 54 was stretched. A unit is now drawn at its
  picture's own size by `units.scale`, from its place's top left, as TripleA draws it. A PU
  picture sat a line of text below its place with names drawn from the top left, and its own
  height too low without: now its top left is at the place, or its bottom left. Both compared
  with TripleA's own drawing of the same territories.
- **Flags on sea zones and their PU pictures missing in the Game view** (#27). Maps like 11
  Rings of Power, 1888A and the Shoguns place each sea zone's flag by convoy.txt, to the pixel
  over the map's picture. The Game view now draws a sea zone's convoy flag, blockade marker and
  kamikaze flag at their places, as the game does. The flag is the one the game takes: the
  nation's `_convoy` flag with Map look's "Each nation's own convoy flags", else its normal
  flag, the faded flag on a kamikaze zone, the Neutral's for a zone nobody owns. A sea zone's
  value shows as its PU picture where the game draws it, with sea zone names on or along with the
  zone's comment, and Places offers its PUs place to drag. A sea zone's name shows only with
  both name boxes ticked, and no name the map says not to draw.
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
- **A word when a new version is out.** Once a day when the editor starts, it asks GitHub for
  the latest release and shows a toast with Download when there is a newer one, once per version.
  Nothing but that request leaves the computer; **Tell me of new versions** in the File menu
  turns it off.
- **Choose lists offer the game's $variables$** (#22) of their kind, nations, territories or unit
  types, under a Variables heading below the names, wherever the chosen names go into an
  attachment's option, which is where TripleA expands them: Units, Nations, Technology, Terrain
  effects, Canals and the trigger assistant. A production rule or placement takes none, so none
  is offered there.
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

### Documentation

- A **user guide** in `docs/guide`: an overview with the window, keys and menus, a page per tool
  that describes every control, and a picture per page with numbered callouts. The pictures are
  made from the running editor (GuideScreenshots) in the dark theme; a test keeps every tool,
  view and menu item named in the guide. The guide is published as a site at
  https://xxeddiexxx.github.io/eddies-map-editor/ by MkDocs Material, built on each push.

### For developers

- A **pre-commit hook** (`.githooks`, installed by the first build) runs UserGuideTest before a
  commit that touches the sources or the guide, so a tool, view or menu item the user guide does
  not name cannot be committed. `SKIP_GUIDE_CHECK=1` skips it once.

- The tests run against the installed TripleA. With TripleA 2026-09.16 one test fails,
  StackingLimitsTest.setsUnitLimits: that TripleA stores a unit's movementLimit as its
  placementLimit, which TripleA has fixed since. With TripleA 2026-11.16 every test passes.
