package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MapCanvasTest {

  private static MapCanvas givenCanvas() {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final MapCanvas canvas = new MapCanvas(project);
    canvas.setSize(400, 300);
    // a canvas outside a window has no font of its own, the badges need one
    canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    return canvas;
  }

  private static void drag(final MapCanvas canvas, final Point from, final Point to) {
    canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, from));
    canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED, to));
    canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, to));
  }

  private static MouseEvent mouse(final MapCanvas canvas, final int id, final Point at) {
    return new MouseEvent(
        canvas,
        id,
        System.currentTimeMillis(),
        InputEvent.BUTTON1_DOWN_MASK,
        at.x,
        at.y,
        at.x,
        at.y,
        1,
        false,
        MouseEvent.BUTTON1);
  }

  @DisplayName("Moving places after drawing a split line draws no line any more")
  @Test
  void editPlacesEndsTheLine() throws Exception {
    final List<List<Point>> lines = new ArrayList<>();
    final List<Integer> cursors = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.drawLine(lines::add);
          canvas.editPlaces();
          drag(canvas, new Point(20, 20), new Point(120, 60));
          cursors.add(canvas.getCursor().getType());
        });

    assertThat(lines).as("a drag while moving places offers no split").isEmpty();
    assertThat(cursors)
        .as("the crosshair of the line is gone")
        .containsExactly(Cursor.DEFAULT_CURSOR);
  }

  private static BufferedImage shown(final MapCanvas canvas) {
    final BufferedImage shown = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = shown.createGraphics();
    canvas.paint(g);
    g.dispose();
    return shown;
  }

  private static int brightness(final int rgb) {
    final Color color = new Color(rgb);
    return color.getRed() + color.getGreen() + color.getBlue();
  }

  @DisplayName("While a cutting line is drawn the other territories are veiled")
  @Test
  void drawingLineVeilsOthers() throws Exception {
    final List<Integer> brightness = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.setSelected("Territory 1");
          // (150, 25) on the screen at zoom 0.5 is in Territory 2, (50, 25) in Territory 1
          final BufferedImage before = shown(canvas);
          canvas.drawLine(lines -> {}, lines -> true);
          final BufferedImage drawing = shown(canvas);
          brightness.add(brightness(drawing.getRGB(150, 25)) - brightness(before.getRGB(150, 25)));
          brightness.add(brightness(drawing.getRGB(50, 25)) - brightness(before.getRGB(50, 25)));
        });

    assertThat(brightness.get(0)).as("another territory is lighter under the veil").isPositive();
    assertThat(brightness.get(1)).as("the territory to split stays as it is").isZero();
  }
  @DisplayName("A free line, drawn into the picture, veils nothing")
  @Test
  void freeLineVeilsNothing() throws Exception {
    // given
    final List<Integer> brightness = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.setSelected("Territory 1");
          final BufferedImage before = shown(canvas);
          canvas.drawLine(lines -> {});
          brightness.add(
              brightness(shown(canvas).getRGB(150, 25)) - brightness(before.getRGB(150, 25)));
        });

    // then
    assertThat(brightness.get(0)).as("another territory as it was").isZero();
  }

  /** A click whose hand shakes a pixel or two, so AWT would send no MOUSE_CLICKED. */
  private static void click(final MapCanvas canvas, final Point at, final int count) {
    for (final int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED}) {
      final int shake = id == MouseEvent.MOUSE_RELEASED ? 2 : 0;
      canvas.dispatchEvent(
          new MouseEvent(
              canvas,
              id,
              System.currentTimeMillis(),
              0,
              at.x,
              at.y,
              at.x + shake,
              at.y + shake,
              count,
              false,
              MouseEvent.BUTTON1));
    }
  }

  /** Presses the key as the window would: only while its action is enabled. */
  private static void press(final MapCanvas canvas, final int key) {
    final Object name =
        canvas
            .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .get(KeyStroke.getKeyStroke(key, 0));
    final Action action = canvas.getActionMap().get(name);
    if (action.isEnabled()) {
      action.actionPerformed(new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, ""));
    }
  }

  @DisplayName("A line of clicked points ends with a double-click, through every point")
  @Test
  void lineByPointsEndsWithDoubleClick() throws Exception {
    // given
    final List<List<Point>> lines = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.drawLineByPoints(lines::add);
          click(canvas, new Point(10, 10), 1);
          drag(canvas, new Point(50, 50), new Point(60, 60));
          click(canvas, new Point(100, 10), 1);
          click(canvas, new Point(100, 80), 1);
          click(canvas, new Point(102, 81), 2); // the hand shook between the two clicks
        });

    // then
    assertThat(lines)
        .as("one line, a drag and a shaky second click adding nothing, at zoom 0.5 in map px")
        .containsExactly(List.of(new Point(20, 20), new Point(200, 20), new Point(200, 160)));
  }

  @DisplayName("Backspace takes back a point, Esc the unfinished line, Enter ends the line")
  @Test
  void lineByPointsKeys() throws Exception {
    // given
    final List<List<Point>> lines = new ArrayList<>();
    final List<String> escaped = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.onEscape(Optional.of(() -> escaped.add("mode ended")));
          canvas.drawLineByPoints(lines::add);
          click(canvas, new Point(10, 10), 1);
          click(canvas, new Point(100, 10), 1);
          click(canvas, new Point(100, 80), 1);
          press(canvas, KeyEvent.VK_BACK_SPACE);
          press(canvas, KeyEvent.VK_ENTER);
          click(canvas, new Point(30, 30), 1);
          click(canvas, new Point(60, 30), 1);
          press(canvas, KeyEvent.VK_ESCAPE);
          press(canvas, KeyEvent.VK_ENTER);
          escaped.add("line dropped");
          press(canvas, KeyEvent.VK_ESCAPE);
        });

    // then
    assertThat(lines)
        .as("the line without its last point; the dropped one never ends")
        .containsExactly(List.of(new Point(20, 20), new Point(200, 20)));
    assertThat(escaped)
        .as("Esc drops the unfinished line first, then ends the mode")
        .containsExactly("line dropped", "mode ended");
  }

  @DisplayName("A single clicked point, ended, draws nothing")
  @Test
  void singlePointDrawsNothing() throws Exception {
    // given
    final List<List<Point>> lines = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.drawLineByPoints(lines::add);
          click(canvas, new Point(10, 10), 1);
          press(canvas, KeyEvent.VK_ENTER);
          click(canvas, new Point(40, 40), 1);
          click(canvas, new Point(40, 40), 2);
        });

    // then
    assertThat(lines).as("no line of one point").isEmpty();
  }

  @DisplayName("Drawing freehand after point by point drags a line again")
  @Test
  void freehandAfterPoints() throws Exception {
    // given
    final List<List<Point>> lines = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.drawLineByPoints(lines::add);
          canvas.drawLine(lines::add);
          drag(canvas, new Point(10, 10), new Point(100, 10));
        });

    // then
    assertThat(lines)
        .as("the freehand line dragged")
        .containsExactly(List.of(new Point(20, 20), new Point(200, 20)));
  }

  @DisplayName("A line is checked at most every 100 ms while dragged, and green when it cuts")
  @Test
  void lineCheckIsThrottledAndTinted() throws Exception {
    final List<Integer> checks = new ArrayList<>();
    final List<Integer> pixels = new ArrayList<>();
    final MapCanvas[] canvas = new MapCanvas[1];
    SwingUtilities.invokeAndWait(
        () -> {
          canvas[0] = givenCanvas();
          canvas[0].setSelected("Territory 1");
          canvas[0].drawLine(
              lines -> {},
              line -> {
                checks.add(line.size());
                return true;
              });
          canvas[0].dispatchEvent(mouse(canvas[0], MouseEvent.MOUSE_PRESSED, new Point(10, 80)));
          for (int x = 20; x <= 120; x += 10) {
            canvas[0].dispatchEvent(mouse(canvas[0], MouseEvent.MOUSE_DRAGGED, new Point(x, 80)));
          }
          checks.add(-1);
        });

    Thread.sleep(400);
    SwingUtilities.invokeAndWait(() -> pixels.add(shown(canvas[0]).getRGB(10, 80) & 0xFFFFFF));

    assertThat(checks)
        .as("no check per mouse move, one for all moves of the 100 ms, with the whole line")
        .containsExactly(-1, 12);
    assertThat(pixels)
        .as("the line's start handle in the success color, as the line cuts")
        .containsExactly(Theme.getSuccessColor().getRGB() & 0xFFFFFF);
  }

  @DisplayName("The part a split would make is hatched until editing stops")
  @Test
  void cutIsHatched() throws Exception {
    final List<Long> changed = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          final BufferedImage before = shown(canvas);
          canvas.setCut(
              List.of(new Polygon(new int[] {0, 200, 200, 0}, new int[] {0, 0, 110, 110}, 4)));
          changed.add(differing(before, shown(canvas)));
          canvas.stopEditingPlaces();
          changed.add(differing(before, shown(canvas)));
        });

    assertThat(changed.get(0)).as("stripes over the part").isPositive();
    assertThat(changed.get(1)).as("gone once editing stops").isZero();
  }

  private static long differing(final BufferedImage a, final BufferedImage b) {
    long count = 0;
    for (int x = 0; x < a.getWidth(); x++) {
      for (int y = 0; y < a.getHeight(); y++) {
        count += a.getRGB(x, y) == b.getRGB(x, y) ? 0 : 1;
      }
    }
    return count;
  }

  @DisplayName("In the Connections view the selected territory's lines and neighbours stand out")
  @Test
  void connectionsOfSelectedStandOut() throws Exception {
    final List<Integer> pixels = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapProject project = MapProject.newEmpty();
          final BufferedImage picture = MapPictureTest.givenPicture();
          project.loadPicture(picture, MapPicture.detect(picture));
          final MapCanvas canvas = new MapCanvas(project);
          canvas.setSize(400, 300);
          canvas.setMode(MapMode.CONNECTIONS);
          final BufferedImage unselected = shown(canvas);
          canvas.setSelected("Territory 1");
          final BufferedImage selected = shown(canvas);
          final Point from = project.getFiles().centers().get("Territory 1");
          final Point to = project.getFiles().centers().get("Territory 2");
          pixels.add(selected.getRGB((from.x + to.x) / 4, (from.y + to.y) / 4) & 0xFFFFFF);
          // at zoom 0.5: a corner of Territory 2, its neighbour, and of Territory 4, diagonal
          pixels.add(selected.getRGB(190, 5) == unselected.getRGB(190, 5) ? 0 : 1);
          pixels.add(selected.getRGB(190, 105) == unselected.getRGB(190, 105) ? 0 : 1);
        });

    assertThat(pixels.get(0))
        .as("the middle of its line to a neighbour is red, as it is being edited")
        .isEqualTo(Theme.getDangerColor().getRGB() & 0xFFFFFF);
    assertThat(pixels.subList(1, 3))
        .as("its neighbour is tinted, a territory that is none is not")
        .containsExactly(1, 0);
  }

  @DisplayName("The map's background is the same under a dark theme")
  @Test
  void layerSquareFillsTheMapBackground() throws Exception {
    final List<Integer> pixels = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = new MapCanvas(MapProject.newEmpty());
          canvas.setSize(100, 100);
          canvas.setBackground(Color.BLACK);
          final BufferedImage shown = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
          final Graphics2D g = shown.createGraphics();
          canvas.paint(g);
          g.dispose();
          pixels.add(shown.getRGB(50, 50) & 0xFFFFFF);
        });

    assertThat(pixels)
        .as("a map without picture shows the fixed map background, not the theme's")
        .containsExactly(MapCanvas.MAP_BACKGROUND.getRGB() & 0xFFFFFF);
  }
}
