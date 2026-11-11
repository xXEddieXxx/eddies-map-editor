package eddies.mapeditor;

import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import javax.swing.JColorChooser;
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

  // the game's ways of blending the relief into the map, BlendComposite
  private static final List<String> BLEND_MODES =
      List.of("NORMAL", "OVERLAY", "MULTIPLY", "DIFFERENCE", "LINEAR_LIGHT");
  // MapData keeps these keys private
  private static final String TRANSFORM_COLOR = "units.transform.color.";
  private static final String TRANSFORM_BRIGHTNESS = "units.transform.brightness.";
  private static final String TRANSFORM_FLIP = "units.transform.flip.";
  private static final String SMALL_MAP_SATURATION = "smallMap.territory.saturation";
  private static final String SMALL_MAP_UNIT_SIZE = "smallMap.unit.size";
  private static final String SMALL_MAP_BORDER = "smallMap.viewer.borderColor";
  private static final String SMALL_MAP_FILL = "smallMap.viewer.fillColor";
  private static final String SMALL_MAP_FILL_ALPHA = "smallMap.viewer.fillAlpha";
  // what the game takes for the title's place and size without all three
  private static final int SCREENSHOT_TITLE_DEFAULT = 15;

  private final MapProject project;
  // two columns of sections, as the tool takes the window's width
  private final Form left = new Form(300);
  private final Form right = new Form(300);
  // the column the sections are added to while filling
  private Form form = left;
  private final JPanel page = new JPanel(new BorderLayout());
  // while the form is filled from the map, its fields tell no changes
  private boolean filling;
  // a change made here keeps the form, so a field keeps its focus
  private boolean editingHere;
  // the nation whose units' look is shown
  private String tintedNation = "";

  MapLookEditor(final MapProject project) {
    this.project = project;
    final JPanel columns = new JPanel(new GridLayout(1, 2, 24, 0));
    columns.add(left);
    columns.add(right);
    // a form around them, so they follow the width of the scrolled view
    page.add(SwingComponents.newJScrollPane(new Form(300).row(columns)), BorderLayout.CENTER);
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
      left.clear();
      right.clear();
      form = left;
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
      form.field(
          "Number left",
          "Where the number of units is drawn, from the left of the picture.",
          number(
              MapData.PROPERTY_UNITS_COUNTER_OFFSET_WIDTH,
              intValue(MapData.PROPERTY_UNITS_WIDTH, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE) / 4,
              400));
      form.field(
          "Number down",
          "Where the number of units is drawn, from the top of the picture.",
          number(
              MapData.PROPERTY_UNITS_COUNTER_OFFSET_HEIGHT,
              intValue(MapData.PROPERTY_UNITS_HEIGHT, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE),
              400));
      unitTint();
      form.section("Scrolling", "Whether the map goes on at the other side, as round a globe.");
      form.row(check("Scrolls around left and right", MapData.PROPERTY_MAP_SCROLLWRAPX, true));
      form.row(check("Scrolls around top and bottom", MapData.PROPERTY_MAP_SCROLLWRAPY, false));
      form.section(
          "Shown on the map", "What the game draws on the territories, besides units and owners.");
      form.row(check("Territory names", MapData.PROPERTY_MAP_SHOWTERRITORYNAMES, true));
      form.row(check("Sea zone names", MapData.PROPERTY_MAP_SHOWSEAZONENAMES, false));
      form.row(check("Resources, eg PUs", MapData.PROPERTY_MAP_SHOWRESOURCES, true));
      form.row(check("Capital markers", MapData.PROPERTY_MAP_SHOWCAPITOLMARKERS, true));
      form.row(
          check("Territory effect markers", MapData.PROPERTY_MAP_USETERRITORYEFFECTMARKERS, false));
      form.row(check("Comment markers", MapData.PROPERTY_MAP_SHOWCOMMENTS, true));
      form.row(
          check("Each nation's own convoy flags", MapData.PROPERTY_MAP_USENATION_CONVOYFLAGS, false));
      form.row(
          check(
              "Names from their place's top left",
              MapData.PROPERTY_MAP_DRAWNAMESFROMTOPLEFT,
              false));
      form.end();
      form = right;
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
      blends();
      smallMap();
      screenshot();
      form.section(
          "Mouse pointer",
          "The map's own pointer is misc/cursor.gif; this is the point of it that clicks.");
      form.field("Point left", number(MapData.PROPERTY_MAP_CURSOR_HOTSPOT_X, 0, 256));
      form.field("Point down", number(MapData.PROPERTY_MAP_CURSOR_HOTSPOT_Y, 0, 256));
      form.end();
      form = left;
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
    return number(property, fallback, 0, max);
  }

  private JComponent number(
      final String property, final int fallback, final int min, final int max) {
    return Form.newNumber(
        intValue(property, fallback), min, max, n -> set(property, String.valueOf(n)));
  }

  /** The whole number; a map's value the game cannot read either as the game's default. */
  private int intValue(final String property, final int fallback) {
    try {
      return Integer.parseInt(value(property, String.valueOf(fallback)).strip());
    } catch (final NumberFormatException e) {
      return fallback;
    }
  }

  /** A share from 0 to 1, eg 0.5f, as a percent. */
  private JComponent percent(final String property, final float fallback) {
    float share;
    try {
      share = Float.parseFloat(value(property, String.valueOf(fallback)).strip());
    } catch (final NumberFormatException e) {
      share = fallback;
    }
    return Form.newNumber(
        Math.round(share * 100), 100, n -> set(property, String.valueOf(n / 100.0)));
  }

  /**
   * A button showing the color, which a color chooser changes; "None" without one, which the
   * chooser then also offers, as the game draws nothing for it.
   */
  private JComponent color(final String property, final Optional<Color> fallback) {
    if (fallback.isPresent()) {
      return colorWithDefault(property, fallback.get());
    }
    final Optional<Color> shown =
        Optional.ofNullable(project.getFiles().properties().getProperty(property))
            .flatMap(MapLookEditor::colorOf);
    final JButton button = new JButton(shown.isPresent() ? "      " : "None");
    shown.ifPresent(
        c -> {
          button.setBackground(c);
          button.setToolTipText("#" + hex(c));
        });
    button.addActionListener(
        e -> {
          final JColorChooser chooser = new JColorChooser(shown.orElse(Color.GRAY));
          final int choice =
              JOptionPane.showOptionDialog(
                  page,
                  chooser,
                  "Choose a color",
                  JOptionPane.DEFAULT_OPTION,
                  JOptionPane.PLAIN_MESSAGE,
                  null,
                  new Object[] {"OK", "None", "Cancel"},
                  "OK");
          if (choice == 0) {
            set(property, hex(chooser.getColor()));
          } else if (choice == 1) {
            set(property, "");
          }
        });
    return fitting(button);
  }

  private JComponent colorWithDefault(final String property, final Color fallback) {
    final Color shown =
        Optional.ofNullable(project.getFiles().properties().getProperty(property))
            .flatMap(MapLookEditor::colorOf)
            .orElse(fallback);
    final JButton button = new JButton("      ");
    button.setBackground(shown);
    button.setToolTipText("#" + hex(shown));
    button.addActionListener(
        e ->
            Optional.ofNullable(JColorChooser.showDialog(page, "Choose a color", shown))
                .ifPresent(chosen -> set(property, hex(chosen))));
    return fitting(button);
  }

  /** The color as map.properties holds it, eg FF0011; empty for one the game cannot read. */
  static Optional<Color> colorOf(final String value) {
    final String hex = value.strip();
    return hex.matches("[0-9A-Fa-f]{6}")
        ? Optional.of(new Color(Integer.parseInt(hex, 16)))
        : Optional.empty();
  }

  static String hex(final Color color) {
    return String.format("%06X", color.getRGB() & 0xFFFFFF);
  }

  /** How a nation's units are drawn: tinted, lighter or darker, turned to face the other way. */
  private void unitTint() {
    final List<String> nations = MapXml.nations(project.game());
    if (nations.isEmpty()) {
      return;
    }
    if (!nations.contains(tintedNation)) {
      tintedNation = nations.getFirst();
    }
    form.section(
        "A nation's units",
        "Draws the nation's unit pictures tinted, lighter or darker, or facing the other way,"
            + " eg when the nations share one set of pictures.");
    final JComboBox<String> nation = new JComboBox<>(nations.toArray(String[]::new));
    nation.setSelectedItem(tintedNation);
    nation.addActionListener(
        e -> {
          tintedNation = (String) nation.getSelectedItem();
          fill();
        });
    form.field("Nation", fitting(nation));
    final String tint = TRANSFORM_COLOR + tintedNation;
    form.field("Tint", "None draws the pictures in their own colors.", color(tint, Optional.empty()));
    form.field(
        "Brightness",
        "From -100, dark, to 100, light; 0 as the pictures are.",
        number(TRANSFORM_BRIGHTNESS + tintedNation, 0, -100, 100));
    form.row(check("Facing the other way", TRANSFORM_FLIP + tintedNation, false));
  }

  private void blends() {
    form.section(
        "Blending",
        "How the relief is blended into the map when a player turns on View, Show Map Blends.");
    final JComboBox<String> mode = new JComboBox<>(BLEND_MODES.toArray(String[]::new));
    final String current =
        value(MapData.PROPERTY_MAP_MAPBLENDMODE, "normal").strip().toUpperCase(Locale.ROOT);
    if (!BLEND_MODES.contains(current)) {
      mode.addItem(current);
    }
    mode.setSelectedItem(current);
    mode.addActionListener(
        e -> set(MapData.PROPERTY_MAP_MAPBLENDMODE, (String) mode.getSelectedItem()));
    form.field("Mode", fitting(mode));
    form.field("Strength in %", percent(MapData.PROPERTY_MAP_MAPBLENDALPHA, 0.5f));
  }

  private void smallMap() {
    form.section("Small map", "The whole map in small, with a frame where the player looks.");
    form.field(
        "Territory colors in %",
        "How strongly the nations' colors show.",
        percent(SMALL_MAP_SATURATION, 1));
    form.field("Unit dots", "Their size in pixels.", number(SMALL_MAP_UNIT_SIZE, 4, 50));
    form.field("Frame", color(SMALL_MAP_BORDER, Optional.of(Color.LIGHT_GRAY)));
    form.field("Inside the frame", color(SMALL_MAP_FILL, Optional.of(Color.LIGHT_GRAY)));
    form.field(
        "Inside the frame, cover in %",
        "0 leaves the map clear inside the frame.",
        percent(SMALL_MAP_FILL_ALPHA, 0));
  }

  /** The title of a picture of the map the game saves, eg "Big World Round 3". */
  private void screenshot() {
    form.section("Map picture", "A player's File, Export map picture: its title.");
    form.row(check("With a title", MapData.PROPERTY_SCREENSHOT_TITLE_ENABLED, true));
    form.field("Title left", titleNumber(MapData.PROPERTY_SCREENSHOT_TITLE_X, 100_000));
    form.field("Title down", titleNumber(MapData.PROPERTY_SCREENSHOT_TITLE_Y, 100_000));
    form.field("Title size", titleNumber(MapData.PROPERTY_SCREENSHOT_TITLE_FONT_SIZE, 400));
    form.field(
        "Title color", color(MapData.PROPERTY_SCREENSHOT_TITLE_COLOR, Optional.of(Color.BLACK)));
  }

  /**
   * One of the title's place and size: the game reads them only all three, else takes 15 for
   * each, so a change writes all three.
   */
  private JComponent titleNumber(final String property, final int max) {
    return Form.newNumber(
        titlePlace().get(property),
        max,
        n -> {
          final Map<String, String> written = new LinkedHashMap<>();
          titlePlace().forEach((k, v) -> written.put(k, String.valueOf(v)));
          written.put(property, String.valueOf(n));
          setAll(written);
        });
  }

  private Map<String, Integer> titlePlace() {
    final List<String> keys =
        List.of(
            MapData.PROPERTY_SCREENSHOT_TITLE_X,
            MapData.PROPERTY_SCREENSHOT_TITLE_Y,
            MapData.PROPERTY_SCREENSHOT_TITLE_FONT_SIZE);
    final Map<String, Integer> place = new LinkedHashMap<>();
    try {
      for (final String key : keys) {
        place.put(key, Integer.parseInt(value(key, "").strip()));
      }
    } catch (final NumberFormatException e) {
      keys.forEach(key -> place.put(key, SCREENSHOT_TITLE_DEFAULT));
    }
    return place;
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
    setAll(Map.of(property, value));
  }

  private void setAll(final Map<String, String> values) {
    if (filling) {
      return;
    }
    editingHere = true;
    try {
      project.setMapProperties(values);
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
