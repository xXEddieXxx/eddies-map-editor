# Eddie's Map Editor: user guide

How to make and change a TripleA map with the editor, tool by tool. For installing it see the
[README](../README.md). This guide follows the editor's own words: a heading here is a tool or a
menu item as you see it on screen.

## The window

- **Start page.** Open a map folder, a recent map, or make a new one. Maps live in TripleA's maps
  folder, where TripleA finds them.
- **Header.** The File menu, the map's name, whether it is saved, and the buttons Check, Save,
  Play, Undo and Redo. One of them is highlighted: the one that comes next.
- **Sidebar.** The tools, in groups: Home; Map; Players; Units & economy; Game flow; Texts &
  sound; Share; Advanced. Each tool has a "?" that says what it is for.
- **The map.** Shown beside most tools. The toolbar above it has zoom, Fit the map (Ctrl+0) and
  the map picture menu. The row under the toolbar shows a goal while you follow a step of the
  guide, and a progress bar while the map's picture is read from its tiles or a resize works.
- **Views.** Game, Owners, Political, Values, Units and Connections show the same map in
  different colours; the legend below explains them.

### Keys

| Key | Does |
| --- | --- |
| Ctrl+O | Open a map |
| Ctrl+N | New map |
| Ctrl+S | Save |
| Ctrl+Z, Ctrl+Y | Undo, Redo |
| F6 | Check |
| F7 | Play |
| F8 | Export as zip |
| Ctrl+W | Close map |
| Ctrl+0 | Fit the map |
| Ctrl+wheel | Zoom |

### File menu

- **Open a map…**, **New map…**, **Recent maps**.
- **New game from this one…** copies the game under a new name beside it, for a shorter or
  different scenario of the same map.
- **Rename map…** renames the game and the entries in map.yml that carried the old name. The
  folder keeps its name, which the game finds the map's files by. The same Rename… sits beside the
  title on Home.
- **Export as zip…** packs the map without the `.bak` files, as the map server takes it.
- **Publish…** opens the Share tool.
- **Play with…** chooses the TripleA to start the map with.
- **Show in Explorer** (Finder on a Mac) opens the map's folder.
- **Close map** goes back to the start page.
- **Show all options** opens every "▸ More options" row in every tool, now and next time.

### Saving and undo

Every change is a step. Undo takes it back, also after a save. Save writes only what changed and
keeps the first version of each file as `.bak`. The Save button's tool tip lists what the next
save writes.

### Check and Play

- **Check (F6)** loads the map as TripleA does, plays the first round in memory and lists what
  stops the game, with a link to the tool that fixes it.
- **Play (F7)** saves, runs the check on the saved files and starts TripleA with the map.

## Home

Your map at a glance: what to do next and what still keeps it from being played. The guide on the
left walks through the steps of a new map; the overview on the right counts territories, nations
and units. Rename… beside the title renames the map.

## Map

### Territories

Click a territory to change it: owner, PUs, capital, victory city, starting units and places. Use
Paint to give many territories an owner, a value or units at once. Draw borders draws lines into
the picture and Make territories finds the territories between them; Erase takes lines out.
Places puts units, names, flags and markers where you drag them, or right-click a territory to add
and remove them.

### Connections

Click a territory, then its neighbours to connect or part them. Check finds territories without a
connection.

### Canals

Canals and straits, eg Suez: ships pass between the two sea zones only while their side holds the
land territories. Choose a canal or make a new one, then click its sea zones and land.

### Terrain effects

Effects such as forest or mountain: how units fight and move in the territories that have them,
and which territories have them.

### Map look

How the game draws your map: the size of the units, whether it scrolls around, what it shows on
the territories, textures and a relief picture over the map, the small map, and Resize map.

#### Resize map

From Map look or the map picture menu. Choose a size from 25 % to 400 %; the picture, the borders
and every place are resized with it, units keep their size. A saved map is resized tile by tile
from its tiles on disk, at any size, with a progress bar above the map; a picture not saved yet is
held whole, up to 50 million pixels. Undo takes a resize back, also after the save. A map enlarged
by an odd factor such as 120 % looks softer than before: a painted relief cannot be enlarged
sharply. For the sharpest result enlarge the picture in an image editor and import it, or resize in
whole steps.

### The map picture menu

The button at the right of the toolbar above the map.

- **Show the map picture** switches the picture under the colours on and off.
- **Load another picture…** replaces the picture, keeping the territories.
- **Redraw borders from a picture…** takes a picture with changed borders and redraws the
  territories from it, keeping names, owners and units.
- **Find territories again** looks for territories in the whole picture.
- **Resize map…** as above.
- **Save the map's picture as a file…** writes the whole picture, without the relief, as one
  PNG at any size, to change it in a paint program and load it again.
- **Save the relief as a file…** the same for the relief.

## Players

### Nations

Each nation's alliance, colour (shown as in the game), flag, starting PUs and own rules. Renaming
a nation renames it in the game and moves its flags of every variant and its unit pictures to the
new name on the next save; the old files go once the game is saved under the new name. Stacking
limits per nation are set here; limits written once for every nation of a variable show
read-only with a link to Attachments.

### Politics

Nations start allied or at war by their alliance. Choose a type here only where a pair starts
differently, eg neutral.

### Objectives

National objectives: PUs a nation gets at the end of its turn while its checks hold, eg while it
owns certain territories, each with the text the players read.

### Technology

Technologies nations can research, and a research chart for each nation that can.

## Units & economy

### Units

The kinds of units. Each has a picture, and the top row: Cost, Value (tuv, what the AI and the
battle calculator take it for, blank for its cost), Attack, Defense, Movement, Hit points, and
whether it is a land, sea or air unit. Below, its options in sections: Combat, Gives support,
Movement, Transport and carrier, Sea, Air and anti-air, Production and infrastructure (including
Resources it makes each turn, eg -1 PUs upkeep), Bombing, Special, and Miscellaneous with the
stacking limits. Search options finds an option by its words or by TripleA's name for it. What no
section shows is under More options; Add option adds any option TripleA reads. The list on the
left shows each unit's picture. Upload picture… gives a unit its own picture.

### Production

Each nation buys from one purchase list: rules like "1 infantry for 3 PUs". Pick a list, choose
who uses it, and tick what it sells; also repairs, resources and the buy window's tabs.

## Game flow

### Turn order

The steps of a round, top to bottom: what happens in each, eg buying or battles, and whose step it
is. Add, move or remove steps; choose one to change it. Game options have a "?" each.

### Actions

The buttons a nation may press in its politics step, eg to declare war, and its user actions,
which fire triggers. Each has its texts, the conditions it needs, a cost and what it does.

### Triggers

The game's triggers in plain words: what each does, when and how often. Change one or make a new
one step by step in the trigger assistant, starting from an example.

## Texts & sound

### Game notes

What players read about your game: its story, rules and tips, in HTML, with a preview as the game
shows it.

### Texts

The texts the game shows by key: objectives, the messages of triggers, unit tool tips and the
texts of actions. Each is saved in its file beside the game.

### Sounds

The sounds the game plays, eg for a land battle or a nation's turn. Choose mp3 files for a sound,
or leave it to the game's own.

## Share

### Publish

What your map needs before players can download it in TripleA: its name and version in map.yml,
the description and preview the list of maps shows, and the steps to upload it. Export as zip
packs it.

## Advanced

### Attachments

Every attachment of the game: triggers, conditions, canals, actions and all the others, with their
options as the game file has them. For what no other tool shows, and for attachments written once
for every element of a variable (`foreach`), which the other tools show read-only.

### Files

The map's files as on disk. Open one to change it; the game's files are checked as you type. Save
file keeps the first version as `.bak` and reads the map again.

## When something goes wrong

- **A unit shows as a name badge.** The editor found no picture for it: none in `units/<nation>/`,
  none in `units/` itself, and none among the game's own. Upload one in Units.
- **Gives support says None, stacking limits are blank.** The support or limit may be written in
  Attachments for every unit or nation of a variable; the tools show those read-only.
- **Resize map is greyed out at a size.** The map's picture is not saved yet: save first, then it
  is resized from its tiles at any size.
- **The game does not start.** Check (F6) says why and links the tool.

Found a bug or miss a feature? Open an issue at
https://github.com/xXEddieXxx/eddies-map-editor/issues.
