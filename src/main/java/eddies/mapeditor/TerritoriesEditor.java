package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.swing.FileChooser;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapProject.Renamed;

/**
 * The main editor: every territory with its kind, owner, value, capital, victory city and the units
 * it starts with. A click on the map picks a territory, or paints the brush onto it.
 */
final class TerritoriesEditor implements Editor {
  // first in the owner boxes; told by its place, as a map may have a nation called "Neutral"
  private static final String NO_OWNER = "(no owner)";
  private static final List<Integer> VALUES = List.of(0, 1, 2, 3, 4, 5, 6, 8, 10, 12);
  // the width of the decoration box: G40's misc folder holds dozens of long names
  private static final String DECORATION_WIDTH = "a_decoration_name.png";

  private final MapProject project;
  private final MapView map;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(250);
  private final JComboBox<Brush> brush = new JComboBox<>();
  // the territory whose units the copy brush copies, taken when the brush is chosen
  private @Nullable String copySource;
  private final JLabel copyFrom = new JLabel(" ");
  private final Brush copyUnits =
      new Brush(
          "Copy units of the chosen territory",
          (g, t) -> copySource == null ? g : MapXml.withUnitsCopied(g, copySource, t));
  // shows the chosen territory's places on the map to move; a click then only selects
  private final Brush movePlaces = new Brush("Move places", null);
  // places the picture chosen in the box beside it where the map is clicked
  private final Brush placeDecorations = new Brush("Place decorations", null);
  private final JComboBox<String> decorationPicture = new JComboBox<>();
  private boolean refilling;
  private @Nullable String selected;
  private boolean editingHere;

  TerritoriesEditor(final MapProject project, final MapView map) {
    this.project = project;
    this.map = map;
    list =
        new SearchList(
            "Search territory or owner",
            () -> MapXml.territories(project.game()),
            this::detail,
            this::choose);
    final JScrollPane inspectorScroll = SwingComponents.newJScrollPane(inspector);
    final JPopupMenu namesMenu = new JPopupMenu();
    final JMenuItem importNames = namesMenu.add("Import names...");
    importNames.setToolTipText("Renames territories by a text or CSV file of lines old;new");
    importNames.addActionListener(e -> importNames());
    final JMenuItem exportNames = namesMenu.add("Export names...");
    exportNames.setToolTipText("Writes the names as lines name; to fill in the new names");
    exportNames.addActionListener(e -> exportNames());
    final JButton names = new JButton("Names ▾");
    names.setToolTipText("Rename many territories at once with a file");
    names.addActionListener(e -> namesMenu.show(names, 0, names.getHeight()));
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(
        Help.with(
            names,
            "Rename many territories at once: Export names writes a list to fill in, eg in a"
                + " spreadsheet; Import names reads it back as lines old;new."),
        BorderLayout.SOUTH);
    page.add(Panels.stacked(listColumn, inspectorScroll), BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (copySource != null && !MapXml.territories(project.game()).contains(copySource)) {
            // the copied territory is gone, eg renamed or undone
            copySource = null;
            copyFrom.setText(" ");
            brush.setSelectedIndex(0);
          }
          fillBrushes();
          if (!editingHere) {
            showInspector();
          }
        });
    fillBrushes();
    brush.addActionListener(e -> brushChosen());
    decorationPicture.setPrototypeDisplayValue(DECORATION_WIDTH);
    decorationPicture.addActionListener(
        e -> {
          if (!refilling) {
            editWithBrush();
          }
        });
    showInspector();
  }

  @Override
  public boolean usesMap() {
    return true;
  }

  @Override
  public String title() {
    return "Territories";
  }

  @Override
  public Glyph glyph() {
    return Glyph.TERRITORIES;
  }

  @Override
  public String hint() {
    return "Each territory with its owner, value, capital, victory city and starting units. Click"
        + " one on the map or in the list to change it here, or pick a brush above the map and"
        + " paint owners, values or units onto it: the copy brush copies the units of the territory"
        + " chosen when it is picked. Move places shows where the game draws the chosen"
        + " territory's units, name and markers, to move them.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    tools.add(copyFrom);
    tools.add(decorationPicture);
    tools.add(
        Help.with(
            brush,
            "What a click on the map does: Select opens the territory here; a brush paints an"
                + " owner or a value onto each territory clicked, owners only on land. Copy units"
                + " gives each territory clicked the starting units of the territory chosen"
                + " before, the copied nation's units turned to the new owner's; Clear units"
                + " takes them away. Move places shows the chosen territory's places: drag one"
                + " to move it, click into the territory to add a place for units, right-click"
                + " one or press Delete over it to remove it. Place decorations puts the picture"
                + " chosen beside it, from the map's misc folder, where you click, anywhere on the"
                + " map; drag one to move it, right-click or Delete removes it."));
    map.useFor(MapMode.GAME, tools, this::clickOnMap);
    editWithBrush();
    map.select(selected);
  }

  @Override
  public void show(final String territory) {
    choose(territory);
    list.choose(territory);
    map.scrollTo(territory);
  }

  private String detail(final String territory) {
    final Game game = project.game();
    final String kind =
        MapXml.isWater(game, territory)
            ? "Sea zone"
            : MapXml.owner(game, territory).orElse(NO_OWNER);
    final int value = MapXml.production(game, territory);
    return kind
        + (value > 0 ? " · " + value + " PUs" : "")
        + (project.isChanged(territory) ? " ●" : "");
  }

  private void clickOnMap(final String territory) {
    final Brush chosen = (Brush) brush.getSelectedItem();
    if (chosen != null && chosen.paint() != null) {
      project.edit(game -> chosen.paint().apply(game, territory));
    }
    choose(territory);
    list.choose(territory);
  }

  private void choose(final String territory) {
    selected = territory;
    map.select(territory);
    showInspector();
  }

  /** The brushes: select only, an owner for each nation or none, a value. */
  private void fillBrushes() {
    final Object chosen = brush.getSelectedItem();
    final List<Brush> brushes = new ArrayList<>();
    brushes.add(new Brush("Select", null));
    // owners paint land only: a sweep across the map leaves the sea zones as they are
    for (final String nation : MapXml.nations(project.game())) {
      brushes.add(
          new Brush(
              "Paint owner: " + nation,
              (g, t) -> MapXml.isWater(g, t) ? g : MapXml.withOwner(g, t, nation)));
    }
    brushes.add(
        new Brush(
            "Paint owner: none",
            (g, t) -> MapXml.isWater(g, t) ? g : MapXml.withOwner(g, t, null)));
    for (final int value : VALUES) {
      brushes.add(
          new Brush(
              "Paint value: " + value + " PUs", (g, t) -> MapXml.withProduction(g, t, value)));
    }
    brushes.add(copyUnits);
    brushes.add(new Brush("Clear units", (g, t) -> MapXml.withTerritoryUnits(g, t, List.of())));
    brushes.add(movePlaces);
    // the pictures in the map's misc folder, placed anywhere on the map as the game draws them
    // and those decorations.txt names but misc lacks, so their decorations can still be moved
    final Set<String> named = new TreeSet<>(project.getFiles().miscPictures());
    named.addAll(project.getFiles().places().decorations().keySet());
    final List<String> pictures = List.copyOf(named);
    if (!pictures.isEmpty()) {
      brushes.add(placeDecorations);
    }
    fillDecorationPictures(pictures);
    // by title, as nations come and go
    final List<String> shown =
        IntStream.range(0, brush.getItemCount()).mapToObj(i -> brush.getItemAt(i).title()).toList();
    if (!brushes.stream().map(Brush::title).toList().equals(shown)) {
      refilling = true;
      brush.removeAllItems();
      brushes.forEach(brush::addItem);
      brush.setSelectedItem(chosen == null ? brushes.get(0) : chosen);
      refilling = false;
    }
  }

  /** The misc pictures in the decoration box, keeping the one chosen. */
  private void fillDecorationPictures(final List<String> pictures) {
    final List<String> shown =
        IntStream.range(0, decorationPicture.getItemCount())
            .mapToObj(decorationPicture::getItemAt)
            .toList();
    if (!pictures.equals(shown)) {
      final Object chosen = decorationPicture.getSelectedItem();
      refilling = true;
      decorationPicture.removeAllItems();
      pictures.forEach(decorationPicture::addItem);
      if (chosen != null) {
        decorationPicture.setSelectedItem(chosen);
      }
      refilling = false;
      if (!Objects.equals(chosen, decorationPicture.getSelectedItem())) {
        // the chosen picture is gone from misc: the map places the one chosen now
        editWithBrush();
      }
    }
  }

  /** The map shows what the chosen brush moves: the chosen territory's places or decorations. */
  private void editWithBrush() {
    final boolean decorating = placeDecorations.equals(brush.getSelectedItem());
    decorationPicture.setVisible(decorating);
    decorationPicture.setToolTipText(
        "The picture a click places: " + decorationPicture.getSelectedItem());
    if (decorating && decorationPicture.getSelectedItem() instanceof String picture) {
      map.editDecorations(picture);
    } else if (movePlaces.equals(brush.getSelectedItem())) {
      map.editPlaces();
    } else {
      map.stopEditingPlaces();
    }
  }

  /** The copy brush takes the territory chosen now as the one whose units it copies. */
  private void brushChosen() {
    if (refilling) {
      return;
    }
    editWithBrush();
    if (brush.getSelectedItem() != copyUnits) {
      copyFrom.setText(" ");
      return;
    }
    if (selected == null) {
      JOptionPane.showMessageDialog(
          page,
          "First choose the territory whose units to copy, on the map or in the list.",
          "Copy units",
          JOptionPane.INFORMATION_MESSAGE);
      brush.setSelectedIndex(0);
      return;
    }
    copySource = selected;
    copyFrom.setText("from " + selected);
  }

  /** What a click on the map does, null to only select. */
  private record Brush(String title, @Nullable BiFunction<Game, String, Game> paint) {
    @Override
    public boolean equals(final Object other) {
      return other instanceof Brush b && b.title.equals(title);
    }

    @Override
    public int hashCode() {
      return title.hashCode();
    }

    @Override
    public String toString() {
      return title;
    }
  }

  private void showInspector() {
    inspector.clear();
    if (selected == null || !MapXml.territories(project.game()).contains(selected)) {
      inspector.note("Choose a territory on the map or in the list.").end();
      return;
    }
    final String territory = selected;
    final Game game = project.game();
    inspector.title(territory);
    final JTextField name = new JTextField(territory);
    final JButton rename = new JButton("Rename");
    name.addActionListener(e -> rename(territory, name.getText()));
    rename.addActionListener(e -> rename(territory, name.getText()));
    final JPanel naming = new JPanel(new BorderLayout(6, 0));
    naming.add(name, BorderLayout.CENTER);
    naming.add(rename, BorderLayout.EAST);
    inspector.field("Name", naming);
    if (project.isChanged(territory)) {
      inspector.note("● Changed since the last save");
    }
    inspector.section("Territory");
    final JRadioButton land = new JRadioButton("Land", !MapXml.isWater(game, territory));
    final JRadioButton sea = new JRadioButton("Sea zone", MapXml.isWater(game, territory));
    final ButtonGroup kind = new ButtonGroup();
    kind.add(land);
    kind.add(sea);
    land.addActionListener(e -> project.edit(g -> MapXml.withLand(g, territory)));
    sea.addActionListener(e -> project.edit(g -> MapXml.withWater(g, territory)));
    final JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    kinds.add(land);
    kinds.add(sea);
    inspector.row(kinds);

    final Optional<String> owner = MapXml.owner(game, territory);
    final JComboBox<String> owners = newOwnerBox(owner.orElse(null));
    owners.addActionListener(
        e -> project.edit(g -> MapXml.withOwner(g, territory, chosenOwner(owners))));
    inspector.field("Owner at the start", owners);
    inspector.field(
        "PUs per turn",
        "What the territory gives its owner each turn to buy units with.",
        Form.newNumber(
            MapXml.production(game, territory),
            999,
            value -> edit(g -> MapXml.withProduction(g, territory, value))));

    final JCheckBox capital =
        new JCheckBox("Capital of its owner", MapXml.isCapital(game, territory));
    capital.setEnabled(owner.isPresent() || capital.isSelected());
    capital.addActionListener(
        e ->
            project.edit(
                g ->
                    MapXml.withCapital(
                        g, territory, capital.isSelected() ? owner.orElse(null) : null)));
    inspector.row(capital);
    final JCheckBox victoryCity =
        new JCheckBox("Victory city", MapXml.isVictoryCity(game, territory));
    victoryCity.addActionListener(
        e ->
            project.edit(
                g ->
                    victoryCity.isSelected()
                        ? MapXml.withVictoryCity(g, territory)
                        : MapXml.withoutVictoryCity(g, territory)));
    inspector.row(victoryCity);

    inspector.section("Units at the start");
    for (final UnitPlacement units : MapXml.units(game, territory)) {
      inspector.row(unitRow(territory, units));
    }
    inspector.row(addUnitsRow(territory, owner.orElse(null)));

    inspector.section(
        "Places",
        "Where the game draws the units, the name, the value, the capital's flag, the victory"
            + " city marker, a sea zone's convoy, blockade, kamikaze and comment markers and the"
            + " territory's effects. Pick Move places above the map to move them; the game fills"
            + " the places for units in their order. Effects without places show side by side;"
            + " once the map has territory_effects.txt a save writes them so, else the game"
            + " stacks them on the center. Find again finds them inside the territory.");
    final JButton findPlaces = new JButton("Find again");
    findPlaces.setToolTipText("Finds the territory's places again inside its border");
    findPlaces.addActionListener(e -> project.findPlacesAgain(territory));
    inspector.row(findPlaces);

    inspector.section(
        "Neighbours", "The territories units can move to from here. Change them in Connections.");
    inspector.note(SearchList.escape(String.join(", ", MapXml.neighbors(game, territory))));
    inspector.end();
  }

  private void rename(final String old, final String typed) {
    final String renamed = typed.strip();
    if (renamed.equals(old)) {
      return;
    }
    // chosen before the change, so that the inspector shown after it is the renamed territory's
    selected = renamed;
    if (project.rename(old, renamed)) {
      list.choose(renamed);
      map.select(renamed);
    } else {
      selected = old;
      JOptionPane.showMessageDialog(
          page,
          "<html>The name must not be empty or used by another territory, and must not hold"
              + " : &lt; or (.<br>A territory named like a nation or a unit keeps its name:"
              + " the rules name both alike.</html>",
          "Rename",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  /** Renames the territories a chosen file lists, as one step, and tells how it went. */
  private void importNames() {
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(page, "Import territory names", userFolder(), "txt", "csv");
    if (file.isEmpty()) {
      return;
    }
    final Renamed renamed;
    try {
      renamed = project.renameAll(Files.readAllLines(file.get(), StandardCharsets.UTF_8));
    } catch (final IOException e) {
      JOptionPane.showMessageDialog(
          page,
          "Could not read " + file.get().getFileName() + ":\n" + e.getMessage(),
          "Import names",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    final StringBuilder report =
        new StringBuilder(
            "<html>Renamed "
                + renamed.count()
                + (renamed.count() == 1 ? " territory" : " territories")
                + (renamed.count() > 0 ? " as one step: Undo takes all back." : "."));
    if (!renamed.refused().isEmpty()) {
      report.append("<br><br>Refused:<br>");
      renamed.refused().forEach(line -> report.append(SearchList.escape(line)).append("<br>"));
    }
    final JLabel message = new JLabel(report.append("</html>").toString());
    final JScrollPane scroll = SwingComponents.newJScrollPane(message);
    scroll.setPreferredSize(
        new Dimension(520, Math.min(360, message.getPreferredSize().height + 16)));
    JOptionPane.showMessageDialog(
        page,
        scroll,
        "Import names",
        renamed.refused().isEmpty()
            ? JOptionPane.INFORMATION_MESSAGE
            : JOptionPane.WARNING_MESSAGE);
  }

  private static Path userFolder() {
    return Path.of(System.getProperty("user.home"));
  }

  /** Writes the names as lines "name;" for a spreadsheet, where the new names are filled in. */
  private void exportNames() {
    final Optional<Path> file =
        FileChooser.builder()
            .parent(JOptionPane.getFrameForComponent(page))
            .title("Export territory names")
            .mode(FileDialog.SAVE)
            .directory(userFolder())
            .fileName("territory-names.csv")
            .fileExtension(".csv")
            .build()
            .chooseFile();
    if (file.isEmpty()) {
      return;
    }
    try {
      Files.write(file.get(), project.nameList(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      JOptionPane.showMessageDialog(
          page,
          "Could not write " + file.get().getFileName() + ":\n" + e.getMessage(),
          "Export names",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    JOptionPane.showMessageDialog(
        page,
        "Wrote the names of "
            + project.nameList().size()
            + " territories.\nWrite each new name after the ';', then use Import names.",
        "Export names",
        JOptionPane.INFORMATION_MESSAGE);
  }

  /** A box with no owner first, then the nations, showing the owner given (none for null). */
  private JComboBox<String> newOwnerBox(@Nullable final String owner) {
    final List<String> nations = MapXml.nations(project.game());
    final JComboBox<String> box = new JComboBox<>();
    box.addItem(NO_OWNER);
    nations.forEach(box::addItem);
    box.setSelectedIndex(owner == null ? 0 : nations.indexOf(owner) + 1);
    return box;
  }

  /** The nation chosen in an owner box, null for no owner. */
  @Nullable
  private static String chosenOwner(final JComboBox<String> owners) {
    return owners.getSelectedIndex() == 0 ? null : (String) owners.getSelectedItem();
  }

  private JPanel unitRow(final String territory, final UnitPlacement units) {
    final JPanel row = new JPanel(new BorderLayout(8, 0));
    row.add(
        new JLabel(
            units.getUnitType()
                + "  ("
                + Optional.ofNullable(units.getOwner()).orElse(NO_OWNER)
                + ")"),
        BorderLayout.CENTER);
    row.add(
        Form.newNumber(
            units.getQuantity(),
            9999,
            count ->
                edit(
                    g ->
                        MapXml.withUnits(
                            g, territory, units.getOwner(), units.getUnitType(), count))),
        BorderLayout.EAST);
    return row;
  }

  /** A row to add units; their owner first is the territory's (none for null). */
  private JPanel addUnitsRow(final String territory, @Nullable final String owner) {
    final JComboBox<String> type = new JComboBox<>();
    MapXml.unitTypes(project.game()).forEach(type::addItem);
    final JComboBox<String> of = newOwnerBox(owner);
    final JButton add = new JButton("Add");
    add.addActionListener(
        e -> {
          final String unitType = (String) type.getSelectedItem();
          final String unitOwner = chosenOwner(of);
          if (unitType != null) {
            final int already =
                MapXml.units(project.game(), territory).stream()
                    .filter(
                        u ->
                            unitType.equals(u.getUnitType())
                                && Objects.equals(unitOwner, u.getOwner()))
                    .mapToInt(UnitPlacement::getQuantity)
                    .sum();
            project.edit(g -> MapXml.withUnits(g, territory, unitOwner, unitType, already + 1));
          }
        });
    final JPanel row = new JPanel(new GridLayout(1, 3, 6, 0));
    row.add(type);
    row.add(of);
    row.add(add);
    return row;
  }

  /** A change from the inspector, which keeps its fields instead of being built again. */
  private void edit(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }
}
