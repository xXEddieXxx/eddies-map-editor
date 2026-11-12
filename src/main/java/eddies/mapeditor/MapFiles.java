package eddies.mapeditor;

import games.strategy.triplea.Constants;
import games.strategy.triplea.image.MapImage;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.ui.mapdata.PlayerColors;
import games.strategy.triplea.ui.screen.TileManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
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
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.triplea.io.FileUtils;
import org.triplea.java.ColorUtils;
import org.triplea.map.data.elements.Game;
import org.triplea.util.PointFileReaderWriter;
import org.triplea.util.Tuple;
import eddies.mapeditor.MapPicture.DetectedMap;
import eddies.mapeditor.MapPicture.DetectedTerritory;

/**
 * The files of a map beside its game XML that the map maker shows: the territories' outlines and
 * centers, map.properties with the nations' colors, the picture of the map, in tiles in the folder
 * or, loaded but not saved yet, in memory, and the unit pictures uploaded since it was opened. A
 * map without outlines has no map to show.
 *
 * @param folder the map's folder with polygons.txt, empty for a map not saved yet or a game XML
 *     without one
 * @param picture the picture loaded in the map maker, empty when the map's tiles show it
 * @param unitPictures the pictures uploaded for unit types, by unit type, written for every nation
 *     on the next save
 * @param nationUnitPictures the pictures imported for one nation's units, before {@code
 *     unitPictures} for that nation
 * @param relief a relief picture loaded since the last save, cut into reliefTiles on the next
 * @param places where the game draws units, names, values and markers, read from the folder when
 *     the map opened and changed since; {@link Places}
 */
@Slf4j
record MapFiles(
    Optional<Path> folder,
    Map<String, List<Polygon>> polygons,
    Map<String, Point> centers,
    Properties properties,
    Optional<BufferedImage> picture,
    Map<String, UnitPicture> unitPictures,
    Map<NationUnit, BufferedImage> nationUnitPictures,
    Optional<BufferedImage> relief,
    Places places) {

  // map.properties: the color the game tints a nation's unit pictures in, eg ...color.Germans
  static final String UNIT_TINT = "units.transform.color.";
  // the unit types never tinted, eg ones in the same colors for every nation
  static final String UNTINTED_UNITS = "units.transform.ignore";
  private static final String POLYGONS = "polygons.txt";
  private static final String CENTERS = "centers.txt";
  private static final String PLACEMENTS = "place.txt";
  private static final String DECORATIONS = "decorations.txt";
  // the game's default map font and the sizes of its capital flags and victory city marker
  // shortcut: a map with another map font or other flag sizes gets its names and flags slightly
  // off center; read the font from map.properties and the flag size from its flags when that shows
  private static final Font NAME_FONT = new Font(MapImage.FONT_FAMILY_DEFAULT, Font.BOLD, 12);
  private static final int CAPITAL_FLAG_SIZE = 100;
  private static final int VICTORY_CITY_SIZE = 36;
  private static final int MAX_PLACES = 30;
  // pixels around the territories redrawn where borders were drawn, for the lines around them
  private static final int REDRAW_MARGIN = 10;
  // found effects side by side, about as wide as the game's effect pictures
  private static final int EFFECT_SPACING = 32;
  private static final String MAP_PROPERTIES = "map.properties";
  private static final String WIDTH = "map.width";
  private static final String HEIGHT = "map.height";
  // a redrawn border whose bounds overlap the old ones less than this changed noticeably
  private static final double SAME_BORDER = 0.9;
  // nor one whose bounds moved this few pixels, as found outlines reach into the border line
  private static final int SAME_BORDER_PIXELS = 3;
  // 50 million pixels, 200 MB: a picture in tiles bigger than this is not held to draw into
  private static final long MAX_HELD_PICTURE = 50_000_000L;

  /** The files of the map that the game XML belongs to, found in the folders above it. */
  static MapFiles of(final Path gameXml) {
    final Optional<Path> folder =
        FileUtils.findFileInParentFolders(gameXml, POLYGONS).map(Path::getParent);
    return folder.map(MapFiles::read).orElseGet(MapFiles::none);
  }

  /** No files: a map without outlines, colors or picture. */
  static MapFiles none() {
    return new MapFiles(
        Optional.empty(),
        Map.of(),
        Map.of(),
        new Properties(),
        Optional.empty(),
        Map.of(),
        Map.of(),
        Optional.empty(),
        Places.none());
  }

  private static MapFiles read(final Path folder) {
    try {
      final Path centers = folder.resolve(CENTERS);
      return new MapFiles(
          Optional.of(folder),
          PointFileReaderWriter.readOneToManyPolygons(folder.resolve(POLYGONS)),
          Files.exists(centers) ? PointFileReaderWriter.readOneToOne(centers) : Map.of(),
          readProperties(folder.resolve(MAP_PROPERTIES)),
          Optional.empty(),
          Map.of(),
          Map.of(),
          Optional.empty(),
          Places.read(folder));
    } catch (final IOException e) {
      log.warn("Could not read the outlines of the map in {}", folder, e);
      return none();
    }
  }

  private static Properties readProperties(final Path file) throws IOException {
    final Properties properties = new Properties();
    if (Files.exists(file)) {
      try (InputStream in = Files.newInputStream(file)) {
        properties.load(in);
      }
    }
    return properties;
  }

  /**
   * The files with the territory's outlines, center and places under its new name, in their place.
   */
  MapFiles withRenamed(final String old, final String renamed) {
    return new MapFiles(
        folder,
        renamedKey(polygons, old, renamed),
        renamedKey(centers, old, renamed),
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places.renamed(old, renamed));
  }

  /**
   * The files with these outlines for the territory, a new one last; its center stays if still
   * inside, and its places are found again in them.
   */
  MapFiles withOutlines(final String territory, final List<Polygon> outlines) {
    final Map<String, List<Polygon>> outlined = new LinkedHashMap<>(polygons);
    outlined.put(territory, List.copyOf(outlines));
    final Map<String, Point> middles = new LinkedHashMap<>(centers);
    movedCenter(territory, outlines).ifPresent(p -> middles.put(territory, p));
    return new MapFiles(
        folder,
        outlined,
        middles,
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places.without(List.of(territory)));
  }

  /**
   * The territory's center in the new outlines, when it must move: the center moves only when they
   * leave it outside.
   */
  private Optional<Point> movedCenter(final String territory, final List<Polygon> outlines) {
    final Point center = centers.get(territory);
    return center == null || outlines.stream().noneMatch(o -> o.contains(center))
        ? outlines.stream().findFirst().flatMap(MapFiles::pointInside)
        : Optional.empty();
  }

  /** The files without the territory's outlines, center and places. */
  MapFiles withoutTerritory(final String territory) {
    final Map<String, List<Polygon>> outlined = new LinkedHashMap<>(polygons);
    outlined.remove(territory);
    final Map<String, Point> middles = new LinkedHashMap<>(centers);
    middles.remove(territory);
    return new MapFiles(
        folder,
        outlined,
        middles,
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places.without(List.of(territory)));
  }

  private static <T> Map<String, T> renamedKey(
      final Map<String, T> values, final String old, final String renamed) {
    final Map<String, T> result = new LinkedHashMap<>();
    values.forEach((name, value) -> result.put(old.equals(name) ? renamed : name, value));
    return result;
  }

  /** The files with this picture, the map of its size; the outlines and centers stay. */
  MapFiles withPicture(final BufferedImage image) {
    final Properties sized = copyOfProperties();
    sized.setProperty(WIDTH, String.valueOf(image.getWidth()));
    sized.setProperty(HEIGHT, String.valueOf(image.getHeight()));
    return new MapFiles(
        folder,
        polygons,
        centers,
        sized,
        Optional.of(image),
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  /** The files with the picture and the outlines and centers found in it, the map of its size. */
  MapFiles withPicture(final BufferedImage image, final DetectedMap detected) {
    final Map<String, List<Polygon>> outlines = new LinkedHashMap<>();
    final Map<String, Point> middles = new LinkedHashMap<>();
    for (final DetectedTerritory territory : detected.territories()) {
      outlines.put(territory.name(), territory.outlines());
      middles.put(territory.name(), territory.center());
    }
    final MapFiles pictured = withPicture(image);
    // the picture's territories are new: points in the files belong to the names found
    return new MapFiles(
        folder,
        outlines,
        middles,
        pictured.properties,
        pictured.picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  /**
   * The files with the picture, each territory's borders redrawn from it: each of its outlines
   * becomes the outline of the area in the picture that covers most of it and no territory before
   * took, so names, owners, units and neighbours stay. An outline keeps its border where no such
   * area is found, eg after the border between two territories was rubbed out: then the area is
   * told as merged when it holds all of the territory's outlines and the one that took it has
   * exactly it, or none took it and it holds all outlines of two or more. A territory whose
   * border changed noticeably gets its places found again, the others keep theirs.
   */
  Redrawn withBordersFrom(final BufferedImage image, final DetectedMap detected) {
    return withBordersFrom(image, detected, polygons.keySet(), Map.of());
  }

  /**
   * As {@link #withBordersFrom(BufferedImage, DetectedMap)}, only where borders were drawn: the
   * territories with an outline reaching into the region, from the areas found in the part of the
   * picture they cover; the others keep their outlines and places, and no area beyond the region
   * is new.
   */
  Redrawn withBordersFrom(final BufferedImage image, final Rectangle region) {
    final Set<String> near = new HashSet<>();
    final Rectangle box = new Rectangle(region);
    polygons.forEach(
        (territory, outlines) -> {
          if (outlines.stream().anyMatch(o -> o.intersects(region))) {
            near.add(territory);
            outlines.forEach(o -> box.add(o.getBounds()));
          }
        });
    // the border lines around them too
    box.grow(REDRAW_MARGIN, REDRAW_MARGIN);
    final Rectangle whole = new Rectangle(image.getWidth(), image.getHeight());
    Rectangle within = box.intersection(whole);
    while (true) {
      final DetectedMap found = MapPicture.detect(image, within);
      // an area beyond the region is the territory's it lies in, so a new one gets it as neighbour
      final Map<DetectedTerritory, String> others = new HashMap<>();
      final List<DetectedTerritory> areas = new ArrayList<>();
      boolean cut = false;
      for (final DetectedTerritory area : found.territories()) {
        final Optional<String> holder = territoryAt(area.center());
        if (area.outlines().stream().anyMatch(o -> o.intersects(region))
            || holder.filter(near::contains).isPresent()) {
          areas.add(area);
          cut |= endsOnEdge(area, within, whole);
        } else if (holder.isPresent()) {
          areas.add(area);
          others.put(area, holder.get());
        }
      }
      // an area taken or new must be whole, eg one left out before that reaches beyond the box
      if (!cut || within.equals(whole)) {
        return withBordersFrom(
            image, new DetectedMap(found.size(), areas, found.connections()), near, others);
      }
      within = new Rectangle(within);
      within.grow(within.width, within.height);
      within = within.intersection(whole);
    }
  }

  /** Whether the area found within the box ends on its edge inside the picture: it was cut. */
  private static boolean endsOnEdge(
      final DetectedTerritory area, final Rectangle box, final Rectangle whole) {
    return area.outlines().stream()
        .map(Polygon::getBounds)
        .anyMatch(
            b ->
                (b.x <= box.x && box.x > whole.x)
                    || (b.y <= box.y && box.y > whole.y)
                    || (b.x + b.width >= box.x + box.width - 1
                        && box.x + box.width < whole.x + whole.width)
                    || (b.y + b.height >= box.y + box.height - 1
                        && box.y + box.height < whole.y + whole.height));
  }

  /** The innermost territory with an outline holding the point: the island, not the sea around. */
  private Optional<String> territoryAt(final Point point) {
    return polygons.entrySet().stream()
        .filter(e -> e.getValue().stream().anyMatch(o -> o.contains(point)))
        .min(
            Comparator.comparingDouble(
                e ->
                    e.getValue().stream()
                        .map(Polygon::getBounds)
                        .mapToDouble(b -> (double) b.width * b.height)
                        .sum()))
        .map(Map.Entry::getKey);
  }

  /**
   * Borders redrawn for these territories, the others kept as they are.
   *
   * @param taken areas that are other territories', which none of these may take
   */
  private Redrawn withBordersFrom(
      final BufferedImage image,
      final DetectedMap detected,
      final Set<String> redrawing,
      final Map<DetectedTerritory, String> taken) {
    final Map<String, List<Polygon>> outlines = new LinkedHashMap<>();
    final Map<String, Point> middles = new LinkedHashMap<>(centers);
    final Map<DetectedTerritory, String> takenBy = new HashMap<>(taken);
    final Set<String> kept = new LinkedHashSet<>();
    final Set<String> foundOne = new HashSet<>();
    // per territory, the area holding most of each outline that found no free area
    final Map<String, List<Optional<DetectedTerritory>>> heldBy = new LinkedHashMap<>();
    polygons.forEach(
        (territory, before) -> {
          if (!redrawing.contains(territory)) {
            outlines.put(territory, before);
            return;
          }
          final List<Polygon> after = new ArrayList<>();
          for (final Polygon outline : before) {
            final Optional<DetectedTerritory> area =
                areasCovering(outline, detected).stream()
                    .filter(a -> takenBy.getOrDefault(a, territory).equals(territory))
                    .findFirst();
            if (area.isPresent()) {
              foundOne.add(territory);
              if (takenBy.put(area.get(), territory) == null) {
                after.addAll(area.get().outlines());
              }
            } else {
              after.add(outline);
              kept.add(territory);
              heldBy
                  .computeIfAbsent(territory, t -> new ArrayList<>())
                  .add(areaHoldingMost(outline, detected));
            }
          }
          outlines.put(territory, after);
          movedCenter(territory, after).ifPresent(p -> middles.put(territory, p));
        });
    final Map<DetectedTerritory, List<String>> merged = new LinkedHashMap<>();
    final Set<DetectedTerritory> held = new HashSet<>();
    heldBy.forEach(
        (territory, areas) -> {
          areas.forEach(area -> area.ifPresent(held::add));
          final Optional<DetectedTerritory> area = areas.getFirst();
          if (!foundOne.contains(territory)
              && area.isPresent()
              && areas.stream().allMatch(area::equals)
              && (!takenBy.containsKey(area.get())
                  || outlines.get(takenBy.get(area.get())).equals(area.get().outlines()))) {
            merged
                .computeIfAbsent(
                    area.get(),
                    a -> new ArrayList<>(Optional.ofNullable(takenBy.get(a)).stream().toList()))
                .add(territory);
          }
        });
    // an area none took, as each holds too little of it, is a merge of two or more
    merged.values().removeIf(members -> members.size() < 2);
    merged.replaceAll((area, members) -> List.copyOf(members));
    final MapFiles pictured = withPicture(image);
    final List<String> changed =
        outlines.keySet().stream()
            .filter(t -> !kept.contains(t))
            .filter(t -> !isSameBorder(polygons.get(t), outlines.get(t)))
            .toList();
    return new Redrawn(
        new MapFiles(
            folder,
            outlines,
            middles,
            pictured.properties,
            pictured.picture,
            unitPictures,
            nationUnitPictures,
            relief,
            // places the old border held may lie outside the new one: found again in it
            places.without(changed)),
        changed,
        List.copyOf(kept),
        detected,
        Map.copyOf(takenBy),
        Collections.unmodifiableMap(merged),
        Set.copyOf(held));
  }

  /**
   * Files with borders redrawn, {@link #withBordersFrom}.
   *
   * @param changed the territories whose border changed noticeably, to look at
   * @param kept the territories with an outline that kept its border
   * @param detected the areas of the picture
   * @param takenBy the territory that took each area
   * @param merged the areas a rubbed-out border made of territories, with them: the one that took
   *     it first, if one did
   * @param held the areas holding most of an outline that kept its border: old land, not new
   */
  record Redrawn(
      MapFiles files,
      List<String> changed,
      List<String> kept,
      DetectedMap detected,
      Map<DetectedTerritory, String> takenBy,
      Map<DetectedTerritory, List<String>> merged,
      Set<DetectedTerritory> held) {

    /** The areas no territory took, eg drawn in as new ones, in the picture's order. */
    List<DetectedTerritory> added() {
      return detected.territories().stream()
          .filter(a -> !takenBy.containsKey(a) && !held.contains(a))
          .toList();
    }
  }

  /**
   * The areas that cover a part of the outline, the most first, counted on a grid of points inside
   * it, so a new border through the outline's middle still finds them. An area counts only when a
   * good part of it lies in the outline: an area's outline has no holes, so a sea around an island
   * covers the island too, yet the island holds little of the sea.
   */
  private static List<DetectedTerritory> areasCovering(
      final Polygon outline, final DetectedMap detected) {
    final Map<DetectedTerritory, Integer> covered =
        innermostCounts(pointsInside(outline), detected);
    covered.keySet().removeIf(area -> !isMostlyIn(area, outline));
    return covered.entrySet().stream()
        .sorted(Map.Entry.<DetectedTerritory, Integer>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** How many of the points each area holds innermost: the island, not the sea around it. */
  private static Map<DetectedTerritory, Integer> innermostCounts(
      final List<Point> points, final DetectedMap detected) {
    final Map<DetectedTerritory, Integer> counts = new HashMap<>();
    for (final Point point : points) {
      detected.territories().stream()
          .filter(area -> area.outlines().stream().anyMatch(o -> o.contains(point)))
          .min(Comparator.comparingDouble(MapFiles::boundsArea))
          .ifPresent(area -> counts.merge(area, 1, Integer::sum));
    }
    return counts;
  }

  /**
   * The area innermost at more than half of the outline, eg the big one a small territory was
   * merged into; not {@link #isMostlyIn}, as little of that area lies in the outline.
   */
  private static Optional<DetectedTerritory> areaHoldingMost(
      final Polygon outline, final DetectedMap detected) {
    final List<Point> points = pointsInside(outline);
    return innermostCounts(points, detected).entrySet().stream()
        .filter(e -> e.getValue() * 2 > points.size())
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private static double boundsArea(final DetectedTerritory area) {
    return area.outlines().stream()
        .map(Polygon::getBounds)
        .mapToDouble(b -> (double) b.width * b.height)
        .sum();
  }

  /** Whether at least a quarter of the area lies in the outline, on a grid over the area. */
  private static boolean isMostlyIn(final DetectedTerritory area, final Polygon outline) {
    final List<Point> points =
        area.outlines().stream().flatMap(o -> pointsInside(o).stream()).toList();
    return points.stream().filter(outline::contains).count() * 4 >= points.size();
  }

  /** A point well inside the outline: the one nearest its middle on a grid over it. */
  private static Optional<Point> pointInside(final Polygon outline) {
    final Rectangle bounds = outline.getBounds();
    final Point middle = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
    return outline.contains(middle)
        ? Optional.of(middle)
        : pointsInside(outline).stream().min(Comparator.comparingDouble(p -> p.distance(middle)));
  }

  /** The points of a grid of about 16 by 16 over the outline that lie inside it. */
  private static List<Point> pointsInside(final Polygon outline) {
    final Rectangle bounds = outline.getBounds();
    final int stepX = Math.max(1, bounds.width / 16);
    final int stepY = Math.max(1, bounds.height / 16);
    final List<Point> inside = new ArrayList<>();
    for (int x = bounds.x + stepX / 2; x <= bounds.x + bounds.width; x += stepX) {
      for (int y = bounds.y + stepY / 2; y <= bounds.y + bounds.height; y += stepY) {
        if (outline.contains(x, y)) {
          inside.add(new Point(x, y));
        }
      }
    }
    return inside;
  }

  /** Whether two territories' bounds are the same within a few pixels or mostly overlap. */
  private static boolean isSameBorder(final List<Polygon> before, final List<Polygon> after) {
    final Rectangle a = boundsOf(before);
    final Rectangle b = boundsOf(after);
    final boolean near =
        Math.abs(a.x - b.x) <= SAME_BORDER_PIXELS
            && Math.abs(a.y - b.y) <= SAME_BORDER_PIXELS
            && Math.abs(a.x + a.width - b.x - b.width) <= SAME_BORDER_PIXELS
            && Math.abs(a.y + a.height - b.y - b.height) <= SAME_BORDER_PIXELS;
    return near || overlap(a, b) >= SAME_BORDER;
  }

  private static double overlap(final Rectangle a, final Rectangle b) {
    final Rectangle common = a.intersection(b);
    final double shared = common.isEmpty() ? 0 : (double) common.width * common.height;
    return shared / ((double) a.width * a.height + (double) b.width * b.height - shared);
  }

  private static Rectangle boundsOf(final List<Polygon> outlines) {
    return outlines.stream()
        .map(Polygon::getBounds)
        .reduce(Rectangle::union)
        .orElseGet(Rectangle::new);
  }

  /** The files in this folder, eg once a new map is saved. */
  MapFiles withFolder(final Path mapFolder) {
    return new MapFiles(
        Optional.of(mapFolder),
        polygons,
        centers,
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  MapFiles withNationColor(final String nation, final Color color) {
    final Properties colored = copyOfProperties();
    colored.setProperty(
        PlayerColors.PROPERTY_COLOR_PREFIX + nation,
        String.format(Locale.ROOT, "%06X", color.getRGB() & 0xFFFFFF));
    return new MapFiles(
        folder,
        polygons,
        centers,
        colored,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  MapFiles withRelief(final BufferedImage image) {
    return new MapFiles(
        folder,
        polygons,
        centers,
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        Optional.of(image),
        places);
  }

  /** With the map.properties entry set, or removed for an empty value, eg units.scale. */
  MapFiles withProperty(final String name, final String value) {
    final Properties changed = copyOfProperties();
    if (value.isEmpty()) {
      changed.remove(name);
    } else {
      changed.setProperty(name, value);
    }
    return new MapFiles(
        folder,
        polygons,
        centers,
        changed,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  MapFiles withoutNationColor(final String nation) {
    final Properties uncolored = copyOfProperties();
    uncolored.remove(PlayerColors.PROPERTY_COLOR_PREFIX + nation);
    return new MapFiles(
        folder,
        polygons,
        centers,
        uncolored,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  MapFiles withUnitPicture(final String unitType, final UnitPicture unitPicture) {
    final Map<String, UnitPicture> pictures = new LinkedHashMap<>(unitPictures);
    pictures.put(unitType, unitPicture);
    return new MapFiles(
        folder,
        polygons,
        centers,
        properties,
        picture,
        pictures,
        nationUnitPictures,
        relief,
        places);
  }

  /** The files without the unit type's uploaded picture: its units look as the game draws them. */
  MapFiles withoutUnitPicture(final String unitType) {
    final Map<String, UnitPicture> pictures = new LinkedHashMap<>(unitPictures);
    pictures.remove(unitType);
    return new MapFiles(
        folder,
        polygons,
        centers,
        properties,
        picture,
        pictures,
        nationUnitPictures,
        relief,
        places);
  }

  /**
   * The files with these pictures for the nation's units, by unit type, over the ones it had; an
   * empty map takes all of them back, so the nation's units look as before.
   */
  MapFiles withNationUnitPictures(final String nation, final Map<String, BufferedImage> byUnit) {
    final Map<NationUnit, BufferedImage> pictures = new LinkedHashMap<>(nationUnitPictures);
    if (byUnit.isEmpty()) {
      pictures.keySet().removeIf(key -> key.nation().equals(nation));
    }
    byUnit.forEach((unit, image) -> pictures.put(new NationUnit(nation, unit), image));
    return new MapFiles(
        folder,
        polygons,
        centers,
        properties,
        picture,
        unitPictures,
        pictures,
        relief,
        places);
  }

  /** A unit type as one nation's units show it. */
  record NationUnit(String nation, String unit) {}

  /**
   * A picture uploaded for a unit type, for the units of every nation.
   *
   * @param image at most of the size of a unit, {@link MapFolder#toUnitSize}
   */
  record UnitPicture(BufferedImage image, Coloring coloring) {
    /** Whether each nation's units show the picture in the nation's color or all as it is. */
    enum Coloring {
      PER_NATION,
      AS_IS
    }

    /** The picture as the units of a nation of this color show it. */
    BufferedImage forNation(final Color color) {
      return coloring == Coloring.PER_NATION ? MapFolder.tint(image, color) : image;
    }
  }

  // a step keeps its properties as they were, so a change copies them
  private Properties copyOfProperties() {
    final Properties copy = new Properties();
    copy.putAll(properties);
    return copy;
  }

  /** Writes the outlines and centers into the map's folder; without a folder or outlines, none. */
  void writeOutlines() throws IOException {
    if (folder.isPresent() && hasOutlines()) {
      MapFolder.replace(
          folder.get().resolve(POLYGONS),
          file -> PointFileReaderWriter.writeOneToManyPolygons(file, polygons));
      if (!centers.isEmpty()) {
        MapFolder.replace(
            folder.get().resolve(CENTERS),
            file -> PointFileReaderWriter.writeOneToOne(file, centers));
      }
    }
  }

  /**
   * Writes where the game draws each territory's units, each capital's flag and each victory city's
   * marker; the other markers' files ({@link Marker#isAlwaysWritten}) and decorations.txt only when
   * the map has them or places were set here; without a folder or outlines, none. Lines of
   * territories removed since the last save go.
   *
   * @param saved the files as last saved, {@link #writeKeeping}
   */
  void writePlaces(final Game game, final MapFiles saved) throws IOException {
    if (folder.isEmpty() || !hasOutlines()) {
      return;
    }
    // place.txt, which the game needs for every territory
    writeKeeping(
        PLACEMENTS,
        polygons.keySet(),
        places.units(),
        saved.places().units(),
        t -> Tuple.of(unitPlaces(t), Boolean.FALSE),
        t -> !polygons.containsKey(t) && !saved.polygons().containsKey(t),
        PointFileReaderWriter::readOneToManyPlacements,
        PointFileReaderWriter::writeOneToManyPlacements);
    // the game reads each file on its own and falls back on a place of its own without it
    for (final Marker marker : Marker.values()) {
      if (marker.isAlwaysWritten() || isKept(marker.file, places.markers(marker))) {
        writeMarkers(game, marker, saved);
      }
    }
    if (isKept(DECORATIONS, places.decorations())) {
      writeKeeping(
          DECORATIONS,
          places.decorations().keySet(),
          places.decorations(),
          saved.places().decorations(),
          image -> List.of(),
          image -> true,
          PointFileReaderWriter::readOneToMany,
          PointFileReaderWriter::writeOneToMany);
    }
  }

  private boolean isKept(final String fileName, final Map<String, ?> placed) {
    return !placed.isEmpty() || Files.exists(folder.orElseThrow().resolve(fileName));
  }

  /**
   * Writes name_place.txt and pu_place.txt: each territory's name centered above the units around
   * its center, and below it the value of land that gives PUs. Without them the game finds a place
   * for the name itself and draws the value below it; a map made here gets them, an opened map
   * keeps its way until a name or value is moved.
   *
   * @param saved the files as last saved, {@link #writeKeeping}
   */
  void writeNamePlaces(final Game game, final MapFiles saved) throws IOException {
    if (folder.isPresent() && hasOutlines()) {
      writeMarkers(game, Marker.NAME, saved);
      writeMarkers(game, Marker.VALUE, saved);
    }
  }

  private void writeMarkers(final Game game, final Marker marker, final MapFiles saved)
      throws IOException {
    final Map<String, List<Point>> had = places.markers(marker);
    final boolean keepsAll = marker.keepsUnused();
    writeKeeping(
        marker.file,
        territories(t -> marker.isOn(game, t) || (keepsAll && had.containsKey(t))),
        had,
        saved.places().markers(marker),
        t -> foundMarkers(game, marker, t),
        // a territory removed since the last save loses its line
        t -> polygons.containsKey(t) ? keepsAll : !saved.polygons().containsKey(t),
        marker::read,
        marker::write);
  }

  /**
   * Writes a file of points by territory. For these territories: a place changed here since the
   * last save, else the file's, maybe set by hand meanwhile, else the one here, else a found one.
   * Other lines of the file stay if {@code keeps} says so, unless they were taken away here, eg by
   * a rename. Written only when that changes the file; a file left with no territory is removed.
   *
   * @param had the places here
   * @param saved the places as last saved
   */
  private <T> void writeKeeping(
      final String fileName,
      final Collection<String> territories,
      final Map<String, T> had,
      final Map<String, T> saved,
      final Function<String, T> found,
      final Predicate<String> keeps,
      final PointsReader<T> reader,
      final PointsWriter<T> writer)
      throws IOException {
    final Path file = folder.orElseThrow().resolve(fileName);
    final Map<String, T> before = Files.exists(file) ? reader.read(file) : Map.of();
    final Map<String, T> after = new LinkedHashMap<>();
    for (final String territory : territories) {
      // a place taken away here, eg found again, is found
      final Optional<T> chosen =
          Objects.equals(had.get(territory), saved.get(territory))
              ? Optional.ofNullable(before.get(territory))
                  .or(() -> Optional.ofNullable(had.get(territory)))
              : Optional.ofNullable(had.get(territory));
      after.put(territory, chosen.orElseGet(() -> found.apply(territory)));
    }
    before.forEach(
        (territory, points) -> {
          if (!after.containsKey(territory)
              && keeps.test(territory)
              && !(saved.containsKey(territory) && !had.containsKey(territory))) {
            after.put(territory, points);
          }
        });
    if (after.isEmpty()) {
      if (Files.exists(file)) {
        MapFolder.remove(file);
      }
    } else if (!after.equals(before)) {
      MapFolder.replace(file, written -> writer.write(written, after));
    }
  }

  @FunctionalInterface
  private interface PointsReader<T> {
    Map<String, T> read(Path file) throws IOException;
  }

  @FunctionalInterface
  private interface PointsWriter<T> {
    void write(Path file, Map<String, T> points) throws IOException;
  }

  /**
   * A mark the game draws at a point of its own in a territory, as {@code MapData} reads them: its
   * name, value, capital's flag, victory city marker, a sea zone's blockade, convoy, comment and
   * kamikaze markers, and the territory's effects, one point each but the effects.
   */
  @AllArgsConstructor
  enum Marker {
    NAME("name_place.txt", "Name", "N"),
    VALUE("pu_place.txt", "PUs", "P"),
    CAPITAL("capitols.txt", "Capital", "C"),
    VICTORY_CITY("vc.txt", "Victory city", "V"),
    BLOCKADE("blockade.txt", "Blockade", "B"),
    CONVOY("convoy.txt", "Convoy", "Cv"),
    COMMENT("comments.txt", "Comment", "T"),
    KAMIKAZE("kamikaze_place.txt", "Kamikaze", "K"),
    EFFECT("territory_effects.txt", "Effect", "E");

    private final String file;
    @Getter private final String label;
    // on the map, where the full labels would cover each other
    @Getter private final String letter;

    /** Whether the game draws it for the territory, as TripleA's TileManager decides. */
    boolean isOn(final Game game, final String territory) {
      final boolean water = MapXml.isWater(game, territory);
      return switch (this) {
        case NAME -> true;
        case VALUE -> !water && MapXml.production(game, territory) > 0;
        case CAPITAL -> MapXml.isCapital(game, territory);
        case VICTORY_CITY -> MapXml.isVictoryCity(game, territory);
        case BLOCKADE -> water && MapXml.hasTerritoryFlag(game, territory, "blockadeZone");
        case CONVOY ->
            water
                && (MapXml.hasTerritoryFlag(game, territory, "convoyRoute")
                    || MapXml.production(game, territory) > 0);
        // a convoy center's comment names its original owner, at the start its owner
        case COMMENT ->
            water
                && (MapXml.hasTerritoryFlag(game, territory, "convoyRoute")
                    || (MapXml.production(game, territory) > 0
                        && (MapXml.owner(game, territory).isPresent()
                            || MapXml.option(
                                    game,
                                    Constants.TERRITORY_ATTACHMENT_NAME,
                                    territory,
                                    "originalOwner")
                                .isPresent())));
        case KAMIKAZE -> water && MapXml.hasTerritoryFlag(game, territory, "kamikazeZone");
        case EFFECT -> !MapXml.territoryEffects(game, territory).isEmpty();
      };
    }

    /**
     * Whether a save keeps its points where the game does not draw it now: names, values and flags
     * follow the game, but triggers may make any sea zone a convoy route or kamikaze zone.
     */
    boolean keepsUnused() {
      return EnumSet.of(BLOCKADE, CONVOY, COMMENT, KAMIKAZE, EFFECT).contains(this);
    }

    /** How many the game draws: one, or one per effect. */
    int count(final Game game, final String territory) {
      return this == EFFECT ? Math.max(1, MapXml.territoryEffects(game, territory).size()) : 1;
    }

    /** Whether its file is written on every save, as the game draws it badly without one. */
    boolean isAlwaysWritten() {
      return this == CAPITAL || this == VICTORY_CITY;
    }

    private Map<String, List<Point>> read(final Path from) throws IOException {
      if (this == EFFECT) {
        return PointFileReaderWriter.readOneToMany(from);
      }
      final Map<String, List<Point>> points = new LinkedHashMap<>();
      PointFileReaderWriter.readOneToOne(from).forEach((t, point) -> points.put(t, List.of(point)));
      return points;
    }

    private void write(final Path to, final Map<String, List<Point>> points) throws IOException {
      if (this == EFFECT) {
        PointFileReaderWriter.writeOneToMany(to, points);
      } else {
        final Map<String, Point> first = new LinkedHashMap<>();
        points.forEach((t, list) -> first.put(t, list.getFirst()));
        PointFileReaderWriter.writeOneToOne(to, first);
      }
    }
  }

  /**
   * Where the map's files place things: by territory the units (place.txt, with whether more stacks
   * than places go on to the left) and the markers, and the decorations by their picture in the
   * misc folder. A territory missing here has its places found, {@link #unitPlaces}, {@link
   * #markerPlaces}; places of territories the map maker does not know stay as they are.
   */
  record Places(
      Map<String, Tuple<List<Point>, Boolean>> units,
      Map<Marker, Map<String, List<Point>>> markers,
      Map<String, List<Point>> decorations) {
    static Places none() {
      return new Places(Map.of(), Map.of(), Map.of());
    }

    /** The places in the folder's files; a file that does not read gives none, a save says why. */
    static Places read(final Path folder) {
      final Map<Marker, Map<String, List<Point>>> markers = new EnumMap<>(Marker.class);
      for (final Marker marker : Marker.values()) {
        markers.put(marker, readOrNone(folder.resolve(marker.file), marker::read));
      }
      return new Places(
          readOrNone(folder.resolve(PLACEMENTS), PointFileReaderWriter::readOneToManyPlacements),
          Map.copyOf(markers),
          readOrNone(folder.resolve(DECORATIONS), PointFileReaderWriter::readOneToMany));
    }

    private static <T> Map<String, T> readOrNone(final Path file, final PointsReader<T> reader) {
      if (!Files.exists(file)) {
        return Map.of();
      }
      try {
        return Collections.unmodifiableMap(reader.read(file));
      } catch (final IOException | IllegalArgumentException e) {
        log.warn("Could not read {}", file, e);
        return Map.of();
      }
    }

    Map<String, List<Point>> markers(final Marker marker) {
      return markers.getOrDefault(marker, Map.of());
    }

    Places renamed(final String old, final String renamed) {
      final Map<Marker, Map<String, List<Point>>> moved = new EnumMap<>(Marker.class);
      markers.forEach(
          (marker, points) ->
              moved.put(marker, Collections.unmodifiableMap(renamedKey(points, old, renamed))));
      return new Places(
          Collections.unmodifiableMap(renamedKey(units, old, renamed)),
          Map.copyOf(moved),
          decorations);
    }

    /** Without the places of these territories, so they are found again. */
    Places without(final Collection<String> territories) {
      final Map<Marker, Map<String, List<Point>>> kept = new EnumMap<>(Marker.class);
      markers.forEach((marker, points) -> kept.put(marker, without(points, territories)));
      return new Places(without(units, territories), Map.copyOf(kept), decorations);
    }

    private static <T> Map<String, T> without(
        final Map<String, T> values, final Collection<String> territories) {
      final Map<String, T> kept = new LinkedHashMap<>(values);
      kept.keySet().removeAll(territories);
      return Collections.unmodifiableMap(kept);
    }

    Places withUnits(final String territory, final Tuple<List<Point>, Boolean> placed) {
      final Map<String, Tuple<List<Point>, Boolean>> changed = new LinkedHashMap<>(units);
      changed.put(territory, placed);
      return new Places(Collections.unmodifiableMap(changed), markers, decorations);
    }

    Places withMarker(final Marker marker, final String territory, final List<Point> points) {
      final Map<String, List<Point>> placed = new LinkedHashMap<>(markers(marker));
      placed.put(territory, points);
      final Map<Marker, Map<String, List<Point>>> changed = new EnumMap<>(Marker.class);
      changed.putAll(markers);
      changed.put(marker, Collections.unmodifiableMap(placed));
      return new Places(units, Map.copyOf(changed), decorations);
    }

    Places withDecorations(final String picture, final List<Point> points) {
      final Map<String, List<Point>> changed = new LinkedHashMap<>(decorations);
      if (points.isEmpty()) {
        changed.remove(picture);
      } else {
        changed.put(picture, List.copyOf(points));
      }
      return new Places(units, markers, Collections.unmodifiableMap(changed));
    }
  }

  /**
   * Where the game draws the territory's units, the first stack first: as the map's place.txt says,
   * else found inside it.
   */
  List<Point> unitPlaces(final String territory) {
    return Optional.ofNullable(places.units().get(territory))
        .map(Tuple::getFirst)
        .orElseGet(
            () ->
                placesInside(
                    polygons.getOrDefault(territory, List.of()),
                    centers.get(territory),
                    unitSize()));
  }

  /**
   * Where the game draws the marker in the territory: as the map's file says, else found; for
   * effects one point per effect, the last one again for effects beyond them.
   */
  List<Point> markerPlaces(final Game game, final Marker marker, final String territory) {
    return Optional.ofNullable(places.markers(marker).get(territory))
        .filter(points -> !points.isEmpty())
        .orElseGet(() -> foundMarkers(game, marker, territory));
  }

  /**
   * The marker's places as found: one, or the effects side by side, so each shows and can be
   * moved.
   */
  private List<Point> foundMarkers(final Game game, final Marker marker, final String territory) {
    return sideBySide(foundMarker(game, marker, territory), marker.count(game, territory));
  }

  private static List<Point> sideBySide(final Point first, final int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new Point(first.x + i * EFFECT_SPACING, first.y))
        .toList();
  }

  /**
   * Like {@link #markerPlaces} for so many effects, without asking the game how many: the map
   * shows every territory's effects at once.
   */
  List<Point> effectPlaces(final String territory, final int effects) {
    return Optional.ofNullable(places.markers(Marker.EFFECT).get(territory))
        .filter(points -> !points.isEmpty())
        .orElseGet(() -> sideBySide(middle(territory), Math.max(1, effects)));
  }

  Point markerPlace(final Game game, final Marker marker, final String territory) {
    return markerPlaces(game, marker, territory).getFirst();
  }

  /** The files with the territory's units placed here, in this order; for none, unchanged. */
  MapFiles withUnitPlaces(final String territory, final List<Point> points) {
    if (points.isEmpty() || points.equals(unitPlaces(territory))) {
      return this;
    }
    final boolean toLeft =
        Optional.ofNullable(places.units().get(territory)).map(Tuple::getSecond).orElse(false);
    return withPlaces(places.withUnits(territory, Tuple.of(List.copyOf(points), toLeft)));
  }

  /** The files with the marker's point of this number (0 but for effects) at the point given. */
  MapFiles withMarkerPlace(
      final Game game,
      final Marker marker,
      final String territory,
      final int index,
      final Point point) {
    final List<Point> before = markerPlaces(game, marker, territory);
    final List<Point> moved = new ArrayList<>(before);
    while (moved.size() <= index) {
      moved.add(moved.getLast());
    }
    moved.set(index, point);
    return moved.equals(before)
        ? this
        : withPlaces(places.withMarker(marker, territory, List.copyOf(moved)));
  }

  /** The files with the territory's places found again; unchanged if they are the found ones. */
  MapFiles withPlacesFoundAgain(final String territory) {
    final Places found = places.without(List.of(territory));
    return found.equals(places) ? this : withPlaces(found);
  }

  /**
   * The files with the misc folder's picture drawn at these top left corners; none takes it off the
   * map.
   */
  MapFiles withDecorations(final String picture, final List<Point> points) {
    return points.equals(places.decorations().getOrDefault(picture, List.of()))
        ? this
        : withPlaces(places.withDecorations(picture, points));
  }

  /** The pictures in the map's misc folder that decorations can show, by file name. */
  List<String> miscPictures() {
    final Optional<Path> misc = miscFolder().filter(Files::isDirectory);
    if (misc.isEmpty()) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(misc.get())) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(name -> name.toLowerCase(Locale.ROOT).matches(".*\\.(png|gif|jpg|jpeg)"))
          .sorted()
          .toList();
    } catch (final IOException e) {
      log.info("Could not list the pictures in {}", misc.get(), e);
      return List.of();
    }
  }

  /** The map's misc folder, where the game finds the decorations' pictures. */
  Optional<Path> miscFolder() {
    return folder.map(f -> f.resolve("misc"));
  }

  private MapFiles withPlaces(final Places changed) {
    return new MapFiles(
        folder,
        polygons,
        centers,
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        changed);
  }

  /** The size of a unit on the map as the game draws it: units.width and height by units.scale. */
  Dimension unitSize() {
    final int size = UnitImageFactory.DEFAULT_UNIT_ICON_SIZE;
    double scale;
    try {
      scale = Double.parseDouble(properties.getProperty(MapData.PROPERTY_UNITS_SCALE, "1"));
    } catch (final NumberFormatException e) {
      // the game takes such a map's units at their size too
      scale = 1;
    }
    return new Dimension(
        (int) (scale * intProperty(MapData.PROPERTY_UNITS_WIDTH).orElse(size)),
        (int) Math.round(scale * intProperty(MapData.PROPERTY_UNITS_HEIGHT).orElse(size)));
  }

  /**
   * The marker's place as the map maker finds it, eg the name above the units at the center; where
   * the game falls back on the center, the center.
   */
  private Point foundMarker(final Game game, final Marker marker, final String territory) {
    final Point middle = middle(territory);
    return switch (marker) {
      // the game draws the flag and the marker from their top left corner, else from the center
      case CAPITAL -> new Point(middle.x - CAPITAL_FLAG_SIZE / 2, middle.y - CAPITAL_FLAG_SIZE / 2);
      // right of the units around the center
      case VICTORY_CITY ->
          new Point(
              middle.x + UnitImageFactory.DEFAULT_UNIT_ICON_SIZE / 2 + 2,
              middle.y - VICTORY_CITY_SIZE / 2);
      case BLOCKADE, CONVOY, KAMIKAZE, EFFECT -> middle;
      case NAME, VALUE, COMMENT -> {
        final FontMetrics font = fontMetrics();
        final Optional<Point> namePlace =
            Optional.ofNullable(places.markers(Marker.NAME).get(territory)).map(List::getFirst);
        if (marker == Marker.COMMENT && namePlace.isPresent()) {
          // the game draws the comment a line above the name it places
          yield new Point(namePlace.get().x, namePlace.get().y - font.getHeight());
        }
        // the game draws a text from its left end, on its baseline or, set so, below its top
        final int top =
            Boolean.parseBoolean(properties.getProperty(MapData.PROPERTY_MAP_DRAWNAMESFROMTOPLEFT))
                ? font.getHeight()
                : 0;
        final Point baseline = nameBaseline(territory, font);
        final String text =
            marker == Marker.VALUE ? String.valueOf(MapXml.production(game, territory)) : territory;
        // the value a line below the name, a comment a line above it
        final int below =
            switch (marker) {
              case VALUE -> font.getHeight();
              case COMMENT -> -font.getHeight();
              default -> 0;
            };
        yield new Point(baseline.x - font.stringWidth(text) / 2, baseline.y + below - top);
      }
    };
  }

  private static FontMetrics fontMetrics() {
    final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
    try {
      return g.getFontMetrics(NAME_FONT);
    } finally {
      g.dispose();
    }
  }

  /**
   * The middle of the territory's name on its baseline: two lines above the units around the
   * center, room for the value below; at the center when that is outside the territory.
   */
  private Point nameBaseline(final String territory, final FontMetrics font) {
    final Point middle = middle(territory);
    final Point above =
        new Point(
            middle.x,
            middle.y
                - UnitImageFactory.DEFAULT_UNIT_ICON_SIZE / 2
                - font.getDescent()
                - font.getHeight());
    final Point textMiddle = new Point(above.x, above.y - font.getAscent() / 2);
    return polygons.get(territory).stream().anyMatch(outline -> outline.contains(textMiddle))
        ? above
        : new Point(middle.x, middle.y + font.getAscent() / 2);
  }

  private List<String> territories(final Predicate<String> matching) {
    return polygons.keySet().stream().filter(matching).toList();
  }

  /** The territory's center, else the middle of its outlines. */
  private Point middle(final String territory) {
    return Optional.ofNullable(centers.get(territory))
        .orElseGet(
            () -> {
              final Rectangle bounds =
                  polygons.get(territory).stream()
                      .map(Polygon::getBounds)
                      .reduce(Rectangle::union)
                      .orElseGet(Rectangle::new);
              return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
            });
  }

  /**
   * Places for unit stacks inside the outlines: the top left corners of unit-sized squares on a
   * grid that lie wholly inside, nearest to the center first; the center alone if none fits.
   */
  private static List<Point> placesInside(
      final List<Polygon> outlines, @Nullable final Point center, final Dimension unit) {
    // from the outlines' own bounds: an empty Rectangle would add the map's origin to them
    final Rectangle bounds =
        outlines.stream()
            .map(Polygon::getBounds)
            .reduce(Rectangle::union)
            .orElseGet(Rectangle::new);
    final Point middle =
        Optional.ofNullable(center)
            .orElseGet(() -> new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
    final int width = Math.max(1, unit.width);
    final int height = Math.max(1, unit.height);
    // the grid runs through the center, so the first place sits on it where it fits
    final int startX =
        middle.x - width / 2 - Math.floorDiv(middle.x - width / 2 - bounds.x, width) * width;
    final int startY =
        middle.y - height / 2 - Math.floorDiv(middle.y - height / 2 - bounds.y, height) * height;
    final List<Point> places = new ArrayList<>();
    for (int x = startX; x + width <= bounds.x + bounds.width; x += width) {
      for (int y = startY; y + height <= bounds.y + bounds.height; y += height) {
        final Rectangle square = new Rectangle(x, y, width, height);
        if (outlines.stream().anyMatch(outline -> outline.contains(square))) {
          places.add(new Point(x, y));
        }
      }
    }
    places.sort(
        Comparator.comparingDouble(
            p -> p.distance(middle.x - width / 2.0, middle.y - height / 2.0)));
    return places.isEmpty()
        ? List.of(new Point(middle.x - width / 2, middle.y - height / 2))
        : List.copyOf(places.subList(0, Math.min(places.size(), MAX_PLACES)));
  }

  /** Writes map.properties into the map's folder, eg the map's size and the nations' colors. */
  void writeProperties() throws IOException {
    if (folder.isPresent()) {
      MapFolder.replace(
          folder.get().resolve(MAP_PROPERTIES),
          file -> {
            try (OutputStream out = Files.newOutputStream(file)) {
              properties.store(out, "Written by the TripleA map maker");
            }
          });
    }
  }

  boolean hasOutlines() {
    return !polygons.isEmpty();
  }

  /**
   * The size of the map: the loaded picture's, else as map.properties says, else as far as the
   * outlines reach.
   */
  Dimension size() {
    if (picture.isPresent()) {
      return new Dimension(picture.get().getWidth(), picture.get().getHeight());
    }
    final Rectangle bounds = new Rectangle();
    polygons.values().stream().flatMap(List::stream).map(Polygon::getBounds).forEach(bounds::add);
    return new Dimension(
        intProperty(WIDTH).orElse(bounds.x + bounds.width),
        intProperty(HEIGHT).orElse(bounds.y + bounds.height));
  }

  private Optional<Integer> intProperty(final String name) {
    try {
      return Optional.ofNullable(properties.getProperty(name))
          .map(String::strip)
          .map(Integer::valueOf);
    } catch (final NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * The color the game tints the nation's unit pictures in (map.properties
   * units.transform.color.Nation), empty when it draws them as they are or the color is unreadable.
   */
  Optional<Color> unitTint(final String nation) {
    return MapLookEditor.colorOf(properties.getProperty(UNIT_TINT + nation, ""));
  }

  /**
   * The unit types the game draws as they are, never tinted in a nation's color (map.properties
   * units.transform.ignore, names split at commas as the game reads them).
   */
  List<String> untintedUnits() {
    return Arrays.stream(properties.getProperty(UNTINTED_UNITS, "").split(","))
        .filter(name -> !name.isEmpty())
        .toList();
  }

  /** The nation's color from map.properties, empty when the map gives it none. */
  Optional<Color> nationColor(final String nation) {
    try {
      return Optional.ofNullable(
              properties.getProperty(PlayerColors.PROPERTY_COLOR_PREFIX + nation))
          .map(ColorUtils::fromHexString);
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * The picture put together from its tiles, as big as map.properties says (else as the tiles
   * reach); empty without tiles, for one that does not read, or one too big to hold in memory.
   */
  Optional<BufferedImage> tilesPicture() {
    if (tile(0, 0).isEmpty()) {
      return Optional.empty();
    }
    final int tileSize = tileSize();
    int columns = 0;
    while (tile(columns, 0).isPresent()) {
      columns++;
    }
    int rows = 0;
    while (tile(0, rows).isPresent()) {
      rows++;
    }
    final int width = intProperty(WIDTH).orElse(columns * tileSize);
    final int height = intProperty(HEIGHT).orElse(rows * tileSize);
    if (width <= 0 || height <= 0 || (long) width * height > MAX_HELD_PICTURE) {
      return Optional.empty();
    }
    final BufferedImage whole = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = whole.createGraphics();
    try {
      for (int column = 0; column * tileSize < width; column++) {
        for (int row = 0; row * tileSize < height; row++) {
          final Optional<Path> file = tile(column, row);
          if (file.isPresent()) {
            final BufferedImage read = ImageIO.read(file.get().toFile());
            if (read == null) {
              return Optional.empty();
            }
            g.drawImage(read, column * tileSize, row * tileSize, null);
          }
        }
      }
    } catch (final IOException e) {
      return Optional.empty();
    } finally {
      g.dispose();
    }
    return Optional.of(whole);
  }

  /** The tile of the map's picture at the column and row, as the game cuts it; empty if missing. */
  Optional<Path> tile(final int column, final int row) {
    return folder
        .map(f -> f.resolve("baseTiles").resolve(column + "_" + row + ".png"))
        .filter(Files::exists);
  }

  /** The tile of the relief drawn over the picture at the column and row; empty if missing. */
  Optional<Path> reliefTile(final int column, final int row) {
    return folder
        .map(f -> f.resolve("reliefTiles").resolve(column + "_" + row + ".png"))
        .filter(Files::exists);
  }

  /** Whether the game draws the relief, as map.properties says; it does unless told not to. */
  boolean showsRelief() {
    return Boolean.parseBoolean(
        properties.getProperty(MapData.PROPERTY_MAP_HASRELIEF, "true").strip());
  }

  static int tileSize() {
    return TileManager.TILE_SIZE;
  }
}
