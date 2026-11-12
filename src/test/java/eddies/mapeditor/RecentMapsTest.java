package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RecentMapsTest {
  private final Preferences preferences =
      Preferences.userRoot().node("eddies-map-editor-test-" + UUID.randomUUID());

  @AfterEach
  void removePreferences() throws BackingStoreException {
    preferences.removeNode();
  }

  private static Path givenGame(final String name) {
    return Path.of("maps", name, "games", name + ".xml").toAbsolutePath();
  }

  @DisplayName("The game opened last comes first, and only the last six stay")
  @Test
  void keepsNewestSix() {
    IntStream.rangeClosed(1, 8)
        .forEach(map -> RecentMaps.add(preferences, givenGame("map" + map)));

    assertThat(RecentMaps.list(preferences))
        .as("newest first, the two oldest gone")
        .containsExactly(
            givenGame("map8"),
            givenGame("map7"),
            givenGame("map6"),
            givenGame("map5"),
            givenGame("map4"),
            givenGame("map3"));
  }

  @DisplayName("A game opened again moves to the top instead of showing twice")
  @Test
  void movesGameOpenedAgainToTop() {
    RecentMaps.add(preferences, givenGame("island"));
    RecentMaps.add(preferences, givenGame("pacific"));

    RecentMaps.add(preferences, Path.of("maps", "island", "games", "..", "games", "island.xml"));

    assertThat(RecentMaps.list(preferences))
        .as("the same file by another way is the same game")
        .containsExactly(givenGame("island"), givenGame("pacific"));
  }

  @DisplayName("A removed game leaves the list, the others keep their order")
  @Test
  void removesGame() {
    RecentMaps.add(preferences, givenGame("island"));
    RecentMaps.add(preferences, givenGame("pacific"));
    RecentMaps.add(preferences, givenGame("europe"));

    RecentMaps.remove(preferences, givenGame("pacific"));

    assertThat(RecentMaps.list(preferences))
        .as("pacific gone")
        .containsExactly(givenGame("europe"), givenGame("island"));
  }
}
