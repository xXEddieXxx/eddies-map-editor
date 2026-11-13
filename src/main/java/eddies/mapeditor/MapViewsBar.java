package eddies.mapeditor;

import java.awt.FlowLayout;
import java.io.Serial;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JToggleButton;

/**
 * The map's views at the start of the map's toolbar: with room a row of toggles, each with its icon
 * and name, the shown one selected, what a view shows as its tool tip; short of room one button,
 * "View: Game ▾", whose menu lists them with what they show ({@link Shape}). Only the player's
 * choice reports a change; {@link #showChosen} does not.
 */
final class MapViewsBar extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  /** How the views show: a toggle each, or one button with a menu. */
  enum Shape {
    TOGGLES,
    DROPDOWN
  }

  private final Map<MapMode, JToggleButton> toggles = new EnumMap<>(MapMode.class);
  private final JButton chooser = new JButton();
  private final transient Consumer<MapMode> chosen;
  private MapMode shown;

  MapViewsBar(final MapMode shown, final Consumer<MapMode> chosen) {
    super(new FlowLayout(FlowLayout.LEFT, 0, 0));
    this.chosen = chosen;
    this.shown = shown;
    setOpaque(false);
    final ButtonGroup group = new ButtonGroup();
    for (final MapMode mode : MapMode.values()) {
      final JToggleButton toggle =
          new JToggleButton(mode.toString(), new MakerIcon(mode.getGlyph(), Theme.MAP_TOOL_ICON));
      Theme.setMapToolStyle(toggle);
      final String tip = mode + ": " + mode.getDescription();
      toggle.setToolTipText(tip);
      toggle.getAccessibleContext().setAccessibleName(tip);
      toggle.addActionListener(e -> choose(mode));
      group.add(toggle);
      toggles.put(mode, toggle);
      add(toggle);
    }
    Theme.setMapToolStyle(chooser);
    chooser.addActionListener(e -> viewsMenu().show(chooser, 0, chooser.getHeight()));
    chooser.setVisible(false);
    add(chooser);
    showChosen(shown);
  }

  /** Shows the view as the chosen one, eg when an editor takes the map in its own view. */
  void showChosen(final MapMode mode) {
    shown = mode;
    toggles.get(mode).setSelected(true);
    chooser.setText("View: " + mode + " ▾");
    chooser.setIcon(new MakerIcon(mode.getGlyph(), Theme.MAP_TOOL_ICON));
    chooser.setToolTipText(
        "View: " + mode + ". " + mode.getDescription() + ". Click for the other views");
    chooser.getAccessibleContext().setAccessibleName("View: " + mode);
    Theme.fitMapTool(chooser);
  }

  /** Shows the views in this shape from now on. */
  void show(final Shape shape) {
    toggles.values().forEach(toggle -> toggle.setVisible(shape == Shape.TOGGLES));
    chooser.setVisible(shape == Shape.DROPDOWN);
  }

  /** How wide the views are in this shape, measured without changing them. */
  int width(final Shape shape) {
    return shape == Shape.DROPDOWN
        ? chooser.getPreferredSize().width
        : toggles.values().stream().mapToInt(toggle -> toggle.getPreferredSize().width).sum();
  }

  /** The views to choose from, each with its icon, name and what it shows. */
  JPopupMenu viewsMenu() {
    final JPopupMenu menu = new JPopupMenu();
    final String grey = Theme.convertColorToHex(Theme.getSecondaryTextColor());
    for (final MapMode mode : MapMode.values()) {
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
