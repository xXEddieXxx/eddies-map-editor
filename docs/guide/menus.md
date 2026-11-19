# Menus

[← Guide](README.md){ .back }

The editor's menus and what each of their items does: the File menu in the header, the menus of
the map's toolbar, and the two above the territories list.

??? task "The File menu"

    ![The File menu, open](img/menus.png#only-light){ .shot }
    ![The File menu, open](img/menus-dark.png#only-dark){ .shot }

    **File** at the left of the header.

    - **Open a map… (Ctrl+O)** asks for a map folder, then for the game when the map has more
      than one. Maps are kept in TripleA's maps folder, where TripleA finds them.
    - **New map… (Ctrl+N)** starts the [New map wizard](new-map.md).
    - **Recent maps** lists the maps opened last.
    - **New game from this one…** copies the saved game under a new name beside it, for a
      shorter or different scenario of the same map, and opens the copy.
    - **Rename map…** renames the game, which is the name players choose the map and its game
      by, and the entries in map.yml that carried the old name. The folder keeps its name, which
      the game finds the map's files by. A name has at most 70 characters. Rename… beside the
      title on Home does the same.
    - **Export as zip… (F8)** packs the map's folder, without the `.bak` files an older version
      of the editor left there, as the map server takes it; the map is saved first.
    - **Publish…** opens the [Publish](publish.md) tool.
    - **Play with: TripleA…** chooses the TripleA that Play starts, when more than one is
      installed; the menu names the one in use.
    - **Show in Explorer** (Finder on a Mac) opens the map's folder.
    - **Close map (Ctrl+W)** goes back to the start page, asking about unsaved changes first.
    - **Show all options** opens every "▸ More … options" row in every tool, now and next time.
    - **Tell me of new versions**: once a day when the editor starts, it asks GitHub for the
      latest release. A newer one is offered once in the middle of the window, and stays as the
      button **⬆ New version** at the top right, on the start page and beside a map's buttons,
      until you have it; a click on the button offers it again. Nothing but that request leaves
      your computer; untick to stop asking. An editor installed by its Windows installer offers
      **Install**: it downloads the new installer, checks it against the release, asks, then
      closes, lets Windows install the new version and starts again. An editor run from the zip,
      or on another system, offers **Open the page**, the release's page with the downloads.
    - **Look for a new version** asks now, whatever was asked or told before. The menu stays
      open and the answer shows beside the words: that this is the newest, or that GitHub gave
      none. A newer version is offered as above.
    - **Version** is the editor's version. "dev" for a build from the sources.

??? task "The map picture menu"

    ![The map picture menu, open](img/menus-picture.png#only-light){ .shot }
    ![The map picture menu, open](img/menus-picture-dark.png#only-dark){ .shot }

    The button at the right end of the map's toolbar.

    - **Show the map picture** switches the picture under the colours on and off. Off, the map
      shows the owners' colours and the borders alone.
    - **Load another picture…** replaces the picture, keeping the territories. The new picture
      is cut into tiles on the next save.
    - **Redraw borders from a picture…** takes a picture with changed borders, drawn in a paint
      program, and redraws the territories from it: they keep their names, owners and units, new
      areas become territories, merged ones are joined.
    - **Make territories again…** looks for territories in the whole picture again. Only a
      picture loaded here can be searched; a map opened from its folder has tiles instead.
    - **Resize map…** is described under [Map look](map-look.md).
    - **Save the map's picture as a file…** writes the whole picture, without the relief, as one
      PNG, put together from the tiles. For a paint program; then Load another picture or Redraw
      borders. A map too big to hold is written row by row, at any size.
    - **Save the relief as a file…** does the same for the relief.

??? task "View"

    ![The View menu, open: Game, Owners, Political, Values](img/menus-view.png#only-light){ .shot }
    ![The View menu, open: Game, Owners, Political, Values](img/menus-view-dark.png#only-dark){ .shot }

    One button above the map, **View: Game ▾**: the same map in four colourings. The legend
    below the map explains the one shown.

    - **Game** as it plays: the picture, the owners' colours and the units.
    - **Owners** each territory in its owner's colour.
    - **Political** each territory in its owner's alliance colour.
    - **Values** each territory's PUs per turn, capital and victory city, at its center. Where
      the game draws the number is set in Territories' Places › PUs.

    The Connections tool has no view button: there the map always shows which territories are
    neighbours, as lines.

??? task "Paint"

    ![The Paint menu, open](img/menus-paint.png#only-light){ .shot }
    ![The Paint menu, open](img/menus-paint-dark.png#only-dark){ .shot }

    **Paint ▾** above the map in Territories. A brush: choose what to paint, then click or drag
    across territories.

    - **Owner** a nation, or no owner: dragging across land gives it that owner.
    - **Capital** makes the clicked territory its owner's capital.
    - **PUs per turn** a value from the list, or **Other…** for any value; while painting, the
      banner above the map has a field to change it.
    - **Units** copies the starting units of the chosen territory onto the territories you drag
      across, turned to their owner (**Copy units of …**, after a territory is chosen), or takes
      units away (**Clear units**).
    - **Passable** paints **Impassable**, where no unit may enter, or **Passable** again.
    - **Terrain effects** paints an effect from the [Terrain effects](terrain-effects.md) tool;
      the entry is grey while the game has none.
    - **Textures** paints a texture from the map's textures into the relief; **Find textures in
      the map…** collects them from the picture, **Import texture…** takes a picture file. Sea
      textures are listed apart.

??? task "Places"

    **Places ▾** above the map in Territories. What the game draws on every territory, shown to
    drag. A right click on a place offers more.

    - **Units**, **Capitals**, **Centers**, **Comments**, **Decorations**, **Names**, **PUs**,
      **Terrain effects**, **Victory cities**, **Blockades**, **Convoys**, **Kamikaze**: each
      ticks one kind of place on or off. The tool tip names the file it is kept in, for example
      place.txt for units and name_place.txt for names. "(none)" means the map has none of that
      kind.
    - A place shows what the game draws there: the name or PU value in the map's font, the PU
      picture, the capital's large flag, the victory city marker, a sea zone's convoy flag, each
      with an outline and a dot on the point the file keeps, which is the text's left end on its
      baseline or a picture's top left.
    - A sea zone has a PUs place where the game draws its value: with sea zone names shown, or
      along with the zone's comment.
    - The Game view draws the same things where TripleA draws them, also for territories without
      a place, as far as Map look's "On the map" boxes show them.
    - **Lay out unit places** arranges the unit places of every territory: how many at most per
      territory, and whether units beyond the places go on beside the last place.
    - **Create missing places** adds a place of a kind for every territory that lacks one, for
      example names for territories without a line in name_place.txt.
    - **Reload places from the files** reads place.txt, the marker files and decorations.txt
      again, for a map changed by hand or with another program while it is open.
    - A click in the chosen territory adds a unit place.
    - A right click on a unit place offers **Add a unit place here**, **Remove this unit
      place**, **Back to its found place**, **Put here** and **Units overflow to the left /
      right**.

??? task "Sort"

    **Sort** above the territories list. The order of the list: by **Name**, by **Owner** or by
    **PUs**.

??? task "Many territories at once"

    The ⋯ button beside Sort.

    - **Export names…** writes the names as lines `name;` to fill in the new names.
    - **Import names…** renames territories by a text or CSV file of lines `old;new`.
    - **Export territory pictures…** writes a PNG of each territory chosen, to draw on in a
      paint program and lay over the map.
    - **Remove territories…** ticks the territories to remove, the smallest first, for example
      islands found by mistake.
