package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The page the map maker opens with: a card to open a map's folder, one for a new map with the
 * wizard, and the maps opened last.
 */
final class StartPage extends JPanel {
  @Serial private static final long serialVersionUID = 1L;

  private static final int ICON_SIZE = 44;
  private static final int CARD_WIDTH = 352;
  private static final int COLUMN_WIDTH = 2 * CARD_WIDTH + Theme.GAP_L;
  private static final int TOP = 120;
  private static final int ROW_HEIGHT = 44;

  private final transient Consumer<Path> openRecent;
  private final JPanel recent = new JPanel();
  private final GridBagConstraints place = new GridBagConstraints();
  private final JPanel column = new JPanel(new GridBagLayout());
  private boolean noRecentMaps;

  /** With the maps opened last; a click on one opens its game. */
  StartPage(final Runnable open, final Runnable newMap, final Consumer<Path> openRecent) {
    this(open, newMap, openRecent, RecentMaps.list());
  }

  StartPage(
      final Runnable open,
      final Runnable newMap,
      final Consumer<Path> openRecent,
      final List<Path> recentGames) {
    super(new GridBagLayout());
    this.openRecent = openRecent;
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridwidth = 2;
    c.insets = new Insets(0, 0, Theme.GAP_XS, 0);
    final JLabel heading = new JLabel("Eddie's Map Editor");
    heading.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1");
    column.add(heading, c);
    final JLabel intro =
        new JLabel(
            "Make your own map for TripleA, or change one you have. · Version "
                + EddiesMapEditor.version());
    intro.setForeground(Theme.getSecondaryTextColor());
    c.insets = new Insets(0, 0, Theme.GAP_XXL, 0);
    column.add(intro, c);
    c.gridwidth = 1;
    c.gridy = 2;
    c.gridx = GridBagConstraints.RELATIVE;
    c.insets = new Insets(0, 0, 0, Theme.GAP_L);
    c.fill = GridBagConstraints.BOTH;
    column.add(
        newCard(
            Glyph.FILES,
            "Open a map…",
            "A map folder on your computer",
            Theme.getSeparatorColor(),
            open),
        c);
    c.insets = new Insets(0, 0, 0, 0);
    // without maps opened yet, the new map is the way to start
    column.add(
        newCard(
            Glyph.NEW_MAP,
            "New map…",
            "Name it, add a picture of it, say who plays",
            recentGames.isEmpty() ? Theme.getAccentColor() : Theme.getSeparatorColor(),
            newMap),
        c);
    c.gridx = 0;
    c.gridy = 3;
    c.gridwidth = 2;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(Theme.GAP_XXL, 0, 0, 0);
    recent.setLayout(new BoxLayout(recent, BoxLayout.Y_AXIS));
    recent.setOpaque(false);
    column.add(recent, c);
    fillRecent(recentGames);
    place.anchor = GridBagConstraints.NORTH;
    place.weightx = 1;
    place.weighty = 1;
    add(column, place);
  }

  // in the middle without recent maps; with them 120 px down, so the list grows downwards, closer
  // to the top where the window is too low for that
  @Override
  public void doLayout() {
    final int spare = getHeight() - column.getPreferredSize().height;
    final int top =
        noRecentMaps
            ? Math.max(spare / 2, Theme.GAP_XXL)
            : Math.clamp(spare / 2, Theme.GAP_XXL, TOP);
    place.insets = new Insets(top, 0, 0, 0);
    ((GridBagLayout) getLayout()).setConstraints(column, place);
    super.doLayout();
  }

  private void fillRecent(final List<Path> games) {
    recent.removeAll();
    noRecentMaps = games.isEmpty();
    if (games.isEmpty()) {
      final JLabel firstRun =
          new JLabel(
              "<html><div style='width:"
                  + Theme.htmlWidth(COLUMN_WIDTH)
                  + "'>New to map making? Start with a picture of your map: the editor finds"
                  + " the territories in it.</div></html>");
      firstRun.setForeground(Theme.getSecondaryTextColor());
      recent.add(alignedLeft(firstRun));
    } else {
      recent.add(alignedLeft(Theme.newSectionLabel("Recent maps")));
      recent.add(Box.createVerticalStrut(Theme.GAP_XS));
      recent.add(alignedLeft(new JSeparator()));
      recent.add(Box.createVerticalStrut(Theme.GAP_XS));
      for (final Path game : games) {
        recent.add(
            alignedLeft(
                Files.isRegularFile(game) ? newRecentRow(game) : newMissingRow(game, games)));
      }
    }
    recent.revalidate();
    recent.repaint();
  }

  private static JComponent alignedLeft(final JComponent part) {
    part.setAlignmentX(LEFT_ALIGNMENT);
    return part;
  }

  /** The game's name, its file and map folder below; a click opens it. */
  private JComponent newRecentRow(final Path game) {
    final Path root = MapFolder.rootOf(game.getParent());
    final JButton row = new JButton();
    row.setLayout(new GridLayout(2, 1));
    row.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    row.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_S, 0, Theme.GAP_S));
    final JLabel title = Theme.newSectionLabel(gameName(game, root));
    final JLabel where = new JLabel();
    where.setForeground(Theme.getSecondaryTextColor());
    final String file = game.getFileName().toString().replaceFirst("\\.xml$", "") + " · ";
    final FontMetrics metrics = where.getFontMetrics(where.getFont());
    where.setText(
        file
            + SearchList.cutMiddle(
                root.toString(), metrics, COLUMN_WIDTH - Theme.GAP_XL - metrics.stringWidth(file)));
    row.add(title);
    row.add(where);
    row.setToolTipText(game.toString());
    row.getAccessibleContext().setAccessibleName(title.getText());
    rowSize(row);
    row.addActionListener(e -> openRecent.accept(game));
    return row;
  }

  /** The name map.yml gives the game, else its file's. */
  private static String gameName(final Path game, final Path root) {
    try {
      final Map<String, Path> games = MapFolder.gamesOf(root);
      return games.entrySet().stream()
          .filter(named -> named.getValue().toAbsolutePath().normalize().equals(game))
          .map(Map.Entry::getKey)
          .findFirst()
          .orElse(game.getFileName().toString());
    } catch (final IOException e) {
      return game.getFileName().toString();
    }
  }

  /** A game whose file is gone: "Not found" and Remove, which forgets it. */
  private JComponent newMissingRow(final Path game, final List<Path> games) {
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    row.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_S, 0, Theme.GAP_S));
    final JPanel lines = new JPanel(new GridLayout(2, 1));
    lines.setOpaque(false);
    lines.add(Theme.newSectionLabel(game.getFileName().toString()));
    final JLabel notFound = new JLabel("Not found");
    notFound.setForeground(Theme.getSecondaryTextColor());
    notFound.setToolTipText(game.toString());
    lines.add(notFound);
    row.add(lines, BorderLayout.CENTER);
    final JButton remove = new JButton("Remove");
    Theme.setTertiaryButtonStyle(remove);
    remove.addActionListener(
        e -> {
          RecentMaps.remove(game);
          fillRecent(games.stream().filter(other -> !other.equals(game)).toList());
        });
    final JPanel right = new JPanel(new GridBagLayout());
    right.setOpaque(false);
    right.add(remove);
    row.add(right, BorderLayout.EAST);
    rowSize(row);
    return row;
  }

  private static void rowSize(final JComponent row) {
    row.setPreferredSize(new Dimension(COLUMN_WIDTH, ROW_HEIGHT));
    row.setMaximumSize(row.getPreferredSize());
  }

  /** A card that acts like a button: its icon, title and what it does; the border lights up. */
  private static JPanel newCard(
      final Glyph glyph,
      final String title,
      final String description,
      final Color line,
      final Runnable action) {
    final JPanel card = new JPanel(new BorderLayout(0, 10));
    Theme.setCardStyle(card);
    final Border quiet = cardBorder(line);
    final Border lit = cardBorder(Theme.getAccentColor());
    card.setBorder(quiet);
    card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    // icon and title on top, the description below them from the top: titles stay in one line
    final JPanel top = new JPanel(new BorderLayout(0, 10));
    top.setOpaque(false);
    top.add(new JLabel(new MakerIcon(glyph, ICON_SIZE)), BorderLayout.NORTH);
    final JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
    titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
    top.add(titleLabel, BorderLayout.CENTER);
    card.add(top, BorderLayout.NORTH);
    final JLabel descriptionLabel =
        new JLabel(
            "<html><div style='text-align:center;width:"
                + Theme.htmlWidth(CARD_WIDTH - 40)
                + "'>"
                + SearchList.escape(description)
                + "</div></html>",
            SwingConstants.CENTER);
    descriptionLabel.setForeground(Theme.getSecondaryTextColor());
    descriptionLabel.setVerticalAlignment(SwingConstants.TOP);
    card.add(descriptionLabel, BorderLayout.CENTER);
    card.setPreferredSize(new Dimension(CARD_WIDTH, card.getPreferredSize().height));
    card.getAccessibleContext().setAccessibleName(title);
    card.getAccessibleContext().setAccessibleDescription(description);
    // lit while the mouse is over it or it has the keyboard's focus
    final Runnable light =
        () -> card.setBorder(card.hasFocus() || card.getMousePosition() != null ? lit : quiet);
    card.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(final MouseEvent e) {
            light.run();
          }

          @Override
          public void mouseExited(final MouseEvent e) {
            light.run();
          }

          @Override
          public void mouseClicked(final MouseEvent e) {
            action.run();
            light.run();
          }
        });
    // reachable with Tab, Enter or Space opens it
    card.setFocusable(true);
    card.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(final FocusEvent e) {
            light.run();
          }

          @Override
          public void focusLost(final FocusEvent e) {
            light.run();
          }
        });
    card.addKeyListener(
        new KeyAdapter() {
          @Override
          public void keyPressed(final KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
              action.run();
            }
          }
        });
    return card;
  }

  private static Border cardBorder(final Color line) {
    return BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(line, 2, true),
        BorderFactory.createEmptyBorder(22, 18, 20, 18));
  }
}
