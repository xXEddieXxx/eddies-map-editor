package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
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
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import lombok.extern.slf4j.Slf4j;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.Initialize.UnitInitialize.UnitPlacement;
import org.triplea.map.data.elements.Map.Connection;

/**
 * The map of the map maker: the territories by their outlines, colored by the chosen {@link
 * MapMode}. A click picks a territory, dragging with any mouse button moves the map, Ctrl and the
 * mouse wheel zoom, as in the game.
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

  private final transient MapProject project;
  // the tiles seen last, enough for a screen at any zoom; a big map has far more than fit memory
  private final transient Map<Point, Optional<Image>> tiles = newRecentlyUsed(200);
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
  private final transient Map<String, Rectangle> bounds = new HashMap<>();
  private final transient Map<String, List<Point>> unitPlaces = new HashMap<>();
  private transient @Nullable Map<String, List<Point>> savedUnitPlaces;
  private final transient Map<String, Optional<Image>> unitPictures = new HashMap<>();
  // the picture made smaller once, for a zoom at which the screen holds more tiles than are kept
  private transient @Nullable BufferedImage overview;
  private transient List<Object> overviewOf = List.of();
  private MapMode mode = MapMode.GAME;
  // the map's units.scale, as the files have it
  private double unitsScale = 1;
  private double zoom = 0.5;
  private boolean showPicture = true;
  private @Nullable String selected;
  private transient Consumer<String> onClick = territory -> {};

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
    return territoryAt(event.getPoint()).orElse(null);
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
    g.dispose();
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
    final Map<String, List<Polygon>> polygons = project.getFiles().polygons();
    shownIn(g.getClipBounds())
        .forEach(territory -> paintTerritory(g, territory, polygons.get(territory), picture));
    if (mode == MapMode.CONNECTIONS) {
      paintConnections(g, project.game());
    }
    g.dispose();
    return drawn;
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
      savedUnitPlaces = null;
      unitsScale = unitsScale(filesSeen);
      unitPictures.clear();
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
    final int size = MapFiles.tileSize();
    final Rectangle visible = g.getClipBounds();
    boolean any = false;
    for (int column = Math.max(0, visible.x / size);
        column <= (visible.x + visible.width) / size;
        column++) {
      for (int row = Math.max(0, visible.y / size);
          row <= (visible.y + visible.height) / size;
          row++) {
        final Optional<Image> tile = tile(column, row);
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
    final List<Object> source = List.of(files.picture(), files.folder());
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
    g.dispose();
    return small;
  }

  private Optional<Image> tile(final int column, final int row) {
    return tiles.computeIfAbsent(
        new Point(column, row), at -> project.getFiles().tile(at.x, at.y).flatMap(MapCanvas::read));
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
    // the map's units.scale too, as its place.txt is laid out for units of that size
    final int unitSize =
        (int) Math.round(UnitImageFactory.DEFAULT_UNIT_ICON_SIZE * unitsScale * zoom);
    final boolean showsUnits = mode == MapMode.GAME || mode == MapMode.UNITS;
    if (showsUnits && unitSize >= MIN_UNIT_SIZE) {
      paintUnits(g, territory, look.units(), unitSize);
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

  private static double unitsScale(final MapFiles files) {
    try {
      return Double.parseDouble(files.properties().getProperty(MapData.PROPERTY_UNITS_SCALE, "1"));
    } catch (final NumberFormatException e) {
      // the game takes such a map's units at their size too
      return 1;
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

  /** Where the game draws the territory's units: from the map's place.txt, else found inside it. */
  private List<Point> unitPlacesOf(final String territory) {
    return unitPlaces.computeIfAbsent(
        territory,
        t -> {
          final MapFiles files = project.getFiles();
          if (savedUnitPlaces == null) {
            savedUnitPlaces = project.getFolder().map(MapFiles::savedUnitPlaces).orElse(Map.of());
          }
          final List<Point> saved =
              savedUnitPlaces.getOrDefault(files.renames().getOrDefault(t, t), List.of());
          return saved.isEmpty()
              ? MapFiles.placesInside(
                  files.polygons().getOrDefault(t, List.of()), files.centers().get(t))
              : saved;
        });
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
      // any button moves the map; a left click without moving picks a territory
      dragFrom = e.getLocationOnScreen();
    }

    @Override
    public void mouseReleased(final MouseEvent e) {
      dragFrom = null;
      setCursor(Cursor.getDefaultCursor());
    }

    @Override
    public void mouseClicked(final MouseEvent e) {
      if (SwingUtilities.isLeftMouseButton(e)) {
        territoryAt(e.getPoint()).ifPresent(onClick);
      }
    }

    @Override
    public void mouseDragged(final MouseEvent e) {
      if (dragFrom != null && getParent() instanceof JViewport viewport) {
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
      if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
        zoomAt(e.getPoint(), e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15);
      } else if (getParent() instanceof JViewport viewport
          && viewport.getParent() instanceof JScrollPane scroll) {
        scroll.dispatchEvent(SwingUtilities.convertMouseEvent(MapCanvas.this, e, scroll));
      }
    }
  }
}
