package eddies.mapeditor;

import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;

/**
 * How the game draws the map, as map.properties sets it: the size of the units, whether the map
 * scrolls around, what it shows on the territories, and a relief picture over it. What TripleA's
 * map creator does with its map properties maker and the relief part of its tile image breaker.
 */
final class MapLookEditor implements Editor {
  // the scales the game's map creator offers; a map's own value is added to them
  private static final List<String> SCALES =
      List.of("1.25", "1", "0.875", "0.8333", "0.75", "0.6666", "0.5625", "0.5");

  private final MapProject project;
  private final Form form = new Form(420);
  private final JPanel page = new JPanel(new BorderLayout());
  // while the form is filled from the map, its fields tell no changes
  private boolean filling;
  // a change made here keeps the form, so a field keeps its focus
  private boolean editingHere;

  MapLookEditor(final MapProject project) {
    this.project = project;
    page.add(SwingComponents.newJScrollPane(form), BorderLayout.CENTER);
    project.onChange(this::fill);
    fill();
  }

  @Override
  public String title() {
    return "Map look";
  }

  @Override
  public Glyph glyph() {
    return Glyph.MAP_LOOK;
  }

  @Override
  public String hint() {
    return "How the game draws your map: the size of the units, whether it scrolls around, what"
        + " it shows on the territories, and a relief picture over it. Saved in map.properties.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  private void fill() {
    if (editingHere) {
      return;
    }
    filling = true;
    try {
      form.clear();
      form.section("Units", "The size the game draws the units in, and how many it stacks.");
      form.field("Scale", "1 draws them at their pictures' size.", fitting(scaleChoice()));
      form.field(
          "Picture width",
          "The width of the unit pictures in pixels; 48 for the game's own.",
          number(MapData.PROPERTY_UNITS_WIDTH, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE, 400));
      form.field(
          "Picture height",
          "The height of the unit pictures in pixels; 48 for the game's own.",
          number(MapData.PROPERTY_UNITS_HEIGHT, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE, 400));
      form.field(
          "Stack up to",
          "How many units of a kind are drawn on top of each other; 0 for one with a number.",
          number(MapData.PROPERTY_UNITS_STACK_SIZE, 0, 99));
      form.section("Scrolling");
      form.row(check("Scrolls around left and right", MapData.PROPERTY_MAP_SCROLLWRAPX, true));
      form.row(check("Scrolls around top and bottom", MapData.PROPERTY_MAP_SCROLLWRAPY, false));
      form.section("Shown on the map");
      form.row(check("Territory names", MapData.PROPERTY_MAP_SHOWTERRITORYNAMES, true));
      form.row(check("Sea zone names", MapData.PROPERTY_MAP_SHOWSEAZONENAMES, false));
      form.row(check("Resources, eg PUs", MapData.PROPERTY_MAP_SHOWRESOURCES, true));
      form.row(check("Capital markers", MapData.PROPERTY_MAP_SHOWCAPITOLMARKERS, true));
      form.row(
          check("Territory effect markers", MapData.PROPERTY_MAP_USETERRITORYEFFECTMARKERS, false));
      form.section(
          "Relief",
          "A picture drawn over the map, eg mountains and forests, with see-through parts where"
              + " the map shows. As big as the map; saving cuts it into the reliefTiles folder.");
      form.note(reliefNote());
      final JButton relief = new JButton("Load relief picture...");
      relief.addActionListener(e -> loadRelief());
      relief.setEnabled(project.getFiles().hasOutlines());
      form.row(fitting(relief));
      form.row(check("Show the relief", MapData.PROPERTY_MAP_HASRELIEF, true));
      form.end();
    } finally {
      filling = false;
    }
  }

  /** The component at its own width, as a row would stretch it over the whole form. */
  private static JPanel fitting(final JComponent component) {
    final JPanel fitting = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    fitting.add(component);
    return fitting;
  }

  private JComboBox<String> scaleChoice() {
    final String scale = value(MapData.PROPERTY_UNITS_SCALE, "1");
    final JComboBox<String> choice = new JComboBox<>(SCALES.toArray(String[]::new));
    if (!SCALES.contains(scale)) {
      choice.addItem(scale);
    }
    choice.setSelectedItem(scale);
    choice.addActionListener(
        e -> set(MapData.PROPERTY_UNITS_SCALE, (String) choice.getSelectedItem()));
    return choice;
  }

  private JComponent number(final String property, final int fallback, final int max) {
    final int value;
    try {
      value = Integer.parseInt(value(property, String.valueOf(fallback)).strip());
    } catch (final NumberFormatException e) {
      // a map's value the game cannot read either: shown as the game's default
      return Form.newNumber(fallback, max, n -> set(property, String.valueOf(n)));
    }
    return Form.newNumber(value, max, n -> set(property, String.valueOf(n)));
  }

  private JCheckBox check(final String label, final String property, final boolean fallback) {
    final JCheckBox check =
        new JCheckBox(
            label, Boolean.parseBoolean(value(property, String.valueOf(fallback)).strip()));
    check.addActionListener(e -> set(property, String.valueOf(check.isSelected())));
    return check;
  }

  private String value(final String property, final String fallback) {
    final Properties properties = project.getFiles().properties();
    return properties.getProperty(property, fallback);
  }

  private void set(final String property, final String value) {
    if (filling) {
      return;
    }
    editingHere = true;
    try {
      project.setMapProperty(property, value);
    } finally {
      editingHere = false;
    }
  }

  private String reliefNote() {
    if (project.hasUnsavedRelief()) {
      return "A new relief picture is loaded; saving writes it.";
    }
    final boolean hasTiles =
        project
            .getFolder()
            .map(folder -> Files.isDirectory(folder.resolve("reliefTiles")))
            .orElse(false);
    return hasTiles ? "The map has relief tiles." : "The map has no relief yet.";
  }

  private void loadRelief() {
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(
            page,
            "Choose a relief picture",
            project.getFolder().orElse(Path.of(System.getProperty("user.home"))),
            "png",
            "gif");
    if (file.isEmpty()) {
      return;
    }
    final BufferedImage image;
    try {
      image = ImageIO.read(file.get().toFile());
    } catch (final IOException e) {
      showNoRelief(file.get(), e.getMessage());
      return;
    }
    if (image == null) {
      showNoRelief(file.get(), "It is no png or gif picture.");
      return;
    }
    final Dimension size = project.getFiles().size();
    if (image.getWidth() != size.width || image.getHeight() != size.height) {
      showNoRelief(
          file.get(),
          "It is "
              + image.getWidth()
              + " x "
              + image.getHeight()
              + " pixels, the map "
              + size.width
              + " x "
              + size.height
              + ". The relief must be as big as the map.");
      return;
    }
    project.setRelief(image);
  }

  private void showNoRelief(final Path file, final String why) {
    JOptionPane.showMessageDialog(
        page,
        "Could not take " + file.getFileName() + " as the relief:\n" + why,
        "Relief",
        JOptionPane.WARNING_MESSAGE);
  }
}
