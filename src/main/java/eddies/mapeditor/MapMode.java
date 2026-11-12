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
      Glyph.GAME_VIEW),
  OWNERS("Owners", "Each territory in its owner's color", Glyph.OWNERS),
  POLITICAL("Political", "Each territory in its owner's alliance color", Glyph.POLITICAL),
  VALUES("Values", "What each territory gives per turn, capitals and victory cities", Glyph.VALUES),
  UNITS("Units", "The units each territory starts with", Glyph.UNITS),
  CONNECTIONS("Neighbours", "Which territories are neighbours", Glyph.CONNECTIONS);

  private final String title;
  @Getter private final String description;
  @Getter private final Glyph glyph;

  @Override
  public String toString() {
    return title;
  }
}
