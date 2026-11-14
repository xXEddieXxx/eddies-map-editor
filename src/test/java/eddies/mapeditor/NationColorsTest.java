package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import games.strategy.triplea.Constants;
import games.strategy.triplea.ui.mapdata.PlayerColors;
import java.awt.Color;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;

/** The editor shows land in the colors TripleA gives it: TripleA's own PlayerColors says which. */
final class NationColorsTest {
  private static final Path DOWNLOADED_MAPS =
      Path.of(System.getProperty("user.home"), "triplea", "downloadedMaps");
  private static final String IMPASSABLE = "(impassable)";

  @TempDir Path folder;

  private MapProject givenNewMap() throws Exception {
    return MapProject.newMap(
            "Island Wars",
            MapPictureTest.givenPicture(),
            List.of("Germans", "Russians", "British"),
            folder.resolve("maps"))
        .project();
  }

  /** The new map with each nation owning a territory, and its map.properties changed by hand. */
  private Path givenMapWithColors(final String... lines) throws Exception {
    final MapProject project = givenNewMap();
    final List<String> land =
        MapXml.territories(project.game()).stream()
            .filter(t -> !MapXml.isWater(project.game(), t))
            .toList();
    final List<String> nations = MapXml.nations(project.game());
    for (int i = 0; i < nations.size(); i++) {
      final String territory = land.get(i);
      final String nation = nations.get(i);
      project.edit(g -> MapXml.withOwner(g, territory, nation));
    }
    project.save();
    final Path properties = project.getFiles().folder().orElseThrow().resolve("map.properties");
    final List<String> kept =
        new ArrayList<>(
            Files.readAllLines(properties).stream()
                .filter(line -> !line.startsWith("color."))
                .toList());
    kept.addAll(List.of(lines));
    Files.write(properties, kept);
    return project.getXmlFile().orElseThrow();
  }

  /**
   * The color TripleA fills each owner's land with for this map.properties, asked as its small
   * map does when a game starts: the land territories in the game's order, impassable ones apart,
   * no owner as Neutral. The order is the one the editor assumes too, written here a second time:
   * comparing with this proves TripleA's list of colors and how keys are read, not the order.
   */
  private static Map<String, Color> givenTripleAColors(
      final Game game, final Properties properties) throws Exception {
    final PlayerColors colors = new PlayerColors(properties);
    final Method ofPlayer = PlayerColors.class.getDeclaredMethod("getPlayerColor", String.class);
    ofPlayer.setAccessible(true);
    final Method ofImpassable = PlayerColors.class.getDeclaredMethod("getImpassableColor");
    ofImpassable.setAccessible(true);
    final Map<String, String> owners = MapXml.ownersByTerritory(game);
    final Map<String, Color> result = new LinkedHashMap<>();
    for (final String territory : MapXml.territories(game)) {
      if (MapXml.isWater(game, territory)) {
        continue;
      }
      final boolean impassable = MapXml.isImpassable(game, territory);
      final String owner =
          impassable ? IMPASSABLE : owners.getOrDefault(territory, Constants.PLAYER_NAME_NEUTRAL);
      if (!result.containsKey(owner)) {
        result.put(
            owner,
            (Color) (impassable ? ofImpassable.invoke(colors) : ofPlayer.invoke(colors, owner)));
      }
    }
    return result;
  }

  private static Map<String, Color> editorColors(
      final MapProject project, final Iterable<String> owners) {
    final Map<String, Color> result = new LinkedHashMap<>();
    for (final String owner : owners) {
      result.put(
          owner,
          IMPASSABLE.equals(owner)
              ? project.getFiles().impassableColor()
              : project.nationColor(owner));
    }
    return result;
  }

  @DisplayName("Nations and land without owner show in TripleA's colors, with and without a key")
  @Test
  void showsWhatTripleAShows() throws Exception {
    // given: one nation with a color, the others and the land without owner with none
    final MapProject project = MapProject.open(givenMapWithColors("color.Russians=2E86C1"));

    // when
    final Map<String, Color> inGame =
        givenTripleAColors(project.game(), project.getFiles().properties());

    // then
    assertThat(inGame.keySet())
        .as("the nations with land and the land without owner")
        .contains("Germans", "Russians", "British", Constants.PLAYER_NAME_NEUTRAL);
    assertThat(editorColors(project, inGame.keySet()))
        .as("the editor shows each in the color the game will")
        .isEqualTo(inGame);
    assertThat(project.neutralColor())
        .as("land without owner is TripleA's nation Neutral")
        .isEqualTo(inGame.get(Constants.PLAYER_NAME_NEUTRAL));
  }

  @DisplayName("A map opened with land without owner and no color for it stays as it is")
  @Test
  void openedMapKeepsTripleAsNeutralColor() throws Exception {
    // given: a map as others publish them, no color.Neutral in its map.properties
    final Path xml = givenMapWithColors("color.Russians=2E86C1");

    // when
    final MapProject project = MapProject.open(xml);

    // then
    assertThat(givenTripleAColors(project.game(), project.getFiles().properties()))
        .as("given: land without owner")
        .containsKey(Constants.PLAYER_NAME_NEUTRAL);
    assertThat(project.hasUnsavedChanges())
        .as("a save would change how TripleA paints the map its players know")
        .isFalse();
    assertThat(project.getFiles().properties())
        .as("no color written for land without owner")
        .doesNotContainKey("color.Neutral");
  }

  @DisplayName("A new map gets a calm color for land without owner, which TripleA would paint red")
  @Test
  void newMapHasANeutralColor() throws Exception {
    // given, when
    final MapProject project = givenNewMap();

    // then
    assertThat(project.getFiles().nationColor(Constants.PLAYER_NAME_NEUTRAL))
        .as("written into map.properties, as TripleA gives a nation without color its red")
        .contains(MapCanvas.NEUTRAL);
    assertThat(
            givenTripleAColors(
                project.game(), MapFiles.of(project.getXmlFile().orElseThrow()).properties()))
        .as("saved: the game shows it too")
        .containsEntry(Constants.PLAYER_NAME_NEUTRAL, MapCanvas.NEUTRAL);
    assertThat(project.getFiles().impassableColor())
        .as("impassable land as TripleA fills it without a key")
        .isEqualTo(PlayerColors.DEFAULT_IMPASSABLE_COLOR);
  }

  @DisplayName("#F00 and ' ff0000 ' are read and written as TripleA reads them; 'red' is a problem")
  @Test
  void colorsTripleACannotReadAreMended() throws Exception {
    // given: TripleA stops at each of these when it draws the nation
    final Path xml =
        givenMapWithColors("color.Germans=#F00", "color.Russians=00ff00 ", "color.British=red");

    // when
    final MapProject project = MapProject.open(xml);

    // then
    assertThat(project.nationColor("Germans")).as("the short form").isEqualTo(Color.RED);
    assertThat(project.nationColor("Russians")).as("blanks around it").isEqualTo(Color.GREEN);
    assertThat(project.hasUnsavedChanges()).as("mended, as a change to save").isTrue();
    assertThat(project.getFiles().properties())
        .as("as TripleA reads them: six hex digits")
        .containsEntry("color.Germans", "FF0000")
        .containsEntry("color.Russians", "00FF00");
    assertThat(project.problems())
        .as("no color at all: told with the tool that sets it")
        .anySatisfy(
            problem -> {
              assertThat(problem.text()).contains("British's color").contains("red");
              assertThat(problem.tool()).contains("Nations");
            });
  }

  @DisplayName("Every downloaded map's nations show in the colors TripleA gives them")
  @Test
  void downloadedMapsShowTripleAsColors() throws Exception {
    assumeTrue(Files.isDirectory(DOWNLOADED_MAPS), "no downloaded maps on this machine");
    final List<String> different = new ArrayList<>();
    int compared = 0;

    for (final Path xml : givenDownloadedGames()) {
      final MapProject project;
      try {
        project = MapProject.open(xml);
      } catch (final Exception e) {
        // no game of TripleA's, eg a file kept beside the games
        continue;
      }
      if (project.getFiles().folder().isEmpty()) {
        continue;
      }
      // with map.properties as a save writes it, eg its colors as six hex digits
      final Map<String, Color> inGame =
          givenTripleAColors(project.game(), project.getFiles().properties());
      compared += inGame.size();
      editorColors(project, inGame.keySet())
          .forEach(
              (owner, color) -> {
                if (!color.equals(inGame.get(owner))) {
                  different.add(
                      xml + ": " + owner + " " + color + ", in TripleA " + inGame.get(owner));
                }
              });
    }

    assertThat(compared).as("nations compared").isPositive();
    assertThat(different).as("owners the editor shows in another color than TripleA").isEmpty();
  }

  private static List<Path> givenDownloadedGames() throws Exception {
    try (Stream<Path> files = Files.walk(DOWNLOADED_MAPS)) {
      return files
          .filter(file -> file.getFileName().toString().endsWith(".xml"))
          .filter(file -> file.getParent().getFileName().toString().equals("games"))
          .toList();
    }
  }
}
