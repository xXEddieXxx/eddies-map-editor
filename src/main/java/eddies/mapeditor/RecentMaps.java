package eddies.mapeditor;

import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/** The games opened last, newest first, kept in Java preferences for the start page. */
@UtilityClass
class RecentMaps {
  private static final String KEY = "recentMaps";
  private static final int MAX = 6;

  List<Path> list() {
    return list(preferences());
  }

  List<Path> list(final Preferences preferences) {
    return preferences.get(KEY, "").lines().filter(line -> !line.isBlank()).map(Path::of).toList();
  }

  /** Puts the game first; an older entry of it goes, and the oldest beyond six. */
  void add(final Path gameXml) {
    add(preferences(), gameXml);
  }

  void add(final Preferences preferences, final Path gameXml) {
    final Path added = normalize(gameXml);
    keep(
        preferences,
        Stream.concat(Stream.of(added), without(preferences, added)).limit(MAX).toList());
  }

  void remove(final Path gameXml) {
    remove(preferences(), gameXml);
  }

  void remove(final Preferences preferences, final Path gameXml) {
    keep(preferences, without(preferences, normalize(gameXml)).toList());
  }

  private Stream<Path> without(final Preferences preferences, final Path gameXml) {
    return list(preferences).stream().filter(path -> !normalize(path).equals(gameXml));
  }

  private Path normalize(final Path gameXml) {
    return gameXml.toAbsolutePath().normalize();
  }

  private void keep(final Preferences preferences, final List<Path> games) {
    preferences.put(KEY, String.join("\n", games.stream().map(Path::toString).toList()));
  }

  private Preferences preferences() {
    return Preferences.userNodeForPackage(RecentMaps.class);
  }
}
