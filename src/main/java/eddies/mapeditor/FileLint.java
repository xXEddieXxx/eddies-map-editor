package eddies.mapeditor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.generic.xml.reader.exceptions.XmlParsingException;
import org.triplea.io.FileUtils;
import org.triplea.map.data.elements.Game;
import org.triplea.map.description.file.MapDescriptionYaml;
import org.triplea.util.PointFileReaderWriter;

/**
 * Checks the text of a map's file as the game reads it, before it is saved: the game XML, map.yml,
 * the point files (polygons, centers, places, markers) and map.properties. Other files have no
 * check.
 */
@UtilityClass
class FileLint {
  private static final Pattern LINE = Pattern.compile("[Ll]ine:? *(\\d+)");
  // the reader's message ends the quote with this, so a name with an apostrophe stays whole
  private static final Pattern QUOTED = Pattern.compile("[Ll]ine '(.*)' did not match");
  private static final String MAP_PROPERTIES = "map.properties";
  private static final Pattern HEX_COLOR = Pattern.compile("[0-9A-Fa-f]{6}");
  // the point files with one point per territory
  private static final List<String> ONE_POINT =
      List.of("centers.txt", "capitols.txt", "vc.txt", "name_place.txt", "pu_place.txt");

  /** A problem in the text, at its line when the reader tells one (1 for the first), else 0. */
  record Lint(int line, String message) {
    static Lint of(final String message) {
      final Matcher line = LINE.matcher(message);
      return new Lint(line.find() ? Integer.parseInt(line.group(1)) : 0, message);
    }
  }

  /** Whether the map maker checks files of this name. */
  static boolean isChecked(final String fileName) {
    final String name = fileName.toLowerCase(Locale.ROOT);
    return name.endsWith(".xml")
        || name.equals(MapDescriptionYaml.MAP_YAML_FILE_NAME)
        || name.equals("polygons.txt")
        || name.equals("place.txt")
        || ONE_POINT.contains(name)
        || name.equals(MAP_PROPERTIES);
  }

  /**
   * The problems of the text as a file of this name; none for a file the map maker does not check.
   */
  static List<Lint> lint(final String fileName, final String text) {
    final String name = fileName.toLowerCase(Locale.ROOT);
    try {
      if (name.endsWith(".xml")) {
        return lintXml(text);
      } else if (name.equals(MAP_PROPERTIES)) {
        return lintProperties(text);
      } else if (isChecked(name)) {
        return lintByReading(name, text);
      }
      return List.of();
    } catch (final IOException e) {
      return List.of(Lint.of("Could not check the file: " + e.getMessage()));
    }
  }

  /** As the map maker reads a game, and for a game as the engine loads it. */
  private static List<Lint> lintXml(final String text) throws IOException {
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    try (var in = new ByteArrayInputStream(bytes)) {
      new XmlMapper(in).mapXmlToObject(Game.class);
    } catch (final XmlParsingException e) {
      return List.of(Lint.of(e.getMessage()));
    }
    final Path xml = Files.createTempFile("map-maker-lint", ".xml");
    try {
      Files.write(xml, bytes);
      return MapChecks.findError(xml).map(error -> List.of(Lint.of(error))).orElse(List.of());
    } finally {
      Files.deleteIfExists(xml);
    }
  }

  /** The values the map maker and the game read as colors and sizes must be such. */
  private static List<Lint> lintProperties(final String text) throws IOException {
    final Properties properties = new Properties();
    properties.load(new StringReader(text));
    final List<Lint> problems = new ArrayList<>();
    for (final Map.Entry<Object, Object> entry : properties.entrySet()) {
      final String key = entry.getKey().toString();
      final String value = entry.getValue().toString();
      // as the game reads them: a color must be its six digits alone, a size may have spaces
      if (key.startsWith("color.") && !HEX_COLOR.matcher(value).matches()) {
        problems.add(new Lint(lineOf(text, key), key + " is no color like FF0000: " + value));
      } else if ((key.equals("map.width") || key.equals("map.height"))
          && !value.strip().matches("\\d+")) {
        problems.add(new Lint(lineOf(text, key), key + " is no whole number: " + value));
      }
    }
    return problems;
  }

  /**
   * The line that sets the key, the last one as the value read is the last; 0 if none does. color.X
   * is not color.XY's line.
   */
  private static int lineOf(final String text, final String key) {
    // shortcut: a key written with escapes, eg "color.Some\ Nation", is not found (line 0)
    final Pattern setting = Pattern.compile("\\s*" + Pattern.quote(key) + "\\s*[=:\\s].*");
    final List<String> lines = text.lines().toList();
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (setting.matcher(lines.get(i)).matches()) {
        return i + 1;
      }
    }
    return 0;
  }

  /** map.yml and the point files, read by the game's own readers from a file of their name. */
  private static List<Lint> lintByReading(final String name, final String text) throws IOException {
    final Path folder = Files.createTempDirectory("map-maker-lint");
    final Path file = folder.resolve(name);
    try {
      Files.writeString(file, text);
      if (name.equals(MapDescriptionYaml.MAP_YAML_FILE_NAME)) {
        return MapDescriptionYaml.fromFile(file).isPresent()
            ? List.of()
            : List.of(
                Lint.of(
                    "map.yml needs map_name and a games list, each game with game_name and"
                        + " file_name"));
      }
      if (name.equals("polygons.txt")) {
        PointFileReaderWriter.readOneToManyPolygons(file);
      } else if (name.equals("place.txt")) {
        PointFileReaderWriter.readOneToManyPlacements(file);
      } else {
        PointFileReaderWriter.readOneToOne(file);
      }
      return List.of();
    } catch (final IOException e) {
      // the readers tell a wrong line in an IllegalArgumentException, quoting the line
      if (e.getCause() instanceof IllegalArgumentException wrong) {
        return List.of(new Lint(quotedLine(text, wrong.getMessage()), wrong.getMessage()));
      }
      throw e;
    } catch (final IllegalArgumentException e) {
      return List.of(new Lint(quotedLine(text, e.getMessage()), e.getMessage()));
    } finally {
      FileUtils.deleteDirectory(folder);
    }
  }

  /** The line the message quotes, as in "Line 'Germany (1' did not match", 0 if none. */
  private static int quotedLine(final String text, final String message) {
    final Matcher quoted = QUOTED.matcher(message);
    if (quoted.find()) {
      final List<String> lines = text.lines().toList();
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).equals(quoted.group(1))) {
          return i + 1;
        }
      }
    }
    return 0;
  }
}
