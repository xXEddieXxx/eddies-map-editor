package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.CanalXml.Canal;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * The canals: a canal joins two sea zones, and ships pass it only while their side holds its land
 * territories. Choose or make one, then click its sea zones and land territories on the map.
 */
final class CanalsEditor implements Editor {
  private final MapProject project;
  private final MapView map;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(250);
  private @Nullable String selected;
  // a new canal before its first sea zone: the game has no place for it until then
  private Optional<Canal> draft = Optional.empty();

  CanalsEditor(final MapProject project, final MapView map) {
    this.project = project;
    this.map = map;
    list = new SearchList("Search canal", this::names, this::detail, this::chooseInList);
    final JButton add = new JButton("New canal...");
    add.setToolTipText("A new canal: name it, then click its sea zones and land on the map");
    add.addActionListener(e -> addCanal());
    final JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    addRow.add(add);
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(addRow, BorderLayout.SOUTH);
    page.add(
        Panels.stacked(listColumn, SwingComponents.newJScrollPane(inspector)),
        BorderLayout.CENTER);
    project.onChange(this::refresh);
    showInspector();
  }

  @Override
  public boolean usesMap() {
    return true;
  }

  @Override
  public String title() {
    return "Canals";
  }

  @Override
  public Glyph glyph() {
    return Glyph.CANALS;
  }

  @Override
  public String hint() {
    return "Canals and straits, eg Suez: ships pass between its two sea zones only while their"
        + " side holds its land territories. Choose a canal or make a new one, then click its sea"
        + " zones and land territories on the map; a click again takes one out.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    map.useFor(MapMode.GAME, new JPanel(), this::click);
    map.select(null);
    markOnMap();
  }

  private List<String> names() {
    final List<String> names =
        new ArrayList<>(CanalXml.canals(project.game()).stream().map(Canal::name).toList());
    draft.map(Canal::name).filter(name -> !names.contains(name)).ifPresent(names::add);
    return names;
  }

  private String detail(final String name) {
    return canal(name).map(c -> String.join(" · ", territories(c))).orElse("");
  }

  /** The canal's sea zones, then its land territories. */
  private static List<String> territories(final Canal canal) {
    return Stream.concat(canal.seaZones().stream(), canal.lands().stream()).toList();
  }

  /** The canal of the name in the game, else the new one being made. */
  private Optional<Canal> canal(final String name) {
    return CanalXml.canals(project.game()).stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .or(() -> draft.filter(d -> d.name().equals(name)));
  }

  /** A choice in the list also brings the canal into view, at its first sea zone or land. */
  private void chooseInList(final String name) {
    choose(name);
    canal(name).flatMap(c -> territories(c).stream().findFirst()).ifPresent(map::scrollTo);
  }

  private void choose(final String name) {
    selected = name;
    list.choose(name);
    refresh();
  }

  private void refresh() {
    list.refresh();
    showInspector();
    // the map is shared: only while this tool shows it
    if (page.isShowing()) {
      markOnMap();
    }
  }

  private void markOnMap() {
    map.mark(
        Optional.ofNullable(selected)
            .flatMap(this::canal)
            .map(c -> Set.copyOf(territories(c)))
            .orElse(Set.of()));
  }

  /** Adds or takes out the territory clicked; without a chosen canal, chooses the one there. */
  private void click(final String territory) {
    final Optional<Canal> shown = Optional.ofNullable(selected).flatMap(this::canal);
    if (shown.isEmpty()) {
      CanalXml.canals(project.game()).stream()
          .filter(c -> c.seaZones().contains(territory) || c.lands().contains(territory))
          .findFirst()
          .ifPresent(c -> choose(c.name()));
      return;
    }
    final Canal canal = shown.get();
    if (MapXml.isWater(project.game(), territory)) {
      change(
          canal,
          new Canal(
              canal.name(),
              toggled(canal.seaZones(), territory),
              canal.lands(),
              canal.excludedUnits()));
    } else {
      change(
          canal,
          new Canal(
              canal.name(),
              canal.seaZones(),
              toggled(canal.lands(), territory),
              canal.excludedUnits()));
    }
  }

  private static List<String> toggled(final List<String> names, final String name) {
    return names.contains(name)
        ? names.stream().filter(n -> !n.equals(name)).toList()
        : Stream.concat(names.stream(), Stream.of(name)).toList();
  }

  /** The canal changed as one step; one left without sea zones stays here until it has one. */
  private void change(final Canal old, final Canal changed) {
    draft = changed.seaZones().isEmpty() ? Optional.of(changed) : Optional.empty();
    selected = changed.name();
    project.edit(g -> CanalXml.withCanal(g, old.name(), changed));
    // a change to a canal without sea zones is none to the game
    refresh();
  }

  private void addCanal() {
    final String name =
        Optional.ofNullable(
                JOptionPane.showInputDialog(
                    page, "Name of the new canal:", "New canal", JOptionPane.PLAIN_MESSAGE))
            .map(String::strip)
            .orElse("");
    if (name.isEmpty()) {
      return;
    }
    if (canal(name).isPresent()) {
      JOptionPane.showMessageDialog(
          page, "There is a canal of that name.", "New canal", JOptionPane.WARNING_MESSAGE);
      return;
    }
    draft = Optional.of(new Canal(name, List.of(), List.of(), List.of()));
    choose(name);
  }

  private void showInspector() {
    inspector.clear();
    final Optional<Canal> shown = Optional.ofNullable(selected).flatMap(this::canal);
    if (shown.isEmpty()) {
      inspector
          .note(
              CanalXml.canals(project.game()).isEmpty()
                  ? "No canals yet. Make one with New canal."
                  : "Choose a canal in the list or click one on the map.")
          .end();
      return;
    }
    final Canal canal = shown.get();
    final Game game = project.game();
    inspector.title(
        canal.name(),
        "Click its two sea zones and the land territories on the map; a click again takes one"
            + " out. Ships pass between the sea zones while their side holds all its land.");
    final JTextField name = new JTextField(canal.name());
    name.getAccessibleContext().setAccessibleName("Name of the canal");
    final JButton rename = new JButton("Rename");
    rename.setToolTipText("Gives the canal the name typed, on both sea zones");
    name.addActionListener(e -> rename(canal, name.getText()));
    rename.addActionListener(e -> rename(canal, name.getText()));
    final JPanel naming = new JPanel(new BorderLayout(6, 0));
    naming.add(name, BorderLayout.CENTER);
    naming.add(rename, BorderLayout.EAST);
    inspector.field("Name", naming);
    if (canal.seaZones().size() != 2 || canal.lands().isEmpty()) {
      inspector.note("A canal needs two sea zones and at least one land territory.");
    }
    inspector.section("Sea zones");
    for (final String seaZone : canal.seaZones()) {
      inspector.row(
          removableRow(
              seaZone,
              () ->
                  change(
                      canal,
                      new Canal(
                          canal.name(),
                          toggled(canal.seaZones(), seaZone),
                          canal.lands(),
                          canal.excludedUnits()))));
    }
    inspector.section("Land territories", "Ships pass only while their side holds all of them.");
    for (final String land : canal.lands()) {
      inspector.row(
          removableRow(
              land,
              () ->
                  change(
                      canal,
                      new Canal(
                          canal.name(),
                          canal.seaZones(),
                          toggled(canal.lands(), land),
                          canal.excludedUnits()))));
    }
    inspector.section(
        "Pass freely",
        "Unit types the canal does not stop, eg submarines diving through. Without a choice the"
            + " game lets air units pass.");
    final List<String> free = CanalXml.passFreely(game, canal);
    inspector.note(
        canal.excludedUnits().isEmpty()
            ? "Air units, the game's default"
            : SearchList.escape(free.isEmpty() ? "None" : String.join(", ", free)));
    final JButton choose = new JButton("Choose...");
    choose.setToolTipText("Choose the unit types that pass freely");
    choose.addActionListener(
        e ->
            Panels.pick(page, "Units that pass freely", MapXml.unitTypes(game), free)
                .ifPresent(
                    units ->
                        change(
                            canal,
                            new Canal(
                                canal.name(),
                                canal.seaZones(),
                                canal.lands(),
                                CanalXml.excludedUnits(game, units)))));
    final JButton remove = new JButton("Remove canal");
    remove.setToolTipText("Removes it from both sea zones. Undo takes it back");
    remove.addActionListener(
        e -> {
          draft = Optional.empty();
          project.edit(g -> CanalXml.withoutCanal(g, canal.name()));
          refresh();
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    buttons.add(choose);
    inspector.row(buttons);
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);
    inspector.end();
  }

  private static JPanel removableRow(final String name, final Runnable remove) {
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    row.add(new JLabel(name), BorderLayout.CENTER);
    final JButton button = new JButton("Remove");
    button.setToolTipText("Takes " + name + " out of the canal");
    button.addActionListener(e -> remove.run());
    row.add(button, BorderLayout.EAST);
    return row;
  }

  private void rename(final Canal canal, final String typed) {
    final String renamed = typed.strip();
    if (renamed.equals(canal.name())) {
      return;
    }
    if (renamed.isEmpty() || canal(renamed).isPresent()) {
      JOptionPane.showMessageDialog(
          page,
          "The name must not be empty or another canal's.",
          "Rename",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    change(canal, new Canal(renamed, canal.seaZones(), canal.lands(), canal.excludedUnits()));
  }
}
