# Nations

[← Guide](README.md){ .back }

Give each nation an alliance and its starting PUs. Who owns which land is set in Territories. The
map shows the Owners view; the list on the right names the nations, each with its colour and, once
the map has a folder, its flag; a click chooses one.

??? task "Add a nation"

    ![The top of a nation: ← All nations, Name, Change color, Flag…, Alliance and PUs at the start](img/nations.png#only-light){ .shot }
    ![The top of a nation: ← All nations, Name, Change color, Flag…, Alliance and PUs at the start](img/nations-dark.png#only-dark){ .shot }

    1. Press **+ Add nation…** at the list of nations.
    2. Type its **Name** and choose **Buys the same as**: a nation whose purchase rules it gets.
    3. Set its **Alliance**: choose one from the box, or type a new name for a new alliance.
    4. Set **PUs at the start**: what it has to spend in its first turn.

    !!! tip
        The new nation has no land yet: who owns which land is set in Territories.

??? task "Give a nation its color and flag"

    1. Choose the nation in the list.
    2. Press **Change color** and choose the colour the game draws its land in.
    3. Press **Flag…** and choose a picture: it becomes the nation's flags in the sizes the game
       shows.

    The swatch beside the flag opens the same colour chooser.

??? task "Rename a nation"

    1. Choose the nation in the list.
    2. Type the new name into **Name**.

    The game and every file that carries the name follow on the next save, the flags and unit
    pictures included.

??? task "Let a nation play without a capital"

    1. Choose the nation in the list.
    2. Tick **Has no capital**: it then keeps its PUs without one.

    !!! tip
        While the nation has a capital the box says where: untick that in Territories first.

??? task "Limit how many units stand in one territory"

    ![The section Stacking limits of a nation, with one limit and + Add limit](img/nations-limits.png#only-light){ .shot }
    ![The section Stacking limits of a nation, with one limit and + Add limit](img/nations-limits-dark.png#only-dark){ .shot }

    1. Choose the nation and go to **Stacking limits**.
    2. Press **+ Add limit** and choose the unit types.
    3. Set how many, when it holds (placing, moving in or attacking) and whose units count: the
       nation's own, its allies' or everyone's.

??? task "Every control"

    **The top of a nation**

    - **← All nations** goes back to the list, which has **+ Add nation…**. A new nation gets a
      name and **Buys the same as**: a purchase list and a repair list of its own with the chosen
      nation's rules, or the game's shared list.
    - **Compare nations ›** under the list shows all nations side by side.
    - **Change color**: the colour the game draws the nation's land and tints its units in. The
      swatch beside the flag opens the same chooser.
    - **Flag…** chooses a picture: it becomes the nation's flags in the sizes the game shows
      (flags/Name.png, _small, _large, _fade).
    - **Alliance**: nations of one alliance fight together. Type a new name for a new alliance; the
      box lists the ones there are.
    - **PUs at the start**: what the nation has to spend in its first turn. The line below says what
      its territories are worth per turn.
    - **Has no capital**: a nation that plays without one, so it keeps its PUs without it. While the
      nation has a capital the box says where: untick that in Territories first.
    - **Who plays it**: **Played by default** human or an AI, as the game proposes it.
    - **Can be switched off at the start**: players may leave the nation out. **Hidden from the
      player list** hides a nation that only holds neutral land. **May have no capital** lets it
      start without one.

    The header shows the flag, the alliance, the PUs, the capital and the number of territories.
    **Name** renames the nation: the game and every file that carries the name follow on the next
    save, the flags and unit pictures included.

    **Pictures and limits**

    - **Unit pictures**: the unit types the nation can buy or has at the start, each with its
      picture as the game shows it. **In the nation's color** tints the pictures when the game draws
      them; the files stay. **All nations** does that for every nation.
    - **Import pictures…** takes a folder of pictures named like unit types for this nation.
    - **Stacking limits**: at most so many units of some types together in one territory, when
      placing, moving in or attacking, counting the nation's own, its allies' or everyone's.
      **+ Add limit** chooses the unit types. Limits written once for every nation of a variable
      show as sentences with a link to Attachments, where they are changed.
    - **Capitals**: **Capitals needed to keep its PUs** and **to collect income and build**; **Its
      PUs are lost when its capital falls**; **Immune to blockades**.
    - **Sharing**: **Hands its units over to** another nation, also **in all territories, not only
      theirs**; **Shares its technology with**; **Helps pay its research**.
    - **Units it holds at the start**: units the nation holds without a territory, for example a
      fleet at sea; **+ Add unit…**.

    **Placing and moving**

    - **Placing units**: **May place in any land it owns**; **in any sea zone by its land**; **in
      land it took this turn**; **only in its capital**; **May buy more than it can place**; **Units
      placed per territory each turn, at most**; **No placing where it has this many units**.
    - **A free unit per this many territories** and **The free unit**: a unit the nation gets for
      every so many territories it owns.
    - **Moving and attacking**: **Movement** how the nation's units move; **In round 1 its enemies
      defend at 1 at most**, a surprise attack; **Defends as usual against such an attack**.
    - **Other rules**: **+ Add rule…** adds any rule of the game's playerAttachment the sections do
      not show, by its name in the game's XML; each has a field and ×. A Choose list of nations,
      territories or units ends with the game's variables of that kind, `$Allies$` say.
    - **Remove nation** takes the nation out of the game. Its land becomes unowned, its units go.
