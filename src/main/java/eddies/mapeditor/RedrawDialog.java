package eddies.mapeditor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import lombok.experimental.UtilityClass;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MapFiles.Redrawn;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapProject.RedrawChoices;

/**
 * Asks what becomes of the areas of a redrawn picture that no territory took and of those a
 * rubbed-out border merged: which to add under what name, and which territory keeps each merged
 * one.
 */
@UtilityClass
class RedrawDialog {
  private static final String TITLE = "Redraw borders";
  private static final String LAND = "Land";
  private static final String SEA = "Sea zone";
  private static final String NAME_REFUSED = "Already used, used twice, or holds : < (.";
  private static final String HOW_TO_HIDDEN = "redrawHowToHidden";
  private static final int WIDTH = 640;
  private static final int MAX_HEIGHT = 560;
  // every row's picture is this size, so a wide merged area stays readable and rows line up
  private static final int THUMBNAIL_WIDTH = 160;
  private static final int THUMBNAIL_HEIGHT = 110;
  private static final int CARD_TEXT_WIDTH = WIDTH - THUMBNAIL_WIDTH - 5 * Theme.GAP_M;
  // the how-to shows once a session at most (Swing thread)
  private static boolean howToShown;

  /** The dialog's inside, built without a window, so tests can look at it. */
  record Content(
      JComponent root,
      Supplier<Optional<String>> problem,
      Supplier<RedrawChoices> choices,
      Runnable addAll,
      Runnable addNone) {}

  /** A new area's row: whether to add it, its name and kind, what is wrong with the name. */
  private record NewRow(
      DetectedTerritory area,
      JCheckBox add,
      JTextField name,
      JComboBox<String> kind,
      FieldError error) {}

  /**
   * Before the picture is chosen, what to draw into it; once a session and not after "Don't show
   * again". Whether to go on.
   */
  static boolean howTo(final Component parent) {
    return howTo(parent, Preferences.userNodeForPackage(RedrawDialog.class));
  }

  static boolean howTo(final Component parent, final Preferences preferences) {
    if (howToShown || preferences.getBoolean(HOW_TO_HIDDEN, false)) {
      return true;
    }
    final JCheckBox hide = new JCheckBox("Don't show again");
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    content.add(
        wrapped(
            "Draw the new borders as dark lines into a copy of the map's picture with a paint"
                + " program, then choose that picture. Territories keep their names, owners and"
                + " units; new areas become territories, rubbed-out borders merge territories.",
            480));
    content.add(Box.createVerticalStrut(Theme.GAP_M));
    content.add(hide);
    final boolean chosen = Dialogs.ask(parent, TITLE, content, "Choose picture…");
    if (hide.isSelected()) {
      preferences.putBoolean(HOW_TO_HIDDEN, true);
    }
    howToShown |= chosen;
    return chosen;
  }

  /** The choices, empty when cancelled; the verb is off while a ticked name is refused. */
  static Optional<RedrawChoices> ask(
      final Component parent, final MapProject project, final MapFiles from, final Redrawn redrawn) {
    final Content content = content(project, from, redrawn);
    return Dialogs.ask(parent, TITLE, content.root(), TITLE, () -> content.problem().get().isEmpty())
        ? Optional.of(content.choices().get())
        : Optional.empty();
  }

  /** The summary, the new areas and the merged ones, and the choices made in them. */
  static Content content(final MapProject project, final MapFiles from, final Redrawn redrawn) {
    final BufferedImage picture = redrawn.files().picture().orElseThrow();
    final Form form = new Form(CARD_TEXT_WIDTH);
    form.setBorder(BorderFactory.createEmptyBorder());
    final List<NewRow> rows = new ArrayList<>();
    final Runnable check = () -> showRefused(project, rows);
    if (!redrawn.added().isEmpty()) {
      final JButton all = new JButton("Add all");
      final JButton none = new JButton("None");
      Theme.setTertiaryButtonStyle(all);
      Theme.setTertiaryButtonStyle(none);
      all.addActionListener(e -> tickAll(rows, true, check));
      none.addActionListener(e -> tickAll(rows, false, check));
      form.row(heading("New areas (" + redrawn.added().size() + ")", 0, all, none));
      for (final DetectedTerritory area : redrawn.added()) {
        rows.add(newRow(project, from, picture, area, rows, form, check));
      }
    }
    final Map<DetectedTerritory, JComboBox<String>> keepers = new LinkedHashMap<>();
    if (!redrawn.merged().isEmpty()) {
      form.row(
          heading(
              "Merged areas (" + redrawn.merged().size() + ")",
              rows.isEmpty() ? 0 : Theme.GAP_L));
      redrawn
          .merged()
          .forEach(
              (area, members) -> {
                final JComboBox<String> keeper = new JComboBox<>(members.toArray(String[]::new));
                keeper.setToolTipText("Territory keeping the merged area");
                keeper.getAccessibleContext().setAccessibleName("Territory keeping the merged area");
                keepers.put(area, keeper);
                final boolean many = members.size() > 2;
                form.row(
                    card(
                        thumbnail(picture, area),
                        wrapped(together(members) + " are one area now.", CARD_TEXT_WIDTH),
                        line(new JLabel("Keep"), keeper),
                        secondary(
                            many
                                ? "The others are removed with their owners and units; their"
                                    + " neighbours go to the one kept; canals through them are"
                                    + " removed."
                                : "The other is removed with its owner and units; its neighbours"
                                    + " go to the one kept; canals through it are removed.")));
              });
    }
    form.end();
    check.run();
    final JScrollPane scroll = SwingComponents.newJScrollPane(form);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    final JLabel summary = wrapped(summary(redrawn), WIDTH);
    final JPanel root = new JPanel(new BorderLayout(0, Theme.GAP_M));
    root.setOpaque(false);
    root.add(summary, BorderLayout.NORTH);
    root.add(scroll, BorderLayout.CENTER);
    int fixed = summary.getPreferredSize().height + Theme.GAP_M;
    if (!redrawn.merged().isEmpty()) {
      final JLabel footer = secondary("A territory still named in triggers keeps its old border.");
      root.add(footer, BorderLayout.SOUTH);
      fixed += footer.getPreferredSize().height + Theme.GAP_M;
    }
    scroll.setPreferredSize(
        new Dimension(WIDTH, Math.min(MAX_HEIGHT - fixed, form.getPreferredSize().height + 4)));
    return new Content(
        root,
        () -> refusedName(project, rows),
        () -> choices(rows, keepers),
        () -> tickAll(rows, true, check),
        () -> tickAll(rows, false, check));
  }

  /** "The new picture has 3 new areas and 1 merged area. 41 territories took their new borders…" */
  private static String summary(final Redrawn redrawn) {
    final List<String> parts = new ArrayList<>();
    if (!redrawn.added().isEmpty()) {
      parts.add(count(redrawn.added().size(), "new area", "new areas"));
    }
    if (!redrawn.merged().isEmpty()) {
      parts.add(count(redrawn.merged().size(), "merged area", "merged areas"));
    }
    final int changed = redrawn.changed().size();
    return (parts.isEmpty() ? "" : "The new picture has " + String.join(" and ", parts) + ". ")
        + (changed == 0
            ? ""
            : changed == 1
                ? "1 territory took its new border; its name, owner and units stay."
                : changed + " territories took their new borders; their names, owners and units"
                    + " stay.");
  }

  private static String count(final int n, final String one, final String many) {
    return n + " " + (n == 1 ? one : many);
  }

  /** "a and b", "a, b and c". */
  private static String together(final List<String> names) {
    return names.size() == 1
        ? names.getFirst()
        : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }

  private static NewRow newRow(
      final MapProject project,
      final MapFiles from,
      final BufferedImage picture,
      final DetectedTerritory area,
      final List<NewRow> rows,
      final Form form,
      final Runnable check) {
    // a frame or legend box around the map is no territory: only areas in the old map start ticked
    final boolean inOldMap = isInOldMap(area, from);
    final JCheckBox add = new JCheckBox("Add as", inOldMap);
    add.getAccessibleContext().setAccessibleName("Add this area");
    final JTextField name =
        new JTextField(
            project.freeName(
                area.water() ? "New sea zone" : "New territory",
                rows.stream().map(r -> r.name().getText()).toList()),
            16);
    name.getAccessibleContext().setAccessibleName("Name of the new territory");
    final JComboBox<String> kind = new JComboBox<>(new String[] {LAND, SEA});
    kind.setSelectedItem(area.water() ? SEA : LAND);
    kind.setToolTipText("Land or sea zone");
    kind.getAccessibleContext().setAccessibleName("Land or sea zone");
    final FieldError error = new FieldError(name);
    add.addItemListener(e -> check.run());
    OnTyping.attach(name, check);
    final List<JComponent> lines = new ArrayList<>(List.of(line(add, name, kind), error));
    if (!inOldMap) {
      lines.add(secondary("Outside the old map, eg a frame or a legend box."));
    }
    form.row(card(thumbnail(picture, area), lines.toArray(JComponent[]::new)));
    return new NewRow(area, add, name, kind, error);
  }

  private static boolean isInOldMap(final DetectedTerritory area, final MapFiles from) {
    return from.polygons().values().stream()
        .flatMap(List::stream)
        .anyMatch(outline -> outline.contains(area.center()));
  }

  private static void tickAll(final List<NewRow> rows, final boolean add, final Runnable check) {
    rows.forEach(r -> r.add().setSelected(add));
    check.run();
  }

  /** Each ticked name the map cannot take, or that another ticked row has too, says so. */
  private static void showRefused(final MapProject project, final List<NewRow> rows) {
    final Set<String> names = new HashSet<>();
    for (final NewRow row : rows) {
      final boolean refused = row.add().isSelected() && isRefused(project, names, row);
      row.error().setProblem(refused ? Optional.of(NAME_REFUSED) : Optional.empty());
    }
  }

  /** The first ticked name the map cannot take or that another ticked row has too. */
  private static Optional<String> refusedName(final MapProject project, final List<NewRow> rows) {
    final Set<String> names = new HashSet<>();
    return rows.stream()
        .filter(r -> r.add().isSelected())
        .filter(r -> isRefused(project, names, r))
        .map(r -> r.name().getText())
        .findFirst();
  }

  private static boolean isRefused(
      final MapProject project, final Set<String> seen, final NewRow row) {
    final String name = row.name().getText();
    // seen first, so a refused name still counts for the rows below it
    final boolean twice = !seen.add(name);
    return twice || !project.isFreeName(name);
  }

  private static JComponent heading(final String text, final int above, final JComponent... actions) {
    final JPanel heading = new JPanel(new BorderLayout());
    heading.setOpaque(false);
    heading.setBorder(BorderFactory.createEmptyBorder(above, 0, 0, 0));
    heading.add(Theme.newSectionLabel(text), BorderLayout.WEST);
    final JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, Theme.GAP_XS, 0));
    right.setOpaque(false);
    for (final JComponent action : actions) {
      right.add(action);
    }
    heading.add(right, BorderLayout.EAST);
    return heading;
  }

  /** The thumbnail at the left, the lines stacked beside it. */
  private static JComponent card(final ImageIcon thumbnail, final JComponent... lines) {
    final JPanel text = new JPanel();
    text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
    text.setOpaque(false);
    for (final JComponent part : lines) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      if (text.getComponentCount() > 0) {
        text.add(Box.createVerticalStrut(Theme.GAP_XS));
      }
      text.add(part);
    }
    final JPanel card = new JPanel(new BorderLayout(Theme.GAP_M, 0));
    Theme.setCardStyle(card);
    card.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_S, Theme.GAP_S, Theme.GAP_S));
    card.add(new JLabel(thumbnail), BorderLayout.WEST);
    card.add(text, BorderLayout.CENTER);
    return card;
  }

  private static JPanel line(final JComponent... parts) {
    final JPanel line = new JPanel();
    line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
    line.setOpaque(false);
    for (final JComponent part : parts) {
      if (line.getComponentCount() > 0) {
        line.add(Box.createHorizontalStrut(Theme.GAP_S));
      }
      part.setMaximumSize(part.getPreferredSize());
      line.add(part);
    }
    line.setMaximumSize(line.getPreferredSize());
    return line;
  }

  private static JLabel secondary(final String text) {
    final JLabel label = wrapped(text, CARD_TEXT_WIDTH);
    label.setForeground(Theme.getSecondaryTextColor());
    return label;
  }

  private static JLabel wrapped(final String text, final int width) {
    return new JLabel(
        "<html><div style='width:"
            + Theme.htmlWidth(width)
            + "'>"
            + SearchList.escape(text)
            + "</div></html>");
  }

  /**
   * The picture around the area, fitted into the thumbnail's box, at most at its own size; its
   * outline in the accent color on a dark halo.
   */
  private static ImageIcon thumbnail(final BufferedImage picture, final DetectedTerritory area) {
    final Rectangle around =
        area.outlines().stream()
            .map(Polygon::getBounds)
            .reduce(Rectangle::union)
            .orElseGet(Rectangle::new);
    around.grow(10, 10);
    final double scale =
        Math.min(
            1,
            Math.min(
                (double) THUMBNAIL_WIDTH / Math.max(1, around.width),
                (double) THUMBNAIL_HEIGHT / Math.max(1, around.height)));
    final BufferedImage image =
        new BufferedImage(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.DARK_GRAY);
    g.fillRect(0, 0, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
    // the area in the middle of the box, the picture around it filling the rest
    g.translate(THUMBNAIL_WIDTH / 2.0, THUMBNAIL_HEIGHT / 2.0);
    g.scale(scale, scale);
    g.translate(-around.getCenterX(), -around.getCenterY());
    final Rectangle crop =
        new Rectangle(
                (int) (around.getCenterX() - THUMBNAIL_WIDTH / 2.0 / scale) - 1,
                (int) (around.getCenterY() - THUMBNAIL_HEIGHT / 2.0 / scale) - 1,
                (int) (THUMBNAIL_WIDTH / scale) + 2,
                (int) (THUMBNAIL_HEIGHT / scale) + 2)
            .intersection(new Rectangle(picture.getWidth(), picture.getHeight()));
    if (!crop.isEmpty()) {
      g.drawImage(
          picture.getSubimage(crop.x, crop.y, crop.width, crop.height), crop.x, crop.y, null);
    }
    g.setColor(new Color(0, 0, 0, 0xC0));
    g.setStroke(new BasicStroke((float) (5 / scale)));
    area.outlines().forEach(g::draw);
    g.setColor(Theme.getAccentColor());
    g.setStroke(new BasicStroke((float) (3 / scale)));
    area.outlines().forEach(g::draw);
    g.dispose();
    return new ImageIcon(image);
  }

  private static RedrawChoices choices(
      final List<NewRow> rows, final Map<DetectedTerritory, JComboBox<String>> keepers) {
    final Map<DetectedTerritory, DetectedTerritory> added = new LinkedHashMap<>();
    for (final NewRow row : rows) {
      if (row.add().isSelected()) {
        added.put(
            row.area(),
            new DetectedTerritory(
                row.name().getText(),
                SEA.equals(row.kind().getSelectedItem()),
                row.area().outlines(),
                row.area().center()));
      }
    }
    final Map<DetectedTerritory, String> kept = new LinkedHashMap<>();
    keepers.forEach((area, keeper) -> kept.put(area, (String) keeper.getSelectedItem()));
    return new RedrawChoices(added, kept);
  }
}
