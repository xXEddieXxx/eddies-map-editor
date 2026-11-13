package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit places where a territory has little room: off the grid, sticking out, and by hand. */
final class UnitRoomTest {
  private static final String STAIRS = "Stairs";
  private static final String STRIP = "Strip";

  /**
   * Three boxes of 60 by 56 px, each half a grid step right of the one above: beside the center's
   * square, in the middle box, no square of the grid fits.
   */
  private static MapFiles givenStairs() {
    final Polygon stairs =
        new Polygon(
            new int[] {100, 160, 160, 186, 186, 212, 212, 152, 152, 126, 126, 100},
            new int[] {0, 0, 56, 56, 112, 112, 168, 168, 112, 112, 56, 56},
            12);
    return MapFiles.none().withOutlines(STAIRS, List.of(stairs)).withCenter(STAIRS, new Point(156, 84));
  }

  /** A strip 40 px wide, narrower than a unit's 48 px, and 240 px high. */
  private static MapFiles givenStrip() {
    return MapFiles.none()
        .withOutlines(STRIP, List.of(box(100, 0, 40, 240)))
        .withCenter(STRIP, new Point(120, 120));
  }

  private static Polygon box(final int x, final int y, final int width, final int height) {
    return new Polygon(
        new int[] {x, x + width, x + width, x}, new int[] {y, y, y + height, y + height}, 4);
  }

  private static boolean apart(final List<Point> places, final Dimension unit) {
    for (int i = 0; i < places.size(); i++) {
      for (int j = i + 1; j < places.size(); j++) {
        final Rectangle grown = new Rectangle(places.get(i), unit);
        grown.grow(2, 2);
        if (grown.intersects(new Rectangle(places.get(j), unit))) {
          return false;
        }
      }
    }
    return true;
  }

  @DisplayName("Where no square of the grid fits, places off the grid use the room, wholly inside")
  @Test
  void usesRoomOffTheGrid() {
    // given
    final MapFiles files = givenStairs();
    final Polygon outline = files.polygons().get(STAIRS).getFirst();
    final Dimension unit = files.unitSize();

    // when
    final List<Point> places =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 16, STAIRS).getFirst();

    // then
    assertThat(unit).as("the units' size the shape is made for").isEqualTo(new Dimension(48, 48));
    assertThat(places)
        .as("one in the box above and one in the box below, each wholly inside, off the center")
        .hasSize(2)
        .allMatch(p -> outline.contains(new Rectangle(p, unit)))
        .noneMatch(p -> new Rectangle(p, unit).contains(files.centers().get(STAIRS)));
    assertThat(apart(places, unit)).as("at least 2 px apart").isTrue();
  }

  @DisplayName("A territory narrower than a unit still gets three places, sticking out a little")
  @Test
  void narrowTerritoryGetsPlacesThatStickOut() {
    // given
    final MapFiles files = givenStrip();
    final Polygon outline = files.polygons().get(STRIP).getFirst();
    final Dimension unit = files.unitSize();

    // when
    final List<Point> places =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.SQUEEZE, 16, STRIP).getFirst();

    // then
    assertThat(places)
        .as("three, as no more than a few may stick out; each with its middle inside, off the"
            + " center")
        .hasSize(3)
        .allMatch(
            p -> outline.contains(p.x + unit.width / 2.0, p.y + unit.height / 2.0))
        .noneMatch(p -> outline.contains(new Rectangle(p, unit)))
        .noneMatch(p -> new Rectangle(p, unit).contains(files.centers().get(STRIP)));
    assertThat(apart(places, unit)).as("at least 2 px apart").isTrue();
    assertThat(files.unitPlacesLaidOut(MapFiles.UnitLayout.SQUEEZE, 2, STRIP).getFirst())
        .as("never more than asked for")
        .hasSize(2);
  }

  @DisplayName("Places that stick out avoid a neighbour's center and places where they can")
  @Test
  void stickingOutAvoidsNeighbours() {
    // given: a neighbour right of the strip, its center and a unit place beside the strip's top
    final MapFiles files =
        givenStrip()
            .withOutlines("Neighbour", List.of(box(140, 0, 200, 240)))
            .withCenter("Neighbour", new Point(142, 60))
            .withUnitPlaces("Neighbour", List.of(new Point(141, 150)));
    final Dimension unit = files.unitSize();

    // when
    final List<Point> places =
        files.unitPlacesLaidOut(MapFiles.UnitLayout.GRID, 16, STRIP).getFirst();

    // then
    assertThat(places)
        .as("places are found, none over the neighbour's center or its unit place")
        .isNotEmpty()
        .noneMatch(p -> new Rectangle(p, unit).contains(new Point(142, 60)))
        .noneMatch(p -> new Rectangle(p, unit).intersects(new Rectangle(new Point(141, 150), unit)));
  }

  @DisplayName("With the unit places shown, a click in the chosen territory adds one, also where"
      + " it does not wholly fit")
  @Test
  void clickAddsAPlaceInTheUnitsView() throws Exception {
    // given
    final MapProject project = MapProject.newEmpty();
    project.editFiles(files -> givenStrip());
    final List<Object> seen = new ArrayList<>();

    // when: the canvas shows the map at half its size, so the click is at 102, 230 of the map
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = new MapCanvas(project);
          canvas.setSize(400, 300);
          canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
          canvas.showPlaces(MapCanvas.ShownPlaces.NONE.with(MapCanvas.PlaceKind.UNITS));
          canvas.setSelected(STRIP);
          final BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
          final Graphics2D g = image.createGraphics();
          canvas.paint(g);
          g.dispose();
          final int before = project.getFiles().unitPlaces(STRIP).size();
          final MouseEvent click =
              new MouseEvent(
                  canvas, MouseEvent.MOUSE_CLICKED, 0, 0, 51, 115, 1, false, MouseEvent.BUTTON1);
          canvas.dispatchEvent(click);
          seen.add(project.getFiles().unitPlaces(STRIP).size() - before);
          canvas.addUnitPlacesByClick();
          canvas.dispatchEvent(click);
          seen.add(project.getFiles().unitPlaces(STRIP).size() - before);
          seen.add(project.getFiles().unitPlaces(STRIP).getLast());
        });

    // then
    assertThat(seen)
        .as("no place while clicks only select; then one, centered on the click at the strip's"
            + " edge, where the unit sticks out to the left and below")
        .containsExactly(0, 1, new Point(102 - 24, 230 - 24));
  }
}
