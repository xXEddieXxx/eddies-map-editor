package eddies.mapeditor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import eddies.mapeditor.MakerIcon.Glyph;

/** What the map of the map maker shows in its colors and badges, chosen below the map. */
@AllArgsConstructor
enum MapMode {
  GAME(
      "Game",
      "The map as it plays: its picture, the owners' colors and the units",
      Glyph.DIE),
  OWNERS("Owners", "Each territory in its owner's color", Glyph.OWNERS),
  POLITICAL("Political", "Each territory in its owner's alliance color", Glyph.POLITICAL),
  VALUES(
      "Values",
      "Each territory's PUs per turn, capital and victory city, at its center",
      Glyph.VALUES),
  UNITS("Units", "The units each territory starts with", Glyph.UNITS),
  CONNECTIONS(
      "Connections", "Which territories are neighbours, as the Connections tool", Glyph.CONNECTIONS);

  private final String title;
  @Getter private final String description;
  @Getter private final Glyph glyph;

  @Override
  public String toString() {
    return title;
  }
}
