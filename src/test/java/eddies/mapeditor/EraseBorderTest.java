package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The eraser's click: the whole border between two territories goes, so that they merge. */
final class EraseBorderTest {
  private static final int LINE = 3;

  /**
   * Green land of 300 by 200 px in a black frame, cut in three by black lines 3 px wide: one down
   * at x 100, from the top to the bottom, and one across the right part at y 100.
   */
  private static BufferedImage givenThreeAreas() {
    final BufferedImage picture = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = picture.createGraphics();
    g.setColor(new Color(0x80C080));
    g.fillRect(0, 0, 300, 200);
    g.setColor(Color.BLACK);
    g.fillRect(0, 0, 300, LINE);
    g.fillRect(0, 200 - LINE, 300, LINE);
    g.fillRect(0, 0, LINE, 200);
    g.fillRect(300 - LINE, 0, LINE, 200);
    g.fillRect(99, 0, LINE, 200);
    g.fillRect(99, 99, 201, LINE);
    g.dispose();
    return picture;
  }

  private static MapProject givenProject() {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = givenThreeAreas();
    project.loadPicture(picture, MapPicture.detect(picture));
    return project;
  }

  private static String at(final MapProject project, final int x, final int y) {
    return project.getFiles().territoryAt(x, y).orElseThrow();
  }

  private static boolean isLine(final BufferedImage picture, final int x, final int y) {
    return (picture.getRGB(x, y) & 0xFFFFFF) == 0;
  }

  @DisplayName("The border between two territories is every line pixel between them, no other's")
  @Test
  void erasesTheWholeSharedBorder() {
    // given the upper and the lower area on the right, and the one on the left
    final MapProject project = givenProject();
    final String upper = at(project, 200, 50);
    final String lower = at(project, 200, 150);
    final String left = at(project, 50, 100);

    // when a point of the line between the two right areas is asked for
    final Optional<MapFiles.SharedBorder> border =
        project.getFiles().sharedBorderAt(new Point(220, 100), LINE);
    final boolean erased = project.eraseBorder(border.orElseThrow());

    // then
    assertThat(MapXml.territories(project.game())).as("three areas").hasSize(3);
    assertThat(List.of(border.get().first(), border.get().second()))
        .as("the two that meet there")
        .containsExactlyInAnyOrder(upper, lower);
    assertThat(erased).as("erased").isTrue();
    final BufferedImage picture = project.getFiles().picture().orElseThrow();
    // but for the few pixels where it meets the third territory's line and the frame, which stay whole
    for (int x = 106; x < 294; x++) {
      for (int y = 99; y < 99 + LINE; y++) {
        assertThat(isLine(picture, x, y)).as("no line pixel left at %s, %s", x, y).isFalse();
      }
    }
    for (int y = 3; y < 197; y++) {
      assertThat(isLine(picture, 100, y))
          .as("the line of %s, the third territory, is whole at y %s", left, y)
          .isTrue();
    }
    assertThat(isLine(picture, 298, 100)).as("and the frame").isTrue();
    assertThat(MapPicture.detect(picture).territories())
        .as("the two areas are one for Make territories")
        .hasSize(2);
    project.undo();
    assertThat(isLine(project.getFiles().picture().orElseThrow(), 200, 100))
        .as("one undo brings the line back")
        .isTrue();
  }

  @DisplayName("No border is found where one territory or three meet")
  @Test
  void findsNoBorderInsideOrAtAJunction() {
    // given
    final MapFiles files = givenProject().getFiles();

    // when, then
    assertThat(files.sharedBorderAt(new Point(200, 50), LINE)).as("inside a territory").isEmpty();
    assertThat(files.sharedBorderAt(new Point(100, 100), LINE))
        .as("where the three meet")
        .isEmpty();
    assertThat(files.sharedBorderAt(new Point(100, 50), LINE)).as("on a border").isPresent();
  }

  @DisplayName("With the eraser the border under the mouse shows, and a click gives the whole of it")
  @Test
  void eraserHighlightsAndClicksABorder() throws Exception {
    // given
    final MapProject project = givenProject();
    final List<Object> seen = new ArrayList<>();
    final List<List<Point>> strokes = new ArrayList<>();

    // when: the canvas shows the map at half its size
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = new MapCanvas(project);
          canvas.setSize(400, 300);
          canvas.eraseLine(
              strokes::add, 9, LINE, border -> seen.add(project.eraseBorder(border)));
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_MOVED, 110, 50));
          seen.add(canvas.hoveredBorder().isPresent());
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_MOVED, 100, 25));
          seen.add(canvas.hoveredBorder().isPresent());
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, 110, 50));
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, 111, 50));
        });

    // then
    assertThat(seen)
        .as("the border shows on the line, not inside a territory; the click erased it")
        .containsExactly(true, false, true);
    assertThat(strokes).as("no dot was erased as a stroke").isEmpty();
    assertThat(MapPicture.detect(project.getFiles().picture().orElseThrow()).territories())
        .as("the two areas are one")
        .hasSize(2);
  }

  private static MouseEvent mouse(final MapCanvas canvas, final int id, final int x, final int y) {
    return new MouseEvent(
        canvas, id, System.currentTimeMillis(), 0, x, y, x, y, 1, false, MouseEvent.BUTTON1);
  }
}
