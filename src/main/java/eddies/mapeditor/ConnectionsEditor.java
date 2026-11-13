package eddies.mapeditor;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;

/**
 * Which territories border each other: click one territory, then its neighbour to connect them or,
 * if they are, to part them.
 */
final class ConnectionsEditor implements Editor {
  private static final int NEWS_MS = 3000;

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
