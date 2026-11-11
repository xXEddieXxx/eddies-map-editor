package eddies.mapeditor;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import lombok.experimental.UtilityClass;

/** How the tools lay out their panel: beside the map, or alone with the whole room. */
@UtilityClass
class Panels {
  private static final int LIST_WIDTH = 320;

  /** A list above the inspector of the chosen entry, the line between them movable. */
  static JSplitPane stacked(final JComponent list, final JComponent inspector) {
    final JSplitPane stacked = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, inspector);
    stacked.setResizeWeight(0.35);
    stacked.setBorder(null);
    return stacked;
  }

  /**
   * The list left of the inspector of the chosen entry, for a tool that has the whole room; the
   * line between them movable.
   */
  static JSplitPane sideBySide(final JComponent list, final JComponent inspector) {
    final JSplitPane sideBySide = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, list, inspector);
    sideBySide.setDividerLocation(LIST_WIDTH);
    sideBySide.setBorder(null);
    return sideBySide;
  }
}
