# Terrain effects

[← Guide](README.md)

Effects such as forest or mountain: how units fight and move in the territories that have them.

![Terrain effects](img/terrain-effects.png)

1. **Add effect…** makes an effect: give it a name. A map without effects shows this alone.

An effect, once chosen in the list on the left, has:

- **In the game** under its title says how the player sees the effect, which of the game's three
  ways this map uses: without a picture, the effect's name in brackets after the territory's name
  on the status bar, "Karelia (forest)"; with a picture `territoryEffects/forest.png`, a small icon
  there instead, the name as its tool tip; and on the map too, where territory_effects.txt places
  it (Places › Terrain effects) and map.properties has `map.useTerritoryEffectMarkers=true`, as
  `forest_large.png` when there is one. The game shows an effect's values nowhere; a picture that
  carries text, as 1941 Global Command Decision draws them, is how a map shows them, else the
  game notes say.
- **Name** as the game file names it; a marker picture of that name in the map's
  `territoryEffects` folder is drawn on the territories that have the effect. "No picture in the
  map's folder" says there is none yet; "Save the map first: the picture goes into its folder"
  while the map is new. **Choose…** copies a picture into the folder as the effect's.
- **In battle and on the move**: what the effect does to units, each line a unit type with its
  change, for example attack −1 or movement cost 2. **Choose…** picks the unit types; × takes a
  line away.
- **Who may enter**: unit types the effect keeps out, for example tanks out of a swamp. Every
  Choose list of unit types, nations or territories ends with the game's **variables** of that
  kind, `$Planes$` say, under a heading of their own; the tool tip names their elements. TripleA
  puts the elements in when it reads the file.
- **Show on map** switches to Territories with the effect as the Paint brush, where a click gives
  a territory the effect. Paint → Terrain effects in Territories does the same.
- **Remove effect** takes it out of the game and off every territory.
