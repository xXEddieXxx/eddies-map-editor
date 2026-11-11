package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.description.file.MapDescriptionYaml;
import org.triplea.map.description.file.MapDescriptionYaml.MapGame;
import eddies.mapeditor.MapFolder.UnitPictures;

final class MapFolderTest {
  @TempDir Path root;

  private Path givenMap() throws IOException {
    final Path map = Files.createDirectories(root.resolve("my_map"));
    Files.createDirectories(map.resolve("games"));
    Files.writeString(map.resolve("games/first.xml"), "<game/>");
    Files.writeString(map.resolve("polygons.txt"), "");
    Files.writeString(
        map.resolve("map.yml"),
"""
map_name: My Map
games:
- game_name: First
  file_name: games/first.xml
""");
    return map;
  }

  @DisplayName("A new game is a copy of the template, listed in map.yml beside the others")
  @Test
  void newGameIsListed() throws IOException {
    final Path map = givenMap();

    final Path xml = MapFolder.newGame(map.resolve("games/first.xml"), "Second Try");

    assertThat(xml)
        .as("name made safe for a file, copy of the template")
        .hasFileName("second_try.xml")
        .hasContent("<game/>");
    assertThat(MapDescriptionYaml.fromFile(map.resolve("map.yml")).orElseThrow().getMapGameList())
        .as("listed after the games already there")
        .extracting(MapGame::getGameName)
        .containsExactly("First", "Second Try");
    assertThat(
            MapDescriptionYaml.fromFile(map.resolve("map.yml"))
                .orElseThrow()
                .getGameXmlPathByGameName("Second Try"))
        .as("the game chooser finds the file by the name map.yml gives")
        .contains(xml);
    assertThatThrownBy(() -> MapFolder.newGame(map.resolve("games/first.xml"), "second try"))
        .as("never over a game that is there")
        .isInstanceOf(IOException.class);
  }

  @DisplayName("A map keeping its files in map/ below its map.yml opens from either folder")
  @Test
  void findsGamesOfMapWithContentFolder() throws IOException {
    final Path map = Files.createDirectories(root.resolve("theatre/map/games"));
    Files.writeString(map.resolve("first.xml"), "<game/>");
    Files.writeString(map.resolve("second.xml"), "<game/>");
    Files.writeString(
        root.resolve("theatre/map.yml"),
"""
map_name: Theatre
games:
- game_name: Second
  file_name: second.xml
- game_name: First
  file_name: first.xml
""");

    final Map<String, Path> games = MapFolder.gamesOf(root.resolve("theatre/map"));

    assertThat(games)
        .as("by map.yml's names, in its order")
        .containsExactly(
            Map.entry("Second", map.resolve("second.xml")),
            Map.entry("First", map.resolve("first.xml")));
    assertThat(MapFolder.gamesOf(root.resolve("theatre")))
        .as("the same from the folder of map.yml")
        .isEqualTo(games);
    assertThat(MapFolder.rootOf(map.getParent()))
        .as("exported with its map.yml")
        .isEqualTo(root.resolve("theatre").toAbsolutePath());
  }

  @DisplayName("A map.yml far above a map is another's, not the map's")
  @Test
  void ignoresMapYmlFarAbove() throws IOException {
    Files.writeString(root.resolve("map.yml"), "map_name: Other\n");
    final Path map = Files.createDirectories(root.resolve("a/b/c/my_map"));

    assertThat(MapFolder.rootOf(map)).as("the map's own folder").isEqualTo(map);
  }

  @DisplayName("A map without map.yml offers the game files of its games folder")
  @Test
  void findsGamesWithoutMapYml() throws IOException {
    final Path games = Files.createDirectories(root.resolve("loose/games"));
    Files.writeString(games.resolve("b.xml"), "<game/>");
    Files.writeString(games.resolve("a.xml"), "<game/>");
    Files.writeString(root.resolve("loose/other.xml"), "<other/>");

    assertThat(MapFolder.gamesOf(root.resolve("loose")).keySet())
        .as("only the games folder's, by file name")
        .containsExactly("a.xml", "b.xml");
  }

  @DisplayName("The zip holds the map's folder without the map maker's backups or failed saves")
  @Test
  void zipsTheMap() throws IOException {
    final Path map = givenMap();
    Files.writeString(map.resolve("games/first.xml.bak"), "old");
    Files.writeString(map.resolve("games/first.xml.saving"), "half written");
    Files.createDirectories(map.resolve(".git"));
    Files.writeString(map.resolve(".git/config"), "[core]");
    Files.writeString(map.resolve(".gitignore"), "*.bak");
    final Path zip = root.resolve("my_map.zip");

    final int files = MapFolder.zip(map, zip);

    assertThat(files).as("the .bak, .saving and a clone's hidden files are left out").isEqualTo(3);
    assertThat(entries(zip))
        .as("under the map's folder, as the game unpacks a map")
        .containsExactlyInAnyOrder(
            "my_map/games/first.xml", "my_map/polygons.txt", "my_map/map.yml");
  }

  @DisplayName("A new map's folder has its map.yml; a name in use or without letters is refused")
  @Test
  void newMapFolder() throws IOException {
    final Path maps = Files.createDirectories(root.resolve("maps"));

    final Path xml = MapFolder.newMapFolder(maps, "Island Wars");

    assertThat(xml)
        .as("the game in the map's games folder, named safely")
        .isEqualTo(maps.resolve("island_wars/games/island_wars.xml"));
    assertThat(
            MapDescriptionYaml.fromFile(maps.resolve("island_wars/map.yml"))
                .orElseThrow()
                .getMapName())
        .as("the game finds its map by this name")
        .isEqualTo("Island Wars");
    Files.writeString(xml, "<game/>");
    assertThat(
            MapDescriptionYaml.fromFile(maps.resolve("island_wars/map.yml"))
                .orElseThrow()
                .getGameXmlPathByGameName("Island Wars"))
        .as("the game chooser finds the game file by the name map.yml gives")
        .contains(xml);
    assertThatThrownBy(() -> MapFolder.newMapFolder(maps, "island wars"))
        .as("never over a map that is there")
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> MapFolder.newMapFolder(maps, "!!!"))
        .as("a folder name needs letters or digits")
        .isInstanceOf(IOException.class);
  }

  @DisplayName("Tinting takes the color's hue and saturation, keeps brightness and transparency")
  @Test
  void tints() {
    final BufferedImage picture = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
    picture.setRGB(0, 0, 0xFF808080);
    picture.setRGB(1, 0, 0x80FFFFFF);
    picture.setRGB(2, 0, 0x00000000);

    final BufferedImage tinted = MapFolder.tint(picture, Color.RED);

    assertThat(tinted.getRGB(0, 0)).as("grey becomes as dark a red").isEqualTo(0xFF800000);
    assertThat(tinted.getRGB(1, 0)).as("half clear stays half clear").isEqualTo(0x80FF0000);
    assertThat(tinted.getRGB(2, 0) >>> 24).as("clear stays clear").isZero();
  }

  @DisplayName("A large picture is shrunk to a unit's size, keeping its shape; a small one stays")
  @Test
  void fitsUnitSize() {
    final BufferedImage wide = new BufferedImage(96, 48, BufferedImage.TYPE_INT_RGB);
    final BufferedImage small = new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB);

    final BufferedImage fitted = MapFolder.toUnitSize(wide);

    assertThat(List.of(fitted.getWidth(), fitted.getHeight()))
        .as("a unit's size")
        .containsExactly(48, 48);
    assertThat(fitted.getRGB(24, 2) >>> 24).as("clear above the 48x24 picture").isZero();
    assertThat(fitted.getRGB(24, 24) >>> 24).as("the picture in the middle").isEqualTo(0xFF);
    assertThat(MapFolder.toUnitSize(small)).as("not blown up").isSameAs(small);
  }

  @DisplayName("A map's unit picture stays as .bak when written over or taken back")
  @Test
  void backsUpUnitPictures() throws IOException {
    final Path map = givenMap();
    final Path picture = map.resolve("units/Red/infantry.png");
    MapFolder.writeUnitPicture(map, "Red", "infantry", givenSquare(Color.GREEN));
    final byte[] first = Files.readAllBytes(picture);

    MapFolder.writeUnitPicture(map, "Red", "infantry", givenSquare(Color.BLUE));
    MapFolder.restoreUnitPicture(map, "Red", Color.RED, "infantry");

    assertThat(map.resolve("units/Red/infantry.png.bak"))
        .as("the map's first picture, not the one written over it")
        .hasBinaryContent(first);
  }

  @DisplayName("Pictures named like unit types are read, any case; others are named as unknown")
  @Test
  void readsUnitPictures() throws IOException {
    final Path folder = Files.createDirectories(root.resolve("units/Germans"));
    ImageIO.write(givenSquare(Color.GREEN), "png", folder.resolve("Infantry.png").toFile());
    ImageIO.write(givenSquare(Color.BLUE), "png", folder.resolve("dragon.png").toFile());
    Files.writeString(folder.resolve("notes.txt"), "not a picture");

    final UnitPictures read = MapFolder.readUnitPictures(folder, List.of("infantry", "tank"));

    assertThat(read.byUnit()).as("by the unit type's own name").containsOnlyKeys("infantry");
    assertThat(read.unknown())
        .as("no such unit type; text files are no pictures")
        .containsExactly("dragon.png");
  }

  private static BufferedImage givenSquare(final Color color) {
    final BufferedImage square = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = square.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, 48, 48);
    g.dispose();
    return square;
  }

  private static List<String> entries(final Path zip) throws IOException {
    final List<String> names = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  @DisplayName("Typed text in a .properties file is saved as the game reads it; other files UTF-8")
  @Test
  void savesPropertiesAsTheGameReadsThem() throws IOException {
    final Path map = givenMap();
    final Path properties = map.resolve("notifications.properties");
    final Path notes = map.resolve("notes.txt");
    final String typed = "Hello=Grüße 中";

    MapFolder.replaceText(properties, typed);
    MapFolder.replaceText(notes, typed);
    final Properties read = new Properties();
    try (InputStream in = Files.newInputStream(properties)) {
      // as TripleA's ResourceLoader reads it: from bytes, which Properties takes as ISO-8859-1
      read.load(in);
    }

    assertThat(read.getProperty("Hello"))
        .as("the umlaut and the character beyond ISO-8859-1 as typed")
        .isEqualTo("Grüße 中");
    assertThat(MapFolder.readText(properties))
        .as("the Files tool shows the umlaut as typed, the other as its escape")
        .isEqualTo("Hello=Grüße \\u4E2D");
    assertThat(Files.readString(notes, StandardCharsets.UTF_8))
        .as("another text file stays UTF-8 as typed")
        .isEqualTo(typed);
  }

}
