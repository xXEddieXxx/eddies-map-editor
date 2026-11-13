package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import eddies.mapeditor.MapFiles.Marker;

final class ResizeMapTest {
  private static final String LAND = "Territory 1";

  @TempDir Path folder;

  /** A saved map of the 400x300 picture, opened again, so its picture is only in tiles. */
  private MapProject givenSavedMap() throws Exception {
    final MapProject made =
        MapProject.newMap(
                "Island Wars", MapPictureTest.givenPicture(), List.of("Red"), folder.resolve("maps"))
            .project();
    return MapProject.open(made.getXmlFile().orElseThrow());
  }

  /** The map with a place of every kind at 100,60, the picture held as Resize map holds it. */
  private MapProject givenMapWithPlaces() throws Exception {
    final MapProject project = givenSavedMap();
    project.holdPicture(project.getFiles().tilesPicture().orElseThrow());
    project.setCenter(LAND, new Point(100, 60));
    project.setUnitPlaces(LAND, List.of(new Point(100, 60)));
    project.setMarkerPlace(Marker.VALUE, LAND, 0, new Point(100, 60));
    project.setMarkerPlace(Marker.CAPITAL, LAND, 0, new Point(100, 60));
    project.setDecorations("ship.png", List.of(new Point(100, 60)));
    project.setTextureOffset("Forest", new Point(100, 60));
    project.setMapProperty("screenshot.title.x", "100");
    project.setMapProperty("screenshot.title.font.size", "20");
    return project;
  }

  private static void whenResized(final MapProject project, final double factor) {
    final MapFiles from = project.getFiles();
    assertThat(project.takeResized(from, from.resized(factor), factor))
        .as("taken, as the map did not change meanwhile")
        .isTrue();
  }

  @DisplayName("A map resized to 200 % has every outline, center, place and offset twice as far")
  @Test
  void scalesEveryCoordinate() throws Exception {
    // given
    final MapProject project = givenMapWithPlaces();
    final Rectangle outline = project.getFiles().polygons().get(LAND).getFirst().getBounds();

    // when
    whenResized(project, 2);

    // then
    final MapFiles files = project.getFiles();
    assertThat(files.size()).as("the map's size").isEqualTo(new java.awt.Dimension(800, 600));
    assertThat(files.properties().getProperty("map.width")).as("map.width").isEqualTo("800");
    assertThat(files.properties().getProperty("map.height")).as("map.height").isEqualTo("600");
    assertThat(files.picture().orElseThrow().getWidth()).as("the picture's width").isEqualTo(800);
    assertThat(files.polygons().get(LAND).getFirst().getBounds())
        .as("the outline, twice as big from the map's corner")
        .isEqualTo(
            new Rectangle(outline.x * 2, outline.y * 2, outline.width * 2, outline.height * 2));
    assertThat(files.centers().get(LAND)).as("the center").isEqualTo(new Point(200, 120));
    assertThat(files.unitPlaces(LAND))
        .as("a 48 px unit keeps its size: its middle, at 124,84, is twice as far")
        .containsExactly(new Point(224, 144));
    assertThat(files.places().markers(Marker.VALUE).get(LAND))
        .as("the value, drawn at its place")
        .containsExactly(new Point(200, 120));
    assertThat(files.places().markers(Marker.CAPITAL).get(LAND))
        .as("the 100 px flag's middle, at 150,110, is twice as far")
        .containsExactly(new Point(250, 170));
    assertThat(files.places().decorations().get("ship.png"))
        .as("the decoration")
        .containsExactly(new Point(200, 120));
    assertThat(files.textureOffset("Forest")).as("the texture's offset").isEqualTo(new Point(200, 120));
    assertThat(files.properties().getProperty("screenshot.title.x"))
        .as("the exported picture's title")
        .isEqualTo("200");
    assertThat(files.properties().getProperty("screenshot.title.font.size"))
        .as("and its letters")
        .isEqualTo("40");
    assertThat(files.properties().getProperty("units.scale"))
        .as("the units keep their size")
        .isNull();
  }

  @DisplayName("A resize is one step: one undo brings the map back as it was")
  @Test
  void isOneStep() throws Exception {
    // given
    final MapProject project = givenMapWithPlaces();
    final MapFiles before = project.getFiles();

    // when
    whenResized(project, 1.5);
    project.undo();

    // then
    assertThat(project.getFiles()).as("the files as before the resize").isSameAs(before);
  }

  @DisplayName("A map that changed while it was resized stays as it is")
  @Test
  void refusesWhenChangedMeanwhile() throws Exception {
    // given
    final MapProject project = givenMapWithPlaces();
    final MapFiles from = project.getFiles();
    final MapFiles resized = from.resized(2);

    // when
    project.setCenter(LAND, new Point(90, 50));

    // then
    assertThat(project.takeResized(from, resized, 2)).as("not taken").isFalse();
    assertThat(project.getFiles().size().width).as("the map's width").isEqualTo(400);
  }

  @DisplayName("A flat picture made bigger keeps its colors; made smaller, its lines stay closed")
  @Test
  void keepsLinesOfThePicture() throws Exception {
    // given
    final MapProject project = givenMapWithPlaces();
    final BufferedImage picture = project.getFiles().picture().orElseThrow();

    // when
    final BufferedImage bigger = project.getFiles().resized(1.5).picture().orElseThrow();
    final BufferedImage smaller = project.getFiles().resized(0.5).picture().orElseThrow();

    // then
    assertThat(colors(bigger))
        .as("no color between two others: each pixel is one of the picture's")
        .isSubsetOf(colors(picture));
    assertThat(Stream.iterate(0, x -> x + 1).limit(200))
        .as("the 3 px line across the picture at y 110 is still dark all along at y 55")
        .allMatch(x -> new Color(smaller.getRGB(x, 55)).getRed() < 120);
  }

  private static java.util.Set<Integer> colors(final BufferedImage image) {
    final java.util.Set<Integer> colors = new java.util.HashSet<>();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        colors.add(image.getRGB(x, y) | 0xFF000000);
      }
    }
    return colors;
  }

  @DisplayName("A save after a resize writes tiles, outlines, places and size; TripleA loads it")
  @Test
  void savesResizedMap() throws Exception {
    // given
    final MapProject project = givenMapWithPlaces();
    final Path map = project.getFolder().orElseThrow();
    final byte[] firstTile = Files.readAllBytes(map.resolve("baseTiles/0_0.png"));
    final byte[] smallMap = Files.readAllBytes(map.resolve("smallMap.png"));
    final byte[] preview = Files.readAllBytes(map.resolve("preview.png"));

    // when
    whenResized(project, 2);
    project.save();

    // then
    assertThat(map.resolve("baseTiles/3_2.png")).as("800x600 needs 4 by 3 tiles").exists();
    assertThat(map.resolve("baseTiles/0_0.png.bak"))
        .as("the first tile as it was before the resize")
        .hasBinaryContent(firstTile);
    assertThat(map.resolve("smallMap.png"))
        .as("the small map, maybe made with the relief, stays as it is")
        .hasBinaryContent(smallMap);
    assertThat(map.resolve("preview.png")).as("so does the preview").hasBinaryContent(preview);
    final Properties saved = new Properties();
    saved.load(Files.newBufferedReader(map.resolve("map.properties")));
    assertThat(saved.getProperty("map.width")).as("map.width in the file").isEqualTo("800");
    final MapProject opened = MapProject.open(project.getXmlFile().orElseThrow());
    assertThat(opened.getFiles().centers().get(LAND))
        .as("centers.txt")
        .isEqualTo(new Point(200, 120));
    assertThat(opened.getFiles().unitPlaces(LAND))
        .as("place.txt")
        .containsExactly(new Point(224, 144));
    assertThat(opened.getFiles().places().decorations().get("ship.png"))
        .as("decorations.txt")
        .containsExactly(new Point(200, 120));
    assertThat(opened.getFiles().tilesPicture().orElseThrow().getWidth())
        .as("the picture put together from the tiles")
        .isEqualTo(800);
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("the game still loads in TripleA")
        .isEmpty();
  }

  @DisplayName("Made smaller and saved, the tiles beyond the map go, kept as .bak; undo and save"
      + " bring the picture back")
  @Test
  void savesSmallerAndBack() throws Exception {
    // given: 400x300 takes 2 by 2 tiles
    final MapProject project = givenMapWithPlaces();
    final Path tiles = project.getFolder().orElseThrow().resolve("baseTiles");

    // when
    whenResized(project, 0.5);
    project.save();

    // then
    assertThat(tiles.resolve("1_1.png")).as("no tile beyond 200x150").doesNotExist();
    assertThat(tiles.resolve("1_1.png.bak")).as("its first version").exists();

    // when
    project.undo();
    project.save();

    // then
    assertThat(ImageIO.read(tiles.resolve("1_1.png").toFile()))
        .as("the tile is back with the old picture")
        .isNotNull();
    assertThat(MapProject.open(project.getXmlFile().orElseThrow()).getFiles().size().width)
        .as("the saved map's width")
        .isEqualTo(400);
  }

  @DisplayName("A relief held from its tiles is resized and saved with the map")
  @Test
  void resizesRelief() throws Exception {
    // given
    final MapProject made = givenSavedMap();
    final BufferedImage relief = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = relief.createGraphics();
    g.setColor(new Color(0, 0, 0, 80));
    g.fillRect(0, 0, 200, 300);
    g.dispose();
    made.setRelief(relief);
    made.save();
    final MapProject project = MapProject.open(made.getXmlFile().orElseThrow());
    project.holdPicture(project.getFiles().tilesPicture().orElseThrow());

    // when
    project.holdRelief(project.getFiles().reliefTilesPicture().orElseThrow());

    // then
    assertThat(project.hasUnsavedChanges()).as("holding the relief changes nothing").isFalse();

    // when
    whenResized(project, 2);
    project.save();

    // then
    final BufferedImage saved =
        MapProject.open(project.getXmlFile().orElseThrow())
            .getFiles()
            .reliefTilesPicture()
            .orElseThrow();
    assertThat(saved.getRGB(300, 300) >>> 24).as("the shaded left half reaches to x 400").isEqualTo(80);
    assertThat(saved.getRGB(500, 300) >>> 24).as("the right half stays see-through").isZero();
  }

  @DisplayName("Too big, too small or with a place file that does not read, a resize is refused")
  @Test
  void refusesWhatCannotBeDone() throws Exception {
    // given
    final MapProject project = givenSavedMap();
    final MapFiles files = project.getFiles();
    final Path places = project.getFolder().orElseThrow().resolve("place.txt");
    Files.writeString(places, Files.readString(places) + "\r\n" + LAND + " (1,2)\r\n");
    final MapFiles unread = MapProject.open(project.getXmlFile().orElseThrow()).getFiles();

    // then
    assertThat(files.whyNotResized(1.5)).as("150 % is fine").isEmpty();
    assertThat(files.whyNotResized(40))
        .as("16000x12000 is more than is held")
        .hasValueSatisfying(why -> assertThat(why).startsWith("Too big"));
    assertThat(files.whyNotResized(0.25))
        .as("100x75 leaves no room")
        .hasValueSatisfying(why -> assertThat(why).startsWith("Too small"));
    assertThat(unread.whyNotResized(1.5))
        .as("place.txt names a territory twice, so it does not read")
        .hasValueSatisfying(why -> assertThat(why).startsWith("place.txt does not read"));
  }

  @DisplayName("The dialog says the new size and turns its verb off for a size refused")
  @Test
  void dialogSaysTheSize() throws Exception {
    // given
    final ResizeDialog dialog = new ResizeDialog(givenSavedMap().getFiles());

    // then
    assertThat(dialog.resultText()).as("150 % to start with").isEqualTo("New size: 600 × 450 pixels");
    assertThat(dialog.isValid()).as("150 % can be done").isTrue();

    // when
    dialog.setPercent(25);

    // then
    assertThat(dialog.resultText()).as("100x75 is refused").startsWith("Too small");
    assertThat(dialog.isValid()).as("the verb is off").isFalse();

    // when
    dialog.setPercent(100);

    // then
    assertThat(dialog.isValid()).as("the size it has is no resize").isFalse();
  }

  @DisplayName("Home notes units too big for most land territories, and not once they fit")
  @Test
  void notesBigUnits() throws Exception {
    // given: four land territories of about 200x110
    final MapProject project = givenSavedMap();
    final HomeEditor home =
        new HomeEditor(project, t -> {}, t -> {}, s -> {}, () -> {}, () -> {}, () -> {});

    // then
    assertThat(home.notes()).as("48 px units fit").noneMatch(n -> n.text().startsWith("Units are big"));

    // when: units of 240 px fit into none
    project.setMapProperty("units.scale", "5");

    // then
    assertThat(home.notes())
        .as("the note, fixed in Map look")
        .anyMatch(n -> n.text().startsWith("Units are big") && n.tool().equals(java.util.Optional.of("Map look")));

    // when
    project.setMapProperty("units.scale", "0.5");

    // then
    assertThat(home.notes()).as("24 px units fit").noneMatch(n -> n.text().startsWith("Units are big"));
  }
}
