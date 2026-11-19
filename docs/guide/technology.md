# Technology

[← Guide](README.md){ .back }

Technologies nations can research. Give each nation a research chart for it; leave Not researched
where a nation should not get it.

??? task "Add a technology"

    ![The list of technologies with + Add technology… and the line Chosen in Game options ›](img/technology.png#only-light){ .shot }
    ![The list of technologies with + Add technology… and the line Chosen in Game options ›](img/technology-dark.png#only-dark){ .shot }

    1. Press **+ Add technology…**.
    2. Choose **TripleA built-in** for one of the game's own, or **Own** for a new one with its
       own bonuses.
    3. Give each nation that may research it a research chart, see below.

    !!! tip
        A game that uses TripleA's standard technologies says so above the list; changing one
        writes the list into the game.

??? task "Say what a technology gives"

    ![What it gives: the bonuses of a technology, each a number for a unit type, and + Add bonus…](img/technology-bonus.png#only-light){ .shot }
    ![What it gives: the bonuses of a technology, each a number for a unit type, and + Add bonus…](img/technology-bonus-dark.png#only-dark){ .shot }

    1. Choose the technology in the list and go to **What it gives**.
    2. Press **+ Add bonus…**.
    3. Choose **What** it changes and the **Unit type**, and set **By how much**, for example +1
       attack for fighters.

    The rarer settings are under **Rockets, war bonds, factories, airborne forces**.

    !!! tip
        A standard technology with nothing set here keeps its standard effect, which the text
        names.

??? task "Let a nation research a technology"

    1. Choose the technology in the list.
    2. In the nation's row, choose its **Research chart**: the set of technologies it may get.
       Type a name for a new chart.
    3. Leave **Not researched** where a nation should not get it.

??? task "Every control"

    - **Chosen in Game options ›**: which technology model the game uses is the option WW2V3 Tech
      Model. A game that uses TripleA's standard technologies says so above the list; changing one
      writes the list into the game.
    - **+ Add technology…** adds one of the game's own, or a new one with its own bonuses.
    - **What it gives**: the bonuses, each a number for a unit type, for example +1 attack for
      fighters; **+ Add bonus…** adds one. A standard technology with nothing set here keeps its
      standard effect, which the text names.
    - **Rockets, war bonds, factories, airborne forces**: the rarer settings. **Rockets reach this
      many territories**, **Rocket attacks from one territory, at most**; **Dice rolled for PUs each
      turn** and **Sides of each die** for war bonds; **More units produced only in territories
      worth at least** and **Repairs cost less, in %** for factories; **May blitz** and **May
      bombard** give units the ability; airborne forces: **Units can drop from a base as airborne
      forces**, **Airborne units reach this many territories**, **Units that can drop**, **Bases
      they start from**, **+ Add anti-air kind…** for the anti-air that shoots at them.
    - **Remove technology** takes it out of the game.

    A Choose list of unit types ends with the game's variables that hold unit types, `$Planes$` say.

    Above the bonuses the header says which nations research it and how many have it at the start.
    Each nation's research chart, the set of technologies it may get, is set per nation in the list
    of nations at the left; "Not researched" leaves a technology out of a nation's chart.
