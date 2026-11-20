# Units

[← Guide](README.md){ .back }

The unit types of the game: what they cost, how they fight and how they move. The list on the
left shows each with its picture and its numbers; 2-3-1 means attack 2, defense 3, movement 1.

??? task "Add a unit type"

    ![The top of a unit: its picture, name, cost, value, attack, defense, movement, hit points and kind](img/units.png#only-light){ .shot }
    ![The top of a unit: its picture, name, cost, value, attack, defense, movement, hit points and kind](img/units-dark.png#only-dark){ .shot }

    1. Press **+ Add unit…** above the list.
    2. Type a name and choose the unit to start from, for example infantry.
    3. Set **Cost**, **Attack**, **Defense**, **Movement** and **Hit points** beside the picture,
       and its **Kind**: Land, Sea or Air.

    !!! tip
        Start from a unit that is close to what you want: every option comes along.

??? task "Give a unit its own picture"

    1. Choose the unit in the list.
    2. Press **Upload picture…** under its name and choose a picture file.
    3. Say whether every nation shows it as it is, or tinted in each nation's color.

    **Use the game's picture** takes your picture back.

    !!! tip
        A picture clearly bigger than the map's units is scaled down on request.

??? task "Set what a unit costs and is worth"

    1. **Cost** is the PUs to buy one. It comes from the purchase list that sells the unit; "–"
       means no rule sells it for PUs.
    2. Other resources a unit costs show under its name, for example "1 PU + 5 Fame". Change
       them in [Production](production.md).
    3. **Value** is the TUV, what the unit counts as for the AI and the battle calculator. Leave
       it empty to take the cost; the grey "its cost" says so.

    !!! tip
        Set a Value for a unit that costs 0 or 1 but is strong. The "?" beside it has a rule of
        thumb.

??? task "Let a unit support others"

    1. Scroll to **Gives support**, right below Combat.
    2. Press **+ Add support**. It starts as +1 attack to one allied infantry.
    3. Press **Edit** on its line to choose whom it supports, when, and by how much.

    A support that helps allies shows green, one that harms enemies red.

??? task "Find an option"

    1. Type into **Search options…**: a word of the option here, or its name in the game's XML.
    2. The sections then show only what matches, whatever is folded.

    Without a search, each section shows its common options and those set. The rest wait behind
    **▸ More … options**; the sections of the other kinds of unit are in **▸ Not for land
    units**, the rarer ones in **▸ More sections**.

    !!! tip
        File › Show all options opens every fold in every tool.

??? task "Every control"

    Every option of the game's unitAttachment is here, in plain words, grouped as a map maker
    thinks of them; the game's name for an option is in the tool tip of its label.

    - **+ Add unit…** makes a unit type: a name, the unit to start from as a copy, and maybe a
      picture. **▲ ▼** before it move the chosen unit in the game's list, which is the order TripleA
      lists and draws the units in; **⇅** orders the whole list in one step: A to Z, by cost or by
      kind as TripleA's own buy tabs group units. Undo takes an order back.
    - **Upload picture…** gives the unit its own picture, for every nation or tinted per nation;
      **Use the game's picture** takes it back. The list shows each unit's picture as the first
      nation shows it.
    - **Cost**: PUs to buy one, from the purchase list that sells it; "–" when no rule sells it for PUs.
      A unit that costs other resources too shows them all in the list and under its name, "1 PU +
      5 Fame"; they are changed in Production. A unit that uses up other units when placed says
      "· uses 2 Material" there; that is set under **Uses up units**.
    - **Value** (TUV): what the unit is worth to the AI and the battle calculator. Left empty,
      shown as a grey "its cost", TripleA takes what the unit costs. Set it for a unit that costs 0
      or 1 but is strong; from a cost of 2 it is usually left empty. The "?" has a rule of thumb:
      attack + defense + movement for a unit with 1 hit point.
    - **Attack**, **Defense**, **Movement**: the numbers of the list's line. **Attack** hits on a
      die roll of this or less, **Defense** when defending, **Movement** territories per turn.
    - **Hit points**: hits it takes to destroy, for example 2 for a battleship.
    - **Kind**: Land, Sea or Air. It decides which sections apply; a unit that both sails and flies
      sets both in the Sea and Air sections.
    - **Draw it on the map**: off, the game draws it nowhere and leaves it out of its unit
      statistics, for a unit that only marks something.
    - **Search options** finds an option by its words here or its name in the game's XML; the
      sections then show only what matches.
    - **More … options** folds the rarer options of a section; Show all options in the File menu
      opens them all.
    - Lists of unit types, nations or territories show as a sentence with **Choose…**, which ticks
      them in a dialog.

    Each section shows its common options and those set; the rest wait behind its More row. A section
    that is not for the unit's kind, for example Sea for a land unit, is folded into "Not for land
    units" with the others; Production and infrastructure and Special, which are for no kind but
    rarer, wait in "More sections". The sections come in this order: what the unit does in a battle (Combat,
    Gives support, Air and anti-air, Bombing, Sea), how it moves (Movement, Transport and carrier),
    then the rest (Miscellaneous, Uses up units, Production and infrastructure, Special).

    - **Combat**: Dice when attacking, Dice when defending, Fires first: its hits take no shot back,
      Artillery: supports infantry, Gets the support of artillery; more: Takes its best die instead of
      rolling each, Can leave a battle before it fires, Units it supports, Bonus when landing from sea,
      Cannot hit, Cannot be hit by, Dies after it attacks, Dies after it defends, Dies when it hits.


    - **Gives support**: the supports the unit gives, like artillery, each a sentence such as "+1
      attack to 1 allied infantry when attacking", green for a help to allies, red for a harm to
      enemies. **Edit** opens it: Supports (unit types), When (attacking, defending), Whose units
      (allied, enemy), Bonus, To (strength, rolls), Units one supports, Kind of bonus, Nations.
      × removes it; **+ Add support** adds one that starts as +1 attack to one allied infantry. A
      support written once for every unit of a variable shows read-only with a link to Attachments.
    - **Movement**: Can blitz through empty territories; more: Can move through enemy units, Enemies
      can move through it, Moves only after combat, Kamikaze.
    - **Transport and carrier**: Transport capacity, Transport cost, Carrier capacity, Carrier cost;
      more: Fights as well as transports, Carries paratroopers, Can be carried by planes, Carries
      land units on land, Can be carried on land.


    - **Miscellaneous → Stacking limits**: at most so many of this unit type in a territory **When
      placing**, **When moving in** and **When attacking**, each **counting** its owner's, its
      owner's and allies', or everyone's. Blank is no limit. Below, the nations' limits that count
      this unit, from Nations, each once with the nations it holds for.
    - **Uses up units**: the units that placing one takes away from the territory, for example a
      battleship made from a hull or 2 Material. A row per unit type with how many and which, × to
      remove it; **+ Add a unit it uses up** adds a row with the first unit type not used yet, to
      choose another in. In the game's XML this is consumesUnits, kept as the map wrote it.
    - **Not for land units**: the sections of the other kinds, folded, with their count.
    - **Air and anti-air**: Flies, Can intercept bombers, Needs an air base to intercept, Can escort
      bombers, Fights in air battles, Air battle attack and defense, Can scramble to sea battles,
      Air base, Scramble distance, Planes it lets scramble and intercept, Fires at planes in
      battles, Fires at planes bombing it, Fires at planes flying over, Anti-air defense and attack,
      Anti-air shots and rounds, Kind of anti-air, Planes it fires at, May fire more shots than
      there are planes, Its anti-air can be damaged, Rocket: fires at factories. Its "?" explains
      the order of fire in a battle. **Bombing**: Strategic bomber, Bombing die, Bombing bonus,
      Bombs. **Sea**: Sails, Destroyer: cancels the first strike of subs, Can bombard a shore,
      Bombard strength, Blockade.


    "More sections" holds these two, unless the unit has one of their options set:

    - **Production and infrastructure**: Infrastructure, eg a factory; Produces units; Units it
      produces; Resources it makes each turn, a count and a resource, a minus count for upkeep such
      as −1 PUs; Can be damaged by bombing; Most damage; Damage until it stops working; Is destroyed
      at its most damage; Construction: placed like a factory; Kind of construction; Constructions
      per turn and per territory; Most a nation may have; Placed only in territories worth; Never
      placed in; Placed only in.
    - **Special**: Damage when captured, Captured by entering, Given with its territory to.
    - **More options**: the options the sections do not show, as the game file has them, each with
      its name, a field and ×; a repeated or counted option is here too. **+ Add option…** adds any
      option TripleA's unitAttachment takes, by its name, with a hint of its format. A Choose list
      of unit types, nations or territories ends with the game's variables of that kind,
      `$All-Fighters$` say; TripleA puts their elements in when it reads the file.
    - **Remove unit** takes the unit type out of the game, its rules and starting units with it.

    A unit type whose options the game file writes once for every unit of a variable (`foreach`)
    shows them read-only, under a line with a link to **Attachments**: the loop is changed there,
    for all its unit types at once. Its cost stays a field. **Change for this unit only** below
    that line gives the unit type an attachment of its own with all the values it has, written
    after the loop: it is then changed here like any unit and no longer follows the loop, whose
    other unit types stay as they are. A toast says so, with **Undo**. **Remove unit** is refused
    while the loop's variable still names the unit type.
