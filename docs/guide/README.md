# Eddie's Map Editor

<div class="hero" markdown>

**A map editor for TripleA.** Turn a picture into territories, give nations their land, units
and rules, and play the map in TripleA, without writing XML by hand. This site is the user
guide: a page for every tool, with pictures.

[Download the editor](https://github.com/xXEddieXxx/eddies-map-editor/releases/latest){ .md-button .md-button--primary }
[Make your first map](getting-started.md){ .md-button }

</div>

## What you can do with it

- **Territories from a picture.** Draw borders as dark lines; the editor finds the territories,
  sea zones and neighbours. Draw, erase, split and merge later.
- **Owners, values and units** one territory at a time or with a paint brush.
- **Nations** with alliance, colour, flag, starting PUs and their own rules.
- **Units** with cost, combat values and every ability in plain words.
- **The round**: turn order, game options, political actions and a trigger assistant.
- **Check and Play.** Check says what stops the game; Play starts TripleA with your map.

## Start here

<div class="grid cards" markdown>

- [**Getting started: your first map**](getting-started.md)

    From a picture to a game you can play, step by step.

- [**The window**](#the-window)

    The header, the sidebar, the keys, saving and undo.

- [**New map**](new-map.md)

    The wizard that starts a map from a picture in three steps.

- [**Home**](home.md)

    What to do next, the problems, the overview and a tile per tool.

- [**Menus**](menus.md)

    The File menu, the map picture menu, View, Paint, Places, Sort and the ⋯ list.

</div>

## The tools

Every tool has a page: its tasks, then each of its controls.

<div class="grid cards" markdown>

- **Map**

    [Territories](territories.md) ·
    [Connections](connections.md) ·
    [Canals](canals.md) ·
    [Terrain effects](terrain-effects.md) ·
    [Map look](map-look.md)

- **Players**

    [Nations](nations.md) ·
    [Politics](politics.md) ·
    [Objectives](objectives.md) ·
    [Technology](technology.md)

- **Units & economy**

    [Units](units.md) ·
    [Production](production.md)

- **Game flow**

    [Turn order](turn-order.md) ·
    [Game options](game-options.md) ·
    [Actions](actions.md) ·
    [Triggers](triggers.md)

- **Texts & sound**

    [Game notes](game-notes.md) ·
    [Texts](texts.md) ·
    [Sounds](sounds.md)

- **Share**

    [Publish](publish.md)

- **Advanced**

    [Attachments](attachments.md) ·
    [Files](files.md)

</div>

## The window

The header on top, the tools in the sidebar on the left, the chosen tool beside it. Most tools
show the map.

??? task "Save, undo and redo"

    ![The header: File, the map's name, the save state, Undo, Redo, Check, Save and Play](img/window-header.png#only-light){ .shot }
    ![The header: File, the map's name, the save state, Undo, Redo, Check, Save and Play](img/window-header-dark.png#only-dark){ .shot }

    1. Change something. Every change is a step; the header then says "• Not saved" in orange
       and shows the **Save** button.
    2. Press **Undo (Ctrl+Z)** to take the last step back, **Redo (Ctrl+Y)** to do it again.
       Undo also works after a save.
    3. Press **Save** (Ctrl+S). Its tool tip lists what the save writes, for example "Saves 3
       files: 300BC.xml, place.txt, map.properties".

    Save writes only what changed and keeps the first version of each file in the editor's
    Backups folder, `eddies-map-editor/Backups` in your home folder, a folder per map. A save
    never writes a half file: it writes beside the file and moves it into place.

??? task "Check and play the map"

    1. Press **Check (F6)**. The editor loads the map as TripleA does, plays the first round in
       memory and lists what stops the game.
    2. Read the result in the pill beside the save state, or on [Home](home.md).
    3. Press **Play (F7)**. It saves, runs the same check on the saved files and starts TripleA
       with the map.

    One of Check, Save and Play is highlighted: the one that comes next.

??? task "Open a tool"

    ![The sidebar: the tools in their groups, Advanced, Guides & links and « at the bottom](img/window-sidebar.png#only-light){ .shot }
    ![The sidebar: the tools in their groups, Advanced, Guides & links and « at the bottom](img/window-sidebar-dark.png#only-dark){ .shot }

    1. Click a tool in the sidebar. Its groups are Home, Map, Players, Units & economy, Game
       flow, Texts & sound and Share. The chosen tool has a bar at its left.
    2. Press **Advanced** to fold the Attachments and Files tools in and out.
    3. Press **?** beside the tool's title to read what the tool is for.

    !!! tip
        Alt+1 to Alt+9 open the first nine tools.

??? task "Find something in a long list"

    1. A tool with many things, such as Territories or Units, has a list with a search field
       above it. Type a part of a name.
    2. Where the list has groups, click a header row to fold a group.
    3. Click a row to choose it. The tool then shows what it has, with "← All …" at the top to
       go back to the list.

??? task "Show the options a tool folds away"

    1. Options the game reads rarely sit behind a "▸ More … options (n)" row. Click it to open
       it; the editor remembers which rows are open.
    2. Choose **Show all options** in the File menu to open every such row in every tool, now
       and next time.

??? task "Every control"

    - **File** opens the [File menu](menus.md): open, new, recent, rename, export, publish,
      play with, the folder, close, Show all options.
    - **Save state.** "All saved", or "• Not saved" in orange, and "not checked" until Check
      ran. The **Save** button appears beside it once there is something to save.
    - **Undo (Ctrl+Z)** takes the last step back. Every change is a step, also a save does not
      end the chain.
    - **Redo (Ctrl+Y)** does it again.
    - **Check (F6)** loads the map as TripleA does, plays the first round in memory and lists
      what stops the game. The result shows as a pill beside the save state and on Home.
    - **Play (F7)** saves, runs the same check on the saved files and starts TripleA with the
      map.
    - **The sidebar** lists the tools in groups: Home, Map, Players, Units & economy, Game flow,
      Texts & sound, Share. Alt+1 to Alt+9 open the first nine. The chosen tool has a bar at its
      left.
    - **Advanced** folds the Attachments and Files tools in and out. The editor remembers it.
    - **Guides & links** opens TripleA's map making guides and the places to ask for help.
    - **«** at the bottom of the sidebar shows icons only, to give the map room; the icons' tool
      tips still name the tools.
    - **?** beside a tool's title explains the tool. Many fields have their own "?".
    - **Hide** hides the hint line under the title. The "?" still has it.

    **The map beside the tools**

    - Most tools show the map. Its toolbar, views and legend are described on the
      [Territories](territories.md) page, since that tool uses them most.
    - The row under the toolbar shows a goal while you follow a step of Home's guide, and a
      progress bar while the map's picture is read from its tiles or a resize works.

    **Keys**

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
    | Alt+1 … Alt+9 | The first nine tools |
    | Ctrl+0 | Fit the map |
    | Ctrl+wheel | Zoom the map |
    | Delete | In a table: remove the chosen row |

## When something goes wrong

!!! tip "A unit shows as a name badge on the map"
    The editor found no picture for it: none in `units/<nation>/`, none in `units/` itself, and
    none among the game's own. Upload one in Units.

!!! tip "Gives support says None, stacking limits are blank"
    The support or limit may be written in Attachments for every unit or nation of a variable
    (`foreach`); the tools show those read-only.

!!! tip "A size in Resize map is greyed out"
    The picture is not saved yet: save first, then it is resized from its tiles at any size.

!!! tip "The game does not start"
    Check (F6) says why and links the tool.

!!! tip "The toolbar's words are cut, like \"Draw bord…\""
    The window is narrow for the inspector shown; widen it, or hide the sidebar's words with «.

Found a bug or miss a feature? Open an issue at
[github.com/xXEddieXxx/eddies-map-editor/issues](https://github.com/xXEddieXxx/eddies-map-editor/issues).

## About this guide

The pages use the editor's own words: a control is named as you see it on screen. A page is a
list of tasks; click one to open its steps. The last task, Every control, explains each control
of the tool. The editor follows TripleA's look, so with a light TripleA it is light, with a dark
one dark, with the same controls in the same places. For installing the editor see the
[README on GitHub](https://github.com/xXEddieXxx/eddies-map-editor#get-started); each release
names the TripleA release it was tested with.
