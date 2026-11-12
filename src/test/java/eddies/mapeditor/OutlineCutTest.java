package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Point;
import java.awt.Polygon;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import eddies.mapeditor.OutlineCut.Cut;

final class OutlineCutTest {
  @TempDir Path folder;

  private static Polygon givenSquare(final int x, final int y) {
    return new Polygon(new int[] {x, x + 100, x + 100, x}, new int[] {y, y, y + 100, y + 100}, 4);
  }

  private static List<Point> givenLine(final int... xy) {
    return IntStream.range(0, xy.length / 2)
        .mapToObj(i -> new Point(xy[2 * i], xy[2 * i + 1]))
        .toList();
  }

  @DisplayName("A line across a square cuts it: the larger part is kept, the smaller split off")
  @Test
  void cutsSquare() {
    final Polygon square = givenSquare(0, 0);

    final Cut cut = OutlineCut.cut(List.of(square), givenLine(60, -10, 60, 110)).orElseThrow();

    assertThat(cut.kept()).as("one kept part").hasSize(1);
    assertThat(cut.kept().get(0).getBounds())
        .as("the left part, up to the line")
        .matches(r -> r.x == 0 && Math.abs(r.x + r.width - 60) <= 1 && r.height == 100);
    assertThat(cut.split()).as("one split part").hasSize(1);
    assertThat(cut.split().get(0).getBounds())
        .as("the right part, from the line")
        .matches(r -> Math.abs(r.x - 60) <= 1 && r.x + r.width == 100 && r.height == 100);
  }

  @DisplayName("A line ending inside, outside the outline or cutting into three parts is refused")
  @Test
  void refusesBadLines() {
    final List<Polygon> square = List.of(givenSquare(0, 0));

    assertThat(OutlineCut.cut(square, givenLine(60, -10, 60, 50)))
        .as("ending inside leaves only a slit")
        .isEmpty();
    assertThat(OutlineCut.cut(square, givenLine(150, -10, 150, 110)))
        .as("a line beside it cuts nothing")
        .isEmpty();
    assertThat(OutlineCut.cut(square, givenLine(30, -10, 30, 110, 70, 110, 70, -10)))
        .as("a zigzag makes three parts")
        .isEmpty();
  }

  @DisplayName("An outline the line misses stays with the kept part")
  @Test
  void keepsMissedOutline() {
    final Polygon island = givenSquare(200, 0);

    final Cut cut =
        OutlineCut.cut(List.of(givenSquare(0, 0), island), givenLine(60, -10, 60, 110))
            .orElseThrow();

    assertThat(cut.kept()).as("the larger part and the island").hasSize(2).contains(island);
    assertThat(cut.split()).as("only the cut off part").hasSize(1);
  }

  @DisplayName("Outlines touch at a shared edge or a border line apart, not 20 px apart")
  @Test
  void touches() {
    final List<Polygon> square = List.of(givenSquare(0, 0));

    assertThat(OutlineCut.touches(square, List.of(givenSquare(100, 0))))
        .as("a shared edge")
        .isTrue();
    assertThat(OutlineCut.touches(square, List.of(givenSquare(103, 0)))).as("3 px apart").isTrue();
    assertThat(OutlineCut.touches(square, List.of(givenSquare(120, 0))))
        .as("20 px apart")
        .isFalse();
  }

  private MapProject givenMapWithRedTerritory4() throws Exception {
    final MapProject project =
        MapProject.newMap(
                "Island Wars",
                MapPictureTest.givenPicture(),
                List.of("Red"),
                folder.resolve("maps"))
            .project();
    project.edit(g -> MapXml.withOwner(g, "Territory 4", "Red"));
    return project;
  }

  @DisplayName("A split territory's smaller part becomes a new one beside it, one undo step")
  @Test
  void splitsTerritory() throws Exception {
    // Territory 4 is the bottom right square, x 200-400, y 110-220; Territory 3 left of it,
    // Territory 2 above, the sea below
    final MapProject project = givenMapWithRedTerritory4();
    final Game before = project.game();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"), givenLine(350, 0, 350, 300))
            .orElseThrow();

    final boolean split = project.splitTerritory("Territory 4", cut, "East");
    final Game after = project.game();
    project.save();
    final Path xml = project.getXmlFile().orElseThrow();
    project.undo();

    assertThat(split).as("a free name is taken").isTrue();
    assertThat(MapXml.isWater(after, "East")).as("land as Territory 4").isFalse();
    assertThat(MapXml.owner(after, "East")).as("Territory 4's owner").contains("Red");
    assertThat(MapXml.neighbors(after, "East"))
        .as("the old one and the neighbours it touches, not the far Territory 3")
        .containsExactlyInAnyOrder("Territory 4", "Territory 2", "Sea Zone 1");
    assertThat(MapXml.neighbors(after, "Territory 4"))
        .as("Territory 4 keeps its neighbours and gets East")
        .containsExactlyInAnyOrder("Territory 2", "Territory 3", "Sea Zone 1", "East");
    assertThat(MapChecks.findError(xml)).as("the saved game loads in TripleA").isEmpty();
    assertThat(project.game()).as("one undo takes the split back").isSameAs(before);
    assertThat(project.getFiles().polygons()).as("and the outlines").doesNotContainKey("East");
  }

  @DisplayName("A split draws its border into the picture within the territory, as wide as asked")
  @Test
  void splitDrawsBorder() throws Exception {
    final MapProject project = givenMapWithRedTerritory4();
    final BufferedImage before = project.getFiles().picture().orElseThrow();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"), givenLine(350, 0, 350, 300))
            .orElseThrow();

    project.splitTerritory(
        "Territory 4",
        cut,
        "East",
        Optional.of(
            new MapProject.BorderLine(givenLine(350, 0, 350, 300), 5, new Color(10, 20, 30))));
    final BufferedImage after = project.getFiles().picture().orElseThrow();
    project.undo();

    assertThat(after.getRGB(350, 160) & 0xFFFFFF).as("drawn in the color given").isEqualTo(0x0A141E);
    assertThat(after.getRGB(348, 160)).as("5 px wide").isEqualTo(after.getRGB(350, 160));
    assertThat(after.getRGB(346, 160)).as("not wider").isEqualTo(before.getRGB(346, 160));
    assertThat(after.getRGB(350, 60))
        .as("not in Territory 2 above, which the line crosses too")
        .isEqualTo(before.getRGB(350, 60));
    assertThat(after.getRGB(350, 260)).as("nor in the sea below").isEqualTo(before.getRGB(350, 260));
    assertThat(project.getFiles().picture().orElseThrow().getRGB(350, 160))
        .as("one undo takes the line back")
        .isEqualTo(before.getRGB(350, 160));
  }

  @DisplayName("A split's border is saved into the tiles; undone and saved, it is gone from them")
  @Test
  void splitBorderSavedAndUndone() throws Exception {
    final MapProject project = givenMapWithRedTerritory4();
    final Path tile = project.getFolder().orElseThrow().resolve("baseTiles/1_0.png");
    final int size = MapFiles.tileSize();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"), givenLine(350, 0, 350, 300))
            .orElseThrow();

    project.splitTerritory(
        "Territory 4",
        cut,
        "East",
        Optional.of(new MapProject.BorderLine(givenLine(350, 0, 350, 300), 3, Color.RED)));
    project.save();
    final int saved = ImageIO.read(tile.toFile()).getRGB(350 - size, 160);
    project.undo();
    project.save();
    final int undone = ImageIO.read(tile.toFile()).getRGB(350 - size, 160);

    assertThat(saved & 0xFFFFFF).as("the save wrote the border").isEqualTo(0xFF0000);
    assertThat(undone & 0xFFFFFF).as("undone and saved, the tile is as before").isNotEqualTo(0xFF0000);
  }

  @DisplayName("A split's border meets the picture's line at the neighbour and stops there")
  @Test
  void splitBorderStopsAtTheLine() throws Exception {
    // the picture's 3 px line between Territory 3 and 4 is at x 199 to 201
    final MapProject project = givenMapWithRedTerritory4();
    final BufferedImage before = project.getFiles().picture().orElseThrow();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"), givenLine(150, 160, 450, 160))
            .orElseThrow();

    project.splitTerritory(
        "Territory 4",
        cut,
        "South",
        Optional.of(new MapProject.BorderLine(givenLine(150, 160, 450, 160), 3, Color.RED)));
    final BufferedImage after = project.getFiles().picture().orElseThrow();

    assertThat(after.getRGB(205, 160) & 0xFFFFFF).as("drawn in Territory 4").isEqualTo(0xFF0000);
    assertThat(after.getRGB(201, 160) & 0xFFFFFF)
        .as("up to the picture's line")
        .isEqualTo(0xFF0000);
    assertThat(after.getRGB(196, 160))
        .as("not past it into Territory 3")
        .isEqualTo(before.getRGB(196, 160));
  }

  @DisplayName("A neighbour only the split-off part touches is no longer the old one's")
  @Test
  void splitTakesTheOnlyEdgeToANeighbour() throws Exception {
    // a cut along y = 190 leaves Territory 4 its top, 30 px from the sea; the new part has the
    // coast
    final MapProject project = givenMapWithRedTerritory4();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"), givenLine(150, 190, 450, 190))
            .orElseThrow();

    project.splitTerritory("Territory 4", cut, "Coast");

    assertThat(MapXml.neighbors(project.game(), "Territory 4"))
        .as("the sea went to the new part")
        .doesNotContain("Sea Zone 1")
        .contains("Coast", "Territory 2", "Territory 3");
    assertThat(MapXml.neighbors(project.game(), "Coast"))
        .as("the coast is the new part's")
        .contains("Sea Zone 1", "Territory 4");
  }

  @DisplayName("A split sea zone makes a new sea zone that still loads")
  @Test
  void splitsSeaZone() throws Exception {
    // Sea Zone 1 is the bottom strip, x 0-400, y 220-300, below Territory 3 and 4
    final MapProject project = givenMapWithRedTerritory4();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Sea Zone 1"), givenLine(300, 200, 300, 310))
            .orElseThrow();

    project.splitTerritory("Sea Zone 1", cut, "East Sea");
    project.save();

    assertThat(MapXml.isWater(project.game(), "East Sea")).as("water as Sea Zone 1").isTrue();
    assertThat(MapXml.neighbors(project.game(), "East Sea"))
        .as("the old sea and the land above the split part")
        .containsExactlyInAnyOrder("Sea Zone 1", "Territory 4");
    assertThat(MapChecks.findError(project.getXmlFile().orElseThrow()))
        .as("the saved game loads in TripleA")
        .isEmpty();
  }

  @DisplayName("A split with a taken name changes nothing")
  @Test
  void refusesTakenName() throws Exception {
    final MapProject project = givenMapWithRedTerritory4();
    final Game before = project.game();
    final Cut cut =
        OutlineCut.cut(
                project.getFiles().polygons().get("Territory 4"), givenLine(350, 0, 350, 300))
            .orElseThrow();

    final boolean split = project.splitTerritory("Territory 4", cut, "Territory 2");

    assertThat(split).as("the name is taken").isFalse();
    assertThat(project.game()).as("nothing changed").isSameAs(before);
  }
}
