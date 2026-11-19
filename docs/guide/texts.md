# Texts

[← Guide](README.md){ .back }

The texts the game shows by key: objectives, the messages of triggers, unit tool tips and the
texts of actions. This tool shows them all at once, a tab per file.

??? task "Change a text"

    ![The tabs of Texts with their counts, the Messages tab open: its list of keys and a chosen message with its file and key, Text and Preview](img/texts.png#only-light){ .shot }
    ![The tabs of Texts with their counts, the Messages tab open: its list of keys and a chosen message with its file and key, Text and Preview](img/texts-dark.png#only-dark){ .shot }

    1. Choose the tab of its file, for example the objectives.
    2. Choose the text in the list.
    3. Type into **Text**. HTML is allowed where the game shows HTML.
    4. Look at **Preview**: it shows the text as the game does.

    !!! tip
        [Objectives](objectives.md), [Triggers](triggers.md) and [Actions](actions.md) edit the
        same texts where they belong.

??? task "Add a text"

    1. Choose the tab of the file the text belongs to.
    2. Press **+ Add text…**.
    3. Choose or type the **Key** and press **Add**.
    4. Type the **Text**.

??? task "Show another game's objective texts in this game"

    1. Open the objectives' tab. "Not shown in this game" says the texts are another game's.
    2. Press **Use them for this game**.

??? task "Remove a text"

    1. Choose the text in the list.
    2. Press **Remove text…**.

??? task "Every control"

    Each text is saved in its file beside the game: objectives.properties,
    notifications.properties, tooltips.properties and politicstext.properties. A tab per file,
    each with its count.

    - **+ Add text…** adds a text to the tab's file with a new key.

    Each text has:

    - **Key**: how the game finds the text. For an objective the key names the game, the nation
      and the condition; for a trigger's notification its name; for a tool tip the unit type;
      for an action its text key with .BUTTON, .DESCRIPTION and so on.
    - **Text**: what the players read; HTML is allowed where the game shows HTML.
    - **Preview** as the game shows it.
    - **In this group (n)**: the texts that share a key's start, for example one action's texts.
    - **Use them for this game** appears when the objective texts carry another game's name:
      it renames them to this game's, as one step that Undo takes back.
    - **Remove text…** takes it out of the file.

    The [Objectives](objectives.md), [Triggers](triggers.md) and [Actions](actions.md) tools
    edit the same texts where they belong; this tool shows them all at once, also texts no tool
    names.
