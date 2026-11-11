package eddies.mapeditor;

import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingWorker;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.Game;
import org.triplea.swing.SwingComponents;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.ReliefMaker.Touch;

/**
 * How the game draws the map, as map.properties sets it: the size of the units, whether the map
 * scrolls around, what it shows on the territories, and a relief picture over it. What TripleA's
 * map creator does with its map properties maker and the relief part of its tile image breaker.
 */
@Slf4j
final class MapLookEditor implements Editor {
  // the scales the game's map creator offers; a map's own value is added to them
  private static final Dimension PREVIEW = new Dimension(480, 320);
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
      final JButton create = new JButton("Create relief...");
      create.setToolTipText("Make a relief from the outlines: coasts, waves and raised land");
      create.addActionListener(e -> createRelief());
      create.setEnabled(project.getFiles().hasOutlines());
      final Box buttons = Box.createHorizontalBox();
      buttons.add(relief);
      buttons.add(Box.createHorizontalStrut(6));
      buttons.add(create);
      form.row(fitting(buttons));
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

  /** Asks how the relief should look, showing a part of the map, then makes it for the map. */
  private void createRelief() {
    final MapFiles files = project.getFiles();
    final Game game = project.game();
    final Set<String> water =
        files.polygons().keySet().stream()
            .filter(t -> MapXml.isWater(game, t))
            .collect(Collectors.toUnmodifiableSet());
    final ReliefOptions options = new ReliefOptions(files, water);
    final int choice =
        JOptionPane.showOptionDialog(
            page,
            options.form,
            "Create relief",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            new Object[] {"Create", "Cancel"},
            "Create");
    if (choice == 0) {
      makeRelief(files, game, water, options.strength.getValue(), options.touches());
    }
  }

  /** How the relief should look, with a part of the map showing it. */
  private static final class ReliefOptions {
    private final Form form = new Form(PREVIEW.width);
    private final JSlider strength = new JSlider(10, 100, 50);
    private final JCheckBox waves = new JCheckBox("Waves along the coasts", true);
    private final JCheckBox texture = new JCheckBox("Texture on the land", true);
    private final JLabel preview = new JLabel();
    private final MapFiles files;
    private final Set<String> water;
    private final Rectangle area;
    private final BufferedImage under;

    ReliefOptions(final MapFiles files, final Set<String> water) {
      this.files = files;
      this.water = water;
      area = previewArea(files, water);
      under = pictureOf(files, area);
      strength.addChangeListener(
          e -> {
            if (!strength.getValueIsAdjusting()) {
              show();
            }
          });
      waves.addActionListener(e -> show());
      texture.addActionListener(e -> show());
      show();
      form.row(preview);
      form.field("Strength", "How strongly the relief is drawn.", strength);
      form.row(waves);
      form.row(texture);
      form.end();
    }

    Set<Touch> touches() {
      final Set<Touch> chosen = EnumSet.noneOf(Touch.class);
      if (waves.isSelected()) {
        chosen.add(Touch.WAVES);
      }
      if (texture.isSelected()) {
        chosen.add(Touch.TEXTURE);
      }
      return chosen;
    }

    // a part of the map is made at once, so the preview follows the choices right away
    private void show() {
      final BufferedImage shown = copy(under);
      final Graphics2D g = shown.createGraphics();
      g.drawImage(
          ReliefMaker.make(area, files.polygons(), water, strength.getValue(), touches()),
          0,
          0,
          null);
      g.dispose();
      preview.setIcon(new ImageIcon(shown));
    }
  }

  /** Makes the relief of the whole map in the background; taken if the map is still the same. */
  private void makeRelief(
      final MapFiles files,
      final Game game,
      final Set<String> water,
      final int strength,
      final Set<Touch> touches) {
    page.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<BufferedImage, Void>() {
      @Override
      protected BufferedImage doInBackground() {
        return ReliefMaker.make(
            new Rectangle(files.size()), files.polygons(), water, strength, touches);
      }

      @Override
      protected void done() {
        page.setCursor(Cursor.getDefaultCursor());
        final BufferedImage relief;
        try {
          relief = get();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (final ExecutionException e) {
          log.warn("Could not make the relief", e.getCause());
          JOptionPane.showMessageDialog(
              page,
              "Could not make the relief:\n" + e.getCause().getMessage(),
              "Relief",
              JOptionPane.WARNING_MESSAGE);
          return;
        }
        if (project.getFiles() != files || project.game() != game) {
          JOptionPane.showMessageDialog(
              page,
              "The map changed while the relief was made. Create it again.",
              "Relief",
              JOptionPane.INFORMATION_MESSAGE);
          return;
        }
        project.setRelief(relief);
      }
    }.execute();
  }

  /** A part of the map with a coast, the one nearest its middle; the middle if it has none. */
  private static Rectangle previewArea(final MapFiles files, final Set<String> water) {
    final Dimension size = files.size();
    final Point middle = new Point(size.width / 2, size.height / 2);
    final List<Polygon> seas =
        files.polygons().entrySet().stream()
            .filter(e -> water.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .toList();
    final Point at =
        files.polygons().entrySet().stream()
            .filter(e -> !water.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .flatMap(p -> IntStream.range(0, p.npoints).mapToObj(i -> corner(p, i)))
            .filter(p -> seas.stream().anyMatch(sea -> sea.intersects(p.x - 4, p.y - 4, 8, 8)))
            .min(Comparator.comparingDouble(p -> p.distance(middle)))
            .orElse(middle);
    final int width = Math.min(PREVIEW.width, size.width);
    final int height = Math.min(PREVIEW.height, size.height);
    return new Rectangle(
        Math.clamp(at.x - width / 2, 0, size.width - width),
        Math.clamp(at.y - height / 2, 0, size.height - height),
        width,
        height);
  }

  private static Point corner(final Polygon polygon, final int index) {
    return new Point(polygon.xpoints[index], polygon.ypoints[index]);
  }

  /** The map's picture in the area, over a land color where its tiles are see-through. */
  private static BufferedImage pictureOf(final MapFiles files, final Rectangle area) {
    final BufferedImage picture =
        new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(205, 190, 150));
    g.fillRect(0, 0, area.width, area.height);
    g.translate(-area.x, -area.y);
    if (files.picture().isPresent()) {
      g.drawImage(files.picture().get(), 0, 0, null);
    } else {
      final int tile = MapFiles.tileSize();
      for (int column = area.x / tile; column * tile < area.x + area.width; column++) {
        for (int row = area.y / tile; row * tile < area.y + area.height; row++) {
          final int x = column * tile;
          final int y = row * tile;
          files
              .tile(column, row)
              .flatMap(MapLookEditor::read)
              .ifPresent(t -> g.drawImage(t, x, y, null));
        }
      }
    }
    g.dispose();
    return picture;
  }

  private static Optional<BufferedImage> read(final Path file) {
    try {
      return Optional.ofNullable(ImageIO.read(file.toFile()));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private static BufferedImage copy(final BufferedImage image) {
    final BufferedImage copy =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    copy.setData(image.getRaster());
    return copy;
  }

  private void showNoRelief(final Path file, final String why) {
    JOptionPane.showMessageDialog(
        page,
        "Could not take " + file.getFileName() + " as the relief:\n" + why,
        "Relief",
        JOptionPane.WARNING_MESSAGE);
  }
}
