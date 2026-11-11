package eddies.mapeditor;

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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
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
 * @param renames the territories renamed since the last save, their name now to their name in the
 *     folder's files, so a save finds the points those files keep for them
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
    Map<String, String> renames) {

  private static final String POLYGONS = "polygons.txt";
  private static final String CENTERS = "centers.txt";
  private static final String PLACEMENTS = "place.txt";
  private static final String NAME_PLACES = "name_place.txt";
  private static final String PU_PLACES = "pu_place.txt";
  private static final String CAPITALS = "capitols.txt";
  private static final String VICTORY_CITIES = "vc.txt";
  // the game's default map font and the sizes of its capital flags and victory city marker
  // shortcut: a map with another map font or other flag sizes gets its names and flags slightly
  // off center; read the font from map.properties and the flag size from its flags when that shows
  private static final Font NAME_FONT = new Font(MapImage.FONT_FAMILY_DEFAULT, Font.BOLD, 12);
  private static final int CAPITAL_FLAG_SIZE = 100;
  private static final int VICTORY_CITY_SIZE = 36;
  private static final int MAX_PLACES = 30;
  private static final String MAP_PROPERTIES = "map.properties";
  private static final String WIDTH = "map.width";
  private static final String HEIGHT = "map.height";
  // a redrawn border whose bounds overlap the old ones less than this changed noticeably
  private static final double SAME_BORDER = 0.9;

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
        Map.of());
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
          Map.of());
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
   * The files with the territory's outlines and center under its new name, in their place, and the
   * rename kept for the next save.
   */
  MapFiles withRenamed(final String old, final String renamed) {
    final Map<String, String> chained = new LinkedHashMap<>(renames);
    final String inFolder = Optional.ofNullable(chained.remove(old)).orElse(old);
    if (!inFolder.equals(renamed)) {
      chained.put(renamed, inFolder);
    }
    return new MapFiles(
        folder,
        renamedKey(polygons, old, renamed),
        renamedKey(centers, old, renamed),
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        chained);
  }

  /**
   * These files once the folder's files hold the names of a save that made these renames: an
   * earlier or later step's renames then lead to the names saved.
   *
   * @param saved the renames the save wrote, {@link #renames} of the files saved
   */
  MapFiles afterSaving(final Map<String, String> saved) {
    if (saved.isEmpty()) {
      return this;
    }
    final Map<String, String> savedNameOf = new HashMap<>();
    saved.forEach((now, before) -> savedNameOf.put(before, now));
    final Map<String, String> rebased = new LinkedHashMap<>();
    // this step's own territories: a name only the saved step had would turn into a wrong mapping
    for (final String name : polygons.keySet()) {
      final String inOldFolder = renames.getOrDefault(name, name);
      final String inFolder = savedNameOf.getOrDefault(inOldFolder, inOldFolder);
      if (!inFolder.equals(name)) {
        rebased.put(name, inFolder);
      }
    }
    return new MapFiles(
        folder,
        polygons,
        centers,
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        Map.copyOf(rebased));
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
        renames);
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
        Map.of());
  }

  /**
   * The files with the picture, each territory's borders redrawn from it: each of its outlines
   * becomes the outline of the area in the picture that covers most of it and no territory before
   * took, so names, owners, units and neighbours stay. An outline keeps its border where no such
   * area is found, eg after the border between two territories was rubbed out.
   */
  Redrawn withBordersFrom(final BufferedImage image, final DetectedMap detected) {
    final Map<String, List<Polygon>> outlines = new LinkedHashMap<>();
    final Map<String, Point> middles = new LinkedHashMap<>(centers);
    final Map<DetectedTerritory, String> takenBy = new HashMap<>();
    final Set<String> kept = new LinkedHashSet<>();
    polygons.forEach(
        (territory, before) -> {
          final List<Polygon> after = new ArrayList<>();
          for (final Polygon outline : before) {
            final Optional<DetectedTerritory> area =
                areasCovering(outline, detected).stream()
                    .filter(a -> takenBy.getOrDefault(a, territory).equals(territory))
                    .findFirst();
            if (area.isPresent()) {
              if (takenBy.put(area.get(), territory) == null) {
                after.addAll(area.get().outlines());
              }
            } else {
              after.add(outline);
              kept.add(territory);
            }
          }
          outlines.put(territory, after);
          // the center moves only when the new border leaves it outside
          final Point center = centers.get(territory);
          if (center == null || after.stream().noneMatch(o -> o.contains(center))) {
            after.stream()
                .findFirst()
                .flatMap(MapFiles::pointInside)
                .ifPresent(p -> middles.put(territory, p));
          }
        });
    final MapFiles pictured = withPicture(image);
    return new Redrawn(
        new MapFiles(
            folder,
            outlines,
            middles,
            pictured.properties,
            pictured.picture,
            unitPictures,
            nationUnitPictures,
            renames),
        outlines.keySet().stream()
            .filter(t -> !kept.contains(t))
            .filter(t -> overlap(polygons.get(t), outlines.get(t)) < SAME_BORDER)
            .toList(),
        List.copyOf(kept));
  }

  /**
   * Files with borders redrawn, {@link #withBordersFrom}.
   *
   * @param changed the territories whose border changed noticeably, to look at
   * @param kept the territories with an outline that kept its border
   */
  record Redrawn(MapFiles files, List<String> changed, List<String> kept) {}

  /**
   * The areas that cover a part of the outline, the most first, counted on a grid of points inside
   * it, so a new border through the outline's middle still finds them. An area counts only when a
   * good part of it lies in the outline: an area's outline has no holes, so a sea around an island
   * covers the island too, yet the island holds little of the sea.
   */
  private static List<DetectedTerritory> areasCovering(
      final Polygon outline, final DetectedMap detected) {
    final Map<DetectedTerritory, Integer> covered = new HashMap<>();
    for (final Point point : pointsInside(outline)) {
      // the innermost area at the point: the island, not the sea around it
      detected.territories().stream()
          .filter(area -> area.outlines().stream().anyMatch(o -> o.contains(point)))
          .min(Comparator.comparingDouble(MapFiles::boundsArea))
          .ifPresent(area -> covered.merge(area, 1, Integer::sum));
    }
    covered.keySet().removeIf(area -> !isMostlyIn(area, outline));
    return covered.entrySet().stream()
        .sorted(Map.Entry.<DetectedTerritory, Integer>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
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

  /** How much two territories' bounds overlap: 1 for the same, 0 for none. */
  private static double overlap(final List<Polygon> before, final List<Polygon> after) {
    final Rectangle a = boundsOf(before);
    final Rectangle b = boundsOf(after);
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
        renames);
  }

  MapFiles withNationColor(final String nation, final Color color) {
    final Properties colored = copyOfProperties();
    colored.setProperty(
        PlayerColors.PROPERTY_COLOR_PREFIX + nation,
        String.format(Locale.ROOT, "%06X", color.getRGB() & 0xFFFFFF));
    return new MapFiles(
        folder, polygons, centers, colored, picture, unitPictures, nationUnitPictures, renames);
  }

  MapFiles withoutNationColor(final String nation) {
    final Properties uncolored = copyOfProperties();
    uncolored.remove(PlayerColors.PROPERTY_COLOR_PREFIX + nation);
    return new MapFiles(
        folder, polygons, centers, uncolored, picture, unitPictures, nationUnitPictures, renames);
  }

  MapFiles withUnitPicture(final String unitType, final UnitPicture unitPicture) {
    final Map<String, UnitPicture> pictures = new LinkedHashMap<>(unitPictures);
    pictures.put(unitType, unitPicture);
    return new MapFiles(
        folder, polygons, centers, properties, picture, pictures, nationUnitPictures, renames);
  }

  /** The files without the unit type's uploaded picture: its units look as the game draws them. */
  MapFiles withoutUnitPicture(final String unitType) {
    final Map<String, UnitPicture> pictures = new LinkedHashMap<>(unitPictures);
    pictures.remove(unitType);
    return new MapFiles(
        folder, polygons, centers, properties, picture, pictures, nationUnitPictures, renames);
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
        folder, polygons, centers, properties, picture, unitPictures, pictures, renames);
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
      // place.txt, where the game draws each territory's units, which it needs for every territory
      writeKeeping(
          folder.get().resolve(PLACEMENTS),
          polygons.keySet(),
          territory ->
              Tuple.of(
                  placesInside(polygons.get(territory), centers.get(territory)), Boolean.FALSE),
          PointFileReaderWriter::readOneToManyPlacements,
          PointFileReaderWriter::writeOneToManyPlacements);
    }
  }

  /**
   * Writes a file of points by territory for these territories: points a map has already, maybe set
   * by hand, stay, also under a territory's new name; missing ones are found, points of other
   * territories are left out. A file left with no territory is removed.
   */
  private <T> void writeKeeping(
      final Path file,
      final Collection<String> territories,
      final Function<String, T> found,
      final PointsReader<T> reader,
      final PointsWriter<T> writer)
      throws IOException {
    final Map<String, T> before = Files.exists(file) ? reader.read(file) : Map.of();
    final Map<String, T> after = new LinkedHashMap<>();
    for (final String territory : territories) {
      final String inFile = renames.getOrDefault(territory, territory);
      after.put(
          territory,
          // the own name too: after a save that failed halfway the file may hold it already
          before.containsKey(inFile)
              ? before.get(inFile)
              : before.containsKey(territory) ? before.get(territory) : found.apply(territory));
    }
    if (after.isEmpty()) {
      MapFolder.remove(file);
    } else if (!after.equals(before)) {
      MapFolder.replace(file, written -> writer.write(written, after));
    }
  }

  /**
   * Where the game draws each territory's units, by the name in the map folder's place.txt; none
   * without the file or when it cannot be read.
   */
  static Map<String, List<Point>> savedUnitPlaces(final Path mapFolder) {
    final Path file = mapFolder.resolve(PLACEMENTS);
    if (!Files.exists(file)) {
      return Map.of();
    }
    try {
      final Map<String, List<Point>> places = new HashMap<>();
      PointFileReaderWriter.readOneToManyPlacements(file)
          .forEach((territory, points) -> places.put(territory, points.getFirst()));
      return places;
    } catch (final IOException expected) {
      // the map maker then shows units at places it finds itself
      return Map.of();
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
   * Writes where the game draws each capital's flag and each victory city's marker, and for a map
   * that places its names itself (with name_place.txt) also the names and values, {@link
   * #writeNamePlaces}; without a folder or outlines, none.
   */
  void writeMarkers(final Game game) throws IOException {
    if (folder.isEmpty() || !hasOutlines()) {
      return;
    }
    final Path mapFolder = folder.get();
    if (Files.exists(mapFolder.resolve(NAME_PLACES))) {
      writeNamePlaces(game);
    }
    // the game draws the flag and the marker from their top left corner, else from the center
    writeKeeping(
        mapFolder.resolve(CAPITALS),
        territories(t -> MapXml.isCapital(game, t)),
        t -> new Point(middle(t).x - CAPITAL_FLAG_SIZE / 2, middle(t).y - CAPITAL_FLAG_SIZE / 2),
        PointFileReaderWriter::readOneToOne,
        PointFileReaderWriter::writeOneToOne);
    writeKeeping(
        mapFolder.resolve(VICTORY_CITIES),
        territories(t -> MapXml.isVictoryCity(game, t)),
        // right of the units around the center
        t ->
            new Point(
                middle(t).x + UnitImageFactory.DEFAULT_UNIT_ICON_SIZE / 2 + 2,
                middle(t).y - VICTORY_CITY_SIZE / 2),
        PointFileReaderWriter::readOneToOne,
        PointFileReaderWriter::writeOneToOne);
  }

  /**
   * Writes name_place.txt and pu_place.txt: each territory's name centered above the units around
   * its center, and below it the value of land that gives PUs. Without them the game finds a place
   * for the name itself and draws the value below it; a map made here gets them, an opened map
   * keeps its way.
   */
  void writeNamePlaces(final Game game) throws IOException {
    if (folder.isEmpty() || !hasOutlines()) {
      return;
    }
    final FontMetrics font = fontMetrics();
    // the game draws a text from its left end, on its baseline or, set so, below its top
    final int top =
        Boolean.parseBoolean(properties.getProperty(MapData.PROPERTY_MAP_DRAWNAMESFROMTOPLEFT))
            ? font.getHeight()
            : 0;
    writeKeeping(
        folder.get().resolve(NAME_PLACES),
        polygons.keySet(),
        t -> {
          final Point baseline = nameBaseline(t, font);
          return new Point(baseline.x - font.stringWidth(t) / 2, baseline.y - top);
        },
        PointFileReaderWriter::readOneToOne,
        PointFileReaderWriter::writeOneToOne);
    writeKeeping(
        folder.get().resolve(PU_PLACES),
        territories(t -> !MapXml.isWater(game, t) && MapXml.production(game, t) > 0),
        t -> {
          final Point baseline = nameBaseline(t, font);
          final String value = String.valueOf(MapXml.production(game, t));
          return new Point(
              baseline.x - font.stringWidth(value) / 2, baseline.y + font.getHeight() - top);
        },
        PointFileReaderWriter::readOneToOne,
        PointFileReaderWriter::writeOneToOne);
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
  static List<Point> placesInside(final List<Polygon> outlines, @Nullable final Point center) {
    // from the outlines' own bounds: an empty Rectangle would add the map's origin to them
    final Rectangle bounds =
        outlines.stream()
            .map(Polygon::getBounds)
            .reduce(Rectangle::union)
            .orElseGet(Rectangle::new);
    final Point middle =
        Optional.ofNullable(center)
            .orElseGet(() -> new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
    final int size = UnitImageFactory.DEFAULT_UNIT_ICON_SIZE;
    // the grid runs through the center, so the first place sits on it where it fits
    final int startX =
        middle.x - size / 2 - Math.floorDiv(middle.x - size / 2 - bounds.x, size) * size;
    final int startY =
        middle.y - size / 2 - Math.floorDiv(middle.y - size / 2 - bounds.y, size) * size;
    final List<Point> places = new ArrayList<>();
    for (int x = startX; x + size <= bounds.x + bounds.width; x += size) {
      for (int y = startY; y + size <= bounds.y + bounds.height; y += size) {
        final Rectangle square = new Rectangle(x, y, size, size);
        if (outlines.stream().anyMatch(outline -> outline.contains(square))) {
          places.add(new Point(x, y));
        }
      }
    }
    places.sort(
        Comparator.comparingDouble(p -> p.distance(middle.x - size / 2.0, middle.y - size / 2.0)));
    return places.isEmpty()
        ? List.of(new Point(middle.x - size / 2, middle.y - size / 2))
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

  /** The tile of the map's picture at the column and row, as the game cuts it; empty if missing. */
  Optional<Path> tile(final int column, final int row) {
    return folder
        .map(f -> f.resolve("baseTiles").resolve(column + "_" + row + ".png"))
        .filter(Files::exists);
  }

  static int tileSize() {
    return TileManager.TILE_SIZE;
  }
}
