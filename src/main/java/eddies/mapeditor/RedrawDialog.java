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
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
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
import eddies.mapeditor.MapFiles.Group;
import eddies.mapeditor.MapFiles.Redrawn;
import eddies.mapeditor.MapPicture.DetectedTerritory;
import eddies.mapeditor.MapProject.RedrawChoices;
import eddies.mapeditor.MapProject.Stroke;

/**
 * Asks which changes of a redrawn picture to take, group by group ({@link Redrawn#groups}): each
 * taken or kept as it was as a whole, so no hole or overlap is left; in a taken one, which
 * territory keeps each merged area, and which new areas to add under what name. After Draw
 * borders a change starts taken only where a line drawn crosses it; from a new picture every
 * change starts taken.
 */
@UtilityClass
class RedrawDialog {
  private static final String TITLE = "Redraw borders";
  private static final String LAND = "Land";
  private static final String SEA = "Sea zone";
  private static final String NAME_REFUSED = "Already used, used twice, or holds : < (.";
  private static final String HOW_TO_HIDDEN = "redrawHowToHidden";
  private static final String LOOK = "\n\nLook at them on the map; Undo takes it all back.";
  private static final String UNTOUCHED = "Not touched by your lines: off unless you tick it.";
  private static final String REMOVED =
      "Where territories become one, the others are removed with their owners and units; their"
          + " neighbours go to the one kept; canals through them are removed.";
  // more groups than this start folded, their pictures made only when opened
  private static final int FOLDED_FROM = 30;
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
      Runnable takeAll,
      Runnable takeNone) {}

  /** A new area's row: its name and kind, what is wrong with the name. */
  private record NewRow(
      DetectedTerritory area,
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

  /** The summary and a card per group of changes, and the choices made in them. */
  static Content content(final MapProject project, final MapFiles from, final Redrawn redrawn) {
    final BufferedImage picture = redrawn.files().picture().orElseThrow();
    final Form form = new Form(CARD_TEXT_WIDTH);
    form.setBorder(BorderFactory.createEmptyBorder());
    final List<NewRow> rows = new ArrayList<>();
    final Map<DetectedTerritory, JComboBox<String>> keepers = new LinkedHashMap<>();
    final Map<Group, JCheckBox> takes = new LinkedHashMap<>();
    // lines drawn into the map's own picture stay with Cancel; a picture from a file is not taken
    final boolean drawnHere = from.picture().equals(redrawn.files().picture());
    final List<Stroke> strokes = drawnHere ? project.drawnStrokes() : List.of();
    final Map<Group, List<String>> whyOff = new LinkedHashMap<>();
    redrawn.groups().forEach(group -> whyOff.put(group, whyOff(group, strokes, from, redrawn)));
    final String cancel =
        strokes.isEmpty()
            ? " Cancel changes nothing."
            : whyOff.values().stream().anyMatch(why -> !why.isEmpty())
                ? " Cancel keeps your lines: close a gap in Draw borders and make territories"
                    + " again."
                : " Cancel keeps your lines.";
    final Map<DetectedTerritory, String> names = names(project, redrawn, whyOff);
    final String nothing =
        drawnHere
            ? "The borders stay as they were."
            : "Takes the new picture; the borders stay as they were.";
    final JLabel summary = wrapped("", WIDTH);
    final Runnable check =
        () -> {
          showRefused(project, rows);
          summary.setText(
              html(summary(choices(rows, keepers, takes), takes.size(), nothing, cancel), WIDTH));
        };
    final JButton all = new JButton("All");
    final JButton none = new JButton("None");
    Theme.setTertiaryButtonStyle(all);
    Theme.setTertiaryButtonStyle(none);
    all.setToolTipText("Take every change");
    none.setToolTipText("Keep every change as it was");
    all.addActionListener(e -> takeAll(takes, true));
    none.addActionListener(e -> takeAll(takes, false));
    final List<JComponent> actions = new ArrayList<>();
    if (!redrawn.merged().isEmpty()) {
      actions.add(Help.button(REMOVED));
    }
    actions.addAll(List.of(all, none));
    form.row(
        heading(
            "Changes (" + redrawn.groups().size() + ")", 0, actions.toArray(JComponent[]::new)));
    form.row(secondary("Unticked, a change stays as it was: old borders, no merge, nothing new."));
    final JPanel cards = new JPanel();
    cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
    cards.setOpaque(false);
    final List<Runnable> pictures = new ArrayList<>();
    for (final Group group : redrawn.groups()) {
      if (cards.getComponentCount() > 0) {
        cards.add(Box.createVerticalStrut(Theme.GAP_S));
      }
      cards.add(
          groupCard(
              from,
              redrawn,
              group,
              whyOff.get(group),
              names,
              rows,
              keepers,
              takes,
              check,
              pictures));
    }
    if (redrawn.groups().size() < FOLDED_FROM) {
      pictures.forEach(Runnable::run);
    } else {
      final JButton fold = Theme.newFoldButton();
      final String label = "Show them (" + redrawn.groups().size() + ")";
      Theme.showFold(fold, label, false);
      cards.setVisible(false);
      fold.addActionListener(
          e -> {
            pictures.forEach(Runnable::run);
            pictures.clear();
            cards.setVisible(!cards.isVisible());
            Theme.showFold(fold, label, cards.isVisible());
            cards.revalidate();
          });
      form.row(fold);
    }
    form.row(cards);
    form.end();
    check.run();
    final JScrollPane scroll = SwingComponents.newJScrollPane(form);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    final JPanel root = new JPanel(new BorderLayout(0, Theme.GAP_M));
    root.setOpaque(false);
    root.add(summary, BorderLayout.NORTH);
    final JPanel body = new JPanel(new BorderLayout(0, Theme.GAP_M));
    body.setOpaque(false);
    body.add(scroll, BorderLayout.CENTER);
    // room the dialog has over is left below everything, not between the cards and the footer
    final JPanel hug = new JPanel(new BorderLayout());
    hug.setOpaque(false);
    hug.add(body, BorderLayout.NORTH);
    root.add(hug, BorderLayout.CENTER);
    int fixed = summary.getPreferredSize().height + Theme.GAP_M;
    if (!redrawn.merged().isEmpty()) {
      final JLabel footer = secondary("A territory still named in triggers keeps its old border.");
      body.add(footer, BorderLayout.SOUTH);
      fixed += footer.getPreferredSize().height + Theme.GAP_M;
    }
    scroll.setPreferredSize(
        new Dimension(WIDTH, Math.min(MAX_HEIGHT - fixed, form.getPreferredSize().height + 4)));
    return new Content(
        root,
        () -> refusedName(project, rows),
        () -> choices(rows, keepers, takes),
        () -> takeAll(takes, true),
        () -> takeAll(takes, false));
  }

  /**
   * Why a change starts off after Draw borders: no stroke touched it ({@link Stroke#crosses}); or
   * land runs where no erased stroke lets it ({@link Stroke#lets}), eg through a gap in a line: a
   * territory taking part of another, or territories becoming one. None without strokes, eg from
   * a new picture, where every change starts taken.
   */
  private static List<String> whyOff(
      final Group group, final List<Stroke> strokes, final MapFiles from, final Redrawn redrawn) {
    if (strokes.isEmpty()) {
      return List.of();
    }
    if (strokes.stream().noneMatch(stroke -> stroke.crosses(group, from, redrawn))) {
      return List.of(UNTOUCHED);
    }
    final List<String> leaks = new ArrayList<>();
    final Map<String, List<String>> mergeOf = new HashMap<>();
    for (final DetectedTerritory area : group.merged()) {
      final List<String> members = redrawn.merged().get(area);
      members.forEach(m -> mergeOf.put(m, members));
      leaks.addAll(unerased(members, strokes, from));
    }
    group.moves().stream()
        .filter(
            move ->
                mergeOf.getOrDefault(move.into(), List.of(move.into())).stream()
                    .noneMatch(
                        into ->
                            strokes.stream()
                                .anyMatch(s -> s.lets(new MapFiles.Move(into, move.from()), from))))
        .forEach(
            move ->
                leaks.add(
                    together(mergeOf.getOrDefault(move.into(), List.of(move.into())))
                        + " would take part of "
                        + move.from()
                        + " through a gap in the line."));
    return List.copyOf(leaks);
  }

  /**
   * What of a merge no erased stroke lets: its territories joined by the pairs an erased stroke
   * reaches both of; each set apart from the biggest would join it through a gap.
   */
  private static List<String> unerased(
      final List<String> members, final List<Stroke> strokes, final MapFiles from) {
    final Map<String, Set<String>> setOf = new LinkedHashMap<>();
    members.forEach(m -> setOf.put(m, new LinkedHashSet<>(List.of(m))));
    for (final String one : members) {
      for (final String other : members) {
        if (!one.equals(other)
            && setOf.get(one) != setOf.get(other)
            && strokes.stream().anyMatch(s -> s.lets(new MapFiles.Move(one, other), from))) {
          final Set<String> joined = setOf.get(one);
          joined.addAll(setOf.get(other));
          joined.forEach(m -> setOf.put(m, joined));
        }
      }
    }
    final List<Set<String>> sets = setOf.values().stream().distinct().toList();
    if (sets.size() == 1) {
      return List.of();
    }
    if (sets.size() == members.size()) {
      return List.of(together(members) + " would become one through a gap in the line.");
    }
    final Set<String> biggest =
        sets.stream().max(Comparator.comparingInt(Set::size)).orElseThrow();
    return sets.stream()
        .filter(set -> set != biggest)
        .map(
            set ->
                together(List.copyOf(set))
                    + " would join "
                    + together(List.copyOf(biggest))
                    + " through a gap in the line.")
        .toList();
  }

  /**
   * The new areas' names, those of the changes that start taken first, so they get the plain
   * ones, eg "New territory" before "New territory 2".
   */
  private static Map<DetectedTerritory, String> names(
      final MapProject project, final Redrawn redrawn, final Map<Group, List<String>> whyOff) {
    final Map<DetectedTerritory, String> names = new HashMap<>();
    final List<String> given = new ArrayList<>();
    Stream.concat(
            redrawn.groups().stream().filter(group -> startsTaken(group, whyOff.get(group))),
            redrawn.groups().stream().filter(group -> !startsTaken(group, whyOff.get(group))))
        .flatMap(group -> group.added().stream())
        .forEach(
            area -> {
              final String name =
                  project.freeName(area.water() ? "New sea zone" : "New territory", given);
              names.put(area, name);
              given.add(name);
            });
    return names;
  }

  // a new area over no old outline, eg a frame or a legend box, is no territory unless chosen
  private static boolean startsTaken(final Group group, final List<String> whyOff) {
    return whyOff.isEmpty() && !group.territories().isEmpty();
  }

  /**
   * A group's card: what taking it does, Take, why it starts off if it does, which territory keeps
   * each merged area and the new areas' rows, all off while it is not taken. Its picture is drawn
   * by the runnable it adds to pictures.
   */
  private static JComponent groupCard(
      final MapFiles from,
      final Redrawn redrawn,
      final Group group,
      final List<String> whyOff,
      final Map<DetectedTerritory, String> names,
      final List<NewRow> rows,
      final Map<DetectedTerritory, JComboBox<String>> keepers,
      final Map<Group, JCheckBox> takes,
      final Runnable check,
      final List<Runnable> pictures) {
    final JCheckBox take = new JCheckBox("Take", startsTaken(group, whyOff));
    take.getAccessibleContext().setAccessibleName("Take this change");
    takes.put(group, take);
    final List<JComponent> lines = new ArrayList<>();
    lines.add(line(take));
    lines.add(wrapped(whatTakeDoes(from, group, redrawn), CARD_TEXT_WIDTH));
    whyOff.forEach(why -> lines.add(secondary(why)));
    final List<JComponent> parts = new ArrayList<>();
    for (final DetectedTerritory area : group.merged()) {
      final JComboBox<String> keeper =
          new JComboBox<>(redrawn.merged().get(area).toArray(String[]::new));
      keeper.setToolTipText("Territory keeping the merged area");
      keeper.getAccessibleContext().setAccessibleName("Territory keeping the merged area");
      keeper.addItemListener(e -> check.run());
      keepers.put(area, keeper);
      lines.add(line(new JLabel("Merged area kept by"), keeper));
      parts.add(keeper);
    }
    for (final DetectedTerritory area : group.added()) {
      final NewRow row =
          newRow(area, names.get(area), group.territories().isEmpty(), check, lines);
      rows.add(row);
      parts.addAll(List.of(row.name(), row.kind()));
    }
    final Runnable enable =
        () -> {
          parts.forEach(part -> part.setEnabled(take.isSelected()));
          check.run();
        };
    take.addItemListener(e -> enable.run());
    parts.forEach(part -> part.setEnabled(take.isSelected()));
    final JLabel thumbnail = new JLabel();
    thumbnail.setPreferredSize(new Dimension(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT));
    pictures.add(
        () ->
            thumbnail.setIcon(
                thumbnail(
                    redrawn.files().picture().orElseThrow(),
                    Stream.of(
                            outlinesOf(redrawn.files(), group.territories()),
                            group.added().stream().flatMap(a -> a.outlines().stream()).toList())
                        .flatMap(List::stream)
                        .toList(),
                    outlinesOf(from, group.territories()))));
    return card(thumbnail, lines.toArray(JComponent[]::new));
  }

  /** "Canton splits in two; Champagne and Alsace become one; new border for Lorraine." */
  private static String whatTakeDoes(
      final MapFiles from, final Group group, final Redrawn redrawn) {
    final List<String> parts = new ArrayList<>();
    final Set<String> told = new HashSet<>();
    for (final DetectedTerritory area : group.merged()) {
      parts.add(together(redrawn.merged().get(area)) + " become one");
      told.addAll(redrawn.merged().get(area));
    }
    int elsewhere = 0;
    final Map<String, Integer> splits = new LinkedHashMap<>();
    for (final DetectedTerritory area : group.added()) {
      final Optional<String> in =
          group.territories().stream()
              .filter(
                  t ->
                      outlinesOf(from, List.of(t)).stream()
                          .anyMatch(o -> o.contains(area.center())))
              .findFirst();
      if (in.isPresent()) {
        splits.merge(in.get(), 1, Integer::sum);
      } else {
        elsewhere++;
      }
    }
    splits.forEach(
        (territory, n) -> {
          if (!told.contains(territory)) {
            parts.add(territory + (n == 1 ? " splits in two" : " splits into " + (n + 1)));
            told.add(territory);
          }
        });
    if (elsewhere > 0) {
      parts.add(count(elsewhere, "new area", "new areas"));
    }
    final List<String> rest = group.territories().stream().filter(t -> !told.contains(t)).toList();
    if (!rest.isEmpty()) {
      parts.add((rest.size() == 1 ? "new border for " : "new borders for ") + together(rest));
    }
    final String text = String.join("; ", parts) + ".";
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  private static List<Polygon> outlinesOf(final MapFiles files, final List<String> territories) {
    return territories.stream()
        .flatMap(t -> files.polygons().getOrDefault(t, List.of()).stream())
        .toList();
  }

  private static void takeAll(final Map<Group, JCheckBox> takes, final boolean take) {
    takes.values().forEach(box -> box.setSelected(take));
  }

  /**
   * "Takes 2 changes (1 new territory, 1 merge). 1 change stays as it was. Cancel…"
   *
   * @param nothing what taking nothing still does
   * @param cancel what Cancel does, a sentence after a space
   */
  private static String summary(
      final RedrawChoices choices, final int total, final String nothing, final String cancel) {
    final int kept = choices.asItWas().size();
    final int taken = total - kept;
    final List<String> parts = new ArrayList<>();
    if (!choices.added().isEmpty()) {
      parts.add(count(choices.added().size(), "new territory", "new territories"));
    }
    if (!choices.keepers().isEmpty()) {
      parts.add(count(choices.keepers().size(), "merge", "merges"));
    }
    return (taken == 0
            ? nothing
            : "Takes "
                + count(taken, "change", "changes")
                + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")")
                + "."
                + (kept == 0
                    ? ""
                    : kept == 1
                        ? " 1 change stays as it was."
                        : " " + kept + " changes stay as they were."))
        + cancel;
  }

  /** What a redraw taken after the dialog tells: a toast, and a warning only with its own news. */
  record News(String toast, Optional<String> warning) {}

  /**
   * The news of a redraw taken after the dialog: a toast counting what was taken and kept, and a
   * warning only for what the dialog could not show ({@link #notShown}).
   *
   * @param territories the game's territories after the redraw
   * @param stillNamed the territories kept as rules still name them, "territory: where"
   * @param canalsGone the canals removed with merged territories
   */
  static News news(
      final Redrawn redrawn,
      final RedrawChoices choices,
      final List<String> territories,
      final List<String> stillNamed,
      final List<String> canalsGone) {
    final Set<DetectedTerritory> notMerged = new HashSet<>();
    choices.asItWas().forEach(group -> notMerged.addAll(group.merged()));
    int removed = 0;
    for (final Map.Entry<DetectedTerritory, List<String>> merge : redrawn.merged().entrySet()) {
      if (!notMerged.contains(merge.getKey())) {
        final String keeper =
            choices.keepers().getOrDefault(merge.getKey(), merge.getValue().getFirst());
        removed +=
            (int)
                merge.getValue().stream()
                    .filter(m -> !m.equals(keeper) && !territories.contains(m))
                    .count();
      }
    }
    final int changed =
        (int)
            redrawn.groups().stream()
                .filter(group -> !choices.asItWas().contains(group))
                .flatMap(group -> group.territories().stream())
                .filter(territories::contains)
                .count();
    final List<String> parts = new ArrayList<>();
    if (changed > 0) {
      parts.add(count(changed, "territory changed", "territories changed"));
    }
    if (!choices.added().isEmpty()) {
      parts.add(count(choices.added().size(), "added", "added"));
    }
    if (removed > 0) {
      parts.add(count(removed, "merged into another", "merged into others"));
    }
    final int keptAsItWas = choices.asItWas().size();
    final String notShown = notShown(redrawn, stillNamed, canalsGone);
    return new News(
        (parts.isEmpty() ? "Borders as they were" : "Territories made: " + String.join(", ", parts))
            + (keptAsItWas == 0
                ? ""
                : "; " + count(keptAsItWas, "change kept as it was", "changes kept as they were")),
        notShown.isEmpty() ? Optional.empty() : Optional.of(notShown.strip() + LOOK));
  }

  /**
   * The report of a redraw taken without the dialog, as it found no change to choose: the borders
   * that changed and {@link #notShown}.
   */
  static String report(
      final Redrawn redrawn,
      final List<String> territories,
      final List<String> stillNamed,
      final List<String> canalsGone) {
    final List<String> borders = redrawn.changed().stream().filter(territories::contains).toList();
    return (borders.isEmpty()
            ? "No border changed noticeably."
            : "Borders changed: " + MapMakerWindow.names(borders) + ".")
        + notShown(redrawn, stillNamed, canalsGone)
        + LOOK;
  }

  /**
   * What the dialog does not show, each after a blank line: canals gone, territories still named
   * and those kept as the picture has no area of their own; empty without any.
   */
  private static String notShown(
      final Redrawn redrawn, final List<String> stillNamed, final List<String> canalsGone) {
    final Set<String> merged = new HashSet<>();
    redrawn.merged().values().forEach(merged::addAll);
    // a merged territory is told otherwise: it was removed, or kept its border as still named
    final List<String> kept = redrawn.kept().stream().filter(t -> !merged.contains(t)).toList();
    return (canalsGone.isEmpty()
            ? ""
            : "\n\nCanals removed with them: " + MapMakerWindow.names(canalsGone) + ".")
        + (stillNamed.isEmpty()
            ? ""
            : "\n\nKept their old border, still named: " + MapMakerWindow.names(stillNamed) + ".")
        + (kept.isEmpty()
            ? ""
            : "\n\nThese kept their border, as the picture has no area there of their own: "
                + MapMakerWindow.names(kept)
                + ".");
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

  /**
   * A new area's row, its lines added to the card's.
   *
   * @param outside whether it lies over no old outline, eg a frame or a legend box
   */
  private static NewRow newRow(
      final DetectedTerritory area,
      final String named,
      final boolean outside,
      final Runnable check,
      final List<JComponent> lines) {
    final JTextField name = new JTextField(named, 16);
    name.getAccessibleContext().setAccessibleName("Name of the new territory");
    final JComboBox<String> kind = new JComboBox<>(new String[] {LAND, SEA});
    kind.setSelectedItem(area.water() ? SEA : LAND);
    kind.setToolTipText("Land or sea zone");
    kind.getAccessibleContext().setAccessibleName("Land or sea zone");
    final FieldError error = new FieldError(name);
    OnTyping.attach(name, check);
    lines.add(line(new JLabel("Add as"), name, kind));
    lines.add(error);
    if (outside) {
      lines.add(secondary("Outside the old map, eg a frame or a legend box."));
    }
    return new NewRow(area, name, kind, error);
  }

  // a row adds its area when its group is taken
  private static boolean adds(final NewRow row) {
    return row.name().isEnabled();
  }

  /** Each ticked name the map cannot take, or that another ticked row has too, says so. */
  private static void showRefused(final MapProject project, final List<NewRow> rows) {
    final Set<String> names = new HashSet<>();
    for (final NewRow row : rows) {
      final boolean refused = adds(row) && isRefused(project, names, row);
      row.error().setProblem(refused ? Optional.of(NAME_REFUSED) : Optional.empty());
    }
  }

  /** The first ticked name the map cannot take or that another ticked row has too. */
  private static Optional<String> refusedName(final MapProject project, final List<NewRow> rows) {
    final Set<String> names = new HashSet<>();
    return rows.stream()
        .filter(RedrawDialog::adds)
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
  private static JComponent card(final JLabel thumbnail, final JComponent... lines) {
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
    card.add(thumbnail, BorderLayout.WEST);
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
    return new JLabel(html(text, width));
  }

  private static String html(final String text, final int width) {
    return "<html><div style='width:"
        + Theme.htmlWidth(width)
        + "'>"
        + SearchList.escape(text)
        + "</div></html>";
  }

  /**
   * The picture around the outlines, fitted into the thumbnail's box, at most at its own size; the
   * outlines in the accent color on a dark halo, those before them, if any, as a thin white line.
   */
  private static ImageIcon thumbnail(
      final BufferedImage picture, final List<Polygon> outlines, final List<Polygon> before) {
    final Rectangle around =
        Stream.concat(outlines.stream(), before.stream())
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
    outlines.forEach(g::draw);
    g.setColor(Theme.getAccentColor());
    g.setStroke(new BasicStroke((float) (3 / scale)));
    outlines.forEach(g::draw);
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke((float) (1 / scale)));
    before.forEach(g::draw);
    g.dispose();
    return new ImageIcon(image);
  }

  private static RedrawChoices choices(
      final List<NewRow> rows,
      final Map<DetectedTerritory, JComboBox<String>> keepers,
      final Map<Group, JCheckBox> takes) {
    final Map<DetectedTerritory, DetectedTerritory> added = new LinkedHashMap<>();
    for (final NewRow row : rows) {
      if (adds(row)) {
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
    keepers.forEach(
        (area, keeper) -> {
          if (keeper.isEnabled()) {
            kept.put(area, (String) keeper.getSelectedItem());
          }
        });
    final Set<Group> asItWas = new LinkedHashSet<>();
    takes.forEach(
        (group, take) -> {
          if (!take.isSelected()) {
            asItWas.add(group);
          }
        });
    return new RedrawChoices(added, kept, Collections.unmodifiableSet(asItWas));
  }
}
