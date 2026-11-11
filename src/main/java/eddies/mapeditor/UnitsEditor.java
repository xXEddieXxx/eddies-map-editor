package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapFiles.UnitPicture;
import eddies.mapeditor.MapFiles.UnitPicture.Coloring;

/** The unit types: what they cost and how they fight and move. */
final class UnitsEditor implements Editor {
  /** A whole number stat: the game's name, how it reads, and UnitAttachment's default. */
  private record Stat(String option, String label, String note, int defaultValue) {}

  /** A yes or no stat, no by default. */
  private record Flag(String option, String label) {}

  private static final List<Stat> STATS =
      List.of(
          new Stat("attack", "Attack", "Hits on a die roll of this or less.", 0),
          new Stat("defense", "Defense", "Hits when defending on this or less.", 0),
          new Stat("movement", "Movement", "Territories it can move per turn.", 0),
          new Stat(
              "hitPoints", "Hit points", "Hits it takes to destroy, eg 2 for a battleship.", 1),
          new Stat(
              "transportCapacity",
              "Transport capacity",
              "Room it has for other units, -1 for none.",
              -1),
          new Stat(
              "transportCost", "Transport cost", "Room it takes on a transport, -1 for none.", -1));

  private static final List<Flag> FLAGS =
      List.of(
          new Flag("isAir", "Flies"),
          new Flag("isSea", "Sails"),
          new Flag("canBlitz", "Can blitz through empty territories"),
          new Flag("isInfrastructure", "Infrastructure, eg a factory: taken, never destroyed"),
          new Flag("artillery", "Artillery: supports infantry"),
          new Flag("artillerySupportable", "Gets the support of artillery"));

  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  private @Nullable String selected;
  private boolean editingHere;

  UnitsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search unit", () -> MapXml.unitTypes(project.game()), this::detail, this::choose);
    final JComponent inspectorScroll = SwingComponents.newJScrollPane(inspector);
    final JButton add = new JButton("Add unit...");
    add.addActionListener(e -> addUnit());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(
        Help.with(
            add,
            "A new kind of unit, starting with the stats and cost of the chosen one, or of"
                + " infantry. The list shows attack-defense-movement and cost."),
        BorderLayout.SOUTH);
    page.add(Panels.sideBySide(listColumn, inspectorScroll), BorderLayout.CENTER);
    project.onChange(
        () -> {
          list.refresh();
          if (!editingHere) {
            showInspector();
          }
        });
    showInspector();
  }

  @Override
  public String title() {
    return "Units";
  }

  @Override
  public Glyph glyph() {
    return Glyph.UNITS;
  }

  @Override
  public String hint() {
    return "The kinds of units. Add or remove them; choose one to change its picture, its cost"
        + " and how it fights and moves.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  private String detail(final String unit) {
    final Game game = project.game();
    return MapXml.unitStat(game, unit, "attack", 0)
        + "-"
        + MapXml.unitStat(game, unit, "defense", 0)
        + "-"
        + MapXml.unitStat(game, unit, "movement", 0)
        + MapXml.unitCost(game, unit).map(cost -> " · " + cost + " PUs").orElse("");
  }

  private void choose(final String unit) {
    selected = unit;
    showInspector();
  }

  /** Asks a name and adds a unit type with the stats and cost of the chosen one, or infantry. */
  private void addUnit() {
    final List<String> units = MapXml.unitTypes(project.game());
    final String like =
        selected != null && units.contains(selected)
            ? selected
            : units.contains("infantry") || units.isEmpty() ? "infantry" : units.get(0);
    final String name =
        JOptionPane.showInputDialog(
            page,
            "Name of the new unit (it starts with the stats and cost of " + like + "):",
            "Add unit",
            JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    final String unit = name.strip();
    if (project.addUnitType(unit, like)) {
      choose(unit);
      list.choose(unit);
      if (JOptionPane.showConfirmDialog(
              page,
              "Upload a picture for " + unit + " now? Until then it looks like " + like + ".",
              "Add unit",
              JOptionPane.YES_NO_OPTION)
          == JOptionPane.YES_OPTION) {
        uploadPicture(unit);
      }
    } else {
      JOptionPane.showMessageDialog(
          page,
          "<html>The name must not be empty or be a territory's, a nation's or a unit's, and"
              + " must not hold : &lt; or (.</html>",
          "Add unit",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private void removeUnit(final String unit) {
    final int answer =
        JOptionPane.showConfirmDialog(
            page,
            "Remove "
                + unit
                + " with its stats, the rules that buy it and its units at the start?"
                + "\nUndo takes it back.",
            "Remove unit",
            JOptionPane.OK_CANCEL_OPTION);
    if (answer == JOptionPane.OK_OPTION) {
      project.edit(g -> MapXml.withoutUnitType(g, unit));
    }
  }

  private void showInspector() {
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !MapXml.unitTypes(game).contains(selected)) {
      inspector.note(MapXml.unitTypes(game).size() + " kinds of units in this game.").end();
      return;
    }
    final String unit = selected;
    inspector.title(unit);
    showPicture(unit);
    inspector.section("Cost");
    final Optional<Integer> cost = MapXml.unitCost(game, unit);
    if (cost.isPresent()) {
      inspector.field(
          "PUs to buy one",
          Form.newNumber(
              cost.get(), 999, value -> quietly(g -> MapXml.withUnitCost(g, unit, value))));
    } else {
      inspector.note("No nation can buy it: it has no rule that sells one.");
    }
    inspector.section("Fighting and moving");
    for (final Stat stat : STATS) {
      inspector.field(
          stat.label(),
          stat.note(),
          Form.newNumber(
              MapXml.unitStat(game, unit, stat.option(), stat.defaultValue()),
              Math.min(0, stat.defaultValue()),
              99,
              v ->
                  quietly(
                      g ->
                          v == stat.defaultValue()
                              ? MapXml.withoutUnitOption(g, unit, stat.option())
                              : MapXml.withUnitOption(g, unit, stat.option(), String.valueOf(v)))));
    }
    inspector.section("Kind", "The other abilities stay as the game has them.");
    for (final Flag flag : FLAGS) {
      final JCheckBox box = new JCheckBox(flag.label(), MapXml.unitFlag(game, unit, flag.option()));
      box.addActionListener(
          e ->
              quietly(
                  g ->
                      box.isSelected()
                          ? MapXml.withUnitOption(g, unit, flag.option(), "true")
                          : MapXml.withoutUnitOption(g, unit, flag.option())));
      inspector.row(box);
    }
    final JButton remove = new JButton("Remove unit");
    remove.addActionListener(e -> removeUnit(unit));
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);
    inspector.end();
  }

  private void quietly(final UnaryOperator<Game> change) {
    editingHere = true;
    try {
      project.edit(change);
    } finally {
      editingHere = false;
    }
  }

  /**
   * The unit's picture, twice its size, with the buttons to upload one or go back to the game's.
   */
  private void showPicture(final String unit) {
    project.unitPicture(unit)
        .ifPresent(
            image ->
                inspector.row(
                    new JLabel(
                        new ImageIcon(
                            image.getScaledInstance(
                                image.getWidth(null) * 2,
                                image.getHeight(null) * 2,
                                Image.SCALE_SMOOTH)))));
    final Optional<UnitPicture> uploaded =
        Optional.ofNullable(project.getFiles().unitPictures().get(unit));
    final JButton upload = new JButton("Upload picture...");
    upload.addActionListener(e -> uploadPicture(unit));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    buttons.add(
        Help.with(
            upload,
            "A png, gif or jpg picture, shrunk to 48x48 if larger. Saving writes it for every"
                + " nation, over the unit pictures the map has; the first ones stay as .bak."));
    if (uploaded.isEmpty()) {
      inspector.row(buttons);
      return;
    }
    final JButton gamePicture = new JButton("Use the game's picture");
    gamePicture.addActionListener(e -> project.removeUnitPicture(unit));
    buttons.add(Box.createHorizontalStrut(6));
    buttons.add(gamePicture);
    inspector.row(buttons);
    final JRadioButton perNation =
        new JRadioButton(
            "Color it for each nation", uploaded.get().coloring() == Coloring.PER_NATION);
    final JRadioButton asIs =
        new JRadioButton("Same for all nations", uploaded.get().coloring() == Coloring.AS_IS);
    final ButtonGroup coloring = new ButtonGroup();
    coloring.add(perNation);
    coloring.add(asIs);
    perNation.addActionListener(e -> setColoring(unit, uploaded.get(), Coloring.PER_NATION));
    asIs.addActionListener(e -> setColoring(unit, uploaded.get(), Coloring.AS_IS));
    final JPanel colorings = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    colorings.add(perNation);
    colorings.add(asIs);
    inspector.row(colorings);
  }

  private void setColoring(final String unit, final UnitPicture uploaded, final Coloring coloring) {
    if (uploaded.coloring() != coloring) {
      project.setUnitPicture(unit, new UnitPicture(uploaded.image(), coloring));
    }
  }

  /** Asks for a picture and makes it the unit type's, colored for each nation, as one step. */
  private void uploadPicture(final String unit) {
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(
            page,
            "Choose a picture for " + unit,
            Path.of(System.getProperty("user.home")),
            "png",
            "gif",
            "jpg",
            "jpeg");
    if (file.isEmpty()) {
      return;
    }
    final BufferedImage image;
    try {
      image = ImageIO.read(file.get().toFile());
    } catch (final IOException e) {
      showNoPicture(file.get(), e.getMessage());
      return;
    }
    if (image == null) {
      showNoPicture(file.get(), "It is no png, gif or jpg picture.");
      return;
    }
    project.setUnitPicture(unit, new UnitPicture(MapFolder.toUnitSize(image), Coloring.PER_NATION));
  }

  private void showNoPicture(final Path file, final String why) {
    JOptionPane.showMessageDialog(
        page,
        "Could not read " + file.getFileName() + " as a picture:\n" + why,
        "Upload picture",
        JOptionPane.WARNING_MESSAGE);
  }
}
