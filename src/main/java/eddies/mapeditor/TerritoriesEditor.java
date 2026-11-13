package eddies.mapeditor;

import games.strategy.triplea.Constants;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import eddies.mapeditor.MapCanvas.PlaceKind;
import eddies.mapeditor.MapCanvas.ShownPlaces;
import eddies.mapeditor.MapFiles.Marker;
import eddies.mapeditor.MapFiles.UnitLayout;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.DefaultFormatter;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.swing.FileChooser;
import org.triplea.swing.SwingComponents;
import org.triplea.swing.WrapLayout;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapGuide.Step;
import eddies.mapeditor.MapProject.Renamed;

/**
 * The main editor: every territory with its kind, owner, value, capital, victory city and the units
 * it starts with. A click on the map chooses a territory, or paints the brush onto it.
 */
final class TerritoriesEditor implements Editor {
  // first in the owner boxes; told by its place, as a map may have a nation called "No owner"
  private static final String NO_OWNER = "No owner";
  private static final String OWNER_AT_START = "(its owner at the start)";
  private static final List<Integer> VALUES =
      List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20);
  private static final int MAX_PUS = 999;
  private static final String PUS_BRUSH = "PUs per turn";
  private static final int TILE_COLUMNS = 4;
  // the width of the decoration box: G40's misc folder holds dozens of long names
  private static final String DECORATION_WIDTH = "a_decoration_name.png";
  private static final String LIST = "list";
  private static final String TERRITORY = "territory";
  private static final String SPLIT = "split";
  private static final String NAME_REFUSED = "Already used, empty, or holds : < (.";
  static final String ERASER_HOW_TO = "Click a border to erase all of it; drag to erase a part.";
  private static final int MAX_NAMED = 6;
  // how near a border, in pixels of the screen, a drawn line's end is brought to it
  private static final int SNAP_PIXELS = 12;

  // the colors a line drawn into the picture may have; the first stands for the picture's own
  private static final List<String> LINE_COLOR_NAMES = List.of("Like its lines", "Black", "Grey");
  private static final List<Color> LINE_COLORS =
      List.of(Color.BLACK, Color.BLACK, new Color(0x80, 0x80, 0x80));

  private static final Brush CAPITAL =
      new Brush(
          "Capital",
          MapGuide.CAPITAL_HOW_TO,
          Optional.empty(),
          MapXml::withCapitalMovedTo);

  // with the effect brushes: the map shows the effects it clears
  private static final Brush NO_EFFECTS =
      new Brush(
          "No effects",
          "Clearing effects: click or drag across territories to take all their effects away.",
          Optional.empty(),
          (g, t) -> TerritoryXml.withEffects(g, t, List.of()));

  // with the texture brushes: the map shows the textures it clears
  private static final Brush NO_TEXTURE =
      new Brush(
          "No texture",
          "Clearing textures: click or drag across territories to take their texture away.",
          Optional.empty(),
          (g, t) -> g,
          Optional.empty(),
          (f, t) -> f.withTexture(t, Optional.empty()),
          Optional.empty());

  /**
   * How Draw borders draws: a dragged line, clicked points, an ellipse from corner to corner; or
   * takes lines away under a dragged band.
   */
  private enum DrawBrush {
    FREEHAND,
    LINE,
    CIRCLE,
    ERASER
  }

  /** What a click on the map does besides choosing the territory. */
  private enum Mode {
    SELECT,
    PAINT,
    DECORATE,
    MOVE_PLACES,
    SPLIT,
    MERGE,
    DRAW
  }

  private final MapProject project;
  private final MapView map;
  private final BiConsumer<String, String> openTool;
  private final CardLayout cards = new CardLayout();
  private final JPanel page = new JPanel(cards);
  private final SearchList list;
  private final Form inspector = new Form(280);
  private final JScrollPane inspectorScroll = SwingComponents.newJScrollPane(inspector);
  private final Form splitForm = new Form(280);
  private final JToggleButton select = new JToggleButton("Select");
  private final JToggleButton paint = new JToggleButton("Paint ▾");
  // not shown: the Decorations mode starts from the Places menu, but its button is in the group
  private final JToggleButton decorations = new JToggleButton();
  private final JToggleButton drawBorders = new JToggleButton("Draw borders");
  // not a mode: the places shown on every territory stay while modes change
  private final JToggleButton places = new JToggleButton("Places ▾");
  private ShownPlaces shownPlaces = ShownPlaces.NONE;
  // the layouts' places per territory at most; huge sea zones rely on overflow
  private final JSpinner mostPlaces = new JSpinner(new SpinnerNumberModel(16, 1, 999, 1));
  private final Consumer<BufferedImage> findTerritories;
  private final JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
  // in the inspector, built again with it; one button, so the banner's Done can untoggle it
  private final JToggleButton movePlaces = new JToggleButton("Move places");
  private final JComboBox<String> decorationPicture = new JComboBox<>();
  // the chosen texture's offset, in the banner while its brush is chosen
  private final JSpinner textureX = new JSpinner(new SpinnerNumberModel(0, -9999, 9999, 1));
  private final JSpinner textureY = new JSpinner(new SpinnerNumberModel(0, -9999, 9999, 1));
  // what the PU brush paints, in the banner while it is chosen; read when a stroke is painted
  private final JSpinner paintedPus = Form.newNumber(1, MAX_PUS, value -> {});
  private final transient Brush pusBrush =
      new Brush(
          PUS_BRUSH,
          "Painting PUs per turn: click or drag across territories to give them",
          Optional.empty(),
          // the field's value when painted
          (g, t) -> MapXml.withProduction(g, t, (Integer) paintedPus.getValue()));
  private Mode mode = Mode.SELECT;
  private Optional<Brush> brush = Optional.empty();
  // the territory whose units the copy brush copies, taken when the brush is chosen
  private Optional<String> copySource = Optional.empty();
  // the effect painted and its territories marked, when the Terrain effects tool asked for it
  private Optional<String> paintedEffect = Optional.empty();
  // a line drawn across the chosen territory that cuts it, until Split or Cancel
  private Optional<OutlineCut.Cut> pendingCut = Optional.empty();
  private List<Point> pendingLine = List.of();
  // how a split draws its border into the picture: as the picture's lines, the width as last chosen
  private Optional<MapPicture.LineStyle> foundStyle = Optional.empty();
  private Optional<BufferedImage> styleOf = Optional.empty();
  private Optional<Integer> chosenWidth = Optional.empty();
  private Optional<Color> chosenColor = Optional.empty();
  // the eraser's width as last chosen; at first three times the picture's line width
  private Optional<Integer> eraserWidth = Optional.empty();
  // Draw borders' brush, kept for the session
  private DrawBrush drawBrush = DrawBrush.FREEHAND;
  // a picture only in tiles is put together in the background before lines go into it
  private boolean preparingPicture;
  private boolean pictureTooBig;
  private boolean pictureUnread;
  private Optional<JTextField> splitName = Optional.empty();
  private String card = LIST;
  private boolean refilling;
  private @Nullable String selected;
  private boolean editingHere;
  // a name the rename refused, kept in the field until another territory is chosen
  private Optional<String> refusedName = Optional.empty();
  // the commit of a text being typed, made a step by commit()
  private Optional<Runnable> typing = Optional.empty();

  /** Without a way to open another tool: "Edit in Connections" does nothing. */
  TerritoriesEditor(final MapProject project, final MapView map) {
    this(project, map, (tool, item) -> {}, picture -> {});
  }

  /**
   * @param openTool opens the tool of the title with the item chosen, eg ("Connections", territory)
   * @param findTerritories finds the territories in the picture with borders drawn into it, as
   *     Redraw borders does
   */
  TerritoriesEditor(
      final MapProject project,
      final MapView map,
      final BiConsumer<String, String> openTool,
      final Consumer<BufferedImage> findTerritories) {
    this.project = project;
    this.map = map;
    this.openTool = openTool;
    this.findTerritories = findTerritories;
    list =
        new SearchList(
            "territory",
            "territories",
            () -> MapXml.territories(project.game()),
            t -> t,
            this::detail,
            this::chooseInList);
    // the fields start where the tool's title does: the panel has its margin already
    for (final Form form : List.of(inspector, splitForm)) {
      form.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XS, 0, Theme.GAP_L, Theme.GAP_L));
    }
    page.add(newListCard(), LIST);
    page.add(newTerritoryCard(), TERRITORY);
    page.add(SwingComponents.newJScrollPane(splitForm), SPLIT);
    buildModes();
    // a number typed counts without Enter: a click on Squeeze closes the menu before focus-lost,
    // and a press on the map takes no focus, so a stroke would paint the value before
    List.of(mostPlaces, paintedPus, textureX, textureY).forEach(TerritoriesEditor::takeAsTyped);
    project.onChange(
        () -> {
          list.refresh();
          final boolean sourceGone =
              copySource.filter(s -> !MapXml.territories(project.game()).contains(s)).isPresent();
          final boolean keeperGone =
              mode == Mode.MERGE && !MapXml.territories(project.game()).contains(selected);
          // a cut of the outlines before the change, eg an undo, would split what is no more
          if (sourceGone || keeperGone || pendingCut.isPresent()) {
            resetMode();
            refreshMap();
          } else if (mode == Mode.PAINT && paintedEffect.isPresent()) {
            refreshMap();
          }
          // an undo may move the texture back
          brush.flatMap(Brush::texture).ifPresent(this::showOffset);
          fillDecorationPictures();
          if (!editingHere) {
            showInspector();
          }
        });
    fillDecorationPictures();
    decorationPicture.setPrototypeDisplayValue(DECORATION_WIDTH);
    decorationPicture.addActionListener(
        e -> {
          if (!refilling && mode == Mode.DECORATE) {
            applyMode();
          }
        });
    for (final JSpinner offset : List.of(textureX, textureY)) {
      offset.addChangeListener(
          e -> {
            if (!refilling) {
              brush
                  .flatMap(Brush::texture)
                  .ifPresent(
                      texture ->
                          project.setTextureOffset(
                              texture,
                              new Point((int) textureX.getValue(), (int) textureY.getValue())));
            }
          });
    }
    textureX.setToolTipText("Moves the texture right, eg to line it up with the one beside it");
    textureY.setToolTipText("Moves the texture down, eg to line it up with the one beside it");
    movePlaces.addActionListener(
        e -> {
          if (movePlaces.isSelected()) {
            startMode(Mode.MOVE_PLACES);
          } else {
            endMode();
          }
        });
    showInspector();
  }

  /** The list with "⋯" at the right of its count, for the names menu. */
  private JComponent newListCard() {
    final JPopupMenu namesMenu = new JPopupMenu();
    final JMenuItem exportNames = namesMenu.add("Export names…");
    exportNames.setToolTipText("Writes the names as lines name; to fill in the new names");
    exportNames.addActionListener(e -> exportNames());
    final JMenuItem importNames = namesMenu.add("Import names…");
    importNames.setToolTipText("Renames territories by a text or CSV file of lines old;new");
    importNames.addActionListener(e -> importNames());
    namesMenu.addSeparator();
    final JMenuItem exportPictures = namesMenu.add("Export territory pictures…");
    exportPictures.setToolTipText(
        "A PNG of each territory chosen, to draw on in a paint program and lay over the map");
    exportPictures.addActionListener(e -> exportPictures());
    namesMenu.addSeparator();
    final JMenuItem removeMany = namesMenu.add("Remove territories…");
    removeMany.setToolTipText(
        "Tick the territories to remove, the smallest first, eg islands found by mistake");
    removeMany.addActionListener(e -> removeTerritories());
    final JButton names = new JButton("⋯");
    Theme.setTertiaryButtonStyle(names);
    final String manyTip = "Many territories at once: names and pictures as files, remove";
    names.setToolTipText(manyTip);
    names.getAccessibleContext().setAccessibleName(manyTip);
    names.addActionListener(e -> namesMenu.show(names, 0, names.getHeight()));
    // over the list's count line, which the list draws itself
    final JPanel listCard =
        new JPanel(null) {
          @Serial private static final long serialVersionUID = 1L;

          @Override
          public void doLayout() {
            list.setBounds(0, 0, getWidth(), getHeight());
            final Dimension size = names.getPreferredSize();
            names.setBounds(getWidth() - size.width - Theme.GAP_S, -2, size.width, size.height);
          }

          @Override
          public Dimension getPreferredSize() {
            return list.getPreferredSize();
          }

          @Override
          public boolean isOptimizedDrawingEnabled() {
            return false;
          }
        };
    listCard.add(names);
    listCard.add(list);
    return listCard;
  }

  /** "← All territories" over the chosen territory's form; Esc goes back too. */
  private JComponent newTerritoryCard() {
    final JButton back = new JButton("← All territories");
    Theme.setTertiaryButtonStyle(back);
    back.addActionListener(e -> showList());
    final JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    // the link's text in line with the fields; its hover background reaches out to the left
    top.setBorder(BorderFactory.createEmptyBorder(0, -back.getInsets().left, 0, 0));
    top.add(back);
    final JPanel territoryCard = new JPanel(new BorderLayout(0, Theme.GAP_XS));
    territoryCard.add(top, BorderLayout.NORTH);
    territoryCard.add(inspectorScroll, BorderLayout.CENTER);
    territoryCard.registerKeyboardAction(
        e -> showList(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    return territoryCard;
  }

  /**
   * Select, Paint (a brush with ▾) and Places (with ▾) above the map; behind a line of its own
   * Draw borders, which changes the map's picture.
   */
  private void buildModes() {
    final ButtonGroup group = new ButtonGroup();
    Theme.setNamedMapToolStyle(select, Glyph.SELECT);
    // their glyphs also beside their names: the brush and the pencil are how they are known
    Theme.setGlyphAndNameMapToolStyle(paint, Glyph.BRUSH);
    Theme.setGlyphAndNameMapToolStyle(drawBorders, Glyph.PENCIL);
    for (final JToggleButton button : List.of(select, paint, decorations, drawBorders)) {
      group.add(button);
    }
    List.of(select, paint).forEach(modes::add);
    select.setSelected(true);
    // with the name, as only the glyph shows when the toolbar is short of room
    select.setToolTipText("Select: a click on the map chooses the territory");
    paint.setToolTipText(
        "Paint: give many territories an owner, a capital, PUs, units, rules, effects or"
            + " textures");
    paint.getAccessibleContext().setAccessibleName("Paint");
    select.addActionListener(e -> endMode());
    paint.addActionListener(
        e -> {
          // the click toggled it: the menu decides
          showModeButtons();
          paintMenu().show(paint, 0, paint.getHeight());
        });
    drawBorders.setToolTipText(
        "Draw borders: changes the map's picture. Draw border lines into it by hand, then make"
            + " territories of them");
    drawBorders.addActionListener(e -> startMode(Mode.DRAW));
    // by its name; short of room the pin alone
    Theme.setNamedMapToolStyle(places, Glyph.PLACES);
    places.getAccessibleContext().setAccessibleName("Places");
    places.setToolTipText(
        "Places: show where the game draws units, names, flags and markers on every territory,"
            + " drag one to move it; lay out units; create missing places; place decorations");
    places.addActionListener(
        e -> {
          // the click toggled it: the menu decides
          showModeButtons();
          placesMenu().show(places, 0, places.getHeight());
        });
    modes.add(places);
    final JSeparator line = new JSeparator(SwingConstants.VERTICAL);
    line.setPreferredSize(new Dimension(line.getPreferredSize().width, UIScale.scale(24)));
    modes.add(line);
    modes.add(drawBorders);
  }

  /**
   * A check per kind of place in plain words, several at once, the menu staying open to check
   * more; then laying out unit places and creating the missing ones, each a menu of its own.
   */
  JPopupMenu placesMenu() {
    final JPopupMenu menu = new JPopupMenu();
    menu.add(placesItem("Units", MapFiles.PLACEMENTS, PlaceKind.UNITS));
    menu.add(placesItem("Centers", MapFiles.CENTERS, PlaceKind.CENTERS));
    for (final Marker marker : Marker.values()) {
      menu.add(placesItem(placesName(marker), marker.getFile(), marker));
    }
    final JCheckBoxMenuItem decorate = new JCheckBoxMenuItem("Decorations", mode == Mode.DECORATE);
    decorate.setToolTipText(
        (decorationPicture.getItemCount() > 0
                ? "Place pictures from the map's misc folder anywhere on the map"
                : "The map's misc folder has no pictures to place; Files imports them")
            + " — decorations.txt");
    decorate.setEnabled(decorationPicture.getItemCount() > 0);
    decorate.addActionListener(
        e -> {
          if (decorate.isSelected()) {
            startMode(Mode.DECORATE);
          } else {
            endMode();
          }
        });
    menu.add(decorate);
    menu.addSeparator();
    menu.add(layOutMenu());
    menu.add(createMissingMenu());
    return menu;
  }

  /** The marker's places in the menu, in plain words. */
  private static String placesName(final Marker marker) {
    return switch (marker) {
      case NAME -> "Names";
      case VALUE -> "PUs";
      case CAPITAL -> "Capitals";
      case VICTORY_CITY -> "Victory cities";
      case BLOCKADE -> "Blockades";
      case CONVOY -> "Convoys";
      case COMMENT -> "Comments";
      case KAMIKAZE -> "Kamikaze";
      case EFFECT -> "Terrain effects";
    };
  }

  /** How many places a territory gets at most, then each layout for the chosen one and for all. */
  private JMenu layOutMenu() {
    final JMenu layOut = new JMenu("Lay out unit places");
    final JPanel most = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    most.setOpaque(false);
    // in line with the items: a centered panel would push the menu's rows apart
    most.setAlignmentX(Component.LEFT_ALIGNMENT);
    most.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_XS, Theme.GAP_M - Theme.GAP_S, Theme.GAP_XS, 0));
    final JLabel atMost = new JLabel("At most");
    atMost.setLabelFor(mostPlaces);
    most.add(atMost);
    most.add(mostPlaces);
    most.add(new JLabel("per territory"));
    mostPlaces.setToolTipText("More units than places go on beside the last place");
    layOut.add(most);
    for (final UnitLayout layout : UnitLayout.values()) {
      final String name =
          layout == UnitLayout.SQUEEZE ? "Round the center" : "In rows below the center";
      layOut.addSeparator();
      Optional.ofNullable(selected)
          .filter(project.getFiles().polygons()::containsKey)
          .ifPresent(t -> layOut.add(layOutItem(name + " — in " + t, layout, List.of(t))));
      layOut.add(
          layOutItem(
              name + " — in all territories",
              layout,
              List.copyOf(project.getFiles().polygons().keySet())));
    }
    return layOut;
  }

  /** Lays out the territories' unit places as one step, and shows them. */
  private JMenuItem layOutItem(
      final String text, final UnitLayout layout, final List<String> territories) {
    final JMenuItem item = new JMenuItem(text);
    item.setToolTipText(
        layout == UnitLayout.SQUEEZE
            ? "Places round the center, clockwise from 12:00, 4 px apart"
            : "Places in rows below the center, the first at 6:00, 4 px apart");
    item.addActionListener(
        e -> {
          final Optional<String> refused = project.whyNoPlaces(MapFiles.PLACEMENTS);
          if (refused.isPresent()) {
            Dialogs.toast(
                page, refused.get(), "Files", () -> MapMakerWindow.openTool(page, "Files", null));
            return;
          }
          project.layOutUnitPlaces(layout, (Integer) mostPlaces.getValue(), territories);
          shownPlaces = shownPlaces.with(PlaceKind.UNITS);
          showModeButtons();
          map.showPlaces(shownPlaces);
        });
    return item;
  }

  /**
   * Per kind of place, and for all, how many territories miss their line in its file; an item
   * creates them.
   */
  private JMenu createMissingMenu() {
    final JMenu create = new JMenu("Create missing places");
    final List<Enum<?>> kinds = new ArrayList<>(List.of(PlaceKind.UNITS));
    kinds.addAll(List.of(Marker.values()));
    // every territory is asked about, per kind
    final Game territories = MapXml.withTerritoryAttachmentsOnly(project.game());
    int all = 0;
    for (final Enum<?> kind : kinds) {
      final int missing = missingPlaces(territories, kind);
      all += missing;
      final String file = kind instanceof Marker marker ? marker.getFile() : MapFiles.PLACEMENTS;
      final JMenuItem item =
          createMissingItem(
              kind instanceof Marker marker ? placesName(marker) : "Units",
              missing,
              "Each territory without a line in "
                  + file
                  + " gets its found place there; places set already stay",
              List.of(kind));
      if (project.getFiles().placesUnread(file)) {
        item.setText(item.getText().replaceFirst(" — .*", " — file does not read"));
        item.setToolTipText(
            file + " does not read, eg for a name twice: open it in Files to put it right");
      }
      create.add(item);
    }
    create.addSeparator();
    create.add(
        createMissingItem(
            "All kinds",
            all,
            "Each territory gets the found place of every kind it has none of; places set already"
                + " stay",
            kinds));
    return create;
  }

  private int missingPlaces(final Game territories, final Enum<?> kind) {
    final MapFiles files = project.getFiles();
    return kind instanceof Marker marker
        ? files.withoutMarkerPlace(territories, marker).size()
        : files.withoutUnitPlaces().size();
  }

  private JMenuItem createMissingItem(
      final String name, final int missing, final String tip, final List<Enum<?>> kinds) {
    final JMenuItem item =
        new JMenuItem(name + " — " + (missing == 0 ? "none missing" : missing + " missing"));
    item.setEnabled(missing > 0);
    item.setToolTipText(tip);
    item.addActionListener(e -> createMissingPlaces(kinds));
    return item;
  }

  /**
   * Gives every territory that misses a place of these kinds its found one, as one step, so the
   * next save writes them; one kind then shows on the map.
   */
  void createMissingPlaces(final List<Enum<?>> kinds) {
    commit();
    // a marker's place is found from the territory's own attachment
    final Game game = MapXml.withTerritoryAttachmentsOnly(project.game());
    final int missing = kinds.stream().mapToInt(kind -> missingPlaces(game, kind)).sum();
    project.editFiles(
        files -> {
          MapFiles filled = files;
          for (final Enum<?> kind : kinds) {
            filled =
                kind instanceof Marker marker
                    ? filled.withMissingMarkerPlaces(game, marker)
                    : filled.withMissingUnitPlaces();
          }
          return filled;
        });
    if (kinds.size() == 1) {
      shownPlaces = shownPlaces.with(kinds.getFirst());
      showModeButtons();
      map.showPlaces(shownPlaces);
    }
    Dialogs.toast(
        page,
        "✓ "
            + missing
            + (missing == 1 ? " place created." : " places created.")
            + " The next save writes "
            + (missing == 1 ? "it." : "them."));
  }

  private JCheckBoxMenuItem placesItem(final String text, final String file, final Enum<?> kind) {
    final JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, shownPlaces.shows(kind));
    item.setToolTipText(
        "Shown on every territory, to drag"
            + (kind == PlaceKind.UNITS ? "; a click in the chosen territory adds a place" : "")
            + " — "
            + file);
    item.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true);
    item.addActionListener(
        e -> {
          shownPlaces = item.isSelected() ? shownPlaces.with(kind) : shownPlaces.without(kind);
          showModeButtons();
          map.showPlaces(shownPlaces);
        });
    return item;
  }

  /** The kind of place a file holds, eg name_place.txt the names'; none for another file. */
  private static Optional<Enum<?>> placeKind(final String file) {
    if (MapFiles.PLACEMENTS.equals(file)) {
      return Optional.of(PlaceKind.UNITS);
    }
    if (MapFiles.CENTERS.equals(file)) {
      return Optional.of(PlaceKind.CENTERS);
    }
    return Arrays.stream(Marker.values())
        .filter(marker -> marker.getFile().equals(file))
        .findFirst()
        .map(marker -> marker);
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
    return "Click a territory to change it. Use Paint to give many territories an owner, a value"
        + " or units at once.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    map.useFor(MapMode.GAME, modes, this::clickOnMap);
    map.showPlaces(shownPlaces);
    applyMode();
    map.select(selected);
  }

  /**
   * Shows the territory; or, for a place file's name, eg name_place.txt from Files, its kind of
   * place on every territory, with the Places menu open to create the missing ones.
   */
  @Override
  public void show(final String territory) {
    final Optional<Enum<?>> kind =
        placeKind(territory).filter(k -> !MapXml.territories(project.game()).contains(territory));
    if (kind.isPresent()) {
      shownPlaces = shownPlaces.with(kind.get());
      showModeButtons();
      map.showPlaces(shownPlaces);
      SwingUtilities.invokeLater(
          () -> {
            if (places.isShowing()) {
              placesMenu().show(places, 0, places.getHeight());
            }
          });
      return;
    }
    choose(territory);
    list.choose(territory);
    map.scrollTo(territory);
  }

  /** "Sea zone", "Italians · 1 PU", "No owner · 2 PUs", with " ●" when changed. */
  private String detail(final String territory) {
    final Game game = project.game();
    final String kind =
        MapXml.isWater(game, territory)
            ? "Sea zone"
            : MapXml.owner(game, territory).orElse(NO_OWNER);
    final int value = MapXml.production(game, territory);
    return kind
        + (value > 0 ? " · " + pus(value) : "")
        + (project.isChanged(territory) ? " ●" : "");
  }

  private static String pus(final int value) {
    return value == 1 ? "1 PU" : value + " PUs";
  }

  @Override
  public void commit() {
    typing.ifPresent(Runnable::run);
  }

  /** Makes the spinner take each valid number as it is typed, not only on Enter or focus-lost. */
  private static void takeAsTyped(final JSpinner spinner) {
    final JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) spinner.getEditor();
    ((DefaultFormatter) editor.getTextField().getFormatter()).setCommitsOnValidEdit(true);
  }

  /** A click on the map: paints the brush, if any, and chooses the territory. */
  void clickOnMap(final String territory) {
    if (mode == Mode.SPLIT || mode == Mode.DRAW) {
      // a click while drawing the line must not change the territory being cut
      return;
    }
    // a text being typed is the shown territory's, before the inspector shows another
    commit();
    if (mode == Mode.MERGE && selected != null) {
      // the keeper stays chosen: each click brings it another part
      if (!territory.equals(selected)) {
        merge(selected, territory);
      }
      return;
    }
    final boolean capitalBrush = mode == Mode.PAINT && brush.equals(Optional.of(CAPITAL));
    final boolean capitalsWanted = capitalBrush && !MapGuide.withoutCapital(project).isEmpty();
    final Optional<String> capitalBefore =
        MapXml.owner(project.game(), territory)
            .filter(owner -> capitalBrush)
            .map(owner -> MapXml.capitalsOf(project.game(), owner))
            .filter(capitals -> capitals.size() == 1)
            .map(List::getFirst);
    final Game before = project.game();
    brush.filter(b -> mode == Mode.PAINT).ifPresent(b -> paint(b, List.of(territory)));
    final boolean capitalPainted = capitalBrush && project.game() != before;
    if (capitalPainted && capitalsWanted && MapGuide.withoutCapital(project).isEmpty()) {
      // the last capital: the brush has done its work, and a later click must not move one
      final boolean stepTold = map.goalStep().filter(Step.Id.CAPITALS::equals).isPresent();
      endMode();
      if (!stepTold) {
        Dialogs.toast(page, "✓ Every nation with land has its capital");
      }
    } else if (capitalPainted) {
      tellCapital(territory, capitalBefore);
    }
    choose(territory);
    list.choose(territory);
  }

  /** "Canada's capital is now Ottawa (was Toronto)", with Undo, as a click moved it. */
  private void tellCapital(final String territory, final Optional<String> before) {
    final String owner = MapXml.owner(project.game(), territory).orElse("");
    final int capitals = MapXml.capitalsOf(project.game(), owner).size();
    Dialogs.toast(
        page,
        capitals > 1
            ? "✓ " + territory + " is another capital of " + owner + " (it has " + capitals + ")"
            : "✓ "
                + owner
                + "'s capital is now "
                + territory
                + before.map(was -> " (was " + was + ")").orElse(""),
        "Undo",
        project::undo);
  }

  /** A drag across the map with a brush: paints each territory it passed, as one step. */
  void paintStroke(final List<String> territories) {
    commit();
    // capitals are clicked one by one: a drag across land would make every territory one
    brush
        .filter(b -> mode == Mode.PAINT && b != CAPITAL)
        .ifPresent(b -> paint(b, territories));
  }

  /** The brush on the territories as one step: a brush changes the game or the files. */
  private void paint(final Brush b, final List<String> territories) {
    project.edit(
        game -> {
          Game painted = game;
          for (final String territory : territories) {
            painted = b.paint().apply(painted, territory);
          }
          return painted;
        });
    project.editFiles(
        files -> {
          MapFiles painted = files;
          for (final String territory : territories) {
            painted = b.paintFiles().apply(painted, territory);
          }
          return painted;
        });
  }

  /** A choice in the list also brings the territory into view; one on the map is in it. */
  private void chooseInList(final String territory) {
    choose(territory);
    map.scrollTo(territory);
  }

  private void choose(final String territory) {
    commit();
    final boolean another = !territory.equals(selected);
    selected = territory;
    card = TERRITORY;
    map.select(territory);
    if (another) {
      refusedName = Optional.empty();
      // the line cuts the territory chosen before; the banner names the one whose places move
      if (mode == Mode.SPLIT || mode == Mode.MERGE || mode == Mode.MOVE_PLACES) {
        if (mode != Mode.MOVE_PLACES) {
          resetMode();
        }
        refreshMap();
      }
    }
    showInspector();
    if (another) {
      inspectorScroll.getViewport().setViewPosition(new Point());
    }
  }

  /** Back to the list; the territory stays chosen on the map. */
  private void showList() {
    commit();
    card = LIST;
    list.choose(selected);
    showInspector();
  }

  // ---- modes

  private void startMode(final Mode started) {
    commit();
    resetMode();
    mode = started;
    applyMode();
  }

  /** Back to Select, as Done, Esc or Select do. */
  private void endMode() {
    resetMode();
    applyMode();
    showInspector();
  }

  private void resetMode() {
    mode = Mode.SELECT;
    brush = Optional.empty();
    copySource = Optional.empty();
    paintedEffect = Optional.empty();
    pendingCut = Optional.empty();
    map.endGoal();
  }

  /** The map as the mode needs it, only while this tool has the map. */
  private void refreshMap() {
    if (page.isShowing()) {
      applyMode();
    } else {
      showModeButtons();
    }
  }

  /** The mode on the map: what a click or drag does, and the banner that says so. */
  private void applyMode() {
    showModeButtons();
    final String territory = Objects.requireNonNullElse(selected, "");
    final Set<String> withEffect = paintedEffect.map(this::territoriesWith).orElse(Set.of());
    switch (mode) {
      case SELECT -> {
        map.stopEditingPlaces();
        // with the Places menu's Units shown, a click in the chosen territory adds a place
        map.addUnitPlacesByClick();
        map.hideBanner();
      }
      case PAINT -> {
        if (brush.equals(Optional.of(CAPITAL))) {
          // click only: no drag paints, so none shows its territories filled
          map.stopEditingPlaces();
        } else {
          map.paintStrokes(this::paintStroke);
        }
        map.showBanner(
            brush.orElseThrow().banner(),
            map.goalStep()
                .filter(Step.Id.LAND::equals)
                .map(step -> (JComponent) nationBox())
                .or(() -> brush.flatMap(Brush::texture).map(this::offsetBox))
                // by identity: a nation or effect may have its title
                .or(() -> brush.filter(b -> b == pusBrush).map(b -> pusBox())),
            "Done",
            this::endMode);
        paintedEffect.ifPresent(
            effect -> {
              map.showGoal(
                  "Terrain effect " + effect,
                  SearchList.count(
                      withEffect.size(), withEffect.size(), "territory", "territories"),
                  "Back to Terrain effects",
                  () -> openTool.accept("Terrain effects", effect));
            });
      }
      case DECORATE -> {
        if (decorationPicture.getSelectedItem() instanceof String picture) {
          map.editDecorations(picture);
        } else {
          map.stopEditingPlaces();
        }
        decorationPicture.setToolTipText(
            "The picture a click places: " + decorationPicture.getSelectedItem());
        map.showBanner(
            "Placing decorations: click the map to place the picture chosen here. Drag one to"
                + " move it; right-click or Delete removes it.",
            Optional.of(decorationPicture),
            "Done",
            this::endMode);
      }
      case MOVE_PLACES -> {
        map.editPlaces();
        map.showBanner(
            "Moving "
                + territory
                + "'s places: drag a place to move it, click inside "
                + territory
                + " to add a place for units, right-click or Delete removes one.",
            this::endMode);
      }
      case MERGE -> {
        map.stopEditingPlaces();
        map.showEditBanner(
            "Merging into "
                + territory
                + ": click each territory that belongs to it. Esc or Done ends.",
            this::endMode);
      }
      case DRAW -> {
        borderStyle()
            .ifPresentOrElse(
                style -> {
                  switch (drawBrush) {
                    case FREEHAND -> map.drawLine(this::borderDrawn);
                    case LINE -> map.drawLineByPoints(this::borderDrawn);
                    case CIRCLE -> map.drawCircle(this::borderDrawn);
                    case ERASER -> eraseOnMap(style);
                  }
                  // an edit mode: each line changes the map's picture, which the badge says, as
                  // the text is cut where the brushes and the line's tools need the room
                  map.showEditBanner(
                      "Edits the picture",
                      switch (drawBrush) {
                        case FREEHAND -> "Drag a border line.";
                        case LINE -> "Click points; double-click ends.";
                        case CIRCLE -> "Drag a circle, corner to corner.";
                        case ERASER -> ERASER_HOW_TO;
                      },
                      drawingTools(style),
                      this::endMode);
                },
                () -> {
                  // no click places or draws anything while it says why it cannot draw
                  map.stopEditingPlaces();
                  map.showBanner(noLineReason(), Optional.empty(), "Close", this::endMode);
                });
      }
      case SPLIT -> {
        map.drawLine(this::lineDrawn, line -> cut(line).isPresent());
        // after drawLine, which clears the cut
        pendingCut.ifPresent(cut -> map.showCut(cut.split()));
        map.showBanner(
            "Splitting "
                + territory
                + ": drag a line across it, from outside its border to outside again.",
            Optional.empty(),
            "Cancel",
            this::endMode);
      }
    }
    map.mark(mode == Mode.MERGE ? Set.of(territory) : withEffect);
    final Optional<String> brushEffect = brush.flatMap(Brush::effect);
    if (mode == Mode.PAINT
        && (brushEffect.isPresent() || brush.equals(Optional.of(NO_EFFECTS)))) {
      map.showEffects(brushEffect);
    } else {
      map.hideEffects();
    }
    if (mode == Mode.PAINT
        && (brush.flatMap(Brush::texture).isPresent() || brush.equals(Optional.of(NO_TEXTURE)))) {
      map.showTextures();
    } else {
      map.hideTextures();
    }
  }

  /** The texture's offset, X and Y, each change a step. */
  private JComponent offsetBox(final String texture) {
    showOffset(texture);
    final JPanel box = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
    box.setOpaque(false);
    box.add(new JLabel("Offset X"));
    box.add(textureX);
    box.add(new JLabel("Y"));
    box.add(textureY);
    return box;
  }

  private void showOffset(final String texture) {
    final Point offset = project.getFiles().textureOffset(texture);
    refilling = true;
    textureX.setValue(offset.x);
    textureY.setValue(offset.y);
    refilling = false;
  }

  /** The nation the owner brush paints, to change it without the Paint menu. */
  private JComboBox<String> nationBox() {
    final JComboBox<String> nations =
        new JComboBox<>(MapXml.nations(project.game()).toArray(String[]::new));
    nations.setSelectedItem(brush.map(Brush::title).orElse(null));
    nations.setToolTipText("The nation that gets the land");
    nations.addActionListener(
        e -> {
          if (nations.getSelectedItem() instanceof String nation
              && !brush.map(Brush::title).equals(Optional.of(nation))) {
            paintOwner(nation);
          }
        });
    return nations;
  }

  private void showModeButtons() {
    (switch (mode) {
          case PAINT -> paint;
          case DECORATE -> decorations;
          case DRAW -> drawBorders;
          default -> select;
        })
        .setSelected(true);
    movePlaces.setSelected(mode == Mode.MOVE_PLACES);
    places.setSelected(mode == Mode.DECORATE || !shownPlaces.equals(ShownPlaces.NONE));
  }

  private Set<String> territoriesWith(final String effect) {
    return Set.copyOf(
        TerritoryEffectsXml.territoriesWith(project.game()).getOrDefault(effect, List.of()));
  }

  /** What a paint click does to a territory, its title and the banner's text. */
  private record Brush(
      String title,
      String banner,
      Optional<Icon> icon,
      BiFunction<Game, String, Game> paint,
      Optional<String> effect,
      BiFunction<MapFiles, String, MapFiles> paintFiles,
      Optional<String> texture) {
    Brush(
        final String title,
        final String banner,
        final Optional<Icon> icon,
        final BiFunction<Game, String, Game> paint) {
      this(title, banner, icon, paint, Optional.empty(), (f, t) -> f, Optional.empty());
    }
  }

  /**
   * The Paint menu, built for each click: a menu each for owners, PUs, units, passable, terrain
   * effects and textures, so the whole stays short; Capital, one brush, at the top level.
   */
  JPopupMenu paintMenu() {
    final JPopupMenu menu = new JPopupMenu();
    final Game game = project.game();
    final JMenu owners = new JMenu("Owner");
    for (final String nation : MapXml.nations(game)) {
      owners.add(item(ownerBrush(nation)));
    }
    owners.addSeparator();
    owners.add(
        item(
            new Brush(
                NO_OWNER,
                "Painting no owner: click or drag across land to take its owner away.",
                Optional.empty(),
                (g, t) -> MapXml.isWater(g, t) ? g : MapXml.withOwner(g, t, null))));
    menu.add(owners);
    menu.add(item(CAPITAL));
    final JMenu values = new JMenu(PUS_BRUSH);
    for (final int value : VALUES) {
      values.add(String.valueOf(value)).addActionListener(e -> paintPus(value));
    }
    values.addSeparator();
    final JMenuItem other = values.add("Other…");
    other.setToolTipText("Any other value; the banner's field changes it while painting, too");
    other.addActionListener(e -> askPus());
    menu.add(values);
    menu.add(unitsMenu(game));
    final JMenu passable = new JMenu("Passable");
    passable.add(
        item(
            new Brush(
                "Impassable",
                "Painting impassable: click or drag across territories no unit may enter.",
                Optional.empty(),
                (g, t) -> TerritoryXml.withFlag(g, t, TerritoryXml.IMPASSABLE))));
    passable.add(
        item(
            new Brush(
                "Passable",
                "Painting passable: click or drag across territories units may enter again.",
                Optional.empty(),
                (g, t) -> TerritoryXml.withoutFlag(g, t, TerritoryXml.IMPASSABLE))));
    menu.add(passable);
    final JMenu effects = new JMenu("Terrain effects");
    TerritoryEffectsXml.effects(game).forEach(effect -> effects.add(item(effectBrush(effect))));
    if (effects.getItemCount() == 0) {
      effects.setEnabled(false);
      effects.setToolTipText("The game has none yet: the Terrain effects tool makes them");
    } else {
      effects.addSeparator();
      effects.add(item(NO_EFFECTS));
    }
    menu.add(effects);
    menu.add(texturesMenu(game));
    return menu;
  }

  private JMenu unitsMenu(final Game game) {
    final JMenu units = new JMenu("Units");
    final Optional<String> source =
        Optional.ofNullable(selected).filter(MapXml.territories(game)::contains);
    final JMenuItem copy =
        units.add(source.map(s -> "Copy units of " + s).orElse("Copy units of the chosen one"));
    copy.setEnabled(source.isPresent());
    if (source.isEmpty()) {
      copy.setToolTipText("Choose a territory first");
    }
    source.ifPresent(
        from ->
            copy.addActionListener(
                e -> {
                  paintWith(
                      new Brush(
                          "Copy units of " + from,
                          "Copying "
                              + from
                              + "'s units: click or drag across territories to give them"
                              + " the same starting units, turned to their owner.",
                          Optional.empty(),
                          (g, t) ->
                              MapXml.territories(g).contains(from)
                                  ? MapXml.withUnitsCopied(g, from, t)
                                  : g));
                  copySource = Optional.of(from);
                }));
    units.add(
        item(
            new Brush(
                "Clear units",
                "Clearing units: click or drag across territories to take their starting units"
                    + " away.",
                Optional.empty(),
                (g, t) -> MapXml.withTerritoryUnits(g, t, List.of()))));
    return units;
  }

  /**
   * The textures as tiles in a grid, each on the color it lies on; then clearing and adding. The
   * tiles are items of the menu itself, laid out in its grid, so the arrow keys reach them.
   */
  private JMenu texturesMenu(final Game game) {
    final JMenu textures = new JMenu("Textures");
    final MapFiles files = project.getFiles();
    final List<String> names = Textures.names(files);
    final Set<String> onWater =
        files.textures().entrySet().stream()
            .filter(e -> MapXml.isWater(game, e.getKey()))
            .map(Map.Entry::getValue)
            .collect(Collectors.toUnmodifiableSet());
    // many textures, eg found ones, in a wider grid, so the menu stays short
    final int columns = names.size() > 3 * TILE_COLUMNS ? 2 * TILE_COLUMNS : TILE_COLUMNS;
    final JPopupMenu popup = textures.getPopupMenu();
    popup.setLayout(new GridBagLayout());
    final GridBagConstraints cell = new GridBagConstraints();
    for (int i = 0; i < names.size(); i++) {
      final String texture = names.get(i);
      final boolean water = texture.endsWith(" sea") || onWater.contains(texture);
      final Optional<Icon> picture =
          Textures.icon(files, texture, water ? MapCanvas.SEA : MapCanvas.NEUTRAL)
              .map(ImageIcon::new);
      // a picture that does not read: its name instead
      final JMenuItem tile = new JMenuItem(picture.isPresent() ? "" : texture);
      picture.ifPresent(tile::setIcon);
      tile.setToolTipText(texture);
      tile.getAccessibleContext().setAccessibleName(texture);
      tile.addActionListener(e -> paintWith(textureBrush(texture)));
      cell.gridx = i % columns;
      cell.gridy = i / columns;
      popup.add(tile, cell);
    }
    // the rows below span the grid
    final GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.gridwidth = GridBagConstraints.REMAINDER;
    row.fill = GridBagConstraints.HORIZONTAL;
    row.weightx = 1;
    popup.add(new JPopupMenu.Separator(), row);
    popup.add(item(NO_TEXTURE), row);
    final Optional<Path> folder = files.folder();
    final JMenuItem own = new JMenuItem("Import texture…");
    popup.add(own, row);
    own.setEnabled(folder.isPresent());
    own.setToolTipText(
        folder.isPresent()
            ? "Copies a picture into the map's textures folder; it is repeated over the territory"
            : "Save the map first: the picture goes into its folder.");
    folder.ifPresent(f -> own.addActionListener(e -> importTexture(f)));
    final JMenuItem find = new JMenuItem("Find textures in the map…");
    popup.add(find, row);
    final boolean canFind = folder.isPresent() && files.hasPictureOrRelief();
    find.setEnabled(canFind);
    find.setToolTipText(
        canFind
            ? "Finds the textures painted into the map's picture or relief, as brushes"
            : folder.isEmpty()
                ? "Save the map first: the textures go into its folder."
                : "The map has no picture or relief to find textures in.");
    folder.ifPresent(f -> find.addActionListener(e -> findTextures(f)));
    return textures;
  }

  /** Paints the value; the banner's field changes it while painting. */
  void paintPus(final int value) {
    paintedPus.setValue(value);
    paintWith(pusBrush);
  }

  /** Asks for a value the menu does not list, eg 25, and paints it. */
  private void askPus() {
    Dialogs.askName(
            page,
            "Paint PUs per turn",
            PUS_BRUSH,
            String.valueOf(paintedPus.getValue()),
            "Paint",
            typed ->
                typed.matches("\\d{1,3}")
                    ? Optional.empty()
                    : Optional.of("A whole number from 0 to " + MAX_PUS + "."))
        .ifPresent(typed -> paintPus(Integer.parseInt(typed)));
  }

  /** The value a PU brush paints, to change it without the Paint menu. */
  private JComponent pusBox() {
    final JPanel box = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
    box.setOpaque(false);
    box.add(paintedPus);
    box.add(new JLabel("PUs"));
    return box;
  }

  /**
   * Finds the textures in the map in the background; if the map is still the same, asks which to
   * take: their pictures go into textures/ at once, the territories get them as one step.
   */
  private void findTextures(final Path folder) {
    final MapFiles files = project.getFiles();
    final Game game = project.game();
    final Set<String> water =
        files.polygons().keySet().stream()
            .filter(t -> MapXml.isWater(game, t))
            .collect(Collectors.toUnmodifiableSet());
    Dialogs.toast(page, "Looking for textures in the map…");
    new SwingWorker<List<TextureFinder.Found>, Void>() {
      @Override
      protected List<TextureFinder.Found> doInBackground() {
        return files
            .shownPicture()
            .map(picture -> TextureFinder.find(picture, files.polygons(), water))
            .orElse(List.of());
      }

      @Override
      protected void done() {
        final List<TextureFinder.Found> found;
        try {
          found = get();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (final ExecutionException e) {
          Dialogs.warn(page, "Find textures", String.valueOf(e.getCause().getMessage()));
          return;
        }
        if (project.getFiles() != files || project.game() != game) {
          Dialogs.toast(page, "! The map changed while textures were looked for. Find them again.");
          return;
        }
        FindTexturesDialog.ask(page, found, Textures.names(files))
            .filter(taken -> !taken.isEmpty())
            .ifPresent(
                taken -> {
                  try {
                    TextureFinder.write(folder, taken);
                  } catch (final IOException e) {
                    Dialogs.warn(
                        page, "Find textures", "Could not write them.\n" + e.getMessage());
                    return;
                  }
                  project.editFiles(f -> TextureFinder.withFound(f, taken));
                });
      }
    }.execute();
  }

  // the relief Map look makes has the textures; the game knows none
  private Brush textureBrush(final String texture) {
    return new Brush(
        texture,
        "Painting "
            + texture
            + ": click or drag across territories. Map look's Create relief bakes it in.",
        Optional.empty(),
        (g, t) -> g,
        Optional.empty(),
        (f, t) -> f.withTexture(t, Optional.of(texture)),
        Optional.of(texture));
  }

  /** Copies a picture into the map's textures folder at once, outside undo, and paints it. */
  private void importTexture(final Path folder) {
    final Optional<Path> chosen =
        MapMakerWindow.chooseFileToOpen(page, "Texture picture", folder, "png", "gif", "jpg");
    if (chosen.isEmpty()) {
      return;
    }
    final String file = chosen.get().getFileName().toString();
    final int dot = file.lastIndexOf('.');
    final String texture = (dot > 0 ? file.substring(0, dot) : file) + ".png";
    final Path target = folder.resolve(Textures.FOLDER).resolve(texture);
    if (Files.exists(target)
        && !Dialogs.confirm(
            page,
            "Import texture",
            "The map has a texture "
                + texture
                + " already. Replace it? Its first version stays as .bak.",
            "Replace")) {
      return;
    }
    try {
      MapFolder.importPicture(chosen.get(), target);
    } catch (final IOException e) {
      Dialogs.warn(page, "Import texture", "Could not take " + file + ".\n" + e.getMessage());
      return;
    }
    paintWith(textureBrush(texture));
  }

  // owners paint land only: a sweep across the map leaves the sea zones as they are
  private Brush ownerBrush(final String nation) {
    return new Brush(
        nation,
        "Painting owner "
            + nation
            + ": click or drag across land to give it to "
            + nation
            + ". Sea zones stay as they are.",
        Optional.of(new MapLegend.Swatch(project.nationColor(nation))),
        (g, t) -> MapXml.isWater(g, t) ? g : MapXml.withOwner(g, t, nation));
  }

  private JMenuItem item(final Brush chosen) {
    final JMenuItem item = new JMenuItem(chosen.title());
    chosen.icon().ifPresent(item::setIcon);
    item.addActionListener(e -> paintWith(chosen));
    return item;
  }

  private static Brush effectBrush(final String effect) {
    return new Brush(
        effect,
        "Painting " + effect + ": click or drag across territories to add the effect.",
        Optional.empty(),
        (g, t) -> TerritoryXml.withEffect(g, t, effect),
        Optional.of(effect),
        (f, t) -> f,
        Optional.empty());
  }

  private void paintWith(final Brush chosen) {
    paintWith(chosen, Optional.empty());
  }

  /** Paints the nation's land, keeping the goal shown, eg the step "Give every nation its land". */
  void paintOwner(final String nation) {
    paintKeepingGoal(ownerBrush(nation));
  }

  /** Paints capitals, keeping the goal shown, eg the step "Mark each nation's capital". */
  void paintCapitals() {
    paintKeepingGoal(CAPITAL);
  }

  /** Back to Select, eg for a step followed while the brush of another one was still chosen. */
  void select() {
    commit();
    endMode();
  }

  /**
   * Chooses a territory to place units in, so its panel shows "Add units" at once: the first land
   * without units, one with an owner before the others.
   */
  void chooseForUnits() {
    final Game game = project.game();
    MapXml.territories(game).stream()
        .filter(t -> !MapXml.isWater(game, t) && MapXml.units(game, t).isEmpty())
        .min(Comparator.comparing(t -> MapXml.owner(game, t).isEmpty()))
        .ifPresent(this::show);
  }

  private void paintKeepingGoal(final Brush chosen) {
    final Optional<Step.Id> step = map.goalStep();
    paintWith(chosen);
    step.ifPresent(
        shown -> {
          map.showStep(shown);
          // again: the banner's tools depend on the goal
          applyMode();
        });
  }

  /** Paints the effect, its territories marked on the map, eg from the Terrain effects tool. */
  void paintEffect(final String effect) {
    paintWith(effectBrush(effect), Optional.of(effect));
  }

  private void paintWith(final Brush chosen, final Optional<String> markedEffect) {
    commit();
    resetMode();
    mode = Mode.PAINT;
    brush = Optional.of(chosen);
    paintedEffect = markedEffect;
    applyMode();
  }

  /** The misc pictures in the decoration box, keeping the one chosen. */
  private void fillDecorationPictures() {
    // the pictures in the map's misc folder, placed anywhere on the map as the game draws them,
    // and those decorations.txt names but misc lacks, so their decorations can still be moved
    final Set<String> named = new TreeSet<>(project.getFiles().miscPictures());
    named.addAll(project.getFiles().places().decorations().keySet());
    final List<String> pictures = List.copyOf(named);
    final List<String> shown =
        IntStream.range(0, decorationPicture.getItemCount())
            .mapToObj(decorationPicture::getItemAt)
            .toList();
    if (pictures.equals(shown)) {
      return;
    }
    final Object chosen = decorationPicture.getSelectedItem();
    refilling = true;
    decorationPicture.removeAllItems();
    pictures.forEach(decorationPicture::addItem);
    if (chosen != null) {
      decorationPicture.setSelectedItem(chosen);
    }
    refilling = false;
    if (mode == Mode.DECORATE) {
      if (pictures.isEmpty()) {
        resetMode();
      }
      // the chosen picture may be gone from misc: the map places the one chosen now
      refreshMap();
    }
  }

  // ---- draw borders

  /**
   * A line released on the map, drawn into the picture as chosen, its ends brought to the border
   * lines near them so the areas close; none for width 0, nor one ending far from any border.
   */
  void borderDrawn(final List<Point> line) {
    final Optional<BufferedImage> picture = project.getFiles().picture();
    final Optional<MapPicture.LineStyle> chosen =
        borderStyle().filter(style -> style.width() > 0);
    if (picture.isEmpty() || chosen.isEmpty()) {
      return;
    }
    // about as near as a hand misses a line, on the screen at any zoom
    final int reach = (int) Math.ceil(SNAP_PIXELS / map.zoom());
    MapPicture.snapToBorders(picture.get(), line, reach)
        .ifPresentOrElse(
            snapped ->
                project.drawBorder(
                    new MapProject.BorderLine(
                        snapped, chosen.get().width(), chosen.get().color())),
            () ->
                Dialogs.toast(
                    page,
                    "! Not drawn: start and end a border line near another border,"
                        + " the map's edge, or back at its start."));
  }

  /**
   * A band released on the map: the border lines under it go from the picture, each side's color
   * growing over them, as one step.
   */
  void bordersErased(final List<Point> stroke) {
    if (!project.eraseBorders(stroke, eraserWidth())) {
      Dialogs.toast(page, "! Nothing erased: no border line under the eraser.");
    }
  }

  /** The eraser on the map: a drag erases under its band, a click the whole border it is on. */
  private void eraseOnMap(final MapPicture.LineStyle style) {
    map.eraseLine(this::bordersErased, eraserWidth(), style.width(), this::borderErased);
  }

  /**
   * A click of the eraser on a border: all of the line between the two territories goes from the
   * picture, as one step, so that they are one area for Make territories.
   */
  void borderErased(final MapFiles.SharedBorder border) {
    if (project.eraseBorder(border)) {
      Dialogs.toast(
          page,
          "✓ Erased the border between " + border.first() + " and " + border.second(),
          "Undo",
          project::undo);
    } else {
      Dialogs.toast(page, "! Nothing erased: the picture has no line between them.");
    }
  }

  /** The eraser's width in the picture's pixels: as chosen, else three times its lines' width. */
  private int eraserWidth() {
    return eraserWidth.orElseGet(
        () -> 3 * foundStyle.map(MapPicture.LineStyle::width).orElse(2));
  }

  /** Ends drawing and finds the territories in the picture with the lines drawn into it. */
  void findDrawnTerritories() {
    endMode();
    project.getFiles().picture().ifPresent(findTerritories);
  }

  /** The brush, the line's color and width, and Make territories, for the banner. */
  private JPanel drawingTools(final MapPicture.LineStyle style) {
    final JButton make = new JButton("Make territories");
    Theme.setPrimaryButtonStyle(make);
    make.setToolTipText("The areas between the lines become territories, as Redraw borders does");
    make.addActionListener(e -> findDrawnTerritories());
    final JPanel tools = drawBrush == DrawBrush.ERASER ? eraserFields() : lineFields(style);
    tools.add(brushButton(Glyph.FREEHAND, "Freehand: drag to draw", DrawBrush.FREEHAND), 0);
    final String lineTip =
        "Line: click points, double-click or Enter ends it; Backspace takes back a point,"
            + " Esc the line";
    tools.add(brushButton(Glyph.POLYLINE, lineTip, DrawBrush.LINE), 1);
    final JToggleButton circle =
        brushButton(
            Glyph.CIRCLE,
            "Circle: drag from corner to corner; Shift for a round circle",
            DrawBrush.CIRCLE);
    circle.getAccessibleContext().setAccessibleName("Circle");
    tools.add(circle, 2);
    final JToggleButton eraser =
        brushButton(
            Glyph.ERASER,
            "Eraser: drag over border lines to take them out of the picture; Make territories"
                + " then joins the areas they parted",
            DrawBrush.ERASER);
    eraser.getAccessibleContext().setAccessibleName("Eraser");
    tools.add(eraser, 3);
    tools.add(Box.createHorizontalStrut(Theme.GAP_M), 4);
    tools.add(Box.createHorizontalStrut(Theme.GAP_M));
    tools.add(make);
    return tools;
  }

  /** One part of the brush toggle, for the brush it chooses. */
  private JToggleButton brushButton(final Glyph glyph, final String tip, final DrawBrush chosen) {
    final JToggleButton button = new JToggleButton(MakerIcon.small(glyph), drawBrush == chosen);
    button.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    button.setToolTipText(tip);
    button.getAccessibleContext().setAccessibleName(tip);
    button.addActionListener(
        e -> {
          // the chosen brush again would drop a line half set
          button.setSelected(true);
          if (drawBrush != chosen) {
            drawBrush = chosen;
            applyMode();
          }
        });
    return button;
  }

  // ---- split

  /** A line released on the map: a cut shows hatched with the split card, else the banner says why. */
  void lineDrawn(final List<Point> line) {
    if (mode != Mode.SPLIT || selected == null) {
      return;
    }
    final String territory = selected;
    final Optional<OutlineCut.Cut> cut = cut(line);
    if (cut.isEmpty()) {
      pendingCut = Optional.empty();
      map.clearCut();
      showInspector();
      map.showBanner(
          "That line doesn't cut " + territory + " in two. Drag again, from outside to outside.",
          Optional.empty(),
          "Cancel",
          this::endMode);
      return;
    }
    final boolean first = pendingCut.isEmpty();
    pendingCut = cut;
    pendingLine = List.copyOf(line);
    applyMode();
    if (first) {
      // a line drawn again keeps the name typed
      splitName = Optional.empty();
      fillSplitCard(territory);
    }
    showInspector();
  }

  private Optional<OutlineCut.Cut> cut(final List<Point> line) {
    return Optional.ofNullable(selected)
        .map(t -> project.getFiles().polygons().getOrDefault(t, List.of()))
        .filter(outlines -> !outlines.isEmpty())
        .flatMap(outlines -> OutlineCut.cut(outlines, line));
  }

  private void fillSplitCard(final String territory) {
    // filled again once the picture is ready: the name typed stays
    final JTextField name =
        new JTextField(
            splitName
                .map(JTextField::getText)
                .orElseGet(() -> project.freeName(territory, Set.of())));
    splitName = Optional.of(name);
    splitForm.clear();
    splitForm.title("Split " + territory);
    splitForm.note(
        "The hatched part becomes a new territory. "
            + SearchList.escape(territory)
            + " keeps the rest, its owner and its units.");
    splitForm.field("Name of the new territory", name);
    final FieldError error = splitForm.error(name);
    borderStyle()
        .ifPresentOrElse(
            style -> splitForm.field("Border line in the picture", lineFields(style)),
            () -> splitForm.note(noLineReason()));
    final JButton cancel = new JButton("Cancel");
    Theme.setSecondaryButtonStyle(cancel);
    cancel.addActionListener(e -> endMode());
    final JButton split = new JButton("Split");
    Theme.setPrimaryButtonStyle(split);
    final Runnable check =
        () -> {
          final Optional<String> problem = project.nameProblem(name.getText());
          error.setProblem(problem);
          // while the picture is got ready the border line would be missed
          split.setEnabled(
              problem.isEmpty() && !name.getText().isBlank() && !preparingPicture);
        };
    OnTyping.attach(name, check);
    check.run();
    split.addActionListener(
        e ->
            split(
                territory,
                name.getText(),
                borderStyle()
                    .filter(style -> style.width() > 0)
                    .map(
                        style ->
                            new MapProject.BorderLine(
                                pendingLine, style.width(), style.color()))));
    name.addActionListener(
        e -> {
          if (split.isEnabled()) {
            split.doClick();
          }
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
    buttons.add(cancel);
    buttons.add(split);
    splitForm.row(buttons).end();
  }

  /**
   * The line drawn into the picture as chosen this session: at first as wide as most of the
   * picture's lines and in their color; empty for a map without a picture to draw into.
   */
  private Optional<MapPicture.LineStyle> borderStyle() {
    final Optional<BufferedImage> picture = project.getFiles().picture();
    if (picture.isEmpty()) {
      preparePicture();
      return Optional.empty();
    }
    // while drawing each line makes a new picture: its lines must not change the style found
    if (styleOf.isEmpty() || (picture.get() != styleOf.get() && mode != Mode.DRAW)) {
      foundStyle = picture.map(MapPicture::lineStyle);
      styleOf = picture;
    }
    return foundStyle.map(
        style ->
            new MapPicture.LineStyle(
                chosenWidth.orElse(style.width()), chosenColor.orElse(style.color())));
  }

  /** Puts the picture together from its tiles in the background, once, to draw into. */
  private void preparePicture() {
    if (preparingPicture
        || pictureTooBig
        || pictureUnread
        || project.getFiles().tile(0, 0).isEmpty()) {
      return;
    }
    preparingPicture = true;
    final MapFiles files = project.getFiles();
    new SwingWorker<Optional<BufferedImage>, Void>() {
      @Override
      protected Optional<BufferedImage> doInBackground() {
        return files.tilesPicture();
      }

      @Override
      protected void done() {
        preparingPicture = false;
        try {
          get().ifPresentOrElse(project::holdPicture, () -> pictureTooBig = true);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          pictureUnread = true;
        } catch (final ExecutionException e) {
          pictureUnread = true;
        }
        // the banner and the split card get the line's fields now
        if (mode == Mode.DRAW && page.isShowing()) {
          applyMode();
        }
        if (pendingCut.isPresent() && selected != null) {
          fillSplitCard(selected);
        }
      }
    }.execute();
  }

  /** Why there are no line fields: the picture is being got ready, too big, or there is none. */
  private String noLineReason() {
    return preparingPicture
        ? "Getting the map's picture ready to draw into…"
        : pictureTooBig
            ? "The map's picture is too big to draw into here, or one of its tiles does not read."
            : pictureUnread
                ? "The map's picture tiles could not be read."
                : "The map has no picture to draw into: choose one in the picture menu at the right.";
  }

  /** The eraser's width, kept for the session as changed. */
  private JPanel eraserFields() {
    final JSpinner width = new JSpinner(new SpinnerNumberModel(eraserWidth(), 1, 99, 1));
    takeAsTyped(width);
    width.setToolTipText(
        "Pixels wide; at first three times as wide as most of the picture's lines");
    width.getAccessibleContext().setAccessibleName("Eraser width in pixels");
    width.addChangeListener(
        e -> {
          eraserWidth = Optional.of((Integer) width.getValue());
          // the band dragged next is as wide; the banner stays, with the field being typed in
          borderStyle().ifPresent(this::eraseOnMap);
        });
    final JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    fields.setOpaque(false);
    fields.add(width);
    fields.add(Box.createHorizontalStrut(Theme.GAP_XS));
    fields.add(new JLabel("px"));
    return fields;
  }

  /** The color and width of the line, kept for the session as changed. */
  private JPanel lineFields(final MapPicture.LineStyle style) {
    final JComboBox<String> color = new JComboBox<>(LINE_COLOR_NAMES.toArray(String[]::new));
    color.setSelectedIndex(chosenColor.map(LINE_COLORS::lastIndexOf).filter(i -> i > 0).orElse(0));
    color.setToolTipText("At first in the color of most of the picture's lines");
    color.getAccessibleContext().setAccessibleName("Line color");
    color.addActionListener(
        e ->
            chosenColor =
                color.getSelectedIndex() == 0
                    ? Optional.empty()
                    : Optional.of(LINE_COLORS.get(color.getSelectedIndex())));
    final JSpinner width = new JSpinner(new SpinnerNumberModel(style.width(), 0, 20, 1));
    takeAsTyped(width);
    width.setToolTipText(
        "Pixels wide; at first as wide as most of the picture's lines. 0 draws nothing: a"
            + " split's border then shows on the map only while chosen.");
    width.getAccessibleContext().setAccessibleName("Line width in pixels");
    width.addChangeListener(e -> chosenWidth = Optional.of((Integer) width.getValue()));
    final JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    fields.setOpaque(false);
    fields.add(color);
    fields.add(Box.createHorizontalStrut(Theme.GAP_S));
    fields.add(width);
    fields.add(Box.createHorizontalStrut(Theme.GAP_XS));
    fields.add(new JLabel("px"));
    return fields;
  }

  /** The split as one step, its border drawn into the picture if given; the new territory chosen. */
  private void split(
      final String territory, final String newName, final Optional<MapProject.BorderLine> border) {
    final Optional<OutlineCut.Cut> cut = pendingCut;
    endMode();
    if (cut.isPresent() && project.splitTerritory(territory, cut.get(), newName, border)) {
      show(newName);
    }
  }

  // ---- inspector

  /** The card of the moment: the split, the chosen territory or the list. */
  private void showInspector() {
    final boolean known =
        selected != null && MapXml.territories(project.game()).contains(selected);
    if (known) {
      fillInspector(selected);
    }
    cards.show(page, pendingCut.isPresent() && known ? SPLIT : known ? card : LIST);
  }

  private void fillInspector(final String territory) {
    inspector.clear();
    final Game game = project.game();
    inspector.title(territory).subtitle(detail(territory));
    addName(territory);

    final JRadioButton land = new JRadioButton("Land", !MapXml.isWater(game, territory));
    final JRadioButton sea = new JRadioButton("Sea zone", MapXml.isWater(game, territory));
    final ButtonGroup kind = new ButtonGroup();
    kind.add(land);
    kind.add(sea);
    land.addActionListener(e -> project.edit(g -> MapXml.withLand(g, territory)));
    sea.addActionListener(e -> project.edit(g -> MapXml.withWater(g, territory)));
    final JPanel kinds = leftAligned();
    kinds.add(land);
    kinds.add(sea);
    inspector.field("Kind", kinds);

    final Optional<String> owner = MapXml.owner(game, territory);
    final JComboBox<String> owners = newOwnerBox(owner.orElse(null));
    owners.addActionListener(
        e -> project.edit(g -> MapXml.withOwner(g, territory, chosenOwner(owners))));
    inspector.field("Owner", owners);
    final JSpinner value =
        Form.newNumber(
            MapXml.production(game, territory),
            999,
            v -> edit(g -> MapXml.withProduction(g, territory, v)));
    value.setToolTipText("What the territory gives its owner each turn to buy units with");
    inspector.number("PUs per turn", value);
    final JCheckBox capital =
        new JCheckBox(
            owner.map(o -> "Capital of " + o).orElse("Capital of its owner"),
            MapXml.isCapital(game, territory));
    capital.setEnabled(owner.isPresent() || capital.isSelected());
    capital.addActionListener(
        e ->
            project.edit(
                g ->
                    MapXml.withCapital(
                        g, territory, capital.isSelected() ? owner.orElse(null) : null)));
    inspector.row(
        Help.with(
            capital,
            "Where the nation collects its income; losing it costs the nation its PUs. A nation"
                + " normally has one: the Capital brush moves it. Tick this on another territory"
                + " for a second capital, which TripleA allows."));
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

    final List<UnitPlacement> units = MapXml.units(game, territory);
    inspector.section("Units at the start (" + units.size() + ")");
    units.forEach(u -> inspector.row(unitRow(territory, u)));
    final JButton addUnits =
        Theme.newAddButton(
            "Add units", "Add starting units", () -> addUnits(territory, owner.orElse(null)));
    addUnits.setEnabled(!MapXml.unitTypes(game).isEmpty());
    final JPanel addRow = leftAligned();
    addRow.add(addUnits);
    if (!units.isEmpty()) {
      final JButton removeAll = new JButton("Remove all");
      Theme.setTertiaryButtonStyle(removeAll);
      removeAll.setToolTipText("Take all units away from " + territory + "; Undo brings them back");
      removeAll.addActionListener(e -> removeUnits(territory));
      addRow.add(Box.createHorizontalStrut(Theme.GAP_S));
      addRow.add(removeAll);
    }
    inspector.row(addRow);

    final boolean water = MapXml.isWater(game, territory);
    inspector.fold("territory.rules", "Rules", 0, f -> fillRules(f, game, territory, water));
    final List<String> effects = TerritoryEffectsXml.effects(game);
    if (!effects.isEmpty()) {
      final List<String> has = MapXml.territoryEffects(game, territory);
      inspector.more(
          "territory.effects",
          Theme.foldLabel("Terrain effects", effects.size(), has.size()),
          f -> f.row(effectBoxes(territory, effects, has)));
    }
    inspector.fold(
        "territory.map", "On the map: name, places, outline", 0, f -> fillOnTheMap(f, territory));
    inspector.more("territory.more", 5, f -> fillMore(f, game, territory));

    final Set<String> neighbours = MapXml.neighbors(game, territory);
    inspector.section("Neighbours (" + neighbours.size() + ")");
    final JPanel links = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
    for (final String neighbour : neighbours) {
      final JButton link = new JButton(neighbour);
      Theme.setTertiaryButtonStyle(link);
      link.addActionListener(e -> show(neighbour));
      links.add(link);
    }
    final JButton connections = new JButton("→");
    Theme.setTertiaryButtonStyle(connections);
    connections.setToolTipText("Edit in Connections");
    connections.getAccessibleContext().setAccessibleName("Edit in Connections");
    connections.addActionListener(e -> openTool.accept("Connections", territory));
    links.add(connections);
    inspector.row(links);
    inspector.remove("Remove territory", () -> removeTerritory(territory));
    inspector.end();
    // the neighbours wrap by their row's width, which the first layout only gives them
    SwingUtilities.invokeLater(inspector::revalidate);
  }

  /**
   * The name, renamed on Enter, focus loss or commit(); a refused one stays typed with the reason
   * below it, the territory keeping its name.
   */
  private void addName(final String territory) {
    final JTextField name = new JTextField(territory);
    inspector.field("Name", name);
    final FieldError error = inspector.error(name);
    final String refused = NAME_REFUSED + " Kept \"" + territory + "\".";
    refusedName.ifPresent(
        typed -> {
          name.setText(typed);
          error.setProblem(Optional.of(refused));
        });
    final Runnable rename =
        () -> {
          typing = Optional.empty();
          final String typed = name.getText().strip();
          // after a rename the old field may still lose the focus: its territory is gone then
          if (typed.equals(territory) || !MapXml.territories(project.game()).contains(territory)) {
            return;
          }
          refusedName = Optional.empty();
          // chosen before the change, so that the inspector shown after it is the renamed one's
          selected = typed;
          if (project.rename(territory, typed)) {
            list.choose(typed);
            map.select(typed);
          } else {
            selected = territory;
            refusedName = Optional.of(name.getText());
            error.setProblem(Optional.of(refused));
          }
        };
    whenTyped(name, rename);
  }

  private void fillRules(
      final Form rules, final Game game, final String territory, final boolean water) {
    rules.row(flagBox(game, territory, TerritoryXml.IMPASSABLE, "Impassable: no unit may enter"));
    if (water) {
      rules.row(flagBox(game, territory, "blockadeZone", "Blockade zone: ships here cut enemy PUs"));
      rules.row(
          flagBox(game, territory, "kamikazeZone", "Kamikaze zone: kamikaze attacks strike here"));
      rules.row(flagBox(game, territory, "convoyRoute", "Convoy route: enemy ships here cut PUs"));
      return;
    }
    final JSpinner production =
        Form.newNumber(
            TerritoryXml.unitProduction(game, territory),
            999,
            value -> edit(g -> TerritoryXml.withUnitProduction(g, territory, value)));
    production.setToolTipText("As many as its PUs per turn, unless set otherwise here");
    rules.field("Units its factories place per turn", production);
    final JComboBox<String> original = new JComboBox<>();
    original.addItem(OWNER_AT_START);
    MapXml.nations(game).forEach(original::addItem);
    TerritoryXml.originalOwner(game, territory)
        .ifPresent(
            nation -> {
              if (!MapXml.nations(game).contains(nation)) {
                original.addItem(nation);
              }
              original.setSelectedItem(nation);
            });
    original.setToolTipText("Who gets it back when an ally frees it, if not its owner at the start");
    original.addActionListener(
        e ->
            project.edit(
                g ->
                    TerritoryXml.withOriginalOwner(
                        g,
                        territory,
                        original.getSelectedIndex() == 0
                            ? null
                            : (String) original.getSelectedItem())));
    rules.field("Original owner", original);
  }

  private JPanel effectBoxes(
      final String territory, final List<String> effects, final List<String> has) {
    final JPanel boxes = new JPanel(new GridLayout(0, 2));
    boxes.setOpaque(false);
    for (final String effect : effects) {
      final JCheckBox box = new JCheckBox(effect, has.contains(effect));
      box.addActionListener(
          e ->
              project.edit(
                  g ->
                      box.isSelected()
                          ? TerritoryXml.withEffect(g, territory, effect)
                          : TerritoryXml.withEffects(
                              g,
                              territory,
                              MapXml.territoryEffects(g, territory).stream()
                                  .filter(other -> !other.equals(effect))
                                  .toList())));
      boxes.add(box);
    }
    return boxes;
  }

  private void fillOnTheMap(final Form onTheMap, final String territory) {
    Theme.setSecondaryButtonStyle(movePlaces);
    movePlaces.setSelected(mode == Mode.MOVE_PLACES);
    final JButton findPlaces = new JButton("Find places again");
    Theme.setTertiaryButtonStyle(findPlaces);
    findPlaces.setToolTipText("Finds the places inside " + territory + "'s border again");
    findPlaces.addActionListener(e -> project.findPlacesAgain(territory));
    final JButton split = new JButton("Split with a line…");
    Theme.setSecondaryButtonStyle(split);
    final List<Polygon> outlines =
        project.getFiles().polygons().getOrDefault(territory, List.of());
    split.setEnabled(!outlines.isEmpty());
    split.setToolTipText(
        outlines.isEmpty() ? territory + " has no outline on the map to cut." : null);
    split.addActionListener(e -> startMode(Mode.SPLIT));
    onTheMap.row(nameCheck(territory));
    final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    actions.setOpaque(false);
    actions.add(movePlaces);
    actions.add(split);
    onTheMap.row(actions);
    final JButton mergeHere = new JButton("Merge into this…");
    Theme.setSecondaryButtonStyle(mergeHere);
    mergeHere.setToolTipText(
        "Then click each territory that belongs to "
            + territory
            + ", eg the islands of a group: it becomes part of it");
    mergeHere.addActionListener(e -> startMode(Mode.MERGE));
    final JButton mergeInto = new JButton("Merge into…");
    Theme.setSecondaryButtonStyle(mergeInto);
    mergeInto.setToolTipText(territory + " becomes part of another territory you choose");
    mergeInto.addActionListener(e -> mergeInto(territory));
    final JPanel merging = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    merging.setOpaque(false);
    merging.add(mergeHere);
    merging.add(mergeInto);
    onTheMap.row(merging);
    final JPanel again = leftAligned();
    again.add(findPlaces);
    onTheMap.row(again);
    onTheMap.note(
        "Where the game draws " + SearchList.escape(territory) + "'s units, name and markers.");
  }

  /** Whether the game draws the territory's name, map.properties dont_draw_territory_names. */
  private JCheckBox nameCheck(final String territory) {
    final JCheckBox named =
        new JCheckBox(
            "Show its name on the map",
            !project.getFiles().listed(MapFiles.HIDDEN_NAMES).contains(territory));
    named.setOpaque(false);
    named.setToolTipText(
        "Off: the game draws no name here, eg where the map's picture has it already.");
    named.addActionListener(
        e ->
            project.editFiles(
                f ->
                    named.isSelected()
                        ? f.withoutListed(MapFiles.HIDDEN_NAMES, territory)
                        : f.withListed(MapFiles.HIDDEN_NAMES, territory)));
    return named;
  }

  private void fillMore(final Form more, final Game game, final String territory) {
    more.row(flagBox(game, territory, "originalFactory", "Original factory: its own, for repairs"));
    more.field(
        "Resources per turn",
        withTip(
            newText(territory, "resources"),
            "Resources besides PUs it gives its owner each turn, eg 2:Ore; several with ';'"));
    more.field(
        "Taken by, goes to",
        withTip(
            newText(territory, "whenCapturedByGoesTo"),
            "Pairs captor:receiver, eg Germans:Italians: taken by the first, it goes to the"
                + " second; several with ';' (whenCapturedByGoesTo)"));
    more.field(
        "Takes over its units when capturing",
        withTip(
            newText(territory, "changeUnitOwners"),
            "Nations, eg Germans:Italians, that take over the units in it when they capture it"
                + " (changeUnitOwners)"));
    more.field(
        "Takes its units by entering",
        withTip(
            newText(territory, "captureUnitOnEnteringBy"),
            "Nations, eg Germans:Italians, that capture the units in it by moving in"
                + " (captureUnitOnEnteringBy)"));
  }

  private static JTextField withTip(final JTextField field, final String tip) {
    field.setToolTipText(tip);
    return field;
  }

  private static JPanel leftAligned() {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setOpaque(false);
    return row;
  }

  /** A check box for an option that is true or not there, eg blockadeZone. */
  private JCheckBox flagBox(
      final Game game, final String territory, final String option, final String label) {
    final JCheckBox box = new JCheckBox(label, MapXml.hasTerritoryFlag(game, territory, option));
    box.addActionListener(
        e ->
            project.edit(
                g ->
                    box.isSelected()
                        ? TerritoryXml.withFlag(g, territory, option)
                        : TerritoryXml.withoutFlag(g, territory, option)));
    return box;
  }

  /**
   * A text of the option's values separated by ';' that becomes a step on Enter, when it loses the
   * focus, or before a save or another territory is shown.
   */
  private JTextField newText(final String territory, final String option) {
    final JTextField field =
        new JTextField(String.join("; ", TerritoryXml.values(project.game(), territory, option)));
    whenTyped(
        field,
        () -> {
          typing = Optional.empty();
          final List<String> values =
              Arrays.stream(field.getText().split(";"))
                  .map(String::strip)
                  .filter(v -> !v.isEmpty())
                  .toList();
          // compared with the game as it is now, which withValues does: a text committed once
          // and then cleared must clear the option too; the territory may be gone meanwhile, eg
          // renamed, and its option must not come back
          edit(
              g ->
                  MapXml.territories(g).contains(territory)
                      ? TerritoryXml.withValues(g, territory, option, values)
                      : g);
        });
    return field;
  }

  /** The commit runs on Enter, on focus loss, and by commit() once something is typed. */
  private void whenTyped(final JTextField field, final Runnable commit) {
    OnTyping.attach(field, () -> typing = Optional.of(commit));
    field.addActionListener(e -> commit.run());
    field.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            commit.run();
          }
        });
  }

  // ---- remove

  private void removeTerritory(final String territory) {
    commit();
    final Game game = project.game();
    // eg a playerAttachment's capital: no point asking to remove what stays anyway
    final List<String> stillNamed =
        RulesXml.whereNamed(MapXml.withoutTerritory(game, territory), territory);
    if (!stillNamed.isEmpty()) {
      tellStillNamed(territory, stillNamed);
      return;
    }
    if (!Dialogs.confirmRemove(
        page,
        "Remove " + territory + "?",
        territory
            + " goes with its owner, starting units, connections, settings, outline and places.\n"
            + "Undo takes it back.",
        removeDetails(game, territory),
        "Remove territory")) {
      return;
    }
    // chosen before the change, so that the list shows after it
    selected = null;
    final List<String> named = project.removeTerritory(territory);
    if (named.isEmpty()) {
      map.select(null);
      card = LIST;
      showInspector();
    } else {
      selected = territory;
      tellStillNamed(territory, named);
    }
  }

  /**
   * Removes the territories ticked in a list, the smallest first, as one step, and tells which
   * stay because attachments still name them.
   */
  private void removeTerritories() {
    commit();
    final Game game = project.game();
    final MapFiles files = project.getFiles();
    final Map<String, String> byLine = new LinkedHashMap<>();
    smallestFirst(MapXml.territories(game), files)
        .forEach(t -> byLine.put(areaLine(t, files), t));
    final List<String> chosen =
        Dialogs.pickMany(
                page, "Remove territories", List.copyOf(byLine.keySet()), List.of(), "Next")
            .orElse(List.of())
            .stream()
            .map(byLine::get)
            .toList();
    if (chosen.isEmpty()) {
      return;
    }
    final String many = SearchList.count(chosen.size(), chosen.size(), "territory", "territories");
    if (!Dialogs.confirmRemove(
        page,
        "Remove " + many + "?",
        "They go with their owners, starting units, connections, settings, outlines and places."
            + " One that attachments still name stays.\nUndo takes them back.",
        removeDetails(game, chosen),
        "Remove " + many)) {
      return;
    }
    final Map<String, List<String>> named = project.removeTerritories(chosen);
    if (selected != null && !MapXml.territories(project.game()).contains(selected)) {
      selected = null;
      map.select(null);
      card = LIST;
      showInspector();
    }
    if (named.isEmpty()) {
      Dialogs.toast(page, "✓ Removed " + many + ".");
    } else {
      final List<String> where = new ArrayList<>();
      named.forEach((territory, places) -> places.forEach(p -> where.add(territory + ": " + p)));
      Dialogs.refuseNamed(
          page,
          (chosen.size() - named.size()) + " removed, " + named.size() + " can't be removed yet",
          stillNamedText("them", where),
          named.values().stream().flatMap(List::stream).toList());
    }
  }

  /** The territories by the area inside their outlines, the smallest first, then by name. */
  static List<String> smallestFirst(final Collection<String> territories, final MapFiles files) {
    return territories.stream()
        .sorted(Comparator.<String>comparingLong(files::area).thenComparing(t -> t))
        .toList();
  }

  /** "Tiny Island · 1,240 px²"; one without an outline says so. */
  static String areaLine(final String territory, final MapFiles files) {
    final long area = files.area(territory);
    return territory
        + " · "
        + (area == 0 ? "not on the map" : String.format(Locale.ENGLISH, "%,d px²", area));
  }

  /** What goes with many territories: how many have an owner or units, then each one's own. */
  static List<String> removeDetails(final Game game, final List<String> territories) {
    final List<String> details = new ArrayList<>();
    final long owned = territories.stream().filter(t -> MapXml.owner(game, t).isPresent()).count();
    if (owned > 0) {
      details.add(owned + " of them " + (owned == 1 ? "has" : "have") + " an owner.");
    }
    final long withUnits =
        territories.stream().filter(t -> !MapXml.units(game, t).isEmpty()).count();
    if (withUnits > 0) {
      details.add(
          withUnits + " of them " + (withUnits == 1 ? "has" : "have") + " starting units.");
    }
    territories.forEach(
        t -> removeDetails(game, t).forEach(d -> details.add(t + ": " + d)));
    return List.copyOf(details);
  }

  // ---- merge

  /** Asks which territory this one becomes part of, among those of its kind, and merges it. */
  private void mergeInto(final String territory) {
    commit();
    final Game game = project.game();
    final boolean water = MapXml.isWater(game, territory);
    final List<String> keepers =
        MapXml.territories(game).stream()
            .filter(t -> !t.equals(territory) && MapXml.isWater(game, t) == water)
            .sorted()
            .toList();
    Dialogs.pick(
            page,
            "Merge " + territory,
            territory
                + " becomes part of the territory chosen, which keeps its own owner, units and"
                + " settings.",
            keepers,
            t -> t,
            "Merge")
        .filter(keeper -> merge(keeper, territory))
        .ifPresent(this::show);
  }

  /**
   * Makes the other part of the keeper, as one step, asking first when the other has something
   * that goes with it; says why not for land with sea or while attachments still name the other.
   */
  private boolean merge(final String keeper, final String other) {
    final Game game = project.game();
    final Optional<String> refusal = project.mergeRefusal(keeper, other);
    if (refusal.isPresent()) {
      Dialogs.toast(page, "! Not merged: " + refusal.get());
      return false;
    }
    // no point asking to merge what stays anyway
    final List<String> stillNamed =
        RulesXml.whereNamed(MapXml.withoutTerritory(game, other), other);
    final List<String> lost = stillNamed.isEmpty() ? mergeLosses(game, keeper, other) : List.of();
    if (!lost.isEmpty()
        && !Dialogs.confirmRemove(
            page,
            "Merge " + other + " into " + keeper + "?",
            other
                + " becomes part of "
                + keeper
                + ", which keeps its own owner, units and settings. What "
                + other
                + " has goes.\nUndo takes it back.",
            lost,
            "Merge")) {
      return false;
    }
    final List<String> named =
        stillNamed.isEmpty() ? project.mergeTerritories(keeper, other) : stillNamed;
    if (!named.isEmpty()) {
      Dialogs.refuseNamed(page, other + " can't be merged yet", stillNamedText(named), named);
      return false;
    }
    Dialogs.toast(page, "✓ " + other + " is now part of " + keeper + ".");
    return true;
  }

  /** What the other territory has that a merge drops, as the keeper keeps its own. */
  static List<String> mergeLosses(final Game game, final String keeper, final String other) {
    final List<String> lost = new ArrayList<>();
    MapXml.owner(game, other)
        .filter(owner -> !MapXml.owner(game, keeper).equals(Optional.of(owner)))
        .ifPresent(owner -> lost.add("Its owner, " + owner + "."));
    if (!MapXml.units(game, other).isEmpty()) {
      lost.add("Its starting units.");
    }
    final int production = MapXml.production(game, other);
    if (production > 0) {
      lost.add("Its " + pus(production) + " per turn.");
    }
    final long settings =
        AttachmentsXml.attachments(game).stream()
            // not its canals, told with the canal
            .filter(
                a ->
                    AttachmentsXml.isNamed(a, Constants.TERRITORY_ATTACHMENT_NAME)
                        && other.equals(a.getAttachTo()))
            .flatMap(a -> AttachmentsXml.options(a).stream())
            .map(option -> String.valueOf(option.getName()))
            .filter(name -> !Set.of("production", "capital", "victoryCity").contains(name))
            .count();
    if (settings > 0) {
      lost.add(settings == 1 ? "Another setting of its own." : settings + " other settings.");
    }
    lost.addAll(removeDetails(game, other));
    return List.copyOf(lost);
  }

  /** What else goes with the territory: its being a capital or victory city, its canals. */
  static List<String> removeDetails(final Game game, final String territory) {
    final List<String> details = new ArrayList<>();
    MapXml.capitalOf(game, territory)
        .ifPresent(nation -> details.add("It is the capital of " + nation + "."));
    if (MapXml.isVictoryCity(game, territory)) {
      details.add("It is a victory city.");
    }
    CanalXml.canals(game).stream()
        .filter(c -> c.seaZones().contains(territory) || c.lands().contains(territory))
        .forEach(c -> details.add("The canal " + c.name() + " goes with it."));
    return List.copyOf(details);
  }

  private void tellStillNamed(final String territory, final List<String> named) {
    Dialogs.refuseNamed(page, territory + " can't be removed yet", stillNamedText(named), named);
  }

  /** Why a territory stays: what still names it, at most six of them. */
  static String stillNamedText(final List<String> named) {
    return stillNamedText("it", named);
  }

  /** As {@link #stillNamedText(List)}; of several territories: "them". */
  private static String stillNamedText(final String what, final List<String> named) {
    return "The game would not load without "
        + what
        + ".\nThese still name "
        + what
        + ":\n"
        + named.stream().limit(MAX_NAMED).map(n -> "• " + n).collect(Collectors.joining("\n"))
        + (named.size() > MAX_NAMED ? " … and " + (named.size() - MAX_NAMED) + " more" : "")
        + "\nChange them in Attachments first.";
  }

  // ---- names

  /** Renames the territories a chosen file lists, as one step, and tells how it went. */
  private void importNames() {
    commit();
    final Optional<Path> file =
        MapMakerWindow.chooseFileToOpen(page, "Import territory names", userFolder(), "txt", "csv");
    if (file.isEmpty()) {
      return;
    }
    final Renamed renamed;
    try {
      renamed = project.renameAll(Files.readAllLines(file.get(), StandardCharsets.UTF_8));
    } catch (final IOException e) {
      Dialogs.warn(
          page, "Import names", "Could not read " + file.get().getFileName() + ":\n" + e.getMessage());
      return;
    }
    final String count =
        renamed.count() + (renamed.count() == 1 ? " territory" : " territories");
    if (renamed.refused().isEmpty()) {
      Dialogs.toast(page, "✓ Renamed " + count + " as one step.", "Undo", project::undo);
      return;
    }
    Dialogs.warn(
        page,
        "Import names",
        "Renamed "
            + count
            + (renamed.count() > 0 ? " as one step: Undo takes all back." : ".")
            + "\nRefused:\n"
            + String.join("\n", renamed.refused()));
  }

  private static Path userFolder() {
    return Path.of(System.getProperty("user.home"));
  }

  /**
   * Writes a PNG of each territory chosen into a folder, see-through outside its outlines, and
   * territory-pictures.txt with where each lies on the map (name;file;x;y), so a design drawn on
   * it can be laid back at its place; files there already are replaced only after asking.
   */
  private static final String PICTURE_POSITIONS = "territory-pictures.txt";
  // Windows' reserved file names, also with an extension
  private static final Pattern RESERVED =
      Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

  private void exportPictures() {
    final List<String> territories = List.copyOf(project.getFiles().polygons().keySet());
    final Optional<List<String>> chosen =
        Dialogs.pickMany(
            page,
            "Export territory pictures",
            territories,
            Optional.ofNullable(selected).map(List::of).orElse(List.of()),
            "Next");
    if (chosen.isEmpty() || chosen.get().isEmpty()) {
      return;
    }
    final Optional<Path> folder =
        MapMakerWindow.chooseFolder(page, "Choose a folder for the pictures", userFolder());
    if (folder.isEmpty()) {
      return;
    }
    final Map<String, String> pictures = pictureFiles(chosen.get());
    final Path positions = folder.get().resolve(PICTURE_POSITIONS);
    final long there =
        pictures.values().stream().map(folder.get()::resolve).filter(Files::exists).count();
    if (there > 0
        && !Dialogs.confirm(
            page,
            "Export territory pictures",
            there + " of these pictures are in the folder already. Replace them?",
            "Replace")) {
      return;
    }
    final MapFiles files = project.getFiles();
    new SwingWorker<Integer, Void>() {
      @Override
      protected Integer doInBackground() throws IOException {
        final BufferedImage map =
            files
                .basePicture()
                .orElseThrow(
                    () ->
                        new IOException(
                            "The map has no picture, or one too big to hold in memory."));
        final Map<String, String> lines = new LinkedHashMap<>();
        for (final Map.Entry<String, String> picture : pictures.entrySet()) {
          final Optional<MapFiles.TerritoryPicture> cut =
              files.territoryPicture(map, picture.getKey());
          if (cut.isPresent()) {
            ImageIO.write(
                cut.get().picture(), "png", folder.get().resolve(picture.getValue()).toFile());
            lines.put(
                picture.getKey(),
                String.join(
                    ";",
                    picture.getKey(),
                    picture.getValue(),
                    String.valueOf(cut.get().at().x),
                    String.valueOf(cut.get().at().y)));
          }
        }
        final List<String> before =
            Files.exists(positions)
                ? Files.readAllLines(positions, StandardCharsets.UTF_8)
                : List.of();
        Files.write(positions, withPositions(before, lines), StandardCharsets.UTF_8);
        return lines.size();
      }

      @Override
      protected void done() {
        try {
          Dialogs.toast(page, "✓ Wrote " + get() + " territory pictures.");
        } catch (final ExecutionException e) {
          Dialogs.warn(
              page,
              "Export territory pictures",
              "Could not write the pictures:\n" + e.getCause().getMessage());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }.execute();
  }

  /**
   * Each territory's picture file: its name with what files may not have replaced, and a number
   * after a name taken already in this export (files on Windows ignore case).
   */
  static Map<String, String> pictureFiles(final List<String> territories) {
    final Map<String, String> files = new LinkedHashMap<>();
    final Set<String> taken = new HashSet<>();
    for (final String territory : territories) {
      final String base = fileSafe(territory);
      String file = base + ".png";
      for (int n = 2; !taken.add(file.toLowerCase(Locale.ROOT)); n++) {
        file = base + " (" + n + ").png";
      }
      files.put(territory, file);
    }
    return files;
  }

  private static String fileSafe(final String name) {
    final String safe =
        name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[. ]+$", "_");
    return safe.isEmpty() || RESERVED.matcher(safe).matches() ? safe + "_" : safe;
  }

  /**
   * territory-pictures.txt with these lines (name;file;x;y by territory): an earlier export's
   * lines stay, but for the territories written now or a file written over now.
   */
  static List<String> withPositions(final List<String> before, final Map<String, String> now) {
    final Set<String> files =
        now.values().stream()
            .map(line -> line.split(";")[1].toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    final List<String> lines = new ArrayList<>();
    for (final String line : before) {
      final String[] parts = line.split(";");
      if (parts.length < 2
          || (!now.containsKey(parts[0]) && !files.contains(parts[1].toLowerCase(Locale.ROOT)))) {
        lines.add(line);
      }
    }
    lines.addAll(now.values());
    return lines;
  }

  /** Writes the names as lines "name;" for a spreadsheet, where the new names are filled in. */
  private void exportNames() {
    final Optional<Path> file =
        FileChooser.builder()
            .parent((Frame) SwingUtilities.getAncestorOfClass(Frame.class, page))
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
    final List<String> names = project.nameList();
    try {
      Files.write(file.get(), names, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      Dialogs.warn(
          page,
          "Export names",
          "Could not write " + file.get().getFileName() + ":\n" + e.getMessage());
      return;
    }
    Dialogs.toast(
        page,
        "✓ Wrote the names of "
            + names.size()
            + " territories. Fill in the new names after the ';', then use Import names.");
  }

  // ---- units

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
    final JPanel row = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    row.setOpaque(false);
    row.add(
        new JLabel(
            units.getUnitType()
                + " · "
                + Optional.ofNullable(units.getOwner()).orElse(NO_OWNER)),
        BorderLayout.CENTER);
    final UnaryOperator<Game> removed =
        g -> MapXml.withUnits(g, territory, units.getOwner(), units.getUnitType(), 0);
    final JSpinner count =
        Form.newNumber(
            units.getQuantity(),
            9999,
            c -> {
              if (c == 0) {
                // through the project, so the list is built again without the row
                project.edit(removed);
              } else {
                edit(
                    g ->
                        MapXml.withUnits(
                            g, territory, units.getOwner(), units.getUnitType(), c));
              }
            });
    count.setToolTipText("0 removes them");
    count.setPreferredSize(new Dimension(96, count.getPreferredSize().height));
    final JPanel end = leftAligned();
    end.add(count);
    end.add(
        Theme.newIconButton(
            "×", "Remove " + units.getUnitType(), () -> project.edit(removed)));
    row.add(end, BorderLayout.EAST);
    return row;
  }

  /** All the territory's units away as one step, told with Undo instead of asked before. */
  private void removeUnits(final String territory) {
    commit();
    final int count =
        MapXml.units(project.game(), territory).stream().mapToInt(UnitPlacement::getQuantity).sum();
    project.edit(g -> MapXml.withTerritoryUnits(g, territory, List.of()));
    Dialogs.toast(
        page,
        "✓ Removed " + SearchList.count(count, count, "unit", "units") + " from " + territory,
        "Undo",
        project::undo);
  }

  /** Asks for a unit type and its owner, the territory's first (none for null), and adds one. */
  private void addUnits(final String territory, @Nullable final String owner) {
    commit();
    final JComboBox<String> type = new JComboBox<>();
    MapXml.unitTypes(project.game()).forEach(type::addItem);
    final JComboBox<String> of = newOwnerBox(owner);
    final Form fields = new Form(240);
    fields.setBorder(BorderFactory.createEmptyBorder());
    fields.field("Unit type", type).field("Owner", of);
    if (!Dialogs.ask(page, "Add units", fields, "Add")
        || !(type.getSelectedItem() instanceof String unitType)) {
      return;
    }
    final String unitOwner = chosenOwner(of);
    final int already =
        MapXml.units(project.game(), territory).stream()
            .filter(u -> unitType.equals(u.getUnitType()) && Objects.equals(unitOwner, u.getOwner()))
            .mapToInt(UnitPlacement::getQuantity)
            .sum();
    project.edit(g -> MapXml.withUnits(g, territory, unitOwner, unitType, already + 1));
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
