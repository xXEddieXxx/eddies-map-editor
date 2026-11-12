package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * The line below the map: left the views ({@link MapViewsBar}, only their icons on a narrow map),
 * then what the shown view's colors and marks mean, as many nations as fit and a button for all;
 * right how many territories changed since the last save and how many problems are outlined red,
 * a click on them shows the problem list.
 */
final class MapLegend extends JPanel {
  @Serial private static final long serialVersionUID = 1L;
  private static final int GAP = 12;
  // narrower, the views show only their icons
  private static final int NAMES_FROM = 700;

  private final transient MapProject project;
  private final MapViewsBar views;
  private MapMode mode = MapMode.GAME;
  private final JPanel keys = new JPanel(new FlowLayout(FlowLayout.LEFT, GAP, 2));
  private final JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, GAP, 2));
  private final List<JLabel> nationChips = new ArrayList<>();
  // "Colors from Nations ›" in the views with nation colors, shown only while every nation fits
  private Optional<JComponent> colorsLink = Optional.empty();
  private final JButton moreNations = new JButton();
  private final JPopupMenu allNations = new JPopupMenu();
  private transient Runnable showProblems = () -> {};

  MapLegend(final MapProject project, final MapViewsBar views) {
    super(new BorderLayout(GAP, 0));
    this.project = project;
    this.views = views;
    add(keys, BorderLayout.WEST);
    add(status, BorderLayout.EAST);
    Theme.setTertiaryButtonStyle(moreNations);
    moreNations.addActionListener(e -> allNations.show(moreNations, 0, moreNations.getHeight()));
    project.onChange(this::fill);
    fill();
  }

  /** Explains this view from now on. */
  void explain(final MapMode shown) {
    mode = shown;
    fill();
  }

  /** What a click on the problems does, eg show the problem list. */
  void onShowProblems(final Runnable showProblems) {
    this.showProblems = showProblems;
  }

  private void fill() {
    keys.removeAll();
    nationChips.clear();
    colorsLink = Optional.empty();
    keys.add(views);
    switch (mode) {
      case GAME, OWNERS -> {
        final List<String> nations = MapXml.nations(project.game());
        final JPanel all = new JPanel(new GridLayout(0, 2, GAP, Theme.GAP_XS));
        all.setBorder(
            BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_M, Theme.GAP_S, Theme.GAP_M));
        for (final String nation : nations) {
          final JLabel chip = chip(project.nationColor(nation), nation);
          nationChips.add(chip);
          keys.add(chip);
          all.add(chip(project.nationColor(nation), nation));
        }
        allNations.removeAll();
        allNations.add(all);
        keys.add(moreNations);
        // counted as always shown by doLayout, so fewer nations fit beside it
        keys.add(chip(MapCanvas.NEUTRAL, "No owner"));
        // the game view leaves seas clear over the map's picture
        if (mode == MapMode.OWNERS) {
          keys.add(chip(MapCanvas.SEA, "Sea zone"));
        }
        // last, as no owner and sea have no nation's color
        colorsLink = Optional.of(Help.link("Colors from", "Nations"));
        keys.add(colorsLink.get());
      }
      case VALUES -> keys.add(note("Badge: PUs per turn · ★ capital · VC victory city"));
      case UNITS -> keys.add(note("Units where the game draws them"));
      case CONNECTIONS ->
          keys.add(note("Line = neighbours. The chosen territory's lines are red."));
      default -> throw new IllegalStateException("Unknown view: " + mode);
    }
    fillStatus();
    revalidate();
    repaint();
  }

  /** The changed territories and the problems outlined red, each only when there are any. */
  private void fillStatus() {
    status.removeAll();
    final long changed = project.changedCount();
    if (changed > 0) {
      final JLabel label = new JLabel("● " + changed + " changed");
      label.setToolTipText("Changed since the last save. The list marks them with ●.");
      status.add(label);
    }
    final long problems =
        project.problems().stream().filter(problem -> problem.territory().isPresent()).count();
    if (problems > 0) {
      final JButton button =
          new JButton("■ " + problems + (problems == 1 ? " problem" : " problems"));
      Theme.setDestructiveButtonStyle(button);
      button.setToolTipText("Outlined red on the map. Click for the list.");
      button.addActionListener(e -> showProblems.run());
      status.add(button);
    }
  }

  /** Shows as many nations as fit beside the rest of the line, and how many more there are. */
  @Override
  public void doLayout() {
    if (getWidth() < NAMES_FROM) {
      views.showIconsOnly();
    } else {
      views.showNames();
    }
    if (!nationChips.isEmpty()) {
      final Insets insets = getInsets();
      final Insets keyInsets = keys.getInsets();
      int room =
          getWidth()
              - insets.left
              - insets.right
              - status.getPreferredSize().width
              - GAP
              - keyInsets.left
              - keyInsets.right
              - GAP;
      // what always shows: the views and the keys after the nations, not the link to Nations
      for (final Component key : keys.getComponents()) {
        if (!nationChips.contains(key)
            && key != moreNations
            && colorsLink.filter(key::equals).isEmpty()) {
          room -= key.getPreferredSize().width + GAP;
        }
      }
      // the link only when every nation fits beside it: the nations come first
      final int linkWidth = colorsLink.map(l -> l.getPreferredSize().width + GAP).orElse(0);
      final int allNations =
          nationChips.stream().mapToInt(chip -> chip.getPreferredSize().width + GAP).sum();
      final boolean linkFits = allNations + linkWidth <= room;
      colorsLink.ifPresent(l -> l.setVisible(linkFits));
      if (linkFits) {
        room -= linkWidth;
      }
      // measured, not set, as each new text would lay out the line again; with the most digits
      final Insets buttonInsets = moreNations.getInsets();
      final int moreWidth =
          buttonInsets.left
              + buttonInsets.right
              + moreNations
                  .getFontMetrics(moreNations.getFont())
                  .stringWidth(moreText(nationChips.size()))
              + GAP;
      int shown = 0;
      int width = 0;
      for (final JLabel chip : nationChips) {
        width += chip.getPreferredSize().width + GAP;
        // the button for the rest needs room too, unless this is the last nation
        final boolean last = shown == nationChips.size() - 1;
        if (width + (last ? 0 : moreWidth) > room) {
          break;
        }
        shown++;
      }
      for (int i = 0; i < nationChips.size(); i++) {
        nationChips.get(i).setVisible(i < shown);
      }
      moreNations.setText(moreText(nationChips.size() - shown));
      moreNations.setVisible(shown < nationChips.size());
    } else {
      moreNations.setVisible(false);
    }
    super.doLayout();
  }

  private static String moreText(final int hidden) {
    return "+" + hidden + " nations";
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
  record Swatch(Color color) implements Icon {
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
