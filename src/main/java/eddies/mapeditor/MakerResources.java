package eddies.mapeditor;

import java.awt.Component;
import java.util.List;
import javax.swing.JPopupMenu;
import lombok.experimental.UtilityClass;
import org.triplea.awt.OpenFileUtility;

/**
 * Where to learn more about making maps: the map-making guides of the TripleA repository, the
 * published maps to learn from, the maps list and the map makers' forum, opened in the browser.
 */
@UtilityClass
class MakerResources {
  private static final String DOCS =
      "https://github.com/triplea-game/triplea/blob/main/docs/map-making/";

  /** A link and what it is about. */
  private record Link(String title, String url) {}

  /** The links, guides first. */
  private static final List<Link> LINKS =
      List.of(
          new Link(
              "Guide: the game XML, section by section",
              DOCS + "tutorial/creating-custom-map-xml.md"),
          new Link(
              "Guide: map pictures, tiles and the other map files",
              DOCS + "tutorial/map-and-map-skin-making.md"),
          new Link("Reference: map.yml", DOCS + "reference/map-yml-file.md"),
          new Link("Reference: game notes", DOCS + "reference/game-notes.md"),
          new Link("Reference: new map features", DOCS + "reference/map-features-change-log.md"),
          new Link("How to: update an existing map", DOCS + "how-to/updating-existing-maps.md"),
          new Link("How to: publish a map", DOCS + "how-to/uploading-a-map-to-triplea.md"),
          new Link("All published maps, to learn from", "https://github.com/triplea-maps"),
          new Link("The maps list", "https://triplea-game.org/maps-list/maps/"),
          new Link(
              "The map makers' forum", "https://forums.triplea-game.org/category/5/maps-mods"));

  /**
   * The menu of the links, guides first; one more entry adds an installed map to the file browser
   * to copy from.
   */
  static JPopupMenu menu(final Component parent, final Runnable copyFromMap) {
    final JPopupMenu menu = new JPopupMenu();
    for (final Link link : LINKS) {
      menu.add(link.title()).addActionListener(e -> OpenFileUtility.openUrl(parent, link.url()));
    }
    menu.addSeparator();
    menu.add("Copy from another map...").addActionListener(e -> copyFromMap.run());
    return menu;
  }
}
