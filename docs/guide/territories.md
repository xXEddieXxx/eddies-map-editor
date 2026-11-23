# Territories

[← Guide](README.md){ .back }

Click a territory to change it. Use Paint to give many territories an owner, a value or units at
once. The tool shows the map with its toolbar, the list of territories on the right, and the
chosen territory in place of the list.

??? task "Give a territory its owner, value and units"

    ![A chosen territory: Name, Kind, Owner, PUs per turn, Capital of its owner, Victory city and Units at the start](img/territories.png#only-light){ .shot }
    ![A chosen territory: Name, Kind, Owner, PUs per turn, Capital of its owner, Victory city and Units at the start](img/territories-dark.png#only-dark){ .shot }

    1. With **Select** chosen above the map, click the territory on the map or in the list.
    2. Choose its **Owner**, or No owner.
    3. Type its **PUs per turn**.
    4. Tick **Capital of its owner** if it is the nation's capital.
    5. Under **Units at the start** press **+ Add units**, pick a kind and type how many.

    !!! tip
        Type 0 as a count to remove that kind of unit.

??? task "Paint many territories at once"

    1. Press **Paint ▾** above the map and choose a brush: **Owner**, **Capital**, **PUs per
       turn**, **Units**, **Passable**, **Terrain effects** or **Textures**.
    2. Click a territory, or drag across several.
    3. Watch the row above the map: it says what is painted and has a field for the value.
    4. Press **Done** in the row above the map to end.

    ![The map's toolbar: View, Select, Paint, Places, Draw borders, zoom, Fit and the map picture menu](img/territories-paint.png#only-light){ .wide }
    ![The map's toolbar: View, Select, Paint, Places, Draw borders, zoom, Fit and the map picture menu](img/territories-paint-dark.png#only-dark){ .wide }

    !!! tip
        To paint units, choose a territory that has them first: Paint ▾ › Units then offers
        **Copy units of …**.

??? task "Move where the game draws units and names"

    1. Press **Places ▾** above the map and tick a kind, for example **Unit places** or **Names**.
    2. Drag a place to where the game should draw it.
    3. Right click a unit place for more, for example **Remove this unit place**.
    4. For every territory at once, choose **Lay out unit places** or **Create missing places**
       in the same menu.

    All items are under Places in [Menus](menus.md).

    !!! tip
        For one territory, **Move places** in its "On the map" section switches the Places mode
        on.

??? task "Draw new borders"

    1. Press **Draw borders** above the map. The relief is hidden so the lines show. Pick the
       line's width and colour in the row above the map; **Like its lines** takes the colour
       most of the picture's lines have.
    2. Draw across the picture. A line joins existing borders where it ends near them. The
       **Eraser** takes a line away where you drag.
    3. Press **Make territories**. The territories enclosed by the new lines are found; the rest
       stay as they were. A dialog lists the changes, each with a **Take** tick: what it
       splits, merges or moves, and the names of new territories. A change your lines cross
       starts ticked (a merge, or land moving to a neighbour, only where you used the Eraser);
       one they do not touch, or that runs through a gap in an old line, starts unticked and
       says why, as does an area outside the old map.
       A new area of a change taken is added under the name you give it.
       A change not taken stays as it was. Cancel keeps your lines, so you can close a gap
       first.
    4. Save. Only the tiles the lines touched are written, their first version kept in Backups.

    !!! tip
        New territories are named by number; rename them in the list.

??? task "Split or merge territories"

    1. Choose the territory.
    2. To cut it in two, press **Split with a line…**, draw a line across the territory and name
       the new one in the dialog.
    3. To add others to it, press **Merge into this…**, then click each territory that belongs
       to it, for example the islands of a group. Esc or **Done** ends.
    4. To make it part of another, press **Merge into…** and choose the other territory.

    !!! tip
        Land and sea cannot be merged.

??? task "Every control"

    **The map and its toolbar**

    - **View** switches the colouring: Game, Owners, Political, Values. See View in
      [Menus](menus.md).
    - **Select** is the usual mode: a click on the map chooses the territory.
    - **Paint** opens the brush menu: owner, capital, PUs, units, passable, terrain effects,
      textures. While a brush is chosen, a click or drag paints; the row above the map says what
      is painted and has a field for the value. See Paint in [Menus](menus.md).
    - **Places** shows where the game draws units, names, flags and markers on every territory,
      to drag; lays out unit places; creates missing ones. See Places in [Menus](menus.md).
    - **Draw borders** draws dark lines into the map's picture; the Eraser takes lines out. The
      relief is hidden meanwhile. Then **Make territories** finds the territories between the
      lines, keeping names, owners and units where a territory stays. Lines drawn are kept with
      the map until a save writes the tiles they touched.
    - **−**, the zoom in percent, **+**: zoom out and in; Ctrl+wheel does the same, a click on
      the percent goes to 100 %.
    - **Fit the map (Ctrl+0)** shows the whole map.
    - **Map picture** menu: show or hide the picture, load another, redraw borders from a
      picture, make territories again, resize, save the picture or the relief as a file. See The
      map picture menu in [Menus](menus.md).
    - The legend under the map names the nations' colours; **+n nations** opens the rest. Under
      the Game view a hatched sea zone is one no unit may enter.

    **A chosen territory**

    - **← All territories** goes back to the list.
    - **Name**, typed and taken on Enter; a name in use is refused.
    - **Kind**: Land or Sea zone.
    - **Owner** the nation that owns it at the start, or No owner.
    - **PUs per turn** what the territory gives its owner each turn to buy units with.
    - **Capital of its owner** ticks the territory as the capital; a nation has one. **Victory
      city** below marks it for a victory condition.
    - **Units at the start**: each kind with its count, × removes it; **+ Add units** adds a
      kind, **Remove all** takes every unit away. 0 in a count removes that kind.

    **The rest of a territory**

    - **Rules**: **Impassable: no unit may enter**; **Units its factories place per turn**, as
      many as its PUs unless set here; **Original owner**, who gets it back when an ally frees
      it, if not its owner at the start.
    - **On the map: name, places, outline.** **Show its name on the map**, off where the picture
      has the name already. **Move places** switches the Places mode on for this territory.
    - **Split with a line…** draws a line across the territory and makes two of it; the new one
      is named in a dialog.
    - **Merge into this…** then click each territory that belongs to it, for example the islands
      of a group; **Merge into…** makes this territory part of another you choose.
    - **Find places again** finds the places inside the territory's border again, for example
      after its outline changed.
    - **More options**: **Original factory: its own, for repairs**; **Resources per turn**
      besides PUs, for example 2:Ore; **Taken by, goes to** pairs captor:receiver, so a
      territory taken by the first goes to the second; **Takes over its units when capturing**
      and **Takes its units by entering** list nations, as the game's whenCapturedByGoesTo,
      changeUnitOwners and captureUnitOnEnteringBy.
    - **Neighbours** lists the connected territories as buttons, each a click away; **→ Edit in
      Connections** opens the Connections tool on this territory.
    - **Remove territory** takes it out of the game, its outline and its places with it. Undo
      brings it back.

    **The list**

    - **Sort** orders the list by Name, Owner or PUs. See Sort in [Menus](menus.md).
    - **⋯ Many territories at once**: export and import names, export territory pictures, remove
      territories. See Many territories at once in [Menus](menus.md).
    - The count, and the search field below it: type a part of a name. Each row names the
      territory with its owner and PUs; a click chooses it.

    **Draw borders, step by step**

    - Choose Draw borders. The relief is hidden so the lines show. Pick the line's width and
      colour in the row above the map; "Like its lines" takes the colour most of the picture's
      lines have.
    - Draw across the picture. A line joins existing borders where it ends near them. The Eraser
      takes a line away where you drag.
    - Choose Make territories. The territories enclosed by the new lines are found; the rest
      stay as they were. New territories are named by number; rename them in the list. The
      dialog before lets you leave out a change, which then stays as it was; changes your
      lines do not touch start left out.
    - Save. Only the tiles the lines touched are written, their first version kept in Backups.
