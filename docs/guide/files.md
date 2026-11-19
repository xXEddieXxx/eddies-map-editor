# Files

[← Guide](README.md){ .back }

The map's files as on disk. Open one to change it; the game's files are checked as you type.

??? task "See what a map folder holds"

    ![The list What's in a map folder: files with ✓, ! and ○, a line for each, Games… and Edit in … at the right](img/files.png#only-light){ .shot }
    ![The list What's in a map folder: files with ✓, ! and ○, a line for each, Games… and Edit in … at the right](img/files-dark.png#only-dark){ .shot }

    1. Open Files without a file chosen: **What's in a map folder** shows on the right.
    2. Read the marks: ✓ there, ! missing and needed, ○ missing and optional.
    3. Click a file's name to open it.

    !!! tip
        **Edit in …** at the right of a file opens the tool that edits it, which is easier than
        the file.

??? task "Change a file by hand"

    1. Click the file's name to open it in the editor on the right.
    2. Type the change. In a game file or a place file, a line that TripleA would not read is
       marked and explained.
    3. Press **Save file**.

    Save file keeps the first version in the editor's Backups folder and reads the map again
    from its files. It asks first when the map has unsaved changes.

??? task "Get a file back as it was"

    1. Save a file for the first time: the toast has **Show**.
    2. Press **Show**. The tree lists the map's Backups folder, read only.
    3. Open the first version there to read or copy from it.

??? task "Rename a game of the map"

    1. Find map.yml in **What's in a map folder**.
    2. Press **Games…** beside it: it lists the map's games.
    3. Rename the game. The map name stays, since the games find the map by it.

??? task "Every control"

    - **⋯ More**: **Refresh** reads the folders again, **Show in Explorer** (**Show in Finder**
      on a Mac) opens the map's folder, and **Add a map to copy from…** shows another map's
      folder in the tree, to copy files from.
    - **Search files** above the tree finds a file by its name.
    - **What's in a map folder** lists every file a map has or could have, in its place in the
      folder, with ✓ there, ! missing and needed, ○ missing and optional, and a line that says
      what it is for. Click a file's name to open it in the editor on the right.
    - **Games…** beside map.yml lists the map's games, each to rename; the map name stays, since
      the games find the map by it.
    - **Edit in …** at the right of a file opens the tool that edits it, for example Edit in
      Territories for polygons.txt, Edit in Nations for flags, Make in Map look for reliefTiles.

    The editor on the right is a text editor with colouring, line numbers, folding, find
    (Ctrl+F) and a size and theme that follow the editor. A game file or a place file is checked
    as you type: a line that TripleA would not read is marked and explained. **Save file**
    writes the file, keeps the first version in the editor's Backups folder, and reads the map
    again from its files, asking first when the map has unsaved changes. Pictures open in a
    viewer. The toast after the first save of a file has **Show**, which lists the map's Backups
    folder in the tree, read only.
