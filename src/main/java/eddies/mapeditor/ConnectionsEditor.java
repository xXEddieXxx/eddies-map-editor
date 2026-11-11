package eddies.mapeditor;

import java.awt.BorderLayout;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * Which territories border each other: click one territory, then its neighbour to connect them or,
 * if they are, to part them.
 */
final class ConnectionsEditor implements Editor {
  private final MapProject project;
  private final MapView map;
  private final Form inspector = new Form(250);
  private final JComponent page;
  private @Nullable String from;

  ConnectionsEditor(final MapProject project, final MapView map) {
    this.project = project;
    this.map = map;
    page = SwingComponents.newJScrollPane(inspector);
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
    return "Which territories border each other; units move along these lines. Click a"
        + " territory, then a neighbour to connect them; click a connected one to part them.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    final JButton done = new JButton("Pick another territory");
    done.addActionListener(e -> choose(null));
    map.useFor(MapMode.CONNECTIONS, done, this::click);
    map.select(from);
  }

  @Override
  public void show(final String territory) {
    choose(territory);
    map.scrollTo(territory);
  }

  private void click(final String territory) {
    if (from == null || from.equals(territory)) {
      choose(territory);
    } else {
      final String first = from;
      final boolean connected = MapXml.neighbors(project.game(), first).contains(territory);
      project.edit(
          g ->
              connected
                  ? MapXml.withoutConnection(g, first, territory)
                  : MapXml.withConnection(g, first, territory));
    }
  }

  private void choose(@Nullable final String territory) {
    from = territory;
    map.select(territory);
    showInspector();
  }

  private void showInspector() {
    inspector.clear();
    if (from == null) {
      inspector
          .note("Click a territory on the map.")
          .note(MapXml.connections(project.game()).size() + " connections in this game.")
          .end();
      return;
    }
    final String territory = from;
    inspector.title(
        territory,
        "Click the territories that border it on the map; a click on one again parts them.");
    inspector.section("Neighbours");
    for (final String neighbour : MapXml.neighbors(project.game(), territory)) {
      final JPanel row = new JPanel(new BorderLayout(8, 0));
      row.add(new JLabel(neighbour), BorderLayout.CENTER);
      final JButton part = new JButton("Remove");
      part.setToolTipText("Parts " + territory + " and " + neighbour);
      part.addActionListener(
          e -> project.edit(g -> MapXml.withoutConnection(g, territory, neighbour)));
      row.add(part, BorderLayout.EAST);
      inspector.row(row);
    }
    inspector.end();
  }
}
