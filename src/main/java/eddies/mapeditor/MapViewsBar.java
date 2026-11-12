package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.Serial;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * The map's views as one row of toggles, each with its icon and name, the shown one selected; what
 * a view shows is its tool tip. Only the player's choice reports a change; {@link #showChosen}
 * does not.
 */
final class MapViewsBar extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  private static final int ICON_ONLY = 28;

  private final Map<MapMode, JToggleButton> toggles = new EnumMap<>(MapMode.class);

  MapViewsBar(final MapMode shown, final Consumer<MapMode> chosen) {
    super(new FlowLayout(FlowLayout.LEFT, 0, 0));
    setOpaque(false);
    final ButtonGroup group = new ButtonGroup();
    for (final MapMode mode : MapMode.values()) {
      final JToggleButton toggle =
          new JToggleButton(mode.toString(), MakerIcon.small(mode.getGlyph()));
      toggle.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
      // with the name, as only the icon shows on a narrow map
      final String tip = mode + ": " + mode.getDescription();
      toggle.setToolTipText(tip);
      toggle.getAccessibleContext().setAccessibleName(tip);
      toggle.addActionListener(e -> chosen.accept(mode));
      group.add(toggle);
      toggles.put(mode, toggle);
      add(toggle);
    }
    showChosen(shown);
  }

  /** Shows the view as the chosen one, eg when an editor takes the map in its own view. */
  void showChosen(final MapMode mode) {
    toggles.get(mode).setSelected(true);
  }

  /** Each view with its icon and name. */
  void showNames() {
    toggles.forEach(
        (mode, toggle) -> {
          toggle.setText(mode.toString());
          toggle.setPreferredSize(null);
        });
  }

  /** Only the icons, square as the map's other icon buttons; the names stay in the tool tips. */
  void showIconsOnly() {
    for (final JToggleButton toggle : toggles.values()) {
      toggle.setText("");
      toggle.setPreferredSize(new Dimension(UIScale.scale(ICON_ONLY), UIScale.scale(ICON_ONLY)));
    }
  }
}
