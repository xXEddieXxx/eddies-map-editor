package eddies.mapeditor;

import com.formdev.flatlaf.util.UIScale;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
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
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.ListCellRenderer;
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
  private static final int TEXT_WIDTH = 400;
  // the page's width at most, so the cards do not stretch over a wide window
  private static final int MAX_WIDTH = 1040;
  private static final Dimension PREVIEW = new Dimension(480, 320);
  private static final int SWATCH = 20;
  // the scales the game's map creator offers; a map's own value is added to them
  private static final List<String> SCALES =
      List.of("1.25", "1", "0.875", "0.8333", "0.75", "0.6666", "0.5625", "0.5");

  // the game's ways of blending the relief into the map, BlendComposite
  private static final List<String> BLEND_MODES =
      List.of("NORMAL", "OVERLAY", "MULTIPLY", "DIFFERENCE", "LINEAR_LIGHT");
  // MapData keeps these keys private
  private static final String TRANSFORM_COLOR = MapFiles.UNIT_TINT;
  private static final String TRANSFORM_BRIGHTNESS = "units.transform.brightness.";
  private static final String TRANSFORM_FLIP = "units.transform.flip.";
  private static final String SMALL_MAP_SATURATION = "smallMap.territory.saturation";
  private static final String SMALL_MAP_UNIT_SIZE = "smallMap.unit.size";
  private static final String SMALL_MAP_BORDER = "smallMap.viewer.borderColor";
  private static final String SMALL_MAP_FILL = "smallMap.viewer.fillColor";
  private static final String SMALL_MAP_FILL_ALPHA = "smallMap.viewer.fillAlpha";
  // what the game takes for the title's place and size without all three
  private static final int SCREENSHOT_TITLE_DEFAULT = 15;
  private static final String POINTER_TIP =
      "The map's own pointer is misc/cursor.gif; this is the point of it that clicks.";

  private final MapProject project;
  private final Runnable goToTerritories;
  private final Runnable paintTextures;
  private final Form form = new Form(TEXT_WIDTH);
  // the four sections, two side by side when wide enough
  private final Form cards = new Form(TEXT_WIDTH);
  private final JPanel page = new JPanel(new BorderLayout());
  // while the form is filled from the map, its fields tell no changes
  private boolean filling;
  // a change made here keeps the form, so a field keeps its focus
  private boolean editingHere;
  // while the whole relief is made in the background
  private boolean making;
  // the nation whose units' look is shown
  private String tintedNation = "";

  MapLookEditor(final MapProject project) {
    this(project, () -> {});
  }

  /**
   * @param goToTerritories shows Territories, where the outlines a relief needs are found
   */
  MapLookEditor(final MapProject project, final Runnable goToTerritories) {
    this(project, goToTerritories, goToTerritories);
  }

  /**
   * @param goToTerritories shows Territories, where the outlines a relief needs are found
   * @param paintTextures shows Territories with a texture brush, where the relief's textures are
   *     painted
   */
  MapLookEditor(
      final MapProject project, final Runnable goToTerritories, final Runnable paintTextures) {
    this.project = project;
    this.goToTerritories = goToTerritories;
    this.paintTextures = paintTextures;
    cards.setBorder(BorderFactory.createEmptyBorder());
    final JComponent scrolled = SwingComponents.newJScrollPane(form);
    scrolled.setMaximumSize(new Dimension(UIScale.scale(MAX_WIDTH), Integer.MAX_VALUE));
    // a box gives it its maximum width at most, from the left
    final JPanel capped = new JPanel();
    capped.setLayout(new BoxLayout(capped, BoxLayout.X_AXIS));
    capped.add(scrolled);
    page.add(capped, BorderLayout.CENTER);
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
      cards.clear();
      cards.columns();
      onTheMap();
      units();
      relief();
      scrolling();
      cards.end();
      form.row(cards);
      form.more("look.smallMap", "Small map (5)", this::smallMap);
      form.more("look.title", "Exported map picture (4)", this::screenshot);
      form.more(
          "look.pointer",
          "Mouse pointer (2)",
          f -> {
            f.number("Point left", tipped(number(MapData.PROPERTY_MAP_CURSOR_HOTSPOT_X, 0, 256)));
            f.number("Point down", tipped(number(MapData.PROPERTY_MAP_CURSOR_HOTSPOT_Y, 0, 256)));
          });
      form.end();
    } finally {
      filling = false;
    }
  }

  private void onTheMap() {
    cards.section("On the map");
    cards.row(check("Territory names", MapData.PROPERTY_MAP_SHOWTERRITORYNAMES, true));
    cards.row(check("Sea zone names", MapData.PROPERTY_MAP_SHOWSEAZONENAMES, false));
    final List<String> unnamed = project.getFiles().listed(MapFiles.HIDDEN_NAMES);
    if (!unnamed.isEmpty()) {
      final JLabel hidden =
          Theme.newSecondaryLabel("No name on " + NationsEditor.shortList(unnamed));
      hidden.setToolTipText(
          "<html>The game draws no name on: "
              + SearchList.escape(String.join(", ", unnamed))
              + "<br>Set per territory in Territories, On the map.</html>");
      final JButton showAll = new JButton("Show all");
      Theme.setTertiaryButtonStyle(showAll);
      showAll.setToolTipText("The game draws every territory's name again");
      // not through set: the line goes with the list, so the form is built again
      showAll.addActionListener(e -> project.setMapProperty(MapFiles.HIDDEN_NAMES, ""));
      final JPanel line = fitting(hidden, showAll);
      line.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
      cards.row(line);
    }
    cards.row(check("PUs (resources)", MapData.PROPERTY_MAP_SHOWRESOURCES, true));
    cards.row(check("Capital markers", MapData.PROPERTY_MAP_SHOWCAPITOLMARKERS, true));
    cards.row(
        check("Territory effect markers", MapData.PROPERTY_MAP_USETERRITORYEFFECTMARKERS, false));
    cards.row(check("Comment markers", MapData.PROPERTY_MAP_SHOWCOMMENTS, true));
    cards.more(
        "look.onMap",
        "More (2)",
        f -> {
          f.row(
              check(
                  "Each nation's own convoy flags",
                  MapData.PROPERTY_MAP_USENATION_CONVOYFLAGS,
                  false));
          f.row(
              check(
                  "Names from their place's top left",
                  MapData.PROPERTY_MAP_DRAWNAMESFROMTOPLEFT,
                  false));
        });
  }

  private void units() {
    cards.section("Units");
    final JComboBox<String> size = scaleChoice();
    final JLabel scale = Theme.newSecondaryLabel("scale " + size.getSelectedItem());
    size.addActionListener(e -> scale.setText("scale " + size.getSelectedItem()));
    cards.field("Size", fitting(size, scale));
    cards.note("Smaller units: more fit into a territory. Or give them a bigger map:");
    final MapFiles files = project.getFiles();
    final JButton resize = new JButton("Resize map…");
    Theme.setSecondaryButtonStyle(resize);
    resize.setToolTipText(
        "Makes the whole map bigger or smaller: picture, borders and places. Units keep their"
            + " size");
    resize.setEnabled(files.hasOutlines() || files.hasPictureOrRelief());
    resize.addActionListener(e -> MapMakerWindow.resizeMap(page));
    final Dimension mapSize = files.size();
    cards.row(
        fitting(
            resize,
            Theme.newSecondaryLabel(
                resize.isEnabled()
                    ? "now " + mapSize.width + " × " + mapSize.height + " pixels"
                    : "once the map has a picture")));
    // label above, as Size's: both fields in one column
    cards.field(
        "Stack up to",
        "How many units of a kind are drawn on top of each other; 0 for one with a number.",
        number(MapData.PROPERTY_UNITS_STACK_SIZE, 0, 99));
    cards.more(
        "look.unitPicture",
        "Picture size and count position (4)",
        f -> {
          f.number(
              "Picture width",
              tipped(
                  number(MapData.PROPERTY_UNITS_WIDTH, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE, 400),
                  "The width of the unit pictures in pixels; 48 for the game's own."));
          f.number(
              "Picture height",
              tipped(
                  number(
                      MapData.PROPERTY_UNITS_HEIGHT, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE, 400),
                  "The height of the unit pictures in pixels; 48 for the game's own."));
          f.number(
              "Number left",
              tipped(
                  number(
                      MapData.PROPERTY_UNITS_COUNTER_OFFSET_WIDTH,
                      intValue(
                              MapData.PROPERTY_UNITS_WIDTH,
                              UnitImageFactory.DEFAULT_UNIT_ICON_SIZE)
                          / 4,
                      400),
                  "Where the number of units is drawn, from the left of the picture."));
          f.number(
              "Number down",
              tipped(
                  number(
                      MapData.PROPERTY_UNITS_COUNTER_OFFSET_HEIGHT,
                      intValue(
                          MapData.PROPERTY_UNITS_HEIGHT, UnitImageFactory.DEFAULT_UNIT_ICON_SIZE),
                      400),
                  "Where the number of units is drawn, from the top of the picture."));
        });
    if (!MapXml.nations(project.game()).isEmpty()) {
      cards.more("look.nation", "A nation's units: tint, brightness", this::unitTint);
    }
  }

  /** The components side by side at their own width, as a row would stretch one over the form. */
  private static JPanel fitting(final JComponent... components) {
    final JPanel fitting = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    fitting.setOpaque(false);
    // no gap before the first, so it lines up with the label above it
    fitting.setBorder(BorderFactory.createEmptyBorder(0, -Theme.GAP_S, 0, 0));
    for (final JComponent component : components) {
      fitting.add(component);
    }
    return fitting;
  }

  /** The scales as percents, eg "88 %" for 0.875; written as the scale. */
  private JComboBox<String> scaleChoice() {
    final String scale = value(MapData.PROPERTY_UNITS_SCALE, "1");
    final JComboBox<String> choice = new JComboBox<>(SCALES.toArray(String[]::new));
    if (!SCALES.contains(scale)) {
      choice.addItem(scale);
    }
    choice.setSelectedItem(scale);
    showAs(choice, MapLookEditor::percentOf);
    choice.addActionListener(
        e -> set(MapData.PROPERTY_UNITS_SCALE, (String) choice.getSelectedItem()));
    return choice;
  }

  static String percentOf(final String scale) {
    try {
      return Math.round(Float.parseFloat(scale.strip()) * 100) + " %";
    } catch (final NumberFormatException e) {
      return scale;
    }
  }

  /** The choice shows its items as the label makes them, and keeps them as they are written. */
  private static void showAs(final JComboBox<String> choice, final UnaryOperator<String> label) {
    final ListCellRenderer<? super String> plain = choice.getRenderer();
    choice.setRenderer(
        (list, item, index, selected, focused) ->
            plain.getListCellRendererComponent(
                list, item == null ? null : label.apply(item), index, selected, focused));
  }

  private JSpinner number(final String property, final int fallback, final int max) {
    return number(property, fallback, 0, max);
  }

  private JSpinner number(
      final String property, final int fallback, final int min, final int max) {
    return Form.newNumber(
        intValue(property, fallback), min, max, n -> set(property, String.valueOf(n)));
  }

  private static JSpinner tipped(final JSpinner spinner) {
    return tipped(spinner, POINTER_TIP);
  }

  // on the text field too, as the spinner's own tool tip shows only over its arrows
  private static JSpinner tipped(final JSpinner spinner, final String tip) {
    spinner.setToolTipText(tip);
    if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
      editor.getTextField().setToolTipText(tip);
    }
    return spinner;
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
  private JSpinner percent(final String property, final float fallback) {
    float share;
    try {
      share = Float.parseFloat(value(property, String.valueOf(fallback)).strip());
    } catch (final NumberFormatException e) {
      share = fallback;
    }
    return Form.newNumber(
        Math.round(share * 100), 100, n -> set(property, String.valueOf(n / 100.0)));
  }

  /** A swatch, the color as "#C0C0C0" and "Change…", which a color chooser changes. */
  private JPanel color(final String property, final Color fallback) {
    return color(property, fallback, "");
  }

  /**
   * @param unset what shows while the map sets no color, eg "None"; empty: the fallback color
   */
  private JPanel color(final String property, final Color fallback, final String unset) {
    final Optional<Color> set =
        Optional.ofNullable(project.getFiles().properties().getProperty(property))
            .flatMap(MapLookEditor::colorOf);
    final Color[] shown = {set.orElse(fallback)};
    final JLabel swatch = new JLabel(swatchOf(shown[0]));
    final JLabel hex = new JLabel("#" + hex(shown[0]));
    if (set.isEmpty() && !unset.isEmpty()) {
      swatch.setVisible(false);
      hex.setText(unset);
    }
    final JButton change = new JButton("Change…");
    Theme.setSecondaryButtonStyle(change);
    change.addActionListener(
        e -> {
          final JColorChooser chooser = new JColorChooser(shown[0]);
          if (Dialogs.ask(page, "Choose a colour", chooser, "Choose")) {
            shown[0] = chooser.getColor();
            swatch.setIcon(swatchOf(shown[0]));
            swatch.setVisible(true);
            hex.setText("#" + hex(shown[0]));
            set(property, hex(shown[0]));
          }
        });
    return fitting(swatch, hex, change);
  }

  private static Icon swatchOf(final Color color) {
    return new Icon() {
      @Override
      public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        g.setColor(color);
        g.fillRect(x, y, SWATCH, SWATCH);
        g.setColor(Theme.getSwatchOutlineColor(color));
        g.drawRect(x, y, SWATCH - 1, SWATCH - 1);
      }

      @Override
      public int getIconWidth() {
        return SWATCH;
      }

      @Override
      public int getIconHeight() {
        return SWATCH;
      }
    };
  }

  /**
   * The color map.properties holds, eg FF0011; empty for what is no color. TripleA reads six hex
   * digits only and stops at anything else; here blanks around them, a leading # and the short
   * form (F01) are read too, and {@link MapFiles#withReadableColors} writes them as TripleA does.
   */
  static Optional<Color> colorOf(final String value) {
    final String written = value.strip().replaceFirst("^#", "");
    final String hex = written.length() == 3 ? written.replaceAll("(.)", "$1$1") : written;
    return hex.matches("[0-9A-Fa-f]{6}")
        ? Optional.of(new Color(Integer.parseInt(hex, 16)))
        : Optional.empty();
  }

  static String hex(final Color color) {
    return String.format("%06X", color.getRGB() & 0xFFFFFF);
  }

  /** How a nation's units are drawn: tinted, lighter or darker, turned to face the other way. */
  private void unitTint(final Form f) {
    final List<String> nations = MapXml.nations(project.game());
    if (!nations.contains(tintedNation)) {
      tintedNation = nations.getFirst();
    }
    final JComboBox<String> nation = new JComboBox<>(nations.toArray(String[]::new));
    nation.setSelectedItem(tintedNation);
    nation.addActionListener(
        e -> {
          tintedNation = (String) nation.getSelectedItem();
          fill();
        });
    f.field("Nation", fitting(nation));
    final String property = TRANSFORM_COLOR + tintedNation;
    final JCheckBox tinted =
        new JCheckBox("Tint the pictures", colorOf(value(property, "")).isPresent());
    tinted.setToolTipText("Without a tint the pictures keep their own colors.");
    final JPanel tint = color(property, Color.GRAY, "None");
    final Runnable enable =
        () -> {
          for (final Component part : tint.getComponents()) {
            part.setEnabled(tinted.isSelected());
          }
        };
    // ticked, nothing is written until a color is chosen; unticked, the tint is taken away
    tinted.addActionListener(
        e -> {
          enable.run();
          if (!tinted.isSelected()) {
            set(property, "");
            // the tint row says None again
            fill();
          }
        });
    enable.run();
    f.row(tinted);
    f.field("Tint", tint);
    f.number(
        "Brightness",
        tipped(
            number(TRANSFORM_BRIGHTNESS + tintedNation, 0, -100, 100),
            "From -100, dark, to 100, light; 0 as the pictures are."));
    f.row(check("Facing the other way", TRANSFORM_FLIP + tintedNation, false));
  }

  private void relief() {
    cards.section("Relief");
    if (making) {
      cards.note("Making the relief…");
      final JProgressBar progress = new JProgressBar();
      progress.setIndeterminate(true);
      cards.row(progress);
    } else {
      cards.note(reliefNote());
    }
    final boolean canMake = project.getFiles().hasOutlines() && !making;
    final JButton create = new JButton("Create relief…");
    create.setToolTipText("Make a relief from the outlines: coasts, waves and raised land");
    create.addActionListener(e -> createRelief());
    final JButton load = new JButton("Load picture…");
    load.setToolTipText("A picture as big as the map; saving cuts it into the reliefTiles folder");
    load.addActionListener(e -> loadRelief());
    for (final JButton button : List.of(create, load)) {
      Theme.setSecondaryButtonStyle(button);
      button.setEnabled(canMake);
    }
    cards.row(fitting(create, load));
    if (!project.getFiles().hasOutlines()) {
      cards.note("Needs the territories' outlines first. Find them in Territories.");
      final JButton go = new JButton("Go to Territories");
      Theme.setTertiaryButtonStyle(go);
      go.addActionListener(e -> goToTerritories.run());
      cards.row(fitting(go));
    } else {
      // hard to find from here: the textures are a Paint brush of Territories
      cards.row(Help.link("Textures for the relief are painted in", "Territories", paintTextures));
    }
    final JCheckBox show = check("Show the relief", MapData.PROPERTY_MAP_HASRELIEF, true);
    if (!hasRelief()) {
      // nothing to show: unticked here, the file's setting stays as it is
      show.setSelected(false);
      show.setEnabled(false);
    }
    cards.row(show);
    cards.more("look.blend", "Blending", this::blends);
  }

  private void blends(final Form f) {
    final JComboBox<String> mode = new JComboBox<>(BLEND_MODES.toArray(String[]::new));
    final String current =
        value(MapData.PROPERTY_MAP_MAPBLENDMODE, "normal").strip().toUpperCase(Locale.ROOT);
    if (!BLEND_MODES.contains(current)) {
      mode.addItem(current);
    }
    mode.setSelectedItem(current);
    showAs(mode, MapLookEditor::blendWords);
    mode.addActionListener(
        e -> set(MapData.PROPERTY_MAP_MAPBLENDMODE, (String) mode.getSelectedItem()));
    f.field(
        "Mode",
        "How the relief is blended into the map when a player turns on View, Show Map Blends.",
        fitting(mode));
    f.number("Strength in %", percent(MapData.PROPERTY_MAP_MAPBLENDALPHA, 0.5f));
  }

  /** A blend mode as words, eg "Linear light" for LINEAR_LIGHT. */
  private static String blendWords(final String mode) {
    return mode.isEmpty()
        ? mode
        : mode.charAt(0) + mode.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  /** One choice for both directions, written as one step. */
  private void scrolling() {
    cards.section("Scrolling");
    final boolean x = bool(MapData.PROPERTY_MAP_SCROLLWRAPX, true);
    final boolean y = bool(MapData.PROPERTY_MAP_SCROLLWRAPY, false);
    final ButtonGroup group = new ButtonGroup();
    final JPanel choices = new JPanel(new GridLayout(2, 2, Theme.GAP_M, 0));
    choices.setOpaque(false);
    choices.add(scroll(group, "Around left and right", true, false, x && !y));
    choices.add(scroll(group, "Around top and bottom", false, true, !x && y));
    choices.add(scroll(group, "Both", true, true, x && y));
    choices.add(scroll(group, "Neither", false, false, !x && !y));
    cards.row(fitting(choices));
  }

  private JRadioButton scroll(
      final ButtonGroup group,
      final String label,
      final boolean aroundX,
      final boolean aroundY,
      final boolean chosen) {
    final JRadioButton radio = new JRadioButton(label, chosen);
    group.add(radio);
    radio.addActionListener(
        e -> {
          final Map<String, String> written = new LinkedHashMap<>();
          written.put(MapData.PROPERTY_MAP_SCROLLWRAPX, String.valueOf(aroundX));
          written.put(MapData.PROPERTY_MAP_SCROLLWRAPY, String.valueOf(aroundY));
          setAll(written);
        });
    return radio;
  }

  private void smallMap(final Form f) {
    f.number(
        "Territory colors in %",
        "The whole map in small, with a frame where the player looks. This is how strongly the"
            + " nations' colors show in it.",
        percent(SMALL_MAP_SATURATION, 1));
    f.number("Unit dots", tipped(number(SMALL_MAP_UNIT_SIZE, 4, 50), "Their size in pixels."));
    f.field("Frame", color(SMALL_MAP_BORDER, Color.LIGHT_GRAY));
    f.field("Inside the frame", color(SMALL_MAP_FILL, Color.LIGHT_GRAY));
    f.number(
        "Inside the frame, cover in %",
        tipped(percent(SMALL_MAP_FILL_ALPHA, 0), "0 leaves the map clear inside the frame."));
    final JButton update = new JButton("Update the small map");
    Theme.setSecondaryButtonStyle(update);
    update.setToolTipText(
        "Makes the small map's picture anew from the map's picture with its relief, written at"
            + " once. The game fills the land in the owners' colors, so the relief shows in the"
            + " sea and on the coasts. A save leaves a small map that is there as it is.");
    update.setEnabled(project.getFolder().isPresent());
    update.addActionListener(e -> updateSmallMap());
    f.row(update);
  }

  /**
   * Writes the small map from the map's picture and relief as they are now, in the background as
   * the picture may be big.
   */
  void updateSmallMap() {
    if (project.getFolder().isEmpty()) {
      return;
    }
    final MapFiles files = project.getFiles();
    final Path folder = project.getFolder().orElseThrow();
    new SwingWorker<Boolean, Void>() {
      @Override
      protected Boolean doInBackground() throws IOException {
        final Optional<BufferedImage> shown = files.shownPicture();
        if (shown.isPresent()) {
          MapFolder.writeSmallMap(folder, () -> shown);
        }
        return shown.isPresent();
      }

      @Override
      protected void done() {
        try {
          Dialogs.toast(
              page,
              get()
                  ? "✓ The small map shows the map with its relief"
                  : "! The map has no picture to make the small map from");
        } catch (final InterruptedException | ExecutionException e) {
          log.warn("Could not write the small map", e);
          Dialogs.warn(page, "Update the small map", "Could not write it.\n" + e.getMessage());
        }
      }
    }.execute();
  }

  /** The title of a picture of the map the game saves, eg "Big World Round 3". */
  private void screenshot(final Form f) {
    f.row(check("With a title", MapData.PROPERTY_SCREENSHOT_TITLE_ENABLED, true));
    f.number("Title left", titleNumber(MapData.PROPERTY_SCREENSHOT_TITLE_X, 100_000));
    f.number("Title down", titleNumber(MapData.PROPERTY_SCREENSHOT_TITLE_Y, 100_000));
    f.number("Title size", titleNumber(MapData.PROPERTY_SCREENSHOT_TITLE_FONT_SIZE, 400));
    f.field("Title color", color(MapData.PROPERTY_SCREENSHOT_TITLE_COLOR, Color.BLACK));
    f.note("Where a player's File ▸ Export map picture writes the title.");
  }

  /**
   * One of the title's place and size: the game reads them only all three, else takes 15 for
   * each, so a change writes all three.
   */
  private JSpinner titleNumber(final String property, final int max) {
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
    final JCheckBox check = new JCheckBox(label, bool(property, fallback));
    check.addActionListener(e -> set(property, String.valueOf(check.isSelected())));
    return check;
  }

  private boolean bool(final String property, final boolean fallback) {
    return Boolean.parseBoolean(value(property, String.valueOf(fallback)).strip());
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
    return hasRelief() ? "The map has relief tiles." : "The map has no relief yet.";
  }

  private boolean hasRelief() {
    return project.hasUnsavedRelief()
        || project
            .getFolder()
            .map(folder -> Files.isDirectory(folder.resolve("reliefTiles")))
            .orElse(false);
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
  void createRelief() {
    final MapFiles files = project.getFiles();
    final Optional<String> noRoom = files.whyNoRoomFor("the relief");
    if (noRoom.isPresent()) {
      Dialogs.warn(page, "Create relief", noRoom.get());
      return;
    }
    final Game game = project.game();
    final Set<String> water =
        files.polygons().keySet().stream()
            .filter(t -> MapXml.isWater(game, t))
            .collect(Collectors.toUnmodifiableSet());
    final ReliefOptions options = new ReliefOptions(files, water);
    if (Dialogs.ask(page, "Create relief", options.form, "Create relief")) {
      makeRelief(files, game, water, options.strength.getValue(), options.touches());
    }
  }

  /** How the relief should look, with a part of the map showing it. */
  static final class ReliefOptions {
    final Form form = new Form(PREVIEW.width);
    final JSlider strength = new JSlider(10, 100, 50);
    private final JCheckBox waves = new JCheckBox("Waves along the coasts", true);
    private final JCheckBox texture = new JCheckBox("Texture on the land", true);
    private final JLabel preview = new JLabel();
    private final MapFiles files;
    private final Set<String> water;
    private final Rectangle area;
    private final BufferedImage under;
    private final Map<String, Textures.Fill> fills;

    ReliefOptions(final MapFiles files, final Set<String> water) {
      this.files = files;
      this.water = water;
      fills = Textures.fills(files);
      area = previewArea(files, water, fills.keySet());
      under = pictureOf(files, area);
      // as wide as its widest text, so the slider does not move while dragged
      final JLabel value = new JLabel("100 %");
      value.setPreferredSize(value.getPreferredSize());
      value.setText(strength.getValue() + " %");
      strength.addChangeListener(
          e -> {
            value.setText(strength.getValue() + " %");
            if (!strength.getValueIsAdjusting()) {
              show();
            }
          });
      strength.setToolTipText("How strongly the relief is drawn");
      waves.addActionListener(e -> show());
      texture.addActionListener(e -> show());
      show();
      final JLabel label = new JLabel("Strength");
      label.setLabelFor(strength);
      form.note(
          "A part of your map with the relief. Textures painted in Territories are included: "
              + (fills.isEmpty()
                  ? "no territory has one yet."
                  : fills.size() == 1
                      ? "1 territory has one."
                      : fills.size() + " territories have one.")
              + " Saving writes it for the whole map into reliefTiles.");
      form.row(preview);
      form.row(
          fitting(
              label,
              (JComponent) Box.createHorizontalStrut(Theme.GAP_S),
              new JLabel("Light"),
              strength,
              new JLabel("Strong"),
              value));
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
          ReliefMaker.make(
              area, files.polygons(), water, strength.getValue(), touches(), fills),
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
    making = true;
    fill();
    new SwingWorker<BufferedImage, Void>() {
      @Override
      protected BufferedImage doInBackground() {
        return ReliefMaker.make(
            new Rectangle(files.size()),
            files.polygons(),
            water,
            strength,
            touches,
            Textures.fills(files));
      }

      @Override
      protected void done() {
        making = false;
        fill();
        final BufferedImage relief;
        try {
          relief = get();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (final ExecutionException e) {
          log.warn("Could not make the relief", e.getCause());
          Dialogs.warn(
              page, "Could not make the relief", String.valueOf(e.getCause().getMessage()));
          return;
        }
        if (project.getFiles() != files || project.game() != game) {
          Dialogs.toast(page, "! The map changed while the relief was made. Create it again.");
          return;
        }
        project.setRelief(relief);
      }
    }.execute();
  }

  /**
   * A part of the map with a coast, the one nearest its middle; the middle if it has none. With
   * textures, a border of a textured territory instead, so they show.
   */
  private static Rectangle previewArea(
      final MapFiles files, final Set<String> water, final Set<String> textured) {
    final Dimension size = files.size();
    final Point middle = new Point(size.width / 2, size.height / 2);
    final List<Polygon> seas =
        files.polygons().entrySet().stream()
            .filter(e -> water.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .toList();
    final Point at =
        files.polygons().entrySet().stream()
            .filter(
                e ->
                    textured.isEmpty()
                        ? !water.contains(e.getKey())
                        : textured.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream())
            .flatMap(p -> IntStream.range(0, p.npoints).mapToObj(i -> corner(p, i)))
            .filter(
                p ->
                    !textured.isEmpty()
                        || seas.stream().anyMatch(sea -> sea.intersects(p.x - 4, p.y - 4, 8, 8)))
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
    Dialogs.warn(
        page,
        "Could not load the relief",
        "Could not take " + file.getFileName() + " as the relief.\n" + why);
  }
}

