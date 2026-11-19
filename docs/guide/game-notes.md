# Game notes

[← Guide](README.md){ .back }

What players read about your game: its story, rules and tips, in HTML. The preview shows it as
the game does.

??? task "Write the notes"

    ![The insert bar B, I, H, •, ¶, a (Link…), img (Picture…) and the views Text, Split, Preview above the notes](img/game-notes.png#only-light){ .shot }
    ![The insert bar B, I, H, •, ¶, a (Link…), img (Picture…) and the views Text, Split, Preview above the notes](img/game-notes-dark.png#only-dark){ .shot }

    1. Type the text on the HTML side.
    2. Select words and press **B** for bold or **I** for italic.
    3. Press **H** to make a heading of the line, **•** to make it a list item.
    4. Press **¶** for a line break.

    !!! tip
        The keys are faster: Ctrl+B, Ctrl+I and Shift+Enter.

??? task "Add a link or a picture"

    1. Put the cursor where it should go.
    2. For a link, press **&lt;a&gt;** (its tool tip says Link…) and give the address and the
       words.
    3. For a picture, press **&lt;img&gt;** (Picture…) and choose a picture file.

    The picture is copied into doc/images beside the notes and linked from there, so the map
    carries it.

??? task "See the notes as the game shows them"

    1. Press **Split** for the HTML on the left and the preview on the right.
    2. Press **Preview** for the preview alone.
    3. Press **Text** for the HTML alone, wide.

??? task "Find a word in the notes"

    1. Click into the HTML side.
    2. Press Ctrl+F and type the word.

??? task "Every control"

    The notes are saved in the game's notes file beside the game, for example 270BC.notes.html,
    which the line at the right names.

    - **B** bold (Ctrl+B) and **I** italic (Ctrl+I) wrap the chosen text.
    - **H** makes a heading of the line.
    - **•** makes a list item of the line.
    - **¶** a line break (Shift+Enter).
    - **&lt;a&gt;** (Link…) asks for the address and the words.
    - **&lt;img&gt;** (Picture…) chooses a picture file; it is copied into doc/images beside the notes and
      linked from there, so the map carries it.
    - **Text** shows the HTML alone, wide.
    - **Split** shows the HTML on the left and the preview on the right.
    - **Preview** shows the notes as the game shows them, alone.

    The HTML side is a text editor with colouring, line numbers, folding and find (Ctrl+F); the
    text size and the theme follow the editor. Notes that are not UTF-8 open as TripleA shows
    them and are saved only when changed.
