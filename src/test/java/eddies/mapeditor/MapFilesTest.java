package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import eddies.mapeditor.MapFiles.NationUnit;
import eddies.mapeditor.MapFiles.Redrawn;

final class MapFilesTest {
  // the areas of MapPictureTest.givenPicture, as Find territories names them
  private static final String TOP_LEFT = "Territory 1";
  private static final String TOP_RIGHT = "Territory 2";
  private static final String UNCHANGED = "Territory 3";

  private static MapFiles givenFilesOfPicture() {
    final BufferedImage picture = MapPictureTest.givenPicture();
    return MapFiles.none().withPicture(picture, MapPicture.detect(picture));
  }

  /** The picture with the top border between left and right drawn elsewhere. */
  private static BufferedImage givenPictureWithBorderAt(final int x) {
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

  private static Rectangle bounds(final MapFiles files, final String territory) {
    return files.polygons().get(territory).stream()
        .map(Polygon::getBounds)
        .reduce(Rectangle::union)
        .orElseThrow();
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
}
