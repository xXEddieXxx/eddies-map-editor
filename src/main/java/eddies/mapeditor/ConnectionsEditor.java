package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapPicture.Neighbours;

/**
 * Which territories border each other: click one territory, then its neighbour to connect them or,
 * if they are, to part them. "Find neighbours again…" compares the connections with the outlines:
 * pairs that touch without a connection, and connections between territories that do not touch.
 */
final class ConnectionsEditor implements Editor {
  private static final int NEWS_MS = 3000;
  private static final String FIND_AGAIN = "Find neighbours again";
  private static final String TICK_ALL = "Tick all";
  private static final String UNTICK_ALL = "Untick all";
  private static final int DIALOG_WIDTH = 480;
  private static final int LIST_HEIGHT = 220;

  private final MapProject project;
  private final MapView map;
  private final Form inspector = new Form(250);
  private final JComponent page;
  // what a click did, shown in the banner for a while, then the instruction again
  private final Timer backToInstruction = new Timer(NEWS_MS, e -> showInstructionAfterNews());
  private @Nullable String from;
  private String news = "";

  ConnectionsEditor(final MapProject project, final MapView map) {
    this.project = project;
    this.map = map;
    page = SwingComponents.newJScrollPane(inspector);
    backToInstruction.setRepeats(false);
    project.onChange(this::showInspector);
    showInspector();
  }

  @Override
  public boolean usesMap() {
    return true;
  }

  @Override
  public String title() {
    return "Connections";
  }

  @Override
  public Glyph glyph() {
    return Glyph.CONNECTIONS;
  }

  @Override
  public String hint() {
    return "Click a territory, then its neighbours to connect or part them.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    map.useFor(MapMode.CONNECTIONS, new JPanel(), this::click);
    // opens on the overview of all connections; show(territory), eg from a problem, comes after
    choose(null);
  }

  @Override
  public void show(final String territory) {
    choose(territory);
    map.scrollTo(territory);
  }

  /** A click on the map: chooses the territory, or connects or parts it and the chosen one. */
  void click(final String territory) {
    if (from == null || from.equals(territory)) {
      choose(territory);
    } else if (MapXml.neighbors(project.game(), from).contains(territory)) {
      part(from, territory);
    } else {
      final String first = from;
      project.edit(g -> MapXml.withConnection(g, first, territory));
      showNews("Connected " + first + " and " + territory + ".");
    }
  }

  private void part(final String territory, final String neighbour) {
    project.edit(g -> MapXml.withoutConnection(g, territory, neighbour));
    showNews("Parted " + territory + " and " + neighbour + ".");
  }

  private void choose(@Nullable final String territory) {
    from = territory;
    map.select(territory);
    if (territory == null) {
      backToInstruction.stop();
      map.hideBanner();
    } else {
      showInstruction();
    }
    showInspector();
  }

  private void showInstruction() {
    backToInstruction.stop();
    map.showEditBanner(
        "Connecting "
            + from
            + ": click a territory to connect it or part it.",
        () -> choose(null));
  }

  private void showNews(final String text) {
    news = text;
    map.showEditBanner(text, () -> choose(null));
    backToInstruction.restart();
  }

  // only while the news still shows: another tool may have taken the map meanwhile
  private void showInstructionAfterNews() {
    if (from != null && map.bannerText().filter(news::equals).isPresent()) {
      showInstruction();
    }
  }

  private void showInspector() {
    inspector.clear();
    if (!project.getFiles().hasOutlines()) {
      inspector
          .row(
              Panels.empty(
                  Glyph.CONNECTIONS, "Connections need the map", "Load its picture first."))
          .end();
    } else if (from == null) {
      showOverview();
    } else {
      showNeighbours(from);
    }
    inspector.revalidate();
    inspector.repaint();
  }

  private void showOverview() {
    final int connections = MapXml.connections(project.game()).size();
    inspector
        .subtitle(connections + (connections == 1 ? " connection" : " connections"))
        .row(
            Panels.empty(
                Glyph.CONNECTIONS,
                "Click a territory",
                "Then click its neighbours to connect or part them."));
    if (project.getFiles().hasOutlines()) {
      final JButton find = new JButton("Find neighbours again…");
      Theme.setSecondaryButtonStyle(find);
      find.setToolTipText(
          "Compares the connections with the territories' outlines: which touch without a"
              + " connection, and which connections join territories that do not touch");
      find.addActionListener(e -> findAgain());
      final JPanel line = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
      line.setOpaque(false);
      line.add(find);
      inspector.row(line);
    }
    final List<Problem> problems =
        project.problems().stream()
            .filter(p -> p.tool().filter(title()::equals).isPresent())
            .toList();
    if (!problems.isEmpty()) {
      inspector.section("Problems");
      problems.forEach(p -> inspector.row(problemRow(p, Theme.Status.PROBLEM)));
    }
    // territories without a neighbour: no problem, many finished maps have one on purpose
    final List<Problem> notes = MapChecks.notes(project.game());
    if (!notes.isEmpty() && connections > 0) {
      inspector.section("Worth a look");
      notes.forEach(p -> inspector.row(problemRow(p, Theme.Status.TODO)));
    }
    inspector.end();
  }

  // ---- find neighbours again: the connections against the outlines

  /**
   * What the outlines say against the game's connections.
   *
   * @param missing pairs whose outlines touch but that are not connected: likely forgotten
   * @param apart connections between territories that do not touch: meant, eg a sea route or a
   *     canal, or a mistake
   */
  record Found(List<Neighbours> missing, List<Neighbours> apart) {}

  /**
   * Compares the game's connections with the outlines; territories without outlines are left
   * out.
   */
  static Found find(final Game game, final Map<String, List<Polygon>> polygons) {
    final Map<String, List<Polygon>> outlines = new LinkedHashMap<>();
    MapXml.territories(game).stream()
        .filter(t -> !polygons.getOrDefault(t, List.of()).isEmpty())
        .forEach(t -> outlines.put(t, polygons.get(t)));
    final List<Neighbours> touching = OutlineCut.touching(outlines);
    // seen: a pair touches, or its connection is listed; a game may write one both ways round
    final Set<Set<String>> seen =
        touching.stream().map(n -> Set.of(n.t1(), n.t2())).collect(Collectors.toSet());
    return new Found(
        touching.stream().filter(n -> !MapXml.neighbors(game, n.t1()).contains(n.t2())).toList(),
        MapXml.connections(game).stream()
            .filter(c -> outlines.containsKey(c.getT1()) && outlines.containsKey(c.getT2()))
            .filter(c -> !c.getT1().equals(c.getT2()))
            .filter(c -> seen.add(Set.of(c.getT1(), c.getT2())))
            .map(c -> new Neighbours(c.getT1(), c.getT2()))
            .toList());
  }

  /** The game with the pairs connected and the others parted. */
  static Game applied(
      final Game game, final List<Neighbours> connect, final List<Neighbours> part) {
    Game changed = game;
    for (final Neighbours pair : connect) {
      changed = MapXml.withConnection(changed, pair.t1(), pair.t2());
    }
    for (final Neighbours pair : part) {
      changed = MapXml.withoutConnection(changed, pair.t1(), pair.t2());
    }
    return changed;
  }

  /** Looks in the background, as a big map takes a while; applied only to the map looked at. */
  private void findAgain() {
    final Game game = project.game();
    final MapFiles files = project.getFiles();
    Dialogs.toast(page, "Looking for neighbours on the map…");
    new SwingWorker<Found, Void>() {
      @Override
      protected Found doInBackground() {
        return find(game, files.polygons());
      }

      @Override
      protected void done() {
        final Found found;
        try {
          found = get();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (final ExecutionException e) {
          Dialogs.warn(page, FIND_AGAIN, String.valueOf(e.getCause().getMessage()));
          return;
        }
        if (project.getFiles() != files || project.game() != game) {
          Dialogs.toast(
              page, "! The map changed while neighbours were looked for. Find them again.");
          return;
        }
        if (found.missing().isEmpty() && found.apart().isEmpty()) {
          Dialogs.toast(page, "✓ All territories that touch are connected, and no others");
          return;
        }
        final Choices choices = newChoices(found);
        if (Dialogs.ask(page, FIND_AGAIN, choices.content(), "Change connections")) {
          // one step for all of them
          project.edit(g -> applied(g, choices.connect().get(), choices.part().get()));
        }
      }
    }.execute();
  }

  /** The dialog's inside, and what is ticked in it. */
  record Choices(
      JComponent content, Supplier<List<Neighbours>> connect, Supplier<List<Neighbours>> part) {}

  /**
   * The pairs to connect and the connections to review, none ticked: on a map drawn by hand many
   * pairs only nearly touch or are apart on purpose, and a connection changes how the map plays.
   */
  static Choices newChoices(final Found found) {
    final JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setOpaque(false);
    final Supplier<List<Neighbours>> connect =
        addList(
            content,
            found.missing(),
            found.missing().size() == 1
                ? "1 pair touches but is not connected"
                : found.missing().size() + " pairs touch but are not connected",
            "Tick the pairs that are truly neighbours: they get a connection. Leave one that"
                + " should stay apart, eg sea that only meets a corner of the coast.");
    final Supplier<List<Neighbours>> part =
        addList(
            content,
            found.apart(),
            found.apart().size() == 1
                ? "1 connection joins territories that do not touch"
                : found.apart().size() + " connections join territories that do not touch",
            "Often meant so, eg a sea route or a canal. Tick only a mistake: ticked connections"
                + " are removed.");
    return new Choices(content, connect, part);
  }

  private static Supplier<List<Neighbours>> addList(
      final JPanel content,
      final List<Neighbours> pairs,
      final String title,
      final String line) {
    if (pairs.isEmpty()) {
      return List::of;
    }
    final JLabel heading = new JLabel(title);
    heading.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
    final JLabel note = Theme.newSecondaryLabel(line);
    final JPanel ticks = new JPanel(new GridLayout(0, 1));
    final List<JCheckBox> boxes =
        pairs.stream()
            .map(pair -> new JCheckBox(pair.t1() + "  —  " + pair.t2()))
            .toList();
    boxes.forEach(ticks::add);
    final JButton all = new JButton(TICK_ALL);
    Theme.setTertiaryButtonStyle(all);
    all.addActionListener(
        e -> {
          final boolean tick = TICK_ALL.equals(all.getText());
          boxes.forEach(box -> box.setSelected(tick));
          all.setText(tick ? UNTICK_ALL : TICK_ALL);
        });
    final JPanel head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.add(heading, BorderLayout.WEST);
    head.add(all, BorderLayout.EAST);
    head.setMaximumSize(new Dimension(DIALOG_WIDTH, head.getPreferredSize().height));
    final JScrollPane scroll = SwingComponents.newJScrollPane(ticks);
    scroll.setPreferredSize(
        new Dimension(DIALOG_WIDTH, Math.min(LIST_HEIGHT, ticks.getPreferredSize().height + 8)));
    if (content.getComponentCount() > 0) {
      content.add(Box.createVerticalStrut(Theme.GAP_L));
    }
    for (final JComponent part : List.of(head, note, scroll)) {
      part.setAlignmentX(Component.LEFT_ALIGNMENT);
      content.add(part);
      content.add(Box.createVerticalStrut(Theme.GAP_XS));
    }
    return () ->
        IntStream.range(0, pairs.size())
            .filter(i -> boxes.get(i).isSelected())
            .mapToObj(pairs::get)
            .toList();
  }

  private JPanel problemRow(final Problem problem, final Theme.Status status) {
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    // wrapped, as territory names can be long
    final JLabel text =
        new JLabel(
            "<html><div style='width:"
                + Theme.htmlWidth(200)
                + "'>"
                + SearchList.escape(status.mark() + " " + problem.text())
                + "</div></html>");
    if (status == Theme.Status.PROBLEM) {
      text.setForeground(status.color());
    }
    row.add(text, BorderLayout.CENTER);
    problem
        .territory()
        .ifPresent(
            territory -> {
              final JButton show = new JButton("Show");
              Theme.setTertiaryButtonStyle(show);
              show.setToolTipText("Choose " + territory + " and show it on the map");
              show.addActionListener(e -> show(territory));
              row.add(show, BorderLayout.EAST);
            });
    return row;
  }

  private void showNeighbours(final String territory) {
    final Set<String> neighbours = MapXml.neighbors(project.game(), territory);
    inspector
        .title(territory)
        .subtitle(neighbours.size() + (neighbours.size() == 1 ? " neighbour" : " neighbours"));
    for (final String neighbour : neighbours) {
      final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
      row.setOpaque(false);
      final JButton name = new JButton(neighbour);
      Theme.setTertiaryButtonStyle(name);
      name.setHorizontalAlignment(JButton.LEADING);
      name.setToolTipText("Choose " + neighbour);
      name.addActionListener(e -> choose(neighbour));
      row.add(name, BorderLayout.CENTER);
      row.add(
          Theme.newIconButton(
              "×",
              "Part " + territory + " and " + neighbour,
              () -> part(territory, neighbour)),
          BorderLayout.EAST);
      inspector.row(row);
    }
    if (MapXml.isImpassable(project.game(), territory)) {
      // two lines: one is too wide for the inspector at 1280 px, and a row clips what wraps
      inspector.note("Impassable: it may stay without neighbours.");
      inspector.row(Help.link("Set in", "Territories", territory));
    }
    inspector.note("Click a territory on the map to add it.").end();
  }
}
