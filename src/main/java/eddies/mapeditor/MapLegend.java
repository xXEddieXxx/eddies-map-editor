package eddies.mapeditor;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.io.Serial;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The line below the map that says what its colors and marks mean in the shown view: the nations'
 * colors, the badges, the lines; a red outline is a territory with a problem.
 */
final class MapLegend extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  // more nations than fit in the line: the rest is in Nations
  private static final int MAX_NATIONS = 6;

  private final transient MapProject project;
  private MapMode mode = MapMode.GAME;

  MapLegend(final MapProject project) {
    super(new FlowLayout(FlowLayout.LEFT, 12, 2));
    this.project = project;
    project.onChange(this::fill);
    fill();
  }

  /** Explains this view from now on. */
  void explain(final MapMode shown) {
    mode = shown;
    fill();
  }

  private void fill() {
    removeAll();
    switch (mode) {
      case GAME, OWNERS -> {
        final List<String> nations = MapXml.nations(project.game());
        nations.stream()
            .limit(MAX_NATIONS)
            .forEach(nation -> add(chip(project.nationColor(nation), nation)));
        if (nations.size() > MAX_NATIONS) {
          add(note("+" + (nations.size() - MAX_NATIONS) + " more"));
        }
        add(chip(MapCanvas.NEUTRAL, "No owner"));
        // the game view leaves seas clear over the map's picture
        if (mode == MapMode.OWNERS) {
          add(chip(MapCanvas.SEA, "Sea zone"));
        }
      }
      case VALUES -> add(note("Badge: PUs per turn · ★ capital · VC victory city"));
      case UNITS ->
          add(
              note(
                  "The starting units where the game puts them, how many beside them; zoomed out,"
                      + " a count"));
      case CONNECTIONS ->
          add(note("A line joins two neighbours; the chosen territory's lines stand out"));
      default -> throw new IllegalStateException("Unknown view: " + mode);
    }
    if (project.problems().stream().anyMatch(problem -> problem.territory().isPresent())) {
      add(chip(MapCanvas.PROBLEM, "Has a problem, see Overview"));
    }
    add(note("● not saved"));
    revalidate();
    repaint();
  }

  private static JLabel chip(final Color color, final String text) {
    return new JLabel(text, new Swatch(color), JLabel.LEFT);
  }

  private static JLabel note(final String text) {
    final JLabel note = new JLabel(text);
    note.setForeground(Theme.getSecondaryTextColor());
    return note;
  }

  /** A small square of the color. */
  private record Swatch(Color color) implements Icon {
    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
      g.setColor(color);
      g.fillRect(x, y + 1, 12, 12);
      g.setColor(Theme.getSwatchOutlineColor(color));
      g.drawRect(x, y + 1, 11, 11);
    }

    @Override
    public int getIconWidth() {
      return 12;
    }

    @Override
    public int getIconHeight() {
      return 14;
    }
  }
}
