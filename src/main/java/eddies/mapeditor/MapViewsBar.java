package eddies.mapeditor;

import java.awt.FlowLayout;
import java.io.Serial;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * The map's views as one row of toggles at the start of the map's toolbar, the shown one selected;
 * what a view shows is its tool tip. With room each has its icon and name, shorter of room only the
 * chosen one or none ({@link Names}). Only the player's choice reports a change; {@link
 * #showChosen} does not.
 */
final class MapViewsBar extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  /** Which views show their name beside their icon. */
  enum Names {
    ALL,
    CHOSEN,
    NONE
  }

  private final Map<MapMode, JToggleButton> toggles = new EnumMap<>(MapMode.class);
  private Names names = Names.ALL;

  MapViewsBar(final MapMode shown, final Consumer<MapMode> chosen) {
    super(new FlowLayout(FlowLayout.LEFT, 0, 0));
    setOpaque(false);
    final ButtonGroup group = new ButtonGroup();
    for (final MapMode mode : MapMode.values()) {
      final JToggleButton toggle =
          new JToggleButton(mode.toString(), new MakerIcon(mode.getGlyph(), Theme.MAP_TOOL_ICON));
      Theme.setMapToolStyle(toggle);
      // with the name, as only the icon shows when the toolbar is short of room
      final String tip = mode + ": " + mode.getDescription();
      toggle.setToolTipText(tip);
      toggle.getAccessibleContext().setAccessibleName(tip);
      toggle.addActionListener(
          e -> {
            showTexts();
            chosen.accept(mode);
          });
      group.add(toggle);
      toggles.put(mode, toggle);
      add(toggle);
    }
    showChosen(shown);
  }

  /** Shows the view as the chosen one, eg when an editor takes the map in its own view. */
  void showChosen(final MapMode mode) {
    toggles.get(mode).setSelected(true);
    showTexts();
  }

  /** Shows these names from now on. */
  void show(final Names shown) {
    names = shown;
    showTexts();
  }

  /** How wide the views are with these names, measured without changing them. */
  int width(final Names shown) {
    int width = 0;
    for (final Map.Entry<MapMode, JToggleButton> entry : toggles.entrySet()) {
      final JToggleButton toggle = entry.getValue();
      width +=
          Theme.mapToolWidth(
              toggle, Optional.of(toggle.getIcon()), text(entry.getKey(), toggle, shown));
    }
    return width;
  }

  private void showTexts() {
    toggles.forEach(
        (mode, toggle) -> {
          final String text = text(mode, toggle, names);
          if (!text.equals(toggle.getText())) {
            toggle.setText(text);
            Theme.fitMapTool(toggle);
          }
        });
  }

  private static String text(final MapMode mode, final JToggleButton toggle, final Names shown) {
    final boolean named =
        switch (shown) {
          case ALL -> true;
          case CHOSEN -> toggle.isSelected();
          case NONE -> false;
        };
    return named ? mode.toString() : "";
  }
}
