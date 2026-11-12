package eddies.mapeditor;

import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.Serial;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

/**
 * The button for the map's views: the icon and name of the shown one; a click opens all views as a
 * grid of tiles, each with its icon, name and below it what it shows. Only the player's choice
 * reports a change; {@link #showChosen} does not.
 */
final class MapViewsButton extends JButton {
  @Serial private static final long serialVersionUID = 1L;

  private static final int TILE_ICON = 32;
  private static final int TILE_WIDTH = 120;
  // the tile's text within its margins and the table's spacing
  private static final int TEXT_WIDTH = 100;

  private final Map<MapMode, JToggleButton> tiles = new EnumMap<>(MapMode.class);

  MapViewsButton(final MapMode shown, final Consumer<MapMode> chosen) {
    final JPopupMenu popup = new JPopupMenu();
    final JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
    grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    final ButtonGroup group = new ButtonGroup();
    for (final MapMode mode : MapMode.values()) {
      final JToggleButton tile =
          new JToggleButton(tileText(mode), new MakerIcon(mode.getGlyph(), TILE_ICON));
      tile.setVerticalTextPosition(SwingConstants.BOTTOM);
      tile.setHorizontalTextPosition(SwingConstants.CENTER);
      tile.setMargin(new Insets(6, 4, 6, 4));
      // as wide as the design wants, as high as the description needs
      tile.setPreferredSize(new Dimension(TILE_WIDTH, tile.getPreferredSize().height));
      tile.addActionListener(
          e -> {
            popup.setVisible(false);
            showChosen(mode);
            chosen.accept(mode);
          });
      group.add(tile);
      tiles.put(mode, tile);
      grid.add(tile);
    }
    popup.add(grid);
    setIconTextGap(6);
    addActionListener(e -> popup.show(this, 0, getHeight()));
    showChosen(shown);
  }

  /** The view's name and below it, smaller and wrapped, what it shows. */
  private static String tileText(final MapMode mode) {
    // a table's width, as Swing scales a CSS width by 4/3
    return "<html><table width='"
        + TEXT_WIDTH
        + "'><tr><td align='center'>"
        + mode
        + "<br><small><font color='"
        + Theme.convertColorToHex(Theme.getSecondaryTextColor())
        + "'>"
        + mode.getDescription()
        + "</font></small></td></tr></table></html>";
  }

  /** Shows the view as the chosen one, eg when an editor takes the map in its own view. */
  void showChosen(final MapMode mode) {
    tiles.get(mode).setSelected(true);
    setIcon(MakerIcon.small(mode.getGlyph()));
    setText(mode + " ▾");
    setToolTipText("View: " + mode.getDescription());
  }
}
