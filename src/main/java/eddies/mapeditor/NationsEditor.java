package eddies.mapeditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapFolder.UnitPictures;

/**
 * The nations: adding and removing them, their color, alliance, the PUs they start with, and what
 * they own at the start.
 */
final class NationsEditor implements Editor {
  private final MapProject project;
  private final JPanel page = new JPanel(new BorderLayout(8, 0));
  private final SearchList list;
  private final Form inspector = new Form(280);
  private @Nullable String selected;
  private boolean editingHere;

  NationsEditor(final MapProject project) {
    this.project = project;
    list =
        new SearchList(
            "Search nation",
            () -> MapXml.nations(project.game()),
            nation -> String.join(", ", MapXml.alliances(project.game(), nation)),
            this::choose);
    final JComponent inspectorScroll = SwingComponents.newJScrollPane(inspector);
    final JButton add = new JButton("Add nation...");
    add.addActionListener(e -> addNation());
    final JPanel listColumn = new JPanel(new BorderLayout(0, 8));
    listColumn.add(list, BorderLayout.CENTER);
    listColumn.add(add, BorderLayout.SOUTH);
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
    return "Nations";
  }

  @Override
  public Glyph glyph() {
    return Glyph.NATIONS;
  }

  @Override
  public String hint() {
    return "The nations that play. Add them here; choose one to change its color, alliance, the"
        + " PUs it starts with and its unit pictures. What it owns is set in Territories.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  private void choose(final String nation) {
    selected = nation;
    showInspector();
  }

  private void addNation() {
    final String name =
        JOptionPane.showInputDialog(
            page, "Name of the new nation:", "Add nation", JOptionPane.PLAIN_MESSAGE);
    if (name == null) {
      return;
    }
    final String nation = name.strip();
    if (project.addNation(nation)) {
      choose(nation);
      list.choose(nation);
    } else {
      JOptionPane.showMessageDialog(
          page,
          "<html>The name must not be empty or be a territory's, a nation's or a unit's, and"
              + " must not hold : &lt; or (.</html>",
          "Add nation",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private void showInspector() {
    inspector.clear();
    final Game game = project.game();
    if (selected == null || !MapXml.nations(game).contains(selected)) {
      inspector
          .note(
              MapXml.nations(game).isEmpty()
                  ? "No nations yet. Add the nations that play with Add nation."
                  : MapXml.nations(game).size() + " nations play this game.")
          .end();
      return;
    }
    final String nation = selected;
    inspector.title(nation);
    final JPanel swatch = new JPanel();
    swatch.setPreferredSize(new Dimension(48, 20));
    swatch.setBackground(project.nationColor(nation));
    swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
    final JButton color = new JButton("Change color...");
    color.addActionListener(
        e ->
            Optional.ofNullable(
                    JColorChooser.showDialog(
                        page, "Color of " + nation, project.nationColor(nation)))
                .ifPresent(chosen -> project.setNationColor(nation, chosen)));
    final JPanel colorRow = new JPanel(new BorderLayout(8, 0));
    colorRow.add(swatch, BorderLayout.WEST);
    colorRow.add(new JLabel("Its color on the map"), BorderLayout.CENTER);
    colorRow.add(color, BorderLayout.EAST);
    inspector.row(colorRow);
    final JButton remove = new JButton("Remove nation");
    remove.addActionListener(e -> removeNation(nation));
    final JPanel removeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    removeRow.add(remove);
    inspector.row(removeRow);

    inspector.section("Alliance");
    final JComboBox<String> alliance = new JComboBox<>();
    alliance.setEditable(true);
    MapXml.allAlliances(game).forEach(alliance::addItem);
    final List<String> current = MapXml.alliances(game, nation);
    alliance.setSelectedItem(current.isEmpty() ? "" : current.get(0));
    alliance.addActionListener(
        e -> {
          final String chosen = String.valueOf(alliance.getSelectedItem());
          if (!List.of(chosen).equals(MapXml.alliances(project.game(), nation))) {
            project.edit(g -> MapXml.withAlliance(g, nation, chosen));
          }
        });
    inspector.field(
        "Plays in the alliance",
        "Nations of one alliance fight together. Type a name for a new alliance.",
        alliance);

    inspector.section("Start");
    inspector.field(
        "PUs at the start",
        Form.newNumber(
            MapXml.startingResource(game, nation, MapXml.PUS),
            9999,
            value -> {
              editingHere = true;
              try {
                project.edit(g -> MapXml.withStartingResource(g, nation, MapXml.PUS, value));
              } finally {
                editingHere = false;
              }
            }));
    final List<String> owned =
        MapXml.territories(game).stream()
            .filter(t -> MapXml.owner(game, t).filter(nation::equals).isPresent())
            .toList();
    final int income = owned.stream().mapToInt(t -> MapXml.production(game, t)).sum();
    inspector.note(
        "Owns " + owned.size() + " territories at the start, worth " + income + " PUs per turn.");
    owned.stream()
        .filter(t -> MapXml.isCapital(game, t))
        .findFirst()
        .ifPresentOrElse(
            capital -> inspector.note("Capital: " + capital),
            () -> inspector.note("No capital yet: mark one in Territories."));
    showUnitPictures(nation);
    inspector.end();
  }

  /** The nation's units as they look, and buttons to import pictures for them or take them back. */
  private void showUnitPictures(final String nation) {
    inspector.section(
        "Unit pictures",
        "How this nation's units look. Import a folder of pictures, eg another map's"
            + " units/<nation> folder: each picture named like a unit type, eg infantry.png,"
            + " becomes this nation's picture of it. Saving writes them into the map's units"
            + " folder; the first versions stay as .bak. Use the game's pictures takes them back.");
    // rows of a few, as a flowing row would not wrap in the form
    final JPanel pictures = new JPanel(new GridLayout(0, 6, 4, 8));
    final Color color = project.nationColor(nation);
    for (final String unit : MapXml.unitTypes(project.game())) {
      final JLabel picture = new JLabel(unit);
      project.unitPicture(nation, color, unit).ifPresent(image -> picture.setIcon(icon(image)));
      picture.setHorizontalTextPosition(SwingConstants.CENTER);
      picture.setVerticalTextPosition(SwingConstants.BOTTOM);
      pictures.add(picture);
    }
    inspector.row(pictures);
    final JButton importPictures = new JButton("Import unit pictures...");
    importPictures.addActionListener(e -> importUnitPictures(nation));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    buttons.add(importPictures);
    if (project.getFiles().nationUnitPictures().keySet().stream()
        .anyMatch(key -> key.nation().equals(nation))) {
      final JButton takeBack = new JButton("Use the game's pictures");
      takeBack.addActionListener(e -> project.setNationUnitPictures(nation, Map.of()));
      buttons.add(takeBack);
    }
    inspector.row(buttons);
  }

  private static Icon icon(final Image image) {
    // a big picture, eg of a map with large units, stays a unit's size here
    final int width = image.getWidth(null);
    final int height = image.getHeight(null);
    final double scale = Math.min(1, 48.0 / Math.max(1, Math.max(width, height)));
    return new ImageIcon(
        scale == 1
            ? image
            : image.getScaledInstance(
                (int) (width * scale), (int) (height * scale), Image.SCALE_SMOOTH));
  }

  /** Asks for a folder of unit pictures and takes those named like unit types, as one step. */
  private void importUnitPictures(final String nation) {
    final Optional<Path> folder =
        MapMakerWindow.chooseFolder(
            page,
            "Choose a folder with unit pictures for " + nation,
            project
                .getFolder()
                .map(f -> f.resolve("units"))
                .filter(Files::isDirectory)
                .orElseGet(() -> Path.of(System.getProperty("user.home"))));
    if (folder.isEmpty()) {
      return;
    }
    final UnitPictures read;
    try {
      read = MapFolder.readUnitPictures(folder.get(), MapXml.unitTypes(project.game()));
    } catch (final IOException e) {
      JOptionPane.showMessageDialog(
          page,
          "Could not read " + folder.get() + ":\n" + e.getMessage(),
          "Import unit pictures",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    if (!read.byUnit().isEmpty()) {
      project.setNationUnitPictures(nation, read.byUnit());
    }
    JOptionPane.showMessageDialog(
        page,
        read.byUnit().isEmpty()
            ? "No picture there is named like a unit type of this game, eg infantry.png."
            : read.byUnit().size()
                + " unit pictures imported for "
                + nation
                + (read.unknown().isEmpty()
                    ? "."
                    : ".\nLeft out, named like no unit type or no picture: "
                        + String.join(", ", read.unknown())),
        "Import unit pictures",
        read.byUnit().isEmpty() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
  }

  private void removeNation(final String nation) {
    final int answer =
        JOptionPane.showConfirmDialog(
            page,
            "Remove "
                + nation
                + " with its turn, the territories it owns at the start and its units?"
                + "\nUndo takes it back.",
            "Remove nation",
            JOptionPane.OK_CANCEL_OPTION);
    if (answer != JOptionPane.OK_OPTION) {
      return;
    }
    final List<String> named = project.removeNation(nation);
    if (!named.isEmpty()) {
      JOptionPane.showMessageDialog(
          page,
          nation
              + " stays: the game would not load without it, as these still name it:\n"
              + String.join("\n", named.subList(0, Math.min(10, named.size())))
              + (named.size() > 10 ? "\nand " + (named.size() - 10) + " more" : "")
              + "\nChange them in Attachments first.",
          "Remove nation",
          JOptionPane.WARNING_MESSAGE);
    }
  }
}
