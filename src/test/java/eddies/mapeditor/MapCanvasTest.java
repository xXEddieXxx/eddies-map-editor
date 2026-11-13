package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import lombok.experimental.ExtensionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.map.data.elements.Game;
import org.triplea.map.data.elements.PlayerList;

@ExtensionMethod(Builders.class)
final class MapCanvasTest {
  private static final String EFFECT_LAND = "Territory 1";
  @TempDir Path maps;

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

  /** The project's canvas, painted once, showing units and centers on every territory. */
  private static MapCanvas givenCanvasShowingPlaces(final MapProject project) {
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    final MapCanvas canvas = new MapCanvas(project);
    canvas.setSize(400, 300);
    canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    canvas.showPlaces(
        MapCanvas.ShownPlaces.NONE
            .with(MapCanvas.PlaceKind.UNITS)
            .with(MapCanvas.PlaceKind.CENTERS));
    // a paint takes the unit size the places are drawn in
    shown(canvas);
    return canvas;
  }

  @DisplayName("Places shown on every territory drag there, each move one step, centers too")
  @Test
  void shownPlacesDragOnAnyTerritory() throws Exception {
    // given
    final MapProject project = MapProject.newEmpty();
    final List<Point> before = new ArrayList<>();
    final List<Point> after = new ArrayList<>();
    final List<Boolean> undone = new ArrayList<>();
    final List<Double> zoom = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvasShowingPlaces(project);
          final double z = canvas.getZoom();
          zoom.add(z);
          final Point unit = project.getFiles().unitPlaces(EFFECT_LAND).getFirst();
          final Point center = project.getFiles().centers().get(EFFECT_LAND);
          before.addAll(List.of(unit, center));
          final Point onUnit = new Point((int) (unit.x * z) + 3, (int) (unit.y * z) + 3);
          drag(canvas, onUnit, new Point(onUnit.x + 10, onUnit.y + 6));
          final Point onCenter = new Point((int) (center.x * z), (int) (center.y * z));
          drag(canvas, onCenter, new Point(onCenter.x - 8, onCenter.y + 4));
          after.add(project.getFiles().unitPlaces(EFFECT_LAND).getFirst());
          after.add(project.getFiles().centers().get(EFFECT_LAND));
          project.undo();
          undone.add(project.getFiles().centers().get(EFFECT_LAND).equals(center));
          project.undo();
          undone.add(project.getFiles().unitPlaces(EFFECT_LAND).getFirst().equals(unit));
        });

    // then
    final double z = zoom.getFirst();
    assertThat(after)
        .as("the unselected territory's unit place and center, moved by the drags")
        .containsExactly(
            new Point(
                before.get(0).x + (int) Math.round(10 / z),
                before.get(0).y + (int) Math.round(6 / z)),
            new Point(
                before.get(1).x - (int) Math.round(8 / z),
                before.get(1).y + (int) Math.round(4 / z)));
    assertThat(undone).as("each move undone by one step").containsExactly(true, true);
  }

  @DisplayName("Unit places show their number only while big enough on screen to read")
  @Test
  void placeNumbersOnlyWhenBigEnough() {
    // given: a 48 px unit at 100 % and zoomed out to fit a big map
    final Rectangle full = new Rectangle(0, 0, 48, 48);
    final Rectangle fit = new Rectangle(0, 0, 15, 15);

    // when, then
    assertThat(MapCanvas.isNumbered(full)).as("readable at 100 %").isTrue();
    assertThat(MapCanvas.isNumbered(fit)).as("numbers would run together at fit").isFalse();
  }

  @DisplayName("Faintly marked territories, eg the other canals, show in the mark's color")
  @Test
  void faintMarksShow() throws Exception {
    // given
    final List<Integer> magenta = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          final int before = magentaPixels(shown(canvas));
          canvas.setMarked(Set.of(), Set.of(EFFECT_LAND));
          magenta.add(magentaPixels(shown(canvas)) - before);
        });

    // then
    assertThat(magenta)
        .as("the territory tinted and outlined in the mark's color")
        .allMatch(n -> n > 100);
  }

  /** Pixels clearly more red and blue than green, as the mark's magenta tints them. */
  private static int magentaPixels(final BufferedImage image) {
    int count = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        final Color c = new Color(image.getRGB(x, y));
        if (c.getRed() - c.getGreen() > 40 && c.getBlue() - c.getGreen() > 20) {
          count++;
        }
      }
    }
    return count;
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

  /** The picture map saved and opened again, Territory 1 a Forest, so it has a folder. */
  private MapProject givenForestMap() throws Exception {
    final MapProject made = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    made.loadPicture(picture, MapPicture.detect(picture));
    made.edit(g -> TerritoryXml.withEffect(g, EFFECT_LAND, "Forest"));
    made.saveAsNewMap("Effects", maps);
    return MapProject.open(made.getXmlFile().orElseThrow());
  }

  private static void givenForestPicture(final MapProject project) throws Exception {
    final BufferedImage forest = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
    final Graphics2D g = forest.createGraphics();
    g.setColor(Color.MAGENTA);
    g.fillRect(0, 0, 20, 20);
    g.dispose();
    final Path pictures = project.getFiles().folder().orElseThrow().resolve("territoryEffects");
    Files.createDirectories(pictures);
    ImageIO.write(forest, "png", pictures.resolve("Forest.png").toFile());
  }

  /** Where the effect is drawn on the screen at the canvas' zoom of 0.5, its top left corner. */
  private static Point effectOnScreen(final MapProject project) {
    final Point at = project.getFiles().effectPlaces(EFFECT_LAND, 1).getFirst();
    return new Point(at.x / 2, at.y / 2);
  }

  private static MapCanvas givenCanvas(final MapProject project) {
    final MapCanvas canvas = new MapCanvas(project);
    canvas.setSize(400, 300);
    canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    // the picture's tiles would be read in the background, past the end of the test
    canvas.hidePicture();
    return canvas;
  }

  @DisplayName("With an effect brush the map shows the effect's picture where the game draws it")
  @Test
  void effectBrushShowsEffectPictures() throws Exception {
    // given
    final MapProject project = givenForestMap();
    givenForestPicture(project);
    final List<Integer> pixels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas(project);
          // 20 px at zoom 0.5 would be 10, so it is drawn 14 px, its middle 7 px in
          final Point at = effectOnScreen(project);
          pixels.add(shown(canvas).getRGB(at.x + 7, at.y + 7) & 0xFFFFFF);
          canvas.showEffects(Optional.of("Forest"));
          pixels.add(shown(canvas).getRGB(at.x + 7, at.y + 7) & 0xFFFFFF);
          pixels.add(shown(canvas).getRGB(at.x + 12, at.y + 12) & 0xFFFFFF);
          canvas.hideEffects();
          pixels.add(shown(canvas).getRGB(at.x + 7, at.y + 7) & 0xFFFFFF);
        });

    // then
    assertThat(pixels.get(0)).as("no effects without the brush").isNotEqualTo(0xFF00FF);
    assertThat(pixels.subList(1, 3))
        .as("the picture at the effect's place, at least 14 screen px")
        .containsExactly(0xFF00FF, 0xFF00FF);
    assertThat(pixels.get(3)).as("gone once the brush is left").isNotEqualTo(0xFF00FF);
  }

  @DisplayName("An effect without a picture shows as a dark badge with its letter")
  @Test
  void effectWithoutPictureShowsBadge() throws Exception {
    // given
    final MapProject project = givenForestMap();
    final List<Integer> brightness = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas(project);
          final Point at = effectOnScreen(project);
          // left in the round badge, beside its letter
          brightness.add(brightness(shown(canvas).getRGB(at.x + 3, at.y + 9)));
          canvas.showEffects(Optional.empty());
          brightness.add(brightness(shown(canvas).getRGB(at.x + 3, at.y + 9)));
        });

    // then
    assertThat(brightness.get(1))
        .as("the badge darkens the map where the effect is")
        .isLessThan(brightness.get(0) - 100);
  }

  /** Presses the button at the first point, drags through the others, releases at the last. */
  private static void stroke(final MapCanvas canvas, final int button, final Point... points) {
    final int mask =
        button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
    for (int i = 0; i < points.length; i++) {
      final int id =
          i == 0
              ? MouseEvent.MOUSE_PRESSED
              : i == points.length - 1 ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_DRAGGED;
      canvas.dispatchEvent(
          new MouseEvent(
              canvas,
              id,
              System.currentTimeMillis(),
              id == MouseEvent.MOUSE_RELEASED ? 0 : mask,
              points[i].x,
              points[i].y,
              points[i].x,
              points[i].y,
              1,
              false,
              button));
    }
  }

  @DisplayName("A left drag with a brush paints every territory it passes, once, in one stroke")
  @Test
  void dragPaintsTerritoriesPassed() throws Exception {
    // given
    final List<List<String>> strokes = new ArrayList<>();
    final List<String> clicked = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.onTerritoryClick(clicked::add);
          canvas.paintStrokes(strokes::add);
          // into the right neighbour in one jump, down into the third and back
          stroke(
              canvas,
              MouseEvent.BUTTON1,
              new Point(50, 25),
              new Point(150, 25),
              new Point(150, 80),
              new Point(150, 25),
              new Point(150, 25));
          // a right drag still moves the map
          stroke(
              canvas,
              MouseEvent.BUTTON3,
              new Point(50, 25),
              new Point(150, 80),
              new Point(150, 80));
        });

    // then
    assertThat(strokes).as("one stroke, the right drag none").hasSize(1);
    assertThat(strokes.getFirst())
        .as("three territories, each once")
        .hasSize(3)
        .doesNotHaveDuplicates();
    assertThat(clicked).as("a drag is no click").isEmpty();
  }

  @DisplayName("A click with a brush stays a click; without a brush a drag paints nothing")
  @Test
  void clickIsNoStroke() throws Exception {
    // given
    final List<List<String>> strokes = new ArrayList<>();
    final List<String> clicked = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.onTerritoryClick(clicked::add);
          canvas.paintStrokes(strokes::add);
          click(canvas, new Point(50, 25), 1);
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_CLICKED, new Point(50, 25)));
          canvas.stopEditingPlaces();
          stroke(
              canvas,
              MouseEvent.BUTTON1,
              new Point(50, 25),
              new Point(150, 25),
              new Point(150, 25));
        });

    // then
    assertThat(clicked).as("the click chooses or paints one territory").hasSize(1);
    assertThat(strokes).as("no stroke from a click, nor once painting ended").isEmpty();
  }

  @DisplayName("A circle dragged from corner to corner is given closed; Esc in the drag drops it")
  @Test
  void circleIsClosedAndEscDropsIt() throws Exception {
    // given
    final List<List<Point>> lines = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.drawCircle(lines::add);
          stroke(
              canvas, MouseEvent.BUTTON1, new Point(20, 20), new Point(80, 60), new Point(80, 60));
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, new Point(20, 20)));
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED, new Point(80, 60)));
          press(canvas, KeyEvent.VK_ESCAPE);
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, new Point(80, 60)));
        });

    // then
    assertThat(lines).as("only the first circle").hasSize(1);
    final List<Point> circle = lines.getFirst();
    assertThat(circle.getLast()).as("closed").isEqualTo(circle.getFirst());
    assertThat(circle)
        .as("within its corners, in map px at zoom 0.5")
        .allMatch(p -> p.x >= 40 && p.x <= 160 && p.y >= 40 && p.y <= 120)
        .anyMatch(p -> p.x == 40)
        .anyMatch(p -> p.y == 120);
  }

  @DisplayName("A stroke back to where it began still paints what it passed")
  @Test
  void strokeBackToItsStartPaints() throws Exception {
    // given
    final List<List<String>> strokes = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          canvas.paintStrokes(strokes::add);
          stroke(
              canvas,
              MouseEvent.BUTTON1,
              new Point(50, 25),
              new Point(150, 25),
              new Point(50, 25),
              new Point(50, 25));
        });

    // then
    assertThat(strokes).as("one stroke, though released at its start").hasSize(1);
    assertThat(strokes.getFirst()).as("both territories it passed").hasSize(2);
  }

  @DisplayName("Esc drops a dragged line; the rest of that drag does not move the map")
  @Test
  void escapedLineDragDoesNotPan() throws Exception {
    // given
    final List<Point> views = new ArrayList<>();
    final List<List<Point>> lines = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapCanvas canvas = givenCanvas();
          final JViewport viewport = new JViewport();
          viewport.setView(canvas);
          viewport.setSize(200, 150);
          canvas.setSize(800, 600);
          viewport.setViewPosition(new Point(100, 100));
          canvas.drawLine(lines::add);
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED, new Point(300, 300)));
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED, new Point(310, 310)));
          press(canvas, KeyEvent.VK_ESCAPE);
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED, new Point(350, 350)));
          canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED, new Point(350, 350)));
          views.add(viewport.getViewPosition());
        });

    // then
    assertThat(views).as("the map where it was").containsExactly(new Point(100, 100));
    assertThat(lines).as("no line drawn").isEmpty();
  }

  @DisplayName("Political: the nations of one alliance in shades of its hue, another in its own")
  @Test
  void alliancesInShadesOfTheirHue() {
    // given
    final var game = GameRulesXmlTest.givenG40();

    // when
    final var colors = MapCanvas.allianceColors(game);

    // then
    final float germans = hue(colors.get("Germans"));
    assertThat(colors.get("Germans"))
        .as("two Axis nations told apart")
        .isNotEqualTo(colors.get("Japanese"));
    assertThat(Math.abs(germans - hue(colors.get("Japanese"))))
        .as("but in one hue, as they are one side")
        .isLessThan(0.02f);
    assertThat(Math.abs(germans - hue(colors.get("Russians"))))
        .as("the Allies in another hue")
        .isGreaterThan(0.1f);
  }

  private static float hue(final Color color) {
    return Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null)[0];
  }

  @DisplayName("Political: land of a nation in no alliance is grey as land without an owner")
  @Test
  void noAllianceIsGrey() throws Exception {
    // given
    final List<Integer> pixels = new ArrayList<>();

    // when
    SwingUtilities.invokeAndWait(
        () -> {
          final MapProject project = givenOwnedBy("Loner", "");
          final MapCanvas canvas = givenCanvas(project);
          canvas.setMode(MapMode.POLITICAL);
          pixels.add(shown(canvas).getRGB(50, 25));
          project.edit(game -> MapXml.withOwner(game, EFFECT_LAND, null));
          pixels.add(shown(canvas).getRGB(50, 25));
          project.edit(game -> MapXml.withOwner(game, EFFECT_LAND, "Loner"));
          project.edit(game -> MapXml.withAlliance(game, "Loner", "Axis"));
          pixels.add(shown(canvas).getRGB(50, 25));
        });

    // then
    assertThat(pixels.get(0)).as("no alliance: as no owner").isEqualTo(pixels.get(1));
    assertThat(pixels.get(2))
        .as("in an alliance it has the alliance's color")
        .isNotEqualTo(pixels.get(1));
  }

  /** The test picture's map, Territory 1 owned by a new nation in this alliance, none for blank. */
  private static MapProject givenOwnedBy(final String nation, final String alliance) {
    final MapProject project = MapProject.newEmpty();
    final BufferedImage picture = MapPictureTest.givenPicture();
    project.loadPicture(picture, MapPicture.detect(picture));
    project.edit(
        game ->
            MapXml.withOwner(
                MapXml.withAlliance(MapXml.withNation(game, nation), nation, alliance),
                EFFECT_LAND,
                nation));
    return project;
  }

  @DisplayName("Political: nations of different alliances clearly apart, and apart from the sea")
  @Test
  void allianceColorsClearlyApart() {
    // given
    final Game game = givenAlliancesOfThree(6);
    final List<Double> fromSea = new ArrayList<>();
    final List<Double> acrossAlliances = new ArrayList<>();

    // when
    final var colors = MapCanvas.allianceColors(game);
    for (final String nation : colors.keySet()) {
      fromSea.add(distance(colors.get(nation), MapCanvas.SEA));
      for (final String other : colors.keySet()) {
        if (nation.charAt(0) != other.charAt(0)) {
          acrossAlliances.add(distance(colors.get(nation), colors.get(other)));
        }
      }
    }

    // then
    assertThat(fromSea).as("no side's land reads as sea").allMatch(d -> d > 30);
    assertThat(acrossAlliances)
        .as("no nation looks like one of another side, also in its shades")
        .allMatch(d -> d > 20);
  }

  /** A game with this many alliances "A".."F" of three nations each, eg "A1". */
  private static Game givenAlliancesOfThree(final int alliances) {
    Game game = MapProject.newEmpty().game();
    for (int i = 0; i < alliances; i++) {
      final String alliance = String.valueOf((char) ('A' + i));
      for (int k = 1; k <= 3; k++) {
        game = MapXml.withAlliance(MapXml.withNation(game, alliance + k), alliance + k, alliance);
      }
    }
    return game;
  }

  /** How far apart two colors look: CIE76 in Lab. */
  private static double distance(final Color a, final Color b) {
    final double[] x = lab(a);
    final double[] y = lab(b);
    return Math.sqrt(
        Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
  }

  private static double[] lab(final Color color) {
    final double r = linear(color.getRed());
    final double g = linear(color.getGreen());
    final double b = linear(color.getBlue());
    final double x = labPart((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047);
    final double y = labPart(0.2126 * r + 0.7152 * g + 0.0722 * b);
    final double z = labPart((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883);
    return new double[] {116 * y - 16, 500 * (x - y), 200 * (y - z)};
  }

  private static double linear(final int channel) {
    final double c = channel / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static double labPart(final double v) {
    return v > 0.008856 ? Math.cbrt(v) : 7.787 * v + 16 / 116.0;
  }

  @DisplayName("Political: a nation in two alliances counts in its first, an empty one is left out")
  @Test
  void nationInTwoAlliancesCountsInItsFirst() {
    // given
    final Game game = givenGermansAlsoIn("Allies");

    // when
    final var nations = MapCanvas.allianceNations(game);
    final var colors = MapCanvas.allianceColors(game);

    // then
    assertThat(nations.get("Allies")).as("not listed under its second").doesNotContain("Germans");
    assertThat(nations.get("Axis")).as("listed under its first").contains("Germans");
    assertThat(Math.abs(hue(colors.get("Germans")) - hue(colors.get("Japanese"))))
        .as("in its first alliance's hue")
        .isLessThan(0.02f);
    assertThat(MapCanvas.allianceNations(givenGermansAlsoIn("Neutral")))
        .as("an alliance no nation has first spends no chip and no hue")
        .doesNotContainKey("Neutral");
  }

  /** G40 with the Germans also in this alliance, after their Axis. */
  private static Game givenGermansAlsoIn(final String alliance) {
    final Game game = GameRulesXmlTest.givenG40();
    final List<PlayerList.Alliance> alliances =
        new ArrayList<>(game.getPlayerList().getAlliances());
    alliances.add(PlayerList.Alliance.builder().player("Germans").alliance(alliance).build());
    return game.toBuilder()
        .playerList(game.getPlayerList().toBuilder().alliances(alliances).build())
        .build();
  }
}
