package eddies.mapeditor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a picture of the guide must not show of the computer that makes it ({@link
 * GuideScreenshots}): its home and temporary folders, as Java names them and as they are on disk
 * (so in their long and their Windows 8.3 form, eg C:\Users\JANEDO~1), and the user's name, also as
 * the 8.3 name of a folder called like it. Each is found whatever its case, a folder with either
 * separator.
 */
final class GuidePrivacy {
  static final String HOME = "C:\\Users\\you";
  static final String TEMP = "C:\\Temp";
  static final String USER = "you";
  private static final String SEPARATORS = "[\\\\/]+";

  // what is looked for with what stands there instead, a folder before the names in it
  private final Map<Pattern, String> neutral = new LinkedHashMap<>();

  /**
   * @param homes the home folder in every form it has
   * @param temps the folder for temporary files in every form it has, eg under the home folder
   * @param names the user's name and the home folder's, where they differ
   */
  GuidePrivacy(final List<String> homes, final List<String> temps, final List<String> names) {
    temps.forEach(temp -> folder(temp, TEMP));
    homes.forEach(home -> folder(home, HOME));
    for (final String name : names) {
      if (!name.isBlank()) {
        neutral.put(Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE), USER);
        // Windows' short name of a folder called so: its first six characters, without blanks and
        // dots, then ~ and a number
        final String start = name.replaceAll("[ .]", "");
        neutral.put(
            Pattern.compile(
                Pattern.quote(start.substring(0, Math.min(6, start.length()))) + "~[0-9]+",
                Pattern.CASE_INSENSITIVE),
            USER);
      }
    }
  }

  /** This computer's, from Java's user.home, java.io.tmpdir and user.name. */
  static GuidePrivacy ofThisComputer() {
    final Path home = Path.of(System.getProperty("user.home"));
    return new GuidePrivacy(
        forms(home),
        forms(Path.of(System.getProperty("java.io.tmpdir"))),
        List.of(System.getProperty("user.name"), String.valueOf(home.getFileName())));
  }

  // as Java names the folder and as it is on disk: of an 8.3 name the long one
  private static List<String> forms(final Path folder) {
    final List<String> forms = new ArrayList<>(List.of(folder.toString()));
    try {
      forms.add(folder.toRealPath().toString());
    } catch (final IOException e) {
      // not there: only as named
    }
    return forms;
  }

  private void folder(final String folder, final String instead) {
    final List<String> parts =
        Stream.of(folder.split(SEPARATORS)).filter(part -> !part.isEmpty()).toList();
    if (!parts.isEmpty()) {
      // with the separator it starts with, eg /home/jane
      final String start = folder.matches(SEPARATORS + ".*") ? SEPARATORS : "";
      neutral.put(
          Pattern.compile(
              start + String.join(SEPARATORS, parts.stream().map(Pattern::quote).toList()),
              Pattern.CASE_INSENSITIVE),
          instead);
    }
  }

  /** The text with a neutral folder or name for each of the computer's. */
  String neutral(final String text) {
    String result = text;
    for (final Map.Entry<Pattern, String> entry : neutral.entrySet()) {
      result =
          entry.getKey().matcher(result).replaceAll(Matcher.quoteReplacement(entry.getValue()));
    }
    return result;
  }

  /** Whether the text has a folder or name of the computer. */
  boolean shows(final String text) {
    return !neutral(text).equals(text);
  }
}
