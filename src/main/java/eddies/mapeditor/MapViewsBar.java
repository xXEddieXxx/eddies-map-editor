package eddies.mapeditor;

import java.awt.FlowLayout;
import java.io.Serial;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;

/**
 * The map's views at the start of the map's toolbar: one button, "View: Game ▾", whose menu lists
 * the views to choose from ({@link MapMode#CHOICES}) with what they show. Only the map maker's
 * choice reports a change; {@link #showChosen} does not.
 */
final class MapViewsBar extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  private final JButton chooser = new JButton();
  private final transient Consumer<MapMode> chosen;
  private MapMode shown;

  MapViewsBar(final MapMode shown, final Consumer<MapMode> chosen) {
    super(new FlowLayout(FlowLayout.LEFT, 0, 0));
    this.chosen = chosen;
    this.shown = shown;
    setOpaque(false);
    Theme.setMapToolStyle(chooser);
    chooser.addActionListener(e -> viewsMenu().show(chooser, 0, chooser.getHeight()));
    add(chooser);
    showChosen(shown);
  }

  /** Shows the view as the chosen one, eg when an editor takes the map in its own view. */
  void showChosen(final MapMode mode) {
    shown = mode;
    chooser.setText("View: " + mode + " ▾");
    chooser.setIcon(new MakerIcon(mode.getGlyph(), Theme.MAP_TOOL_ICON));
    chooser.setToolTipText(
        "View: " + mode + ". " + mode.getDescription() + ". Click for the other views");
    chooser.getAccessibleContext().setAccessibleName("View: " + mode);
    Theme.fitMapTool(chooser);
  }

  /** The views to choose from, each with its icon, name and what it shows. */
  JPopupMenu viewsMenu() {
    final JPopupMenu menu = new JPopupMenu();
    final String grey = Theme.convertColorToHex(Theme.getSecondaryTextColor());
    for (final MapMode mode : MapMode.CHOICES) {
      final JRadioButtonMenuItem item =
          new JRadioButtonMenuItem(
              "<html><b>"
                  + mode
                  + "</b><br><span style='color:"
                  + grey
                  + "'>"
                  + mode.getDescription()
                  + "</span></html>",
              new MakerIcon(mode.getGlyph(), Theme.MAP_TOOL_ICON),
              mode == shown);
      item.getAccessibleContext().setAccessibleName(mode + ": " + mode.getDescription());
      item.addActionListener(e -> choose(mode));
      menu.add(item);
    }
    return menu;
  }

  private void choose(final MapMode mode) {
    showChosen(mode);
    chosen.accept(mode);
  }
}
