package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.util.Tuple;
import eddies.mapeditor.MapFiles.NationUnit;
import eddies.mapeditor.MapFiles.Redrawn;

final class MapFilesTest {
  // the areas of MapPictureTest.givenPicture, as Make territories names them
  private static final String TOP_LEFT = "Territory 1";
  private static final String TOP_RIGHT = "Territory 2";
  private static final String UNCHANGED = "Territory 3";

  @TempDir Path folder;

  @DisplayName("The shown picture: the tiles, the relief tiles over them, a missing one clear")
  @Test
  void shownPictureFromTiles() throws Exception {
    // given: a map of 2 x 2 tiles, base tiles blue and green above, none below, one relief tile
    // see-through at its left half, red at its right
    final int size = MapFiles.tileSize();
    givenTile("baseTiles", "0_0.png", Color.BLUE, size);
    givenTile("baseTiles", "1_0.png", Color.GREEN, size);
    final BufferedImage relief = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = relief.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(size / 2, 0, size / 2, size);
    g.dispose();
    Files.createDirectories(folder.resolve("reliefTiles"));
    ImageIO.write(relief, "png", folder.resolve("reliefTiles/0_0.png").toFile());
    final MapFiles files =
        MapFiles.none()
            .withFolder(folder)
            .withProperty("map.width", String.valueOf(2 * size))
            .withProperty("map.height", String.valueOf(2 * size));

    // when
    final BufferedImage shown = files.shownPicture().orElseThrow();

    // then
    assertThat(files.hasPictureOrRelief()).as("it has tiles").isTrue();
    assertThat(MapFiles.none().hasPictureOrRelief()).as("no files, no picture").isFalse();
    assertThat(shown.getRGB(10, 10)).as("the base tile under a clear relief").isEqualTo(0xFF0000FF);
    assertThat(shown.getRGB(size - 10, 10)).as("the relief over it").isEqualTo(0xFFFF0000);
    assertThat(shown.getRGB(size + 10, 10))
        .as("the second tile in its place")
        .isEqualTo(0xFF00FF00);
    assertThat(shown.getRGB(10, size + 10) >>> 24).as("a missing tile stays clear").isZero();
  }

  private void givenTile(final String tiles, final String name, final Color color, final int size)
      throws Exception {
    final BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = tile.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, size, size);
    g.dispose();
    Files.createDirectories(folder.resolve(tiles));
    ImageIO.write(tile, "png", folder.resolve(tiles).resolve(name).toFile());
  }

  private static MapFiles givenFilesOfPicture() {
    final BufferedImage picture = MapPictureTest.givenPicture();
    return MapFiles.none().withPicture(picture, MapPicture.detect(picture));
  }

  /** The picture with the top border between left and right drawn elsewhere. */
  static BufferedImage givenPictureWithBorderAt(final int x) {
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(230, 180, 180));
    g.fillRect(0, 0, 400, 109);
    g.setColor(Color.BLACK);
    g.fillRect(x, 0, 3, 109);
    g.dispose();
    return picture;
  }

  /** The picture with a small island ringed by a border in its sea. */
  private static BufferedImage givenPictureWithIsland() {
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(90, 240, 34, 34);
    g.setColor(new Color(240, 230, 150));
    g.fillRect(92, 242, 30, 30);
    g.dispose();
    return picture;
  }

  private static Polygon rectangle(final Rectangle r) {
    return new Polygon(
        new int[] {r.x, r.x + r.width, r.x + r.width, r.x},
        new int[] {r.y, r.y, r.y + r.height, r.y + r.height},
        4);
  }

  private static Rectangle bounds(final MapFiles files, final String territory) {
    return files.polygons().get(territory).stream()
        .map(Polygon::getBounds)
        .reduce(Rectangle::union)
        .orElseThrow();
  }

  @DisplayName("Redrawn from the same picture, outlines a few pixels apart count as unchanged")
  @Test
  void sameBorderWithinPixels() {
    final BufferedImage picture = givenPictureWithIsland();
    MapFiles files = MapFiles.none().withPicture(picture, MapPicture.detect(picture));
    // outlines as found before they reached into the line: 2 pixels inside it
    for (final String territory : files.polygons().keySet()) {
      final Rectangle inside = bounds(files, territory);
      inside.grow(-2, -2);
      files =
          files.withOutlines(territory, List.of(rectangle(inside)));
    }

    final Redrawn redrawn = files.withBordersFrom(picture, MapPicture.detect(picture));

    assertThat(redrawn.changed())
        .as("no territory changed noticeably, the island included, so places stay")
        .isEmpty();
  }

  @DisplayName("A moved border moves the outlines of both territories; names stay, none kept")
  @Test
  void redrawsMovedBorder() {
    final MapFiles files = givenFilesOfPicture();
    final BufferedImage moved = givenPictureWithBorderAt(299);

    final Redrawn redrawn = files.withBordersFrom(moved, MapPicture.detect(moved));

    assertThat(redrawn.kept()).as("every outline found its area").isEmpty();
    assertThat(redrawn.changed())
        .as("only the two at the moved border changed")
        .containsExactly(TOP_LEFT, TOP_RIGHT);
    assertThat(bounds(redrawn.files(), TOP_LEFT).getMaxX())
        .as("the top left territory now reaches the moved border")
        .isGreaterThan(290);
    assertThat(bounds(redrawn.files(), TOP_RIGHT).getMinX())
        .as("the top right one starts there")
        .isGreaterThan(290);
    assertThat(redrawn.files().polygons().keySet())
        .as("the same territories")
        .isEqualTo(files.polygons().keySet());
    assertThat(redrawn.files().centers().get(TOP_RIGHT))
        .as("a center the new border leaves outside moves inside")
        .matches(center -> bounds(redrawn.files(), TOP_RIGHT).contains(center));
    assertThat(redrawn.files().picture()).as("the picture is the map's").contains(moved);
  }

  @DisplayName("Where two territories share one area, the second keeps its border")
  @Test
  void keepsBorderOfMergedArea() {
    final MapFiles files = givenFilesOfPicture();
    final BufferedImage merged = givenPictureWithBorderAt(500);

    final Redrawn redrawn = files.withBordersFrom(merged, MapPicture.detect(merged));

    assertThat(redrawn.kept()).as("the area went to the top left one").containsExactly(TOP_RIGHT);
    assertThat(redrawn.files().polygons().get(TOP_RIGHT))
        .as("its old outline")
        .isEqualTo(files.polygons().get(TOP_RIGHT));
    assertThat(redrawn.files().centers().get(TOP_LEFT))
        .as("a center still inside stays")
        .isEqualTo(files.centers().get(TOP_LEFT));
    assertThat(redrawn.merged().values())
        .as("one area now, the one that took it first")
        .containsExactly(List.of(TOP_LEFT, TOP_RIGHT));
    assertThat(redrawn.added()).as("no area is new").isEmpty();
  }

  /** The picture with a border drawn through the bottom right area, at x = 330. */
  static BufferedImage givenPictureWithNewBorder() {
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    g.fillRect(330, 110, 3, 110);
    g.dispose();
    return picture;
  }

  @DisplayName("An area drawn into a territory that none takes is told as added, land in it")
  @Test
  void tellsAddedArea() {
    final MapFiles files = givenFilesOfPicture();
    final BufferedImage split = givenPictureWithNewBorder();

    final Redrawn redrawn = files.withBordersFrom(split, MapPicture.detect(split));

    assertThat(redrawn.added()).as("one area no territory took").hasSize(1);
    assertThat(redrawn.added().getFirst().water()).as("land").isFalse();
    assertThat(files.polygons().get("Territory 4"))
        .as("it lies in the old bottom right territory")
        .anyMatch(outline -> outline.contains(redrawn.added().getFirst().center()));
    assertThat(redrawn.merged()).as("nothing merged").isEmpty();
  }

  @DisplayName("An island rubbed out of the picture keeps its border instead of taking the sea's")
  @Test
  void keepsIslandInsideSea() {
    final BufferedImage withIsland = givenPictureWithIsland();
    final MapFiles files = MapFiles.none().withPicture(withIsland, MapPicture.detect(withIsland));
    final String island =
        files.polygons().keySet().stream()
            .filter(t -> bounds(files, t).width < 40)
            .findFirst()
            .orElseThrow();
    final BufferedImage withoutIsland = MapPictureTest.givenPicture();

    final Redrawn redrawn = files.withBordersFrom(withoutIsland, MapPicture.detect(withoutIsland));

    assertThat(redrawn.kept())
        .as("only the island found no area of its own")
        .containsExactly(island);
    assertThat(bounds(redrawn.files(), island).width)
        .as("still the island's small outline")
        .isLessThan(40);
    assertThat(redrawn.merged().values())
        .as("merged into the sea, the sea zone first to keep it")
        .containsExactly(List.of("Sea Zone 1", island));
  }

  /** The picture with its top left area cut by four borders into five strips. */
  private static BufferedImage givenPictureWithStrips() {
    final BufferedImage picture = MapPictureTest.givenPicture();
    final Graphics2D g = picture.createGraphics();
    g.setColor(Color.BLACK);
    for (int x = 38; x < 199; x += 40) {
      g.fillRect(x, 0, 3, 109);
    }
    g.dispose();
    return picture;
  }

  @DisplayName("Borders rubbed out between five small territories merge them, no new area")
  @Test
  void mergesSmallTerritoriesNoneTook() {
    final BufferedImage strips = givenPictureWithStrips();
    final MapFiles files = MapFiles.none().withPicture(strips, MapPicture.detect(strips));
    final List<String> small =
        files.polygons().keySet().stream().filter(t -> bounds(files, t).width < 45).toList();
    final BufferedImage joined = MapPictureTest.givenPicture();

    final Redrawn redrawn = files.withBordersFrom(joined, MapPicture.detect(joined));

    assertThat(small).as("five strips in the old map").hasSize(5);
    assertThat(redrawn.added())
        .as("the joined area is old land, no new territory on top of the strips")
        .isEmpty();
    assertThat(redrawn.merged().values())
        .as("one area now, made of the five strips")
        .singleElement()
        .satisfies(members -> assertThat(members).containsExactlyInAnyOrderElementsOf(small));
  }

  @DisplayName("Found places for units follow the map's units.scale: half size, a 24 pixel grid")
  @Test
  void foundPlacesFollowUnitScale() {
    final MapFiles files = givenFilesOfPicture().withProperty("units.scale", "0.5");

    final List<Point> places = files.unitPlaces(TOP_LEFT);

    assertThat(files.unitSize()).as("a unit at half size").isEqualTo(new Dimension(24, 24));
    assertThat(places)
        .as("on a grid of half units through the first")
        .allMatch(p -> (p.x - places.getFirst().x) % 24 == 0)
        .allMatch(p -> (p.y - places.getFirst().y) % 24 == 0)
        .anyMatch(p -> Math.abs(p.x - places.getFirst().x) == 24);
  }

  // a unit of 48 px with 2 px around it: one place every 52 px
  private static final int STEP = 52;

  /** A territory "Square" of 480 px, its center at 340, 340. */
  private static MapFiles givenSquare() {
    return MapFiles.none().withOutlines("Square", List.of(square(100, 100, 480)));
  }

  /** The place of the grid square so many squares right and down of the center's (316, 316). */
  private static Point squareAt(final int right, final int down) {
    return new Point(316 + right * STEP, 316 + down * STEP);
  }

  @DisplayName("Laid out, squeeze starts above the center, grid below; both leave the center free")
  @Test
  void laysOutUnitPlaces() {
    final MapFiles files = givenSquare();
    final Polygon outline = files.polygons().get("Square").getFirst();
    final Point center = files.centers().get("Square");
    final Dimension unit = files.unitSize();

    final List<Point> squeeze =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.SQUEEZE, 100, "Square").getFirst();
    final List<Point> grid =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 100, "Square").getFirst();

    assertThat(center).as("the square's middle").isEqualTo(new Point(340, 340));
    assertThat(squeeze.subList(0, 2))
        .as("straight above the center, then clockwise to the right")
        .containsExactly(squareAt(0, -1), squareAt(1, -1));
    assertThat(grid.subList(0, 5))
        .as("the row below the center from 6:00, left first: left to right 4 2 1 3 5")
        .containsExactly(squareAt(0, 1), squareAt(-1, 1), squareAt(1, 1), squareAt(-2, 1),
            squareAt(2, 1));
    assertThat(grid.subList(9, 11))
        .as("the row below the first, after its 9 places, left to right")
        .containsExactly(squareAt(-4, 2), squareAt(-3, 2));
    assertThat(grid.get(2).x - grid.get(0).x - unit.width)
        .as("4 px between neighbouring units")
        .isEqualTo(4);
    for (final List<Point> places : List.of(squeeze, grid)) {
      assertThat(places)
          .as("every unit wholly inside, none on the center, 9 by 9 but the center")
          .allMatch(p -> outline.contains(new Rectangle(p, unit)))
          .noneMatch(p -> new Rectangle(p, unit).contains(center))
          .doesNotHaveDuplicates()
          .hasSize(80);
    }
  }

  @DisplayName("Laid out to a limit, the last place ends the bottom row, where there is more room")
  @Test
  void laysOutUnitPlacesToLimit() {
    final MapFiles files = givenSquare();

    final Tuple<List<Point>, Boolean> grid =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 16, "Square");
    final Tuple<List<Point>, Boolean> squeeze =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.SQUEEZE, 16, "Square");

    assertThat(grid.getFirst()).as("at most 16").hasSize(16);
    assertThat(squeeze.getFirst()).as("at most 16").hasSize(16);
    assertThat(grid.getFirst().getLast())
        .as("9 in the first row, 7 in the second from the left: its right end, 2 free beyond it")
        .isEqualTo(squareAt(2, 2));
    assertThat(grid.getSecond()).as("extra units go right").isFalse();
  }

  @DisplayName("Laid out where the bottom row has more room on the left, extra units go left")
  @Test
  void laysOutOverflowToRoomyLeft() {
    // wide on top, and below the center only to the left of it
    final Polygon outline =
        new Polygon(new int[] {0, 1000, 1000, 600, 600, 0}, new int[] {0, 0, 100, 100, 300, 300}, 6);
    final MapFiles files = MapFiles.none().withOutlines("Hook", List.of(outline));
    final Point center = files.centers().get("Hook");
    final Point first = new Point(center.x - 24, center.y + 28);

    final Tuple<List<Point>, Boolean> laidOut =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 3, "Hook");

    assertThat(laidOut.getFirst())
        .as("6:00 first, the left end of the row last")
        .containsExactly(
            first, new Point(first.x + STEP, first.y), new Point(first.x - STEP, first.y));
    assertThat(laidOut.getSecond()).as("extra units go left, where the row has room").isTrue();
  }

  @DisplayName("Laid out in a sea around an island, no place lies on the island")
  @Test
  void laysOutAroundIsland() {
    // the sea's outline has no hole: the island is only the other territory's outline
    final Polygon island = square(330, 380, 120);
    final MapFiles files = givenSquare().withOutlines("Island", List.of(island));

    final List<Point> places =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 100, "Square").getFirst();

    assertThat(places)
        .as("around the island, never on it")
        .isNotEmpty()
        .noneMatch(p -> island.intersects(new Rectangle(p, files.unitSize())));
  }

  @DisplayName("A territory's picture is its part of the map, see-through outside its outline")
  @Test
  void cutsTerritoryPicture() {
    // given: a triangle on a red map
    final Polygon triangle = new Polygon(new int[] {100, 300, 100}, new int[] {50, 50, 250}, 3);
    final MapFiles files = MapFiles.none().withOutlines("Corner", List.of(triangle));
    final BufferedImage map = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = map.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, 400, 300);
    g.dispose();

    // when
    final MapFiles.TerritoryPicture cut = files.territoryPicture(map, "Corner").orElseThrow();

    // then
    assertThat(cut.at()).as("its top left on the map").isEqualTo(new Point(100, 50));
    assertThat(cut.picture().getWidth()).as("as wide as the outline").isEqualTo(200);
    assertThat(new Color(cut.picture().getRGB(10, 10), true))
        .as("inside: the map")
        .isEqualTo(Color.RED);
    assertThat(cut.picture().getRGB(190, 190) >>> 24)
        .as("outside the outline: see-through")
        .isZero();
    assertThat(files.territoryPicture(map, "Nowhere")).as("no outline, no picture").isEmpty();
  }

  @DisplayName("A territory too small for a laid out unit keeps a found place")
  @Test
  void laidOutTooSmallKeepsFoundPlace() {
    final MapFiles files = MapFiles.none().withOutlines("Speck", List.of(square(0, 0, 10)));

    final List<Point> places =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.SQUEEZE, 16, "Speck").getFirst();

    assertThat(places).as("as found today").isEqualTo(files.unitPlaces("Speck")).isNotEmpty();
  }

  @DisplayName("A redrawn border finds the places of its territories again; the others keep theirs")
  @Test
  void redrawnBorderFindsPlacesAgain() {
    final Point set = new Point(1, 1);
    final MapFiles files =
        givenFilesOfPicture()
            .withUnitPlaces(TOP_LEFT, List.of(set))
            .withUnitPlaces(UNCHANGED, List.of(set));
    final BufferedImage moved = givenPictureWithBorderAt(299);

    final MapFiles redrawn = files.withBordersFrom(moved, MapPicture.detect(moved)).files();

    assertThat(redrawn.unitPlaces(TOP_LEFT))
        .as("found inside the moved border")
        .doesNotContain(set)
        .allMatch(place -> bounds(redrawn, TOP_LEFT).contains(place));
    assertThat(redrawn.unitPlaces(UNCHANGED)).as("a border not moved").containsExactly(set);
  }

  @DisplayName("Imported pictures belong to the nation; taking them back keeps other nations'")
  @Test
  void importsNationUnitPictures() {
    final BufferedImage picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    final MapFiles imported =
        MapFiles.none()
            .withNationUnitPictures("Red", Map.of("infantry", picture))
            .withNationUnitPictures("Blue", Map.of("tank", picture));

    final MapFiles takenBack = imported.withNationUnitPictures("Red", Map.of());

    assertThat(imported.nationUnitPictures())
        .as("one picture each")
        .containsOnlyKeys(new NationUnit("Red", "infantry"), new NationUnit("Blue", "tank"));
    assertThat(takenBack.nationUnitPictures())
        .as("Blue's stays")
        .containsOnlyKeys(new NationUnit("Blue", "tank"));
  }

  @DisplayName("New outlines keep a center still inside, move one left outside, find places again")
  @Test
  void takesNewOutlines() {
    final Point set = new Point(1, 1);
    final MapFiles files = givenFilesOfPicture().withUnitPlaces(TOP_LEFT, List.of(set));
    final Point center = files.centers().get(TOP_LEFT);
    final Polygon aroundCenter = square(center.x - 5, center.y - 5, 10);
    final Polygon elsewhere = square(center.x + 50, center.y + 50, 20);

    final MapFiles kept = files.withOutlines(TOP_LEFT, List.of(aroundCenter));
    final MapFiles moved = files.withOutlines(TOP_LEFT, List.of(elsewhere));

    assertThat(kept.centers().get(TOP_LEFT)).as("still inside, so it stays").isEqualTo(center);
    assertThat(moved.centers().get(TOP_LEFT))
        .as("left outside, it moves into the first outline")
        .matches(elsewhere::contains);
    assertThat(moved.polygons().get(TOP_LEFT)).as("the new outline").containsExactly(elsewhere);
    assertThat(moved.places().units())
        .as("its unit places are found again")
        .doesNotContainKey(TOP_LEFT);
  }

  private static Polygon square(final int x, final int y, final int size) {
    return new Polygon(
        new int[] {x, x + size, x + size, x}, new int[] {y, y, y + size, y + size}, 4);
  }

  @DisplayName("A removed territory leaves no outline, center or places")
  @Test
  void removesTerritory() {
    final MapFiles files =
        givenFilesOfPicture().withUnitPlaces(TOP_LEFT, List.of(new Point(1, 1)));

    final MapFiles without = files.withoutTerritory(TOP_LEFT);

    assertThat(without.polygons()).as("no outline").doesNotContainKey(TOP_LEFT);
    assertThat(without.centers()).as("no center").doesNotContainKey(TOP_LEFT);
    assertThat(without.places().units()).as("no unit places").doesNotContainKey(TOP_LEFT);
    assertThat(without.polygons()).as("the others stay").containsKey(TOP_RIGHT);
  }
}
