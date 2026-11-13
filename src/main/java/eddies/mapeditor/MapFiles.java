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
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.triplea.io.FileUtils;
import org.triplea.java.ColorUtils;
import org.triplea.map.data.elements.AttachmentList.Attachment;
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
  /** The territories whose names the game does not draw, MapData.shouldDrawTerritoryName. */
  static final String HIDDEN_NAMES = MapData.PROPERTY_DONT_DRAW_TERRITORY_NAMES;
  /**
   * The unit types the game draws nowhere on the map and leaves out of its unit statistics,
   * MapData.shouldDrawUnit.
   */
  static final String UNDRAWN_UNITS = "dont_draw_units";
  // the textures the editor bakes into the relief it makes, which the game does not read: a
  // territory's, eg relief.texture.Mordor=Mountains, and where one starts, relief.textureOffset.
  static final String TEXTURE = "relief.texture.";
  static final String TEXTURE_OFFSET = "relief.textureOffset.";
  // the nations that have no capital on purpose, which the game does not read either: the guide
  // then asks for none, eg guide.noCapital=Chinese,Dutch
  static final String NO_CAPITAL = "guide.noCapital";
  // the problems and notes the map maker marked "Fine as it is", which the game does not read
  // either: by the problem's kind and what it is about, eg guide.fine.notConnected.Sahara=true
  static final String FINE = "guide.fine.";
  // a renamed nation's name in the folder, where its flags and unit pictures stay until the next
  // save moves them, eg editor.filesOf.Soviets=Russians; held in memory only, never written
  private static final String FILES_OF = "editor.filesOf.";
  // map.properties' entries of one nation, each with its name at the end (MapData, PlayerColors)
  private static final List<String> NATION_KEYS =
      List.of(
          PlayerColors.PROPERTY_COLOR_PREFIX,
          UNIT_TINT,
          "units.transform.brightness.",
          "units.transform.flip.");
  private static final String POLYGONS = "polygons.txt";
  static final String CENTERS = "centers.txt";
  static final String PLACEMENTS = "place.txt";
  private static final String DECORATIONS = "decorations.txt";
  // the game's default map font and the sizes of its capital flags and victory city marker
  // shortcut: a map with another map font or other flag sizes gets its names and flags slightly
  // off center; read the font from map.properties and the flag size from its flags when that shows
  private static final Font NAME_FONT = new Font(MapImage.FONT_FAMILY_DEFAULT, Font.BOLD, 12);
  private static final int CAPITAL_FLAG_SIZE = 100;
  private static final int VICTORY_CITY_SIZE = 36;
  private static final int MAX_PLACES = 30;
  // pixels kept free around each laid out unit place, so neighbours stand 4 px apart
  private static final int UNIT_GUTTER = 2;
  // below so many places a territory also gets some that stick out over its border
  private static final int FEW_PLACES = 3;
  // off the grid only for territories with at most so many positions to try, so a layout of every
  // territory stays quick: a sea of 3000 px has room on the grid
  private static final int MOST_OFF_GRID = 4000;
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
  // a held picture of more pixels is drawn into itself, with no copy per line for undo
  // ({@link #drawsInPlace}); not final, as tests lower it rather than make such a picture
  static long undoPictureLimit = MAX_HELD_PICTURE;
  // beside a big picture's own bytes: the canvas's smaller copy and squares, tiles being read
  private static final long BESIDE_PICTURE_BYTES = 256_000_000L;
  // a resized map's sides at least: below it territories fall together
  private static final int MIN_RESIZED_SIDE = 200;
  // a picture counts as drawn in flat colors when this share of its pixels equals its neighbour
  private static final double FLAT_SHARE = 0.75;

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
        propertiesMoved(old, Optional.of(renamed)),
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
        propertiesMoved(territory, Optional.empty()),
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places.without(List.of(territory)));
  }

  /**
   * The properties with what names the territory under the new name, or without it: its texture
   * and its entry in the list of territories whose names the game does not draw.
   */
  private Properties propertiesMoved(final String territory, final Optional<String> renamed) {
    final Properties moved = copyOfProperties();
    final Object texture = moved.remove(TEXTURE + territory);
    if (texture != null) {
      renamed.ifPresent(r -> moved.put(TEXTURE + r, texture));
    }
    renameInList(moved, HIDDEN_NAMES, territory, renamed.stream().toList());
    moveFine(moved, territory, renamed.stream().toList());
    return moved;
  }

  /** The marks "Fine as it is" about this territory or nation under these names instead. */
  private static void moveFine(
      final Properties properties, final String about, final List<String> put) {
    for (final String key : properties.stringPropertyNames()) {
      if (key.startsWith(FINE) && key.endsWith("." + about)) {
        final String kind = key.substring(0, key.length() - about.length());
        final Object mark = properties.remove(key);
        put.forEach(name -> properties.put(kind + name, mark));
      }
    }
  }

  /**
   * Whether these are the other files but for the marks "Fine as it is": what Check found for
   * the others then holds for these.
   */
  boolean differsInFineOnly(final MapFiles other) {
    return withoutFine().equals(other.withoutFine());
  }

  private MapFiles withoutFine() {
    final Properties plain = copyOfProperties();
    plain.keySet().removeIf(key -> String.valueOf(key).startsWith(FINE));
    return new MapFiles(
        folder,
        polygons,
        centers,
        plain,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
  }

  /** Whether the map maker marked the problem "Fine as it is": it then counts nowhere. */
  boolean isFine(final MapChecks.Problem problem) {
    return problem.key() != null && properties.containsKey(FINE + problem.key());
  }

  /** The list of names between commas with these in the name's place; an emptied list goes. */
  private static void renameInList(
      final Properties properties, final String key, final String name, final List<String> put) {
    final String listed = properties.getProperty(key);
    // the game reads the names between the commas as they are
    if (listed != null && List.of(listed.split(",")).contains(name)) {
      final String rest =
          Stream.of(listed.split(","))
              .flatMap(n -> n.equals(name) ? put.stream() : Stream.of(n))
              .distinct()
              .collect(Collectors.joining(","));
      if (rest.isEmpty()) {
        properties.remove(key);
      } else {
        properties.setProperty(key, rest);
      }
    }
  }

  /**
   * The files with the nation's settings under its new name: its color, its units' tint,
   * brightness and flip, its "has no capital", and the unit pictures imported for it. Its flags
   * and unit pictures in the folder keep the old name until the next save: {@link
   * #nationFilesName}.
   */
  MapFiles withRenamedNation(final String old, final String renamed) {
    return withNationAs(old, renamed, List.of(renamed));
  }

  /**
   * As {@link #withRenamedNation}, but the old name keeps its settings too: another game of the
   * map still has a nation of that name.
   */
  MapFiles withRenamedNationKeeping(final String old, final String renamed) {
    return withNationAs(old, renamed, List.of(old, renamed));
  }

  private MapFiles withNationAs(final String old, final String renamed, final List<String> put) {
    final Properties moved = copyOfProperties();
    for (final String key : NATION_KEYS) {
      final String value = properties.getProperty(key + old);
      if (value != null) {
        moved.remove(key + old);
        put.forEach(name -> moved.setProperty(key + name, value));
      }
    }
    renameInList(moved, NO_CAPITAL, old, put);
    moveFine(moved, old, put);
    final String inFolder = nationFilesName(old);
    moved.remove(FILES_OF + old);
    if (!inFolder.equals(renamed)) {
      moved.setProperty(FILES_OF + renamed, inFolder);
    }
    final Map<NationUnit, BufferedImage> pictures = new LinkedHashMap<>();
    nationUnitPictures.forEach(
        (key, image) ->
            pictures.put(
                key.nation().equals(old) ? new NationUnit(renamed, key.unit()) : key, image));
    return new MapFiles(
        folder, polygons, centers, moved, picture, unitPictures, pictures, relief, places);
  }

  /**
   * The name the nation's flags and unit pictures have in the map's folder: its own, or for one
   * renamed since the last save the name it had then.
   */
  String nationFilesName(final String nation) {
    return properties.getProperty(FILES_OF + nation, nation);
  }

  /** The nations renamed since the last save, each with its {@link #nationFilesName}. */
  Map<String, String> renamedNations() {
    final Map<String, String> renamed = new TreeMap<>();
    for (final String key : properties.stringPropertyNames()) {
      if (key.startsWith(FILES_OF)) {
        renamed.put(key.substring(FILES_OF.length()), properties.getProperty(key));
      }
    }
    return renamed;
  }

  /**
   * The files after a save moved nations' files in the folder: each of these nations with the
   * name its files have now, eg in an undo step from before a rename the name it was saved under.
   *
   * @param moved the new name by the name in the folder before the save
   */
  MapFiles withNationFilesMoved(final Map<String, String> moved, final List<String> nations) {
    final Properties named = copyOfProperties();
    for (final String nation : nations) {
      final String before = nationFilesName(nation);
      final String now = moved.getOrDefault(before, before);
      if (now.equals(nation)) {
        named.remove(FILES_OF + nation);
      } else {
        named.setProperty(FILES_OF + nation, now);
      }
    }
    return named.equals(properties)
        ? this
        : new MapFiles(
            folder,
            polygons,
            centers,
            named,
            picture,
            unitPictures,
            nationUnitPictures,
            relief,
            places);
  }

  /** The nations that have no capital on purpose, so the guide asks for none. */
  Set<String> nationsWithoutCapital() {
    return Stream.of(properties.getProperty(NO_CAPITAL, "").split(","))
        .filter(name -> !name.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** The files with the nation having no capital on purpose. */
  MapFiles withNoCapital(final String nation) {
    final Set<String> without = nationsWithoutCapital();
    return without.add(nation) ? withProperty(NO_CAPITAL, String.join(",", without)) : this;
  }

  /** The files with the nation wanting a capital again, as every nation does at first. */
  MapFiles withCapitalWanted(final String nation) {
    final Set<String> without = nationsWithoutCapital();
    return without.remove(nation) ? withProperty(NO_CAPITAL, String.join(",", without)) : this;
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

  private Optional<String> territoryAt(final Point point) {
    return territoryAt(point.x, point.y);
  }

  /**
   * The whole border between two territories: the pixels of the map between their outlines.
   *
   * @param box the part of the map the pixels are of
   * @param between row by row, which of the box's pixels lie between the two
   */
  record SharedBorder(String first, String second, Rectangle box, boolean[] between) {}

  /**
   * The two territories that meet at the point, if just two do there, eg under the mouse on their
   * border line; told by the outlines within a line's width around it.
   */
  Optional<List<String>> territoriesMeetingAt(final Point at, final int lineWidth) {
    final int reach = borderReach(lineWidth);
    final Set<String> around = new TreeSet<>();
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        territoryAt(at.x + dx * reach, at.y + dy * reach).ifPresent(around::add);
      }
    }
    return around.size() == 2 ? Optional.of(List.copyOf(around)) : Optional.empty();
  }

  /** How far from a territory's outline its border line's pixels may lie. */
  private static int borderReach(final int lineWidth) {
    // outlines made here meet in the line's middle; those of other tools end at its edges
    return Math.max(3, lineWidth + 2);
  }

  /**
   * The border between the two territories that meet at the point ({@link #territoriesMeetingAt}):
   * every pixel within a line's width of both, where each pixel is its smallest outline's as for
   * {@link #territoryAt}, but none that close to a third territory, whose own border runs there.
   * Empty where not just two meet.
   */
  Optional<SharedBorder> sharedBorderAt(final Point at, final int lineWidth) {
    return territoriesMeetingAt(at, lineWidth)
        .flatMap(two -> sharedBorder(two.getFirst(), two.getLast(), lineWidth));
  }

  private Optional<SharedBorder> sharedBorder(
      final String first, final String second, final int lineWidth) {
    final int reach = borderReach(lineWidth);
    final Rectangle firstBox = boundsOf(polygons.getOrDefault(first, List.of()));
    final Rectangle secondBox = boundsOf(polygons.getOrDefault(second, List.of()));
    firstBox.grow(reach + 3, reach + 3);
    secondBox.grow(reach + 3, reach + 3);
    final Rectangle box = firstBox.intersection(secondBox);
    if (box.isEmpty()) {
      return Optional.empty();
    }
    // whose each pixel is: the outlines drawn big to small, so the smallest one has the pixel
    final BufferedImage owners =
        new BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = owners.createGraphics();
    g.translate(-box.x, -box.y);
    polygons.entrySet().stream()
        .flatMap(e -> e.getValue().stream().map(outline -> Tuple.of(e.getKey(), outline)))
        .filter(outline -> outline.getSecond().getBounds().intersects(box))
        .sorted(
            Comparator.comparingDouble(
                (Tuple<String, Polygon> outline) -> {
                  final Rectangle bounds = outline.getSecond().getBounds();
                  return -(double) bounds.width * bounds.height;
                }))
        .forEach(
            outline -> {
              final String name = outline.getFirst();
              g.setColor(new Color(name.equals(first) ? 1 : name.equals(second) ? 2 : 3));
              g.fill(outline.getSecond());
            });
    g.dispose();
    final int[] owner = owners.getRGB(0, 0, box.width, box.height, null, 0, box.width);
    final boolean[][] of = new boolean[4][owner.length];
    for (int p = 0; p < owner.length; p++) {
      of[owner[p] & 3][p] = true;
    }
    final boolean[] nearFirst = MapPicture.dilated(of[1], box.width, reach);
    final boolean[] nearSecond = MapPicture.dilated(of[2], box.width, reach);
    // a third territory's border line reaches half its width from the territory
    final int half = (lineWidth + 1) / 2 + 1;
    final boolean[] nearOthers = MapPicture.dilated(of[3], box.width, half);
    // what is no territory and wider than a line, eg beyond the map's frame, has its line too;
    // the gap a line fills between outlines that end at its edges is not that wide
    final int thin = lineWidth / 2 + 1;
    final boolean[] territory = new boolean[owner.length];
    for (int p = 0; p < owner.length; p++) {
      territory[p] = !of[0][p];
    }
    final boolean[] nearTerritory = MapPicture.dilated(territory, box.width, thin);
    final boolean[] wide = new boolean[owner.length];
    for (int p = 0; p < owner.length; p++) {
      wide[p] = !nearTerritory[p];
    }
    final boolean[] nearOutside = MapPicture.dilated(wide, box.width, thin + half);
    final boolean[] between = new boolean[owner.length];
    boolean any = false;
    for (int p = 0; p < owner.length; p++) {
      between[p] = nearFirst[p] && nearSecond[p] && !nearOthers[p] && !nearOutside[p];
      any |= between[p];
    }
    return any ? Optional.of(new SharedBorder(first, second, box, between)) : Optional.empty();
  }

  /**
   * The territory at the point of the map: the one whose outline holds it, any of its outlines,
   * eg each island of a group; of several the smallest outline wins, so an island is hit and not
   * the sea zone drawn around it. Every click, tool tip and paint stroke asks this: an outline's
   * box is asked first (Polygon.contains), which leaves a handful to look at closely even on a
   * map of 700 territories.
   */
  Optional<String> territoryAt(final double x, final double y) {
    String found = null;
    double smallest = Double.MAX_VALUE;
    for (final Map.Entry<String, List<Polygon>> territory : polygons.entrySet()) {
      for (final Polygon outline : territory.getValue()) {
        if (outline.contains(x, y)) {
          final Rectangle box = outline.getBounds();
          final double size = (double) box.width * box.height;
          if (size < smallest) {
            smallest = size;
            found = territory.getKey();
          }
        }
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * The territory's area to fill: its outlines without the other territories' that lie inside
   * them, eg an island in a sea zone, whose outline has no hole for it; so the island is not
   * covered or tinted by the sea, whichever is drawn first. Asked once per change by who draws.
   */
  Shape filled(final String territory) {
    final List<Polygon> own = polygons.getOrDefault(territory, List.of());
    final Path2D shape = new Path2D.Float(Path2D.WIND_EVEN_ODD);
    own.forEach(outline -> shape.append(outline, false));
    final List<Polygon> inside =
        polygons.entrySet().stream()
            .filter(other -> !other.getKey().equals(territory))
            .flatMap(other -> other.getValue().stream())
            .filter(outline -> own.stream().anyMatch(around -> isInside(outline, around)))
            .toList();
    // one inside another of them, eg an island in a lake of an island, is no hole of its own
    inside.stream()
        .filter(outline -> inside.stream().noneMatch(around -> isInside(outline, around)))
        .forEach(outline -> shape.append(outline, false));
    return shape;
  }

  // the boxes first: only a handful of outlines are looked at closely
  private static boolean isInside(final Polygon outline, final Polygon around) {
    final Rectangle box = outline.getBounds();
    final Rectangle aroundBox = around.getBounds();
    return aroundBox.contains(box)
        && (long) box.width * box.height < (long) aroundBox.width * aroundBox.height
        && pointInside(outline).filter(around::contains).isPresent();
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

  /** The files with the territory's center, as centers.txt has it, at this point. */
  MapFiles withCenter(final String territory, final Point center) {
    if (center.equals(centers.get(territory))) {
      return this;
    }
    final Map<String, Point> moved = new LinkedHashMap<>(centers);
    moved.put(territory, new Point(center));
    return new MapFiles(
        folder,
        polygons,
        Collections.unmodifiableMap(moved),
        properties,
        picture,
        unitPictures,
        nationUnitPictures,
        relief,
        places);
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

  /** The files with the territory's texture, or with none. */
  MapFiles withTexture(final String territory, final Optional<String> texture) {
    return texture.orElse("").equals(properties.getProperty(TEXTURE + territory, ""))
        ? this
        : withProperty(TEXTURE + territory, texture.orElse(""));
  }

  MapFiles withoutNationColor(final String nation) {
    final Properties uncolored = copyOfProperties();
    uncolored.remove(PlayerColors.PROPERTY_COLOR_PREFIX + nation);
    uncolored.remove(FILES_OF + nation);
    renameInList(uncolored, NO_CAPITAL, nation, List.of());
    moveFine(uncolored, nation, List.of());
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

  // ---- the whole map bigger or smaller

  /**
   * Why the map cannot be resized by the factor, in plain words; empty when it can. A place file
   * that does not read would keep its places where they were, off the resized map.
   */
  Optional<String> whyNotResized(final double factor) {
    if (!places.unread().isEmpty()) {
      return Optional.of(
          String.join(", ", new TreeSet<>(places.unread()))
              + " does not read, so its places could not move with the map. Fix it in Files"
              + " first.");
    }
    final Dimension size = size();
    if (size.width <= 0 || size.height <= 0) {
      return Optional.of("The map has no size yet: load its picture first.");
    }
    final long width = Math.round(size.width * factor);
    final long height = Math.round(size.height * factor);
    if (width * height > MAX_HELD_PICTURE) {
      final int most =
          (int) (100 * Math.sqrt(MAX_HELD_PICTURE / ((double) size.width * size.height)));
      return Optional.of(
          "Too big: "
              + width
              + " × "
              + height
              + " pixels. This map can grow to "
              + most
              + " % at most.");
    }
    if (Math.min(width, height) < MIN_RESIZED_SIDE) {
      return Optional.of(
          "Too small: " + width + " × " + height + " pixels leave no room for territories.");
    }
    return Optional.empty();
  }

  /** The relief put together from its tiles, as big as the map; empty without or if unread. */
  Optional<BufferedImage> reliefTilesPicture() {
    final Dimension size = size();
    if (reliefTile(0, 0).isEmpty()
        || size.width <= 0
        || size.height <= 0
        || (long) size.width * size.height > MAX_HELD_PICTURE) {
      return Optional.empty();
    }
    final BufferedImage whole =
        new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = whole.createGraphics();
    try {
      drawTiles(g, size, this::reliefTile);
    } catch (final IOException e) {
      return Optional.empty();
    } finally {
      g.dispose();
    }
    return Optional.of(whole);
  }

  /**
   * The whole map bigger or smaller by the factor: the picture and relief held here, every
   * outline, center and place, map.width and height, the textures' offsets and the place of the
   * exported picture's title. Units, flags and names keep their size, so a place moves with the
   * middle of what the game draws there. The caller holds a picture or relief that is only in
   * tiles first ({@link MapProject#holdPicture}); takes long for a big picture.
   *
   * <p>A picture made bigger keeps each pixel as it is (nearest neighbour) when it is drawn in
   * flat colors, so its border lines stay crisp and one color, as Redraw borders needs them; a
   * painted one, every relief and every picture made smaller are smoothed, as leaving pixels out
   * would break thin lines.
   */
  MapFiles resized(final double factor) {
    final Dimension size = size();
    final int width = (int) Math.round(size.width * factor);
    final int height = (int) Math.round(size.height * factor);
    // by the sizes in whole pixels, so the coordinates end where the picture does
    final double fx = width / (double) size.width;
    final double fy = height / (double) size.height;
    final BiFunction<Point, Dimension, Point> about =
        (point, drawn) ->
            new Point(
                (int) Math.round((point.x + drawn.width / 2.0) * fx - drawn.width / 2.0),
                (int) Math.round((point.y + drawn.height / 2.0) * fy - drawn.height / 2.0));
    final Dimension none = new Dimension();
    final Map<String, List<Polygon>> outlines = new LinkedHashMap<>();
    polygons.forEach(
        (territory, shapes) ->
            outlines.put(
                territory,
                shapes.stream()
                    .map(
                        shape ->
                            new Polygon(
                                Arrays.stream(shape.xpoints)
                                    .limit(shape.npoints)
                                    .map(x -> (int) Math.round(x * fx))
                                    .toArray(),
                                Arrays.stream(shape.ypoints)
                                    .limit(shape.npoints)
                                    .map(y -> (int) Math.round(y * fy))
                                    .toArray(),
                                shape.npoints))
                    .toList()));
    final Map<String, Point> middles = new LinkedHashMap<>();
    centers.forEach((territory, center) -> middles.put(territory, about.apply(center, none)));
    final Dimension unit = unitSize();
    final Map<String, Tuple<List<Point>, Boolean>> units = new LinkedHashMap<>();
    places
        .units()
        .forEach(
            (territory, placed) ->
                units.put(
                    territory,
                    Tuple.of(
                        placed.getFirst().stream().map(p -> about.apply(p, unit)).toList(),
                        placed.getSecond())));
    final FontMetrics font = fontMetrics();
    final Map<Marker, Map<String, List<Point>>> markers = new EnumMap<>(Marker.class);
    places
        .markers()
        .forEach(
            (marker, byTerritory) -> {
              final Map<String, List<Point>> moved = new LinkedHashMap<>();
              byTerritory.forEach(
                  (territory, points) -> {
                    // what the game draws from the place: a flag or marker from its top left
                    // corner, a name from its left end; the rest at the place
                    final Dimension drawn =
                        switch (marker) {
                          case CAPITAL -> new Dimension(CAPITAL_FLAG_SIZE, CAPITAL_FLAG_SIZE);
                          case VICTORY_CITY -> new Dimension(VICTORY_CITY_SIZE, VICTORY_CITY_SIZE);
                          case NAME -> new Dimension(font.stringWidth(territory), 0);
                          default -> none;
                        };
                    moved.put(
                        territory, points.stream().map(p -> about.apply(p, drawn)).toList());
                  });
              markers.put(marker, Collections.unmodifiableMap(moved));
            });
    // shortcut: PU, blockade, convoy, kamikaze and territory effect markers (above) and
    // decorations are drawn from a corner too but move by it, as their pictures' sizes are not
    // read here, so they sit up to half a picture off; read the sizes from the map's PUs,
    // misc, flags and territoryEffects folders if that shows, till then move them in Territories'
    // Places
    final Map<String, List<Point>> decorations = new LinkedHashMap<>();
    places
        .decorations()
        .forEach(
            (picture, points) ->
                decorations.put(picture, points.stream().map(p -> about.apply(p, none)).toList()));
    final Properties sized = copyOfProperties();
    sized.setProperty(WIDTH, String.valueOf(width));
    sized.setProperty(HEIGHT, String.valueOf(height));
    for (final String key : properties.stringPropertyNames()) {
      if (key.startsWith(TEXTURE_OFFSET)) {
        final Point offset =
            about.apply(textureOffset(key.substring(TEXTURE_OFFSET.length())), none);
        sized.setProperty(key, offset.x + "," + offset.y);
      }
    }
    intProperty(MapData.PROPERTY_SCREENSHOT_TITLE_X)
        .ifPresent(
            x ->
                sized.setProperty(
                    MapData.PROPERTY_SCREENSHOT_TITLE_X, String.valueOf(Math.round(x * fx))));
    intProperty(MapData.PROPERTY_SCREENSHOT_TITLE_Y)
        .ifPresent(
            y ->
                sized.setProperty(
                    MapData.PROPERTY_SCREENSHOT_TITLE_Y, String.valueOf(Math.round(y * fy))));
    // the title's letters grow with the picture they are drawn on
    intProperty(MapData.PROPERTY_SCREENSHOT_TITLE_FONT_SIZE)
        .ifPresent(
            points ->
                sized.setProperty(
                    MapData.PROPERTY_SCREENSHOT_TITLE_FONT_SIZE,
                    String.valueOf(Math.max(1, Math.round(points * factor)))));
    return new MapFiles(
        folder,
        Collections.unmodifiableMap(outlines),
        Collections.unmodifiableMap(middles),
        sized,
        picture.map(p -> scaled(p, width, height, factor > 1 && isFlat(p))),
        unitPictures,
        nationUnitPictures,
        relief.map(r -> scaled(r, width, height, false)),
        new Places(
            Collections.unmodifiableMap(units),
            markers.isEmpty() ? Map.of() : Map.copyOf(markers),
            Collections.unmodifiableMap(decorations),
            places.unread()));
  }

  /**
   * Whether the picture is drawn in flat colors, as a map of lines and filled areas is: most of
   * its pixels are the color of the one beside them, which a painted or photographed one's are not.
   */
  static boolean isFlat(final BufferedImage image) {
    final int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 200);
    long same = 0;
    long all = 0;
    for (int y = 0; y < image.getHeight(); y += step) {
      for (int x = 0; x + 1 < image.getWidth(); x += step) {
        all++;
        if (image.getRGB(x, y) == image.getRGB(x + 1, y)) {
          same++;
        }
      }
    }
    return all > 0 && same >= FLAT_SHARE * all;
  }

  /** The picture at this size: crisp, each pixel as it is, or smoothed, halved step by step. */
  private static BufferedImage scaled(
      final BufferedImage image, final int width, final int height, final boolean crisp) {
    BufferedImage from = image;
    // a smoothing step looks at 2 by 2 pixels only: halve until the last step is less than half
    while (!crisp && from.getWidth() >= 2 * width && from.getHeight() >= 2 * height) {
      from = drawnAt(from, from.getWidth() / 2, from.getHeight() / 2, false);
    }
    return drawnAt(from, width, height, crisp);
  }

  private static BufferedImage drawnAt(
      final BufferedImage image, final int width, final int height, final boolean crisp) {
    final BufferedImage drawn = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = drawn.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        crisp
            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(image, 0, 0, width, height, null);
    g.dispose();
    return drawn;
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
   * Writes the places that changed here since the last save into place.txt, the markers' files and
   * decorations.txt, each as its line: every other line stays as it is, also of a territory the
   * game does not draw the marker for, as the map's other games share the files. So a save that
   * changed no place writes none of them. A territory removed here loses its lines, a new one gets
   * unit places found. A marker the game draws only since a change made here, eg of a new capital
   * or territory, gets its found place as a new line, in a new file if the map has none. Without a
   * folder or outlines, nothing is written.
   *
   * @param saved the files as last saved
   * @param savedGame the game as last saved
   */
  void writePlaces(final Game game, final MapFiles saved, final Game savedGame)
      throws IOException {
    if (folder.isEmpty() || !hasOutlines()) {
      return;
    }
    final Map<String, String> savedNames = savedNames(saved);
    // the game starts only with unit places for every territory
    writeKeeping(
        PLACEMENTS,
        places.units(),
        saved.places().units(),
        saved,
        t -> !savedNames.containsKey(t),
        t -> Tuple.of(unitPlaces(t), Boolean.FALSE),
        placed -> pointsText(placed.getFirst()) + " | overflowToLeft=" + placed.getSecond());
    final Set<String> changed = changedTerritories(game, savedGame, savedNames);
    for (final Marker marker : Marker.values()) {
      writeKeeping(
          marker.file,
          places.markers(marker),
          saved.places().markers(marker),
          saved,
          t ->
              changed.contains(t)
                  && marker.isOn(game, t)
                  && !(savedNames.containsKey(t) && marker.isOn(savedGame, savedNames.get(t))),
          t -> foundMarkers(game, marker, t),
          // the game reads one point but for the effects
          points -> pointsText(marker == Marker.EFFECT ? points : points.subList(0, 1)));
    }
    writeKeeping(
        DECORATIONS,
        places.decorations(),
        saved.places().decorations(),
        saved,
        picture -> false,
        picture -> List.of(),
        MapFiles::pointsText);
  }

  /**
   * Each territory's name at the last save; a new one has none. A renamed one is known by its
   * outlines, which a rename hands on as they are, so it does not count as new.
   */
  private Map<String, String> savedNames(final MapFiles saved) {
    final Map<List<Polygon>, String> gone = new IdentityHashMap<>();
    saved
        .polygons()
        .forEach(
            (name, outlines) -> {
              if (!polygons.containsKey(name)) {
                gone.put(outlines, name);
              }
            });
    final Map<String, String> names = new HashMap<>();
    polygons.forEach(
        (name, outlines) -> {
          if (saved.polygons().containsKey(name)) {
            names.put(name, name);
          } else if (gone.containsKey(outlines)) {
            names.put(name, gone.get(outlines));
          }
        });
    return names;
  }

  /**
   * The territories new since the last save or whose attachments read differently now, found in
   * one pass over each game's attachments: only for these a save asks whether a marker is drawn
   * now, as that reads all attachments again.
   */
  // shortcut: a marker drawn only since an owner or water change (a convoy's comment, a value) is
  // not seen; the game then places it itself
  private Set<String> changedTerritories(
      final Game game, final Game savedGame, final Map<String, String> savedNames) {
    final Set<String> changed = new HashSet<>(polygons.keySet());
    changed.removeAll(savedNames.keySet());
    if (game != savedGame) {
      final Map<String, List<String>> before = territoryAttachments(savedGame);
      final Map<String, List<String>> now = territoryAttachments(game);
      savedNames.forEach(
          (territory, savedName) -> {
            if (!Objects.equals(now.get(territory), before.get(savedName))) {
              changed.add(territory);
            }
          });
    }
    return changed;
  }

  /** Each territory's attachments as text, as an edit makes new ones also where nothing changed. */
  private static Map<String, List<String>> territoryAttachments(final Game game) {
    return AttachmentsXml.attachments(game).stream()
        .filter(a -> "territory".equals(a.getType()) && a.getAttachTo() != null)
        .collect(
            Collectors.groupingBy(
                Attachment::getAttachTo,
                Collectors.mapping(
                    a ->
                        a.getName()
                            + AttachmentsXml.options(a).stream()
                                .map(o -> o.getName() + "=" + o.getCount() + ":" + o.getValue())
                                .toList(),
                    Collectors.toList())));
  }

  /** Points as TripleA's PointFileReaderWriter writes them after a name. */
  private static String pointsText(final List<Point> points) {
    return points.stream()
        .map(point -> " (" + point.x + "," + point.y + ") ")
        .collect(Collectors.joining());
  }

  /**
   * Changes a file of points by name, line by line. A place changed here since the last save is
   * written as its line; one taken away here is found again while its territory is there, else its
   * line goes, as the lines of territories removed here do. A territory that needs a line and has
   * none gets a found one. All other lines stay, maybe set by hand meanwhile.
   *
   * @param had the places here
   * @param savedPlaces the places as last saved
   * @param needed whether the territory must have a line in the file
   */
  private <T> void writeKeeping(
      final String fileName,
      final Map<String, T> had,
      final Map<String, T> savedPlaces,
      final MapFiles saved,
      final Predicate<String> needed,
      final Function<String, T> found,
      final Function<T, String> text)
      throws IOException {
    final Path file = folder.orElseThrow().resolve(fileName);
    final PlaceLines lines = PlaceLines.read(file);
    final Set<String> names = new LinkedHashSet<>(savedPlaces.keySet());
    names.addAll(had.keySet());
    for (final String name : names) {
      if (Objects.equals(had.get(name), savedPlaces.get(name))) {
        continue;
      }
      if (had.containsKey(name)) {
        lines.set(name, text.apply(had.get(name)));
      } else if (polygons.containsKey(name) && (lines.has(name) || needed.test(name))) {
        lines.set(name, text.apply(found.apply(name)));
      } else {
        lines.remove(name);
      }
    }
    saved.polygons().keySet().stream()
        .filter(territory -> !polygons.containsKey(territory))
        .forEach(lines::remove);
    for (final String territory : polygons.keySet()) {
      if (!lines.has(territory) && needed.test(territory)) {
        lines.set(
            territory,
            text.apply(
                Optional.ofNullable(had.get(territory)).orElseGet(() -> found.apply(territory))));
      }
    }
    lines.write(file);
  }

  /** A file of points by name as its lines, changed one by one, so the others stay as written. */
  private static final class PlaceLines {
    // each with its line end
    private final List<String> lines = new ArrayList<>();
    private final Charset charset;
    private final String lineEnd;
    private boolean changed;

    private PlaceLines(final String text, final Charset charset) {
      this.charset = charset;
      lineEnd = text.contains("\r\n") || !text.contains("\n") ? "\r\n" : "\n";
      if (!text.isEmpty()) {
        lines.addAll(List.of(text.split("(?<=\n)|(?<=\r)(?!\n)")));
      }
    }

    /** The file's lines; of no file, none. A file that is not UTF-8 keeps its bytes. */
    static PlaceLines read(final Path file) throws IOException {
      if (!Files.exists(file)) {
        return new PlaceLines("", StandardCharsets.UTF_8);
      }
      final byte[] bytes = Files.readAllBytes(file);
      final Charset charset =
          MapFolder.isUtf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
      return new PlaceLines(new String(bytes, charset), charset);
    }

    /** The name a line gives points to, as PointFileReaderWriter reads it: up to the first "(". */
    private static String nameOf(final String line) {
      final int points = line.indexOf('(');
      return points < 0 ? "" : line.substring(0, points).trim();
    }

    boolean has(final String name) {
      return lines.stream().anyMatch(line -> name.equals(nameOf(line)));
    }

    /** The name's line with these points, at its place; a new one comes last. */
    void set(final String name, final String points) {
      final String line = name + " " + points;
      final int at =
          IntStream.range(0, lines.size())
              .filter(i -> name.equals(nameOf(lines.get(i))))
              .reduce((first, last) -> last)
              .orElse(-1);
      if (at < 0) {
        if (!lines.isEmpty() && !lines.getLast().matches("(?s).*[\r\n]")) {
          lines.set(lines.size() - 1, lines.getLast() + lineEnd);
        }
        lines.add(line);
        changed = true;
      } else if (!lines.get(at).stripTrailing().equals(line.stripTrailing())) {
        final String old = lines.get(at);
        lines.set(at, line + old.substring(old.replaceFirst("[\r\n]+$", "").length()));
        changed = true;
      }
    }

    void remove(final String name) {
      changed |= lines.removeIf(line -> name.equals(nameOf(line)));
    }

    /** Writes the file if a line changed, with its .bak; left without a line, it is removed. */
    void write(final Path file) throws IOException {
      if (!changed) {
        return;
      }
      final String text = String.join("", lines);
      if (text.isBlank()) {
        MapFolder.remove(file);
      } else {
        MapFolder.replace(file, to -> Files.write(to, text.getBytes(charset)));
      }
    }
  }

  @FunctionalInterface
  private interface PointsReader<T> {
    Map<String, T> read(Path file) throws IOException;
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

    @Getter private final String file;
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
     * Whether the map shows its place from the file where the game does not draw it now: names,
     * values and flags follow the game, but triggers may make any sea zone a convoy route or
     * kamikaze zone. A save keeps every marker's lines.
     */
    boolean keepsUnused() {
      return EnumSet.of(BLOCKADE, CONVOY, COMMENT, KAMIKAZE, EFFECT).contains(this);
    }

    /** How many the game draws: one, or one per effect. */
    int count(final Game game, final String territory) {
      return this == EFFECT ? Math.max(1, MapXml.territoryEffects(game, territory).size()) : 1;
    }

    private Map<String, List<Point>> read(final Path from) throws IOException {
      if (this == EFFECT) {
        return PointFileReaderWriter.readOneToMany(from);
      }
      final Map<String, List<Point>> points = new LinkedHashMap<>();
      PointFileReaderWriter.readOneToOne(from).forEach((t, point) -> points.put(t, List.of(point)));
      return points;
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
      Map<String, List<Point>> decorations,
      Set<String> unread) {
    static Places none() {
      return new Places(Map.of(), Map.of(), Map.of(), Set.of());
    }

    /**
     * The places in the folder's files; a file that does not read gives none, and a save leaves it
     * as it is.
     */
    static Places read(final Path folder) {
      final Set<String> unread = new HashSet<>();
      final Map<Marker, Map<String, List<Point>>> markers = new EnumMap<>(Marker.class);
      for (final Marker marker : Marker.values()) {
        markers.put(marker, readOrNone(folder.resolve(marker.file), marker::read, unread));
      }
      return new Places(
          readOrNone(
              folder.resolve(PLACEMENTS), PointFileReaderWriter::readOneToManyPlacements, unread),
          Map.copyOf(markers),
          readOrNone(folder.resolve(DECORATIONS), PointFileReaderWriter::readOneToMany, unread),
          Set.copyOf(unread));
    }

    /** The file's places; of one that is there but does not read none, its name noted as unread. */
    private static <T> Map<String, T> readOrNone(
        final Path file, final PointsReader<T> reader, final Set<String> unread) {
      if (!Files.exists(file)) {
        return Map.of();
      }
      try {
        return Collections.unmodifiableMap(reader.read(file));
      } catch (final IOException | IllegalArgumentException e) {
        log.warn("Could not read {}", file, e);
        unread.add(file.getFileName().toString());
        return Map.of();
      }
    }

    Map<String, List<Point>> markers(final Marker marker) {
      return markers.getOrDefault(marker, Map.of());
    }

    /** How many lines of the places' files differ between these and the other places. */
    int differing(final Places other) {
      int lines = differing(units, other.units) + differing(decorations, other.decorations);
      for (final Marker marker : Marker.values()) {
        lines += differing(markers(marker), other.markers(marker));
      }
      return lines;
    }

    private static <T> int differing(final Map<String, T> some, final Map<String, T> others) {
      final Set<String> names = new HashSet<>(some.keySet());
      names.addAll(others.keySet());
      return (int)
          names.stream().filter(n -> !Objects.equals(some.get(n), others.get(n))).count();
    }

    Places renamed(final String old, final String renamed) {
      final Map<Marker, Map<String, List<Point>>> moved = new EnumMap<>(Marker.class);
      markers.forEach(
          (marker, points) ->
              moved.put(marker, Collections.unmodifiableMap(renamedKey(points, old, renamed))));
      return new Places(
          Collections.unmodifiableMap(renamedKey(units, old, renamed)),
          Map.copyOf(moved),
          decorations,
          unread);
    }

    /** Without the places of these territories, so they are found again. */
    Places without(final Collection<String> territories) {
      final Map<Marker, Map<String, List<Point>>> kept = new EnumMap<>(Marker.class);
      markers.forEach((marker, points) -> kept.put(marker, without(points, territories)));
      return new Places(without(units, territories), Map.copyOf(kept), decorations, unread);
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
      return new Places(Collections.unmodifiableMap(changed), markers, decorations, unread);
    }

    Places withMarker(final Marker marker, final String territory, final List<Point> points) {
      final Map<String, List<Point>> placed = new LinkedHashMap<>(markers(marker));
      placed.put(territory, points);
      final Map<Marker, Map<String, List<Point>>> changed = new EnumMap<>(Marker.class);
      changed.putAll(markers);
      changed.put(marker, Collections.unmodifiableMap(placed));
      return new Places(units, Map.copyOf(changed), decorations, unread);
    }

    Places withDecorations(final String picture, final List<Point> points) {
      final Map<String, List<Point>> changed = new LinkedHashMap<>(decorations);
      if (points.isEmpty()) {
        changed.remove(picture);
      } else {
        changed.put(picture, List.copyOf(points));
      }
      return new Places(units, markers, Collections.unmodifiableMap(changed), unread);
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

  /**
   * The files with the territory's units placed here, in this order; for none, unchanged, as
   * while place.txt does not read ({@link #placesUnread}): a save would replace its lines.
   */
  MapFiles withUnitPlaces(final String territory, final List<Point> points) {
    if (placesUnread(PLACEMENTS) || points.isEmpty() || points.equals(unitPlaces(territory))) {
      return this;
    }
    final boolean toLeft =
        Optional.ofNullable(places.units().get(territory)).map(Tuple::getSecond).orElse(false);
    return withPlaces(places.withUnits(territory, Tuple.of(List.copyOf(points), toLeft)));
  }

  /** How a territory's unit places are laid out afresh around its center. */
  enum UnitLayout {
    /** A clockwise spiral from 12:00 outward. */
    SQUEEZE,
    /** In rows from 6:00, ordered by {@code gridOrder}. */
    GRID
  }

  /**
   * The files with these territories' unit places laid out afresh, at most so many each, with the
   * overflow direction {@link #unitPlacesLaidOut} chose; a territory without outlines is skipped.
   * Unchanged while place.txt does not read.
   */
  MapFiles withUnitPlacesLaidOut(
      final UnitLayout layout, final int most, final Collection<String> territories) {
    if (placesUnread(PLACEMENTS)) {
      return this;
    }
    Places laidOut = places;
    for (final String territory : territories) {
      if (polygons.containsKey(territory)) {
        laidOut = laidOut.withUnits(territory, unitPlacesLaidOut(layout, most, territory));
      }
    }
    return laidOut.equals(places) ? this : withPlaces(laidOut);
  }

  /**
   * The territory's unit places as the layout orders them, at most so many, and whether its extra
   * units go to the left. The places lie on a grid of unit squares with a {@link #UNIT_GUTTER}
   * around each; a place fits when its unit square (not the gutter) is wholly inside the outlines.
   * The grid's square on the center holds none, so units leave the center free. The last place is
   * the end of the bottom row with more room beyond it, where the extra units go. If none fits,
   * the found places.
   */
  Tuple<List<Point>, Boolean> unitPlacesLaidOut(
      final UnitLayout layout, final int most, final String territory) {
    final List<Polygon> outlines = polygons.getOrDefault(territory, List.of());
    final Dimension unit = unitSize();
    final boolean toLeftNow =
        Optional.ofNullable(places.units().get(territory)).map(Tuple::getSecond).orElse(false);
    if (outlines.isEmpty()) {
      return Tuple.of(unitPlaces(territory), toLeftNow);
    }
    final Rectangle bounds =
        outlines.stream().map(Polygon::getBounds).reduce(Rectangle::union).orElseThrow();
    final int width = Math.max(1, unit.width);
    final int height = Math.max(1, unit.height);
    final int stepX = width + 2 * UNIT_GUTTER;
    final int stepY = height + 2 * UNIT_GUTTER;
    final Point middle = middle(territory);
    // the corner of the center's square (0,0)
    final int left = middle.x - stepX / 2;
    final int top = middle.y - stepY / 2;
    final Function<Point, Point> place =
        cell -> new Point(left + cell.x * stepX + UNIT_GUTTER, top + cell.y * stepY + UNIT_GUTTER);
    // a sea's outline has no holes: the islands in it are other territories' outlines
    final List<Polygon> others =
        polygons.entrySet().stream()
            .filter(e -> !e.getKey().equals(territory))
            .flatMap(e -> e.getValue().stream())
            .filter(o -> o.getBounds().intersects(bounds))
            .toList();
    final Predicate<Point> fits =
        cell -> {
          final Rectangle square = new Rectangle(place.apply(cell), unit);
          return outlines.stream().anyMatch(o -> o.contains(square))
              && others.stream().noneMatch(o -> o.intersects(square));
        };
    // how many squares the grid reaches from the center's square to each side of the bounds
    final int toLeft = Math.max(0, Math.ceilDiv(left - bounds.x, stepX));
    final int toRight = Math.max(0, Math.ceilDiv(bounds.x + bounds.width - left - stepX, stepX));
    final int up = Math.max(0, Math.ceilDiv(top - bounds.y, stepY));
    final int down = Math.max(0, Math.ceilDiv(bounds.y + bounds.height - top - stepY, stepY));
    final List<Point> cells =
        layout == UnitLayout.SQUEEZE
            ? spiralOrder(Math.max(Math.max(toLeft, toRight), Math.max(up, down)))
            : gridOrder(toLeft, toRight, up, down);
    final List<Point> kept =
        new ArrayList<>(cells.stream().filter(fits).limit(Math.max(1, most)).toList());
    // where the grid leaves room unused, eg in a narrow or slanted shape: places off the grid
    final List<Point> offGrid =
        placesOffGrid(
            territory,
            others,
            place.apply(new Point()),
            kept.stream().map(place).toList(),
            Math.max(1, most) - kept.size());
    if (kept.isEmpty()) {
      if (!offGrid.isEmpty()) {
        return Tuple.of(offGrid, toLeftNow);
      }
      final List<Point> found = placesInside(outlines, centers.get(territory), unit);
      return Tuple.of(found.subList(0, Math.min(found.size(), Math.max(1, most))), toLeftNow);
    }
    final int bottom = kept.stream().mapToInt(cell -> cell.y).max().orElseThrow();
    final List<Point> bottomRow = kept.stream().filter(cell -> cell.y == bottom).toList();
    final Point leftEnd = bottomRow.stream().min(Comparator.comparingInt(c -> c.x)).orElseThrow();
    final Point rightEnd = bottomRow.stream().max(Comparator.comparingInt(c -> c.x)).orElseThrow();
    final boolean overflowToLeft = roomBeyond(leftEnd, -1, fits) > roomBeyond(rightEnd, 1, fits);
    final Point last = overflowToLeft ? leftEnd : rightEnd;
    kept.remove(last);
    // the grid's places, then those off it, then the place the extra units go on from
    final List<Point> laidOut = new ArrayList<>(kept.stream().map(place).toList());
    laidOut.addAll(offGrid);
    laidOut.add(place.apply(last));
    return Tuple.of(List.copyOf(laidOut), overflowToLeft);
  }


  /**
   * Up to so many more places for the territory's units, off the layout's grid, nearest the
   * center first: positions a quarter step apart whose unit square is wholly inside the outlines,
   * on no other territory, off the center's square and {@link #UNIT_GUTTER} px from every other
   * place. If the territory then has fewer than {@value #FEW_PLACES} places, also squares that
   * stick out over its border, with most of the square inside and off the center's square: first
   * those on no other territory's center or set unit places, then, if still too few, any.
   *
   * @param centerSquare the place of the grid's square on the center, which stays free
   * @param taken the places the grid gave
   */
  private List<Point> placesOffGrid(
      final String territory,
      final List<Polygon> others,
      final Point centerSquare,
      final List<Point> taken,
      final int most) {
    final List<Polygon> outlines = polygons.getOrDefault(territory, List.of());
    final Dimension unit = unitSize();
    final Rectangle bounds =
        outlines.stream().map(Polygon::getBounds).reduce(Rectangle::union).orElseThrow();
    final int stepX = Math.max(1, (unit.width + 2 * UNIT_GUTTER) / 4);
    final int stepY = Math.max(1, (unit.height + 2 * UNIT_GUTTER) / 4);
    final int columns = bounds.width / stepX + 2;
    final int rows = bounds.height / stepY + 2;
    if (most <= 0 || (long) columns * rows > MOST_OFF_GRID) {
      return List.of();
    }
    final Rectangle center = new Rectangle(centerSquare, unit);
    final Point middle = new Point((int) center.getCenterX(), (int) center.getCenterY());
    final List<Point> candidates = new ArrayList<>();
    // on the grid's own lines, so a place beside a grid place can stand right at its gutter
    for (int x = centerSquare.x - Math.ceilDiv(centerSquare.x - bounds.x + unit.width, stepX) * stepX;
        x < bounds.x + bounds.width;
        x += stepX) {
      for (int y =
              centerSquare.y - Math.ceilDiv(centerSquare.y - bounds.y + unit.height, stepY) * stepY;
          y < bounds.y + bounds.height;
          y += stepY) {
        candidates.add(new Point(x, y));
      }
    }
    candidates.sort(
        Comparator.comparingDouble(
            p -> middle.distanceSq(p.x + unit.width / 2.0, p.y + unit.height / 2.0)));
    final List<Rectangle> placed =
        new ArrayList<>(taken.stream().map(p -> new Rectangle(p, unit)).toList());
    final List<Point> added = new ArrayList<>();
    final Predicate<Rectangle> apart =
        square -> {
          final Rectangle grown = new Rectangle(square);
          grown.grow(UNIT_GUTTER, UNIT_GUTTER);
          return placed.stream().noneMatch(grown::intersects);
        };
    final Predicate<Rectangle> inside =
        square ->
            !square.intersects(center)
                && outlines.stream().anyMatch(o -> o.contains(square))
                && others.stream().noneMatch(o -> o.intersects(square));
    // most of the square inside: its middle and at least 6 of 9 points spread over it
    final Predicate<Rectangle> mostlyInside =
        square ->
            !square.intersects(center)
                && outlines.stream()
                    .anyMatch(o -> o.contains(square.getCenterX(), square.getCenterY()))
                && IntStream.range(0, 9)
                        .filter(
                            i ->
                                outlines.stream()
                                    .anyMatch(
                                        o ->
                                            o.contains(
                                                square.x + square.width * (1 + 2 * (i % 3)) / 6.0,
                                                square.y + square.height * (1 + 2 * (i / 3)) / 6.0)))
                        .count()
                    >= 6;
    final List<Rectangle> othersOwn = new ArrayList<>();
    centers.forEach(
        (name, point) -> {
          if (!name.equals(territory)) {
            othersOwn.add(new Rectangle(point.x, point.y, 1, 1));
          }
        });
    places
        .units()
        .forEach(
            (name, set) -> {
              if (!name.equals(territory)) {
                set.getFirst().forEach(p -> othersOwn.add(new Rectangle(p, unit)));
              }
            });
    final Predicate<Rectangle> offOthers =
        square -> othersOwn.stream().noneMatch(square::intersects);
    final List<Predicate<Rectangle>> rules =
        List.of(inside, mostlyInside.and(offOthers), mostlyInside);
    for (int rule = 0; rule < rules.size(); rule++) {
      // squares that stick out only for a territory that would have too few places
      final int wanted = rule == 0 ? most : Math.min(most, FEW_PLACES - taken.size());
      for (final Point candidate : candidates) {
        if (added.size() >= wanted) {
          break;
        }
        final Rectangle square = new Rectangle(candidate, unit);
        if (rules.get(rule).test(square) && apart.test(square)) {
          placed.add(square);
          added.add(candidate);
        }
      }
    }
    return List.copyOf(added);
  }

  /** The free squares inside the outlines on from this end of its row, one after another. */
  private static int roomBeyond(final Point end, final int dx, final Predicate<Point> fits) {
    int free = 0;
    // the outlines end, so the row of fitting squares does
    for (Point next = new Point(end.x + dx, end.y); fits.test(next); next.translate(dx, 0)) {
      free++;
    }
    return free;
  }

  /**
   * Squeeze's order of grid squares (x right, y down, the center's square 0,0 left out): ring by
   * ring outward, each clockwise from 12:00.
   */
  private static List<Point> spiralOrder(final int rings) {
    final List<Point> cells = new ArrayList<>();
    for (int k = 1; k <= rings; k++) {
      for (int x = 0; x <= k; x++) {
        cells.add(new Point(x, -k));
      }
      for (int y = -k + 1; y <= k; y++) {
        cells.add(new Point(k, y));
      }
      for (int x = k - 1; x >= -k; x--) {
        cells.add(new Point(x, k));
      }
      for (int y = k - 1; y >= -k; y--) {
        cells.add(new Point(-k, y));
      }
      for (int x = -k + 1; x < 0; x++) {
        cells.add(new Point(x, -k));
      }
    }
    return cells;
  }

  /**
   * Grid's order of grid squares (x right, y down, the center's square 0,0 left out), the one place
   * that says it. The first row is the one below the center, from 6:00 outward, left first, so
   * left to right it reads 4 2 1 3 5; then the rows further below, then the center's row, then the
   * rows above, nearest first; those each left to right.
   */
  private static List<Point> gridOrder(
      final int toLeft, final int toRight, final int up, final int down) {
    final List<Point> cells = new ArrayList<>();
    if (down > 0) {
      for (int x = 0; x <= Math.max(toLeft, toRight); x++) {
        if (x > 0 && x <= toLeft) {
          cells.add(new Point(-x, 1));
        }
        if (x <= toRight) {
          cells.add(new Point(x, 1));
        }
      }
    }
    final List<Integer> rows = new ArrayList<>();
    IntStream.rangeClosed(2, down).forEach(rows::add);
    rows.add(0);
    IntStream.rangeClosed(1, up).forEach(y -> rows.add(-y));
    for (final int y : rows) {
      for (int x = -toLeft; x <= toRight; x++) {
        if (x != 0 || y != 0) {
          cells.add(new Point(x, y));
        }
      }
    }
    return cells;
  }

  /**
   * The files with the marker's point of this number (0 but for effects) at the point given;
   * unchanged while the marker's file does not read.
   */
  MapFiles withMarkerPlace(
      final Game game,
      final Marker marker,
      final String territory,
      final int index,
      final Point point) {
    if (placesUnread(marker.file)) {
      return this;
    }
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
   * The territories on the map without a line in place.txt: the game starts only with unit places
   * for every territory. None while the file does not read ({@link #placesUnread}): its lines are
   * not known, and places set here would replace them.
   */
  List<String> withoutUnitPlaces() {
    return placesUnread(PLACEMENTS)
        ? List.of()
        : polygons.keySet().stream().filter(t -> !places.units().containsKey(t)).toList();
  }

  /**
   * The territories on the map the game draws the marker for that have no line in its file; none
   * while the file does not read.
   *
   * @param game asked about every territory: for many, give {@link
   *     MapXml#withTerritoryAttachmentsOnly}
   */
  List<String> withoutMarkerPlace(final Game game, final Marker marker) {
    if (placesUnread(marker.file)) {
      return List.of();
    }
    final Map<String, List<Point>> placed = places.markers(marker);
    return polygons.keySet().stream()
        .filter(t -> placed.getOrDefault(t, List.of()).isEmpty() && marker.isOn(game, t))
        .toList();
  }

  /** Whether the place file of this name is there but does not read, eg for a name twice. */
  boolean placesUnread(final String fileName) {
    return places.unread().contains(fileName);
  }

  /**
   * How many territories miss their line in the place file of this name, place.txt or a marker's;
   * 0 for another file.
   */
  int missingPlaces(final Game game, final String fileName) {
    return PLACEMENTS.equals(fileName)
        ? withoutUnitPlaces().size()
        : Stream.of(Marker.values())
            .filter(marker -> marker.file.equals(fileName))
            .mapToInt(marker -> withoutMarkerPlace(game, marker).size())
            .sum();
  }

  /**
   * The files with the found unit places set for each territory that has none in place.txt, so a
   * save writes them; places there stay. Unchanged when none is missing.
   */
  MapFiles withMissingUnitPlaces() {
    Places filled = places;
    for (final String territory : withoutUnitPlaces()) {
      filled = filled.withUnits(territory, Tuple.of(unitPlaces(territory), Boolean.FALSE));
    }
    return filled == places ? this : withPlaces(filled);
  }

  /** As {@link #withMissingUnitPlaces} for the marker, where the game draws it. */
  MapFiles withMissingMarkerPlaces(final Game game, final Marker marker) {
    Places filled = places;
    for (final String territory : withoutMarkerPlace(game, marker)) {
      filled = filled.withMarker(marker, territory, foundMarkers(game, marker, territory));
    }
    return filled == places ? this : withPlaces(filled);
  }

  /**
   * The files with the other territory's outlines added to the keeper's, which keeps its center
   * and places; the other goes with its center, places and texture.
   */
  MapFiles withMerged(final String keeper, final String other) {
    final List<Polygon> outlines = new ArrayList<>(polygons.getOrDefault(keeper, List.of()));
    outlines.addAll(polygons.getOrDefault(other, List.of()));
    return withOutlines(keeper, outlines)
        .withoutTerritory(other)
        .withPlaces(places.without(List.of(other)));
  }

  /** The area inside the territory's outlines in square pixels; 0 without an outline. */
  long area(final String territory) {
    double area = 0;
    for (final Polygon outline : polygons.getOrDefault(territory, List.of())) {
      double twice = 0;
      for (int i = 0; i < outline.npoints; i++) {
        final int next = (i + 1) % outline.npoints;
        twice +=
            (double) outline.xpoints[i] * outline.ypoints[next]
                - (double) outline.xpoints[next] * outline.ypoints[i];
      }
      area += Math.abs(twice) / 2;
    }
    return Math.round(area);
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

  /**
   * The files with the places as the folder's files have them now, eg changed by hand or with
   * another program while the map is open; a territory renamed since the last save keeps its
   * line's places under its new name. Unchanged when they are the places here, or without a
   * folder.
   *
   * @param saved the files as last saved
   */
  MapFiles withPlacesReloaded(final MapFiles saved) {
    if (folder.isEmpty()) {
      return this;
    }
    Places read = Places.read(folder.get());
    for (final Map.Entry<String, String> name : savedNames(saved).entrySet()) {
      if (!name.getKey().equals(name.getValue())) {
        read = read.renamed(name.getValue(), name.getKey());
      }
    }
    return read.equals(places) ? this : withPlaces(read);
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
   * The size the map's unit pictures are made for, map.properties units.width and height (48
   * without); the game draws each picture at its own size by units.scale.
   */
  Dimension unitPictureSize() {
    final int size = UnitImageFactory.DEFAULT_UNIT_ICON_SIZE;
    return new Dimension(
        intProperty(MapData.PROPERTY_UNITS_WIDTH).filter(width -> width > 0).orElse(size),
        intProperty(MapData.PROPERTY_UNITS_HEIGHT).filter(height -> height > 0).orElse(size));
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
    final List<Point> inside = squaresInside(outlines, center, unit);
    if (!inside.isEmpty()) {
      return inside;
    }
    final Rectangle bounds =
        outlines.stream()
            .map(Polygon::getBounds)
            .reduce(Rectangle::union)
            .orElseGet(Rectangle::new);
    final Point middle =
        Optional.ofNullable(center)
            .orElseGet(() -> new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
    return List.of(
        new Point(
            middle.x - Math.max(1, unit.width) / 2, middle.y - Math.max(1, unit.height) / 2));
  }

  /** As {@link #placesInside}, none when no unit square fits. */
  private static List<Point> squaresInside(
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
    return List.copyOf(places.subList(0, Math.min(places.size(), MAX_PLACES)));
  }

  /**
   * How many unit stacks fit side by side wholly inside each of these territories at the units'
   * size now, at most {@value #MAX_PLACES}; 0 where not even one does. Territories without
   * outlines are left out.
   */
  Map<String, Integer> unitRoom(final Collection<String> territories) {
    final Dimension unit = unitSize();
    final Map<String, Integer> room = new HashMap<>();
    for (final String territory : territories) {
      final List<Polygon> outlines = polygons.get(territory);
      if (outlines != null) {
        room.put(territory, squaresInside(outlines, centers.get(territory), unit).size());
      }
    }
    return Map.copyOf(room);
  }

  /**
   * Writes map.properties into the map's folder key by key: an entry that differs from the last
   * save gets its value in its line, a new one is added at the end, one that is gone loses its
   * line. Comments, the order and every other line stay as they are in the file, also what was
   * changed there by hand meanwhile ({@link MapTexts#withEntries}, which writes the entries anew
   * rather than lose one, should the file not read as meant after the change).
   *
   * @param before the entries as last saved; none for a map saved for the first time
   */
  void writeProperties(final Properties before) throws IOException {
    if (folder.isEmpty()) {
      return;
    }
    final Path file = folder.get().resolve(MAP_PROPERTIES);
    // a new file starts with a line, as a map without settings yet still gets its file
    final String text =
        Files.exists(file) ? MapFolder.readText(file) : "# Written by Eddie's Map Editor\n";
    final Properties inFile = new Properties();
    try {
      inFile.load(new StringReader(text));
    } catch (final IllegalArgumentException e) {
      // a file the game cannot read either, eg a broken unicode escape: every change is written
      inFile.clear();
    }
    // sorted, so new entries come in the same order each time
    final Map<String, String> changed = new TreeMap<>();
    for (final String key : properties.stringPropertyNames()) {
      final String value = properties.getProperty(key);
      if (!key.startsWith(FILES_OF)
          && !value.equals(before.getProperty(key))
          && !value.equals(inFile.getProperty(key))) {
        changed.put(key, value);
      }
    }
    final String written =
        MapTexts.withEntries(
            text,
            changed,
            before.stringPropertyNames().stream()
                .filter(k -> !properties.containsKey(k) && !k.startsWith(FILES_OF))
                .toList());
    if (!written.equals(text) || !Files.exists(file)) {
      MapFolder.replaceText(file, written);
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
    return listed(UNTINTED_UNITS);
  }

  /**
   * The names a map.properties list holds, eg {@link #HIDDEN_NAMES}: split at commas and taken as
   * they are, as the game reads them.
   */
  List<String> listed(final String property) {
    return Arrays.stream(properties.getProperty(property, "").split(","))
        .filter(name -> !name.isEmpty())
        .toList();
  }

  /** With the name in the list, after the others; the same files when it is there. */
  MapFiles withListed(final String property, final String name) {
    final List<String> names = listed(property);
    return names.contains(name)
        ? this
        : withProperty(
            property,
            Stream.concat(names.stream(), Stream.of(name)).collect(Collectors.joining(",")));
  }

  /** Without the name in the list, the others kept; a list left empty goes with its key. */
  MapFiles withoutListed(final String property, final String name) {
    final List<String> names = listed(property);
    return names.contains(name)
        ? withProperty(
            property, names.stream().filter(n -> !n.equals(name)).collect(Collectors.joining(",")))
        : this;
  }

  /** Each territory's texture, by territory, {@link Textures}. */
  Map<String, String> textures() {
    final Map<String, String> textures = new HashMap<>();
    for (final String key : properties.stringPropertyNames()) {
      if (key.startsWith(TEXTURE)) {
        textures.put(key.substring(TEXTURE.length()), properties.getProperty(key).strip());
      }
    }
    return Map.copyOf(textures);
  }

  /** Where the texture's pictures start on the map, "x,y" in map.properties; 0,0 if unset. */
  Point textureOffset(final String texture) {
    final String[] parts = properties.getProperty(TEXTURE_OFFSET + texture, "").split(",");
    try {
      return parts.length == 2
          ? new Point(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()))
          : new Point();
    } catch (final NumberFormatException e) {
      return new Point();
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

  /**
   * The picture put together from its tiles, as big as map.properties says (else as the tiles
   * reach); empty without tiles, for one that does not read, or one too big to hold in memory.
   */
  Optional<BufferedImage> tilesPicture() {
    return tilesPicture(MAX_HELD_PICTURE);
  }

  /**
   * As {@link #tilesPicture} to draw borders into, also a picture too big for the other uses,
   * which make copies of it, while the memory Java may take has room for it ({@link
   * #memoryMissing}); lines are then drawn into it without undo ({@link #drawsInPlace}).
   */
  Optional<BufferedImage> tilesPictureToDrawInto() {
    final Dimension size = size();
    final long pixels = (long) size.width * size.height;
    if (pixels <= MAX_HELD_PICTURE) {
      return tilesPicture();
    }
    try {
      return memoryMissing(pixels, freeMemory()) > 0
          ? Optional.empty()
          : tilesPicture(Long.MAX_VALUE);
    } catch (final OutOfMemoryError e) {
      // less room than Java said, eg taken meanwhile: as too big
      return Optional.empty();
    }
  }

  /**
   * Why {@link #tilesPictureToDrawInto} gives no picture though the map has tiles: how much
   * memory is missing for one this big, else that a tile does not read.
   */
  String whyNotDrawnInto() {
    final Dimension size = size();
    final long missing = memoryMissing((long) size.width * size.height, freeMemory());
    return missing > 0
        ? "The map's picture ("
            + size.width
            + " × "
            + size.height
            + ") is too big to draw into: the editor would need about "
            + (missing + 999_999) / 1_000_000
            + " MB more memory. Close other programs and open the map again, or start the"
            + " editor's Java with more (-Xmx)."
        : "One of the map's picture tiles does not read.";
  }

  /** The bytes Java may still take: its most, less what it uses now. */
  private static long freeMemory() {
    final Runtime runtime = Runtime.getRuntime();
    return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
  }

  /**
   * How many bytes are missing to hold a picture of so many pixels (4 bytes each) with what the
   * map on screen needs beside it; 0 when it fits.
   */
  static long memoryMissing(final long pixels, final long freeBytes) {
    return Math.max(0, 4 * pixels + BESIDE_PICTURE_BYTES - freeBytes);
  }

  /**
   * Whether lines are drawn into the picture held itself: it has more pixels than a copy per
   * line, which an undo needs, may take.
   */
  boolean drawsInPlace() {
    return picture
        .filter(p -> (long) p.getWidth() * p.getHeight() > undoPictureLimit)
        .isPresent();
  }

  private Optional<BufferedImage> tilesPicture(final long mostPixels) {
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
    if (width <= 0 || height <= 0 || (long) width * height > mostPixels) {
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

  /** The map's picture without its relief, loaded or put together from its tiles. */
  Optional<BufferedImage> basePicture() {
    return picture.or(this::tilesPicture);
  }

  /** A territory's part of the map's picture and where its top left corner lies on the map. */
  record TerritoryPicture(BufferedImage picture, Point at) {}

  /**
   * The territory's part of the map, see-through outside its outlines, eg to draw on in a paint
   * program and lay back over the map at the same place; empty without outlines on the map.
   */
  Optional<TerritoryPicture> territoryPicture(final BufferedImage map, final String territory) {
    final Area area = new Area();
    polygons.getOrDefault(territory, List.of()).forEach(p -> area.add(new Area(p)));
    final Rectangle bounds =
        area.getBounds().intersection(new Rectangle(map.getWidth(), map.getHeight()));
    if (bounds.isEmpty()) {
      return Optional.empty();
    }
    final BufferedImage cut =
        new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = cut.createGraphics();
    g.translate(-bounds.x, -bounds.y);
    g.setClip(area);
    g.drawImage(map, 0, 0, null);
    g.dispose();
    return Optional.of(new TerritoryPicture(cut, bounds.getLocation()));
  }

  /** Whether the map has a picture or a relief, loaded or in tiles. */
  boolean hasPictureOrRelief() {
    return picture.isPresent()
        || relief.isPresent()
        || tile(0, 0).isPresent()
        || reliefTile(0, 0).isPresent();
  }

  /**
   * The map as players see it, its picture with the relief over it, loaded or put together from
   * tiles (a missing one left see-through); empty without either, or too big to hold in memory.
   */
  Optional<BufferedImage> shownPicture() {
    final Dimension size = size();
    if (!hasPictureOrRelief()
        || size.width <= 0
        || size.height <= 0
        || (long) size.width * size.height > MAX_HELD_PICTURE) {
      return Optional.empty();
    }
    final BufferedImage shown =
        new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = shown.createGraphics();
    try {
      if (picture.isPresent()) {
        g.drawImage(picture.get(), 0, 0, null);
      } else {
        drawTiles(g, size, this::tile);
      }
      if (relief.isPresent()) {
        g.drawImage(relief.get(), 0, 0, null);
      } else {
        drawTiles(g, size, this::reliefTile);
      }
    } catch (final IOException e) {
      return Optional.empty();
    } finally {
      g.dispose();
    }
    return Optional.of(shown);
  }

  private static void drawTiles(
      final Graphics2D g,
      final Dimension size,
      final BiFunction<Integer, Integer, Optional<Path>> tileAt)
      throws IOException {
    final int tileSize = tileSize();
    for (int column = 0; column * tileSize < size.width; column++) {
      for (int row = 0; row * tileSize < size.height; row++) {
        final Optional<Path> file = tileAt.apply(column, row);
        if (file.isPresent()) {
          final BufferedImage read = ImageIO.read(file.get().toFile());
          if (read != null) {
            g.drawImage(read, column * tileSize, row * tileSize, null);
          }
        }
      }
    }
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

  /** Whether the game's map goes on at the other side, left and right: so unless set to false. */
  boolean wrapsX() {
    return Boolean.parseBoolean(
        properties.getProperty(MapData.PROPERTY_MAP_SCROLLWRAPX, "true").strip());
  }

  /** Whether the game's map goes on at the other side, top and bottom: only when set. */
  boolean wrapsY() {
    return Boolean.parseBoolean(
        properties.getProperty(MapData.PROPERTY_MAP_SCROLLWRAPY, "false").strip());
  }

  static int tileSize() {
    return TileManager.TILE_SIZE;
  }
}
