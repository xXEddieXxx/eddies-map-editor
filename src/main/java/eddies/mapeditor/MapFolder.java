package eddies.mapeditor;

import games.strategy.engine.framework.CliProperties;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ResourceLoader;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import lombok.experimental.UtilityClass;
import org.triplea.game.ApplicationContext;
import org.triplea.io.FileUtils;
import org.triplea.map.data.elements.DiceSides;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.TerritoryEffectList;
import org.triplea.map.description.file.MapDescriptionYaml;
import org.triplea.map.description.file.MapDescriptionYaml.MapGame;
import org.triplea.util.Services;
import org.triplea.yaml.YamlReader;
import org.triplea.yaml.YamlWriter;

/**
 * What the map maker does with a map's folder: its games, a new map's folder, a new game in it, the
 * picture in tiles, flags and unit pictures for new nations, a zip of it, a test game.
 */
@UtilityClass
class MapFolder {
  private static final int SMALL_MAP_WIDTH = 250;
  private static final int PREVIEW_WIDTH = 960;
  private static final int UNIT_SIZE = 48;
  // the file name endings and sizes of the game's flags, eg flags/Germans_small.png
  private static final Map<String, Integer> FLAG_SIZES =
      Map.of("", 32, "_small", 12, "_large", 100);
  private static final String BASE_NATION = "Germans";

  /**
   * A copy of the game XML under a new name beside it, listed in the map's map.yml so that it shows
   * among the games.
   *
   * @return the new game XML
   * @throws IOException also when a game file of that name is there already
   */
  static Path newGame(final Path template, final String gameName) throws IOException {
    final String fileName = safeName(gameName) + ".xml";
    final Path xml = template.resolveSibling(fileName);
    if (Files.exists(xml)) {
      throw new IOException("There is a game file " + fileName + " already");
    }
    final Optional<Path> yamlFile =
        FileUtils.findFileInParentFolders(template, MapDescriptionYaml.MAP_YAML_FILE_NAME);
    // read first: a map.yml that does not read leaves no game file behind
    final Optional<Map<String, Object>> yaml =
        yamlFile.isPresent() ? Optional.of(readYaml(yamlFile.get())) : Optional.empty();
    Files.copy(template, xml);
    if (yamlFile.isPresent()) {
      final List<Object> games = new ArrayList<>();
      if (yaml.get().get("games") instanceof List<?> listed) {
        games.addAll(listed);
      }
      final Map<String, Object> game = new LinkedHashMap<>();
      game.put("game_name", gameName.strip());
      // the file name only: the game looks it up in the map's games folder
      game.put("file_name", xml.getFileName().toString());
      games.add(game);
      yaml.get().put("games", games);
      try {
        replaceText(yamlFile.get(), YamlWriter.writeToString(yaml.get()));
      } catch (final IOException e) {
        // a game file map.yml does not list would only confuse: take it back
        Files.deleteIfExists(xml);
        throw e;
      }
    }
    return xml;
  }

  /**
   * The map's own folder: the one with its map.yml, which may keep the map's files in a folder
   * below it (eg map/); else this folder.
   */
  static Path rootOf(final Path folder) {
    // as close as map.yml sits to a map's games, eg map/games/; a map.yml further up is another's
    Path candidate = folder.toAbsolutePath();
    for (int up = 0; candidate != null && up <= 2; up++, candidate = candidate.getParent()) {
      if (Files.isRegularFile(candidate.resolve(MapDescriptionYaml.MAP_YAML_FILE_NAME))) {
        return candidate;
      }
    }
    return folder;
  }

  /**
   * The games of the map in this folder, or in the folder of the map it belongs to: by the names
   * its map.yml gives them, in that order; without them the XML files in its games folders, by file
   * name.
   */
  static Map<String, Path> gamesOf(final Path folder) throws IOException {
    final Path root = rootOf(folder);
    final Map<String, Path> games = new LinkedHashMap<>();
    MapDescriptionYaml.fromMap(root)
        .ifPresent(
            yaml ->
                yaml.getMapGameList()
                    .forEach(
                        game ->
                            yaml.getGameXmlPathByGameName(game.getGameName())
                                .ifPresent(xml -> games.put(game.getGameName(), xml))));
    if (games.isEmpty()) {
      try (Stream<Path> files =
          Files.find(
              root,
              4,
              (path, attributes) ->
                  attributes.isRegularFile()
                      && path.getFileName().toString().endsWith(".xml")
                      // a drive's top has no name: valueOf gives "null"
                      && "games".equals(String.valueOf(path.getParent().getFileName())))) {
        files.sorted().forEach(xml -> games.put(xml.getFileName().toString(), xml));
      } catch (final UncheckedIOException e) {
        // a folder the walk cannot read
        throw e.getCause();
      }
    }
    return games;
  }

  /** The name as a file or folder name: letters, digits, '_' and '-' only, in lower case. */
  private static String safeName(final String name) {
    return name.strip().replaceAll("[^A-Za-z0-9_-]+", "_").toLowerCase(Locale.ROOT);
  }

  /**
   * Why a new map of this name cannot be made in the maps folder: a name without letters or digits,
   * or a map folder of that name there already; empty when it can.
   */
  static Optional<String> whyNoNewMap(final Path mapsFolder, final String mapName) {
    final String name = safeName(mapName);
    if (!name.matches(".*[a-z0-9].*")) {
      return Optional.of("The name needs letters or digits");
    }
    return Files.exists(mapsFolder.resolve(name))
        ? Optional.of("There is a map folder " + name + " already")
        : Optional.empty();
  }

  /**
   * A new map's folder in the maps folder, named after the map, with its map.yml listing its one
   * game.
   *
   * @return the game XML the map.yml names, not written yet
   * @throws IOException also for a name without letters or digits, or when a map folder of that
   *     name is there already
   */
  static Path newMapFolder(final Path mapsFolder, final String mapName) throws IOException {
    final Optional<String> refused = whyNoNewMap(mapsFolder, mapName);
    if (refused.isPresent()) {
      throw new IOException(refused.get());
    }
    final String name = safeName(mapName);
    final Path folder = mapsFolder.resolve(name);
    Files.createDirectories(folder.resolve("games"));
    final Path gameXml = folder.resolve("games").resolve(name + ".xml");
    final Path yamlFile = folder.resolve(MapDescriptionYaml.MAP_YAML_FILE_NAME);
    Files.writeString(
        yamlFile,
        MapDescriptionYaml.builder()
            .yamlFileLocation(yamlFile)
            .mapName(mapName.strip())
            .game(
                MapGame.builder()
                    .gameName(mapName.strip())
                    .xmlFileName(gameXml.getFileName().toString())
                    .build())
            .build()
            .toYamlString());
    return gameXml;
  }

  /**
   * Writes the tiles of the picture that differ from the same tile of the picture before, each as
   * {@link #replace} does, so its first version stays as .bak; the rest, the small map and the
   * preview stay as they are.
   */
  static void writeChangedTiles(
      final Path folder, final BufferedImage image, final BufferedImage before) throws IOException {
    final int size = MapFiles.tileSize();
    final Path tiles = Files.createDirectories(folder.resolve("baseTiles"));
    for (int column = 0; column * size < image.getWidth(); column++) {
      for (int row = 0; row * size < image.getHeight(); row++) {
        final BufferedImage tile = tileOf(image, column, row, size);
        if (!Arrays.equals(pixels(tile), pixels(tileOf(before, column, row, size)))) {
          final Path file = tiles.resolve(column + "_" + row + ".png");
          final BufferedImage written = overOldTile(file, image, column, row, size);
          MapFolder.replace(file, f -> ImageIO.write(written, "png", f.toFile()));
        }
      }
    }
  }

  private static BufferedImage tileOf(
      final BufferedImage image, final int column, final int row, final int size) {
    final BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = tile.createGraphics();
    g.drawImage(image, -column * size, -row * size, null);
    g.dispose();
    return tile;
  }

  /** The tile file with the picture's part drawn over it, so what it has beyond the map stays. */
  private static BufferedImage overOldTile(
      final Path file, final BufferedImage image, final int column, final int row, final int size)
      throws IOException {
    final BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = tile.createGraphics();
    final Optional<BufferedImage> old =
        Files.exists(file) ? Optional.ofNullable(ImageIO.read(file.toFile())) : Optional.empty();
    old.ifPresent(o -> g.drawImage(o, 0, 0, null));
    g.setComposite(AlphaComposite.Src);
    g.setClip(
        0,
        0,
        Math.min(size, image.getWidth() - column * size),
        Math.min(size, image.getHeight() - row * size));
    g.drawImage(image, -column * size, -row * size, null);
    g.dispose();
    return tile;
  }

  private static int[] pixels(final BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  /**
   * The picture cut into the game's base tiles, and shrunk as the game's small map; tiles of a
   * bigger picture before it are deleted.
   */
  static void writePicture(final Path folder, final BufferedImage image) throws IOException {
    final int size = MapFiles.tileSize();
    final Path tiles = Files.createDirectories(folder.resolve("baseTiles"));
    final Set<Path> written = new HashSet<>();
    for (int column = 0; column * size < image.getWidth(); column++) {
      for (int row = 0; row * size < image.getHeight(); row++) {
        final BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = tile.createGraphics();
        g.drawImage(image, -column * size, -row * size, null);
        g.dispose();
        final Path file = tiles.resolve(column + "_" + row + ".png");
        ImageIO.write(tile, "png", file.toFile());
        written.add(file);
      }
    }
    try (Stream<Path> before = Files.list(tiles)) {
      for (final Path stale : before.filter(f -> !written.contains(f)).toList()) {
        Files.delete(stale);
      }
    }
    // the game needs a small map to show the whole map at once
    ImageIO.write(
        shrunk(image, SMALL_MAP_WIDTH),
        "png",
        folder.resolve(Constants.SMALL_MAP_FILENAME + ".png").toFile());
    // the map browser and the game chooser show the map by its preview
    ImageIO.write(shrunk(image, PREVIEW_WIDTH), "png", folder.resolve("preview.png").toFile());
  }

  /**
   * The relief picture cut into the game's relief tiles, drawn over the base tiles; tiles there
   * were before keep their first version as ".bak", as relief is drawn by hand.
   */
  static void writeRelief(final Path folder, final BufferedImage image) throws IOException {
    final int size = MapFiles.tileSize();
    final Path tiles = Files.createDirectories(folder.resolve("reliefTiles"));
    final Set<Path> written = new HashSet<>();
    for (int column = 0; column * size < image.getWidth(); column++) {
      for (int row = 0; row * size < image.getHeight(); row++) {
        final BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = tile.createGraphics();
        g.drawImage(image, -column * size, -row * size, null);
        g.dispose();
        final Path file = tiles.resolve(column + "_" + row + ".png");
        replace(file, to -> ImageIO.write(tile, "png", to.toFile()));
        written.add(file);
      }
    }
    try (Stream<Path> before = Files.list(tiles)) {
      for (final Path stale :
          before
              .filter(f -> f.getFileName().toString().endsWith(".png"))
              .filter(f -> !written.contains(f))
              .toList()) {
        remove(stale);
      }
    }
  }

  private static BufferedImage shrunk(final BufferedImage image, final int maxWidth) {
    final double scale = Math.min(1.0, maxWidth / (double) image.getWidth());
    final int width = Math.max(1, (int) (image.getWidth() * scale));
    final int height = Math.max(1, (int) (image.getHeight() * scale));
    final BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = small.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(image, 0, 0, width, height, null);
    g.dispose();
    return small;
  }

  /**
   * The nation's flags in its color, in the sizes the game shows them, unless the game has flags of
   * its own for it or the map has them already.
   */
  static void writeFlags(final Path folder, final String nation, final Color color)
      throws IOException {
    if (engineAsset("flags", nation + ".png").isPresent()) {
      return;
    }
    final Path flags = Files.createDirectories(folder.resolve("flags"));
    for (final Map.Entry<String, Integer> size : FLAG_SIZES.entrySet()) {
      final Path file = flags.resolve(nation + size.getKey() + ".png");
      if (!Files.exists(file)) {
        final int side = size.getValue();
        final BufferedImage flag = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = flag.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, side, side);
        g.setColor(color.darker().darker());
        g.drawRect(0, 0, side - 1, side - 1);
        g.dispose();
        ImageIO.write(flag, "png", file.toFile());
      }
    }
  }

  /**
   * A picture for each unit type of the nation that neither the game nor the map has: the game's
   * picture of the unit for the {@value #BASE_NATION} in the nation's color, else a disc in its
   * color with the unit's first letter.
   */
  static void writeUnitPictures(
      final Path folder, final String nation, final Color color, final Collection<String> units)
      throws IOException {
    for (final String unit : units) {
      final Path file = unitPicture(folder, nation, unit);
      if (engineAsset("units", nation, unit + ".png").isEmpty() && !Files.exists(file)) {
        final Optional<URL> base = engineAsset("units", BASE_NATION, unit + ".png");
        final Optional<BufferedImage> basePicture =
            base.isPresent() ? Optional.ofNullable(ImageIO.read(base.get())) : Optional.empty();
        writeUnitPicture(
            folder,
            nation,
            unit,
            basePicture.map(p -> tint(p, color)).orElseGet(() -> newUnitPicture(unit, color)));
      }
    }
  }

  private static Path unitPicture(final Path folder, final String nation, final String unit) {
    return folder.resolve("units").resolve(nation).resolve(unit + ".png");
  }

  /**
   * Writes the picture as the nation's units of the type show it, over the one there was, whose
   * first version stays as ".bak".
   */
  static void writeUnitPicture(
      final Path folder, final String nation, final String unit, final BufferedImage picture)
      throws IOException {
    final Path file = unitPicture(folder, nation, unit);
    Files.createDirectories(file.getParent());
    backUp(file);
    ImageIO.write(picture, "png", file.toFile());
  }

  /**
   * The nation's units of the type as the game shows them again: the map's picture of them deleted,
   * then one of {@link #writeUnitPictures} where the game has none.
   */
  static void restoreUnitPicture(
      final Path folder, final String nation, final Color color, final String unit)
      throws IOException {
    remove(unitPicture(folder, nation, unit));
    writeUnitPictures(folder, nation, color, List.of(unit));
  }

  /**
   * The picture in the color: each pixel takes the color's hue and saturation and keeps its own
   * brightness and transparency, so shading and outlines stay.
   */
  static BufferedImage tint(final BufferedImage image, final Color color) {
    final float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
    final BufferedImage tinted =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    for (int x = 0; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        final int argb = image.getRGB(x, y);
        final float brightness =
            Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, null)[2];
        tinted.setRGB(
            x, y, (argb & 0xFF000000) | (Color.HSBtoRGB(hsb[0], hsb[1], brightness) & 0xFFFFFF));
      }
    }
    return tinted;
  }

  /**
   * The unit pictures in a folder, eg another map's {@code units/<nation>}: each picture named like
   * one of the unit types (case does not matter) as it is, by unit type; the others' names.
   */
  static UnitPictures readUnitPictures(final Path folder, final Collection<String> unitTypes)
      throws IOException {
    final Map<String, String> byLowerName = new LinkedHashMap<>();
    unitTypes.forEach(unit -> byLowerName.put(unit.toLowerCase(Locale.ROOT), unit));
    final Map<String, BufferedImage> found = new LinkedHashMap<>();
    final List<String> unknown = new ArrayList<>();
    try (Stream<Path> files = Files.list(folder)) {
      for (final Path file : files.sorted().toList()) {
        final String name = file.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        if (dot <= 0
            || !List.of("png", "gif", "jpg", "jpeg")
                .contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
          continue;
        }
        final String unit = byLowerName.get(name.substring(0, dot).toLowerCase(Locale.ROOT));
        final Optional<BufferedImage> image =
            unit == null ? Optional.empty() : readPicture(file);
        if (image.isPresent()) {
          found.put(unit, image.get());
        } else {
          unknown.add(name);
        }
      }
    }
    return new UnitPictures(found, unknown);
  }

  /** The picture in the file, empty when it is no picture or broken, so the others still come. */
  private static Optional<BufferedImage> readPicture(final Path file) {
    try {
      return Optional.ofNullable(ImageIO.read(file.toFile()));
    } catch (final IOException expected) {
      return Optional.empty();
    }
  }

  /**
   * Unit pictures read from a folder, {@link #readUnitPictures}.
   *
   * @param unknown the pictures named like no unit type, or named like one but not readable
   */
  record UnitPictures(Map<String, BufferedImage> byUnit, List<String> unknown) {}

  /**
   * The picture as a unit's: one larger than a unit shrunk to fit, keeping its shape, in the middle
   * of a clear square of a unit's size; a smaller one as it is.
   */
  static BufferedImage toUnitSize(final BufferedImage image) {
    if (image.getWidth() <= UNIT_SIZE && image.getHeight() <= UNIT_SIZE) {
      return image;
    }
    final double scale =
        Math.min(UNIT_SIZE / (double) image.getWidth(), UNIT_SIZE / (double) image.getHeight());
    final int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
    final int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
    final BufferedImage fitted =
        new BufferedImage(UNIT_SIZE, UNIT_SIZE, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = fitted.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.drawImage(image, (UNIT_SIZE - width) / 2, (UNIT_SIZE - height) / 2, width, height, null);
    g.dispose();
    return fitted;
  }

  private static BufferedImage newUnitPicture(final String unit, final Color color) {
    final BufferedImage picture =
        new BufferedImage(UNIT_SIZE, UNIT_SIZE, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = picture.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(color);
    g.fillOval(4, 4, UNIT_SIZE - 8, UNIT_SIZE - 8);
    g.setColor(color.darker().darker());
    g.drawOval(4, 4, UNIT_SIZE - 8, UNIT_SIZE - 8);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
    final String letter = unit.substring(0, 1).toUpperCase(Locale.ROOT);
    final FontMetrics metrics = g.getFontMetrics();
    g.setColor(isDark(color) ? Color.WHITE : Color.BLACK);
    g.drawString(
        letter,
        (UNIT_SIZE - metrics.stringWidth(letter)) / 2,
        (UNIT_SIZE - metrics.getHeight()) / 2 + metrics.getAscent());
    g.dispose();
    return picture;
  }

  private static boolean isDark(final Color color) {
    return color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114 < 128_000;
  }

  /** A picture among the game's own assets, which the game takes when a map has none. */
  private static Optional<URL> engineAsset(final String... path) {
    return Optional.ofNullable(
        MapFolder.class.getClassLoader().getResource(ResourceLoader.getAssetsFileLocation(path)));
  }

  /** A text file as the game reads it: a .properties file as ISO-8859-1, the others as UTF-8. */
  static String readText(final Path file) throws IOException {
    return Files.readString(file, charsetOf(file));
  }

  /**
   * Writes a text file as {@link #replace} does, as the game reads it: in a .properties file a
   * character beyond ISO-8859-1 as a unicode escape like the game's own files have.
   */
  static void replaceText(final Path file, final String text) throws IOException {
    final Charset charset = charsetOf(file);
    final StringBuilder written = new StringBuilder(text.length());
    for (final char c : text.toCharArray()) {
      written.append(
          charset.equals(StandardCharsets.UTF_8) || c <= 0xFF
              ? String.valueOf(c)
              : String.format("\\u%04X", (int) c));
    }
    replace(file, to -> Files.writeString(to, written, charset));
  }

  private static Charset charsetOf(final Path file) {
    return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".properties")
        ? StandardCharsets.ISO_8859_1
        : StandardCharsets.UTF_8;
  }

  /**
   * Writes a file anew without risk: the file as it was before the map maker first replaced it
   * stays next to it as ".bak", and the new one is written beside it first, so a failed write loses
   * nothing.
   */
  static void replace(final Path file, final ContentWriter writer) throws IOException {
    backUp(file);
    final Path written = file.resolveSibling(file.getFileName() + ".saving");
    Files.deleteIfExists(written);
    writer.write(written);
    if (!Files.exists(written) || Files.size(written) == 0) {
      throw new IOException("Could not write " + written);
    }
    Files.move(written, file, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void backUp(final Path file) throws IOException {
    final Path backup = file.resolveSibling(file.getFileName() + ".bak");
    if (Files.exists(file) && !Files.exists(backup)) {
      Files.copy(file, backup);
    }
  }

  /** Deletes a file as {@link #replace} replaces one: the first version stays as ".bak". */
  static void remove(final Path file) throws IOException {
    backUp(file);
    Files.deleteIfExists(file);
  }

  /** Writes a file's content to the path given. */
  @FunctionalInterface
  interface ContentWriter {
    void write(Path to) throws IOException;
  }

  /** The folders of pictures the game looks for in a map, with what they hold. */
  static final Map<String, String> PICTURE_FOLDERS =
      Map.of(
          "PUs", "The PU coins on the territories, one for each value.",
          "territoryNames", "Territory names as pictures, in place of the drawn ones.",
          "territoryEffects", "The markers of territory effects, eg a forest.",
          "dice", "The dice in battles: thrown, hits and ignored.",
          "misc", "Victory city and blockade markers, the mouse pointer and decorations.");

  /**
   * The pictures the game looks for in one of the {@link #PICTURE_FOLDERS} for this game, eg 3.png
   * in PUs for a territory worth 3; the game draws its own where one is missing.
   */
  static List<String> picturesFor(final String folderName, final Game game) {
    return switch (folderName) {
      case "PUs" ->
          MapXml.territories(game).stream()
              .map(t -> MapXml.production(game, t))
              .filter(value -> value > 0)
              .distinct()
              .sorted()
              .map(value -> value + ".png")
              .toList();
      case "territoryNames" ->
          MapXml.territories(game).stream().map(t -> t.replace(' ', '_') + ".png").toList();
      case "territoryEffects" ->
          Optional.ofNullable(game.getTerritoryEffectList())
              .map(TerritoryEffectList::getTerritoryEffects)
              .orElse(List.of())
              .stream()
              .map(TerritoryEffectList.TerritoryEffect::getName)
              .flatMap(effect -> Stream.of(effect + ".png", effect + "_large.png"))
              .toList();
      case "dice" ->
          IntStream.rangeClosed(
                  1,
                  Optional.ofNullable(game.getDiceSides()).map(DiceSides::getValue).orElse(6))
              .boxed()
              .flatMap(side -> Stream.of("", "_hit", "_ignored").map(kind -> side + kind + ".png"))
              .toList();
      case "misc" ->
          List.of(
              "vc.png",
              "blockade.png",
              "cursor.gif",
              "error.gif",
              "warning.gif",
              "non-withdrawable.png",
              "non-withdrawable_small.png");
      default -> List.of();
    };
  }

  /** Copies the file into the map as {@link #replace} writes one, eg a sound. */
  static void copyInto(final Path source, final Path target) throws IOException {
    Files.createDirectories(target.getParent());
    replace(target, to -> Files.copy(source, to));
  }

  /**
   * The picture written as the target's kind, eg PUs/3.png from a jpg, as {@link #replace} writes
   * a file.
   *
   * @throws IOException also for a file that is no picture
   */
  static void importPicture(final Path source, final Path target) throws IOException {
    final BufferedImage picture = ImageIO.read(source.toFile());
    if (picture == null) {
      throw new IOException(source.getFileName() + " is no picture");
    }
    final String name = target.getFileName().toString();
    final String kind = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    Files.createDirectories(target.getParent());
    // a jpg has no see-through parts, which png and gif keep
    replace(target, to -> ImageIO.write(picture, kind, to.toFile()));
  }

  /**
   * Gives the game of this XML file a new name in the map.yml, the name the game lists it by; every
   * other entry of the file stays.
   *
   * @throws IOException also for a map.yml that does not read
   */
  static void renameGame(final Path yamlFile, final String xmlFileName, final String gameName)
      throws IOException {
    final Map<String, Object> yaml = readYaml(yamlFile);
    if (yaml.get("games") instanceof List<?> games) {
      for (final Object game : games) {
        if (game instanceof Map<?, ?> entry
            && xmlFileName.equals(entry.get("file_name"))) {
          @SuppressWarnings("unchecked")
          final Map<String, Object> named = (Map<String, Object>) entry;
          named.put("game_name", gameName.strip());
        }
      }
    }
    replaceText(yamlFile, YamlWriter.writeToString(yaml));
  }

  /**
   * The map.yml as its entries, to change and write back with every other entry; its comments are
   * lost, kept in the .bak {@link #replaceText} leaves.
   */
  private static Map<String, Object> readYaml(final Path yamlFile) throws IOException {
    try {
      return new LinkedHashMap<>(YamlReader.readMap(readText(yamlFile)));
    } catch (final YamlReader.InvalidYamlFormatException | ClassCastException e) {
      throw new IOException(yamlFile.getFileName() + " does not read: " + e.getMessage(), e);
    }
  }

  /** Packs the folder into a zip, as the map server takes a map; returns the number of files. */
  static int zip(final Path folder, final Path zipFile) throws IOException {
    final List<Path> files;
    try (Stream<Path> walk = Files.walk(folder)) {
      files =
          walk.filter(Files::isRegularFile)
              .filter(f -> !f.getFileName().toString().endsWith(".bak"))
              // left over from a save that failed
              .filter(f -> !f.getFileName().toString().endsWith(".saving"))
              .filter(f -> !f.toAbsolutePath().equals(zipFile.toAbsolutePath()))
              // a clone's .git, .github, .gitignore: no part of the map
              .filter(
                  f ->
                      StreamSupport.stream(folder.relativize(f).spliterator(), false)
                          .noneMatch(part -> part.toString().startsWith(".")))
              .toList();
    }
    try (OutputStream out = Files.newOutputStream(zipFile);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      final Path base = folder.getParent() == null ? folder : folder.getParent();
      for (final Path file : files) {
        zip.putNextEntry(new ZipEntry(base.relativize(file).toString().replace('\\', '/')));
        Files.copy(file, zip);
        zip.closeEntry();
      }
    }
    return files.size();
  }

  /** Starts TripleA once more with the game on its setup screen, to play it against the AI. */
  static void playtest(final Path gameXml) throws IOException {
    final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    final String mainClass = Services.loadAny(ApplicationContext.class).getMainClass().getName();
    new ProcessBuilder(
            java,
            "-classpath",
            System.getProperty("java.class.path"),
            "-D" + CliProperties.TRIPLEA_GAME + "=" + gameXml.toAbsolutePath(),
            mainClass)
        .inheritIO()
        .start();
  }

  /**
   * The picture as the resource's icons, fitted keeping its shape into the squares the game shows
   * them in: {@code resources/<resource>.png} and {@code _large.png}; their first versions stay as
   * ".bak".
   */
  static void writeResourceIcon(
      final Path folder, final String resource, final BufferedImage picture) throws IOException {
    final Path icons = Files.createDirectories(folder.resolve("resources"));
    for (final Map.Entry<String, Integer> size : Map.of(".png", 20, "_large.png", 48).entrySet()) {
      final int side = size.getValue();
      final double scale =
          Math.min(side / (double) picture.getWidth(), side / (double) picture.getHeight());
      final int width = Math.max(1, (int) Math.round(picture.getWidth() * scale));
      final int height = Math.max(1, (int) Math.round(picture.getHeight() * scale));
      final BufferedImage icon = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
      final Graphics2D g = icon.createGraphics();
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.drawImage(picture, (side - width) / 2, (side - height) / 2, width, height, null);
      g.dispose();
      replace(
          icons.resolve(resource + size.getKey()),
          file -> ImageIO.write(icon, "png", file.toFile()));
    }
  }
}
