# Changelog

What changed for map makers, release by release. The GitHub release of each version carries the
downloads; this file carries the words.

## Unreleased

From the feedback of map makers on 0.1.91, each change under its GitHub issue.

### Fixed

- **Units drawn as name badges** (#2). Pictures a map keeps in its `units` folder itself, shared
  by every nation as 1941 Global Command Decision keeps its factories and bunkers, are found. The
  nation's own picture still wins. Not yet confirmed on that map by the map maker who reported it.
- **Gives support said None, limits were blank** (#9, #10). A map that writes one attachment for
  each unit or nation of a variable (`foreach="$All-Fighters$"`) was read as if it had none. Such
  supports and stacking limits now read as TripleA reads them. They show read-only, with a link
  to Attachments, where the one written is changed. Units also lists the nations' limits that
  count the unit. Checked against 1941 Global Command Decision.

### Changed

- **Value (tuv)** sits in the top row beside the cost, blank for "its cost" (#3).
- **Resources it makes each turn** (`createsResourcesList`) is a field under Production and
  infrastructure: a count and a resource, a minus count for upkeep (#3). A unit with two such
  options keeps them under More options.
- The **Stacking limits** section is **Miscellaneous**, with Stacking limits as its heading (#4).
- **Rename map** in the File menu and beside the title on Home (#5). It renames the game and the
  entries in map.yml that carried the old name. The folder keeps its name, which the game finds
  the map by. Publish keeps its Map name field.
- The message after the **save of a resized map** ends with the map's new size (#6).
- The **list of unit types** shows each unit's picture before its name, at most 32 px (#8).

### Tried, not confirmed

- A picture made bigger is drawn **bicubic** instead of bilinear (#7). This should sharpen a
  painted picture. A flat-coloured picture is still snapped to its own colours afterwards so border
  lines stay one colour, which at 120 % makes thin lines one and two pixels wide by turns. Not yet
  compared on Settlers: Fallen Empire, the map it was reported on. The issue stays open.

### Known, not fixed

- A unit's icon sometimes disappears (#11). No repro yet.
- A renamed nation does not get its unit pictures copied under the new name (#12).

### For developers

- Nine tests fail on a machine where TripleA's start checks return nothing, or where the
  downloaded maps hold a stray copy; they failed before these changes too.
