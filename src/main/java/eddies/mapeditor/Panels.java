package eddies.mapeditor;

import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.Dimension;
import java.awt.Point;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
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

  /** A text left of how it shows, in halves of the room; the line between them movable. */
  static JSplitPane textAndPreview(final JComponent text, final JComponent preview) {
    // the same small wish for both, as a wide HTML table would take nearly all the room
    text.setPreferredSize(new Dimension(100, 0));
    preview.setPreferredSize(new Dimension(100, 0));
    final JSplitPane halves = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, text, preview);
    halves.setResizeWeight(0.5);
    halves.setBorder(null);
    return halves;
  }

  /** Shows the HTML in the preview where the reader was in it, eg after a pause in typing. */
  static void showKeepingPlace(final GameNotesView preview, final String html) {
    final Optional<JViewport> viewport =
        Optional.ofNullable(
            (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, preview));
    final Point at = viewport.map(JViewport::getViewPosition).orElseGet(Point::new);
    preview.setText(html);
    // the view scrolls to its top after a new text, later: back to where the reader was
    SwingUtilities.invokeLater(() -> viewport.ifPresent(v -> v.setViewPosition(at)));
  }
}
