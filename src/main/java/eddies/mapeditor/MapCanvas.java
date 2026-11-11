package eddies.mapeditor;

import games.strategy.triplea.Constants;
import java.awt.BasicStroke;
import java.awt.Color;
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
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
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
  private static final Color OUTLINE = new Color(0, 0, 0, 0x99);
  static final Color PROBLEM = new Color(0xE53935);
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
  // the size of a decoration whose picture is missing, so it can still be moved or removed
  private static final int MISSING_DECORATION = 16;

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
  // these follow the map's files: the territories' bounds, units' places and pictures
  private transient @Nullable MapFiles filesSeen;
  private transient Optional<BufferedImage> reliefSeen = Optional.empty();
  private final transient Map<String, Rectangle> bounds = new HashMap<>();
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
  private transient Consumer<String> onClick = territory -> {};
  // the selected territory's places shown to move, add and remove
  private boolean editingPlaces;
  // the place being dragged and how far, in pixels of the screen
  private transient @Nullable Place dragged;
  private Point draggedBy = new Point();
  // where the mouse is over the map, for the Delete key; empty once it left or the map moved
  private Optional<Point> hovered = Optional.empty();
  private transient Optional<Place> hoveredPlace = Optional.empty();
  // the misc picture a click places while decorations are edited
  private Optional<String> decorating = Optional.empty();
  // the pictures of decorations, by their file
  private final transient Map<Path, Optional<Image>> decorationPictures = new HashMap<>();

  /**
   * A place the game draws something at: in the selected territory units, by their place in the
   * order, or a marker, by its number for effects; or a decoration's picture, by its number.
   */
  private record Place(
      Optional<Marker> marker, Optional<String> decoration, int index, Point at) {}

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
      @Nullable String owner,
      String values,
      List<UnitPlacement> units,
      boolean changed,
      Point center) {}

  MapCanvas(final MapProject project) {
    this.project = project;
    // a new picture may change the map's size
    project.onChange(
        () -> {
          looks.clear();
          problemTerritories = null;
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

  /**
   * Shows the selected territory's places: dragging one moves it, a click into the territory adds
   * a place for units, a right-click removes one.
   */
  void editPlaces() {
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

  void stopEditingPlaces() {
    editingPlaces = false;
    decorating = Optional.empty();
    dragged = null;
    repaint();
  }

  void setSelected(@Nullable final String territory) {
    selected = territory;
    repaint();
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
                place
                    .marker()
                    .map(marker -> label(marker, place.index()) + ": drag to move")
                    .orElse(
                        place.decoration().orElse("Units " + (place.index() + 1))
                            + ": drag to move, right-click or Delete removes"))
        .or(() -> territoryAt(event.getPoint()))
        .orElse(null);
  }

  private Optional<String> territoryAt(final Point point) {
    final double x = point.x / zoom;
    final double y = point.y / zoom;
    return project.getFiles().polygons().entrySet().stream()
        .filter(e -> e.getValue().stream().anyMatch(p -> p.contains(x, y)))
        .map(Map.Entry::getKey)
        .findFirst();
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
            zoom, screenScale, mode, showPicture, selected, changes, project.getFiles(), overview);
    if (!drawn.equals(layerOf)) {
      layer.clear();
      layerOf = drawn;
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
    shownIn(
            new Rectangle(
                (int) (visible.x / zoom),
                (int) (visible.y / zoom),
                (int) Math.ceil(visible.width / zoom),
                (int) Math.ceil(visible.height / zoom)))
        .forEach(territory -> paintBadges(g, territory));
    places().forEach(place -> paintPlace(g, place));
    g.dispose();
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
                  places.add(new Place(Optional.empty(), Optional.of(picture), i, points.get(i)));
                }
              });
      return places;
    }
    if (!editingPlaces
        || selected == null
        || !project.getFiles().polygons().containsKey(selected)) {
      return List.of();
    }
    final String territory = selected;
    final List<Point> units = unitPlacesOf(territory);
    for (int i = 0; i < units.size(); i++) {
      places.add(new Place(Optional.empty(), Optional.empty(), i, units.get(i)));
    }
    final Game game = project.game();
    final MapFiles files = project.getFiles();
    for (final Marker marker : Marker.values()) {
      // a save keeps no other
      if (marker.isOn(game, territory)
          || (marker.keepsUnused() && files.places().markers(marker).containsKey(territory))) {
        final List<Point> points = files.markerPlaces(game, marker, territory);
        for (int i = 0; i < marker.count(game, territory); i++) {
          places.add(
              new Place(
                  Optional.of(marker),
                  Optional.empty(),
                  i,
                  points.get(Math.min(i, points.size() - 1))));
        }
      }
    }
    return places;
  }

  /**
   * The place on the screen, moved while it is dragged: a unit's square, a marker's dot or a
   * decoration's picture.
   */
  private Rectangle onScreen(final Place place) {
    final Point by = place.equals(dragged) ? draggedBy : new Point();
    final int x = (int) (place.at().x * zoom) + by.x;
    final int y = (int) (place.at().y * zoom) + by.y;
    if (place.marker().isPresent()) {
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

  /** How the marker is called, with its number for effects. */
  private static String label(final Marker marker, final int index) {
    return marker == Marker.EFFECT ? marker.getLabel() + " " + (index + 1) : marker.getLabel();
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
    final String territory = Objects.toString(selected, "");
    final Point to =
        new Point(
            place.at().x + (int) Math.round(by.x / zoom),
            place.at().y + (int) Math.round(by.y / zoom));
    if (place.decoration().isPresent()) {
      final String picture = place.decoration().get();
      final List<Point> moved = new ArrayList<>(decorations(picture));
      moved.set(place.index(), to);
      project.setDecorations(picture, moved);
    } else if (place.marker().isPresent()) {
      project.setMarkerPlace(place.marker().get(), territory, place.index(), to);
    } else {
      final List<Point> moved = new ArrayList<>(unitPlacesOf(territory));
      moved.set(place.index(), to);
      project.setUnitPlaces(territory, moved);
    }
  }

  /** A place for units after the others, centered at the point on the screen. */
  private void addUnitPlace(final Point point) {
    final String territory = Objects.requireNonNull(selected);
    final List<Point> added = new ArrayList<>(unitPlacesOf(territory));
    added.add(
        new Point(
            (int) Math.round(point.x / zoom) - unitSize.width / 2,
            (int) Math.round(point.y / zoom) - unitSize.height / 2));
    project.setUnitPlaces(territory, added);
  }

  /** Removes a place for units or a decoration; markers stay. */
  private void remove(final Place place) {
    if (place.decoration().isPresent()) {
      final List<Point> removed = new ArrayList<>(decorations(place.decoration().get()));
      removed.remove(place.index());
      project.setDecorations(place.decoration().get(), removed);
    } else if (place.marker().isEmpty()) {
      removeUnitPlace(place.index());
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
  private void removeUnitPlace(final int index) {
    final String territory = Objects.requireNonNull(selected);
    final List<Point> removed = new ArrayList<>(unitPlacesOf(territory));
    removed.remove(index);
    project.setUnitPlaces(territory, removed);
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
    } else if (place.marker().isEmpty()) {
      g.setColor(withAlpha(Color.WHITE, 0x66));
      g.fill(shown);
      g.setColor(accent);
      g.draw(shown);
      // in the middle: markers often sit at a square's corner
      paintBadge(
          g,
          String.valueOf(place.index() + 1),
          (int) shown.getCenterX(),
          (int) shown.getCenterY());
    } else {
      // a letter on the point, the whole name while the mouse is over it
      final Marker marker = place.marker().get();
      final boolean hover = hovered.map(shown::contains).orElse(false);
      final String text =
          hover
              ? label(marker, place.index())
              : marker.getLetter() + (marker == Marker.EFFECT ? place.index() + 1 : "");
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
    g.setColor(getBackground());
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
        List.of(files.picture(), files.folder(), files.relief(), files.showsRelief());
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
    // the game view as the game draws it: the owner's color thin over the picture, seas clear
    final boolean asPlayed = mode == MapMode.GAME && overPicture;
    if (!(asPlayed && look.water())) {
      final Color fill = fill(look);
      g.setColor(overPicture ? withAlpha(fill, asPlayed ? 0x59 : 0x8C) : fill);
      shapes.forEach(g::fill);
    }
    final boolean isSelected = territory.equals(selected);
    // a territory with a problem stands out in red, as the legend says
    final boolean problem = !isSelected && problemTerritories().contains(territory);
    g.setColor(isSelected ? Theme.getAccentColor() : problem ? PROBLEM : OUTLINE);
    g.setStroke(new BasicStroke((float) ((isSelected ? 4 : problem ? 3 : 1) / zoom)));
    shapes.forEach(g::draw);
  }

  private Color fill(final Look look) {
    if (look.water()) {
      return SEA;
    }
    final Color owner = Optional.ofNullable(look.owner()).map(project::nationColor).orElse(NEUTRAL);
    return switch (mode) {
      case GAME, OWNERS -> owner;
      case VALUES, UNITS, CONNECTIONS -> blend(owner, NEUTRAL);
    };
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

  private void paintConnections(final Graphics2D g, final Game game) {
    for (final Connection connection : MapXml.connections(game)) {
      final Point from = center(connection.getT1());
      final Point to = center(connection.getT2());
      final boolean ofSelected =
          connection.getT1().equals(selected) || connection.getT2().equals(selected);
      g.setStroke(new BasicStroke((float) ((ofSelected ? 5 : 3) / zoom)));
      g.setColor(Color.WHITE);
      g.drawLine(from.x, from.y, to.x, to.y);
      g.setStroke(new BasicStroke((float) ((ofSelected ? 3 : 1.5) / zoom)));
      g.setColor(ofSelected ? Theme.getAccentColor() : Color.DARK_GRAY);
      g.drawLine(from.x, from.y, to.x, to.y);
    }
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
            case GAME, OWNERS, CONNECTIONS -> "";
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

  /** Zooms so that the whole map fits into the view. */
  void zoomToFit() {
    if (getParent() instanceof JViewport viewport) {
      final Dimension view = viewport.getExtentSize();
      final Dimension map = project.getFiles().size();
      if (map.width > 0 && map.height > 0 && view.width > 0) {
        final double before = zoom;
        zoom =
            Math.clamp(
                Math.min(view.width / (double) map.width, view.height / (double) map.height),
                0.1,
                3.0);
        revalidate();
        repaint();
        firePropertyChange("zoom", before, zoom);
      }
    }
  }

  private final class Mouse extends MouseAdapter {
    private @Nullable Point dragFrom;

    @Override
    public void mousePressed(final MouseEvent e) {
      // any button moves the map, but a place grabbed; a left click without moving picks a
      // territory
      dragFrom = e.getLocationOnScreen();
      dragged = SwingUtilities.isLeftMouseButton(e) ? placeAt(e.getPoint()).orElse(null) : null;
      draggedBy = new Point();
    }

    @Override
    public void mouseReleased(final MouseEvent e) {
      if (dragged != null && !draggedBy.equals(new Point())) {
        move(dragged, draggedBy);
      }
      dragged = null;
      dragFrom = null;
      setCursor(Cursor.getDefaultCursor());
    }

    @Override
    public void mouseClicked(final MouseEvent e) {
      final Optional<Place> place = placeAt(e.getPoint());
      final Optional<String> territory = territoryAt(e.getPoint());
      if (place.isPresent()) {
        if (SwingUtilities.isRightMouseButton(e)) {
          remove(place.get());
        }
      } else if (SwingUtilities.isLeftMouseButton(e)) {
        if (decorating.isPresent()) {
          addDecoration(decorating.get(), e.getPoint());
        } else if (editingPlaces && territory.isPresent() && territory.get().equals(selected)) {
          addUnitPlace(e.getPoint());
        } else {
          territory.ifPresent(onClick);
        }
      }
    }

    @Override
    public void mouseExited(final MouseEvent e) {
      hovered = Optional.empty();
    }

    @Override
    public void mouseMoved(final MouseEvent e) {
      hovered = Optional.of(e.getPoint());
      if (editingPlaces || decorating.isPresent()) {
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
      if (dragged != null && dragFrom != null) {
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
