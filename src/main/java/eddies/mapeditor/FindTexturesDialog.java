package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Image;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import lombok.experimental.UtilityClass;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.TextureFinder.Found;

/** Asks which found textures to take, under which names. */
@UtilityClass
class FindTexturesDialog {
  private static final String TITLE = "Find textures in the map";
  private static final int WIDTH = 520;
  private static final int MAX_HEIGHT = 520;
  private static final int SWATCH = 64;
  // a smaller tile of a few territories is mostly a marker or icon, eg a ring's: listed, not ticked
  private static final int SMALL_TILE = 64;
  private static final int FEW_TERRITORIES = 7;
  private static final int MAX_NAMED = 4;
  private static final String NAME_REFUSED =
      "Taken by another texture, used twice, empty, or holds \\ / : * ? \" < > |";

  /** The dialog's inside, built without a window, so tests can look at it. */
  record Content(
      JComponent root,
      Supplier<Optional<String>> problem,
      Supplier<Map<String, Found>> chosen) {}

  private record Row(Found found, JCheckBox take, JTextField name, FieldError error) {}

  /** The textures to take, by their file name in textures/, empty when cancelled. */
  static Optional<Map<String, Found>> ask(
      final Component parent, final List<Found> found, final List<String> taken) {
    final Content content = content(found, taken);
    return Dialogs.ask(
            parent, TITLE, content.root(), "Take textures", () -> content.problem().get().isEmpty())
        ? Optional.of(content.chosen().get())
        : Optional.empty();
  }

  /**
   * A card per texture: its tile, a name and how many territories have it.
   *
   * @param taken the textures the map has already, premade and its own
   */
  static Content content(final List<Found> found, final List<String> taken) {
    final Set<String> takenNames =
        taken.stream().map(FindTexturesDialog::bare).collect(Collectors.toSet());
    final List<Row> rows = new ArrayList<>();
    final Runnable check = () -> showRefused(rows, takenNames);
    final Form form = new Form(WIDTH - SWATCH - 4 * Theme.GAP_M);
    form.setBorder(BorderFactory.createEmptyBorder());
    final Map<Boolean, Integer> counts = new LinkedHashMap<>();
    for (final Found texture : found) {
      final int n = counts.merge(texture.water(), 1, Integer::sum);
      final JCheckBox take = new JCheckBox("Take as", !isSmall(texture));
      final String base = texture.water() ? "Sea texture " : "Land texture ";
      final JTextField name = new JTextField(freeName(base, n, takenNames), 16);
      name.getAccessibleContext().setAccessibleName("Name of the texture");
      final FieldError error = new FieldError(name);
      take.addItemListener(e -> check.run());
      OnTyping.attach(name, check);
      final JLabel where = new JLabel(where(texture.territories()));
      where.setForeground(Theme.getSecondaryTextColor());
      where.setToolTipText(String.join(", ", texture.territories()));
      form.row(card(swatch(texture), line(take, name), error, where));
      rows.add(new Row(texture, take, name, error));
    }
    form.end();
    check.run();
    final JScrollPane scroll = SwingComponents.newJScrollPane(form);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    final JLabel summary =
        new JLabel(
            "<html><div style='width:"
                + Theme.htmlWidth(WIDTH)
                + "'>"
                + (found.isEmpty()
                    ? "No textures found: the territories are flat colors or too small."
                    : "Territories that look alike share a texture. Taken, they go into the map's"
                        + " textures folder and the territories get them, as one step.")
                + "</div></html>");
    final JPanel root = new JPanel(new BorderLayout(0, Theme.GAP_M));
    root.setOpaque(false);
    root.add(summary, BorderLayout.NORTH);
    root.add(scroll, BorderLayout.CENTER);
    scroll.setPreferredSize(
        new Dimension(
            WIDTH,
            Math.min(
                MAX_HEIGHT - summary.getPreferredSize().height,
                form.getPreferredSize().height + 4)));
    return new Content(root, () -> refusedName(rows, takenNames), () -> chosen(rows));
  }

  private static boolean isSmall(final Found texture) {
    return Math.min(texture.tile().getWidth(), texture.tile().getHeight()) < SMALL_TILE
        && texture.territories().size() < FEW_TERRITORIES;
  }

  /** "Forest, Mirkwood and 3 more" */
  private static String where(final List<String> territories) {
    final int more = territories.size() - MAX_NAMED;
    return String.join(", ", territories.subList(0, Math.min(MAX_NAMED, territories.size())))
        + (more > 0 ? " and " + more + " more" : "");
  }

  private static String freeName(final String base, final int from, final Set<String> taken) {
    int n = from;
    while (taken.contains(bare(base + n))) {
      n++;
    }
    return base + n;
  }

  /** A name as compared: without ".png", in any case, as Windows' file names are. */
  private static String bare(final String name) {
    final String lower = name.strip().toLowerCase(Locale.ROOT);
    return lower.endsWith(".png") ? lower.substring(0, lower.length() - 4) : lower;
  }

  private static void showRefused(final List<Row> rows, final Set<String> taken) {
    final Set<String> seen = new HashSet<>();
    for (final Row row : rows) {
      final boolean refused = row.take().isSelected() && isRefused(row, seen, taken);
      row.error().setProblem(refused ? Optional.of(NAME_REFUSED) : Optional.empty());
    }
  }

  private static Optional<String> refusedName(final List<Row> rows, final Set<String> taken) {
    final Set<String> seen = new HashSet<>();
    return rows.stream()
        .filter(r -> r.take().isSelected())
        .filter(r -> isRefused(r, seen, taken))
        .map(r -> r.name().getText())
        .findFirst();
  }

  private static boolean isRefused(final Row row, final Set<String> seen, final Set<String> taken) {
    final String name = bare(row.name().getText());
    final boolean twice = !seen.add(name);
    return twice
        || name.isEmpty()
        || taken.contains(name)
        || row.name().getText().strip().endsWith(".")
        || name.matches(".*[\\\\/:*?\"<>|].*");
  }

  /** The ticked textures by file name, eg "Land texture 1.png". */
  private static Map<String, Found> chosen(final List<Row> rows) {
    final Map<String, Found> chosen = new LinkedHashMap<>();
    for (final Row row : rows) {
      if (row.take().isSelected()) {
        final String name = row.name().getText().strip();
        final boolean png = name.toLowerCase(Locale.ROOT).endsWith(".png");
        chosen.put(png ? name : name + ".png", row.found());
      }
    }
    return chosen;
  }

  private static ImageIcon swatch(final Found texture) {
    return new ImageIcon(
        texture.tile().getScaledInstance(SWATCH, SWATCH, Image.SCALE_SMOOTH));
  }

  private static JComponent card(final ImageIcon swatch, final JComponent... lines) {
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
    card.add(new JLabel(swatch), BorderLayout.WEST);
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
}
