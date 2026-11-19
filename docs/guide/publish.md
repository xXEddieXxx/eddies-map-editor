# Publish

[← Guide](README.md){ .back }

What your map needs before players can download it in TripleA: its name and version in map.yml,
the description and picture the list of maps shows, and the steps to upload it.

??? task "Get the map ready to upload"

    ![The checklist Before you upload with "n of m are ready" and a button on each open line](img/publish.png#only-light){ .shot }
    ![The checklist Before you upload with "n of m are ready" and a button on each open line](img/publish-dark.png#only-dark){ .shot }

    1. Look at **Before you upload**: it says "n of m are ready".
    2. Press the button on each line with ○, for example **Check** or **Set version 1**.
    3. Go on until every line is ready.

    !!! tip
        Press **Check** last: a change to the map makes its line open again.

??? task "Name the map and set its version"

    ![The fields Map name, Version with Raise, and Game version](img/publish-version.png#only-light){ .shot }
    ![The fields Map name, Version with Raise, and Game version](img/publish-version-dark.png#only-dark){ .shot }

    1. Type the **Map name**, at most 70 characters. It is the name in TripleA's list of maps.
    2. Set **Version** to a whole number.
    3. Press **Raise** before each update you upload: it makes the version one higher.

    !!! tip
        The map name is not the game's name: Rename map changes the game's.

??? task "Write the description players read"

    1. Press **Write it** on the checklist's description line. The cursor goes to the
       **Description** box.
    2. Type what the list of maps should show about the map.
    3. Watch the count of characters: 3000 at most. The preview shows it as TripleA does.

??? task "Upload the map"

    1. Go to **Upload**: the steps to publish, each with a link.
    2. Sign up at GitHub and create a repository named like the map's folder.
    3. Install Git and upload the folder with Git GUI as the guide shows.
    4. Ask for the map to be added with a Map Registration issue.

    Later updates go into the map's repository at triplea-maps, after which players are told
    their map is out of date.

??? task "Every control"

    - **Map name**: the name in TripleA's list of maps to download (map.yml map_name), at most
      70 characters. The games in the map have names of their own; Rename map changes the
      game's.
    - **Version**: a whole number in map.yml, raised with each update you upload; **Raise**
      makes it one higher. **Game version** below it is the game file's own (info version), free
      text as 0.021, which TripleA's upload guide recommends changing with the game notes though
      the game does not read it; Raise raises its last number too. A line says when the game
      file names a TripleA version it needs, which TripleA no longer reads.
    - **Before you upload**: the checklist, "n of m are ready": checked since the last change, a
      name, a version, the description, the preview picture, the folder's name as the upload
      guide asks it, no backup files in the folder. Each ○ has a button that does it, for
      example **Set version 1** or **Check**. **Write it** on the description line goes to the
      Description box below, which it marks for a moment with the cursor in it; **Shorten it**
      when the file is too long.
    - **Description**: what the list of maps shows about the map (description.html), with a
      preview as TripleA shows it and the count of characters, 3000 at most.
    - **Upload**: the steps to publish, each with a link. Sign up at GitHub, create a repository
      named like the map's folder, install Git, upload the folder with Git GUI as the guide
      shows, ask for the map to be added with a Map Registration issue, and later updates go
      into the map's repository at triplea-maps, after which players are told their map is out
      of date.

    **Export as zip…** in the File menu packs the folder for an upload by hand, without the
    `.bak` files an older version of the editor left there.
