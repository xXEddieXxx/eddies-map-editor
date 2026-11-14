package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Map.Connection;
import eddies.mapeditor.MapFiles.Marker;

/**
 * The map of the map maker: the territories by their outlines, colored by the chosen {@link
 * MapMode}. A click picks a territory, dragging with any mouse button moves the map, Ctrl and the
 * mouse wheel zoom, as in the game. While places are edited, the selected territory's show above
 * it, {@link #editPlaces}.
 */
@Slf4j
final class MapCanvas extends JComponent {
  @Serial private static final long serialVersionUID = 1L;

  // shared with the legend below the map
  static final Color SEA = new Color(0x3E6A8A);
  static final Color NEUTRAL = new Color(0xA89F86);
  // the Political view's hues, one per alliance in its order: Okabe-Ito's, told apart also by the
  // color-blind, their shades too; no blue, which would read as sea; from the seventh alliance on
  // the hues repeat
  private static final List<Color> ALLIANCE_COLORS =
      List.of(
          new Color(0xD55E00),
          new Color(0x009E73),
          new Color(0xE69F00),
          new Color(0xCC79A7),
          new Color(0xF0E442),
          new Color(0x6A3D9A));
  // the map is content: the same under a light and a dark theme
  static final Color MAP_BACKGROUND = new Color(0xE8E4D8);
  private static final Color OUTLINE = new Color(0, 0, 0, 0x99);
  static final Color PROBLEM = new Color(0xE53935);
  // territories a tool shows together, eg a canal's: fixed, as a grey system accent drowns in maps
  static final Color MARKED = new Color(0xD81B9A);
  private static final Color BADGE = new Color(0x14, 0x14, 0x14, 0xC0);
  private static final Color BADGE_TEXT = new Color(0xFFE178);
  // at this zoom or less a smaller copy of the picture shows: the screen holds more tiles than kept
  private static final double OVERVIEW_ZOOM = 0.6;
  private static final int OVERVIEW_WIDTH = 4096;
  // unit pictures smaller than this, in pixels, show as a count instead
  private static final int MIN_UNIT_SIZE = 14;
  // squares of the screen drawn once, 1 MB each at 100 %, 4 MB at 200 %: enough for a big screen
  private static final int LAYER_SIZE = 512;
  private static final int LAYER_SQUARES_KEPT = 32;
  // the radius of a marker's dot while places are edited, in pixels of the screen
  private static final int PLACE_DOT = 8;
  // a unit place narrower on screen gets no number: zoomed out they would run into each other
  private static final int NUMBERED_PLACE = 24;
  // the size of a decoration whose picture is missing, so it can still be moved or removed
  private static final int MISSING_DECORATION = 16;
  private static final int LINE_CHECK_MS = 100;
  // how far the line's halo and end handle reach beyond it, in pixels of the screen
  private static final int LINE_REACH = 10;
  // a terrain effect's picture is never smaller than this, in pixels of the screen; a missing one
  // shows as a round badge this big
  private static final int MIN_EFFECT_ICON = 14;
  private static final int EFFECT_BADGE = 18;
  // screen px between the samples of a paint stroke, so a fast move skips no territory
  private static final int STROKE_STEP = 4;

  private final transient MapProject project;
  // the tiles seen last, enough for a screen at any zoom; a big map has far more than fit memory
  private final transient Map<Point, Optional<Image>> tiles = newRecentlyUsed(200);
  private final transient Map<Point, Optional<Image>> reliefTiles = newRecentlyUsed(200);
  // the squares of the screen drawn last, and what they show
  private final transient Map<Point, BufferedImage> layer = newRecentlyUsed(LAYER_SQUARES_KEPT);
  private transient @Nullable LayerKey layerOf;
  // counts the changes, so the squares are drawn again after one
  private int changes;
  // what the territories show, worked out when first shown after a change, not on every repaint
  private final transient Map<String, Look> looks = new HashMap<>();
  private transient @Nullable Set<String> problemTerritories;
  private transient @Nullable Map<String, Color> allianceColors;
  // nations' colors shown while one is being chosen, before it is taken
  private transient Map<String, Color> previewColors = Map.of();
  // these follow the map's files: the territories' bounds, units' places and pictures
  private transient @Nullable MapFiles filesSeen;
  private transient Optional<BufferedImage> reliefSeen = Optional.empty();
  private final transient Map<String, Rectangle> bounds = new HashMap<>();
  private final transient Map<String, Shape> filled = new HashMap<>();
  private final transient Map<String, List<Point>> unitPlaces = new HashMap<>();
  private final transient Map<String, Optional<Image>> unitPictures = new HashMap<>();
  // the picture made smaller once, for a zoom at which the screen holds more tiles than are kept
  private transient @Nullable BufferedImage overview;
  private transient List<Object> overviewOf = List.of();
  private MapMode mode = MapMode.GAME;
  // the size of a unit on the map, as the files have it
  private Dimension unitSize = new Dimension();
  private double zoom = 0.5;
  private boolean showPicture = true;
  private @Nullable String selected;
  // territories a tool shows together, eg a canal's sea zones and land
  private transient Set<String> marked = Set.of();
  // territories shown lighter beside them, eg every other canal's
  private transient Set<String> faintlyMarked = Set.of();
  // canals in MARKED on a halo; other tools' marks, eg a nation's land, in the calm accent
  private boolean canalMarks;
  private transient Consumer<String> onClick = territory -> {};
  // what Esc does, while a tool's mode is on
  private transient Optional<Runnable> onEscape = Optional.empty();
  // the selected territory's places shown to move, add and remove
  private boolean editingPlaces;
  private boolean addsUnitPlaces;
  // the place being dragged and how far, in pixels of the screen
  private transient @Nullable Place dragged;
  private Point draggedBy = new Point();
  // where the mouse is over the map, for the Delete key; empty once it left or the map moved
  private Optional<Point> hovered = Optional.empty();
  private transient Optional<Place> hoveredPlace = Optional.empty();
  // the misc picture a click places while decorations are edited
  private Optional<String> decorating = Optional.empty();
  // what a line dragged on the map is for, and the line being dragged, in the map's pixels
  private transient Optional<Consumer<List<Point>>> drawingLine = Optional.empty();
  private transient List<Point> line = new ArrayList<>();
  // a line set by clicks, point by point, instead of dragged; the pointer ends its last segment
  private boolean byPoints;
  // a drag from corner to corner draws an ellipse instead, given as a closed line
  private boolean byCircle;
  // the eraser's width in the map's pixels while its band is dragged, else 0
  private int eraserWidth;
  // the eraser's click: the whole border between two territories, shown while the mouse is on it
  private transient Optional<Consumer<MapFiles.SharedBorder>> onBorder = Optional.empty();
  private int borderLineWidth;
  private transient Optional<MapFiles.SharedBorder> hoveredBorder = Optional.empty();
  private transient Optional<List<String>> hoveredPair = Optional.empty();
  private transient Optional<BufferedImage> hoveredBorderPicture = Optional.empty();
  private transient @Nullable MapFiles hoveredBorderOf;
  private transient Optional<Point> pointer = Optional.empty();
  // screen px a click may move between press and release and still set a point
  private static final int CLICK_SLOP = 4;
  // whether the line cuts, asked at most every LINE_CHECK_MS: a big outline takes a moment
  private transient Optional<Predicate<List<Point>>> lineCheck = Optional.empty();
  private boolean lineCuts;
  private final Timer lineCheckTimer = new Timer(LINE_CHECK_MS, e -> checkLine());
  // the part a split would make, hatched, in the map's pixels
  private transient List<Polygon> cut = List.of();
  // the selected territory's neighbours, in the Connections view
  private transient Set<String> neighbours = Set.of();
  // the pictures of decorations, by their file
  private final transient Map<Path, Optional<Image>> decorationPictures = new HashMap<>();
  // while a terrain effect brush is chosen: every territory's effects as the game draws them,
  // the painted one standing out; worked out once per change
  private boolean showingEffects;
  private Optional<String> standingOut = Optional.empty();
  private transient @Nullable Map<String, List<String>> effects;
  private final transient Map<String, List<Point>> effectPlaces = new HashMap<>();
  private final transient Map<String, Optional<Image>> effectPictures = new HashMap<>();
  // while a texture brush is chosen: every textured territory filled with it, worked out once per
  // change and drawn into the squares
  private boolean showingTextures;
  private transient @Nullable Map<String, Textures.Fill> textureFills;
  // what a left drag paints, while a brush is chosen, and the territories its stroke passed
  private transient Optional<Consumer<List<String>>> onStroke = Optional.empty();
  private transient Set<String> stroke = new LinkedHashSet<>();

  // the places shown on every territory to drag, and those worked out for them once per change
  private ShownPlaces everywhere = ShownPlaces.NONE;
  private transient List<Place> shownPlaces = List.of();
  private transient List<Object> shownPlacesOf = List.of();

  /**
   * A place the game draws something at, in a territory: units, by their place in the order, a
   * marker, by its number for effects, or its center; or a decoration's picture, by its number.
   */
  private record Place(
      Optional<Marker> marker,
      Optional<String> decoration,
      int index,
      Point at,
      String territory,
      boolean center) {}

  /** Places that are no marker: a territory's units' and its center. */
  enum PlaceKind {
    UNITS,
    CENTERS
  }

  /** Which places every territory shows to drag: {@link PlaceKind}s and {@link Marker}s. */
  record ShownPlaces(Set<Enum<?>> kinds) {
    static final ShownPlaces NONE = new ShownPlaces(Set.of());
    // all of the selected territory's, as Move places shows them
    private static final ShownPlaces SELECTED =
        NONE.with(PlaceKind.UNITS).withAll(EnumSet.allOf(Marker.class));

    boolean shows(final Enum<?> kind) {
      return kinds.contains(kind);
    }

    ShownPlaces with(final Enum<?> kind) {
      return withAll(Set.of(kind));
    }

    ShownPlaces without(final Enum<?> kind) {
      final Set<Enum<?>> fewer = new HashSet<>(kinds);
      fewer.remove(kind);
      return new ShownPlaces(Set.copyOf(fewer));
    }

    private ShownPlaces withAll(final Set<? extends Enum<?>> more) {
      final Set<Enum<?>> all = new HashSet<>(kinds);
      all.addAll(more);
      return new ShownPlaces(Set.copyOf(all));
    }
  }

  /**
   * What the squares of the screen show; the files and the small picture compare as the same
   * objects, as each change makes new ones.
   */
  private record LayerKey(
      double zoom,
      double screenScale,
      MapMode mode,
      boolean showPicture,
      @Nullable String selected,
      Set<String> marked,
      Set<String> faintlyMarked,
      boolean canalMarks,
      boolean showingTextures,
      int changes,
      MapFiles files,
      @Nullable BufferedImage overview) {}

  /** The territories with a problem, from the project's problems of the map as it is now. */
  private Set<String> problemTerritories() {
    if (problemTerritories == null) {
      problemTerritories =
          project.problems().stream()
              .flatMap(problem -> problem.territory().stream())
              .collect(Collectors.toSet());
    }
    return problemTerritories;
  }

  /** What a territory shows, in map coordinates. */
  private record Look(
      boolean water,
      boolean impassable,
      @Nullable String owner,
      String values,
      List<UnitPlacement> units,
      boolean changed,
      Point center) {}

  MapCanvas(final MapProject project) {
    this.project = project;
    lineCheckTimer.setRepeats(false);
    // a new picture may change the map's size
    project.onChange(
        () -> {
          looks.clear();
          problemTerritories = null;
          allianceColors = null;
          effects = null;
          textureFills = null;
          effectPlaces.clear();
          effectPictures.clear();
          changes++;
          revalidate();
          repaint();
        });
    ToolTipManager.sharedInstance().registerComponent(this);
    final MouseAdapter mouse = new Mouse();
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addMouseWheelListener(mouse);
    // Delete removes the unit place under the mouse; a focused text field keeps its own Delete
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removePlace");
    getActionMap()
        .put(
            "removePlace",
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                hovered
                    .flatMap(MapCanvas.this::placeAt)
                    .filter(place -> place.marker().isEmpty())
                    .ifPresent(MapCanvas.this::remove);
              }
            });
    // like Delete, wherever the focus is in the window; without a mode Esc stays free for others
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
    getActionMap()
        .put(
            "escape",
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public boolean isEnabled() {
                return drawingNow() || onEscape.isPresent();
              }

              @Override
              public void actionPerformed(final ActionEvent e) {
                if (drawingNow()) {
                  dropLine();
                } else {
                  onEscape.ifPresent(Runnable::run);
                }
              }
            });
    bindWhileSettingPoints(KeyEvent.VK_ENTER, "finishLine", this::finishLine);
    bindWhileSettingPoints(KeyEvent.VK_BACK_SPACE, "dropPoint", this::dropPoint);
  }

  /** A key for a line being set point by point; else the key stays free for others. */
  private void bindWhileSettingPoints(final int key, final String name, final Runnable action) {
    getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
    getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public boolean isEnabled() {
                return settingPoints();
              }

              @Override
              public void actionPerformed(final ActionEvent e) {
                action.run();
              }
            });
  }

  private boolean settingPoints() {
    return byPoints && drawingLine.isPresent() && !line.isEmpty();
  }

  /** A line being set or dragged: Esc drops it rather than end the tool's mode. */
  private boolean drawingNow() {
    return drawingLine.isPresent() && !line.isEmpty();
  }

  /** A map that forgets the entries used least recently beyond this many. */
  private static <K, V> Map<K, V> newRecentlyUsed(final int kept) {
    return new LinkedHashMap<>(kept + 1, 0.75f, true) {
      @Serial private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
        return size() > kept;
      }
    };
  }

  void setMode(final MapMode mode) {
    this.mode = mode;
    repaint();
  }

  void showPicture() {
    showPicture = true;
    repaint();
  }

  void hidePicture() {
    showPicture = false;
    repaint();
  }

  /** What a click on a territory does. */
  void onTerritoryClick(final Consumer<String> onClick) {
    this.onClick = onClick;
  }

  /** What Esc does from now on, eg end a tool's mode; empty: nothing. */
  void onEscape(final Optional<Runnable> onEscape) {
    this.onEscape = onEscape;
  }

  /**
   * Shows the selected territory's places: dragging one moves it, a click into the territory adds
   * a place for units, a right-click removes one.
   */
  void editPlaces() {
    stopEditingPlaces();
    editingPlaces = true;
    repaint();
  }

  /**
   * Shows the decorations: dragging one moves it, a click places the misc picture given, a
   * right-click removes one.
   */
  void editDecorations(final String picture) {
    stopEditingPlaces();
    decorating = Optional.of(picture);
  }

  /** A line dragged with the left button goes to onLine instead of moving the map. */
  void drawLine(final Consumer<List<Point>> onLine) {
    stopEditingPlaces();
    drawingLine = Optional.of(onLine);
    lineCheck = Optional.empty();
    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    repaint();
  }

  /**
   * Like {@link #drawLine(Consumer)}, but each click sets a point of the line: a double-click or
   * Enter finishes it, Backspace drops its last point, Esc the whole line.
   */
  void drawLineByPoints(final Consumer<List<Point>> onLine) {
    drawLine(onLine);
    byPoints = true;
  }

  /**
   * Like {@link #drawLine(Consumer)}, but a drag from corner to corner gives the ellipse between
   * them as a closed line, its last point its first; Shift makes it round.
   */
  void drawCircle(final Consumer<List<Point>> onLine) {
    drawLine(onLine);
    byCircle = true;
  }

  /**
   * Like {@link #drawLine(Consumer)}, the drag shown as a band so many of the map's pixels wide:
   * what an eraser takes away.
   */
  void eraseLine(final Consumer<List<Point>> onLine, final int width) {
    drawLine(onLine);
    eraserWidth = width;
  }

  /**
   * Like {@link #eraseLine(Consumer, int)}; besides, the whole border between two territories
   * shows while the mouse is on it, and a click there gives it to onBorder instead of a dot to
   * onLine.
   *
   * @param lineWidth how wide the picture's border lines are, in its pixels
   */
  void eraseLine(
      final Consumer<List<Point>> onLine,
      final int width,
      final int lineWidth,
      final Consumer<MapFiles.SharedBorder> onBorder) {
    eraseLine(onLine, width);
    borderLineWidth = lineWidth;
    this.onBorder = Optional.of(onBorder);
  }

  /** The border under the mouse while the eraser is chosen; worked out once per border. */
  private void hoverBorder(final Point onMap) {
    final MapFiles files = project.getFiles();
    final Optional<List<String>> meeting = files.territoriesMeetingAt(onMap, borderLineWidth);
    // also a pair without a border between them: asked once, not with every move of the mouse
    if (meeting.equals(hoveredPair) && hoveredBorderOf == files) {
      return;
    }
    hoveredPair = meeting;
    final Optional<Rectangle> before = hoveredBorder.map(MapFiles.SharedBorder::box);
    // shortcut: found on the Swing thread, some 100 ms for a border across a 2000 px box; in a
    // SwingWorker if a map's long borders make the mouse stutter
    hoveredBorder = meeting.flatMap(two -> files.sharedBorderAt(onMap, borderLineWidth));
    hoveredBorderOf = files;
    hoveredBorderPicture =
        hoveredBorder.map(
            border -> {
              final Rectangle box = border.box();
              final BufferedImage picture =
                  new BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_ARGB);
              final int color = 0xD0000000 | (MARKED.getRGB() & 0xFFFFFF);
              for (int p = 0; p < border.between().length; p++) {
                if (border.between()[p]) {
                  picture.setRGB(p % box.width, p / box.width, color);
                }
              }
              return picture;
            });
    java.util.stream.Stream.concat(
            before.stream(), hoveredBorder.map(MapFiles.SharedBorder::box).stream())
        .forEach(
            box ->
                repaint(
                    (int) (box.x * zoom) - 2,
                    (int) (box.y * zoom) - 2,
                    (int) (box.width * zoom) + 4,
                    (int) (box.height * zoom) + 4));
  }

  /** The border a click of the eraser would take away, over the map. */
  private void paintHoveredBorder(final Graphics2D g) {
    if (hoveredBorder.isEmpty() || hoveredBorderPicture.isEmpty()) {
      return;
    }
    final Rectangle box = hoveredBorder.get().box();
    g.drawImage(
        hoveredBorderPicture.get(),
        (int) Math.round(box.x * zoom),
        (int) Math.round(box.y * zoom),
        (int) Math.round(box.width * zoom),
        (int) Math.round(box.height * zoom),
        null);
  }

  /** The border the eraser shows now, eg for a test. */
  Optional<MapFiles.SharedBorder> hoveredBorder() {
    return hoveredBorder;
  }

  /** The line set point by point goes to onLine, if it has two points. */
  private void finishLine() {
    final List<Point> drawn = List.copyOf(line);
    dropLine();
    if (drawn.size() >= 2) {
      drawingLine.ifPresent(onLine -> onLine.accept(drawn));
    }
  }

  private void dropPoint() {
    if (!line.isEmpty()) {
      line.removeLast();
    }
    if (line.isEmpty()) {
      pointer = Optional.empty();
    }
    repaint();
  }

  private void dropLine() {
    line = new ArrayList<>();
    pointer = Optional.empty();
    repaint();
  }

  /**
   * Like {@link #drawLine(Consumer)}; while dragged the line is green when cuts says it cuts the
   * selected territory, else red.
   */
  void drawLine(final Consumer<List<Point>> onLine, final Predicate<List<Point>> cuts) {
    drawLine(onLine);
    lineCheck = Optional.of(cuts);
  }

  /** Hatches the part a split would make, in the map's pixels; none clears it. */
  void setCut(final List<Polygon> part) {
    cut = List.copyOf(part);
    repaint();
  }

  private void checkLine() {
    if (line.size() >= 2 && lineCheck.isPresent()) {
      lineCuts = lineCheck.get().test(List.copyOf(line));
      repaint();
    }
  }

  /**
   * While the unit places show on every territory, a click inside the chosen territory, off its
   * places, adds one there, wherever it is, also where the unit does not wholly fit; until {@link
   * #stopEditingPlaces}.
   */
  void addUnitPlacesByClick() {
    addsUnitPlaces = true;
  }

  void stopEditingPlaces() {
    editingPlaces = false;
    addsUnitPlaces = false;
    decorating = Optional.empty();
    drawingLine = Optional.empty();
    line = new ArrayList<>();
    byPoints = false;
    byCircle = false;
    eraserWidth = 0;
    onBorder = Optional.empty();
    hoveredBorder = Optional.empty();
    hoveredPair = Optional.empty();
    hoveredBorderPicture = Optional.empty();
    pointer = Optional.empty();
    lineCheck = Optional.empty();
    lineCheckTimer.stop();
    cut = List.of();
    onStroke = Optional.empty();
    stroke = new LinkedHashSet<>();
    setCursor(Cursor.getDefaultCursor());
    dragged = null;
    repaint();
  }

  Optional<String> getSelected() {
    return Optional.ofNullable(selected);
  }

  void setSelected(@Nullable final String territory) {
    selected = territory;
    repaint();
  }

  /** Shows the nation's land in this color until {@link #endPreview}, without a change. */
  void previewColor(final String nation, final Color color) {
    previewColors = Map.of(nation, color);
    changes++;
    repaint();
  }

  void endPreview() {
    previewColors = Map.of();
    changes++;
    repaint();
  }

  /** Shows these places on every territory, to drag; {@link ShownPlaces#NONE} hides them. */
  void showPlaces(final ShownPlaces places) {
    everywhere = places;
    repaint();
  }

  /** Shows these territories together in the accent color, eg a nation's land; none for none. */
  void setMarked(final Set<String> territories) {
    marked = Set.copyOf(territories);
    faintlyMarked = Set.of();
    canalMarks = false;
    repaint();
  }

  /**
   * Shows a canal's territories in {@link #MARKED} on a white halo, so they stand out on any
   * picture, and the other canals' lighter.
   */
  void setCanalMarks(final Set<String> territories, final Set<String> faintly) {
    marked = Set.copyOf(territories);
    faintlyMarked = Set.copyOf(faintly);
    canalMarks = true;
    repaint();
  }

  /** Shows each territory's terrain effects as the game draws them, the one given standing out. */
  void showEffects(final Optional<String> painted) {
    showingEffects = true;
    standingOut = painted;
    repaint();
  }

  void hideEffects() {
    showingEffects = false;
    standingOut = Optional.empty();
    repaint();
  }

  /** Fills each textured territory with its texture, {@link Textures}; an own one read anew. */
  void showTextures() {
    showingTextures = true;
    textureFills = null;
    // the squares again too: an own texture's picture may be new
    changes++;
    repaint();
  }

  void hideTextures() {
    showingTextures = false;
    repaint();
  }

  /**
   * A left drag paints from now on, until {@link #stopEditingPlaces}: the territories it passes,
   * each once and in their order, go to onStroke at its end; a click stays a click.
   */
  void paintStrokes(final Consumer<List<String>> onStroke) {
    stopEditingPlaces();
    this.onStroke = Optional.of(onStroke);
  }

  /** Scrolls the territory into the middle of the view. */
  void scrollTo(final String territory) {
    final Point center = center(territory);
    if (getParent() instanceof JViewport viewport) {
      final Dimension view = viewport.getExtentSize();
      final Point corner =
          new Point(
              Math.max(0, (int) (center.x * zoom) - view.width / 2),
              Math.max(0, (int) (center.y * zoom) - view.height / 2));
      viewport.setViewPosition(corner);
    }
  }

  @Override
  public Dimension getPreferredSize() {
    final Dimension size = project.getFiles().size();
    return new Dimension((int) (size.width * zoom), (int) (size.height * zoom));
  }

  @Override
  @Nullable
  public String getToolTipText(final MouseEvent event) {
    return placeAt(event.getPoint())
        .map(
            place ->
                place.marker().isPresent() || place.center()
                    ? label(place) + ": drag to move"
                    : place
                            .decoration()
                            .orElse("Units " + (place.index() + 1) + " · " + place.territory())
                        + ": drag to move, right-click or Delete removes")
        .or(() -> territoryAt(event.getPoint()))
        .orElse(null);
  }

  private Optional<String> territoryAt(final Point point) {
    return project.getFiles().territoryAt(point.x / zoom, point.y / zoom);
  }

  /**
   * The colors and outlines from squares drawn once until something they show changes, so moving
   * the map only copies them; the badges and units above them anew.
   */
  @Override
  protected void paintComponent(final Graphics graphics) {
    final Graphics2D g = (Graphics2D) graphics.create();
    followFiles();
    // the screen's own scale, eg 1.5 at 150 %: squares of as many pixels stay sharp
    final double screenScale = g.getTransform().getScaleX();
    final LayerKey drawn =
        new LayerKey(
            zoom,
            screenScale,
            mode,
            showPicture,
            selected,
            marked,
            faintlyMarked,
            canalMarks,
            showingTextures,
            changes,
            project.getFiles(),
            overview);
    if (!drawn.equals(layerOf)) {
      layer.clear();
      layerOf = drawn;
      neighbours =
          mode == MapMode.CONNECTIONS && selected != null
              ? MapXml.neighbors(project.game(), selected)
              : Set.of();
    }
    final Rectangle visible =
        Optional.ofNullable(g.getClipBounds()).orElseGet(() -> new Rectangle(getSize()));
    for (int column = visible.x / LAYER_SIZE;
        column * LAYER_SIZE < visible.x + visible.width;
        column++) {
      for (int row = visible.y / LAYER_SIZE; row * LAYER_SIZE < visible.y + visible.height; row++) {
        g.drawImage(
            layer.computeIfAbsent(
                new Point(column, row), square -> newLayerSquare(square, screenScale)),
            column * LAYER_SIZE,
            row * LAYER_SIZE,
            LAYER_SIZE,
            LAYER_SIZE,
            null);
      }
    }
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    final List<String> shown =
        shownIn(
            new Rectangle(
                (int) (visible.x / zoom),
                (int) (visible.y / zoom),
                (int) Math.ceil(visible.width / zoom),
                (int) Math.ceil(visible.height / zoom)));
    shown.forEach(territory -> paintBadges(g, territory));
    if (showingEffects) {
      final Font letters = getFont().deriveFont(Font.BOLD, 11f);
      shown.forEach(territory -> paintEffects(g, territory, letters));
    }
    paintStroke(g);
    places().stream()
        .filter(place -> onScreen(place).intersects(visible))
        .forEach(place -> paintPlace(g, place));
    paintVeil(g, visible);
    paintCut(g);
    paintHoveredBorder(g);
    paintLine(g);
    g.dispose();
  }

  /** While a line cuts, a white veil over all but the selected territory, so it stands out. */
  private void paintVeil(final Graphics2D g, final Rectangle visible) {
    if (drawingLine.isEmpty() || lineCheck.isEmpty() || selected == null) {
      return;
    }
    // even-odd: the outlines are holes in the veil
    final Path2D veil = new Path2D.Double(Path2D.WIND_EVEN_ODD);
    veil.append(visible, false);
    final AffineTransform toScreen = AffineTransform.getScaleInstance(zoom, zoom);
    project
        .getFiles()
        .polygons()
        .getOrDefault(selected, List.of())
        .forEach(shape -> veil.append(shape.getPathIterator(toScreen), false));
    g.setColor(withAlpha(Color.WHITE, 0x73));
    g.fill(veil);
  }

  private void paintCut(final Graphics2D g) {
    final AffineTransform toScreen = AffineTransform.getScaleInstance(zoom, zoom);
    g.setPaint(hatch(8, Theme.getAccentColor()));
    cut.forEach(part -> g.fill(toScreen.createTransformedShape(part)));
  }

  /** Accent stripes repeating every so many units of the graphics, for a part set apart. */
  private static TexturePaint hatch(final double every, final Color color) {
    final BufferedImage stripes = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = stripes.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(color);
    g.setStroke(new BasicStroke(2));
    // the corners too, so the stripes run on across tiles
    g.drawLine(-1, 9, 9, -1);
    g.drawLine(-1, 1, 1, -1);
    g.drawLine(7, 9, 9, 7);
    g.dispose();
    return new TexturePaint(stripes, new Rectangle2D.Double(0, 0, every, every));
  }

  /**
   * The line being drawn: a dashed core on a dark halo with round ends; with a check, green when it
   * cuts the selected territory, else red, and saying so at its end.
   */
  private void paintLine(final Graphics2D g) {
    // a line set by clicks: its points marked, the segment to the pointer thinner, still to come
    final Optional<Point> loose = byPoints && !line.isEmpty() ? pointer : Optional.empty();
    final List<Point> shown = new ArrayList<>(line);
    loose.ifPresent(shown::add);
    if (shown.size() < 2) {
      return;
    }
    final Path2D path = new Path2D.Double();
    final List<Point> drawnLine = byCircle ? ellipse(line.getFirst(), line.getLast()) : line;
    path.moveTo(drawnLine.get(0).x * zoom, drawnLine.get(0).y * zoom);
    drawnLine.stream().skip(1).forEach(p -> path.lineTo(p.x * zoom, p.y * zoom));
    final Color tint =
        lineCheck.isEmpty()
            ? Color.DARK_GRAY
            : lineCuts ? Theme.getSuccessColor() : Theme.getDangerColor();
    final Color halo = new Color(0, 0, 0, 0xC0);
    // a dark halo under a white line shows on land, sea and the selected (accent) border alike
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    if (eraserWidth > 0) {
      // the band the eraser clears, see-through so the lines under it still show
      final float band = (float) Math.max(2, eraserWidth * zoom);
      g.setColor(halo);
      g.setStroke(new BasicStroke(band + 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.draw(path);
      g.setColor(new Color(0xFF, 0xFF, 0xFF, 0xB0));
      g.setStroke(new BasicStroke(band, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.draw(path);
      return;
    }
    g.setColor(halo);
    g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(path);
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(path);
    g.setColor(tint);
    g.setStroke(
        new BasicStroke(
            4, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[] {10, 6}, 0));
    g.draw(path);
    loose.ifPresent(
        to -> {
          final Line2D segment =
              new Line2D.Double(
                  line.getLast().x * zoom, line.getLast().y * zoom, to.x * zoom, to.y * zoom);
          g.setColor(halo);
          g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          g.draw(segment);
          g.setColor(Color.WHITE);
          g.setStroke(
              new BasicStroke(
                  2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[] {6, 4}, 0));
          g.draw(segment);
        });
    g.setStroke(new BasicStroke(2));
    for (final Point end : byPoints ? line : List.of(line.getFirst(), line.getLast())) {
      final Ellipse2D handle = new Ellipse2D.Double(end.x * zoom - 6, end.y * zoom - 6, 12, 12);
      g.setColor(tint);
      g.fill(handle);
      g.setColor(halo);
      g.draw(handle);
    }
    if (lineCheck.isPresent()) {
      final Point end = shown.get(shown.size() - 1);
      paintBadge(
          g,
          lineCuts ? "Cuts in two" : "Start and end outside " + Objects.toString(selected, "it"),
          (int) (end.x * zoom),
          (int) (end.y * zoom) - 20);
    }
  }

  /** The ellipse between two corners as a closed line, in the map's pixels. */
  static List<Point> ellipse(final Point from, final Point to) {
    final Rectangle2D box = new Rectangle2D.Double();
    box.setFrameFromDiagonal(from, to);
    final List<Point> points = new ArrayList<>();
    final double[] at = new double[6];
    final Ellipse2D shape =
        new Ellipse2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight());
    for (final PathIterator i = shape.getPathIterator(null, 0.5); !i.isDone(); i.next()) {
      if (i.currentSegment(at) != PathIterator.SEG_CLOSE) {
        final Point point = new Point((int) Math.round(at[0]), (int) Math.round(at[1]));
        if (points.isEmpty() || !points.getLast().equals(point)) {
          points.add(point);
        }
      }
    }
    if (!points.getLast().equals(points.getFirst())) {
      points.add(points.getFirst());
    }
    return points;
  }

  /** The corner that makes the box from the first one a square, for a round circle. */
  private static Point squared(final Point from, final Point to) {
    final int side = Math.max(Math.abs(to.x - from.x), Math.abs(to.y - from.y));
    return new Point(
        from.x + (to.x < from.x ? -side : side), from.y + (to.y < from.y ? -side : side));
  }

  /** Where on the screen the last segment, to the pointer, is drawn, with its halo and handle. */
  private Rectangle lastSegment() {
    final Rectangle segment = new Rectangle(onScreen(line.getLast()));
    pointer.ifPresent(p -> segment.add(onScreen(p)));
    segment.grow(LINE_REACH, LINE_REACH);
    return segment;
  }

  private Point onScreen(final Point onMap) {
    return new Point((int) Math.round(onMap.x * zoom), (int) Math.round(onMap.y * zoom));
  }

  /**
   * The decorations while they are edited, else the selected territory's places while they are,
   * the units' in their order first, then the markers the game draws there or the files place.
   */
  private List<Place> places() {
    final List<Place> places = new ArrayList<>();
    if (decorating.isPresent()) {
      project
          .getFiles()
          .places()
          .decorations()
          .forEach(
              (picture, points) -> {
                for (int i = 0; i < points.size(); i++) {
                  places.add(
                      new Place(
                          Optional.empty(), Optional.of(picture), i, points.get(i), "", false));
                }
              });
      return places;
    }
    final boolean editingSelected =
        editingPlaces && selected != null && project.getFiles().polygons().containsKey(selected);
    // the selected territory's own above, not twice
    shownPlaces().stream()
        .filter(place -> !(editingSelected && place.territory().equals(selected)))
        .forEach(places::add);
    if (editingSelected) {
      addPlaces(places, selected, ShownPlaces.SELECTED);
    }
    return places;
  }

  /** The shown places of every territory, worked out once per change: big maps have 700+. */
  private List<Place> shownPlaces() {
    final List<Object> of = List.of(project.game(), project.getFiles(), everywhere);
    if (shownPlacesOf.size() != of.size()
        || shownPlacesOf.get(0) != of.get(0)
        || shownPlacesOf.get(1) != of.get(1)
        || !shownPlacesOf.get(2).equals(everywhere)) {
      final List<Place> places = new ArrayList<>();
      if (!everywhere.equals(ShownPlaces.NONE)) {
        project.getFiles().polygons().keySet().forEach(t -> addPlaces(places, t, everywhere));
      }
      shownPlaces = List.copyOf(places);
      shownPlacesOf = of;
    }
    return shownPlaces;
  }

  /** The territory's places of these kinds: units in their order, its center, the markers. */
  private void addPlaces(final List<Place> places, final String territory, final ShownPlaces of) {
    if (of.shows(PlaceKind.UNITS)) {
      final List<Point> units = unitPlacesOf(territory);
      for (int i = 0; i < units.size(); i++) {
        places.add(
            new Place(Optional.empty(), Optional.empty(), i, units.get(i), territory, false));
      }
    }
    if (of.shows(PlaceKind.CENTERS)) {
      places.add(
          new Place(Optional.empty(), Optional.empty(), 0, center(territory), territory, true));
    }
    final Game game = project.game();
    final MapFiles files = project.getFiles();
    for (final Marker marker : Marker.values()) {
      // a save keeps no other
      if (of.shows(marker)
          && (marker.isOn(game, territory)
              || (marker.keepsUnused()
                  && files.places().markers(marker).containsKey(territory)))) {
        final List<Point> points = files.markerPlaces(game, marker, territory);
        for (int i = 0; i < marker.count(game, territory); i++) {
          places.add(
              new Place(
                  Optional.of(marker),
                  Optional.empty(),
                  i,
                  points.get(Math.min(i, points.size() - 1)),
                  territory,
                  false));
        }
      }
    }
  }

  /**
   * The place on the screen, moved while it is dragged: a unit's square, a marker's dot or a
   * decoration's picture.
   */
  private Rectangle onScreen(final Place place) {
    final Point by = place.equals(dragged) ? draggedBy : new Point();
    final int x = (int) (place.at().x * zoom) + by.x;
    final int y = (int) (place.at().y * zoom) + by.y;
    if (place.marker().isPresent() || place.center()) {
      return new Rectangle(x - PLACE_DOT, y - PLACE_DOT, 2 * PLACE_DOT, 2 * PLACE_DOT);
    }
    final Dimension size = place.decoration().map(this::decorationSize).orElse(unitSize);
    return new Rectangle(
        x, y, (int) Math.round(size.width * zoom), (int) Math.round(size.height * zoom));
  }

  /** The size of the decoration's picture on the map; a small square for one that is missing. */
  private Dimension decorationSize(final String picture) {
    return decorationPicture(picture)
        .map(image -> new Dimension(image.getWidth(null), image.getHeight(null)))
        .orElse(new Dimension(MISSING_DECORATION, MISSING_DECORATION));
  }

  private Optional<Image> decorationPicture(final String picture) {
    return project
        .getFiles()
        .miscFolder()
        .flatMap(
            misc -> decorationPictures.computeIfAbsent(misc.resolve(picture), MapCanvas::read));
  }

  /** How the marker or center is called, with its number for effects, and its territory. */
  private static String label(final Place place) {
    final String name =
        place
            .marker()
            .map(m -> m == Marker.EFFECT ? m.getLabel() + " " + (place.index() + 1) : m.getLabel())
            .orElse("Center");
    return name + " · " + place.territory();
  }

  /** The place at the point on the screen, the markers above the units. */
  private Optional<Place> placeAt(final Point point) {
    final List<Place> places = places();
    for (int i = places.size() - 1; i >= 0; i--) {
      if (onScreen(places.get(i)).contains(point)) {
        return Optional.of(places.get(i));
      }
    }
    return Optional.empty();
  }

  /** Moves the place by so many pixels of the screen, as one step. */
  private void move(final Place place, final Point by) {
    final String territory = place.territory();
    final Point to =
        new Point(
            place.at().x + (int) Math.round(by.x / zoom),
            place.at().y + (int) Math.round(by.y / zoom));
    if (place.decoration().isPresent()) {
      final String picture = place.decoration().get();
      final List<Point> moved = new ArrayList<>(decorations(picture));
      moved.set(place.index(), to);
      project.setDecorations(picture, moved);
    } else if (place.center()) {
      project.setCenter(territory, to);
    } else if (place.marker().isPresent()) {
      if (!refusesPlaces(place.marker().get().getFile())) {
        project.setMarkerPlace(place.marker().get(), territory, place.index(), to);
      }
    } else if (!refusesPlaces(MapFiles.PLACEMENTS)) {
      final List<Point> moved = new ArrayList<>(unitPlacesOf(territory));
      moved.set(place.index(), to);
      project.setUnitPlaces(territory, moved);
    }
  }

  /**
   * Whether places of this file stay as they are because the file does not read ({@link
   * MapProject#whyNoPlaces}); says so then, with the way to Files.
   */
  private boolean refusesPlaces(final String fileName) {
    final Optional<String> why = project.whyNoPlaces(fileName);
    why.ifPresent(
        text ->
            Dialogs.toast(
                this, text, "Files", () -> MapMakerWindow.openTool(this, "Files", null)));
    return why.isPresent();
  }

  /** A place for units after the others, centered at the point on the screen. */
  private void addUnitPlace(final Point point) {
    if (refusesPlaces(MapFiles.PLACEMENTS)) {
      return;
    }
    final String territory = Objects.requireNonNull(selected);
    final List<Point> added = new ArrayList<>(unitPlacesOf(territory));
    added.add(
        new Point(
            (int) Math.round(point.x / zoom) - unitSize.width / 2,
            (int) Math.round(point.y / zoom) - unitSize.height / 2));
    project.setUnitPlaces(territory, added);
  }

  /** Removes a place for units or a decoration; markers and centers stay. */
  private void remove(final Place place) {
    if (place.decoration().isPresent()) {
      final List<Point> removed = new ArrayList<>(decorations(place.decoration().get()));
      removed.remove(place.index());
      project.setDecorations(place.decoration().get(), removed);
    } else if (place.marker().isEmpty() && !place.center()) {
      removeUnitPlace(place.territory(), place.index());
    }
  }

  private List<Point> decorations(final String picture) {
    return project.getFiles().places().decorations().getOrDefault(picture, List.of());
  }

  /** The picture being placed once more, centered at the point on the screen. */
  private void addDecoration(final String picture, final Point point) {
    final Dimension size = decorationSize(picture);
    final List<Point> added = new ArrayList<>(decorations(picture));
    added.add(
        new Point(
            (int) Math.round(point.x / zoom) - size.width / 2,
            (int) Math.round(point.y / zoom) - size.height / 2));
    project.setDecorations(picture, added);
  }

  /** Removes the place for units; the last one stays, as the game needs one. */
  private void removeUnitPlace(final String territory, final int index) {
    if (refusesPlaces(MapFiles.PLACEMENTS)) {
      return;
    }
    final List<Point> removed = new ArrayList<>(unitPlacesOf(territory));
    removed.remove(index);
    project.setUnitPlaces(territory, removed);
  }

  /** Whether a unit place this big on screen shows its number; its tool tip always has it. */
  static boolean isNumbered(final Rectangle onScreen) {
    return onScreen.width >= NUMBERED_PLACE;
  }

  /** A unit's square with its number in the order, or a marker's dot with what it marks. */
  private void paintPlace(final Graphics2D g, final Place place) {
    final Rectangle shown = onScreen(place);
    final Color accent = Theme.getAccentColor();
    g.setStroke(new BasicStroke(2));
    if (place.decoration().isPresent()) {
      // the picture itself is drawn with the map
      g.setColor(accent);
      g.draw(shown);
    } else if (place.marker().isEmpty() && !place.center()) {
      g.setColor(withAlpha(Color.WHITE, 0x66));
      g.fill(shown);
      g.setColor(accent);
      g.draw(shown);
      // in the middle: markers often sit at a square's corner
      if (isNumbered(shown)) {
        paintBadge(
            g,
            String.valueOf(place.index() + 1),
            (int) shown.getCenterX(),
            (int) shown.getCenterY());
      }
    } else {
      // a letter on the point, the whole name while the mouse is over it
      final boolean hover = hovered.map(shown::contains).orElse(false);
      final String text =
          hover
              ? label(place)
              : place
                  .marker()
                  .map(m -> m.getLetter() + (m == Marker.EFFECT ? place.index() + 1 : ""))
                  .orElse("+");
      g.setFont(getFont().deriveFont(Font.BOLD, 11f));
      final FontMetrics metrics = g.getFontMetrics();
      final int width = Math.max(shown.width, metrics.stringWidth(text) + 8);
      final int x = (int) shown.getCenterX() - width / 2;
      g.setColor(accent);
      g.fillRoundRect(x, shown.y, width, shown.height, shown.height, shown.height);
      g.setColor(Color.WHITE);
      g.drawRoundRect(x, shown.y, width, shown.height, shown.height, shown.height);
      g.drawString(
          text,
          x + (width - metrics.stringWidth(text)) / 2,
          shown.y + (shown.height + metrics.getAscent() - metrics.getDescent()) / 2);
    }
  }

  /** One square of the screen's colors and outlines, at the square's place in the map. */
  private BufferedImage newLayerSquare(final Point square, final double screenScale) {
    final int pixels = (int) Math.ceil(LAYER_SIZE * screenScale);
    final BufferedImage drawn = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = drawn.createGraphics();
    g.setColor(MAP_BACKGROUND);
    g.fillRect(0, 0, pixels, pixels);
    g.scale(screenScale, screenScale);
    g.setClip(0, 0, LAYER_SIZE, LAYER_SIZE);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.translate(-square.x * LAYER_SIZE, -square.y * LAYER_SIZE);
    g.scale(zoom, zoom);
    final boolean picture = showPicture && paintPicture(g);
    if (picture && project.getFiles().showsRelief()) {
      paintRelief(g);
    }
    final Map<String, List<Polygon>> polygons = project.getFiles().polygons();
    shownIn(g.getClipBounds())
        .forEach(territory -> paintTerritory(g, territory, polygons.get(territory), picture));
    if (picture) {
      paintDecorations(g);
    }
    if (mode == MapMode.CONNECTIONS) {
      paintConnections(g, project.game());
    }
    g.dispose();
    return drawn;
  }

  /** The misc pictures that decorations.txt places, as the game draws them over the map. */
  private void paintDecorations(final Graphics2D g) {
    final Rectangle visible = g.getClipBounds();
    project
        .getFiles()
        .places()
        .decorations()
        .forEach(
            (picture, points) ->
                decorationPicture(picture)
                    .ifPresent(
                        image -> {
                          for (final Point at : points) {
                            if (visible.intersects(
                                at.x, at.y, image.getWidth(null), image.getHeight(null))) {
                              g.drawImage(image, at.x, at.y, null);
                            }
                          }
                        }));
  }

  /** The territories in this part of the map or so close that a selected one's outline shows. */
  private List<String> shownIn(final Rectangle area) {
    final int margin = (int) Math.ceil(8 / zoom);
    final Rectangle around =
        new Rectangle(
            area.x - margin, area.y - margin, area.width + 2 * margin, area.height + 2 * margin);
    return project.getFiles().polygons().keySet().stream()
        .filter(t -> boundsOf(t).intersects(around))
        .toList();
  }

  /** Starts again what follows the map's files when they changed, eg after a rename. */
  private void followFiles() {
    if (project.getFiles() != filesSeen) {
      filesSeen = project.getFiles();
      bounds.clear();
      filled.clear();
      unitPlaces.clear();
      unitSize = filesSeen.unitSize();
      unitPictures.clear();
      // a relief loaded, saved or undone: the tiles on disk may be new
      if (!filesSeen.relief().equals(reliefSeen)) {
        reliefSeen = filesSeen.relief();
        reliefTiles.clear();
      }
    }
  }

  private Rectangle boundsOf(final String territory) {
    return bounds.computeIfAbsent(
        territory,
        t ->
            project.getFiles().polygons().getOrDefault(t, List.of()).stream()
                .map(Polygon::getBounds)
                .reduce(Rectangle::union)
                .orElseGet(Rectangle::new));
  }

  private Look look(final String territory) {
    return looks.computeIfAbsent(
        territory,
        t -> {
          final Game game = project.game();
          return new Look(
              MapXml.isWater(game, t),
              project.isImpassable(t),
              MapXml.owner(game, t).orElse(null),
              values(game, t),
              MapXml.units(game, t),
              project.isChanged(t),
              center(t));
        });
  }

  /**
   * The map's own picture below the colors, loaded or in tiles; false when the map has none. At a
   * small zoom a smaller copy, made once in the background.
   */
  private boolean paintPicture(final Graphics2D g) {
    final MapFiles files = project.getFiles();
    final Optional<BufferedImage> loaded = files.picture();
    if (loaded.isEmpty() && files.tile(0, 0).isEmpty()) {
      return false;
    }
    if (zoom <= OVERVIEW_ZOOM) {
      final Dimension map = files.size();
      overview().ifPresent(small -> g.drawImage(small, 0, 0, map.width, map.height, null));
      return true;
    }
    if (loaded.isPresent()) {
      g.drawImage(loaded.get(), 0, 0, null);
      return true;
    }
    return paintTiles(g, this::tile);
  }

  /**
   * The relief over the picture, loaded or in tiles, as the game draws it; at a small zoom the
   * smaller copy of the picture has it already.
   */
  private void paintRelief(final Graphics2D g) {
    if (zoom <= OVERVIEW_ZOOM) {
      return;
    }
    final Optional<BufferedImage> loaded = project.getFiles().relief();
    if (loaded.isPresent()) {
      g.drawImage(loaded.get(), 0, 0, null);
    } else {
      paintTiles(g, this::reliefTile);
    }
  }

  /** The tiles in the visible part; false when there is none. */
  private static boolean paintTiles(
      final Graphics2D g, final BiFunction<Integer, Integer, Optional<Image>> tileAt) {
    final int size = MapFiles.tileSize();
    final Rectangle visible = g.getClipBounds();
    boolean any = false;
    for (int column = Math.max(0, visible.x / size);
        column <= (visible.x + visible.width) / size;
        column++) {
      for (int row = Math.max(0, visible.y / size);
          row <= (visible.y + visible.height) / size;
          row++) {
        final Optional<Image> tile = tileAt.apply(column, row);
        if (tile.isPresent()) {
          g.drawImage(tile.get(), column * size, row * size, null);
          any = true;
        }
      }
    }
    return any;
  }

  /** The smaller picture once it is made; starts making it for a new picture or folder. */
  private Optional<BufferedImage> overview() {
    final MapFiles files = project.getFiles();
    final List<Object> source =
        List.of(
            files.picture(),
            files.folder(),
            files.relief(),
            files.showsRelief(),
            // lines drawn into a big picture itself leave it the same picture
            project.pictureEdits());
    if (!source.equals(overviewOf)) {
      overviewOf = source;
      overview = null;
      new SwingWorker<BufferedImage, Void>() {
        @Override
        protected BufferedImage doInBackground() {
          return newOverview(files);
        }

        @Override
        protected void done() {
          try {
            if (source.equals(overviewOf)) {
              overview = get();
              repaint();
            }
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (final ExecutionException e) {
            // without it a small zoom shows the colors alone
            log.info("Could not make the small picture of the map", e);
          }
        }
      }.execute();
    }
    return Optional.ofNullable(overview);
  }

  /** The picture or the tiles drawn smaller, at most {@link #OVERVIEW_WIDTH} wide. */
  private static BufferedImage newOverview(final MapFiles files) {
    final Dimension map = files.size();
    final double scale = Math.min(OVERVIEW_ZOOM, OVERVIEW_WIDTH / (double) Math.max(1, map.width));
    final BufferedImage small =
        new BufferedImage(
            Math.max(1, (int) (map.width * scale)),
            Math.max(1, (int) (map.height * scale)),
            BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = small.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.scale(scale, scale);
    if (files.picture().isPresent()) {
      g.drawImage(files.picture().get(), 0, 0, null);
    } else {
      final int size = MapFiles.tileSize();
      for (int column = 0; column * size < map.width; column++) {
        for (int row = 0; row * size < map.height; row++) {
          final int x = column * size;
          final int y = row * size;
          files
              .tile(column, row)
              .flatMap(MapCanvas::read)
              .ifPresent(t -> g.drawImage(t, x, y, null));
        }
      }
    }
    if (files.showsRelief()) {
      if (files.relief().isPresent()) {
        g.drawImage(files.relief().get(), 0, 0, null);
      } else {
        final int size = MapFiles.tileSize();
        for (int column = 0; column * size < map.width; column++) {
          for (int row = 0; row * size < map.height; row++) {
            final int x = column * size;
            final int y = row * size;
            files
                .reliefTile(column, row)
                .flatMap(MapCanvas::read)
                .ifPresent(t -> g.drawImage(t, x, y, null));
          }
        }
      }
    }
    g.dispose();
    return small;
  }

  private Optional<Image> tile(final int column, final int row) {
    return tiles.computeIfAbsent(
        new Point(column, row), at -> project.getFiles().tile(at.x, at.y).flatMap(MapCanvas::read));
  }

  private Optional<Image> reliefTile(final int column, final int row) {
    return reliefTiles.computeIfAbsent(
        new Point(column, row),
        at -> project.getFiles().reliefTile(at.x, at.y).flatMap(MapCanvas::read));
  }

  private static Optional<Image> read(final Path file) {
    try {
      return Optional.ofNullable(ImageIO.read(file.toFile()));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private void paintTerritory(
      final Graphics2D g,
      final String territory,
      final List<Polygon> shapes,
      final boolean overPicture) {
    final Look look = look(territory);
    // without the territories inside it, eg a sea zone's islands, which the fill would cover
    final Shape area = filled.computeIfAbsent(territory, project.getFiles()::filled);
    // the game view as the game draws it: the owner's color thin over the picture, seas clear
    final boolean asPlayed = mode == MapMode.GAME && overPicture;
    // the sides in solid colors, as in the legend; the picture only just shows through
    final boolean political = mode == MapMode.POLITICAL;
    if (!(asPlayed && look.water())) {
      final Color fill = fill(look);
      g.setColor(overPicture ? withAlpha(fill, asPlayed ? 0x59 : political ? 0xE0 : 0x8C) : fill);
      g.fill(area);
    }
    if (showingTextures) {
      if (textureFills == null) {
        textureFills = Textures.fills(project.getFiles());
      }
      final Textures.Fill texture = textureFills.get(territory);
      if (texture != null) {
        // as strong as the relief draws it at its usual strength
        final Composite before = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(ReliefMaker.TEXTURE_ALPHA));
        g.setPaint(texture.paint());
        g.fill(area);
        g.setComposite(before);
      }
    }
    final boolean isMarked = marked.contains(territory);
    if (isMarked) {
      // sea zones filled, land hatched, so the two read apart
      final Color mark = canalMarks ? MARKED : Theme.getAccentColor();
      if (canalMarks || look.water()) {
        g.setColor(withAlpha(mark, look.water() ? (canalMarks ? 0x66 : 0x59) : 0x33));
        g.fill(area);
      }
      if (!look.water()) {
        g.setPaint(hatch(8 / zoom, mark));
        g.fill(area);
      }
    }
    final boolean isFaint = !isMarked && faintlyMarked.contains(territory);
    if (isFaint) {
      g.setColor(withAlpha(MARKED, 0x33));
      g.fill(area);
      g.setColor(withAlpha(MARKED, 0xB0));
      g.setStroke(new BasicStroke((float) (2 / zoom)));
      shapes.forEach(g::draw);
    }
    if (neighbours.contains(territory)) {
      g.setColor(withAlpha(Theme.getAccentColor(), 0x40));
      g.fill(area);
    }
    final boolean isSelected = territory.equals(selected) || isMarked;
    // a territory with a problem stands out in red, as the legend says
    final boolean problem = !isSelected && problemTerritories().contains(territory);
    // over a picture its own lines are the borders: a thin outline beside them only doubles them
    if (isMarked && canalMarks) {
      // on a white halo, so it reads on any picture
      g.setColor(Color.WHITE);
      g.setStroke(new BasicStroke((float) (7 / zoom)));
      shapes.forEach(g::draw);
    }
    if (isSelected || problem || !overPicture || political) {
      g.setColor(
          isMarked && canalMarks
              ? MARKED
              : isSelected ? Theme.getAccentColor() : problem ? PROBLEM : OUTLINE);
      g.setStroke(new BasicStroke((float) ((isSelected ? 4 : problem ? 3 : 1) / zoom)));
      shapes.forEach(g::draw);
    }
  }

  private Color fill(final Look look) {
    if (look.water()) {
      return SEA;
    }
    // as TripleA fills land: impassable in a color of its own, without owner as "Neutral"
    final Color owner =
        look.impassable()
            ? project.impassableColor()
            : Optional.ofNullable(look.owner())
                .map(n -> previewColors.getOrDefault(n, project.nationColor(n)))
                .orElseGet(project::neutralColor);
    return switch (mode) {
      case GAME, OWNERS -> owner;
      case POLITICAL -> {
        if (allianceColors == null) {
          allianceColors = allianceColors(project.game());
        }
        yield Optional.ofNullable(look.owner())
            .map(n -> allianceColors.getOrDefault(n, NEUTRAL))
            .orElse(NEUTRAL);
      }
      case VALUES, UNITS, CONNECTIONS -> blend(owner, NEUTRAL);
    };
  }

  /** The alliance's hue, by its place in the game's alliances. */
  static Color allianceColor(final int alliance) {
    return ALLIANCE_COLORS.get(alliance % ALLIANCE_COLORS.size());
  }

  /**
   * Each nation in an alliance in a shade of its hue, from darker to lighter in the nations'
   * order; a nation in several alliances in its first one's.
   */
  static Map<String, Color> allianceColors(final Game game) {
    final Map<String, Color> colors = new HashMap<>();
    final List<List<String>> members = List.copyOf(allianceNations(game).values());
    for (int i = 0; i < members.size(); i++) {
      final List<String> nations = members.get(i);
      for (int k = 0; k < nations.size(); k++) {
        final double shade = nations.size() == 1 ? 0 : -0.3 + 0.6 * k / (nations.size() - 1);
        colors.put(nations.get(k), shade(allianceColor(i), shade));
      }
    }
    return Map.copyOf(colors);
  }

  /**
   * The game's alliances in order, each with the nations whose first alliance it is; without the
   * alliances no nation has first, as they color nothing.
   */
  static Map<String, List<String>> allianceNations(final Game game) {
    final Map<String, List<String>> nations = new LinkedHashMap<>();
    MapXml.allAlliances(game).forEach(alliance -> nations.put(alliance, new ArrayList<>()));
    for (final String nation : MapXml.nations(game)) {
      MapXml.alliances(game, nation).stream()
          .findFirst()
          .ifPresent(alliance -> nations.get(alliance).add(nation));
    }
    nations.values().removeIf(List::isEmpty);
    return nations;
  }

  /** The color towards black (below 0) or white (above 0) by this much. */
  private static Color shade(final Color color, final double by) {
    final int toward = by < 0 ? 0 : 255;
    final double part = Math.abs(by);
    return new Color(
        (int) Math.round(color.getRed() + (toward - color.getRed()) * part),
        (int) Math.round(color.getGreen() + (toward - color.getGreen()) * part),
        (int) Math.round(color.getBlue() + (toward - color.getBlue()) * part));
  }

  private static Color blend(final Color color, final Color other) {
    return new Color(
        (color.getRed() + other.getRed() * 2) / 3,
        (color.getGreen() + other.getGreen() * 2) / 3,
        (color.getBlue() + other.getBlue() * 2) / 3);
  }

  private static Color withAlpha(final Color color, final int alpha) {
    return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
  }

  /**
   * Each connection as a line between centers; with a territory selected its lines stand out above
   * the others, which fade.
   */
  private void paintConnections(final Graphics2D g, final Game game) {
    final MapFiles files = project.getFiles();
    final Dimension map = files.size();
    final Dimension wrap =
        new Dimension(files.wrapsX() ? map.width : 0, files.wrapsY() ? map.height : 0);
    final List<List<Line2D>> ofSelected = new ArrayList<>();
    for (final Connection connection : MapXml.connections(game)) {
      final List<Line2D> lines =
          connectionLines(
              center(connection.getT1()), center(connection.getT2()), map, wrap);
      if (selected == null) {
        // the overview's lines thick enough not to be missed
        g.setColor(Color.WHITE);
        drawConnection(g, lines, 6, BasicStroke.CAP_SQUARE);
        g.setColor(Color.DARK_GRAY);
        drawConnection(g, lines, 4, BasicStroke.CAP_SQUARE);
      } else if (connection.getT1().equals(selected) || connection.getT2().equals(selected)) {
        ofSelected.add(lines);
      } else {
        g.setColor(withAlpha(Color.DARK_GRAY, 0x66));
        drawConnection(g, lines, 1.5, BasicStroke.CAP_SQUARE);
      }
    }
    // the lines being edited in red, the color that draws the eye
    for (final List<Line2D> lines : ofSelected) {
      g.setColor(new Color(0, 0, 0, 0xC0));
      drawConnection(g, lines, 10, BasicStroke.CAP_ROUND);
      g.setColor(Theme.getDangerColor());
      drawConnection(g, lines, 5, BasicStroke.CAP_ROUND);
    }
  }

  // a connection that goes on at the other side of the map is dashed, the same dashes for every
  // width, so a line drawn over a wider one covers it
  private void drawConnection(
      final Graphics2D g, final List<Line2D> lines, final double width, final int cap) {
    g.setStroke(
        lines.size() == 1
            ? new BasicStroke((float) (width / zoom), cap, BasicStroke.JOIN_ROUND)
            : new BasicStroke(
                (float) (width / zoom),
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND,
                10,
                new float[] {(float) (14 / zoom), (float) (10 / zoom)},
                0));
    lines.forEach(g::draw);
  }

  /**
   * The lines that show a connection between two centers: one between them, or, on a map that
   * goes on at the other side (map.scrollWrapX, map.scrollWrapY) where that way is the shorter
   * one, two, from each center to the edge where the line leaves the map.
   *
   * @param wrap after how many pixels the map comes again, across and down; 0 where it does
   *     not go on
   */
  static List<Line2D> connectionLines(
      final Point from, final Point to, final Dimension map, final Dimension wrap) {
    final int dx = to.x - from.x;
    final int dy = to.y - from.y;
    // where the other center is when the map is laid beside itself, if that is nearer
    final int shiftX =
        wrap.width > 0 && 2 * Math.abs(dx) > wrap.width ? -Integer.signum(dx) * wrap.width : 0;
    final int shiftY =
        wrap.height > 0 && 2 * Math.abs(dy) > wrap.height
            ? -Integer.signum(dy) * wrap.height
            : 0;
    if (shiftX == 0 && shiftY == 0) {
      return List.of(new Line2D.Double(from, to));
    }
    return List.of(
        toEdge(from, to.x + shiftX, to.y + shiftY, map),
        toEdge(to, from.x - shiftX, from.y - shiftY, map));
  }

  // how much of the way from a coordinate on the map to one maybe off it is on the map
  private static double shareInside(final int from, final int to, final int size) {
    return to < 0
        ? from / (double) (from - to)
        : to > size ? (size - from) / (double) (to - from) : 1;
  }

  /** The line from a point on the map towards one off it, as far as the map's edge. */
  private static Line2D toEdge(final Point from, final int x, final int y, final Dimension map) {
    final double until =
        Math.min(shareInside(from.x, x, map.width), shareInside(from.y, y, map.height));
    return new Line2D.Double(
        from.x, from.y, from.x + (x - from.x) * until, from.y + (y - from.y) * until);
  }

  /**
   * The territory's value in a badge, or its units: their pictures where the game draws them, or in
   * the Units view when they would be too small to see, how many in a badge. A dot marks a change
   * not saved yet.
   */
  private void paintBadges(final Graphics2D g, final String territory) {
    final Look look = look(territory);
    final int x = (int) (look.center().x * zoom);
    final int y = (int) (look.center().y * zoom);
    // the map's unit size, as its place.txt is laid out for units of that size
    final int size = (int) Math.round(unitSize.width * zoom);
    final boolean showsUnits = mode == MapMode.GAME || mode == MapMode.UNITS;
    if (showsUnits && size >= MIN_UNIT_SIZE) {
      paintUnits(g, territory, look.units(), size);
    } else {
      final String text =
          switch (mode) {
            case VALUES -> look.values();
            case UNITS ->
                Optional.of(look.units().stream().mapToInt(UnitPlacement::getQuantity).sum())
                    .filter(count -> count > 0)
                    .map(count -> count + " units")
                    .orElse("");
            case GAME, OWNERS, POLITICAL, CONNECTIONS -> "";
          };
      paintBadge(g, text, x, y);
    }
    if (look.changed()) {
      g.setColor(Theme.getAccentColor());
      g.fillOval(x - 4, y + 10, 8, 8);
    }
  }

  private void paintBadge(final Graphics2D g, final String text, final int x, final int y) {
    if (!text.isEmpty()) {
      g.setFont(getFont().deriveFont(Font.BOLD, 12f));
      final FontMetrics metrics = g.getFontMetrics();
      final int width = metrics.stringWidth(text) + 12;
      final int height = metrics.getHeight();
      g.setColor(BADGE);
      g.fillRoundRect(x - width / 2, y - height / 2, width, height, height, height);
      g.setColor(BADGE_TEXT);
      g.drawString(text, x - width / 2 + 6, y - height / 2 + metrics.getAscent());
    }
  }

  /**
   * The territory's effects as the game draws them: the n-th picture from its top left corner at
   * the n-th effect place, scaled like units but at least {@link #MIN_EFFECT_ICON}; without a
   * picture a badge with the effect's first letter.
   */
  private void paintEffects(final Graphics2D g, final String territory, final Font letters) {
    final List<String> named = effects().getOrDefault(territory, List.of());
    if (named.isEmpty()) {
      return;
    }
    final List<Point> points =
        effectPlaces.computeIfAbsent(
            territory, t -> project.getFiles().effectPlaces(t, named.size()));
    for (int i = 0; i < named.size(); i++) {
      final String effect = named.get(i);
      final Point at = points.get(Math.min(i, points.size() - 1));
      final int x = (int) (at.x * zoom);
      final int y = (int) (at.y * zoom);
      final Optional<Image> picture = effectPicture(effect);
      final Dimension size =
          picture
              .map(p -> effectSize(p.getWidth(null), p.getHeight(null)))
              .orElse(new Dimension(EFFECT_BADGE, EFFECT_BADGE));
      if (standingOut.equals(Optional.of(effect))) {
        g.setColor(withAlpha(Color.WHITE, 0xD0));
        g.fillRoundRect(x - 3, y - 3, size.width + 6, size.height + 6, 8, 8);
        g.setColor(Theme.getAccentColor());
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(x - 3, y - 3, size.width + 6, size.height + 6, 8, 8);
      }
      if (picture.isPresent()) {
        g.drawImage(picture.get(), x, y, size.width, size.height, null);
      } else {
        g.setColor(BADGE);
        g.fillOval(x, y, size.width, size.height);
        final String letter = effect.substring(0, 1).toUpperCase(Locale.ENGLISH);
        g.setFont(letters);
        final FontMetrics metrics = g.getFontMetrics();
        g.setColor(BADGE_TEXT);
        g.drawString(
            letter,
            x + (size.width - metrics.stringWidth(letter)) / 2,
            y + (size.height + metrics.getAscent() - metrics.getDescent()) / 2);
      }
    }
  }

  private Dimension effectSize(final int width, final int height) {
    final double scale =
        Math.max(zoom, MIN_EFFECT_ICON / (double) Math.max(1, Math.max(width, height)));
    return new Dimension((int) Math.round(width * scale), (int) Math.round(height * scale));
  }

  private Map<String, List<String>> effects() {
    if (effects == null) {
      effects = MapXml.territoryEffects(project.game());
    }
    return effects;
  }

  /** The effect's picture as the game finds it: territoryEffects/name_large.png, else name.png. */
  private Optional<Image> effectPicture(final String effect) {
    return effectPictures.computeIfAbsent(
        effect,
        e ->
            project
                .getFiles()
                .folder()
                .map(folder -> folder.resolve("territoryEffects"))
                .flatMap(
                    pictures ->
                        read(pictures.resolve(e + "_large.png"))
                            .or(() -> read(pictures.resolve(e + ".png")))));
  }

  /** The territories a paint stroke has passed so far, filled as they will be painted. */
  private void paintStroke(final Graphics2D g) {
    final AffineTransform toScreen = AffineTransform.getScaleInstance(zoom, zoom);
    for (final String territory : stroke) {
      for (final Polygon shape : project.getFiles().polygons().getOrDefault(territory, List.of())) {
        final Shape onScreen = toScreen.createTransformedShape(shape);
        g.setColor(withAlpha(Theme.getAccentColor(), 0x59));
        g.fill(onScreen);
        g.setColor(Theme.getAccentColor());
        g.setStroke(new BasicStroke(2));
        g.draw(onScreen);
      }
    }
  }

  /** Adds the territories along the segment, in screen px, to the stroke; repaints only them. */
  private void strokeAlong(final Point from, final Point to) {
    final int steps = Math.max(1, (int) Math.ceil(from.distance(to) / STROKE_STEP));
    for (int i = 0; i <= steps; i++) {
      final Point at =
          new Point(
              from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps);
      territoryAt(at)
          .filter(stroke::add)
          .ifPresent(
              added -> {
                final Rectangle bounds = boundsOf(added);
                repaint(
                    (int) (bounds.x * zoom) - 2,
                    (int) (bounds.y * zoom) - 2,
                    (int) Math.ceil(bounds.width * zoom) + 4,
                    (int) Math.ceil(bounds.height * zoom) + 4);
              });
    }
  }

  /**
   * Each stack of units, its picture at one of the territory's places and how many beside it, as
   * the game draws them; stacks beyond the places go on to the right of the last.
   */
  private void paintUnits(
      final Graphics2D g, final String territory, final List<UnitPlacement> units, final int size) {
    if (units.isEmpty()) {
      return;
    }
    final List<Point> places = unitPlacesOf(territory);
    g.setFont(getFont().deriveFont(Font.BOLD, 11f));
    final FontMetrics metrics = g.getFontMetrics();
    for (int i = 0; i < units.size(); i++) {
      final UnitPlacement stack = units.get(i);
      final Point place = places.get(Math.min(i, places.size() - 1));
      final int x = (int) (place.x * zoom) + Math.max(0, i - places.size() + 1) * size;
      final int y = (int) (place.y * zoom);
      unitPicture(stack.getOwner(), stack.getUnitType())
          .ifPresentOrElse(
              picture -> g.drawImage(picture, x, y, size, size, null),
              () -> paintBadge(g, stack.getUnitType(), x + size / 2, y + size / 2));
      if (stack.getQuantity() > 1) {
        final String count = String.valueOf(stack.getQuantity());
        final int textX = x + size - metrics.stringWidth(count) / 2;
        final int textY = y + size;
        g.setColor(Color.BLACK);
        g.drawString(count, textX + 1, textY + 1);
        g.setColor(Color.WHITE);
        g.drawString(count, textX, textY);
      }
    }
  }

  /** Where the game draws the territory's units, {@link MapFiles#unitPlaces}, once per change. */
  private List<Point> unitPlacesOf(final String territory) {
    return unitPlaces.computeIfAbsent(territory, project.getFiles()::unitPlaces);
  }

  private Optional<Image> unitPicture(@Nullable final String owner, final String unitType) {
    final String nation = Optional.ofNullable(owner).orElse(Constants.PLAYER_NAME_NEUTRAL);
    return unitPictures.computeIfAbsent(
        nation + '/' + unitType,
        key ->
            project.unitPicture(
                nation, owner == null ? NEUTRAL : project.nationColor(nation), unitType));
  }

  private static String values(final Game game, final String territory) {
    final StringBuilder text = new StringBuilder();
    final int production = MapXml.production(game, territory);
    if (production > 0) {
      text.append(production);
    }
    if (MapXml.isCapital(game, territory)) {
      text.append(text.isEmpty() ? "" : " ").append('★');
    }
    if (MapXml.isVictoryCity(game, territory)) {
      text.append(text.isEmpty() ? "" : " ").append("VC");
    }
    return text.toString();
  }

  /** The territory's center from centers.txt, else the middle of its first outline. */
  private Point center(final String territory) {
    return Optional.ofNullable(project.getFiles().centers().get(territory))
        .or(
            () ->
                Optional.ofNullable(project.getFiles().polygons().get(territory))
                    .filter(shapes -> !shapes.isEmpty())
                    .map(shapes -> shapes.get(0).getBounds())
                    .map(b -> new Point((int) b.getCenterX(), (int) b.getCenterY())))
        .orElseGet(Point::new);
  }

  /** Zooms in or out by the factor around the middle of the view, as the zoom buttons do. */
  void zoomBy(final double factor) {
    final Rectangle shown = getVisibleRect();
    zoomAt(new Point((int) shown.getCenterX(), (int) shown.getCenterY()), factor);
  }

  /** The zoom, 1 for the map's own size; a "zoom" property change tells each new one. */
  double getZoom() {
    return zoom;
  }

  private void zoomAt(final Point at, final double factor) {
    hovered = Optional.empty();
    final double before = zoom;
    final double newZoom = Math.clamp(zoom * factor, 0.1, 3.0);
    if (getParent() instanceof JViewport viewport) {
      final Point view = viewport.getViewPosition();
      final double mapX = at.x / zoom;
      final double mapY = at.y / zoom;
      zoom = newZoom;
      // sized now, not at the deferred layout, so the viewport takes the new position unclamped
      setSize(getPreferredSize());
      viewport.setViewPosition(
          new Point(
              Math.max(0, (int) (mapX * zoom) - (at.x - view.x)),
              Math.max(0, (int) (mapY * zoom) - (at.y - view.y))));
    } else {
      zoom = newZoom;
      revalidate();
    }
    repaint();
    firePropertyChange("zoom", before, zoom);
  }

  /** Zooms so that the whole map fits into the view, as the Fit button does. */
  void zoomToFit() {
    zoomToFit(3.0);
  }

  /**
   * Zooms so that the whole map fits into the view, but shows it at most this much bigger than it
   * is: an opened map is fitted at most at 100 %, as a small picture blown up is blurred.
   */
  void zoomToFit(final double most) {
    if (getParent() instanceof JViewport viewport) {
      final Dimension view = viewport.getExtentSize();
      final Dimension map = project.getFiles().size();
      if (map.width > 0 && map.height > 0 && view.width > 0) {
        final double before = zoom;
        zoom =
            Math.clamp(
                Math.min(view.width / (double) map.width, view.height / (double) map.height),
                0.1,
                most);
        revalidate();
        repaint();
        firePropertyChange("zoom", before, zoom);
      }
    }
  }

  private final class Mouse extends MouseAdapter {
    private @Nullable Point dragFrom;
    // the last point of a paint stroke, in pixels of the screen, while the left button paints
    private @Nullable Point strokeAt;
    // where the stroke began, and whether it went farther than a click's slop from there
    private Point strokeFrom = new Point();
    private boolean strokeMoved;
    // the press began a dragged line: once Esc dropped it, the rest of the drag does nothing
    private boolean lineDrag;

    @Override
    public void mousePressed(final MouseEvent e) {
      // any button moves the map, but a place grabbed; a left click without moving picks a
      // territory
      dragFrom = e.getLocationOnScreen();
      dragged = SwingUtilities.isLeftMouseButton(e) ? placeAt(e.getPoint()).orElse(null) : null;
      draggedBy = new Point();
      stroke = new LinkedHashSet<>();
      strokeAt =
          onStroke.isPresent() && SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
      strokeFrom = e.getPoint();
      strokeMoved = false;
      lineDrag = drawingLine.isPresent() && !byPoints && SwingUtilities.isLeftMouseButton(e);
      if (lineDrag) {
        line = new ArrayList<>(List.of(onMap(e.getPoint())));
        lineCuts = false;
      }
    }

    private Point onMap(final Point onScreen) {
      return new Point((int) Math.round(onScreen.x / zoom), (int) Math.round(onScreen.y / zoom));
    }

    @Override
    public void mouseReleased(final MouseEvent e) {
      if (dragged != null && !draggedBy.equals(new Point())) {
        move(dragged, draggedBy);
      }
      // a point on release, not on click: AWT sends no click when the hand shook a pixel
      final boolean shortMove =
          dragFrom != null && dragFrom.distance(e.getLocationOnScreen()) <= CLICK_SLOP;
      dragged = null;
      dragFrom = null;
      lineDrag = false;
      if (strokeAt != null) {
        strokeAt = null;
        final List<String> painted = List.copyOf(stroke);
        stroke = new LinkedHashSet<>();
        repaint();
        // a click paints by mouseClicked, as without a brush it chooses; a stroke that came
        // back to where it began is still a stroke, as AWT sends no click after it
        if (strokeMoved && !painted.isEmpty()) {
          onStroke.ifPresent(paint -> paint.accept(painted));
        }
      } else if (drawingLine.isPresent() && byPoints) {
        if (shortMove && SwingUtilities.isLeftMouseButton(e)) {
          // a double-click's second release ends the line at the point the first one set
          if (e.getClickCount() >= 2) {
            finishLine();
          } else {
            setPoint(onMap(e.getPoint()));
          }
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
      } else if (drawingLine.isPresent()) {
        final List<Point> drawn = List.copyOf(line);
        line = new ArrayList<>();
        lineCheckTimer.stop();
        repaint();
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        if (onBorder.isPresent() && shortMove && SwingUtilities.isLeftMouseButton(e)) {
          // the eraser's click: the whole border there, not a dot of it
          hoverBorder(onMap(e.getPoint()));
          final Optional<MapFiles.SharedBorder> clicked = hoveredBorder;
          hoveredBorder = Optional.empty();
          hoveredPair = Optional.empty();
          hoveredBorderPicture = Optional.empty();
          clicked.ifPresentOrElse(
              onBorder.get(),
              () ->
                  Dialogs.toast(
                      MapCanvas.this,
                      "! No border between two territories here: drag to erase a line."));
        } else if (drawn.size() >= 2 && SwingUtilities.isLeftMouseButton(e)) {
          drawingLine.get().accept(byCircle ? ellipse(drawn.getFirst(), drawn.getLast()) : drawn);
        }
      } else {
        setCursor(Cursor.getDefaultCursor());
      }
    }

    @Override
    public void mouseClicked(final MouseEvent e) {
      if (drawingLine.isPresent() && byPoints) {
        return;
      }
      final Optional<Place> place = placeAt(e.getPoint());
      final Optional<String> territory = territoryAt(e.getPoint());
      if (place.isPresent()) {
        if (SwingUtilities.isRightMouseButton(e)) {
          remove(place.get());
        } else if (SwingUtilities.isLeftMouseButton(e) && decorating.isEmpty()) {
          // places may cover a territory: a click still chooses or paints it
          onClick.accept(place.get().territory());
        }
      } else if (SwingUtilities.isLeftMouseButton(e)) {
        if (decorating.isPresent()) {
          addDecoration(decorating.get(), e.getPoint());
        } else if (editingPlaces && territory.isPresent() && territory.get().equals(selected)) {
          addUnitPlace(e.getPoint());
        } else if (addsUnitPlaces
            && everywhere.shows(PlaceKind.UNITS)
            && territory.isPresent()
            && territory.get().equals(selected)) {
          // the unit places show on every territory: a click in the chosen one adds one there
          addUnitPlace(e.getPoint());
          Dialogs.toast(
              MapCanvas.this,
              "✓ Added a place for units in " + selected,
              "Undo",
              project::undo);
        } else {
          territory.ifPresent(onClick);
        }
      }
    }

    /** A click's point of the line; the second click of a double-click is at the first's. */
    private void setPoint(final Point at) {
      if (line.isEmpty() || !line.getLast().equals(at)) {
        line.add(at);
      }
      pointer = Optional.of(at);
      repaint();
    }

    @Override
    public void mouseExited(final MouseEvent e) {
      hovered = Optional.empty();
    }

    @Override
    public void mouseMoved(final MouseEvent e) {
      hovered = Optional.of(e.getPoint());
      if (onBorder.isPresent()) {
        hoverBorder(onMap(e.getPoint()));
      }
      if (settingPoints()) {
        // only the last segment moves: big maps repaint just its part
        final Rectangle changed = lastSegment();
        pointer = Optional.of(onMap(e.getPoint()));
        changed.add(lastSegment());
        repaint(changed);
      }
      if (editingPlaces || decorating.isPresent() || !everywhere.equals(ShownPlaces.NONE)) {
        final Optional<Place> over = placeAt(e.getPoint());
        setCursor(
            over.isPresent()
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        // a marker shows its whole name while the mouse is over it
        if (!over.equals(hoveredPlace)) {
          hoveredPlace = over;
          repaint();
        }
      }
    }

    @Override
    public void mouseDragged(final MouseEvent e) {
      hovered = Optional.empty();
      if (strokeAt != null) {
        strokeAlong(strokeAt, e.getPoint());
        strokeAt = e.getPoint();
        strokeMoved |= strokeFrom.distance(e.getPoint()) > CLICK_SLOP;
      } else if (!line.isEmpty() && !byPoints && SwingUtilities.isLeftMouseButton(e)) {
        final Point at = onMap(e.getPoint());
        if (byCircle) {
          final Point from = line.getFirst();
          final Point corner = e.isShiftDown() ? squared(from, at) : at;
          line = new ArrayList<>(List.of(from, corner));
          repaint();
        } else if (!at.equals(line.get(line.size() - 1))) {
          line.add(at);
          // the moves of the next LINE_CHECK_MS are checked together, at its end
          if (lineCheck.isPresent() && !lineCheckTimer.isRunning()) {
            lineCheckTimer.start();
          }
          repaint();
        }
      } else if (lineDrag) {
        // Esc dropped the line being dragged: the map stays where it is
        return;
      } else if (dragged != null && dragFrom != null) {
        final Point now = e.getLocationOnScreen();
        draggedBy = new Point(now.x - dragFrom.x, now.y - dragFrom.y);
        repaint();
      } else if (dragFrom != null && getParent() instanceof JViewport viewport) {
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        final Point now = e.getLocationOnScreen();
        final Point view = viewport.getViewPosition();
        final Dimension extent = viewport.getExtentSize();
        viewport.setViewPosition(
            new Point(
                Math.clamp(
                    view.x - (now.x - dragFrom.x), 0, Math.max(0, getWidth() - extent.width)),
                Math.clamp(
                    view.y - (now.y - dragFrom.y), 0, Math.max(0, getHeight() - extent.height))));
        dragFrom = now;
      }
    }

    @Override
    public void mouseWheelMoved(final MouseWheelEvent e) {
      hovered = Optional.empty();
      if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
        zoomAt(e.getPoint(), e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15);
      } else if (getParent() instanceof JViewport viewport
          && viewport.getParent() instanceof JScrollPane scroll) {
        scroll.dispatchEvent(SwingUtilities.convertMouseEvent(MapCanvas.this, e, scroll));
      }
    }
  }
}
