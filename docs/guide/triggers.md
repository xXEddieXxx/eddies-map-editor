# Triggers

[← Guide](README.md){ .back }

The game's triggers in plain words: what each does, when and how often. Change one or make a new
one step by step in the trigger assistant.

??? task "Add a trigger"

    ![The trigger list with Group the triggers by and + Add trigger… above it](img/triggers.png#only-light){ .shot }
    ![The trigger list with Group the triggers by and + Add trigger… above it](img/triggers-dark.png#only-dark){ .shot }

    1. Press **+ Add trigger…** above the list. The assistant opens.
    2. Choose under **Start from** the example closest to what you want, or **Empty trigger**.
    3. Go through the steps: **If**, **Then**, **When and how often**.
    4. At **Review**, fix what the assistant lists as missing.
    5. Press **Add trigger**.

    !!! tip
        An example fills the steps with a working trigger, so you only change what differs.

??? task "Change a trigger"

    1. Choose the trigger in the list. It reads as sentences: what it does, when, how often and
       the conditions it needs.
    2. Press **Change...** below them.
    3. Change the steps in the assistant and press **Save trigger**.

    !!! tip
        Options the assistant does not change stay as they are; [Attachments](attachments.md)
        shows them.

??? task "Say when a trigger fires"

    ![The assistant's When step: At each effect's usual moment, Before or after the step, Every round, In 1 round, In several rounds, Only by chance](img/triggers-when.png#only-light){ .shot }
    ![The assistant's When step: At each effect's usual moment, Before or after the step, Every round, In 1 round, In several rounds, Only by chance](img/triggers-when-dark.png#only-dark){ .shot }

    1. Open the trigger in the assistant and go to **When and how often**.
    2. Choose **At each effect's usual moment**, or **Before or after the step** of a nation, for
       example before Germans' purchase.
    3. Choose **Every round**, **In 1 round** or **In several rounds**.
    4. Tick **Only by chance** to add a dice roll.

??? task "Give a trigger a condition"

    1. Open the trigger in the assistant and go to **If**.
    2. Press **Add a check** and choose what must be true: a nation holds territories, has units,
       the round number, a game option, and so on.
    3. To use a condition the map has already, open **More options** there and press **Use a
       condition of the map...**.
    4. With several conditions, **Fire when** in the same fold says how many must hold.

??? task "Find, copy or remove a trigger"

    1. Choose under **Group the triggers by**: by nation, by what they do, or by when they fire.
       The headings fold.
    2. Choose the trigger in the list.
    3. Press **Duplicate** for a copy that uses the same conditions, or **Remove trigger** to take
       it out of the game.

    !!! tip
        While the game option Use Triggers is off, no trigger fires; a line under the trigger
        says so.

??? task "Every control"

    **The list and a chosen trigger**

    - **Group the triggers by** lists them under headings that fold: by nation, by what they do,
      or by when they fire. The list counts the triggers and shows each as one line.
    - **+ Add trigger…** opens the assistant with a new trigger.

    A trigger, once chosen, reads as sentences: what it does, when it fires, how often, and the
    conditions it needs, each condition a sentence of its own. Below them:

    - **Change...** opens the trigger in the assistant.
    - **Show** chooses a trigger the sentences name, for example one this trigger activates.
    - **Duplicate** makes a copy that uses the same conditions.
    - **Remove trigger** takes it out of the game.
    - "Use Triggers is off: no trigger fires. Set in Game options" shows while that option is
      off.

    **The trigger assistant**

    The assistant builds a trigger in five steps: Start, If, Then, When and how often, Review.

    - **Start from** an example, or **Empty trigger**. The examples are grouped: Money & units
      (Give PUs when a nation holds territories, Place units when a nation holds territories),
      Land & politics (Change relationships, declare war, in a round), Production & tech,
      Messages & victory. An example fills the steps with a working trigger to change.
    - **Then**: the effects, one or more. **Add effect** adds something the trigger
      does: give PUs or resources, place or change units, change a relationship, activate
      another trigger, show a message, give a technology, change a game option, end the game,
      and more. Each effect has its own fields, for example the nation, the territories and the
      units.
    - **When and how often**: **At each effect's usual moment**, or **Before or after the step** of a nation,
      for example before Germans' purchase. **Every round**, **In 1 round**, or **In several
      rounds**; **In how many rounds the trigger may fire; 0 is used up**. **Only by chance**
      adds a dice roll.
    - **If**: what must be true. **Add a check** adds something a new condition checks: a
      nation holds territories, has units, the round number, a game option, and so on. Under
      **More options**, **New condition** and **Use a condition of the map...** add another
      condition, and **Fire when** says how many conditions must hold for the trigger to fire,
      or the fewest. **Fire when the conditions are not met** turns the sense around.
    - **Review**: what the trigger does in plain words, and what is still missing.
    - **For nations (optional)**: the nations the trigger belongs to. Here and in every list of
      territories, nations or units, Choose ends with the game's variables of that kind,
      `$Axis$` say, under Variables.
    - **Check and save**: the assistant lists what is missing, for example "Condition c: Owns
      territories: Territories is missing.", or "No problems.", then **Add trigger** writes it
      (**Save trigger** for one you change). Options the assistant does not change stay as they
      are; Attachments shows them.
