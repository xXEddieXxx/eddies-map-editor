package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import eddies.mapeditor.MapProject.BorderLine;
import java.awt.Color;
import java.awt.Point;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a save will write, told before it: the save's own list, found without writing. */
final class SaveFilesTest {
  @TempDir Path folder;

  /** A saved map of 400 x 300, opened again, its picture held to draw into. */
  private MapProject givenSavedMap() throws Exception {
    final MapProject made =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder)
            .project();
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    project.holdPicture(project.getFiles().tilesPictureToDrawInto().orElseThrow());
    return project;
  }

  /** A change of each kind a save writes: game, notes, colors, places, a nation, the picture. */
  private static void givenChangesOfEveryKind(final MapProject project) {
    final String territory = project.getFiles().polygons().keySet().iterator().next();
    project.addNation("Blue");
    project.setNationColor("Red", new Color(0x102030));
    project.edit(game -> MapXml.withNotes(game, "<p>Notes</p>"));
    project.flipOverflow(territory);
    final Point center = project.getFiles().centers().get(territory);
    project.setCenter(territory, new Point(center.x + 1, center.y));
    project.drawBorder(
        new BorderLine(List.of(new Point(300, 0), new Point(300, 109)), 3, Color.BLACK));
  }

  /** A map folder with the minimap and pictures of the Russians, opened. */
  private MapProject givenMapWithRussiansPictures() throws Exception {
    final Path xml = Files.createDirectories(folder.resolve("games")).resolve("minimap.xml");
    try (InputStream original =
        SaveFilesTest.class.getClassLoader().getResourceAsStream("minimap.xml")) {
      Files.copy(original, xml);
    }
    Files.writeString(
        folder.resolve("polygons.txt"), "red north 1  <  (0,0) (10,0) (10,10) (0,10) >\n");
    Files.createDirectories(folder.resolve("flags"));
    Files.writeString(folder.resolve("flags").resolve("Russians.png"), "flag");
    Files.createDirectories(folder.resolve("units").resolve("Russians"));
    Files.writeString(
        folder.resolve("units").resolve("Russians").resolve("infantry.png"), "infantry");
    Files.writeString(folder.resolve("map.properties"), "color.Russians=AA0000\n");
    return MapProject.open(xml);
  }

  /** Every file below the folder with its bytes. */
  private Map<Path, String> givenFolderAsItIs() throws Exception {
    final Map<Path, String> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(folder)) {
      for (final Path file : walk.toList()) {
        files.put(
            file,
            Files.isDirectory(file)
                ? ""
                : new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
      }
    }
    return files;
  }

  @DisplayName("The files a save is said to write are the ones it writes; asking writes nothing")
  @Test
  void listsWhatTheSaveWrites() throws Exception {
    // given
    final MapProject project = givenSavedMap();
    givenChangesOfEveryKind(project);
    final Map<Path, String> before = givenFolderAsItIs();

    // when
    final List<Path> told = project.filesToSave();
    final Map<Path, String> afterAsking = givenFolderAsItIs();
    final List<Path> written = project.save();
    final Map<Path, String> after = givenFolderAsItIs();

    // then
    assertThat(afterAsking)
        .as("asking makes, changes and removes no file or folder")
        .isEqualTo(before);
    assertThat(told).as("told before the save, the list of the save itself").isEqualTo(written);
    final Set<Path> changed = new HashSet<>();
    after.forEach(
        (file, bytes) -> {
          if (!Files.isDirectory(file)
              && !file.toString().endsWith(".bak")
              && !bytes.equals(before.get(file))) {
            changed.add(file);
          }
        });
    assertThat(written).as("every file the save changed is in its list").containsAll(changed);
    assertThat(changed.stream().map(file -> file.getFileName().toString()))
        .as("game, notes, map.properties, the places and centers, and a tile changed")
        .contains("island_wars.xml", "map.properties", "place.txt", "centers.txt", "1_0.png");
    assertThat(told.stream().map(file -> file.getParent().getFileName() + "/" + file.getFileName()))
        .as("the tile the line crosses, the new nation's flags and unit pictures")
        .contains("baseTiles/1_0.png", "flags/Blue.png", "Blue/infantry.png");
    assertThat(project.filesToSave()).as("after the save nothing is left to write").isEmpty();
  }

  @DisplayName("A save of other files leaves the game's file as it is")
  @Test
  void unchangedGameKeepsItsFile() throws Exception {
    // given
    final MapProject project = givenSavedMap();
    final Path xml = project.getXmlFile().orElseThrow();
    final String asSaved = Files.readString(xml);
    project.setNationColor("Red", new Color(0x102030));

    // when
    final List<Path> written = project.save();

    // then
    assertThat(written).as("only the colors changed").doesNotContain(xml);
    assertThat(Files.readString(xml)).as("the game's file byte for byte").isEqualTo(asSaved);
  }

  @DisplayName("A map made in this session keeps its game's file on a save of other files")
  @Test
  void madeMapKeepsItsGameFile() throws Exception {
    // given: a map made and saved here, still open
    final MapProject project =
        MapProject.newMap("Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder)
            .project();
    final Path xml = project.getXmlFile().orElseThrow();
    final String asSaved = Files.readString(xml);
    project.setNationColor("Red", new Color(0x102030));

    // when
    final List<Path> written = project.save();

    // then
    assertThat(written).as("only the colors changed").doesNotContain(xml);
    assertThat(Files.readString(xml)).as("the game's file byte for byte").isEqualTo(asSaved);
    assertThat(project.name())
        .as("the map keeps the name it was made with")
        .isEqualTo("Island Wars");
  }

  @DisplayName("A save that wrote nothing says so, not 'Saved 0 files'")
  @Test
  void savedNothing() {
    // given
    final Path map = Path.of("maps", "island");

    // when
    final String nothing = MapMakerWindow.savedNews(map, List.of());
    final String one = MapMakerWindow.savedNews(map, List.of(map.resolve("map.properties")));

    // then
    assertThat(nothing).as("an empty save").isEqualTo("✓ Nothing to save");
    assertThat(one).as("a save of one file").isEqualTo("✓ Saved 1 file: map.properties");
  }

  @DisplayName("A renamed nation's pictures, copied and taken away, are told before the save")
  @Test
  void listsARenamedNationsPictures() throws Exception {
    // given
    final MapProject project = givenMapWithRussiansPictures();
    project.renameNation("Russians", "Soviets");
    final Map<Path, String> before = givenFolderAsItIs();

    // when
    final List<Path> told = project.filesToSave();
    final Map<Path, String> afterAsking = givenFolderAsItIs();
    final List<Path> written = project.save();

    // then
    assertThat(afterAsking).as("asking copies and removes nothing").isEqualTo(before);
    assertThat(told)
        .as("the new name's pictures and the old name's, which go")
        .contains(
            folder.resolve("flags").resolve("Soviets.png"),
            folder.resolve("flags").resolve("Russians.png"),
            folder.resolve("units").resolve("Soviets").resolve("infantry.png"),
            folder.resolve("units").resolve("Russians").resolve("infantry.png"));
    assertThat(new HashSet<>(told))
        .as("the list of the save itself")
        .isEqualTo(new HashSet<>(written));
  }

  @DisplayName("Files are named, pictures counted by kind, and many shortened as 'and n more'")
  @Test
  void namesFilesInALine() {
    // given
    final Path map = Path.of("maps", "island");
    final List<Path> files =
        List.of(
            map.resolve("place.txt"),
            map.resolve("baseTiles").resolve("0_0.png"),
            map.resolve("baseTiles").resolve("0_1.png"),
            map.resolve("reliefTiles").resolve("0_0.png"),
            map.resolve("flags").resolve("Red.png"),
            map.resolve("units").resolve("Red").resolve("infantry.png"),
            map.resolve("map.properties"),
            map.resolve("games").resolve("island.xml"));

    // when
    final String many = MapProject.filesLine(map, files);
    final String one = MapProject.filesLine(map, files.subList(7, 8));

    // then
    assertThat(many)
        .as("the first four in the save's order, the rest counted")
        .isEqualTo("8 files: place.txt, 2 tiles, 1 relief tile, 1 flag and 3 more");
    assertThat(one).as("a single file").isEqualTo("1 file: island.xml");
  }

  @DisplayName("A map in a folder named like a kind of picture still has its files named")
  @Test
  void namesFilesOfAMapInAFolderNamedUnits() {
    // given
    final Path map = Path.of("maps", "units", "baseTiles");
    final List<Path> files = List.of(map.resolve("place.txt"), map.resolve("map.properties"));

    // when
    final String line = MapProject.filesLine(map, files);

    // then
    assertThat(line)
        .as("only the path in the map's folder counts")
        .isEqualTo("2 files: place.txt, map.properties");
  }

  @DisplayName("The Save button's tool tip names the files the next save writes")
  @Test
  void saveToolTipNamesTheFiles() throws Exception {
    // given
    final MapProject project = givenSavedMap();
    project.setNationColor("Red", new Color(0x102030));

    // when
    final String tip = MapMakerWindow.saveToolTip(project);
    final String unsavedMap = MapMakerWindow.saveToolTip(MapProject.newEmpty());

    // then
    assertThat(tip)
        .as("map.properties for the color; the game did not change")
        .isEqualTo("Saves 1 file: map.properties (Ctrl+S)");
    assertThat(unsavedMap)
        .as("a map with no folder yet: the plain tool tip")
        .isEqualTo("Writes the map's files (Ctrl+S)");
  }

  @DisplayName("With every change undone the Save button's tool tip names no files")
  @Test
  void saveToolTipAfterAllUndone() throws Exception {
    // given
    final MapProject project = givenSavedMap();
    project.setNationColor("Red", new Color(0x102030));
    MapMakerWindow.saveToolTip(project);

    // when
    project.undo();
    final String tip = MapMakerWindow.saveToolTip(project);

    // then
    assertThat(tip)
        .as("nothing differs from the saved map: the plain tool tip")
        .isEqualTo("Writes the map's files (Ctrl+S)");
  }
}
