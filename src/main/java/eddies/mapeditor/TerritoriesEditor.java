package eddies.mapeditor;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.swing.FileChooser;
import org.triplea.swing.SwingComponents;
import org.triplea.swing.WrapLayout;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapProject.Renamed;

/**
 * The main editor: every territory with its kind, owner, value, capital, victory city and the units
 * it starts with. A click on the map chooses a territory, or paints the brush onto it.
 */
final class TerritoriesEditor implements Editor {
  // first in the owner boxes; told by its place, as a map may have a nation called "No owner"
  private static final String NO_OWNER = "No owner";
  private static final String OWNER_AT_START = "(its owner at the start)";
  private static final List<Integer> VALUES = List.of(0, 1, 2, 3, 4, 5, 6, 8, 10, 12);
  // the width of the decoration box: G40's misc folder holds dozens of long names
  private static final String DECORATION_WIDTH = "a_decoration_name.png";
  private static final String LIST = "list";
  private static final String TERRITORY = "territory";
  private static final String SPLIT = "split";
  private static final String NAME_REFUSED = "Already used, empty, or holds : < (.";
  private static final int MAX_NAMED = 6;
  // how near a border, in pixels of the screen, a drawn line's end is brought to it
  private static final int SNAP_PIXELS = 12;

  // the colors a line drawn into the picture may have; the first stands for the picture's own
  private static final List<String> LINE_COLOR_NAMES = List.of("Like its lines", "Black", "Grey");
  private static final List<Color> LINE_COLORS =
      List.of(Color.BLACK, Color.BLACK, new Color(0x80, 0x80, 0x80));

  /** What a click on the map does besides choosing the territory. */
  private enum Mode {
    SELECT,
    PAINT,
    DECORATE,
    MOVE_PLACES,
    SPLIT,
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
  private final JToggleButton decorations = new JToggleButton("Decorations");
  private final JToggleButton drawBorders = new JToggleButton("Draw borders");
  private final Consumer<BufferedImage> findTerritories;
  private final JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_XS, 0));
  // in the inspector, built again with it; one button, so the banner's Done can untoggle it
  private final JToggleButton movePlaces = new JToggleButton("Move places");
  private final JComboBox<String> decorationPicture = new JComboBox<>();
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
    project.onChange(
        () -> {
          list.refresh();
          final boolean sourceGone =
              copySource.filter(s -> !MapXml.territories(project.game()).contains(s)).isPresent();
          // a cut of the outlines before the change, eg an undo, would split what is no more
          if (sourceGone || pendingCut.isPresent()) {
            resetMode();
            refreshMap();
          } else if (mode == Mode.PAINT && paintedEffect.isPresent()) {
            refreshMap();
          }
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
    final JButton names = new JButton("⋯");
    Theme.setTertiaryButtonStyle(names);
    names.setToolTipText("Rename many territories at once with a file");
    names.getAccessibleContext().setAccessibleName("Rename many territories at once with a file");
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

  /** Select, Paint ▾, Decorations and Draw borders above the map. */
  private void buildModes() {
    final ButtonGroup group = new ButtonGroup();
    for (final JToggleButton button : List.of(select, paint, decorations, drawBorders)) {
      button.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
      group.add(button);
      modes.add(button);
    }
    select.setSelected(true);
    select.setToolTipText("A click on the map chooses the territory");
    paint.setToolTipText("Give many territories an owner, a value, units or rules at once");
    decorations.setToolTipText("Place pictures from the map's misc folder anywhere on the map");
    select.addActionListener(e -> endMode());
    paint.addActionListener(
        e -> {
          // the click toggled it: the menu decides
          showModeButtons();
          paintMenu().show(paint, 0, paint.getHeight());
        });
    decorations.addActionListener(e -> startMode(Mode.DECORATE));
    drawBorders.setToolTipText(
        "Draw border lines into the map's picture by hand, then find the territories in them");
    drawBorders.addActionListener(e -> startMode(Mode.DRAW));
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
    applyMode();
    map.select(selected);
  }

  @Override
  public void show(final String territory) {
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

  /** A click on the map: paints the brush, if any, and chooses the territory. */
  void clickOnMap(final String territory) {
    if (mode == Mode.SPLIT || mode == Mode.DRAW) {
      // a click while drawing the line must not change the territory being cut
      return;
    }
    // a text being typed is the shown territory's, before the inspector shows another
    commit();
    brush
        .filter(b -> mode == Mode.PAINT)
        .ifPresent(b -> project.edit(game -> b.paint().apply(game, territory)));
    choose(territory);
    list.choose(territory);
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
      if (mode == Mode.SPLIT || mode == Mode.MOVE_PLACES) {
        if (mode == Mode.SPLIT) {
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
    switch (mode) {
      case SELECT -> {
        map.stopEditingPlaces();
        map.hideBanner();
      }
      case PAINT -> {
        map.stopEditingPlaces();
        map.showBanner(brush.orElseThrow().banner(), this::endMode);
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
      case DRAW -> {
        borderStyle()
            .ifPresentOrElse(
                style -> {
                  map.drawLine(this::borderDrawn);
                  map.showBanner(
                      "Drag on the map to draw a border line.",
                      Optional.of(drawingTools(style)),
                      "Done",
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
    map.mark(paintedEffect.map(this::territoriesWith).orElse(Set.of()));
  }

  private void showModeButtons() {
    (switch (mode) {
          case PAINT -> paint;
          case DECORATE -> decorations;
          case DRAW -> drawBorders;
          default -> select;
        })
        .setSelected(true);
    paint.setText(brush.map(Brush::title).orElse("Paint") + " ▾");
    paint.setIcon(brush.flatMap(Brush::icon).orElse(null));
    movePlaces.setSelected(mode == Mode.MOVE_PLACES);
  }

  private Set<String> territoriesWith(final String effect) {
    return Set.copyOf(
        TerritoryEffectsXml.territoriesWith(project.game()).getOrDefault(effect, List.of()));
  }

  /** What a paint click does to a territory, the Paint button's text and the banner's. */
  private record Brush(
      String title, String banner, Optional<Icon> icon, BiFunction<Game, String, Game> paint) {}

  /** The Paint menu: owners, values, units, rules and terrain effects, built for each click. */
  JPopupMenu paintMenu() {
    final JPopupMenu menu = new JPopupMenu();
    final Game game = project.game();
    menu.add(header("Owner"));
    // owners paint land only: a sweep across the map leaves the sea zones as they are
    for (final String nation : MapXml.nations(game)) {
      add(
          menu,
          new Brush(
              nation,
              "Painting owner "
                  + nation
                  + ": click or drag across land to give it to "
                  + nation
                  + ". Sea zones stay as they are.",
              Optional.of(new MapLegend.Swatch(project.nationColor(nation))),
              (g, t) -> MapXml.isWater(g, t) ? g : MapXml.withOwner(g, t, nation)));
    }
    add(
        menu,
        new Brush(
            NO_OWNER,
            "Painting no owner: click land to take its owner away.",
            Optional.empty(),
            (g, t) -> MapXml.isWater(g, t) ? g : MapXml.withOwner(g, t, null)));
    menu.add(header("PUs per turn"));
    final JPanel values = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
    values.setOpaque(false);
    values.setAlignmentX(Component.LEFT_ALIGNMENT);
    values.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP_S, 0, Theme.GAP_S));
    for (final int value : VALUES) {
      final Brush valueBrush =
          new Brush(
              pus(value),
              "Painting " + pus(value) + " per turn: click territories to set their value.",
              Optional.empty(),
              (g, t) -> MapXml.withProduction(g, t, value));
      final JToggleButton button = new JToggleButton(String.valueOf(value));
      button.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
      button.setSelected(
          mode == Mode.PAINT && brush.map(Brush::title).equals(Optional.of(valueBrush.title())));
      button.addActionListener(
          e -> {
            menu.setVisible(false);
            paintWith(valueBrush);
          });
      values.add(button);
    }
    menu.add(values);
    menu.add(header("Units"));
    final Optional<String> source =
        Optional.ofNullable(selected).filter(MapXml.territories(game)::contains);
    final JMenuItem copy =
        menu.add(source.map(s -> "Copy units of " + s).orElse("Copy units of the chosen one"));
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
                              + "'s units: click territories to give them the same starting"
                              + " units, turned to their owner.",
                          Optional.empty(),
                          (g, t) ->
                              MapXml.territories(g).contains(from)
                                  ? MapXml.withUnitsCopied(g, from, t)
                                  : g));
                  copySource = Optional.of(from);
                }));
    add(
        menu,
        new Brush(
            "Clear units",
            "Clearing units: click territories to take their starting units away.",
            Optional.empty(),
            (g, t) -> MapXml.withTerritoryUnits(g, t, List.of())));
    menu.add(header("Rules"));
    add(
        menu,
        new Brush(
            "Impassable",
            "Painting impassable: click territories no unit may enter.",
            Optional.empty(),
            (g, t) -> TerritoryXml.withFlag(g, t, TerritoryXml.IMPASSABLE)));
    add(
        menu,
        new Brush(
            "Passable",
            "Painting passable: click territories units may enter again.",
            Optional.empty(),
            (g, t) -> TerritoryXml.withoutFlag(g, t, TerritoryXml.IMPASSABLE)));
    final List<String> effects = TerritoryEffectsXml.effects(game);
    if (!effects.isEmpty()) {
      menu.add(header("Terrain effects"));
      effects.forEach(effect -> add(menu, effectBrush(effect)));
      add(
          menu,
          new Brush(
              "No effects",
              "Clearing effects: click territories to take all their effects away.",
              Optional.empty(),
              (g, t) -> TerritoryXml.withEffects(g, t, List.of())));
    }
    return menu;
  }

  private static JLabel header(final String text) {
    final JLabel header = Theme.newSectionLabel(text);
    header.setBorder(
        BorderFactory.createEmptyBorder(Theme.GAP_S, Theme.GAP_M, Theme.GAP_XS, Theme.GAP_M));
    // at the left like the items
    header.setAlignmentX(Component.LEFT_ALIGNMENT);
    return header;
  }

  private void add(final JPopupMenu menu, final Brush chosen) {
    final JMenuItem item = menu.add(chosen.title());
    chosen.icon().ifPresent(item::setIcon);
    item.addActionListener(e -> paintWith(chosen));
  }

  private static Brush effectBrush(final String effect) {
    return new Brush(
        effect,
        "Painting " + effect + ": click territories to add the effect.",
        Optional.empty(),
        (g, t) -> TerritoryXml.withEffect(g, t, effect));
  }

  private void paintWith(final Brush chosen) {
    paintWith(chosen, Optional.empty());
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
    decorations.setVisible(!pictures.isEmpty());
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
                    "! Not drawn: start and end a border line near another border"
                        + " or the map's edge."));
  }

  /** Ends drawing and finds the territories in the picture with the lines drawn into it. */
  void findDrawnTerritories() {
    endMode();
    project.getFiles().picture().ifPresent(findTerritories);
  }

  /** The line's color and width, and Find territories, for the banner. */
  private JPanel drawingTools(final MapPicture.LineStyle style) {
    final JButton find = new JButton("Find territories");
    Theme.setPrimaryButtonStyle(find);
    find.setToolTipText("The areas between the lines become territories, as Redraw borders does");
    find.addActionListener(e -> findDrawnTerritories());
    final JPanel tools = lineFields(style);
    tools.add(Box.createHorizontalStrut(Theme.GAP_M));
    tools.add(find);
    return tools;
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

    final List<UnitPlacement> units = MapXml.units(game, territory);
    inspector.section("Units at the start (" + units.size() + ")");
    units.forEach(u -> inspector.row(unitRow(territory, u)));
    final JButton addUnits =
        Theme.newAddButton(
            "Add units", "Add starting units", () -> addUnits(territory, owner.orElse(null)));
    addUnits.setEnabled(!MapXml.unitTypes(game).isEmpty());
    final JPanel addRow = leftAligned();
    addRow.add(addUnits);
    inspector.row(addRow);

    final boolean water = MapXml.isWater(game, territory);
    inspector.more("territory.rules", "Rules", f -> fillRules(f, game, territory, water));
    final List<String> effects = TerritoryEffectsXml.effects(game);
    if (!effects.isEmpty()) {
      final List<String> has = MapXml.territoryEffects(game, territory);
      inspector.more(
          "territory.effects",
          "Terrain effects (" + has.size() + " checked)",
          f -> f.row(effectBoxes(territory, effects, has)));
    }
    inspector.more(
        "territory.map", "On the map: places, outline", f -> fillOnTheMap(f, territory));
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
    final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
    actions.setOpaque(false);
    actions.add(movePlaces);
    actions.add(split);
    onTheMap.row(actions);
    final JPanel again = leftAligned();
    again.add(findPlaces);
    onTheMap.row(again);
    onTheMap.note(
        "Where the game draws " + SearchList.escape(territory) + "'s units, name and markers.");
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
    Dialogs.warn(page, territory + " can't be removed yet", stillNamedText(named));
  }

  /** Why a territory stays: what still names it, at most six of them. */
  static String stillNamedText(final List<String> named) {
    return "The game would not load without it.\nThese still name it:\n"
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
    final JSpinner count =
        Form.newNumber(
            units.getQuantity(),
            9999,
            c ->
                edit(
                    g ->
                        MapXml.withUnits(
                            g, territory, units.getOwner(), units.getUnitType(), c)));
    count.setToolTipText("0 removes them");
    count.setPreferredSize(new Dimension(96, count.getPreferredSize().height));
    row.add(count, BorderLayout.EAST);
    return row;
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
