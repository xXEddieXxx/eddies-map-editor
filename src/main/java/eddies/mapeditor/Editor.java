package eddies.mapeditor;

import java.util.Optional;
import javax.swing.JComponent;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * One tool of the map maker, chosen by its icon in the strip on the left: a title, what it does
 * (its "?"), its panel on the right with its lists and settings, and what the middle shows: its
 * preview, the map for a tool that works on it, else nothing and its panel takes the whole room.
 */
interface Editor {
  String title();

  /** What the tool does, shown by its "?" and as its icon's tool tip. */
  String hint();

  /** The tool's icon in the strip on the left and above its panel. */
  Glyph glyph();

  /** The right side: the tool's lists and settings. */
  JComponent page();

  /** The middle for this tool when it is not the map, eg the notes as the game shows them. */
  default Optional<JComponent> preview() {
    return Optional.empty();
  }

  /** Whether the tool works on the map, so the map shows beside its panel. */
  default boolean usesMap() {
    return false;
  }

  /** Called each time the editor is shown, eg to take the shared map for its clicks. */
  default void showing() {}

  /** Shows the territory, for an editor that has territories. */
  default void show(final String territory) {}

  /** Makes an edit still being typed a step, eg before a save. */
  default void commit() {}
}
