# Actions

[← Guide](README.md){ .back }

The buttons a nation may press in its politics step, for example to declare war, and its user
actions, which fire triggers. Each has its texts, the conditions it needs, a cost and what it
does.

??? task "Add an action"

    ![The top of an action: whose it is, Button, Description and the messages under Button and texts](img/actions.png#only-light){ .shot }
    ![The top of an action: whose it is, Button, Description and the messages under Button and texts](img/actions-dark.png#only-dark){ .shot }

    1. Press **+ Add action…** above the list.
    2. Choose its **Kind**: **Political: changes relationships** or **User action: fires
       triggers**.
    3. Choose the **Nation** and type the text of its **Button**.
    4. For a political action, choose under **Changes its relationship with** the other nation,
       and under **to** the relationship.
    5. Press **Add action**.

    !!! tip
        Add the nations first: an action belongs to a nation.

??? task "Write what players read"

    1. Choose the action in the list.
    2. Type the **Button** text and the **Description**, which is the button's tool tip.
    3. Type the message to the nation when it succeeds, for example **Message to Germans when
       it succeeds**, and the **Message to the others when it succeeds**.
    4. Open **More texts** for the messages when it fails and the **Question to those who must
       accept**.

    !!! tip
        Actions with the same **Text key** share their texts: a change shows in all of them.

??? task "Say when an action can be used"

    1. Choose the action and go to **When it can be used**.
    2. Press **+ Add condition…** and choose one of the map's conditions.
    3. Press × on a condition to remove it.

??? task "Set what an action does"

    ![The section What it does with a relationship change and + Add change, and Cost and chance below it](img/actions-does.png#only-light){ .shot }
    ![The section What it does with a relationship change and + Add change, and Cost and chance below it](img/actions-does-dark.png#only-dark){ .shot }

    1. Choose the action and go to **What it does**.
    2. For a political action, press **+ Add change** and say how two nations stand when it
       succeeds.
    3. A user action fires triggers instead: they are listed here.

??? task "Set the cost, the chance and who must agree"

    1. Under **Cost and chance**, press **+ Add cost** and say what an attempt costs.
    2. Set **Succeeds on a roll of** n **of** m. All of one die means always.
    3. Set **Attempts per turn**: how often the nation may try.
    4. Under **Must be accepted by**, choose the nations that must agree.

??? task "Every control"

    **Texts and conditions**

    - **+ Add action…** makes a political or a user action for a nation. The list on the left
      names the actions; the header of a chosen one says whose it is and its name in the game
      file.
    - **Button and texts**: **Button** is the text on the button players press; **Description**
      its tool tip; **Message to the nation when it succeeds**, **Message to the others when it
      succeeds**; under More texts: the messages when it fails, and the **Question to those who
      must accept**.
    - **Text key**: the texts are kept in politicstext.properties by this key, for example
      Axis_War_Neutral_True.BUTTON. Actions may share a key and so their texts.
    - **When it can be used**: the conditions the action needs, each a sentence; × removes one,
      **+ Add condition…** adds one of the map's.

    **What it does and what it costs**

    - **What it does**: for a political action, the relationship changes, each "A and B → type";
      × removes one; **+ Add change** adds how two nations stand when it succeeds. A user action
      fires triggers instead, listed here.
    - **Cost and chance**: **Costs**, what an attempt costs in each resource, **+ Add cost**.
    - **Succeeds on a roll of** n **of** m: the chance; all of one die means always.
    - **Attempts per turn**: how often the nation may try.
    - **Must be accepted by**: nations that must agree, for example the other side of an
      alliance; **Nobody** by default.
    - **Remove action** takes it out of the game.
