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
- **Resize map at any size** (#1). A saved map is resized tile by tile from its tiles on disk,
  never holding the whole picture, so 1941 Global Command Decision (16816 × 8085) can be made
  bigger. The new tiles show at once, undo is free, and a save moves them into the map, keeping the
  old ones aside so an undo after the save still has them. A picture not saved yet is still held
  whole, up to 50 million pixels. A bar under the map's toolbar shows how far a resize is.
- **A loading bar** while a map opens, which now reads in the background, so a big map no longer
  freezes the window.
- **Save the map's picture or relief as one PNG at any size** (#13). The map picture menu writes
  the tiles into one file row by row, so a map too big to hold still comes out whole, for a paint
  program. New: the relief the same way.

### Tried, taken out again

- Enlarging a picture **bicubic**, and copying a flat picture pixel by pixel at a whole step (#7).
  Neither looked good on Settlers: Fallen Empire: bicubic fringed the lines blue and yellow. The
  resize is as in 0.1.91 again. Enlarging by an odd factor cannot keep a painted relief sharp;
  enlarge it in an image editor and import it.

### Known, not fixed

- A unit's icon sometimes disappears (#11). No repro yet.
- A renamed nation does not get its unit pictures copied under the new name (#12).

### For developers

- Nine tests fail on a machine where TripleA's start checks return nothing, or where the
  downloaded maps hold a stray copy; they failed before these changes too.
