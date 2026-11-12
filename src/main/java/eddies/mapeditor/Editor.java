package eddies.mapeditor;

import javax.swing.JComponent;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * One tool of the map maker, chosen in the sidebar: a title, what it does (its "?") and its page;
 * a tool that works on the map has it beside its page, the others take the whole room.
 */
interface Editor {
  String title();

  /** What the tool does, shown by its "?" and as its icon's tool tip. */
  String hint();

  /** The tool's icon in the sidebar. */
  Glyph glyph();

  /** The tool's lists and settings. */
  JComponent page();

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
