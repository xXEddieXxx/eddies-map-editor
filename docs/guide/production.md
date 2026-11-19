# Production

[← Guide](README.md){ .back }

Each nation buys from one purchase list: rules like "1 infantry for 3 PUs". Pick a list, choose
who uses it, and tick what it sells. Four tabs: **Buy**, **Repair**, **Resources** and **Buy
window tabs**.

??? task "Give a nation a purchase list"

    ![The top of a purchase list on the Buy tab: Used by with Choose nations…, Copy from… and Show with Sold here and All rules](img/production.png#only-light){ .shot }
    ![The top of a purchase list on the Buy tab: Used by with Choose nations…, Copy from… and Show with Sold here and All rules](img/production-dark.png#only-dark){ .shot }

    1. On the tab **Buy**, press **+ Add list…** and type a name. Or choose a list on the left.
    2. Press **Choose nations…** and tick the nations that buy from this list.
    3. Press **Copy from…** to take the rules of another list, or tick the rules yourself, see
       the next task.

    !!! tip
        A nation uses one list, and a nation without one cannot buy.

??? task "Say what a list sells"

    ![The rules of a purchase list with All rules shown: a tick, the unit's picture and the price fields per row, and + Add rule…](img/production-rules.png#only-light){ .wide }
    ![The rules of a purchase list with All rules shown: a tick, the unit's picture and the price fields per row, and + Add rule…](img/production-rules-dark.png#only-dark){ .wide }

    1. Choose the list on the left.
    2. Under **Show**, choose **All rules (n)**: every rule of the game.
    3. Tick each rule this list sells.
    4. For a unit or price no rule has yet, press **+ Add rule…**: the unit or resource, how
       many, and what it costs in each resource.

??? task "Order the game's buy window"

    1. Choose the list and show **Sold here (n)**.
    2. Choose a rule and move it with **▲ ▼**.
    3. Or press **⇅** to order the whole list in one step: A to Z, by cost or by kind.

    !!! tip
        With several lists, **⇅** offers this list or every list; Undo takes an order back.

??? task "Add a resource"

    ![The Resources tab: a column per resource under its icon, each nation's amount At the start, and Add resource…](img/production-resources.png#only-light){ .shot }
    ![The Resources tab: a column per resource under its icon, each nation's amount At the start, and Add resource…](img/production-resources-dark.png#only-dark){ .shot }

    1. Open the tab **Resources**.
    2. Press **Add resource…** and type its name, for example Ore.
    3. Give it an icon with **Change icon…**.
    4. Type what each nation has of it **At the start**.

??? task "Make your own tabs for the buy window"

    1. Open the tab **Buy window tabs**.
    2. Choose **Own tabs** instead of **TripleA's own tabs**.
    3. Give each tab a name and the unit types in it.

    !!! tip
        Put everything a nation's list sells into a tab, else TripleA cannot open its buy window;
        the editor lists what is missing as problems.

??? task "Every control"

    **Buy**

    - **+ Add list…** adds a purchase list; a nation without one cannot buy. The list on the left
      names the lists; a click chooses one. The header shows its id in the game file.
    - **Used by … Choose nations…**: the nations that buy from this list. A nation uses one list.
    - **Copy from…** takes the rules of another list in place of this one's.
    - **Show**: **Sold here (n)** lists the rules this list sells; **All rules (n)** every rule of
      the game, with a tick for each that this list sells. A rule is a unit or resource for a price;
      tick it to sell it here. A unit's row shows its picture, as the nation that buys from the list
      shows it.
    - **+ Add rule…** makes a rule: the unit or resource, how many, and what it costs in each
      resource. A rule with a count sells several at once, for example 2 infantry for 7 PUs. Each
      rule row has **Rename**, **Remove rule** and, in the row, the price fields. **▲ ▼** move the
      chosen rule, which is the order of the game's buy window, with Sold here shown; **⇅** orders
      the whole list in one step: A to Z, by cost or by kind as TripleA's own buy tabs group units
      (land, air, sea, construction, upgrades, resources), and with several lists, this list or every
      list. Undo takes an order back.
    - **Remove list** takes the list away; its nations then have none.

    **Repair**

    Repair lists work like purchase lists: which damaged units a nation may repair and what one
    point of repair costs. **Choose nations…** says who uses the list.

    **Resources**

    The game's resources, PUs first; **Add resource…** adds another, for example Ore, with its icon
    (**Change icon…**) and what each nation has of it **At the start**, a column per resource under
    its icon. Territories give resources per turn in Territories → More options, units in Units →
    Production and infrastructure.

    **Buy window tabs**

    The tabs of the game's purchase window. **TripleA's own tabs** sort units by kind; **Own tabs**
    lets the map define the tabs: each a name and the unit types in it.
